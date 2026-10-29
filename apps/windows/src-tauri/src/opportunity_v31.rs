//! Decision rule V3.1 — SHADOW MODE (Juan, 2026-10-29). Official decisions and ranking stay V3.
//! For every config in [`V31_SHADOW_CONFIGS`] this computes what V3.1 *would* decide:
//!
//!     m      = clip((histogram / close) / bound, -1, 1)
//!     S_adj  = S * exp(-λ_T * max(0, -T) / 100) * exp(-λ_M * max(0, -m))
//!     shadow = Watch if official == Act and S_adj < 30, else official
//!
//! T = V3 technical bucket; histogram = MACD(12,26) − signal(9) of the Android Year (`1y`/`1wk`)
//! summary, in cents. S_adj ≤ S, so the shadow can only demote Act. Mirror of Kotlin
//! `V31ActAdjustment` (apps/android/core); `tools/android-jvm-parity/diff_headless.py` checks the
//! constants and the config list match.

use crate::android_chart::AndroidChartSummary;
use serde::{Deserialize, Serialize};

pub const V31_SHADOW_RULE_VERSION: &str = "V3.1-shadow";
pub const V31_LAMBDA_T: f64 = 1.5;
pub const V31_ACT_THRESHOLD: f64 = 30.0;

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
    pub config_id: String,
    pub bound: f64,
    pub lambda_t: f64,
    pub lambda_m: f64,
    pub m: Option<f64>,
    pub s_adj: f64,
    pub shadow_decision: Option<String>,
}

/// histogram / close of the Year summary; `None` when either is missing (close must be > 0).
pub fn histogram_ratio(summary: Option<&AndroidChartSummary>) -> Option<f64> {
    let s = summary?;
    let hist = s.histogram_cents?;
    let close = s.latest_close_cents.filter(|c| *c > 0)?;
    Some(hist as f64 / close as f64)
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

pub fn shadow_decision<'a>(official: Option<&'a str>, s_adj: f64) -> Option<&'a str> {
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
) -> Vec<ShadowValue> {
    V31_SHADOW_CONFIGS
        .iter()
        .map(|c| {
            let m = momentum(ratio, c.bound);
            let s_adj = adjusted_score(composite, technical, m, V31_LAMBDA_T, c.lambda_m);
            ShadowValue {
                config_id: c.id.to_string(),
                bound: c.bound,
                lambda_t: V31_LAMBDA_T,
                lambda_m: c.lambda_m,
                m,
                s_adj,
                shadow_decision: shadow_decision(official, s_adj).map(str::to_string),
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
        let g = shadow_grid(Some("Act"), 36, Some(-20), Some(-87.0 / 2833.0));
        assert_eq!(g.len(), 6);
        assert!(g
            .iter()
            .all(|v| v.shadow_decision.as_deref() == Some("Watch")));
    }
}
