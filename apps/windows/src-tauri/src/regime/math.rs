//! Pure math helpers for the regime engine.

/// Clamp `v` into `[lo, hi]`.
pub fn clamp(v: f64, lo: f64, hi: f64) -> f64 {
    v.max(lo).min(hi)
}

pub fn clamp_i32(v: i32, lo: i32, hi: i32) -> i32 {
    v.max(lo).min(hi)
}

/// Linear map `x` from [x0,x1] into [y0,y1], clamped.
pub fn linmap(x: f64, x0: f64, x1: f64, y0: f64, y1: f64) -> f64 {
    if (x1 - x0).abs() < 1e-12 {
        return y0;
    }
    let t = clamp((x - x0) / (x1 - x0), 0.0, 1.0);
    y0 + t * (y1 - y0)
}

/// Smoothstep blend (available for continuous band blending).
#[allow(dead_code)]
pub fn smoothstep(edge0: f64, edge1: f64, x: f64) -> f64 {
    let t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

/// Logistic exposure ceiling: E≈−100 → ~15%, E≈0 → ~57%, E≈+100 → ~100%.
pub fn logistic_exposure_pct(environment_score: i32) -> f64 {
    let x = environment_score as f64 / 35.0;
    15.0 + 85.0 / (1.0 + (-x).exp())
}

/// Round to nearest step (e.g. 5).
pub fn round_to_step(v: f64, step: f64) -> u32 {
    if step <= 0.0 {
        return v.round().max(0.0) as u32;
    }
    ((v / step).round() * step).max(0.0) as u32
}

/// Percentile of `value` within sorted sample (0..100). Linear rank.
pub fn percentile_of(sorted: &[f64], value: f64) -> Option<f64> {
    if sorted.is_empty() {
        return None;
    }
    let n = sorted.len();
    let mut below = 0usize;
    for &s in sorted {
        if s < value {
            below += 1;
        } else {
            break;
        }
    }
    // count equals for midrank-ish
    let mut eq = 0usize;
    for &s in sorted.iter().skip(below) {
        if (s - value).abs() < 1e-9 {
            eq += 1;
        } else {
            break;
        }
    }
    let rank = below as f64 + eq as f64 * 0.5;
    Some(clamp(rank / n as f64 * 100.0, 0.0, 100.0))
}

/// Simple returns from close series.
pub fn simple_returns(closes: &[f64]) -> Vec<f64> {
    let mut out = Vec::with_capacity(closes.len().saturating_sub(1));
    for w in closes.windows(2) {
        if w[0] > 0.0 {
            out.push((w[1] - w[0]) / w[0]);
        }
    }
    out
}

/// Annualized realized vol from daily simple returns (approx √252).
pub fn realized_vol_pct(returns: &[f64]) -> Option<f64> {
    if returns.len() < 5 {
        return None;
    }
    let n = returns.len() as f64;
    let mean = returns.iter().sum::<f64>() / n;
    let var = returns.iter().map(|r| (r - mean).powi(2)).sum::<f64>() / (n - 1.0);
    if var < 0.0 {
        return None;
    }
    Some(var.sqrt() * (252.0_f64).sqrt() * 100.0)
}

/// Relative performance of series A vs B over last `lookback` closes: (ret_a - ret_b)*100.
pub fn relative_return_pct(a: &[f64], b: &[f64], lookback: usize) -> Option<f64> {
    if a.len() < lookback + 1 || b.len() < lookback + 1 {
        return None;
    }
    let a0 = a[a.len() - 1 - lookback];
    let a1 = *a.last()?;
    let b0 = b[b.len() - 1 - lookback];
    let b1 = *b.last()?;
    if a0 <= 0.0 || b0 <= 0.0 {
        return None;
    }
    let ra = (a1 - a0) / a0;
    let rb = (b1 - b0) / b0;
    Some((ra - rb) * 100.0)
}

/// Map a relative return % into −100..+100 with soft caps.
pub fn rel_return_to_score(rel_pct: f64, soft_cap: f64) -> i32 {
    let cap = soft_cap.max(0.1);
    let t = clamp(rel_pct / cap, -1.0, 1.0);
    (t * 100.0).round() as i32
}

/// Contrarian map: F&G 0 → +100, 50 → 0, 100 → −100.
pub fn fng_to_contrarian_score(fng: f64) -> i32 {
    let s = 100.0 - 2.0 * clamp(fng, 0.0, 100.0);
    s.round() as i32
}

/// Classify CNN-style rating string into zone.
pub fn fng_zone(score: f64) -> &'static str {
    if score <= 24.0 {
        "ExtremeFear"
    } else if score <= 44.0 {
        "Fear"
    } else if score <= 55.0 {
        "Neutral"
    } else if score <= 75.0 {
        "Greed"
    } else {
        "ExtremeGreed"
    }
}

pub fn fng_label_from_zone(zone: &str) -> &'static str {
    match zone {
        "ExtremeFear" => "Extreme Fear",
        "Fear" => "Fear",
        "Neutral" => "Neutral",
        "Greed" => "Greed",
        "ExtremeGreed" => "Extreme Greed",
        _ => "Unknown",
    }
}

/// VIX absolute fallback when percentile unavailable.
pub fn vix_state(vix: f64) -> &'static str {
    if vix < 15.0 {
        "Calm"
    } else if vix < 20.0 {
        "Normal"
    } else if vix < 28.0 {
        "Elevated"
    } else if vix < 40.0 {
        "Fear"
    } else {
        "Crisis"
    }
}

/// Stress score from VIX percentile (0..100) → roughly −40..+100.
pub fn stress_from_vix_percentile(pctl: f64) -> f64 {
    if pctl < 20.0 {
        linmap(pctl, 0.0, 20.0, -40.0, 0.0)
    } else if pctl < 50.0 {
        linmap(pctl, 20.0, 50.0, 0.0, 10.0)
    } else if pctl < 80.0 {
        linmap(pctl, 50.0, 80.0, 10.0, 40.0)
    } else if pctl < 95.0 {
        linmap(pctl, 80.0, 95.0, 40.0, 75.0)
    } else {
        linmap(pctl, 95.0, 100.0, 75.0, 100.0)
    }
}

/// Fallback stress from absolute VIX.
pub fn stress_from_vix_level(vix: f64) -> f64 {
    if vix < 12.0 {
        linmap(vix, 8.0, 12.0, -40.0, -20.0)
    } else if vix < 18.0 {
        linmap(vix, 12.0, 18.0, -20.0, 0.0)
    } else if vix < 26.0 {
        linmap(vix, 18.0, 26.0, 0.0, 35.0)
    } else if vix < 40.0 {
        linmap(vix, 26.0, 40.0, 35.0, 75.0)
    } else {
        linmap(vix, 40.0, 80.0, 75.0, 100.0)
    }
}

/// Term structure contribution: ratio = VIX/VIX3M.
pub fn term_structure_stress(ratio: f64) -> f64 {
    if ratio < 0.90 {
        -15.0
    } else if ratio < 1.00 {
        -5.0
    } else if ratio < 1.10 {
        15.0
    } else {
        35.0
    }
}

/// Weighted mean of (score, weight) pairs; weights should be positive.
pub fn weighted_mean_i32(parts: &[(i32, f64)]) -> Option<i32> {
    let mut num = 0.0;
    let mut den = 0.0;
    for &(s, w) in parts {
        if w > 0.0 {
            num += s as f64 * w;
            den += w;
        }
    }
    if den <= 0.0 {
        None
    } else {
        Some((num / den).round() as i32)
    }
}

/// Hysteresis: only move if delta exceeds deadband, else keep previous.
pub fn hysteresis_u32(prev: Option<u32>, next: u32, deadband: u32) -> u32 {
    match prev {
        None => next,
        Some(p) => {
            if p.abs_diff(next) <= deadband {
                p
            } else {
                next
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fng_contrarian_extremes() {
        assert_eq!(fng_to_contrarian_score(0.0), 100);
        assert_eq!(fng_to_contrarian_score(50.0), 0);
        assert_eq!(fng_to_contrarian_score(100.0), -100);
    }

    #[test]
    fn fng_zones() {
        assert_eq!(fng_zone(12.0), "ExtremeFear");
        assert_eq!(fng_zone(30.0), "Fear");
        assert_eq!(fng_zone(50.0), "Neutral");
        assert_eq!(fng_zone(70.0), "Greed");
        assert_eq!(fng_zone(90.0), "ExtremeGreed");
    }

    #[test]
    fn logistic_exposure_bounds() {
        let low = logistic_exposure_pct(-100);
        let mid = logistic_exposure_pct(0);
        let high = logistic_exposure_pct(100);
        assert!(low >= 14.0 && low <= 30.0);
        assert!(mid >= 50.0 && mid <= 65.0);
        assert!(high >= 90.0 && high <= 100.0);
    }

    #[test]
    fn hysteresis_deadband() {
        assert_eq!(hysteresis_u32(Some(60), 55, 5), 60);
        assert_eq!(hysteresis_u32(Some(60), 50, 5), 50);
        assert_eq!(hysteresis_u32(None, 55, 5), 55);
    }

    #[test]
    fn percentile_basic() {
        let s = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let p = percentile_of(&s, 3.0).unwrap();
        assert!(p > 30.0 && p < 70.0);
    }
}
