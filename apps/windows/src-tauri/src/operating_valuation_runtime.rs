use serde::{Deserialize, Serialize};

use crate::dcf_model::{
    resolve_cost_of_equity, BusinessClass, CostOfEquityResolutionError, DcfAnalysis, MarketParams,
    ResolvedCostOfEquity, ValuationModel, WaccFieldSource,
};
use crate::engine::FundamentalSnapshot;
use crate::operating_valuation::{
    route_operating_models, value_forward_earnings, CandidateStatus, FcffCandidate,
    ForwardEarningsInput, ForwardForecast, ModelQuality, OperatingRouteDecision,
    OperatingRouteInput, ProjectionPolicy, StructuralDistortion,
};
use crate::quote_summary::{ForwardForecastEvidence, ForwardForecastProviderError};

pub const RUNTIME_POLICY_VERSION: &str = "operating-valuation-runtime/3";
pub const FORWARD_RETRY_AFTER_SECONDS: i64 = 90;

pub fn fundamentals_fingerprint(fundamentals: &FundamentalSnapshot) -> String {
    let payload = serde_json::to_vec(fundamentals).unwrap_or_default();
    let mut hash = 0xcbf29ce484222325_u64;
    for byte in payload {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    format!(
        "fund-runtime/2|symbol={}|hash={hash:016x}",
        fundamentals.symbol
    )
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForwardSourceFailure {
    NotAttempted,
    RateLimited,
    Transport,
    Provider(ForwardForecastProviderError),
    Normalization(ForwardNormalizationFailure),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForwardSourceState {
    NotAttempted,
    Selected,
    Rejected,
    Unavailable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OperatingRuntimeDiagnostics {
    pub provider: String,
    pub forward_source_state: ForwardSourceState,
    pub forward_source_failure: Option<ForwardSourceFailure>,
    pub rate_failure: Option<CostOfEquityResolutionError>,
    pub forecast_period_end_epoch_day: Option<i64>,
    pub latest_fiscal_year: Option<i32>,
    pub computed_at_epoch_seconds: i64,
    pub runtime_policy_version: String,
    pub router_policy_version: String,
    pub model_policy_version: String,
    pub source_fingerprints: Vec<String>,
    pub code_locators: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OperatingValuationEnvelope {
    pub decision: OperatingRouteDecision,
    pub diagnostics: OperatingRuntimeDiagnostics,
}

pub struct RuntimeValuationInput<'a> {
    pub business_class: BusinessClass,
    pub fundamentals: &'a FundamentalSnapshot,
    pub fcff_analysis: Option<&'a DcfAnalysis>,
    pub fcff_failure: Option<&'a str>,
    pub forward_evidence: Result<ForwardForecastEvidence, ForwardSourceFailure>,
    pub market_params: &'a MarketParams,
    pub as_of_epoch_day: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NormalizedForwardEvidence {
    pub forecast: ForwardForecast,
    pub hold_years: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForwardNormalizationFailure {
    MissingGrowthEvidence,
    CurrencyMismatch,
    SparseRevenueCoverage,
}

pub fn normalize_forward_evidence(
    evidence: &ForwardForecastEvidence,
    fundamentals: &FundamentalSnapshot,
) -> Result<NormalizedForwardEvidence, ForwardNormalizationFailure> {
    if evidence.currency.is_empty()
        || evidence.revenue_currency.is_empty()
        || evidence.reporting_currency.is_empty()
        || evidence.currency != evidence.revenue_currency
        || evidence.currency != evidence.reporting_currency
    {
        return Err(ForwardNormalizationFailure::CurrencyMismatch);
    }
    if evidence.revenue_analyst_count.is_none_or(|count| count < 3) {
        return Err(ForwardNormalizationFailure::SparseRevenueCoverage);
    }
    let revenue_growth = evidence
        .revenue_growth_bps
        .ok_or(ForwardNormalizationFailure::MissingGrowthEvidence)?;
    let earnings_growth = evidence
        .earnings_growth_bps
        .ok_or(ForwardNormalizationFailure::MissingGrowthEvidence)?;
    let near_growth_bps = derive_near_growth_bps(fundamentals, revenue_growth, earnings_growth);
    let hold_years = derive_hold_years(fundamentals, near_growth_bps);
    Ok(NormalizedForwardEvidence {
        forecast: ForwardForecast {
            eps_low_cents: evidence.eps_low_cents,
            eps_mean_cents: evidence.eps_mean_cents,
            eps_high_cents: evidence.eps_high_cents,
            analyst_count: evidence.analyst_count,
            near_growth_bps,
            currency: evidence.currency.clone(),
            observed_epoch_day: evidence.observed_epoch_day,
            forecast_period_end_epoch_day: evidence.forecast_period_end_epoch_day,
            source_fingerprint: evidence.source_fingerprint.clone(),
        },
        hold_years,
    })
}

fn derive_near_growth_bps(
    fundamentals: &FundamentalSnapshot,
    revenue_growth_bps: i32,
    earnings_growth_bps: i32,
) -> i32 {
    if through_cycle_business(fundamentals) || earnings_growth_bps > 10_000 {
        return 300;
    }
    if fundamentals
        .debt_to_equity_hundredths
        .is_some_and(|value| value > 50_000)
    {
        return revenue_growth_bps.clamp(-200, 2_000);
    }
    if earnings_growth_bps < 0 && revenue_growth_bps > 0 {
        return revenue_growth_bps.clamp(-200, 2_000);
    }
    mean_half_up(revenue_growth_bps, earnings_growth_bps).clamp(-200, 2_000)
}

fn mean_half_up(left: i32, right: i32) -> i32 {
    let sum = i64::from(left) + i64::from(right);
    let quotient = sum / 2;
    let remainder = sum % 2;
    if remainder == 0 {
        quotient as i32
    } else if sum > 0 {
        (quotient + 1) as i32
    } else {
        quotient as i32
    }
}

fn derive_hold_years(fundamentals: &FundamentalSnapshot, growth_bps: i32) -> i32 {
    if growth_bps > 1_200 {
        return 0;
    }
    let sector = fundamentals.sector_key.as_deref().unwrap_or("");
    let roe = fundamentals.return_on_equity_bps.unwrap_or(i32::MIN);
    let leverage = fundamentals.debt_to_equity_hundredths.unwrap_or(i32::MAX);
    if sector == "utilities" {
        5
    } else if sector == "consumer-defensive" && roe >= 1_500 {
        3
    } else if roe >= 10_000 && leverage <= 15_000 {
        10
    } else if roe >= 1_500 && leverage < 500 {
        7
    } else if roe >= 3_000 {
        3
    } else {
        0
    }
}

fn through_cycle_business(fundamentals: &FundamentalSnapshot) -> bool {
    matches!(
        fundamentals.industry_key.as_deref(),
        Some("oil-gas-e-p" | "oil-gas-integrated" | "specialty-chemicals")
    )
}

pub fn derive_structural_distortions(
    fundamentals: &FundamentalSnapshot,
    fcff: Option<&DcfAnalysis>,
    fcff_failure: Option<&str>,
    current_year: i32,
) -> Vec<StructuralDistortion> {
    let latest_year = fcff.and_then(|analysis| analysis.diagnostics.fcf_years.last().copied());
    let source_discontinuity = latest_year.is_none_or(|year| year < current_year - 1);
    let normalized_margin_bps = fcff.and_then(|analysis| {
        let normalized = i128::from(analysis.diagnostics.normalized_fcff_dollars?);
        let revenue = i128::from(analysis.diagnostics.latest_revenue_dollars?);
        (revenue > 0)
            .then(|| normalized.saturating_mul(10_000) / revenue)
            .and_then(|value| i32::try_from(value).ok())
    });
    let mature_defensive = fcff.is_some_and(|analysis| {
        normalized_margin_bps.is_some_and(|margin| margin >= 800)
            && (fundamentals.sector_key.as_deref() == Some("consumer-defensive")
                || fundamentals.industry_key.as_deref() == Some("drug-manufacturers-general"))
            && analysis.base_intrinsic_value_cents > 0
    });
    let high_growth_fcff = fcff.is_some_and(|analysis| {
        analysis.base_growth_bps > 5_000
            && analysis.base_intrinsic_value_cents > 0
            && !source_discontinuity
    });
    if mature_defensive || high_growth_fcff {
        return Vec::new();
    }

    let mut distortions = Vec::new();
    if fcff.is_none() || fcff_failure.is_some() {
        distortions.push(StructuralDistortion::TrailingCashUnavailable);
    }
    if through_cycle_business(fundamentals) {
        distortions.push(StructuralDistortion::ThroughCycleRequired);
    }
    if fundamentals
        .debt_to_equity_hundredths
        .is_some_and(|value| value > 50_000)
    {
        distortions.push(StructuralDistortion::ExtremeLeverage);
    }
    if source_discontinuity {
        distortions.push(StructuralDistortion::SourceDiscontinuity);
    }
    if normalized_margin_bps.is_some_and(|margin| margin < 800) {
        distortions.push(StructuralDistortion::ThinNormalizedFcffMargin);
    }
    if fcff.is_some_and(|analysis| {
        latest_year.is_some_and(|year| analysis.diagnostics.capex_spike_years.contains(&year))
    }) {
        distortions.push(StructuralDistortion::LatestCapexSpike);
    }
    if fcff.is_some_and(|analysis| analysis.diagnostics.driver_regime == "acquisition_normalized") {
        distortions.push(StructuralDistortion::AcquisitionDiscontinuity);
    }
    if fundamentals
        .return_on_equity_bps
        .is_some_and(|roe| roe >= 3_000)
        && fundamentals
            .debt_to_equity_hundredths
            .is_some_and(|leverage| leverage <= 50_000)
    {
        distortions.push(StructuralDistortion::DurableExcessReturnEvidence);
    }
    distortions.sort_unstable();
    distortions.dedup();
    distortions
}

pub fn route_runtime_valuation(input: RuntimeValuationInput<'_>) -> OperatingValuationEnvelope {
    let normalized_result = match input.forward_evidence.as_ref() {
        Ok(evidence) => normalize_forward_evidence(evidence, input.fundamentals)
            .map_err(ForwardSourceFailure::Normalization),
        Err(error) => Err(error.clone()),
    };
    let forward_failure = normalized_result.as_ref().err().cloned();
    let forecast_period_end_epoch_day = normalized_result
        .as_ref()
        .ok()
        .map(|value| value.forecast.forecast_period_end_epoch_day);
    let normalized = normalized_result.ok();
    let expected_currency = normalized
        .as_ref()
        .map(|value| value.forecast.currency.clone())
        .unwrap_or_else(|| "USD".into());
    let hold_years = normalized.as_ref().map_or(0, |value| value.hold_years);
    let forecast = normalized.map_or_else(
        || ForwardForecast {
            eps_low_cents: None,
            eps_mean_cents: None,
            eps_high_cents: None,
            analyst_count: None,
            near_growth_bps: 0,
            currency: expected_currency.clone(),
            observed_epoch_day: input.as_of_epoch_day,
            forecast_period_end_epoch_day: input.as_of_epoch_day,
            source_fingerprint: format!("forward-unavailable:{forward_failure:?}"),
        },
        |value| value.forecast,
    );
    let (resolved_cost, rate_failure) =
        match resolve_cost_of_equity(input.fundamentals, input.market_params) {
            Ok(value) => (value, None),
            Err(error) => (
                ResolvedCostOfEquity {
                    cost_of_equity_bps: 0,
                    beta_source: WaccFieldSource::Unavailable,
                    provisional: true,
                    market_params_as_of_epoch: input.market_params.as_of_epoch,
                    source_fingerprint: format!("cost-of-equity-unavailable:{error:?}"),
                },
                Some(error),
            ),
        };
    let policy = projection_policy(input.market_params, expected_currency, hold_years);
    let forward_candidate = value_forward_earnings(&ForwardEarningsInput {
        as_of_epoch_day: input.as_of_epoch_day,
        forecast,
        cost_of_equity: resolved_cost,
        policy,
    });
    let fcff_candidate = fcff_candidate(input.fcff_analysis, input.fcff_failure);
    let current_year = epoch_day_year(input.as_of_epoch_day);
    let structural_distortions = derive_structural_distortions(
        input.fundamentals,
        input.fcff_analysis,
        input.fcff_failure,
        current_year,
    );
    let decision = route_operating_models(OperatingRouteInput {
        business_class: input.business_class,
        fcff_candidate,
        forward_candidate,
        structural_distortions,
    });
    let forward_source_state = if forward_failure == Some(ForwardSourceFailure::NotAttempted) {
        ForwardSourceState::NotAttempted
    } else if forward_failure.is_some() {
        ForwardSourceState::Unavailable
    } else if decision.status == crate::operating_valuation::RouteStatus::Selected
        && decision.selected_model
            == Some(crate::operating_valuation::OperatingModel::ForwardEarningsPower)
    {
        ForwardSourceState::Selected
    } else {
        ForwardSourceState::Rejected
    };
    let mut source_fingerprints = vec![decision.forward_candidate.fingerprint.clone()];
    source_fingerprints.push(fundamentals_fingerprint(input.fundamentals));
    if !decision.fcff_candidate.fingerprint.is_empty() {
        source_fingerprints.push(decision.fcff_candidate.fingerprint.clone());
    }
    source_fingerprints.sort();
    source_fingerprints.dedup();
    OperatingValuationEnvelope {
        diagnostics: OperatingRuntimeDiagnostics {
            provider: if forward_failure == Some(ForwardSourceFailure::NotAttempted) {
                "not_attempted".into()
            } else {
                "yahoo_finance".into()
            },
            forward_source_state,
            forward_source_failure: forward_failure,
            rate_failure,
            forecast_period_end_epoch_day,
            latest_fiscal_year: input
                .fcff_analysis
                .and_then(|analysis| analysis.diagnostics.fcf_years.last().copied()),
            computed_at_epoch_seconds: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|duration| duration.as_secs() as i64)
                .unwrap_or(0),
            runtime_policy_version: RUNTIME_POLICY_VERSION.into(),
            router_policy_version: crate::operating_valuation::ROUTER_POLICY_VERSION.into(),
            model_policy_version: crate::dcf_model::MODEL_POLICY_VERSION.into(),
            source_fingerprints,
            code_locators: vec![
                "operating_valuation_runtime.rs#route_runtime_valuation".into(),
                "operating_valuation.rs#route_operating_models".into(),
                "operating_valuation.rs#value_forward_earnings".into(),
                "dcf_model.rs#compute".into(),
            ],
        },
        decision,
    }
}

fn projection_policy(
    market_params: &MarketParams,
    expected_currency: String,
    hold_years: i32,
) -> ProjectionPolicy {
    ProjectionPolicy {
        version: "forward-earnings-policy/1".into(),
        expected_currency,
        max_age_days: 90,
        min_forecast_horizon_days: 180,
        max_forecast_horizon_days: 730,
        min_analyst_count: 3,
        hold_years,
        fade_years: 10,
        max_projection_years: 25,
        macro_stable_growth_bps: 300,
        risk_free_rate_bps: market_params.rf_bps,
        risk_free_buffer_bps: 100,
        minimum_terminal_spread_bps: 100,
    }
}

fn fcff_candidate(analysis: Option<&DcfAnalysis>, failure: Option<&str>) -> FcffCandidate {
    let usable = analysis.filter(|value| {
        value.model == ValuationModel::FcffWacc && value.base_intrinsic_value_cents > 0
    });
    let refusal_codes = failure
        .map(|value| vec![value.to_string()])
        .unwrap_or_default();
    match usable {
        Some(value) => FcffCandidate {
            status: CandidateStatus::Available,
            intrinsic_value_cents: Some(value.base_intrinsic_value_cents),
            quality: if value.diagnostics.point_estimate_unreliable {
                ModelQuality::Soft
            } else {
                ModelQuality::Solid
            },
            refusal_codes,
            fingerprint: format!(
                "fcff-runtime/1|engine={}|policy={}|source={}|driver={}|base={}",
                value.engine_version,
                value.model_policy_version,
                value.source,
                value
                    .diagnostics
                    .driver_input_fingerprint
                    .as_deref()
                    .unwrap_or("missing"),
                value.base_intrinsic_value_cents
            ),
        },
        None => FcffCandidate {
            status: CandidateStatus::Unavailable,
            intrinsic_value_cents: None,
            quality: ModelQuality::Soft,
            refusal_codes: if refusal_codes.is_empty() {
                vec!["fcff_unavailable".into()]
            } else {
                refusal_codes
            },
            fingerprint: format!("fcff-runtime/1|unavailable={failure:?}"),
        },
    }
}

fn epoch_day_year(epoch_day: i64) -> i32 {
    use chrono::{Datelike, TimeDelta};
    let epoch = chrono::NaiveDate::from_ymd_opt(1970, 1, 1).expect("valid Unix epoch");
    epoch
        .checked_add_signed(TimeDelta::days(epoch_day))
        .map_or(1970, |date| date.year())
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use crate::dcf_model::{
        BusinessClass, DcfAnalysis, DcfDiagnostics, DiscountRateKind, MarketParams, ValuationModel,
        WaccFieldSource, WaccInputProvenance,
    };
    use crate::engine::FundamentalSnapshot;
    use crate::operating_valuation::{OperatingModel, RouteStatus, StructuralDistortion};
    use crate::quote_summary::ForwardForecastEvidence;

    use super::*;

    fn evidence() -> ForwardForecastEvidence {
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
            source_fingerprint: "yahoo-earnings-trend/1|symbol=AMZN|hash=test".into(),
        }
    }

    fn fund() -> FundamentalSnapshot {
        FundamentalSnapshot {
            symbol: "AMZN".into(),
            sector_key: Some("consumer-cyclical".into()),
            industry_key: Some("internet-retail".into()),
            return_on_equity_bps: Some(3_055),
            debt_to_equity_hundredths: Some(4_046),
            beta_millis: Some(1_350),
            ..Default::default()
        }
    }

    fn fcff(base: i64, normalized: i64, revenue: i64) -> DcfAnalysis {
        DcfAnalysis {
            bear_intrinsic_value_cents: base / 2,
            base_intrinsic_value_cents: base,
            bull_intrinsic_value_cents: base * 2,
            wacc_bps: 900,
            base_growth_bps: 1_000,
            net_debt_dollars: 0,
            wacc_inputs: WaccInputProvenance {
                market_cap: WaccFieldSource::Reported,
                beta: WaccFieldSource::IndustryShrink,
                total_debt: WaccFieldSource::Reported,
                total_cash: WaccFieldSource::Reported,
                cost_of_debt: WaccFieldSource::InterestOverDebt,
                tax_rate: WaccFieldSource::Reported,
                wacc_clamped: false,
            },
            source: "sec_edgar".into(),
            engine_version: crate::dcf_model::ENGINE_VERSION.into(),
            model_policy_version: crate::dcf_model::MODEL_POLICY_VERSION.into(),
            business_class: BusinessClass::OperatingNonFinancial,
            model: ValuationModel::FcffWacc,
            discount_rate_kind: DiscountRateKind::Wacc,
            stable_growth_bps: 300,
            book_value_per_share_cents: None,
            roe0_bps: None,
            reason_codes: vec![],
            diagnostics: DcfDiagnostics {
                normalized_fcff_dollars: Some(normalized),
                latest_revenue_dollars: Some(revenue),
                fcf_years: vec![2023, 2024, 2025],
                driver_input_fingerprint: Some("fcff:test".into()),
                valuation_driver: "driver_based_fcff".into(),
                ..Default::default()
            },
        }
    }

    #[test]
    fn production_normalization_derives_growth_hold_and_complete_provenance() {
        let normalized = normalize_forward_evidence(&evidence(), &fund()).expect("normalized");
        assert_eq!(normalized.forecast.near_growth_bps, 1_343);
        assert_eq!(normalized.hold_years, 0);
        assert!(normalized.forecast.source_fingerprint.contains("AMZN"));
        assert_eq!(normalized.forecast.currency, "USD");
    }

    #[test]
    fn thin_fcff_and_durable_returns_are_structural_evidence() {
        let analysis = fcff(900, 10, 1_000);
        let distortions = derive_structural_distortions(&fund(), Some(&analysis), None, 2026);
        assert!(distortions.contains(&StructuralDistortion::ThinNormalizedFcffMargin));
        assert!(distortions.contains(&StructuralDistortion::DurableExcessReturnEvidence));
    }

    #[test]
    fn forward_provider_failure_preserves_a_usable_fcff_primary() {
        let analysis = fcff(25_000, 200, 1_000);
        let envelope = route_runtime_valuation(RuntimeValuationInput {
            business_class: BusinessClass::OperatingNonFinancial,
            fundamentals: &fund(),
            fcff_analysis: Some(&analysis),
            fcff_failure: None,
            forward_evidence: Err(ForwardSourceFailure::Transport),
            market_params: &MarketParams::default_usd(),
            as_of_epoch_day: 20_665,
        });
        assert_eq!(envelope.decision.status, RouteStatus::Selected);
        assert_eq!(
            envelope.decision.selected_model,
            Some(OperatingModel::FcffWacc)
        );
        assert_eq!(envelope.decision.selected_value_cents, Some(25_000));
        assert_eq!(
            envelope.diagnostics.forward_source_state,
            ForwardSourceState::Unavailable
        );
    }

    #[test]
    fn material_candidate_disagreement_has_no_selected_primary() {
        let analysis = fcff(1_000, 10, 1_000);
        let envelope = route_runtime_valuation(RuntimeValuationInput {
            business_class: BusinessClass::OperatingNonFinancial,
            fundamentals: &fund(),
            fcff_analysis: Some(&analysis),
            fcff_failure: None,
            forward_evidence: Ok(evidence()),
            market_params: &MarketParams::default_usd(),
            as_of_epoch_day: 20_665,
        });
        assert_eq!(envelope.decision.status, RouteStatus::Disputed);
        assert_eq!(envelope.decision.selected_value_cents, None);
    }

    #[test]
    fn normalization_failure_is_typed_and_never_fabricates_a_period() {
        let mut sparse = evidence();
        sparse.revenue_analyst_count = Some(1);
        let envelope = route_runtime_valuation(RuntimeValuationInput {
            business_class: BusinessClass::OperatingNonFinancial,
            fundamentals: &fund(),
            fcff_analysis: None,
            fcff_failure: Some("missing_fcff"),
            forward_evidence: Ok(sparse),
            market_params: &MarketParams::default_usd(),
            as_of_epoch_day: 20_665,
        });
        assert_eq!(envelope.decision.status, RouteStatus::Unavailable);
        assert_eq!(
            envelope.diagnostics.forward_source_failure,
            Some(ForwardSourceFailure::Normalization(
                ForwardNormalizationFailure::SparseRevenueCoverage
            ))
        );
        assert_eq!(envelope.diagnostics.forecast_period_end_epoch_day, None);

        let mut wrong_currency = evidence();
        wrong_currency.reporting_currency = "EUR".into();
        assert_eq!(
            normalize_forward_evidence(&wrong_currency, &fund()),
            Err(ForwardNormalizationFailure::CurrencyMismatch)
        );
    }

    #[test]
    fn reported_provider_fixture_crosses_parser_normalization_and_runtime_router() {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/yahoo/earningsTrend/reported5.json");
        let rows: Vec<serde_json::Value> = serde_json::from_str(
            &std::fs::read_to_string(path).expect("reported provider fixture"),
        )
        .expect("fixture json");
        assert_eq!(rows.len(), 5);
        for row in rows {
            let symbol = row["symbol"].as_str().expect("symbol");
            let evidence =
                crate::quote_summary::parse_forward_forecast_evidence(&row, symbol, 20_665)
                    .unwrap_or_else(|error| panic!("{symbol}: {error:?}"));
            let mut fundamentals = fund();
            fundamentals.symbol = symbol.into();
            let envelope = route_runtime_valuation(RuntimeValuationInput {
                business_class: BusinessClass::OperatingNonFinancial,
                fundamentals: &fundamentals,
                fcff_analysis: None,
                fcff_failure: Some("fixture_missing_fcff"),
                forward_evidence: Ok(evidence),
                market_params: &MarketParams::default_usd(),
                as_of_epoch_day: 20_665,
            });
            assert_eq!(
                envelope.decision.selected_model,
                Some(OperatingModel::ForwardEarningsPower),
                "{symbol}"
            );
            assert!(
                envelope
                    .decision
                    .selected_value_cents
                    .is_some_and(|value| value > 0),
                "{symbol}"
            );
            assert!(envelope
                .diagnostics
                .source_fingerprints
                .iter()
                .any(|fingerprint| fingerprint.contains(symbol)));
        }
    }
}
