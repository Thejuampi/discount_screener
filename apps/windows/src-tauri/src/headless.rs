//! Headless V3 pricing engine (no Tauri UI, no gtk/webkit).
//!
//! Two semantics, chosen per request (`--semantics`):
//! - **android** (default for Long V3): ranks exactly like `apps/android/core`
//!   (`rank_android_v3`): Android's profile universe (`profiles::android_profile_symbols`,
//!   loaded by `commands::apply_universe_profile_android`), qualification at
//!   gap ≥ 2000 bps, Android signal status / confidence (weighted analyst count
//!   fallback), the Kotlin fundamentals / `ChartAnalysis` technicals / forecast
//!   target, half-up composite rounding, Act/Watch/Avoid and Android tie-breaks.
//!   Parity is pinned by the Kotlin-generated golden in `shared/contracts`.
//! - **tauri**: the desktop `get_opportunities` body
//!   (`capture_opportunity_projection` + `project_opportunity_rows`) and the
//!   Opportunities list sort (composite desc, stable). Unchanged GUI behaviour.
//!
//! The frozen scoring input is persisted as a JSON snapshot so the daemon
//! restarts warm and rankings can be replayed offline (`rank-snapshot`).

use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};

use serde::{Deserialize, Serialize};

use crate::commands::{
    apply_universe_profile, capture_opportunity_projection, project_opportunity_rows,
    OpportunityProjection, OpportunityProjectionInput, OpportunityRow,
};
use crate::engine::ScreenerState;
pub use crate::opportunity_v3::ScoringModel;
use crate::state::AppState;

pub const SNAPSHOT_FILE: &str = "opportunity_snapshot.json";

// ── Snapshot (persisted scoring input) ────────────────────────────────────────

#[derive(Serialize, Deserialize, Clone)]
pub struct SnapshotRow {
    row: crate::engine::CandidateRow,
    daily: Option<crate::engine::ChartSummary>,
    weekly: Option<crate::engine::ChartSummary>,
    hourly: Option<crate::engine::ChartSummary>,
    daily_candles: Vec<crate::engine::HistoricalCandle>,
    dcf_analysis: Option<crate::dcf_model::DcfAnalysis>,
    crypto_setup: Option<(i32, String)>,
    /// Android preferred chart: Yahoo `1y`/`1wk` summary (Android `ChartRange.Year`).
    #[serde(default)]
    year: Option<crate::android_chart::AndroidChartSummary>,
    /// `FinancialClassPolicy.classify(fundamentals)`; `None` = no fundamentals snapshot.
    #[serde(default)]
    business_class: Option<crate::dcf_model::BusinessClass>,
    /// External (Street) signal as ingested: (fair value cents, age seconds).
    #[serde(default)]
    external: Option<(i64, u64)>,
    /// Android confidence analyst count from the sanitized external signal:
    /// `weightedAnalystCount ?: analystOpinionCount` (ReportingEngine.kt), where
    /// the weighted count is dropped when the clamped weighted fair value is null.
    #[serde(default)]
    external_analysts: Option<u32>,
    /// Kotlin forecast target: (`preferredForecastFairValueCents`, `targetAnalystCount`).
    #[serde(default)]
    android_target: Option<(Option<i64>, Option<u32>)>,
    /// Sanitized Street fields the Opps-screen projection reads (`SymbolDetail`).
    #[serde(default)]
    android_street: Option<AndroidStreet>,
    /// Android DCF state facts for the projection; `None` = no DCF for the symbol.
    #[serde(default)]
    android_dcf: Option<AndroidDcfFacts>,
}

/// `ReportingEngine.sanitizeExternalSignal` fields read by `ScreenDataProjectionEngine`.
#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
pub struct AndroidStreet {
    pub fair: i64,
    pub low: Option<i64>,
    pub high: Option<i64>,
    /// Weighted fair clamped into [low, high]; kept only if > 0.
    pub weighted: Option<i64>,
    /// Kept only with `weighted`.
    pub weighted_count: Option<u32>,
    pub opinions: Option<u32>,
    pub age: u64,
}

impl AndroidStreet {
    pub fn from_signal(x: &crate::engine::ExternalValuationSignal) -> Self {
        let weighted = android_clamped_weighted_fair(x);
        Self {
            fair: x.fair_value_cents,
            low: x.low_fair_value_cents,
            high: x.high_fair_value_cents,
            weighted,
            weighted_count: weighted.and(x.weighted_analyst_count),
            opinions: x.analyst_opinion_count,
            age: x.age_seconds,
        }
    }

    /// `ValuationJudgmentPolicy.streetComplete`: range ordered and positive, coverage
    /// sufficient, fresh, available. Returns the Street base (`weighted ?: consensus`).
    pub fn usable_base(&self) -> Option<i64> {
        let base = self.weighted.unwrap_or(self.fair);
        let (low, high) = (self.low.unwrap_or(0), self.high.unwrap_or(0));
        let ordered = low > 0 && base > 0 && high > 0 && low <= base && base <= high;
        let coverage = self.weighted_count.or(self.opinions).unwrap_or(0) > 0;
        let fresh = self.age <= ANDROID_SIGNAL_MAX_AGE_SECONDS;
        (ordered && coverage && fresh).then_some(base)
    }
}

/// Android DCF identity value as the projection sees it.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum AndroidIdentity {
    /// No computed identity (refused, unavailable, or no DCF).
    None,
    /// Computed identity base value (cents).
    Value(i64),
    /// Android computes an identity value headless does not reproduce
    /// (live residual-income / FCFF DCF). Only matters when Street is unusable.
    Unknown,
}

/// The `DcfAnalysis` facts `ScreenDataProjectionEngine` branches on.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct AndroidDcfFacts {
    /// `resolverState == ProviderUncertain`.
    pub provider_uncertain: bool,
    /// `resolverState == RestoredOnly`.
    pub restored_only: bool,
    /// `isSourceFreeDcf`: source null/Unknown, or LegacySourceFreePayload provenance/reason.
    pub source_free: bool,
    pub identity: AndroidIdentity,
}

/// Kotlin `ValuationJudgmentPolicy.legalPair` (class refuse included: Unclassified /
/// NotEligible are never a legal identity).
fn android_legal_pair(class: &str, model: &str) -> bool {
    match class {
        "OperatingNonFinancial" => model == "FcffWacc" || model == "ComponentSum",
        "FinancialServices" => model == "ResidualIncomeEquity",
        _ => false,
    }
}

/// Kotlin `ValuationDecisionPolicy.scenarioWidthBps`: round-half-up (bull-bear)*10000/base.
fn android_scenario_width_bps((bear, base, bull): (i64, i64, i64)) -> Option<i64> {
    if bear <= 0 || base <= 0 || bull <= 0 || bear > base || base > bull {
        return None;
    }
    let n = (bull as i128 - bear as i128) * 10_000;
    i64::try_from((n + base as i128 / 2) / base as i128).ok()
}

/// `ValuationJudgmentPolicy.IDENTITY_USABLE_MAX_WIDTH_BPS` (valuation-policy.yaml).
const ANDROID_IDENTITY_USABLE_MAX_WIDTH_BPS: i64 = 12_000;

/// Kotlin `identityFanUnusable` negated.
fn android_identity_fan_usable(fan: (i64, i64, i64)) -> bool {
    android_scenario_width_bps(fan).is_some_and(|w| w <= ANDROID_IDENTITY_USABLE_MAX_WIDTH_BPS)
}

/// Port of Kotlin `DcfAnalysisEngine.residualIncome` (with `ResidualPathPolicy` and
/// `ResidualIncomeMath`) as `ResidualFromDrivers.compute` runs it on a bulk load (SEC off,
/// no timeseries). Returns the (bear, base, bull) per-share cents, or `None` where Kotlin
/// throws (→ `missingDrivers`). Android semantics only; Tauri keeps `dcf_model::residual_income`.
pub fn android_residual_fan(
    f: &crate::engine::FundamentalSnapshot,
    price_cents: i64,
    market: &crate::dcf_model::MarketParams,
) -> Option<(i64, i64, i64)> {
    // valuation-policy.yaml: dcf.*, residual_path.*, residual_income.*
    const COE_SCENARIO_BAND_BPS: i32 = 75;
    const ROE_BEAR_HAIRCUT_BPS: i32 = 300;
    const ROE_BULL_BOOST_BPS: i32 = 200;
    const ROE_BULL_CAP_BPS: i32 = 9_000;
    const MIN_EQUITY_SPREAD_OVER_RF_BPS: i32 = 50;
    let shares = f.shares_outstanding.filter(|s| *s > 0)? as f64;
    let bvps = match f.book_value_per_share_cents.filter(|b| *b > 0) {
        Some(b) => b,
        None => {
            let price = (price_cents > 0).then_some(price_cents as f64 / 100.0)?;
            let pb = f.price_to_book_hundredths.filter(|p| *p > 0)? as f64 / 100.0;
            let v = price / pb;
            if !v.is_finite() || v <= 0.0 {
                return None;
            }
            (v * 100.0).round() as i64
        }
    };
    let book0 = (bvps as f64 / 100.0) * shares;
    if !book0.is_finite() || book0 <= 0.0 {
        return None;
    }
    let roe0 = f.return_on_equity_bps.filter(|r| *r > 0 && *r < 10_000)?;
    let retention_bps = f.retention_bps.filter(|r| (0..=10_000).contains(r))?;
    let re_raw = crate::dcf_model::resolve_cost_of_equity(f, market)
        .ok()?
        .cost_of_equity_bps;
    let path = android_residual_path(
        roe0,
        re_raw,
        f.industry_name.as_deref(),
        f.sector_name.as_deref(),
    );
    let re_base = (re_raw + path.discount_adjust_bps).max(market.rf_bps + 50);
    let retention = retention_bps as f64 / 10_000.0;
    let bear_re = re_base + COE_SCENARIO_BAND_BPS;
    let bull_re =
        (re_base - COE_SCENARIO_BAND_BPS).max(market.rf_bps + MIN_EQUITY_SPREAD_OVER_RF_BPS);
    let g = market.stable_growth_bps();
    let sc = |roe: i32, re: i32, ret: f64| {
        android_ri_value_per_share_cents(
            book0,
            shares,
            roe,
            re,
            ret,
            path.fade_years,
            android_long_run_roe_bps(roe, re, path.franchise_spread_bps),
            g,
        )
    };
    let start = path.starting_roe_bps;
    let bear = sc(
        (start - ROE_BEAR_HAIRCUT_BPS).max(100),
        bear_re,
        retention * 0.9,
    )?;
    let base = sc(start, re_base, retention)?;
    let bull = sc(
        (start + ROE_BULL_BOOST_BPS).min(ROE_BULL_CAP_BPS),
        bull_re,
        retention.min(0.85),
    )?;
    Some((bear, base, bull))
}

struct AndroidResidualPath {
    starting_roe_bps: i32,
    franchise_spread_bps: i32,
    fade_years: i32,
    discount_adjust_bps: i32,
}

/// Kotlin `ResidualPathPolicy.resolve` (residual-path/4-care-quality-roe).
fn android_residual_path(
    roe0: i32,
    coe: i32,
    industry: Option<&str>,
    sector: Option<&str>,
) -> AndroidResidualPath {
    let text = format!("{} {}", industry.unwrap_or(""), sector.unwrap_or("")).to_lowercase();
    let mut p = AndroidResidualPath {
        starting_roe_bps: roe0,
        franchise_spread_bps: 500,
        fade_years: 5,
        discount_adjust_bps: 0,
    };
    if text.contains("bank") {
        if roe0 < 1_300 && roe0 < coe {
            p.starting_roe_bps = 1_300.min(roe0 + 400);
        }
        p.franchise_spread_bps = 600;
        if roe0 >= 1_400 {
            p.discount_adjust_bps = -70;
        }
    } else if text.contains("insurance") || text.contains("insur") {
        p.franchise_spread_bps = 120;
    } else if text.contains("healthcare plan")
        || text.contains("health care plan")
        || text.contains("managed care")
    {
        p.franchise_spread_bps = if roe0 < 2_000 { 350 } else { 2_000 };
        p.fade_years = 1;
    }
    p
}

/// Kotlin `ResidualIncomeMath.longRunRoeBps`.
fn android_long_run_roe_bps(roe0: i32, coe: i32, spread: i32) -> i32 {
    if roe0 <= coe {
        coe
    } else {
        roe0.min(coe + spread)
    }
}

/// Kotlin `ResidualIncomeMath.valuePerShareCents` (RESIDUAL_GORDON_EPSILON_BPS = 50).
#[allow(clippy::too_many_arguments)]
fn android_ri_value_per_share_cents(
    book0: f64,
    shares: f64,
    roe0_bps: i32,
    coe_bps: i32,
    retention: f64,
    fade_years: i32,
    long_run_roe_bps: i32,
    stable_growth_bps: i32,
) -> Option<i64> {
    if book0 <= 0.0 || shares <= 0.0 || coe_bps <= 0 || fade_years <= 0 {
        return None;
    }
    let re = coe_bps as f64 / 10_000.0;
    let roe0 = roe0_bps as f64 / 10_000.0;
    let roe_stable = long_run_roe_bps as f64 / 10_000.0;
    let mut book = book0;
    let mut pv = 0.0;
    for t in 1..=fade_years {
        let w = t as f64 / fade_years as f64;
        let roe_t = roe0 * (1.0 - w) + roe_stable * w;
        pv += (roe_t - re) * book / (1.0 + re).powi(t);
        book *= 1.0 + roe_t * retention;
        if !book.is_finite() || book <= 0.0 {
            return None;
        }
    }
    if long_run_roe_bps > coe_bps {
        let g = stable_growth_bps.min((coe_bps - 50).max(0)) as f64 / 10_000.0;
        if g >= re {
            return None;
        }
        pv += (roe_stable - re) * book / (re - g) / (1.0 + re).powi(fade_years);
    }
    let equity = book0 + pv;
    if !equity.is_finite() || equity <= 0.0 {
        return None;
    }
    Some(((equity / shares) * 100.0).round() as i64)
}

impl AndroidDcfFacts {
    /// From a Kotlin `DcfAnalysis` JSON object (fixture injection / recorded requests).
    pub fn from_kotlin_json(v: &serde_json::Value) -> Self {
        let s = |k: &str| v.get(k).and_then(|x| x.as_str());
        let source = s("source");
        // Kotlin defaults: resolverState = RestoredOnly when source == null, else Selected;
        // provenance.fallbackReason / providerReasons carry LegacySourceFreePayload when null.
        let resolver = s("resolverState")
            .or(s("resolver_state"))
            .unwrap_or(if source.is_none() {
                "RestoredOnly"
            } else {
                "Selected"
            });
        let legacy = "LegacySourceFreePayload";
        let prov = v.get("provenance");
        let fallback = prov
            .and_then(|p| p.get("fallbackReason").or(p.get("fallback_reason")))
            .and_then(|x| x.as_str());
        let reasons = v.get("providerReasons").or(v.get("provider_reasons"));
        let reason_legacy = match reasons.and_then(|r| r.as_array()) {
            Some(a) => a
                .iter()
                .any(|r| r.get("code").and_then(|c| c.as_str()) == Some(legacy)),
            None => source.is_none(),
        };
        let fallback_legacy = match prov {
            Some(_) => fallback == Some(legacy),
            None => source.is_none(),
        };
        let source_free = source.is_none_or(|x| x == "Unknown") || reason_legacy || fallback_legacy;
        let i = |k: &str| v.get(k).and_then(|x| x.as_i64());
        let base = i("baseIntrinsicValueCents")
            .or(i("base_intrinsic_value_cents"))
            .unwrap_or(0);
        let model = s("model").unwrap_or("None");
        let unavailable = s("valuationUnavailableReason")
            .or(s("valuation_unavailable_reason"))
            .is_some_and(|r| !r.trim().is_empty());
        // Kotlin `DcfAnalysis.businessClass` defaults to OperatingNonFinancial.
        let class = s("businessClass")
            .or(s("business_class"))
            .unwrap_or("OperatingNonFinancial");
        let fan = (
            i("bearIntrinsicValueCents")
                .or(i("bear_intrinsic_value_cents"))
                .unwrap_or(0),
            base,
            i("bullIntrinsicValueCents")
                .or(i("bull_intrinsic_value_cents"))
                .unwrap_or(0),
        );
        // ValuationJudgmentAssembler.isComputedIdentity, then
        // ValuationJudgmentPolicy.identityComplete (class refuse, legalPair, fan width).
        let computed = model != "None" && !unavailable && base > 0;
        let identity =
            if computed && android_legal_pair(class, model) && android_identity_fan_usable(fan) {
                AndroidIdentity::Value(base)
            } else {
                AndroidIdentity::None
            };
        Self {
            provider_uncertain: resolver == "ProviderUncertain",
            restored_only: resolver == "RestoredOnly",
            source_free,
            identity,
        }
    }

    /// The DCF state Android settles on after a bulk load (`enrichSymbol`, SEC off).
    /// `operating_identity`: what an operating name's DCF contributes (live: an FCFF value
    /// headless does not reproduce → `Some(Unknown)`; recorded fixture: no DCF → `None`).
    /// `restored_only`: the analysis was recovered from persisted state, not resolved live
    /// this session (Kotlin `ResolverState.RestoredOnly`), as when the daemon serves its
    /// persisted snapshot. Terminal classification states are rebuilt, never restored.
    /// `market`: the `MarketParams` the residual-income DCF runs with.
    pub fn bulk_load(
        class: Option<crate::dcf_model::BusinessClass>,
        f: Option<&crate::engine::FundamentalSnapshot>,
        price_cents: i64,
        operating_identity: Option<AndroidIdentity>,
        restored_only: bool,
        market: &crate::dcf_model::MarketParams,
    ) -> Option<Self> {
        use crate::dcf_model::BusinessClass::*;
        let facts = |source_free, identity, restored| Self {
            provider_uncertain: false,
            restored_only: restored,
            source_free,
            identity,
        };
        match class? {
            // terminalClassificationAnalysis: source Unknown, model None.
            Unclassified | NotEligible => Some(facts(true, AndroidIdentity::None, false)),
            // ResidualFromDrivers.compute: Selected with the default source-free provenance
            // (`DcfAnalysis(source = null)` then `.copy(source = Yahoo)`); otherwise
            // missingDrivers (SecEdgar Unavailable, model None).
            FinancialServices => Some(match android_residual_fan(f?, price_cents, market) {
                Some(fan) => facts(
                    true,
                    if android_identity_fan_usable(fan) {
                        AndroidIdentity::Value(fan.1)
                    } else {
                        AndroidIdentity::None
                    },
                    restored_only,
                ),
                None => facts(false, AndroidIdentity::None, false),
            }),
            OperatingNonFinancial => operating_identity.map(|i| facts(false, i, restored_only)),
        }
    }

    /// Mark a persisted snapshot's computed DCF states as restored (not live this session).
    pub fn mark_restored(&mut self) {
        if self.identity != AndroidIdentity::None {
            self.restored_only = true;
        }
    }
}

/// Serializable copy of the exact input the V3 projection consumes.
#[derive(Serialize, Deserialize, Clone)]
pub struct OpportunitySnapshot {
    pub profile: String,
    pub captured_at_epoch: i64,
    pub rows: Vec<SnapshotRow>,
}

impl OpportunitySnapshot {
    pub fn capture(screener: &Mutex<ScreenerState>, profile: &str) -> Self {
        Self::capture_with_year(screener, profile, &Default::default())
    }

    pub fn capture_with_year(
        screener: &Mutex<ScreenerState>,
        profile: &str,
        year: &std::collections::HashMap<String, crate::android_chart::AndroidChartSummary>,
    ) -> Self {
        let (classes, externals, funds) = {
            let s = screener.lock().unwrap();
            let funds = s.fundamentals.clone();
            let classes: std::collections::HashMap<String, crate::dcf_model::BusinessClass> = s
                .fundamentals
                .iter()
                .map(|(sym, f)| (sym.clone(), android_class(sym, f)))
                .collect();
            type Ext = (
                (i64, u64),
                Option<u32>,
                (Option<i64>, Option<u32>),
                AndroidStreet,
            );
            let externals: std::collections::HashMap<String, Ext> = s
                .signals
                .iter()
                // Kotlin `ReportingEngine.ingestExternal` drops fair <= 0 signals
                // entirely (status Missing, no forecast target).
                .filter(|(_, x)| android_accepts_signal(x))
                .map(|(sym, x)| {
                    (
                        sym.clone(),
                        (
                            (x.fair_value_cents, x.age_seconds),
                            android_analyst_count(x),
                            android_forecast_target(x),
                            AndroidStreet::from_signal(x),
                        ),
                    )
                })
                .collect();
            (classes, externals, funds)
        };
        let projection = capture_opportunity_projection(screener);
        let rows = projection
            .rows
            .into_iter()
            .map(|i| SnapshotRow {
                daily: i.daily,
                weekly: i.weekly,
                hourly: i.hourly,
                daily_candles: i.daily_candles,
                dcf_analysis: i.dcf_analysis,
                crypto_setup: i.crypto_setup.map(|(s, l)| (s, l.to_string())),
                year: year.get(&i.row.symbol).cloned(),
                business_class: classes.get(&i.row.symbol).copied(),
                external: externals.get(&i.row.symbol).map(|e| e.0),
                external_analysts: externals.get(&i.row.symbol).and_then(|e| e.1),
                android_target: externals.get(&i.row.symbol).map(|e| e.2),
                android_street: externals.get(&i.row.symbol).map(|e| e.3.clone()),
                android_dcf: AndroidDcfFacts::bulk_load(
                    classes.get(&i.row.symbol).copied(),
                    funds.get(&i.row.symbol),
                    i.row.market_price_cents,
                    Some(AndroidIdentity::Unknown),
                    false,
                    &crate::dcf_model::MarketParams::default_usd(),
                ),
                row: i.row,
            })
            .collect();
        Self {
            profile: profile.to_string(),
            captured_at_epoch: chrono::Utc::now().timestamp(),
            rows,
        }
    }

    pub fn len(&self) -> usize {
        self.rows.len()
    }

    pub fn is_empty(&self) -> bool {
        self.rows.is_empty()
    }

    pub fn load(path: &Path) -> Result<Self, String> {
        let bytes = std::fs::read(path).map_err(|e| format!("read {}: {e}", path.display()))?;
        serde_json::from_slice(&bytes).map_err(|e| format!("parse {}: {e}", path.display()))
    }

    /// Atomic write (tmp + rename).
    pub fn save(&self, path: &Path) -> Result<(), String> {
        let tmp = path.with_extension("json.tmp");
        let bytes = serde_json::to_vec(self).map_err(|e| e.to_string())?;
        std::fs::write(&tmp, bytes).map_err(|e| format!("write {}: {e}", tmp.display()))?;
        std::fs::rename(&tmp, path).map_err(|e| format!("rename {}: {e}", path.display()))
    }

    fn to_projection(&self, model: ScoringModel) -> OpportunityProjection {
        OpportunityProjection {
            model,
            rows: self
                .rows
                .iter()
                .cloned()
                .map(|r| OpportunityProjectionInput {
                    row: r.row,
                    daily: r.daily,
                    weekly: r.weekly,
                    hourly: r.hourly,
                    daily_candles: r.daily_candles,
                    dcf_analysis: r.dcf_analysis,
                    // The label is a pure function of the score (`crypto_cycle::score_to_label`),
                    // so it is re-derived instead of interning the stored string.
                    crypto_setup: r
                        .crypto_setup
                        .map(|(s, _)| (s, crate::crypto_cycle::score_to_label(s))),
                })
                .collect(),
        }
    }
}

// ── Ranking ───────────────────────────────────────────────────────────────────

/// Parse `v3` | `v2` | `short` (and the canonical ids) into a scoring model.
pub fn parse_model(raw: &str) -> Result<ScoringModel, String> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "v3" | "long_v3" | "long-v3" | "aggressive_v3" => Ok(ScoringModel::AggressiveV3),
        "v2" | "long_v2" | "long-v2" | "aggressive_v2" => Ok(ScoringModel::AggressiveV2),
        "short" | "short_v3" | "short-v3" => Ok(ScoringModel::ShortV3),
        other => Err(format!("unknown model '{other}' (use v3, v2 or short)")),
    }
}

/// Opportunities list default order: `composite_score` desc, stable.
/// (`OpportunityList.tsx` sorts by composite desc with JS's stable sort.)
pub fn sort_like_opportunities_list(rows: &mut [OpportunityRow]) {
    rows.sort_by_key(|r| std::cmp::Reverse(r.composite_score));
}

/// Score a snapshot with `model` (market context OFF unless a policy is given).
pub fn rank_snapshot(
    snapshot: &OpportunitySnapshot,
    model: ScoringModel,
    market_regime: Option<crate::regime::MarketRegime>,
) -> Vec<OpportunityRow> {
    use crate::regime::{RegimeScoringPolicy, ScoreSide};
    let apply = market_regime.is_some();
    let long = market_regime
        .as_ref()
        .and_then(|r| RegimeScoringPolicy::from_regime(r, ScoreSide::Long));
    let short = market_regime
        .as_ref()
        .and_then(|r| RegimeScoringPolicy::from_regime(r, ScoreSide::Short));
    let mut rows =
        project_opportunity_rows(snapshot.to_projection(model), apply, long, short, || {});
    sort_like_opportunities_list(&mut rows);
    rows
}

/// The desktop `get_opportunities` body (context OFF) on live state, for an
/// explicit model. The model is set on the frozen projection, never on the
/// shared `ScreenerState`, so concurrent requests cannot observe each other.
///
/// Note: this shares scoring code with [`rank_snapshot`]; it only proves the
/// snapshot round-trip is lossless. Android parity is proven separately against
/// the Kotlin golden (`android_parity_tests`).
pub fn rank_tauri_path(
    screener: &Mutex<ScreenerState>,
    model: ScoringModel,
) -> Vec<OpportunityRow> {
    let mut projection = capture_opportunity_projection(screener);
    projection.model = model;
    let mut rows = project_opportunity_rows(projection, false, None, None, || {});
    sort_like_opportunities_list(&mut rows);
    rows
}

/// Compact row for the CLI.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct RankedRow {
    pub rank: usize,
    pub symbol: String,
    pub company: Option<String>,
    pub sector: Option<String>,
    pub composite_score: i32,
    pub setup_score: i32,
    pub setup_label: String,
    pub decision: String,
    #[serde(default)]
    pub confidence: Option<String>,
    pub price: f64,
    pub target: Option<f64>,
    pub gap_pct: Option<f64>,
    #[serde(default)]
    pub upside_pct: Option<f64>,
    pub fundamentals: Option<i32>,
    pub technical: Option<i32>,
    pub forecast: Option<i32>,
    pub regime: Option<i32>,
    /// Android Opps-screen trust signal (android semantics only).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trust_signal: Option<String>,
    /// Set when the on-screen value needs a DCF identity value headless does not compute.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub projection_gap: Option<String>,
}

/// Short-side setup label from the (already short-oriented) Short V3 composite.
/// Same bands as `setup_from_v3_composite`, named for the short side.
pub fn short_setup_label(composite: i32) -> &'static str {
    match composite {
        c if c >= 50 => "StrongShort",
        c if c >= 30 => "Short",
        c if c >= 15 => "LeanShort",
        c if c >= 0 => "WatchShort",
        c if c >= -20 => "NoEdge",
        c if c >= -40 => "AvoidShort",
        _ => "StrongAvoidShort",
    }
}

pub fn to_ranked(rows: &[OpportunityRow], model: ScoringModel) -> Vec<RankedRow> {
    let short = model == ScoringModel::ShortV3;
    rows.iter()
        .enumerate()
        .map(|(i, r)| RankedRow {
            rank: i + 1,
            symbol: r.row.symbol.clone(),
            company: r.row.company_name.clone(),
            sector: r.row.sector_name.clone(),
            composite_score: r.composite_score,
            // Desktop crypto rows carry the long-only crypto-cycle setup; on the
            // short list the setup must be the short composite instead.
            setup_score: if short {
                r.composite_score
            } else {
                r.setup_score
            },
            setup_label: if short {
                short_setup_label(r.composite_score).to_string()
            } else {
                r.setup_label.to_string()
            },
            decision: r.decision.to_string(),
            confidence: None,
            price: r.row.market_price_cents as f64 / 100.0,
            target: (r.row.intrinsic_value_cents > 0)
                .then(|| r.row.intrinsic_value_cents as f64 / 100.0),
            gap_pct: r.row.gap_bps.map(|b| b as f64 / 100.0),
            upside_pct: android_upside_bps(r.row.market_price_cents, r.row.intrinsic_value_cents)
                .map(|b| b as f64 / 100.0),
            fundamentals: r.fundamentals_score,
            technical: r.technical_score,
            forecast: r.forecast_score,
            regime: r.regime_score,
            trust_signal: None,
            projection_gap: None,
        })
        .collect()
}

pub fn android_to_ranked(rows: &[AndroidRow]) -> Vec<RankedRow> {
    rows.iter()
        .enumerate()
        .map(|(i, r)| RankedRow {
            rank: i + 1,
            symbol: r.symbol.clone(),
            company: r.company.clone(),
            sector: r.sector.clone(),
            composite_score: r.composite_score,
            setup_score: r.composite_score,
            setup_label: r.decision.clone().unwrap_or_default(),
            decision: r.decision.clone().unwrap_or_default(),
            confidence: Some(r.confidence.clone()),
            price: r.price_cents as f64 / 100.0,
            target: r.anchor_cents.map(|c| c as f64 / 100.0),
            gap_pct: r.gap_bps.map(|b| b as f64 / 100.0),
            upside_pct: r.upside_bps.map(|b| b as f64 / 100.0),
            fundamentals: r.fundamentals,
            technical: r.technical,
            forecast: r.forecast,
            regime: None,
            trust_signal: r.trust_signal.clone(),
            projection_gap: r.projection_gap.clone(),
        })
        .collect()
}

/// Full-row diff between two rankings (order + every serialized field).
pub fn diff_rankings(a: &[OpportunityRow], b: &[OpportunityRow]) -> Vec<String> {
    let mut out = Vec::new();
    if a.len() != b.len() {
        out.push(format!("row count differs: {} vs {}", a.len(), b.len()));
    }
    for (i, (x, y)) in a.iter().zip(b.iter()).enumerate() {
        let xv = serde_json::to_value(x).unwrap();
        let yv = serde_json::to_value(y).unwrap();
        if xv != yv {
            let mut fields = Vec::new();
            if let (Some(xo), Some(yo)) = (xv.as_object(), yv.as_object()) {
                for (k, v) in xo {
                    if yo.get(k) != Some(v) {
                        fields.push(k.clone());
                    }
                }
            }
            out.push(format!(
                "#{} {} vs {}: fields {:?}",
                i + 1,
                x.row.symbol,
                y.row.symbol,
                fields
            ));
        }
    }
    out
}

// ── Android semantics (ground truth: apps/android/core OpportunityEngine) ─────

/// Which engine's Opportunities semantics the headless ranking follows.
/// `Android` is the headless default; `Tauri` reproduces the desktop list
/// byte-for-byte. The desktop GUI never goes through this switch.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Semantics {
    Android,
    Tauri,
}

impl Semantics {
    pub fn parse(raw: &str) -> Result<Self, String> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "android" => Ok(Self::Android),
            "tauri" | "desktop" | "windows" => Ok(Self::Tauri),
            o => Err(format!("unknown semantics '{o}' (use android or tauri)")),
        }
    }
}

/// Android `ReportingEngine` default `minGapBps` (Rust desktop uses 1_000).
pub const ANDROID_MIN_GAP_BPS: i32 = 2_000;

/// `FinancialClassPolicy.classify(fundamentals)` (Kotlin passes `assetNotEquity = false`).
pub fn android_class(
    symbol: &str,
    f: &crate::engine::FundamentalSnapshot,
) -> crate::dcf_model::BusinessClass {
    crate::dcf_model::classify_business_for(
        Some(symbol),
        f.sector_name.as_deref(),
        f.industry_name.as_deref(),
        f.sector_key.as_deref(),
        f.industry_key.as_deref(),
        false,
    )
}

/// One Android Opportunities row (V3, context off) as the Opps screen shows it:
/// `OpportunityEngine.buildRows` order and scores, with confidence / gap / upside /
/// trust / decision from `ScreenDataProjectionEngine.projectedOpportunityRow`.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct AndroidRow {
    pub symbol: String,
    pub company: Option<String>,
    pub sector: Option<String>,
    pub composite_score: i32,
    pub coverage: u8,
    pub fundamentals: Option<i32>,
    pub technical: Option<i32>,
    pub forecast: Option<i32>,
    /// Projected decision (`Act` / `Watch` / `Avoid`); `None` = no badge on screen.
    pub decision: Option<String>,
    /// Projected confidence (`high` / `provisional` / `low`).
    pub confidence: String,
    pub price_cents: i64,
    /// buildRows intrinsic value (the row's own fair value).
    pub intrinsic_cents: i64,
    /// Projected anchor value (judgment primary); `None` = no anchor.
    pub anchor_cents: Option<i64>,
    /// `street_weighted` / `street_consensus` / `identity` / `unavailable`.
    pub anchor: String,
    /// Projected discount / upside vs the anchor, clamped to ±100000 bps.
    pub gap_bps: Option<i32>,
    pub upside_bps: Option<i32>,
    /// `ProjectedTrustSignalKind` name.
    pub trust_signal: Option<String>,
    /// buildRows values (what the sort uses).
    pub row_confidence: String,
    pub row_gap_bps: i32,
    pub row_upside_bps: i32,
    /// Set when Android's on-screen value depends on a DCF identity value headless
    /// does not compute (Street unusable + live residual/FCFF DCF).
    pub projection_gap: Option<String>,
    pub fundamentals_signals: Vec<String>,
}

/// `quant_lens.row_upside_min_bps` / `row_upside_max_bps` (valuation-policy.yaml).
pub const ANDROID_ROW_UPSIDE_LIMIT_BPS: i32 = 100_000;

/// Output of [`android_project_row`].
#[derive(Clone, Debug, PartialEq)]
pub struct AndroidProjection {
    pub confidence: Option<crate::engine::ConfidenceBand>,
    pub anchor_cents: Option<i64>,
    pub anchor: &'static str,
    pub gap_bps: Option<i32>,
    pub upside_bps: Option<i32>,
    pub trust_signal: Option<&'static str>,
    pub decision: Option<&'static str>,
    pub projection_gap: Option<&'static str>,
}

/// Kotlin `checkedGapBps`: (fair - market) * 10000 / fair, fair > 0.
fn android_checked_gap_bps(price: i64, fair: i64) -> Option<i32> {
    if fair <= 0 {
        return None;
    }
    let v = (fair as i128 - price as i128) * 10_000 / fair as i128;
    Some(v.clamp(i32::MIN as i128, i32::MAX as i128) as i32)
}

fn android_lower(c: crate::engine::ConfidenceBand) -> crate::engine::ConfidenceBand {
    use crate::engine::ConfidenceBand::*;
    match c {
        High => Provisional,
        Provisional | Low => Low,
    }
}

/// Port of `ScreenDataProjectionEngine.projectedOpportunityRow` for an Opps row built
/// after a cold load (every symbol Live, no provider issues, Consensus statistic):
/// `namedRowValuation` (ValuationJudgmentAssembler/Policy: Street if `streetComplete`,
/// else refuse/Identity), `projectedConfidence`, `trustSignal`, `opportunityDecision`.
pub fn android_project_row(
    row_confidence: crate::engine::ConfidenceBand,
    price_cents: i64,
    composite: i32,
    street: Option<&AndroidStreet>,
    dcf: Option<&AndroidDcfFacts>,
) -> AndroidProjection {
    use crate::engine::ConfidenceBand::*;
    let lower = dcf.is_some_and(|d| d.provider_uncertain || d.restored_only || d.source_free);
    let confidence = if lower {
        android_lower(row_confidence)
    } else {
        row_confidence
    };
    let street_base = street.and_then(|s| s.usable_base());
    let mut projection_gap = None;
    // (anchor value, label, analyst coverage for the anchor role)
    let (anchor_cents, anchor, analyst_role_count): (
        Option<i64>,
        &'static str,
        Option<Option<u32>>,
    ) = match (street_base, street) {
        (Some(base), Some(s)) => {
            if s.weighted.is_some_and(|w| w > 0) {
                (
                    Some(base),
                    "street_weighted",
                    Some(s.weighted_count.or(s.opinions)),
                )
            } else {
                (Some(base), "street_consensus", Some(s.opinions))
            }
        }
        _ => match dcf.map(|d| d.identity) {
            Some(AndroidIdentity::Value(v)) => (Some(v), "identity", None),
            Some(AndroidIdentity::Unknown) => {
                projection_gap = Some("identity_value_not_computed_headless");
                (None, "identity", None)
            }
            _ => (None, "unavailable", None),
        },
    };
    let clamp = |v: i32| v.clamp(-ANDROID_ROW_UPSIDE_LIMIT_BPS, ANDROID_ROW_UPSIDE_LIMIT_BPS);
    let gap_bps = anchor_cents
        .and_then(|a| android_checked_gap_bps(price_cents, a))
        .map(clamp);
    let upside_bps = anchor_cents
        .and_then(|a| android_upside_bps(price_cents, a))
        .map(clamp);
    let trust_signal = if dcf.is_some_and(|d| d.provider_uncertain) {
        Some("SourceUncertain")
    } else if dcf.is_some_and(|d| d.source_free) {
        Some("SourceUnknown")
    } else if anchor == "identity" {
        let valid = street.is_some_and(|s| {
            s.weighted.is_some_and(|w| w > 0)
                || s.fair > 0
                || s.low.is_some_and(|l| l > 0)
                || s.high.is_some_and(|h| h > 0)
        });
        Some(if valid {
            "ModelValue"
        } else {
            "NoAnalystTarget"
        })
    } else {
        match analyst_role_count {
            Some(Some(1..=2)) => Some("ThinCoverage"),
            Some(Some(0) | None) => Some("CoverageUnknown"),
            _ => None,
        }
    };
    let decision = match (upside_bps, projection_gap) {
        // Kotlin: `projectedUpsideBps(...) ?: return null`.
        (None, None) => None,
        // Identity value unknown: only the confidence gate is decidable.
        (None, Some(_)) => (confidence == Low).then_some("Avoid"),
        (Some(up), _) => Some(if confidence == Low || up <= 0 || composite < 0 {
            "Avoid"
        } else if trust_signal.is_some() {
            "Watch"
        } else if confidence == High && composite >= 30 {
            "Act"
        } else {
            "Watch"
        }),
    };
    AndroidProjection {
        confidence: Some(confidence),
        anchor_cents,
        anchor,
        gap_bps,
        upside_bps,
        trust_signal,
        decision,
        projection_gap,
    }
}

fn confidence_rank(c: crate::engine::ConfidenceBand) -> u8 {
    use crate::engine::ConfidenceBand::*;
    match c {
        Low => 0,
        Provisional => 1,
        High => 2,
    }
}

fn confidence_label(c: crate::engine::ConfidenceBand) -> &'static str {
    use crate::engine::ConfidenceBand::*;
    match c {
        Low => "low",
        Provisional => "provisional",
        High => "high",
    }
}

/// Kotlin `checkedUpsideBps` (BigInteger truncating division).
pub fn android_upside_bps(price: i64, fair: i64) -> Option<i32> {
    if price <= 0 || fair <= 0 {
        return None;
    }
    let v = (fair as i128 - price as i128) * 10_000 / price as i128;
    Some(v.clamp(i32::MIN as i128, i32::MAX as i128) as i32)
}

/// Android `OpportunityEngine.buildRows` for AggressiveV3, regime scoring off:
/// profile universe only, qualified rows only (profitable and gap ≥ 20%),
/// Year (1y/1wk) technicals, Android fundamentals, Android tie-breaks.
/// Kotlin `clampedWeightedFairValue`: weighted fair clamped into [low, high], kept if > 0.
pub fn android_clamped_weighted_fair(x: &crate::engine::ExternalValuationSignal) -> Option<i64> {
    let mut w = x.weighted_fair_value_cents?;
    if let (Some(l), Some(h)) = (x.low_fair_value_cents, x.high_fair_value_cents) {
        w = w.clamp(l.min(h), l.max(h));
    }
    (w > 0).then_some(w)
}

/// `sanitizeExternalSignal(x).weightedAnalystCount ?: analystOpinionCount`.
pub fn android_analyst_count(x: &crate::engine::ExternalValuationSignal) -> Option<u32> {
    let weighted = android_clamped_weighted_fair(x).and(x.weighted_analyst_count);
    weighted.or(x.analyst_opinion_count)
}

/// Kotlin `ReportingEngine.ingestExternal`: `if (fairValueCents <= 0) return`.
pub fn android_accepts_signal(x: &crate::engine::ExternalValuationSignal) -> bool {
    x.fair_value_cents > 0
}

/// Kotlin `OpportunityEngine.preferredForecastFairValueCents` / `targetAnalystCount`.
pub fn android_forecast_target(
    x: &crate::engine::ExternalValuationSignal,
) -> (Option<i64>, Option<u32>) {
    match android_clamped_weighted_fair(x) {
        Some(w) => (
            Some(w),
            x.weighted_analyst_count.or(x.analyst_opinion_count),
        ),
        // Signals with fair <= 0 never reach here (see `android_accepts_signal`).
        None => (Some(x.fair_value_cents), x.analyst_opinion_count),
    }
}

/// Android `ReportingEngine` external-signal max age (seconds).
///
/// Live data: signal age is always 0. Every production constructor stamps
/// `age_seconds: 0` at fetch time (Android `YahooFinanceClient.kt`, Rust
/// `quote_summary.rs` / `commands.rs`) and neither app ever ages a stored
/// signal, so this Stale gate never fires live in either app. It only
/// matters for injected signals; the shared fixture covers ages 0/5/30/31/3600.
pub const ANDROID_SIGNAL_MAX_AGE_SECONDS: u64 = 30;

/// Re-derive qualification / external status / confidence with Android's
/// thresholds (min gap 20%, signal max age 30 s) — `ReportingEngine.buildSymbolDetail`.
fn android_row(r: &SnapshotRow) -> crate::engine::CandidateRow {
    use crate::engine::{checked_gap_bps, confidence, qualification, ExternalSignalStatus};
    let mut row = r.row.clone();
    let profitable = row.qualification != crate::engine::QualificationStatus::Unprofitable;
    row.qualification = qualification(profitable, row.gap_bps, ANDROID_MIN_GAP_BPS);
    row.signal_status = match r.external {
        None => ExternalSignalStatus::Missing,
        Some((_, age)) if age > ANDROID_SIGNAL_MAX_AGE_SECONDS => ExternalSignalStatus::Stale,
        Some((fair, _)) => {
            if checked_gap_bps(row.market_price_cents, fair).unwrap_or(i32::MIN)
                >= ANDROID_MIN_GAP_BPS
            {
                ExternalSignalStatus::Supportive
            } else {
                ExternalSignalStatus::Divergent
            }
        }
    };
    // Older snapshots lack `external_analysts`; fall back to the row's count.
    let analysts = if r.external.is_some() {
        r.external_analysts.or(row.analyst_opinion_count)
    } else {
        None
    };
    row.confidence = confidence(row.qualification, row.signal_status, analysts);
    row
}

pub fn rank_android_v3(
    snapshot: &OpportunitySnapshot,
    universe: Option<&[String]>,
) -> Vec<AndroidRow> {
    use crate::engine::QualificationStatus;
    use crate::opportunity_v3::{
        composite_score_v3_android, score_forecast_v3, score_forecast_v3_with,
        score_fundamentals_v3_android,
    };
    let allowed: Option<std::collections::HashSet<&str>> =
        universe.map(|u| u.iter().map(|s| s.as_str()).collect());
    let mut rows: Vec<(AndroidRow, u8)> = snapshot
        .rows
        .iter()
        .filter(|r| {
            allowed
                .as_ref()
                .is_none_or(|a| a.contains(r.row.symbol.as_str()))
        })
        .map(|r| (r, android_row(r)))
        .filter(|(_, row)| row.qualification == QualificationStatus::Qualified)
        .map(|(r, row)| {
            let row = &row;
            let (f, sig) = score_fundamentals_v3_android(row, r.business_class);
            let t = crate::android_chart::score_technicals_v3(r.year.as_ref());
            let (fc, _) = match r.android_target {
                Some((fair, count)) => {
                    score_forecast_v3_with(row, r.dcf_analysis.as_ref(), fair, count)
                }
                // No external signal → Kotlin has no Street target.
                None if r.external.is_none() => {
                    score_forecast_v3_with(row, r.dcf_analysis.as_ref(), None, None)
                }
                // Snapshot written before `android_target` existed: desktop inputs.
                None => score_forecast_v3(row, r.dcf_analysis.as_ref()),
            };
            let composite = composite_score_v3_android(f, t, fc, row.beta_millis);
            let coverage = [f, t, fc].iter().filter(|x| x.is_some()).count() as u8;
            let upside = android_upside_bps(row.market_price_cents, row.intrinsic_value_cents);
            let p = android_project_row(
                row.confidence,
                row.market_price_cents,
                composite,
                r.android_street.as_ref(),
                r.android_dcf.as_ref(),
            );
            (
                AndroidRow {
                    symbol: row.symbol.clone(),
                    company: row.company_name.clone(),
                    sector: row.sector_name.clone(),
                    composite_score: composite,
                    coverage,
                    fundamentals: f,
                    technical: t,
                    forecast: fc,
                    decision: p.decision.map(str::to_string),
                    confidence: confidence_label(p.confidence.unwrap_or(row.confidence))
                        .to_string(),
                    price_cents: row.market_price_cents,
                    intrinsic_cents: row.intrinsic_value_cents,
                    anchor_cents: p.anchor_cents,
                    anchor: p.anchor.to_string(),
                    gap_bps: p.gap_bps,
                    upside_bps: p.upside_bps,
                    trust_signal: p.trust_signal.map(str::to_string),
                    row_confidence: confidence_label(row.confidence).to_string(),
                    row_gap_bps: row.gap_bps.unwrap_or(0),
                    row_upside_bps: upside.unwrap_or(0),
                    projection_gap: p.projection_gap.map(str::to_string),
                    fundamentals_signals: sig,
                },
                confidence_rank(row.confidence),
            )
        })
        .collect();
    rows.sort_by(|(a, ac), (b, bc)| {
        b.composite_score
            .cmp(&a.composite_score)
            .then(b.coverage.cmp(&a.coverage))
            .then(bc.cmp(ac))
            .then(b.row_upside_bps.cmp(&a.row_upside_bps))
            .then(a.symbol.cmp(&b.symbol))
    });
    rows.into_iter().map(|(r, _)| r).collect()
}

// ── Daemon engine ─────────────────────────────────────────────────────────────

pub struct EngineConfig {
    pub data_dir: PathBuf,
    pub profile: String,
    pub market_context: bool,
    /// Fetch Android's `1y`/`1wk` Year chart for every profile symbol.
    pub fetch_year_charts: bool,
    /// Load only Android's profile universe (sp500 = 501 equities) instead of
    /// the desktop composition (+ ETFs + crypto). Headless default: true.
    pub android_universe: bool,
}

#[derive(Serialize)]
pub struct EngineStatus {
    pub year_charts_loaded: usize,
    pub profile: String,
    pub market_context: bool,
    pub feed_running: bool,
    pub symbols_loaded: usize,
    pub symbols_total: usize,
    pub last_error: Option<String>,
    pub ranked_rows: usize,
    pub source: &'static str,
    pub snapshot_captured_at_epoch: Option<i64>,
}

pub struct HeadlessEngine {
    pub state: AppState,
    cfg: EngineConfig,
    persisted: Mutex<Option<Arc<OpportunitySnapshot>>>,
    year: Arc<Mutex<std::collections::HashMap<String, crate::android_chart::AndroidChartSummary>>>,
}

/// Canonical profile id (`S&P500`, `sp500`, … → one key) so an alias does not
/// discard a persisted snapshot.
pub fn canonical_profile(raw: &str) -> String {
    crate::profiles::compose_universe(raw)
        .map(|(id, _)| id)
        .unwrap_or_else(|_| raw.trim().to_ascii_lowercase())
}

/// Android profile universe (the asset file, no Rust ETF/crypto/priority extras).
pub fn android_universe(profile: &str) -> Option<Vec<String>> {
    crate::profiles::android_profile_symbols(profile)
}

fn spawn_year_worker(
    symbols: Vec<String>,
    year: Arc<Mutex<std::collections::HashMap<String, crate::android_chart::AndroidChartSummary>>>,
) {
    std::thread::spawn(move || loop {
        if let Ok(client) = crate::fetcher::YahooClient::new() {
            let _ = client.warm_session();
            for sym in &symbols {
                if let Ok(c) = client.fetch_candles(sym, "1y", "1wk") {
                    if !c.is_empty() {
                        let sum = crate::android_chart::build_summary(&c);
                        year.lock().unwrap().insert(sym.clone(), sum);
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(120));
            }
        }
        std::thread::sleep(std::time::Duration::from_secs(1800));
    });
}

impl HeadlessEngine {
    pub fn start(cfg: EngineConfig) -> Result<Arc<Self>, String> {
        std::fs::create_dir_all(&cfg.data_dir)
            .map_err(|e| format!("create {}: {e}", cfg.data_dir.display()))?;
        let state = AppState::new_with_forced_profile(cfg.data_dir.join("history.sqlite"), None)?;
        state
            .apply_regime_scoring
            .store(cfg.market_context, Ordering::Relaxed);
        let persisted = OpportunitySnapshot::load(&cfg.data_dir.join(SNAPSHOT_FILE))
            .ok()
            .filter(|s| canonical_profile(&s.profile) == canonical_profile(&cfg.profile))
            .map(|mut s| {
                // Recovered from disk, not resolved live this session: RestoredOnly.
                for r in &mut s.rows {
                    if let Some(d) = &mut r.android_dcf {
                        d.mark_restored();
                    }
                }
                Arc::new(s)
            });
        if cfg.android_universe {
            crate::commands::apply_universe_profile_android(&cfg.profile, &state)?;
        } else {
            apply_universe_profile(&cfg.profile, &state)?;
        }
        let mut seed = std::collections::HashMap::new();
        if let Some(p) = &persisted {
            for r in &p.rows {
                if let Some(y) = &r.year {
                    seed.insert(r.row.symbol.clone(), y.clone());
                }
            }
        }
        let year = Arc::new(Mutex::new(seed));
        if cfg.fetch_year_charts {
            if let Some(u) = android_universe(&cfg.profile) {
                spawn_year_worker(u, year.clone());
            }
        }
        if cfg.market_context {
            crate::regime::spawn_regime_worker(&state);
        }
        Ok(Arc::new(Self {
            state,
            cfg,
            persisted: Mutex::new(persisted),
            year,
        }))
    }

    pub fn profile(&self) -> String {
        self.state.active_profile.lock().unwrap().clone()
    }

    /// Live snapshot of current engine state (falls back to persisted one while
    /// the feed is still empty after a restart).
    pub fn current_snapshot(&self) -> (Arc<OpportunitySnapshot>, &'static str) {
        let live = self.capture();
        if !live.is_empty() {
            return (Arc::new(live), "live");
        }
        match self.persisted.lock().unwrap().clone() {
            Some(p) => (p, "persisted"),
            None => (Arc::new(live), "live"),
        }
    }

    pub fn persist(&self) -> Result<usize, String> {
        let snap = self.capture();
        if snap.is_empty() {
            return Ok(0);
        }
        snap.save(&self.cfg.data_dir.join(SNAPSHOT_FILE))?;
        let n = snap.len();
        *self.persisted.lock().unwrap() = Some(Arc::new(snap));
        Ok(n)
    }

    fn capture(&self) -> OpportunitySnapshot {
        let year = self.year.lock().unwrap().clone();
        OpportunitySnapshot::capture_with_year(&self.state.screener, &self.profile(), &year)
    }

    pub fn android_ranking(&self) -> (Vec<AndroidRow>, &'static str) {
        let (snap, source) = self.current_snapshot();
        let u = android_universe(&snap.profile);
        (rank_android_v3(&snap, u.as_deref()), source)
    }

    fn regime(&self) -> Option<crate::regime::MarketRegime> {
        if !self.cfg.market_context {
            return None;
        }
        // Context ON but no reading yet → policy unavailable; mirrors the app.
        Some(self.state.regime_cache.get().unwrap_or_default())
    }

    pub fn ranking(&self, model: ScoringModel) -> (Vec<OpportunityRow>, &'static str) {
        let (snap, source) = self.current_snapshot();
        (rank_snapshot(&snap, model, self.regime()), source)
    }

    /// Same-instant parity: desktop command path on live state vs headless path on
    /// a JSON round-tripped snapshot of that same state.
    pub fn parity(&self, model: ScoringModel) -> Result<ParityReport, String> {
        let tauri = rank_tauri_path(&self.state.screener, model);
        let snap = OpportunitySnapshot::capture(&self.state.screener, &self.profile());
        let json = serde_json::to_vec(&snap).map_err(|e| e.to_string())?;
        let snap: OpportunitySnapshot = serde_json::from_slice(&json).map_err(|e| e.to_string())?;
        let headless = rank_snapshot(&snap, model, None);
        Ok(ParityReport {
            model: model.as_str().to_string(),
            rows: tauri.len(),
            differences: diff_rankings(&tauri, &headless),
            tauri_top: to_ranked(&tauri, model).into_iter().take(10).collect(),
            headless_top: to_ranked(&headless, model).into_iter().take(10).collect(),
        })
    }

    pub fn status(&self) -> EngineStatus {
        let fs = self.state.feed_status.lock().unwrap().clone();
        let (snap, source) = self.current_snapshot();
        EngineStatus {
            year_charts_loaded: self.year.lock().unwrap().len(),
            profile: self.profile(),
            market_context: self.cfg.market_context,
            feed_running: fs.running,
            symbols_loaded: fs.symbols_loaded,
            symbols_total: self.state.active_symbols.lock().unwrap().len(),
            last_error: fs.last_error,
            ranked_rows: snap.len(),
            source,
            snapshot_captured_at_epoch: Some(snap.captured_at_epoch),
        }
    }
}

#[derive(Serialize, Deserialize)]
pub struct ParityReport {
    pub model: String,
    pub rows: usize,
    pub differences: Vec<String>,
    pub tauri_top: Vec<RankedRow>,
    pub headless_top: Vec<RankedRow>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::commands::opportunity_projection_tests::fixture_state;

    const MODELS: [ScoringModel; 3] = [
        ScoringModel::AggressiveV3,
        ScoringModel::AggressiveV2,
        ScoringModel::ShortV3,
    ];

    #[test]
    fn headless_ranking_matches_tauri_command_path_for_every_model() {
        let screener = fixture_state();
        for model in MODELS {
            let tauri = rank_tauri_path(&screener, model);
            let snap = OpportunitySnapshot::capture(&screener, "fixture");
            let json = serde_json::to_string(&snap).unwrap();
            let snap: OpportunitySnapshot = serde_json::from_str(&json).unwrap();
            let headless = rank_snapshot(&snap, model, None);
            assert!(!tauri.is_empty());
            assert_eq!(
                diff_rankings(&tauri, &headless),
                Vec::<String>::new(),
                "{model:?}"
            );
            assert_eq!(
                serde_json::to_string(&tauri).unwrap(),
                serde_json::to_string(&headless).unwrap()
            );
        }
    }

    #[test]
    fn context_off_reports_disabled_regime_and_no_regime_bucket() {
        let screener = fixture_state();
        let snap = OpportunitySnapshot::capture(&screener, "fixture");
        for row in rank_snapshot(&snap, ScoringModel::AggressiveV3, None) {
            assert!(row.regime_score.is_none());
            assert_eq!(row.composite_score, row.composite_score_base);
        }
    }

    #[test]
    fn snapshot_persists_atomically_and_reloads_identically() {
        let screener = fixture_state();
        let snap = OpportunitySnapshot::capture(&screener, "fixture");
        let dir = std::env::temp_dir().join(format!("vh-snap-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(SNAPSHOT_FILE);
        snap.save(&path).unwrap();
        let loaded = OpportunitySnapshot::load(&path).unwrap();
        let a = rank_snapshot(&snap, ScoringModel::AggressiveV3, None);
        let b = rank_snapshot(&loaded, ScoringModel::AggressiveV3, None);
        assert!(diff_rankings(&a, &b).is_empty());
        assert!(!path.with_extension("json.tmp").exists());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn ranking_is_composite_desc_and_stable() {
        let screener = fixture_state();
        let snap = OpportunitySnapshot::capture(&screener, "fixture");
        let rows = rank_snapshot(&snap, ScoringModel::AggressiveV3, None);
        assert!(rows
            .windows(2)
            .all(|w| w[0].composite_score >= w[1].composite_score));
        let ranked = to_ranked(&rows, ScoringModel::AggressiveV3);
        assert_eq!(ranked[0].rank, 1);
        assert_eq!(ranked.len(), rows.len());
    }

    #[test]
    fn short_rows_use_short_labels() {
        let screener = fixture_state();
        let snap = OpportunitySnapshot::capture(&screener, "fixture");
        let rows = rank_snapshot(&snap, ScoringModel::ShortV3, None);
        for r in to_ranked(&rows, ScoringModel::ShortV3) {
            assert_eq!(r.setup_label, short_setup_label(r.composite_score));
            assert!(!matches!(
                r.setup_label.as_str(),
                "StrongBuy" | "HoldWait" | "Buy"
            ));
        }
        assert_eq!(short_setup_label(50), "StrongShort");
        assert_eq!(short_setup_label(-41), "StrongAvoidShort");
    }

    /// Android loads `apps/android/app/src/main/assets/profiles/<p>.txt`
    /// (`ProfileCatalog.loadProfile`). The Android-mode feed must load exactly it.
    #[test]
    fn android_feed_universe_matches_android_assets() {
        let db = crate::db::Db::open_in_memory().unwrap();
        let assets = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../android/app/src/main/assets/profiles");
        for profile in [
            "sp500", "dow", "russell", "merval", "nikkei", "europe", "asia",
        ] {
            let text = std::fs::read_to_string(assets.join(format!("{profile}.txt"))).unwrap();
            let mut android: Vec<String> = text
                .lines()
                .map(str::trim)
                .filter(|l| !l.is_empty())
                .map(String::from)
                .collect();
            // DefaultDashboardRepository.resolveProfileSymbols applies `.distinct()`.
            let mut seen = std::collections::HashSet::new();
            android.retain(|s| seen.insert(s.clone()));
            let loaded = crate::commands::resolve_android_membership(profile, &db)
                .unwrap()
                .symbols;
            assert_eq!(loaded.len(), android.len(), "{profile} count");
            let a: std::collections::HashSet<_> = android.iter().collect();
            let l: std::collections::HashSet<_> = loaded.iter().collect();
            assert_eq!(l, a, "{profile} membership");
            assert_eq!(loaded, android, "{profile} order");
        }
        let sp = crate::commands::resolve_android_membership("sp500", &db)
            .unwrap()
            .symbols;
        assert_eq!(sp.len(), 501);
        assert!(!sp
            .iter()
            .any(|s| s.ends_with("-USD") || s == "SPY" || s == "SLV"));
        // Desktop composition is unchanged (ETFs + crypto on top).
        let desktop = crate::profiles::resolve_profile_membership("sp500", &db)
            .unwrap()
            .symbols;
        assert!(desktop.len() > 501);
        // The ranking filter uses the same list as the feed.
        assert_eq!(android_universe("sp500").unwrap(), sp);
    }

    #[test]
    fn android_forecast_target_matches_kotlin() {
        let mut x: crate::engine::ExternalValuationSignal = serde_json::from_value(
            serde_json::json!({"symbol": "Z", "fair_value_cents": 0, "age_seconds": 0,
                               "analyst_opinion_count": 4}),
        )
        .unwrap();
        // Kotlin ingestExternal ignores fair <= 0; Rust must too.
        assert!(!android_accepts_signal(&x));
        x.fair_value_cents = -5;
        assert!(!android_accepts_signal(&x));
        // Weighted fair outside [low, high] is clamped and wins over the plain one.
        x.fair_value_cents = 10_000;
        x.weighted_fair_value_cents = Some(20_000);
        x.weighted_analyst_count = Some(7);
        x.low_fair_value_cents = Some(8_000);
        x.high_fair_value_cents = Some(12_000);
        assert_eq!(android_forecast_target(&x), (Some(12_000), Some(7)));
    }

    #[test]
    fn profile_alias_keeps_snapshot() {
        assert_eq!(canonical_profile("sp500"), canonical_profile("SPX"));
    }

    #[test]
    fn parse_model_aliases() {
        assert_eq!(parse_model("v3").unwrap(), ScoringModel::AggressiveV3);
        assert_eq!(parse_model("Long_V2").unwrap(), ScoringModel::AggressiveV2);
        assert_eq!(parse_model("short").unwrap(), ScoringModel::ShortV3);
        assert!(parse_model("v9").is_err());
    }

    #[test]
    fn diff_detects_order_and_value_changes() {
        let screener = fixture_state();
        let snap = OpportunitySnapshot::capture(&screener, "fixture");
        let a = rank_snapshot(&snap, ScoringModel::AggressiveV3, None);
        let mut b = rank_snapshot(&snap, ScoringModel::AggressiveV3, None);
        b[0].composite_score += 1;
        assert_eq!(diff_rankings(&a, &b).len(), 1);
        b.pop();
        assert!(diff_rankings(&a, &b)[0].contains("row count"));
    }
}

/// Android-vs-headless parity on the shared contract. The golden is produced by
/// the Kotlin core (`HeadlessV3ParityGoldenTest`), never by this crate.
#[cfg(test)]
mod android_parity_tests {
    use super::*;
    use crate::engine::{
        ExternalValuationSignal, FundamentalSnapshot, HistoricalCandle, MarketSnapshot,
    };
    use serde_json::Value;

    fn snake(v: Value) -> Value {
        match v {
            Value::Object(m) => Value::Object(
                m.into_iter()
                    .map(|(k, v)| {
                        let mut out = String::new();
                        for c in k.chars() {
                            if c.is_ascii_uppercase() {
                                out.push('_');
                                out.push(c.to_ascii_lowercase());
                            } else {
                                out.push(c);
                            }
                        }
                        (out, snake(v))
                    })
                    .collect(),
            ),
            Value::Array(a) => Value::Array(a.into_iter().map(snake).collect()),
            o => o,
        }
    }

    fn contracts() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../shared/contracts")
    }

    /// Feed the fixture through the real Rust ingest path, then rank.
    pub(super) fn rank_fixture() -> Vec<AndroidRow> {
        rank_fixture_at(&contracts().join("opportunity-v3-headless-fixture.json"))
    }

    fn rank_fixture_at(path: &std::path::Path) -> Vec<AndroidRow> {
        let raw: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
        let fx = snake(raw);
        let screener = Mutex::new(ScreenerState::new());
        let mut year = std::collections::HashMap::new();
        for case in fx["symbols"].as_array().unwrap() {
            let snap: MarketSnapshot = serde_json::from_value(case["snapshot"].clone()).unwrap();
            let sym = snap.symbol.clone();
            let mut s = screener.lock().unwrap();
            s.ingest_snapshot(snap);
            if !case["external"].is_null() {
                let x: ExternalValuationSignal =
                    serde_json::from_value(case["external"].clone()).unwrap();
                s.ingest_signal(x);
            }
            if !case["fundamentals"].is_null() {
                let f: FundamentalSnapshot =
                    serde_json::from_value(case["fundamentals"].clone()).unwrap();
                s.ingest_fundamentals(f);
            }
            let candles: Vec<HistoricalCandle> =
                serde_json::from_value(case["year_candles"].clone()).unwrap();
            if !candles.is_empty() {
                year.insert(sym, crate::android_chart::build_summary(&candles));
            }
        }
        let mut snap = OpportunitySnapshot::capture_with_year(&screener, "fixture", &year);
        // Recorded inputs carry no operating DCF (no timeseries); an injected `dcf`
        // replaces the bulk-load state.
        let cases: std::collections::HashMap<&str, &Value> = fx["symbols"]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| (c["snapshot"]["symbol"].as_str().unwrap(), c))
            .collect();
        for r in &mut snap.rows {
            let case = cases[r.row.symbol.as_str()];
            r.android_dcf = if !case["dcf"].is_null() {
                Some(AndroidDcfFacts::from_kotlin_json(&case["dcf"]))
            } else {
                let fund = screener
                    .lock()
                    .unwrap()
                    .fundamentals
                    .get(&r.row.symbol)
                    .cloned();
                AndroidDcfFacts::bulk_load(
                    r.business_class,
                    fund.as_ref(),
                    r.row.market_price_cents,
                    None,
                    false,
                    &crate::dcf_model::MarketParams::default_usd(),
                )
            };
        }
        rank_android_v3(&snap, None)
    }

    #[test]
    fn headless_v3_matches_kotlin_golden() {
        let golden: Value = serde_json::from_slice(
            &std::fs::read(contracts().join("opportunity-v3-headless-golden.json")).unwrap(),
        )
        .unwrap();
        let rows = rank_fixture();
        let want = golden["rows"].as_array().unwrap();
        let mut diffs = Vec::new();
        let mut gaps = 0;
        if rows.len() != want.len() {
            diffs.push(format!("row count {} vs golden {}", rows.len(), want.len()));
        }
        let by_sym: std::collections::HashMap<&str, &Value> = want
            .iter()
            .map(|g| (g["symbol"].as_str().unwrap(), g))
            .collect();
        for (i, r) in rows.iter().enumerate() {
            let Some(g) = by_sym.get(r.symbol.as_str()) else {
                diffs.push(format!("{}: ranked by Rust, absent in Kotlin", r.symbol));
                continue;
            };
            let cap = |c: &str| match c {
                "high" => "High",
                "low" => "Low",
                _ => "Provisional",
            };
            let got = serde_json::json!({
                "rank": i + 1, "compositeScore": r.composite_score,
                "coverageCount": r.coverage, "fundamentalsScore": r.fundamentals,
                "technicalScore": r.technical, "forecastScore": r.forecast,
                "confidence": cap(&r.row_confidence),
                "gapBps": r.row_gap_bps, "upsideBps": r.row_upside_bps,
                "projectedConfidence": cap(&r.confidence),
                "projectedGapBps": r.gap_bps, "projectedUpsideBps": r.upside_bps,
                "trustSignal": r.trust_signal, "decision": r.decision,
            });
            for (k, v) in got.as_object().unwrap() {
                // Known, flagged gap: Android's identity value (live residual / FCFF DCF)
                // is not computed headless; only the decision's confidence gate is.
                if r.projection_gap.is_some()
                    && matches!(k.as_str(), "projectedGapBps" | "projectedUpsideBps")
                    && v.is_null()
                {
                    gaps += 1;
                    continue;
                }
                if g.get(k) != Some(v) {
                    diffs.push(format!("{}: {k} rust={v} kotlin={}", r.symbol, g[k]));
                }
            }
        }
        for g in want {
            let sym = g["symbol"].as_str().unwrap();
            if !rows.iter().any(|r| r.symbol == sym) {
                diffs.push(format!("{sym}: ranked by Kotlin, absent in Rust"));
            }
        }
        assert!(
            diffs.is_empty(),
            "Android parity diffs:\n{}",
            diffs.join("\n")
        );
        // The residual identity value is computed headless (FXRI, FXRB): nothing is skipped.
        assert_eq!(gaps, 0, "unexpected identity-gap field count");
    }

    /// Mutation guard: the golden must catch a one-point coverage-bonus change
    /// (the reviewer's 5 → 6 probe) — i.e. the test is not circular.
    /// Fixture-authoring helper for `tools/gen_headless_v3_fixture.py` (picks
    /// tie pairs from a candidate pool). Not part of the suite.
    #[test]
    #[ignore]
    fn dump_fixture_ranking() {
        let (Ok(inp), Ok(out)) = (std::env::var("FIXTURE_IN"), std::env::var("FIXTURE_OUT")) else {
            return;
        };
        let rows = rank_fixture_at(std::path::Path::new(&inp));
        std::fs::write(out, serde_json::to_vec(&rows).unwrap()).unwrap();
    }

    /// The golden must actually exercise every sort key, or mutating one would
    /// go unnoticed: equal composite with different coverage, equal composite
    /// and coverage with different confidence, a full tie (symbol), and the
    /// weighted-analyst confidence fallback.
    #[test]
    fn golden_exercises_every_tie_key() {
        let golden: Value = serde_json::from_slice(
            &std::fs::read(contracts().join("opportunity-v3-headless-golden.json")).unwrap(),
        )
        .unwrap();
        let rows = golden["rows"].as_array().unwrap();
        let key = |g: &Value, k: &str| g[k].clone();
        let pair = |f: &dyn Fn(&Value, &Value) -> bool| {
            rows.iter()
                .any(|a| rows.iter().any(|b| a["symbol"] != b["symbol"] && f(a, b)))
        };
        assert!(pair(&|a, b| key(a, "compositeScore")
            == key(b, "compositeScore")
            && key(a, "coverageCount") != key(b, "coverageCount")));
        assert!(pair(&|a, b| key(a, "compositeScore")
            == key(b, "compositeScore")
            && key(a, "coverageCount") == key(b, "coverageCount")
            && key(a, "confidence") != key(b, "confidence")));
        assert!(pair(&|a, b| key(a, "compositeScore")
            == key(b, "compositeScore")
            && key(a, "upsideBps") == key(b, "upsideBps")
            && key(a, "confidence") == key(b, "confidence")));
        let conf = |s: &str| {
            rows.iter()
                .find(|g| g["symbol"] == s)
                .map(|g| g["confidence"].clone())
        };
        assert_eq!(
            conf("FXW1"),
            Some(Value::from("High")),
            "weighted count 5 beats opinions 2"
        );
        assert_eq!(
            conf("FXW2"),
            Some(Value::from("Provisional")),
            "weighted count 2 beats opinions 5"
        );
        assert_eq!(
            conf("FXW3"),
            Some(Value::from("Provisional")),
            "no weighted fair → opinions 2"
        );
        assert!(rows
            .iter()
            .any(|g| g["coverageCount"].as_i64().unwrap() > 1));
    }

    #[test]
    fn projection_screen_cases_in_golden() {
        let golden: Value = serde_json::from_slice(
            &std::fs::read(contracts().join("opportunity-v3-headless-golden.json")).unwrap(),
        )
        .unwrap();
        let row = |s: &str| {
            golden["rows"]
                .as_array()
                .unwrap()
                .iter()
                .find(|g| g["symbol"] == s)
                .cloned()
                .unwrap()
        };
        // ProviderUncertain DCF at composite >= 30: High -> Provisional, Watch not Act.
        let pu = row("FXPU");
        assert!(pu["compositeScore"].as_i64().unwrap() >= 30);
        assert_eq!(pu["confidence"], "High");
        assert_eq!(pu["projectedConfidence"], "Provisional");
        assert_eq!(pu["trustSignal"], "SourceUncertain");
        assert_eq!(pu["decision"], "Watch");
        let rf = row("FXRF");
        assert!(rf["compositeScore"].as_i64().unwrap() >= 30);
        assert_eq!(rf["trustSignal"], "SourceUnknown");
        assert_eq!(rf["decision"], "Watch");
        assert_eq!(row("FX12")["decision"], "Act");
    }

    #[test]
    fn android_decision_boundaries_and_trust_gate() {
        use crate::engine::ConfidenceBand::*;
        let street = AndroidStreet {
            fair: 15_000,
            low: Some(12_000),
            high: Some(20_000),
            weighted: None,
            weighted_count: None,
            opinions: Some(10),
            age: 0,
        };
        // Avoid gate is `composite < 0`: 0 is not Avoid, -1 is.
        let p = |comp| android_project_row(High, 10_000, comp, Some(&street), None).decision;
        assert_eq!(p(0), Some("Watch"));
        assert_eq!(p(-1), Some("Avoid"));
        // High + identity anchor (Street unusable) + composite >= 30: trust gate -> Watch.
        let unusable = AndroidStreet {
            low: Some(25_000),
            ..street.clone()
        };
        let sourced = AndroidDcfFacts {
            provider_uncertain: false,
            restored_only: false,
            source_free: false,
            identity: AndroidIdentity::Value(15_000),
        };
        let x = android_project_row(High, 10_000, 45, Some(&unusable), Some(&sourced));
        assert_eq!(
            (x.confidence, x.anchor, x.trust_signal, x.decision),
            (Some(High), "identity", Some("ModelValue"), Some("Watch"))
        );
        let x = android_project_row(High, 10_000, 45, None, Some(&sourced));
        assert_eq!(x.trust_signal, Some("NoAnalystTarget"));
        assert_eq!(x.decision, Some("Watch"));
        // RestoredOnly lowers confidence one step, without a trust signal.
        let restored = AndroidDcfFacts {
            restored_only: true,
            ..sourced.clone()
        };
        let x = android_project_row(High, 10_000, 45, Some(&street), Some(&restored));
        assert_eq!(
            (x.confidence, x.trust_signal, x.decision),
            (Some(Provisional), None, Some("Watch"))
        );
    }

    #[test]
    fn android_restored_only_is_plumbed() {
        use crate::dcf_model::{BusinessClass::*, MarketParams};
        let mp = MarketParams::default_usd();
        let op = |restored| {
            AndroidDcfFacts::bulk_load(
                Some(OperatingNonFinancial),
                None,
                10_000,
                Some(AndroidIdentity::Value(12_000)),
                restored,
                &mp,
            )
            .unwrap()
        };
        assert!(!op(false).restored_only);
        assert!(op(true).restored_only);
        // Terminal classification states are rebuilt, never restored.
        let t = AndroidDcfFacts::bulk_load(Some(NotEligible), None, 10_000, None, true, &mp);
        assert!(!t.unwrap().restored_only);
        // A persisted snapshot's computed DCF is marked RestoredOnly on daemon start.
        let mut d = op(false);
        d.mark_restored();
        assert!(d.restored_only);
        let kt = AndroidDcfFacts::from_kotlin_json(&serde_json::json!({
            "source": "YahooFinance", "resolverState": "RestoredOnly",
            "model": "FcffWacc", "bearIntrinsicValueCents": 9000,
            "baseIntrinsicValueCents": 10000, "bullIntrinsicValueCents": 11000
        }));
        assert!(kt.restored_only);
        assert_eq!(kt.identity, AndroidIdentity::Value(10_000));
    }

    #[test]
    fn android_from_kotlin_json_identity_complete() {
        let id = |extra: Value| {
            let mut v = serde_json::json!({
                "source": "YahooFinance", "resolverState": "Selected",
                "businessClass": "OperatingNonFinancial", "model": "FcffWacc",
                "bearIntrinsicValueCents": 9000, "baseIntrinsicValueCents": 10000,
                "bullIntrinsicValueCents": 11000
            });
            for (k, x) in extra.as_object().unwrap() {
                v[k] = x.clone();
            }
            AndroidDcfFacts::from_kotlin_json(&v).identity
        };
        assert_eq!(id(serde_json::json!({})), AndroidIdentity::Value(10_000));
        // Kotlin default businessClass is OperatingNonFinancial.
        let mut no_class = serde_json::json!({
            "source": "YahooFinance", "model": "ComponentSum",
            "bearIntrinsicValueCents": 9000, "baseIntrinsicValueCents": 10000,
            "bullIntrinsicValueCents": 11000
        });
        assert_eq!(
            AndroidDcfFacts::from_kotlin_json(&no_class).identity,
            AndroidIdentity::Value(10_000)
        );
        no_class["model"] = "ResidualIncomeEquity".into();
        assert_eq!(
            AndroidDcfFacts::from_kotlin_json(&no_class).identity,
            AndroidIdentity::None
        );
        // Width exactly 12000 bps is usable; above is not; disordered fan is not.
        assert_eq!(
            id(
                serde_json::json!({"bearIntrinsicValueCents": 4000, "bullIntrinsicValueCents": 16000})
            ),
            AndroidIdentity::Value(10_000)
        );
        assert_eq!(
            id(
                serde_json::json!({"bearIntrinsicValueCents": 4000, "bullIntrinsicValueCents": 16001})
            ),
            AndroidIdentity::None
        );
        assert_eq!(
            id(serde_json::json!({"bearIntrinsicValueCents": 10001})),
            AndroidIdentity::None
        );
        for refuse in [
            serde_json::json!({"businessClass": "Unclassified"}),
            serde_json::json!({"businessClass": "NotEligible"}),
            serde_json::json!({"businessClass": "FinancialServices"}),
            serde_json::json!({"valuationUnavailableReason": "x"}),
            serde_json::json!({"model": "None"}),
        ] {
            assert_eq!(id(refuse.clone()), AndroidIdentity::None, "{refuse}");
        }
        // A blank unavailable reason does not refuse (Kotlin isNullOrBlank).
        assert_eq!(
            id(serde_json::json!({"valuationUnavailableReason": " "})),
            AndroidIdentity::Value(10_000)
        );
        assert_eq!(
            id(
                serde_json::json!({"businessClass": "FinancialServices", "model": "ResidualIncomeEquity"})
            ),
            AndroidIdentity::Value(10_000)
        );
    }

    #[test]
    fn android_projection_gates() {
        use crate::engine::ConfidenceBand::*;
        let street = AndroidStreet {
            fair: 15_000,
            low: Some(12_000),
            high: Some(20_000),
            weighted: None,
            weighted_count: None,
            opinions: Some(10),
            age: 0,
        };
        let pu = AndroidDcfFacts {
            provider_uncertain: true,
            restored_only: false,
            source_free: false,
            identity: AndroidIdentity::None,
        };
        let p = |c, comp, s: Option<&AndroidStreet>, d: Option<&AndroidDcfFacts>| {
            android_project_row(c, 10_000, comp, s, d)
        };
        assert_eq!(p(High, 30, Some(&street), None).decision, Some("Act"));
        assert_eq!(p(High, 29, Some(&street), None).decision, Some("Watch"));
        let x = p(High, 45, Some(&street), Some(&pu));
        assert_eq!(
            (x.confidence, x.trust_signal, x.decision),
            (Some(Provisional), Some("SourceUncertain"), Some("Watch"))
        );
        assert_eq!(p(Low, 45, Some(&street), None).decision, Some("Avoid"));
        assert_eq!(p(High, -1, Some(&street), None).decision, Some("Avoid"));
        let thin = AndroidStreet {
            opinions: Some(2),
            ..street.clone()
        };
        assert_eq!(
            p(High, 45, Some(&thin), None).trust_signal,
            Some("ThinCoverage")
        );
        let stale = AndroidStreet {
            age: 31,
            ..street.clone()
        };
        assert_eq!(p(Low, 45, Some(&stale), None).decision, None);
        let x = p(High, 45, Some(&street), None);
        assert_eq!((x.gap_bps, x.upside_bps), (Some(3333), Some(5000)));
        let huge = AndroidStreet {
            fair: 10_000_000,
            high: Some(20_000_000),
            ..street
        };
        assert_eq!(p(High, 45, Some(&huge), None).upside_bps, Some(100_000));
    }
}
