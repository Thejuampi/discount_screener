use super::*;
use crate::engine::MarketSnapshot;
use crate::launch_profile::ForcedProfile;
use std::path::PathBuf;

struct TestDb(PathBuf);

impl TestDb {
    fn new() -> Self {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        Self(std::env::temp_dir().join(format!("ds_tracked_feed_{}_{unique}", std::process::id())))
    }

    fn path(&self) -> PathBuf {
        self.0.join("history.sqlite")
    }
}

impl Drop for TestDb {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn snapshot(symbol: &str, intrinsic_value_cents: i64) -> MarketSnapshot {
    MarketSnapshot {
        symbol: symbol.into(),
        company_name: None,
        profitable: true,
        market_price_cents: 1000,
        intrinsic_value_cents,
        previous_close_cents: 1000,
        next_earnings_epoch: None,
    }
}

#[test]
fn async_portfolio_startup_defers_yahoo_construction_and_shares_the_client() {
    let db = TestDb::new();
    let state = AppState::new_with_profile_policy(db.path(), None, true).unwrap();
    let runtime = tokio::runtime::Builder::new_current_thread().build().unwrap();
    let client = runtime.block_on(async {
        let load_client = market_data_client_loader(&state);
        assert!(state.feed_yahoo.lock().unwrap().is_none());
        tokio::task::spawn_blocking(load_client)
            .await
            .unwrap()
            .unwrap()
    });
    assert!(Arc::ptr_eq(&client, &create_feed_yahoo_client(&state).unwrap()));
}

#[test]
fn saved_edits_restore_before_gui_feed_start_and_stay_out_of_headless() {
    let db = TestDb::new();
    {
        let state = AppState::new_with_profile_policy(db.path(), None, true).unwrap();
        set_tracked_feed_member_with("spx", " test.add ", true, &state, |_, _, _, _| Ok(()))
            .unwrap();
        set_tracked_feed_member_with("sp500", "AAPL", false, &state, |_, _, _, _| Ok(())).unwrap();
    }

    let gui = AppState::new_with_profile_policy(db.path(), None, true).unwrap();
    let restored = read_tracked_feed("sp500", &gui).unwrap();
    assert!(restored.editable);
    assert_eq!(restored.symbols.last().unwrap(), "TEST.ADD");
    assert!(!restored.symbols.iter().any(|symbol| symbol == "AAPL"));
    assert_eq!(
        gui.feed_status.lock().unwrap().symbols_total,
        restored.symbols.len()
    );

    let headless = AppState::new_with_forced_profile(db.path(), None).unwrap();
    let stock = read_tracked_feed("sp500", &headless).unwrap();
    assert!(!stock.editable);
    assert!(stock.symbols.iter().any(|symbol| symbol == "AAPL"));
    assert!(!stock.symbols.iter().any(|symbol| symbol == "TEST.ADD"));
}

#[test]
fn change_replaces_generation_and_keeps_coverage_for_ready_members() {
    let db = TestDb::new();
    let state = AppState::new_with_profile_policy(db.path(), None, true).unwrap();
    {
        let mut screener = state.screener.lock().unwrap();
        screener.ingest_snapshot(snapshot("AAPL", 2000));
        screener.ingest_snapshot(snapshot("SPY", 2000));
        screener.daily_candles.insert("SPY".into(), Vec::new());
    }
    let now = Instant::now();
    {
        let mut schedules = state.edgar_feed_schedules.lock().unwrap();
        for symbol in ["AAPL", "SPY"] {
            schedules.insiders.record_result(symbol, now, &Ok(None));
            schedules
                .financials
                .record_result(symbol, vec![1], now, true);
        }
    }
    let yahoo = create_feed_yahoo_client(&state).unwrap();
    *state.edgar_cik_map.lock().unwrap() = Some(HashMap::from([("SPY".into(), 1)]));
    let old = state.feed_generation.load(Ordering::SeqCst);
    let removed = set_tracked_feed_member_with(
        "sp500",
        "AAPL",
        false,
        &state,
        |_, symbols, generation, startup| {
            assert_ne!(generation, old);
            assert!(!symbols.iter().any(|symbol| symbol == "AAPL"));
            assert!(startup.visible.contains("SPY"));
            assert!(!startup.pending.iter().any(|symbol| symbol == "SPY"));
            assert!(startup.reuse_edgar_cik_map);
            Ok(())
        },
    )
    .unwrap();

    assert!(!removed.symbols.iter().any(|symbol| symbol == "AAPL"));
    assert_eq!(state.feed_status.lock().unwrap().symbols_loaded, 1);
    assert!(Arc::ptr_eq(
        &yahoo,
        &create_feed_yahoo_client(&state).unwrap()
    ));
    assert_eq!(
        load_edgar_feed_cik_map(Some(&state.edgar_cik_map), true, || panic!(
            "cached map was fetched"
        ))
        .unwrap()
        .get("SPY"),
        Some(&1),
    );
    {
        let schedules = state.edgar_feed_schedules.lock().unwrap();
        assert!(!schedules.insiders.is_due("SPY", now));
        assert!(schedules.insiders.is_due("AAPL", now));
        assert!(!schedules.financials.should_compute("SPY", &[1], now, true));
        assert!(schedules.financials.should_compute("AAPL", &[1], now, true));
    }
    let gate = EnrichmentGate::from_app(&state);
    assert!(!gate.mutate(old, |screener| screener
        .ingest_snapshot(snapshot("AAPL", 2000))));
    let mut screener = state.screener.lock().unwrap();
    assert!(screener.alerts.iter().all(|event| event.symbol != "AAPL"));
    screener.ingest_snapshot(snapshot("AAPL", 1000));
    screener.ingest_snapshot(snapshot("AAPL", 2000));
    assert!(screener.snapshots.contains_key("AAPL"));
    assert!(screener
        .candidate_rows()
        .iter()
        .all(|row| row.symbol != "AAPL"));
    assert!(screener.alerts.iter().all(|event| event.symbol != "AAPL"));
    assert!(screener
        .candidate_rows()
        .iter()
        .any(|row| row.symbol == "SPY"));
}

#[test]
fn zero_pending_startup_cannot_drop_a_manual_request() {
    let mut refresh = FeedRefresh::default();
    refresh.restart_for_membership(&HashSet::from(["SPY".into()]), 0);
    assert_eq!(refresh.progress().phase, RefreshPhase::Startup);
    assert!(!refresh.queue_manual(1));
    refresh.finish(None);
    assert!(refresh.queue_manual(1));
}

#[test]
fn stale_profile_noop_and_locked_edits_do_not_change_membership() {
    let db = TestDb::new();
    let state = AppState::new_with_profile_policy(db.path(), None, true).unwrap();
    let unexpected =
        |_: &AppState, _: Arc<Vec<String>>, _: u64, _: FeedStartup| panic!("unexpected launch");
    assert!(set_tracked_feed_member_with("dow", "AAPL", false, &state, unexpected).is_err());
    assert!(set_tracked_feed_member_with("sp500", "AAPL", true, &state, unexpected).is_ok());
    assert!(state.db.tracked_feed_overrides("sp500").unwrap().is_empty());
    assert!(state.db.tracked_feed_overrides("dow").unwrap().is_empty());
    assert_eq!(state.feed_generation.load(Ordering::SeqCst), 0);

    let forced = AppState::new_with_profile_policy(
        db.path(),
        Some(ForcedProfile {
            name: "sp500".into(),
        }),
        true,
    )
    .unwrap();
    assert!(!read_tracked_feed("sp500", &forced).unwrap().editable);
    assert!(set_tracked_feed_member_with("sp500", "AAPL", false, &forced, unexpected).is_err());

    let qa = AppState::new_with_profile_policy(
        db.path(),
        Some(ForcedProfile { name: "qa".into() }),
        true,
    )
    .unwrap();
    assert!(!read_tracked_feed("test", &qa).unwrap().editable);
    assert!(set_tracked_feed_member_with("qa", "TEST.ADD", true, &qa, unexpected).is_err());
    assert!(qa.db.tracked_feed_overrides("qa").unwrap().is_empty());
}

#[test]
fn launch_failure_keeps_saved_and_installed_membership_with_feed_error() {
    let db = TestDb::new();
    let state = AppState::new_with_profile_policy(db.path(), None, true).unwrap();
    let installed =
        set_tracked_feed_member_with("sp500", "TEST.ADD", true, &state, |_, _, _, _| {
            Err("test launcher failed".into())
        })
        .unwrap();
    assert_eq!(installed.symbols.last().unwrap(), "TEST.ADD");
    assert_eq!(
        state.db.tracked_feed_overrides("sp500").unwrap(),
        vec![("TEST.ADD".into(), true)]
    );
    let status = state.feed_status.lock().unwrap();
    assert!(!status.running);
    assert_eq!(status.last_error.as_deref(), Some("test launcher failed"));
}

#[test]
fn profile_edits_are_isolated_and_empty_roster_waits_for_the_next_add() {
    let db = TestDb::new();
    let state = AppState::new_with_profile_policy(db.path(), None, true).unwrap();
    let base = resolve_profile_membership("dow", &state.db).unwrap();
    for symbol in &base.symbols {
        state
            .db
            .set_tracked_feed_override("dow", symbol, false)
            .unwrap();
    }
    state
        .db
        .set_tracked_feed_override("dow", "TEST.ADD", true)
        .unwrap();
    assert!(!read_tracked_feed("sp500", &state)
        .unwrap()
        .symbols
        .contains(&"TEST.ADD".into()));

    let dow = resolve_tracked_feed_membership("djia", &state.db, true).unwrap();
    with_profile_apply_gate(&state.profile_apply_gate, || {
        install_feed_membership(&state, dow, false, |_, _, _, _| Ok(()))
    })
    .unwrap();
    assert_eq!(
        read_tracked_feed("dow", &state).unwrap().symbols,
        ["TEST.ADD"]
    );
    let empty = set_tracked_feed_member_with("dow", "TEST.ADD", false, &state, |_, _, _, _| {
        panic!("empty roster launched workers")
    })
    .unwrap();
    assert!(empty.symbols.is_empty());
    assert!(!state.feed_status.lock().unwrap().running);
    assert_eq!(
        state.feed_status.lock().unwrap().refresh.progress().phase,
        RefreshPhase::Idle
    );
    assert!(resolve_tracked_feed_membership("dow", &state.db, true)
        .unwrap()
        .profile
        .symbols
        .is_empty());

    let member = &base.symbols[0];
    let restored =
        set_tracked_feed_member_with("dow", member, true, &state, |_, symbols, _, startup| {
            assert_eq!(symbols.as_slice(), [member.as_str()]);
            assert_eq!(startup.pending, [member.as_str()]);
            Ok(())
        })
        .unwrap();
    assert_eq!(restored.symbols, [member.as_str()]);
    assert!(state.feed_status.lock().unwrap().running);
}
