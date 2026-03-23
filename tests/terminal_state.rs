use discount_screener::CandidateRow;
use discount_screener::ConfidenceBand;
use discount_screener::ExternalValuationSignal;
use discount_screener::MarketSnapshot;
use discount_screener::TerminalState;

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
fn qualifies_profitable_discounted_symbol_without_external_signal() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, 8_000, 10_000));

    assert_eq!(
        state.candidate("ACME"),
        Some(CandidateRow {
            symbol: "ACME".to_string(),
            market_price_cents: 8_000,
            intrinsic_value_cents: 10_000,
            gap_bps: 2_000,
            is_qualified: true,
            confidence: ConfidenceBand::Provisional,
        })
    );
}

#[test]
fn supportive_external_signal_boosts_confidence_without_changing_internal_logic() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, 8_000, 10_000));
    state.ingest_external(external_signal("ACME", 12_000, 5));

    assert_eq!(
        state
            .candidate("ACME")
            .map(|row| (row.is_qualified, row.confidence)),
        Some((true, ConfidenceBand::High))
    );
}

#[test]
fn disagreeing_external_signal_keeps_internal_qualification() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, 8_000, 10_000));
    state.ingest_external(external_signal("ACME", 7_000, 5));

    assert_eq!(
        state
            .candidate("ACME")
            .map(|row| (row.is_qualified, row.confidence)),
        Some((true, ConfidenceBand::Low))
    );
}

#[test]
fn ranks_candidates_by_gap_then_confidence_then_symbol() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("BETA", true, 8_000, 10_000));
    state.ingest_snapshot(market_snapshot("ALFA", true, 7_000, 10_000));
    state.ingest_snapshot(market_snapshot("CHAR", true, 8_000, 10_000));
    state.ingest_external(external_signal("BETA", 12_000, 5));

    assert_eq!(
        state
            .top_rows(3)
            .into_iter()
            .map(|row| (row.symbol, row.gap_bps, row.confidence))
            .collect::<Vec<_>>(),
        vec![
            ("ALFA".to_string(), 3_000, ConfidenceBand::Provisional),
            ("BETA".to_string(), 2_000, ConfidenceBand::High),
            ("CHAR".to_string(), 2_000, ConfidenceBand::Provisional),
        ]
    );
}

#[test]
fn keeps_a_bounded_recent_tape_for_high_throughput_rendering() {
    let mut state = TerminalState::new(2_000, 30, 3);

    state.ingest_snapshot(market_snapshot("ALFA", true, 8_000, 10_000));
    state.ingest_snapshot(market_snapshot("BETA", true, 8_000, 10_000));
    state.ingest_snapshot(market_snapshot("CHAR", true, 8_000, 10_000));
    state.ingest_snapshot(market_snapshot("DELTA", true, 8_000, 10_000));

    assert_eq!(
        state
            .recent_tape()
            .into_iter()
            .map(|event| event.symbol)
            .collect::<Vec<_>>(),
        vec!["BETA".to_string(), "CHAR".to_string(), "DELTA".to_string(),]
    );
}
