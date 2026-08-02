//! Shared-contract harness for EvidenceObservation V2 (Foundation 0A).
//! Loads `shared/contracts/valuation-evidence-observation-v2.json`.

use crate::valuation_evidence::*;
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Contract {
    schema_version: u16,
    fingerprint_scheme: String,
    fixtures: Fixtures,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Fixtures {
    partition: Vec<PartitionFixture>,
    replay_admission: Vec<ReplayFixture>,
    lineage: Vec<LineageFixture>,
    canonical: Vec<CanonicalFixture>,
    #[serde(default)]
    evidence_set: Vec<EvidenceSetFixture>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EvidenceSetFixture {
    name: String,
    fingerprints_a: Vec<String>,
    fingerprints_b: Vec<String>,
    expect_same: bool,
    #[serde(default)]
    expected_sha256: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartitionFixture {
    name: String,
    a: PartitionFields,
    b: PartitionFields,
    expect_same_partition: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartitionFields {
    issuer_id: String,
    security_id: Option<String>,
    evidence_lane: String,
    metric_id: String,
    metric_basis: String,
    accounting_regime: String,
    economic_period_start: String,
    economic_period_end: String,
    unit: String,
    currency: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReplayFixture {
    name: String,
    replay_mode: String,
    decision_at_unix_ms: i64,
    publication_at_unix_ms: i64,
    source_available_at_unix_ms: i64,
    ingested_at_unix_ms: i64,
    availability_basis: String,
    provider_vintage_id: Option<String>,
    expect_admit: bool,
    #[serde(default)]
    live_projection_eligible: Option<bool>,
    #[serde(default)]
    refusal_code: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LineageFixture {
    name: String,
    lineage_group_ids: Vec<String>,
    expected_component_count: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CanonicalFixture {
    name: String,
    #[serde(default)]
    observation: Option<ObsDto>,
    #[serde(default)]
    observation_null: Option<ObsDto>,
    #[serde(default)]
    observation_empty: Option<ObsDto>,
    #[serde(default)]
    expected_sha256: Option<String>,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct ObsDto {
    id: String,
    issuer_id: String,
    security_id: Option<String>,
    evidence_lane: String,
    provider_id: String,
    lineage_group_id: String,
    metric_id: String,
    metric_basis: String,
    accounting_regime: String,
    economic_period_start: String,
    economic_period_end: String,
    date_precision: String,
    publication_at_unix_ms: i64,
    source_available_at_unix_ms: i64,
    ingested_at_unix_ms: i64,
    availability_basis: String,
    provider_vintage_id: Option<String>,
    unit: String,
    value_cents: Option<i64>,
    value_bps: Option<i32>,
    value_millis: Option<i64>,
    text_value: Option<String>,
    currency: Option<String>,
    definition: String,
    source_location: String,
    extraction_method: String,
    quality: String,
    retrieval_state: String,
    revision_id: String,
    supersedes: Option<String>,
    external_file_reference: Option<String>,
    storage_disposition: String,
}

fn load_contract() -> Contract {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../shared/contracts/valuation-evidence-observation-v2.json");
    let raw =
        std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
    serde_json::from_str(&raw).unwrap_or_else(|e| panic!("parse contract: {e}"))
}

fn parse_lane(s: &str) -> EvidenceLane {
    match s {
        "reported_actual" => EvidenceLane::ReportedActual,
        "issuer_guidance" => EvidenceLane::IssuerGuidance,
        "external_consensus" => EvidenceLane::ExternalConsensus,
        "internal_forecast" => EvidenceLane::InternalForecast,
        "analyst_stated_method" => EvidenceLane::AnalystStatedMethod,
        other => panic!("unknown evidenceLane {other}"),
    }
}

fn parse_basis(s: &str) -> MetricBasis {
    match s {
        "reported_gaap" => MetricBasis::ReportedGaap,
        "adjusted_normalized" => MetricBasis::AdjustedNormalized,
        "provider_unknown" => MetricBasis::ProviderUnknown,
        "transcription_claim" => MetricBasis::TranscriptionClaim,
        other => panic!("unknown metricBasis {other}"),
    }
}

fn parse_regime(s: &str) -> AccountingRegime {
    match s {
        "domestic_us_gaap" => AccountingRegime::DomesticUsGaap,
        "ifrs" => AccountingRegime::Ifrs,
        "not_applicable" => AccountingRegime::NotApplicable,
        "unsupported" => AccountingRegime::Unsupported,
        other => panic!("unknown accountingRegime {other}"),
    }
}

fn parse_precision(s: &str) -> DatePrecision {
    match s {
        "exact_date" => DatePrecision::ExactDate,
        "month_label" => DatePrecision::MonthLabel,
        "fiscal_period" => DatePrecision::FiscalPeriod,
        "provider_horizon" => DatePrecision::ProviderHorizon,
        other => panic!("unknown datePrecision {other}"),
    }
}

fn parse_availability(s: &str) -> AvailabilityBasis {
    match s {
        "primary_publication" => AvailabilityBasis::PrimaryPublication,
        "provider_certified_vintage" => AvailabilityBasis::ProviderCertifiedVintage,
        "first_observed_capture" => AvailabilityBasis::FirstObservedCapture,
        other => panic!("unknown availabilityBasis {other}"),
    }
}

fn parse_unit(s: &str) -> EvidenceUnitV2 {
    match s {
        "money_cents" => EvidenceUnitV2::MoneyCents,
        "rate_bps" => EvidenceUnitV2::RateBps,
        "quantity_millis" => EvidenceUnitV2::QuantityMillis,
        "shares" => EvidenceUnitV2::Shares,
        "text" => EvidenceUnitV2::Text,
        "boolean" => EvidenceUnitV2::Boolean,
        "multiple_hundredths" => EvidenceUnitV2::MultipleHundredths,
        other => panic!("unknown unit {other}"),
    }
}

fn parse_storage(s: &str) -> StorageDisposition {
    match s {
        "metadata_only" => StorageDisposition::MetadataOnly,
        "encrypted_artifact" => StorageDisposition::EncryptedArtifact,
        "prohibited" => StorageDisposition::Prohibited,
        other => panic!("unknown storageDisposition {other}"),
    }
}

fn parse_replay(s: &str) -> ReplayMode {
    match s {
        "operational" => ReplayMode::Operational,
        "certified_backfill_research" => ReplayMode::CertifiedBackfillResearch,
        other => panic!("unknown replayMode {other}"),
    }
}

fn to_obs(dto: &ObsDto) -> EvidenceObservationV2 {
    EvidenceObservationV2 {
        id: dto.id.clone(),
        issuer_id: dto.issuer_id.clone(),
        security_id: dto.security_id.clone(),
        evidence_lane: parse_lane(&dto.evidence_lane),
        provider_id: dto.provider_id.clone(),
        lineage_group_id: dto.lineage_group_id.clone(),
        metric_id: dto.metric_id.clone(),
        metric_basis: parse_basis(&dto.metric_basis),
        accounting_regime: parse_regime(&dto.accounting_regime),
        economic_period_start: dto.economic_period_start.clone(),
        economic_period_end: dto.economic_period_end.clone(),
        date_precision: parse_precision(&dto.date_precision),
        publication_at_unix_ms: dto.publication_at_unix_ms,
        source_available_at_unix_ms: dto.source_available_at_unix_ms,
        ingested_at_unix_ms: dto.ingested_at_unix_ms,
        availability_basis: parse_availability(&dto.availability_basis),
        provider_vintage_id: dto.provider_vintage_id.clone(),
        unit: parse_unit(&dto.unit),
        value_cents: dto.value_cents,
        value_bps: dto.value_bps,
        value_millis: dto.value_millis,
        text_value: dto.text_value.clone(),
        currency: dto.currency.clone(),
        definition: dto.definition.clone(),
        source_location: dto.source_location.clone(),
        extraction_method: dto.extraction_method.clone(),
        quality: dto.quality.clone(),
        retrieval_state: dto.retrieval_state.clone(),
        revision_id: dto.revision_id.clone(),
        supersedes: dto.supersedes.clone(),
        external_file_reference: dto.external_file_reference.clone(),
        storage_disposition: parse_storage(&dto.storage_disposition),
    }
}

fn partition_from(fields: &PartitionFields) -> ResolutionPartitionKey {
    ResolutionPartitionKey {
        issuer_id: fields.issuer_id.clone(),
        security_id: fields.security_id.clone(),
        evidence_lane: parse_lane(&fields.evidence_lane),
        metric_id: fields.metric_id.clone(),
        metric_basis: parse_basis(&fields.metric_basis),
        accounting_regime: parse_regime(&fields.accounting_regime),
        economic_period_start: fields.economic_period_start.clone(),
        economic_period_end: fields.economic_period_end.clone(),
        unit: parse_unit(&fields.unit),
        currency: fields.currency.clone(),
    }
}

#[test]
fn contract_schema_and_scheme_match_module() {
    let c = load_contract();
    assert_eq!(c.schema_version, SCHEMA_VERSION);
    assert_eq!(c.fingerprint_scheme, FINGERPRINT_SCHEME);
}

#[test]
fn shared_partition_fixtures_execute() {
    let c = load_contract();
    for f in c.fixtures.partition {
        let a = partition_from(&f.a);
        let b = partition_from(&f.b);
        if f.expect_same_partition {
            assert_eq!(a, b, "{}", f.name);
        } else {
            assert_ne!(a, b, "{}", f.name);
        }
    }
}

#[test]
fn shared_replay_admission_fixtures_execute() {
    let c = load_contract();
    for f in c.fixtures.replay_admission {
        let d = admit_observation(
            parse_replay(&f.replay_mode),
            f.decision_at_unix_ms,
            f.publication_at_unix_ms,
            f.source_available_at_unix_ms,
            f.ingested_at_unix_ms,
            parse_availability(&f.availability_basis),
            f.provider_vintage_id.as_deref(),
        );
        assert_eq!(d.admit, f.expect_admit, "{}", f.name);
        if let Some(live) = f.live_projection_eligible {
            assert_eq!(d.live_projection_eligible, live, "{}", f.name);
        }
        if let Some(code) = f.refusal_code.as_deref() {
            assert_eq!(d.refusal_code, Some(code), "{}", f.name);
        }
    }
}

#[test]
fn shared_lineage_fixtures_execute() {
    let c = load_contract();
    for f in c.fixtures.lineage {
        assert_eq!(
            lineage_component_count(&f.lineage_group_ids),
            f.expected_component_count,
            "{}",
            f.name
        );
    }
}

#[test]
fn shared_canonical_fingerprint_fixtures_execute() {
    let c = load_contract();
    for f in c.fixtures.canonical {
        match f.name.as_str() {
            "baseline_observation" => {
                let obs = to_obs(f.observation.as_ref().expect("observation"));
                let expected = f.expected_sha256.expect("expectedSha256");
                assert_eq!(obs.fingerprint_sha256(), expected, "{}", f.name);
            }
            "null_security_vs_empty_security_differ" => {
                let null_obs = to_obs(f.observation_null.as_ref().expect("observationNull"));
                let empty_obs = to_obs(f.observation_empty.as_ref().expect("observationEmpty"));
                assert_ne!(
                    null_obs.fingerprint_sha256(),
                    empty_obs.fingerprint_sha256(),
                    "{}",
                    f.name
                );
            }
            other => panic!("unhandled canonical fixture: {other}"),
        }
    }
}

#[test]
fn shared_evidence_set_fingerprint_fixtures_execute() {
    let c = load_contract();
    assert!(
        !c.fixtures.evidence_set.is_empty(),
        "evidenceSet fixtures required for dual-lock"
    );
    for f in c.fixtures.evidence_set {
        let a = evidence_set_fingerprint(&f.fingerprints_a);
        let b = evidence_set_fingerprint(&f.fingerprints_b);
        if f.expect_same {
            assert_eq!(a, b, "{}", f.name);
        } else {
            assert_ne!(a, b, "{}", f.name);
        }
        if let Some(expected) = f.expected_sha256.as_deref() {
            assert_eq!(a, expected, "{} expectedSha256", f.name);
            assert_eq!(b, expected, "{} expectedSha256 on B", f.name);
        }
    }
}
