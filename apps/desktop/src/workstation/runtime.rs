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

#[derive(Clone, Debug, PartialEq, Eq)]
struct VolumeProfileBin {
    up_volume: u64,
    down_volume: u64,
}

static MAIN_THREAD_ID: OnceLock<thread::ThreadId> = OnceLock::new();
static PANIC_REPORT: OnceLock<Mutex<Option<String>>> = OnceLock::new();

struct ScreenRenderer {
    terminal: Terminal<CrosstermBackend<Stdout>>,
}

impl ScreenRenderer {
    fn new(stdout: Stdout) -> io::Result<Self> {
        let backend = CrosstermBackend::new(stdout);
        let terminal = Terminal::new(backend)
            .map_err(|error| with_io_context(error, "create ratatui terminal backend"))?;
        Ok(Self { terminal })
    }

    fn render(
        &mut self,
        lines: &[RenderLine],
        viewport_width: usize,
        viewport_height: usize,
    ) -> io::Result<()> {
        self.terminal
            .draw(|frame| {
                let area = frame.area();
                frame.render_widget(
                    tui::RenderLineFrame::new(
                        lines,
                        viewport_width.min(area.width as usize),
                        viewport_height.min(area.height as usize),
                    ),
                    area,
                );
            })
            .map(|_| ())
            .map_err(|error| with_io_context(error, "draw ratatui terminal frame"))
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
    let mut screen_renderer = ScreenRenderer::new(stdout)
        .map_err(|error| with_io_context(error, "create screen renderer"))?;
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
    let initial_selected_index = app.sync_base_selected_index(&state, &initial_rows);
    if let Some(live_symbols) = live_symbols.as_ref() {
        let tracked = live_symbols.snapshot();
        app.queue_background_chart_requests(Some(&chart_control_sender), &tracked);
        app.queue_background_analysis_requests(&state, Some(&analysis_control_sender), &tracked);
    }
    render(
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
                let selected_index = app.input_selected_index(&state, &rows);
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
        let selected_index = app.sync_base_selected_index(&state, &rows);
        render(
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
        InputMode::Normal => {
            let active_symbols = app.active_base_symbols(state, rows);
            let active_symbol_refs = active_symbols
                .iter()
                .map(String::as_str)
                .collect::<Vec<_>>();

            match key_event.code {
                KeyCode::Char('q') => return Ok(LoopControl::Exit),
                KeyCode::Down | KeyCode::Char('j') => {
                    app.move_selection_for_symbols(&active_symbol_refs, 1);
                }
                KeyCode::Up | KeyCode::Char('k') => {
                    app.move_selection_for_symbols(&active_symbol_refs, -1);
                }
                KeyCode::Home => {
                    app.select_first_for_symbols(&active_symbol_refs);
                }
                KeyCode::End => {
                    app.select_last_for_symbols(&active_symbol_refs);
                }
                KeyCode::PageDown => {
                    app.move_selection_by_page_for_symbols(
                        &active_symbol_refs,
                        1,
                        MAX_VISIBLE_ROWS,
                    );
                }
                KeyCode::PageUp => {
                    app.move_selection_by_page_for_symbols(
                        &active_symbol_refs,
                        -1,
                        MAX_VISIBLE_ROWS,
                    );
                }
                KeyCode::Enter | KeyCode::Char('d') => {
                    if let Some(symbol) = active_symbols.get(selected_index) {
                        app.open_ticker_detail(symbol);
                        app.queue_detail_chart_request(chart_control_sender);
                        app.queue_detail_analysis_request(state, analysis_control_sender);
                    } else {
                        app.set_status_message("Select a ticker to open the detail screen.");
                    }
                }
                KeyCode::Char('w') => {
                    if let Some(symbol) = active_symbols.get(selected_index) {
                        state.toggle_watchlist(symbol);
                        if let Some(persistence_handle) = persistence_handle {
                            persistence_handle.replace_watchlist(state.watchlist_symbols());
                        }
                        app.set_selection(symbol);
                    }
                }
                KeyCode::Char('f') => {
                    app.view_filter.watchlist_only = !app.view_filter.watchlist_only;
                    app.clear_all_selections();
                }
                KeyCode::Char('o') => {
                    app.toggle_primary_view(state);
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
            }
        }
        InputMode::FilterSearch(buffer) => match key_event.code {
            KeyCode::Enter => {
                app.view_filter.query = buffer.clone();
                app.clear_all_selections();
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

