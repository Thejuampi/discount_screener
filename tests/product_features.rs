use std::env;
use std::fs;
use std::path::PathBuf;
use std::time::SystemTime;
use std::time::UNIX_EPOCH;

use discount_screener::AlertKind;
use discount_screener::ConfidenceBand;
use discount_screener::ExternalValuationSignal;
use discount_screener::MarketSnapshot;
use discount_screener::TerminalState;
use discount_screener::ViewFilter;

fn market_snapshot(
    symbol: &str,
    profitable: bool,
    market_price_cents: i64,
    intrinsic_value_cents: i64,
) -> MarketSnapshot {
    MarketSnapshot {
        symbol: symbol.to_string(),
        profitable,
        market_price_cents,
        intrinsic_value_cents,
    }
}

fn external_signal(
    symbol: &str,
    fair_value_cents: i64,
    age_seconds: u64,
) -> ExternalValuationSignal {
    ExternalValuationSignal {
        symbol: symbol.to_string(),
        fair_value_cents,
        age_seconds,
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
    }
}

#[test]
fn replays_extended_analyst_consensus_fields_from_the_journal() {
    let journal_path = temp_file_path("analyst_consensus_round_trip");
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("NVDA", true, 17_270, 26_923));
    state.ingest_external(ExternalValuationSignal {
        symbol: "NVDA".to_string(),
        fair_value_cents: 26_500,
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
        weighted_fair_value_cents: Some(27_200),
        weighted_analyst_count: Some(9),
    });
    state
        .save_journal_file(&journal_path)
        .expect("journal should be written");

    let replayed =
        TerminalState::replay_file(2_000, 30, 8, &journal_path).expect("journal should replay");

    fs::remove_file(&journal_path).ok();

    assert_eq!(
        replayed.detail("NVDA").map(|detail| (
            detail.external_signal_fair_value_cents,
            detail.external_signal_low_fair_value_cents,
            detail.external_signal_high_fair_value_cents,
            detail.analyst_opinion_count,
            detail.recommendation_mean_hundredths,
            detail.strong_buy_count,
            detail.buy_count,
            detail.hold_count,
            detail.sell_count,
            detail.strong_sell_count,
            detail.weighted_external_signal_fair_value_cents,
            detail.weighted_analyst_count,
        )),
        Some((
            Some(26_500),
            Some(18_500),
            Some(32_000),
            Some(42),
            Some(185),
            Some(20),
            Some(10),
            Some(8),
            Some(3),
            Some(1),
            Some(27_200),
            Some(9),
        ))
    );
}

fn temp_file_path(stem: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system time should be after epoch")
        .as_nanos();
    env::temp_dir().join(format!("discount_screener_{stem}_{nanos}.log"))
}

#[test]
fn persists_and_restores_state_from_a_journal_file() {
    let file_path = temp_file_path("round_trip");
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("BETA", true, 8_000, 10_000));
    state.ingest_snapshot(market_snapshot("ALFA", true, 7_000, 10_000));
    state.ingest_external(external_signal("BETA", 12_000, 5));
    state
        .save_journal_file(&file_path)
        .expect("journal should be written");

    let replayed =
        TerminalState::replay_file(2_000, 30, 8, &file_path).expect("journal file should replay");

    fs::remove_file(&file_path).ok();

    assert_eq!(
        (
            replayed.top_rows(3),
            replayed
                .alerts()
                .into_iter()
                .map(|alert| (alert.symbol, alert.kind))
                .collect::<Vec<_>>(),
        ),
        (
            state.top_rows(3),
            state
                .alerts()
                .into_iter()
                .map(|alert| (alert.symbol, alert.kind))
                .collect::<Vec<_>>(),
        )
    );
}

#[test]
fn replays_a_seeded_event_file_into_the_expected_ranked_view() {
    let file_path = temp_file_path("seeded_feed");
    fs::write(
        &file_path,
        concat!(
            "S|1|BETA|1|8000|10000\n",
            "S|2|ALFA|1|7000|10000\n",
            "E|3|BETA|12000|5\n"
        ),
    )
    .expect("seed file should be written");

    let replayed =
        TerminalState::replay_file(2_000, 30, 8, &file_path).expect("seed file should replay");

    fs::remove_file(&file_path).ok();

    assert_eq!(
        replayed
            .top_rows(3)
            .into_iter()
            .map(|row| (row.symbol, row.gap_bps, row.confidence))
            .collect::<Vec<_>>(),
        vec![
            ("ALFA".to_string(), 3_000, ConfidenceBand::Provisional),
            ("BETA".to_string(), 2_000, ConfidenceBand::High),
        ]
    );
}

#[test]
fn filters_rows_by_watchlist_and_search_query() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ALFA", true, 7_000, 10_000));
    state.ingest_snapshot(market_snapshot("ALGO", true, 8_000, 10_000));
    state.ingest_snapshot(market_snapshot("BETA", true, 6_000, 10_000));
    state.toggle_watchlist("ALFA");
    state.toggle_watchlist("ALGO");

    assert_eq!(
        state
            .filtered_rows(
                10,
                &ViewFilter {
                    query: "AL".to_string(),
                    watchlist_only: true,
                },
            )
            .into_iter()
            .map(|row| row.symbol)
            .collect::<Vec<_>>(),
        vec!["ALFA".to_string(), "ALGO".to_string(),]
    );
}

#[test]
fn marks_symbol_detail_as_watched_after_watchlist_toggle() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ALFA", true, 7_000, 10_000));
    state.toggle_watchlist("ALFA");

    assert_eq!(
        state.detail("ALFA").map(|detail| detail.is_watched),
        Some(true)
    );
}

#[test]
fn reloads_journal_file_with_alert_history_intact() {
    let file_path = temp_file_path("alert_round_trip");
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, 8_000, 10_000));
    state.ingest_external(external_signal("ACME", 12_000, 5));
    state.ingest_snapshot(market_snapshot("ACME", false, 8_000, 10_000));
    state
        .save_journal_file(&file_path)
        .expect("journal should be written");

    let replayed =
        TerminalState::replay_file(2_000, 30, 8, &file_path).expect("journal file should replay");

    fs::remove_file(&file_path).ok();

    assert_eq!(
        replayed
            .alerts()
            .into_iter()
            .map(|alert| alert.kind)
            .collect::<Vec<_>>(),
        vec![
            AlertKind::EnteredQualified,
            AlertKind::ConfidenceUpgraded,
            AlertKind::ExitedQualified,
        ]
    );
}

#[test]
fn saves_and_loads_watchlist_membership_from_disk() {
    let watchlist_path = temp_file_path("watchlist_round_trip");
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ALFA", true, 7_000, 10_000));
    state.ingest_snapshot(market_snapshot("BETA", true, 6_000, 10_000));
    state.toggle_watchlist("ALFA");
    state
        .save_watchlist_file(&watchlist_path)
        .expect("watchlist should be written");

    let mut restored = TerminalState::new(2_000, 30, 8);
    restored.ingest_snapshot(market_snapshot("ALFA", true, 7_000, 10_000));
    restored.ingest_snapshot(market_snapshot("BETA", true, 6_000, 10_000));
    restored
        .load_watchlist_file(&watchlist_path)
        .expect("watchlist should be loaded");

    fs::remove_file(&watchlist_path).ok();

    assert_eq!(
        restored
            .filtered_rows(
                10,
                &ViewFilter {
                    query: String::new(),
                    watchlist_only: true,
                },
            )
            .into_iter()
            .map(|row| row.symbol)
            .collect::<Vec<_>>(),
        vec!["ALFA".to_string()]
    );
}

#[test]
fn appends_new_journal_entries_to_disk_without_losing_prior_history() {
    let journal_path = temp_file_path("incremental_append");
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, 8_000, 10_000));
    TerminalState::append_journal_file(&journal_path, &state.journal_since(0))
        .expect("initial append should succeed");

    state.ingest_external(external_signal("ACME", 12_000, 5));
    TerminalState::append_journal_file(&journal_path, &state.journal_since(1))
        .expect("incremental append should succeed");

    let replayed = TerminalState::replay_file(2_000, 30, 8, &journal_path)
        .expect("appended journal should replay");

    fs::remove_file(&journal_path).ok();

    assert_eq!(
        replayed
            .alerts()
            .into_iter()
            .map(|alert| alert.kind)
            .collect::<Vec<_>>(),
        vec![AlertKind::EnteredQualified, AlertKind::ConfidenceUpgraded,]
    );
}
