mod market_data;

use std::collections::VecDeque;
use std::io;
use std::io::ErrorKind;
use std::io::Stdout;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;
use std::time::Instant;

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
use discount_screener::AlertKind;
use discount_screener::CandidateRow;
use discount_screener::ConfidenceBand;
use discount_screener::ExternalSignalStatus;
use discount_screener::ExternalValuationSignal;
use discount_screener::MarketSnapshot;
use discount_screener::QualificationStatus;
use discount_screener::SymbolDetail;
use discount_screener::TerminalState;
use discount_screener::ViewFilter;
use market_data::DEFAULT_POLL_INTERVAL;
use market_data::MarketDataClient;
use market_data::default_live_symbols;

const MAX_VISIBLE_ROWS: usize = 12;
const MAX_VISIBLE_ALERTS: usize = 6;
const MAX_VISIBLE_TAPE: usize = 8;
const MAX_VISIBLE_ISSUES: usize = 8;
const MAX_STORED_ISSUES: usize = 64;
const TARGET_RANGE_BAR_WIDTH: usize = 18;
const GAP_METER_WIDTH: usize = 12;
const ISSUE_TOAST_DURATION: Duration = Duration::from_secs(6);
const EVENT_RATE_WINDOW: Duration = Duration::from_secs(1);
const ISSUE_KEY_FEED_UNAVAILABLE: &str = "feed-unavailable";
const ISSUE_KEY_FEED_PARTIAL: &str = "feed-partial";
const ISSUE_KEY_JOURNAL_PERSISTENCE: &str = "journal-persistence";
const ISSUE_KEY_WATCHLIST_PERSISTENCE: &str = "watchlist-persistence";

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
    last_error: Option<String>,
}

enum FeedControl {
    RefreshNow,
}

enum AppEvent {
    Input(KeyEvent),
    Resize,
    FeedBatch(Vec<FeedEvent>),
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

    fn snapshot(&self) -> Vec<String> {
        self.with_symbols(|symbols| symbols.clone())
    }

    fn count(&self) -> usize {
        self.with_symbols(|symbols| symbols.len())
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
        }
    }
}

impl AppState {
    fn visible_rows(&self, state: &TerminalState) -> Vec<CandidateRow> {
        state.filtered_rows(MAX_VISIBLE_ROWS, &self.view_filter)
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

#[derive(Clone, Debug, PartialEq, Eq)]
struct RenderLine {
    color: Option<Color>,
    text: String,
}

#[derive(Default)]
struct ScreenRenderer {
    previous_frame: Vec<RenderLine>,
    last_painted_rows: usize,
}

impl ScreenRenderer {
    fn render(&mut self, stdout: &mut Stdout, lines: &[RenderLine]) -> io::Result<()> {
        let (width, height) = terminal::size()?;
        let viewport_width = width as usize;
        let viewport_height = height as usize;
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

        queue!(stdout, BeginSynchronizedUpdate)?;

        for row_index in dirty_rows {
            paint_row(stdout, row_index, next_frame.get(row_index))?;
        }

        for row_index in clear_rows {
            paint_row(stdout, row_index, None)?;
        }

        queue!(stdout, EndSynchronizedUpdate)?;
        stdout.flush()?;

        self.previous_frame = next_frame;
        if viewport_height >= self.last_painted_rows {
            self.last_painted_rows = self.previous_frame.len();
        }

        Ok(())
    }
}

fn main() -> io::Result<()> {
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
            "{} price={} fair={} gap={} confidence={}",
            row.symbol,
            format_money(row.market_price_cents),
            format_money(row.intrinsic_value_cents),
            format_bps(row.gap_bps),
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
    let mut state = load_initial_state(&options)?;
    let mut app = AppState::default();
    let mut last_persisted_sequence = state.latest_sequence();
    let live_symbols = live_mode.then(|| LiveSymbolState::new(options.symbols.clone()));
    let (app_event_sender, app_event_receiver) = mpsc::channel();
    let app_event_publisher = AppEventPublisher::new(app_event_sender);
    install_shutdown_publisher(app_event_publisher.clone())?;

    let mut stdout = io::stdout();
    terminal::enable_raw_mode()?;
    execute!(stdout, EnterAlternateScreen, Hide)?;
    let terminal_guard = TerminalGuard;
    let mut screen_renderer = ScreenRenderer::default();
    let mut rate_tracker = RateTracker::default();

    spawn_input_publisher(app_event_publisher.clone());
    let feed_control_sender = if let Some(live_symbols) = live_symbols.as_ref() {
        Some(spawn_feed_publisher(
            app_event_publisher.clone(),
            live_symbols.clone(),
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
    )?;

    loop {
        let app_event = match app_event_receiver.recv() {
            Ok(app_event) => app_event,
            Err(_) => break,
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
        )?;
    }

    let save_result = if let Some(journal_file) = options.journal_file.as_ref() {
        let journal_result = state.save_journal_file(journal_file);
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
    thread::spawn(move || {
        loop {
            let event = match event::read() {
                Ok(event) => event,
                Err(_) => return,
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
    });
}

fn spawn_feed_publisher(
    publisher: AppEventPublisher,
    live_symbols: LiveSymbolState,
) -> mpsc::Sender<FeedControl> {
    let (control_sender, control_receiver) = mpsc::channel();
    let scheduler_sender = control_sender.clone();
    let initial_sender = control_sender.clone();
    thread::spawn(move || feed_loop(publisher, control_receiver, live_symbols));
    thread::spawn(move || feed_schedule_loop(scheduler_sender));
    let _ = initial_sender.send(FeedControl::RefreshNow);
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
    watchlist_file: Option<&PathBuf>,
) -> io::Result<LoopControl> {
    if is_force_quit_key(&key_event) {
        return Ok(LoopControl::Exit);
    }

    if handle_overlay_key(app, state, &key_event, watchlist_file)? {
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
    let lines = build_screen_lines(
        state,
        rows,
        selected_index,
        updates_per_second,
        live_mode,
        app,
        live_symbols,
    );

    screen_renderer.render(stdout, &lines)
}

fn build_screen_lines(
    state: &TerminalState,
    rows: &[CandidateRow],
    selected_index: usize,
    updates_per_second: usize,
    live_mode: bool,
    app: &AppState,
    live_symbols: Option<&LiveSymbolState>,
) -> Vec<RenderLine> {
    match &app.overlay_mode {
        OverlayMode::IssueLog => return build_issue_log_lines(app),
        OverlayMode::TickerDetail(symbol) => return build_ticker_detail_lines(state, app, symbol),
        OverlayMode::None => {}
    }

    let selected_row = rows.get(selected_index);
    let selected_detail = selected_row.and_then(|row| state.detail(&row.symbol));
    let alerts = state.alerts();
    let tape = state.recent_tape();
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
                "Mode: live  Source: yahoo  Feed: {}  Tracked: {}  Loaded: {}  Applied: {}  Pending: {}  Rate: {}/s",
                if app.paused { "paused" } else { "running" },
                tracked_count,
                state.symbol_count(),
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
            text: format!(
                "Tracked symbols: {}",
                format_symbol_list(&live_symbols.snapshot())
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
        text: input_prompt(app, live_mode),
    });
    lines.push(RenderLine {
        color: Some(Color::Cyan),
        text: "TOP CANDIDATES".to_string(),
    });
    lines.push(RenderLine {
        color: None,
        text: "Idx  W  Symbol  Price      Fair       Gap      Confidence".to_string(),
    });

    for (index, row) in rows.iter().enumerate() {
        let marker = if index == selected_index { '>' } else { ' ' };
        let watched_marker = if state.is_watched(&row.symbol) {
            '*'
        } else {
            ' '
        };
        lines.push(RenderLine {
            color: Some(candidate_row_color(row, index == selected_index)),
            text: format!(
                "{} {:>2}  {}  {:<6} {:>10} {:>10} {:>8}  {}",
                marker,
                index,
                watched_marker,
                row.symbol,
                format_money(row.market_price_cents),
                format_money(row.intrinsic_value_cents),
                format_bps(row.gap_bps),
                confidence_label(row.confidence),
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
                selected_detail.symbol,
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
                "Price: {}  Fair value: {}  Gap: {}  External: {}",
                format_money(selected_detail.market_price_cents),
                format_money(selected_detail.intrinsic_value_cents),
                format_bps(selected_detail.gap_bps),
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

    for alert in alerts.iter().rev().take(MAX_VISIBLE_ALERTS) {
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

    for tape_event in tape.iter().rev().take(MAX_VISIBLE_TAPE) {
        lines.push(RenderLine {
            color: Some(confidence_color(tape_event.confidence)),
            text: format!(
                "{: <6} gap={} qualified={} confidence={}",
                tape_event.symbol,
                format_bps(tape_event.gap_bps),
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

fn build_ticker_detail_lines(
    state: &TerminalState,
    app: &AppState,
    symbol: &str,
) -> Vec<RenderLine> {
    let detail_rows = filtered_symbol_rows(state, &app.view_filter);
    let symbol_index = detail_rows
        .iter()
        .position(|row| row.symbol == symbol)
        .map(|index| index + 1)
        .unwrap_or(1);
    let symbol_count = detail_rows.len().max(1);
    let filtered_alerts = state
        .alerts()
        .into_iter()
        .filter(|alert| alert.symbol == symbol)
        .collect::<Vec<_>>();
    let filtered_tape = state
        .recent_tape()
        .into_iter()
        .filter(|tape_event| tape_event.symbol == symbol)
        .collect::<Vec<_>>();
    let mut lines = Vec::new();

    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "TICKER DETAIL  |  j/k next ticker  |  w watch  |  l logs  |  Backspace or d or Enter close  |  Ctrl+C quit".to_string(),
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

    lines.push(RenderLine {
        color: Some(Color::Cyan),
        text: format!(
            "Symbol: {}  Position: {}/{} filtered tickers  Watched: {}",
            detail.symbol,
            symbol_index,
            symbol_count,
            if detail.is_watched { "yes" } else { "no" },
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkCyan),
        text: format!(
            "Price: {}  Mean target: {}  Median target: {}",
            format_money(detail.market_price_cents),
            format_money(detail.intrinsic_value_cents),
            format_optional_money(detail.external_signal_fair_value_cents),
        ),
    });
    if let Some(weighted_target_cents) = detail.weighted_external_signal_fair_value_cents {
        lines.push(RenderLine {
            color: Some(Color::DarkCyan),
            text: format!(
                "Weighted target: {}  Scored firms: {}",
                format_money(weighted_target_cents),
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
        color: Some(gap_color(detail.gap_bps, detail.minimum_gap_bps)),
        text: format!(
            "Discount to mean: {}  Gap to mean: {}  threshold {}  {}",
            format_money(discount_cents),
            format_bps(detail.gap_bps),
            format_bps(detail.minimum_gap_bps),
            gap_meter(detail.gap_bps, detail.minimum_gap_bps, GAP_METER_WIDTH),
        ),
    });
    if let Some(weighted_target_cents) = detail.weighted_external_signal_fair_value_cents {
        let weighted_gap_bps =
            gap_bps_from_price_and_target(detail.market_price_cents, weighted_target_cents);
        lines.push(RenderLine {
            color: Some(gap_color(weighted_gap_bps, detail.minimum_gap_bps)),
            text: format!(
                "Gap to weighted: {}  threshold {}  {}",
                format_bps(weighted_gap_bps),
                format_bps(detail.minimum_gap_bps),
                gap_meter(weighted_gap_bps, detail.minimum_gap_bps, GAP_METER_WIDTH),
            ),
        });
    }
    lines.push(RenderLine {
        color: Some(status_summary_color(
            detail.qualification,
            detail.confidence,
        )),
        text: format!(
            "Qualification: {}  Confidence: {}  External: {}  Profitable: {}",
            qualification_label(detail.qualification),
            confidence_label(detail.confidence),
            external_status_label(detail.external_status),
            yes_no(detail.profitable),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Last sequence: {}  Updates: {}  Visible filter: query='{}' watchlist_only={}",
            detail.last_sequence,
            detail.update_count,
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
        text: "ANALYST CONSENSUS".to_string(),
    });
    for analyst_line in analyst_consensus_lines(&detail) {
        lines.push(RenderLine {
            color: Some(analyst_consensus_line_color(&detail, &analyst_line)),
            text: analyst_line,
        });
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Green),
        text: "QUALIFICATION".to_string(),
    });
    for (index, explanation) in qualification_justification_lines(&detail)
        .into_iter()
        .enumerate()
    {
        lines.push(RenderLine {
            color: Some(qualification_line_color(&detail, index)),
            text: explanation,
        });
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Blue),
        text: "CONFIDENCE".to_string(),
    });
    for (index, explanation) in confidence_justification_lines(&detail)
        .into_iter()
        .enumerate()
    {
        lines.push(RenderLine {
            color: Some(confidence_line_color(&detail, index)),
            text: explanation,
        });
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Magenta),
        text: "RECENT SYMBOL ALERTS".to_string(),
    });
    if filtered_alerts.is_empty() {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "No recent alerts for this ticker in the current session.".to_string(),
        });
    } else {
        for alert in filtered_alerts.iter().rev().take(MAX_VISIBLE_ALERTS) {
            lines.push(RenderLine {
                color: Some(alert_kind_color(alert.kind)),
                text: format!("kind={} seq={}", alert_label(alert.kind), alert.sequence),
            });
        }
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "RECENT SYMBOL TAPE".to_string(),
    });
    if filtered_tape.is_empty() {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "No recent tape events for this ticker in the current session.".to_string(),
        });
    } else {
        for tape_event in filtered_tape.iter().rev().take(MAX_VISIBLE_TAPE) {
            lines.push(RenderLine {
                color: Some(confidence_color(tape_event.confidence)),
                text: format!(
                    "gap={} qualified={} confidence={}",
                    format_bps(tape_event.gap_bps),
                    if tape_event.is_qualified { "yes" } else { "no" },
                    confidence_label(tape_event.confidence),
                ),
            });
        }
    }

    lines
}

fn filtered_symbol_rows(state: &TerminalState, view_filter: &ViewFilter) -> Vec<CandidateRow> {
    state.filtered_rows(state.symbol_count().max(1), view_filter)
}

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

    text.chars().take(viewport_width).collect()
}

fn paint_row(stdout: &mut Stdout, row_index: usize, line: Option<&RenderLine>) -> io::Result<()> {
    queue!(
        stdout,
        MoveTo(0, row_index as u16),
        Clear(ClearType::CurrentLine)
    )?;

    if let Some(line) = line {
        if let Some(color) = line.color {
            queue!(stdout, SetForegroundColor(color))?;
        }

        queue!(stdout, Print(&line.text), ResetColor)?;
    }

    Ok(())
}

fn load_initial_state(options: &RuntimeOptions) -> io::Result<TerminalState> {
    let mut state = if let Some(replay_file) = options.replay_file.as_ref() {
        TerminalState::replay_file(2_000, 30, 32, replay_file)?
    } else if let Some(journal_file) = options.journal_file.as_ref() {
        if journal_file.exists() {
            TerminalState::replay_file(2_000, 30, 32, journal_file)?
        } else {
            TerminalState::new(2_000, 30, 32)
        }
    } else {
        TerminalState::new(2_000, 30, 32)
    };

    if let Some(watchlist_file) = options.watchlist_file.as_ref() {
        if watchlist_file.exists() {
            state.load_watchlist_file(watchlist_file)?;
        }
    }

    Ok(state)
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
) {
    let client = match MarketDataClient::new() {
        Ok(client) => client,
        Err(error) => {
            let _ = publisher.publish(AppEvent::FeedBatch(vec![FeedEvent::SourceStatus(
                LiveSourceStatus {
                    tracked_symbols: live_symbols.count(),
                    loaded_symbols: 0,
                    unsupported_symbols: 0,
                    last_error: Some(format!("market data client initialization failed: {error}")),
                },
            )]));
            return;
        }
    };

    while let Ok(FeedControl::RefreshNow) = control_receiver.recv() {
        if !publish_feed_refresh(&publisher, &live_symbols.snapshot(), |symbol| {
            client.fetch_symbol(symbol)
        }) {
            return;
        }
    }
}

fn publish_feed_refresh<F>(
    publisher: &AppEventPublisher,
    symbols: &[String],
    mut fetch_symbol: F,
) -> bool
where
    F: FnMut(&str) -> io::Result<Option<market_data::LiveSymbolFeed>>,
{
    let mut loaded_symbols = 0usize;
    let mut unsupported_symbols = 0usize;
    let mut last_error = None;

    for symbol in symbols {
        let live_feed = match fetch_symbol(symbol) {
            Ok(Some(live_feed)) => {
                loaded_symbols += 1;
                live_feed
            }
            Ok(None) => {
                unsupported_symbols += 1;
                continue;
            }
            Err(error) => {
                last_error = Some(error.to_string());
                continue;
            }
        };

        if !publisher.publish(AppEvent::FeedBatch(build_symbol_feed_batch(live_feed))) {
            return false;
        }
    }

    publisher.publish(AppEvent::FeedBatch(vec![FeedEvent::SourceStatus(
        LiveSourceStatus {
            tracked_symbols: symbols.len(),
            loaded_symbols,
            unsupported_symbols,
            last_error,
        },
    )]))
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

    if source_status.loaded_symbols == 0 {
        let detail = if let Some(last_error) = source_status.last_error {
            format!(
                "Loaded 0 of {} tracked symbols. Last provider error: {}",
                source_status.tracked_symbols, last_error
            )
        } else if source_status.unsupported_symbols > 0 {
            format!(
                "Loaded 0 of {} tracked symbols. {} symbols returned pages without current price, EPS, or target data.",
                source_status.tracked_symbols, source_status.unsupported_symbols
            )
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
        || source_status.last_error.is_some()
    {
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

        if let Some(last_error) = source_status.last_error {
            detail.push_str(&format!(" Last provider error: {}", last_error));
        }

        issue_center.raise(
            ISSUE_KEY_FEED_PARTIAL,
            IssueSource::Feed,
            IssueSeverity::Warning,
            "Live source partially degraded",
            detail,
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
                }
                KeyCode::Up | KeyCode::Char('k') => {
                    let rows = filtered_symbol_rows(state, &app.view_filter);
                    app.move_ticker_detail_selection(&rows, -1);
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

fn qualification_justification_lines(detail: &SymbolDetail) -> Vec<String> {
    let minimum_gap_label = format_bps(detail.minimum_gap_bps);
    let actual_gap_label = format_bps(detail.gap_bps);
    let discount_cents = detail.intrinsic_value_cents - detail.market_price_cents;
    let result_line = match detail.qualification {
        QualificationStatus::Qualified => format!(
            "Result: qualified because profitable=yes and {} >= {}.",
            actual_gap_label, minimum_gap_label
        ),
        QualificationStatus::Unprofitable => format!(
            "Result: unqualified because profitable=no, even though the required gap is {}.",
            minimum_gap_label
        ),
        QualificationStatus::GapTooSmall => format!(
            "Result: unqualified because {} < {} despite profitable=yes.",
            actual_gap_label, minimum_gap_label
        ),
    };

    vec![
        format!(
            "Profitability gate: actual={}  required=yes",
            yes_no(detail.profitable)
        ),
        format!(
            "Internal gap: actual={}  required>={} ",
            actual_gap_label, minimum_gap_label
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

fn confidence_justification_lines(detail: &SymbolDetail) -> Vec<String> {
    let minimum_gap_label = format_bps(detail.minimum_gap_bps);
    let external_fair_value_label = detail
        .external_signal_fair_value_cents
        .map(format_money)
        .unwrap_or_else(|| "n/a".to_string());
    let external_gap_label = detail
        .external_signal_gap_bps
        .map(format_bps)
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
                "Result: low because the external gap {} does not support the required threshold of {}.",
                external_gap_label, minimum_gap_label
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
            "External fair value: {}  external gap: {}  support threshold: >={}",
            external_fair_value_label, external_gap_label, minimum_gap_label
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

fn format_optional_recommendation_mean(value_hundredths: Option<u16>) -> String {
    value_hundredths
        .map(|value| format!("{}.{:02}", value / 100, value % 100))
        .unwrap_or_else(|| "n/a".to_string())
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

fn gap_bps_from_price_and_target(market_price_cents: i64, target_cents: i64) -> i32 {
    (((target_cents - market_price_cents) * 10_000) / target_cents) as i32
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

fn analyst_consensus_line_color(detail: &SymbolDetail, line_text: &str) -> Color {
    if line_text.starts_with("Range:")
        || line_text.starts_with("Target range width:")
        || line_text.starts_with("Targets: mean")
    {
        return Color::DarkCyan;
    }

    if line_text.starts_with("Analysts:") || line_text.starts_with("Ratings:") {
        return analyst_sentiment_color(detail);
    }

    Color::Grey
}

fn analyst_sentiment_color(detail: &SymbolDetail) -> Color {
    if let Some(recommendation_mean_hundredths) = detail.recommendation_mean_hundredths {
        if recommendation_mean_hundredths <= 200 {
            return Color::Green;
        }

        if recommendation_mean_hundredths <= 300 {
            return Color::Yellow;
        }

        return Color::Red;
    }

    Color::DarkGrey
}

fn qualification_line_color(detail: &SymbolDetail, line_index: usize) -> Color {
    match line_index {
        0 => bool_color(detail.profitable),
        1 | 2 => gap_color(detail.gap_bps, detail.minimum_gap_bps),
        3 => match detail.qualification {
            QualificationStatus::Qualified => Color::Green,
            QualificationStatus::GapTooSmall => Color::Yellow,
            QualificationStatus::Unprofitable => Color::Red,
        },
        _ => Color::Grey,
    }
}

fn confidence_line_color(detail: &SymbolDetail, line_index: usize) -> Color {
    match line_index {
        0 | 1 => external_status_color(detail.external_status),
        2 => signal_age_color(detail),
        3 => confidence_color(detail.confidence),
        _ => Color::Grey,
    }
}

fn signal_age_color(detail: &SymbolDetail) -> Color {
    match detail.external_signal_age_seconds {
        Some(age_seconds) if age_seconds <= detail.external_signal_max_age_seconds => Color::Green,
        Some(_) => Color::Red,
        None => Color::DarkGrey,
    }
}

fn bool_color(value: bool) -> Color {
    if value { Color::Green } else { Color::Red }
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
    let mut truncated = String::new();

    for character in text.chars() {
        if truncated.chars().count() + 1 > max_len {
            truncated.push_str("...");
            return truncated;
        }

        truncated.push(character);
    }

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
    TerminalState::append_journal_file(journal_file, &delta)?;
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

    state.save_watchlist_file(watchlist_file)
}

fn format_money(value_cents: i64) -> String {
    let dollars = value_cents / 100;
    let cents = value_cents.abs() % 100;
    format!("${dollars}.{cents:02}")
}

fn format_bps(value_bps: i32) -> String {
    let whole = value_bps / 100;
    let fraction = value_bps.abs() % 100;
    format!("{whole}.{fraction:02}%")
}

struct TerminalGuard;

impl Drop for TerminalGuard {
    fn drop(&mut self) {
        let mut stdout = io::stdout();
        let _ = terminal::disable_raw_mode();
        let _ = execute!(stdout, Show, LeaveAlternateScreen);
    }
}

#[cfg(test)]
mod tests {
    use super::AppEvent;
    use super::AppEventPublisher;
    use super::AppState;
    use super::Color;
    use super::FeedEvent;
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
    use super::OverlayMode;
    use super::RenderLine;
    use super::analyst_consensus_lines;
    use super::apply_live_source_status;
    use super::build_ticker_detail_lines;
    use super::clip_text_to_width;
    use super::collect_clear_rows;
    use super::collect_dirty_rows;
    use super::confidence_justification_lines;
    use super::format_symbol_list;
    use super::gap_meter;
    use super::health_status_label;
    use super::normalize_frame;
    use super::parse_symbols_argument;
    use super::publish_feed_refresh;
    use super::qualification_justification_lines;
    use super::should_handle_key_event;
    use super::should_leave_input_mode_on_backspace;
    use discount_screener::CandidateRow;
    use discount_screener::ConfidenceBand;
    use discount_screener::ExternalSignalStatus;
    use discount_screener::ExternalValuationSignal;
    use discount_screener::MarketSnapshot;
    use discount_screener::QualificationStatus;
    use discount_screener::SymbolDetail;
    use discount_screener::TerminalState;
    use std::sync::mpsc;

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

    fn live_feed(symbol: &str) -> super::market_data::LiveSymbolFeed {
        super::market_data::LiveSymbolFeed {
            snapshot: MarketSnapshot {
                symbol: symbol.to_string(),
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
                last_error: Some("provider timeout".to_string()),
            },
        );

        let issue = issue_center.sorted_entries()[0].clone();

        assert_eq!(
            (
                health_status_label(issue_center.health_status()),
                issue.title,
                issue.active,
            ),
            (
                "degraded",
                "Live source partially degraded".to_string(),
                true
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

        assert!(publish_feed_refresh(&publisher, &symbols, |_| {
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
                last_error: None,
            }))
        ));

        assert!(receiver.try_recv().is_err());
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
    fn qualification_justification_includes_actual_and_threshold_numbers() {
        let lines = qualification_justification_lines(&detail());

        assert_eq!(
            lines,
            vec![
                "Profitability gate: actual=yes  required=yes".to_string(),
                "Internal gap: actual=35.85%  required>=20.00%".to_string(),
                "Internal discount: $96.53 = $269.23 - $172.70".to_string(),
                "Result: qualified because profitable=yes and 35.85% >= 20.00%.".to_string(),
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
                "External fair value: $269.23  external gap: 35.85%  support threshold: >=20.00%".to_string(),
                "External signal age: 6s  freshness limit: <=30s".to_string(),
                "Result: high because internal qualification is qualified and external status is supportive.".to_string(),
            ]
        );
    }

    #[test]
    fn target_map_renders_unicode_markers_and_a_separate_legend() {
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
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
