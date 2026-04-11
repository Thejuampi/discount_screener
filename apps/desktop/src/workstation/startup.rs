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

        let tracked_symbols = if options.symbols.is_empty() {
            default_live_symbols()
        } else {
            options.symbols.clone()
        };
        let mut app = AppState::default();
        app.set_show_all_tracked_symbols_in_candidates(options.symbols_explicit);
        app.set_tracked_symbols(tracked_symbols.clone());

        return Ok(LoadedState {
            state,
            app,
            tracked_symbols,
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
                tracked_symbols = reorder_symbols_by_persisted_ranking(&tracked_symbols, &state);
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
    app.set_show_all_tracked_symbols_in_candidates(options.symbols_explicit);
    app.set_tracked_symbols(tracked_symbols.clone());

    Ok(LoadedState {
        state,
        app,
        tracked_symbols,
        persistence_db_path,
        startup_issues,
    })
}

fn reorder_symbols_by_persisted_ranking(symbols: &[String], state: &TerminalState) -> Vec<String> {
    if symbols.is_empty() {
        return Vec::new();
    }
    let ranked = state.top_rows(state.symbol_count());
    let mut ranked_symbols: Vec<String> = ranked
        .into_iter()
        .map(|row| row.symbol)
        .filter(|s| symbols.contains(s))
        .collect();
    for symbol in symbols {
        if !ranked_symbols.contains(symbol) {
            ranked_symbols.push(symbol.clone());
        }
    }
    ranked_symbols
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

        if let Some(feed_error_logger) = feed_error_logger {
            let _ = feed_error_logger.log_provider_result(&provider_result);
        }

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

    if let Some(feed_error_logger) = feed_error_logger {
        let batch_symbols = refresh_plan
            .symbols
            .iter()
            .map(|(_, symbol)| symbol.clone())
            .collect::<Vec<_>>();
        let _ = feed_error_logger.log_debug(&format!(
            "refresh_batch phase={} symbols={}",
            refresh_plan.phase_label,
            format_symbol_list(&batch_symbols),
        ));
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
                    if let Some(feed_error_logger) = feed_error_logger {
                        let _ = feed_error_logger.log_provider_result(&provider_result);
                    }
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
        "DISCOUNT TERMINAL  |  o view  d detail  / filter  s symbol  space pause  q quit"
    } else {
        "DISCOUNT TERMINAL  |  o view  d detail  / filter  l logs  q quit"
    };
    let full = if live_mode {
        "DISCOUNT TERMINAL  |  j/k move  |  o view  |  d detail  |  w watch  |  / filter  |  s symbol  |  l logs  |  f watch filter  |  space pause  |  q quit"
    } else {
        "DISCOUNT TERMINAL  |  j/k move  |  o view  |  d detail  |  w watch  |  / filter  |  l logs  |  f watch filter  |  q quit"
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
                "o view  d detail  / filter  s symbol  l logs  Backspace back  Ctrl+C quit"
            } else {
                "o view  d detail  / filter  l logs  Backspace back  Ctrl+C quit"
            };
            let full = if live_mode {
                "Use j/k, Home/End, or PgUp/PgDn to navigate, o to switch list views, d or Enter for ticker detail, / to filter, s to track a symbol, l to open issues, Backspace to go back, or Ctrl+C to quit."
            } else {
                "Use j/k, Home/End, or PgUp/PgDn to navigate, o to switch list views, d or Enter for ticker detail, / to filter, l to open issues, Backspace to go back, or Ctrl+C to quit."
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
    let chart_rows = tui_graphs::render_line_chart(&tile.points, inner_width.max(8), 4);
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
                        let rows = app.active_detail_symbols(state);
                        let symbols = rows.iter().map(String::as_str).collect::<Vec<_>>();
                        app.move_ticker_detail_selection_for_symbols(&symbols, 1);
                        app.queue_detail_chart_request(chart_control_sender);
                        app.queue_detail_analysis_request(state, analysis_control_sender);
                    }
                    KeyCode::Up | KeyCode::Char('k') => {
                        let rows = app.active_detail_symbols(state);
                        let symbols = rows.iter().map(String::as_str).collect::<Vec<_>>();
                        app.move_ticker_detail_selection_for_symbols(&symbols, -1);
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
                    KeyCode::Left => {
                        let count = app.detail_replay_candle_count();
                        app.step_replay_back(count);
                    }
                    KeyCode::Right => {
                        app.step_replay_forward();
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
                        let rows = app.active_detail_symbols(state);
                        let symbols = rows.iter().map(String::as_str).collect::<Vec<_>>();
                        app.move_ticker_detail_selection_for_symbols(&symbols, 1);
                        app.load_detail_history(persistence_handle);
                    }
                    KeyCode::Char('p') => {
                        let rows = app.active_detail_symbols(state);
                        let symbols = rows.iter().map(String::as_str).collect::<Vec<_>>();
                        app.move_ticker_detail_selection_for_symbols(&symbols, -1);
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

fn candidate_row_color(row: &CandidateRow, is_selected: bool, is_stale: bool) -> Color {
    if is_selected {
        return if row.is_qualified {
            Color::Cyan
        } else {
            Color::DarkCyan
        };
    }
    if is_stale {
        return Color::DarkGrey;
    }

    confidence_color(row.confidence)
}

fn candidate_display_color(
    row: &CandidateRow,
    is_selected: bool,
    is_stale: bool,
    has_provider_error: bool,
) -> Color {
    if has_provider_error {
        return if is_selected {
            Color::DarkRed
        } else {
            Color::Red
        };
    }

    candidate_row_color(row, is_selected, is_stale)
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

fn provider_component_state_label(state: market_data::ProviderComponentState) -> &'static str {
    match state {
        market_data::ProviderComponentState::Fresh => "fresh",
        market_data::ProviderComponentState::Missing => "missing",
        market_data::ProviderComponentState::Error => "error",
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
        app.add_tracked_symbols(added_symbols.clone());
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

