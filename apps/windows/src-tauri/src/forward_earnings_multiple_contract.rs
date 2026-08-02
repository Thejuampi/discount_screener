//! Shared-contract harness for ForwardEarningsMultiple (Slice 1A).

use crate::forward_earnings_multiple::*;
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Contract {
    engine_id: String,
    method_policy_version: String,
    fixtures: Fixtures,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Fixtures {
    available: Vec<AvailableFixture>,
    refusals: Vec<RefusalFixture>,
    mutation_invariants: Vec<MutationFixture>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AvailableFixture {
    name: String,
    input: InputDto,
    expected_target_value_cents: i64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RefusalFixture {
    name: String,
    input: InputDto,
    expected_reason_code: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MutationFixture {
    name: String,
    base: InputDto,
    mutated: InputDto,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct InputDto {
    issuer_id: String,
    security_id: Option<String>,
    metric_id: String,
    metric_basis: String,
    eps_cents: i64,
    multiple_hundredths: i32,
    multiple_provenance: String,
    forecast_period_end: String,
    target_as_of: String,
    date_precision: String,
    currency: String,
    evidence_observed_at_unix_ms: i64,
    #[serde(default)]
    market_price_cents: Option<i64>,
    #[serde(default)]
    stated_target_cents: Option<i64>,
    #[serde(default)]
    peer_count: Option<u32>,
}

fn load() -> Contract {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../shared/contracts/valuation-forward-earnings-multiple-v1.json");
    let raw =
        std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
    serde_json::from_str(&raw).unwrap_or_else(|e| panic!("parse: {e}"))
}

fn to_input(d: &InputDto) -> ForwardEarningsMultipleInput {
    let provenance = match d.multiple_provenance.as_str() {
        "analyst_stated" => MultipleProvenance::AnalystStated,
        "peer_policy_derived" => MultipleProvenance::PeerPolicyDerived,
        other => panic!("unknown provenance {other}"),
    };
    ForwardEarningsMultipleInput {
        issuer_id: d.issuer_id.clone(),
        security_id: d.security_id.clone(),
        metric_id: d.metric_id.clone(),
        metric_basis: d.metric_basis.clone(),
        eps_cents: d.eps_cents,
        multiple_hundredths: d.multiple_hundredths,
        multiple_provenance: provenance,
        forecast_period_end: d.forecast_period_end.clone(),
        target_as_of: d.target_as_of.clone(),
        date_precision: d.date_precision.clone(),
        currency: d.currency.clone(),
        evidence_observed_at_unix_ms: d.evidence_observed_at_unix_ms,
        market_price_cents: d.market_price_cents,
        stated_target_cents: d.stated_target_cents,
        peer_count: d.peer_count,
    }
}

#[test]
fn shared_available_fixtures_execute() {
    let c = load();
    assert_eq!(c.engine_id, ENGINE_ID);
    assert_eq!(c.method_policy_version, METHOD_POLICY_VERSION);
    for f in c.fixtures.available {
        match compute_forward_earnings_multiple(&to_input(&f.input)) {
            ForwardEarningsMultipleResult::Available(a) => {
                assert_eq!(
                    a.target_value_cents, f.expected_target_value_cents,
                    "{}",
                    f.name
                );
            }
            other => panic!("{}: expected available, got {other:?}", f.name),
        }
    }
}

#[test]
fn shared_refusal_fixtures_execute() {
    let c = load();
    for f in c.fixtures.refusals {
        match compute_forward_earnings_multiple(&to_input(&f.input)) {
            ForwardEarningsMultipleResult::Unavailable { reason_code } => {
                assert_eq!(reason_code, f.expected_reason_code, "{}", f.name);
            }
            other => panic!("{}: expected refuse, got {other:?}", f.name),
        }
    }
}

#[test]
fn shared_mutation_invariants_execute() {
    let c = load();
    for f in c.fixtures.mutation_invariants {
        let a = compute_forward_earnings_multiple(&to_input(&f.base));
        let b = compute_forward_earnings_multiple(&to_input(&f.mutated));
        assert_eq!(a, b, "{}", f.name);
    }
}
