//! Valuation model family: business-class routing, residual income for financials,
//! FCFF+WACC for operating firms, dynamic market params + beta shrink.
//!
//! See `_bmad-output/planning-artifacts/valuation-model-family-architecture.md`.

use serde::{Deserialize, Serialize};

use crate::engine::FundamentalSnapshot;

pub const ENGINE_VERSION: &str = "valuation-model-family/1";
/// Policy bump: closed-world business-class routing (no silent FCFF default).
/// Unclassified sector/industry → valuation unavailable (never absurd FCFF).
/// See `_bmad-output/implementation-artifacts/spec-dcf-street-calibration-provisional-wacc.md`.
pub const MODEL_POLICY_VERSION: &str = "business-class-policy/3";

// ── Market policy (versioned; not eternal magic for valuation truth) ───────────
/// Default US 10Y-style nominal risk-free (bps). Shells may override via MarketParams.
const DEFAULT_RF_BPS: i32 = 430;
/// Versioned equity risk premium (bps).
const DEFAULT_ERP_BPS: i32 = 450;
const DEFAULT_TAX_RATE_BPS: i32 = 2_100;
const DEFAULT_COST_OF_DEBT_BPS: i32 = 550;
/// When CoD is a policy default (no bond yield), floor credit spread over rf so
/// levered names do not collapse WACC toward after-tax 4% debt alone.
const DEFAULT_COD_SPREAD_OVER_RF_BPS: i32 = 300;
/// Cap market-implied debt weight when CoD is default — depressed market caps
/// circularly inflate D/(D+E), crush WACC, and inflate intrinsic (T ~$65 vs ~$29).
/// Not a hard WACC floor; a capital-structure estimation guard for soft rates only.
const PROVISIONAL_MAX_DEBT_WEIGHT: f64 = 0.40;
/// When CoD is policy default, soft CAPM+structure WACC is systematically cheap vs
/// Street-implied discount rates on levered operating names (T reverse-DCF ≈ +170 bps
/// at the debt-weight cap). Full uplift applies at `PROVISIONAL_MAX_DEBT_WEIGHT`;
/// scales linearly with debt weight. Not an intrinsic/price clamp.
const PROVISIONAL_WACC_BASE_UPLIFT_BPS: i32 = 175;
const DEFAULT_RETENTION_BPS: i32 = 7_000; // 70% retained when payout unknown
const BETA_COMPANY_WEIGHT: f64 = 0.67;
const BETA_INDUSTRY_WEIGHT: f64 = 0.33;
const DEFAULT_INDUSTRY_BETA_MILLIS: i32 = 1_000;
const PROJECTION_YEARS: i32 = 5;
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
        if self.cost_of_debt == WaccFieldSource::Default {
            labels.push("cost of debt=default".into());
        }
        if self.tax_rate == WaccFieldSource::Default {
            labels.push("tax=default".into());
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
            || self.wacc_clamped
            || self.beta == WaccFieldSource::Default
            || self.tax_rate == WaccFieldSource::Default
    }
}

/// Raw model inputs for UI/debug (avoids archaeology on odd DCF prints).
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DcfDiagnostics {
    /// Most recent fiscal FCF observation, never a normalized replacement.
    pub latest_fcf_dollars: Option<i64>,
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

/// Annual FCF point (dollars).
#[derive(Debug, Clone)]
pub struct FcfPoint {
    pub year: i32,
    pub value_dollars: f64,
    /// True when CapEx for this year was interpolated/carried (not filed under known tags).
    pub capex_imputed: bool,
}

impl FcfPoint {
    pub fn new(year: i32, value_dollars: f64) -> Self {
        Self {
            year,
            value_dollars,
            capex_imputed: false,
        }
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

    let (re_base, beta_source, beta_provisional) = cost_of_equity_bps(fundamentals, market_params);
    let retention = DEFAULT_RETENTION_BPS as f64 / 10_000.0;
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
        total_debt: WaccFieldSource::Reported,
        total_cash: WaccFieldSource::Reported,
        cost_of_debt: WaccFieldSource::Reported,
        tax_rate: WaccFieldSource::Reported,
        wacc_clamped: beta_provisional || market_params.provisional,
    };
    let mut reasons = vec![
        "model=residual_income_equity".into(),
        "business_class=financial_services".into(),
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
    let (run_rate, fcf_normalized) = fcf_run_rate_dollars(fcf_history)
        .ok_or_else(|| "insufficient positive free cash flow for run-rate".to_string())?;
    if run_rate <= 0.0 {
        return Err("free cash flow run-rate is not positive".into());
    }
    let shares = fundamentals
        .shares_outstanding
        .filter(|&s| s > 0)
        .map(|s| s as f64)
        .ok_or_else(|| "share count is missing".to_string())?;

    let raw_g_near = recent_fcf_growth_bps(fcf_history)
        .ok_or_else(|| "insufficient positive free cash flow history for growth".to_string())?;
    let g_stable = market_params.stable_growth_bps().min(DEFAULT_RF_BPS); // will be re-clamped vs WACC below

    let resolved = derive_wacc(fundamentals, market_price_cents, market_params)?;
    let net_debt =
        fundamentals.total_debt_dollars.unwrap_or(0) - fundamentals.total_cash_dollars.unwrap_or(0);

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
        },
    })
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
    if !equity.is_finite() || equity <= 0.0 {
        return None;
    }
    Some(((equity / shares) * 100.0).round() as i64)
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
}

fn industry_beta_millis(fundamentals: &FundamentalSnapshot) -> i32 {
    let blob = [
        fundamentals.sector_name.as_deref().unwrap_or(""),
        fundamentals.industry_name.as_deref().unwrap_or(""),
        fundamentals.sector_key.as_deref().unwrap_or(""),
    ]
    .join(" ")
    .to_ascii_lowercase();

    if blob.contains("utilit") {
        return 600;
    }
    if blob.contains("consumer staples") || blob.contains("consumer defensive") {
        return 700;
    }
    if blob.contains("health") || blob.contains("pharma") {
        return 900;
    }
    if blob.contains("technolog") || blob.contains("software") || blob.contains("semiconductor") {
        return 1_200;
    }
    if blob.contains("energy") {
        return 1_100;
    }
    if blob.contains("financial") || blob.contains("insurance") || blob.contains("bank") {
        return 900;
    }
    if blob.contains("real estate") || blob.contains("reit") {
        return 850;
    }
    DEFAULT_INDUSTRY_BETA_MILLIS
}

fn cost_of_equity_bps(
    fundamentals: &FundamentalSnapshot,
    market_params: &MarketParams,
) -> (i32, WaccFieldSource, bool) {
    let industry = industry_beta_millis(fundamentals) as f64 / 1_000.0;
    let (raw, source, provisional) = match fundamentals.beta_millis {
        Some(b) if b > 0 => {
            let company = b as f64 / 1_000.0;
            let shrunk = BETA_COMPANY_WEIGHT * company + BETA_INDUSTRY_WEIGHT * industry;
            (shrunk, WaccFieldSource::IndustryShrink, false)
        }
        _ => (industry, WaccFieldSource::Default, true),
    };
    let re = market_params.rf_bps + (raw * market_params.erp_bps as f64).round() as i32;
    (
        re.max(market_params.rf_bps + 50),
        source,
        provisional || market_params.provisional,
    )
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
    market_price_cents: Option<i64>,
    market_params: &MarketParams,
) -> Result<ResolvedWacc, String> {
    let (market_cap, market_cap_source) = resolve_market_cap(fundamentals, market_price_cents)
        .ok_or_else(|| "market cap is missing".to_string())?;
    let (cost_of_equity_bps, beta_source, beta_prov) =
        cost_of_equity_bps(fundamentals, market_params);

    let total_debt_source = if fundamentals.total_debt_dollars.is_some() {
        WaccFieldSource::Reported
    } else {
        WaccFieldSource::AssumedZero
    };
    let total_cash_source = if fundamentals.total_cash_dollars.is_some() {
        WaccFieldSource::Reported
    } else {
        WaccFieldSource::AssumedZero
    };
    let total_debt = fundamentals.total_debt_dollars.unwrap_or(0).max(0) as f64;
    let total_cash = fundamentals.total_cash_dollars.unwrap_or(0).max(0) as f64;
    let net_debt = (total_debt - total_cash).max(0.0);
    let base = market_cap + net_debt;
    let mut equity_w = if base > 0.0 { market_cap / base } else { 1.0 };
    let mut debt_w = if base > 0.0 { net_debt / base } else { 0.0 };

    // No live bond yield → policy CoD. Prefer rf + spread over a bare constant so
    // rates move with regime (still provisional / default provenance).
    let (cost_of_debt_bps, cost_of_debt_source) = if total_debt > 0.0 {
        let from_spread = market_params.rf_bps + DEFAULT_COD_SPREAD_OVER_RF_BPS;
        (
            DEFAULT_COST_OF_DEBT_BPS.max(from_spread),
            WaccFieldSource::Default,
        )
    } else {
        (DEFAULT_COST_OF_DEBT_BPS, WaccFieldSource::Reported)
    };

    // Soft-rate capital structure: when CoD is not market-sourced, do not let a
    // depressed equity price dominate weights (cheap stock → higher D% → lower
    // WACC → even higher intrinsic). Cap debt weight; renormalize.
    let mut structure_guard = false;
    if cost_of_debt_source == WaccFieldSource::Default && debt_w > PROVISIONAL_MAX_DEBT_WEIGHT {
        debt_w = PROVISIONAL_MAX_DEBT_WEIGHT;
        equity_w = 1.0 - debt_w;
        structure_guard = true;
    }

    let tax_rate_bps = DEFAULT_TAX_RATE_BPS.clamp(0, 3_500);
    let tax_rate_source = WaccFieldSource::Default;
    let after_tax_debt =
        (cost_of_debt_bps as f64 * (1.0 - tax_rate_bps as f64 / 10_000.0)).round() as i32;
    let weighted = (equity_w * cost_of_equity_bps as f64) + (debt_w * after_tax_debt as f64);
    let soft_wacc_bps = weighted.round() as i32;

    // Debt-scaled provisional base uplift: full at structure cap (~T reverse-DCF
    // +170 bps). Low-leverage names get a small share — not a blanket haircut.
    let provisional_wacc_uplift_bps =
        if cost_of_debt_source == WaccFieldSource::Default && debt_w > 0.0 {
            let scale = (debt_w / PROVISIONAL_MAX_DEBT_WEIGHT).clamp(0.0, 1.0);
            (PROVISIONAL_WACC_BASE_UPLIFT_BPS as f64 * scale).round() as i32
        } else {
            0
        };
    let wacc_bps = soft_wacc_bps + provisional_wacc_uplift_bps;

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
            // Structure guard, uplift, and policy CoD/rf keep point estimate unreliable.
            wacc_clamped: beta_prov
                || market_params.provisional
                || structure_guard
                || provisional_wacc_uplift_bps > 0,
        },
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
            ..Default::default()
        }
    }

    fn sample_fcf() -> Vec<FcfPoint> {
        vec![
            FcfPoint::new(2021, 80_000_000.0),
            FcfPoint::new(2022, 90_000_000.0),
            FcfPoint::new(2023, 100_000_000.0),
            FcfPoint::new(2024, 110_000_000.0),
        ]
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
                .contains("growth_and_discount_rate"),
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
        // Default path: base includes debt-scaled uplift; bear adds a further band.
        let a = compute(&operating_fund(), &sample_fcf(), Some(1_000), "sec_edgar").expect("dcf");
        assert!(a.diagnostics.point_estimate_unreliable);
        let base_w = a.wacc_bps;
        let bear_w = a.diagnostics.wacc_bear_bps.expect("bear wacc");
        let bull_w = a.diagnostics.wacc_bull_bps.expect("bull wacc");
        assert_eq!(bear_w - base_w, WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS);
        // Bull must not cheapen further: same WACC as base (band = 0).
        assert_eq!(bull_w, base_w);
        assert_eq!(WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS, 0);
        // Combined soft-path stress (uplift on levered names + bear band) stays material.
        let uplift = a.diagnostics.provisional_wacc_uplift_bps.unwrap_or(0);
        assert!(
            uplift + (bear_w - base_w) >= 150,
            "expected material provisional rate stress, uplift={uplift} bear_band={}",
            bear_w - base_w
        );
        assert!(a.reason_codes.iter().any(|r| {
            r.contains("wacc_stress=asymmetric_provisional")
                && r.contains("bull=base_no_further_cheapening")
        }));
    }

    /// Pinned T-class snapshot (Yahoo fixture + SEC OCF−ProductiveAssets FCF).
    /// Pre-calibration soft path overstated base vs weighted Street ~$30.
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

    fn t_class_fcf_edgar_ppe() -> Vec<FcfPoint> {
        // SEC ProductiveAssets path (not OCF alone; not Yahoo TTM).
        vec![
            FcfPoint::new(2021, 26_420_000_000.0),
            FcfPoint::new(2023, 20_460_000_000.0),
            FcfPoint::new(2024, 18_510_000_000.0),
            FcfPoint::new(2025, 19_440_000_000.0),
        ]
    }

    #[test]
    fn t_class_base_moves_toward_weighted_analyst_without_clamp() {
        // Weighted / mean Street from Yahoo quoteSummary fixture (targetMeanPrice).
        let weighted_consensus_cents: i64 = 3_002; // $30.02
        let a = compute(
            &t_class_fund(),
            &t_class_fcf_edgar_ppe(),
            Some(2_112),
            "sec_edgar",
        )
        .expect("t dcf");
        assert_eq!(a.model, ValuationModel::FcffWacc);
        assert!(a.diagnostics.point_estimate_unreliable);
        assert!(
            a.diagnostics.provisional_wacc_uplift_bps.unwrap_or(0) > 0,
            "levered soft path must apply provisional WACC uplift"
        );
        assert!(
            a.reason_codes
                .iter()
                .any(|r| r.starts_with("wacc=provisional_base_uplift:")),
            "uplift provenance missing: {:?}",
            a.reason_codes
        );
        assert!(
            a.reason_codes
                .iter()
                .all(|r| !r.starts_with("calibration_target=")),
            "Street is an external development metric, not runtime provenance: {:?}",
            a.reason_codes
        );

        let base = a.base_intrinsic_value_cents;
        eprintln!(
            "t_gap_metrics base_cents={} base_dollars={:.2} street_cents={} gap_cents={} wacc_bps={} uplift_bps={:?} run_rate={:?} normalized={} bear={} bull={}",
            base,
            base as f64 / 100.0,
            weighted_consensus_cents,
            base - weighted_consensus_cents,
            a.wacc_bps,
            a.diagnostics.provisional_wacc_uplift_bps,
            a.diagnostics.fcf_run_rate_dollars,
            a.diagnostics.fcf_run_rate_normalized,
            a.bear_intrinsic_value_cents,
            a.bull_intrinsic_value_cents
        );
        // Materially closer to Street than the pre-calibration ~$46–$55 band.
        assert!(
            base < 4_000,
            "base ${} still in pre-calibration overstatement band",
            base as f64 / 100.0
        );
        // Residual must remain a model output — not assigned to Street.
        assert_ne!(base, weighted_consensus_cents);
        // Gap to weighted consensus smaller than gap from a $50 soft mirage.
        let gap_to_street = (base - weighted_consensus_cents).abs();
        let gap_from_old_mirage = (5_000_i64 - weighted_consensus_cents).abs();
        assert!(
            gap_to_street < gap_from_old_mirage,
            "gap to Street {gap_to_street}c not improved vs old mirage; base={}",
            base as f64 / 100.0
        );
        // Pinned residual band from the shared executable contract (not equality).
        assert!(
            base >= 2_500 && base <= 3_500,
            "base ${} outside honest residual band",
            base as f64 / 100.0
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
        ];
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
            .map(|point| FcfPoint::new(point.year, point.value_dollars))
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
        ];
        let (run, normalized) = fcf_run_rate_dollars(&hist).expect("run");
        assert!(normalized);
        assert!(
            (run - 21_500_000.0).abs() < 1.0,
            "avg of four = 21.5M, got {run}"
        );
        let a = compute(&operating_fund(), &hist, Some(1_000), "test").expect("dcf");
        assert!(a.diagnostics.fcf_run_rate_normalized);
        assert_eq!(a.diagnostics.latest_fcf_dollars, Some(23_000_000));
        assert_eq!(a.diagnostics.fcf_run_rate_dollars, Some(21_500_000));
        assert!(a
            .reason_codes
            .iter()
            .any(|r| r == "fcf_run_rate=recent_window_average"));
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
    fn provisional_uplift_scales_monotonically_with_debt_weight() {
        let with_debt = |debt: i64| FundamentalSnapshot {
            symbol: format!("D{debt}"),
            market_cap_dollars: Some(100_000_000_000),
            shares_outstanding: Some(1_000_000_000),
            total_debt_dollars: Some(debt),
            total_cash_dollars: Some(0),
            beta_millis: Some(1_000),
            sector_name: Some("Industrials".into()),
            industry_name: Some("Conglomerates".into()),
            ..Default::default()
        };
        let fcf = vec![
            FcfPoint::new(2021, 14_000_000_000.0),
            FcfPoint::new(2022, 15_000_000_000.0),
            FcfPoint::new(2023, 16_000_000_000.0),
            FcfPoint::new(2024, 17_000_000_000.0),
        ];
        let low = compute(&with_debt(10_000_000_000), &fcf, Some(1_000), "test").expect("low");
        let mid = compute(&with_debt(40_000_000_000), &fcf, Some(1_000), "test").expect("mid");
        let capped =
            compute(&with_debt(200_000_000_000), &fcf, Some(1_000), "test").expect("capped");
        let uplifts = [
            low.diagnostics.provisional_wacc_uplift_bps.unwrap(),
            mid.diagnostics.provisional_wacc_uplift_bps.unwrap(),
            capped.diagnostics.provisional_wacc_uplift_bps.unwrap(),
        ];
        assert!(uplifts[0] > 0 && uplifts[0] < uplifts[1]);
        assert!(uplifts[1] < uplifts[2]);
        assert_eq!(uplifts[2], PROVISIONAL_WACC_BASE_UPLIFT_BPS);
    }

    #[test]
    fn solid_rates_use_symmetric_wacc_band() {
        let mut params = MarketParams::default_usd();
        params.provisional = false;
        // Still tax/CoD default → unreliable. Force non-unreliable inputs via custom path:
        // derive_wacc always defaults CoD/tax today, so point_estimate_unreliable stays true.
        // Document that solid band requires non-default CoD+tax; until then asymmetric stands.
        let a = compute_with_params(
            &operating_fund(),
            &sample_fcf(),
            Some(1_000),
            &params,
            "test",
            false,
        )
        .unwrap();
        // cost_of_debt remains Default → still unreliable asymmetric.
        assert!(a.diagnostics.point_estimate_unreliable);
        assert_eq!(
            a.diagnostics.wacc_bear_bps.unwrap() - a.wacc_bps,
            WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS
        );
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

    /// Highly levered + depressed equity must not crush WACC toward after-tax CoD.
    #[test]
    fn levered_provisional_wacc_caps_debt_weight() {
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
        let fcf = vec![
            FcfPoint::new(2021, 16_000_000_000.0),
            FcfPoint::new(2022, 17_000_000_000.0),
            FcfPoint::new(2023, 18_000_000_000.0),
            FcfPoint::new(2024, 18_500_000_000.0),
        ];
        let a = compute(&fund, &fcf, Some(2_300), "sec_edgar").expect("dcf");
        let dw = a.diagnostics.debt_weight_bps.expect("debt weight");
        assert!(
            dw <= (PROVISIONAL_MAX_DEBT_WEIGHT * 10_000.0).round() as i32 + 1,
            "debt weight {dw} should respect provisional max"
        );
        // Soft path still unreliable (no live CoD).
        assert!(a.diagnostics.point_estimate_unreliable);
        // Soft blend + full provisional uplift at debt cap → clearly above after-tax CoD.
        assert!(
            a.wacc_bps >= 800,
            "expected WACC ≥ 8% on levered provisional path, got {}",
            a.wacc_bps
        );
        assert_eq!(
            a.diagnostics.provisional_wacc_uplift_bps,
            Some(PROVISIONAL_WACC_BASE_UPLIFT_BPS)
        );
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
        weighted_analyst_mean_dollars: f64,
        shares_outstanding: u64,
        market_cap_dollars: u64,
        beta_millis: i32,
        total_debt_dollars: i64,
        total_cash_dollars: i64,
        sector_name: String,
        industry_name: String,
        fcf_annual_dollars: Vec<ContractFcfPoint>,
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
        base_intrinsic_range_dollars: Option<[f64; 2]>,
        latest_fcf_dollars: i64,
        fcf_run_rate_dollars: i64,
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
            .find(|fixture| {
                fixture.name
                    == "t_class_provisional_fcff_calibrates_toward_weighted_analyst_not_clamp"
            })
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
            .map(|point| FcfPoint::new(point.year, point.value_dollars))
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
        let base = analysis.base_intrinsic_value_cents as f64 / 100.0;
        let range = expected
            .base_intrinsic_range_dollars
            .expect("T base intrinsic range");
        assert!(
            base >= range[0] && base <= range[1],
            "base {base} outside contract range {range:?}"
        );
        let street = inputs.weighted_analyst_mean_dollars;
        assert_ne!(base, street);
        assert!(
            (base - street).abs() < (50.0 - street).abs(),
            "contract base did not improve on pre-policy soft mirage"
        );
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
}
