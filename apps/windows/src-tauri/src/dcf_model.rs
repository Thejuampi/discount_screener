//! Valuation model family: business-class routing, residual income for financials,
//! FCFF+WACC for operating firms, dynamic market params + beta shrink.
//!
//! See `_bmad-output/planning-artifacts/valuation-model-family-architecture.md`.

use serde::{Deserialize, Serialize};

use crate::engine::FundamentalSnapshot;
use crate::sec_driver_normalization_policy_generated::MATERIAL_ACQUISITION_REVENUE_BPS;

pub const ENGINE_VERSION: &str = "valuation-model-family/1";
/// Policy bump: versioned industry-beta priors + through-cycle commodity pull
/// (industry-beta-policy/1). Unclassified sector/industry still refuse FCFF.
/// See `shared/contracts/industry-beta-policy-v1.json`.
pub const MODEL_POLICY_VERSION: &str = "business-class-policy/13-industry-beta-policy-v1";
/// Sole industry-prior table version for CoE shrink (cache fingerprint input).
pub const INDUSTRY_BETA_POLICY_VERSION: &str = "industry-beta-policy/1";

// ── Market policy (versioned; not eternal magic for valuation truth) ───────────
/// Default US 10Y-style nominal risk-free (bps). Shells may override via MarketParams.
const DEFAULT_RF_BPS: i32 = 430;
/// Versioned equity risk premium (bps).
const DEFAULT_ERP_BPS: i32 = 450;
const BETA_COMPANY_WEIGHT_PCT: i64 = 67;
const BETA_INDUSTRY_WEIGHT_PCT: i64 = 33;
const DEFAULT_INDUSTRY_BETA_MILLIS: i32 = 1_000;
const INDUSTRY_BETA_POLICY_JSON: &str =
    include_str!("../../../../shared/contracts/industry-beta-policy-v1.json");
const PROJECTION_YEARS: i32 = 5;
/// Operating drivers are regime-sensitive; use a recent multi-year window
/// rather than allowing a 15-year history to dominate a changed business.
const DRIVER_RECENT_WINDOW: usize = 5;
const COE_SCENARIO_BAND_BPS: i32 = 75;
/// FCFF scenarios stress discount rate when rates are market-sourced.
const WACC_SCENARIO_BAND_BPS: i32 = 100;
/// After provisional base uplift, bear still stresses rates further (not symmetric).
/// Pre-uplift soft + full uplift (~175) + bear band (~150) ≈ soft + 325 bps stress path.
const WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS: i32 = 150;
/// When the base rate is already known-biased low (policy defaults), do **not**
/// invent a still-cheaper bull WACC. Bull band = 0 ⇒ bull uses the same soft base
/// WACC (growth stress only). Bear alone encodes further discount-rate understatement.
const WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS: i32 = 0;
/// Provisional rate inputs have repeatedly understated levered operating-company
/// discount rates. Scale the versioned uplift by net-debt weight; this changes
/// an uncertain input, never the valuation output or its relationship to price.
const PROVISIONAL_WACC_BASE_UPLIFT_BPS: i32 = 175;
const PROVISIONAL_UPLIFT_FULL_DEBT_WEIGHT: f64 = 0.40;
const ROE_BEAR_HAIRCUT_BPS: i32 = 300;
const ROE_BULL_BOOST_BPS: i32 = 200;
const GROWTH_RECENT_WINDOW: usize = 4;
/// Robust recent-growth signal stays within a dynamic band around the macro
/// stable rate. This constrains noisy endpoint CAGR inputs, not valuation output.
const MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS: i32 = 1_200;
/// Real-rate buffer so g_stable < rf (Gordon headroom identity).
const STABLE_GROWTH_RF_BUFFER_BPS: i32 = 100;
/// Long-run nominal economy growth ceiling; g_stable ≤ min(this, rf − buffer).
const MACRO_STABLE_GROWTH_BPS: i32 = 300;
const MIN_STABLE_GROWTH_BPS: i32 = 50;
const GORDON_RATE_EPSILON_BPS: i32 = 50;
/// A CapEx observation is an extraordinary spike only when it is both a
/// relative and an economically material jump.  A persistent new investment
/// regime is retained in the driver history instead of repeatedly discarded.
const CAPEX_SPIKE_RATIO: f64 = 1.40;
const CAPEX_SPIKE_MIN_ABS_BPS: i32 = 500;
/// A persistent expansion regime fades growth pressure more slowly over the
/// explicit five-year forecast; this is derived from revenue persistence, not
/// a company-specific calibration.
const SECULAR_GROWTH_FADE_EXPONENT: f64 = 1.50;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BusinessClass {
    OperatingNonFinancial,
    FinancialServices,
    /// ETF / crypto / non-equity shells — no equity intrinsic model.
    NotEligible,
    /// Sector/industry missing or not in the closed policy tables.
    /// Must **fail** valuation (no silent FCFF fallback).
    Unclassified,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ValuationModel {
    FcffWacc,
    ResidualIncomeEquity,
    None,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DiscountRateKind {
    Wacc,
    CostOfEquity,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WaccFieldSource {
    Reported,
    Default,
    DerivedPriceTimesShares,
    AssumedZero,
    InterestOverDebt,
    IndustryShrink,
    MarketParams,
    MarketYield,
    RatedOrSyntheticSpread,
    InterestOverAverageDebt,
    YahooAlignedInterestOverDebt,
    ReportedMarginalTax,
    TaxReconciliation,
    JurisdictionStatutory,
    DomicileTaxProxy,
    HistoricalEffectiveTax,
    NotApplicable,
    Unavailable,
}

impl WaccFieldSource {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Reported => "reported",
            Self::Default => "default",
            Self::DerivedPriceTimesShares => "derived_price_times_shares",
            Self::AssumedZero => "assumed_zero",
            Self::InterestOverDebt => "interest_over_debt",
            Self::IndustryShrink => "industry_shrink",
            Self::MarketParams => "market_params",
            Self::MarketYield => "market_yield",
            Self::RatedOrSyntheticSpread => "rated_or_synthetic_spread",
            Self::InterestOverAverageDebt => "interest_over_average_debt",
            Self::YahooAlignedInterestOverDebt => "yahoo_aligned_interest_over_debt",
            Self::ReportedMarginalTax => "reported_marginal_tax",
            Self::TaxReconciliation => "tax_reconciliation",
            Self::JurisdictionStatutory => "jurisdiction_statutory",
            Self::DomicileTaxProxy => "domicile_tax_proxy",
            Self::HistoricalEffectiveTax => "historical_effective_tax",
            Self::NotApplicable => "not_applicable",
            Self::Unavailable => "unavailable",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WaccInputProvenance {
    pub market_cap: WaccFieldSource,
    pub beta: WaccFieldSource,
    pub total_debt: WaccFieldSource,
    pub total_cash: WaccFieldSource,
    pub cost_of_debt: WaccFieldSource,
    pub tax_rate: WaccFieldSource,
    /// Legacy field: true when beta was industry-shrunk or market params provisional.
    pub wacc_clamped: bool,
}

impl WaccInputProvenance {
    #[allow(dead_code)] // used by UI / tests parity with Android
    pub fn summary_labels(&self) -> Vec<String> {
        let mut labels = Vec::new();
        if self.market_cap == WaccFieldSource::DerivedPriceTimesShares {
            labels.push("market cap=price×shares".into());
        }
        if self.beta == WaccFieldSource::Default {
            labels.push("beta=default".into());
        }
        // IndustryShrink is intentional estimation (Bayes/Blume), not a weak default —
        // keep it out of provisional noise so Quant Lens quality stays high-SNR.
        if self.total_debt == WaccFieldSource::AssumedZero {
            labels.push("debt=assumed 0".into());
        }
        if self.total_cash == WaccFieldSource::AssumedZero {
            labels.push("cash=assumed 0".into());
        }
        if matches!(
            self.cost_of_debt,
            WaccFieldSource::Default | WaccFieldSource::Unavailable
        ) {
            labels.push(
                if self.cost_of_debt == WaccFieldSource::Unavailable {
                    "cost of debt=unavailable"
                } else {
                    "cost of debt=default"
                }
                .into(),
            );
        }
        if matches!(
            self.tax_rate,
            WaccFieldSource::Default | WaccFieldSource::Unavailable
        ) {
            labels.push(
                if self.tax_rate == WaccFieldSource::Unavailable {
                    "tax=unavailable"
                } else {
                    "tax=default"
                }
                .into(),
            );
        }
        if self.wacc_clamped {
            labels.push("params=provisional".into());
        }
        labels
    }

    /// True when rate inputs used weak defaults (not industry beta shrink).
    pub fn is_provisional(&self) -> bool {
        !self.summary_labels().is_empty()
    }

    /// Point intrinsic must not be shown as a single “truth” number when CoD,
    /// tax, beta, or market params (rf/ERP) come from policy defaults.
    pub fn point_estimate_unreliable(&self) -> bool {
        self.cost_of_debt == WaccFieldSource::Default
            || self.cost_of_debt == WaccFieldSource::Unavailable
            || self.wacc_clamped
            || self.beta == WaccFieldSource::Default
            || self.tax_rate == WaccFieldSource::Default
            || self.tax_rate == WaccFieldSource::Unavailable
    }
}

/// Raw model inputs for UI/debug (avoids archaeology on odd DCF prints).
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DcfDiagnostics {
    /// Most recent fiscal FCF observation, never a normalized replacement.
    pub latest_fcf_dollars: Option<i64>,
    /// Most recent fiscal OCF observation when the provider supplied it.
    #[serde(default)]
    pub latest_ocf_dollars: Option<i64>,
    /// FCFF run-rate actually used by the valuation model.
    #[serde(default)]
    pub fcf_run_rate_dollars: Option<i64>,
    pub shares_outstanding: Option<u64>,
    pub cost_of_equity_bps: Option<i32>,
    pub cost_of_debt_bps: Option<i32>,
    pub after_tax_cost_of_debt_bps: Option<i32>,
    pub equity_weight_bps: Option<i32>,
    pub debt_weight_bps: Option<i32>,
    /// Fiscal years aligned with `fcf_annual_dollars` (oldest → newest).
    #[serde(default)]
    pub fcf_years: Vec<i32>,
    #[serde(default)]
    pub fcf_annual_dollars: Vec<i64>,
    /// When true, UI must not present base as a trusted point estimate.
    #[serde(default)]
    pub point_estimate_unreliable: bool,
    /// `growth_and_discount_rate` | `growth_only` | `none`
    #[serde(default = "default_scenario_stress")]
    pub scenario_stress: String,
    /// Fiscal years where CapEx was interpolated/carried (taxonomy gaps).
    #[serde(default)]
    pub capex_imputed_years: Vec<i32>,
    /// Effective WACC used for bear / bull scenarios (bps).
    #[serde(default)]
    pub wacc_bear_bps: Option<i32>,
    #[serde(default)]
    pub wacc_bull_bps: Option<i32>,
    /// Provisional WACC base uplift applied (bps); 0 when rates are market-solid.
    #[serde(default)]
    pub provisional_wacc_uplift_bps: Option<i32>,
    /// True when FCFF run-rate used the recent-window average (normalized), not only latest.
    #[serde(default)]
    pub fcf_run_rate_normalized: bool,
    /// `driver_based_fcff` when revenue/OCF/CapEx drivers are available, otherwise the
    /// explicit legacy `fcf_history_fade` fallback.
    #[serde(default)]
    pub valuation_driver: String,
    /// Latest reported revenue used to scale the normalized FCFF driver path.
    #[serde(default)]
    pub latest_revenue_dollars: Option<i64>,
    /// Normalized FCFF run-rate after the operating-cash-flow/CapEx bridge.
    #[serde(default)]
    pub normalized_fcff_dollars: Option<i64>,
    /// Median normalized operating-cash-flow margin used by the base case.
    #[serde(default)]
    pub normalized_ocf_margin_bps: Option<i32>,
    /// Median non-spike CapEx/revenue intensity used by the base case.
    #[serde(default)]
    pub normalized_capex_intensity_bps: Option<i32>,
    /// Median after-tax interest/revenue bridge used by the FCFF base case.
    #[serde(default)]
    pub normalized_after_tax_interest_margin_bps: Option<i32>,
    /// Fiscal years identified as extraordinary CapEx intensity spikes.
    #[serde(default)]
    pub capex_spike_years: Vec<i32>,
    /// `secular_expansion` | `stable_operating` | `cyclical_or_transition`.
    #[serde(default)]
    pub driver_regime: String,
    /// Robust recent revenue-growth dispersion used to classify the regime.
    #[serde(default)]
    pub growth_dispersion_bps: Option<i32>,
    /// `revenue_growth_median` or the legacy growth policy name.
    #[serde(default)]
    pub growth_driver: String,
    /// Canonical aligned annual input fingerprint used to invalidate stale DCFs.
    #[serde(default)]
    pub driver_input_fingerprint: Option<String>,
    /// Human-readable provenance for the driver bridge and its source layer.
    #[serde(default)]
    pub driver_provenance: Vec<String>,
}

fn default_scenario_stress() -> String {
    "none".into()
}

/// Live / policy market parameters for discount rates and stable growth.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MarketParams {
    pub rf_bps: i32,
    pub erp_bps: i32,
    pub as_of_epoch: Option<i64>,
    pub provisional: bool,
}

/// Provider-independent cost-of-equity resolution consumed by pure equity
/// valuation candidates. Extracting this DTO does not change the legacy FCFF
/// or residual-income runtime paths.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedCostOfEquity {
    pub cost_of_equity_bps: i32,
    pub beta_source: WaccFieldSource,
    pub provisional: bool,
    pub market_params_as_of_epoch: Option<i64>,
    pub source_fingerprint: String,
    /// Industry prior used in shrink (millis), from versioned policy table.
    #[serde(default)]
    pub industry_beta_millis: i32,
    /// True when the matched policy entry is marked through-cycle (commodity/cycle risk).
    #[serde(default)]
    pub through_cycle_prior: bool,
    /// Policy table version fingerprint (`industry-beta-policy/1`).
    #[serde(default = "default_industry_beta_policy_version")]
    pub industry_beta_policy_version: String,
    /// Matched entry id (or `default` for unmapped provisional prior).
    #[serde(default)]
    pub industry_beta_entry_id: String,
}

impl Default for ResolvedCostOfEquity {
    fn default() -> Self {
        Self {
            cost_of_equity_bps: 0,
            beta_source: WaccFieldSource::Unavailable,
            provisional: true,
            market_params_as_of_epoch: None,
            source_fingerprint: String::new(),
            industry_beta_millis: DEFAULT_INDUSTRY_BETA_MILLIS,
            through_cycle_prior: false,
            industry_beta_policy_version: INDUSTRY_BETA_POLICY_VERSION.into(),
            industry_beta_entry_id: "default".into(),
        }
    }
}

fn default_industry_beta_policy_version() -> String {
    INDUSTRY_BETA_POLICY_VERSION.into()
}

/// Resolved industry beta prior from the versioned policy table.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IndustryBetaPrior {
    pub beta_millis: i32,
    pub entry_id: String,
    pub through_cycle: bool,
    /// True when the prior is the unmapped default (provisional provenance).
    pub provisional: bool,
    pub policy_version: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CostOfEquityResolutionError {
    InvalidMarketParameters,
    ArithmeticOverflow,
    ResultOutOfRange,
}

impl MarketParams {
    pub fn default_usd() -> Self {
        Self {
            rf_bps: DEFAULT_RF_BPS,
            erp_bps: DEFAULT_ERP_BPS,
            as_of_epoch: None,
            provisional: true,
        }
    }

    pub fn stable_growth_bps(&self) -> i32 {
        MACRO_STABLE_GROWTH_BPS
            .min(self.rf_bps - STABLE_GROWTH_RF_BUFFER_BPS)
            .max(MIN_STABLE_GROWTH_BPS)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DcfAnalysis {
    pub bear_intrinsic_value_cents: i64,
    pub base_intrinsic_value_cents: i64,
    pub bull_intrinsic_value_cents: i64,
    /// Discount rate in bps (WACC or cost of equity — see discount_rate_kind).
    pub wacc_bps: i32,
    pub base_growth_bps: i32,
    pub net_debt_dollars: i64,
    pub wacc_inputs: WaccInputProvenance,
    /// "sec_edgar" | "yahoo" | "fundamentals" | "unknown"
    pub source: String,
    #[serde(default = "default_engine_version")]
    pub engine_version: String,
    #[serde(default = "default_model_policy_version")]
    pub model_policy_version: String,
    #[serde(default = "default_business_class")]
    pub business_class: BusinessClass,
    #[serde(default = "default_valuation_model")]
    pub model: ValuationModel,
    #[serde(default = "default_discount_rate_kind")]
    pub discount_rate_kind: DiscountRateKind,
    #[serde(default)]
    pub stable_growth_bps: i32,
    #[serde(default)]
    pub book_value_per_share_cents: Option<i64>,
    #[serde(default)]
    pub roe0_bps: Option<i32>,
    #[serde(default)]
    pub reason_codes: Vec<String>,
    #[serde(default)]
    pub diagnostics: DcfDiagnostics,
}

fn default_engine_version() -> String {
    "legacy".into()
}
fn default_model_policy_version() -> String {
    "legacy".into()
}
fn default_business_class() -> BusinessClass {
    BusinessClass::OperatingNonFinancial
}
fn default_valuation_model() -> ValuationModel {
    ValuationModel::FcffWacc
}
fn default_discount_rate_kind() -> DiscountRateKind {
    DiscountRateKind::Wacc
}

fn driver_input_fingerprint(history: &[FcfPoint]) -> String {
    history
        .iter()
        .map(|point| {
            format!(
                "{}:{}:{}:{}:{}:{}:{}:{}:{}:{}:{}:{}:{}",
                point.year,
                point.value_dollars.round() as i64,
                point
                    .operating_cash_flow_dollars
                    .map(|value| value.round() as i64)
                    .map_or_else(|| "-".into(), |value| value.to_string()),
                point
                    .capital_expenditure_dollars
                    .map(|value| value.round() as i64)
                    .map_or_else(|| "-".into(), |value| value.to_string()),
                point
                    .revenue_dollars
                    .map(|value| value.round() as i64)
                    .map_or_else(|| "-".into(), |value| value.to_string()),
                point
                    .acquisition_investment_dollars
                    .map(|value| value.round() as i64)
                    .map_or_else(|| "-".into(), |value| value.to_string()),
                point
                    .interest_expense_dollars
                    .map(|value| value.round() as i64)
                    .map_or_else(|| "-".into(), |value| value.to_string()),
                point
                    .tax_rate_bps
                    .map_or_else(|| "-".into(), |value| value.to_string()),
                point
                    .total_debt_dollars
                    .map(|value| value.round() as i64)
                    .map_or_else(|| "-".into(), |value| value.to_string()),
                point
                    .marginal_tax_bps
                    .map_or_else(|| "-".into(), |value| value.to_string()),
                point
                    .marginal_tax_source
                    .map_or_else(|| String::from("-"), |value| value.as_str().to_string()),
                point
                    .market_yield_bps
                    .map_or_else(|| "-".into(), |value| value.to_string()),
                point
                    .rated_or_synthetic_spread_bps
                    .map_or_else(|| "-".into(), |value| value.to_string()),
            )
        })
        .collect::<Vec<_>>()
        .join("|")
}

/// Annual FCF point (dollars).
#[derive(Debug, Clone)]
pub struct FcfPoint {
    pub year: i32,
    pub value_dollars: f64,
    /// True when CapEx for this year was interpolated/carried (not filed under known tags).
    pub capex_imputed: bool,
    /// Annual operating cash flow, when the provider supplied the underlying driver.
    pub operating_cash_flow_dollars: Option<f64>,
    /// Annual CapEx as a positive outflow, when the provider supplied the underlying driver.
    pub capital_expenditure_dollars: Option<f64>,
    /// Annual revenue used to normalize operating cash-flow and CapEx margins.
    pub revenue_dollars: Option<f64>,
    /// Cash paid for property/business acquisitions. It is not recurring CapEx
    /// but is material evidence that reported revenue growth may be inorganic.
    pub acquisition_investment_dollars: Option<f64>,
    /// Annual interest expense used to bridge levered OCF to after-tax FCFF.
    pub interest_expense_dollars: Option<f64>,
    /// Effective tax rate in basis points used for the interest add-back.
    pub tax_rate_bps: Option<i32>,
    /// Debt at the same fiscal period end as the annual interest observation.
    pub total_debt_dollars: Option<f64>,
    /// Marginal tax rate for WACC tax shielding, never the historical effective rate.
    pub marginal_tax_bps: Option<i32>,
    /// Provenance for the marginal WACC tax rate.
    pub marginal_tax_source: Option<WaccFieldSource>,
    /// Observable market yield when available for the fiscal period.
    pub market_yield_bps: Option<i32>,
    /// Rating-derived or interest-coverage synthetic spread over risk-free.
    pub rated_or_synthetic_spread_bps: Option<i32>,
}

impl FcfPoint {
    pub fn new(year: i32, value_dollars: f64) -> Self {
        Self {
            year,
            value_dollars,
            capex_imputed: false,
            operating_cash_flow_dollars: None,
            capital_expenditure_dollars: None,
            revenue_dollars: None,
            acquisition_investment_dollars: None,
            interest_expense_dollars: None,
            tax_rate_bps: None,
            total_debt_dollars: None,
            marginal_tax_bps: None,
            marginal_tax_source: None,
            market_yield_bps: None,
            rated_or_synthetic_spread_bps: None,
        }
    }

    pub fn with_operating_drivers(
        mut self,
        operating_cash_flow_dollars: f64,
        capital_expenditure_dollars: f64,
        revenue_dollars: f64,
        interest_expense_dollars: Option<f64>,
        tax_rate_bps: Option<i32>,
    ) -> Self {
        self.operating_cash_flow_dollars = Some(operating_cash_flow_dollars);
        self.capital_expenditure_dollars = Some(capital_expenditure_dollars.abs());
        self.revenue_dollars = Some(revenue_dollars);
        self.interest_expense_dollars = interest_expense_dollars.map(f64::abs);
        self.tax_rate_bps = tax_rate_bps;
        self
    }

    pub fn with_acquisition_investment(mut self, dollars: Option<f64>) -> Self {
        self.acquisition_investment_dollars = dollars.map(f64::abs);
        self
    }

    pub fn with_rate_resolution_inputs(
        mut self,
        total_debt_dollars: Option<f64>,
        marginal_tax_bps: Option<i32>,
        market_yield_bps: Option<i32>,
        rated_or_synthetic_spread_bps: Option<i32>,
    ) -> Self {
        self.total_debt_dollars = total_debt_dollars;
        self.marginal_tax_bps = marginal_tax_bps;
        // Contract fixtures use the explicit US statutory table as their
        // declared source; provider adapters override this with filing-level
        // reconciliation or domicile provenance before runtime resolution.
        self.marginal_tax_source = marginal_tax_bps.map(|_| WaccFieldSource::JurisdictionStatutory);
        self.market_yield_bps = market_yield_bps;
        self.rated_or_synthetic_spread_bps = rated_or_synthetic_spread_bps;
        self
    }

    pub fn with_marginal_tax_source(mut self, source: WaccFieldSource) -> Self {
        if self.marginal_tax_bps.is_some() {
            self.marginal_tax_source = Some(source);
        }
        self
    }
}

// ── Public API ────────────────────────────────────────────────────────────────

/// Classify business economics from sector/industry/asset hints.
pub fn classify_business(
    sector_name: Option<&str>,
    industry_name: Option<&str>,
    sector_key: Option<&str>,
    industry_key: Option<&str>,
    asset_not_equity: bool,
) -> BusinessClass {
    if asset_not_equity {
        return BusinessClass::NotEligible;
    }
    let sector = [sector_name.unwrap_or(""), sector_key.unwrap_or("")]
        .join(" ")
        .to_ascii_lowercase();
    let industry = [industry_name.unwrap_or(""), industry_key.unwrap_or("")]
        .join(" ")
        .to_ascii_lowercase();
    let blob = format!("{sector} {industry}");

    // Closed world: only explicit policy tables route to a model.
    // Priority: not-eligible shells → financial float → operating → unclassified fail.
    if is_not_eligible_equity_text(&blob) {
        return BusinessClass::NotEligible;
    }
    if is_financial_services_text(&blob) {
        return BusinessClass::FinancialServices;
    }
    if is_operating_non_financial_text(&sector, &industry, &blob) {
        return BusinessClass::OperatingNonFinancial;
    }
    BusinessClass::Unclassified
}

/// Human-readable reason for UI / logs when classification is not operable.
pub fn classification_unavailable_reason(class: BusinessClass) -> Option<&'static str> {
    match class {
        BusinessClass::Unclassified => Some(
            "business class unclassified: sector/industry missing or not in policy tables — valuation refused (no FCFF fallback)",
        ),
        BusinessClass::NotEligible => {
            Some("valuation not eligible for this asset class (ETF/fund/crypto/REIT shell)")
        }
        BusinessClass::OperatingNonFinancial | BusinessClass::FinancialServices => None,
    }
}

fn contains_any(hay: &str, keys: &[&str]) -> bool {
    keys.iter().any(|k| hay.contains(k))
}

fn is_not_eligible_equity_text(blob: &str) -> bool {
    const KEYS: &[&str] = &[
        "exchange traded",
        "etf",
        " etf",
        "closed-end fund",
        "closed end fund",
        "mutual fund",
        "money market",
        "cryptocurrency",
        "crypto ",
        " digital currency",
        "reit",
        "real estate investment trust",
        "mortgage reit",
        "equity reit",
    ];
    contains_any(blob, KEYS)
}

fn is_financial_services_text(blob: &str) -> bool {
    // Float / book-equity economics — residual income only. Never FCFF on OCF−PPE.
    // Do **not** use bare "health" (pharma/devices stay operating when matched there).
    const KEYS: &[&str] = &[
        "financial services",
        "financials",
        "financial",
        "insurance",
        "insur",
        "bank",
        "banks",
        "capital markets",
        "asset management",
        "credit services",
        "mortgage finance",
        "reinsurance",
        "life insurance",
        "property & casualty",
        "property and casualty",
        "property casualty",
        "diversified financial",
        "financial conglomerate",
        "savings & loan",
        "thrift",
        "brokerage",
        "investment banking",
        "specialty insurance",
        "p&c",
        "healthcare plans",
        "health care plans",
        "healthcare-plans",
        "health-care-plans",
        "managed care",
        "managed-care",
        "health insurance",
        "medical insurance",
        "insurance brokers",
        "insurance-brokers",
        "credit card",
        "consumer finance",
        "shell companies", // SPACs / financial shells — not FCFF industrials
    ];
    contains_any(blob, KEYS)
}

/// GICS-style operating (non-financial) coverage. Both sector-level and industry tokens.
/// Unlisted tokens → Unclassified (fail closed).
fn is_operating_non_financial_text(sector: &str, industry: &str, blob: &str) -> bool {
    // Clear GICS / Yahoo sector buckets (when present).
    const OPERATING_SECTORS: &[&str] = &[
        "technology",
        "information technology",
        "industrials",
        "industrial",
        "consumer cyclical",
        "consumer defensive",
        "consumer staples",
        "consumer discretionary",
        "energy",
        "utilities",
        "basic materials",
        "materials",
        "communication services",
        "communication",
        "telecommunications",
    ];
    if contains_any(sector, OPERATING_SECTORS) {
        return true;
    }

    // Healthcare sector is split: managed care is financial; equipment/pharma/biotech operate.
    if sector.contains("healthcare") || sector.contains("health care") {
        if is_financial_services_text(industry) || is_financial_services_text(blob) {
            return false;
        }
        // Sector-only "Healthcare" without industry is ambiguous → not operating here.
        if industry.trim().is_empty() {
            return false;
        }
        const HEALTH_OPERATING: &[&str] = &[
            "drug",
            "pharma",
            "biotech",
            "biotechnology",
            "device",
            "devices",
            "diagnostics",
            "medical instruments",
            "medical devices",
            "medical care",
            "medical distribution",
            "health information",
            "health care equipment",
            "healthcare equipment",
            "hospitals",
            "medical facilities",
            "tools & diagnostics",
            "tools and diagnostics",
        ];
        return contains_any(industry, HEALTH_OPERATING) || contains_any(blob, HEALTH_OPERATING);
    }

    // Industry / name tokens when sector is missing or non-standard.
    const OPERATING_INDUSTRY: &[&str] = &[
        "software",
        "semiconductor",
        "semiconductors",
        "hardware",
        "computer",
        "internet content",
        "internet retail",
        "it services",
        "information technology services",
        "electronic",
        "aerospace",
        "defense",
        "airlines",
        "railroad",
        "trucking",
        "logistics",
        "machinery",
        "construction",
        "building products",
        "real estate services",
        "property management",
        "engineering",
        "waste management",
        "farming",
        "agriculture",
        "auto manufacturers",
        "auto parts",
        "automobiles",
        "restaurants",
        "apparel",
        "footwear",
        "lodging",
        "leisure",
        "entertainment",
        "packaging",
        "tobacco",
        "beverages",
        "food products",
        "confectioners",
        "household products",
        "personal products",
        "discount stores",
        "department stores",
        "specialty retail",
        "oil & gas",
        "oil and gas",
        "oil gas",
        "thermal coal",
        "uranium",
        "renewable",
        "solar",
        "electric utilities",
        "gas utilities",
        "water utilities",
        "independent power",
        "diversified utilities",
        "chemicals",
        "specialty chemicals",
        "steel",
        "aluminum",
        "copper",
        "gold",
        "silver",
        "other industrial metals",
        "other precious metals",
        "coking coal",
        "lumber",
        "paper",
        "building materials",
        "telecom",
        "telecommunications",
        "media",
        "publishing",
        "broadcasting",
        "advertising",
        "interactive media",
    ];
    contains_any(industry, OPERATING_INDUSTRY) || contains_any(blob, OPERATING_INDUSTRY)
}

/// Compute multi-scenario intrinsic value with model-family routing.
pub fn compute(
    fundamentals: &FundamentalSnapshot,
    fcf_history: &[FcfPoint],
    market_price_cents: Option<i64>,
    source: &str,
) -> Result<DcfAnalysis, String> {
    compute_with_params(
        fundamentals,
        fcf_history,
        market_price_cents,
        &MarketParams::default_usd(),
        source,
        false,
    )
}

pub fn compute_with_params(
    fundamentals: &FundamentalSnapshot,
    fcf_history: &[FcfPoint],
    market_price_cents: Option<i64>,
    market_params: &MarketParams,
    source: &str,
    asset_not_equity: bool,
) -> Result<DcfAnalysis, String> {
    let class = classify_business(
        fundamentals.sector_name.as_deref(),
        fundamentals.industry_name.as_deref(),
        fundamentals.sector_key.as_deref(),
        fundamentals.industry_key.as_deref(),
        asset_not_equity,
    );
    match class {
        BusinessClass::NotEligible | BusinessClass::Unclassified => {
            Err(classification_unavailable_reason(class)
                .unwrap_or("valuation unavailable")
                .into())
        }
        BusinessClass::FinancialServices => {
            residual_income(fundamentals, market_price_cents, market_params, source)
        }
        BusinessClass::OperatingNonFinancial => fcff_wacc(
            fundamentals,
            fcf_history,
            market_price_cents,
            market_params,
            source,
        ),
    }
}

/// Financials-only path when no FCF series is required (fundamentals refresh).
pub fn compute_from_fundamentals(
    fundamentals: &FundamentalSnapshot,
    market_price_cents: Option<i64>,
    source: &str,
) -> Result<DcfAnalysis, String> {
    compute_with_params(
        fundamentals,
        &[],
        market_price_cents,
        &MarketParams::default_usd(),
        source,
        false,
    )
}

// ── Residual income (financial services) ──────────────────────────────────────

fn residual_income(
    fundamentals: &FundamentalSnapshot,
    market_price_cents: Option<i64>,
    market_params: &MarketParams,
    source: &str,
) -> Result<DcfAnalysis, String> {
    let shares = fundamentals
        .shares_outstanding
        .filter(|&s| s > 0)
        .map(|s| s as f64)
        .ok_or_else(|| "share count is missing".to_string())?;
    let bvps_cents = resolve_book_value_per_share_cents(fundamentals, market_price_cents)
        .ok_or_else(|| "book equity is missing".to_string())?;
    let book0 = (bvps_cents as f64 / 100.0) * shares;
    if !book0.is_finite() || book0 <= 0.0 {
        return Err("book equity is not positive".into());
    }
    let roe0_bps = fundamentals
        .return_on_equity_bps
        .filter(|&r| r > 0 && r < 10_000)
        .ok_or_else(|| "return on equity is missing or invalid".to_string())?;
    let retention_bps = fundamentals
        .retention_bps
        .filter(|&retention| (0..=10_000).contains(&retention))
        .ok_or_else(|| "retention/payout is missing or invalid".to_string())?;

    let (re_base, beta_source, beta_provisional) = cost_of_equity_bps(fundamentals, market_params)
        .map_err(|error| format!("cost of equity unavailable: {error:?}"))?;
    let retention = retention_bps as f64 / 10_000.0;
    let fade_years = PROJECTION_YEARS;

    let bear = ri_scenario(
        book0,
        shares,
        roe0_bps.saturating_sub(ROE_BEAR_HAIRCUT_BPS).max(100),
        re_base + COE_SCENARIO_BAND_BPS,
        retention * 0.9,
        fade_years,
    )
    .ok_or_else(|| "bear residual income invalid".to_string())?;
    let base = ri_scenario(book0, shares, roe0_bps, re_base, retention, fade_years)
        .ok_or_else(|| "base residual income invalid".to_string())?;
    let bull = ri_scenario(
        book0,
        shares,
        roe0_bps.saturating_add(ROE_BULL_BOOST_BPS).min(9_000),
        (re_base - COE_SCENARIO_BAND_BPS).max(market_params.rf_bps + 50),
        retention.min(0.85),
        fade_years,
    )
    .ok_or_else(|| "bull residual income invalid".to_string())?;

    let wacc_inputs = WaccInputProvenance {
        market_cap: WaccFieldSource::Reported,
        beta: beta_source,
        total_debt: WaccFieldSource::NotApplicable,
        total_cash: WaccFieldSource::NotApplicable,
        cost_of_debt: WaccFieldSource::NotApplicable,
        tax_rate: WaccFieldSource::NotApplicable,
        wacc_clamped: beta_provisional || market_params.provisional,
    };
    let mut reasons = vec![
        "model=residual_income_equity".into(),
        "business_class=financial_services".into(),
        format!("retention_source=reported:{}bps", retention_bps),
        "terminal_roe_fades_to_cost_of_equity".into(),
        "scenario_stress=growth_and_discount_rate".into(),
    ];
    if market_params.provisional {
        reasons.push("market_params=provisional".into());
    }
    if wacc_inputs.point_estimate_unreliable() {
        reasons.push("point_estimate=unreliable".into());
    }
    let shares_u = fundamentals.shares_outstanding.filter(|&s| s > 0);

    Ok(DcfAnalysis {
        bear_intrinsic_value_cents: bear,
        base_intrinsic_value_cents: base,
        bull_intrinsic_value_cents: bull,
        wacc_bps: re_base,
        base_growth_bps: ((roe0_bps as f64 / 10_000.0) * retention * 10_000.0).round() as i32,
        net_debt_dollars: 0,
        wacc_inputs: wacc_inputs.clone(),
        source: source.to_string(),
        engine_version: ENGINE_VERSION.into(),
        model_policy_version: MODEL_POLICY_VERSION.into(),
        business_class: BusinessClass::FinancialServices,
        model: ValuationModel::ResidualIncomeEquity,
        discount_rate_kind: DiscountRateKind::CostOfEquity,
        stable_growth_bps: market_params
            .stable_growth_bps()
            .min(re_base - GORDON_RATE_EPSILON_BPS),
        book_value_per_share_cents: Some(bvps_cents),
        roe0_bps: Some(roe0_bps),
        reason_codes: reasons,
        diagnostics: DcfDiagnostics {
            latest_fcf_dollars: None,
            latest_ocf_dollars: None,
            fcf_run_rate_dollars: None,
            shares_outstanding: shares_u,
            cost_of_equity_bps: Some(re_base),
            cost_of_debt_bps: None,
            after_tax_cost_of_debt_bps: None,
            equity_weight_bps: Some(10_000),
            debt_weight_bps: Some(0),
            fcf_years: vec![],
            fcf_annual_dollars: vec![],
            point_estimate_unreliable: wacc_inputs.point_estimate_unreliable(),
            scenario_stress: "growth_and_discount_rate".into(),
            capex_imputed_years: vec![],
            wacc_bear_bps: Some(re_base + COE_SCENARIO_BAND_BPS),
            wacc_bull_bps: Some((re_base - COE_SCENARIO_BAND_BPS).max(market_params.rf_bps + 50)),
            provisional_wacc_uplift_bps: Some(0),
            fcf_run_rate_normalized: false,
            valuation_driver: "residual_income".into(),
            latest_revenue_dollars: None,
            normalized_fcff_dollars: None,
            normalized_ocf_margin_bps: None,
            normalized_capex_intensity_bps: None,
            normalized_after_tax_interest_margin_bps: None,
            capex_spike_years: vec![],
            driver_regime: "financial_services".into(),
            growth_dispersion_bps: None,
            growth_driver: "roe_retention".into(),
            driver_input_fingerprint: None,
            driver_provenance: vec![
                format!("source={source}"),
                "model=residual_income_equity".into(),
            ],
        },
    })
}

fn ri_scenario(
    book0: f64,
    shares: f64,
    roe0_bps: i32,
    re_bps: i32,
    retention: f64,
    fade_years: i32,
) -> Option<i64> {
    if book0 <= 0.0 || shares <= 0.0 || re_bps <= 0 {
        return None;
    }
    let re = re_bps as f64 / 10_000.0;
    let roe0 = roe0_bps as f64 / 10_000.0;
    // Competitive long-run: ROE fades to cost of equity ⇒ terminal residual income = 0.
    // V0 = B0 + Σ PV((ROE_t − r_e) × B_{t−1}).
    let roe_stable = re;
    let mut book = book0;
    let mut pv_ri = 0.0;
    for t in 1..=fade_years {
        let w = t as f64 / fade_years as f64;
        let roe_t = roe0 * (1.0 - w) + roe_stable * w;
        let excess = (roe_t - re) * book;
        pv_ri += excess / (1.0 + re).powi(t);
        book *= 1.0 + roe_t * retention;
        if !book.is_finite() || book <= 0.0 {
            return None;
        }
    }
    let equity = book0 + pv_ri;
    if !equity.is_finite() || equity <= 0.0 {
        return None;
    }
    Some(((equity / shares) * 100.0).round() as i64)
}

fn resolve_book_value_per_share_cents(
    fundamentals: &FundamentalSnapshot,
    market_price_cents: Option<i64>,
) -> Option<i64> {
    if let Some(bvps) = fundamentals.book_value_per_share_cents.filter(|&v| v > 0) {
        return Some(bvps);
    }
    // Derive from price / P/B when both available.
    let price = market_price_cents.filter(|&p| p > 0)? as f64 / 100.0;
    let pb = fundamentals.price_to_book_hundredths.filter(|&p| p > 0)? as f64 / 100.0;
    if pb <= 0.0 {
        return None;
    }
    let bvps = price / pb;
    if !bvps.is_finite() || bvps <= 0.0 {
        return None;
    }
    Some((bvps * 100.0).round() as i64)
}

// ── FCFF + WACC (operating non-financial) ─────────────────────────────────────

#[derive(Debug, Clone)]
struct DriverPoint {
    year: i32,
    revenue_dollars: f64,
    fcff_margin_bps: i32,
    ocf_margin_bps: i32,
    capex_intensity_bps: i32,
    after_tax_interest_margin_bps: i32,
    revenue_growth_bps: Option<i32>,
    capex_spike: bool,
    acquisition_contaminated: bool,
}

#[derive(Debug, Clone)]
struct DriverModelInputs {
    latest_revenue_dollars: f64,
    normalized_fcff_dollars: f64,
    base_growth_bps: i32,
    bear_growth_bps: i32,
    bull_growth_bps: i32,
    base_fcff_margin_bps: i32,
    bear_fcff_margin_bps: i32,
    bull_fcff_margin_bps: i32,
    normalized_ocf_margin_bps: i32,
    normalized_capex_intensity_bps: i32,
    normalized_after_tax_interest_margin_bps: i32,
    capex_spike_years: Vec<i32>,
    acquisition_contaminated_growth_years: Vec<i32>,
    driver_regime: String,
    growth_dispersion_bps: i32,
    growth_fade_exponent: f64,
    tax_defaulted: bool,
}

/// Build a driver-consistent FCFF path from aligned annual operating data.
///
/// The old path used a normalized FCF level together with raw endpoint FCF CAGR.
/// That combination is internally contradictory for CapEx-cycle businesses such
/// as AMZN. This policy normalizes operating cash-flow and CapEx margins first,
/// then grows revenue and derives FCFF from the normalized margin.
fn driver_model_inputs(history: &[FcfPoint]) -> Option<DriverModelInputs> {
    let mut points = Vec::new();
    for point in history {
        let (Some(ocf), Some(capex), Some(revenue)) = (
            point.operating_cash_flow_dollars,
            point.capital_expenditure_dollars,
            point.revenue_dollars,
        ) else {
            continue;
        };
        if !ocf.is_finite()
            || !capex.is_finite()
            || !revenue.is_finite()
            || capex < 0.0
            || revenue <= 0.0
        {
            continue;
        }
        let Some(interest) = point.interest_expense_dollars else {
            continue;
        };
        let Some(tax_bps) = point.tax_rate_bps else {
            // The bridge is an explicit FCFF identity.  Missing annual interest
            // or effective-tax evidence is unavailable, not a zero/default.
            continue;
        };
        let interest = interest.abs();
        let tax_bps = tax_bps.clamp(0, 5_000);
        let fcff = ocf + interest * (1.0 - tax_bps as f64 / 10_000.0) - capex;
        let fcff_margin_bps = ((fcff / revenue) * 10_000.0).round() as i32;
        let ocf_margin_bps = ((ocf / revenue) * 10_000.0).round() as i32;
        let capex_intensity_bps = ((capex / revenue) * 10_000.0).round() as i32;
        let after_tax_interest_margin_bps =
            ((interest * (1.0 - tax_bps as f64 / 10_000.0) / revenue) * 10_000.0).round() as i32;
        if !fcff.is_finite() {
            continue;
        }
        let acquisition_contaminated = point
            .acquisition_investment_dollars
            .filter(|value| value.is_finite())
            .is_some_and(|acquisition| {
                acquisition.abs() * 10_000.0 >= revenue * MATERIAL_ACQUISITION_REVENUE_BPS as f64
            });
        points.push((
            point.year,
            revenue,
            fcff_margin_bps,
            ocf_margin_bps,
            capex_intensity_bps,
            after_tax_interest_margin_bps,
            false,
            acquisition_contaminated,
        ));
    }
    if points.len() < 3 {
        return None;
    }
    points.sort_by_key(|point| point.0);

    let mut driver_points: Vec<DriverPoint> = Vec::with_capacity(points.len());
    for (
        index,
        (
            year,
            revenue,
            fcff_margin_bps,
            ocf_margin_bps,
            capex_intensity_bps,
            interest_margin_bps,
            _,
            acquisition_contaminated,
        ),
    ) in points.iter().enumerate()
    {
        let prior_intensities: Vec<i32> = points[..index].iter().map(|point| point.4).collect();
        let prior_capex_median = median_bps(&prior_intensities);
        let prior_was_spike = driver_points
            .last()
            .map(|point| point.capex_spike)
            .unwrap_or(false);
        let capex_spike = prior_intensities.len() >= 3
            && !prior_was_spike
            && *capex_intensity_bps as f64 > prior_capex_median as f64 * CAPEX_SPIKE_RATIO
            && *capex_intensity_bps >= prior_capex_median + CAPEX_SPIKE_MIN_ABS_BPS;
        let revenue_growth_bps = if index == 0 {
            None
        } else {
            let prior = points[index - 1].1;
            let growth = *revenue / prior - 1.0;
            growth
                .is_finite()
                .then_some((growth * 10_000.0).round() as i32)
        };
        driver_points.push(DriverPoint {
            year: *year,
            revenue_dollars: *revenue,
            fcff_margin_bps: *fcff_margin_bps,
            ocf_margin_bps: *ocf_margin_bps,
            capex_intensity_bps: *capex_intensity_bps,
            after_tax_interest_margin_bps: *interest_margin_bps,
            revenue_growth_bps,
            capex_spike,
            acquisition_contaminated: *acquisition_contaminated,
        });
    }

    let recent_start = driver_points.len().saturating_sub(DRIVER_RECENT_WINDOW);
    let recent_points = &driver_points[recent_start..];
    let recent_baseline: Vec<&DriverPoint> = recent_points
        .iter()
        .filter(|point| !point.capex_spike)
        .collect();
    let recent_baseline = if recent_baseline.len() >= 2 {
        recent_baseline
    } else {
        recent_points.iter().collect()
    };
    let prior_start = recent_start.saturating_sub(DRIVER_RECENT_WINDOW);
    let prior_points = &driver_points[prior_start..recent_start];
    let prior_baseline: Vec<&DriverPoint> = prior_points
        .iter()
        .filter(|point| !point.capex_spike)
        .collect();

    let acquisition_contaminated_growth_years: Vec<i32> = recent_points
        .iter()
        .filter(|point| point.revenue_growth_bps.is_some() && point.acquisition_contaminated)
        .map(|point| point.year)
        .collect();
    let latest_growth_is_acquisition_contaminated = recent_points
        .last()
        .is_some_and(|point| point.revenue_growth_bps.is_some() && point.acquisition_contaminated);
    let mut recent_growths: Vec<i32> = recent_points
        .iter()
        .filter(|point| !point.acquisition_contaminated)
        .filter_map(|point| point.revenue_growth_bps)
        .collect();
    let acquisition_growth_must_be_zero = !acquisition_contaminated_growth_years.is_empty()
        && (latest_growth_is_acquisition_contaminated || recent_growths.len() < 2);
    if recent_growths.len() < 2 && acquisition_contaminated_growth_years.is_empty() {
        recent_growths = driver_points
            .iter()
            .filter(|point| !point.acquisition_contaminated)
            .filter_map(|point| point.revenue_growth_bps)
            .collect();
    }
    if recent_baseline.len() < 2 || (recent_growths.len() < 2 && !acquisition_growth_must_be_zero) {
        return None;
    }

    let prior_growths: Vec<i32> = prior_points
        .iter()
        .filter(|point| !point.acquisition_contaminated)
        .filter_map(|point| point.revenue_growth_bps)
        .collect();
    let regime = if acquisition_growth_must_be_zero {
        DriverRegime::StableOperating
    } else {
        classify_driver_regime(&recent_growths, &prior_growths)
    };
    let use_cycle_blend = regime == DriverRegime::CyclicalOrTransition
        && prior_baseline.len() >= 2
        && prior_growths.len() >= 2;
    // Margin evidence keeps every aligned annual identity, including CapEx
    // expansion years. CapEx spike detection is useful for diagnostics and
    // component context, but deleting the cash outflow would overstate FCFF.
    let aligned_margin_points: Vec<&DriverPoint> = if use_cycle_blend {
        recent_points.iter().chain(prior_points.iter()).collect()
    } else {
        recent_points.iter().collect()
    };
    let margins: Vec<i32> = aligned_margin_points
        .iter()
        .map(|point| point.fcff_margin_bps)
        .collect();
    let ocf_margins: Vec<i32> = recent_baseline
        .iter()
        .map(|point| point.ocf_margin_bps)
        .collect();
    let capex_intensities: Vec<i32> = recent_baseline
        .iter()
        .map(|point| point.capex_intensity_bps)
        .collect();
    let interest_margins: Vec<i32> = recent_baseline
        .iter()
        .map(|point| point.after_tax_interest_margin_bps)
        .collect();
    let scenario_growths: Vec<i32> = if acquisition_growth_must_be_zero {
        vec![0, 0]
    } else if use_cycle_blend {
        recent_growths
            .iter()
            .copied()
            .chain(prior_growths.iter().copied())
            .collect()
    } else {
        recent_growths.clone()
    };
    let recent_ocf_margin_bps = median_bps(&ocf_margins);
    let recent_capex_intensity_bps = median_bps(&capex_intensities);
    let recent_interest_margin_bps = median_bps(&interest_margins);
    let (normalized_ocf_margin_bps, normalized_capex_intensity_bps, normalized_interest_margin_bps) =
        if use_cycle_blend {
            let prior_ocf: Vec<i32> = prior_baseline
                .iter()
                .map(|point| point.ocf_margin_bps)
                .collect();
            let prior_capex: Vec<i32> = prior_baseline
                .iter()
                .map(|point| point.capex_intensity_bps)
                .collect();
            let prior_interest: Vec<i32> = prior_baseline
                .iter()
                .map(|point| point.after_tax_interest_margin_bps)
                .collect();
            (
                blend_recent_prior(recent_ocf_margin_bps, median_bps(&prior_ocf)),
                blend_recent_prior(recent_capex_intensity_bps, median_bps(&prior_capex)),
                blend_recent_prior(recent_interest_margin_bps, median_bps(&prior_interest)),
            )
        } else {
            (
                recent_ocf_margin_bps,
                recent_capex_intensity_bps,
                recent_interest_margin_bps,
            )
        };
    // Preserve the annual identity before applying a robust statistic. Taking
    // independent component medians can synthesize a non-existent year and can
    // turn a cyclical issuer with a positive median annual FCFF margin negative.
    let base_fcff_margin_bps = median_bps(&margins);
    let scenario_bear_margin_bps = quantile_bps(&margins, 0.25).min(base_fcff_margin_bps);
    let scenario_bull_margin_bps = quantile_bps(&margins, 0.75).max(base_fcff_margin_bps);
    let scenario_bear_growth_bps = quantile_bps(&scenario_growths, 0.25);
    let scenario_bull_growth_bps = quantile_bps(&scenario_growths, 0.75);
    let base_growth_bps = if acquisition_growth_must_be_zero {
        0
    } else if use_cycle_blend {
        blend_recent_prior(median_bps(&recent_growths), median_bps(&prior_growths))
            .clamp(scenario_bear_growth_bps, scenario_bull_growth_bps)
    } else {
        median_bps(&recent_growths)
    };
    let growth_dispersion_bps = if acquisition_growth_must_be_zero {
        0
    } else {
        quantile_bps(&recent_growths, 0.75).saturating_sub(quantile_bps(&recent_growths, 0.25))
    };
    let latest_revenue_dollars = driver_points.last()?.revenue_dollars;
    let normalized_fcff_dollars = latest_revenue_dollars * base_fcff_margin_bps as f64 / 10_000.0;
    if !normalized_fcff_dollars.is_finite() {
        return None;
    }

    Some(DriverModelInputs {
        latest_revenue_dollars,
        normalized_fcff_dollars,
        base_growth_bps,
        bear_growth_bps: scenario_bear_growth_bps,
        bull_growth_bps: scenario_bull_growth_bps,
        base_fcff_margin_bps,
        bear_fcff_margin_bps: scenario_bear_margin_bps,
        bull_fcff_margin_bps: scenario_bull_margin_bps,
        normalized_ocf_margin_bps,
        normalized_capex_intensity_bps,
        normalized_after_tax_interest_margin_bps: normalized_interest_margin_bps,
        capex_spike_years: driver_points
            .iter()
            .filter(|point| point.capex_spike)
            .map(|point| point.year)
            .collect(),
        acquisition_contaminated_growth_years,
        driver_regime: if acquisition_growth_must_be_zero {
            "acquisition_normalized".into()
        } else {
            regime.as_str().into()
        },
        growth_dispersion_bps,
        growth_fade_exponent: if acquisition_growth_must_be_zero {
            1.0
        } else {
            growth_fade_exponent(regime)
        },
        tax_defaulted: points.iter().any(|point| point.6),
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DriverRegime {
    SecularExpansion,
    StableOperating,
    CyclicalOrTransition,
}

impl DriverRegime {
    fn as_str(self) -> &'static str {
        match self {
            Self::SecularExpansion => "secular_expansion",
            Self::StableOperating => "stable_operating",
            Self::CyclicalOrTransition => "cyclical_or_transition",
        }
    }
}

fn classify_driver_regime(recent_growths: &[i32], prior_growths: &[i32]) -> DriverRegime {
    let recent_median = median_bps(recent_growths);
    let recent_positive_share = (recent_growths.iter().filter(|growth| **growth > 0).count()
        * 10_000
        / recent_growths.len()) as i32;
    let recent_dispersion =
        quantile_bps(recent_growths, 0.75).saturating_sub(quantile_bps(recent_growths, 0.25));
    let prior_median = median_bps(prior_growths);

    if recent_median >= 500
        && recent_positive_share >= 7_500
        && (prior_growths.is_empty() || recent_median >= prior_median)
        && (recent_dispersion <= 4_000
            || (recent_positive_share == 10_000
                && recent_median >= 1_000
                && recent_dispersion <= 8_000))
    {
        DriverRegime::SecularExpansion
    } else if recent_dispersion >= 2_000 || recent_positive_share <= 5_000 {
        DriverRegime::CyclicalOrTransition
    } else {
        DriverRegime::StableOperating
    }
}

fn growth_fade_exponent(regime: DriverRegime) -> f64 {
    match regime {
        DriverRegime::SecularExpansion => SECULAR_GROWTH_FADE_EXPONENT,
        DriverRegime::StableOperating | DriverRegime::CyclicalOrTransition => 1.0,
    }
}

fn blend_recent_prior(recent: i32, prior: i32) -> i32 {
    ((recent as i64 * 6 + prior as i64 * 4) / 10) as i32
}

fn median_bps(values: &[i32]) -> i32 {
    let mut sorted = values.to_vec();
    sorted.sort_unstable();
    if sorted.is_empty() {
        return 0;
    }
    let middle = sorted.len() / 2;
    if sorted.len() % 2 == 0 {
        ((sorted[middle - 1] as i64 + sorted[middle] as i64) / 2) as i32
    } else {
        sorted[middle]
    }
}

fn quantile_bps(values: &[i32], quantile: f64) -> i32 {
    let mut sorted = values.to_vec();
    sorted.sort_unstable();
    if sorted.is_empty() {
        return 0;
    }
    let index = (((sorted.len() - 1) as f64 * quantile).round() as usize).min(sorted.len() - 1);
    sorted[index]
}

fn discounted_driver_fcff(
    latest_revenue_dollars: f64,
    fcff_margin_bps: i32,
    stable_fcff_margin_bps: i32,
    revenue_growth_bps: i32,
    current_shares: f64,
    net_debt_dollars: i64,
    g_stable_bps: i32,
    wacc_bps: i32,
    growth_fade_exponent: f64,
) -> Option<i64> {
    if latest_revenue_dollars <= 0.0
        || current_shares <= 0.0
        || stable_fcff_margin_bps <= 0
        || revenue_growth_bps <= -10_000
        || g_stable_bps >= wacc_bps
    {
        return None;
    }
    let wacc = wacc_bps as f64 / 10_000.0;
    let g_near = revenue_growth_bps as f64 / 10_000.0;
    let g_stable = g_stable_bps as f64 / 10_000.0;
    let margin = fcff_margin_bps as f64 / 10_000.0;
    let mut revenue = latest_revenue_dollars;
    let mut pv = 0.0;
    for year in 1..=PROJECTION_YEARS {
        let fade = (year as f64 / PROJECTION_YEARS as f64).powf(growth_fade_exponent);
        let growth = g_near * (1.0 - fade) + g_stable * fade;
        revenue *= 1.0 + growth;
        // Scenario margins are near-term stresses. Fade all cases back to the
        // normalized base margin instead of treating a temporary bear margin
        // as a perpetual terminal condition. This permits economically valid
        // transition years with negative FCFF without creating an invalid
        // Gordon terminal value or silently dropping the company.
        let margin_t = margin * (1.0 - fade) + (stable_fcff_margin_bps as f64 / 10_000.0) * fade;
        let fcff = revenue * margin_t;
        if !fcff.is_finite() {
            return None;
        }
        pv += fcff / (1.0 + wacc).powi(year);
    }
    let terminal_margin = stable_fcff_margin_bps as f64 / 10_000.0;
    let terminal_fcff = revenue * (1.0 + g_stable) * terminal_margin;
    let terminal_value = terminal_fcff / (wacc - g_stable);
    let enterprise_value = pv + terminal_value / (1.0 + wacc).powi(PROJECTION_YEARS);
    let equity_value = enterprise_value - net_debt_dollars as f64;
    if !equity_value.is_finite() {
        return None;
    }
    // Common equity is bounded below by zero. Preserve a zero bear case when
    // net debt consumes the scenario enterprise value instead of dropping the
    // whole model as “invalid”; this is a capital-structure result, not a
    // price/analyst cap.
    Some(((equity_value.max(0.0) / current_shares) * 100.0).round() as i64)
}

fn fcff_driver_wacc(
    _fundamentals: &FundamentalSnapshot,
    fcf_history: &[FcfPoint],
    shares: f64,
    net_debt: i64,
    resolved: ResolvedWacc,
    market_params: &MarketParams,
    source: &str,
    drivers: DriverModelInputs,
) -> Result<DcfAnalysis, String> {
    if drivers.base_fcff_margin_bps <= 0 {
        return Err("non_positive_normalized_fcff: aligned annual FCFF evidence has a non-positive robust margin".into());
    }
    let g_stable_base = market_params
        .stable_growth_bps()
        .min(resolved.wacc_bps - GORDON_RATE_EPSILON_BPS)
        .max(MIN_STABLE_GROWTH_BPS);
    let rates_unreliable = resolved.inputs.point_estimate_unreliable();
    let (bear_band, bull_band) = if rates_unreliable {
        (
            WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS,
            WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS,
        )
    } else {
        (WACC_SCENARIO_BAND_BPS, WACC_SCENARIO_BAND_BPS)
    };
    let bear_wacc = resolved.wacc_bps + bear_band;
    let bull_wacc = (resolved.wacc_bps - bull_band)
        .max(market_params.rf_bps + 50)
        .max(g_stable_base + GORDON_RATE_EPSILON_BPS);
    let bear_g_stable = g_stable_base
        .min(bear_wacc - GORDON_RATE_EPSILON_BPS)
        .max(MIN_STABLE_GROWTH_BPS);
    let bull_g_stable = g_stable_base
        .min(bull_wacc - GORDON_RATE_EPSILON_BPS)
        .max(MIN_STABLE_GROWTH_BPS);

    let bear = discounted_driver_fcff(
        drivers.latest_revenue_dollars,
        drivers.bear_fcff_margin_bps,
        drivers.base_fcff_margin_bps,
        drivers.bear_growth_bps,
        shares,
        net_debt,
        bear_g_stable,
        bear_wacc,
        drivers.growth_fade_exponent,
    )
    .ok_or_else(|| "bear driver scenario invalid".to_string())?;
    let base = discounted_driver_fcff(
        drivers.latest_revenue_dollars,
        drivers.base_fcff_margin_bps,
        drivers.base_fcff_margin_bps,
        drivers.base_growth_bps,
        shares,
        net_debt,
        g_stable_base,
        resolved.wacc_bps,
        drivers.growth_fade_exponent,
    )
    .ok_or_else(|| "base driver scenario invalid".to_string())?;
    let bull = discounted_driver_fcff(
        drivers.latest_revenue_dollars,
        drivers.bull_fcff_margin_bps,
        drivers.base_fcff_margin_bps,
        drivers.bull_growth_bps,
        shares,
        net_debt,
        bull_g_stable,
        bull_wacc,
        drivers.growth_fade_exponent,
    )
    .ok_or_else(|| "bull driver scenario invalid".to_string())?;
    if bear > base || base > bull {
        return Err("driver scenarios not ordered after driver transition".to_string());
    }

    let capex_imputed_years: Vec<i32> = fcf_history
        .iter()
        .filter(|point| point.capex_imputed)
        .map(|point| point.year)
        .collect();
    let fcf_years: Vec<i32> = fcf_history.iter().map(|point| point.year).collect();
    let fcf_annual_dollars: Vec<i64> = fcf_history
        .iter()
        .map(|point| point.value_dollars.round() as i64)
        .collect();
    let mut reasons = vec![
        "model=fcff_wacc".into(),
        "business_class=operating_non_financial".into(),
        "valuation_driver=driver_based_fcff".into(),
        "fcff=ocf_plus_after_tax_interest_minus_capex".into(),
        format!(
            "growth=recent_driver_median:regime={}",
            drivers.driver_regime
        ),
        format!(
            "growth_fade=regime:{}_exponent:{:.2}",
            drivers.driver_regime, drivers.growth_fade_exponent
        ),
        format!("fcff_margin=median_aligned_annual:{}", drivers.base_fcff_margin_bps),
        format!(
            "fcff_component_diagnostics=ocf_margin:{};after_tax_interest_margin:{};capex_intensity:{}",
            drivers.normalized_ocf_margin_bps,
            drivers.normalized_after_tax_interest_margin_bps,
            drivers.normalized_capex_intensity_bps
        ),
        "scenario_stress=growth_margin_and_discount_rate".into(),
    ];
    if !drivers.capex_spike_years.is_empty() {
        reasons.push(format!(
            "capex=investment_spike_years:{}",
            drivers
                .capex_spike_years
                .iter()
                .map(ToString::to_string)
                .collect::<Vec<_>>()
                .join(",")
        ));
    }
    if !drivers.acquisition_contaminated_growth_years.is_empty() {
        reasons.push(format!(
            "growth=acquisition_contaminated_years_excluded:{}",
            drivers
                .acquisition_contaminated_growth_years
                .iter()
                .map(ToString::to_string)
                .collect::<Vec<_>>()
                .join(",")
        ));
    }
    reasons.extend(resolved.rate_reasons.iter().cloned());
    if market_params.provisional {
        reasons.push("market_params=provisional".into());
    }
    if rates_unreliable {
        reasons.push("point_estimate=unreliable".into());
        reasons.push(format!(
            "wacc_stress=asymmetric_provisional_bear+{bear_band}_bull=base_no_further_cheapening"
        ));
    }
    if resolved.provisional_wacc_uplift_bps > 0 {
        reasons.push(format!(
            "wacc=provisional_base_uplift:{}",
            resolved.provisional_wacc_uplift_bps
        ));
    }
    if !capex_imputed_years.is_empty() {
        reasons.push(format!(
            "capex=imputed_years:{}",
            capex_imputed_years
                .iter()
                .map(ToString::to_string)
                .collect::<Vec<_>>()
                .join(",")
        ));
    }
    if [bear, base, bull].iter().any(|value| *value == 0) {
        reasons.push("equity_value_floor=limited_liability".into());
    }

    Ok(DcfAnalysis {
        bear_intrinsic_value_cents: bear,
        base_intrinsic_value_cents: base,
        bull_intrinsic_value_cents: bull,
        wacc_bps: resolved.wacc_bps,
        base_growth_bps: drivers.base_growth_bps,
        net_debt_dollars: net_debt,
        wacc_inputs: resolved.inputs.clone(),
        source: source.to_string(),
        engine_version: ENGINE_VERSION.into(),
        model_policy_version: MODEL_POLICY_VERSION.into(),
        business_class: BusinessClass::OperatingNonFinancial,
        model: ValuationModel::FcffWacc,
        discount_rate_kind: DiscountRateKind::Wacc,
        stable_growth_bps: g_stable_base,
        book_value_per_share_cents: None,
        roe0_bps: None,
        reason_codes: reasons,
        diagnostics: DcfDiagnostics {
            latest_fcf_dollars: fcf_history
                .last()
                .map(|point| point.value_dollars.round() as i64),
            latest_ocf_dollars: fcf_history.last().and_then(|point| {
                point
                    .operating_cash_flow_dollars
                    .map(|value| value.round() as i64)
            }),
            fcf_run_rate_dollars: Some(drivers.normalized_fcff_dollars.round() as i64),
            shares_outstanding: Some(shares.round() as u64),
            cost_of_equity_bps: Some(resolved.cost_of_equity_bps),
            cost_of_debt_bps: Some(resolved.cost_of_debt_bps),
            after_tax_cost_of_debt_bps: Some(resolved.after_tax_cost_of_debt_bps),
            equity_weight_bps: Some(resolved.equity_weight_bps),
            debt_weight_bps: Some(resolved.debt_weight_bps),
            fcf_years,
            fcf_annual_dollars,
            point_estimate_unreliable: rates_unreliable,
            scenario_stress: "growth_margin_and_discount_rate".into(),
            capex_imputed_years,
            wacc_bear_bps: Some(bear_wacc),
            wacc_bull_bps: Some(bull_wacc),
            provisional_wacc_uplift_bps: Some(resolved.provisional_wacc_uplift_bps),
            fcf_run_rate_normalized: true,
            valuation_driver: "driver_based_fcff".into(),
            latest_revenue_dollars: Some(drivers.latest_revenue_dollars.round() as i64),
            normalized_fcff_dollars: Some(drivers.normalized_fcff_dollars.round() as i64),
            normalized_ocf_margin_bps: Some(drivers.normalized_ocf_margin_bps),
            normalized_capex_intensity_bps: Some(drivers.normalized_capex_intensity_bps),
            normalized_after_tax_interest_margin_bps: Some(
                drivers.normalized_after_tax_interest_margin_bps,
            ),
            capex_spike_years: drivers.capex_spike_years,
            growth_driver: format!("revenue_growth_median:{}", drivers.driver_regime),
            driver_regime: drivers.driver_regime,
            growth_dispersion_bps: Some(drivers.growth_dispersion_bps),
            driver_input_fingerprint: Some(driver_input_fingerprint(fcf_history)),
            driver_provenance: vec![
                format!("source={source}"),
                "annual_aligned=ocf,capex,revenue,interest,debt,effective_tax,marginal_tax".into(),
                "fcff=ocf_plus_after_tax_interest_minus_capex".into(),
            ],
        },
    })
}

fn fcff_wacc(
    fundamentals: &FundamentalSnapshot,
    fcf_history: &[FcfPoint],
    market_price_cents: Option<i64>,
    market_params: &MarketParams,
    source: &str,
) -> Result<DcfAnalysis, String> {
    if fcf_history.len() < 3 {
        return Err("need at least 3 annual free cash flow points".into());
    }

    let shares = fundamentals
        .shares_outstanding
        .filter(|&s| s > 0)
        .map(|s| s as f64)
        .ok_or_else(|| "share count is missing".to_string())?;
    let resolved = derive_wacc(
        fundamentals,
        fcf_history,
        market_price_cents,
        market_params,
        source,
    )?;
    let total_debt = fundamentals
        .total_debt_dollars
        .ok_or_else(|| {
            "fcff unavailable: total debt is missing; missing debt is not zero".to_string()
        })?
        .max(0);
    let total_cash = fundamentals.total_cash_dollars.unwrap_or(0).max(0);
    // Preserve net cash in the equity bridge; only the WACC debt weight
    // clamps negative net debt to zero. Treating net cash as zero would
    // silently destroy value for cash-rich operating companies.
    let net_debt = total_debt - total_cash;

    let drivers = driver_model_inputs(fcf_history).ok_or_else(|| {
        "fcff unavailable: at least three aligned annual OCF, CapEx, revenue, interest, and effective-tax driver rows are required".to_string()
    })?;
    return fcff_driver_wacc(
        fundamentals,
        fcf_history,
        shares,
        net_debt,
        resolved,
        market_params,
        source,
        drivers,
    );

    /*
     * The old FCF-level fallback intentionally remains below in history for
     * auditability only.  It is unreachable by design: operating valuation no
     * longer combines a normalized FCF level with a point FCF growth rate.
     */
    /*
    let (run_rate, fcf_normalized) = ...
    let g_stable = market_params.stable_growth_bps().min(DEFAULT_RF_BPS); // will be re-clamped vs WACC below

    let g_stable_base = g_stable
        .min(resolved.wacc_bps - GORDON_RATE_EPSILON_BPS)
        .max(MIN_STABLE_GROWTH_BPS);
    let g_near = raw_g_near.clamp(
        g_stable_base - MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS,
        g_stable_base + MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS,
    );

    // Scenario paths: fade growth AND stress WACC.
    // Provisional path: base already includes debt-scaled WACC uplift (see derive_wacc).
    //   bear: +additional band from that base
    //   bull: +0 bps on WACC (do not cheapen further a known-soft base; growth still varies)
    let rates_unreliable = resolved.inputs.point_estimate_unreliable();
    let (bear_band, bull_band) = if rates_unreliable {
        (
            WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS,
            WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS,
        )
    } else {
        (WACC_SCENARIO_BAND_BPS, WACC_SCENARIO_BAND_BPS)
    };
    let bear_near = (g_near - 400).max(-1_200);
    let bull_near = (g_near + 400).min(2_400);
    let bear_wacc = resolved.wacc_bps + bear_band;
    let bull_wacc = (resolved.wacc_bps - bull_band)
        .max(market_params.rf_bps + 50)
        .max(g_stable_base + GORDON_RATE_EPSILON_BPS);
    let bear_g_stable = g_stable_base
        .min(bear_wacc - GORDON_RATE_EPSILON_BPS)
        .max(MIN_STABLE_GROWTH_BPS);
    let bull_g_stable = g_stable_base
        .min(bull_wacc - GORDON_RATE_EPSILON_BPS)
        .max(MIN_STABLE_GROWTH_BPS);

    let bear = discounted_fcff_fade(
        run_rate,
        shares,
        net_debt,
        bear_near,
        bear_g_stable,
        bear_wacc,
    )
    .ok_or_else(|| "bear scenario invalid".to_string())?;
    let base = discounted_fcff_fade(
        run_rate,
        shares,
        net_debt,
        g_near,
        g_stable_base,
        resolved.wacc_bps,
    )
    .ok_or_else(|| "base scenario invalid".to_string())?;
    let bull = discounted_fcff_fade(
        run_rate,
        shares,
        net_debt,
        bull_near,
        bull_g_stable,
        bull_wacc,
    )
    .ok_or_else(|| "bull scenario invalid".to_string())?;

    let capex_imputed_years: Vec<i32> = fcf_history
        .iter()
        .filter(|p| p.capex_imputed)
        .map(|p| p.year)
        .collect();

    let mut reasons = vec![
        "model=fcff_wacc".into(),
        "business_class=operating_non_financial".into(),
        "growth=recent_window_fade_to_stable".into(),
        "scenario_stress=growth_and_discount_rate".into(),
    ];
    if g_near != raw_g_near {
        reasons.push(format!(
            "growth=recent_window_robustified:raw={raw_g_near}:used={g_near}"
        ));
    }
    if fcf_normalized {
        reasons.push("fcf_run_rate=recent_window_average".into());
    } else {
        reasons.push("fcf_run_rate=latest_positive".into());
    }
    if market_params.provisional {
        reasons.push("market_params=provisional".into());
    }
    if rates_unreliable {
        reasons.push("point_estimate=unreliable".into());
        // Explicit: bull WACC band is 0 so we do not further cheapen a soft base.
        reasons.push(format!(
            "wacc_stress=asymmetric_provisional_bear+{bear_band}_bull=base_no_further_cheapening"
        ));
    }
    if resolved.provisional_wacc_uplift_bps > 0 {
        reasons.push(format!(
            "wacc=provisional_base_uplift:{}",
            resolved.provisional_wacc_uplift_bps
        ));
    }
    if !capex_imputed_years.is_empty() {
        reasons.push(format!(
            "capex=imputed_years:{}",
            capex_imputed_years
                .iter()
                .map(|y| y.to_string())
                .collect::<Vec<_>>()
                .join(",")
        ));
    }
    if [bear, base, bull].iter().any(|value| *value == 0) {
        reasons.push("equity_value_floor=limited_liability".into());
    }

    let fcf_years: Vec<i32> = fcf_history.iter().map(|p| p.year).collect();
    let fcf_annual_dollars: Vec<i64> = fcf_history
        .iter()
        .map(|p| p.value_dollars.round() as i64)
        .collect();

    Ok(DcfAnalysis {
        bear_intrinsic_value_cents: bear,
        base_intrinsic_value_cents: base,
        bull_intrinsic_value_cents: bull,
        wacc_bps: resolved.wacc_bps,
        base_growth_bps: g_near,
        net_debt_dollars: net_debt,
        wacc_inputs: resolved.inputs.clone(),
        source: source.to_string(),
        engine_version: ENGINE_VERSION.into(),
        model_policy_version: MODEL_POLICY_VERSION.into(),
        business_class: BusinessClass::OperatingNonFinancial,
        model: ValuationModel::FcffWacc,
        discount_rate_kind: DiscountRateKind::Wacc,
        stable_growth_bps: g_stable_base,
        book_value_per_share_cents: fundamentals.book_value_per_share_cents,
        roe0_bps: fundamentals.return_on_equity_bps,
        reason_codes: reasons,
        diagnostics: DcfDiagnostics {
            latest_fcf_dollars: fcf_history
                .last()
                .map(|point| point.value_dollars.round() as i64),
            latest_ocf_dollars: fcf_history.last().and_then(|point| {
                point
                    .operating_cash_flow_dollars
                    .map(|value| value.round() as i64)
            }),
            fcf_run_rate_dollars: Some(run_rate.round() as i64),
            shares_outstanding: fundamentals.shares_outstanding.filter(|&s| s > 0),
            cost_of_equity_bps: Some(resolved.cost_of_equity_bps),
            cost_of_debt_bps: Some(resolved.cost_of_debt_bps),
            after_tax_cost_of_debt_bps: Some(resolved.after_tax_cost_of_debt_bps),
            equity_weight_bps: Some(resolved.equity_weight_bps),
            debt_weight_bps: Some(resolved.debt_weight_bps),
            fcf_years,
            fcf_annual_dollars,
            point_estimate_unreliable: rates_unreliable,
            scenario_stress: if rates_unreliable {
                "growth_and_discount_rate_asymmetric_provisional".into()
            } else {
                "growth_and_discount_rate".into()
            },
            capex_imputed_years,
            wacc_bear_bps: Some(bear_wacc),
            wacc_bull_bps: Some(bull_wacc),
            provisional_wacc_uplift_bps: Some(resolved.provisional_wacc_uplift_bps),
            fcf_run_rate_normalized: fcf_normalized,
            valuation_driver: "fcf_history_fade".into(),
            latest_revenue_dollars: None,
            normalized_fcff_dollars: None,
            normalized_ocf_margin_bps: None,
            normalized_capex_intensity_bps: None,
            normalized_after_tax_interest_margin_bps: None,
            capex_spike_years: vec![],
            driver_regime: "legacy_fcf_history".into(),
            growth_dispersion_bps: None,
            growth_driver: "fcf_endpoint_robustified".into(),
            driver_input_fingerprint: Some(driver_input_fingerprint(fcf_history)),
            driver_provenance: vec![
                format!("source={source}"),
                "fallback=fcf_history_fade".into(),
            ],
        },
    })*/
}

/// Latest contiguous positive FCF suffix (oldest → newest within the suffix).
///
/// Missing fiscal years end the window so sparse observations are not given the
/// same weight as consecutive annual reports.
fn recent_positive_fcf_window(history: &[FcfPoint]) -> Vec<&FcfPoint> {
    let Some(latest) = history.last().filter(|point| point.value_dollars > 0.0) else {
        return Vec::new();
    };
    let mut suffix = Vec::with_capacity(GROWTH_RECENT_WINDOW);
    let mut expected_year = latest.year;
    for point in history.iter().rev() {
        if suffix.len() == GROWTH_RECENT_WINDOW
            || point.value_dollars <= 0.0
            || point.year != expected_year
        {
            break;
        }
        suffix.push(point);
        expected_year = expected_year.saturating_sub(1);
    }
    suffix.reverse();
    suffix
}

/// FCFF run-rate from the recent contiguous positive window.
///
/// Default: equal-weight window average (mid-cycle). When the **latest** year is
/// substantially above that average (recovery / CapEx-trough step-up), blend
/// 50/50 latest and average so multi-year depression does not crush the run-rate
/// (VICR/INOD/AMZN-class) while still not taking a pure single-year peak.
/// Returns (run_rate_dollars, used_multi_year_normalization).
fn fcf_run_rate_dollars(history: &[FcfPoint]) -> Option<(f64, bool)> {
    let window = recent_positive_fcf_window(history);
    if window.is_empty() {
        return None;
    }
    if window.len() == 1 {
        return Some((window[0].value_dollars, false));
    }
    let sum: f64 = window.iter().map(|p| p.value_dollars).sum();
    let avg = sum / window.len() as f64;
    if !avg.is_finite() || avg <= 0.0 {
        return None;
    }
    let latest = window.last().map(|p| p.value_dollars).unwrap_or(avg);
    // Recovery step-up: latest > 125% of window mean → blend toward latest.
    let run = if latest > avg * 1.25 {
        0.5 * latest + 0.5 * avg
    } else {
        avg
    };
    if !run.is_finite() || run <= 0.0 {
        return None;
    }
    Some((run, true))
}

/// CAGR over the last up-to-GROWTH_RECENT_WINDOW positive FCF points (not full history).
fn recent_fcf_growth_bps(history: &[FcfPoint]) -> Option<i32> {
    let window = recent_positive_fcf_window(history);
    if window.len() < 2 {
        return None;
    }
    let first = window.first()?;
    let last = window.last()?;
    let years = (last.year - first.year).max(1) as f64;
    if first.value_dollars <= 0.0 {
        return None;
    }
    let cagr = (last.value_dollars / first.value_dollars).powf(1.0 / years) - 1.0;
    if !cagr.is_finite() {
        return None;
    }
    Some((cagr * 10_000.0).round() as i32)
}

fn discounted_fcff_fade(
    latest_fcf: f64,
    shares: f64,
    net_debt: i64,
    g_near_bps: i32,
    g_stable_bps: i32,
    wacc_bps: i32,
) -> Option<i64> {
    if latest_fcf <= 0.0 || shares <= 0.0 || g_stable_bps >= wacc_bps {
        return None;
    }
    let wacc = wacc_bps as f64 / 10_000.0;
    let g_near = g_near_bps as f64 / 10_000.0;
    let g_stable = g_stable_bps as f64 / 10_000.0;
    let mut projected = latest_fcf;
    let mut pv = 0.0;
    for year in 1..=PROJECTION_YEARS {
        let w = year as f64 / PROJECTION_YEARS as f64;
        let g = g_near * (1.0 - w) + g_stable * w;
        projected *= 1.0 + g;
        pv += projected / (1.0 + wacc).powi(year);
    }
    let terminal_cf = projected * (1.0 + g_stable);
    let terminal_value = terminal_cf / (wacc - g_stable);
    let enterprise = pv + terminal_value / (1.0 + wacc).powi(PROJECTION_YEARS);
    let equity = enterprise - net_debt as f64;
    if !equity.is_finite() {
        return None;
    }
    Some(((equity.max(0.0) / shares) * 100.0).round() as i64)
}

// ── Discount rates ────────────────────────────────────────────────────────────

struct ResolvedWacc {
    wacc_bps: i32,
    cost_of_equity_bps: i32,
    cost_of_debt_bps: i32,
    after_tax_cost_of_debt_bps: i32,
    equity_weight_bps: i32,
    debt_weight_bps: i32,
    provisional_wacc_uplift_bps: i32,
    inputs: WaccInputProvenance,
    rate_quality: crate::driver_resolution::EvidenceQuality,
    rate_reasons: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndustryBetaPolicyFile {
    policy_version: String,
    shrink: IndustryBetaShrinkWeights,
    default_prior: IndustryBetaDefaultPrior,
    entries: Vec<IndustryBetaPolicyEntry>,
    #[serde(default)]
    golden_cases: Vec<IndustryBetaGoldenCase>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndustryBetaShrinkWeights {
    company_weight_pct: i64,
    industry_weight_pct: i64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndustryBetaDefaultPrior {
    beta_millis: i32,
    through_cycle: bool,
    provisional: bool,
    entry_id: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndustryBetaPolicyEntry {
    id: String,
    #[serde(default)]
    industry_keys: Vec<String>,
    #[serde(default)]
    industry_name_contains: Vec<String>,
    #[serde(default)]
    sector_keys: Vec<String>,
    #[serde(default)]
    sector_name_contains: Vec<String>,
    beta_millis: i32,
    through_cycle: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndustryBetaGoldenCase {
    name: String,
    sector_name: Option<String>,
    industry_name: Option<String>,
    sector_key: Option<String>,
    industry_key: Option<String>,
    company_beta_millis: Option<i32>,
    rf_bps: i32,
    erp_bps: i32,
    expected_industry_beta_millis: i32,
    expected_entry_id: String,
    expected_through_cycle: bool,
    expected_industry_provisional: bool,
    expected_shrunk_beta_millis: i32,
    expected_cost_of_equity_bps: i32,
    pure_trailing_cost_of_equity_bps: Option<i32>,
    must_exceed_pure_trailing_coe: bool,
}

fn industry_beta_policy() -> &'static IndustryBetaPolicyFile {
    use std::sync::OnceLock;
    static POLICY: OnceLock<IndustryBetaPolicyFile> = OnceLock::new();
    POLICY.get_or_init(|| {
        serde_json::from_str(INDUSTRY_BETA_POLICY_JSON)
            .expect("industry-beta-policy-v1.json must parse")
    })
}

/// Resolve the versioned industry beta prior for sector/industry labels.
/// Unmapped text returns the default prior with provisional provenance.
pub fn resolve_industry_beta_prior(
    sector_name: Option<&str>,
    industry_name: Option<&str>,
    sector_key: Option<&str>,
    industry_key: Option<&str>,
) -> IndustryBetaPrior {
    let policy = industry_beta_policy();
    let sk = sector_key.unwrap_or("").trim().to_ascii_lowercase();
    let ik = industry_key.unwrap_or("").trim().to_ascii_lowercase();
    let sn = sector_name.unwrap_or("").trim().to_ascii_lowercase();
    let inn = industry_name.unwrap_or("").trim().to_ascii_lowercase();

    let matched = match_industry_beta_entry(policy, &sk, &ik, &sn, &inn);
    match matched {
        Some(entry) => IndustryBetaPrior {
            beta_millis: entry.beta_millis,
            entry_id: entry.id.clone(),
            through_cycle: entry.through_cycle,
            provisional: false,
            policy_version: policy.policy_version.clone(),
        },
        None => IndustryBetaPrior {
            beta_millis: policy.default_prior.beta_millis,
            entry_id: policy.default_prior.entry_id.clone(),
            through_cycle: policy.default_prior.through_cycle,
            provisional: policy.default_prior.provisional,
            policy_version: policy.policy_version.clone(),
        },
    }
}

fn match_industry_beta_entry<'a>(
    policy: &'a IndustryBetaPolicyFile,
    sector_key: &str,
    industry_key: &str,
    sector_name: &str,
    industry_name: &str,
) -> Option<&'a IndustryBetaPolicyEntry> {
    if !industry_key.is_empty() {
        for entry in &policy.entries {
            if entry
                .industry_keys
                .iter()
                .any(|key| key.eq_ignore_ascii_case(industry_key))
            {
                return Some(entry);
            }
        }
    }
    if !industry_name.is_empty() {
        for entry in &policy.entries {
            if entry
                .industry_name_contains
                .iter()
                .any(|token| industry_name.contains(&token.to_ascii_lowercase()))
            {
                return Some(entry);
            }
        }
    }
    if !sector_key.is_empty() {
        for entry in &policy.entries {
            if entry
                .sector_keys
                .iter()
                .any(|key| key.eq_ignore_ascii_case(sector_key))
            {
                return Some(entry);
            }
        }
    }
    if !sector_name.is_empty() {
        for entry in &policy.entries {
            if entry
                .sector_name_contains
                .iter()
                .any(|token| sector_name.contains(&token.to_ascii_lowercase()))
            {
                return Some(entry);
            }
        }
    }
    None
}

fn industry_beta_prior_for(fundamentals: &FundamentalSnapshot) -> IndustryBetaPrior {
    resolve_industry_beta_prior(
        fundamentals.sector_name.as_deref(),
        fundamentals.industry_name.as_deref(),
        fundamentals.sector_key.as_deref(),
        fundamentals.industry_key.as_deref(),
    )
}

fn cost_of_equity_bps(
    fundamentals: &FundamentalSnapshot,
    market_params: &MarketParams,
) -> Result<(i32, WaccFieldSource, bool), CostOfEquityResolutionError> {
    let resolved = resolve_cost_of_equity(fundamentals, market_params)?;
    Ok((
        resolved.cost_of_equity_bps,
        resolved.beta_source,
        resolved.provisional,
    ))
}

fn div_round_half_up_i128(numerator: i128, denominator: i128) -> Option<i128> {
    if numerator < 0 || denominator <= 0 {
        return None;
    }
    numerator
        .checked_add(denominator / 2)
        .map(|value| value / denominator)
}

/// Pure trailing CoE (company beta only) for diagnostics/tests — never a production path.
fn pure_trailing_cost_of_equity_bps(
    company_beta_millis: i32,
    market_params: &MarketParams,
) -> Result<i32, CostOfEquityResolutionError> {
    if market_params.rf_bps < 0 || market_params.erp_bps <= 0 || company_beta_millis <= 0 {
        return Err(CostOfEquityResolutionError::InvalidMarketParameters);
    }
    let equity_premium = div_round_half_up_i128(
        i128::from(company_beta_millis)
            .checked_mul(i128::from(market_params.erp_bps))
            .ok_or(CostOfEquityResolutionError::ArithmeticOverflow)?,
        1_000,
    )
    .and_then(|value| i32::try_from(value).ok())
    .ok_or(CostOfEquityResolutionError::ResultOutOfRange)?;
    let re = market_params
        .rf_bps
        .checked_add(equity_premium)
        .ok_or(CostOfEquityResolutionError::ArithmeticOverflow)?;
    let minimum = market_params
        .rf_bps
        .checked_add(50)
        .ok_or(CostOfEquityResolutionError::ArithmeticOverflow)?;
    Ok(re.max(minimum))
}

pub fn resolve_cost_of_equity(
    fundamentals: &FundamentalSnapshot,
    market_params: &MarketParams,
) -> Result<ResolvedCostOfEquity, CostOfEquityResolutionError> {
    if market_params.rf_bps < 0 || market_params.erp_bps <= 0 {
        return Err(CostOfEquityResolutionError::InvalidMarketParameters);
    }
    let prior = industry_beta_prior_for(fundamentals);
    let industry = i64::from(prior.beta_millis);
    let shrink = &industry_beta_policy().shrink;
    // Policy table owns weights; constants remain the documented 67/33 identity.
    debug_assert_eq!(shrink.company_weight_pct, BETA_COMPANY_WEIGHT_PCT);
    debug_assert_eq!(shrink.industry_weight_pct, BETA_INDUSTRY_WEIGHT_PCT);
    let (beta_millis, source, beta_provisional) = match fundamentals.beta_millis {
        Some(b) if b > 0 => {
            let company = i64::from(b);
            let weighted = company
                .checked_mul(shrink.company_weight_pct)
                .and_then(|value| {
                    industry
                        .checked_mul(shrink.industry_weight_pct)
                        .and_then(|industry_value| value.checked_add(industry_value))
                })
                .ok_or(CostOfEquityResolutionError::ArithmeticOverflow)?;
            (
                div_round_half_up_i128(i128::from(weighted), 100)
                    .and_then(|value| i64::try_from(value).ok())
                    .ok_or(CostOfEquityResolutionError::ResultOutOfRange)?,
                WaccFieldSource::IndustryShrink,
                // Unmapped default prior is provisional; mapped industry shrink is intentional.
                prior.provisional,
            )
        }
        // Missing company beta uses the industry prior (Bayesian estimate).
        // Unmapped default remains provisional; mapped prior is not weak noise.
        _ => (industry, WaccFieldSource::IndustryShrink, prior.provisional),
    };
    let equity_premium = div_round_half_up_i128(
        i128::from(beta_millis)
            .checked_mul(i128::from(market_params.erp_bps))
            .ok_or(CostOfEquityResolutionError::ArithmeticOverflow)?,
        1_000,
    )
    .and_then(|value| i32::try_from(value).ok())
    .ok_or(CostOfEquityResolutionError::ResultOutOfRange)?;
    let re = market_params
        .rf_bps
        .checked_add(equity_premium)
        .ok_or(CostOfEquityResolutionError::ArithmeticOverflow)?;
    let minimum = market_params
        .rf_bps
        .checked_add(50)
        .ok_or(CostOfEquityResolutionError::ArithmeticOverflow)?;
    let cost_of_equity_bps = re.max(minimum);
    let provisional = beta_provisional || market_params.provisional;
    Ok(ResolvedCostOfEquity {
        cost_of_equity_bps,
        beta_source: source,
        provisional,
        market_params_as_of_epoch: market_params.as_of_epoch,
        source_fingerprint: format!(
            "cost-of-equity/2|rf={}|erp={}|asof={:?}|beta_raw={:?}|beta_industry={}|beta_source={}|industry_beta_policy={}|entry={}|through_cycle={}|provisional={}",
            market_params.rf_bps,
            market_params.erp_bps,
            market_params.as_of_epoch,
            fundamentals.beta_millis,
            prior.beta_millis,
            source.as_str(),
            prior.policy_version,
            prior.entry_id,
            prior.through_cycle,
            provisional,
        ),
        industry_beta_millis: prior.beta_millis,
        through_cycle_prior: prior.through_cycle,
        industry_beta_policy_version: prior.policy_version,
        industry_beta_entry_id: prior.entry_id,
    })
}

fn resolve_market_cap(
    fundamentals: &FundamentalSnapshot,
    market_price_cents: Option<i64>,
) -> Option<(f64, WaccFieldSource)> {
    if let Some(cap) = fundamentals.market_cap_dollars.filter(|&c| c > 0) {
        return Some((cap as f64, WaccFieldSource::Reported));
    }
    let shares = fundamentals.shares_outstanding.filter(|&s| s > 0)? as f64;
    let price = market_price_cents.filter(|&p| p > 0)? as f64 / 100.0;
    let derived = price * shares;
    if !derived.is_finite() || derived <= 0.0 {
        return None;
    }
    Some((derived, WaccFieldSource::DerivedPriceTimesShares))
}

fn derive_wacc(
    fundamentals: &FundamentalSnapshot,
    fcf_history: &[FcfPoint],
    market_price_cents: Option<i64>,
    market_params: &MarketParams,
    source: &str,
) -> Result<ResolvedWacc, String> {
    let resolved_rates = crate::driver_resolution::resolve_rate_inputs_for_source(
        fcf_history,
        fundamentals.total_debt_dollars,
        market_params.rf_bps,
        source,
    )?;
    let (market_cap, market_cap_source) = resolve_market_cap(fundamentals, market_price_cents)
        .ok_or_else(|| "market cap is missing".to_string())?;
    let (cost_of_equity_bps, beta_source, beta_prov) =
        cost_of_equity_bps(fundamentals, market_params)
            .map_err(|error| format!("cost of equity unavailable: {error:?}"))?;

    let total_debt_source = if fundamentals.total_debt_dollars.is_some() {
        WaccFieldSource::Reported
    } else {
        WaccFieldSource::Unavailable
    };
    let total_cash_source = if fundamentals.total_cash_dollars.is_some() {
        WaccFieldSource::Reported
    } else {
        WaccFieldSource::AssumedZero
    };
    let total_debt = fundamentals
        .total_debt_dollars
        .ok_or_else(|| "fcff unavailable: total debt is missing".to_string())?
        .max(0) as f64;
    let total_cash = fundamentals.total_cash_dollars.unwrap_or(0).max(0) as f64;
    let net_debt = (total_debt - total_cash).max(0.0);
    let base = market_cap + net_debt;
    let equity_w = if base > 0.0 { market_cap / base } else { 1.0 };
    let debt_w = if base > 0.0 { net_debt / base } else { 0.0 };

    let (
        cost_of_debt_bps,
        cost_of_debt_source,
        tax_rate_bps,
        tax_rate_source,
        rate_quality,
        rate_reasons,
    ) = match resolved_rates {
        Some(rates) => (
            rates.cost_of_debt_bps,
            rates.cost_of_debt_source,
            rates.marginal_tax_bps,
            rates.marginal_tax_source,
            rates.quality,
            rates.reasons,
        ),
        None => (
            0,
            WaccFieldSource::NotApplicable,
            0,
            WaccFieldSource::NotApplicable,
            crate::driver_resolution::EvidenceQuality::Solid,
            vec![
                "cost_of_debt=not_applicable_explicit_zero_debt".into(),
                "marginal_tax=not_applicable_no_debt_tax_shield".into(),
            ],
        ),
    };

    let after_tax_debt =
        (cost_of_debt_bps as f64 * (1.0 - tax_rate_bps as f64 / 10_000.0)).round() as i32;
    let weighted = (equity_w * cost_of_equity_bps as f64) + (debt_w * after_tax_debt as f64);
    let soft_wacc_bps = weighted.round() as i32;

    let provisional_rate_evidence = market_params.provisional
        || rate_quality == crate::driver_resolution::EvidenceQuality::Provisional;
    let provisional_wacc_uplift_bps = if provisional_rate_evidence && debt_w > 0.0 {
        (PROVISIONAL_WACC_BASE_UPLIFT_BPS as f64
            * (debt_w / PROVISIONAL_UPLIFT_FULL_DEBT_WEIGHT).min(1.0))
        .round() as i32
    } else {
        0
    };
    let wacc_bps = soft_wacc_bps.saturating_add(provisional_wacc_uplift_bps);

    Ok(ResolvedWacc {
        wacc_bps,
        cost_of_equity_bps,
        cost_of_debt_bps,
        after_tax_cost_of_debt_bps: after_tax_debt,
        equity_weight_bps: (equity_w * 10_000.0).round() as i32,
        debt_weight_bps: (debt_w * 10_000.0).round() as i32,
        provisional_wacc_uplift_bps,
        inputs: WaccInputProvenance {
            market_cap: market_cap_source,
            beta: beta_source,
            total_debt: total_debt_source,
            total_cash: total_cash_source,
            cost_of_debt: cost_of_debt_source,
            tax_rate: tax_rate_source,
            // A short real evidence history is provisional; no policy CoD/tax
            // default is permitted.
            wacc_clamped: beta_prov
                || market_params.provisional
                || rate_quality == crate::driver_resolution::EvidenceQuality::Provisional
                || provisional_wacc_uplift_bps > 0,
        },
        rate_quality,
        rate_reasons,
    })
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;

    fn operating_fund() -> FundamentalSnapshot {
        FundamentalSnapshot {
            symbol: "TEST".into(),
            sector_name: Some("Technology".into()),
            industry_name: Some("Software".into()),
            market_cap_dollars: Some(1_000_000_000),
            shares_outstanding: Some(100_000_000),
            beta_millis: Some(1_100),
            total_debt_dollars: Some(100_000_000),
            total_cash_dollars: Some(50_000_000),
            ..Default::default()
        }
    }

    fn acgl_like_fund() -> FundamentalSnapshot {
        FundamentalSnapshot {
            symbol: "ACGL".into(),
            sector_name: Some("Financial Services".into()),
            industry_name: Some("Insurance - Property & Casualty".into()),
            market_cap_dollars: Some(36_000_000_000),
            shares_outstanding: Some(349_390_000),
            beta_millis: Some(292), // raw micro-beta that previously blew up FCFF
            return_on_equity_bps: Some(2_000),
            book_value_per_share_cents: Some(6_511),
            price_to_book_hundredths: Some(159),
            retention_bps: Some(7_000),
            ..Default::default()
        }
    }

    fn amzn_driver_fcf() -> Vec<FcfPoint> {
        vec![
            FcfPoint::new(2022, -16_893_000_000.0)
                .with_operating_drivers(
                    46_752_000_000.0,
                    63_645_000_000.0,
                    513_983_000_000.0,
                    Some(2_367_000_000.0),
                    Some(2_100),
                )
                .with_rate_resolution_inputs(Some(190_000_000_000.0), Some(2_100), None, None),
            FcfPoint::new(2023, 32_217_000_000.0)
                .with_operating_drivers(
                    84_946_000_000.0,
                    52_729_000_000.0,
                    574_785_000_000.0,
                    Some(3_182_000_000.0),
                    Some(1_896),
                )
                .with_rate_resolution_inputs(Some(210_000_000_000.0), Some(2_000), None, None),
            FcfPoint::new(2024, 32_878_000_000.0)
                .with_operating_drivers(
                    115_877_000_000.0,
                    82_999_000_000.0,
                    637_959_000_000.0,
                    Some(2_406_000_000.0),
                    Some(1_350),
                )
                .with_rate_resolution_inputs(Some(235_540_004_864.0), Some(1_900), None, None),
            FcfPoint::new(2025, 7_695_000_000.0)
                .with_operating_drivers(
                    139_514_000_000.0,
                    131_819_000_000.0,
                    716_924_000_000.0,
                    Some(2_274_000_000.0),
                    Some(1_961),
                )
                .with_rate_resolution_inputs(Some(235_540_004_864.0), Some(1_900), None, None),
        ]
    }

    fn sample_fcf() -> Vec<FcfPoint> {
        vec![
            (2021, 80_000_000.0),
            (2022, 90_000_000.0),
            (2023, 100_000_000.0),
            (2024, 110_000_000.0),
        ]
        .into_iter()
        .map(|(year, fcf)| {
            FcfPoint::new(year, fcf)
                .with_operating_drivers(
                    fcf + 20_000_000.0,
                    20_000_000.0,
                    200_000_000.0,
                    Some(2_000_000.0),
                    Some(2_100),
                )
                .with_rate_resolution_inputs(Some(100_000_000.0), Some(2_100), None, None)
        })
        .collect()
    }

    #[test]
    fn material_acquisition_uses_zero_near_term_growth_not_a_refusal() {
        let history = sample_fcf()
            .into_iter()
            .map(|point| {
                if point.year == 2024 {
                    point.with_acquisition_investment(Some(23_000_000.0))
                } else {
                    point
                }
            })
            .collect::<Vec<_>>();

        let analysis = compute(&operating_fund(), &history, Some(1_000), "test")
            .expect("acquisition-normalized FCFF");
        assert_eq!(analysis.base_growth_bps, 0);
        assert_eq!(analysis.diagnostics.driver_regime, "acquisition_normalized");
    }

    #[test]
    fn historical_acquisition_excludes_only_its_growth_transition() {
        let history = vec![
            (2021, 200_000_000.0, None),
            (2022, 220_000_000.0, Some(30_000_000.0)),
            (2023, 242_000_000.0, None),
            (2024, 266_200_000.0, None),
            (2025, 292_820_000.0, None),
        ]
        .into_iter()
        .map(|(year, revenue, acquisition)| {
            FcfPoint::new(year, 80_000_000.0)
                .with_operating_drivers(
                    100_000_000.0,
                    25_000_000.0,
                    revenue,
                    Some(10_000_000.0),
                    Some(2_000),
                )
                .with_rate_resolution_inputs(Some(100_000_000.0), Some(2_100), None, None)
                .with_acquisition_investment(acquisition)
        })
        .collect::<Vec<_>>();

        let analysis = compute(&operating_fund(), &history, Some(1_000), "test")
            .expect("clean post-acquisition growth remains usable");
        assert!(analysis.base_growth_bps > 0);
        assert_ne!(analysis.diagnostics.driver_regime, "acquisition_normalized");
        assert!(analysis
            .reason_codes
            .iter()
            .any(|reason| { reason == "growth=acquisition_contaminated_years_excluded:2022" }));
    }

    #[test]
    fn mu_cycle_uses_median_aligned_fcff_margin_and_retains_negative_year() {
        let history = vec![
            FcfPoint::new(2023, -6_117_000_000.0)
                .with_operating_drivers(
                    1_559_000_000.0,
                    7_676_000_000.0,
                    15_540_000_000.0,
                    Some(388_000_000.0),
                    Some(313),
                )
                .with_rate_resolution_inputs(Some(13_330_000_000.0), Some(2_100), None, None),
            FcfPoint::new(2024, 121_000_000.0)
                .with_operating_drivers(
                    8_507_000_000.0,
                    8_386_000_000.0,
                    25_111_000_000.0,
                    Some(562_000_000.0),
                    Some(3_500),
                )
                .with_rate_resolution_inputs(Some(13_397_000_000.0), Some(2_100), None, None),
            FcfPoint::new(2025, 1_668_000_000.0)
                .with_operating_drivers(
                    17_525_000_000.0,
                    15_857_000_000.0,
                    37_378_000_000.0,
                    Some(477_000_000.0),
                    Some(1_164),
                )
                .with_rate_resolution_inputs(Some(14_577_000_000.0), Some(2_100), None, None),
        ];
        let fund = FundamentalSnapshot {
            symbol: "MU".into(),
            sector_name: Some("Technology".into()),
            industry_name: Some("Semiconductors".into()),
            market_cap_dollars: Some(959_177_950_000),
            shares_outstanding: Some(1_129_393_151),
            beta_millis: Some(1_200),
            total_debt_dollars: Some(14_577_000_000),
            total_cash_dollars: Some(12_000_000_000),
            ..Default::default()
        };

        let analysis = compute(&fund, &history, Some(83_398), "sec_edgar")
            .expect("MU has a positive robust aligned FCFF base");
        assert!(analysis.base_intrinsic_value_cents > 0);
        assert!(analysis.diagnostics.normalized_fcff_dollars.unwrap_or(0) > 0);
        assert!(analysis.diagnostics.fcf_annual_dollars[0] < 0);
    }

    #[test]
    fn amzn_driver_fcff_uses_operating_bridge_and_revenue_growth() {
        let fund = FundamentalSnapshot {
            symbol: "AMZN".into(),
            sector_name: Some("Consumer Cyclical".into()),
            industry_name: Some("Internet Retail".into()),
            market_cap_dollars: Some(2_574_493_679_616),
            shares_outstanding: Some(10_757_109_436),
            beta_millis: Some(1_461),
            total_debt_dollars: Some(235_540_004_864),
            total_cash_dollars: Some(143_088_992_256),
            ..Default::default()
        };
        let analysis = compute(&fund, &amzn_driver_fcf(), Some(23_977), "sec_edgar")
            .expect("AMZN driver path");

        assert_eq!(analysis.diagnostics.valuation_driver, "driver_based_fcff");
        assert_eq!(analysis.diagnostics.latest_fcf_dollars, Some(7_695_000_000));
        assert_eq!(
            analysis.diagnostics.normalized_fcff_dollars,
            Some(24_375_416_000)
        );
        assert_eq!(analysis.diagnostics.normalized_ocf_margin_bps, Some(1_478));
        assert_eq!(
            analysis.diagnostics.normalized_capex_intensity_bps,
            Some(1_238)
        );
        assert_eq!(analysis.diagnostics.capex_spike_years, vec![2025]);
        assert!(analysis.base_growth_bps > -900);
        assert_eq!(
            analysis.diagnostics.growth_driver,
            "revenue_growth_median:secular_expansion"
        );
        assert!(analysis.bear_intrinsic_value_cents <= analysis.base_intrinsic_value_cents);
        assert!(analysis.base_intrinsic_value_cents <= analysis.bull_intrinsic_value_cents);
        assert!(analysis
            .reason_codes
            .iter()
            .all(|reason| !reason.contains("analyst") && !reason.contains("calibration_target")));
    }

    #[test]
    fn driver_regime_uses_persistence_before_dispersion() {
        assert_eq!(
            classify_driver_regime(&[4_000, 5_000, 6_000, 7_000], &[1_000, 2_000]),
            DriverRegime::SecularExpansion
        );
        assert_eq!(
            classify_driver_regime(&[-3_000, 4_000, -2_000, 5_000], &[1_000, -1_000]),
            DriverRegime::CyclicalOrTransition
        );
        assert_eq!(
            growth_fade_exponent(DriverRegime::SecularExpansion),
            SECULAR_GROWTH_FADE_EXPONENT
        );
        assert_eq!(growth_fade_exponent(DriverRegime::StableOperating), 1.0);
    }

    #[test]
    fn classifier_acgl_is_financial() {
        let c = classify_business(
            Some("Financial Services"),
            Some("Insurance - Property & Casualty"),
            None,
            None,
            false,
        );
        assert_eq!(c, BusinessClass::FinancialServices);
    }

    /// Cigna / managed care: Yahoo sector Healthcare + industry Healthcare Plans
    /// must not run FCFF on float-like OCF (same failure mode as ACGL).
    #[test]
    fn classifier_cigna_healthcare_plans_is_financial() {
        let c = classify_business(
            Some("Healthcare"),
            Some("Healthcare Plans"),
            Some("healthcare"),
            Some("healthcare-plans"),
            false,
        );
        assert_eq!(c, BusinessClass::FinancialServices);
        // Pharma/devices stay operating FCFF (bare "health" must not match).
        let pharma = classify_business(
            Some("Healthcare"),
            Some("Drug Manufacturers - General"),
            Some("healthcare"),
            Some("drug-manufacturers-general"),
            false,
        );
        assert_eq!(pharma, BusinessClass::OperatingNonFinancial);
    }

    #[test]
    fn ci_like_managed_care_uses_residual_income_not_fcff() {
        let fund = FundamentalSnapshot {
            symbol: "CI".into(),
            sector_name: Some("Healthcare".into()),
            industry_name: Some("Healthcare Plans".into()),
            sector_key: Some("healthcare".into()),
            industry_key: Some("healthcare-plans".into()),
            market_cap_dollars: Some(80_000_000_000),
            shares_outstanding: Some(270_000_000),
            beta_millis: Some(600),
            return_on_equity_bps: Some(1_800),
            book_value_per_share_cents: Some(15_000), // $150 book
            price_to_book_hundredths: Some(193),
            retention_bps: Some(7_000),
            ..Default::default()
        };
        // Float-like OCF−CapEx series that would inflate FCFF (user-reported ~$733).
        let fake_float = vec![
            FcfPoint::new(2022, 8_000_000_000.0),
            FcfPoint::new(2023, 9_000_000_000.0),
            FcfPoint::new(2024, 10_000_000_000.0),
            FcfPoint::new(2025, 11_000_000_000.0),
        ];
        let a = compute(&fund, &fake_float, Some(28_969), "sec_edgar").expect("ri");
        assert_eq!(a.model, ValuationModel::ResidualIncomeEquity);
        assert_eq!(a.business_class, BusinessClass::FinancialServices);
        let base = a.base_intrinsic_value_cents as f64 / 100.0;
        // Residual income near book + excess ROE — not the $700+ FCFF mirage.
        assert!(
            base < 400.0,
            "CI-class residual income base ${base} still FCFF-like"
        );
        assert!(base > 100.0, "expected above book ballpark, got ${base}");
    }

    #[test]
    fn classifier_operating_tech() {
        let c = classify_business(Some("Technology"), Some("Software"), None, None, false);
        assert_eq!(c, BusinessClass::OperatingNonFinancial);
    }

    #[test]
    fn classifier_real_estate_services_is_operating_but_reit_is_not_eligible() {
        assert_eq!(
            classify_business(
                Some("Real Estate"),
                Some("Real Estate Services"),
                None,
                None,
                false
            ),
            BusinessClass::OperatingNonFinancial
        );
        assert_eq!(
            classify_business(Some("Real Estate"), Some("REIT"), None, None, false),
            BusinessClass::NotEligible
        );
    }

    #[test]
    fn classifier_etf_not_eligible() {
        let c = classify_business(None, None, None, None, true);
        assert_eq!(c, BusinessClass::NotEligible);
    }

    #[test]
    fn classifier_missing_sector_industry_is_unclassified_not_fcff_default() {
        let c = classify_business(None, None, None, None, false);
        assert_eq!(c, BusinessClass::Unclassified);
    }

    #[test]
    fn classifier_unknown_sector_is_unclassified() {
        let c = classify_business(
            Some("Intergalactic Mining Conglomerate"),
            Some("Moon Cheese Extraction"),
            Some("intergalactic"),
            Some("moon-cheese"),
            false,
        );
        assert_eq!(c, BusinessClass::Unclassified);
    }

    #[test]
    fn unclassified_compute_fails_without_absurd_fcff() {
        let fund = FundamentalSnapshot {
            symbol: "ZZZ".into(),
            sector_name: Some("Unknown Sector XYZ".into()),
            industry_name: Some("Mystery Widgets".into()),
            market_cap_dollars: Some(1_000_000_000),
            shares_outstanding: Some(100_000_000),
            beta_millis: Some(1_000),
            total_debt_dollars: Some(0),
            total_cash_dollars: Some(0),
            ..Default::default()
        };
        let fcf = sample_fcf();
        let err = compute(&fund, &fcf, Some(1_000), "test").unwrap_err();
        assert!(
            err.contains("unclassified") || err.contains("refused"),
            "expected closed-world refusal, got {err}"
        );
    }

    #[test]
    fn classifier_operating_sectors_covered() {
        for (sector, industry) in [
            ("Technology", "Software - Infrastructure"),
            ("Industrials", "Aerospace & Defense"),
            ("Consumer Cyclical", "Internet Retail"),
            ("Energy", "Oil & Gas E&P"),
            ("Utilities", "Utilities - Regulated Electric"),
            ("Basic Materials", "Chemicals"),
            ("Communication Services", "Telecom Services"),
            ("Consumer Defensive", "Packaged Foods"),
        ] {
            let c = classify_business(Some(sector), Some(industry), None, None, false);
            assert_eq!(
                c,
                BusinessClass::OperatingNonFinancial,
                "{sector} / {industry}"
            );
        }
    }

    #[test]
    fn acgl_uses_residual_income_not_fcff() {
        // Even with absurd OCF-like "FCF", financials must not use FCFF.
        let fake_float_fcf = vec![
            FcfPoint::new(2022, 3_800_000_000.0),
            FcfPoint::new(2023, 5_700_000_000.0),
            FcfPoint::new(2024, 6_600_000_000.0),
            FcfPoint::new(2025, 6_172_000_000.0),
        ];
        let a = compute(
            &acgl_like_fund(),
            &fake_float_fcf,
            Some(10_336),
            "sec_edgar",
        )
        .expect("ri");
        assert_eq!(a.model, ValuationModel::ResidualIncomeEquity);
        assert_eq!(a.business_class, BusinessClass::FinancialServices);
        assert_eq!(a.discount_rate_kind, DiscountRateKind::CostOfEquity);
        // Must not reproduce the ~$875 FCFF mirage.
        let base_dollars = a.base_intrinsic_value_cents as f64 / 100.0;
        assert!(
            base_dollars < 400.0,
            "residual income base ${base_dollars} still absurdly high"
        );
        // Book + finite excess ROE should clear book (~$65).
        assert!(base_dollars > 65.0);
        assert!(a.reason_codes.iter().any(|r| r.contains("residual_income")));
    }

    #[test]
    fn operating_fcff_ordered_scenarios() {
        let a = compute(&operating_fund(), &sample_fcf(), Some(1_000), "sec_edgar").expect("dcf");
        assert_eq!(a.model, ValuationModel::FcffWacc);
        assert!(a.bear_intrinsic_value_cents > 0);
        assert!(a.base_intrinsic_value_cents >= a.bear_intrinsic_value_cents);
        assert!(a.bull_intrinsic_value_cents >= a.base_intrinsic_value_cents);
        assert!(
            a.diagnostics
                .scenario_stress
                .contains("growth_margin_and_discount_rate"),
            "scenario_stress={}",
            a.diagnostics.scenario_stress
        );
        assert!(a.diagnostics.latest_fcf_dollars.is_some());
        assert!(a.diagnostics.cost_of_equity_bps.is_some());
        assert!(a.diagnostics.shares_outstanding.is_some());
        assert!(!a.diagnostics.fcf_annual_dollars.is_empty());
        // Policy defaults → not a trusted point estimate.
        assert!(a.diagnostics.point_estimate_unreliable);
        assert!(a
            .reason_codes
            .iter()
            .any(|r| r == "point_estimate=unreliable"));
    }

    #[test]
    fn net_debt_can_produce_visible_zero_equity_scenario_without_refusing_model() {
        let fund = FundamentalSnapshot {
            symbol: "DEBT".into(),
            sector_name: Some("Technology".into()),
            industry_name: Some("Software".into()),
            market_cap_dollars: Some(100_000_000_000),
            shares_outstanding: Some(1_000_000_000),
            total_debt_dollars: Some(500_000_000_000),
            total_cash_dollars: Some(0),
            ..Default::default()
        };
        let history = (2022..=2025)
            .map(|year| {
                FcfPoint::new(year, 1_000_000_000.0)
                    .with_operating_drivers(
                        2_000_000_000.0,
                        1_000_000_000.0,
                        10_000_000_000.0,
                        Some(100_000_000.0),
                        Some(2_100),
                    )
                    .with_rate_resolution_inputs(Some(500_000_000_000.0), Some(2_100), None, None)
            })
            .collect::<Vec<_>>();
        let analysis = compute(&fund, &history, Some(1_000), "test").expect("zero equity DCF");
        assert_eq!(analysis.base_intrinsic_value_cents, 0);
        assert!(analysis
            .reason_codes
            .iter()
            .any(|reason| reason == "equity_value_floor=limited_liability"));
    }

    #[test]
    fn wacc_stress_widens_scenario_band_vs_growth_only_shape() {
        // Bear WACC higher than base → bear value must sit below base even if growth equalized.
        let a = compute(&operating_fund(), &sample_fcf(), Some(1_000), "sec_edgar").expect("dcf");
        let span = a.bull_intrinsic_value_cents - a.bear_intrinsic_value_cents;
        // With provisional asymmetric stress + growth, band should be material relative to base.
        assert!(
            span as f64 / a.base_intrinsic_value_cents as f64 > 0.08,
            "expected wider scenario span with rate stress, span={span} base={}",
            a.base_intrinsic_value_cents
        );
    }

    #[test]
    fn provisional_wacc_stress_is_asymmetric_and_reaches_market_like_bear() {
        // Default path keeps a provisional rate estimate visibly stressed in the
        // bear case, with the documented debt-scaled provisional base uplift.
        let a = compute(&operating_fund(), &sample_fcf(), Some(1_000), "sec_edgar").expect("dcf");
        assert!(a.diagnostics.point_estimate_unreliable);
        let base_w = a.wacc_bps;
        let bear_w = a.diagnostics.wacc_bear_bps.expect("bear wacc");
        let bull_w = a.diagnostics.wacc_bull_bps.expect("bull wacc");
        assert_eq!(bear_w - base_w, WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS);
        // Bull must not cheapen further: same WACC as base (band = 0).
        assert_eq!(bull_w, base_w);
        assert_eq!(WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS, 0);
        let uplift = a.diagnostics.provisional_wacc_uplift_bps.unwrap_or(0);
        assert!(
            uplift > 0 && bear_w - base_w >= WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS,
            "expected explicit provisional rate stress, uplift={uplift} bear_band={}",
            bear_w - base_w
        );
        assert!(a.reason_codes.iter().any(|r| {
            r.contains("wacc_stress=asymmetric_provisional")
                && r.contains("bull=base_no_further_cheapening")
        }));
    }

    /// Pinned T-class snapshot with explicitly aligned operating and rate drivers.
    fn t_class_fund() -> FundamentalSnapshot {
        FundamentalSnapshot {
            symbol: "T".into(),
            sector_name: Some("Communication Services".into()),
            industry_name: Some("Telecom Services".into()),
            market_cap_dollars: Some(146_748_915_712),
            shares_outstanding: Some(6_948_338_835),
            beta_millis: Some(422),
            total_debt_dollars: Some(159_750_995_968),
            total_cash_dollars: Some(11_964_000_256),
            ..Default::default()
        }
    }

    fn t_class_driver_fcf() -> Vec<FcfPoint> {
        vec![
            FcfPoint::new(2021, 26_420_000_000.0),
            FcfPoint::new(2022, 12_397_000_000.0)
                .with_operating_drivers(
                    32_023_000_000.0,
                    -19_626_000_000.0,
                    120_741_000_000.0,
                    Some(6_108_000_000.0),
                    Some(2_100),
                )
                .with_rate_resolution_inputs(Some(154_679_000_000.0), Some(2_100), None, None),
            FcfPoint::new(2023, 20_460_000_000.0)
                .with_operating_drivers(
                    38_314_000_000.0,
                    -17_853_000_000.0,
                    122_428_000_000.0,
                    Some(6_704_000_000.0),
                    Some(2_130),
                )
                .with_rate_resolution_inputs(Some(154_899_000_000.0), Some(2_100), None, None),
            FcfPoint::new(2024, 18_510_000_000.0)
                .with_operating_drivers(
                    38_771_000_000.0,
                    -20_263_000_000.0,
                    122_336_000_000.0,
                    Some(6_759_000_000.0),
                    Some(2_660),
                )
                .with_rate_resolution_inputs(Some(140_923_000_000.0), Some(2_100), None, None),
            FcfPoint::new(2025, 19_440_000_000.0)
                .with_operating_drivers(
                    40_284_000_000.0,
                    -20_842_000_000.0,
                    125_648_000_000.0,
                    Some(6_804_000_000.0),
                    Some(1_340),
                )
                .with_rate_resolution_inputs(Some(155_043_000_000.0), Some(2_100), None, None),
        ]
    }

    #[test]
    fn t_class_base_moves_toward_weighted_analyst_without_clamp() {
        let a = compute(
            &t_class_fund(),
            &t_class_driver_fcf(),
            Some(2_112),
            "sec_edgar",
        )
        .expect("t dcf");
        assert_eq!(a.model, ValuationModel::FcffWacc);
        assert!(a.diagnostics.point_estimate_unreliable);
        assert_eq!(a.diagnostics.provisional_wacc_uplift_bps, Some(175));
        assert_eq!(
            a.wacc_inputs.cost_of_debt,
            WaccFieldSource::InterestOverAverageDebt
        );
        assert_eq!(
            a.wacc_inputs.tax_rate,
            WaccFieldSource::JurisdictionStatutory
        );
        assert!(
            a.reason_codes
                .iter()
                .all(|r| !r.starts_with("calibration_target=")),
            "Street is an external development metric, not runtime provenance: {:?}",
            a.reason_codes
        );

        assert!(
            a.bear_intrinsic_value_cents <= a.base_intrinsic_value_cents
                && a.base_intrinsic_value_cents <= a.bull_intrinsic_value_cents,
            "T scenarios must be ordered"
        );
    }

    #[test]
    fn fcff_does_not_clamp_intrinsic_to_price_or_street() {
        // Extremely high FCF must still be allowed to produce high intrinsic —
        // proves we did not add intrinsic/price or Street assignment clamps.
        let fund = FundamentalSnapshot {
            symbol: "RICH".into(),
            sector_name: Some("Technology".into()),
            industry_name: Some("Software".into()),
            market_cap_dollars: Some(50_000_000_000),
            shares_outstanding: Some(1_000_000_000),
            beta_millis: Some(1_000),
            total_debt_dollars: Some(5_000_000_000),
            total_cash_dollars: Some(20_000_000_000),
            ..Default::default()
        };
        let fat_fcf = vec![
            FcfPoint::new(2021, 40_000_000_000.0),
            FcfPoint::new(2022, 45_000_000_000.0),
            FcfPoint::new(2023, 50_000_000_000.0),
            FcfPoint::new(2024, 55_000_000_000.0),
        ]
        .into_iter()
        .map(|point| {
            point
                .with_operating_drivers(
                    65_000_000_000.0,
                    -15_000_000_000.0,
                    120_000_000_000.0,
                    Some(2_000_000_000.0),
                    Some(2_100),
                )
                .with_rate_resolution_inputs(Some(20_000_000_000.0), Some(2_100), None, None)
        })
        .collect::<Vec<_>>();
        let a = compute(&fund, &fat_fcf, Some(5_000), "test").expect("dcf");
        let base_dollars = a.base_intrinsic_value_cents as f64 / 100.0;
        assert!(
            base_dollars > 100.0,
            "expected unclamped high intrinsic, got ${base_dollars}"
        );
        // No reason code that implies price-multiple rejection.
        assert!(a
            .reason_codes
            .iter()
            .all(|r| !r.contains("intrinsic_price") && !r.contains("clamp_to_street")));
    }

    #[test]
    fn amzn_capex_trough_keeps_normalized_scenarios_ordered() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../shared/contracts/valuation-model-family.json"
        );
        let contract: ValuationContract =
            serde_json::from_str(&std::fs::read_to_string(path).expect("read valuation contract"))
                .expect("parse valuation contract");
        let fixture = contract
            .regression_fixtures
            .iter()
            .find(|fixture| fixture.name == "amzn_capex_trough_does_not_invert_fcff_scenarios")
            .expect("AMZN contract fixture");
        let inputs: ContractTInputs =
            serde_json::from_value(fixture.sampled_inputs.clone()).expect("parse AMZN inputs");
        let expected: ContractTExpected =
            serde_json::from_value(fixture.expected.clone()).expect("parse AMZN expected");
        let fund = FundamentalSnapshot {
            symbol: "AMZN".into(),
            sector_name: Some(inputs.sector_name.clone()),
            industry_name: Some(inputs.industry_name.clone()),
            market_cap_dollars: Some(inputs.market_cap_dollars),
            shares_outstanding: Some(inputs.shares_outstanding),
            beta_millis: Some(inputs.beta_millis),
            total_debt_dollars: Some(inputs.total_debt_dollars),
            total_cash_dollars: Some(inputs.total_cash_dollars),
            ..Default::default()
        };
        let fcf: Vec<FcfPoint> = inputs
            .fcf_annual_dollars
            .iter()
            .map(|point| {
                let by_year = |series: &[ContractFcfPoint]| {
                    series
                        .iter()
                        .find(|driver| driver.year == point.year)
                        .map(|driver| driver.value_dollars)
                };
                let mut value = FcfPoint::new(point.year, point.value_dollars);
                if let (Some(ocf), Some(capex), Some(revenue)) = (
                    by_year(&inputs.operating_cash_flow_annual_dollars),
                    by_year(&inputs.capital_expenditure_annual_dollars),
                    by_year(&inputs.revenue_annual_dollars),
                ) {
                    value = value.with_operating_drivers(
                        ocf,
                        capex,
                        revenue,
                        by_year(&inputs.interest_expense_annual_dollars),
                        by_year(&inputs.tax_rate_bps_annual).map(|bps| bps.round() as i32),
                    );
                    value = value.with_rate_resolution_inputs(
                        by_year(&inputs.total_debt_annual_dollars),
                        by_year(&inputs.marginal_tax_bps_annual).map(|bps| bps.round() as i32),
                        None,
                        None,
                    );
                }
                value
            })
            .collect();
        let analysis = compute(
            &fund,
            &fcf,
            Some((inputs.market_price_dollars * 100.0).round() as i64),
            "contract",
        )
        .expect("AMZN DCF");
        assert_eq!(
            analysis.diagnostics.latest_fcf_dollars,
            Some(expected.latest_fcf_dollars)
        );
        assert_eq!(
            analysis.diagnostics.fcf_run_rate_dollars,
            Some(expected.fcf_run_rate_dollars)
        );
        assert_eq!(
            analysis.diagnostics.valuation_driver,
            expected
                .valuation_driver
                .as_deref()
                .unwrap_or("fcf_history_fade")
        );
        assert!(
            analysis.bear_intrinsic_value_cents <= analysis.base_intrinsic_value_cents
                && analysis.base_intrinsic_value_cents <= analysis.bull_intrinsic_value_cents,
            "scenario inversion: bear={} base={} bull={} growth={}",
            analysis.bear_intrinsic_value_cents,
            analysis.base_intrinsic_value_cents,
            analysis.bull_intrinsic_value_cents,
            analysis.base_growth_bps
        );
    }

    #[test]
    fn fcf_run_rate_uses_recent_window_average() {
        // Flat-ish path: latest not > 1.25× mean → pure window average.
        let hist = vec![
            FcfPoint::new(2021, 20_000_000.0),
            FcfPoint::new(2022, 21_000_000.0),
            FcfPoint::new(2023, 22_000_000.0),
            FcfPoint::new(2024, 23_000_000.0),
        ]
        .into_iter()
        .map(|point| {
            point
                .with_operating_drivers(
                    30_000_000.0,
                    -5_000_000.0,
                    100_000_000.0,
                    Some(1_000_000.0),
                    Some(2_100),
                )
                .with_rate_resolution_inputs(Some(50_000_000.0), Some(2_100), None, None)
        })
        .collect::<Vec<_>>();
        let (run, normalized) = fcf_run_rate_dollars(&hist).expect("run");
        assert!(normalized);
        assert!(
            (run - 21_500_000.0).abs() < 1.0,
            "avg of four = 21.5M, got {run}"
        );
        let a = compute(&operating_fund(), &hist, Some(1_000), "test").expect("dcf");
        assert!(a.diagnostics.fcf_run_rate_normalized);
        assert_eq!(a.diagnostics.latest_fcf_dollars, Some(23_000_000));
        assert_eq!(a.diagnostics.fcf_run_rate_dollars, Some(25_790_000));
        assert_eq!(a.diagnostics.normalized_fcff_dollars, Some(25_790_000));
        assert!(a
            .reason_codes
            .iter()
            .any(|r| r == "fcff=ocf_plus_after_tax_interest_minus_capex"));
        assert!(!a
            .reason_codes
            .iter()
            .any(|r| r.starts_with("fcf_run_rate=")));
    }

    #[test]
    fn fcf_run_rate_blends_toward_latest_on_recovery_step_up() {
        // Latest is 2× window mean → recovery blend 50/50 latest and average.
        let hist = vec![
            FcfPoint::new(2021, 10_000_000.0),
            FcfPoint::new(2022, 20_000_000.0),
            FcfPoint::new(2023, 30_000_000.0),
            FcfPoint::new(2024, 40_000_000.0),
        ];
        // mean=25, latest=40 > 1.25*25 → run = 0.5*40 + 0.5*25 = 32.5
        let (run, normalized) = fcf_run_rate_dollars(&hist).expect("run");
        assert!(normalized);
        assert!(
            (run - 32_500_000.0).abs() < 1.0,
            "expected recovery blend 32.5M, got {run}"
        );
    }

    #[test]
    fn fcf_run_rate_uses_latest_contiguous_positive_suffix() {
        let hist = vec![
            FcfPoint::new(2021, 10_000_000.0),
            FcfPoint::new(2023, 30_000_000.0),
            FcfPoint::new(2024, 40_000_000.0),
            FcfPoint::new(2025, 50_000_000.0),
        ];
        let (run, normalized) = fcf_run_rate_dollars(&hist).expect("run");
        assert!(normalized);
        assert!(
            (run - 40_000_000.0).abs() < 1.0,
            "missing 2022 must break the averaging window; got {run}"
        );
    }

    #[test]
    fn explicit_rate_resolution_has_no_debt_weight_uplift() {
        let mut params = MarketParams::default_usd();
        params.provisional = false;
        let analysis = compute_with_params(
            &operating_fund(),
            &sample_fcf(),
            Some(1_000),
            &params,
            "test",
            false,
        )
        .expect("explicit rate inputs");
        assert_eq!(analysis.diagnostics.provisional_wacc_uplift_bps, Some(0));
        assert_eq!(
            analysis.wacc_inputs.cost_of_debt,
            WaccFieldSource::InterestOverAverageDebt
        );
        assert_eq!(
            analysis.wacc_inputs.tax_rate,
            WaccFieldSource::JurisdictionStatutory
        );
    }

    #[test]
    fn solid_rates_use_symmetric_wacc_band() {
        let mut params = MarketParams::default_usd();
        params.provisional = false;
        let a = compute_with_params(
            &operating_fund(),
            &sample_fcf(),
            Some(1_000),
            &params,
            "test",
            false,
        )
        .unwrap();
        assert!(!a.diagnostics.point_estimate_unreliable);
        assert_eq!(
            a.diagnostics.wacc_bear_bps.unwrap() - a.wacc_bps,
            WACC_SCENARIO_BAND_BPS
        );
    }

    #[test]
    fn poc_real_data_driver_wacc_resolution_solid_quality() {
        let mut params = MarketParams::default_usd();
        params.provisional = false;
        let fund = FundamentalSnapshot {
            symbol: "NVDA".into(),
            sector_name: Some("Technology".into()),
            industry_name: Some("Semiconductors".into()),
            market_cap_dollars: Some(3_000_000_000_000),
            shares_outstanding: Some(24_600_000_000),
            beta_millis: Some(1_680),
            total_debt_dollars: Some(11_000_000_000),
            total_cash_dollars: Some(34_000_000_000),
            ..Default::default()
        };
        let hist = amzn_driver_fcf(); // Reported operating drivers with interest & tax
        let a = compute_with_params(&fund, &hist, Some(12_200), &params, "sec_edgar", false)
            .expect("dcf computation");

        assert_eq!(
            a.wacc_inputs.cost_of_debt,
            WaccFieldSource::InterestOverAverageDebt
        );
        assert_eq!(
            a.wacc_inputs.tax_rate,
            WaccFieldSource::JurisdictionStatutory
        );
        assert!(!a.diagnostics.point_estimate_unreliable);
        assert!(!a.wacc_inputs.point_estimate_unreliable());
    }

    #[test]
    fn capex_imputed_years_surface_in_diagnostics() {
        let mut hist = sample_fcf();
        hist[1].capex_imputed = true; // 2022
        hist[2].capex_imputed = true; // 2023
        let a = compute(&operating_fund(), &hist, Some(1_000), "sec_edgar").expect("dcf");
        assert_eq!(a.diagnostics.capex_imputed_years, vec![2022, 2023]);
        assert!(a
            .reason_codes
            .iter()
            .any(|r| r.contains("capex=imputed_years:2022,2023")));
    }

    /// Provisional market parameters retain the documented debt-scaled uplift.
    #[test]
    fn levered_provisional_wacc_applies_full_debt_scaled_uplift() {
        let fund = FundamentalSnapshot {
            symbol: "T".into(),
            sector_name: Some("Communication Services".into()),
            industry_name: Some("Telecom Services".into()),
            // Small equity vs huge debt → raw D/(D+E) >> 40%.
            market_cap_dollars: Some(160_000_000_000),
            shares_outstanding: Some(7_170_000_000),
            beta_millis: Some(700),
            total_debt_dollars: Some(150_000_000_000),
            total_cash_dollars: Some(5_000_000_000),
            ..Default::default()
        };
        // T-scale FCF (not the tiny sample_fcf fixture).
        let fcf = (2021..=2024)
            .map(|year| {
                FcfPoint::new(year, 17_000_000_000.0)
                    .with_operating_drivers(
                        25_000_000_000.0,
                        8_000_000_000.0,
                        100_000_000_000.0,
                        Some(3_000_000_000.0),
                        Some(2_100),
                    )
                    .with_rate_resolution_inputs(Some(150_000_000_000.0), Some(2_100), None, None)
            })
            .collect::<Vec<_>>();
        let a = compute(&fund, &fcf, Some(2_300), "sec_edgar").expect("dcf");
        let dw = a.diagnostics.debt_weight_bps.expect("debt weight");
        assert!(dw >= 4_000);
        assert!(a.diagnostics.point_estimate_unreliable);
        assert_eq!(a.diagnostics.provisional_wacc_uplift_bps, Some(175));
        assert!(a
            .reason_codes
            .iter()
            .any(|reason| reason == "wacc=provisional_base_uplift:175"));
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ValuationContract {
        policy2_adoption: Policy2Adoption,
        regression_fixtures: Vec<ContractRegressionFixture>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Policy2Adoption {
        executable_surfaces: Vec<String>,
        deferred_surfaces: Vec<String>,
    }

    #[derive(Deserialize)]
    struct ContractRegressionFixture {
        name: String,
        #[serde(rename = "sampledInputs")]
        sampled_inputs: serde_json::Value,
        expected: serde_json::Value,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ContractTInputs {
        market_price_dollars: f64,
        shares_outstanding: u64,
        market_cap_dollars: u64,
        beta_millis: i32,
        total_debt_dollars: i64,
        total_cash_dollars: i64,
        sector_name: String,
        industry_name: String,
        fcf_annual_dollars: Vec<ContractFcfPoint>,
        #[serde(default)]
        operating_cash_flow_annual_dollars: Vec<ContractFcfPoint>,
        #[serde(default)]
        capital_expenditure_annual_dollars: Vec<ContractFcfPoint>,
        #[serde(default)]
        revenue_annual_dollars: Vec<ContractFcfPoint>,
        #[serde(default)]
        interest_expense_annual_dollars: Vec<ContractFcfPoint>,
        #[serde(default)]
        tax_rate_bps_annual: Vec<ContractFcfPoint>,
        #[serde(default)]
        total_debt_annual_dollars: Vec<ContractFcfPoint>,
        #[serde(default)]
        marginal_tax_bps_annual: Vec<ContractFcfPoint>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ContractFcfPoint {
        year: i32,
        value_dollars: f64,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ContractTExpected {
        model_policy_version: String,
        latest_fcf_dollars: i64,
        fcf_run_rate_dollars: i64,
        #[serde(default)]
        valuation_driver: Option<String>,
    }

    #[test]
    fn shared_t_contract_executes_against_windows_engine() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../shared/contracts/valuation-model-family.json"
        );
        let contract: ValuationContract =
            serde_json::from_str(&std::fs::read_to_string(path).expect("read valuation contract"))
                .expect("parse valuation contract");
        assert!(contract
            .policy2_adoption
            .executable_surfaces
            .iter()
            .any(|surface| surface == "windows"));
        assert!(contract
            .policy2_adoption
            .deferred_surfaces
            .iter()
            .any(|surface| surface == "desktop"));
        let fixture = contract
            .regression_fixtures
            .iter()
            .find(|fixture| fixture.name == "t_class_explicit_driver_fcff_no_analyst_calibration")
            .expect("T contract fixture");
        let inputs: ContractTInputs =
            serde_json::from_value(fixture.sampled_inputs.clone()).expect("parse T inputs");
        let expected: ContractTExpected =
            serde_json::from_value(fixture.expected.clone()).expect("parse T expected");
        let fund = FundamentalSnapshot {
            symbol: "T".into(),
            sector_name: Some(inputs.sector_name.clone()),
            industry_name: Some(inputs.industry_name.clone()),
            market_cap_dollars: Some(inputs.market_cap_dollars),
            shares_outstanding: Some(inputs.shares_outstanding),
            beta_millis: Some(inputs.beta_millis),
            total_debt_dollars: Some(inputs.total_debt_dollars),
            total_cash_dollars: Some(inputs.total_cash_dollars),
            ..Default::default()
        };
        let fcf: Vec<FcfPoint> = inputs
            .fcf_annual_dollars
            .iter()
            .map(|point| {
                let by_year = |series: &[ContractFcfPoint]| {
                    series
                        .iter()
                        .find(|driver| driver.year == point.year)
                        .map(|driver| driver.value_dollars)
                };
                let mut value = FcfPoint::new(point.year, point.value_dollars);
                if let (Some(ocf), Some(capex), Some(revenue)) = (
                    by_year(&inputs.operating_cash_flow_annual_dollars),
                    by_year(&inputs.capital_expenditure_annual_dollars),
                    by_year(&inputs.revenue_annual_dollars),
                ) {
                    value = value.with_operating_drivers(
                        ocf,
                        capex,
                        revenue,
                        by_year(&inputs.interest_expense_annual_dollars),
                        by_year(&inputs.tax_rate_bps_annual).map(|bps| bps.round() as i32),
                    );
                    value = value.with_rate_resolution_inputs(
                        by_year(&inputs.total_debt_annual_dollars),
                        by_year(&inputs.marginal_tax_bps_annual).map(|bps| bps.round() as i32),
                        None,
                        None,
                    );
                }
                value
            })
            .collect();
        let analysis = compute(
            &fund,
            &fcf,
            Some((inputs.market_price_dollars * 100.0).round() as i64),
            "contract",
        )
        .expect("contract valuation");
        assert_eq!(analysis.model_policy_version, expected.model_policy_version);
        assert_eq!(
            analysis.diagnostics.latest_fcf_dollars,
            Some(expected.latest_fcf_dollars)
        );
        assert_eq!(
            analysis.diagnostics.fcf_run_rate_dollars,
            Some(expected.fcf_run_rate_dollars)
        );
        assert!(analysis.base_intrinsic_value_cents > 0);
        assert!(analysis.wacc_inputs.point_estimate_unreliable());
        assert!(analysis
            .reason_codes
            .iter()
            .all(|reason| !reason.starts_with("calibration_target=")));
    }

    #[test]
    fn higher_rf_lowers_operating_value() {
        let mut low_rf = MarketParams::default_usd();
        low_rf.rf_bps = 300;
        low_rf.provisional = false;
        let mut high_rf = MarketParams::default_usd();
        high_rf.rf_bps = 600;
        high_rf.provisional = false;
        let lo = compute_with_params(
            &operating_fund(),
            &sample_fcf(),
            Some(1_000),
            &low_rf,
            "test",
            false,
        )
        .unwrap();
        let hi = compute_with_params(
            &operating_fund(),
            &sample_fcf(),
            Some(1_000),
            &high_rf,
            "test",
            false,
        )
        .unwrap();
        assert!(
            hi.base_intrinsic_value_cents < lo.base_intrinsic_value_cents,
            "higher rf should lower FCFF value"
        );
    }

    #[test]
    fn financials_without_book_fail_not_fcff_fallback() {
        let mut f = acgl_like_fund();
        f.book_value_per_share_cents = None;
        f.price_to_book_hundredths = None;
        let fake_fcf = sample_fcf();
        let err = compute(&f, &fake_fcf, Some(10_000), "test").unwrap_err();
        assert!(err.contains("book"), "expected missing book, got {err}");
    }

    #[test]
    fn financials_without_retention_fail_not_fcff_fallback() {
        let mut f = acgl_like_fund();
        f.retention_bps = None;
        let fake_fcf = sample_fcf();
        let err = compute(&f, &fake_fcf, Some(10_336), "test").unwrap_err();
        assert!(
            err.contains("retention") || err.contains("payout"),
            "expected missing retention refuse, got {err}"
        );
    }

    #[test]
    fn financials_without_roe_fail_not_fcff_fallback() {
        let mut f = acgl_like_fund();
        f.return_on_equity_bps = None;
        let fake_fcf = sample_fcf();
        let err = compute(&f, &fake_fcf, Some(10_336), "test").unwrap_err();
        assert!(
            err.contains("return on equity") || err.contains("equity"),
            "expected missing ROE refuse, got {err}"
        );
    }

    #[test]
    fn cost_of_equity_extremes_refuse_instead_of_saturating() {
        let fund = FundamentalSnapshot {
            symbol: "EXTREME".into(),
            sector_name: Some("Technology".into()),
            industry_name: Some("Software".into()),
            beta_millis: Some(i32::MAX),
            ..Default::default()
        };
        let params = MarketParams {
            rf_bps: i32::MAX,
            erp_bps: i32::MAX,
            as_of_epoch: None,
            provisional: false,
        };
        assert!(matches!(
            resolve_cost_of_equity(&fund, &params),
            Err(CostOfEquityResolutionError::ResultOutOfRange)
                | Err(CostOfEquityResolutionError::ArithmeticOverflow)
        ));
        let invalid = MarketParams {
            rf_bps: 400,
            erp_bps: 0,
            as_of_epoch: None,
            provisional: false,
        };
        assert_eq!(
            resolve_cost_of_equity(&fund, &invalid),
            Err(CostOfEquityResolutionError::InvalidMarketParameters)
        );
    }

    #[test]
    fn industry_beta_policy_version_is_embedded_and_matches_constant() {
        let policy = industry_beta_policy();
        assert_eq!(policy.policy_version, INDUSTRY_BETA_POLICY_VERSION);
        assert_eq!(policy.shrink.company_weight_pct, BETA_COMPANY_WEIGHT_PCT);
        assert_eq!(policy.shrink.industry_weight_pct, BETA_INDUSTRY_WEIGHT_PCT);
        assert_eq!(
            policy.default_prior.beta_millis,
            DEFAULT_INDUSTRY_BETA_MILLIS
        );
        assert!(policy.default_prior.provisional);
        assert!(!policy.entries.is_empty());
    }

    #[test]
    fn industry_beta_policy_golden_cases_exact_fixed_point() {
        let policy = industry_beta_policy();
        assert!(
            !policy.golden_cases.is_empty(),
            "policy must ship executable golden cases"
        );
        for case in &policy.golden_cases {
            let prior = resolve_industry_beta_prior(
                case.sector_name.as_deref(),
                case.industry_name.as_deref(),
                case.sector_key.as_deref(),
                case.industry_key.as_deref(),
            );
            assert_eq!(
                prior.beta_millis, case.expected_industry_beta_millis,
                "{} industry beta",
                case.name
            );
            assert_eq!(
                prior.entry_id, case.expected_entry_id,
                "{} entry",
                case.name
            );
            assert_eq!(
                prior.through_cycle, case.expected_through_cycle,
                "{} through_cycle",
                case.name
            );
            assert_eq!(
                prior.provisional, case.expected_industry_provisional,
                "{} provisional",
                case.name
            );

            let fund = FundamentalSnapshot {
                symbol: case.name.clone(),
                sector_name: case.sector_name.clone(),
                industry_name: case.industry_name.clone(),
                sector_key: case.sector_key.clone(),
                industry_key: case.industry_key.clone(),
                beta_millis: case.company_beta_millis,
                ..Default::default()
            };
            let params = MarketParams {
                rf_bps: case.rf_bps,
                erp_bps: case.erp_bps,
                as_of_epoch: None,
                provisional: false,
            };
            let resolved =
                resolve_cost_of_equity(&fund, &params).expect("cost of equity should resolve");
            assert_eq!(
                resolved.cost_of_equity_bps, case.expected_cost_of_equity_bps,
                "{} coe",
                case.name
            );
            assert_eq!(
                resolved.industry_beta_millis, case.expected_industry_beta_millis,
                "{} industry prior on resolved",
                case.name
            );
            assert_eq!(
                resolved.through_cycle_prior, case.expected_through_cycle,
                "{} through_cycle on resolved",
                case.name
            );
            assert_eq!(
                resolved.industry_beta_policy_version,
                INDUSTRY_BETA_POLICY_VERSION
            );
            assert!(
                resolved
                    .source_fingerprint
                    .contains(INDUSTRY_BETA_POLICY_VERSION),
                "{} fingerprint must cite policy version",
                case.name
            );
            assert!(
                resolved
                    .source_fingerprint
                    .contains(&format!("through_cycle={}", case.expected_through_cycle)),
                "{} fingerprint must cite through_cycle prior",
                case.name
            );
            // No price / target leakage in CoE fingerprint.
            assert!(!resolved.source_fingerprint.contains("price"));
            assert!(!resolved.source_fingerprint.contains("target"));

            if let Some(pure) = case.pure_trailing_cost_of_equity_bps {
                let company = case
                    .company_beta_millis
                    .expect("pure trailing requires company beta");
                let trailing =
                    pure_trailing_cost_of_equity_bps(company, &params).expect("pure trailing coe");
                assert_eq!(trailing, pure, "{} pure trailing", case.name);
                if case.must_exceed_pure_trailing_coe {
                    assert!(
                        resolved.cost_of_equity_bps > trailing,
                        "{} through-cycle CoE {} must exceed pure trailing {}",
                        case.name,
                        resolved.cost_of_equity_bps,
                        trailing
                    );
                }
            }

            // Reconstruct shrunk beta from identity and assert golden pin.
            let industry = i64::from(prior.beta_millis);
            let shrunk = match case.company_beta_millis {
                Some(b) if b > 0 => {
                    let weighted = i64::from(b) * BETA_COMPANY_WEIGHT_PCT
                        + industry * BETA_INDUSTRY_WEIGHT_PCT;
                    div_round_half_up_i128(i128::from(weighted), 100).unwrap() as i32
                }
                _ => prior.beta_millis,
            };
            assert_eq!(
                shrunk, case.expected_shrunk_beta_millis,
                "{} shrunk beta",
                case.name
            );
        }
    }

    #[test]
    fn dvn_class_coe_not_bond_like_from_low_trailing_beta_alone() {
        let fund = FundamentalSnapshot {
            symbol: "DVN".into(),
            sector_name: Some("Energy".into()),
            industry_name: Some("Oil & Gas E&P".into()),
            sector_key: Some("energy".into()),
            industry_key: Some("oil-gas-e-p".into()),
            beta_millis: Some(430),
            ..Default::default()
        };
        let params = MarketParams {
            rf_bps: 430,
            erp_bps: 450,
            as_of_epoch: None,
            provisional: false,
        };
        let resolved = resolve_cost_of_equity(&fund, &params).unwrap();
        let pure = pure_trailing_cost_of_equity_bps(430, &params).unwrap();
        assert!(resolved.through_cycle_prior);
        assert_eq!(resolved.industry_beta_entry_id, "oil_gas_ep");
        assert!(resolved.cost_of_equity_bps > pure);
        // Industry prior must be the elevated through-cycle table value, not sector 1.1.
        assert_eq!(resolved.industry_beta_millis, 1_500);
    }

    #[test]
    fn software_control_industry_prior_stable_within_policy() {
        let prior = resolve_industry_beta_prior(
            Some("Technology"),
            Some("Software - Infrastructure"),
            Some("technology"),
            Some("software-infrastructure"),
        );
        assert_eq!(prior.beta_millis, 1_200);
        assert!(!prior.through_cycle);
        assert!(!prior.provisional);
        assert_eq!(prior.entry_id, "software_technology");
    }
}
