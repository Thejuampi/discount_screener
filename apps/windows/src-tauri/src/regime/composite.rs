//! Composite policy: environment band, stance matrix, exposure, multipliers.

use super::math::{
    clamp_i32, fng_zone, hysteresis_u32, logistic_exposure_pct, round_to_step, weighted_mean_i32,
};
use super::types::PillarResult;

#[derive(Clone, Debug)]
pub struct CompositeInput {
    pub trend: PillarResult,
    pub breadth: PillarResult,
    pub volatility: PillarResult, // score = stress (high = bad for environment)
    pub sentiment: PillarResult,  // score = contrarian (+ = buy fear)
    pub cross_asset: PillarResult,
    pub quality: PillarResult, // high = healthy, low = fragile
    pub spy_drawdown_from_ath_pct: Option<f64>,
    pub breadth_ma200_pct: Option<f64>,
    pub vix_term_ratio: Option<f64>,
    pub cnn_fng: Option<f64>,
    pub prev_exposure_pct: Option<u32>,
}

#[derive(Clone, Debug)]
pub struct CompositeOutput {
    pub environment_score: i32,
    pub sentiment_score: i32,
    pub quality_score: i32,
    pub environment_band: &'static str,
    pub action_stance: &'static str,
    pub primary_regime: &'static str,
    pub suggested_exposure_pct: u32,
    pub cash_buffer_pct: u32,
    pub new_risk_multiplier_bps: i32,
    pub add_bias: i32,
    pub prefer_quality: bool,
    pub global_confidence_bps: u32,
    /// Effective weights used for env pillars (bps of 10000).
    pub weight_trend_bps: u32,
    pub weight_breadth_bps: u32,
    pub weight_vol_bps: u32,
    pub weight_cross_bps: u32,
    pub weight_quality_bps: u32,
    pub crisis_cap_applied: bool,
    pub quality_haircut_applied: bool,
}

/// Base weights (will be scaled by confidence and renormalized).
const W_TREND: f64 = 0.22;
const W_BREADTH: f64 = 0.22;
const W_VOL: f64 = 0.18;
const W_CROSS: f64 = 0.16;
const W_QUALITY: f64 = 0.14;
// residual 0.08 reserved — absorbed into renormalization

fn conf_w(base: f64, conf_bps: u32) -> f64 {
    base * (conf_bps as f64 / 10_000.0).clamp(0.0, 1.0)
}

pub fn compose(input: &CompositeInput) -> CompositeOutput {
    let wt = conf_w(W_TREND, input.trend.confidence_bps);
    let wb = conf_w(W_BREADTH, input.breadth.confidence_bps);
    let wv = conf_w(W_VOL, input.volatility.confidence_bps);
    let wc = conf_w(W_CROSS, input.cross_asset.confidence_bps);
    let wq = conf_w(W_QUALITY, input.quality.confidence_bps);

    // Volatility score is stress → invert for environment contribution.
    let env_vol = -input.volatility.score;

    let parts = [
        (input.trend.score, wt),
        (input.breadth.score, wb),
        (env_vol, wv),
        (input.cross_asset.score, wc),
        (input.quality.score, wq),
    ];
    let environment_score = weighted_mean_i32(&parts).unwrap_or(0);
    let sentiment_score = input.sentiment.score;
    let quality_score = input.quality.score;

    let total_w = wt + wb + wv + wc + wq;
    let to_bps = |w: f64| -> u32 {
        if total_w <= 0.0 {
            0
        } else {
            ((w / total_w) * 10_000.0).round() as u32
        }
    };

    let mut environment_band = env_band(environment_score);
    // Crisis override: deep stress + breadth crash + term backwardation
    let breadth_crash = input.breadth_ma200_pct.map(|b| b < 30.0).unwrap_or(false);
    let back = input.vix_term_ratio.map(|r| r > 1.05).unwrap_or(false);
    let deep_dd = input
        .spy_drawdown_from_ath_pct
        .map(|d| d >= 20.0)
        .unwrap_or(false);
    if (input.volatility.score >= 70 && breadth_crash && back)
        || (environment_score <= -60 && deep_dd)
    {
        environment_band = "Crisis";
    }

    let sent_zone = match input.cnn_fng {
        Some(fg) => fng_zone(fg),
        None => sentiment_zone_from_score(sentiment_score),
    };

    let action_stance = stance_matrix(environment_band, sent_zone);
    let primary_regime = classify_primary_regime(
        environment_band,
        sent_zone,
        environment_score,
        input.spy_drawdown_from_ath_pct,
        input.breadth_ma200_pct,
        input.volatility.score,
        sentiment_score,
    );

    // Exposure
    let mut raw = logistic_exposure_pct(environment_score);
    let mut quality_haircut_applied = false;
    if quality_score < -30 && input.quality.confidence_bps >= 3000 {
        raw *= 0.80;
        quality_haircut_applied = true;
    } else if quality_score < -10 && input.quality.confidence_bps >= 3000 {
        raw *= 0.90;
        quality_haircut_applied = true;
    }

    let mut crisis_cap_applied = false;
    if environment_band == "Crisis" || (input.volatility.score >= 75 && breadth_crash) {
        raw = raw.min(35.0);
        crisis_cap_applied = true;
    }

    raw = raw.clamp(15.0, 100.0);
    let stepped = round_to_step(raw, 5.0).clamp(15, 100);
    let suggested_exposure_pct = hysteresis_u32(input.prev_exposure_pct, stepped, 5);

    let cash_buffer_pct = (100u32.saturating_sub(suggested_exposure_pct)).min(50);

    let (new_risk_multiplier_bps, add_bias, prefer_quality) =
        stance_risk_params(action_stance, suggested_exposure_pct);

    // Global confidence: mean of available pillar confidences × coverage
    let confs = [
        input.trend.confidence_bps,
        input.breadth.confidence_bps,
        input.volatility.confidence_bps,
        input.sentiment.confidence_bps,
        input.cross_asset.confidence_bps,
        input.quality.confidence_bps,
    ];
    let present: Vec<u32> = confs.into_iter().filter(|&c| c > 0).collect();
    let mean_conf = if present.is_empty() {
        0
    } else {
        present.iter().sum::<u32>() / present.len() as u32
    };
    // Coverage: how many pillars have conf > 20%
    let active = present.iter().filter(|&&c| c >= 2000).count();
    let coverage = (active as f64 / 6.0).clamp(0.25, 1.0);
    let global_confidence_bps = ((mean_conf as f64) * coverage).round() as u32;

    CompositeOutput {
        environment_score,
        sentiment_score,
        quality_score,
        environment_band,
        action_stance,
        primary_regime,
        suggested_exposure_pct,
        cash_buffer_pct,
        new_risk_multiplier_bps,
        add_bias,
        prefer_quality,
        global_confidence_bps,
        weight_trend_bps: to_bps(wt),
        weight_breadth_bps: to_bps(wb),
        weight_vol_bps: to_bps(wv),
        weight_cross_bps: to_bps(wc),
        weight_quality_bps: to_bps(wq),
        crisis_cap_applied,
        quality_haircut_applied,
    }
}

pub fn env_band(e: i32) -> &'static str {
    if e <= -60 {
        "Crisis"
    } else if e <= -20 {
        "RiskOff"
    } else if e < 20 {
        "Neutral"
    } else if e < 60 {
        "RiskOn"
    } else {
        "StrongRiskOn"
    }
}

fn sentiment_zone_from_score(s: i32) -> &'static str {
    // S is contrarian: +100 extreme fear, −100 extreme greed
    if s >= 50 {
        "ExtremeFear"
    } else if s >= 20 {
        "Fear"
    } else if s > -20 {
        "Neutral"
    } else if s > -50 {
        "Greed"
    } else {
        "ExtremeGreed"
    }
}

/// Full Environment × Sentiment policy matrix.
pub fn stance_matrix(env: &str, sent: &str) -> &'static str {
    match (env, sent) {
        ("Crisis", "ExtremeFear") => "BloodInStreets",
        ("Crisis", "Fear") => "Defend",
        ("Crisis", "Neutral") => "Defend",
        ("Crisis", "Greed") => "Denial",
        ("Crisis", "ExtremeGreed") => "UnstableBlowoff",

        ("RiskOff", "ExtremeFear") => "Washout",
        ("RiskOff", "Fear") => "SelectiveBuy",
        ("RiskOff", "Neutral") => "Hold",
        ("RiskOff", "Greed") => "Reduce",
        ("RiskOff", "ExtremeGreed") => "Denial",

        ("Neutral", "ExtremeFear") => "Accumulate",
        ("Neutral", "Fear") => "SelectiveBuy",
        ("Neutral", "Neutral") => "Neutral",
        ("Neutral", "Greed") => "HoldTrim",
        ("Neutral", "ExtremeGreed") => "Reduce",

        ("RiskOn", "ExtremeFear") => "HealthyPullback",
        ("RiskOn", "Fear") => "Deploy",
        ("RiskOn", "Neutral") => "TrendDeploy",
        ("RiskOn", "Greed") => "HoldTrim",
        ("RiskOn", "ExtremeGreed") => "Euphoria",

        ("StrongRiskOn", "ExtremeFear") => "Deploy",
        ("StrongRiskOn", "Fear") => "TrendDeploy",
        ("StrongRiskOn", "Neutral") => "TrendDeploy",
        ("StrongRiskOn", "Greed") => "Euphoria",
        ("StrongRiskOn", "ExtremeGreed") => "Distribute",

        ("Unknown", _) | (_, _) => {
            if env == "Unknown" {
                "Unknown"
            } else {
                "Mixed"
            }
        }
    }
}

fn classify_primary_regime(
    env: &str,
    sent: &str,
    e: i32,
    dd: Option<f64>,
    breadth200: Option<f64>,
    stress: i32,
    sentiment: i32,
) -> &'static str {
    let dd = dd.unwrap_or(0.0);
    let br = breadth200.unwrap_or(50.0);
    let narrow = br < 40.0 && e > 20;

    if env == "Crisis" && (sent == "ExtremeFear" || stress >= 70) {
        return "Capitulation";
    }
    if env == "Crisis" || (e < -40 && dd >= 20.0) {
        return "Bear";
    }
    // Snapback: recovering from deep fear while still scared
    if e > -20 && e < 40 && sentiment >= 30 && dd >= 10.0 && stress < 60 {
        return "Snapback";
    }
    if env == "RiskOff" && dd >= 5.0 && dd < 20.0 {
        return "Correction";
    }
    if env == "RiskOff" {
        return "Bear";
    }
    if env == "StrongRiskOn" && sent != "ExtremeGreed" && br >= 50.0 {
        return "StrongBull";
    }
    if (env == "RiskOn" || env == "StrongRiskOn") && (sent == "ExtremeGreed" || narrow) {
        return "LateBull";
    }
    if env == "RiskOn" || env == "StrongRiskOn" {
        return "Bull";
    }
    if env == "Neutral" && stress < 25 {
        return "Range";
    }
    if env == "Neutral" {
        return "Range";
    }
    "Unknown"
}

fn stance_risk_params(stance: &str, ceiling: u32) -> (i32, i32, bool) {
    let base = ceiling as f64 / 100.0;
    let (mult, bias, quality) = match stance {
        "BloodInStreets" => (base * 0.55, 2, true),
        "Washout" => (base * 0.65, 2, true),
        "Accumulate" => (base * 0.80, 1, true),
        "SelectiveBuy" => (base * 0.70, 1, true),
        "HealthyPullback" => (base * 1.05, 1, false),
        "Deploy" => (base * 1.05, 1, false),
        "TrendDeploy" => (base * 1.10, 1, false),
        "Neutral" | "Hold" => (base * 0.90, 0, false),
        "HoldTrim" => (base * 0.75, -1, false),
        "Reduce" => (base * 0.60, -1, true),
        "Euphoria" => (base * 0.65, -2, true),
        "Distribute" => (base * 0.55, -2, true),
        "Denial" => (base * 0.45, -2, true),
        "Defend" => (base * 0.50, -1, true),
        "UnstableBlowoff" => (base * 0.35, -2, true),
        _ => (base, 0, false),
    };
    let bps = clamp_i32((mult * 10_000.0).round() as i32, 2500, 12_500);
    (bps, bias, quality)
}

/// Compat: map rich environment band → old regime labels.
pub fn compat_regime(band: &str) -> &'static str {
    match band {
        "StrongRiskOn" | "RiskOn" => "RiskOn",
        "RiskOff" | "Crisis" => "RiskOff",
        "Neutral" => "Neutral",
        _ => "Unknown",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::regime::types::PillarResult;

    fn pillar(score: i32, conf: u32) -> PillarResult {
        PillarResult {
            score,
            confidence_bps: conf,
            signals: vec![],
            stale: false,
        }
    }

    fn base_input() -> CompositeInput {
        CompositeInput {
            trend: pillar(40, 8000),
            breadth: pillar(30, 8000),
            volatility: pillar(10, 8000),
            sentiment: pillar(0, 8000),
            cross_asset: pillar(20, 6000),
            quality: pillar(10, 6000),
            spy_drawdown_from_ath_pct: Some(3.0),
            breadth_ma200_pct: Some(60.0),
            vix_term_ratio: Some(0.92),
            cnn_fng: Some(50.0),
            prev_exposure_pct: None,
        }
    }

    #[test]
    fn risk_on_neutral_is_trend_deploy() {
        let out = compose(&base_input());
        assert_eq!(out.environment_band, "RiskOn");
        assert_eq!(out.action_stance, "TrendDeploy");
        assert!(out.suggested_exposure_pct >= 55);
    }

    #[test]
    fn extreme_greed_on_risk_on_is_euphoria() {
        let mut inp = base_input();
        inp.cnn_fng = Some(85.0);
        inp.sentiment = pillar(-70, 9000);
        let out = compose(&inp);
        assert_eq!(out.action_stance, "Euphoria");
        assert!(out.add_bias < 0);
    }

    #[test]
    fn extreme_fear_crisis_is_blood_in_streets() {
        let mut inp = base_input();
        inp.trend = pillar(-70, 9000);
        inp.breadth = pillar(-80, 9000);
        inp.volatility = pillar(85, 9000);
        inp.cross_asset = pillar(-50, 7000);
        inp.quality = pillar(-40, 7000);
        inp.cnn_fng = Some(12.0);
        inp.sentiment = pillar(80, 9000);
        inp.breadth_ma200_pct = Some(22.0);
        inp.vix_term_ratio = Some(1.15);
        inp.spy_drawdown_from_ath_pct = Some(25.0);
        let out = compose(&inp);
        assert_eq!(out.environment_band, "Crisis");
        assert_eq!(out.action_stance, "BloodInStreets");
        assert!(out.suggested_exposure_pct <= 40);
        assert!(out.add_bias > 0);
        assert!(out.prefer_quality);
    }

    #[test]
    fn stance_matrix_all_cells_nonempty() {
        let envs = ["Crisis", "RiskOff", "Neutral", "RiskOn", "StrongRiskOn"];
        let sents = ["ExtremeFear", "Fear", "Neutral", "Greed", "ExtremeGreed"];
        for e in envs {
            for s in sents {
                let st = stance_matrix(e, s);
                assert!(!st.is_empty(), "{e} x {s}");
                assert_ne!(st, "Mixed", "{e} x {s} should be explicit");
            }
        }
    }

    #[test]
    fn fear_does_not_raise_ceiling_alone() {
        let mut calm = base_input();
        calm.cnn_fng = Some(50.0);
        let a = compose(&calm).suggested_exposure_pct;

        let mut fear = base_input();
        fear.cnn_fng = Some(10.0);
        fear.sentiment = pillar(90, 9000);
        let b = compose(&fear).suggested_exposure_pct;

        // Same environment → ceiling should not jump up just because of fear
        assert!(
            (a as i32 - b as i32).abs() <= 10,
            "ceiling should be env-driven, got {a} vs {b}"
        );
    }

    #[test]
    fn late_bull_on_greed_and_narrow_breadth() {
        let mut inp = base_input();
        inp.cnn_fng = Some(82.0);
        inp.sentiment = pillar(-65, 9000);
        inp.breadth_ma200_pct = Some(35.0);
        let out = compose(&inp);
        assert_eq!(out.primary_regime, "LateBull");
    }
}
