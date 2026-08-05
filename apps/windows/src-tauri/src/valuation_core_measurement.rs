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
//! # The two sides do not read the same history, on purpose
//!
//! The old engine reads `baseline_driver_data_*`, the three-year fixture it was
//! pinned against and the one it ships with. The Core reads
//! `core_driver_data_deep`, every year each issuer actually filed — a median of
//! seventeen. Re-capturing the pinned fixture would move the reference the
//! rebuild is measured against, so it stays where it is.
//!
//! This makes the comparison a comparison of *systems* rather than of models
//! alone, which is the honest unit: what ships is a model plus the evidence it
//! is fed, and "we were reading three years" is a finding about the old system
//! rather than an unfairness in the new one.
//!
//! # The Core refuses every issuer here, for now
//!
//! The Core has no invested capital in evidence yet, so FR-29 makes every
//! operating issuer refuse rather than publish: an absent return on capital is
//! not the same fact as a measured one earning exactly its cost of capital, so
//! it gets no line, not the value-neutral one or any other. `new/mkt` reads `-`
//! for every row until an estimator for return on capital is promoted (Probe C,
//! above).

#![cfg(test)]

use crate::dcf_model::compute;
use crate::operating_valuation::terminal_payout_bps;
use crate::valuation_baseline::{load_cohort, load_driver_fixture, CohortMember};
use crate::valuation_core_adapter::{
    fit_cross_section, median_cents, value, widest_input, IssuerAnnual, IssuerEvidence, MarketFrame,
};
use crate::valuation_fixture_capture::DEEP_DRIVER_FIXTURE;
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
    let drivers = load_driver_fixture(DEEP_DRIVER_FIXTURE);
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
        if fitted.growth_path().is_some() {
            "fitted"
        } else {
            "REFUSED"
        }
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
            old_cents
                .map(|c| c.to_string())
                .unwrap_or_else(|| "-".into()),
            old_ratio
                .map(|r| format!("{:.2}x", r as f64 / 10_000.0))
                .unwrap_or_else(|| "-".into()),
            "",
            new_cents
                .map(|c| c.to_string())
                .unwrap_or_else(|| "-".into()),
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
        // Read through the adapter's own accessor rather than off the annuals,
        // so this reports the evidence the fit consumed. A second reading here
        // would quietly diverge from the first the moment either changes.
        let Some(structure) = issuer.credit_evidence() else {
            println!(
                "{:<6} {:>14} {:>14} {:>12} {:>12} {:>12}",
                issuer.symbol, "-", "-", "-", "-", "-"
            );
            continue;
        };
        println!(
            "{:<6} {:>14} {:>14} {:>12.2} {:>12.2} {:>12.0}",
            issuer.symbol,
            "",
            "",
            structure.leverage,
            structure.coverage,
            structure.effective_rate_bps
        );
        if structure.effective_rate_bps > frame.risk_free_bps {
            points.push((
                structure.leverage,
                structure.effective_rate_bps - frame.risk_free_bps,
            ));
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

/// Whether growth converges at all, tested at horizons where annual noise has
/// somewhere to average out.
///
/// The fitted path rests on a one-year AR(1) of revenue growth, and on the deep
/// history that comes out at **-0.02 over 232 pairs** — no persistence, so no
/// fade rate, so no valuation. Before accepting that as the answer it is worth
/// asking whether the estimator or the phenomenon is at fault: a single year of
/// revenue growth is mostly noise, and regressing noise on noise measures the
/// noise. Averaging growth over `w` years before regressing gives the persistent
/// component somewhere to survive.
///
/// If persistence stays at zero as `w` grows, the conclusion is the strong one —
/// this cohort's growth genuinely does not persist, and a fitted fade rate is not
/// available from revenue history at any horizon.
///
/// ```text
/// cargo test --lib does_growth_persist_at_longer_horizons -- --ignored --nocapture
/// ```
#[test]
#[ignore = "diagnostic: prints a table for a person to read"]
fn does_growth_persist_at_longer_horizons() {
    let series: Vec<Vec<f64>> = cohort_evidence()
        .iter()
        .map(|(issuer, _)| issuer.revenue_growth())
        .collect();

    println!(
        "\n{:>6} {:>8} {:>14} {:>12}",
        "window", "pairs", "persistence", "half-life"
    );
    for window in 1..=5usize {
        let smoothed: Vec<Vec<f64>> = series
            .iter()
            .map(|growth| {
                growth
                    .windows(window)
                    .map(|slice| slice.iter().sum::<f64>() / window as f64)
                    .collect()
            })
            .collect();

        // Consecutive *non-overlapping* averages, so the lag and the response
        // never share a year. Overlapping windows share terms by construction
        // and would manufacture persistence out of the smoothing itself.
        let pairs: Vec<(f64, f64)> = smoothed
            .iter()
            .flat_map(|growth| {
                growth
                    .iter()
                    .step_by(window)
                    .zip(growth.iter().skip(window).step_by(window))
                    .map(|(lag, next)| (*lag, *next))
                    .collect::<Vec<(f64, f64)>>()
            })
            .collect();
        if pairs.len() < 2 {
            continue;
        }

        let pooled_mean = pairs.iter().map(|(lag, _)| lag).sum::<f64>() / pairs.len() as f64;
        let centred: Vec<(f64, f64)> = pairs
            .iter()
            .map(|(lag, next)| (lag - pooled_mean, next - pooled_mean))
            .collect();
        let cross: f64 = centred.iter().map(|(lag, next)| lag * next).sum();
        let square: f64 = centred.iter().map(|(lag, _)| lag * lag).sum();
        let persistence = cross / square;

        // Persistence is per `window` years, so the half-life it implies is in
        // the same unit and has to be scaled back to years to be comparable.
        let half_life = (persistence > 0.0 && persistence < 1.0)
            .then(|| window as f64 * (0.5f64.ln() / persistence.ln()))
            .map(|years| format!("{years:.2} yr"))
            .unwrap_or_else(|| "none".to_string());
        println!(
            "{window:>6} {:>8} {persistence:>14.4} {half_life:>12}",
            pairs.len()
        );
    }

    // If growth deviations do not persist, every issuer's best growth forecast is
    // the pooled level — so whether a fade rate matters at all comes down to how
    // far that level sits from terminal. A pooled level at terminal makes the
    // path flat and `k` irrelevant rather than merely unmeasurable.
    let all: Vec<f64> = series.iter().flatten().copied().collect();
    let pooled = all.iter().sum::<f64>() / all.len() as f64;
    let spread =
        (all.iter().map(|g| (g - pooled).powi(2)).sum::<f64>() / (all.len() - 1) as f64).sqrt();
    println!(
        "\npooled annual growth {:.0} bps (sd {:.0} bps, se {:.0} bps) over {} observations",
        pooled,
        spread,
        spread / (all.len() as f64).sqrt(),
        all.len()
    );
    println!("terminal growth is {:.0} bps", frame().terminal_growth_bps);

    // The between-firm question, asked directly: do the firms' own mean growth
    // rates differ by more than the noise in estimating each one?
    let firm_means: Vec<f64> = series
        .iter()
        .filter(|growth| !growth.is_empty())
        .map(|growth| growth.iter().sum::<f64>() / growth.len() as f64)
        .collect();
    let between = firm_means.iter().sum::<f64>() / firm_means.len() as f64;
    let between_sd = (firm_means
        .iter()
        .map(|m| (m - between).powi(2))
        .sum::<f64>()
        / (firm_means.len() - 1) as f64)
        .sqrt();
    println!(
        "firm mean growth: {} firms, spread {:.0} bps against a within-firm sd of {:.0} bps",
        firm_means.len(),
        between_sd,
        spread
    );
}

/// P2, measured on the real pinned market cohort rather than only on the
/// adapter's synthetic six: no issuer here still produces a Core value. Two
/// names -- MH and BWMN -- already refused before this wave, for an evidence
/// gap unrelated to return on capital (`docs/valuation-aggregation-audit.md`
/// §7 records the same two names and the same reason, measured before FR-29's
/// removal); every other issuer's refusal reason is `estimator_unavailable`,
/// the one this wave adds.
///
/// When an estimator for return on capital is promoted, DELETE this test — do
/// not weaken it. A test that starts filtering out the issuers an estimator
/// makes measurable is a test rewritten to keep passing, not one that still
/// protects the population claim it was written to pin.
#[test]
fn every_issuer_in_the_pinned_cohort_refuses_and_only_two_predate_this_wave() {
    const PRE_EXISTING_NOT_REPORTED: [&str; 2] = ["MH", "BWMN"];
    let cohort = cohort_evidence();
    let issuers: Vec<IssuerEvidence> = cohort.iter().map(|(issuer, _)| issuer.clone()).collect();
    let fitted = fit_cross_section(&issuers, &frame());
    let unexpected: Vec<String> = issuers
        .iter()
        .filter_map(|issuer| {
            let posterior = value(issuer, &frame(), &fitted);
            let expected_reason = if PRE_EXISTING_NOT_REPORTED.contains(&issuer.symbol.as_str()) {
                "not_reported"
            } else {
                "estimator_unavailable"
            };
            match posterior
                .refusal()
                .map(|refusal| (refusal.kind(), refusal.detail()))
            {
                Some(("evidence", reason)) if reason == expected_reason => None,
                _ => Some(format!(
                    "{}: expected evidence/{expected_reason}, found {posterior:?}",
                    issuer.symbol
                )),
            }
        })
        .collect();
    assert!(
        unexpected.is_empty(),
        "unexpected refusal reasons: {unexpected:?}"
    );
}

/// A characterization test, pinning what the *legacy* engine does today rather
/// than what the new Core should do. `operating_valuation::terminal_payout_bps`
/// is untouched by FR-29's Wave 5 correction (L7) — it still substitutes the
/// cost of equity for an absent return on capital, and this cohort's new Core
/// posteriors are compared against that unchanged legacy number. If this test
/// ever fails, the legacy engine changed underneath the comparison this file
/// exists to make, not the new Core.
#[test]
fn the_legacy_engine_still_substitutes_the_cost_of_equity_for_an_absent_return() {
    let cost_of_equity_bps = 800;
    let stable_growth_bps = 300;
    assert_eq!(
        terminal_payout_bps(None, cost_of_equity_bps, stable_growth_bps),
        terminal_payout_bps(
            Some(cost_of_equity_bps),
            cost_of_equity_bps,
            stable_growth_bps
        )
    );
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
