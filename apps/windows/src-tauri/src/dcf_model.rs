//! Valuation model family: business-class routing, residual income for financials,
//! FCFF+WACC for operating firms, dynamic market params + beta shrink.
//!
//! See `_bmad-output/planning-artifacts/valuation-model-family-architecture.md`.

use serde::{Deserialize, Serialize};

use crate::engine::FundamentalSnapshot;

pub const ENGINE_VERSION: &str = "valuation-model-family/1";
pub const MODEL_POLICY_VERSION: &str = "business-class-policy/1";

// ── Market policy (versioned; not eternal magic for valuation truth) ───────────
/// Default US 10Y-style nominal risk-free (bps). Shells may override via MarketParams.
const DEFAULT_RF_BPS: i32 = 430;
/// Versioned equity risk premium (bps).
const DEFAULT_ERP_BPS: i32 = 450;
const DEFAULT_TAX_RATE_BPS: i32 = 2_100;
const DEFAULT_COST_OF_DEBT_BPS: i32 = 550;
const DEFAULT_RETENTION_BPS: i32 = 7_000; // 70% retained when payout unknown
const BETA_COMPANY_WEIGHT: f64 = 0.67;
const BETA_INDUSTRY_WEIGHT: f64 = 0.33;
const DEFAULT_INDUSTRY_BETA_MILLIS: i32 = 1_000;
const PROJECTION_YEARS: i32 = 5;
const COE_SCENARIO_BAND_BPS: i32 = 75;
const ROE_BEAR_HAIRCUT_BPS: i32 = 300;
const ROE_BULL_BOOST_BPS: i32 = 200;
const GROWTH_RECENT_WINDOW: usize = 4;
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
    NotEligible,
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
    let blob = [
        sector_name.unwrap_or(""),
        industry_name.unwrap_or(""),
        sector_key.unwrap_or(""),
        industry_key.unwrap_or(""),
    ]
    .join(" ")
    .to_ascii_lowercase();

    if is_financial_services_text(&blob) {
        return BusinessClass::FinancialServices;
    }
    BusinessClass::OperatingNonFinancial
}

fn is_financial_services_text(blob: &str) -> bool {
    const KEYS: &[&str] = &[
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
    ];
    KEYS.iter().any(|k| blob.contains(k))
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
        BusinessClass::NotEligible => Err("valuation not eligible for this asset class".into()),
        BusinessClass::FinancialServices => {
            residual_income(fundamentals, market_price_cents, market_params, source)
        }
        BusinessClass::OperatingNonFinancial => {
            fcff_wacc(fundamentals, fcf_history, market_price_cents, market_params, source)
        }
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

    let (re_base, beta_source, beta_provisional) =
        cost_of_equity_bps(fundamentals, market_params);
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

    let mut reasons = vec![
        "model=residual_income_equity".into(),
        "business_class=financial_services".into(),
        "terminal_roe_fades_to_cost_of_equity".into(),
    ];
    if market_params.provisional {
        reasons.push("market_params=provisional".into());
    }

    Ok(DcfAnalysis {
        bear_intrinsic_value_cents: bear,
        base_intrinsic_value_cents: base,
        bull_intrinsic_value_cents: bull,
        wacc_bps: re_base,
        base_growth_bps: ((roe0_bps as f64 / 10_000.0) * retention * 10_000.0).round() as i32,
        net_debt_dollars: 0,
        wacc_inputs: WaccInputProvenance {
            market_cap: WaccFieldSource::Reported,
            beta: beta_source,
            total_debt: WaccFieldSource::Reported,
            total_cash: WaccFieldSource::Reported,
            cost_of_debt: WaccFieldSource::Reported,
            tax_rate: WaccFieldSource::Reported,
            wacc_clamped: beta_provisional || market_params.provisional,
        },
        source: source.to_string(),
        engine_version: ENGINE_VERSION.into(),
        model_policy_version: MODEL_POLICY_VERSION.into(),
        business_class: BusinessClass::FinancialServices,
        model: ValuationModel::ResidualIncomeEquity,
        discount_rate_kind: DiscountRateKind::CostOfEquity,
        stable_growth_bps: market_params.stable_growth_bps().min(re_base - GORDON_RATE_EPSILON_BPS),
        book_value_per_share_cents: Some(bvps_cents),
        roe0_bps: Some(roe0_bps),
        reason_codes: reasons,
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
    let latest = fcf_history
        .last()
        .and_then(|p| (p.value_dollars > 0.0).then_some(p.value_dollars))
        .ok_or_else(|| "latest annual free cash flow is not positive".to_string())?;
    let shares = fundamentals
        .shares_outstanding
        .filter(|&s| s > 0)
        .map(|s| s as f64)
        .ok_or_else(|| "share count is missing".to_string())?;

    let g_near = recent_fcf_growth_bps(fcf_history)
        .ok_or_else(|| "insufficient positive free cash flow history for growth".to_string())?;
    let g_stable = market_params
        .stable_growth_bps()
        .min(DEFAULT_RF_BPS); // will be re-clamped vs WACC below

    let resolved = derive_wacc(fundamentals, market_price_cents, market_params)?;
    let net_debt =
        fundamentals.total_debt_dollars.unwrap_or(0) - fundamentals.total_cash_dollars.unwrap_or(0);

    let g_stable = g_stable
        .min(resolved.wacc_bps - GORDON_RATE_EPSILON_BPS)
        .max(MIN_STABLE_GROWTH_BPS);

    // Scenario growth paths: fade from near-term toward stable
    let bear_near = (g_near - 400).max(-1_200);
    let bull_near = (g_near + 400).min(2_400);

    let bear = discounted_fcff_fade(latest, shares, net_debt, bear_near, g_stable, resolved.wacc_bps)
        .ok_or_else(|| "bear scenario invalid".to_string())?;
    let base = discounted_fcff_fade(latest, shares, net_debt, g_near, g_stable, resolved.wacc_bps)
        .ok_or_else(|| "base scenario invalid".to_string())?;
    let bull = discounted_fcff_fade(latest, shares, net_debt, bull_near, g_stable, resolved.wacc_bps)
        .ok_or_else(|| "bull scenario invalid".to_string())?;

    let mut reasons = vec![
        "model=fcff_wacc".into(),
        "business_class=operating_non_financial".into(),
        "growth=recent_window_fade_to_stable".into(),
    ];
    if market_params.provisional {
        reasons.push("market_params=provisional".into());
    }

    Ok(DcfAnalysis {
        bear_intrinsic_value_cents: bear,
        base_intrinsic_value_cents: base,
        bull_intrinsic_value_cents: bull,
        wacc_bps: resolved.wacc_bps,
        base_growth_bps: g_near,
        net_debt_dollars: net_debt,
        wacc_inputs: resolved.inputs,
        source: source.to_string(),
        engine_version: ENGINE_VERSION.into(),
        model_policy_version: MODEL_POLICY_VERSION.into(),
        business_class: BusinessClass::OperatingNonFinancial,
        model: ValuationModel::FcffWacc,
        discount_rate_kind: DiscountRateKind::Wacc,
        stable_growth_bps: g_stable,
        book_value_per_share_cents: fundamentals.book_value_per_share_cents,
        roe0_bps: fundamentals.return_on_equity_bps,
        reason_codes: reasons,
    })
}

/// CAGR over the last up-to-GROWTH_RECENT_WINDOW positive FCF points (not full history).
fn recent_fcf_growth_bps(history: &[FcfPoint]) -> Option<i32> {
    let positive: Vec<&FcfPoint> = history.iter().filter(|p| p.value_dollars > 0.0).collect();
    if positive.len() < 2 {
        return None;
    }
    let window = if positive.len() > GROWTH_RECENT_WINDOW {
        &positive[positive.len() - GROWTH_RECENT_WINDOW..]
    } else {
        &positive[..]
    };
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
    (re.max(market_params.rf_bps + 50), source, provisional || market_params.provisional)
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
    let equity_w = if base > 0.0 { market_cap / base } else { 1.0 };
    let debt_w = if base > 0.0 { net_debt / base } else { 0.0 };

    let (cost_of_debt_bps, cost_of_debt_source) = if total_debt > 0.0 {
        (DEFAULT_COST_OF_DEBT_BPS, WaccFieldSource::Default)
    } else {
        (DEFAULT_COST_OF_DEBT_BPS, WaccFieldSource::Reported)
    };

    let tax_rate_bps = DEFAULT_TAX_RATE_BPS.clamp(0, 3_500);
    let tax_rate_source = WaccFieldSource::Default;
    let after_tax_debt =
        (cost_of_debt_bps as f64 * (1.0 - tax_rate_bps as f64 / 10_000.0)).round() as i32;
    let weighted = (equity_w * cost_of_equity_bps as f64) + (debt_w * after_tax_debt as f64);
    let wacc_bps = weighted.round() as i32;

    Ok(ResolvedWacc {
        wacc_bps,
        inputs: WaccInputProvenance {
            market_cap: market_cap_source,
            beta: beta_source,
            total_debt: total_debt_source,
            total_cash: total_cash_source,
            cost_of_debt: cost_of_debt_source,
            tax_rate: tax_rate_source,
            wacc_clamped: beta_prov || market_params.provisional,
        },
    })
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

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
            FcfPoint {
                year: 2021,
                value_dollars: 80_000_000.0,
            },
            FcfPoint {
                year: 2022,
                value_dollars: 90_000_000.0,
            },
            FcfPoint {
                year: 2023,
                value_dollars: 100_000_000.0,
            },
            FcfPoint {
                year: 2024,
                value_dollars: 110_000_000.0,
            },
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
    fn acgl_uses_residual_income_not_fcff() {
        // Even with absurd OCF-like "FCF", financials must not use FCFF.
        let fake_float_fcf = vec![
            FcfPoint {
                year: 2022,
                value_dollars: 3_800_000_000.0,
            },
            FcfPoint {
                year: 2023,
                value_dollars: 5_700_000_000.0,
            },
            FcfPoint {
                year: 2024,
                value_dollars: 6_600_000_000.0,
            },
            FcfPoint {
                year: 2025,
                value_dollars: 6_172_000_000.0,
            },
        ];
        let a = compute(&acgl_like_fund(), &fake_float_fcf, Some(10_336), "sec_edgar").expect("ri");
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
        assert!(
            err.contains("book"),
            "expected missing book, got {err}"
        );
    }
}
