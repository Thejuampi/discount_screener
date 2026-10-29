//! Decision rule V3.1 — SHADOW MODE (Juan, 2026-10-29). Official decisions and ranking stay V3.
//! For every config in [`V31_SHADOW_CONFIGS`] this computes what V3.1 *would* decide:
//!
//!     m      = clip((histogram / close) / bound, -1, 1)
//!     S_adj  = S * exp(-λ_T * max(0, -T) / 100) * exp(-λ_M * max(0, -m))
//!     shadow = Watch if official == Act and S_adj < 30, else official
//!
//! T = V3 technical bucket; histogram = MACD(12,26) − signal(9) of the Android Year (`1y`/`1wk`)
//! summary, preferably UNROUNDED (`histogram_raw_cents`, so the Rust round-half-even vs Kotlin
//! round-half-up difference cannot reach the shadow). Summaries without the raw value (e.g.
//! snapshots persisted before it existed) fall back to the rounded `histogram_cents`; each
//! shadow value records which one was used in `hist_source` (`raw` / `rounded`). The last
//! weekly bar is the current, possibly partial, week (same as the official technicals).
//! Warm-up: with fewer than [`V31_MIN_WEEKLY_BARS`] bars (26 + 9 for MACD + signal) m is null,
//! `warmup` is true and no momentum penalty applies; the T term still applies. S_adj ≤ S, so the shadow can only demote Act. Mirror of Kotlin
//! `V31ActAdjustment` (apps/android/core); `tools/android-jvm-parity/diff_headless.py` checks the
//! constants and the config list match.

use crate::android_chart::AndroidChartSummary;
use serde::{Deserialize, Serialize};

pub const V31_SHADOW_RULE_VERSION: &str = "V3.1-shadow";
pub const V31_LAMBDA_T: f64 = 1.5;
pub const V31_ACT_THRESHOLD: f64 = 30.0;
pub const V31_MIN_WEEKLY_BARS: usize = 35;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShadowConfig {
    pub id: &'static str,
    pub bound: f64,
    pub lambda_m: f64,
}

/// Stable ids: b<bound·10⁴, 4 digits>_lm<λ_M·100, 3 digits>. Never renumber; append only.
pub const V31_SHADOW_CONFIGS: [ShadowConfig; 6] = [
    ShadowConfig {
        id: "b0050_lm015",
        bound: 0.005,
        lambda_m: 0.15,
    },
    ShadowConfig {
        id: "b0050_lm030",
        bound: 0.005,
        lambda_m: 0.30,
    },
    ShadowConfig {
        id: "b0200_lm015",
        bound: 0.02,
        lambda_m: 0.15,
    },
    ShadowConfig {
        id: "b0200_lm030",
        bound: 0.02,
        lambda_m: 0.30,
    },
    ShadowConfig {
        id: "b0500_lm015",
        bound: 0.05,
        lambda_m: 0.15,
    },
    ShadowConfig {
        id: "b0500_lm030",
        bound: 0.05,
        lambda_m: 0.30,
    },
];

/// One shadow config result. Diagnostic only: never drives the official decision or ranking.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ShadowValue {
    pub rule_version: String,
    pub config_id: String,
    pub bound: f64,
    pub lambda_t: f64,
    pub lambda_m: f64,
    pub m: Option<f64>,
    /// True when m is null because the Year series is still warming up (< 35 weekly bars).
    pub warmup: bool,
    pub s_adj: f64,
    pub shadow_decision: Option<String>,
    /// Histogram used for m: `raw` (unrounded) or `rounded` (fallback); None when m is null.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hist_source: Option<String>,
}

/// Which histogram [`momentum_input`] used: `raw`, `rounded` (fallback) or None (no ratio).
pub fn hist_source(summary: Option<&AndroidChartSummary>) -> Option<&'static str> {
    match momentum_input(summary) {
        (Some(_), false) => summary.map(|s| {
            if s.histogram_raw_cents.is_some_and(f64::is_finite) {
                "raw"
            } else {
                "rounded"
            }
        }),
        _ => None,
    }
}

/// Momentum input from the Year summary: (unrounded histogram / close, warm-up flag).
/// The ratio is `None` when the summary, the raw histogram or a positive close is missing, or
/// during warm-up.
pub fn momentum_input(summary: Option<&AndroidChartSummary>) -> (Option<f64>, bool) {
    let Some(s) = summary else {
        return (None, false);
    };
    if s.candle_count < V31_MIN_WEEKLY_BARS {
        return (None, true);
    }
    // Raw histogram when present; summaries persisted before the raw field existed (e.g. the
    // daemons' opportunity_snapshot.json) fall back to the rounded cents.
    let hist = s
        .histogram_raw_cents
        .or(s.histogram_cents.map(|h| h as f64));
    let ratio = match (hist, s.latest_close_cents.filter(|c| *c > 0)) {
        (Some(h), Some(c)) if h.is_finite() => Some(h / c as f64),
        _ => None,
    };
    (ratio, false)
}

pub fn momentum(ratio: Option<f64>, bound: f64) -> Option<f64> {
    assert!(bound > 0.0, "momentum bound must be > 0");
    ratio.map(|r| (r / bound).clamp(-1.0, 1.0))
}

/// S_adj; a missing T or m contributes no penalty.
pub fn adjusted_score(
    composite: i32,
    technical: Option<i32>,
    momentum: Option<f64>,
    lambda_t: f64,
    lambda_m: f64,
) -> f64 {
    let t = technical.unwrap_or(0) as f64;
    let m = momentum.unwrap_or(0.0).clamp(-1.0, 1.0);
    composite as f64 * (-lambda_t * (-t).max(0.0) / 100.0).exp() * (-lambda_m * (-m).max(0.0)).exp()
}

pub fn shadow_decision(official: Option<&str>, s_adj: f64) -> Option<&str> {
    match official {
        Some("Act") if s_adj < V31_ACT_THRESHOLD => Some("Watch"),
        o => o,
    }
}

pub fn shadow_grid(
    official: Option<&str>,
    composite: i32,
    technical: Option<i32>,
    ratio: Option<f64>,
    warmup: bool,
) -> Vec<ShadowValue> {
    let ratio = if warmup { None } else { ratio };
    V31_SHADOW_CONFIGS
        .iter()
        .map(|c| {
            let m = momentum(ratio, c.bound);
            let s_adj = adjusted_score(composite, technical, m, V31_LAMBDA_T, c.lambda_m);
            ShadowValue {
                rule_version: V31_SHADOW_RULE_VERSION.to_string(),
                config_id: c.id.to_string(),
                bound: c.bound,
                lambda_t: V31_LAMBDA_T,
                lambda_m: c.lambda_m,
                m,
                warmup,
                s_adj,
                shadow_decision: shadow_decision(official, s_adj).map(str::to_string),
                hist_source: None,
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_list_is_the_named_6_grid_with_stable_ids() {
        let ids: Vec<_> = V31_SHADOW_CONFIGS.iter().map(|c| c.id).collect();
        assert_eq!(
            ids,
            [
                "b0050_lm015",
                "b0050_lm030",
                "b0200_lm015",
                "b0200_lm030",
                "b0500_lm015",
                "b0500_lm030"
            ]
        );
        for c in V31_SHADOW_CONFIGS {
            assert_eq!(
                c.id,
                format!(
                    "b{:04}_lm{:03}",
                    (c.bound * 1e4).round() as i64,
                    (c.lambda_m * 100.0).round() as i64
                )
            );
        }
    }

    #[test]
    fn ssrm_t_term_alone_drops_below_act() {
        let s = adjusted_score(36, Some(-18), None, 1.5, 0.3);
        assert!((s - 36.0 * (-0.27f64).exp()).abs() < 1e-12);
        assert!((s - 27.4).abs() < 0.1 && s < 30.0);
    }

    #[test]
    fn non_negative_t_and_m_leave_s_unchanged() {
        for (t, m) in [(0, 0.0), (5, 0.3), (100, 1.0)] {
            assert_eq!(adjusted_score(36, Some(t), Some(m), 1.5, 0.3), 36.0);
        }
        assert_eq!(adjusted_score(36, None, None, 1.5, 0.3), 36.0);
    }

    #[test]
    fn monotonic_in_t_and_m() {
        let mut prev = f64::INFINITY;
        for t in (-100..=0).rev() {
            let s = adjusted_score(50, Some(t), Some(0.0), 1.5, 0.3);
            assert!(s <= prev);
            prev = s;
        }
        prev = f64::INFINITY;
        for i in 0..=20 {
            let s = adjusted_score(50, Some(0), Some(-(i as f64) / 20.0), 1.5, 0.3);
            assert!(s <= prev);
            prev = s;
        }
    }

    #[test]
    fn momentum_is_clipped_per_bound() {
        assert_eq!(momentum(Some(-0.0087), 0.005), Some(-1.0));
        assert!((momentum(Some(-0.0087), 0.02).unwrap() + 0.435).abs() < 1e-12);
        assert_eq!(momentum(Some(0.5), 0.05), Some(1.0));
        assert_eq!(momentum(None, 0.02), None);
    }

    #[test]
    fn shadow_only_demotes_act() {
        assert_eq!(shadow_decision(Some("Act"), 29.99), Some("Watch"));
        assert_eq!(shadow_decision(Some("Act"), 30.0), Some("Act"));
        assert_eq!(shadow_decision(Some("Avoid"), 1.0), Some("Avoid"));
        assert_eq!(shadow_decision(Some("Watch"), 99.0), Some("Watch"));
        assert_eq!(shadow_decision(None, 1.0), None);
        let g = shadow_grid(Some("Act"), 36, Some(-20), Some(-87.0 / 2833.0), false);
        assert_eq!(g.len(), 6);
        assert!(g
            .iter()
            .all(|v| v.shadow_decision.as_deref() == Some("Watch")));
    }

    fn summary(
        n: usize,
        raw: Option<f64>,
        rounded: Option<i64>,
        close: i64,
    ) -> AndroidChartSummary {
        AndroidChartSummary {
            candle_count: n,
            latest_close_cents: Some(close),
            ema20_cents: None,
            ema50_cents: None,
            ema200_cents: None,
            macd_cents: None,
            signal_cents: None,
            histogram_cents: rounded,
            histogram_raw_cents: raw,
            latest_wilder_rsi: None,
            latest_rsi_slope: None,
            volume_ratio_hundredths: None,
        }
    }

    /// Pins the exact config list AND the defaults (kills "bound changed" / "λ changed").
    #[test]
    fn pinned_constants_and_config_values() {
        assert_eq!(V31_SHADOW_RULE_VERSION, "V3.1-shadow");
        assert_eq!(V31_LAMBDA_T, 1.5);
        assert_eq!(V31_ACT_THRESHOLD, 30.0);
        assert_eq!(V31_MIN_WEEKLY_BARS, 35);
        let got: Vec<_> = V31_SHADOW_CONFIGS
            .iter()
            .map(|c| (c.id, c.bound, c.lambda_m))
            .collect();
        assert_eq!(
            got,
            [
                ("b0050_lm015", 0.005, 0.15),
                ("b0050_lm030", 0.005, 0.30),
                ("b0200_lm015", 0.02, 0.15),
                ("b0200_lm030", 0.02, 0.30),
                ("b0500_lm015", 0.05, 0.15),
                ("b0500_lm030", 0.05, 0.30),
            ]
        );
    }

    /// λ_M pin: S=40, T=0, m=-1 → 40·e^(−λ_M), for every config (and its provenance fields).
    #[test]
    fn lambda_m_pinned_per_config() {
        // ratio = -1 → m = -1 at every bound.
        let g = shadow_grid(Some("Act"), 40, Some(0), Some(-1.0), false);
        for (v, c) in g.iter().zip(V31_SHADOW_CONFIGS) {
            assert_eq!(v.m, Some(-1.0));
            assert!(
                (v.s_adj - 40.0 * (-c.lambda_m).exp()).abs() < 1e-12,
                "{}",
                c.id
            );
            assert_eq!(
                (v.config_id.as_str(), v.bound, v.lambda_m, v.lambda_t),
                (c.id, c.bound, c.lambda_m, 1.5)
            );
            assert_eq!(v.rule_version, "V3.1-shadow");
        }
        assert!((g[1].s_adj - 40.0 * (-0.3f64).exp()).abs() < 1e-12);
        assert!((g[0].s_adj - 40.0 * (-0.15f64).exp()).abs() < 1e-12);
    }

    /// λ_T pin: S=40, m=0, T=-20 → 40·e^(−0.3); T=-100 → 40·e^(−1.5); identical for every config.
    #[test]
    fn lambda_t_pinned() {
        for (t, want) in [
            (-20, 40.0 * (-0.3f64).exp()),
            (-100, 40.0 * (-1.5f64).exp()),
        ] {
            for v in shadow_grid(Some("Act"), 40, Some(t), Some(0.0), false) {
                assert!((v.s_adj - want).abs() < 1e-12, "T={t} {}", v.config_id);
            }
        }
    }

    /// Kills "max(0,·) dropped on T": positive T must not raise S.
    #[test]
    fn positive_t_never_boosts() {
        assert_eq!(adjusted_score(40, Some(50), Some(0.0), 1.5, 0.3), 40.0);
        assert_eq!(adjusted_score(40, Some(0), Some(1.0), 1.5, 0.3), 40.0);
    }

    /// Kills "clip ±0.5", "no clip" and "no clamp in S_adj".
    #[test]
    fn clip_is_exactly_plus_minus_one() {
        assert_eq!(momentum(Some(-0.75 * 0.02), 0.02), Some(-0.75));
        assert_eq!(momentum(Some(-3.0 * 0.02), 0.02), Some(-1.0));
        assert_eq!(momentum(Some(3.0 * 0.02), 0.02), Some(1.0));
        // m beyond -1 passed straight into S_adj is clamped there too.
        let a = adjusted_score(40, Some(0), Some(-5.0), 1.5, 0.3);
        assert!((a - 40.0 * (-0.3f64).exp()).abs() < 1e-12);
        // Per-bound values (kills a changed bound): ratio -0.01 → m = -1, -0.5, -0.2.
        let g = shadow_grid(Some("Act"), 40, Some(0), Some(-0.01), false);
        let ms: Vec<_> = g.iter().map(|v| v.m.unwrap()).collect();
        for (got, want) in ms.iter().zip([-1.0, -1.0, -0.5, -0.5, -0.2, -0.2]) {
            assert!((got - want).abs() < 1e-12, "{ms:?}");
        }
    }

    #[test]
    fn hist_source_raw_rounded_or_none() {
        assert_eq!(
            hist_source(Some(&summary(60, Some(-5.4), Some(-5), 10_000))),
            Some("raw")
        );
        assert_eq!(
            hist_source(Some(&summary(60, None, Some(-5), 10_000))),
            Some("rounded")
        );
        assert_eq!(
            hist_source(Some(&summary(34, Some(-5.4), Some(-5), 10_000))),
            None
        );
        assert_eq!(hist_source(Some(&summary(60, None, None, 10_000))), None);
        assert_eq!(hist_source(None), None);
    }

    #[test]
    fn warmup_nulls_m_without_penalty_and_raw_histogram_is_used() {
        let (r, w) = momentum_input(Some(&summary(34, Some(-500.0), Some(-500), 10_000)));
        assert_eq!((r, w), (None, true));
        let g = shadow_grid(Some("Act"), 40, Some(0), r, w);
        assert!(g
            .iter()
            .all(|v| v.m.is_none() && v.warmup && v.s_adj == 40.0));
        // Unrounded: -0.5 cents (rounded would be 0 or -1 depending on the rounding mode).
        let (r, w) = momentum_input(Some(&summary(35, Some(-0.5), Some(0), 10_000)));
        assert_eq!((r, w), (Some(-0.5 / 10_000.0), false));
        assert_eq!(momentum_input(None), (None, false));
        // No raw value (older persisted summary): rounded cents fallback.
        assert_eq!(
            momentum_input(Some(&summary(60, None, Some(-5), 10_000))),
            (Some(-5.0 / 10_000.0), false)
        );
        assert_eq!(
            momentum_input(Some(&summary(60, None, None, 10_000))),
            (None, false)
        );
        // A grid called with warmup ignores any ratio.
        assert!(shadow_grid(Some("Act"), 40, Some(0), Some(-1.0), true)
            .iter()
            .all(|v| v.m.is_none() && v.s_adj == 40.0));
    }
}
