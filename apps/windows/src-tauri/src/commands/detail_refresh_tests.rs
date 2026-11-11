use super::*;
use crate::engine::{MarketSnapshot, ScreenerState};
use crate::fetcher::FetchResult;
use std::sync::atomic::AtomicU64;

fn gate() -> EnrichmentGate {
    EnrichmentGate::for_test(
        Arc::new(AtomicU64::new(1)),
        Arc::new(Mutex::new(ScreenerState::new())),
        Arc::new(AtomicU64::new(0)),
    )
}

fn quote(price: i64) -> FetchResult {
    FetchResult {
        symbol: "ABC".into(),
        snapshot: Some(MarketSnapshot {
            symbol: "ABC".into(),
            company_name: None,
            profitable: false,
            market_price_cents: price,
            intrinsic_value_cents: 0,
            previous_close_cents: 0,
            next_earnings_epoch: None,
        }),
        signal: None,
        fundamentals: None,
    }
}

fn candle(price: i64) -> HistoricalCandle {
    HistoricalCandle {
        epoch_seconds: 1_700_000_000,
        open_cents: price,
        high_cents: price,
        low_cents: price,
        close_cents: price,
        volume: 100,
    }
}

fn apply(
    gate: &EnrichmentGate,
    generation: u64,
    started: Instant,
    quote: Option<FetchResult>,
    candles: Vec<HistoricalCandle>,
) -> Result<DetailRefreshOutcome, String> {
    apply_detail_refresh(
        gate,
        generation,
        DetailRefresh {
            symbol: "ABC",
            range: detail_chart_request("3mo").unwrap(),
            started,
            quote,
            candles,
        },
    )
}

#[test]
fn publishes_quote_and_chart_then_preserves_both_on_total_failure() {
    let gate = gate();
    let started = Instant::now();
    let result = apply(&gate, 1, started, Some(quote(1_000)), vec![candle(900)]).unwrap();
    assert!(result.quote_updated && result.chart_updated);
    assert!(result.issues.is_empty());

    let result = apply(&gate, 1, started, None, Vec::new()).unwrap();
    assert!(!result.quote_updated && !result.chart_updated);
    assert_eq!(
        result.issues,
        [
            DetailRefreshIssue::QuoteUnavailable,
            DetailRefreshIssue::ChartUnavailable
        ]
    );
    let state = gate.screener.lock().unwrap();
    assert_eq!(state.detail("ABC").unwrap().market_price_cents, 1_000);
    assert_eq!(
        cached_detail_candles(&state, "ABC", "3mo")[0].close_cents,
        900
    );
}

#[test]
fn reports_partial_refresh_and_keeps_a_chart_close_out_of_the_quote() {
    let gate = gate();
    let started = Instant::now();
    let result = apply(&gate, 1, started, Some(quote(1_000)), Vec::new()).unwrap();
    assert!(result.quote_updated && !result.chart_updated);
    assert_eq!(result.issues, [DetailRefreshIssue::ChartUnavailable]);

    let result = apply(&gate, 1, started, Some(quote(0)), vec![candle(1_200)]).unwrap();
    assert!(!result.quote_updated && result.chart_updated);
    assert_eq!(result.issues, [DetailRefreshIssue::QuoteUnavailable]);
    let state = gate.screener.lock().unwrap();
    assert_eq!(state.detail("ABC").unwrap().market_price_cents, 1_000);
    assert_eq!(
        cached_detail_candles(&state, "ABC", "3mo")[0].close_cents,
        1_200
    );
}

#[test]
fn missing_quote_and_available_chart_do_not_create_a_snapshot() {
    let gate = gate();
    let result = apply(&gate, 1, Instant::now(), None, vec![candle(900)]).unwrap();
    assert!(!result.quote_updated && result.chart_updated);
    let state = gate.screener.lock().unwrap();
    assert!(state.detail("ABC").is_none());
    assert_eq!(
        cached_detail_candles(&state, "ABC", "3mo")[0].close_cents,
        900
    );
}

#[test]
fn older_explicit_and_feed_results_cannot_overwrite_fresh_detail() {
    let gate = gate();
    let old = Instant::now();
    let new = old + Duration::from_secs(1);
    apply(&gate, 1, new, Some(quote(2_000)), vec![candle(1_800)]).unwrap();
    let older = apply(&gate, 1, old, Some(quote(1_000)), vec![candle(900)]).unwrap();
    assert!(!older.quote_updated && !older.chart_updated);
    assert!(apply_refresh_quote_if_current(&gate, 1, old, quote(1_000), false, false).is_some());
    let state = gate.screener.lock().unwrap();
    assert_eq!(state.detail("ABC").unwrap().market_price_cents, 2_000);
    assert_eq!(
        cached_detail_candles(&state, "ABC", "3mo")[0].close_cents,
        1_800
    );
}

#[test]
fn profile_change_refuses_late_detail_and_range_results() {
    let gate = gate();
    gate.active_generation.store(2, Ordering::SeqCst);
    let started = Instant::now();
    assert!(apply(&gate, 1, started, Some(quote(1_000)), vec![candle(900)]).is_err());
    assert!(store_detail_candles(
        &gate,
        1,
        "ABC",
        detail_chart_request("3mo").unwrap(),
        started,
        vec![candle(900)]
    )
    .is_err());
    let state = gate.screener.lock().unwrap();
    assert!(state.detail("ABC").is_none());
    assert!(cached_detail_candles(&state, "ABC", "3mo").is_empty());
}

#[test]
fn reuses_saved_daily_candles_only_for_a_compatible_range() {
    let mut state = ScreenerState::new();
    let latest = candle(1_000);
    let mut old = candle(900);
    old.epoch_seconds -= 40 * 86_400;
    state.ingest_daily_candles("ABC".into(), vec![old, latest]);
    assert_eq!(cached_detail_candles(&state, "ABC", "1mo").len(), 1);
    assert_eq!(cached_detail_candles(&state, "ABC", "3mo").len(), 2);
    assert!(cached_detail_candles(&state, "ABC", "1d").is_empty());
}

#[test]
fn detail_enrichment_fills_missing_daily_history_and_preserves_later_feed_history() {
    let gate = gate();
    let daily = |price| {
        (0..40)
            .map(|index| {
                let mut bar = candle(price);
                bar.epoch_seconds += index * 86_400;
                bar
            })
            .collect::<Vec<_>>()
    };
    let initial = daily(1_000);
    seed_missing_detail_daily(
        &gate,
        1,
        "ABC",
        compute_chart_summary(&initial).unwrap(),
        initial,
    );
    {
        let state = gate.screener.lock().unwrap();
        assert_eq!(state.daily_candles["ABC"].len(), 40);
        assert_eq!(state.chart_summaries["ABC"].latest_close_cents, 1_000);
    }
    let later = daily(2_000);
    assert!(ingest_daily_chart_if_current(
        &gate,
        1,
        "ABC".into(),
        compute_chart_summary(&later).unwrap(),
        later,
    ));
    let older = daily(1_500);
    seed_missing_detail_daily(
        &gate,
        1,
        "ABC",
        compute_chart_summary(&older).unwrap(),
        older,
    );
    let state = gate.screener.lock().unwrap();
    assert_eq!(state.chart_summaries["ABC"].latest_close_cents, 2_000);
    assert_eq!(state.daily_candles["ABC"][0].close_cents, 2_000);
}
