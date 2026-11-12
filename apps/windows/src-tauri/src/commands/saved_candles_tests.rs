use super::*;
use std::path::PathBuf;
use std::sync::atomic::AtomicU64;

struct TestDb(PathBuf);

impl TestDb {
    fn new() -> Self {
        static NEXT_ID: AtomicU64 = AtomicU64::new(0);
        Self(std::env::temp_dir().join(format!(
            "ds_saved_candles_{}_{}",
            std::process::id(),
            NEXT_ID.fetch_add(1, Ordering::Relaxed)
        )))
    }

    fn path(&self) -> PathBuf {
        self.0.join("history.sqlite")
    }

    fn open(&self) -> AppState {
        AppState::new(self.path())
    }

    fn connection(&self) -> rusqlite::Connection {
        rusqlite::Connection::open(self.path()).unwrap()
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

fn bar(price: i64) -> HistoricalCandle {
    HistoricalCandle {
        epoch_seconds: 1_700_000_000,
        open_cents: price,
        high_cents: price,
        low_cents: price,
        close_cents: price,
        volume: 100,
    }
}

fn store_exact(state: &AppState, range: ChartRequest, started: Instant, price: i64) {
    let generation = state.feed_generation.load(Ordering::SeqCst);
    let (_, accepted) = store_detail_candles(
        &EnrichmentGate::from_app(state),
        generation,
        "ABC",
        range,
        started,
        vec![bar(price)],
    )
    .unwrap();
    assert!(accepted);
}

#[test]
fn selected_session_range_restores_after_reopen_without_hydrating_live_inputs() {
    let db = TestDb::new();
    {
        let first = db.open();
        let mut older = bar(800);
        older.epoch_seconds -= 40 * 86_400;
        first
            .screener
            .lock()
            .unwrap()
            .ingest_daily_candles("ABC".into(), vec![older, bar(900)]);
        let session = read_detail_candles(" abc ", "1mo", &first)
            .unwrap()
            .unwrap();
        assert_eq!(session.source, DetailCandleSource::Session);
        assert_eq!(session.saved_at_epoch_seconds, None);
        assert_eq!(session.candles.len(), 1);
    }

    let reopened = db.open();
    assert!(read_detail_candles("ABC", "3mo", &reopened)
        .unwrap()
        .is_none());
    assert!(read_detail_candles("ABD", "1mo", &reopened)
        .unwrap()
        .is_none());
    assert!(read_detail_candles("ABC", "1wk", &reopened).is_err());
    let restored = read_detail_candles("ABC", "1mo", &reopened)
        .unwrap()
        .unwrap();
    let json = serde_json::to_value(restored).unwrap();
    assert_eq!(json["source"], "restored");
    assert!(json["saved_at_epoch_seconds"].as_i64().unwrap() > 0);
    assert_eq!(json["candles"][0]["close_cents"], 900);

    assert!(build_opportunity_rows(&reopened.screener, false, None).is_empty());
    {
        let screener = reopened.screener.lock().unwrap();
        assert!(screener.detail_charts.get("ABC", "1mo").is_none());
        assert!(screener.daily_candles.is_empty());
        assert!(screener.chart_summaries.is_empty());
        assert!(screener.weekly_summaries.is_empty());
        assert!(screener.hourly_summaries.is_empty());
        assert!(screener.monthly_summaries.is_empty());
        assert!(screener.alerts.is_empty());
    }

    *reopened.active_profile.lock().unwrap() = "qa".into();
    assert!(read_detail_candles("ABC", "1mo", &reopened)
        .unwrap()
        .is_none());
}

#[test]
fn session_exact_then_compatible_session_daily_take_precedence_over_disk() {
    let db = TestDb::new();
    let range = detail_chart_request("1mo").unwrap();
    let now = Instant::now();
    {
        let first = db.open();
        let generation = first.feed_generation.load(Ordering::SeqCst);
        store_exact(&first, range, now, 800);
        persist_loaded_detail_candles(&first, generation, "ABC", range);
    }
    let reopened = db.open();
    assert_eq!(
        read_detail_candles("ABC", "1mo", &reopened)
            .unwrap()
            .unwrap()
            .candles[0]
            .close_cents,
        800
    );
    reopened
        .screener
        .lock()
        .unwrap()
        .ingest_daily_candles("ABC".into(), vec![bar(1_000)]);
    let daily = read_detail_candles("ABC", "1mo", &reopened)
        .unwrap()
        .unwrap();
    assert_eq!(daily.source, DetailCandleSource::Session);
    assert_eq!(daily.candles[0].close_cents, 1_000);
    store_exact(&reopened, range, now, 1_200);
    let exact = read_detail_candles("ABC", "1mo", &reopened)
        .unwrap()
        .unwrap();
    assert_eq!(exact.source, DetailCandleSource::Session);
    assert_eq!(exact.candles[0].close_cents, 1_200);
}

#[test]
fn explicit_writes_use_the_accepted_exact_series_and_refuse_old_generations() {
    let db = TestDb::new();
    let range = detail_chart_request("1d").unwrap();
    let now = Instant::now();
    {
        let first = db.open();
        let generation = first.feed_generation.load(Ordering::SeqCst);
        let outcome = apply_detail_refresh(
            &EnrichmentGate::from_app(&first),
            generation,
            DetailRefresh {
                symbol: "ABC",
                range,
                started: now,
                quote: None,
                candles: vec![bar(800)],
            },
        )
        .unwrap();
        assert!(outcome.chart_updated);
        store_exact(&first, range, now + Duration::from_secs(1), 900);
        persist_loaded_detail_candles(&first, generation, "ABC", range);
        store_exact(&first, range, now + Duration::from_secs(2), 1_100);
        with_profile_apply_gate(&first.profile_apply_gate, || {
            first.feed_generation.fetch_add(1, Ordering::SeqCst);
            *first.active_profile.lock().unwrap() = "qa".into();
        });
        persist_loaded_detail_candles(&first, generation, "ABC", range);
    }
    let reopened = db.open();
    let prior = read_detail_candles("ABC", "1d", &reopened)
        .unwrap()
        .unwrap();
    assert_eq!(prior.source, DetailCandleSource::Restored);
    assert_eq!(prior.candles[0].close_cents, 900);
    *reopened.active_profile.lock().unwrap() = "qa".into();
    assert!(read_detail_candles("ABC", "1d", &reopened)
        .unwrap()
        .is_none());
}

#[test]
fn empty_refresh_and_database_write_failure_preserve_the_prior_chart() {
    let db = TestDb::new();
    let range = detail_chart_request("5d").unwrap();
    let now = Instant::now();
    {
        let first = db.open();
        let generation = first.feed_generation.load(Ordering::SeqCst);
        store_exact(&first, range, now, 900);
        persist_loaded_detail_candles(&first, generation, "ABC", range);
        assert!(store_detail_candles(
            &EnrichmentGate::from_app(&first),
            generation,
            "ABC",
            range,
            now + Duration::from_secs(1),
            Vec::new(),
        )
        .is_err());
        let current = read_detail_candles("ABC", "5d", &first).unwrap().unwrap();
        assert_eq!(current.candles[0].close_cents, 900);
        db.connection()
            .execute_batch(
                "CREATE TRIGGER refuse_candle_update BEFORE UPDATE ON detail_candle_cache
                 BEGIN SELECT RAISE(ABORT, 'test write failure'); END;",
            )
            .unwrap();
        store_exact(&first, range, now + Duration::from_secs(2), 1_000);
        let current = read_detail_candles("ABC", "5d", &first).unwrap().unwrap();
        assert_eq!(current.source, DetailCandleSource::Session);
        assert_eq!(current.candles[0].close_cents, 1_000);
    }
    let reopened = db.open();
    let prior = read_detail_candles("ABC", "5d", &reopened)
        .unwrap()
        .unwrap();
    assert_eq!(prior.source, DetailCandleSource::Restored);
    assert_eq!(prior.candles[0].close_cents, 900);
}

#[test]
fn corrupt_or_unknown_disk_series_acts_as_a_cache_miss() {
    let db = TestDb::new();
    let first = db.open();
    let range = detail_chart_request("5d").unwrap();
    store_exact(&first, range, Instant::now(), 900);
    let generation = first.feed_generation.load(Ordering::SeqCst);
    persist_loaded_detail_candles(&first, generation, "ABC", range);
    drop(first);
    let reopened = db.open();
    let sql = db.connection();
    sql.execute_batch("UPDATE detail_candle_cache SET payload_json = 'not JSON';")
        .unwrap();
    assert!(read_detail_candles("ABC", "5d", &reopened)
        .unwrap()
        .is_none());
    sql.execute_batch("UPDATE detail_candle_cache SET format_version = 999;")
        .unwrap();
    assert!(read_detail_candles("ABC", "5d", &reopened)
        .unwrap()
        .is_none());
}

#[test]
fn candle_cache_bounds_series_count_bar_count_and_stored_bytes() {
    use crate::db::saved_candles::{MAX_SAVED_CANDLE_BARS, MAX_SAVED_CANDLE_SERIES};

    let db = TestDb::new();
    let state = db.open();
    for index in 0..=MAX_SAVED_CANDLE_SERIES {
        state
            .db
            .save_detail_candles("sp500", &format!("ABC{index}"), "5d", &[bar(900)])
            .unwrap();
    }
    let sql = db.connection();
    let count: usize = sql
        .query_row("SELECT COUNT(*) FROM detail_candle_cache", [], |row| {
            row.get(0)
        })
        .unwrap();
    assert_eq!(count, MAX_SAVED_CANDLE_SERIES);
    assert!(read_detail_candles("ABC0", "5d", &state).unwrap().is_none());

    let bars: Vec<_> = (0..=MAX_SAVED_CANDLE_BARS)
        .map(|index| {
            let mut candle = bar(1_000);
            candle.epoch_seconds += index as i64;
            candle
        })
        .collect();
    assert!(state
        .db
        .save_detail_candles("sp500", "ABC1", "5d", &bars)
        .is_err());
    let prior = read_detail_candles("ABC1", "5d", &state).unwrap().unwrap();
    assert_eq!(prior.candles[0].close_cents, 900);

    sql.execute_batch("UPDATE detail_candle_cache SET payload_json = CAST(zeroblob(262145) AS TEXT) WHERE symbol = 'ABC1';")
        .unwrap();
    assert!(read_detail_candles("ABC1", "5d", &state).unwrap().is_none());
}
