//! Decision rule V3.1 (Juan, 2026-10-29): Act is checked on a technically-adjusted
//! composite instead of the raw V3 composite S.
//!
//!     S_adj = S * exp(-λ_T * max(0, -T) / 100) * exp(-λ_M * max(0, -m))
//!     m     = clip((histogram / close) / V31_MOMENTUM_HISTOGRAM_BOUND, -1, 1)
//!
//! T = V3 technical bucket; histogram = MACD(12,26) − signal(9) of the Android Year
//! (`1y`/`1wk`) summary, in cents. Only negative T / m penalise; ranking stays on raw S;
//! Avoid logic is unchanged. Mirror of Kotlin `V31ActAdjustment` (apps/android/core);
//! `tools/android-jvm-parity/diff_headless.py` checks the constants match.

use crate::android_chart::AndroidChartSummary;

pub const V31_RULE_VERSION: &str = "V3.1";
pub const V31_LAMBDA_T: f64 = 1.5;
pub const V31_LAMBDA_M: f64 = 0.3;
/// V3.1's own m bound, independent of the V3 technical histogram bound. Mirrors Kotlin
/// `V31ActAdjustment.MOMENTUM_HISTOGRAM_BOUND`; pass another bound to `macd_momentum_with_bound`.
pub const V31_MOMENTUM_HISTOGRAM_BOUND: f64 = 0.005;

/// MACD momentum m in [-1, 1]; `None` when histogram or a positive close is missing.
pub fn macd_momentum(summary: Option<&AndroidChartSummary>) -> Option<f64> {
    macd_momentum_with_bound(summary, V31_MOMENTUM_HISTOGRAM_BOUND)
}

/// `macd_momentum` with an explicit bound (> 0), for sensitivity runs.
pub fn macd_momentum_with_bound(summary: Option<&AndroidChartSummary>, bound: f64) -> Option<f64> {
    assert!(bound > 0.0, "momentum bound must be > 0");
    let s = summary?;
    let hist = s.histogram_cents?;
    let close = s.latest_close_cents.filter(|c| *c > 0)?;
    Some(((hist as f64 / close as f64) / bound).clamp(-1.0, 1.0))
}

/// S_adj; a missing T or m contributes no penalty.
pub fn adjusted_score(composite: i32, technical: Option<i32>, momentum: Option<f64>) -> f64 {
    let t = technical.unwrap_or(0) as f64;
    let m = momentum.unwrap_or(0.0).clamp(-1.0, 1.0);
    composite as f64
        * (-V31_LAMBDA_T * (-t).max(0.0) / 100.0).exp()
        * (-V31_LAMBDA_M * (-m).max(0.0)).exp()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ssrm_t_term_alone_drops_below_act() {
        let s = adjusted_score(36, Some(-18), None);
        assert!((s - 36.0 * (-0.27f64).exp()).abs() < 1e-12);
        assert!((s - 27.4).abs() < 0.1 && s < 30.0);
    }

    #[test]
    fn non_negative_t_and_m_leave_s_unchanged() {
        for (t, m) in [(0, 0.0), (5, 0.3), (100, 1.0), (0, 1.0)] {
            assert_eq!(adjusted_score(36, Some(t), Some(m)), 36.0);
        }
        assert_eq!(adjusted_score(36, None, None), 36.0);
    }

    #[test]
    fn monotonic_in_t_and_m() {
        let mut prev = f64::INFINITY;
        for t in (-100..=0).rev() {
            let s = adjusted_score(50, Some(t), Some(0.0));
            assert!(s <= prev);
            prev = s;
        }
        let mut prev = f64::INFINITY;
        for i in 0..=20 {
            let s = adjusted_score(50, Some(0), Some(-(i as f64) / 20.0));
            assert!(s <= prev);
            prev = s;
        }
    }

    #[test]
    fn m_is_clipped() {
        let sum = |h: i64, c: i64| AndroidChartSummary {
            candle_count: 54,
            latest_close_cents: Some(c),
            ema20_cents: None,
            ema50_cents: None,
            ema200_cents: None,
            macd_cents: None,
            signal_cents: None,
            histogram_cents: Some(h),
            latest_wilder_rsi: None,
            latest_rsi_slope: None,
            volume_ratio_hundredths: None,
        };
        assert_eq!(macd_momentum(Some(&sum(-1000, 10_000))), Some(-1.0));
        assert_eq!(macd_momentum(Some(&sum(1000, 10_000))), Some(1.0));
        assert!((macd_momentum(Some(&sum(-25, 10_000))).unwrap() + 0.5).abs() < 1e-12);
        assert_eq!(macd_momentum(Some(&sum(5, 0))), None);
        // configurable bound: -100/10000 = -1% of price -> -0.5 at a 2% bound
        assert!((macd_momentum_with_bound(Some(&sum(-100, 10_000)), 0.02).unwrap() + 0.5).abs() < 1e-12);
        assert_eq!(
            adjusted_score(30, Some(0), Some(-5.0)),
            adjusted_score(30, Some(0), Some(-1.0))
        );
    }
}
