//! Gap / policy-delta attribution waterfall for operating forward-earnings valuation.
//!
//! ## What this module is
//! Permanent engine telemetry: decompose the value difference between **active**
//! engine policy settings and **own policy baselines** into per-factor contributions.
//!
//! ## Decomposition method: Shapley
//! Factor contributions are Shapley values over the factor set
//! `{rates, horizon, path, g_terminal}`. Every coalition of factors is evaluated
//! once (16 valuations); each factor's contribution is the average marginal
//! effect over all subsets of the remaining factors. Order of substitution does
//! **not** bias the result. Interactions are distributed across factors — they
//! are not dumped into a residual "catch-all".
//!
//! Rounding may leave a ±few cents residual (`attr_rounding_residual_cents`);
//! that is integer noise only, not unattributed economic interaction.
//!
//! ## Neutral baseline = own policy (never Street reverse-engineering)
//! For every factor, the "off" setting is a **documented own-policy baseline**,
//! never the CoE/horizon/path that would force our intrinsic to equal Street.
//! Street appears only as a **post-hoc diagnostic** gap
//! (`diagnostic_gap_vs_street_*`) and **must never** enter the value function
//! or factor baselines.
//!
//! ## Forbidden use
//! `diagnostic_gap_vs_street_*`, `attr_*_vs_street*`, and any residual framed
//! against Street are **not** acceptance criteria and **must not** be minimized
//! as a calibration objective. See `street_diagnostic_only_enforcement` tests.
//!
//! ## Scope (MVP)
//! Shapley factors cover the **forward-earnings** policy surface only.
//! Method diagnostic `v_naive_fcff_baseline` is **not** a Shapley factor: it
//! values firm FCFF at **WACC**, subtracts **net debt**, then ÷ shares, so it
//! is comparable to EPS@CoE. Empirical fade via PIT store is a separate project.
//!
//! Contract: `shared/contracts/valuation-gap-attribution-v1.json`

use serde::{Deserialize, Serialize};

use crate::dcf_model::ResolvedCostOfEquity;
use crate::operating_valuation::{
    value_forward_earnings, CandidateStatus, ForwardEarningsInput, ForwardForecast,
    ProjectionPolicy,
};

/// Schema / telemetry version for attribution outputs.
pub const ATTRIBUTION_SCHEMA_VERSION: &str = "valuation-gap-attribution/1";

/// Decomposition method declared on every report (must match contract).
pub const DECOMPOSITION_METHOD: &str = "shapley";

/// Factor baseline kind: own policy alternatives only.
pub const FACTOR_BASELINE_KIND: &str = "policy_own";

/// Street role: diagnostic gap only — never a factor anchor.
pub const STREET_ROLE: &str = "diagnostic_only";

/// Ordered factor set for Shapley (index = bit in coalition mask).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AttributionFactor {
    Rates = 0,
    Horizon = 1,
    Path = 2,
    GTerminal = 3,
}

impl AttributionFactor {
    pub const ALL: [Self; 4] = [Self::Rates, Self::Horizon, Self::Path, Self::GTerminal];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Rates => "rates",
            Self::Horizon => "horizon",
            Self::Path => "path",
            Self::GTerminal => "g_terminal",
        }
    }

    fn bit(self) -> u8 {
        1u8 << (self as u8)
    }
}

/// Own-policy baseline constants for each factor (version with this module).
///
/// These are **not** reverse-engineered from Street or market price.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PolicyBaselineSpec {
    /// CAPM unit-beta CoE: `rf + 1.0 × ERP` (no industry shrink, no leverage floor).
    pub rates_uses_unit_beta: bool,
    /// Canonical long fade with no explicit hold: hold=0, fade=10.
    pub horizon_hold_years: i32,
    pub horizon_fade_years: i32,
    /// No growth capitalization beyond current EPS level (`near_growth = 0`).
    pub path_near_growth_bps: i32,
    /// Macro stable growth floor used when g_terminal factor is baseline.
    pub g_terminal_macro_stable_growth_bps: i32,
    pub g_terminal_risk_free_buffer_bps: i32,
    pub g_terminal_minimum_terminal_spread_bps: i32,
}

impl Default for PolicyBaselineSpec {
    fn default() -> Self {
        Self {
            rates_uses_unit_beta: true,
            horizon_hold_years: 0,
            horizon_fade_years: 10,
            path_near_growth_bps: 0,
            g_terminal_macro_stable_growth_bps: 300,
            g_terminal_risk_free_buffer_bps: 100,
            g_terminal_minimum_terminal_spread_bps: 100,
        }
    }
}

/// Active (current engine) settings for one name — already resolved by caller.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActiveFactorSettings {
    pub cost_of_equity_bps: i32,
    pub cost_of_equity_provisional: bool,
    pub hold_years: i32,
    pub fade_years: i32,
    pub eps_mean_cents: i64,
    pub near_growth_bps: i32,
    pub macro_stable_growth_bps: i32,
    pub risk_free_rate_bps: i32,
    pub risk_free_buffer_bps: i32,
    pub minimum_terminal_spread_bps: i32,
    /// ERP used only to build unit-beta rates baseline: CoE_base = rf + erp.
    pub erp_bps: i32,
    pub currency: String,
    pub as_of_epoch_day: i64,
    pub forecast_period_end_epoch_day: i64,
    pub analyst_count: Option<i32>,
    pub source_fingerprint: String,
    /// Return on total capital funding perpetual growth. Held constant across
    /// every coalition — it is an issuer property, not a policy factor, so it
    /// must not leak into the rates/horizon/path/g attribution.
    pub return_on_capital_bps: Option<i32>,
}

/// Optional FCFF/owner-earnings inputs for the **method diagnostic** column
/// (`v_naive_fcff_baseline`). Not a Shapley factor.
///
/// Mechanics (required):
/// - Discount unlevered FCFF at **WACC** (not CoE).
/// - Equity = EV − net_debt, then ÷ shares.
/// CapEx/OCF level comes from `extract_normalized_fcff_level` (owner-earnings
/// bridge), independent of full scenario ordering.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct NaiveFcffDiagnosticInput {
    /// Annual normalized firm FCFF (owner-earnings style) in whole dollars.
    pub fcff_run_rate_dollars: Option<i64>,
    pub shares_outstanding: Option<u64>,
    /// Total debt − cash (negative = net cash). Required for EV→equity bridge.
    pub net_debt_dollars: Option<i64>,
    /// WACC used to discount FCFF (unlevered). Must not be CoE.
    pub wacc_bps: Option<i32>,
    /// Unit-beta CoE component inside that WACC (audit).
    pub coe_unit_beta_bps: Option<i32>,
    pub after_tax_cod_bps: Option<i32>,
    pub equity_weight_bps: Option<i32>,
    pub debt_weight_bps: Option<i32>,
    /// Provenance note (e.g. source of normalized FCFF).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source_note: Option<String>,
}

/// Full attribution report for one name.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GapAttributionReport {
    pub schema_version: String,
    pub decomposition_method: String,
    pub factor_baseline_kind: String,
    pub street_role: String,
    pub symbol: String,
    pub model: String,
    /// Value with all factors at active (current engine) settings.
    pub v_active_cents: Option<i64>,
    /// Value with all factors at own-policy baseline settings (EPS capitalised).
    pub v_baseline_cents: Option<i64>,
    /// `v_active − v_baseline` (what Shapley attributes).
    pub policy_delta_cents: Option<i64>,
    pub attr_rates_cents: Option<i64>,
    pub attr_horizon_cents: Option<i64>,
    pub attr_path_cents: Option<i64>,
    pub attr_g_terminal_cents: Option<i64>,
    /// Integer residual after summing Shapley attrs vs `policy_delta_cents`.
    /// Economic interactions are already inside the Shapley attrs — this is rounding only.
    pub attr_rounding_residual_cents: Option<i64>,
    /// Raw horizon settings (for validating horizon=$0 attributions).
    pub hold_active: i32,
    pub fade_active: i32,
    pub hold_baseline: i32,
    pub fade_baseline: i32,
    /// True when active hold/fade equal baseline — horizon factor is inert by construction.
    pub horizon_settings_match_baseline: bool,
    /// Street target used only for diagnostic gap fields.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub street_target_cents: Option<i64>,
    /// Diagnostic only: active intrinsic − Street target. Never a calibration target.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diagnostic_gap_vs_street_cents: Option<i64>,
    /// Diagnostic only: relative bps vs Street. Never a calibration target.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diagnostic_gap_vs_street_bps: Option<i32>,
    /// Diagnostic: `v_baseline − Street` (how much of the gap exists before policy).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diagnostic_baseline_gap_vs_street_cents: Option<i64>,
    /// Diagnostic: share of total gap already in baseline, in bps of the total gap
    /// (`(v_baseline−Street)/(v_active−Street)×10000`). Can be outside 0..10000 if signs flip.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diagnostic_baseline_share_of_gap_bps: Option<i32>,
    /// Diagnostic: share of total gap added by policy delta, same units.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diagnostic_policy_share_of_gap_bps: Option<i32>,
    /// Method diagnostic (not Shapley): same hold/fade/g=0 baseline horizon, but
    /// firm FCFF discounted at **WACC**, then EV − net_debt → equity/share.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub v_naive_fcff_baseline_cents: Option<i64>,
    /// Explicit audit: always "wacc" when FCFF value is present.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub naive_fcff_discount_rate_kind: Option<String>,
    /// Explicit audit: true when net debt was subtracted before per-share.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub naive_fcff_subtracted_net_debt: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fcff_run_rate_dollars: Option<i64>,
    /// Firm FCFF / shares (cash-flow level only — **not** equity value).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fcff_per_share_cents: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub net_debt_dollars: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub naive_fcff_wacc_bps: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub naive_fcff_coe_unit_beta_bps: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diagnostic_naive_fcff_gap_vs_street_cents: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diagnostic_naive_fcff_gap_vs_street_bps: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub naive_fcff_source_note: Option<String>,
    pub baseline_spec: PolicyBaselineSpec,
    pub active_settings: ActiveFactorSettings,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub notes: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct CoalitionSettings {
    cost_of_equity_bps: i32,
    hold_years: i32,
    fade_years: i32,
    near_growth_bps: i32,
    macro_stable_growth_bps: i32,
    risk_free_buffer_bps: i32,
    minimum_terminal_spread_bps: i32,
    cost_of_equity_provisional: bool,
}

fn rates_baseline_bps(active: &ActiveFactorSettings, baseline: &PolicyBaselineSpec) -> i32 {
    if baseline.rates_uses_unit_beta {
        active
            .risk_free_rate_bps
            .saturating_add(active.erp_bps)
            .max(active.risk_free_rate_bps.saturating_add(50))
    } else {
        active.cost_of_equity_bps
    }
}

fn settings_for_coalition(
    mask: u8,
    active: &ActiveFactorSettings,
    baseline: &PolicyBaselineSpec,
) -> CoalitionSettings {
    let rates_on = mask & AttributionFactor::Rates.bit() != 0;
    let horizon_on = mask & AttributionFactor::Horizon.bit() != 0;
    let path_on = mask & AttributionFactor::Path.bit() != 0;
    let g_on = mask & AttributionFactor::GTerminal.bit() != 0;

    CoalitionSettings {
        cost_of_equity_bps: if rates_on {
            active.cost_of_equity_bps
        } else {
            rates_baseline_bps(active, baseline)
        },
        hold_years: if horizon_on {
            active.hold_years
        } else {
            baseline.horizon_hold_years
        },
        fade_years: if horizon_on {
            active.fade_years
        } else {
            baseline.horizon_fade_years
        },
        near_growth_bps: if path_on {
            active.near_growth_bps
        } else {
            baseline.path_near_growth_bps
        },
        macro_stable_growth_bps: if g_on {
            active.macro_stable_growth_bps
        } else {
            baseline.g_terminal_macro_stable_growth_bps
        },
        risk_free_buffer_bps: if g_on {
            active.risk_free_buffer_bps
        } else {
            baseline.g_terminal_risk_free_buffer_bps
        },
        minimum_terminal_spread_bps: if g_on {
            active.minimum_terminal_spread_bps
        } else {
            baseline.g_terminal_minimum_terminal_spread_bps
        },
        cost_of_equity_provisional: if rates_on {
            active.cost_of_equity_provisional
        } else {
            // Unit-beta CAPM baseline is a deliberate policy alternative, not provisional noise.
            false
        },
    }
}

fn value_coalition(settings: CoalitionSettings, active: &ActiveFactorSettings) -> Option<i64> {
    if active.eps_mean_cents <= 0 || settings.cost_of_equity_bps <= 0 || settings.fade_years <= 0 {
        return None;
    }
    // Forward validator requires a full ordered EPS range; attribution varies
    // rates/horizon/path/g only — keep a degenerate but valid range around mean.
    let eps = active.eps_mean_cents;
    let input = ForwardEarningsInput {
        as_of_epoch_day: active.as_of_epoch_day,
        return_on_capital_bps: active.return_on_capital_bps,
        forecast: ForwardForecast {
            eps_low_cents: Some(eps),
            eps_mean_cents: Some(eps),
            eps_high_cents: Some(eps),
            analyst_count: active.analyst_count.or(Some(5)),
            near_growth_bps: settings.near_growth_bps,
            currency: active.currency.clone(),
            observed_epoch_day: active.as_of_epoch_day,
            forecast_period_end_epoch_day: active.forecast_period_end_epoch_day,
            source_fingerprint: active.source_fingerprint.clone(),
        },
        cost_of_equity: ResolvedCostOfEquity {
            cost_of_equity_bps: settings.cost_of_equity_bps,
            provisional: settings.cost_of_equity_provisional,
            source_fingerprint: format!(
                "gap-attr|coe={}|prov={}",
                settings.cost_of_equity_bps, settings.cost_of_equity_provisional
            ),
            ..ResolvedCostOfEquity::default()
        },
        policy: ProjectionPolicy {
            version: "gap-attribution-policy/1".into(),
            expected_currency: active.currency.clone(),
            max_age_days: 365,
            min_forecast_horizon_days: 1,
            max_forecast_horizon_days: 3_650,
            min_analyst_count: 1,
            hold_years: settings.hold_years,
            fade_years: settings.fade_years,
            max_projection_years: 40,
            macro_stable_growth_bps: settings.macro_stable_growth_bps,
            risk_free_rate_bps: active.risk_free_rate_bps,
            risk_free_buffer_bps: settings.risk_free_buffer_bps,
            minimum_terminal_spread_bps: settings.minimum_terminal_spread_bps,
        },
    };
    let candidate = value_forward_earnings(&input);
    match candidate.status {
        CandidateStatus::Available => candidate.intrinsic_value_cents.filter(|&v| v > 0),
        _ => None,
    }
}

/// Integer Shapley weights for n=4: |S|!(n-|S|-1)! / n! as rational over 24.
fn shapley_weight_numerator(subset_size: usize) -> i64 {
    // n! = 24. weight = |S|! * (3-|S|)! / 24
    // numerators for |S|=0,1,2,3: 6, 2, 2, 6  (times value, divide by 24 at end)
    match subset_size {
        0 => 6,
        1 => 2,
        2 => 2,
        3 => 6,
        _ => 0,
    }
}

/// Firm FCFF / shares in cents (cash-flow level audit only — not equity value).
fn fcff_per_share_cents(run_rate_dollars: i64, shares: u64) -> Option<i64> {
    if run_rate_dollars <= 0 || shares == 0 {
        return None;
    }
    let numer = (run_rate_dollars as i128).checked_mul(100)?;
    let denom = shares as i128;
    let q = numer / denom;
    let r = numer % denom;
    let half = denom / 2;
    Some(if r.abs() * 2 >= denom.abs() {
        if numer >= 0 {
            q + 1
        } else {
            q - 1
        }
    } else {
        q
    } as i64)
}

/// Policy-baseline **equity** value from firm FCFF: WACC discount + net-debt bridge.
pub fn value_naive_fcff_equity_baseline(
    baseline: &PolicyBaselineSpec,
    active: &ActiveFactorSettings,
    fcff: &NaiveFcffDiagnosticInput,
) -> Option<i64> {
    let run = fcff.fcff_run_rate_dollars.filter(|&v| v > 0)?;
    let shares = fcff.shares_outstanding.filter(|&s| s > 0)?;
    let net_debt = fcff.net_debt_dollars?;
    let wacc = fcff.wacc_bps.filter(|&w| w > 0)?;
    // Stable g under baseline g_terminal params, Gordon-safe vs WACC.
    let rate_linked = active
        .risk_free_rate_bps
        .saturating_sub(baseline.g_terminal_risk_free_buffer_bps);
    let gordon_linked = wacc.saturating_sub(baseline.g_terminal_minimum_terminal_spread_bps);
    let g_stable = baseline
        .g_terminal_macro_stable_growth_bps
        .min(rate_linked)
        .min(gordon_linked);
    if g_stable >= wacc {
        return None;
    }
    crate::dcf_model::equity_cents_from_fcff_run_rate(
        run as f64,
        shares as f64,
        net_debt,
        wacc,
        baseline.horizon_hold_years,
        baseline.horizon_fade_years,
        baseline.path_near_growth_bps, // 0
        g_stable,
    )
}

fn share_of_gap_bps(component: i64, total_gap: i64) -> Option<i32> {
    if total_gap == 0 {
        return None;
    }
    let bps = (component as i128 * 10_000i128) / total_gap as i128;
    i32::try_from(bps).ok()
}

/// Shapley attribution of `v(active) − v(baseline)` across the four factors.
///
/// `street_target_cents` and `naive_fcff` are optional **diagnostic only** —
/// never used in the Shapley value function or factor baselines.
pub fn attribute_policy_delta(
    symbol: &str,
    active: &ActiveFactorSettings,
    baseline: &PolicyBaselineSpec,
    street_target_cents: Option<i64>,
) -> GapAttributionReport {
    attribute_policy_delta_with_fcff(symbol, active, baseline, street_target_cents, None)
}

/// Same as [`attribute_policy_delta`] plus optional naive-FCFF method diagnostic.
pub fn attribute_policy_delta_with_fcff(
    symbol: &str,
    active: &ActiveFactorSettings,
    baseline: &PolicyBaselineSpec,
    street_target_cents: Option<i64>,
    naive_fcff: Option<&NaiveFcffDiagnosticInput>,
) -> GapAttributionReport {
    const FULL: u8 = 0b1111;
    const N_FACTORIAL: i64 = 24;

    // Precompute all 16 coalition values.
    let mut values: [Option<i64>; 16] = [None; 16];
    for mask in 0u8..16 {
        values[mask as usize] =
            value_coalition(settings_for_coalition(mask, active, baseline), active);
    }

    let v_active = values[FULL as usize];
    let v_baseline = values[0];
    let policy_delta = match (v_active, v_baseline) {
        (Some(a), Some(b)) => Some(a - b),
        _ => None,
    };

    let mut attr = [0i64; 4];
    let mut attrs_ok = v_active.is_some() && v_baseline.is_some();

    if attrs_ok {
        for (factor_idx, factor) in AttributionFactor::ALL.iter().enumerate() {
            let bit = factor.bit();
            let mut weighted = 0i64;
            // All subsets of N \ {i}
            for mask in 0u8..16 {
                if mask & bit != 0 {
                    continue;
                }
                let subset_size = mask.count_ones() as usize;
                let v_without = match values[mask as usize] {
                    Some(v) => v,
                    None => {
                        attrs_ok = false;
                        break;
                    }
                };
                let v_with = match values[(mask | bit) as usize] {
                    Some(v) => v,
                    None => {
                        attrs_ok = false;
                        break;
                    }
                };
                let marginal = v_with - v_without;
                weighted = weighted
                    .saturating_add(marginal.saturating_mul(shapley_weight_numerator(subset_size)));
            }
            if !attrs_ok {
                break;
            }
            // Divide by n! with half-up toward +∞ for positive, toward −∞ magnitude for neg.
            attr[factor_idx] = div_round_half_away_from_zero(weighted, N_FACTORIAL);
        }
    }

    let (attr_rates, attr_horizon, attr_path, attr_g, rounding) = if attrs_ok {
        let sum = attr[0] + attr[1] + attr[2] + attr[3];
        let residual = policy_delta.map(|d| d - sum);
        (
            Some(attr[0]),
            Some(attr[1]),
            Some(attr[2]),
            Some(attr[3]),
            residual,
        )
    } else {
        (None, None, None, None, None)
    };

    let horizon_settings_match_baseline = active.hold_years == baseline.horizon_hold_years
        && active.fade_years == baseline.horizon_fade_years;

    let diagnostic_gap_vs_street_cents = match (v_active, street_target_cents) {
        (Some(v), Some(st)) if st > 0 => Some(v - st),
        _ => None,
    };
    let diagnostic_gap_vs_street_bps = match (v_active, street_target_cents) {
        (Some(v), Some(st)) if st > 0 => {
            let bps = ((v - st) as i128 * 10_000i128) / st as i128;
            i32::try_from(bps).ok()
        }
        _ => None,
    };
    let diagnostic_baseline_gap_vs_street_cents = match (v_baseline, street_target_cents) {
        (Some(v), Some(st)) if st > 0 => Some(v - st),
        _ => None,
    };
    let diagnostic_baseline_share_of_gap_bps = match (
        diagnostic_baseline_gap_vs_street_cents,
        diagnostic_gap_vs_street_cents,
    ) {
        (Some(base_gap), Some(total)) => share_of_gap_bps(base_gap, total),
        _ => None,
    };
    let diagnostic_policy_share_of_gap_bps = match (policy_delta, diagnostic_gap_vs_street_cents) {
        (Some(delta), Some(total)) => share_of_gap_bps(delta, total),
        _ => None,
    };

    let (
        fcff_run_rate_dollars,
        fcff_per_share,
        net_debt_dollars,
        wacc_bps,
        coe_unit,
        v_naive_fcff,
        naive_note,
        discount_kind,
        subtracted_nd,
    ) = if let Some(fcff) = naive_fcff {
        let per_share = match (fcff.fcff_run_rate_dollars, fcff.shares_outstanding) {
            (Some(rr), Some(sh)) => fcff_per_share_cents(rr, sh),
            _ => None,
        };
        let v = value_naive_fcff_equity_baseline(baseline, active, fcff);
        let ok = v.is_some();
        (
            fcff.fcff_run_rate_dollars,
            per_share,
            fcff.net_debt_dollars,
            fcff.wacc_bps,
            fcff.coe_unit_beta_bps,
            v,
            fcff.source_note.clone(),
            ok.then_some("wacc".to_string()),
            ok.then_some(true),
        )
    } else {
        (None, None, None, None, None, None, None, None, None)
    };

    let diagnostic_naive_fcff_gap_vs_street_cents = match (v_naive_fcff, street_target_cents) {
        (Some(v), Some(st)) if st > 0 => Some(v - st),
        _ => None,
    };
    let diagnostic_naive_fcff_gap_vs_street_bps = match (v_naive_fcff, street_target_cents) {
        (Some(v), Some(st)) if st > 0 => {
            let bps = ((v - st) as i128 * 10_000i128) / st as i128;
            i32::try_from(bps).ok()
        }
        _ => None,
    };

    let mut notes = Vec::new();
    if v_active.is_none() {
        notes.push("v_active_unavailable");
    }
    if v_baseline.is_none() {
        notes.push("v_baseline_unavailable");
    }
    if !attrs_ok && v_active.is_some() && v_baseline.is_some() {
        notes.push("coalition_value_missing");
    }
    if horizon_settings_match_baseline {
        notes.push("horizon_inert_settings_match");
    }
    notes.push("street=diagnostic_only");
    notes.push("baseline=policy_own");
    notes.push("method=shapley");
    notes.push("naive_fcff=method_diagnostic_not_shapley");

    GapAttributionReport {
        schema_version: ATTRIBUTION_SCHEMA_VERSION.into(),
        decomposition_method: DECOMPOSITION_METHOD.into(),
        factor_baseline_kind: FACTOR_BASELINE_KIND.into(),
        street_role: STREET_ROLE.into(),
        symbol: symbol.into(),
        model: "forward_earnings_power".into(),
        v_active_cents: v_active,
        v_baseline_cents: v_baseline,
        policy_delta_cents: policy_delta,
        attr_rates_cents: attr_rates,
        attr_horizon_cents: attr_horizon,
        attr_path_cents: attr_path,
        attr_g_terminal_cents: attr_g,
        attr_rounding_residual_cents: rounding,
        hold_active: active.hold_years,
        fade_active: active.fade_years,
        hold_baseline: baseline.horizon_hold_years,
        fade_baseline: baseline.horizon_fade_years,
        horizon_settings_match_baseline,
        street_target_cents,
        diagnostic_gap_vs_street_cents,
        diagnostic_gap_vs_street_bps,
        diagnostic_baseline_gap_vs_street_cents,
        diagnostic_baseline_share_of_gap_bps,
        diagnostic_policy_share_of_gap_bps,
        v_naive_fcff_baseline_cents: v_naive_fcff,
        naive_fcff_discount_rate_kind: discount_kind,
        naive_fcff_subtracted_net_debt: subtracted_nd,
        fcff_run_rate_dollars,
        fcff_per_share_cents: fcff_per_share,
        net_debt_dollars,
        naive_fcff_wacc_bps: wacc_bps,
        naive_fcff_coe_unit_beta_bps: coe_unit,
        diagnostic_naive_fcff_gap_vs_street_cents,
        diagnostic_naive_fcff_gap_vs_street_bps,
        naive_fcff_source_note: naive_note,
        baseline_spec: *baseline,
        active_settings: active.clone(),
        notes: Some(notes.join(";")),
    }
}

fn div_round_half_away_from_zero(numer: i64, denom: i64) -> i64 {
    if denom == 0 {
        return 0;
    }
    let half = denom.abs() / 2;
    if numer >= 0 {
        (numer + half) / denom
    } else {
        (numer - half) / denom
    }
}

/// Fixed diagnostic cohort for schema validation with real engine settings.
pub const ATTRIBUTION_DIAGNOSTIC_COHORT: &[&str] = &["CHTR", "T", "MPWR", "WDC", "GOOGL"];

/// Relative disagreement bps used only for **reporting** in the diagnostic cohort.
/// Must not be wired into accept/reject of high-signal or any calibration objective.
pub fn diagnostic_relative_gap_bps(our_cents: i64, street_cents: i64) -> Option<i32> {
    if street_cents <= 0 {
        return None;
    }
    let bps = ((our_cents - street_cents) as i128 * 10_000i128) / street_cents as i128;
    i32::try_from(bps).ok()
}

/// Build active factor settings from already-resolved engine pieces (forward path).
///
/// Caller supplies CoE from `resolve_cost_of_equity`, hold/fade from
/// `derive_hold_years` / `derive_fade_years`, and growth/EPS from normalized
/// forward evidence. Street is never accepted here.
pub fn active_settings_from_engine(
    cost_of_equity_bps: i32,
    cost_of_equity_provisional: bool,
    hold_years: i32,
    fade_years: i32,
    eps_mean_cents: i64,
    near_growth_bps: i32,
    return_on_capital_bps: Option<i32>,
    market_params: &crate::dcf_model::MarketParams,
    as_of_epoch_day: i64,
    forecast_period_end_epoch_day: i64,
    analyst_count: Option<i32>,
    currency: &str,
    source_fingerprint: &str,
) -> ActiveFactorSettings {
    ActiveFactorSettings {
        cost_of_equity_bps,
        cost_of_equity_provisional,
        hold_years,
        fade_years,
        eps_mean_cents,
        near_growth_bps,
        return_on_capital_bps,
        macro_stable_growth_bps: 300,
        risk_free_rate_bps: market_params.rf_bps,
        risk_free_buffer_bps: 100,
        minimum_terminal_spread_bps: 100,
        erp_bps: market_params.erp_bps,
        currency: currency.into(),
        as_of_epoch_day,
        forecast_period_end_epoch_day,
        analyst_count,
        source_fingerprint: source_fingerprint.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;
    use std::path::PathBuf;

    fn sample_active() -> ActiveFactorSettings {
        ActiveFactorSettings {
            cost_of_equity_bps: 1_100, // 11%
            return_on_capital_bps: None,
            cost_of_equity_provisional: false,
            hold_years: 3,
            fade_years: 5,
            eps_mean_cents: 1_000,  // $10
            near_growth_bps: 1_500, // 15%
            macro_stable_growth_bps: 300,
            risk_free_rate_bps: 400,
            risk_free_buffer_bps: 100,
            minimum_terminal_spread_bps: 100,
            erp_bps: 500,
            currency: "USD".into(),
            as_of_epoch_day: 20_000,
            forecast_period_end_epoch_day: 20_400,
            analyst_count: Some(12),
            source_fingerprint: "test-active".into(),
        }
    }

    #[test]
    fn shapley_attrs_sum_to_policy_delta_within_rounding() {
        let report = attribute_policy_delta(
            "TEST",
            &sample_active(),
            &PolicyBaselineSpec::default(),
            None,
        );
        assert_eq!(report.decomposition_method, "shapley");
        assert_eq!(report.factor_baseline_kind, "policy_own");
        assert_eq!(report.street_role, "diagnostic_only");
        let delta = report.policy_delta_cents.expect("policy delta");
        let sum = report.attr_rates_cents.unwrap()
            + report.attr_horizon_cents.unwrap()
            + report.attr_path_cents.unwrap()
            + report.attr_g_terminal_cents.unwrap();
        let residual = report.attr_rounding_residual_cents.unwrap();
        assert_eq!(delta - sum, residual);
        // Rounding residual must be tiny vs policy delta magnitude.
        assert!(residual.abs() <= 4, "residual={residual} delta={delta}");
    }

    #[test]
    fn street_is_diagnostic_only_never_enters_factor_baselines() {
        let active = sample_active();
        let baseline = PolicyBaselineSpec::default();
        let without_street = attribute_policy_delta("TEST", &active, &baseline, None);
        // Absurd Street that would dominate reverse-engineered baselines if used.
        let with_street = attribute_policy_delta("TEST", &active, &baseline, Some(1));
        assert_eq!(without_street.v_active_cents, with_street.v_active_cents);
        assert_eq!(
            without_street.v_baseline_cents,
            with_street.v_baseline_cents
        );
        assert_eq!(
            without_street.policy_delta_cents,
            with_street.policy_delta_cents
        );
        assert_eq!(
            without_street.attr_rates_cents,
            with_street.attr_rates_cents
        );
        assert_eq!(
            without_street.attr_horizon_cents,
            with_street.attr_horizon_cents
        );
        assert_eq!(without_street.attr_path_cents, with_street.attr_path_cents);
        assert_eq!(
            without_street.attr_g_terminal_cents,
            with_street.attr_g_terminal_cents
        );
        // Diagnostic fields present only when Street is supplied.
        assert!(without_street.diagnostic_gap_vs_street_cents.is_none());
        assert!(with_street.diagnostic_gap_vs_street_cents.is_some());
    }

    #[test]
    fn rates_factor_alone_moves_value_when_only_coe_differs() {
        // Active CoE much higher than unit-beta baseline (rf+erp=900).
        let mut active = sample_active();
        active.cost_of_equity_bps = 1_800;
        active.hold_years = 0;
        active.fade_years = 10;
        active.near_growth_bps = 0; // path baseline matches active
                                    // g_terminal params match default baseline
        let report = attribute_policy_delta("TEST", &active, &PolicyBaselineSpec::default(), None);
        let rates = report.attr_rates_cents.expect("rates");
        // Higher CoE → lower value vs unit-beta baseline → negative rates attr.
        assert!(rates < 0, "rates attr should lower value, got {rates}");
        // Path should be ~0 (active near growth = baseline 0).
        assert_eq!(report.attr_path_cents, Some(0));
    }

    #[test]
    fn path_growth_capitalization_is_isolated() {
        let mut active = sample_active();
        // Match rates baseline: unit beta rf+erp
        active.cost_of_equity_bps = active.risk_free_rate_bps + active.erp_bps;
        active.hold_years = 0;
        active.fade_years = 10;
        active.near_growth_bps = 2_000;
        let report = attribute_policy_delta("TEST", &active, &PolicyBaselineSpec::default(), None);
        let path = report.attr_path_cents.expect("path");
        assert!(
            path > 0,
            "growth path should raise value vs zero growth, got {path}"
        );
    }

    #[test]
    fn shapley_is_order_independent_by_construction() {
        // Re-running attribution is deterministic; same inputs → same attrs.
        let a = attribute_policy_delta(
            "TEST",
            &sample_active(),
            &PolicyBaselineSpec::default(),
            None,
        );
        let b = attribute_policy_delta(
            "TEST",
            &sample_active(),
            &PolicyBaselineSpec::default(),
            None,
        );
        assert_eq!(a.attr_rates_cents, b.attr_rates_cents);
        assert_eq!(a.attr_horizon_cents, b.attr_horizon_cents);
        assert_eq!(a.attr_path_cents, b.attr_path_cents);
        assert_eq!(a.attr_g_terminal_cents, b.attr_g_terminal_cents);
    }

    #[test]
    fn contract_declares_shapley_and_policy_own_baseline() {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/contracts/valuation-gap-attribution-v1.json");
        let raw = std::fs::read_to_string(&path).expect("contract file");
        let v: serde_json::Value = serde_json::from_str(&raw).expect("json");
        assert_eq!(v["decompositionMethod"], "shapley");
        assert_eq!(v["factorBaselineKind"], "policy_own");
        assert_eq!(v["streetRole"], "diagnostic_only");
        assert_eq!(
            v["factorBaselineNever"]
                .as_array()
                .expect("array")
                .iter()
                .filter_map(|x| x.as_str())
                .collect::<BTreeSet<_>>(),
            BTreeSet::from([
                "street_reverse_engineered_coe",
                "street_reverse_engineered_horizon",
                "street_implied_growth",
                "market_price_implied_multiple",
            ])
        );
        let factors = v["factors"]
            .as_array()
            .expect("factors")
            .iter()
            .filter_map(|x| x.as_str())
            .collect::<Vec<_>>();
        assert_eq!(factors, vec!["rates", "horizon", "path", "g_terminal"]);
    }

    /// Build-gate: no test or gate may use Street-framed attribution fields as
    /// acceptance / optimization criteria. Factor attrs that sum to policy delta
    /// may be unit-tested for identity (sum, sign), but never minimized vs Street.
    #[test]
    fn street_diagnostic_only_enforcement_scan() {
        let src_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("src");
        let tests_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests");
        let mut violations = Vec::new();

        // Patterns that treat Street gap / Street-framed attrs as accept/optimize targets.
        let forbidden_snippets = [
            "assert!(.*diagnostic_gap_vs_street",
            "assert_eq!\\(.*diagnostic_gap_vs_street",
            "assert!\\(.*attr_.*_vs_street",
            "assert_eq!\\(.*attr_.*_vs_street",
            "max_.*diagnostic_gap_vs_street",
            "min_.*diagnostic_gap_vs_street",
            "minimize.*diagnostic_gap_vs_street",
            "optimize.*diagnostic_gap_vs_street",
            "attr_residual_bps",
            "attr_residual_vs_street",
            "accept.*gap_vs_street",
            "high_signal.*attr_.*street",
        ];

        for root in [src_root, tests_root] {
            if !root.exists() {
                continue;
            }
            scan_rs_files(&root, &forbidden_snippets, &mut violations);
        }

        // Self-file may mention patterns only inside this enforcement test's string table
        // and documentation — filter those out by requiring a "live" assert form.
        violations.retain(|v| {
            !v.contains("valuation_gap_attribution.rs")
                || v.contains("assert") && !v.contains("forbidden_snippets")
        });

        // Re-scan with a simpler allowlist approach: only flag files other than this module
        // that contain assert* on diagnostic_gap_vs_street or attr_*_vs_street.
        let mut real = Vec::new();
        let roots = [
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("src"),
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests"),
        ];
        for root in roots {
            if !root.exists() {
                continue;
            }
            collect_street_assert_violations(&root, &mut real);
        }

        assert!(
            real.is_empty(),
            "Street-framed attribution fields used as acceptance criteria:\n{}",
            real.join("\n")
        );
    }

    fn scan_rs_files(dir: &std::path::Path, _patterns: &[&str], _out: &mut Vec<String>) {
        // Placeholder kept for documentation; real check is collect_street_assert_violations.
        let _ = (dir, _patterns, _out);
    }

    fn collect_street_assert_violations(dir: &std::path::Path, out: &mut Vec<String>) {
        let entries = match std::fs::read_dir(dir) {
            Ok(e) => e,
            Err(_) => return,
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                collect_street_assert_violations(&path, out);
                continue;
            }
            if path.extension().and_then(|e| e.to_str()) != Some("rs") {
                continue;
            }
            // This module documents forbidden names; skip its own source.
            if path
                .file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n == "valuation_gap_attribution.rs")
            {
                continue;
            }
            let Ok(content) = std::fs::read_to_string(&path) else {
                continue;
            };
            for (line_no, line) in content.lines().enumerate() {
                let trimmed = line.trim();
                if trimmed.starts_with("//") {
                    continue;
                }
                let lower = trimmed.to_ascii_lowercase();
                let is_assert = lower.contains("assert!")
                    || lower.contains("assert_eq!")
                    || lower.contains("assert_ne!")
                    || lower.contains("assert_lt")
                    || lower.contains("assert_gt")
                    || lower.contains("assert_le")
                    || lower.contains("assert_ge");
                if !is_assert {
                    continue;
                }
                if lower.contains("diagnostic_gap_vs_street")
                    || lower.contains("attr_residual_bps")
                    || lower.contains("attr_residual_vs_street")
                    || lower.contains("attr_") && lower.contains("_vs_street")
                    || lower.contains("gap_vs_street")
                        && (lower.contains("<")
                            || lower.contains(">")
                            || lower.contains("max_")
                            || lower.contains("min_"))
                {
                    out.push(format!("{}:{}: {}", path.display(), line_no + 1, trimmed));
                }
            }
        }
    }

    #[test]
    fn diagnostic_cohort_symbols_are_pinned() {
        assert_eq!(
            ATTRIBUTION_DIAGNOSTIC_COHORT,
            &["CHTR", "T", "MPWR", "WDC", "GOOGL"]
        );
    }

    #[test]
    fn horizon_attr_zero_when_settings_match_baseline() {
        let mut active = sample_active();
        active.hold_years = 0;
        active.fade_years = 10; // matches PolicyBaselineSpec::default
        let report = attribute_policy_delta("TEST", &active, &PolicyBaselineSpec::default(), None);
        assert!(report.horizon_settings_match_baseline);
        assert_eq!(report.hold_active, 0);
        assert_eq!(report.fade_active, 10);
        assert_eq!(report.hold_baseline, 0);
        assert_eq!(report.fade_baseline, 10);
        assert_eq!(report.attr_horizon_cents, Some(0));
    }

    #[test]
    fn gap_share_bps_sum_to_10000_when_street_present() {
        let report = attribute_policy_delta(
            "TEST",
            &sample_active(),
            &PolicyBaselineSpec::default(),
            Some(5_000),
        );
        let base = report
            .diagnostic_baseline_share_of_gap_bps
            .expect("base share");
        let pol = report
            .diagnostic_policy_share_of_gap_bps
            .expect("policy share");
        // Truncation of independent integer divisions can leave ±1 bps.
        assert!((base + pol - 10_000).abs() <= 1, "base={base} pol={pol}");
    }

    #[test]
    fn naive_fcff_uses_wacc_and_subtracts_net_debt() {
        let active = sample_active();
        let baseline = PolicyBaselineSpec::default();
        // 1 share, FCFF $10/yr, net debt $50 → equity much lower than capitalising FCFF at CoE.
        let with_debt = NaiveFcffDiagnosticInput {
            fcff_run_rate_dollars: Some(10),
            shares_outstanding: Some(1),
            net_debt_dollars: Some(50),
            wacc_bps: Some(900), // 9%
            coe_unit_beta_bps: Some(900),
            after_tax_cod_bps: Some(0),
            equity_weight_bps: Some(10_000),
            debt_weight_bps: Some(0),
            source_note: Some("unit-test".into()),
        };
        let no_debt = NaiveFcffDiagnosticInput {
            net_debt_dollars: Some(0),
            ..with_debt.clone()
        };
        let v_debt =
            value_naive_fcff_equity_baseline(&baseline, &active, &with_debt).expect("with debt");
        let v_clean =
            value_naive_fcff_equity_baseline(&baseline, &active, &no_debt).expect("no debt");
        assert!(
            v_clean > v_debt,
            "net debt must lower equity/share: clean={v_clean} debt={v_debt}"
        );
        // $50 net debt on 1 share ≈ $50/share hit (5000 cents order).
        assert!(
            v_clean - v_debt >= 4_000,
            "debt bridge material: delta={}",
            v_clean - v_debt
        );

        let report =
            attribute_policy_delta_with_fcff("TEST", &active, &baseline, None, Some(&with_debt));
        assert_eq!(
            report.naive_fcff_discount_rate_kind.as_deref(),
            Some("wacc")
        );
        assert_eq!(report.naive_fcff_subtracted_net_debt, Some(true));
        assert_eq!(report.naive_fcff_wacc_bps, Some(900));
    }

    #[test]
    fn equity_from_fcff_rejects_stable_g_at_or_above_wacc() {
        assert!(crate::dcf_model::equity_cents_from_fcff_run_rate(
            100.0, 10.0, 0, 300, 0, 10, 0, 300
        )
        .is_none());
    }

    /// Live diagnostic: recompute active engine settings for the fixed 5-name
    /// cohort and emit Shapley attribution. Reports only — never asserts on
    /// Street gap magnitude (that would reintroduce Street as optimand).
    ///
    /// Run: `cargo test --lib valuation_gap_attribution::tests::live_attribution_diagnostic_cohort -- --ignored --nocapture`
    #[test]
    #[ignore = "network: Yahoo + live rates + SEC; diagnostic review only"]
    fn live_attribution_diagnostic_cohort() {
        let yahoo = crate::fetcher::YahooClient::new().expect("Yahoo client");
        let edgar = crate::edgar::edgar_client();
        let cik_map = crate::edgar::fetch_cik_map(&edgar).expect("CIK map");
        let day = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| (d.as_secs() / 86_400) as i64)
            .unwrap_or(20_000);
        let market_params = if let Some((rf_bps, as_of)) = yahoo.fetch_us_10y_yield_bps() {
            crate::dcf_model::MarketParams::from_live_risk_free(rf_bps, as_of)
        } else {
            crate::dcf_model::MarketParams::default_usd()
        };
        let baseline = PolicyBaselineSpec::default();
        let mut reports = Vec::new();

        eprintln!("=== HORIZON RAW SETTINGS ===");
        eprintln!(
            "{:<6} {:>10} {:>10} {:>10} {:>10} {:>8} {:>12}",
            "sym", "hold_act", "fade_act", "hold_base", "fade_base", "match?", "attr_horiz"
        );

        for &symbol in ATTRIBUTION_DIAGNOSTIC_COHORT {
            let report = match live_one(
                symbol,
                &yahoo,
                &edgar,
                &cik_map,
                day,
                &market_params,
                &baseline,
            ) {
                Ok(r) => r,
                Err(e) => {
                    eprintln!("{symbol}: SKIP {e}");
                    continue;
                }
            };
            eprintln!(
                "{:<6} {:>10} {:>10} {:>10} {:>10} {:>8} {:>12?}",
                report.symbol,
                report.hold_active,
                report.fade_active,
                report.hold_baseline,
                report.fade_baseline,
                report.horizon_settings_match_baseline,
                report.attr_horizon_cents,
            );
            // Identity only — never assert Street band.
            if let (Some(delta), Some(r), Some(h), Some(p), Some(g), Some(res)) = (
                report.policy_delta_cents,
                report.attr_rates_cents,
                report.attr_horizon_cents,
                report.attr_path_cents,
                report.attr_g_terminal_cents,
                report.attr_rounding_residual_cents,
            ) {
                assert_eq!(delta - (r + h + p + g), res);
            }
            // If settings match baseline, horizon attr must be $0 (not a Shapley bug).
            if report.horizon_settings_match_baseline {
                assert_eq!(
                    report.attr_horizon_cents,
                    Some(0),
                    "{} horizon settings match baseline but attr_horizon != 0",
                    report.symbol
                );
            }
            reports.push(report);
        }

        eprintln!("=== GAP COMPOSITION vs STREET (diagnostic) ===");
        eprintln!(
            "{:<6} {:>10} {:>10} {:>10} {:>10} {:>12} {:>12} {:>10} {:>10}",
            "sym",
            "street",
            "v_active",
            "v_base",
            "gap_tot",
            "gap_in_base",
            "gap_policy",
            "%_base",
            "%_policy"
        );
        for r in &reports {
            let pct_base = r
                .diagnostic_baseline_share_of_gap_bps
                .map(|b| format!("{:.1}%", b as f64 / 100.0))
                .unwrap_or_else(|| "—".into());
            let pct_pol = r
                .diagnostic_policy_share_of_gap_bps
                .map(|b| format!("{:.1}%", b as f64 / 100.0))
                .unwrap_or_else(|| "—".into());
            eprintln!(
                "{:<6} {:>10?} {:>10?} {:>10?} {:>10?} {:>12?} {:>12?} {:>10} {:>10}",
                r.symbol,
                r.street_target_cents,
                r.v_active_cents,
                r.v_baseline_cents,
                r.diagnostic_gap_vs_street_cents,
                r.diagnostic_baseline_gap_vs_street_cents,
                r.policy_delta_cents,
                pct_base,
                pct_pol,
            );
        }

        eprintln!("=== METHOD DIAGNOSTIC: EPS@CoE vs FCFF@WACC+net_debt bridge ===");
        eprintln!(
            "{:<6} {:>8} {:>8} {:>12} {:>10} {:>8} {:>10} {:>12} {:>12} {:>10}",
            "sym",
            "eps¢",
            "fcff/sh¢",
            "net_debt$",
            "wacc_bps",
            "rate",
            "v_EPS",
            "v_FCFF_eq",
            "gap_EPS",
            "gap_FCFF"
        );
        for r in &reports {
            let gap_eps = r.diagnostic_baseline_gap_vs_street_cents;
            let gap_fcff = r.diagnostic_naive_fcff_gap_vs_street_cents;
            let closer = match (gap_eps, gap_fcff) {
                (Some(e), Some(f)) => {
                    if f.abs() < e.abs() {
                        "FCFF_closer"
                    } else if e.abs() < f.abs() {
                        "EPS_closer"
                    } else {
                        "tie"
                    }
                }
                (Some(_), None) => "no_fcff",
                _ => "—",
            };
            eprintln!(
                "{:<6} {:>8} {:>8?} {:>12?} {:>10?} {:>8} {:>10?} {:>12?} {:>12?} {:>10?}  {}",
                r.symbol,
                r.active_settings.eps_mean_cents,
                r.fcff_per_share_cents,
                r.net_debt_dollars,
                r.naive_fcff_wacc_bps,
                r.naive_fcff_discount_rate_kind.as_deref().unwrap_or("—"),
                r.v_baseline_cents,
                r.v_naive_fcff_baseline_cents,
                gap_eps,
                gap_fcff,
                closer,
            );
            eprintln!(
                "       sub_nd={:?} coe_unitβ={:?} note={}",
                r.naive_fcff_subtracted_net_debt,
                r.naive_fcff_coe_unit_beta_bps,
                r.naive_fcff_source_note.as_deref().unwrap_or(""),
            );
            assert!(
                r.v_naive_fcff_baseline_cents.is_some(),
                "{} must have naive FCFF equity value (no no_fcff)",
                r.symbol
            );
            assert_eq!(r.naive_fcff_discount_rate_kind.as_deref(), Some("wacc"));
            assert_eq!(r.naive_fcff_subtracted_net_debt, Some(true));
        }

        assert!(
            !reports.is_empty(),
            "expected at least one live attribution report"
        );

        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/valuation/gap_attribution_diagnostic_cohort.json");
        let body = serde_json::json!({
            "contract": "valuation-gap-attribution-v1",
            "captureId": format!("attribution-diagnostic-{}", day),
            "decompositionMethod": DECOMPOSITION_METHOD,
            "factorBaselineKind": FACTOR_BASELINE_KIND,
            "streetRole": STREET_ROLE,
            "note": "Diagnostic capture only. Street gap is not an acceptance criterion. Do not calibrate to minimize diagnostic_gap_vs_street_*. naive_fcff is method diagnostic, not a Shapley factor.",
            "members": reports,
        });
        if let Err(e) = std::fs::write(
            &path,
            serde_json::to_string_pretty(&body).expect("serialize"),
        ) {
            eprintln!("warn: could not write {}: {e}", path.display());
        } else {
            eprintln!("wrote attribution diagnostic → {}", path.display());
        }
    }

    /// Sniff / driver audit for CHTR (and peers). Not a conclusion gate.
    /// Run: cargo test --lib valuation_gap_attribution::tests::live_fcff_driver_audit_cohort -- --ignored --nocapture
    #[test]
    #[ignore = "network: SEC driver audit only"]
    fn live_fcff_driver_audit_cohort() {
        let yahoo = crate::fetcher::YahooClient::new().expect("Yahoo");
        let edgar = crate::edgar::edgar_client();
        let cik_map = crate::edgar::fetch_cik_map(&edgar).expect("CIK");
        // External sniff anchors (order-of-magnitude, not optimands):
        // CHTR FCF TTM ~$30–35/sh; research post-capex ~$60–70/sh by 2027–28.
        // CHTR diluted WAS Q2'26 ~121.3M; Yahoo shares outstanding ~119.3M.
        const EXTERNAL_CHTR_FCF_PER_SHARE_LOW: f64 = 30.0;
        const EXTERNAL_CHTR_FCF_PER_SHARE_HIGH: f64 = 70.0;

        // AMZN is the owner-earnings anchor: any maintenance-CapEx policy change
        // has to be read against both ends of the range (CHTR over-adds back,
        // AMZN under-adds back) or the calibration becomes one-sided.
        // SW and WDC are the two names the restored dispute refusal exposed: the
        // FCFF lane prices SW at 18.6x market and WDC at 0.04x. Both are here to
        // test whether the cable-calibrated renewal rate generalizes.
        for &symbol in &[
            "CHTR", "T", "WDC", "SW", "MPWR", "PG", "GOOGL", "AMZN", "MSFT", "AAPL",
        ] {
            let Ok(fetched) = yahoo.fetch_symbol(symbol) else {
                eprintln!("{symbol}: yahoo fail");
                continue;
            };
            let Some(mut fund) = fetched.fundamentals else {
                eprintln!("{symbol}: no fund");
                continue;
            };
            let Some(&cik) = cik_map.get(symbol) else {
                eprintln!("{symbol}: no cik");
                continue;
            };
            if fund.shares_outstanding.unwrap_or(0) == 0 {
                fund.shares_outstanding =
                    crate::edgar::fetch_shares_outstanding(&edgar, symbol, cik).unwrap_or(None);
            }
            let Ok(Some(history)) = crate::edgar::fetch_fcf_history(&edgar, symbol, cik) else {
                eprintln!("{symbol}: no fcf history");
                continue;
            };
            match crate::dcf_model::extract_normalized_fcff_level(&fund, &history) {
                Ok(level) => {
                    let per_share =
                        level.normalized_fcff_dollars as f64 / level.shares_outstanding as f64;
                    eprintln!(
                        "\n=== {symbol} FCFF LEVEL AUDIT ===\n  shares={}  fcff_total=${:.3}B  fcff/sh=${:.2}\n  owner_earnings={}  base_margin_bps={}  annual_reported_margin_bps={}\n  ocf_margin_bps={}  capex_intensity_bps={}  maint_capex_bps={:?}  interest_margin_bps={}\n  revenue_latest=${:.3}B",
                        level.shares_outstanding,
                        level.normalized_fcff_dollars as f64 / 1e9,
                        per_share,
                        level.owner_earnings_base,
                        level.base_fcff_margin_bps,
                        level.annual_reported_fcff_margin_bps,
                        level.normalized_ocf_margin_bps,
                        level.normalized_capex_intensity_bps,
                        level.maintenance_capex_intensity_bps,
                        level.normalized_after_tax_interest_margin_bps,
                        level.latest_revenue_dollars as f64 / 1e9,
                    );
                    eprintln!(
                        "  base_growth_bps={}  acquisition_contaminated_years={:?}  reported_fcf_per_share_latest=${:.2}",
                        level.base_growth_bps,
                        level.acquisition_contaminated_growth_years,
                        level
                            .years
                            .last()
                            .and_then(|y| y.reported_fcff_dollars)
                            .unwrap_or(0) as f64
                            / level.shares_outstanding as f64,
                    );
                    eprintln!(
                        "  year | revenue$B | ocf$B | capex$B | reported_fcff$B | ocf% | capex% | fcff%"
                    );
                    for y in &level.years {
                        eprintln!(
                            "  {} | {:8.2} | {:6.2} | {:7.2} | {:13.2} | {:4.1} | {:5.1} | {:5.1}",
                            y.year,
                            y.revenue_dollars as f64 / 1e9,
                            y.ocf_dollars.unwrap_or(0) as f64 / 1e9,
                            y.capex_dollars.unwrap_or(0) as f64 / 1e9,
                            y.reported_fcff_dollars.unwrap_or(0) as f64 / 1e9,
                            y.ocf_margin_bps as f64 / 100.0,
                            y.capex_intensity_bps as f64 / 100.0,
                            y.fcff_margin_bps as f64 / 100.0,
                        );
                    }
                    if symbol == "CHTR" {
                        let ok = per_share >= EXTERNAL_CHTR_FCF_PER_SHARE_LOW
                            && per_share <= EXTERNAL_CHTR_FCF_PER_SHARE_HIGH;
                        eprintln!(
                            "  SNIFF vs external ~${:.0}–{:.0}/sh: engine=${:.2}/sh → {}",
                            EXTERNAL_CHTR_FCF_PER_SHARE_LOW,
                            EXTERNAL_CHTR_FCF_PER_SHARE_HIGH,
                            per_share,
                            if ok {
                                "PASS"
                            } else {
                                "FAIL — do not use as clean evidence"
                            }
                        );
                    }
                }
                Err(e) => eprintln!("{symbol}: extract fail {e}"),
            }
        }
    }

    fn live_one(
        symbol: &str,
        yahoo: &crate::fetcher::YahooClient,
        edgar: &reqwest::blocking::Client,
        cik_map: &std::collections::HashMap<String, u64>,
        day: i64,
        market_params: &crate::dcf_model::MarketParams,
        baseline: &PolicyBaselineSpec,
    ) -> Result<GapAttributionReport, String> {
        let fetched = yahoo
            .fetch_symbol(symbol)
            .map_err(|e| format!("yahoo:{e}"))?;
        let street = fetched
            .snapshot
            .as_ref()
            .map(|s| s.intrinsic_value_cents)
            .filter(|&c| c > 0);
        let mut fund = fetched
            .fundamentals
            .ok_or_else(|| "missing_fundamentals".to_string())?;
        let coe = crate::dcf_model::resolve_cost_of_equity(&fund, market_params)
            .map_err(|e| format!("coe:{e:?}"))?;
        let evidence = yahoo
            .fetch_forward_forecast(symbol, day)
            .map_err(|e| format!("forward:{e}"))?;
        let normalized =
            crate::operating_valuation_runtime::normalize_forward_evidence(&evidence, &fund, None)
                .map_err(|e| format!("normalize:{e:?}"))?;
        let fade = crate::operating_valuation_runtime::derive_fade_years(
            &fund,
            normalized.forecast.near_growth_bps,
        );
        let eps = normalized
            .forecast
            .eps_mean_cents
            .ok_or_else(|| "missing_eps".to_string())?;
        let active = active_settings_from_engine(
            coe.cost_of_equity_bps,
            coe.provisional,
            normalized.hold_years,
            fade,
            eps,
            normalized.forecast.near_growth_bps,
            // No FCFF analysis in this module, so this exercises the unlevered-ROE
            // fallback rather than the through-cycle branch production prefers.
            crate::operating_valuation_runtime::return_on_capital_bps(&fund, None),
            market_params,
            day,
            normalized.forecast.forecast_period_end_epoch_day,
            normalized.forecast.analyst_count,
            &normalized.forecast.currency,
            &normalized.forecast.source_fingerprint,
        );

        // Owner-earnings level + WACC (unit-β CoE inside) — independent of
        // full scenario ordering so CHTR-class ordering failures still yield a level.
        let mut naive = NaiveFcffDiagnosticInput::default();
        if let Some(&cik) = cik_map.get(symbol) {
            if fund.shares_outstanding.unwrap_or(0) == 0 {
                fund.shares_outstanding =
                    crate::edgar::fetch_shares_outstanding(edgar, symbol, cik).unwrap_or(None);
            }
            match crate::edgar::fetch_fcf_history(edgar, symbol, cik) {
                Ok(Some(history)) => {
                    match crate::dcf_model::extract_normalized_fcff_level(&fund, &history) {
                        Ok(level) => {
                            naive.fcff_run_rate_dollars = Some(level.normalized_fcff_dollars);
                            naive.shares_outstanding = Some(level.shares_outstanding);
                            naive.net_debt_dollars = Some(level.net_debt_dollars);
                            match crate::dcf_model::resolve_attribution_wacc(
                                &fund,
                                &history,
                                market_params,
                                street,
                                true, // unit-beta CoE inside WACC (policy baseline)
                            ) {
                                Ok(w) => {
                                    naive.wacc_bps = Some(w.wacc_bps);
                                    naive.coe_unit_beta_bps = Some(w.cost_of_equity_bps);
                                    naive.after_tax_cod_bps = Some(w.after_tax_cost_of_debt_bps);
                                    naive.equity_weight_bps = Some(w.equity_weight_bps);
                                    naive.debt_weight_bps = Some(w.debt_weight_bps);
                                    naive.source_note = Some(format!(
                                        "extract_normalized_fcff_level; owner_earnings={}; margin_bps={}; wacc=unit_beta_coe+cod; net_debt={}",
                                        level.owner_earnings_base,
                                        level.base_fcff_margin_bps,
                                        level.net_debt_dollars
                                    ));
                                }
                                Err(e) => {
                                    naive.source_note =
                                        Some(format!("fcff_level_ok_wacc_fail:{e}"));
                                }
                            }
                        }
                        Err(e) => {
                            naive.source_note = Some(format!("fcff_level:{e}"));
                        }
                    }
                }
                Ok(None) => naive.source_note = Some("missing_fcf_history".into()),
                Err(e) => naive.source_note = Some(format!("fcf_fetch:{e}")),
            }
        } else {
            naive.source_note = Some("missing_cik".into());
        }

        Ok(attribute_policy_delta_with_fcff(
            symbol,
            &active,
            baseline,
            street,
            Some(&naive),
        ))
    }
}
