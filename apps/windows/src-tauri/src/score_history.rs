//! Score history: one snapshot per finished feed run per ticker. **Append-only.**
//!
//! Lives inside the app database, `<data-dir>/history.sqlite` (the same file the desktop
//! app and headless use), in two additive tables: `score_runs`, `score_history`.
//!
//! Protections (see `docs/product/score-history.md`):
//! - No retention, pruning or TTL: rows are never deleted or updated. `BEFORE DELETE` /
//!   `BEFORE UPDATE` triggers `RAISE(ABORT)` on both tables (and on the migration log), and
//!   a test scans `src/` for any DELETE/UPDATE/DROP aimed at them.
//! - Migrations are additive only and the whole file is backed up (`VACUUM INTO`,
//!   timestamped, verified) before any pending migration runs.
//! - Verified daily backups (`VACUUM INTO` + `PRAGMA integrity_check` + row counts) to a
//!   directory outside the data-dir, rotated 30 daily + 12 monthly. Rotation only removes
//!   files matching `score-history-YYYY-MM-DD.sqlite` in that directory, never the live DB.
//! - A manual `rm -rf` of the data-dir is covered only by those off-dir backups
//!   (`vantage-headless history-restore <file>`).
//!
//! Contract for Android: `shared/contracts/score-history-v1.md`.

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
/// The two append-only tables.
pub const HISTORY_TABLES: [&str; 3] = ["score_runs", "score_history", "score_shadow"];

/// Production default for daily backups (outside the data-dir). Override with
/// `--history-backup-dir` or [`BACKUP_DIR_ENV`].
pub const DEFAULT_BACKUP_DIR: &str =
    "/cursor/stores/store-676cd913-00cd-4acb-a69b-30555d151409/score-history-backups";
pub const BACKUP_DIR_ENV: &str = "VANTAGE_HISTORY_BACKUP_DIR";
pub const BACKUP_PREFIX: &str = "score-history-";
pub const BACKUP_KEEP_DAILY: usize = 30;
pub const BACKUP_KEEP_MONTHLY: usize = 12;

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
    PRIMARY KEY (run_id, ticker, config_id)
);
CREATE INDEX IF NOT EXISTS score_shadow_config ON score_shadow(config_id, ticker);
CREATE TRIGGER IF NOT EXISTS score_shadow_no_delete BEFORE DELETE ON score_shadow
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS score_shadow_no_update BEFORE UPDATE ON score_shadow
BEGIN SELECT RAISE(ABORT, 'score history is append-only'); END;
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
    pub score_runs: i64,
    pub score_history: i64,
    /// V3.1 shadow rows (-1 when the table is absent, e.g. a pre-v2 backup).
    #[serde(default)]
    pub score_shadow: i64,
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

fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

fn now_epoch() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn stamp(epoch: i64) -> String {
    chrono::DateTime::from_timestamp(epoch, 0)
        .map(|d| d.format("%Y%m%dT%H%M%SZ").to_string())
        .unwrap_or_default()
}

fn table_count(c: &Connection, table: &str) -> i64 {
    c.query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0))
        .unwrap_or(0)
}

/// Consistent copy of the open database (`VACUUM INTO`); fails if `dest` exists.
fn vacuum_into(c: &Connection, dest: &Path) -> Result<(), String> {
    if dest.exists() {
        return Err(format!("{} already exists", dest.display()));
    }
    if let Some(p) = dest.parent() {
        std::fs::create_dir_all(p).map_err(|e| format!("mkdir {}: {e}", p.display()))?;
    }
    c.execute("VACUUM INTO ?1", params![dest.to_string_lossy()])
        .map_err(|e| format!("backup to {}: {e}", dest.display()))?;
    Ok(())
}

/// `PRAGMA integrity_check` + row counts of a backup (or any history file).
pub fn verify_file(path: &Path) -> Result<BackupReport, String> {
    let c = Connection::open_with_flags(path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)
        .map_err(|e| format!("open {}: {e}", path.display()))?;
    let integrity: String = c
        .query_row("PRAGMA integrity_check", [], |r| r.get(0))
        .map_err(|e| format!("integrity_check {}: {e}", path.display()))?;
    let has = |t: &str| -> bool {
        c.query_row(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?1",
            params![t],
            |_| Ok(()),
        )
        .optional()
        .ok()
        .flatten()
        .is_some()
    };
    let rep = BackupReport {
        path: path.to_path_buf(),
        integrity,
        score_runs: if has("score_runs") {
            table_count(&c, "score_runs")
        } else {
            -1
        },
        score_history: if has("score_history") {
            table_count(&c, "score_history")
        } else {
            -1
        },
        score_shadow: if has("score_shadow") {
            table_count(&c, "score_shadow")
        } else {
            -1
        },
    };
    if rep.integrity != "ok" {
        return Err(format!(
            "{}: integrity_check = {}",
            path.display(),
            rep.integrity
        ));
    }
    Ok(rep)
}

pub fn default_backup_dir() -> PathBuf {
    std::env::var_os(BACKUP_DIR_ENV)
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(DEFAULT_BACKUP_DIR))
}

pub struct ScoreHistoryStore {
    conn: Mutex<Connection>,
    path: Option<PathBuf>,
    /// Backup taken before migrations at open, if any.
    pub migration_backup: Option<PathBuf>,
}

impl ScoreHistoryStore {
    /// Opens (and additively migrates) the history tables inside the app DB at `path`.
    /// Before any pending migration on a non-empty file, a verified `VACUUM INTO` copy is
    /// written to `backup_dir` (or next to the file when `None`).
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
        Ok(Self {
            conn: Mutex::new(conn),
            path,
            migration_backup,
        })
    }

    pub fn path(&self) -> Option<&Path> {
        self.path.as_deref()
    }

    pub fn schema_version(&self) -> Result<i64, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        current_version(&c)
    }

    /// Appends one run. Idempotent per run id (a repeat is a no-op).
    pub fn record_run(&self, meta: &RunMeta, rows: &[RankedRow]) -> Result<RecordOutcome, String> {
        let run_id = meta.run_id();
        let (good, bad): (Vec<&RankedRow>, Vec<&RankedRow>) = rows
            .iter()
            .partition(|r| r.price.is_finite() && r.price > 0.0);
        let mut c = self.conn.lock().map_err(|_| "lock")?;
        let tx = c.transaction().map_err(err)?;
        let inserted = tx
            .execute(
                "INSERT OR IGNORE INTO score_runs (run_id, captured_at, profile, model, rule_version, \
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
        if inserted == 0 {
            return Ok(RecordOutcome::Duplicate);
        }
        {
            let mut st = tx
                .prepare(
                    "INSERT OR IGNORE INTO score_history (run_id, symbol, captured_at, rank, decision, \
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
            // V3.1 shadow grid, same transaction as the official rows (all or nothing).
            let mut st = tx
                .prepare(
                    "INSERT OR IGNORE INTO score_shadow (run_id, ticker, rule_version, config_id, bound, \
                     lambda_t, lambda_m, m, warmup, s_adj, shadow_decision) \
                     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
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
                    ])
                    .map_err(|e| format!("insert shadow {} {}: {e}", r.symbol, v.config_id))?;
                }
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
        let since = days.map(|d| now - d * 86_400).unwrap_or(i64::MIN);
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

    /// Verified `VACUUM INTO` copy of the whole app DB to `dest` (must not exist).
    pub fn backup_to(&self, dest: &Path) -> Result<BackupReport, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        let (runs, rows, shadow) = (
            table_count(&c, "score_runs"),
            table_count(&c, "score_history"),
            table_count(&c, "score_shadow"),
        );
        vacuum_into(&c, dest)?;
        drop(c);
        let rep = verify_file(dest)?;
        if rep.score_runs != runs || rep.score_history != rows || rep.score_shadow != shadow {
            let bad = dest.with_extension("sqlite.failed");
            let _ = std::fs::rename(dest, &bad);
            return Err(format!(
                "backup {} row counts {}/{}/{} != live {runs}/{rows}/{shadow} (moved to {})",
                dest.display(),
                rep.score_runs,
                rep.score_history,
                rep.score_shadow,
                bad.display()
            ));
        }
        Ok(rep)
    }

    /// Daily backup `score-history-YYYY-MM-DD.sqlite` (UTC day of `now`) when missing,
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
        let dest = dir.join(format!("{BACKUP_PREFIX}{day}.sqlite"));
        if dest.exists() {
            return Ok(None);
        }
        let rep = self.backup_to(&dest)?;
        rotate_backups(dir, self.path.as_deref())?;
        Ok(Some(rep))
    }
}

fn current_version(c: &Connection) -> Result<i64, String> {
    c.query_row(
        "SELECT COALESCE(MAX(version), 0) FROM score_history_migrations",
        [],
        |r| r.get(0),
    )
    .map_err(err)
}

/// Applies pending additive migrations; backs the file up first when it has any content.
fn migrate(
    c: &Connection,
    backup_dir: Option<&Path>,
    migrations: &[(i64, &str)],
) -> Result<Option<PathBuf>, String> {
    let has_log: bool = c
        .query_row(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='score_history_migrations'",
            [],
            |_| Ok(()),
        )
        .optional()
        .map_err(err)?
        .is_some();
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
            let dest = dir.join(format!(
                "history.pre-score-history-v{current}-to-v{target}-{}.sqlite",
                stamp(now_epoch())
            ));
            vacuum_into(c, &dest)?;
            verify_file(&dest)?;
            Some(dest)
        }
        _ => None,
    };
    c.execute_batch(MIGRATION_LOG).map_err(err)?;
    for (v, sql) in pending {
        c.execute_batch(&format!("BEGIN IMMEDIATE;\n{sql}\nCOMMIT;"))
            .map_err(|e| {
                let _ = c.execute_batch("ROLLBACK");
                format!("score history migration v{v}: {e}")
            })?;
        c.execute(
            "INSERT INTO score_history_migrations (version, applied_at, backup_path) VALUES (?1,?2,?3)",
            params![v, now_epoch(), backup.as_ref().map(|p| p.to_string_lossy().to_string())],
        )
        .map_err(err)?;
    }
    Ok(backup)
}

fn backup_day(name: &str) -> Option<NaiveDate> {
    let d = name.strip_prefix(BACKUP_PREFIX)?.strip_suffix(".sqlite")?;
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
    dated.sort_by(|a, b| b.0.cmp(&a.0));
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

/// Removes rotated-out daily backups in `dir`. Only exact `score-history-YYYY-MM-DD.sqlite`
/// files are candidates, and never the live DB.
pub fn rotate_backups(dir: &Path, live: Option<&Path>) -> Result<Vec<PathBuf>, String> {
    let names: Vec<String> = std::fs::read_dir(dir)
        .map_err(|e| format!("read {}: {e}", dir.display()))?
        .flatten()
        .filter(|e| e.file_type().map(|t| t.is_file()).unwrap_or(false))
        .filter_map(|e| e.file_name().into_string().ok())
        .collect();
    let live = live.and_then(|p| std::fs::canonicalize(p).ok());
    let mut removed = Vec::new();
    for n in rotation_deletes(&names) {
        let p = dir.join(&n);
        if backup_day(&n).is_none() || std::fs::canonicalize(&p).ok() == live {
            continue;
        }
        std::fs::remove_file(&p).map_err(|e| format!("rotate {}: {e}", p.display()))?;
        removed.push(p);
    }
    Ok(removed)
}

/// Replaces the app DB at `live` with the verified backup `src`. Refuses to overwrite an
/// existing file without `force`; when it does, `live` is first backed up to `backup_dir`.
/// The daemon / desktop app must be stopped. Returns the pre-restore backup, if any.
pub fn restore(
    src: &Path,
    live: &Path,
    backup_dir: &Path,
    force: bool,
) -> Result<Option<PathBuf>, String> {
    let rep = verify_file(src)?;
    if rep.score_runs < 0 {
        return Err(format!("{} has no score_runs table", src.display()));
    }
    let mut pre = None;
    if live.exists() {
        if !force {
            return Err(format!(
                "{} exists; pass --force to replace it (it is backed up first)",
                live.display()
            ));
        }
        let c = Connection::open(live).map_err(err)?;
        let dest = backup_dir.join(format!("history.pre-restore-{}.sqlite", stamp(now_epoch())));
        vacuum_into(&c, &dest)?;
        verify_file(&dest)?;
        drop(c);
        pre = Some(dest);
    }
    if let Some(p) = live.parent() {
        std::fs::create_dir_all(p).map_err(err)?;
    }
    let tmp = live.with_extension(format!("restore-{}.tmp", std::process::id()));
    let _ = std::fs::remove_file(&tmp);
    {
        let s = Connection::open_with_flags(src, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)
            .map_err(err)?;
        vacuum_into(&s, &tmp)?;
    }
    verify_file(&tmp)?;
    for ext in ["sqlite-wal", "sqlite-shm"] {
        let _ = std::fs::remove_file(live.with_extension(ext));
    }
    std::fs::rename(&tmp, live).map_err(|e| format!("rename into {}: {e}", live.display()))?;
    Ok(pre)
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
        let rep = verify_file(&bk).unwrap();
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
        let extra: &[(i64, &str)] = &[
            MIGRATIONS[0],
            MIGRATIONS[1],
            (3, "ALTER TABLE score_runs ADD COLUMN note TEXT;"),
        ];
        drop(s);
        let s = ScoreHistoryStore::open_with(&p, Some(&bdir), extra).unwrap();
        let bk2 = s.migration_backup.clone().unwrap();
        assert_eq!(verify_file(&bk2).unwrap().score_runs, 1);
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
    fn delete_and_update_abort() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        let mut r = row("ADSK", "Act", 30, 60, 0, 30);
        r.shadow = Some(crate::opportunity_v31::shadow_grid(
            Some("Act"),
            30,
            Some(0),
            Some(0.0),
            false,
        ));
        s.record_run(&meta(1), &[r]).unwrap();
        assert_eq!(s.shadow_count().unwrap(), 6);
        let c = s.conn.lock().unwrap();
        for t in HISTORY_TABLES
            .iter()
            .chain(["score_history_migrations"].iter())
        {
            let del = format!("{} FROM {t}", "DELETE");
            let e = c.execute(&del, []).unwrap_err().to_string();
            assert!(e.contains("append-only"), "{t}: {e}");
            let upd = format!("{} {t} SET rowid = rowid", "UPDATE");
            let e = c.execute(&upd, []).unwrap_err().to_string();
            assert!(e.contains("append-only"), "{t}: {e}");
        }
        drop(c);
        assert_eq!(s.counts().unwrap(), (1, 1));
        assert_eq!(s.shadow_count().unwrap(), 6);
    }

    /// CI guard: no source file may DELETE/UPDATE/DROP/rename the history tables.
    #[test]
    fn no_source_deletes_or_updates_history_tables() {
        fn walk(dir: &Path, out: &mut Vec<PathBuf>) {
            for e in std::fs::read_dir(dir).unwrap().flatten() {
                let p = e.path();
                if p.is_dir() {
                    walk(&p, out);
                } else if p.extension().map_or(false, |x| x == "rs") {
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
        let verbs = [
            ["delete", "from"].join(" "),
            "update".to_string(),
            ["drop", "table"].join(" "),
            ["drop", "table", "if", "exists"].join(" "),
            ["alter", "table"].join(" "),
            ["insert", "or", "replace", "into"].join(" "),
            ["replace", "into"].join(" "),
        ];
        let mut bad = Vec::new();
        for f in files {
            let text = std::fs::read_to_string(&f).unwrap().to_ascii_lowercase();
            let norm: String = text.split_whitespace().collect::<Vec<_>>().join(" ");
            for v in &verbs {
                for t in HISTORY_TABLES
                    .iter()
                    .chain(["score_history_migrations"].iter())
                {
                    let needle = format!("{v} {t}");
                    let mut from = 0;
                    while let Some(i) = norm[from..].find(&needle) {
                        let at = from + i;
                        let after = norm[at + needle.len()..].chars().next().unwrap_or(' ');
                        let additive = v.starts_with("alter")
                            && norm[at + needle.len()..]
                                .trim_start()
                                .starts_with("add column");
                        if !(after.is_ascii_alphanumeric() || after == '_') && !additive {
                            bad.push(format!("{}: {needle}", f.display()));
                        }
                        from = at + needle.len();
                    }
                }
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
        assert_eq!((rep.score_runs, rep.score_history), (2, 2));
        assert!(
            s.daily_backup_if_due(&bdir, 1_793_300_100)
                .unwrap()
                .is_none(),
            "one per day"
        );
        drop(s);
        std::fs::remove_dir_all(&data).unwrap();
        assert!(!p.exists());
        // Refuses to overwrite without --force.
        std::fs::create_dir_all(&data).unwrap();
        std::fs::write(&p, b"").unwrap();
        assert!(restore(&rep.path, &p, &bdir, false).is_err());
        let pre = restore(&rep.path, &p, &bdir, true).unwrap();
        assert!(pre.unwrap().exists(), "current file backed up first");
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        let h = s.history("ADSK", None, 100, None, None).unwrap();
        assert_eq!(
            h.iter().map(|p| p.captured_at).collect::<Vec<_>>(),
            vec![10, 20]
        );
        let _ = std::fs::remove_dir_all(&root);
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
                "{BACKUP_PREFIX}{}.sqlite",
                start + chrono::Duration::days(k)
            ));
        }
        names.push("history.sqlite".into());
        names.push("score-history-2025-01-01.sqlite.failed".into());
        names.push("history.pre-restore-20250101T000000Z.sqlite".into());
        let del = rotation_deletes(&names);
        let kept: Vec<&String> = names.iter().filter(|n| !del.contains(n)).collect();
        assert!(del.iter().all(|n| backup_day(n).is_some()));
        assert!(kept.contains(&&"history.sqlite".to_string()));
        let dated = kept.iter().filter(|n| backup_day(n).is_some()).count();
        // 30 daily + monthly heads not already among them (12 months, newest month overlaps).
        assert!(
            dated >= BACKUP_KEEP_DAILY && dated <= BACKUP_KEEP_DAILY + BACKUP_KEEP_MONTHLY,
            "{dated}"
        );
        // On disk: live DB and foreign files survive.
        let dir = tmpdir("rot");
        for n in names.iter().take(40).chain(names.iter().rev().take(3)) {
            std::fs::write(dir.join(n), b"x").unwrap();
        }
        let removed = rotate_backups(&dir, Some(&dir.join("history.sqlite"))).unwrap();
        assert!(!removed.is_empty());
        assert!(dir.join("history.sqlite").exists());
        assert!(dir.join("score-history-2025-01-01.sqlite.failed").exists());
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
    fn v1_file_is_backed_up_then_gains_score_shadow() {
        let dir = tmpdir("shadow-mig");
        let (p, bdir) = (dir.join("history.sqlite"), dir.join("bk"));
        let s = ScoreHistoryStore::open_with(&p, Some(&bdir), &MIGRATIONS[..1]).unwrap();
        s.record_run(&meta(100), &[row("ADSK", "Act", 40, 60, 10, 30)])
            .unwrap();
        drop(s);
        let s = ScoreHistoryStore::open(&p, Some(&bdir)).unwrap();
        let bk = s.migration_backup.clone().expect("backup before v1 -> v2");
        let rep = verify_file(&bk).unwrap();
        assert_eq!(
            (rep.score_runs, rep.score_history, rep.score_shadow),
            (1, 1, -1)
        );
        assert_eq!(s.schema_version().unwrap(), 2);
        assert_eq!(
            (s.counts().unwrap(), s.shadow_count().unwrap()),
            ((1, 1), 0)
        );
        let r2 = s.backup_to(&dir.join("after.sqlite")).unwrap();
        assert_eq!(r2.score_shadow, 0);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
