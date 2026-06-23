// Crypto Scalping — Smart Money Concepts / Market Structure (Phase 2a).
//
// Pure analysis over a candle series: swing structure (HH/HL/LH/LL), Break of
// Structure, Change of Character, liquidity sweeps, Fair Value Gaps, order
// blocks, volatility squeeze/expansion and a simple volume profile (POC/VAH/VAL).

use serde::Serialize;

use crate::engine::{compute_atr, HistoricalCandle};

const PIVOT_K: usize = 2; // swing pivot uses ±2 bars

#[derive(Serialize, Clone)]
pub struct Zone {
    pub kind: &'static str, // "BullishFVG" | "BearishFVG" | "BullishOB" | "BearishOB"
    pub low_cents: i64,
    pub high_cents: i64,
    pub at_epoch: i64,
}

#[derive(Serialize, Clone)]
pub struct SmcEvent {
    pub kind: &'static str,      // "BOS" | "CHoCH" | "LiquiditySweep"
    pub direction: &'static str, // "Bullish" | "Bearish"
    pub price_cents: i64,
    pub at_epoch: i64,
}

#[derive(Serialize)]
pub struct VolumeProfile {
    pub poc_cents: i64,
    pub vah_cents: i64,
    pub val_cents: i64,
}

#[derive(Serialize)]
pub struct SmcAnalysis {
    pub tf: String,
    pub structure: &'static str, // "Bullish" | "Bearish" | "Range"
    pub events: Vec<SmcEvent>,
    pub fvgs: Vec<Zone>,
    pub order_blocks: Vec<Zone>,
    pub squeeze: bool,
    pub expansion: bool,
    pub volume_profile: Option<VolumeProfile>,
}

struct Swing { price: i64, epoch: i64, high: bool }

fn swings(c: &[HistoricalCandle]) -> Vec<Swing> {
    let mut out = Vec::new();
    let n = c.len();
    if n < 2 * PIVOT_K + 1 { return out; }
    for i in PIVOT_K..n - PIVOT_K {
        let hi = c[i].high_cents;
        let lo = c[i].low_cents;
        let is_high = (i - PIVOT_K..=i + PIVOT_K).all(|j| c[j].high_cents <= hi)
            && (i - PIVOT_K..i).chain(i + 1..=i + PIVOT_K).any(|j| c[j].high_cents < hi);
        let is_low = (i - PIVOT_K..=i + PIVOT_K).all(|j| c[j].low_cents >= lo)
            && (i - PIVOT_K..i).chain(i + 1..=i + PIVOT_K).any(|j| c[j].low_cents > lo);
        if is_high { out.push(Swing { price: hi, epoch: c[i].epoch_seconds, high: true }); }
        if is_low { out.push(Swing { price: lo, epoch: c[i].epoch_seconds, high: false }); }
    }
    out
}

/// Prevailing structure from the last two swing highs and lows.
fn structure_trend(sw: &[Swing]) -> &'static str {
    let highs: Vec<i64> = sw.iter().filter(|s| s.high).map(|s| s.price).collect();
    let lows: Vec<i64> = sw.iter().filter(|s| !s.high).map(|s| s.price).collect();
    if highs.len() < 2 || lows.len() < 2 { return "Range"; }
    let (h1, h2) = (highs[highs.len() - 2], highs[highs.len() - 1]);
    let (l1, l2) = (lows[lows.len() - 2], lows[lows.len() - 1]);
    if h2 > h1 && l2 > l1 { "Bullish" }
    else if h2 < h1 && l2 < l1 { "Bearish" }
    else { "Range" }
}

/// BOS / CHoCH from the latest close breaking the most recent swing high/low.
fn break_event(c: &[HistoricalCandle], sw: &[Swing], trend: &str) -> Option<SmcEvent> {
    let last = c.last()?;
    let last_high = sw.iter().rev().find(|s| s.high)?;
    let last_low = sw.iter().rev().find(|s| !s.high)?;
    if last.close_cents > last_high.price {
        let kind = if trend == "Bullish" { "BOS" } else { "CHoCH" };
        return Some(SmcEvent { kind, direction: "Bullish", price_cents: last_high.price, at_epoch: last.epoch_seconds });
    }
    if last.close_cents < last_low.price {
        let kind = if trend == "Bearish" { "BOS" } else { "CHoCH" };
        return Some(SmcEvent { kind, direction: "Bearish", price_cents: last_low.price, at_epoch: last.epoch_seconds });
    }
    None
}

/// Liquidity sweep: a wick takes out a recent swing then closes back inside.
fn liquidity_sweep(c: &[HistoricalCandle], sw: &[Swing]) -> Option<SmcEvent> {
    let last_high = sw.iter().rev().find(|s| s.high).map(|s| s.price);
    let last_low = sw.iter().rev().find(|s| !s.high).map(|s| s.price);
    for x in c.iter().rev().take(3) {
        if let Some(lo) = last_low {
            if x.low_cents < lo && x.close_cents > lo {
                return Some(SmcEvent { kind: "LiquiditySweep", direction: "Bullish", price_cents: lo, at_epoch: x.epoch_seconds });
            }
        }
        if let Some(hi) = last_high {
            if x.high_cents > hi && x.close_cents < hi {
                return Some(SmcEvent { kind: "LiquiditySweep", direction: "Bearish", price_cents: hi, at_epoch: x.epoch_seconds });
            }
        }
    }
    None
}

/// Unfilled Fair Value Gaps (3-candle imbalance) in the recent window.
fn fair_value_gaps(c: &[HistoricalCandle]) -> Vec<Zone> {
    let n = c.len();
    let mut out = Vec::new();
    let start = n.saturating_sub(60);
    for i in (start + 2)..n {
        // Bullish FVG: gap between candle[i-2].high and candle[i].low.
        if c[i - 2].high_cents < c[i].low_cents {
            let (lo, hi) = (c[i - 2].high_cents, c[i].low_cents);
            let filled = c[i + 1..].iter().any(|x| x.low_cents <= lo);
            if !filled { out.push(Zone { kind: "BullishFVG", low_cents: lo, high_cents: hi, at_epoch: c[i].epoch_seconds }); }
        }
        // Bearish FVG: gap between candle[i].high and candle[i-2].low.
        if c[i - 2].low_cents > c[i].high_cents {
            let (lo, hi) = (c[i].high_cents, c[i - 2].low_cents);
            let filled = c[i + 1..].iter().any(|x| x.high_cents >= hi);
            if !filled { out.push(Zone { kind: "BearishFVG", low_cents: lo, high_cents: hi, at_epoch: c[i].epoch_seconds }); }
        }
    }
    out.reverse(); // most recent first
    out.truncate(3);
    out
}

/// Order blocks: last opposite-color candle before an impulsive (>1.5×ATR) move.
fn order_blocks(c: &[HistoricalCandle], atr: i64) -> Vec<Zone> {
    let n = c.len();
    let mut out = Vec::new();
    if atr <= 0 || n < 5 { return out; }
    let start = n.saturating_sub(30);
    for i in (start + 1)..n {
        let body = (c[i].close_cents - c[i].open_cents).abs();
        if body <= (atr as f64 * 1.5) as i64 { continue; }
        let up = c[i].close_cents > c[i].open_cents;
        // walk back to the last opposite-color candle = the order block.
        for j in (start..i).rev() {
            let ob_up = c[j].close_cents > c[j].open_cents;
            if ob_up != up {
                let kind = if up { "BullishOB" } else { "BearishOB" };
                out.push(Zone { kind, low_cents: c[j].low_cents, high_cents: c[j].high_cents, at_epoch: c[j].epoch_seconds });
                break;
            }
        }
    }
    out.reverse();
    out.truncate(2);
    out
}

/// Bollinger(20,2) inside Keltner(20, 1.5×ATR) → squeeze; outside → expansion.
fn squeeze_state(c: &[HistoricalCandle]) -> (bool, bool) {
    let n = c.len();
    if n < 21 { return (false, false); }
    let w = &c[n - 20..];
    let closes: Vec<f64> = w.iter().map(|x| x.close_cents as f64).collect();
    let mean = closes.iter().sum::<f64>() / 20.0;
    let var = closes.iter().map(|x| (x - mean).powi(2)).sum::<f64>() / 20.0;
    let sd = var.sqrt();
    let (bb_up, bb_lo) = (mean + 2.0 * sd, mean - 2.0 * sd);
    let atr = compute_atr(c, 20).unwrap_or(0) as f64;
    let (kc_up, kc_lo) = (mean + 1.5 * atr, mean - 1.5 * atr);
    let squeeze = bb_up < kc_up && bb_lo > kc_lo;
    let expansion = bb_up > kc_up && bb_lo < kc_lo;
    (squeeze, expansion)
}

fn volume_profile(c: &[HistoricalCandle]) -> Option<VolumeProfile> {
    let lo = c.iter().map(|x| x.low_cents).min()?;
    let hi = c.iter().map(|x| x.high_cents).max()?;
    if hi <= lo { return None; }
    let bins = 24usize;
    let width = (hi - lo) as f64 / bins as f64;
    let mut vol = vec![0f64; bins];
    for x in c {
        let mid = (x.high_cents + x.low_cents) / 2;
        let mut b = (((mid - lo) as f64) / width) as usize;
        if b >= bins { b = bins - 1; }
        vol[b] += x.volume.max(1) as f64;
    }
    let total: f64 = vol.iter().sum();
    if total <= 0.0 { return None; }
    let poc_bin = (0..bins).max_by(|&a, &b| vol[a].partial_cmp(&vol[b]).unwrap())?;
    let bin_price = |b: usize| lo + (width * (b as f64 + 0.5)) as i64;
    // Expand a value area around the POC until it covers ~70% of volume.
    let (mut lo_b, mut hi_b, mut acc) = (poc_bin, poc_bin, vol[poc_bin]);
    while acc < total * 0.7 && (lo_b > 0 || hi_b < bins - 1) {
        let down = if lo_b > 0 { vol[lo_b - 1] } else { -1.0 };
        let up = if hi_b < bins - 1 { vol[hi_b + 1] } else { -1.0 };
        if up >= down { hi_b += 1; acc += vol[hi_b]; } else { lo_b -= 1; acc += vol[lo_b]; }
    }
    Some(VolumeProfile { poc_cents: bin_price(poc_bin), vah_cents: bin_price(hi_b), val_cents: bin_price(lo_b) })
}

/// Run the full SMC analysis on a candle series for timeframe `tf`.
pub fn analyze(tf: &str, candles: &[HistoricalCandle]) -> SmcAnalysis {
    // Focus detectors on the recent window for relevance.
    let n = candles.len();
    let win = &candles[n.saturating_sub(120)..];
    let sw = swings(win);
    let structure = structure_trend(&sw);
    let mut events = Vec::new();
    if let Some(e) = break_event(win, &sw, structure) { events.push(e); }
    if let Some(e) = liquidity_sweep(win, &sw) { events.push(e); }
    let atr = compute_atr(win, 14).unwrap_or(0);
    let (squeeze, expansion) = squeeze_state(win);

    SmcAnalysis {
        tf: tf.to_string(),
        structure,
        events,
        fvgs: fair_value_gaps(win),
        order_blocks: order_blocks(win, atr),
        squeeze,
        expansion,
        volume_profile: volume_profile(win),
    }
}
