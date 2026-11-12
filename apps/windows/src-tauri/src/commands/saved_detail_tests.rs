use super::*;
use crate::engine::{FundamentalSnapshot, MarketSnapshot};
use crate::fetcher::FetchResult;
use crate::saved_detail::{DetailAssetType, DetailSource, SavedTickerDetail};
use std::path::PathBuf;

struct TestDb {
    directory: PathBuf,
}

impl TestDb {
    fn new() -> Self {
        let epoch = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap();
        Self {
            directory: std::env::temp_dir().join(format!(
                "ds_saved_detail_{}_{}",
                std::process::id(),
                epoch.as_nanos()
            )),
        }
    }

    fn path(&self) -> PathBuf {
        self.directory.join("history.sqlite")
    }

    fn open(&self) -> AppState {
        AppState::new(self.path())
    }

    fn sql(&self, sql: &str) {
        rusqlite::Connection::open(self.path())
            .unwrap()
            .execute_batch(sql)
            .unwrap();
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
            let _ = std::fs::remove_file(self.directory.join(name));
        }
        let _ = std::fs::remove_dir(&self.directory);
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
            intrinsic_value_cents: 30_000,
            previous_close_cents: price,
            next_earnings_epoch: None,
        }),
        signal: None,
        fundamentals: Some(FundamentalSnapshot {
            symbol: symbol.into(),
            sector_name: Some("Technology".into()),
            market_cap_dollars: Some(1_000_000_000),
            ..Default::default()
        }),
    }
}

fn ingest_quote(state: &AppState, quote: FetchResult) {
    let mut screener = state.screener.lock().unwrap();
    assert!(ingest_current_quote(&mut screener, Instant::now(), quote, false, false).1);
}

#[test]
fn reopening_restores_quote_and_model_provenance_without_rank_or_alert_inputs() {
    let db = TestDb::new();
    let market_price;
    let prior_model;
    {
        let first = db.open();
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../../tests/fixtures/yahoo/quoteSummary/COF-retention.json"
        ))
        .unwrap();
        ingest_quote(
            &first,
            crate::quote_summary::parse_quote_summary(&fixture, "COF"),
        );
        let live = read_ticker_detail(" cof ", &first).unwrap().unwrap();
        assert_eq!(live.detail_source, DetailSource::Session);
        assert_eq!(live.saved_at_epoch_seconds, None);
        market_price = live.detail.market_price_cents;
        prior_model = serde_json::to_value(live.detail.dcf_analysis.unwrap()).unwrap();
    }

    let reopened = db.open();
    let restored = read_ticker_detail("cof", &reopened).unwrap().unwrap();
    assert_eq!(restored.detail_source, DetailSource::Restored);
    assert!(restored.saved_at_epoch_seconds.unwrap() > 0);
    assert_eq!(restored.asset_type, Some(DetailAssetType::Stock));
    assert_eq!(restored.detail.market_price_cents, market_price);
    assert_eq!(
        serde_json::to_value(restored.detail.dcf_analysis.unwrap()).unwrap(),
        prior_model
    );
    assert!(build_opportunity_rows(&reopened.screener, false, None).is_empty());
    let screener = reopened.screener.lock().unwrap();
    assert!(screener.detail("COF").is_none());
    assert!(screener.fundamentals.is_empty());
    assert!(screener.alerts.is_empty());
}

#[test]
fn accepted_detail_refresh_persists_without_a_panel_read_and_replaces_saved_data() {
    let db = TestDb::new();
    {
        let first = db.open();
        ingest_quote(&first, quote("AAPL", 10_000));
        read_ticker_detail("AAPL", &first).unwrap().unwrap();
    }
    {
        let reopened = db.open();
        let gate = EnrichmentGate::from_app(&reopened);
        let generation = reopened.feed_generation.load(Ordering::SeqCst);
        let outcome = apply_detail_refresh(
            &gate,
            generation,
            DetailRefresh {
                symbol: "AAPL",
                range: detail_chart_request("3mo").unwrap(),
                started: Instant::now(),
                quote: Some(quote("AAPL", 12_345)),
                candles: Vec::new(),
            },
        )
        .unwrap();
        assert!(outcome.quote_updated);
        persist_detail_refresh(&reopened, generation, "AAPL", &outcome);
    }
    let reopened = db.open();
    let restored = read_ticker_detail("AAPL", &reopened).unwrap().unwrap();
    assert_eq!(restored.detail_source, DetailSource::Restored);
    assert_eq!(restored.detail.market_price_cents, 12_345);

    ingest_quote(&reopened, quote("AAPL", 13_579));
    let live = read_ticker_detail("AAPL", &reopened).unwrap().unwrap();
    assert_eq!(live.detail_source, DetailSource::Session);
    assert_eq!(live.detail.market_price_cents, 13_579);
}

#[test]
fn chart_fallback_and_failed_explicit_refresh_preserve_the_saved_quote() {
    let db = TestDb::new();
    {
        let first = db.open();
        ingest_quote(&first, quote("AAPL", 10_000));
        read_ticker_detail("AAPL", &first).unwrap().unwrap();
    }
    let reopened = db.open();
    let gate = EnrichmentGate::from_app(&reopened);
    let generation = reopened.feed_generation.load(Ordering::SeqCst);
    let candles = vec![HistoricalCandle {
        epoch_seconds: 1_700_000_000,
        open_cents: 12_000,
        high_cents: 12_000,
        low_cents: 12_000,
        close_cents: 12_000,
        volume: 100,
    }];
    let outcome = apply_detail_refresh(
        &gate,
        generation,
        DetailRefresh {
            symbol: "AAPL",
            range: detail_chart_request("3mo").unwrap(),
            started: Instant::now(),
            quote: None,
            candles: candles.clone(),
        },
    )
    .unwrap();
    assert!(!outcome.quote_updated && outcome.chart_updated);
    persist_detail_refresh(&reopened, generation, "AAPL", &outcome);
    let feed_fallback = apply_refresh_chart_if_current(
        &gate,
        generation,
        "AAPL",
        candles,
        RefreshOutcome::default(),
    )
    .unwrap();
    assert!(feed_fallback.visible);

    let restored = read_ticker_detail("AAPL", &reopened).unwrap().unwrap();
    assert_eq!(restored.detail_source, DetailSource::Restored);
    assert_eq!(restored.detail.market_price_cents, 10_000);
}

#[test]
fn profile_switch_excludes_saved_detail_and_rejects_late_persistence() {
    let db = TestDb::new();
    let first = db.open();
    ingest_quote(&first, quote("AAPL", 10_000));
    read_ticker_detail("AAPL", &first).unwrap().unwrap();
    let original_generation = first.feed_generation.load(Ordering::SeqCst);
    with_profile_apply_gate(&first.profile_apply_gate, || {
        *first.active_profile.lock().unwrap() = "dow".into();
        first.feed_generation.fetch_add(1, Ordering::SeqCst);
        first.screener.lock().unwrap().clear_universe();
    });
    assert!(read_ticker_detail("AAPL", &first).unwrap().is_none());
    ingest_quote(&first, quote("AAPL", 15_000));
    persist_detail_refresh(
        &first,
        original_generation,
        "AAPL",
        &DetailRefreshOutcome {
            quote_updated: true,
            ..Default::default()
        },
    );
    assert!(first
        .db
        .load_saved_ticker_detail("dow", "AAPL")
        .unwrap()
        .is_none());
    let saved = first
        .db
        .load_saved_ticker_detail("sp500", "AAPL")
        .unwrap()
        .unwrap();
    assert_eq!(saved.detail.market_price_cents, 10_000);
}

#[test]
fn unknown_or_corrupt_storage_refuses_restore_and_keeps_live_detail_available() {
    let db = TestDb::new();
    {
        let first = db.open();
        ingest_quote(&first, quote("AAPL", 10_000));
        ingest_quote(&first, quote("MSFT", 20_000));
        read_ticker_detail("AAPL", &first).unwrap().unwrap();
        read_ticker_detail("MSFT", &first).unwrap().unwrap();
    }
    db.sql(
        "UPDATE ticker_detail_cache SET format_version = 99 WHERE symbol = 'AAPL';
         UPDATE ticker_detail_cache SET payload_json = 'not json' WHERE symbol = 'MSFT';",
    );
    let reopened = db.open();
    assert!(read_ticker_detail("AAPL", &reopened).is_err());
    assert!(read_ticker_detail("MSFT", &reopened).is_err());
    ingest_quote(&reopened, quote("AAPL", 11_111));
    let live = read_ticker_detail("AAPL", &reopened).unwrap().unwrap();
    assert_eq!(live.detail_source, DetailSource::Session);
    assert_eq!(live.detail.market_price_cents, 11_111);
    assert!(reopened
        .db
        .load_saved_ticker_detail("sp500", "AAPL")
        .is_err());
}

#[test]
fn failed_cache_write_preserves_usable_session_detail() {
    let db = TestDb::new();
    let state = db.open();
    db.sql("DROP TABLE ticker_detail_cache;");
    ingest_quote(&state, quote("AAPL", 19_000));
    let detail = read_ticker_detail("AAPL", &state).unwrap().unwrap();
    assert_eq!(detail.detail_source, DetailSource::Session);
    assert_eq!(detail.detail.market_price_cents, 19_000);
}

#[test]
fn exact_saved_ticker_search_opens_off_universe_detail_locally() {
    let db = TestDb::new();
    {
        let first = db.open();
        ingest_quote(&first, quote("ZLOCAL", 10_000));
        let live = read_ticker_detail("ZLOCAL", &first).unwrap().unwrap();
        assert_eq!(live.asset_type, None);
    }
    let reopened = db.open();
    let candidates = local_ticker_candidates("zlocal", &reopened);
    let ranked = merge_and_rank(&candidates, 8);
    assert_eq!(ranked.len(), 1);
    assert_eq!(ranked[0].symbol, "ZLOCAL");
    assert!(!ranked[0].is_remote);
    assert!(!ranked[0].in_current_profile);
    assert!(ranked[0].profiles.is_empty());
    assert!(!should_trigger_remote_search("zlocal", &ranked));
    assert!(matches!(
        resolve_search_submit("zlocal", &ranked),
        SearchSubmitOutcome::Open { symbol } if symbol == "ZLOCAL"
    ));
    assert!(local_ticker_candidates("zloc", &reopened).is_empty());
    *reopened.active_profile.lock().unwrap() = "dow".into();
    assert!(local_ticker_candidates("zlocal", &reopened).is_empty());
}

#[test]
fn cache_retains_only_bounded_latest_details_and_repeated_read_does_not_change_capture_time() {
    let db = crate::db::Db::open_in_memory().unwrap();
    let mut screener = crate::engine::ScreenerState::new();
    for i in 0..=crate::db::MAX_SAVED_TICKER_DETAILS {
        let symbol = format!("TEST{i}");
        screener.ingest_snapshot(quote(&symbol, 10_000).snapshot.unwrap());
        let saved = SavedTickerDetail::new(screener.detail(&symbol).unwrap(), None, i as i64);
        db.save_ticker_detail("sp500", &saved).unwrap();
    }
    assert!(db
        .load_saved_ticker_detail("sp500", "TEST0")
        .unwrap()
        .is_none());
    let original = db
        .load_saved_ticker_detail("sp500", "TEST1")
        .unwrap()
        .unwrap();
    let repeated = SavedTickerDetail::new(original.detail, None, 500_000);
    db.save_ticker_detail("sp500", &repeated).unwrap();
    let saved = db
        .load_saved_ticker_detail("sp500", "TEST1")
        .unwrap()
        .unwrap();
    assert_eq!(saved.saved_at_epoch_ms, 1);
}
