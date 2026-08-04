//! The arithmetic both valuation models share, and the bounds it is trusted in.
//!
//! # One series, two models
//!
//! Operating cash flow and residual income are different economics over the same
//! path. Both integrate something against `exp(-A exp(-k t))`, and expanding that
//! factor as its exponential series turns either integral into
//!
//! ```text
//! e^A * sum_{n >= 0} [ (-A)^n / n! ] * c(n)
//! ```
//!
//! where only `c(n)` differs — the models supply it and share everything else:
//! the alternating sum, its truncation rule, and the conditioning bound under
//! which the answer is worth publishing. Duplicating the loop per model would
//! make the two drift apart in exactly the way FR-4's reproducibility claim
//! cannot survive.

use crate::evidence::AbsenceReason;

/// Terms after which a series that has not converged is treated as unusable.
/// Reached only if the truncation test below never fires, which cannot happen
/// for `|A| <= MAX_PATH_AMPLITUDE`; it exists so the loop is provably finite.
const MAX_SERIES_TERMS: usize = 512;

/// Truncation point, relative to the largest term seen. Below double precision's
/// own noise floor, so truncation is never the binding error.
const SERIES_TRUNCATION_RATIO: f64 = 1e-16;

/// Conditioning bound on `A = (g_0 - g_inf)/k`, labelled arithmetic under FR-27.
///
/// The series alternates, and its largest term is of order `exp(2|A|)`, so the
/// relative rounding error of the sum is of order `exp(2|A|) * eps`. At `|A| =
/// 12` that is about `6e-6` — six digits, far inside the one-cent tolerance the
/// outlines assert. Beyond it the sum stops being trustworthy, so the model
/// refuses instead of publishing a number it cannot stand behind.
///
/// This is a numerical bound with a derivation, not an economic claim: `|A| = 12`
/// is `240` points of excess growth at a three-and-a-half-year half-life, which
/// no fitted cross-section produces.
pub(crate) const MAX_PATH_AMPLITUDE: f64 = 12.0;

/// Sum `e^A * sum_n [(-A)^n / n!] * coefficient(n)` over a fading path.
///
/// `amplitude` is `A`. `coefficient` receives the term order as a float, since
/// every caller uses it to build a rate denominator rather than to index.
///
/// Refuses rather than returning a number when `A` is outside the conditioning
/// bound, when the sum has not converged within [`MAX_SERIES_TERMS`], or when it
/// is not finite.
pub(crate) fn fading_path_series(
    amplitude: f64,
    coefficient: impl Fn(f64) -> f64,
) -> Result<f64, AbsenceReason> {
    if !amplitude.is_finite() || amplitude.abs() > MAX_PATH_AMPLITUDE {
        return Err(AbsenceReason::OutOfPolicyRange);
    }

    let mut term = amplitude.exp();
    let mut peak = term.abs();
    let mut sum = 0.0;
    for index in 0..MAX_SERIES_TERMS {
        let order = index as f64;
        sum += term * coefficient(order);
        term *= -amplitude / (order + 1.0);
        peak = peak.max(term.abs());
        // The terms grow until `n` passes `|A|`, so decay is only meaningful past
        // that point; testing earlier would truncate on the way up.
        if order > amplitude.abs() && term.abs() <= SERIES_TRUNCATION_RATIO * peak {
            return sum
                .is_finite()
                .then_some(sum)
                .ok_or(AbsenceReason::OutOfPolicyRange);
        }
    }
    Err(AbsenceReason::OutOfPolicyRange)
}

/// Central difference of a function that may refuse, with the refusal
/// propagating as a non-finite partial so the caller's single finiteness check
/// catches it.
pub(crate) fn central_difference(
    step: f64,
    evaluate: impl Fn(f64) -> Result<f64, AbsenceReason>,
) -> f64 {
    match (evaluate(step), evaluate(-step)) {
        (Ok(up), Ok(down)) => (up - down) / (2.0 * step),
        _ => f64::NAN,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// At `A = 0` the path is flat, every term past the first vanishes, and the
    /// sum is the leading coefficient alone.
    #[test]
    fn a_flat_path_sums_to_its_leading_coefficient() {
        let summed = fading_path_series(0.0, |order| 1.0 / (0.05 + order)).expect("converges");
        assert!((summed - 20.0).abs() < 1e-12);
    }

    /// `sum_n (-A)^n/n! * x^n = exp(-A x)`, so with `c(n) = x^n` the whole
    /// expression is `exp(A(1 - x))` in closed form. A slip in the recurrence or
    /// the `e^A` prefactor fails here without needing a valuation to notice it.
    #[test]
    fn the_series_reproduces_a_known_exponential() {
        let (amplitude, ratio): (f64, f64) = (3.0, 0.4);
        let summed =
            fading_path_series(amplitude, |order| ratio.powf(order)).expect("converges");
        assert!((summed - (amplitude * (1.0 - ratio)).exp()).abs() < 1e-9);
    }

    #[test]
    fn an_amplitude_beyond_the_conditioning_bound_refuses() {
        assert_eq!(
            fading_path_series(MAX_PATH_AMPLITUDE + 1.0, |_| 1.0),
            Err(AbsenceReason::OutOfPolicyRange)
        );
    }

    #[test]
    fn a_refusing_evaluation_yields_a_non_finite_partial() {
        assert!(central_difference(1.0, |step| if step > 0.0 {
            Ok(1.0)
        } else {
            Err(AbsenceReason::NotReported)
        })
        .is_nan());
    }
}
