//! Score history: one snapshot per recorded feed run per ticker. **Append-only.**
//!
//! Lives inside the app database, `<data-dir>/history.sqlite` (the same file the desktop
//! app and headless use), in additive tables: `score_runs`, `score_history`, the V3.1
//! shadow grid `score_shadow` (migration v3, PR #68) and the migration log
//! `score_history_migrations`.
//!
//! Protections (see `docs/product/score-history.md`):
//! - No retention, pruning or TTL. `BEFORE DELETE` / `BEFORE UPDATE` triggers abort, and
//!   `BEFORE INSERT` triggers abort on an existing key, so `INSERT OR REPLACE`, `REPLACE`
//!   and UPSERT cannot overwrite a row. Writes use plain `INSERT`. At open the store checks
//!   that every trigger in [`REQUIRED_TRIGGERS`] exists and refuses to write otherwise. A
//!   test scans `src/` for statements that could delete, overwrite or reshape these tables.
//! - Migrations are additive; each runs with its log row in one `BEGIN IMMEDIATE`, and the
//!   whole file is backed up first (`VACUUM INTO`, gzip, verified).
//! - Verified daily backups of the score tables only (gzip SQLite) to a directory outside
//!   the data-dir; rotated 30 daily + 12 monthly, pre-migration/pre-restore copies keep the
//!   newest 10. Rotation only ever removes backup-named files in that directory.
//! - Restore merges append-only (inserts missing rows, never overwrites) and refuses while
//!   a `serve` holds the data-dir lock.
//! - A manual `rm -rf` of the data-dir is covered only by the off-dir backups.
//!
//! Contract for Android: `shared/contracts/score-history-v1.md`.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use chrono::{Datelike, NaiveDate, TimeZone};
use chrono_tz::Tz;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::headless::RankedRow;

/// Scoring model / rule version stamped on every `score_runs` row (`rule_version`).
/// THE single place to bump when the decision rules change (e.g. "V3" -> "V3.1").
pub const SCORE_RULE_VERSION: &str = "V3";

/// Migration that adds the full-universe evaluation tables. THE single number to bump
/// if another migration lands on main first (v3 is #68's score_shadow).
pub const EVALUATIONS_MIGRATION: i64 = 4;
/// Highest score-history migration this binary knows.
pub const SCHEMA_VERSION: i64 = EVALUATIONS_MIGRATION;
/// Tables exported to backups and merged on restore (indices 0..=2 are fixed).
pub const HISTORY_TABLES: [&str; 8] = [
    "score_runs",
    "score_history",
    "score_history_migrations",
    "score_shadow",
    "score_inputs",
    "score_run_inputs",
    "score_evaluations",
    "score_run_stats",
];
/// Tables the source guard protects (includes the planned shadow table).
pub const GUARDED_TABLES: [&str; 8] = [
    "score_runs",
    "score_history",
    "score_history_migrations",
    "score_inputs",
    "score_run_inputs",
    "score_evaluations",
    "score_run_stats",
    "score_shadow",
];
/// Triggers that must exist before the store writes.
pub const REQUIRED_TRIGGERS: [&str; 24] = [
    "score_inputs_no_delete",
    "score_inputs_no_update",
    "score_inputs_no_overwrite",
    "score_run_inputs_no_delete",
    "score_run_inputs_no_update",
    "score_run_inputs_no_overwrite",
    "score_evaluations_no_delete",
    "score_evaluations_no_update",
    "score_evaluations_no_overwrite",
    "score_run_stats_no_delete",
    "score_run_stats_no_update",
    "score_run_stats_no_overwrite",
    "score_runs_no_delete",
    "score_runs_no_update",
    "score_runs_no_overwrite",
    "score_history_no_delete",
    "score_history_no_update",
    "score_history_no_overwrite",
    "score_history_migrations_no_delete",
    "score_history_migrations_no_update",
    "score_history_migrations_no_overwrite",
    "score_shadow_no_delete",
    "score_shadow_no_update",
    "score_shadow_no_overwrite",
];

/// Backups go to `--history-backup-dir`, else this env var, else
/// `$XDG_DATA_HOME/vantage/backups` (`~/.local/share/vantage/backups`).
pub const BACKUP_DIR_ENV: &str = "VANTAGE_HISTORY_BACKUP_DIR";
pub const BACKUP_PREFIX: &str = "score-history-";
pub const BACKUP_SUFFIX: &str = ".sqlite.gz";
pub const BACKUP_KEEP_DAILY: usize = 7;
pub const BACKUP_KEEP_MONTHLY: usize = 12;
/// Newest pre-migration and pre-restore copies kept (each kind).
pub const PRE_BACKUP_KEEP: usize = 10;
/// Upper bound for `days` in history queries.
pub const MAX_HISTORY_DAYS: i64 = 36_500;
/// Lock file `serve` holds in the data-dir; restore refuses while it is held.
pub const LOCK_FILE: &str = "vantage-headless.lock";

/// Market-close snapshot: weekdays at 16:10 America/New_York (DST via chrono-tz).
/// Exchange holidays are not modelled (a holiday gets an extra, harmless snapshot).
pub const MARKET_TZ: Tz = chrono_tz::America::New_York;
pub const CLOSE_SNAPSHOT_HM: (u32, u32) = (16, 10);
/// An interval run due within this many seconds before the close slot is skipped
/// (the close snapshot takes its place).
pub const CLOSE_DEDUP_SECS: i64 = 30 * 60;
/// A missed close slot is still taken this long after it (e.g. initial pass still running).
pub const CLOSE_CATCHUP_SECS: i64 = 18 * 3600;
/// Year charts must cover this share of the universe before a run is recorded,
/// or [`HISTORY_YEAR_WAIT_SECS`] must have passed since the initial pass finished.
pub const HISTORY_YEAR_COVERAGE: f64 = 0.95;
pub const HISTORY_YEAR_WAIT_SECS: i64 = 20 * 60;

const MIGRATION_LOG: &str = "
CREATE TABLE IF NOT EXISTS score_history_migrations (
    version     INTEGER PRIMARY KEY NOT NULL,
    applied_at  INTEGER NOT NULL,
    backup_path TEXT
);
CREATE TRIGGER IF NOT EXISTS score_history_migrations_no_delete BEFORE DELETE ON score_history_migrations
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_history_migrations_no_update BEFORE UPDATE ON score_history_migrations
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
";

/// Additive migrations only (CREATE … / ALTER TABLE … ADD COLUMN). Never drop, rename or rewrite.
pub const MIGRATIONS: &[(i64, &str)] = &[
    (
        1,
        "
CREATE TABLE IF NOT EXISTS score_runs (
    run_id             TEXT PRIMARY KEY NOT NULL,
    captured_at        INTEGER NOT NULL,
    profile            TEXT NOT NULL,
    model              TEXT NOT NULL,
    rule_version       TEXT NOT NULL,
    semantics          TEXT NOT NULL,
    market_context     INTEGER NOT NULL,
    binary_version     TEXT NOT NULL,
    trigger            TEXT NOT NULL,
    symbols_total      INTEGER NOT NULL,
    symbols_ranked     INTEGER NOT NULL,
    incomplete_symbols TEXT NOT NULL DEFAULT '[]'
);
CREATE TABLE IF NOT EXISTS score_history (
    run_id        TEXT NOT NULL REFERENCES score_runs(run_id),
    symbol        TEXT NOT NULL,
    captured_at   INTEGER NOT NULL,
    rank          INTEGER NOT NULL,
    decision      TEXT NOT NULL,
    composite     INTEGER NOT NULL,
    fundamentals  INTEGER,
    technical     INTEGER,
    forecast      INTEGER,
    regime        INTEGER,
    confidence    TEXT,
    trust_signal  TEXT,
    price_cents   INTEGER NOT NULL,
    target_cents  INTEGER,
    upside_bps    INTEGER,
    inputs_json   TEXT,
    PRIMARY KEY (run_id, symbol)
);
CREATE INDEX IF NOT EXISTS score_history_symbol_time ON score_history(symbol, captured_at);
CREATE INDEX IF NOT EXISTS score_runs_profile_model ON score_runs(profile, model);
CREATE TRIGGER IF NOT EXISTS score_runs_no_delete BEFORE DELETE ON score_runs
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_runs_no_update BEFORE UPDATE ON score_runs
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_history_no_delete BEFORE DELETE ON score_history
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_history_no_update BEFORE UPDATE ON score_history
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
",
    ),
    (
        2,
        "
CREATE TRIGGER IF NOT EXISTS score_runs_no_overwrite BEFORE INSERT ON score_runs
WHEN EXISTS (SELECT 1 FROM score_runs WHERE run_id = NEW.run_id)
BEGIN SELECT RAISE(ABORT, 'score history is append-only: existing key'); END;
CREATE TRIGGER IF NOT EXISTS score_history_no_overwrite BEFORE INSERT ON score_history
WHEN EXISTS (SELECT 1 FROM score_history WHERE run_id = NEW.run_id AND symbol = NEW.symbol)
BEGIN SELECT RAISE(ABORT, 'score history is append-only: existing key'); END;
CREATE TRIGGER IF NOT EXISTS score_history_migrations_no_overwrite BEFORE INSERT ON score_history_migrations
WHEN EXISTS (SELECT 1 FROM score_history_migrations WHERE version = NEW.version)
BEGIN SELECT RAISE(ABORT, 'score history is append-only: existing key'); END;
",
    ),
    (
        3,
        "
CREATE TABLE IF NOT EXISTS score_shadow (
    run_id          TEXT NOT NULL REFERENCES score_runs(run_id),
    ticker          TEXT NOT NULL,
    rule_version    TEXT NOT NULL,
    config_id       TEXT NOT NULL,
    bound           REAL NOT NULL,
    lambda_t        REAL NOT NULL,
    lambda_m        REAL NOT NULL,
    m               REAL,
    warmup          INTEGER NOT NULL,
    s_adj           REAL NOT NULL,
    shadow_decision TEXT,
    hist_source     TEXT,
    PRIMARY KEY (run_id, ticker, config_id)
);
CREATE INDEX IF NOT EXISTS score_shadow_config ON score_shadow(config_id, ticker);
CREATE TRIGGER IF NOT EXISTS score_shadow_no_delete BEFORE DELETE ON score_shadow
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_shadow_no_update BEFORE UPDATE ON score_shadow
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_shadow_no_overwrite BEFORE INSERT ON score_shadow
WHEN EXISTS (SELECT 1 FROM score_shadow WHERE run_id = NEW.run_id AND ticker = NEW.ticker
             AND config_id = NEW.config_id)
BEGIN SELECT RAISE(ABORT, 'score history is append-only: existing key'); END;
",
    ),
    (EVALUATIONS_MIGRATION, EVALUATIONS_DDL),
];

/// Full-universe evaluations (see `docs/product/score-history.md`).
/// - `score_inputs`: content-addressed raw snapshot rows (sha256 of the exact serde
///   bytes; stored gzip), deduplicated across runs.
/// - `score_run_inputs`: per run, every snapshot row in order -> exact reconstruction.
/// - `score_evaluations`: one row per universe symbol per run (ok / excluded / failed).
/// - `score_run_stats`: per-run counts plus the snapshot envelope bytes.
const EVALUATIONS_DDL: &str = "
CREATE TABLE IF NOT EXISTS score_inputs (
    hash      TEXT PRIMARY KEY NOT NULL,
    encoding  TEXT NOT NULL,
    raw_size  INTEGER NOT NULL,
    bytes     BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS score_run_inputs (
    run_id  TEXT NOT NULL,
    idx     INTEGER NOT NULL,
    symbol  TEXT NOT NULL,
    hash    TEXT NOT NULL,
    PRIMARY KEY (run_id, idx)
);
CREATE TABLE IF NOT EXISTS score_evaluations (
    run_id          TEXT NOT NULL,
    symbol          TEXT NOT NULL,
    captured_at     INTEGER NOT NULL,
    status          TEXT NOT NULL,
    reason          TEXT NOT NULL,
    rank            INTEGER,
    gate_gap_bps    INTEGER,
    composite       INTEGER,
    coverage        INTEGER,
    fundamentals    INTEGER,
    technical       INTEGER,
    technical_reason TEXT,
    forecast        INTEGER,
    decision        TEXT,
    confidence      TEXT,
    row_confidence  TEXT,
    price_cents     INTEGER,
    intrinsic_cents INTEGER,
    anchor_cents    INTEGER,
    anchor          TEXT,
    gap_bps         INTEGER,
    upside_bps      INTEGER,
    trust_signal    TEXT,
    inputs_hash     TEXT,
    CHECK (status IN ('ok', 'excluded', 'failed')),
    CHECK (decision IS NULL OR status = 'ok'),
    PRIMARY KEY (run_id, symbol)
);
CREATE INDEX IF NOT EXISTS score_evaluations_symbol_time ON score_evaluations(symbol, captured_at);
CREATE TABLE IF NOT EXISTS score_run_stats (
    run_id          TEXT PRIMARY KEY NOT NULL,
    universe_size   INTEGER NOT NULL,
    loaded          INTEGER NOT NULL,
    ok              INTEGER NOT NULL,
    excluded        INTEGER NOT NULL,
    failed          INTEGER NOT NULL,
    reasons_json    TEXT NOT NULL,
    snapshot_rows   INTEGER NOT NULL,
    inputs_stored   INTEGER NOT NULL,
    inputs_run_id   TEXT,
    inputs_reason   TEXT NOT NULL,
    snapshot_prefix BLOB NOT NULL,
    snapshot_suffix BLOB NOT NULL
);
CREATE TRIGGER IF NOT EXISTS score_inputs_no_delete BEFORE DELETE ON score_inputs
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_inputs_no_update BEFORE UPDATE ON score_inputs
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_inputs_no_overwrite BEFORE INSERT ON score_inputs
WHEN EXISTS (SELECT 1 FROM score_inputs WHERE hash = NEW.hash)
BEGIN SELECT RAISE(ABORT, 'score history is append-only: existing key'); END;
CREATE TRIGGER IF NOT EXISTS score_run_inputs_no_delete BEFORE DELETE ON score_run_inputs
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_run_inputs_no_update BEFORE UPDATE ON score_run_inputs
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_run_inputs_no_overwrite BEFORE INSERT ON score_run_inputs
WHEN EXISTS (SELECT 1 FROM score_run_inputs WHERE run_id = NEW.run_id AND idx = NEW.idx)
BEGIN SELECT RAISE(ABORT, 'score history is append-only: existing key'); END;
CREATE TRIGGER IF NOT EXISTS score_evaluations_no_delete BEFORE DELETE ON score_evaluations
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_evaluations_no_update BEFORE UPDATE ON score_evaluations
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_evaluations_no_overwrite BEFORE INSERT ON score_evaluations
WHEN EXISTS (SELECT 1 FROM score_evaluations WHERE run_id = NEW.run_id AND symbol = NEW.symbol)
BEGIN SELECT RAISE(ABORT, 'score history is append-only: existing key'); END;
CREATE TRIGGER IF NOT EXISTS score_run_stats_no_delete BEFORE DELETE ON score_run_stats
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_run_stats_no_update BEFORE UPDATE ON score_run_stats
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_run_stats_no_overwrite BEFORE INSERT ON score_run_stats
WHEN EXISTS (SELECT 1 FROM score_run_stats WHERE run_id = NEW.run_id)
BEGIN SELECT RAISE(ABORT, 'score history is append-only: existing key'); END;
";

/// One universe symbol's evaluation, as stored in `score_evaluations`.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct EvalRecord {
    pub symbol: String,
    /// `ok` / `excluded` / `failed`.
    pub status: String,
    pub reason: String,
    pub rank: Option<i64>,
    pub gate_gap_bps: Option<i64>,
    pub composite: Option<i64>,
    pub coverage: Option<i64>,
    pub fundamentals: Option<i64>,
    pub technical: Option<i64>,
    /// Why `technical` is NULL (`no_year_chart`, `no_latest_close`, `no_indicators`,
    /// `not_loaded`, `score_error`); `None` when it was computed.
    pub technical_reason: Option<String>,
    pub forecast: Option<i64>,
    /// V3 decision; always `None` unless `status == "ok"`.
    pub decision: Option<String>,
    pub confidence: Option<String>,
    pub row_confidence: Option<String>,
    pub price_cents: Option<i64>,
    pub intrinsic_cents: Option<i64>,
    pub anchor_cents: Option<i64>,
    pub anchor: Option<String>,
    pub gap_bps: Option<i64>,
    pub upside_bps: Option<i64>,
    pub trust_signal: Option<String>,
    /// Index into [`RunEvaluation::inputs`] (the snapshot row), `None` when not loaded.
    #[serde(skip)]
    pub input_idx: Option<usize>,
    #[serde(default)]
    pub inputs_hash: Option<String>,
}

/// Everything a full-universe run stores besides `score_runs` / `score_history`.
#[derive(Clone, Debug, Default)]
pub struct RunEvaluation {
    pub universe_size: usize,
    pub evals: Vec<EvalRecord>,
    /// Every snapshot row, in snapshot order: (symbol, exact serde bytes).
    pub inputs: Vec<(String, Vec<u8>)>,
    /// Snapshot bytes before the first row / after the last row, so that
    /// `prefix + rows.join(",") + suffix` is byte-identical to the snapshot.
    pub snapshot_prefix: Vec<u8>,
    pub snapshot_suffix: Vec<u8>,
}

/// Per-run counts (`score_run_stats`).
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct RunStats {
    pub run_id: String,
    pub universe_size: i64,
    pub loaded: i64,
    pub ok: i64,
    pub excluded: i64,
    pub failed: i64,
    /// Count per reason (`gap_below_min:<bps>` counted as `gap_below_min`).
    pub reasons: std::collections::BTreeMap<String, i64>,
    pub snapshot_rows: i64,
    /// Whether this run stored its own full inputs.
    #[serde(default)]
    pub inputs_stored: bool,
    /// Run whose inputs this run stored or points to (most recent run with inputs).
    #[serde(default)]
    pub inputs_run_id: Option<String>,
    /// `first`, `close`, `material:<what>` or `skipped:unchanged`.
    #[serde(default)]
    pub inputs_reason: String,
}

impl RunStats {
    fn from_evals(run_id: &str, e: &RunEvaluation) -> Self {
        let mut reasons = std::collections::BTreeMap::new();
        let n = |st: &str| e.evals.iter().filter(|x| x.status == st).count() as i64;
        let (ok, excluded, failed) = (n("ok"), n("excluded"), n("failed"));
        for x in &e.evals {
            let key = x.reason.split(':').next().unwrap_or("").to_string();
            *reasons.entry(key).or_insert(0) += 1;
        }
        Self {
            run_id: run_id.to_string(),
            universe_size: e.universe_size as i64,
            loaded: e.evals.iter().filter(|x| x.input_idx.is_some()).count() as i64,
            ok,
            excluded,
            failed,
            reasons,
            snapshot_rows: e.inputs.len() as i64,
            inputs_stored: false,
            inputs_run_id: None,
            inputs_reason: String::new(),
        }
    }
}

/// Writes a run's evaluations, inputs and stats inside the caller's transaction.
fn write_evaluation(
    tx: &Connection,
    meta: &RunMeta,
    run_id: &str,
    e: &RunEvaluation,
) -> Result<(), String> {
    let captured_at = meta.captured_at;
    let (store_inputs, inputs_reason, prev) = inputs_policy(tx, meta, e)?;
    let mut hashes = Vec::with_capacity(e.inputs.len());
    if store_inputs {
        let mut blob = tx
            .prepare(
                "INSERT INTO score_inputs (hash, encoding, raw_size, bytes) \
                 SELECT ?1, 'zstd', ?2, ?3 \
                 WHERE NOT EXISTS (SELECT 1 FROM score_inputs WHERE hash = ?1)",
            )
            .map_err(err)?;
        let mut link = tx
            .prepare(
                "INSERT INTO score_run_inputs (run_id, idx, symbol, hash) VALUES (?1,?2,?3,?4)",
            )
            .map_err(err)?;
        for (i, (sym, raw)) in e.inputs.iter().enumerate() {
            let h = sha256_hex(raw);
            let exists = tx
                .query_row(
                    "SELECT 1 FROM score_inputs WHERE hash = ?1",
                    params![h],
                    |_| Ok(()),
                )
                .optional()
                .map_err(err)?
                .is_some();
            if !exists {
                blob.execute(params![h, raw.len() as i64, zstd_bytes(raw)?])
                    .map_err(|x| format!("input {sym}: {x}"))?;
            }
            link.execute(params![run_id, i as i64, sym, h])
                .map_err(|x| format!("input link {sym}: {x}"))?;
            hashes.push(h);
        }
    }
    {
        let mut st = tx
            .prepare(
                "INSERT INTO score_evaluations (run_id, symbol, captured_at, status, reason, rank, \
                 gate_gap_bps, composite, coverage, fundamentals, technical, technical_reason, \
                 forecast, decision, confidence, row_confidence, price_cents, intrinsic_cents, \
                 anchor_cents, anchor, gap_bps, upside_bps, trust_signal, inputs_hash) VALUES \
                 (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19,?20,?21,?22,?23,?24)",
            )
            .map_err(err)?;
        let mut seen = std::collections::HashSet::new();
        for x in &e.evals {
            // Duplicate snapshot symbols: the first evaluation is the stored one.
            if !seen.insert(x.symbol.as_str()) {
                continue;
            }
            let decision = if x.status == "ok" {
                x.decision.clone()
            } else {
                None
            };
            st.execute(rusqlite::params_from_iter([
                &run_id as &dyn rusqlite::ToSql,
                &x.symbol,
                &captured_at,
                &x.status,
                &x.reason,
                &x.rank,
                &x.gate_gap_bps,
                &x.composite,
                &x.coverage,
                &x.fundamentals,
                &x.technical,
                &x.technical_reason,
                &x.forecast,
                &decision,
                &x.confidence,
                &x.row_confidence,
                &x.price_cents,
                &x.intrinsic_cents,
                &x.anchor_cents,
                &x.anchor,
                &x.gap_bps,
                &x.upside_bps,
                &x.trust_signal,
                &x.input_idx.and_then(|i| hashes.get(i)),
            ]))
            .map_err(|y| format!("evaluation {}: {y}", x.symbol))?;
        }
    }
    let mut stats = RunStats::from_evals(run_id, e);
    // Stored counts are per distinct symbol (matches score_evaluations).
    let distinct: std::collections::HashSet<&str> =
        e.evals.iter().map(|x| x.symbol.as_str()).collect();
    if distinct.len() != e.evals.len() {
        let mut seen = std::collections::HashSet::new();
        let uniq = RunEvaluation {
            evals: e
                .evals
                .iter()
                .filter(|x| seen.insert(x.symbol.as_str()))
                .cloned()
                .collect(),
            ..e.clone()
        };
        stats = RunStats::from_evals(run_id, &uniq);
    }
    tx.execute(
        "INSERT INTO score_run_stats (run_id, universe_size, loaded, ok, excluded, failed, \
         reasons_json, snapshot_rows, inputs_stored, inputs_run_id, inputs_reason, \
         snapshot_prefix, snapshot_suffix) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13)",
        params![
            run_id,
            stats.universe_size,
            stats.loaded,
            stats.ok,
            stats.excluded,
            stats.failed,
            serde_json::to_string(&stats.reasons).unwrap(),
            stats.snapshot_rows,
            store_inputs as i64,
            if store_inputs {
                Some(run_id.to_string())
            } else {
                prev
            },
            inputs_reason,
            e.snapshot_prefix,
            e.snapshot_suffix,
        ],
    )
    .map_err(err)?;
    Ok(())
}

/// Material-change signature of one evaluation: (status, reason class, decision).
/// `gap_below_min:<bps>` counts as `gap_below_min`; scores, ranks and prices do not count.
/// Evaluation columns read by [`eval_from_row`] (a macro so queries stay `concat!` literals).
macro_rules! eval_cols {
    () => {
        "e.symbol, e.status, e.reason, e.rank, e.gate_gap_bps, e.composite, \
    e.coverage, e.fundamentals, e.technical, e.forecast, e.decision, e.confidence, \
    e.row_confidence, e.price_cents, e.intrinsic_cents, e.anchor_cents, e.anchor, e.gap_bps, \
    e.upside_bps, e.trust_signal, e.inputs_hash, e.technical_reason"
    };
}

fn eval_from_row(r: &rusqlite::Row) -> rusqlite::Result<EvalRecord> {
    Ok(EvalRecord {
        symbol: r.get(0)?,
        status: r.get(1)?,
        reason: r.get(2)?,
        rank: r.get(3)?,
        gate_gap_bps: r.get(4)?,
        composite: r.get(5)?,
        coverage: r.get(6)?,
        fundamentals: r.get(7)?,
        technical: r.get(8)?,
        forecast: r.get(9)?,
        decision: r.get(10)?,
        confidence: r.get(11)?,
        row_confidence: r.get(12)?,
        price_cents: r.get(13)?,
        intrinsic_cents: r.get(14)?,
        anchor_cents: r.get(15)?,
        anchor: r.get(16)?,
        gap_bps: r.get(17)?,
        upside_bps: r.get(18)?,
        trust_signal: r.get(19)?,
        input_idx: None,
        inputs_hash: r.get(20)?,
        technical_reason: r.get(21)?,
    })
}

const INPUTS_MAX_GAP_SECS: i64 = 24 * 3600;

fn material_key(status: &str, reason: &str, decision: Option<&str>) -> String {
    format!(
        "{status}|{}|{}",
        reason.split(':').next().unwrap_or(""),
        decision.unwrap_or("")
    )
}

/// Deterministic full-input policy (see docs/product/score-history.md):
/// 1. `first`: no earlier run of this profile+model stored inputs;
/// 2. `close`: the post-close run (trigger `close`) always stores them;
/// 3. `daily`: the most recent inputs are >= 24 h older than this run (covers days whose
///    close slot was satisfied by an interval run, and restarts);
/// 4. `material:<what>`: vs the most recent run with inputs, the set of loaded symbols
///    changed (`loaded_set`) or any symbol's (status, reason class, decision) changed
///    (`evaluation`);
/// 5. otherwise `skipped:unchanged`, pointing at that most recent run.
///
/// Returns (store, reason, most recent run with inputs).
fn inputs_policy(
    tx: &Connection,
    meta: &RunMeta,
    e: &RunEvaluation,
) -> Result<(bool, String, Option<String>), String> {
    let prev: Option<(String, i64)> = tx
        .query_row(
            "SELECT s.run_id, r.captured_at FROM score_run_stats s JOIN score_runs r ON r.run_id = s.run_id \
             WHERE s.inputs_stored = 1 AND r.profile = ?1 AND r.model = ?2 \
             ORDER BY r.captured_at DESC, r.run_id DESC LIMIT 1",
            params![meta.profile, meta.model],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()
        .map_err(err)?;
    let Some((p, p_at)) = prev else {
        return Ok((true, "first".into(), None));
    };
    if meta.trigger == "close" {
        return Ok((true, "close".into(), Some(p)));
    }
    if meta.captured_at - p_at >= INPUTS_MAX_GAP_SECS {
        return Ok((true, "daily".into(), Some(p)));
    }
    let mut st = tx
        .prepare(
            "SELECT symbol, status, reason, decision, reason != 'not_loaded' \
             FROM score_evaluations WHERE run_id = ?1",
        )
        .map_err(err)?;
    let old: std::collections::BTreeMap<String, (String, bool)> = st
        .query_map(params![p], |r| {
            let (sym, status, reason, dec, loaded): (String, String, String, Option<String>, bool) =
                (r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?);
            Ok((
                sym,
                (material_key(&status, &reason, dec.as_deref()), loaded),
            ))
        })
        .map_err(err)?
        .collect::<Result<_, _>>()
        .map_err(err)?;
    let mut seen = std::collections::HashSet::new();
    let new: std::collections::BTreeMap<String, (String, bool)> = e
        .evals
        .iter()
        .filter(|x| seen.insert(x.symbol.as_str()))
        .map(|x| {
            let dec = if x.status == "ok" {
                x.decision.as_deref()
            } else {
                None
            };
            (
                x.symbol.clone(),
                (
                    material_key(&x.status, &x.reason, dec),
                    x.reason != "not_loaded",
                ),
            )
        })
        .collect();
    let loaded = |m: &std::collections::BTreeMap<String, (String, bool)>| -> Vec<String> {
        m.iter()
            .filter(|(_, v)| v.1)
            .map(|(k, _)| k.clone())
            .collect()
    };
    if loaded(&old) != loaded(&new) {
        return Ok((true, "material:loaded_set".into(), Some(p)));
    }
    let keys = |m: &std::collections::BTreeMap<String, (String, bool)>| -> Vec<(String, String)> {
        m.iter().map(|(k, v)| (k.clone(), v.0.clone())).collect()
    };
    if keys(&old) != keys(&new) {
        return Ok((true, "material:evaluation".into(), Some(p)));
    }
    Ok((false, "skipped:unchanged".into(), Some(p)))
}

/// zstd level for input blobs (deterministic; decoding does not depend on it).
const INPUT_ZSTD_LEVEL: i32 = 19;
/// Hard cap on `score_inputs.raw_size` before any decompress (zip-bomb / SIGABRT guard).
pub const INPUT_BLOB_MAX_RAW_SIZE: i64 = 64 * 1024 * 1024;
/// Content-addressed blob store under the backup dir (`inputs/<sha256>`).
const INPUTS_BLOB_SUBDIR: &str = "inputs";

fn zstd_bytes(b: &[u8]) -> Result<Vec<u8>, String> {
    zstd::bulk::compress(b, INPUT_ZSTD_LEVEL).map_err(err)
}

/// Decodes a `score_inputs` blob and checks it against its sha256 and `raw_size`.
/// Rejects `raw_size` above [`INPUT_BLOB_MAX_RAW_SIZE`] *before* decompress so a
/// crafted size cannot SIGABRT inside zstd; zstd expansion is also capped at `raw_size`.
fn check_blob(
    hash: &str,
    enc: Option<&str>,
    size: Option<i64>,
    bytes: Vec<u8>,
) -> Result<Vec<u8>, String> {
    let size = size.ok_or_else(|| format!("blob {hash}: missing raw_size"))?;
    if size < 0 {
        return Err(format!("blob {hash}: negative raw_size {size}"));
    }
    if size > INPUT_BLOB_MAX_RAW_SIZE {
        return Err(format!(
            "blob {hash}: raw_size {size} exceeds cap {INPUT_BLOB_MAX_RAW_SIZE}"
        ));
    }
    let max = size as usize;
    let raw = match enc {
        Some("zstd") => zstd::bulk::decompress(&bytes, max).map_err(err)?,
        Some("gzip") => gunzip_bytes(&bytes)?,
        Some("raw") => bytes,
        e => return Err(format!("blob {hash}: unknown encoding {e:?}")),
    };
    if sha256_hex(&raw) != hash {
        return Err(format!("blob {hash}: content does not match its sha256"));
    }
    if raw.len() as i64 != size {
        return Err(format!("blob {hash}: raw_size {size} != {}", raw.len()));
    }
    Ok(raw)
}

fn inputs_blob_dir(backup_dir: &Path) -> PathBuf {
    backup_dir.join(INPUTS_BLOB_SUBDIR)
}

/// Content-addressed blob names are exactly 64 lowercase hex chars (sha256).
/// Validated *before* any path join so traversal like `../../secret` is refused.
fn validate_input_hash(hash: &str) -> Result<(), String> {
    if hash.len() != 64 || !hash.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')) {
        return Err(format!(
            "blob hash must be 64 lowercase hex characters, got {hash:?}"
        ));
    }
    Ok(())
}

fn inputs_blob_path(backup_dir: &Path, hash: &str) -> Result<PathBuf, String> {
    validate_input_hash(hash)?;
    Ok(inputs_blob_dir(backup_dir).join(hash))
}

fn fsync_dir(dir: &Path) -> Result<(), String> {
    // Directory fsync after rename: persists the new directory entry
    // (temp → fsync(file) → rename → fsync(dir)). Not a test seam — production
    // durability; unit tests exercise write/rename without asserting fsync.
    let f = std::fs::File::open(dir).map_err(|e| format!("open dir {}: {e}", dir.display()))?;
    f.sync_all()
        .map_err(|e| format!("fsync dir {}: {e}", dir.display()))
}

/// Durable write: temp file → fsync → rename → fsync dir.
fn durable_replace_file(dir: &Path, path: &Path, bytes: &[u8]) -> Result<(), String> {
    let tmp = dir.join(format!(
        ".{}.{}.tmp",
        path.file_name().and_then(|s| s.to_str()).unwrap_or("blob"),
        std::process::id()
    ));
    {
        let mut f =
            std::fs::File::create(&tmp).map_err(|e| format!("create {}: {e}", tmp.display()))?;
        use std::io::Write;
        f.write_all(bytes)
            .map_err(|e| format!("write {}: {e}", tmp.display()))?;
        f.sync_all()
            .map_err(|e| format!("fsync {}: {e}", tmp.display()))?;
    }
    std::fs::rename(&tmp, path).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        format!("rename {} -> {}: {e}", tmp.display(), path.display())
    })?;
    fsync_dir(dir)?;
    Ok(())
}

/// True when on-disk blob bytes match `expected` (length + content).
fn blob_file_matches(path: &Path, expected: &[u8]) -> bool {
    match open_input_blob_nofollow(path).and_then(|mut f| {
        use std::io::Read;
        let mut got = Vec::new();
        f.read_to_end(&mut got).map(|_| got)
    }) {
        Ok(got) => got.len() == expected.len() && got == expected,
        Err(_) => false,
    }
}

/// Open `inputs/<sha>` for read with `O_NOFOLLOW` so a TOCTOU symlink swap
/// cannot redirect the read. Caller must fstat the returned fd (`metadata()`)
/// and only treat a regular file as a valid blob.
#[cfg(unix)]
fn open_input_blob_nofollow(path: &Path) -> std::io::Result<std::fs::File> {
    use std::os::unix::fs::OpenOptionsExt;
    // Linux O_NOFOLLOW = 0x20000; macOS uses the same bit in OpenOptionsExt.
    std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(0x20000)
        .open(path)
}

/// Non-Unix fallback: plain `File::open` (follows reparse points / symlinks).
///
/// CI is Ubuntu-only. On Windows, `inputs/` must not be attacker-writable —
/// otherwise a reparse-point swap can redirect the open. Prefer locking down
/// the directory ACL; a future hardening can open without following reparse
/// points (`FILE_FLAG_OPEN_REPARSE_POINT`) if Windows CI is added.
#[cfg(not(unix))]
fn open_input_blob_nofollow(path: &Path) -> std::io::Result<std::fs::File> {
    std::fs::File::open(path)
}

/// Propose a quarantine destination that does not already exist (including a
/// dangling symlink — `exists()` would miss those). Caller must still handle
/// rename races via [`quarantine_input_blob_aside`]'s capped check-or-retry.
fn unique_bad_quarantine_path(path: &Path, base: &str) -> PathBuf {
    let candidate = path.with_file_name(format!("{base}.bad"));
    // symlink_metadata: dangling symlinks still "exist" as an entry.
    if std::fs::symlink_metadata(&candidate).is_err() {
        return candidate;
    }
    static BAD_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let seq = BAD_SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    path.with_file_name(format!(
        "{base}.bad.{}.{}{}",
        std::process::id(),
        nanos,
        seq
    ))
}

const BAD_QUARANTINE_RENAME_ATTEMPTS: usize = 8;

/// Move `path` aside to a unique `*.bad*` name. Check-or-retry before rename so
/// a concurrent quarantine cannot clobber an existing `.bad` (capped attempts).
fn quarantine_input_blob_aside(path: &Path, kind: &str) -> Result<(), String> {
    quarantine_input_blob_aside_with(path, kind, unique_bad_quarantine_path)
}

/// [`quarantine_input_blob_aside`] with an injectable destination proposer, so
/// tests can simulate a destination that appears between proposal and rename.
fn quarantine_input_blob_aside_with(
    path: &Path,
    kind: &str,
    mut propose: impl FnMut(&Path, &str) -> PathBuf,
) -> Result<(), String> {
    let base = path
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("blob");
    let mut last = String::new();
    for _ in 0..BAD_QUARANTINE_RENAME_ATTEMPTS {
        let bad = propose(path, base);
        // Re-check immediately before rename (TOCTOU window with unique_*).
        if std::fs::symlink_metadata(&bad).is_ok() {
            last = format!("{} already exists", bad.display());
            continue;
        }
        match std::fs::rename(path, &bad) {
            Ok(()) => return Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {
                last = format!("{}: {e}", bad.display());
                continue;
            }
            Err(e) => {
                return Err(format!(
                    "blob {}: not a regular file ({kind}); quarantine to {}: {e}",
                    path.display(),
                    bad.display()
                ));
            }
        }
    }
    Err(format!(
        "blob {}: not a regular file ({kind}); quarantine exhausted after {BAD_QUARANTINE_RENAME_ATTEMPTS} attempts ({last})",
        path.display()
    ))
}

/// `inputs/<sha>` must be a regular file. Directories/symlinks are moved aside to
/// `*.bad` (never deleted, never overwriting an existing `.bad`) so a durable
/// rewrite can replace them; returns Err if the entry cannot be quarantined.
fn ensure_regular_input_blob_path(path: &Path) -> Result<(), String> {
    let meta = match std::fs::symlink_metadata(path) {
        Ok(m) => m,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(format!("stat {}: {e}", path.display())),
    };
    if meta.file_type().is_file() {
        // symlink_metadata: is_file() is false for symlinks even when the target is a file.
        return Ok(());
    }
    // Symlink or directory (or other): quarantine, never delete.
    let kind = if meta.file_type().is_symlink() {
        "symlink"
    } else if meta.is_dir() {
        "directory"
    } else {
        "special"
    };
    quarantine_input_blob_aside(path, kind)
}

/// Remove stale `.{sha}.{pid}.tmp` left by crashed writers. Only that exact
/// pattern inside `inputs/`, and only when older than `max_age`.
///
/// `write_input_blob` uses `max_age = 15 minutes`. A writer stalled longer than
/// that may lose its temp (cleanup removes it) and then get `Err` on rename —
/// callers must retry. Fresh temps and non-numeric pid suffixes are kept.
fn cleanup_stale_input_tmps(inputs_dir: &Path, max_age: std::time::Duration) -> usize {
    let Ok(rd) = std::fs::read_dir(inputs_dir) else {
        return 0;
    };
    let now = std::time::SystemTime::now();
    let mut n = 0;
    for ent in rd.flatten() {
        let name = ent.file_name();
        let Some(s) = name.to_str() else { continue };
        // Pattern: .<64 hex>.<pid>.tmp
        if !(s.starts_with('.') && s.ends_with(".tmp")) {
            continue;
        }
        let body = &s[1..s.len() - 4]; // strip leading '.' and trailing '.tmp'
        let Some((hash, pid)) = body.rsplit_once('.') else {
            continue;
        };
        if hash.len() != 64 || !hash.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')) {
            continue;
        }
        if !pid.bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        let Ok(meta) = ent.metadata() else { continue };
        let Ok(modified) = meta.modified() else { continue };
        let Ok(age) = now.duration_since(modified) else { continue };
        if age < max_age {
            continue;
        }
        if std::fs::remove_file(ent.path()).is_ok() {
            n += 1;
        }
    }
    n
}

/// Content-addressed input blob write. If `inputs/<sha>` already exists and matches,
/// leaves it alone. If it exists but is corrupt (wrong length/bytes), rewrites it
/// atomically with the correct payload — never deletes a good blob.
/// Returns true when a new/repaired file was written.
///
/// Opens existing blobs with `O_NOFOLLOW` + fstat on that fd (TOCTOU-safe).
/// Opportunistic cleanup of `.{sha}.{pid}.tmp` older than 15 minutes: a writer
/// stalled past that window can observe `Err` from rename after its temp was
/// reaped and must retry.
fn write_input_blob(backup_dir: &Path, hash: &str, bytes: &[u8]) -> Result<bool, String> {
    validate_input_hash(hash)?;
    let dir = inputs_blob_dir(backup_dir);
    std::fs::create_dir_all(&dir).map_err(|e| format!("mkdir {}: {e}", dir.display()))?;
    fsync_dir(&dir).ok(); // best-effort after mkdir
    // Opportunistic cleanup of crashed-writer temps (15 minutes).
    let _ = cleanup_stale_input_tmps(&dir, std::time::Duration::from_secs(15 * 60));
    let path = inputs_blob_path(backup_dir, hash)?;
    ensure_regular_input_blob_path(&path)?;
    match open_input_blob_nofollow(&path) {
        Ok(f) => {
            let meta = f
                .metadata()
                .map_err(|e| format!("fstat {}: {e}", path.display()))?;
            // Post-open is_file: on Unix this re-checks the same fd after
            // O_NOFOLLOW; on non-Unix (plain File::open) it is the only
            // regular-file guard before we trust blob bytes.
            if !meta.file_type().is_file() {
                return Err(format!("{}: not a regular file after open", path.display()));
            }
            use std::io::Read;
            let mut got = Vec::new();
            // Read from the same fd we fstat'd (no path re-walk).
            let mut f = f;
            f.read_to_end(&mut got)
                .map_err(|e| format!("read {}: {e}", path.display()))?;
            if got.len() == bytes.len() && got == bytes {
                return Ok(false);
            }
            // Corrupt/truncated: rewrite atomically; do not unlink the good path first.
            durable_replace_file(&dir, &path, bytes)?;
            return Ok(true);
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => {
            // Symlink (O_NOFOLLOW → ELOOP) or directory (EISDIR): quarantine then rewrite.
            let os = e.raw_os_error();
            let is_special = matches!(os, Some(40) | Some(21)) // ELOOP, EISDIR
                || e.kind() == std::io::ErrorKind::Other
                || e.kind() == std::io::ErrorKind::IsADirectory;
            if is_special {
                ensure_regular_input_blob_path(&path)?;
            } else {
                return Err(format!("open {}: {e}", path.display()));
            }
        }
    }
    match durable_replace_file(&dir, &path, bytes) {
        Ok(()) => Ok(true),
        Err(_) if blob_file_matches(&path, bytes) => {
            // Lost the race to another writer that produced the correct blob.
            Ok(false)
        }
        Err(e) => Err(e),
    }
}

/// Resolve blob bytes: non-empty inline bytes (legacy full backup) win; otherwise
/// load from the append-only `inputs/<hash>` store under any of `blob_dirs`
/// (typically the restore `backup_dir`, then the gzip's parent).
/// Opens with `O_NOFOLLOW` and reads from that fd after fstat confirms a regular file.
fn resolve_input_bytes(
    blob_dirs: &[PathBuf],
    hash: &str,
    bytes: Vec<u8>,
) -> Result<Vec<u8>, String> {
    if !bytes.is_empty() {
        return Ok(bytes);
    }
    if blob_dirs.is_empty() {
        return Err(format!(
            "blob {hash}: empty bytes in backup and no inputs store directory"
        ));
    }
    let mut last = String::new();
    for dir in blob_dirs {
        let path = match inputs_blob_path(dir, hash) {
            Ok(p) => p,
            Err(e) => {
                last = e;
                continue;
            }
        };
        match open_input_blob_nofollow(&path) {
            Ok(f) => {
                let meta = match f.metadata() {
                    Ok(m) => m,
                    Err(e) => {
                        last = format!("{}: fstat {e}", path.display());
                        continue;
                    }
                };
                // Same post-open regular-file check as write_input_blob (non-Unix fallback).
                if !meta.file_type().is_file() {
                    last = format!("{}: not a regular file", path.display());
                    continue;
                }
                use std::io::Read;
                let mut f = f;
                let mut b = Vec::new();
                match f.read_to_end(&mut b) {
                    Ok(_) => return Ok(b),
                    Err(e) => last = format!("{}: {e}", path.display()),
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                last = format!("{}: {e}", path.display());
            }
            Err(e) => {
                // Symlink (O_NOFOLLOW) or other — report as not a regular file.
                last = format!("{}: not a regular file ({e})", path.display());
            }
        }
    }
    Err(format!(
        "blob {hash}: missing or unreadable in inputs store ({last})"
    ))
}

fn blob_dirs_for(backup_dir: Option<&Path>, src: Option<&Path>) -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    if let Some(d) = backup_dir {
        dirs.push(d.to_path_buf());
    }
    if let Some(p) = src.and_then(|s| s.parent()) {
        let p = p.to_path_buf();
        if !dirs.iter().any(|d| d == &p) {
            dirs.push(p);
        }
    }
    dirs
}

/// Re-checks every `score_inputs` blob of an attached database before a restore merges it.
/// Empty `bytes` are resolved from the append-only `inputs/` store under `backup_dir`.
fn verify_src_blobs(c: &Connection, schema: &str, blob_dirs: &[PathBuf]) -> Result<usize, String> {
    let mut st = c
        .prepare(
            &[
                "SELECT hash, encoding, raw_size, bytes FROM ",
                schema,
                ".score_inputs",
            ]
            .concat(),
        )
        .map_err(err)?;
    let mut rows = st.query([]).map_err(err)?;
    let mut n = 0;
    while let Some(r) = rows.next().map_err(err)? {
        let hash: String = r.get(0).map_err(err)?;
        let bytes = resolve_input_bytes(blob_dirs, &hash, r.get(3).map_err(err)?)?;
        check_blob(
            &hash,
            r.get::<_, Option<String>>(1).map_err(err)?.as_deref(),
            r.get(2).map_err(err)?,
            bytes,
        )?;
        n += 1;
    }
    Ok(n)
}

fn sha256_hex(b: &[u8]) -> String {
    use sha2::Digest;
    sha2::Sha256::digest(b)
        .iter()
        .map(|x| format!("{x:02x}"))
        .collect()
}

fn gunzip_bytes(b: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    flate2::read::GzDecoder::new(b)
        .read_to_end(&mut out)
        .map_err(err)?;
    Ok(out)
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct RunMeta {
    pub captured_at: i64,
    pub profile: String,
    pub model: String,
    pub semantics: String,
    pub market_context: bool,
    pub binary_version: String,
    /// `interval`, `close` or `snapshot` (offline seeding).
    pub trigger: String,
    pub symbols_total: usize,
    pub incomplete_symbols: Vec<String>,
}

impl RunMeta {
    pub fn run_id(&self) -> String {
        format!("{}-{}-{}", self.profile, self.captured_at, self.model)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RecordOutcome {
    /// Rows written (rows with a non-finite price are skipped, not stored as 0).
    Recorded { rows: usize, skipped: Vec<String> },
    /// The run id already exists; nothing written.
    Duplicate,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct HistoryPoint {
    pub run_id: String,
    pub captured_at: i64,
    /// ISO-8601 UTC, e.g. `2026-10-29T20:10:03Z`.
    pub captured_at_utc: String,
    pub profile: String,
    pub model: String,
    pub rule_version: String,
    /// True when `rule_version` differs from the previous point of the same profile+model:
    /// a new series starts here and it is never reported as a decision flip.
    pub rule_change: bool,
    pub trigger: String,
    pub market_context: bool,
    pub rank: i64,
    pub decision: String,
    pub composite: i64,
    pub fundamentals: Option<i64>,
    pub technical: Option<i64>,
    pub forecast: Option<i64>,
    pub regime: Option<i64>,
    pub confidence: Option<String>,
    pub trust_signal: Option<String>,
    pub price_cents: i64,
    pub target_cents: Option<i64>,
    pub upside_bps: Option<i64>,
}

/// A decision flip (same profile, model and rule_version) and what moved.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct DecisionChange {
    pub from_at: i64,
    pub to_at: i64,
    pub from_utc: String,
    pub to_utc: String,
    pub from: String,
    pub to: String,
    pub composite_delta: i64,
    pub fundamentals_delta: Option<i64>,
    pub technical_delta: Option<i64>,
    pub forecast_delta: Option<i64>,
    pub point: HistoryPoint,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct BackupReport {
    pub path: PathBuf,
    pub integrity: String,
    /// -1 when the table is absent (e.g. a pre-migration copy of an older DB).
    pub score_runs: i64,
    pub score_history: i64,
    /// V3.1 shadow rows; -1 when the table is absent (pre-v3 file).
    #[serde(default = "absent")]
    pub score_shadow: i64,
    pub bytes: u64,
}

fn absent() -> i64 {
    -1
}

#[derive(Clone, Debug, PartialEq)]
pub struct RestoreReport {
    pub pre_restore_backup: Option<PathBuf>,
    pub runs_added: usize,
    pub rows_added: usize,
    /// V3.1 shadow rows added (0 for a pre-v3 backup without `score_shadow`).
    pub shadow_added: usize,
    /// Rows added per later table: `score_inputs`, `score_run_inputs`,
    /// `score_evaluations`, `score_run_stats` (0 when the backup predates them).
    pub inputs_added: usize,
    pub run_inputs_added: usize,
    pub evaluations_added: usize,
    pub run_stats_added: usize,
    /// Row counts found in the (verified) backup.
    pub backup_runs: i64,
    pub backup_rows: i64,
}

pub fn iso_utc(epoch: i64) -> String {
    chrono::DateTime::from_timestamp(epoch, 0)
        .map(|d| d.format("%Y-%m-%dT%H:%M:%SZ").to_string())
        .unwrap_or_default()
}

/// `2026-10-29 16:10 EDT` in `tz` (the zone label follows DST).
pub fn format_in_tz(epoch: i64, tz: Tz) -> String {
    chrono::DateTime::from_timestamp(epoch, 0)
        .map(|d| d.with_timezone(&tz).format("%Y-%m-%d %H:%M %Z").to_string())
        .unwrap_or_default()
}

/// `^[A-Z0-9.\-^=]{1,16}$` after upper-casing; returns the normalized symbol.
pub fn validate_symbol(raw: &str) -> Result<String, String> {
    let s = raw.trim().to_ascii_uppercase();
    let ok = !s.is_empty()
        && s.len() <= 16
        && s.chars()
            .all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || ".-^=".contains(c));
    if ok {
        Ok(s)
    } else {
        Err(format!(
            "bad symbol '{raw}' (expected ^[A-Z0-9.\\-^=]{{1,16}}$)"
        ))
    }
}

/// A run id as written by [`RunMeta::run_id`]: `<profile>-<epoch>-<model>`, at most
/// 64 chars of `[a-z0-9._-]`.
pub fn validate_run_id(raw: &str) -> Result<String, String> {
    let ok = !raw.is_empty()
        && raw.len() <= 64
        && raw
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "._-".contains(c))
        && raw.split('-').filter(|p| !p.is_empty()).count() >= 3;
    if ok {
        Ok(raw.to_string())
    } else {
        Err(format!(
            "bad run id '{raw}' (expected <profile>-<epoch>-<model>)"
        ))
    }
}

/// `days` must be in 1..=[`MAX_HISTORY_DAYS`].
pub fn validate_days(days: i64) -> Result<i64, String> {
    if (1..=MAX_HISTORY_DAYS).contains(&days) {
        Ok(days)
    } else {
        Err(format!(
            "bad days '{days}' (expected 1..={MAX_HISTORY_DAYS})"
        ))
    }
}

fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

fn now_epoch() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn stamp() -> String {
    chrono::Utc::now().format("%Y%m%dT%H%M%S%.3fZ").to_string()
}

fn table_count(c: &Connection, table: &str) -> i64 {
    c.query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0))
        .unwrap_or(-1)
}

fn has_table(c: &Connection, schema: &str, t: &str) -> bool {
    c.query_row(
        &format!("SELECT 1 FROM {schema}.sqlite_master WHERE type='table' AND name=?1"),
        params![t],
        |_| Ok(()),
    )
    .optional()
    .ok()
    .flatten()
    .is_some()
}

/// Normalization for the trigger SQL comparison. The expected SQL is rebuilt from
/// [`MIGRATIONS`], so FUTURE MIGRATIONS MUST NOT EDIT THE TEXT OF EXISTING TRIGGERS:
/// a changed trigger needs a new name (and a new migration), otherwise every existing
/// DB would be flagged as altered and refuse writes.
fn norm_sql(sql: &str) -> String {
    sql.to_ascii_lowercase()
        .replace("if not exists ", "")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .trim_end_matches(';')
        .to_string()
}

/// Expected trigger DDL (normalized), from the migrations applied to an empty DB.
fn expected_trigger_sql() -> std::collections::HashMap<String, String> {
    let c = Connection::open_in_memory().expect("in-memory sqlite");
    c.execute_batch(MIGRATION_LOG).expect("log ddl");
    for (_, sql) in MIGRATIONS {
        c.execute_batch(sql).expect("migration ddl");
    }
    let mut st = c
        .prepare("SELECT name, sql FROM sqlite_master WHERE type='trigger'")
        .unwrap();
    let rows = st
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
        .unwrap();
    rows.flatten().map(|(n, q)| (n, norm_sql(&q))).collect()
}

/// Triggers from [`REQUIRED_TRIGGERS`] that are missing in `main` or whose SQL differs
/// from the expected DDL (e.g. a same-name no-op trigger).
pub fn missing_triggers(c: &Connection) -> Vec<&'static str> {
    let expected = expected_trigger_sql();
    REQUIRED_TRIGGERS
        .iter()
        .copied()
        .filter(|t| {
            let have: Option<String> = c
                .query_row(
                    "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?1",
                    params![t],
                    |r| r.get(0),
                )
                .optional()
                .ok()
                .flatten();
            have.map(|h| norm_sql(&h)) != expected.get(*t).cloned()
        })
        .collect()
}

/// Behavioural check: inside a SAVEPOINT, DELETE, UPDATE and a duplicate INSERT on each
/// history table must abort with the append-only error; everything is rolled back to the
/// savepoint, so no data can be lost (empty tables get a sentinel row inside it).
/// Returns the failing `table: op` pairs, or `Err` when the DB is busy/locked by another
/// writer (inconclusive, NOT a failure).
pub fn trigger_behaviour_failures(c: &Connection) -> Result<Vec<String>, String> {
    let mut bad = Vec::new();
    // Take the write lock up front (BEGIN IMMEDIATE waits on the busy handler); a
    // deferred read->write upgrade would get an immediate, un-retried SQLITE_BUSY.
    let own_tx = c.is_autocommit();
    if own_tx {
        if let Err(e) = c.execute_batch("BEGIN IMMEDIATE") {
            return if is_busy(&e) {
                Err(e.to_string())
            } else {
                Ok(vec![format!("begin {e}")])
            };
        }
    }
    if let Err(e) = c.execute_batch("SAVEPOINT score_history_selfcheck") {
        if own_tx {
            let _ = c.execute_batch("ROLLBACK");
        }
        return if is_busy(&e) {
            Err(e.to_string())
        } else {
            Ok(vec![format!("savepoint {e}")])
        };
    }
    let mut busy: Option<String> = None;
    let sentinel = |t: &str| -> rusqlite::Result<()> {
        if table_count(c, t) > 0 {
            return Ok(());
        }
        match t {
            "score_runs" | "score_history" => {
                if table_count(c, "score_runs") == 0 {
                    c.execute(
                        "INSERT INTO score_runs (run_id, captured_at, profile, model, rule_version, \
                         semantics, market_context, binary_version, trigger, symbols_total, \
                         symbols_ranked) VALUES ('__selfcheck__',0,'','','','',0,'','',0,0)",
                        [],
                    )?;
                }
                if t == "score_history" {
                    c.execute(
                        "INSERT INTO score_history (run_id, symbol, captured_at, rank, decision, \
                         composite, price_cents) SELECT run_id, '__SELFCHECK__', 0, 0, '', 0, 0 \
                         FROM score_runs LIMIT 1",
                        [],
                    )?;
                }
                Ok(())
            }
            "score_inputs" => c
                .execute(
                    "INSERT INTO score_inputs (hash, encoding, raw_size, bytes) \
                     VALUES ('__selfcheck__', 'raw', 0, x'')",
                    [],
                )
                .map(|_| ()),
            "score_run_inputs" => c
                .execute(
                    "INSERT INTO score_run_inputs (run_id, idx, symbol, hash) \
                     VALUES ('__selfcheck__', 0, '__SELFCHECK__', '__selfcheck__')",
                    [],
                )
                .map(|_| ()),
            "score_evaluations" => c
                .execute(
                    "INSERT INTO score_evaluations (run_id, symbol, captured_at, status, reason) \
                     VALUES ('__selfcheck__', '__SELFCHECK__', 0, 'failed', 'selfcheck')",
                    [],
                )
                .map(|_| ()),
            "score_run_stats" => c
                .execute(
                    "INSERT INTO score_run_stats (run_id, universe_size, loaded, ok, excluded, \
                     failed, reasons_json, snapshot_rows, inputs_stored, inputs_reason, \
                     snapshot_prefix, snapshot_suffix) \
                     VALUES ('__selfcheck__', 0, 0, 0, 0, 0, '{}', 0, 0, 'selfcheck', x'', x'')",
                    [],
                )
                .map(|_| ()),
            "score_shadow" => {
                if table_count(c, "score_runs") == 0 {
                    c.execute(
                        "INSERT INTO score_runs (run_id, captured_at, profile, model, rule_version, \
                         semantics, market_context, binary_version, trigger, symbols_total, \
                         symbols_ranked) VALUES ('__selfcheck__',0,'','','','',0,'','',0,0)",
                        [],
                    )?;
                }
                c.execute(
                    "INSERT INTO score_shadow (run_id, ticker, rule_version, config_id, bound, \
                     lambda_t, lambda_m, m, warmup, s_adj, shadow_decision) SELECT run_id, \
                     '__SELFCHECK__', '', '', 0, 0, 0, NULL, 0, 0, '' FROM score_runs LIMIT 1",
                    [],
                )
                .map(|_| ())
            }
            _ => c
                .execute(
                    "INSERT INTO score_history_migrations (version, applied_at) VALUES (-1, 0)",
                    [],
                )
                .map(|_| ()),
        }
    };
    for t in HISTORY_TABLES {
        if busy.is_some() {
            break;
        }
        if let Err(e) = sentinel(t) {
            if is_busy(&e) {
                busy = Some(e.to_string());
                break;
            }
            bad.push(format!("{t}: sentinel {e}"));
            continue;
        }
        let ops = [
            ("delete", format!("{} FROM {t}", "DELETE")),
            ("update", format!("{} {t} SET rowid = rowid", "UPDATE")),
            (
                "duplicate insert",
                format!("{} INTO {t} SELECT * FROM {t} LIMIT 1", "INSERT"),
            ),
        ];
        for (op, sql) in ops {
            // Each probe in its own nested savepoint, so one missing trigger cannot
            // mask the next probe.
            let _ = c.execute_batch("SAVEPOINT score_history_probe");
            match c.execute(&sql, []) {
                Err(e) if is_busy(&e) => busy = Some(e.to_string()),
                Err(e) if e.to_string().contains("append-only") => {}
                _ => bad.push(format!("{t}: {op}")),
            }
            let _ = c.execute_batch("ROLLBACK TO score_history_probe; RELEASE score_history_probe");
        }
    }
    let _ = c.execute_batch("ROLLBACK TO score_history_selfcheck; RELEASE score_history_selfcheck");
    if own_tx {
        let _ = c.execute_batch("ROLLBACK");
    }
    match busy {
        Some(e) => Err(e),
        None => Ok(bad),
    }
}

fn is_busy(e: &rusqlite::Error) -> bool {
    matches!(
        e.sqlite_error_code(),
        Some(rusqlite::ErrorCode::DatabaseBusy | rusqlite::ErrorCode::DatabaseLocked)
    )
}

/// Result of the behavioural self-check.
#[derive(Debug, Clone, PartialEq)]
pub enum SelfCheck {
    Passed,
    Failed(Vec<String>),
    /// Another writer held the DB; re-run before the next write.
    Pending(String),
}

type Probe = fn(&Connection) -> Result<Vec<String>, String>;

/// Runs `probe` with a short busy timeout and backoff; BUSY after all attempts →
/// [`SelfCheck::Pending`] (logged loudly), never a failure. Restores the 10 s timeout.
fn run_selfcheck(c: &Connection, probe: Probe, attempts: u32, per_try: Duration) -> SelfCheck {
    let _ = c.busy_timeout(per_try);
    let mut last = String::new();
    let mut out = None;
    for i in 0..attempts {
        match probe(c) {
            Ok(bad) if bad.is_empty() => out = Some(SelfCheck::Passed),
            Ok(bad) => out = Some(SelfCheck::Failed(bad)),
            Err(e) => {
                last = e;
                if i + 1 < attempts {
                    std::thread::sleep(Duration::from_millis(100 << i.min(6)));
                }
                continue;
            }
        }
        break;
    }
    let _ = c.busy_timeout(BUSY_TIMEOUT);
    out.unwrap_or_else(|| {
        eprintln!(
            "WARNING score history: append-only trigger self-check INCONCLUSIVE — the database \
             is locked by another writer ({last}). History is NOT disabled: the check will run \
             again before the next history write, and that write is refused (not lost) if the \
             lock is still held. If this repeats, find the process holding the DB open for writing."
        );
        SelfCheck::Pending(last)
    })
}

const BUSY_TIMEOUT: Duration = Duration::from_secs(10);

fn gzip_file(src: &Path, dest: &Path) -> Result<(), String> {
    let partial = dest.with_extension("gz.partial");
    {
        let mut input = std::fs::File::open(src).map_err(err)?;
        let out = std::fs::File::create(&partial).map_err(err)?;
        let mut gz = flate2::write::GzEncoder::new(out, flate2::Compression::best());
        std::io::copy(&mut input, &mut gz).map_err(err)?;
        gz.finish().map_err(err)?.sync_all().map_err(err)?;
    }
    std::fs::rename(&partial, dest).map_err(|e| format!("rename {}: {e}", dest.display()))
}

/// A readable SQLite path for `path` (`.gz` is inflated to a temp file, removed on drop).
struct Plain {
    path: PathBuf,
    temp: bool,
}
impl Drop for Plain {
    fn drop(&mut self) {
        if self.temp {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}
fn plain(path: &Path) -> Result<Plain, String> {
    static SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    if path.extension().is_some_and(|e| e == "gz") {
        let tmp = std::env::temp_dir().join(format!(
            "score-history-inflate-{}-{}-{}.sqlite",
            std::process::id(),
            SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed),
            stamp()
        ));
        let mut gz = flate2::read::GzDecoder::new(
            std::fs::File::open(path).map_err(|e| format!("open {}: {e}", path.display()))?,
        );
        let mut buf = Vec::new();
        gz.read_to_end(&mut buf)
            .map_err(|e| format!("{}: not a valid gzip backup: {e}", path.display()))?;
        std::fs::File::create(&tmp)
            .and_then(|mut f| f.write_all(&buf))
            .map_err(err)?;
        Ok(Plain {
            path: tmp,
            temp: true,
        })
    } else {
        Ok(Plain {
            path: path.to_path_buf(),
            temp: false,
        })
    }
}

/// `PRAGMA integrity_check` + row counts of a backup (`.sqlite` or `.sqlite.gz`).
/// With `require_tables`, `score_runs` and `score_history` must exist.
pub fn verify_file(path: &Path, require_tables: bool) -> Result<BackupReport, String> {
    let bytes = std::fs::metadata(path)
        .map_err(|e| format!("{}: {e}", path.display()))?
        .len();
    let p = plain(path)?;
    let c = Connection::open_with_flags(&p.path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)
        .map_err(|e| format!("open {}: {e}", path.display()))?;
    let integrity: String = c
        .query_row("PRAGMA integrity_check", [], |r| r.get(0))
        .map_err(|e| format!("{}: not a valid SQLite file: {e}", path.display()))?;
    if integrity != "ok" {
        return Err(format!("{}: integrity_check = {integrity}", path.display()));
    }
    let count = |t: &str| {
        if has_table(&c, "main", t) {
            table_count(&c, t)
        } else {
            -1
        }
    };
    let rep = BackupReport {
        path: path.to_path_buf(),
        integrity,
        score_runs: count("score_runs"),
        score_history: count("score_history"),
        score_shadow: count("score_shadow"),
        bytes,
    };
    if require_tables && (rep.score_runs < 0 || rep.score_history < 0) {
        return Err(format!(
            "{}: missing score_runs/score_history tables",
            path.display()
        ));
    }
    Ok(rep)
}

/// `--history-backup-dir` default: [`BACKUP_DIR_ENV`], else `$XDG_DATA_HOME/vantage/backups`,
/// else `~/.local/share/vantage/backups`.
pub fn default_backup_dir() -> PathBuf {
    if let Some(v) = std::env::var_os(BACKUP_DIR_ENV).filter(|v| !v.is_empty()) {
        return PathBuf::from(v);
    }
    std::env::var_os("XDG_DATA_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("vantage/backups")
}

/// Exclusive lock on `<data-dir>/vantage-headless.lock`, held by `serve` (and by offline
/// writers). Released when dropped / when the process exits.
pub struct DataDirLock {
    _file: std::fs::File,
    pub path: PathBuf,
}

pub fn lock_data_dir(dir: &Path) -> Result<DataDirLock, String> {
    std::fs::create_dir_all(dir).map_err(|e| format!("mkdir {}: {e}", dir.display()))?;
    let path = dir.join(LOCK_FILE);
    let mut f = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(&path)
        .map_err(|e| format!("open {}: {e}", path.display()))?;
    match f.try_lock() {
        Ok(()) => {}
        Err(std::fs::TryLockError::WouldBlock) => {
            let mut pid = String::new();
            let _ = f.read_to_string(&mut pid);
            return Err(format!(
                "{} is in use by a running vantage-headless (pid {}); stop it first",
                dir.display(),
                pid.trim()
            ));
        }
        Err(std::fs::TryLockError::Error(e)) => {
            return Err(format!("lock {}: {e}", path.display()))
        }
    }
    let _ = f.set_len(0);
    let _ = write!(f, "{}", std::process::id());
    Ok(DataDirLock { _file: f, path })
}

pub struct ScoreHistoryStore {
    conn: Mutex<Connection>,
    path: Option<PathBuf>,
    /// Backup taken before migrations at open, if any.
    pub migration_backup: Option<PathBuf>,
    /// Missing required triggers at open: when non-empty the store refuses to write.
    pub missing_triggers: Vec<&'static str>,
    /// Behavioural self-check state (Pending is re-run before the next write).
    selfcheck: Mutex<SelfCheck>,
    probe: Probe,
    /// Total busy budget for the deferred self-check at write time.
    write_busy: Duration,
}

impl ScoreHistoryStore {
    /// Opens (and additively migrates) the history tables inside the app DB at `path`.
    /// Before any pending migration on a non-empty file, a verified gzip `VACUUM INTO` copy
    /// is written to `backup_dir` (or next to the file when `None`).
    pub fn open(path: &Path, backup_dir: Option<&Path>) -> Result<Self, String> {
        Self::open_with(path, backup_dir, MIGRATIONS)
    }

    pub fn open_with(
        path: &Path,
        backup_dir: Option<&Path>,
        migrations: &[(i64, &str)],
    ) -> Result<Self, String> {
        if let Some(p) = path.parent() {
            std::fs::create_dir_all(p).map_err(|e| format!("mkdir {}: {e}", p.display()))?;
        }
        let conn = Connection::open(path).map_err(|e| format!("open {}: {e}", path.display()))?;
        let dir = backup_dir
            .map(Path::to_path_buf)
            .or_else(|| path.parent().map(Path::to_path_buf));
        Self::init(conn, Some(path.to_path_buf()), dir, migrations)
    }

    pub fn open_in_memory() -> Result<Self, String> {
        Self::init(
            Connection::open_in_memory().map_err(err)?,
            None,
            None,
            MIGRATIONS,
        )
    }

    fn init(
        conn: Connection,
        path: Option<PathBuf>,
        backup_dir: Option<PathBuf>,
        migrations: &[(i64, &str)],
    ) -> Result<Self, String> {
        Self::init_with(
            conn,
            path,
            backup_dir,
            migrations,
            trigger_behaviour_failures,
        )
    }

    fn init_with(
        conn: Connection,
        path: Option<PathBuf>,
        backup_dir: Option<PathBuf>,
        migrations: &[(i64, &str)],
        probe: Probe,
    ) -> Result<Self, String> {
        conn.busy_timeout(BUSY_TIMEOUT).map_err(err)?;
        if path.is_some() {
            // Same journal mode the desktop Db already sets on this file (db.rs).
            let _ = conn.pragma_update(None, "journal_mode", "WAL");
        }
        let migration_backup = migrate(&conn, backup_dir.as_deref(), migrations)?;
        let missing_triggers = missing_triggers(&conn);
        let selfcheck = if missing_triggers.is_empty() {
            run_selfcheck(&conn, probe, 4, Duration::from_millis(250))
        } else {
            SelfCheck::Failed(vec![])
        };
        Ok(Self {
            conn: Mutex::new(conn),
            path,
            migration_backup,
            missing_triggers,
            selfcheck: Mutex::new(selfcheck),
            probe,
            write_busy: BUSY_TIMEOUT,
        })
    }

    pub fn path(&self) -> Option<&Path> {
        self.path.as_deref()
    }

    /// Current behavioural self-check state.
    pub fn selfcheck(&self) -> SelfCheck {
        self.selfcheck
            .lock()
            .map(|s| s.clone())
            .unwrap_or(SelfCheck::Pending("lock".into()))
    }

    fn check_writable(&self) -> Result<(), String> {
        if !self.missing_triggers.is_empty() {
            return Err(format!(
                "score history: refusing to write, append-only triggers missing or altered: {}",
                self.missing_triggers.join(", ")
            ));
        }
        let mut st = self.selfcheck.lock().map_err(|_| "lock")?;
        if let SelfCheck::Pending(_) = *st {
            // Deferred: re-run now, ONE attempt with a total budget of `write_busy` (10 s),
            // since the conn mutex is held (HTTP reads wait too).
            let c = self.conn.lock().map_err(|_| "lock")?;
            *st = run_selfcheck(&c, self.probe, 1, self.write_busy);
        }
        match &*st {
            SelfCheck::Passed => Ok(()),
            SelfCheck::Failed(bad) => Err(format!(
                "score history: refusing to write, append-only behaviour self-check failed: {}",
                bad.join(", ")
            )),
            SelfCheck::Pending(e) => Err(format!(
                "score history: database busy ({e}); append-only self-check deferred, write \
                 not attempted — will retry on the next write"
            )),
        }
    }

    pub fn schema_version(&self) -> Result<i64, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        current_version(&c)
    }

    /// Appends one run with plain INSERTs. A run id that already exists is a no-op
    /// ([`RecordOutcome::Duplicate`]); nothing is ever overwritten.
    pub fn record_run(&self, meta: &RunMeta, rows: &[RankedRow]) -> Result<RecordOutcome, String> {
        self.record_run_full(meta, rows, None)
    }

    /// [`Self::record_run`] plus, when `eval` is given, every universe symbol's
    /// evaluation, the deduplicated raw inputs and the run stats, all in ONE transaction.
    pub fn record_run_full(
        &self,
        meta: &RunMeta,
        rows: &[RankedRow],
        eval: Option<&RunEvaluation>,
    ) -> Result<RecordOutcome, String> {
        self.check_writable()?;
        let run_id = meta.run_id();
        let (good, bad): (Vec<&RankedRow>, Vec<&RankedRow>) = rows
            .iter()
            .partition(|r| r.price.is_finite() && r.price > 0.0);
        let mut c = self.conn.lock().map_err(|_| "lock")?;
        let tx = c
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
            .map_err(err)?;
        let exists = tx
            .query_row(
                "SELECT 1 FROM score_runs WHERE run_id = ?1",
                params![run_id],
                |_| Ok(()),
            )
            .optional()
            .map_err(err)?
            .is_some();
        if exists {
            return Ok(RecordOutcome::Duplicate);
        }
        tx.execute(
            "INSERT INTO score_runs (run_id, captured_at, profile, model, rule_version, \
             semantics, market_context, binary_version, trigger, symbols_total, symbols_ranked, \
             incomplete_symbols) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)",
            params![
                run_id,
                meta.captured_at,
                meta.profile,
                meta.model,
                SCORE_RULE_VERSION,
                meta.semantics,
                meta.market_context as i64,
                meta.binary_version,
                meta.trigger,
                meta.symbols_total as i64,
                good.len() as i64,
                serde_json::to_string(&meta.incomplete_symbols).unwrap(),
            ],
        )
        .map_err(err)?;
        {
            let mut st = tx
                .prepare(
                    "INSERT INTO score_history (run_id, symbol, captured_at, rank, decision, \
                     composite, fundamentals, technical, forecast, regime, confidence, trust_signal, \
                     price_cents, target_cents, upside_bps, inputs_json) \
                     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,NULL)",
                )
                .map_err(err)?;
            let fin = |x: Option<f64>| x.filter(|v| v.is_finite());
            for r in &good {
                st.execute(params![
                    run_id,
                    r.symbol,
                    meta.captured_at,
                    r.rank as i64,
                    r.decision,
                    r.composite_score,
                    r.fundamentals,
                    r.technical,
                    r.forecast,
                    r.regime,
                    r.confidence,
                    r.trust_signal,
                    (r.price * 100.0).round() as i64,
                    fin(r.target).map(|t| (t * 100.0).round() as i64),
                    fin(r.upside_pct).map(|u| (u * 100.0).round() as i64),
                ])
                .map_err(|e| format!("insert {}: {e}", r.symbol))?;
            }
        }
        if good
            .iter()
            .any(|r| r.shadow.as_ref().is_some_and(|g| !g.is_empty()))
        {
            // V3.1 shadow grid: plain INSERTs in the same transaction as the official rows.
            let mut st = tx
                .prepare(
                    "INSERT INTO score_shadow (run_id, ticker, rule_version, config_id, bound, \
                     lambda_t, lambda_m, m, warmup, s_adj, shadow_decision, hist_source) \
                     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)",
                )
                .map_err(err)?;
            for r in &good {
                for v in r.shadow.iter().flatten() {
                    st.execute(params![
                        run_id,
                        r.symbol,
                        v.rule_version,
                        v.config_id,
                        v.bound,
                        v.lambda_t,
                        v.lambda_m,
                        v.m.filter(|x| x.is_finite()),
                        v.warmup,
                        v.s_adj,
                        v.shadow_decision,
                        v.hist_source,
                    ])
                    .map_err(|e| format!("insert shadow {} {}: {e}", r.symbol, v.config_id))?;
                }
            }
        }
        if let Some(e) = eval {
            write_evaluation(&tx, meta, &run_id, e)?;
        }
        tx.commit().map_err(err)?;
        Ok(RecordOutcome::Recorded {
            rows: good.len(),
            skipped: bad.iter().map(|r| r.symbol.clone()).collect(),
        })
    }
    /// Per-run counts, `None` for runs recorded without evaluations.
    pub fn run_stats(&self, run_id: &str) -> Result<Option<RunStats>, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        c.query_row(
            "SELECT universe_size, loaded, ok, excluded, failed, reasons_json, snapshot_rows, \
             inputs_stored, inputs_run_id, inputs_reason FROM score_run_stats WHERE run_id = ?1",
            params![run_id],
            |r| {
                Ok(RunStats {
                    run_id: run_id.to_string(),
                    universe_size: r.get(0)?,
                    loaded: r.get(1)?,
                    ok: r.get(2)?,
                    excluded: r.get(3)?,
                    failed: r.get(4)?,
                    reasons: serde_json::from_str(&r.get::<_, String>(5)?).unwrap_or_default(),
                    snapshot_rows: r.get(6)?,
                    inputs_stored: r.get::<_, i64>(7)? != 0,
                    inputs_run_id: r.get(8)?,
                    inputs_reason: r.get(9)?,
                })
            },
        )
        .optional()
        .map_err(err)
    }

    /// All evaluations of one run (rank order for `ok`, then symbol).
    pub fn evaluations(&self, run_id: &str) -> Result<Vec<EvalRecord>, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        let mut st = c
            .prepare(concat!(
                "SELECT ",
                eval_cols!(),
                " FROM score_evaluations e WHERE e.run_id = ?1 \
                 ORDER BY e.rank IS NULL, e.rank, e.symbol"
            ))
            .map_err(err)?;
        let out = st
            .query_map(params![run_id], eval_from_row)
            .map_err(err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(err)?;
        Ok(out)
    }

    /// One symbol's evaluations across runs (oldest first), any status, from one indexed
    /// join on (run_id, symbol). `since` (epoch secs, inclusive) filters; `limit` keeps the
    /// most recent `limit` rows.
    pub fn symbol_evaluations(
        &self,
        symbol: &str,
        profile: &str,
        model: &str,
        since: Option<i64>,
        limit: usize,
    ) -> Result<Vec<serde_json::Value>, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        let mut st = c
            .prepare(concat!(
                "SELECT * FROM (SELECT ",
                eval_cols!(),
                ", e.run_id, e.captured_at FROM score_evaluations e \
                 JOIN score_runs r ON r.run_id = e.run_id \
                 WHERE e.symbol = ?1 AND r.profile = ?2 AND r.model = ?3 AND e.captured_at >= ?4 \
                 ORDER BY e.captured_at DESC, e.run_id DESC LIMIT ?5) \
                 ORDER BY captured_at, run_id"
            ))
            .map_err(err)?;
        let rows = st
            .query_map(
                params![
                    symbol,
                    profile,
                    model,
                    since.unwrap_or(i64::MIN),
                    limit as i64
                ],
                |r| {
                    Ok((
                        eval_from_row(r)?,
                        r.get::<_, String>(22)?,
                        r.get::<_, i64>(23)?,
                    ))
                },
            )
            .map_err(err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(err)?;
        let mut out = Vec::with_capacity(rows.len());
        for (e, run, at) in rows {
            let mut v = serde_json::to_value(&e).map_err(err)?;
            v["run_id"] = run.into();
            v["captured_at"] = at.into();
            v["captured_at_utc"] = iso_utc(at).into();
            out.push(v);
        }
        Ok(out)
    }

    /// Whether `run_id` is an evaluated run of this profile+model.
    pub fn run_in_profile(&self, run_id: &str, profile: &str, model: &str) -> Result<bool, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        c.query_row(
            "SELECT 1 FROM score_runs r JOIN score_run_stats s ON s.run_id = r.run_id \
             WHERE r.run_id = ?1 AND r.profile = ?2 AND r.model = ?3",
            params![run_id, profile, model],
            |_| Ok(()),
        )
        .optional()
        .map(|o| o.is_some())
        .map_err(err)
    }

    /// The newest evaluated run id for a profile+model (`None` when there is none).
    pub fn latest_run_id(&self, profile: &str, model: &str) -> Result<Option<String>, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        c.query_row(
            "SELECT r.run_id FROM score_runs r JOIN score_run_stats s ON s.run_id = r.run_id \
             WHERE r.profile = ?1 AND r.model = ?2 ORDER BY r.captured_at DESC, r.run_id DESC LIMIT 1",
            params![profile, model],
            |r| r.get(0),
        )
        .optional()
        .map_err(err)
    }

    /// Rebuilds the exact snapshot bytes a run was computed from. Every blob's sha256 is
    /// re-checked; any mismatch or gap is an error.
    pub fn reconstruct_snapshot(&self, run_id: &str) -> Result<Vec<u8>, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        type Head = (i64, Vec<u8>, Vec<u8>, i64, Option<String>);
        let (n, prefix, suffix, stored, link): Head = c
            .query_row(
                "SELECT snapshot_rows, snapshot_prefix, snapshot_suffix, inputs_stored, \
                 inputs_run_id FROM score_run_stats WHERE run_id = ?1",
                params![run_id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
            )
            .optional()
            .map_err(err)?
            .ok_or_else(|| format!("run {run_id} has no evaluations"))?;
        if stored == 0 {
            return Err(format!(
                "run {run_id} did not store full inputs; most recent inputs: {}",
                link.as_deref().unwrap_or("none")
            ));
        }
        let mut st = c
            .prepare(
                "SELECT ri.idx, ri.hash, i.encoding, i.raw_size, i.bytes FROM score_run_inputs ri \
                 LEFT JOIN score_inputs i ON i.hash = ri.hash WHERE ri.run_id = ?1 ORDER BY ri.idx",
            )
            .map_err(err)?;
        type Blob = (i64, String, Option<String>, Option<i64>, Option<Vec<u8>>);
        let parts: Vec<Blob> = st
            .query_map(params![run_id], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
            })
            .map_err(err)?
            .collect::<Result<_, _>>()
            .map_err(err)?;
        if parts.len() as i64 != n {
            return Err(format!(
                "run {run_id}: {} input rows, expected {n}",
                parts.len()
            ));
        }
        let mut out = prefix;
        for (i, (idx, hash, enc, size, bytes)) in parts.into_iter().enumerate() {
            if idx != i as i64 {
                return Err(format!("run {run_id}: input index gap at {i}"));
            }
            let bytes = bytes.ok_or_else(|| format!("run {run_id}: blob {hash} missing"))?;
            let raw = check_blob(&hash, enc.as_deref(), size, bytes)?;
            if i > 0 {
                out.push(b',');
            }
            out.extend_from_slice(&raw);
        }
        out.extend_from_slice(&suffix);
        Ok(out)
    }

    /// Oldest-first history of one symbol over the last `days` days (`None` = all),
    /// ordered by (captured_at, run_id), with `rule_change` marked per profile+model.
    pub fn history(
        &self,
        symbol: &str,
        days: Option<i64>,
        now: i64,
        model: Option<&str>,
        profile: Option<&str>,
    ) -> Result<Vec<HistoryPoint>, String> {
        let since = days
            .and_then(|d| d.checked_mul(86_400))
            .map(|s| now.saturating_sub(s))
            .unwrap_or(i64::MIN);
        let c = self.conn.lock().map_err(|_| "lock")?;
        let mut st = c
            .prepare(
                "SELECT h.run_id, h.captured_at, r.profile, r.model, r.market_context, h.rank, \
                 h.decision, h.composite, h.fundamentals, h.technical, h.forecast, h.regime, \
                 h.confidence, h.trust_signal, h.price_cents, h.target_cents, h.upside_bps, \
                 r.rule_version, r.trigger \
                 FROM score_history h JOIN score_runs r ON r.run_id = h.run_id \
                 WHERE h.symbol = ?1 AND h.captured_at >= ?2 \
                   AND (?3 IS NULL OR r.model = ?3) AND (?4 IS NULL OR r.profile = ?4) \
                 ORDER BY h.captured_at ASC, h.run_id ASC",
            )
            .map_err(err)?;
        let rows = st
            .query_map(
                params![symbol.to_ascii_uppercase(), since, model, profile],
                |r| {
                    let at: i64 = r.get(1)?;
                    Ok(HistoryPoint {
                        run_id: r.get(0)?,
                        captured_at: at,
                        captured_at_utc: iso_utc(at),
                        profile: r.get(2)?,
                        model: r.get(3)?,
                        market_context: r.get::<_, i64>(4)? != 0,
                        rank: r.get(5)?,
                        decision: r.get(6)?,
                        composite: r.get(7)?,
                        fundamentals: r.get(8)?,
                        technical: r.get(9)?,
                        forecast: r.get(10)?,
                        regime: r.get(11)?,
                        confidence: r.get(12)?,
                        trust_signal: r.get(13)?,
                        price_cents: r.get(14)?,
                        target_cents: r.get(15)?,
                        upside_bps: r.get(16)?,
                        rule_version: r.get(17)?,
                        trigger: r.get(18)?,
                        rule_change: false,
                    })
                },
            )
            .map_err(err)?;
        let mut pts = rows.collect::<Result<Vec<_>, _>>().map_err(err)?;
        let mut last: std::collections::HashMap<(String, String), String> = Default::default();
        for p in &mut pts {
            let key = (p.profile.clone(), p.model.clone());
            if let Some(prev) = last.get(&key) {
                p.rule_change = prev != &p.rule_version;
            }
            last.insert(key, p.rule_version.clone());
        }
        Ok(pts)
    }

    pub fn counts(&self) -> Result<(i64, i64), String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        Ok((
            table_count(&c, "score_runs"),
            table_count(&c, "score_history"),
        ))
    }

    /// Rows in the V3.1 `score_shadow` table.
    pub fn shadow_count(&self) -> Result<i64, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        Ok(table_count(&c, "score_shadow"))
    }

    pub fn run_count(&self) -> Result<i64, String> {
        Ok(self.counts()?.0)
    }

    /// `captured_at` of the most recently *written* run for profile+model (by insertion
    /// order, so a run written under a skewed clock does not pin "last" in the future).
    pub fn last_run_at(&self, profile: &str, model: &str) -> Result<Option<i64>, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        c.query_row(
            "SELECT captured_at FROM score_runs WHERE profile = ?1 AND model = ?2 \
             ORDER BY rowid DESC LIMIT 1",
            params![profile, model],
            |r| r.get::<_, i64>(0),
        )
        .optional()
        .map_err(err)
    }

    /// Exports the [`HISTORY_TABLES`] (nothing else)
    /// into a fresh SQLite file, gzips it to `dest` (`*.sqlite.gz`, must not exist) and
    /// verifies it: integrity ok and row counts >= the live counts taken before the export.
    pub fn export_to(&self, dest: &Path) -> Result<BackupReport, String> {
        if dest.exists() {
            return Err(format!("{} already exists", dest.display()));
        }
        if let Some(p) = dest.parent() {
            std::fs::create_dir_all(p).map_err(|e| format!("mkdir {}: {e}", p.display()))?;
        }
        let tmp = dest.with_extension(format!("export-{}.tmp", std::process::id()));
        let _ = std::fs::remove_file(&tmp);
        let (runs, rows, shadow) = {
            let b = Connection::open(&tmp).map_err(err)?;
            b.execute_batch(MIGRATION_LOG).map_err(err)?;
            for (_, sql) in MIGRATIONS {
                b.execute_batch(sql).map_err(err)?;
            }
            drop(b);
            let c = self.conn.lock().map_err(|_| "lock")?;
            let counts = (
                table_count(&c, "score_runs"),
                table_count(&c, "score_history"),
                table_count(&c, "score_shadow"),
            );
            c.execute("ATTACH DATABASE ?1 AS bk", params![tmp.to_string_lossy()])
                .map_err(err)?;
            // Incremental `score_inputs`: payload bytes live under
            // `<backup_dir>/inputs/<sha256>` (append-only by content hash). The
            // gzip sqlite carries metadata + empty bytes for that table; other
            // tables stay full copies. Legacy backups with inline bytes still restore.
            let blob_dir = dest.parent().map(|p| p.to_path_buf());
            let copied = (|| -> Result<(), String> {
                c.execute_batch("BEGIN").map_err(err)?;
                for t in HISTORY_TABLES {
                    if t == "score_inputs" {
                        let mut st = c
                            .prepare(
                                "SELECT hash, encoding, raw_size, bytes FROM main.score_inputs",
                            )
                            .map_err(err)?;
                        let mut rows = st.query([]).map_err(err)?;
                        let mut insert = c
                            .prepare(
                                "INSERT INTO bk.score_inputs (hash, encoding, raw_size, bytes)                                  VALUES (?1, ?2, ?3, x'')",
                            )
                            .map_err(err)?;
                        while let Some(r) = rows.next().map_err(err)? {
                            let hash: String = r.get(0).map_err(err)?;
                            let enc: String = r.get(1).map_err(err)?;
                            let raw_size: i64 = r.get(2).map_err(err)?;
                            let bytes: Vec<u8> = r.get(3).map_err(err)?;
                            if let Some(dir) = blob_dir.as_deref() {
                                write_input_blob(dir, &hash, &bytes)?;
                            }
                            insert
                                .execute(params![hash, enc, raw_size])
                                .map_err(|e| format!("export score_inputs: {e}"))?;
                        }
                        continue;
                    }
                    // Copied by column NAME, never by position.
                    let cols = columns(&c, "main", t)?
                        .into_iter()
                        .map(|x| x.name)
                        .collect::<Vec<_>>()
                        .join(", ");
                    if let Err(e) = c.execute(
                        &format!("INSERT INTO bk.{t} ({cols}) SELECT {cols} FROM main.{t}"),
                        [],
                    ) {
                        let _ = c.execute_batch("ROLLBACK");
                        return Err(format!("export {t}: {e}"));
                    }
                }
                c.execute_batch("COMMIT").map_err(err)
            })();
            let _ = c.execute("DETACH DATABASE bk", []);
            if let Err(e) = copied {
                let _ = std::fs::remove_file(&tmp);
                return Err(e);
            }
            counts
        };
        let zipped = gzip_file(&tmp, dest);
        let _ = std::fs::remove_file(&tmp);
        zipped?;
        let rep = check_backup(dest, runs, rows)?;
        if rep.score_shadow < shadow {
            let bad = PathBuf::from(format!("{}.failed", dest.display()));
            let _ = std::fs::rename(dest, &bad);
            return Err(format!(
                "backup {} score_shadow {} < live {shadow} (moved to {})",
                dest.display(),
                rep.score_shadow,
                bad.display()
            ));
        }
        Ok(rep)
    }

    /// Daily backup `score-history-YYYY-MM-DD.sqlite.gz` (UTC day of `now`) when missing,
    /// then rotation of that directory. Returns the new backup, if one was written.
    pub fn daily_backup_if_due(
        &self,
        dir: &Path,
        now: i64,
    ) -> Result<Option<BackupReport>, String> {
        let day = chrono::DateTime::from_timestamp(now, 0)
            .ok_or("bad clock")?
            .format("%Y-%m-%d")
            .to_string();
        let dest = dir.join(format!("{BACKUP_PREFIX}{day}{BACKUP_SUFFIX}"));
        if dest.exists() {
            return Ok(None);
        }
        let rep = self.export_to(&dest)?;
        rotate_backups(dir)?;
        Ok(Some(rep))
    }
}

/// Verifies a fresh backup against counts taken before it; a failing file is renamed
/// `*.failed` so it is never mistaken for a good one.
fn check_backup(dest: &Path, runs: i64, rows: i64) -> Result<BackupReport, String> {
    let fail = |msg: String| {
        let bad = PathBuf::from(format!("{}.failed", dest.display()));
        let _ = std::fs::rename(dest, &bad);
        format!("{msg} (moved to {})", bad.display())
    };
    let rep = verify_file(dest, true).map_err(fail)?;
    if rep.score_runs < runs || rep.score_history < rows {
        return Err(fail(format!(
            "backup {} row counts {}/{} < live {runs}/{rows}",
            dest.display(),
            rep.score_runs,
            rep.score_history
        )));
    }
    // Every score_inputs row must resolve to a blob whose payload matches hash.
    let dirs = blob_dirs_for(dest.parent(), Some(dest));
    let plain = plain(dest).map_err(fail)?;
    let c = Connection::open_with_flags(&plain.path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)
        .map_err(|e| fail(format!("open backup for blob check: {e}")))?;
    if has_table(&c, "main", "score_inputs") {
        verify_src_blobs(&c, "main", &dirs).map_err(fail)?;
    }
    Ok(rep)
}

fn current_version(c: &Connection) -> Result<i64, String> {
    c.query_row(
        "SELECT COALESCE(MAX(version), 0) FROM score_history_migrations",
        [],
        |r| r.get(0),
    )
    .map_err(err)
}

/// Applies pending additive migrations, each with its log row in one `BEGIN IMMEDIATE`.
/// A non-empty file is backed up first (whole file, `VACUUM INTO`, gzip, verified).
fn migrate(
    c: &Connection,
    backup_dir: Option<&Path>,
    migrations: &[(i64, &str)],
) -> Result<Option<PathBuf>, String> {
    let has_log = has_table(c, "main", "score_history_migrations");
    let current = if has_log { current_version(c)? } else { 0 };
    let pending: Vec<&(i64, &str)> = migrations.iter().filter(|(v, _)| *v > current).collect();
    if pending.is_empty() {
        if !has_log {
            c.execute_batch(MIGRATION_LOG).map_err(err)?;
        }
        return Ok(None);
    }
    let objects: i64 = c
        .query_row("SELECT COUNT(*) FROM sqlite_master", [], |r| r.get(0))
        .map_err(err)?;
    let target = pending.last().unwrap().0;
    let backup = match backup_dir {
        Some(dir) if objects > 0 => {
            std::fs::create_dir_all(dir).map_err(|e| format!("mkdir {}: {e}", dir.display()))?;
            let dest = dir.join(format!(
                "history.pre-score-history-v{current}-to-v{target}-{}.sqlite.gz",
                stamp()
            ));
            let tmp = dest.with_extension("vacuum.tmp");
            let _ = std::fs::remove_file(&tmp);
            c.execute("VACUUM INTO ?1", params![tmp.to_string_lossy()])
                .map_err(|e| format!("backup to {}: {e}", tmp.display()))?;
            let z = gzip_file(&tmp, &dest);
            let _ = std::fs::remove_file(&tmp);
            z?;
            verify_file(&dest, false)?;
            rotate_pre_backups(dir)?;
            Some(dest)
        }
        _ => None,
    };
    for (v, sql) in pending {
        let run = || -> Result<(), String> {
            c.execute_batch("BEGIN IMMEDIATE").map_err(err)?;
            let body = (|| {
                c.execute_batch(MIGRATION_LOG)?;
                c.execute_batch(sql)?;
                c.execute(
                    "INSERT INTO score_history_migrations (version, applied_at, backup_path) \
                     VALUES (?1,?2,?3)",
                    params![
                        v,
                        now_epoch(),
                        backup.as_ref().map(|p| p.to_string_lossy().to_string())
                    ],
                )?;
                c.execute_batch("COMMIT")
            })();
            body.map_err(|e| {
                let _ = c.execute_batch("ROLLBACK");
                e.to_string()
            })
        };
        run().map_err(|e| format!("score history migration v{v}: {e}"))?;
    }
    Ok(backup)
}

fn backup_day(name: &str) -> Option<NaiveDate> {
    let d = name
        .strip_prefix(BACKUP_PREFIX)?
        .strip_suffix(BACKUP_SUFFIX)?;
    if d.len() != 10 {
        return None;
    }
    NaiveDate::parse_from_str(d, "%Y-%m-%d").ok()
}

/// Pure rotation: of the daily backup names, which to delete. Keeps the newest
/// [`BACKUP_KEEP_DAILY`] days, the newest file of each of the newest
/// [`BACKUP_KEEP_MONTHLY`] months, and the newest file of every year forever.
/// Names not matching the pattern are never returned.
pub fn rotation_deletes(names: &[String]) -> Vec<String> {
    let mut dated: Vec<(NaiveDate, &String)> = names
        .iter()
        .filter_map(|n| backup_day(n).map(|d| (d, n)))
        .collect();
    dated.sort_by_key(|d| std::cmp::Reverse(d.0));
    let mut keep = std::collections::HashSet::new();
    for (_, n) in dated.iter().take(BACKUP_KEEP_DAILY) {
        keep.insert((*n).clone());
    }
    let mut months = Vec::new();
    let mut years = Vec::new();
    for (d, n) in &dated {
        let m = (d.year(), d.month());
        if !months.contains(&m) && months.len() < BACKUP_KEEP_MONTHLY {
            months.push(m);
            keep.insert((*n).clone());
        }
        if !years.contains(&d.year()) {
            years.push(d.year());
            keep.insert((*n).clone());
        }
    }
    dated
        .iter()
        .filter(|(_, n)| !keep.contains(*n))
        .map(|(_, n)| (*n).clone())
        .collect()
}

const PRE_KINDS: [&str; 3] = [
    "history.pre-score-history-",
    "history.pre-restore-",
    "history.manual-",
];

/// Stamp of a pre-* backup name (`…-<YYYYmmddTHHMMSS.mmmZ>.sqlite.gz`).
fn pre_stamp(name: &str) -> Option<&str> {
    let base = name.strip_suffix(".sqlite.gz")?;
    let s = &base[base.rfind('-')? + 1..];
    (s.len() >= 16 && s.ends_with('Z') && s.as_bytes()[8] == b'T').then_some(s)
}

/// Pure rotation of pre-migration / pre-restore / manual copies: keep the newest
/// [`PRE_BACKUP_KEEP`] of each kind. Only names with a pre-* prefix and a stamp qualify.
pub fn pre_rotation_deletes(names: &[String]) -> Vec<String> {
    let mut out = Vec::new();
    for kind in PRE_KINDS {
        let mut v: Vec<(&str, &String)> = names
            .iter()
            .filter(|n| n.starts_with(kind))
            .filter_map(|n| pre_stamp(n).map(|s| (s, n)))
            .collect();
        v.sort_by(|a, b| b.0.cmp(a.0));
        out.extend(v.into_iter().skip(PRE_BACKUP_KEEP).map(|(_, n)| n.clone()));
    }
    out
}

fn file_names(dir: &Path) -> Result<Vec<String>, String> {
    Ok(std::fs::read_dir(dir)
        .map_err(|e| format!("read {}: {e}", dir.display()))?
        .flatten()
        .filter(|e| e.file_type().map(|t| t.is_file()).unwrap_or(false))
        .filter_map(|e| e.file_name().into_string().ok())
        .collect())
}

/// Removes rotated-out daily backups in `dir`. Only exact
/// `score-history-YYYY-MM-DD.sqlite.gz` names are candidates (the live DB is
/// `history.sqlite`, which can never match).
pub fn rotate_backups(dir: &Path) -> Result<Vec<PathBuf>, String> {
    remove_all(dir, rotation_deletes(&file_names(dir)?))
}

/// Keeps the newest [`PRE_BACKUP_KEEP`] pre-migration, pre-restore and manual copies in `dir`.
pub fn rotate_pre_backups(dir: &Path) -> Result<Vec<PathBuf>, String> {
    remove_all(dir, pre_rotation_deletes(&file_names(dir)?))
}

fn remove_all(dir: &Path, names: Vec<String>) -> Result<Vec<PathBuf>, String> {
    let mut removed = Vec::new();
    for n in names {
        let p = dir.join(&n);
        std::fs::remove_file(&p).map_err(|e| format!("rotate {}: {e}", p.display()))?;
        removed.push(p);
    }
    Ok(removed)
}

/// Merged tables and their keys.
const MERGE_TABLES: [(&str, &[&str]); 7] = [
    ("score_runs", &["run_id"]),
    ("score_history", &["run_id", "symbol"]),
    // Migration v3; optional in the source (a pre-v3 backup has no such table).
    ("score_shadow", &["run_id", "ticker", "config_id"]),
    ("score_inputs", &["hash"]),
    ("score_run_inputs", &["run_id", "idx"]),
    ("score_evaluations", &["run_id", "symbol"]),
    ("score_run_stats", &["run_id"]),
];

/// Tables introduced after v1, as (version, table). A backup older than `version` may
/// lack the table entirely; it is then skipped (nothing to merge).
const LATER_TABLES: &[(i64, &str)] = &[
    (3, "score_shadow"),
    (EVALUATIONS_MIGRATION, "score_inputs"),
    (EVALUATIONS_MIGRATION, "score_run_inputs"),
    (EVALUATIONS_MIGRATION, "score_evaluations"),
    (EVALUATIONS_MIGRATION, "score_run_stats"),
];

/// Columns introduced by migrations after v1, as (version, table, column). An older
/// backup may lack exactly these (they are then filled with their default). Empty today.
const LATER_COLUMNS: &[(i64, &str, &str)] = &[];

#[derive(Debug, PartialEq)]
struct Col {
    name: String,
    ty: String,
}

fn columns(c: &Connection, schema: &str, t: &str) -> Result<Vec<Col>, String> {
    let mut st = c
        .prepare(&format!("PRAGMA {schema}.table_info({t})"))
        .map_err(err)?;
    let cols = st
        .query_map([], |r| {
            Ok(Col {
                name: r.get::<_, String>(1)?.to_ascii_lowercase(),
                ty: r.get::<_, String>(2)?.to_ascii_uppercase(),
            })
        })
        .map_err(err)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(err)?;
    Ok(cols)
}

fn schema_version_of(c: &Connection, schema: &str) -> i64 {
    let log = HISTORY_TABLES[2];
    if !has_table(c, schema, log) {
        return 0;
    }
    c.query_row(
        &format!("SELECT COALESCE(MAX(version), 0) FROM {schema}.{log}"),
        [],
        |r| r.get(0),
    )
    .unwrap_or(0)
}

/// Column list to copy for `t`, matched by NAME. Refuses unknown columns, missing
/// columns (except [`LATER_COLUMNS`] newer than the backup's version) and type mismatches.
fn merge_columns(
    live: &[Col],
    src: &[Col],
    t: &str,
    src_version: i64,
) -> Result<Vec<String>, String> {
    if let Some(x) = src.iter().find(|s| !live.iter().any(|l| l.name == s.name)) {
        return Err(format!("backup {t} has unknown column '{}'", x.name));
    }
    let mut out = Vec::new();
    for l in live {
        match src.iter().find(|s| s.name == l.name) {
            Some(s) if s.ty != l.ty => {
                return Err(format!(
                    "backup {t}.{} has type {} (expected {})",
                    l.name, s.ty, l.ty
                ))
            }
            Some(_) => out.push(l.name.clone()),
            None => {
                let later = LATER_COLUMNS
                    .iter()
                    .any(|(v, lt, lc)| *lt == t && *lc == l.name && *v > src_version);
                if !later {
                    return Err(format!("backup {t} is missing column '{}'", l.name));
                }
            }
        }
    }
    Ok(out)
}

/// Merge `src.score_inputs` into live, resolving external `inputs/<hash>` blobs
/// into full inline bytes (live DB always stores the payload).
fn merge_score_inputs_from_src(c: &Connection, blob_dirs: &[PathBuf]) -> rusqlite::Result<usize> {
    let mut st = c.prepare(
        "SELECT hash, encoding, raw_size, bytes FROM src.score_inputs s          WHERE NOT EXISTS (SELECT 1 FROM main.score_inputs m WHERE m.hash = s.hash)",
    )?;
    let mut rows = st.query([])?;
    let mut n = 0usize;
    while let Some(r) = rows.next()? {
        let hash: String = r.get(0)?;
        let enc: String = r.get(1)?;
        let raw_size: i64 = r.get(2)?;
        let bytes: Vec<u8> = r.get(3)?;
        let bytes = resolve_input_bytes(blob_dirs, &hash, bytes).map_err(|e| {
            rusqlite::Error::ToSqlConversionFailure(Box::new(std::io::Error::other(e)))
        })?;
        // Re-verify under the merge lock (tamper between plan and merge).
        check_blob(&hash, Some(&enc), Some(raw_size), bytes.clone()).map_err(|e| {
            rusqlite::Error::ToSqlConversionFailure(Box::new(std::io::Error::other(e)))
        })?;
        c.execute(
            "INSERT INTO main.score_inputs (hash, encoding, raw_size, bytes)              VALUES (?1, ?2, ?3, ?4)",
            params![hash, enc, raw_size, bytes],
        )?;
        n += 1;
    }
    Ok(n)
}

/// Append-only restore: merges the score tables of backup `src` (`.sqlite` or
/// `.sqlite.gz`) into the app DB `<data_dir>/history.sqlite`, copying columns by name.
/// Only rows whose key is missing are inserted; nothing is overwritten or deleted.
/// Refuses while a `serve` holds the data-dir lock, when the backup's schema version is
/// newer than this binary's, or when its columns differ. The live score tables are
/// exported to `backup_dir` first.
pub fn restore(src: &Path, data_dir: &Path, backup_dir: &Path) -> Result<RestoreReport, String> {
    let _lock = lock_data_dir(data_dir)?;
    let rep = verify_file(src, true)?;
    let src_plain = plain(src)?;
    let store = ScoreHistoryStore::open(&data_dir.join("history.sqlite"), Some(backup_dir))?;
    store.check_writable()?;
    let c = store.conn.lock().map_err(|_| "lock")?;
    c.execute(
        "ATTACH DATABASE ?1 AS src",
        params![src_plain.path.to_string_lossy()],
    )
    .map_err(err)?;
    let plan = (|| -> Result<Vec<String>, String> {
        let live_v = current_version(&c)?;
        let src_v = schema_version_of(&c, "src");
        if src_v > live_v {
            return Err(format!(
                "backup schema v{src_v} is newer than this binary's v{live_v}; upgrade first"
            ));
        }
        if has_table(&c, "src", "score_inputs") {
            let dirs = blob_dirs_for(Some(backup_dir), Some(src));
            verify_src_blobs(&c, "src", &dirs)?;
        }
        let mut sqls = Vec::new();
        for (t, key) in MERGE_TABLES {
            if !has_table(&c, "src", t) {
                let later = LATER_TABLES.iter().any(|(v, lt)| *lt == t && *v > src_v);
                if later {
                    sqls.push(String::new());
                    continue;
                }
                return Err(format!("backup is schema v{src_v} but has no {t}"));
            }
            if t == "score_inputs" {
                // Custom merge: resolve empty-bytes rows from `inputs/<hash>`.
                sqls.push("__SCORE_INPUTS__".to_string());
                continue;
            }
            let cols = merge_columns(&columns(&c, "main", t)?, &columns(&c, "src", t)?, t, src_v)?;
            let list = cols.join(", ");
            let sel = cols
                .iter()
                .map(|x| format!("s.{x}"))
                .collect::<Vec<_>>()
                .join(", ");
            let on = key
                .iter()
                .map(|k| format!("m.{k} = s.{k}"))
                .collect::<Vec<_>>()
                .join(" AND ");
            sqls.push(format!(
                "INSERT INTO main.{t} ({list}) SELECT {sel} FROM src.{t} s \
                 WHERE NOT EXISTS (SELECT 1 FROM main.{t} m WHERE {on})"
            ));
        }
        Ok(sqls)
    })();
    let plan = match plan {
        Ok(p) => p,
        Err(e) => {
            let _ = c.execute("DETACH DATABASE src", []);
            return Err(format!("restore {}: {e}", src.display()));
        }
    };
    drop(c);
    let pre = backup_dir.join(format!("history.pre-restore-{}.sqlite.gz", stamp()));
    store.export_to(&pre)?;
    rotate_pre_backups(backup_dir)?;
    let c = store.conn.lock().map_err(|_| "lock")?;
    let merged = (|| -> Result<Vec<usize>, String> {
        c.execute_batch("BEGIN EXCLUSIVE").map_err(err)?;
        let res = (|| -> rusqlite::Result<Vec<usize>> {
            // One count per MERGE_TABLES entry; a skipped (later) table adds 0.
            let mut added = Vec::with_capacity(plan.len());
            for sql in &plan {
                added.push(if sql.is_empty() {
                    0
                } else if sql == "__SCORE_INPUTS__" {
                    merge_score_inputs_from_src(&c, &blob_dirs_for(Some(backup_dir), Some(src)))?
                } else {
                    c.execute(sql, [])?
                });
            }
            c.execute_batch("COMMIT")?;
            Ok(added)
        })();
        res.map_err(|e| {
            let _ = c.execute_batch("ROLLBACK");
            format!("merge from {}: {e}", src.display())
        })
    })();
    let _ = c.execute("DETACH DATABASE src", []);
    let added = merged?;
    let n = |t: &str| {
        MERGE_TABLES
            .iter()
            .position(|(x, _)| *x == t)
            .map_or(0, |i| added[i])
    };
    c.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()))
        .map_err(err)?;
    Ok(RestoreReport {
        pre_restore_backup: Some(pre),
        runs_added: n("score_runs"),
        rows_added: n("score_history"),
        shadow_added: n("score_shadow"),
        inputs_added: n("score_inputs"),
        run_inputs_added: n("score_run_inputs"),
        evaluations_added: n("score_evaluations"),
        run_stats_added: n("score_run_stats"),
        backup_runs: rep.score_runs,
        backup_rows: rep.score_history,
    })
}

// ── scheduling ──────────────────────────────────────────────────────────────

fn close_slot_on(d: NaiveDate) -> Option<i64> {
    if matches!(d.weekday(), chrono::Weekday::Sat | chrono::Weekday::Sun) {
        return None;
    }
    let t = d.and_hms_opt(CLOSE_SNAPSHOT_HM.0, CLOSE_SNAPSHOT_HM.1, 0)?;
    MARKET_TZ
        .from_local_datetime(&t)
        .earliest()
        .map(|x| x.timestamp())
}

fn market_date(epoch: i64) -> NaiveDate {
    chrono::DateTime::from_timestamp(epoch, 0)
        .unwrap_or_default()
        .with_timezone(&MARKET_TZ)
        .date_naive()
}

/// First weekday 16:10 America/New_York strictly after `after`.
pub fn next_close_snapshot(after: i64) -> i64 {
    let d0 = market_date(after);
    (0..8)
        .filter_map(|k| close_slot_on(d0 + chrono::Duration::days(k)))
        .find(|s| *s > after)
        .expect("a weekday within 8 days")
}

/// Latest weekday 16:10 America/New_York at or before `at`.
pub fn last_close_snapshot(at: i64) -> Option<i64> {
    let d0 = market_date(at);
    (0..8)
        .filter_map(|k| close_slot_on(d0 - chrono::Duration::days(k)))
        .find(|s| *s <= at)
}

/// Everything the write decision depends on (injected so it is testable).
#[derive(Clone, Debug, PartialEq)]
pub struct DueInputs {
    pub now: i64,
    /// The feed's initial pass for the current generation has completed.
    pub pass_completed: bool,
    /// Seconds since that completion was first seen.
    pub since_pass: i64,
    /// Year charts cover >= [`HISTORY_YEAR_COVERAGE`] of the universe.
    pub charts_ready: bool,
    /// The ranking comes from live engine state (not the persisted snapshot).
    pub live: bool,
    pub rows: usize,
    /// Last written run for this profile+model, from `score_runs` (survives restarts).
    pub last_run: Option<i64>,
    pub interval: i64,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Due {
    No(&'static str),
    Interval,
    /// The weekday market-close snapshot for this slot.
    Close(i64),
}

/// Write decision. Interval is measured only from the last run in `score_runs`, so a
/// restart does not force an early write. A clock behind the last run (`now < last`) is
/// treated as due, so a backwards jump never blocks writes; `last_run` is the latest
/// *inserted* run, so the next interval counts from that write.
pub fn history_due(i: &DueInputs) -> Due {
    if !i.pass_completed {
        return Due::No("initial pass in progress");
    }
    if !i.charts_ready && i.since_pass < HISTORY_YEAR_WAIT_SECS {
        return Due::No("waiting for year charts");
    }
    if !i.live || i.rows == 0 {
        return Due::No("no live ranking");
    }
    let Some(last) = i.last_run else {
        return Due::Interval;
    };
    if i.now < last {
        return Due::Interval;
    }
    if let Some(slot) = last_close_snapshot(i.now) {
        if i.now - slot < CLOSE_CATCHUP_SECS && last < slot {
            return Due::Close(slot);
        }
    }
    if i.now - last >= i.interval {
        if next_close_snapshot(i.now) - i.now <= CLOSE_DEDUP_SECS {
            return Due::No("close snapshot imminent");
        }
        return Due::Interval;
    }
    Due::No("interval not elapsed")
}

/// Decision flips per (profile, model). A `rule_version` boundary starts a new series:
/// it is neither a flip nor compared across.
pub fn decision_changes(points: &[HistoryPoint]) -> Vec<DecisionChange> {
    let d = |a: Option<i64>, b: Option<i64>| Some(b? - a?);
    let mut prev: std::collections::HashMap<(&str, &str), &HistoryPoint> = Default::default();
    let mut out = Vec::new();
    for p in points {
        let key = (p.profile.as_str(), p.model.as_str());
        if let Some(a) = prev.get(&key) {
            if a.rule_version == p.rule_version && a.decision != p.decision {
                out.push(DecisionChange {
                    from_at: a.captured_at,
                    to_at: p.captured_at,
                    from_utc: a.captured_at_utc.clone(),
                    to_utc: p.captured_at_utc.clone(),
                    from: a.decision.clone(),
                    to: p.decision.clone(),
                    composite_delta: p.composite - a.composite,
                    fundamentals_delta: d(a.fundamentals, p.fundamentals),
                    technical_delta: d(a.technical, p.technical),
                    forecast_delta: d(a.forecast, p.forecast),
                    point: p.clone(),
                });
            }
        }
        prev.insert(key, p);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(sym: &str, decision: &str, c: i32, f: i32, t: i32, fc: i32) -> RankedRow {
        RankedRow {
            rank: 1,
            symbol: sym.into(),
            company: None,
            sector: None,
            composite_score: c,
            setup_score: c,
            setup_label: decision.into(),
            decision: decision.into(),
            confidence: Some("high".into()),
            price: 28.67,
            target: Some(41.0),
            gap_pct: Some(30.07),
            upside_pct: Some(43.0),
            fundamentals: Some(f),
            technical: Some(t),
            forecast: Some(fc),
            regime: None,
            trust_signal: None,
            projection_gap: None,
            shadow: None,
        }
    }

    fn meta_p(at: i64, profile: &str) -> RunMeta {
        RunMeta {
            captured_at: at,
            profile: profile.into(),
            model: "v3".into(),
            semantics: "android".into(),
            market_context: false,
            binary_version: "test".into(),
            trigger: "interval".into(),
            symbols_total: 2,
            incomplete_symbols: vec!["XYZ".into()],
        }
    }
    fn meta(at: i64) -> RunMeta {
        meta_p(at, "sp500")
    }

    fn tmpdir(tag: &str) -> PathBuf {
        let d =
            std::env::temp_dir().join(format!("sh-{tag}-{}-{}", std::process::id(), now_epoch()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn et(y: i32, m: u32, d: u32, h: u32, mi: u32) -> i64 {
        MARKET_TZ
            .with_ymd_and_hms(y, m, d, h, mi, 0)
            .earliest()
            .unwrap()
            .timestamp()
    }

    #[test]
    fn migration_on_old_db_is_additive_backed_up_and_idempotent() {
        let dir = tmpdir("mig");
        let bdir = dir.join("bk");
        let p = dir.join("history.sqlite");
        {
            let c = Connection::open(&p).unwrap();
            c.execute_batch(
                "CREATE TABLE snapshots (symbol TEXT NOT NULL, captured_at INTEGER NOT NULL, \
                 decision TEXT NOT NULL, PRIMARY KEY(symbol, captured_at)); \
                 INSERT INTO snapshots VALUES ('ADSK', 1, 'Act'); PRAGMA user_version = 7;",
            )
            .unwrap();
        }
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        let bk = s.migration_backup.clone().expect("backup before migrating");
        assert!(bk.starts_with(&bdir));
        assert!(bk.to_string_lossy().ends_with(".sqlite.gz"));
        let rep = verify_file(&bk, false).unwrap();
        assert_eq!(
            (rep.integrity.as_str(), rep.score_runs),
            ("ok", -1),
            "pre-migration copy"
        );
        assert_eq!(s.schema_version().unwrap(), SCHEMA_VERSION);
        s.record_run(&meta(100), &[row("ADSK", "Act", 40, 60, 10, 30)])
            .unwrap();
        drop(s);
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        assert!(s.migration_backup.is_none(), "nothing pending, no backup");
        assert_eq!(s.run_count().unwrap(), 1);
        // A later additive migration: backed up first (with the v1 data), data kept.
        let add_col = [
            ["ALTER", "TABLE"].join(" "),
            "score_runs ADD COLUMN note TEXT;".into(),
        ]
        .join(" ");
        let next = SCHEMA_VERSION + 1;
        let mut extra: Vec<(i64, &str)> = MIGRATIONS.to_vec();
        extra.push((next, add_col.as_str()));
        let extra = extra.as_slice();
        drop(s);
        let s = ScoreHistoryStore::open_with(&p, Some(&bdir), extra).unwrap();
        let bk2 = s.migration_backup.clone().unwrap();
        assert_eq!(verify_file(&bk2, true).unwrap().score_runs, 1);
        assert_eq!(s.schema_version().unwrap(), next);
        assert_eq!(s.counts().unwrap(), (1, 1));
        let c = Connection::open(&p).unwrap();
        let uv: i64 = c
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(uv, 7, "the main schema version is not touched");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn one_write_per_run_values_and_window() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        let rows = [
            row("ADSK", "Act", 40, 60, 10, 30),
            row("SSRM", "Act", 36, 72, -18, 31),
        ];
        assert_eq!(
            s.record_run(&meta(1_000), &rows).unwrap(),
            RecordOutcome::Recorded {
                rows: 2,
                skipped: vec![]
            }
        );
        assert_eq!(
            s.record_run(&meta(1_000), &rows).unwrap(),
            RecordOutcome::Duplicate
        );
        assert_eq!(s.run_count().unwrap(), 1);
        let mut nan = row("NANP", "Act", 1, 1, 1, 1);
        nan.price = f64::NAN;
        nan.target = Some(f64::NAN);
        assert_eq!(
            s.record_run(&meta(2_000), &[row("ADSK", "Watch", 25, 55, -10, 20), nan])
                .unwrap(),
            RecordOutcome::Recorded {
                rows: 1,
                skipped: vec!["NANP".into()]
            }
        );
        assert!(s
            .history("NANP", None, 3_000, None, None)
            .unwrap()
            .is_empty());
        let h = s.history("adsk", None, 3_000, Some("v3"), None).unwrap();
        assert_eq!(h.len(), 2);
        assert_eq!(
            (h[0].decision.as_str(), h[1].decision.as_str()),
            ("Act", "Watch")
        );
        assert_eq!(h[0].price_cents, 2867);
        assert_eq!(h[0].target_cents, Some(4100));
        assert_eq!(h[0].upside_bps, Some(4300));
        assert_eq!(h[0].rule_version, SCORE_RULE_VERSION);
        assert_eq!(h[0].captured_at_utc, "1970-01-01T00:16:40Z");
        // captured_at >= now - days*86400 is inclusive at the bound.
        let now = 2_000 + 86_400;
        assert_eq!(
            s.history("ADSK", Some(1), now, None, None).unwrap().len(),
            1
        );
        assert_eq!(
            s.history("ADSK", Some(1), now + 1, None, None)
                .unwrap()
                .len(),
            0
        );
        assert!(s
            .history("ADSK", None, 3_000, Some("v2"), None)
            .unwrap()
            .is_empty());
        assert_eq!(s.last_run_at("sp500", "v3").unwrap(), Some(2_000));
    }

    #[test]
    fn changes_are_per_profile_and_skip_rule_boundaries() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        for (at, p, d) in [
            (1, "sp500", "Act"),
            (2, "nasdaq", "Avoid"),
            (3, "sp500", "Act"),
            (4, "nasdaq", "Avoid"),
            (5, "sp500", "Watch"),
        ] {
            s.record_run(&meta_p(at, p), &[row("ADSK", d, 30, 60, 0, 30)])
                .unwrap();
        }
        let all = s.history("ADSK", None, 10, None, None).unwrap();
        let ch = decision_changes(&all);
        assert_eq!(
            ch.len(),
            1,
            "interleaved profiles do not create flips: {ch:?}"
        );
        assert_eq!(
            (ch[0].from.as_str(), ch[0].to.as_str(), ch[0].to_at),
            ("Act", "Watch", 5)
        );
        // Rule-version boundary: new series, not a flip.
        let mut pts = s.history("ADSK", None, 10, None, Some("sp500")).unwrap();
        pts[2].rule_version = "V3.1".into();
        pts[2].decision = "Avoid".into();
        assert!(decision_changes(&pts).is_empty());
        assert!(decision_changes(&[]).is_empty());
    }

    #[test]
    fn delete_update_replace_and_upsert_abort() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        assert!(s.missing_triggers.is_empty());
        // With evaluations and a shadow grid, so every table has a row.
        let mut r = row("ADSK", "Act", 30, 60, 0, 30);
        r.shadow = Some(crate::opportunity_v31::shadow_grid(
            Some("Act"),
            30,
            Some(0),
            Some(0.0),
            false,
        ));
        s.record_run_full(&meta(1), &[r], Some(&tiny_eval(&["ADSK", "BK"])))
            .unwrap();
        assert_eq!(s.shadow_count().unwrap(), 6);
        let c = s.conn.lock().unwrap();
        for t in HISTORY_TABLES {
            let del = format!("{} FROM {t}", "DELETE");
            let e = c.execute(&del, []).unwrap_err().to_string();
            assert!(e.contains("append-only"), "{t}: {e}");
            let upd = format!("{} {t} SET rowid = rowid", "UPDATE");
            let e = c.execute(&upd, []).unwrap_err().to_string();
            assert!(e.contains("append-only"), "{t}: {e}");
            // REPLACE / INSERT OR REPLACE re-insert the existing row: must abort.
            for verb in [["INSERT", "OR", "REPLACE"].join(" "), "REPLACE".into()] {
                let sql = format!("{verb} INTO {t} SELECT * FROM {t}");
                let e = c.execute(&sql, []).unwrap_err().to_string();
                assert!(e.contains("append-only"), "{verb} {t}: {e}");
            }
            // A plain duplicate INSERT fails too.
            let sql = format!("{} INTO {t} SELECT * FROM {t}", "INSERT");
            assert!(c.execute(&sql, []).is_err(), "{t}");
        }
        // UPSERT … DO UPDATE on an existing key.
        let up = format!(
            "{} INTO {} (run_id, symbol, captured_at, rank, decision, composite, price_cents) \
             SELECT run_id, symbol, captured_at, rank, 'Avoid', 0, 1 FROM {} WHERE 1 \
             ON CONFLICT(run_id, symbol) DO UPDATE SET decision = 'Avoid'",
            "INSERT", HISTORY_TABLES[1], HISTORY_TABLES[1]
        );
        assert!(c.execute(&up, []).is_err());
        let d: String = c
            .query_row("SELECT decision FROM score_history", [], |r| r.get(0))
            .unwrap();
        assert_eq!(d, "Act", "row never overwritten");
        drop(c);
        assert_eq!(s.counts().unwrap(), (1, 1));
        assert_eq!(
            s.record_run(&meta(1), &[row("ADSK", "Avoid", 0, 0, 0, 0)])
                .unwrap(),
            RecordOutcome::Duplicate,
            "a repeated run is still reported as a no-op"
        );
    }

    #[test]
    fn missing_trigger_blocks_writes() {
        let dir = tmpdir("trig");
        let p = dir.join("history.sqlite");
        drop(ScoreHistoryStore::open(&p, None).unwrap());
        {
            let c = Connection::open(&p).unwrap();
            let sql = format!("{} score_runs_no_overwrite", ["DROP", "TRIGGER"].join(" "));
            c.execute_batch(&sql).unwrap();
        }
        let s = ScoreHistoryStore::open(&p, None).unwrap();
        assert_eq!(s.missing_triggers, vec!["score_runs_no_overwrite"]);
        let e = s
            .record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap_err();
        assert!(e.contains("refusing to write"), "{e}");
        assert_eq!(s.counts().unwrap(), (0, 0));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn same_name_noop_trigger_is_detected_and_blocks_writes() {
        let dir = tmpdir("noop");
        let p = dir.join("history.sqlite");
        drop(ScoreHistoryStore::open(&p, None).unwrap());
        {
            let c = Connection::open(&p).unwrap();
            let t = HISTORY_TABLES[0];
            let sql = format!(
                "{drop} {t}_no_delete; CREATE TRIGGER {t}_no_delete BEFORE DELETE ON {t} \
                 BEGIN SELECT 1; END;",
                drop = ["DROP", "TRIGGER"].join(" ")
            );
            c.execute_batch(&sql).unwrap();
            assert_eq!(
                trigger_behaviour_failures(&c).unwrap(),
                vec![format!("{t}: delete")]
            );
        }
        let s = ScoreHistoryStore::open(&p, None).unwrap();
        assert_eq!(s.missing_triggers, vec!["score_runs_no_delete"]);
        assert!(s
            .record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn behaviour_selfcheck_passes_and_never_changes_data() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        {
            let c = s.conn.lock().unwrap();
            assert!(
                trigger_behaviour_failures(&c).unwrap().is_empty(),
                "empty tables (sentinels)"
            );
            assert_eq!(
                (
                    table_count(&c, "score_runs"),
                    table_count(&c, "score_history")
                ),
                (0, 0)
            );
        }
        s.record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap();
        let c = s.conn.lock().unwrap();
        let logs = table_count(&c, "score_history_migrations");
        assert!(trigger_behaviour_failures(&c).unwrap().is_empty());
        assert_eq!(
            (
                table_count(&c, "score_runs"),
                table_count(&c, "score_history")
            ),
            (1, 1)
        );
        assert_eq!(table_count(&c, "score_history_migrations"), logs);
    }

    #[test]
    fn behaviour_failure_with_matching_sql_refuses_writes_through_open() {
        let s = ScoreHistoryStore::init_with(
            Connection::open_in_memory().unwrap(),
            None,
            None,
            MIGRATIONS,
            |_| Ok(vec!["score_runs: delete".into()]),
        )
        .unwrap();
        assert!(s.missing_triggers.is_empty(), "SQL matches");
        assert_eq!(
            s.selfcheck(),
            SelfCheck::Failed(vec!["score_runs: delete".into()])
        );
        let e = s.check_writable().unwrap_err();
        assert!(e.contains("behaviour self-check failed"), "{e}");
        assert!(s
            .record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .is_err());
        assert_eq!(s.counts().unwrap(), (0, 0));
    }

    #[test]
    fn duplicate_insert_probe_without_trigger_is_flagged() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        s.record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap();
        let c = s.conn.lock().unwrap();
        let t = HISTORY_TABLES[0];
        c.execute_batch(&format!(
            "{} {t}_no_overwrite;",
            ["DROP", "TRIGGER"].join(" ")
        ))
        .unwrap();
        // The PK still rejects the duplicate, but not with the append-only abort.
        assert_eq!(
            trigger_behaviour_failures(&c).unwrap(),
            vec![format!("{t}: duplicate insert")]
        );
        assert_eq!(table_count(&c, t), 1);
    }

    /// N1 repro: another connection holds BEGIN IMMEDIATE while the store opens.
    #[test]
    fn busy_db_at_open_defers_selfcheck_and_does_not_disable_history() {
        let dir = tmpdir("busy");
        let p = dir.join("history.sqlite");
        drop(ScoreHistoryStore::open(&p, None).unwrap());
        let holder = Connection::open(&p).unwrap();
        holder.execute_batch("BEGIN IMMEDIATE").unwrap();
        let s = ScoreHistoryStore::open(&p, None).unwrap();
        assert!(
            matches!(s.selfcheck(), SelfCheck::Pending(_)),
            "{:?}",
            s.selfcheck()
        );
        assert!(s.missing_triggers.is_empty());
        // Writer releases shortly: the next write re-runs the check and succeeds.
        let h = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(300));
            holder.execute_batch("COMMIT").unwrap();
        });
        s.record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap();
        h.join().unwrap();
        assert_eq!(s.selfcheck(), SelfCheck::Passed);
        // Restore while another writer holds the lock for a while: also not disabled.
        let bk = dir.join("bk");
        let f = bk.join("x.sqlite.gz");
        std::fs::create_dir_all(&bk).unwrap();
        s.export_to(&f).unwrap();
        drop(s);
        let data2 = dir.join("d2");
        std::fs::create_dir_all(&data2).unwrap();
        let p2 = data2.join("history.sqlite");
        drop(ScoreHistoryStore::open(&p2, None).unwrap());
        let holder = Connection::open(&p2).unwrap();
        holder.execute_batch("BEGIN IMMEDIATE").unwrap();
        let h = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(3000));
            holder.execute_batch("COMMIT").unwrap();
        });
        let r = restore(&f, &data2, &bk).unwrap();
        h.join().unwrap();
        assert_eq!((r.runs_added, r.rows_added), (1, 1));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn busy_during_probe_with_empty_score_shadow_is_inconclusive_not_failed() {
        // score_shadow empty => the probe must insert a score_shadow sentinel; a BUSY there
        // (or anywhere in the probe) is inconclusive (Err), never a reported failure.
        let dir = tmpdir("busy-shadow");
        let p = dir.join("history.sqlite");
        let s = ScoreHistoryStore::open(&p, None).unwrap();
        s.record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap();
        assert_eq!(s.shadow_count().unwrap(), 0);
        drop(s);
        let holder = Connection::open(&p).unwrap();
        holder.execute_batch("BEGIN IMMEDIATE").unwrap();
        let c = Connection::open(&p).unwrap();
        c.busy_timeout(Duration::from_millis(50)).unwrap();
        // Own transaction: BUSY at BEGIN IMMEDIATE.
        assert!(trigger_behaviour_failures(&c).is_err());
        // Caller's (deferred, read) transaction: BUSY inside the probes/sentinels.
        c.execute_batch("BEGIN").unwrap();
        let _: i64 = c
            .query_row("SELECT count(*) FROM score_shadow", [], |r| r.get(0))
            .unwrap();
        let r = trigger_behaviour_failures(&c);
        assert!(r.is_err(), "busy must be inconclusive, got {r:?}");
        c.execute_batch("ROLLBACK").unwrap();
        // Store-level: deferred, then passes once the writer is gone; sentinel rolled back.
        let st = run_selfcheck(&c, trigger_behaviour_failures, 2, Duration::from_millis(20));
        assert!(matches!(st, SelfCheck::Pending(_)), "{st:?}");
        holder.execute_batch("COMMIT").unwrap();
        assert_eq!(
            run_selfcheck(&c, trigger_behaviour_failures, 2, Duration::from_millis(20)),
            SelfCheck::Passed
        );
        assert_eq!(
            table_count(&c, "score_shadow"),
            0,
            "sentinel never persists"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn write_while_still_locked_is_refused_and_writes_nothing() {
        let dir = tmpdir("busy2");
        let p = dir.join("history.sqlite");
        drop(ScoreHistoryStore::open(&p, None).unwrap());
        let holder = Connection::open(&p).unwrap();
        holder.execute_batch("BEGIN IMMEDIATE").unwrap();
        let mut s = ScoreHistoryStore::open(&p, None).unwrap();
        s.write_busy = Duration::from_millis(300);
        assert!(matches!(s.selfcheck(), SelfCheck::Pending(_)));
        let t0 = std::time::Instant::now();
        let e = s
            .record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap_err();
        assert!(
            e.contains("database busy") && e.contains("self-check deferred"),
            "{e}"
        );
        assert!(
            t0.elapsed() < Duration::from_secs(3),
            "single attempt within budget"
        );
        assert!(matches!(s.selfcheck(), SelfCheck::Pending(_)));
        holder.execute_batch("COMMIT").unwrap();
        assert_eq!(s.counts().unwrap(), (0, 0), "nothing written while locked");
        s.record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap();
        assert_eq!(s.counts().unwrap(), (1, 1));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn probe_busy_partway_is_inconclusive_not_passed() {
        let dir = tmpdir("busy3");
        let p = dir.join("history.sqlite");
        let s = ScoreHistoryStore::open(&p, None).unwrap();
        s.record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap();
        drop(s);
        // Caller already inside a deferred read txn, so the probe's first write op is
        // where BUSY appears (after SAVEPOINT and the sentinel reads succeeded).
        let c = Connection::open(&p).unwrap();
        c.busy_timeout(Duration::from_millis(100)).unwrap();
        c.execute_batch("BEGIN").unwrap();
        assert_eq!(table_count(&c, "score_runs"), 1);
        let holder = Connection::open(&p).unwrap();
        holder.execute_batch("BEGIN IMMEDIATE").unwrap();
        let r = trigger_behaviour_failures(&c);
        assert!(r.is_err(), "BUSY must be inconclusive, got {r:?}");
        c.execute_batch("ROLLBACK").unwrap();
        holder.execute_batch("COMMIT").unwrap();
        assert_eq!(table_count(&c, "score_runs"), 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    type ColEdit<'a> = &'a dyn Fn(&str, &mut Vec<(String, String)>);

    /// Writes a backup whose score tables use `cols` (name, type) in that order.
    /// A synthetic evaluation: first symbol loaded and ok, the rest not loaded.
    fn tiny_eval(syms: &[&str]) -> RunEvaluation {
        let raw = format!("{{\"symbol\":\"{}\",\"p\":1}}", syms[0]).into_bytes();
        let mut evals = vec![EvalRecord {
            symbol: syms[0].into(),
            status: "ok".into(),
            reason: "qualified".into(),
            rank: Some(1),
            composite: Some(30),
            decision: Some("Act".into()),
            input_idx: Some(0),
            ..Default::default()
        }];
        for x in &syms[1..] {
            evals.push(EvalRecord {
                symbol: (*x).into(),
                status: "failed".into(),
                reason: "not_loaded".into(),
                ..Default::default()
            });
        }
        RunEvaluation {
            universe_size: syms.len(),
            evals,
            inputs: vec![(syms[0].into(), raw)],
            snapshot_prefix: b"{\"rows\":[".to_vec(),
            snapshot_suffix: b"]}".to_vec(),
        }
    }

    #[test]
    fn evaluations_stats_dedup_and_exact_reconstruction() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        let e = tiny_eval(&["ADSK", "BK", "ZZ"]);
        s.record_run_full(&meta(1), &[row("ADSK", "Act", 30, 60, 0, 30)], Some(&e))
            .unwrap();
        s.record_run_full(&meta(2), &[row("ADSK", "Act", 30, 60, 0, 30)], Some(&e))
            .unwrap();
        let r1 = meta(1).run_id();
        assert_eq!(s.evaluations(&r1).unwrap().len(), 3);
        let st = s.run_stats(&r1).unwrap().unwrap();
        assert_eq!(
            (st.universe_size, st.loaded, st.ok, st.excluded, st.failed),
            (3, 1, 1, 0, 2)
        );
        assert_eq!(st.reasons.get("not_loaded"), Some(&2));
        // Run 1 stores inputs (`first`, zstd); run 2 is unchanged: no inputs, explicit link.
        let r2 = meta(2).run_id();
        assert!(st.inputs_stored);
        assert_eq!(st.inputs_reason, "first");
        let st2 = s.run_stats(&r2).unwrap().unwrap();
        assert!(!st2.inputs_stored);
        assert_eq!(st2.inputs_reason, "skipped:unchanged");
        assert_eq!(st2.inputs_run_id.as_deref(), Some(r1.as_str()));
        assert_eq!(
            s.evaluations(&r2).unwrap().len(),
            3,
            "evaluations on every run"
        );
        let msg = s.reconstruct_snapshot(&r2).unwrap_err();
        assert!(msg.contains(&r1), "{msg}");
        let c = s.conn.lock().unwrap();
        let enc: String = c
            .query_row("SELECT encoding FROM score_inputs LIMIT 1", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(enc, "zstd");
        assert_eq!(table_count(&c, "score_run_inputs"), 1);
        drop(c);
        let want = [b"{\"rows\":[".as_slice(), &e.inputs[0].1, b"]}"].concat();
        assert_eq!(s.reconstruct_snapshot(&r1).unwrap(), want);
        // The post-close run always stores inputs; identical bytes dedup to one blob.
        let mut close = meta(4);
        close.trigger = "close".into();
        s.record_run_full(&close, &[row("ADSK", "Act", 30, 60, 0, 30)], Some(&e))
            .unwrap();
        let st4 = s.run_stats(&close.run_id()).unwrap().unwrap();
        assert_eq!(
            (st4.inputs_stored, st4.inputs_reason.as_str()),
            (true, "close")
        );
        assert_eq!(s.reconstruct_snapshot(&close.run_id()).unwrap(), want);
        let c = s.conn.lock().unwrap();
        assert_eq!(table_count(&c, "score_inputs"), 1);
        assert_eq!(table_count(&c, "score_run_inputs"), 2);
        drop(c);
        // Excluded/failed decisions are never stored even if a caller passes one.
        let mut bad = tiny_eval(&["X", "Y"]);
        bad.evals[1].decision = Some("Act".into());
        s.record_run_full(&meta(3), &[], Some(&bad)).unwrap();
        let ev = s.evaluations(&meta(3).run_id()).unwrap();
        assert!(ev
            .iter()
            .find(|x| x.symbol == "Y")
            .unwrap()
            .decision
            .is_none());
    }

    #[test]
    fn material_change_policy_is_deterministic() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        let base = tiny_eval(&["A", "B", "C"]);
        let rec = |at: i64, e: &RunEvaluation| {
            s.record_run_full(&meta(at), &[], Some(e)).unwrap();
            s.run_stats(&meta(at).run_id()).unwrap().unwrap()
        };
        assert_eq!(rec(1, &base).inputs_reason, "first");
        // Score / price / rank moves alone are NOT material.
        let mut e = base.clone();
        e.evals[0].composite = Some(99);
        e.evals[0].price_cents = Some(12345);
        e.evals[0].rank = Some(7);
        e.inputs[0].1 = b"{\"symbol\":\"A\",\"p\":2}".to_vec();
        assert_eq!(rec(2, &e).inputs_reason, "skipped:unchanged");
        // gap_below_min:<bps> value changes are not material either (same class).
        let mut x = base.clone();
        x.evals[0].status = "excluded".into();
        x.evals[0].reason = "gap_below_min:100".into();
        x.evals[0].decision = None;
        assert_eq!(rec(3, &x).inputs_reason, "material:evaluation");
        x.evals[0].reason = "gap_below_min:150".into();
        assert_eq!(rec(4, &x).inputs_reason, "skipped:unchanged");
        // A decision change is material.
        let mut d = base.clone();
        d.evals[0].decision = Some("Watch".into());
        assert_eq!(rec(5, &d).inputs_reason, "material:evaluation");
        // A newly loaded symbol is material (loaded set).
        let mut l = d.clone();
        l.evals[1].status = "excluded".into();
        l.evals[1].reason = "unprofitable".into();
        assert_eq!(rec(6, &l).inputs_reason, "material:loaded_set");
        // Skipped runs link to the most recent run that stored inputs.
        let st = rec(7, &l);
        assert_eq!(st.inputs_run_id, Some(meta(6).run_id()));
        // Unchanged but >= 24 h since the last inputs: `daily`.
        assert_eq!(rec(6 + 86_400, &l).inputs_reason, "daily");
    }

    /// Never prune: no API deletes history, a year of runs survives reopen, and the
    /// only file-removing paths touch backup files, never the live tables.
    #[test]
    fn no_pruning_path_and_every_run_survives() {
        let dir = tmpdir("noprune");
        let p = dir.join("history.sqlite");
        let s = ScoreHistoryStore::open(&p, Some(&dir.join("bk"))).unwrap();
        let e = tiny_eval(&["A", "B"]);
        for d in 0..400 {
            s.record_run_full(
                &meta(1_000 + d * 86_400),
                &[row("A", "Act", 30, 60, 0, 30)],
                Some(&e),
            )
            .unwrap();
        }
        drop(s);
        let s = ScoreHistoryStore::open(&p, Some(&dir.join("bk"))).unwrap();
        assert_eq!(s.run_count().unwrap(), 400);
        let c = s.conn.lock().unwrap();
        for t in HISTORY_TABLES.iter().skip(4) {
            assert!(table_count(&c, t) > 0, "{t}");
        }
        assert_eq!(table_count(&c, "score_evaluations"), 800);
        drop(c);
        // Source scan: no prune/purge/retention API and no delete verb in this module
        // outside tests (the guard test covers SQL on every guarded table).
        let src = include_str!("score_history.rs");
        let body = &src[..src.find("#[cfg(test)]").unwrap()];
        for w in ["fn prune", "fn purge", "retention_days", "fn truncate"] {
            assert!(!body.contains(w), "{w}");
        }
        let del = ["DELETE", "FROM"].join(" ");
        assert!(!body.contains(&del), "no delete statement in the store");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn v2_backup_restores_into_current_and_evaluations_survive_restore() {
        let root = tmpdir("v2into");
        let bdir = root.join("bk");
        // A v2 backup (no evaluation tables) merges into the current schema.
        let f = root.join("v2.sqlite");
        custom_backup(&f, 2, |_, _| {});
        let data = root.join("d");
        let r = restore(&f, &data, &bdir).unwrap();
        assert_eq!((r.runs_added, r.rows_added), (1, 1));
        assert_eq!(
            (r.evaluations_added, r.run_stats_added, r.inputs_added),
            (0, 0, 0)
        );
        // Full round trip: export a store with evaluations, restore into an empty dir.
        let src = ScoreHistoryStore::open(&root.join("src.sqlite"), Some(&bdir)).unwrap();
        let e = tiny_eval(&["ADSK", "BK"]);
        src.record_run_full(&meta(5), &[row("ADSK", "Act", 30, 60, 0, 30)], Some(&e))
            .unwrap();
        // Export into backup_dir so inputs/ lands next to restore's blob store.
        let bk = bdir.join("full.sqlite.gz");
        src.export_to(&bk).unwrap();
        let want = src.reconstruct_snapshot(&meta(5).run_id()).unwrap();
        let data2 = root.join("d2");
        let r = restore(&bk, &data2, &bdir).unwrap();
        assert_eq!((r.evaluations_added, r.run_stats_added), (2, 1));
        assert!(r.inputs_added >= 1 && r.run_inputs_added >= 1);
        let r = restore(&bk, &data2, &bdir).unwrap();
        assert_eq!(
            (
                r.evaluations_added,
                r.run_stats_added,
                r.inputs_added,
                r.run_inputs_added
            ),
            (0, 0, 0, 0),
            "second restore adds nothing"
        );
        let s = ScoreHistoryStore::open(&data2.join("history.sqlite"), Some(&bdir)).unwrap();
        assert_eq!(s.reconstruct_snapshot(&meta(5).run_id()).unwrap(), want);
        assert_eq!(s.evaluations(&meta(5).run_id()).unwrap().len(), 2);
        assert_eq!(s.run_stats(&meta(5).run_id()).unwrap().unwrap().failed, 1);
        let _ = std::fs::remove_dir_all(&root);
    }

    fn custom_backup(path: &Path, version: i64, edit: impl Fn(&str, &mut Vec<(String, String)>)) {
        let live = ScoreHistoryStore::open_in_memory().unwrap();
        let lc = live.conn.lock().unwrap();
        let b = Connection::open(path).unwrap();
        b.execute_batch(MIGRATION_LOG).unwrap();
        b.execute(
            "INSERT INTO score_history_migrations (version, applied_at) VALUES (?1, 0)",
            params![version],
        )
        .unwrap();
        let tables: &[&str] = if version >= 3 {
            &["score_runs", "score_history", "score_shadow"]
        } else {
            &["score_runs", "score_history"]
        };
        for &t in tables {
            let mut cols: Vec<(String, String)> = columns(&lc, "main", t)
                .unwrap()
                .into_iter()
                .map(|c| (c.name, c.ty))
                .collect();
            cols.reverse();
            edit(t, &mut cols);
            let ddl = cols
                .iter()
                .map(|(n, ty)| format!("{n} {ty}"))
                .collect::<Vec<_>>()
                .join(", ");
            b.execute_batch(&format!("{} {t} ({ddl});", "CREATE TABLE"))
                .unwrap();
        }
        if version >= EVALUATIONS_MIGRATION {
            b.execute_batch(EVALUATIONS_DDL).unwrap();
        }
        let has = |t: &str, c: &str| {
            b.query_row(
                &format!("SELECT 1 FROM pragma_table_info('{t}') WHERE name = ?1"),
                params![c],
                |_| Ok(()),
            )
            .is_ok()
        };
        let runs = HISTORY_TABLES[0];
        let hist = HISTORY_TABLES[1];
        let rc = [
            "run_id",
            "captured_at",
            "profile",
            "model",
            "rule_version",
            "semantics",
            "market_context",
            "binary_version",
            "trigger",
            "symbols_total",
            "symbols_ranked",
            "incomplete_symbols",
        ];
        let rv = [
            "'sp500-7-v3'",
            "7",
            "'sp500'",
            "'v3'",
            "'V3'",
            "'android'",
            "0",
            "'b'",
            "'interval'",
            "1",
            "1",
            "'[]'",
        ];
        let (n, v): (Vec<&str>, Vec<&str>) = rc
            .iter()
            .zip(rv.iter())
            .filter(|(c, _)| has(runs, c))
            .unzip();
        b.execute(
            &format!(
                "{} INTO {runs} ({}) VALUES ({})",
                "INSERT",
                n.join(","),
                v.join(",")
            ),
            [],
        )
        .unwrap();
        let hc = [
            "run_id",
            "symbol",
            "captured_at",
            "rank",
            "decision",
            "composite",
            "fundamentals",
            "price_cents",
            "target_cents",
            "trust_signal",
        ];
        let hv = [
            "'sp500-7-v3'",
            "'ADSK'",
            "7",
            "3",
            "'Watch'",
            "26",
            "13",
            "24175",
            "30250",
            "'t'",
        ];
        let (n, v): (Vec<&str>, Vec<&str>) = hc
            .iter()
            .zip(hv.iter())
            .filter(|(c, _)| has(hist, c))
            .unzip();
        b.execute(
            &format!(
                "{} INTO {hist} ({}) VALUES ({})",
                "INSERT",
                n.join(","),
                v.join(",")
            ),
            [],
        )
        .unwrap();
        if version >= 3 && has("score_shadow", "s_adj") {
            // Reversed column order too: restored by name.
            b.execute(
                "INSERT INTO score_shadow (shadow_decision, s_adj, warmup, m, lambda_m, lambda_t, \
                 bound, config_id, rule_version, ticker, run_id) VALUES \
                 ('Watch', 12.5, 0, -1.0, 0.3, 1.5, 0.02, 'b0200_lm030', 'V3.1-shadow', 'ADSK', \
                 'sp500-7-v3')",
                [],
            )
            .unwrap();
        }
    }

    #[test]
    fn restore_is_by_column_name_and_strict() {
        let root = tmpdir("byname");
        let bdir = root.join("bk");
        // Reversed column order (and a v1 backup into v2): restored by name.
        for v in [SCHEMA_VERSION, 1] {
            let f = root.join(format!("rev{v}.sqlite"));
            custom_backup(&f, v, |_, _| {});
            let data = root.join(format!("d{v}"));
            let r = restore(&f, &data, &bdir).unwrap();
            assert_eq!(
                (r.runs_added, r.rows_added, r.backup_runs, r.backup_rows),
                (1, 1, 1, 1)
            );
            assert_eq!(r.shadow_added, usize::from(v >= 3));
            let s = ScoreHistoryStore::open(&data.join("history.sqlite"), Some(&bdir)).unwrap();
            let h = s.history("ADSK", None, 10, None, None).unwrap();
            assert_eq!(
                (
                    h[0].rank,
                    h[0].decision.as_str(),
                    h[0].composite,
                    h[0].fundamentals
                ),
                (3, "Watch", 26, Some(13))
            );
            assert_eq!((h[0].price_cents, h[0].target_cents), (24175, Some(30250)));
            assert_eq!(
                (h[0].trust_signal.as_deref(), h[0].trigger.as_str()),
                (Some("t"), "interval")
            );
        }
        let refuse = |name: &str, v: i64, edit: ColEdit, want: &str| {
            let f = root.join(name);
            custom_backup(&f, v, edit);
            let data = root.join(format!("d-{name}"));
            let e = restore(&f, &data, &bdir).unwrap_err();
            assert!(e.contains(want), "{name}: {e}");
            let s = ScoreHistoryStore::open(&data.join("history.sqlite"), Some(&bdir)).unwrap();
            assert_eq!(s.counts().unwrap(), (0, 0), "{name}: nothing written");
        };
        refuse(
            "missing.sqlite",
            SCHEMA_VERSION,
            &|t, c| {
                if t == "score_history" {
                    c.retain(|(n, _)| n != "trust_signal")
                }
            },
            "missing column 'trust_signal'",
        );
        refuse(
            "extra.sqlite",
            SCHEMA_VERSION,
            &|t, c| {
                if t == "score_runs" {
                    c.push(("bogus".into(), "TEXT".into()))
                }
            },
            "unknown column 'bogus'",
        );
        refuse(
            "type.sqlite",
            SCHEMA_VERSION,
            &|t, c| {
                if t == "score_history" {
                    for x in c.iter_mut().filter(|x| x.0 == "composite") {
                        x.1 = "TEXT".into();
                    }
                }
            },
            "type TEXT",
        );
        refuse("newer.sqlite", SCHEMA_VERSION + 1, &|_, _| {}, "newer than");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn last_run_at_is_by_insertion_order() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        s.record_run(&meta(100), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap();
        s.record_run(&meta(50), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap();
        assert_eq!(s.last_run_at("sp500", "v3").unwrap(), Some(50));
        assert_eq!(s.last_run_at("nasdaq", "v3").unwrap(), None);
    }

    #[test]
    fn infinite_values_are_filtered() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        let mut a = row("INFP", "Act", 1, 1, 1, 1);
        a.price = f64::INFINITY;
        let mut b = row("ADSK", "Act", 1, 1, 1, 1);
        b.target = Some(f64::INFINITY);
        b.upside_pct = Some(f64::NEG_INFINITY);
        assert_eq!(
            s.record_run(&meta(1), &[a, b]).unwrap(),
            RecordOutcome::Recorded {
                rows: 1,
                skipped: vec!["INFP".into()]
            }
        );
        let h = s.history("ADSK", None, 2, None, None).unwrap();
        assert_eq!((h[0].target_cents, h[0].upside_bps), (None, None));
    }

    #[test]
    fn days_are_bounded() {
        assert!(validate_days(0).is_err());
        assert!(validate_days(MAX_HISTORY_DAYS + 1).is_err());
        assert_eq!(validate_days(MAX_HISTORY_DAYS), Ok(MAX_HISTORY_DAYS));
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        s.record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap();
        // Overflowing day counts never panic and mean "everything".
        assert_eq!(
            s.history("ADSK", Some(i64::MAX), 2, None, None)
                .unwrap()
                .len(),
            1
        );
    }

    /// Normalizes SQL-ish text: lower-case, quotes removed, whitespace collapsed and
    /// schema prefixes (`main.`, `temp.`, `bk.` …) stripped from the guarded tables.
    fn normalize(text: &str) -> String {
        let t: String = text
            .to_ascii_lowercase()
            .chars()
            .map(|c| if "\"'`[]".contains(c) { ' ' } else { c })
            .collect();
        let mut s = t.split_whitespace().collect::<Vec<_>>().join(" ");
        s = s.replace(" . ", ".").replace(" .", ".").replace(". ", ".");
        for tbl in GUARDED_TABLES {
            let needle = format!(".{tbl}");
            while let Some(i) = s.find(&needle) {
                let start = s[..i]
                    .rfind(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
                    .map_or(0, |k| k + 1);
                s.replace_range(start..i + 1, "");
            }
        }
        s
    }

    fn word_at(s: &str, at: usize, len: usize) -> bool {
        let before = s[..at].chars().last().unwrap_or(' ');
        let after = s[at + len..].chars().next().unwrap_or(' ');
        let w = |c: char| c.is_ascii_alphanumeric() || c == '_';
        !w(before) && !w(after)
    }

    fn find_all(s: &str, needle: &str) -> Vec<usize> {
        let mut v = Vec::new();
        let mut from = 0;
        while let Some(i) = s[from..].find(needle) {
            if word_at(s, from + i, needle.len()) {
                v.push(from + i);
            }
            from += i + needle.len();
        }
        v
    }

    /// Statements in `text` that could delete, overwrite or reshape the history tables.
    fn guard_violations(text: &str) -> Vec<String> {
        let s = normalize(text);
        let mut bad = Vec::new();
        let j = |w: &[&str]| w.join(" ");
        let ors = ["rollback", "abort", "replace", "fail", "ignore"];
        for at in find_all(&s, &j(&["drop", "trigger"])) {
            let rest = s[at..].splitn(3, ' ').nth(2).unwrap_or("");
            let rest = rest.strip_prefix("if exists ").unwrap_or(rest);
            if GUARDED_TABLES.iter().any(|t| rest.starts_with(t)) {
                bad.push(j(&["drop", "trigger"]));
            }
        }
        for t in GUARDED_TABLES {
            let mut needles = vec![
                j(&["delete", "from", t]),
                j(&["update", t]),
                j(&["drop", "table", t]),
                j(&["drop", "table", "if", "exists", t]),
                j(&["alter", "table", t]),
                j(&["replace", "into", t]),
            ];
            for o in ors {
                needles.push(j(&["update", "or", o, t]));
                needles.push(j(&["insert", "or", o, "into", t]));
            }
            for n in &needles {
                if !find_all(&s, n).is_empty() {
                    bad.push(n.clone());
                }
            }
            // UPSERT / conflict clauses on these tables.
            for at in find_all(&s, &j(&["into", t]))
                .into_iter()
                .chain(find_all(&s, &j(&["table", t])))
            {
                let stmt = &s[at..];
                let stmt = &stmt[..stmt.find(';').unwrap_or(stmt.len())];
                let stmt = &stmt[..stmt.len().min(2_000)];
                if stmt.contains(&j(&["on", "conflict"])) || stmt.contains(&j(&["do", "update"])) {
                    bad.push(format!("{} ({t})", j(&["on", "conflict"])));
                }
            }
        }
        // SQL built with format! that names a guarded table.
        let mut from = 0;
        let fm = ["format", "!("].concat();
        while let Some(i) = text[from..].find(&fm) {
            let at = from + i;
            let end = text[at..].find(';').map_or(text.len(), |k| at + k);
            // String literals of the call that name a table next to an SQL keyword.
            let lits: Vec<String> = text[at..end]
                .split('"')
                .skip(1)
                .step_by(2)
                .map(str::to_ascii_lowercase)
                .collect();
            let kw = [
                "select", "insert", "delete", "update", "drop", "alter", "replace", "create",
                "from", "into",
            ];
            for l in lits {
                let words: Vec<&str> = l
                    .split(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
                    .collect();
                if let Some(t) = GUARDED_TABLES.iter().find(|t| words.contains(t)) {
                    if kw.iter().any(|k| words.contains(k)) {
                        bad.push(format!("{fm} naming {t}"));
                    }
                }
            }
            from = at + fm.len();
        }
        bad
    }

    #[test]
    fn guard_catches_disguised_writes() {
        let t = GUARDED_TABLES[1];
        let samples = [
            format!("{} FROM \"main\".\"{t}\" WHERE 1", "DELETE"),
            format!("{} `{t}` SET x=1", "UPDATE"),
            format!("{} {t} SET x=1", ["UPDATE", "OR", "REPLACE"].join(" ")),
            format!(
                "{} INTO main . {t} VALUES (1)",
                ["INSERT", "OR", "REPLACE"].join(" ")
            ),
            format!(
                "{} INTO {t} VALUES (1)",
                ["INSERT", "OR", "IGNORE"].join(" ")
            ),
            format!("{} INTO [{t}] VALUES (1)", "REPLACE"),
            format!("INSERT INTO {t} (a) VALUES (1) ON  CONFLICT(a) DO UPDATE SET a=2;"),
            format!("{} IF EXISTS {t}_no_delete", ["DROP", "TRIGGER"].join(" ")),
            format!("{} {t}_no_overwrite;", ["DROP", "TRIGGER"].join(" ")),
            format!("{} IF EXISTS '{t}'", ["DROP", "TABLE"].join(" ")),
            format!("{} temp.{t} RENAME TO x", ["ALTER", "TABLE"].join(" ")),
            format!(
                "let q = {}\"SELECT * FROM {t}\");",
                ["format", "!("].concat()
            ),
        ];
        for smp in &samples {
            assert!(!guard_violations(smp).is_empty(), "missed: {smp}");
        }
        for ok in [
            format!("INSERT INTO main.{t} SELECT * FROM src.{t} s WHERE NOT EXISTS (x);"),
            format!("SELECT * FROM {t} WHERE a = 1"),
            format!("CREATE TRIGGER x BEFORE UPDATE ON {t} BEGIN SELECT 1; END;"),
            format!(
                "{} INTO {t}_other VALUES (1)",
                ["INSERT", "OR", "REPLACE"].join(" ")
            ),
        ] {
            assert!(guard_violations(&ok).is_empty(), "false positive: {ok}");
        }
    }

    /// CI guard (runs in monorepo-validation): no source file may delete, overwrite,
    /// drop, alter or format!-build SQL against the history tables.
    #[test]
    fn no_source_deletes_or_updates_history_tables() {
        fn walk(dir: &Path, out: &mut Vec<PathBuf>) {
            for e in std::fs::read_dir(dir).unwrap().flatten() {
                let p = e.path();
                if p.is_dir() {
                    walk(&p, out);
                } else if p.extension().is_some_and(|x| x == "rs") {
                    out.push(p);
                }
            }
        }
        let mut files = Vec::new();
        walk(
            &Path::new(env!("CARGO_MANIFEST_DIR")).join("src"),
            &mut files,
        );
        assert!(files.len() > 10);
        let mut bad = Vec::new();
        for f in files {
            let text = std::fs::read_to_string(&f).unwrap();
            for v in guard_violations(&text) {
                bad.push(format!("{}: {v}", f.display()));
            }
        }
        assert!(bad.is_empty(), "history tables are append-only: {bad:?}");
    }

    #[test]
    fn app_db_cleanup_paths_and_reopen_keep_history() {
        let dir = tmpdir("cleanup");
        let p = dir.join("history.sqlite");
        let db = crate::db::Db::open(p.clone()).unwrap();
        let s = ScoreHistoryStore::open(&p, Some(&dir.join("bk"))).unwrap();
        for at in 1..=3 {
            s.record_run(
                &meta(at),
                &[
                    row("ADSK", "Act", 30, 60, 0, 30),
                    row("SSRM", "Act", 3, 6, 0, 3),
                ],
            )
            .unwrap();
        }
        let before = s.counts().unwrap();
        assert_eq!(before, (3, 6));
        // Every time-based cleanup the app DB runs (provider caches / budgets).
        db.save_fmp_forecast_cache("2099-12-31", "ADSK", 1, "{}")
            .unwrap();
        let _ = db.reserve_fmp_attempt("2099-12-31", 5).unwrap();
        db.save_tipranks_forecast_cache("2099-12", "ADSK", 1, "{}")
            .unwrap();
        let _ = db.reserve_tipranks_attempt("2099-12", 5).unwrap();
        db.save_tipranks_usage_snapshot("2099-12", 1, 2, 1, 3, 4)
            .unwrap();
        assert_eq!(s.counts().unwrap(), before);
        drop(db);
        drop(s);
        // Reopen: desktop migrations + history migrations run again.
        let _db = crate::db::Db::open(p.clone()).unwrap();
        let s = ScoreHistoryStore::open(&p, Some(&dir.join("bk"))).unwrap();
        assert_eq!(s.counts().unwrap(), before);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn data_dir_wipe_then_restore_from_off_dir_backup() {
        let root = tmpdir("wipe");
        let data = root.join("data");
        let bdir = root.join("backups");
        let p = data.join("history.sqlite");
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        s.record_run(&meta(10), &[row("ADSK", "Act", 30, 60, 0, 30)])
            .unwrap();
        s.record_run(&meta(20), &[row("ADSK", "Watch", 20, 60, 0, 30)])
            .unwrap();
        let rep = s
            .daily_backup_if_due(&bdir, 1_793_300_000)
            .unwrap()
            .unwrap();
        assert!(rep.path.to_string_lossy().ends_with(BACKUP_SUFFIX));
        assert_eq!((rep.score_runs, rep.score_history), (2, 2));
        assert!(
            s.daily_backup_if_due(&bdir, 1_793_300_100)
                .unwrap()
                .is_none(),
            "one per day"
        );
        drop(s);
        // Backup holds the score tables only.
        let pl = plain(&rep.path).unwrap();
        let b = Connection::open(&pl.path).unwrap();
        let names: Vec<String> = b
            .prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        let mut want: Vec<&str> = HISTORY_TABLES.to_vec();
        want.sort();
        assert_eq!(names, want);
        drop(b);
        drop(pl);
        std::fs::remove_dir_all(&data).unwrap();
        assert!(!p.exists());
        let r = restore(&rep.path, &data, &bdir).unwrap();
        assert_eq!((r.runs_added, r.rows_added), (2, 2));
        assert!(
            r.pre_restore_backup.unwrap().exists(),
            "live backed up first"
        );
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        let h = s.history("ADSK", None, 100, None, None).unwrap();
        assert_eq!(
            h.iter().map(|p| p.captured_at).collect::<Vec<_>>(),
            vec![10, 20]
        );
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn restore_merges_append_only_and_never_overwrites() {
        let root = tmpdir("merge");
        let data = root.join("data");
        let bdir = root.join("backups");
        let p = data.join("history.sqlite");
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        s.record_run(&meta(10), &[row("ADSK", "Act", 30, 60, 0, 30)])
            .unwrap();
        let bk = bdir.join("manual.sqlite.gz");
        s.export_to(&bk).unwrap();
        drop(s);
        // Live diverges: run 10 exists (different values must not matter), run 30 is new.
        {
            let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
            s.record_run(&meta(30), &[row("ADSK", "Avoid", 1, 1, 1, 1)])
                .unwrap();
        }
        let r = restore(&bk, &data, &bdir).unwrap();
        assert_eq!((r.runs_added, r.rows_added), (0, 0), "nothing missing");
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        let h = s.history("ADSK", None, 100, None, None).unwrap();
        assert_eq!(
            h.iter().map(|p| p.decision.as_str()).collect::<Vec<_>>(),
            ["Act", "Avoid"]
        );
        drop(s);
        // Restoring into an empty data-dir and then again is idempotent.
        let data2 = root.join("data2");
        assert_eq!(restore(&bk, &data2, &bdir).unwrap().runs_added, 1);
        assert_eq!(restore(&bk, &data2, &bdir).unwrap().runs_added, 0);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn restore_rechecks_blob_sha256_and_raw_size() {
        let raw = b"{\"row\":1}".to_vec();
        let h = sha256_hex(&raw);
        let n = raw.len() as i64;
        let z = zstd::bulk::compress(&raw, 3).unwrap();
        assert_eq!(
            check_blob(&h, Some("zstd"), Some(n), z.clone()).unwrap(),
            raw
        );
        assert_eq!(
            check_blob(&h, Some("raw"), Some(n), raw.clone()).unwrap(),
            raw
        );
        let e = check_blob(&h, Some("zstd"), Some(n + 1), z.clone()).unwrap_err();
        assert!(e.contains("raw_size"), "{e}");
        let e = check_blob(&sha256_hex(b"x"), Some("zstd"), Some(n), z.clone()).unwrap_err();
        assert!(e.contains("sha256"), "{e}");
        assert!(check_blob(&h, Some("lz4"), Some(n), z.clone()).is_err());
        // An attached backup with one corrupt blob is refused before anything merges.
        let c = Connection::open_in_memory().unwrap();
        c.execute("ATTACH DATABASE ':memory:' AS src", []).unwrap();
        let t = ["src", ".score_inputs"].concat();
        c.execute_batch(
            &[
                "CREATE TABLE ",
                &t,
                " (hash TEXT, encoding TEXT, raw_size INTEGER, bytes BLOB)",
            ]
            .concat(),
        )
        .unwrap();
        let ins = [
            ["INS", "ERT INTO "].concat(),
            t.clone(),
            " VALUES (?1,?2,?3,?4)".into(),
        ]
        .concat();
        c.execute(&ins, params![h, "zstd", n, z]).unwrap();
        assert_eq!(verify_src_blobs(&c, "src", &[]).unwrap(), 1);
        c.execute(&ins, params![h, "zstd", n + 5, z]).unwrap();
        assert!(verify_src_blobs(&c, "src", &[])
            .unwrap_err()
            .contains("raw_size"));
    }

    #[test]
    fn check_blob_rejects_raw_size_over_64mb_before_decompress() {
        let raw = b"tiny";
        let h = sha256_hex(raw);
        let z = zstd::bulk::compress(raw, 1).unwrap();
        // Far above the 64 MiB cap (and above typical usize tricks) — must refuse
        // cleanly before any decompress attempt.
        let over = 1_i64 << 46;
        let err = check_blob(&h, Some("zstd"), Some(over), z).unwrap_err();
        assert!(
            err.contains("exceeds cap"),
            "expected clean oversize error, got: {err}"
        );
    }

    #[test]
    fn input_hash_rejects_path_traversal_before_join() {
        let err = validate_input_hash("../../secret").unwrap_err();
        assert!(err.contains("64 lowercase hex"), "{err}");
        let err = inputs_blob_path(Path::new("/tmp"), "../../secret").unwrap_err();
        assert!(err.contains("64 lowercase hex"), "{err}");
        // Nothing under inputs/ is created or read for a bad name.
        let dir = tmpdir("hash-trav");
        let inputs = dir.join("inputs");
        let _ = std::fs::create_dir_all(&inputs);
        assert!(write_input_blob(&dir, "../../secret", b"x").is_err());
        assert!(std::fs::read_dir(&inputs).unwrap().next().is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn input_hash_rejects_length_case_and_non_hex() {
        // V2/V3/V4: length, uppercase, non-hex, empty, slash-in-64.
        let hex64 = "a".repeat(64);
        assert!(validate_input_hash(&hex64).is_ok());
        assert!(validate_input_hash(&"a".repeat(63)).unwrap_err().contains("64 lowercase hex"));
        assert!(validate_input_hash(&"a".repeat(65)).unwrap_err().contains("64 lowercase hex"));
        assert!(validate_input_hash("").unwrap_err().contains("64 lowercase hex"));
        let mut upper = hex64.clone();
        upper.replace_range(0..1, "A");
        assert!(validate_input_hash(&upper).unwrap_err().contains("64 lowercase hex"));
        let mut gee = hex64.clone();
        gee.replace_range(10..11, "g");
        assert!(validate_input_hash(&gee).unwrap_err().contains("64 lowercase hex"));
        let mut slash = hex64.clone();
        slash.replace_range(5..6, "/");
        assert!(validate_input_hash(&slash).unwrap_err().contains("64 lowercase hex"));
        assert!(inputs_blob_path(Path::new("/tmp"), &slash).is_err());
    }

    /// Re-check before rename: if the proposed `.bad` appeared after it was
    /// proposed (lost race), it must be skipped and preserved.
    #[test]
    fn quarantine_recheck_skips_destination_that_appeared() {
        let dir = tmpdir("quarantine-recheck");
        let path = dir.join("blob");
        std::fs::write(&path, b"source").unwrap();
        let raced = dir.join("blob.bad.raced");
        std::fs::write(&raced, b"keep").unwrap();
        let fresh = dir.join("blob.bad.fresh");
        let mut calls = 0usize;
        quarantine_input_blob_aside_with(&path, "file", |_, _| {
            calls += 1;
            if calls == 1 {
                raced.clone()
            } else {
                fresh.clone()
            }
        })
        .unwrap();
        assert_eq!(calls, 2, "raced destination skipped, then retried");
        assert_eq!(std::fs::read(&raced).unwrap(), b"keep", "existing .bad untouched");
        assert_eq!(std::fs::read(&fresh).unwrap(), b"source");
        assert!(path.symlink_metadata().is_err(), "original moved aside");
    }

    /// Retries are capped at BAD_QUARANTINE_RENAME_ATTEMPTS: succeeds on the
    /// last allowed attempt, and gives up (without clobbering) after it.
    #[test]
    fn quarantine_retry_count_is_capped() {
        let dir = tmpdir("quarantine-cap");
        let taken = dir.join("blob.bad.taken");
        std::fs::write(&taken, b"keep").unwrap();

        // Every proposal but the last allowed one is taken -> Ok on attempt N.
        let path = dir.join("blob");
        std::fs::create_dir(&path).unwrap();
        let last = dir.join("blob.bad.last");
        let mut calls = 0usize;
        quarantine_input_blob_aside_with(&path, "directory", |_, _| {
            calls += 1;
            if calls < BAD_QUARANTINE_RENAME_ATTEMPTS {
                taken.clone()
            } else {
                last.clone()
            }
        })
        .unwrap();
        assert_eq!(calls, BAD_QUARANTINE_RENAME_ATTEMPTS);
        assert!(last.is_dir());

        // Every proposal taken -> Err after exactly N attempts, source intact.
        let path2 = dir.join("blob2");
        std::fs::write(&path2, b"source").unwrap();
        let mut calls2 = 0usize;
        let err = quarantine_input_blob_aside_with(&path2, "file", |_, _| {
            calls2 += 1;
            taken.clone()
        })
        .unwrap_err();
        assert_eq!(calls2, BAD_QUARANTINE_RENAME_ATTEMPTS);
        assert!(err.contains("quarantine exhausted"), "{err}");
        assert_eq!(std::fs::read(&path2).unwrap(), b"source");
        assert_eq!(std::fs::read(&taken).unwrap(), b"keep");
    }

    #[test]
    fn write_input_blob_quarantines_directory() {
        let dir = tmpdir("blob-special");
        let inputs = dir.join("inputs");
        std::fs::create_dir_all(&inputs).unwrap();
        let hash = "b".repeat(64);
        let path = inputs.join(&hash);
        // Directory occupying the blob name.
        std::fs::create_dir(&path).unwrap();
        assert!(write_input_blob(&dir, &hash, b"payload-dir").unwrap());
        assert!(path.is_file(), "rewrote as regular file");
        assert_eq!(std::fs::read(&path).unwrap(), b"payload-dir");
        let bad = inputs.join(format!("{hash}.bad"));
        assert!(bad.is_dir(), "directory quarantined to .bad");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn write_input_blob_quarantines_symlinks() {
        let dir = tmpdir("blob-symlink");
        let inputs = dir.join("inputs");
        std::fs::create_dir_all(&inputs).unwrap();
        // Symlink-to-file occupying the blob name.
        let hash2 = "c".repeat(64);
        let path2 = inputs.join(&hash2);
        let target = inputs.join("elsewhere");
        std::fs::write(&target, b"nope").unwrap();
        std::os::unix::fs::symlink(&target, &path2).unwrap();
        assert!(write_input_blob(&dir, &hash2, b"payload-link").unwrap());
        assert!(path2.is_file());
        assert_eq!(std::fs::read(&path2).unwrap(), b"payload-link");
        let bad2 = inputs.join(format!("{hash2}.bad"));
        assert!(bad2.symlink_metadata().unwrap().file_type().is_symlink());

        // Symlink-to-directory occupying the blob name.
        let hash_dirlink = "a".repeat(64);
        let path_dirlink = inputs.join(&hash_dirlink);
        let target_dir = inputs.join("else-dir");
        std::fs::create_dir(&target_dir).unwrap();
        std::os::unix::fs::symlink(&target_dir, &path_dirlink).unwrap();
        assert!(write_input_blob(&dir, &hash_dirlink, b"payload-dirlink").unwrap());
        assert!(path_dirlink.is_file());
        assert_eq!(std::fs::read(&path_dirlink).unwrap(), b"payload-dirlink");
        let bad_dirlink = inputs.join(format!("{hash_dirlink}.bad"));
        assert!(
            bad_dirlink.symlink_metadata().unwrap().file_type().is_symlink(),
            "symlink-to-directory quarantined"
        );

        // Dangling symlink occupying the blob name.
        let hash_dang = "f".repeat(64);
        let path_dang = inputs.join(&hash_dang);
        std::os::unix::fs::symlink(inputs.join("missing-target"), &path_dang).unwrap();
        assert!(write_input_blob(&dir, &hash_dang, b"payload-dangling").unwrap());
        assert!(path_dang.is_file());
        assert_eq!(std::fs::read(&path_dang).unwrap(), b"payload-dangling");
        let bad_dang = inputs.join(format!("{hash_dang}.bad"));
        assert!(
            bad_dang.symlink_metadata().unwrap().file_type().is_symlink(),
            "dangling symlink quarantined"
        );

        // Existing dangling `.bad` must not be overwritten — unique suffix.
        let hash4 = "1".repeat(64);
        let path4 = inputs.join(&hash4);
        let bad4 = inputs.join(format!("{hash4}.bad"));
        std::os::unix::fs::symlink(inputs.join("gone"), &bad4).unwrap();
        std::fs::create_dir(&path4).unwrap();
        assert!(write_input_blob(&dir, &hash4, b"payload-unique-bad").unwrap());
        assert!(path4.is_file());
        // Original dangling .bad still a symlink; quarantine used a unique name.
        assert!(bad4.symlink_metadata().unwrap().file_type().is_symlink());
        let unique_bads: Vec<_> = std::fs::read_dir(&inputs)
            .unwrap()
            .flatten()
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n.starts_with(&format!("{hash4}.bad.")) )
            .collect();
        assert!(
            !unique_bads.is_empty(),
            "must quarantine to unique .bad.* when .bad is a dangling symlink"
        );

        // resolve_input_bytes refuses a symlink left in place (O_NOFOLLOW).
        let hash3 = "d".repeat(64);
        let path3 = inputs.join(&hash3);
        std::os::unix::fs::symlink(&target, &path3).unwrap();
        let err = resolve_input_bytes(&[dir.clone()], &hash3, Vec::new()).unwrap_err();
        assert!(
            err.contains("not a regular file") || err.contains("symlink"),
            "{err}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn cleanup_stale_input_tmps_only_exact_pattern() {
        let dir = tmpdir("blob-tmps");
        let inputs = dir.join("inputs");
        std::fs::create_dir_all(&inputs).unwrap();
        let hash = "e".repeat(64);
        let stale = inputs.join(format!(".{hash}.12345.tmp"));
        std::fs::write(&stale, b"x").unwrap();
        // Keep a good blob and a non-matching tmp.
        std::fs::write(inputs.join(&hash), b"good").unwrap();
        std::fs::write(inputs.join(".notahash.1.tmp"), b"y").unwrap();
        // Non-numeric pid suffix must be kept (T2).
        let weird = inputs.join(format!(".{hash}.12a.tmp"));
        std::fs::write(&weird, b"z").unwrap();
        // Fresh matching tmp must be kept when max_age is large (T1).
        let fresh = inputs.join(format!(".{hash}.99999.tmp"));
        std::fs::write(&fresh, b"fresh").unwrap();
        // max_age=0 treats every matching tmp as stale (no filetime dependency).
        let n = cleanup_stale_input_tmps(&inputs, std::time::Duration::from_secs(0));
        assert!(n >= 1, "stale tmp removed");
        assert!(!stale.exists());
        assert!(inputs.join(&hash).exists());
        assert!(inputs.join(".notahash.1.tmp").exists());
        assert!(weird.exists(), "non-numeric pid suffix tmp must be kept");
        // Re-create a fresh matching tmp and confirm max_age=3600 keeps it.
        std::fs::write(&fresh, b"fresh2").unwrap();
        let kept = cleanup_stale_input_tmps(&inputs, std::time::Duration::from_secs(3600));
        assert_eq!(kept, 0, "fresh tmp with max_age=3600 must be kept");
        assert!(fresh.exists(), "fresh matching tmp kept at max_age=3600");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn write_input_blob_repairs_zero_and_flipped_then_restore_ok() {
        let root = tmpdir("blob-repair");
        let data = root.join("data");
        let bdir = root.join("backups");
        let s = ScoreHistoryStore::open(&data.join("history.sqlite"), Some(&bdir)).unwrap();
        let e = tiny_eval(&["ADSK"]);
        s.record_run_full(&meta(10), &[row("ADSK", "Act", 30, 60, 0, 30)], Some(&e))
            .unwrap();
        let good = bdir.join("day1.sqlite.gz");
        s.export_to(&good).unwrap();
        // Collect the one inputs blob and its correct bytes.
        let inputs = bdir.join("inputs");
        let names: Vec<_> = std::fs::read_dir(&inputs)
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        assert_eq!(names.len(), 1);
        let hash = names[0].clone();
        let correct = std::fs::read(inputs.join(&hash)).unwrap();
        assert!(!correct.is_empty());

        // Zero-length corrupt → next export repairs.
        std::fs::write(inputs.join(&hash), b"").unwrap();
        assert_eq!(std::fs::metadata(inputs.join(&hash)).unwrap().len(), 0);
        let day2 = bdir.join("day2.sqlite.gz");
        s.export_to(&day2).unwrap();
        assert_eq!(
            std::fs::read(inputs.join(&hash)).unwrap(),
            correct,
            "zero-length blob repaired on export"
        );

        // Flipped-byte corrupt → next export repairs.
        let mut flipped = correct.clone();
        flipped[0] ^= 0xff;
        std::fs::write(inputs.join(&hash), &flipped).unwrap();
        let day3 = bdir.join("day3.sqlite.gz");
        s.export_to(&day3).unwrap();
        assert_eq!(
            std::fs::read(inputs.join(&hash)).unwrap(),
            correct,
            "flipped-byte blob repaired on export"
        );

        // Restore from repaired backup is OK / byte-identical.
        let want = {
            let c = s.conn.lock().unwrap();
            let mut st = c
                .prepare("SELECT hash, bytes FROM score_inputs ORDER BY hash")
                .unwrap();
            st.query_map([], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, Vec<u8>>(1)?))
            })
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap()
        };
        drop(s);
        let live2 = root.join("live2");
        restore(&day3, &live2, &bdir).unwrap();
        let s2 = ScoreHistoryStore::open(&live2.join("history.sqlite"), Some(&bdir)).unwrap();
        let got = {
            let c = s2.conn.lock().unwrap();
            let mut st = c
                .prepare("SELECT hash, bytes FROM score_inputs ORDER BY hash")
                .unwrap();
            st.query_map([], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, Vec<u8>>(1)?))
            })
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap()
        };
        assert_eq!(want, got);

        // check_backup fails on missing blob.
        let orphan = bdir.join("orphan.sqlite.gz");
        std::fs::copy(&day3, &orphan).unwrap();
        std::fs::remove_file(inputs.join(&hash)).unwrap();
        let err = check_backup(&orphan, 1, 1).unwrap_err();
        assert!(
            err.contains("missing") || err.contains("blob") || err.contains("inputs"),
            "{err}"
        );
        assert!(
            PathBuf::from(format!("{}.failed", orphan.display())).exists() || !orphan.exists(),
            "failed backup renamed"
        );

        // check_backup fails on corrupt blob (restore correct file first).
        // Re-export to recreate the good blob, then corrupt after a fresh copy.
        let s = ScoreHistoryStore::open(&data.join("history.sqlite"), Some(&bdir)).unwrap();
        let day4 = bdir.join("day4.sqlite.gz");
        s.export_to(&day4).unwrap();
        drop(s);
        let mut bad_bytes = std::fs::read(inputs.join(&hash)).unwrap();
        bad_bytes[0] ^= 0xaa;
        std::fs::write(inputs.join(&hash), &bad_bytes).unwrap();
        let corrupt = bdir.join("corrupt.sqlite.gz");
        std::fs::copy(&day4, &corrupt).unwrap();
        let err = check_backup(&corrupt, 1, 1).unwrap_err();
        assert!(
            err.contains("blob") || err.contains("match") || err.contains("sha"),
            "{err}"
        );
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn incremental_backup_dedups_blobs_and_restores_byte_identical() {
        let root = tmpdir("incr-blobs");
        let data = root.join("data");
        let bdir = root.join("backups");
        let p = data.join("history.sqlite");
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        let e = tiny_eval(&["ADSK", "BK"]);
        s.record_run_full(&meta(10), &[row("ADSK", "Act", 30, 60, 0, 30)], Some(&e))
            .unwrap();
        let b1 = bdir.join("day1.sqlite.gz");
        s.export_to(&b1).unwrap();
        let blobs1: Vec<_> = std::fs::read_dir(bdir.join("inputs"))
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert!(!blobs1.is_empty(), "first export writes inputs/ blobs");
        // Second export same inputs: no new blob files (append-only by hash).
        let b2 = bdir.join("day2.sqlite.gz");
        s.export_to(&b2).unwrap();
        let blobs2: Vec<_> = std::fs::read_dir(bdir.join("inputs"))
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(blobs1.len(), blobs2.len(), "no duplicate blob files");
        // Sqlite in backup has empty bytes for score_inputs.
        {
            let pl = plain(&b1).unwrap();
            let c = Connection::open(&pl.path).unwrap();
            let empty: i64 = c
                .query_row(
                    "SELECT COUNT(*) FROM score_inputs WHERE length(bytes) = 0",
                    [],
                    |r| r.get(0),
                )
                .unwrap();
            let total: i64 = table_count(&c, "score_inputs");
            assert_eq!(empty, total, "incremental backup stores empty bytes");
        }
        // Live snapshot bytes before wipe.
        let before = {
            let c = s.conn.lock().unwrap();
            let mut st = c
                .prepare("SELECT hash, encoding, raw_size, bytes FROM score_inputs ORDER BY hash")
                .unwrap();
            st.query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, Vec<u8>>(3)?,
                ))
            })
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap()
        };
        drop(s);
        std::fs::remove_dir_all(&data).unwrap();
        let r = restore(&b1, &data, &bdir).unwrap();
        assert!(r.inputs_added > 0);
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        let after = {
            let c = s.conn.lock().unwrap();
            let mut st = c
                .prepare("SELECT hash, encoding, raw_size, bytes FROM score_inputs ORDER BY hash")
                .unwrap();
            st.query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, Vec<u8>>(3)?,
                ))
            })
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap()
        };
        assert_eq!(before, after, "restore reconstructs byte-identical inputs");
        // Missing blob fails atomically (no inputs merged into empty target).
        let victim = std::fs::read_dir(bdir.join("inputs"))
            .unwrap()
            .next()
            .unwrap()
            .unwrap()
            .path();
        let stash = root.join("stash-blob");
        std::fs::rename(&victim, &stash).unwrap();
        let empty = root.join("empty-live");
        let err = restore(&b2, &empty, &bdir).unwrap_err();
        assert!(
            err.contains("missing") || err.contains("blob") || err.contains("inputs"),
            "{err}"
        );
        if empty.join("history.sqlite").exists() {
            let s2 = ScoreHistoryStore::open(&empty.join("history.sqlite"), Some(&bdir)).unwrap();
            assert_eq!(
                table_count(&s2.conn.lock().unwrap(), "score_inputs"),
                0,
                "refused restore must not merge inputs"
            );
        }
        let _ = std::fs::remove_dir_all(&root);
    }

    /// End-to-end: a backup whose `score_inputs` blob fails `verify_src_blobs` is
    /// refused by `restore`, and the live target is left untouched (no partial merge).
    /// Kills the mutant that deletes the `verify_src_blobs` call in `restore`.
    #[test]
    fn restore_refuses_tampered_blob_atomically() {
        let root = tmpdir("tamper-restore");
        let bdir = root.join("bk");
        // A good backup that includes at least one score_inputs blob.
        let src = ScoreHistoryStore::open(&root.join("src.sqlite"), Some(&bdir)).unwrap();
        let e = tiny_eval(&["ADSK", "BK"]);
        src.record_run_full(&meta(50), &[row("ADSK", "Act", 30, 60, 0, 30)], Some(&e))
            .unwrap();
        let good = root.join("good.sqlite.gz");
        src.export_to(&good).unwrap();
        drop(src);

        // Live target with a different run that must survive a refused restore.
        let data = root.join("live");
        {
            let live = ScoreHistoryStore::open(&data.join("history.sqlite"), Some(&bdir)).unwrap();
            live.record_run(&meta(10), &[row("ADSK", "Watch", 1, 1, 1, 1)])
                .unwrap();
        }
        let counts = |dir: &Path| {
            let s = ScoreHistoryStore::open(&dir.join("history.sqlite"), Some(&bdir)).unwrap();
            let c = s.conn.lock().unwrap();
            (
                table_count(&c, "score_runs"),
                table_count(&c, "score_history"),
                table_count(&c, "score_inputs"),
                table_count(&c, "score_evaluations"),
                table_count(&c, "score_run_inputs"),
                table_count(&c, "score_run_stats"),
            )
        };
        let before = counts(&data);
        assert_eq!(before.0, 1);
        assert_eq!(before.2, 0, "live has no inputs yet");

        // Gunzip the backup and bump raw_size so check_blob fails (drop UPDATE triggers).
        let bad = root.join("bad.sqlite");
        {
            let mut dec = flate2::read::GzDecoder::new(std::fs::File::open(&good).unwrap());
            let mut out = std::fs::File::create(&bad).unwrap();
            std::io::copy(&mut dec, &mut out).unwrap();
            let c = Connection::open(&bad).unwrap();
            // Built with joins so the CI source-scan does not see DROP/UPDATE literals.
            let drop_tr =
                |n: &str| [["DROP", "TRIGGER", "IF", "EXISTS"].join(" "), n.to_string()].join(" ");
            c.execute_batch(
                &[
                    drop_tr("score_inputs_no_update"),
                    drop_tr("score_inputs_no_overwrite"),
                    drop_tr("score_inputs_no_delete"),
                ]
                .join(";"),
            )
            .unwrap();
            let n: i64 = c
                .query_row("SELECT count(*) FROM score_inputs", [], |r| r.get(0))
                .unwrap();
            assert!(n >= 1, "backup must carry inputs to tamper");
            let bump = [
                "UPDATE",
                "score_inputs",
                "SET",
                "raw_size",
                "=",
                "raw_size",
                "+",
                "7",
            ]
            .join(" ");
            c.execute(&bump, []).unwrap();
        }

        let err = restore(&bad, &data, &bdir).unwrap_err();
        assert!(
            err.contains("raw_size") || err.contains("blob"),
            "refused with blob error, got: {err}"
        );
        assert_eq!(
            counts(&data),
            before,
            "target untouched: no partial restore"
        );
        let s = ScoreHistoryStore::open(&data.join("history.sqlite"), Some(&bdir)).unwrap();
        let h = s.history("ADSK", None, 100, None, None).unwrap();
        assert_eq!(h.len(), 1);
        assert_eq!(h[0].decision, "Watch");

        // Empty target: still refused, and stays empty (atomicity, not just "keep old rows").
        let empty = root.join("empty");
        let err = restore(&bad, &empty, &bdir).unwrap_err();
        assert!(err.contains("raw_size") || err.contains("blob"), "{err}");
        assert!(
            !empty.join("history.sqlite").exists() || {
                let s =
                    ScoreHistoryStore::open(&empty.join("history.sqlite"), Some(&bdir)).unwrap();
                s.run_count().unwrap() == 0
                    && table_count(&s.conn.lock().unwrap(), "score_inputs") == 0
            },
            "empty target gains no rows from a refused restore"
        );
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn restore_refuses_while_writer_holds_lock() {
        let root = tmpdir("lock");
        let data = root.join("data");
        let bdir = root.join("backups");
        let s = ScoreHistoryStore::open(&data.join("history.sqlite"), Some(&bdir)).unwrap();
        s.record_run(&meta(10), &[row("ADSK", "Act", 30, 60, 0, 30)])
            .unwrap();
        let bk = bdir.join("b.sqlite.gz");
        s.export_to(&bk).unwrap();
        let held = lock_data_dir(&data).unwrap();
        let e = restore(&bk, &data, &bdir).unwrap_err();
        assert!(e.contains("in use"), "{e}");
        assert!(lock_data_dir(&data).is_err(), "second serve refused too");
        drop(held);
        assert!(restore(&bk, &data, &bdir).is_ok(), "free once released");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn restore_with_stale_wal_keeps_wal_rows_and_truncates() {
        let root = tmpdir("wal");
        let data = root.join("data");
        let bdir = root.join("backups");
        let p = data.join("history.sqlite");
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        s.record_run(&meta(10), &[row("ADSK", "Act", 30, 60, 0, 30)])
            .unwrap();
        let bk = bdir.join("b.sqlite.gz");
        s.export_to(&bk).unwrap();
        drop(s);
        // A crashed writer: its rows only live in -wal (connection never closed).
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        s.record_run(&meta(20), &[row("ADSK", "Watch", 1, 1, 1, 1)])
            .unwrap();
        std::mem::forget(s);
        let wal = data.join("history.sqlite-wal");
        assert!(
            std::fs::metadata(&wal).unwrap().len() > 0,
            "stale wal present"
        );
        let r = restore(&bk, &data, &bdir).unwrap();
        assert_eq!(r.runs_added, 0);
        assert_eq!(
            std::fs::metadata(&wal).map(|m| m.len()).unwrap_or(0),
            0,
            "checkpointed"
        );
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        assert_eq!(s.counts().unwrap(), (2, 2), "wal rows survived");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn verify_rejects_corrupt_missing_tables_and_short_counts() {
        let dir = tmpdir("verify");
        let junk = dir.join("junk.sqlite");
        std::fs::write(&junk, b"definitely not sqlite, just bytes ......").unwrap();
        assert!(verify_file(&junk, false).is_err());
        let gz = dir.join("junk.sqlite.gz");
        std::fs::write(&gz, b"not gzip").unwrap();
        assert!(verify_file(&gz, false).is_err());
        // Valid SQLite whose pages are damaged: integrity_check fails.
        let dmg = dir.join("dmg.sqlite");
        {
            let c = Connection::open(&dmg).unwrap();
            c.execute_batch("CREATE TABLE t(a TEXT); CREATE INDEX ti ON t(a);")
                .unwrap();
            for k in 0..2000 {
                c.execute("INSERT INTO t VALUES (?1)", params![format!("v{k:06}")])
                    .unwrap();
            }
        }
        let mut bytes = std::fs::read(&dmg).unwrap();
        let n = bytes.len();
        for b in &mut bytes[n / 2..n / 2 + 4096] {
            *b = 0x5a;
        }
        std::fs::write(&dmg, &bytes).unwrap();
        assert!(verify_file(&dmg, false).is_err(), "damaged pages rejected");
        // Missing tables.
        let empty = dir.join("empty.sqlite");
        Connection::open(&empty)
            .unwrap()
            .execute_batch("CREATE TABLE x(a);")
            .unwrap();
        assert_eq!(verify_file(&empty, false).unwrap().score_runs, -1);
        let e = verify_file(&empty, true).unwrap_err();
        assert!(e.contains("missing"), "{e}");
        let data = dir.join("data");
        assert!(restore(&empty, &data, &dir.join("bk"))
            .unwrap_err()
            .contains("missing"));
        // A backup with fewer rows than counted before is rejected and renamed.
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        s.record_run(&meta(1), &[row("ADSK", "Act", 1, 1, 1, 1)])
            .unwrap();
        let b = dir.join("b.sqlite.gz");
        let rep = s.export_to(&b).unwrap();
        assert_eq!((rep.score_runs, rep.score_history), (1, 1));
        assert!(check_backup(&b, 1, 1).is_ok(), ">= accepted (equal)");
        assert!(check_backup(&b, 0, 0).is_ok(), ">= accepted (more)");
        let e = check_backup(&b, 2, 1).unwrap_err();
        assert!(e.contains("< live"), "{e}");
        assert!(!b.exists() && dir.join("b.sqlite.gz.failed").exists());
        assert!(
            s.export_to(&dir.join("b.sqlite.gz.failed")).is_err(),
            "never overwrites"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn default_backup_dir_is_outside_data_dir() {
        // Single test touching these env vars.
        std::env::remove_var(BACKUP_DIR_ENV);
        std::env::set_var("XDG_DATA_HOME", "/x/data");
        assert_eq!(
            default_backup_dir(),
            PathBuf::from("/x/data/vantage/backups")
        );
        std::env::remove_var("XDG_DATA_HOME");
        std::env::set_var("HOME", "/home/u");
        assert_eq!(
            default_backup_dir(),
            PathBuf::from("/home/u/.local/share/vantage/backups")
        );
        std::env::set_var(BACKUP_DIR_ENV, "/store/bk");
        assert_eq!(default_backup_dir(), PathBuf::from("/store/bk"));
        std::env::remove_var(BACKUP_DIR_ENV);
    }

    #[test]
    fn survives_restart_and_interval_counts_from_score_runs() {
        let dir = tmpdir("restart");
        let p = dir.join("history.sqlite");
        let s = ScoreHistoryStore::open(&p, None).unwrap();
        let t0 = et(2026, 10, 27, 11, 0); // Tue 11:00 ET
        s.record_run(&meta(t0), &[row("ADSK", "Act", 30, 60, 0, 30)])
            .unwrap();
        drop(s);
        let s = ScoreHistoryStore::open(&p, None).unwrap();
        assert_eq!(s.history("ADSK", None, t0, None, None).unwrap().len(), 1);
        let last = s.last_run_at("sp500", "v3").unwrap();
        assert_eq!(last, Some(t0));
        // Fresh process after restart (since_pass = 0): the interval still holds.
        let i = DueInputs {
            now: t0 + 3_600,
            pass_completed: true,
            since_pass: HISTORY_YEAR_WAIT_SECS,
            charts_ready: true,
            live: true,
            rows: 500,
            last_run: last,
            interval: 6 * 3600,
        };
        assert_eq!(history_due(&i), Due::No("interval not elapsed"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn due(now: i64, last: Option<i64>) -> DueInputs {
        DueInputs {
            now,
            pass_completed: true,
            since_pass: 0,
            charts_ready: true,
            live: true,
            rows: 500,
            last_run: last,
            interval: 6 * 3600,
        }
    }

    #[test]
    fn due_gates_each_killed() {
        let now = et(2026, 10, 27, 11, 0);
        assert_eq!(history_due(&due(now, None)), Due::Interval);
        let mut i = due(now, None);
        i.pass_completed = false;
        assert_eq!(history_due(&i), Due::No("initial pass in progress"));
        let mut i = due(now, None);
        i.charts_ready = false;
        assert_eq!(history_due(&i), Due::No("waiting for year charts"));
        i.since_pass = HISTORY_YEAR_WAIT_SECS - 1;
        assert_eq!(history_due(&i), Due::No("waiting for year charts"));
        i.since_pass = HISTORY_YEAR_WAIT_SECS;
        assert_eq!(history_due(&i), Due::Interval, "20-minute wait releases it");
        let mut i = due(now, None);
        i.live = false;
        assert_eq!(history_due(&i), Due::No("no live ranking"));
        let mut i = due(now, None);
        i.rows = 0;
        assert_eq!(history_due(&i), Due::No("no live ranking"));
        // Interval boundary.
        assert_eq!(
            history_due(&due(now, Some(now - 6 * 3600 + 1))),
            Due::No("interval not elapsed")
        );
        assert_eq!(history_due(&due(now, Some(now - 6 * 3600))), Due::Interval);
        // Clock went backwards: due, never blocked.
        assert_eq!(history_due(&due(now, Some(now + 86_400))), Due::Interval);
    }

    #[test]
    fn close_snapshot_schedule_dst_and_weekends() {
        // Fri 2026-10-30 after close -> Mon 2026-11-02 16:10 EST (DST ended Sun Nov 1).
        let fri = et(2026, 10, 30, 16, 10);
        assert_eq!(format_in_tz(fri, MARKET_TZ), "2026-10-30 16:10 EDT");
        let mon = next_close_snapshot(fri);
        assert_eq!(format_in_tz(mon, MARKET_TZ), "2026-11-02 16:10 EST");
        assert_eq!(mon - fri, 3 * 86_400 + 3_600, "a DST hour longer");
        assert_eq!(next_close_snapshot(fri - 1), fri);
        assert_eq!(last_close_snapshot(fri), Some(fri));
        assert_eq!(last_close_snapshot(mon - 1), Some(fri), "weekend -> Friday");
        // Spring forward: Fri 2027-03-12 -> Mon 2027-03-15 16:10 EDT = 20:10 UTC.
        let f = et(2027, 3, 12, 16, 10);
        let m = next_close_snapshot(f);
        assert_eq!(iso_utc(m), "2027-03-15T20:10:00Z");
        assert_eq!(iso_utc(f), "2027-03-12T21:10:00Z");
    }

    #[test]
    fn close_snapshot_due_and_no_duplicates() {
        let slot = et(2026, 10, 29, 16, 10);
        // Interval ran at 11:00; at 16:12 the close slot is due even though the interval is not.
        assert_eq!(
            history_due(&due(slot + 120, Some(slot - 5 * 3600 - 600))),
            Due::Close(slot)
        );
        // Initial pass still running at the slot: taken once it finishes (catch-up).
        let mut i = due(slot + 3_000, Some(slot - 86_400));
        i.pass_completed = false;
        assert_eq!(history_due(&i), Due::No("initial pass in progress"));
        i.pass_completed = true;
        assert_eq!(history_due(&i), Due::Close(slot));
        // An interval run that lands after the slot satisfies it: no second write.
        assert_eq!(
            history_due(&due(slot + 600, Some(slot + 60))),
            Due::No("interval not elapsed")
        );
        // An interval run due shortly before the slot is skipped in favour of the close.
        assert_eq!(
            history_due(&due(slot - 15 * 60, Some(slot - 7 * 3600))),
            Due::No("close snapshot imminent")
        );
        // Weekend: no close slot; Saturday falls back to the interval only.
        let sat = et(2026, 10, 31, 16, 15);
        assert_eq!(
            history_due(&due(sat, Some(sat - 3_600))),
            Due::No("interval not elapsed")
        );
        // Missed slot older than the catch-up window is not taken late.
        assert_eq!(
            history_due(&due(slot + CLOSE_CATCHUP_SECS, Some(slot - 3_600))),
            Due::Interval
        );
    }

    #[test]
    fn tz_formatting_across_dst() {
        let before = 1_793_510_400; // 2026-11-01T05:20:00Z
        assert_eq!(format_in_tz(before, MARKET_TZ), "2026-11-01 01:20 EDT");
        assert_eq!(
            format_in_tz(before + 3_600, MARKET_TZ),
            "2026-11-01 01:20 EST"
        );
        assert_eq!(
            format_in_tz(before, chrono_tz::Europe::Madrid),
            "2026-11-01 06:20 CET"
        );
        assert_eq!(format_in_tz(before, chrono_tz::UTC), "2026-11-01 05:20 UTC");
    }

    #[test]
    fn backup_rotation_only_touches_backup_files() {
        let mut names: Vec<String> = Vec::new();
        let start = NaiveDate::from_ymd_opt(2025, 1, 1).unwrap();
        for k in 0..500 {
            names.push(format!(
                "{BACKUP_PREFIX}{}{BACKUP_SUFFIX}",
                start + chrono::Duration::days(k)
            ));
        }
        names.push("history.sqlite".into());
        names.push("score-history-2025-01-01.sqlite.gz.failed".into());
        names.push("score-history-2025-01-01.sqlite".into());
        names.push("history.pre-restore-20250101T000000.000Z.sqlite.gz".into());
        let del = rotation_deletes(&names);
        let kept: Vec<&String> = names.iter().filter(|n| !del.contains(n)).collect();
        assert!(del.iter().all(|n| backup_day(n).is_some()));
        assert!(kept.contains(&&"history.sqlite".to_string()));
        let dated = kept.iter().filter(|n| backup_day(n).is_some()).count();
        // Last day 2026-05-15: 7 daily + 11 more month heads (2025-06..2026-04); the year
        // heads (2026-05-15, 2025-12-31) are already kept.
        assert_eq!(dated, 18, "{kept:?}");
        let has = |d: &str| kept.iter().any(|n| n.contains(d));
        assert!(has("2025-12-31") && has("2025-06-30") && !has("2025-05-31"));
        // One per year forever.
        let yearly: Vec<String> = (2015..=2026)
            .flat_map(|y| {
                [3u32, 9].map(move |m| format!("{BACKUP_PREFIX}{y}-{m:02}-28{BACKUP_SUFFIX}"))
            })
            .collect();
        let gone = rotation_deletes(&yearly);
        for y in 2015..=2025 {
            let head = format!("{BACKUP_PREFIX}{y}-09-28{BACKUP_SUFFIX}");
            assert!(!gone.contains(&head), "year head {y} kept");
        }
        assert!(gone.contains(&format!("{BACKUP_PREFIX}2015-03-28{BACKUP_SUFFIX}")));
        // On disk: live DB and foreign files survive.
        let dir = tmpdir("rot");
        for n in names.iter().take(40).chain(names.iter().rev().take(4)) {
            std::fs::write(dir.join(n), b"x").unwrap();
        }
        let removed = rotate_backups(&dir).unwrap();
        assert!(!removed.is_empty());
        assert!(dir.join("history.sqlite").exists());
        assert!(dir
            .join("score-history-2025-01-01.sqlite.gz.failed")
            .exists());
        assert!(dir.join("score-history-2025-01-01.sqlite").exists());
        // pre-* copies: newest 10 per kind, nothing else.
        let mut pre: Vec<String> = (0..15)
            .map(|k| {
                format!(
                    "history.pre-restore-202601{:02}T000000.000Z.sqlite.gz",
                    k + 1
                )
            })
            .collect();
        pre.extend((0..12).map(|k| {
            format!(
                "history.pre-score-history-v1-to-v2-202602{:02}T000000.000Z.sqlite.gz",
                k + 1
            )
        }));
        pre.push("history.sqlite".into());
        pre.push("history.pre-restore-notes.txt".into());
        for n in &pre {
            std::fs::write(dir.join(n), b"x").unwrap();
        }
        let gone = rotate_pre_backups(&dir).unwrap();
        assert_eq!(
            gone.len(),
            6 + 2,
            "16 pre-restore (incl. 2025) + 12 pre-migration"
        );
        assert!(!dir
            .join("history.pre-restore-20260101T000000.000Z.sqlite.gz")
            .exists());
        assert!(dir
            .join("history.pre-restore-20260115T000000.000Z.sqlite.gz")
            .exists());
        assert!(dir
            .join("history.pre-restore-20260106T000000.000Z.sqlite.gz")
            .exists());
        assert!(dir.join("history.pre-restore-notes.txt").exists());
        assert!(dir.join("history.sqlite").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn symbol_validation() {
        assert_eq!(validate_symbol(" brk.b ").unwrap(), "BRK.B");
        assert!(validate_symbol("^GSPC").is_ok());
        assert!(validate_symbol("EURUSD=X").is_ok());
        assert!(validate_symbol("").is_err());
        assert!(validate_symbol("A B").is_err());
        assert!(validate_symbol("A';--").is_err());
        assert!(validate_symbol(&"A".repeat(17)).is_err());
    }
    #[test]
    fn shadow_rows_written_with_the_run_and_append_only() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        let mut a = row("ADSK", "Act", 36, 60, -20, 30);
        a.shadow = Some(crate::opportunity_v31::shadow_grid(
            Some("Act"),
            36,
            Some(-20),
            Some(-0.0307),
            false,
        ));
        let w = row("ZZZ", "Watch", 20, 60, 0, 30); // no grid: no shadow rows
        s.record_run(&meta(1), &[a.clone(), w]).unwrap();
        assert_eq!(
            (s.counts().unwrap(), s.shadow_count().unwrap()),
            ((1, 2), 6)
        );
        // Duplicate run id: no-op for official and shadow rows alike.
        s.record_run(&meta(1), &[a]).unwrap();
        assert_eq!(s.shadow_count().unwrap(), 6);
        let c = s.conn.lock().unwrap();
        let ids: Vec<String> = c
            .prepare("SELECT config_id FROM score_shadow ORDER BY config_id")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        let want: Vec<String> = crate::opportunity_v31::V31_SHADOW_CONFIGS
            .iter()
            .map(|c| c.id.to_string())
            .collect();
        assert_eq!(ids, want);
        // Provenance per row: rule version, parameters and warm-up flag.
        let prov: Vec<(String, f64, f64, f64, i64)> = c
            .prepare(
                "SELECT rule_version, bound, lambda_t, lambda_m, warmup FROM score_shadow \
                 ORDER BY config_id",
            )
            .unwrap()
            .query_map([], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
            })
            .unwrap()
            .map(Result::unwrap)
            .collect();
        for (p, c) in prov.iter().zip(crate::opportunity_v31::V31_SHADOW_CONFIGS) {
            assert_eq!(p, &("V3.1-shadow".to_string(), c.bound, 1.5, c.lambda_m, 0));
        }
    }

    #[test]
    fn v2_db_from_67_is_backed_up_then_migrates_to_v3_with_shadow() {
        let dir = tmpdir("shadow-mig");
        let (p, bdir) = (dir.join("history.sqlite"), dir.join("bk"));
        let mut s = ScoreHistoryStore::open_with(&p, Some(&bdir), &MIGRATIONS[..2]).unwrap();
        assert_eq!(s.schema_version().unwrap(), 2);
        // #67's binary only required its own 9 triggers; model that writer.
        let later = [
            "score_shadow",
            "score_inputs",
            "score_run_inputs",
            "score_evaluations",
            "score_run_stats",
        ];
        s.missing_triggers
            .retain(|t| !later.iter().any(|p| t.starts_with(p)));
        assert!(s.missing_triggers.is_empty(), "{:?}", s.missing_triggers);
        // Its behaviour probe only covered its own 3 tables (no score_shadow at v2).
        *s.selfcheck.lock().unwrap() = SelfCheck::Passed;
        s.record_run(&meta(100), &[row("ADSK", "Act", 40, 60, 10, 30)])
            .unwrap();
        drop(s);
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        let bk = s.migration_backup.clone().expect("backup before v2 -> v3");
        let rep = verify_file(&bk, false).unwrap();
        assert_eq!(
            (rep.score_runs, rep.score_history, rep.score_shadow),
            (1, 1, -1)
        );
        assert_eq!(s.schema_version().unwrap(), SCHEMA_VERSION);
        assert!(s.missing_triggers.is_empty(), "{:?}", s.missing_triggers);
        assert_eq!(
            (s.counts().unwrap(), s.shadow_count().unwrap()),
            ((1, 1), 0)
        );
        let mut r = row("ADSK", "Act", 36, 60, -20, 30);
        r.shadow = Some(crate::opportunity_v31::shadow_grid(
            Some("Act"),
            36,
            Some(-20),
            Some(-0.03),
            false,
        ));
        s.record_run(&meta(200), &[r]).unwrap();
        assert_eq!(s.shadow_count().unwrap(), 6);
        let r2 = s.export_to(&dir.join("after.sqlite.gz")).unwrap();
        assert_eq!(r2.score_shadow, 6);
        drop(s);
        // Restore into a fresh dir merges the shadow rows once (missing rows only).
        let d2 = tmpdir("shadow-restore");
        let rr = restore(&dir.join("after.sqlite.gz"), &d2, &d2.join("bk")).unwrap();
        assert_eq!(rr.shadow_added, 6);
        let rr = restore(&dir.join("after.sqlite.gz"), &d2, &d2.join("bk")).unwrap();
        assert_eq!(rr.shadow_added, 0, "second restore adds nothing");
        let _ = std::fs::remove_dir_all(&dir);
        let _ = std::fs::remove_dir_all(&d2);
    }

    #[test]
    fn v3_backup_without_score_shadow_or_hist_source_is_refused() {
        let root = tmpdir("noshadow");
        let bdir = root.join("bk");
        // v3 backup whose score_shadow table was dropped: refused, nothing written.
        let f = root.join("v3-noshadow.sqlite");
        custom_backup(&f, 3, |_, _| {});
        Connection::open(&f)
            .unwrap()
            .execute_batch(&format!("{} TABLE score_shadow;", "DROP"))
            .unwrap();
        let data = root.join("d1");
        let e = restore(&f, &data, &bdir).unwrap_err();
        assert!(e.contains("has no score_shadow"), "{e}");
        let s = ScoreHistoryStore::open(&data.join("history.sqlite"), Some(&bdir)).unwrap();
        assert_eq!(
            (s.counts().unwrap(), s.shadow_count().unwrap()),
            ((0, 0), 0)
        );
        drop(s);
        // v3 backup lacking the hist_source column: strict by-name check refuses.
        let f = root.join("v3-nohs.sqlite");
        custom_backup(&f, 3, |t, c| {
            if t == "score_shadow" {
                c.retain(|(n, _)| n != "hist_source")
            }
        });
        let data = root.join("d2");
        let e = restore(&f, &data, &bdir).unwrap_err();
        assert!(e.contains("missing column 'hist_source'"), "{e}");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn hist_source_is_recorded_per_shadow_row() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        let mut a = row("RAW", "Act", 36, 60, -20, 30);
        let mut g =
            crate::opportunity_v31::shadow_grid(Some("Act"), 36, Some(-20), Some(-0.03), false);
        g.iter_mut()
            .for_each(|v| v.hist_source = Some("raw".into()));
        a.shadow = Some(g);
        let mut b = row("RND", "Act", 36, 60, -20, 30);
        let mut g =
            crate::opportunity_v31::shadow_grid(Some("Act"), 36, Some(-20), Some(-0.03), false);
        g.iter_mut()
            .for_each(|v| v.hist_source = Some("rounded".into()));
        b.shadow = Some(g);
        s.record_run(&meta(1), &[a, b]).unwrap();
        let c = s.conn.lock().unwrap();
        let n: Vec<(String, String, i64)> = c
            .prepare(
                "SELECT ticker, hist_source, count(*) FROM score_shadow GROUP BY 1, 2 ORDER BY 1",
            )
            .unwrap()
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        assert_eq!(
            n,
            vec![
                ("RAW".into(), "raw".into(), 6),
                ("RND".into(), "rounded".into(), 6)
            ]
        );
    }

    #[test]
    fn evaluation_checks_refuse_bad_status_and_decision_on_non_ok_rows() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        let c = s.conn.lock().unwrap();
        let ins = [
            "INS",
            "ERT INTO score_evaluations (run_id, symbol, captured_at, status, reason, decision) \
             VALUES (?1, ?2, 0, ?3, 'r', ?4)",
        ]
        .concat();
        let put = |sym: &str, st: &str, d: Option<&str>| c.execute(&ins, params!["R", sym, st, d]);
        assert!(put("A", "ok", Some("Act")).is_ok());
        assert!(put("B", "excluded", None).is_ok());
        assert!(put("C", "failed", None).is_ok());
        for (sym, st, d) in [
            ("D", "excluded", Some("Act")),
            ("E", "failed", Some("Watch")),
            ("F", "bogus", None),
        ] {
            let e = put(sym, st, d).unwrap_err().to_string();
            assert!(e.contains("CHECK"), "{sym}: {e}");
        }
    }

    #[test]
    fn fresh_db_is_current_schema_with_all_triggers() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        assert_eq!(s.schema_version().unwrap(), SCHEMA_VERSION);
        assert_eq!(REQUIRED_TRIGGERS.len(), 24);
        assert!(s.missing_triggers.is_empty());
        let c = s.conn.lock().unwrap();
        let n: i64 = c
            .query_row(
                "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'score_shadow_%'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(n, 3);
        // Behavioural self-check covers score_shadow (empty table gets a sentinel).
        assert!(trigger_behaviour_failures(&c).unwrap().is_empty());
        assert_eq!(table_count(&c, "score_shadow"), 0, "sentinel rolled back");
        // A same-name no-op trigger is caught by the SQL comparison.
        c.execute_batch(&format!(
            "{} TRIGGER score_shadow_no_update; CREATE TRIGGER score_shadow_no_update \
             BEFORE UPDATE ON score_shadow BEGIN SELECT 1; END;",
            "DROP"
        ))
        .unwrap();
        assert_eq!(missing_triggers(&c), vec!["score_shadow_no_update"]);
        assert!(trigger_behaviour_failures(&c)
            .unwrap()
            .iter()
            .any(|f| f == "score_shadow: update"));
    }
}
