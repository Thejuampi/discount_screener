//! Derive a scoring policy from MarketRegime (pure, no I/O).

use super::types::MarketRegime;

/// Side for which the regime_fit bucket is scored.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ScoreSide {
    Long,
    Short,
}

/// Weights and multipliers that shape the regime_fit bucket + beta haircut.
#[derive(Clone, Debug)]
pub struct RegimeScoringPolicy {
    pub stance: String,
    pub environment_band: String,
    pub primary_regime: String,
    /// Relative weights (will be L1-normalized in the scorer).
    pub w_quality: f64,
    pub w_low_beta: f64,
    pub w_value: f64,
    pub w_oversold_quality: f64,
    pub w_anti_extension: f64,
    pub w_trend: f64,
    pub w_defensive: f64,
    pub w_growth: f64,
    pub w_liquidity: f64,
    /// Multiplier on V3 beta haircut (1.0 = unchanged).
    pub beta_haircut_mult: f64,
    /// 0..1 scales all feature contributions (from regime confidence).
    pub strength: f64,
    pub prefer_quality: bool,
    pub label_es: String,
    pub label_en: String,
    pub side: ScoreSide,
}

const MIN_CONF_BPS: u32 = 3500;

impl RegimeScoringPolicy {
    /// Build policy from regime. Returns None if confidence too low / unknown.
    pub fn from_regime(regime: &MarketRegime, side: ScoreSide) -> Option<Self> {
        if regime.global_confidence_bps < MIN_CONF_BPS {
            return None;
        }
        if regime.environment_band == "Unknown" && regime.primary_regime == "Unknown" {
            return None;
        }

        let stance = regime.action_stance.as_str();
        let mut p = base_for_stance(stance);
        p.stance = regime.action_stance.clone();
        p.environment_band = regime.environment_band.clone();
        p.primary_regime = regime.primary_regime.clone();
        p.prefer_quality = regime.prefer_quality;
        p.side = side;

        // Confidence soft-scales strength
        p.strength = (regime.global_confidence_bps as f64 / 10_000.0).clamp(0.35, 1.0);

        // Amplify quality if flag set
        if regime.prefer_quality {
            p.w_quality = (p.w_quality + 0.25).min(1.25);
            p.w_low_beta = (p.w_low_beta + 0.1).min(1.1);
        }

        // Breadth / narrow rally: if SPY up-ish environment but weak breadth → less growth chase
        if let Some(b) = regime.breadth_above_ma200_pct {
            if b < 40.0 && matches!(regime.environment_band.as_str(), "RiskOn" | "StrongRiskOn") {
                p.w_anti_extension = (p.w_anti_extension + 0.25).min(1.2);
                p.w_quality = (p.w_quality + 0.15).min(1.2);
                p.w_growth *= 0.5;
            }
        }

        // Credit stress → quality/low beta
        if let Some(c) = regime.credit_score {
            if c < -25 {
                p.w_quality = (p.w_quality + 0.2).min(1.3);
                p.w_low_beta = (p.w_low_beta + 0.2).min(1.2);
                p.beta_haircut_mult = (p.beta_haircut_mult * 1.15).min(2.2);
            }
        }

        // Extreme F&G nudges
        if let Some(fg) = regime.cnn_fear_greed {
            if fg <= 24 {
                p.w_oversold_quality = (p.w_oversold_quality + 0.2).min(1.2);
            } else if fg >= 76 {
                p.w_anti_extension = (p.w_anti_extension + 0.25).min(1.25);
                p.w_oversold_quality *= 0.3;
            }
        }

        if side == ScoreSide::Short {
            mirror_for_short(&mut p);
        }

        p.label_es = format!(
            "{} · {} · fuerza {:.0}%",
            p.stance,
            timing_label_es(&p),
            p.strength * 100.0
        );
        p.label_en = format!(
            "{} · {} · strength {:.0}%",
            p.stance,
            timing_label_en(&p),
            p.strength * 100.0
        );

        Some(p)
    }
}

fn base_for_stance(stance: &str) -> RegimeScoringPolicy {
    // quality, lowβ, value, oversold×q, anti-ext, trend, def, growth, liq, beta_mult
    let (
        w_quality,
        w_low_beta,
        w_value,
        w_oversold_quality,
        w_anti_extension,
        w_trend,
        w_defensive,
        w_growth,
        w_liquidity,
        beta_haircut_mult,
    ) = match stance {
        "BloodInStreets" => (1.0, 0.9, 0.6, 1.0, 0.3, 0.1, 0.7, 0.0, 0.5, 1.8),
        "Washout" => (0.9, 0.7, 0.6, 0.9, 0.4, 0.2, 0.5, 0.1, 0.4, 1.5),
        "Accumulate" | "SelectiveBuy" => (0.7, 0.5, 0.5, 0.7, 0.5, 0.3, 0.4, 0.2, 0.3, 1.2),
        "HealthyPullback" => (0.4, 0.2, 0.3, 0.6, 0.4, 0.7, 0.2, 0.4, 0.2, 0.9),
        "Deploy" | "TrendDeploy" => (0.2, 0.0, 0.2, 0.2, 0.2, 1.0, 0.0, 0.6, 0.1, 0.7),
        "HoldTrim" | "Reduce" => (0.5, 0.4, 0.3, 0.1, 0.8, 0.3, 0.3, 0.2, 0.3, 1.1),
        "Euphoria" | "Distribute" => (0.4, 0.3, 0.2, 0.0, 1.0, 0.2, 0.2, 0.1, 0.2, 1.0),
        "Defend" | "Denial" | "UnstableBlowoff" => {
            (1.0, 1.0, 0.4, 0.3, 0.6, 0.1, 0.8, 0.0, 0.6, 2.0)
        }
        "Hold" | "Neutral" | "Mixed" => (0.3, 0.2, 0.3, 0.3, 0.4, 0.4, 0.2, 0.3, 0.2, 1.0),
        _ => (0.3, 0.2, 0.3, 0.3, 0.4, 0.4, 0.2, 0.3, 0.2, 1.0),
    };

    RegimeScoringPolicy {
        stance: stance.into(),
        environment_band: String::new(),
        primary_regime: String::new(),
        w_quality,
        w_low_beta,
        w_value,
        w_oversold_quality,
        w_anti_extension,
        w_trend,
        w_defensive,
        w_growth,
        w_liquidity,
        beta_haircut_mult,
        strength: 1.0,
        prefer_quality: false,
        label_es: String::new(),
        label_en: String::new(),
        side: ScoreSide::Long,
    }
}

/// Short: reward what long punishes on timing/extension; punish fortress quality somewhat.
fn mirror_for_short(p: &mut RegimeScoringPolicy) {
    // Extension becomes a positive for shorts when anti-chase was high
    // We encode this by flipping sign of anti_extension weight at score time when side=Short.
    // Growth / fragile more attractive for shorts; defensive less.
    std::mem::swap(&mut p.w_growth, &mut p.w_defensive);
    p.w_quality *= 0.35; // don't short quality compounders as hard
    p.w_oversold_quality *= 0.2; // don't short washouts
    p.w_anti_extension = p.w_anti_extension.max(0.5); // want extended names
    p.w_trend *= 0.5;
    p.beta_haircut_mult = (2.0 - p.beta_haircut_mult * 0.5).clamp(0.5, 1.5);
    // For shorts, high beta can be useful (fragile) — low_beta weight reduced
    p.w_low_beta *= 0.25;
}

fn timing_label_es(p: &RegimeScoringPolicy) -> &'static str {
    if p.w_oversold_quality >= 0.7 {
        "mean-revert"
    } else if p.w_anti_extension >= 0.8 {
        "anti-chase"
    } else if p.w_trend >= 0.7 {
        "trend"
    } else {
        "balanced"
    }
}

fn timing_label_en(p: &RegimeScoringPolicy) -> &'static str {
    timing_label_es(p)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::regime::types::MarketRegime;

    fn regime_stance(stance: &str, conf: u32) -> MarketRegime {
        MarketRegime {
            action_stance: stance.into(),
            environment_band: "RiskOff".into(),
            primary_regime: "Correction".into(),
            global_confidence_bps: conf,
            prefer_quality: true,
            cnn_fear_greed: Some(15),
            breadth_above_ma200_pct: Some(35.0),
            ..MarketRegime::default()
        }
    }

    #[test]
    fn low_confidence_yields_none() {
        let r = regime_stance("Washout", 1000);
        assert!(RegimeScoringPolicy::from_regime(&r, ScoreSide::Long).is_none());
    }

    #[test]
    fn blood_in_streets_heavy_quality() {
        let r = regime_stance("BloodInStreets", 9000);
        let p = RegimeScoringPolicy::from_regime(&r, ScoreSide::Long).unwrap();
        assert!(p.w_quality >= 0.9);
        assert!(p.w_oversold_quality >= 0.9);
        assert!(p.beta_haircut_mult >= 1.5);
    }

    #[test]
    fn euphoria_anti_chase() {
        let mut r = regime_stance("Euphoria", 9000);
        r.cnn_fear_greed = Some(88);
        r.environment_band = "RiskOn".into();
        r.primary_regime = "LateBull".into();
        let p = RegimeScoringPolicy::from_regime(&r, ScoreSide::Long).unwrap();
        assert!(p.w_anti_extension >= 0.9);
        assert!(p.w_oversold_quality < 0.3);
    }
}
