use crate::evidence_sotp::*;
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Contract {
    engine_version: String,
    model_policy_version: String,
    resolver_policy_version: String,
    point_in_time_fixtures: Vec<PitFixture>,
    routing_fixtures: Vec<RouteFixture>,
    consolidation_fixtures: Vec<ConsolidationFixture>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PitFixture {
    name: String,
    decision_at: String,
    observations: Vec<EvidenceObservation>,
    expected: PitExpected,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PitExpected {
    selected_ids: Vec<String>,
    selected_values_cents: Vec<i64>,
    rejected_codes: Vec<EvidenceRejectionCode>,
    fingerprint: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RouteFixture {
    name: String,
    sector: Option<String>,
    industry: Option<String>,
    asset_class: AssetClass,
    expected_family: ComponentFamily,
    expected_model: ComponentModel,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ConsolidationFixture {
    name: String,
    components: Vec<ConsolidationComponent>,
    corporate_overhead_enterprise_value_cents: i64,
    separately_valued_investments_cents: i64,
    net_debt_cents: i64,
    nci_cents: i64,
    preferred_claims_cents: i64,
    other_senior_claims_cents: i64,
    shares: i64,
    expected: ConsolidationExpected,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ConsolidationComponent {
    id: String,
    enterprise_value_cents: Option<i64>,
    material: bool,
    quality: EvidenceQuality,
    model: ComponentModel,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ConsolidationExpected {
    status: SotpStatus,
    covered_enterprise_value_cents: Option<i64>,
    equity_value_cents: Option<i64>,
    intrinsic_price_cents: Option<i64>,
    valuation_score_eligible: bool,
    reason_codes: Vec<String>,
}

#[test]
fn shared_point_in_time_fixtures_execute_on_windows() {
    let contract = load_contract();
    for fixture in contract.point_in_time_fixtures {
        let replay = replay_point_in_time(&fixture.observations, &fixture.decision_at)
            .unwrap_or_else(|error| panic!("{}: {error}", fixture.name));
        assert_eq!(
            replay
                .selected
                .iter()
                .map(|row| row.id.clone())
                .collect::<Vec<_>>(),
            fixture.expected.selected_ids,
            "{}",
            fixture.name
        );
        assert_eq!(
            replay
                .selected
                .iter()
                .filter_map(|row| row.value_cents)
                .collect::<Vec<_>>(),
            fixture.expected.selected_values_cents,
            "{}",
            fixture.name
        );
        assert_eq!(
            replay
                .rejected
                .iter()
                .map(|row| row.code)
                .collect::<Vec<_>>(),
            fixture.expected.rejected_codes,
            "{}",
            fixture.name
        );
        assert_eq!(
            replay.fingerprint, fixture.expected.fingerprint,
            "{}",
            fixture.name
        );
    }
}

#[test]
fn shared_routing_fixtures_execute_on_windows() {
    let contract = load_contract();
    for fixture in contract.routing_fixtures {
        let family = route_component(&ClassificationInput {
            sector: fixture.sector,
            industry: fixture.industry,
            asset_class: fixture.asset_class,
        });
        assert_eq!(family, fixture.expected_family, "{}", fixture.name);
        assert_eq!(family.model(), fixture.expected_model, "{}", fixture.name);
    }
}

#[test]
fn shared_sotp_fixtures_execute_on_windows_and_one_cent_mutation_fails() {
    let contract = load_contract();
    let engine_version = contract.engine_version.clone();
    let model_policy_version = contract.model_policy_version.clone();
    let resolver_policy_version = contract.resolver_policy_version.clone();
    for fixture in contract.consolidation_fixtures {
        let input = build_sotp(&fixture);
        let output = consolidate_sotp(&input);
        assert_eq!(output.status, fixture.expected.status, "{}", fixture.name);
        assert_eq!(
            output.covered_enterprise_value_cents, fixture.expected.covered_enterprise_value_cents,
            "{}",
            fixture.name
        );
        assert_eq!(
            output.equity_value_cents, fixture.expected.equity_value_cents,
            "{}",
            fixture.name
        );
        assert_eq!(
            output.intrinsic_price_cents, fixture.expected.intrinsic_price_cents,
            "{}",
            fixture.name
        );
        assert_eq!(
            output.valuation_score_eligible, fixture.expected.valuation_score_eligible,
            "{}",
            fixture.name
        );
        assert_eq!(
            output.reason_codes, fixture.expected.reason_codes,
            "{}",
            fixture.name
        );
        assert_eq!(output.engine_version, engine_version, "{}", fixture.name);
        assert_eq!(
            output.model_policy_version, model_policy_version,
            "{}",
            fixture.name
        );
        assert_eq!(
            output.resolver_policy_version, resolver_policy_version,
            "{}",
            fixture.name
        );
        if fixture.name == "complete_bridge_publishes_price" {
            assert_ne!(
                output.equity_value_cents,
                fixture.expected.equity_value_cents.map(|value| value + 1),
                "one-cent mutation must not pass"
            );
        }
    }
}

fn build_sotp(fixture: &ConsolidationFixture) -> SotpInput {
    let components = fixture
        .components
        .iter()
        .map(|component| SotpComponent {
            component_id: component.id.clone(),
            material: component.material,
            valuation: component
                .enterprise_value_cents
                .map(|enterprise_value_cents| ComponentValuation {
                    component_id: component.id.clone(),
                    family: ComponentFamily::OperatingNonFinancial,
                    model: component.model,
                    status: ComponentStatus::Publishable,
                    enterprise_value_cents,
                    scenarios: ScenarioValues {
                        bear_cents: enterprise_value_cents,
                        base_cents: enterprise_value_cents,
                        bull_cents: enterprise_value_cents,
                    },
                    discount_rate_bps: 800,
                    discount_rate_kind: DiscountRateKind::Wacc,
                    source_regime: SourceRegime::DomesticUsGaap,
                    evidence_refs: vec![format!("fixture:{}", component.id)],
                    quality: ComponentQuality {
                        evidence_quality: component.quality,
                        confidence: if component.quality == EvidenceQuality::Solid {
                            ConfidenceBand::Solid
                        } else {
                            ConfidenceBand::Provisional
                        },
                        uncertainty_bps: 0,
                        sensitivity_bps: 0,
                        solver_stability_bps: 0,
                    },
                    reason_codes: vec![],
                }),
            refusal: (component.enterprise_value_cents.is_none()).then(|| ValuationRefusalWire {
                code: "incomplete_segment_disclosures".into(),
                detail: "fixture unresolved component".into(),
            }),
        })
        .collect();
    SotpInput {
        issuer: "FIXTURE".into(),
        components,
        corporate_overhead: Some(CorporateOverhead {
            enterprise_value_cents: fixture.corporate_overhead_enterprise_value_cents,
            material: true,
            evidence_refs: vec!["fixture:overhead".into()],
        }),
        bridge: CapitalBridge {
            net_debt: Some(BridgeEvidence {
                amount_cents: fixture.net_debt_cents,
                evidence_refs: vec!["fixture:net_debt".into()],
            }),
            non_controlling_interest: Some(BridgeEvidence {
                amount_cents: fixture.nci_cents,
                evidence_refs: vec!["fixture:nci".into()],
            }),
            preferred_claims: Some(BridgeEvidence {
                amount_cents: fixture.preferred_claims_cents,
                evidence_refs: vec!["fixture:preferred".into()],
            }),
            other_senior_claims: Some(BridgeEvidence {
                amount_cents: fixture.other_senior_claims_cents,
                evidence_refs: vec!["fixture:senior".into()],
            }),
            separately_valued_investments: if fixture.separately_valued_investments_cents == 0 {
                vec![]
            } else {
                vec![BridgeEvidence {
                    amount_cents: fixture.separately_valued_investments_cents,
                    evidence_refs: vec!["fixture:investment".into()],
                }]
            },
        },
        shares: Some(BridgeEvidence {
            amount_cents: fixture.shares,
            evidence_refs: vec!["fixture:shares".into()],
        }),
        source_fingerprint: format!("fixture:{}", fixture.name),
    }
}

fn load_contract() -> Contract {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../shared/contracts/valuation-evidence-sotp.json");
    let raw = std::fs::read_to_string(path).expect("read valuation evidence SOTP contract");
    serde_json::from_str(&raw).expect("parse valuation evidence SOTP contract")
}
