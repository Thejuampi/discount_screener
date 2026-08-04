//! Cost of capital that responds to capital structure.
//!
//! # The defect this exists to remove
//!
//! The shipping engine resolves cost of debt as `interest_t / avg_debt_t` — a
//! trailing accounting coupon on debt issued years ago, which rises with
//! nothing. Because the debt term therefore never grows, WACC is *strictly
//! decreasing* in leverage: in the diagnostic cohort every net-cash name lands
//! exactly on the unit-beta cost of equity (925 bps) while levered names are
//! dragged below it — CHTR at 4.4x leverage gets 493 bps, T gets 680. The model
//! asserts a firm can lower its cost of capital without limit by borrowing.
//!
//! That is an internal coherence failure, provable without ever looking at a
//! market price.
//!
//! # Why a fitted curve, and why it arrives as an argument
//!
//! No leverage-responsive spread can be derived from a single issuer's own
//! accounting history — the observable is one stale coupon. It needs a
//! cross-section: fit the spread that lenders actually charge against leverage
//! and coverage across a universe, then place each issuer on the fitted curve.
//!
//! The fit is a two-pass computation over many issuers, which is Shell work. The
//! Core stays pure by taking the fitted [`CreditCurve`] as evidence like any
//! other input. A hard-coded ratings table would have been the alternative, and
//! it is exactly the "constant that works today and breaks in two years" this
//! redesign exists to eliminate.
//!
//! # Monotonicity is a type invariant, not a check
//!
//! FR-20 requires `d(r_d)/d(leverage) > 0`. A fit that came out flat or
//! inverted is a *failed fit*, not a curve to be used with a warning, so
//! [`CreditCurve::fitted`] refuses to construct one. The `non-monotone-fit` row
//! of the outline is that refusal.
//!
//! # WACC is U-shaped, not monotone
//!
//! Adding cheap tax-shielded debt does lower WACC at first — that is
//! Modigliani-Miller with taxes and it is real. What the old engine lacked was
//! the other side: as leverage rises the spread rises too, and WACC turns back
//! up. The outline's middle rows read down as that U, which is the whole point.

use crate::evidence::{AbsenceReason, Observation, Provenance, Uncertainty, UncertaintyBasis};

/// A cross-sectionally fitted credit spread curve.
///
/// ```text
/// spread_bps(leverage, coverage) = intercept * exp(b_lev * leverage - b_cov * coverage)
/// ```
///
/// Exponential rather than linear for two reasons: the spread stays positive
/// without a floor (floors are clamps), and it is monotone in both arguments by
/// construction rather than by assertion.
#[derive(Debug, Clone, PartialEq)]
pub struct CreditCurve {
    intercept_bps: f64,
    leverage_slope: f64,
    coverage_slope: f64,
    provenance: Provenance,
}

impl CreditCurve {
    /// Returns `None` when the supplied fit would not be a usable credit curve:
    /// a non-positive intercept, a leverage slope that is not strictly positive
    /// (FR-20), a negative coverage slope, or any non-finite parameter.
    pub fn fitted(
        intercept_bps: f64,
        leverage_slope: f64,
        coverage_slope: f64,
        provenance: Provenance,
    ) -> Option<Self> {
        let finite =
            intercept_bps.is_finite() && leverage_slope.is_finite() && coverage_slope.is_finite();
        (finite && intercept_bps > 0.0 && leverage_slope > 0.0 && coverage_slope >= 0.0).then_some(
            Self {
                intercept_bps,
                leverage_slope,
                coverage_slope,
                provenance,
            },
        )
    }

    fn spread_bps(&self, leverage: f64, coverage: f64) -> f64 {
        self.intercept_bps * (self.leverage_slope * leverage - self.coverage_slope * coverage).exp()
    }

    pub fn provenance(&self) -> &Provenance {
        &self.provenance
    }
}

/// Place an issuer on the fitted curve to get its cost of debt.
///
/// Uncertainty in leverage and coverage is carried through by the delta method
/// (FR-26): with `s` the spread,
///
/// ```text
/// Var(s) ~= (b_lev * s)^2 * Var(leverage) + (b_cov * s)^2 * Var(coverage)
/// ```
///
/// The risk-free rate is treated as known, being a directly quoted market
/// observable rather than an estimate.
pub fn cost_of_debt(
    curve: Option<&CreditCurve>,
    leverage: &Observation<f64>,
    coverage: &Observation<f64>,
    risk_free_bps: f64,
) -> Observation<f64> {
    let provenance = Provenance::new(
        "cost_of_debt",
        leverage
            .provenance()
            .observed_epoch_day()
            .max(coverage.provenance().observed_epoch_day()),
    );

    let Some(curve) = curve else {
        return Observation::absent(AbsenceReason::ProviderUnavailable, provenance);
    };
    let (Some(&leverage_value), Some(&coverage_value)) = (leverage.value(), coverage.value())
    else {
        return Observation::absent(AbsenceReason::NotReported, provenance);
    };
    if !leverage_value.is_finite() || !coverage_value.is_finite() || !risk_free_bps.is_finite() {
        return Observation::absent(AbsenceReason::OutOfPolicyRange, provenance);
    }

    let spread = curve.spread_bps(leverage_value, coverage_value);
    let rate = risk_free_bps + spread;
    if !rate.is_finite() {
        return Observation::absent(AbsenceReason::OutOfPolicyRange, provenance);
    }

    let variance = (curve.leverage_slope * spread).powi(2) * leverage.propagation_variance()
        + (curve.coverage_slope * spread).powi(2) * coverage.propagation_variance();

    match Uncertainty::from_variance(variance, UncertaintyBasis::Propagated { inputs: 2 }) {
        Some(uncertainty) => Observation::measured(rate, uncertainty, provenance),
        // Both inputs arrived without usable variance, so the rate is a point
        // with no width. Reporting it as absent would discard a real estimate;
        // reporting a fabricated width would be worse. The smallest honest
        // uncertainty is the one the inputs implied, so absence of width is
        // itself absence of the observation.
        None => Observation::absent(AbsenceReason::InsufficientObservations, provenance),
    }
}

/// Weighted average cost of capital.
///
/// ```text
/// wacc = (1 - w_d) * coe + w_d * r_d * (1 - tax)
/// ```
///
/// `debt_weight_bps` and `tax_bps` are shares in basis points. A zero debt
/// weight makes the debt term vanish entirely, so a net-cash issuer resolves
/// even when no credit curve could be fitted for it — missing evidence about a
/// term that carries no weight is not missing evidence about the answer.
pub fn wacc(
    cost_of_equity: &Observation<f64>,
    cost_of_debt: &Observation<f64>,
    debt_weight_bps: f64,
    tax_bps: f64,
) -> Observation<f64> {
    let provenance = Provenance::new(
        "wacc",
        cost_of_equity
            .provenance()
            .observed_epoch_day()
            .max(cost_of_debt.provenance().observed_epoch_day()),
    );

    let admissible = (0.0..=10_000.0).contains(&debt_weight_bps)
        && (0.0..=10_000.0).contains(&tax_bps)
        && debt_weight_bps.is_finite()
        && tax_bps.is_finite();
    if !admissible {
        return Observation::absent(AbsenceReason::OutOfPolicyRange, provenance);
    }

    let Some(&equity_rate) = cost_of_equity.value() else {
        return Observation::absent(AbsenceReason::NotReported, provenance);
    };

    let debt_weight = debt_weight_bps / 10_000.0;
    let equity_weight = 1.0 - debt_weight;
    let after_tax = 1.0 - tax_bps / 10_000.0;

    let (debt_contribution, debt_variance) = if debt_weight == 0.0 {
        (0.0, 0.0)
    } else {
        let Some(&debt_rate) = cost_of_debt.value() else {
            return Observation::absent(AbsenceReason::NotReported, provenance);
        };
        (
            debt_weight * debt_rate * after_tax,
            (debt_weight * after_tax).powi(2) * cost_of_debt.propagation_variance(),
        )
    };

    let rate = equity_weight * equity_rate + debt_contribution;
    if !rate.is_finite() {
        return Observation::absent(AbsenceReason::OutOfPolicyRange, provenance);
    }

    let variance = equity_weight.powi(2) * cost_of_equity.propagation_variance() + debt_variance;
    match Uncertainty::from_variance(variance, UncertaintyBasis::Propagated { inputs: 2 }) {
        Some(uncertainty) => Observation::measured(rate, uncertainty, provenance),
        None => Observation::absent(AbsenceReason::InsufficientObservations, provenance),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn provenance() -> Provenance {
        Provenance::new("test", 20_000)
    }

    fn curve() -> CreditCurve {
        CreditCurve::fitted(90.0, 0.45, 0.05, provenance()).expect("monotone fit")
    }

    fn known(value: f64) -> Observation<f64> {
        Observation::measured(
            value,
            Uncertainty::from_variance(0.01, UncertaintyBasis::SampleVariance { observations: 8 })
                .expect("valid variance"),
            provenance(),
        )
    }

    fn rate_at(leverage: f64) -> f64 {
        *cost_of_debt(Some(&curve()), &known(leverage), &known(6.0), 469.0)
            .value()
            .expect("resolved cost of debt")
    }

    #[test]
    fn a_fit_that_does_not_rise_with_leverage_is_not_a_curve() {
        assert_eq!(
            CreditCurve::fitted(90.0, -0.2, 0.05, provenance()),
            None,
            "an inverted leverage slope is a failed fit, not a usable curve"
        );
    }

    #[test]
    fn a_fit_with_a_non_positive_intercept_is_not_a_curve() {
        assert_eq!(CreditCurve::fitted(0.0, 0.45, 0.05, provenance()), None);
    }

    /// The property the whole module exists for. Sweeping leverage across the
    /// realistic range, the cost of debt must rise everywhere — never the flat
    /// line the trailing accounting coupon produced.
    #[test]
    fn cost_of_debt_rises_with_leverage_across_the_whole_range() {
        let sweep: Vec<f64> = (0..=140).map(|step| step as f64 / 20.0).collect();
        let violations: Vec<(f64, f64)> = sweep
            .windows(2)
            .filter(|pair| rate_at(pair[1]) <= rate_at(pair[0]))
            .map(|pair| (pair[0], pair[1]))
            .collect();
        assert!(
            violations.is_empty(),
            "cost of debt failed to rise between these leverage levels: {violations:?}"
        );
    }

    #[test]
    fn cost_of_debt_falls_as_interest_coverage_improves() {
        let thin = cost_of_debt(Some(&curve()), &known(2.5), &known(2.0), 469.0);
        let comfortable = cost_of_debt(Some(&curve()), &known(2.5), &known(12.0), 469.0);
        assert!(comfortable.value().expect("resolved") < thin.value().expect("resolved"));
    }

    /// The defect, restated as a test. Under the old engine WACC fell without
    /// limit as leverage rose. Here it falls, bottoms out, and turns back up.
    #[test]
    fn wacc_stops_falling_and_turns_back_up_as_leverage_rises() {
        let equity = known(925.0);
        let sampled: Vec<f64> = [0.0, 0.5, 1.5, 2.5, 4.5, 7.0]
            .iter()
            .zip([0.0, 2_000.0, 3_000.0, 4_000.0, 6_000.0, 8_000.0])
            .map(|(leverage, weight)| {
                let debt = cost_of_debt(Some(&curve()), &known(*leverage), &known(6.0), 469.0);
                *wacc(&equity, &debt, weight, 2_100.0)
                    .value()
                    .expect("resolved wacc")
            })
            .collect();
        let trough = sampled.iter().copied().fold(f64::INFINITY, f64::min);
        assert!(
            sampled.last().is_some_and(|last| *last > trough),
            "wacc never recovers from its trough: {sampled:?}"
        );
    }

    #[test]
    fn a_net_cash_issuer_resolves_without_any_credit_curve() {
        let absent = Observation::absent(AbsenceReason::ProviderUnavailable, provenance());
        let resolved = wacc(&known(925.0), &absent, 0.0, 2_100.0);
        assert_eq!(resolved.value(), Some(&925.0));
    }

    #[test]
    fn a_levered_issuer_refuses_without_a_cost_of_debt() {
        let absent = Observation::absent(AbsenceReason::ProviderUnavailable, provenance());
        let refused = wacc(&known(925.0), &absent, 4_000.0, 2_100.0);
        assert_eq!(refused.absence_reason(), Some(AbsenceReason::NotReported));
    }

    #[test]
    fn uncertainty_in_leverage_widens_the_cost_of_debt() {
        let vague = Observation::measured(
            2.5,
            Uncertainty::from_variance(1.0, UncertaintyBasis::SampleVariance { observations: 8 })
                .expect("valid variance"),
            provenance(),
        );
        let precise = known(2.5);
        let wide = cost_of_debt(Some(&curve()), &vague, &known(6.0), 469.0);
        let tight = cost_of_debt(Some(&curve()), &precise, &known(6.0), 469.0);
        assert!(
            wide.uncertainty().map(Uncertainty::variance)
                > tight.uncertainty().map(Uncertainty::variance)
        );
    }
}
