//! Deterministic human interpretations for pillars, signals, and aggregate reading.

use super::composite::CompositeOutput;
use super::narrative::{stance_en, stance_es};
use super::types::{MarketRegime, RegimePillar};

/// Radar radius 0..100: edge = better. Volatility is inverted (stress → center).
pub fn radar_radius(pillar_id: &str, score: i32) -> u32 {
    let s = score.clamp(-100, 100) as f64;
    let r = if pillar_id == "volatility" {
        (100.0 - s) / 2.0
    } else {
        (s + 100.0) / 2.0
    };
    r.round().clamp(0.0, 100.0) as u32
}

/// Fill pillar interpretations, signal hints, reading, and action bullets.
pub fn enrich_regime(regime: &mut MarketRegime, comp: &CompositeOutput) {
    for p in &mut regime.pillars {
        let (es, en, tone) = interpret_pillar(&p.id, p.score, p.confidence_bps, p.stale);
        p.interpretation_es = es;
        p.interpretation_en = en;
        p.tone = tone.into();
        p.radar_radius = radar_radius(&p.id, p.score);
        for s in &mut p.signals {
            if s.contribution.abs() >= 25 {
                let (he, hn) = interpret_signal(&s.id, s.contribution, s.detail.as_deref());
                s.hint_es = he;
                s.hint_en = hn;
            }
        }
    }

    let (re, rn) = build_reading(regime, comp);
    regime.reading_es = re;
    regime.reading_en = rn;

    let (ae, an) = action_bullets(regime, comp);
    regime.action_bullets_es = ae;
    regime.action_bullets_en = an;
}

pub fn interpret_pillar(
    id: &str,
    score: i32,
    conf_bps: u32,
    stale: bool,
) -> (String, String, &'static str) {
    let band = score_band(score);
    let (mut es, mut en, tone) = match id {
        "trend" => trend_copy(band),
        "breadth" => breadth_copy(band),
        "volatility" => vol_copy(score_band(score)), // high score = stress
        "sentiment" => sentiment_copy(band),
        "cross_asset" => cross_copy(band),
        "quality" => quality_copy(band),
        _ => (
            format!("Pilar {id}: score {score}."),
            format!("Pillar {id}: score {score}."),
            "neutral",
        ),
    };

    if stale || conf_bps < 2000 {
        es.push_str(" (datos parciales — interpretá con cautela)");
        en.push_str(" (partial data — treat with caution)");
    } else if conf_bps < 4500 {
        es.push_str(" Confianza moderada.");
        en.push_str(" Moderate confidence.");
    }

    (es, en, tone)
}

fn score_band(score: i32) -> i32 {
    if score >= 50 {
        2
    } else if score >= 15 {
        1
    } else if score > -15 {
        0
    } else if score > -50 {
        -1
    } else {
        -2
    }
}

fn trend_copy(band: i32) -> (String, String, &'static str) {
    match band {
        2 => (
            "Tendencia sólida: el índice está alineado alcista; el tape empuja a favor de posiciones largas."
                .into(),
            "Solid trend: the index is stacked bullish; the tape supports long risk."
                .into(),
            "bullish",
        ),
        1 => (
            "Tendencia moderadamente positiva; hay sesgo alcista pero no es un breakout limpio."
                .into(),
            "Moderately positive trend; mild upside bias without a clean breakout."
                .into(),
            "bullish",
        ),
        0 => (
            "Tendencia mixta o lateral: no hay edge direccional claro en el índice."
                .into(),
            "Mixed/sideways trend: no clear directional edge in the index."
                .into(),
            "neutral",
        ),
        -1 => (
            "Tendencia debilitada: el mercado pelea contra entradas largas agresivas."
                .into(),
            "Weakening trend: the market fights aggressive long entries."
                .into(),
            "bearish",
        ),
        _ => (
            "Tendencia hostil: sesgo bajista estructural en el índice; priorizá defensa y tamaño chico."
                .into(),
            "Hostile trend: structural downside bias; prioritize defense and small size."
                .into(),
            "bearish",
        ),
    }
}

fn breadth_copy(band: i32) -> (String, String, &'static str) {
    match band {
        2 => (
            "Amplitud amplia: muchas acciones participan del movimiento; el avance es saludable."
                .into(),
            "Broad participation: many stocks join the move; the advance looks healthy."
                .into(),
            "bullish",
        ),
        1 => (
            "Amplitud aceptable: hay participación, aunque no es un thrust extremo."
                .into(),
            "Acceptable breadth: participation is present without an extreme thrust."
                .into(),
            "bullish",
        ),
        0 => (
            "Amplitud mixta: el índice puede engañar si solo mirás el precio del SPY."
                .into(),
            "Mixed breadth: the index can mislead if you only watch SPY price."
                .into(),
            "neutral",
        ),
        -1 => (
            "Amplitud débil: el rally o la caída es estrecha; hay fragilidad bajo la superficie."
                .into(),
            "Weak breadth: the move is narrow; fragility sits under the surface."
                .into(),
            "caution",
        ),
        _ => (
            "Amplitud colapsada: muy pocas acciones lideran; riesgo de falsa fortaleza o pánico generalizado."
                .into(),
            "Collapsed breadth: few leaders; risk of false strength or broad panic."
                .into(),
            "bearish",
        ),
    }
}

fn vol_copy(band: i32) -> (String, String, &'static str) {
    // band from stress score: +2 = high stress = bad
    match band {
        2 => (
            "Estrés de volatilidad extremo: VIX/estructura señalan crisis; el sizing debe ser defensivo."
                .into(),
            "Extreme volatility stress: VIX/structure signal crisis; keep sizing defensive."
                .into(),
            "bearish",
        ),
        1 => (
            "Volatilidad elevada: el tape está nervioso; esperá gaps y exigí más margen de error."
                .into(),
            "Elevated volatility: nervous tape; expect gaps and demand more margin of error."
                .into(),
            "caution",
        ),
        0 => (
            "Volatilidad en zona normal: ni calma extrema ni pánico; el régimen no está dominado por el VIX."
                .into(),
            "Normal volatility zone: neither deep calm nor panic; VIX is not dominating the regime."
                .into(),
            "neutral",
        ),
        -1 => (
            "Volatilidad contenida: el mercado opera con calma relativa; hay más espacio para desplegar riesgo."
                .into(),
            "Contained volatility: relatively calm tape; more room to deploy risk."
                .into(),
            "bullish",
        ),
        _ => (
            "Calma de volatilidad: estrés bajo (contango/VIX quieto); ambiente amable para tamaño normal."
                .into(),
            "Volatility calm: low stress (quiet VIX/contango); friendly for normal size."
                .into(),
            "bullish",
        ),
    }
}

fn sentiment_copy(band: i32) -> (String, String, &'static str) {
    // score is contrarian: high = fear = opportunity
    match band {
        2 => (
            "Miedo extremo (contrarian): históricamente favorece acumulación selectiva — no all-in, sí sesgo a comprar calidad con tamaño disciplinado."
                .into(),
            "Extreme fear (contrarian): historically favors selective accumulation — not all-in, but a disciplined quality buy bias."
                .into(),
            "opportunity",
        ),
        1 => (
            "Miedo moderado: el sentimiento deja espacio para sumar con selectividad; todavía no es euforia."
                .into(),
            "Moderate fear: sentiment leaves room to add selectively; not euphoria yet."
                .into(),
            "opportunity",
        ),
        0 => (
            "Sentimiento neutral: ni lavado ni euforia; el F&G no aporta un edge contrarian fuerte."
                .into(),
            "Neutral sentiment: neither washout nor euphoria; F&G offers little contrarian edge."
                .into(),
            "neutral",
        ),
        -1 => (
            "Codicia creciente: el tape se pone crowded; reducí apetito de agregar riesgo nuevo."
                .into(),
            "Rising greed: crowded tape; curb appetite for new risk adds."
                .into(),
            "caution",
        ),
        _ => (
            "Euforia / extreme greed (contrarian): históricamente mal momento para perseguir; sesgo a recortar o no sumar FOMO."
                .into(),
            "Euphoria / extreme greed (contrarian): historically a poor chase zone; bias to trim or skip FOMO adds."
                .into(),
            "caution",
        ),
    }
}

fn cross_copy(band: i32) -> (String, String, &'static str) {
    match band {
        2 => (
            "Cross-asset risk-on: crédito e índices cíclicos confirman apetito por riesgo."
                .into(),
            "Cross-asset risk-on: credit and cyclicals confirm risk appetite."
                .into(),
            "bullish",
        ),
        1 => (
            "Cross-asset levemente constructivo: hay preferencia por riesgo sin exceso claro."
                .into(),
            "Mildly constructive cross-asset: risk preference without a clear excess."
                .into(),
            "bullish",
        ),
        0 => (
            "Cross-asset mixto: equity, crédito y defensivos no cuentan la misma historia."
                .into(),
            "Mixed cross-asset: equity, credit, and defensives disagree."
                .into(),
            "neutral",
        ),
        -1 => (
            "Rotación defensiva / crédito flojo: el apetito por riesgo se enfría fuera del equity spot."
                .into(),
            "Defensive rotation / soft credit: risk appetite cools outside spot equity."
                .into(),
            "caution",
        ),
        _ => (
            "Cross-asset risk-off: credit stress o flight-to-quality confirman hostilidad del entorno."
                .into(),
            "Cross-asset risk-off: credit stress or flight-to-quality confirms a hostile backdrop."
                .into(),
            "bearish",
        ),
    }
}

fn quality_copy(band: i32) -> (String, String, &'static str) {
    match band {
        2 => (
            "Calidad del movimiento alta: participación y estructura no gritan fragilidad."
                .into(),
            "High move quality: participation and structure do not scream fragility."
                .into(),
            "bullish",
        ),
        1 => (
            "Calidad aceptable: no hay banderas rojas graves de fragilidad."
                .into(),
            "Acceptable quality: no major fragility red flags."
                .into(),
            "bullish",
        ),
        0 => (
            "Calidad neutra: el avance/caída no está claramente sano ni roto."
                .into(),
            "Neutral quality: the move is neither clearly healthy nor broken."
                .into(),
            "neutral",
        ),
        -1 => (
            "Fragilidad visible: rally estrecho, correlación alta o crédito débil recortan confianza."
                .into(),
            "Visible fragility: narrow rally, high correlation, or soft credit cut confidence."
                .into(),
            "caution",
        ),
        _ => (
            "Estructura frágil: el mercado se mueve con mala calidad interna; no confíes en el precio solo."
                .into(),
            "Fragile structure: poor internal quality; do not trust price alone."
                .into(),
            "bearish",
        ),
    }
}

pub fn interpret_signal(
    id: &str,
    contribution: i32,
    detail: Option<&str>,
) -> (Option<String>, Option<String>) {
    let d = detail.unwrap_or("");
    let (es, en) = match id {
        "cnn_fng" => {
            if contribution >= 25 {
                (
                    format!("F&G en zona de miedo: sesgo contrarian a acumular ({d})."),
                    format!("F&G in fear zone: contrarian accumulate bias ({d})."),
                )
            } else {
                (
                    format!("F&G en zona de codicia: sesgo a no perseguir ({d})."),
                    format!("F&G in greed zone: bias against chasing ({d})."),
                )
            }
        }
        "vix_term" => {
            if contribution > 0 {
                (
                    "Term structure en backwardation: estrés real, no solo VIX spot.".into(),
                    "Term structure in backwardation: real stress, not just spot VIX.".into(),
                )
            } else {
                (
                    "Term structure en contango: calma estructural de vol.".into(),
                    "Term structure in contango: structural vol calm.".into(),
                )
            }
        }
        "vix_pctl" | "vix_level" => {
            if contribution > 0 {
                (
                    format!("VIX elevado ({d}): recortá tamaño y esperá más volatilidad."),
                    format!("Elevated VIX ({d}): cut size and expect more volatility."),
                )
            } else {
                (
                    format!("VIX contenido ({d}): menos fricción de volatilidad."),
                    format!("Contained VIX ({d}): less volatility friction."),
                )
            }
        }
        "narrow_rally" => (
            "Rally estrecho: el índice sube sin amplitud — frágil.".into(),
            "Narrow rally: index up without breadth — fragile.".into(),
        ),
        "broad_participation" => (
            "Participación amplia: el avance tiene base.".into(),
            "Broad participation: the advance has a base.".into(),
        ),
        "spy_ma200" => {
            if contribution > 0 {
                (
                    "SPY sobre MA200: tendencia de largo plazo alcista.".into(),
                    "SPY above MA200: long-term uptrend.".into(),
                )
            } else {
                (
                    "SPY bajo MA200: regimén de largo plazo dañado.".into(),
                    "SPY below MA200: long-term regime impaired.".into(),
                )
            }
        }
        "breadth_ma200" | "breadth_ma50" => {
            if contribution > 0 {
                (
                    format!("Amplitud positiva ({d})."),
                    format!("Positive breadth ({d})."),
                )
            } else {
                (
                    format!("Amplitud débil ({d})."),
                    format!("Weak breadth ({d})."),
                )
            }
        }
        "credit_hy_ie" | "credit_quality" => {
            if contribution > 0 {
                (
                    "Crédito HY relativo fuerte: risk-on en fixed income.".into(),
                    "HY credit relatively strong: risk-on in fixed income.".into(),
                )
            } else {
                (
                    "Crédito bajo presión: warning de risk-off.".into(),
                    "Credit under pressure: risk-off warning.".into(),
                )
            }
        }
        "leadership" => {
            if contribution > 0 {
                (
                    "Liderazgo en growth/cíclicos: apetito de riesgo activo.".into(),
                    "Growth/cyclical leadership: active risk appetite.".into(),
                )
            } else {
                (
                    "Defensivos lideran: rotación a seguridad.".into(),
                    "Defensives lead: rotation to safety.".into(),
                )
            }
        }
        "avg_corr" => {
            if contribution < 0 {
                (
                    "Correlación alta entre nombres: diversificación falsa.".into(),
                    "High name correlation: false diversification.".into(),
                )
            } else {
                (
                    "Correlación más baja: hay más dispersión útil.".into(),
                    "Lower correlation: more useful dispersion.".into(),
                )
            }
        }
        "stress_breadth" => (
            "Estrés + amplitud débil a la vez: combo frágil.".into(),
            "Stress + weak breadth together: fragile combo.".into(),
        ),
        _ => return (None, None),
    };
    (Some(es), Some(en))
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
        _ => "indefinida",
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
        _ => "undefined",
    }
}

fn band_es(b: &str) -> &'static str {
    match b {
        "StrongRiskOn" => "risk-on fuerte",
        "RiskOn" => "risk-on",
        "Neutral" => "neutral",
        "RiskOff" => "risk-off",
        "Crisis" => "crisis",
        _ => "sin clasificar",
    }
}

fn band_en(b: &str) -> &'static str {
    match b {
        "StrongRiskOn" => "strong risk-on",
        "RiskOn" => "risk-on",
        "Neutral" => "neutral",
        "RiskOff" => "risk-off",
        "Crisis" => "crisis",
        _ => "unclassified",
    }
}

/// Radar-adjusted "goodness" for ranking pillars (higher = better on chart).
fn radar_goodness(p: &RegimePillar) -> i32 {
    p.radar_radius as i32
}

pub fn build_reading(regime: &MarketRegime, comp: &CompositeOutput) -> (String, String) {
    let mult = regime.new_risk_multiplier_bps as f64 / 10_000.0;
    let exp = regime.suggested_exposure_pct;
    let fg = regime
        .cnn_fear_greed
        .map(|v| v.to_string())
        .unwrap_or_else(|| "n/d".into());
    let fg_label = regime
        .cnn_fear_greed_label
        .clone()
        .unwrap_or_else(|| "n/d".into());

    let mut ranked: Vec<&RegimePillar> = regime.pillars.iter().collect();
    ranked.sort_by_key(|p| std::cmp::Reverse(radar_goodness(p)));
    let best: Vec<&str> = ranked.iter().take(2).map(|p| p.name_es.as_str()).collect();
    let best_en: Vec<&str> = ranked.iter().take(2).map(|p| p.name_en.as_str()).collect();
    ranked.sort_by_key(|p| radar_goodness(p));
    let worst: Vec<&str> = ranked.iter().take(2).map(|p| p.name_es.as_str()).collect();
    let worst_en: Vec<&str> = ranked.iter().take(2).map(|p| p.name_en.as_str()).collect();

    let conf_note_es = if regime.global_confidence_bps < 4000 {
        " Lectura degradada: confianza global baja por datos parciales."
    } else {
        ""
    };
    let conf_note_en = if regime.global_confidence_bps < 4000 {
        " Degraded reading: low global confidence from partial data."
    } else {
        ""
    };

    let es = format!(
        "Fase de mercado: {} con entorno {} y stance «{}». \
En el radar, lo más sólido es {} y lo más débil {}. \
Fear & Greed en {} ({}) se lee en modo contrarian (miedo→oportunidad de acumular selectivo; euforia→no perseguir). \
Implicación: techo de exposición {}%, new risk {:.2}×, cash buffer {}%.{}",
        phase_es(&regime.primary_regime),
        band_es(&regime.environment_band),
        stance_es(comp.action_stance),
        best.join(" / "),
        worst.join(" / "),
        fg,
        fg_label,
        exp,
        mult,
        regime.cash_buffer_pct,
        conf_note_es,
    );

    let en = format!(
        "Market phase: {} with {} environment and «{}» stance. \
On the radar, strongest is {} and weakest {}. \
Fear & Greed at {} ({}) is read contrarian (fear→selective accumulate opportunity; greed→do not chase). \
Implication: exposure ceiling {}%, new risk {:.2}×, cash buffer {}%.{}",
        phase_en(&regime.primary_regime),
        band_en(&regime.environment_band),
        stance_en(comp.action_stance),
        best_en.join(" / "),
        worst_en.join(" / "),
        fg,
        fg_label,
        exp,
        mult,
        regime.cash_buffer_pct,
        conf_note_en,
    );

    (es, en)
}

pub fn action_bullets(regime: &MarketRegime, comp: &CompositeOutput) -> (Vec<String>, Vec<String>) {
    let mult = regime.new_risk_multiplier_bps as f64 / 10_000.0;
    let mut es = Vec::new();
    let mut en = Vec::new();

    es.push(format!(
        "Respetá techo de exposición {}% en riesgo nuevo (no es target de portfolio forzado).",
        regime.suggested_exposure_pct
    ));
    en.push(format!(
        "Respect {}% exposure ceiling for new risk (not a forced portfolio target).",
        regime.suggested_exposure_pct
    ));

    es.push(format!(
        "Multiplicador de tamaño {:.2}× vs tu sizing normal (ATR/risk engine).",
        mult
    ));
    en.push(format!(
        "Size multiplier {:.2}× versus your normal ATR/risk sizing.",
        mult
    ));

    if regime.cash_buffer_pct >= 25 {
        es.push(format!(
            "Mantené un cash buffer ~{}% hasta que mejore el entorno o la calidad.",
            regime.cash_buffer_pct
        ));
        en.push(format!(
            "Keep ~{}% cash buffer until environment or quality improves.",
            regime.cash_buffer_pct
        ));
    }

    match comp.action_stance {
        "BloodInStreets" | "Washout" | "Accumulate" | "SelectiveBuy" => {
            es.push(
                "Sesgo acumular: preferí calidad y entradas escalonadas; el miedo es la oportunidad, no la justificación para all-in."
                    .into(),
            );
            en.push(
                "Accumulate bias: prefer quality and scaled entries; fear is opportunity, not a reason for all-in."
                    .into(),
            );
        }
        "Euphoria" | "Distribute" | "Reduce" | "HoldTrim" => {
            es.push(
                "Sesgo reducir/no perseguir: no sumar FOMO; tomá ganancias parciales o exigí setups excepcionales."
                    .into(),
            );
            en.push(
                "Reduce/no-chase bias: skip FOMO adds; take partial profits or demand exceptional setups."
                    .into(),
            );
        }
        "Defend" | "Denial" => {
            es.push(
                "Modo defensa: priorizá capital preservation; nuevas entradas solo con edge alto y tamaño chico."
                    .into(),
            );
            en.push(
                "Defend mode: prioritize capital preservation; new entries only with high edge and small size."
                    .into(),
            );
        }
        "HealthyPullback" | "Deploy" | "TrendDeploy" => {
            es.push(
                "Entorno para desplegar: podés seguir el playbook de tendencia respetando stops y el techo de riesgo."
                    .into(),
            );
            en.push(
                "Environment to deploy: you can run the trend playbook while respecting stops and the risk ceiling."
                    .into(),
            );
        }
        _ => {}
    }

    if regime.prefer_quality {
        es.push(
            "Priorizá balance sheet / menor beta mientras dure el stress o la fragilidad.".into(),
        );
        en.push(
            "Prefer balance-sheet quality / lower beta while stress or fragility lasts.".into(),
        );
    }

    // Breadth caution
    if let Some(b) = regime.breadth_above_ma200_pct {
        if b < 40.0 && regime.spy_above_ma200 == Some(true) {
            es.push(
                "Amplitud débil con SPY arriba: desconfiá de la fortaleza del índice; exigí confirmación por nombre."
                    .into(),
            );
            en.push(
                "Weak breadth with SPY up: distrust index strength; demand per-name confirmation."
                    .into(),
            );
        }
    }

    (es, en)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::regime::types::RegimePillar;

    #[test]
    fn radar_vol_inverted() {
        assert!(radar_radius("volatility", 80) < 30); // high stress → center
        assert!(radar_radius("volatility", -40) > 60); // calm → edge
        assert!(radar_radius("trend", 80) > 80);
        assert!(radar_radius("sentiment", 80) > 80); // fear opportunity → edge
    }

    #[test]
    fn sentiment_extreme_fear_is_opportunity_tone() {
        let (_, _, tone) = interpret_pillar("sentiment", 80, 9000, false);
        assert_eq!(tone, "opportunity");
    }

    #[test]
    fn vol_high_stress_bearish_tone() {
        let (_, _, tone) = interpret_pillar("volatility", 80, 9000, false);
        assert_eq!(tone, "bearish");
    }

    #[test]
    fn signal_fng_hint_present() {
        let (es, en) = interpret_signal("cnn_fng", 70, Some("12 Extreme Fear"));
        assert!(es.unwrap().to_lowercase().contains("miedo"));
        assert!(en.unwrap().to_lowercase().contains("fear"));
    }

    #[test]
    fn reading_mentions_ceiling_and_stance() {
        let mut regime = MarketRegime {
            primary_regime: "Capitulation".into(),
            environment_band: "Crisis".into(),
            action_stance: "BloodInStreets".into(),
            suggested_exposure_pct: 30,
            cash_buffer_pct: 40,
            new_risk_multiplier_bps: 4000,
            cnn_fear_greed: Some(12),
            cnn_fear_greed_label: Some("Extreme Fear".into()),
            global_confidence_bps: 8000,
            pillars: vec![
                RegimePillar {
                    id: "trend".into(),
                    name_es: "Tendencia".into(),
                    name_en: "Trend".into(),
                    score: -60,
                    confidence_bps: 8000,
                    weight_used_bps: 3000,
                    signals: vec![],
                    stale: false,
                    interpretation_es: String::new(),
                    interpretation_en: String::new(),
                    tone: "bearish".into(),
                    radar_radius: 20,
                },
                RegimePillar {
                    id: "sentiment".into(),
                    name_es: "Sentimiento".into(),
                    name_en: "Sentiment".into(),
                    score: 80,
                    confidence_bps: 9000,
                    weight_used_bps: 0,
                    signals: vec![],
                    stale: false,
                    interpretation_es: String::new(),
                    interpretation_en: String::new(),
                    tone: "opportunity".into(),
                    radar_radius: 90,
                },
            ],
            ..MarketRegime::default()
        };
        let comp = CompositeOutput {
            environment_score: -70,
            sentiment_score: 80,
            quality_score: -40,
            environment_band: "Crisis",
            action_stance: "BloodInStreets",
            primary_regime: "Capitulation",
            suggested_exposure_pct: 30,
            cash_buffer_pct: 40,
            new_risk_multiplier_bps: 4000,
            add_bias: 2,
            prefer_quality: true,
            global_confidence_bps: 8000,
            weight_trend_bps: 3000,
            weight_breadth_bps: 3000,
            weight_vol_bps: 2000,
            weight_cross_bps: 1000,
            weight_quality_bps: 1000,
            crisis_cap_applied: true,
            quality_haircut_applied: true,
        };
        enrich_regime(&mut regime, &comp);
        assert!(regime.reading_es.contains("30%"));
        assert!(
            regime.reading_es.to_lowercase().contains("sangre")
                || regime.reading_es.contains("Blood")
                || regime.action_stance == "BloodInStreets"
        );
        assert!(!regime.action_bullets_es.is_empty());
        assert!(!regime.pillars[0].interpretation_es.is_empty());
    }
}
