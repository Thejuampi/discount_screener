//! Score history: one snapshot per finished feed run per ticker.
//!
//! Lives in the data-dir's `history.sqlite` next to the desktop `snapshots` table, in two
//! additive tables (`score_runs`, `score_history`). Creation is idempotent
//! (`CREATE TABLE IF NOT EXISTS`) and touches no existing table, so opening an older
//! database only adds the new tables. The rows are exactly what `/ranking` returns
//! (android semantics, V3), so history matches what the Opps screen showed.
//!
//! Retention ([`ScoreHistoryStore::apply_retention`]): every run for 30 days; older than
//! that, the last row of each UTC day per symbol for 2 years; rows where the decision
//! changed versus the previous row are kept forever.
//!
//! Contract for Android: `shared/contracts/score-history-v1.md`.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::headless::RankedRow;

pub const SCHEMA_VERSION: i64 = 1;

/// Scoring model / rule version stamped on every `score_runs` row (`rule_version`).
/// THE single place to bump when the decision rules change (e.g. "V3" -> "V3.1").
pub const SCORE_RULE_VERSION: &str = "V3";
pub const KEEP_ALL_SECS: i64 = 30 * 86_400;
pub const KEEP_DAILY_SECS: i64 = 730 * 86_400;

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS score_history_meta (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    version INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS score_runs (
    run_id             TEXT PRIMARY KEY NOT NULL,
    captured_at        INTEGER NOT NULL,
    profile            TEXT NOT NULL,
    model              TEXT NOT NULL,
    rule_version       TEXT NOT NULL,
    semantics          TEXT NOT NULL,
    market_context     INTEGER NOT NULL,
    binary_version     TEXT NOT NULL,
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
INSERT OR IGNORE INTO score_history_meta (id, version) VALUES (1, 1);
";

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct RunMeta {
    pub captured_at: i64,
    pub profile: String,
    pub model: String,
    pub semantics: String,
    pub market_context: bool,
    pub binary_version: String,
    pub symbols_total: usize,
    pub incomplete_symbols: Vec<String>,
}

impl RunMeta {
    pub fn run_id(&self) -> String {
        format!("{}-{}-{}", self.profile, self.captured_at, self.model)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct HistoryPoint {
    pub run_id: String,
    pub captured_at: i64,
    pub date: String,
    pub profile: String,
    pub model: String,
    pub rule_version: String,
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

/// A decision flip and what moved between the two rows.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct DecisionChange {
    pub from_date: String,
    pub to_date: String,
    pub from: String,
    pub to: String,
    pub composite_delta: i64,
    pub fundamentals_delta: Option<i64>,
    pub technical_delta: Option<i64>,
    pub forecast_delta: Option<i64>,
    pub point: HistoryPoint,
}

pub struct ScoreHistoryStore {
    conn: Mutex<Connection>,
}

fn utc_date(epoch: i64) -> String {
    chrono::DateTime::from_timestamp(epoch, 0)
        .map(|d| d.format("%Y-%m-%d %H:%M").to_string())
        .unwrap_or_default()
}

impl ScoreHistoryStore {
    pub fn open(path: &Path) -> Result<Self, String> {
        let conn = Connection::open(path).map_err(|e| format!("open {}: {e}", path.display()))?;
        Self::init(conn)
    }

    pub fn open_in_memory() -> Result<Self, String> {
        Self::init(Connection::open_in_memory().map_err(|e| e.to_string())?)
    }

    fn init(conn: Connection) -> Result<Self, String> {
        conn.busy_timeout(std::time::Duration::from_secs(10))
            .map_err(|e| e.to_string())?;
        conn.execute_batch(SCHEMA)
            .map_err(|e| format!("score history schema: {e}"))?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    pub fn schema_version(&self) -> Result<i64, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        c.query_row(
            "SELECT version FROM score_history_meta WHERE id = 1",
            [],
            |r| r.get(0),
        )
        .map_err(|e| e.to_string())
    }

    /// Writes one run. Idempotent per run id (a repeat is ignored); returns rows written.
    pub fn record_run(&self, meta: &RunMeta, rows: &[RankedRow]) -> Result<usize, String> {
        let run_id = meta.run_id();
        let mut c = self.conn.lock().map_err(|_| "lock")?;
        let tx = c.transaction().map_err(|e| e.to_string())?;
        let inserted = tx
            .execute(
                "INSERT OR IGNORE INTO score_runs (run_id, captured_at, profile, model, rule_version, semantics, \
                 market_context, binary_version, symbols_total, symbols_ranked, incomplete_symbols) \
                 VALUES (?1,?2,?3,?4,?11,?5,?6,?7,?8,?9,?10)",
                params![
                    run_id,
                    meta.captured_at,
                    meta.profile,
                    meta.model,
                    meta.semantics,
                    meta.market_context as i64,
                    meta.binary_version,
                    meta.symbols_total as i64,
                    rows.len() as i64,
                    serde_json::to_string(&meta.incomplete_symbols).unwrap(),
                    SCORE_RULE_VERSION,
                ],
            )
            .map_err(|e| e.to_string())?;
        if inserted == 0 {
            return Ok(0);
        }
        {
            let mut st = tx
                .prepare(
                    "INSERT OR IGNORE INTO score_history (run_id, symbol, captured_at, rank, decision, \
                     composite, fundamentals, technical, forecast, regime, confidence, trust_signal, \
                     price_cents, target_cents, upside_bps, inputs_json) \
                     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,NULL)",
                )
                .map_err(|e| e.to_string())?;
            for r in rows {
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
                    r.target.map(|t| (t * 100.0).round() as i64),
                    r.upside_pct.map(|u| (u * 100.0).round() as i64),
                ])
                .map_err(|e| format!("insert {}: {e}", r.symbol))?;
            }
        }
        tx.commit().map_err(|e| e.to_string())?;
        Ok(rows.len())
    }

    /// Oldest-first history of one symbol over the last `days` days (`None` = all).
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
                 h.confidence, h.trust_signal, h.price_cents, h.target_cents, h.upside_bps, r.rule_version \
                 FROM score_history h JOIN score_runs r ON r.run_id = h.run_id \
                 WHERE h.symbol = ?1 AND h.captured_at >= ?2 \
                   AND (?3 IS NULL OR r.model = ?3) AND (?4 IS NULL OR r.profile = ?4) \
                 ORDER BY h.captured_at ASC",
            )
            .map_err(|e| e.to_string())?;
        let rows = st
            .query_map(
                params![symbol.to_ascii_uppercase(), since, model, profile],
                |r| {
                    let at: i64 = r.get(1)?;
                    Ok(HistoryPoint {
                        run_id: r.get(0)?,
                        captured_at: at,
                        date: utc_date(at),
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
                    })
                },
            )
            .map_err(|e| e.to_string())?;
        rows.collect::<Result<Vec<_>, _>>()
            .map_err(|e| e.to_string())
    }

    pub fn run_count(&self) -> Result<i64, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        c.query_row("SELECT COUNT(*) FROM score_runs", [], |r| r.get(0))
            .map_err(|e| e.to_string())
    }

    pub fn last_run_at(&self, profile: &str, model: &str) -> Result<Option<i64>, String> {
        let c = self.conn.lock().map_err(|_| "lock")?;
        c.query_row(
            "SELECT MAX(captured_at) FROM score_runs WHERE profile = ?1 AND model = ?2",
            params![profile, model],
            |r| r.get::<_, Option<i64>>(0),
        )
        .optional()
        .map(|o| o.flatten())
        .map_err(|e| e.to_string())
    }

    /// Applies retention as of `now`; returns the number of history rows deleted.
    pub fn apply_retention(&self, now: i64) -> Result<usize, String> {
        let mut c = self.conn.lock().map_err(|_| "lock")?;
        let all: Vec<(String, String, i64, String)> = {
            let mut st = c
                .prepare(
                    "SELECT run_id, symbol, captured_at, decision FROM score_history \
                     ORDER BY symbol, captured_at",
                )
                .map_err(|e| e.to_string())?;
            let it = st
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))
                .map_err(|e| e.to_string())?;
            it.collect::<Result<Vec<_>, _>>()
                .map_err(|e| e.to_string())?
        };
        let delete = retention_deletes(&all, now);
        let tx = c.transaction().map_err(|e| e.to_string())?;
        for (run, sym) in &delete {
            tx.execute(
                "DELETE FROM score_history WHERE run_id = ?1 AND symbol = ?2",
                params![run, sym],
            )
            .map_err(|e| e.to_string())?;
        }
        tx.execute(
            "DELETE FROM score_runs WHERE run_id NOT IN (SELECT DISTINCT run_id FROM score_history)",
            [],
        )
        .map_err(|e| e.to_string())?;
        tx.commit().map_err(|e| e.to_string())?;
        Ok(delete.len())
    }
}

/// Pure retention rule over rows sorted by (symbol, captured_at). Returns (run_id, symbol) to drop.
pub fn retention_deletes(
    rows: &[(String, String, i64, String)],
    now: i64,
) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for (i, (run, sym, at, decision)) in rows.iter().enumerate() {
        let age = now - at;
        if age <= KEEP_ALL_SECS {
            continue;
        }
        let prev = i.checked_sub(1).map(|j| &rows[j]).filter(|p| &p.1 == sym);
        let flip = prev.map_or(true, |p| &p.3 != decision);
        if flip {
            continue; // first row of a symbol and every decision change: kept forever
        }
        if age <= KEEP_DAILY_SECS {
            let next = rows.get(i + 1).filter(|n| &n.1 == sym);
            let last_of_day =
                next.map_or(true, |n| n.2.div_euclid(86_400) != at.div_euclid(86_400));
            if last_of_day {
                continue;
            }
        }
        out.push((run.clone(), sym.clone()));
    }
    out
}

/// Rows where the decision differs from the previous row, with bucket deltas.
pub fn decision_changes(points: &[HistoryPoint]) -> Vec<DecisionChange> {
    let d = |a: Option<i64>, b: Option<i64>| Some(b? - a?);
    points
        .windows(2)
        .filter(|w| w[0].decision != w[1].decision)
        .map(|w| DecisionChange {
            from_date: w[0].date.clone(),
            to_date: w[1].date.clone(),
            from: w[0].decision.clone(),
            to: w[1].decision.clone(),
            composite_delta: w[1].composite - w[0].composite,
            fundamentals_delta: d(w[0].fundamentals, w[1].fundamentals),
            technical_delta: d(w[0].technical, w[1].technical),
            forecast_delta: d(w[0].forecast, w[1].forecast),
            point: w[1].clone(),
        })
        .collect()
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

    fn meta(at: i64) -> RunMeta {
        RunMeta {
            captured_at: at,
            profile: "sp500".into(),
            model: "v3".into(),
            semantics: "android".into(),
            market_context: false,
            binary_version: "test".into(),
            symbols_total: 2,
            incomplete_symbols: vec!["XYZ".into()],
        }
    }

    #[test]
    fn migration_on_old_db_is_additive_and_idempotent() {
        let dir = std::env::temp_dir().join(format!("sh-mig-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("history.sqlite");
        {
            // An older history.sqlite: only the desktop snapshots table, with data.
            let c = Connection::open(&p).unwrap();
            c.execute_batch(
                "CREATE TABLE snapshots (symbol TEXT NOT NULL, captured_at INTEGER NOT NULL, \
                 decision TEXT NOT NULL, PRIMARY KEY(symbol, captured_at)); \
                 INSERT INTO snapshots VALUES ('ADSK', 1, 'Act'); PRAGMA user_version = 7;",
            )
            .unwrap();
        }
        let s = ScoreHistoryStore::open(&p).unwrap();
        assert_eq!(s.schema_version().unwrap(), SCHEMA_VERSION);
        s.record_run(&meta(100), &[row("ADSK", "Act", 40, 60, 10, 30)])
            .unwrap();
        drop(s);
        let s = ScoreHistoryStore::open(&p).unwrap(); // reopen: no error, data kept
        assert_eq!(s.run_count().unwrap(), 1);
        let c = Connection::open(&p).unwrap();
        let legacy: (String, String) = c
            .query_row("SELECT symbol, decision FROM snapshots", [], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .unwrap();
        assert_eq!(legacy, ("ADSK".into(), "Act".into()));
        let uv: i64 = c
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(uv, 7, "the main schema version is not touched");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn one_write_per_run_and_history_query() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        let rows = [
            row("ADSK", "Act", 40, 60, 10, 30),
            row("SSRM", "Act", 36, 72, -18, 31),
        ];
        assert_eq!(s.record_run(&meta(1_000), &rows).unwrap(), 2);
        assert_eq!(
            s.record_run(&meta(1_000), &rows).unwrap(),
            0,
            "same run id is ignored"
        );
        assert_eq!(s.run_count().unwrap(), 1);
        s.record_run(&meta(2_000), &[row("ADSK", "Watch", 25, 55, -10, 20)])
            .unwrap();
        let h = s.history("adsk", None, 3_000, Some("v3"), None).unwrap();
        assert_eq!(h.len(), 2);
        assert_eq!(
            (h[0].decision.as_str(), h[1].decision.as_str()),
            ("Act", "Watch")
        );
        assert_eq!(h[0].price_cents, 2867);
        assert_eq!(h[0].target_cents, Some(4100));
        assert_eq!(h[0].rule_version, SCORE_RULE_VERSION);
        assert_eq!(
            s.history("ADSK", Some(0), 3_000, None, None).unwrap().len(),
            0
        );
        assert_eq!(
            s.history("ADSK", None, 3_000, Some("v2"), None)
                .unwrap()
                .len(),
            0
        );
        assert_eq!(s.last_run_at("sp500", "v3").unwrap(), Some(2_000));
    }

    #[test]
    fn changes_report_only_flips_with_deltas() {
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        for (at, d, c, t) in [
            (1, "Act", 41, 25),
            (2, "Act", 38, 20),
            (3, "Watch", 27, 3),
            (4, "Avoid", -4, -15),
        ] {
            s.record_run(&meta(at), &[row("ADSK", d, c, 60, t, 30)])
                .unwrap();
        }
        let ch = decision_changes(&s.history("ADSK", None, 10, None, None).unwrap());
        assert_eq!(ch.len(), 2);
        assert_eq!((ch[0].from.as_str(), ch[0].to.as_str()), ("Act", "Watch"));
        assert_eq!(ch[0].composite_delta, -11);
        assert_eq!(ch[0].technical_delta, Some(-17));
        assert_eq!(ch[0].fundamentals_delta, Some(0));
        assert_eq!((ch[1].from.as_str(), ch[1].to.as_str()), ("Watch", "Avoid"));
        assert!(decision_changes(&[]).is_empty());
    }

    #[test]
    fn retention_keeps_recent_daily_and_flips() {
        let day = 86_400;
        let now = 1_000 * day;
        let s = ScoreHistoryStore::open_in_memory().unwrap();
        let runs = [
            (now - 900 * day, "Act"), // >2y, first row of the symbol: kept (start of history)
            (now - 800 * day, "Act"), // >2y, no flip: dropped
            (now - 799 * day, "Watch"), // >2y but a flip: kept forever
            (now - 100 * day, "Watch"), // 30d..2y, morning, same day as next: dropped
            (now - 100 * day + 3_600, "Watch"), // last of that day: kept
            (now - 10 * day, "Watch"), // <30d: kept
            (now - 10 * day + 60, "Watch"), // <30d: kept
        ];
        for (at, d) in runs {
            s.record_run(&meta(at), &[row("ADSK", d, 30, 60, 0, 30)])
                .unwrap();
        }
        let deleted = s.apply_retention(now).unwrap();
        assert_eq!(deleted, 2);
        let kept: Vec<i64> = s
            .history("ADSK", None, now, None, None)
            .unwrap()
            .iter()
            .map(|p| p.captured_at)
            .collect();
        assert_eq!(
            kept,
            vec![
                now - 900 * day,
                now - 799 * day,
                now - 100 * day + 3_600,
                now - 10 * day,
                now - 10 * day + 60
            ]
        );
        assert_eq!(
            s.run_count().unwrap(),
            5,
            "runs left with no rows are removed"
        );
        assert_eq!(s.apply_retention(now).unwrap(), 0, "idempotent");
    }
}
