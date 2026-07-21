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

impl Db {
    /// Open (or create) the database at `path`. Runs the schema migration.
    pub fn open(path: PathBuf) -> Result<Self, String> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("mkdir {}: {}", parent.display(), e))?;
        }
        let conn =
            Connection::open(&path).map_err(|e| format!("open {}: {}", path.display(), e))?;
        conn.execute_batch(SCHEMA)
            .map_err(|e| format!("schema: {}", e))?;
        // WAL mode: better concurrent reads while writer is active
        let _ = conn.pragma_update(None, "journal_mode", "WAL");
        let _ = conn.pragma_update(None, "synchronous", "NORMAL");
        Ok(Self {
            conn: Mutex::new(conn),
        })
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
