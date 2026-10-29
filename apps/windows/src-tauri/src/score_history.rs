//! Score history: one snapshot per recorded feed run per ticker. **Append-only.**
//!
//! Lives inside the app database, `<data-dir>/history.sqlite` (the same file the desktop
//! app and headless use), in additive tables: `score_runs`, `score_history` and the
//! migration log `score_history_migrations`.
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

use chrono::{Datelike, NaiveDate, TimeZone};
use chrono_tz::Tz;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::headless::RankedRow;

/// Scoring model / rule version stamped on every `score_runs` row (`rule_version`).
/// THE single place to bump when the decision rules change (e.g. "V3" -> "V3.1").
pub const SCORE_RULE_VERSION: &str = "V3";

/// Highest score-history migration this binary knows.
pub const SCHEMA_VERSION: i64 = 2;
/// Tables exported to backups and merged on restore.
pub const HISTORY_TABLES: [&str; 3] = ["score_runs", "score_history", "score_history_migrations"];
/// Tables the source guard protects (includes the planned shadow table).
pub const GUARDED_TABLES: [&str; 4] = [
    "score_runs",
    "score_history",
    "score_history_migrations",
    "score_shadow",
];
/// Triggers that must exist before the store writes.
pub const REQUIRED_TRIGGERS: [&str; 9] = [
    "score_runs_no_delete",
    "score_runs_no_update",
    "score_runs_no_overwrite",
    "score_history_no_delete",
    "score_history_no_update",
    "score_history_no_overwrite",
    "score_history_migrations_no_delete",
    "score_history_migrations_no_update",
    "score_history_migrations_no_overwrite",
];

/// Backups go to `--history-backup-dir`, else this env var, else
/// `$XDG_DATA_HOME/vantage/backups` (`~/.local/share/vantage/backups`).
pub const BACKUP_DIR_ENV: &str = "VANTAGE_HISTORY_BACKUP_DIR";
pub const BACKUP_PREFIX: &str = "score-history-";
pub const BACKUP_SUFFIX: &str = ".sqlite.gz";
pub const BACKUP_KEEP_DAILY: usize = 30;
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
];

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
    pub bytes: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct RestoreReport {
    pub pre_restore_backup: Option<PathBuf>,
    pub runs_added: usize,
    pub rows_added: usize,
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

/// Missing names from [`REQUIRED_TRIGGERS`] in schema `main`.
pub fn missing_triggers(c: &Connection) -> Vec<&'static str> {
    REQUIRED_TRIGGERS
        .iter()
        .copied()
        .filter(|t| {
            c.query_row(
                "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?1",
                params![t],
                |_| Ok(()),
            )
            .optional()
            .ok()
            .flatten()
            .is_none()
        })
        .collect()
}

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
        conn.busy_timeout(std::time::Duration::from_secs(10))
            .map_err(err)?;
        if path.is_some() {
            // Same journal mode the desktop Db already sets on this file (db.rs).
            let _ = conn.pragma_update(None, "journal_mode", "WAL");
        }
        let migration_backup = migrate(&conn, backup_dir.as_deref(), migrations)?;
        let missing_triggers = missing_triggers(&conn);
        Ok(Self {
            conn: Mutex::new(conn),
            path,
            migration_backup,
            missing_triggers,
        })
    }

    pub fn path(&self) -> Option<&Path> {
        self.path.as_deref()
    }

    fn check_writable(&self) -> Result<(), String> {
        if self.missing_triggers.is_empty() {
            Ok(())
        } else {
            Err(format!(
                "score history: refusing to write, append-only triggers missing: {}",
                self.missing_triggers.join(", ")
            ))
        }
    }

    pub fn schema_version(&self) -> Result<i64, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        current_version(&c)
    }

    /// Appends one run with plain INSERTs. A run id that already exists is a no-op
    /// ([`RecordOutcome::Duplicate`]); nothing is ever overwritten.
    pub fn record_run(&self, meta: &RunMeta, rows: &[RankedRow]) -> Result<RecordOutcome, String> {
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
        tx.commit().map_err(err)?;
        Ok(RecordOutcome::Recorded {
            rows: good.len(),
            skipped: bad.iter().map(|r| r.symbol.clone()).collect(),
        })
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

    /// Exports `score_runs`, `score_history` and `score_history_migrations` (nothing else)
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
        let (runs, rows) = {
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
            );
            c.execute("ATTACH DATABASE ?1 AS bk", params![tmp.to_string_lossy()])
                .map_err(err)?;
            let copied = (|| -> Result<(), String> {
                c.execute_batch("BEGIN").map_err(err)?;
                for t in HISTORY_TABLES {
                    if let Err(e) =
                        c.execute(&format!("INSERT INTO bk.{t} SELECT * FROM main.{t}"), [])
                    {
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
        check_backup(dest, runs, rows)
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

/// Pure rotation: of the daily backup names, which to delete (keep the newest
/// [`BACKUP_KEEP_DAILY`] days plus the newest file of each of the newest
/// [`BACKUP_KEEP_MONTHLY`] months). Names not matching the pattern are never returned.
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
    for (d, n) in &dated {
        let m = (d.year(), d.month());
        if !months.contains(&m) {
            if months.len() == BACKUP_KEEP_MONTHLY {
                break;
            }
            months.push(m);
            keep.insert((*n).clone());
        }
    }
    dated
        .iter()
        .filter(|(_, n)| !keep.contains(*n))
        .map(|(_, n)| (*n).clone())
        .collect()
}

const PRE_KINDS: [&str; 2] = ["history.pre-score-history-", "history.pre-restore-"];

/// Stamp of a pre-* backup name (`…-<YYYYmmddTHHMMSS.mmmZ>.sqlite.gz`).
fn pre_stamp(name: &str) -> Option<&str> {
    let base = name.strip_suffix(".sqlite.gz")?;
    let s = &base[base.rfind('-')? + 1..];
    (s.len() >= 16 && s.ends_with('Z') && s.as_bytes()[8] == b'T').then_some(s)
}

/// Pure rotation of pre-migration / pre-restore copies: keep the newest
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

/// Keeps the newest [`PRE_BACKUP_KEEP`] pre-migration and pre-restore copies in `dir`.
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

/// Append-only restore: merges the score tables of backup `src` (`.sqlite` or
/// `.sqlite.gz`) into the app DB `<data_dir>/history.sqlite`. Only rows whose key is
/// missing are inserted; nothing is overwritten or deleted. Refuses while a `serve`
/// holds the data-dir lock. The live score tables are exported to `backup_dir` first.
pub fn restore(src: &Path, data_dir: &Path, backup_dir: &Path) -> Result<RestoreReport, String> {
    let _lock = lock_data_dir(data_dir)?;
    let rep = verify_file(src, true)?;
    let src_plain = plain(src)?;
    let store = ScoreHistoryStore::open(&data_dir.join("history.sqlite"), Some(backup_dir))?;
    store.check_writable()?;
    let pre = backup_dir.join(format!("history.pre-restore-{}.sqlite.gz", stamp()));
    store.export_to(&pre)?;
    rotate_pre_backups(backup_dir)?;
    let c = store.conn.lock().map_err(|_| "lock")?;
    c.execute(
        "ATTACH DATABASE ?1 AS src",
        params![src_plain.path.to_string_lossy()],
    )
    .map_err(err)?;
    let merged = (|| -> Result<(usize, usize), String> {
        c.execute_batch("BEGIN EXCLUSIVE").map_err(err)?;
        let res = (|| -> rusqlite::Result<(usize, usize)> {
            let runs = c.execute(
                "INSERT INTO main.score_runs SELECT * FROM src.score_runs s \
                 WHERE NOT EXISTS (SELECT 1 FROM main.score_runs m WHERE m.run_id = s.run_id)",
                [],
            )?;
            let rows = c.execute(
                "INSERT INTO main.score_history SELECT * FROM src.score_history s \
                 WHERE NOT EXISTS (SELECT 1 FROM main.score_history m \
                 WHERE m.run_id = s.run_id AND m.symbol = s.symbol)",
                [],
            )?;
            c.execute_batch("COMMIT")?;
            Ok((runs, rows))
        })();
        res.map_err(|e| {
            let _ = c.execute_batch("ROLLBACK");
            format!("merge from {}: {e}", src.display())
        })
    })();
    let _ = c.execute("DETACH DATABASE src", []);
    let (runs_added, rows_added) = merged?;
    c.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()))
        .map_err(err)?;
    let _ = rep;
    Ok(RestoreReport {
        pre_restore_backup: Some(pre),
        runs_added,
        rows_added,
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
        let extra: &[(i64, &str)] = &[MIGRATIONS[0], MIGRATIONS[1], (3, add_col.as_str())];
        drop(s);
        let s = ScoreHistoryStore::open_with(&p, Some(&bdir), extra).unwrap();
        let bk2 = s.migration_backup.clone().unwrap();
        assert_eq!(verify_file(&bk2, true).unwrap().score_runs, 1);
        assert_eq!(s.schema_version().unwrap(), 3);
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
        s.record_run(&meta(1), &[row("ADSK", "Act", 30, 60, 0, 30)])
            .unwrap();
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
        assert_eq!(
            names,
            ["score_history", "score_history_migrations", "score_runs"]
        );
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
        // 30 daily + monthly heads not already among them (12 months, newest month overlaps).
        assert!(
            (BACKUP_KEEP_DAILY..=BACKUP_KEEP_DAILY + BACKUP_KEEP_MONTHLY).contains(&dated),
            "{dated}"
        );
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
}
