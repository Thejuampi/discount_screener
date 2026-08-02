//! Forward earnings × multiple pure engine (Slice 1A).
//!
//! Market-reference lane only. Does not use subject market price or stated
//! target as inputs to the arithmetic. No FCFF router coupling.

use serde::{Deserialize, Serialize};

pub const ENGINE_ID: &str = "forward_earnings_multiple/1";
pub const METHOD_POLICY_VERSION: &str = "fem-policy-v1";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MultipleProvenance {
    AnalystStated,
    PeerPolicyDerived,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ForwardEarningsMultipleInput {
    pub issuer_id: String,
    pub security_id: Option<String>,
    pub metric_id: String,
    pub metric_basis: String,
    /// Diluted EPS in cents (e.g. $13.00 → 1300).
    pub eps_cents: i64,
    /// Multiple in hundredths (e.g. 28.00x → 2800).
    pub multiple_hundredths: i32,
    pub multiple_provenance: MultipleProvenance,
    pub forecast_period_end: String,
    pub target_as_of: String,
    pub date_precision: String,
    pub currency: String,
    pub evidence_observed_at_unix_ms: i64,
    /// Optional fields that must never affect the result (mutation-invariant).
    #[serde(default)]
    pub market_price_cents: Option<i64>,
    #[serde(default)]
    pub stated_target_cents: Option<i64>,
    #[serde(default)]
    pub peer_count: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ForwardEarningsMultipleAvailable {
    pub target_value_cents: i64,
    pub eps_cents: i64,
    pub multiple_hundredths: i32,
    pub engine_id: String,
    pub method_policy_version: String,
    pub multiple_provenance: MultipleProvenance,
    pub quality: String,
    pub forecast_period_end: String,
    pub target_as_of: String,
    pub date_precision: String,
    pub currency: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum ForwardEarningsMultipleResult {
    Available(ForwardEarningsMultipleAvailable),
    Unavailable { reason_code: String },
}

/// `target_value_cents = round_half_up(eps_cents × multiple_hundredths / 100)`.
pub fn compute_forward_earnings_multiple(
    input: &ForwardEarningsMultipleInput,
) -> ForwardEarningsMultipleResult {
    if input.issuer_id.trim().is_empty() {
        return refuse("empty_issuer_id");
    }
    if input.metric_id.trim().is_empty() {
        return refuse("missing_metric_id");
    }
    if input.currency.trim().is_empty() {
        return refuse("missing_currency");
    }
    if input.forecast_period_end.trim().is_empty() {
        return refuse("missing_forecast_period_end");
    }
    if input.target_as_of.trim().is_empty() {
        return refuse("missing_target_as_of");
    }
    if input.date_precision.trim().is_empty() {
        return refuse("missing_date_precision");
    }
    if input.eps_cents <= 0 {
        return refuse("non_positive_eps");
    }
    if input.multiple_hundredths <= 0 {
        return refuse("non_positive_multiple");
    }
    match input.multiple_provenance {
        MultipleProvenance::AnalystStated => {}
        MultipleProvenance::PeerPolicyDerived => {
            let peers = input.peer_count.unwrap_or(0);
            if peers == 0 {
                return refuse("unsupported_provenance");
            }
            // Peer policy path is not fully implemented in Slice 1A.
            return refuse("peer_policy_not_implemented");
        }
    }

    // product / 100 with half-up; market_price / stated_target intentionally unused.
    let product = match (input.eps_cents as i128).checked_mul(input.multiple_hundredths as i128) {
        Some(p) => p,
        None => return refuse("overflow"),
    };
    let target = match div_round_half_up_i128(product, 100) {
        Some(v) if v >= i64::MIN as i128 && v <= i64::MAX as i128 => v as i64,
        Some(_) => return refuse("overflow"),
        None => return refuse("overflow"),
    };

    ForwardEarningsMultipleResult::Available(ForwardEarningsMultipleAvailable {
        target_value_cents: target,
        eps_cents: input.eps_cents,
        multiple_hundredths: input.multiple_hundredths,
        engine_id: ENGINE_ID.into(),
        method_policy_version: METHOD_POLICY_VERSION.into(),
        multiple_provenance: input.multiple_provenance,
        quality: "provisional".into(),
        forecast_period_end: input.forecast_period_end.clone(),
        target_as_of: input.target_as_of.clone(),
        date_precision: input.date_precision.clone(),
        currency: input.currency.clone(),
    })
}

fn refuse(code: &str) -> ForwardEarningsMultipleResult {
    ForwardEarningsMultipleResult::Unavailable {
        reason_code: code.into(),
    }
}

fn div_round_half_up_i128(numerator: i128, denominator: i128) -> Option<i128> {
    if denominator == 0 {
        return None;
    }
    let half = denominator / 2;
    if numerator >= 0 {
        Some((numerator + half) / denominator)
    } else {
        Some((numerator - half) / denominator)
    }
}

fn fixture_transcription_input() -> ForwardEarningsMultipleInput {
    ForwardEarningsMultipleInput {
        issuer_id: "issuer:0001018724".into(),
        security_id: Some("sec:amzn-us".into()),
        metric_id: "gaap_diluted_eps".into(),
        metric_basis: "reported_gaap".into(),
        eps_cents: 1300,
        multiple_hundredths: 2800,
        multiple_provenance: MultipleProvenance::AnalystStated,
        forecast_period_end: "2028-12-31".into(),
        target_as_of: "2027-12".into(),
        date_precision: "month_label".into(),
        currency: "USD".into(),
        evidence_observed_at_unix_ms: 1_753_920_000_000,
        market_price_cents: Some(20_000),
        stated_target_cents: Some(36_500),
        peer_count: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixture_transcription_thirteen_times_twenty_eight_is_three_sixty_four() {
        match compute_forward_earnings_multiple(&fixture_transcription_input()) {
            ForwardEarningsMultipleResult::Available(a) => {
                assert_eq!(a.target_value_cents, 36_400);
            }
            other => panic!("expected available, got {other:?}"),
        }
    }

    #[test]
    fn synthetic_issuer_same_arithmetic() {
        let mut input = fixture_transcription_input();
        input.issuer_id = "issuer:0000999999".into();
        input.security_id = Some("sec:syn-us".into());
        match compute_forward_earnings_multiple(&input) {
            ForwardEarningsMultipleResult::Available(a) => {
                assert_eq!(a.target_value_cents, 36_400);
            }
            other => panic!("expected available, got {other:?}"),
        }
    }

    #[test]
    fn market_price_and_stated_target_do_not_affect_result() {
        let mut a = fixture_transcription_input();
        a.market_price_cents = Some(1);
        a.stated_target_cents = Some(99_999);
        let mut b = fixture_transcription_input();
        b.market_price_cents = Some(9_999_999);
        b.stated_target_cents = Some(1);
        assert_eq!(
            compute_forward_earnings_multiple(&a),
            compute_forward_earnings_multiple(&b)
        );
    }

    #[test]
    fn zero_eps_refuses() {
        let mut input = fixture_transcription_input();
        input.eps_cents = 0;
        match compute_forward_earnings_multiple(&input) {
            ForwardEarningsMultipleResult::Unavailable { reason_code } => {
                assert_eq!(reason_code, "non_positive_eps");
            }
            other => panic!("expected refuse, got {other:?}"),
        }
    }

    #[test]
    fn non_positive_multiple_refuses() {
        let mut input = fixture_transcription_input();
        input.multiple_hundredths = 0;
        match compute_forward_earnings_multiple(&input) {
            ForwardEarningsMultipleResult::Unavailable { reason_code } => {
                assert_eq!(reason_code, "non_positive_multiple");
            }
            other => panic!("expected refuse, got {other:?}"),
        }
    }

    #[test]
    fn missing_metric_refuses() {
        let mut input = fixture_transcription_input();
        input.metric_id = "  ".into();
        match compute_forward_earnings_multiple(&input) {
            ForwardEarningsMultipleResult::Unavailable { reason_code } => {
                assert_eq!(reason_code, "missing_metric_id");
            }
            other => panic!("expected refuse, got {other:?}"),
        }
    }

    #[test]
    fn peer_policy_with_zero_peers_refuses() {
        let mut input = fixture_transcription_input();
        input.multiple_provenance = MultipleProvenance::PeerPolicyDerived;
        input.peer_count = Some(0);
        match compute_forward_earnings_multiple(&input) {
            ForwardEarningsMultipleResult::Unavailable { reason_code } => {
                assert_eq!(reason_code, "unsupported_provenance");
            }
            other => panic!("expected refuse, got {other:?}"),
        }
    }

    #[test]
    fn missing_horizon_fields_refuse() {
        let mut input = fixture_transcription_input();
        input.forecast_period_end = "".into();
        match compute_forward_earnings_multiple(&input) {
            ForwardEarningsMultipleResult::Unavailable { reason_code } => {
                assert_eq!(reason_code, "missing_forecast_period_end");
            }
            other => panic!("expected refuse, got {other:?}"),
        }
    }

    #[test]
    fn half_up_rounding_example() {
        // 1.005 * 100 / 100 would be odd; use 150 cents * 333 hundredths / 100
        // 150 * 333 = 49950 / 100 = 499.5 → 500 half-up
        let mut input = fixture_transcription_input();
        input.eps_cents = 150;
        input.multiple_hundredths = 333;
        match compute_forward_earnings_multiple(&input) {
            ForwardEarningsMultipleResult::Available(a) => {
                assert_eq!(a.target_value_cents, 500);
            }
            other => panic!("expected available, got {other:?}"),
        }
    }

    #[test]
    fn extreme_i64_max_times_one_hundred_uses_wide_intermediate() {
        // Product exceeds i64; i128/BigInteger intermediate must yield i64::MAX, not refuse.
        let mut input = fixture_transcription_input();
        input.eps_cents = i64::MAX;
        input.multiple_hundredths = 100;
        match compute_forward_earnings_multiple(&input) {
            ForwardEarningsMultipleResult::Available(a) => {
                assert_eq!(a.target_value_cents, i64::MAX);
            }
            other => panic!("expected available, got {other:?}"),
        }
    }

    #[test]
    fn extreme_overflow_result_refuses() {
        let mut input = fixture_transcription_input();
        input.eps_cents = i64::MAX;
        input.multiple_hundredths = 200;
        match compute_forward_earnings_multiple(&input) {
            ForwardEarningsMultipleResult::Unavailable { reason_code } => {
                assert_eq!(reason_code, "overflow");
            }
            other => panic!("expected overflow refuse, got {other:?}"),
        }
    }
}
