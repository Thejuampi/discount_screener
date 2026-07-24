//! Deterministic thesis / bullet templates (ES + EN).

use super::composite::CompositeOutput;
use super::types::MarketRegime;

pub fn fill_narrative(regime: &mut MarketRegime, comp: &CompositeOutput) {
    let fg = regime
        .cnn_fear_greed
        .map(|v| v.to_string())
        .unwrap_or_else(|| "—".into());
    let fg_label = regime
        .cnn_fear_greed_label
        .clone()
        .unwrap_or_else(|| "n/d".into());
    let exp = regime.suggested_exposure_pct;
    let mult = regime.new_risk_multiplier_bps as f64 / 10_000.0;
    let br = regime
        .breadth_above_ma200_pct
        .map(|b| format!("{b:.0}%"))
        .unwrap_or_else(|| "—".into());
    let vix = regime
        .vix
        .map(|v| format!("{v:.1}"))
        .unwrap_or_else(|| "—".into());
    let dd = regime
        .spy_drawdown_from_ath_pct
        .map(|d| format!("{d:.1}%"))
        .unwrap_or_else(|| "—".into());

    let (thesis_es, thesis_en) = match (comp.primary_regime, comp.action_stance) {
        ("Capitulation", "BloodInStreets") => (
            format!(
                "Capitulación: techo {exp}% (tape hostil) pero F&G {fg} ({fg_label}) implica sesgo de acumulación selectiva. New risk {mult:.2}×. Priorizá calidad; no all-in."
            ),
            format!(
                "Capitulation: ceiling {exp}% (hostile tape) but F&G {fg} ({fg_label}) implies selective accumulation bias. New risk {mult:.2}×. Prefer quality; no all-in."
            ),
        ),
        ("LateBull", _) | (_, "Euphoria") | (_, "Distribute") => (
            format!(
                "Late bull / euforia: techo {exp}% pero no sumar FOMO. Stance {}; F&G {fg}; amplitud {br}."
            , stance_es(comp.action_stance)),
            format!(
                "Late bull / euphoria: ceiling {exp}% but do not FOMO-add. Stance {}; F&G {fg}; breadth {br}."
            , stance_en(comp.action_stance)),
        ),
        (_, "HealthyPullback") | (_, "Deploy") | (_, "TrendDeploy") => (
            format!(
                "Entorno constructivo ({}) con stance {}. Techo {exp}%, mult {mult:.2}×. F&G {fg} · VIX {vix}."
            , band_es(comp.environment_band), stance_es(comp.action_stance)),
            format!(
                "Constructive tape ({}) with stance {}. Ceiling {exp}%, mult {mult:.2}×. F&G {fg} · VIX {vix}."
            , band_en(comp.environment_band), stance_en(comp.action_stance)),
        ),
        (_, "Washout") | (_, "Accumulate") | (_, "SelectiveBuy") => (
            format!(
                "Miedo en el tape (F&G {fg}): sesgo a acumular con disciplina. Techo {exp}% · risk {mult:.2}× · SPY DD {dd}."
            ),
            format!(
                "Fear in the tape (F&G {fg}): disciplined accumulate bias. Ceiling {exp}% · risk {mult:.2}× · SPY DD {dd}."
            ),
        ),
        (_, "Defend") | (_, "Denial") => (
            format!(
                "Modo defensa: entorno {} y sentimiento inconsistente. Techo {exp}%, preferí calidad y cash buffer {}%."
            , band_es(comp.environment_band), regime.cash_buffer_pct),
            format!(
                "Defend mode: {} environment with inconsistent sentiment. Ceiling {exp}%, prefer quality and {}% cash buffer."
            , band_en(comp.environment_band), regime.cash_buffer_pct),
        ),
        _ => (
            format!(
                "Régimen {}: entorno {}, stance {}. Exposición techo {exp}% · F&G {fg} · amplitud {br}."
            , phase_es(comp.primary_regime), band_es(comp.environment_band), stance_es(comp.action_stance)),
            format!(
                "Regime {}: environment {}, stance {}. Exposure ceiling {exp}% · F&G {fg} · breadth {br}."
            , phase_en(comp.primary_regime), band_en(comp.environment_band), stance_en(comp.action_stance)),
        ),
    };

    regime.thesis_es = thesis_es;
    regime.thesis_en = thesis_en;

    // Bullets from pillars already in notes; add policy bullets
    if comp.crisis_cap_applied {
        regime
            .notes_es
            .push("Cap de crisis aplicado al techo de exposición".into());
        regime
            .notes_en
            .push("Crisis cap applied to exposure ceiling".into());
    }
    if comp.quality_haircut_applied {
        regime
            .notes_es
            .push("Haircut por calidad/fragilidad del mercado".into());
        regime
            .notes_en
            .push("Haircut for market quality/fragility".into());
    }
    if regime.prefer_quality {
        regime
            .notes_es
            .push("Preferir nombres de calidad / menor beta".into());
        regime
            .notes_en
            .push("Prefer quality / lower-beta names".into());
    }
}

fn phase_es(p: &str) -> &'static str {
    match p {
        "StrongBull" => "bull fuerte",
        "Bull" => "bull",
        "LateBull" => "late bull",
        "Range" => "rango",
        "Correction" => "corrección",
        "Bear" => "bear",
        "Capitulation" => "capitulación",
        "Snapback" => "snapback",
        _ => "desconocido",
    }
}

fn phase_en(p: &str) -> &'static str {
    match p {
        "StrongBull" => "strong bull",
        "Bull" => "bull",
        "LateBull" => "late bull",
        "Range" => "range",
        "Correction" => "correction",
        "Bear" => "bear",
        "Capitulation" => "capitulation",
        "Snapback" => "snapback",
        _ => "unknown",
    }
}

fn band_es(b: &str) -> &'static str {
    match b {
        "StrongRiskOn" => "risk-on fuerte",
        "RiskOn" => "risk-on",
        "Neutral" => "neutral",
        "RiskOff" => "risk-off",
        "Crisis" => "crisis",
        _ => "sin datos",
    }
}

fn band_en(b: &str) -> &'static str {
    match b {
        "StrongRiskOn" => "strong risk-on",
        "RiskOn" => "risk-on",
        "Neutral" => "neutral",
        "RiskOff" => "risk-off",
        "Crisis" => "crisis",
        _ => "unknown",
    }
}

pub fn stance_es(s: &str) -> &'static str {
    match s {
        "BloodInStreets" => "sangre en las calles",
        "Washout" => "lavado / washout",
        "Accumulate" => "acumular",
        "SelectiveBuy" => "compra selectiva",
        "HealthyPullback" => "pullback sano",
        "Deploy" => "desplegar",
        "TrendDeploy" => "seguir tendencia",
        "Neutral" => "neutral",
        "Hold" => "mantener",
        "HoldTrim" => "mantener / recortar",
        "Reduce" => "reducir",
        "Euphoria" => "euforia",
        "Distribute" => "distribuir",
        "Denial" => "negación",
        "Defend" => "defender",
        "UnstableBlowoff" => "blow-off inestable",
        "Mixed" => "mixto",
        _ => "desconocido",
    }
}

pub fn stance_en(s: &str) -> &'static str {
    match s {
        "BloodInStreets" => "blood in the streets",
        "Washout" => "washout",
        "Accumulate" => "accumulate",
        "SelectiveBuy" => "selective buy",
        "HealthyPullback" => "healthy pullback",
        "Deploy" => "deploy",
        "TrendDeploy" => "trend deploy",
        "Neutral" => "neutral",
        "Hold" => "hold",
        "HoldTrim" => "hold / trim",
        "Reduce" => "reduce",
        "Euphoria" => "euphoria",
        "Distribute" => "distribute",
        "Denial" => "denial",
        "Defend" => "defend",
        "UnstableBlowoff" => "unstable blow-off",
        "Mixed" => "mixed",
        _ => "unknown",
    }
}
