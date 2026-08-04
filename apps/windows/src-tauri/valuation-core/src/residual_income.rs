//! Residual income on book, for issuers whose balance sheet *is* the business.
//!
//! # Why a bank cannot go through the cash-flow model
//!
//! Free cash flow to the firm subtracts capital expenditure from operating cash
//! flow and discounts the remainder at a weighted average cost of capital. Both
//! halves of that sentence are meaningless for a lender. Debt is not how a bank
//! finances its assets, it is the raw material the assets are made of, so there
//! is no enterprise value to bridge and no leverage ratio that means what the
//! credit curve thinks it means. Running the operating model on a bank does not
//! produce a worse number; it produces a number about a different quantity.
//!
//! So `FinancialServices` issuers are valued on book instead (FR-31):
//!
//! ```text
//! V = B_0 + integral_0^inf ( roe(t) - r ) B(t) exp(-r t) dt
//! ```
//!
//! Book value is the starting point and everything after it is the value the
//! business adds *beyond* its book — earning more on equity than equity costs.
//! Under clean surplus this is an accounting identity with the dividend discount
//! model, not a competing theory of value.
//!
//! # The same path, a different integrand
//!
//! Book compounds along the growth path of [`crate::projection`], and the spread
//! over cost of equity relaxes at the same rate `k`. Both are the same economic
//! statement — a competitive advantage erodes, and the growth it financed erodes
//! with it — so giving them separate fade rates would invent a parameter the
//! cross-section cannot fit. With `A = (g_0 - g_inf)/k` and `a = r - g_inf`:
//!
//! ```text
//! V / B_0 = 1 + s_0 * e^A * sum_{n>=0} [ (-A)^n / n! ] / ( a + (n+1) k )
//! ```
//!
//! The terminal condition is that the spread goes to zero: eventually a bank
//! earns its cost of equity and is worth its book. That is the competitive
//! equilibrium the whole model is built on, and it is why nothing here needs a
//! terminal multiple.
//!
//! # Where the width comes from
//!
//! Return on equity enters as an [`Observation`] with its own measured
//! uncertainty, which is the point of FR-31. A single contaminated year — a
//! day-one CECL provision on an acquired loan book, say — arrives as a wider
//! return posterior and widens the published interval. In the shipping engine
//! that same year dragged the point estimate down toward book and published it
//! narrow, which is the failure mode this replaces.
//!
//! Whether such a year should be *normalized out* upstream is an open decision
//! and not one this module can make: it is a question about how the Shell builds
//! the evidence, and the Core values whatever return posterior it is handed.

use crate::attribution::{Contribution, ValuationInput};
use crate::evidence::{AbsenceReason, Observation, Provenance, Uncertainty, UncertaintyBasis};
use crate::numerics::{central_difference, fading_path_series};
use crate::projection::{GrowthPath, Valuation};

/// Present value of book plus all future residual income.
///
/// `book_value` is common equity in the caller's own unit; the result carries
/// the same unit, since value is exactly linear in it. The result is an *equity*
/// value already — there is no debt bridge on this path, because a lender's
/// liabilities are not a claim to subtract from an enterprise value.
pub fn residual_income_value(
    book_value: &Observation<f64>,
    return_on_equity_bps: &Observation<f64>,
    growth_bps: &Observation<f64>,
    path: &GrowthPath,
    cost_of_equity_bps: &Observation<f64>,
) -> Valuation {
    let provenance = Provenance::new(
        "residual_income_value",
        [
            book_value.provenance(),
            return_on_equity_bps.provenance(),
            growth_bps.provenance(),
            path.provenance(),
            cost_of_equity_bps.provenance(),
        ]
        .into_iter()
        .map(Provenance::observed_epoch_day)
        .max()
        .unwrap_or_default(),
    );
    let refused = |reason| Valuation::new(Observation::absent(reason, provenance.clone()), vec![]);

    let (Some(&book), Some(&growth), Some(&cost_of_equity)) = (
        book_value.value(),
        growth_bps.value(),
        cost_of_equity_bps.value(),
    ) else {
        return refused(AbsenceReason::NotReported);
    };
    // A non-positive book is an insolvent balance sheet, and residual income
    // measures value added *to* book. Scaling a spread by a negative base would
    // return a number with the sign back to front rather than a valuation.
    if !(book.is_finite() && book > 0.0) {
        return refused(AbsenceReason::OutOfPolicyRange);
    }

    // The FR-29 identity in its residual-income form. At `roe = r` the spread is
    // zero, every future year adds nothing, and the firm is worth exactly its
    // book — for *any* growth path. Substituting the cost of equity for an
    // absent return therefore says "we do not know whether this balance sheet
    // earns its keep", not "assume it barely does". An observed return below the
    // cost of equity is used as observed and values the issuer below book.
    let return_on_equity = return_on_equity_bps.value().copied().unwrap_or(cost_of_equity);

    let evaluate = |return_on_equity: f64, growth: f64, cost_of_equity: f64| {
        unit_value(return_on_equity, growth, path, cost_of_equity)
    };
    let Ok(unit) = evaluate(return_on_equity, growth, cost_of_equity) else {
        return refused(AbsenceReason::OutOfPolicyRange);
    };
    let value = book * unit;
    if !value.is_finite() {
        return refused(AbsenceReason::OutOfPolicyRange);
    }

    // The cost-of-equity step is a quarter of the distance to its own guard, so
    // neither side of the difference crosses the terminal growth rate.
    let cost_step = ((cost_of_equity - path.terminal_bps()) / 4.0).min(1.0);
    let partials = [
        (ValuationInput::BookValue, unit, book_value),
        (
            ValuationInput::ReturnOnEquity,
            book * central_difference(1.0, |step| {
                evaluate(return_on_equity + step, growth, cost_of_equity)
            }),
            return_on_equity_bps,
        ),
        (
            ValuationInput::Growth,
            book * central_difference(1.0, |step| {
                evaluate(return_on_equity, growth + step, cost_of_equity)
            }),
            growth_bps,
        ),
        (
            ValuationInput::DiscountRate,
            book * central_difference(cost_step, |step| {
                evaluate(return_on_equity, growth, cost_of_equity + step)
            }),
            cost_of_equity_bps,
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
        None => refused(AbsenceReason::InsufficientObservations),
    }
}

/// Value per unit of book, or the reason there is none.
fn unit_value(
    return_on_equity_bps: f64,
    growth_bps: f64,
    path: &GrowthPath,
    cost_of_equity_bps: f64,
) -> Result<f64, AbsenceReason> {
    let cost_of_equity = cost_of_equity_bps / 10_000.0;
    let terminal = path.terminal_bps() / 10_000.0;
    let growth = growth_bps / 10_000.0;
    let return_on_equity = return_on_equity_bps / 10_000.0;
    let fade = path.fade_per_year();

    if ![cost_of_equity, terminal, growth, return_on_equity]
        .iter()
        .all(|quantity| quantity.is_finite())
    {
        return Err(AbsenceReason::OutOfPolicyRange);
    }

    // FR-27 arithmetic guard: book compounding at or above the rate it is
    // discounted at makes the integral diverge.
    let gordon_denominator = cost_of_equity - terminal;
    if gordon_denominator <= 0.0 {
        return Err(AbsenceReason::OutOfPolicyRange);
    }
    // Clean surplus: book grows by retained earnings, so growth above the return
    // on equity is growth financed by issuing shares. That is a real thing banks
    // do and a real thing this model cannot price, because the value it computes
    // would be split with shareholders who do not exist yet. Refused rather than
    // silently attributed to today's owners.
    //
    // Testing `t = 0` tests the whole path. Both growth and the spread relax at
    // the same `k`, so `g(t) - roe(t)` is affine in `exp(-k t)` and therefore
    // bounded by its endpoints: here, and the limit `g_inf - r`, which the
    // divergence guard above has already made negative.
    if growth > return_on_equity {
        return Err(AbsenceReason::OutOfPolicyRange);
    }

    let spread = return_on_equity - cost_of_equity;
    if spread == 0.0 {
        // The value-neutral identity. Kept as its own branch because summing a
        // series to multiply it by zero would be arithmetic theatre.
        return Ok(1.0);
    }

    let series = fading_path_series((growth - terminal) / fade, |order| {
        1.0 / (gordon_denominator + (order + 1.0) * fade)
    })?;
    Ok(1.0 + spread * series)
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

    fn value_of(return_on_equity: &Observation<f64>, growth: f64) -> f64 {
        *residual_income_value(
            &known(1_000.0),
            return_on_equity,
            &known(growth),
            &path(),
            &known(800.0),
        )
        .value()
        .value()
        .expect("resolved residual income value")
    }

    /// Hand-derivable end to end. On a flat path the series is a single term,
    /// `1/(a + k)` with `a = 0.08 - 0.03` and `k = 0.20`, so the multiple is
    /// `1 + 0.04 * 4 = 1.16` and a book of 1000 is worth 1160.
    #[test]
    fn a_flat_path_matches_the_closed_form_by_hand() {
        assert!((value_of(&known(1_200.0), 300.0) - 1_160.0).abs() < 1e-9);
    }

    /// The derivation check, against a direct integral of the defining
    /// integrand. An algebra slip in the series expansion fails here rather than
    /// silently shifting every financial issuer.
    #[test]
    fn unit_value_agrees_with_direct_numeric_integration() {
        let (spread, terminal, fade, growth, cost_of_equity) = (0.12, 0.03, 0.20, 0.15, 0.08);
        let steps = 2_000_000;
        let horizon = 400.0;
        let width = horizon / steps as f64;
        let integrand = |years: f64| {
            let book = (terminal * years
                + (growth - terminal) / fade * (1.0 - (-fade * years).exp()))
            .exp();
            spread * (-fade * years).exp() * book * (-cost_of_equity * years).exp()
        };
        let integrated: f64 = (0..steps)
            .map(|step| integrand((step as f64 + 0.5) * width) * width)
            .sum();
        let closed_form = unit_value(2_000.0, 1_500.0, &path(), 800.0).expect("resolved");
        assert!(
            (closed_form - 1.0 - integrated).abs() < 1e-6 * integrated.abs(),
            "closed form {closed_form} disagrees with 1 + {integrated}"
        );
    }

    /// FR-31's version of the FR-29 identity: earning exactly the cost of equity
    /// adds nothing to book, whatever the growth path does.
    #[test]
    fn earning_the_cost_of_equity_is_worth_exactly_book() {
        assert!((value_of(&known(800.0), 600.0) - 1_000.0).abs() < 1e-9);
    }

    #[test]
    fn an_absent_return_on_equity_values_the_issuer_at_book() {
        assert!((value_of(&missing(), 600.0) - 1_000.0).abs() < 1e-9);
    }

    /// Not floored at book. An issuer destroying equity capital is worth less
    /// than the capital, and saying so is the whole point of the spread.
    #[test]
    fn a_return_below_the_cost_of_equity_is_worth_less_than_book() {
        assert!(value_of(&known(500.0), 300.0) < 1_000.0);
    }

    #[test]
    fn value_rises_monotonically_with_return_on_equity() {
        let returns: Vec<f64> = (7..=40).map(|step| step as f64 * 50.0).collect();
        let violations: Vec<f64> = returns
            .windows(2)
            .filter(|pair| value_of(&known(pair[1]), 300.0) <= value_of(&known(pair[0]), 300.0))
            .map(|pair| pair[0])
            .collect();
        assert!(
            violations.is_empty(),
            "value failed to rise above these returns on equity: {violations:?}"
        );
    }

    /// Growth is only worth something when the spread is positive, which is the
    /// residual-income statement of "growth without excess return is not value".
    #[test]
    fn growth_is_worthless_at_no_spread_and_valuable_above_it() {
        let flat = value_of(&known(800.0), 700.0) - value_of(&known(800.0), 300.0);
        let earning = value_of(&known(1_200.0), 700.0) - value_of(&known(1_200.0), 300.0);
        assert!(flat.abs() < 1e-9 && earning > 0.0);
    }

    #[test]
    fn book_growing_faster_than_the_return_that_finances_it_refuses() {
        let refused = residual_income_value(
            &known(1_000.0),
            &known(600.0),
            &known(900.0),
            &path(),
            &known(800.0),
        );
        assert_eq!(
            refused.value().absence_reason(),
            Some(AbsenceReason::OutOfPolicyRange)
        );
    }

    /// An insolvent balance sheet is out of policy range, not a small positive
    /// one. Multiplying a spread by a negative base would flip the sign of every
    /// conclusion the model draws.
    #[test]
    fn a_negative_book_refuses_rather_than_inverting_the_spread() {
        let refused = residual_income_value(
            &known(-1_000.0),
            &known(1_200.0),
            &known(300.0),
            &path(),
            &known(800.0),
        );
        assert_eq!(
            refused.value().absence_reason(),
            Some(AbsenceReason::OutOfPolicyRange)
        );
    }

    #[test]
    fn an_absent_book_value_refuses() {
        let refused = residual_income_value(
            &missing(),
            &known(1_200.0),
            &known(300.0),
            &path(),
            &known(800.0),
        );
        assert_eq!(
            refused.value().absence_reason(),
            Some(AbsenceReason::NotReported)
        );
    }

    /// FR-34, on this path too: the delta method's parts add to the whole.
    #[test]
    fn contributions_sum_to_the_published_variance() {
        let valued = residual_income_value(
            &known(1_000.0),
            &known(1_200.0),
            &known(300.0),
            &path(),
            &known(800.0),
        );
        let summed: f64 = valued
            .contributions()
            .iter()
            .copied()
            .map(Contribution::variance)
            .sum();
        let total = valued
            .value()
            .uncertainty()
            .map(Uncertainty::variance)
            .expect("resolved");
        assert!((summed - total).abs() < 1e-9 * total);
    }

    /// The COF shape, as a property rather than as a ticker. A contaminated
    /// provision year arrives as a wider return posterior, and the model widens
    /// rather than collapsing the estimate toward book.
    #[test]
    fn a_contaminated_return_year_widens_the_interval_rather_than_collapsing_value() {
        let vague = Observation::measured(
            1_200.0,
            Uncertainty::from_variance(40_000.0, UncertaintyBasis::SampleVariance {
                observations: 5,
            })
            .expect("valid variance"),
            provenance(),
        );
        let wide = residual_income_value(
            &known(1_000.0),
            &vague,
            &known(300.0),
            &path(),
            &known(800.0),
        );
        assert!(
            wide.value().uncertainty().map(Uncertainty::variance)
                > residual_income_value(
                    &known(1_000.0),
                    &known(1_200.0),
                    &known(300.0),
                    &path(),
                    &known(800.0)
                )
                .value()
                .uncertainty()
                .map(Uncertainty::variance)
        );
    }
}
