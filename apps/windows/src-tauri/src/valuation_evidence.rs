//! EvidenceObservation V2 pure core (Foundation 0A).
//!
//! Point-in-time clocks, resolution partition keys, lineage component counts,
//! and SHA-256 canonical fingerprints. Does not reinterpret SOTP v1 FNV rows.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use unicode_normalization::UnicodeNormalization;

pub const FINGERPRINT_SCHEME: &str = "sha256_canonical_v1";
pub const SCHEMA_VERSION: u16 = 2;
pub const DOMAIN_OBSERVATION: &str = "ds.valuation.evidence_observation.v2";
pub const DOMAIN_EVIDENCE_SET: &str = "ds.valuation.evidence_set.v2";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceLane {
    ReportedActual,
    IssuerGuidance,
    ExternalConsensus,
    InternalForecast,
    AnalystStatedMethod,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MetricBasis {
    ReportedGaap,
    AdjustedNormalized,
    ProviderUnknown,
    TranscriptionClaim,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AccountingRegime {
    DomesticUsGaap,
    Ifrs,
    NotApplicable,
    Unsupported,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DatePrecision {
    ExactDate,
    MonthLabel,
    FiscalPeriod,
    ProviderHorizon,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AvailabilityBasis {
    PrimaryPublication,
    ProviderCertifiedVintage,
    FirstObservedCapture,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReplayMode {
    Operational,
    CertifiedBackfillResearch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StorageDisposition {
    MetadataOnly,
    EncryptedArtifact,
    Prohibited,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceUnitV2 {
    MoneyCents,
    RateBps,
    QuantityMillis,
    Shares,
    Text,
    Boolean,
    MultipleHundredths,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EvidenceObservationV2 {
    pub id: String,
    pub issuer_id: String,
    pub security_id: Option<String>,
    pub evidence_lane: EvidenceLane,
    pub provider_id: String,
    pub lineage_group_id: String,
    pub metric_id: String,
    pub metric_basis: MetricBasis,
    pub accounting_regime: AccountingRegime,
    pub economic_period_start: String,
    pub economic_period_end: String,
    pub date_precision: DatePrecision,
    pub publication_at_unix_ms: i64,
    pub source_available_at_unix_ms: i64,
    pub ingested_at_unix_ms: i64,
    pub availability_basis: AvailabilityBasis,
    pub provider_vintage_id: Option<String>,
    pub unit: EvidenceUnitV2,
    pub value_cents: Option<i64>,
    pub value_bps: Option<i32>,
    pub value_millis: Option<i64>,
    pub text_value: Option<String>,
    pub currency: Option<String>,
    pub definition: String,
    pub source_location: String,
    pub extraction_method: String,
    pub quality: String,
    pub retrieval_state: String,
    pub revision_id: String,
    pub supersedes: Option<String>,
    pub external_file_reference: Option<String>,
    pub storage_disposition: StorageDisposition,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolutionPartitionKey {
    pub issuer_id: String,
    pub security_id: Option<String>,
    pub evidence_lane: EvidenceLane,
    pub metric_id: String,
    pub metric_basis: MetricBasis,
    pub accounting_regime: AccountingRegime,
    pub economic_period_start: String,
    pub economic_period_end: String,
    pub unit: EvidenceUnitV2,
    pub currency: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdmissionDecision {
    pub admit: bool,
    pub live_projection_eligible: bool,
    pub refusal_code: Option<&'static str>,
}

impl EvidenceObservationV2 {
    pub fn partition_key(&self) -> ResolutionPartitionKey {
        ResolutionPartitionKey {
            issuer_id: self.issuer_id.clone(),
            security_id: self.security_id.clone(),
            evidence_lane: self.evidence_lane,
            metric_id: self.metric_id.clone(),
            metric_basis: self.metric_basis,
            accounting_regime: self.accounting_regime,
            economic_period_start: self.economic_period_start.clone(),
            economic_period_end: self.economic_period_end.clone(),
            unit: self.unit,
            currency: self.currency.clone(),
        }
    }

    pub fn validate_identity(&self) -> Result<(), &'static str> {
        if self.id.trim().is_empty() {
            return Err("empty_id");
        }
        if self.issuer_id.trim().is_empty() {
            return Err("empty_issuer_id");
        }
        if self.lineage_group_id.trim().is_empty() {
            return Err("empty_lineage_group_id");
        }
        if self.metric_id.trim().is_empty() {
            return Err("empty_metric_id");
        }
        if self.provider_id.trim().is_empty() {
            return Err("empty_provider_id");
        }
        let value_slots = [
            self.value_cents.is_some(),
            self.value_bps.is_some(),
            self.value_millis.is_some(),
            self.text_value.is_some(),
        ]
        .into_iter()
        .filter(|x| *x)
        .count();
        if value_slots != 1 {
            return Err("exactly_one_value_required");
        }
        Ok(())
    }

    /// Full admission before ledger persistence (1B-0).
    pub fn validate_for_persist(&self) -> Result<(), &'static str> {
        self.validate_identity()?;
        if self.storage_disposition == StorageDisposition::Prohibited {
            return Err("storage_prohibited");
        }
        validate_unit_value_slot(self)?;
        validate_clock_order(
            self.publication_at_unix_ms,
            self.source_available_at_unix_ms,
            self.ingested_at_unix_ms,
        )?;
        validate_quality_token(&self.quality)?;
        validate_retrieval_state_token(&self.retrieval_state)?;
        Ok(())
    }

    pub fn fingerprint_sha256(&self) -> String {
        let bytes = encode_observation_canonical(self);
        format!("sha256:{}", hex_lower(&Sha256::digest(&bytes)))
    }
}

/// Canonical evidence-set fingerprint: sorted unique observation fingerprints.
/// Membership is reconstructible from the sorted list that entered the digest.
pub fn evidence_set_fingerprint(observation_fingerprints: &[String]) -> String {
    let mut fps: Vec<&str> = observation_fingerprints
        .iter()
        .map(|s| s.as_str())
        .filter(|s| !s.trim().is_empty())
        .collect();
    fps.sort_unstable();
    fps.dedup();
    let mut out = Vec::new();
    write_str(&mut out, DOMAIN_EVIDENCE_SET);
    write_u16(&mut out, 1); // set scheme version
    write_u16(&mut out, SCHEMA_VERSION);
    write_str(&mut out, FINGERPRINT_SCHEME);
    write_u32(&mut out, fps.len() as u32);
    for fp in fps {
        write_str(&mut out, fp);
    }
    format!("sha256:{}", hex_lower(&Sha256::digest(&out)))
}

pub fn parse_replay_mode(raw: &str) -> Result<ReplayMode, &'static str> {
    match raw.trim() {
        "operational" => Ok(ReplayMode::Operational),
        "certified_backfill_research" => Ok(ReplayMode::CertifiedBackfillResearch),
        _ => Err("invalid_replay_mode"),
    }
}

pub fn replay_mode_snake(mode: ReplayMode) -> &'static str {
    match mode {
        ReplayMode::Operational => "operational",
        ReplayMode::CertifiedBackfillResearch => "certified_backfill_research",
    }
}

fn validate_unit_value_slot(obs: &EvidenceObservationV2) -> Result<(), &'static str> {
    let ok = match obs.unit {
        EvidenceUnitV2::MoneyCents => {
            obs.value_cents.is_some()
                && obs.value_bps.is_none()
                && obs.value_millis.is_none()
                && obs.text_value.is_none()
        }
        EvidenceUnitV2::RateBps => {
            obs.value_bps.is_some()
                && obs.value_cents.is_none()
                && obs.value_millis.is_none()
                && obs.text_value.is_none()
        }
        EvidenceUnitV2::QuantityMillis
        | EvidenceUnitV2::Shares
        | EvidenceUnitV2::MultipleHundredths => {
            obs.value_millis.is_some()
                && obs.value_cents.is_none()
                && obs.value_bps.is_none()
                && obs.text_value.is_none()
        }
        EvidenceUnitV2::Text | EvidenceUnitV2::Boolean => {
            obs.text_value.is_some()
                && obs.value_cents.is_none()
                && obs.value_bps.is_none()
                && obs.value_millis.is_none()
        }
    };
    if ok {
        Ok(())
    } else {
        Err("unit_value_slot_mismatch")
    }
}

fn validate_clock_order(
    publication_at_unix_ms: i64,
    source_available_at_unix_ms: i64,
    ingested_at_unix_ms: i64,
) -> Result<(), &'static str> {
    if publication_at_unix_ms < 0 || source_available_at_unix_ms < 0 || ingested_at_unix_ms < 0 {
        return Err("negative_clock");
    }
    // Knowable order: published → available → ingested (equal allowed).
    if source_available_at_unix_ms < publication_at_unix_ms {
        return Err("clock_order_source_before_publication");
    }
    if ingested_at_unix_ms < source_available_at_unix_ms {
        return Err("clock_order_ingestion_before_source");
    }
    Ok(())
}

fn validate_quality_token(quality: &str) -> Result<(), &'static str> {
    match quality.trim() {
        "solid" | "soft" | "provisional" => Ok(()),
        _ => Err("invalid_quality"),
    }
}

fn validate_retrieval_state_token(state: &str) -> Result<(), &'static str> {
    match state.trim() {
        "retrieved" | "not_retrieved" | "partial" => Ok(()),
        _ => Err("invalid_retrieval_state"),
    }
}

/// Admit observation clocks under a replay mode and decision instant.
pub fn admit_observation(
    mode: ReplayMode,
    decision_at_unix_ms: i64,
    publication_at_unix_ms: i64,
    source_available_at_unix_ms: i64,
    ingested_at_unix_ms: i64,
    availability_basis: AvailabilityBasis,
    provider_vintage_id: Option<&str>,
) -> AdmissionDecision {
    if publication_at_unix_ms > decision_at_unix_ms {
        return AdmissionDecision {
            admit: false,
            live_projection_eligible: false,
            refusal_code: Some("look_ahead_publication"),
        };
    }
    if source_available_at_unix_ms > decision_at_unix_ms {
        return AdmissionDecision {
            admit: false,
            live_projection_eligible: false,
            refusal_code: Some("look_ahead_source_available"),
        };
    }
    match mode {
        ReplayMode::Operational => {
            if ingested_at_unix_ms > decision_at_unix_ms {
                AdmissionDecision {
                    admit: false,
                    live_projection_eligible: false,
                    refusal_code: Some("look_ahead_ingestion"),
                }
            } else {
                AdmissionDecision {
                    admit: true,
                    live_projection_eligible: true,
                    refusal_code: None,
                }
            }
        }
        ReplayMode::CertifiedBackfillResearch => {
            if ingested_at_unix_ms <= decision_at_unix_ms {
                return AdmissionDecision {
                    admit: true,
                    live_projection_eligible: false,
                    refusal_code: None,
                };
            }
            let vintage_ok = availability_basis == AvailabilityBasis::ProviderCertifiedVintage
                && provider_vintage_id
                    .map(|v| !v.trim().is_empty())
                    .unwrap_or(false);
            if vintage_ok {
                AdmissionDecision {
                    admit: true,
                    live_projection_eligible: false,
                    refusal_code: None,
                }
            } else {
                AdmissionDecision {
                    admit: false,
                    live_projection_eligible: false,
                    refusal_code: Some("missing_provider_vintage"),
                }
            }
        }
    }
}

/// Count connected lineage components (same lineage_group_id = one component).
pub fn lineage_component_count(lineage_group_ids: &[String]) -> usize {
    let mut unique = lineage_group_ids
        .iter()
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>();
    unique.sort_unstable();
    unique.dedup();
    unique.len()
}

fn encode_observation_canonical(obs: &EvidenceObservationV2) -> Vec<u8> {
    let mut out = Vec::new();
    write_str(&mut out, DOMAIN_OBSERVATION);
    write_u16(&mut out, 1); // scheme version
    write_u8(&mut out, 1); // record kind observation
    write_u16(&mut out, SCHEMA_VERSION);
    write_str(&mut out, FINGERPRINT_SCHEME);
    write_str(&mut out, &obs.id);
    write_str(&mut out, &obs.issuer_id);
    write_opt_str(&mut out, obs.security_id.as_deref());
    write_str(&mut out, &enum_snake(obs.evidence_lane));
    write_str(&mut out, &obs.provider_id);
    write_str(&mut out, &obs.lineage_group_id);
    write_str(&mut out, &obs.metric_id);
    write_str(&mut out, &enum_snake(obs.metric_basis));
    write_str(&mut out, &enum_snake(obs.accounting_regime));
    write_str(&mut out, &obs.economic_period_start);
    write_str(&mut out, &obs.economic_period_end);
    write_str(&mut out, &enum_snake(obs.date_precision));
    write_i64(&mut out, obs.publication_at_unix_ms);
    write_i64(&mut out, obs.source_available_at_unix_ms);
    write_i64(&mut out, obs.ingested_at_unix_ms);
    write_str(&mut out, &enum_snake(obs.availability_basis));
    write_opt_str(&mut out, obs.provider_vintage_id.as_deref());
    write_str(&mut out, &enum_snake(obs.unit));
    write_opt_i64(&mut out, obs.value_cents);
    write_opt_i32(&mut out, obs.value_bps);
    write_opt_i64(&mut out, obs.value_millis);
    write_opt_str(&mut out, obs.text_value.as_deref());
    write_opt_str(&mut out, obs.currency.as_deref());
    write_str(&mut out, &obs.definition);
    write_str(&mut out, &obs.source_location);
    write_str(&mut out, &obs.extraction_method);
    write_str(&mut out, &obs.quality);
    write_str(&mut out, &obs.retrieval_state);
    write_str(&mut out, &obs.revision_id);
    write_opt_str(&mut out, obs.supersedes.as_deref());
    write_opt_str(&mut out, obs.external_file_reference.as_deref());
    write_str(&mut out, &enum_snake(obs.storage_disposition));
    out
}

fn write_u8(out: &mut Vec<u8>, v: u8) {
    out.push(v);
}

fn write_u16(out: &mut Vec<u8>, v: u16) {
    out.extend_from_slice(&v.to_be_bytes());
}

fn write_u32(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_be_bytes());
}

fn write_i64(out: &mut Vec<u8>, v: i64) {
    out.push(0x01);
    out.extend_from_slice(&v.to_be_bytes());
}

fn write_opt_i64(out: &mut Vec<u8>, v: Option<i64>) {
    match v {
        None => out.push(0x00),
        Some(x) => write_i64(out, x),
    }
}

fn write_opt_i32(out: &mut Vec<u8>, v: Option<i32>) {
    match v {
        None => out.push(0x00),
        Some(x) => {
            out.push(0x01);
            out.extend_from_slice(&x.to_be_bytes());
        }
    }
}

fn write_str(out: &mut Vec<u8>, s: &str) {
    let nfc: String = s.nfc().collect();
    let bytes = nfc.as_bytes();
    out.push(0x01);
    write_u32(out, bytes.len() as u32);
    out.extend_from_slice(bytes);
}

fn write_opt_str(out: &mut Vec<u8>, s: Option<&str>) {
    match s {
        None => out.push(0x00),
        Some(v) => write_str(out, v),
    }
}

fn hex_lower(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn enum_snake<T: Serialize>(value: T) -> String {
    serde_json::to_value(value)
        .ok()
        .and_then(|v| v.as_str().map(|s| s.to_string()))
        .unwrap_or_else(|| "unknown".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_obs() -> EvidenceObservationV2 {
        EvidenceObservationV2 {
            id: "obs:fixture:1".into(),
            issuer_id: "issuer:0001018724".into(),
            security_id: Some("sec:amzn-us".into()),
            evidence_lane: EvidenceLane::AnalystStatedMethod,
            provider_id: "manual_import".into(),
            lineage_group_id: "lineage:jpm-amzn-2026-07-31".into(),
            metric_id: "diluted_eps".into(),
            // Unverified transcription claims GAAP-shaped metrics without asserting GAAP truth.
            metric_basis: MetricBasis::TranscriptionClaim,
            accounting_regime: AccountingRegime::DomesticUsGaap,
            economic_period_start: "2028-01-01".into(),
            economic_period_end: "2028-12-31".into(),
            date_precision: DatePrecision::FiscalPeriod,
            publication_at_unix_ms: 1_753_920_000_000,
            source_available_at_unix_ms: 1_753_920_000_000,
            ingested_at_unix_ms: 1_753_920_000_000,
            availability_basis: AvailabilityBasis::PrimaryPublication,
            provider_vintage_id: None,
            unit: EvidenceUnitV2::MoneyCents,
            value_cents: Some(1300),
            value_bps: None,
            value_millis: None,
            text_value: None,
            currency: Some("USD".into()),
            definition: "FY2028E GAAP diluted EPS claim (unverified transcription)".into(),
            source_location: "manual:transcription".into(),
            extraction_method: "manual_entry".into(),
            quality: "provisional".into(),
            retrieval_state: "retrieved".into(),
            revision_id: "r1".into(),
            supersedes: None,
            external_file_reference: None,
            storage_disposition: StorageDisposition::MetadataOnly,
        }
    }

    #[test]
    fn partition_key_differs_by_metric_basis() {
        let a = sample_obs();
        let mut b = sample_obs();
        b.metric_basis = MetricBasis::AdjustedNormalized;
        assert_ne!(a.partition_key(), b.partition_key());
    }

    #[test]
    fn partition_key_same_when_only_provider_differs() {
        let a = sample_obs();
        let mut b = sample_obs();
        b.provider_id = "other_provider".into();
        // provider is not in partition key
        assert_eq!(a.partition_key(), b.partition_key());
    }

    #[test]
    fn operational_ingested_after_decision_refuses() {
        let d = admit_observation(
            ReplayMode::Operational,
            1_743_465_600_000,
            1_743_465_600_000,
            1_743_465_600_000,
            1_743_552_000_000,
            AvailabilityBasis::PrimaryPublication,
            None,
        );
        assert!(!d.admit);
        assert_eq!(d.refusal_code, Some("look_ahead_ingestion"));
    }

    #[test]
    fn certified_backfill_with_vintage_admits_not_live() {
        let d = admit_observation(
            ReplayMode::CertifiedBackfillResearch,
            1_743_465_600_000,
            1_743_465_600_000,
            1_743_465_600_000,
            1_743_552_000_000,
            AvailabilityBasis::ProviderCertifiedVintage,
            Some("vendor:vintage:2025-04-01"),
        );
        assert!(d.admit);
        assert!(!d.live_projection_eligible);
    }

    #[test]
    fn certified_without_vintage_refuses() {
        let d = admit_observation(
            ReplayMode::CertifiedBackfillResearch,
            1_743_465_600_000,
            1_743_465_600_000,
            1_743_465_600_000,
            1_743_552_000_000,
            AvailabilityBasis::FirstObservedCapture,
            None,
        );
        assert!(!d.admit);
        assert_eq!(d.refusal_code, Some("missing_provider_vintage"));
    }

    #[test]
    fn lineage_component_count_counts_unique_groups() {
        assert_eq!(
            lineage_component_count(&[
                "lineage:jpm-amzn-2026-07-31".into(),
                "lineage:jpm-amzn-2026-07-31".into()
            ]),
            1
        );
        assert_eq!(
            lineage_component_count(&["lineage:a".into(), "lineage:b".into()]),
            2
        );
    }

    #[test]
    fn fingerprint_stable_and_mutation_sensitive() {
        let a = sample_obs();
        let fp1 = a.fingerprint_sha256();
        assert_eq!(
            fp1,
            "sha256:18ad8a23cbc8e036a39fecee1d2ef42171ef14257325a44668064e3eddd0f8b1"
        );
        assert!(fp1.starts_with("sha256:"));
        assert_eq!(fp1.len(), 7 + 64);
        let mut b = sample_obs();
        b.value_cents = Some(1301);
        let fp2 = b.fingerprint_sha256();
        assert_ne!(fp1, fp2);
        // NFC stability: decomposed vs composed 'é' in definition
        let mut c = sample_obs();
        c.definition = "cafe\u{0301}".into(); // e + combining acute
        let mut d = sample_obs();
        d.definition = "caf\u{00e9}".into(); // precomposed
        assert_eq!(c.fingerprint_sha256(), d.fingerprint_sha256());
    }

    #[test]
    fn null_security_differs_from_empty_security() {
        let mut a = sample_obs();
        a.security_id = None;
        let mut b = sample_obs();
        b.security_id = Some(String::new());
        assert_ne!(a.fingerprint_sha256(), b.fingerprint_sha256());
    }

    #[test]
    fn validate_rejects_blank_lineage() {
        let mut a = sample_obs();
        a.lineage_group_id = "  ".into();
        assert_eq!(a.validate_identity(), Err("empty_lineage_group_id"));
    }

    #[test]
    fn baseline_transcription_claim_is_persistable() {
        assert_eq!(sample_obs().validate_for_persist(), Ok(()));
        assert_eq!(sample_obs().metric_basis, MetricBasis::TranscriptionClaim);
    }

    #[test]
    fn reported_gaap_partition_differs_from_transcription_claim() {
        let claim = sample_obs();
        let mut gaap = sample_obs();
        gaap.metric_basis = MetricBasis::ReportedGaap;
        assert_ne!(claim.partition_key(), gaap.partition_key());
        assert_ne!(claim.fingerprint_sha256(), gaap.fingerprint_sha256());
    }

    #[test]
    fn unit_value_slot_mismatch_refuses_persist() {
        let mut a = sample_obs();
        a.unit = EvidenceUnitV2::RateBps;
        // still has value_cents, not bps
        assert_eq!(a.validate_for_persist(), Err("unit_value_slot_mismatch"));
    }

    #[test]
    fn clock_order_ingestion_before_source_refuses() {
        let mut a = sample_obs();
        a.source_available_at_unix_ms = 200;
        a.ingested_at_unix_ms = 100;
        a.publication_at_unix_ms = 100;
        assert_eq!(
            a.validate_for_persist(),
            Err("clock_order_ingestion_before_source")
        );
    }

    #[test]
    fn storage_prohibited_refuses_persist() {
        let mut a = sample_obs();
        a.storage_disposition = StorageDisposition::Prohibited;
        assert_eq!(a.validate_for_persist(), Err("storage_prohibited"));
    }

    #[test]
    fn invalid_quality_refuses_persist() {
        let mut a = sample_obs();
        a.quality = "legendary".into();
        assert_eq!(a.validate_for_persist(), Err("invalid_quality"));
    }

    #[test]
    fn evidence_set_fingerprint_order_independent_and_membership_sensitive() {
        let a = "sha256:aaa".to_string();
        let b = "sha256:bbb".to_string();
        let ab = evidence_set_fingerprint(&[a.clone(), b.clone()]);
        let ba = evidence_set_fingerprint(&[b.clone(), a.clone()]);
        assert_eq!(ab, ba);
        // Dual-lock pin (shared contract fixtures.evidenceSet).
        assert_eq!(
            ab,
            "sha256:0e2e803826b99c6b8ea7ab08302fc1ddb6705b70ec2b0c6d008289ad388872de"
        );
        let only_a = evidence_set_fingerprint(&[a]);
        assert_ne!(ab, only_a);
        assert!(ab.starts_with("sha256:"));
        assert_eq!(ab.len(), 7 + 64);
    }

    #[test]
    fn parse_replay_mode_rejects_free_text() {
        assert_eq!(
            parse_replay_mode("operational"),
            Ok(ReplayMode::Operational)
        );
        assert_eq!(
            parse_replay_mode("certified_backfill_research"),
            Ok(ReplayMode::CertifiedBackfillResearch)
        );
        assert_eq!(parse_replay_mode("whatever"), Err("invalid_replay_mode"));
    }
}
