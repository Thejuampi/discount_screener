use crate::engine::HistoricalCandle;
use crate::fetcher::YahooClient;
use serde::Serialize;
/// Backtest framework for congressional trades + politician metrics aggregation.
///
/// For each trade we compute forward returns from the **disclosure date** (not
/// the transaction date) — this is the actionable date for any replication
/// strategy, and avoids lookahead bias.
///
/// We benchmark against SPY to compute alpha.
use std::collections::HashMap;

const HORIZONS_DAYS: [(i64, &str); 4] = [(5, "5d"), (30, "30d"), (90, "90d"), (180, "180d")];

/// Result of a forward-return computation for one trade.
#[derive(Clone, Debug, Default, Serialize)]
pub struct TradeOutcome {
    pub trade_id: i64,
    pub base_price_cents: Option<i64>,
    pub price_5d_cents: Option<i64>,
    pub price_30d_cents: Option<i64>,
    pub price_90d_cents: Option<i64>,
    pub price_180d_cents: Option<i64>,
    pub return_5d_bps: Option<i32>,
    pub return_30d_bps: Option<i32>,
    pub return_90d_bps: Option<i32>,
    pub return_180d_bps: Option<i32>,
    pub spy_return_5d_bps: Option<i32>,
    pub spy_return_30d_bps: Option<i32>,
    pub spy_return_90d_bps: Option<i32>,
    pub spy_return_180d_bps: Option<i32>,
    pub estimated_gain_180d_cents: Option<i64>,
}

/// Aggregated metrics for a single politician.
#[derive(Clone, Debug, Serialize)]
pub struct PoliticianMetrics {
    pub politician_id: i64,
    pub total_trades: u32,
    pub purchase_count: u32,
    pub sale_count: u32,
    pub avg_return_30d_bps: Option<i32>,
    pub avg_return_90d_bps: Option<i32>,
    pub avg_return_180d_bps: Option<i32>,
    pub win_rate_30d_pct: Option<i32>,
    pub win_rate_90d_pct: Option<i32>,
    pub win_rate_180d_pct: Option<i32>,
    pub avg_alpha_90d_bps: Option<i32>,
    pub avg_alpha_180d_bps: Option<i32>,
    pub estimated_total_gain_cents: i64,
    pub confidence_score: u32,  // 0-100
    pub qualifying_trades: u32, // trades with full 90-day window
}

// ── Date helpers ─────────────────────────────────────────────────────────────

/// Parse ISO "YYYY-MM-DD" → epoch seconds at 00:00 UTC.
pub fn iso_to_epoch(iso: &str) -> Option<i64> {
    let parts: Vec<&str> = iso.split('-').collect();
    if parts.len() != 3 {
        return None;
    }
    let y: i32 = parts[0].parse().ok()?;
    let m: u32 = parts[1].parse().ok()?;
    let d: u32 = parts[2].parse().ok()?;
    Some(civil_to_epoch(y, m, d))
}

fn civil_to_epoch(y: i32, m: u32, d: u32) -> i64 {
    let y = y as i64 - if m <= 2 { 1 } else { 0 };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = (y - era * 400) as i64;
    let doy = (153 * (if m > 2 { m as i64 - 3 } else { m as i64 + 9 }) + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    days * 86_400
}

// ── Price lookup helpers ────────────────────────────────────────────────────

/// Find the closing price on or after a given target epoch.
/// Returns the close in cents, or None if the candles end before the target.
fn price_on_or_after(candles: &[HistoricalCandle], target_epoch: i64) -> Option<i64> {
    candles
        .iter()
        .find(|c| c.epoch_seconds >= target_epoch)
        .map(|c| c.close_cents)
}

/// Find the closing price on or BEFORE a target epoch (most recent close).
/// Useful for base_price when disclosure date might land on a weekend.
fn price_on_or_before(candles: &[HistoricalCandle], target_epoch: i64) -> Option<i64> {
    let mut last: Option<i64> = None;
    for c in candles {
        if c.epoch_seconds > target_epoch {
            break;
        }
        last = Some(c.close_cents);
    }
    last
}

fn return_bps(base: i64, end: i64) -> i32 {
    if base <= 0 {
        return 0;
    }
    ((end - base) as f64 / base as f64 * 10_000.0).round() as i32
}

// ── Outcome computation ─────────────────────────────────────────────────────

/// Compute a single trade's forward-return outcome given the symbol's candles
/// and SPY's candles.
pub fn compute_outcome(
    trade_id: i64,
    disclosure_date_iso: &str,
    candles: &[HistoricalCandle],
    spy_candles: &[HistoricalCandle],
    transaction_type: &str,
    amount_mid_dollars: i64,
) -> TradeOutcome {
    let mut out = TradeOutcome {
        trade_id,
        ..Default::default()
    };

    let Some(disc_epoch) = iso_to_epoch(disclosure_date_iso) else {
        return out;
    };

    let base =
        price_on_or_before(candles, disc_epoch).or_else(|| price_on_or_after(candles, disc_epoch));
    let spy_base = price_on_or_before(spy_candles, disc_epoch)
        .or_else(|| price_on_or_after(spy_candles, disc_epoch));
    out.base_price_cents = base;

    if let (Some(base), Some(spy_base)) = (base, spy_base) {
        for (days, _) in HORIZONS_DAYS.iter() {
            let target = disc_epoch + days * 86_400;
            let p_target = price_on_or_after(candles, target);
            let spy_target = price_on_or_after(spy_candles, target);

            if let Some(p) = p_target {
                let r = return_bps(base, p);
                match days {
                    5 => {
                        out.price_5d_cents = Some(p);
                        out.return_5d_bps = Some(r);
                    }
                    30 => {
                        out.price_30d_cents = Some(p);
                        out.return_30d_bps = Some(r);
                    }
                    90 => {
                        out.price_90d_cents = Some(p);
                        out.return_90d_bps = Some(r);
                    }
                    180 => {
                        out.price_180d_cents = Some(p);
                        out.return_180d_bps = Some(r);
                    }
                    _ => {}
                }
            }
            if let Some(sp) = spy_target {
                let r = return_bps(spy_base, sp);
                match days {
                    5 => out.spy_return_5d_bps = Some(r),
                    30 => out.spy_return_30d_bps = Some(r),
                    90 => out.spy_return_90d_bps = Some(r),
                    180 => out.spy_return_180d_bps = Some(r),
                    _ => {}
                }
            }
        }

        // Estimated gain over the longest horizon we have.
        // For purchases: gain ≈ amount × return_pct
        // For sales: we don't estimate gain (a sale closes a position, not opens one).
        if transaction_type == "P" {
            if let Some(r) = out
                .return_180d_bps
                .or(out.return_90d_bps)
                .or(out.return_30d_bps)
            {
                let gain = (amount_mid_dollars as f64 * r as f64 / 10_000.0).round() as i64;
                out.estimated_gain_180d_cents = Some(gain * 100); // cents
            }
        }
    }

    out
}

// ── Politician aggregation ──────────────────────────────────────────────────

/// Aggregate outcomes into per-politician metrics.
pub fn aggregate_metrics(
    politician_id: i64,
    outcomes_with_meta: &[OutcomeForAggregation],
) -> PoliticianMetrics {
    let total = outcomes_with_meta.len() as u32;
    let purchases = outcomes_with_meta.iter().filter(|o| o.is_purchase).count() as u32;
    let sales = total - purchases;

    fn mean(vals: &[i32]) -> Option<i32> {
        if vals.is_empty() {
            return None;
        }
        Some((vals.iter().map(|&v| v as i64).sum::<i64>() / vals.len() as i64) as i32)
    }
    fn win_rate(vals: &[i32]) -> Option<i32> {
        if vals.is_empty() {
            return None;
        }
        let wins = vals.iter().filter(|&&v| v > 0).count();
        Some(((wins * 100) / vals.len()) as i32)
    }

    // Only count purchases for return metrics (sales don't have a meaningful "return").
    let r30: Vec<i32> = outcomes_with_meta
        .iter()
        .filter(|o| o.is_purchase)
        .filter_map(|o| o.outcome.return_30d_bps)
        .collect();
    let r90: Vec<i32> = outcomes_with_meta
        .iter()
        .filter(|o| o.is_purchase)
        .filter_map(|o| o.outcome.return_90d_bps)
        .collect();
    let r180: Vec<i32> = outcomes_with_meta
        .iter()
        .filter(|o| o.is_purchase)
        .filter_map(|o| o.outcome.return_180d_bps)
        .collect();

    // Alpha = return - SPY return at same horizon
    let alpha_90: Vec<i32> = outcomes_with_meta
        .iter()
        .filter(|o| o.is_purchase)
        .filter_map(|o| Some(o.outcome.return_90d_bps? - o.outcome.spy_return_90d_bps.unwrap_or(0)))
        .collect();
    let alpha_180: Vec<i32> = outcomes_with_meta
        .iter()
        .filter(|o| o.is_purchase)
        .filter_map(|o| {
            Some(o.outcome.return_180d_bps? - o.outcome.spy_return_180d_bps.unwrap_or(0))
        })
        .collect();

    let estimated_gain: i64 = outcomes_with_meta
        .iter()
        .filter_map(|o| o.outcome.estimated_gain_180d_cents)
        .sum();

    let qualifying = r90.len() as u32;

    // Simple confidence score based on sample size — be honest about small samples.
    //   0  trades  → 0
    //   <5 trades  → very low (10)
    //   5-15       → low (30)
    //   15-30      → medium (50)
    //   30-60      → high (70)
    //   60+        → very high (90)
    let confidence = match qualifying {
        0 => 0,
        1..=4 => 10,
        5..=14 => 30,
        15..=29 => 50,
        30..=59 => 70,
        _ => 90,
    };

    PoliticianMetrics {
        politician_id,
        total_trades: total,
        purchase_count: purchases,
        sale_count: sales,
        avg_return_30d_bps: mean(&r30),
        avg_return_90d_bps: mean(&r90),
        avg_return_180d_bps: mean(&r180),
        win_rate_30d_pct: win_rate(&r30),
        win_rate_90d_pct: win_rate(&r90),
        win_rate_180d_pct: win_rate(&r180),
        avg_alpha_90d_bps: mean(&alpha_90),
        avg_alpha_180d_bps: mean(&alpha_180),
        estimated_total_gain_cents: estimated_gain,
        confidence_score: confidence,
        qualifying_trades: qualifying,
    }
}

pub struct OutcomeForAggregation {
    pub outcome: TradeOutcome,
    pub is_purchase: bool,
}

// ── Yahoo candles fetch helper ──────────────────────────────────────────────

/// Fetch enough historical candles to cover all trades for one symbol.
/// We use Yahoo's 5y/1d range (plenty for our backtest window).
pub fn fetch_history(client: &YahooClient, symbol: &str) -> Option<Vec<HistoricalCandle>> {
    client.fetch_candles(symbol, "5y", "1d").ok()
}
