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
use valuation_core::posterior::{fuse, Fusion};

/// The reserved absence token (FR-43). `tests/schema.rs` rejects every other
/// spelling of absence in an Examples table, so this is the only one that
/// reaches a step definition.
const ABSENT: &str = "ABSENT";

#[derive(Debug, Default, World)]
struct GrowthWorld {
    trailing: Option<Observation<f64>>,
    forward: Option<Observation<f64>>,
    fusion: Option<Fusion>,
}

impl GrowthWorld {
    fn fusion(&self) -> &Fusion {
        self.fusion
            .as_ref()
            .expect("the Growth Posterior step must run before its assertions")
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
    world.fusion = Some(fuse(&[trailing, forward]));
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
    let actual = if world.fusion().is_resolved() {
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
