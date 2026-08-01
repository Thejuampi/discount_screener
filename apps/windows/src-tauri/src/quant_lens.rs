//! Quant Lens multi-section read-only report (Android QuantLensEngine port, condensed).
//!
//! Signal/noise goals:
//! - Do not crown a single FCFF/residual number as truth when it fights analyst anchors.
//! - Count independent evidence *families*, not every positive flag.
//! - Surface disagreement explicitly (Disputed / Mixed) instead of Strong + absurd upside.

use serde::Serialize;

use crate::dcf_model::{BusinessClass, DcfAnalysis, ValuationModel};
use crate::engine::{CandidateRow, ChartSummary, HistoricalCandle, SymbolDetail};
use crate::operating_valuation::{CandidateStatus, OperatingModel, RouteStatus};

#[derive(Debug, Clone, Serialize)]
pub struct QuantLensSection {
    pub id: String,
    pub title: String,
    pub status: String,
    pub summary: String,
    pub metrics: Vec<(String, String)>,
}

#[derive(Debug, Clone, Serialize)]
pub struct QuantLensReport {
    pub symbol: String,
    pub primary_status: String,
    pub sections: Vec<QuantLensSection>,
    pub model_version: i32,
}

/// Relative gap beyond which model vs analyst is treated as a conflict (not a hard price cap).
const MODEL_ANALYST_AGREE_BPS: i32 = 2_500; // 25%
const MODEL_ANALYST_SOFT_BPS: i32 = 5_000; // 50%
/// Scenario span (bull−bear)/base above this marks model quality as weak.
const WIDE_SCENARIO_BPS: i32 = 12_000; // 120%

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ModelQuality {
    /// Ordered scenarios, not provisional, moderate width.
    Solid,
    /// Usable but provisional inputs or wide scenarios.
    Soft,
    /// Structurally broken / incomplete — do not drive EV.
    Unusable,
}

pub fn analyze(
    detail: &SymbolDetail,
    daily_candles: Option<&[HistoricalCandle]>,
    dcf: Option<&DcfAnalysis>,
    opportunity: Option<&CandidateRow>,
    peers: &[(String, Vec<HistoricalCandle>)],
) -> QuantLensReport {
    let mut sections = Vec::new();

    sections.push(evidence_strength(detail, daily_candles, dcf));
    sections.push(expected_value_range(detail, dcf));
    sections.push(correlation_risk(daily_candles, peers));
    sections.push(trend_reliability(
        daily_candles,
        detail.chart_summary.as_ref(),
    ));
    sections.push(horizon_context(daily_candles));
    sections.push(similar_setups(opportunity, peers.len()));

    let primary = worst_status(sections.iter().map(|s| s.status.as_str()));
    QuantLensReport {
        symbol: detail.symbol.clone(),
        primary_status: primary.into(),
        sections,
        model_version: 4, // high-SNR evidence families + disputed EV
    }
}

fn scenarios_ordered(a: &DcfAnalysis) -> bool {
    a.bear_intrinsic_value_cents > 0
        && a.base_intrinsic_value_cents > 0
        && a.bull_intrinsic_value_cents > 0
        && a.bear_intrinsic_value_cents <= a.base_intrinsic_value_cents
        && a.base_intrinsic_value_cents <= a.bull_intrinsic_value_cents
}

fn scenario_width_bps(a: &DcfAnalysis) -> Option<i32> {
    let base = a.base_intrinsic_value_cents;
    if base <= 0 {
        return None;
    }
    let span = a.bull_intrinsic_value_cents - a.bear_intrinsic_value_cents;
    Some(((span as i128 * 10_000) / base as i128) as i32)
}

fn model_quality(dcf: Option<&DcfAnalysis>) -> ModelQuality {
    let Some(a) = dcf else {
        return ModelQuality::Unusable;
    };
    if a.model == ValuationModel::None || !scenarios_ordered(a) {
        return ModelQuality::Unusable;
    }
    let wide = scenario_width_bps(a).is_some_and(|w| w > WIDE_SCENARIO_BPS);
    let provisional = a.wacc_inputs.is_provisional();
    if provisional || wide {
        ModelQuality::Soft
    } else {
        ModelQuality::Solid
    }
}

fn usable_model(dcf: Option<&DcfAnalysis>) -> bool {
    !matches!(model_quality(dcf), ModelQuality::Unusable)
}

#[derive(Clone, Copy)]
struct RuntimeModelAnchor<'a> {
    base_cents: i64,
    low_cents: i64,
    high_cents: i64,
    label: &'static str,
    quality: ModelQuality,
    analyst_correlated: bool,
    dcf: Option<&'a DcfAnalysis>,
}

fn runtime_model_anchor<'a>(
    detail: &'a SymbolDetail,
    dcf: Option<&'a DcfAnalysis>,
) -> Option<RuntimeModelAnchor<'a>> {
    let route = detail.operating_valuation.as_ref();
    if let Some(envelope) = route {
        if envelope.decision.status != RouteStatus::Selected {
            return None;
        }
        match envelope.decision.selected_model? {
            OperatingModel::ForwardEarningsPower => {
                let candidate = &envelope.decision.forward_candidate;
                let base = candidate.intrinsic_value_cents?;
                return (candidate.status == CandidateStatus::Available).then_some(
                    RuntimeModelAnchor {
                        base_cents: base,
                        low_cents: 0,
                        high_cents: 0,
                        label: "Forward earnings value",
                        quality: ModelQuality::Soft,
                        analyst_correlated: true,
                        dcf: None,
                    },
                );
            }
            OperatingModel::FcffWacc => {
                let analysis = dcf?;
                return usable_model(Some(analysis)).then_some(RuntimeModelAnchor {
                    base_cents: analysis.base_intrinsic_value_cents,
                    low_cents: analysis.bear_intrinsic_value_cents,
                    high_cents: analysis.bull_intrinsic_value_cents,
                    label: valuation_source_label(analysis),
                    quality: model_quality(Some(analysis)),
                    analyst_correlated: false,
                    dcf: Some(analysis),
                });
            }
        }
    }

    let analysis = dcf.filter(|analysis| {
        usable_model(dcf) && analysis.business_class != BusinessClass::OperatingNonFinancial
    })?;
    Some(RuntimeModelAnchor {
        base_cents: analysis.base_intrinsic_value_cents,
        low_cents: analysis.bear_intrinsic_value_cents,
        high_cents: analysis.bull_intrinsic_value_cents,
        label: valuation_source_label(analysis),
        quality: model_quality(Some(analysis)),
        analyst_correlated: false,
        dcf: Some(analysis),
    })
}

fn complete_analyst(detail: &SymbolDetail) -> bool {
    detail.intrinsic_value_cents > 0
        && detail.low_fair_value_cents.is_some_and(|v| v > 0)
        && detail.high_fair_value_cents.is_some_and(|v| v > 0)
}

fn relative_disagreement_bps(a_cents: i64, b_cents: i64) -> Option<i32> {
    if a_cents <= 0 || b_cents <= 0 {
        return None;
    }
    let mid = (i128::from(a_cents) + i128::from(b_cents)) as f64 / 2.0;
    if mid <= 0.0 {
        return None;
    }
    let distance = (i128::from(a_cents) - i128::from(b_cents)).abs() as f64;
    Some(((distance / mid) * 10_000.0).round() as i32)
}

fn valuation_source_label(a: &DcfAnalysis) -> &'static str {
    match a.model {
        ValuationModel::ResidualIncomeEquity => "Residual income",
        ValuationModel::FcffWacc => "FCFF DCF",
        ValuationModel::None => "Unavailable",
    }
}

fn weighted_three(low: i64, base: i64, high: i64) -> i64 {
    if low > 0 && high > 0 {
        let weighted = i128::from(low)
            .saturating_add(i128::from(base).saturating_mul(2))
            .saturating_add(i128::from(high))
            / 4;
        i64::try_from(weighted).unwrap_or(i64::MAX)
    } else {
        base
    }
}

fn upside_bps(price: i64, fair: i64) -> i32 {
    if price > 0 && fair > 0 {
        let delta = i128::from(fair) - i128::from(price);
        ((delta as f64 / price as f64) * 10_000.0).round() as i32
    } else {
        0
    }
}

fn evidence_strength(
    detail: &SymbolDetail,
    candles: Option<&[HistoricalCandle]>,
    dcf: Option<&DcfAnalysis>,
) -> QuantLensSection {
    // Independent families — never double-count gap + analyst as two supports.
    let mut families_ok = 0;
    let mut conflict = 0;
    let mut notes: Vec<&str> = Vec::new();

    let analyst_ok = complete_analyst(detail);
    if analyst_ok {
        families_ok += 1;
        notes.push("analyst_range");
    }

    let anchor = runtime_model_anchor(detail, dcf);
    let mq = anchor.map_or(ModelQuality::Unusable, |value| value.quality);
    match mq {
        ModelQuality::Solid => {
            families_ok += 1;
            notes.push("model_solid");
        }
        ModelQuality::Soft => {
            if anchor.is_some_and(|value| value.analyst_correlated) && analyst_ok {
                notes.push("forward_model_correlated_with_analyst");
            } else {
                families_ok += 1;
                notes.push("model_soft");
            }
        }
        ModelQuality::Unusable => {}
    }

    if candles.is_some_and(|c| c.len() >= 20) {
        families_ok += 1;
        notes.push("price_history");
    }

    // Cross-source agreement is a separate family (bonus) or conflict.
    if analyst_ok {
        if let Some(a) = anchor.filter(|value| !value.analyst_correlated) {
            if let Some(rel) = relative_disagreement_bps(a.base_cents, detail.intrinsic_value_cents)
            {
                if rel <= MODEL_ANALYST_AGREE_BPS {
                    families_ok += 1;
                    notes.push("model_analyst_agree");
                } else if rel > MODEL_ANALYST_SOFT_BPS {
                    conflict += 1;
                    notes.push("model_analyst_diverge");
                } else {
                    // Soft disagreement: no bonus, no hard conflict, but blocks Strong.
                    conflict += 1;
                    notes.push("model_analyst_tension");
                }
            }
        }
    }

    if detail
        .operating_valuation
        .as_ref()
        .is_some_and(|value| value.decision.status == RouteStatus::Disputed)
    {
        conflict += 1;
        notes.push("operating_candidates_disputed");
    }

    // Soft model alone never upgrades to Strong.
    let status = match (families_ok, conflict, mq) {
        (f, 0, ModelQuality::Solid) if f >= 3 => "Strong",
        (f, 0, _) if f >= 3 => "Provisional",
        (f, 0, _) if f >= 2 => "Provisional",
        (f, c, _) if f >= 1 && c > 0 => "Mixed",
        (0, _, _) => "Unavailable",
        _ => "Sparse",
    };

    let mut metrics = vec![
        ("families".into(), families_ok.to_string()),
        ("conflict".into(), conflict.to_string()),
        ("notes".into(), notes.join(",")),
        (
            "gap_bps".into(),
            detail
                .gap_bps
                .map(|g| g.to_string())
                .unwrap_or_else(|| "null".into()),
        ),
        (
            "model_quality".into(),
            match mq {
                ModelQuality::Solid => "solid",
                ModelQuality::Soft => "soft",
                ModelQuality::Unusable => "unusable",
            }
            .into(),
        ),
    ];
    if let Some(a) = dcf {
        metrics.push(("valuation_model".into(), model_metric_label(a.model).into()));
        metrics.push((
            "business_class".into(),
            business_class_metric_label(a.business_class).into(),
        ));
        if let Some(w) = scenario_width_bps(a) {
            metrics.push(("scenario_width_bps".into(), w.to_string()));
        }
    }

    QuantLensSection {
        id: "evidence".into(),
        title: "Evidence strength".into(),
        status: status.into(),
        summary: format!(
            "{families_ok} independent families · {conflict} conflicts · {}",
            notes.join(", ")
        ),
        metrics,
    }
}

fn expected_value_range(detail: &SymbolDetail, dcf: Option<&DcfAnalysis>) -> QuantLensSection {
    let price = detail.market_price_cents;
    if let Some(envelope) = detail
        .operating_valuation
        .as_ref()
        .filter(|value| value.decision.status == RouteStatus::Disputed)
    {
        let fcff = envelope.decision.fcff_candidate.intrinsic_value_cents;
        let forward = envelope.decision.forward_candidate.intrinsic_value_cents;
        return QuantLensSection {
            id: "ev_range".into(),
            title: "Expected value range".into(),
            status: "Disputed".into(),
            summary: format!(
                "FCFF DCF {} vs Forward earnings value {} — no single EV",
                fcff.map_or_else(
                    || "unavailable".into(),
                    |v| format!("${:.2}", v as f64 / 100.0)
                ),
                forward.map_or_else(
                    || "unavailable".into(),
                    |v| format!("${:.2}", v as f64 / 100.0)
                ),
            ),
            metrics: vec![
                ("primary".into(), "disputed".into()),
                ("upside_bps".into(), "n/a".into()),
                (
                    "fcff_base_cents".into(),
                    fcff.map_or_else(|| "null".into(), |v| v.to_string()),
                ),
                (
                    "forward_base_cents".into(),
                    forward.map_or_else(|| "null".into(), |v| v.to_string()),
                ),
                (
                    "candidate_difference_bps".into(),
                    envelope
                        .decision
                        .candidate_difference_bps
                        .map_or_else(|| "null".into(), |v| v.to_string()),
                ),
            ],
        };
    }

    let anchor = runtime_model_anchor(detail, dcf);
    let mq = anchor.map_or(ModelQuality::Unusable, |value| value.quality);
    let model = anchor;
    let analyst_ok = complete_analyst(detail);

    let analyst_low = detail.low_fair_value_cents.unwrap_or(0);
    let analyst_base = detail.intrinsic_value_cents;
    let analyst_high = detail.high_fair_value_cents.unwrap_or(0);

    // ── Select primary anchor without silencing disagreement ─────────────────
    enum Primary {
        Model,
        Analyst,
        Disputed,
        None,
    }

    let disagreement = match (model, analyst_ok) {
        (Some(a), true) => relative_disagreement_bps(a.base_cents, analyst_base),
        _ => None,
    };

    let primary = match (model, analyst_ok, mq, disagreement) {
        (None, false, _, _) => Primary::None,
        (None, true, _, _) => Primary::Analyst,
        (Some(_), false, ModelQuality::Unusable, _) => Primary::None,
        (Some(_), false, _, _) => Primary::Model,
        (Some(_), true, ModelQuality::Solid, Some(d)) if d <= MODEL_ANALYST_AGREE_BPS => {
            Primary::Model
        }
        (Some(_), true, ModelQuality::Soft, Some(d)) if d <= MODEL_ANALYST_AGREE_BPS => {
            // Agree but soft model → prefer analyst as primary fair value for SNR.
            Primary::Analyst
        }
        (Some(_), true, _, Some(d)) if d > MODEL_ANALYST_AGREE_BPS => Primary::Disputed,
        (Some(_), true, ModelQuality::Solid, None) => Primary::Model,
        (Some(_), true, _, None) => Primary::Analyst,
        _ => Primary::Analyst,
    };

    let (status, summary, low, base, high, source) = match primary {
        Primary::None => (
            "Unavailable",
            "No usable model or analyst scenario set".to_string(),
            0,
            0,
            0,
            "none",
        ),
        Primary::Model => {
            let a = model.expect("model primary");
            let low = a.low_cents;
            let base = a.base_cents;
            let high = a.high_cents;
            let w = weighted_three(low, base, high);
            let up = upside_bps(price, w);
            let label = a.label;
            let soft = if mq == ModelQuality::Soft {
                " · provisional inputs"
            } else {
                ""
            };
            (
                if mq == ModelQuality::Soft {
                    "Provisional"
                } else {
                    "Available"
                },
                format!("{label}{soft}: weighted vs price {up} bps"),
                low,
                base,
                high,
                label,
            )
        }
        Primary::Analyst => {
            let w = weighted_three(analyst_low, analyst_base, analyst_high);
            let up = upside_bps(price, w);
            let note = if model.is_some() && matches!(mq, ModelQuality::Soft) {
                " (model soft — analyst primary)"
            } else {
                ""
            };
            (
                "Available",
                format!("Analyst range{note}: weighted vs price {up} bps"),
                analyst_low,
                analyst_base,
                analyst_high,
                "Analyst range",
            )
        }
        Primary::Disputed => {
            let a = model.expect("disputed model");
            let m_up = upside_bps(price, a.base_cents);
            let an_up = upside_bps(price, analyst_base);
            let rel = disagreement.unwrap_or(0);
            (
                "Disputed",
                format!(
                    "{} base ${:.2} ({m_up} bps) vs analyst ${:.2} ({an_up} bps) · diverge {rel} bps — no single EV",
                    a.label,
                    a.base_cents as f64 / 100.0,
                    analyst_base as f64 / 100.0,
                ),
                // Keep model as metric anchors but do not present as sole truth.
                a.low_cents,
                a.base_cents,
                a.high_cents,
                "disputed",
            )
        }
    };

    let mut metrics = vec![
        ("low_cents".into(), low.to_string()),
        ("base_cents".into(), base.to_string()),
        ("high_cents".into(), high.to_string()),
        (
            "upside_bps".into(),
            if matches!(primary, Primary::Disputed) {
                "n/a".into()
            } else {
                upside_bps(price, weighted_three(low, base, high)).to_string()
            },
        ),
        ("source".into(), source.into()),
        (
            "primary".into(),
            match primary {
                Primary::Model => "model",
                Primary::Analyst => "analyst",
                Primary::Disputed => "disputed",
                Primary::None => "none",
            }
            .into(),
        ),
    ];

    if let Some(anchor) = model {
        if anchor.analyst_correlated {
            let forward = &detail
                .operating_valuation
                .as_ref()
                .expect("runtime anchor")
                .decision
                .forward_candidate;
            metrics.push(("model_base_cents".into(), anchor.base_cents.to_string()));
            metrics.push((
                "model_upside_bps".into(),
                upside_bps(price, anchor.base_cents).to_string(),
            ));
            metrics.push((
                "discount_rate_bps".into(),
                forward.cost_of_equity_bps.to_string(),
            ));
            metrics.push(("discount_rate_kind".into(), "cost_of_equity".into()));
            metrics.push(("evidence_family".into(), "analyst_derived_model".into()));
            metrics.push((
                "forecast_analyst_count".into(),
                forward
                    .provenance
                    .forecast
                    .analyst_count
                    .map_or_else(|| "null".into(), |v| v.to_string()),
            ));
            metrics.push((
                "forecast_period_end_epoch_day".into(),
                forward
                    .provenance
                    .forecast
                    .forecast_period_end_epoch_day
                    .to_string(),
            ));
            metrics.push((
                "forward_source_fingerprint".into(),
                forward.provenance.forecast.source_fingerprint.clone(),
            ));
        }
    }

    if let Some(a) = model.and_then(|value| value.dcf) {
        metrics.push((
            "model_bear_cents".into(),
            a.bear_intrinsic_value_cents.to_string(),
        ));
        metrics.push((
            "model_base_cents".into(),
            a.base_intrinsic_value_cents.to_string(),
        ));
        metrics.push((
            "model_bull_cents".into(),
            a.bull_intrinsic_value_cents.to_string(),
        ));
        metrics.push((
            "model_upside_bps".into(),
            upside_bps(price, a.base_intrinsic_value_cents).to_string(),
        ));
        metrics.push(("discount_rate_bps".into(), a.wacc_bps.to_string()));
        metrics.push((
            "discount_rate_kind".into(),
            match a.discount_rate_kind {
                crate::dcf_model::DiscountRateKind::CostOfEquity => "cost_of_equity",
                crate::dcf_model::DiscountRateKind::Wacc => "wacc",
            }
            .into(),
        ));
        // Diagnostics (detail header stays overview-only; Quant Lens owns depth).
        let d = &a.diagnostics;
        metrics.push(("valuation_driver".into(), d.valuation_driver.clone()));
        metrics.push(("growth_driver".into(), d.growth_driver.clone()));
        if d.point_estimate_unreliable || a.wacc_inputs.is_provisional() {
            metrics.push(("rate_quality".into(), "provisional".into()));
        }
        let labels = a.wacc_inputs.summary_labels();
        if !labels.is_empty() {
            metrics.push(("wacc_provenance".into(), labels.join("; ")));
        }
        if let Some(fcf) = d.latest_fcf_dollars {
            metrics.push(("latest_fcf_dollars".into(), fcf.to_string()));
        }
        if let Some(fcf) = d.fcf_run_rate_dollars {
            metrics.push(("fcf_run_rate_dollars".into(), fcf.to_string()));
        }
        if let Some(revenue) = d.latest_revenue_dollars {
            metrics.push(("latest_revenue_dollars".into(), revenue.to_string()));
        }
        if let Some(fcff) = d.normalized_fcff_dollars {
            metrics.push(("normalized_fcff_dollars".into(), fcff.to_string()));
        }
        if let Some(margin) = d.normalized_ocf_margin_bps {
            metrics.push(("normalized_ocf_margin_bps".into(), margin.to_string()));
        }
        if let Some(intensity) = d.normalized_capex_intensity_bps {
            metrics.push((
                "normalized_capex_intensity_bps".into(),
                intensity.to_string(),
            ));
        }
        if !d.capex_spike_years.is_empty() {
            metrics.push((
                "capex_spike_years".into(),
                d.capex_spike_years
                    .iter()
                    .map(ToString::to_string)
                    .collect::<Vec<_>>()
                    .join(","),
            ));
        }
        if !d.fcf_annual_dollars.is_empty() {
            let series: Vec<String> = d
                .fcf_years
                .iter()
                .zip(d.fcf_annual_dollars.iter())
                .map(|(y, v)| format!("{y}:{:.1}B", *v as f64 / 1e9))
                .collect();
            // Cap width for UI — last 6 years of the series.
            let tail = if series.len() > 6 {
                &series[series.len() - 6..]
            } else {
                &series[..]
            };
            metrics.push(("fcf_series".into(), tail.join(" · ")));
        }
        if !d.capex_imputed_years.is_empty() {
            metrics.push((
                "capex_imputed_years".into(),
                d.capex_imputed_years
                    .iter()
                    .map(|y| y.to_string())
                    .collect::<Vec<_>>()
                    .join(","),
            ));
        }
        metrics.push(("net_debt_dollars".into(), a.net_debt_dollars.to_string()));
        if let Some(sh) = d.shares_outstanding {
            metrics.push(("shares_outstanding".into(), sh.to_string()));
        }
        metrics.push(("g_near_bps".into(), a.base_growth_bps.to_string()));
        metrics.push(("g_stable_bps".into(), a.stable_growth_bps.to_string()));
        if let Some(re) = d.cost_of_equity_bps {
            metrics.push(("cost_of_equity_bps".into(), re.to_string()));
        }
        if let Some(rd) = d.cost_of_debt_bps {
            metrics.push(("cost_of_debt_bps".into(), rd.to_string()));
        }
        if let Some(at) = d.after_tax_cost_of_debt_bps {
            metrics.push(("after_tax_cod_bps".into(), at.to_string()));
        }
        if let Some(ew) = d.equity_weight_bps {
            metrics.push(("equity_weight_bps".into(), ew.to_string()));
        }
        if let Some(dw) = d.debt_weight_bps {
            metrics.push(("debt_weight_bps".into(), dw.to_string()));
        }
        if let Some(wb) = d.wacc_bear_bps {
            metrics.push(("wacc_bear_bps".into(), wb.to_string()));
        }
        if let Some(wu) = d.wacc_bull_bps {
            metrics.push(("wacc_bull_bps".into(), wu.to_string()));
        }
        if !d.scenario_stress.is_empty() && d.scenario_stress != "none" {
            metrics.push(("scenario_stress".into(), d.scenario_stress.clone()));
        }
        if a.business_class == BusinessClass::FinancialServices {
            if let Some(bvps) = a.book_value_per_share_cents {
                metrics.push(("bvps_cents".into(), bvps.to_string()));
            }
            if let Some(roe) = a.roe0_bps {
                metrics.push(("roe0_bps".into(), roe.to_string()));
            }
        }
    }
    if analyst_ok {
        metrics.push(("analyst_base_cents".into(), analyst_base.to_string()));
        metrics.push((
            "analyst_upside_bps".into(),
            upside_bps(price, analyst_base).to_string(),
        ));
    }
    if let Some(d) = disagreement {
        metrics.push(("model_analyst_diverge_bps".into(), d.to_string()));
    }

    QuantLensSection {
        id: "ev_range".into(),
        title: "Expected value range".into(),
        status: status.into(),
        summary,
        metrics,
    }
}

fn correlation_risk(
    self_candles: Option<&[HistoricalCandle]>,
    peers: &[(String, Vec<HistoricalCandle>)],
) -> QuantLensSection {
    let Some(mine) = self_candles.filter(|c| c.len() >= 30) else {
        return QuantLensSection {
            id: "correlation".into(),
            title: "Correlation risk".into(),
            status: "Insufficient".into(),
            summary: "Need ≥30 daily closes for correlation".into(),
            metrics: vec![],
        };
    };
    let my_ret = returns(mine);
    let mut pairs = Vec::new();
    for (sym, candles) in peers {
        if candles.len() < 30 {
            continue;
        }
        let their = returns(candles);
        if let Some(rho) = pearson(&my_ret, &their) {
            pairs.push((sym.clone(), rho));
        }
    }
    pairs.sort_by(|a, b| {
        b.1.abs()
            .partial_cmp(&a.1.abs())
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    pairs.truncate(3);
    let high = pairs.iter().filter(|(_, r)| r.abs() >= 0.85).count()
        + if pairs.iter().filter(|(_, r)| r.abs() >= 0.70).count() >= 2 {
            1
        } else {
            0
        };
    let status = if pairs.len() < 3 {
        "Partial"
    } else if high > 0 {
        "High"
    } else {
        "Moderate"
    };
    QuantLensSection {
        id: "correlation".into(),
        title: "Correlation risk".into(),
        status: status.into(),
        summary: format!("Top peer |ρ| pairs: {}", pairs.len()),
        metrics: pairs
            .into_iter()
            .map(|(s, r)| (s, format!("{r:.2}")))
            .collect(),
    }
}

fn trend_reliability(
    candles: Option<&[HistoricalCandle]>,
    summary: Option<&ChartSummary>,
) -> QuantLensSection {
    let Some(c) = candles.filter(|x| x.len() >= 30) else {
        return QuantLensSection {
            id: "trend".into(),
            title: "Trend reliability".into(),
            status: "Insufficient".into(),
            summary: "Need ≥30 daily closes".into(),
            metrics: vec![],
        };
    };
    let closes: Vec<f64> = c.iter().map(|x| x.close_cents as f64).collect();
    let (r2, slope) = ols_r2_slope(&closes);
    let move_bps = if closes.first().copied().unwrap_or(0.0) > 0.0 {
        ((closes.last().unwrap() / closes.first().unwrap() - 1.0) * 10_000.0).abs()
    } else {
        0.0
    };
    let status = if move_bps < 200.0 {
        "Flat"
    } else if r2 >= 0.6 {
        "Reliable"
    } else if r2 >= 0.3 {
        "Moderate"
    } else {
        "Noisy"
    };
    let rsi = summary
        .and_then(|s| s.rsi)
        .map(|r| format!("{r:.1}"))
        .unwrap_or_else(|| "—".into());
    QuantLensSection {
        id: "trend".into(),
        title: "Trend reliability".into(),
        status: status.into(),
        summary: format!("R²={r2:.2} · move={move_bps:.0} bps · RSI {rsi}"),
        metrics: vec![
            ("r2".into(), format!("{r2:.3}")),
            ("slope".into(), format!("{slope:.4}")),
        ],
    }
}

fn horizon_context(candles: Option<&[HistoricalCandle]>) -> QuantLensSection {
    let Some(c) = candles.filter(|x| x.len() >= 60) else {
        return QuantLensSection {
            id: "horizon".into(),
            title: "Horizon context".into(),
            status: "Insufficient".into(),
            summary: "Need longer daily history".into(),
            metrics: vec![],
        };
    };
    let mut moves = Vec::new();
    for w in [5usize, 21, 63] {
        if c.len() > w {
            let a = c[c.len() - 1 - w].close_cents as f64;
            let b = c[c.len() - 1].close_cents as f64;
            if a > 0.0 {
                moves.push(((b / a - 1.0).abs() * 10_000.0) as i32);
            }
        }
    }
    moves.sort();
    let med = moves.get(moves.len() / 2).copied().unwrap_or(0);
    QuantLensSection {
        id: "horizon".into(),
        title: "Horizon context".into(),
        status: if moves.len() >= 3 {
            "Available"
        } else {
            "Partial"
        }
        .into(),
        summary: format!("Median absolute move across 1w/1m/3m windows: {med} bps"),
        metrics: moves
            .into_iter()
            .enumerate()
            .map(|(i, v)| (format!("window_{i}"), v.to_string()))
            .collect(),
    }
}

fn similar_setups(opp: Option<&CandidateRow>, peer_count: usize) -> QuantLensSection {
    let status = if peer_count >= 3 {
        "Available"
    } else {
        "Partial"
    };
    let _ = opp;
    QuantLensSection {
        id: "similar".into(),
        title: "Similar setups".into(),
        status: status.into(),
        summary: format!("Peer candle universe size: {peer_count}"),
        metrics: vec![("peers".into(), peer_count.to_string())],
    }
}

fn returns(c: &[HistoricalCandle]) -> Vec<f64> {
    c.windows(2)
        .filter_map(|w| {
            let a = w[0].close_cents as f64;
            let b = w[1].close_cents as f64;
            if a > 0.0 {
                Some(b / a - 1.0)
            } else {
                None
            }
        })
        .collect()
}

fn pearson(a: &[f64], b: &[f64]) -> Option<f64> {
    let n = a.len().min(b.len());
    if n < 30 {
        return None;
    }
    let a = &a[a.len() - n..];
    let b = &b[b.len() - n..];
    let mean_a = a.iter().sum::<f64>() / n as f64;
    let mean_b = b.iter().sum::<f64>() / n as f64;
    let mut num = 0.0;
    let mut da = 0.0;
    let mut db = 0.0;
    for i in 0..n {
        let x = a[i] - mean_a;
        let y = b[i] - mean_b;
        num += x * y;
        da += x * x;
        db += y * y;
    }
    let den = (da * db).sqrt();
    if den == 0.0 {
        None
    } else {
        Some(num / den)
    }
}

fn ols_r2_slope(y: &[f64]) -> (f64, f64) {
    let n = y.len() as f64;
    if n < 2.0 {
        return (0.0, 0.0);
    }
    let mean_x = (n - 1.0) / 2.0;
    let mean_y = y.iter().sum::<f64>() / n;
    let mut num = 0.0;
    let mut den = 0.0;
    let mut ss_tot = 0.0;
    for (i, yi) in y.iter().enumerate() {
        let x = i as f64;
        num += (x - mean_x) * (yi - mean_y);
        den += (x - mean_x) * (x - mean_x);
        ss_tot += (yi - mean_y) * (yi - mean_y);
    }
    let slope = if den == 0.0 { 0.0 } else { num / den };
    let intercept = mean_y - slope * mean_x;
    let mut ss_res = 0.0;
    for (i, yi) in y.iter().enumerate() {
        let pred = intercept + slope * i as f64;
        ss_res += (yi - pred) * (yi - pred);
    }
    let r2 = if ss_tot == 0.0 {
        0.0
    } else {
        (1.0 - ss_res / ss_tot).clamp(0.0, 1.0)
    };
    (r2, slope)
}

fn model_metric_label(model: ValuationModel) -> &'static str {
    match model {
        ValuationModel::ResidualIncomeEquity => "residual_income_equity",
        ValuationModel::FcffWacc => "fcff_wacc",
        ValuationModel::None => "none",
    }
}

fn business_class_metric_label(class: BusinessClass) -> &'static str {
    match class {
        BusinessClass::FinancialServices => "financial_services",
        BusinessClass::OperatingNonFinancial => "operating_non_financial",
        BusinessClass::NotEligible => "not_eligible",
        BusinessClass::Unclassified => "unclassified",
    }
}

fn worst_status<'a>(statuses: impl Iterator<Item = &'a str>) -> &'a str {
    // Lower index = more concerning for the overall chip.
    let order = [
        "Unavailable",
        "Insufficient",
        "Disputed",
        "Sparse",
        "Mixed",
        "High",
        "Noisy",
        "Partial",
        "Flat",
        "Moderate",
        "Provisional",
        "Reliable",
        "Available",
        "Strong",
    ];
    let mut best_idx = order.len();
    let mut best = "Available";
    for s in statuses {
        if let Some(i) = order.iter().position(|x| *x == s) {
            if i < best_idx {
                best_idx = i;
                best = s;
            }
        }
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dcf_model::{
        BusinessClass, DcfAnalysis, DiscountRateKind, ValuationModel, WaccFieldSource,
        WaccInputProvenance,
    };
    use crate::engine::SymbolDetail;
    use crate::operating_valuation_runtime::{route_runtime_valuation, RuntimeValuationInput};
    use crate::quote_summary::ForwardForecastEvidence;

    #[test]
    fn extreme_provider_values_do_not_overflow_disagreement_or_upside_math() {
        assert!(relative_disagreement_bps(i64::MAX, 1).is_some());
        assert_eq!(upside_bps(1, i64::MAX), i32::MAX);
    }

    fn detail_tsla_like() -> SymbolDetail {
        SymbolDetail {
            symbol: "TSLA".into(),
            company_name: Some("Tesla".into()),
            market_price_cents: 31_200,    // ~$312
            intrinsic_value_cents: 38_150, // analyst ~$381.5 → gap ~22%
            gap_bps: Some(2_227),
            qualification: crate::engine::QualificationStatus::Qualified,
            confidence: crate::engine::ConfidenceBand::Provisional,
            signal_status: crate::engine::ExternalSignalStatus::Supportive,
            signal_age_seconds: None,
            low_fair_value_cents: Some(25_000),
            high_fair_value_cents: Some(50_000),
            analyst_opinion_count: Some(40),
            recommendation_mean_hundredths: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            fundamentals: crate::engine::FundamentalSnapshot {
                symbol: "TSLA".into(),
                sector_name: Some("Consumer Cyclical".into()),
                ..Default::default()
            },
            chart_summary: None,
            weekly_summary: None,
            hourly_summary: None,
            monthly_summary: None,
            technical_breakdown: None,
            dcf_value_cents: Some(3_499),
            dcf_analysis: None,
            valuation_status: None,
            selected_valuation_value_cents: None,
            selected_valuation_model: None,
            operating_valuation: None,
            valuation_unavailable_reason: None,
            insider_net_shares_90d: None,
            insider_buy_count: None,
            insider_sell_count: None,
            next_earnings_epoch: None,
            chart_patterns: vec![],
            fib: None,
        }
    }

    fn junk_fcff_tsla() -> DcfAnalysis {
        DcfAnalysis {
            bear_intrinsic_value_cents: 3_027,
            base_intrinsic_value_cents: 3_499,
            bull_intrinsic_value_cents: 4_067,
            wacc_bps: 900,
            base_growth_bps: 500,
            net_debt_dollars: 0,
            wacc_inputs: WaccInputProvenance {
                market_cap: WaccFieldSource::Reported,
                beta: WaccFieldSource::IndustryShrink,
                total_debt: WaccFieldSource::Default,
                total_cash: WaccFieldSource::Default,
                cost_of_debt: WaccFieldSource::Default,
                tax_rate: WaccFieldSource::Default,
                wacc_clamped: true, // provisional
            },
            source: "sec_edgar".into(),
            engine_version: "valuation-model-family/1".into(),
            model_policy_version: "business-class-policy/1".into(),
            business_class: BusinessClass::OperatingNonFinancial,
            model: ValuationModel::FcffWacc,
            discount_rate_kind: DiscountRateKind::Wacc,
            stable_growth_bps: 300,
            book_value_per_share_cents: None,
            roe0_bps: None,
            reason_codes: vec![],
            diagnostics: Default::default(),
        }
    }

    fn solid_aligned_model() -> DcfAnalysis {
        let mut a = junk_fcff_tsla();
        a.bear_intrinsic_value_cents = 34_000;
        a.base_intrinsic_value_cents = 37_000;
        a.bull_intrinsic_value_cents = 40_000;
        a.wacc_inputs = WaccInputProvenance {
            market_cap: WaccFieldSource::Reported,
            beta: WaccFieldSource::IndustryShrink,
            total_debt: WaccFieldSource::Reported,
            total_cash: WaccFieldSource::Reported,
            cost_of_debt: WaccFieldSource::InterestOverDebt,
            tax_rate: WaccFieldSource::Reported,
            wacc_clamped: false,
        };
        a
    }

    fn forward_evidence() -> ForwardForecastEvidence {
        ForwardForecastEvidence {
            eps_low_cents: Some(775),
            eps_mean_cents: Some(1_004),
            eps_high_cents: Some(1_512),
            analyst_count: Some(55),
            revenue_growth_bps: Some(1_327),
            revenue_analyst_count: Some(60),
            earnings_growth_bps: Some(1_358),
            currency: "USD".into(),
            revenue_currency: "USD".into(),
            reporting_currency: "USD".into(),
            observed_epoch_day: 20_665,
            forecast_period_end_epoch_day: 21_183,
            source_fingerprint: "yahoo-earnings-trend/1|symbol=TEST|hash=quant".into(),
        }
    }

    fn with_runtime_route(mut detail: SymbolDetail, fcff: Option<&DcfAnalysis>) -> SymbolDetail {
        detail.fundamentals.sector_key = Some("consumer-cyclical".into());
        detail.fundamentals.industry_key = Some("internet-retail".into());
        detail.fundamentals.beta_millis = Some(1_350);
        detail.fundamentals.debt_to_equity_hundredths = Some(4_046);
        detail.fundamentals.return_on_equity_bps = Some(3_055);
        let envelope = route_runtime_valuation(RuntimeValuationInput {
            business_class: BusinessClass::OperatingNonFinancial,
            fundamentals: &detail.fundamentals,
            fcff_analysis: fcff,
            fcff_failure: fcff.is_none().then_some("no trailing FCFF"),
            forward_evidence: Ok(forward_evidence()),
            market_params: &crate::dcf_model::MarketParams::default_usd(),
            as_of_epoch_day: 20_665,
        });
        detail.valuation_status = Some(envelope.decision.status);
        detail.selected_valuation_model = envelope.decision.selected_model;
        detail.selected_valuation_value_cents = envelope.decision.selected_value_cents;
        detail.operating_valuation = Some(envelope);
        detail
    }

    fn with_runtime_fcff_selected(mut detail: SymbolDetail, fcff: &DcfAnalysis) -> SymbolDetail {
        detail.fundamentals.sector_key = Some("consumer-cyclical".into());
        detail.fundamentals.industry_key = Some("internet-retail".into());
        detail.fundamentals.beta_millis = Some(1_350);
        detail.fundamentals.debt_to_equity_hundredths = Some(4_046);
        detail.fundamentals.return_on_equity_bps = Some(3_055);
        let envelope = route_runtime_valuation(RuntimeValuationInput {
            business_class: BusinessClass::OperatingNonFinancial,
            fundamentals: &detail.fundamentals,
            fcff_analysis: Some(fcff),
            fcff_failure: None,
            forward_evidence: Err(
                crate::operating_valuation_runtime::ForwardSourceFailure::Transport,
            ),
            market_params: &crate::dcf_model::MarketParams::default_usd(),
            as_of_epoch_day: 20_665,
        });
        detail.valuation_status = Some(envelope.decision.status);
        detail.selected_valuation_model = envelope.decision.selected_model;
        detail.selected_valuation_value_cents = envelope.decision.selected_value_cents;
        detail.operating_valuation = Some(envelope);
        detail
    }

    #[test]
    fn tsla_junk_fcff_is_disputed_not_strong_ev() {
        let dcf = junk_fcff_tsla();
        let detail = with_runtime_fcff_selected(detail_tsla_like(), &dcf);
        let ev = expected_value_range(&detail, Some(&dcf));
        assert_eq!(ev.status, "Disputed");
        assert!(
            !ev.summary.contains("-8875"),
            "must not headline absurd single upside: {}",
            ev.summary
        );
        assert!(ev.summary.contains("diverge") || ev.summary.contains("vs analyst"));
        assert_eq!(
            ev.metrics
                .iter()
                .find(|(k, _)| k == "upside_bps")
                .map(|(_, v)| v.as_str()),
            Some("n/a")
        );

        let ev_only = evidence_strength(&detail, None, Some(&dcf));
        assert_ne!(ev_only.status, "Strong");
        assert!(
            ev_only.status == "Mixed"
                || ev_only.status == "Sparse"
                || ev_only.status == "Provisional",
            "status={}",
            ev_only.status
        );
        assert!(
            ev_only
                .metrics
                .iter()
                .any(|(k, v)| k == "conflict" && v != "0"),
            "expected conflict when model and analyst diverge hard"
        );
    }

    #[test]
    fn aligned_solid_model_can_be_primary() {
        let dcf = solid_aligned_model();
        let detail = with_runtime_fcff_selected(detail_tsla_like(), &dcf);
        let ev = expected_value_range(&detail, Some(&dcf));
        assert_eq!(ev.status, "Available");
        assert!(ev.summary.contains("FCFF DCF"));
        assert_eq!(
            ev.metrics
                .iter()
                .find(|(k, _)| k == "primary")
                .map(|(_, v)| v.as_str()),
            Some("model")
        );
    }

    #[test]
    fn analyst_only_when_no_model() {
        let detail = detail_tsla_like();
        let ev = expected_value_range(&detail, None);
        assert_eq!(ev.status, "Available");
        assert!(ev.summary.contains("Analyst range"));
        assert_eq!(
            ev.metrics
                .iter()
                .find(|(k, _)| k == "base_cents")
                .map(|(_, v)| v.as_str()),
            Some("38150")
        );
    }

    #[test]
    fn gap_alone_does_not_double_count_as_two_families() {
        let detail = detail_tsla_like();
        let ev = evidence_strength(&detail, None, None);
        // Only analyst_range family without history/model.
        assert_eq!(
            ev.metrics
                .iter()
                .find(|(k, _)| k == "families")
                .map(|(_, v)| v.as_str()),
            Some("1")
        );
        assert_ne!(ev.status, "Strong");
    }

    #[test]
    fn residual_income_label_when_aligned() {
        let mut detail = detail_tsla_like();
        detail.symbol = "ACGL".into();
        detail.market_price_cents = 10_336;
        detail.intrinsic_value_cents = 8_500;
        detail.low_fair_value_cents = Some(7_500);
        detail.high_fair_value_cents = Some(9_500);
        let dcf = DcfAnalysis {
            bear_intrinsic_value_cents: 7_000,
            base_intrinsic_value_cents: 8_200,
            bull_intrinsic_value_cents: 9_500,
            wacc_bps: 750,
            base_growth_bps: 1_400,
            net_debt_dollars: 0,
            wacc_inputs: WaccInputProvenance {
                market_cap: WaccFieldSource::Reported,
                beta: WaccFieldSource::IndustryShrink,
                total_debt: WaccFieldSource::Reported,
                total_cash: WaccFieldSource::Reported,
                cost_of_debt: WaccFieldSource::Reported,
                tax_rate: WaccFieldSource::Reported,
                wacc_clamped: false,
            },
            source: "fundamentals".into(),
            engine_version: "valuation-model-family/1".into(),
            model_policy_version: "business-class-policy/1".into(),
            business_class: BusinessClass::FinancialServices,
            model: ValuationModel::ResidualIncomeEquity,
            discount_rate_kind: DiscountRateKind::CostOfEquity,
            stable_growth_bps: 300,
            book_value_per_share_cents: Some(6_511),
            roe0_bps: Some(2_000),
            reason_codes: vec![],
            diagnostics: Default::default(),
        };
        let ev = expected_value_range(&detail, Some(&dcf));
        assert_eq!(ev.status, "Available");
        assert!(ev.summary.contains("Residual income"));
    }

    #[test]
    fn forward_and_yahoo_target_are_one_correlated_family_without_agreement_bonus() {
        let mut detail = with_runtime_route(detail_tsla_like(), None);
        let selected = detail
            .selected_valuation_value_cents
            .expect("forward selected");
        detail.intrinsic_value_cents = selected;
        detail.low_fair_value_cents = Some(selected * 8 / 10);
        detail.high_fair_value_cents = Some(selected * 12 / 10);

        let evidence = evidence_strength(&detail, None, None);
        assert_eq!(evidence.status, "Sparse");
        assert_eq!(
            evidence
                .metrics
                .iter()
                .find(|(k, _)| k == "families")
                .map(|(_, v)| v.as_str()),
            Some("1")
        );
        let notes = evidence
            .metrics
            .iter()
            .find(|(k, _)| k == "notes")
            .map(|(_, v)| v.as_str())
            .unwrap_or("");
        assert!(notes.contains("forward_model_correlated_with_analyst"));
        assert!(!notes.contains("model_analyst_agree"));
        assert_ne!(evidence.status, "Strong");
    }

    #[test]
    fn operating_candidate_dispute_has_no_single_expected_value() {
        let fcff = junk_fcff_tsla();
        let detail = with_runtime_route(detail_tsla_like(), Some(&fcff));
        assert_eq!(detail.valuation_status, Some(RouteStatus::Disputed));

        let ev = expected_value_range(&detail, Some(&fcff));
        assert_eq!(ev.status, "Disputed");
        assert!(ev.summary.contains("no single EV"));
        assert_eq!(
            ev.metrics
                .iter()
                .find(|(k, _)| k == "primary")
                .map(|(_, v)| v.as_str()),
            Some("disputed")
        );
        assert_eq!(
            ev.metrics
                .iter()
                .find(|(k, _)| k == "upside_bps")
                .map(|(_, v)| v.as_str()),
            Some("n/a")
        );
    }
}
