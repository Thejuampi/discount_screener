//! Android `ChartAnalysis.buildSummary` + `aggressiveV3TechnicalScore`, ported
//! for the headless Android-semantics path.
//!
//! The desktop `compute_chart_summary` is a different indicator set (classic RSI,
//! ratio volume, …); Android V3 technicals are only reproducible from Android's
//! own summary, so this module ports the fields that bucket reads. Rounding
//! follows Kotlin: `ChartAnalysis` rounds half-to-even (`kotlin.math.round`),
//! the score accumulator rounds half-up (`roundToInt`).

use serde::{Deserialize, Serialize};

use crate::engine::{smooth_ramp, HistoricalCandle};
use crate::opportunity_v3::*;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct AndroidChartSummary {
    pub candle_count: usize,
    pub latest_close_cents: Option<i64>,
    pub ema20_cents: Option<i64>,
    pub ema50_cents: Option<i64>,
    pub ema200_cents: Option<i64>,
    pub macd_cents: Option<i64>,
    pub signal_cents: Option<i64>,
    pub histogram_cents: Option<i64>,
    pub latest_wilder_rsi: Option<f64>,
    pub latest_rsi_slope: Option<f64>,
    pub volume_ratio_hundredths: Option<i32>,
}

fn ema(values: &[f64], period: usize) -> Vec<f64> {
    let m = 2.0 / (period as f64 + 1.0);
    let mut out = Vec::with_capacity(values.len());
    let mut prev = 0.0;
    for (i, &v) in values.iter().enumerate() {
        prev = if i == 0 { v } else { (v - prev) * m + prev };
        out.push(prev);
    }
    out
}

fn wilder_average(values: &[f64], period: usize) -> Vec<f64> {
    let mut out = Vec::with_capacity(values.len());
    let mut prev = 0.0;
    for (i, &v) in values.iter().enumerate() {
        prev = if i == 0 {
            v
        } else if i < period {
            (prev * i as f64 + v) / (i as f64 + 1.0)
        } else {
            (prev * (period as f64 - 1.0) + v) / period as f64
        };
        out.push(prev);
    }
    out
}

fn rsi_from(gains: &[f64], losses: &[f64], period: usize) -> Vec<f64> {
    let g = wilder_average(gains, period);
    let l = wilder_average(losses, period);
    g.iter()
        .zip(&l)
        .map(|(&ag, &al)| {
            if ag == 0.0 && al == 0.0 {
                50.0
            } else if al == 0.0 {
                100.0
            } else {
                100.0 - 100.0 / (1.0 + ag / al)
            }
        })
        .collect()
}

fn deltas(values: &[f64]) -> Vec<f64> {
    let mut d = vec![0.0];
    d.extend(values.windows(2).map(|w| w[1] - w[0]));
    d
}

fn rsi_wilder(values: &[f64], period: usize) -> Vec<f64> {
    if values.is_empty() {
        return vec![];
    }
    if values.len() == 1 {
        return vec![50.0];
    }
    let d = deltas(values);
    let gains: Vec<f64> = d.iter().map(|x| x.max(0.0)).collect();
    let losses: Vec<f64> = d.iter().map(|x| (-x).max(0.0)).collect();
    rsi_from(&gains, &losses, period)
}

fn rsi_weighted(values: &[f64], volumes: &[f64], period: usize) -> Vec<f64> {
    if values.is_empty() {
        return vec![];
    }
    let rel: Vec<f64> = volumes
        .iter()
        .enumerate()
        .map(|(i, &v)| {
            let from = (i + 1).saturating_sub(period);
            let w = &volumes[from..=i];
            let avg = w.iter().sum::<f64>() / w.len() as f64;
            let base = if avg > 0.0 { avg } else { 1.0 };
            (v / base).clamp(0.5, 2.0)
        })
        .collect();
    let d = deltas(values);
    let gains: Vec<f64> = d.iter().zip(&rel).map(|(x, r)| x.max(0.0) * r).collect();
    let losses: Vec<f64> = d.iter().zip(&rel).map(|(x, r)| (-x).max(0.0) * r).collect();
    rsi_from(&gains, &losses, period)
}

fn derivative(values: &[f64]) -> Vec<f64> {
    if values.is_empty() {
        return vec![];
    }
    let mut out = vec![0.0; values.len()];
    for i in 1..values.len() {
        out[i] = values[i] - values[i - 1];
    }
    ema(&out, 3)
}

fn round_even(x: f64) -> i64 {
    x.round_ties_even() as i64
}

fn volume_ratio_hundredths(candles: &[HistoricalCandle]) -> Option<i32> {
    let vols: Vec<u64> = candles.iter().map(|c| c.volume).collect();
    let latest = *vols.last()?;
    if vols.iter().all(|v| *v == 0) {
        return None;
    }
    let mut s = vols.clone();
    s.sort_unstable();
    let n = s.len();
    let median = if n.is_multiple_of(2) {
        (s[n / 2 - 1] + s[n / 2]) as f64 / 2.0
    } else {
        s[n / 2] as f64
    };
    if median <= 0.0 {
        return None;
    }
    Some((round_even(latest as f64 / median * 100.0) as i32).clamp(0, 10_000))
}

/// `ChartAnalysis.buildSummary` (fields read by the V3 technical bucket).
pub fn build_summary(candles: &[HistoricalCandle]) -> AndroidChartSummary {
    let closes: Vec<f64> = candles.iter().map(|c| c.close_cents as f64).collect();
    let last = |v: Vec<f64>| v.last().map(|x| round_even(*x));
    let fast = ema(&closes, 12);
    let slow = ema(&closes, 26);
    let macd: Vec<f64> = fast.iter().zip(&slow).map(|(a, b)| a - b).collect();
    let signal = ema(&macd, 9);
    let hist: Vec<f64> = macd.iter().zip(&signal).map(|(a, b)| a - b).collect();
    let (rsi, slope) = if candles.len() < 2 {
        (None, None)
    } else {
        let vols: Vec<f64> = candles.iter().map(|c| c.volume as f64).collect();
        let wilder = rsi_wilder(&closes, 14);
        let weighted = rsi_weighted(&closes, &vols, 14);
        let sig: Vec<f64> = weighted
            .iter()
            .zip(&wilder)
            .map(|(w, c)| (w * 0.65 + c * 0.35).clamp(0.0, 100.0))
            .collect();
        let smoothed: Vec<f64> = ema(&sig, 5)
            .into_iter()
            .map(|x| x.clamp(0.0, 100.0))
            .collect();
        (
            wilder.last().copied(),
            derivative(&smoothed).last().copied(),
        )
    };
    AndroidChartSummary {
        candle_count: candles.len(),
        latest_close_cents: closes.last().map(|x| *x as i64),
        ema20_cents: last(ema(&closes, 20)),
        ema50_cents: last(ema(&closes, 50)),
        ema200_cents: last(ema(&closes, 200)),
        macd_cents: last(macd),
        signal_cents: last(signal),
        histogram_cents: last(hist),
        latest_wilder_rsi: rsi,
        latest_rsi_slope: slope,
        volume_ratio_hundredths: volume_ratio_hundredths(candles),
    }
}

/// `OpportunityEngine.aggressiveV3TechnicalScore`.
pub fn score_technicals_v3(summary: Option<&AndroidChartSummary>) -> Option<i32> {
    let s = summary?;
    let close = s.latest_close_cents?;
    let mut sum = 0.0;
    let mut ev = 0.0;
    let mut add = |w: f64, r: f64| {
        sum += w * r.clamp(-1.0, 1.0);
        ev += w;
    };
    let b = V3_TECH_TREND_DELTA_BOUND;
    if let Some(e20) = s.ema20_cents.filter(|e| *e > 0) {
        add(
            V3_TECH_TREND_PRICE_20_WEIGHT,
            smooth_ramp((close - e20) as f64 / e20 as f64, -b, b),
        );
    }
    if let (Some(e20), Some(e50)) = (s.ema20_cents, s.ema50_cents.filter(|e| *e > 0)) {
        add(
            V3_TECH_TREND_20_50_WEIGHT,
            smooth_ramp((e20 - e50) as f64 / e50 as f64, -b, b),
        );
    }
    if let (Some(e50), Some(e200)) = (s.ema50_cents, s.ema200_cents.filter(|e| *e > 0)) {
        add(
            V3_TECH_TREND_50_200_WEIGHT,
            smooth_ramp((e50 - e200) as f64 / e200 as f64, -b, b),
        );
    }
    if let Some(h) = s.histogram_cents.filter(|_| close > 0) {
        add(
            V3_TECH_HISTOGRAM_WEIGHT,
            smooth_ramp(
                h as f64 / close as f64,
                -V3_TECH_HISTOGRAM_BOUND,
                V3_TECH_HISTOGRAM_BOUND,
            ),
        );
    }
    if let (Some(m), Some(sg)) = (s.macd_cents, s.signal_cents) {
        add(V3_TECH_MACD_DIRECTION_WEIGHT, (m - sg).signum() as f64);
    }
    if let Some(rsi) = s.latest_wilder_rsi {
        let level = v3_rsi_level_ramp(rsi);
        let slope = s
            .latest_rsi_slope
            .map(|x| smooth_ramp(x, -V3_TECH_RSI_SLOPE_BOUND, V3_TECH_RSI_SLOPE_BOUND))
            .unwrap_or(0.0);
        add(
            V3_TECH_RSI_WEIGHT,
            (0.65 * level + 0.35 * slope).clamp(-1.0, 1.0),
        );
    }
    if let Some(v) = s.volume_ratio_hundredths {
        add(
            V3_TECH_VOLUME_WEIGHT,
            smooth_ramp(
                v as f64,
                V3_TECH_VOLUME_RATIO_LOW,
                V3_TECH_VOLUME_RATIO_HIGH,
            ),
        );
    }
    if ev == 0.0 {
        return None;
    }
    Some(kotlin_round(
        ((sum / V3_TECHNICALS_FULL_WEIGHT) * 100.0).clamp(-100.0, 100.0),
    ))
}
