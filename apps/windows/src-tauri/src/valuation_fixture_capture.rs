//! Re-capture the pinned driver fixture from EDGAR with the full history each
//! issuer actually filed.
//!
//! The fixture the cohort measurement runs on carries three years for twenty of
//! its twenty-four names. Three years is the arithmetic floor for a trend and
//! nowhere near enough to say whether growth persists, so every cross-sectional
//! fit built on it is resting on a sample chosen by a capture rather than by the
//! evidence. EDGAR companyfacts carries a decade.
//!
//! # Why this is a test and not a binary
//!
//! It reaches the network, so it can never run in the normal suite. Living here
//! rather than in a `bin/` target keeps it next to the fixture loader it feeds
//! and inside the crate whose types it writes, so a schema change breaks it at
//! compile time instead of silently producing a fixture nothing can read.

#![cfg(test)]

use std::collections::BTreeMap;

use crate::edgar::{edgar_client, fetch_cik_map, fetch_fcf_history};
use crate::valuation_baseline::{fixture_path, load_cohort};

/// How much history EDGAR actually holds for the pinned cohort, before any
/// fixture is written.
///
/// Run with:
///
/// ```text
/// cargo test --lib how_deep_the_filed_history_goes -- --ignored --nocapture
/// ```
#[test]
#[ignore = "reaches the SEC network"]
fn how_deep_the_filed_history_goes() {
    let client = edgar_client();
    let cik_map = fetch_cik_map(&client).expect("EDGAR ticker map");
    let cohort = load_cohort();

    println!("\n{:<6} {:>8} {:>8}  {}", "sym", "years", "span", "range");
    let mut depths: Vec<usize> = Vec::new();
    for member in &cohort.members {
        let Some(cik) = cik_map.get(&member.symbol) else {
            println!("{:<6} {:>8} {:>8}  no CIK", member.symbol, "-", "-");
            continue;
        };
        match fetch_fcf_history(&client, &member.symbol, *cik) {
            Ok(Some(points)) => {
                let years: Vec<i32> = points.iter().map(|point| point.year).collect();
                let (first, last) = (
                    years.iter().min().copied().unwrap_or(0),
                    years.iter().max().copied().unwrap_or(0),
                );
                depths.push(years.len());
                println!(
                    "{:<6} {:>8} {:>8}  {first}..{last}",
                    member.symbol,
                    years.len(),
                    last - first + 1
                );
            }
            Ok(None) => println!("{:<6} {:>8} {:>8}  no history", member.symbol, "-", "-"),
            Err(error) => println!("{:<6} {:>8} {:>8}  {error}", member.symbol, "-", "-"),
        }
    }

    depths.sort_unstable();
    let histogram: BTreeMap<usize, usize> =
        depths.iter().fold(BTreeMap::new(), |mut counts, depth| {
            *counts.entry(*depth).or_default() += 1;
            counts
        });
    println!("\nyears of history -> issuers");
    for (years, issuers) in &histogram {
        println!("{years:>4} -> {issuers}");
    }
    println!("fixture lives at {}", fixture_path().display());
}

/// The deep driver fixture the Core measurement reads.
///
/// Deliberately a different file from `baseline_driver_data_*`. That one is the
/// old engine's pinned baseline and its numbers are the reference the rebuild is
/// measured against; re-capturing it would move the thing being compared to.
pub(crate) const DEEP_DRIVER_FIXTURE: &str = "tests/fixtures/valuation/core_driver_data_deep.json";

/// Write `DEEP_DRIVER_FIXTURE` from EDGAR, with every year each issuer filed.
///
/// ```text
/// cargo test --lib capture_the_deep_driver_fixture -- --ignored --nocapture
/// ```
#[test]
#[ignore = "reaches the SEC network and rewrites a fixture"]
fn capture_the_deep_driver_fixture() {
    let client = edgar_client();
    let cik_map = fetch_cik_map(&client).expect("EDGAR ticker map");
    let cohort = load_cohort();

    let mut rows = serde_json::Map::new();
    let mut skipped: Vec<String> = Vec::new();
    for member in &cohort.members {
        let Some(cik) = cik_map.get(&member.symbol) else {
            skipped.push(format!("{} (no CIK)", member.symbol));
            continue;
        };
        let points = match fetch_fcf_history(&client, &member.symbol, *cik) {
            Ok(Some(points)) => points,
            Ok(None) => {
                skipped.push(format!("{} (no history)", member.symbol));
                continue;
            }
            Err(error) => {
                skipped.push(format!("{} ({error})", member.symbol));
                continue;
            }
        };

        // A year is usable only when the three drivers the model divides by are
        // all reported for it. Filling any of them would be inventing the very
        // history this capture exists to stop inventing.
        let mut years: Vec<serde_json::Value> = points
            .iter()
            .filter_map(|point| {
                let operating_cash_flow = point.operating_cash_flow_dollars?;
                let capital_expenditure = point.capital_expenditure_dollars?;
                let revenue = point.revenue_dollars?;
                Some(serde_json::json!({
                    "year": point.year,
                    "ocf": operating_cash_flow,
                    "capex": capital_expenditure,
                    "revenue": revenue,
                    "interest": point.interest_expense_dollars.unwrap_or(0.0),
                    "effective_tax_bps": point.tax_rate_bps.unwrap_or(0),
                    "debt": point.total_debt_dollars,
                    "marginal_tax_bps": point.marginal_tax_bps.unwrap_or(2_100),
                }))
            })
            .collect();
        years.sort_by_key(|year| year["year"].as_i64().unwrap_or_default());

        if years.is_empty() {
            skipped.push(format!("{} (no complete year)", member.symbol));
            continue;
        }
        println!("{:<6} {:>3} years captured", member.symbol, years.len());
        rows.insert(member.symbol.clone(), serde_json::Value::Array(years));
    }

    let fixture = serde_json::json!({
        "source": "SEC EDGAR companyfacts, full filed history, captured 2026-08-04",
        "rows": rows,
    });
    let path =
        fixture_path().with_file_name(DEEP_DRIVER_FIXTURE.rsplit('/').next().expect("a file name"));
    std::fs::write(
        &path,
        serde_json::to_string_pretty(&fixture).expect("serialize fixture"),
    )
    .unwrap_or_else(|error| panic!("write {}: {error}", path.display()));

    println!("\nwrote {} issuers to {}", rows.len(), path.display());
    if !skipped.is_empty() {
        println!("skipped {}: {}", skipped.len(), skipped.join(", "));
    }
}
