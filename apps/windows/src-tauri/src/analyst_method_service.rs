//! Slice 1B / 1B.3 application service: control envelope → semantic admit → atomic lifecycle.
//!
//! No UI, ranking, providers, or FCFF. Publication blocked until independent 1B.3 closure.

use crate::analyst_method_import::{
    admit_observations_for_decision, canonical_command_sha256, canonical_projection_key,
    fem_result_json, parse_analyst_method_import_json, parse_control_envelope,
    METHOD_FORWARD_EARNINGS_MULTIPLE,
};
use crate::db::{is_deterministic_lifecycle_refusal, Db, RefusedAnalystMethodAttempt};
use crate::forward_earnings_multiple::{
    compute_forward_earnings_multiple, ForwardEarningsMultipleResult, ENGINE_ID,
    METHOD_POLICY_VERSION,
};
use crate::issuer_identity::identity_vintage_fingerprint;
use crate::issuer_identity::IdentityBundle;
use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnalystMethodCommitResult {
    pub run_id: String,
    pub evidence_set_fp: String,
    pub target_value_cents: i64,
    pub projection_key: Option<String>,
    pub invalidated_prior_run_id: Option<String>,
    pub idempotent_replay: bool,
}

struct TrustedSupersede {
    attempted_run_id: String,
    projection_key: String,
    prior_run_id: String,
    issuer_id: String,
    security_id: String,
}

/// Commit a typed analyst-method import against a seeded identity bundle.
///
/// Control-envelope admission is separated from semantic admission (1B.3 / EL-015):
/// once supersession authority is established, semantic refusal invalidates the prior
/// current candidate; malformed/foreign envelopes do not.
/// `created_at_unix_ms` is this processing attempt's clock. The semantic PIT decision instant is
/// the required `decisionAtUnixMs` inside the import and is part of command identity.
pub fn commit_analyst_method_import(
    db: &Db,
    import_json: &str,
    identity: &IdentityBundle,
    created_at_unix_ms: i64,
) -> Result<AnalystMethodCommitResult, String> {
    // --- Phase 1: control envelope (never invalidates) ---
    let envelope = parse_control_envelope(import_json)?;
    if envelope.issuer_id != identity.issuer.issuer_id {
        return Err("import_issuer_mismatch".into());
    }
    if envelope.security_id != identity.security.security_id {
        return Err("import_security_mismatch".into());
    }
    let identity_fp = identity_vintage_fingerprint(identity);
    let expected_proj = canonical_projection_key(
        &envelope.issuer_id,
        &envelope.security_id,
        METHOD_FORWARD_EARNINGS_MULTIPLE,
    );
    let projection_key = match envelope.projection_key.as_deref() {
        None => None,
        Some(k) if k == expected_proj => Some(expected_proj.clone()),
        Some(_) => return Err(format!("projection_key_not_canonical:{expected_proj}")),
    };

    let trusted_supersede = if let Some(prior) = envelope.supersedes_run_id.as_deref() {
        let key = projection_key
            .as_deref()
            .ok_or("supersedes_requires_projection_key")?;
        db.assert_supersession_authority(
            key,
            prior,
            &envelope.issuer_id,
            &envelope.security_id,
            METHOD_FORWARD_EARNINGS_MULTIPLE,
        )?;
        Some(TrustedSupersede {
            attempted_run_id: envelope.run_id.clone(),
            projection_key: key.to_string(),
            prior_run_id: prior.to_string(),
            issuer_id: envelope.issuer_id.clone(),
            security_id: envelope.security_id.clone(),
        })
    } else {
        None
    };

    // --- Phase 2: semantic admission ---
    let parsed = match parse_analyst_method_import_json(import_json) {
        Ok(p) => p,
        Err(sem_err) => {
            return finalize_semantic_refusal(
                db,
                trusted_supersede.as_ref(),
                import_json,
                None,
                identity,
                extract_decision_at(import_json),
                &sem_err,
                created_at_unix_ms,
            );
        }
    };
    if parsed.fem_input.currency != identity.security.currency {
        return finalize_semantic_refusal(
            db,
            trusted_supersede.as_ref(),
            import_json,
            Some(crate::valuation_evidence::replay_mode_snake(
                parsed.replay_mode,
            )),
            identity,
            Some(parsed.decision_at_unix_ms),
            "currency_mismatch_security",
            created_at_unix_ms,
        );
    }
    if parsed.eps_share_basis_id != identity.share_basis.basis_id {
        return finalize_semantic_refusal(
            db,
            trusted_supersede.as_ref(),
            import_json,
            Some(crate::valuation_evidence::replay_mode_snake(
                parsed.replay_mode,
            )),
            identity,
            Some(parsed.decision_at_unix_ms),
            "eps_share_basis_mismatch",
            created_at_unix_ms,
        );
    }

    if let Err(pit_err) = admit_observations_for_decision(
        &parsed.observations,
        parsed.replay_mode,
        parsed.decision_at_unix_ms,
    ) {
        return finalize_semantic_refusal(
            db,
            trusted_supersede.as_ref(),
            import_json,
            Some(crate::valuation_evidence::replay_mode_snake(
                parsed.replay_mode,
            )),
            identity,
            Some(parsed.decision_at_unix_ms),
            &pit_err,
            created_at_unix_ms,
        );
    }

    let fem = match compute_forward_earnings_multiple(&parsed.fem_input) {
        ForwardEarningsMultipleResult::Available(a) => a,
        ForwardEarningsMultipleResult::Unavailable { reason_code } => {
            return finalize_semantic_refusal(
                db,
                trusted_supersede.as_ref(),
                import_json,
                Some(crate::valuation_evidence::replay_mode_snake(
                    parsed.replay_mode,
                )),
                identity,
                Some(parsed.decision_at_unix_ms),
                &format!("fem_unavailable:{reason_code}"),
                created_at_unix_ms,
            );
        }
    };
    let result_json = fem_result_json(&fem, parsed.quality_label)?;

    let mut revision_groups: BTreeMap<(String, Option<String>), Vec<String>> = BTreeMap::new();
    for obs in &parsed.observations {
        revision_groups
            .entry((obs.revision_id.clone(), obs.supersedes.clone()))
            .or_default()
            .push(obs.id.clone());
    }
    let revision_groups: Vec<(String, Option<String>, Vec<String>)> = revision_groups
        .into_iter()
        .map(|((rev, sup), ids)| (rev, sup, ids))
        .collect();

    let outcome = db.commit_analyst_method_lifecycle(
        &parsed.observations,
        import_json,
        &parsed.canonical_command_sha256,
        parsed.decision_at_unix_ms,
        &parsed.run_id,
        METHOD_FORWARD_EARNINGS_MULTIPLE,
        ENGINE_ID,
        METHOD_POLICY_VERSION,
        &identity_fp,
        &parsed.issuer_id,
        &parsed.security_id,
        &identity.share_basis.basis_id,
        &parsed.eps_share_basis_id,
        &identity.ticker_alias.identity_vintage,
        &identity.ticker_alias.ticker,
        parsed.replay_mode,
        &result_json,
        created_at_unix_ms,
        projection_key.as_deref(),
        parsed.supersedes_run_id.as_deref(),
        &parsed.eps_observation_id,
        &parsed.multiple_observation_id,
        &revision_groups,
    );
    let outcome = match outcome {
        Ok(outcome) => outcome,
        Err(err) if is_deterministic_lifecycle_refusal(&err) => {
            return finalize_semantic_refusal(
                db,
                trusted_supersede.as_ref(),
                import_json,
                Some(crate::valuation_evidence::replay_mode_snake(
                    parsed.replay_mode,
                )),
                identity,
                Some(parsed.decision_at_unix_ms),
                &err,
                created_at_unix_ms,
            );
        }
        Err(err) => return Err(err),
    };

    Ok(AnalystMethodCommitResult {
        run_id: parsed.run_id,
        evidence_set_fp: outcome.evidence_set_fp,
        target_value_cents: fem.target_value_cents,
        projection_key,
        invalidated_prior_run_id: outcome.invalidated_prior_run_id,
        idempotent_replay: outcome.idempotent_replay,
    })
}

fn finalize_semantic_refusal(
    db: &Db,
    trusted: Option<&TrustedSupersede>,
    raw_command_json: &str,
    replay_mode: Option<&str>,
    identity: &IdentityBundle,
    decision_at_unix_ms: Option<i64>,
    reason: &str,
    processed_at_unix_ms: i64,
) -> Result<AnalystMethodCommitResult, String> {
    if let Some(t) = trusted {
        let reason_code = format!("refused_revision:{reason}");
        let identity_fp = identity_vintage_fingerprint(identity);
        let command_sha256 = canonical_command_sha256(raw_command_json).ok();
        db.refuse_superseding_revision(&RefusedAnalystMethodAttempt {
            attempted_run_id: &t.attempted_run_id,
            raw_command_json,
            canonical_command_sha256: command_sha256.as_deref(),
            decision_at_unix_ms,
            issuer_id: &t.issuer_id,
            security_id: &t.security_id,
            method: METHOD_FORWARD_EARNINGS_MULTIPLE,
            projection_key: &t.projection_key,
            supersedes_run_id: &t.prior_run_id,
            replay_mode,
            identity_fingerprint: Some(&identity_fp),
            share_basis_id: Some(&identity.share_basis.basis_id),
            identity_vintage: Some(&identity.ticker_alias.identity_vintage),
            ticker: Some(&identity.ticker_alias.ticker),
            reason_code: &reason_code,
            processed_at_unix_ms,
        })?;
        return Err(format!("refused_revision_invalidated:{reason}"));
    }
    Err(reason.into())
}

fn extract_decision_at(raw: &str) -> Option<i64> {
    serde_json::from_str::<serde_json::Value>(raw)
        .ok()?
        .get("decisionAtUnixMs")?
        .as_i64()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::Db;
    use crate::issuer_identity::{fixture_amzn_shaped, fixture_synthetic};
    use crate::valuation_evidence::evidence_set_fingerprint;
    use std::path::PathBuf;

    const DECISION_AT: i64 = 1_753_920_000_000;
    const PROJ: &str = "proj:issuer:0001018724:sec:amzn-us:forward_earnings_multiple";

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

    #[test]
    fn end_to_end_fixture_import_commits_three_sixty_four() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        let result =
            commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
        assert_eq!(result.target_value_cents, 36_400);
        assert!(!result.idempotent_replay);
        assert_eq!(db.observation_count().unwrap(), 2);
        assert_eq!(db.model_run_count().unwrap(), 1);
        assert_eq!(
            db.current_projection_run_id(PROJ).unwrap().as_deref(),
            Some("run:fixture:amzn-fem-1")
        );
        let roles = db.run_role_bindings("run:fixture:amzn-fem-1").unwrap();
        assert_eq!(roles.len(), 2);
        assert!(roles
            .iter()
            .any(|(r, id)| r == "forward_eps" && id == "obs:fixture:eps:1"));
        assert!(roles
            .iter()
            .any(|(r, id)| r == "forward_pe" && id == "obs:fixture:pe:1"));
        let membership = db
            .run_observation_membership("run:fixture:amzn-fem-1")
            .unwrap();
        let rebuilt = evidence_set_fingerprint(
            &membership
                .iter()
                .map(|(_, fp)| fp.clone())
                .collect::<Vec<_>>(),
        );
        assert_eq!(result.evidence_set_fp, rebuilt);
    }

    #[test]
    fn exact_retry_is_idempotent_noop() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        let first =
            commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
        let pretty_retry = serde_json::to_string_pretty(
            &serde_json::from_str::<serde_json::Value>(&fixture_import_json()).unwrap(),
        )
        .unwrap();
        let second =
            commit_analyst_method_import(&db, &pretty_retry, &amzn, DECISION_AT + 60_000).unwrap();
        assert!(second.idempotent_replay);
        assert_eq!(first.evidence_set_fp, second.evidence_set_fp);
        assert_eq!(db.model_run_count().unwrap(), 1);
        assert_eq!(
            db.model_run_created_at("run:fixture:amzn-fem-1").unwrap(),
            Some(DECISION_AT)
        );
    }

    #[test]
    fn decision_only_mutation_conflicts_same_run_identity() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();

        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["decisionAtUnixMs"] = serde_json::json!(DECISION_AT + 1);
        let err =
            commit_analyst_method_import(&db, &v.to_string(), &amzn, DECISION_AT + 10).unwrap_err();
        assert_eq!(err, "decision_at_mismatch");
        assert_eq!(db.model_run_count().unwrap(), 1);
        assert_eq!(
            db.current_projection_run_id(PROJ).unwrap().as_deref(),
            Some("run:fixture:amzn-fem-1")
        );
    }

    #[test]
    fn accepted_field_mutation_conflicts_even_when_fem_result_is_unchanged() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();

        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["fem"]["marketPriceCents"] = serde_json::json!(20_001);
        let err =
            commit_analyst_method_import(&db, &v.to_string(), &amzn, DECISION_AT + 10).unwrap_err();
        assert!(err.contains("run_id_content_conflict"), "{err}");
        assert_eq!(db.model_run_count().unwrap(), 1);
    }

    #[test]
    fn role_binding_mutation_conflicts_idempotency() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        let mut alt = v["observations"][0].clone();
        alt["id"] = serde_json::json!("obs:fixture:eps:alt");
        v["observations"].as_array_mut().unwrap().push(alt);

        // Both commands freeze exactly the same three observations. Only the EPS role pointer
        // changes, while numeric output and every other lifecycle coordinate remain identical.
        let first_json = v.to_string();
        let first = commit_analyst_method_import(&db, &first_json, &amzn, DECISION_AT).unwrap();
        v["fem"]["epsObservationId"] = serde_json::json!("obs:fixture:eps:alt");
        let second_json = v.to_string();
        let parsed_first = parse_analyst_method_import_json(&first_json).unwrap();
        let parsed_second = parse_analyst_method_import_json(&second_json).unwrap();
        assert_eq!(
            evidence_set_fingerprint(
                &parsed_first
                    .observations
                    .iter()
                    .map(|o| o.fingerprint_sha256())
                    .collect::<Vec<_>>()
            ),
            evidence_set_fingerprint(
                &parsed_second
                    .observations
                    .iter()
                    .map(|o| o.fingerprint_sha256())
                    .collect::<Vec<_>>()
            )
        );
        assert_eq!(first.target_value_cents, 36_400);
        let err = commit_analyst_method_import(&db, &second_json, &amzn, DECISION_AT).unwrap_err();
        assert!(err.contains("run_id_content_conflict"), "{err}");
    }

    #[test]
    fn semantic_refusal_on_trusted_supersede_invalidates() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
        assert_eq!(
            db.current_projection_run_id(PROJ).unwrap().as_deref(),
            Some("run:fixture:amzn-fem-1")
        );

        // Trusted supersede with revenue-as-EPS (semantic fail before service used to skip invalidate).
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["runId"] = serde_json::json!("run:fixture:amzn-fem-bad");
        v["supersedesRunId"] = serde_json::json!("run:fixture:amzn-fem-1");
        v["observations"][0]["id"] = serde_json::json!("obs:fixture:eps:bad");
        v["observations"][0]["metricId"] = serde_json::json!("revenue");
        v["fem"]["epsObservationId"] = serde_json::json!("obs:fixture:eps:bad");
        v["observations"][1]["id"] = serde_json::json!("obs:fixture:pe:bad");
        v["fem"]["multipleObservationId"] = serde_json::json!("obs:fixture:pe:bad");

        let err =
            commit_analyst_method_import(&db, &v.to_string(), &amzn, DECISION_AT + 1).unwrap_err();
        assert!(err.contains("refused_revision_invalidated"), "{err}");
        assert!(err.contains("eps_metric_not_earnings"), "{err}");
        assert_eq!(db.current_projection_run_id(PROJ).unwrap(), None);
        assert_eq!(db.model_run_count().unwrap(), 1);
        assert!(db.invalidation_count().unwrap() >= 1);
    }

    #[test]
    fn split_basis_mismatch_on_trusted_supersede_invalidates_stale_current() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();

        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["runId"] = serde_json::json!("run:fixture:amzn-fem-pre-split");
        v["supersedesRunId"] = serde_json::json!("run:fixture:amzn-fem-1");
        v["fem"]["epsShareBasisId"] = serde_json::json!("share_basis:amzn-us:pre-split-2022");
        let err =
            commit_analyst_method_import(&db, &v.to_string(), &amzn, DECISION_AT + 1).unwrap_err();
        assert!(err.contains("refused_revision_invalidated"), "{err}");
        assert!(err.contains("eps_share_basis_mismatch"), "{err}");
        assert_eq!(db.current_projection_run_id(PROJ).unwrap(), None);
        assert_eq!(db.model_run_count().unwrap(), 1);
    }

    #[test]
    fn malformed_semantic_fields_do_not_prevent_trusted_refusal_invalidation() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();

        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["runId"] = serde_json::json!("run:fixture:amzn-fem-malformed");
        v["supersedesRunId"] = serde_json::json!("run:fixture:amzn-fem-1");
        v["qualityLabel"] = serde_json::json!({ "not": "a quality label" });
        v["replayMode"] = serde_json::json!(42);

        let err =
            commit_analyst_method_import(&db, &v.to_string(), &amzn, DECISION_AT + 1).unwrap_err();
        assert!(err.contains("refused_revision_invalidated"), "{err}");
        assert!(err.contains("import_json_parse"), "{err}");
        assert_eq!(db.current_projection_run_id(PROJ).unwrap(), None);
        assert_eq!(db.import_command_attempt_count().unwrap(), 2);
    }

    #[test]
    fn deterministic_lifecycle_conflict_is_atomically_refused_and_invalidated() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();

        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        // Authority is valid, but reusing the prior run ID with supersession intent changes the
        // semantic lifecycle command and must be durably refused rather than leave stale current.
        v["supersedesRunId"] = serde_json::json!("run:fixture:amzn-fem-1");
        let err =
            commit_analyst_method_import(&db, &v.to_string(), &amzn, DECISION_AT + 1).unwrap_err();
        assert!(err.contains("refused_revision_invalidated"), "{err}");
        assert!(err.contains("run_id_content_conflict"), "{err}");
        assert_eq!(db.current_projection_run_id(PROJ).unwrap(), None);
        assert_eq!(db.model_run_count().unwrap(), 1);
        assert_eq!(db.import_command_attempt_count().unwrap(), 2);
    }

    #[test]
    fn foreign_envelope_does_not_invalidate() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["issuerId"] = serde_json::json!("issuer:foreign");
        v["supersedesRunId"] = serde_json::json!("run:fixture:amzn-fem-1");
        let err =
            commit_analyst_method_import(&db, &v.to_string(), &amzn, DECISION_AT).unwrap_err();
        assert!(err.contains("import_issuer_mismatch"), "{err}");
        assert_eq!(
            db.current_projection_run_id(PROJ).unwrap().as_deref(),
            Some("run:fixture:amzn-fem-1")
        );
        assert_eq!(db.invalidation_count().unwrap(), 0);
    }

    #[test]
    fn superseding_import_invalidates_prior_projection() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["runId"] = serde_json::json!("run:fixture:amzn-fem-2");
        v["supersedesRunId"] = serde_json::json!("run:fixture:amzn-fem-1");
        v["observations"][0]["id"] = serde_json::json!("obs:fixture:eps:2");
        v["fem"]["epsObservationId"] = serde_json::json!("obs:fixture:eps:2");
        v["observations"][1]["id"] = serde_json::json!("obs:fixture:pe:2");
        v["fem"]["multipleObservationId"] = serde_json::json!("obs:fixture:pe:2");
        let second =
            commit_analyst_method_import(&db, &v.to_string(), &amzn, DECISION_AT + 1).unwrap();
        assert_eq!(second.target_value_cents, 36_400);
        assert_eq!(
            db.current_projection_run_id(PROJ).unwrap().as_deref(),
            Some("run:fixture:amzn-fem-2")
        );
        assert_eq!(
            second.invalidated_prior_run_id.as_deref(),
            Some("run:fixture:amzn-fem-1")
        );
    }

    #[test]
    fn new_run_without_supersedes_cannot_overwrite_projection() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        seed(&db, &amzn);
        commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT).unwrap();
        let mut v: serde_json::Value = serde_json::from_str(&fixture_import_json()).unwrap();
        v["runId"] = serde_json::json!("run:fixture:amzn-fem-2");
        v["supersedesRunId"] = serde_json::Value::Null;
        v["observations"][0]["id"] = serde_json::json!("obs:fixture:eps:2");
        v["fem"]["epsObservationId"] = serde_json::json!("obs:fixture:eps:2");
        v["observations"][1]["id"] = serde_json::json!("obs:fixture:pe:2");
        v["fem"]["multipleObservationId"] = serde_json::json!("obs:fixture:pe:2");
        let err =
            commit_analyst_method_import(&db, &v.to_string(), &amzn, DECISION_AT + 1).unwrap_err();
        assert!(
            err.contains("projection_occupied_requires_supersedes"),
            "{err}"
        );
    }

    #[test]
    fn unseeded_identity_refuses_end_to_end() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        let err = commit_analyst_method_import(&db, &fixture_import_json(), &amzn, DECISION_AT)
            .unwrap_err();
        assert!(err.contains("issuer_not_seeded"), "{err}");
    }

    #[test]
    fn wrong_bundle_for_import_refuses() {
        let db = Db::open_in_memory().unwrap();
        let amzn = fixture_amzn_shaped();
        let syn = fixture_synthetic();
        seed(&db, &amzn);
        seed(&db, &syn);
        let err = commit_analyst_method_import(&db, &fixture_import_json(), &syn, DECISION_AT)
            .unwrap_err();
        assert!(err.contains("import_issuer_mismatch"), "{err}");
    }
}
