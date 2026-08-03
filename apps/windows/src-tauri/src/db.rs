/// SQLite persistence for historical snapshots → enables backtesting.
///
/// Schema: one row per (symbol, captured_at) with all the key metrics so we
/// can later ask "what happened to stocks that were Act on YYYY-MM-DD?".
///
/// Storage: a single file in the OS-appropriate app data directory.
use std::path::PathBuf;
use std::sync::Mutex;

use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS snapshots (
    symbol               TEXT    NOT NULL,
    captured_at          INTEGER NOT NULL,   -- unix epoch seconds
    market_price_cents   INTEGER NOT NULL,
    intrinsic_value_cents INTEGER NOT NULL,
    gap_bps              INTEGER NOT NULL,
    decision             TEXT    NOT NULL,
    composite_score      INTEGER NOT NULL,
    fundamentals_score   INTEGER,
    technical_score      INTEGER,
    forecast_score       INTEGER,
    confidence           TEXT    NOT NULL,
    PRIMARY KEY (symbol, captured_at)
);

CREATE INDEX IF NOT EXISTS idx_snapshots_captured
    ON snapshots(captured_at);

CREATE INDEX IF NOT EXISTS idx_snapshots_symbol_time
    ON snapshots(symbol, captured_at);

-- ── Congressional trading (US House STOCK Act) ─────────────────────────────
CREATE TABLE IF NOT EXISTS politicians (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    full_name     TEXT    NOT NULL UNIQUE,
    last_name     TEXT,
    first_name    TEXT,
    chamber       TEXT    NOT NULL,
    state         TEXT,
    district      TEXT
);

CREATE TABLE IF NOT EXISTS congressional_trades (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    doc_id               TEXT    NOT NULL,
    politician_id        INTEGER NOT NULL,
    owner                TEXT,
    asset_name           TEXT    NOT NULL,
    symbol               TEXT,
    asset_type           TEXT,
    transaction_type     TEXT    NOT NULL,
    transaction_date     TEXT,
    disclosure_date      TEXT,
    amount_range_min     INTEGER,
    amount_range_max     INTEGER,
    cap_gains_over_200   INTEGER,
    imported_at_epoch    INTEGER NOT NULL,
    FOREIGN KEY (politician_id) REFERENCES politicians(id),
    UNIQUE (doc_id, asset_name, transaction_date, transaction_type)
);

CREATE INDEX IF NOT EXISTS idx_ct_symbol ON congressional_trades(symbol);
CREATE INDEX IF NOT EXISTS idx_ct_politician ON congressional_trades(politician_id);
CREATE INDEX IF NOT EXISTS idx_ct_disclosure ON congressional_trades(disclosure_date);

-- Forward-return outcomes per trade (backtest data)
CREATE TABLE IF NOT EXISTS trade_outcomes (
    trade_id                INTEGER PRIMARY KEY,
    base_price_cents        INTEGER,
    price_5d_cents          INTEGER,
    price_30d_cents         INTEGER,
    price_90d_cents         INTEGER,
    price_180d_cents        INTEGER,
    return_5d_bps           INTEGER,
    return_30d_bps          INTEGER,
    return_90d_bps          INTEGER,
    return_180d_bps         INTEGER,
    spy_return_5d_bps       INTEGER,
    spy_return_30d_bps      INTEGER,
    spy_return_90d_bps      INTEGER,
    spy_return_180d_bps     INTEGER,
    estimated_gain_180d_cents INTEGER,
    computed_at             INTEGER NOT NULL,
    FOREIGN KEY (trade_id) REFERENCES congressional_trades(id)
);

-- Aggregated metrics per politician
CREATE TABLE IF NOT EXISTS politician_metrics (
    politician_id              INTEGER PRIMARY KEY,
    total_trades               INTEGER NOT NULL,
    purchase_count             INTEGER NOT NULL,
    sale_count                 INTEGER NOT NULL,
    avg_return_30d_bps         INTEGER,
    avg_return_90d_bps         INTEGER,
    avg_return_180d_bps        INTEGER,
    win_rate_30d_pct           INTEGER,
    win_rate_90d_pct           INTEGER,
    win_rate_180d_pct          INTEGER,
    avg_alpha_90d_bps          INTEGER,
    avg_alpha_180d_bps         INTEGER,
    estimated_total_gain_cents INTEGER,
    confidence_score           INTEGER,
    qualifying_trades          INTEGER NOT NULL,
    updated_at                 INTEGER NOT NULL,
    FOREIGN KEY (politician_id) REFERENCES politicians(id)
);

-- ── Personal portfolio (advisor) ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS portfolio_positions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol          TEXT    NOT NULL,
    quantity        REAL    NOT NULL,
    avg_cost_cents  INTEGER NOT NULL,
    opened_at       TEXT,
    notes           TEXT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

-- ── Investment journal (decision log) ──────────────────────────────────────
-- One row per decision. The discipline that lets you grade the model AND
-- yourself: thesis + the model's read at the moment, reviewed against outcome.
CREATE TABLE IF NOT EXISTS journal_entries (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol           TEXT    NOT NULL,
    action           TEXT    NOT NULL,   -- Buy | Sell | Hold | Watch | Trim | Exit
    thesis           TEXT,
    price_cents      INTEGER,            -- price when the call was made
    setup_score      INTEGER,            -- model score at decision time
    setup_label      TEXT,               -- model label at decision time
    created_at       INTEGER NOT NULL,
    outcome          TEXT,               -- review notes (filled in later)
    exit_price_cents INTEGER,
    closed_at        INTEGER
);

CREATE INDEX IF NOT EXISTS idx_journal_symbol ON journal_entries(symbol);

-- ── Schwab API auth (single-row) ───────────────────────────────────────────
-- Stores the user's developer app credentials + OAuth tokens locally. Same
-- trust model as any brokerage CLI: plaintext in the user's own app-data dir.
CREATE TABLE IF NOT EXISTS schwab_auth (
    id                  INTEGER PRIMARY KEY CHECK (id = 1),
    app_key             TEXT,
    secret              TEXT,
    callback            TEXT,
    access_token        TEXT,
    refresh_token       TEXT,
    access_expires_at   INTEGER,
    refresh_expires_at  INTEGER,
    updated_at          INTEGER
);

-- ── Email notifications config (single-row) ────────────────────────────────
CREATE TABLE IF NOT EXISTS email_config (
    id               INTEGER PRIMARY KEY CHECK (id = 1),
    smtp_host        TEXT,
    smtp_port        INTEGER,
    username         TEXT,
    password         TEXT,
    from_email       TEXT,
    to_email         TEXT,
    enabled          INTEGER DEFAULT 0,
    daily_digest     INTEGER DEFAULT 1,
    digest_hour      INTEGER DEFAULT 8,
    instant_alerts   INTEGER DEFAULT 1,
    last_digest_date TEXT,
    updated_at       INTEGER
);

-- Legacy FMP tables remain for schema compatibility but are inert.
CREATE TABLE IF NOT EXISTS fmp_forecast_cache (
    provider_day       TEXT NOT NULL,
    symbol             TEXT NOT NULL,
    fetched_at_epoch   INTEGER NOT NULL,
    payload_json       TEXT NOT NULL,
    PRIMARY KEY (provider_day, symbol)
);

CREATE TABLE IF NOT EXISTS fmp_request_budget (
    provider_day       TEXT PRIMARY KEY,
    attempts           INTEGER NOT NULL CHECK (attempts >= 0)
);

-- TipRanks monthly licensed cache. Previous UTC quota months are pruned.
CREATE TABLE IF NOT EXISTS tipranks_forecast_cache (
    provider_month     TEXT NOT NULL,
    symbol             TEXT NOT NULL,
    fetched_at_epoch   INTEGER NOT NULL,
    payload_json       TEXT NOT NULL,
    PRIMARY KEY (provider_month, symbol)
);

-- Local TipRanks counted-call budget (UTC calendar month).
CREATE TABLE IF NOT EXISTS tipranks_request_budget (
    provider_month     TEXT PRIMARY KEY,
    attempts           INTEGER NOT NULL CHECK (attempts >= 0)
);

-- Last free get_my_usage reconciliation snapshot (does not consume quota).
CREATE TABLE IF NOT EXISTS tipranks_usage_snapshot (
    provider_month     TEXT PRIMARY KEY,
    used               INTEGER NOT NULL CHECK (used >= 0),
    limit_calls        INTEGER NOT NULL CHECK (limit_calls >= 0),
    remaining          INTEGER NOT NULL CHECK (remaining >= 0),
    resets_at_epoch    INTEGER NOT NULL,
    reconciled_at_epoch INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS schwab_reports (
    symbol               TEXT    PRIMARY KEY,
    company_name         TEXT,
    exchange             TEXT,
    rating               TEXT    NOT NULL,
    rating_label         TEXT    NOT NULL,
    percentile           INTEGER,
    previous_rating      TEXT,
    report_date          TEXT,
    data_as_of           TEXT,
    price_at_report_cents INTEGER,
    market_cap_billions  REAL,
    beta                 REAL,
    sector               TEXT,
    industry             TEXT,
    price_volatility     TEXT,
    growth_grade         TEXT,
    quality_grade        TEXT,
    sentiment_grade      TEXT,
    stability_grade      TEXT,
    valuation_grade      TEXT,
    eps_forecast_y1      REAL,
    eps_forecast_y2      REAL,
    eps_growth_5yr_pct   REAL,
    esg_rating           TEXT,
    source_filename      TEXT,
    imported_at_epoch    INTEGER NOT NULL
);
"#;

/// Foundation 0B ledger v1; identity/membership v2; lifecycle FP v3; role/command coords v4;
/// immutable command/supersession ledger and legacy quarantine v5.
const SQLITE_SCHEMA_VERSION: i32 = 8;

const EVIDENCE_LEDGER_SCHEMA_V1: &str = r#"
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS issuer (
    issuer_id   TEXT PRIMARY KEY NOT NULL,
    cik         TEXT NOT NULL UNIQUE,
    legal_name  TEXT
);

CREATE TABLE IF NOT EXISTS security (
    security_id        TEXT PRIMARY KEY NOT NULL,
    issuer_id          TEXT NOT NULL REFERENCES issuer(issuer_id),
    currency           TEXT NOT NULL,
    share_class_label  TEXT
);

CREATE TABLE IF NOT EXISTS security_ticker_alias (
    security_id       TEXT NOT NULL REFERENCES security(security_id),
    ticker            TEXT NOT NULL,
    effective_from    TEXT NOT NULL,
    identity_vintage  TEXT NOT NULL,
    PRIMARY KEY (security_id, ticker, identity_vintage)
);

CREATE TABLE IF NOT EXISTS evidence_observation_v2 (
    id                  TEXT PRIMARY KEY NOT NULL,
    fingerprint_sha256  TEXT NOT NULL UNIQUE,
    issuer_id           TEXT NOT NULL,
    security_id         TEXT,
    payload_json        TEXT NOT NULL,
    ingested_at_unix_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS evidence_revision_edge (
    revision_id TEXT NOT NULL,
    supersedes  TEXT,
    observation_id TEXT NOT NULL REFERENCES evidence_observation_v2(id),
    PRIMARY KEY (revision_id, observation_id)
);

CREATE TABLE IF NOT EXISTS valuation_model_run (
    run_id              TEXT PRIMARY KEY NOT NULL,
    method              TEXT NOT NULL,
    engine_version      TEXT NOT NULL,
    method_policy_version TEXT NOT NULL,
    evidence_set_fp     TEXT NOT NULL,
    issuer_id           TEXT NOT NULL,
    security_id         TEXT,
    replay_mode         TEXT NOT NULL,
    result_json         TEXT NOT NULL,
    created_at_unix_ms  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS valuation_current_projection (
    projection_key TEXT PRIMARY KEY NOT NULL,
    run_id         TEXT REFERENCES valuation_model_run(run_id),
    updated_at_unix_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS valuation_projection_invalidation (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    projection_key     TEXT NOT NULL,
    reason_code        TEXT NOT NULL,
    invalidated_at_unix_ms INTEGER NOT NULL,
    prior_run_id       TEXT
);
"#;

const EVIDENCE_LEDGER_SCHEMA_V2: &str = r#"
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS share_basis_vintage (
    basis_id            TEXT PRIMARY KEY NOT NULL,
    security_id         TEXT NOT NULL REFERENCES security(security_id),
    vintage_fingerprint TEXT NOT NULL,
    description         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS valuation_run_observation (
    run_id                   TEXT NOT NULL REFERENCES valuation_model_run(run_id),
    observation_id           TEXT NOT NULL REFERENCES evidence_observation_v2(id),
    ordinal                  INTEGER NOT NULL,
    observation_fingerprint  TEXT NOT NULL,
    PRIMARY KEY (run_id, observation_id)
);

CREATE INDEX IF NOT EXISTS idx_run_obs_run ON valuation_run_observation(run_id);
"#;

/// A persisted snapshot of a single symbol's state at a point in time.
#[derive(Debug, Clone, Serialize)]
pub struct HistorySnapshot {
    pub symbol: String,
    pub captured_at: i64,
    pub market_price_cents: i64,
    pub intrinsic_value_cents: i64,
    pub gap_bps: i32,
    pub decision: String,
    pub composite_score: i32,
    pub fundamentals_score: Option<i32>,
    pub technical_score: Option<i32>,
    pub forecast_score: Option<i32>,
    pub confidence: String,
}

/// Latest snapshot row per symbol (after `captured_at DESC, rowid DESC` pick).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LatestSnapshotRow {
    pub symbol: String,
    pub captured_at: i64,
    pub market_price_cents: i64,
    pub intrinsic_value_cents: i64,
    pub gap_bps: i32,
    pub decision: String,
    pub composite_score: i32,
    pub fundamentals_score: Option<i32>,
    pub technical_score: Option<i32>,
    pub forecast_score: Option<i32>,
    pub confidence: String,
}

/// One row to insert.
pub struct SnapshotInsert<'a> {
    pub symbol: &'a str,
    pub captured_at: i64,
    pub market_price_cents: i64,
    pub intrinsic_value_cents: i64,
    pub gap_bps: i32,
    pub decision: &'a str,
    pub composite_score: i32,
    pub fundamentals_score: Option<i32>,
    pub technical_score: Option<i32>,
    pub forecast_score: Option<i32>,
    pub confidence: &'a str,
}

pub struct Db {
    conn: Mutex<Connection>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FmpCacheRecord {
    pub fetched_at_epoch: i64,
    pub payload_json: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TipRanksCacheRecord {
    pub fetched_at_epoch: i64,
    pub payload_json: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TipRanksUsageRecord {
    pub used: u16,
    pub limit_calls: u16,
    pub remaining: u16,
    pub resets_at_epoch: i64,
    pub reconciled_at_epoch: i64,
}

/// Complete rejected analyst-method command persisted before stale state is cleared.
/// Optional identity coordinates reflect how far control-envelope admission progressed.
pub struct RefusedAnalystMethodAttempt<'a> {
    pub attempted_run_id: &'a str,
    pub raw_command_json: &'a str,
    pub canonical_command_sha256: Option<&'a str>,
    pub decision_at_unix_ms: Option<i64>,
    pub issuer_id: &'a str,
    pub security_id: &'a str,
    pub method: &'a str,
    pub projection_key: &'a str,
    pub supersedes_run_id: &'a str,
    pub replay_mode: Option<&'a str>,
    pub identity_fingerprint: Option<&'a str>,
    pub share_basis_id: Option<&'a str>,
    pub identity_vintage: Option<&'a str>,
    pub ticker: Option<&'a str>,
    pub reason_code: &'a str,
    pub processed_at_unix_ms: i64,
}

#[derive(Debug, Clone, Copy)]
pub struct CurrentProjectionEligibility<'a> {
    pub engine_version: &'a str,
    pub method_policy_version: &'a str,
    pub identity_fingerprint: &'a str,
}

/// Eligible analyst-method run reconstructed for the 1C publication read path.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EligibleAnalystMethodRun {
    pub run_id: String,
    pub projection_key: String,
    pub result_json: String,
    pub issuer_id: String,
    pub security_id: String,
    pub identity_fingerprint: String,
    pub share_basis_id: String,
    pub share_basis_vintage_fingerprint: String,
    pub share_basis_description: String,
    pub identity_vintage: String,
    pub ticker: String,
    pub decision_at_unix_ms: i64,
    pub created_at_unix_ms: i64,
    pub replay_mode: String,
    pub eps_observation_id: String,
    pub multiple_observation_id: String,
    pub eps_observation_json: String,
    pub multiple_observation_json: String,
    pub raw_command_json: String,
}

/// Cache-only publication status for the manual analyst-method lane (Slice 1C).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AnalystMethodPublication {
    Absent,
    Eligible(EligibleAnalystMethodRun),
    Ineligible {
        run_id: Option<String>,
        projection_key: Option<String>,
        reason_code: String,
    },
}

/// Conservative classifier for deterministic command/content refusal. Authority/CAS failures
/// (`supersedes_*`, occupied projection) and SQLite/infrastructure errors intentionally remain
/// false so callers never clear a projection after an ambiguous write failure.
pub fn is_deterministic_lifecycle_refusal(error: &str) -> bool {
    [
        "run_id_content_conflict",
        "observation_id_conflict:",
        "observation_invalid:",
        "observation_issuer_mismatch",
        "observation_security_mismatch",
        "observation_missing_security_id",
        "eps_observation_not_in_prepared_set",
        "multiple_observation_not_in_prepared_set",
        "eps_and_multiple_observation_must_differ",
        "eps_role_semantic_mismatch",
        "multiple_role_semantic_mismatch",
        "analyst_role_partition_mismatch",
        "identity_fingerprint_mismatch",
        "decision_at_mismatch",
        "invalid_decision_at_unix_ms",
        "canonical_command_",
        "revision_",
    ]
    .iter()
    .any(|prefix| error == *prefix || error.starts_with(prefix))
}

impl Db {
    /// Open (or create) the database at `path`. Runs the schema migration.
    pub fn open(path: PathBuf) -> Result<Self, String> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("mkdir {}: {}", parent.display(), e))?;
        }
        let conn =
            Connection::open(&path).map_err(|e| format!("open {}: {}", path.display(), e))?;
        Self::from_connection(conn)
    }

    #[cfg(test)]
    pub fn open_in_memory() -> Result<Self, String> {
        let conn = Connection::open_in_memory().map_err(|e| format!("open memory db: {e}"))?;
        Self::from_connection(conn)
    }

    #[cfg(test)]
    pub fn drop_tipranks_budget_table_for_test(&self) {
        self.conn
            .lock()
            .unwrap()
            .execute("DROP TABLE tipranks_request_budget", [])
            .unwrap();
    }

    fn from_connection(conn: Connection) -> Result<Self, String> {
        conn.execute_batch(SCHEMA)
            .map_err(|e| format!("schema: {}", e))?;
        // WAL mode: better concurrent reads while writer is active
        let _ = conn.pragma_update(None, "journal_mode", "WAL");
        let _ = conn.pragma_update(None, "synchronous", "NORMAL");
        run_migrations(&conn)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    /// Current SQLite `user_version` after migrations.
    pub fn schema_version(&self) -> Result<i32, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.pragma_query_value(None, "user_version", |row| row.get(0))
            .map_err(|e| format!("user_version: {e}"))
    }

    /// Immutable, idempotent identity seed including share-basis vintage.
    /// A stable key can be replayed with identical content, but never rewritten.
    pub fn upsert_identity_bundle(
        &self,
        issuer_id: &str,
        cik: &str,
        legal_name: Option<&str>,
        security_id: &str,
        currency: &str,
        share_class_label: Option<&str>,
        ticker: &str,
        effective_from: &str,
        identity_vintage: &str,
        share_basis_id: &str,
        share_basis_vintage_fingerprint: &str,
        share_basis_description: &str,
    ) -> Result<(), String> {
        for (value, code) in [
            (issuer_id, "empty_issuer_id"),
            (cik, "empty_cik"),
            (security_id, "empty_security_id"),
            (currency, "empty_currency"),
            (ticker, "empty_ticker"),
            (effective_from, "empty_effective_from"),
            (identity_vintage, "empty_identity_vintage"),
            (share_basis_id, "empty_share_basis_id"),
            (
                share_basis_vintage_fingerprint,
                "empty_share_basis_fingerprint",
            ),
        ] {
            if value.trim().is_empty() {
                return Err(code.into());
            }
        }
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute("PRAGMA foreign_keys = ON", [])
            .map_err(|e| format!("foreign_keys: {e}"))?;
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin identity: {e}"))?;
        let existing_issuer: Option<(String, Option<String>)> = tx
            .query_row(
                "SELECT cik, legal_name FROM issuer WHERE issuer_id = ?1",
                params![issuer_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()
            .map_err(|e| format!("lookup issuer seed: {e}"))?;
        match existing_issuer {
            Some((old_cik, old_name)) if old_cik != cik || old_name.as_deref() != legal_name => {
                return Err("immutable_identity_conflict:issuer".into())
            }
            Some(_) => {}
            None => {
                tx.execute(
                    "INSERT INTO issuer (issuer_id, cik, legal_name) VALUES (?1, ?2, ?3)",
                    params![issuer_id, cik, legal_name],
                )
                .map_err(|e| format!("insert issuer: {e}"))?;
            }
        }
        let existing_security: Option<(String, String, Option<String>)> = tx.query_row(
            "SELECT issuer_id, currency, share_class_label FROM security WHERE security_id = ?1", params![security_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        ).optional().map_err(|e| format!("lookup security seed: {e}"))?;
        match existing_security {
            Some((old_issuer, old_currency, old_label))
                if old_issuer != issuer_id
                    || old_currency != currency
                    || old_label.as_deref() != share_class_label =>
            {
                return Err("immutable_identity_conflict:security".into())
            }
            Some(_) => {}
            None => {
                tx.execute("INSERT INTO security (security_id, issuer_id, currency, share_class_label) VALUES (?1, ?2, ?3, ?4)", params![security_id, issuer_id, currency, share_class_label]).map_err(|e| format!("insert security: {e}"))?;
            }
        }
        let existing_ticker: Option<String> = tx.query_row(
            "SELECT effective_from FROM security_ticker_alias WHERE security_id=?1 AND ticker=?2 AND identity_vintage=?3",
            params![security_id, ticker, identity_vintage], |r| r.get(0),
        ).optional().map_err(|e| format!("lookup ticker seed: {e}"))?;
        match existing_ticker {
            Some(old) if old != effective_from => {
                return Err("immutable_identity_conflict:ticker_alias".into())
            }
            Some(_) => {}
            None => {
                tx.execute("INSERT INTO security_ticker_alias (security_id, ticker, effective_from, identity_vintage) VALUES (?1, ?2, ?3, ?4)", params![security_id, ticker, effective_from, identity_vintage]).map_err(|e| format!("insert ticker alias: {e}"))?;
            }
        }
        let existing_basis: Option<(String, String, String)> = tx.query_row(
            "SELECT security_id, vintage_fingerprint, description FROM share_basis_vintage WHERE basis_id=?1",
            params![share_basis_id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        ).optional().map_err(|e| format!("lookup share basis seed: {e}"))?;
        match existing_basis {
            Some((old_security, old_fp, old_desc))
                if old_security != security_id
                    || old_fp != share_basis_vintage_fingerprint
                    || old_desc != share_basis_description =>
            {
                return Err("immutable_identity_conflict:share_basis".into())
            }
            Some(_) => {}
            None => {
                tx.execute("INSERT INTO share_basis_vintage (basis_id, security_id, vintage_fingerprint, description) VALUES (?1, ?2, ?3, ?4)", params![share_basis_id, security_id, share_basis_vintage_fingerprint, share_basis_description]).map_err(|e| format!("insert share basis: {e}"))?;
            }
        }
        tx.commit().map_err(|e| format!("commit identity: {e}"))?;
        Ok(())
    }

    /// Atomic commit of typed V2 observations + model run + membership + optional projection.
    ///
    /// 1B-0.1 fail-closed boundary:
    /// - each observation is `validate_for_persist`'d
    /// - each fingerprint is **recomputed** from the observation (caller cannot supply hash/payload)
    /// - payload JSON is canonical serialization of the observation
    /// - identity_fingerprint must match a seeded security vintage in the ledger
    /// - issuer/security must exist and match observation identity fields
    /// - `certified_backfill_research` never updates `valuation_current_projection`
    #[cfg(test)]
    pub fn commit_valuation_run(
        &self,
        observations: &[crate::valuation_evidence::EvidenceObservationV2],
        run_id: &str,
        method: &str,
        engine_version: &str,
        method_policy_version: &str,
        identity_fingerprint: &str,
        issuer_id: &str,
        security_id: &str,
        replay_mode: crate::valuation_evidence::ReplayMode,
        result_json: &str,
        created_at_unix_ms: i64,
        projection_key: Option<&str>,
    ) -> Result<String, String> {
        if observations.is_empty() {
            return Err("empty_observations".into());
        }
        if identity_fingerprint.trim().is_empty() {
            return Err("empty_identity_fingerprint".into());
        }
        if issuer_id.trim().is_empty() {
            return Err("empty_issuer_id".into());
        }
        if security_id.trim().is_empty() {
            return Err("empty_security_id".into());
        }
        if let Some(key) = projection_key {
            if key.trim().is_empty() {
                return Err("empty_projection_key".into());
            }
            if matches!(
                replay_mode,
                crate::valuation_evidence::ReplayMode::CertifiedBackfillResearch
            ) {
                return Err("certified_backfill_cannot_update_projection".into());
            }
        }

        // Pure-side validation + rehash before opening the write transaction work set.
        let mut prepared: Vec<(
            crate::valuation_evidence::EvidenceObservationV2,
            String,
            String,
        )> = Vec::with_capacity(observations.len());
        for obs in observations {
            if let Err(code) = obs.validate_for_persist() {
                return Err(format!("observation_invalid:{code}"));
            }
            if obs.issuer_id != issuer_id {
                return Err("observation_issuer_mismatch".into());
            }
            match obs.security_id.as_deref() {
                Some(sid) if sid == security_id => {}
                Some(_) => return Err("observation_security_mismatch".into()),
                None => return Err("observation_missing_security_id".into()),
            }
            let fp = obs.fingerprint_sha256();
            let payload =
                serde_json::to_string(obs).map_err(|e| format!("serialize_observation: {e}"))?;
            prepared.push((obs.clone(), fp, payload));
        }

        let obs_fps: Vec<String> = prepared.iter().map(|(_, fp, _)| fp.clone()).collect();
        let evidence_set_fp = crate::valuation_evidence::evidence_set_fingerprint(&obs_fps);
        let replay_mode_s = crate::valuation_evidence::replay_mode_snake(replay_mode);

        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute("PRAGMA foreign_keys = ON", [])
            .map_err(|e| format!("foreign_keys: {e}"))?;
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin valuation run: {e}"))?;

        // Fail-closed identity: issuer/security must exist and fingerprint must match seeded vintage.
        let issuer_exists: bool = tx
            .query_row(
                "SELECT 1 FROM issuer WHERE issuer_id = ?1",
                params![issuer_id],
                |_| Ok(true),
            )
            .optional()
            .map_err(|e| format!("lookup issuer: {e}"))?
            .unwrap_or(false);
        if !issuer_exists {
            return Err("issuer_not_seeded".into());
        }
        let security_issuer: Option<String> = tx
            .query_row(
                "SELECT issuer_id FROM security WHERE security_id = ?1",
                params![security_id],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| format!("lookup security: {e}"))?;
        match security_issuer {
            None => return Err("security_not_seeded".into()),
            Some(sid_issuer) if sid_issuer != issuer_id => {
                return Err("security_issuer_mismatch".into());
            }
            Some(_) => {}
        }
        let seeded_fp = load_identity_fingerprint_for_security(&tx, security_id, None, None, None)?;
        if seeded_fp != identity_fingerprint {
            return Err("identity_fingerprint_mismatch".into());
        }

        for (obs, fp, payload) in &prepared {
            let existing: Option<(String, String)> = tx
                .query_row(
                    "SELECT id, fingerprint_sha256 FROM evidence_observation_v2 WHERE id = ?1",
                    params![&obs.id],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .optional()
                .map_err(|e| format!("lookup observation: {e}"))?;
            if let Some((existing_id, existing_fp)) = existing {
                if existing_fp != *fp {
                    return Err(format!(
                        "observation_id_conflict:{existing_id}:fingerprint_mismatch"
                    ));
                }
                continue;
            }
            let by_fp: Option<String> = tx
                .query_row(
                    "SELECT id FROM evidence_observation_v2 WHERE fingerprint_sha256 = ?1",
                    params![fp],
                    |row| row.get(0),
                )
                .optional()
                .map_err(|e| format!("lookup fingerprint: {e}"))?;
            if by_fp.is_some() {
                continue;
            }
            tx.execute(
                "INSERT INTO evidence_observation_v2
                 (id, fingerprint_sha256, issuer_id, security_id, payload_json, ingested_at_unix_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![
                    &obs.id,
                    fp,
                    &obs.issuer_id,
                    &obs.security_id,
                    payload,
                    obs.ingested_at_unix_ms
                ],
            )
            .map_err(|e| format!("insert observation: {e}"))?;
        }
        let lifecycle_fp = crate::analyst_method_import::lifecycle_fingerprint(
            &evidence_set_fp,
            result_json,
            "sha256:legacy_test_only",
            identity_fingerprint,
            issuer_id,
            security_id,
            method,
            engine_version,
            method_policy_version,
            replay_mode,
            created_at_unix_ms,
            projection_key,
            None,
            "",
            "",
            "",
            "",
            "",
            "",
        );
        tx.execute(
            "INSERT INTO valuation_model_run
             (run_id, method, engine_version, method_policy_version, evidence_set_fp,
              issuer_id, security_id, replay_mode, result_json, created_at_unix_ms,
              identity_fingerprint, lifecycle_fingerprint)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
            params![
                run_id,
                method,
                engine_version,
                method_policy_version,
                evidence_set_fp,
                issuer_id,
                security_id,
                replay_mode_s,
                result_json,
                created_at_unix_ms,
                identity_fingerprint,
                lifecycle_fp
            ],
        )
        .map_err(|e| format!("insert model run: {e}"))?;

        let mut membership: Vec<(String, String)> = prepared
            .iter()
            .map(|(obs, fp, _)| (obs.id.clone(), fp.clone()))
            .collect();
        membership.sort_by(|a, b| a.1.cmp(&b.1).then_with(|| a.0.cmp(&b.0)));
        for (ordinal, (obs_id, obs_fp)) in membership.iter().enumerate() {
            tx.execute(
                "INSERT INTO valuation_run_observation
                 (run_id, observation_id, ordinal, observation_fingerprint)
                 VALUES (?1, ?2, ?3, ?4)",
                params![run_id, obs_id, ordinal as i64, obs_fp],
            )
            .map_err(|e| format!("insert run membership: {e}"))?;
        }

        if let Some(key) = projection_key {
            let occupied: Option<String> = tx
                .query_row(
                    "SELECT run_id FROM valuation_current_projection WHERE projection_key = ?1",
                    params![key],
                    |row| row.get(0),
                )
                .optional()
                .map_err(|e| format!("lookup projection: {e}"))?;
            if occupied.is_some() {
                return Err("projection_occupied_requires_supersedes".into());
            }
            tx.execute(
                "INSERT INTO valuation_current_projection (projection_key, run_id, updated_at_unix_ms)
                 VALUES (?1, ?2, ?3)",
                params![key, run_id, created_at_unix_ms],
            )
            .map_err(|e| format!("insert projection: {e}"))?;
        }
        tx.commit()
            .map_err(|e| format!("commit valuation run: {e}"))?;
        Ok(evidence_set_fp)
    }

    /// Single-transaction analyst-method lifecycle (1B.1): optional supersession invalidation,
    /// observations, model run, membership, revision edges, projection.
    ///
    /// Exact same `run_id` + content is an idempotent no-op. Same `run_id` with different
    /// evidence set / result refuses with `run_id_content_conflict`.
    pub fn commit_analyst_method_lifecycle(
        &self,
        observations: &[crate::valuation_evidence::EvidenceObservationV2],
        raw_command_json: &str,
        canonical_command_sha256: &str,
        decision_at_unix_ms: i64,
        run_id: &str,
        method: &str,
        engine_version: &str,
        method_policy_version: &str,
        identity_fingerprint: &str,
        issuer_id: &str,
        security_id: &str,
        share_basis_id: &str,
        eps_share_basis_id: &str,
        identity_vintage: &str,
        ticker: &str,
        replay_mode: crate::valuation_evidence::ReplayMode,
        result_json: &str,
        created_at_unix_ms: i64,
        projection_key: Option<&str>,
        supersedes_run_id: Option<&str>,
        eps_observation_id: &str,
        multiple_observation_id: &str,
        revision_groups: &[(String, Option<String>, Vec<String>)],
        // (revision_id, supersedes, observation_ids)
    ) -> Result<AnalystMethodLifecycleOutcome, String> {
        if observations.is_empty() {
            return Err("empty_observations".into());
        }
        if identity_fingerprint.trim().is_empty() {
            return Err("empty_identity_fingerprint".into());
        }
        if issuer_id.trim().is_empty() || security_id.trim().is_empty() {
            return Err("empty_issuer_or_security".into());
        }
        if raw_command_json.trim().is_empty() {
            return Err("empty_raw_command_json".into());
        }
        if canonical_command_sha256.trim().is_empty() || eps_share_basis_id.trim().is_empty() {
            return Err("empty_canonical_command_or_eps_share_basis".into());
        }
        if decision_at_unix_ms <= 0 {
            return Err("invalid_decision_at_unix_ms".into());
        }
        if eps_share_basis_id != share_basis_id {
            return Err("eps_share_basis_mismatch".into());
        }
        let recomputed_command =
            crate::analyst_method_import::canonical_command_sha256(raw_command_json)
                .map_err(|e| format!("canonical_command_invalid:{e}"))?;
        if recomputed_command != canonical_command_sha256 {
            return Err("canonical_command_digest_mismatch".into());
        }
        let command =
            crate::analyst_method_import::parse_analyst_method_import_json(raw_command_json)
                .map_err(|e| format!("canonical_command_semantic_invalid:{e}"))?;
        if command.run_id != run_id
            || command.issuer_id != issuer_id
            || command.security_id != security_id
            || command.decision_at_unix_ms != decision_at_unix_ms
            || command.eps_share_basis_id != eps_share_basis_id
            || command.projection_key.as_deref() != projection_key
            || command.supersedes_run_id.as_deref() != supersedes_run_id
            || command.eps_observation_id != eps_observation_id
            || command.multiple_observation_id != multiple_observation_id
        {
            return Err("canonical_command_coordinate_mismatch".into());
        }
        if let Some(key) = projection_key {
            if key.trim().is_empty() {
                return Err("empty_projection_key".into());
            }
            if matches!(
                replay_mode,
                crate::valuation_evidence::ReplayMode::CertifiedBackfillResearch
            ) {
                return Err("certified_backfill_cannot_update_projection".into());
            }
        }

        let mut prepared: Vec<(
            crate::valuation_evidence::EvidenceObservationV2,
            String,
            String,
        )> = Vec::with_capacity(observations.len());
        for obs in observations {
            if let Err(code) = obs.validate_for_persist() {
                return Err(format!("observation_invalid:{code}"));
            }
            if obs.issuer_id != issuer_id {
                return Err("observation_issuer_mismatch".into());
            }
            match obs.security_id.as_deref() {
                Some(sid) if sid == security_id => {}
                Some(_) => return Err("observation_security_mismatch".into()),
                None => return Err("observation_missing_security_id".into()),
            }
            let fp = obs.fingerprint_sha256();
            let payload =
                serde_json::to_string(obs).map_err(|e| format!("serialize_observation: {e}"))?;
            prepared.push((obs.clone(), fp, payload));
        }
        validate_analyst_role_bindings(&prepared, eps_observation_id, multiple_observation_id)?;
        let mut command_members: Vec<(String, String)> = command
            .observations
            .iter()
            .map(|o| (o.id.clone(), o.fingerprint_sha256()))
            .collect();
        let mut prepared_members: Vec<(String, String)> = prepared
            .iter()
            .map(|(o, fp, _)| (o.id.clone(), fp.clone()))
            .collect();
        command_members.sort();
        prepared_members.sort();
        if command_members != prepared_members {
            return Err("canonical_command_evidence_mismatch".into());
        }
        let prepared_ids: std::collections::HashSet<&str> =
            prepared.iter().map(|(o, _, _)| o.id.as_str()).collect();
        let mut revision_intents = std::collections::HashMap::<&str, Option<&str>>::new();
        for (revision_id, supersedes, observation_ids) in revision_groups {
            if revision_id.trim().is_empty() {
                return Err("revision_empty_id".into());
            }
            if supersedes.as_deref() == Some(revision_id.as_str()) {
                return Err("revision_self_supersession".into());
            }
            if observation_ids.is_empty()
                || observation_ids
                    .iter()
                    .any(|id| !prepared_ids.contains(id.as_str()))
            {
                return Err("revision_observation_not_in_prepared_set".into());
            }
            let intent = supersedes.as_deref();
            if let Some(previous) = revision_intents.insert(revision_id, intent) {
                if previous != intent {
                    return Err("revision_id_content_conflict".into());
                }
            }
        }
        let obs_fps: Vec<String> = prepared.iter().map(|(_, fp, _)| fp.clone()).collect();
        let evidence_set_fp = crate::valuation_evidence::evidence_set_fingerprint(&obs_fps);
        let replay_mode_s = crate::valuation_evidence::replay_mode_snake(replay_mode);
        let lifecycle_fp = crate::analyst_method_import::lifecycle_fingerprint(
            &evidence_set_fp,
            result_json,
            canonical_command_sha256,
            identity_fingerprint,
            issuer_id,
            security_id,
            method,
            engine_version,
            method_policy_version,
            replay_mode,
            decision_at_unix_ms,
            projection_key,
            supersedes_run_id,
            eps_observation_id,
            multiple_observation_id,
            share_basis_id,
            eps_share_basis_id,
            identity_vintage,
            ticker,
        );

        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute("PRAGMA foreign_keys = ON", [])
            .map_err(|e| format!("foreign_keys: {e}"))?;
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin lifecycle: {e}"))?;

        // Idempotent retry: full lifecycle fingerprint must match (1B.2 / 1B.3).
        let existing_lifecycle: Option<(String,i64,String)> = tx
            .query_row(
                "SELECT lifecycle_fingerprint,decision_at_unix_ms,canonical_command_sha256 FROM valuation_model_run WHERE run_id = ?1",
                params![run_id],
                |row| Ok((row.get(0)?,row.get(1)?,row.get(2)?)),
            )
            .optional()
            .map_err(|e| format!("lookup run: {e}"))?;
        if let Some((ex_lc, ex_decision, ex_command)) = existing_lifecycle {
            if ex_decision != decision_at_unix_ms {
                return Err("decision_at_mismatch".into());
            }
            if ex_command != canonical_command_sha256 {
                return Err("run_id_content_conflict:canonical_command".into());
            }
            if ex_lc == lifecycle_fp {
                tx.commit()
                    .map_err(|e| format!("commit noop lifecycle: {e}"))?;
                return Ok(AnalystMethodLifecycleOutcome {
                    evidence_set_fp,
                    invalidated_prior_run_id: None,
                    idempotent_replay: true,
                });
            }
            return Err("run_id_content_conflict".into());
        }

        // Identity fail-closed — exact vintage coordinates (1B.3).
        let issuer_exists: bool = tx
            .query_row(
                "SELECT 1 FROM issuer WHERE issuer_id = ?1",
                params![issuer_id],
                |_| Ok(true),
            )
            .optional()
            .map_err(|e| format!("lookup issuer: {e}"))?
            .unwrap_or(false);
        if !issuer_exists {
            return Err("issuer_not_seeded".into());
        }
        let security_issuer: Option<String> = tx
            .query_row(
                "SELECT issuer_id FROM security WHERE security_id = ?1",
                params![security_id],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| format!("lookup security: {e}"))?;
        match security_issuer {
            None => return Err("security_not_seeded".into()),
            Some(sid_issuer) if sid_issuer != issuer_id => {
                return Err("security_issuer_mismatch".into());
            }
            Some(_) => {}
        }
        let seeded_fp = load_identity_fingerprint_for_security(
            &tx,
            security_id,
            Some(share_basis_id),
            Some(identity_vintage),
            Some(ticker),
        )?;
        if seeded_fp != identity_fingerprint {
            return Err("identity_fingerprint_mismatch".into());
        }

        for (revision_id, supersedes, _) in revision_groups {
            let persisted_supersedes: Option<Option<String>> = tx
                .query_row(
                    "SELECT supersedes FROM evidence_revision_edge WHERE revision_id=?1 LIMIT 1",
                    params![revision_id],
                    |r| r.get(0),
                )
                .optional()
                .map_err(|e| format!("lookup revision intent: {e}"))?;
            if let Some(old) = persisted_supersedes {
                if old.as_deref() != supersedes.as_deref() {
                    return Err("revision_id_content_conflict".into());
                }
            }
            if let Some(predecessor) = supersedes {
                let cycle: bool = tx
                    .query_row(
                        "WITH RECURSIVE ancestors(id) AS (
                       SELECT ?1
                       UNION
                       SELECT e.supersedes FROM evidence_revision_edge e
                       JOIN ancestors a ON e.revision_id=a.id WHERE e.supersedes IS NOT NULL
                     ) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=?2)",
                        params![predecessor, revision_id],
                        |r| r.get(0),
                    )
                    .map_err(|e| format!("check revision cycle: {e}"))?;
                if cycle {
                    return Err("revision_cycle".into());
                }
                let predecessor_partition: Option<(String, Option<String>)> = tx
                    .query_row(
                        "SELECT o.issuer_id, o.security_id FROM evidence_revision_edge e
                     JOIN evidence_observation_v2 o ON o.id=e.observation_id
                     WHERE e.revision_id=?1 LIMIT 1",
                        params![predecessor],
                        |r| Ok((r.get(0)?, r.get(1)?)),
                    )
                    .optional()
                    .map_err(|e| format!("lookup revision predecessor: {e}"))?;
                if let Some((pred_issuer, pred_security)) = predecessor_partition {
                    if pred_issuer != issuer_id || pred_security.as_deref() != Some(security_id) {
                        return Err("revision_predecessor_partition_mismatch".into());
                    }
                } else if !revision_intents.contains_key(predecessor.as_str()) {
                    return Err("revision_predecessor_missing".into());
                }
            }
        }

        // Projection ownership (1B.2): occupied key requires explicit supersession.
        let mut invalidated_prior = None;
        if let Some(key) = projection_key {
            let current: Option<String> = tx
                .query_row(
                    "SELECT run_id FROM valuation_current_projection WHERE projection_key = ?1",
                    params![key],
                    |row| row.get(0),
                )
                .optional()
                .map_err(|e| format!("lookup projection: {e}"))?;
            if current.is_some() && supersedes_run_id.is_none() {
                return Err("projection_occupied_requires_supersedes".into());
            }
        }

        // Supersession: must be current projection for same issuer/security/method.
        if let Some(prior) = supersedes_run_id {
            let key = projection_key.ok_or("supersedes_requires_projection_key")?;
            let current: Option<String> = tx
                .query_row(
                    "SELECT run_id FROM valuation_current_projection WHERE projection_key = ?1",
                    params![key],
                    |row| row.get(0),
                )
                .optional()
                .map_err(|e| format!("lookup projection: {e}"))?;
            match current.as_deref() {
                Some(cur) if cur == prior => {}
                Some(cur) => {
                    return Err(format!("supersedes_not_current_projection:{cur}"));
                }
                None => return Err("supersedes_no_current_projection".into()),
            }
            let prior_meta: Option<(String, String, String)> = tx
                .query_row(
                    "SELECT issuer_id, security_id, method FROM valuation_model_run WHERE run_id = ?1",
                    params![prior],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .optional()
                .map_err(|e| format!("lookup prior run: {e}"))?;
            match prior_meta {
                None => return Err(format!("supersedes_run_missing:{prior}")),
                Some((p_iss, p_sec, p_method)) => {
                    if p_iss != issuer_id || p_sec != security_id {
                        return Err("supersedes_identity_mismatch".into());
                    }
                    if p_method != method {
                        return Err("supersedes_method_mismatch".into());
                    }
                }
            }
            tx.execute(
                "INSERT INTO valuation_projection_invalidation
                 (projection_key, reason_code, invalidated_at_unix_ms, prior_run_id)
                 VALUES (?1, ?2, ?3, ?4)",
                params![key, "superseded_by_revision", created_at_unix_ms, prior],
            )
            .map_err(|e| format!("insert invalidation: {e}"))?;
            tx.execute(
                "DELETE FROM valuation_current_projection WHERE projection_key = ?1",
                params![key],
            )
            .map_err(|e| format!("clear projection: {e}"))?;
            invalidated_prior = Some(prior.to_string());
        }

        for (obs, fp, payload) in &prepared {
            let existing: Option<(String, String)> = tx
                .query_row(
                    "SELECT id, fingerprint_sha256 FROM evidence_observation_v2 WHERE id = ?1",
                    params![&obs.id],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .optional()
                .map_err(|e| format!("lookup observation: {e}"))?;
            if let Some((existing_id, existing_fp)) = existing {
                if existing_fp != *fp {
                    return Err(format!(
                        "observation_id_conflict:{existing_id}:fingerprint_mismatch"
                    ));
                }
                continue;
            }
            let by_fp: Option<String> = tx
                .query_row(
                    "SELECT id FROM evidence_observation_v2 WHERE fingerprint_sha256 = ?1",
                    params![fp],
                    |row| row.get(0),
                )
                .optional()
                .map_err(|e| format!("lookup fingerprint: {e}"))?;
            if by_fp.is_some() {
                continue;
            }
            tx.execute(
                "INSERT INTO evidence_observation_v2
                 (id, fingerprint_sha256, issuer_id, security_id, payload_json, ingested_at_unix_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![
                    &obs.id,
                    fp,
                    &obs.issuer_id,
                    &obs.security_id,
                    payload,
                    obs.ingested_at_unix_ms
                ],
            )
            .map_err(|e| format!("insert observation: {e}"))?;
        }

        tx.execute(
            "INSERT INTO valuation_model_run
             (run_id, method, engine_version, method_policy_version, evidence_set_fp,
              issuer_id, security_id, replay_mode, result_json, created_at_unix_ms,
              identity_fingerprint, lifecycle_fingerprint,
              share_basis_id, identity_vintage, ticker, projection_key, supersedes_run_id,
              eps_observation_id, multiple_observation_id, canonical_command_sha256,
              decision_at_unix_ms, eps_share_basis_id)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22)",
            params![
                run_id,
                method,
                engine_version,
                method_policy_version,
                evidence_set_fp,
                issuer_id,
                security_id,
                replay_mode_s,
                result_json,
                created_at_unix_ms,
                identity_fingerprint,
                lifecycle_fp,
                share_basis_id,
                identity_vintage,
                ticker,
                projection_key,
                supersedes_run_id,
                eps_observation_id,
                multiple_observation_id,
                canonical_command_sha256,
                decision_at_unix_ms,
                eps_share_basis_id
            ],
        )
        .map_err(|e| format!("insert model run: {e}"))?;

        // Explicit economic role bindings (1B.3).
        tx.execute(
            "INSERT INTO valuation_run_role_binding (run_id, role, observation_id)
             VALUES (?1, ?2, ?3)",
            params![
                run_id,
                crate::analyst_method_import::ROLE_FORWARD_EPS,
                eps_observation_id
            ],
        )
        .map_err(|e| format!("insert eps role: {e}"))?;
        tx.execute(
            "INSERT INTO valuation_run_role_binding (run_id, role, observation_id)
             VALUES (?1, ?2, ?3)",
            params![
                run_id,
                crate::analyst_method_import::ROLE_FORWARD_PE,
                multiple_observation_id
            ],
        )
        .map_err(|e| format!("insert pe role: {e}"))?;

        let mut membership: Vec<(String, String)> = prepared
            .iter()
            .map(|(obs, fp, _)| (obs.id.clone(), fp.clone()))
            .collect();
        membership.sort_by(|a, b| a.1.cmp(&b.1).then_with(|| a.0.cmp(&b.0)));
        for (ordinal, (obs_id, obs_fp)) in membership.iter().enumerate() {
            tx.execute(
                "INSERT INTO valuation_run_observation
                 (run_id, observation_id, ordinal, observation_fingerprint)
                 VALUES (?1, ?2, ?3, ?4)",
                params![run_id, obs_id, ordinal as i64, obs_fp],
            )
            .map_err(|e| format!("insert run membership: {e}"))?;
        }

        for (revision_id, supersedes, obs_ids) in revision_groups {
            for obs_id in obs_ids {
                tx.execute(
                    "INSERT INTO evidence_revision_edge (revision_id, supersedes, observation_id)
                     VALUES (?1, ?2, ?3)",
                    params![revision_id, supersedes, obs_id],
                )
                .map_err(|e| format!("insert revision edge: {e}"))?;
            }
        }
        if let Some(prior) = supersedes_run_id {
            tx.execute(
                "INSERT INTO valuation_run_supersession
                 (run_id, supersedes_run_id, projection_key, created_at_unix_ms)
                 VALUES (?1, ?2, ?3, ?4)",
                params![run_id, prior, projection_key, created_at_unix_ms],
            )
            .map_err(|e| format!("insert run supersession: {e}"))?;
        }

        let command_sha256 = sha256_text(raw_command_json);
        tx.execute(
            "INSERT INTO valuation_import_command_attempt
             (attempted_run_id, outcome, raw_payload_json, payload_sha256, issuer_id, security_id,
              method, projection_key, supersedes_run_id, replay_mode, identity_fingerprint,
              share_basis_id, identity_vintage, ticker, reason_code, decision_at_unix_ms,
              canonical_command_sha256, processed_at_unix_ms)
             VALUES (?1, 'published', ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, NULL, ?14, ?15, ?16)",
            params![run_id, raw_command_json, command_sha256, issuer_id, security_id, method,
                projection_key, supersedes_run_id, replay_mode_s, identity_fingerprint,
                share_basis_id, identity_vintage, ticker, decision_at_unix_ms,
                canonical_command_sha256, created_at_unix_ms],
        ).map_err(|e| format!("insert successful command: {e}"))?;

        // After supersession the key was cleared; INSERT (no silent overwrite of foreign run).
        if let Some(key) = projection_key {
            tx.execute(
                "INSERT INTO valuation_current_projection (projection_key, run_id, updated_at_unix_ms)
                 VALUES (?1, ?2, ?3)",
                params![key, run_id, created_at_unix_ms],
            )
            .map_err(|e| format!("insert projection: {e}"))?;
        }

        tx.commit().map_err(|e| format!("commit lifecycle: {e}"))?;
        Ok(AnalystMethodLifecycleOutcome {
            evidence_set_fp,
            invalidated_prior_run_id: invalidated_prior,
            idempotent_replay: false,
        })
    }

    /// Atomic refuse-and-invalidate when a superseding revision is typed but not publishable.
    pub fn refuse_superseding_revision(
        &self,
        attempt: &RefusedAnalystMethodAttempt<'_>,
    ) -> Result<(), String> {
        if attempt.raw_command_json.trim().is_empty() || attempt.reason_code.trim().is_empty() {
            return Err("refused_attempt_missing_payload_or_reason".into());
        }
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin refuse supersede: {e}"))?;
        let current: Option<String> = tx
            .query_row(
                "SELECT run_id FROM valuation_current_projection WHERE projection_key = ?1",
                params![attempt.projection_key],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| format!("lookup projection: {e}"))?;
        match current.as_deref() {
            Some(cur) if cur == attempt.supersedes_run_id => {}
            Some(cur) => return Err(format!("supersedes_not_current_projection:{cur}")),
            None => return Err("supersedes_no_current_projection".into()),
        }
        let prior_meta: Option<(String, String, String)> = tx
            .query_row(
                "SELECT issuer_id, security_id, method FROM valuation_model_run WHERE run_id = ?1",
                params![attempt.supersedes_run_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .optional()
            .map_err(|e| format!("lookup prior: {e}"))?;
        match prior_meta {
            None => {
                return Err(format!(
                    "supersedes_run_missing:{}",
                    attempt.supersedes_run_id
                ))
            }
            Some((p_iss, p_sec, p_method)) => {
                if p_iss != attempt.issuer_id || p_sec != attempt.security_id {
                    return Err("supersedes_identity_mismatch".into());
                }
                if p_method != attempt.method {
                    return Err("supersedes_method_mismatch".into());
                }
            }
        }
        tx.execute(
            "INSERT INTO valuation_projection_invalidation
             (projection_key, reason_code, invalidated_at_unix_ms, prior_run_id)
             VALUES (?1, ?2, ?3, ?4)",
            params![
                attempt.projection_key,
                attempt.reason_code,
                attempt.processed_at_unix_ms,
                attempt.supersedes_run_id
            ],
        )
        .map_err(|e| format!("insert invalidation: {e}"))?;
        tx.execute(
            "DELETE FROM valuation_current_projection WHERE projection_key = ?1",
            params![attempt.projection_key],
        )
        .map_err(|e| format!("clear projection: {e}"))?;
        tx.execute(
            "INSERT INTO valuation_import_command_attempt
             (attempted_run_id, outcome, raw_payload_json, payload_sha256, issuer_id, security_id,
              method, projection_key, supersedes_run_id, replay_mode, identity_fingerprint,
              share_basis_id, identity_vintage, ticker, reason_code, decision_at_unix_ms,
              canonical_command_sha256, processed_at_unix_ms)
             VALUES (?1, 'refused', ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17)",
            params![
                attempt.attempted_run_id,
                attempt.raw_command_json,
                sha256_text(attempt.raw_command_json),
                attempt.issuer_id,
                attempt.security_id,
                attempt.method,
                attempt.projection_key,
                attempt.supersedes_run_id,
                attempt.replay_mode,
                attempt.identity_fingerprint,
                attempt.share_basis_id,
                attempt.identity_vintage,
                attempt.ticker,
                attempt.reason_code,
                attempt.decision_at_unix_ms,
                attempt.canonical_command_sha256,
                attempt.processed_at_unix_ms
            ],
        )
        .map_err(|e| format!("insert refused command: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit refuse supersede: {e}"))?;
        Ok(())
    }

    pub fn observation_count(&self) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row("SELECT COUNT(*) FROM evidence_observation_v2", [], |row| {
            row.get(0)
        })
        .map_err(|e| format!("count observations: {e}"))
    }

    pub fn model_run_count(&self) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row("SELECT COUNT(*) FROM valuation_model_run", [], |row| {
            row.get(0)
        })
        .map_err(|e| format!("count runs: {e}"))
    }

    /// Read a current candidate only when its persisted command is reconstructible and its
    /// engine, policy, identity and operational replay coordinates still match.
    pub fn eligible_current_projection_run_id(
        &self,
        key: &str,
        expected: CurrentProjectionEligibility<'_>,
    ) -> Result<Option<String>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let run_id: Option<String> = conn
            .query_row(
                "SELECT run_id FROM valuation_current_projection WHERE projection_key=?1",
                params![key],
                |r| r.get(0),
            )
            .optional()
            .map_err(|e| format!("eligible projection pointer: {e}"))?;
        let Some(run_id) = run_id else {
            return Ok(None);
        };
        if verify_current_candidate(&conn, key, expected, &run_id)? {
            Ok(Some(run_id))
        } else {
            Ok(None)
        }
    }

    /// Cache-only analyst-method publication read for Quant Lens / dossier view (1C).
    ///
    /// Resolves ticker → security → canonical projection; admits only fully reconstructible
    /// operational candidates under the current engine/policy/identity. Never writes legacy
    /// intrinsic scalars.
    pub fn load_analyst_method_publication(
        &self,
        ticker: &str,
    ) -> Result<AnalystMethodPublication, String> {
        let ticker = ticker.trim();
        if ticker.is_empty() {
            return Ok(AnalystMethodPublication::Absent);
        }
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut alias_stmt = conn
            .prepare(
                "SELECT security_id, ticker, identity_vintage
                 FROM security_ticker_alias WHERE upper(ticker) = upper(?1)
                 ORDER BY security_id, identity_vintage",
            )
            .map_err(|e| format!("publication resolve ticker: {e}"))?;
        let aliases: Vec<(String, String, String)> = alias_stmt
            .query_map(params![ticker], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
            .map_err(|e| format!("publication resolve ticker: {e}"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| format!("publication resolve ticker row: {e}"))?;
        drop(alias_stmt);
        if aliases.is_empty() {
            return Ok(AnalystMethodPublication::Absent);
        }
        // There is no mutable "current alias" pointer in schema v8. Therefore more than one
        // matching immutable alias is ambiguous, even when both rows name the same security.
        // Picking a lexical LIMIT 1 would publish an obsolete ticker/corporate-action vintage.
        if aliases.len() != 1 {
            return Ok(AnalystMethodPublication::Ineligible {
                run_id: None,
                projection_key: None,
                reason_code: "ambiguous_ticker_identity".into(),
            });
        }
        let (security_id, current_ticker, current_identity_vintage) = aliases[0].clone();
        let issuer_id: String = conn
            .query_row(
                "SELECT issuer_id FROM security WHERE security_id=?1",
                params![&security_id],
                |r| r.get(0),
            )
            .map_err(|e| format!("publication resolve issuer: {e}"))?;
        let projection_key = crate::analyst_method_import::canonical_projection_key(
            &issuer_id,
            &security_id,
            crate::analyst_method_import::METHOD_FORWARD_EARNINGS_MULTIPLE,
        );
        let run_id: Option<String> = conn
            .query_row(
                "SELECT run_id FROM valuation_current_projection WHERE projection_key=?1",
                params![&projection_key],
                |r| r.get(0),
            )
            .optional()
            .map_err(|e| format!("publication projection pointer: {e}"))?;
        let Some(run_id) = run_id else {
            return Ok(AnalystMethodPublication::Absent);
        };
        let basis_rows: Vec<(String, String, String)> = {
            let mut stmt = conn
                .prepare(
                    "SELECT basis_id, vintage_fingerprint, description FROM share_basis_vintage
                     WHERE security_id=?1 ORDER BY basis_id",
                )
                .map_err(|e| format!("publication resolve share basis: {e}"))?;
            let rows = stmt
                .query_map(params![&security_id], |r| {
                    Ok((r.get(0)?, r.get(1)?, r.get(2)?))
                })
                .map_err(|e| format!("publication resolve share basis: {e}"))?
                .collect::<Result<Vec<_>, _>>()
                .map_err(|e| format!("publication resolve share basis row: {e}"))?;
            rows
        };
        if basis_rows.len() != 1 {
            return Ok(AnalystMethodPublication::Ineligible {
                run_id: Some(run_id),
                projection_key: Some(projection_key),
                reason_code: if basis_rows.is_empty() {
                    "share_basis_not_seeded"
                } else {
                    "ambiguous_share_basis_vintage"
                }
                .into(),
            });
        }
        let (current_share_basis_id, current_share_basis_fp, current_share_basis_description) =
            &basis_rows[0];
        let (
            result_json,
            run_issuer_id,
            run_security_id,
            identity_fingerprint,
            run_share_basis_id,
            run_identity_vintage,
            run_ticker,
            decision_at,
            created_at,
            replay_mode,
            eps_obs,
            multiple_obs,
        ): (
            String,
            String,
            String,
            String,
            String,
            String,
            String,
            i64,
            i64,
            String,
            String,
            String,
        ) = conn
            .query_row(
                "SELECT result_json, issuer_id, security_id, identity_fingerprint,
                        share_basis_id, identity_vintage, ticker, decision_at_unix_ms,
                        created_at_unix_ms, replay_mode, eps_observation_id,
                        multiple_observation_id
                 FROM valuation_model_run WHERE run_id=?1",
                params![&run_id],
                |r| {
                    Ok((
                        r.get(0)?,
                        r.get(1)?,
                        r.get(2)?,
                        r.get(3)?,
                        r.get(4)?,
                        r.get(5)?,
                        r.get(6)?,
                        r.get(7)?,
                        r.get(8)?,
                        r.get(9)?,
                        r.get(10)?,
                        r.get(11)?,
                    ))
                },
            )
            .map_err(|e| format!("publication load run: {e}"))?;
        if run_issuer_id != issuer_id
            || run_security_id != security_id
            || !run_ticker.eq_ignore_ascii_case(&current_ticker)
            || run_identity_vintage != current_identity_vintage
            || run_share_basis_id != *current_share_basis_id
        {
            return Ok(AnalystMethodPublication::Ineligible {
                run_id: Some(run_id),
                projection_key: Some(projection_key),
                reason_code: "stale_identity_vintage".into(),
            });
        }
        let identity_fp = match load_identity_fingerprint_for_security(
            &conn,
            &security_id,
            Some(current_share_basis_id),
            Some(&current_identity_vintage),
            Some(&current_ticker),
        ) {
            Ok(fp) => fp,
            Err(_) => {
                return Ok(AnalystMethodPublication::Ineligible {
                    run_id: Some(run_id),
                    projection_key: Some(projection_key),
                    reason_code: "identity_not_seeded".into(),
                });
            }
        };
        if identity_fingerprint != identity_fp {
            return Ok(AnalystMethodPublication::Ineligible {
                run_id: Some(run_id),
                projection_key: Some(projection_key),
                reason_code: "identity_fingerprint_mismatch".into(),
            });
        }
        let expected = CurrentProjectionEligibility {
            engine_version: crate::forward_earnings_multiple::ENGINE_ID,
            method_policy_version: crate::forward_earnings_multiple::METHOD_POLICY_VERSION,
            identity_fingerprint: &identity_fp,
        };
        if !verify_current_candidate(&conn, &projection_key, expected, &run_id)? {
            return Ok(AnalystMethodPublication::Ineligible {
                run_id: Some(run_id),
                projection_key: Some(projection_key),
                reason_code: "not_eligible_for_publication".into(),
            });
        }
        let eps_observation_json: String = conn
            .query_row(
                "SELECT payload_json FROM evidence_observation_v2 WHERE id=?1",
                params![&eps_obs],
                |r| r.get(0),
            )
            .map_err(|e| format!("publication eps evidence: {e}"))?;
        let multiple_observation_json: String = conn
            .query_row(
                "SELECT payload_json FROM evidence_observation_v2 WHERE id=?1",
                params![&multiple_obs],
                |r| r.get(0),
            )
            .map_err(|e| format!("publication multiple evidence: {e}"))?;
        let raw_command_json: String = conn
            .query_row(
                "SELECT raw_payload_json FROM valuation_import_command_attempt
                 WHERE attempted_run_id=?1 AND outcome='published'",
                params![&run_id],
                |r| r.get(0),
            )
            .map_err(|e| format!("publication command envelope: {e}"))?;
        Ok(AnalystMethodPublication::Eligible(
            EligibleAnalystMethodRun {
                run_id,
                projection_key,
                result_json,
                issuer_id: run_issuer_id,
                security_id: run_security_id,
                identity_fingerprint,
                share_basis_id: run_share_basis_id,
                share_basis_vintage_fingerprint: current_share_basis_fp.clone(),
                share_basis_description: current_share_basis_description.clone(),
                identity_vintage: run_identity_vintage,
                ticker: run_ticker,
                decision_at_unix_ms: decision_at,
                created_at_unix_ms: created_at,
                replay_mode,
                eps_observation_id: eps_obs,
                multiple_observation_id: multiple_obs,
                eps_observation_json,
                multiple_observation_json,
                raw_command_json,
            },
        ))
    }

    #[cfg(test)]
    pub fn current_projection_run_id(&self, key: &str) -> Result<Option<String>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row(
            "SELECT run_id FROM valuation_current_projection WHERE projection_key = ?1",
            params![key],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| format!("projection: {e}"))
    }

    #[cfg(test)]
    pub fn import_command_attempt_count(&self) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row(
            "SELECT COUNT(*) FROM valuation_import_command_attempt",
            [],
            |r| r.get(0),
        )
        .map_err(|e| format!("count command attempts: {e}"))
    }

    pub fn resolve_security_id_by_ticker(&self, ticker: &str) -> Result<Option<String>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row(
            "SELECT security_id FROM security_ticker_alias WHERE upper(ticker) = upper(?1) LIMIT 1",
            params![ticker],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| format!("resolve ticker: {e}"))
    }

    pub fn share_basis_count(&self) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row("SELECT COUNT(*) FROM share_basis_vintage", [], |row| {
            row.get(0)
        })
        .map_err(|e| format!("count share basis: {e}"))
    }

    #[cfg(test)]
    pub fn corrupt_analyst_result_for_test(&self, run_id: &str) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "UPDATE valuation_model_run SET result_json='{not-json' WHERE run_id=?1",
            params![run_id],
        )
        .map_err(|e| format!("corrupt result fixture: {e}"))?;
        Ok(())
    }

    #[cfg(test)]
    pub fn corrupt_observation_payload_for_test(&self, observation_id: &str) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "UPDATE evidence_observation_v2 SET payload_json='{not-json' WHERE id=?1",
            params![observation_id],
        )
        .map_err(|e| format!("corrupt observation fixture: {e}"))?;
        Ok(())
    }

    /// Observation fingerprints for a run, ordered by membership ordinal.
    pub fn run_observation_membership(
        &self,
        run_id: &str,
    ) -> Result<Vec<(String, String)>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT observation_id, observation_fingerprint
                 FROM valuation_run_observation
                 WHERE run_id = ?1
                 ORDER BY ordinal ASC",
            )
            .map_err(|e| format!("prepare membership: {e}"))?;
        let rows = stmt
            .query_map(params![run_id], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| format!("query membership: {e}"))?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row.map_err(|e| format!("membership row: {e}"))?);
        }
        Ok(out)
    }

    pub fn model_run_identity_fingerprint(&self, run_id: &str) -> Result<Option<String>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row(
            "SELECT identity_fingerprint FROM valuation_model_run WHERE run_id = ?1",
            params![run_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| format!("run identity fp: {e}"))
    }

    #[cfg(test)]
    pub fn model_run_created_at(&self, run_id: &str) -> Result<Option<i64>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row(
            "SELECT created_at_unix_ms FROM valuation_model_run WHERE run_id=?1",
            params![run_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| format!("run created_at: {e}"))
    }

    pub fn model_run_evidence_set_fp(&self, run_id: &str) -> Result<Option<String>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row(
            "SELECT evidence_set_fp FROM valuation_model_run WHERE run_id = ?1",
            params![run_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| format!("run evidence set fp: {e}"))
    }

    /// Append-only revision edges for observations in a run (Slice 1B).
    #[cfg(test)]
    pub fn append_revision_edges(
        &self,
        revision_id: &str,
        supersedes: Option<&str>,
        observation_ids: &[String],
    ) -> Result<(), String> {
        if revision_id.trim().is_empty() {
            return Err("empty_revision_id".into());
        }
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin revision edges: {e}"))?;
        for obs_id in observation_ids {
            tx.execute(
                "INSERT INTO evidence_revision_edge (revision_id, supersedes, observation_id)
                 VALUES (?1, ?2, ?3)",
                params![revision_id, supersedes, obs_id],
            )
            .map_err(|e| format!("insert revision edge: {e}"))?;
        }
        tx.commit()
            .map_err(|e| format!("commit revision edges: {e}"))?;
        Ok(())
    }

    pub fn revision_edge_count(&self) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row("SELECT COUNT(*) FROM evidence_revision_edge", [], |row| {
            row.get(0)
        })
        .map_err(|e| format!("count revision edges: {e}"))
    }

    /// Append-only projection invalidation + clear current projection (history preserved).
    #[cfg(test)]
    pub fn invalidate_current_projection(
        &self,
        projection_key: &str,
        reason_code: &str,
        prior_run_id: Option<&str>,
        invalidated_at_unix_ms: i64,
    ) -> Result<(), String> {
        if projection_key.trim().is_empty() {
            return Err("empty_projection_key".into());
        }
        if reason_code.trim().is_empty() {
            return Err("empty_reason_code".into());
        }
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin invalidation: {e}"))?;
        let current: Option<String> = tx
            .query_row(
                "SELECT run_id FROM valuation_current_projection WHERE projection_key = ?1",
                params![projection_key],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| format!("lookup projection: {e}"))?;
        let prior = prior_run_id.map(|s| s.to_string()).or(current);
        tx.execute(
            "INSERT INTO valuation_projection_invalidation
             (projection_key, reason_code, invalidated_at_unix_ms, prior_run_id)
             VALUES (?1, ?2, ?3, ?4)",
            params![projection_key, reason_code, invalidated_at_unix_ms, prior],
        )
        .map_err(|e| format!("insert invalidation: {e}"))?;
        tx.execute(
            "DELETE FROM valuation_current_projection WHERE projection_key = ?1",
            params![projection_key],
        )
        .map_err(|e| format!("clear projection: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit invalidation: {e}"))?;
        Ok(())
    }

    pub fn invalidation_count(&self) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row(
            "SELECT COUNT(*) FROM valuation_projection_invalidation",
            [],
            |row| row.get(0),
        )
        .map_err(|e| format!("count invalidations: {e}"))
    }

    pub fn model_run_exists(&self, run_id: &str) -> Result<bool, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let found: Option<i64> = conn
            .query_row(
                "SELECT 1 FROM valuation_model_run WHERE run_id = ?1",
                params![run_id],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| format!("lookup run: {e}"))?;
        Ok(found.is_some())
    }

    /// Prove supersession authority without mutating state (1B.3).
    pub fn assert_supersession_authority(
        &self,
        projection_key: &str,
        supersedes_run_id: &str,
        issuer_id: &str,
        security_id: &str,
        method: &str,
    ) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let current: Option<String> = conn
            .query_row(
                "SELECT run_id FROM valuation_current_projection WHERE projection_key = ?1",
                params![projection_key],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| format!("lookup projection: {e}"))?;
        match current.as_deref() {
            Some(cur) if cur == supersedes_run_id => {}
            Some(cur) => return Err(format!("supersedes_not_current_projection:{cur}")),
            None => return Err("supersedes_no_current_projection".into()),
        }
        let prior_meta: Option<(String, String, String)> = conn
            .query_row(
                "SELECT issuer_id, security_id, method FROM valuation_model_run WHERE run_id = ?1",
                params![supersedes_run_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .optional()
            .map_err(|e| format!("lookup prior: {e}"))?;
        match prior_meta {
            None => return Err(format!("supersedes_run_missing:{supersedes_run_id}")),
            Some((p_iss, p_sec, p_method)) => {
                if p_iss != issuer_id || p_sec != security_id {
                    return Err("supersedes_identity_mismatch".into());
                }
                if p_method != method {
                    return Err("supersedes_method_mismatch".into());
                }
            }
        }
        Ok(())
    }

    pub fn run_role_bindings(&self, run_id: &str) -> Result<Vec<(String, String)>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT role, observation_id FROM valuation_run_role_binding
                 WHERE run_id = ?1 ORDER BY role",
            )
            .map_err(|e| format!("prepare roles: {e}"))?;
        let rows = stmt
            .query_map(params![run_id], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| format!("query roles: {e}"))?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row.map_err(|e| format!("role row: {e}"))?);
        }
        Ok(out)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnalystMethodLifecycleOutcome {
    pub evidence_set_fp: String,
    pub invalidated_prior_run_id: Option<String>,
    pub idempotent_replay: bool,
}

#[derive(Debug)]
struct PersistedCandidate {
    method: String,
    engine_version: String,
    policy_version: String,
    evidence_set_fp: String,
    issuer_id: String,
    security_id: String,
    replay_mode: String,
    result_json: String,
    created_at_unix_ms: i64,
    identity_fingerprint: String,
    lifecycle_fingerprint: String,
    share_basis_id: String,
    identity_vintage: String,
    ticker: String,
    projection_key: Option<String>,
    supersedes_run_id: Option<String>,
    eps_observation_id: String,
    multiple_observation_id: String,
    canonical_command_sha256: String,
    decision_at_unix_ms: i64,
    eps_share_basis_id: String,
}

fn verify_current_candidate(
    conn: &Connection,
    projection_key: &str,
    expected: CurrentProjectionEligibility<'_>,
    run_id: &str,
) -> Result<bool, String> {
    use crate::forward_earnings_multiple::{
        compute_forward_earnings_multiple, ForwardEarningsMultipleResult,
    };
    let run: Option<PersistedCandidate> = conn.query_row(
        "SELECT method,engine_version,method_policy_version,evidence_set_fp,issuer_id,security_id,
                replay_mode,result_json,created_at_unix_ms,identity_fingerprint,lifecycle_fingerprint,share_basis_id,
                identity_vintage,ticker,projection_key,supersedes_run_id,eps_observation_id,
                multiple_observation_id,canonical_command_sha256,decision_at_unix_ms,eps_share_basis_id
         FROM valuation_model_run WHERE run_id=?1", params![run_id], |r| Ok(PersistedCandidate {
            method:r.get(0)?, engine_version:r.get(1)?, policy_version:r.get(2)?, evidence_set_fp:r.get(3)?,
            issuer_id:r.get(4)?, security_id:r.get(5)?, replay_mode:r.get(6)?, result_json:r.get(7)?,
            created_at_unix_ms:r.get(8)?, identity_fingerprint:r.get(9)?, lifecycle_fingerprint:r.get(10)?, share_basis_id:r.get(11)?,
            identity_vintage:r.get(12)?, ticker:r.get(13)?, projection_key:r.get(14)?, supersedes_run_id:r.get(15)?,
            eps_observation_id:r.get(16)?, multiple_observation_id:r.get(17)?, canonical_command_sha256:r.get(18)?,
            decision_at_unix_ms:r.get(19)?, eps_share_basis_id:r.get(20)?,
        })).optional().map_err(|e| format!("verify run row: {e}"))?;
    let Some(run) = run else {
        return Ok(false);
    };
    if run.method != crate::analyst_method_import::METHOD_FORWARD_EARNINGS_MULTIPLE
        || run.engine_version != expected.engine_version
        || run.policy_version != expected.method_policy_version
        || run.identity_fingerprint != expected.identity_fingerprint
        || run.replay_mode != "operational"
        || run.projection_key.as_deref() != Some(projection_key)
        || run.share_basis_id.is_empty()
        || run.eps_share_basis_id != run.share_basis_id
        || run.decision_at_unix_ms <= 0
    {
        return Ok(false);
    }
    if projection_key
        != crate::analyst_method_import::canonical_projection_key(
            &run.issuer_id,
            &run.security_id,
            &run.method,
        )
    {
        return Ok(false);
    }

    let command_rows: i64 = conn.query_row(
        "SELECT COUNT(*) FROM valuation_import_command_attempt WHERE attempted_run_id=?1 AND outcome='published' AND processed_at_unix_ms>0",
        params![run_id], |r| r.get(0),
    ).map_err(|e| format!("verify command count: {e}"))?;
    if command_rows != 1 {
        return Ok(false);
    }
    let command: (String,String,Option<String>,Option<i64>,String,String,String,Option<String>,Option<String>,Option<String>,Option<String>,Option<String>,Option<String>,Option<String>,i64) = conn.query_row(
        "SELECT raw_payload_json,payload_sha256,canonical_command_sha256,decision_at_unix_ms,
                issuer_id,security_id,method,projection_key,supersedes_run_id,replay_mode,identity_fingerprint,share_basis_id,identity_vintage,ticker,processed_at_unix_ms
         FROM valuation_import_command_attempt WHERE attempted_run_id=?1 AND outcome='published'", params![run_id],
        |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get(5)?,r.get(6)?,r.get(7)?,r.get(8)?,r.get(9)?,r.get(10)?,r.get(11)?,r.get(12)?,r.get(13)?,r.get(14)?)),
    ).map_err(|e| format!("verify command row: {e}"))?;
    if sha256_text(&command.0) != command.1 {
        return Ok(false);
    }
    let canonical = match crate::analyst_method_import::canonical_command_sha256(&command.0) {
        Ok(v) => v,
        Err(_) => return Ok(false),
    };
    if command.2.as_deref() != Some(canonical.as_str())
        || canonical != run.canonical_command_sha256
        || command.3 != Some(run.decision_at_unix_ms)
        || command.4 != run.issuer_id
        || command.5 != run.security_id
        || command.6 != run.method
        || command.7.as_deref() != run.projection_key.as_deref()
        || command.8.as_deref() != run.supersedes_run_id.as_deref()
        || command.9.as_deref() != Some(run.replay_mode.as_str())
        || command.10.as_deref() != Some(run.identity_fingerprint.as_str())
        || command.11.as_deref() != Some(run.share_basis_id.as_str())
        || command.12.as_deref() != Some(run.identity_vintage.as_str())
        || command.13.as_deref() != Some(run.ticker.as_str())
        || command.14 != run.created_at_unix_ms
    {
        return Ok(false);
    }
    let parsed = match crate::analyst_method_import::parse_analyst_method_import_json(&command.0) {
        Ok(v) => v,
        Err(_) => return Ok(false),
    };
    if parsed.run_id != run_id
        || parsed.issuer_id != run.issuer_id
        || parsed.security_id != run.security_id
        || parsed.projection_key.as_deref() != run.projection_key.as_deref()
        || parsed.supersedes_run_id.as_deref() != run.supersedes_run_id.as_deref()
        || parsed.eps_observation_id != run.eps_observation_id
        || parsed.multiple_observation_id != run.multiple_observation_id
        || parsed.decision_at_unix_ms != run.decision_at_unix_ms
        || parsed.eps_share_basis_id != run.eps_share_basis_id
        || parsed.canonical_command_sha256 != run.canonical_command_sha256
    {
        return Ok(false);
    }
    if crate::analyst_method_import::admit_observations_for_decision(
        &parsed.observations,
        parsed.replay_mode,
        parsed.decision_at_unix_ms,
    )
    .is_err()
    {
        return Ok(false);
    }
    let security_currency: Option<String> = conn
        .query_row(
            "SELECT currency FROM security WHERE security_id=?1",
            params![&run.security_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("verify security currency: {e}"))?;
    if security_currency.as_deref() != Some(parsed.fem_input.currency.as_str()) {
        return Ok(false);
    }

    let mut stmt = conn.prepare(
        "SELECT m.observation_id,m.ordinal,m.observation_fingerprint,e.fingerprint_sha256,e.payload_json
         FROM valuation_run_observation m JOIN evidence_observation_v2 e ON e.id=m.observation_id
         WHERE m.run_id=?1 ORDER BY m.ordinal"
    ).map_err(|e| format!("verify membership prepare: {e}"))?;
    let rows = stmt
        .query_map(params![run_id], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, String>(4)?,
            ))
        })
        .map_err(|e| format!("verify membership query: {e}"))?;
    let mut prepared = Vec::new();
    let mut fps = Vec::new();
    for (expected_ordinal, row) in rows.enumerate() {
        let (id, ordinal, mfp, efp, payload) =
            row.map_err(|e| format!("verify membership row: {e}"))?;
        let obs: crate::valuation_evidence::EvidenceObservationV2 =
            match serde_json::from_str(&payload) {
                Ok(v) => v,
                Err(_) => return Ok(false),
            };
        let computed = obs.fingerprint_sha256();
        if ordinal != expected_ordinal as i64 || obs.id != id || computed != mfp || computed != efp
        {
            return Ok(false);
        }
        fps.push(computed.clone());
        prepared.push((obs, computed, payload));
    }
    let mut command_members: Vec<(String, String)> = parsed
        .observations
        .iter()
        .map(|o| (o.id.clone(), o.fingerprint_sha256()))
        .collect();
    let mut persisted_members: Vec<(String, String)> = prepared
        .iter()
        .map(|(o, fp, _)| (o.id.clone(), fp.clone()))
        .collect();
    command_members.sort();
    persisted_members.sort();
    if prepared.len() != parsed.observations.len()
        || command_members != persisted_members
        || crate::valuation_evidence::evidence_set_fingerprint(&fps) != run.evidence_set_fp
    {
        return Ok(false);
    }
    let mut expected_edges: Vec<(String, Option<String>, String)> = parsed
        .observations
        .iter()
        .map(|o| (o.revision_id.clone(), o.supersedes.clone(), o.id.clone()))
        .collect();
    expected_edges.sort();
    let mut edge_stmt=conn.prepare(
        "SELECT e.revision_id,e.supersedes,e.observation_id FROM evidence_revision_edge e
         WHERE e.observation_id IN (SELECT observation_id FROM valuation_run_observation WHERE run_id=?1)
            OR e.revision_id IN (
               SELECT x.revision_id FROM evidence_revision_edge x
               WHERE x.observation_id IN (SELECT observation_id FROM valuation_run_observation WHERE run_id=?1)
            )"
    ).map_err(|e|format!("verify revision edges prepare: {e}"))?;
    let edge_rows = edge_stmt
        .query_map(params![run_id], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, Option<String>>(1)?,
                r.get::<_, String>(2)?,
            ))
        })
        .map_err(|e| format!("verify revision edges query: {e}"))?;
    let mut persisted_edges = Vec::new();
    for edge in edge_rows {
        persisted_edges.push(edge.map_err(|e| format!("verify revision edge row: {e}"))?);
    }
    persisted_edges.sort();
    if persisted_edges != expected_edges {
        return Ok(false);
    }
    for (_, predecessor, _) in &expected_edges {
        if let Some(predecessor) = predecessor {
            if !verify_revision_ancestor_chain(conn, predecessor, &run.issuer_id, &run.security_id)?
            {
                return Ok(false);
            }
        }
    }
    if validate_analyst_role_bindings(
        &prepared,
        &run.eps_observation_id,
        &run.multiple_observation_id,
    )
    .is_err()
    {
        return Ok(false);
    }
    let bindings = {
        let mut s=conn.prepare("SELECT role,observation_id FROM valuation_run_role_binding WHERE run_id=?1 ORDER BY role").map_err(|e| format!("verify roles: {e}"))?;
        let rs = s
            .query_map(params![run_id], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
            })
            .map_err(|e| format!("verify roles query: {e}"))?;
        let mut v = Vec::new();
        for r in rs {
            v.push(r.map_err(|e| format!("verify role row: {e}"))?);
        }
        v
    };
    let expected_roles = vec![
        (
            crate::analyst_method_import::ROLE_FORWARD_EPS.to_string(),
            run.eps_observation_id.clone(),
        ),
        (
            crate::analyst_method_import::ROLE_FORWARD_PE.to_string(),
            run.multiple_observation_id.clone(),
        ),
    ];
    if bindings != expected_roles {
        return Ok(false);
    }
    let seeded = match load_identity_fingerprint_for_security(
        conn,
        &run.security_id,
        Some(&run.share_basis_id),
        Some(&run.identity_vintage),
        Some(&run.ticker),
    ) {
        Ok(v) => v,
        Err(_) => return Ok(false),
    };
    if seeded != run.identity_fingerprint {
        return Ok(false);
    }
    let computed_result = match compute_forward_earnings_multiple(&parsed.fem_input) {
        ForwardEarningsMultipleResult::Available(v) => {
            match crate::analyst_method_import::fem_result_json(&v, parsed.quality_label) {
                Ok(j) => j,
                Err(_) => return Ok(false),
            }
        }
        _ => return Ok(false),
    };
    if computed_result != run.result_json {
        return Ok(false);
    }
    let replay = parsed.replay_mode;
    let lifecycle = crate::analyst_method_import::lifecycle_fingerprint(
        &run.evidence_set_fp,
        &run.result_json,
        &run.canonical_command_sha256,
        &run.identity_fingerprint,
        &run.issuer_id,
        &run.security_id,
        &run.method,
        &run.engine_version,
        &run.policy_version,
        replay,
        run.decision_at_unix_ms,
        run.projection_key.as_deref(),
        run.supersedes_run_id.as_deref(),
        &run.eps_observation_id,
        &run.multiple_observation_id,
        &run.share_basis_id,
        &run.eps_share_basis_id,
        &run.identity_vintage,
        &run.ticker,
    );
    if lifecycle != run.lifecycle_fingerprint {
        return Ok(false);
    }
    let supersession_count: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM valuation_run_supersession WHERE run_id=?1",
            params![run_id],
            |r| r.get(0),
        )
        .map_err(|e| format!("verify supersession count: {e}"))?;
    match run.supersedes_run_id.as_deref() {
        None if supersession_count == 0 => {}
        Some(prior) if supersession_count == 1 => {
            let pair:(String,String)=conn.query_row("SELECT supersedes_run_id,projection_key FROM valuation_run_supersession WHERE run_id=?1",params![run_id],|r|Ok((r.get(0)?,r.get(1)?))).map_err(|e|format!("verify supersession: {e}"))?;
            if pair.0 != prior || pair.1 != projection_key {
                return Ok(false);
            }
        }
        _ => return Ok(false),
    }
    Ok(true)
}

fn verify_revision_ancestor_chain(
    conn: &Connection,
    start: &str,
    issuer_id: &str,
    security_id: &str,
) -> Result<bool, String> {
    let mut current = Some(start.to_string());
    let mut visited = std::collections::HashSet::new();
    while let Some(revision) = current {
        if !visited.insert(revision.clone()) {
            return Ok(false);
        }
        let mut stmt = conn
            .prepare(
                "SELECT e.supersedes,o.issuer_id,o.security_id FROM evidence_revision_edge e
             JOIN evidence_observation_v2 o ON o.id=e.observation_id WHERE e.revision_id=?1",
            )
            .map_err(|e| format!("verify ancestor prepare: {e}"))?;
        let rows = stmt
            .query_map(params![&revision], |r| {
                Ok((
                    r.get::<_, Option<String>>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, Option<String>>(2)?,
                ))
            })
            .map_err(|e| format!("verify ancestor query: {e}"))?;
        let mut intents = std::collections::HashSet::<Option<String>>::new();
        let mut count = 0usize;
        for row in rows {
            let (intent, row_issuer, row_security) =
                row.map_err(|e| format!("verify ancestor row: {e}"))?;
            count += 1;
            intents.insert(intent);
            if row_issuer != issuer_id || row_security.as_deref() != Some(security_id) {
                return Ok(false);
            }
        }
        if count == 0 || intents.len() != 1 {
            return Ok(false);
        }
        current = intents.into_iter().next().expect("one intent");
    }
    Ok(true)
}

fn sha256_text(value: &str) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(value.as_bytes());
    format!(
        "sha256:{}",
        digest
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>()
    )
}

fn validate_analyst_role_bindings(
    prepared: &[(
        crate::valuation_evidence::EvidenceObservationV2,
        String,
        String,
    )],
    eps_observation_id: &str,
    multiple_observation_id: &str,
) -> Result<(), String> {
    use crate::valuation_evidence::{EvidenceLane, EvidenceUnitV2};
    if eps_observation_id.trim().is_empty() || multiple_observation_id.trim().is_empty() {
        return Err("missing_analyst_role_binding".into());
    }
    if eps_observation_id == multiple_observation_id {
        return Err("eps_and_multiple_observation_must_differ".into());
    }
    let eps = prepared
        .iter()
        .find(|(o, _, _)| o.id == eps_observation_id)
        .map(|(o, _, _)| o)
        .ok_or("eps_observation_not_in_prepared_set")?;
    let multiple = prepared
        .iter()
        .find(|(o, _, _)| o.id == multiple_observation_id)
        .map(|(o, _, _)| o)
        .ok_or("multiple_observation_not_in_prepared_set")?;
    if eps.unit != EvidenceUnitV2::MoneyCents
        || !["gaap_diluted_eps", "diluted_eps", "normalized_diluted_eps"]
            .contains(&eps.metric_id.as_str())
        || eps.evidence_lane != EvidenceLane::AnalystStatedMethod
    {
        return Err("eps_role_semantic_mismatch".into());
    }
    if multiple.unit != EvidenceUnitV2::MultipleHundredths
        || !["forward_pe", "pe_forward", "forward_pe_multiple"]
            .contains(&multiple.metric_id.as_str())
        || multiple.evidence_lane != EvidenceLane::AnalystStatedMethod
    {
        return Err("multiple_role_semantic_mismatch".into());
    }
    if eps.issuer_id != multiple.issuer_id
        || eps.security_id != multiple.security_id
        || eps.economic_period_start != multiple.economic_period_start
        || eps.economic_period_end != multiple.economic_period_end
        || eps.lineage_group_id != multiple.lineage_group_id
        || eps.metric_basis != multiple.metric_basis
    {
        return Err("analyst_role_partition_mismatch".into());
    }
    Ok(())
}

/// Rebuild identity vintage fingerprint from seeded ledger rows (fail-closed).
/// When vintage coordinates are provided (1B.3), select exact rows — never ambiguous LIMIT 1.
fn load_identity_fingerprint_for_security(
    tx: &rusqlite::Connection,
    security_id: &str,
    share_basis_id: Option<&str>,
    identity_vintage: Option<&str>,
    ticker: Option<&str>,
) -> Result<String, String> {
    let (issuer_id, currency, share_class): (String, String, Option<String>) = tx
        .query_row(
            "SELECT issuer_id, currency, share_class_label FROM security WHERE security_id = ?1",
            params![security_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .map_err(|e| format!("security row: {e}"))?;
    let (cik, legal_name): (String, Option<String>) = tx
        .query_row(
            "SELECT cik, legal_name FROM issuer WHERE issuer_id = ?1",
            params![&issuer_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|e| format!("issuer row: {e}"))?;
    let (ticker, effective_from, identity_vintage): (String, String, String) =
        if let (Some(t), Some(iv)) = (ticker, identity_vintage) {
            tx.query_row(
                "SELECT ticker, effective_from, identity_vintage FROM security_ticker_alias
                 WHERE security_id = ?1 AND ticker = ?2 AND identity_vintage = ?3",
                params![security_id, t, iv],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .map_err(|_| "ticker_alias_vintage_not_seeded".to_string())?
        } else {
            tx.query_row(
                "SELECT ticker, effective_from, identity_vintage FROM security_ticker_alias
                 WHERE security_id = ?1 ORDER BY identity_vintage LIMIT 1",
                params![security_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .map_err(|_| "ticker_alias_not_seeded".to_string())?
        };
    let (basis_id, vintage_fp, description): (String, String, String) =
        if let Some(bid) = share_basis_id {
            tx.query_row(
                "SELECT basis_id, vintage_fingerprint, description FROM share_basis_vintage
                 WHERE security_id = ?1 AND basis_id = ?2",
                params![security_id, bid],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .map_err(|_| "share_basis_vintage_not_seeded".to_string())?
        } else {
            tx.query_row(
                "SELECT basis_id, vintage_fingerprint, description FROM share_basis_vintage
                 WHERE security_id = ?1 ORDER BY basis_id LIMIT 1",
                params![security_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .map_err(|_| "share_basis_not_seeded".to_string())?
        };

    let bundle = crate::issuer_identity::IdentityBundle {
        issuer: crate::issuer_identity::IssuerIdentity {
            issuer_id: issuer_id.clone(),
            cik,
            legal_name,
        },
        security: crate::issuer_identity::SecurityIdentity {
            security_id: security_id.into(),
            issuer_id,
            currency,
            share_class_label: share_class,
        },
        ticker_alias: crate::issuer_identity::TickerAlias {
            security_id: security_id.into(),
            ticker,
            effective_from,
            identity_vintage,
        },
        share_basis: crate::issuer_identity::ShareBasisVintage {
            basis_id,
            security_id: security_id.into(),
            vintage_fingerprint: vintage_fp,
            description,
        },
    };
    Ok(crate::issuer_identity::identity_vintage_fingerprint(
        &bundle,
    ))
}

fn run_migrations(conn: &Connection) -> Result<(), String> {
    let version: i32 = conn
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .map_err(|e| format!("read user_version: {e}"))?;
    if version > SQLITE_SCHEMA_VERSION {
        return Err(format!(
            "sqlite schema version {version} is newer than supported version {SQLITE_SCHEMA_VERSION}"
        ));
    }
    if version < 1 {
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin migration v1: {e}"))?;
        tx.execute_batch(EVIDENCE_LEDGER_SCHEMA_V1)
            .map_err(|e| format!("evidence ledger schema v1: {e}"))?;
        tx.pragma_update(None, "user_version", 1)
            .map_err(|e| format!("set user_version 1: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit migration v1: {e}"))?;
    }
    let version: i32 = conn
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .map_err(|e| format!("re-read user_version: {e}"))?;
    if version < 2 {
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin migration v2: {e}"))?;
        // identity_fingerprint required on new rows; existing v1 runs get empty placeholder.
        let has_col: bool = {
            let mut stmt = tx
                .prepare("PRAGMA table_info(valuation_model_run)")
                .map_err(|e| format!("table_info: {e}"))?;
            let cols = stmt
                .query_map([], |row| row.get::<_, String>(1))
                .map_err(|e| format!("table_info rows: {e}"))?;
            let mut found = false;
            for c in cols {
                if c.map_err(|e| format!("col: {e}"))? == "identity_fingerprint" {
                    found = true;
                    break;
                }
            }
            found
        };
        if !has_col {
            tx.execute(
                "ALTER TABLE valuation_model_run ADD COLUMN identity_fingerprint TEXT NOT NULL DEFAULT ''",
                [],
            )
            .map_err(|e| format!("add identity_fingerprint: {e}"))?;
        }
        tx.execute_batch(EVIDENCE_LEDGER_SCHEMA_V2)
            .map_err(|e| format!("evidence ledger schema v2: {e}"))?;
        tx.pragma_update(None, "user_version", 2)
            .map_err(|e| format!("set user_version 2: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit migration v2: {e}"))?;
    }
    let version: i32 = conn
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .map_err(|e| format!("re-read user_version after v2: {e}"))?;
    if version < 3 {
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin migration v3: {e}"))?;
        let has_col: bool = {
            let mut stmt = tx
                .prepare("PRAGMA table_info(valuation_model_run)")
                .map_err(|e| format!("table_info: {e}"))?;
            let cols = stmt
                .query_map([], |row| row.get::<_, String>(1))
                .map_err(|e| format!("table_info rows: {e}"))?;
            let mut found = false;
            for c in cols {
                if c.map_err(|e| format!("col: {e}"))? == "lifecycle_fingerprint" {
                    found = true;
                    break;
                }
            }
            found
        };
        if !has_col {
            tx.execute(
                "ALTER TABLE valuation_model_run ADD COLUMN lifecycle_fingerprint TEXT NOT NULL DEFAULT ''",
                [],
            )
            .map_err(|e| format!("add lifecycle_fingerprint: {e}"))?;
        }
        tx.pragma_update(None, "user_version", 3)
            .map_err(|e| format!("set user_version 3: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit migration v3: {e}"))?;
    }
    let version: i32 = conn
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .map_err(|e| format!("re-read user_version after v3: {e}"))?;
    if version < 4 {
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin migration v4: {e}"))?;
        for (col, sql) in [
            (
                "share_basis_id",
                "ALTER TABLE valuation_model_run ADD COLUMN share_basis_id TEXT NOT NULL DEFAULT ''",
            ),
            (
                "identity_vintage",
                "ALTER TABLE valuation_model_run ADD COLUMN identity_vintage TEXT NOT NULL DEFAULT ''",
            ),
            (
                "ticker",
                "ALTER TABLE valuation_model_run ADD COLUMN ticker TEXT NOT NULL DEFAULT ''",
            ),
            (
                "projection_key",
                "ALTER TABLE valuation_model_run ADD COLUMN projection_key TEXT",
            ),
            (
                "supersedes_run_id",
                "ALTER TABLE valuation_model_run ADD COLUMN supersedes_run_id TEXT",
            ),
            (
                "eps_observation_id",
                "ALTER TABLE valuation_model_run ADD COLUMN eps_observation_id TEXT NOT NULL DEFAULT ''",
            ),
            (
                "multiple_observation_id",
                "ALTER TABLE valuation_model_run ADD COLUMN multiple_observation_id TEXT NOT NULL DEFAULT ''",
            ),
        ] {
            let has_col: bool = {
                let mut stmt = tx
                    .prepare("PRAGMA table_info(valuation_model_run)")
                    .map_err(|e| format!("table_info: {e}"))?;
                let cols = stmt
                    .query_map([], |row| row.get::<_, String>(1))
                    .map_err(|e| format!("table_info rows: {e}"))?;
                let mut found = false;
                for c in cols {
                    if c.map_err(|e| format!("col: {e}"))? == col {
                        found = true;
                        break;
                    }
                }
                found
            };
            if !has_col {
                tx.execute(sql, [])
                    .map_err(|e| format!("add {col}: {e}"))?;
            }
        }
        tx.execute_batch(
            "CREATE TABLE IF NOT EXISTS valuation_run_role_binding (
                run_id TEXT NOT NULL,
                role TEXT NOT NULL,
                observation_id TEXT NOT NULL,
                PRIMARY KEY (run_id, role)
            );",
        )
        .map_err(|e| format!("role binding table: {e}"))?;
        tx.pragma_update(None, "user_version", 4)
            .map_err(|e| format!("set user_version 4: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit migration v4: {e}"))?;
    }
    let version: i32 = conn
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .map_err(|e| format!("re-read user_version after v4: {e}"))?;
    if version < 5 {
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin migration v5: {e}"))?;
        tx.execute_batch(
            "CREATE TABLE IF NOT EXISTS valuation_import_command_attempt (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                attempted_run_id TEXT NOT NULL,
                outcome TEXT NOT NULL CHECK(outcome IN ('published','refused')),
                raw_payload_json TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL,
                issuer_id TEXT NOT NULL,
                security_id TEXT NOT NULL,
                method TEXT NOT NULL,
                projection_key TEXT,
                supersedes_run_id TEXT,
                replay_mode TEXT,
                identity_fingerprint TEXT,
                share_basis_id TEXT,
                identity_vintage TEXT,
                ticker TEXT,
                reason_code TEXT,
                decision_at_unix_ms INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_import_attempt_run
              ON valuation_import_command_attempt(attempted_run_id, id);
            CREATE TABLE IF NOT EXISTS valuation_run_supersession (
                run_id TEXT PRIMARY KEY NOT NULL REFERENCES valuation_model_run(run_id),
                supersedes_run_id TEXT NOT NULL REFERENCES valuation_model_run(run_id),
                projection_key TEXT NOT NULL,
                created_at_unix_ms INTEGER NOT NULL,
                CHECK(run_id <> supersedes_run_id)
            );
            CREATE TRIGGER IF NOT EXISTS immutable_issuer_update
              BEFORE UPDATE ON issuer BEGIN SELECT RAISE(ABORT, 'immutable_identity:issuer'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_issuer_delete
              BEFORE DELETE ON issuer BEGIN SELECT RAISE(ABORT, 'immutable_identity:issuer'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_security_update
              BEFORE UPDATE ON security BEGIN SELECT RAISE(ABORT, 'immutable_identity:security'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_security_delete
              BEFORE DELETE ON security BEGIN SELECT RAISE(ABORT, 'immutable_identity:security'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_ticker_alias_update
              BEFORE UPDATE ON security_ticker_alias BEGIN SELECT RAISE(ABORT, 'immutable_identity:ticker'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_ticker_alias_delete
              BEFORE DELETE ON security_ticker_alias BEGIN SELECT RAISE(ABORT, 'immutable_identity:ticker'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_share_basis_update
              BEFORE UPDATE ON share_basis_vintage BEGIN SELECT RAISE(ABORT, 'immutable_identity:share_basis'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_share_basis_delete
              BEFORE DELETE ON share_basis_vintage BEGIN SELECT RAISE(ABORT, 'immutable_identity:share_basis'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_import_attempt_update
              BEFORE UPDATE ON valuation_import_command_attempt BEGIN SELECT RAISE(ABORT, 'append_only:import_attempt'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_import_attempt_delete
              BEFORE DELETE ON valuation_import_command_attempt BEGIN SELECT RAISE(ABORT, 'append_only:import_attempt'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_run_supersession_update
              BEFORE UPDATE ON valuation_run_supersession BEGIN SELECT RAISE(ABORT, 'append_only:run_supersession'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_run_supersession_delete
              BEFORE DELETE ON valuation_run_supersession BEGIN SELECT RAISE(ABORT, 'append_only:run_supersession'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_role_binding_update
              BEFORE UPDATE ON valuation_run_role_binding BEGIN SELECT RAISE(ABORT, 'append_only:role_binding'); END;
            CREATE TRIGGER IF NOT EXISTS immutable_role_binding_delete
              BEFORE DELETE ON valuation_run_role_binding BEGIN SELECT RAISE(ABORT, 'append_only:role_binding'); END;
            CREATE TRIGGER IF NOT EXISTS role_binding_requires_rows
              BEFORE INSERT ON valuation_run_role_binding
              WHEN NOT EXISTS (SELECT 1 FROM valuation_model_run WHERE run_id=NEW.run_id)
                OR NOT EXISTS (SELECT 1 FROM evidence_observation_v2 WHERE id=NEW.observation_id)
              BEGIN SELECT RAISE(ABORT, 'role_binding_orphan'); END;"
        ).map_err(|e| format!("schema v5: {e}"))?;

        // A v1-v3 current pointer cannot acquire exact v4 coordinates or economic roles by
        // inference. Preserve the run, append a quarantine event, and fail closed on read.
        tx.execute(
            "INSERT INTO valuation_projection_invalidation
             (projection_key, reason_code, invalidated_at_unix_ms, prior_run_id)
             SELECT p.projection_key, 'legacy_run_unreconstructible_v5', p.updated_at_unix_ms, p.run_id
             FROM valuation_current_projection p JOIN valuation_model_run r ON r.run_id=p.run_id
             WHERE r.projection_key IS NULL OR r.projection_key<>p.projection_key
                OR r.share_basis_id='' OR r.identity_vintage='' OR r.ticker=''
                OR r.eps_observation_id='' OR r.multiple_observation_id=''
                OR NOT EXISTS (SELECT 1 FROM valuation_run_role_binding b
                               WHERE b.run_id=r.run_id AND b.role='forward_eps'
                                 AND b.observation_id=r.eps_observation_id)
                OR NOT EXISTS (SELECT 1 FROM valuation_run_role_binding b
                               WHERE b.run_id=r.run_id AND b.role='forward_pe'
                                 AND b.observation_id=r.multiple_observation_id)
                OR NOT EXISTS (SELECT 1 FROM valuation_run_observation m
                               WHERE m.run_id=r.run_id AND m.observation_id=r.eps_observation_id)
                OR NOT EXISTS (SELECT 1 FROM valuation_run_observation m
                               WHERE m.run_id=r.run_id AND m.observation_id=r.multiple_observation_id)
                OR NOT EXISTS (SELECT 1 FROM valuation_import_command_attempt c
                               WHERE c.attempted_run_id=r.run_id AND c.outcome='published')",
            [],
        ).map_err(|e| format!("quarantine legacy projections: {e}"))?;
        tx.execute(
            "DELETE FROM valuation_current_projection WHERE run_id IN (
               SELECT r.run_id FROM valuation_model_run r
               WHERE r.projection_key IS NULL
                  OR r.share_basis_id='' OR r.identity_vintage='' OR r.ticker=''
                  OR r.eps_observation_id='' OR r.multiple_observation_id=''
                  OR NOT EXISTS (SELECT 1 FROM valuation_run_role_binding b WHERE b.run_id=r.run_id AND b.role='forward_eps' AND b.observation_id=r.eps_observation_id)
                  OR NOT EXISTS (SELECT 1 FROM valuation_run_role_binding b WHERE b.run_id=r.run_id AND b.role='forward_pe' AND b.observation_id=r.multiple_observation_id)
                  OR NOT EXISTS (SELECT 1 FROM valuation_run_observation m WHERE m.run_id=r.run_id AND m.observation_id=r.eps_observation_id)
                  OR NOT EXISTS (SELECT 1 FROM valuation_run_observation m WHERE m.run_id=r.run_id AND m.observation_id=r.multiple_observation_id)
                  OR NOT EXISTS (SELECT 1 FROM valuation_import_command_attempt c WHERE c.attempted_run_id=r.run_id AND c.outcome='published')
             ) OR EXISTS (
               SELECT 1 FROM valuation_model_run r
               WHERE r.run_id=valuation_current_projection.run_id
                 AND r.projection_key<>valuation_current_projection.projection_key
             )", [],
        ).map_err(|e| format!("clear legacy projections: {e}"))?;
        tx.pragma_update(None, "user_version", 5)
            .map_err(|e| format!("set user_version 5: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit migration v5: {e}"))?;
    }
    let version: i32 = conn
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .map_err(|e| format!("re-read user_version after v5: {e}"))?;
    if version < 6 {
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin migration v6: {e}"))?;
        for (col, sql) in [
            ("canonical_command_sha256", "ALTER TABLE valuation_model_run ADD COLUMN canonical_command_sha256 TEXT NOT NULL DEFAULT ''"),
            ("decision_at_unix_ms", "ALTER TABLE valuation_model_run ADD COLUMN decision_at_unix_ms INTEGER NOT NULL DEFAULT 0"),
            ("eps_share_basis_id", "ALTER TABLE valuation_model_run ADD COLUMN eps_share_basis_id TEXT NOT NULL DEFAULT ''"),
        ] {
            let has_col: bool = {
                let mut stmt = tx.prepare("PRAGMA table_info(valuation_model_run)").map_err(|e| format!("v6 table_info: {e}"))?;
                let rows = stmt.query_map([], |r| r.get::<_, String>(1)).map_err(|e| format!("v6 cols: {e}"))?;
                let mut found = false;
                for row in rows { if row.map_err(|e| format!("v6 col: {e}"))? == col { found=true; break; } }
                found
            };
            if !has_col { tx.execute(sql, []).map_err(|e| format!("add v6 {col}: {e}"))?; }
        }
        tx.execute_batch(
            "ALTER TABLE valuation_import_command_attempt RENAME TO valuation_import_command_attempt_v5;
             CREATE TABLE valuation_import_command_attempt (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                attempted_run_id TEXT NOT NULL,
                outcome TEXT NOT NULL CHECK(outcome IN ('published','refused')),
                raw_payload_json TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL,
                issuer_id TEXT NOT NULL, security_id TEXT NOT NULL, method TEXT NOT NULL,
                projection_key TEXT, supersedes_run_id TEXT, replay_mode TEXT,
                identity_fingerprint TEXT, share_basis_id TEXT, identity_vintage TEXT, ticker TEXT,
                reason_code TEXT, decision_at_unix_ms INTEGER,
                canonical_command_sha256 TEXT, processed_at_unix_ms INTEGER NOT NULL
             );
             INSERT INTO valuation_import_command_attempt
             (id,attempted_run_id,outcome,raw_payload_json,payload_sha256,issuer_id,security_id,method,
              projection_key,supersedes_run_id,replay_mode,identity_fingerprint,share_basis_id,
              identity_vintage,ticker,reason_code,decision_at_unix_ms,canonical_command_sha256,processed_at_unix_ms)
             SELECT id,attempted_run_id,outcome,raw_payload_json,payload_sha256,issuer_id,security_id,method,
              projection_key,supersedes_run_id,replay_mode,identity_fingerprint,share_basis_id,
              identity_vintage,ticker,reason_code,decision_at_unix_ms,NULL,decision_at_unix_ms
             FROM valuation_import_command_attempt_v5;
             DROP TABLE valuation_import_command_attempt_v5;
             CREATE INDEX idx_import_attempt_run ON valuation_import_command_attempt(attempted_run_id,id);
             CREATE TRIGGER immutable_import_attempt_update BEFORE UPDATE ON valuation_import_command_attempt
               BEGIN SELECT RAISE(ABORT, 'append_only:import_attempt'); END;
             CREATE TRIGGER immutable_import_attempt_delete BEFORE DELETE ON valuation_import_command_attempt
               BEGIN SELECT RAISE(ABORT, 'append_only:import_attempt'); END;"
        ).map_err(|e| format!("rebuild command ledger v6: {e}"))?;
        tx.execute(
            "INSERT INTO valuation_projection_invalidation(projection_key,reason_code,invalidated_at_unix_ms,prior_run_id)
             SELECT p.projection_key,'legacy_run_unreconstructible_v6',p.updated_at_unix_ms,p.run_id
             FROM valuation_current_projection p JOIN valuation_model_run r ON r.run_id=p.run_id
             WHERE r.canonical_command_sha256='' OR r.decision_at_unix_ms<=0 OR r.eps_share_basis_id=''", [],
        ).map_err(|e| format!("quarantine v6 current: {e}"))?;
        tx.execute(
            "DELETE FROM valuation_current_projection WHERE run_id IN
             (SELECT run_id FROM valuation_model_run WHERE canonical_command_sha256='' OR decision_at_unix_ms<=0 OR eps_share_basis_id='')", [],
        ).map_err(|e| format!("clear v6 current: {e}"))?;
        tx.pragma_update(None, "user_version", 6)
            .map_err(|e| format!("set user_version 6: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit migration v6: {e}"))?;
    }
    let version: i32 = conn
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .map_err(|e| format!("re-read user_version after v6: {e}"))?;
    if version < 7 {
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin migration v7: {e}"))?;
        tx.execute_batch(
            "CREATE TRIGGER IF NOT EXISTS immutable_revision_edge_update
               BEFORE UPDATE ON evidence_revision_edge BEGIN SELECT RAISE(ABORT,'append_only:revision_edge'); END;
             CREATE TRIGGER IF NOT EXISTS immutable_revision_edge_delete
               BEFORE DELETE ON evidence_revision_edge BEGIN SELECT RAISE(ABORT,'append_only:revision_edge'); END;"
        ).map_err(|e|format!("revision edge append-only v7: {e}"))?;
        tx.pragma_update(None, "user_version", 7)
            .map_err(|e| format!("set user_version 7: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit migration v7: {e}"))?;
    }
    let version: i32 = conn
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .map_err(|e| format!("re-read user_version after v7: {e}"))?;
    if version < 8 {
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("begin migration v8: {e}"))?;
        tx.execute_batch(
            "CREATE TRIGGER IF NOT EXISTS revision_edge_insert_consistent
             BEFORE INSERT ON evidence_revision_edge
             WHEN EXISTS (
               SELECT 1 FROM evidence_revision_edge e WHERE e.revision_id=NEW.revision_id
                 AND COALESCE(e.supersedes,'')<>COALESCE(NEW.supersedes,'')
             ) OR EXISTS (
               SELECT 1 FROM evidence_revision_edge e
               JOIN evidence_observation_v2 old_o ON old_o.id=e.observation_id
               JOIN evidence_observation_v2 new_o ON new_o.id=NEW.observation_id
               WHERE e.revision_id=NEW.revision_id
                 AND (old_o.issuer_id<>new_o.issuer_id OR COALESCE(old_o.security_id,'')<>COALESCE(new_o.security_id,''))
             ) BEGIN SELECT RAISE(ABORT,'revision_edge_insert_inconsistent'); END;"
        ).map_err(|e|format!("revision edge insert guard v8: {e}"))?;
        tx.pragma_update(None, "user_version", 8)
            .map_err(|e| format!("set user_version 8: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit migration v8: {e}"))?;
    }
    Ok(())
}

impl Db {
    pub fn load_fmp_forecast_cache(
        &self,
        provider_day: &str,
        symbol: &str,
    ) -> Result<Option<FmpCacheRecord>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "DELETE FROM fmp_forecast_cache WHERE provider_day < ?1",
            params![provider_day],
        )
        .map_err(|e| format!("prune FMP cache on read: {e}"))?;
        let mut stmt = conn
            .prepare(
                "SELECT fetched_at_epoch, payload_json
                 FROM fmp_forecast_cache
                 WHERE provider_day = ?1 AND symbol = ?2",
            )
            .map_err(|e| format!("prepare FMP cache load: {e}"))?;
        let mut rows = stmt
            .query(params![provider_day, symbol])
            .map_err(|e| format!("query FMP cache: {e}"))?;
        match rows
            .next()
            .map_err(|e| format!("read FMP cache row: {e}"))?
        {
            Some(row) => Ok(Some(FmpCacheRecord {
                fetched_at_epoch: row.get(0).map_err(|e| format!("FMP cache epoch: {e}"))?,
                payload_json: row.get(1).map_err(|e| format!("FMP cache payload: {e}"))?,
            })),
            None => Ok(None),
        }
    }

    pub fn save_fmp_forecast_cache(
        &self,
        provider_day: &str,
        symbol: &str,
        fetched_at_epoch: i64,
        payload_json: &str,
    ) -> Result<(), String> {
        let mut conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let tx = conn
            .transaction()
            .map_err(|e| format!("begin FMP cache save: {e}"))?;
        tx.execute(
            "DELETE FROM fmp_forecast_cache WHERE provider_day < ?1",
            params![provider_day],
        )
        .map_err(|e| format!("prune FMP cache: {e}"))?;
        tx.execute(
            "INSERT INTO fmp_forecast_cache
                (provider_day, symbol, fetched_at_epoch, payload_json)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(provider_day, symbol) DO UPDATE SET
                fetched_at_epoch = excluded.fetched_at_epoch,
                payload_json = excluded.payload_json",
            params![provider_day, symbol, fetched_at_epoch, payload_json],
        )
        .map_err(|e| format!("save FMP cache: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit FMP cache save: {e}"))
    }

    /// Reserve one outbound attempt. `None` means the provider-day limit was
    /// already reached and no network request is authorized.
    pub fn reserve_fmp_attempt(
        &self,
        provider_day: &str,
        limit: u16,
    ) -> Result<Option<u16>, String> {
        let mut conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let tx = conn
            .transaction()
            .map_err(|e| format!("begin FMP budget reservation: {e}"))?;
        tx.execute(
            "DELETE FROM fmp_request_budget WHERE provider_day < ?1",
            params![provider_day],
        )
        .map_err(|e| format!("prune FMP budget: {e}"))?;
        tx.execute(
            "INSERT OR IGNORE INTO fmp_request_budget (provider_day, attempts) VALUES (?1, 0)",
            params![provider_day],
        )
        .map_err(|e| format!("initialize FMP budget: {e}"))?;
        let changed = tx
            .execute(
                "UPDATE fmp_request_budget
                 SET attempts = attempts + 1
                 WHERE provider_day = ?1 AND attempts < ?2",
                params![provider_day, i64::from(limit)],
            )
            .map_err(|e| format!("reserve FMP budget: {e}"))?;
        let attempts: i64 = tx
            .query_row(
                "SELECT attempts FROM fmp_request_budget WHERE provider_day = ?1",
                params![provider_day],
                |row| row.get(0),
            )
            .map_err(|e| format!("read FMP budget: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit FMP budget reservation: {e}"))?;
        if changed == 0 {
            Ok(None)
        } else {
            Ok(Some(attempts.clamp(0, i64::from(u16::MAX)) as u16))
        }
    }

    pub fn fmp_attempts(&self, provider_day: &str) -> Result<u16, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let attempts = conn
            .query_row(
                "SELECT attempts FROM fmp_request_budget WHERE provider_day = ?1",
                params![provider_day],
                |row| row.get::<_, i64>(0),
            )
            .optional()
            .map_err(|e| format!("read FMP budget: {e}"))?
            .unwrap_or(0);
        Ok(attempts.clamp(0, i64::from(u16::MAX)) as u16)
    }

    pub fn load_tipranks_forecast_cache(
        &self,
        provider_month: &str,
        symbol: &str,
    ) -> Result<Option<TipRanksCacheRecord>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "DELETE FROM tipranks_forecast_cache WHERE provider_month < ?1",
            params![provider_month],
        )
        .map_err(|e| format!("prune TipRanks cache on read: {e}"))?;
        let mut stmt = conn
            .prepare(
                "SELECT fetched_at_epoch, payload_json
                 FROM tipranks_forecast_cache
                 WHERE provider_month = ?1 AND symbol = ?2",
            )
            .map_err(|e| format!("prepare TipRanks cache load: {e}"))?;
        let mut rows = stmt
            .query(params![provider_month, symbol])
            .map_err(|e| format!("query TipRanks cache: {e}"))?;
        match rows
            .next()
            .map_err(|e| format!("read TipRanks cache row: {e}"))?
        {
            Some(row) => Ok(Some(TipRanksCacheRecord {
                fetched_at_epoch: row
                    .get(0)
                    .map_err(|e| format!("TipRanks cache epoch: {e}"))?,
                payload_json: row
                    .get(1)
                    .map_err(|e| format!("TipRanks cache payload: {e}"))?,
            })),
            None => Ok(None),
        }
    }

    pub fn save_tipranks_forecast_cache(
        &self,
        provider_month: &str,
        symbol: &str,
        fetched_at_epoch: i64,
        payload_json: &str,
    ) -> Result<(), String> {
        let mut conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let tx = conn
            .transaction()
            .map_err(|e| format!("begin TipRanks cache save: {e}"))?;
        tx.execute(
            "DELETE FROM tipranks_forecast_cache WHERE provider_month < ?1",
            params![provider_month],
        )
        .map_err(|e| format!("prune TipRanks cache: {e}"))?;
        tx.execute(
            "INSERT INTO tipranks_forecast_cache
                (provider_month, symbol, fetched_at_epoch, payload_json)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(provider_month, symbol) DO UPDATE SET
                fetched_at_epoch = excluded.fetched_at_epoch,
                payload_json = excluded.payload_json",
            params![provider_month, symbol, fetched_at_epoch, payload_json],
        )
        .map_err(|e| format!("save TipRanks cache: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit TipRanks cache save: {e}"))
    }

    pub fn reserve_tipranks_attempt(
        &self,
        provider_month: &str,
        limit: u16,
    ) -> Result<Option<u16>, String> {
        let mut conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let tx = conn
            .transaction()
            .map_err(|e| format!("begin TipRanks budget reservation: {e}"))?;
        tx.execute(
            "DELETE FROM tipranks_request_budget WHERE provider_month < ?1",
            params![provider_month],
        )
        .map_err(|e| format!("prune TipRanks budget: {e}"))?;
        tx.execute(
            "INSERT OR IGNORE INTO tipranks_request_budget (provider_month, attempts) VALUES (?1, 0)",
            params![provider_month],
        )
        .map_err(|e| format!("initialize TipRanks budget: {e}"))?;
        let changed = tx
            .execute(
                "UPDATE tipranks_request_budget
                 SET attempts = attempts + 1
                 WHERE provider_month = ?1 AND attempts < ?2",
                params![provider_month, i64::from(limit)],
            )
            .map_err(|e| format!("reserve TipRanks budget: {e}"))?;
        let attempts: i64 = tx
            .query_row(
                "SELECT attempts FROM tipranks_request_budget WHERE provider_month = ?1",
                params![provider_month],
                |row| row.get(0),
            )
            .map_err(|e| format!("read TipRanks budget: {e}"))?;
        tx.commit()
            .map_err(|e| format!("commit TipRanks budget reservation: {e}"))?;
        if changed == 0 {
            Ok(None)
        } else {
            Ok(Some(attempts.clamp(0, i64::from(u16::MAX)) as u16))
        }
    }

    pub fn tipranks_attempts(&self, provider_month: &str) -> Result<u16, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let attempts = conn
            .query_row(
                "SELECT attempts FROM tipranks_request_budget WHERE provider_month = ?1",
                params![provider_month],
                |row| row.get::<_, i64>(0),
            )
            .optional()
            .map_err(|e| format!("read TipRanks budget: {e}"))?
            .unwrap_or(0);
        Ok(attempts.clamp(0, i64::from(u16::MAX)) as u16)
    }

    pub fn save_tipranks_usage_snapshot(
        &self,
        provider_month: &str,
        used: u16,
        limit_calls: u16,
        remaining: u16,
        resets_at_epoch: i64,
        reconciled_at_epoch: i64,
    ) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "DELETE FROM tipranks_usage_snapshot WHERE provider_month < ?1",
            params![provider_month],
        )
        .map_err(|e| format!("prune TipRanks usage: {e}"))?;
        conn.execute(
            "INSERT INTO tipranks_usage_snapshot
                (provider_month, used, limit_calls, remaining, resets_at_epoch, reconciled_at_epoch)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(provider_month) DO UPDATE SET
                used = excluded.used,
                limit_calls = excluded.limit_calls,
                remaining = excluded.remaining,
                resets_at_epoch = excluded.resets_at_epoch,
                reconciled_at_epoch = excluded.reconciled_at_epoch",
            params![
                provider_month,
                i64::from(used),
                i64::from(limit_calls),
                i64::from(remaining),
                resets_at_epoch,
                reconciled_at_epoch
            ],
        )
        .map_err(|e| format!("save TipRanks usage: {e}"))?;
        Ok(())
    }

    pub fn load_tipranks_usage_snapshot(
        &self,
        provider_month: &str,
    ) -> Result<Option<TipRanksUsageRecord>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "DELETE FROM tipranks_usage_snapshot WHERE provider_month < ?1",
            params![provider_month],
        )
        .map_err(|e| format!("prune TipRanks usage on read: {e}"))?;
        let mut stmt = conn
            .prepare(
                "SELECT used, limit_calls, remaining, resets_at_epoch, reconciled_at_epoch
                 FROM tipranks_usage_snapshot
                 WHERE provider_month = ?1",
            )
            .map_err(|e| format!("prepare TipRanks usage load: {e}"))?;
        let mut rows = stmt
            .query(params![provider_month])
            .map_err(|e| format!("query TipRanks usage: {e}"))?;
        match rows
            .next()
            .map_err(|e| format!("read TipRanks usage row: {e}"))?
        {
            Some(row) => {
                let used: i64 = row
                    .get(0)
                    .map_err(|e| format!("TipRanks usage used: {e}"))?;
                let limit_calls: i64 = row
                    .get(1)
                    .map_err(|e| format!("TipRanks usage limit: {e}"))?;
                let remaining: i64 = row
                    .get(2)
                    .map_err(|e| format!("TipRanks usage remaining: {e}"))?;
                Ok(Some(TipRanksUsageRecord {
                    used: used.clamp(0, i64::from(u16::MAX)) as u16,
                    limit_calls: limit_calls.clamp(0, i64::from(u16::MAX)) as u16,
                    remaining: remaining.clamp(0, i64::from(u16::MAX)) as u16,
                    resets_at_epoch: row
                        .get(3)
                        .map_err(|e| format!("TipRanks usage reset: {e}"))?,
                    reconciled_at_epoch: row
                        .get(4)
                        .map_err(|e| format!("TipRanks usage reconciled: {e}"))?,
                }))
            }
            None => Ok(None),
        }
    }

    /// Insert a batch of snapshots in a single transaction (much faster than row-by-row).
    /// ON CONFLICT (same symbol + captured_at) → ignore: we never overwrite history.
    pub fn insert_snapshots(&self, rows: &[SnapshotInsert]) -> Result<usize, String> {
        if rows.is_empty() {
            return Ok(0);
        }
        let mut conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let tx = conn.transaction().map_err(|e| format!("begin: {}", e))?;
        {
            let mut stmt = tx
                .prepare(
                    "INSERT OR IGNORE INTO snapshots \
                 (symbol, captured_at, market_price_cents, intrinsic_value_cents, gap_bps, \
                  decision, composite_score, fundamentals_score, technical_score, \
                  forecast_score, confidence) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
                )
                .map_err(|e| format!("prepare: {}", e))?;
            for r in rows {
                stmt.execute(params![
                    r.symbol,
                    r.captured_at,
                    r.market_price_cents,
                    r.intrinsic_value_cents,
                    r.gap_bps,
                    r.decision,
                    r.composite_score,
                    r.fundamentals_score,
                    r.technical_score,
                    r.forecast_score,
                    r.confidence,
                ])
                .map_err(|e| format!("insert {}: {}", r.symbol, e))?;
            }
        }
        tx.commit().map_err(|e| format!("commit: {}", e))?;
        Ok(rows.len())
    }

    /// Latest snapshot row per symbol (deterministic: `captured_at DESC, rowid DESC`).
    ///
    /// Callers apply business filters (gap, SP500 membership, ranking) after this —
    /// never filter gap on historical non-latest rows.
    pub fn latest_snapshot_per_symbol(&self) -> Result<Vec<LatestSnapshotRow>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT symbol, captured_at, market_price_cents, intrinsic_value_cents, gap_bps, \
                        decision, composite_score, fundamentals_score, technical_score, \
                        forecast_score, confidence \
                 FROM ( \
                     SELECT symbol, captured_at, market_price_cents, intrinsic_value_cents, gap_bps, \
                            decision, composite_score, fundamentals_score, technical_score, \
                            forecast_score, confidence, \
                            ROW_NUMBER() OVER ( \
                                PARTITION BY symbol \
                                ORDER BY captured_at DESC, rowid DESC \
                            ) AS rn \
                     FROM snapshots \
                 ) \
                 WHERE rn = 1",
            )
            .map_err(|e| format!("prepare latest snapshots: {e}"))?;
        let rows = stmt
            .query_map([], |r| {
                Ok(LatestSnapshotRow {
                    symbol: r.get(0)?,
                    captured_at: r.get(1)?,
                    market_price_cents: r.get(2)?,
                    intrinsic_value_cents: r.get(3)?,
                    gap_bps: r.get(4)?,
                    decision: r.get(5)?,
                    composite_score: r.get(6)?,
                    fundamentals_score: r.get(7)?,
                    technical_score: r.get(8)?,
                    forecast_score: r.get(9)?,
                    confidence: r.get(10)?,
                })
            })
            .map_err(|e| format!("query latest snapshots: {e}"))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("latest snapshot row: {e}"))?);
        }
        Ok(out)
    }

    /// Return all snapshots for a symbol over the trailing `days` days, oldest first.
    pub fn symbol_history(&self, symbol: &str, days: i64) -> Result<Vec<HistorySnapshot>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let cutoff = now_secs() - days * 86_400;
        let mut stmt = conn
            .prepare(
                "SELECT symbol, captured_at, market_price_cents, intrinsic_value_cents, gap_bps, \
                    decision, composite_score, fundamentals_score, technical_score, \
                    forecast_score, confidence \
             FROM snapshots \
             WHERE symbol = ?1 AND captured_at >= ?2 \
             ORDER BY captured_at ASC",
            )
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![symbol, cutoff], |r| {
                Ok(HistorySnapshot {
                    symbol: r.get(0)?,
                    captured_at: r.get(1)?,
                    market_price_cents: r.get(2)?,
                    intrinsic_value_cents: r.get(3)?,
                    gap_bps: r.get(4)?,
                    decision: r.get(5)?,
                    composite_score: r.get(6)?,
                    fundamentals_score: r.get(7)?,
                    technical_score: r.get(8)?,
                    forecast_score: r.get(9)?,
                    confidence: r.get(10)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    /// Backtest aggregate: for all symbols flagged `decision_state` at a point ≥ `days_ago` days back,
    /// compute the average return from that snapshot's market price to today.
    /// Returns (matched_symbols, mean_return_bps, median_return_bps, win_rate_bps).
    pub fn backtest(&self, decision: &str, days_ago: i64) -> Result<BacktestResult, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let cutoff_target = now_secs() - days_ago * 86_400;
        let cutoff_window = cutoff_target + 86_400; // accept any snapshot within ±1 day of target

        // For each symbol: find the snapshot closest to cutoff_target with the matching decision,
        // and the most recent snapshot overall.
        let mut stmt = conn
            .prepare(
                "WITH entries AS (
                 SELECT symbol, market_price_cents AS entry_price, captured_at AS entry_at,
                        ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY captured_at ASC) AS rn
                 FROM snapshots
                 WHERE decision = ?1
                   AND captured_at BETWEEN ?2 AND ?3
             ),
             latest AS (
                 SELECT symbol, market_price_cents AS now_price,
                        ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY captured_at DESC) AS rn
                 FROM snapshots
             )
             SELECT e.symbol, e.entry_price, e.entry_at, l.now_price
             FROM entries e
             JOIN latest l ON l.symbol = e.symbol AND l.rn = 1
             WHERE e.rn = 1",
            )
            .map_err(|e| format!("prepare: {}", e))?;

        let rows = stmt
            .query_map(
                params![decision, cutoff_target - 86_400, cutoff_window],
                |r| {
                    let entry_price: i64 = r.get(1)?;
                    let now_price: i64 = r.get(3)?;
                    let return_bps = if entry_price > 0 {
                        ((now_price - entry_price) as f64 / entry_price as f64 * 10_000.0) as i32
                    } else {
                        0
                    };
                    Ok(BacktestEntry {
                        symbol: r.get(0)?,
                        entry_price_cents: entry_price,
                        entry_at: r.get(2)?,
                        current_price_cents: now_price,
                        return_bps,
                    })
                },
            )
            .map_err(|e| format!("query: {}", e))?;

        let mut entries: Vec<BacktestEntry> = Vec::new();
        for r in rows {
            entries.push(r.map_err(|e| format!("row: {}", e))?);
        }

        if entries.is_empty() {
            return Ok(BacktestResult {
                decision: decision.to_string(),
                days_ago,
                sample_size: 0,
                mean_return_bps: 0,
                median_return_bps: 0,
                win_rate_pct: 0,
                top_winners: vec![],
                top_losers: vec![],
            });
        }

        let mean_return_bps =
            entries.iter().map(|e| e.return_bps as i64).sum::<i64>() as i32 / entries.len() as i32;
        let wins = entries.iter().filter(|e| e.return_bps > 0).count();
        let win_rate_pct = (wins * 100 / entries.len()) as i32;

        let mut sorted_returns: Vec<i32> = entries.iter().map(|e| e.return_bps).collect();
        sorted_returns.sort_unstable();
        let median_return_bps = sorted_returns[sorted_returns.len() / 2];

        let mut sorted = entries.clone();
        sorted.sort_by_key(|e| -e.return_bps);
        let top_winners = sorted.iter().take(5).cloned().collect();
        sorted.sort_by_key(|e| e.return_bps);
        let top_losers = sorted.iter().take(5).cloned().collect();

        Ok(BacktestResult {
            decision: decision.to_string(),
            days_ago,
            sample_size: entries.len() as i32,
            mean_return_bps,
            median_return_bps,
            win_rate_pct,
            top_winners,
            top_losers,
        })
    }

    /// Total rows in the snapshots table — useful for status UI.
    pub fn snapshot_count(&self) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row("SELECT COUNT(*) FROM snapshots", [], |r| r.get(0))
            .map_err(|e| format!("count: {}", e))
    }

    // ── Schwab reports ───────────────────────────────────────────────────────

    pub fn upsert_schwab_report(&self, r: &crate::schwab::SchwabReport) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "INSERT OR REPLACE INTO schwab_reports
             (symbol, company_name, exchange, rating, rating_label, percentile, previous_rating,
              report_date, data_as_of, price_at_report_cents, market_cap_billions, beta,
              sector, industry, price_volatility,
              growth_grade, quality_grade, sentiment_grade, stability_grade, valuation_grade,
              eps_forecast_y1, eps_forecast_y2, eps_growth_5yr_pct, esg_rating,
              source_filename, imported_at_epoch)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15,
                     ?16, ?17, ?18, ?19, ?20, ?21, ?22, ?23, ?24, ?25, ?26)",
            params![
                r.symbol,
                r.company_name,
                r.exchange,
                r.rating,
                r.rating_label,
                r.percentile,
                r.previous_rating,
                r.report_date,
                r.data_as_of,
                r.price_at_report_cents,
                r.market_cap_billions,
                r.beta,
                r.sector,
                r.industry,
                r.price_volatility,
                r.growth_grade,
                r.quality_grade,
                r.sentiment_grade,
                r.stability_grade,
                r.valuation_grade,
                r.eps_forecast_y1,
                r.eps_forecast_y2,
                r.eps_growth_5yr_pct,
                r.esg_rating,
                r.source_filename,
                r.imported_at_epoch,
            ],
        )
        .map_err(|e| format!("upsert schwab: {}", e))?;
        Ok(())
    }

    pub fn get_schwab_report(
        &self,
        symbol: &str,
    ) -> Result<Option<crate::schwab::SchwabReport>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn.prepare(
            "SELECT symbol, company_name, exchange, rating, rating_label, percentile, previous_rating,
                    report_date, data_as_of, price_at_report_cents, market_cap_billions, beta,
                    sector, industry, price_volatility,
                    growth_grade, quality_grade, sentiment_grade, stability_grade, valuation_grade,
                    eps_forecast_y1, eps_forecast_y2, eps_growth_5yr_pct, esg_rating,
                    source_filename, imported_at_epoch
             FROM schwab_reports WHERE symbol = ?1"
        ).map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![symbol], |row| {
                Ok(crate::schwab::SchwabReport {
                    symbol: row.get(0)?,
                    company_name: row.get(1)?,
                    exchange: row.get(2)?,
                    rating: row.get(3)?,
                    rating_label: row.get(4)?,
                    percentile: row.get(5)?,
                    previous_rating: row.get(6)?,
                    report_date: row.get(7)?,
                    data_as_of: row.get(8)?,
                    price_at_report_cents: row.get(9)?,
                    market_cap_billions: row.get(10)?,
                    beta: row.get(11)?,
                    sector: row.get(12)?,
                    industry: row.get(13)?,
                    price_volatility: row.get(14)?,
                    growth_grade: row.get(15)?,
                    quality_grade: row.get(16)?,
                    sentiment_grade: row.get(17)?,
                    stability_grade: row.get(18)?,
                    valuation_grade: row.get(19)?,
                    eps_forecast_y1: row.get(20)?,
                    eps_forecast_y2: row.get(21)?,
                    eps_growth_5yr_pct: row.get(22)?,
                    esg_rating: row.get(23)?,
                    cfra_stars: None,
                    morningstar_stars: None,
                    source_filename: row.get(24)?,
                    imported_at_epoch: row.get(25)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        for r in rows {
            return Ok(Some(r.map_err(|e| format!("row: {}", e))?));
        }
        Ok(None)
    }

    pub fn count_schwab_reports(&self) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row("SELECT COUNT(*) FROM schwab_reports", [], |r| r.get(0))
            .map_err(|e| format!("count schwab: {}", e))
    }

    pub fn delete_schwab_report(&self, symbol: &str) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "DELETE FROM schwab_reports WHERE symbol = ?1",
            params![symbol],
        )
        .map_err(|e| format!("delete schwab: {}", e))?;
        Ok(())
    }

    // ── Portfolio positions ──────────────────────────────────────────────────

    pub fn portfolio_add(
        &self,
        symbol: &str,
        quantity: f64,
        avg_cost_cents: i64,
        opened_at: Option<String>,
        notes: Option<String>,
    ) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let now = now_secs();
        conn.execute(
            "INSERT INTO portfolio_positions
             (symbol, quantity, avg_cost_cents, opened_at, notes, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6)",
            params![symbol, quantity, avg_cost_cents, opened_at, notes, now],
        )
        .map_err(|e| format!("portfolio add: {}", e))?;
        Ok(conn.last_insert_rowid())
    }

    pub fn portfolio_update(
        &self,
        id: i64,
        quantity: f64,
        avg_cost_cents: i64,
        opened_at: Option<String>,
        notes: Option<String>,
    ) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "UPDATE portfolio_positions
             SET quantity = ?2, avg_cost_cents = ?3, opened_at = ?4, notes = ?5, updated_at = ?6
             WHERE id = ?1",
            params![id, quantity, avg_cost_cents, opened_at, notes, now_secs()],
        )
        .map_err(|e| format!("portfolio update: {}", e))?;
        Ok(())
    }

    /// Insert or replace a position keyed by symbol (used by CSV bulk import).
    /// Returns true if a new row was created, false if an existing one was updated.
    pub fn portfolio_upsert_by_symbol(
        &self,
        symbol: &str,
        quantity: f64,
        avg_cost_cents: i64,
        opened_at: Option<String>,
    ) -> Result<bool, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let existing: Option<i64> = conn
            .query_row(
                "SELECT id FROM portfolio_positions WHERE symbol = ?1 LIMIT 1",
                params![symbol],
                |r| r.get(0),
            )
            .optional()
            .map_err(|e| format!("portfolio lookup: {}", e))?;
        let now = now_secs();
        match existing {
            Some(id) => {
                conn.execute(
                    "UPDATE portfolio_positions
                     SET quantity = ?2, avg_cost_cents = ?3, opened_at = ?4, updated_at = ?5
                     WHERE id = ?1",
                    params![id, quantity, avg_cost_cents, opened_at, now],
                )
                .map_err(|e| format!("portfolio upsert-update: {}", e))?;
                Ok(false)
            }
            None => {
                conn.execute(
                    "INSERT INTO portfolio_positions
                     (symbol, quantity, avg_cost_cents, opened_at, notes, created_at, updated_at)
                     VALUES (?1, ?2, ?3, ?4, NULL, ?5, ?5)",
                    params![symbol, quantity, avg_cost_cents, opened_at, now],
                )
                .map_err(|e| format!("portfolio upsert-insert: {}", e))?;
                Ok(true)
            }
        }
    }

    pub fn portfolio_delete(&self, id: i64) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute("DELETE FROM portfolio_positions WHERE id = ?1", params![id])
            .map_err(|e| format!("portfolio delete: {}", e))?;
        Ok(())
    }

    pub fn portfolio_list(&self) -> Result<Vec<PortfolioPosition>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT id, symbol, quantity, avg_cost_cents, opened_at, notes
             FROM portfolio_positions ORDER BY symbol ASC",
            )
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map([], |r| {
                Ok(PortfolioPosition {
                    id: r.get(0)?,
                    symbol: r.get(1)?,
                    quantity: r.get(2)?,
                    avg_cost_cents: r.get(3)?,
                    opened_at: r.get(4)?,
                    notes: r.get(5)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    // ── Investment journal ────────────────────────────────────────────────────

    pub fn journal_add(
        &self,
        symbol: &str,
        action: &str,
        thesis: Option<String>,
        price_cents: Option<i64>,
        setup_score: Option<i64>,
        setup_label: Option<String>,
    ) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "INSERT INTO journal_entries
             (symbol, action, thesis, price_cents, setup_score, setup_label, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                symbol,
                action,
                thesis,
                price_cents,
                setup_score,
                setup_label,
                now_secs()
            ],
        )
        .map_err(|e| format!("journal add: {}", e))?;
        Ok(conn.last_insert_rowid())
    }

    /// Record the outcome of a past decision (review/close).
    pub fn journal_close(
        &self,
        id: i64,
        outcome: Option<String>,
        exit_price_cents: Option<i64>,
    ) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "UPDATE journal_entries
             SET outcome = ?2, exit_price_cents = ?3, closed_at = ?4
             WHERE id = ?1",
            params![id, outcome, exit_price_cents, now_secs()],
        )
        .map_err(|e| format!("journal close: {}", e))?;
        Ok(())
    }

    pub fn journal_delete(&self, id: i64) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute("DELETE FROM journal_entries WHERE id = ?1", params![id])
            .map_err(|e| format!("journal delete: {}", e))?;
        Ok(())
    }

    pub fn journal_list(&self) -> Result<Vec<JournalEntry>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT id, symbol, action, thesis, price_cents, setup_score, setup_label,
                    created_at, outcome, exit_price_cents, closed_at
             FROM journal_entries ORDER BY created_at DESC",
            )
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map([], |r| {
                Ok(JournalEntry {
                    id: r.get(0)?,
                    symbol: r.get(1)?,
                    action: r.get(2)?,
                    thesis: r.get(3)?,
                    price_cents: r.get(4)?,
                    setup_score: r.get(5)?,
                    setup_label: r.get(6)?,
                    created_at: r.get(7)?,
                    outcome: r.get(8)?,
                    exit_price_cents: r.get(9)?,
                    closed_at: r.get(10)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    // ── Schwab auth (credentials + OAuth tokens) ──────────────────────────────

    pub fn schwab_auth_get(&self) -> Result<Option<SchwabAuth>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row(
            "SELECT app_key, secret, callback, access_token, refresh_token,
                    access_expires_at, refresh_expires_at
             FROM schwab_auth WHERE id = 1",
            [],
            |r| {
                Ok(SchwabAuth {
                    app_key: r.get(0)?,
                    secret: r.get(1)?,
                    callback: r.get(2)?,
                    access_token: r.get(3)?,
                    refresh_token: r.get(4)?,
                    access_expires_at: r.get(5)?,
                    refresh_expires_at: r.get(6)?,
                })
            },
        )
        .optional()
        .map_err(|e| format!("schwab auth get: {}", e))
    }

    /// Store/replace the developer app credentials, clearing any existing tokens.
    pub fn schwab_set_credentials(
        &self,
        app_key: &str,
        secret: &str,
        callback: &str,
    ) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "INSERT INTO schwab_auth (id, app_key, secret, callback, updated_at)
             VALUES (1, ?1, ?2, ?3, ?4)
             ON CONFLICT(id) DO UPDATE SET
               app_key = ?1, secret = ?2, callback = ?3,
               access_token = NULL, refresh_token = NULL,
               access_expires_at = NULL, refresh_expires_at = NULL,
               updated_at = ?4",
            params![app_key, secret, callback, now_secs()],
        )
        .map_err(|e| format!("schwab set creds: {}", e))?;
        Ok(())
    }

    pub fn schwab_set_tokens(
        &self,
        access: &str,
        refresh: &str,
        access_exp: i64,
        refresh_exp: i64,
    ) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "UPDATE schwab_auth SET
               access_token = ?1, refresh_token = ?2,
               access_expires_at = ?3, refresh_expires_at = ?4, updated_at = ?5
             WHERE id = 1",
            params![access, refresh, access_exp, refresh_exp, now_secs()],
        )
        .map_err(|e| format!("schwab set tokens: {}", e))?;
        Ok(())
    }

    pub fn schwab_clear(&self) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute("DELETE FROM schwab_auth WHERE id = 1", [])
            .map_err(|e| format!("schwab clear: {}", e))?;
        Ok(())
    }

    // ── Email notifications config ────────────────────────────────────────────

    pub fn email_config_get(&self) -> Result<EmailConfig, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let cfg = conn
            .query_row(
                "SELECT smtp_host, smtp_port, username, password, from_email, to_email,
                    enabled, daily_digest, digest_hour, instant_alerts, last_digest_date
             FROM email_config WHERE id = 1",
                [],
                |r| {
                    Ok(EmailConfig {
                        smtp_host: r.get(0)?,
                        smtp_port: r.get(1)?,
                        username: r.get(2)?,
                        password: r.get(3)?,
                        from_email: r.get(4)?,
                        to_email: r.get(5)?,
                        enabled: r.get::<_, i64>(6)? != 0,
                        daily_digest: r.get::<_, i64>(7)? != 0,
                        digest_hour: r.get(8)?,
                        instant_alerts: r.get::<_, i64>(9)? != 0,
                        last_digest_date: r.get(10)?,
                    })
                },
            )
            .optional()
            .map_err(|e| format!("email config get: {}", e))?;
        Ok(cfg.unwrap_or_else(|| EmailConfig {
            digest_hour: 8,
            daily_digest: true,
            instant_alerts: true,
            ..Default::default()
        }))
    }

    /// Upsert settings. `password` of None preserves the stored one (so the UI
    /// can save toggles without re-sending the secret each time).
    #[allow(clippy::too_many_arguments)]
    pub fn email_config_set(
        &self,
        smtp_host: &str,
        smtp_port: i64,
        username: &str,
        password: Option<String>,
        from_email: &str,
        to_email: &str,
        enabled: bool,
        daily_digest: bool,
        digest_hour: i64,
        instant_alerts: bool,
    ) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let now = now_secs();
        // Resolve password: keep existing when None given.
        let pass: Option<String> = match password {
            Some(p) => Some(p),
            None => conn
                .query_row("SELECT password FROM email_config WHERE id = 1", [], |r| {
                    r.get(0)
                })
                .optional()
                .map_err(|e| format!("email pass read: {}", e))?
                .flatten(),
        };
        conn.execute(
            "INSERT INTO email_config
              (id, smtp_host, smtp_port, username, password, from_email, to_email,
               enabled, daily_digest, digest_hour, instant_alerts, updated_at)
             VALUES (1, ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
             ON CONFLICT(id) DO UPDATE SET
               smtp_host=?1, smtp_port=?2, username=?3, password=?4, from_email=?5,
               to_email=?6, enabled=?7, daily_digest=?8, digest_hour=?9,
               instant_alerts=?10, updated_at=?11",
            params![
                smtp_host,
                smtp_port,
                username,
                pass,
                from_email,
                to_email,
                enabled as i64,
                daily_digest as i64,
                digest_hour,
                instant_alerts as i64,
                now
            ],
        )
        .map_err(|e| format!("email config set: {}", e))?;
        Ok(())
    }

    pub fn email_mark_digest_sent(&self, date: &str) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "UPDATE email_config SET last_digest_date = ?1 WHERE id = 1",
            params![date],
        )
        .map_err(|e| format!("email mark digest: {}", e))?;
        Ok(())
    }

    // ── Model accuracy (honest signal validation) ────────────────────────────

    /// For each historical snapshot at least `horizon_days` old, find the first
    /// snapshot of the same symbol ≥ horizon later and measure the realized
    /// return. Grouped two ways: by decision label and by composite-score bucket.
    ///
    /// To reduce autocorrelation from hourly snapshots, only the FIRST snapshot
    /// of each symbol per calendar day enters the sample.
    pub fn model_accuracy(&self, horizon_days: i64) -> Result<Vec<AccuracyRow>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let horizon_secs = horizon_days * 86_400;
        let mut out: Vec<AccuracyRow> = Vec::new();

        // Shared FROM/JOIN clause: daily-deduped entry snapshots joined to the
        // first snapshot at or after entry + horizon.
        const BODY: &str = "FROM snapshots s1
             JOIN snapshots s2
               ON s2.symbol = s1.symbol
              AND s2.captured_at = (
                  SELECT MIN(s3.captured_at) FROM snapshots s3
                  WHERE s3.symbol = s1.symbol
                    AND s3.captured_at >= s1.captured_at + ?1
              )
             WHERE s1.market_price_cents > 0
               AND s1.captured_at = (
                   SELECT MIN(s4.captured_at) FROM snapshots s4
                   WHERE s4.symbol = s1.symbol
                     AND date(s4.captured_at,'unixepoch') = date(s1.captured_at,'unixepoch')
               )";

        // Pass 1: by decision
        let sql_decision = format!(
            "SELECT s1.decision AS bucket, COUNT(*) AS n,
                    CAST(AVG((s2.market_price_cents - s1.market_price_cents) * 10000.0 / s1.market_price_cents) AS INTEGER) AS avg_bps,
                    CAST(SUM(CASE WHEN s2.market_price_cents > s1.market_price_cents THEN 1 ELSE 0 END) * 100.0 / COUNT(*) AS INTEGER) AS win_pct
             {BODY}
             GROUP BY s1.decision"
        );
        let mut stmt = conn
            .prepare(&sql_decision)
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![horizon_secs], |r| {
                Ok(AccuracyRow {
                    bucket_type: "decision".to_string(),
                    bucket: r.get(0)?,
                    samples: r.get(1)?,
                    avg_return_bps: r.get(2)?,
                    win_rate_pct: r.get(3)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }

        // Pass 2: by composite score bucket
        let sql_score = format!(
            "SELECT CASE
                      WHEN s1.composite_score >= 30 THEN 'score ≥30'
                      WHEN s1.composite_score >= 10 THEN 'score 10-29'
                      WHEN s1.composite_score >= 0  THEN 'score 0-9'
                      ELSE 'score <0'
                    END AS bucket,
                    COUNT(*) AS n,
                    CAST(AVG((s2.market_price_cents - s1.market_price_cents) * 10000.0 / s1.market_price_cents) AS INTEGER) AS avg_bps,
                    CAST(SUM(CASE WHEN s2.market_price_cents > s1.market_price_cents THEN 1 ELSE 0 END) * 100.0 / COUNT(*) AS INTEGER) AS win_pct
             {BODY}
             GROUP BY 1"
        );
        let mut stmt = conn
            .prepare(&sql_score)
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![horizon_secs], |r| {
                Ok(AccuracyRow {
                    bucket_type: "score".to_string(),
                    bucket: r.get(0)?,
                    samples: r.get(1)?,
                    avg_return_bps: r.get(2)?,
                    win_rate_pct: r.get(3)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }

        Ok(out)
    }

    // ── Congressional trades ─────────────────────────────────────────────────

    pub fn upsert_politician(&self, p: &crate::congress::PoliticianStub) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        // Try insert; if duplicate, fetch existing id.
        conn.execute(
            "INSERT OR IGNORE INTO politicians (full_name, last_name, first_name, chamber, state, district)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![p.full_name, p.last_name, p.first_name, p.chamber, p.state, p.district],
        ).map_err(|e| format!("politician insert: {}", e))?;
        conn.query_row(
            "SELECT id FROM politicians WHERE full_name = ?1",
            params![p.full_name],
            |r| r.get(0),
        )
        .map_err(|e| format!("politician lookup: {}", e))
    }

    pub fn insert_congressional_trade(
        &self,
        politician_id: i64,
        t: &crate::congress::CongressTrade,
    ) -> Result<bool, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let now = now_secs();
        let affected = conn
            .execute(
                "INSERT OR IGNORE INTO congressional_trades
             (doc_id, politician_id, owner, asset_name, symbol, asset_type,
              transaction_type, transaction_date, disclosure_date,
              amount_range_min, amount_range_max, cap_gains_over_200, imported_at_epoch)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
                params![
                    t.doc_id,
                    politician_id,
                    t.owner,
                    t.asset_name,
                    t.symbol,
                    t.asset_type,
                    t.transaction_type,
                    t.transaction_date,
                    t.disclosure_date,
                    t.amount_range_min,
                    t.amount_range_max,
                    t.cap_gains_over_200.map(|b| if b { 1 } else { 0 }),
                    now,
                ],
            )
            .map_err(|e| format!("insert trade: {}", e))?;
        Ok(affected > 0)
    }

    pub fn count_congressional_trades(&self) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row("SELECT COUNT(*) FROM congressional_trades", [], |r| {
            r.get(0)
        })
        .map_err(|e| format!("count: {}", e))
    }

    pub fn count_politicians(&self) -> Result<i64, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.query_row("SELECT COUNT(*) FROM politicians", [], |r| r.get(0))
            .map_err(|e| format!("count: {}", e))
    }

    /// Top tickers by congressional activity over the trailing `days` window.
    pub fn top_congress_tickers(
        &self,
        days: i64,
        limit: i64,
    ) -> Result<Vec<CongressTickerRow>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let cutoff = (now_secs() - days * 86_400) as i64;
        // We use disclosure_date for cutoff (ISO YYYY-MM-DD lexicographic comparable).
        let cutoff_iso = civil_from_days(cutoff / 86_400);
        let mut stmt = conn
            .prepare(
                "SELECT symbol,
                    SUM(CASE WHEN transaction_type='P' THEN 1 ELSE 0 END) AS buys,
                    SUM(CASE WHEN transaction_type LIKE 'S%' THEN 1 ELSE 0 END) AS sells,
                    COUNT(DISTINCT politician_id) AS politicians,
                    MAX(disclosure_date) AS last_disc,
                    SUM(COALESCE(amount_range_min,0)) AS amt_min,
                    SUM(COALESCE(amount_range_max,0)) AS amt_max
             FROM congressional_trades
             WHERE symbol IS NOT NULL
               AND symbol != ''
               AND (disclosure_date IS NULL OR disclosure_date >= ?1)
             GROUP BY symbol
             ORDER BY (buys + sells) DESC, politicians DESC
             LIMIT ?2",
            )
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![cutoff_iso, limit], |r| {
                Ok(CongressTickerRow {
                    symbol: r.get(0)?,
                    buy_count: r.get::<_, i64>(1)? as u32,
                    sell_count: r.get::<_, i64>(2)? as u32,
                    unique_politicians: r.get::<_, i64>(3)? as u32,
                    last_disclosure_date: r.get(4)?,
                    total_amount_min: r.get(5)?,
                    total_amount_max: r.get(6)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    /// All congressional trades for a specific ticker, ordered newest-first by disclosure.
    pub fn trades_for_symbol(
        &self,
        symbol: &str,
        limit: i64,
    ) -> Result<Vec<CongressTradeWithPolitician>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT ct.id, p.full_name, p.chamber, p.state, p.district,
                    ct.owner, ct.asset_name, ct.symbol, ct.asset_type,
                    ct.transaction_type, ct.transaction_date, ct.disclosure_date,
                    ct.amount_range_min, ct.amount_range_max
             FROM congressional_trades ct
             JOIN politicians p ON p.id = ct.politician_id
             WHERE ct.symbol = ?1
             ORDER BY ct.disclosure_date DESC, ct.id DESC
             LIMIT ?2",
            )
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![symbol, limit], |r| {
                Ok(CongressTradeWithPolitician {
                    trade_id: r.get(0)?,
                    politician_name: r.get(1)?,
                    chamber: r.get(2)?,
                    state: r.get(3)?,
                    district: r.get(4)?,
                    owner: r.get(5)?,
                    asset_name: r.get(6)?,
                    symbol: r.get(7)?,
                    asset_type: r.get(8)?,
                    transaction_type: r.get(9)?,
                    transaction_date: r.get(10)?,
                    disclosure_date: r.get(11)?,
                    amount_range_min: r.get(12)?,
                    amount_range_max: r.get(13)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    /// All distinct symbols with at least one congressional trade.
    pub fn congress_symbols(&self) -> Result<Vec<String>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT DISTINCT symbol FROM congressional_trades
             WHERE symbol IS NOT NULL AND symbol != ''",
            )
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map([], |r| r.get::<_, String>(0))
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    /// All trades for a symbol along with politician_id, transaction details.
    pub fn trades_with_meta_for_symbol(&self, symbol: &str) -> Result<Vec<TradeMeta>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT id, politician_id, transaction_type, disclosure_date,
                    amount_range_min, amount_range_max
             FROM congressional_trades
             WHERE symbol = ?1 AND disclosure_date IS NOT NULL",
            )
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![symbol], |r| {
                Ok(TradeMeta {
                    trade_id: r.get(0)?,
                    politician_id: r.get(1)?,
                    transaction_type: r.get(2)?,
                    disclosure_date: r.get(3)?,
                    amount_range_min: r.get(4)?,
                    amount_range_max: r.get(5)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    pub fn upsert_outcome(&self, o: &crate::congress_scoring::TradeOutcome) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "INSERT OR REPLACE INTO trade_outcomes
             (trade_id, base_price_cents,
              price_5d_cents, price_30d_cents, price_90d_cents, price_180d_cents,
              return_5d_bps, return_30d_bps, return_90d_bps, return_180d_bps,
              spy_return_5d_bps, spy_return_30d_bps, spy_return_90d_bps, spy_return_180d_bps,
              estimated_gain_180d_cents, computed_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16)",
            params![
                o.trade_id,
                o.base_price_cents,
                o.price_5d_cents,
                o.price_30d_cents,
                o.price_90d_cents,
                o.price_180d_cents,
                o.return_5d_bps,
                o.return_30d_bps,
                o.return_90d_bps,
                o.return_180d_bps,
                o.spy_return_5d_bps,
                o.spy_return_30d_bps,
                o.spy_return_90d_bps,
                o.spy_return_180d_bps,
                o.estimated_gain_180d_cents,
                now_secs(),
            ],
        )
        .map_err(|e| format!("upsert outcome: {}", e))?;
        Ok(())
    }

    /// Fetch all outcomes joined with trade metadata for politician aggregation.
    pub fn outcomes_for_politician(
        &self,
        politician_id: i64,
    ) -> Result<Vec<crate::congress_scoring::OutcomeForAggregation>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn.prepare(
            "SELECT ct.id, ct.transaction_type,
                    o.base_price_cents,
                    o.price_5d_cents, o.price_30d_cents, o.price_90d_cents, o.price_180d_cents,
                    o.return_5d_bps, o.return_30d_bps, o.return_90d_bps, o.return_180d_bps,
                    o.spy_return_5d_bps, o.spy_return_30d_bps, o.spy_return_90d_bps, o.spy_return_180d_bps,
                    o.estimated_gain_180d_cents
             FROM congressional_trades ct
             JOIN trade_outcomes o ON o.trade_id = ct.id
             WHERE ct.politician_id = ?1"
        ).map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![politician_id], |r| {
                let tt: String = r.get(1)?;
                let is_purchase = tt == "P";
                Ok(crate::congress_scoring::OutcomeForAggregation {
                    outcome: crate::congress_scoring::TradeOutcome {
                        trade_id: r.get(0)?,
                        base_price_cents: r.get(2)?,
                        price_5d_cents: r.get(3)?,
                        price_30d_cents: r.get(4)?,
                        price_90d_cents: r.get(5)?,
                        price_180d_cents: r.get(6)?,
                        return_5d_bps: r.get(7)?,
                        return_30d_bps: r.get(8)?,
                        return_90d_bps: r.get(9)?,
                        return_180d_bps: r.get(10)?,
                        spy_return_5d_bps: r.get(11)?,
                        spy_return_30d_bps: r.get(12)?,
                        spy_return_90d_bps: r.get(13)?,
                        spy_return_180d_bps: r.get(14)?,
                        estimated_gain_180d_cents: r.get(15)?,
                    },
                    is_purchase,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    pub fn upsert_politician_metrics(
        &self,
        m: &crate::congress_scoring::PoliticianMetrics,
    ) -> Result<(), String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        conn.execute(
            "INSERT OR REPLACE INTO politician_metrics
             (politician_id, total_trades, purchase_count, sale_count,
              avg_return_30d_bps, avg_return_90d_bps, avg_return_180d_bps,
              win_rate_30d_pct, win_rate_90d_pct, win_rate_180d_pct,
              avg_alpha_90d_bps, avg_alpha_180d_bps,
              estimated_total_gain_cents, confidence_score, qualifying_trades, updated_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16)",
            params![
                m.politician_id,
                m.total_trades,
                m.purchase_count,
                m.sale_count,
                m.avg_return_30d_bps,
                m.avg_return_90d_bps,
                m.avg_return_180d_bps,
                m.win_rate_30d_pct,
                m.win_rate_90d_pct,
                m.win_rate_180d_pct,
                m.avg_alpha_90d_bps,
                m.avg_alpha_180d_bps,
                m.estimated_total_gain_cents,
                m.confidence_score,
                m.qualifying_trades,
                now_secs(),
            ],
        )
        .map_err(|e| format!("upsert metrics: {}", e))?;
        Ok(())
    }

    /// Get all politician_ids that have at least one trade outcome.
    pub fn politicians_with_outcomes(&self) -> Result<Vec<i64>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT DISTINCT politician_id FROM congressional_trades
             WHERE id IN (SELECT trade_id FROM trade_outcomes)",
            )
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map([], |r| r.get::<_, i64>(0))
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    /// Politicians ranked with metrics (joined).
    pub fn top_politicians_with_metrics(
        &self,
        sort_key: &str,
        limit: i64,
    ) -> Result<Vec<PoliticianWithMetrics>, String> {
        let order_clause = match sort_key {
            "gain" => "m.estimated_total_gain_cents DESC",
            "alpha" => "m.avg_alpha_180d_bps DESC",
            "winrate" => "m.win_rate_180d_pct DESC",
            "trades" => "m.total_trades DESC",
            "confidence" => "m.confidence_score DESC",
            _ => "m.estimated_total_gain_cents DESC",
        };
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let sql = format!(
            "SELECT p.id, p.full_name, p.chamber, p.state, p.district,
                    m.total_trades, m.purchase_count, m.sale_count,
                    m.avg_return_30d_bps, m.avg_return_90d_bps, m.avg_return_180d_bps,
                    m.win_rate_30d_pct, m.win_rate_90d_pct, m.win_rate_180d_pct,
                    m.avg_alpha_90d_bps, m.avg_alpha_180d_bps,
                    m.estimated_total_gain_cents, m.confidence_score, m.qualifying_trades
             FROM politicians p
             JOIN politician_metrics m ON m.politician_id = p.id
             ORDER BY {} NULLS LAST
             LIMIT ?1",
            order_clause
        );
        let mut stmt = conn.prepare(&sql).map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![limit], |r| {
                Ok(PoliticianWithMetrics {
                    politician_id: r.get(0)?,
                    full_name: r.get(1)?,
                    chamber: r.get(2)?,
                    state: r.get(3)?,
                    district: r.get(4)?,
                    total_trades: r.get::<_, i64>(5)? as u32,
                    purchase_count: r.get::<_, i64>(6)? as u32,
                    sale_count: r.get::<_, i64>(7)? as u32,
                    avg_return_30d_bps: r.get(8)?,
                    avg_return_90d_bps: r.get(9)?,
                    avg_return_180d_bps: r.get(10)?,
                    win_rate_30d_pct: r.get(11)?,
                    win_rate_90d_pct: r.get(12)?,
                    win_rate_180d_pct: r.get(13)?,
                    avg_alpha_90d_bps: r.get(14)?,
                    avg_alpha_180d_bps: r.get(15)?,
                    estimated_total_gain_cents: r.get(16)?,
                    confidence_score: r.get::<_, i64>(17)? as u32,
                    qualifying_trades: r.get::<_, i64>(18)? as u32,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    /// All trades by a politician (with outcomes if available).
    pub fn trades_for_politician(
        &self,
        politician_id: i64,
        limit: i64,
    ) -> Result<Vec<PoliticianTradeRow>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn.prepare(
            "SELECT ct.id, ct.symbol, ct.asset_name, ct.owner, ct.transaction_type,
                    ct.transaction_date, ct.disclosure_date, ct.amount_range_min, ct.amount_range_max,
                    o.return_30d_bps, o.return_90d_bps, o.return_180d_bps, o.estimated_gain_180d_cents
             FROM congressional_trades ct
             LEFT JOIN trade_outcomes o ON o.trade_id = ct.id
             WHERE ct.politician_id = ?1
             ORDER BY ct.disclosure_date DESC, ct.id DESC
             LIMIT ?2"
        ).map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![politician_id, limit], |r| {
                Ok(PoliticianTradeRow {
                    trade_id: r.get(0)?,
                    symbol: r.get(1)?,
                    asset_name: r.get(2)?,
                    owner: r.get(3)?,
                    transaction_type: r.get(4)?,
                    transaction_date: r.get(5)?,
                    disclosure_date: r.get(6)?,
                    amount_range_min: r.get(7)?,
                    amount_range_max: r.get(8)?,
                    return_30d_bps: r.get(9)?,
                    return_90d_bps: r.get(10)?,
                    return_180d_bps: r.get(11)?,
                    estimated_gain_cents: r.get(12)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    pub fn get_politician_metrics(
        &self,
        politician_id: i64,
    ) -> Result<Option<PoliticianWithMetrics>, String> {
        let v = self.top_politicians_with_metrics_by_id(politician_id)?;
        Ok(v.into_iter().next())
    }

    fn top_politicians_with_metrics_by_id(
        &self,
        politician_id: i64,
    ) -> Result<Vec<PoliticianWithMetrics>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT p.id, p.full_name, p.chamber, p.state, p.district,
                    m.total_trades, m.purchase_count, m.sale_count,
                    m.avg_return_30d_bps, m.avg_return_90d_bps, m.avg_return_180d_bps,
                    m.win_rate_30d_pct, m.win_rate_90d_pct, m.win_rate_180d_pct,
                    m.avg_alpha_90d_bps, m.avg_alpha_180d_bps,
                    m.estimated_total_gain_cents, m.confidence_score, m.qualifying_trades
             FROM politicians p
             JOIN politician_metrics m ON m.politician_id = p.id
             WHERE p.id = ?1",
            )
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![politician_id], |r| {
                Ok(PoliticianWithMetrics {
                    politician_id: r.get(0)?,
                    full_name: r.get(1)?,
                    chamber: r.get(2)?,
                    state: r.get(3)?,
                    district: r.get(4)?,
                    total_trades: r.get::<_, i64>(5)? as u32,
                    purchase_count: r.get::<_, i64>(6)? as u32,
                    sale_count: r.get::<_, i64>(7)? as u32,
                    avg_return_30d_bps: r.get(8)?,
                    avg_return_90d_bps: r.get(9)?,
                    avg_return_180d_bps: r.get(10)?,
                    win_rate_30d_pct: r.get(11)?,
                    win_rate_90d_pct: r.get(12)?,
                    win_rate_180d_pct: r.get(13)?,
                    avg_alpha_90d_bps: r.get(14)?,
                    avg_alpha_180d_bps: r.get(15)?,
                    estimated_total_gain_cents: r.get(16)?,
                    confidence_score: r.get::<_, i64>(17)? as u32,
                    qualifying_trades: r.get::<_, i64>(18)? as u32,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }

    /// Most active politicians (by trade count).
    pub fn top_politicians_by_activity(
        &self,
        limit: i64,
    ) -> Result<Vec<PoliticianActivityRow>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut stmt = conn
            .prepare(
                "SELECT p.id, p.full_name, p.chamber, p.state, p.district,
                    COUNT(*) AS trades,
                    SUM(CASE WHEN ct.transaction_type='P' THEN 1 ELSE 0 END) AS buys,
                    SUM(CASE WHEN ct.transaction_type LIKE 'S%' THEN 1 ELSE 0 END) AS sells,
                    MAX(ct.disclosure_date) AS last_disc
             FROM politicians p
             JOIN congressional_trades ct ON ct.politician_id = p.id
             GROUP BY p.id
             ORDER BY trades DESC
             LIMIT ?1",
            )
            .map_err(|e| format!("prepare: {}", e))?;
        let rows = stmt
            .query_map(params![limit], |r| {
                Ok(PoliticianActivityRow {
                    politician_id: r.get(0)?,
                    full_name: r.get(1)?,
                    chamber: r.get(2)?,
                    state: r.get(3)?,
                    district: r.get(4)?,
                    trade_count: r.get::<_, i64>(5)? as u32,
                    buy_count: r.get::<_, i64>(6)? as u32,
                    sell_count: r.get::<_, i64>(7)? as u32,
                    last_disclosure_date: r.get(8)?,
                })
            })
            .map_err(|e| format!("query: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("row: {}", e))?);
        }
        Ok(out)
    }
}

#[derive(Debug, Serialize)]
pub struct CongressTickerRow {
    pub symbol: String,
    pub buy_count: u32,
    pub sell_count: u32,
    pub unique_politicians: u32,
    pub last_disclosure_date: Option<String>,
    pub total_amount_min: i64,
    pub total_amount_max: i64,
}

#[derive(Debug, Serialize)]
pub struct CongressTradeWithPolitician {
    pub trade_id: i64,
    pub politician_name: String,
    pub chamber: String,
    pub state: Option<String>,
    pub district: Option<String>,
    pub owner: Option<String>,
    pub asset_name: String,
    pub symbol: Option<String>,
    pub asset_type: Option<String>,
    pub transaction_type: String,
    pub transaction_date: Option<String>,
    pub disclosure_date: Option<String>,
    pub amount_range_min: Option<i64>,
    pub amount_range_max: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct PoliticianActivityRow {
    pub politician_id: i64,
    pub full_name: String,
    pub chamber: String,
    pub state: Option<String>,
    pub district: Option<String>,
    pub trade_count: u32,
    pub buy_count: u32,
    pub sell_count: u32,
    pub last_disclosure_date: Option<String>,
}

#[derive(Debug)]
pub struct TradeMeta {
    pub trade_id: i64,
    /// Loaded for join context; scoring currently keys off trade_id only.
    #[allow(dead_code)]
    pub politician_id: i64,
    pub transaction_type: String,
    pub disclosure_date: String,
    pub amount_range_min: Option<i64>,
    pub amount_range_max: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct PoliticianWithMetrics {
    pub politician_id: i64,
    pub full_name: String,
    pub chamber: String,
    pub state: Option<String>,
    pub district: Option<String>,
    pub total_trades: u32,
    pub purchase_count: u32,
    pub sale_count: u32,
    pub avg_return_30d_bps: Option<i32>,
    pub avg_return_90d_bps: Option<i32>,
    pub avg_return_180d_bps: Option<i32>,
    pub win_rate_30d_pct: Option<i32>,
    pub win_rate_90d_pct: Option<i32>,
    pub win_rate_180d_pct: Option<i32>,
    pub avg_alpha_90d_bps: Option<i32>,
    pub avg_alpha_180d_bps: Option<i32>,
    pub estimated_total_gain_cents: i64,
    pub confidence_score: u32,
    pub qualifying_trades: u32,
}

#[derive(Debug, Serialize)]
pub struct PortfolioPosition {
    pub id: i64,
    pub symbol: String,
    pub quantity: f64,
    pub avg_cost_cents: i64,
    pub opened_at: Option<String>,
    pub notes: Option<String>,
}

/// Internal email config. Holds the SMTP password — NOT serialized to frontend.
#[derive(Debug, Clone, Default)]
pub struct EmailConfig {
    pub smtp_host: Option<String>,
    pub smtp_port: Option<i64>,
    pub username: Option<String>,
    pub password: Option<String>,
    pub from_email: Option<String>,
    pub to_email: Option<String>,
    pub enabled: bool,
    pub daily_digest: bool,
    pub digest_hour: i64,
    pub instant_alerts: bool,
    pub last_digest_date: Option<String>,
}

/// Internal Schwab auth state. NOT serialized to the frontend (holds secrets).
#[derive(Debug, Clone)]
pub struct SchwabAuth {
    pub app_key: Option<String>,
    pub secret: Option<String>,
    pub callback: Option<String>,
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
    pub access_expires_at: Option<i64>,
    pub refresh_expires_at: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct JournalEntry {
    pub id: i64,
    pub symbol: String,
    pub action: String,
    pub thesis: Option<String>,
    pub price_cents: Option<i64>,
    pub setup_score: Option<i64>,
    pub setup_label: Option<String>,
    pub created_at: i64,
    pub outcome: Option<String>,
    pub exit_price_cents: Option<i64>,
    pub closed_at: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct AccuracyRow {
    pub bucket_type: String, // "decision" | "score"
    pub bucket: String,
    pub samples: i64,
    pub avg_return_bps: i64,
    pub win_rate_pct: i64,
}

#[derive(Debug, Serialize)]
pub struct PoliticianTradeRow {
    pub trade_id: i64,
    pub symbol: Option<String>,
    pub asset_name: String,
    pub owner: Option<String>,
    pub transaction_type: String,
    pub transaction_date: Option<String>,
    pub disclosure_date: Option<String>,
    pub amount_range_min: Option<i64>,
    pub amount_range_max: Option<i64>,
    pub return_30d_bps: Option<i32>,
    pub return_90d_bps: Option<i32>,
    pub return_180d_bps: Option<i32>,
    pub estimated_gain_cents: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct BacktestEntry {
    pub symbol: String,
    pub entry_price_cents: i64,
    pub entry_at: i64,
    pub current_price_cents: i64,
    pub return_bps: i32,
}

#[derive(Debug, Clone, Serialize)]
pub struct BacktestResult {
    pub decision: String,
    pub days_ago: i64,
    pub sample_size: i32,
    pub mean_return_bps: i32,
    pub median_return_bps: i32,
    pub win_rate_pct: i32,
    pub top_winners: Vec<BacktestEntry>,
    pub top_losers: Vec<BacktestEntry>,
}

/// Howard Hinnant's algorithm: epoch days → "YYYY-MM-DD".
fn civil_from_days(days: i64) -> String {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 {
        mp + 3
    } else {
        mp.saturating_sub(9)
    };
    let y = y + if m <= 2 { 1 } else { 0 };
    format!("{:04}-{:02}-{:02}", y, m, d)
}

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod fmp_tests {
    use super::*;

    #[test]
    fn fmp_cache_is_replaceable_and_scoped_to_the_provider_day() {
        let db = Db::open_in_memory().unwrap();
        db.save_fmp_forecast_cache("2026-07-28", "AAPL", 100, r#"{"old":true}"#)
            .unwrap();
        db.save_fmp_forecast_cache("2026-07-29", "MSFT", 200, r#"{"new":true}"#)
            .unwrap();

        let current = db
            .load_fmp_forecast_cache("2026-07-29", "MSFT")
            .unwrap()
            .unwrap();
        assert_eq!(current.fetched_at_epoch, 200);
        assert_eq!(current.payload_json, r#"{"new":true}"#);
        assert!(db
            .load_fmp_forecast_cache("2026-07-28", "AAPL")
            .unwrap()
            .is_none());
    }

    #[test]
    fn reading_a_new_provider_day_prunes_licensed_rows_without_a_fetch() {
        let db = Db::open_in_memory().unwrap();
        db.save_fmp_forecast_cache("2026-07-28", "AAPL", 100, "{}")
            .unwrap();

        assert!(db
            .load_fmp_forecast_cache("2026-07-29", "MSFT")
            .unwrap()
            .is_none());
        assert!(db
            .load_fmp_forecast_cache("2026-07-28", "AAPL")
            .unwrap()
            .is_none());
    }

    #[test]
    fn fmp_budget_reservation_never_exceeds_the_limit() {
        let db = Db::open_in_memory().unwrap();

        assert_eq!(db.reserve_fmp_attempt("2026-07-29", 2).unwrap(), Some(1));
        assert_eq!(db.reserve_fmp_attempt("2026-07-29", 2).unwrap(), Some(2));
        assert_eq!(db.reserve_fmp_attempt("2026-07-29", 2).unwrap(), None);
        assert_eq!(db.fmp_attempts("2026-07-29").unwrap(), 2);
    }

    #[test]
    fn fmp_budget_discards_previous_provider_days() {
        let db = Db::open_in_memory().unwrap();
        db.reserve_fmp_attempt("2026-07-28", 250).unwrap();
        db.reserve_fmp_attempt("2026-07-29", 250).unwrap();

        assert_eq!(db.fmp_attempts("2026-07-28").unwrap(), 0);
        assert_eq!(db.fmp_attempts("2026-07-29").unwrap(), 1);
    }

    #[test]
    fn late_previous_day_cache_write_cannot_prune_the_new_day() {
        let db = Db::open_in_memory().unwrap();
        db.save_fmp_forecast_cache("2026-07-29", "MSFT", 200, r#"{"new":true}"#)
            .unwrap();
        db.save_fmp_forecast_cache("2026-07-28", "AAPL", 100, r#"{"old":true}"#)
            .unwrap();

        let current = db
            .load_fmp_forecast_cache("2026-07-29", "MSFT")
            .unwrap()
            .unwrap();
        assert_eq!(current.payload_json, r#"{"new":true}"#);
    }

    #[test]
    fn late_previous_day_budget_write_cannot_prune_the_new_day() {
        let db = Db::open_in_memory().unwrap();
        assert_eq!(db.reserve_fmp_attempt("2026-07-29", 250).unwrap(), Some(1));
        assert_eq!(db.reserve_fmp_attempt("2026-07-28", 250).unwrap(), Some(1));

        assert_eq!(db.fmp_attempts("2026-07-29").unwrap(), 1);
        assert_eq!(db.reserve_fmp_attempt("2026-07-29", 250).unwrap(), Some(2));
    }

    #[test]
    fn fmp_attempts_propagates_sqlite_failures_but_missing_rows_are_zero() {
        let db = Db::open_in_memory().unwrap();
        assert_eq!(db.fmp_attempts("2026-07-29").unwrap(), 0);
        db.conn
            .lock()
            .unwrap()
            .execute("DROP TABLE fmp_request_budget", [])
            .unwrap();

        assert!(db.fmp_attempts("2026-07-29").is_err());
    }
}

#[cfg(test)]
mod tipranks_tests {
    use super::*;

    #[test]
    fn tipranks_cache_is_replaceable_and_scoped_to_the_provider_month() {
        let db = Db::open_in_memory().unwrap();
        db.save_tipranks_forecast_cache("2026-06", "AAPL", 100, r#"{"old":true}"#)
            .unwrap();
        db.save_tipranks_forecast_cache("2026-07", "MSFT", 200, r#"{"new":true}"#)
            .unwrap();

        let current = db
            .load_tipranks_forecast_cache("2026-07", "MSFT")
            .unwrap()
            .unwrap();
        assert_eq!(current.fetched_at_epoch, 200);
        assert!(db
            .load_tipranks_forecast_cache("2026-06", "AAPL")
            .unwrap()
            .is_none());
    }

    #[test]
    fn tipranks_budget_reservation_never_exceeds_the_limit() {
        let db = Db::open_in_memory().unwrap();
        assert_eq!(db.reserve_tipranks_attempt("2026-07", 2).unwrap(), Some(1));
        assert_eq!(db.reserve_tipranks_attempt("2026-07", 2).unwrap(), Some(2));
        assert_eq!(db.reserve_tipranks_attempt("2026-07", 2).unwrap(), None);
        assert_eq!(db.tipranks_attempts("2026-07").unwrap(), 2);
    }

    #[test]
    fn tipranks_budget_discards_previous_provider_months() {
        let db = Db::open_in_memory().unwrap();
        db.reserve_tipranks_attempt("2026-06", 50).unwrap();
        db.reserve_tipranks_attempt("2026-07", 50).unwrap();
        assert_eq!(db.tipranks_attempts("2026-06").unwrap(), 0);
        assert_eq!(db.tipranks_attempts("2026-07").unwrap(), 1);
    }

    #[test]
    fn tipranks_usage_snapshot_is_replaceable_per_month() {
        let db = Db::open_in_memory().unwrap();
        db.save_tipranks_usage_snapshot("2026-07", 10, 50, 40, 1_775_001_600, 100)
            .unwrap();
        db.save_tipranks_usage_snapshot("2026-07", 25, 50, 25, 1_775_001_600, 200)
            .unwrap();
        let snap = db.load_tipranks_usage_snapshot("2026-07").unwrap().unwrap();
        assert_eq!(snap.used, 25);
        assert_eq!(snap.remaining, 25);
        assert_eq!(snap.reconciled_at_epoch, 200);
    }
}

#[cfg(test)]
mod qa_latest_snapshot_tests {
    use super::*;

    fn insert(db: &Db, symbol: &str, captured_at: i64, gap_bps: i32, composite_score: i32) {
        db.insert_snapshots(&[SnapshotInsert {
            symbol,
            captured_at,
            market_price_cents: 10_000,
            intrinsic_value_cents: 12_000,
            gap_bps,
            decision: "Act",
            composite_score,
            fundamentals_score: Some(50),
            technical_score: Some(50),
            forecast_score: Some(50),
            confidence: "High",
        }])
        .unwrap();
    }

    #[test]
    fn latest_per_symbol_ignores_older_qualifying_rows() {
        let db = Db::open_in_memory().unwrap();
        // Older row qualifies; latest does not.
        insert(&db, "CI", 100, 5000, 90);
        insert(&db, "CI", 200, 100, 10);
        insert(&db, "AAPL", 200, 3000, 80);

        let latest = db.latest_snapshot_per_symbol().unwrap();
        let ci = latest.iter().find(|r| r.symbol == "CI").unwrap();
        assert_eq!(ci.captured_at, 200);
        assert_eq!(ci.gap_bps, 100);
        assert_eq!(ci.composite_score, 10);
    }

    #[test]
    fn latest_picks_higher_captured_at() {
        let db = Db::open_in_memory().unwrap();
        insert(&db, "X", 50, 1000, 1);
        insert(&db, "X", 51, 9999, 99);
        let latest = db.latest_snapshot_per_symbol().unwrap();
        let x = latest.iter().find(|r| r.symbol == "X").unwrap();
        assert_eq!(x.captured_at, 51);
        assert_eq!(x.gap_bps, 9999);
    }
}

#[cfg(test)]
mod foundation_0b_tests {
    use super::*;
    use crate::issuer_identity::{
        fixture_amzn_shaped, fixture_synthetic, identity_vintage_fingerprint, IdentityBundle,
    };
    use crate::valuation_evidence::{
        evidence_set_fingerprint, AccountingRegime, AvailabilityBasis, DatePrecision, EvidenceLane,
        EvidenceObservationV2, EvidenceUnitV2, MetricBasis, ReplayMode, StorageDisposition,
    };
    use std::path::PathBuf;

    fn seed(db: &Db, b: &IdentityBundle) {
        db.upsert_identity_bundle(
            &b.issuer.issuer_id,
            &b.issuer.cik,
            b.issuer.legal_name.as_deref(),
            &b.security.security_id,
            &b.security.currency,
            b.security.share_class_label.as_deref(),
            &b.ticker_alias.ticker,
            &b.ticker_alias.effective_from,
            &b.ticker_alias.identity_vintage,
            &b.share_basis.basis_id,
            &b.share_basis.vintage_fingerprint,
            &b.share_basis.description,
        )
        .unwrap();
    }

    fn analyst_import_value() -> serde_json::Value {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/contracts/valuation-forward-earnings-import-v1.json");
        let contract: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        contract["fixtures"]["available"][0]["import"].clone()
    }

    fn revised_import(
        mut value: serde_json::Value,
        run_id: &str,
        prior_run: &str,
        revision: &str,
        predecessor: &str,
        decision_delta: i64,
    ) -> String {
        value["runId"] = serde_json::json!(run_id);
        value["supersedesRunId"] = serde_json::json!(prior_run);
        let decision = value["decisionAtUnixMs"].as_i64().unwrap() + decision_delta;
        value["decisionAtUnixMs"] = serde_json::json!(decision);
        let mut ids = Vec::new();
        for (i, obs) in value["observations"]
            .as_array_mut()
            .unwrap()
            .iter_mut()
            .enumerate()
        {
            let id = format!("obs:{revision}:{i}");
            obs["id"] = serde_json::json!(&id);
            obs["revisionId"] = serde_json::json!(revision);
            obs["supersedes"] = serde_json::json!(predecessor);
            ids.push(id);
        }
        value["fem"]["epsObservationId"] = serde_json::json!(&ids[0]);
        value["fem"]["multipleObservationId"] = serde_json::json!(&ids[1]);
        value.to_string()
    }

    fn typed_obs(bundle: &IdentityBundle, id: &str, value_cents: i64) -> EvidenceObservationV2 {
        EvidenceObservationV2 {
            id: id.into(),
            issuer_id: bundle.issuer.issuer_id.clone(),
            security_id: Some(bundle.security.security_id.clone()),
            evidence_lane: EvidenceLane::AnalystStatedMethod,
            provider_id: "manual_import".into(),
            lineage_group_id: "lineage:fixture".into(),
            metric_id: "diluted_eps".into(),
            metric_basis: MetricBasis::TranscriptionClaim,
            accounting_regime: AccountingRegime::DomesticUsGaap,
            economic_period_start: "2028-01-01".into(),
            economic_period_end: "2028-12-31".into(),
            date_precision: DatePrecision::FiscalPeriod,
            publication_at_unix_ms: 1_000,
            source_available_at_unix_ms: 1_000,
            ingested_at_unix_ms: 1_000,
            availability_basis: AvailabilityBasis::PrimaryPublication,
            provider_vintage_id: None,
            unit: EvidenceUnitV2::MoneyCents,
            value_cents: Some(value_cents),
            value_bps: None,
            value_millis: None,
            text_value: None,
            currency: Some("USD".into()),
            definition: "fixture claim".into(),
            source_location: "manual:transcription".into(),
            extraction_method: "manual_entry".into(),
            quality: "provisional".into(),
            retrieval_state: "retrieved".into(),
            revision_id: "r1".into(),
            supersedes: None,
            external_file_reference: None,
            storage_disposition: StorageDisposition::MetadataOnly,
        }
    }

    fn commit_simple(
        db: &Db,
        obs: &[EvidenceObservationV2],
        run_id: &str,
        identity_fp: &str,
        issuer_id: &str,
        security_id: &str,
        mode: ReplayMode,
        projection: Option<&str>,
    ) -> Result<String, String> {
        db.commit_valuation_run(
            obs,
            run_id,
            "m",
            "e",
            "p",
            identity_fp,
            issuer_id,
            security_id,
            mode,
            "{}",
            1,
            projection,
        )
    }

    #[test]
    fn fresh_db_reaches_schema_version_8() {
        let db = Db::open_in_memory().unwrap();
        assert_eq!(db.schema_version().unwrap(), SQLITE_SCHEMA_VERSION);
        assert_eq!(SQLITE_SCHEMA_VERSION, 8);
    }

    #[test]
    fn populated_legacy_db_migrates_without_snapshot_loss() {
        let dir = std::env::temp_dir().join(format!(
            "ds_0b_mig_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("history.sqlite");
        {
            let conn = Connection::open(&path).unwrap();
            conn.execute_batch(SCHEMA).unwrap();
            conn.execute(
                "INSERT INTO snapshots (
                    symbol, captured_at, market_price_cents, intrinsic_value_cents,
                    gap_bps, decision, composite_score, confidence
                 ) VALUES ('T', 1, 100, 200, 50, 'Watch', 10, 'soft')",
                [],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO tipranks_request_budget (provider_month, attempts)
                 VALUES ('2026-08', 3)",
                [],
            )
            .unwrap();
        }
        let db = Db::open(path.clone()).unwrap();
        assert_eq!(db.schema_version().unwrap(), 8);
        assert_eq!(db.snapshot_count().unwrap(), 1);
        assert_eq!(db.tipranks_attempts("2026-08").unwrap(), 3);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn future_user_version_refuses_open() {
        let dir = std::env::temp_dir().join(format!(
            "ds_0b_future_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path: PathBuf = dir.join("history.sqlite");
        {
            let conn = Connection::open(&path).unwrap();
            conn.execute_batch(SCHEMA).unwrap();
            conn.pragma_update(None, "user_version", 99).unwrap();
        }
        match Db::open(path) {
            Ok(_) => panic!("expected open to refuse future schema"),
            Err(err) => assert!(err.contains("newer than supported"), "{err}"),
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn seed_two_identities_and_resolve_tickers() {
        let db = Db::open_in_memory().unwrap();
        seed(&db, &fixture_amzn_shaped());
        seed(&db, &fixture_synthetic());
        assert_eq!(
            db.resolve_security_id_by_ticker("AMZN").unwrap().as_deref(),
            Some("sec:amzn-us")
        );
        assert_eq!(
            db.resolve_security_id_by_ticker("SYNX").unwrap().as_deref(),
            Some("sec:syn-us")
        );
        assert_eq!(db.share_basis_count().unwrap(), 2);
    }

    #[test]
    fn identity_seed_is_idempotent_but_same_key_different_content_refuses() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_amzn_shaped();
        seed(&db, &b);
        seed(&db, &b);
        let err = db
            .upsert_identity_bundle(
                &b.issuer.issuer_id,
                &b.issuer.cik,
                b.issuer.legal_name.as_deref(),
                &b.security.security_id,
                &b.security.currency,
                b.security.share_class_label.as_deref(),
                &b.ticker_alias.ticker,
                "2026-01-01",
                &b.ticker_alias.identity_vintage,
                &b.share_basis.basis_id,
                &b.share_basis.vintage_fingerprint,
                &b.share_basis.description,
            )
            .unwrap_err();
        assert_eq!(err, "immutable_identity_conflict:ticker_alias");
    }

    #[test]
    fn migration_v5_quarantines_unreconstructible_legacy_current_projection() {
        let dir = std::env::temp_dir().join(format!(
            "ds_v5_quarantine_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("ledger.sqlite");
        {
            let conn = Connection::open(&path).unwrap();
            conn.execute_batch(SCHEMA).unwrap();
            conn.execute_batch(EVIDENCE_LEDGER_SCHEMA_V1).unwrap();
            conn.execute_batch(EVIDENCE_LEDGER_SCHEMA_V2).unwrap();
            conn.execute("ALTER TABLE valuation_model_run ADD COLUMN identity_fingerprint TEXT NOT NULL DEFAULT ''", []).unwrap();
            conn.execute("ALTER TABLE valuation_model_run ADD COLUMN lifecycle_fingerprint TEXT NOT NULL DEFAULT ''", []).unwrap();
            conn.execute("INSERT INTO valuation_model_run
                (run_id,method,engine_version,method_policy_version,evidence_set_fp,issuer_id,security_id,replay_mode,result_json,created_at_unix_ms,identity_fingerprint,lifecycle_fingerprint)
                VALUES ('legacy','m','e','p','fp','issuer',NULL,'operational','{}',1,'','')", []).unwrap();
            conn.execute("INSERT INTO valuation_current_projection(projection_key,run_id,updated_at_unix_ms) VALUES ('proj','legacy',2)", []).unwrap();
            conn.pragma_update(None, "user_version", 3).unwrap();
        }
        let db = Db::open(path.clone()).unwrap();
        assert_eq!(db.schema_version().unwrap(), 8);
        assert_eq!(db.current_projection_run_id("proj").unwrap(), None);
        assert_eq!(db.invalidation_count().unwrap(), 1);
        drop(db);
        let reopened = Db::open(path.clone()).unwrap();
        assert_eq!(reopened.current_projection_run_id("proj").unwrap(), None);
        assert_eq!(reopened.invalidation_count().unwrap(), 1);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn revision_predecessor_must_exist_and_lineage_survives_reopen() {
        let dir = std::env::temp_dir().join(format!(
            "ds_lineage_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("ledger.sqlite");
        let b = fixture_amzn_shaped();
        let base = analyst_import_value();
        let first_raw = base.to_string();
        let first =
            crate::analyst_method_import::parse_analyst_method_import_json(&first_raw).unwrap();
        {
            let db = Db::open(path.clone()).unwrap();
            seed(&db, &b);
            crate::analyst_method_service::commit_analyst_method_import(
                &db,
                &first_raw,
                &b,
                first.decision_at_unix_ms + 10,
            )
            .unwrap();
            let mut missing_value: serde_json::Value = serde_json::from_str(&revised_import(
                base.clone(),
                "run:missing",
                "run:never",
                "rev:missing",
                "rev:does-not-exist",
                10,
            ))
            .unwrap();
            missing_value["projectionKey"] = serde_json::Value::Null;
            missing_value["supersedesRunId"] = serde_json::Value::Null;
            let missing_raw = missing_value.to_string();
            let missing =
                crate::analyst_method_import::parse_analyst_method_import_json(&missing_raw)
                    .unwrap();
            let err = db
                .commit_analyst_method_lifecycle(
                    &missing.observations,
                    &missing_raw,
                    &missing.canonical_command_sha256,
                    missing.decision_at_unix_ms,
                    &missing.run_id,
                    crate::analyst_method_import::METHOD_FORWARD_EARNINGS_MULTIPLE,
                    crate::forward_earnings_multiple::ENGINE_ID,
                    crate::forward_earnings_multiple::METHOD_POLICY_VERSION,
                    &identity_vintage_fingerprint(&b),
                    &missing.issuer_id,
                    &missing.security_id,
                    &b.share_basis.basis_id,
                    &missing.eps_share_basis_id,
                    &b.ticker_alias.identity_vintage,
                    &b.ticker_alias.ticker,
                    missing.replay_mode,
                    "{}",
                    missing.decision_at_unix_ms + 1,
                    None,
                    None,
                    &missing.eps_observation_id,
                    &missing.multiple_observation_id,
                    &[(
                        "rev:missing".into(),
                        Some("rev:does-not-exist".into()),
                        missing.observations.iter().map(|o| o.id.clone()).collect(),
                    )],
                )
                .unwrap_err();
            assert_eq!(err, "revision_predecessor_missing");

            let prior_revision = &first.observations[0].revision_id;
            let second_raw = revised_import(
                base,
                "run:second",
                &first.run_id,
                "rev:second",
                prior_revision,
                20,
            );
            crate::analyst_method_service::commit_analyst_method_import(
                &db,
                &second_raw,
                &b,
                first.decision_at_unix_ms + 30,
            )
            .unwrap();
        }
        let reopened = Db::open(path.clone()).unwrap();
        let conn = reopened.conn.lock().unwrap();
        let edge_count:i64=conn.query_row("SELECT COUNT(*) FROM evidence_revision_edge WHERE revision_id='rev:second' AND supersedes=?1",params![&first.observations[0].revision_id],|r|r.get(0)).unwrap();
        let supersession_count:i64=conn.query_row("SELECT COUNT(*) FROM valuation_run_supersession WHERE run_id='run:second' AND supersedes_run_id=?1",params![&first.run_id],|r|r.get(0)).unwrap();
        assert_eq!(edge_count, 2);
        assert_eq!(supersession_count, 1);
        drop(conn);
        drop(reopened);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn two_independent_connections_contend_and_only_one_cas_transition_survives_reopen() {
        use std::sync::{Arc, Barrier};
        let dir = std::env::temp_dir().join(format!(
            "ds_two_writer_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("ledger.sqlite");
        let b = fixture_amzn_shaped();
        let base = analyst_import_value();
        let first_raw = base.to_string();
        let first =
            crate::analyst_method_import::parse_analyst_method_import_json(&first_raw).unwrap();
        {
            let db = Db::open(path.clone()).unwrap();
            seed(&db, &b);
            crate::analyst_method_service::commit_analyst_method_import(
                &db,
                &first_raw,
                &b,
                first.decision_at_unix_ms + 1,
            )
            .unwrap();
        }
        let predecessor = first.observations[0].revision_id.clone();
        let commands = [
            revised_import(
                base.clone(),
                "run:writer-a",
                &first.run_id,
                "rev:writer-a",
                &predecessor,
                10,
            ),
            revised_import(
                base,
                "run:writer-b",
                &first.run_id,
                "rev:writer-b",
                &predecessor,
                20,
            ),
        ];
        let decision_at = first.decision_at_unix_ms;
        let barrier = Arc::new(Barrier::new(3));
        let mut handles = Vec::new();
        for (index, raw) in commands.into_iter().enumerate() {
            let db = Db::open(path.clone()).unwrap();
            let bundle = b.clone();
            let gate = barrier.clone();
            handles.push(std::thread::spawn(move || {
                gate.wait();
                crate::analyst_method_service::commit_analyst_method_import(
                    &db,
                    &raw,
                    &bundle,
                    decision_at + 100 + index as i64,
                )
            }));
        }
        barrier.wait();
        let outcomes: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
        assert_eq!(
            outcomes.iter().filter(|r| r.is_ok()).count(),
            1,
            "{outcomes:?}"
        );
        let reopened = Db::open(path.clone()).unwrap();
        let conn = reopened.conn.lock().unwrap();
        let current: String = conn
            .query_row("SELECT run_id FROM valuation_current_projection", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert!(
            current == "run:writer-a" || current == "run:writer-b",
            "{current}"
        );
        let replacements:i64=conn.query_row("SELECT COUNT(*) FROM valuation_model_run WHERE run_id IN ('run:writer-a','run:writer-b')",[],|r|r.get(0)).unwrap();
        let supersessions:i64=conn.query_row("SELECT COUNT(*) FROM valuation_run_supersession WHERE run_id IN ('run:writer-a','run:writer-b')",[],|r|r.get(0)).unwrap();
        assert_eq!(replacements, 1);
        assert_eq!(supersessions, 1);
        drop(conn);
        drop(reopened);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn refused_attempt_and_invalidation_survive_reopen_atomically() {
        let dir = std::env::temp_dir().join(format!(
            "ds_refused_reopen_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("ledger.sqlite");
        let b = fixture_synthetic();
        {
            let db = Db::open(path.clone()).unwrap();
            seed(&db, &b);
            let obs = typed_obs(&b, "obs:prior", 100);
            db.commit_valuation_run(
                &[obs],
                "run:prior",
                "forward_earnings_multiple",
                "e",
                "p",
                &identity_vintage_fingerprint(&b),
                &b.issuer.issuer_id,
                &b.security.security_id,
                ReplayMode::Operational,
                "{}",
                1,
                Some("proj:x"),
            )
            .unwrap();
            let identity_fp = identity_vintage_fingerprint(&b);
            let rejected = RefusedAnalystMethodAttempt {
                attempted_run_id: "run:rejected",
                raw_command_json: "{\"runId\":\"run:rejected\"}",
                canonical_command_sha256: None,
                decision_at_unix_ms: None,
                issuer_id: &b.issuer.issuer_id,
                security_id: &b.security.security_id,
                method: "forward_earnings_multiple",
                projection_key: "proj:x",
                supersedes_run_id: "run:prior",
                replay_mode: Some("operational"),
                identity_fingerprint: Some(&identity_fp),
                share_basis_id: Some(&b.share_basis.basis_id),
                identity_vintage: Some(&b.ticker_alias.identity_vintage),
                ticker: Some(&b.ticker_alias.ticker),
                reason_code: "semantic_refusal",
                processed_at_unix_ms: 2,
            };
            db.refuse_superseding_revision(&rejected).unwrap();
            let stale_writer = db.refuse_superseding_revision(&rejected).unwrap_err();
            assert_eq!(stale_writer, "supersedes_no_current_projection");
            assert_eq!(db.current_projection_run_id("proj:x").unwrap(), None);
            assert_eq!(db.import_command_attempt_count().unwrap(), 1);
        }
        let reopened = Db::open(path.clone()).unwrap();
        assert_eq!(reopened.current_projection_run_id("proj:x").unwrap(), None);
        assert_eq!(reopened.invalidation_count().unwrap(), 1);
        assert_eq!(reopened.import_command_attempt_count().unwrap(), 1);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn eligible_projection_reconstructs_every_component_and_fails_closed_on_corruption() {
        let b = fixture_amzn_shaped();
        let fp = identity_vintage_fingerprint(&b);
        let raw_path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/contracts/valuation-forward-earnings-import-v1.json");
        let contract: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(raw_path).unwrap()).unwrap();
        let raw = contract["fixtures"]["available"][0]["import"].to_string();
        let parsed = crate::analyst_method_import::parse_analyst_method_import_json(&raw).unwrap();
        let projection = parsed.projection_key.clone().unwrap();
        let run_id = parsed.run_id.clone();
        let build = || {
            let db = Db::open_in_memory().unwrap();
            seed(&db, &b);
            crate::analyst_method_service::commit_analyst_method_import(
                &db,
                &raw,
                &b,
                parsed.decision_at_unix_ms + 10,
            )
            .unwrap();
            db
        };
        let expected = || CurrentProjectionEligibility {
            engine_version: crate::forward_earnings_multiple::ENGINE_ID,
            method_policy_version: crate::forward_earnings_multiple::METHOD_POLICY_VERSION,
            identity_fingerprint: &fp,
        };
        let db = build();
        assert_eq!(
            db.eligible_current_projection_run_id(&projection, expected())
                .unwrap()
                .as_deref(),
            Some(run_id.as_str())
        );
        assert_eq!(
            db.eligible_current_projection_run_id(
                &projection,
                CurrentProjectionEligibility {
                    engine_version: "wrong",
                    method_policy_version: crate::forward_earnings_multiple::METHOD_POLICY_VERSION,
                    identity_fingerprint: &fp
                }
            )
            .unwrap(),
            None
        );
        drop(db);

        let corruptions = [
            ("", "UPDATE valuation_model_run SET evidence_set_fp='sha256:bad' WHERE run_id=?1"),
            ("", "UPDATE valuation_model_run SET result_json='{}' WHERE run_id=?1"),
            ("", "UPDATE valuation_model_run SET lifecycle_fingerprint='sha256:bad' WHERE run_id=?1"),
            ("", "UPDATE valuation_model_run SET canonical_command_sha256='sha256:bad' WHERE run_id=?1"),
            ("", "UPDATE valuation_model_run SET decision_at_unix_ms=decision_at_unix_ms+1 WHERE run_id=?1"),
            ("", "UPDATE valuation_model_run SET identity_fingerprint='sha256:bad' WHERE run_id=?1"),
            ("", "UPDATE valuation_run_observation SET observation_fingerprint='sha256:bad' WHERE run_id=?1"),
            ("", "UPDATE evidence_observation_v2 SET payload_json='{}' WHERE id=(SELECT observation_id FROM valuation_run_observation WHERE run_id=?1 LIMIT 1)"),
            ("DROP TRIGGER immutable_role_binding_update", "UPDATE valuation_run_role_binding SET observation_id='missing' WHERE run_id=?1 AND role='forward_eps'"),
            ("DROP TRIGGER immutable_import_attempt_update", "UPDATE valuation_import_command_attempt SET payload_sha256='sha256:bad' WHERE attempted_run_id=?1 AND outcome='published'"),
            ("DROP TRIGGER immutable_import_attempt_update", "UPDATE valuation_import_command_attempt SET raw_payload_json='{}' WHERE attempted_run_id=?1 AND outcome='published'"),
        ];
        for (prep, mutation) in corruptions {
            let db = build();
            let conn = db.conn.lock().unwrap();
            if !prep.is_empty() {
                conn.execute_batch(prep).unwrap();
            }
            conn.execute(mutation, params![&run_id]).unwrap();
            drop(conn);
            assert_eq!(
                db.eligible_current_projection_run_id(&projection, expected())
                    .unwrap(),
                None,
                "mutation remained eligible: {mutation}"
            );
        }
    }

    #[test]
    fn revision_edge_delete_update_and_extra_fail_closed_after_reopen() {
        let b = fixture_amzn_shaped();
        let raw = analyst_import_value().to_string();
        let parsed = crate::analyst_method_import::parse_analyst_method_import_json(&raw).unwrap();
        let projection = parsed.projection_key.clone().unwrap();
        let fp = identity_vintage_fingerprint(&b);
        for mode in ["delete", "update", "extra"] {
            let dir = std::env::temp_dir().join(format!(
                "ds_edge_corrupt_{mode}_{}",
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ));
            std::fs::create_dir_all(&dir).unwrap();
            let path = dir.join("ledger.sqlite");
            {
                let db = Db::open(path.clone()).unwrap();
                seed(&db, &b);
                crate::analyst_method_service::commit_analyst_method_import(
                    &db,
                    &raw,
                    &b,
                    parsed.decision_at_unix_ms + 10,
                )
                .unwrap();
                let conn = db.conn.lock().unwrap();
                match mode {
                    "delete" => {
                        let guarded = conn
                            .execute(
                                "DELETE FROM evidence_revision_edge WHERE observation_id=?1",
                                params![&parsed.observations[0].id],
                            )
                            .unwrap_err();
                        assert!(guarded.to_string().contains("append_only:revision_edge"));
                        conn.execute_batch("DROP TRIGGER immutable_revision_edge_delete")
                            .unwrap();
                        conn.execute(
                            "DELETE FROM evidence_revision_edge WHERE observation_id=?1",
                            params![&parsed.observations[0].id],
                        )
                        .unwrap();
                    }
                    "update" => {
                        let guarded=conn.execute("UPDATE evidence_revision_edge SET supersedes='rev:mutated' WHERE observation_id=?1",params![&parsed.observations[0].id]).unwrap_err();
                        assert!(guarded.to_string().contains("append_only:revision_edge"));
                        conn.execute_batch("DROP TRIGGER immutable_revision_edge_update")
                            .unwrap();
                        conn.execute("UPDATE evidence_revision_edge SET supersedes='rev:mutated' WHERE observation_id=?1",params![&parsed.observations[0].id]).unwrap();
                    }
                    "extra" => {
                        conn.execute("INSERT INTO evidence_observation_v2(id,fingerprint_sha256,issuer_id,security_id,payload_json,ingested_at_unix_ms)
                            VALUES ('obs:edge-extra','sha256:edge-extra',?1,?2,'{}',1)",params![&parsed.issuer_id,&parsed.security_id]).unwrap();
                        conn.execute("INSERT INTO evidence_revision_edge(revision_id,supersedes,observation_id) VALUES (?1,?2,'obs:edge-extra')",
                            params![&parsed.observations[0].revision_id,&parsed.observations[0].supersedes]).unwrap();
                    }
                    _ => unreachable!(),
                }
            }
            let reopened = Db::open(path.clone()).unwrap();
            assert_eq!(
                reopened
                    .eligible_current_projection_run_id(
                        &projection,
                        CurrentProjectionEligibility {
                            engine_version: crate::forward_earnings_multiple::ENGINE_ID,
                            method_policy_version:
                                crate::forward_earnings_multiple::METHOD_POLICY_VERSION,
                            identity_fingerprint: &fp,
                        }
                    )
                    .unwrap(),
                None,
                "edge corruption remained eligible: {mode}"
            );
            drop(reopened);
            let _ = std::fs::remove_dir_all(dir);
        }
    }

    #[test]
    fn deep_ancestor_cycle_partition_and_intent_corruption_fail_closed_after_reopen() {
        let b = fixture_amzn_shaped();
        let base = analyst_import_value();
        let first_raw = base.to_string();
        let first =
            crate::analyst_method_import::parse_analyst_method_import_json(&first_raw).unwrap();
        let ancestor_a = first.observations[0].revision_id.clone();
        let second_raw = revised_import(
            base,
            "run:ancestor-candidate",
            &first.run_id,
            "rev:candidate-c",
            &ancestor_a,
            20,
        );
        let second =
            crate::analyst_method_import::parse_analyst_method_import_json(&second_raw).unwrap();
        let projection = second.projection_key.clone().unwrap();
        let fp = identity_vintage_fingerprint(&b);
        for mode in [
            "upstream_cycle",
            "deep_cross_partition",
            "conflicting_intent",
        ] {
            let dir = std::env::temp_dir().join(format!(
                "ds_ancestor_{mode}_{}",
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ));
            std::fs::create_dir_all(&dir).unwrap();
            let path = dir.join("ledger.sqlite");
            {
                let db = Db::open(path.clone()).unwrap();
                seed(&db, &b);
                crate::analyst_method_service::commit_analyst_method_import(
                    &db,
                    &first_raw,
                    &b,
                    first.decision_at_unix_ms + 1,
                )
                .unwrap();
                crate::analyst_method_service::commit_analyst_method_import(
                    &db,
                    &second_raw,
                    &b,
                    second.decision_at_unix_ms + 1,
                )
                .unwrap();
                let conn = db.conn.lock().unwrap();
                conn.execute_batch("DROP TRIGGER immutable_revision_edge_update")
                    .unwrap();
                match mode {
                    "upstream_cycle" => {
                        conn.execute("INSERT INTO evidence_observation_v2(id,fingerprint_sha256,issuer_id,security_id,payload_json,ingested_at_unix_ms)
                            VALUES ('obs:ancestor-b-cycle','sha256:ancestor-b-cycle',?1,?2,'{}',1)",params![&first.issuer_id,&first.security_id]).unwrap();
                        conn.execute("INSERT INTO evidence_revision_edge(revision_id,supersedes,observation_id) VALUES ('rev:ancestor-b',?1,'obs:ancestor-b-cycle')",params![&ancestor_a]).unwrap();
                        conn.execute("UPDATE evidence_revision_edge SET supersedes='rev:ancestor-b' WHERE revision_id=?1",params![&ancestor_a]).unwrap();
                    }
                    "deep_cross_partition" => {
                        conn.execute("INSERT INTO evidence_observation_v2(id,fingerprint_sha256,issuer_id,security_id,payload_json,ingested_at_unix_ms)
                            VALUES ('obs:ancestor-b-foreign','sha256:ancestor-b-foreign','issuer:foreign','sec:foreign','{}',1)",[]).unwrap();
                        conn.execute("INSERT INTO evidence_revision_edge(revision_id,supersedes,observation_id) VALUES ('rev:ancestor-b',NULL,'obs:ancestor-b-foreign')",[]).unwrap();
                        conn.execute("UPDATE evidence_revision_edge SET supersedes='rev:ancestor-b' WHERE revision_id=?1",params![&ancestor_a]).unwrap();
                    }
                    "conflicting_intent" => {
                        conn.execute("UPDATE evidence_revision_edge SET supersedes='rev:ancestor-b' WHERE revision_id=?1 AND observation_id=?2",
                            params![&ancestor_a,&first.observations[0].id]).unwrap();
                    }
                    _ => unreachable!(),
                }
            }
            let reopened = Db::open(path.clone()).unwrap();
            assert_eq!(
                reopened
                    .eligible_current_projection_run_id(
                        &projection,
                        CurrentProjectionEligibility {
                            engine_version: crate::forward_earnings_multiple::ENGINE_ID,
                            method_policy_version:
                                crate::forward_earnings_multiple::METHOD_POLICY_VERSION,
                            identity_fingerprint: &fp,
                        }
                    )
                    .unwrap(),
                None,
                "ancestor corruption remained eligible: {mode}"
            );
            drop(reopened);
            let _ = std::fs::remove_dir_all(dir);
        }
    }

    #[test]
    fn authority_race_preserves_new_current_and_writes_no_refusal() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_synthetic();
        seed(&db, &b);
        let fp = identity_vintage_fingerprint(&b);
        db.commit_valuation_run(
            &[typed_obs(&b, "obs:old", 100)],
            "run:old",
            "forward_earnings_multiple",
            "e",
            "p",
            &fp,
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::Operational,
            "{}",
            1,
            Some("proj:x"),
        )
        .unwrap();
        db.commit_valuation_run(
            &[typed_obs(&b, "obs:new", 101)],
            "run:new",
            "forward_earnings_multiple",
            "e",
            "p",
            &fp,
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::Operational,
            "{}",
            2,
            None,
        )
        .unwrap();
        db.conn.lock().unwrap().execute(
            "UPDATE valuation_current_projection SET run_id='run:new', updated_at_unix_ms=2 WHERE projection_key='proj:x'", []).unwrap();
        let attempt = RefusedAnalystMethodAttempt {
            attempted_run_id: "run:attempt",
            raw_command_json: "{}",
            canonical_command_sha256: None,
            decision_at_unix_ms: None,
            issuer_id: &b.issuer.issuer_id,
            security_id: &b.security.security_id,
            method: "forward_earnings_multiple",
            projection_key: "proj:x",
            supersedes_run_id: "run:old",
            replay_mode: Some("operational"),
            identity_fingerprint: Some(&fp),
            share_basis_id: Some(&b.share_basis.basis_id),
            identity_vintage: Some(&b.ticker_alias.identity_vintage),
            ticker: Some(&b.ticker_alias.ticker),
            reason_code: "semantic_refusal",
            processed_at_unix_ms: 3,
        };
        assert_eq!(
            db.refuse_superseding_revision(&attempt).unwrap_err(),
            "supersedes_not_current_projection:run:new"
        );
        assert_eq!(
            db.current_projection_run_id("proj:x").unwrap().as_deref(),
            Some("run:new")
        );
        assert_eq!(db.invalidation_count().unwrap(), 0);
        assert_eq!(db.import_command_attempt_count().unwrap(), 0);
    }

    #[test]
    fn mid_transaction_failure_rolls_back_invalidation_run_roles_and_command() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_amzn_shaped();
        seed(&db, &b);
        let fp = identity_vintage_fingerprint(&b);
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/contracts/valuation-forward-earnings-import-v1.json");
        let contract: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let mut command = contract["fixtures"]["available"][0]["import"].clone();
        command["runId"] = serde_json::json!("run:replacement");
        command["supersedesRunId"] = serde_json::json!("run:prior");
        let raw = command.to_string();
        let parsed = crate::analyst_method_import::parse_analyst_method_import_json(&raw).unwrap();
        let projection = parsed.projection_key.as_deref().unwrap();
        db.commit_valuation_run(
            &parsed.observations,
            "run:prior",
            "forward_earnings_multiple",
            "engine/1",
            "policy/1",
            &fp,
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::Operational,
            "{}",
            parsed.decision_at_unix_ms - 1,
            Some(projection),
        )
        .unwrap();
        let first = &parsed.observations[0];
        db.append_revision_edges(
            &first.revision_id,
            first.supersedes.as_deref(),
            &[first.id.clone()],
        )
        .unwrap();
        let mut grouped =
            std::collections::BTreeMap::<(String, Option<String>), Vec<String>>::new();
        for obs in &parsed.observations {
            grouped
                .entry((obs.revision_id.clone(), obs.supersedes.clone()))
                .or_default()
                .push(obs.id.clone());
        }
        let groups: Vec<_> = grouped
            .into_iter()
            .map(|((r, s), ids)| (r, s, ids))
            .collect();
        let fem = match crate::forward_earnings_multiple::compute_forward_earnings_multiple(
            &parsed.fem_input,
        ) {
            crate::forward_earnings_multiple::ForwardEarningsMultipleResult::Available(v) => v,
            _ => panic!("fixture unavailable"),
        };
        let result =
            crate::analyst_method_import::fem_result_json(&fem, parsed.quality_label).unwrap();
        let err = db
            .commit_analyst_method_lifecycle(
                &parsed.observations,
                &raw,
                &parsed.canonical_command_sha256,
                parsed.decision_at_unix_ms,
                "run:replacement",
                "forward_earnings_multiple",
                crate::forward_earnings_multiple::ENGINE_ID,
                crate::forward_earnings_multiple::METHOD_POLICY_VERSION,
                &fp,
                &b.issuer.issuer_id,
                &b.security.security_id,
                &b.share_basis.basis_id,
                &parsed.eps_share_basis_id,
                &b.ticker_alias.identity_vintage,
                &b.ticker_alias.ticker,
                ReplayMode::Operational,
                &result,
                parsed.decision_at_unix_ms + 10,
                Some(projection),
                Some("run:prior"),
                &parsed.eps_observation_id,
                &parsed.multiple_observation_id,
                &groups,
            )
            .unwrap_err();
        assert!(err.contains("insert revision edge"), "{err}");
        assert_eq!(
            db.current_projection_run_id(projection).unwrap().as_deref(),
            Some("run:prior")
        );
        assert!(!db.model_run_exists("run:replacement").unwrap());
        assert_eq!(db.invalidation_count().unwrap(), 0);
        assert_eq!(db.import_command_attempt_count().unwrap(), 0);
        assert!(db.run_role_bindings("run:replacement").unwrap().is_empty());
    }

    #[test]
    fn atomic_commit_typed_observation_recomputes_set_and_membership() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        let id_fp = identity_vintage_fingerprint(&amzn);
        let obs = typed_obs(&amzn, "obs:1", 1300);
        let expected_obs_fp = obs.fingerprint_sha256();
        let set_fp = db
            .commit_valuation_run(
                &[obs],
                "run:1",
                "forward_earnings_multiple",
                "forward_earnings_multiple/1",
                "fem-policy-v1",
                &id_fp,
                &amzn.issuer.issuer_id,
                &amzn.security.security_id,
                ReplayMode::Operational,
                r#"{"target_value_cents":36400}"#,
                2,
                Some("proj:amzn:fem"),
            )
            .unwrap();
        assert_eq!(set_fp, evidence_set_fingerprint(&[expected_obs_fp.clone()]));
        assert_eq!(db.observation_count().unwrap(), 1);
        assert_eq!(db.model_run_count().unwrap(), 1);
        assert_eq!(
            db.current_projection_run_id("proj:amzn:fem")
                .unwrap()
                .as_deref(),
            Some("run:1")
        );
        assert_eq!(
            db.model_run_identity_fingerprint("run:1")
                .unwrap()
                .as_deref(),
            Some(id_fp.as_str())
        );
        let membership = db.run_observation_membership("run:1").unwrap();
        assert_eq!(membership.len(), 1);
        assert_eq!(membership[0].0, "obs:1");
        assert_eq!(membership[0].1, expected_obs_fp);
        let rebuilt = evidence_set_fingerprint(
            &membership
                .iter()
                .map(|(_, fp)| fp.clone())
                .collect::<Vec<_>>(),
        );
        assert_eq!(
            db.model_run_evidence_set_fp("run:1").unwrap().as_deref(),
            Some(rebuilt.as_str())
        );
    }

    #[test]
    fn certified_backfill_cannot_update_projection() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_synthetic();
        seed(&db, &b);
        let obs = typed_obs(&b, "obs:1", 100);
        let err = commit_simple(
            &db,
            &[obs],
            "run:1",
            &identity_vintage_fingerprint(&b),
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::CertifiedBackfillResearch,
            Some("proj:x"),
        )
        .unwrap_err();
        assert!(
            err.contains("certified_backfill_cannot_update_projection"),
            "{err}"
        );
        assert_eq!(db.model_run_count().unwrap(), 0);
    }

    #[test]
    fn certified_backfill_without_projection_commits() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_synthetic();
        seed(&db, &b);
        let obs = typed_obs(&b, "obs:1", 100);
        commit_simple(
            &db,
            &[obs],
            "run:1",
            &identity_vintage_fingerprint(&b),
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::CertifiedBackfillResearch,
            None,
        )
        .unwrap();
        assert_eq!(db.model_run_count().unwrap(), 1);
    }

    #[test]
    fn unseeded_identity_refuses_commit() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_synthetic();
        // deliberately not seeded
        let obs = typed_obs(&b, "obs:1", 100);
        let err = commit_simple(
            &db,
            &[obs],
            "run:1",
            &identity_vintage_fingerprint(&b),
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::Operational,
            None,
        )
        .unwrap_err();
        assert!(err.contains("issuer_not_seeded"), "{err}");
    }

    #[test]
    fn wrong_identity_fingerprint_refuses() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_synthetic();
        seed(&db, &b);
        let obs = typed_obs(&b, "obs:1", 100);
        let err = commit_simple(
            &db,
            &[obs],
            "run:1",
            "sha256:deadbeef",
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::Operational,
            None,
        )
        .unwrap_err();
        assert!(err.contains("identity_fingerprint_mismatch"), "{err}");
    }

    #[test]
    fn invalid_observation_refuses_before_write() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_synthetic();
        seed(&db, &b);
        let mut obs = typed_obs(&b, "obs:1", 100);
        obs.storage_disposition = StorageDisposition::Prohibited;
        let err = commit_simple(
            &db,
            &[obs],
            "run:1",
            &identity_vintage_fingerprint(&b),
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::Operational,
            None,
        )
        .unwrap_err();
        assert!(
            err.contains("observation_invalid:storage_prohibited"),
            "{err}"
        );
        assert_eq!(db.observation_count().unwrap(), 0);
        assert_eq!(db.model_run_count().unwrap(), 0);
    }

    #[test]
    fn empty_identity_fingerprint_refuses() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_synthetic();
        seed(&db, &b);
        let obs = typed_obs(&b, "obs:1", 100);
        let err = commit_simple(
            &db,
            &[obs],
            "run:1",
            "  ",
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::Operational,
            None,
        )
        .unwrap_err();
        assert!(err.contains("empty_identity_fingerprint"), "{err}");
    }

    #[test]
    fn duplicate_observation_content_is_noop_for_obs_table() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_synthetic();
        seed(&db, &b);
        let id_fp = identity_vintage_fingerprint(&b);
        let obs = typed_obs(&b, "obs:1", 100);
        commit_simple(
            &db,
            &[obs.clone()],
            "run:1",
            &id_fp,
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::Operational,
            None,
        )
        .unwrap();
        commit_simple(
            &db,
            &[obs],
            "run:2",
            &id_fp,
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::Operational,
            None,
        )
        .unwrap();
        assert_eq!(db.observation_count().unwrap(), 1);
        assert_eq!(db.model_run_count().unwrap(), 2);
    }

    #[test]
    fn tipranks_prune_does_not_delete_evidence() {
        let db = Db::open_in_memory().unwrap();
        let b = fixture_synthetic();
        seed(&db, &b);
        let obs = typed_obs(&b, "obs:1", 100);
        commit_simple(
            &db,
            &[obs],
            "run:1",
            &identity_vintage_fingerprint(&b),
            &b.issuer.issuer_id,
            &b.security.security_id,
            ReplayMode::Operational,
            None,
        )
        .unwrap();
        db.save_tipranks_forecast_cache("2026-07", "T", 100, r#"{}"#)
            .unwrap();
        let _ = db.load_tipranks_forecast_cache("2026-08", "T").unwrap();
        assert_eq!(db.observation_count().unwrap(), 1);
    }
}
