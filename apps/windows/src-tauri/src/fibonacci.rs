// Fibonacci retracement + extension levels from the dominant recent swing.
//
// Convention (TradingView-style): the swing is anchored by the window's extreme
// high and low; direction is set by whichever extreme is more recent. Levels are
// the classic ratios, plus extensions beyond the impulse.

use serde::Serialize;

use crate::engine::HistoricalCandle;

#[derive(Serialize, Clone, Debug)]
pub struct FibLevel {
    pub ratio: f64, // 0.0 .. 1.618
    pub price_cents: i64,
    pub kind: &'static str, // "anchor" | "retracement" | "extension"
}

#[derive(Serialize, Clone, Debug)]
pub struct FibAnalysis {
    pub direction: &'static str, // "Up" | "Down" (impulse direction)
    pub timeframe: String,
    pub swing_high_cents: i64,
    pub swing_low_cents: i64,
    pub swing_high_epoch: i64,
    pub swing_low_epoch: i64,
    pub levels: Vec<FibLevel>,
}

const RETR: [f64; 5] = [0.236, 0.382, 0.5, 0.618, 0.786];
const EXT: [f64; 2] = [1.272, 1.618];

/// Compute fib levels over the recent window. None if the range is degenerate.
pub fn analyze(c: &[HistoricalCandle], tf: &str) -> Option<FibAnalysis> {
    let n = c.len();
    if n < 20 {
        return None;
    }
    let w = &c[n.saturating_sub(120)..];
    let (mut hi_i, mut lo_i) = (0usize, 0usize);
    for (i, k) in w.iter().enumerate() {
        if k.high_cents > w[hi_i].high_cents {
            hi_i = i;
        }
        if k.low_cents < w[lo_i].low_cents {
            lo_i = i;
        }
    }
    let high = w[hi_i].high_cents;
    let low = w[lo_i].low_cents;
    let range = (high - low) as f64;
    if range <= 0.0 {
        return None;
    }
    let up = hi_i > lo_i; // most recent extreme is the high → up impulse
    let (hf, lf) = (high as f64, low as f64);

    let mut levels: Vec<FibLevel> = Vec::new();
    let mut add = |ratio: f64, price: f64, kind: &'static str| {
        levels.push(FibLevel {
            ratio,
            price_cents: price.round() as i64,
            kind,
        });
    };
    if up {
        add(0.0, hf, "anchor");
        for &r in &RETR {
            add(r, hf - r * range, "retracement");
        }
        add(1.0, lf, "anchor");
        for &r in &EXT {
            add(r, hf + (r - 1.0) * range, "extension");
        }
    } else {
        add(0.0, lf, "anchor");
        for &r in &RETR {
            add(r, lf + r * range, "retracement");
        }
        add(1.0, hf, "anchor");
        for &r in &EXT {
            add(r, lf - (r - 1.0) * range, "extension");
        }
    }

    Some(FibAnalysis {
        direction: if up { "Up" } else { "Down" },
        timeframe: tf.to_string(),
        swing_high_cents: high,
        swing_low_cents: low,
        swing_high_epoch: w[hi_i].epoch_seconds,
        swing_low_epoch: w[lo_i].epoch_seconds,
        levels,
    })
}
