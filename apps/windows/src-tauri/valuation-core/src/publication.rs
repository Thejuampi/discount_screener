//! The Core's output boundary: an interval, its attribution, or a named refusal.
//!
//! # A distribution, not a number
//!
//! The Core returns percentiles (FR-33). A wide interval is an honest answer to
//! a hard name; a refusal is that same answer with the estimate thrown away and
//! the uncertainty deleted, which is strictly less information presented as
//! caution. The shipping engine refused nine issuers for `Disputed` — two lanes
//! disagreeing by more than 5000 bps — and in all nine the forward lane was the
//! closer one. The disagreement was information about width, and suppressing the
//! publication discarded it.
//!
//! So there are exactly two kinds of refusal (FR-32), and neither of them can be
//! reached by anything being uncertain:
//!
//! * **Eligibility** — this is not a thing we value. A fund, a trust, or text the
//!   taxonomy does not recognize.
//! * **Evidence** — a structurally required input was not measured. Not a *wide*
//!   input: an absent one.
//!
//! [`Refusal`] has no third variant, which is how "uncertainty is never a
//! refusal" is enforced rather than merely intended.
//!
//! # Why the interval is symmetric
//!
//! Propagation yields two moments — a value and a variance — and nothing about
//! the shape. Given exactly a mean and a variance on the real line, the normal
//! is the maximum-entropy distribution, so it is the assumption that adds least
//! beyond what was measured. It is genuinely an assumption: an equity value is
//! empirically right-skewed, and a lognormal would produce a tighter downside
//! and a fatter upside than these percentiles do. It is recorded here rather
//! than buried because the calibration gate can eventually test it against
//! realized outcomes.
//!
//! # The fixed-point boundary
//!
//! `f64` is how the Core computes and not how it publishes (FR-3). Quantization
//! to cents happens exactly once, here, half-up, on values that have already
//! finished being arithmetic. No `f64` appears on [`ValuationPosterior`].

use crate::attribution::{apportion_bps, Contribution, ValuationInput};
use crate::classification::BusinessClass;
use crate::evidence::{AbsenceReason, Observation, Provenance};
use crate::projection::Valuation;

/// Standard normal quantile at 95%. A mathematical constant, like `ln 2` — it
/// is what "the 5th and 95th percentile" means, not a tuned parameter.
const NORMAL_QUANTILE_95: f64 = 1.644_853_626_951_472_7;

/// Why no value was published.
///
/// Two variants, deliberately. There is no `TooUncertain`, no `Disputed`, and no
/// variant a threshold on interval width could construct.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Refusal {
    /// Not something this Core values. Carries the class that says so.
    Eligibility(BusinessClass),
    /// A structurally required input was absent. Carries which kind of absence.
    Evidence(AbsenceReason),
}

impl Refusal {
    pub fn kind(&self) -> &'static str {
        match self {
            Self::Eligibility(_) => "eligibility",
            Self::Evidence(_) => "evidence",
        }
    }

    pub fn detail(&self) -> &'static str {
        match self {
            Self::Eligibility(class) => class.as_str(),
            Self::Evidence(reason) => reason.as_str(),
        }
    }
}

/// One input's share of the published width.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AttributedShare {
    input: ValuationInput,
    share_bps: i32,
}

impl AttributedShare {
    pub fn input(self) -> ValuationInput {
        self.input
    }

    pub fn share_bps(self) -> i32 {
        self.share_bps
    }
}

/// What the Core returns for every issuer it is asked about.
///
/// Total by construction (FR-2): there is no `Result` and no `Option` around
/// this, and every failure is one of its own variants.
#[derive(Debug, Clone, PartialEq)]
pub enum ValuationPosterior {
    Published {
        percentile_05_cents: i64,
        median_cents: i64,
        percentile_95_cents: i64,
        attribution: Vec<AttributedShare>,
        provenance: Provenance,
    },
    Refused {
        refusal: Refusal,
        provenance: Provenance,
    },
}

impl ValuationPosterior {
    pub fn is_published(&self) -> bool {
        matches!(self, Self::Published { .. })
    }

    pub fn refusal(&self) -> Option<&Refusal> {
        match self {
            Self::Published { .. } => None,
            Self::Refused { refusal, .. } => Some(refusal),
        }
    }

    pub fn provenance(&self) -> &Provenance {
        match self {
            Self::Published { provenance, .. } | Self::Refused { provenance, .. } => provenance,
        }
    }
}

/// Divide an equity value across its shares and quantize the result.
///
/// ```text
/// per_share = equity_value / diluted_shares
/// ```
///
/// The division is arithmetic, but it is *valuation* arithmetic, so it lives
/// here rather than in the Shell (FR-5, FR-36). It also carries uncertainty: a
/// share count is a diluted estimate, and it widens the interval through the
/// same delta method as everything upstream.
///
/// Both models reach this point already holding an equity value — the operating
/// path through [`equity_value`](crate::projection::equity_value), the financial
/// path directly, since residual income on book has no debt to bridge.
pub fn publish(
    class: BusinessClass,
    equity_value: &Valuation,
    diluted_shares: &Observation<f64>,
) -> ValuationPosterior {
    let provenance = Provenance::new(
        "valuation_posterior",
        [
            equity_value.value().provenance(),
            diluted_shares.provenance(),
        ]
        .into_iter()
        .map(Provenance::observed_epoch_day)
        .max()
        .unwrap_or_default(),
    );
    let refuse = |refusal| ValuationPosterior::Refused {
        refusal,
        provenance: provenance.clone(),
    };

    if !class.routes_to_a_model() {
        return refuse(Refusal::Eligibility(class));
    }
    let Some(&equity) = equity_value.value().value() else {
        return refuse(Refusal::Evidence(
            equity_value
                .value()
                .absence_reason()
                .unwrap_or(AbsenceReason::NotReported),
        ));
    };
    let Some(&shares) = diluted_shares.value() else {
        return refuse(Refusal::Evidence(
            diluted_shares
                .absence_reason()
                .unwrap_or(AbsenceReason::NotReported),
        ));
    };
    // A share count is a divisor and a structural fact about the issuer. Zero or
    // negative is not a small number, it is a broken record.
    if !(shares.is_finite() && shares > 0.0) {
        return refuse(Refusal::Evidence(AbsenceReason::OutOfPolicyRange));
    }

    let per_share = equity / shares;

    // per_share = E/S, so d/dE = 1/S and d/dS = -E/S^2. Every upstream
    // contribution is a contribution to E, so all of them scale by the same 1/S;
    // the share count adds its own.
    let contributions: Vec<Contribution> = equity_value
        .contributions()
        .iter()
        .map(|contribution| contribution.scaled_by(1.0 / shares))
        .chain([Contribution::new(
            ValuationInput::DilutedShares,
            diluted_shares.propagation_variance() * (per_share / shares).powi(2),
        )])
        .collect();

    let variance: f64 = contributions.iter().copied().map(Contribution::variance).sum();
    if !per_share.is_finite() || !variance.is_finite() || variance <= 0.0 {
        return refuse(Refusal::Evidence(AbsenceReason::InsufficientObservations));
    }
    let half_width = NORMAL_QUANTILE_95 * variance.sqrt();

    let Some(percentiles) = quantize_all(&[
        per_share - half_width,
        per_share,
        per_share + half_width,
    ]) else {
        return refuse(Refusal::Evidence(AbsenceReason::OutOfPolicyRange));
    };

    let shares_of_variance: Vec<f64> = contributions.iter().copied().map(Contribution::variance).collect();
    let attribution = contributions
        .iter()
        .zip(apportion_bps(&shares_of_variance, variance))
        .map(|(contribution, share_bps)| AttributedShare {
            input: contribution.input(),
            share_bps,
        })
        .collect();

    ValuationPosterior::Published {
        percentile_05_cents: percentiles[0],
        median_cents: percentiles[1],
        percentile_95_cents: percentiles[2],
        attribution,
        provenance,
    }
}

/// Quantize to cents, half-up, once — or refuse the whole publication.
///
/// All three percentiles go through together so that a value outside the
/// representable range cannot publish a partial interval.
fn quantize_all(values: &[f64]) -> Option<Vec<i64>> {
    values.iter().map(|value| to_cents(*value)).collect()
}

fn to_cents(value: f64) -> Option<i64> {
    let cents = (value * 100.0 + 0.5).floor();
    (cents.is_finite() && cents.abs() < i64::MAX as f64).then_some(cents as i64)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::evidence::{Uncertainty, UncertaintyBasis};

    fn provenance() -> Provenance {
        Provenance::new("test", 20_000)
    }

    fn known(value: f64, variance: f64) -> Observation<f64> {
        Observation::measured(
            value,
            Uncertainty::from_variance(variance, UncertaintyBasis::SampleVariance { observations: 8 })
                .expect("valid variance"),
            provenance(),
        )
    }

    fn exact(value: f64) -> Observation<f64> {
        Observation::measured(
            value,
            Uncertainty::from_variance(
                f64::MIN_POSITIVE,
                UncertaintyBasis::SampleVariance { observations: 8 },
            )
            .expect("valid variance"),
            provenance(),
        )
    }

    fn equity(value: f64, variance: f64) -> Valuation {
        Valuation::new(
            known(value, variance),
            vec![Contribution::new(ValuationInput::BaseCashFlow, variance)],
        )
    }

    fn published(posterior: &ValuationPosterior) -> (i64, i64, i64) {
        match posterior {
            ValuationPosterior::Published {
                percentile_05_cents,
                median_cents,
                percentile_95_cents,
                ..
            } => (*percentile_05_cents, *median_cents, *percentile_95_cents),
            ValuationPosterior::Refused { refusal, .. } => {
                panic!("expected a published posterior, found {refusal:?}")
            }
        }
    }

    #[test]
    fn the_median_is_the_per_share_value_in_cents() {
        let posterior = publish(
            BusinessClass::OperatingNonFinancial,
            &equity(800.0, 100.0),
            &exact(8.0),
        );
        assert_eq!(published(&posterior).1, 10_000);
    }

    #[test]
    fn percentiles_are_ordered_by_construction() {
        let (low, median, high) = published(&publish(
            BusinessClass::OperatingNonFinancial,
            &equity(800.0, 100.0),
            &exact(8.0),
        ));
        assert!(low < median && median < high);
    }

    /// FR-33. Width is a function of measured uncertainty and nothing else, so
    /// raising an input's variance must widen the published interval.
    #[test]
    fn the_interval_widens_as_input_uncertainty_rises() {
        let width_at = |variance: f64| {
            let (low, _, high) = published(&publish(
                BusinessClass::OperatingNonFinancial,
                &equity(800.0, variance),
                &exact(8.0),
            ));
            high - low
        };
        assert!(width_at(400.0) > width_at(100.0));
    }

    #[test]
    fn attributed_shares_sum_to_exactly_one() {
        let posterior = publish(
            BusinessClass::OperatingNonFinancial,
            &equity(800.0, 100.0),
            &known(8.0, 0.04),
        );
        let ValuationPosterior::Published { attribution, .. } = posterior else {
            panic!("expected a published posterior");
        };
        assert_eq!(
            attribution
                .iter()
                .copied()
                .map(AttributedShare::share_bps)
                .sum::<i32>(),
            10_000
        );
    }

    /// A noisy share count is a real source of width and has to be visible as
    /// one, or the reader attributes it to the model instead.
    #[test]
    fn a_noisy_share_count_earns_a_share_of_the_width() {
        let posterior = publish(
            BusinessClass::OperatingNonFinancial,
            &equity(800.0, 100.0),
            &known(8.0, 1.0),
        );
        let ValuationPosterior::Published { attribution, .. } = posterior else {
            panic!("expected a published posterior");
        };
        let shares = attribution
            .iter()
            .find(|share| share.input() == ValuationInput::DilutedShares)
            .expect("share count is attributed");
        assert!(shares.share_bps() > 0);
    }

    #[test]
    fn an_ineligible_class_refuses_on_eligibility() {
        let posterior = publish(
            BusinessClass::NotEligible,
            &equity(800.0, 100.0),
            &exact(8.0),
        );
        assert_eq!(
            posterior.refusal(),
            Some(&Refusal::Eligibility(BusinessClass::NotEligible))
        );
    }

    #[test]
    fn an_unclassified_issuer_refuses_rather_than_publishing() {
        let posterior = publish(
            BusinessClass::Unclassified,
            &equity(800.0, 100.0),
            &exact(8.0),
        );
        assert_eq!(
            posterior.refusal(),
            Some(&Refusal::Eligibility(BusinessClass::Unclassified))
        );
    }

    #[test]
    fn an_absent_share_count_refuses_on_evidence() {
        let posterior = publish(
            BusinessClass::OperatingNonFinancial,
            &equity(800.0, 100.0),
            &Observation::absent(AbsenceReason::NotReported, provenance()),
        );
        assert_eq!(
            posterior.refusal(),
            Some(&Refusal::Evidence(AbsenceReason::NotReported))
        );
    }

    /// FR-32 as a structural claim rather than a behavioural one: there is no
    /// path from "this is very uncertain" to a refusal, because a hugely
    /// uncertain input still publishes.
    #[test]
    fn an_enormous_uncertainty_still_publishes() {
        let posterior = publish(
            BusinessClass::OperatingNonFinancial,
            &equity(800.0, 1e12),
            &exact(8.0),
        );
        assert!(posterior.is_published());
    }

    /// `1.125` is exactly `112.5` cents in binary, so this tests the rounding
    /// rule rather than the decimal literal's representation error — `1.005` is
    /// really `1.00499...` and would round down for a reason that has nothing to
    /// do with the rule.
    #[test]
    fn quantization_to_cents_is_half_up() {
        assert_eq!(to_cents(1.125), Some(113));
    }
}
