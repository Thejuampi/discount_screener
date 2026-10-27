//! Headless V3 pricing engine (no Tauri UI, no gtk/webkit).
//!
//! Nothing here re-implements scoring. The daemon drives the *same* code the
//! desktop app runs:
//! - feed: `commands::apply_universe_profile` (Yahoo refresh/enrich/EDGAR workers),
//! - scoring: `commands::capture_opportunity_projection` +
//!   `commands::project_opportunity_rows` (the body of the `get_opportunities`
//!   Tauri command),
//! - ordering: the Opportunities list default sort (composite desc, nulls last,
//!   stable), mirrored from `OpportunityList.tsx::sortRows`.
//!
//! The frozen scoring input (`OpportunityProjection`) is persisted as a JSON
//! snapshot so the daemon restarts warm and so rankings can be replayed
//! deterministically (`rank_snapshot`).

use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};

use serde::{Deserialize, Serialize};

use crate::commands::{
    apply_universe_profile, capture_opportunity_projection,
    project_opportunity_rows, OpportunityProjection, OpportunityProjectionInput, OpportunityRow,
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
        let (classes, externals) = {
            let s = screener.lock().unwrap();
            let classes: std::collections::HashMap<String, crate::dcf_model::BusinessClass> = s
                .fundamentals
                .iter()
                .map(|(sym, f)| (sym.clone(), android_class(sym, f)))
                .collect();
            let externals: std::collections::HashMap<String, (i64, u64)> = s
                .signals
                .iter()
                .map(|(sym, x)| (sym.clone(), (x.fair_value_cents, x.age_seconds)))
                .collect();
            (classes, externals)
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
                external: externals.get(&i.row.symbol).copied(),
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
            setup_score: if short { r.composite_score } else { r.setup_score },
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
            setup_label: r.decision.clone(),
            decision: r.decision.clone(),
            confidence: Some(r.confidence.clone()),
            price: r.price_cents as f64 / 100.0,
            target: (r.intrinsic_cents > 0).then(|| r.intrinsic_cents as f64 / 100.0),
            gap_pct: Some(r.gap_bps as f64 / 100.0),
            upside_pct: Some(r.upside_bps as f64 / 100.0),
            fundamentals: r.fundamentals,
            technical: r.technical,
            forecast: r.forecast,
            regime: None,
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
pub fn android_class(symbol: &str, f: &crate::engine::FundamentalSnapshot) -> crate::dcf_model::BusinessClass {
    crate::dcf_model::classify_business_for(
        Some(symbol),
        f.sector_name.as_deref(),
        f.industry_name.as_deref(),
        f.sector_key.as_deref(),
        f.industry_key.as_deref(),
        false,
    )
}

/// One Android Opportunities row (V3, context off).
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
    pub decision: String,
    pub confidence: String,
    pub price_cents: i64,
    pub intrinsic_cents: i64,
    pub gap_bps: i32,
    pub upside_bps: i32,
    pub fundamentals_signals: Vec<String>,
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

/// Port of `app/.../OpportunityDecision.kt` (trust note never set headless).
pub fn android_decision(
    confidence: crate::engine::ConfidenceBand,
    upside_bps: Option<i32>,
    score: i32,
    trust_note: bool,
) -> &'static str {
    use crate::engine::ConfidenceBand::*;
    if confidence == Low {
        return "Avoid";
    }
    let Some(up) = upside_bps else { return "Watch" };
    if up <= 0 || score < 0 {
        return "Avoid";
    }
    if trust_note {
        return "Watch";
    }
    if confidence == High && score >= 30 {
        return "Act";
    }
    "Watch"
}

/// Android `OpportunityEngine.buildRows` for AggressiveV3, regime scoring off:
/// profile universe only, qualified rows only (profitable and gap ≥ 20%),
/// Year (1y/1wk) technicals, Android fundamentals, Android tie-breaks.
/// Android `ReportingEngine` external-signal max age (seconds).
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
            if checked_gap_bps(row.market_price_cents, fair).unwrap_or(i32::MIN) >= ANDROID_MIN_GAP_BPS {
                ExternalSignalStatus::Supportive
            } else {
                ExternalSignalStatus::Divergent
            }
        }
    };
    row.confidence = confidence(row.qualification, row.signal_status, row.analyst_opinion_count);
    row
}

pub fn rank_android_v3(snapshot: &OpportunitySnapshot, universe: Option<&[String]>) -> Vec<AndroidRow> {
    use crate::engine::QualificationStatus;
    use crate::opportunity_v3::{
        composite_score_v3_android, score_forecast_v3, score_fundamentals_v3_android,
    };
    let allowed: Option<std::collections::HashSet<&str>> =
        universe.map(|u| u.iter().map(|s| s.as_str()).collect());
    let mut rows: Vec<(AndroidRow, u8)> = snapshot
        .rows
        .iter()
        .filter(|r| allowed.as_ref().is_none_or(|a| a.contains(r.row.symbol.as_str())))
        .map(|r| (r, android_row(r)))
        .filter(|(_, row)| row.qualification == QualificationStatus::Qualified)
        .map(|(r, row)| {
            let row = &row;
            let (f, sig) = score_fundamentals_v3_android(row, r.business_class);
            let t = crate::android_chart::score_technicals_v3(r.year.as_ref());
            let (fc, _) = score_forecast_v3(row, r.dcf_analysis.as_ref());
            let composite = composite_score_v3_android(f, t, fc, row.beta_millis);
            let coverage = [f, t, fc].iter().filter(|x| x.is_some()).count() as u8;
            let upside = android_upside_bps(row.market_price_cents, row.intrinsic_value_cents);
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
                    decision: android_decision(row.confidence, upside, composite, false).to_string(),
                    confidence: confidence_label(row.confidence).to_string(),
                    price_cents: row.market_price_cents,
                    intrinsic_cents: row.intrinsic_value_cents,
                    gap_bps: row.gap_bps.unwrap_or(0),
                    upside_bps: upside.unwrap_or(0),
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
            .then(b.upside_bps.cmp(&a.upside_bps))
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
    crate::profiles::profile_symbols(profile)
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
            .map(Arc::new);
        apply_universe_profile(&cfg.profile, &state)?;
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
            assert!(!matches!(r.setup_label.as_str(), "StrongBuy" | "HoldWait" | "Buy"));
        }
        assert_eq!(short_setup_label(50), "StrongShort");
        assert_eq!(short_setup_label(-41), "StrongAvoidShort");
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
        ExternalValuationSignal, FundamentalSnapshot, HistoricalCandle,
        MarketSnapshot,
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
        let raw: Value = serde_json::from_slice(
            &std::fs::read(contracts().join("opportunity-v3-headless-fixture.json")).unwrap(),
        )
        .unwrap();
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
        let snap = OpportunitySnapshot::capture_with_year(&screener, "fixture", &year);
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
        if rows.len() != want.len() {
            diffs.push(format!("row count {} vs golden {}", rows.len(), want.len()));
        }
        let by_sym: std::collections::HashMap<&str, &Value> =
            want.iter().map(|g| (g["symbol"].as_str().unwrap(), g)).collect();
        for (i, r) in rows.iter().enumerate() {
            let Some(g) = by_sym.get(r.symbol.as_str()) else {
                diffs.push(format!("{}: ranked by Rust, absent in Kotlin", r.symbol));
                continue;
            };
            let got = serde_json::json!({
                "rank": i + 1, "compositeScore": r.composite_score,
                "coverageCount": r.coverage, "fundamentalsScore": r.fundamentals,
                "technicalScore": r.technical, "forecastScore": r.forecast,
                "confidence": match r.confidence.as_str() { "high" => "High", "low" => "Low", _ => "Provisional" },
                "gapBps": r.gap_bps, "upsideBps": r.upside_bps, "decision": r.decision,
            });
            for (k, v) in got.as_object().unwrap() {
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
        assert!(diffs.is_empty(), "Android parity diffs:\n{}", diffs.join("\n"));
    }

    /// Mutation guard: the golden must catch a one-point coverage-bonus change
    /// (the reviewer's 5 → 6 probe) — i.e. the test is not circular.
    #[test]
    fn golden_detects_a_one_point_composite_shift() {
        let golden: Value = serde_json::from_slice(
            &std::fs::read(contracts().join("opportunity-v3-headless-golden.json")).unwrap(),
        )
        .unwrap();
        let multi_bucket = golden["rows"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|g| g["coverageCount"].as_i64().unwrap() > 1)
            .count();
        assert!(multi_bucket > 0, "fixture must exercise the coverage bonus");
    }

    #[test]
    fn android_decision_gates() {
        use crate::engine::ConfidenceBand::*;
        assert_eq!(android_decision(Low, Some(5000), 90, false), "Avoid");
        assert_eq!(android_decision(High, None, 40, false), "Watch");
        assert_eq!(android_decision(High, Some(0), 40, false), "Avoid");
        assert_eq!(android_decision(High, Some(100), -1, false), "Avoid");
        assert_eq!(android_decision(High, Some(100), 40, true), "Watch");
        assert_eq!(android_decision(High, Some(100), 30, false), "Act");
        assert_eq!(android_decision(High, Some(100), 29, false), "Watch");
        assert_eq!(android_decision(Provisional, Some(100), 80, false), "Watch");
    }
}
