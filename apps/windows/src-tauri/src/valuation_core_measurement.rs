//! What the new Core actually produces, on the pinned cohort, next to the engine
//! it is meant to replace.
//!
//! This is the measurement the whole rebuild exists to make. Everything else is
//! a claim about how the model should behave; this is the only place the numbers
//! are looked at.
//!
//! # Market price is a diagnostic, never an input
//!
//! Price appears in this file for exactly one purpose: reading how far each
//! model lands from it, after both have finished. It is not fed to the Core
//! (FR-35 makes that unrepresentable), it is not a target, and no assertion here
//! is a tolerance around it. A name can be genuinely mispriced — that is the
//! product. What the ratio does say is whether a model is *systematically* off,
//! and a median of 1.5x across twenty names is a statement about the model
//! rather than about twenty markets.
//!
//! # Read the ratios with the neutral line in mind
//!
//! The Core has no invested capital in evidence yet, so every issuer here is
//! valued at FR-29's value-neutral line: growth exactly earns its cost of
//! capital. Names that genuinely earn above their cost of capital are therefore
//! understated by this run and names that earn below it are overstated. The
//! spread of the ratios is informative now; their level will move once return on
//! capital arrives.

#![cfg(test)]

use crate::dcf_model::compute;
use crate::valuation_baseline::{load_cohort, load_driver_data, CohortMember};
use crate::valuation_core_adapter::{
    fit_cross_section, median_cents, value, widest_input, IssuerAnnual, IssuerEvidence, MarketFrame,
};
use valuation_core::publication::ValuationPosterior;

/// The frame the pinned cohort was captured under. Fixed here rather than read
/// from a live series, because a measurement whose frame moves between runs
/// measures the frame.
fn frame() -> MarketFrame {
    MarketFrame {
        risk_free_bps: 430.0,
        equity_risk_premium_bps: 450.0,
        terminal_growth_bps: 300.0,
        observed_epoch_day: 20_663,
    }
}

fn evidence(member: &CohortMember) -> IssuerEvidence {
    let drivers = load_driver_data();
    let annuals = drivers
        .get(&member.symbol)
        .into_iter()
        .flatten()
        .map(|row| IssuerAnnual {
            year: row.year,
            operating_cash_flow: row.ocf,
            capital_expenditure: row.capex,
            revenue: row.revenue,
            interest_expense: row.interest,
            debt: row.debt,
            marginal_tax_bps: row.marginal_tax_bps,
        })
        .collect();
    IssuerEvidence {
        symbol: member.symbol.clone(),
        sector: member.inputs.sector_name.clone(),
        industry: member.inputs.industry_name.clone(),
        shares_outstanding: member.inputs.shares_outstanding,
        market_capitalization: member.inputs.market_cap_dollars.map(|cap| cap as f64),
        total_debt: member.inputs.total_debt_dollars as f64,
        total_cash: member.inputs.total_cash_dollars as f64,
        beta_millis: member.inputs.beta_millis,
        annuals,
    }
}

fn cohort_evidence() -> Vec<(IssuerEvidence, i64)> {
    load_cohort()
        .members
        .iter()
        .filter(|member| !member.quarantine && member.status == "ok")
        .map(|member| (evidence(member), member.inputs.market_price_cents))
        .collect()
}

/// Ratio of a valuation to the market price, in basis points, so a run can be
/// summarized without any of it becoming a threshold.
fn ratio_bps(valuation_cents: i64, market_cents: i64) -> Option<i64> {
    (market_cents > 0).then(|| valuation_cents * 10_000 / market_cents)
}

fn median(values: &mut [i64]) -> Option<i64> {
    if values.is_empty() {
        return None;
    }
    values.sort_unstable();
    Some(values[values.len() / 2])
}

/// The whole reason the Core exists, printed rather than asserted.
///
/// Ignored in normal runs because its output is a table for a person to read,
/// not a pass or a fail. Run it with:
///
/// ```text
/// cargo test --lib core_versus_current_engine_on_the_pinned_cohort -- --ignored --nocapture
/// ```
#[test]
#[ignore = "diagnostic: prints a table for a person to read"]
fn core_versus_current_engine_on_the_pinned_cohort() {
    let cohort = cohort_evidence();
    let issuers: Vec<IssuerEvidence> = cohort.iter().map(|(issuer, _)| issuer.clone()).collect();
    let fitted = fit_cross_section(&issuers, &frame());
    let diagnostics = &fitted.diagnostics;

    println!("\ncross-section fit");
    println!(
        "  credit curve: {} observations, intercept {:.1} bps, leverage slope {:.4}, coverage slope {:.4} -> {}",
        diagnostics.credit_observations,
        diagnostics.credit_intercept_bps,
        diagnostics.credit_leverage_slope,
        diagnostics.credit_coverage_slope,
        if fitted.credit_curve().is_some() { "fitted" } else { "REFUSED" }
    );
    println!(
        "  growth path:  {} pairs, persistence {:.4}, fade {:.4}/yr -> {}",
        diagnostics.growth_pairs,
        diagnostics.growth_persistence,
        diagnostics.fade_per_year,
        if fitted.growth_path().is_some() { "fitted" } else { "REFUSED" }
    );
    println!("  beta sd:      {:.4}", diagnostics.beta_standard_deviation);

    println!(
        "\n{:<6} {:>10} {:>12} {:>12} {:>9} {:>12} {:>12} {:>9} {:>18}",
        "sym", "market", "old", "old/mkt", "", "new p50", "new/mkt", "", "widest input"
    );

    let mut old_ratios: Vec<i64> = Vec::new();
    let mut new_ratios: Vec<i64> = Vec::new();
    let mut refusals: Vec<String> = Vec::new();

    for (issuer, market_cents) in &cohort {
        let posterior = value(issuer, &frame(), &fitted);
        let member = load_cohort()
            .members
            .into_iter()
            .find(|candidate| candidate.symbol == issuer.symbol)
            .expect("the member the evidence came from");
        let old_cents = old_engine_cents(&member);

        let old_ratio = old_cents.and_then(|cents| ratio_bps(cents, *market_cents));
        let new_cents = median_cents(&posterior);
        let new_ratio = new_cents.and_then(|cents| ratio_bps(cents, *market_cents));
        if let Some(ratio) = old_ratio {
            old_ratios.push(ratio);
        }
        if let Some(ratio) = new_ratio {
            new_ratios.push(ratio);
        }
        if let ValuationPosterior::Refused { refusal, .. } = &posterior {
            refusals.push(format!(
                "{} {} / {}",
                issuer.symbol,
                refusal.kind(),
                refusal.detail()
            ));
        }

        println!(
            "{:<6} {:>10} {:>12} {:>12} {:>9} {:>12} {:>12} {:>9} {:>18}",
            issuer.symbol,
            market_cents,
            old_cents.map(|c| c.to_string()).unwrap_or_else(|| "-".into()),
            old_ratio
                .map(|r| format!("{:.2}x", r as f64 / 10_000.0))
                .unwrap_or_else(|| "-".into()),
            "",
            new_cents.map(|c| c.to_string()).unwrap_or_else(|| "-".into()),
            new_ratio
                .map(|r| format!("{:.2}x", r as f64 / 10_000.0))
                .unwrap_or_else(|| "-".into()),
            "",
            widest_input(&posterior)
                .map(|input| input.as_str().to_string())
                .unwrap_or_else(|| "-".into()),
        );
    }

    println!(
        "\nold engine: {} valued, median {:.2}x market",
        old_ratios.len(),
        median(&mut old_ratios.clone()).unwrap_or(0) as f64 / 10_000.0
    );
    println!(
        "new core:   {} published, median {:.2}x market",
        new_ratios.len(),
        median(&mut new_ratios.clone()).unwrap_or(0) as f64 / 10_000.0
    );
    if !refusals.is_empty() {
        println!("\nrefusals ({}):", refusals.len());
        for refusal in &refusals {
            println!("  {refusal}");
        }
    }
}

/// The evidence behind the credit fit, name by name.
///
/// Printed on its own because the fit either succeeds or refuses, and when it
/// refuses the only useful thing is the scatter it refused on.
#[test]
#[ignore = "diagnostic: prints a table for a person to read"]
fn what_the_credit_fit_sees_on_the_pinned_cohort() {
    let frame = frame();
    println!(
        "\n{:<6} {:>14} {:>14} {:>12} {:>12} {:>12}",
        "sym", "debt", "ocf", "leverage", "coverage", "coupon bps"
    );
    let mut points: Vec<(f64, f64)> = Vec::new();
    for (issuer, _) in cohort_evidence() {
        let Some(latest) = issuer.annuals.iter().max_by_key(|annual| annual.year) else {
            continue;
        };
        let Some(debt) = latest.debt.filter(|debt| *debt > 0.0) else {
            println!("{:<6} {:>14} {:>14} {:>12} {:>12} {:>12}", issuer.symbol, "-", "-", "-", "-", "-");
            continue;
        };
        if latest.interest_expense <= 0.0 || latest.operating_cash_flow <= 0.0 {
            println!("{:<6} {:>14.0} {:>14.0} {:>12} {:>12} {:>12}", issuer.symbol, debt, latest.operating_cash_flow, "-", "-", "-");
            continue;
        }
        let leverage = debt / latest.operating_cash_flow;
        let coverage = latest.operating_cash_flow / latest.interest_expense;
        let coupon = latest.interest_expense / debt * 10_000.0;
        println!(
            "{:<6} {:>14.0} {:>14.0} {:>12.2} {:>12.2} {:>12.0}",
            issuer.symbol, debt, latest.operating_cash_flow, leverage, coverage, coupon
        );
        if coupon > frame.risk_free_bps {
            points.push((leverage, coupon - frame.risk_free_bps));
        }
    }
    println!(
        "\n{} usable points; leverage and spread, sorted by leverage:",
        points.len()
    );
    points.sort_by(|left, right| left.0.partial_cmp(&right.0).unwrap());
    for (leverage, spread) in &points {
        println!("  {leverage:>8.2}  {spread:>8.0} bps");
    }
}

fn old_engine_cents(member: &CohortMember) -> Option<i64> {
    let analysis = compute(
        &crate::valuation_baseline::fund_from(member),
        &crate::valuation_baseline::fcf_from(member),
        Some(member.inputs.market_price_cents),
        "core_measurement",
    )
    .ok()?;
    Some(analysis.base_intrinsic_value_cents)
}
