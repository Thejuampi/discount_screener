//! Slice 1B / 1B.1: typed JSON import → EvidenceObservationV2 + FEM derived from observations.
//!
//! Does not touch SQLite. Application service owns the single atomic lifecycle transaction.

use crate::forward_earnings_multiple::{
    ForwardEarningsMultipleInput, MultipleProvenance, ENGINE_ID, METHOD_POLICY_VERSION,
};
use crate::valuation_evidence::{
    admit_observation, AccountingRegime, AvailabilityBasis, DatePrecision, EvidenceLane,
    EvidenceObservationV2, EvidenceUnitV2, MetricBasis, ReplayMode, StorageDisposition,
};
use serde::{Deserialize, Serialize};

pub const IMPORT_SCHEMA_VERSION: u16 = 1;
pub const METHOD_FORWARD_EARNINGS_MULTIPLE: &str = "forward_earnings_multiple";
pub const ROLE_FORWARD_EPS: &str = "forward_eps";
pub const ROLE_FORWARD_PE: &str = "forward_pe";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ImportQualityLabel {
    FixtureTranscription,
    ManualTranscriptionUnverified,
}

impl ImportQualityLabel {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::FixtureTranscription => "fixture_transcription",
            Self::ManualTranscriptionUnverified => "manual_transcription_unverified",
        }
    }

    pub fn parse(raw: &str) -> Result<Self, String> {
        match raw.trim() {
            "fixture_transcription" => Ok(Self::FixtureTranscription),
            "manual_transcription_unverified" => Ok(Self::ManualTranscriptionUnverified),
            other => Err(format!("invalid_quality_label:{other}")),
        }
    }

    pub fn requires_transcription_claim(self) -> bool {
        matches!(
            self,
            Self::FixtureTranscription | Self::ManualTranscriptionUnverified
        )
    }
}

/// FEM method metadata only — numeric EPS/multiple are **derived** from observations (1B.1).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FemImportSection {
    pub eps_observation_id: String,
    pub eps_share_basis_id: String,
    pub multiple_observation_id: String,
    pub multiple_provenance: String,
    pub forecast_period_end: String,
    pub target_as_of: String,
    pub date_precision: String,
    #[serde(default)]
    pub market_price_cents: Option<i64>,
    #[serde(default)]
    pub stated_target_cents: Option<i64>,
    #[serde(default)]
    pub peer_count: Option<u32>,
}

/// Minimal trusted control envelope (1B.3). Extra JSON fields are ignored here.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ControlEnvelope {
    pub schema_version: u16,
    pub issuer_id: String,
    pub security_id: String,
    pub run_id: String,
    #[serde(default)]
    pub projection_key: Option<String>,
    #[serde(default)]
    pub supersedes_run_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalystMethodImportDocument {
    pub schema_version: u16,
    pub quality_label: String,
    pub issuer_id: String,
    pub security_id: String,
    pub run_id: String,
    pub decision_at_unix_ms: i64,
    #[serde(default)]
    pub projection_key: Option<String>,
    pub replay_mode: String,
    #[serde(default)]
    pub supersedes_run_id: Option<String>,
    pub fem: FemImportSection,
    pub observations: Vec<ObsImportDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ObsImportDto {
    pub id: String,
    pub issuer_id: String,
    pub security_id: Option<String>,
    pub evidence_lane: String,
    pub provider_id: String,
    pub lineage_group_id: String,
    pub metric_id: String,
    pub metric_basis: String,
    pub accounting_regime: String,
    pub economic_period_start: String,
    pub economic_period_end: String,
    pub date_precision: String,
    pub publication_at_unix_ms: i64,
    pub source_available_at_unix_ms: i64,
    pub ingested_at_unix_ms: i64,
    pub availability_basis: String,
    pub provider_vintage_id: Option<String>,
    pub unit: String,
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
    pub storage_disposition: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedAnalystMethodImport {
    pub quality_label: ImportQualityLabel,
    pub issuer_id: String,
    pub security_id: String,
    pub run_id: String,
    pub decision_at_unix_ms: i64,
    pub canonical_command_sha256: String,
    pub projection_key: Option<String>,
    pub replay_mode: ReplayMode,
    pub supersedes_run_id: Option<String>,
    pub fem_input: ForwardEarningsMultipleInput,
    pub eps_observation_id: String,
    pub eps_share_basis_id: String,
    pub multiple_observation_id: String,
    pub observations: Vec<EvidenceObservationV2>,
}

/// Parse only the control envelope (issuer/security/run/projection/supersession).
/// Semantic observation/FEM failures must not prevent this step (1B.3).
pub fn parse_control_envelope(raw: &str) -> Result<ControlEnvelope, String> {
    let env: ControlEnvelope =
        serde_json::from_str(raw).map_err(|e| format!("control_envelope_parse:{e}"))?;
    if env.schema_version != IMPORT_SCHEMA_VERSION {
        return Err(format!("unsupported_import_schema:{}", env.schema_version));
    }
    if env.issuer_id.trim().is_empty() {
        return Err("empty_issuer_id".into());
    }
    if env.security_id.trim().is_empty() {
        return Err("empty_security_id".into());
    }
    if env.run_id.trim().is_empty() {
        return Err("empty_run_id".into());
    }
    Ok(env)
}

/// Full semantic parse including observations and economic-role admission (no I/O).
pub fn parse_analyst_method_import_json(raw: &str) -> Result<ParsedAnalystMethodImport, String> {
    let doc: AnalystMethodImportDocument =
        serde_json::from_str(raw).map_err(|e| format!("import_json_parse:{e}"))?;
    admit_import_document(doc)
}

pub fn admit_import_document(
    doc: AnalystMethodImportDocument,
) -> Result<ParsedAnalystMethodImport, String> {
    if doc.schema_version != IMPORT_SCHEMA_VERSION {
        return Err(format!("unsupported_import_schema:{}", doc.schema_version));
    }
    let quality_label = ImportQualityLabel::parse(&doc.quality_label)?;
    if doc.issuer_id.trim().is_empty() {
        return Err("empty_issuer_id".into());
    }
    if doc.security_id.trim().is_empty() {
        return Err("empty_security_id".into());
    }
    if doc.run_id.trim().is_empty() {
        return Err("empty_run_id".into());
    }
    if doc.decision_at_unix_ms <= 0 {
        return Err("invalid_decision_at_unix_ms".into());
    }
    if doc.observations.is_empty() {
        return Err("empty_observations".into());
    }
    let replay_mode = crate::valuation_evidence::parse_replay_mode(&doc.replay_mode)
        .map_err(|e| e.to_string())?;

    let mut observations = Vec::with_capacity(doc.observations.len());
    for dto in &doc.observations {
        let obs = dto_to_observation(dto)?;
        if let Err(code) = obs.validate_for_persist() {
            return Err(format!("observation_invalid:{code}"));
        }
        if obs.issuer_id != doc.issuer_id {
            return Err("observation_issuer_mismatch".into());
        }
        match obs.security_id.as_deref() {
            Some(sid) if sid == doc.security_id => {}
            Some(_) => return Err("observation_security_mismatch".into()),
            None => return Err("observation_missing_security_id".into()),
        }
        if quality_label.requires_transcription_claim()
            && obs.metric_basis != MetricBasis::TranscriptionClaim
        {
            return Err("unverified_requires_transcription_claim".into());
        }
        observations.push(obs);
    }

    let fem_input = derive_fem_input(&observations, &doc.fem, &doc.issuer_id, &doc.security_id)?;
    let canonical_command_sha256 = canonical_import_document_sha256(&doc)?;

    Ok(ParsedAnalystMethodImport {
        quality_label,
        issuer_id: doc.issuer_id,
        security_id: doc.security_id,
        run_id: doc.run_id,
        decision_at_unix_ms: doc.decision_at_unix_ms,
        canonical_command_sha256,
        projection_key: doc.projection_key,
        replay_mode,
        supersedes_run_id: doc.supersedes_run_id,
        eps_observation_id: doc.fem.eps_observation_id,
        eps_share_basis_id: doc.fem.eps_share_basis_id,
        multiple_observation_id: doc.fem.multiple_observation_id,
        fem_input,
        observations,
    })
}

/// Digest every accepted typed import field while normalizing irrelevant JSON formatting and
/// object-key order. Unknown wire fields are intentionally outside the v1 typed command.
pub fn canonical_command_sha256(raw: &str) -> Result<String, String> {
    let doc: AnalystMethodImportDocument =
        serde_json::from_str(raw).map_err(|e| format!("canonical_command_parse:{e}"))?;
    canonical_import_document_sha256(&doc)
}

fn canonical_import_document_sha256(doc: &AnalystMethodImportDocument) -> Result<String, String> {
    use sha2::{Digest, Sha256};
    let canonical =
        serde_json::to_vec(doc).map_err(|e| format!("canonical_command_serialize:{e}"))?;
    Ok(format!(
        "sha256:{}",
        Sha256::digest(canonical)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>()
    ))
}

/// Canonical projection key scoped to issuer/security/method (1B.2).
pub fn canonical_projection_key(issuer_id: &str, security_id: &str, method: &str) -> String {
    format!("proj:{issuer_id}:{security_id}:{method}")
}

const EPS_METRIC_IDS: &[&str] = &["gaap_diluted_eps", "diluted_eps", "normalized_diluted_eps"];
const FORWARD_PE_METRIC_IDS: &[&str] = &["forward_pe", "pe_forward", "forward_pe_multiple"];

/// Bind FEM arithmetic to frozen observation IDs with economic-role checks (1B.1 / 1B.2).
pub fn derive_fem_input(
    observations: &[EvidenceObservationV2],
    fem: &FemImportSection,
    issuer_id: &str,
    security_id: &str,
) -> Result<ForwardEarningsMultipleInput, String> {
    if fem.eps_observation_id.trim().is_empty() {
        return Err("missing_eps_observation_id".into());
    }
    if fem.eps_share_basis_id.trim().is_empty() {
        return Err("missing_eps_share_basis_id".into());
    }
    if fem.multiple_observation_id.trim().is_empty() {
        return Err("missing_multiple_observation_id".into());
    }
    if fem.eps_observation_id == fem.multiple_observation_id {
        return Err("eps_and_multiple_observation_must_differ".into());
    }
    let eps = observations
        .iter()
        .find(|o| o.id == fem.eps_observation_id)
        .ok_or_else(|| format!("eps_observation_not_in_set:{}", fem.eps_observation_id))?;
    let mult = observations
        .iter()
        .find(|o| o.id == fem.multiple_observation_id)
        .ok_or_else(|| {
            format!(
                "multiple_observation_not_in_set:{}",
                fem.multiple_observation_id
            )
        })?;

    if eps.unit != EvidenceUnitV2::MoneyCents {
        return Err("eps_observation_unit_mismatch".into());
    }
    let eps_cents = eps
        .value_cents
        .ok_or("eps_observation_missing_value_cents")?;
    if mult.unit != EvidenceUnitV2::MultipleHundredths {
        return Err("multiple_observation_unit_mismatch".into());
    }
    let multiple_millis = mult
        .value_millis
        .ok_or("multiple_observation_missing_value_millis")?;
    if multiple_millis < i32::MIN as i64 || multiple_millis > i32::MAX as i64 {
        return Err("multiple_observation_overflow".into());
    }
    let multiple_hundredths = multiple_millis as i32;

    let currency = eps
        .currency
        .clone()
        .filter(|c| !c.trim().is_empty())
        .ok_or("eps_observation_missing_currency")?;
    if let Some(mc) = mult.currency.as_deref() {
        if !mc.trim().is_empty() && mc != currency {
            return Err("currency_mismatch_between_eps_and_multiple".into());
        }
    }

    let provenance = match fem.multiple_provenance.as_str() {
        "analyst_stated" => MultipleProvenance::AnalystStated,
        "peer_policy_derived" => MultipleProvenance::PeerPolicyDerived,
        other => return Err(format!("invalid_multiple_provenance:{other}")),
    };

    // Horizon coordinates have contract-defined refusal precedence across platforms.
    validate_horizon_fields(fem)?;
    // 1B.2 economic roles — not every money_cents row is EPS; not every multiple is forward P/E.
    validate_eps_economic_role(eps, fem, provenance)?;
    validate_multiple_economic_role(mult, eps, fem, provenance)?;

    let metric_basis = enum_snake(eps.metric_basis);
    let evidence_observed_at = eps
        .source_available_at_unix_ms
        .max(mult.source_available_at_unix_ms);

    Ok(ForwardEarningsMultipleInput {
        issuer_id: issuer_id.into(),
        security_id: Some(security_id.into()),
        metric_id: eps.metric_id.clone(),
        metric_basis,
        eps_cents,
        multiple_hundredths,
        multiple_provenance: provenance,
        forecast_period_end: fem.forecast_period_end.clone(),
        target_as_of: fem.target_as_of.clone(),
        date_precision: fem.date_precision.clone(),
        currency,
        evidence_observed_at_unix_ms: evidence_observed_at,
        market_price_cents: fem.market_price_cents,
        stated_target_cents: fem.stated_target_cents,
        peer_count: fem.peer_count,
    })
}

fn validate_eps_economic_role(
    eps: &EvidenceObservationV2,
    fem: &FemImportSection,
    provenance: MultipleProvenance,
) -> Result<(), String> {
    if matches!(provenance, MultipleProvenance::AnalystStated) {
        if !EPS_METRIC_IDS.iter().any(|m| *m == eps.metric_id.as_str()) {
            return Err(format!("eps_metric_not_earnings:{}", eps.metric_id));
        }
        if eps.evidence_lane != EvidenceLane::AnalystStatedMethod {
            return Err("eps_lane_not_analyst_stated_method".into());
        }
    }
    let period_start = parse_iso_date(&eps.economic_period_start, "eps_period_start")?;
    let period_end = parse_iso_date(&eps.economic_period_end, "eps_period_end")?;
    if period_start > period_end {
        return Err("economic_period_start_after_end".into());
    }
    if eps.economic_period_end != fem.forecast_period_end {
        return Err("eps_period_mismatch_forecast".into());
    }
    Ok(())
}

fn validate_multiple_economic_role(
    mult: &EvidenceObservationV2,
    eps: &EvidenceObservationV2,
    _fem: &FemImportSection,
    provenance: MultipleProvenance,
) -> Result<(), String> {
    if matches!(provenance, MultipleProvenance::AnalystStated) {
        if !FORWARD_PE_METRIC_IDS
            .iter()
            .any(|m| *m == mult.metric_id.as_str())
        {
            return Err(format!("multiple_metric_not_forward_pe:{}", mult.metric_id));
        }
        if mult.evidence_lane != EvidenceLane::AnalystStatedMethod {
            return Err("multiple_lane_not_analyst_stated_method".into());
        }
        if mult.lineage_group_id != eps.lineage_group_id {
            return Err("lineage_mismatch_eps_multiple".into());
        }
        if mult.metric_basis != eps.metric_basis {
            return Err("metric_basis_mismatch_eps_multiple".into());
        }
    }
    // Period: multiple should share the EPS economic window for analyst_stated.
    if mult.economic_period_end != eps.economic_period_end
        || mult.economic_period_start != eps.economic_period_start
    {
        return Err("period_mismatch_eps_multiple".into());
    }
    Ok(())
}

fn validate_horizon_fields(fem: &FemImportSection) -> Result<(), String> {
    let forecast_period_end = parse_iso_date(&fem.forecast_period_end, "forecast_period_end")?;
    match fem.date_precision.as_str() {
        "month_label" => {
            let (year, month) = parse_yyyy_mm(&fem.target_as_of)
                .ok_or_else(|| "invalid_target_as_of_month_label".to_string())?;
            let target_month = chrono::NaiveDate::from_ymd_opt(year, month, 1)
                .ok_or_else(|| "invalid_target_as_of_month_label".to_string())?;
            if target_month > forecast_period_end {
                return Err("target_as_of_after_forecast_period_end".into());
            }
        }
        "exact_date" | "fiscal_period" => {
            let target_as_of = parse_iso_date(&fem.target_as_of, "target_as_of")?;
            if target_as_of > forecast_period_end {
                return Err("target_as_of_after_forecast_period_end".into());
            }
        }
        "provider_horizon" => {
            if fem.target_as_of.trim().is_empty() {
                return Err("empty_target_as_of".into());
            }
        }
        other => return Err(format!("invalid_date_precision:{other}")),
    }
    Ok(())
}

fn parse_iso_date(s: &str, field: &str) -> Result<chrono::NaiveDate, String> {
    let b = s.as_bytes();
    if b.len() != 10 || b[4] != b'-' || b[7] != b'-' {
        return Err(format!("invalid_iso_date:{field}"));
    }
    let y: i32 = s[0..4]
        .parse()
        .map_err(|_| format!("invalid_iso_date:{field}"))?;
    let m: u32 = s[5..7]
        .parse()
        .map_err(|_| format!("invalid_iso_date:{field}"))?;
    let d: u32 = s[8..10]
        .parse()
        .map_err(|_| format!("invalid_iso_date:{field}"))?;
    if y < 1900 {
        return Err(format!("invalid_iso_date:{field}"));
    }
    chrono::NaiveDate::from_ymd_opt(y, m, d).ok_or_else(|| format!("invalid_iso_date:{field}"))
}

fn parse_yyyy_mm(s: &str) -> Option<(i32, u32)> {
    let b = s.as_bytes();
    if b.len() != 7 || b[4] != b'-' {
        return None;
    }
    let y: i32 = match s[0..4].parse() {
        Ok(v) => v,
        Err(_) => return None,
    };
    let m: u32 = match s[5..7].parse() {
        Ok(v) => v,
        Err(_) => return None,
    };
    if (1..=12).contains(&m) && y >= 1900 {
        Some((y, m))
    } else {
        None
    }
}

/// Canonical lifecycle fingerprint for idempotent run identity (1B.2 / 1B.3).
/// Includes explicit economic role bindings and identity vintage coordinates.
pub fn lifecycle_fingerprint(
    evidence_set_fp: &str,
    result_json: &str,
    canonical_command_sha256: &str,
    identity_fingerprint: &str,
    issuer_id: &str,
    security_id: &str,
    method: &str,
    engine_version: &str,
    method_policy_version: &str,
    replay_mode: ReplayMode,
    decision_at_unix_ms: i64,
    projection_key: Option<&str>,
    supersedes_run_id: Option<&str>,
    eps_observation_id: &str,
    multiple_observation_id: &str,
    share_basis_id: &str,
    eps_share_basis_id: &str,
    identity_vintage: &str,
    ticker: &str,
) -> String {
    use sha2::{Digest, Sha256};
    let mut out = Vec::new();
    fn write_str(buf: &mut Vec<u8>, s: &str) {
        let b = s.as_bytes();
        buf.push(0x01);
        buf.extend_from_slice(&(b.len() as u32).to_be_bytes());
        buf.extend_from_slice(b);
    }
    fn write_opt(buf: &mut Vec<u8>, s: Option<&str>) {
        match s {
            None => buf.push(0x00),
            Some(v) => write_str(buf, v),
        }
    }
    fn write_i64(buf: &mut Vec<u8>, v: i64) {
        buf.push(0x01);
        buf.extend_from_slice(&v.to_be_bytes());
    }
    // v4 carries a first-class decision instant, canonical full command and EPS basis attestation.
    write_str(&mut out, "ds.valuation.lifecycle.v4");
    write_str(&mut out, evidence_set_fp);
    write_str(&mut out, result_json);
    write_str(&mut out, canonical_command_sha256);
    write_str(&mut out, identity_fingerprint);
    write_str(&mut out, issuer_id);
    write_str(&mut out, security_id);
    write_str(&mut out, method);
    write_str(&mut out, engine_version);
    write_str(&mut out, method_policy_version);
    write_str(
        &mut out,
        crate::valuation_evidence::replay_mode_snake(replay_mode),
    );
    write_i64(&mut out, decision_at_unix_ms);
    write_opt(&mut out, projection_key);
    write_opt(&mut out, supersedes_run_id);
    write_str(&mut out, ROLE_FORWARD_EPS);
    write_str(&mut out, eps_observation_id);
    write_str(&mut out, ROLE_FORWARD_PE);
    write_str(&mut out, multiple_observation_id);
    write_str(&mut out, share_basis_id);
    write_str(&mut out, eps_share_basis_id);
    write_str(&mut out, identity_vintage);
    write_str(&mut out, ticker);
    format!(
        "sha256:{}",
        Sha256::digest(&out)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>()
    )
}

/// Versioned eligibility for a currently-pointed candidate (1B.3).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CurrentCandidateEligibility {
    pub eligible: bool,
    pub reason_code: Option<&'static str>,
}

pub fn evaluate_current_candidate_eligibility(
    run_replay_mode: ReplayMode,
    run_engine_version: &str,
    run_method_policy_version: &str,
    run_identity_fingerprint: &str,
    current_engine_version: &str,
    current_method_policy_version: &str,
    current_identity_fingerprint: &str,
    projection_invalidated: bool,
) -> CurrentCandidateEligibility {
    if projection_invalidated {
        return CurrentCandidateEligibility {
            eligible: false,
            reason_code: Some("projection_invalidated"),
        };
    }
    if !matches!(run_replay_mode, ReplayMode::Operational) {
        return CurrentCandidateEligibility {
            eligible: false,
            reason_code: Some("non_operational_replay"),
        };
    }
    if run_engine_version != current_engine_version {
        return CurrentCandidateEligibility {
            eligible: false,
            reason_code: Some("engine_version_stale"),
        };
    }
    if run_method_policy_version != current_method_policy_version {
        return CurrentCandidateEligibility {
            eligible: false,
            reason_code: Some("method_policy_stale"),
        };
    }
    if run_identity_fingerprint != current_identity_fingerprint {
        return CurrentCandidateEligibility {
            eligible: false,
            reason_code: Some("identity_vintage_stale"),
        };
    }
    CurrentCandidateEligibility {
        eligible: true,
        reason_code: None,
    }
}

/// PIT admission for every observation under `decision_at` / replay mode (1B.1).
pub fn admit_observations_for_decision(
    observations: &[EvidenceObservationV2],
    mode: ReplayMode,
    decision_at_unix_ms: i64,
) -> Result<(), String> {
    for obs in observations {
        let d = admit_observation(
            mode,
            decision_at_unix_ms,
            obs.publication_at_unix_ms,
            obs.source_available_at_unix_ms,
            obs.ingested_at_unix_ms,
            obs.availability_basis,
            obs.provider_vintage_id.as_deref(),
        );
        if !d.admit {
            return Err(format!(
                "look_ahead_refused:{}",
                d.refusal_code.unwrap_or("unknown")
            ));
        }
        if matches!(mode, ReplayMode::Operational) && !d.live_projection_eligible {
            return Err("operational_not_live_projection_eligible".into());
        }
    }
    Ok(())
}

pub fn fem_result_json(
    available: &crate::forward_earnings_multiple::ForwardEarningsMultipleAvailable,
    quality_label: ImportQualityLabel,
) -> Result<String, String> {
    #[derive(Serialize)]
    #[serde(rename_all = "camelCase")]
    struct Envelope<'a> {
        status: &'static str,
        engine_id: &'a str,
        method_policy_version: &'a str,
        import_quality_label: &'static str,
        target_value_cents: i64,
        eps_cents: i64,
        multiple_hundredths: i32,
        forecast_period_end: &'a str,
        target_as_of: &'a str,
        date_precision: &'a str,
        currency: &'a str,
        quality: &'a str,
    }
    serde_json::to_string(&Envelope {
        status: "available",
        engine_id: ENGINE_ID,
        method_policy_version: METHOD_POLICY_VERSION,
        import_quality_label: quality_label.as_str(),
        target_value_cents: available.target_value_cents,
        eps_cents: available.eps_cents,
        multiple_hundredths: available.multiple_hundredths,
        forecast_period_end: &available.forecast_period_end,
        target_as_of: &available.target_as_of,
        date_precision: &available.date_precision,
        currency: &available.currency,
        quality: &available.quality,
    })
    .map_err(|e| format!("result_json:{e}"))
}

fn enum_snake<T: Serialize>(value: T) -> String {
    serde_json::to_value(value)
        .ok()
        .and_then(|v| v.as_str().map(|s| s.to_string()))
        .unwrap_or_else(|| "unknown".into())
}

fn dto_to_observation(dto: &ObsImportDto) -> Result<EvidenceObservationV2, String> {
    Ok(EvidenceObservationV2 {
        id: dto.id.clone(),
        issuer_id: dto.issuer_id.clone(),
        security_id: dto.security_id.clone(),
        evidence_lane: parse_lane(&dto.evidence_lane)?,
        provider_id: dto.provider_id.clone(),
        lineage_group_id: dto.lineage_group_id.clone(),
        metric_id: dto.metric_id.clone(),
        metric_basis: parse_basis(&dto.metric_basis)?,
        accounting_regime: parse_regime(&dto.accounting_regime)?,
        economic_period_start: dto.economic_period_start.clone(),
        economic_period_end: dto.economic_period_end.clone(),
        date_precision: parse_precision(&dto.date_precision)?,
        publication_at_unix_ms: dto.publication_at_unix_ms,
        source_available_at_unix_ms: dto.source_available_at_unix_ms,
        ingested_at_unix_ms: dto.ingested_at_unix_ms,
        availability_basis: parse_availability(&dto.availability_basis)?,
        provider_vintage_id: dto.provider_vintage_id.clone(),
        unit: parse_unit(&dto.unit)?,
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
        storage_disposition: parse_storage(&dto.storage_disposition)?,
    })
}

fn parse_lane(s: &str) -> Result<EvidenceLane, String> {
    match s {
        "reported_actual" => Ok(EvidenceLane::ReportedActual),
        "issuer_guidance" => Ok(EvidenceLane::IssuerGuidance),
        "external_consensus" => Ok(EvidenceLane::ExternalConsensus),
        "internal_forecast" => Ok(EvidenceLane::InternalForecast),
        "analyst_stated_method" => Ok(EvidenceLane::AnalystStatedMethod),
        other => Err(format!("invalid_evidence_lane:{other}")),
    }
}

fn parse_basis(s: &str) -> Result<MetricBasis, String> {
    match s {
        "reported_gaap" => Ok(MetricBasis::ReportedGaap),
        "adjusted_normalized" => Ok(MetricBasis::AdjustedNormalized),
        "provider_unknown" => Ok(MetricBasis::ProviderUnknown),
        "transcription_claim" => Ok(MetricBasis::TranscriptionClaim),
        other => Err(format!("invalid_metric_basis:{other}")),
    }
}

fn parse_regime(s: &str) -> Result<AccountingRegime, String> {
    match s {
        "domestic_us_gaap" => Ok(AccountingRegime::DomesticUsGaap),
        "ifrs" => Ok(AccountingRegime::Ifrs),
        "not_applicable" => Ok(AccountingRegime::NotApplicable),
        "unsupported" => Ok(AccountingRegime::Unsupported),
        other => Err(format!("invalid_accounting_regime:{other}")),
    }
}

fn parse_precision(s: &str) -> Result<DatePrecision, String> {
    match s {
        "exact_date" => Ok(DatePrecision::ExactDate),
        "month_label" => Ok(DatePrecision::MonthLabel),
        "fiscal_period" => Ok(DatePrecision::FiscalPeriod),
        "provider_horizon" => Ok(DatePrecision::ProviderHorizon),
        other => Err(format!("invalid_date_precision:{other}")),
    }
}

fn parse_availability(s: &str) -> Result<AvailabilityBasis, String> {
    match s {
        "primary_publication" => Ok(AvailabilityBasis::PrimaryPublication),
        "provider_certified_vintage" => Ok(AvailabilityBasis::ProviderCertifiedVintage),
        "first_observed_capture" => Ok(AvailabilityBasis::FirstObservedCapture),
        other => Err(format!("invalid_availability_basis:{other}")),
    }
}

fn parse_unit(s: &str) -> Result<EvidenceUnitV2, String> {
    match s {
        "money_cents" => Ok(EvidenceUnitV2::MoneyCents),
        "rate_bps" => Ok(EvidenceUnitV2::RateBps),
        "quantity_millis" => Ok(EvidenceUnitV2::QuantityMillis),
        "shares" => Ok(EvidenceUnitV2::Shares),
        "text" => Ok(EvidenceUnitV2::Text),
        "boolean" => Ok(EvidenceUnitV2::Boolean),
        "multiple_hundredths" => Ok(EvidenceUnitV2::MultipleHundredths),
        other => Err(format!("invalid_unit:{other}")),
    }
}

fn parse_storage(s: &str) -> Result<StorageDisposition, String> {
    match s {
        "metadata_only" => Ok(StorageDisposition::MetadataOnly),
        "encrypted_artifact" => Ok(StorageDisposition::EncryptedArtifact),
        "prohibited" => Ok(StorageDisposition::Prohibited),
        other => Err(format!("invalid_storage_disposition:{other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::forward_earnings_multiple::{
        compute_forward_earnings_multiple, ForwardEarningsMultipleResult,
    };
    use std::path::PathBuf;

    fn fixture_import_json() -> String {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/contracts/valuation-forward-earnings-import-v1.json");
        let raw = std::fs::read_to_string(path).unwrap();
        let v: serde_json::Value = serde_json::from_str(&raw).unwrap();
        v["fixtures"]["available"][0]["import"].to_string()
    }

    #[test]
    fn shared_pointer_refusals_execute_in_exact_contract_order() {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/contracts/valuation-forward-earnings-import-v1.json");
        let contract: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let base = contract["fixtures"]["available"][0]["import"].clone();
        let context = &contract["fixtures"]["available"][0]["admissionContext"];
        let expected = [
            (
                "impossible_forecast_calendar_date_refuses",
                "invalid_iso_date:forecast_period_end",
            ),
            (
                "impossible_eps_calendar_date_refuses",
                "invalid_iso_date:eps_period_end",
            ),
            (
                "impossible_exact_target_calendar_date_refuses",
                "invalid_iso_date:target_as_of",
            ),
            (
                "economic_period_start_after_end_refuses",
                "economic_period_start_after_end",
            ),
            (
                "invalid_month_label_refuses",
                "invalid_target_as_of_month_label",
            ),
            (
                "target_after_forecast_period_refuses",
                "target_as_of_after_forecast_period_end",
            ),
            (
                "unsupported_target_precision_refuses",
                "invalid_date_precision:quarter_label",
            ),
            (
                "multiple_period_relationship_mismatch_refuses",
                "period_mismatch_eps_multiple",
            ),
            (
                "split_vintage_eps_share_basis_mismatch_refuses",
                "eps_share_basis_mismatch",
            ),
            ("decision_only_mutation_refuses", "decision_at_mismatch"),
            (
                "negative_decision_coordinate_refuses",
                "invalid_decision_at_unix_ms",
            ),
            (
                "zero_decision_coordinate_refuses",
                "invalid_decision_at_unix_ms",
            ),
        ];
        let refusals = contract["fixtures"]["refusals"].as_array().unwrap();
        let shared_horizon: Vec<&serde_json::Value> = refusals
            .iter()
            .filter(|fixture| fixture.get("jsonPointerPatch").is_some())
            .collect();
        assert_eq!(shared_horizon.len(), expected.len());

        for (fixture, (expected_name, expected_reason)) in shared_horizon.into_iter().zip(expected)
        {
            assert_eq!(fixture["name"].as_str(), Some(expected_name));
            assert_eq!(
                fixture["expectedReasonCode"].as_str(),
                Some(expected_reason)
            );
            let mut candidate = base.clone();
            for (pointer, value) in fixture["jsonPointerPatch"].as_object().unwrap() {
                *candidate
                    .pointer_mut(pointer)
                    .unwrap_or_else(|| panic!("missing contract pointer {pointer}")) =
                    value.clone();
            }
            let actual = match parse_analyst_method_import_json(&candidate.to_string()) {
                Err(reason) => reason,
                Ok(parsed)
                    if parsed.decision_at_unix_ms
                        != context["decisionAtUnixMs"].as_i64().unwrap() =>
                {
                    "decision_at_mismatch".into()
                }
                Ok(parsed)
                    if parsed.eps_share_basis_id != context["shareBasisId"].as_str().unwrap() =>
                {
                    "eps_share_basis_mismatch".into()
                }
                Ok(_) => panic!("fixture {expected_name} was admitted"),
            };
            assert_eq!(actual, expected_reason, "fixture {expected_name}");
        }
    }

    #[test]
    fn control_envelope_does_not_admit_quality_or_replay_semantics() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["qualityLabel"] = serde_json::json!({ "invalid": "shape" });
        v["replayMode"] = serde_json::json!(42);

        let envelope = parse_control_envelope(&v.to_string()).unwrap();
        assert_eq!(envelope.issuer_id, "issuer:0001018724");
        assert_eq!(envelope.security_id, "sec:amzn-us");
        let semantic = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert!(semantic.contains("import_json_parse"), "{semantic}");
    }

    #[test]
    fn canonical_command_digest_ignores_json_formatting_but_covers_accepted_fields() {
        let raw = fixture_import_json();
        let pretty =
            serde_json::to_string_pretty(&serde_json::from_str::<serde_json::Value>(&raw).unwrap())
                .unwrap();
        assert_eq!(
            canonical_command_sha256(&raw).unwrap(),
            canonical_command_sha256(&pretty).unwrap()
        );

        let mut changed: serde_json::Value = serde_json::from_str(&raw).unwrap();
        changed["fem"]["marketPriceCents"] = serde_json::json!(20_001);
        assert_ne!(
            canonical_command_sha256(&raw).unwrap(),
            canonical_command_sha256(&changed.to_string()).unwrap()
        );
    }

    #[test]
    fn non_positive_decision_instant_refuses() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["decisionAtUnixMs"] = serde_json::json!(0);
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert_eq!(err, "invalid_decision_at_unix_ms");
    }

    #[test]
    fn derives_fem_from_observations_not_duplicate_fields() {
        let parsed = parse_analyst_method_import_json(&fixture_import_json()).unwrap();
        assert_eq!(parsed.fem_input.eps_cents, 1300);
        assert_eq!(parsed.fem_input.multiple_hundredths, 2800);
        assert_eq!(parsed.eps_observation_id, "obs:fixture:eps:1");
        assert_eq!(parsed.multiple_observation_id, "obs:fixture:pe:1");
        match compute_forward_earnings_multiple(&parsed.fem_input) {
            ForwardEarningsMultipleResult::Available(a) => {
                assert_eq!(a.target_value_cents, 36_400);
            }
            other => panic!("expected available: {other:?}"),
        }
    }

    #[test]
    fn eps_value_change_without_observation_update_impossible() {
        // No epsCents on wire — only observation values matter.
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["observations"][0]["valueCents"] = serde_json::json!(1400);
        let parsed = parse_analyst_method_import_json(&v.to_string()).unwrap();
        assert_eq!(parsed.fem_input.eps_cents, 1400);
        match compute_forward_earnings_multiple(&parsed.fem_input) {
            ForwardEarningsMultipleResult::Available(a) => {
                assert_eq!(a.target_value_cents, 39_200); // 14 * 28
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn missing_eps_observation_refuses() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["fem"]["epsObservationId"] = serde_json::json!("obs:missing");
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert!(err.contains("eps_observation_not_in_set"), "{err}");
    }

    #[test]
    fn unverified_with_reported_gaap_refuses() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["qualityLabel"] = serde_json::json!("manual_transcription_unverified");
        v["observations"][0]["metricBasis"] = serde_json::json!("reported_gaap");
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert!(
            err.contains("unverified_requires_transcription_claim"),
            "{err}"
        );
    }

    #[test]
    fn storage_prohibited_refuses_on_admit() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["observations"][0]["storageDisposition"] = serde_json::json!("prohibited");
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert!(
            err.contains("observation_invalid:storage_prohibited"),
            "{err}"
        );
    }

    #[test]
    fn pit_operational_look_ahead_refuses() {
        let parsed = parse_analyst_method_import_json(&fixture_import_json()).unwrap();
        // decision far before observation clocks
        let err =
            admit_observations_for_decision(&parsed.observations, ReplayMode::Operational, 100)
                .unwrap_err();
        assert!(err.contains("look_ahead_refused"), "{err}");
    }

    #[test]
    fn non_eps_money_observation_refuses() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["observations"][0]["metricId"] = serde_json::json!("revenue");
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert!(err.contains("eps_metric_not_earnings"), "{err}");
    }

    #[test]
    fn non_forward_pe_multiple_refuses() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["observations"][1]["metricId"] = serde_json::json!("ev_ebitda");
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert!(err.contains("multiple_metric_not_forward_pe"), "{err}");
    }

    #[test]
    fn lineage_mismatch_refuses() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["observations"][1]["lineageGroupId"] = serde_json::json!("lineage:other");
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert!(err.contains("lineage_mismatch_eps_multiple"), "{err}");
    }

    #[test]
    fn period_mismatch_refuses() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["observations"][0]["economicPeriodEnd"] = serde_json::json!("2029-12-31");
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert!(
            err.contains("eps_period_mismatch_forecast") || err.contains("period_mismatch"),
            "{err}"
        );
    }

    #[test]
    fn impossible_calendar_date_refuses() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["observations"][0]["economicPeriodStart"] = serde_json::json!("2028-02-31");
        v["observations"][1]["economicPeriodStart"] = serde_json::json!("2028-02-31");
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert_eq!(err, "invalid_iso_date:eps_period_start");
    }

    #[test]
    fn economic_period_start_after_end_refuses() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["observations"][0]["economicPeriodStart"] = serde_json::json!("2029-01-01");
        v["observations"][1]["economicPeriodStart"] = serde_json::json!("2029-01-01");
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert_eq!(err, "economic_period_start_after_end");
    }

    #[test]
    fn target_as_of_after_forecast_period_end_refuses() {
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["fem"]["targetAsOf"] = serde_json::json!("2029-01");
        let err = parse_analyst_method_import_json(&v.to_string()).unwrap_err();
        assert_eq!(err, "target_as_of_after_forecast_period_end");
    }

    #[test]
    fn decision_instant_is_part_of_semantic_fingerprint() {
        let first = lifecycle_fingerprint(
            "set",
            "result",
            "command",
            "identity",
            "issuer",
            "security",
            "method",
            "engine",
            "policy",
            ReplayMode::Operational,
            100,
            Some("projection"),
            None,
            "eps",
            "pe",
            "basis",
            "basis",
            "vintage",
            "T",
        );
        let different_decision = lifecycle_fingerprint(
            "set",
            "result",
            "command",
            "identity",
            "issuer",
            "security",
            "method",
            "engine",
            "policy",
            ReplayMode::Operational,
            999,
            Some("projection"),
            None,
            "eps",
            "pe",
            "basis",
            "basis",
            "vintage",
            "T",
        );
        assert_ne!(first, different_decision);
    }
}
