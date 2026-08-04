//! Inverse-variance fusion of evidence channels.
//!
//! Two channels observe the same latent quantity with different noise. The
//! minimum-variance unbiased combination weights each by its precision:
//!
//! ```text
//! g_hat  = ( sum_i  g_i / var_i ) / ( sum_i 1 / var_i )
//! var_hat = 1 / ( sum_i 1 / var_i )
//! ```
//!
//! Two properties matter more than the formula.
//!
//! First, an [`Observation::Absent`](crate::evidence::Observation::Absent)
//! channel has precision exactly zero, so it contributes nothing and the
//! posterior falls back to whatever else is present, with no special case
//! anywhere in this module.
//!
//! Second, disagreement between channels does not suppress publication. It moves
//! the point estimate toward the tighter channel and leaves the posterior
//! variance unchanged — the width reflects how well the quantity is *known*, not
//! how much the sources argue. The old engine refused to publish when two lanes
//! disagreed by more than 5000 bps; that refusal is what this replaces, and the
//! `both-tight-disagree` row of the outline is exactly it.
//!
//! ## What this is not
//!
//! The estimator is minimum-variance only for *unbiased* channels. A trailing
//! channel is systematically stale and an analyst channel is systematically
//! optimistic, so neither is unbiased and the weighting is an approximation, not
//! an optimum. Tight analyst dispersion can also signal herding rather than
//! knowledge, in which case low variance means correlated bias and this
//! estimator will weight it *up*. Both are open calibration questions; neither is
//! resolved by this code, and both are why the residual-structure gate tests the
//! posterior against realized outcomes instead of trusting the arithmetic.

use crate::attribution::apportion_bps;
use crate::evidence::{AbsenceReason, Observation, Provenance, Uncertainty, UncertaintyBasis};

/// A fused estimate together with the weight each channel earned.
#[derive(Debug, Clone, PartialEq)]
pub struct Fusion {
    estimate: Observation<f64>,
    weights_bps: Vec<i32>,
}

impl Fusion {
    pub fn estimate(&self) -> &Observation<f64> {
        &self.estimate
    }

    /// Channel weights in basis points, in the order the channels were supplied.
    ///
    /// Apportioned by largest remainder so the reported weights sum to exactly
    /// 10000 whenever any channel is measured, and are all zero when none is.
    /// A reader comparing two rows of the outline is comparing shares that add
    /// up, which plain independent rounding would not guarantee.
    pub fn weights_bps(&self) -> &[i32] {
        &self.weights_bps
    }

    pub fn is_resolved(&self) -> bool {
        self.estimate.is_measured()
    }
}

/// Fuse evidence channels by inverse-variance weighting.
///
/// Total: never panics, never divides by zero. Absent channels weigh nothing;
/// when no channel is measured the result is itself absent, which is the only
/// refusal this function can produce.
pub fn fuse(channels: &[Observation<f64>]) -> Fusion {
    let precisions: Vec<f64> = channels.iter().map(Observation::precision).collect();
    let total_precision: f64 = precisions.iter().sum();

    if !(total_precision.is_finite() && total_precision > 0.0) {
        return Fusion {
            estimate: Observation::absent(
                AbsenceReason::InsufficientObservations,
                fused_provenance(channels),
            ),
            weights_bps: vec![0; channels.len()],
        };
    }

    let weighted_sum: f64 = channels
        .iter()
        .zip(&precisions)
        .filter_map(|(channel, precision)| channel.value().map(|value| value * precision))
        .sum();

    let estimate = weighted_sum / total_precision;
    let posterior_variance = 1.0 / total_precision;

    // `total_precision` is finite and positive, so both quotients are finite
    // unless a channel value was itself non-finite. Absence of a usable result
    // is reported as absence rather than as a NaN escaping into the model.
    let Some(uncertainty) = Uncertainty::from_variance(
        posterior_variance,
        UncertaintyBasis::SampleVariance {
            observations: measured_sample_size(channels),
        },
    )
    .filter(|_| estimate.is_finite()) else {
        return Fusion {
            estimate: Observation::absent(
                AbsenceReason::OutOfPolicyRange,
                fused_provenance(channels),
            ),
            weights_bps: vec![0; channels.len()],
        };
    };

    Fusion {
        estimate: Observation::measured(estimate, uncertainty, fused_provenance(channels)),
        weights_bps: apportion_bps(&precisions, total_precision),
    }
}

fn measured_sample_size(channels: &[Observation<f64>]) -> u32 {
    channels
        .iter()
        .filter_map(Observation::uncertainty)
        .map(|uncertainty| uncertainty.basis().sample_size())
        .sum()
}

/// Provenance of a fused value lists every contributing channel, so a posterior
/// can always be traced back to the sources that moved it.
fn fused_provenance(channels: &[Observation<f64>]) -> Provenance {
    let sources: Vec<&str> = channels
        .iter()
        .map(|channel| channel.provenance().source())
        .collect();
    let observed = channels
        .iter()
        .map(|channel| channel.provenance().observed_epoch_day())
        .max()
        .unwrap_or_default();
    Provenance::new(format!("fused[{}]", sources.join("+")), observed)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn trailing(value: f64, variance: f64) -> Observation<f64> {
        Observation::measured(
            value,
            Uncertainty::from_variance(variance, UncertaintyBasis::SampleVariance { observations: 5 })
                .expect("valid variance"),
            Provenance::new("trailing", 20_000),
        )
    }

    fn forward(value: f64, variance: f64) -> Observation<f64> {
        Observation::measured(
            value,
            Uncertainty::from_variance(
                variance,
                UncertaintyBasis::AnalystDispersion { analysts: 22 },
            )
            .expect("valid variance"),
            Provenance::new("forward", 20_010),
        )
    }

    fn missing(source: &str) -> Observation<f64> {
        Observation::absent(AbsenceReason::NotReported, Provenance::new(source, 20_000))
    }

    #[test]
    fn fusing_nothing_at_all_refuses_rather_than_returning_zero() {
        let fused = fuse(&[missing("trailing"), missing("forward")]);
        assert_eq!(
            fused.estimate().absence_reason(),
            Some(AbsenceReason::InsufficientObservations)
        );
    }

    #[test]
    fn an_absent_channel_leaves_the_survivor_untouched() {
        let fused = fuse(&[trailing(1_200.0, 4_000.0), missing("forward")]);
        assert_eq!(fused.estimate().value(), Some(&1_200.0));
    }

    #[test]
    fn an_absent_channel_earns_no_weight() {
        let fused = fuse(&[trailing(1_200.0, 4_000.0), missing("forward")]);
        assert_eq!(fused.weights_bps(), &[10_000, 0]);
    }

    #[test]
    fn disagreement_widens_nothing_and_still_resolves() {
        let agree = fuse(&[trailing(1_200.0, 4_000.0), forward(1_300.0, 2_250.0)]);
        let disagree = fuse(&[trailing(1_200.0, 4_000.0), forward(2_600.0, 2_250.0)]);
        assert_eq!(
            agree.estimate().uncertainty().map(Uncertainty::variance),
            disagree.estimate().uncertainty().map(Uncertainty::variance)
        );
    }

    #[test]
    fn reported_weights_sum_to_exactly_one_when_any_channel_is_measured() {
        let fused = fuse(&[trailing(1_200.0, 4_000.0), forward(2_600.0, 40_000.0)]);
        assert_eq!(fused.weights_bps().iter().sum::<i32>(), 10_000);
    }

    #[test]
    fn a_non_finite_channel_value_becomes_absence_not_a_nan() {
        let fused = fuse(&[trailing(f64::INFINITY, 4_000.0), missing("forward")]);
        assert_eq!(
            fused.estimate().absence_reason(),
            Some(AbsenceReason::OutOfPolicyRange)
        );
    }

    #[test]
    fn fused_provenance_names_every_contributing_channel() {
        let fused = fuse(&[trailing(1_200.0, 4_000.0), forward(1_300.0, 2_250.0)]);
        assert_eq!(
            fused.estimate().provenance().source(),
            "fused[trailing+forward]"
        );
    }
}
