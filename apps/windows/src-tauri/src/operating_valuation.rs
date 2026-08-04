//! Pure, provider-independent operating valuation candidates and routing.
//!
//! Market price and analyst target are deliberately absent from every DTO in
//! this module. Provider normalization and runtime/UI integration are separate
//! boundaries.

use serde::{Deserialize, Serialize};

use crate::dcf_model::{BusinessClass, ResolvedCostOfEquity};

pub const ENGINE_VERSION: &str = "operating-valuation-router/2";
pub const ROUTER_POLICY_VERSION: &str = "operating-model-router-policy/1";
pub const DISPUTED_DIFFERENCE_BPS: i64 = 5_000;
const BPS_SCALE: i128 = 10_000;
const HARD_MAX_PROJECTION_YEARS: i32 = 100;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OperatingModel {
    FcffWacc,
    ForwardEarningsPower,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CandidateStatus {
    Available,
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RouteStatus {
    Selected,
    Disputed,
    Unavailable,
    NotEligible,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ModelQuality {
    Solid,
    Soft,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceFamily {
    CashFlowModel,
    AnalystDerivedModel,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CandidateRefusal {
    MissingForwardEps,
    NonPositiveForwardEps,
    InvalidForecastRange,
    MissingCoverage,
    SparseCoverage,
    StaleForecast,
    CurrencyMismatch,
    MissingCurrency,
    MissingSourceFingerprint,
    InvalidPolicy,
    InvalidForecastPeriod,
    InvalidProjectionHorizon,
    InvalidGrowth,
    InvalidCostOfEquity,
    CostOfEquityNotAboveStableGrowth,
    ArithmeticOverflow,
    NonPositiveProjectedValue,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StructuralDistortion {
    TrailingCashUnavailable,
    ThroughCycleRequired,
    ExtremeLeverage,
    SourceDiscontinuity,
    ThinNormalizedFcffMargin,
    LatestCapexSpike,
    AcquisitionDiscontinuity,
    DurableExcessReturnEvidence,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RouteReason {
    FamilyFinancialServices,
    FamilyNotEligible,
    FamilyUnclassified,
    StructuralDistortionPresent,
    SelectedForwardEarningsPower,
    SelectedRepresentativeFcff,
    ForwardCandidateUnavailable,
    FcffCandidateUnavailable,
    ForwardRequiresStructuralDistortion,
    CandidateDisagreement,
    InvalidForwardCandidate,
    InvalidFcffCandidate,
    /// The two lanes disagreed materially and the dispute was resolved to the
    /// lane whose evidence set strictly contains the other's. Recorded
    /// alongside `CandidateDisagreement`, never instead of it.
    DisagreementResolvedToForwardEvidence,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ForwardForecast {
    pub eps_low_cents: Option<i64>,
    pub eps_mean_cents: Option<i64>,
    pub eps_high_cents: Option<i64>,
    pub analyst_count: Option<i32>,
    pub near_growth_bps: i32,
    pub currency: String,
    pub observed_epoch_day: i64,
    pub forecast_period_end_epoch_day: i64,
    pub source_fingerprint: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectionPolicy {
    pub version: String,
    pub expected_currency: String,
    pub max_age_days: i64,
    pub min_forecast_horizon_days: i64,
    pub max_forecast_horizon_days: i64,
    pub min_analyst_count: i32,
    pub hold_years: i32,
    pub fade_years: i32,
    pub max_projection_years: i32,
    pub macro_stable_growth_bps: i32,
    pub risk_free_rate_bps: i32,
    pub risk_free_buffer_bps: i32,
    pub minimum_terminal_spread_bps: i32,
}

impl ProjectionPolicy {
    pub fn stable_growth_bps(&self, cost_of_equity_bps: i32) -> Option<i32> {
        let rate_linked = self
            .risk_free_rate_bps
            .checked_sub(self.risk_free_buffer_bps)?;
        let gordon_linked = cost_of_equity_bps.checked_sub(self.minimum_terminal_spread_bps)?;
        Some(
            self.macro_stable_growth_bps
                .min(rate_linked)
                .min(gordon_linked),
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ForwardEarningsInput {
    pub as_of_epoch_day: i64,
    pub forecast: ForwardForecast,
    pub cost_of_equity: ResolvedCostOfEquity,
    pub policy: ProjectionPolicy,
    /// Return on total capital (bps) used to charge perpetual growth. `None`
    /// means *no evidence*, and [`terminal_payout_bps`] then assumes the issuer
    /// earns exactly its cost of capital so growth is value-neutral. It is not a
    /// floor on measured returns — see that function.
    #[serde(default)]
    pub return_on_capital_bps: Option<i32>,
}

/// Minimum spread the return on capital must keep over perpetual growth.
///
/// This is a **mathematical guard only** — it keeps `1 - g/ROIC` positive and
/// bounded when the measured return approaches or falls below `g`. It carries no
/// economic claim about the business, and it deliberately sits just above `g`
/// rather than at the cost of capital. Mirrors the FCFF lane's
/// `minimum_terminal_spread_bps`.
pub const MIN_TERMINAL_ROIC_SPREAD_BPS: i32 = 100;

/// Share of terminal earnings that is actually distributable (bps of earnings).
///
/// A business growing perpetually at `g` must retain `b = g / ROIC` of its
/// earnings to fund the capital that growth consumes; only `1 - b` reaches the
/// owner. Capitalizing the **full** analyst EPS while also granting `g` forever
/// is free-lunch growth, and it is the reason the forward lane priced the
/// cohort at a median 1.5x market with the error rising monotonically as return
/// on capital fell (CHTR at 5.0% ROIC came out 5.5x market).
///
/// Two different problems meet here and are kept strictly apart:
///
/// * **Missing evidence** — `return_on_capital_bps` is `None`. The honest prior
///   is that the issuer merely earns its cost of capital, so growth is
///   value-neutral (`terminal` collapses to `EPS / r`). That is an economic
///   statement about ignorance.
/// * **Arithmetic safety** — a measured return at or below `g` would make the
///   payout non-positive. [`MIN_TERMINAL_ROIC_SPREAD_BPS`] bounds it. That is a
///   statement about the formula, not about the business.
///
/// Collapsing the two — flooring *observed* returns at the cost of equity —
/// is what flattened every sub-cost-of-capital issuer onto one payout: SW at
/// 1.5% ROIC, OMC at 2.9% and CHTR at 5.0% were all charged as if they earned
/// their cost of capital, which erased exactly the differentiation this function
/// exists to make.
pub fn terminal_payout_bps(
    return_on_capital_bps: Option<i32>,
    cost_of_equity_bps: i32,
    stable_growth_bps: i32,
) -> i32 {
    if cost_of_equity_bps <= 0 {
        return BPS_SCALE as i32;
    }
    if stable_growth_bps <= 0 {
        return BPS_SCALE as i32;
    }
    let observed = return_on_capital_bps.unwrap_or(cost_of_equity_bps);
    let effective = observed.max(stable_growth_bps.saturating_add(MIN_TERMINAL_ROIC_SPREAD_BPS));
    let retention = i64::from(stable_growth_bps) * BPS_SCALE as i64 / i64::from(effective);
    (BPS_SCALE as i64 - retention).clamp(0, BPS_SCALE as i64) as i32
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ForwardEarningsCandidate {
    pub model: OperatingModel,
    pub status: CandidateStatus,
    pub intrinsic_value_cents: Option<i64>,
    pub cost_of_equity_bps: i32,
    pub stable_growth_bps: Option<i32>,
    pub projection_years: Option<i32>,
    pub quality: ModelQuality,
    pub evidence_family: EvidenceFamily,
    pub refusals: Vec<CandidateRefusal>,
    pub provenance: ForwardEarningsInput,
    pub fingerprint: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FcffCandidate {
    pub status: CandidateStatus,
    pub intrinsic_value_cents: Option<i64>,
    pub quality: ModelQuality,
    pub refusal_codes: Vec<String>,
    pub fingerprint: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OperatingRouteInput {
    pub business_class: BusinessClass,
    pub fcff_candidate: FcffCandidate,
    pub forward_candidate: ForwardEarningsCandidate,
    pub structural_distortions: Vec<StructuralDistortion>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OperatingRouteDecision {
    pub status: RouteStatus,
    pub selected_model: Option<OperatingModel>,
    pub selected_value_cents: Option<i64>,
    pub candidate_difference_bps: Option<i64>,
    pub reasons: Vec<RouteReason>,
    pub structural_distortions: Vec<StructuralDistortion>,
    pub fcff_candidate: FcffCandidate,
    pub forward_candidate: ForwardEarningsCandidate,
    pub fingerprint: String,
}

pub fn value_forward_earnings(input: &ForwardEarningsInput) -> ForwardEarningsCandidate {
    let mut refusals = validate_forward_input(input);
    refusals.sort_unstable();
    refusals.dedup();
    let stable_growth_bps = input
        .policy
        .stable_growth_bps(input.cost_of_equity.cost_of_equity_bps);
    let projection_years = input
        .policy
        .hold_years
        .checked_add(input.policy.fade_years)
        .and_then(|years| years.checked_add(1));
    let fingerprint = forward_fingerprint(input, stable_growth_bps);

    if !refusals.is_empty() {
        return ForwardEarningsCandidate {
            model: OperatingModel::ForwardEarningsPower,
            status: CandidateStatus::Unavailable,
            intrinsic_value_cents: None,
            cost_of_equity_bps: input.cost_of_equity.cost_of_equity_bps,
            stable_growth_bps,
            projection_years,
            quality: ModelQuality::Soft,
            evidence_family: EvidenceFamily::AnalystDerivedModel,
            refusals,
            provenance: input.clone(),
            fingerprint,
        };
    }

    let stable = stable_growth_bps.expect("validated stable growth");
    let value = project_forward_value(
        input.forecast.eps_mean_cents.expect("validated EPS"),
        input.forecast.near_growth_bps,
        input.cost_of_equity.cost_of_equity_bps,
        stable,
        input.policy.hold_years,
        input.policy.fade_years,
        terminal_payout_bps(
            input.return_on_capital_bps,
            input.cost_of_equity.cost_of_equity_bps,
            stable,
        ),
    );
    match value {
        Some(value) if value > 0 => ForwardEarningsCandidate {
            model: OperatingModel::ForwardEarningsPower,
            status: CandidateStatus::Available,
            intrinsic_value_cents: Some(value),
            cost_of_equity_bps: input.cost_of_equity.cost_of_equity_bps,
            stable_growth_bps,
            projection_years,
            // Solid when CoE is market-sourced (non-provisional). Soft when rates
            // still bootstrap from policy defaults.
            quality: if input.cost_of_equity.provisional {
                ModelQuality::Soft
            } else {
                ModelQuality::Solid
            },
            evidence_family: EvidenceFamily::AnalystDerivedModel,
            refusals: Vec::new(),
            provenance: input.clone(),
            fingerprint,
        },
        Some(_) => ForwardEarningsCandidate {
            model: OperatingModel::ForwardEarningsPower,
            status: CandidateStatus::Unavailable,
            intrinsic_value_cents: None,
            cost_of_equity_bps: input.cost_of_equity.cost_of_equity_bps,
            stable_growth_bps,
            projection_years,
            quality: ModelQuality::Soft,
            evidence_family: EvidenceFamily::AnalystDerivedModel,
            refusals: vec![CandidateRefusal::NonPositiveProjectedValue],
            provenance: input.clone(),
            fingerprint,
        },
        None => ForwardEarningsCandidate {
            model: OperatingModel::ForwardEarningsPower,
            status: CandidateStatus::Unavailable,
            intrinsic_value_cents: None,
            cost_of_equity_bps: input.cost_of_equity.cost_of_equity_bps,
            stable_growth_bps,
            projection_years,
            quality: ModelQuality::Soft,
            evidence_family: EvidenceFamily::AnalystDerivedModel,
            refusals: vec![CandidateRefusal::ArithmeticOverflow],
            provenance: input.clone(),
            fingerprint,
        },
    }
}

pub fn route_operating_models(input: OperatingRouteInput) -> OperatingRouteDecision {
    let mut structural_distortions = input.structural_distortions.clone();
    structural_distortions.sort_unstable();
    structural_distortions.dedup();
    let forward_consistent = valid_forward_candidate(&input.forward_candidate);
    let fcff_consistent = valid_fcff_candidate(&input.fcff_candidate);
    let forward_available = forward_consistent
        .then(|| {
            candidate_value(
                input.forward_candidate.status,
                input.forward_candidate.intrinsic_value_cents,
            )
        })
        .flatten();
    let fcff_available = fcff_consistent
        .then(|| {
            candidate_value(
                input.fcff_candidate.status,
                input.fcff_candidate.intrinsic_value_cents,
            )
        })
        .flatten();
    let mut reasons = Vec::new();
    if !forward_consistent {
        reasons.push(RouteReason::InvalidForwardCandidate);
    }
    if !fcff_consistent {
        reasons.push(RouteReason::InvalidFcffCandidate);
    }

    let (status, selected_model, selected_value_cents, difference) = match input.business_class {
        BusinessClass::FinancialServices => {
            reasons.push(RouteReason::FamilyFinancialServices);
            (RouteStatus::Unavailable, None, None, None)
        }
        BusinessClass::NotEligible => {
            reasons.push(RouteReason::FamilyNotEligible);
            (RouteStatus::NotEligible, None, None, None)
        }
        BusinessClass::Unclassified => {
            reasons.push(RouteReason::FamilyUnclassified);
            (RouteStatus::Unavailable, None, None, None)
        }
        BusinessClass::OperatingNonFinancial if !structural_distortions.is_empty() => {
            reasons.push(RouteReason::StructuralDistortionPresent);
            match (forward_available, fcff_available) {
                (Some(forward), Some(fcff)) => {
                    let difference = difference_bps(forward, fcff);
                    // Material disagreement stays labelled `Disputed`, but it no
                    // longer suppresses the number. This branch is reached only
                    // under structural distortion — the exact condition under
                    // which the trailing series is known to be contaminated —
                    // and the forward lane observes that same filed history plus
                    // a forecast. Its evidence set strictly contains the FCFF
                    // lane's, so the disagreement resolves toward it on evidence
                    // grounds alone. `Disputed` keeps the disagreement visible
                    // and keeps the name out of ranking scores; what changes is
                    // that a reader now gets a value instead of a blank.
                    let fwd_solid = input.forward_candidate.quality == ModelQuality::Solid;
                    let fcff_solid = input.fcff_candidate.quality == ModelQuality::Solid;
                    if difference.is_some_and(|value| value > DISPUTED_DIFFERENCE_BPS) {
                        reasons.push(RouteReason::CandidateDisagreement);
                        reasons.push(RouteReason::DisagreementResolvedToForwardEvidence);
                        (
                            RouteStatus::Disputed,
                            Some(OperatingModel::ForwardEarningsPower),
                            Some(forward),
                            difference,
                        )
                    } else if fwd_solid || !fcff_solid {
                        reasons.push(RouteReason::SelectedForwardEarningsPower);
                        (
                            RouteStatus::Selected,
                            Some(OperatingModel::ForwardEarningsPower),
                            Some(forward),
                            difference,
                        )
                    } else {
                        // Forward soft, FCFF solid — keep FCFF under distortion only
                        // when forward quality is weaker.
                        reasons.push(RouteReason::SelectedRepresentativeFcff);
                        (
                            RouteStatus::Selected,
                            Some(OperatingModel::FcffWacc),
                            Some(fcff),
                            difference,
                        )
                    }
                }
                (Some(forward), None) => {
                    reasons.push(RouteReason::FcffCandidateUnavailable);
                    reasons.push(RouteReason::SelectedForwardEarningsPower);
                    (
                        RouteStatus::Selected,
                        Some(OperatingModel::ForwardEarningsPower),
                        Some(forward),
                        None,
                    )
                }
                (None, Some(fcff)) => {
                    reasons.push(RouteReason::ForwardCandidateUnavailable);
                    reasons.push(RouteReason::SelectedRepresentativeFcff);
                    (
                        RouteStatus::Selected,
                        Some(OperatingModel::FcffWacc),
                        Some(fcff),
                        None,
                    )
                }
                (None, None) => {
                    reasons.push(RouteReason::ForwardCandidateUnavailable);
                    reasons.push(RouteReason::FcffCandidateUnavailable);
                    (RouteStatus::Unavailable, None, None, None)
                }
            }
        }
        // No structural distortion: FCFF is the primary whenever it exists, and the
        // forward lane keeps its distortion-only mandate. Candidate quality decides
        // *within* the distorted branch above; it never promotes the analyst-derived
        // lane across the undistorted cohort — that is a policy change, not a fix.
        BusinessClass::OperatingNonFinancial => match fcff_available {
            Some(fcff) => {
                if forward_available.is_some() {
                    reasons.push(RouteReason::ForwardRequiresStructuralDistortion);
                }
                reasons.push(RouteReason::SelectedRepresentativeFcff);
                (
                    RouteStatus::Selected,
                    Some(OperatingModel::FcffWacc),
                    Some(fcff),
                    None,
                )
            }
            None => {
                reasons.push(RouteReason::FcffCandidateUnavailable);
                if forward_available.is_some() {
                    reasons.push(RouteReason::ForwardRequiresStructuralDistortion);
                } else {
                    reasons.push(RouteReason::ForwardCandidateUnavailable);
                }
                (RouteStatus::Unavailable, None, None, None)
            }
        },
    };

    reasons.sort_unstable();
    reasons.dedup();
    let fingerprint = route_fingerprint(
        input.business_class,
        status,
        selected_model,
        &input.fcff_candidate,
        &input.forward_candidate,
        &structural_distortions,
        &reasons,
    );
    OperatingRouteDecision {
        status,
        selected_model,
        selected_value_cents,
        candidate_difference_bps: difference,
        reasons,
        structural_distortions,
        fcff_candidate: input.fcff_candidate,
        forward_candidate: input.forward_candidate,
        fingerprint,
    }
}

fn validate_forward_input(input: &ForwardEarningsInput) -> Vec<CandidateRefusal> {
    let mut refusals = Vec::new();
    match (
        input.forecast.eps_low_cents,
        input.forecast.eps_mean_cents,
        input.forecast.eps_high_cents,
    ) {
        (None, _, _) | (_, None, _) | (_, _, None) => {
            refusals.push(CandidateRefusal::MissingForwardEps)
        }
        (Some(low), Some(mean), Some(high)) if low <= 0 || mean <= 0 || high <= 0 => {
            refusals.push(CandidateRefusal::NonPositiveForwardEps)
        }
        (Some(low), Some(mean), Some(high)) if low > mean || mean > high => {
            refusals.push(CandidateRefusal::InvalidForecastRange)
        }
        _ => {}
    }
    match input.forecast.analyst_count {
        None => refusals.push(CandidateRefusal::MissingCoverage),
        Some(count) if count <= 0 || count < input.policy.min_analyst_count => {
            refusals.push(CandidateRefusal::SparseCoverage)
        }
        _ => {}
    }
    if input.forecast.currency.trim().is_empty() {
        refusals.push(CandidateRefusal::MissingCurrency);
    } else if input.forecast.currency != input.policy.expected_currency {
        refusals.push(CandidateRefusal::CurrencyMismatch);
    }
    if input.forecast.source_fingerprint.trim().is_empty()
        || input.cost_of_equity.source_fingerprint.trim().is_empty()
    {
        refusals.push(CandidateRefusal::MissingSourceFingerprint);
    }
    if input.policy.version.trim().is_empty()
        || input.policy.expected_currency.trim().is_empty()
        || input.policy.max_age_days < 0
        || input.policy.min_forecast_horizon_days <= 0
        || input.policy.max_forecast_horizon_days < input.policy.min_forecast_horizon_days
        || input.policy.min_analyst_count <= 0
        || input.policy.minimum_terminal_spread_bps <= 0
    {
        refusals.push(CandidateRefusal::InvalidPolicy);
    }
    let age = input
        .as_of_epoch_day
        .checked_sub(input.forecast.observed_epoch_day);
    if age.is_none_or(|days| days < 0 || days > input.policy.max_age_days) {
        refusals.push(CandidateRefusal::StaleForecast);
    }
    let forecast_horizon = input
        .forecast
        .forecast_period_end_epoch_day
        .checked_sub(input.as_of_epoch_day);
    if forecast_horizon.is_none_or(|days| {
        days < input.policy.min_forecast_horizon_days
            || days > input.policy.max_forecast_horizon_days
    }) {
        refusals.push(CandidateRefusal::InvalidForecastPeriod);
    }
    let projection_years = input
        .policy
        .hold_years
        .checked_add(input.policy.fade_years)
        .and_then(|years| years.checked_add(1));
    if input.policy.hold_years < 0
        || input.policy.fade_years <= 0
        || input.policy.max_projection_years <= 0
        || projection_years.is_none_or(|years| {
            years > input.policy.max_projection_years || years > HARD_MAX_PROJECTION_YEARS
        })
    {
        refusals.push(CandidateRefusal::InvalidProjectionHorizon);
    }
    if input.forecast.near_growth_bps <= -10_000 {
        refusals.push(CandidateRefusal::InvalidGrowth);
    }
    let rate = input.cost_of_equity.cost_of_equity_bps;
    if rate <= 0 {
        refusals.push(CandidateRefusal::InvalidCostOfEquity);
    }
    match input.policy.stable_growth_bps(rate) {
        None => refusals.push(CandidateRefusal::ArithmeticOverflow),
        Some(stable) if stable <= -10_000 => refusals.push(CandidateRefusal::InvalidGrowth),
        Some(stable)
            if rate
                .checked_sub(stable)
                .is_none_or(|spread| spread < input.policy.minimum_terminal_spread_bps) =>
        {
            refusals.push(CandidateRefusal::CostOfEquityNotAboveStableGrowth)
        }
        _ => {}
    }
    refusals
}

/// Analyst EPS over the explicit horizon is taken as given evidence — the
/// forecast already prices whatever reinvestment the next few years need. Only
/// the **perpetuity** is charged for the capital its growth consumes, via
/// `terminal_payout_bps`; that is where the free-growth error concentrated
/// (~70% of a typical name's value sits in the terminal).
fn project_forward_value(
    eps_mean_cents: i64,
    near_growth_bps: i32,
    cost_of_equity_bps: i32,
    stable_growth_bps: i32,
    hold_years: i32,
    fade_years: i32,
    terminal_payout_bps: i32,
) -> Option<i64> {
    let rate_denominator = BPS_SCALE.checked_add(i128::from(cost_of_equity_bps))?;
    let mut discounted_earnings =
        mul_div_half_up(i128::from(eps_mean_cents), BPS_SCALE, rate_denominator)?;
    let mut present_value = discounted_earnings;
    for _ in 0..hold_years {
        discounted_earnings =
            grow_discounted(discounted_earnings, near_growth_bps, rate_denominator)?;
        present_value = present_value.checked_add(discounted_earnings)?;
    }
    for fade_step in 1..=fade_years {
        let growth_delta = i128::from(stable_growth_bps) - i128::from(near_growth_bps);
        let faded_delta = signed_div_half_up(
            growth_delta.checked_mul(i128::from(fade_step))?,
            i128::from(fade_years),
        )?;
        let growth = i128::from(near_growth_bps).checked_add(faded_delta)?;
        let growth: i32 = growth.try_into().ok()?;
        discounted_earnings = grow_discounted(discounted_earnings, growth, rate_denominator)?;
        present_value = present_value.checked_add(discounted_earnings)?;
    }
    let distributable = mul_div_half_up(
        discounted_earnings,
        i128::from(terminal_payout_bps),
        BPS_SCALE,
    )?;
    let terminal = mul_div_half_up(
        distributable,
        BPS_SCALE.checked_add(i128::from(stable_growth_bps))?,
        i128::from(cost_of_equity_bps).checked_sub(i128::from(stable_growth_bps))?,
    )?;
    i64::try_from(present_value.checked_add(terminal)?).ok()
}

fn grow_discounted(value: i128, growth_bps: i32, rate_denominator: i128) -> Option<i128> {
    mul_div_half_up(
        value,
        BPS_SCALE.checked_add(i128::from(growth_bps))?,
        rate_denominator,
    )
}

fn mul_div_half_up(value: i128, multiplier: i128, denominator: i128) -> Option<i128> {
    if value < 0 || multiplier < 0 || denominator <= 0 {
        return None;
    }
    value
        .checked_mul(multiplier)?
        .checked_add(denominator / 2)?
        .checked_div(denominator)
}

fn signed_div_half_up(numerator: i128, denominator: i128) -> Option<i128> {
    if denominator <= 0 {
        return None;
    }
    if numerator >= 0 {
        numerator
            .checked_add(denominator / 2)?
            .checked_div(denominator)
    } else {
        numerator
            .checked_abs()?
            .checked_add(denominator / 2)?
            .checked_div(denominator)
            .and_then(|value| value.checked_neg())
    }
}

fn candidate_value(status: CandidateStatus, value: Option<i64>) -> Option<i64> {
    match (status, value) {
        (CandidateStatus::Available, Some(value)) if value > 0 => Some(value),
        _ => None,
    }
}

fn valid_forward_candidate(candidate: &ForwardEarningsCandidate) -> bool {
    candidate == &value_forward_earnings(&candidate.provenance)
}

fn valid_fcff_candidate(candidate: &FcffCandidate) -> bool {
    !candidate.fingerprint.trim().is_empty()
        && match candidate.status {
            CandidateStatus::Available => {
                candidate
                    .intrinsic_value_cents
                    .is_some_and(|value| value > 0)
                    && candidate.refusal_codes.is_empty()
            }
            CandidateStatus::Unavailable => {
                candidate.intrinsic_value_cents.is_none() && !candidate.refusal_codes.is_empty()
            }
        }
}

fn difference_bps(left: i64, right: i64) -> Option<i64> {
    if left <= 0 || right <= 0 {
        return None;
    }
    let denominator = i128::from(left).checked_add(i128::from(right))?;
    let numerator = (i128::from(left) - i128::from(right))
        .abs()
        .checked_mul(20_000)?;
    i64::try_from((numerator.checked_add(denominator / 2)?) / denominator).ok()
}

fn fingerprint_part(value: &str) -> String {
    format!("{}:{value}", value.len())
}

fn forward_fingerprint(input: &ForwardEarningsInput, stable_growth_bps: Option<i32>) -> String {
    format!(
        "{ENGINE_VERSION}|policy={}|expected_currency={}|max_age={}|forecast_window={}/{}|min_coverage={}|projection={}/{}/{}|macro_growth={}|rf={}|rf_buffer={}|terminal_spread={}|forecast={}|rate={}|rate_bps={}|beta_source={}|provisional={}|market_asof={}|asof={}|observed={}|period_end={}|currency={}|eps={}/{}/{}|coverage={}|growth={}|stable={}|roic={}|terminal_payout={}",
        fingerprint_part(&input.policy.version),
        fingerprint_part(&input.policy.expected_currency),
        input.policy.max_age_days,
        input.policy.min_forecast_horizon_days,
        input.policy.max_forecast_horizon_days,
        input.policy.min_analyst_count,
        input.policy.hold_years,
        input.policy.fade_years,
        input.policy.max_projection_years,
        input.policy.macro_stable_growth_bps,
        input.policy.risk_free_rate_bps,
        input.policy.risk_free_buffer_bps,
        input.policy.minimum_terminal_spread_bps,
        fingerprint_part(&input.forecast.source_fingerprint),
        fingerprint_part(&input.cost_of_equity.source_fingerprint),
        input.cost_of_equity.cost_of_equity_bps,
        input.cost_of_equity.beta_source.as_str(),
        input.cost_of_equity.provisional,
        input.cost_of_equity.market_params_as_of_epoch.map_or_else(|| "none".into(), |value| value.to_string()),
        input.as_of_epoch_day,
        input.forecast.observed_epoch_day,
        input.forecast.forecast_period_end_epoch_day,
        fingerprint_part(&input.forecast.currency),
        optional_i64(input.forecast.eps_low_cents),
        optional_i64(input.forecast.eps_mean_cents),
        optional_i64(input.forecast.eps_high_cents),
        input.forecast.analyst_count.map_or_else(|| "none".into(), |value| value.to_string()),
        input.forecast.near_growth_bps,
        stable_growth_bps.map_or_else(|| "none".into(), |value| value.to_string()),
        input.return_on_capital_bps.map_or_else(|| "none".into(), |value| value.to_string()),
        stable_growth_bps.map_or_else(|| "none".into(), |stable| {
            terminal_payout_bps(
                input.return_on_capital_bps,
                input.cost_of_equity.cost_of_equity_bps,
                stable,
            )
            .to_string()
        }),
    )
}

fn optional_i64(value: Option<i64>) -> String {
    value.map_or_else(|| "none".into(), |value| value.to_string())
}

fn route_fingerprint(
    business_class: BusinessClass,
    status: RouteStatus,
    selected_model: Option<OperatingModel>,
    fcff: &FcffCandidate,
    forward: &ForwardEarningsCandidate,
    structural_distortions: &[StructuralDistortion],
    reasons: &[RouteReason],
) -> String {
    let structural = structural_distortions
        .iter()
        .map(|value| format!("{value:?}").to_ascii_lowercase())
        .collect::<Vec<_>>()
        .join(",");
    let reasons = reasons
        .iter()
        .map(|value| format!("{value:?}").to_ascii_lowercase())
        .collect::<Vec<_>>()
        .join(",");
    let fcff_refusals = fcff
        .refusal_codes
        .iter()
        .map(|value| fingerprint_part(value))
        .collect::<Vec<_>>()
        .join(",");
    let forward_refusals = forward
        .refusals
        .iter()
        .map(|value| format!("{value:?}").to_ascii_lowercase())
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "{ROUTER_POLICY_VERSION}|class={}|status={}|selected={}|fcff={}/{}/{}/{}/{}|forward={}/{}/{}/{}/{}/{}/{}|structural={structural}|reasons={reasons}",
        format!("{business_class:?}").to_ascii_lowercase(),
        format!("{status:?}").to_ascii_lowercase(),
        selected_model.map_or_else(|| "none".into(), |value| format!("{value:?}").to_ascii_lowercase()),
        format!("{:?}", fcff.status).to_ascii_lowercase(),
        optional_i64(fcff.intrinsic_value_cents),
        format!("{:?}", fcff.quality).to_ascii_lowercase(),
        fcff_refusals,
        fingerprint_part(&fcff.fingerprint),
        format!("{:?}", forward.status).to_ascii_lowercase(),
        optional_i64(forward.intrinsic_value_cents),
        format!("{:?}", forward.quality).to_ascii_lowercase(),
        format!("{:?}", forward.evidence_family).to_ascii_lowercase(),
        forward_refusals,
        forward.projection_years.map_or_else(|| "none".into(), |value| value.to_string()),
        fingerprint_part(&forward.fingerprint),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct SharedContract {
        executable_fixtures: Vec<SharedFixture>,
        executable_synthetic_cases: Vec<SyntheticCase>,
        router_golden_cases: Vec<RouterGoldenCase>,
        arithmetic_golden_cases: Vec<ArithmeticGoldenCase>,
        validation_cohorts: ValidationCohorts,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct SyntheticCase {
        name: String,
        mutation: String,
        business_class: BusinessClass,
        structural_distortions: Vec<StructuralDistortion>,
        expected_candidate_status: CandidateStatus,
        expected_refusals: Vec<CandidateRefusal>,
        expected_route_status: RouteStatus,
        expected_selected_model: Option<OperatingModel>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct RouterGoldenCase {
        name: String,
        forward_value_cents: i64,
        fcff_value_cents: i64,
        structural_distortions: Vec<StructuralDistortion>,
        expected: SharedRouteExpected,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ArithmeticGoldenCase {
        name: String,
        near_growth_bps: i32,
        hold_years: i32,
        fade_years: i32,
        expected_intrinsic_value_cents: i64,
        expected_fingerprint: String,
    }

    #[derive(Deserialize)]
    struct ValidationCohorts {
        reported: Vec<ValidationRow>,
        holdout: Vec<ValidationRow>,
    }

    #[derive(Deserialize)]
    struct ValidationRow {
        symbol: String,
        #[serde(rename = "businessClass")]
        business_class: BusinessClass,
        #[serde(rename = "epsLowCents")]
        eps_low_cents: i64,
        #[serde(rename = "epsMeanCents")]
        eps_mean_cents: i64,
        #[serde(rename = "epsHighCents")]
        eps_high_cents: i64,
        #[serde(rename = "analystCount")]
        analyst_count: i32,
        #[serde(rename = "forecastEndEpochDay")]
        forecast_end_epoch_day: i64,
        #[serde(rename = "nearGrowthBps")]
        near_growth_bps: i32,
        #[serde(rename = "resolvedCostOfEquityBps")]
        resolved_cost_of_equity_bps: i32,
        #[serde(rename = "holdYears")]
        hold_years: u32,
        /// Frozen unlevered return on capital. `None` is an explicit resolution,
        /// not a default: `returnOnCapitalAbsentReason` records which of the three
        /// no-evidence cases the row is (GDDY, WYNN, BSX, ALB).
        #[serde(rename = "returnOnCapitalBps")]
        return_on_capital_bps: Option<i32>,
        #[serde(default, rename = "returnOnCapitalAbsentReason")]
        return_on_capital_absent_reason: Option<String>,
        #[serde(rename = "fcffValidationOnlyCents")]
        fcff_validation_only_cents: Option<i64>,
        #[serde(default, rename = "routedPocValidationOnlyCents")]
        routed_poc_validation_only_cents: Option<i64>,
        #[serde(rename = "routeEvidence")]
        route_evidence: Vec<String>,
        #[serde(rename = "validationOnly")]
        validation_only: serde_json::Value,
    }

    #[derive(Deserialize)]
    struct SharedFixture {
        input: SharedFixtureInput,
        expected: SharedFixtureExpected,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct SharedFixtureInput {
        as_of_epoch_day: i64,
        forecast: ForwardForecast,
        cost_of_equity: ResolvedCostOfEquity,
        policy: ProjectionPolicy,
        route: SharedRouteInput,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct SharedRouteInput {
        business_class: BusinessClass,
        fcff_candidate: FcffCandidate,
        structural_distortions: Vec<StructuralDistortion>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct SharedFixtureExpected {
        forward_candidate: ForwardEarningsCandidate,
        route_decision: SharedRouteExpected,
    }

    #[derive(Debug, Deserialize, PartialEq, Eq)]
    #[serde(rename_all = "camelCase")]
    struct SharedRouteExpected {
        status: RouteStatus,
        selected_model: Option<OperatingModel>,
        selected_value_cents: Option<i64>,
        candidate_difference_bps: Option<i64>,
        reasons: Vec<RouteReason>,
        structural_distortions: Vec<StructuralDistortion>,
        fingerprint: String,
    }

    fn input() -> ForwardEarningsInput {
        ForwardEarningsInput {
            return_on_capital_bps: None,
            as_of_epoch_day: 20_000,
            forecast: ForwardForecast {
                eps_low_cents: Some(900),
                eps_mean_cents: Some(1_000),
                eps_high_cents: Some(1_100),
                analyst_count: Some(8),
                near_growth_bps: 600,
                currency: "USD".into(),
                observed_epoch_day: 19_990,
                forecast_period_end_epoch_day: 20_200,
                source_fingerprint: "forecast:test".into(),
            },
            cost_of_equity: ResolvedCostOfEquity {
                cost_of_equity_bps: 900,
                beta_source: crate::dcf_model::WaccFieldSource::IndustryShrink,
                provisional: false,
                market_params_as_of_epoch: Some(1_728_000_000),
                source_fingerprint: "rate:test".into(),
                ..Default::default()
            },
            policy: ProjectionPolicy {
                version: "forward-earnings-policy/1".into(),
                expected_currency: "USD".into(),
                max_age_days: 90,
                min_forecast_horizon_days: 180,
                max_forecast_horizon_days: 730,
                min_analyst_count: 3,
                hold_years: 2,
                fade_years: 4,
                max_projection_years: 20,
                macro_stable_growth_bps: 300,
                risk_free_rate_bps: 430,
                risk_free_buffer_bps: 100,
                minimum_terminal_spread_bps: 100,
            },
        }
    }

    fn fcff(value: Option<i64>) -> FcffCandidate {
        FcffCandidate {
            status: if value.is_some() {
                CandidateStatus::Available
            } else {
                CandidateStatus::Unavailable
            },
            intrinsic_value_cents: value,
            quality: ModelQuality::Solid,
            refusal_codes: if value.is_some() {
                vec![]
            } else {
                vec!["missing_fcff".into()]
            },
            fingerprint: "fcff:test".into(),
        }
    }

    #[test]
    fn forward_candidate_uses_fixed_point_recurrence() {
        let candidate = value_forward_earnings(&input());
        assert_eq!(candidate.status, CandidateStatus::Available);
        assert_eq!(candidate.intrinsic_value_cents, Some(14_056));
        assert_eq!(candidate.stable_growth_bps, Some(300));
        assert_eq!(
            candidate.quality,
            ModelQuality::Solid,
            "non-provisional CoE earns solid forward quality"
        );
        assert_eq!(
            candidate.evidence_family,
            EvidenceFamily::AnalystDerivedModel
        );
    }

    #[test]
    fn structural_distortion_selects_forward_and_exposes_disagreement() {
        let forward = value_forward_earnings(&input());
        let decision = route_operating_models(OperatingRouteInput {
            business_class: BusinessClass::OperatingNonFinancial,
            fcff_candidate: fcff(Some(5_000)),
            forward_candidate: forward,
            structural_distortions: vec![StructuralDistortion::LatestCapexSpike],
        });
        assert_eq!(decision.status, RouteStatus::Disputed);
        assert_eq!(
            decision.selected_model,
            Some(OperatingModel::ForwardEarningsPower)
        );
        assert_eq!(
            decision.selected_value_cents,
            decision.forward_candidate.intrinsic_value_cents
        );
        assert!(decision
            .reasons
            .contains(&RouteReason::CandidateDisagreement));
        assert!(decision
            .reasons
            .contains(&RouteReason::DisagreementResolvedToForwardEvidence));
    }

    #[test]
    fn representative_trailing_cash_retains_fcff_primary() {
        let decision = route_operating_models(OperatingRouteInput {
            business_class: BusinessClass::OperatingNonFinancial,
            fcff_candidate: fcff(Some(20_000)),
            forward_candidate: value_forward_earnings(&input()),
            structural_distortions: vec![],
        });
        assert_eq!(decision.status, RouteStatus::Selected);
        assert_eq!(decision.selected_model, Some(OperatingModel::FcffWacc));
        assert_eq!(decision.selected_value_cents, Some(20_000));
    }

    #[test]
    fn stale_forward_cannot_displace_usable_fcff() {
        let mut stale = input();
        stale.forecast.observed_epoch_day = 19_000;
        let forward = value_forward_earnings(&stale);
        assert_eq!(forward.refusals, vec![CandidateRefusal::StaleForecast]);
        let decision = route_operating_models(OperatingRouteInput {
            business_class: BusinessClass::OperatingNonFinancial,
            fcff_candidate: fcff(Some(20_000)),
            forward_candidate: forward,
            structural_distortions: vec![StructuralDistortion::SourceDiscontinuity],
        });
        assert_eq!(decision.selected_model, Some(OperatingModel::FcffWacc));
    }

    #[test]
    fn all_non_operating_families_fail_closed() {
        for (class, expected_status, expected_reason) in [
            (
                BusinessClass::FinancialServices,
                RouteStatus::Unavailable,
                RouteReason::FamilyFinancialServices,
            ),
            (
                BusinessClass::NotEligible,
                RouteStatus::NotEligible,
                RouteReason::FamilyNotEligible,
            ),
            (
                BusinessClass::Unclassified,
                RouteStatus::Unavailable,
                RouteReason::FamilyUnclassified,
            ),
        ] {
            let decision = route_operating_models(OperatingRouteInput {
                business_class: class,
                fcff_candidate: fcff(Some(20_000)),
                forward_candidate: value_forward_earnings(&input()),
                structural_distortions: vec![StructuralDistortion::LatestCapexSpike],
            });
            assert_eq!(decision.status, expected_status);
            assert_eq!(decision.selected_model, None);
            assert_eq!(decision.selected_value_cents, None);
            assert_eq!(decision.reasons, vec![expected_reason]);
        }
    }

    #[test]
    fn invalid_economics_refuse_instead_of_clamping() {
        let mut invalid = input();
        invalid.policy.minimum_terminal_spread_bps = 0;
        let candidate = value_forward_earnings(&invalid);
        assert_eq!(candidate.status, CandidateStatus::Unavailable);
        assert_eq!(candidate.intrinsic_value_cents, None);
        assert_eq!(candidate.refusals, vec![CandidateRefusal::InvalidPolicy]);
    }

    #[test]
    fn refusal_and_route_reasons_are_canonical() {
        let mut sparse = input();
        sparse.forecast.eps_low_cents = None;
        sparse.forecast.analyst_count = Some(1);
        sparse.forecast.currency = "EUR".into();
        assert_eq!(
            value_forward_earnings(&sparse).refusals,
            vec![
                CandidateRefusal::MissingForwardEps,
                CandidateRefusal::SparseCoverage,
                CandidateRefusal::CurrencyMismatch,
            ]
        );
    }

    #[test]
    fn policy_provenance_window_and_structural_work_limits_fail_closed() {
        let mut missing_provenance = input();
        missing_provenance.forecast.source_fingerprint.clear();
        assert!(value_forward_earnings(&missing_provenance)
            .refusals
            .contains(&CandidateRefusal::MissingSourceFingerprint));

        let mut distant = input();
        distant.forecast.forecast_period_end_epoch_day = 21_000;
        assert!(value_forward_earnings(&distant)
            .refusals
            .contains(&CandidateRefusal::InvalidForecastPeriod));

        let mut unbounded = input();
        unbounded.policy.hold_years = 100;
        unbounded.policy.fade_years = 1;
        unbounded.policy.max_projection_years = i32::MAX;
        assert!(value_forward_earnings(&unbounded)
            .refusals
            .contains(&CandidateRefusal::InvalidProjectionHorizon));

        let mut zero_minimum = input();
        zero_minimum.policy.min_analyst_count = 0;
        assert!(value_forward_earnings(&zero_minimum)
            .refusals
            .contains(&CandidateRefusal::InvalidPolicy));
    }

    #[test]
    fn gordon_headroom_is_part_of_effective_stable_growth() {
        let mut narrow = input();
        narrow.cost_of_equity.cost_of_equity_bps = 301;
        let candidate = value_forward_earnings(&narrow);
        assert_eq!(candidate.stable_growth_bps, Some(201));
        assert_eq!(candidate.status, CandidateStatus::Available);
    }

    #[test]
    fn router_rejects_contradictory_candidates_and_fingerprints_material_inputs() {
        let base = input();
        let candidate = value_forward_earnings(&base);
        for mutated in [
            {
                let mut value = base.clone();
                value.forecast.forecast_period_end_epoch_day += 1;
                value
            },
            {
                let mut value = base.clone();
                value.cost_of_equity.cost_of_equity_bps += 1;
                value
            },
            {
                let mut value = base.clone();
                value.cost_of_equity.provisional = true;
                value
            },
            {
                let mut value = base.clone();
                value.policy.max_age_days += 1;
                value
            },
        ] {
            assert_ne!(
                candidate.fingerprint,
                value_forward_earnings(&mutated).fingerprint
            );
        }

        let mut contradictory = candidate;
        contradictory.refusals = vec![CandidateRefusal::MissingCoverage];
        let decision = route_operating_models(OperatingRouteInput {
            business_class: BusinessClass::OperatingNonFinancial,
            fcff_candidate: fcff(Some(20_000)),
            forward_candidate: contradictory,
            structural_distortions: vec![StructuralDistortion::LatestCapexSpike],
        });
        assert_eq!(decision.selected_model, Some(OperatingModel::FcffWacc));
        assert!(decision
            .reasons
            .contains(&RouteReason::InvalidForwardCandidate));
    }

    #[test]
    fn validation_anchor_mutation_is_bit_identical() {
        let raw = std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../shared/contracts/operating-valuation-router-v1.json"
        ))
        .expect("read shared contract");
        let mut contract: serde_json::Value = serde_json::from_str(&raw).expect("parse contract");
        let before =
            serde_json::to_vec(&value_forward_earnings(&input())).expect("serialize candidate");
        contract["validationCohorts"]["reported"][0]["validationOnly"]["analystTargetCents"] =
            serde_json::json!(i64::MAX);
        contract["validationCohorts"]["reported"][0]["validationOnly"]["marketPriceCents"] =
            serde_json::json!(1);
        let _mutated_contract = serde_json::to_vec(&contract).expect("serialize mutated anchors");
        let after =
            serde_json::to_vec(&value_forward_earnings(&input())).expect("serialize candidate");
        assert_eq!(before, after);
    }

    fn distortions_from_tokens(tokens: &[String]) -> Vec<StructuralDistortion> {
        tokens
            .iter()
            .filter_map(|reason| match reason.as_str() {
                "trailing_unavailable" => Some(StructuralDistortion::TrailingCashUnavailable),
                "through_cycle_required" => Some(StructuralDistortion::ThroughCycleRequired),
                "extreme_leverage" => Some(StructuralDistortion::ExtremeLeverage),
                "stale_sec_period" => Some(StructuralDistortion::SourceDiscontinuity),
                "thin_normalized_fcff_margin" => {
                    Some(StructuralDistortion::ThinNormalizedFcffMargin)
                }
                "latest_capex_spike" => Some(StructuralDistortion::LatestCapexSpike),
                "acquisition_discontinuity" => Some(StructuralDistortion::AcquisitionDiscontinuity),
                "durable_excess_return_evidence" => {
                    Some(StructuralDistortion::DurableExcessReturnEvidence)
                }
                _ => None,
            })
            .collect()
    }

    fn durable_row_decision(
        row: &ValidationRow,
    ) -> (ForwardEarningsCandidate, OperatingRouteDecision) {
        let forward = value_forward_earnings(&ForwardEarningsInput {
            // Frozen rows carry no FCFF diagnostics, so this is the unlevered-ROE
            // branch of the production resolver, not the through-cycle one.
            return_on_capital_bps: row.return_on_capital_bps,
            as_of_epoch_day: 20_665,
            forecast: ForwardForecast {
                eps_low_cents: Some(row.eps_low_cents),
                eps_mean_cents: Some(row.eps_mean_cents),
                eps_high_cents: Some(row.eps_high_cents),
                analyst_count: Some(row.analyst_count),
                near_growth_bps: row.near_growth_bps,
                currency: "USD".into(),
                observed_epoch_day: 20_665,
                forecast_period_end_epoch_day: row.forecast_end_epoch_day,
                source_fingerprint: format!("frozen-yahoo:{}:2026-07-31", row.symbol),
            },
            cost_of_equity: ResolvedCostOfEquity {
                cost_of_equity_bps: row.resolved_cost_of_equity_bps,
                beta_source: crate::dcf_model::WaccFieldSource::IndustryShrink,
                // Durable cohort rows use market-sourced CoE semantics (solid when
                // non-provisional). Soft rates must not be the default for these pins.
                provisional: false,
                market_params_as_of_epoch: Some(1_728_000_000),
                source_fingerprint: format!("poc5-resolved-rate:{}", row.symbol),
                ..Default::default()
            },
            policy: ProjectionPolicy {
                version: "forward-earnings-policy/1-poc".into(),
                expected_currency: "USD".into(),
                max_age_days: 90,
                min_forecast_horizon_days: 180,
                max_forecast_horizon_days: 730,
                min_analyst_count: 3,
                hold_years: i32::try_from(row.hold_years).expect("hold years fit i32"),
                fade_years: 10,
                max_projection_years: 25,
                macro_stable_growth_bps: 300,
                risk_free_rate_bps: 400,
                risk_free_buffer_bps: 100,
                minimum_terminal_spread_bps: 100,
            },
        });
        let fcff_value = row.fcff_validation_only_cents.filter(|value| *value > 0);
        let decision = route_operating_models(OperatingRouteInput {
            business_class: row.business_class,
            fcff_candidate: FcffCandidate {
                status: if fcff_value.is_some() {
                    CandidateStatus::Available
                } else {
                    CandidateStatus::Unavailable
                },
                intrinsic_value_cents: fcff_value,
                quality: ModelQuality::Solid,
                refusal_codes: if fcff_value.is_some() {
                    vec![]
                } else {
                    vec!["trailing_unavailable".into()]
                },
                fingerprint: format!("frozen-fcff:{}", row.symbol),
            },
            forward_candidate: forward.clone(),
            structural_distortions: distortions_from_tokens(&row.route_evidence),
        });
        (forward, decision)
    }

    #[test]
    fn durable_reported_and_holdout_cohorts_recompute_in_normal_gate() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../shared/contracts/operating-valuation-router-v1.json"
        );
        let contract: SharedContract =
            serde_json::from_str(&std::fs::read_to_string(path).expect("read contract"))
                .expect("parse contract");
        let expected = [
            ("DVN", Some(5_018)),
            ("GDDY", Some(11_155)),
            ("WYNN", Some(8_631)),
            ("SNDK", Some(207_006)),
            ("BR", Some(20_858)),
            ("BSX", Some(5_245)),
            ("AMZN", Some(23_092)),
            ("AVGO", Some(43_101)),
            ("HPE", Some(5_620)),
            ("MU", Some(158_235)),
            ("ORCL", Some(20_778)),
            ("AAPL", Some(27_823)),
            ("CPRT", Some(3_178)),
            ("CEG", Some(27_689)),
            ("ALB", Some(15_492)),
            ("T", Some(2_749)),
            ("MSFT", Some(57_583)),
            ("NVDA", Some(27_522)),
            ("JNJ", Some(24_928)),
            ("XOM", Some(11_695)),
            ("V", None),
            ("WMT", Some(9_909)),
            ("GOOGL", Some(39_040)),
            ("META", Some(86_464)),
            ("HD", Some(31_356)),
            ("PG", Some(17_400)),
            ("MRK", Some(14_303)),
        ];
        let rows = contract
            .validation_cohorts
            .reported
            .iter()
            .chain(contract.validation_cohorts.holdout.iter());
        let mut reported_errors = Vec::new();
        let mut holdout_errors = Vec::new();
        for (index, (row, (symbol, expected_value))) in rows.zip(expected).enumerate() {
            assert_eq!(row.symbol, symbol);
            let (forward, decision) = durable_row_decision(row);
            let diagnostic = if decision.status == RouteStatus::Disputed {
                forward.intrinsic_value_cents
            } else {
                decision.selected_value_cents
            };
            assert_eq!(diagnostic, expected_value, "{}", row.symbol);
            let target = row.validation_only["analystTargetCents"]
                .as_i64()
                .expect("target anchor");
            if let Some(value) = diagnostic {
                let error = (value - target).abs() as f64 / target as f64 * 100.0;
                if index < 15 {
                    reported_errors.push(error);
                } else {
                    holdout_errors.push(error);
                }
            }
        }
        assert_eq!(reported_errors.len(), 15);
        assert!(reported_errors.iter().sum::<f64>() / 15.0 < 11.0);
        assert!(reported_errors.iter().copied().fold(0.0, f64::max) < 24.0);
        assert_eq!(holdout_errors.len(), 11);
        assert!(holdout_errors.iter().sum::<f64>() / 11.0 < 11.5);
        assert!(holdout_errors.iter().copied().fold(0.0, f64::max) < 21.0);
    }

    /// Every cohort row states its return on capital, and the four rows that have
    /// none say *which* no-evidence case they are. A silent `null` would be
    /// indistinguishable from an unwired field, and the value-neutral prior it
    /// triggers is a real 25-35% haircut on the terminal — too consequential to
    /// reach by omission.
    #[test]
    fn every_cohort_row_resolves_return_on_capital_explicitly() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../shared/contracts/operating-valuation-router-v1.json"
        );
        let contract: SharedContract =
            serde_json::from_str(&std::fs::read_to_string(path).expect("read contract"))
                .expect("parse contract");
        let mut violations: Vec<String> = contract
            .validation_cohorts
            .reported
            .iter()
            .chain(contract.validation_cohorts.holdout.iter())
            .filter_map(|row| {
                match (
                    row.return_on_capital_bps,
                    row.return_on_capital_absent_reason.as_deref(),
                ) {
                    (Some(value), None) if value > 0 => None,
                    (None, Some(_)) => None,
                    other => Some(format!("{}: {other:?}", row.symbol)),
                }
            })
            .collect();
        violations.sort();
        assert_eq!(violations, Vec::<String>::new());
    }

    #[test]
    fn shared_contract_matches_exactly_and_keeps_validation_anchors_external() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../shared/contracts/operating-valuation-router-v1.json"
        );
        let contract: SharedContract = serde_json::from_str(
            &std::fs::read_to_string(path).expect("read operating valuation contract"),
        )
        .expect("parse operating valuation contract");
        assert_eq!(contract.validation_cohorts.reported.len(), 15);
        assert_eq!(contract.validation_cohorts.holdout.len(), 12);
        assert_eq!(
            contract
                .validation_cohorts
                .reported
                .iter()
                .chain(contract.validation_cohorts.holdout.iter())
                .map(|row| row.symbol.as_str())
                .collect::<std::collections::BTreeSet<_>>()
                .len(),
            27
        );
        assert!(contract
            .validation_cohorts
            .reported
            .iter()
            .chain(contract.validation_cohorts.holdout.iter())
            .all(|row| row.validation_only.get("analystTargetCents").is_some()));

        for fixture in contract.executable_fixtures {
            let forward_input = ForwardEarningsInput {
                return_on_capital_bps: None,
                as_of_epoch_day: fixture.input.as_of_epoch_day,
                forecast: fixture.input.forecast,
                cost_of_equity: fixture.input.cost_of_equity,
                policy: fixture.input.policy,
            };
            let forward = value_forward_earnings(&forward_input);
            assert_eq!(forward, fixture.expected.forward_candidate);
            let route = route_operating_models(OperatingRouteInput {
                business_class: fixture.input.route.business_class,
                fcff_candidate: fixture.input.route.fcff_candidate,
                forward_candidate: forward,
                structural_distortions: fixture.input.route.structural_distortions,
            });
            assert_eq!(
                SharedRouteExpected {
                    status: route.status,
                    selected_model: route.selected_model,
                    selected_value_cents: route.selected_value_cents,
                    candidate_difference_bps: route.candidate_difference_bps,
                    reasons: route.reasons,
                    structural_distortions: route.structural_distortions,
                    fingerprint: route.fingerprint,
                },
                fixture.expected.route_decision
            );
        }

        for case in contract.router_golden_cases {
            let forward = value_forward_earnings(&input());
            assert_eq!(
                forward.intrinsic_value_cents,
                Some(case.forward_value_cents)
            );
            let route = route_operating_models(OperatingRouteInput {
                business_class: BusinessClass::OperatingNonFinancial,
                fcff_candidate: fcff(Some(case.fcff_value_cents)),
                forward_candidate: forward,
                structural_distortions: case.structural_distortions,
            });
            assert_eq!(
                SharedRouteExpected {
                    status: route.status,
                    selected_model: route.selected_model,
                    selected_value_cents: route.selected_value_cents,
                    candidate_difference_bps: route.candidate_difference_bps,
                    reasons: route.reasons,
                    structural_distortions: route.structural_distortions,
                    fingerprint: route.fingerprint,
                },
                case.expected,
                "{}",
                case.name,
            );
        }

        for case in contract.arithmetic_golden_cases {
            let mut arithmetic_input = input();
            arithmetic_input.forecast.near_growth_bps = case.near_growth_bps;
            arithmetic_input.policy.hold_years = case.hold_years;
            arithmetic_input.policy.fade_years = case.fade_years;
            let candidate = value_forward_earnings(&arithmetic_input);
            assert_eq!(
                candidate.intrinsic_value_cents,
                Some(case.expected_intrinsic_value_cents),
                "{}",
                case.name
            );
            assert_eq!(
                candidate.fingerprint, case.expected_fingerprint,
                "{}",
                case.name
            );
        }

        assert_eq!(contract.executable_synthetic_cases.len(), 10);
        for case in contract.executable_synthetic_cases {
            let mut synthetic_input = input();
            match case.mutation.as_str() {
                "none" => {}
                "stale_forecast" => synthetic_input.forecast.observed_epoch_day = 19_000,
                "sparse_coverage" => synthetic_input.forecast.analyst_count = Some(1),
                "non_positive_eps" => {
                    synthetic_input.forecast.eps_low_cents = Some(0);
                    synthetic_input.forecast.eps_mean_cents = Some(0);
                    synthetic_input.forecast.eps_high_cents = Some(0);
                }
                "invalid_terminal_policy" => synthetic_input.policy.minimum_terminal_spread_bps = 0,
                "arithmetic_overflow" => {
                    synthetic_input.forecast.eps_low_cents = Some(i64::MAX);
                    synthetic_input.forecast.eps_mean_cents = Some(i64::MAX);
                    synthetic_input.forecast.eps_high_cents = Some(i64::MAX);
                    synthetic_input.forecast.near_growth_bps = i32::MAX;
                    synthetic_input.policy.hold_years = 10;
                    synthetic_input.policy.fade_years = 9;
                }
                "multiple_refusals" => {
                    synthetic_input.forecast.eps_low_cents = None;
                    synthetic_input.forecast.analyst_count = Some(1);
                    synthetic_input.forecast.currency = "EUR".into();
                }
                unknown => panic!("unknown synthetic mutation {unknown}"),
            }
            let candidate = value_forward_earnings(&synthetic_input);
            assert_eq!(
                candidate.status, case.expected_candidate_status,
                "{}",
                case.name
            );
            assert_eq!(candidate.refusals, case.expected_refusals, "{}", case.name);
            let decision = route_operating_models(OperatingRouteInput {
                business_class: case.business_class,
                fcff_candidate: fcff(Some(5_000)),
                forward_candidate: candidate,
                structural_distortions: case.structural_distortions,
            });
            assert_eq!(decision.status, case.expected_route_status, "{}", case.name);
            assert_eq!(
                decision.selected_model, case.expected_selected_model,
                "{}",
                case.name
            );
        }
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct PocCaptureRow {
        symbol: String,
        fundamentals: crate::engine::FundamentalSnapshot,
        earnings_trend: Vec<serde_json::Value>,
    }

    #[derive(Serialize)]
    #[serde(rename_all = "camelCase")]
    struct FixedPointPocRow {
        symbol: String,
        business_class: BusinessClass,
        forward_candidate_cents: Option<i64>,
        route_status: RouteStatus,
        selected_model: Option<OperatingModel>,
        selected_value_cents: Option<i64>,
        diagnostic_route_value_cents: Option<i64>,
        route_reasons: Vec<RouteReason>,
        candidate_refusals: Vec<CandidateRefusal>,
        float_poc_validation_only_cents: Option<i64>,
        analyst_target_validation_only_cents: Option<i64>,
        absolute_validation_error_pct: Option<f64>,
    }

    #[test]
    #[ignore = "frozen Yahoo/SEC capture; headless fixed-point cohort PoC"]
    fn headless_fixed_point_operating_core_poc() {
        use chrono::NaiveDate;
        use std::collections::BTreeMap;

        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let contract_path = root.join("shared/contracts/operating-valuation-router-v1.json");
        let contract: SharedContract = serde_json::from_str(
            &std::fs::read_to_string(contract_path).expect("read operating contract"),
        )
        .expect("parse operating contract");
        let captures = [
            root.join(".agents/workspace/tmp/poc-current-engine.json"),
            root.join(".agents/workspace/tmp/poc-current-engine-holdout12.json"),
        ]
        .into_iter()
        .flat_map(|path| {
            serde_json::from_str::<Vec<PocCaptureRow>>(
                &std::fs::read_to_string(path).expect("read frozen capture"),
            )
            .expect("parse frozen capture")
        })
        .map(|row| (row.symbol.clone(), row))
        .collect::<BTreeMap<_, _>>();
        let epoch = NaiveDate::from_ymd_opt(1970, 1, 1).unwrap();
        let as_of = NaiveDate::from_ymd_opt(2026, 7, 31).unwrap();
        let as_of_epoch_day = (as_of - epoch).num_days();
        let mut output = Vec::new();

        for row in contract
            .validation_cohorts
            .reported
            .iter()
            .chain(contract.validation_cohorts.holdout.iter())
        {
            let capture = captures.get(&row.symbol).expect("capture by symbol");
            let trend = capture
                .earnings_trend
                .iter()
                .find(|trend| {
                    trend.get("period").and_then(serde_json::Value::as_str) == Some("+1y")
                })
                .expect("+1y trend");
            let raw = |pointer: &str| trend.pointer(pointer).and_then(serde_json::Value::as_f64);
            let eps_cents =
                |pointer: &str| raw(pointer).map(|value| (value * 100.0).round() as i64);
            let forecast_end = trend
                .get("endDate")
                .and_then(serde_json::Value::as_str)
                .and_then(|value| NaiveDate::parse_from_str(value, "%Y-%m-%d").ok())
                .expect("forecast end date");
            let currency = trend
                .pointer("/earningsEstimate/earningsCurrency")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("");
            let analyst_count = trend
                .pointer("/earningsEstimate/numberOfAnalysts/raw")
                .and_then(serde_json::Value::as_u64)
                .and_then(|value| i32::try_from(value).ok());
            let forward = value_forward_earnings(&ForwardEarningsInput {
                as_of_epoch_day,
                return_on_capital_bps: None,
                forecast: ForwardForecast {
                    eps_low_cents: eps_cents("/earningsEstimate/low/raw"),
                    eps_mean_cents: eps_cents("/earningsEstimate/avg/raw"),
                    eps_high_cents: eps_cents("/earningsEstimate/high/raw"),
                    analyst_count,
                    near_growth_bps: row.near_growth_bps,
                    currency: currency.into(),
                    observed_epoch_day: as_of_epoch_day,
                    forecast_period_end_epoch_day: (forecast_end - epoch).num_days(),
                    source_fingerprint: format!("frozen-yahoo:{}:2026-07-31", row.symbol),
                },
                cost_of_equity: ResolvedCostOfEquity {
                    cost_of_equity_bps: row.resolved_cost_of_equity_bps,
                    beta_source: crate::dcf_model::WaccFieldSource::IndustryShrink,
                    provisional: true,
                    market_params_as_of_epoch: None,
                    source_fingerprint: format!("poc5-resolved-rate:{}", row.symbol),
                    ..Default::default()
                },
                policy: ProjectionPolicy {
                    version: "forward-earnings-policy/1-poc".into(),
                    expected_currency: "USD".into(),
                    max_age_days: 90,
                    min_forecast_horizon_days: 180,
                    max_forecast_horizon_days: 730,
                    min_analyst_count: 3,
                    hold_years: i32::try_from(row.hold_years).expect("hold years fit i32"),
                    fade_years: 10,
                    max_projection_years: 25,
                    macro_stable_growth_bps: 300,
                    risk_free_rate_bps: 400,
                    risk_free_buffer_bps: 100,
                    minimum_terminal_spread_bps: 100,
                },
            });
            let business_class = crate::dcf_model::classify_business(
                capture.fundamentals.sector_name.as_deref(),
                capture.fundamentals.industry_name.as_deref(),
                capture.fundamentals.sector_key.as_deref(),
                capture.fundamentals.industry_key.as_deref(),
                false,
            );
            let structural_distortions = row
                .route_evidence
                .iter()
                .filter_map(|reason| match reason.as_str() {
                    "trailing_unavailable" => Some(StructuralDistortion::TrailingCashUnavailable),
                    "through_cycle_required" => Some(StructuralDistortion::ThroughCycleRequired),
                    "extreme_leverage" => Some(StructuralDistortion::ExtremeLeverage),
                    "stale_sec_period" => Some(StructuralDistortion::SourceDiscontinuity),
                    "thin_normalized_fcff_margin" => {
                        Some(StructuralDistortion::ThinNormalizedFcffMargin)
                    }
                    "latest_capex_spike" => Some(StructuralDistortion::LatestCapexSpike),
                    "acquisition_discontinuity" => {
                        Some(StructuralDistortion::AcquisitionDiscontinuity)
                    }
                    "durable_excess_return_evidence" => {
                        Some(StructuralDistortion::DurableExcessReturnEvidence)
                    }
                    _ => None,
                })
                .collect();
            let fcff_value = row.fcff_validation_only_cents.filter(|value| *value > 0);
            let decision = route_operating_models(OperatingRouteInput {
                business_class,
                fcff_candidate: FcffCandidate {
                    status: if fcff_value.is_some() {
                        CandidateStatus::Available
                    } else {
                        CandidateStatus::Unavailable
                    },
                    intrinsic_value_cents: fcff_value,
                    quality: ModelQuality::Solid,
                    refusal_codes: if fcff_value.is_some() {
                        vec![]
                    } else {
                        vec!["trailing_unavailable".into()]
                    },
                    fingerprint: format!("frozen-fcff:{}", row.symbol),
                },
                forward_candidate: forward.clone(),
                structural_distortions,
            });
            let target = row
                .validation_only
                .get("analystTargetCents")
                .and_then(serde_json::Value::as_i64);
            let diagnostic_route_value = match decision.status {
                RouteStatus::Disputed => forward.intrinsic_value_cents,
                _ => decision.selected_value_cents,
            };
            let error = diagnostic_route_value
                .zip(target)
                .map(|(value, target)| ((value - target).abs() as f64 / target as f64) * 100.0);
            output.push(FixedPointPocRow {
                symbol: row.symbol.clone(),
                business_class,
                forward_candidate_cents: forward.intrinsic_value_cents,
                route_status: decision.status,
                selected_model: decision.selected_model,
                selected_value_cents: decision.selected_value_cents,
                diagnostic_route_value_cents: diagnostic_route_value,
                route_reasons: decision.reasons,
                candidate_refusals: forward.refusals,
                float_poc_validation_only_cents: row.routed_poc_validation_only_cents,
                analyst_target_validation_only_cents: target,
                absolute_validation_error_pct: error,
            });
        }

        let output_path = std::env::var("DS_OPERATING_CORE_POC_OUTPUT")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(|_| {
                root.join(".agents/workspace/tmp/poc-fixed-point-operating-core.json")
            });
        std::fs::write(
            output_path,
            serde_json::to_string_pretty(&output).expect("serialize fixed-point PoC"),
        )
        .expect("write fixed-point PoC");
        assert_eq!(output.len(), 27);
        let reported_errors = output[..15]
            .iter()
            .filter_map(|row| row.absolute_validation_error_pct)
            .collect::<Vec<_>>();
        assert_eq!(reported_errors.len(), 15);
        assert!(reported_errors.iter().sum::<f64>() / 15.0 < 12.0);
        assert!(reported_errors.iter().copied().fold(0.0, f64::max) < 25.0);
        let holdout_errors = output[15..]
            .iter()
            .filter_map(|row| row.absolute_validation_error_pct)
            .collect::<Vec<_>>();
        assert_eq!(holdout_errors.len(), 11);
        assert!(holdout_errors.iter().sum::<f64>() / 11.0 < 12.0);
        assert!(holdout_errors.iter().copied().fold(0.0, f64::max) < 25.0);
    }
}
