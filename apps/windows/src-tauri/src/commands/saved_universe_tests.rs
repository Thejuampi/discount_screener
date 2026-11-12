use super::*;
use crate::engine::{FundamentalSnapshot, MarketSnapshot};
use crate::fetcher::FetchResult;
use std::path::PathBuf;

struct TestDb(PathBuf);

impl TestDb {
    fn new() -> Self {
        let epoch = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap();
        Self(std::env::temp_dir().join(format!(
            "ds_saved_universe_{}_{}",
            std::process::id(),
            epoch.as_nanos()
        )))
    }

    fn open(&self, profile: &str, symbols: &[&str]) -> AppState {
        let state = AppState::new(self.0.join("history.sqlite"));
        *state.active_profile.lock().unwrap() = profile.into();
        *state.active_symbols.lock().unwrap() =
            Arc::new(symbols.iter().map(|&symbol| symbol.into()).collect());
        state
    }
}

impl Drop for TestDb {
    fn drop(&mut self) {
        for name in [
            "history.sqlite",
            "history.sqlite-shm",
            "history.sqlite-wal",
            "feed.log",
        ] {
            let _ = std::fs::remove_file(self.0.join(name));
        }
        let _ = std::fs::remove_dir(&self.0);
    }
}

fn quote(symbol: &str, price: i64) -> FetchResult {
    FetchResult {
        symbol: symbol.into(),
        snapshot: Some(MarketSnapshot {
            symbol: symbol.into(),
            company_name: Some(format!("{symbol} company")),
            profitable: true,
            market_price_cents: price,
            intrinsic_value_cents: 3_000,
            previous_close_cents: 1_900,
            next_earnings_epoch: None,
        }),
        signal: None,
        fundamentals: Some(FundamentalSnapshot {
            symbol: symbol.into(),
            sector_name: Some("Technology".into()),
            ..Default::default()
        }),
    }
}

fn candle() -> HistoricalCandle {
    HistoricalCandle {
        epoch_seconds: 1_700_000_000,
        open_cents: 9_000,
        high_cents: 9_000,
        low_cents: 9_000,
        close_cents: 9_000,
        volume: 100,
    }
}

#[test]
fn first_quote_is_saved_before_chart_and_reopens_without_rank_or_alert_inputs() {
    let db = TestDb::new();
    let before_reopen = {
        let state = db.open("sp500", &["AAPL"]);
        let gate = EnrichmentGate::from_app(&state);
        let checkpoint = SavedUniverseCheckpoint::new(&state, 0);
        let mut before_reopen = Vec::new();
        refresh_one_symbol(
            &gate,
            0,
            "AAPL",
            false,
            || false,
            |symbol| Ok(quote(symbol, 2_000)),
            |_| {
                before_reopen = read_saved_universe_rows("sp500", &state).unwrap();
                Ok(vec![])
            },
            Some(&checkpoint),
        )
        .unwrap();
        assert_eq!(before_reopen.len(), 1);
        assert_eq!(before_reopen[0].symbol, "AAPL");
        assert_eq!(before_reopen[0].company_name, "AAPL company");
        assert_eq!(before_reopen[0].market_price_cents, 2_000);
        assert_eq!(before_reopen[0].target_price_cents, Some(3_000));
        assert_eq!(before_reopen[0].asset_type, "stock");
        assert!(before_reopen[0].saved_at_epoch_seconds > 0);
        before_reopen
    };

    let reopened = db.open("sp500", &["AAPL"]);
    let restored = read_saved_universe_rows("sp500", &reopened).unwrap();
    assert_eq!(restored, before_reopen);
    assert!(build_opportunity_rows(&reopened.screener, false, None).is_empty());
    let screener = reopened.screener.lock().unwrap();
    assert!(screener.snapshots.is_empty());
    assert!(screener.quote_request_starts.is_empty());
    assert!(screener.alerts.is_empty());
}

#[test]
fn refresh_batch_keeps_previous_rows_when_quote_fails_even_if_chart_succeeds() {
    let db = TestDb::new();
    let symbols = ["AAPL", "MSFT", "NVDA", "ZZZZ"];
    let state = db.open("sp500", &symbols);
    let gate = EnrichmentGate::from_app(&state);
    let checkpoint = SavedUniverseCheckpoint::new(&state, 0);
    let loaded = AtomicUsize::new(0);
    let completed = Mutex::new(HashSet::new());
    let (sender, _receiver) = std::sync::mpsc::channel();
    let worker = FeedRefreshWorker {
        gate: &gate,
        generation: 0,
        feed_status: &state.feed_status,
        loaded: &loaded,
        completed: &completed,
        enrichment_senders: &[sender],
        total: symbols.len(),
        checkpoint: Some(&checkpoint),
    };
    let fetch = |symbol: &str, result: fn(&str) -> std::io::Result<FetchResult>| {
        refresh_one_symbol(
            &gate,
            0,
            symbol,
            false,
            || false,
            result,
            |_| Ok(vec![candle()]),
            Some(&checkpoint),
        )
    };
    let active = state.active_symbols.lock().unwrap().clone();
    worker
        .run_batch(&active[..3], false, |symbol| fetch(symbol, prior_quote))
        .unwrap();
    let mut saved = read_saved_universe_rows("sp500", &state).unwrap();
    assert_eq!(saved.len(), 3);
    for row in &mut saved {
        row.saved_at_epoch_seconds = 1;
    }
    state.db.save_universe_rows("sp500", &saved).unwrap();

    worker
        .run_batch(&active, false, |symbol| fetch(symbol, mixed_quote))
        .unwrap();
    let refreshed = read_saved_universe_rows("sp500", &state).unwrap();
    assert_eq!(refreshed.len(), 3);
    assert_eq!(refreshed[0].market_price_cents, 2_500);
    assert_eq!(refreshed[1], saved[1]);
    assert_eq!(refreshed[2].market_price_cents, 2_500);
}

fn prior_quote(symbol: &str) -> std::io::Result<FetchResult> {
    Ok(quote(symbol, 2_000))
}

fn mixed_quote(symbol: &str) -> std::io::Result<FetchResult> {
    if matches!(symbol, "MSFT" | "ZZZZ") {
        Err(std::io::Error::other("offline quote fixture"))
    } else {
        Ok(quote(symbol, 2_500))
    }
}

#[test]
fn restore_requires_the_active_profile_and_current_qa_membership() {
    let db = TestDb::new();
    {
        let state = db.open("qa", &["AAPL", "MSFT"]);
        let checkpoint = SavedUniverseCheckpoint::new(&state, 0);
        let gate = EnrichmentGate::from_app(&state);
        for symbol in ["AAPL", "MSFT"] {
            refresh_one_symbol(
                &gate,
                0,
                symbol,
                false,
                || false,
                prior_quote,
                |_| Ok(vec![]),
                Some(&checkpoint),
            )
            .unwrap();
        }
        checkpoint.flush();
    }
    let other_profile = db.open("sp500", &["AAPL"]);
    assert!(read_saved_universe_rows("sp500", &other_profile)
        .unwrap()
        .is_empty());
    assert!(read_saved_universe_rows("qa", &other_profile).is_err());
    let changed_qa = db.open("qa", &["MSFT", "NVDA"]);
    let restored = read_saved_universe_rows("qa", &changed_qa).unwrap();
    assert_eq!(restored.len(), 1);
    assert_eq!(restored[0].symbol, "MSFT");
    assert!(read_saved_universe_rows("test", &changed_qa).is_err());
}

#[test]
fn pending_checkpoint_cannot_write_after_the_generation_changes() {
    let db = TestDb::new();
    let state = db.open("sp500", &["AAPL", "MSFT"]);
    let gate = EnrichmentGate::from_app(&state);
    let checkpoint = SavedUniverseCheckpoint::new(&state, 0);
    for symbol in ["AAPL", "MSFT"] {
        refresh_one_symbol(
            &gate,
            0,
            symbol,
            false,
            || false,
            prior_quote,
            |_| Ok(vec![]),
            Some(&checkpoint),
        )
        .unwrap();
    }
    let first = read_saved_universe_rows("sp500", &state).unwrap();
    assert_eq!(first.len(), 1);
    state.feed_generation.store(1, Ordering::SeqCst);
    checkpoint.flush();
    assert_eq!(read_saved_universe_rows("sp500", &state).unwrap(), first);
}
