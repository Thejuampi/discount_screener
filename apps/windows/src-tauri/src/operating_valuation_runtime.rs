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
use crate::source_continuity::{
    emits_source_discontinuity, evaluate_source_continuity, ContinuityStatus,
    SourceContinuityDecision, SourceContinuityEvidence, SourceContinuityPolicy,
    CONTINUITY_POLICY_VERSION,
};

pub const RUNTIME_POLICY_VERSION: &str = "operating-valuation-runtime/6-through-cycle-roic";
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
    #[serde(default)]
    pub continuity_policy_version: String,
    #[serde(default)]
    pub continuity_status: Option<ContinuityStatus>,
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
    /// Optional last market price (cents) for FCFF scale-quality demotion only.
    /// Never used as a valuation input or clamp target.
    pub market_price_cents: Option<i64>,
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

/// `own_growth_bps` is the company's own multi-year revenue trend as the FCFF
/// lane normalized it (`DcfAnalysis::base_growth_bps`) — already median-of-recent
/// and already excluding acquisition-contaminated years. `None` when the FCFF
/// lane produced no analysis, which is an absence of evidence, not a zero.
pub fn normalize_forward_evidence(
    evidence: &ForwardForecastEvidence,
    fundamentals: &FundamentalSnapshot,
    own_growth_bps: Option<i32>,
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
    let near_growth_bps = derive_near_growth_bps(
        fundamentals,
        revenue_growth,
        earnings_growth,
        own_growth_bps,
    );
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

/// Weight on consensus when it sits exactly on the company's own trend, and the
/// weight it decays to once it has fully departed from it. These are the two
/// endpoints `industry-beta-policy-v1.json` already uses: 67/33 toward the
/// company's own evidence normally, 33/67 toward the through-cycle prior when
/// the current reading fights the cycle. Growth gets the same treatment for the
/// same reason — a consensus number far from a company's own multi-year history
/// is a cycle reading, not a durable rate.
const CONSENSUS_WEIGHT_ON_TREND_BPS: i32 = 6_700;
const CONSENSUS_WEIGHT_OFF_TREND_BPS: i32 = 3_300;

/// Deviation at which consensus carries only `CONSENSUS_WEIGHT_OFF_TREND_BPS`.
/// 7500 on the symmetric scale below is consensus at roughly three times the
/// company's own trend.
const FULL_DEVIATION_BPS: i32 = 7_500;

/// Scale anchor so "far from trend" is measured against the macro growth rate
/// rather than against zero. Without it two near-zero growths produce an
/// unbounded relative deviation.
const MACRO_GROWTH_ANCHOR_BPS: i32 = 300;

/// Arithmetic backstop on the blended near-term rate. It is Gordon headroom,
/// not a business claim about how fast a company may grow: the cyclical-peak
/// job now belongs to the blend, which acts on the input.
const NEAR_GROWTH_FLOOR_BPS: i32 = -200;
const NEAR_GROWTH_CEILING_BPS: i32 = 4_000;

/// Symmetric, scale-free distance between consensus and own trend, on the same
/// 0..20000 scale as `difference_bps` in the router.
fn growth_deviation_bps(consensus_bps: i32, own_bps: i32) -> i32 {
    let scale = i64::from(consensus_bps.abs())
        + i64::from(own_bps.abs())
        + 2 * i64::from(MACRO_GROWTH_ANCHOR_BPS);
    if scale <= 0 {
        return 0;
    }
    let gap = (i64::from(consensus_bps) - i64::from(own_bps)).abs();
    i32::try_from(gap * 20_000 / scale).unwrap_or(i32::MAX)
}

/// Every named step of the near-term growth resolution. Exposed so the audit can
/// show the blend's inputs and weight next to its output instead of a single
/// opaque rate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NearGrowthResolution {
    pub consensus_bps: i32,
    pub own_bps: Option<i32>,
    pub deviation_bps: i32,
    pub consensus_weight_bps: i32,
    pub resolved_bps: i32,
    /// What the retired flat cap would have returned. Diagnostic only — the
    /// engine never reads it.
    pub legacy_capped_bps: i32,
}

/// Blend consensus with the company's own multi-year growth, weighted by how
/// far consensus has departed from that trend.
fn blend_consensus_with_own_trend(consensus_bps: i32, own_bps: i32) -> (i32, i32, i32) {
    let deviation = growth_deviation_bps(consensus_bps, own_bps).min(FULL_DEVIATION_BPS);
    let span = i64::from(CONSENSUS_WEIGHT_ON_TREND_BPS - CONSENSUS_WEIGHT_OFF_TREND_BPS);
    let decay = ratio_half_up(span * i64::from(deviation), i64::from(FULL_DEVIATION_BPS));
    let consensus_weight = i64::from(CONSENSUS_WEIGHT_ON_TREND_BPS) - decay;
    let blended = consensus_weight * i64::from(consensus_bps)
        + (10_000 - consensus_weight) * i64::from(own_bps);
    (
        deviation,
        consensus_weight as i32,
        i32::try_from(ratio_half_up(blended, 10_000)).unwrap_or(consensus_bps),
    )
}

/// Half-up division that rounds away from zero on negatives, matching the
/// fixed-point convention the valuation lanes use everywhere else.
fn ratio_half_up(numerator: i64, denominator: i64) -> i64 {
    debug_assert!(denominator > 0);
    if numerator >= 0 {
        (numerator + denominator / 2) / denominator
    } else {
        -((-numerator + denominator / 2) / denominator)
    }
}

/// Retired flat cap on the near-term rate. Kept only so the audit can show what
/// the blend replaced; nothing in the engine reads it.
const LEGACY_NEAR_GROWTH_CEILING_BPS: i32 = 2_000;

/// The company's own revenue trend, or `None` when the FCFF lane has none to
/// give. Under `acquisition_normalized` the lane sets `base_growth_bps` to zero
/// as a *refusal* — the reported growth was inorganic and it declined to guess
/// an organic rate. Blending against that zero would read a refusal as evidence
/// that the company does not grow.
pub fn own_growth_bps(fcff_analysis: Option<&DcfAnalysis>) -> Option<i32> {
    fcff_analysis
        .filter(|analysis| analysis.diagnostics.driver_regime != ACQUISITION_NORMALIZED_REGIME)
        .map(|analysis| analysis.base_growth_bps)
}

const ACQUISITION_NORMALIZED_REGIME: &str = "acquisition_normalized";

pub fn resolve_near_growth(
    fundamentals: &FundamentalSnapshot,
    revenue_growth_bps: i32,
    earnings_growth_bps: i32,
    own_growth_bps: Option<i32>,
) -> NearGrowthResolution {
    // A through-cycle issuer already has its growth set by the cycle prior, and
    // an earnings forecast above 100% is a base-effect artefact. Neither has a
    // consensus number worth blending.
    if through_cycle_business(fundamentals) || earnings_growth_bps > 10_000 {
        return NearGrowthResolution {
            consensus_bps: 300,
            own_bps: own_growth_bps,
            deviation_bps: 0,
            consensus_weight_bps: 10_000,
            resolved_bps: 300,
            legacy_capped_bps: 300,
        };
    }
    // The blend acts on the revenue leg only, and is measured against it.
    // `own_growth_bps` is a revenue trend; the consensus figure below mixes
    // revenue with earnings growth, which operating leverage and buybacks keep
    // structurally higher. Comparing the mixed figure to a revenue-only trend
    // reports disagreement that is really just the earnings leg, and shifts
    // every issuer down whether or not consensus and history actually differ.
    // No own history means no trend to weigh consensus against — consensus
    // alone is the honest answer, not a blend against an invented number.
    let (deviation_bps, consensus_weight_bps, blended_revenue_bps) = match own_growth_bps {
        Some(own) => blend_consensus_with_own_trend(revenue_growth_bps, own),
        None => (0, 10_000, revenue_growth_bps),
    };
    let combine = |revenue_bps: i32| {
        if fundamentals
            .debt_to_equity_hundredths
            .is_some_and(|value| value > 50_000)
            || (earnings_growth_bps < 0 && revenue_bps > 0)
        {
            revenue_bps
        } else {
            mean_half_up(revenue_bps, earnings_growth_bps)
        }
    };
    NearGrowthResolution {
        consensus_bps: combine(revenue_growth_bps),
        own_bps: own_growth_bps,
        deviation_bps,
        consensus_weight_bps,
        resolved_bps: combine(blended_revenue_bps)
            .clamp(NEAR_GROWTH_FLOOR_BPS, NEAR_GROWTH_CEILING_BPS),
        legacy_capped_bps: combine(revenue_growth_bps)
            .clamp(NEAR_GROWTH_FLOOR_BPS, LEGACY_NEAR_GROWTH_CEILING_BPS),
    }
}

/// Production reads `legacy_capped_bps`, not `resolved_bps`.
///
/// The own-trend blend is implemented and measured but not switched on: on the
/// 2026-08-03 cohort it moves AAPL $245.35 → $210.00 (-14.4%) and GOOGL
/// $374.86 → $347.90 (-7.2%), both past the ±5% anchor tolerance this engine
/// holds a growth-policy change to. Switching lanes here is a one-line change to
/// `resolved_bps` once those two are accounted for.
fn derive_near_growth_bps(
    fundamentals: &FundamentalSnapshot,
    revenue_growth_bps: i32,
    earnings_growth_bps: i32,
    own_growth_bps: Option<i32>,
) -> i32 {
    resolve_near_growth(
        fundamentals,
        revenue_growth_bps,
        earnings_growth_bps,
        own_growth_bps,
    )
    .legacy_capped_bps
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

/// Hold years from current projection policy (also used by gap-attribution baselines).
pub fn derive_hold_years(fundamentals: &FundamentalSnapshot, growth_bps: i32) -> i32 {
    let sector = fundamentals.sector_key.as_deref().unwrap_or("");
    let industry = fundamentals.industry_key.as_deref().unwrap_or("");
    let industry_name = fundamentals
        .industry_name
        .as_deref()
        .unwrap_or("")
        .to_ascii_lowercase();
    let semiconductor = industry.contains("semiconductor")
        || industry_name.contains("semiconductor")
        || industry_name.contains("semiconductor equipment");
    // High near-term growth normally skips explicit hold (fade-only). Semis in a
    // recovery wave still need a short explicit hold so trough EPS is not treated
    // as a single-period fade into terminal.
    if growth_bps > 1_200 {
        return if semiconductor { 3 } else { 0 };
    }
    let roe = fundamentals.return_on_equity_bps.unwrap_or(i32::MIN);
    let leverage = fundamentals.debt_to_equity_hundredths.unwrap_or(i32::MAX);
    if sector == "utilities" {
        5
    } else if semiconductor {
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

/// Build pure continuity evidence from fundamentals + FCFF diagnostics.
pub fn continuity_evidence_from_runtime(
    fundamentals: &FundamentalSnapshot,
    fcff: Option<&DcfAnalysis>,
    as_of_epoch_day: i64,
) -> SourceContinuityEvidence {
    let years = fcff
        .map(|analysis| analysis.diagnostics.fcf_years.as_slice())
        .unwrap_or(&[]);
    SourceContinuityEvidence {
        latest_sec_fiscal_year: years.last().copied(),
        sec_series_length: years.len() as u32,
        last_sec_ocf_dollars: fcff.and_then(|analysis| analysis.diagnostics.latest_ocf_dollars),
        last_sec_fcf_dollars: fcff.and_then(|analysis| analysis.diagnostics.latest_fcf_dollars),
        yahoo_ocf_dollars: fundamentals.operating_cash_flow_dollars,
        yahoo_fcf_dollars: fundamentals.free_cash_flow_dollars,
        sec_cik: None,
        yahoo_cik: None,
        as_of_epoch_day,
    }
}

pub fn evaluate_runtime_continuity(
    fundamentals: &FundamentalSnapshot,
    fcff: Option<&DcfAnalysis>,
    as_of_epoch_day: i64,
) -> SourceContinuityDecision {
    let evidence = continuity_evidence_from_runtime(fundamentals, fcff, as_of_epoch_day);
    evaluate_source_continuity(&evidence, &SourceContinuityPolicy::default())
}

pub fn derive_structural_distortions(
    fundamentals: &FundamentalSnapshot,
    fcff: Option<&DcfAnalysis>,
    fcff_failure: Option<&str>,
    as_of_epoch_day: i64,
    market_price_cents: Option<i64>,
) -> Vec<StructuralDistortion> {
    let continuity = evaluate_runtime_continuity(fundamentals, fcff, as_of_epoch_day);
    let source_discontinuity = emits_source_discontinuity(&continuity);
    let latest_year = fcff.and_then(|analysis| analysis.diagnostics.fcf_years.last().copied());
    let normalized_margin_bps = fcff.and_then(|analysis| {
        let normalized = i128::from(analysis.diagnostics.normalized_fcff_dollars?);
        let revenue = i128::from(analysis.diagnostics.latest_revenue_dollars?);
        (revenue > 0)
            .then(|| normalized.saturating_mul(10_000) / revenue)
            .and_then(|value| i32::try_from(value).ok())
    });
    // Material scale gap vs market (tighter than hard OOM refuse) must not suppress
    // distortion routing — investment-wave semis can show high FCFF growth while
    // still undervaluing vs market (e.g. base ~0.3–0.5× price).
    let fcff_routing_scale_gap = fcff.is_some_and(|analysis| {
        let Some(mkt) = market_price_cents.filter(|&m| m >= FCFF_OOM_MIN_MARKET_CENTS) else {
            return false;
        };
        let base = analysis.base_intrinsic_value_cents;
        if base <= 0 {
            return false;
        }
        let multiple = base as f64 / mkt as f64;
        // Asymmetric: undervaluation routes earlier (<0.50×); overvaluation routes
        // from 1.75× so solid forward can displace soft-scale FCFF sooner.
        multiple < 0.50 || multiple > 1.75
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
    if (mature_defensive || high_growth_fcff) && !fcff_routing_scale_gap {
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
    // Material scale gap vs market is itself evidence that trailing FCFF is a poor
    // sole primary (investment wave undervaluation or soft overvaluation). Prefer
    // forward when available without inventing ticker-specific clamps.
    if fcff_routing_scale_gap {
        distortions.push(StructuralDistortion::ThinNormalizedFcffMargin);
    }
    distortions.sort_unstable();
    distortions.dedup();
    distortions
}

pub fn route_runtime_valuation(input: RuntimeValuationInput<'_>) -> OperatingValuationEnvelope {
    let normalized_result = match input.forward_evidence.as_ref() {
        Ok(evidence) => normalize_forward_evidence(
            evidence,
            input.fundamentals,
            own_growth_bps(input.fcff_analysis),
        )
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
    let near_growth_for_fade = normalized
        .as_ref()
        .map(|value| value.forecast.near_growth_bps)
        .unwrap_or(0);
    let fade_years = derive_fade_years(input.fundamentals, near_growth_for_fade);
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
                    industry_beta_millis: 0,
                    through_cycle_prior: false,
                    industry_beta_policy_version: crate::dcf_model::INDUSTRY_BETA_POLICY_VERSION
                        .into(),
                    industry_beta_entry_id: "unavailable".into(),
                },
                Some(error),
            ),
        };
    let policy = projection_policy(
        input.market_params,
        expected_currency,
        hold_years,
        fade_years,
    );
    let forward_candidate = value_forward_earnings(&ForwardEarningsInput {
        as_of_epoch_day: input.as_of_epoch_day,
        forecast,
        return_on_capital_bps: return_on_capital_bps(input.fundamentals, input.fcff_analysis),
        cost_of_equity: resolved_cost,
        policy,
    });
    let fcff_candidate = fcff_candidate(
        input.fcff_analysis,
        input.fcff_failure,
        input.market_price_cents,
    );
    let continuity = evaluate_runtime_continuity(
        input.fundamentals,
        input.fcff_analysis,
        input.as_of_epoch_day,
    );
    let structural_distortions = derive_structural_distortions(
        input.fundamentals,
        input.fcff_analysis,
        input.fcff_failure,
        input.as_of_epoch_day,
        input.market_price_cents,
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
    source_fingerprints.push(continuity.fingerprint.clone());
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
            continuity_policy_version: CONTINUITY_POLICY_VERSION.into(),
            continuity_status: Some(continuity.status),
            source_fingerprints,
            code_locators: vec![
                "operating_valuation_runtime.rs#route_runtime_valuation".into(),
                "source_continuity.rs#evaluate_source_continuity".into(),
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
    fade_years: i32,
) -> ProjectionPolicy {
    ProjectionPolicy {
        version: "forward-earnings-policy/2".into(),
        expected_currency,
        max_age_days: 90,
        min_forecast_horizon_days: 180,
        max_forecast_horizon_days: 730,
        min_analyst_count: 3,
        hold_years,
        fade_years,
        max_projection_years: 25,
        macro_stable_growth_bps: 300,
        risk_free_rate_bps: market_params.rf_bps,
        risk_free_buffer_bps: 100,
        minimum_terminal_spread_bps: 100,
    }
}

/// Debt/equity arrives as hundredths of a percent, so it shares the bps scale.
const BPS: i64 = 10_000;

/// After-tax cost of debt used to unlever a reported ROE when the FCFF lane
/// produced no issuer-specific figure. Deliberately generic: it is the cohort's
/// investment-grade level, not a per-ticker input.
const FALLBACK_AFTER_TAX_COST_OF_DEBT_BPS: i32 = 400;

/// Beyond this gearing the reported equity book is a rounding artifact and any
/// return computed on it is arithmetic noise, not economics — GDDY reports
/// 573x debt/equity, which turns a 442% ROE into a 0.8% "return on capital".
/// Such issuers carry no usable equity-derived signal.
const MAX_MEANINGFUL_GEARING_HUNDREDTHS: i32 = 100_000;

/// Return on **total capital** (bps), used to charge perpetual growth in the
/// forward lane.
///
/// Preference order, best evidence first:
///
/// 1. **Through-cycle.** The FCFF lane already normalizes cash flow across the
///    reported window (median OCF margin, sustaining CapEx), so
///    `normalized_fcff / invested_capital` is a multi-year return that a single
///    depressed or inflated year cannot swing. This is the noise-tolerant input,
///    and it needs no new plumbing — the analysis is already routed in.
/// 2. **Point ROE, unlevered.** Reported ROE is a return on the equity sliver
///    only, so a levered issuer looks like a compounder on the same operating
///    economics: CHTR reports 27.2% ROE on 4.4x debt/equity. Unlevering restores
///    the comparison. The debt term matters — dropping it (plain `ROE/(1+D/E)`)
///    silently assumes debt is free and understates CHTR's return by ~40%.
/// 3. `None` — no evidence at all. [`terminal_payout_bps`] then treats growth as
///    value-neutral rather than guessing.
///
/// Smoothing happens *here*, on the input. The payout function no longer floors
/// its result at the cost of capital, because doing both erased the differences
/// between low-return issuers.
pub fn return_on_capital_bps(
    fundamentals: &FundamentalSnapshot,
    fcff_analysis: Option<&DcfAnalysis>,
) -> Option<i32> {
    through_cycle_return_on_capital_bps(fundamentals, fcff_analysis).or_else(|| {
        let after_tax_cost_of_debt_bps = fcff_analysis
            .and_then(|analysis| analysis.diagnostics.after_tax_cost_of_debt_bps)
            .filter(|value| *value > 0)
            .unwrap_or(FALLBACK_AFTER_TAX_COST_OF_DEBT_BPS);
        unlevered_return_on_equity_bps(fundamentals, after_tax_cost_of_debt_bps)
    })
}

/// Multi-year return on invested capital from the FCFF lane's normalized cash
/// flow. `None` whenever any leg is missing — the caller falls back rather than
/// substituting a default.
fn through_cycle_return_on_capital_bps(
    fundamentals: &FundamentalSnapshot,
    fcff_analysis: Option<&DcfAnalysis>,
) -> Option<i32> {
    let normalized_fcff = fcff_analysis?.diagnostics.normalized_fcff_dollars?;
    if normalized_fcff <= 0 {
        return None;
    }
    let book_value_per_share = fundamentals.book_value_per_share_cents.filter(|v| *v > 0)?;
    let shares = fundamentals.shares_outstanding.filter(|v| *v > 0)?;
    let book_equity = i128::from(book_value_per_share) * i128::from(shares) / 100;
    let invested = book_equity + i128::from(fundamentals.total_debt_dollars.unwrap_or(0).max(0));
    if invested <= 0 {
        return None;
    }
    let roic = i128::from(normalized_fcff) * i128::from(BPS) / invested;
    i32::try_from(roic).ok().filter(|value| *value > 0)
}

/// `ROIC = (ROE + Kd_after_tax x D/E) / (1 + D/E)`. The debt term restores the
/// earnings that service debt, which the equity-only ratio drops. `Kd` is the
/// issuer's own resolved after-tax cost of debt when the FCFF lane produced one;
/// [`FALLBACK_AFTER_TAX_COST_OF_DEBT_BPS`] otherwise.
fn unlevered_return_on_equity_bps(
    fundamentals: &FundamentalSnapshot,
    after_tax_cost_of_debt_bps: i32,
) -> Option<i32> {
    let roe = fundamentals
        .return_on_equity_bps
        .filter(|value| *value > 0)?;
    let gearing_hundredths = fundamentals.debt_to_equity_hundredths.unwrap_or(0).max(0);
    if gearing_hundredths > MAX_MEANINGFUL_GEARING_HUNDREDTHS {
        return None;
    }
    let gearing = BPS + i64::from(gearing_hundredths);
    let debt_return = i64::from(after_tax_cost_of_debt_bps) * i64::from(gearing_hundredths);
    let unlevered = (i64::from(roe) * BPS + debt_return) / gearing.max(1);
    i32::try_from(unlevered).ok().filter(|value| *value > 0)
}

/// Fade horizon: shorter only for through-cycle / extreme-leverage businesses,
/// where high near-term growth must not compound for a full decade into terminal
/// value. Secular compounders keep the full fade window.
/// Also consumed by gap-attribution as the active horizon setting.
pub fn derive_fade_years(fundamentals: &FundamentalSnapshot, _near_growth_bps: i32) -> i32 {
    let extreme_leverage = fundamentals
        .debt_to_equity_hundredths
        .is_some_and(|value| value > 50_000);
    if through_cycle_business(fundamentals) || extreme_leverage {
        5
    } else {
        10
    }
}

/// Order-of-magnitude band vs market used only as a **quality** demotion
/// (soft vs solid). Never clamps or rewrites intrinsic value.
const FCFF_OOM_MAX_MULTIPLE: f64 = 3.0;
const FCFF_OOM_MIN_MULTIPLE: f64 = 0.10;
const FCFF_OOM_MIN_MARKET_CENTS: i64 = 500;

fn fcff_scale_soft(base_cents: i64, market_price_cents: Option<i64>) -> bool {
    let Some(mkt) = market_price_cents else {
        return false;
    };
    if mkt < FCFF_OOM_MIN_MARKET_CENTS || base_cents <= 0 {
        return false;
    }
    let multiple = base_cents as f64 / mkt as f64;
    multiple > FCFF_OOM_MAX_MULTIPLE || multiple < FCFF_OOM_MIN_MULTIPLE
}

fn fcff_candidate(
    analysis: Option<&DcfAnalysis>,
    failure: Option<&str>,
    market_price_cents: Option<i64>,
) -> FcffCandidate {
    let usable = analysis.filter(|value| {
        value.model == ValuationModel::FcffWacc && value.base_intrinsic_value_cents > 0
    });
    let refusal_codes = failure
        .map(|value| vec![value.to_string()])
        .unwrap_or_default();
    match usable {
        Some(value) => {
            let base = value.base_intrinsic_value_cents;
            let rates_soft = value.diagnostics.point_estimate_unreliable;
            let scale_soft = fcff_scale_soft(base, market_price_cents);
            FcffCandidate {
                status: CandidateStatus::Available,
                intrinsic_value_cents: Some(base),
                quality: if rates_soft || scale_soft {
                    ModelQuality::Soft
                } else {
                    ModelQuality::Solid
                },
                refusal_codes,
                fingerprint: format!(
                    "fcff-runtime/1|engine={}|policy={}|source={}|driver={}|base={}|scale_soft={}",
                    value.engine_version,
                    value.model_policy_version,
                    value.source,
                    value
                        .diagnostics
                        .driver_input_fingerprint
                        .as_deref()
                        .unwrap_or("missing"),
                    base,
                    scale_soft
                ),
            }
        }
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
    use crate::source_continuity::ContinuityStatus;

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
            // Aligned Yahoo cash so continuity does not invent discontinuity.
            operating_cash_flow_dollars: Some(115_000_000_000),
            free_cash_flow_dollars: Some(8_000_000_000),
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
                latest_fcf_dollars: Some(7_695_000_000),
                latest_ocf_dollars: Some(115_900_000_000),
                driver_input_fingerprint: Some("fcff:test".into()),
                valuation_driver: "driver_based_fcff".into(),
                ..Default::default()
            },
        }
    }

    fn sndk_fund() -> FundamentalSnapshot {
        FundamentalSnapshot {
            symbol: "SNDK".into(),
            sector_key: Some("technology".into()),
            industry_key: Some("semiconductors".into()),
            return_on_equity_bps: Some(1_200),
            debt_to_equity_hundredths: Some(2_000),
            beta_millis: Some(1_400),
            operating_cash_flow_dollars: Some(4_640_000_000),
            free_cash_flow_dollars: Some(2_260_000_000),
            ..Default::default()
        }
    }

    fn sndk_fcff() -> DcfAnalysis {
        let mut analysis = fcff(500, 50, 1_000);
        analysis.diagnostics.fcf_years = vec![2020, 2021, 2022];
        analysis.diagnostics.latest_fcf_dollars = Some(-120_000_000);
        analysis.diagnostics.latest_ocf_dollars = Some(84_000_000);
        analysis.diagnostics.normalized_fcff_dollars = Some(-50_000_000);
        analysis.diagnostics.latest_revenue_dollars = Some(2_000_000_000);
        analysis
    }

    #[test]
    fn consensus_on_its_own_trend_keeps_the_company_weight() {
        let resolution = resolve_near_growth(&fund(), 1_500, 1_500, Some(1_500));

        assert_eq!(
            resolution.consensus_weight_bps,
            CONSENSUS_WEIGHT_ON_TREND_BPS
        );
    }

    #[test]
    fn consensus_far_from_trend_decays_to_the_through_cycle_weight() {
        // Consensus at 30% against a 3% own trend saturates the deviation ramp.
        let resolution = resolve_near_growth(&fund(), 3_000, 3_000, Some(300));

        assert_eq!(
            resolution.consensus_weight_bps,
            CONSENSUS_WEIGHT_OFF_TREND_BPS
        );
    }

    /// Revenue 30% against a 3% own trend saturates the ramp, so the revenue leg
    /// blends to 33/67 = 1191 bps. The 30% earnings leg is untouched, and the
    /// two recombine to mean(1191, 3000) = 2096.
    #[test]
    fn saturated_deviation_pulls_the_rate_toward_the_companys_own_history() {
        let resolution = resolve_near_growth(&fund(), 3_000, 3_000, Some(300));

        assert_eq!(resolution.resolved_bps, 2_096);
    }

    /// `own_growth_bps` is a revenue trend. Measuring it against a figure that
    /// already mixes in earnings growth reports the earnings leg as if it were
    /// disagreement, and marks every issuer down whether or not consensus and
    /// history differ at all.
    #[test]
    fn deviation_compares_revenue_against_revenue_not_the_mixed_consensus() {
        let on_trend_revenue = resolve_near_growth(&fund(), 1_000, 4_000, Some(1_000));

        assert_eq!(on_trend_revenue.deviation_bps, 0);
    }

    /// Under `acquisition_normalized` the FCFF lane sets `base_growth_bps` to
    /// zero because the reported growth was inorganic — it is a refusal, not a
    /// measurement of a company that does not grow.
    #[test]
    fn acquisition_normalized_growth_is_absent_evidence_not_a_zero_trend() {
        let mut analysis = fcff(900, 10, 1_000);
        analysis.base_growth_bps = 0;
        analysis.diagnostics.driver_regime = ACQUISITION_NORMALIZED_REGIME.into();

        assert_eq!(own_growth_bps(Some(&analysis)), None);
    }

    #[test]
    fn production_still_reads_the_flat_cap_until_the_blend_clears_its_anchors() {
        let resolution = resolve_near_growth(&fund(), 3_000, 3_000, Some(300));

        assert_eq!(
            derive_near_growth_bps(&fund(), 3_000, 3_000, Some(300)),
            resolution.legacy_capped_bps
        );
    }

    #[test]
    fn production_normalization_derives_growth_hold_and_complete_provenance() {
        let normalized =
            normalize_forward_evidence(&evidence(), &fund(), None).expect("normalized");
        assert_eq!(normalized.forecast.near_growth_bps, 1_343);
        assert_eq!(normalized.hold_years, 0);
        assert!(normalized.forecast.source_fingerprint.contains("AMZN"));
        assert_eq!(normalized.forecast.currency, "USD");
    }

    #[test]
    fn thin_fcff_and_durable_returns_are_structural_evidence() {
        let analysis = fcff(900, 10, 1_000);
        let distortions =
            derive_structural_distortions(&fund(), Some(&analysis), None, 20_665, None);
        assert!(distortions.contains(&StructuralDistortion::ThinNormalizedFcffMargin));
        assert!(distortions.contains(&StructuralDistortion::DurableExcessReturnEvidence));
        assert!(!distortions.contains(&StructuralDistortion::SourceDiscontinuity));
    }

    #[test]
    fn sndk_class_emits_source_discontinuity_via_continuity_gate() {
        let analysis = sndk_fcff();
        let continuity = evaluate_runtime_continuity(&sndk_fund(), Some(&analysis), 20_665);
        assert_eq!(continuity.status, ContinuityStatus::Discontinuous);
        let distortions =
            derive_structural_distortions(&sndk_fund(), Some(&analysis), None, 20_665, None);
        assert!(distortions.contains(&StructuralDistortion::SourceDiscontinuity));
        assert!(continuity.fingerprint.contains(CONTINUITY_POLICY_VERSION));
    }

    #[test]
    fn continuous_issuer_does_not_force_forward_from_calendar_age() {
        let analysis = fcff(25_000, 200, 1_000);
        let continuity = evaluate_runtime_continuity(&fund(), Some(&analysis), 20_665);
        assert_eq!(continuity.status, ContinuityStatus::Continuous);
        let distortions =
            derive_structural_distortions(&fund(), Some(&analysis), None, 20_665, None);
        assert!(!distortions.contains(&StructuralDistortion::SourceDiscontinuity));
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
            market_price_cents: None,
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
            market_price_cents: None,
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
            market_price_cents: None,
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
            normalize_forward_evidence(&wrong_currency, &fund(), None),
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
                market_price_cents: None,
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
