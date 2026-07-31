//! Auditable DCF-vs-analyst divergence analysis.
//!
//! This module deliberately contains no ticker-specific calibration.  It ranks
//! comparable anchors and classifies the evidence exposed by the valuation
//! engine so a large gap becomes a model/input investigation, not a hidden
//! price cap.

use serde::Serialize;

use crate::dcf_model::{BusinessClass, DcfAnalysis, ValuationModel};

pub const AUDIT_MAX_SYMBOLS: usize = 20;
const WIDE_SCENARIO_BPS: i32 = 12_000;
const RECENT_IMPUTED_POINT_WINDOW: usize = 5;

#[derive(Clone, Debug)]
pub struct AuditCandidate {
    pub symbol: String,
    pub analyst_value_cents: Option<i64>,
    pub analyst_low_cents: Option<i64>,
    pub analyst_high_cents: Option<i64>,
    pub analyst_opinion_count: Option<u32>,
    pub dcf: Option<DcfAnalysis>,
    pub unavailable_reason: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct ValuationDivergenceAudit {
    pub profile_name: String,
    pub model_policy_version: String,
    pub computed_at_epoch_seconds: i64,
    pub candidate_count: usize,
    pub comparable_count: usize,
    pub unavailable_count: usize,
    pub rows: Vec<ValuationDivergenceRow>,
    pub unavailable: Vec<ValuationAuditUnavailable>,
}

#[derive(Clone, Debug, Serialize)]
pub struct ValuationAuditUnavailable {
    pub symbol: String,
    pub analyst_anchor_available: bool,
    pub dcf_available: bool,
    pub reason: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct ValuationDivergenceRow {
    pub rank: usize,
    pub symbol: String,
    pub analyst_value_cents: i64,
    pub dcf_value_cents: i64,
    /// DCF minus analyst, in cents/share.
    pub signed_difference_cents: i64,
    /// Symmetric relative disagreement: |DCF−analyst| / midpoint.
    pub relative_disagreement_bps: i32,
    pub direction: DivergenceDirection,
    pub model: ValuationModel,
    pub business_class: BusinessClass,
    pub analyst_opinion_count: Option<u32>,
    pub analyst_range_complete: bool,
    pub model_quality: ModelQuality,
    pub primary_cause: String,
    pub causes: Vec<String>,
    pub evidence: Vec<String>,
}

#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DivergenceDirection {
    DcfAboveAnalyst,
    DcfBelowAnalyst,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ModelQuality {
    Solid,
    Soft,
    Unusable,
}

pub fn build_audit(
    profile_name: impl Into<String>,
    candidates: Vec<AuditCandidate>,
    computed_at_epoch_seconds: i64,
) -> ValuationDivergenceAudit {
    let model_policy_version = candidates
        .iter()
        .find_map(|candidate| {
            candidate
                .dcf
                .as_ref()
                .map(|analysis| analysis.model_policy_version.clone())
        })
        .unwrap_or_else(|| "none".into());

    let mut comparable: Vec<ValuationDivergenceRow> =
        candidates.iter().filter_map(comparable_row).collect();
    comparable.sort_by(|left, right| {
        right
            .relative_disagreement_bps
            .cmp(&left.relative_disagreement_bps)
            .then_with(|| left.symbol.cmp(&right.symbol))
    });
    comparable.truncate(AUDIT_MAX_SYMBOLS);
    for (index, row) in comparable.iter_mut().enumerate() {
        row.rank = index + 1;
    }

    let comparable_count = candidates
        .iter()
        .filter(|candidate| is_comparable(candidate))
        .count();
    let unavailable = candidates
        .iter()
        .filter(|candidate| !is_comparable(candidate))
        .map(|candidate| ValuationAuditUnavailable {
            symbol: candidate.symbol.clone(),
            analyst_anchor_available: candidate.analyst_value_cents.is_some_and(|value| value > 0),
            dcf_available: candidate
                .dcf
                .as_ref()
                .is_some_and(|analysis| analysis.base_intrinsic_value_cents > 0),
            reason: unavailable_reason(candidate),
        })
        .collect();
    ValuationDivergenceAudit {
        profile_name: profile_name.into(),
        model_policy_version,
        computed_at_epoch_seconds,
        candidate_count: candidates.len(),
        comparable_count,
        unavailable_count: candidates.len().saturating_sub(comparable_count),
        rows: comparable,
        unavailable,
    }
}

fn is_comparable(candidate: &AuditCandidate) -> bool {
    candidate.analyst_value_cents.is_some_and(|value| value > 0)
        && candidate
            .dcf
            .as_ref()
            .is_some_and(|analysis| analysis.base_intrinsic_value_cents > 0)
}

fn comparable_row(candidate: &AuditCandidate) -> Option<ValuationDivergenceRow> {
    let analyst = candidate.analyst_value_cents.filter(|value| *value > 0)?;
    let dcf = candidate
        .dcf
        .as_ref()
        .filter(|analysis| analysis.base_intrinsic_value_cents > 0)?;
    let model_value = dcf.base_intrinsic_value_cents;
    let midpoint = analyst.checked_add(model_value)? as f64 / 2.0;
    if midpoint <= 0.0 {
        return None;
    }
    let signed_difference_cents = model_value.saturating_sub(analyst);
    let relative_disagreement_bps =
        ((signed_difference_cents.unsigned_abs() as f64 / midpoint) * 10_000.0).round() as i32;
    let direction = if signed_difference_cents >= 0 {
        DivergenceDirection::DcfAboveAnalyst
    } else {
        DivergenceDirection::DcfBelowAnalyst
    };
    let analyst_range_complete = candidate.analyst_low_cents.is_some_and(|value| value > 0)
        && candidate.analyst_high_cents.is_some_and(|value| value > 0);
    let model_quality = model_quality(dcf);
    let (primary_cause, causes, evidence) = classify_causes(candidate, dcf, model_quality);

    Some(ValuationDivergenceRow {
        rank: 0,
        symbol: candidate.symbol.clone(),
        analyst_value_cents: analyst,
        dcf_value_cents: model_value,
        signed_difference_cents,
        relative_disagreement_bps,
        direction,
        model: dcf.model,
        business_class: dcf.business_class,
        analyst_opinion_count: candidate.analyst_opinion_count,
        analyst_range_complete,
        model_quality,
        primary_cause,
        causes,
        evidence,
    })
}

fn unavailable_reason(candidate: &AuditCandidate) -> String {
    if let Some(reason) = candidate.unavailable_reason.as_deref() {
        return reason.to_string();
    }
    if candidate
        .dcf
        .as_ref()
        .is_some_and(|analysis| analysis.base_intrinsic_value_cents == 0)
        && candidate.dcf.as_ref().is_some_and(|analysis| {
            analysis
                .reason_codes
                .iter()
                .any(|reason| reason == "equity_value_floor=limited_liability")
        })
    {
        return "base equity value is zero after net debt consumes modeled enterprise value; no positive common-equity estimate".into();
    }
    "analyst_or_dcf_anchor_missing".into()
}

fn model_quality(analysis: &DcfAnalysis) -> ModelQuality {
    if analysis.model == ValuationModel::None
        || analysis.bear_intrinsic_value_cents < 0
        || analysis.base_intrinsic_value_cents <= 0
        || analysis.bull_intrinsic_value_cents <= 0
        || analysis.bear_intrinsic_value_cents > analysis.base_intrinsic_value_cents
        || analysis.base_intrinsic_value_cents > analysis.bull_intrinsic_value_cents
    {
        return ModelQuality::Unusable;
    }
    let span = analysis
        .bull_intrinsic_value_cents
        .saturating_sub(analysis.bear_intrinsic_value_cents);
    let width_bps =
        ((span as f64 / analysis.base_intrinsic_value_cents as f64) * 10_000.0).round() as i32;
    if analysis.wacc_inputs.is_provisional() || width_bps > WIDE_SCENARIO_BPS {
        ModelQuality::Soft
    } else {
        ModelQuality::Solid
    }
}

fn classify_causes(
    candidate: &AuditCandidate,
    analysis: &DcfAnalysis,
    quality: ModelQuality,
) -> (String, Vec<String>, Vec<String>) {
    let mut causes = Vec::new();
    let mut evidence = Vec::new();
    let diagnostics = &analysis.diagnostics;

    if analysis
        .reason_codes
        .iter()
        .any(|reason| reason == "shares=sec_dei_fallback")
    {
        evidence.push("shares_source=sec_dei_fallback".into());
    }

    if candidate.analyst_opinion_count.unwrap_or(0) < 3 {
        causes.push("analyst_coverage_thin".into());
        evidence.push(format!(
            "analyst_opinion_count={}",
            candidate
                .analyst_opinion_count
                .map_or_else(|| "missing".into(), |count| count.to_string())
        ));
    }
    if candidate.analyst_low_cents.is_none() || candidate.analyst_high_cents.is_none() {
        causes.push("analyst_range_incomplete".into());
        evidence.push("analyst_low_high=not_both_available".into());
    }

    match analysis.model {
        ValuationModel::ResidualIncomeEquity => {
            causes.push("financials_use_residual_income".into());
            evidence.push(format!(
                "model=residual_income_equity book_value_per_share_cents={:?} roe0_bps={:?}",
                analysis.book_value_per_share_cents, analysis.roe0_bps
            ));
        }
        ValuationModel::FcffWacc => {
            if analysis.base_intrinsic_value_cents == 0 {
                causes.push("equity_value_floor".into());
                evidence.push(
                    "scenario_equity_value_floor=limited_liability_when_net_debt_exceeds_enterprise_value"
                        .into(),
                );
            } else if analysis.bear_intrinsic_value_cents == 0
                || analysis.bull_intrinsic_value_cents == 0
            {
                evidence.push(
                    "downside_scenario_equity_value_floor=limited_liability; base_equity_remains_positive"
                        .into(),
                );
            }
            if diagnostics.valuation_driver == "driver_based_fcff" {
                causes.push("operating_drivers_rebased".into());
                evidence.push(format!(
                    "driver_based_fcff normalized_fcff_dollars={:?} latest_revenue_dollars={:?} normalized_ocf_margin_bps={:?} after_tax_interest_margin_bps={:?} normalized_capex_intensity_bps={:?}",
                    diagnostics.normalized_fcff_dollars,
                    diagnostics.latest_revenue_dollars,
                    diagnostics.normalized_ocf_margin_bps,
                    diagnostics.normalized_after_tax_interest_margin_bps,
                    diagnostics.normalized_capex_intensity_bps
                ));
                if let Some(reason) = analysis
                    .reason_codes
                    .iter()
                    .find(|reason| reason.starts_with("growth_fade="))
                {
                    evidence.push(reason.clone());
                }
                match diagnostics.driver_regime.as_str() {
                    "cyclical_or_transition" => {
                        causes.push("driver_regime_cyclical".into());
                        evidence.push(format!(
                            "driver_regime=cyclical_or_transition growth_dispersion_bps={:?}",
                            diagnostics.growth_dispersion_bps
                        ));
                    }
                    "secular_expansion" => {
                        causes.push("driver_regime_secular".into());
                        evidence.push(format!(
                            "driver_regime=secular_expansion growth_dispersion_bps={:?}",
                            diagnostics.growth_dispersion_bps
                        ));
                    }
                    _ => evidence.push(format!(
                        "driver_regime={} growth_dispersion_bps={:?}",
                        if diagnostics.driver_regime.is_empty() {
                            "legacy_or_unreported"
                        } else {
                            diagnostics.driver_regime.as_str()
                        },
                        diagnostics.growth_dispersion_bps
                    )),
                }
            } else if diagnostics.valuation_driver == "fcf_history_fade" {
                causes.push("legacy_fcf_history_fallback".into());
                evidence.push("valuation_driver=fcf_history_fade".into());
            }
            if !diagnostics.capex_spike_years.is_empty() {
                causes.push("capex_cycle_normalized".into());
                evidence.push(format!(
                    "capex_spike_years={:?} normalized_capex_intensity_bps={:?}",
                    diagnostics.capex_spike_years, diagnostics.normalized_capex_intensity_bps
                ));
            }
            if diagnostics
                .growth_driver
                .starts_with("revenue_growth_median")
            {
                causes.push("revenue_growth_driver_median".into());
                evidence.push(format!(
                    "growth_driver={} base_growth_bps={}",
                    diagnostics.growth_driver, analysis.base_growth_bps
                ));
            }
        }
        ValuationModel::None => {
            causes.push("model_unavailable".into());
        }
    }

    if diagnostics.point_estimate_unreliable || analysis.wacc_inputs.is_provisional() {
        causes.push("discount_inputs_provisional".into());
        evidence.push(format!(
            "point_estimate_unreliable={} wacc_inputs={:?}",
            diagnostics.point_estimate_unreliable, analysis.wacc_inputs
        ));
    }
    if !diagnostics.capex_imputed_years.is_empty() {
        let recent_years: std::collections::HashSet<i32> = diagnostics
            .fcf_years
            .iter()
            .rev()
            .take(RECENT_IMPUTED_POINT_WINDOW)
            .copied()
            .collect();
        let recent = diagnostics
            .capex_imputed_years
            .iter()
            .any(|year| recent_years.contains(year));
        if recent {
            causes.push("capex_data_imputed".into());
            evidence.push(format!(
                "capex_imputed_years={:?} recent=true",
                diagnostics.capex_imputed_years
            ));
        } else {
            evidence.push(format!(
                "capex_imputed_years={:?} recent=false historical_only=true",
                diagnostics.capex_imputed_years
            ));
        }
    }
    if quality == ModelQuality::Soft {
        causes.push("scenario_or_input_quality_soft".into());
        evidence.push(format!(
            "scenario_width_bps={}",
            scenario_width_bps(analysis).unwrap_or_default()
        ));
    }

    if causes.is_empty() {
        causes.push("unexplained_after_diagnostics".into());
        evidence.push(format!(
            "source={} policy={} driver_fingerprint={:?}",
            analysis.source, analysis.model_policy_version, diagnostics.driver_input_fingerprint
        ));
    }
    let primary = primary_cause(&causes).to_string();
    (primary, causes, evidence)
}

fn primary_cause(causes: &[String]) -> &str {
    // Order is evidence precedence, not a ticker-specific exception list.
    const PRIORITY: &[&str] = &[
        "model_unavailable",
        "equity_value_floor",
        "legacy_fcf_history_fallback",
        "capex_data_imputed",
        "discount_inputs_provisional",
        "capex_cycle_normalized",
        "driver_regime_cyclical",
        "driver_regime_secular",
        "operating_drivers_rebased",
        "financials_use_residual_income",
        "analyst_coverage_thin",
        "analyst_range_incomplete",
        "scenario_or_input_quality_soft",
        "revenue_growth_driver_median",
        "unexplained_after_diagnostics",
    ];
    PRIORITY
        .iter()
        .find(|cause| causes.iter().any(|candidate| candidate == *cause))
        .copied()
        .unwrap_or("unexplained_after_diagnostics")
}

fn scenario_width_bps(analysis: &DcfAnalysis) -> Option<i32> {
    if analysis.base_intrinsic_value_cents <= 0 {
        return None;
    }
    let span = analysis
        .bull_intrinsic_value_cents
        .checked_sub(analysis.bear_intrinsic_value_cents)?;
    Some(((span as f64 / analysis.base_intrinsic_value_cents as f64) * 10_000.0).round() as i32)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dcf_model::{DcfDiagnostics, WaccFieldSource, WaccInputProvenance};

    fn dcf(base: i64, driver: &str, capex_spikes: Vec<i32>) -> DcfAnalysis {
        DcfAnalysis {
            bear_intrinsic_value_cents: base.saturating_sub(10),
            base_intrinsic_value_cents: base,
            bull_intrinsic_value_cents: base.saturating_add(10),
            wacc_bps: 800,
            base_growth_bps: 500,
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
            source: "test".into(),
            engine_version: "test".into(),
            model_policy_version: "test-policy".into(),
            business_class: BusinessClass::OperatingNonFinancial,
            model: ValuationModel::FcffWacc,
            discount_rate_kind: crate::dcf_model::DiscountRateKind::Wacc,
            stable_growth_bps: 300,
            book_value_per_share_cents: None,
            roe0_bps: None,
            reason_codes: vec![],
            diagnostics: DcfDiagnostics {
                valuation_driver: driver.into(),
                capex_spike_years: capex_spikes,
                growth_driver: "revenue_growth_median".into(),
                normalized_fcff_dollars: Some(10),
                latest_revenue_dollars: Some(100),
                normalized_ocf_margin_bps: Some(2_000),
                normalized_capex_intensity_bps: Some(500),
                ..Default::default()
            },
        }
    }

    fn candidate(symbol: &str, analyst: i64, model: DcfAnalysis) -> AuditCandidate {
        AuditCandidate {
            symbol: symbol.into(),
            analyst_value_cents: Some(analyst),
            analyst_low_cents: Some(analyst - 10),
            analyst_high_cents: Some(analyst + 10),
            analyst_opinion_count: Some(12),
            dcf: Some(model),
            unavailable_reason: None,
        }
    }

    #[test]
    fn ranks_symmetric_relative_disagreement_and_caps_output() {
        let mut candidates = (0..25)
            .map(|i| {
                candidate(
                    &format!("S{i:02}"),
                    100,
                    dcf(100 + i, "driver_based_fcff", vec![]),
                )
            })
            .collect::<Vec<_>>();
        let report = build_audit("qa", std::mem::take(&mut candidates), 123);
        assert_eq!(report.candidate_count, 25);
        assert_eq!(report.comparable_count, 25);
        assert_eq!(report.rows.len(), AUDIT_MAX_SYMBOLS);
        assert_eq!(report.rows[0].symbol, "S24");
        assert_eq!(report.rows[0].rank, 1);
        assert_eq!(report.rows[19].rank, 20);
    }

    #[test]
    fn classifies_generic_driver_and_capex_evidence_without_symbol_rules() {
        let report = build_audit(
            "qa",
            vec![candidate(
                "AMZN",
                100,
                dcf(250, "driver_based_fcff", vec![2025]),
            )],
            123,
        );
        let row = &report.rows[0];
        assert_eq!(row.primary_cause, "capex_cycle_normalized");
        assert!(row.causes.contains(&"operating_drivers_rebased".into()));
        assert!(row.causes.contains(&"capex_cycle_normalized".into()));
        assert!(row.causes.contains(&"revenue_growth_driver_median".into()));
        assert!(row.evidence.iter().any(|item| item.contains("2025")));
    }

    #[test]
    fn excludes_missing_anchors_from_comparable_top_twenty() {
        let mut missing = candidate("MISS", 100, dcf(200, "driver_based_fcff", vec![]));
        missing.analyst_value_cents = None;
        let report = build_audit(
            "qa",
            vec![
                missing,
                candidate("OK", 100, dcf(200, "driver_based_fcff", vec![])),
            ],
            123,
        );
        assert_eq!(report.comparable_count, 1);
        assert_eq!(report.unavailable_count, 1);
        assert_eq!(report.rows[0].symbol, "OK");
    }

    #[test]
    fn keeps_zero_equity_value_floor_visible_as_explicit_unavailable() {
        let mut model = dcf(0, "driver_based_fcff", vec![]);
        model
            .reason_codes
            .push("equity_value_floor=limited_liability".into());
        let report = build_audit("qa", vec![candidate("DEBT", 1_000, model)], 123);
        assert_eq!(report.comparable_count, 0);
        assert_eq!(report.unavailable_count, 1);
        assert_eq!(report.unavailable[0].symbol, "DEBT");
        assert!(!report.unavailable[0].dcf_available);
        assert!(report.unavailable[0].reason.contains("zero"));
    }
}
