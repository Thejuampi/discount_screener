mod support;

use discount_screener::AlertKind;
use discount_screener::ConfidenceBand;
use discount_screener::ExternalSignalStatus;
use discount_screener::QualificationStatus;
use discount_screener::TerminalState;
use support::external_signal;
use support::market_snapshot;

#[test]
fn describes_internal_and_external_state_for_a_qualified_symbol() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, 8_000, 10_000));

    assert_eq!(
        state.detail("ACME").map(|detail| (
            detail.qualification,
            detail.external_status,
            detail.confidence
        )),
        Some((
            QualificationStatus::Qualified,
            ExternalSignalStatus::Missing,
            ConfidenceBand::Provisional,
        ))
    );
}

#[test]
fn emits_an_alert_when_a_symbol_enters_the_qualified_set() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, 8_000, 10_000));

    assert_eq!(
        state
            .alerts()
            .into_iter()
            .map(|alert| (alert.symbol, alert.kind))
            .collect::<Vec<_>>(),
        vec![("ACME".to_string(), AlertKind::EnteredQualified)]
    );
}

#[test]
fn emits_a_confidence_upgrade_alert_when_supportive_external_value_arrives() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, 8_000, 10_000));
    state.ingest_external(external_signal("ACME", 12_000, 5));

    assert_eq!(
        state.alerts().last().map(|alert| alert.kind),
        Some(AlertKind::ConfidenceUpgraded)
    );
}

#[test]
fn emits_an_exit_alert_when_a_symbol_loses_internal_qualification() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("ACME", true, 8_000, 10_000));
    state.ingest_snapshot(market_snapshot("ACME", false, 8_000, 10_000));

    assert_eq!(
        state
            .alerts()
            .into_iter()
            .map(|alert| alert.kind)
            .collect::<Vec<_>>(),
        vec![AlertKind::EnteredQualified, AlertKind::ExitedQualified]
    );
}

#[test]
fn replays_the_journal_into_the_same_ranked_state() {
    let mut state = TerminalState::new(2_000, 30, 8);

    state.ingest_snapshot(market_snapshot("BETA", true, 8_000, 10_000));
    state.ingest_snapshot(market_snapshot("ALFA", true, 7_000, 10_000));
    state.ingest_external(external_signal("BETA", 12_000, 5));

    let replayed = TerminalState::replay(2_000, 30, 8, &state.journal());

    assert_eq!(replayed.top_rows(3), state.top_rows(3));
}
