// Risk engine: volatility (ATR) + real correlation matrix for a set of holdings.
//
// The frontend already knows quantities, prices and avg cost, so it can derive
// position sizing, stops and weights with simple arithmetic. What it CANNOT do
// cheaply in JS is fetch price history and compute ATR / pairwise correlation —
// that lives here.

use std::collections::BTreeMap;

use serde::Serialize;
use tauri::State;

use crate::engine::{compute_atr, HistoricalCandle};
use crate::state::AppState;

/// How many of the most recent daily candles feed the correlation window.
const CORR_LOOKBACK: usize = 120;
/// Minimum overlapping days required to report a correlation for a pair.
const CORR_MIN_OVERLAP: usize = 30;
/// |corr| at or above this is flagged as "hidden concentration".
const HIGH_CORR_MILLI: i32 = 700;

#[derive(Serialize)]
pub struct SymbolRisk {
    pub symbol: String,
    pub last_close_cents: i64,
    pub atr_cents: Option<i64>,
    /// ATR as basis points of price — a daily-volatility proxy comparable across symbols.
    pub atr_pct_bps: Option<i32>,
}

#[derive(Serialize, Clone)]
pub struct CorrPair {
    pub a: String,
    pub b: String,
    /// Pearson correlation × 1000, range -1000..1000.
    pub corr_milli: i32,
}

#[derive(Serialize)]
pub struct PortfolioRiskResponse {
    pub per_symbol: Vec<SymbolRisk>,
    pub correlation: Vec<CorrPair>,
    /// Subset of `correlation` with |corr| >= 0.70 — diversification you don't actually have.
    pub high_corr_pairs: Vec<CorrPair>,
    pub lookback_days: usize,
}

/// Pearson correlation over aligned return series. None if too little overlap or no variance.
fn pearson(xs: &[f64], ys: &[f64]) -> Option<f64> {
    let n = xs.len();
    if n < CORR_MIN_OVERLAP {
        return None;
    }
    let mean_x = xs.iter().sum::<f64>() / n as f64;
    let mean_y = ys.iter().sum::<f64>() / n as f64;
    let (mut cov, mut vx, mut vy) = (0.0f64, 0.0f64, 0.0f64);
    for i in 0..n {
        let dx = xs[i] - mean_x;
        let dy = ys[i] - mean_y;
        cov += dx * dy;
        vx += dx * dx;
        vy += dy * dy;
    }
    if vx <= 0.0 || vy <= 0.0 {
        return None;
    }
    Some(cov / (vx.sqrt() * vy.sqrt()))
}

/// Daily simple returns keyed by calendar day (epoch/86400) so different fetches align.
fn daily_returns(candles: &[HistoricalCandle]) -> BTreeMap<i64, f64> {
    let mut out = BTreeMap::new();
    let start = candles.len().saturating_sub(CORR_LOOKBACK + 1);
    let window = &candles[start..];
    for pair in window.windows(2) {
        let prev = pair[0].close_cents;
        let cur = pair[1].close_cents;
        if prev > 0 {
            let day = pair[1].epoch_seconds / 86_400;
            out.insert(day, (cur - prev) as f64 / prev as f64);
        }
    }
    out
}

/// Compute ATR + pairwise correlation for the given holdings.
/// Reuses cached daily candles from the screener and fetches the rest on demand.
#[tauri::command]
pub async fn get_portfolio_risk(
    symbols: Vec<String>,
    state: State<'_, AppState>,
) -> Result<PortfolioRiskResponse, String> {
    // Heavy (network + correlation math) → run off the UI thread.
    let screener_arc = state.screener.clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<PortfolioRiskResponse, String> {
        // Normalize + dedupe, preserving order.
        let mut syms: Vec<String> = Vec::new();
        for s in symbols {
            let k = s.trim().to_uppercase();
            if !k.is_empty() && !syms.contains(&k) {
                syms.push(k);
            }
        }

        // Gather candles: cache first, fetch what's missing.
        let mut candles_by_sym: BTreeMap<String, Vec<HistoricalCandle>> = BTreeMap::new();
        let mut missing: Vec<String> = Vec::new();
        {
            let screener = screener_arc.lock().map_err(|_| "screener lock")?;
            for sym in &syms {
                match screener.daily_candles.get(sym) {
                    Some(c) if c.len() >= CORR_MIN_OVERLAP + 1 => {
                        candles_by_sym.insert(sym.clone(), c.clone());
                    }
                    _ => missing.push(sym.clone()),
                }
            }
        }
        if !missing.is_empty() {
            let client = crate::fetcher::YahooClient::new().map_err(|e| e.to_string())?;
            for sym in &missing {
                if let Ok(c) = client.fetch_candles(sym, "1y", "1d") {
                    if c.len() >= 2 {
                        candles_by_sym.insert(sym.clone(), c);
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(150));
            }
        }

        // Per-symbol ATR + last close.
        let mut per_symbol = Vec::with_capacity(syms.len());
        for sym in &syms {
            let (last_close_cents, atr_cents) = match candles_by_sym.get(sym) {
                Some(c) => {
                    let last = c.last().map(|x| x.close_cents).unwrap_or(0);
                    (last, compute_atr(c, 14))
                }
                None => (0, None),
            };
            let atr_pct_bps = match (atr_cents, last_close_cents) {
                (Some(a), p) if p > 0 => Some(((a as f64 / p as f64) * 10_000.0).round() as i32),
                _ => None,
            };
            per_symbol.push(SymbolRisk {
                symbol: sym.clone(),
                last_close_cents,
                atr_cents,
                atr_pct_bps,
            });
        }

        // Pairwise correlation over aligned daily returns.
        let returns: BTreeMap<String, BTreeMap<i64, f64>> = candles_by_sym
            .iter()
            .map(|(s, c)| (s.clone(), daily_returns(c)))
            .collect();

        let mut correlation = Vec::new();
        let mut high_corr_pairs = Vec::new();
        for i in 0..syms.len() {
            for j in (i + 1)..syms.len() {
                let (sa, sb) = (&syms[i], &syms[j]);
                let (ra, rb) = match (returns.get(sa), returns.get(sb)) {
                    (Some(a), Some(b)) => (a, b),
                    _ => continue,
                };
                // Intersect on common days.
                let (mut xs, mut ys) = (Vec::new(), Vec::new());
                for (day, va) in ra {
                    if let Some(vb) = rb.get(day) {
                        xs.push(*va);
                        ys.push(*vb);
                    }
                }
                if let Some(c) = pearson(&xs, &ys) {
                    let pair = CorrPair {
                        a: sa.clone(),
                        b: sb.clone(),
                        corr_milli: (c * 1000.0).round() as i32,
                    };
                    if pair.corr_milli.abs() >= HIGH_CORR_MILLI {
                        high_corr_pairs.push(pair.clone());
                    }
                    correlation.push(pair);
                }
            }
        }
        high_corr_pairs.sort_by(|a, b| b.corr_milli.abs().cmp(&a.corr_milli.abs()));

        Ok(PortfolioRiskResponse {
            per_symbol,
            correlation,
            high_corr_pairs,
            lookback_days: CORR_LOOKBACK,
        })
    })
    .await
    .map_err(|e| e.to_string())?
}
