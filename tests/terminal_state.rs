mod support;

use discount_screener::CandidateRow;
use discount_screener::ConfidenceBand;
use discount_screener::ExternalValuationSignal;
use discount_screener::TerminalState;
use support::external_signal;
use support::market_snapshot;

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

#[test]
fn clamps_large_negative_gap_values_instead_of_overflowing() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, i64::MAX, 1));

    assert_eq!(
        state.candidate("ACME").map(|row| row.gap_bps),
        Some(i32::MIN)
    );
}

#[test]
fn ignores_non_positive_weighted_targets_from_external_signals() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, 8_000, 10_000));
    state.ingest_external(ExternalValuationSignal {
        weighted_fair_value_cents: Some(0),
        weighted_analyst_count: Some(9),
        ..external_signal("ACME", 12_000, 5)
    });

    assert_eq!(
        state.detail("ACME").map(|detail| (
            detail.weighted_external_signal_fair_value_cents,
            detail.weighted_analyst_count
        )),
        Some((None, None))
    );
}
