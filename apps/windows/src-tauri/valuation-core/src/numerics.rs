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

/// How far from the centre an observation may sit and still be read as one of
/// the sample rather than as something else that got into it.
///
/// Three standard deviations. Under anything close to normal that is about one
/// observation in 370, so on the ten-to-twenty-year histories this crate is fed
/// it should fire roughly never by chance — which is what makes it informative
/// when it does fire. It is a boundary between populations, not a tuning knob,
/// and it must not be lowered to make an estimate come out somewhere nicer.
pub const MAX_ABSOLUTE_Z: f64 = 3.0;

/// Scales a median absolute deviation to a standard deviation.
///
/// `1 / Phi^-1(0.75)`. For a normal sample the MAD converges to `0.6745 sigma`,
/// so this factor makes the robust scale read in the same units as the textbook
/// one and `MAX_ABSOLUTE_Z` mean the same thing under both.
const MAD_TO_DEVIATION: f64 = 1.482_602_218_505_602;

/// A sample expressed in units of its own dispersion.
///
/// # Why this exists
///
/// A plain average is the wrong summary for nearly every series in this codebase
/// and it fails silently, which is worse. It weights a small early year exactly
/// like a large recent one, and a single contaminated observation moves it by
/// `outlier / n` with no trace in the result. That failure mode has already cost
/// this project: an implied coupon of 5208% — a debt tag that caught a sliver of
/// the balance while the interest line covered all of it — inverted an entire
/// cross-sectional credit fit by itself.
///
/// Standardizing first makes the question answerable. A score says how far an
/// observation sits from the rest of its own sample in the sample's own units,
/// so "is this an observation or a category error" stops being a judgement call
/// and becomes a number that can be asserted on.
///
/// # Why not the textbook `(x - mean) / sd`
///
/// Because on the samples this crate is actually fed it cannot work, and the way
/// it fails is silent. The mean and the standard deviation are both computed
/// *from* the contaminated sample, so an outlier inflates the very scale it is
/// then measured against. The effect has a hard arithmetic ceiling: for `n`
/// observations no score can exceed `(n - 1) / sqrt(n)`, which is 2.85 at `n =
/// 10` and 3.10 at `n = 12`. Filed histories here run ten to nineteen years, so
/// a three-deviation rule built on the mean would be unable to flag anything at
/// all on the shorter ones — including, by construction, the single worst point.
///
/// The median and the median absolute deviation do not have that property. Half
/// the sample would have to be contaminated before either moved, so a bad point
/// is measured against a scale it had no part in setting, and the 5208% coupon
/// scores in the hundreds instead of hiding under three.
#[derive(Debug, Clone, PartialEq)]
pub struct Standardized {
    location: f64,
    scale: f64,
    scores: Vec<f64>,
}

impl Standardized {
    /// The sample median the scores are measured from.
    pub fn location(&self) -> f64 {
        self.location
    }

    /// The sample's median absolute deviation, scaled to read as a standard
    /// deviation, which is the unit the scores are in.
    pub fn scale(&self) -> f64 {
        self.scale
    }

    /// One score per input observation, in input order.
    pub fn scores(&self) -> &[f64] {
        &self.scores
    }

    /// Indices of the observations further than `max_absolute_z` from the centre.
    ///
    /// Returned rather than removed, because whether an outlier is contamination
    /// or the most important thing in the sample is the caller's question, not
    /// this function's.
    pub fn outliers(&self, max_absolute_z: f64) -> Vec<usize> {
        self.scores
            .iter()
            .enumerate()
            .filter(|(_, score)| score.abs() > max_absolute_z)
            .map(|(index, _)| index)
            .collect()
    }
}

/// The middle of a sample. Takes `&mut` because it must order the values, and
/// ordering a copy the caller cannot see is the more surprising contract.
fn median_of(sample: &mut [f64]) -> Option<f64> {
    if sample.is_empty() {
        return None;
    }
    sample.sort_by(|left, right| left.partial_cmp(right).expect("finite values"));
    let middle = sample.len() / 2;
    Some(if sample.len() % 2 == 0 {
        (sample[middle - 1] + sample[middle]) / 2.0
    } else {
        sample[middle]
    })
}

/// Express a sample in units of its own dispersion.
///
/// Refuses when the sample cannot support the question rather than returning a
/// number that looks like an answer:
///
/// * fewer than three observations — a centre and a spread are two quantities,
///   and two points cannot supply both and still leave anything to be measured;
/// * zero dispersion through the middle of the sample, which happens when more
///   than half the observations are identical. Then the bulk has no width, every
///   score is either zero or infinite, and the honest report is that this sample
///   has no scale rather than that everything unlike the mode is an outlier;
/// * any non-finite input, which would otherwise poison the whole sample.
pub fn standardize(sample: &[f64]) -> Result<Standardized, AbsenceReason> {
    if sample.len() < 3 {
        return Err(AbsenceReason::InsufficientObservations);
    }
    if sample.iter().any(|value| !value.is_finite()) {
        return Err(AbsenceReason::NotReported);
    }
    let location = median_of(&mut sample.to_vec()).expect("a non-empty sample");
    let mut deviations: Vec<f64> = sample
        .iter()
        .map(|value| (value - location).abs())
        .collect();
    let scale = median_of(&mut deviations).expect("a non-empty sample") * MAD_TO_DEVIATION;
    if !(scale > 0.0) || !scale.is_finite() {
        return Err(AbsenceReason::OutOfPolicyRange);
    }
    Ok(Standardized {
        location,
        scale,
        scores: sample
            .iter()
            .map(|value| (value - location) / scale)
            .collect(),
    })
}

/// The centre of a sample, computed only from the observations that belong to it.
///
/// The plain mean of everything the caller happened to collect is the amateur
/// summary this function exists to replace: it cannot tell a small year from a
/// contaminated one, and it reports the contamination as signal. Here the sample
/// states its own dispersion, anything beyond [`MAX_ABSOLUTE_Z`] of the centre
/// is dropped as belonging to a different population, and the centre is
/// recomputed from what is left.
///
/// One pass, deliberately. Iterating to a fixed point lets each removal tighten
/// the scale enough to justify the next removal, and a sample can be trimmed to
/// almost nothing that way — the estimate would then be a consequence of the
/// procedure rather than of the evidence.
///
/// Falls back to refusing, never to the untrimmed mean: if removing the outliers
/// leaves too little to speak with, the honest answer is that there is no
/// measurement here, not the number that included the contamination.
///
/// This is [`robust_centre`] with everything but the point estimate dropped. A
/// caller that wants the width, the kept count or the exclusions asks for the
/// centre itself rather than recomputing any of them.
pub fn robust_mean(sample: &[f64]) -> Result<f64, AbsenceReason> {
    robust_centre(sample).map(|centre| centre.centre())
}

/// A centre, the width of that centre, and the observations neither of them used.
///
/// The parts are returned together because they are only true together. A centre
/// computed from the observations that belong to the sample, paired with a width
/// computed from all of them, is a clean level wearing a contaminated precision —
/// and under inverse-variance weighting that is worse than not trimming at all,
/// because the dirtier the sample the wider the discarded tail, the larger the
/// width that was thrown away, and so the *tighter* the number that gets
/// reported. A caller cannot make that mistake with this type: there is no way
/// to obtain the centre without the width that belongs to it.
#[derive(Debug, Clone, PartialEq)]
pub struct RobustCentre {
    centre: f64,
    variance_of_centre: f64,
    retained: usize,
    outliers: Vec<usize>,
}

impl RobustCentre {
    /// The mean of the observations the sample's own dispersion admitted.
    pub fn centre(&self) -> f64 {
        self.centre
    }

    /// The squared standard error of [`centre`](Self::centre) — the width of the
    /// *centre*, not of the sample. It is named for what it is so that no caller
    /// reads it as a dispersion: the sample is wider than this by a factor of
    /// the kept count.
    ///
    /// It is computed over the retained observations only, from the same kept
    /// set that produced the centre. That is deliberate and it is what removes
    /// the monotone-in-contamination bias described on the type.
    ///
    /// **The residual bias, and its direction.** A retained sample is narrower
    /// than the population it was drawn from, so this is a mild *understatement*
    /// of how uncertain the centre is. Under inverse-variance fusion an
    /// understated variance is an overstated weight, so a channel carrying this
    /// width pulls slightly harder on a posterior than the evidence entitles it
    /// to. The alternative — a scale taken over the full sample — is rejected
    /// because it would describe a different estimator than the one that
    /// produced the point.
    pub fn variance_of_centre(&self) -> f64 {
        self.variance_of_centre
    }

    /// How many observations the centre and its width were computed from. This
    /// is what a caller reports as its sample size; reporting the raw input
    /// count for a width taken from a subset overstates precision again.
    pub fn retained(&self) -> usize {
        self.retained
    }

    /// How many observations the sample's own dispersion excluded.
    pub fn discarded(&self) -> usize {
        self.outliers.len()
    }

    /// Positions in the INPUT sample, in input order, of the observations the
    /// centre excluded. A caller that must drop the same observations from a
    /// downstream fit reads them here rather than deriving them a second time
    /// from a threshold it would then own a copy of.
    pub fn outliers(&self) -> &[usize] {
        &self.outliers
    }
}

/// The robust centre of a sample, at the standing threshold.
///
/// There is deliberately no threshold parameter. [`MAX_ABSOLUTE_Z`] is a
/// boundary between populations rather than a tuning knob, and a call site able
/// to pass 4.0 would be relaxing it without touching the constant — which is
/// exactly the move the constant exists to make reviewable.
pub fn robust_centre(sample: &[f64]) -> Result<RobustCentre, AbsenceReason> {
    trimmed(sample)
}

/// The one place in this workspace that acts on a z-score.
///
/// Both public entry points reach this function, so there is a single trimming
/// rule to review and a single refusal rule to reason about. It takes no
/// threshold parameter: [`MAX_ABSOLUTE_Z`] is a boundary between populations
/// rather than a tuning knob, and a caller able to pass a different value would
/// be relaxing it without touching the constant that exists to make that move
/// reviewable.
///
/// The scores come from [`standardize`], which already refuses the three samples
/// that cannot be trimmed at all — fewer than three observations, a non-finite
/// value, and a middle with no width. What is left for this function to refuse
/// is a sample that trimming empties: fewer than three survivors is no longer a
/// measurement, and reporting the untrimmed mean instead would be the
/// contaminated answer wearing a robust label.
fn trimmed(sample: &[f64]) -> Result<RobustCentre, AbsenceReason> {
    let outliers = standardize(sample)?.outliers(MAX_ABSOLUTE_Z);
    let kept: Vec<f64> = sample
        .iter()
        .enumerate()
        .filter(|(index, _)| !outliers.contains(index))
        .map(|(_, value)| *value)
        .collect();
    if kept.len() < 3 {
        return Err(AbsenceReason::InsufficientObservations);
    }

    let centre = kept.iter().sum::<f64>() / kept.len() as f64;
    let dispersion = kept
        .iter()
        .map(|value| (value - centre).powi(2))
        .sum::<f64>()
        / (kept.len() - 1) as f64;
    Ok(RobustCentre {
        centre,
        variance_of_centre: dispersion / kept.len() as f64,
        retained: kept.len(),
        outliers,
    })
}

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
        let summed = fading_path_series(amplitude, |order| ratio.powf(order)).expect("converges");
        assert!((summed - (amplitude * (1.0 - ratio)).exp()).abs() < 1e-9);
    }

    #[test]
    fn an_amplitude_beyond_the_conditioning_bound_refuses() {
        assert_eq!(
            fading_path_series(MAX_PATH_AMPLITUDE + 1.0, |_| 1.0),
            Err(AbsenceReason::OutOfPolicyRange)
        );
    }

    /// Nine ordinary years and one category error — the shape of the 5208%
    /// implied coupon that inverted the credit fit.
    #[cfg(test)]
    const CONTAMINATED: [f64; 10] = [9.0, 9.0, 10.0, 10.0, 10.0, 11.0, 11.0, 12.0, 12.0, 910.0];

    /// A centre and a spread are two quantities; two points cannot supply both
    /// and still leave anything to be measured.
    #[test]
    fn a_sample_too_small_to_have_a_spread_refuses() {
        assert_eq!(
            standardize(&[1.0, 9.0]),
            Err(AbsenceReason::InsufficientObservations)
        );
    }

    /// No observation is further from the centre than any other, so "how far
    /// out" is not a question this sample can answer.
    #[test]
    fn a_sample_with_no_dispersion_refuses_rather_than_dividing_by_zero() {
        assert_eq!(
            standardize(&[4.0, 4.0, 4.0, 4.0]),
            Err(AbsenceReason::OutOfPolicyRange)
        );
    }

    /// More than half the sample identical leaves the bulk with no width. Every
    /// score is then zero or infinite, and the honest report is that the sample
    /// has no scale — not that everything unlike the mode is an outlier.
    #[test]
    fn a_sample_whose_middle_has_no_width_refuses() {
        assert_eq!(
            standardize(&[7.0, 7.0, 7.0, 7.0, 7.0, 7.0, 1_000.0]),
            Err(AbsenceReason::OutOfPolicyRange)
        );
    }

    /// The scores are anchored on the median, so the middle observation of an
    /// odd sample scores exactly zero however skewed the rest of it is.
    #[test]
    fn the_centre_of_the_sample_scores_zero() {
        let standardized = standardize(&[1.0, 2.0, 3.0, 4.0, 5_000.0]).expect("a spread");
        assert_eq!(standardized.scores()[2], 0.0);
    }

    /// `[1, 2, 3, 4, 5000]`: median 3, absolute deviations `[2, 1, 0, 1, 4997]`
    /// whose median is 1, so the scale is the MAD consistency factor itself.
    /// Under mean and standard deviation the scale would read 2235.
    #[test]
    fn the_scale_is_the_middle_spread_not_the_one_the_outlier_inflates() {
        let standardized = standardize(&[1.0, 2.0, 3.0, 4.0, 5_000.0]).expect("a spread");
        assert!((standardized.scale() - MAD_TO_DEVIATION).abs() < 1e-12);
    }

    /// The ceiling that rules the textbook estimator out. With mean and standard
    /// deviation no score in a ten-point sample can exceed `9/sqrt(10) = 2.85`,
    /// so this point would hide under a three-deviation rule; measured against a
    /// scale it had no part in setting, it scores in the hundreds.
    #[test]
    fn a_contaminated_observation_cannot_hide_behind_the_scale_it_inflates() {
        let standardized = standardize(&CONTAMINATED).expect("a spread");
        assert!(standardized.scores()[9] > 100.0);
    }

    /// The failure this module exists to prevent: one contaminated observation
    /// carrying an estimate the rest of the sample contradicts. The clean years
    /// centre on 10.4; the plain mean of all ten is 100.4.
    #[test]
    fn a_contaminated_observation_does_not_move_the_robust_centre() {
        let centre = robust_mean(&CONTAMINATED).expect("a centre");
        assert!((centre - 94.0 / 9.0).abs() < 1e-12);
    }

    /// An outlier is reported, not removed: whether it is contamination or the
    /// most important fact in the sample is the caller's question.
    #[test]
    fn an_outlier_is_named_by_index_rather_than_silently_dropped() {
        assert_eq!(
            standardize(&CONTAMINATED)
                .expect("a spread")
                .outliers(MAX_ABSOLUTE_Z),
            vec![9]
        );
    }

    /// [`CONTAMINATED`] with the category error taken out: the sample the centre
    /// is supposed to describe, and the mean it is supposed to report.
    const CLEAN: [f64; 9] = [9.0, 9.0, 10.0, 10.0, 10.0, 11.0, 11.0, 12.0, 12.0];

    /// The whole claim, in one line: the centre of the contaminated sample is
    /// the centre of the clean one. Nine values summing to 94.
    #[test]
    fn the_robust_centre_reports_the_centre_of_what_it_kept() {
        assert!(
            (robust_centre(&CONTAMINATED).expect("a centre").centre() - 94.0 / 9.0).abs() < 1e-12
        );
    }

    #[test]
    fn the_contaminated_observation_is_counted_as_discarded() {
        assert_eq!(
            robust_centre(&CONTAMINATED).expect("a centre").discarded(),
            1
        );
    }

    /// Named by position in the input, so a caller excluding the same
    /// observation from a downstream fit reads it here instead of owning a
    /// second copy of the threshold.
    #[test]
    fn the_discarded_observation_is_named_by_its_position_in_the_input() {
        assert_eq!(
            robust_centre(&CONTAMINATED).expect("a centre").outliers(),
            [9]
        );
    }

    /// The width is the width of the KEPT sample: nine values with a variance of
    /// `23/18`, over the nine of them. The untrimmed standard error of the same
    /// input is about 8092 — some fifty thousand times wider — so a consumer
    /// weighting by inverse variance is reading a different quantity entirely
    /// depending on which of the two it gets.
    #[test]
    fn the_width_of_the_centre_is_the_width_of_what_the_centre_kept() {
        assert!(
            (robust_centre(&CONTAMINATED)
                .expect("a centre")
                .variance_of_centre()
                - 23.0 / 162.0)
                .abs()
                < 1e-12
        );
    }

    #[test]
    fn the_retained_count_is_what_survived_rather_than_what_arrived() {
        assert_eq!(
            robust_centre(&CONTAMINATED).expect("a centre").retained(),
            9
        );
    }

    /// Trimming is not something that happens to every sample. A sample with
    /// nothing out of place loses nothing.
    #[test]
    fn a_sample_with_nothing_out_of_place_discards_nothing() {
        assert_eq!(robust_centre(&CLEAN).expect("a centre").discarded(), 0);
    }

    /// And where nothing is discarded the robust centre is the plain mean, so
    /// this estimator only ever differs from the naive one on evidence that the
    /// sample itself says is contaminated.
    #[test]
    fn a_sample_with_nothing_out_of_place_centres_where_the_plain_mean_would() {
        assert!((robust_centre(&CLEAN).expect("a centre").centre() - 94.0 / 9.0).abs() < 1e-12);
    }

    /// One trimming implementation, asserted rather than promised: the mean is
    /// the centre with its width dropped, at the same threshold.
    #[test]
    fn the_robust_mean_is_the_robust_centre_with_everything_but_the_point_dropped() {
        assert_eq!(
            robust_mean(&CONTAMINATED),
            robust_centre(&CONTAMINATED).map(|centre| centre.centre())
        );
    }

    #[test]
    fn a_sample_too_small_to_have_a_spread_has_no_robust_centre() {
        assert_eq!(
            robust_centre(&[1.0, 9.0]),
            Err(AbsenceReason::InsufficientObservations)
        );
    }

    /// A non-finite observation is refused rather than propagated, so a NaN
    /// cannot leave here wearing the shape of a measurement.
    #[test]
    fn a_non_finite_observation_refuses_rather_than_poisoning_the_centre() {
        assert_eq!(
            robust_centre(&[1.0, 2.0, 3.0, f64::NAN]),
            Err(AbsenceReason::NotReported)
        );
    }

    /// The nearly-flat case. More than half the sample identical leaves the bulk
    /// with no width, and a centre reported from it would carry a width of zero,
    /// which under inverse-variance weighting is infinite confidence.
    #[test]
    fn a_sample_whose_middle_has_no_width_has_no_robust_centre() {
        assert_eq!(
            robust_centre(&[7.0, 7.0, 7.0, 7.0, 7.0, 7.0, 1_000.0]),
            Err(AbsenceReason::OutOfPolicyRange)
        );
    }

    /// The boundary of the refusal, from above: two of five excluded, three
    /// retained, and that is a measurement.
    #[test]
    fn a_sample_trimmed_exactly_to_three_still_reports_a_centre() {
        assert_eq!(
            robust_centre(&[10.0, 11.0, 12.0, 500.0, 600.0])
                .expect("a centre")
                .retained(),
            3
        );
    }

    /// And from below. Three observations, one of them a category error: two
    /// would survive, two is not a sample, so the answer is that there is no
    /// centre here — never the untrimmed mean of 337.
    #[test]
    fn a_sample_trimmed_below_three_refuses_rather_than_reporting_a_pair() {
        assert_eq!(
            robust_centre(&[10.0, 11.0, 1_000.0]),
            Err(AbsenceReason::InsufficientObservations)
        );
    }

    /// A retained count of one or two is unreachable above three observations,
    /// and this is the arithmetic reason rather than a defensive branch. The
    /// scale is the middle deviation, so on five observations only the two
    /// largest deviations can exceed any multiple of it — three always survive,
    /// whatever is done to the other two.
    #[test]
    fn no_five_observation_sample_can_be_trimmed_below_three() {
        let extremes = [
            [10.0, 11.0, 12.0, 1e6, 1e9],
            [10.0, 11.0, 12.0, -1e9, 1e9],
            [-1e9, 10.0, 11.0, 12.0, 1e9],
        ];
        assert!(extremes.iter().all(|sample| robust_centre(sample)
            .map(|centre| centre.retained())
            .unwrap_or(0)
            >= 3));
    }

    /// Trimming must not become a way to manufacture an estimate from a sample
    /// that no longer has one. Refusing beats reporting the untrimmed mean,
    /// which would be the contaminated answer wearing a robust label.
    #[test]
    fn trimming_below_a_usable_sample_refuses_rather_than_falling_back() {
        assert_eq!(
            robust_mean(&[1.0, 1.0, 1.0, f64::NAN]),
            Err(AbsenceReason::NotReported)
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
