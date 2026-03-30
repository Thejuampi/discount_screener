use std::env;
use std::io;
use std::path::Path;
use std::path::PathBuf;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use discount_screener::ConfidenceBand;
use discount_screener::ExternalSignalStatus;
use discount_screener::ExternalValuationSignal;
use discount_screener::FundamentalSnapshot;
use discount_screener::MarketSnapshot;
use discount_screener::PersistedSymbolState;
use discount_screener::PriceHistoryPoint;
use discount_screener::QualificationStatus;
use rusqlite::Connection;
use rusqlite::OptionalExtension;
use rusqlite::params;
use serde::Deserialize;
use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::AppEvent;
use crate::AppEventPublisher;
use crate::ChartRangeSummary;
use crate::DcfAnalysis;
use crate::DcfSignal;
use crate::IssueSeverity;
use crate::IssueSource;
use crate::SectorRelativeScore;
use crate::market_data::ChartRange;
use crate::market_data::FundamentalTimeseries;
use crate::market_data::HistoricalCandle;
use crate::with_io_context;

const SQLITE_SCHEMA_VERSION: i32 = 3;
const SQLITE_BUSY_TIMEOUT: Duration = Duration::from_secs(5);
const META_KEY_LAST_STARTUP_AT: &str = "last_startup_at";
const META_KEY_LAST_CLEAN_SHUTDOWN_AT: &str = "last_clean_shutdown_at";
const META_KEY_LAST_PERSISTED_AT: &str = "last_persisted_at";
const DEFAULT_STATE_DB_FILE_NAME: &str = "state.sqlite3";

#[derive(Clone, Debug)]
pub(crate) struct PersistenceStatusEvent {
    pub operation: &'static str,
    pub error: Option<String>,
}

#[derive(Clone, Debug)]
pub(crate) struct PersistedChartRecord {
    pub symbol: String,
    pub range: ChartRange,
    pub candles: Vec<HistoricalCandle>,
    pub fetched_at: u64,
}

#[derive(Clone, Debug)]
pub(crate) struct PersistedIssueRecord {
    pub key: String,
    pub source: IssueSource,
    pub severity: IssueSeverity,
    pub title: String,
    pub detail: String,
    pub count: usize,
    pub first_seen_event: usize,
    pub last_seen_event: usize,
    pub active: bool,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct PersistenceBootstrap {
    pub tracked_symbols: Vec<String>,
    pub watchlist: Vec<String>,
    pub symbol_states: Vec<PersistedSymbolState>,
    pub chart_cache: Vec<PersistedChartRecord>,
    pub issues: Vec<PersistedIssueRecord>,
    pub last_persisted_at: Option<u64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) enum CaptureKind {
    Snapshot,
    External,
    Fundamentals,
    ChartCandles,
    FundamentalTimeseries,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub(crate) enum RawCapturePayload {
    Snapshot(MarketSnapshot),
    External(ExternalValuationSignal),
    Fundamentals(FundamentalSnapshot),
    Chart {
        range: ChartRange,
        candles: Vec<HistoricalCandle>,
    },
    FundamentalTimeseries(FundamentalTimeseries),
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub(crate) struct RawCapture {
    pub symbol: String,
    pub capture_kind: CaptureKind,
    pub scope_key: Option<String>,
    pub captured_at: u64,
    pub payload: RawCapturePayload,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct MetricGroupStatus {
    pub available: bool,
    pub stale: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub(crate) struct EvaluatedSymbolState {
    pub snapshot: Option<MarketSnapshot>,
    pub external_signal: Option<ExternalValuationSignal>,
    pub fundamentals: Option<FundamentalSnapshot>,
    pub gap_bps: Option<i32>,
    pub qualification: Option<QualificationStatus>,
    pub external_status: Option<ExternalSignalStatus>,
    pub confidence: Option<ConfidenceBand>,
    pub external_gap_bps: Option<i32>,
    pub weighted_gap_bps: Option<i32>,
    pub dcf_analysis: Option<DcfAnalysis>,
    pub dcf_signal: Option<DcfSignal>,
    pub dcf_margin_of_safety_bps: Option<i32>,
    pub relative_score: Option<SectorRelativeScore>,
    pub chart_summaries: Vec<ChartRangeSummary>,
    pub core_status: MetricGroupStatus,
    pub fundamentals_status: MetricGroupStatus,
    pub relative_status: MetricGroupStatus,
    pub dcf_status: MetricGroupStatus,
    pub chart_status: MetricGroupStatus,
    pub is_watched: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub(crate) struct SymbolRevisionInput {
    pub symbol: String,
    pub evaluated_at: u64,
    pub last_sequence: usize,
    pub update_count: usize,
    pub price_history: Vec<PriceHistoryPoint>,
    pub payload: EvaluatedSymbolState,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub(crate) struct PersistedRevisionRecord {
    pub revision_id: i64,
    pub symbol: String,
    pub evaluated_at: u64,
    pub last_sequence: usize,
    pub update_count: usize,
    pub payload: EvaluatedSymbolState,
}

enum PersistenceCommand {
    PersistBatch {
        raw_captures: Vec<RawCapture>,
        revisions: Vec<SymbolRevisionInput>,
        ack: mpsc::Sender<Result<(), String>>,
    },
    LoadHistory {
        symbol: String,
        ack: mpsc::Sender<Result<Vec<PersistedRevisionRecord>, String>>,
    },
    RequestHistory {
        symbol: String,
    },
    ReplaceWatchlist {
        symbols: Vec<String>,
    },
    ReplaceTrackedSymbols {
        symbols: Vec<String>,
    },
    ReplaceIssues {
        issues: Vec<PersistedIssueRecord>,
    },
    Flush {
        ack: mpsc::Sender<()>,
    },
    MarkCleanShutdown {
        timestamp: u64,
    },
    Shutdown,
}

pub(crate) struct PersistenceHandle {
    sender: mpsc::Sender<PersistenceCommand>,
    join_handle: Option<thread::JoinHandle<()>>,
}

impl PersistenceHandle {
    pub(crate) fn persist_batch(
        &self,
        raw_captures: Vec<RawCapture>,
        revisions: Vec<SymbolRevisionInput>,
    ) -> io::Result<()> {
        if raw_captures.is_empty() && revisions.is_empty() {
            return Ok(());
        }

        let (ack_sender, ack_receiver) = mpsc::channel();
        self.sender
            .send(PersistenceCommand::PersistBatch {
                raw_captures,
                revisions,
                ack: ack_sender,
            })
            .map_err(|error| {
                io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    format!("queue sqlite persistence batch: {error}"),
                )
            })?;
        match ack_receiver.recv_timeout(Duration::from_secs(10)) {
            Ok(Ok(())) => Ok(()),
            Ok(Err(error)) => Err(io::Error::other(error)),
            Err(error) => Err(io::Error::new(
                io::ErrorKind::TimedOut,
                format!("wait for sqlite persistence batch: {error}"),
            )),
        }
    }

    pub(crate) fn request_symbol_history(&self, symbol: String) {
        let _ = self
            .sender
            .send(PersistenceCommand::RequestHistory { symbol });
    }

    pub(crate) fn load_symbol_history(
        &self,
        symbol: String,
    ) -> io::Result<Vec<PersistedRevisionRecord>> {
        let (ack_sender, ack_receiver) = mpsc::channel();
        self.sender
            .send(PersistenceCommand::LoadHistory {
                symbol,
                ack: ack_sender,
            })
            .map_err(|error| {
                io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    format!("queue sqlite history load: {error}"),
                )
            })?;
        match ack_receiver.recv_timeout(Duration::from_secs(10)) {
            Ok(Ok(history)) => Ok(history),
            Ok(Err(error)) => Err(io::Error::other(error)),
            Err(error) => Err(io::Error::new(
                io::ErrorKind::TimedOut,
                format!("wait for sqlite history load: {error}"),
            )),
        }
    }

    pub(crate) fn replace_watchlist(&self, symbols: Vec<String>) {
        let _ = self
            .sender
            .send(PersistenceCommand::ReplaceWatchlist { symbols });
    }

    pub(crate) fn replace_tracked_symbols(&self, symbols: Vec<String>) {
        let _ = self
            .sender
            .send(PersistenceCommand::ReplaceTrackedSymbols { symbols });
    }

    pub(crate) fn replace_issues(&self, issues: Vec<PersistedIssueRecord>) {
        let _ = self
            .sender
            .send(PersistenceCommand::ReplaceIssues { issues });
    }

    pub(crate) fn shutdown(mut self, timestamp: u64) {
        let (ack_sender, ack_receiver) = mpsc::channel();
        let _ = self
            .sender
            .send(PersistenceCommand::MarkCleanShutdown { timestamp });
        let _ = self
            .sender
            .send(PersistenceCommand::Flush { ack: ack_sender });
        let _ = ack_receiver.recv_timeout(Duration::from_secs(2));
        let _ = self.sender.send(PersistenceCommand::Shutdown);

        if let Some(join_handle) = self.join_handle.take() {
            let _ = join_handle.join();
        }
    }
}

pub(crate) fn default_state_db_path() -> PathBuf {
    if cfg!(target_os = "windows") {
        if let Ok(local_app_data) = env::var("LOCALAPPDATA") {
            return PathBuf::from(local_app_data)
                .join("discount_screener")
                .join(DEFAULT_STATE_DB_FILE_NAME);
        }
    }

    if let Ok(xdg_state_home) = env::var("XDG_STATE_HOME") {
        return PathBuf::from(xdg_state_home)
            .join("discount_screener")
            .join(DEFAULT_STATE_DB_FILE_NAME);
    }

    if let Ok(home) = env::var("HOME") {
        return PathBuf::from(home)
            .join(".local")
            .join("state")
            .join("discount_screener")
            .join(DEFAULT_STATE_DB_FILE_NAME);
    }

    PathBuf::from(DEFAULT_STATE_DB_FILE_NAME)
}

pub(crate) fn load_warm_start(path: &Path) -> io::Result<PersistenceBootstrap> {
    let connection = open_connection(path)?;
    run_migrations(&connection)?;
    set_meta_value(
        &connection,
        META_KEY_LAST_STARTUP_AT,
        &crate::unix_timestamp_seconds().to_string(),
    )?;

    Ok(PersistenceBootstrap {
        tracked_symbols: load_tracked_symbols(&connection)?,
        watchlist: load_watchlist(&connection)?,
        symbol_states: load_symbol_latest(&connection)?,
        chart_cache: load_chart_cache(&connection)?,
        issues: load_issues(&connection)?,
        last_persisted_at: load_meta_value(&connection, META_KEY_LAST_PERSISTED_AT)?
            .and_then(|value| value.parse::<u64>().ok()),
    })
}

pub(crate) fn reset_warm_start_state(path: &Path) -> io::Result<()> {
    let connection = open_connection(path)?;
    run_migrations(&connection)?;
    let transaction = connection
        .unchecked_transaction()
        .map_err(io::Error::other)?;
    transaction
        .execute_batch(
            "\
            DELETE FROM tracked_symbol;
            DELETE FROM watchlist;
            DELETE FROM raw_capture;
            DELETE FROM raw_latest;
            DELETE FROM symbol_revision;
            DELETE FROM symbol_latest;
            DELETE FROM issue_state;
            DELETE FROM meta WHERE key = 'last_persisted_at';",
        )
        .map_err(io::Error::other)?;
    transaction.commit().map_err(io::Error::other)
}

pub(crate) fn spawn_worker(
    path: PathBuf,
    publisher: AppEventPublisher,
) -> io::Result<PersistenceHandle> {
    let (sender, receiver) = mpsc::channel();
    let join_handle = thread::Builder::new()
        .name("persistence".to_string())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                worker_loop(&path, &publisher, receiver)
            }));

            match result {
                Ok(Ok(())) => {}
                Ok(Err(error)) => {
                    let _ = publisher.publish(AppEvent::Fatal(with_io_context(
                        error,
                        format!("sqlite persistence worker failed: {}", path.display()),
                    )));
                }
                Err(_) => {
                    let _ = publisher.publish(AppEvent::Fatal(io::Error::other(format!(
                        "sqlite persistence worker panicked\ncrash report: {}",
                        crate::crash_report_log_path().display()
                    ))));
                }
            }
        })
        .map_err(io::Error::other)?;

    Ok(PersistenceHandle {
        sender,
        join_handle: Some(join_handle),
    })
}

fn worker_loop(
    path: &Path,
    publisher: &AppEventPublisher,
    receiver: mpsc::Receiver<PersistenceCommand>,
) -> io::Result<()> {
    let connection = open_connection(path)?;
    run_migrations(&connection)?;

    while let Ok(command) = receiver.recv() {
        match command {
            PersistenceCommand::PersistBatch {
                raw_captures,
                revisions,
                ack,
            } => {
                let result = persist_batch(&connection, &raw_captures, &revisions);
                let ack_result = result
                    .as_ref()
                    .map(|_| ())
                    .map_err(|error| error.to_string());
                let _ = ack.send(ack_result);
            }
            PersistenceCommand::LoadHistory { symbol, ack } => {
                let result =
                    load_revision_history(&connection, &symbol).map_err(|error| error.to_string());
                let _ = ack.send(result);
            }
            PersistenceCommand::RequestHistory { symbol } => {
                let result = load_revision_history(&connection, &symbol);
                let _ = publisher.publish(AppEvent::HistoryLoaded { symbol, result });
            }
            PersistenceCommand::ReplaceWatchlist { symbols } => publish_worker_status(
                publisher,
                "persist-watchlist",
                replace_watchlist(&connection, &symbols),
            ),
            PersistenceCommand::ReplaceTrackedSymbols { symbols } => publish_worker_status(
                publisher,
                "persist-tracked-symbols",
                replace_tracked_symbols(&connection, &symbols),
            ),
            PersistenceCommand::ReplaceIssues { issues } => publish_worker_status(
                publisher,
                "persist-issues",
                replace_issue_state(&connection, &issues),
            ),
            PersistenceCommand::Flush { ack } => {
                let _ = ack.send(());
            }
            PersistenceCommand::MarkCleanShutdown { timestamp } => publish_worker_status(
                publisher,
                "persist-clean-shutdown",
                set_meta_value(
                    &connection,
                    META_KEY_LAST_CLEAN_SHUTDOWN_AT,
                    &timestamp.to_string(),
                ),
            ),
            PersistenceCommand::Shutdown => break,
        }
    }

    Ok(())
}

fn publish_worker_status(
    publisher: &AppEventPublisher,
    operation: &'static str,
    result: io::Result<()>,
) {
    let _ = publisher.publish(AppEvent::PersistenceStatus(PersistenceStatusEvent {
        operation,
        error: result.err().map(|error| error.to_string()),
    }));
}

fn open_connection(path: &Path) -> io::Result<Connection> {
    if let Some(parent) = path.parent().filter(|path| !path.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }

    let connection = Connection::open(path).map_err(io::Error::other)?;
    connection
        .busy_timeout(SQLITE_BUSY_TIMEOUT)
        .map_err(io::Error::other)?;
    connection
        .pragma_update(None, "journal_mode", "WAL")
        .map_err(io::Error::other)?;
    connection
        .pragma_update(None, "synchronous", "FULL")
        .map_err(io::Error::other)?;
    Ok(connection)
}

fn run_migrations(connection: &Connection) -> io::Result<()> {
    let version: i32 = connection
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .map_err(io::Error::other)?;
    if version > SQLITE_SCHEMA_VERSION {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!(
                "sqlite schema version {version} is newer than supported version {SQLITE_SCHEMA_VERSION}"
            ),
        ));
    }

    if version == 0 {
        create_schema(connection)?;
        connection
            .pragma_update(None, "user_version", SQLITE_SCHEMA_VERSION)
            .map_err(io::Error::other)?;
        return Ok(());
    }

    if version == 2 {
        if !table_has_column(connection, "symbol_latest", "price_history_json")? {
            connection
                .execute(
                    "ALTER TABLE symbol_latest ADD COLUMN price_history_json TEXT",
                    [],
                )
                .map_err(io::Error::other)?;
        }
        connection
            .pragma_update(None, "user_version", SQLITE_SCHEMA_VERSION)
            .map_err(io::Error::other)?;
        return Ok(());
    }

    if version != SQLITE_SCHEMA_VERSION {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("unsupported sqlite schema version {version}"),
        ));
    }

    Ok(())
}

fn create_schema(connection: &Connection) -> io::Result<()> {
    connection
        .execute_batch(
            "\
            CREATE TABLE meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE tracked_symbol (
                position INTEGER NOT NULL,
                symbol TEXT PRIMARY KEY
            );
            CREATE TABLE watchlist (
                symbol TEXT PRIMARY KEY
            );
            CREATE TABLE raw_capture (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                capture_kind TEXT NOT NULL,
                scope_key TEXT,
                captured_at INTEGER NOT NULL,
                payload_json TEXT NOT NULL
            );
            CREATE INDEX raw_capture_symbol_idx ON raw_capture(symbol, captured_at, id);
            CREATE TABLE raw_latest (
                symbol TEXT NOT NULL,
                capture_key TEXT NOT NULL,
                capture_id INTEGER NOT NULL,
                PRIMARY KEY(symbol, capture_key)
            );
            CREATE TABLE symbol_revision (
                revision_id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                evaluated_at INTEGER NOT NULL,
                last_sequence INTEGER NOT NULL,
                update_count INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                snapshot_json TEXT,
                external_json TEXT,
                fundamentals_json TEXT
            );
            CREATE INDEX symbol_revision_symbol_idx
                ON symbol_revision(symbol, evaluated_at, revision_id);
            CREATE TABLE symbol_latest (
                symbol TEXT PRIMARY KEY,
                revision_id INTEGER NOT NULL,
                evaluated_at INTEGER NOT NULL,
                last_sequence INTEGER NOT NULL,
                update_count INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                snapshot_json TEXT,
                external_json TEXT,
                fundamentals_json TEXT,
                price_history_json TEXT
            );
            CREATE TABLE issue_state (
                key TEXT PRIMARY KEY,
                source TEXT NOT NULL,
                severity TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                issue_count INTEGER NOT NULL,
                first_seen_event INTEGER NOT NULL,
                last_seen_event INTEGER NOT NULL,
                active INTEGER NOT NULL
            );",
        )
        .map_err(io::Error::other)?;
    Ok(())
}

fn table_has_column(
    connection: &Connection,
    table_name: &str,
    column_name: &str,
) -> io::Result<bool> {
    let mut statement = connection
        .prepare(&format!("PRAGMA table_info({table_name})"))
        .map_err(io::Error::other)?;
    let rows = statement
        .query_map([], |row| row.get::<_, String>(1))
        .map_err(io::Error::other)?;

    for row in rows {
        if row.map_err(io::Error::other)? == column_name {
            return Ok(true);
        }
    }

    Ok(false)
}

fn load_tracked_symbols(connection: &Connection) -> io::Result<Vec<String>> {
    let mut statement = connection
        .prepare("SELECT symbol FROM tracked_symbol ORDER BY position ASC")
        .map_err(io::Error::other)?;
    let rows = statement
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(io::Error::other)?;

    let mut symbols = Vec::new();
    for row in rows {
        symbols.push(row.map_err(io::Error::other)?);
    }
    Ok(symbols)
}

fn load_watchlist(connection: &Connection) -> io::Result<Vec<String>> {
    let mut statement = connection
        .prepare("SELECT symbol FROM watchlist ORDER BY symbol ASC")
        .map_err(io::Error::other)?;
    let rows = statement
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(io::Error::other)?;

    let mut symbols = Vec::new();
    for row in rows {
        symbols.push(row.map_err(io::Error::other)?);
    }
    Ok(symbols)
}

fn load_symbol_latest(connection: &Connection) -> io::Result<Vec<PersistedSymbolState>> {
    let mut statement = connection
        .prepare(
            "\
            SELECT symbol, snapshot_json, external_json, fundamentals_json, last_sequence, update_count, price_history_json
            FROM symbol_latest
            ORDER BY symbol ASC",
        )
        .map_err(io::Error::other)?;
    let rows = statement
        .query_map([], |row| {
            Ok(PersistedSymbolState {
                symbol: row.get(0)?,
                snapshot: parse_optional_json_row(row.get::<_, Option<String>>(1)?)?,
                external_signal: parse_optional_json_row(row.get::<_, Option<String>>(2)?)?,
                fundamentals: parse_optional_json_row(row.get::<_, Option<String>>(3)?)?,
                last_sequence: row.get::<_, i64>(4)? as usize,
                update_count: row.get::<_, i64>(5)? as usize,
                price_history: parse_optional_json_row(row.get::<_, Option<String>>(6)?)?
                    .unwrap_or_default(),
            })
        })
        .map_err(io::Error::other)?;

    let mut symbol_states = Vec::new();
    for row in rows {
        symbol_states.push(row.map_err(io::Error::other)?);
    }
    Ok(symbol_states)
}

fn load_chart_cache(connection: &Connection) -> io::Result<Vec<PersistedChartRecord>> {
    let mut statement = connection
        .prepare(
            "\
            SELECT raw_capture.symbol, raw_capture.captured_at, raw_capture.payload_json
            FROM raw_latest
            JOIN raw_capture ON raw_capture.id = raw_latest.capture_id
            WHERE raw_latest.capture_key LIKE 'chart:%'
            ORDER BY raw_capture.symbol ASC, raw_latest.capture_key ASC",
        )
        .map_err(io::Error::other)?;
    let rows = statement
        .query_map([], |row| {
            let payload = parse_json_row::<RawCapturePayload>(row.get::<_, String>(2)?)?;
            let RawCapturePayload::Chart { range, candles } = payload else {
                return Err(rusqlite::Error::FromSqlConversionFailure(
                    2,
                    rusqlite::types::Type::Text,
                    Box::new(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "raw_latest chart row contained a non-chart payload",
                    )),
                ));
            };

            Ok(PersistedChartRecord {
                symbol: row.get(0)?,
                range,
                candles,
                fetched_at: row.get::<_, i64>(1)? as u64,
            })
        })
        .map_err(io::Error::other)?;

    let mut chart_cache = Vec::new();
    for row in rows {
        chart_cache.push(row.map_err(io::Error::other)?);
    }
    Ok(chart_cache)
}

fn load_issues(connection: &Connection) -> io::Result<Vec<PersistedIssueRecord>> {
    let mut statement = connection
        .prepare(
            "\
            SELECT key, source, severity, title, detail, issue_count, first_seen_event, last_seen_event, active
            FROM issue_state
            ORDER BY active DESC, last_seen_event DESC",
        )
        .map_err(io::Error::other)?;
    let rows = statement
        .query_map([], |row| {
            Ok(PersistedIssueRecord {
                key: row.get(0)?,
                source: parse_issue_source(&row.get::<_, String>(1)?)?,
                severity: parse_issue_severity(&row.get::<_, String>(2)?)?,
                title: row.get(3)?,
                detail: row.get(4)?,
                count: row.get::<_, i64>(5)? as usize,
                first_seen_event: row.get::<_, i64>(6)? as usize,
                last_seen_event: row.get::<_, i64>(7)? as usize,
                active: row.get::<_, i64>(8)? != 0,
            })
        })
        .map_err(io::Error::other)?;

    let mut issues = Vec::new();
    for row in rows {
        issues.push(row.map_err(io::Error::other)?);
    }
    Ok(issues)
}

fn replace_tracked_symbols(connection: &Connection, symbols: &[String]) -> io::Result<()> {
    let transaction = connection
        .unchecked_transaction()
        .map_err(io::Error::other)?;
    transaction
        .execute("DELETE FROM tracked_symbol", [])
        .map_err(io::Error::other)?;

    for (position, symbol) in symbols.iter().enumerate() {
        transaction
            .execute(
                "INSERT INTO tracked_symbol(position, symbol) VALUES (?, ?)",
                params![position as i64, symbol],
            )
            .map_err(io::Error::other)?;
    }

    transaction.commit().map_err(io::Error::other)
}

fn replace_watchlist(connection: &Connection, symbols: &[String]) -> io::Result<()> {
    let transaction = connection
        .unchecked_transaction()
        .map_err(io::Error::other)?;
    transaction
        .execute("DELETE FROM watchlist", [])
        .map_err(io::Error::other)?;

    for symbol in symbols {
        transaction
            .execute("INSERT INTO watchlist(symbol) VALUES (?)", params![symbol])
            .map_err(io::Error::other)?;
    }

    transaction.commit().map_err(io::Error::other)
}

fn replace_issue_state(connection: &Connection, issues: &[PersistedIssueRecord]) -> io::Result<()> {
    let transaction = connection
        .unchecked_transaction()
        .map_err(io::Error::other)?;
    transaction
        .execute("DELETE FROM issue_state", [])
        .map_err(io::Error::other)?;

    for issue in issues {
        transaction
            .execute(
                "\
                INSERT INTO issue_state(
                    key, source, severity, title, detail, issue_count, first_seen_event, last_seen_event, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                params![
                    issue.key,
                    encode_issue_source(issue.source),
                    encode_issue_severity(issue.severity),
                    issue.title,
                    issue.detail,
                    issue.count as i64,
                    issue.first_seen_event as i64,
                    issue.last_seen_event as i64,
                    if issue.active { 1_i64 } else { 0_i64 },
                ],
            )
            .map_err(io::Error::other)?;
    }

    transaction.commit().map_err(io::Error::other)
}

fn persist_batch(
    connection: &Connection,
    raw_captures: &[RawCapture],
    revisions: &[SymbolRevisionInput],
) -> io::Result<()> {
    if raw_captures.is_empty() && revisions.is_empty() {
        return Ok(());
    }

    let transaction = connection
        .unchecked_transaction()
        .map_err(io::Error::other)?;
    let mut latest_timestamp = None;

    for capture in raw_captures {
        transaction
            .execute(
                "\
                INSERT INTO raw_capture(symbol, capture_kind, scope_key, captured_at, payload_json)
                VALUES (?, ?, ?, ?, ?)",
                params![
                    capture.symbol,
                    encode_capture_kind(capture.capture_kind),
                    capture.scope_key,
                    capture.captured_at as i64,
                    serialize_json(&capture.payload)?,
                ],
            )
            .map_err(io::Error::other)?;
        let capture_id = transaction.last_insert_rowid();
        transaction
            .execute(
                "\
                INSERT INTO raw_latest(symbol, capture_key, capture_id)
                VALUES (?, ?, ?)
                ON CONFLICT(symbol, capture_key) DO UPDATE SET
                    capture_id = excluded.capture_id",
                params![capture.symbol, raw_capture_key(capture), capture_id],
            )
            .map_err(io::Error::other)?;
        latest_timestamp = Some(latest_timestamp.unwrap_or(0).max(capture.captured_at));
    }

    for revision in revisions {
        let payload_json = serialize_json(&revision.payload)?;
        let snapshot_json = serialize_optional_json(&revision.payload.snapshot)?;
        let external_json = serialize_optional_json(&revision.payload.external_signal)?;
        let fundamentals_json = serialize_optional_json(&revision.payload.fundamentals)?;
        let price_history_json = serialize_json(&revision.price_history)?;
        transaction
            .execute(
                "\
                INSERT INTO symbol_revision(
                    symbol, evaluated_at, last_sequence, update_count, payload_json, snapshot_json, external_json, fundamentals_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                params![
                    revision.symbol,
                    revision.evaluated_at as i64,
                    revision.last_sequence as i64,
                    revision.update_count as i64,
                    payload_json,
                    snapshot_json,
                    external_json,
                    fundamentals_json,
                ],
            )
            .map_err(io::Error::other)?;
        let revision_id = transaction.last_insert_rowid();
        transaction
            .execute(
                "\
                INSERT INTO symbol_latest(
                    symbol, revision_id, evaluated_at, last_sequence, update_count, payload_json, snapshot_json, external_json, fundamentals_json, price_history_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(symbol) DO UPDATE SET
                    revision_id = excluded.revision_id,
                    evaluated_at = excluded.evaluated_at,
                    last_sequence = excluded.last_sequence,
                    update_count = excluded.update_count,
                    payload_json = excluded.payload_json,
                    snapshot_json = excluded.snapshot_json,
                    external_json = excluded.external_json,
                    fundamentals_json = excluded.fundamentals_json,
                    price_history_json = excluded.price_history_json",
                params![
                    revision.symbol,
                    revision_id,
                    revision.evaluated_at as i64,
                    revision.last_sequence as i64,
                    revision.update_count as i64,
                    payload_json,
                    snapshot_json,
                    external_json,
                    fundamentals_json,
                    price_history_json,
                ],
            )
            .map_err(io::Error::other)?;
        latest_timestamp = Some(latest_timestamp.unwrap_or(0).max(revision.evaluated_at));
    }

    if let Some(latest_timestamp) = latest_timestamp {
        set_meta_value_tx(
            &transaction,
            META_KEY_LAST_PERSISTED_AT,
            &latest_timestamp.to_string(),
        )?;
    }

    transaction.commit().map_err(io::Error::other)
}

fn load_revision_history(
    connection: &Connection,
    symbol: &str,
) -> io::Result<Vec<PersistedRevisionRecord>> {
    let mut statement = connection
        .prepare(
            "\
            SELECT revision_id, symbol, evaluated_at, last_sequence, update_count, payload_json
            FROM symbol_revision
            WHERE symbol = ?
            ORDER BY evaluated_at ASC, revision_id ASC",
        )
        .map_err(io::Error::other)?;
    let rows = statement
        .query_map([symbol], |row| {
            Ok(PersistedRevisionRecord {
                revision_id: row.get(0)?,
                symbol: row.get(1)?,
                evaluated_at: row.get::<_, i64>(2)? as u64,
                last_sequence: row.get::<_, i64>(3)? as usize,
                update_count: row.get::<_, i64>(4)? as usize,
                payload: parse_json_row(row.get::<_, String>(5)?)?,
            })
        })
        .map_err(io::Error::other)?;

    let mut history = Vec::new();
    for row in rows {
        history.push(row.map_err(io::Error::other)?);
    }
    Ok(history)
}

fn raw_capture_key(capture: &RawCapture) -> String {
    match capture.capture_kind {
        CaptureKind::Snapshot => "snapshot".to_string(),
        CaptureKind::External => "external".to_string(),
        CaptureKind::Fundamentals => "fundamentals".to_string(),
        CaptureKind::ChartCandles => format!(
            "chart:{}",
            capture.scope_key.as_deref().unwrap_or("unknown")
        ),
        CaptureKind::FundamentalTimeseries => "fundamental-timeseries".to_string(),
    }
}

fn set_meta_value(connection: &Connection, key: &str, value: &str) -> io::Result<()> {
    connection
        .execute(
            "\
            INSERT INTO meta(key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )
        .map_err(io::Error::other)?;
    Ok(())
}

fn set_meta_value_tx(
    transaction: &rusqlite::Transaction<'_>,
    key: &str,
    value: &str,
) -> io::Result<()> {
    transaction
        .execute(
            "\
            INSERT INTO meta(key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )
        .map_err(io::Error::other)?;
    Ok(())
}

fn load_meta_value(connection: &Connection, key: &str) -> io::Result<Option<String>> {
    connection
        .query_row("SELECT value FROM meta WHERE key = ?", [key], |row| {
            row.get(0)
        })
        .optional()
        .map_err(io::Error::other)
}

fn serialize_json<T: Serialize>(value: &T) -> io::Result<String> {
    serde_json::to_string(value).map_err(io::Error::other)
}

fn serialize_optional_json<T: Serialize>(value: &Option<T>) -> io::Result<Option<String>> {
    value.as_ref().map(serialize_json).transpose()
}

fn parse_json_row<T: DeserializeOwned>(value: String) -> rusqlite::Result<T> {
    serde_json::from_str(&value).map_err(|error| {
        rusqlite::Error::FromSqlConversionFailure(0, rusqlite::types::Type::Text, Box::new(error))
    })
}

fn parse_optional_json_row<T: DeserializeOwned>(
    value: Option<String>,
) -> rusqlite::Result<Option<T>> {
    value.map(parse_json_row).transpose()
}

fn encode_capture_kind(capture_kind: CaptureKind) -> &'static str {
    match capture_kind {
        CaptureKind::Snapshot => "snapshot",
        CaptureKind::External => "external",
        CaptureKind::Fundamentals => "fundamentals",
        CaptureKind::ChartCandles => "chart-candles",
        CaptureKind::FundamentalTimeseries => "fundamental-timeseries",
    }
}

fn encode_issue_source(issue_source: IssueSource) -> &'static str {
    match issue_source {
        IssueSource::Feed => "feed",
        IssueSource::Persistence => "persistence",
    }
}

fn encode_issue_severity(issue_severity: IssueSeverity) -> &'static str {
    match issue_severity {
        IssueSeverity::Warning => "warning",
        IssueSeverity::Error => "error",
        IssueSeverity::Critical => "critical",
    }
}

fn parse_issue_source(value: &str) -> rusqlite::Result<IssueSource> {
    match value {
        "feed" => Ok(IssueSource::Feed),
        "persistence" => Ok(IssueSource::Persistence),
        other => Err(rusqlite::Error::FromSqlConversionFailure(
            0,
            rusqlite::types::Type::Text,
            Box::new(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("unknown issue source {other}"),
            )),
        )),
    }
}

fn parse_issue_severity(value: &str) -> rusqlite::Result<IssueSeverity> {
    match value {
        "warning" => Ok(IssueSeverity::Warning),
        "error" => Ok(IssueSeverity::Error),
        "critical" => Ok(IssueSeverity::Critical),
        other => Err(rusqlite::Error::FromSqlConversionFailure(
            0,
            rusqlite::types::Type::Text,
            Box::new(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("unknown issue severity {other}"),
            )),
        )),
    }
}
