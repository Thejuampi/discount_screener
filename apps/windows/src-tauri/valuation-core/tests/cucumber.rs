//! The Gherkin outlines are the specification (FR-45). This binds them to the
//! Core.
//!
//! `fail_on_skipped` is load-bearing: without it an outline row whose step has no
//! definition is reported as skipped and the suite still passes green, which
//! would make "the tables are the contract" a decorative claim. A row that is not
//! actually executed must fail the build.

use cucumber::{given, then, when, World};
use valuation_core::evidence::{
    AbsenceReason, Observation, Provenance, Uncertainty, UncertaintyBasis,
};
use valuation_core::capital::{cost_of_debt, wacc, CreditCurve};
use valuation_core::posterior::{fuse, Fusion};
use valuation_core::attribution::{Contribution, ValuationInput};
use valuation_core::classification::{classify, BusinessClass, Instrument};
use valuation_core::projection::{equity_value, intrinsic_value, GrowthPath, Valuation};
use valuation_core::publication::{publish, ValuationPosterior};
use valuation_core::residual_income::residual_income_value;

/// The reserved absence token (FR-43). `tests/schema.rs` rejects every other
/// spelling of absence in an Examples table, so this is the only one that
/// reaches a step definition.
const ABSENT: &str = "ABSENT";

#[derive(Debug, Default, World)]
struct GrowthWorld {
    trailing: Option<Observation<f64>>,
    forward: Option<Observation<f64>>,
    fusion: Option<Fusion>,
    curve: Option<CreditCurve>,
    leverage: Option<Observation<f64>>,
    coverage: Option<Observation<f64>>,
    risk_free_bps: f64,
    cost_of_equity: Option<Observation<f64>>,
    debt_cost: Option<Observation<f64>>,
    debt_weight_bps: f64,
    tax_bps: f64,
    base_cash_flow: Option<Observation<f64>>,
    growth: Option<Observation<f64>>,
    path: Option<GrowthPath>,
    return_on_capital: Option<Observation<f64>>,
    discount_rate: Option<Observation<f64>>,
    sector: String,
    industry: String,
    instrument: Option<Instrument>,
    business_class: Option<BusinessClass>,
    valuation: Option<Valuation>,
    book_value: Option<Observation<f64>>,
    return_on_equity: Option<Observation<f64>>,
    net_debt: Option<Observation<f64>>,
    diluted_shares: Option<Observation<f64>>,
    posterior: Option<ValuationPosterior>,
    /// Whatever the last `When` resolved. Every outline reports its outcome
    /// through this, so `the outcome is ...` reads the same in all of them.
    result: Option<Observation<f64>>,
}

impl GrowthWorld {
    fn fusion(&self) -> &Fusion {
        self.fusion
            .as_ref()
            .expect("the Growth Posterior step must run before its assertions")
    }

    fn result(&self) -> &Observation<f64> {
        self.result
            .as_ref()
            .expect("a When step must run before its assertions")
    }
}

/// Uncertainty attached to scalar inputs that the tables state as bare numbers.
///
/// The outlines specify point behaviour; propagation of width is unit-tested in
/// `capital.rs` where the expected variances can be stated exactly instead of
/// cluttering every row with a column no row asserts on. A positive nominal
/// variance is required because a zero-width input is not a measurement.
fn nominal(value: f64, source: &'static str) -> Observation<f64> {
    Observation::measured(
        value,
        Uncertainty::from_variance(0.01, UncertaintyBasis::SampleVariance { observations: 8 })
            .expect("nominal variance is positive"),
        Provenance::new(source, 20_000),
    )
}

/// A table cell that is either `ABSENT` or a number, as an Observation.
fn observed(token: &str, source: &'static str) -> Observation<f64> {
    match cell(token) {
        Some(value) => nominal(value, source),
        None => Observation::absent(AbsenceReason::NotReported, Provenance::new(source, 20_000)),
    }
}

/// A cell is either the reserved absence token or a number. Anything else is a
/// malformed table, and failing loudly here is better than coercing it.
fn cell(token: &str) -> Option<f64> {
    if token == ABSENT {
        return None;
    }
    Some(
        token
            .parse::<f64>()
            .unwrap_or_else(|_| panic!("table cell {token:?} is neither {ABSENT} nor a number")),
    )
}

fn channel(
    value: &str,
    variance: &str,
    basis: UncertaintyBasis,
    source: &'static str,
) -> Observation<f64> {
    let provenance = Provenance::new(source, 20_000);
    match (cell(value), cell(variance)) {
        (Some(value), Some(variance)) => {
            let uncertainty = Uncertainty::from_variance(variance, basis)
                .unwrap_or_else(|| panic!("variance {variance} is not a usable positive real"));
            Observation::measured(value, uncertainty, provenance)
        }
        // A value without its uncertainty is not evidence (FR-6): the triple is
        // all-or-nothing, so a half-populated row is an absence, not a guess.
        _ => Observation::absent(AbsenceReason::NotReported, provenance),
    }
}

#[given(expr = "a Trailing Channel of {word} bps with variance {word} over {int} observations")]
fn given_trailing(world: &mut GrowthWorld, value: String, variance: String, observations: u32) {
    world.trailing = Some(channel(
        &value,
        &variance,
        UncertaintyBasis::SampleVariance { observations },
        "trailing",
    ));
}

#[given(expr = "a Forward Channel of {word} bps with variance {word} from {int} analysts")]
fn given_forward(world: &mut GrowthWorld, value: String, variance: String, analysts: u32) {
    world.forward = Some(channel(
        &value,
        &variance,
        UncertaintyBasis::AnalystDispersion { analysts },
        "forward",
    ));
}

#[when(expr = "the Growth Posterior is resolved")]
fn when_resolved(world: &mut GrowthWorld) {
    let trailing = world.trailing.clone().expect("Trailing Channel");
    let forward = world.forward.clone().expect("Forward Channel");
    let fusion = fuse(&[trailing, forward]);
    world.result = Some(fusion.estimate().clone());
    world.fusion = Some(fusion);
}

#[given(
    expr = "a Credit Curve with intercept {word} bps, leverage slope {word} and coverage slope {word}"
)]
fn given_credit_curve(
    world: &mut GrowthWorld,
    intercept: String,
    leverage_slope: String,
    coverage_slope: String,
) {
    // A curve is absent either because the Shell could not fit one (ABSENT
    // cells) or because the fit it produced is not a usable curve — a leverage
    // slope that does not rise is a failed fit, and `fitted` refuses it.
    world.curve = match (
        cell(&intercept),
        cell(&leverage_slope),
        cell(&coverage_slope),
    ) {
        (Some(intercept), Some(leverage_slope), Some(coverage_slope)) => CreditCurve::fitted(
            intercept,
            leverage_slope,
            coverage_slope,
            Provenance::new("credit-curve", 20_000),
        ),
        _ => None,
    };
}

#[given(expr = "an issuer with leverage {word} and interest coverage {word}")]
fn given_issuer_structure(world: &mut GrowthWorld, leverage: String, coverage: String) {
    world.leverage = Some(observed(&leverage, "leverage"));
    world.coverage = Some(observed(&coverage, "coverage"));
}

#[given(expr = "a risk-free rate of {int} bps")]
fn given_risk_free(world: &mut GrowthWorld, risk_free_bps: i64) {
    world.risk_free_bps = risk_free_bps as f64;
}

#[when(expr = "the Cost of Debt is resolved")]
fn when_cost_of_debt(world: &mut GrowthWorld) {
    let leverage = world.leverage.clone().expect("leverage");
    let coverage = world.coverage.clone().expect("coverage");
    world.result = Some(cost_of_debt(
        world.curve.as_ref(),
        &leverage,
        &coverage,
        world.risk_free_bps,
    ));
}

#[then(expr = "the Cost of Debt is {word} bps within 1 bp")]
fn then_cost_of_debt(world: &mut GrowthWorld, expected: String) {
    assert_within_one_bp(world.result(), &expected, "cost of debt");
}

#[given(expr = "a Cost of Equity of {word} bps")]
fn given_cost_of_equity(world: &mut GrowthWorld, value: String) {
    world.cost_of_equity = Some(observed(&value, "cost-of-equity"));
}

#[given(expr = "a Cost of Debt of {word} bps")]
fn given_cost_of_debt(world: &mut GrowthWorld, value: String) {
    world.debt_cost = Some(observed(&value, "cost-of-debt"));
}

#[given(expr = "a debt weight of {word} bps and a marginal tax rate of {word} bps")]
fn given_weights(world: &mut GrowthWorld, debt_weight: String, tax: String) {
    world.debt_weight_bps = cell(&debt_weight).expect("debt weight is always stated");
    world.tax_bps = cell(&tax).expect("tax rate is always stated");
}

#[when(expr = "the WACC is resolved")]
fn when_wacc(world: &mut GrowthWorld) {
    let equity = world.cost_of_equity.clone().expect("Cost of Equity");
    let debt = world.debt_cost.clone().expect("Cost of Debt");
    world.result = Some(wacc(&equity, &debt, world.debt_weight_bps, world.tax_bps));
}

#[then(expr = "the WACC is {word} bps within 1 bp")]
fn then_wacc(world: &mut GrowthWorld, expected: String) {
    assert_within_one_bp(world.result(), &expected, "wacc");
}

#[given(expr = "a base cash flow of {word}")]
fn given_base_cash_flow(world: &mut GrowthWorld, value: String) {
    world.base_cash_flow = Some(observed(&value, "base-cash-flow"));
}

#[given(expr = "a Growth Posterior of {word} bps")]
fn given_growth_posterior(world: &mut GrowthWorld, value: String) {
    world.growth = Some(observed(&value, "growth-posterior"));
}

#[given(expr = "a growth path fading to {word} bps at {word} per year")]
fn given_growth_path(world: &mut GrowthWorld, terminal: String, fade: String) {
    // A path is absent either because the Shell fitted no relaxation speed or
    // because the speed it produced is not a fade — a non-positive rate never
    // reaches the terminal rate, and `fitted` refuses it.
    world.path = match (cell(&terminal), cell(&fade)) {
        (Some(terminal), Some(fade)) => {
            GrowthPath::fitted(terminal, fade, Provenance::new("growth-path", 20_000))
        }
        _ => None,
    };
}

#[given(expr = "a return on capital of {word} bps")]
fn given_return_on_capital(world: &mut GrowthWorld, value: String) {
    world.return_on_capital = Some(observed(&value, "return-on-capital"));
}

#[given(expr = "a discount rate of {word} bps")]
fn given_discount_rate(world: &mut GrowthWorld, value: String) {
    world.discount_rate = Some(observed(&value, "discount-rate"));
}

#[when(expr = "the Intrinsic Value is resolved")]
fn when_intrinsic_value(world: &mut GrowthWorld) {
    let base = world.base_cash_flow.clone().expect("base cash flow");
    let growth = world.growth.clone().expect("Growth Posterior");
    let return_on_capital = world.return_on_capital.clone().expect("return on capital");
    let discount = world.discount_rate.clone().expect("discount rate");
    // An unfitted path refuses the whole valuation. `intrinsic_value` takes the
    // path by reference rather than by option because a path that does not fade
    // is not representable, so the refusal happens here.
    let valued = match world.path.as_ref() {
        Some(path) => intrinsic_value(&base, &growth, path, &return_on_capital, &discount),
        None => Valuation::new(
            Observation::absent(
                AbsenceReason::NotReported,
                Provenance::new("growth-path", 20_000),
            ),
            vec![],
        ),
    };
    world.result = Some(valued.value().clone());
    world.valuation = Some(valued);
}

#[then(expr = "the Intrinsic Value is {word} within 1 cent")]
fn then_intrinsic_value(world: &mut GrowthWorld, expected: String) {
    match cell(&expected) {
        Some(expected) => {
            let actual = world
                .result()
                .value()
                .expect("expected a resolved intrinsic value, found absence");
            assert!(
                (actual - expected).abs() <= 0.01,
                "intrinsic value {actual} is not within 1 cent of {expected}"
            );
        }
        None => assert_eq!(
            world.result().value(),
            None,
            "expected absence, found a resolved intrinsic value"
        ),
    }
}

#[given(expr = "an issuer in sector {string} and industry {string}")]
fn given_sector_and_industry(world: &mut GrowthWorld, sector: String, industry: String) {
    // Absent text is empty text here: the taxonomy matches nothing against it
    // and falls through to unclassified, which is the closed-world refusal.
    world.sector = text(&sector);
    world.industry = text(&industry);
}

#[given(expr = "the instrument is {word}")]
fn given_instrument(world: &mut GrowthWorld, instrument: String) {
    world.instrument = Some(match instrument.as_str() {
        "common_equity" => Instrument::CommonEquity,
        "fund" => Instrument::NotCommonEquity,
        other => panic!("table cell {other:?} is not an instrument"),
    });
}

#[when(expr = "the Business Class is resolved")]
fn when_business_class(world: &mut GrowthWorld) {
    let instrument = world.instrument.expect("instrument");
    world.business_class = Some(classify(&world.sector, &world.industry, instrument));
}

#[then(expr = "the Business Class is {word}")]
fn then_business_class(world: &mut GrowthWorld, expected: String) {
    let actual = world.business_class.expect("the When step must run first");
    assert_eq!(actual.as_str(), expected);
}

/// A quantity stated as a value and a standard deviation, as an Observation.
///
/// A zero standard deviation is not a measurement, so it produces an input that
/// carries a value and contributes no width — which is how the rows isolate one
/// input's effect on the interval at a time.
fn with_deviation(value: &str, deviation: &str, source: &'static str) -> Observation<f64> {
    let provenance = Provenance::new(source, 20_000);
    let Some(value) = cell(value) else {
        return Observation::absent(AbsenceReason::NotReported, provenance);
    };
    let variance = cell(deviation).expect("a standard deviation is always stated").powi(2);
    let uncertainty = Uncertainty::from_variance(
        variance.max(f64::MIN_POSITIVE),
        UncertaintyBasis::SampleVariance { observations: 8 },
    )
    .expect("a positive variance");
    Observation::measured(value, uncertainty, provenance)
}

#[given(expr = "a firm value of {word} with standard deviation {word}")]
fn given_firm_value(world: &mut GrowthWorld, value: String, deviation: String) {
    let observed = with_deviation(&value, &deviation, "firm-value");
    world.valuation = Some(Valuation::new(
        observed.clone(),
        vec![Contribution::new(
            ValuationInput::BaseCashFlow,
            observed.propagation_variance(),
        )],
    ));
}

#[given(expr = "net debt of {word} with standard deviation {word}")]
fn given_net_debt(world: &mut GrowthWorld, value: String, deviation: String) {
    world.net_debt = Some(with_deviation(&value, &deviation, "net-debt"));
}

#[given(expr = "{word} diluted shares with standard deviation {word}")]
fn given_diluted_shares(world: &mut GrowthWorld, value: String, deviation: String) {
    world.diluted_shares = Some(with_deviation(&value, &deviation, "diluted-shares"));
}

#[given(expr = "a Business Class of {word}")]
fn given_business_class(world: &mut GrowthWorld, class: String) {
    world.business_class = Some(match class.as_str() {
        "operating_non_financial" => BusinessClass::OperatingNonFinancial,
        "financial_services" => BusinessClass::FinancialServices,
        "not_eligible" => BusinessClass::NotEligible,
        "unclassified" => BusinessClass::Unclassified,
        other => panic!("table cell {other:?} is not a business class"),
    });
}

#[when(expr = "the Valuation Posterior is published")]
fn when_published(world: &mut GrowthWorld) {
    // Two steps, because the debt bridge belongs to the operating path and not
    // to publication: a firm value crosses it, and a residual-income value is
    // already an equity value and never sees it.
    let equity = equity_value(
        world.valuation.as_ref().expect("firm value"),
        world.net_debt.as_ref().expect("net debt"),
    );
    world.posterior = Some(publish(
        world.business_class.expect("Business Class"),
        &equity,
        world.diluted_shares.as_ref().expect("diluted shares"),
    ));
}

#[given(expr = "a book value of {word} with standard deviation {word}")]
fn given_book_value(world: &mut GrowthWorld, value: String, deviation: String) {
    world.book_value = Some(with_deviation(&value, &deviation, "book-value"));
}

#[given(expr = "a return on equity of {word} bps")]
fn given_return_on_equity(world: &mut GrowthWorld, value: String) {
    world.return_on_equity = Some(observed(&value, "return-on-equity"));
}

#[given(expr = "a cost of equity of {word} bps")]
fn given_cost_of_equity_rate(world: &mut GrowthWorld, value: String) {
    world.discount_rate = Some(observed(&value, "cost-of-equity"));
}

#[when(expr = "the Residual Income Value is resolved")]
fn when_residual_income(world: &mut GrowthWorld) {
    let book = world.book_value.clone().expect("book value");
    let return_on_equity = world.return_on_equity.clone().expect("return on equity");
    let growth = world.growth.clone().expect("Growth Posterior");
    let cost_of_equity = world.discount_rate.clone().expect("cost of equity");
    let valued = match world.path.as_ref() {
        Some(path) => residual_income_value(&book, &return_on_equity, &growth, path, &cost_of_equity),
        None => Valuation::new(
            Observation::absent(
                AbsenceReason::NotReported,
                Provenance::new("growth-path", 20_000),
            ),
            vec![],
        ),
    };
    world.result = Some(valued.value().clone());
    world.valuation = Some(valued);
}

#[then(expr = "the Residual Income Value is {word} within 1 cent")]
fn then_residual_income(world: &mut GrowthWorld, expected: String) {
    match cell(&expected) {
        Some(expected) => {
            let actual = world
                .result()
                .value()
                .expect("expected a resolved residual income value, found absence");
            assert!(
                (actual - expected).abs() <= 0.01,
                "residual income value {actual} is not within 1 cent of {expected}"
            );
        }
        None => assert_eq!(
            world.result().value(),
            None,
            "expected absence, found a resolved residual income value"
        ),
    }
}

#[then(expr = "the published percentiles are {word}, {word} and {word} cents")]
fn then_percentiles(world: &mut GrowthWorld, low: String, median: String, high: String) {
    let posterior = world.posterior.as_ref().expect("the When step must run first");
    match (cell(&low), cell(&median), cell(&high)) {
        (Some(low), Some(median), Some(high)) => {
            let ValuationPosterior::Published {
                percentile_05_cents,
                median_cents,
                percentile_95_cents,
                ..
            } = posterior
            else {
                panic!("expected a published posterior, found {posterior:?}");
            };
            assert_eq!(
                [*percentile_05_cents, *median_cents, *percentile_95_cents],
                [low as i64, median as i64, high as i64]
            );
        }
        _ => assert!(
            !posterior.is_published(),
            "expected a refusal, found {posterior:?}"
        ),
    }
}

#[then(expr = "the publication outcome is {word}")]
fn then_publication_outcome(world: &mut GrowthWorld, expected: String) {
    let posterior = world.posterior.as_ref().expect("the When step must run first");
    let actual = if posterior.is_published() {
        "published"
    } else {
        "refused"
    };
    assert_eq!(actual, expected);
}

/// A text cell is either the reserved absence token or the text itself.
fn text(token: &str) -> String {
    if token == ABSENT {
        return String::new();
    }
    token.to_string()
}

fn assert_within_one_bp(actual: &Observation<f64>, expected: &str, label: &str) {
    match cell(expected) {
        Some(expected) => {
            let actual = actual
                .value()
                .unwrap_or_else(|| panic!("expected a resolved {label}, found absence"));
            assert!(
                (actual - expected).abs() <= 1.0,
                "{label} {actual} is not within 1 bp of {expected}"
            );
        }
        None => assert_eq!(
            actual.value(),
            None,
            "expected absence, found a resolved {label}"
        ),
    }
}

#[then(expr = "the point estimate is {word} bps within 1 bp")]
fn then_point_estimate(world: &mut GrowthWorld, expected: String) {
    let actual = world.fusion().estimate().value().copied();
    match cell(&expected) {
        Some(expected) => {
            let actual = actual.expect("expected a resolved point estimate, found absence");
            assert!(
                (actual - expected).abs() <= 1.0,
                "point estimate {actual} is not within 1 bp of {expected}"
            );
        }
        None => assert_eq!(actual, None, "expected absence, found a point estimate"),
    }
}

#[then(expr = "the posterior variance is {word} within 1 bp")]
fn then_posterior_variance(world: &mut GrowthWorld, expected: String) {
    let actual = world
        .fusion()
        .estimate()
        .uncertainty()
        .map(Uncertainty::variance);
    match cell(&expected) {
        Some(expected) => {
            let actual = actual.expect("expected a resolved posterior variance, found absence");
            assert!(
                (actual - expected).abs() <= 1.0,
                "posterior variance {actual} is not within 1 bp of {expected}"
            );
        }
        None => assert_eq!(actual, None, "expected absence, found a posterior variance"),
    }
}

#[then(expr = "the channel weights are {int} and {int} bps")]
fn then_channel_weights(world: &mut GrowthWorld, trailing: i32, forward: i32) {
    assert_eq!(world.fusion().weights_bps(), &[trailing, forward]);
}

#[then(expr = "the outcome is {word}")]
fn then_outcome(world: &mut GrowthWorld, expected: String) {
    let actual = if world.result().is_measured() {
        "resolved"
    } else {
        "refused"
    };
    assert_eq!(actual, expected);
}

#[tokio::main]
async fn main() {
    GrowthWorld::cucumber()
        .fail_on_skipped()
        .run_and_exit("tests/features")
        .await;
}
