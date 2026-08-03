//! Source-continuity gate (SNDK-class).
//!
//! Pure comparison of SEC cash-flow history vs current Yahoo fundamentals.
//! Never reads market price or analyst targets. Fail-closed: missing Yahoo
//! cash yields [`ContinuityStatus::InsufficientEvidence`], not invented continuity.

use serde::{Deserialize, Serialize};

/// Versioned continuity policy identity. Cache fingerprints must include this.
pub const CONTINUITY_POLICY_VERSION: &str = "source-continuity/1";

/// Default policy thresholds (versioned; not per-ticker lists).
pub const DEFAULT_SCALE_RATIO_THRESHOLD: i64 = 5;
pub const DEFAULT_MATERIALITY_FLOOR_DOLLARS: i64 = 10_000_000;
/// Supporting signal only — never a sole hard year wall (no "must be ≥2025").
pub const DEFAULT_MIN_CONFIDENT_SERIES_LENGTH: u32 = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ContinuityStatus {
    Continuous,
    Discontinuous,
    InsufficientEvidence,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ContinuityReason {
    /// No SEC annual cash series at all.
    SecSeriesAbsent,
    /// SEC series shorter than the confident-length floor (supporting).
    SecSeriesShort,
    /// Latest SEC fiscal year lags policy as-of by more than one calendar year (supporting).
    SecFiscalLagSupporting,
    /// Absolute OCF scale differs beyond the versioned ratio threshold.
    ScaleMismatchOcf,
    /// Absolute FCF scale differs beyond the versioned ratio threshold.
    ScaleMismatchFcf,
    /// Sign of comparable cash metrics disagrees at material size.
    ScaleSignConflict,
    /// Optional CIK / entity identifiers disagree when both present.
    EntityCikMismatch,
    /// Yahoo OCF and FCF both missing — cannot verify continuity.
    YahooCashMissing,
    /// Comparable cash pair available and within scale thresholds.
    AlignedScale,
    /// SEC series present with a fiscal end observation.
    SecSeriesPresent,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceContinuityPolicy {
    pub version: String,
    /// Larger/smaller absolute cash ratio that marks scale discontinuity.
    pub scale_ratio_threshold: i64,
    /// Ignore scale comparison when both sides are below this absolute size.
    pub materiality_floor_dollars: i64,
    /// Supporting short-series flag when length is below this.
    pub min_confident_series_length: u32,
}

impl Default for SourceContinuityPolicy {
    fn default() -> Self {
        Self {
            version: CONTINUITY_POLICY_VERSION.into(),
            scale_ratio_threshold: DEFAULT_SCALE_RATIO_THRESHOLD,
            materiality_floor_dollars: DEFAULT_MATERIALITY_FLOOR_DOLLARS,
            min_confident_series_length: DEFAULT_MIN_CONFIDENT_SERIES_LENGTH,
        }
    }
}

/// Pure inputs — no price, target, or ticker-exception lists.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceContinuityEvidence {
    pub latest_sec_fiscal_year: Option<i32>,
    pub sec_series_length: u32,
    pub last_sec_ocf_dollars: Option<i64>,
    pub last_sec_fcf_dollars: Option<i64>,
    pub yahoo_ocf_dollars: Option<i64>,
    pub yahoo_fcf_dollars: Option<i64>,
    /// Optional entity continuity — set only when both providers expose CIK.
    pub sec_cik: Option<u64>,
    pub yahoo_cik: Option<u64>,
    /// Policy as-of day (epoch days). Used only for relative lag support signals.
    pub as_of_epoch_day: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceContinuityDecision {
    pub status: ContinuityStatus,
    pub reasons: Vec<ContinuityReason>,
    pub policy_version: String,
    pub fingerprint: String,
}

pub fn evaluate_source_continuity(
    evidence: &SourceContinuityEvidence,
    policy: &SourceContinuityPolicy,
) -> SourceContinuityDecision {
    let mut reasons = Vec::new();
    let as_of_year = epoch_day_year(evidence.as_of_epoch_day);

    if evidence.sec_series_length == 0 || evidence.latest_sec_fiscal_year.is_none() {
        reasons.push(ContinuityReason::SecSeriesAbsent);
        reasons.sort_unstable();
        reasons.dedup();
        return finish(
            ContinuityStatus::InsufficientEvidence,
            reasons,
            policy,
            evidence,
        );
    }
    reasons.push(ContinuityReason::SecSeriesPresent);

    if evidence.sec_series_length < policy.min_confident_series_length {
        reasons.push(ContinuityReason::SecSeriesShort);
    }

    if let Some(latest) = evidence.latest_sec_fiscal_year {
        // Relative lag only — no absolute year wall.
        if latest < as_of_year - 1 {
            reasons.push(ContinuityReason::SecFiscalLagSupporting);
        }
    }

    if let (Some(sec), Some(yahoo)) = (evidence.sec_cik, evidence.yahoo_cik) {
        if sec != yahoo {
            reasons.push(ContinuityReason::EntityCikMismatch);
            reasons.sort_unstable();
            reasons.dedup();
            return finish(ContinuityStatus::Discontinuous, reasons, policy, evidence);
        }
    }

    let yahoo_missing =
        evidence.yahoo_ocf_dollars.is_none() && evidence.yahoo_fcf_dollars.is_none();
    if yahoo_missing {
        reasons.push(ContinuityReason::YahooCashMissing);
        reasons.sort_unstable();
        reasons.dedup();
        return finish(
            ContinuityStatus::InsufficientEvidence,
            reasons,
            policy,
            evidence,
        );
    }

    let mut scale_hit = false;
    if let (Some(sec), Some(yahoo)) = (evidence.last_sec_ocf_dollars, evidence.yahoo_ocf_dollars) {
        match compare_cash_scale(sec, yahoo, policy) {
            ScaleCompare::Aligned => {}
            ScaleCompare::RatioMismatch => {
                reasons.push(ContinuityReason::ScaleMismatchOcf);
                scale_hit = true;
            }
            ScaleCompare::SignConflict => {
                reasons.push(ContinuityReason::ScaleSignConflict);
                reasons.push(ContinuityReason::ScaleMismatchOcf);
                scale_hit = true;
            }
        }
    }
    if let (Some(sec), Some(yahoo)) = (evidence.last_sec_fcf_dollars, evidence.yahoo_fcf_dollars) {
        match compare_cash_scale(sec, yahoo, policy) {
            ScaleCompare::Aligned => {}
            ScaleCompare::RatioMismatch => {
                reasons.push(ContinuityReason::ScaleMismatchFcf);
                scale_hit = true;
            }
            ScaleCompare::SignConflict => {
                reasons.push(ContinuityReason::ScaleSignConflict);
                reasons.push(ContinuityReason::ScaleMismatchFcf);
                scale_hit = true;
            }
        }
    }

    let comparable = evidence.last_sec_ocf_dollars.is_some()
        && evidence.yahoo_ocf_dollars.is_some()
        || evidence.last_sec_fcf_dollars.is_some() && evidence.yahoo_fcf_dollars.is_some();

    if !comparable {
        // Yahoo present but no overlapping metric pair with SEC.
        reasons.push(ContinuityReason::YahooCashMissing);
        reasons.sort_unstable();
        reasons.dedup();
        return finish(
            ContinuityStatus::InsufficientEvidence,
            reasons,
            policy,
            evidence,
        );
    }

    if scale_hit {
        reasons.sort_unstable();
        reasons.dedup();
        return finish(ContinuityStatus::Discontinuous, reasons, policy, evidence);
    }

    reasons.push(ContinuityReason::AlignedScale);
    reasons.sort_unstable();
    reasons.dedup();
    finish(ContinuityStatus::Continuous, reasons, policy, evidence)
}

/// True when the gate requires treating trailing SEC FCFF as structurally distorted.
pub fn emits_source_discontinuity(decision: &SourceContinuityDecision) -> bool {
    decision.status == ContinuityStatus::Discontinuous
}

fn finish(
    status: ContinuityStatus,
    reasons: Vec<ContinuityReason>,
    policy: &SourceContinuityPolicy,
    evidence: &SourceContinuityEvidence,
) -> SourceContinuityDecision {
    let fingerprint = continuity_fingerprint(status, &reasons, policy, evidence);
    SourceContinuityDecision {
        status,
        reasons,
        policy_version: policy.version.clone(),
        fingerprint,
    }
}

pub fn continuity_fingerprint(
    status: ContinuityStatus,
    reasons: &[ContinuityReason],
    policy: &SourceContinuityPolicy,
    evidence: &SourceContinuityEvidence,
) -> String {
    let reason_tokens = reasons
        .iter()
        .map(reason_token)
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "source-continuity/1|policy={}|status={}|reasons={}|sec_year={}|sec_len={}|sec_ocf={}|sec_fcf={}|yahoo_ocf={}|yahoo_fcf={}|ratio={}|floor={}",
        policy.version,
        status_token(status),
        reason_tokens,
        opt_i32(evidence.latest_sec_fiscal_year),
        evidence.sec_series_length,
        opt_i64(evidence.last_sec_ocf_dollars),
        opt_i64(evidence.last_sec_fcf_dollars),
        opt_i64(evidence.yahoo_ocf_dollars),
        opt_i64(evidence.yahoo_fcf_dollars),
        policy.scale_ratio_threshold,
        policy.materiality_floor_dollars,
    )
}

fn status_token(status: ContinuityStatus) -> &'static str {
    match status {
        ContinuityStatus::Continuous => "continuous",
        ContinuityStatus::Discontinuous => "discontinuous",
        ContinuityStatus::InsufficientEvidence => "insufficient_evidence",
    }
}

fn reason_token(reason: &ContinuityReason) -> &'static str {
    match reason {
        ContinuityReason::SecSeriesAbsent => "sec_series_absent",
        ContinuityReason::SecSeriesShort => "sec_series_short",
        ContinuityReason::SecFiscalLagSupporting => "sec_fiscal_lag_supporting",
        ContinuityReason::ScaleMismatchOcf => "scale_mismatch_ocf",
        ContinuityReason::ScaleMismatchFcf => "scale_mismatch_fcf",
        ContinuityReason::ScaleSignConflict => "scale_sign_conflict",
        ContinuityReason::EntityCikMismatch => "entity_cik_mismatch",
        ContinuityReason::YahooCashMissing => "yahoo_cash_missing",
        ContinuityReason::AlignedScale => "aligned_scale",
        ContinuityReason::SecSeriesPresent => "sec_series_present",
    }
}

fn opt_i32(value: Option<i32>) -> String {
    value.map_or_else(|| "-".into(), |v| v.to_string())
}

fn opt_i64(value: Option<i64>) -> String {
    value.map_or_else(|| "-".into(), |v| v.to_string())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ScaleCompare {
    Aligned,
    RatioMismatch,
    SignConflict,
}

fn compare_cash_scale(sec: i64, yahoo: i64, policy: &SourceContinuityPolicy) -> ScaleCompare {
    let floor = policy.materiality_floor_dollars;
    let a = sec.unsigned_abs();
    let b = yahoo.unsigned_abs();
    let a_i = a as i64;
    let b_i = b as i64;
    if a_i < floor && b_i < floor {
        return ScaleCompare::Aligned;
    }
    if sec != 0 && yahoo != 0 && sec.signum() != yahoo.signum() && a_i >= floor && b_i >= floor {
        return ScaleCompare::SignConflict;
    }
    let larger = a_i.max(b_i);
    let smaller = a_i.min(b_i).max(1);
    if larger / smaller >= policy.scale_ratio_threshold {
        ScaleCompare::RatioMismatch
    } else {
        ScaleCompare::Aligned
    }
}

fn epoch_day_year(epoch_day: i64) -> i32 {
    // Proleptic Gregorian from Unix epoch day without pulling chrono into pure tests path.
    // Algorithm: Howard Hinnant civil_from_days (public domain).
    let z = epoch_day + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if m <= 2 { y + 1 } else { y };
    year as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy() -> SourceContinuityPolicy {
        SourceContinuityPolicy::default()
    }

    /// SNDK-class: short stale SEC cash vs multi-billion Yahoo cash.
    fn sndk_evidence() -> SourceContinuityEvidence {
        SourceContinuityEvidence {
            latest_sec_fiscal_year: Some(2022),
            sec_series_length: 3,
            last_sec_ocf_dollars: Some(84_000_000),
            last_sec_fcf_dollars: Some(-120_000_000),
            yahoo_ocf_dollars: Some(4_640_000_000),
            yahoo_fcf_dollars: Some(2_260_000_000),
            sec_cik: None,
            yahoo_cik: None,
            // ~2026-07-01
            as_of_epoch_day: 20_665,
        }
    }

    /// Continuous control: multi-year SEC aligned with Yahoo cash (AAPL/T-class).
    fn continuous_evidence() -> SourceContinuityEvidence {
        SourceContinuityEvidence {
            latest_sec_fiscal_year: Some(2025),
            sec_series_length: 5,
            last_sec_ocf_dollars: Some(118_000_000_000),
            last_sec_fcf_dollars: Some(99_000_000_000),
            yahoo_ocf_dollars: Some(118_300_000_000),
            yahoo_fcf_dollars: Some(98_800_000_000),
            sec_cik: Some(320_193),
            yahoo_cik: Some(320_193),
            as_of_epoch_day: 20_665,
        }
    }

    #[test]
    fn sndk_class_is_discontinuous_with_scale_reasons() {
        let decision = evaluate_source_continuity(&sndk_evidence(), &policy());
        assert_eq!(decision.status, ContinuityStatus::Discontinuous);
        assert!(decision
            .reasons
            .contains(&ContinuityReason::ScaleMismatchOcf));
        assert!(decision
            .reasons
            .contains(&ContinuityReason::ScaleMismatchFcf));
        assert!(decision
            .reasons
            .contains(&ContinuityReason::ScaleSignConflict));
        assert!(decision.reasons.contains(&ContinuityReason::SecSeriesShort));
        assert!(decision
            .reasons
            .contains(&ContinuityReason::SecFiscalLagSupporting));
        assert!(emits_source_discontinuity(&decision));
        assert!(decision.fingerprint.contains(CONTINUITY_POLICY_VERSION));
        assert!(decision.fingerprint.contains("discontinuous"));
        assert_eq!(decision.policy_version, CONTINUITY_POLICY_VERSION);
    }

    #[test]
    fn continuous_control_does_not_force_discontinuity_from_calendar_alone() {
        let decision = evaluate_source_continuity(&continuous_evidence(), &policy());
        assert_eq!(decision.status, ContinuityStatus::Continuous);
        assert!(decision.reasons.contains(&ContinuityReason::AlignedScale));
        assert!(!decision
            .reasons
            .contains(&ContinuityReason::ScaleMismatchOcf));
        assert!(!decision
            .reasons
            .contains(&ContinuityReason::ScaleMismatchFcf));
        assert!(!emits_source_discontinuity(&decision));
    }

    #[test]
    fn calendar_lag_without_scale_mismatch_is_not_discontinuous() {
        let mut evidence = continuous_evidence();
        // SEC ends two years before as-of but cash still matches.
        evidence.latest_sec_fiscal_year = Some(2024);
        let decision = evaluate_source_continuity(&evidence, &policy());
        assert_eq!(decision.status, ContinuityStatus::Continuous);
        assert!(decision
            .reasons
            .contains(&ContinuityReason::SecFiscalLagSupporting));
        assert!(!emits_source_discontinuity(&decision));
    }

    #[test]
    fn missing_yahoo_cash_is_insufficient_evidence_not_invented_continuity() {
        let mut evidence = continuous_evidence();
        evidence.yahoo_ocf_dollars = None;
        evidence.yahoo_fcf_dollars = None;
        let decision = evaluate_source_continuity(&evidence, &policy());
        assert_eq!(decision.status, ContinuityStatus::InsufficientEvidence);
        assert!(decision
            .reasons
            .contains(&ContinuityReason::YahooCashMissing));
        assert!(!emits_source_discontinuity(&decision));
    }

    #[test]
    fn absent_sec_series_is_insufficient_evidence() {
        let evidence = SourceContinuityEvidence {
            latest_sec_fiscal_year: None,
            sec_series_length: 0,
            last_sec_ocf_dollars: None,
            last_sec_fcf_dollars: None,
            yahoo_ocf_dollars: Some(1_000_000_000),
            yahoo_fcf_dollars: Some(500_000_000),
            sec_cik: None,
            yahoo_cik: None,
            as_of_epoch_day: 20_665,
        };
        let decision = evaluate_source_continuity(&evidence, &policy());
        assert_eq!(decision.status, ContinuityStatus::InsufficientEvidence);
        assert!(decision
            .reasons
            .contains(&ContinuityReason::SecSeriesAbsent));
    }

    #[test]
    fn entity_cik_mismatch_is_discontinuous() {
        let mut evidence = continuous_evidence();
        evidence.yahoo_cik = Some(999_999);
        let decision = evaluate_source_continuity(&evidence, &policy());
        assert_eq!(decision.status, ContinuityStatus::Discontinuous);
        assert!(decision
            .reasons
            .contains(&ContinuityReason::EntityCikMismatch));
        assert!(emits_source_discontinuity(&decision));
    }

    #[test]
    fn scale_thresholds_are_policy_versioned_not_ticker_lists() {
        let mut loose = policy();
        loose.scale_ratio_threshold = 100;
        // SNDK ratio ~55 — under a 100× policy it is continuous on ratio,
        // but FCF sign conflict remains material.
        let decision = evaluate_source_continuity(&sndk_evidence(), &loose);
        assert_eq!(decision.status, ContinuityStatus::Discontinuous);
        assert!(decision
            .reasons
            .contains(&ContinuityReason::ScaleSignConflict));

        let mut evidence = sndk_evidence();
        evidence.last_sec_fcf_dollars = Some(120_000_000);
        evidence.yahoo_fcf_dollars = Some(200_000_000);
        // OCF still ~55× — continuous under loose policy with matching signs.
        let aligned_signs = evaluate_source_continuity(&evidence, &loose);
        assert_eq!(aligned_signs.status, ContinuityStatus::Continuous);
    }

    #[test]
    fn fingerprint_includes_policy_version_and_is_deterministic() {
        let a = evaluate_source_continuity(&sndk_evidence(), &policy());
        let b = evaluate_source_continuity(&sndk_evidence(), &policy());
        assert_eq!(a.fingerprint, b.fingerprint);
        assert!(a.fingerprint.contains("policy=source-continuity/1"));
        assert!(a.fingerprint.contains("status=discontinuous"));
    }
}
