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

    fn log_provider_result(
        &self,
        provider_result: &market_data::ProviderFetchResult,
    ) -> io::Result<()> {
        self.append_line(format!(
            "ts={} kind=provider_result symbol={} snapshot={} external={} fundamentals={} core={} external_state={} fundamentals_state={} diagnostics={}",
            unix_timestamp_seconds(),
            provider_result.symbol,
            if provider_result.snapshot.is_some() { "true" } else { "false" },
            if provider_result.external_signal.is_some() { "true" } else { "false" },
            if provider_result.fundamentals.is_some() { "true" } else { "false" },
            provider_component_state_label(provider_result.coverage.core),
            provider_component_state_label(provider_result.coverage.external),
            provider_component_state_label(provider_result.coverage.fundamentals),
            provider_result.diagnostics.len(),
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PrimaryViewMode {
    Candidates,
    Opportunities,
}

struct AppState {
    paused: bool,
    primary_view: PrimaryViewMode,
    selected_symbol: Option<String>,
    candidate_selected_symbol: Option<String>,
    opportunity_selected_symbol: Option<String>,
    tracked_symbols: Vec<String>,
    show_all_tracked_symbols_in_candidates: bool,
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
    replay_offset: usize,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            paused: false,
            primary_view: PrimaryViewMode::Candidates,
            selected_symbol: None,
            candidate_selected_symbol: None,
            opportunity_selected_symbol: None,
            tracked_symbols: Vec::new(),
            show_all_tracked_symbols_in_candidates: false,
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
            replay_offset: 0,
        }
    }
}

impl AppState {
    fn set_show_all_tracked_symbols_in_candidates(&mut self, enabled: bool) {
        self.show_all_tracked_symbols_in_candidates = enabled;
    }

    fn set_tracked_symbols(&mut self, symbols: Vec<String>) {
        let mut seen = HashSet::new();
        self.tracked_symbols = symbols
            .into_iter()
            .filter(|symbol| seen.insert(symbol.clone()))
            .collect();
    }

    fn add_tracked_symbols(&mut self, symbols: Vec<String>) {
        let mut tracked_symbols = self.tracked_symbols.clone();
        tracked_symbols.extend(symbols);
        self.set_tracked_symbols(tracked_symbols);
    }

    fn selection_for_view(&self, view: PrimaryViewMode) -> Option<&str> {
        let stored_selection = match view {
            PrimaryViewMode::Candidates => self.candidate_selected_symbol.as_deref(),
            PrimaryViewMode::Opportunities => self.opportunity_selected_symbol.as_deref(),
        };

        stored_selection.or_else(|| {
            if self.primary_view == view {
                self.selected_symbol.as_deref()
            } else {
                None
            }
        })
    }

    fn remember_selection_for_view(&mut self, view: PrimaryViewMode, symbol: &str) {
        let symbol = symbol.to_string();
        match view {
            PrimaryViewMode::Candidates => self.candidate_selected_symbol = Some(symbol.clone()),
            PrimaryViewMode::Opportunities => {
                self.opportunity_selected_symbol = Some(symbol.clone())
            }
        }
        self.selected_symbol = Some(symbol);
    }

    fn clear_selection_for_view(&mut self, view: PrimaryViewMode) {
        match view {
            PrimaryViewMode::Candidates => self.candidate_selected_symbol = None,
            PrimaryViewMode::Opportunities => self.opportunity_selected_symbol = None,
        }
        if self.primary_view == view {
            self.selected_symbol = None;
        }
    }

    fn clear_all_selections(&mut self) {
        self.selected_symbol = None;
        self.candidate_selected_symbol = None;
        self.opportunity_selected_symbol = None;
    }

    fn candidate_rows(&self, state: &TerminalState) -> Vec<CandidateRow> {
        if self.show_all_tracked_symbols_in_candidates {
            return self
                .tracked_symbols
                .iter()
                .filter(|symbol| symbol_matches_view_filter(state, symbol, &self.view_filter))
                .filter_map(|symbol| {
                    state.candidate(symbol).or_else(|| {
                        self.provider_coverage.get(symbol).and_then(|coverage| {
                            symbol_coverage_has_error(coverage)
                                .then(|| unavailable_candidate_row(symbol))
                        })
                    })
                })
                .collect();
        }

        let mut rows = filtered_symbol_rows(state, &self.view_filter);
        let mut included_symbols = rows
            .iter()
            .map(|row| row.symbol.clone())
            .collect::<HashSet<_>>();

        for symbol in self
            .tracked_symbols
            .iter()
            .filter(|symbol| symbol_matches_view_filter(state, symbol, &self.view_filter))
        {
            if included_symbols.contains(symbol) {
                continue;
            }

            let Some(coverage) = self.symbol_coverage(symbol) else {
                continue;
            };
            if !symbol_coverage_has_error(coverage) {
                continue;
            }

            if let Some(candidate) = state.candidate(symbol) {
                included_symbols.insert(symbol.clone());
                rows.push(candidate);
                continue;
            }

            included_symbols.insert(symbol.clone());
            rows.push(unavailable_candidate_row(symbol));
        }

        rows
    }

    fn visible_rows(&self, state: &TerminalState) -> Vec<CandidateRow> {
        let mut rows = self.candidate_rows(state);
        rows.truncate(MAX_VISIBLE_ROWS);
        rows
    }

    fn visible_opportunity_rows(&self, state: &TerminalState) -> Vec<OpportunityRow> {
        build_opportunity_rows(state, self)
    }

    fn ranked_opportunity_symbols(&self, state: &TerminalState) -> Vec<String> {
        build_opportunity_rows(state, self)
            .into_iter()
            .map(|row| row.symbol)
            .collect()
    }

    fn active_detail_symbols(&self, state: &TerminalState) -> Vec<String> {
        match self.primary_view {
            PrimaryViewMode::Candidates => self
                .candidate_rows(state)
                .into_iter()
                .map(|row| row.symbol)
                .collect(),
            PrimaryViewMode::Opportunities => self.ranked_opportunity_symbols(state),
        }
    }

    fn active_base_symbols(
        &self,
        state: &TerminalState,
        candidate_rows: &[CandidateRow],
    ) -> Vec<String> {
        match self.primary_view {
            PrimaryViewMode::Candidates => candidate_rows
                .iter()
                .map(|row| row.symbol.clone())
                .collect(),
            PrimaryViewMode::Opportunities => self.ranked_opportunity_symbols(state),
        }
    }

    fn sync_base_selected_index(
        &mut self,
        state: &TerminalState,
        candidate_rows: &[CandidateRow],
    ) -> usize {
        match self.primary_view {
            PrimaryViewMode::Candidates => self.selected_index(candidate_rows),
            PrimaryViewMode::Opportunities => {
                let rows = self.visible_opportunity_rows(state);
                let symbols = rows
                    .iter()
                    .map(|row| row.symbol.as_str())
                    .collect::<Vec<_>>();
                self.selected_index_for_symbols(&symbols)
            }
        }
    }

    fn input_selected_index(
        &mut self,
        state: &TerminalState,
        candidate_rows: &[CandidateRow],
    ) -> usize {
        self.sync_base_selected_index(state, candidate_rows)
    }

    fn selected_index_for_symbols(&mut self, symbols: &[&str]) -> usize {
        let view = self.primary_view;
        if symbols.is_empty() {
            self.clear_selection_for_view(view);
            return 0;
        }

        if let Some(selected_symbol) = self.selection_for_view(view) {
            if let Some(index) = symbols.iter().position(|symbol| *symbol == selected_symbol) {
                self.selected_symbol = Some(selected_symbol.to_string());
                return index;
            }
        }

        self.remember_selection_for_view(view, symbols[0]);
        0
    }

    fn set_selection_for_symbols(&mut self, symbols: &[&str], index: usize) -> usize {
        let view = self.primary_view;
        if symbols.is_empty() {
            self.clear_selection_for_view(view);
            return 0;
        }

        let next_index = index.min(symbols.len().saturating_sub(1));
        self.remember_selection_for_view(view, symbols[next_index]);
        next_index
    }

    fn move_selection_for_symbols(&mut self, symbols: &[&str], delta: isize) -> usize {
        let current_index = self.selected_index_for_symbols(symbols);
        if symbols.is_empty() {
            return 0;
        }

        let next_index = current_index
            .saturating_add_signed(delta)
            .min(symbols.len().saturating_sub(1));
        self.set_selection_for_symbols(symbols, next_index)
    }

    fn select_first_for_symbols(&mut self, symbols: &[&str]) -> usize {
        self.set_selection_for_symbols(symbols, 0)
    }

    fn select_last_for_symbols(&mut self, symbols: &[&str]) -> usize {
        self.set_selection_for_symbols(symbols, symbols.len().saturating_sub(1))
    }

    fn move_selection_by_page_for_symbols(
        &mut self,
        symbols: &[&str],
        delta: isize,
        page_size: usize,
    ) -> usize {
        let current_index = self.selected_index_for_symbols(symbols);
        if symbols.is_empty() {
            return 0;
        }

        let page_delta = delta.saturating_mul(page_size.max(1) as isize);
        let next_index = current_index
            .saturating_add_signed(page_delta)
            .min(symbols.len().saturating_sub(1));
        self.set_selection_for_symbols(symbols, next_index)
    }

    fn toggle_primary_view(&mut self, state: &TerminalState) {
        self.primary_view = match self.primary_view {
            PrimaryViewMode::Candidates => PrimaryViewMode::Opportunities,
            PrimaryViewMode::Opportunities => PrimaryViewMode::Candidates,
        };
        self.selected_symbol = None;
        self.ensure_primary_view_selection(state);
    }

    fn ensure_primary_view_selection(&mut self, state: &TerminalState) {
        match self.primary_view {
            PrimaryViewMode::Candidates => {
                let rows = self.visible_rows(state);
                self.selected_index(&rows);
            }
            PrimaryViewMode::Opportunities => {
                let rows = self.visible_opportunity_rows(state);
                let symbols = rows
                    .iter()
                    .map(|row| row.symbol.as_str())
                    .collect::<Vec<_>>();
                self.selected_index_for_symbols(&symbols);
            }
        }
    }

    fn selected_index(&mut self, rows: &[CandidateRow]) -> usize {
        let symbols = rows
            .iter()
            .map(|row| row.symbol.as_str())
            .collect::<Vec<_>>();
        self.selected_index_for_symbols(&symbols)
    }

    fn set_selection(&mut self, symbol: &str) {
        self.remember_selection_for_view(self.primary_view, symbol);
    }

    fn clear_filters(&mut self) {
        self.view_filter = ViewFilter::default();
        self.clear_all_selections();
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
        self.remember_selection_for_view(self.primary_view, symbol);
        self.overlay_mode = OverlayMode::TickerDetail(symbol.to_string());
        self.detail_tab = DetailTab::Snapshot;
        self.history_view = HistoryViewState::default();
        self.reset_replay();
    }

    fn close_overlay(&mut self) {
        self.overlay_mode = OverlayMode::None;
        self.detail_tab = DetailTab::Snapshot;
        self.selected_symbol = self
            .selection_for_view(self.primary_view)
            .map(str::to_string);
    }

    fn detail_symbol(&self) -> Option<&str> {
        match &self.overlay_mode {
            OverlayMode::TickerDetail(symbol) => Some(symbol.as_str()),
            _ => None,
        }
    }

    #[cfg(test)]
    fn move_ticker_detail_selection(&mut self, rows: &[CandidateRow], delta: isize) {
        let symbols = rows
            .iter()
            .map(|row| row.symbol.as_str())
            .collect::<Vec<_>>();
        self.move_ticker_detail_selection_for_symbols(&symbols, delta);
    }

    fn move_ticker_detail_selection_for_symbols(&mut self, symbols: &[&str], delta: isize) {
        let Some(current_symbol) = self.detail_symbol() else {
            return;
        };

        let Some(current_index) = symbols.iter().position(|symbol| *symbol == current_symbol)
        else {
            return;
        };

        let next_index = current_index
            .saturating_add_signed(delta)
            .min(symbols.len().saturating_sub(1));
        let next_symbol = symbols[next_index].to_string();
        self.remember_selection_for_view(self.primary_view, &next_symbol);
        self.overlay_mode = OverlayMode::TickerDetail(next_symbol);
        self.history_view.scroll = 0;
        self.reset_replay();
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
        self.reset_replay();
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

    fn step_replay_back(&mut self, total_candles: usize) -> bool {
        let max_offset = total_candles.saturating_sub(1);
        if self.replay_offset >= max_offset {
            return false;
        }
        self.replay_offset += 1;
        true
    }

    fn step_replay_forward(&mut self) -> bool {
        if self.replay_offset == 0 {
            return false;
        }
        self.replay_offset -= 1;
        true
    }

    fn reset_replay(&mut self) {
        self.replay_offset = 0;
    }

    fn visible_candle_end(&self, total_candles: usize) -> usize {
        if total_candles == 0 {
            return 0;
        }
        total_candles.saturating_sub(self.replay_offset).max(1)
    }

    fn detail_replay_candle_count(&self) -> usize {
        let Some(symbol) = self.detail_symbol() else {
            return 0;
        };
        match self.detail_chart_entry(symbol) {
            Some(ChartCacheEntry::Ready { candles }) => candles.len(),
            Some(ChartCacheEntry::Loading {
                previous: Some(candles),
                ..
            }) => candles.len(),
            Some(ChartCacheEntry::Failed {
                previous: Some(candles),
                ..
            }) => candles.len(),
            _ => 0,
        }
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
                self.reset_replay();
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

    fn symbol_has_provider_error(&self, symbol: &str) -> bool {
        self.symbol_coverage(symbol)
            .map(symbol_coverage_has_error)
            .unwrap_or(false)
    }

    fn symbol_is_unavailable(&self, state: &TerminalState, symbol: &str) -> bool {
        state.detail(symbol).is_none() && self.symbol_coverage(symbol).is_some()
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
    bg_color: Option<Color>,
    text: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct StyledCell {
    ch: char,
    color: Option<Color>,
    bg_color: Option<Color>,
    priority: u8,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct OpportunityRow {
    symbol: String,
    market_price_cents: i64,
    intrinsic_value_cents: i64,
    gap_bps: i32,
    confidence: ConfidenceBand,
    is_watched: bool,
    fundamentals_score: Option<i32>,
    technical_score: Option<i32>,
    forecast_score: Option<i32>,
    composite_score: i32,
    coverage_count: usize,
    fundamentals_signals: Vec<&'static str>,
    technical_signals: Vec<&'static str>,
    forecast_signals: Vec<&'static str>,
}

