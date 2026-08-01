//! Executes the platform-neutral valuation-decision arithmetic goldens.

#![cfg(test)]

use serde::Deserialize;
use std::path::PathBuf;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Contract {
    schema_version: u32,
    policy_version: String,
    fixtures: Vec<Fixture>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Fixture {
    name: String,
    left: Option<i64>,
    right: Option<i64>,
    bear: Option<i64>,
    base: Option<i64>,
    bull: Option<i64>,
    expected_difference_bps: Option<i64>,
    expected_scenario_width_bps: Option<i64>,
}

#[test]
fn valuation_decision_policy_goldens_execute_on_windows() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../shared/contracts/valuation-decision-policy.json");
    let contract: Contract =
        serde_json::from_str(&std::fs::read_to_string(path).expect("read contract"))
            .expect("parse contract");
    assert_eq!(contract.schema_version, 1);
    assert_eq!(contract.policy_version, "valuation-decision-policy/1");

    for fixture in contract.fixtures {
        if let (Some(left), Some(right)) = (fixture.left, fixture.right) {
            assert_eq!(
                difference_bps(left, right),
                fixture.expected_difference_bps,
                "{}",
                fixture.name
            );
        }
        if let (Some(bear), Some(base), Some(bull)) = (fixture.bear, fixture.base, fixture.bull) {
            assert_eq!(
                scenario_width_bps(bear, base, bull),
                fixture.expected_scenario_width_bps,
                "{}",
                fixture.name
            );
        }
    }
}

fn difference_bps(left: i64, right: i64) -> Option<i64> {
    if left <= 0 || right <= 0 {
        return None;
    }
    let denominator = i128::from(left) + i128::from(right);
    Some(
        (((i128::from(left) - i128::from(right)).abs() * 20_000 + denominator / 2) / denominator)
            as i64,
    )
}

fn scenario_width_bps(bear: i64, base: i64, bull: i64) -> Option<i64> {
    if bear <= 0 || base <= 0 || bull <= 0 || bear > base || base > bull {
        return None;
    }
    Some(((i128::from(bull - bear) * 10_000 + i128::from(base) / 2) / i128::from(base)) as i64)
}

#[test]
fn shared_tipranks_forecast_goldens_execute_on_windows() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../shared/contracts/tipranks-forecast-panel.json");
    let contract: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(path).expect("read TipRanks contract"))
            .expect("parse TipRanks contract");
    assert_eq!(contract["schemaVersion"], 1);
    for case in contract["fixtures"].as_array().expect("fixtures") {
        let observations = case["observations"].as_array().expect("observations");
        let simple = observations
            .iter()
            .map(|item| item["target"].as_i64().unwrap())
            .sum::<i64>()
            / observations.len() as i64;
        let mut newest = std::collections::BTreeMap::<String, &serde_json::Value>::new();
        for observation in observations {
            if observation["ageSeconds"].as_i64().unwrap() <= 7_776_000 {
                let identity = observation["identity"].as_str().unwrap().to_owned();
                let replace = newest
                    .get(&identity)
                    .map(|prior| {
                        prior["ageSeconds"].as_i64().unwrap()
                            > observation["ageSeconds"].as_i64().unwrap()
                    })
                    .unwrap_or(true);
                if replace {
                    newest.insert(identity, observation);
                }
            }
        }
        let weighted = if newest.len() >= 3
            && newest
                .values()
                .all(|item| item["weightMillis"].as_i64().unwrap() > 0)
        {
            let total_weight: i64 = newest
                .values()
                .map(|item| item["weightMillis"].as_i64().unwrap())
                .sum();
            Some(
                (newest
                    .values()
                    .map(|item| {
                        item["target"].as_i64().unwrap() * item["weightMillis"].as_i64().unwrap()
                    })
                    .sum::<i64>()
                    + total_weight / 2)
                    / total_weight,
            )
        } else {
            None
        };
        let expected = &case["expected"];
        assert_eq!(
            Some(simple),
            expected["simple"].as_i64(),
            "{}",
            case["name"]
        );
        assert_eq!(weighted, expected["weighted"].as_i64(), "{}", case["name"]);
        assert_eq!(
            newest.len() as i64,
            expected["identities"].as_i64().unwrap(),
            "{}",
            case["name"]
        );
    }
}
