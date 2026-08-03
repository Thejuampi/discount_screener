//! Slice 1C: additive valuation dossier read model for the market-reference lane.
//!
//! Diagnostic only — never writes legacy FCFF/selected/intrinsic scalars and never
//! feeds Quant Lens ranking / Strong / blended EV.

use serde::{Deserialize, Serialize};

use crate::analyst_method_import::METHOD_FORWARD_EARNINGS_MULTIPLE;
use crate::db::{AnalystMethodPublication, Db};
use crate::forward_earnings_multiple::{ENGINE_ID, METHOD_POLICY_VERSION};
use crate::quant_lens::QuantLensSection;
use crate::valuation_evidence::EvidenceObservationV2;

pub const DOSSIER_VIEW_VERSION: i32 = 1;
pub const LANE_ANALYST_METHOD: &str = "manual_analyst_method";

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AnalystMethodLaneStatus {
    Available,
    Unavailable,
    Absent,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalystMethodCandidateView {
    pub status: AnalystMethodLaneStatus,
    pub run_id: Option<String>,
    pub projection_key: Option<String>,
    /// Decimal strings preserve the full SQLite/Rust i64 domain across JSON/JavaScript.
    pub target_value_cents: Option<String>,
    pub eps_cents: Option<String>,
    pub multiple_hundredths: Option<i32>,
    pub forecast_period_end: Option<String>,
    pub economic_period_start: Option<String>,
    pub target_as_of: Option<String>,
    pub date_precision: Option<String>,
    pub currency: Option<String>,
    pub metric_id: Option<String>,
    pub metric_basis: Option<String>,
    pub multiple_provenance: Option<String>,
    pub scenario: Option<String>,
    pub quality: Option<String>,
    pub import_quality_label: Option<String>,
    pub source_verification: Option<String>,
    pub method_label: String,
    pub engine_id: Option<String>,
    pub method_policy_version: Option<String>,
    pub decision_at_unix_ms: Option<i64>,
    pub computation_created_at_unix_ms: Option<i64>,
    pub evidence_observed_at_unix_ms: Option<i64>,
    pub replay_mode: Option<String>,
    pub issuer_id: Option<String>,
    pub security_id: Option<String>,
    pub ticker: Option<String>,
    pub identity_vintage: Option<String>,
    pub identity_fingerprint: Option<String>,
    pub share_basis_id: Option<String>,
    pub share_basis_vintage_fingerprint: Option<String>,
    pub share_basis_description: Option<String>,
    pub per_share_basis_id: Option<String>,
    pub corporate_action_vintage: Option<String>,
    pub fiscal_calendar_vintage: Option<String>,
    pub fiscal_period_coordinate: Option<String>,
    pub fiscal_calendar_verification: Option<String>,
    pub horizon_comparison_eligible: bool,
    pub eps_observation_id: Option<String>,
    pub multiple_observation_id: Option<String>,
    pub lineage_group_id: Option<String>,
    pub eps_provider_id: Option<String>,
    pub multiple_provider_id: Option<String>,
    pub eps_provider_vintage_id: Option<String>,
    pub multiple_provider_vintage_id: Option<String>,
    pub eps_source_location: Option<String>,
    pub multiple_source_location: Option<String>,
    pub eps_extraction_method: Option<String>,
    pub multiple_extraction_method: Option<String>,
    pub eps_revision_id: Option<String>,
    pub multiple_revision_id: Option<String>,
    pub eps_publication_at_unix_ms: Option<i64>,
    pub multiple_publication_at_unix_ms: Option<i64>,
    pub eps_source_available_at_unix_ms: Option<i64>,
    pub multiple_source_available_at_unix_ms: Option<i64>,
    pub eps_ingested_at_unix_ms: Option<i64>,
    pub multiple_ingested_at_unix_ms: Option<i64>,
    pub reason_code: Option<String>,
    /// Always true for Slice 1C: candidate is diagnostic-only and not ranking-eligible.
    pub diagnostic_only: bool,
    pub ranking_eligible: bool,
    pub strong_eligible: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ValuationDossierView {
    pub symbol: String,
    pub view_version: i32,
    pub analyst_method: AnalystMethodCandidateView,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FemResultEnvelope {
    status: String,
    engine_id: String,
    method_policy_version: String,
    import_quality_label: String,
    target_value_cents: i64,
    eps_cents: i64,
    multiple_hundredths: i32,
    forecast_period_end: String,
    target_as_of: String,
    date_precision: String,
    currency: String,
    quality: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CommandProvenanceEnvelope {
    fem: CommandFemProvenance,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CommandFemProvenance {
    multiple_provenance: String,
}

impl AnalystMethodCandidateView {
    fn absent() -> Self {
        Self {
            status: AnalystMethodLaneStatus::Absent,
            run_id: None,
            projection_key: None,
            target_value_cents: None,
            eps_cents: None,
            multiple_hundredths: None,
            forecast_period_end: None,
            economic_period_start: None,
            target_as_of: None,
            date_precision: None,
            currency: None,
            metric_id: None,
            metric_basis: None,
            multiple_provenance: None,
            scenario: None,
            quality: None,
            import_quality_label: None,
            source_verification: None,
            method_label: "manual analyst method".into(),
            engine_id: None,
            method_policy_version: None,
            decision_at_unix_ms: None,
            computation_created_at_unix_ms: None,
            evidence_observed_at_unix_ms: None,
            replay_mode: None,
            issuer_id: None,
            security_id: None,
            ticker: None,
            identity_vintage: None,
            identity_fingerprint: None,
            share_basis_id: None,
            share_basis_vintage_fingerprint: None,
            share_basis_description: None,
            per_share_basis_id: None,
            corporate_action_vintage: None,
            fiscal_calendar_vintage: None,
            fiscal_period_coordinate: None,
            fiscal_calendar_verification: None,
            horizon_comparison_eligible: false,
            eps_observation_id: None,
            multiple_observation_id: None,
            lineage_group_id: None,
            eps_provider_id: None,
            multiple_provider_id: None,
            eps_provider_vintage_id: None,
            multiple_provider_vintage_id: None,
            eps_source_location: None,
            multiple_source_location: None,
            eps_extraction_method: None,
            multiple_extraction_method: None,
            eps_revision_id: None,
            multiple_revision_id: None,
            eps_publication_at_unix_ms: None,
            multiple_publication_at_unix_ms: None,
            eps_source_available_at_unix_ms: None,
            multiple_source_available_at_unix_ms: None,
            eps_ingested_at_unix_ms: None,
            multiple_ingested_at_unix_ms: None,
            reason_code: None,
            diagnostic_only: true,
            ranking_eligible: false,
            strong_eligible: false,
        }
    }

    fn unavailable(run_id: Option<String>, projection_key: Option<String>, reason: &str) -> Self {
        Self {
            status: AnalystMethodLaneStatus::Unavailable,
            run_id,
            projection_key,
            target_value_cents: None,
            eps_cents: None,
            multiple_hundredths: None,
            forecast_period_end: None,
            economic_period_start: None,
            target_as_of: None,
            date_precision: None,
            currency: None,
            metric_id: None,
            metric_basis: None,
            multiple_provenance: None,
            scenario: None,
            quality: None,
            import_quality_label: None,
            source_verification: None,
            method_label: "manual analyst method".into(),
            engine_id: Some(ENGINE_ID.into()),
            method_policy_version: Some(METHOD_POLICY_VERSION.into()),
            decision_at_unix_ms: None,
            computation_created_at_unix_ms: None,
            evidence_observed_at_unix_ms: None,
            replay_mode: None,
            issuer_id: None,
            security_id: None,
            ticker: None,
            identity_vintage: None,
            identity_fingerprint: None,
            share_basis_id: None,
            share_basis_vintage_fingerprint: None,
            share_basis_description: None,
            per_share_basis_id: None,
            corporate_action_vintage: None,
            fiscal_calendar_vintage: None,
            fiscal_period_coordinate: None,
            fiscal_calendar_verification: None,
            horizon_comparison_eligible: false,
            eps_observation_id: None,
            multiple_observation_id: None,
            lineage_group_id: None,
            eps_provider_id: None,
            multiple_provider_id: None,
            eps_provider_vintage_id: None,
            multiple_provider_vintage_id: None,
            eps_source_location: None,
            multiple_source_location: None,
            eps_extraction_method: None,
            multiple_extraction_method: None,
            eps_revision_id: None,
            multiple_revision_id: None,
            eps_publication_at_unix_ms: None,
            multiple_publication_at_unix_ms: None,
            eps_source_available_at_unix_ms: None,
            multiple_source_available_at_unix_ms: None,
            eps_ingested_at_unix_ms: None,
            multiple_ingested_at_unix_ms: None,
            reason_code: Some(reason.into()),
            diagnostic_only: true,
            ranking_eligible: false,
            strong_eligible: false,
        }
    }
}

fn source_verification_label(import_quality_label: &str) -> &'static str {
    match import_quality_label {
        "fixture_transcription" => "source_not_verified",
        "manual_transcription_unverified" => "source_not_verified",
        _ => "source_not_verified",
    }
}

/// Sanitized fail-closed view used by command shells. Infrastructure details never cross IPC.
pub fn publication_read_failure_dossier(symbol: &str) -> ValuationDossierView {
    unavailable_dossier(symbol, "publication_read_failed")
}

pub fn unavailable_dossier(symbol: &str, reason: &str) -> ValuationDossierView {
    ValuationDossierView {
        symbol: symbol.trim().to_uppercase(),
        view_version: DOSSIER_VIEW_VERSION,
        analyst_method: AnalystMethodCandidateView::unavailable(None, None, reason),
    }
}

/// Cache-only publication read for the analyst-method lane (1C).
pub fn load_valuation_dossier(db: &Db, symbol: &str) -> Result<ValuationDossierView, String> {
    let ticker = symbol.trim().to_uppercase();
    if ticker.is_empty() {
        return Ok(ValuationDossierView {
            symbol: symbol.to_string(),
            view_version: DOSSIER_VIEW_VERSION,
            analyst_method: AnalystMethodCandidateView::absent(),
        });
    }

    let payload = db.load_analyst_method_publication(&ticker)?;
    let analyst_method = match payload {
        AnalystMethodPublication::Absent => AnalystMethodCandidateView::absent(),
        AnalystMethodPublication::Ineligible {
            run_id,
            projection_key,
            reason_code,
        } => AnalystMethodCandidateView::unavailable(run_id, projection_key, &reason_code),
        AnalystMethodPublication::Eligible(run) => {
            let env: FemResultEnvelope = match serde_json::from_str(&run.result_json) {
                Ok(env) => env,
                Err(_) => {
                    return Ok(unavailable_dossier(&ticker, "result_json_corrupt"));
                }
            };
            let eps: EvidenceObservationV2 = match serde_json::from_str(&run.eps_observation_json) {
                Ok(obs) => obs,
                Err(_) => {
                    return Ok(unavailable_dossier(&ticker, "evidence_payload_corrupt"));
                }
            };
            let multiple: EvidenceObservationV2 =
                match serde_json::from_str(&run.multiple_observation_json) {
                    Ok(obs) => obs,
                    Err(_) => {
                        return Ok(unavailable_dossier(&ticker, "evidence_payload_corrupt"));
                    }
                };
            let command: CommandProvenanceEnvelope =
                match serde_json::from_str(&run.raw_command_json) {
                    Ok(command) => command,
                    Err(_) => {
                        return Ok(unavailable_dossier(&ticker, "command_payload_corrupt"));
                    }
                };
            if env.status != "available" {
                AnalystMethodCandidateView::unavailable(
                    Some(run.run_id),
                    Some(run.projection_key),
                    "result_not_available",
                )
            } else {
                AnalystMethodCandidateView {
                    status: AnalystMethodLaneStatus::Available,
                    run_id: Some(run.run_id),
                    projection_key: Some(run.projection_key),
                    target_value_cents: Some(env.target_value_cents.to_string()),
                    eps_cents: Some(env.eps_cents.to_string()),
                    multiple_hundredths: Some(env.multiple_hundredths),
                    forecast_period_end: Some(env.forecast_period_end),
                    economic_period_start: Some(eps.economic_period_start.clone()),
                    target_as_of: Some(env.target_as_of),
                    date_precision: Some(env.date_precision),
                    currency: Some(env.currency),
                    metric_id: Some(eps.metric_id.clone()),
                    metric_basis: Some(enum_token(&eps.metric_basis)),
                    multiple_provenance: Some(command.fem.multiple_provenance),
                    scenario: Some("base_reference".into()),
                    quality: Some(env.quality),
                    import_quality_label: Some(env.import_quality_label.clone()),
                    source_verification: Some(
                        source_verification_label(&env.import_quality_label).into(),
                    ),
                    method_label: "manual analyst method".into(),
                    engine_id: Some(env.engine_id),
                    method_policy_version: Some(env.method_policy_version),
                    decision_at_unix_ms: Some(run.decision_at_unix_ms),
                    computation_created_at_unix_ms: Some(run.created_at_unix_ms),
                    evidence_observed_at_unix_ms: Some(
                        eps.source_available_at_unix_ms
                            .max(multiple.source_available_at_unix_ms),
                    ),
                    replay_mode: Some(run.replay_mode),
                    issuer_id: Some(run.issuer_id),
                    security_id: Some(run.security_id),
                    ticker: Some(run.ticker),
                    identity_vintage: Some(run.identity_vintage.clone()),
                    identity_fingerprint: Some(run.identity_fingerprint),
                    share_basis_id: Some(run.share_basis_id.clone()),
                    share_basis_vintage_fingerprint: Some(run.share_basis_vintage_fingerprint),
                    share_basis_description: Some(run.share_basis_description),
                    per_share_basis_id: Some(run.share_basis_id.clone()),
                    // Identity and share-basis coordinates are persisted independently. Neither
                    // proves that a corporate-action master vintage was observed.
                    corporate_action_vintage: None,
                    // Provider vintage is source provenance, not a fiscal-calendar master.
                    // Slice 1 keeps this absence explicit and cannot compare horizons.
                    fiscal_calendar_vintage: None,
                    fiscal_period_coordinate: Some(format!(
                        "{}/{}",
                        eps.economic_period_start, eps.economic_period_end
                    )),
                    fiscal_calendar_verification: Some("not_captured".into()),
                    horizon_comparison_eligible: false,
                    eps_observation_id: Some(run.eps_observation_id),
                    multiple_observation_id: Some(run.multiple_observation_id),
                    lineage_group_id: Some(eps.lineage_group_id.clone()),
                    eps_provider_id: Some(eps.provider_id.clone()),
                    multiple_provider_id: Some(multiple.provider_id.clone()),
                    eps_provider_vintage_id: eps.provider_vintage_id.clone(),
                    multiple_provider_vintage_id: multiple.provider_vintage_id.clone(),
                    eps_source_location: Some(eps.source_location.clone()),
                    multiple_source_location: Some(multiple.source_location.clone()),
                    eps_extraction_method: Some(eps.extraction_method.clone()),
                    multiple_extraction_method: Some(multiple.extraction_method.clone()),
                    eps_revision_id: Some(eps.revision_id.clone()),
                    multiple_revision_id: Some(multiple.revision_id.clone()),
                    eps_publication_at_unix_ms: Some(eps.publication_at_unix_ms),
                    multiple_publication_at_unix_ms: Some(multiple.publication_at_unix_ms),
                    eps_source_available_at_unix_ms: Some(eps.source_available_at_unix_ms),
                    multiple_source_available_at_unix_ms: Some(
                        multiple.source_available_at_unix_ms,
                    ),
                    eps_ingested_at_unix_ms: Some(eps.ingested_at_unix_ms),
                    multiple_ingested_at_unix_ms: Some(multiple.ingested_at_unix_ms),
                    reason_code: None,
                    diagnostic_only: true,
                    ranking_eligible: false,
                    strong_eligible: false,
                }
            }
        }
    };

    Ok(ValuationDossierView {
        symbol: ticker,
        view_version: DOSSIER_VIEW_VERSION,
        analyst_method,
    })
}

fn enum_token<T: Serialize>(value: &T) -> String {
    serde_json::to_value(value)
        .ok()
        .and_then(|v| v.as_str().map(str::to_owned))
        .unwrap_or_else(|| "unknown".into())
}

/// Parallel Quant Lens section — never alters evidence families, EV, or Strong.
pub fn analyst_method_quant_section(view: &AnalystMethodCandidateView) -> Option<QuantLensSection> {
    match view.status {
        AnalystMethodLaneStatus::Absent => None,
        AnalystMethodLaneStatus::Unavailable => {
            let reason = view.reason_code.as_deref().unwrap_or("unavailable");
            Some(QuantLensSection {
                id: LANE_ANALYST_METHOD.into(),
                title: "Manual analyst method".into(),
                status: "Unavailable".into(),
                summary: format!(
                    "Market-reference candidate unavailable ({reason}); diagnostic only — not ranking or Strong"
                ),
                metrics: vec![
                    ("lane".into(), LANE_ANALYST_METHOD.into()),
                    ("diagnostic_only".into(), "true".into()),
                    ("ranking_eligible".into(), "false".into()),
                    ("strong_eligible".into(), "false".into()),
                    ("reason_code".into(), reason.into()),
                    (
                        "run_id".into(),
                        view.run_id.clone().unwrap_or_else(|| "null".into()),
                    ),
                ],
            })
        }
        AnalystMethodLaneStatus::Available => {
            let target = view.target_value_cents.as_deref().unwrap_or("0");
            let eps = view.eps_cents.as_deref().unwrap_or("0");
            let mult = view.multiple_hundredths.unwrap_or(0);
            let source = view
                .source_verification
                .as_deref()
                .unwrap_or("source_not_verified");
            let period = view.forecast_period_end.as_deref().unwrap_or("—");
            let horizon = view.target_as_of.as_deref().unwrap_or("—");
            let precision = view.date_precision.as_deref().unwrap_or("—");
            let currency = view.currency.as_deref().unwrap_or("—");
            Some(QuantLensSection {
                id: LANE_ANALYST_METHOD.into(),
                title: "Manual analyst method".into(),
                status: "Provisional".into(),
                summary: format!(
                    "Manual analyst method {currency} {} = EPS {currency} {} × {}x · forecast {} · target as-of {} ({}) · {} · diagnostic only",
                    fixed_decimal_string(target, 2),
                    fixed_decimal_string(eps, 2),
                    fixed_decimal_i128(mult as i128, 2),
                    period,
                    horizon,
                    precision,
                    source.replace('_', " "),
                ),
                metrics: vec![
                    ("lane".into(), LANE_ANALYST_METHOD.into()),
                    ("method_label".into(), view.method_label.clone()),
                    ("target_value_cents".into(), target.into()),
                    ("eps_cents".into(), eps.into()),
                    ("multiple_hundredths".into(), mult.to_string()),
                    ("forecast_period_end".into(), period.into()),
                    ("target_as_of".into(), horizon.into()),
                    ("date_precision".into(), precision.into()),
                    (
                        "currency".into(),
                        currency.into(),
                    ),
                    (
                        "metric_id".into(),
                        view.metric_id.clone().unwrap_or_else(|| "—".into()),
                    ),
                    (
                        "metric_basis".into(),
                        view.metric_basis.clone().unwrap_or_else(|| "—".into()),
                    ),
                    ("source_verification".into(), source.into()),
                    (
                        "multiple_provenance".into(),
                        view.multiple_provenance
                            .clone()
                            .unwrap_or_else(|| "—".into()),
                    ),
                    (
                        "scenario".into(),
                        view.scenario.clone().unwrap_or_else(|| "—".into()),
                    ),
                    (
                        "import_quality_label".into(),
                        view.import_quality_label
                            .clone()
                            .unwrap_or_else(|| "—".into()),
                    ),
                    (
                        "quality".into(),
                        view.quality.clone().unwrap_or_else(|| "—".into()),
                    ),
                    ("diagnostic_only".into(), "true".into()),
                    ("ranking_eligible".into(), "false".into()),
                    ("strong_eligible".into(), "false".into()),
                    (
                        "engine_id".into(),
                        view.engine_id.clone().unwrap_or_else(|| ENGINE_ID.into()),
                    ),
                    (
                        "method_policy_version".into(),
                        view.method_policy_version
                            .clone()
                            .unwrap_or_else(|| METHOD_POLICY_VERSION.into()),
                    ),
                    (
                        "run_id".into(),
                        view.run_id.clone().unwrap_or_else(|| "null".into()),
                    ),
                    (
                        "share_basis_id".into(),
                        view.share_basis_id.clone().unwrap_or_else(|| "—".into()),
                    ),
                    (
                        "share_basis_vintage_fingerprint".into(),
                        view.share_basis_vintage_fingerprint
                            .clone()
                            .unwrap_or_else(|| "—".into()),
                    ),
                    (
                        "identity_vintage".into(),
                        view.identity_vintage.clone().unwrap_or_else(|| "—".into()),
                    ),
                    (
                        "fiscal_calendar_vintage".into(),
                        view.fiscal_calendar_vintage
                            .clone()
                            .unwrap_or_else(|| "not_captured".into()),
                    ),
                    (
                        "fiscal_period_coordinate".into(),
                        view.fiscal_period_coordinate
                            .clone()
                            .unwrap_or_else(|| "—".into()),
                    ),
                    (
                        "fiscal_calendar_verification".into(),
                        view.fiscal_calendar_verification
                            .clone()
                            .unwrap_or_else(|| "not_captured".into()),
                    ),
                    (
                        "horizon_comparison_eligible".into(),
                        view.horizon_comparison_eligible.to_string(),
                    ),
                    (
                        "lineage_group_id".into(),
                        view.lineage_group_id.clone().unwrap_or_else(|| "—".into()),
                    ),
                    (
                        "method".into(),
                        METHOD_FORWARD_EARNINGS_MULTIPLE.into(),
                    ),
                ],
            })
        }
    }
}

fn fixed_decimal_string(raw: &str, scale: u32) -> String {
    match raw.parse::<i128>() {
        Ok(value) => fixed_decimal_i128(value, scale),
        Err(_) => "—".into(),
    }
}

fn fixed_decimal_i128(value: i128, scale: u32) -> String {
    let divisor = 10_i128.pow(scale);
    let magnitude = value.unsigned_abs();
    let whole = magnitude / divisor as u128;
    let fraction = magnitude % divisor as u128;
    let sign = if value < 0 { "-" } else { "" };
    format!("{sign}{whole}.{fraction:0width$}", width = scale as usize)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::analyst_method_service::commit_analyst_method_import;
    use crate::issuer_identity::{
        fixture_amzn_shaped, identity_vintage_fingerprint, IdentityBundle,
    };
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    const DECISION_AT: i64 = 1_753_920_000_000;

    fn seed(db: &Db, b: &IdentityBundle) {
        db.upsert_identity_bundle(
            &b.issuer.issuer_id,
            &b.issuer.cik,
            b.issuer.legal_name.as_deref(),
            &b.security.security_id,
            &b.security.currency,
            b.security.share_class_label.as_deref(),
            &b.ticker_alias.ticker,
            &b.ticker_alias.effective_from,
            &b.ticker_alias.identity_vintage,
            &b.share_basis.basis_id,
            &b.share_basis.vintage_fingerprint,
            &b.share_basis.description,
        )
        .unwrap();
    }

    fn fixture_import_json() -> String {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/contracts/valuation-forward-earnings-import-v1.json");
        let raw = std::fs::read_to_string(path).unwrap();
        let v: serde_json::Value = serde_json::from_str(&raw).unwrap();
        v["fixtures"]["available"][0]["import"].to_string()
    }

    fn temp_db_path(label: &str) -> PathBuf {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../.agents/workspace/tmp");
        std::fs::create_dir_all(&dir).unwrap();
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        dir.join(format!(
            "dossier-{label}-{}-{nonce}.sqlite",
            std::process::id()
        ))
    }

    #[test]
    fn dossier_absent_when_ticker_unknown() {
        let db = Db::open_in_memory().unwrap();
        let view = load_valuation_dossier(&db, "ZZZZ").unwrap();
        assert_eq!(view.symbol, "ZZZZ");
        assert_eq!(view.analyst_method.status, AnalystMethodLaneStatus::Absent);
        assert!(!view.analyst_method.ranking_eligible);
        assert!(!view.analyst_method.strong_eligible);
        assert!(view.analyst_method.diagnostic_only);
        assert!(analyst_method_quant_section(&view.analyst_method).is_none());
    }

    #[test]
    fn dossier_projects_eligible_fixture_as_diagnostic_three_sixty_four() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();

        let view = load_valuation_dossier(&db, "amzn").unwrap();
        assert_eq!(view.symbol, "AMZN");
        assert_eq!(
            view.analyst_method.status,
            AnalystMethodLaneStatus::Available,
            "reason={:?}",
            view.analyst_method.reason_code
        );
        assert_eq!(
            view.analyst_method.target_value_cents.as_deref(),
            Some("36400")
        );
        assert_eq!(view.analyst_method.eps_cents.as_deref(), Some("1300"));
        assert_eq!(view.analyst_method.multiple_hundredths, Some(2_800));
        assert_eq!(
            view.analyst_method.forecast_period_end.as_deref(),
            Some("2028-12-31")
        );
        assert_eq!(view.analyst_method.target_as_of.as_deref(), Some("2027-12"));
        assert_eq!(
            view.analyst_method.date_precision.as_deref(),
            Some("month_label")
        );
        assert_eq!(
            view.analyst_method.source_verification.as_deref(),
            Some("source_not_verified")
        );
        assert_eq!(view.analyst_method.method_label, "manual analyst method");
        assert_eq!(
            view.analyst_method.multiple_provenance.as_deref(),
            Some("analyst_stated")
        );
        assert_eq!(
            view.analyst_method.scenario.as_deref(),
            Some("base_reference")
        );
        assert_eq!(
            view.analyst_method.share_basis_id.as_deref(),
            Some("share_basis:amzn-us:post-split-2022")
        );
        assert_eq!(
            view.analyst_method.lineage_group_id.as_deref(),
            Some("lineage:jpm-amzn-2026-07-31")
        );
        assert_eq!(
            view.analyst_method.eps_observation_id.as_deref(),
            Some("obs:fixture:eps:1")
        );
        assert_eq!(
            view.analyst_method.multiple_observation_id.as_deref(),
            Some("obs:fixture:pe:1")
        );
        assert_eq!(
            view.analyst_method.evidence_observed_at_unix_ms,
            Some(DECISION_AT)
        );
        assert_eq!(view.analyst_method.corporate_action_vintage, None);
        assert_eq!(view.analyst_method.fiscal_calendar_vintage, None);
        assert_eq!(
            view.analyst_method.fiscal_period_coordinate.as_deref(),
            Some("2028-01-01/2028-12-31")
        );
        assert_eq!(
            view.analyst_method.fiscal_calendar_verification.as_deref(),
            Some("not_captured")
        );
        assert!(!view.analyst_method.horizon_comparison_eligible);
        assert!(view.analyst_method.diagnostic_only);
        assert!(!view.analyst_method.ranking_eligible);
        assert!(!view.analyst_method.strong_eligible);

        let section = analyst_method_quant_section(&view.analyst_method).unwrap();
        assert_eq!(section.id, LANE_ANALYST_METHOD);
        assert_eq!(section.status, "Provisional");
        assert!(section.summary.contains("364.00"));
        assert!(section.summary.contains("diagnostic only"));
        assert!(section
            .metrics
            .iter()
            .any(|(k, v)| k == "ranking_eligible" && v == "false"));
        assert!(section
            .metrics
            .iter()
            .any(|(k, v)| k == "strong_eligible" && v == "false"));
        // Guard: identity still reconstructible after read.
        let _ = identity_vintage_fingerprint(&amzn);
    }

    #[test]
    fn dossier_unavailable_section_is_diagnostic_only() {
        let view = AnalystMethodCandidateView::unavailable(
            Some("run:x".into()),
            Some("proj:x".into()),
            "not_eligible_for_publication",
        );
        assert_eq!(view.status, AnalystMethodLaneStatus::Unavailable);
        assert!(!view.ranking_eligible);
        assert!(!view.strong_eligible);
        let section = analyst_method_quant_section(&view).unwrap();
        assert_eq!(section.id, LANE_ANALYST_METHOD);
        assert_eq!(section.status, "Unavailable");
        assert!(section.summary.contains("diagnostic only"));
        assert!(section
            .metrics
            .iter()
            .any(|(k, v)| k == "reason_code" && v == "not_eligible_for_publication"));
    }

    #[test]
    fn dossier_absent_after_projection_invalidation() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
        let proj = "proj:issuer:0001018724:sec:amzn-us:forward_earnings_multiple";
        db.invalidate_current_projection(proj, "policy_bump", None, DECISION_AT + 1)
            .unwrap();
        let view = load_valuation_dossier(&db, "AMZN").unwrap();
        assert_eq!(view.analyst_method.status, AnalystMethodLaneStatus::Absent);
        assert!(analyst_method_quant_section(&view.analyst_method).is_none());
    }

    #[test]
    fn projection_invalidation_remains_absent_after_file_backed_restart() {
        let path = temp_db_path("invalidated-restart");
        let projection_key = "proj:issuer:0001018724:sec:amzn-us:forward_earnings_multiple";
        {
            let db = Db::open(path.clone()).unwrap();
            let amzn = fixture_amzn_shaped();
            seed(&db, &amzn);
            commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
            db.invalidate_current_projection(
                projection_key,
                "trusted_revision_refused",
                Some("run:fixture:amzn-fem-1"),
                DECISION_AT + 1,
            )
            .unwrap();
        }
        {
            let reopened = Db::open(path.clone()).unwrap();
            let view = load_valuation_dossier(&reopened, "AMZN").unwrap();
            assert_eq!(view.analyst_method.status, AnalystMethodLaneStatus::Absent);
            assert!(analyst_method_quant_section(&view.analyst_method).is_none());
            assert_eq!(reopened.invalidation_count().unwrap(), 1);
        }
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn file_backed_publication_survives_restart_without_legacy_snapshot_write() {
        let path = temp_db_path("restart");
        {
            let db = Db::open(path.clone()).unwrap();
            let amzn = fixture_amzn_shaped();
            seed(&db, &amzn);
            assert_eq!(db.snapshot_count().unwrap(), 0);
            commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
            assert_eq!(db.snapshot_count().unwrap(), 0);
        }
        {
            let reopened = Db::open(path.clone()).unwrap();
            let view = load_valuation_dossier(&reopened, "AMZN").unwrap();
            assert_eq!(
                view.analyst_method.status,
                AnalystMethodLaneStatus::Available
            );
            assert_eq!(
                view.analyst_method.target_value_cents.as_deref(),
                Some("36400")
            );
            assert_eq!(reopened.snapshot_count().unwrap(), 0);
        }
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn second_ticker_vintage_refuses_instead_of_selecting_limit_one() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
        let mut newer = amzn.clone();
        newer.ticker_alias.identity_vintage = "identity:amzn:post-split-next".into();
        newer.ticker_alias.effective_from = "2028-01-01".into();
        seed(&db, &newer);

        let view = load_valuation_dossier(&db, "AMZN").unwrap();
        assert_eq!(
            view.analyst_method.status,
            AnalystMethodLaneStatus::Unavailable
        );
        assert_eq!(
            view.analyst_method.reason_code.as_deref(),
            Some("ambiguous_ticker_identity")
        );
    }

    #[test]
    fn second_share_basis_refuses_stale_pre_split_candidate() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
        let mut split = amzn.clone();
        split.share_basis.basis_id = "share_basis:amzn-us:future-split".into();
        split.share_basis.vintage_fingerprint = "sha256:future-split".into();
        split.share_basis.description = "future split basis".into();
        seed(&db, &split);

        let view = load_valuation_dossier(&db, "AMZN").unwrap();
        assert_eq!(
            view.analyst_method.status,
            AnalystMethodLaneStatus::Unavailable
        );
        assert_eq!(
            view.analyst_method.reason_code.as_deref(),
            Some("ambiguous_share_basis_vintage")
        );
    }

    #[test]
    fn corrupt_persisted_result_is_explicit_unavailable_after_restart() {
        let path = temp_db_path("corrupt");
        {
            let db = Db::open(path.clone()).unwrap();
            let amzn = fixture_amzn_shaped();
            seed(&db, &amzn);
            commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
            db.corrupt_analyst_result_for_test("run:fixture:amzn-fem-1")
                .unwrap();
        }
        {
            let reopened = Db::open(path.clone()).unwrap();
            let view = load_valuation_dossier(&reopened, "AMZN").unwrap();
            assert_eq!(
                view.analyst_method.status,
                AnalystMethodLaneStatus::Unavailable
            );
            assert_eq!(
                view.analyst_method.reason_code.as_deref(),
                Some("not_eligible_for_publication")
            );
        }
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn full_i64_money_is_json_exact_and_summary_uses_declared_currency() {
        let mut view = AnalystMethodCandidateView::unavailable(None, None, "fixture");
        view.status = AnalystMethodLaneStatus::Available;
        view.target_value_cents = Some(i64::MAX.to_string());
        view.eps_cents = Some("1300".into());
        view.multiple_hundredths = Some(2800);
        view.currency = Some("EUR".into());
        view.source_verification = Some("source_not_verified".into());
        let json = serde_json::to_string(&view).unwrap();
        assert!(json.contains("\"targetValueCents\":\"9223372036854775807\""));
        let section = analyst_method_quant_section(&view).unwrap();
        assert!(section.summary.contains("EUR 92233720368547758.07"));
        assert!(!section.summary.contains('$'));
    }

    #[test]
    fn command_shell_failure_helper_is_sanitized() {
        let view = publication_read_failure_dossier("amzn");
        assert_eq!(view.symbol, "AMZN");
        assert_eq!(
            view.analyst_method.status,
            AnalystMethodLaneStatus::Unavailable
        );
        assert_eq!(
            view.analyst_method.reason_code.as_deref(),
            Some("publication_read_failed")
        );
    }
}
