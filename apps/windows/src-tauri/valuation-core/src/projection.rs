//! Continuous projection: the whole discounted cash-flow path, in closed form.
//!
//! # The seam this removes
//!
//! The shipping engine projects an explicit horizon and then bolts a terminal
//! value onto the end of it. The horizon is chosen by `derive_hold_years`, an
//! eight-branch tree, and `derive_fade_years`, which returns 5 or 10 — so two
//! issuers whose evidence differs by a hair can land on different branches and
//! be valued through different arithmetic. That discontinuity is not a modelling
//! choice anybody made; it is an artefact of having a horizon at all.
//!
//! Here growth decays continuously and value is the integral over all future
//! time. There is no horizon, no hold period, no fade period, and therefore no
//! seam between "explicit" and "terminal" cash flows — the terminal value is
//! just the limit of the same integrand (FR-18, FR-19).
//!
//! # The model
//!
//! Growth relaxes from its current level toward the terminal rate:
//!
//! ```text
//! g(t) = g_inf + (g_0 - g_inf) * exp(-k t)
//! ```
//!
//! Earnings compound at that instantaneous rate, so with `A = (g_0 - g_inf)/k`:
//!
//! ```text
//! E(t) = E_0 * exp( g_inf t + A (1 - exp(-k t)) )
//! ```
//!
//! Growth is charged for the capital it consumes (FR-28): sustaining growth `g`
//! at a return on capital `r` retains `g/r` of earnings, so the owner receives
//!
//! ```text
//! C(t) = E(t) * ( 1 - g(t)/r )
//! ```
//!
//! and the intrinsic value is `V = integral_0^inf C(t) exp(-w t) dt`.
//!
//! # Why this has a closed form
//!
//! Expanding `exp(-A exp(-k t))` as its exponential series turns the integrand
//! into a sum of pure exponentials, each of which integrates exactly:
//!
//! ```text
//! V / E_0 = e^A * sum_{n>=0} [ (-A)^n / n! ] *
//!               [ (1 - g_inf/r) / (a + n k)  -  ((g_0 - g_inf)/r) / (a + (n+1) k) ]
//! ```
//!
//! with `a = w - g_inf`. The series converges absolutely for every finite `A`, so
//! this is arithmetic rather than quadrature: no step size, no node count, and
//! nothing that could differ between platforms beyond `exp` itself (FR-4).
//!
//! [`unit_value_agrees_with_direct_numeric_integration`] checks the derivation
//! against a straightforward numerical integral of the defining integrand, so
//! the closed form is verified rather than asserted.
//!
//! # Where the constants went
//!
//! `PROJECTION_YEARS`, `PROJECTION_YEARS_SECULAR`, `SECULAR_GROWTH_FADE_EXPONENT`,
//! `derive_hold_years`, `derive_fade_years` and `through_cycle_business` have no
//! analogue here. The only quantity playing their role is the fade rate `k`, and
//! it arrives as evidence from the cross-section rather than from a branch on an
//! industry string.
//!
//! ## Why the fade rate is not measured per issuer
//!
//! The PRD specified `k = -ln(rho_1)` from each issuer's own growth
//! autocorrelation (FR-16). A probe over 28 names measured `rho_1` on realized
//! annual revenue growth: **median 0.003**, 14 of 28 at or below zero, median
//! `se(rho_1)` 0.25. There is no estimable per-issuer fade speed. What the probe
//! does *not* say is that growth has no persistence — `rho_1` was computed on
//! deviations from each firm's own mean, so it says those deviations are noise
//! and the persistent quantity is the **mean growth level**, which is exactly
//! what the growth posterior fuses. So `k` becomes a Cross-Section Prior: one
//! fitted relaxation speed for the universe, supplied by the Shell, in the same
//! way and for the same reason as the credit curve.

use crate::attribution::{Contribution, ValuationInput};
use crate::evidence::{AbsenceReason, Observation, Provenance, Uncertainty, UncertaintyBasis};

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
/// outlines assert. Beyond it the sum stops being trustworthy, so the projection
/// refuses instead of publishing a number it cannot stand behind.
///
/// This is a numerical bound with a derivation, not an economic claim: `|A| = 12`
/// is `240` points of excess growth at a three-and-a-half-year half-life, which
/// no fitted cross-section produces.
const MAX_PATH_AMPLITUDE: f64 = 12.0;

/// The market-frame half of the growth path: where growth is heading and how
/// fast it relaxes there.
///
/// Both quantities are frame-level, identical for every issuer in a run —
/// terminal growth from the term structure (FR-25), the fade rate from the
/// cross-section (FR-17). The issuer's own contribution is its current growth
/// level, which arrives separately as a fused [`Observation`] carrying its
/// measured width.
#[derive(Debug, Clone, PartialEq)]
pub struct GrowthPath {
    terminal_bps: f64,
    fade_per_year: f64,
    provenance: Provenance,
}

impl GrowthPath {
    /// Returns `None` when the supplied frame is not a fade: a non-positive
    /// relaxation speed would send growth *away* from the terminal rate, which
    /// is a failed fit rather than a path to project along — the same invariant
    /// posture as [`CreditCurve::fitted`](crate::capital::CreditCurve::fitted).
    pub fn fitted(terminal_bps: f64, fade_per_year: f64, provenance: Provenance) -> Option<Self> {
        (terminal_bps.is_finite() && fade_per_year.is_finite() && fade_per_year > 0.0).then_some(
            Self {
                terminal_bps,
                fade_per_year,
                provenance,
            },
        )
    }

    /// Instantaneous growth at `years` from now, in basis points.
    pub fn growth_bps_at(&self, initial_bps: f64, years: f64) -> f64 {
        self.terminal_bps
            + (initial_bps - self.terminal_bps) * (-self.fade_per_year * years).exp()
    }

    /// Years for half the gap to the terminal rate to close. Reported in
    /// provenance because it is the one horizon-shaped quantity in the model and
    /// a reader is entitled to see it (UJ-1).
    pub fn half_life_years(&self) -> f64 {
        std::f64::consts::LN_2 / self.fade_per_year
    }

    pub fn terminal_bps(&self) -> f64 {
        self.terminal_bps
    }

    pub fn provenance(&self) -> &Provenance {
        &self.provenance
    }
}

/// A value together with the inputs that made it uncertain.
///
/// The two travel together because publishing one without the other is what
/// makes a wide interval useless to a reader (FR-34). A refused valuation
/// carries no contributions, exactly as an unresolved fusion carries no weights.
#[derive(Debug, Clone, PartialEq)]
pub struct Valuation {
    value: Observation<f64>,
    contributions: Vec<Contribution>,
}

impl Valuation {
    /// Public because the operating path is not the only producer of a value —
    /// residual income on book is another, and both publish through the same
    /// boundary.
    pub fn new(value: Observation<f64>, contributions: Vec<Contribution>) -> Self {
        Self {
            value,
            contributions,
        }
    }

    pub fn value(&self) -> &Observation<f64> {
        &self.value
    }

    pub fn contributions(&self) -> &[Contribution] {
        &self.contributions
    }
}

/// Present value of the whole cash-flow path.
///
/// `base_cash_flow` is the owner cash flow at `t = 0` in whatever unit the caller
/// keeps; the result carries the same unit, since value is exactly linear in it.
///
/// Uncertainty in all four inputs reaches the result by the delta method
/// (FR-26). The partial with respect to the base is exact — value is linear in
/// it — and the three rate partials are central differences on the closed form,
/// stepped by a fraction of the distance to their own guard so that both sides
/// of every difference are inside the admissible region.
pub fn intrinsic_value(
    base_cash_flow: &Observation<f64>,
    growth_bps: &Observation<f64>,
    path: &GrowthPath,
    return_on_capital_bps: &Observation<f64>,
    discount_rate_bps: &Observation<f64>,
) -> Valuation {
    let provenance = Provenance::new(
        "intrinsic_value",
        [
            base_cash_flow.provenance(),
            growth_bps.provenance(),
            path.provenance(),
            return_on_capital_bps.provenance(),
            discount_rate_bps.provenance(),
        ]
        .into_iter()
        .map(Provenance::observed_epoch_day)
        .max()
        .unwrap_or_default(),
    );

    let refused = |reason| Valuation::new(Observation::absent(reason, provenance.clone()), vec![]);

    let (Some(&base), Some(&growth), Some(&discount)) = (
        base_cash_flow.value(),
        growth_bps.value(),
        discount_rate_bps.value(),
    ) else {
        return refused(AbsenceReason::NotReported);
    };
    if !base.is_finite() {
        return refused(AbsenceReason::OutOfPolicyRange);
    }

    // FR-29. An absent return on capital makes growth value-neutral, and value
    // neutrality is the identity `r = w`: at that return the retention charge
    // cancels the compounding exactly and the integral collapses to `E_0 / w`
    // for *any* growth path. Substituting the discount rate is therefore the
    // statement "we do not know whether this growth creates value", not a floor
    // on an observed return. An observed low return is used as observed.
    let return_on_capital = return_on_capital_bps.value().copied().unwrap_or(discount);

    let evaluate = |growth: f64, return_on_capital: f64, discount: f64| {
        unit_value(growth, path, return_on_capital, discount)
    };
    let Ok(unit) = evaluate(growth, return_on_capital, discount) else {
        return refused(AbsenceReason::OutOfPolicyRange);
    };
    let value = base * unit;
    if !value.is_finite() {
        return refused(AbsenceReason::OutOfPolicyRange);
    }

    // Steps are a quarter of the distance to each input's own guard, so a
    // perturbed evaluation never crosses one and no partial is one-sided.
    let discount_step = ((discount - path.terminal_bps) / 4.0).min(1.0);
    let return_step = (return_on_capital / 4.0).min(1.0);
    let partials = [
        (ValuationInput::BaseCashFlow, unit, base_cash_flow),
        (
            ValuationInput::Growth,
            base * central_difference(1.0, |step| {
                evaluate(growth + step, return_on_capital, discount)
            }),
            growth_bps,
        ),
        (
            ValuationInput::ReturnOnCapital,
            base * central_difference(return_step, |step| {
                evaluate(growth, return_on_capital + step, discount)
            }),
            return_on_capital_bps,
        ),
        (
            ValuationInput::DiscountRate,
            base * central_difference(discount_step, |step| {
                evaluate(growth, return_on_capital, discount + step)
            }),
            discount_rate_bps,
        ),
    ];
    if partials.iter().any(|(_, partial, _)| !partial.is_finite()) {
        return refused(AbsenceReason::OutOfPolicyRange);
    }

    let contributions: Vec<Contribution> = partials
        .iter()
        .map(|(input, partial, observation)| {
            Contribution::new(*input, observation.propagation_variance() * partial * partial)
        })
        .collect();
    let variance: f64 = contributions.iter().copied().map(Contribution::variance).sum();
    let inputs = partials
        .iter()
        .filter(|(.., observation)| observation.is_measured())
        .count() as u32;

    match Uncertainty::from_variance(variance, UncertaintyBasis::Propagated { inputs }) {
        Some(uncertainty) => Valuation::new(
            Observation::measured(value, uncertainty, provenance),
            contributions,
        ),
        // Every input arrived without usable width, so the value is a point with
        // no interval to publish. Fabricating a width would be worse than saying
        // so (FR-33 publishes an interval, not a bare number).
        None => refused(AbsenceReason::InsufficientObservations),
    }
}

/// Present value per unit of base cash flow, or the reason there is none.
fn unit_value(
    growth_bps: f64,
    path: &GrowthPath,
    return_on_capital_bps: f64,
    discount_bps: f64,
) -> Result<f64, AbsenceReason> {
    let discount = discount_bps / 10_000.0;
    let terminal = path.terminal_bps / 10_000.0;
    let growth = growth_bps / 10_000.0;
    let return_on_capital = return_on_capital_bps / 10_000.0;
    let fade = path.fade_per_year;

    if ![discount, terminal, growth, return_on_capital]
        .iter()
        .all(|quantity| quantity.is_finite())
    {
        return Err(AbsenceReason::OutOfPolicyRange);
    }

    // FR-27 arithmetic guard: the integral diverges unless terminal growth is
    // strictly below the discount rate. Named as arithmetic because it carries
    // no economic claim — it is the condition for the sum to exist at all.
    let gordon_denominator = discount - terminal;
    if gordon_denominator <= 0.0 {
        return Err(AbsenceReason::OutOfPolicyRange);
    }
    // FR-27 arithmetic guard: the retention charge divides by return on capital.
    if return_on_capital <= 0.0 {
        return Err(AbsenceReason::OutOfPolicyRange);
    }

    let terminal_payout = 1.0 - terminal / return_on_capital;
    let excess = growth - terminal;
    if excess == 0.0 {
        // Growth is already at the terminal rate, so the path is flat and the
        // series has a single term. Kept as its own branch because `A` is zero
        // and the general form would be arithmetically identical but harder to
        // read.
        return Ok(terminal_payout / gordon_denominator);
    }

    let amplitude = excess / fade;
    if amplitude.abs() > MAX_PATH_AMPLITUDE {
        return Err(AbsenceReason::OutOfPolicyRange);
    }
    let transient_payout = excess / return_on_capital;

    let mut term = amplitude.exp();
    let mut peak = term.abs();
    let mut sum = 0.0;
    let mut converged = false;
    for index in 0..MAX_SERIES_TERMS {
        let order = index as f64;
        sum += term
            * (terminal_payout / (gordon_denominator + order * fade)
                - transient_payout / (gordon_denominator + (order + 1.0) * fade));
        term *= -amplitude / (order + 1.0);
        peak = peak.max(term.abs());
        // The terms grow until `n` passes `|A|`, so decay is only meaningful
        // past that point; testing earlier would truncate on the way up.
        if order > amplitude.abs() && term.abs() <= SERIES_TRUNCATION_RATIO * peak {
            converged = true;
            break;
        }
    }
    if !converged || !sum.is_finite() {
        return Err(AbsenceReason::OutOfPolicyRange);
    }
    Ok(sum)
}

/// Central difference of a function that may refuse, with the refusal
/// propagating as a non-finite partial so the caller's single finiteness check
/// catches it.
fn central_difference(step: f64, evaluate: impl Fn(f64) -> Result<f64, AbsenceReason>) -> f64 {
    match (evaluate(step), evaluate(-step)) {
        (Ok(up), Ok(down)) => (up - down) / (2.0 * step),
        _ => f64::NAN,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn provenance() -> Provenance {
        Provenance::new("test", 20_000)
    }

    fn path() -> GrowthPath {
        GrowthPath::fitted(300.0, 0.20, provenance()).expect("a positive fade rate is a path")
    }

    fn known(value: f64) -> Observation<f64> {
        Observation::measured(
            value,
            Uncertainty::from_variance(0.01, UncertaintyBasis::SampleVariance { observations: 8 })
                .expect("valid variance"),
            provenance(),
        )
    }

    fn missing() -> Observation<f64> {
        Observation::absent(AbsenceReason::NotReported, provenance())
    }

    fn value_of(growth: f64, return_on_capital: &Observation<f64>, discount: f64) -> f64 {
        *intrinsic_value(
            &known(100.0),
            &known(growth),
            &path(),
            return_on_capital,
            &known(discount),
        )
        .value()
        .value()
        .expect("resolved intrinsic value")
    }

    #[test]
    fn a_path_that_does_not_relax_is_not_a_path() {
        assert_eq!(
            GrowthPath::fitted(300.0, 0.0, provenance()),
            None,
            "a zero fade rate never reaches the terminal rate"
        );
    }

    #[test]
    fn half_life_is_the_time_to_close_half_the_gap_to_terminal() {
        let path = path();
        let midpoint = path.growth_bps_at(1_500.0, path.half_life_years());
        assert!((midpoint - 900.0).abs() < 1e-9);
    }

    /// The derivation check. The closed form is a series expansion of an
    /// integral; this integrates that integrand directly and compares, so an
    /// algebra slip in the expansion fails here rather than silently shifting
    /// every valuation.
    #[test]
    fn unit_value_agrees_with_direct_numeric_integration() {
        let (growth, terminal, fade, return_on_capital, discount) =
            (0.15, 0.03, 0.20, 0.25, 0.08);
        let steps = 2_000_000;
        let horizon = 400.0;
        let width = horizon / steps as f64;
        let integrand = |years: f64| {
            let instant = terminal + (growth - terminal) * (-fade * years).exp();
            let earnings =
                (terminal * years + (growth - terminal) / fade * (1.0 - (-fade * years).exp())).exp();
            earnings * (1.0 - instant / return_on_capital) * (-discount * years).exp()
        };
        let integrated: f64 = (0..steps)
            .map(|step| integrand((step as f64 + 0.5) * width) * width)
            .sum();
        let closed_form = unit_value(1_500.0, &path(), 2_500.0, 800.0).expect("resolved");
        assert!(
            (closed_form - integrated).abs() < 1e-6 * integrated.abs(),
            "closed form {closed_form} disagrees with the integral {integrated}"
        );
    }

    /// FR-29, as an identity rather than an approximation: at a return on
    /// capital equal to the discount rate the retention charge exactly cancels
    /// the compounding, so the path stops mattering entirely.
    #[test]
    fn value_neutral_growth_collapses_to_earnings_over_the_discount_rate() {
        let resolved = value_of(1_500.0, &known(800.0), 800.0);
        assert!((resolved - 1_250.0).abs() < 1e-6);
    }

    #[test]
    fn an_absent_return_on_capital_is_value_neutral_rather_than_floored() {
        assert!((value_of(1_500.0, &missing(), 800.0) - 1_250.0).abs() < 1e-6);
    }

    /// The retention charge, as the ordering it exists to produce. Identical
    /// earnings and identical growth, different returns on capital, strictly
    /// ordered values.
    #[test]
    fn value_rises_monotonically_with_return_on_capital() {
        let returns: Vec<f64> = (4..=60).map(|step| step as f64 * 50.0).collect();
        let violations: Vec<f64> = returns
            .windows(2)
            .filter(|pair| value_of(1_500.0, &known(pair[1]), 800.0) <= value_of(1_500.0, &known(pair[0]), 800.0))
            .map(|pair| pair[0])
            .collect();
        assert!(
            violations.is_empty(),
            "value failed to rise above these returns on capital: {violations:?}"
        );
    }

    /// An observed return below terminal growth means growth consumes more
    /// capital than it earns, and the model says so. The old engine floored this
    /// at the cost of capital, which erased the difference between every issuer
    /// below it.
    #[test]
    fn an_observed_return_below_terminal_growth_is_not_floored() {
        assert!(value_of(300.0, &known(200.0), 800.0) < 0.0);
    }

    /// FR-18. The whole reason for a continuous path: no branch, no horizon, and
    /// therefore no jump anywhere in the fade rate.
    ///
    /// Continuity is tested by refining the step rather than by bounding the
    /// change, because the value is legitimately *steep* where growth barely
    /// fades — a fixed tolerance would confuse steepness with a seam. Across a
    /// jump the difference does not shrink when the step does; here shrinking
    /// the step tenfold must shrink the difference by roughly as much. The old
    /// engine's 5-vs-10-year switch would leave it unchanged.
    #[test]
    fn value_is_continuous_in_the_fade_rate() {
        let value_at = |fade: f64| {
            let path = GrowthPath::fitted(300.0, fade, provenance()).expect("positive fade");
            unit_value(1_500.0, &path, 2_500.0, 800.0).expect("resolved")
        };
        let seams: Vec<f64> = (1..=40)
            .map(|step| step as f64 / 20.0)
            .filter(|fade| {
                let coarse = (value_at(fade + 0.01) - value_at(fade - 0.01)).abs();
                let refined = (value_at(fade + 0.001) - value_at(fade - 0.001)).abs();
                refined > 0.2 * coarse
            })
            .collect();
        assert!(seams.is_empty(), "value jumped at these fade rates: {seams:?}");
    }

    #[test]
    fn terminal_growth_at_the_discount_rate_refuses_rather_than_dividing_by_zero() {
        let path = GrowthPath::fitted(800.0, 0.20, provenance()).expect("positive fade");
        let refused = intrinsic_value(
            &known(100.0),
            &known(1_500.0),
            &path,
            &known(2_500.0),
            &known(800.0),
        );
        assert_eq!(
            refused.value().absence_reason(),
            Some(AbsenceReason::OutOfPolicyRange)
        );
    }

    #[test]
    fn an_absent_base_cash_flow_refuses() {
        let refused = intrinsic_value(
            &missing(),
            &known(1_500.0),
            &path(),
            &known(2_500.0),
            &known(800.0),
        );
        assert_eq!(
            refused.value().absence_reason(),
            Some(AbsenceReason::NotReported)
        );
    }

    /// FR-34. The delta method is a sum of squared-partial terms, so the parts
    /// add up to the whole by construction rather than by reconciliation.
    #[test]
    fn contributions_sum_to_the_published_variance() {
        let valued = intrinsic_value(
            &known(100.0),
            &known(1_500.0),
            &path(),
            &known(2_500.0),
            &known(800.0),
        );
        let summed: f64 = valued.contributions().iter().copied().map(Contribution::variance).sum();
        let total = valued
            .value()
            .uncertainty()
            .map(Uncertainty::variance)
            .expect("resolved");
        assert!((summed - total).abs() < 1e-9 * total);
    }

    #[test]
    fn a_wider_growth_posterior_widens_the_value() {
        let vague = Observation::measured(
            1_500.0,
            Uncertainty::from_variance(
                10_000.0,
                UncertaintyBasis::AnalystDispersion { analysts: 4 },
            )
            .expect("valid variance"),
            provenance(),
        );
        let wide = intrinsic_value(&known(100.0), &vague, &path(), &known(2_500.0), &known(800.0));
        let tight = intrinsic_value(
            &known(100.0),
            &known(1_500.0),
            &path(),
            &known(2_500.0),
            &known(800.0),
        );
        assert!(
            wide.value().uncertainty().map(Uncertainty::variance)
                > tight.value().uncertainty().map(Uncertainty::variance)
        );
    }
}
