// Market-regime engine: tells you whether the *environment* is friendly before
// you act on any single-stock signal. ~70% of a stock's move is the market and
// its sector, not the company — so a Strong Buy in a risk-off tape is still a
// fight against the current.
//
// Three free, robust inputs:
//   1. VIX        — the market's fear gauge (Yahoo ^VIX).
//   2. Breadth    — % of S&P names trading above their 200-day MA (from cache).
//   3. SPY trend  — index above/below its own 200-day MA (from cache).

use serde::Serialize;
use tauri::State;

use crate::fetcher::{is_crypto, is_etf, YahooClient};
use crate::state::AppState;

#[derive(Serialize)]
pub struct MarketRegime {
    /// "RiskOn" | "Neutral" | "RiskOff" | "Unknown"
    pub regime: &'static str,
    /// Suggested equity exposure ceiling for this regime, in percent.
    pub suggested_exposure_pct: u32,

    /// VIX level (points, e.g. 18.4). None if the fetch failed.
    pub vix: Option<f64>,
    pub vix_state: &'static str, // "Calm" | "Normal" | "Elevated" | "Fear" | "Unknown"

    /// % of S&P constituents above their 200-day MA (0..100).
    pub breadth_above_ma200_pct: Option<f64>,
    /// % above their 50-day MA (faster breadth).
    pub breadth_above_ma50_pct: Option<f64>,
    pub breadth_sample: usize,

    /// Is SPY above its own 200-day MA?
    pub spy_above_ma200: Option<bool>,
    pub spy_price_cents: Option<i64>,
    pub spy_ma200_cents: Option<i64>,

    /// Plain-language reasons behind the verdict.
    pub notes_es: Vec<String>,
    pub notes_en: Vec<String>,
}

fn vix_state(vix: f64) -> &'static str {
    if vix < 15.0 {
        "Calm"
    } else if vix < 20.0 {
        "Normal"
    } else if vix < 28.0 {
        "Elevated"
    } else {
        "Fear"
    }
}

/// Compute the current market regime from cached technicals + a live VIX quote.
#[tauri::command]
pub fn get_market_regime(state: State<AppState>) -> Result<MarketRegime, String> {
    // ── Breadth + SPY trend from the in-memory cache (zero extra network) ─────
    let (breadth200, breadth50, sample, spy_price, spy_ma200) = {
        let screener = state.screener.lock().map_err(|_| "screener lock")?;
        let mut above200 = 0usize;
        let mut have200 = 0usize;
        let mut above50 = 0usize;
        let mut have50 = 0usize;
        for (sym, cs) in screener.chart_summaries.iter() {
            // Equities only — breadth is an S&P concept, not crypto/ETF.
            if is_crypto(sym) || is_etf(sym) {
                continue;
            }
            let price = cs.latest_close_cents;
            if price <= 0 {
                continue;
            }
            if let Some(ma) = cs.ema200_cents {
                have200 += 1;
                if price > ma {
                    above200 += 1;
                }
            }
            if let Some(ma) = cs.ema50_cents {
                have50 += 1;
                if price > ma {
                    above50 += 1;
                }
            }
        }
        let b200 = if have200 > 0 {
            Some(above200 as f64 / have200 as f64 * 100.0)
        } else {
            None
        };
        let b50 = if have50 > 0 {
            Some(above50 as f64 / have50 as f64 * 100.0)
        } else {
            None
        };
        let spy = screener.chart_summaries.get("SPY");
        let spy_price = spy.map(|s| s.latest_close_cents).filter(|p| *p > 0);
        let spy_ma200 = spy.and_then(|s| s.ema200_cents);
        (b200, b50, have200, spy_price, spy_ma200)
    };

    let spy_above_ma200 = match (spy_price, spy_ma200) {
        (Some(p), Some(m)) if m > 0 => Some(p > m),
        _ => None,
    };

    // ── VIX (live) ────────────────────────────────────────────────────────────
    let vix = YahooClient::new()
        .ok()
        .and_then(|c| c.fetch_candles("^VIX", "5d", "1d").ok())
        .and_then(|candles| candles.last().map(|c| c.close_cents as f64 / 100.0))
        .filter(|v| *v > 0.0);

    // ── Compose the verdict ─────────────────────────────────────────────────────
    // Score each axis -1 (hostile) .. +1 (supportive), then bucket.
    let mut score = 0i32;
    let mut axes = 0i32;
    let mut notes_es: Vec<String> = Vec::new();
    let mut notes_en: Vec<String> = Vec::new();

    if let Some(v) = vix {
        axes += 1;
        if v < 18.0 {
            score += 1;
        } else if v > 26.0 {
            score -= 1;
        }
        notes_es.push(format!(
            "VIX en {:.1} ({})",
            v,
            match vix_state(v) {
                "Calm" => "calma",
                "Normal" => "normal",
                "Elevated" => "elevado",
                _ => "miedo",
            }
        ));
        notes_en.push(format!("VIX at {:.1} ({})", v, vix_state(v).to_lowercase()));
    }
    if let Some(b) = breadth200 {
        axes += 1;
        if b > 55.0 {
            score += 1;
        } else if b < 40.0 {
            score -= 1;
        }
        notes_es.push(format!("{:.0}% de las acciones sobre su MA200", b));
        notes_en.push(format!("{:.0}% of stocks above their 200-day MA", b));
    }
    if let Some(up) = spy_above_ma200 {
        axes += 1;
        if up {
            score += 1;
        } else {
            score -= 1;
        }
        notes_es.push(if up {
            "S&P 500 sobre su MA200 (tendencia alcista)".into()
        } else {
            "S&P 500 bajo su MA200 (tendencia bajista)".into()
        });
        notes_en.push(if up {
            "S&P 500 above its 200-day MA (uptrend)".into()
        } else {
            "S&P 500 below its 200-day MA (downtrend)".into()
        });
    }

    let (regime, exposure): (&'static str, u32) = if axes == 0 {
        ("Unknown", 60)
    } else if score >= 2 {
        ("RiskOn", 100)
    } else if score <= -2 {
        ("RiskOff", 30)
    } else {
        ("Neutral", 60)
    };

    Ok(MarketRegime {
        regime,
        suggested_exposure_pct: exposure,
        vix,
        vix_state: vix.map(vix_state).unwrap_or("Unknown"),
        breadth_above_ma200_pct: breadth200,
        breadth_above_ma50_pct: breadth50,
        breadth_sample: sample,
        spy_above_ma200,
        spy_price_cents: spy_price,
        spy_ma200_cents: spy_ma200,
        notes_es,
        notes_en,
    })
}
