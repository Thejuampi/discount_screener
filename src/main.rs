mod market_data;

use std::collections::HashMap;
use std::collections::VecDeque;
use std::error::Error;
use std::fmt;
use std::io;
use std::io::ErrorKind;
use std::io::Stdout;
use std::io::Write;
use std::panic;
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
use discount_screener::MarketSnapshot;
use discount_screener::QualificationStatus;
use discount_screener::SymbolDetail;
use discount_screener::TapeEvent;
use discount_screener::TerminalState;
use discount_screener::ViewFilter;
use market_data::ChartRange;
use market_data::DEFAULT_POLL_INTERVAL;
use market_data::HistoricalCandle;
use market_data::MarketDataClient;
use market_data::default_live_symbols;

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
const INLINE_STYLE_MARKER: char = '\u{001f}';
const DEFAULT_FEED_ERROR_LOG_FILE: &str = "feed-errors.log";
const ISSUE_TOAST_DURATION: Duration = Duration::from_secs(6);
const EVENT_RATE_WINDOW: Duration = Duration::from_secs(1);
const ISSUE_KEY_FEED_UNAVAILABLE: &str = "feed-unavailable";
const ISSUE_KEY_FEED_PARTIAL: &str = "feed-partial";
const ISSUE_KEY_JOURNAL_PERSISTENCE: &str = "journal-persistence";
const ISSUE_KEY_WATCHLIST_PERSISTENCE: &str = "watchlist-persistence";
const ISSUE_KEY_JOURNAL_RESTORE: &str = "journal-restore";
const ISSUE_KEY_WATCHLIST_RESTORE: &str = "watchlist-restore";

#[derive(Clone)]
enum FeedEvent {
    Snapshot(MarketSnapshot),
    External(ExternalValuationSignal),
    SourceStatus(LiveSourceStatus),
}

#[derive(Clone)]
struct LiveSourceStatus {
    tracked_symbols: usize,
    loaded_symbols: usize,
    unsupported_symbols: usize,
    error_symbols: usize,
    last_error: Option<String>,
}

enum FeedControl {
    RefreshNow,
}

enum ChartControl {
    Load {
        symbol: String,
        range: ChartRange,
        request_id: u64,
    },
}

struct ChartDataEvent {
    symbol: String,
    range: ChartRange,
    request_id: u64,
    result: io::Result<Vec<HistoricalCandle>>,
}

enum AppEvent {
    Input(KeyEvent),
    Resize,
    FeedBatch(Vec<FeedEvent>),
    ChartData(ChartDataEvent),
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

#[derive(Default)]
struct RuntimeOptions {
    smoke: bool,
    replay_file: Option<PathBuf>,
    journal_file: Option<PathBuf>,
    watchlist_file: Option<PathBuf>,
    symbols: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum FeedFailureKind {
    IncompleteCoverage,
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
                FeedFailureKind::IncompleteCoverage => "incomplete_coverage",
                FeedFailureKind::ProviderError => "provider_error",
            },
            symbol,
            sanitize_feed_log_text(detail),
        ))
    }

    fn log_refresh_summary(
        &self,
        tracked_symbols: usize,
        loaded_symbols: usize,
        unsupported_symbols: usize,
        error_symbols: usize,
        last_error: Option<&str>,
    ) -> io::Result<()> {
        let mut line = format!(
            "ts={} kind=refresh_summary tracked={} loaded={} incomplete={} errors={}",
            unix_timestamp_seconds(),
            tracked_symbols,
            loaded_symbols,
            unsupported_symbols,
            error_symbols,
        );

        if let Some(last_error) = last_error {
            line.push_str(&format!(
                " last_error=\"{}\"",
                sanitize_feed_log_text(last_error),
            ));
        }

        self.append_line(line)
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
    status_message: Option<String>,
    issue_center: IssueCenter,
    overlay_mode: OverlayMode,
    issue_log_selected: usize,
    detail_chart_range: ChartRange,
    chart_cache: HashMap<ChartCacheKey, ChartCacheEntry>,
    next_chart_request_id: u64,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            paused: false,
            selected_symbol: None,
            view_filter: ViewFilter::default(),
            input_mode: InputMode::Normal,
            pending_feed: VecDeque::new(),
            status_message: None,
            issue_center: IssueCenter::default(),
            overlay_mode: OverlayMode::None,
            issue_log_selected: 0,
            detail_chart_range: ChartRange::Year,
            chart_cache: HashMap::new(),
            next_chart_request_id: 1,
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

    fn open_issue_log(&mut self) {
        self.overlay_mode = OverlayMode::IssueLog;
        self.clamp_issue_log_selection();
    }

    fn open_ticker_detail(&mut self, symbol: &str) {
        self.overlay_mode = OverlayMode::TickerDetail(symbol.to_string());
        self.selected_symbol = Some(symbol.to_string());
    }

    fn close_overlay(&mut self) {
        self.overlay_mode = OverlayMode::None;
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

        self.queue_chart_request(chart_control_sender, &symbol, self.detail_chart_range);
    }

    fn queue_chart_request(
        &mut self,
        chart_control_sender: Option<&mpsc::Sender<ChartControl>>,
        symbol: &str,
        range: ChartRange,
    ) {
        let Some(chart_control_sender) = chart_control_sender else {
            return;
        };
        let key = ChartCacheKey::new(symbol, range);

        if matches!(
            self.chart_cache.get(&key),
            Some(ChartCacheEntry::Ready { .. } | ChartCacheEntry::Loading { .. })
        ) {
            return;
        }

        let previous = self
            .chart_cache
            .get(&key)
            .and_then(ChartCacheEntry::cached_candles)
            .map(|candles| candles.to_vec());
        let request_id = self.next_chart_request_id;
        self.next_chart_request_id = self.next_chart_request_id.saturating_add(1);
        self.chart_cache.insert(
            key,
            ChartCacheEntry::Loading {
                request_id,
                previous,
            },
        );

        if chart_control_sender
            .send(ChartControl::Load {
                symbol: symbol.to_string(),
                range,
                request_id,
            })
            .is_err()
        {
            self.chart_cache.insert(
                ChartCacheKey::new(symbol, range),
                ChartCacheEntry::Failed {
                    message: "chart worker channel disconnected".to_string(),
                    previous: None,
                },
            );
        }
    }

    fn apply_chart_data(&mut self, event: ChartDataEvent) {
        let key = ChartCacheKey::new(&event.symbol, event.range);
        let Some(current_entry) = self.chart_cache.get(&key) else {
            return;
        };
        let ChartCacheEntry::Loading {
            request_id,
            previous,
        } = current_entry
        else {
            return;
        };
        if *request_id != event.request_id {
            return;
        }

        let previous = previous.clone();
        let next_entry = match event.result {
            Ok(candles) => ChartCacheEntry::Ready { candles },
            Err(error) => ChartCacheEntry::Failed {
                message: error.to_string(),
                previous,
            },
        };
        self.chart_cache.insert(key, next_entry);
    }

    fn detail_chart_entry(&self, symbol: &str) -> Option<&ChartCacheEntry> {
        self.chart_cache
            .get(&ChartCacheKey::new(symbol, self.detail_chart_range))
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
    previous_frame: Vec<RenderLine>,
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
        let next_frame = normalize_frame(lines, viewport_width, viewport_height);
        let dirty_rows = collect_dirty_rows(&self.previous_frame, &next_frame, viewport_height);
        let clear_rows =
            collect_clear_rows(next_frame.len(), self.last_painted_rows, viewport_height);

        if dirty_rows.is_empty() && clear_rows.is_empty() {
            self.previous_frame = next_frame;
            if viewport_height >= self.last_painted_rows {
                self.last_painted_rows = self.previous_frame.len();
            }
            return Ok(());
        }

        queue!(stdout, BeginSynchronizedUpdate)
            .map_err(|error| with_io_context(error, "begin synchronized terminal update"))?;

        for row_index in dirty_rows {
            paint_row(stdout, row_index, next_frame.get(row_index))?;
        }

        for row_index in clear_rows {
            paint_row(stdout, row_index, None)?;
        }

        queue!(stdout, EndSynchronizedUpdate)
            .map_err(|error| with_io_context(error, "finish synchronized terminal update"))?;
        stdout
            .flush()
            .map_err(|error| with_io_context(error, "flush terminal output"))?;

        self.previous_frame = next_frame;
        if viewport_height >= self.last_painted_rows {
            self.last_painted_rows = self.previous_frame.len();
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
                take_panic_report()
                    .unwrap_or_else(|| "panic: application aborted without details".to_string())
            );
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

        if MAIN_THREAD_ID
            .get()
            .map(|thread_id| *thread_id != thread::current().id())
            .unwrap_or(true)
        {
            eprintln!("{report}");
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

    format!("panic: {payload}\nlocation: {location}")
}

fn take_panic_report() -> Option<String> {
    PANIC_REPORT
        .get_or_init(|| Mutex::new(None))
        .lock()
        .ok()
        .and_then(|mut slot| slot.take())
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
    let live_mode = options.replay_file.is_none();
    let LoadedState {
        mut state,
        startup_issues,
    } = load_initial_state(&options)?;
    let mut app = AppState::default();
    apply_startup_issues(&mut app.issue_center, startup_issues);
    let mut last_persisted_sequence = state.latest_sequence();
    let live_symbols = live_mode.then(|| LiveSymbolState::new(options.symbols.clone()));
    let feed_error_logger =
        live_mode.then(|| FeedErrorLogger::new(PathBuf::from(DEFAULT_FEED_ERROR_LOG_FILE)));
    let (app_event_sender, app_event_receiver) = mpsc::channel();
    let app_event_publisher = AppEventPublisher::new(app_event_sender);
    install_shutdown_publisher(app_event_publisher.clone())
        .map_err(|error| with_io_context(error, "install shutdown signal handler"))?;

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
                    options.watchlist_file.as_ref(),
                )? {
                    break;
                }

                if was_paused && !app.paused && !app.pending_feed.is_empty() {
                    let pending_feed = std::mem::take(&mut app.pending_feed);
                    let applied_events =
                        apply_feed_events(&mut state, &mut app.issue_center, pending_feed);
                    if applied_events > 0 {
                        rate_tracker.record_batch(applied_events, Instant::now());
                    }
                    reconcile_journal_persistence(
                        &state,
                        options.journal_file.as_ref(),
                        &mut last_persisted_sequence,
                        &mut app.issue_center,
                    );
                }
            }
            AppEvent::Resize => {}
            AppEvent::FeedBatch(feed_events) => {
                if app.paused {
                    enqueue_paused_feed_batch(&mut app, feed_events);
                } else {
                    let applied_events =
                        apply_feed_events(&mut state, &mut app.issue_center, feed_events);
                    if applied_events > 0 {
                        rate_tracker.record_batch(applied_events, Instant::now());
                    }
                    reconcile_journal_persistence(
                        &state,
                        options.journal_file.as_ref(),
                        &mut last_persisted_sequence,
                        &mut app.issue_center,
                    );
                }
            }
            AppEvent::ChartData(event) => {
                app.apply_chart_data(event);
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
        return Err(runtime_error);
    }

    let save_result = if let Some(journal_file) = options.journal_file.as_ref() {
        let journal_result = state
            .save_journal_file(journal_file)
            .map_err(|error| with_path_context(error, "save journal file", journal_file));
        let watchlist_result =
            save_watchlist_if_configured(&state, options.watchlist_file.as_ref());
        journal_result.and(watchlist_result)
    } else {
        save_watchlist_if_configured(&state, options.watchlist_file.as_ref())
    };

    drop(terminal_guard);
    save_result
}

fn install_shutdown_publisher(publisher: AppEventPublisher) -> io::Result<()> {
    ctrlc::set_handler(move || {
        let _ = publisher.publish(AppEvent::Shutdown);
    })
    .map_err(io::Error::other)
}

fn spawn_input_publisher(publisher: AppEventPublisher) {
    thread::spawn(move || publish_input_events(publisher, event::read));
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
    thread::spawn(move || feed_loop(publisher, control_receiver, live_symbols, feed_error_logger));
    thread::spawn(move || feed_schedule_loop(scheduler_sender));
    let _ = initial_sender.send(FeedControl::RefreshNow);
    control_sender
}

fn spawn_chart_publisher(publisher: AppEventPublisher) -> mpsc::Sender<ChartControl> {
    let (control_sender, control_receiver) = mpsc::channel();
    thread::spawn(move || chart_loop(publisher, control_receiver));
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
    ) -> io::Result<Option<market_data::LiveSymbolFeed>>;
}

impl LiveFeedClient for MarketDataClient {
    fn fetch_symbol_with_options(
        &self,
        symbol: &str,
        refresh_weighted_target: bool,
    ) -> io::Result<Option<market_data::LiveSymbolFeed>> {
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

fn chart_loop(publisher: AppEventPublisher, control_receiver: mpsc::Receiver<ChartControl>) {
    chart_loop_with_client_factory(publisher, control_receiver, MarketDataClient::new);
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
            result: client.fetch_historical_candles(&symbol, range),
        })) {
            return;
        }
    }
}

fn apply_feed_events(
    state: &mut TerminalState,
    issue_center: &mut IssueCenter,
    feed_events: impl IntoIterator<Item = FeedEvent>,
) -> usize {
    let mut applied_events = 0usize;

    for feed_event in feed_events {
        match feed_event {
            FeedEvent::Snapshot(snapshot) => {
                state.ingest_snapshot(snapshot);
                applied_events += 1;
            }
            FeedEvent::External(signal) => {
                state.ingest_external(signal);
                applied_events += 1;
            }
            FeedEvent::SourceStatus(source_status) => {
                apply_live_source_status(issue_center, source_status);
            }
        }
    }

    applied_events
}

fn enqueue_paused_feed_batch(app: &mut AppState, feed_events: Vec<FeedEvent>) {
    for feed_event in feed_events {
        match feed_event {
            FeedEvent::SourceStatus(source_status) => {
                apply_live_source_status(&mut app.issue_center, source_status);
            }
            other_event => {
                app.pending_feed.push_back(other_event);
            }
        }
    }
}

fn reconcile_journal_persistence(
    state: &TerminalState,
    journal_file: Option<&PathBuf>,
    last_persisted_sequence: &mut usize,
    issue_center: &mut IssueCenter,
) {
    match persist_new_journal_entries(state, journal_file, last_persisted_sequence) {
        Ok(()) => {
            issue_center.resolve(ISSUE_KEY_JOURNAL_PERSISTENCE);
        }
        Err(error) => {
            issue_center.raise(
                ISSUE_KEY_JOURNAL_PERSISTENCE,
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
    watchlist_file: Option<&PathBuf>,
) -> io::Result<LoopControl> {
    if is_force_quit_key(&key_event) {
        return Ok(LoopControl::Exit);
    }

    if handle_overlay_key(app, state, &key_event, chart_control_sender, watchlist_file)? {
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
                } else {
                    app.set_status_message("Select a ticker to open the detail screen.");
                }
            }
            KeyCode::Char('w') => {
                if let Some(row) = rows.get(selected_index) {
                    state.toggle_watchlist(&row.symbol);
                    match save_watchlist_if_configured(state, watchlist_file) {
                        Ok(()) => {
                            app.issue_center.resolve(ISSUE_KEY_WATCHLIST_PERSISTENCE);
                        }
                        Err(error) => {
                            app.issue_center.raise(
                                ISSUE_KEY_WATCHLIST_PERSISTENCE,
                                IssueSource::Persistence,
                                IssueSeverity::Warning,
                                "Watchlist persistence failed",
                                error.to_string(),
                            );
                        }
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
                track_symbols_from_query(&symbol_query, app, live_symbols, feed_control_sender);
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
            return build_ticker_detail_lines_for_viewport(
                state,
                app,
                symbol,
                viewport_width,
                viewport_height,
            );
        }
        OverlayMode::None => {}
    }

    let selected_row = rows.get(selected_index);
    let selected_detail = selected_row.and_then(|row| state.detail(&row.symbol));
    let mut lines = Vec::new();
    let tracked_count = live_symbols.map(|symbols| symbols.count()).unwrap_or(0);
    let health_status = app.issue_center.health_status();
    let active_issue_count = app.issue_center.active_issue_count();

    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: if live_mode {
            "DISCOUNT TERMINAL  |  j/k move  |  d detail  |  w watch  |  / filter  |  s symbol  |  l logs  |  f watch filter  |  space pause  |  q quit".to_string()
        } else {
            "DISCOUNT TERMINAL  |  j/k move  |  d detail  |  w watch  |  / filter  |  l logs  |  f watch filter  |  q quit".to_string()
        },
    });
    lines.push(RenderLine {
        color: Some(if app.paused {
            Color::Yellow
        } else {
            Color::DarkCyan
        }),
        text: if live_mode {
            format!(
                "Mode: live  Source: yahoo  Feed: {}  Tracked: {}  Loaded: {}  Unavailable: {}  Applied: {}  Pending: {}  Rate: {}/s",
                if app.paused { "paused" } else { "running" },
                tracked_count,
                state.symbol_count(),
                tracked_count.saturating_sub(state.symbol_count()),
                state.total_events(),
                app.pending_count(),
                updates_per_second,
            )
        } else {
            format!(
                "Mode: replay  Source: journal  Feed: {}  Symbols: {}  Applied: {}  Pending: {}  Rate: {}/s",
                if app.paused { "paused" } else { "running" },
                state.symbol_count(),
                state.total_events(),
                app.pending_count(),
                updates_per_second,
            )
        },
    });
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
            text: format!("Tracked symbols: {}", live_symbols.read_symbols(format_symbol_list)),
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
        text: input_prompt(app, live_mode),
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
            "ISSUE LOG  |  j/k move  |  c clear resolved  |  Backspace or l close  |  Ctrl+C quit"
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
    let mut lines = Vec::new();

    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "TICKER DETAIL  |  j/k next ticker  |  1-6 range  |  [/] cycle  |  w watch  |  l logs  |  Backspace or d or Enter close  |  Ctrl+C quit".to_string(),
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
    let mut compact_consensus = viewport_height < 34 || viewport_width < 96;
    let mut compact_evidence = viewport_height < 28 || viewport_width < 90;
    let preferred_chart_stack_height = (viewport_height.saturating_mul(11) / 20)
        .max(DETAIL_MIN_CHART_HEIGHT + DETAIL_MIN_VOLUME_HEIGHT + DETAIL_MIN_MACD_HEIGHT);

    let mut non_chart_lines = 22usize
        + if show_recent_context { 4 } else { 0 }
        + if compact_consensus { 1 } else { 6 }
        + if compact_evidence { 2 } else { 3 };

    if non_chart_lines + preferred_chart_stack_height > viewport_height && show_recent_context {
        show_recent_context = false;
        non_chart_lines = non_chart_lines.saturating_sub(4);
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
            status: format!("ready ({})", candles.len()),
            note: None,
            color: Color::DarkGrey,
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

fn normalize_frame(
    lines: &[RenderLine],
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    lines
        .iter()
        .take(viewport_height)
        .map(|line| RenderLine {
            color: line.color,
            text: clip_text_to_width(&line.text, viewport_width),
        })
        .collect()
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

fn collect_dirty_rows(
    previous_frame: &[RenderLine],
    next_frame: &[RenderLine],
    viewport_height: usize,
) -> Vec<usize> {
    let visible_rows = previous_frame
        .len()
        .max(next_frame.len())
        .min(viewport_height);
    let mut dirty_rows = Vec::new();

    for row_index in 0..visible_rows {
        if previous_frame.get(row_index) != next_frame.get(row_index) {
            dirty_rows.push(row_index);
        }
    }

    dirty_rows
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
            if let Err(error) = state
                .load_watchlist_file(watchlist_file)
                .map_err(|error| with_path_context(error, "load watchlist file", watchlist_file))
            {
                startup_issues.push(StartupIssue {
                    key: ISSUE_KEY_WATCHLIST_RESTORE,
                    severity: IssueSeverity::Warning,
                    title: "Watchlist restore failed",
                    detail: format!("{error}. Starting without the saved watchlist instead."),
                });
            }
        }
    }

    Ok(LoadedState {
        state,
        startup_issues,
    })
}

fn parse_runtime_options() -> io::Result<RuntimeOptions> {
    let mut options = RuntimeOptions::default();
    let mut args = std::env::args().skip(1);

    while let Some(argument) = args.next() {
        match argument.as_str() {
            "--smoke" => options.smoke = true,
            "--replay-file" => {
                let Some(path) = args.next() else {
                    return Err(io::Error::new(
                        ErrorKind::InvalidInput,
                        "--replay-file requires a path",
                    ));
                };
                options.replay_file = Some(PathBuf::from(path));
            }
            "--journal-file" => {
                let Some(path) = args.next() else {
                    return Err(io::Error::new(
                        ErrorKind::InvalidInput,
                        "--journal-file requires a path",
                    ));
                };
                options.journal_file = Some(PathBuf::from(path));
            }
            "--watchlist-file" => {
                let Some(path) = args.next() else {
                    return Err(io::Error::new(
                        ErrorKind::InvalidInput,
                        "--watchlist-file requires a path",
                    ));
                };
                options.watchlist_file = Some(PathBuf::from(path));
            }
            "--symbols" => {
                let Some(symbols) = args.next() else {
                    return Err(io::Error::new(
                        ErrorKind::InvalidInput,
                        "--symbols requires a comma-separated list",
                    ));
                };
                options.symbols = parse_symbols_argument(&symbols)?;
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

    if options.symbols.is_empty() {
        options.symbols = default_live_symbols();
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
    println!(
        "discount_screener [--smoke] [--symbols CSV] [--replay-file PATH] [--journal-file PATH] [--watchlist-file PATH]"
    );
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
    Client: LiveFeedClient,
    BuildClient: FnMut() -> io::Result<Client>,
{
    let mut client = None;
    let mut weighted_target_refresh_cursor = 0usize;

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
                            loaded_symbols: 0,
                            unsupported_symbols: 0,
                            error_symbols: 0,
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
        let weighted_target_refresh_budget =
            symbols.len().min(WEIGHTED_TARGET_REFRESH_BUDGET_PER_CYCLE);

        if !publish_feed_refresh(
            &publisher,
            &symbols,
            feed_error_logger.as_ref(),
            |index, symbol| {
                client.fetch_symbol_with_options(
                    symbol,
                    should_refresh_weighted_target(
                        index,
                        weighted_target_refresh_cursor,
                        symbols.len(),
                        weighted_target_refresh_budget,
                    ),
                )
            },
        ) {
            return;
        }

        weighted_target_refresh_cursor = next_weighted_target_refresh_cursor(
            weighted_target_refresh_cursor,
            symbols.len(),
            weighted_target_refresh_budget,
        );
    }
}

fn publish_feed_refresh<F>(
    publisher: &AppEventPublisher,
    symbols: &[String],
    feed_error_logger: Option<&FeedErrorLogger>,
    mut fetch_symbol: F,
) -> bool
where
    F: FnMut(usize, &str) -> io::Result<Option<market_data::LiveSymbolFeed>>,
{
    let mut loaded_symbols = 0usize;
    let mut unsupported_symbols = 0usize;
    let mut error_symbols = 0usize;
    let mut last_error = None;

    for (index, symbol) in symbols.iter().enumerate() {
        let live_feed = match fetch_symbol(index, symbol) {
            Ok(Some(live_feed)) => {
                loaded_symbols += 1;
                live_feed
            }
            Ok(None) => {
                unsupported_symbols += 1;
                if let Some(feed_error_logger) = feed_error_logger {
                    let _ = feed_error_logger.log_symbol_failure(
                        symbol,
                        FeedFailureKind::IncompleteCoverage,
                        "provider returned incomplete coverage for required quote fields",
                    );
                }
                continue;
            }
            Err(error) => {
                error_symbols += 1;
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

        if !publisher.publish(AppEvent::FeedBatch(build_symbol_feed_batch(live_feed))) {
            return false;
        }
    }

    if unsupported_symbols > 0 || error_symbols > 0 || last_error.is_some() {
        if let Some(feed_error_logger) = feed_error_logger {
            let _ = feed_error_logger.log_refresh_summary(
                symbols.len(),
                loaded_symbols,
                unsupported_symbols,
                error_symbols,
                last_error.as_deref(),
            );
        }
    }

    publisher.publish(AppEvent::FeedBatch(vec![FeedEvent::SourceStatus(
        LiveSourceStatus {
            tracked_symbols: symbols.len(),
            loaded_symbols,
            unsupported_symbols,
            error_symbols,
            last_error,
        },
    )]))
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

fn build_symbol_feed_batch(live_feed: market_data::LiveSymbolFeed) -> Vec<FeedEvent> {
    let mut events = vec![FeedEvent::Snapshot(live_feed.snapshot)];

    if let Some(signal) = live_feed.external_signal {
        events.push(FeedEvent::External(signal));
    }

    events
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

fn input_prompt(app: &AppState, live_mode: bool) -> String {
    match &app.input_mode {
        InputMode::Normal => app.status_message.clone().unwrap_or_else(|| {
            if live_mode {
                "Use d or Enter for ticker detail, / to filter, s to track a symbol, l to open issues, Backspace to go back, or Ctrl+C to quit.".to_string()
            } else {
                "Use d or Enter for ticker detail, / to filter, l to open issues, Backspace to go back, or Ctrl+C to quit.".to_string()
            }
        }),
        InputMode::FilterSearch(buffer) => {
            format!(
                "Filter rows: '{buffer}'  Enter apply  Backspace delete or go back  Esc cancel  Ctrl+C quit"
            )
        }
        InputMode::SymbolSearch(buffer) => {
            format!(
                "Track symbol: '{buffer}'  Enter add  Backspace delete or go back  Esc cancel  Ctrl+C quit"
            )
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

fn apply_live_source_status(issue_center: &mut IssueCenter, source_status: LiveSourceStatus) {
    if source_status.tracked_symbols == 0 {
        issue_center.resolve(ISSUE_KEY_FEED_UNAVAILABLE);
        issue_center.resolve(ISSUE_KEY_FEED_PARTIAL);
        return;
    }

    let build_partial_feed_detail = |source_status: &LiveSourceStatus| {
        let mut detail = format!(
            "Loaded {} of {} tracked symbols.",
            source_status.loaded_symbols, source_status.tracked_symbols
        );

        if source_status.unsupported_symbols > 0 {
            detail.push_str(&format!(
                " {} symbols returned incomplete coverage.",
                source_status.unsupported_symbols
            ));
        }

        if source_status.error_symbols > 0 {
            detail.push_str(&format!(
                " {} symbols failed provider requests.",
                source_status.error_symbols
            ));
        }

        if let Some(last_error) = &source_status.last_error {
            detail.push_str(&format!(" Last provider error: {}", last_error));
        }

        detail
    };

    if source_status.loaded_symbols == 0 {
        let detail = if source_status.unsupported_symbols > 0
            || source_status.error_symbols > 0
            || source_status.last_error.is_some()
        {
            build_partial_feed_detail(&source_status)
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

    if source_status.loaded_symbols < source_status.tracked_symbols
        || source_status.unsupported_symbols > 0
        || source_status.error_symbols > 0
        || source_status.last_error.is_some()
    {
        issue_center.raise(
            ISSUE_KEY_FEED_PARTIAL,
            IssueSource::Feed,
            IssueSeverity::Warning,
            "Live source partially degraded",
            build_partial_feed_detail(&source_status),
        );
    } else {
        issue_center.resolve(ISSUE_KEY_FEED_PARTIAL);
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
    watchlist_file: Option<&PathBuf>,
) -> io::Result<bool> {
    match &app.overlay_mode {
        OverlayMode::None => Ok(false),
        OverlayMode::IssueLog => {
            match key_event.code {
                KeyCode::Esc | KeyCode::Backspace | KeyCode::Char('l') | KeyCode::Char('q') => {
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
            match key_event.code {
                KeyCode::Esc
                | KeyCode::Backspace
                | KeyCode::Enter
                | KeyCode::Char('d')
                | KeyCode::Char('q') => {
                    app.close_overlay();
                }
                KeyCode::Char('l') => {
                    app.open_issue_log();
                }
                KeyCode::Down | KeyCode::Char('j') => {
                    let rows = filtered_symbol_rows(state, &app.view_filter);
                    app.move_ticker_detail_selection(&rows, 1);
                    app.queue_detail_chart_request(chart_control_sender);
                }
                KeyCode::Up | KeyCode::Char('k') => {
                    let rows = filtered_symbol_rows(state, &app.view_filter);
                    app.move_ticker_detail_selection(&rows, -1);
                    app.queue_detail_chart_request(chart_control_sender);
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
                        match save_watchlist_if_configured(state, watchlist_file) {
                            Ok(()) => {
                                app.issue_center.resolve(ISSUE_KEY_WATCHLIST_PERSISTENCE);
                            }
                            Err(error) => {
                                app.issue_center.raise(
                                    ISSUE_KEY_WATCHLIST_PERSISTENCE,
                                    IssueSource::Persistence,
                                    IssueSeverity::Warning,
                                    "Watchlist persistence failed",
                                    error.to_string(),
                                );
                            }
                        }
                    }
                }
                _ => {}
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

fn save_watchlist_if_configured(
    state: &TerminalState,
    watchlist_file: Option<&PathBuf>,
) -> io::Result<()> {
    let Some(watchlist_file) = watchlist_file else {
        return Ok(());
    };

    state
        .save_watchlist_file(watchlist_file)
        .map_err(|error| with_path_context(error, "save watchlist file", watchlist_file))
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
    use super::AppEvent;
    use super::AppEventPublisher;
    use super::AppState;
    use super::CANDIDATE_COMPANY_COLUMN_WIDTH;
    use super::Color;
    use super::Event;
    use super::FeedErrorLogger;
    use super::FeedEvent;
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
    use super::MAX_VISIBLE_ROWS;
    use super::OverlayMode;
    use super::RenderLine;
    use super::RuntimeOptions;
    use super::aggregate_historical_candles;
    use super::analyst_consensus_lines;
    use super::apply_live_source_status;
    use super::build_screen_lines;
    use super::build_ticker_detail_lines;
    use super::build_ticker_detail_lines_for_viewport;
    use super::candidate_company_label;
    use super::chart_loop_with_client_factory;
    use super::chart_range_label;
    use super::clip_text_to_width;
    use super::collect_clear_rows;
    use super::collect_dirty_rows;
    use super::compute_ema_series;
    use super::compute_macd_series;
    use super::confidence_justification_lines;
    use super::feed_loop_with_client_factory;
    use super::filtered_symbol_rows;
    use super::format_bps;
    use super::format_money;
    use super::format_symbol_list;
    use super::gap_meter;
    use super::health_status_label;
    use super::load_initial_state;
    use super::market_data::ChartRange;
    use super::market_data::HistoricalCandle;
    use super::next_weighted_target_refresh_cursor;
    use super::normalize_frame;
    use super::parse_symbols_argument;
    use super::publish_feed_refresh;
    use super::publish_input_events;
    use super::qualification_justification_lines;
    use super::reconcile_journal_persistence;
    use super::should_handle_key_event;
    use super::should_leave_input_mode_on_backspace;
    use super::should_refresh_weighted_target;
    use super::visible_text;
    use discount_screener::CandidateRow;
    use discount_screener::ConfidenceBand;
    use discount_screener::ExternalSignalStatus;
    use discount_screener::ExternalValuationSignal;
    use discount_screener::MarketSnapshot;
    use discount_screener::QualificationStatus;
    use discount_screener::SymbolDetail;
    use discount_screener::TerminalState;
    use discount_screener::ViewFilter;
    use discount_screener::checked_gap_bps;
    use std::collections::VecDeque;
    use std::env::temp_dir;
    use std::error::Error as _;
    use std::fs;
    use std::io;
    use std::path::PathBuf;
    use std::sync::Arc;
    use std::sync::Mutex;
    use std::sync::mpsc;
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

    fn live_feed(symbol: &str) -> super::market_data::LiveSymbolFeed {
        super::market_data::LiveSymbolFeed {
            snapshot: MarketSnapshot {
                symbol: symbol.to_string(),
                company_name: None,
                profitable: true,
                market_price_cents: 10_000,
                intrinsic_value_cents: 12_500,
            },
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
        }
    }

    #[derive(Clone)]
    struct FakeFeedClient {
        calls: Arc<Mutex<Vec<(String, bool)>>>,
        results: Arc<Mutex<VecDeque<io::Result<Option<super::market_data::LiveSymbolFeed>>>>>,
    }

    impl super::LiveFeedClient for FakeFeedClient {
        fn fetch_symbol_with_options(
            &self,
            symbol: &str,
            refresh_weighted_target: bool,
        ) -> io::Result<Option<super::market_data::LiveSymbolFeed>> {
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
    fn live_source_status_raises_a_partial_feed_issue() {
        let mut issue_center = IssueCenter::default();

        apply_live_source_status(
            &mut issue_center,
            LiveSourceStatus {
                tracked_symbols: 8,
                loaded_symbols: 6,
                unsupported_symbols: 2,
                error_symbols: 1,
                last_error: Some("provider timeout".to_string()),
            },
        );

        let issue = issue_center.sorted_entries()[0].clone();

        assert_eq!(
            (
                health_status_label(issue_center.health_status()),
                issue.title,
                issue.active,
                issue
                    .detail
                    .contains("2 symbols returned incomplete coverage."),
                issue.detail.contains("1 symbols failed provider requests."),
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
    fn feed_refresh_publishes_symbol_updates_before_final_source_status() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let symbols = vec!["AAPL".to_string(), "MSFT".to_string(), "AMD".to_string()];
        let mut fetch_results = vec![
            Ok(Some(live_feed("AAPL"))),
            Ok(None),
            Ok(Some(live_feed("AMD"))),
        ]
        .into_iter();

        assert!(publish_feed_refresh(&publisher, &symbols, None, |_, _| {
            fetch_results
                .next()
                .expect("each symbol should have one fetch result")
        }));

        let first_batch = match receiver.recv().expect("first batch should arrive") {
            AppEvent::FeedBatch(feed_events) => feed_events,
            _ => panic!("expected first feed batch"),
        };
        let second_batch = match receiver.recv().expect("second batch should arrive") {
            AppEvent::FeedBatch(feed_events) => feed_events,
            _ => panic!("expected second feed batch"),
        };
        let final_batch = match receiver.recv().expect("final status batch should arrive") {
            AppEvent::FeedBatch(feed_events) => feed_events,
            _ => panic!("expected final feed batch"),
        };

        assert_eq!(first_batch.len(), 2);
        assert!(
            matches!(first_batch.first(), Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AAPL")
        );
        assert!(
            matches!(first_batch.get(1), Some(FeedEvent::External(signal)) if signal.symbol == "AAPL")
        );

        assert_eq!(second_batch.len(), 2);
        assert!(
            matches!(second_batch.first(), Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AMD")
        );
        assert!(
            matches!(second_batch.get(1), Some(FeedEvent::External(signal)) if signal.symbol == "AMD")
        );

        assert_eq!(final_batch.len(), 1);
        assert!(matches!(
            final_batch.first(),
            Some(FeedEvent::SourceStatus(super::LiveSourceStatus {
                tracked_symbols: 3,
                loaded_symbols: 2,
                unsupported_symbols: 1,
                error_symbols: 0,
                last_error: None,
            }))
        ));

        assert!(receiver.try_recv().is_err());
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
            Ok(Some(live_feed("AAPL"))),
            Err(io::Error::other("provider timeout")),
            Ok(Some(live_feed("AMD"))),
        ]
        .into_iter();

        assert!(publish_feed_refresh(&publisher, &symbols, None, |_, _| {
            fetch_results
                .next()
                .expect("each symbol should have one fetch result")
        }));

        let first_batch = match receiver.recv().expect("first batch should arrive") {
            AppEvent::FeedBatch(feed_events) => feed_events,
            _ => panic!("expected first feed batch"),
        };
        let second_batch = match receiver.recv().expect("second batch should arrive") {
            AppEvent::FeedBatch(feed_events) => feed_events,
            _ => panic!("expected second feed batch"),
        };
        let final_batch = match receiver.recv().expect("final status batch should arrive") {
            AppEvent::FeedBatch(feed_events) => feed_events,
            _ => panic!("expected final feed batch"),
        };

        assert_eq!(
            (
                first_batch.len(),
                second_batch.len(),
                matches!(
                    final_batch.first(),
                    Some(FeedEvent::SourceStatus(LiveSourceStatus {
                        tracked_symbols: 3,
                        loaded_symbols: 2,
                        unsupported_symbols: 0,
                        error_symbols: 1,
                        last_error: Some(last_error),
                    })) if last_error == "provider timeout"
                ),
            ),
            (2, 2, true)
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
            Ok(Some(live_feed("AAPL"))),
            Ok(None),
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
        while receiver.try_recv().is_ok() {}

        let log_contents =
            fs::read_to_string(&log_path).expect("feed error log should be readable after refresh");
        let _ = fs::remove_file(&log_path);

        assert!(log_contents.contains("kind=incomplete_coverage symbol=MSFT"));
        assert!(log_contents.contains("kind=provider_error symbol=AMD"));
        assert!(
            log_contents.contains("kind=refresh_summary tracked=3 loaded=1 incomplete=1 errors=1")
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
                    results: Arc::new(Mutex::new(VecDeque::from(vec![Ok(Some(live_feed(
                        "AAPL",
                    )))]))),
                })
            }
        });

        let first_batch = match receiver.recv().expect("first batch should arrive") {
            AppEvent::FeedBatch(feed_events) => feed_events,
            _ => panic!("expected first feed batch"),
        };
        let second_batch = match receiver.recv().expect("second batch should arrive") {
            AppEvent::FeedBatch(feed_events) => feed_events,
            _ => panic!("expected second feed batch"),
        };
        let third_batch = match receiver.recv().expect("third batch should arrive") {
            AppEvent::FeedBatch(feed_events) => feed_events,
            _ => panic!("expected third feed batch"),
        };

        assert!(matches!(
            first_batch.first(),
            Some(FeedEvent::SourceStatus(LiveSourceStatus {
                tracked_symbols: 1,
                loaded_symbols: 0,
                unsupported_symbols: 0,
                error_symbols: 0,
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
                loaded_symbols: 1,
                unsupported_symbols: 0,
                error_symbols: 0,
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
            .map(|symbol| Ok(Some(live_feed(symbol))))
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

        assert_eq!(source_status_batches, 2);
        assert_eq!(calls.len(), 20);
        assert_eq!(
            calls
                .iter()
                .take(10)
                .map(|(_, refresh_weighted_target)| *refresh_weighted_target)
                .collect::<Vec<_>>(),
            vec![true, true, true, true, true, true, true, true, false, false]
        );
        assert_eq!(
            calls
                .iter()
                .skip(10)
                .map(|(_, refresh_weighted_target)| *refresh_weighted_target)
                .collect::<Vec<_>>(),
            vec![true, true, true, true, true, true, false, false, true, true]
        );
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
    fn stale_chart_response_does_not_replace_newer_request() {
        let mut app = AppState::default();
        let (sender, _receiver) = mpsc::channel();
        app.open_ticker_detail("NVDA");

        app.queue_detail_chart_request(Some(&sender));
        app.chart_cache
            .remove(&super::ChartCacheKey::new("NVDA", ChartRange::Year));
        app.queue_detail_chart_request(Some(&sender));

        app.apply_chart_data(super::ChartDataEvent {
            symbol: "NVDA".to_string(),
            range: ChartRange::Year,
            request_id: 1,
            result: Ok(vec![HistoricalCandle {
                epoch_seconds: 1,
                open_cents: 1,
                high_cents: 2,
                low_cents: 1,
                close_cents: 2,
                volume: 1,
            }]),
        });

        assert!(matches!(
            app.detail_chart_entry("NVDA"),
            Some(super::ChartCacheEntry::Loading { request_id: 2, .. })
        ));
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
