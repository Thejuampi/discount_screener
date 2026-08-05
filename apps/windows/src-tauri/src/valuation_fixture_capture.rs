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

use crate::dcf_model::FcfPoint;
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

/// Render one issuer-year as the deep driver fixture's JSON row, or `None`
/// when a divisor the model requires (OCF, CapEx or revenue) was never
/// filed for it.
///
/// A year is usable only when the three drivers the model divides by are all
/// reported for it. Filling any of them would be inventing the very history
/// this capture exists to stop inventing.
///
/// Named and unit-tested apart from the network capture below so the
/// no-fabrication rule for `effective_tax_bps` / `marginal_tax_bps` is
/// checked by a fast test rather than only by a network-gated one.
fn deep_driver_year_row(point: &FcfPoint) -> Option<serde_json::Value> {
    let operating_cash_flow = point.operating_cash_flow_dollars?;
    let capital_expenditure = point.capital_expenditure_dollars?;
    let revenue = point.revenue_dollars?;
    Some(serde_json::json!({
        "year": point.year,
        "ocf": operating_cash_flow,
        "capex": capital_expenditure,
        "revenue": revenue,
        // An absent interest reading is emitted as an explicit null.
        // A fabricated zero was always against the no-fabrication
        // rule, and it is now actively ambiguous as well: zero is a
        // legitimate value of a signed net series, so "the issuer
        // filed nothing" and "the issuer's interest income exactly
        // offset its expense" would be written identically.
        "interest": point.interest_expense_dollars,
        // Same rule as "interest" above: an absent tax reading is
        // emitted as an explicit null, not a fabricated rate. A
        // filled-in 21% marginal rate is worse than an absent one
        // because 21% is also the single most common genuinely
        // filed marginal rate, so a reader (or the FCFF after-tax
        // adjustment that consumes this field) cannot tell "the
        // issuer filed a 21% marginal rate" from "nothing was
        // filed and we guessed the statutory rate". A filled-in 0
        // effective rate has the same problem against a
        // genuinely zero-tax year.
        "effective_tax_bps": point.tax_rate_bps,
        "debt": point.total_debt_dollars,
        "marginal_tax_bps": point.marginal_tax_bps,
    }))
}

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

        let mut years: Vec<serde_json::Value> =
            points.iter().filter_map(deep_driver_year_row).collect();
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

/// A year with no filed effective tax rate must come out of the emitter as
/// an explicit null, never as a fabricated statutory guess (LD-13). This is a
/// regression test for the fabrication this fixture capture used to commit:
/// `unwrap_or(0)` was byte-identical to a genuinely filed zero-tax year on 24
/// issuer-years, so nobody downstream could tell a guess from a filing.
#[test]
fn deep_driver_year_row_never_fabricates_effective_tax_rate() {
    // `with_operating_drivers`'s last argument is the effective tax rate;
    // `None` here is the "nothing filed for this year" case this test guards.
    let point = FcfPoint::new(2024, 1_000.0).with_operating_drivers(
        900.0,
        100.0,
        5_000.0,
        Some(50.0),
        None,
    );

    let row = deep_driver_year_row(&point).expect("ocf/capex/revenue are all present");

    assert!(row["effective_tax_bps"].is_null());
}

/// Same guard as above, for the marginal tax rate: `unwrap_or(2_100)` was
/// byte-identical to a genuinely filed 21% marginal rate on 179 issuer-years.
#[test]
fn deep_driver_year_row_never_fabricates_marginal_tax_rate() {
    // `with_rate_resolution_inputs` is the only way `marginal_tax_bps` is
    // set; leaving it unset here is the "nothing filed" case this test
    // guards.
    let point = FcfPoint::new(2024, 1_000.0).with_operating_drivers(
        900.0,
        100.0,
        5_000.0,
        Some(50.0),
        None,
    );

    let row = deep_driver_year_row(&point).expect("ocf/capex/revenue are all present");

    assert!(row["marginal_tax_bps"].is_null());
}
