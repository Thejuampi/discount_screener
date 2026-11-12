use super::*;
use crate::engine::{FundamentalSnapshot, MarketSnapshot};
use crate::feed_refresh::{AttemptIssues, FeedRefresh, InputAttempt, RefreshPhase, RefreshTrigger};
use crate::fetcher::FetchResult;
use crate::state::FeedStatus;
use std::sync::atomic::AtomicU64;
use std::sync::{mpsc, Condvar};

fn quote(symbol: &str, price: i64) -> FetchResult {
    FetchResult {
        symbol: symbol.into(),
        snapshot: Some(MarketSnapshot {
            symbol: symbol.into(),
            company_name: Some(symbol.into()),
            profitable: true,
            market_price_cents: price,
            intrinsic_value_cents: 0,
            previous_close_cents: 0,
            next_earnings_epoch: None,
        }),
        signal: None,
        fundamentals: Some(FundamentalSnapshot {
            symbol: symbol.into(),
            ..Default::default()
        }),
    }
}

fn candle() -> HistoricalCandle {
    HistoricalCandle {
        epoch_seconds: 1_700_000_000,
        open_cents: 2_000,
        high_cents: 2_000,
        low_cents: 2_000,
        close_cents: 2_000,
        volume: 100,
    }
}

fn recovered() -> AttemptIssues {
    AttemptIssues {
        quote: InputAttempt::Ready,
        chart: InputAttempt::Ready,
        ..AttemptIssues::default()
    }
}

#[test]
fn manual_request_reuses_the_recurring_pass_for_the_active_profile() {
    let dir = std::env::temp_dir().join(format!(
        "ds_manual_feed_{}_{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let app = AppState::new_with_forced_profile(dir.join("history.sqlite"), None).unwrap();
    let symbols = Arc::new(["GOOD", "KEEP", "LATE"].map(str::to_string).to_vec());
    *app.active_symbols.lock().unwrap() = Arc::clone(&symbols);
    app.feed_generation.store(7, Ordering::SeqCst);
    app.initial_pass_completed_generation
        .store(7, Ordering::SeqCst);
    *app.feed_status.lock().unwrap() = FeedStatus {
        running: true,
        symbols_loaded: 2,
        symbols_total: 3,
        last_error: Some("EDGAR is unavailable".into()),
        ..FeedStatus::default()
    };
    {
        let mut screener = app.screener.lock().unwrap();
        screener.ingest_snapshot(quote("GOOD", 1_000).snapshot.unwrap());
        screener.ingest_snapshot(quote("KEEP", 1_000).snapshot.unwrap());
    }
    let gate = EnrichmentGate::from_app(&app);
    let loaded = AtomicUsize::new(2);
    let completed = Mutex::new(HashSet::from(["GOOD".into(), "KEEP".into()]));
    let (sender, receiver) = mpsc::channel();
    let visited = Mutex::new(Vec::new());
    let worker = FeedRefreshWorker {
        gate: &gate,
        generation: 7,
        feed_status: &app.feed_status,
        loaded: &loaded,
        completed: &completed,
        enrichment_senders: &[sender],
        total: symbols.len(),
        checkpoint: None,
    };
    assert_eq!(app.screener.lock().unwrap().snapshots.len(), 2);
    let (started, active) = mpsc::channel();
    let (resume, resumed) = mpsc::channel();
    let resumed = Mutex::new(resumed);
    thread::scope(|scope| {
        let coordinator = scope.spawn(|| {
            worker.run_recurring_pass(
                &symbols,
                &app.feed_wakeup,
                Duration::from_secs(5),
                |symbol| {
                    visited.lock().unwrap().push(symbol.to_string());
                    refresh_one_symbol(
                        &gate,
                        7,
                        symbol,
                        false,
                        || false,
                        |symbol| match symbol {
                            "KEEP" => {
                                started.send(()).unwrap();
                                resumed
                                    .lock()
                                    .unwrap()
                                    .recv_timeout(Duration::from_secs(5))
                                    .unwrap();
                                Ok(FetchResult {
                                    symbol: symbol.into(),
                                    snapshot: None,
                                    signal: None,
                                    fundamentals: None,
                                })
                            }
                            _ => Ok(quote(symbol, 2_000)),
                        },
                        |symbol| match symbol {
                            "KEEP" => Ok(vec![]),
                            _ => Ok(vec![candle()]),
                        },
                        None,
                    )
                },
            )
        });
        request_feed_refresh_inner("sp500", &app).unwrap();
        request_feed_refresh_inner("sp500", &app).unwrap();
        active.recv_timeout(Duration::from_secs(3)).unwrap();
        request_feed_refresh_inner("sp500", &app).unwrap();
        {
            let status = app.feed_status.lock().unwrap();
            assert!(status.symbols_loaded >= 2);
            assert_eq!(status.refresh.progress().phase, RefreshPhase::Refreshing);
            assert_eq!(
                status.refresh.progress().trigger,
                Some(RefreshTrigger::Manual)
            );
        }
        assert!(app.screener.lock().unwrap().snapshots.contains_key("KEEP"));
        resume.send(()).unwrap();
        assert!(coordinator.join().unwrap().unwrap());
    });
    {
        let status = app.feed_status.lock().unwrap();
        let pass = status.refresh.progress();
        assert_eq!(pass.phase, RefreshPhase::Idle);
        assert_eq!(pass.trigger, Some(RefreshTrigger::Manual));
        assert_eq!(pass.processed, 3);
        assert_eq!(pass.total, 3);
        assert_eq!(pass.quote_failures, 1);
        assert_eq!(pass.chart_failures, 1);
        assert_eq!(status.symbols_loaded, 3);
        assert_eq!(status.last_error.as_deref(), Some("EDGAR is unavailable"));
    }
    let mut visited = visited.into_inner().unwrap();
    visited.sort();
    assert_eq!(visited, *symbols);
    assert!(Arc::ptr_eq(&app.active_symbols.lock().unwrap(), &symbols));
    assert_eq!(app.active_profile.lock().unwrap().as_str(), "sp500");
    assert_eq!(app.feed_generation.load(Ordering::SeqCst), 7);
    assert_eq!(
        app.initial_pass_completed_generation.load(Ordering::SeqCst),
        7
    );
    let screener = app.screener.lock().unwrap();
    assert_eq!(screener.snapshots["GOOD"].market_price_cents, 2_000);
    assert_eq!(screener.snapshots["KEEP"].market_price_cents, 1_000);
    assert_eq!(screener.snapshots["LATE"].market_price_cents, 2_000);
    assert_eq!(receiver.try_recv().unwrap(), "LATE");
    drop(screener);
    drop(app);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn scheduled_pass_claim_and_later_click_share_one_pass() {
    let generation = AtomicU64::new(9);
    let status = Mutex::new(FeedStatus {
        running: true,
        symbols_total: 3,
        ..FeedStatus::default()
    });
    let wakeup = Condvar::new();
    assert!(wait_for_recurring_pass(
        &generation,
        9,
        &status,
        &wakeup,
        Duration::ZERO
    ));
    queue_manual_refresh("sp500", &status, &wakeup).unwrap();
    let mut status = status.lock().unwrap();
    assert_eq!(
        status.refresh.progress().trigger,
        Some(RefreshTrigger::Scheduled)
    );
    status.refresh.finish(None);
    assert_eq!(status.refresh.progress().phase, RefreshPhase::Idle);
}

#[test]
fn startup_claims_manual_requests_and_retries_replace_issue_counts() {
    let status = Mutex::new(FeedStatus {
        running: true,
        symbols_total: 2,
        refresh: FeedRefresh::startup(2),
        ..FeedStatus::default()
    });
    let wakeup = Condvar::new();
    queue_manual_refresh("sp500", &status, &wakeup).unwrap();
    {
        let mut status = status.lock().unwrap();
        assert_eq!(status.refresh.progress().phase, RefreshPhase::Startup);
        status.refresh.record("AAA", AttemptIssues::rate_limited());
        status.refresh.record(
            "BBB",
            AttemptIssues {
                quote: InputAttempt::Unavailable,
                ..AttemptIssues::default()
            },
        );
        assert_eq!(status.refresh.progress().rate_limited, 1);
        status.refresh.retrying("Quotes retry".into());
    }
    queue_manual_refresh("sp500", &status, &wakeup).unwrap();
    let mut status = status.lock().unwrap();
    assert_eq!(status.refresh.progress().phase, RefreshPhase::Retrying);
    status.refresh.record("AAA", recovered());
    assert_eq!(status.refresh.progress().processed, 2);
    assert_eq!(status.refresh.progress().rate_limited, 0);
    assert_eq!(status.refresh.progress().quote_failures, 1);
}

#[test]
fn next_pass_keeps_unresolved_issues_until_the_affected_request_recovers() {
    let mut refresh = FeedRefresh::startup(2);
    refresh.record(
        "AAA",
        AttemptIssues {
            quote: InputAttempt::Unavailable,
            chart: InputAttempt::Unavailable,
            ..AttemptIssues::default()
        },
    );
    refresh.finish(None);
    assert!(refresh.queue_manual(2));
    assert_eq!(refresh.progress().quote_failures, 1);
    assert_eq!(refresh.progress().chart_failures, 1);
    assert_eq!(refresh.progress().processed, 0);

    assert!(refresh.begin_recurring(2));
    refresh.record("BBB", recovered());
    assert_eq!(refresh.progress().quote_failures, 1);
    assert_eq!(refresh.progress().chart_failures, 1);

    refresh.record("AAA", AttemptIssues::rate_limited());
    assert_eq!(refresh.progress().quote_failures, 1);
    assert_eq!(refresh.progress().chart_failures, 1);
    assert_eq!(refresh.progress().rate_limited, 1);

    refresh.record("AAA", recovered());
    assert_eq!(refresh.progress().quote_failures, 0);
    assert_eq!(refresh.progress().chart_failures, 0);
    assert_eq!(refresh.progress().rate_limited, 0);
    assert_eq!(refresh.progress().processed, 2);
}

#[test]
fn profile_switch_cancels_a_sleeping_worker_and_rejects_old_requests() {
    let generation = AtomicU64::new(4);
    let status = Mutex::new(FeedStatus {
        running: true,
        symbols_total: 2,
        ..FeedStatus::default()
    });
    let wakeup = Condvar::new();
    thread::scope(|scope| {
        let waiting = scope.spawn(|| {
            wait_for_recurring_pass(&generation, 4, &status, &wakeup, Duration::from_secs(2))
        });
        {
            let mut status = status.lock().unwrap();
            generation.store(5, Ordering::SeqCst);
            status.profile_name = "dow".into();
            status.refresh = FeedRefresh::startup(1);
        }
        wakeup.notify_all();
        assert!(!waiting.join().unwrap());
    });
    assert!(queue_manual_refresh("sp500", &status, &wakeup).is_err());
    assert!(!mutate_current_feed_status(
        &generation,
        4,
        &status,
        |status| {
            status.refresh.record("AAA", AttemptIssues::rate_limited());
        }
    ));
    assert_eq!(status.lock().unwrap().refresh.progress().processed, 0);
}

#[test]
fn cold_feed_refuses_a_manual_request() {
    let status = Mutex::new(FeedStatus::default());
    assert!(queue_manual_refresh("sp500", &status, &Condvar::new()).is_err());
    assert_eq!(
        status.lock().unwrap().refresh.progress().phase,
        RefreshPhase::Idle
    );
}

#[test]
fn cooldown_skips_network_and_reports_affected_symbol() {
    let gate = EnrichmentGate::for_test(
        Arc::new(AtomicU64::new(1)),
        Arc::new(Mutex::new(crate::engine::ScreenerState::new())),
        Arc::new(AtomicU64::new(0)),
    );
    let outcome = refresh_one_symbol(
        &gate,
        1,
        "ABC",
        true,
        || true,
        |_| panic!("quote must wait for the cooldown"),
        |_| panic!("chart must wait for the cooldown"),
        None,
    )
    .unwrap();
    assert!(outcome.issues.rate_limited);
    assert!(outcome.skipped);
}

#[test]
fn recurring_pass_uses_existing_fallbacks_during_quote_cooldown() {
    let gate = EnrichmentGate::for_test(
        Arc::new(AtomicU64::new(1)),
        Arc::new(Mutex::new(crate::engine::ScreenerState::new())),
        Arc::new(AtomicU64::new(0)),
    );
    let result = refresh_one_symbol(
        &gate,
        1,
        "ABC",
        false,
        || true,
        |symbol| Ok(quote(symbol, 1_000)),
        |_| Ok(vec![candle()]),
        None,
    )
    .unwrap();
    assert!(result.issues.rate_limited);
    assert!(!result.skipped);
    assert_eq!(result.issues.quote, InputAttempt::Ready);
    assert_eq!(result.issues.chart, InputAttempt::Ready);
    let state = gate.screener.lock().unwrap();
    assert_eq!(state.snapshots["ABC"].market_price_cents, 1_000);
    assert_eq!(state.daily_candles["ABC"].len(), 1);
}
