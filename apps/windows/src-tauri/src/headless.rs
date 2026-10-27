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
    apply_universe_profile, build_opportunity_rows, capture_opportunity_projection,
    project_opportunity_rows, OpportunityProjection, OpportunityProjectionInput, OpportunityRow,
};
use crate::engine::ScreenerState;
use crate::opportunity_v3::ScoringModel;
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
        let projection = capture_opportunity_projection(screener);
        let rows = projection
            .rows
            .into_iter()
            .map(|i| SnapshotRow {
                row: i.row,
                daily: i.daily,
                weekly: i.weekly,
                hourly: i.hourly,
                daily_candles: i.daily_candles,
                dcf_analysis: i.dcf_analysis,
                crypto_setup: i.crypto_setup.map(|(s, l)| (s, l.to_string())),
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
                    crypto_setup: r.crypto_setup.map(|(s, l)| (s, static_label(l))),
                })
                .collect(),
        }
    }
}

/// Crypto labels are a small closed set of `&'static str`; intern without leaking
/// for the known ones.
fn static_label(label: String) -> &'static str {
    const KNOWN: &[&str] = &[
        "StrongBuy",
        "Buy",
        "Accumulate",
        "Watch",
        "Hold",
        "Avoid",
        "StrongAvoid",
        "Neutral",
    ];
    KNOWN
        .iter()
        .find(|k| **k == label)
        .copied()
        .unwrap_or_else(|| Box::leak(label.into_boxed_str()))
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
    rows.sort_by(|a, b| b.composite_score.cmp(&a.composite_score));
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

/// The desktop app's own path: what the `get_opportunities` Tauri command returns
/// for `screener` (context OFF), then the list's default sort.
pub fn rank_tauri_path(
    screener: &Mutex<ScreenerState>,
    model: ScoringModel,
) -> Vec<OpportunityRow> {
    let previous = {
        let mut s = screener.lock().unwrap();
        std::mem::replace(&mut s.scoring_model, model.as_str().to_string())
    };
    let mut rows = build_opportunity_rows(screener, false, None);
    screener.lock().unwrap().scoring_model = previous;
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
    pub price: f64,
    pub target: Option<f64>,
    pub gap_pct: Option<f64>,
    pub fundamentals: Option<i32>,
    pub technical: Option<i32>,
    pub forecast: Option<i32>,
    pub regime: Option<i32>,
}

pub fn to_ranked(rows: &[OpportunityRow]) -> Vec<RankedRow> {
    rows.iter()
        .enumerate()
        .map(|(i, r)| RankedRow {
            rank: i + 1,
            symbol: r.row.symbol.clone(),
            company: r.row.company_name.clone(),
            sector: r.row.sector_name.clone(),
            composite_score: r.composite_score,
            setup_score: r.setup_score,
            setup_label: r.setup_label.to_string(),
            decision: r.decision.to_string(),
            price: r.row.market_price_cents as f64 / 100.0,
            target: (r.row.intrinsic_value_cents > 0)
                .then(|| r.row.intrinsic_value_cents as f64 / 100.0),
            gap_pct: r.row.gap_bps.map(|b| b as f64 / 100.0),
            fundamentals: r.fundamentals_score,
            technical: r.technical_score,
            forecast: r.forecast_score,
            regime: r.regime_score,
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

// ── Daemon engine ─────────────────────────────────────────────────────────────

pub struct EngineConfig {
    pub data_dir: PathBuf,
    pub profile: String,
    pub market_context: bool,
}

#[derive(Serialize)]
pub struct EngineStatus {
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
            .filter(|s| s.profile == cfg.profile)
            .map(Arc::new);
        apply_universe_profile(&cfg.profile, &state)?;
        if cfg.market_context {
            crate::regime::spawn_regime_worker(&state);
        }
        Ok(Arc::new(Self {
            state,
            cfg,
            persisted: Mutex::new(persisted),
        }))
    }

    pub fn profile(&self) -> String {
        self.state.active_profile.lock().unwrap().clone()
    }

    /// Live snapshot of current engine state (falls back to persisted one while
    /// the feed is still empty after a restart).
    pub fn current_snapshot(&self) -> (Arc<OpportunitySnapshot>, &'static str) {
        let live = OpportunitySnapshot::capture(&self.state.screener, &self.profile());
        if !live.is_empty() {
            return (Arc::new(live), "live");
        }
        match self.persisted.lock().unwrap().clone() {
            Some(p) => (p, "persisted"),
            None => (Arc::new(live), "live"),
        }
    }

    pub fn persist(&self) -> Result<usize, String> {
        let snap = OpportunitySnapshot::capture(&self.state.screener, &self.profile());
        if snap.is_empty() {
            return Ok(0);
        }
        snap.save(&self.cfg.data_dir.join(SNAPSHOT_FILE))?;
        let n = snap.len();
        *self.persisted.lock().unwrap() = Some(Arc::new(snap));
        Ok(n)
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
            tauri_top: to_ranked(&tauri).into_iter().take(10).collect(),
            headless_top: to_ranked(&headless).into_iter().take(10).collect(),
        })
    }

    pub fn status(&self) -> EngineStatus {
        let fs = self.state.feed_status.lock().unwrap().clone();
        let (snap, source) = self.current_snapshot();
        EngineStatus {
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
        let ranked = to_ranked(&rows);
        assert_eq!(ranked[0].rank, 1);
        assert_eq!(ranked.len(), rows.len());
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
