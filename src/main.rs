mod market_data;
mod persistence;
mod profiles;

use std::backtrace::Backtrace;
use std::cmp::Ordering;
use std::collections::HashMap;
use std::collections::HashSet;
use std::collections::VecDeque;
use std::error::Error;
use std::fmt;
use std::io;
use std::io::ErrorKind;
use std::io::Stdout;
use std::io::Write;
use std::panic;
use std::panic::AssertUnwindSafe;
use std::panic::PanicHookInfo;
use std::path::Path;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::OnceLock;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;
use std::time::Instant;
use std::time::SystemTime;
use std::time::UNIX_EPOCH;

use crossterm::cursor::Hide;
use crossterm::cursor::MoveTo;
use crossterm::cursor::Show;
use crossterm::event;
use crossterm::event::Event;
use crossterm::event::KeyCode;
use crossterm::event::KeyEvent;
use crossterm::event::KeyEventKind;
use crossterm::event::KeyModifiers;
use crossterm::execute;
use crossterm::queue;
use crossterm::style::Color;
use crossterm::style::Print;
use crossterm::style::ResetColor;
use crossterm::style::SetForegroundColor;
use crossterm::terminal;
use crossterm::terminal::BeginSynchronizedUpdate;
use crossterm::terminal::Clear;
use crossterm::terminal::ClearType;
use crossterm::terminal::EndSynchronizedUpdate;
use crossterm::terminal::EnterAlternateScreen;
use crossterm::terminal::LeaveAlternateScreen;
use discount_screener::AlertEvent;
use discount_screener::AlertKind;
use discount_screener::CandidateRow;
use discount_screener::ConfidenceBand;
use discount_screener::ExternalSignalStatus;
use discount_screener::ExternalValuationSignal;
use discount_screener::FundamentalSnapshot;
use discount_screener::MarketSnapshot;
use discount_screener::QualificationStatus;
use discount_screener::SymbolDetail;
use discount_screener::TapeEvent;
use discount_screener::TerminalState;
use discount_screener::ViewFilter;
use discount_screener::checked_gap_bps;
use market_data::ChartRange;
use market_data::DEFAULT_POLL_INTERVAL;
use market_data::FundamentalTimeseries;
use market_data::HistoricalCandle;
use market_data::MarketDataClient;
use market_data::default_live_symbols;
use persistence::PersistedChartRecord;
use persistence::PersistedIssueRecord;
use persistence::PersistenceBootstrap;
use persistence::PersistenceHandle;
use persistence::PersistenceStatusEvent;
use profiles::profile_definitions;
use profiles::profile_symbols;
use serde::Deserialize;
use serde::Serialize;

const MAX_VISIBLE_ALERTS: usize = 6;
const MAX_VISIBLE_TAPE: usize = 8;
const MAX_VISIBLE_ISSUES: usize = 8;
const MAX_STORED_ISSUES: usize = 64;
const MAX_VISIBLE_ROWS: usize = 20;
const WEIGHTED_TARGET_REFRESH_BUDGET_PER_CYCLE: usize = 8;
const CANDIDATE_COMPANY_COLUMN_WIDTH: usize = 36;
const TARGET_RANGE_BAR_WIDTH: usize = 18;
const GAP_METER_WIDTH: usize = 12;
const DETAIL_CONSENSUS_BAR_WIDTH: usize = 20;
const DETAIL_RECENT_SUMMARY_COUNT: usize = 4;
#[cfg(test)]
const DEFAULT_VIEWPORT_WIDTH: usize = 120;
#[cfg(test)]
const DEFAULT_VIEWPORT_HEIGHT: usize = 40;
const DETAIL_MIN_CHART_HEIGHT: usize = 6;
const DETAIL_MIN_VISIBLE_CANDLES: usize = 8;
const DETAIL_MIN_VOLUME_HEIGHT: usize = 3;
const DETAIL_MIN_MACD_HEIGHT: usize = 4;
const DETAIL_CHART_AXIS_WIDTH: usize = 12;
const DETAIL_CHART_ROW_PADDING: usize = 2;
const BACKGROUND_CHART_REQUEST_BUDGET_PER_CYCLE: usize = 6;
const INLINE_STYLE_MARKER: char = '\u{001f}';
const DEFAULT_FEED_ERROR_LOG_FILE: &str = "feed-errors.log";
const DEFAULT_CRASH_REPORT_LOG_FILE: &str = "crash-report.log";
const ISSUE_TOAST_DURATION: Duration = Duration::from_secs(6);
const EVENT_RATE_WINDOW: Duration = Duration::from_secs(1);
const ISSUE_KEY_FEED_UNAVAILABLE: &str = "feed-unavailable";
const ISSUE_KEY_FEED_PARTIAL: &str = "feed-partial";
const ISSUE_KEY_SQLITE_PERSISTENCE: &str = "sqlite-persistence";
const ISSUE_KEY_SQLITE_RESTORE: &str = "sqlite-restore";
const ISSUE_KEY_HISTORY_EXPORT: &str = "history-export";
#[cfg(test)]
const ISSUE_KEY_JOURNAL_RESTORE: &str = ISSUE_KEY_SQLITE_RESTORE;
#[cfg(test)]
const ISSUE_KEY_WATCHLIST_RESTORE: &str = ISSUE_KEY_SQLITE_RESTORE;
const RISK_FREE_RATE_BPS: i32 = 400;
const EQUITY_RISK_PREMIUM_BPS: i32 = 500;
const DEFAULT_TAX_RATE_BPS: i32 = 2_100;
const DEFAULT_COST_OF_DEBT_BPS: i32 = 550;
const MIN_COST_OF_DEBT_BPS: i32 = 200;
const MAX_COST_OF_DEBT_BPS: i32 = 1_200;
const MIN_WACC_BPS: i32 = 500;
const MAX_WACC_BPS: i32 = 1_800;
const DCF_PROJECTION_YEARS: usize = 5;
const BASE_GROWTH_MIN_BPS: i32 = -1_000;
const BASE_GROWTH_MAX_BPS: i32 = 1_800;
const SCENARIO_GROWTH_SPREAD_BPS: i32 = 400;
const BEAR_GROWTH_MIN_BPS: i32 = -1_200;
const BEAR_GROWTH_MAX_BPS: i32 = 1_400;
const BULL_GROWTH_MIN_BPS: i32 = -400;
const BULL_GROWTH_MAX_BPS: i32 = 2_400;
const BEAR_TERMINAL_GROWTH_BPS: i32 = 200;
const BASE_TERMINAL_GROWTH_BPS: i32 = 250;
const BULL_TERMINAL_GROWTH_BPS: i32 = 300;
const DCF_OPPORTUNITY_THRESHOLD_BPS: i32 = 2_000;
const DCF_EXPENSIVE_THRESHOLD_BPS: i32 = -1_000;
const MIN_RELATIVE_PEERS: usize = 5;
const STRONG_RELATIVE_SCORE: u8 = 67;
const WEAK_RELATIVE_SCORE: u8 = 34;
const MIN_FEED_FETCH_CONCURRENCY: usize = 1;
const START_FEED_FETCH_CONCURRENCY: usize = 2;
const MAX_FEED_FETCH_CONCURRENCY: usize = 4;
const INITIAL_FEED_REFRESH_BUDGET: usize = 32;
const MIN_STEADY_FEED_REFRESH_BUDGET: usize = 16;
const START_STEADY_FEED_REFRESH_BUDGET: usize = 32;
const MAX_STEADY_FEED_REFRESH_BUDGET: usize = 64;
const MAX_RETRY_SYMBOLS_PER_CYCLE: usize = 16;
const FEED_RECOVERY_COOLDOWN_CYCLES: usize = 3;

#[derive(Clone)]
enum FeedEvent {
    Snapshot(MarketSnapshot),
    External(ExternalValuationSignal),
    Fundamentals(FundamentalSnapshot),
    Coverage(SymbolCoverageEvent),
    SourceStatus(LiveSourceStatus),
}

#[derive(Clone)]
struct LiveSourceStatus {
    tracked_symbols: usize,
    fresh_symbols: usize,
    stale_symbols: usize,
    degraded_symbols: usize,
    unavailable_symbols: usize,
    last_error: Option<String>,
}

#[derive(Clone)]
struct SymbolCoverageEvent {
    symbol: String,
    coverage: market_data::ProviderCoverage,
    diagnostics: Vec<market_data::ProviderDiagnostic>,
}

#[derive(Clone)]
struct FeedProgressStatus {
    message: String,
    color: Color,
}

enum FeedControl {
    RefreshNow,
}

#[derive(Clone, Copy)]
enum ChartRequestKind {
    Detail,
    Background,
}

enum ChartControl {
    Load {
        symbol: String,
        range: ChartRange,
        request_id: u64,
        kind: ChartRequestKind,
    },
}

struct ChartDataEvent {
    symbol: String,
    range: ChartRange,
    request_id: u64,
    kind: ChartRequestKind,
    fetched_at: u64,
    result: io::Result<Vec<HistoricalCandle>>,
}

#[derive(Clone, Copy)]
enum AnalysisRequestKind {
    Detail,
    Background,
}

enum AnalysisControl {
    Load {
        symbol: String,
        request_id: u64,
        kind: AnalysisRequestKind,
        fundamentals: FundamentalSnapshot,
    },
}

struct AnalysisDataEvent {
    symbol: String,
    request_id: u64,
    kind: AnalysisRequestKind,
    fundamentals: FundamentalSnapshot,
    fetched_at: u64,
    result: io::Result<FundamentalTimeseries>,
}

enum AppEvent {
    Input(KeyEvent),
    Resize,
    FeedBatch(Vec<FeedEvent>),
    FeedStatus(FeedProgressStatus),
    ChartData(ChartDataEvent),
    AnalysisData(AnalysisDataEvent),
    HistoryLoaded {
        symbol: String,
        result: io::Result<Vec<persistence::PersistedRevisionRecord>>,
    },
    PersistenceStatus(PersistenceStatusEvent),
    Fatal(io::Error),
    Shutdown,
}

#[derive(Clone)]
struct AppEventPublisher {
    sender: mpsc::Sender<AppEvent>,
}

impl AppEventPublisher {
    fn new(sender: mpsc::Sender<AppEvent>) -> Self {
        Self { sender }
    }

    fn publish(&self, event: AppEvent) -> bool {
        self.sender.send(event).is_ok()
    }
}

#[derive(Default)]
struct RateTracker {
    applied_events: VecDeque<Instant>,
}

impl RateTracker {
    fn record_batch(&mut self, applied_count: usize, now: Instant) {
        for _ in 0..applied_count {
            self.applied_events.push_back(now);
        }

        self.prune(now);
    }

    fn current_rate(&mut self, now: Instant) -> usize {
        self.prune(now);
        self.applied_events.len()
    }

    fn prune(&mut self, now: Instant) {
        while let Some(oldest_event) = self.applied_events.front() {
            if now.saturating_duration_since(*oldest_event) > EVENT_RATE_WINDOW {
                self.applied_events.pop_front();
            } else {
                break;
            }
        }
    }
}

enum LoopControl {
    Continue,
    Exit,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
enum IssueSeverity {
    Warning,
    Error,
    Critical,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum IssueSource {
    Feed,
    Persistence,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HealthStatus {
    Healthy,
    Degraded,
    Down,
    Critical,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum OverlayMode {
    None,
    IssueLog,
    TickerDetail(String),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum DetailTab {
    Snapshot,
    History,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HistorySubview {
    Graphs,
    Table,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HistoryMetricGroup {
    Core,
    Fundamentals,
    Relative,
    Dcf,
    Chart,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HistoryWindow {
    Day,
    Week,
    Month,
    Quarter,
    Year,
    All,
}

#[derive(Clone, Debug)]
struct HistoryViewState {
    subview: HistorySubview,
    group: HistoryMetricGroup,
    window: HistoryWindow,
    scroll: usize,
}

impl Default for HistoryViewState {
    fn default() -> Self {
        Self {
            subview: HistorySubview::Graphs,
            group: HistoryMetricGroup::Core,
            window: HistoryWindow::Month,
            scroll: 0,
        }
    }
}

#[derive(Clone, Debug)]
struct OperationalIssue {
    key: String,
    source: IssueSource,
    severity: IssueSeverity,
    title: String,
    detail: String,
    count: usize,
    first_seen_event: usize,
    last_seen_event: usize,
    active: bool,
}

#[derive(Clone, Debug)]
struct IssueToast {
    severity: IssueSeverity,
    source: IssueSource,
    title: String,
    detail: String,
    expires_at: Instant,
}

struct IssueCenter {
    next_event: usize,
    issues: VecDeque<OperationalIssue>,
    toast: Option<IssueToast>,
}

impl Default for IssueCenter {
    fn default() -> Self {
        Self {
            next_event: 0,
            issues: VecDeque::new(),
            toast: None,
        }
    }
}

impl IssueCenter {
    fn raise(
        &mut self,
        key: &str,
        source: IssueSource,
        severity: IssueSeverity,
        title: impl Into<String>,
        detail: impl Into<String>,
    ) {
        let title = title.into();
        let detail = detail.into();
        let event = self.next_event();

        if let Some(issue) = self.issues.iter_mut().find(|issue| issue.key == key) {
            let was_active = issue.active;
            let changed =
                issue.severity != severity || issue.title != title || issue.detail != detail;
            let escalated = severity > issue.severity;

            issue.source = source;
            issue.severity = severity;
            issue.title = title.clone();
            issue.detail = detail.clone();
            issue.count += 1;
            issue.last_seen_event = event;
            issue.active = true;

            if !was_active || changed || escalated {
                self.set_toast(severity, source, title, detail);
            }

            return;
        }

        if self.issues.len() == MAX_STORED_ISSUES {
            self.issues.pop_back();
        }

        self.issues.push_front(OperationalIssue {
            key: key.to_string(),
            source,
            severity,
            title: title.clone(),
            detail: detail.clone(),
            count: 1,
            first_seen_event: event,
            last_seen_event: event,
            active: true,
        });
        self.set_toast(severity, source, title, detail);
    }

    fn resolve(&mut self, key: &str) {
        let event = self.next_event();

        if let Some(issue) = self
            .issues
            .iter_mut()
            .find(|issue| issue.key == key && issue.active)
        {
            issue.active = false;
            issue.last_seen_event = event;
        }
    }

    fn active_issue_count(&self) -> usize {
        self.issues.iter().filter(|issue| issue.active).count()
    }

    fn resolved_issue_count(&self) -> usize {
        self.issues.iter().filter(|issue| !issue.active).count()
    }

    fn issue_count(&self) -> usize {
        self.issues.len()
    }

    fn clear_resolved(&mut self) {
        self.issues.retain(|issue| issue.active);
    }

    fn health_status(&self) -> HealthStatus {
        if self
            .issues
            .iter()
            .any(|issue| issue.active && issue.severity == IssueSeverity::Critical)
        {
            return HealthStatus::Critical;
        }

        if self
            .issues
            .iter()
            .any(|issue| issue.active && issue.severity == IssueSeverity::Error)
        {
            return HealthStatus::Down;
        }

        if self
            .issues
            .iter()
            .any(|issue| issue.active && issue.severity == IssueSeverity::Warning)
        {
            return HealthStatus::Degraded;
        }

        HealthStatus::Healthy
    }

    fn latest_active_issue(&self) -> Option<&OperationalIssue> {
        self.issues
            .iter()
            .filter(|issue| issue.active)
            .max_by(|left, right| {
                left.severity
                    .cmp(&right.severity)
                    .then_with(|| left.last_seen_event.cmp(&right.last_seen_event))
            })
    }

    fn toast(&self, now: Instant) -> Option<&IssueToast> {
        self.toast.as_ref().filter(|toast| toast.expires_at > now)
    }

    fn sorted_entries(&self) -> Vec<OperationalIssue> {
        let mut entries = self.issues.iter().cloned().collect::<Vec<_>>();

        entries.sort_by(|left, right| {
            right
                .active
                .cmp(&left.active)
                .then_with(|| right.severity.cmp(&left.severity))
                .then_with(|| right.last_seen_event.cmp(&left.last_seen_event))
        });

        entries
    }

    fn next_event(&mut self) -> usize {
        self.next_event += 1;
        self.next_event
    }

    fn set_toast(
        &mut self,
        severity: IssueSeverity,
        source: IssueSource,
        title: String,
        detail: String,
    ) {
        if severity < IssueSeverity::Warning {
            return;
        }

        self.toast = Some(IssueToast {
            severity,
            source,
            title,
            detail,
            expires_at: Instant::now() + ISSUE_TOAST_DURATION,
        });
    }

    fn export_state(&self) -> Vec<PersistedIssueRecord> {
        self.issues
            .iter()
            .map(|issue| PersistedIssueRecord {
                key: issue.key.clone(),
                source: issue.source,
                severity: issue.severity,
                title: issue.title.clone(),
                detail: issue.detail.clone(),
                count: issue.count,
                first_seen_event: issue.first_seen_event,
                last_seen_event: issue.last_seen_event,
                active: issue.active,
            })
            .collect()
    }

    fn hydrate_from_persisted(&mut self, issues: &[PersistedIssueRecord]) {
        self.issues = issues
            .iter()
            .map(|issue| OperationalIssue {
                key: issue.key.clone(),
                source: issue.source,
                severity: issue.severity,
                title: issue.title.clone(),
                detail: issue.detail.clone(),
                count: issue.count,
                first_seen_event: issue.first_seen_event,
                last_seen_event: issue.last_seen_event,
                active: issue.active,
            })
            .collect();
        self.next_event = issues
            .iter()
            .map(|issue| issue.last_seen_event)
            .max()
            .unwrap_or(0);
        self.toast = None;
    }
}

#[derive(Clone)]
struct LiveSymbolState {
    symbols: Arc<Mutex<Vec<String>>>,
}

impl LiveSymbolState {
    fn new(symbols: Vec<String>) -> Self {
        Self {
            symbols: Arc::new(Mutex::new(symbols)),
        }
    }

    fn read_symbols<T>(&self, read: impl FnOnce(&[String]) -> T) -> T {
        let symbols = match self.symbols.lock() {
            Ok(symbols) => symbols,
            Err(poisoned) => poisoned.into_inner(),
        };

        read(&symbols)
    }

    fn snapshot(&self) -> Vec<String> {
        self.read_symbols(|symbols| symbols.to_vec())
    }

    fn count(&self) -> usize {
        self.read_symbols(|symbols| symbols.len())
    }

    fn add_symbols(&self, new_symbols: Vec<String>) -> Vec<String> {
        self.with_symbols(|symbols| {
            let mut added_symbols = Vec::new();

            for symbol in new_symbols {
                if !symbols.contains(&symbol) {
                    symbols.push(symbol.clone());
                    added_symbols.push(symbol);
                }
            }

            added_symbols
        })
    }

    fn with_symbols<T>(&self, mutate: impl FnOnce(&mut Vec<String>) -> T) -> T {
        let mut symbols = match self.symbols.lock() {
            Ok(symbols) => symbols,
            Err(poisoned) => poisoned.into_inner(),
        };

        mutate(&mut symbols)
    }
}

#[derive(Debug)]
struct RuntimeOptions {
    smoke: bool,
    state_db: Option<PathBuf>,
    persist_enabled: bool,
    symbols: Vec<String>,
    symbols_explicit: bool,
    #[cfg(test)]
    replay_file: Option<PathBuf>,
    #[cfg(test)]
    journal_file: Option<PathBuf>,
    #[cfg(test)]
    watchlist_file: Option<PathBuf>,
}

impl Default for RuntimeOptions {
    fn default() -> Self {
        Self {
            smoke: false,
            state_db: None,
            persist_enabled: true,
            symbols: Vec::new(),
            symbols_explicit: false,
            #[cfg(test)]
            replay_file: None,
            #[cfg(test)]
            journal_file: None,
            #[cfg(test)]
            watchlist_file: None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum FeedFailureKind {
    ProviderError,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct FeedErrorLogger {
    path: PathBuf,
}

impl FeedErrorLogger {
    fn new(path: PathBuf) -> Self {
        Self { path }
    }

    fn log_client_initialization_failure(
        &self,
        tracked_symbols: usize,
        error: &io::Error,
    ) -> io::Result<()> {
        self.append_line(format!(
            "ts={} kind=client_init_error tracked={} detail=\"{}\"",
            unix_timestamp_seconds(),
            tracked_symbols,
            sanitize_feed_log_text(&error.to_string()),
        ))
    }

    fn log_symbol_failure(
        &self,
        symbol: &str,
        kind: FeedFailureKind,
        detail: &str,
    ) -> io::Result<()> {
        self.append_line(format!(
            "ts={} kind={} symbol={} detail=\"{}\"",
            unix_timestamp_seconds(),
            match kind {
                FeedFailureKind::ProviderError => "provider_error",
            },
            symbol,
            sanitize_feed_log_text(detail),
        ))
    }

    fn log_provider_diagnostic(
        &self,
        symbol: &str,
        diagnostic: &market_data::ProviderDiagnostic,
        action: &str,
    ) -> io::Result<()> {
        self.append_line(format!(
            "ts={} kind=provider_coverage symbol={} component={} classification={} retryable={} action={} detail=\"{}\"",
            unix_timestamp_seconds(),
            symbol,
            provider_component_label(diagnostic.component),
            provider_diagnostic_kind_label(diagnostic.kind),
            if diagnostic.retryable { "true" } else { "false" },
            action,
            sanitize_feed_log_text(&diagnostic.detail),
        ))
    }

    fn log_refresh_summary(
        &self,
        tracked_symbols: usize,
        fresh_symbols: usize,
        stale_symbols: usize,
        degraded_symbols: usize,
        unavailable_symbols: usize,
        last_error: Option<&str>,
    ) -> io::Result<()> {
        let mut line = format!(
            "ts={} kind=refresh_summary tracked={} fresh={} stale={} degraded={} unavailable={}",
            unix_timestamp_seconds(),
            tracked_symbols,
            fresh_symbols,
            stale_symbols,
            degraded_symbols,
            unavailable_symbols,
        );

        if let Some(last_error) = last_error {
            line.push_str(&format!(
                " last_error=\"{}\"",
                sanitize_feed_log_text(last_error),
            ));
        }

        self.append_line(line)
    }

    fn log_debug(&self, detail: &str) -> io::Result<()> {
        self.append_line(format!(
            "ts={} kind=debug detail=\"{}\"",
            unix_timestamp_seconds(),
            sanitize_feed_log_text(detail),
        ))
    }

    fn append_line(&self, line: String) -> io::Result<()> {
        if let Some(parent) = self
            .path
            .parent()
            .filter(|path| !path.as_os_str().is_empty())
        {
            std::fs::create_dir_all(parent)?;
        }

        let mut file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)?;
        writeln!(file, "{line}")
    }
}

enum InputMode {
    Normal,
    FilterSearch(String),
    SymbolSearch(String),
}

struct AppState {
    paused: bool,
    selected_symbol: Option<String>,
    view_filter: ViewFilter,
    input_mode: InputMode,
    pending_feed: VecDeque<FeedEvent>,
    live_feed_status: Option<FeedProgressStatus>,
    status_message: Option<String>,
    issue_center: IssueCenter,
    overlay_mode: OverlayMode,
    issue_log_selected: usize,
    detail_tab: DetailTab,
    history_view: HistoryViewState,
    detail_chart_range: ChartRange,
    chart_cache: HashMap<ChartCacheKey, ChartCacheEntry>,
    chart_summary_cache: HashMap<ChartCacheKey, ChartRangeSummary>,
    background_chart_requests: HashMap<ChartCacheKey, u64>,
    stale_symbols: HashSet<String>,
    degraded_symbols: HashSet<String>,
    provider_coverage: HashMap<String, SymbolCoverageEvent>,
    live_source_status: Option<LiveSourceStatus>,
    stale_chart_cache: HashSet<ChartCacheKey>,
    warm_start_loaded_at: Option<u64>,
    next_chart_request_id: u64,
    analysis_cache: HashMap<String, AnalysisCacheEntry>,
    next_analysis_request_id: u64,
    history_cache: HashMap<String, Vec<persistence::PersistedRevisionRecord>>,
    history_export_root: Option<PathBuf>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            paused: false,
            selected_symbol: None,
            view_filter: ViewFilter::default(),
            input_mode: InputMode::Normal,
            pending_feed: VecDeque::new(),
            live_feed_status: None,
            status_message: None,
            issue_center: IssueCenter::default(),
            overlay_mode: OverlayMode::None,
            issue_log_selected: 0,
            detail_tab: DetailTab::Snapshot,
            history_view: HistoryViewState::default(),
            detail_chart_range: ChartRange::Year,
            chart_cache: HashMap::new(),
            chart_summary_cache: HashMap::new(),
            background_chart_requests: HashMap::new(),
            stale_symbols: HashSet::new(),
            degraded_symbols: HashSet::new(),
            provider_coverage: HashMap::new(),
            live_source_status: None,
            stale_chart_cache: HashSet::new(),
            warm_start_loaded_at: None,
            next_chart_request_id: 1,
            analysis_cache: HashMap::new(),
            next_analysis_request_id: 1,
            history_cache: HashMap::new(),
            history_export_root: None,
        }
    }
}

impl AppState {
    fn visible_rows(&self, state: &TerminalState) -> Vec<CandidateRow> {
        filtered_symbol_rows(state, &self.view_filter)
            .into_iter()
            .take(MAX_VISIBLE_ROWS)
            .collect()
    }

    fn selected_index(&mut self, rows: &[CandidateRow]) -> usize {
        if rows.is_empty() {
            self.selected_symbol = None;
            return 0;
        }

        if let Some(selected_symbol) = self.selected_symbol.as_deref() {
            if let Some(index) = rows.iter().position(|row| row.symbol == selected_symbol) {
                return index;
            }
        }

        self.selected_symbol = Some(rows[0].symbol.clone());
        0
    }

    fn move_selection(&mut self, rows: &[CandidateRow], delta: isize) -> usize {
        let current_index = self.selected_index(rows);
        if rows.is_empty() {
            return 0;
        }

        let next_index = current_index
            .saturating_add_signed(delta)
            .min(rows.len().saturating_sub(1));
        self.selected_symbol = Some(rows[next_index].symbol.clone());
        next_index
    }

    fn set_selection(&mut self, symbol: &str) {
        self.selected_symbol = Some(symbol.to_string());
    }

    fn clear_filters(&mut self) {
        self.view_filter = ViewFilter::default();
        self.selected_symbol = None;
    }

    fn set_status_message(&mut self, message: impl Into<String>) {
        self.status_message = Some(message.into());
    }

    fn clear_status_message(&mut self) {
        self.status_message = None;
    }

    fn set_history_export_root(&mut self, root: PathBuf) {
        self.history_export_root = Some(root);
    }

    fn open_issue_log(&mut self) {
        self.overlay_mode = OverlayMode::IssueLog;
        self.clamp_issue_log_selection();
    }

    fn open_ticker_detail(&mut self, symbol: &str) {
        self.overlay_mode = OverlayMode::TickerDetail(symbol.to_string());
        self.selected_symbol = Some(symbol.to_string());
        self.detail_tab = DetailTab::Snapshot;
        self.history_view = HistoryViewState::default();
    }

    fn close_overlay(&mut self) {
        self.overlay_mode = OverlayMode::None;
        self.detail_tab = DetailTab::Snapshot;
    }

    fn detail_symbol(&self) -> Option<&str> {
        match &self.overlay_mode {
            OverlayMode::TickerDetail(symbol) => Some(symbol.as_str()),
            _ => None,
        }
    }

    fn move_ticker_detail_selection(&mut self, rows: &[CandidateRow], delta: isize) {
        let Some(current_symbol) = self.detail_symbol() else {
            return;
        };

        let Some(current_index) = rows.iter().position(|row| row.symbol == current_symbol) else {
            return;
        };

        let next_index = current_index
            .saturating_add_signed(delta)
            .min(rows.len().saturating_sub(1));
        let next_symbol = rows[next_index].symbol.clone();
        self.selected_symbol = Some(next_symbol.clone());
        self.overlay_mode = OverlayMode::TickerDetail(next_symbol);
        self.history_view.scroll = 0;
    }

    fn toggle_detail_tab(&mut self) {
        self.detail_tab = match self.detail_tab {
            DetailTab::Snapshot => DetailTab::History,
            DetailTab::History => DetailTab::Snapshot,
        };
        if self.detail_tab == DetailTab::History {
            self.history_view.subview = HistorySubview::Graphs;
        }
        self.history_view.scroll = 0;
    }

    fn toggle_history_subview(&mut self) {
        self.history_view.subview = match self.history_view.subview {
            HistorySubview::Graphs => HistorySubview::Table,
            HistorySubview::Table => HistorySubview::Graphs,
        };
        self.history_view.scroll = 0;
    }

    fn select_history_group(&mut self, group: HistoryMetricGroup) {
        self.history_view.group = group;
        self.history_view.scroll = 0;
    }

    fn cycle_history_window(&mut self, delta: isize) {
        let windows = history_windows();
        let current_index = windows
            .iter()
            .position(|window| *window == self.history_view.window)
            .unwrap_or(2);
        let next_index = current_index
            .saturating_add_signed(delta)
            .clamp(0, windows.len().saturating_sub(1));
        self.history_view.window = windows[next_index];
        self.history_view.scroll = 0;
    }

    fn scroll_history(&mut self, delta: isize) {
        self.history_view.scroll = self.history_view.scroll.saturating_add_signed(delta);
    }

    fn detail_chart_range(&self) -> ChartRange {
        self.detail_chart_range
    }

    fn set_detail_chart_range(&mut self, range: ChartRange) -> bool {
        if self.detail_chart_range == range {
            return false;
        }

        self.detail_chart_range = range;
        true
    }

    fn cycle_detail_chart_range(&mut self, delta: isize) -> bool {
        let ranges = chart_ranges();
        let current_index = ranges
            .iter()
            .position(|range| *range == self.detail_chart_range)
            .unwrap_or(3);
        let next_index = current_index
            .saturating_add_signed(delta)
            .min(ranges.len().saturating_sub(1));

        self.set_detail_chart_range(ranges[next_index])
    }

    fn queue_detail_chart_request(
        &mut self,
        chart_control_sender: Option<&mpsc::Sender<ChartControl>>,
    ) {
        let Some(symbol) = self.detail_symbol().map(str::to_string) else {
            return;
        };

        self.queue_chart_request(
            chart_control_sender,
            &symbol,
            self.detail_chart_range,
            ChartRequestKind::Detail,
        );
    }

    fn queue_chart_request(
        &mut self,
        chart_control_sender: Option<&mpsc::Sender<ChartControl>>,
        symbol: &str,
        range: ChartRange,
        kind: ChartRequestKind,
    ) -> bool {
        let Some(chart_control_sender) = chart_control_sender else {
            return false;
        };
        let key = ChartCacheKey::new(symbol, range);
        let request_id = self.next_chart_request_id;
        self.next_chart_request_id = self.next_chart_request_id.saturating_add(1);
        match kind {
            ChartRequestKind::Detail => {
                if matches!(
                    self.chart_cache.get(&key),
                    Some(ChartCacheEntry::Loading { .. })
                ) {
                    return false;
                }

                if matches!(
                    self.chart_cache.get(&key),
                    Some(ChartCacheEntry::Ready { .. })
                ) && !self.stale_chart_cache.contains(&key)
                {
                    return false;
                }

                let previous = self
                    .chart_cache
                    .get(&key)
                    .and_then(ChartCacheEntry::cached_candles)
                    .map(|candles| candles.to_vec());
                self.chart_cache.insert(
                    key.clone(),
                    ChartCacheEntry::Loading {
                        request_id,
                        previous,
                    },
                );
            }
            ChartRequestKind::Background => {
                if self.background_chart_requests.contains_key(&key)
                    || matches!(
                        self.chart_cache.get(&key),
                        Some(ChartCacheEntry::Loading { .. })
                    )
                {
                    return false;
                }

                if !self.stale_chart_cache.contains(&key)
                    && (self.chart_summary_cache.contains_key(&key)
                        || matches!(
                            self.chart_cache.get(&key),
                            Some(ChartCacheEntry::Ready { .. })
                        ))
                {
                    return false;
                }
                self.background_chart_requests
                    .insert(key.clone(), request_id);
            }
        }

        if chart_control_sender
            .send(ChartControl::Load {
                symbol: symbol.to_string(),
                range,
                request_id,
                kind,
            })
            .is_err()
        {
            match kind {
                ChartRequestKind::Detail => {
                    self.chart_cache.insert(
                        ChartCacheKey::new(symbol, range),
                        ChartCacheEntry::Failed {
                            message: "chart worker channel disconnected".to_string(),
                            previous: None,
                        },
                    );
                }
                ChartRequestKind::Background => {
                    self.background_chart_requests
                        .remove(&ChartCacheKey::new(symbol, range));
                }
            }
            return false;
        }

        true
    }

    fn queue_background_chart_requests(
        &mut self,
        chart_control_sender: Option<&mpsc::Sender<ChartControl>>,
        symbols: &[String],
    ) {
        let mut queued = 0usize;
        for symbol in symbols {
            for range in chart_ranges() {
                if queued >= BACKGROUND_CHART_REQUEST_BUDGET_PER_CYCLE {
                    return;
                }
                if self.queue_chart_request(
                    chart_control_sender,
                    symbol,
                    range,
                    ChartRequestKind::Background,
                ) {
                    queued += 1;
                }
            }
        }
    }

    fn queue_detail_analysis_request(
        &mut self,
        state: &TerminalState,
        analysis_control_sender: Option<&mpsc::Sender<AnalysisControl>>,
    ) {
        let Some(symbol) = self.detail_symbol().map(str::to_string) else {
            return;
        };
        let Some(detail) = state.detail(&symbol) else {
            return;
        };
        let Some(fundamentals) = detail.fundamentals.clone() else {
            return;
        };
        self.queue_analysis_request(
            analysis_control_sender,
            &symbol,
            fundamentals,
            AnalysisRequestKind::Detail,
        );
    }

    fn queue_background_analysis_requests(
        &mut self,
        state: &TerminalState,
        analysis_control_sender: Option<&mpsc::Sender<AnalysisControl>>,
        symbols: &[String],
    ) {
        for symbol in symbols {
            let Some(detail) = state.detail(symbol) else {
                continue;
            };
            let Some(fundamentals) = detail.fundamentals else {
                continue;
            };
            self.queue_analysis_request(
                analysis_control_sender,
                symbol,
                fundamentals,
                AnalysisRequestKind::Background,
            );
        }
    }

    fn queue_analysis_request(
        &mut self,
        analysis_control_sender: Option<&mpsc::Sender<AnalysisControl>>,
        symbol: &str,
        fundamentals: FundamentalSnapshot,
        kind: AnalysisRequestKind,
    ) {
        let Some(analysis_control_sender) = analysis_control_sender else {
            return;
        };
        let analysis_input = analysis_input_key(&fundamentals);

        match self.analysis_cache.get(symbol) {
            Some(
                AnalysisCacheEntry::Loading { input, .. } | AnalysisCacheEntry::Ready { input, .. },
            ) if *input == analysis_input => return,
            Some(AnalysisCacheEntry::Failed { input, .. }) if *input == analysis_input => return,
            _ => {}
        }

        let request_id = self.next_analysis_request_id;
        self.next_analysis_request_id = self.next_analysis_request_id.saturating_add(1);
        self.analysis_cache.insert(
            symbol.to_string(),
            AnalysisCacheEntry::Loading {
                request_id,
                input: analysis_input.clone(),
            },
        );

        if analysis_control_sender
            .send(AnalysisControl::Load {
                symbol: symbol.to_string(),
                request_id,
                kind,
                fundamentals: fundamentals.clone(),
            })
            .is_err()
        {
            self.analysis_cache.insert(
                symbol.to_string(),
                AnalysisCacheEntry::Failed {
                    input: analysis_input,
                    message: "analysis worker channel disconnected".to_string(),
                },
            );
        }
    }

    fn apply_chart_data(&mut self, event: ChartDataEvent) -> Option<PersistedChartRecord> {
        let key = ChartCacheKey::new(&event.symbol, event.range);
        match event.kind {
            ChartRequestKind::Detail => {
                let Some(current_entry) = self.chart_cache.get(&key) else {
                    return None;
                };
                let ChartCacheEntry::Loading {
                    request_id,
                    previous,
                } = current_entry
                else {
                    return None;
                };
                if *request_id != event.request_id {
                    return None;
                }

                let persisted_chart = match &event.result {
                    Ok(candles) => {
                        self.chart_summary_cache.insert(
                            key.clone(),
                            summarize_chart_range(event.range, event.fetched_at, candles),
                        );
                        Some(PersistedChartRecord {
                            symbol: event.symbol.clone(),
                            range: event.range,
                            candles: candles.clone(),
                            fetched_at: event.fetched_at,
                        })
                    }
                    Err(_) => None,
                };
                let previous = previous.clone();
                let next_entry = match event.result {
                    Ok(candles) => {
                        self.stale_chart_cache.remove(&key);
                        ChartCacheEntry::Ready { candles }
                    }
                    Err(error) => ChartCacheEntry::Failed {
                        message: error.to_string(),
                        previous,
                    },
                };
                self.chart_cache.insert(key, next_entry);
                persisted_chart
            }
            ChartRequestKind::Background => {
                let Some(request_id) = self.background_chart_requests.get(&key).copied() else {
                    return None;
                };
                if request_id != event.request_id {
                    return None;
                }
                self.background_chart_requests.remove(&key);
                match &event.result {
                    Ok(candles) => {
                        if self.chart_cache.contains_key(&key) {
                            self.chart_cache.insert(
                                key.clone(),
                                ChartCacheEntry::Ready {
                                    candles: candles.clone(),
                                },
                            );
                        }
                        self.chart_summary_cache.insert(
                            key.clone(),
                            summarize_chart_range(event.range, event.fetched_at, candles),
                        );
                        self.stale_chart_cache.remove(&key);
                        Some(PersistedChartRecord {
                            symbol: event.symbol.clone(),
                            range: event.range,
                            candles: candles.clone(),
                            fetched_at: event.fetched_at,
                        })
                    }
                    Err(_) => None,
                }
            }
        }
    }

    fn detail_chart_entry(&self, symbol: &str) -> Option<&ChartCacheEntry> {
        self.chart_cache
            .get(&ChartCacheKey::new(symbol, self.detail_chart_range))
    }

    fn chart_summary(&self, symbol: &str, range: ChartRange) -> Option<&ChartRangeSummary> {
        self.chart_summary_cache
            .get(&ChartCacheKey::new(symbol, range))
    }

    fn apply_analysis_data(
        &mut self,
        event: AnalysisDataEvent,
    ) -> (Option<FundamentalTimeseries>, Option<DcfAnalysis>) {
        let Some(current_entry) = self.analysis_cache.get(&event.symbol) else {
            return (None, None);
        };
        let AnalysisCacheEntry::Loading { request_id, input } = current_entry else {
            return (None, None);
        };
        if *request_id != event.request_id {
            return (None, None);
        }
        match event.kind {
            AnalysisRequestKind::Detail | AnalysisRequestKind::Background => {}
        }

        let input = input.clone();
        match event.result {
            Ok(timeseries) => {
                let dcf_result = compute_dcf_analysis(&event.fundamentals, &timeseries);
                let next_entry = match dcf_result {
                    Ok(analysis) => {
                        let next_entry = AnalysisCacheEntry::Ready {
                            input,
                            analysis: analysis.clone(),
                        };
                        self.analysis_cache.insert(event.symbol, next_entry);
                        return (Some(timeseries), Some(analysis));
                    }
                    Err(error) => AnalysisCacheEntry::Failed {
                        input,
                        message: error.to_string(),
                    },
                };
                self.analysis_cache.insert(event.symbol, next_entry);
                (Some(timeseries), None)
            }
            Err(error) => {
                self.analysis_cache.insert(
                    event.symbol,
                    AnalysisCacheEntry::Failed {
                        input,
                        message: error.to_string(),
                    },
                );
                (None, None)
            }
        }
    }

    fn detail_analysis_entry(&self, symbol: &str) -> Option<&AnalysisCacheEntry> {
        self.analysis_cache.get(symbol)
    }

    fn load_detail_history(&mut self, persistence_handle: Option<&PersistenceHandle>) {
        let Some(symbol) = self.detail_symbol().map(str::to_string) else {
            return;
        };
        let Some(persistence_handle) = persistence_handle else {
            return;
        };
        persistence_handle.request_symbol_history(symbol);
    }

    fn detail_history(&self, symbol: &str) -> &[persistence::PersistedRevisionRecord] {
        self.history_cache
            .get(symbol)
            .map(Vec::as_slice)
            .unwrap_or(&[])
    }

    fn move_issue_log_selection(&mut self, delta: isize) {
        let issue_count = self.issue_center.issue_count();
        if issue_count == 0 {
            self.issue_log_selected = 0;
            return;
        }

        self.issue_log_selected = self
            .issue_log_selected
            .saturating_add_signed(delta)
            .min(issue_count.saturating_sub(1));
    }

    fn clamp_issue_log_selection(&mut self) {
        let issue_count = self.issue_center.issue_count();
        if issue_count == 0 {
            self.issue_log_selected = 0;
            return;
        }

        self.issue_log_selected = self.issue_log_selected.min(issue_count.saturating_sub(1));
    }

    fn pending_count(&self) -> usize {
        self.pending_feed.len()
    }

    fn load_warm_start(
        &mut self,
        chart_cache: &[PersistedChartRecord],
        last_persisted_at: Option<u64>,
        stale_symbols: &[String],
    ) {
        self.warm_start_loaded_at = last_persisted_at;
        self.stale_symbols = stale_symbols.iter().cloned().collect();
        self.chart_cache.clear();
        self.chart_summary_cache.clear();
        self.background_chart_requests.clear();
        self.stale_chart_cache.clear();

        for chart in chart_cache {
            let key = ChartCacheKey::new(&chart.symbol, chart.range);
            self.chart_cache.insert(
                key.clone(),
                ChartCacheEntry::Ready {
                    candles: chart.candles.clone(),
                },
            );
            self.chart_summary_cache.insert(
                key.clone(),
                summarize_chart_range(chart.range, chart.fetched_at, &chart.candles),
            );
            self.stale_chart_cache.insert(key);
        }
    }

    fn mark_symbol_fresh(&mut self, symbol: &str) {
        self.stale_symbols.remove(symbol);
    }

    fn apply_symbol_coverage(
        &mut self,
        state: &TerminalState,
        coverage_event: SymbolCoverageEvent,
    ) {
        let is_degraded = coverage_event.coverage.core
            != market_data::ProviderComponentState::Fresh
            || coverage_event.coverage.external != market_data::ProviderComponentState::Fresh
            || coverage_event.coverage.fundamentals != market_data::ProviderComponentState::Fresh;

        if coverage_event.coverage.core == market_data::ProviderComponentState::Fresh {
            self.mark_symbol_fresh(&coverage_event.symbol);
        } else if state.detail(&coverage_event.symbol).is_some() {
            self.stale_symbols.insert(coverage_event.symbol.clone());
        }

        if is_degraded {
            self.degraded_symbols.insert(coverage_event.symbol.clone());
            self.provider_coverage
                .insert(coverage_event.symbol.clone(), coverage_event);
        } else {
            self.degraded_symbols.remove(&coverage_event.symbol);
            self.provider_coverage.remove(&coverage_event.symbol);
        }
    }

    fn symbol_coverage(&self, symbol: &str) -> Option<&SymbolCoverageEvent> {
        self.provider_coverage.get(symbol)
    }

    fn set_live_source_status(&mut self, status: LiveSourceStatus) {
        self.live_source_status = Some(status);
    }

    fn is_symbol_stale(&self, symbol: &str) -> bool {
        self.stale_symbols.contains(symbol)
    }

    fn is_chart_stale(&self, symbol: &str, range: ChartRange) -> bool {
        self.stale_chart_cache
            .contains(&ChartCacheKey::new(symbol, range))
    }

    fn warm_start_summary(&self) -> Option<String> {
        let loaded_at = self.warm_start_loaded_at?;
        let age_seconds = crate::unix_timestamp_seconds().saturating_sub(loaded_at);
        let stale_symbol_count = self.stale_symbols.len();
        let cached_chart_count = self.chart_cache.len();
        Some(format!(
            "Warm start: cached SQLite state from {} ago. Stale symbols: {}  Cached charts: {}",
            format_age_seconds(age_seconds),
            stale_symbol_count,
            cached_chart_count,
        ))
    }

    fn live_source_status(&self) -> Option<&LiveSourceStatus> {
        self.live_source_status.as_ref()
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
struct ChartCacheKey {
    symbol: String,
    range: ChartRange,
}

impl ChartCacheKey {
    fn new(symbol: &str, range: ChartRange) -> Self {
        Self {
            symbol: symbol.to_string(),
            range,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum ChartCacheEntry {
    Loading {
        request_id: u64,
        previous: Option<Vec<HistoricalCandle>>,
    },
    Ready {
        candles: Vec<HistoricalCandle>,
    },
    Failed {
        message: String,
        previous: Option<Vec<HistoricalCandle>>,
    },
}

impl ChartCacheEntry {
    fn cached_candles(&self) -> Option<&[HistoricalCandle]> {
        match self {
            Self::Loading {
                previous: Some(candles),
                ..
            }
            | Self::Failed {
                previous: Some(candles),
                ..
            } => Some(candles.as_slice()),
            Self::Ready { candles } => Some(candles.as_slice()),
            _ => None,
        }
    }
}

#[derive(Clone, Debug)]
enum AnalysisCacheEntry {
    Loading {
        request_id: u64,
        input: AnalysisInputKey,
    },
    Ready {
        input: AnalysisInputKey,
        analysis: DcfAnalysis,
    },
    Failed {
        input: AnalysisInputKey,
        message: String,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct AnalysisInputKey {
    symbol: String,
    shares_outstanding: Option<u64>,
    total_debt_dollars: Option<i64>,
    total_cash_dollars: Option<i64>,
    beta_millis: Option<i32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) enum DcfSignal {
    Opportunity,
    Fair,
    Expensive,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub(crate) struct DcfAnalysis {
    bear_intrinsic_value_cents: i64,
    base_intrinsic_value_cents: i64,
    bull_intrinsic_value_cents: i64,
    wacc_bps: i32,
    base_growth_bps: i32,
    net_debt_dollars: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) enum RelativeStrengthBand {
    Strong,
    Mixed,
    Weak,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct RelativeMetricScore {
    label: String,
    percentile: u8,
    band: RelativeStrengthBand,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct SectorRelativeScore {
    group_kind: String,
    group_label: String,
    peer_count: usize,
    composite_percentile: u8,
    composite_band: RelativeStrengthBand,
    metrics: Vec<RelativeMetricScore>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub(crate) struct ChartRangeSummary {
    pub range: ChartRange,
    pub captured_at: u64,
    pub candle_count: usize,
    pub latest_close_cents: Option<i64>,
    pub ema20_cents: Option<i64>,
    pub ema50_cents: Option<i64>,
    pub ema200_cents: Option<i64>,
    pub macd_cents: Option<i64>,
    pub signal_cents: Option<i64>,
    pub histogram_cents: Option<i64>,
}

struct DetailAnalysisSnapshot<'a> {
    analysis: Option<&'a DcfAnalysis>,
    status: String,
    note: Option<String>,
    color: Color,
}

fn compute_dcf_analysis(
    fundamentals: &FundamentalSnapshot,
    timeseries: &FundamentalTimeseries,
) -> io::Result<DcfAnalysis> {
    if timeseries.free_cash_flow.len() < 3 {
        return Err(io::Error::other(
            "DCF unavailable: need at least 3 annual free cash flow points.",
        ));
    }

    let latest_fcf = timeseries
        .free_cash_flow
        .last()
        .map(|point| point.value)
        .filter(|value| *value > 0.0)
        .ok_or_else(|| {
            io::Error::other("DCF unavailable: latest annual free cash flow is not positive.")
        })?;
    let current_shares = latest_share_count(fundamentals, timeseries)
        .ok_or_else(|| io::Error::other("DCF unavailable: share count is missing."))?;
    let fcf_per_share = free_cash_flow_per_share_series(fundamentals, timeseries, current_shares);
    let base_growth_bps = derive_base_growth_bps(&fcf_per_share).ok_or_else(|| {
        io::Error::other("DCF unavailable: insufficient positive free cash flow per share history.")
    })?;
    let wacc_bps = derive_wacc_bps(fundamentals, timeseries)?;
    let net_debt_dollars = fundamentals
        .total_debt_dollars
        .unwrap_or(0)
        .saturating_sub(fundamentals.total_cash_dollars.unwrap_or(0));

    let bear_growth_bps = (base_growth_bps - SCENARIO_GROWTH_SPREAD_BPS)
        .clamp(BEAR_GROWTH_MIN_BPS, BEAR_GROWTH_MAX_BPS);
    let base_growth_bps = base_growth_bps.clamp(BASE_GROWTH_MIN_BPS, BASE_GROWTH_MAX_BPS);
    let bull_growth_bps = (base_growth_bps + SCENARIO_GROWTH_SPREAD_BPS)
        .clamp(BULL_GROWTH_MIN_BPS, BULL_GROWTH_MAX_BPS);

    let bear_intrinsic_value_cents = discounted_intrinsic_value_per_share_cents(
        latest_fcf,
        current_shares,
        net_debt_dollars,
        bear_growth_bps,
        clamp_terminal_growth_bps(BEAR_TERMINAL_GROWTH_BPS, wacc_bps),
        wacc_bps,
    )
    .ok_or_else(|| io::Error::other("DCF unavailable: bear scenario produced an invalid value."))?;
    let base_intrinsic_value_cents = discounted_intrinsic_value_per_share_cents(
        latest_fcf,
        current_shares,
        net_debt_dollars,
        base_growth_bps,
        clamp_terminal_growth_bps(BASE_TERMINAL_GROWTH_BPS, wacc_bps),
        wacc_bps,
    )
    .ok_or_else(|| io::Error::other("DCF unavailable: base scenario produced an invalid value."))?;
    let bull_intrinsic_value_cents = discounted_intrinsic_value_per_share_cents(
        latest_fcf,
        current_shares,
        net_debt_dollars,
        bull_growth_bps,
        clamp_terminal_growth_bps(BULL_TERMINAL_GROWTH_BPS, wacc_bps),
        wacc_bps,
    )
    .ok_or_else(|| io::Error::other("DCF unavailable: bull scenario produced an invalid value."))?;

    Ok(DcfAnalysis {
        bear_intrinsic_value_cents,
        base_intrinsic_value_cents,
        bull_intrinsic_value_cents,
        wacc_bps,
        base_growth_bps,
        net_debt_dollars,
    })
}

fn latest_share_count(
    fundamentals: &FundamentalSnapshot,
    timeseries: &FundamentalTimeseries,
) -> Option<f64> {
    timeseries
        .diluted_average_shares
        .last()
        .map(|point| point.value)
        .filter(|value| *value > 0.0)
        .or_else(|| fundamentals.shares_outstanding.map(|shares| shares as f64))
}

fn free_cash_flow_per_share_series(
    fundamentals: &FundamentalSnapshot,
    timeseries: &FundamentalTimeseries,
    current_shares: f64,
) -> Vec<(String, f64)> {
    timeseries
        .free_cash_flow
        .iter()
        .filter_map(|fcf_point| {
            let shares = share_count_for_date(timeseries, &fcf_point.as_of_date)
                .or_else(|| fundamentals.shares_outstanding.map(|shares| shares as f64))
                .unwrap_or(current_shares);
            if shares <= 0.0 {
                return None;
            }

            Some((fcf_point.as_of_date.clone(), fcf_point.value / shares))
        })
        .collect()
}

fn share_count_for_date(timeseries: &FundamentalTimeseries, as_of_date: &str) -> Option<f64> {
    timeseries
        .diluted_average_shares
        .iter()
        .rev()
        .find(|point| point.as_of_date.as_str() <= as_of_date)
        .map(|point| point.value)
        .filter(|value| *value > 0.0)
}

fn derive_base_growth_bps(fcf_per_share: &[(String, f64)]) -> Option<i32> {
    let latest_index = fcf_per_share.iter().rposition(|(_, value)| *value > 0.0)?;
    let latest = fcf_per_share.get(latest_index)?;
    let first_index = fcf_per_share.iter().position(|(_, value)| *value > 0.0)?;
    let first = fcf_per_share.get(first_index)?;
    let years = elapsed_years_between(&first.0, &latest.0)
        .filter(|years| *years > 0.0)
        .unwrap_or((latest_index - first_index) as f64);
    if years <= 0.0 {
        return None;
    }

    let cagr = (latest.1 / first.1).powf(1.0 / years) - 1.0;
    if !cagr.is_finite() {
        return None;
    }

    rounded_f64_to_i32(cagr * 10_000.0)
}

fn elapsed_years_between(start: &str, end: &str) -> Option<f64> {
    let start_days =
        parse_ymd(start).and_then(|(year, month, day)| days_from_civil(year, month, day))?;
    let end_days =
        parse_ymd(end).and_then(|(year, month, day)| days_from_civil(year, month, day))?;
    let elapsed_days = end_days - start_days;
    (elapsed_days > 0).then_some(elapsed_days as f64 / 365.2425)
}

fn parse_ymd(date: &str) -> Option<(i32, u32, u32)> {
    let mut parts = date.split('-');
    let year = parts.next()?.parse::<i32>().ok()?;
    let month = parts.next()?.parse::<u32>().ok()?;
    let day = parts.next()?.parse::<u32>().ok()?;
    if parts.next().is_some() || !(1..=12).contains(&month) {
        return None;
    }
    if !(1..=days_in_month(year, month)?).contains(&day) {
        return None;
    }
    Some((year, month, day))
}

fn days_in_month(year: i32, month: u32) -> Option<u32> {
    let days = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if is_leap_year(year) => 29,
        2 => 28,
        _ => return None,
    };
    Some(days)
}

fn is_leap_year(year: i32) -> bool {
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}

fn days_from_civil(year: i32, month: u32, day: u32) -> Option<i64> {
    let year = year - if month <= 2 { 1 } else { 0 };
    let era = if year >= 0 { year } else { year - 399 } / 400;
    let year_of_era = year - era * 400;
    let month = month as i32;
    let day = day as i32;
    let day_of_year = (153 * (month + if month > 2 { -3 } else { 9 }) + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    Some((era * 146_097 + day_of_era - 719_468) as i64)
}

fn rounded_f64_to_i32(value: f64) -> Option<i32> {
    if !value.is_finite() {
        return None;
    }
    let rounded = value.round();
    (rounded >= i32::MIN as f64 && rounded <= i32::MAX as f64).then_some(rounded as i32)
}

fn derive_wacc_bps(
    fundamentals: &FundamentalSnapshot,
    timeseries: &FundamentalTimeseries,
) -> io::Result<i32> {
    let market_cap = fundamentals
        .market_cap_dollars
        .filter(|value| *value > 0)
        .ok_or_else(|| io::Error::other("DCF unavailable: market cap is missing."))?
        as f64;
    let beta = fundamentals.beta_millis.unwrap_or(1_000) as f64 / 1_000.0;
    let cost_of_equity_bps =
        RISK_FREE_RATE_BPS + (beta * EQUITY_RISK_PREMIUM_BPS as f64).round() as i32;
    let total_debt = fundamentals.total_debt_dollars.unwrap_or(0).max(0) as f64;
    let total_cash = fundamentals.total_cash_dollars.unwrap_or(0).max(0) as f64;
    let net_debt = (total_debt - total_cash).max(0.0);
    let debt_weight_base = market_cap + net_debt;
    let equity_weight = if debt_weight_base > 0.0 {
        market_cap / debt_weight_base
    } else {
        1.0
    };
    let debt_weight = if debt_weight_base > 0.0 {
        net_debt / debt_weight_base
    } else {
        0.0
    };

    let latest_interest_expense = timeseries
        .interest_expense
        .last()
        .map(|point| point.value.abs());
    let cost_of_debt_bps = if total_debt > 0.0 {
        latest_interest_expense
            .map(|interest| ((interest / total_debt) * 10_000.0).round() as i32)
            .unwrap_or(DEFAULT_COST_OF_DEBT_BPS)
            .clamp(MIN_COST_OF_DEBT_BPS, MAX_COST_OF_DEBT_BPS)
    } else {
        DEFAULT_COST_OF_DEBT_BPS
    };
    let tax_rate_bps = timeseries
        .tax_rate_for_calcs
        .last()
        .map(|point| (point.value * 10_000.0).round() as i32)
        .unwrap_or(DEFAULT_TAX_RATE_BPS)
        .clamp(0, 3_500);

    let after_tax_cost_of_debt_bps =
        ((cost_of_debt_bps as f64) * (1.0 - tax_rate_bps as f64 / 10_000.0)).round() as i32;
    let weighted = (equity_weight * cost_of_equity_bps as f64)
        + (debt_weight * after_tax_cost_of_debt_bps as f64);
    Ok((weighted.round() as i32).clamp(MIN_WACC_BPS, MAX_WACC_BPS))
}

fn clamp_terminal_growth_bps(terminal_growth_bps: i32, wacc_bps: i32) -> i32 {
    terminal_growth_bps.min(wacc_bps.saturating_sub(50)).max(50)
}

fn discounted_intrinsic_value_per_share_cents(
    latest_fcf_dollars: f64,
    current_shares: f64,
    net_debt_dollars: i64,
    growth_bps: i32,
    terminal_growth_bps: i32,
    wacc_bps: i32,
) -> Option<i64> {
    if latest_fcf_dollars <= 0.0 || current_shares <= 0.0 || terminal_growth_bps >= wacc_bps {
        return None;
    }

    let growth = growth_bps as f64 / 10_000.0;
    let terminal_growth = terminal_growth_bps as f64 / 10_000.0;
    let wacc = wacc_bps as f64 / 10_000.0;
    let mut projected_fcf = latest_fcf_dollars;
    let mut present_value = 0.0f64;

    for year in 1..=DCF_PROJECTION_YEARS {
        projected_fcf *= 1.0 + growth;
        present_value += projected_fcf / (1.0 + wacc).powi(year as i32);
    }

    let terminal_cash_flow = projected_fcf * (1.0 + terminal_growth);
    let terminal_value = terminal_cash_flow / (wacc - terminal_growth);
    let enterprise_value =
        present_value + terminal_value / (1.0 + wacc).powi(DCF_PROJECTION_YEARS as i32);
    let equity_value = enterprise_value - net_debt_dollars as f64;
    if !equity_value.is_finite() || equity_value <= 0.0 {
        return None;
    }

    Some(((equity_value / current_shares) * 100.0).round() as i64)
}

fn dcf_margin_of_safety_bps(analysis: &DcfAnalysis, market_price_cents: i64) -> Option<i32> {
    if analysis.base_intrinsic_value_cents <= 0 || market_price_cents <= 0 {
        return None;
    }

    let scaled_gap_bps =
        ((analysis.base_intrinsic_value_cents as i128 - market_price_cents as i128) * 10_000)
            / analysis.base_intrinsic_value_cents as i128;
    i32::try_from(scaled_gap_bps).ok()
}

fn dcf_signal(analysis: &DcfAnalysis, market_price_cents: i64) -> DcfSignal {
    match dcf_margin_of_safety_bps(analysis, market_price_cents).unwrap_or(i32::MIN) {
        value if value >= DCF_OPPORTUNITY_THRESHOLD_BPS => DcfSignal::Opportunity,
        value if value < DCF_EXPENSIVE_THRESHOLD_BPS => DcfSignal::Expensive,
        _ => DcfSignal::Fair,
    }
}

fn compute_sector_relative_score(
    state: &TerminalState,
    fundamentals: &FundamentalSnapshot,
) -> Option<SectorRelativeScore> {
    let subject_symbol = fundamentals.symbol.as_str();
    let industry_key = fundamentals.industry_key.as_deref();
    let sector_key = fundamentals.sector_key.as_deref();
    let industry_name = fundamentals.industry_name.as_deref();
    let sector_name = fundamentals.sector_name.as_deref();

    let industry_peers = industry_key
        .map(|key| {
            state
                .fundamentals_iter()
                .filter(|(symbol, peer)| {
                    *symbol != subject_symbol && peer.industry_key.as_deref() == Some(key)
                })
                .map(|(_, peer)| peer.clone())
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let (group_kind, group_label, peers) = if industry_peers.len() >= MIN_RELATIVE_PEERS {
        (
            "industry".to_string(),
            industry_name.unwrap_or("unknown industry").to_string(),
            industry_peers,
        )
    } else {
        let sector_peers = sector_key
            .map(|key| {
                state
                    .fundamentals_iter()
                    .filter(|(symbol, peer)| {
                        *symbol != subject_symbol && peer.sector_key.as_deref() == Some(key)
                    })
                    .map(|(_, peer)| peer.clone())
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        if sector_peers.len() < MIN_RELATIVE_PEERS {
            return None;
        }
        (
            "sector".to_string(),
            sector_name.unwrap_or("unknown sector").to_string(),
            sector_peers,
        )
    };

    let metric_scores = [
        relative_metric_score("P/E", fundamental_trailing_pe, true, fundamentals, &peers),
        relative_metric_score("PEG", fundamental_peg, true, fundamentals, &peers),
        relative_metric_score("ROE", fundamental_roe, false, fundamentals, &peers),
        relative_metric_score(
            "Net debt/EBITDA",
            fundamental_net_debt_to_ebitda,
            true,
            fundamentals,
            &peers,
        ),
        relative_metric_score(
            "FCF yield",
            fundamental_fcf_yield,
            false,
            fundamentals,
            &peers,
        ),
    ]
    .into_iter()
    .flatten()
    .collect::<Vec<_>>();

    if metric_scores.len() < 3 {
        return None;
    }

    let composite_percentile = robust_composite_percentile(&metric_scores)?;

    Some(SectorRelativeScore {
        group_kind,
        group_label,
        peer_count: peers.len(),
        composite_percentile,
        composite_band: relative_strength_band(composite_percentile),
        metrics: metric_scores,
    })
}

fn relative_metric_score(
    label: &'static str,
    metric: fn(&FundamentalSnapshot) -> Option<f64>,
    lower_is_better: bool,
    subject: &FundamentalSnapshot,
    peers: &[FundamentalSnapshot],
) -> Option<RelativeMetricScore> {
    let subject_value = metric(subject)?;
    let peer_values = peers
        .iter()
        .filter_map(metric)
        .filter(|value| value.is_finite())
        .collect::<Vec<_>>();
    if peer_values.len() < MIN_RELATIVE_PEERS {
        return None;
    }

    let percentile = empirical_percentile_score(subject_value, &peer_values, lower_is_better)?;

    Some(RelativeMetricScore {
        label: label.to_string(),
        percentile,
        band: relative_strength_band(percentile),
    })
}

fn empirical_percentile_score(
    subject_value: f64,
    peer_values: &[f64],
    lower_is_better: bool,
) -> Option<u8> {
    if !subject_value.is_finite() {
        return None;
    }

    let mut less_count = 0usize;
    let mut equal_count = 0usize;
    let mut total_count = 0usize;

    for peer_value in peer_values
        .iter()
        .copied()
        .filter(|value| value.is_finite())
    {
        total_count += 1;
        match compare_with_tolerance(peer_value, subject_value) {
            Ordering::Less => less_count += 1,
            Ordering::Equal => equal_count += 1,
            Ordering::Greater => {}
        }
    }

    if total_count == 0 {
        return None;
    }

    let mut percentile = (less_count as f64 + (equal_count as f64 / 2.0)) / total_count as f64;
    if lower_is_better {
        percentile = 1.0 - percentile;
    }

    Some((percentile * 100.0).round().clamp(0.0, 100.0) as u8)
}

fn compare_with_tolerance(left: f64, right: f64) -> Ordering {
    if approximately_equal(left, right) {
        Ordering::Equal
    } else {
        left.total_cmp(&right)
    }
}

fn approximately_equal(left: f64, right: f64) -> bool {
    let scale = left.abs().max(right.abs()).max(1.0);
    (left - right).abs() <= scale * 1e-9
}

fn robust_composite_percentile(metric_scores: &[RelativeMetricScore]) -> Option<u8> {
    let mut percentiles = metric_scores
        .iter()
        .map(|score| score.percentile)
        .collect::<Vec<_>>();
    if percentiles.is_empty() {
        return None;
    }

    percentiles.sort_unstable();
    if percentiles.len() < 5 {
        return Some(median_percentile(&percentiles));
    }

    let trim_count = (percentiles.len() / 5).max(1);
    if percentiles.len() <= trim_count * 2 + 1 {
        return Some(median_percentile(&percentiles));
    }

    let trimmed = &percentiles[trim_count..percentiles.len() - trim_count];
    let composite =
        trimmed.iter().map(|value| *value as u32).sum::<u32>() as f64 / trimmed.len() as f64;
    Some(composite.round().clamp(0.0, 100.0) as u8)
}

fn median_percentile(percentiles: &[u8]) -> u8 {
    debug_assert!(!percentiles.is_empty());
    let mid = percentiles.len() / 2;
    if percentiles.len() % 2 == 1 {
        percentiles[mid]
    } else {
        let left = percentiles[mid - 1] as u32;
        let right = percentiles[mid] as u32;
        ((left + right) as f64 / 2.0).round() as u8
    }
}

fn relative_strength_band(percentile: u8) -> RelativeStrengthBand {
    if percentile >= STRONG_RELATIVE_SCORE {
        RelativeStrengthBand::Strong
    } else if percentile < WEAK_RELATIVE_SCORE {
        RelativeStrengthBand::Weak
    } else {
        RelativeStrengthBand::Mixed
    }
}

fn fundamental_trailing_pe(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    fundamentals
        .trailing_pe_hundredths
        .map(|value| value as f64 / 100.0)
        .filter(|value| *value >= 0.0)
}

fn fundamental_forward_pe(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    fundamentals
        .forward_pe_hundredths
        .map(|value| value as f64 / 100.0)
        .filter(|value| *value >= 0.0)
}

fn fundamental_price_to_book(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    fundamentals
        .price_to_book_hundredths
        .map(|value| value as f64 / 100.0)
        .filter(|value| *value >= 0.0)
}

fn fundamental_roe(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    fundamentals
        .return_on_equity_bps
        .map(|value| value as f64 / 100.0)
}

fn fundamental_debt_to_equity(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    fundamentals
        .debt_to_equity_hundredths
        .map(|value| value as f64 / 100.0)
}

fn fundamental_ev_to_ebitda(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    fundamentals
        .enterprise_to_ebitda_hundredths
        .map(|value| value as f64 / 100.0)
}

fn fundamental_beta(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    fundamentals.beta_millis.map(|value| value as f64 / 1_000.0)
}

fn fundamental_earnings_growth_percent(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    fundamentals
        .earnings_growth_bps
        .map(|value| value as f64 / 100.0)
}

fn fundamental_peg(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    let pe = fundamental_trailing_pe(fundamentals)?;
    let growth_percent = fundamental_earnings_growth_percent(fundamentals)?;
    (growth_percent > 0.0).then_some(pe / growth_percent)
}

fn fundamental_net_debt_to_ebitda(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    let ebitda = fundamentals.ebitda_dollars? as f64;
    if ebitda <= 0.0 {
        return None;
    }

    let debt = fundamentals.total_debt_dollars.unwrap_or(0) as f64;
    let cash = fundamentals.total_cash_dollars.unwrap_or(0) as f64;
    Some((debt - cash) / ebitda)
}

fn fundamental_debt_or_net_debt_to_ebitda(
    fundamentals: &FundamentalSnapshot,
) -> Option<(&'static str, f64)> {
    let ebitda = fundamentals.ebitda_dollars? as f64;
    if ebitda <= 0.0 {
        return None;
    }

    let debt = fundamentals.total_debt_dollars.unwrap_or(0) as f64;
    if let Some(cash) = fundamentals.total_cash_dollars {
        return Some(("Net debt/EBITDA", (debt - cash as f64) / ebitda));
    }

    Some(("Debt/EBITDA", debt / ebitda))
}

fn fundamental_fcf_yield(fundamentals: &FundamentalSnapshot) -> Option<f64> {
    let market_cap = fundamentals.market_cap_dollars? as f64;
    let fcf = fundamentals.free_cash_flow_dollars? as f64;
    (market_cap > 0.0).then_some(fcf / market_cap)
}

#[derive(Debug)]
struct ErrorContext {
    context: String,
    source: io::Error,
}

impl fmt::Display for ErrorContext {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}", self.context)
    }
}

impl Error for ErrorContext {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        Some(&self.source)
    }
}

struct StartupIssue {
    key: &'static str,
    severity: IssueSeverity,
    title: &'static str,
    detail: String,
}

struct LoadedState {
    state: TerminalState,
    app: AppState,
    tracked_symbols: Vec<String>,
    persistence_db_path: Option<PathBuf>,
    startup_issues: Vec<StartupIssue>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct RenderLine {
    color: Option<Color>,
    text: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct StyledSegment {
    color: Option<Color>,
    text: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct StyledCell {
    ch: char,
    color: Option<Color>,
    priority: u8,
}

#[derive(Clone, Debug, PartialEq)]
struct PriceCandle {
    open_cents: i64,
    high_cents: i64,
    low_cents: i64,
    close_cents: i64,
    volume: u64,
    ema_20_cents: Option<f64>,
    ema_50_cents: Option<f64>,
    ema_200_cents: Option<f64>,
    macd_cents: Option<f64>,
    signal_cents: Option<f64>,
    histogram_cents: Option<f64>,
    point_count: usize,
}

static MAIN_THREAD_ID: OnceLock<thread::ThreadId> = OnceLock::new();
static PANIC_REPORT: OnceLock<Mutex<Option<String>>> = OnceLock::new();

#[derive(Default)]
struct ScreenRenderer {
    displayed: Vec<RenderLine>,
    scratch: Vec<RenderLine>,
    dirty_rows: Vec<usize>,
    last_painted_rows: usize,
}

impl ScreenRenderer {
    fn render(
        &mut self,
        stdout: &mut Stdout,
        lines: &[RenderLine],
        viewport_width: usize,
        viewport_height: usize,
    ) -> io::Result<()> {
        self.scratch.clear();
        normalize_frame_into(lines, viewport_width, viewport_height, &mut self.scratch);

        self.dirty_rows.clear();
        collect_dirty_rows_into(&self.displayed, &self.scratch, viewport_height, &mut self.dirty_rows);
        let clear_rows =
            collect_clear_rows(self.scratch.len(), self.last_painted_rows, viewport_height);

        if self.dirty_rows.is_empty() && clear_rows.is_empty() {
            std::mem::swap(&mut self.displayed, &mut self.scratch);
            if viewport_height >= self.last_painted_rows {
                self.last_painted_rows = self.displayed.len();
            }
            return Ok(());
        }

        queue!(stdout, BeginSynchronizedUpdate)
            .map_err(|error| with_io_context(error, "begin synchronized terminal update"))?;

        for &row_index in &self.dirty_rows {
            paint_row(stdout, row_index, self.scratch.get(row_index))?;
        }

        for row_index in clear_rows {
            paint_row(stdout, row_index, None)?;
        }

        queue!(stdout, EndSynchronizedUpdate)
            .map_err(|error| with_io_context(error, "finish synchronized terminal update"))?;
        stdout
            .flush()
            .map_err(|error| with_io_context(error, "flush terminal output"))?;

        std::mem::swap(&mut self.displayed, &mut self.scratch);
        if viewport_height >= self.last_painted_rows {
            self.last_painted_rows = self.displayed.len();
        }

        Ok(())
    }
}

fn terminal_viewport() -> io::Result<(usize, usize)> {
    let (width, height) =
        terminal::size().map_err(|error| with_io_context(error, "query terminal size"))?;
    Ok((width as usize, height as usize))
}

fn main() {
    install_panic_reporter();

    match panic::catch_unwind(run) {
        Ok(Ok(())) => {}
        Ok(Err(error)) => {
            print_error_report(&error);
            std::process::exit(1);
        }
        Err(_) => {
            eprintln!(
                "{}",
                take_panic_report().unwrap_or_else(|| {
                    "panic: application aborted without details".to_string()
                })
            );
            eprintln!("crash report: {}", crash_report_log_path().display());
            std::process::exit(101);
        }
    }
}

fn install_panic_reporter() {
    let _ = MAIN_THREAD_ID.set(thread::current().id());
    panic::set_hook(Box::new(|panic_info| {
        let report = format_panic_report(panic_info);

        if let Ok(mut slot) = PANIC_REPORT.get_or_init(|| Mutex::new(None)).lock() {
            *slot = Some(report.clone());
        }

        let crash_report_path =
            append_crash_report(&report).unwrap_or_else(|_| crash_report_log_path());

        if MAIN_THREAD_ID
            .get()
            .map(|thread_id| *thread_id != thread::current().id())
            .unwrap_or(true)
        {
            eprintln!("{report}");
            eprintln!("crash report: {}", crash_report_path.display());
        }
    }));
}

fn format_panic_report(panic_info: &PanicHookInfo<'_>) -> String {
    let payload = if let Some(message) = panic_info.payload().downcast_ref::<&str>() {
        *message
    } else if let Some(message) = panic_info.payload().downcast_ref::<String>() {
        message.as_str()
    } else {
        "non-string panic payload"
    };

    let location = panic_info
        .location()
        .map(|location| {
            format!(
                "{}:{}:{}",
                location.file(),
                location.line(),
                location.column()
            )
        })
        .unwrap_or_else(|| "unknown location".to_string());
    let current_thread = thread::current();
    let thread_label = current_thread.name().unwrap_or("unnamed");
    let backtrace = Backtrace::force_capture();

    format!(
        "panic: {payload}\nlocation: {location}\nthread: {thread_label} ({:?})\nbacktrace:\n{backtrace}",
        current_thread.id()
    )
}

fn take_panic_report() -> Option<String> {
    PANIC_REPORT
        .get_or_init(|| Mutex::new(None))
        .lock()
        .ok()
        .and_then(|mut slot| slot.take())
}

fn crash_report_log_path() -> PathBuf {
    PathBuf::from(DEFAULT_CRASH_REPORT_LOG_FILE)
}

fn append_crash_report(report: &str) -> io::Result<PathBuf> {
    let path = crash_report_log_path();
    if let Some(parent) = path.parent().filter(|path| !path.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }

    let mut file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)?;
    writeln!(
        file,
        "=== ts={} pid={} ===\n{}\n",
        unix_timestamp_seconds(),
        std::process::id(),
        report
    )?;
    Ok(path)
}

fn spawn_guarded_thread<F>(name: &'static str, publisher: AppEventPublisher, task: F)
where
    F: FnOnce() + Send + 'static,
{
    let panic_publisher = publisher.clone();
    let spawn_result = thread::Builder::new()
        .name(name.to_string())
        .spawn(move || {
            let result = panic::catch_unwind(AssertUnwindSafe(task));
            if result.is_err() {
                publish_background_panic(&panic_publisher, name);
            }
        });

    if let Err(error) = spawn_result {
        let _ = publisher.publish(AppEvent::Fatal(with_io_context(
            io::Error::other(error),
            format!("spawn {name} worker thread"),
        )));
    }
}

fn publish_background_panic(publisher: &AppEventPublisher, worker_name: &str) {
    let report = take_panic_report().unwrap_or_else(|| {
        format!("panic: background worker '{worker_name}' aborted without details")
    });
    let _ = publisher.publish(AppEvent::Fatal(io::Error::other(format!(
        "background worker '{worker_name}' panicked\n{report}\ncrash report: {}",
        crash_report_log_path().display()
    ))));
}

fn print_error_report(error: &io::Error) {
    eprintln!("error: {error}");

    let mut source = error.source();
    while let Some(cause) = source {
        eprintln!("caused by: {cause}");
        source = cause.source();
    }
}

fn with_io_context(error: io::Error, context: impl Into<String>) -> io::Error {
    let kind = error.kind();
    io::Error::new(
        kind,
        ErrorContext {
            context: context.into(),
            source: error,
        },
    )
}

fn with_path_context(error: io::Error, action: &str, path: &Path) -> io::Error {
    with_io_context(error, format!("{action}: {}", path.display()))
}

fn unix_timestamp_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn sanitize_feed_log_text(text: &str) -> String {
    text.replace('\r', " ").replace('\n', " ").replace('"', "'")
}

fn apply_startup_issues(issue_center: &mut IssueCenter, startup_issues: Vec<StartupIssue>) {
    for startup_issue in startup_issues {
        issue_center.raise(
            startup_issue.key,
            IssueSource::Persistence,
            startup_issue.severity,
            startup_issue.title,
            startup_issue.detail,
        );
    }
}

fn run() -> io::Result<()> {
    let options = parse_runtime_options()?;

    if options.smoke {
        run_smoke();
        return Ok(());
    }

    run_terminal(options)
}

fn run_smoke() {
    let mut state = TerminalState::new(2_000, 30, 8);
    state.ingest_snapshot(MarketSnapshot {
        symbol: "ACME".to_string(),
        company_name: None,
        profitable: true,
        market_price_cents: 8_000,
        intrinsic_value_cents: 10_000,
    });
    state.ingest_external(ExternalValuationSignal {
        symbol: "ACME".to_string(),
        fair_value_cents: 12_000,
        age_seconds: 5,
        low_fair_value_cents: None,
        high_fair_value_cents: None,
        analyst_opinion_count: None,
        recommendation_mean_hundredths: None,
        strong_buy_count: None,
        buy_count: None,
        hold_count: None,
        sell_count: None,
        strong_sell_count: None,
        weighted_fair_value_cents: None,
        weighted_analyst_count: None,
    });

    println!("DISCOUNT TERMINAL SMOKE");
    for row in state.top_rows(5) {
        println!(
            "{} price={} fair={} upside={} confidence={}",
            row.symbol,
            format_money(row.market_price_cents),
            format_money(row.intrinsic_value_cents),
            format_upside_percent(row.market_price_cents, row.intrinsic_value_cents),
            confidence_label(row.confidence)
        );
    }

    for alert in state.alerts() {
        println!(
            "alert={} kind={} seq={}",
            alert.symbol,
            alert_label(alert.kind),
            alert.sequence
        );
    }
}

fn run_terminal(options: RuntimeOptions) -> io::Result<()> {
    let live_mode = true;
    let LoadedState {
        mut state,
        mut app,
        tracked_symbols,
        persistence_db_path,
        startup_issues,
    } = load_initial_state(&options)?;
    let mut last_persisted_sequence = state.latest_sequence();
    let live_symbols = Some(LiveSymbolState::new(tracked_symbols));
    let feed_error_logger = Some(FeedErrorLogger::new(PathBuf::from(
        DEFAULT_FEED_ERROR_LOG_FILE,
    )));
    let (app_event_sender, app_event_receiver) = mpsc::channel();
    let app_event_publisher = AppEventPublisher::new(app_event_sender);
    install_shutdown_publisher(app_event_publisher.clone())
        .map_err(|error| with_io_context(error, "install shutdown signal handler"))?;
    let persistence_handle = if let Some(state_db) = persistence_db_path {
        match persistence::spawn_worker(state_db.clone(), app_event_publisher.clone()) {
            Ok(handle) => Some(handle),
            Err(error) => {
                app.issue_center.raise(
                    ISSUE_KEY_SQLITE_PERSISTENCE,
                    IssueSource::Persistence,
                    IssueSeverity::Warning,
                    "SQLite persistence worker failed",
                    with_path_context(error, "spawn sqlite persistence worker", &state_db)
                        .to_string(),
                );
                None
            }
        }
    } else {
        None
    };
    apply_startup_issues(&mut app.issue_center, startup_issues);
    if let Some(persistence_handle) = persistence_handle.as_ref() {
        if !options.symbols_explicit {
            persistence_handle.replace_tracked_symbols(
                live_symbols
                    .as_ref()
                    .map(LiveSymbolState::snapshot)
                    .unwrap_or_default(),
            );
        }
        persistence_handle.replace_watchlist(state.watchlist_symbols());
        persistence_handle.replace_issues(app.issue_center.export_state());
    }

    let mut stdout = io::stdout();
    let mut terminal_guard = TerminalGuard::default();
    terminal_guard
        .enable_raw_mode()
        .map_err(|error| with_io_context(error, "enable terminal raw mode"))?;
    terminal_guard
        .enter_alternate_screen(&mut stdout)
        .map_err(|error| with_io_context(error, "enter alternate screen"))?;
    let mut screen_renderer = ScreenRenderer::default();
    let mut rate_tracker = RateTracker::default();

    spawn_input_publisher(app_event_publisher.clone());
    let chart_control_sender = spawn_chart_publisher(app_event_publisher.clone());
    let analysis_control_sender = spawn_analysis_publisher(app_event_publisher.clone());
    let feed_control_sender = if let Some(live_symbols) = live_symbols.as_ref() {
        Some(spawn_feed_publisher(
            app_event_publisher.clone(),
            live_symbols.clone(),
            feed_error_logger.clone(),
        ))
    } else {
        None
    };

    let initial_rows = app.visible_rows(&state);
    let initial_selected_index = app.selected_index(&initial_rows);
    if let Some(live_symbols) = live_symbols.as_ref() {
        let tracked = live_symbols.snapshot();
        app.queue_background_chart_requests(Some(&chart_control_sender), &tracked);
        app.queue_background_analysis_requests(&state, Some(&analysis_control_sender), &tracked);
    }
    render(
        &mut stdout,
        &state,
        &initial_rows,
        initial_selected_index,
        rate_tracker.current_rate(Instant::now()),
        live_mode,
        &app,
        live_symbols.as_ref(),
        &mut screen_renderer,
    )
    .map_err(|error| with_io_context(error, "render initial terminal frame"))?;

    let mut runtime_error = None;

    loop {
        let app_event = match app_event_receiver.recv() {
            Ok(app_event) => app_event,
            Err(error) => {
                runtime_error = Some(io::Error::new(
                    ErrorKind::BrokenPipe,
                    format!("application event channel closed unexpectedly: {error}"),
                ));
                break;
            }
        };

        match app_event {
            AppEvent::Input(key_event) => {
                let rows = app.visible_rows(&state);
                let selected_index = app.selected_index(&rows);
                let was_paused = app.paused;

                if let LoopControl::Exit = handle_input_event(
                    key_event,
                    &mut state,
                    &mut app,
                    &rows,
                    selected_index,
                    live_mode,
                    live_symbols.as_ref(),
                    feed_control_sender.as_ref(),
                    Some(&chart_control_sender),
                    Some(&analysis_control_sender),
                    persistence_handle.as_ref(),
                )? {
                    break;
                }

                if was_paused && !app.paused && !app.pending_feed.is_empty() {
                    let pending_feed = std::mem::take(&mut app.pending_feed);
                    let applied_feed_batch = apply_feed_events(
                        &mut state,
                        &mut app,
                        feed_error_logger.as_ref(),
                        pending_feed,
                    );
                    if applied_feed_batch.saw_source_status {
                        synthesize_live_source_status(&state, &mut app, live_symbols.as_ref());
                        log_live_source_summary(
                            feed_error_logger.as_ref(),
                            app.live_source_status(),
                        );
                    }
                    for symbol in &applied_feed_batch.fresh_core_symbols {
                        app.mark_symbol_fresh(symbol);
                    }
                    if applied_feed_batch.applied_events > 0 {
                        rate_tracker
                            .record_batch(applied_feed_batch.applied_events, Instant::now());
                    }
                    app.queue_background_chart_requests(
                        Some(&chart_control_sender),
                        &applied_feed_batch.updated_symbols,
                    );
                    app.queue_background_analysis_requests(
                        &state,
                        Some(&analysis_control_sender),
                        &applied_feed_batch.updated_symbols,
                    );
                    app.queue_detail_analysis_request(&state, Some(&analysis_control_sender));
                    match reconcile_sqlite_persistence(
                        &state,
                        &app,
                        persistence_handle.as_ref(),
                        &mut last_persisted_sequence,
                    ) {
                        Ok(()) => app.issue_center.resolve(ISSUE_KEY_SQLITE_PERSISTENCE),
                        Err(error) => app.issue_center.raise(
                            ISSUE_KEY_SQLITE_PERSISTENCE,
                            IssueSource::Persistence,
                            IssueSeverity::Warning,
                            "SQLite persistence failed",
                            error.to_string(),
                        ),
                    }
                    if let Some(persistence_handle) = persistence_handle.as_ref() {
                        persistence_handle.replace_issues(app.issue_center.export_state());
                    }
                }
            }
            AppEvent::Resize => {}
            AppEvent::FeedBatch(feed_events) => {
                if app.paused {
                    enqueue_paused_feed_batch(&mut app, feed_events);
                    if let Some(persistence_handle) = persistence_handle.as_ref() {
                        persistence_handle.replace_issues(app.issue_center.export_state());
                    }
                } else {
                    let applied_feed_batch = apply_feed_events(
                        &mut state,
                        &mut app,
                        feed_error_logger.as_ref(),
                        feed_events,
                    );
                    if applied_feed_batch.saw_source_status {
                        synthesize_live_source_status(&state, &mut app, live_symbols.as_ref());
                        log_live_source_summary(
                            feed_error_logger.as_ref(),
                            app.live_source_status(),
                        );
                    }
                    for symbol in &applied_feed_batch.fresh_core_symbols {
                        app.mark_symbol_fresh(symbol);
                    }
                    if applied_feed_batch.applied_events > 0 {
                        rate_tracker
                            .record_batch(applied_feed_batch.applied_events, Instant::now());
                    }
                    app.queue_background_chart_requests(
                        Some(&chart_control_sender),
                        &applied_feed_batch.updated_symbols,
                    );
                    app.queue_background_analysis_requests(
                        &state,
                        Some(&analysis_control_sender),
                        &applied_feed_batch.updated_symbols,
                    );
                    app.queue_detail_analysis_request(&state, Some(&analysis_control_sender));
                    match reconcile_sqlite_persistence(
                        &state,
                        &app,
                        persistence_handle.as_ref(),
                        &mut last_persisted_sequence,
                    ) {
                        Ok(()) => app.issue_center.resolve(ISSUE_KEY_SQLITE_PERSISTENCE),
                        Err(error) => app.issue_center.raise(
                            ISSUE_KEY_SQLITE_PERSISTENCE,
                            IssueSource::Persistence,
                            IssueSeverity::Warning,
                            "SQLite persistence failed",
                            error.to_string(),
                        ),
                    }
                    if let Some(persistence_handle) = persistence_handle.as_ref() {
                        persistence_handle.replace_issues(app.issue_center.export_state());
                    }
                }
            }
            AppEvent::FeedStatus(status) => {
                app.live_feed_status = Some(status);
            }
            AppEvent::ChartData(event) => {
                if let Some(chart) = app.apply_chart_data(event) {
                    if let Some(persistence_handle) = persistence_handle.as_ref() {
                        let revisions = build_symbol_revisions(
                            &state,
                            &app,
                            &[chart.symbol.clone()],
                            chart.fetched_at,
                        );
                        let capture = persistence::RawCapture {
                            symbol: chart.symbol.clone(),
                            capture_kind: persistence::CaptureKind::ChartCandles,
                            scope_key: Some(chart_range_label(chart.range).to_string()),
                            captured_at: chart.fetched_at,
                            payload: persistence::RawCapturePayload::Chart {
                                range: chart.range,
                                candles: chart.candles.clone(),
                            },
                        };
                        reconcile_capture_persistence(
                            &mut app,
                            persistence_handle,
                            "persist chart capture",
                            vec![capture],
                            revisions,
                        );
                    }
                }
            }
            AppEvent::AnalysisData(event) => {
                let symbol = event.symbol.clone();
                let fetched_at = event.fetched_at;
                let (timeseries, _analysis) = app.apply_analysis_data(event);
                if let (Some(timeseries), Some(persistence_handle)) =
                    (timeseries, persistence_handle.as_ref())
                {
                    let revisions =
                        build_symbol_revisions(&state, &app, &[symbol.clone()], fetched_at);
                    let capture = persistence::RawCapture {
                        symbol,
                        capture_kind: persistence::CaptureKind::FundamentalTimeseries,
                        scope_key: None,
                        captured_at: fetched_at,
                        payload: persistence::RawCapturePayload::FundamentalTimeseries(timeseries),
                    };
                    reconcile_capture_persistence(
                        &mut app,
                        persistence_handle,
                        "persist fundamental timeseries",
                        vec![capture],
                        revisions,
                    );
                }
            }
            AppEvent::HistoryLoaded { symbol, result } => {
                if let Ok(history) = result {
                    app.history_cache.insert(symbol, history);
                }
            }
            AppEvent::PersistenceStatus(status) => {
                apply_persistence_status(&mut app.issue_center, status);
            }
            AppEvent::Fatal(error) => {
                runtime_error = Some(error);
                break;
            }
            AppEvent::Shutdown => break,
        }

        let rows = app.visible_rows(&state);
        let selected_index = app.selected_index(&rows);
        render(
            &mut stdout,
            &state,
            &rows,
            selected_index,
            rate_tracker.current_rate(Instant::now()),
            live_mode,
            &app,
            live_symbols.as_ref(),
            &mut screen_renderer,
        )
        .map_err(|error| with_io_context(error, "render terminal frame"))?;
    }

    if let Some(runtime_error) = runtime_error {
        drop(terminal_guard);
        if let Some(persistence_handle) = persistence_handle {
            persistence_handle.shutdown(unix_timestamp_seconds());
        }
        return Err(runtime_error);
    }

    drop(terminal_guard);
    if let Some(persistence_handle) = persistence_handle {
        persistence_handle.shutdown(unix_timestamp_seconds());
    }
    Ok(())
}

fn install_shutdown_publisher(publisher: AppEventPublisher) -> io::Result<()> {
    ctrlc::set_handler(move || {
        let _ = publisher.publish(AppEvent::Shutdown);
    })
    .map_err(io::Error::other)
}

fn spawn_input_publisher(publisher: AppEventPublisher) {
    let thread_publisher = publisher.clone();
    spawn_guarded_thread("input", publisher, move || {
        publish_input_events(thread_publisher, event::read)
    });
}

fn publish_input_events<ReadEvent>(publisher: AppEventPublisher, mut read_event: ReadEvent)
where
    ReadEvent: FnMut() -> io::Result<Event>,
{
    loop {
        let event = match read_event() {
            Ok(event) => event,
            Err(error) => {
                let _ = publisher.publish(AppEvent::Fatal(with_io_context(
                    error,
                    "read terminal input event",
                )));
                return;
            }
        };

        match event {
            Event::Key(key_event) if should_handle_key_event(&key_event) => {
                if !publisher.publish(AppEvent::Input(key_event)) {
                    return;
                }
            }
            Event::Resize(_, _) => {
                if !publisher.publish(AppEvent::Resize) {
                    return;
                }
            }
            _ => {}
        }
    }
}

fn spawn_feed_publisher(
    publisher: AppEventPublisher,
    live_symbols: LiveSymbolState,
    feed_error_logger: Option<FeedErrorLogger>,
) -> mpsc::Sender<FeedControl> {
    let (control_sender, control_receiver) = mpsc::channel();
    let scheduler_sender = control_sender.clone();
    let initial_sender = control_sender.clone();
    let feed_thread_publisher = publisher.clone();
    let scheduler_panic_publisher = publisher.clone();
    spawn_guarded_thread("feed", publisher, move || {
        feed_loop(
            feed_thread_publisher,
            control_receiver,
            live_symbols,
            feed_error_logger,
        )
    });
    spawn_guarded_thread("feed-scheduler", scheduler_panic_publisher, move || {
        feed_schedule_loop(scheduler_sender)
    });
    let _ = initial_sender.send(FeedControl::RefreshNow);
    control_sender
}

fn spawn_chart_publisher(publisher: AppEventPublisher) -> mpsc::Sender<ChartControl> {
    let (control_sender, control_receiver) = mpsc::channel();
    let thread_publisher = publisher.clone();
    spawn_guarded_thread("chart", publisher, move || {
        chart_loop(thread_publisher, control_receiver)
    });
    control_sender
}

fn spawn_analysis_publisher(publisher: AppEventPublisher) -> mpsc::Sender<AnalysisControl> {
    let (control_sender, control_receiver) = mpsc::channel();
    let thread_publisher = publisher.clone();
    spawn_guarded_thread("analysis", publisher, move || {
        analysis_loop(thread_publisher, control_receiver)
    });
    control_sender
}

fn feed_schedule_loop(control_sender: mpsc::Sender<FeedControl>) {
    loop {
        thread::sleep(DEFAULT_POLL_INTERVAL);

        if control_sender.send(FeedControl::RefreshNow).is_err() {
            return;
        }
    }
}

trait LiveFeedClient {
    fn fetch_symbol_with_options(
        &self,
        symbol: &str,
        refresh_weighted_target: bool,
    ) -> io::Result<market_data::ProviderFetchResult>;
}

impl LiveFeedClient for MarketDataClient {
    fn fetch_symbol_with_options(
        &self,
        symbol: &str,
        refresh_weighted_target: bool,
    ) -> io::Result<market_data::ProviderFetchResult> {
        MarketDataClient::fetch_symbol_with_options(self, symbol, refresh_weighted_target)
    }
}

trait HistoricalChartClient {
    fn fetch_historical_candles(
        &self,
        symbol: &str,
        range: ChartRange,
    ) -> io::Result<Vec<HistoricalCandle>>;
}

impl HistoricalChartClient for MarketDataClient {
    fn fetch_historical_candles(
        &self,
        symbol: &str,
        range: ChartRange,
    ) -> io::Result<Vec<HistoricalCandle>> {
        MarketDataClient::fetch_historical_candles(self, symbol, range)
    }
}

trait FundamentalTimeseriesClient {
    fn fetch_fundamental_timeseries(&self, symbol: &str) -> io::Result<FundamentalTimeseries>;
}

impl FundamentalTimeseriesClient for MarketDataClient {
    fn fetch_fundamental_timeseries(&self, symbol: &str) -> io::Result<FundamentalTimeseries> {
        MarketDataClient::fetch_fundamental_timeseries(self, symbol)
    }
}

fn chart_loop(publisher: AppEventPublisher, control_receiver: mpsc::Receiver<ChartControl>) {
    chart_loop_with_client_factory(publisher, control_receiver, MarketDataClient::new);
}

fn analysis_loop(publisher: AppEventPublisher, control_receiver: mpsc::Receiver<AnalysisControl>) {
    analysis_loop_with_client_factory(publisher, control_receiver, MarketDataClient::new);
}

fn chart_loop_with_client_factory<Client, BuildClient>(
    publisher: AppEventPublisher,
    control_receiver: mpsc::Receiver<ChartControl>,
    mut build_client: BuildClient,
) where
    Client: HistoricalChartClient,
    BuildClient: FnMut() -> io::Result<Client>,
{
    let mut client = None;

    while let Ok(ChartControl::Load {
        symbol,
        range,
        request_id,
        kind,
    }) = control_receiver.recv()
    {
        if client.is_none() {
            match build_client() {
                Ok(created_client) => client = Some(created_client),
                Err(error) => {
                    let _ = publisher.publish(AppEvent::ChartData(ChartDataEvent {
                        symbol,
                        range,
                        request_id,
                        kind,
                        fetched_at: unix_timestamp_seconds(),
                        result: Err(io::Error::other(format!(
                            "historical chart client initialization failed: {error}"
                        ))),
                    }));
                    continue;
                }
            }
        }

        let Some(client) = client.as_ref() else {
            continue;
        };

        if !publisher.publish(AppEvent::ChartData(ChartDataEvent {
            symbol: symbol.clone(),
            range,
            request_id,
            kind,
            fetched_at: unix_timestamp_seconds(),
            result: client.fetch_historical_candles(&symbol, range),
        })) {
            return;
        }
    }
}

fn analysis_loop_with_client_factory<Client, BuildClient>(
    publisher: AppEventPublisher,
    control_receiver: mpsc::Receiver<AnalysisControl>,
    mut build_client: BuildClient,
) where
    Client: FundamentalTimeseriesClient,
    BuildClient: FnMut() -> io::Result<Client>,
{
    let mut client = None;

    while let Ok(AnalysisControl::Load {
        symbol,
        request_id,
        kind,
        fundamentals,
    }) = control_receiver.recv()
    {
        if client.is_none() {
            match build_client() {
                Ok(created_client) => client = Some(created_client),
                Err(error) => {
                    let _ = publisher.publish(AppEvent::AnalysisData(AnalysisDataEvent {
                        symbol,
                        request_id,
                        kind,
                        fundamentals,
                        fetched_at: unix_timestamp_seconds(),
                        result: Err(io::Error::other(format!(
                            "fundamental analysis client initialization failed: {error}"
                        ))),
                    }));
                    continue;
                }
            }
        }

        let Some(client) = client.as_ref() else {
            continue;
        };

        let result = client.fetch_fundamental_timeseries(&symbol);

        if !publisher.publish(AppEvent::AnalysisData(AnalysisDataEvent {
            symbol,
            request_id,
            kind,
            fundamentals,
            fetched_at: unix_timestamp_seconds(),
            result,
        })) {
            return;
        }
    }
}

struct AppliedFeedBatch {
    applied_events: usize,
    updated_symbols: Vec<String>,
    fresh_core_symbols: Vec<String>,
    saw_source_status: bool,
}

fn apply_feed_events(
    state: &mut TerminalState,
    app: &mut AppState,
    feed_error_logger: Option<&FeedErrorLogger>,
    feed_events: impl IntoIterator<Item = FeedEvent>,
) -> AppliedFeedBatch {
    let mut applied_events = 0usize;
    let mut updated_symbols = HashSet::new();
    let mut fresh_core_symbols = HashSet::new();
    let mut external_symbols = HashSet::new();
    let mut fundamentals_symbols = HashSet::new();
    let mut coverage_symbols = HashSet::new();
    let mut saw_source_status = false;

    for feed_event in feed_events {
        match feed_event {
            FeedEvent::Snapshot(snapshot) => {
                updated_symbols.insert(snapshot.symbol.clone());
                fresh_core_symbols.insert(snapshot.symbol.clone());
                state.ingest_snapshot(snapshot);
                applied_events += 1;
            }
            FeedEvent::External(signal) => {
                updated_symbols.insert(signal.symbol.clone());
                external_symbols.insert(signal.symbol.clone());
                state.ingest_external(signal);
                applied_events += 1;
            }
            FeedEvent::Fundamentals(fundamentals) => {
                updated_symbols.insert(fundamentals.symbol.clone());
                fundamentals_symbols.insert(fundamentals.symbol.clone());
                state.ingest_fundamentals(fundamentals);
                applied_events += 1;
            }
            FeedEvent::Coverage(coverage) => {
                coverage_symbols.insert(coverage.symbol.clone());
                log_symbol_coverage_event(feed_error_logger, state, &coverage);
                app.apply_symbol_coverage(state, coverage);
            }
            FeedEvent::SourceStatus(source_status) => {
                saw_source_status = true;
                app.set_live_source_status(source_status);
            }
        }
    }

    for symbol in updated_symbols.iter().filter(|symbol| {
        fresh_core_symbols.contains(*symbol)
            && external_symbols.contains(*symbol)
            && fundamentals_symbols.contains(*symbol)
            && !coverage_symbols.contains(*symbol)
    }) {
        app.mark_symbol_fresh(symbol);
        app.degraded_symbols.remove(symbol);
        app.provider_coverage.remove(symbol);
    }

    let mut updated_symbols = updated_symbols.into_iter().collect::<Vec<_>>();
    updated_symbols.sort();
    let mut fresh_core_symbols = fresh_core_symbols.into_iter().collect::<Vec<_>>();
    fresh_core_symbols.sort();
    AppliedFeedBatch {
        applied_events,
        updated_symbols,
        fresh_core_symbols,
        saw_source_status,
    }
}

fn enqueue_paused_feed_batch(app: &mut AppState, feed_events: Vec<FeedEvent>) {
    for feed_event in feed_events {
        match feed_event {
            FeedEvent::SourceStatus(source_status) => {
                app.set_live_source_status(source_status.clone());
                apply_live_source_status(&mut app.issue_center, &source_status);
            }
            other_event => {
                app.pending_feed.push_back(other_event);
            }
        }
    }
}

fn reconcile_sqlite_persistence(
    state: &TerminalState,
    app: &AppState,
    persistence_handle: Option<&PersistenceHandle>,
    last_persisted_sequence: &mut usize,
) -> io::Result<()> {
    let Some(persistence_handle) = persistence_handle else {
        *last_persisted_sequence = state.latest_sequence();
        return Ok(());
    };

    let delta = state.journal_since(*last_persisted_sequence);
    if delta.is_empty() {
        return Ok(());
    }

    let symbols = delta_symbols(&delta);
    if symbols.is_empty() {
        *last_persisted_sequence = state.latest_sequence();
        return Ok(());
    }

    let recorded_at = unix_timestamp_seconds();
    let revisions = build_symbol_revisions(state, app, &symbols, recorded_at);
    let max_sequence = delta
        .iter()
        .map(|entry| entry.sequence)
        .max()
        .unwrap_or(*last_persisted_sequence);
    persistence_handle.persist_batch(raw_captures_from_journal(&delta, recorded_at), revisions)?;
    *last_persisted_sequence = max_sequence;
    Ok(())
}

fn delta_symbols(delta: &[discount_screener::JournalEntry]) -> Vec<String> {
    let mut symbols = HashSet::new();
    for entry in delta {
        match &entry.payload {
            discount_screener::JournalPayload::Snapshot(snapshot) => {
                symbols.insert(snapshot.symbol.clone());
            }
            discount_screener::JournalPayload::External(signal) => {
                symbols.insert(signal.symbol.clone());
            }
            discount_screener::JournalPayload::Fundamentals(fundamentals) => {
                symbols.insert(fundamentals.symbol.clone());
            }
            discount_screener::JournalPayload::FundamentalsCleared(symbol) => {
                symbols.insert(symbol.clone());
            }
        }
    }

    let mut symbols = symbols.into_iter().collect::<Vec<_>>();
    symbols.sort();
    symbols
}

fn raw_captures_from_journal(
    delta: &[discount_screener::JournalEntry],
    recorded_at: u64,
) -> Vec<persistence::RawCapture> {
    delta
        .iter()
        .map(|entry| match &entry.payload {
            discount_screener::JournalPayload::Snapshot(snapshot) => persistence::RawCapture {
                symbol: snapshot.symbol.clone(),
                capture_kind: persistence::CaptureKind::Snapshot,
                scope_key: None,
                captured_at: recorded_at,
                payload: persistence::RawCapturePayload::Snapshot(snapshot.clone()),
            },
            discount_screener::JournalPayload::External(signal) => persistence::RawCapture {
                symbol: signal.symbol.clone(),
                capture_kind: persistence::CaptureKind::External,
                scope_key: None,
                captured_at: recorded_at,
                payload: persistence::RawCapturePayload::External(signal.clone()),
            },
            discount_screener::JournalPayload::Fundamentals(fundamentals) => {
                persistence::RawCapture {
                    symbol: fundamentals.symbol.clone(),
                    capture_kind: persistence::CaptureKind::Fundamentals,
                    scope_key: None,
                    captured_at: recorded_at,
                    payload: persistence::RawCapturePayload::Fundamentals(fundamentals.clone()),
                }
            }
            discount_screener::JournalPayload::FundamentalsCleared(symbol) => {
                persistence::RawCapture {
                    symbol: symbol.clone(),
                    capture_kind: persistence::CaptureKind::Fundamentals,
                    scope_key: Some("cleared".to_string()),
                    captured_at: recorded_at,
                    payload: persistence::RawCapturePayload::Fundamentals(FundamentalSnapshot {
                        symbol: symbol.clone(),
                        sector_key: None,
                        sector_name: None,
                        industry_key: None,
                        industry_name: None,
                        market_cap_dollars: None,
                        shares_outstanding: None,
                        trailing_pe_hundredths: None,
                        forward_pe_hundredths: None,
                        price_to_book_hundredths: None,
                        return_on_equity_bps: None,
                        ebitda_dollars: None,
                        enterprise_value_dollars: None,
                        enterprise_to_ebitda_hundredths: None,
                        total_debt_dollars: None,
                        total_cash_dollars: None,
                        debt_to_equity_hundredths: None,
                        free_cash_flow_dollars: None,
                        operating_cash_flow_dollars: None,
                        beta_millis: None,
                        trailing_eps_cents: None,
                        earnings_growth_bps: None,
                    }),
                }
            }
        })
        .collect()
}

fn build_symbol_revisions(
    state: &TerminalState,
    app: &AppState,
    symbols: &[String],
    evaluated_at: u64,
) -> Vec<persistence::SymbolRevisionInput> {
    let mut revisions = symbols
        .iter()
        .filter_map(|symbol| build_symbol_revision(state, app, symbol, evaluated_at))
        .collect::<Vec<_>>();
    revisions.sort_by(|left, right| left.symbol.cmp(&right.symbol));
    revisions
}

fn build_symbol_revision(
    state: &TerminalState,
    app: &AppState,
    symbol: &str,
    evaluated_at: u64,
) -> Option<persistence::SymbolRevisionInput> {
    let detail = state.detail(symbol)?;
    let relative_score = detail
        .fundamentals
        .as_ref()
        .and_then(|fundamentals| compute_sector_relative_score(state, fundamentals));
    let has_relative = relative_score.is_some();
    let dcf_analysis = match app.detail_analysis_entry(symbol) {
        Some(AnalysisCacheEntry::Ready { analysis, .. }) => Some(analysis.clone()),
        _ => None,
    };
    let chart_summaries = chart_ranges()
        .iter()
        .filter_map(|range| app.chart_summary(symbol, *range).cloned())
        .collect::<Vec<_>>();
    Some(persistence::SymbolRevisionInput {
        symbol: symbol.to_string(),
        evaluated_at,
        last_sequence: detail.last_sequence,
        update_count: detail.update_count,
        price_history: state.price_history(symbol, usize::MAX),
        payload: persistence::EvaluatedSymbolState {
            snapshot: Some(MarketSnapshot {
                symbol: detail.symbol.clone(),
                company_name: state.company_name(symbol).map(str::to_string),
                profitable: detail.profitable,
                market_price_cents: detail.market_price_cents,
                intrinsic_value_cents: detail.intrinsic_value_cents,
            }),
            external_signal: detail
                .external_signal_fair_value_cents
                .map(|fair_value_cents| ExternalValuationSignal {
                    symbol: detail.symbol.clone(),
                    fair_value_cents,
                    age_seconds: detail.external_signal_age_seconds.unwrap_or(0),
                    low_fair_value_cents: detail.external_signal_low_fair_value_cents,
                    high_fair_value_cents: detail.external_signal_high_fair_value_cents,
                    analyst_opinion_count: detail.analyst_opinion_count,
                    recommendation_mean_hundredths: detail.recommendation_mean_hundredths,
                    strong_buy_count: detail.strong_buy_count,
                    buy_count: detail.buy_count,
                    hold_count: detail.hold_count,
                    sell_count: detail.sell_count,
                    strong_sell_count: detail.strong_sell_count,
                    weighted_fair_value_cents: detail.weighted_external_signal_fair_value_cents,
                    weighted_analyst_count: detail.weighted_analyst_count,
                }),
            fundamentals: detail.fundamentals.clone(),
            gap_bps: Some(detail.gap_bps),
            qualification: Some(detail.qualification),
            external_status: Some(detail.external_status),
            confidence: Some(detail.confidence),
            external_gap_bps: detail.external_signal_gap_bps,
            weighted_gap_bps: detail.weighted_external_signal_fair_value_cents.and_then(
                |fair_value_cents| checked_gap_bps(detail.market_price_cents, fair_value_cents),
            ),
            dcf_signal: dcf_analysis
                .as_ref()
                .map(|analysis| dcf_signal(analysis, detail.market_price_cents)),
            dcf_margin_of_safety_bps: dcf_analysis
                .as_ref()
                .and_then(|analysis| dcf_margin_of_safety_bps(analysis, detail.market_price_cents)),
            dcf_analysis,
            relative_score,
            chart_summaries: chart_summaries.clone(),
            core_status: persistence::MetricGroupStatus {
                available: true,
                stale: app.is_symbol_stale(symbol),
            },
            fundamentals_status: persistence::MetricGroupStatus {
                available: detail.fundamentals.is_some(),
                stale: false,
            },
            relative_status: persistence::MetricGroupStatus {
                available: has_relative,
                stale: false,
            },
            dcf_status: persistence::MetricGroupStatus {
                available: match app.detail_analysis_entry(symbol) {
                    Some(AnalysisCacheEntry::Ready { .. }) => true,
                    Some(AnalysisCacheEntry::Failed { .. })
                    | Some(AnalysisCacheEntry::Loading { .. })
                    | None => false,
                },
                stale: false,
            },
            chart_status: persistence::MetricGroupStatus {
                available: !chart_summaries.is_empty(),
                stale: chart_summaries.len() < chart_ranges().len(),
            },
            is_watched: detail.is_watched,
        },
    })
}

fn apply_persistence_status(issue_center: &mut IssueCenter, status: PersistenceStatusEvent) {
    if let Some(error) = status.error {
        issue_center.raise(
            ISSUE_KEY_SQLITE_PERSISTENCE,
            IssueSource::Persistence,
            IssueSeverity::Warning,
            "SQLite persistence failed",
            format!("{}: {error}", status.operation),
        );
    } else {
        issue_center.resolve(ISSUE_KEY_SQLITE_PERSISTENCE);
    }
}

fn reconcile_capture_persistence(
    app: &mut AppState,
    persistence_handle: &PersistenceHandle,
    operation: &str,
    raw_captures: Vec<persistence::RawCapture>,
    revisions: Vec<persistence::SymbolRevisionInput>,
) {
    match persistence_handle.persist_batch(raw_captures, revisions) {
        Ok(()) => app.issue_center.resolve(ISSUE_KEY_SQLITE_PERSISTENCE),
        Err(error) => app.issue_center.raise(
            ISSUE_KEY_SQLITE_PERSISTENCE,
            IssueSource::Persistence,
            IssueSeverity::Warning,
            "SQLite persistence failed",
            format!("{operation}: {error}"),
        ),
    }
    persistence_handle.replace_issues(app.issue_center.export_state());
}

#[cfg(test)]
fn reconcile_journal_persistence(
    state: &TerminalState,
    journal_file: Option<&PathBuf>,
    last_persisted_sequence: &mut usize,
    issue_center: &mut IssueCenter,
) {
    match persist_new_journal_entries(state, journal_file, last_persisted_sequence) {
        Ok(()) => {
            issue_center.resolve(ISSUE_KEY_SQLITE_PERSISTENCE);
        }
        Err(error) => {
            issue_center.raise(
                ISSUE_KEY_SQLITE_PERSISTENCE,
                IssueSource::Persistence,
                IssueSeverity::Error,
                "Journal persistence failed",
                error.to_string(),
            );
        }
    }
}

fn handle_input_event(
    key_event: KeyEvent,
    state: &mut TerminalState,
    app: &mut AppState,
    rows: &[CandidateRow],
    selected_index: usize,
    live_mode: bool,
    live_symbols: Option<&LiveSymbolState>,
    feed_control_sender: Option<&mpsc::Sender<FeedControl>>,
    chart_control_sender: Option<&mpsc::Sender<ChartControl>>,
    analysis_control_sender: Option<&mpsc::Sender<AnalysisControl>>,
    persistence_handle: Option<&PersistenceHandle>,
) -> io::Result<LoopControl> {
    if is_force_quit_key(&key_event) {
        return Ok(LoopControl::Exit);
    }

    if matches!(key_event.code, KeyCode::Char('q') | KeyCode::Char('Q'))
        && !matches!(
            app.input_mode,
            InputMode::FilterSearch(_) | InputMode::SymbolSearch(_)
        )
    {
        return Ok(LoopControl::Exit);
    }

    if handle_overlay_key(
        app,
        state,
        &key_event,
        chart_control_sender,
        analysis_control_sender,
        persistence_handle,
    )? {
        return Ok(LoopControl::Continue);
    }

    match &mut app.input_mode {
        InputMode::Normal => match key_event.code {
            KeyCode::Char('q') => return Ok(LoopControl::Exit),
            KeyCode::Down | KeyCode::Char('j') => {
                app.move_selection(rows, 1);
            }
            KeyCode::Up | KeyCode::Char('k') => {
                app.move_selection(rows, -1);
            }
            KeyCode::Enter | KeyCode::Char('d') => {
                if let Some(row) = rows.get(selected_index) {
                    app.open_ticker_detail(&row.symbol);
                    app.queue_detail_chart_request(chart_control_sender);
                    app.queue_detail_analysis_request(state, analysis_control_sender);
                } else {
                    app.set_status_message("Select a ticker to open the detail screen.");
                }
            }
            KeyCode::Char('w') => {
                if let Some(row) = rows.get(selected_index) {
                    state.toggle_watchlist(&row.symbol);
                    if let Some(persistence_handle) = persistence_handle {
                        persistence_handle.replace_watchlist(state.watchlist_symbols());
                    }
                    app.set_selection(&row.symbol);
                }
            }
            KeyCode::Char('f') => {
                app.view_filter.watchlist_only = !app.view_filter.watchlist_only;
                app.selected_symbol = None;
            }
            KeyCode::Char('l') => {
                app.open_issue_log();
            }
            KeyCode::Char('/') => {
                app.input_mode = InputMode::FilterSearch(app.view_filter.query.clone());
                app.clear_status_message();
            }
            KeyCode::Char('s') => {
                if live_mode {
                    app.input_mode = InputMode::SymbolSearch(String::new());
                    app.clear_status_message();
                } else {
                    app.set_status_message("Symbol lookup is only available in live mode.");
                }
            }
            KeyCode::Char(' ') => {
                app.paused = !app.paused;
            }
            KeyCode::Esc | KeyCode::Backspace => {
                app.clear_filters();
            }
            _ => {}
        },
        InputMode::FilterSearch(buffer) => match key_event.code {
            KeyCode::Enter => {
                app.view_filter.query = buffer.clone();
                app.selected_symbol = None;
                app.input_mode = InputMode::Normal;
                app.clear_status_message();
            }
            KeyCode::Esc => {
                app.input_mode = InputMode::Normal;
            }
            KeyCode::Backspace => {
                if should_leave_input_mode_on_backspace(buffer) {
                    app.input_mode = InputMode::Normal;
                }
            }
            KeyCode::Char(character) if !key_event.modifiers.contains(KeyModifiers::CONTROL) => {
                buffer.push(character);
            }
            _ => {}
        },
        InputMode::SymbolSearch(buffer) => match key_event.code {
            KeyCode::Enter => {
                let symbol_query = buffer.clone();
                app.input_mode = InputMode::Normal;
                track_symbols_from_query(
                    &symbol_query,
                    app,
                    live_symbols,
                    feed_control_sender,
                    persistence_handle,
                );
            }
            KeyCode::Esc => {
                app.input_mode = InputMode::Normal;
            }
            KeyCode::Backspace => {
                if should_leave_input_mode_on_backspace(buffer) {
                    app.input_mode = InputMode::Normal;
                }
            }
            KeyCode::Char(character) if !key_event.modifiers.contains(KeyModifiers::CONTROL) => {
                buffer.push(character);
            }
            _ => {}
        },
    }

    Ok(LoopControl::Continue)
}

fn render(
    stdout: &mut Stdout,
    state: &TerminalState,
    rows: &[CandidateRow],
    selected_index: usize,
    updates_per_second: usize,
    live_mode: bool,
    app: &AppState,
    live_symbols: Option<&LiveSymbolState>,
    screen_renderer: &mut ScreenRenderer,
) -> io::Result<()> {
    let (viewport_width, viewport_height) = terminal_viewport()?;
    let lines = build_screen_lines_for_viewport(
        state,
        rows,
        selected_index,
        updates_per_second,
        live_mode,
        app,
        live_symbols,
        viewport_width,
        viewport_height,
    );

    screen_renderer.render(stdout, &lines, viewport_width, viewport_height)
}

#[cfg(test)]
fn build_screen_lines(
    state: &TerminalState,
    rows: &[CandidateRow],
    selected_index: usize,
    updates_per_second: usize,
    live_mode: bool,
    app: &AppState,
    live_symbols: Option<&LiveSymbolState>,
) -> Vec<RenderLine> {
    build_screen_lines_for_viewport(
        state,
        rows,
        selected_index,
        updates_per_second,
        live_mode,
        app,
        live_symbols,
        DEFAULT_VIEWPORT_WIDTH,
        DEFAULT_VIEWPORT_HEIGHT,
    )
}

fn build_screen_lines_for_viewport(
    state: &TerminalState,
    rows: &[CandidateRow],
    selected_index: usize,
    updates_per_second: usize,
    live_mode: bool,
    app: &AppState,
    live_symbols: Option<&LiveSymbolState>,
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    match &app.overlay_mode {
        OverlayMode::IssueLog => return build_issue_log_lines(app),
        OverlayMode::TickerDetail(symbol) => {
            return match app.detail_tab {
                DetailTab::Snapshot => build_ticker_detail_lines_for_viewport(
                    state,
                    app,
                    symbol,
                    viewport_width,
                    viewport_height,
                ),
                DetailTab::History => build_ticker_history_lines_for_viewport(
                    app,
                    symbol,
                    viewport_width,
                    viewport_height,
                ),
            };
        }
        OverlayMode::None => {}
    }

    let selected_row = rows.get(selected_index);
    let selected_detail = selected_row.and_then(|row| state.detail(&row.symbol));
    let mut lines = Vec::with_capacity(viewport_height);
    let tracked_count = live_symbols.map(|symbols| symbols.count()).unwrap_or(0);
    let source_status = app.live_source_status();
    let health_status = app.issue_center.health_status();
    let active_issue_count = app.issue_center.active_issue_count();

    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: main_screen_header(viewport_width, live_mode),
    });
    lines.push(RenderLine {
        color: Some(if app.paused {
            Color::Yellow
        } else {
            Color::DarkCyan
        }),
        text: main_screen_status_line(
            viewport_width,
            state,
            app,
            source_status,
            tracked_count,
            updates_per_second,
            live_mode,
        ),
    });
    if live_mode {
        lines.push(RenderLine {
            color: Some(
                app.live_feed_status
                    .as_ref()
                    .map(|status| status.color)
                    .unwrap_or(Color::DarkGrey),
            ),
            text: app
                .live_feed_status
                .as_ref()
                .map(|status| format!("Feed status: {}", status.message))
                .unwrap_or_else(|| {
                    "Feed status: waiting for the first live refresh window...".to_string()
                }),
        });
    }
    if let Some(warm_start_summary) = app.warm_start_summary() {
        lines.push(RenderLine {
            color: Some(if app.stale_symbols.is_empty() {
                Color::DarkGreen
            } else {
                Color::DarkYellow
            }),
            text: warm_start_summary,
        });
    }
    lines.push(RenderLine {
        color: Some(health_status_color(health_status)),
        text: format!(
            "Health: {}  Active issues: {}  Resolved: {}  Press l for issue log",
            health_status_label(health_status),
            active_issue_count,
            app.issue_center.resolved_issue_count(),
        ),
    });
    if let Some(toast) = app.issue_center.toast(Instant::now()) {
        lines.push(RenderLine {
            color: Some(issue_severity_color(toast.severity)),
            text: format!(
                "Popup: [{}][{}] {}. {}",
                issue_severity_label(toast.severity),
                issue_source_label(toast.source),
                toast.title,
                truncate_text(&toast.detail, 88),
            ),
        });
    } else if let Some(issue) = app.issue_center.latest_active_issue() {
        lines.push(RenderLine {
            color: Some(issue_severity_color(issue.severity)),
            text: format!(
                "Issue: [{}][{}] {}. {}",
                issue_severity_label(issue.severity),
                issue_source_label(issue.source),
                issue.title,
                truncate_text(&issue.detail, 88),
            ),
        });
    } else {
        lines.push(RenderLine {
            color: Some(Color::DarkGreen),
            text: "Issue rail: no active operational issues.".to_string(),
        });
    }
    if let Some(live_symbols) = live_symbols {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: format!(
                "Tracked symbols: {}",
                live_symbols.read_symbols(format_symbol_list)
            ),
        });
    } else {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "Tracked symbols: replay session".to_string(),
        });
    }
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Filter: query='{}' watchlist_only={} input_mode={} selected={}",
            active_filter_query(&app.view_filter, &app.input_mode),
            if app.view_filter.watchlist_only {
                "on"
            } else {
                "off"
            },
            input_mode_label(&app.input_mode),
            selected_row
                .map(|row| row.symbol.as_str())
                .unwrap_or("none"),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: input_prompt(app, live_mode, viewport_width),
    });
    lines.push(RenderLine {
        color: Some(Color::Cyan),
        text: "TOP CANDIDATES".to_string(),
    });
    lines.push(RenderLine {
        color: None,
        text: format!(
            "  {:>3}  {}  {:<width$} {:>10} {:>10} {:>8}  {}",
            "Idx",
            "W",
            "Ticker / Company",
            "Price",
            "Fair",
            "Upside",
            "Confidence",
            width = CANDIDATE_COMPANY_COLUMN_WIDTH,
        ),
    });

    for (index, row) in rows.iter().enumerate() {
        let marker = if index == selected_index { '>' } else { ' ' };
        let watched_marker = if state.is_watched(&row.symbol) {
            '*'
        } else {
            ' '
        };
        let symbol_label = candidate_company_label(&row.symbol, state.company_name(&row.symbol));
        lines.push(RenderLine {
            color: Some(candidate_row_color(row, index == selected_index)),
            text: format!(
                "{} {:>3}  {}  {:<width$} {:>10} {:>10} {:>8}  {}",
                marker,
                index,
                watched_marker,
                symbol_label,
                format_money(row.market_price_cents),
                format_money(row.intrinsic_value_cents),
                format_upside_percent(row.market_price_cents, row.intrinsic_value_cents),
                confidence_label(row.confidence),
                width = CANDIDATE_COMPANY_COLUMN_WIDTH,
            ),
        });
    }

    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Green),
        text: "DETAIL".to_string(),
    });

    if let Some(selected_detail) = selected_detail {
        lines.push(RenderLine {
            color: Some(status_summary_color(
                selected_detail.qualification,
                selected_detail.confidence,
            )),
            text: format!(
                "Symbol: {}  Watched: {}  Qualification: {}  Confidence: {}",
                format_symbol_with_company(
                    &selected_detail.symbol,
                    state.company_name(&selected_detail.symbol),
                ),
                if selected_detail.is_watched {
                    "yes"
                } else {
                    "no"
                },
                qualification_label(selected_detail.qualification),
                confidence_label(selected_detail.confidence),
            ),
        });
        lines.push(RenderLine {
            color: Some(external_status_color(selected_detail.external_status)),
            text: format!(
                "Price: {}  Fair value: {}  Upside: {}  External: {}",
                format_money(selected_detail.market_price_cents),
                format_money(selected_detail.intrinsic_value_cents),
                format_upside_percent(
                    selected_detail.market_price_cents,
                    selected_detail.intrinsic_value_cents,
                ),
                external_status_label(selected_detail.external_status),
            ),
        });
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: format!(
                "Seq: {}  Updates: {}",
                selected_detail.last_sequence, selected_detail.update_count,
            ),
        });
    } else {
        lines.push(RenderLine {
            color: None,
            text: "No active symbols yet.".to_string(),
        });
    }

    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Blue),
        text: "ALERTS".to_string(),
    });

    for alert in state.alerts_iter().rev().take(MAX_VISIBLE_ALERTS) {
        lines.push(RenderLine {
            color: Some(alert_kind_color(alert.kind)),
            text: format!(
                "{: <6} kind={} seq={}",
                alert.symbol,
                alert_label(alert.kind),
                alert.sequence,
            ),
        });
    }

    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Magenta),
        text: "RECENT TAPE".to_string(),
    });

    for tape_event in state.recent_tape_iter().rev().take(MAX_VISIBLE_TAPE) {
        lines.push(RenderLine {
            color: Some(confidence_color(tape_event.confidence)),
            text: format!(
                "{: <6} upside={} qualified={} confidence={}",
                tape_event.symbol,
                format_upside_percent_from_gap_bps(tape_event.gap_bps),
                if tape_event.is_qualified { "yes" } else { "no" },
                confidence_label(tape_event.confidence),
            ),
        });
    }

    lines
}

fn build_issue_log_lines(app: &AppState) -> Vec<RenderLine> {
    let issues = app.issue_center.sorted_entries();
    let selected_index = app.issue_log_selected.min(issues.len().saturating_sub(1));
    let start_index = selected_index.saturating_sub(MAX_VISIBLE_ISSUES / 2);
    let end_index = (start_index + MAX_VISIBLE_ISSUES).min(issues.len());
    let mut lines = Vec::new();

    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text:
            "ISSUE LOG  |  j/k move  |  c clear resolved  |  Backspace or l close  |  q quit  |  Ctrl+C quit"
                .to_string(),
    });
    lines.push(RenderLine {
        color: Some(health_status_color(app.issue_center.health_status())),
        text: format!(
            "Health: {}  Active: {}  Total: {}  Resolved: {}",
            health_status_label(app.issue_center.health_status()),
            app.issue_center.active_issue_count(),
            app.issue_center.issue_count(),
            app.issue_center.resolved_issue_count(),
        ),
    });
    lines.push(RenderLine {
        color: None,
        text: "Idx  State     Sev      Source       Count  Title".to_string(),
    });

    if issues.is_empty() {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "No operational issues recorded in this session.".to_string(),
        });
        return lines;
    }

    for (offset, issue) in issues[start_index..end_index].iter().enumerate() {
        let issue_index = start_index + offset;
        let marker = if issue_index == selected_index {
            '>'
        } else {
            ' '
        };
        lines.push(RenderLine {
            color: Some(issue_severity_color(issue.severity)),
            text: format!(
                "{} {:>2}  {:<8} {:<8} {:<12} {:>5}  {}",
                marker,
                issue_index,
                if issue.active { "active" } else { "resolved" },
                issue_severity_label(issue.severity),
                issue_source_label(issue.source),
                issue.count,
                truncate_text(&issue.title, 48),
            ),
        });
    }

    let selected_issue = &issues[selected_index];
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(issue_severity_color(selected_issue.severity)),
        text: "DETAIL".to_string(),
    });
    lines.push(RenderLine {
        color: None,
        text: format!(
            "Title: {}  Source: {}  Severity: {}  State: {}",
            selected_issue.title,
            issue_source_label(selected_issue.source),
            issue_severity_label(selected_issue.severity),
            if selected_issue.active {
                "active"
            } else {
                "resolved"
            },
        ),
    });
    lines.push(RenderLine {
        color: None,
        text: format!(
            "Occurrences: {}  First seen: #{}  Last seen: #{}",
            selected_issue.count, selected_issue.first_seen_event, selected_issue.last_seen_event,
        ),
    });
    for detail_line in wrap_text(&selected_issue.detail, 108) {
        lines.push(RenderLine {
            color: None,
            text: format!("Detail: {detail_line}"),
        });
    }

    lines
}

#[cfg(test)]
fn build_ticker_detail_lines(
    state: &TerminalState,
    app: &AppState,
    symbol: &str,
) -> Vec<RenderLine> {
    build_ticker_detail_lines_for_viewport(
        state,
        app,
        symbol,
        DEFAULT_VIEWPORT_WIDTH,
        DEFAULT_VIEWPORT_HEIGHT,
    )
}

#[derive(Clone, Debug)]
struct HistoryMetricRow {
    label: String,
    latest: String,
    previous: String,
    delta: String,
    sparkline: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HistoryUnit {
    Usd,
    Percent,
    Count,
    Ratio,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ChartMetricKind {
    Close,
    Ema20,
    Ema50,
    Ema200,
    Macd,
    Signal,
    Histogram,
}

#[derive(Clone, Debug)]
struct HistoryMetricDef {
    key: &'static str,
    label: String,
    range_key: Option<&'static str>,
    unit: HistoryUnit,
    extractor: HistoryValueExtractor,
}

#[derive(Clone, Copy, Debug)]
enum HistoryValueExtractor {
    MarketPrice,
    IntrinsicValue,
    GapPct,
    ExternalGapPct,
    WeightedGapPct,
    AnalystCount,
    ConfidenceRank,
    QualificationRank,
    ExternalStatusRank,
    WatchedCount,
    ProfitableCount,
    MarketCapUsd,
    SharesOutstanding,
    TrailingPe,
    ForwardPe,
    PriceToBook,
    ReturnOnEquityPct,
    EbitdaUsd,
    EnterpriseValueUsd,
    EnterpriseToEbitda,
    TotalDebtUsd,
    TotalCashUsd,
    DebtToEquity,
    FreeCashFlowUsd,
    OperatingCashFlowUsd,
    Beta,
    TrailingEpsUsd,
    EarningsGrowthPct,
    RelativeCompositePercentile,
    RelativePeerCount,
    RelativeMetricPercentile(&'static str),
    DcfBearIntrinsicUsd,
    DcfBaseIntrinsicUsd,
    DcfBullIntrinsicUsd,
    DcfWaccPct,
    DcfBaseGrowthPct,
    DcfNetDebtUsd,
    DcfMarginSafetyPct,
    DcfSignalRank,
    ChartMetric(ChartRange, ChartMetricKind),
}

#[derive(Clone, Debug)]
struct HistorySeriesPoint {
    evaluated_at: u64,
    revision_id: i64,
    value: f64,
    available: bool,
    stale: bool,
}

#[derive(Clone, Debug)]
struct HistorySeries {
    group: HistoryMetricGroup,
    metric_key: &'static str,
    label: String,
    range_key: Option<&'static str>,
    unit: HistoryUnit,
    points: Vec<HistorySeriesPoint>,
}

#[derive(Clone, Debug)]
struct HistoryGraphTile {
    label: String,
    latest: String,
    previous: String,
    delta: String,
    points: Vec<f64>,
    min_label: String,
    max_label: String,
    footer_lines: Vec<String>,
}

#[derive(Clone, Debug)]
struct CsvExportMetadata {
    symbol: String,
    exported_at: u64,
    revision_count: usize,
    export_dir: PathBuf,
}

fn build_ticker_history_lines_for_viewport(
    app: &AppState,
    symbol: &str,
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    let history = app.detail_history(symbol);
    let mut lines = Vec::new();
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: format!(
            "{}  |  History tab  |  view={}  |  group={}  |  window={}  |  h snapshot  g toggle  e export  1-5 group  [/ ] window  j/k nav  n/p symbol",
            symbol,
            history_subview_label(app.history_view.subview),
            history_group_label(app.history_view.group),
            history_window_label(app.history_view.window),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Canonical revisions loaded: {}  |  viewport {}x{}",
            history.len(),
            viewport_width,
            viewport_height,
        ),
    });

    lines.extend(match app.history_view.subview {
        HistorySubview::Graphs => build_ticker_history_graph_lines(
            history,
            app.history_view.group,
            app.history_view.window,
            app.history_view.scroll,
            viewport_width,
            viewport_height.saturating_sub(lines.len()),
        ),
        HistorySubview::Table => build_ticker_history_table_lines(
            history,
            app.history_view.group,
            app.history_view.window,
            app.history_view.scroll,
            viewport_height.saturating_sub(lines.len()),
        ),
    });
    lines
}

fn build_ticker_history_graph_lines(
    history: &[persistence::PersistedRevisionRecord],
    group: HistoryMetricGroup,
    window: HistoryWindow,
    scroll: usize,
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    let filtered = filter_history_window(history, window);
    let tiles = history_graph_tiles(&filtered, group);
    if tiles.is_empty() {
        return vec![RenderLine {
            color: Some(Color::DarkGrey),
            text: "No graph tiles are available for the selected group and window yet.".to_string(),
        }];
    }

    let columns = if viewport_width >= 120 && viewport_height >= 18 {
        2
    } else {
        1
    };
    let tile_height = 9usize;
    let gap = 3usize;
    let page_rows = (viewport_height.max(tile_height) / tile_height).max(1);
    let total_tile_rows = tiles.len().div_ceil(columns);
    let start_row = scroll.min(total_tile_rows.saturating_sub(page_rows));
    let visible_start = start_row * columns;
    let visible_end = ((start_row + page_rows) * columns).min(tiles.len());
    let tile_width = if columns == 1 {
        viewport_width.max(40)
    } else {
        viewport_width
            .saturating_sub(gap * (columns - 1))
            .checked_div(columns)
            .unwrap_or(viewport_width.max(40))
            .max(40)
    };

    let mut lines = vec![RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Graphs {}-{}/{}  |  layout={}col  |  j/k page tile rows",
            visible_start.saturating_add(1).min(tiles.len()),
            visible_end,
            tiles.len(),
            columns
        ),
    }];

    let mut rendered_tiles = tiles[visible_start..visible_end]
        .iter()
        .map(|tile| render_history_graph_tile(tile, tile_width))
        .collect::<Vec<_>>();
    while rendered_tiles.len() % columns != 0 {
        rendered_tiles.push(blank_tile_lines(tile_width, tile_height));
    }

    for chunk in rendered_tiles.chunks(columns) {
        for line_index in 0..tile_height {
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: chunk
                    .iter()
                    .map(|tile| tile.get(line_index).cloned().unwrap_or_default())
                    .collect::<Vec<_>>()
                    .join("   "),
            });
        }
    }

    lines
}

fn build_ticker_history_table_lines(
    history: &[persistence::PersistedRevisionRecord],
    group: HistoryMetricGroup,
    window: HistoryWindow,
    scroll: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    let rows = history_rows(history, group, window);
    let visible_capacity = viewport_height.saturating_sub(2).max(1);
    let start = scroll.min(rows.len().saturating_sub(visible_capacity));
    let end = (start + visible_capacity).min(rows.len());

    let mut lines = Vec::new();
    if rows.is_empty() {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "No history rows are available for the selected group and window yet."
                .to_string(),
        });
        return lines;
    }

    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Table rows {}-{}/{}  |  Metric                          Latest            Prev              Delta             Trend",
            start.saturating_add(1).min(rows.len()),
            end,
            rows.len(),
        ),
    });
    for row in &rows[start..end] {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: format!(
                "{:<30} {:<17} {:<17} {:<17} {}",
                clip_plain_text(&row.label, 30),
                clip_plain_text(&row.latest, 17),
                clip_plain_text(&row.previous, 17),
                clip_plain_text(&row.delta, 17),
                row.sparkline
            ),
        });
    }

    lines
}

fn build_ticker_detail_lines_for_viewport(
    state: &TerminalState,
    app: &AppState,
    symbol: &str,
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    let detail_rows = filtered_symbol_rows(state, &app.view_filter);
    let symbol_index = detail_rows
        .iter()
        .position(|row| row.symbol == symbol)
        .map(|index| index + 1)
        .unwrap_or(1);
    let symbol_count = detail_rows.len().max(1);
    let layout = detail_layout(viewport_width, viewport_height);
    let mut lines = Vec::with_capacity(viewport_height);

    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "TICKER DETAIL  |  j/k next ticker  |  1-6 range  |  [/] cycle  |  w watch  |  l logs  |  Backspace or d or Enter close  |  q quit  |  Ctrl+C quit".to_string(),
    });

    let Some(detail) = state.detail(symbol) else {
        lines.push(RenderLine {
            color: Some(Color::Red),
            text: format!("{symbol} is not active in the current session."),
        });
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "Close the detail screen with Backspace or select another ticker from the main table."
                .to_string(),
        });
        return lines;
    };

    let discount_cents = detail.intrinsic_value_cents - detail.market_price_cents;
    let actual_upside_bps =
        checked_upside_bps(detail.market_price_cents, detail.intrinsic_value_cents).unwrap_or(0);
    let minimum_upside_bps = upside_bps_from_gap_bps(detail.minimum_gap_bps).unwrap_or(0);
    let weighted_upside_bps =
        detail
            .weighted_external_signal_fair_value_cents
            .and_then(|weighted_target_cents| {
                checked_upside_bps(detail.market_price_cents, weighted_target_cents)
            });
    let chart_snapshot = detail_chart_snapshot(app, symbol);
    let analysis_snapshot = detail_analysis_snapshot(app, &detail);
    let relative_score = detail
        .fundamentals
        .as_ref()
        .and_then(|fundamentals| compute_sector_relative_score(state, fundamentals));
    let aggregated_candles =
        aggregate_historical_candles(chart_snapshot.candles, layout.candle_slots);

    lines.push(RenderLine {
        color: Some(Color::Cyan),
        text: format!(
            "{}  Position: {}/{}  Watched: {}  Chart {}",
            format_symbol_with_company(&detail.symbol, state.company_name(&detail.symbol)),
            symbol_index,
            symbol_count,
            if detail.is_watched { "yes" } else { "no" },
            chart_range_label(app.detail_chart_range()),
        ),
    });
    lines.push(RenderLine {
        color: Some(status_summary_color(
            detail.qualification,
            detail.confidence,
        )),
        text: format!(
            "Price {}  Mean {} ({})  Median {} ({})  Weighted {}",
            format_money(detail.market_price_cents),
            format_money(detail.intrinsic_value_cents),
            format_upside_percent(detail.market_price_cents, detail.intrinsic_value_cents),
            format_optional_money(detail.external_signal_fair_value_cents),
            detail
                .external_signal_fair_value_cents
                .map(|fair_value_cents| format_upside_percent(
                    detail.market_price_cents,
                    fair_value_cents
                ))
                .unwrap_or_else(|| "n/a".to_string()),
            detail
                .weighted_external_signal_fair_value_cents
                .map(|weighted_target_cents| {
                    format!(
                        "{} ({})",
                        format_money(weighted_target_cents),
                        format_upside_percent(detail.market_price_cents, weighted_target_cents)
                    )
                })
                .unwrap_or_else(|| "n/a".to_string()),
        ),
    });
    lines.push(RenderLine {
        color: Some(external_status_color(detail.external_status)),
        text: format!(
            "Qualification {}  Confidence {}  External {}  Threshold {}  Discount {}",
            qualification_label(detail.qualification),
            confidence_label(detail.confidence),
            external_status_label(detail.external_status),
            format_upside_percent_from_gap_bps(detail.minimum_gap_bps),
            format_money(discount_cents),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Updates {}  Sequence #{}  Filter query='{}' watchlist_only={}",
            detail.update_count,
            detail.last_sequence,
            app.view_filter.query,
            if app.view_filter.watchlist_only {
                "on"
            } else {
                "off"
            },
        ),
    });
    if app.is_symbol_stale(symbol) {
        lines.push(RenderLine {
            color: Some(Color::DarkYellow),
            text: "Data source: warm-start cache from SQLite. Live Yahoo refresh has not replaced this symbol yet.".to_string(),
        });
    }
    if let Some(coverage) = app.symbol_coverage(symbol) {
        let summary = format_symbol_coverage_summary(coverage);
        if !summary.is_empty() {
            lines.push(RenderLine {
                color: Some(Color::DarkYellow),
                text: format!("Live coverage: {summary}"),
            });
        }
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: format!(
            "PRICE CHART  |  {}  |  {} candle(s)  |  {}",
            chart_range_label(app.detail_chart_range()),
            chart_snapshot.candles.len(),
            chart_snapshot.status
        ),
    });
    if aggregated_candles.is_empty() {
        lines.push(RenderLine {
            color: Some(chart_snapshot.color),
            text: chart_snapshot.note.unwrap_or_else(|| {
                "Yahoo chart returned no OHLC candles for this range.".to_string()
            }),
        });
    } else {
        lines.extend(build_chart_stack_lines(&aggregated_candles, &layout));
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: format!(
                "Showing {} / {} candles  |  ~{} source candle(s) per slot  |  Visible price range {} to {}  |  Volume max {}",
                aggregated_candles.len(),
                chart_snapshot.candles.len(),
                chart_bucket_size(chart_snapshot.candles.len(), aggregated_candles.len()),
                format_money(
                    aggregated_candles
                        .iter()
                        .map(|candle| candle.low_cents)
                        .min()
                        .unwrap_or(detail.market_price_cents)
                ),
                format_money(
                    aggregated_candles
                        .iter()
                        .map(|candle| candle.high_cents)
                        .max()
                        .unwrap_or(detail.market_price_cents)
                ),
                format_compact_quantity(
                    aggregated_candles
                        .iter()
                        .map(|candle| candle.volume)
                        .max()
                        .unwrap_or(0)
                ),
            ),
        });
        if !layout.show_ema_200 || !layout.show_macd {
            let mut reductions = Vec::new();
            if !layout.show_ema_200 {
                reductions.push("EMA200 hidden");
            }
            if !layout.show_macd {
                reductions.push("MACD hidden");
            }
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: format!("Viewport reduction: {}", reductions.join("  |  ")),
            });
        }
        if let Some(note) = chart_snapshot.note {
            lines.push(RenderLine {
                color: Some(chart_snapshot.color),
                text: note,
            });
        }
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "VALUATION MAP".to_string(),
    });
    lines.push(RenderLine {
        color: Some(gap_color(actual_upside_bps, minimum_upside_bps)),
        text: format!(
            "Mean upside {} vs threshold {}  {}",
            format_upside_percent(detail.market_price_cents, detail.intrinsic_value_cents),
            format_upside_percent_from_gap_bps(detail.minimum_gap_bps),
            gap_meter(actual_upside_bps, minimum_upside_bps, GAP_METER_WIDTH),
        ),
    });
    if let Some(weighted_upside_bps) = weighted_upside_bps {
        lines.push(RenderLine {
            color: Some(gap_color(weighted_upside_bps, minimum_upside_bps)),
            text: format!(
                "Weighted upside {} from {} scored firms",
                format_bps(weighted_upside_bps),
                format_optional_count(detail.weighted_analyst_count),
            ),
        });
    }
    if let Some(target_range_line) = target_range_line(&detail) {
        lines.push(RenderLine {
            color: Some(Color::DarkCyan),
            text: target_range_line,
        });
    }
    if let Some(target_marker_legend_line) = target_marker_legend_line(&detail) {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: target_marker_legend_line,
        });
    }
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Range width {}  Analysts {}  Recommendation mean {}",
            detail
                .external_signal_high_fair_value_cents
                .zip(detail.external_signal_low_fair_value_cents)
                .map(|(high, low)| format_money(high.saturating_sub(low)))
                .unwrap_or_else(|| "n/a".to_string()),
            format_optional_count(detail.analyst_opinion_count),
            detail
                .recommendation_mean_hundredths
                .map(format_recommendation_mean)
                .unwrap_or_else(|| "n/a".to_string()),
        ),
    });
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "FUNDAMENTALS".to_string(),
    });
    for line in build_fundamentals_lines(
        &detail,
        &analysis_snapshot,
        relative_score.as_ref(),
        layout.compact_fundamentals,
    ) {
        lines.push(line);
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "CONSENSUS".to_string(),
    });
    if layout.compact_consensus {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: compact_consensus_line(&detail),
        });
    } else {
        lines.extend(build_consensus_graph_lines(&detail));
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "EVIDENCE".to_string(),
    });
    let evidence_lines = if layout.compact_evidence {
        compact_evidence_lines(&detail)
            .into_iter()
            .take(2)
            .collect::<Vec<_>>()
    } else {
        compact_evidence_lines(&detail)
    };
    for evidence_line in evidence_lines {
        lines.push(RenderLine {
            color: Some(evidence_line_color(&detail, &evidence_line)),
            text: evidence_line,
        });
    }
    if layout.show_recent_context {
        lines.push(RenderLine {
            color: None,
            text: String::new(),
        });
        lines.push(RenderLine {
            color: Some(Color::Yellow),
            text: "RECENT CONTEXT".to_string(),
        });
        lines.push(RenderLine {
            color: Some(Color::Magenta),
            text: summarize_recent_alerts(state.alerts_for_symbol(symbol)),
        });
        lines.push(RenderLine {
            color: Some(Color::Cyan),
            text: summarize_recent_tape(state.recent_tape_for_symbol(symbol)),
        });
    }

    lines
}

struct DetailLayout {
    candle_slots: usize,
    price_chart_height: usize,
    volume_chart_height: usize,
    macd_chart_height: usize,
    compact_volume: bool,
    show_macd: bool,
    show_ema_200: bool,
    show_overlay_legend: bool,
    show_macd_legend: bool,
    show_recent_context: bool,
    compact_fundamentals: bool,
    compact_consensus: bool,
    compact_evidence: bool,
}

struct DetailChartSnapshot<'a> {
    candles: &'a [HistoricalCandle],
    status: String,
    note: Option<String>,
    color: Color,
}

fn detail_layout(viewport_width: usize, viewport_height: usize) -> DetailLayout {
    let candle_slots =
        ((viewport_width.saturating_sub(DETAIL_CHART_AXIS_WIDTH + DETAIL_CHART_ROW_PADDING)) / 2)
            .max(DETAIL_MIN_VISIBLE_CANDLES);
    let mut show_recent_context = viewport_height >= 30 && viewport_width >= 88;
    let mut compact_fundamentals = viewport_height < 36 || viewport_width < 104;
    let mut compact_consensus = viewport_height < 34 || viewport_width < 96;
    let mut compact_evidence = viewport_height < 28 || viewport_width < 90;
    let preferred_chart_stack_height = (viewport_height.saturating_mul(11) / 20)
        .max(DETAIL_MIN_CHART_HEIGHT + DETAIL_MIN_VOLUME_HEIGHT + DETAIL_MIN_MACD_HEIGHT);

    let mut non_chart_lines = 22usize
        + if show_recent_context { 4 } else { 0 }
        + if compact_fundamentals {
            2
        } else if viewport_height < 72 {
            9
        } else {
            5
        }
        + if compact_consensus { 1 } else { 6 }
        + if compact_evidence { 2 } else { 3 };

    if non_chart_lines + preferred_chart_stack_height > viewport_height && show_recent_context {
        show_recent_context = false;
        non_chart_lines = non_chart_lines.saturating_sub(4);
    }
    if non_chart_lines + preferred_chart_stack_height > viewport_height && !compact_fundamentals {
        compact_fundamentals = true;
        non_chart_lines = non_chart_lines.saturating_sub(3);
    }
    if non_chart_lines + preferred_chart_stack_height > viewport_height && !compact_evidence {
        compact_evidence = true;
        non_chart_lines = non_chart_lines.saturating_sub(1);
    }
    if non_chart_lines + preferred_chart_stack_height > viewport_height && !compact_consensus {
        compact_consensus = true;
        non_chart_lines = non_chart_lines.saturating_sub(5);
    }

    let chart_stack_height = viewport_height.saturating_sub(non_chart_lines).max(8);
    let (price_chart_height, volume_chart_height, macd_chart_height, compact_volume) =
        split_chart_stack_height(chart_stack_height);
    let show_macd = if compact_volume {
        viewport_height >= 18 && viewport_width >= 72 && macd_chart_height >= 3
    } else {
        macd_chart_height >= DETAIL_MIN_MACD_HEIGHT
    };
    let show_ema_200 = chart_stack_height >= 12 && viewport_width >= 76;
    let show_overlay_legend = viewport_width >= 88 && chart_stack_height >= 12;
    let show_macd_legend = show_macd && viewport_width >= 94 && chart_stack_height >= 13;

    DetailLayout {
        candle_slots,
        price_chart_height,
        volume_chart_height,
        macd_chart_height,
        compact_volume,
        show_macd,
        show_ema_200,
        show_overlay_legend,
        show_macd_legend,
        show_recent_context,
        compact_fundamentals,
        compact_consensus,
        compact_evidence,
    }
}

fn split_chart_stack_height(total_height: usize) -> (usize, usize, usize, bool) {
    let minimum_full_stack_height =
        DETAIL_MIN_CHART_HEIGHT + DETAIL_MIN_VOLUME_HEIGHT + DETAIL_MIN_MACD_HEIGHT;
    if total_height >= minimum_full_stack_height {
        let mut price_height =
            ((total_height.saturating_mul(60) + 99) / 100).max(DETAIL_MIN_CHART_HEIGHT);
        let mut volume_height =
            ((total_height.saturating_mul(15) + 99) / 100).max(DETAIL_MIN_VOLUME_HEIGHT);
        let mut macd_height = total_height.saturating_sub(price_height + volume_height);

        if macd_height < DETAIL_MIN_MACD_HEIGHT {
            let deficit = DETAIL_MIN_MACD_HEIGHT - macd_height;
            let price_cut = price_height
                .saturating_sub(DETAIL_MIN_CHART_HEIGHT)
                .min(deficit);
            price_height = price_height.saturating_sub(price_cut);
            macd_height += price_cut;

            let remaining_deficit = DETAIL_MIN_MACD_HEIGHT.saturating_sub(macd_height);
            let volume_cut = volume_height
                .saturating_sub(DETAIL_MIN_VOLUME_HEIGHT)
                .min(remaining_deficit);
            volume_height = volume_height.saturating_sub(volume_cut);
            macd_height += volume_cut;
        }

        return (price_height, volume_height, macd_height, false);
    }

    let compact_volume_height = 1usize;
    let available_for_price_and_macd = total_height.saturating_sub(compact_volume_height).max(7);
    let macd_height = (available_for_price_and_macd / 3)
        .max(3)
        .min(available_for_price_and_macd.saturating_sub(4));
    let price_height = available_for_price_and_macd
        .saturating_sub(macd_height)
        .max(4);

    (price_height, compact_volume_height, macd_height, true)
}

fn detail_chart_snapshot<'a>(app: &'a AppState, symbol: &str) -> DetailChartSnapshot<'a> {
    match app.detail_chart_entry(symbol) {
        Some(ChartCacheEntry::Loading {
            previous: Some(candles),
            ..
        }) => DetailChartSnapshot {
            candles,
            status: "refreshing".to_string(),
            note: Some(
                "Refreshing Yahoo OHLC history. Showing cached candles meanwhile.".to_string(),
            ),
            color: Color::DarkYellow,
        },
        Some(ChartCacheEntry::Loading { previous: None, .. }) => DetailChartSnapshot {
            candles: &[],
            status: "loading".to_string(),
            note: Some("Loading Yahoo OHLC history for the selected range...".to_string()),
            color: Color::DarkGrey,
        },
        Some(ChartCacheEntry::Ready { candles }) => DetailChartSnapshot {
            candles,
            status: if app.is_chart_stale(symbol, app.detail_chart_range()) {
                format!("cached ({})", candles.len())
            } else {
                format!("ready ({})", candles.len())
            },
            note: app
                .is_chart_stale(symbol, app.detail_chart_range())
                .then_some(
                    "Showing persisted candles from SQLite while Yahoo refreshes this range."
                        .to_string(),
                ),
            color: if app.is_chart_stale(symbol, app.detail_chart_range()) {
                Color::DarkYellow
            } else {
                Color::DarkGrey
            },
        },
        Some(ChartCacheEntry::Failed {
            message,
            previous: Some(candles),
        }) => DetailChartSnapshot {
            candles,
            status: "stale".to_string(),
            note: Some(format!(
                "Yahoo history refresh failed: {message}. Showing cached candles."
            )),
            color: Color::DarkYellow,
        },
        Some(ChartCacheEntry::Failed {
            message,
            previous: None,
        }) => DetailChartSnapshot {
            candles: &[],
            status: "error".to_string(),
            note: Some(format!("Yahoo history unavailable: {message}")),
            color: Color::Red,
        },
        None => DetailChartSnapshot {
            candles: &[],
            status: "idle".to_string(),
            note: Some("Historical price chart has not been loaded yet.".to_string()),
            color: Color::DarkGrey,
        },
    }
}

fn detail_analysis_snapshot<'a>(
    app: &'a AppState,
    detail: &'a SymbolDetail,
) -> DetailAnalysisSnapshot<'a> {
    let Some(fundamentals) = detail.fundamentals.as_ref() else {
        return DetailAnalysisSnapshot {
            analysis: None,
            status: "unavailable".to_string(),
            note: Some(
                "Yahoo quote fundamentals are not available for this ticker yet.".to_string(),
            ),
            color: Color::DarkGrey,
        };
    };
    let analysis_input = analysis_input_key(fundamentals);

    match app.detail_analysis_entry(&detail.symbol) {
        Some(AnalysisCacheEntry::Loading { input, .. }) if *input == analysis_input => {
            DetailAnalysisSnapshot {
                analysis: None,
                status: "loading".to_string(),
                note: Some("Loading annual Yahoo cash flow history for DCF...".to_string()),
                color: Color::DarkGrey,
            }
        }
        Some(AnalysisCacheEntry::Ready { input, analysis }) if *input == analysis_input => {
            DetailAnalysisSnapshot {
                analysis: Some(analysis),
                status: "ready".to_string(),
                note: None,
                color: Color::DarkGrey,
            }
        }
        Some(AnalysisCacheEntry::Failed { input, message }) if *input == analysis_input => {
            DetailAnalysisSnapshot {
                analysis: None,
                status: "error".to_string(),
                note: Some(message.clone()),
                color: Color::DarkYellow,
            }
        }
        Some(_) | None => DetailAnalysisSnapshot {
            analysis: None,
            status: "idle".to_string(),
            note: Some("DCF analysis has not been requested yet.".to_string()),
            color: Color::DarkGrey,
        },
    }
}

fn build_fundamentals_lines(
    detail: &SymbolDetail,
    analysis_snapshot: &DetailAnalysisSnapshot<'_>,
    relative_score: Option<&SectorRelativeScore>,
    compact: bool,
) -> Vec<RenderLine> {
    let Some(fundamentals) = detail.fundamentals.as_ref() else {
        return vec![RenderLine {
            color: Some(Color::DarkGrey),
            text: "Yahoo quote fundamentals are not available for this ticker.".to_string(),
        }];
    };

    let mut lines = Vec::new();
    let debt_ebitda = fundamental_debt_or_net_debt_to_ebitda(fundamentals);
    let compact_line = format!(
        "DCF {}  P/E {}  PEG {}  ROE {}  {} {}  FCF yield {}",
        analysis_snapshot
            .analysis
            .map(|analysis| format!(
                "{} ({})",
                dcf_signal_label(dcf_signal(analysis, detail.market_price_cents)),
                dcf_margin_of_safety_bps(analysis, detail.market_price_cents)
                    .map(format_bps)
                    .unwrap_or_else(|| "n/a".to_string())
            ))
            .unwrap_or_else(|| analysis_snapshot.status.clone()),
        format_optional_decimal(fundamental_trailing_pe(fundamentals), 2),
        format_optional_decimal(fundamental_peg(fundamentals), 2),
        format_optional_percent(fundamental_roe(fundamentals)),
        debt_ebitda.map(|(label, _)| label).unwrap_or("Debt/EBITDA"),
        format_optional_decimal(debt_ebitda.map(|(_, value)| value), 2),
        format_optional_percent_ratio(fundamental_fcf_yield(fundamentals)),
    );

    lines.push(RenderLine {
        color: Some(match analysis_snapshot.analysis {
            Some(analysis) => dcf_signal_color(dcf_signal(analysis, detail.market_price_cents)),
            None => analysis_snapshot.color,
        }),
        text: compact_line,
    });

    if compact {
        if let Some(note) = analysis_snapshot.note.as_ref() {
            lines.push(RenderLine {
                color: Some(analysis_snapshot.color),
                text: format!("DCF note: {note}"),
            });
        }
        return lines;
    }

    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Yahoo analyst fair value {} remains external. Proprietary DCF value {} drives the DCF signal.",
            format_money(detail.intrinsic_value_cents),
            analysis_snapshot
                .analysis
                .map(|analysis| format_money(analysis.base_intrinsic_value_cents))
                .unwrap_or_else(|| "n/a".to_string()),
        ),
    });

    if let Some(analysis) = analysis_snapshot.analysis {
        lines.push(RenderLine {
            color: Some(dcf_signal_color(dcf_signal(
                analysis,
                detail.market_price_cents,
            ))),
            text: format!(
                "DCF bear {}  base {}  bull {}  signal {}  margin {}",
                format_money(analysis.bear_intrinsic_value_cents),
                format_money(analysis.base_intrinsic_value_cents),
                format_money(analysis.bull_intrinsic_value_cents),
                dcf_signal_label(dcf_signal(analysis, detail.market_price_cents)),
                dcf_margin_of_safety_bps(analysis, detail.market_price_cents)
                    .map(format_bps)
                    .unwrap_or_else(|| "n/a".to_string()),
            ),
        });
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: format!(
                "WACC {}  FCF/share CAGR {}  Net debt {}",
                format_bps(analysis.wacc_bps),
                format_bps(analysis.base_growth_bps),
                format_compact_dollars(analysis.net_debt_dollars),
            ),
        });
    } else if let Some(note) = analysis_snapshot.note.as_ref() {
        lines.push(RenderLine {
            color: Some(analysis_snapshot.color),
            text: format!("DCF {}: {}", analysis_snapshot.status, note),
        });
    }

    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "P/E {}  Forward P/E {}  PEG {}  P/B {}",
            format_optional_decimal(fundamental_trailing_pe(fundamentals), 2),
            format_optional_decimal(fundamental_forward_pe(fundamentals), 2),
            format_optional_decimal(fundamental_peg(fundamentals), 2),
            format_optional_decimal(fundamental_price_to_book(fundamentals), 2),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "ROE {}  Debt/Equity {}  EV/EBITDA {}  Beta {}",
            format_optional_percent(fundamental_roe(fundamentals)),
            format_optional_decimal(fundamental_debt_to_equity(fundamentals), 2),
            format_optional_decimal(fundamental_ev_to_ebitda(fundamentals), 2),
            format_optional_decimal(fundamental_beta(fundamentals), 2),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "{} {}  FCF yield {}  OCF {}  FCF {}",
            debt_ebitda.map(|(label, _)| label).unwrap_or("Debt/EBITDA"),
            format_optional_decimal(debt_ebitda.map(|(_, value)| value), 2),
            format_optional_percent_ratio(fundamental_fcf_yield(fundamentals)),
            fundamentals
                .operating_cash_flow_dollars
                .map(format_compact_dollars)
                .unwrap_or_else(|| "n/a".to_string()),
            fundamentals
                .free_cash_flow_dollars
                .map(format_compact_dollars)
                .unwrap_or_else(|| "n/a".to_string()),
        ),
    });

    match relative_score {
        Some(relative_score) => {
            lines.push(RenderLine {
                color: Some(relative_strength_color(relative_score.composite_band)),
                text: format!(
            "Relative vs {} {} peers={} percentile={} ({})",
                    relative_score.group_kind,
                    relative_score.group_label,
                    relative_score.peer_count,
                    relative_score.composite_percentile,
                    relative_strength_label(relative_score.composite_band),
                ),
            });
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: relative_score
                    .metrics
                    .iter()
                    .map(|metric| {
                        format!(
                            "{} pctl {} ({})",
                            metric.label,
                            metric.percentile,
                            relative_strength_label(metric.band)
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("  |  "),
            });
        }
        None => lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "Relative percentile scoring unavailable: need at least 5 tracked peers sharing industry or sector with enough ratio coverage.".to_string(),
        }),
    }

    lines
}

fn aggregate_historical_candles(
    candles: &[HistoricalCandle],
    max_candles: usize,
) -> Vec<PriceCandle> {
    if candles.is_empty() || max_candles == 0 {
        return Vec::new();
    }

    let close_series = candles
        .iter()
        .map(|candle| candle.close_cents)
        .collect::<Vec<_>>();
    let ema_20_series = compute_ema_series(&close_series, 20);
    let ema_50_series = compute_ema_series(&close_series, 50);
    let ema_200_series = compute_ema_series(&close_series, 200);
    let (macd_series, signal_series, histogram_series) = compute_macd_series(&close_series);
    let bucket_size = chart_bucket_size(candles.len(), max_candles);
    candles
        .chunks(bucket_size)
        .enumerate()
        .map(|(bucket_index, bucket)| PriceCandle {
            open_cents: bucket.first().map(|candle| candle.open_cents).unwrap_or(0),
            high_cents: bucket
                .iter()
                .map(|candle| candle.high_cents)
                .max()
                .unwrap_or(0),
            low_cents: bucket
                .iter()
                .map(|candle| candle.low_cents)
                .min()
                .unwrap_or(0),
            close_cents: bucket.last().map(|candle| candle.close_cents).unwrap_or(0),
            volume: bucket.iter().map(|candle| candle.volume).sum(),
            ema_20_cents: last_bucket_value(
                &ema_20_series,
                bucket_index,
                bucket.len(),
                bucket_size,
            ),
            ema_50_cents: last_bucket_value(
                &ema_50_series,
                bucket_index,
                bucket.len(),
                bucket_size,
            ),
            ema_200_cents: last_bucket_value(
                &ema_200_series,
                bucket_index,
                bucket.len(),
                bucket_size,
            ),
            macd_cents: last_bucket_value(&macd_series, bucket_index, bucket.len(), bucket_size),
            signal_cents: last_bucket_value(
                &signal_series,
                bucket_index,
                bucket.len(),
                bucket_size,
            ),
            histogram_cents: last_bucket_value(
                &histogram_series,
                bucket_index,
                bucket.len(),
                bucket_size,
            ),
            point_count: bucket.len(),
        })
        .collect()
}

fn last_bucket_value(
    values: &[Option<f64>],
    bucket_index: usize,
    bucket_len: usize,
    bucket_size: usize,
) -> Option<f64> {
    let end_index = bucket_index
        .saturating_mul(bucket_size)
        .saturating_add(bucket_len.saturating_sub(1));
    values.get(end_index).copied().flatten()
}

fn compute_ema_series(closes_cents: &[i64], period: usize) -> Vec<Option<f64>> {
    if closes_cents.is_empty() || period == 0 {
        return Vec::new();
    }

    let alpha = 2.0 / (period as f64 + 1.0);
    let mut ema = closes_cents[0] as f64;
    let mut series = Vec::with_capacity(closes_cents.len());

    for close_cents in closes_cents {
        ema = *close_cents as f64 * alpha + ema * (1.0 - alpha);
        series.push(Some(ema));
    }

    series
}

fn compute_macd_series(
    closes_cents: &[i64],
) -> (Vec<Option<f64>>, Vec<Option<f64>>, Vec<Option<f64>>) {
    if closes_cents.is_empty() {
        return (Vec::new(), Vec::new(), Vec::new());
    }

    let ema_12 = compute_ema_series(closes_cents, 12);
    let ema_26 = compute_ema_series(closes_cents, 26);
    let macd_series = ema_12
        .iter()
        .zip(ema_26.iter())
        .map(|(fast, slow)| fast.zip(*slow).map(|(fast, slow)| fast - slow))
        .collect::<Vec<_>>();
    let signal_seed = macd_series
        .iter()
        .map(|value| value.unwrap_or(0.0).round() as i64)
        .collect::<Vec<_>>();
    let signal_series = compute_ema_series(&signal_seed, 9);
    let histogram_series = macd_series
        .iter()
        .zip(signal_series.iter())
        .map(|(macd, signal)| macd.zip(*signal).map(|(macd, signal)| macd - signal))
        .collect::<Vec<_>>();

    (macd_series, signal_series, histogram_series)
}

fn summarize_chart_range(
    range: ChartRange,
    captured_at: u64,
    candles: &[HistoricalCandle],
) -> ChartRangeSummary {
    let closes = candles
        .iter()
        .map(|candle| candle.close_cents)
        .collect::<Vec<_>>();
    let ema20 = compute_ema_series(&closes, 20);
    let ema50 = compute_ema_series(&closes, 50);
    let ema200 = compute_ema_series(&closes, 200);
    let (macd, signal, histogram) = compute_macd_series(&closes);
    ChartRangeSummary {
        range,
        captured_at,
        candle_count: candles.len(),
        latest_close_cents: closes.last().copied(),
        ema20_cents: ema20
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
        ema50_cents: ema50
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
        ema200_cents: ema200
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
        macd_cents: macd
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
        signal_cents: signal
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
        histogram_cents: histogram
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
    }
}

fn chart_bucket_size(point_count: usize, max_candles: usize) -> usize {
    if point_count == 0 || max_candles == 0 {
        return 1;
    }

    point_count.div_ceil(max_candles.max(1))
}

fn build_chart_stack_lines(candles: &[PriceCandle], layout: &DetailLayout) -> Vec<RenderLine> {
    let chart_width = candles.len().saturating_mul(2);
    let separator_width = DETAIL_CHART_AXIS_WIDTH + 2 + chart_width;
    let mut lines = vec![pane_header_line(
        "PRICE",
        "candles + EMA",
        Some(Color::Yellow),
        Some(Color::DarkGrey),
    )];
    lines.extend(render_price_chart_lines(candles, layout));
    if layout.show_overlay_legend {
        lines.push(price_legend_line(layout.show_ema_200));
    }

    lines.push(pane_separator_line("VOLUME", separator_width));
    if layout.compact_volume {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: render_compact_volume_line(candles),
        });
    } else {
        lines.extend(render_volume_chart_lines(
            candles,
            layout.volume_chart_height,
        ));
    }

    if layout.show_macd {
        lines.push(pane_separator_line("MACD", separator_width));
        lines.extend(render_macd_chart_lines(candles, layout.macd_chart_height));
        if layout.show_macd_legend {
            lines.push(macd_legend_line());
        }
    }

    lines
}

fn render_price_chart_lines(candles: &[PriceCandle], layout: &DetailLayout) -> Vec<RenderLine> {
    if candles.is_empty() || layout.price_chart_height == 0 {
        return Vec::new();
    }

    let mut min_price_cents = candles
        .iter()
        .map(|candle| candle.low_cents)
        .min()
        .unwrap_or(0);
    let mut max_price_cents = candles
        .iter()
        .map(|candle| candle.high_cents)
        .max()
        .unwrap_or(0);
    for ema_value in candles.iter().flat_map(|candle| {
        [
            candle.ema_20_cents,
            candle.ema_50_cents,
            if layout.show_ema_200 {
                candle.ema_200_cents
            } else {
                None
            },
        ]
    }) {
        if let Some(ema_value) = ema_value {
            min_price_cents = min_price_cents.min(ema_value.round() as i64);
            max_price_cents = max_price_cents.max(ema_value.round() as i64);
        }
    }
    let (min_price_cents, max_price_cents) = padded_i64_range(min_price_cents, max_price_cents);
    let chart_width = candles.len() * 2;
    let mut canvas = blank_canvas(layout.price_chart_height, chart_width);

    for (index, candle) in candles.iter().enumerate() {
        let wick_column = index * 2;
        let body_column = wick_column + 1;
        let high_row = map_numeric_to_row(
            candle.high_cents as f64,
            min_price_cents as f64,
            max_price_cents as f64,
            layout.price_chart_height,
        );
        let low_row = map_numeric_to_row(
            candle.low_cents as f64,
            min_price_cents as f64,
            max_price_cents as f64,
            layout.price_chart_height,
        );
        let open_row = map_numeric_to_row(
            candle.open_cents as f64,
            min_price_cents as f64,
            max_price_cents as f64,
            layout.price_chart_height,
        );
        let close_row = map_numeric_to_row(
            candle.close_cents as f64,
            min_price_cents as f64,
            max_price_cents as f64,
            layout.price_chart_height,
        );

        let candle_color = if candle.close_cents > candle.open_cents {
            Some(Color::Green)
        } else if candle.close_cents < candle.open_cents {
            Some(Color::Red)
        } else {
            Some(Color::Grey)
        };
        let body_char = if candle.close_cents > candle.open_cents {
            '█'
        } else if candle.close_cents < candle.open_cents {
            '▓'
        } else {
            '─'
        };

        for row in high_row.min(low_row)..=high_row.max(low_row) {
            set_canvas_cell(&mut canvas, row, wick_column, '│', candle_color, 4);
        }
        for row in open_row.min(close_row)..=open_row.max(close_row) {
            set_canvas_cell(&mut canvas, row, wick_column, body_char, candle_color, 5);
            set_canvas_cell(&mut canvas, row, body_column, body_char, candle_color, 5);
        }

        draw_overlay_point(
            &mut canvas,
            wick_column,
            body_column,
            candle.ema_20_cents,
            min_price_cents as f64,
            max_price_cents as f64,
            layout.price_chart_height,
            '.',
            Some(Color::Yellow),
            2,
        );
        draw_overlay_point(
            &mut canvas,
            wick_column,
            body_column,
            candle.ema_50_cents,
            min_price_cents as f64,
            max_price_cents as f64,
            layout.price_chart_height,
            'x',
            Some(Color::Cyan),
            2,
        );
        if layout.show_ema_200 {
            draw_overlay_point(
                &mut canvas,
                wick_column,
                body_column,
                candle.ema_200_cents,
                min_price_cents as f64,
                max_price_cents as f64,
                layout.price_chart_height,
                'o',
                Some(Color::DarkGrey),
                1,
            );
        }
    }

    render_i64_axis_pane(canvas, min_price_cents, max_price_cents)
}

fn render_volume_chart_lines(candles: &[PriceCandle], chart_height: usize) -> Vec<RenderLine> {
    if candles.is_empty() || chart_height == 0 {
        return Vec::new();
    }

    let chart_width = candles.len() * 2;
    let max_volume = candles
        .iter()
        .map(|candle| candle.volume)
        .max()
        .unwrap_or(0)
        .max(1);
    let mut canvas = blank_canvas(chart_height, chart_width);

    for (index, candle) in candles.iter().enumerate() {
        if candle.volume == 0 {
            continue;
        }

        let body_column = index * 2 + 1;
        let filled_rows = ((candle.volume as usize * chart_height) + max_volume as usize - 1)
            / max_volume as usize;
        let filled_rows = filled_rows.max(1).min(chart_height);
        for row in chart_height.saturating_sub(filled_rows)..chart_height {
            set_canvas_cell(
                &mut canvas,
                row,
                body_column.saturating_sub(1),
                '█',
                Some(Color::DarkBlue),
                3,
            );
            set_canvas_cell(&mut canvas, row, body_column, '█', Some(Color::DarkBlue), 3);
        }
    }

    render_u64_axis_pane(canvas, max_volume, 0)
}

fn render_compact_volume_line(candles: &[PriceCandle]) -> String {
    let total_volume = candles.iter().map(|candle| candle.volume).sum::<u64>();
    let max_volume = candles
        .iter()
        .map(|candle| candle.volume)
        .max()
        .unwrap_or(0);
    format!(
        "Volume compressed: total {}  max {}  viewport too short for a separate pane",
        format_compact_quantity(total_volume),
        format_compact_quantity(max_volume),
    )
}

fn render_macd_chart_lines(candles: &[PriceCandle], chart_height: usize) -> Vec<RenderLine> {
    if candles.is_empty() || chart_height == 0 {
        return Vec::new();
    }

    let mut min_value = 0.0f64;
    let mut max_value = 0.0f64;
    for indicator_value in candles.iter().flat_map(|candle| {
        [
            candle.macd_cents,
            candle.signal_cents,
            candle.histogram_cents,
        ]
    }) {
        if let Some(indicator_value) = indicator_value {
            min_value = min_value.min(indicator_value);
            max_value = max_value.max(indicator_value);
        }
    }
    let (min_value, max_value) = padded_f64_range(min_value, max_value);
    let chart_width = candles.len() * 2;
    let zero_row = map_numeric_to_row(0.0, min_value, max_value, chart_height);
    let mut canvas = blank_canvas(chart_height, chart_width);

    for column in 0..chart_width {
        set_canvas_cell(&mut canvas, zero_row, column, '-', Some(Color::DarkGrey), 0);
    }

    for (index, candle) in candles.iter().enumerate() {
        let wick_column = index * 2;
        let body_column = wick_column + 1;

        if let Some(histogram_value) = candle.histogram_cents {
            let histogram_row =
                map_numeric_to_row(histogram_value, min_value, max_value, chart_height);
            let histogram_color = if histogram_value >= 0.0 {
                Some(Color::DarkGreen)
            } else {
                Some(Color::DarkRed)
            };
            let histogram_char = if histogram_value >= 0.0 { '█' } else { '▓' };
            for row in zero_row.min(histogram_row)..=zero_row.max(histogram_row) {
                set_canvas_cell(
                    &mut canvas,
                    row,
                    wick_column,
                    histogram_char,
                    histogram_color,
                    1,
                );
                set_canvas_cell(
                    &mut canvas,
                    row,
                    body_column,
                    histogram_char,
                    histogram_color,
                    1,
                );
            }
        }

        draw_overlay_point(
            &mut canvas,
            wick_column,
            body_column,
            candle.macd_cents,
            min_value,
            max_value,
            chart_height,
            '+',
            Some(Color::Cyan),
            3,
        );
        draw_overlay_point(
            &mut canvas,
            wick_column,
            body_column,
            candle.signal_cents,
            min_value,
            max_value,
            chart_height,
            '=',
            Some(Color::Yellow),
            2,
        );
    }

    render_f64_axis_pane(canvas, min_value, max_value)
}

fn compact_consensus_line(detail: &SymbolDetail) -> String {
    format!(
        "Analysts {}  Recommendation mean {}  Ratings: SB {}  B {}  H {}  S {}  SS {}",
        format_optional_count(detail.analyst_opinion_count),
        detail
            .recommendation_mean_hundredths
            .map(format_recommendation_mean)
            .unwrap_or_else(|| "n/a".to_string()),
        detail.strong_buy_count.unwrap_or(0),
        detail.buy_count.unwrap_or(0),
        detail.hold_count.unwrap_or(0),
        detail.sell_count.unwrap_or(0),
        detail.strong_sell_count.unwrap_or(0),
    )
}

fn pane_header_line(
    title: &str,
    subtitle: &str,
    title_color: Option<Color>,
    subtitle_color: Option<Color>,
) -> RenderLine {
    styled_segments_line(vec![
        StyledSegment {
            color: title_color,
            text: format!("{title:<6}"),
        },
        StyledSegment {
            color: subtitle_color,
            text: format!(" {subtitle}"),
        },
    ])
}

fn pane_separator_line(label: &str, width: usize) -> RenderLine {
    let prefix = format!("-- {label} ");
    let rule_width = width.saturating_sub(prefix.chars().count()).max(4);
    styled_segments_line(vec![
        StyledSegment {
            color: Some(Color::DarkGrey),
            text: prefix,
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            text: "-".repeat(rule_width),
        },
    ])
}

fn price_legend_line(show_ema_200: bool) -> RenderLine {
    let mut segments = vec![
        StyledSegment {
            color: Some(Color::Green),
            text: "█ up".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::Red),
            text: "▓ down".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::Grey),
            text: "─ flat".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::Yellow),
            text: ". EMA20".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::Cyan),
            text: "x EMA50".to_string(),
        },
    ];
    if show_ema_200 {
        segments.push(StyledSegment {
            color: Some(Color::DarkGrey),
            text: "  ".to_string(),
        });
        segments.push(StyledSegment {
            color: Some(Color::DarkGrey),
            text: "o EMA200".to_string(),
        });
    }
    styled_segments_line(segments)
}

fn macd_legend_line() -> RenderLine {
    styled_segments_line(vec![
        StyledSegment {
            color: Some(Color::Cyan),
            text: "+ MACD".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::Yellow),
            text: "= signal".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGreen),
            text: "█ hist+".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkRed),
            text: "▓ hist-".to_string(),
        },
    ])
}

fn blank_canvas(height: usize, width: usize) -> Vec<Vec<StyledCell>> {
    vec![
        vec![
            StyledCell {
                ch: ' ',
                color: None,
                priority: 0,
            };
            width
        ];
        height
    ]
}

fn draw_overlay_point(
    canvas: &mut [Vec<StyledCell>],
    wick_column: usize,
    body_column: usize,
    value: Option<f64>,
    min_value: f64,
    max_value: f64,
    chart_height: usize,
    overlay_char: char,
    overlay_color: Option<Color>,
    priority: u8,
) {
    let Some(value) = value else {
        return;
    };
    let row = map_numeric_to_row(value, min_value, max_value, chart_height);
    set_canvas_cell(
        canvas,
        row,
        wick_column,
        overlay_char,
        overlay_color,
        priority,
    );
    set_canvas_cell(
        canvas,
        row,
        body_column,
        overlay_char,
        overlay_color,
        priority,
    );
}

fn set_canvas_cell(
    canvas: &mut [Vec<StyledCell>],
    row: usize,
    column: usize,
    ch: char,
    color: Option<Color>,
    priority: u8,
) {
    let Some(cell) = canvas.get_mut(row).and_then(|row| row.get_mut(column)) else {
        return;
    };
    if cell.priority > priority && cell.ch != ' ' {
        return;
    }
    *cell = StyledCell {
        ch,
        color,
        priority,
    };
}

fn render_i64_axis_pane(
    canvas: Vec<Vec<StyledCell>>,
    min_value: i64,
    max_value: i64,
) -> Vec<RenderLine> {
    render_axis_pane(canvas, |row_index, chart_height| {
        format_money(value_for_row_i64(
            row_index,
            min_value,
            max_value,
            chart_height,
        ))
    })
}

fn render_u64_axis_pane(
    canvas: Vec<Vec<StyledCell>>,
    max_value: u64,
    min_value: u64,
) -> Vec<RenderLine> {
    render_axis_pane(canvas, |row_index, chart_height| {
        format_compact_quantity(value_for_row_u64(
            row_index,
            min_value,
            max_value,
            chart_height,
        ))
    })
}

fn render_f64_axis_pane(
    canvas: Vec<Vec<StyledCell>>,
    min_value: f64,
    max_value: f64,
) -> Vec<RenderLine> {
    render_axis_pane(canvas, |row_index, chart_height| {
        format_money(
            value_for_row_f64(row_index, min_value, max_value, chart_height).round() as i64,
        )
    })
}

fn render_axis_pane(
    canvas: Vec<Vec<StyledCell>>,
    mut label_for_row: impl FnMut(usize, usize) -> String,
) -> Vec<RenderLine> {
    let chart_height = canvas.len();
    let mid_row = chart_height / 2;
    canvas
        .into_iter()
        .enumerate()
        .map(|(row_index, row)| {
            let axis_label =
                if row_index == 0 || row_index == mid_row || row_index + 1 == chart_height {
                    format!("{:>10}", label_for_row(row_index, chart_height))
                } else {
                    " ".repeat(10)
                };
            let mut cells = axis_label
                .chars()
                .map(|ch| StyledCell {
                    ch,
                    color: Some(Color::DarkGrey),
                    priority: 255,
                })
                .collect::<Vec<_>>();
            cells.push(StyledCell {
                ch: ' ',
                color: Some(Color::DarkGrey),
                priority: 255,
            });
            cells.push(StyledCell {
                ch: '│',
                color: Some(Color::DarkGrey),
                priority: 255,
            });
            cells.extend(row);
            styled_cells_line(&cells)
        })
        .collect()
}

fn map_numeric_to_row(value: f64, min_value: f64, max_value: f64, chart_height: usize) -> usize {
    if chart_height <= 1 || min_value >= max_value {
        return 0;
    }

    let normalized = (value.clamp(min_value, max_value) - min_value) / (max_value - min_value);
    let scaled = ((chart_height - 1) as f64 * (1.0 - normalized)).round() as usize;
    scaled.min(chart_height - 1)
}

fn padded_i64_range(min_value: i64, max_value: i64) -> (i64, i64) {
    if min_value != max_value {
        return (min_value, max_value);
    }

    let pad = (min_value.abs() / 50).max(100);
    (min_value.saturating_sub(pad), max_value.saturating_add(pad))
}

fn padded_f64_range(min_value: f64, max_value: f64) -> (f64, f64) {
    if (max_value - min_value).abs() > f64::EPSILON {
        return (min_value, max_value);
    }

    let pad = (min_value.abs() / 50.0).max(100.0);
    (min_value - pad, max_value + pad)
}

fn value_for_row_i64(row_index: usize, min_value: i64, max_value: i64, chart_height: usize) -> i64 {
    if chart_height <= 1 {
        return max_value;
    }

    let ratio = 1.0 - row_index as f64 / (chart_height - 1) as f64;
    min_value + ((max_value - min_value) as f64 * ratio).round() as i64
}

fn value_for_row_u64(row_index: usize, min_value: u64, max_value: u64, chart_height: usize) -> u64 {
    if chart_height <= 1 {
        return max_value;
    }

    let ratio = 1.0 - row_index as f64 / (chart_height - 1) as f64;
    min_value + ((max_value - min_value) as f64 * ratio).round() as u64
}

fn value_for_row_f64(row_index: usize, min_value: f64, max_value: f64, chart_height: usize) -> f64 {
    if chart_height <= 1 {
        return max_value;
    }

    let ratio = 1.0 - row_index as f64 / (chart_height - 1) as f64;
    min_value + (max_value - min_value) * ratio
}

fn format_compact_quantity(value: u64) -> String {
    match value {
        1_000_000_000.. => format!("{:.1}B", value as f64 / 1_000_000_000.0),
        1_000_000.. => format!("{:.1}M", value as f64 / 1_000_000.0),
        1_000.. => format!("{:.1}K", value as f64 / 1_000.0),
        _ => value.to_string(),
    }
}

fn format_compact_dollars(value_dollars: i64) -> String {
    let sign = if value_dollars < 0 { "-" } else { "" };
    let absolute = value_dollars.unsigned_abs();
    match absolute {
        1_000_000_000_000.. => format!("{sign}${:.2}T", absolute as f64 / 1_000_000_000_000.0),
        1_000_000_000.. => format!("{sign}${:.2}B", absolute as f64 / 1_000_000_000.0),
        1_000_000.. => format!("{sign}${:.2}M", absolute as f64 / 1_000_000.0),
        1_000.. => format!("{sign}${:.1}K", absolute as f64 / 1_000.0),
        _ => format!("{sign}${absolute}"),
    }
}

fn build_consensus_graph_lines(detail: &SymbolDetail) -> Vec<RenderLine> {
    let rating_rows = vec![
        (
            "Strong buy",
            detail.strong_buy_count.unwrap_or(0),
            Color::Green,
        ),
        ("Buy", detail.buy_count.unwrap_or(0), Color::Green),
        ("Hold", detail.hold_count.unwrap_or(0), Color::Yellow),
        ("Sell", detail.sell_count.unwrap_or(0), Color::Red),
        (
            "Strong sell",
            detail.strong_sell_count.unwrap_or(0),
            Color::Red,
        ),
    ];
    let total_ratings = rating_rows.iter().map(|(_, count, _)| *count).sum::<u32>();

    if total_ratings == 0 {
        return vec![RenderLine {
            color: Some(Color::DarkGrey),
            text: "No rating distribution is available in the current feed.".to_string(),
        }];
    }

    let mut lines = vec![RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Analysts {}  Recommendation mean {}  Weighted firms {}",
            format_optional_count(detail.analyst_opinion_count),
            detail
                .recommendation_mean_hundredths
                .map(format_recommendation_mean)
                .unwrap_or_else(|| "n/a".to_string()),
            format_optional_count(detail.weighted_analyst_count),
        ),
    }];

    let max_rating_count = rating_rows
        .iter()
        .map(|(_, count, _)| *count)
        .max()
        .unwrap_or(0);
    for (label, count, color) in rating_rows {
        lines.push(RenderLine {
            color: Some(color),
            text: format!(
                "{:<11} [{}] {:>3}",
                label,
                horizontal_bar(count, max_rating_count, DETAIL_CONSENSUS_BAR_WIDTH),
                count,
            ),
        });
    }

    lines
}

fn horizontal_bar(value: u32, max_value: u32, width: usize) -> String {
    if width == 0 {
        return String::new();
    }
    if max_value == 0 {
        return "░".repeat(width);
    }

    let filled = ((value as usize * width) + max_value as usize - 1) / max_value as usize;
    format!(
        "{}{}",
        "█".repeat(filled.min(width)),
        "░".repeat(width.saturating_sub(filled.min(width))),
    )
}

fn compact_evidence_lines(detail: &SymbolDetail) -> Vec<String> {
    let mut lines = vec![format!(
        "Internal: profitable={}  upside {} vs threshold {}  discount {}",
        yes_no(detail.profitable),
        format_upside_percent(detail.market_price_cents, detail.intrinsic_value_cents),
        format_upside_percent_from_gap_bps(detail.minimum_gap_bps),
        format_money(detail.intrinsic_value_cents - detail.market_price_cents),
    )];

    lines.push(format!(
        "External: median {}  weighted {}  status {}  age {}",
        detail
            .external_signal_fair_value_cents
            .map(|fair_value_cents| {
                format!(
                    "{} ({})",
                    format_money(fair_value_cents),
                    format_upside_percent(detail.market_price_cents, fair_value_cents)
                )
            })
            .unwrap_or_else(|| "n/a".to_string()),
        detail
            .weighted_external_signal_fair_value_cents
            .map(|fair_value_cents| {
                format!(
                    "{} ({})",
                    format_money(fair_value_cents),
                    format_upside_percent(detail.market_price_cents, fair_value_cents)
                )
            })
            .unwrap_or_else(|| "n/a".to_string()),
        external_status_label(detail.external_status),
        detail
            .external_signal_age_seconds
            .map(|age_seconds| format!("{age_seconds}s"))
            .unwrap_or_else(|| "n/a".to_string()),
    ));

    lines.push(format!(
        "Result: {} / {}  target range {} to {}",
        qualification_label(detail.qualification),
        confidence_label(detail.confidence),
        format_optional_money(detail.external_signal_low_fair_value_cents),
        format_optional_money(detail.external_signal_high_fair_value_cents),
    ));

    lines
}

fn evidence_line_color(detail: &SymbolDetail, line_text: &str) -> Color {
    if line_text.starts_with("Internal:") {
        gap_color(
            checked_upside_bps(detail.market_price_cents, detail.intrinsic_value_cents)
                .unwrap_or(0),
            upside_bps_from_gap_bps(detail.minimum_gap_bps).unwrap_or(0),
        )
    } else if line_text.starts_with("External:") {
        external_status_color(detail.external_status)
    } else {
        status_summary_color(detail.qualification, detail.confidence)
    }
}

fn summarize_recent_alerts<'a>(alerts: impl DoubleEndedIterator<Item = &'a AlertEvent>) -> String {
    let recent = alerts
        .rev()
        .take(DETAIL_RECENT_SUMMARY_COUNT)
        .map(|alert| format!("{} #{}", alert_label(alert.kind), alert.sequence))
        .collect::<Vec<_>>();

    if recent.is_empty() {
        return "Alerts: none in the current session.".to_string();
    }

    format!("Alerts: {}", recent.join("  |  "))
}

fn summarize_recent_tape<'a>(tape: impl DoubleEndedIterator<Item = &'a TapeEvent>) -> String {
    let recent = tape
        .rev()
        .take(DETAIL_RECENT_SUMMARY_COUNT)
        .map(|event| {
            format!(
                "{} {} {}",
                format_upside_percent_from_gap_bps(event.gap_bps),
                if event.is_qualified {
                    "qualified"
                } else {
                    "watch"
                },
                confidence_label(event.confidence),
            )
        })
        .collect::<Vec<_>>();

    if recent.is_empty() {
        return "Tape: no recent qualifying state changes yet.".to_string();
    }

    format!("Tape: {}", recent.join("  |  "))
}

fn filtered_symbol_rows(state: &TerminalState, view_filter: &ViewFilter) -> Vec<CandidateRow> {
    state
        .filtered_rows(state.symbol_count().max(1), view_filter)
        .into_iter()
        .filter(|row| row.confidence > ConfidenceBand::Provisional)
        .collect()
}

#[cfg(test)]
fn analyst_consensus_lines(detail: &SymbolDetail) -> Vec<String> {
    let mut lines = Vec::new();

    if let (Some(low_target_cents), Some(high_target_cents)) = (
        detail.external_signal_low_fair_value_cents,
        detail.external_signal_high_fair_value_cents,
    ) {
        lines.push(format!(
            "Target range width: {} = {} - {}",
            format_money(high_target_cents - low_target_cents),
            format_money(high_target_cents),
            format_money(low_target_cents),
        ));
    } else {
        lines.push(format!(
            "Targets: mean {}  median {}",
            format_money(detail.intrinsic_value_cents),
            format_optional_money(detail.external_signal_fair_value_cents),
        ));
    }

    lines.push(format!(
        "Analysts: {}  Recommendation mean: {} (1.00=strong buy, 5.00=strong sell)",
        format_optional_count(detail.analyst_opinion_count),
        format_optional_recommendation_mean(detail.recommendation_mean_hundredths),
    ));

    if let (
        Some(strong_buy_count),
        Some(buy_count),
        Some(hold_count),
        Some(sell_count),
        Some(strong_sell_count),
    ) = (
        detail.strong_buy_count,
        detail.buy_count,
        detail.hold_count,
        detail.sell_count,
        detail.strong_sell_count,
    ) {
        lines.push(format!(
            "Ratings: strong buy {}  buy {}  hold {}  sell {}  strong sell {}",
            strong_buy_count, buy_count, hold_count, sell_count, strong_sell_count,
        ));
    } else {
        lines.push("Ratings: recommendation breakdown not available from provider.".to_string());
    }

    lines
}

fn normalize_frame_into(
    lines: &[RenderLine],
    viewport_width: usize,
    viewport_height: usize,
    output: &mut Vec<RenderLine>,
) {
    output.clear();
    output.reserve(viewport_height.saturating_sub(output.capacity()));
    for line in lines.iter().take(viewport_height) {
        output.push(RenderLine {
            color: line.color,
            text: clip_text_to_width(&line.text, viewport_width),
        });
    }
}

#[cfg(test)]
fn normalize_frame(
    lines: &[RenderLine],
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    let mut output = Vec::with_capacity(viewport_height);
    normalize_frame_into(lines, viewport_width, viewport_height, &mut output);
    output
}

fn encode_color_marker(color: Option<Color>) -> char {
    match color {
        None => '0',
        Some(Color::Blue) => '1',
        Some(Color::Cyan) => '2',
        Some(Color::DarkCyan) => '3',
        Some(Color::DarkGrey) => '4',
        Some(Color::Green) => '5',
        Some(Color::DarkGreen) => '6',
        Some(Color::Yellow) => '7',
        Some(Color::DarkYellow) => '8',
        Some(Color::Red) => '9',
        Some(Color::DarkRed) => 'a',
        Some(Color::Magenta) => 'b',
        Some(Color::DarkMagenta) => 'c',
        Some(Color::Grey) => 'd',
        Some(Color::White) => 'e',
        Some(Color::Reset) => 'f',
        Some(Color::Black) => 'g',
        Some(Color::DarkBlue) => 'h',
        _ => '0',
    }
}

fn decode_color_marker(code: char) -> Option<Color> {
    match code {
        '0' => None,
        '1' => Some(Color::Blue),
        '2' => Some(Color::Cyan),
        '3' => Some(Color::DarkCyan),
        '4' => Some(Color::DarkGrey),
        '5' => Some(Color::Green),
        '6' => Some(Color::DarkGreen),
        '7' => Some(Color::Yellow),
        '8' => Some(Color::DarkYellow),
        '9' => Some(Color::Red),
        'a' => Some(Color::DarkRed),
        'b' => Some(Color::Magenta),
        'c' => Some(Color::DarkMagenta),
        'd' => Some(Color::Grey),
        'e' => Some(Color::White),
        'f' => Some(Color::Reset),
        'g' => Some(Color::Black),
        'h' => Some(Color::DarkBlue),
        _ => None,
    }
}

fn styled_segments_line(segments: Vec<StyledSegment>) -> RenderLine {
    let mut text = String::new();
    let mut active_color = None;

    for segment in segments {
        if segment.text.is_empty() {
            continue;
        }
        if segment.color != active_color {
            text.push(INLINE_STYLE_MARKER);
            text.push(encode_color_marker(segment.color));
            active_color = segment.color;
        }
        text.push_str(&segment.text);
    }

    if active_color.is_some() {
        text.push(INLINE_STYLE_MARKER);
        text.push(encode_color_marker(None));
    }

    RenderLine { color: None, text }
}

fn styled_cells_line(cells: &[StyledCell]) -> RenderLine {
    let mut segments = Vec::new();
    let mut current_color = None;
    let mut current_text = String::new();

    for cell in cells {
        if cell.color != current_color && !current_text.is_empty() {
            segments.push(StyledSegment {
                color: current_color,
                text: std::mem::take(&mut current_text),
            });
        }
        current_color = cell.color;
        current_text.push(cell.ch);
    }

    if !current_text.is_empty() {
        segments.push(StyledSegment {
            color: current_color,
            text: current_text,
        });
    }

    styled_segments_line(segments)
}

#[cfg(test)]
fn collect_dirty_rows(
    previous_frame: &[RenderLine],
    next_frame: &[RenderLine],
    viewport_height: usize,
) -> Vec<usize> {
    let mut dirty_rows = Vec::new();
    collect_dirty_rows_into(previous_frame, next_frame, viewport_height, &mut dirty_rows);
    dirty_rows
}

fn collect_dirty_rows_into(
    previous_frame: &[RenderLine],
    next_frame: &[RenderLine],
    viewport_height: usize,
    output: &mut Vec<usize>,
) {
    output.clear();
    let visible_rows = previous_frame
        .len()
        .max(next_frame.len())
        .min(viewport_height);
    for row_index in 0..visible_rows {
        if previous_frame.get(row_index) != next_frame.get(row_index) {
            output.push(row_index);
        }
    }
}

fn collect_clear_rows(
    next_frame_len: usize,
    last_painted_rows: usize,
    viewport_height: usize,
) -> Vec<usize> {
    let clear_end = last_painted_rows.min(viewport_height);
    (next_frame_len..clear_end).collect()
}

fn clip_text_to_width(text: &str, viewport_width: usize) -> String {
    if viewport_width == 0 {
        return String::new();
    }

    let mut clipped = String::new();
    let mut visible_chars = 0usize;
    let mut chars = text.chars();

    while let Some(ch) = chars.next() {
        if ch == INLINE_STYLE_MARKER {
            if let Some(code) = chars.next() {
                clipped.push(ch);
                clipped.push(code);
            }
            continue;
        }

        if visible_chars == viewport_width {
            break;
        }

        clipped.push(ch);
        visible_chars += 1;
    }

    clipped
}

#[cfg(test)]
fn visible_text(text: &str) -> String {
    let mut visible = String::new();
    let mut chars = text.chars();

    while let Some(ch) = chars.next() {
        if ch == INLINE_STYLE_MARKER {
            let _ = chars.next();
            continue;
        }
        visible.push(ch);
    }

    visible
}

fn paint_row(stdout: &mut Stdout, row_index: usize, line: Option<&RenderLine>) -> io::Result<()> {
    queue!(
        stdout,
        MoveTo(0, row_index as u16),
        Clear(ClearType::CurrentLine)
    )?;

    if let Some(line) = line {
        if line.text.contains(INLINE_STYLE_MARKER) {
            let mut active_color = line.color;
            if let Some(color) = active_color {
                queue!(stdout, SetForegroundColor(color))?;
            }

            let mut buffer = String::new();
            let mut chars = line.text.chars();
            while let Some(ch) = chars.next() {
                if ch == INLINE_STYLE_MARKER {
                    if !buffer.is_empty() {
                        queue!(stdout, Print(&buffer))?;
                        buffer.clear();
                    }
                    if let Some(code) = chars.next() {
                        active_color = decode_color_marker(code);
                        match active_color {
                            Some(Color::Reset) | None => queue!(stdout, ResetColor)?,
                            Some(color) => queue!(stdout, SetForegroundColor(color))?,
                        }
                    }
                    continue;
                }

                buffer.push(ch);
            }

            if !buffer.is_empty() {
                queue!(stdout, Print(&buffer))?;
            }
            queue!(stdout, ResetColor)?;
        } else {
            if let Some(color) = line.color {
                queue!(stdout, SetForegroundColor(color))?;
            }

            queue!(stdout, Print(&line.text), ResetColor)?;
        }
    }

    Ok(())
}

fn load_initial_state(options: &RuntimeOptions) -> io::Result<LoadedState> {
    let mut startup_issues = Vec::new();

    #[cfg(test)]
    if options.replay_file.is_some()
        || options.journal_file.is_some()
        || options.watchlist_file.is_some()
    {
        let mut state = if let Some(replay_file) = options.replay_file.as_ref() {
            TerminalState::replay_file(2_000, 30, 32, replay_file)
                .map_err(|error| with_path_context(error, "load replay file", replay_file))?
        } else if let Some(journal_file) = options.journal_file.as_ref() {
            if journal_file.exists() {
                match TerminalState::replay_file(2_000, 30, 32, journal_file)
                    .map_err(|error| with_path_context(error, "load journal file", journal_file))
                {
                    Ok(state) => state,
                    Err(error) => {
                        startup_issues.push(StartupIssue {
                            key: ISSUE_KEY_JOURNAL_RESTORE,
                            severity: IssueSeverity::Warning,
                            title: "Journal restore failed",
                            detail: format!("{error}. Starting with an empty session instead."),
                        });
                        TerminalState::new(2_000, 30, 32)
                    }
                }
            } else {
                TerminalState::new(2_000, 30, 32)
            }
        } else {
            TerminalState::new(2_000, 30, 32)
        };

        if let Some(watchlist_file) = options.watchlist_file.as_ref() {
            if watchlist_file.exists() {
                if let Err(error) = state.load_watchlist_file(watchlist_file).map_err(|error| {
                    with_path_context(error, "load watchlist file", watchlist_file)
                }) {
                    startup_issues.push(StartupIssue {
                        key: ISSUE_KEY_WATCHLIST_RESTORE,
                        severity: IssueSeverity::Warning,
                        title: "Watchlist restore failed",
                        detail: format!("{error}. Starting without the saved watchlist instead."),
                    });
                }
            }
        }

        return Ok(LoadedState {
            state,
            app: AppState::default(),
            tracked_symbols: if options.symbols.is_empty() {
                default_live_symbols()
            } else {
                options.symbols.clone()
            },
            persistence_db_path: None,
            startup_issues,
        });
    }

    let mut state = TerminalState::new(2_000, 30, 32);
    let mut app = AppState::default();
    let mut tracked_symbols = if options.symbols_explicit {
        options.symbols.clone()
    } else {
        default_live_symbols()
    };
    let mut persistence_db_path = None;

    if options.persist_enabled {
        let state_db = options
            .state_db
            .clone()
            .unwrap_or_else(persistence::default_state_db_path);

        match persistence::load_warm_start(&state_db) {
            Ok(PersistenceBootstrap {
                tracked_symbols: _persisted_tracked_symbols,
                watchlist,
                symbol_states,
                chart_cache,
                issues,
                last_persisted_at,
            }) => {
                let tracked_symbol_set = tracked_symbols.iter().cloned().collect::<HashSet<_>>();
                let hydrated_symbol_states = symbol_states
                    .into_iter()
                    .filter(|symbol_state| tracked_symbol_set.contains(&symbol_state.symbol))
                    .collect::<Vec<_>>();
                let stale_symbols = hydrated_symbol_states
                    .iter()
                    .map(|symbol_state| symbol_state.symbol.clone())
                    .collect::<Vec<_>>();
                let hydrated_chart_cache = chart_cache
                    .into_iter()
                    .filter(|chart| tracked_symbol_set.contains(&chart.symbol))
                    .collect::<Vec<_>>();

                state.hydrate_from_persisted(&hydrated_symbol_states, &watchlist);
                app.issue_center.hydrate_from_persisted(&issues);
                app.load_warm_start(&hydrated_chart_cache, last_persisted_at, &stale_symbols);
                persistence_db_path = Some(state_db);
            }
            Err(error) => {
                startup_issues.push(StartupIssue {
                    key: ISSUE_KEY_SQLITE_RESTORE,
                    severity: IssueSeverity::Warning,
                    title: "SQLite warm-start restore failed",
                    detail: format!(
                        "{}. Starting with an empty live session instead.",
                        with_path_context(error, "load sqlite state database", &state_db)
                    ),
                });
                if persistence::reset_warm_start_state(&state_db).is_ok() {
                    persistence_db_path = Some(state_db);
                }
            }
        }
    }

    if tracked_symbols.is_empty() {
        tracked_symbols = default_live_symbols();
    }

    let history_export_root = persistence_db_path
        .as_ref()
        .and_then(|path| path.parent().map(|parent| parent.join("exports")))
        .or_else(|| std::env::current_dir().ok().map(|dir| dir.join("exports")))
        .unwrap_or_else(|| PathBuf::from("exports"));
    app.set_history_export_root(history_export_root);

    Ok(LoadedState {
        state,
        app,
        tracked_symbols,
        persistence_db_path,
        startup_issues,
    })
}

fn parse_runtime_options() -> io::Result<RuntimeOptions> {
    parse_runtime_options_from(std::env::args().skip(1))
}

fn parse_runtime_options_from<I, S>(args: I) -> io::Result<RuntimeOptions>
where
    I: IntoIterator<Item = S>,
    S: Into<String>,
{
    let mut options = RuntimeOptions::default();
    let mut args = args.into_iter().map(Into::into);
    let mut selected_profile: Option<String> = None;
    let mut explicit_symbols = Vec::new();

    while let Some(argument) = args.next() {
        match argument.as_str() {
            "--smoke" => options.smoke = true,
            "--state-db" => {
                let Some(path) = args.next() else {
                    return Err(io::Error::new(
                        ErrorKind::InvalidInput,
                        "--state-db requires a path",
                    ));
                };
                options.state_db = Some(PathBuf::from(path));
            }
            "--no-persist" => options.persist_enabled = false,
            "--symbols" => {
                let Some(symbols) = args.next() else {
                    return Err(io::Error::new(
                        ErrorKind::InvalidInput,
                        "--symbols requires a comma-separated list",
                    ));
                };
                explicit_symbols = parse_symbols_argument(&symbols)?;
                options.symbols_explicit = true;
            }
            "--profile" => {
                let Some(profile_name) = args.next() else {
                    return Err(io::Error::new(
                        ErrorKind::InvalidInput,
                        "--profile requires a profile name",
                    ));
                };
                if profile_symbols(&profile_name).is_none() {
                    return Err(io::Error::new(
                        ErrorKind::InvalidInput,
                        format!(
                            "unknown profile: {profile_name}. Available profiles: {}",
                            available_profile_names()
                        ),
                    ));
                }
                selected_profile = Some(profile_name);
                options.symbols_explicit = true;
            }
            "--replay-file" | "--journal-file" | "--watchlist-file" => {
                return Err(io::Error::new(
                    ErrorKind::InvalidInput,
                    format!(
                        "{argument} is no longer supported. Use --state-db PATH or --no-persist."
                    ),
                ));
            }
            "--help" | "-h" => {
                print_usage();
                std::process::exit(0);
            }
            _ => {
                return Err(io::Error::new(
                    ErrorKind::InvalidInput,
                    format!("unknown argument: {argument}"),
                ));
            }
        }
    }

    options.symbols = match selected_profile.as_deref() {
        Some(profile_name) => {
            let mut symbols = profile_symbols(profile_name).expect("validated profile should load");
            append_unique_symbols(&mut symbols, explicit_symbols);
            symbols
        }
        None if explicit_symbols.is_empty() => Vec::new(),
        None => explicit_symbols,
    };

    if options.persist_enabled && options.state_db.is_none() {
        options.state_db = Some(persistence::default_state_db_path());
    }

    Ok(options)
}

fn parse_symbols_argument(raw_symbols: &str) -> io::Result<Vec<String>> {
    let mut symbols = Vec::new();

    for symbol in raw_symbols
        .split(',')
        .map(|symbol| symbol.trim().to_ascii_uppercase())
        .filter(|symbol| !symbol.is_empty())
    {
        if !symbols.contains(&symbol) {
            symbols.push(symbol);
        }
    }

    if symbols.is_empty() {
        return Err(io::Error::new(
            ErrorKind::InvalidInput,
            "--symbols requires at least one symbol",
        ));
    }

    Ok(symbols)
}

fn print_usage() {
    print!("{}", usage_text());
}

fn usage_text() -> String {
    let profiles = profile_definitions()
        .iter()
        .map(|profile| format!("  {:<8} {}", profile.name, profile.description))
        .collect::<Vec<_>>()
        .join("\n");

    format!(
        concat!(
            "discount_screener [--smoke] [--profile NAME] [--symbols CSV] [--state-db PATH] [--no-persist]\n",
            "\n",
            "Options:\n",
            "  --smoke                 Run the static smoke path without live Yahoo requests\n",
            "  --profile NAME          Load a predefined starting universe for this session\n",
            "  --symbols CSV           Use a custom symbol list for this session; when combined with --profile these symbols are appended\n",
            "  --state-db PATH         Override the SQLite warm-start database path\n",
            "  --no-persist            Disable SQLite persistence and start with a live-only session\n",
            "  -h, --help              Show this help text\n",
            "\n",
            "Profiles:\n",
            "{profiles}\n"
        ),
        profiles = profiles,
    )
}

fn available_profile_names() -> String {
    profile_definitions()
        .iter()
        .map(|profile| profile.name)
        .collect::<Vec<_>>()
        .join(", ")
}

fn append_unique_symbols(symbols: &mut Vec<String>, extra_symbols: Vec<String>) {
    for symbol in extra_symbols {
        if !symbols.contains(&symbol) {
            symbols.push(symbol);
        }
    }
}

fn feed_loop(
    publisher: AppEventPublisher,
    control_receiver: mpsc::Receiver<FeedControl>,
    live_symbols: LiveSymbolState,
    feed_error_logger: Option<FeedErrorLogger>,
) {
    feed_loop_with_client_factory(
        publisher,
        control_receiver,
        live_symbols,
        feed_error_logger,
        MarketDataClient::new,
    );
}

fn feed_loop_with_client_factory<Client, BuildClient>(
    publisher: AppEventPublisher,
    control_receiver: mpsc::Receiver<FeedControl>,
    live_symbols: LiveSymbolState,
    feed_error_logger: Option<FeedErrorLogger>,
    mut build_client: BuildClient,
) where
    Client: LiveFeedClient + Sync,
    BuildClient: FnMut() -> io::Result<Client>,
{
    let mut client = None;
    let mut symbol_refresh_cursor = 0usize;
    let mut steady_refresh_budget = START_STEADY_FEED_REFRESH_BUDGET;
    let mut fetch_concurrency = START_FEED_FETCH_CONCURRENCY;
    let mut retry_symbols = VecDeque::<String>::new();
    let mut refresh_cycle = 0usize;
    let mut recovery_cooldown_cycles = 0usize;

    while let Ok(FeedControl::RefreshNow) = control_receiver.recv() {
        if client.is_none() {
            match build_client() {
                Ok(created_client) => client = Some(created_client),
                Err(error) => {
                    if let Some(feed_error_logger) = feed_error_logger.as_ref() {
                        let _ = feed_error_logger
                            .log_client_initialization_failure(live_symbols.count(), &error);
                    }
                    let _ = publisher.publish(AppEvent::FeedBatch(vec![FeedEvent::SourceStatus(
                        LiveSourceStatus {
                            tracked_symbols: live_symbols.count(),
                            fresh_symbols: 0,
                            stale_symbols: 0,
                            degraded_symbols: 0,
                            unavailable_symbols: live_symbols.count(),
                            last_error: Some(format!(
                                "market data client initialization failed: {error}"
                            )),
                        },
                    )]));
                    continue;
                }
            }
        }

        let Some(client) = client.as_ref() else {
            continue;
        };

        let symbols = live_symbols.snapshot();
        let refresh_plan = plan_feed_refresh(
            &symbols,
            &mut retry_symbols,
            symbol_refresh_cursor,
            refresh_cycle,
            steady_refresh_budget,
            fetch_concurrency,
        );
        if refresh_plan.symbols.is_empty() {
            let _ = publisher.publish(AppEvent::FeedStatus(FeedProgressStatus {
                message: "Waiting for symbols to refresh...".to_string(),
                color: Color::DarkGrey,
            }));
            continue;
        }
        let weighted_target_refresh_budget = refresh_plan
            .symbols
            .len()
            .min(WEIGHTED_TARGET_REFRESH_BUDGET_PER_CYCLE);
        if let Some(feed_error_logger) = feed_error_logger.as_ref() {
            let _ = feed_error_logger.log_debug(&format!(
                "refresh_plan cycle={} mode={} tracked={} batch={} retry_batch={} cursor={} concurrency={} steady_budget={}",
                refresh_cycle,
                refresh_plan.phase_label,
                refresh_plan.total_tracked_symbols,
                refresh_plan.symbols.len(),
                refresh_plan.retry_symbols,
                symbol_refresh_cursor,
                refresh_plan.concurrency,
                steady_refresh_budget,
            ));
        }
        let _ = publisher.publish(AppEvent::FeedStatus(FeedProgressStatus {
            message: format!(
                "{}: fetching {} of {} tracked symbols, retry queue {}, concurrency {}",
                refresh_plan.phase_label,
                refresh_plan.symbols.len(),
                refresh_plan.total_tracked_symbols,
                retry_symbols.len(),
                refresh_plan.concurrency,
            ),
            color: Color::DarkCyan,
        }));

        let Some(outcome) = publish_feed_refresh_concurrently(
            &publisher,
            &refresh_plan,
            feed_error_logger.as_ref(),
            |symbol_index, symbol| {
                client.fetch_symbol_with_options(
                    symbol,
                    should_refresh_weighted_target(
                        symbol_index,
                        symbol_refresh_cursor,
                        refresh_plan.total_tracked_symbols,
                        weighted_target_refresh_budget,
                    ),
                )
            },
        ) else {
            return;
        };

        symbol_refresh_cursor = refresh_plan.next_symbol_cursor;
        refresh_cycle = refresh_cycle.saturating_add(1);

        for symbol in outcome.retry_symbols {
            if !retry_symbols.contains(&symbol) {
                retry_symbols.push_back(symbol);
            }
        }

        if outcome.throttled_errors > 0 {
            fetch_concurrency = fetch_concurrency
                .saturating_sub(1)
                .max(MIN_FEED_FETCH_CONCURRENCY);
            steady_refresh_budget = (steady_refresh_budget / 2).max(MIN_STEADY_FEED_REFRESH_BUDGET);
            recovery_cooldown_cycles = FEED_RECOVERY_COOLDOWN_CYCLES;
            let _ = publisher.publish(AppEvent::FeedStatus(FeedProgressStatus {
                message: format!(
                    "Backoff active: Yahoo returned {} throttle errors, next window {} symbols at concurrency {} (retry queue {}).",
                    outcome.throttled_errors,
                    steady_refresh_budget,
                    fetch_concurrency,
                    retry_symbols.len(),
                ),
                color: Color::Yellow,
            }));
            if let Some(feed_error_logger) = feed_error_logger.as_ref() {
                let _ = feed_error_logger.log_debug(&format!(
                    "refresh_backoff throttled={} next_budget={} next_concurrency={} retry_queue={}",
                    outcome.throttled_errors,
                    steady_refresh_budget,
                    fetch_concurrency,
                    retry_symbols.len(),
                ));
            }
        } else {
            if recovery_cooldown_cycles > 0 {
                recovery_cooldown_cycles = recovery_cooldown_cycles.saturating_sub(1);
            } else {
                let next_concurrency = (fetch_concurrency + 1).min(MAX_FEED_FETCH_CONCURRENCY);
                let next_budget = (steady_refresh_budget + 8).min(MAX_STEADY_FEED_REFRESH_BUDGET);
                if next_concurrency != fetch_concurrency || next_budget != steady_refresh_budget {
                    fetch_concurrency = next_concurrency;
                    steady_refresh_budget = next_budget;
                    if let Some(feed_error_logger) = feed_error_logger.as_ref() {
                        let _ = feed_error_logger.log_debug(&format!(
                            "refresh_recovery next_budget={} next_concurrency={} retry_queue={}",
                            steady_refresh_budget,
                            fetch_concurrency,
                            retry_symbols.len(),
                        ));
                    }
                }
            }

            let _ = publisher.publish(AppEvent::FeedStatus(FeedProgressStatus {
                message: format!(
                    "{} complete: fresh {}, degraded {}, unavailable {}, retry queue {}.",
                    refresh_plan.phase_label,
                    outcome.fresh_symbols,
                    outcome.degraded_symbols,
                    outcome.unavailable_symbols,
                    retry_symbols.len(),
                ),
                color: if outcome.unavailable_symbols > 0 {
                    Color::Yellow
                } else {
                    Color::DarkGreen
                },
            }));
        }

        let dropped_refreshes = drain_pending_feed_refreshes(&control_receiver);
        if dropped_refreshes > 0 {
            if let Some(feed_error_logger) = feed_error_logger.as_ref() {
                let _ = feed_error_logger.log_debug(&format!(
                    "refresh_coalesced dropped_pending={dropped_refreshes}"
                ));
            }
            let _ = publisher.publish(AppEvent::FeedStatus(FeedProgressStatus {
                message: format!(
                    "Feed caught up: coalesced {dropped_refreshes} pending refresh ticks."
                ),
                color: Color::DarkGrey,
            }));
        }
    }
}

#[cfg(test)]
fn publish_feed_refresh<F>(
    publisher: &AppEventPublisher,
    symbols: &[String],
    feed_error_logger: Option<&FeedErrorLogger>,
    mut fetch_symbol: F,
) -> bool
where
    F: FnMut(usize, &str) -> io::Result<market_data::ProviderFetchResult>,
{
    let mut fresh_symbols = 0usize;
    let mut degraded_symbols = 0usize;
    let mut unavailable_symbols = 0usize;
    let mut last_error = None;

    for (index, symbol) in symbols.iter().enumerate() {
        let provider_result = match fetch_symbol(index, symbol) {
            Ok(provider_result) => provider_result,
            Err(error) => {
                unavailable_symbols += 1;
                last_error = Some(error.to_string());
                if let Some(feed_error_logger) = feed_error_logger {
                    let _ = feed_error_logger.log_symbol_failure(
                        symbol,
                        FeedFailureKind::ProviderError,
                        &error.to_string(),
                    );
                }
                continue;
            }
        };

        if provider_result.coverage.core == market_data::ProviderComponentState::Fresh {
            fresh_symbols += 1;
        }
        if !provider_result.all_components_fresh()
            && (provider_result.has_any_payload() || !provider_result.diagnostics.is_empty())
        {
            degraded_symbols += 1;
        } else if !provider_result.has_any_payload() {
            unavailable_symbols += 1;
        }

        if !publisher.publish(AppEvent::FeedBatch(build_symbol_feed_batch(
            provider_result,
        ))) {
            return false;
        }
    }

    publisher.publish(AppEvent::FeedBatch(vec![FeedEvent::SourceStatus(
        LiveSourceStatus {
            tracked_symbols: symbols.len(),
            fresh_symbols,
            stale_symbols: 0,
            degraded_symbols,
            unavailable_symbols,
            last_error,
        },
    )]))
}

struct FeedRefreshPlan {
    phase_label: &'static str,
    total_tracked_symbols: usize,
    retry_symbols: usize,
    concurrency: usize,
    symbols: Vec<(usize, String)>,
    next_symbol_cursor: usize,
}

struct FeedRefreshOutcome {
    fresh_symbols: usize,
    degraded_symbols: usize,
    unavailable_symbols: usize,
    throttled_errors: usize,
    retry_symbols: Vec<String>,
}

enum FeedFetchOutcome {
    Provider(market_data::ProviderFetchResult),
    Error {
        detail: String,
        retryable: bool,
        throttled: bool,
    },
}

fn plan_feed_refresh(
    symbols: &[String],
    retry_symbols: &mut VecDeque<String>,
    symbol_refresh_cursor: usize,
    refresh_cycle: usize,
    steady_refresh_budget: usize,
    fetch_concurrency: usize,
) -> FeedRefreshPlan {
    if symbols.is_empty() {
        return FeedRefreshPlan {
            phase_label: "idle",
            total_tracked_symbols: 0,
            retry_symbols: 0,
            concurrency: fetch_concurrency,
            symbols: Vec::new(),
            next_symbol_cursor: 0,
        };
    }

    let phase_label = if refresh_cycle == 0 {
        "bootstrap"
    } else {
        "steady"
    };
    let refresh_budget = if refresh_cycle == 0 {
        INITIAL_FEED_REFRESH_BUDGET
    } else {
        steady_refresh_budget
    }
    .min(symbols.len());

    let mut selected_symbols = Vec::with_capacity(refresh_budget);
    let mut retry_count = 0usize;
    while retry_count < MAX_RETRY_SYMBOLS_PER_CYCLE && selected_symbols.len() < refresh_budget {
        let Some(symbol) = retry_symbols.pop_front() else {
            break;
        };
        let Some(symbol_index) = symbols.iter().position(|candidate| candidate == &symbol) else {
            continue;
        };
        if selected_symbols
            .iter()
            .any(|(selected_index, _)| *selected_index == symbol_index)
        {
            continue;
        }
        selected_symbols.push((symbol_index, symbol));
        retry_count += 1;
    }

    let mut normal_selected = 0usize;
    let mut offset = 0usize;
    while selected_symbols.len() < refresh_budget && offset < symbols.len() {
        let symbol_index = (symbol_refresh_cursor + offset) % symbols.len();
        if !selected_symbols
            .iter()
            .any(|(selected_index, _)| *selected_index == symbol_index)
        {
            selected_symbols.push((symbol_index, symbols[symbol_index].clone()));
            normal_selected += 1;
        }
        offset += 1;
    }

    FeedRefreshPlan {
        phase_label,
        total_tracked_symbols: symbols.len(),
        retry_symbols: retry_count,
        concurrency: fetch_concurrency.min(selected_symbols.len()).max(1),
        next_symbol_cursor: next_weighted_target_refresh_cursor(
            symbol_refresh_cursor,
            symbols.len(),
            normal_selected,
        ),
        symbols: selected_symbols,
    }
}

fn publish_feed_refresh_concurrently<F>(
    publisher: &AppEventPublisher,
    refresh_plan: &FeedRefreshPlan,
    feed_error_logger: Option<&FeedErrorLogger>,
    fetch_symbol: F,
) -> Option<FeedRefreshOutcome>
where
    F: Fn(usize, &str) -> io::Result<market_data::ProviderFetchResult> + Sync,
{
    if refresh_plan.symbols.is_empty() {
        return Some(FeedRefreshOutcome {
            fresh_symbols: 0,
            degraded_symbols: 0,
            unavailable_symbols: 0,
            throttled_errors: 0,
            retry_symbols: Vec::new(),
        });
    }

    let worker_count = refresh_plan
        .concurrency
        .min(refresh_plan.symbols.len())
        .max(1);
    let (result_sender, result_receiver) = mpsc::channel();

    thread::scope(|scope| {
        for worker_index in 0..worker_count {
            let result_sender = result_sender.clone();
            let fetch_symbol = &fetch_symbol;
            let planned_symbols = &refresh_plan.symbols;
            scope.spawn(move || {
                for planned_index in (worker_index..planned_symbols.len()).step_by(worker_count) {
                    let (symbol_index, symbol) = &planned_symbols[planned_index];
                    let outcome = match fetch_symbol(*symbol_index, symbol) {
                        Ok(provider_result) => FeedFetchOutcome::Provider(provider_result),
                        Err(error) => {
                            let detail = error.to_string();
                            FeedFetchOutcome::Error {
                                retryable: is_retryable_feed_error(&detail),
                                throttled: is_provider_throttle_error(&detail),
                                detail,
                            }
                        }
                    };
                    let _ = result_sender.send((symbol.clone(), outcome));
                }
            });
        }
        drop(result_sender);

        let mut fresh_symbols = 0usize;
        let mut degraded_symbols = 0usize;
        let mut unavailable_symbols = 0usize;
        let mut throttled_errors = 0usize;
        let mut completed_symbols = 0usize;
        let mut last_error = None::<String>;
        let mut retry_symbols = Vec::new();

        for (symbol, outcome) in result_receiver {
            completed_symbols += 1;
            match outcome {
                FeedFetchOutcome::Provider(provider_result) => {
                    if provider_result.coverage.core == market_data::ProviderComponentState::Fresh {
                        fresh_symbols += 1;
                    }
                    if !provider_result.all_components_fresh()
                        && (provider_result.has_any_payload()
                            || !provider_result.diagnostics.is_empty())
                    {
                        degraded_symbols += 1;
                    } else if !provider_result.has_any_payload() {
                        unavailable_symbols += 1;
                    }
                    if !publisher.publish(AppEvent::FeedBatch(build_symbol_feed_batch(
                        provider_result,
                    ))) {
                        return None;
                    }
                }
                FeedFetchOutcome::Error {
                    detail,
                    retryable,
                    throttled,
                } => {
                    unavailable_symbols += 1;
                    if throttled {
                        throttled_errors += 1;
                    }
                    if retryable {
                        retry_symbols.push(symbol.clone());
                    }
                    last_error = Some(detail.clone());
                    if let Some(feed_error_logger) = feed_error_logger {
                        let _ = feed_error_logger.log_symbol_failure(
                            &symbol,
                            FeedFailureKind::ProviderError,
                            &detail,
                        );
                        if !retryable {
                            let _ = feed_error_logger.log_debug(&format!(
                                "non_retryable_symbol_error symbol={} detail={}",
                                symbol, detail
                            ));
                        }
                    }
                }
            }

            if completed_symbols == 1
                || completed_symbols == refresh_plan.symbols.len()
                || completed_symbols % 4 == 0
            {
                if !publisher.publish(AppEvent::FeedStatus(FeedProgressStatus {
                    message: format!(
                        "{}: fetched {}/{} in current window, fresh {}, degraded {}, unavailable {}, retries queued {}.",
                        refresh_plan.phase_label,
                        completed_symbols,
                        refresh_plan.symbols.len(),
                        fresh_symbols,
                        degraded_symbols,
                        unavailable_symbols,
                        retry_symbols.len(),
                    ),
                    color: if unavailable_symbols > 0 {
                        Color::Yellow
                    } else {
                        Color::DarkCyan
                    },
                })) {
                    return None;
                }
            }
        }

        if !publisher.publish(AppEvent::FeedBatch(vec![FeedEvent::SourceStatus(
            LiveSourceStatus {
                tracked_symbols: refresh_plan.total_tracked_symbols,
                fresh_symbols,
                stale_symbols: 0,
                degraded_symbols,
                unavailable_symbols,
                last_error,
            },
        )])) {
            return None;
        }

        Some(FeedRefreshOutcome {
            fresh_symbols,
            degraded_symbols,
            unavailable_symbols,
            throttled_errors,
            retry_symbols,
        })
    })
}

fn drain_pending_feed_refreshes(control_receiver: &mpsc::Receiver<FeedControl>) -> usize {
    let mut dropped = 0usize;
    while matches!(control_receiver.try_recv(), Ok(FeedControl::RefreshNow)) {
        dropped += 1;
    }
    dropped
}

fn is_provider_throttle_error(detail: &str) -> bool {
    detail.contains("429")
}

fn is_retryable_feed_error(detail: &str) -> bool {
    is_provider_throttle_error(detail)
        || detail.contains("502")
        || detail.contains("503")
        || detail.contains("504")
        || detail.contains("timed out")
        || detail.contains("connection reset")
        || detail.contains("Broken pipe")
}

fn should_refresh_weighted_target(
    symbol_index: usize,
    refresh_cursor: usize,
    symbol_count: usize,
    refresh_budget: usize,
) -> bool {
    if symbol_count == 0 || refresh_budget == 0 {
        return false;
    }

    let normalized_cursor = refresh_cursor % symbol_count;
    let normalized_index = (symbol_index + symbol_count - normalized_cursor) % symbol_count;
    normalized_index < refresh_budget.min(symbol_count)
}

fn next_weighted_target_refresh_cursor(
    refresh_cursor: usize,
    symbol_count: usize,
    refresh_budget: usize,
) -> usize {
    if symbol_count == 0 || refresh_budget == 0 {
        return 0;
    }

    (refresh_cursor + refresh_budget.min(symbol_count)) % symbol_count
}

fn build_symbol_feed_batch(provider_result: market_data::ProviderFetchResult) -> Vec<FeedEvent> {
    let emit_coverage =
        !provider_result.all_components_fresh() || !provider_result.diagnostics.is_empty();
    let mut events = Vec::new();

    if let Some(snapshot) = provider_result.snapshot {
        events.push(FeedEvent::Snapshot(snapshot));
    }

    if let Some(signal) = provider_result.external_signal {
        events.push(FeedEvent::External(signal));
    }

    if let Some(fundamentals) = provider_result.fundamentals {
        events.push(FeedEvent::Fundamentals(fundamentals));
    }

    if emit_coverage {
        events.push(FeedEvent::Coverage(SymbolCoverageEvent {
            symbol: provider_result.symbol,
            coverage: provider_result.coverage,
            diagnostics: provider_result.diagnostics,
        }));
    }

    events
}

fn analysis_input_key(fundamentals: &FundamentalSnapshot) -> AnalysisInputKey {
    // Quote-driven market-cap drift would otherwise invalidate the DCF cache on every refresh.
    AnalysisInputKey {
        symbol: fundamentals.symbol.clone(),
        shares_outstanding: fundamentals.shares_outstanding,
        total_debt_dollars: fundamentals.total_debt_dollars,
        total_cash_dollars: fundamentals.total_cash_dollars,
        beta_millis: fundamentals.beta_millis,
    }
}

fn confidence_label(confidence: ConfidenceBand) -> &'static str {
    match confidence {
        ConfidenceBand::Low => "low",
        ConfidenceBand::Provisional => "provisional",
        ConfidenceBand::High => "high",
    }
}

fn qualification_label(qualification: QualificationStatus) -> &'static str {
    match qualification {
        QualificationStatus::Qualified => "qualified",
        QualificationStatus::Unprofitable => "unprofitable",
        QualificationStatus::GapTooSmall => "gap-too-small",
    }
}

fn external_status_label(status: ExternalSignalStatus) -> &'static str {
    match status {
        ExternalSignalStatus::Missing => "missing",
        ExternalSignalStatus::Stale => "stale",
        ExternalSignalStatus::Supportive => "supportive",
        ExternalSignalStatus::Divergent => "divergent",
    }
}

fn alert_label(kind: AlertKind) -> &'static str {
    match kind {
        AlertKind::EnteredQualified => "entered-qualified",
        AlertKind::ExitedQualified => "exited-qualified",
        AlertKind::ConfidenceUpgraded => "confidence-upgraded",
    }
}

fn input_mode_label(input_mode: &InputMode) -> &'static str {
    match input_mode {
        InputMode::Normal => "normal",
        InputMode::FilterSearch(_) => "filter",
        InputMode::SymbolSearch(_) => "symbol",
    }
}

fn active_filter_query<'a>(view_filter: &'a ViewFilter, input_mode: &'a InputMode) -> &'a str {
    match input_mode {
        InputMode::Normal | InputMode::SymbolSearch(_) => &view_filter.query,
        InputMode::FilterSearch(buffer) => buffer,
    }
}

fn plain_text_fits_viewport(text: &str, viewport_width: usize) -> bool {
    text.chars().count() <= viewport_width
}

fn main_screen_header(viewport_width: usize, live_mode: bool) -> String {
    let compact = if live_mode {
        "DISCOUNT TERMINAL  |  d detail  / filter  s symbol  space pause  q quit"
    } else {
        "DISCOUNT TERMINAL  |  d detail  / filter  l logs  q quit"
    };
    let full = if live_mode {
        "DISCOUNT TERMINAL  |  j/k move  |  d detail  |  w watch  |  / filter  |  s symbol  |  l logs  |  f watch filter  |  space pause  |  q quit"
    } else {
        "DISCOUNT TERMINAL  |  j/k move  |  d detail  |  w watch  |  / filter  |  l logs  |  f watch filter  |  q quit"
    };

    if plain_text_fits_viewport(full, viewport_width) {
        full.to_string()
    } else {
        compact.to_string()
    }
}

fn main_screen_status_line(
    viewport_width: usize,
    state: &TerminalState,
    app: &AppState,
    source_status: Option<&LiveSourceStatus>,
    tracked_count: usize,
    updates_per_second: usize,
    live_mode: bool,
) -> String {
    if live_mode {
        let compact = format!(
            "Mode: live  Feed: {}  Tracked: {}  Loaded: {}  Pending: {}  Rate: {}/s",
            if app.paused { "paused" } else { "running" },
            tracked_count,
            state.symbol_count(),
            app.pending_count(),
            updates_per_second,
        );
        let fresh_symbols = source_status
            .map(|status| status.fresh_symbols)
            .unwrap_or_else(|| state.symbol_count().saturating_sub(app.stale_symbols.len()));
        let stale_symbols = source_status
            .map(|status| status.stale_symbols)
            .unwrap_or_else(|| app.stale_symbols.len());
        let degraded_symbols = source_status
            .map(|status| status.degraded_symbols)
            .unwrap_or_else(|| app.degraded_symbols.len());
        let unavailable_symbols = source_status
            .map(|status| status.unavailable_symbols)
            .unwrap_or_else(|| tracked_count.saturating_sub(state.symbol_count()));

        let full = format!(
            "Mode: live  Source: yahoo  Feed: {}  Tracked: {}  Fresh: {}  Stale: {}  Degraded: {}  Unavailable: {}  Applied: {}  Pending: {}  Rate: {}/s",
            if app.paused { "paused" } else { "running" },
            tracked_count,
            fresh_symbols,
            stale_symbols,
            degraded_symbols,
            unavailable_symbols,
            state.total_events(),
            app.pending_count(),
            updates_per_second,
        );

        return if plain_text_fits_viewport(&full, viewport_width) {
            full
        } else {
            compact
        };
    }

    let compact = format!(
        "Mode: replay  Feed: {}  Symbols: {}  Pending: {}  Rate: {}/s",
        if app.paused { "paused" } else { "running" },
        state.symbol_count(),
        app.pending_count(),
        updates_per_second,
    );
    let full = format!(
        "Mode: replay  Source: journal  Feed: {}  Symbols: {}  Applied: {}  Pending: {}  Rate: {}/s",
        if app.paused { "paused" } else { "running" },
        state.symbol_count(),
        state.total_events(),
        app.pending_count(),
        updates_per_second,
    );

    if plain_text_fits_viewport(&full, viewport_width) {
        full
    } else {
        compact
    }
}

fn input_prompt(app: &AppState, live_mode: bool, viewport_width: usize) -> String {
    match &app.input_mode {
        InputMode::Normal => app.status_message.clone().unwrap_or_else(|| {
            let compact = if live_mode {
                "d detail  / filter  s symbol  l logs  Backspace back  Ctrl+C quit"
            } else {
                "d detail  / filter  l logs  Backspace back  Ctrl+C quit"
            };
            let full = if live_mode {
                "Use d or Enter for ticker detail, / to filter, s to track a symbol, l to open issues, Backspace to go back, or Ctrl+C to quit."
            } else {
                "Use d or Enter for ticker detail, / to filter, l to open issues, Backspace to go back, or Ctrl+C to quit."
            };

            if plain_text_fits_viewport(full, viewport_width) {
                full.to_string()
            } else {
                compact.to_string()
            }
        }),
        InputMode::FilterSearch(buffer) => {
            let full = format!(
                "Filter rows: '{buffer}'  Enter apply  Backspace delete or go back  Esc cancel  Ctrl+C quit"
            );
            let compact = format!(
                "Filter: '{buffer}'  Enter apply  Esc cancel  Backspace edit/back"
            );

            if plain_text_fits_viewport(&full, viewport_width) {
                full
            } else {
                compact
            }
        }
        InputMode::SymbolSearch(buffer) => {
            let full = format!(
                "Track symbol: '{buffer}'  Enter add  Backspace delete or go back  Esc cancel  Ctrl+C quit"
            );
            let compact = format!(
                "Symbol: '{buffer}'  Enter add  Esc cancel  Backspace edit/back"
            );

            if plain_text_fits_viewport(&full, viewport_width) {
                full
            } else {
                compact
            }
        }
    }
}

fn should_leave_input_mode_on_backspace(buffer: &mut String) -> bool {
    if buffer.is_empty() {
        return true;
    }

    buffer.pop();
    false
}

fn apply_live_source_status(issue_center: &mut IssueCenter, source_status: &LiveSourceStatus) {
    if source_status.tracked_symbols == 0 {
        issue_center.resolve(ISSUE_KEY_FEED_UNAVAILABLE);
        issue_center.resolve(ISSUE_KEY_FEED_PARTIAL);
        return;
    }

    let build_partial_feed_detail = |source_status: &LiveSourceStatus| {
        let mut detail = format!(
            "Fresh {}  Stale {}  Degraded {}  Unavailable {} of {} tracked symbols.",
            source_status.fresh_symbols,
            source_status.stale_symbols,
            source_status.degraded_symbols,
            source_status.unavailable_symbols,
            source_status.tracked_symbols
        );

        if let Some(last_error) = &source_status.last_error {
            detail.push_str(&format!(" Last provider error: {}", last_error));
        }

        detail
    };

    if source_status.fresh_symbols == 0 && source_status.stale_symbols == 0 {
        let detail = if source_status.degraded_symbols > 0
            || source_status.unavailable_symbols > 0
            || source_status.last_error.is_some()
        {
            build_partial_feed_detail(source_status)
        } else {
            format!(
                "Loaded 0 of {} tracked symbols and no provider detail was returned.",
                source_status.tracked_symbols
            )
        };

        issue_center.raise(
            ISSUE_KEY_FEED_UNAVAILABLE,
            IssueSource::Feed,
            IssueSeverity::Error,
            "Live source unavailable",
            detail,
        );
        issue_center.resolve(ISSUE_KEY_FEED_PARTIAL);
        return;
    }

    issue_center.resolve(ISSUE_KEY_FEED_UNAVAILABLE);

    if source_status.degraded_symbols > 0
        || source_status.unavailable_symbols > 0
        || source_status.last_error.is_some()
    {
        issue_center.raise(
            ISSUE_KEY_FEED_PARTIAL,
            IssueSource::Feed,
            IssueSeverity::Warning,
            "Live source partially degraded",
            build_partial_feed_detail(source_status),
        );
    } else {
        issue_center.resolve(ISSUE_KEY_FEED_PARTIAL);
    }
}

fn synthesize_live_source_status(
    state: &TerminalState,
    app: &mut AppState,
    live_symbols: Option<&LiveSymbolState>,
) {
    let tracked_symbols = live_symbols.map(|symbols| symbols.count()).unwrap_or(0);
    let visible_symbols = state.symbol_count();
    let stale_symbols = app.stale_symbols.len().min(visible_symbols);
    let fresh_symbols = visible_symbols.saturating_sub(stale_symbols);
    let degraded_symbols = app.degraded_symbols.len().min(visible_symbols);
    let unavailable_symbols = tracked_symbols.saturating_sub(visible_symbols);
    let last_error = app
        .provider_coverage
        .values()
        .flat_map(|coverage| coverage.diagnostics.iter())
        .find(|diagnostic| diagnostic.kind == market_data::ProviderDiagnosticKind::Error)
        .map(|diagnostic| diagnostic.detail.clone());

    let source_status = LiveSourceStatus {
        tracked_symbols,
        fresh_symbols,
        stale_symbols,
        degraded_symbols,
        unavailable_symbols,
        last_error,
    };
    app.set_live_source_status(source_status.clone());
    apply_live_source_status(&mut app.issue_center, &source_status);
}

fn log_live_source_summary(
    feed_error_logger: Option<&FeedErrorLogger>,
    source_status: Option<&LiveSourceStatus>,
) {
    let (Some(feed_error_logger), Some(source_status)) = (feed_error_logger, source_status) else {
        return;
    };
    let _ = feed_error_logger.log_refresh_summary(
        source_status.tracked_symbols,
        source_status.fresh_symbols,
        source_status.stale_symbols,
        source_status.degraded_symbols,
        source_status.unavailable_symbols,
        source_status.last_error.as_deref(),
    );
}

fn log_symbol_coverage_event(
    feed_error_logger: Option<&FeedErrorLogger>,
    state: &TerminalState,
    coverage: &SymbolCoverageEvent,
) {
    let Some(feed_error_logger) = feed_error_logger else {
        return;
    };
    let has_fresh_components = coverage.coverage.core == market_data::ProviderComponentState::Fresh
        || coverage.coverage.external == market_data::ProviderComponentState::Fresh
        || coverage.coverage.fundamentals == market_data::ProviderComponentState::Fresh;
    let action = if has_fresh_components {
        "published_partial"
    } else if state.detail(&coverage.symbol).is_some() {
        "kept_stale"
    } else {
        "dropped"
    };

    for diagnostic in &coverage.diagnostics {
        let _ = feed_error_logger.log_provider_diagnostic(&coverage.symbol, diagnostic, action);
    }
}

fn format_symbol_coverage_summary(coverage: &SymbolCoverageEvent) -> String {
    if coverage.diagnostics.is_empty() {
        return String::new();
    }

    coverage
        .diagnostics
        .iter()
        .map(|diagnostic| {
            format!(
                "{} {}: {}",
                provider_component_label(diagnostic.component),
                provider_diagnostic_kind_label(diagnostic.kind),
                diagnostic.detail
            )
        })
        .collect::<Vec<_>>()
        .join("  |  ")
}

fn provider_component_label(component: market_data::ProviderComponent) -> &'static str {
    match component {
        market_data::ProviderComponent::Chart => "chart",
        market_data::ProviderComponent::QuoteHtml => "quote_html",
        market_data::ProviderComponent::Core => "core",
        market_data::ProviderComponent::External => "external",
        market_data::ProviderComponent::Fundamentals => "fundamentals",
        market_data::ProviderComponent::WeightedTarget => "weighted_target",
    }
}

fn provider_diagnostic_kind_label(kind: market_data::ProviderDiagnosticKind) -> &'static str {
    match kind {
        market_data::ProviderDiagnosticKind::Missing => "missing",
        market_data::ProviderDiagnosticKind::Error => "error",
    }
}

fn format_symbol_list(symbols: &[String]) -> String {
    if symbols.is_empty() {
        return "none".to_string();
    }

    const MAX_LABEL_WIDTH: usize = 80;

    let mut label = String::new();
    let mut hidden_count = 0usize;

    for (index, symbol) in symbols.iter().enumerate() {
        let separator = if index == 0 { "" } else { ", " };
        let next_width = label.len() + separator.len() + symbol.len();

        if next_width > MAX_LABEL_WIDTH {
            hidden_count = symbols.len().saturating_sub(index);
            break;
        }

        label.push_str(separator);
        label.push_str(symbol);
    }

    if hidden_count > 0 {
        label.push_str(&format!(" ... (+{hidden_count})"));
    }

    label
}

fn format_age_seconds(age_seconds: u64) -> String {
    if age_seconds < 60 {
        return format!("{age_seconds}s");
    }

    let age_minutes = age_seconds / 60;
    if age_minutes < 60 {
        return format!("{age_minutes}m");
    }

    let age_hours = age_minutes / 60;
    if age_hours < 24 {
        return format!("{age_hours}h");
    }

    format!("{}d", age_hours / 24)
}

fn format_symbol_with_company(symbol: &str, company_name: Option<&str>) -> String {
    match company_name {
        Some(company_name) if !company_name.trim().is_empty() => {
            let company_name = company_name.trim();
            let mut label = String::with_capacity(symbol.len() + 1 + company_name.len());
            label.push_str(symbol);
            label.push(' ');
            label.push_str(company_name);
            label
        }
        _ => symbol.to_string(),
    }
}

fn candidate_company_label(symbol: &str, company_name: Option<&str>) -> String {
    let company_name = company_name.map(str::trim).filter(|name| !name.is_empty());
    let total_chars =
        symbol.chars().count() + company_name.map_or(0, |name| 1 + name.chars().count());

    if total_chars <= CANDIDATE_COMPANY_COLUMN_WIDTH {
        return format_symbol_with_company(symbol, company_name);
    }

    if CANDIDATE_COMPANY_COLUMN_WIDTH <= 3 {
        return ".".repeat(CANDIDATE_COMPANY_COLUMN_WIDTH);
    }

    let mut label = String::with_capacity(CANDIDATE_COMPANY_COLUMN_WIDTH);
    let mut remaining = CANDIDATE_COMPANY_COLUMN_WIDTH - 3;

    push_prefix_chars(&mut label, symbol, &mut remaining);
    if let Some(company_name) = company_name {
        if remaining > 0 {
            label.push(' ');
            remaining -= 1;
            push_prefix_chars(&mut label, company_name, &mut remaining);
        }
    }
    label.push_str("...");

    label
}

fn push_prefix_chars(output: &mut String, text: &str, remaining: &mut usize) {
    if *remaining == 0 {
        return;
    }

    for character in text.chars() {
        if *remaining == 0 {
            break;
        }

        output.push(character);
        *remaining -= 1;
    }
}

fn chart_ranges() -> [ChartRange; 6] {
    [
        ChartRange::Day,
        ChartRange::Week,
        ChartRange::Month,
        ChartRange::Year,
        ChartRange::FiveYears,
        ChartRange::TenYears,
    ]
}

fn history_windows() -> [HistoryWindow; 6] {
    [
        HistoryWindow::Day,
        HistoryWindow::Week,
        HistoryWindow::Month,
        HistoryWindow::Quarter,
        HistoryWindow::Year,
        HistoryWindow::All,
    ]
}

fn chart_range_label(range: ChartRange) -> &'static str {
    match range {
        ChartRange::Day => "D",
        ChartRange::Week => "W",
        ChartRange::Month => "M",
        ChartRange::Year => "1Y",
        ChartRange::FiveYears => "5Y",
        ChartRange::TenYears => "10Y",
    }
}

fn history_window_label(window: HistoryWindow) -> &'static str {
    match window {
        HistoryWindow::Day => "1D",
        HistoryWindow::Week => "1W",
        HistoryWindow::Month => "1M",
        HistoryWindow::Quarter => "3M",
        HistoryWindow::Year => "1Y",
        HistoryWindow::All => "All",
    }
}

fn history_group_label(group: HistoryMetricGroup) -> &'static str {
    match group {
        HistoryMetricGroup::Core => "Core",
        HistoryMetricGroup::Fundamentals => "Fundamentals",
        HistoryMetricGroup::Relative => "Relative",
        HistoryMetricGroup::Dcf => "DCF",
        HistoryMetricGroup::Chart => "Chart",
    }
}

fn history_subview_label(subview: HistorySubview) -> &'static str {
    match subview {
        HistorySubview::Graphs => "Graphs",
        HistorySubview::Table => "Table",
    }
}

fn history_rows(
    history: &[persistence::PersistedRevisionRecord],
    group: HistoryMetricGroup,
    window: HistoryWindow,
) -> Vec<HistoryMetricRow> {
    history_series(history, group, window)
        .iter()
        .filter_map(history_metric_row_from_series)
        .collect()
}

fn history_graph_tiles(
    history: &[&persistence::PersistedRevisionRecord],
    group: HistoryMetricGroup,
) -> Vec<HistoryGraphTile> {
    match group {
        HistoryMetricGroup::Chart => chart_history_graph_tiles(history),
        _ => history_series_from_filtered(history, group)
            .into_iter()
            .filter_map(|series| history_graph_tile_from_series(&series))
            .collect(),
    }
}

fn chart_history_graph_tiles(
    history: &[&persistence::PersistedRevisionRecord],
) -> Vec<HistoryGraphTile> {
    chart_ranges()
        .iter()
        .map(|range| {
            let close_series = build_history_series(
                history,
                HistoryMetricGroup::Chart,
                HistoryMetricDef {
                    key: chart_metric_key(*range, ChartMetricKind::Close),
                    label: chart_range_label(*range).to_string(),
                    range_key: Some(chart_range_export_prefix(*range)),
                    unit: HistoryUnit::Usd,
                    extractor: HistoryValueExtractor::ChartMetric(*range, ChartMetricKind::Close),
                },
            );
            let latest_summary = history.iter().rev().find_map(|record| {
                record
                    .payload
                    .chart_summaries
                    .iter()
                    .find(|summary| summary.range == *range)
            });

            if let Some(series) = close_series.as_ref() {
                let mut tile = history_graph_tile_from_series(series).unwrap_or_else(|| {
                    empty_history_graph_tile(format!("{} range", chart_range_label(*range)))
                });
                tile.label = format!("{} range", chart_range_label(*range));
                if let Some(summary) = latest_summary {
                    tile.footer_lines = vec![
                        clip_plain_text(
                            &format!(
                                "E20 {}  E50 {}  E200 {}",
                                format_optional_history_money(
                                    summary.ema20_cents.map(cents_to_dollars)
                                ),
                                format_optional_history_money(
                                    summary.ema50_cents.map(cents_to_dollars)
                                ),
                                format_optional_history_money(
                                    summary.ema200_cents.map(cents_to_dollars)
                                ),
                            ),
                            72,
                        ),
                        clip_plain_text(
                            &format!(
                                "MACD {}  SIG {}  HIST {}",
                                format_optional_history_money(
                                    summary.macd_cents.map(cents_to_dollars)
                                ),
                                format_optional_history_money(
                                    summary.signal_cents.map(cents_to_dollars)
                                ),
                                format_optional_history_money(
                                    summary.histogram_cents.map(cents_to_dollars)
                                ),
                            ),
                            72,
                        ),
                    ];
                }
                tile
            } else {
                empty_history_graph_tile(format!("{} range", chart_range_label(*range)))
            }
        })
        .collect()
}

fn history_series(
    history: &[persistence::PersistedRevisionRecord],
    group: HistoryMetricGroup,
    window: HistoryWindow,
) -> Vec<HistorySeries> {
    let filtered = filter_history_window(history, window);
    history_series_from_filtered(&filtered, group)
}

fn history_series_from_filtered(
    history: &[&persistence::PersistedRevisionRecord],
    group: HistoryMetricGroup,
) -> Vec<HistorySeries> {
    history_metric_defs(group)
        .into_iter()
        .filter_map(|def| build_history_series(history, group, def))
        .collect()
}

fn build_history_series(
    history: &[&persistence::PersistedRevisionRecord],
    group: HistoryMetricGroup,
    def: HistoryMetricDef,
) -> Option<HistorySeries> {
    let points = history
        .iter()
        .filter_map(|record| {
            def.extractor
                .extract(record)
                .map(|value| HistorySeriesPoint {
                    evaluated_at: record.evaluated_at,
                    revision_id: record.revision_id,
                    value,
                    available: metric_group_status(&record.payload, group).available,
                    stale: metric_group_status(&record.payload, group).stale,
                })
        })
        .collect::<Vec<_>>();
    if points.is_empty() {
        return None;
    }

    Some(HistorySeries {
        group,
        metric_key: def.key,
        label: def.label,
        range_key: def.range_key,
        unit: def.unit,
        points,
    })
}

fn history_metric_defs(group: HistoryMetricGroup) -> Vec<HistoryMetricDef> {
    match group {
        HistoryMetricGroup::Core => vec![
            history_metric_def(
                "market_price_usd",
                "Market price",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::MarketPrice,
            ),
            history_metric_def(
                "intrinsic_value_usd",
                "Intrinsic value",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::IntrinsicValue,
            ),
            history_metric_def(
                "gap_pct",
                "Gap",
                None,
                HistoryUnit::Percent,
                HistoryValueExtractor::GapPct,
            ),
            history_metric_def(
                "external_gap_pct",
                "External gap",
                None,
                HistoryUnit::Percent,
                HistoryValueExtractor::ExternalGapPct,
            ),
            history_metric_def(
                "weighted_gap_pct",
                "Weighted gap",
                None,
                HistoryUnit::Percent,
                HistoryValueExtractor::WeightedGapPct,
            ),
            history_metric_def(
                "analyst_count",
                "Analyst count",
                None,
                HistoryUnit::Count,
                HistoryValueExtractor::AnalystCount,
            ),
            history_metric_def(
                "confidence_rank_count",
                "Confidence rank",
                None,
                HistoryUnit::Count,
                HistoryValueExtractor::ConfidenceRank,
            ),
            history_metric_def(
                "qualification_rank_count",
                "Qualification rank",
                None,
                HistoryUnit::Count,
                HistoryValueExtractor::QualificationRank,
            ),
            history_metric_def(
                "external_status_rank_count",
                "External status rank",
                None,
                HistoryUnit::Count,
                HistoryValueExtractor::ExternalStatusRank,
            ),
            history_metric_def(
                "watched_count",
                "Watched",
                None,
                HistoryUnit::Count,
                HistoryValueExtractor::WatchedCount,
            ),
            history_metric_def(
                "profitable_count",
                "Profitable",
                None,
                HistoryUnit::Count,
                HistoryValueExtractor::ProfitableCount,
            ),
        ],
        HistoryMetricGroup::Fundamentals => vec![
            history_metric_def(
                "market_cap_usd",
                "Market cap",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::MarketCapUsd,
            ),
            history_metric_def(
                "shares_outstanding_count",
                "Shares",
                None,
                HistoryUnit::Count,
                HistoryValueExtractor::SharesOutstanding,
            ),
            history_metric_def(
                "trailing_pe_ratio",
                "Trailing P/E",
                None,
                HistoryUnit::Ratio,
                HistoryValueExtractor::TrailingPe,
            ),
            history_metric_def(
                "forward_pe_ratio",
                "Forward P/E",
                None,
                HistoryUnit::Ratio,
                HistoryValueExtractor::ForwardPe,
            ),
            history_metric_def(
                "price_to_book_ratio",
                "P/B",
                None,
                HistoryUnit::Ratio,
                HistoryValueExtractor::PriceToBook,
            ),
            history_metric_def(
                "return_on_equity_pct",
                "ROE",
                None,
                HistoryUnit::Percent,
                HistoryValueExtractor::ReturnOnEquityPct,
            ),
            history_metric_def(
                "ebitda_usd",
                "EBITDA",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::EbitdaUsd,
            ),
            history_metric_def(
                "enterprise_value_usd",
                "Enterprise value",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::EnterpriseValueUsd,
            ),
            history_metric_def(
                "enterprise_to_ebitda_ratio",
                "EV/EBITDA",
                None,
                HistoryUnit::Ratio,
                HistoryValueExtractor::EnterpriseToEbitda,
            ),
            history_metric_def(
                "total_debt_usd",
                "Total debt",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::TotalDebtUsd,
            ),
            history_metric_def(
                "total_cash_usd",
                "Total cash",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::TotalCashUsd,
            ),
            history_metric_def(
                "debt_to_equity_ratio",
                "Debt/Equity",
                None,
                HistoryUnit::Ratio,
                HistoryValueExtractor::DebtToEquity,
            ),
            history_metric_def(
                "free_cash_flow_usd",
                "Free cash flow",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::FreeCashFlowUsd,
            ),
            history_metric_def(
                "operating_cash_flow_usd",
                "Operating cash flow",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::OperatingCashFlowUsd,
            ),
            history_metric_def(
                "beta_ratio",
                "Beta",
                None,
                HistoryUnit::Ratio,
                HistoryValueExtractor::Beta,
            ),
            history_metric_def(
                "trailing_eps_usd",
                "EPS",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::TrailingEpsUsd,
            ),
            history_metric_def(
                "earnings_growth_pct",
                "Earnings growth",
                None,
                HistoryUnit::Percent,
                HistoryValueExtractor::EarningsGrowthPct,
            ),
        ],
        HistoryMetricGroup::Relative => {
            let mut defs = vec![
                history_metric_def(
                    "composite_percentile_pct",
                    "Composite percentile",
                    None,
                    HistoryUnit::Percent,
                    HistoryValueExtractor::RelativeCompositePercentile,
                ),
                history_metric_def(
                    "peer_count",
                    "Peer count",
                    None,
                    HistoryUnit::Count,
                    HistoryValueExtractor::RelativePeerCount,
                ),
            ];
            for (key, label) in [
                ("relative_pe_percentile_pct", "P/E"),
                ("relative_peg_percentile_pct", "PEG"),
                ("relative_roe_percentile_pct", "ROE"),
                ("relative_net_debt_ebitda_percentile_pct", "Net debt/EBITDA"),
                ("relative_fcf_yield_percentile_pct", "FCF yield"),
            ] {
                defs.push(history_metric_def(
                    key,
                    label,
                    None,
                    HistoryUnit::Percent,
                    HistoryValueExtractor::RelativeMetricPercentile(label),
                ));
            }
            defs
        }
        HistoryMetricGroup::Dcf => vec![
            history_metric_def(
                "bear_intrinsic_usd",
                "Bear intrinsic",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::DcfBearIntrinsicUsd,
            ),
            history_metric_def(
                "base_intrinsic_usd",
                "Base intrinsic",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::DcfBaseIntrinsicUsd,
            ),
            history_metric_def(
                "bull_intrinsic_usd",
                "Bull intrinsic",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::DcfBullIntrinsicUsd,
            ),
            history_metric_def(
                "wacc_pct",
                "WACC",
                None,
                HistoryUnit::Percent,
                HistoryValueExtractor::DcfWaccPct,
            ),
            history_metric_def(
                "base_growth_pct",
                "Base growth",
                None,
                HistoryUnit::Percent,
                HistoryValueExtractor::DcfBaseGrowthPct,
            ),
            history_metric_def(
                "net_debt_usd",
                "Net debt",
                None,
                HistoryUnit::Usd,
                HistoryValueExtractor::DcfNetDebtUsd,
            ),
            history_metric_def(
                "margin_of_safety_pct",
                "Margin of safety",
                None,
                HistoryUnit::Percent,
                HistoryValueExtractor::DcfMarginSafetyPct,
            ),
            history_metric_def(
                "signal_rank_count",
                "Signal rank",
                None,
                HistoryUnit::Count,
                HistoryValueExtractor::DcfSignalRank,
            ),
        ],
        HistoryMetricGroup::Chart => chart_ranges()
            .iter()
            .flat_map(|range| {
                let prefix = chart_range_label(*range);
                let range_key = chart_range_export_prefix(*range);
                [
                    history_metric_def(
                        chart_metric_key(*range, ChartMetricKind::Close),
                        format!("{prefix} Close"),
                        Some(range_key),
                        HistoryUnit::Usd,
                        HistoryValueExtractor::ChartMetric(*range, ChartMetricKind::Close),
                    ),
                    history_metric_def(
                        chart_metric_key(*range, ChartMetricKind::Ema20),
                        format!("{prefix} EMA20"),
                        Some(range_key),
                        HistoryUnit::Usd,
                        HistoryValueExtractor::ChartMetric(*range, ChartMetricKind::Ema20),
                    ),
                    history_metric_def(
                        chart_metric_key(*range, ChartMetricKind::Ema50),
                        format!("{prefix} EMA50"),
                        Some(range_key),
                        HistoryUnit::Usd,
                        HistoryValueExtractor::ChartMetric(*range, ChartMetricKind::Ema50),
                    ),
                    history_metric_def(
                        chart_metric_key(*range, ChartMetricKind::Ema200),
                        format!("{prefix} EMA200"),
                        Some(range_key),
                        HistoryUnit::Usd,
                        HistoryValueExtractor::ChartMetric(*range, ChartMetricKind::Ema200),
                    ),
                    history_metric_def(
                        chart_metric_key(*range, ChartMetricKind::Macd),
                        format!("{prefix} MACD"),
                        Some(range_key),
                        HistoryUnit::Usd,
                        HistoryValueExtractor::ChartMetric(*range, ChartMetricKind::Macd),
                    ),
                    history_metric_def(
                        chart_metric_key(*range, ChartMetricKind::Signal),
                        format!("{prefix} Signal"),
                        Some(range_key),
                        HistoryUnit::Usd,
                        HistoryValueExtractor::ChartMetric(*range, ChartMetricKind::Signal),
                    ),
                    history_metric_def(
                        chart_metric_key(*range, ChartMetricKind::Histogram),
                        format!("{prefix} Histogram"),
                        Some(range_key),
                        HistoryUnit::Usd,
                        HistoryValueExtractor::ChartMetric(*range, ChartMetricKind::Histogram),
                    ),
                ]
            })
            .collect(),
    }
}

fn history_metric_def(
    key: &'static str,
    label: impl Into<String>,
    range_key: Option<&'static str>,
    unit: HistoryUnit,
    extractor: HistoryValueExtractor,
) -> HistoryMetricDef {
    HistoryMetricDef {
        key,
        label: label.into(),
        range_key,
        unit,
        extractor,
    }
}

fn metric_group_status(
    payload: &persistence::EvaluatedSymbolState,
    group: HistoryMetricGroup,
) -> &persistence::MetricGroupStatus {
    match group {
        HistoryMetricGroup::Core => &payload.core_status,
        HistoryMetricGroup::Fundamentals => &payload.fundamentals_status,
        HistoryMetricGroup::Relative => &payload.relative_status,
        HistoryMetricGroup::Dcf => &payload.dcf_status,
        HistoryMetricGroup::Chart => &payload.chart_status,
    }
}

impl HistoryValueExtractor {
    fn extract(&self, record: &persistence::PersistedRevisionRecord) -> Option<f64> {
        match self {
            HistoryValueExtractor::MarketPrice => record
                .payload
                .snapshot
                .as_ref()
                .map(|value| cents_to_dollars(value.market_price_cents)),
            HistoryValueExtractor::IntrinsicValue => record
                .payload
                .snapshot
                .as_ref()
                .map(|value| cents_to_dollars(value.intrinsic_value_cents)),
            HistoryValueExtractor::GapPct => record.payload.gap_bps.map(bps_to_percent),
            HistoryValueExtractor::ExternalGapPct => {
                record.payload.external_gap_bps.map(bps_to_percent)
            }
            HistoryValueExtractor::WeightedGapPct => {
                record.payload.weighted_gap_bps.map(bps_to_percent)
            }
            HistoryValueExtractor::AnalystCount => record
                .payload
                .external_signal
                .as_ref()
                .and_then(|value| value.analyst_opinion_count)
                .map(|value| value as f64),
            HistoryValueExtractor::ConfidenceRank => {
                record.payload.confidence.map(confidence_rank_value)
            }
            HistoryValueExtractor::QualificationRank => {
                record.payload.qualification.map(qualification_rank_value)
            }
            HistoryValueExtractor::ExternalStatusRank => record
                .payload
                .external_status
                .map(external_status_rank_value),
            HistoryValueExtractor::WatchedCount => {
                Some(if record.payload.is_watched { 1.0 } else { 0.0 })
            }
            HistoryValueExtractor::ProfitableCount => record
                .payload
                .snapshot
                .as_ref()
                .map(|value| if value.profitable { 1.0 } else { 0.0 }),
            HistoryValueExtractor::MarketCapUsd => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.market_cap_dollars)
                .map(|value| value as f64),
            HistoryValueExtractor::SharesOutstanding => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.shares_outstanding)
                .map(|value| value as f64),
            HistoryValueExtractor::TrailingPe => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.trailing_pe_hundredths)
                .map(hundredths_to_ratio),
            HistoryValueExtractor::ForwardPe => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.forward_pe_hundredths)
                .map(hundredths_to_ratio),
            HistoryValueExtractor::PriceToBook => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.price_to_book_hundredths)
                .map(hundredths_to_ratio),
            HistoryValueExtractor::ReturnOnEquityPct => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.return_on_equity_bps)
                .map(bps_to_percent),
            HistoryValueExtractor::EbitdaUsd => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.ebitda_dollars)
                .map(|value| value as f64),
            HistoryValueExtractor::EnterpriseValueUsd => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.enterprise_value_dollars)
                .map(|value| value as f64),
            HistoryValueExtractor::EnterpriseToEbitda => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.enterprise_to_ebitda_hundredths)
                .map(hundredths_to_ratio),
            HistoryValueExtractor::TotalDebtUsd => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.total_debt_dollars)
                .map(|value| value as f64),
            HistoryValueExtractor::TotalCashUsd => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.total_cash_dollars)
                .map(|value| value as f64),
            HistoryValueExtractor::DebtToEquity => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.debt_to_equity_hundredths)
                .map(hundredths_to_ratio),
            HistoryValueExtractor::FreeCashFlowUsd => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.free_cash_flow_dollars)
                .map(|value| value as f64),
            HistoryValueExtractor::OperatingCashFlowUsd => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.operating_cash_flow_dollars)
                .map(|value| value as f64),
            HistoryValueExtractor::Beta => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.beta_millis)
                .map(|value| value as f64 / 1_000.0),
            HistoryValueExtractor::TrailingEpsUsd => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.trailing_eps_cents)
                .map(cents_to_dollars),
            HistoryValueExtractor::EarningsGrowthPct => record
                .payload
                .fundamentals
                .as_ref()
                .and_then(|value| value.earnings_growth_bps)
                .map(bps_to_percent),
            HistoryValueExtractor::RelativeCompositePercentile => record
                .payload
                .relative_score
                .as_ref()
                .map(|value| value.composite_percentile as f64),
            HistoryValueExtractor::RelativePeerCount => record
                .payload
                .relative_score
                .as_ref()
                .map(|value| value.peer_count as f64),
            HistoryValueExtractor::RelativeMetricPercentile(label) => record
                .payload
                .relative_score
                .as_ref()
                .and_then(|value| value.metrics.iter().find(|metric| metric.label == *label))
                .map(|metric| metric.percentile as f64),
            HistoryValueExtractor::DcfBearIntrinsicUsd => record
                .payload
                .dcf_analysis
                .as_ref()
                .map(|value| cents_to_dollars(value.bear_intrinsic_value_cents)),
            HistoryValueExtractor::DcfBaseIntrinsicUsd => record
                .payload
                .dcf_analysis
                .as_ref()
                .map(|value| cents_to_dollars(value.base_intrinsic_value_cents)),
            HistoryValueExtractor::DcfBullIntrinsicUsd => record
                .payload
                .dcf_analysis
                .as_ref()
                .map(|value| cents_to_dollars(value.bull_intrinsic_value_cents)),
            HistoryValueExtractor::DcfWaccPct => record
                .payload
                .dcf_analysis
                .as_ref()
                .map(|value| bps_to_percent(value.wacc_bps)),
            HistoryValueExtractor::DcfBaseGrowthPct => record
                .payload
                .dcf_analysis
                .as_ref()
                .map(|value| bps_to_percent(value.base_growth_bps)),
            HistoryValueExtractor::DcfNetDebtUsd => record
                .payload
                .dcf_analysis
                .as_ref()
                .map(|value| value.net_debt_dollars as f64),
            HistoryValueExtractor::DcfMarginSafetyPct => {
                record.payload.dcf_margin_of_safety_bps.map(bps_to_percent)
            }
            HistoryValueExtractor::DcfSignalRank => {
                record.payload.dcf_signal.map(dcf_signal_rank_value)
            }
            HistoryValueExtractor::ChartMetric(range, kind) => record
                .payload
                .chart_summaries
                .iter()
                .find(|summary| summary.range == *range)
                .and_then(|summary| chart_metric_value(summary, *kind)),
        }
    }
}

fn history_metric_row_from_series(series: &HistorySeries) -> Option<HistoryMetricRow> {
    let latest = series.points.last()?;
    let previous = series.points.iter().rev().nth(1);
    Some(HistoryMetricRow {
        label: series.label.clone(),
        latest: format_history_value(latest.value, series.unit),
        previous: previous
            .map(|point| format_history_value(point.value, series.unit))
            .unwrap_or_else(|| "n/a".to_string()),
        delta: previous
            .map(|point| format_history_delta(latest.value - point.value, series.unit))
            .unwrap_or_else(|| "n/a".to_string()),
        sparkline: sparkline(
            &series
                .points
                .iter()
                .map(|point| point.value)
                .collect::<Vec<_>>(),
        ),
    })
}

fn history_graph_tile_from_series(series: &HistorySeries) -> Option<HistoryGraphTile> {
    let latest = series.points.last()?;
    let previous = series.points.iter().rev().nth(1);
    let values = series
        .points
        .iter()
        .map(|point| point.value)
        .collect::<Vec<_>>();
    let min = values
        .iter()
        .fold(f64::INFINITY, |left, right| left.min(*right));
    let max = values
        .iter()
        .fold(f64::NEG_INFINITY, |left, right| left.max(*right));

    Some(HistoryGraphTile {
        label: series.label.clone(),
        latest: format_history_value(latest.value, series.unit),
        previous: previous
            .map(|point| format_history_value(point.value, series.unit))
            .unwrap_or_else(|| "n/a".to_string()),
        delta: previous
            .map(|point| format_history_delta(latest.value - point.value, series.unit))
            .unwrap_or_else(|| "n/a".to_string()),
        points: values,
        min_label: if min.is_finite() {
            format_history_value(min, series.unit)
        } else {
            "n/a".to_string()
        },
        max_label: if max.is_finite() {
            format_history_value(max, series.unit)
        } else {
            "n/a".to_string()
        },
        footer_lines: vec![
            clip_plain_text(
                &format!(
                    "Points {}  rev {}  {}{}{}",
                    series.points.len(),
                    latest.revision_id,
                    format_timestamp_utc(latest.evaluated_at),
                    if latest.stale { "  stale" } else { "" },
                    if latest.available {
                        ""
                    } else {
                        "  unavailable"
                    }
                ),
                72,
            ),
            clip_plain_text(
                &format!(
                    "Metric {}{}  group {}",
                    series.metric_key,
                    series
                        .range_key
                        .map(|value| format!("  range {value}"))
                        .unwrap_or_default(),
                    history_group_label(series.group)
                ),
                72,
            ),
        ],
    })
}

fn empty_history_graph_tile(label: String) -> HistoryGraphTile {
    HistoryGraphTile {
        label,
        latest: "n/a".to_string(),
        previous: "n/a".to_string(),
        delta: "n/a".to_string(),
        points: Vec::new(),
        min_label: "n/a".to_string(),
        max_label: "n/a".to_string(),
        footer_lines: vec!["No series points available yet.".to_string()],
    }
}

fn render_history_graph_tile(tile: &HistoryGraphTile, width: usize) -> Vec<String> {
    let inner_width = width.max(24);
    let chart_rows = render_ascii_line_chart(&tile.points, inner_width.max(8), 4);
    let mut lines = vec![
        clip_plain_text(&tile.label, inner_width),
        clip_plain_text(
            &format!("L {}  P {}  D {}", tile.latest, tile.previous, tile.delta),
            inner_width,
        ),
        clip_plain_text(&format!("max {}", tile.max_label), inner_width),
    ];
    lines.extend(
        chart_rows
            .into_iter()
            .map(|row| clip_plain_text(&row, inner_width)),
    );
    lines.push(clip_plain_text(
        &format!("min {}", tile.min_label),
        inner_width,
    ));
    lines.push(clip_plain_text(
        tile.footer_lines.first().map(String::as_str).unwrap_or(""),
        inner_width,
    ));
    lines.push(clip_plain_text(
        tile.footer_lines.get(1).map(String::as_str).unwrap_or(""),
        inner_width,
    ));
    while lines.len() < 9 {
        lines.push(String::new());
    }
    lines
}

fn blank_tile_lines(width: usize, height: usize) -> Vec<String> {
    vec![" ".repeat(width); height]
}

fn render_ascii_line_chart(values: &[f64], width: usize, height: usize) -> Vec<String> {
    if values.is_empty() {
        return vec!["(no data)".to_string(); height.max(1)];
    }

    let sampled = sample_series(values, width.max(1));
    let min = sampled
        .iter()
        .fold(f64::INFINITY, |left, right| left.min(*right));
    let max = sampled
        .iter()
        .fold(f64::NEG_INFINITY, |left, right| left.max(*right));
    let scale = (max - min).max(1.0);
    let mut grid = vec![vec![' '; sampled.len()]; height.max(1)];

    for (x, value) in sampled.iter().enumerate() {
        let normalized = ((*value - min) / scale).clamp(0.0, 1.0);
        let row = ((1.0 - normalized) * (height.saturating_sub(1) as f64)).round() as usize;
        let row = row.min(height.saturating_sub(1));
        grid[row][x] = '*';

        if x > 0 {
            let prev_value = sampled[x - 1];
            let prev_normalized = ((prev_value - min) / scale).clamp(0.0, 1.0);
            let prev_row =
                ((1.0 - prev_normalized) * (height.saturating_sub(1) as f64)).round() as usize;
            let low = row.min(prev_row);
            let high = row.max(prev_row);
            for row_index in low..=high {
                if grid[row_index][x] == ' ' {
                    grid[row_index][x] = '|';
                }
            }
        }
    }

    grid.into_iter()
        .map(|row| row.into_iter().collect::<String>())
        .collect()
}

fn sample_series(values: &[f64], width: usize) -> Vec<f64> {
    if values.len() <= width {
        return values.to_vec();
    }

    (0..width)
        .map(|index| {
            let start = index * values.len() / width;
            let end = ((index + 1) * values.len() / width).max(start + 1);
            let slice = &values[start..end.min(values.len())];
            slice.iter().copied().sum::<f64>() / slice.len() as f64
        })
        .collect()
}

fn filter_history_window(
    history: &[persistence::PersistedRevisionRecord],
    window: HistoryWindow,
) -> Vec<&persistence::PersistedRevisionRecord> {
    let Some(cutoff) = history_window_cutoff(window) else {
        return history.iter().collect();
    };
    history
        .iter()
        .filter(|record| record.evaluated_at >= cutoff)
        .collect()
}

fn history_window_cutoff(window: HistoryWindow) -> Option<u64> {
    let seconds = match window {
        HistoryWindow::Day => 24 * 60 * 60,
        HistoryWindow::Week => 7 * 24 * 60 * 60,
        HistoryWindow::Month => 30 * 24 * 60 * 60,
        HistoryWindow::Quarter => 90 * 24 * 60 * 60,
        HistoryWindow::Year => 365 * 24 * 60 * 60,
        HistoryWindow::All => return None,
    };
    Some(unix_timestamp_seconds().saturating_sub(seconds))
}

fn sparkline(values: &[f64]) -> String {
    if values.is_empty() {
        return String::new();
    }
    const BLOCKS: &[char] = &[' ', '.', ':', '-', '=', '+', '*', '#', '%', '@'];
    let min = values
        .iter()
        .fold(f64::INFINITY, |left, right| left.min(*right));
    let max = values
        .iter()
        .fold(f64::NEG_INFINITY, |left, right| left.max(*right));
    if !min.is_finite() || !max.is_finite() {
        return String::new();
    }
    let scale = (max - min).max(1.0);
    values
        .iter()
        .map(|value| {
            let normalized = ((*value - min) / scale).clamp(0.0, 0.9999);
            let index = (normalized * BLOCKS.len() as f64).floor() as usize;
            BLOCKS[index.min(BLOCKS.len().saturating_sub(1))]
        })
        .collect()
}

fn clip_plain_text(text: &str, width: usize) -> String {
    if text.len() <= width {
        return text.to_string();
    }
    let clip_width = width.saturating_sub(3);
    text.chars().take(clip_width).collect::<String>() + "..."
}

fn format_history_value(value: f64, unit: HistoryUnit) -> String {
    match unit {
        HistoryUnit::Usd => format_history_money(value),
        HistoryUnit::Percent => format_history_percent(value),
        HistoryUnit::Count => format_history_count(value),
        HistoryUnit::Ratio => format_history_decimal_2(value),
    }
}

fn format_history_delta(value: f64, unit: HistoryUnit) -> String {
    match unit {
        HistoryUnit::Usd => format!("{:+.2}", value),
        HistoryUnit::Percent => format!("{:+.2}%", value),
        HistoryUnit::Count => format!("{:+.0}", value),
        HistoryUnit::Ratio => format!("{:+.2}", value),
    }
}

fn format_history_money(value: f64) -> String {
    format!("${value:.2}")
}

fn format_history_percent(value: f64) -> String {
    format!("{value:.2}%")
}

fn format_history_count(value: f64) -> String {
    format!("{}", value.round() as i64)
}

fn format_history_decimal_2(value: f64) -> String {
    format!("{value:.2}")
}

fn confidence_rank_value(confidence: ConfidenceBand) -> f64 {
    match confidence {
        ConfidenceBand::Low => 0.0,
        ConfidenceBand::Provisional => 1.0,
        ConfidenceBand::High => 2.0,
    }
}

fn qualification_rank_value(status: QualificationStatus) -> f64 {
    match status {
        QualificationStatus::Qualified => 2.0,
        QualificationStatus::GapTooSmall => 1.0,
        QualificationStatus::Unprofitable => 0.0,
    }
}

fn external_status_rank_value(status: ExternalSignalStatus) -> f64 {
    match status {
        ExternalSignalStatus::Supportive => 3.0,
        ExternalSignalStatus::Stale => 2.0,
        ExternalSignalStatus::Divergent => 1.0,
        ExternalSignalStatus::Missing => 0.0,
    }
}

fn dcf_signal_rank_value(signal: DcfSignal) -> f64 {
    match signal {
        DcfSignal::Opportunity => 2.0,
        DcfSignal::Fair => 1.0,
        DcfSignal::Expensive => 0.0,
    }
}

fn cents_to_dollars(value: i64) -> f64 {
    value as f64 / 100.0
}

fn bps_to_percent<T>(value: T) -> f64
where
    T: Into<f64>,
{
    value.into() / 100.0
}

fn hundredths_to_ratio<T>(value: T) -> f64
where
    T: Into<f64>,
{
    value.into() / 100.0
}

fn chart_metric_value(summary: &ChartRangeSummary, kind: ChartMetricKind) -> Option<f64> {
    match kind {
        ChartMetricKind::Close => summary.latest_close_cents.map(cents_to_dollars),
        ChartMetricKind::Ema20 => summary.ema20_cents.map(cents_to_dollars),
        ChartMetricKind::Ema50 => summary.ema50_cents.map(cents_to_dollars),
        ChartMetricKind::Ema200 => summary.ema200_cents.map(cents_to_dollars),
        ChartMetricKind::Macd => summary.macd_cents.map(cents_to_dollars),
        ChartMetricKind::Signal => summary.signal_cents.map(cents_to_dollars),
        ChartMetricKind::Histogram => summary.histogram_cents.map(cents_to_dollars),
    }
}

fn chart_metric_key(range: ChartRange, kind: ChartMetricKind) -> &'static str {
    match (range, kind) {
        (ChartRange::Day, ChartMetricKind::Close) => "d_close_usd",
        (ChartRange::Day, ChartMetricKind::Ema20) => "d_ema20_usd",
        (ChartRange::Day, ChartMetricKind::Ema50) => "d_ema50_usd",
        (ChartRange::Day, ChartMetricKind::Ema200) => "d_ema200_usd",
        (ChartRange::Day, ChartMetricKind::Macd) => "d_macd_usd",
        (ChartRange::Day, ChartMetricKind::Signal) => "d_signal_usd",
        (ChartRange::Day, ChartMetricKind::Histogram) => "d_histogram_usd",
        (ChartRange::Week, ChartMetricKind::Close) => "w_close_usd",
        (ChartRange::Week, ChartMetricKind::Ema20) => "w_ema20_usd",
        (ChartRange::Week, ChartMetricKind::Ema50) => "w_ema50_usd",
        (ChartRange::Week, ChartMetricKind::Ema200) => "w_ema200_usd",
        (ChartRange::Week, ChartMetricKind::Macd) => "w_macd_usd",
        (ChartRange::Week, ChartMetricKind::Signal) => "w_signal_usd",
        (ChartRange::Week, ChartMetricKind::Histogram) => "w_histogram_usd",
        (ChartRange::Month, ChartMetricKind::Close) => "m_close_usd",
        (ChartRange::Month, ChartMetricKind::Ema20) => "m_ema20_usd",
        (ChartRange::Month, ChartMetricKind::Ema50) => "m_ema50_usd",
        (ChartRange::Month, ChartMetricKind::Ema200) => "m_ema200_usd",
        (ChartRange::Month, ChartMetricKind::Macd) => "m_macd_usd",
        (ChartRange::Month, ChartMetricKind::Signal) => "m_signal_usd",
        (ChartRange::Month, ChartMetricKind::Histogram) => "m_histogram_usd",
        (ChartRange::Year, ChartMetricKind::Close) => "y1_close_usd",
        (ChartRange::Year, ChartMetricKind::Ema20) => "y1_ema20_usd",
        (ChartRange::Year, ChartMetricKind::Ema50) => "y1_ema50_usd",
        (ChartRange::Year, ChartMetricKind::Ema200) => "y1_ema200_usd",
        (ChartRange::Year, ChartMetricKind::Macd) => "y1_macd_usd",
        (ChartRange::Year, ChartMetricKind::Signal) => "y1_signal_usd",
        (ChartRange::Year, ChartMetricKind::Histogram) => "y1_histogram_usd",
        (ChartRange::FiveYears, ChartMetricKind::Close) => "y5_close_usd",
        (ChartRange::FiveYears, ChartMetricKind::Ema20) => "y5_ema20_usd",
        (ChartRange::FiveYears, ChartMetricKind::Ema50) => "y5_ema50_usd",
        (ChartRange::FiveYears, ChartMetricKind::Ema200) => "y5_ema200_usd",
        (ChartRange::FiveYears, ChartMetricKind::Macd) => "y5_macd_usd",
        (ChartRange::FiveYears, ChartMetricKind::Signal) => "y5_signal_usd",
        (ChartRange::FiveYears, ChartMetricKind::Histogram) => "y5_histogram_usd",
        (ChartRange::TenYears, ChartMetricKind::Close) => "y10_close_usd",
        (ChartRange::TenYears, ChartMetricKind::Ema20) => "y10_ema20_usd",
        (ChartRange::TenYears, ChartMetricKind::Ema50) => "y10_ema50_usd",
        (ChartRange::TenYears, ChartMetricKind::Ema200) => "y10_ema200_usd",
        (ChartRange::TenYears, ChartMetricKind::Macd) => "y10_macd_usd",
        (ChartRange::TenYears, ChartMetricKind::Signal) => "y10_signal_usd",
        (ChartRange::TenYears, ChartMetricKind::Histogram) => "y10_histogram_usd",
    }
}

fn chart_range_export_prefix(range: ChartRange) -> &'static str {
    match range {
        ChartRange::Day => "d",
        ChartRange::Week => "w",
        ChartRange::Month => "m",
        ChartRange::Year => "y1",
        ChartRange::FiveYears => "y5",
        ChartRange::TenYears => "y10",
    }
}

fn format_optional_history_money(value: Option<f64>) -> String {
    value
        .map(format_history_money)
        .unwrap_or_else(|| "n/a".to_string())
}

fn format_timestamp_utc(epoch_seconds: u64) -> String {
    let (year, month, day, hour, minute, second) = timestamp_utc_parts(epoch_seconds);
    format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}Z")
}

fn format_timestamp_compact_utc(epoch_seconds: u64) -> String {
    let (year, month, day, hour, minute, second) = timestamp_utc_parts(epoch_seconds);
    format!("{year:04}{month:02}{day:02}_{hour:02}{minute:02}{second:02}Z")
}

fn timestamp_utc_parts(epoch_seconds: u64) -> (i32, u32, u32, u32, u32, u32) {
    let days = (epoch_seconds / 86_400) as i64;
    let seconds_of_day = (epoch_seconds % 86_400) as u32;
    let hour = seconds_of_day / 3_600;
    let minute = (seconds_of_day % 3_600) / 60;
    let second = seconds_of_day % 60;
    let (year, month, day) = civil_from_days(days);
    (year, month, day, hour, minute, second)
}

fn civil_from_days(days_since_unix_epoch: i64) -> (i32, u32, u32) {
    let z = days_since_unix_epoch + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let mut year = (yoe + era * 400) as i32;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = (mp + if mp < 10 { 3 } else { -9 }) as u32;
    year += if month <= 2 { 1 } else { 0 };
    (year, month, day)
}

fn export_selected_history_bundle(
    app: &mut AppState,
    persistence_handle: Option<&PersistenceHandle>,
) -> io::Result<CsvExportMetadata> {
    let Some(symbol) = app.detail_symbol().map(str::to_string) else {
        return Err(io::Error::other("no symbol is selected for history export"));
    };

    if !app.history_cache.contains_key(&symbol) {
        let Some(persistence_handle) = persistence_handle else {
            return Err(io::Error::other(
                "no canonical revision history is loaded for the selected symbol",
            ));
        };
        let history = persistence_handle.load_symbol_history(symbol.clone())?;
        app.history_cache.insert(symbol.clone(), history);
    }

    let history = app.detail_history(&symbol).to_vec();
    if history.is_empty() {
        return Err(io::Error::other(
            "no canonical revision history is loaded for the selected symbol",
        ));
    }

    let exported_at = unix_timestamp_seconds();
    let export_root = app
        .history_export_root
        .clone()
        .or_else(|| std::env::current_dir().ok().map(|dir| dir.join("exports")))
        .unwrap_or_else(|| PathBuf::from("exports"));
    let export_dir = export_root
        .join(&symbol)
        .join(format_timestamp_compact_utc(exported_at));
    std::fs::create_dir_all(&export_dir)?;

    let metadata = CsvExportMetadata {
        symbol: symbol.clone(),
        exported_at,
        revision_count: history.len(),
        export_dir: export_dir.clone(),
    };

    write_export_metadata_csv(&metadata)?;
    write_group_wide_csv(
        &export_dir.join("core_wide.csv"),
        &symbol,
        &history,
        HistoryMetricGroup::Core,
    )?;
    write_group_wide_csv(
        &export_dir.join("fundamentals_wide.csv"),
        &symbol,
        &history,
        HistoryMetricGroup::Fundamentals,
    )?;
    write_group_wide_csv(
        &export_dir.join("relative_wide.csv"),
        &symbol,
        &history,
        HistoryMetricGroup::Relative,
    )?;
    write_group_wide_csv(
        &export_dir.join("dcf_wide.csv"),
        &symbol,
        &history,
        HistoryMetricGroup::Dcf,
    )?;
    write_group_wide_csv(
        &export_dir.join("chart_wide.csv"),
        &symbol,
        &history,
        HistoryMetricGroup::Chart,
    )?;
    write_all_tidy_csv(&export_dir.join("all_tidy.csv"), &symbol, &history)?;

    Ok(metadata)
}

fn write_export_metadata_csv(metadata: &CsvExportMetadata) -> io::Result<()> {
    write_csv_file(
        &metadata.export_dir.join("export_metadata.csv"),
        &["key", "value"],
        vec![
            vec!["symbol".to_string(), metadata.symbol.clone()],
            vec![
                "exported_at_utc".to_string(),
                format_timestamp_utc(metadata.exported_at),
            ],
            vec![
                "exported_at_epoch".to_string(),
                metadata.exported_at.to_string(),
            ],
            vec![
                "revision_count".to_string(),
                metadata.revision_count.to_string(),
            ],
            vec![
                "export_dir".to_string(),
                metadata.export_dir.display().to_string(),
            ],
        ],
    )
}

fn write_group_wide_csv(
    path: &Path,
    symbol: &str,
    history: &[persistence::PersistedRevisionRecord],
    group: HistoryMetricGroup,
) -> io::Result<()> {
    let defs = history_metric_defs(group);
    let mut headers = vec![
        "timestamp_utc".to_string(),
        "timestamp_epoch".to_string(),
        "revision_id".to_string(),
        "symbol".to_string(),
    ];
    headers.extend(defs.iter().map(|def| def.key.to_string()));

    let mut rows = Vec::new();
    for record in history {
        let mut row = vec![
            format_timestamp_utc(record.evaluated_at),
            record.evaluated_at.to_string(),
            record.revision_id.to_string(),
            symbol.to_string(),
        ];
        row.extend(defs.iter().map(|def| {
            def.extractor
                .extract(record)
                .map(csv_numeric_value)
                .unwrap_or_default()
        }));
        rows.push(row);
    }

    let header_refs = headers.iter().map(String::as_str).collect::<Vec<_>>();
    write_csv_file(path, &header_refs, rows)
}

fn write_all_tidy_csv(
    path: &Path,
    symbol: &str,
    history: &[persistence::PersistedRevisionRecord],
) -> io::Result<()> {
    let mut rows = Vec::new();
    for record in history {
        for group in [
            HistoryMetricGroup::Core,
            HistoryMetricGroup::Fundamentals,
            HistoryMetricGroup::Relative,
            HistoryMetricGroup::Dcf,
            HistoryMetricGroup::Chart,
        ] {
            let status = metric_group_status(&record.payload, group);
            for def in history_metric_defs(group) {
                if let Some(value) = def.extractor.extract(record) {
                    rows.push(vec![
                        format_timestamp_utc(record.evaluated_at),
                        record.evaluated_at.to_string(),
                        record.revision_id.to_string(),
                        symbol.to_string(),
                        history_group_label(group).to_ascii_lowercase(),
                        def.key.to_string(),
                        def.label,
                        def.range_key.unwrap_or("").to_string(),
                        history_unit_label(def.unit).to_string(),
                        csv_numeric_value(value),
                        if status.available { "true" } else { "false" }.to_string(),
                        if status.stale { "true" } else { "false" }.to_string(),
                    ]);
                }
            }
        }
    }

    write_csv_file(
        path,
        &[
            "timestamp_utc",
            "timestamp_epoch",
            "revision_id",
            "symbol",
            "group_key",
            "metric_key",
            "metric_label",
            "range_key",
            "unit",
            "value",
            "available",
            "stale",
        ],
        rows,
    )
}

fn history_unit_label(unit: HistoryUnit) -> &'static str {
    match unit {
        HistoryUnit::Usd => "usd",
        HistoryUnit::Percent => "pct",
        HistoryUnit::Count => "count",
        HistoryUnit::Ratio => "ratio",
    }
}

fn csv_numeric_value(value: f64) -> String {
    format!("{value:.6}")
}

fn write_csv_file(path: &Path, headers: &[&str], rows: Vec<Vec<String>>) -> io::Result<()> {
    let mut buffer = String::new();
    buffer.push_str(
        &headers
            .iter()
            .map(|header| csv_escape_field(header))
            .collect::<Vec<_>>()
            .join(","),
    );
    buffer.push('\n');
    for row in rows {
        buffer.push_str(
            &row.iter()
                .map(|value| csv_escape_field(value))
                .collect::<Vec<_>>()
                .join(","),
        );
        buffer.push('\n');
    }
    std::fs::write(path, buffer)
}

fn csv_escape_field(value: &str) -> String {
    if value.contains([',', '"', '\n']) {
        format!("\"{}\"", value.replace('"', "\"\""))
    } else {
        value.to_string()
    }
}

fn should_handle_key_event(key_event: &KeyEvent) -> bool {
    matches!(key_event.kind, KeyEventKind::Press | KeyEventKind::Repeat)
}

fn is_force_quit_key(key_event: &KeyEvent) -> bool {
    key_event.modifiers.contains(KeyModifiers::CONTROL)
        && matches!(key_event.code, KeyCode::Char('c') | KeyCode::Char('q'))
}

fn handle_overlay_key(
    app: &mut AppState,
    state: &mut TerminalState,
    key_event: &KeyEvent,
    chart_control_sender: Option<&mpsc::Sender<ChartControl>>,
    analysis_control_sender: Option<&mpsc::Sender<AnalysisControl>>,
    persistence_handle: Option<&PersistenceHandle>,
) -> io::Result<bool> {
    match &app.overlay_mode {
        OverlayMode::None => Ok(false),
        OverlayMode::IssueLog => {
            match key_event.code {
                KeyCode::Esc | KeyCode::Backspace | KeyCode::Char('l') => {
                    app.close_overlay();
                }
                KeyCode::Down | KeyCode::Char('j') => {
                    app.move_issue_log_selection(1);
                }
                KeyCode::Up | KeyCode::Char('k') => {
                    app.move_issue_log_selection(-1);
                }
                KeyCode::Char('c') => {
                    app.issue_center.clear_resolved();
                    app.clamp_issue_log_selection();
                    app.set_status_message("Cleared resolved issues from the log view.");
                }
                _ => {}
            }

            Ok(true)
        }
        OverlayMode::TickerDetail(_) => {
            match app.detail_tab {
                DetailTab::Snapshot => match key_event.code {
                    KeyCode::Esc | KeyCode::Backspace | KeyCode::Enter | KeyCode::Char('d') => {
                        app.close_overlay();
                    }
                    KeyCode::Char('h') => {
                        app.toggle_detail_tab();
                        app.load_detail_history(persistence_handle);
                    }
                    KeyCode::Char('l') => {
                        app.open_issue_log();
                    }
                    KeyCode::Down | KeyCode::Char('j') => {
                        let rows = filtered_symbol_rows(state, &app.view_filter);
                        app.move_ticker_detail_selection(&rows, 1);
                        app.queue_detail_chart_request(chart_control_sender);
                        app.queue_detail_analysis_request(state, analysis_control_sender);
                    }
                    KeyCode::Up | KeyCode::Char('k') => {
                        let rows = filtered_symbol_rows(state, &app.view_filter);
                        app.move_ticker_detail_selection(&rows, -1);
                        app.queue_detail_chart_request(chart_control_sender);
                        app.queue_detail_analysis_request(state, analysis_control_sender);
                    }
                    KeyCode::Char('1') => {
                        if app.set_detail_chart_range(ChartRange::Day) {
                            app.queue_detail_chart_request(chart_control_sender);
                        }
                    }
                    KeyCode::Char('2') => {
                        if app.set_detail_chart_range(ChartRange::Week) {
                            app.queue_detail_chart_request(chart_control_sender);
                        }
                    }
                    KeyCode::Char('3') => {
                        if app.set_detail_chart_range(ChartRange::Month) {
                            app.queue_detail_chart_request(chart_control_sender);
                        }
                    }
                    KeyCode::Char('4') => {
                        if app.set_detail_chart_range(ChartRange::Year) {
                            app.queue_detail_chart_request(chart_control_sender);
                        }
                    }
                    KeyCode::Char('5') => {
                        if app.set_detail_chart_range(ChartRange::FiveYears) {
                            app.queue_detail_chart_request(chart_control_sender);
                        }
                    }
                    KeyCode::Char('6') => {
                        if app.set_detail_chart_range(ChartRange::TenYears) {
                            app.queue_detail_chart_request(chart_control_sender);
                        }
                    }
                    KeyCode::Char('[') => {
                        if app.cycle_detail_chart_range(-1) {
                            app.queue_detail_chart_request(chart_control_sender);
                        }
                    }
                    KeyCode::Char(']') => {
                        if app.cycle_detail_chart_range(1) {
                            app.queue_detail_chart_request(chart_control_sender);
                        }
                    }
                    KeyCode::Char('w') => {
                        if let Some(symbol) = app.detail_symbol().map(str::to_string) {
                            state.toggle_watchlist(&symbol);
                            if let Some(persistence_handle) = persistence_handle {
                                persistence_handle.replace_watchlist(state.watchlist_symbols());
                            }
                        }
                    }
                    _ => {}
                },
                DetailTab::History => match key_event.code {
                    KeyCode::Esc | KeyCode::Backspace | KeyCode::Enter | KeyCode::Char('d') => {
                        app.close_overlay();
                    }
                    KeyCode::Char('h') => {
                        app.toggle_detail_tab();
                    }
                    KeyCode::Char('g') => app.toggle_history_subview(),
                    KeyCode::Char('1') => app.select_history_group(HistoryMetricGroup::Core),
                    KeyCode::Char('2') => {
                        app.select_history_group(HistoryMetricGroup::Fundamentals)
                    }
                    KeyCode::Char('3') => app.select_history_group(HistoryMetricGroup::Relative),
                    KeyCode::Char('4') => app.select_history_group(HistoryMetricGroup::Dcf),
                    KeyCode::Char('5') => app.select_history_group(HistoryMetricGroup::Chart),
                    KeyCode::Char('e') => {
                        match export_selected_history_bundle(app, persistence_handle) {
                            Ok(metadata) => {
                                app.issue_center.resolve(ISSUE_KEY_HISTORY_EXPORT);
                                app.set_status_message(format!(
                                    "Exported {} revisions for {} to {}.",
                                    metadata.revision_count,
                                    metadata.symbol,
                                    metadata.export_dir.display()
                                ));
                            }
                            Err(error) => {
                                app.issue_center.raise(
                                    ISSUE_KEY_HISTORY_EXPORT,
                                    IssueSource::Persistence,
                                    IssueSeverity::Warning,
                                    "History export failed",
                                    error.to_string(),
                                );
                                app.set_status_message(format!("History export failed: {}", error));
                            }
                        }
                    }
                    KeyCode::Char('[') => app.cycle_history_window(-1),
                    KeyCode::Char(']') => app.cycle_history_window(1),
                    KeyCode::Down | KeyCode::Char('j') => app.scroll_history(1),
                    KeyCode::Up | KeyCode::Char('k') => app.scroll_history(-1),
                    KeyCode::Char('n') => {
                        let rows = filtered_symbol_rows(state, &app.view_filter);
                        app.move_ticker_detail_selection(&rows, 1);
                        app.load_detail_history(persistence_handle);
                    }
                    KeyCode::Char('p') => {
                        let rows = filtered_symbol_rows(state, &app.view_filter);
                        app.move_ticker_detail_selection(&rows, -1);
                        app.load_detail_history(persistence_handle);
                    }
                    _ => {}
                },
            }

            Ok(true)
        }
    }
}

#[cfg(test)]
fn qualification_justification_lines(detail: &SymbolDetail) -> Vec<String> {
    let minimum_upside_label = format_upside_percent_from_gap_bps(detail.minimum_gap_bps);
    let actual_upside_label =
        format_upside_percent(detail.market_price_cents, detail.intrinsic_value_cents);
    let discount_cents = detail.intrinsic_value_cents - detail.market_price_cents;
    let result_line = match detail.qualification {
        QualificationStatus::Qualified => format!(
            "Result: qualified because profitable=yes and {} >= {}.",
            actual_upside_label, minimum_upside_label
        ),
        QualificationStatus::Unprofitable => format!(
            "Result: unqualified because profitable=no, even though the required upside is {}.",
            minimum_upside_label
        ),
        QualificationStatus::GapTooSmall => format!(
            "Result: unqualified because {} < {} despite profitable=yes.",
            actual_upside_label, minimum_upside_label
        ),
    };

    vec![
        format!(
            "Profitability gate: actual={}  required=yes",
            yes_no(detail.profitable)
        ),
        format!(
            "Internal upside: actual={}  required>={} ",
            actual_upside_label, minimum_upside_label
        )
        .trim_end()
        .to_string(),
        format!(
            "Internal discount: {} = {} - {}",
            format_money(discount_cents),
            format_money(detail.intrinsic_value_cents),
            format_money(detail.market_price_cents),
        ),
        result_line,
    ]
}

#[cfg(test)]
fn confidence_justification_lines(detail: &SymbolDetail) -> Vec<String> {
    let minimum_upside_label = format_upside_percent_from_gap_bps(detail.minimum_gap_bps);
    let external_fair_value_label = detail
        .external_signal_fair_value_cents
        .map(format_money)
        .unwrap_or_else(|| "n/a".to_string());
    let external_upside_label = detail
        .external_signal_fair_value_cents
        .map(|fair_value_cents| format_upside_percent(detail.market_price_cents, fair_value_cents))
        .unwrap_or_else(|| "n/a".to_string());
    let signal_age_label = detail
        .external_signal_age_seconds
        .map(|age_seconds| format!("{age_seconds}s"))
        .unwrap_or_else(|| "n/a".to_string());
    let result_line = match detail.confidence {
        ConfidenceBand::High => format!(
            "Result: high because internal qualification is {} and external status is supportive.",
            qualification_label(detail.qualification)
        ),
        ConfidenceBand::Provisional => {
            "Result: provisional because the ticker qualifies internally but has no fresh supportive external confirmation.".to_string()
        }
        ConfidenceBand::Low => match detail.external_status {
            ExternalSignalStatus::Stale => format!(
                "Result: low because the external signal age {} exceeds the freshness limit of {}s.",
                signal_age_label, detail.external_signal_max_age_seconds
            ),
            ExternalSignalStatus::Divergent => format!(
                "Result: low because the external upside {} does not support the required threshold of {}.",
                external_upside_label, minimum_upside_label
            ),
            ExternalSignalStatus::Missing => {
                "Result: low because the ticker does not currently satisfy the internal qualification rules and has no external support.".to_string()
            }
            ExternalSignalStatus::Supportive => {
                "Result: low because internal qualification is not currently met even though the external signal is supportive.".to_string()
            }
        },
    };

    vec![
        format!(
            "External status: {}",
            external_status_label(detail.external_status)
        ),
        format!(
            "External fair value: {}  external upside: {}  support threshold: >={}",
            external_fair_value_label, external_upside_label, minimum_upside_label
        ),
        format!(
            "External signal age: {}  freshness limit: <={}s",
            signal_age_label, detail.external_signal_max_age_seconds
        ),
        result_line,
    ]
}

fn format_optional_money(value_cents: Option<i64>) -> String {
    value_cents
        .map(format_money)
        .unwrap_or_else(|| "n/a".to_string())
}

fn format_optional_count(value: Option<u32>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "n/a".to_string())
}

#[cfg(test)]
fn format_optional_recommendation_mean(value_hundredths: Option<u16>) -> String {
    value_hundredths
        .map(format_recommendation_mean)
        .unwrap_or_else(|| "n/a".to_string())
}

fn format_recommendation_mean(value_hundredths: u16) -> String {
    format!("{}.{:02}", value_hundredths / 100, value_hundredths % 100)
}

fn target_range_line(detail: &SymbolDetail) -> Option<String> {
    let (Some(low_target_cents), Some(high_target_cents)) = (
        detail.external_signal_low_fair_value_cents,
        detail.external_signal_high_fair_value_cents,
    ) else {
        return None;
    };

    let mut markers = vec![
        ("P", detail.market_price_cents),
        ("M", detail.intrinsic_value_cents),
    ];

    if let Some(weighted_target_cents) = detail.weighted_external_signal_fair_value_cents {
        markers.push(("W", weighted_target_cents));
    }

    if let Some(median_target_cents) = detail.external_signal_fair_value_cents {
        markers.push(("D", median_target_cents));
    }

    Some(format!(
        "Target map: {}",
        unicode_target_map(
            low_target_cents,
            high_target_cents,
            &markers,
            TARGET_RANGE_BAR_WIDTH
        )
    ))
}

fn target_marker_legend_line(detail: &SymbolDetail) -> Option<String> {
    let (Some(low_target_cents), Some(high_target_cents)) = (
        detail.external_signal_low_fair_value_cents,
        detail.external_signal_high_fair_value_cents,
    ) else {
        return None;
    };

    let mut markers = vec![price_marker_label(
        detail.market_price_cents,
        low_target_cents,
        high_target_cents,
    )];

    if detail.weighted_external_signal_fair_value_cents.is_some() {
        markers.push("◆ weighted".to_string());
    }

    markers.push("▲ mean".to_string());

    if detail.external_signal_fair_value_cents.is_some() {
        markers.push("■ median".to_string());
    }

    Some(format!("Markers: {}", markers.join("  ")))
}

fn price_marker_label(price_cents: i64, low_target_cents: i64, high_target_cents: i64) -> String {
    if price_cents < low_target_cents {
        return "● price<low".to_string();
    }

    if price_cents > high_target_cents {
        return "● price>high".to_string();
    }

    "● price".to_string()
}

fn unicode_target_map(
    range_start: i64,
    range_end: i64,
    markers: &[(&str, i64)],
    width: usize,
) -> String {
    if width == 0 {
        return format!(
            "{} ││ {}",
            format_money(range_start),
            format_money(range_end)
        );
    }

    let mut slots = vec![String::from("─"); width];

    for (label, value) in markers {
        if *value < range_start || *value > range_end {
            continue;
        }

        let index = scaled_bar_index(*value, range_start, range_end, width);
        place_label(&mut slots, index, label);
    }

    format!(
        "{} │{}│ {}",
        format_money(range_start),
        slots.join(""),
        format_money(range_end),
    )
}

fn place_label(slots: &mut [String], index: usize, label: &str) {
    let slot = &mut slots[index];
    let glyph = marker_glyph(label);

    if slot == "─" {
        *slot = glyph.to_string();
        return;
    }

    if !slot.chars().any(|existing| existing == glyph) {
        slot.push(glyph);
    }
}

fn marker_glyph(label: &str) -> char {
    match label {
        "P" => '●',
        "W" => '◆',
        "M" => '▲',
        "D" => '■',
        _ => '•',
    }
}

fn scaled_bar_index(value: i64, range_start: i64, range_end: i64, width: usize) -> usize {
    if width <= 1 || range_end <= range_start {
        return 0;
    }

    let clamped_value = value.clamp(range_start, range_end) as f64;
    let ratio = (clamped_value - range_start as f64) / (range_end - range_start) as f64;

    (ratio * (width - 1) as f64).round() as usize
}

fn gap_meter(actual_gap_bps: i32, minimum_gap_bps: i32, width: usize) -> String {
    if width == 0 {
        return "[]".to_string();
    }

    let max_gap_bps = actual_gap_bps
        .max(minimum_gap_bps)
        .max(minimum_gap_bps * 2)
        .max(1);
    let threshold_index = scaled_gap_index(minimum_gap_bps, max_gap_bps, width);
    let actual_index = scaled_gap_index(actual_gap_bps.max(0).min(max_gap_bps), max_gap_bps, width);
    let mut slots = vec!['░'; width];

    for slot in slots.iter_mut().take(actual_index) {
        *slot = '█';
    }

    slots[threshold_index] = '│';
    slots[actual_index] = if actual_index == threshold_index {
        '◆'
    } else {
        '●'
    };

    format!("[{}]", slots.iter().collect::<String>())
}

fn scaled_gap_index(value_bps: i32, max_gap_bps: i32, width: usize) -> usize {
    if width <= 1 || max_gap_bps <= 0 {
        return 0;
    }

    let clamped_value = value_bps.clamp(0, max_gap_bps) as f64;
    let ratio = clamped_value / max_gap_bps as f64;

    (ratio * (width - 1) as f64).round() as usize
}

fn candidate_row_color(row: &CandidateRow, is_selected: bool) -> Color {
    if is_selected {
        return if row.is_qualified {
            Color::Cyan
        } else {
            Color::DarkCyan
        };
    }

    confidence_color(row.confidence)
}

fn status_summary_color(qualification: QualificationStatus, confidence: ConfidenceBand) -> Color {
    match qualification {
        QualificationStatus::Qualified => confidence_color(confidence),
        QualificationStatus::GapTooSmall => Color::Yellow,
        QualificationStatus::Unprofitable => Color::Red,
    }
}

fn confidence_color(confidence: ConfidenceBand) -> Color {
    match confidence {
        ConfidenceBand::Low => Color::Red,
        ConfidenceBand::Provisional => Color::Yellow,
        ConfidenceBand::High => Color::Green,
    }
}

fn external_status_color(status: ExternalSignalStatus) -> Color {
    match status {
        ExternalSignalStatus::Missing => Color::DarkGrey,
        ExternalSignalStatus::Stale => Color::Yellow,
        ExternalSignalStatus::Supportive => Color::Green,
        ExternalSignalStatus::Divergent => Color::Red,
    }
}

fn alert_kind_color(kind: AlertKind) -> Color {
    match kind {
        AlertKind::EnteredQualified => Color::Green,
        AlertKind::ExitedQualified => Color::Red,
        AlertKind::ConfidenceUpgraded => Color::Cyan,
    }
}

fn gap_color(actual_gap_bps: i32, minimum_gap_bps: i32) -> Color {
    if actual_gap_bps >= minimum_gap_bps {
        Color::Green
    } else if actual_gap_bps >= minimum_gap_bps / 2 {
        Color::Yellow
    } else {
        Color::Red
    }
}

fn yes_no(value: bool) -> &'static str {
    if value { "yes" } else { "no" }
}

fn health_status_label(health_status: HealthStatus) -> &'static str {
    match health_status {
        HealthStatus::Healthy => "healthy",
        HealthStatus::Degraded => "degraded",
        HealthStatus::Down => "down",
        HealthStatus::Critical => "critical",
    }
}

fn health_status_color(health_status: HealthStatus) -> Color {
    match health_status {
        HealthStatus::Healthy => Color::Green,
        HealthStatus::Degraded => Color::Yellow,
        HealthStatus::Down => Color::Red,
        HealthStatus::Critical => Color::Magenta,
    }
}

fn issue_source_label(issue_source: IssueSource) -> &'static str {
    match issue_source {
        IssueSource::Feed => "feed",
        IssueSource::Persistence => "persistence",
    }
}

fn issue_severity_label(issue_severity: IssueSeverity) -> &'static str {
    match issue_severity {
        IssueSeverity::Warning => "warn",
        IssueSeverity::Error => "error",
        IssueSeverity::Critical => "critical",
    }
}

fn issue_severity_color(issue_severity: IssueSeverity) -> Color {
    match issue_severity {
        IssueSeverity::Warning => Color::Yellow,
        IssueSeverity::Error => Color::Red,
        IssueSeverity::Critical => Color::Magenta,
    }
}

fn truncate_text(text: &str, max_len: usize) -> String {
    if max_len == 0 {
        return String::new();
    }

    let characters = text.chars();
    let total_chars = characters.clone().count();
    if total_chars <= max_len {
        return text.to_string();
    }

    if max_len <= 3 {
        return ".".repeat(max_len);
    }

    let mut truncated = characters.take(max_len - 3).collect::<String>();
    truncated.push_str("...");
    truncated
}

fn wrap_text(text: &str, max_width: usize) -> Vec<String> {
    let mut lines = Vec::new();
    let mut current_line = String::new();

    for word in text.split_whitespace() {
        let projected_width = if current_line.is_empty() {
            word.len()
        } else {
            current_line.len() + 1 + word.len()
        };

        if projected_width > max_width && !current_line.is_empty() {
            lines.push(current_line);
            current_line = word.to_string();
        } else if current_line.is_empty() {
            current_line.push_str(word);
        } else {
            current_line.push(' ');
            current_line.push_str(word);
        }
    }

    if !current_line.is_empty() {
        lines.push(current_line);
    }

    if lines.is_empty() {
        lines.push(String::new());
    }

    lines
}

fn track_symbols_from_query(
    query: &str,
    app: &mut AppState,
    live_symbols: Option<&LiveSymbolState>,
    feed_control_sender: Option<&mpsc::Sender<FeedControl>>,
    persistence_handle: Option<&PersistenceHandle>,
) {
    let Some(live_symbols) = live_symbols else {
        app.set_status_message("Symbol lookup is only available in live mode.");
        return;
    };

    let symbols = match parse_symbols_argument(query) {
        Ok(symbols) => symbols,
        Err(_) => {
            app.set_status_message(
                "Enter one or more ticker symbols, for example NVDA or AAPL,MSFT.",
            );
            return;
        }
    };
    let focus_symbol = symbols[0].clone();
    let added_symbols = live_symbols.add_symbols(symbols);

    if !added_symbols.is_empty() {
        if let Some(persistence_handle) = persistence_handle {
            persistence_handle.replace_tracked_symbols(live_symbols.snapshot());
        }
        if let Some(feed_control_sender) = feed_control_sender {
            let _ = feed_control_sender.send(FeedControl::RefreshNow);
        }

        app.set_status_message(format!("Tracking {}.", added_symbols.join(", ")));
    } else {
        app.set_status_message(format!("{focus_symbol} is already tracked."));
    }

    app.view_filter.query = focus_symbol.clone();
    app.view_filter.watchlist_only = false;
    app.selected_symbol = Some(focus_symbol);
}

#[cfg(test)]
fn persist_new_journal_entries(
    state: &TerminalState,
    journal_file: Option<&PathBuf>,
    last_persisted_sequence: &mut usize,
) -> io::Result<()> {
    let Some(journal_file) = journal_file else {
        return Ok(());
    };

    let latest_sequence = state.latest_sequence();
    if latest_sequence <= *last_persisted_sequence {
        return Ok(());
    }

    let delta = state.journal_since(*last_persisted_sequence);
    TerminalState::append_journal_file(journal_file, &delta)
        .map_err(|error| with_path_context(error, "append journal file", journal_file))?;
    *last_persisted_sequence = latest_sequence;
    Ok(())
}

fn format_money(value_cents: i64) -> String {
    let sign = if value_cents < 0 { "-" } else { "" };
    let absolute_cents = value_cents.unsigned_abs();
    let dollars = absolute_cents / 100;
    let cents = absolute_cents % 100;
    format!("{sign}${dollars}.{cents:02}")
}

fn format_bps(value_bps: i32) -> String {
    let sign = if value_bps < 0 { "-" } else { "" };
    let absolute_bps = value_bps.unsigned_abs();
    let whole = absolute_bps / 100;
    let fraction = absolute_bps % 100;
    format!("{sign}{whole}.{fraction:02}%")
}

fn format_optional_decimal(value: Option<f64>, decimals: usize) -> String {
    value
        .map(|value| format!("{value:.decimals$}"))
        .unwrap_or_else(|| "n/a".to_string())
}

fn format_optional_percent(value_percent: Option<f64>) -> String {
    value_percent
        .map(|value| format!("{value:.2}%"))
        .unwrap_or_else(|| "n/a".to_string())
}

fn format_optional_percent_ratio(value_ratio: Option<f64>) -> String {
    value_ratio
        .map(|value| format!("{:.2}%", value * 100.0))
        .unwrap_or_else(|| "n/a".to_string())
}

fn dcf_signal_label(signal: DcfSignal) -> &'static str {
    match signal {
        DcfSignal::Opportunity => "OPPORTUNITY",
        DcfSignal::Fair => "FAIR",
        DcfSignal::Expensive => "EXPENSIVE",
    }
}

fn dcf_signal_color(signal: DcfSignal) -> Color {
    match signal {
        DcfSignal::Opportunity => Color::Green,
        DcfSignal::Fair => Color::Yellow,
        DcfSignal::Expensive => Color::Red,
    }
}

fn relative_strength_label(band: RelativeStrengthBand) -> &'static str {
    match band {
        RelativeStrengthBand::Strong => "strong",
        RelativeStrengthBand::Mixed => "mixed",
        RelativeStrengthBand::Weak => "weak",
    }
}

fn relative_strength_color(band: RelativeStrengthBand) -> Color {
    match band {
        RelativeStrengthBand::Strong => Color::Green,
        RelativeStrengthBand::Mixed => Color::Yellow,
        RelativeStrengthBand::Weak => Color::Red,
    }
}

fn format_upside_percent(market_price_cents: i64, fair_value_cents: i64) -> String {
    checked_upside_bps(market_price_cents, fair_value_cents)
        .map(format_bps)
        .unwrap_or_else(|| "n/a".to_string())
}

fn format_upside_percent_from_gap_bps(gap_bps: i32) -> String {
    upside_bps_from_gap_bps(gap_bps)
        .map(format_bps)
        .unwrap_or_else(|| "n/a".to_string())
}

fn checked_upside_bps(market_price_cents: i64, fair_value_cents: i64) -> Option<i32> {
    if market_price_cents <= 0 || fair_value_cents <= 0 {
        return None;
    }

    let scaled_upside_bps = rounded_division(
        (fair_value_cents as i128 - market_price_cents as i128) * 10_000,
        market_price_cents as i128,
    );

    Some(scaled_upside_bps.clamp(i32::MIN as i128, i32::MAX as i128) as i32)
}

fn upside_bps_from_gap_bps(gap_bps: i32) -> Option<i32> {
    let denominator = 10_000_i128 - gap_bps as i128;
    if denominator <= 0 {
        return None;
    }

    let scaled_upside_bps = rounded_division(gap_bps as i128 * 10_000, denominator);

    Some(scaled_upside_bps.clamp(i32::MIN as i128, i32::MAX as i128) as i32)
}

fn rounded_division(numerator: i128, denominator: i128) -> i128 {
    if numerator >= 0 {
        (numerator + denominator / 2) / denominator
    } else {
        -((-numerator + denominator / 2) / denominator)
    }
}

#[derive(Default)]
struct TerminalGuard {
    raw_mode_enabled: bool,
    alternate_screen_entered: bool,
}

impl TerminalGuard {
    fn enable_raw_mode(&mut self) -> io::Result<()> {
        terminal::enable_raw_mode()?;
        self.raw_mode_enabled = true;
        Ok(())
    }

    fn enter_alternate_screen(&mut self, stdout: &mut Stdout) -> io::Result<()> {
        execute!(stdout, EnterAlternateScreen)?;
        self.alternate_screen_entered = true;
        execute!(stdout, Hide)
    }
}

impl Drop for TerminalGuard {
    fn drop(&mut self) {
        if self.raw_mode_enabled {
            let _ = terminal::disable_raw_mode();
        }

        let mut stdout = io::stdout();
        if self.alternate_screen_entered {
            let _ = execute!(stdout, Show, LeaveAlternateScreen);
        } else {
            let _ = execute!(stdout, Show);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::AnalysisCacheEntry;
    use super::AnalysisControl;
    use super::AppEvent;
    use super::AppEventPublisher;
    use super::AppState;
    use super::CANDIDATE_COMPANY_COLUMN_WIDTH;
    use super::Color;
    use super::DcfAnalysis;
    use super::DcfSignal;
    use super::Event;
    use super::FeedErrorLogger;
    use super::FeedEvent;
    use super::FeedRefreshPlan;
    use super::HistoryMetricGroup;
    use super::HistorySubview;
    use super::HistoryWindow;
    use super::ISSUE_KEY_JOURNAL_RESTORE;
    use super::ISSUE_KEY_WATCHLIST_RESTORE;
    use super::InputMode;
    use super::IssueCenter;
    use super::IssueSeverity;
    use super::IssueSource;
    use super::KeyCode;
    use super::KeyEvent;
    use super::KeyEventKind;
    use super::KeyModifiers;
    use super::LiveSourceStatus;
    use super::LiveSymbolState;
    use super::LoopControl;
    use super::MAX_VISIBLE_ROWS;
    use super::OverlayMode;
    use super::PersistenceStatusEvent;
    use super::RelativeMetricScore;
    use super::RelativeStrengthBand;
    use super::RenderLine;
    use super::RuntimeOptions;
    use super::aggregate_historical_candles;
    use super::analysis_input_key;
    use super::analyst_consensus_lines;
    use super::apply_feed_events;
    use super::apply_live_source_status;
    use super::apply_persistence_status;
    use super::build_screen_lines;
    use super::build_screen_lines_for_viewport;
    use super::build_symbol_feed_batch;
    use super::build_ticker_detail_lines;
    use super::build_ticker_detail_lines_for_viewport;
    use super::build_ticker_history_lines_for_viewport;
    use super::candidate_company_label;
    use super::chart_loop_with_client_factory;
    use super::chart_range_label;
    use super::chart_ranges;
    use super::clip_text_to_width;
    use super::collect_clear_rows;
    use super::collect_dirty_rows;
    use super::compute_dcf_analysis;
    use super::compute_ema_series;
    use super::compute_macd_series;
    use super::compute_sector_relative_score;
    use super::confidence_justification_lines;
    use super::dcf_margin_of_safety_bps;
    use super::dcf_signal;
    use super::derive_base_growth_bps;
    use super::detail_analysis_snapshot;
    use super::export_selected_history_bundle;
    use super::feed_loop_with_client_factory;
    use super::filtered_symbol_rows;
    use super::format_bps;
    use super::format_compact_dollars;
    use super::format_money;
    use super::format_symbol_list;
    use super::gap_meter;
    use super::handle_input_event;
    use super::health_status_label;
    use super::input_prompt;
    use super::is_provider_throttle_error;
    use super::is_retryable_feed_error;
    use super::load_initial_state;
    use super::market_data::AnnualReportedValue;
    use super::market_data::ChartRange;
    use super::market_data::FundamentalTimeseries;
    use super::market_data::HistoricalCandle;
    use super::next_weighted_target_refresh_cursor;
    use super::normalize_frame;
    use super::parse_runtime_options_from;
    use super::parse_symbols_argument;
    use super::persistence;
    use super::publish_feed_refresh;
    use super::publish_feed_refresh_concurrently;
    use super::publish_input_events;
    use super::qualification_justification_lines;
    use super::reconcile_capture_persistence;
    use super::reconcile_journal_persistence;
    use super::reconcile_sqlite_persistence;
    use super::relative_metric_score;
    use super::robust_composite_percentile;
    use super::should_handle_key_event;
    use super::should_leave_input_mode_on_backspace;
    use super::should_refresh_weighted_target;
    use super::summarize_chart_range;
    use super::usage_text;
    use super::visible_text;
    use crate::BACKGROUND_CHART_REQUEST_BUDGET_PER_CYCLE;
    use crate::ChartRangeSummary;
    use crate::SectorRelativeScore;
    use crate::unix_timestamp_seconds;
    use discount_screener::CandidateRow;
    use discount_screener::ConfidenceBand;
    use discount_screener::ExternalSignalStatus;
    use discount_screener::ExternalValuationSignal;
    use discount_screener::FundamentalSnapshot;
    use discount_screener::MarketSnapshot;
    use discount_screener::QualificationStatus;
    use discount_screener::SymbolDetail;
    use discount_screener::TerminalState;
    use discount_screener::ViewFilter;
    use discount_screener::checked_gap_bps;
    use rusqlite::Connection;
    use std::collections::HashSet;
    use std::collections::VecDeque;
    use std::env::temp_dir;
    use std::error::Error as _;
    use std::fs;
    use std::io;
    use std::path::PathBuf;
    use std::sync::Arc;
    use std::sync::Mutex;
    use std::sync::mpsc;
    use std::time::Duration;
    use std::time::SystemTime;
    use std::time::UNIX_EPOCH;

    fn candidate(symbol: &str, gap_bps: i32) -> CandidateRow {
        CandidateRow {
            symbol: symbol.to_string(),
            market_price_cents: 8_000,
            intrinsic_value_cents: 10_000,
            gap_bps,
            is_qualified: true,
            confidence: ConfidenceBand::Provisional,
        }
    }

    fn ranked_main_view_lines_for_viewport(
        viewport_width: usize,
        viewport_height: usize,
    ) -> Vec<String> {
        let mut state = TerminalState::new(2_000, 30, 8);
        let live_symbols = LiveSymbolState::new(
            (0..25)
                .map(|index| format!("H{index:02}"))
                .collect::<Vec<_>>(),
        );

        for index in 0..25 {
            let symbol = format!("H{index:02}");
            state.ingest_snapshot(MarketSnapshot {
                symbol: symbol.clone(),
                company_name: Some(format!("Holding {index} Incorporated")),
                profitable: true,
                market_price_cents: 10_000 + index as i64 * 100,
                intrinsic_value_cents: 15_000 + index as i64 * 100,
            });
            state.ingest_external(ExternalValuationSignal {
                symbol,
                fair_value_cents: 13_000,
                age_seconds: 0,
                low_fair_value_cents: None,
                high_fair_value_cents: None,
                analyst_opinion_count: None,
                recommendation_mean_hundredths: None,
                strong_buy_count: None,
                buy_count: None,
                hold_count: None,
                sell_count: None,
                strong_sell_count: None,
                weighted_fair_value_cents: None,
                weighted_analyst_count: None,
            });
        }

        let app = AppState::default();
        let rows = app.visible_rows(&state);
        normalize_frame(
            &build_screen_lines_for_viewport(
                &state,
                &rows,
                0,
                0,
                true,
                &app,
                Some(&live_symbols),
                viewport_width,
                viewport_height,
            ),
            viewport_width,
            viewport_height,
        )
        .iter()
        .map(|line| visible_text(&line.text))
        .collect()
    }

    fn recv_feed_batch(receiver: &mpsc::Receiver<AppEvent>, label: &str) -> Vec<FeedEvent> {
        loop {
            match receiver
                .recv()
                .unwrap_or_else(|_| panic!("{label} should arrive"))
            {
                AppEvent::FeedBatch(feed_events) => return feed_events,
                AppEvent::FeedStatus(_) => continue,
                _ => panic!("expected {label}"),
            }
        }
    }

    fn recv_with_timeout<T>(receiver: &mpsc::Receiver<T>, message: &str) -> T {
        receiver
            .recv_timeout(Duration::from_millis(250))
            .expect(message)
    }

    fn unique_test_path(label: &str) -> PathBuf {
        let unique_suffix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock should be after the unix epoch")
            .as_nanos();

        temp_dir().join(format!(
            "discount-screener-{label}-{}-{unique_suffix}",
            std::process::id()
        ))
    }

    fn assert_close(actual: f64, expected: f64, tolerance: f64) {
        assert!(
            (actual - expected).abs() <= tolerance,
            "expected {expected}, got {actual}"
        );
    }

    fn detail() -> SymbolDetail {
        SymbolDetail {
            symbol: "NVDA".to_string(),
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
            gap_bps: 3_585,
            minimum_gap_bps: 2_000,
            qualification: QualificationStatus::Qualified,
            external_status: ExternalSignalStatus::Supportive,
            external_signal_fair_value_cents: Some(26_923),
            external_signal_low_fair_value_cents: Some(18_500),
            external_signal_high_fair_value_cents: Some(32_000),
            weighted_external_signal_fair_value_cents: Some(27_850),
            weighted_analyst_count: Some(12),
            external_signal_gap_bps: Some(3_585),
            external_signal_age_seconds: Some(6),
            external_signal_max_age_seconds: 30,
            analyst_opinion_count: Some(42),
            recommendation_mean_hundredths: Some(185),
            strong_buy_count: Some(20),
            buy_count: Some(10),
            hold_count: Some(8),
            sell_count: Some(3),
            strong_sell_count: Some(1),
            fundamentals: None,
            confidence: ConfidenceBand::High,
            last_sequence: 6,
            update_count: 2,
            is_watched: false,
        }
    }

    fn historical_candles() -> Vec<HistoricalCandle> {
        vec![
            HistoricalCandle {
                epoch_seconds: 1,
                open_cents: 10_000,
                high_cents: 11_000,
                low_cents: 9_000,
                close_cents: 9_500,
                volume: 100_000,
            },
            HistoricalCandle {
                epoch_seconds: 2,
                open_cents: 9_500,
                high_cents: 12_000,
                low_cents: 9_400,
                close_cents: 11_500,
                volume: 125_000,
            },
            HistoricalCandle {
                epoch_seconds: 3,
                open_cents: 11_500,
                high_cents: 12_500,
                low_cents: 11_200,
                close_cents: 11_300,
                volume: 150_000,
            },
            HistoricalCandle {
                epoch_seconds: 4,
                open_cents: 11_300,
                high_cents: 13_000,
                low_cents: 10_800,
                close_cents: 12_800,
                volume: 175_000,
            },
        ]
    }

    fn live_feed(symbol: &str) -> super::market_data::ProviderFetchResult {
        super::market_data::ProviderFetchResult {
            symbol: symbol.to_string(),
            snapshot: Some(MarketSnapshot {
                symbol: symbol.to_string(),
                company_name: None,
                profitable: true,
                market_price_cents: 10_000,
                intrinsic_value_cents: 12_500,
            }),
            external_signal: Some(ExternalValuationSignal {
                symbol: symbol.to_string(),
                fair_value_cents: 12_000,
                age_seconds: 0,
                low_fair_value_cents: None,
                high_fair_value_cents: None,
                analyst_opinion_count: None,
                recommendation_mean_hundredths: None,
                strong_buy_count: None,
                buy_count: None,
                hold_count: None,
                sell_count: None,
                strong_sell_count: None,
                weighted_fair_value_cents: None,
                weighted_analyst_count: None,
            }),
            fundamentals: Some(sample_fundamentals(
                symbol,
                "technology",
                "Technology",
                "software",
                "Software",
            )),
            coverage: super::market_data::ProviderCoverage {
                core: super::market_data::ProviderComponentState::Fresh,
                external: super::market_data::ProviderComponentState::Fresh,
                fundamentals: super::market_data::ProviderComponentState::Fresh,
            },
            diagnostics: Vec::new(),
        }
    }

    fn annual_value(as_of_date: &str, value: f64) -> AnnualReportedValue {
        AnnualReportedValue {
            as_of_date: as_of_date.to_string(),
            value,
        }
    }

    fn sample_fundamentals(
        symbol: &str,
        sector_key: &str,
        sector_name: &str,
        industry_key: &str,
        industry_name: &str,
    ) -> FundamentalSnapshot {
        FundamentalSnapshot {
            symbol: symbol.to_string(),
            sector_key: Some(sector_key.to_string()),
            sector_name: Some(sector_name.to_string()),
            industry_key: Some(industry_key.to_string()),
            industry_name: Some(industry_name.to_string()),
            market_cap_dollars: Some(1_200_000_000),
            shares_outstanding: Some(100_000_000),
            trailing_pe_hundredths: Some(1_500),
            forward_pe_hundredths: Some(1_300),
            price_to_book_hundredths: Some(320),
            return_on_equity_bps: Some(1_900),
            ebitda_dollars: Some(220_000_000),
            enterprise_value_dollars: Some(1_300_000_000),
            enterprise_to_ebitda_hundredths: Some(590),
            total_debt_dollars: Some(120_000_000),
            total_cash_dollars: Some(20_000_000),
            debt_to_equity_hundredths: Some(6_000),
            free_cash_flow_dollars: Some(86_000_000),
            operating_cash_flow_dollars: Some(105_000_000),
            beta_millis: Some(1_100),
            trailing_eps_cents: Some(425),
            earnings_growth_bps: Some(1_500),
        }
    }

    fn sample_dcf_timeseries() -> FundamentalTimeseries {
        FundamentalTimeseries {
            free_cash_flow: vec![
                annual_value("2021-12-31", 50_000_000.0),
                annual_value("2022-12-31", 60_000_000.0),
                annual_value("2023-12-31", 72_000_000.0),
                annual_value("2024-12-31", 86_000_000.0),
            ],
            operating_cash_flow: vec![
                annual_value("2021-12-31", 66_000_000.0),
                annual_value("2022-12-31", 80_000_000.0),
                annual_value("2023-12-31", 93_000_000.0),
                annual_value("2024-12-31", 105_000_000.0),
            ],
            capital_expenditure: vec![
                annual_value("2021-12-31", -16_000_000.0),
                annual_value("2022-12-31", -20_000_000.0),
                annual_value("2023-12-31", -21_000_000.0),
                annual_value("2024-12-31", -19_000_000.0),
            ],
            diluted_average_shares: vec![
                annual_value("2021-12-31", 100_000_000.0),
                annual_value("2022-12-31", 100_000_000.0),
                annual_value("2023-12-31", 100_000_000.0),
                annual_value("2024-12-31", 100_000_000.0),
            ],
            interest_expense: vec![annual_value("2024-12-31", 8_000_000.0)],
            pretax_income: vec![annual_value("2024-12-31", 120_000_000.0)],
            tax_rate_for_calcs: vec![annual_value("2024-12-31", 0.21)],
            net_income: vec![annual_value("2024-12-31", 97_000_000.0)],
        }
    }

    fn sample_ready_analysis() -> DcfAnalysis {
        DcfAnalysis {
            bear_intrinsic_value_cents: 1_450,
            base_intrinsic_value_cents: 1_800,
            bull_intrinsic_value_cents: 2_250,
            wacc_bps: 850,
            base_growth_bps: 1_350,
            net_debt_dollars: 100_000_000,
        }
    }

    fn sample_metric_group_status(available: bool) -> persistence::MetricGroupStatus {
        persistence::MetricGroupStatus {
            available,
            stale: false,
        }
    }

    fn sample_relative_score() -> SectorRelativeScore {
        SectorRelativeScore {
            group_kind: "industry".to_string(),
            group_label: "Software".to_string(),
            peer_count: 12,
            composite_percentile: 72,
            composite_band: RelativeStrengthBand::Strong,
            metrics: vec![
                RelativeMetricScore {
                    label: "P/E".to_string(),
                    percentile: 70,
                    band: RelativeStrengthBand::Strong,
                },
                RelativeMetricScore {
                    label: "PEG".to_string(),
                    percentile: 68,
                    band: RelativeStrengthBand::Mixed,
                },
                RelativeMetricScore {
                    label: "ROE".to_string(),
                    percentile: 82,
                    band: RelativeStrengthBand::Strong,
                },
                RelativeMetricScore {
                    label: "Net debt/EBITDA".to_string(),
                    percentile: 55,
                    band: RelativeStrengthBand::Mixed,
                },
                RelativeMetricScore {
                    label: "FCF yield".to_string(),
                    percentile: 77,
                    band: RelativeStrengthBand::Strong,
                },
            ],
        }
    }

    fn sample_chart_summary(range: ChartRange, close_cents: i64) -> ChartRangeSummary {
        ChartRangeSummary {
            range,
            captured_at: 1_700_000_000,
            candle_count: 120,
            latest_close_cents: Some(close_cents),
            ema20_cents: Some(close_cents - 50),
            ema50_cents: Some(close_cents - 100),
            ema200_cents: Some(close_cents - 250),
            macd_cents: Some(45),
            signal_cents: Some(31),
            histogram_cents: Some(14),
        }
    }

    fn sample_history_records(
        symbol: &str,
        include_relative: bool,
    ) -> Vec<persistence::PersistedRevisionRecord> {
        let base_time = unix_timestamp_seconds().saturating_sub(2 * 86_400);
        (0..3)
            .map(|index| {
                let market_price_cents = 18_500 + index as i64 * 75;
                let intrinsic_value_cents = 24_500 + index as i64 * 90;
                let external_fair_value_cents = 23_800 + index as i64 * 80;
                let mut fundamentals =
                    sample_fundamentals(symbol, "technology", "Technology", "software", "Software");
                fundamentals.market_cap_dollars =
                    Some(1_200_000_000_u64 + index as u64 * 50_000_000);
                fundamentals.free_cash_flow_dollars = Some(86_000_000 + index as i64 * 5_000_000);
                fundamentals.operating_cash_flow_dollars =
                    Some(105_000_000 + index as i64 * 6_000_000);
                fundamentals.trailing_pe_hundredths = Some(1_500_u32 + index as u32 * 20);
                fundamentals.earnings_growth_bps = Some(1_500 + index as i32 * 40);

                let mut dcf_analysis = sample_ready_analysis();
                dcf_analysis.base_intrinsic_value_cents += index as i64 * 110;
                dcf_analysis.bear_intrinsic_value_cents += index as i64 * 80;
                dcf_analysis.bull_intrinsic_value_cents += index as i64 * 140;

                persistence::PersistedRevisionRecord {
                    revision_id: index as i64 + 1,
                    symbol: symbol.to_string(),
                    evaluated_at: base_time + index as u64 * 86_400,
                    last_sequence: index + 1,
                    update_count: index + 1,
                    payload: persistence::EvaluatedSymbolState {
                        snapshot: Some(MarketSnapshot {
                            symbol: symbol.to_string(),
                            company_name: Some("Example Corp".to_string()),
                            profitable: true,
                            market_price_cents,
                            intrinsic_value_cents,
                        }),
                        external_signal: Some(ExternalValuationSignal {
                            symbol: symbol.to_string(),
                            fair_value_cents: external_fair_value_cents,
                            age_seconds: 120,
                            low_fair_value_cents: Some(22_000),
                            high_fair_value_cents: Some(26_500),
                            analyst_opinion_count: Some(10 + index as u32),
                            recommendation_mean_hundredths: Some(180),
                            strong_buy_count: Some(5),
                            buy_count: Some(4),
                            hold_count: Some(1),
                            sell_count: Some(0),
                            strong_sell_count: Some(0),
                            weighted_fair_value_cents: Some(24_100 + index as i64 * 75),
                            weighted_analyst_count: Some(8),
                        }),
                        fundamentals: Some(fundamentals),
                        gap_bps: Some(3_200 + index as i32 * 45),
                        qualification: Some(QualificationStatus::Qualified),
                        external_status: Some(ExternalSignalStatus::Supportive),
                        confidence: Some(ConfidenceBand::High),
                        external_gap_bps: Some(2_800 + index as i32 * 30),
                        weighted_gap_bps: Some(2_950 + index as i32 * 25),
                        dcf_analysis: Some(dcf_analysis),
                        dcf_signal: Some(DcfSignal::Opportunity),
                        dcf_margin_of_safety_bps: Some(2_100 + index as i32 * 35),
                        relative_score: include_relative.then(sample_relative_score),
                        chart_summaries: chart_ranges()
                            .iter()
                            .enumerate()
                            .map(|(range_index, range)| {
                                sample_chart_summary(
                                    *range,
                                    market_price_cents + range_index as i64 * 25,
                                )
                            })
                            .collect(),
                        core_status: sample_metric_group_status(true),
                        fundamentals_status: sample_metric_group_status(true),
                        relative_status: sample_metric_group_status(include_relative),
                        dcf_status: sample_metric_group_status(true),
                        chart_status: sample_metric_group_status(true),
                        is_watched: index % 2 == 0,
                    },
                }
            })
            .collect()
    }

    fn persist_sample_symbol_revision(
        persistence_handle: &persistence::PersistenceHandle,
        symbol: &str,
        market_price_cents: i64,
        intrinsic_value_cents: i64,
    ) {
        persistence_handle
            .persist_batch(
                Vec::new(),
                vec![persistence::SymbolRevisionInput {
                    symbol: symbol.to_string(),
                    evaluated_at: 1_700_000_000,
                    last_sequence: 1,
                    update_count: 1,
                    price_history: vec![discount_screener::PriceHistoryPoint {
                        sequence: 1,
                        market_price_cents,
                    }],
                    payload: persistence::EvaluatedSymbolState {
                        snapshot: Some(MarketSnapshot {
                            symbol: symbol.to_string(),
                            company_name: None,
                            profitable: true,
                            market_price_cents,
                            intrinsic_value_cents,
                        }),
                        external_signal: None,
                        fundamentals: None,
                        gap_bps: checked_gap_bps(market_price_cents, intrinsic_value_cents),
                        qualification: Some(QualificationStatus::Qualified),
                        external_status: Some(ExternalSignalStatus::Missing),
                        confidence: Some(ConfidenceBand::Low),
                        external_gap_bps: None,
                        weighted_gap_bps: None,
                        dcf_analysis: None,
                        dcf_signal: None,
                        dcf_margin_of_safety_bps: None,
                        relative_score: None,
                        chart_summaries: Vec::new(),
                        core_status: sample_metric_group_status(true),
                        fundamentals_status: sample_metric_group_status(false),
                        relative_status: sample_metric_group_status(false),
                        dcf_status: sample_metric_group_status(false),
                        chart_status: sample_metric_group_status(false),
                        is_watched: false,
                    },
                }],
            )
            .expect("symbol revision should persist");
    }

    #[derive(Clone)]
    struct FakeFeedClient {
        calls: Arc<Mutex<Vec<(String, bool)>>>,
        results: Arc<Mutex<VecDeque<io::Result<super::market_data::ProviderFetchResult>>>>,
    }

    impl super::LiveFeedClient for FakeFeedClient {
        fn fetch_symbol_with_options(
            &self,
            symbol: &str,
            refresh_weighted_target: bool,
        ) -> io::Result<super::market_data::ProviderFetchResult> {
            self.calls
                .lock()
                .expect("fake client calls should be lockable")
                .push((symbol.to_string(), refresh_weighted_target));
            self.results
                .lock()
                .expect("fake client results should be lockable")
                .pop_front()
                .expect("each fake client fetch should have a queued result")
        }
    }

    #[derive(Clone)]
    struct FakeChartClient {
        calls: Arc<Mutex<Vec<(String, ChartRange)>>>,
        results: Arc<Mutex<VecDeque<io::Result<Vec<HistoricalCandle>>>>>,
    }

    impl super::HistoricalChartClient for FakeChartClient {
        fn fetch_historical_candles(
            &self,
            symbol: &str,
            range: ChartRange,
        ) -> io::Result<Vec<HistoricalCandle>> {
            self.calls
                .lock()
                .expect("fake chart client calls should be lockable")
                .push((symbol.to_string(), range));
            self.results
                .lock()
                .expect("fake chart client results should be lockable")
                .pop_front()
                .expect("each fake chart fetch should have a queued result")
        }
    }

    #[test]
    fn selection_tracks_the_same_symbol_after_reordering() {
        let mut app = AppState::default();
        app.selected_symbol = Some("BETA".to_string());
        let rows = vec![candidate("ALFA", 3_000), candidate("BETA", 2_000)];
        let reordered_rows = vec![candidate("BETA", 2_000), candidate("ALFA", 3_000)];
        app.selected_index(&rows);

        assert_eq!(app.selected_index(&reordered_rows), 0);
    }

    #[test]
    fn render_lines_are_built_as_distinct_rows() {
        let lines = vec![
            RenderLine {
                color: Some(Color::Yellow),
                text: "HEADER".to_string(),
            },
            RenderLine {
                color: None,
                text: "STATUS".to_string(),
            },
            RenderLine {
                color: None,
                text: "FILTER".to_string(),
            },
        ];

        assert_eq!(
            lines.into_iter().map(|line| line.text).collect::<Vec<_>>(),
            vec![
                "HEADER".to_string(),
                "STATUS".to_string(),
                "FILTER".to_string(),
            ]
        );
    }

    #[test]
    fn parse_symbols_argument_deduplicates_and_normalizes_symbols() {
        assert_eq!(
            parse_symbols_argument(" msft, aapl,MSFT ")
                .expect("symbols should parse and deduplicate"),
            vec!["MSFT".to_string(), "AAPL".to_string()]
        );
    }

    #[test]
    fn parse_runtime_options_defaults_to_sp500_profile() {
        let options = parse_runtime_options_from(Vec::<String>::new())
            .expect("empty args should resolve to the default universe");

        assert!(options.symbols.is_empty());
        assert!(options.persist_enabled);
        assert!(options.state_db.is_some());
    }

    #[test]
    fn parse_runtime_options_loads_named_profile_symbols() {
        let options = parse_runtime_options_from(["--profile", "dow-jones"])
            .expect("named profiles should resolve through aliases");

        assert_eq!(
            options.symbols,
            vec![
                "MMM", "AXP", "AMGN", "AMZN", "AAPL", "BA", "CAT", "CVX", "CSCO", "KO", "DIS",
                "GS", "HD", "HON", "IBM", "JNJ", "JPM", "MCD", "MRK", "MSFT", "NVDA", "NKE", "PG",
                "CRM", "SHW", "TRV", "UNH", "VZ", "V", "WMT",
            ]
            .into_iter()
            .map(str::to_string)
            .collect::<Vec<_>>()
        );
    }

    #[test]
    fn compute_dcf_analysis_builds_three_scenarios_and_signal_thresholds() {
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        let analysis =
            compute_dcf_analysis(&fundamentals, &sample_dcf_timeseries()).expect("DCF should work");

        assert!(analysis.bear_intrinsic_value_cents < analysis.base_intrinsic_value_cents);
        assert!(analysis.base_intrinsic_value_cents < analysis.bull_intrinsic_value_cents);
        assert_eq!(analysis.net_debt_dollars, 100_000_000);
        assert!((500..=1_800).contains(&analysis.wacc_bps));
        assert!(analysis.base_growth_bps >= -1_000);
        assert_eq!(
            dcf_signal(
                &analysis,
                ((analysis.base_intrinsic_value_cents as f64) * 0.75).round() as i64
            ),
            DcfSignal::Opportunity
        );
        assert_eq!(
            dcf_signal(&analysis, analysis.base_intrinsic_value_cents),
            DcfSignal::Fair
        );
        assert_eq!(
            dcf_signal(
                &analysis,
                ((analysis.base_intrinsic_value_cents as f64) * 1.20).round() as i64
            ),
            DcfSignal::Expensive
        );
        assert_eq!(
            dcf_margin_of_safety_bps(&analysis, analysis.base_intrinsic_value_cents),
            Some(0)
        );
    }

    #[test]
    fn compute_dcf_analysis_preserves_net_cash_companies() {
        let mut fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        fundamentals.total_debt_dollars = Some(100_000_000);
        fundamentals.total_cash_dollars = Some(250_000_000);

        let analysis =
            compute_dcf_analysis(&fundamentals, &sample_dcf_timeseries()).expect("DCF should work");

        assert_eq!(analysis.net_debt_dollars, -150_000_000);
    }

    #[test]
    fn compute_dcf_analysis_rejects_insufficient_and_non_positive_fcf_history() {
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        let insufficient = FundamentalTimeseries {
            free_cash_flow: vec![
                annual_value("2023-12-31", 72_000_000.0),
                annual_value("2024-12-31", 86_000_000.0),
            ],
            diluted_average_shares: vec![
                annual_value("2023-12-31", 100_000_000.0),
                annual_value("2024-12-31", 100_000_000.0),
            ],
            ..FundamentalTimeseries::default()
        };
        let non_positive = FundamentalTimeseries {
            free_cash_flow: vec![
                annual_value("2022-12-31", 50_000_000.0),
                annual_value("2023-12-31", 25_000_000.0),
                annual_value("2024-12-31", 0.0),
            ],
            diluted_average_shares: vec![
                annual_value("2022-12-31", 100_000_000.0),
                annual_value("2023-12-31", 100_000_000.0),
                annual_value("2024-12-31", 100_000_000.0),
            ],
            ..FundamentalTimeseries::default()
        };

        assert!(
            compute_dcf_analysis(&fundamentals, &insufficient)
                .expect_err("two points should fail")
                .to_string()
                .contains("at least 3 annual free cash flow points")
        );
        assert!(
            compute_dcf_analysis(&fundamentals, &non_positive)
                .expect_err("non-positive latest FCF should fail")
                .to_string()
                .contains("latest annual free cash flow is not positive")
        );
    }

    #[test]
    fn derive_base_growth_uses_the_earliest_positive_history_point() {
        let declining_then_partial_recovery = vec![
            ("2021-12-31".to_string(), 10.0),
            ("2022-12-31".to_string(), 5.0),
            ("2023-12-31".to_string(), 6.0),
        ];

        assert_eq!(
            derive_base_growth_bps(&declining_then_partial_recovery),
            Some(-2255)
        );
    }

    #[test]
    fn derive_base_growth_uses_fractional_years_for_stub_periods() {
        let stub_period = vec![
            ("2022-12-28".to_string(), 10.0),
            ("2024-01-05".to_string(), 15.0),
        ];
        let expected_years = 373.0 / 365.2425;
        let expected_cagr_bps =
            (((15.0_f64 / 10.0_f64).powf(1.0 / expected_years) - 1.0) * 10_000.0).round() as i32;

        assert_eq!(
            derive_base_growth_bps(&stub_period),
            Some(expected_cagr_bps)
        );
        assert!(expected_cagr_bps > 4_000);
    }

    #[test]
    fn dcf_margin_of_safety_returns_none_instead_of_saturating() {
        let analysis = DcfAnalysis {
            bear_intrinsic_value_cents: 1,
            base_intrinsic_value_cents: 1,
            bull_intrinsic_value_cents: 1,
            wacc_bps: 1_000,
            base_growth_bps: 500,
            net_debt_dollars: 0,
        };

        assert_eq!(dcf_margin_of_safety_bps(&analysis, i64::MAX), None);
        assert_eq!(dcf_signal(&analysis, i64::MAX), DcfSignal::Expensive);
    }

    #[test]
    fn sector_relative_score_prefers_industry_peers_when_available() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_fundamentals(subject.clone());

        for (index, pe, growth, roe, debt, cash, ebitda, fcf) in [
            (
                1,
                2_100,
                900,
                1_500,
                180_000_000,
                10_000_000,
                200_000_000,
                55_000_000,
            ),
            (
                2,
                2_300,
                1_000,
                1_600,
                170_000_000,
                15_000_000,
                210_000_000,
                58_000_000,
            ),
            (
                3,
                2_500,
                1_100,
                1_700,
                160_000_000,
                20_000_000,
                205_000_000,
                60_000_000,
            ),
            (
                4,
                2_700,
                1_200,
                1_800,
                150_000_000,
                20_000_000,
                215_000_000,
                63_000_000,
            ),
            (
                5,
                2_900,
                1_300,
                1_900,
                145_000_000,
                25_000_000,
                220_000_000,
                66_000_000,
            ),
        ] {
            let mut peer = sample_fundamentals(
                &format!("I{index}"),
                "technology",
                "Technology",
                "software",
                "Software",
            );
            peer.trailing_pe_hundredths = Some(pe);
            peer.earnings_growth_bps = Some(growth);
            peer.return_on_equity_bps = Some(roe);
            peer.total_debt_dollars = Some(debt);
            peer.total_cash_dollars = Some(cash);
            peer.ebitda_dollars = Some(ebitda);
            peer.free_cash_flow_dollars = Some(fcf);
            state.ingest_fundamentals(peer);
        }

        for index in 1..=2 {
            let peer = sample_fundamentals(
                &format!("S{index}"),
                "technology",
                "Technology",
                "hardware",
                "Hardware",
            );
            state.ingest_fundamentals(peer);
        }

        let score = compute_sector_relative_score(&state, &subject)
            .expect("industry peers should produce a relative score");

        assert_eq!(score.group_kind, "industry");
        assert_eq!(score.group_label, "Software");
        assert_eq!(score.peer_count, 5);
        assert_eq!(score.composite_band, super::RelativeStrengthBand::Strong);
    }

    #[test]
    fn sector_relative_score_falls_back_to_sector_when_industry_is_too_small() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_fundamentals(subject.clone());

        for index in 1..=3 {
            let peer = sample_fundamentals(
                &format!("I{index}"),
                "technology",
                "Technology",
                "software",
                "Software",
            );
            state.ingest_fundamentals(peer);
        }
        for index in 1..=2 {
            let mut peer = sample_fundamentals(
                &format!("H{index}"),
                "technology",
                "Technology",
                "hardware",
                "Hardware",
            );
            peer.trailing_pe_hundredths = Some(2_400 + (index as u32 * 100));
            state.ingest_fundamentals(peer);
        }

        let score = compute_sector_relative_score(&state, &subject)
            .expect("sector fallback should produce a relative score");

        assert_eq!(score.group_kind, "sector");
        assert_eq!(score.group_label, "Technology");
        assert_eq!(score.peer_count, 5);
    }

    #[test]
    fn sector_relative_score_treats_a_single_outlier_as_a_small_shift() {
        let subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");

        let mut baseline_state = TerminalState::new(2_000, 30, 8);
        baseline_state.ingest_fundamentals(subject.clone());
        for index in 1..=5 {
            let mut peer = subject.clone();
            peer.symbol = format!("B{index}");
            baseline_state.ingest_fundamentals(peer);
        }

        let baseline_score = compute_sector_relative_score(&baseline_state, &subject)
            .expect("baseline peers should produce a relative score");
        assert_eq!(baseline_score.composite_percentile, 50);

        let mut outlier_state = TerminalState::new(2_000, 30, 8);
        outlier_state.ingest_fundamentals(subject.clone());
        for index in 1..=4 {
            let mut peer = subject.clone();
            peer.symbol = format!("N{index}");
            outlier_state.ingest_fundamentals(peer);
        }
        let mut outlier = subject.clone();
        outlier.symbol = "OUTLIER".to_string();
        outlier.trailing_pe_hundredths = Some(100_000);
        outlier.earnings_growth_bps = Some(100);
        outlier.return_on_equity_bps = Some(-500);
        outlier.total_debt_dollars = Some(800_000_000);
        outlier.total_cash_dollars = Some(0);
        outlier.ebitda_dollars = Some(100_000_000);
        outlier.free_cash_flow_dollars = Some(10_000_000);
        outlier_state.ingest_fundamentals(outlier);

        let outlier_score = compute_sector_relative_score(&outlier_state, &subject)
            .expect("outlier peers should still produce a relative score");

        assert_eq!(outlier_score.composite_percentile, 60);
        assert_eq!(outlier_score.composite_band, RelativeStrengthBand::Mixed);
        assert!(
            (outlier_score.composite_percentile as i16
                - baseline_score.composite_percentile as i16)
                .abs()
                <= 10
        );
    }

    #[test]
    fn sector_relative_score_requires_five_external_peers() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_fundamentals(subject.clone());

        for index in 1..=4 {
            let mut peer = subject.clone();
            peer.symbol = format!("P{index}");
            state.ingest_fundamentals(peer);
        }

        assert!(compute_sector_relative_score(&state, &subject).is_none());
    }

    #[test]
    fn relative_metric_score_uses_midrank_for_ties() {
        let mut subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        subject.trailing_pe_hundredths = Some(2_000);

        let mut peers = Vec::new();
        for index in 1..=5 {
            let mut peer = subject.clone();
            peer.symbol = format!("T{index}");
            peers.push(peer);
        }

        let score = relative_metric_score(
            "P/E",
            super::fundamental_trailing_pe,
            true,
            &subject,
            &peers,
        )
        .expect("tied peers should still score");

        assert_eq!(score.percentile, 50);
        assert_eq!(score.band, RelativeStrengthBand::Mixed);
    }

    #[test]
    fn robust_composite_percentile_uses_median_for_small_metric_sets() {
        let scores = vec![
            RelativeMetricScore {
                label: "A".to_string(),
                percentile: 10,
                band: RelativeStrengthBand::Weak,
            },
            RelativeMetricScore {
                label: "B".to_string(),
                percentile: 50,
                band: RelativeStrengthBand::Mixed,
            },
            RelativeMetricScore {
                label: "C".to_string(),
                percentile: 90,
                band: RelativeStrengthBand::Strong,
            },
        ];

        assert_eq!(robust_composite_percentile(&scores), Some(50));
    }

    #[test]
    fn robust_composite_percentile_trims_extreme_outliers() {
        let scores = vec![
            RelativeMetricScore {
                label: "A".to_string(),
                percentile: 5,
                band: RelativeStrengthBand::Weak,
            },
            RelativeMetricScore {
                label: "B".to_string(),
                percentile: 45,
                band: RelativeStrengthBand::Mixed,
            },
            RelativeMetricScore {
                label: "C".to_string(),
                percentile: 50,
                band: RelativeStrengthBand::Mixed,
            },
            RelativeMetricScore {
                label: "D".to_string(),
                percentile: 55,
                band: RelativeStrengthBand::Mixed,
            },
            RelativeMetricScore {
                label: "E".to_string(),
                percentile: 95,
                band: RelativeStrengthBand::Strong,
            },
        ];

        assert_eq!(robust_composite_percentile(&scores), Some(50));
    }

    #[test]
    fn queue_detail_analysis_request_enqueues_once_per_fundamental_input() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");
        app.queue_detail_analysis_request(&state, Some(&sender));

        let first = recv_with_timeout(&receiver, "analysis request should be queued");
        let request_id = match first {
            AnalysisControl::Load {
                symbol,
                request_id,
                fundamentals: payload,
                ..
            } => {
                assert_eq!(symbol, "NVDA");
                assert_eq!(payload, fundamentals);
                request_id
            }
        };

        assert!(matches!(
            app.detail_analysis_entry("NVDA"),
            Some(AnalysisCacheEntry::Loading { request_id: cached_id, .. }) if *cached_id == request_id
        ));

        app.queue_detail_analysis_request(&state, Some(&sender));
        assert!(receiver.try_recv().is_err());
    }

    #[test]
    fn queue_detail_analysis_request_skips_failed_entries_with_matching_input() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Failed {
                input: analysis_input_key(&fundamentals),
                message: "temporary Yahoo timeout".to_string(),
            },
        );

        app.queue_detail_analysis_request(&state, Some(&sender));

        assert!(receiver.try_recv().is_err());
        assert!(matches!(
            app.detail_analysis_entry("NVDA"),
            Some(AnalysisCacheEntry::Failed { .. })
        ));
    }

    #[test]
    fn queue_detail_analysis_request_retries_failed_when_input_changes() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");

        let mut stale_fundamentals = fundamentals.clone();
        stale_fundamentals.beta_millis = Some(1_450);
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Failed {
                input: analysis_input_key(&stale_fundamentals),
                message: "temporary Yahoo timeout".to_string(),
            },
        );

        app.queue_detail_analysis_request(&state, Some(&sender));

        let queued =
            recv_with_timeout(&receiver, "failed analysis should retry when inputs change");
        match queued {
            AnalysisControl::Load {
                symbol,
                fundamentals: payload,
                ..
            } => {
                assert_eq!(symbol, "NVDA");
                assert_eq!(payload, fundamentals);
            }
        }
        assert!(matches!(
            app.detail_analysis_entry("NVDA"),
            Some(AnalysisCacheEntry::Loading { .. })
        ));
    }

    #[test]
    fn queue_detail_analysis_request_retries_ready_when_input_changes() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");

        let mut stale_fundamentals = fundamentals.clone();
        stale_fundamentals.total_cash_dollars = Some(35_000_000);
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Ready {
                input: analysis_input_key(&stale_fundamentals),
                analysis: sample_ready_analysis(),
            },
        );

        app.queue_detail_analysis_request(&state, Some(&sender));

        let queued = recv_with_timeout(&receiver, "stale ready analysis should be refreshed");
        match queued {
            AnalysisControl::Load {
                symbol,
                fundamentals: payload,
                ..
            } => {
                assert_eq!(symbol, "NVDA");
                assert_eq!(payload, fundamentals);
            }
        }
        assert!(matches!(
            app.detail_analysis_entry("NVDA"),
            Some(AnalysisCacheEntry::Loading { .. })
        ));
    }

    #[test]
    fn queue_detail_analysis_request_ignores_market_cap_only_changes() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");
        app.queue_detail_analysis_request(&state, Some(&sender));
        let _ = recv_with_timeout(&receiver, "initial analysis request should be queued");

        let mut refreshed = fundamentals.clone();
        refreshed.market_cap_dollars = Some(1_350_000_000);
        state.ingest_fundamentals(refreshed);

        app.queue_detail_analysis_request(&state, Some(&sender));
        assert!(receiver.try_recv().is_err());
    }

    #[test]
    fn background_analysis_skips_failed_entries_with_matching_input() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Failed {
                input: analysis_input_key(&fundamentals),
                message: "DCF unavailable: latest annual free cash flow is not positive."
                    .to_string(),
            },
        );

        app.queue_background_analysis_requests(&state, Some(&sender), &["NVDA".to_string()]);

        assert!(receiver.try_recv().is_err());
        assert!(matches!(
            app.detail_analysis_entry("NVDA"),
            Some(AnalysisCacheEntry::Failed { .. })
        ));
    }

    #[test]
    fn background_analysis_retries_failed_when_input_changes() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();

        let mut stale_fundamentals = fundamentals.clone();
        stale_fundamentals.total_debt_dollars = Some(999_000_000);
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Failed {
                input: analysis_input_key(&stale_fundamentals),
                message: "transient failure".to_string(),
            },
        );

        app.queue_background_analysis_requests(&state, Some(&sender), &["NVDA".to_string()]);

        let queued = recv_with_timeout(
            &receiver,
            "background should retry when input key has changed",
        );
        match queued {
            AnalysisControl::Load { symbol, .. } => {
                assert_eq!(symbol, "NVDA");
            }
        }
    }

    #[test]
    fn detail_analysis_snapshot_reuses_cached_results_when_only_market_cap_changes() {
        let fresh = sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        let mut stale = fresh.clone();
        stale.market_cap_dollars = Some(900_000_000);

        let detail = SymbolDetail {
            symbol: "NVDA".to_string(),
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
            gap_bps: 5_000,
            minimum_gap_bps: 2_000,
            qualification: QualificationStatus::Qualified,
            external_status: ExternalSignalStatus::Supportive,
            external_signal_fair_value_cents: None,
            external_signal_low_fair_value_cents: None,
            external_signal_high_fair_value_cents: None,
            weighted_external_signal_fair_value_cents: None,
            weighted_analyst_count: None,
            external_signal_gap_bps: None,
            external_signal_age_seconds: None,
            external_signal_max_age_seconds: 30,
            analyst_opinion_count: None,
            recommendation_mean_hundredths: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            fundamentals: Some(fresh),
            confidence: ConfidenceBand::High,
            last_sequence: 1,
            update_count: 1,
            is_watched: false,
        };
        let mut app = AppState::default();
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Ready {
                input: analysis_input_key(&stale),
                analysis: sample_ready_analysis(),
            },
        );

        let snapshot = detail_analysis_snapshot(&app, &detail);

        assert_eq!(snapshot.status, "ready");
        assert!(snapshot.analysis.is_some());
    }

    #[test]
    fn detail_analysis_snapshot_ignores_cached_results_for_changed_analysis_inputs() {
        let fresh = sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        let mut stale = fresh.clone();
        stale.beta_millis = Some(1_450);

        let detail = SymbolDetail {
            symbol: "NVDA".to_string(),
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
            gap_bps: 5_000,
            minimum_gap_bps: 2_000,
            qualification: QualificationStatus::Qualified,
            external_status: ExternalSignalStatus::Supportive,
            external_signal_fair_value_cents: None,
            external_signal_low_fair_value_cents: None,
            external_signal_high_fair_value_cents: None,
            weighted_external_signal_fair_value_cents: None,
            weighted_analyst_count: None,
            external_signal_gap_bps: None,
            external_signal_age_seconds: None,
            external_signal_max_age_seconds: 30,
            analyst_opinion_count: None,
            recommendation_mean_hundredths: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            fundamentals: Some(fresh),
            confidence: ConfidenceBand::High,
            last_sequence: 1,
            update_count: 1,
            is_watched: false,
        };
        let mut app = AppState::default();
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Ready {
                input: analysis_input_key(&stale),
                analysis: sample_ready_analysis(),
            },
        );

        let snapshot = detail_analysis_snapshot(&app, &detail);

        assert_eq!(snapshot.status, "idle");
        assert!(snapshot.analysis.is_none());
    }

    #[test]
    fn ticker_detail_renders_fundamentals_section_with_dcf_and_relative_score() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: Some("NVIDIA Corporation".to_string()),
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_950,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "NVDA".to_string(),
            fair_value_cents: 2_000,
            age_seconds: 0,
            low_fair_value_cents: Some(1_600),
            high_fair_value_cents: Some(2_300),
            analyst_opinion_count: Some(24),
            recommendation_mean_hundredths: Some(180),
            strong_buy_count: Some(10),
            buy_count: Some(8),
            hold_count: Some(6),
            sell_count: Some(0),
            strong_sell_count: Some(0),
            weighted_fair_value_cents: Some(1_980),
            weighted_analyst_count: Some(12),
        });
        state.ingest_fundamentals(subject.clone());

        for (index, pe) in [(1, 2_000), (2, 2_200), (3, 2_400), (4, 2_600), (5, 2_800)] {
            let mut peer = sample_fundamentals(
                &format!("P{index}"),
                "technology",
                "Technology",
                "software",
                "Software",
            );
            peer.trailing_pe_hundredths = Some(pe);
            state.ingest_fundamentals(peer);
        }

        let mut app = AppState::default();
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Ready {
                input: analysis_input_key(&subject),
                analysis: sample_ready_analysis(),
            },
        );

        let lines = build_ticker_detail_lines_for_viewport(&state, &app, "NVDA", 140, 80);
        let visible_lines = lines
            .iter()
            .map(|line| visible_text(&line.text))
            .collect::<Vec<_>>();

        assert!(visible_lines.iter().any(|line| line == "FUNDAMENTALS"));
        assert!(
            visible_lines
                .iter()
                .any(|line| line.contains("Proprietary DCF value"))
        );
        assert!(
            visible_lines
                .iter()
                .any(|line| line.contains("DCF bear $14.50  base $18.00  bull $22.50"))
        );
        assert!(
            visible_lines
                .iter()
                .any(|line| line.contains("Relative vs industry Software peers=5"))
        );
    }

    #[test]
    fn parse_runtime_options_appends_custom_symbols_to_a_profile() {
        let options = parse_runtime_options_from(["--profile", "merval", "--symbols", "AAPL,YPF"])
            .expect("profiles should accept extra custom symbols");

        assert_eq!(options.symbols.last().map(String::as_str), Some("YPF"));
        assert!(options.symbols.iter().any(|symbol| symbol == "AAPL"));
        assert_eq!(
            options
                .symbols
                .iter()
                .filter(|symbol| symbol.as_str() == "YPF")
                .count(),
            1
        );
    }

    #[test]
    fn parse_runtime_options_rejects_unknown_profiles() {
        let error = parse_runtime_options_from(["--profile", "unknown-profile"])
            .expect_err("unknown profiles should be rejected");

        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(
            error
                .to_string()
                .contains("Available profiles: sp500, dow, russell")
        );
    }

    #[test]
    fn usage_text_mentions_profile_flag_and_profiles() {
        let usage = usage_text();

        assert!(usage.contains("--profile NAME"));
        assert!(usage.contains("starting universe for this session"));
        assert!(usage.contains("sp500"));
        assert!(usage.contains("merval"));
        assert!(usage.contains("nikkei"));
        assert!(usage.contains("europe"));
        assert!(usage.contains("asia"));
    }

    #[test]
    fn format_symbol_list_truncates_long_universes() {
        let symbols = (0..20)
            .map(|index| format!("LONGSYMBOL{index:02}"))
            .collect::<Vec<_>>();

        let label = format_symbol_list(&symbols);

        assert!(label.contains("(+"));
    }

    #[test]
    fn live_symbol_state_adds_only_new_symbols() {
        let state = LiveSymbolState::new(vec!["AAPL".to_string(), "MSFT".to_string()]);

        assert_eq!(
            (
                state.add_symbols(vec!["MSFT".to_string(), "NVDA".to_string()]),
                state.snapshot(),
            ),
            (
                vec!["NVDA".to_string()],
                vec!["AAPL".to_string(), "MSFT".to_string(), "NVDA".to_string()],
            )
        );
    }

    #[test]
    fn app_state_defaults_to_normal_input_mode() {
        let app = AppState::default();

        assert!(matches!(app.input_mode, InputMode::Normal));
    }

    #[test]
    fn ignores_key_release_events_to_avoid_duplicate_input() {
        let press_event =
            KeyEvent::new_with_kind(KeyCode::Char('n'), KeyModifiers::NONE, KeyEventKind::Press);
        let release_event = KeyEvent::new_with_kind(
            KeyCode::Char('n'),
            KeyModifiers::NONE,
            KeyEventKind::Release,
        );

        assert_eq!(
            (
                should_handle_key_event(&press_event),
                should_handle_key_event(&release_event),
            ),
            (true, false)
        );
    }

    #[test]
    fn issue_center_tracks_active_and_resolved_issues() {
        let mut issue_center = IssueCenter::default();
        issue_center.raise(
            "feed-unavailable",
            IssueSource::Feed,
            IssueSeverity::Error,
            "Live source unavailable",
            "Loaded 0 of 8 tracked symbols.",
        );
        issue_center.resolve("feed-unavailable");

        assert_eq!(
            (
                issue_center.active_issue_count(),
                issue_center.resolved_issue_count(),
                health_status_label(issue_center.health_status()),
            ),
            (0, 1, "healthy")
        );
    }

    #[test]
    fn persistence_status_success_clears_prior_issue() {
        let mut issue_center = IssueCenter::default();

        apply_persistence_status(
            &mut issue_center,
            PersistenceStatusEvent {
                operation: "persist-watchlist",
                error: Some("database is locked".to_string()),
            },
        );
        apply_persistence_status(
            &mut issue_center,
            PersistenceStatusEvent {
                operation: "persist-watchlist",
                error: None,
            },
        );

        assert_eq!(issue_center.active_issue_count(), 0);
        assert_eq!(issue_center.resolved_issue_count(), 1);
    }

    #[test]
    fn live_source_status_raises_a_partial_feed_issue() {
        let mut issue_center = IssueCenter::default();

        apply_live_source_status(
            &mut issue_center,
            &LiveSourceStatus {
                tracked_symbols: 8,
                fresh_symbols: 6,
                stale_symbols: 0,
                degraded_symbols: 2,
                unavailable_symbols: 2,
                last_error: Some("provider timeout".to_string()),
            },
        );

        let issue = issue_center.sorted_entries()[0].clone();

        assert_eq!(
            (
                health_status_label(issue_center.health_status()),
                issue.title,
                issue.active,
                issue.detail.contains("Degraded 2"),
                issue.detail.contains("Unavailable 2"),
            ),
            (
                "degraded",
                "Live source partially degraded".to_string(),
                true,
                true,
                true,
            )
        );
    }

    #[test]
    fn live_source_status_resolves_partial_issue_for_a_healthy_window() {
        let mut issue_center = IssueCenter::default();

        apply_live_source_status(
            &mut issue_center,
            &LiveSourceStatus {
                tracked_symbols: 32,
                fresh_symbols: 32,
                stale_symbols: 0,
                degraded_symbols: 0,
                unavailable_symbols: 0,
                last_error: None,
            },
        );

        assert_eq!(issue_center.active_issue_count(), 0);
        assert_eq!(health_status_label(issue_center.health_status()), "healthy");
    }

    #[test]
    fn live_source_status_keeps_partial_issue_for_a_partial_refresh_window() {
        let mut issue_center = IssueCenter::default();

        apply_live_source_status(
            &mut issue_center,
            &LiveSourceStatus {
                tracked_symbols: 503,
                fresh_symbols: 64,
                stale_symbols: 0,
                degraded_symbols: 0,
                unavailable_symbols: 439,
                last_error: None,
            },
        );

        let issue = issue_center.sorted_entries()[0].clone();
        assert_eq!(
            health_status_label(issue_center.health_status()),
            "degraded"
        );
        assert_eq!(issue.title, "Live source partially degraded");
        assert!(issue.detail.contains("Fresh 64"));
    }

    #[test]
    fn feed_refresh_publishes_symbol_updates_before_final_source_status() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let symbols = vec!["AAPL".to_string(), "MSFT".to_string(), "AMD".to_string()];
        let mut fetch_results = vec![
            Ok(live_feed("AAPL")),
            Ok(super::market_data::ProviderFetchResult {
                symbol: "MSFT".to_string(),
                snapshot: None,
                external_signal: None,
                fundamentals: None,
                coverage: super::market_data::ProviderCoverage {
                    core: super::market_data::ProviderComponentState::Missing,
                    external: super::market_data::ProviderComponentState::Missing,
                    fundamentals: super::market_data::ProviderComponentState::Missing,
                },
                diagnostics: vec![super::market_data::ProviderDiagnostic {
                    component: super::market_data::ProviderComponent::Core,
                    kind: super::market_data::ProviderDiagnosticKind::Missing,
                    detail: "core snapshot is missing target mean price".to_string(),
                    retryable: false,
                }],
            }),
            Ok(live_feed("AMD")),
        ]
        .into_iter();

        assert!(publish_feed_refresh(&publisher, &symbols, None, |_, _| {
            fetch_results
                .next()
                .expect("each symbol should have one fetch result")
        }));

        let first_batch = recv_feed_batch(&receiver, "first feed batch");
        let second_batch = recv_feed_batch(&receiver, "second feed batch");
        let third_batch = recv_feed_batch(&receiver, "third feed batch");
        let final_batch = recv_feed_batch(&receiver, "final feed batch");

        assert_eq!(first_batch.len(), 3);
        assert!(
            matches!(first_batch.first(), Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AAPL")
        );
        assert!(
            matches!(first_batch.get(1), Some(FeedEvent::External(signal)) if signal.symbol == "AAPL")
        );
        assert!(
            matches!(first_batch.get(2), Some(FeedEvent::Fundamentals(fundamentals)) if fundamentals.symbol == "AAPL")
        );

        assert_eq!(second_batch.len(), 1);
        assert!(
            matches!(second_batch.first(), Some(FeedEvent::Coverage(coverage)) if coverage.symbol == "MSFT")
        );

        assert_eq!(third_batch.len(), 3);
        assert!(
            matches!(third_batch.first(), Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AMD")
        );
        assert!(
            matches!(third_batch.get(1), Some(FeedEvent::External(signal)) if signal.symbol == "AMD")
        );
        assert!(
            matches!(third_batch.get(2), Some(FeedEvent::Fundamentals(fundamentals)) if fundamentals.symbol == "AMD")
        );

        assert_eq!(final_batch.len(), 1);
        assert!(matches!(
            final_batch.first(),
            Some(FeedEvent::SourceStatus(super::LiveSourceStatus {
                tracked_symbols: 3,
                fresh_symbols: 2,
                stale_symbols: 0,
                degraded_symbols: 1,
                unavailable_symbols: 0,
                last_error: None,
            }))
        ));

        assert!(receiver.try_recv().is_err());
    }

    #[test]
    fn apply_feed_events_keeps_existing_fundamentals_when_refresh_omits_them() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let refresh_without_fundamentals =
            build_symbol_feed_batch(super::market_data::ProviderFetchResult {
                symbol: "NVDA".to_string(),
                snapshot: Some(MarketSnapshot {
                    symbol: "NVDA".to_string(),
                    company_name: None,
                    profitable: true,
                    market_price_cents: 1_250,
                    intrinsic_value_cents: 1_850,
                }),
                external_signal: None,
                fundamentals: None,
                coverage: super::market_data::ProviderCoverage {
                    core: super::market_data::ProviderComponentState::Fresh,
                    external: super::market_data::ProviderComponentState::Missing,
                    fundamentals: super::market_data::ProviderComponentState::Missing,
                },
                diagnostics: vec![],
            });

        let mut app = AppState::default();
        apply_feed_events(&mut state, &mut app, None, refresh_without_fundamentals);

        assert_eq!(
            state.detail("NVDA").and_then(|detail| detail.fundamentals),
            Some(fundamentals)
        );
    }

    #[test]
    fn weighted_target_refresh_window_rotates_across_symbols() {
        let refreshed = (0..5)
            .filter(|index| should_refresh_weighted_target(*index, 4, 5, 2))
            .collect::<Vec<_>>();

        assert_eq!(
            (refreshed, next_weighted_target_refresh_cursor(4, 5, 2)),
            (vec![0, 4], 1)
        );
    }

    #[test]
    fn weighted_target_refresh_covers_every_symbol_across_windows() {
        let symbol_count = 100usize;
        let refresh_window = 32usize;
        let refresh_budget = 8usize;
        let mut symbol_refresh_cursor = 0usize;
        let mut refreshed_symbols = HashSet::new();

        for _ in 0..25 {
            let window_symbols = (0..refresh_window)
                .map(|offset| (symbol_refresh_cursor + offset) % symbol_count)
                .collect::<Vec<_>>();
            let refreshed_in_window = window_symbols
                .iter()
                .copied()
                .filter(|symbol_index| {
                    should_refresh_weighted_target(
                        *symbol_index,
                        symbol_refresh_cursor,
                        symbol_count,
                        refresh_budget,
                    )
                })
                .collect::<Vec<_>>();

            assert_eq!(refreshed_in_window.len(), refresh_budget);
            refreshed_symbols.extend(refreshed_in_window);
            symbol_refresh_cursor = next_weighted_target_refresh_cursor(
                symbol_refresh_cursor,
                symbol_count,
                refresh_window,
            );
        }

        assert_eq!(refreshed_symbols.len(), symbol_count);
    }

    #[test]
    fn concurrent_refresh_uses_global_indexes_and_window_source_status() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let refresh_plan = FeedRefreshPlan {
            phase_label: "steady",
            total_tracked_symbols: 40,
            retry_symbols: 0,
            concurrency: 1,
            symbols: vec![
                (32, "AAPL".to_string()),
                (33, "MSFT".to_string()),
                (34, "AMD".to_string()),
            ],
            next_symbol_cursor: 35,
        };
        let window_indexes = Arc::new(Mutex::new(Vec::new()));
        let indexes_for_fetch = Arc::clone(&window_indexes);

        let outcome = publish_feed_refresh_concurrently(
            &publisher,
            &refresh_plan,
            None,
            move |symbol_index, symbol| {
                indexes_for_fetch
                    .lock()
                    .expect("symbol indexes should be lockable")
                    .push((symbol_index, symbol.to_string()));
                Ok(live_feed(symbol))
            },
        );
        drop(publisher);

        let first_batch = recv_feed_batch(&receiver, "first feed batch");
        let second_batch = recv_feed_batch(&receiver, "second feed batch");
        let third_batch = recv_feed_batch(&receiver, "third feed batch");

        assert_eq!(
            window_indexes
                .lock()
                .expect("symbol indexes should be readable")
                .clone(),
            vec![
                (32, "AAPL".to_string()),
                (33, "MSFT".to_string()),
                (34, "AMD".to_string()),
            ]
        );
        assert!(matches!(
            outcome,
            Some(super::FeedRefreshOutcome {
                fresh_symbols: 3,
                degraded_symbols: 0,
                unavailable_symbols: 0,
                throttled_errors: 0,
                ..
            })
        ));
        assert!(matches!(
            first_batch.first(),
            Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AAPL"
        ));
        assert!(matches!(
            second_batch.first(),
            Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "MSFT"
        ));
        assert!(matches!(
            third_batch.first(),
            Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AMD"
        ));
        let final_batch = recv_feed_batch(&receiver, "final source status batch");
        assert!(matches!(
            final_batch.first(),
            Some(FeedEvent::SourceStatus(LiveSourceStatus {
                tracked_symbols: 40,
                fresh_symbols: 3,
                stale_symbols: 0,
                degraded_symbols: 0,
                unavailable_symbols: 0,
                last_error: None,
            }))
        ));
    }

    #[test]
    fn retry_classifier_distinguishes_throttle_from_hard_not_found() {
        assert!(is_provider_throttle_error(
            "HTTP status client error (429 Too Many Requests) for url (...)"
        ));
        assert!(is_retryable_feed_error(
            "HTTP status client error (429 Too Many Requests) for url (...)"
        ));
        assert!(!is_retryable_feed_error(
            "HTTP status client error (404 Not Found) for url (...)"
        ));
    }

    #[test]
    fn load_initial_state_recovers_from_invalid_journal_files() {
        let journal_path = unique_test_path("journal");
        fs::write(&journal_path, "not-a-valid-entry\n")
            .expect("test journal fixture should be written");

        let loaded = load_initial_state(&RuntimeOptions {
            journal_file: Some(journal_path.clone()),
            ..RuntimeOptions::default()
        })
        .expect("invalid journal files should fall back to an empty session");

        let _ = fs::remove_file(&journal_path);

        assert_eq!(
            (
                loaded.state.symbol_count(),
                loaded.startup_issues.len(),
                loaded.startup_issues[0].key,
                loaded.startup_issues[0].title,
            ),
            (0, 1, ISSUE_KEY_JOURNAL_RESTORE, "Journal restore failed")
        );
    }

    #[test]
    fn load_initial_state_recovers_from_unreadable_watchlist_paths() {
        let watchlist_path = unique_test_path("watchlist");
        fs::create_dir_all(&watchlist_path)
            .expect("test watchlist fixture directory should be created");

        let loaded = load_initial_state(&RuntimeOptions {
            watchlist_file: Some(watchlist_path.clone()),
            ..RuntimeOptions::default()
        })
        .expect("unreadable watchlist paths should fall back to an empty watchlist");

        let _ = fs::remove_dir(&watchlist_path);

        assert_eq!(
            (
                loaded.state.symbol_count(),
                loaded.startup_issues.len(),
                loaded.startup_issues[0].key,
                loaded.startup_issues[0].title,
            ),
            (
                0,
                1,
                ISSUE_KEY_WATCHLIST_RESTORE,
                "Watchlist restore failed"
            )
        );
    }

    #[test]
    fn load_initial_state_fails_for_invalid_replay_files() {
        let replay_path = unique_test_path("replay");
        fs::write(&replay_path, "not-a-valid-entry\n")
            .expect("test replay fixture should be written");

        let error = load_initial_state(&RuntimeOptions {
            replay_file: Some(replay_path.clone()),
            ..RuntimeOptions::default()
        })
        .err()
        .expect("invalid replay files should stay fatal");

        let _ = fs::remove_file(&replay_path);

        assert_eq!(
            (
                error.kind(),
                error.to_string(),
                error
                    .source()
                    .map(|source| source.to_string().starts_with("invalid journal line 1:")),
            ),
            (
                io::ErrorKind::InvalidData,
                format!("load replay file: {}", replay_path.display()),
                Some(true),
            )
        );
    }

    #[test]
    fn load_initial_state_restores_sqlite_warm_start_state() {
        let state_db = unique_test_path("warm-start.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");

        persistence_handle.replace_tracked_symbols(vec!["AAPL".to_string(), "MSFT".to_string()]);
        persistence_handle.replace_watchlist(vec!["AAPL".to_string()]);
        persistence_handle.replace_issues(vec![persistence::PersistedIssueRecord {
            key: "feed-partial".to_string(),
            source: IssueSource::Feed,
            severity: IssueSeverity::Warning,
            title: "Warm-start issue".to_string(),
            detail: "Restored from sqlite".to_string(),
            count: 1,
            first_seen_event: 1,
            last_seen_event: 1,
            active: true,
        }]);
        let snapshot = MarketSnapshot {
            symbol: "AAPL".to_string(),
            company_name: Some("Apple Inc.".to_string()),
            profitable: true,
            market_price_cents: 18_000,
            intrinsic_value_cents: 24_000,
        };
        let external_signal = ExternalValuationSignal {
            symbol: "AAPL".to_string(),
            fair_value_cents: 23_500,
            age_seconds: 60,
            low_fair_value_cents: Some(21_000),
            high_fair_value_cents: Some(26_000),
            analyst_opinion_count: Some(12),
            recommendation_mean_hundredths: Some(180),
            strong_buy_count: Some(6),
            buy_count: Some(4),
            hold_count: Some(2),
            sell_count: Some(0),
            strong_sell_count: Some(0),
            weighted_fair_value_cents: Some(24_200),
            weighted_analyst_count: Some(10),
        };
        let fundamentals = FundamentalSnapshot {
            symbol: "AAPL".to_string(),
            sector_key: Some("technology".to_string()),
            sector_name: Some("Technology".to_string()),
            industry_key: Some("consumer-electronics".to_string()),
            industry_name: Some("Consumer Electronics".to_string()),
            market_cap_dollars: Some(3_000_000_000_000),
            shares_outstanding: Some(15_000_000_000),
            trailing_pe_hundredths: Some(2850),
            forward_pe_hundredths: Some(2600),
            price_to_book_hundredths: Some(4800),
            return_on_equity_bps: Some(12_000),
            ebitda_dollars: Some(140_000_000_000),
            enterprise_value_dollars: Some(2_950_000_000_000),
            enterprise_to_ebitda_hundredths: Some(2_100),
            total_debt_dollars: Some(110_000_000_000),
            total_cash_dollars: Some(70_000_000_000),
            debt_to_equity_hundredths: Some(180),
            free_cash_flow_dollars: Some(100_000_000_000),
            operating_cash_flow_dollars: Some(120_000_000_000),
            beta_millis: Some(1200),
            trailing_eps_cents: Some(630),
            earnings_growth_bps: Some(900),
        };
        persistence_handle
            .persist_batch(
                Vec::new(),
                vec![persistence::SymbolRevisionInput {
                    symbol: "AAPL".to_string(),
                    evaluated_at: 1_700_000_000,
                    last_sequence: 3,
                    update_count: 3,
                    price_history: vec![
                        discount_screener::PriceHistoryPoint {
                            sequence: 1,
                            market_price_cents: 17_500,
                        },
                        discount_screener::PriceHistoryPoint {
                            sequence: 2,
                            market_price_cents: 17_800,
                        },
                        discount_screener::PriceHistoryPoint {
                            sequence: 3,
                            market_price_cents: 18_000,
                        },
                    ],
                    payload: persistence::EvaluatedSymbolState {
                        snapshot: Some(snapshot),
                        external_signal: Some(external_signal),
                        fundamentals: Some(fundamentals),
                        gap_bps: Some(checked_gap_bps(18_000, 24_000).expect("gap should compute")),
                        qualification: Some(QualificationStatus::Qualified),
                        external_status: Some(ExternalSignalStatus::Supportive),
                        confidence: Some(ConfidenceBand::High),
                        external_gap_bps: Some(
                            checked_gap_bps(18_000, 23_500).expect("external gap should compute"),
                        ),
                        weighted_gap_bps: Some(
                            checked_gap_bps(18_000, 24_200).expect("weighted gap should compute"),
                        ),
                        dcf_analysis: None,
                        dcf_signal: None,
                        dcf_margin_of_safety_bps: None,
                        relative_score: None,
                        chart_summaries: Vec::new(),
                        core_status: sample_metric_group_status(true),
                        fundamentals_status: sample_metric_group_status(true),
                        relative_status: sample_metric_group_status(false),
                        dcf_status: sample_metric_group_status(false),
                        chart_status: sample_metric_group_status(false),
                        is_watched: true,
                    },
                }],
            )
            .expect("symbol revision should persist");
        persistence_handle
            .persist_batch(
                vec![persistence::RawCapture {
                    symbol: "AAPL".to_string(),
                    capture_kind: persistence::CaptureKind::ChartCandles,
                    scope_key: Some(chart_range_label(ChartRange::Year).to_string()),
                    captured_at: 1_700_000_000,
                    payload: persistence::RawCapturePayload::Chart {
                        range: ChartRange::Year,
                        candles: historical_candles(),
                    },
                }],
                Vec::new(),
            )
            .expect("chart capture should persist");
        persistence_handle.shutdown(1_700_000_100);

        let loaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            ..RuntimeOptions::default()
        })
        .expect("sqlite warm-start should load");

        let _ = fs::remove_file(&state_db);

        assert!(loaded.startup_issues.is_empty());
        assert_eq!(loaded.tracked_symbols, super::default_live_symbols());
        assert!(loaded.state.detail("AAPL").is_some());
        assert_eq!(
            loaded.state.price_history("AAPL", 10),
            vec![
                discount_screener::PriceHistoryPoint {
                    sequence: 1,
                    market_price_cents: 17_500,
                },
                discount_screener::PriceHistoryPoint {
                    sequence: 2,
                    market_price_cents: 17_800,
                },
                discount_screener::PriceHistoryPoint {
                    sequence: 3,
                    market_price_cents: 18_000,
                },
            ]
        );
        assert!(loaded.app.is_symbol_stale("AAPL"));
        assert_eq!(loaded.app.issue_center.active_issue_count(), 1);
        assert!(matches!(
            loaded.app.detail_chart_entry("AAPL"),
            Some(super::ChartCacheEntry::Ready { candles }) if candles == &historical_candles()
        ));
        assert!(loaded.app.is_chart_stale("AAPL", ChartRange::Year));
        assert!(loaded.app.chart_summary("AAPL", ChartRange::Year).is_some());
    }

    #[test]
    fn load_initial_state_uses_explicit_symbols_for_the_current_session_only() {
        let state_db = unique_test_path("session-symbols.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        persistence_handle.replace_tracked_symbols(vec!["MSTR".to_string()]);
        persist_sample_symbol_revision(&persistence_handle, "MSTR", 18_000, 24_000);
        persistence_handle.shutdown(1_700_000_100);

        let loaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            symbols: vec!["JPM".to_string()],
            symbols_explicit: true,
            ..RuntimeOptions::default()
        })
        .expect("explicit symbols should define the session universe");

        let _ = fs::remove_file(&state_db);

        assert_eq!(loaded.tracked_symbols, vec!["JPM".to_string()]);
        assert!(loaded.state.detail("MSTR").is_none());
    }

    #[test]
    fn no_arg_startup_uses_sp500_instead_of_a_single_remembered_symbol() {
        let state_db = unique_test_path("default-sp500.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        persistence_handle.replace_tracked_symbols(vec!["MSTR".to_string()]);
        persist_sample_symbol_revision(&persistence_handle, "MSTR", 18_000, 24_000);
        persistence_handle.shutdown(1_700_000_100);

        let reloaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            ..RuntimeOptions::default()
        })
        .expect("no-arg startup should restore the default sp500 universe");

        let _ = fs::remove_file(&state_db);

        assert_eq!(reloaded.tracked_symbols, super::default_live_symbols());
        assert!(
            !reloaded
                .tracked_symbols
                .iter()
                .any(|symbol| symbol == "MSTR")
        );
        assert!(reloaded.state.detail("MSTR").is_none());
    }

    #[test]
    fn load_initial_state_uses_profile_symbols_for_the_current_session_only() {
        let state_db = unique_test_path("session-profile.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        persistence_handle.replace_tracked_symbols(vec!["MSTR".to_string()]);
        persist_sample_symbol_revision(&persistence_handle, "MSTR", 18_000, 24_000);
        persistence_handle.shutdown(1_700_000_100);

        let mut options =
            parse_runtime_options_from(["--profile", "dow-jones"]).expect("profile should parse");
        options.state_db = Some(state_db.clone());

        let loaded = load_initial_state(&options)
            .expect("profile symbols should define the session universe");

        let _ = fs::remove_file(&state_db);

        assert!(loaded.tracked_symbols.iter().any(|symbol| symbol == "JPM"));
        assert!(!loaded.tracked_symbols.iter().any(|symbol| symbol == "MSTR"));
        assert!(loaded.state.detail("MSTR").is_none());
    }

    #[test]
    fn load_initial_state_migrates_v2_sqlite_without_losing_rows() {
        let state_db = unique_test_path("warm-start-v2.sqlite3");
        let _ = fs::remove_file(&state_db);

        let connection = Connection::open(&state_db).expect("sqlite db should open");
        connection
            .execute_batch(
                "\
                PRAGMA user_version = 2;
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
                    fundamentals_json TEXT
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
            .expect("v2 schema should be created");
        connection
            .execute(
                "INSERT INTO tracked_symbol(position, symbol) VALUES (0, 'AAPL')",
                [],
            )
            .expect("tracked symbol should be inserted");
        connection
            .execute(
                "INSERT INTO symbol_latest(
                    symbol, revision_id, evaluated_at, last_sequence, update_count, payload_json, snapshot_json, external_json, fundamentals_json
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
                rusqlite::params![
                    "AAPL",
                    1_i64,
                    1_700_000_000_i64,
                    1_i64,
                    1_i64,
                    "{}",
                    serde_json::to_string(&MarketSnapshot {
                        symbol: "AAPL".to_string(),
                        company_name: None,
                        profitable: true,
                        market_price_cents: 18_000,
                        intrinsic_value_cents: 24_000,
                    })
                    .expect("snapshot should serialize"),
                    Option::<String>::None,
                    Option::<String>::None
                ],
            )
            .expect("latest symbol row should be inserted");
        drop(connection);

        let loaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            ..RuntimeOptions::default()
        })
        .expect("v2 sqlite state should migrate and load");

        let connection = Connection::open(&state_db).expect("sqlite db should reopen");
        let has_price_history_json = connection
            .prepare("PRAGMA table_info(symbol_latest)")
            .expect("table info should prepare")
            .query_map([], |row| row.get::<_, String>(1))
            .expect("table info should query")
            .filter_map(Result::ok)
            .any(|name| name == "price_history_json");
        let _ = fs::remove_file(&state_db);

        assert!(loaded.state.detail("AAPL").is_some());
        assert!(has_price_history_json);
    }

    #[test]
    fn load_initial_state_resets_bad_sqlite_cache_and_keeps_persistence_enabled() {
        let state_db = unique_test_path("broken-warm-start.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let connection = Connection::open(&state_db).expect("sqlite db should open");
        connection
            .execute(
                "INSERT INTO symbol_latest(
                    symbol, revision_id, evaluated_at, last_sequence, update_count, payload_json, snapshot_json, external_json, fundamentals_json
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
                rusqlite::params![
                    "AAPL",
                    1_i64,
                    1_i64,
                    1_i64,
                    1_i64,
                    "{}",
                    "{not-json",
                    Option::<String>::None,
                    Option::<String>::None
                ],
            )
            .expect("corrupt revision row should be written");

        let loaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            ..RuntimeOptions::default()
        })
        .expect("warm-start restore failures should fall back");

        assert_eq!(loaded.startup_issues.len(), 1);
        assert!(loaded.persistence_db_path.is_some());
        assert!(loaded.state.detail("AAPL").is_none());

        let reset = persistence::load_warm_start(&state_db).expect("reset sqlite db should load");
        let _ = fs::remove_file(&state_db);

        assert!(reset.chart_cache.is_empty());
        assert!(reset.symbol_states.is_empty());
        assert!(reset.last_persisted_at.is_none());
    }

    #[test]
    fn reconcile_sqlite_persistence_retries_after_a_busy_write() {
        let state_db = unique_test_path("busy-warm-start.sqlite3");
        let _ = fs::remove_file(&state_db);
        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");

        let lock_connection = Connection::open(&state_db).expect("sqlite db should open");
        lock_connection
            .execute_batch("BEGIN EXCLUSIVE TRANSACTION;")
            .expect("exclusive lock should be acquired");

        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");

        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });

        let mut last_persisted_sequence = 0;
        let app = AppState::default();
        let first_result = reconcile_sqlite_persistence(
            &state,
            &app,
            Some(&persistence_handle),
            &mut last_persisted_sequence,
        );

        assert!(first_result.is_err());
        assert_eq!(last_persisted_sequence, 0);

        lock_connection
            .execute_batch("COMMIT;")
            .expect("exclusive lock should be released");

        let second_result = reconcile_sqlite_persistence(
            &state,
            &app,
            Some(&persistence_handle),
            &mut last_persisted_sequence,
        );
        assert!(second_result.is_ok());
        persistence_handle.shutdown(1_700_000_000);

        let reloaded = persistence::load_warm_start(&state_db).expect("sqlite db should load");
        let _ = fs::remove_file(&state_db);

        assert_eq!(last_persisted_sequence, state.latest_sequence());
        assert!(
            reloaded
                .symbol_states
                .iter()
                .any(|record| record.symbol == "NVDA")
        );
    }

    #[test]
    fn metadata_only_sqlite_updates_do_not_refresh_warm_start_age() {
        let state_db = unique_test_path("metadata-only.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        persistence_handle.replace_tracked_symbols(vec!["AAPL".to_string()]);
        persistence_handle.replace_watchlist(vec!["AAPL".to_string()]);
        persistence_handle.replace_issues(vec![persistence::PersistedIssueRecord {
            key: "feed-partial".to_string(),
            source: IssueSource::Feed,
            severity: IssueSeverity::Warning,
            title: "Warm-start issue".to_string(),
            detail: "Restored from sqlite".to_string(),
            count: 1,
            first_seen_event: 1,
            last_seen_event: 1,
            active: true,
        }]);
        persistence_handle.shutdown(1_700_000_000);

        let loaded = persistence::load_warm_start(&state_db).expect("sqlite db should load");
        let _ = fs::remove_file(&state_db);

        assert_eq!(loaded.last_persisted_at, None);
        assert_eq!(loaded.tracked_symbols, vec!["AAPL".to_string()]);
        assert_eq!(loaded.watchlist, vec!["AAPL".to_string()]);
        assert_eq!(loaded.issues.len(), 1);
    }

    #[test]
    fn reconcile_capture_persistence_raises_and_resolves_sqlite_issue() {
        let state_db = unique_test_path("capture-persist.sqlite3");
        let _ = fs::remove_file(&state_db);
        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");

        let lock_connection = Connection::open(&state_db).expect("sqlite db should open");
        lock_connection
            .execute_batch("BEGIN EXCLUSIVE TRANSACTION;")
            .expect("exclusive lock should be acquired");

        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        let mut app = AppState::default();
        let capture = persistence::RawCapture {
            symbol: "AAPL".to_string(),
            capture_kind: persistence::CaptureKind::ChartCandles,
            scope_key: Some(chart_range_label(ChartRange::Year).to_string()),
            captured_at: 1_700_000_000,
            payload: persistence::RawCapturePayload::Chart {
                range: ChartRange::Year,
                candles: historical_candles(),
            },
        };

        reconcile_capture_persistence(
            &mut app,
            &persistence_handle,
            "persist chart capture",
            vec![capture.clone()],
            Vec::new(),
        );

        assert_eq!(app.issue_center.active_issue_count(), 1);

        lock_connection
            .execute_batch("COMMIT;")
            .expect("exclusive lock should be released");

        reconcile_capture_persistence(
            &mut app,
            &persistence_handle,
            "persist chart capture",
            vec![capture],
            Vec::new(),
        );

        assert_eq!(app.issue_center.active_issue_count(), 0);
        persistence_handle.shutdown(1_700_000_000);
        let _ = fs::remove_file(&state_db);
    }

    #[test]
    fn publish_input_events_forwards_supported_terminal_events() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let key_event =
            KeyEvent::new_with_kind(KeyCode::Char('n'), KeyModifiers::NONE, KeyEventKind::Press);
        let mut events = vec![
            Ok(Event::Key(key_event)),
            Ok(Event::Resize(120, 40)),
            Err(io::Error::new(io::ErrorKind::UnexpectedEof, "stop")),
        ]
        .into_iter();

        publish_input_events(publisher, move || {
            events
                .next()
                .expect("test input sequence should contain a terminating event")
        });

        assert!(matches!(
            (
                receiver.recv().expect("key event should arrive"),
                receiver.recv().expect("resize event should arrive"),
                receiver.recv().expect("fatal event should arrive"),
            ),
            (
                AppEvent::Input(forwarded_key),
                AppEvent::Resize,
                AppEvent::Fatal(error),
            ) if forwarded_key == key_event
                && error.kind() == io::ErrorKind::UnexpectedEof
                && error.to_string() == "read terminal input event"
        ));
    }

    #[test]
    fn publish_input_events_surfaces_reader_failures_with_context() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);

        publish_input_events(publisher, || Err(io::Error::other("input disconnected")));

        assert!(matches!(
            receiver.recv().expect("fatal event should arrive"),
            AppEvent::Fatal(error)
                if error.kind() == io::ErrorKind::Other
                    && error.to_string() == "read terminal input event"
                    && error
                        .source()
                        .map(|source| source.to_string())
                        == Some("input disconnected".to_string())
        ));
    }

    #[test]
    fn feed_refresh_reports_last_error_and_continues_processing_symbols() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let symbols = vec!["AAPL".to_string(), "MSFT".to_string(), "AMD".to_string()];
        let mut fetch_results = vec![
            Ok(live_feed("AAPL")),
            Err(io::Error::other("provider timeout")),
            Ok(live_feed("AMD")),
        ]
        .into_iter();

        assert!(publish_feed_refresh(&publisher, &symbols, None, |_, _| {
            fetch_results
                .next()
                .expect("each symbol should have one fetch result")
        }));

        let first_batch = recv_feed_batch(&receiver, "first feed batch");
        let second_batch = recv_feed_batch(&receiver, "second feed batch");
        let final_batch = recv_feed_batch(&receiver, "final feed batch");

        assert_eq!(
            (
                first_batch.len(),
                second_batch.len(),
                matches!(
                    final_batch.first(),
                    Some(FeedEvent::SourceStatus(LiveSourceStatus {
                        tracked_symbols: 3,
                        fresh_symbols: 2,
                        stale_symbols: 0,
                        degraded_symbols: 0,
                        unavailable_symbols: 1,
                        last_error: Some(last_error),
                    })) if last_error == "provider timeout"
                ),
            ),
            (3, 3, true)
        );
    }

    #[test]
    fn feed_refresh_appends_symbol_failures_to_feed_error_log() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let symbols = vec!["AAPL".to_string(), "MSFT".to_string(), "AMD".to_string()];
        let log_path = unique_test_path("feed-errors.log");
        let logger = FeedErrorLogger::new(log_path.clone());
        let mut fetch_results = vec![
            Ok(live_feed("AAPL")),
            Ok(super::market_data::ProviderFetchResult {
                symbol: "MSFT".to_string(),
                snapshot: None,
                external_signal: None,
                fundamentals: None,
                coverage: super::market_data::ProviderCoverage {
                    core: super::market_data::ProviderComponentState::Missing,
                    external: super::market_data::ProviderComponentState::Missing,
                    fundamentals: super::market_data::ProviderComponentState::Missing,
                },
                diagnostics: vec![super::market_data::ProviderDiagnostic {
                    component: super::market_data::ProviderComponent::Core,
                    kind: super::market_data::ProviderDiagnosticKind::Missing,
                    detail: "core snapshot is missing target mean price".to_string(),
                    retryable: false,
                }],
            }),
            Err(io::Error::other("provider timeout")),
        ]
        .into_iter();

        assert!(publish_feed_refresh(
            &publisher,
            &symbols,
            Some(&logger),
            |_, _| {
                fetch_results
                    .next()
                    .expect("each symbol should have one fetch result")
            }
        ));

        drop(publisher);
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        while let Ok(app_event) = receiver.try_recv() {
            if let AppEvent::FeedBatch(feed_events) = app_event {
                let applied = apply_feed_events(&mut state, &mut app, Some(&logger), feed_events);
                if applied.saw_source_status {
                    super::synthesize_live_source_status(
                        &state,
                        &mut app,
                        Some(&LiveSymbolState::new(symbols.clone())),
                    );
                    super::log_live_source_summary(Some(&logger), app.live_source_status());
                }
            }
        }

        let log_contents =
            fs::read_to_string(&log_path).expect("feed error log should be readable after refresh");
        let _ = fs::remove_file(&log_path);

        assert!(
            log_contents.contains(
                "kind=provider_coverage symbol=MSFT component=core classification=missing"
            )
        );
        assert!(log_contents.contains("kind=provider_error symbol=AMD"));
        assert!(
            log_contents.contains(
                "kind=refresh_summary tracked=3 fresh=1 stale=0 degraded=1 unavailable=2"
            )
        );
    }

    #[test]
    fn feed_loop_retries_client_initialization_on_the_next_refresh() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let live_symbols = LiveSymbolState::new(vec!["AAPL".to_string()]);
        let (control_sender, control_receiver) = mpsc::channel();
        let mut build_attempts = 0usize;

        control_sender
            .send(super::FeedControl::RefreshNow)
            .expect("first refresh should queue");
        control_sender
            .send(super::FeedControl::RefreshNow)
            .expect("second refresh should queue");
        drop(control_sender);

        feed_loop_with_client_factory(publisher, control_receiver, live_symbols, None, move || {
            build_attempts += 1;
            if build_attempts == 1 {
                Err(io::Error::other("boot failed"))
            } else {
                Ok(FakeFeedClient {
                    calls: Arc::new(Mutex::new(Vec::new())),
                    results: Arc::new(Mutex::new(VecDeque::from(vec![Ok(live_feed("AAPL"))]))),
                })
            }
        });

        let first_batch = recv_feed_batch(&receiver, "first feed batch");
        let second_batch = recv_feed_batch(&receiver, "second feed batch");
        let third_batch = recv_feed_batch(&receiver, "third feed batch");

        assert!(matches!(
            first_batch.first(),
            Some(FeedEvent::SourceStatus(LiveSourceStatus {
                tracked_symbols: 1,
                fresh_symbols: 0,
                stale_symbols: 0,
                degraded_symbols: 0,
                unavailable_symbols: 1,
                last_error: Some(last_error),
            })) if last_error == "market data client initialization failed: boot failed"
        ));
        assert!(matches!(
            second_batch.first(),
            Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AAPL"
        ));
        assert!(matches!(
            third_batch.first(),
            Some(FeedEvent::SourceStatus(LiveSourceStatus {
                tracked_symbols: 1,
                fresh_symbols: 1,
                stale_symbols: 0,
                degraded_symbols: 0,
                unavailable_symbols: 0,
                last_error: None,
            }))
        ));
    }

    #[test]
    fn feed_loop_logs_client_initialization_failures() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let live_symbols = LiveSymbolState::new(vec!["AAPL".to_string()]);
        let (control_sender, control_receiver) = mpsc::channel();
        let log_path = unique_test_path("feed-errors-init.log");

        control_sender
            .send(super::FeedControl::RefreshNow)
            .expect("refresh should queue");
        drop(control_sender);

        feed_loop_with_client_factory(
            publisher,
            control_receiver,
            live_symbols,
            Some(FeedErrorLogger::new(log_path.clone())),
            || -> io::Result<FakeFeedClient> { Err(io::Error::other("boot failed")) },
        );

        while receiver.try_recv().is_ok() {}

        let log_contents = fs::read_to_string(&log_path)
            .expect("feed error log should be readable after init failure");
        let _ = fs::remove_file(&log_path);

        assert!(log_contents.contains("kind=client_init_error tracked=1"));
        assert!(log_contents.contains("detail=\"boot failed\""));
    }

    #[test]
    fn feed_loop_can_be_tested_with_an_injected_client_factory() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let symbols = (0..10)
            .map(|index| format!("S{index:02}"))
            .collect::<Vec<_>>();
        let live_symbols = LiveSymbolState::new(symbols.clone());
        let (control_sender, control_receiver) = mpsc::channel();
        let calls = Arc::new(Mutex::new(Vec::new()));
        let results = symbols
            .iter()
            .chain(symbols.iter())
            .map(|symbol| Ok(live_feed(symbol)))
            .collect::<VecDeque<_>>();

        control_sender
            .send(super::FeedControl::RefreshNow)
            .expect("first refresh should queue");
        control_sender
            .send(super::FeedControl::RefreshNow)
            .expect("second refresh should queue");
        drop(control_sender);

        let calls_for_factory = Arc::clone(&calls);
        let results = Arc::new(Mutex::new(results));
        feed_loop_with_client_factory(publisher, control_receiver, live_symbols, None, move || {
            Ok(FakeFeedClient {
                calls: Arc::clone(&calls_for_factory),
                results: Arc::clone(&results),
            })
        });

        let source_status_batches = receiver
            .into_iter()
            .filter_map(|app_event| match app_event {
                AppEvent::FeedBatch(feed_events) => Some(feed_events),
                _ => None,
            })
            .filter(|feed_events| matches!(feed_events.first(), Some(FeedEvent::SourceStatus(_))))
            .count();
        let calls = calls
            .lock()
            .expect("fake client calls should be readable")
            .clone();
        let mut called_symbols = calls
            .iter()
            .map(|(symbol, _)| symbol.clone())
            .collect::<Vec<_>>();
        let mut expected_symbols = symbols.clone();
        called_symbols.sort();
        expected_symbols.sort();

        assert_eq!(source_status_batches, 1);
        assert_eq!(calls.len(), 10);
        assert_eq!(
            calls
                .iter()
                .filter(|(_, refresh_weighted_target)| *refresh_weighted_target)
                .count(),
            8
        );
        assert_eq!(called_symbols, expected_symbols);
    }

    #[test]
    fn reconcile_journal_persistence_keeps_sequence_when_append_fails() {
        let journal_path = unique_test_path("journal-dir");
        fs::create_dir_all(&journal_path)
            .expect("test journal fixture directory should be created");
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut issue_center = IssueCenter::default();
        let mut last_persisted_sequence = 0;

        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });

        reconcile_journal_persistence(
            &state,
            Some(&journal_path),
            &mut last_persisted_sequence,
            &mut issue_center,
        );

        let _ = fs::remove_dir(&journal_path);
        let issue = issue_center.sorted_entries()[0].clone();

        assert_eq!(
            (
                last_persisted_sequence,
                issue_center.active_issue_count(),
                issue.title,
                issue.detail.starts_with("append journal file:"),
            ),
            (0, 1, "Journal persistence failed".to_string(), true)
        );
    }

    #[test]
    fn reconcile_journal_persistence_resolves_issue_after_successful_retry() {
        let bad_journal_path = unique_test_path("journal-dir");
        let good_journal_path = unique_test_path("journal-file");
        fs::create_dir_all(&bad_journal_path)
            .expect("test journal fixture directory should be created");
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut issue_center = IssueCenter::default();
        let mut last_persisted_sequence = 0;

        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });

        reconcile_journal_persistence(
            &state,
            Some(&bad_journal_path),
            &mut last_persisted_sequence,
            &mut issue_center,
        );
        reconcile_journal_persistence(
            &state,
            Some(&good_journal_path),
            &mut last_persisted_sequence,
            &mut issue_center,
        );

        let _ = fs::remove_dir(&bad_journal_path);
        let _ = fs::remove_file(&good_journal_path);

        assert_eq!(
            (
                last_persisted_sequence,
                issue_center.active_issue_count(),
                issue_center.resolved_issue_count(),
            ),
            (1, 0, 1)
        );
    }

    #[test]
    fn normalize_frame_clips_lines_to_the_viewport() {
        let lines = vec![
            RenderLine {
                color: Some(Color::Yellow),
                text: "ABCDEFGHIJ".to_string(),
            },
            RenderLine {
                color: None,
                text: "SECOND".to_string(),
            },
        ];

        assert_eq!(
            normalize_frame(&lines, 5, 1),
            vec![RenderLine {
                color: Some(Color::Yellow),
                text: "ABCDE".to_string(),
            }]
        );
    }

    #[test]
    fn collect_dirty_rows_only_marks_changed_visible_rows() {
        let previous_frame = vec![
            RenderLine {
                color: None,
                text: "same".to_string(),
            },
            RenderLine {
                color: None,
                text: "old".to_string(),
            },
            RenderLine {
                color: None,
                text: "offscreen".to_string(),
            },
        ];
        let next_frame = vec![
            RenderLine {
                color: None,
                text: "same".to_string(),
            },
            RenderLine {
                color: None,
                text: "new".to_string(),
            },
        ];

        assert_eq!(collect_dirty_rows(&previous_frame, &next_frame, 2), vec![1]);
    }

    #[test]
    fn collect_clear_rows_only_clears_visible_stale_rows() {
        assert_eq!(collect_clear_rows(10, 20, 30), (10..20).collect::<Vec<_>>());
        assert_eq!(collect_clear_rows(10, 20, 10), Vec::<usize>::new());
    }

    #[test]
    fn clip_text_to_width_returns_an_empty_string_for_zero_width() {
        assert_eq!(clip_text_to_width("ABC", 0), String::new());
    }

    #[test]
    fn backspace_leaves_input_mode_when_buffer_is_empty() {
        let mut buffer = String::new();

        assert!(should_leave_input_mode_on_backspace(&mut buffer));
    }

    #[test]
    fn backspace_deletes_text_before_leaving_input_mode() {
        let mut buffer = "NV".to_string();

        assert_eq!(
            (should_leave_input_mode_on_backspace(&mut buffer), buffer),
            (false, "N".to_string())
        );
    }

    #[test]
    fn q_exits_from_ticker_detail_overlay() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");

        let result = handle_input_event(
            KeyEvent::new_with_kind(KeyCode::Char('q'), KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &[],
            0,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("q should be handled");

        assert!(matches!(result, LoopControl::Exit));
    }

    #[test]
    fn q_exits_from_issue_log_overlay() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.open_issue_log();

        let result = handle_input_event(
            KeyEvent::new_with_kind(KeyCode::Char('q'), KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &[],
            0,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("q should be handled");

        assert!(matches!(result, LoopControl::Exit));
    }

    #[test]
    fn q_is_still_treated_as_text_while_filtering() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.input_mode = InputMode::FilterSearch(String::new());

        let result = handle_input_event(
            KeyEvent::new_with_kind(KeyCode::Char('q'), KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &[],
            0,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("q should be handled");

        assert!(matches!(result, LoopControl::Continue));
        assert!(matches!(app.input_mode, InputMode::FilterSearch(ref buffer) if buffer == "q"));
    }

    #[test]
    fn ticker_detail_overlay_tracks_the_selected_symbol() {
        let mut app = AppState::default();

        app.open_ticker_detail("NVDA");

        assert_eq!(app.detail_symbol(), Some("NVDA"));
    }

    #[test]
    fn ticker_detail_overlay_can_move_between_rows() {
        let mut app = AppState::default();
        let rows = vec![candidate("ALFA", 3_000), candidate("BETA", 2_000)];
        app.open_ticker_detail("ALFA");

        app.move_ticker_detail_selection(&rows, 1);

        assert_eq!(
            (app.detail_symbol(), &app.overlay_mode),
            (Some("BETA"), &OverlayMode::TickerDetail("BETA".to_string()))
        );
    }

    #[test]
    fn queue_detail_chart_request_uses_default_year_range() {
        let mut app = AppState::default();
        let (sender, receiver) = mpsc::channel();
        app.open_ticker_detail("NVDA");

        app.queue_detail_chart_request(Some(&sender));

        assert!(matches!(
            receiver.recv().expect("chart request should be queued"),
            super::ChartControl::Load {
                symbol,
                range: ChartRange::Year,
                request_id: 1,
                ..
            } if symbol == "NVDA"
        ));
    }

    #[test]
    fn queue_detail_chart_request_skips_ready_cache_entries() {
        let mut app = AppState::default();
        let (sender, receiver) = mpsc::channel();
        app.open_ticker_detail("NVDA");
        app.chart_cache.insert(
            super::ChartCacheKey::new("NVDA", ChartRange::Year),
            super::ChartCacheEntry::Ready {
                candles: historical_candles(),
            },
        );

        app.queue_detail_chart_request(Some(&sender));

        assert!(receiver.try_recv().is_err());
    }

    #[test]
    fn queue_detail_chart_request_refreshes_stale_persisted_cache_entries() {
        let mut app = AppState::default();
        let (sender, receiver) = mpsc::channel();
        let stale_symbols = Vec::<String>::new();
        app.load_warm_start(
            &[persistence::PersistedChartRecord {
                symbol: "NVDA".to_string(),
                range: ChartRange::Year,
                candles: historical_candles(),
                fetched_at: 1_700_000_000,
            }],
            Some(1_700_000_000),
            &stale_symbols,
        );
        app.open_ticker_detail("NVDA");

        app.queue_detail_chart_request(Some(&sender));

        assert!(matches!(
            receiver.recv().expect("stale chart should trigger a refresh"),
            super::ChartControl::Load {
                symbol,
                range: ChartRange::Year,
                request_id: 1,
                ..
            } if symbol == "NVDA"
        ));
        assert!(matches!(
            app.detail_chart_entry("NVDA"),
            Some(super::ChartCacheEntry::Loading {
                previous: Some(candles),
                ..
            }) if !candles.is_empty()
        ));
    }

    #[test]
    fn queue_background_chart_requests_skip_cached_ranges_and_limit_batch_size() {
        let mut app = AppState::default();
        let (sender, receiver) = mpsc::channel();
        let symbols = ["SYM0", "SYM1", "SYM2"]
            .into_iter()
            .map(str::to_string)
            .collect::<Vec<_>>();
        for range in chart_ranges() {
            let key = super::ChartCacheKey::new("SYM0", range);
            let candles = historical_candles();
            app.chart_cache.insert(
                key.clone(),
                super::ChartCacheEntry::Ready {
                    candles: candles.clone(),
                },
            );
            app.chart_summary_cache
                .insert(key, summarize_chart_range(range, 1_700_000_000, &candles));
        }

        app.queue_background_chart_requests(Some(&sender), &symbols);

        let mut queued = Vec::new();
        while let Ok(request) = receiver.try_recv() {
            queued.push(request);
        }

        assert_eq!(queued.len(), BACKGROUND_CHART_REQUEST_BUDGET_PER_CYCLE);
        assert!(queued.iter().all(|request| {
            matches!(
                request,
                super::ChartControl::Load {
                    symbol,
                    kind: super::ChartRequestKind::Background,
                    ..
                } if symbol != "SYM0"
            )
        }));
    }

    #[test]
    fn background_chart_refresh_replaces_warm_start_cache_entry() {
        let mut app = AppState::default();
        let stale_symbols = Vec::<String>::new();
        let stale_candles = historical_candles();
        let refreshed_candles = vec![HistoricalCandle {
            epoch_seconds: 42,
            open_cents: 500,
            high_cents: 575,
            low_cents: 490,
            close_cents: 560,
            volume: 10,
        }];
        let key = super::ChartCacheKey::new("NVDA", ChartRange::Year);
        app.load_warm_start(
            &[persistence::PersistedChartRecord {
                symbol: "NVDA".to_string(),
                range: ChartRange::Year,
                candles: stale_candles.clone(),
                fetched_at: 1_700_000_000,
            }],
            Some(1_700_000_000),
            &stale_symbols,
        );
        app.background_chart_requests.insert(key.clone(), 7);

        let persisted = app.apply_chart_data(super::ChartDataEvent {
            symbol: "NVDA".to_string(),
            range: ChartRange::Year,
            request_id: 7,
            kind: super::ChartRequestKind::Background,
            fetched_at: 1_700_000_100,
            result: Ok(refreshed_candles.clone()),
        });

        assert!(matches!(
            persisted,
            Some(persistence::PersistedChartRecord {
                symbol,
                range: ChartRange::Year,
                candles,
                fetched_at: 1_700_000_100,
            }) if symbol == "NVDA" && candles == refreshed_candles
        ));
        assert!(matches!(
            app.chart_cache.get(&key),
            Some(super::ChartCacheEntry::Ready { candles }) if candles == &refreshed_candles
        ));
        assert!(!app.is_chart_stale("NVDA", ChartRange::Year));
    }

    #[test]
    fn stale_chart_response_does_not_replace_newer_request() {
        let mut app = AppState::default();
        let (sender, _receiver) = mpsc::channel();
        app.open_ticker_detail("NVDA");

        app.queue_detail_chart_request(Some(&sender));
        app.chart_cache
            .remove(&super::ChartCacheKey::new("NVDA", ChartRange::Year));
        app.queue_detail_chart_request(Some(&sender));

        let persisted = app.apply_chart_data(super::ChartDataEvent {
            symbol: "NVDA".to_string(),
            range: ChartRange::Year,
            request_id: 1,
            kind: super::ChartRequestKind::Detail,
            fetched_at: 1_700_000_000,
            result: Ok(vec![HistoricalCandle {
                epoch_seconds: 1,
                open_cents: 1,
                high_cents: 2,
                low_cents: 1,
                close_cents: 2,
                volume: 1,
            }]),
        });

        assert!(persisted.is_none());
        assert!(matches!(
            app.detail_chart_entry("NVDA"),
            Some(super::ChartCacheEntry::Loading { request_id: 2, .. })
        ));
        assert!(app.chart_summary("NVDA", ChartRange::Year).is_none());
    }

    #[test]
    fn chart_loop_publishes_history_results() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let (control_sender, control_receiver) = mpsc::channel();
        let calls = Arc::new(Mutex::new(Vec::new()));

        control_sender
            .send(super::ChartControl::Load {
                symbol: "AMD".to_string(),
                range: ChartRange::Month,
                request_id: 7,
                kind: super::ChartRequestKind::Detail,
            })
            .expect("chart control should accept the request");
        drop(control_sender);

        chart_loop_with_client_factory(publisher, control_receiver, {
            let calls = Arc::clone(&calls);
            move || {
                Ok(FakeChartClient {
                    calls: Arc::clone(&calls),
                    results: Arc::new(Mutex::new(VecDeque::from([Ok(historical_candles())]))),
                })
            }
        });

        assert_eq!(
            calls.lock().expect("calls should be readable").clone(),
            vec![("AMD".to_string(), ChartRange::Month)]
        );
        let chart_event = receiver.recv().expect("chart event should arrive");
        match chart_event {
            AppEvent::ChartData(super::ChartDataEvent {
                symbol,
                range,
                request_id,
                result,
                ..
            }) => {
                assert_eq!(symbol, "AMD");
                assert_eq!(range, ChartRange::Month);
                assert_eq!(request_id, 7);
                assert_eq!(
                    result.expect("chart fetch should succeed").len(),
                    historical_candles().len()
                );
            }
            _ => panic!("expected chart data event"),
        }
    }

    #[test]
    fn history_graph_mode_renders_core_tiles() {
        let mut app = AppState::default();
        app.history_cache
            .insert("AAPL".to_string(), sample_history_records("AAPL", true));

        let lines = build_ticker_history_lines_for_viewport(&app, "AAPL", 140, 28);

        assert!(lines.iter().any(|line| line.text.contains("view=Graphs")));
        assert!(lines.iter().any(|line| line.text.contains("Market price")));
        assert!(
            lines
                .iter()
                .any(|line| line.text.contains("Intrinsic value"))
        );
    }

    #[test]
    fn history_relative_graph_mode_handles_empty_state() {
        let mut app = AppState::default();
        app.history_view.group = HistoryMetricGroup::Relative;
        app.history_cache
            .insert("AAPL".to_string(), sample_history_records("AAPL", false));

        let lines = build_ticker_history_lines_for_viewport(&app, "AAPL", 140, 24);

        assert!(
            lines
                .iter()
                .any(|line| line.text.contains("No graph tiles are available"))
        );
    }

    #[test]
    fn history_graph_layout_switches_between_two_and_one_column_modes() {
        let mut app = AppState::default();
        app.history_cache
            .insert("AAPL".to_string(), sample_history_records("AAPL", true));

        let wide_lines = build_ticker_history_lines_for_viewport(&app, "AAPL", 140, 28);
        let narrow_lines = build_ticker_history_lines_for_viewport(&app, "AAPL", 90, 28);

        assert!(wide_lines.iter().any(|line| {
            line.text.contains("Market price") && line.text.contains("Intrinsic value")
        }));
        assert!(!narrow_lines.iter().any(|line| {
            line.text.contains("Market price") && line.text.contains("Intrinsic value")
        }));
    }

    #[test]
    fn toggle_history_subview_preserves_group_and_window() {
        let mut app = AppState::default();
        app.open_ticker_detail("AAPL");
        app.toggle_detail_tab();
        app.history_view.group = HistoryMetricGroup::Dcf;
        app.history_view.window = HistoryWindow::Year;

        app.toggle_history_subview();

        assert_eq!(app.history_view.subview, HistorySubview::Table);
        assert_eq!(app.history_view.group, HistoryMetricGroup::Dcf);
        assert_eq!(app.history_view.window, HistoryWindow::Year);
    }

    #[test]
    fn export_selected_history_bundle_writes_expected_csv_files() {
        let export_root = unique_test_path("history-export");
        let _ = fs::remove_dir_all(&export_root);

        let mut app = AppState::default();
        app.open_ticker_detail("AAPL");
        app.toggle_detail_tab();
        app.set_history_export_root(export_root.clone());
        app.history_cache
            .insert("AAPL".to_string(), sample_history_records("AAPL", true));

        let metadata =
            export_selected_history_bundle(&mut app, None).expect("history export should succeed");

        let expected_files = [
            "export_metadata.csv",
            "core_wide.csv",
            "fundamentals_wide.csv",
            "relative_wide.csv",
            "dcf_wide.csv",
            "chart_wide.csv",
            "all_tidy.csv",
        ];
        for filename in expected_files {
            assert!(
                metadata.export_dir.join(filename).exists(),
                "{filename} should exist"
            );
        }

        let core_csv =
            fs::read_to_string(metadata.export_dir.join("core_wide.csv")).expect("core csv");
        assert!(core_csv.contains("market_price_usd"));
        assert!(core_csv.contains("intrinsic_value_usd"));
        assert!(core_csv.contains("185.000000"));

        let chart_csv =
            fs::read_to_string(metadata.export_dir.join("chart_wide.csv")).expect("chart csv");
        assert!(chart_csv.contains("d_close_usd"));
        assert!(chart_csv.contains("y10_histogram_usd"));

        let tidy_csv =
            fs::read_to_string(metadata.export_dir.join("all_tidy.csv")).expect("tidy csv");
        assert!(tidy_csv.contains("group_key,metric_key,metric_label,range_key,unit,value"));
        assert!(tidy_csv.contains("core,market_price_usd,Market price,,usd"));
        assert!(tidy_csv.contains("chart,d_close_usd,D Close,d,usd"));

        let _ = fs::remove_dir_all(&export_root);
    }

    #[test]
    fn export_selected_history_bundle_loads_missing_history_from_sqlite() {
        let state_db = unique_test_path("history-export.sqlite3");
        let export_root = unique_test_path("history-export-on-demand");
        let _ = fs::remove_file(&state_db);
        let _ = fs::remove_dir_all(&export_root);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        let record = sample_history_records("AAPL", true)
            .into_iter()
            .next()
            .expect("sample history should exist");
        persistence_handle
            .persist_batch(
                Vec::new(),
                vec![persistence::SymbolRevisionInput {
                    symbol: record.symbol.clone(),
                    evaluated_at: record.evaluated_at,
                    last_sequence: record.last_sequence,
                    update_count: record.update_count,
                    price_history: Vec::new(),
                    payload: record.payload.clone(),
                }],
            )
            .expect("history revision should persist");

        let mut app = AppState::default();
        app.open_ticker_detail("AAPL");
        app.toggle_detail_tab();
        app.set_history_export_root(export_root.clone());

        let metadata = export_selected_history_bundle(&mut app, Some(&persistence_handle))
            .expect("history export should load missing history");

        assert_eq!(metadata.revision_count, 1);
        assert_eq!(app.detail_history("AAPL").len(), 1);
        assert!(metadata.export_dir.join("export_metadata.csv").exists());

        persistence_handle.shutdown(1_700_000_000);
        let _ = fs::remove_file(&state_db);
        let _ = fs::remove_dir_all(&export_root);
    }

    #[test]
    fn qualification_justification_includes_actual_and_threshold_numbers() {
        let lines = qualification_justification_lines(&detail());

        assert_eq!(
            lines,
            vec![
                "Profitability gate: actual=yes  required=yes".to_string(),
                "Internal upside: actual=55.89%  required>=25.00%".to_string(),
                "Internal discount: $96.53 = $269.23 - $172.70".to_string(),
                "Result: qualified because profitable=yes and 55.89% >= 25.00%.".to_string(),
            ]
        );
    }

    #[test]
    fn confidence_justification_includes_external_signal_numbers() {
        let lines = confidence_justification_lines(&detail());

        assert_eq!(
            lines,
            vec![
                "External status: supportive".to_string(),
                "External fair value: $269.23  external upside: 55.89%  support threshold: >=25.00%".to_string(),
                "External signal age: 6s  freshness limit: <=30s".to_string(),
                "Result: high because internal qualification is qualified and external status is supportive.".to_string(),
            ]
        );
    }

    #[test]
    fn formatters_keep_minus_signs_for_small_negative_values() {
        assert_eq!(
            (format_money(-50), format_bps(-50)),
            ("-$0.50".to_string(), "-0.50%".to_string())
        );
    }

    #[test]
    fn compact_dollars_uses_m_b_t_suffixes() {
        assert_eq!(format_compact_dollars(500), "$500");
        assert_eq!(format_compact_dollars(1_500), "$1.5K");
        assert_eq!(format_compact_dollars(86_000_000), "$86.00M");
        assert_eq!(format_compact_dollars(1_500_000_000), "$1.50B");
        assert_eq!(format_compact_dollars(2_500_000_000_000), "$2.50T");
        assert_eq!(format_compact_dollars(-76_326_000), "-$76.33M");
        assert_eq!(format_compact_dollars(-1_200_000_000), "-$1.20B");
    }

    #[test]
    fn candidate_company_label_trims_very_long_names() {
        let label = candidate_company_label(
            "SMCI",
            Some("Super Micro Computer Holdings International and Subsidiaries"),
        );

        assert_eq!(label.chars().count(), CANDIDATE_COMPANY_COLUMN_WIDTH);
        assert!(label.ends_with("..."));
        assert!(label.starts_with("SMCI Super Micro Computer"));
    }

    #[test]
    fn weighted_gap_clamps_large_negative_values() {
        assert_eq!(checked_gap_bps(i64::MAX, 1), Some(i32::MIN));
    }

    #[test]
    fn weighted_gap_rejects_non_positive_targets() {
        assert_eq!(checked_gap_bps(10_000, 0), None);
    }

    #[test]
    fn aggregate_historical_candles_preserves_ohlc_when_compacting_width() {
        let candles = aggregate_historical_candles(
            &[
                HistoricalCandle {
                    epoch_seconds: 1,
                    open_cents: 10_000,
                    high_cents: 11_000,
                    low_cents: 9_000,
                    close_cents: 9_500,
                    volume: 100,
                },
                HistoricalCandle {
                    epoch_seconds: 2,
                    open_cents: 9_500,
                    high_cents: 12_000,
                    low_cents: 9_400,
                    close_cents: 11_500,
                    volume: 200,
                },
                HistoricalCandle {
                    epoch_seconds: 3,
                    open_cents: 11_500,
                    high_cents: 12_500,
                    low_cents: 11_200,
                    close_cents: 11_300,
                    volume: 300,
                },
                HistoricalCandle {
                    epoch_seconds: 4,
                    open_cents: 11_300,
                    high_cents: 13_000,
                    low_cents: 10_800,
                    close_cents: 12_800,
                    volume: 400,
                },
            ],
            2,
        );

        assert_eq!(candles.len(), 2);
        assert_eq!(
            (
                candles[0].open_cents,
                candles[0].high_cents,
                candles[0].low_cents,
                candles[0].close_cents
            ),
            (10_000, 12_000, 9_000, 11_500)
        );
        assert_eq!(
            (
                candles[1].open_cents,
                candles[1].high_cents,
                candles[1].low_cents,
                candles[1].close_cents
            ),
            (11_500, 13_000, 10_800, 12_800)
        );
        assert_eq!((candles[0].volume, candles[1].volume), (300, 700));
        assert!(candles[0].ema_20_cents.is_some());
        assert!(candles[1].macd_cents.is_some());
    }

    #[test]
    fn ema_series_matches_reference_values() {
        let ema_series = compute_ema_series(&[10_000, 20_000, 30_000], 2);

        assert_eq!(ema_series.len(), 3);
        assert_close(ema_series[0].expect("ema should exist"), 10_000.0, 0.001);
        assert_close(ema_series[1].expect("ema should exist"), 16_666.6667, 0.01);
        assert_close(ema_series[2].expect("ema should exist"), 25_555.5556, 0.01);
    }

    #[test]
    fn macd_series_matches_reference_calculation() {
        let closes = [
            10_000, 10_250, 10_500, 10_800, 11_100, 11_400, 11_800, 12_100, 12_500, 12_900, 13_200,
            13_600,
        ];
        let (macd_series, signal_series, histogram_series) = compute_macd_series(&closes);
        let ema_12 = compute_ema_series(&closes, 12);
        let ema_26 = compute_ema_series(&closes, 26);
        let expected_macd = ema_12
            .iter()
            .zip(ema_26.iter())
            .map(|(fast, slow)| fast.zip(*slow).map(|(fast, slow)| fast - slow))
            .collect::<Vec<_>>();

        assert_eq!(macd_series.len(), closes.len());
        assert_eq!(signal_series.len(), closes.len());
        assert_eq!(histogram_series.len(), closes.len());
        assert_close(
            macd_series
                .last()
                .copied()
                .flatten()
                .expect("macd should exist"),
            expected_macd
                .last()
                .copied()
                .flatten()
                .expect("expected macd should exist"),
            0.01,
        );
        let macd_tail = macd_series
            .last()
            .copied()
            .flatten()
            .expect("macd tail should exist");
        let signal_tail = signal_series
            .last()
            .copied()
            .flatten()
            .expect("signal tail should exist");
        assert_close(
            histogram_series
                .last()
                .copied()
                .flatten()
                .expect("histogram should exist"),
            macd_tail - signal_tail,
            0.01,
        );
    }

    #[test]
    fn ticker_detail_prioritizes_chart_and_drops_recent_context_on_short_viewports() {
        let mut state = TerminalState::new(2_000, 30, 16);
        for market_price_cents in [20_500, 20_900, 20_100, 21_300, 20_700, 21_000, 21_500] {
            state.ingest_snapshot(MarketSnapshot {
                symbol: "AMD".to_string(),
                company_name: Some("Advanced Micro Devices, Inc.".to_string()),
                profitable: true,
                market_price_cents,
                intrinsic_value_cents: 28_961,
            });
        }
        state.ingest_external(ExternalValuationSignal {
            symbol: "AMD".to_string(),
            fair_value_cents: 29_050,
            age_seconds: 0,
            low_fair_value_cents: Some(22_000),
            high_fair_value_cents: Some(36_500),
            analyst_opinion_count: Some(46),
            recommendation_mean_hundredths: Some(157),
            strong_buy_count: Some(4),
            buy_count: Some(33),
            hold_count: Some(12),
            sell_count: Some(0),
            strong_sell_count: Some(0),
            weighted_fair_value_cents: Some(22_000),
            weighted_analyst_count: Some(57),
        });

        let mut app = AppState::default();
        app.chart_cache.insert(
            super::ChartCacheKey::new("AMD", ChartRange::Year),
            super::ChartCacheEntry::Ready {
                candles: historical_candles(),
            },
        );
        let lines = build_ticker_detail_lines_for_viewport(&state, &app, "AMD", 96, 24);
        let visible_lines = lines
            .iter()
            .map(|line| visible_text(&line.text))
            .collect::<Vec<_>>();

        assert!(
            visible_lines
                .iter()
                .any(|line| line.starts_with("PRICE CHART  |  1Y"))
        );
        assert!(visible_lines.iter().any(|line| line == "VALUATION MAP"));
        assert!(visible_lines.iter().any(|line| line == "CONSENSUS"));
        assert!(visible_lines.iter().any(|line| line == "EVIDENCE"));
        assert!(!visible_lines.iter().any(|line| line == "RECENT CONTEXT"));
        assert!(
            visible_lines
                .iter()
                .any(|line| line.contains("candles + EMA"))
        );
        assert!(visible_lines.iter().any(|line| line.contains("-- VOLUME ")));
        assert!(visible_lines.iter().any(|line| line.contains("-- MACD ")));
        assert!(visible_lines.iter().any(|line| line.contains("EMA20")));
        assert!(
            visible_lines
                .iter()
                .any(|line| line.contains("Ratings: SB"))
        );
        assert!(!visible_lines.iter().any(|line| line == "QUALIFICATION"));
        assert!(!visible_lines.iter().any(|line| line == "CONFIDENCE"));
        assert!(
            !visible_lines
                .iter()
                .any(|line| line == "RECENT SYMBOL ALERTS")
        );
    }

    #[test]
    fn ticker_detail_compresses_volume_before_dropping_price_and_macd() {
        let mut state = TerminalState::new(2_000, 30, 16);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "AMD".to_string(),
            company_name: Some("Advanced Micro Devices, Inc.".to_string()),
            profitable: true,
            market_price_cents: 20_500,
            intrinsic_value_cents: 28_961,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "AMD".to_string(),
            fair_value_cents: 29_050,
            age_seconds: 0,
            low_fair_value_cents: Some(22_000),
            high_fair_value_cents: Some(36_500),
            analyst_opinion_count: Some(46),
            recommendation_mean_hundredths: Some(157),
            strong_buy_count: Some(4),
            buy_count: Some(33),
            hold_count: Some(12),
            sell_count: Some(0),
            strong_sell_count: Some(0),
            weighted_fair_value_cents: Some(22_000),
            weighted_analyst_count: Some(57),
        });

        let mut app = AppState::default();
        app.chart_cache.insert(
            super::ChartCacheKey::new("AMD", ChartRange::Year),
            super::ChartCacheEntry::Ready {
                candles: historical_candles(),
            },
        );

        let lines = build_ticker_detail_lines_for_viewport(&state, &app, "AMD", 90, 20);
        let visible_lines = lines
            .iter()
            .map(|line| visible_text(&line.text))
            .collect::<Vec<_>>();

        assert!(
            visible_lines
                .iter()
                .any(|line| line.contains("Volume compressed:"))
        );
        assert!(visible_lines.iter().any(|line| line.contains("-- MACD ")));
        assert!(
            visible_lines
                .iter()
                .any(|line| line.contains("candles + EMA"))
        );
    }

    #[test]
    fn ticker_detail_help_and_header_reflect_selected_chart_range() {
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "NVDA".to_string(),
            fair_value_cents: 26_923,
            age_seconds: 6,
            low_fair_value_cents: Some(18_500),
            high_fair_value_cents: Some(32_000),
            analyst_opinion_count: Some(42),
            recommendation_mean_hundredths: Some(185),
            strong_buy_count: Some(20),
            buy_count: Some(10),
            hold_count: Some(8),
            sell_count: Some(3),
            strong_sell_count: Some(1),
            weighted_fair_value_cents: Some(27_850),
            weighted_analyst_count: Some(12),
        });

        let mut app = AppState::default();
        app.set_detail_chart_range(ChartRange::FiveYears);
        app.chart_cache.insert(
            super::ChartCacheKey::new("NVDA", ChartRange::FiveYears),
            super::ChartCacheEntry::Ready {
                candles: historical_candles(),
            },
        );

        let lines = build_ticker_detail_lines(&state, &app, "NVDA");

        assert_eq!(chart_range_label(app.detail_chart_range()), "5Y");
        assert!(lines[0].text.contains("1-6 range"));
        assert!(lines[0].text.contains("[/] cycle"));
        assert!(lines.iter().any(|line| line.text.contains("Chart 5Y")));
        assert!(
            lines
                .iter()
                .any(|line| line.text.starts_with("PRICE CHART  |  5Y"))
        );
    }

    #[test]
    fn target_map_renders_unicode_markers_and_a_separate_legend() {
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "NVDA".to_string(),
            fair_value_cents: 26_923,
            age_seconds: 6,
            low_fair_value_cents: Some(18_500),
            high_fair_value_cents: Some(32_000),
            analyst_opinion_count: Some(42),
            recommendation_mean_hundredths: Some(185),
            strong_buy_count: Some(20),
            buy_count: Some(10),
            hold_count: Some(8),
            sell_count: Some(3),
            strong_sell_count: Some(1),
            weighted_fair_value_cents: Some(27_850),
            weighted_analyst_count: Some(12),
        });

        let lines = build_ticker_detail_lines(&state, &AppState::default(), "NVDA");
        let target_map_line = lines
            .iter()
            .find(|line| line.text.starts_with("Target map:"))
            .expect("target map line should exist");
        let marker_legend_line = lines
            .iter()
            .find(|line| line.text.starts_with("Markers:"))
            .expect("marker legend line should exist");

        assert!(target_map_line.text.contains("$185.00 │"));
        assert!(target_map_line.text.contains('◆'));
        assert!(target_map_line.text.contains('▲'));
        assert!(target_map_line.text.contains('■'));
        assert!(target_map_line.text.ends_with("│ $320.00"));
        assert_eq!(
            marker_legend_line.text,
            "Markers: ● price<low  ◆ weighted  ▲ mean  ■ median"
        );
    }

    #[test]
    fn main_screen_shows_all_high_confidence_candidates_when_under_cap() {
        let mut state = TerminalState::new(2_000, 30, 8);

        for index in 0..13 {
            let symbol = format!("H{:02}", index);
            state.ingest_snapshot(MarketSnapshot {
                symbol: symbol.clone(),
                company_name: None,
                profitable: true,
                market_price_cents: 10_000,
                intrinsic_value_cents: 15_000,
            });
            state.ingest_external(ExternalValuationSignal {
                symbol,
                fair_value_cents: 13_000,
                age_seconds: 0,
                low_fair_value_cents: None,
                high_fair_value_cents: None,
                analyst_opinion_count: None,
                recommendation_mean_hundredths: None,
                strong_buy_count: None,
                buy_count: None,
                hold_count: None,
                sell_count: None,
                strong_sell_count: None,
                weighted_fair_value_cents: None,
                weighted_analyst_count: None,
            });
        }

        state.ingest_snapshot(MarketSnapshot {
            symbol: "LOW".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 10_000,
            intrinsic_value_cents: 11_000,
        });

        let app = AppState::default();
        let rows = app.visible_rows(&state);

        assert_eq!(
            (
                rows.len(),
                rows.iter()
                    .all(|row| row.confidence == ConfidenceBand::High)
            ),
            (13, true)
        );
    }

    #[test]
    fn main_screen_caps_candidates_to_preserve_layout() {
        let mut state = TerminalState::new(2_000, 30, 8);

        for index in 0..25 {
            let symbol = format!("H{:02}", index);
            state.ingest_snapshot(MarketSnapshot {
                symbol: symbol.clone(),
                company_name: None,
                profitable: true,
                market_price_cents: 10_000,
                intrinsic_value_cents: 15_000,
            });
            state.ingest_external(ExternalValuationSignal {
                symbol,
                fair_value_cents: 13_000,
                age_seconds: 0,
                low_fair_value_cents: None,
                high_fair_value_cents: None,
                analyst_opinion_count: None,
                recommendation_mean_hundredths: None,
                strong_buy_count: None,
                buy_count: None,
                hold_count: None,
                sell_count: None,
                strong_sell_count: None,
                weighted_fair_value_cents: None,
                weighted_analyst_count: None,
            });
        }

        let app = AppState::default();
        let rows = app.visible_rows(&state);

        assert_eq!(rows.len(), MAX_VISIBLE_ROWS);
    }

    #[test]
    fn main_screen_preserves_selected_detail_on_short_viewports() {
        let visible_lines = ranked_main_view_lines_for_viewport(96, 18);

        assert_eq!(
            (
                visible_lines.iter().any(|line| line == "TOP CANDIDATES"),
                visible_lines.iter().any(|line| line == "DETAIL"),
                visible_lines.iter().any(|line| line.starts_with("Symbol:")),
            ),
            (true, true, true)
        );
    }

    #[test]
    fn main_screen_drops_alerts_and_tape_before_selected_detail() {
        let visible_lines = ranked_main_view_lines_for_viewport(96, 20);

        assert_eq!(
            (
                visible_lines.iter().any(|line| line == "DETAIL"),
                visible_lines.iter().any(|line| line == "ALERTS"),
                visible_lines.iter().any(|line| line == "RECENT TAPE"),
            ),
            (true, false, false)
        );
    }

    #[test]
    fn main_screen_uses_a_compact_header_on_narrow_viewports() {
        let visible_lines = ranked_main_view_lines_for_viewport(96, 20);

        assert_eq!(
            (
                visible_lines
                    .first()
                    .map(|line| line.contains("q quit"))
                    .unwrap_or(false),
                visible_lines
                    .first()
                    .map(|line| line.contains("space pause"))
                    .unwrap_or(false),
            ),
            (true, true)
        );
    }

    #[test]
    fn main_screen_uses_compact_status_and_prompt_on_narrow_viewports() {
        let visible_lines = ranked_main_view_lines_for_viewport(96, 20);

        assert_eq!(
            (
                visible_lines
                    .iter()
                    .any(|line| line.contains("Pending:") && line.contains("Rate:")),
                visible_lines
                    .iter()
                    .any(|line| line.contains("Ctrl+C quit")),
            ),
            (true, true)
        );
    }

    #[test]
    fn main_screen_switches_to_compact_live_strings_before_mid_width_clipping() {
        let visible_lines = ranked_main_view_lines_for_viewport(120, 20);

        assert_eq!(
            (
                visible_lines.first().map(String::as_str),
                visible_lines.get(1).map(String::as_str),
            ),
            (
                Some("DISCOUNT TERMINAL  |  d detail  / filter  s symbol  space pause  q quit",),
                Some("Mode: live  Feed: running  Tracked: 25  Loaded: 25  Pending: 0  Rate: 0/s"),
            )
        );
    }

    #[test]
    fn narrow_filter_and_symbol_prompts_use_compact_variants() {
        let mut filter_app = AppState::default();
        filter_app.input_mode = InputMode::FilterSearch("NVDA".to_string());
        let mut symbol_app = AppState::default();
        symbol_app.input_mode = InputMode::SymbolSearch("AMD".to_string());

        assert_eq!(
            (
                input_prompt(&filter_app, true, 80),
                input_prompt(&symbol_app, true, 80)
            ),
            (
                "Filter: 'NVDA'  Enter apply  Esc cancel  Backspace edit/back".to_string(),
                "Symbol: 'AMD'  Enter add  Esc cancel  Backspace edit/back".to_string(),
            )
        );
    }

    #[test]
    fn ticker_detail_uses_full_filtered_set_when_main_screen_is_capped() {
        let mut state = TerminalState::new(2_000, 30, 8);

        for index in 0..25 {
            let symbol = format!("H{:02}", index);
            state.ingest_snapshot(MarketSnapshot {
                symbol: symbol.clone(),
                company_name: None,
                profitable: true,
                market_price_cents: 10_000,
                intrinsic_value_cents: 15_000,
            });
            state.ingest_external(ExternalValuationSignal {
                symbol,
                fair_value_cents: 13_000,
                age_seconds: 0,
                low_fair_value_cents: None,
                high_fair_value_cents: None,
                analyst_opinion_count: None,
                recommendation_mean_hundredths: None,
                strong_buy_count: None,
                buy_count: None,
                hold_count: None,
                sell_count: None,
                strong_sell_count: None,
                weighted_fair_value_cents: None,
                weighted_analyst_count: None,
            });
        }

        let filtered_rows = filtered_symbol_rows(&state, &ViewFilter::default());
        let lines = build_ticker_detail_lines(&state, &AppState::default(), "H24");
        let position_line = lines
            .iter()
            .find(|line| line.text.contains("Position:"))
            .expect("ticker detail position line should exist");

        assert_eq!(filtered_rows.len(), 25);
        assert!(position_line.text.contains("Position: 25/25"));
    }

    #[test]
    fn main_screen_detail_summary_formats_true_upside() {
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "NVDA".to_string(),
            fair_value_cents: 26_923,
            age_seconds: 0,
            low_fair_value_cents: None,
            high_fair_value_cents: None,
            analyst_opinion_count: None,
            recommendation_mean_hundredths: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            weighted_fair_value_cents: None,
            weighted_analyst_count: None,
        });

        let app = AppState::default();
        let rows = app.visible_rows(&state);
        let lines = build_screen_lines(&state, &rows, 0, 0, false, &app, None);
        let detail_line = lines
            .iter()
            .find(|line| line.text.starts_with("Price: "))
            .expect("detail summary line should exist");

        assert!(detail_line.text.contains("Upside: 55.89%"));
    }

    #[test]
    fn candidate_table_header_aligns_with_row_columns() {
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "A".to_string(),
            company_name: Some("Agilent Technologies, Inc.".to_string()),
            profitable: true,
            market_price_cents: 11_234,
            intrinsic_value_cents: 16_329,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "A".to_string(),
            fair_value_cents: 16_329,
            age_seconds: 0,
            low_fair_value_cents: None,
            high_fair_value_cents: None,
            analyst_opinion_count: None,
            recommendation_mean_hundredths: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            weighted_fair_value_cents: None,
            weighted_analyst_count: None,
        });

        let app = AppState::default();
        let rows = app.visible_rows(&state);
        let lines = build_screen_lines(&state, &rows, 0, 0, false, &app, None);
        let header_line = lines
            .iter()
            .find(|line| line.text.contains("Ticker / Company"))
            .expect("candidate header should exist");
        let row_line = lines
            .iter()
            .find(|line| line.text.starts_with(">   0"))
            .expect("selected candidate row should exist");

        assert_eq!(
            header_line.text.find("Ticker / Company"),
            row_line.text.find("A Agilent Technologies, Inc.")
        );
        assert_eq!(
            header_line.text.find("Confidence"),
            row_line.text.find("high")
        );
    }

    #[test]
    fn gap_meter_uses_block_fill_with_threshold_and_actual_markers() {
        let meter = gap_meter(2_500, 2_000, 12);

        assert!(meter.starts_with('['));
        assert!(meter.ends_with(']'));
        assert!(meter.contains('│'));
        assert!(meter.contains('●') || meter.contains('◆'));
        assert!(meter.contains('█'));
        assert!(meter.contains('░'));
    }

    #[test]
    fn analyst_consensus_lines_include_target_range_and_rating_distribution() {
        let lines = analyst_consensus_lines(&detail());

        assert_eq!(lines.len(), 3);
        assert_eq!(lines[0], "Target range width: $135.00 = $320.00 - $185.00");
        assert_eq!(
            lines[1],
            "Analysts: 42  Recommendation mean: 1.85 (1.00=strong buy, 5.00=strong sell)"
        );
        assert_eq!(
            lines[2],
            "Ratings: strong buy 20  buy 10  hold 8  sell 3  strong sell 1"
        );
    }
}
