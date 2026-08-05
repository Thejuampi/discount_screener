//! **DEPRECATED — superseded by the `valuation-core` crate.**
//!
//! Still shipping; do not extend. The source waterfall below is sound in shape —
//! market yield, then rated/synthetic spread, then aligned accounting — but the
//! first two rungs are dead in production: every call site passes `None` for
//! both, so resolution always falls to the accounting coupon. That is the
//! mechanism behind the strictly-decreasing WACC recorded in `dcf_model`.
//!
//! Explicit annual financing-driver resolution for operating-company FCFF.
//!
//! This module deliberately has no policy-rate defaults.  It only resolves a
//! cost of debt and marginal tax rate from evidence that is aligned to the same
//! fiscal periods.  Missing evidence is an error consumed by the FCFF caller.

use crate::dcf_model::{FcfPoint, WaccFieldSource};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EvidenceQuality {
    Solid,
    Provisional,
}

impl EvidenceQuality {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Solid => "solid",
            Self::Provisional => "provisional",
        }
    }
}

#[derive(Debug, Clone)]
pub struct ResolvedRateInputs {
    pub cost_of_debt_bps: i32,
    pub cost_of_debt_source: WaccFieldSource,
    pub marginal_tax_bps: i32,
    pub marginal_tax_source: WaccFieldSource,
    pub quality: EvidenceQuality,
    pub valid_debt_periods: Vec<i32>,
    pub valid_tax_periods: Vec<i32>,
    pub average_debt_dollars: f64,
    pub reasons: Vec<String>,
}

/// Resolve CoD and marginal tax for an operating non-financial company.
///
/// The source order is encoded in the order of the checks: observable market
/// yield, rated/synthetic spread, then aligned accounting observations.  The
/// current public input surface carries the latter and the optional market
/// fields; no source is silently substituted.  Debt and interest are paired by
/// fiscal year, never by array position or latest as-of date.
///
/// A fiscal year whose winning interest concept is declared net-of-income
/// (`negatedQnames`) is dropped from the accounting fit rather than the whole
/// issuer being refused; see the comment at the accounting fit for the
/// contract this drops years under, and why it keys on basis rather than sign.
pub fn resolve_rate_inputs(
    history: &[FcfPoint],
    reported_total_debt_dollars: Option<i64>,
    rf_bps: i32,
) -> Result<Option<ResolvedRateInputs>, String> {
    resolve_rate_inputs_for_source(history, reported_total_debt_dollars, rf_bps, "")
}

/// Same resolution with provider provenance available to distinguish the SEC
/// accounting fallback from a Yahoo same-period fallback.
pub fn resolve_rate_inputs_for_source(
    history: &[FcfPoint],
    reported_total_debt_dollars: Option<i64>,
    rf_bps: i32,
    provider_source: &str,
) -> Result<Option<ResolvedRateInputs>, String> {
    let Some(total_debt) = reported_total_debt_dollars else {
        return Err("fcff unavailable: total debt is missing; missing debt is not zero".into());
    };
    if total_debt < 0 {
        return Err("fcff unavailable: total debt is negative or contradictory".into());
    }

    if total_debt == 0 {
        if history.iter().any(|point| {
            point
                .total_debt_dollars
                .is_some_and(|debt| debt.abs() < f64::EPSILON)
                && point
                    .interest_expense_dollars
                    .is_some_and(|interest| interest.abs() > f64::EPSILON)
        }) {
            return Err(
                "fcff unavailable: provider inconsistency, positive interest with zero debt".into(),
            );
        }
        return Ok(None);
    }

    let mut market_yields: Vec<(i32, i32)> = history
        .iter()
        .filter_map(|point| {
            point
                .market_yield_bps
                .filter(|rate| (0..=5_000).contains(rate))
                .map(|rate| (point.year, rate))
        })
        .collect();
    market_yields.sort_by_key(|(year, _)| *year);

    let mut rated_spreads: Vec<(i32, i32)> = history
        .iter()
        .filter_map(|point| {
            point
                .rated_or_synthetic_spread_bps
                .filter(|spread| (0..=4_000).contains(spread))
                .map(|spread| (point.year, rf_bps.saturating_add(spread)))
        })
        .collect();
    rated_spreads.sort_by_key(|(year, _)| *year);

    // A fiscal year whose winning `interestExpense` concept was declared
    // net-of-income (`negatedQnames`, `INTEREST_EXPENSE.qname_signs`) reports a
    // *net* figure: interest income has already been subtracted from interest
    // expense. Gross interest expense is therefore not measurable for that
    // year, and the year is not a fittable observation for this accounting
    // channel -- it is dropped. The issuer loses the channel only when
    // dropping those years empties the fittable set below, which is the
    // pre-existing `accounting_common` intersection with `tax_years`, not a
    // separate rule.
    //
    // This reads the *basis* of the series -- which concept won, carried on
    // `FcfPoint::interest_is_net_basis` -- never the *sign* of the value. A
    // net-*expense* filer's series is equally net while staying positive in
    // every filed year (BKR: net in every filed year, never once negative),
    // and a sign test alone can never see it. That gap was LD-8; this rule
    // closes it wherever the basis reaches a published number -- an issuer
    // whose cost of debt resolves from a higher-priority lane (market yield or
    // rated spread) before reaching this fit is unaffected by its own basis,
    // because those lanes never consult it.
    let accounting: Vec<(i32, f64, f64)> = history
        .iter()
        .filter(|point| point.interest_is_net_basis != Some(true))
        .filter_map(|point| {
            let debt = point.total_debt_dollars?;
            let interest = point.interest_expense_dollars?;
            if !debt.is_finite() || !interest.is_finite() || debt < 0.0 {
                return None;
            }
            (debt > 0.0 && interest > 0.0).then_some((point.year, debt, interest))
        })
        .collect();

    let mut marginal_tax: Vec<(i32, i32, WaccFieldSource)> = history
        .iter()
        .filter_map(|point| {
            point
                .marginal_tax_bps
                .filter(|tax| (0..=5_000).contains(tax))
                .map(|tax| {
                    (
                        point.year,
                        tax,
                        point
                            .marginal_tax_source
                            .unwrap_or(WaccFieldSource::Unavailable),
                    )
                })
        })
        .collect();
    marginal_tax.sort_by_key(|(year, _, _)| *year);
    if marginal_tax.is_empty() {
        return Err(
            "fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources"
                .into(),
        );
    }

    let tax_years: std::collections::BTreeSet<i32> =
        marginal_tax.iter().map(|(year, _, _)| *year).collect();
    let mut accounting_common: Vec<(i32, f64, f64)> = accounting
        .iter()
        .filter(|(year, _, _)| tax_years.contains(year))
        .copied()
        .collect();
    accounting_common.sort_by_key(|(year, _, _)| *year);
    let market_common: Vec<(i32, i32)> = market_yields
        .iter()
        .filter(|(year, _)| tax_years.contains(year))
        .copied()
        .collect();
    let rated_common: Vec<(i32, i32)> = rated_spreads
        .iter()
        .filter(|(year, _)| tax_years.contains(year))
        .copied()
        .collect();

    let (cost_of_debt_bps, cost_of_debt_source, valid_debt_periods, average_debt_dollars) =
        if let Some((year, rate)) = market_common.last().copied() {
            (
                rate,
                WaccFieldSource::MarketYield,
                vec![year],
                total_debt as f64,
            )
        } else if let Some((year, rate)) = rated_common.last().copied() {
            (
                rate,
                WaccFieldSource::RatedOrSyntheticSpread,
                vec![year],
                total_debt as f64,
            )
        } else if !accounting_common.is_empty() {
            // Resolve one annual rate per fiscal period. Summing several
            // years of interest and dividing by one average debt would
            // multiply the cost of debt by the number of years. Each annual
            // observation instead uses the average of the current and prior
            // fiscal closing debt, then the median annual rate is selected.
            let annual_rates: Vec<(i32, i32, f64)> = accounting_common
                .iter()
                .enumerate()
                .map(|(index, (year, debt, interest))| {
                    let prior_debt = accounting_common[..index]
                        .iter()
                        .rev()
                        .find(|(prior_year, _, _)| prior_year < year)
                        .map(|(_, prior_debt, _)| *prior_debt)
                        .unwrap_or(*debt);
                    let average_debt = (*debt + prior_debt) / 2.0;
                    let rate = ((interest / average_debt) * 10_000.0).round() as i32;
                    (*year, rate, average_debt)
                })
                .collect();
            if annual_rates
                .iter()
                .any(|(_, rate, _)| !(1..=5_000).contains(rate))
            {
                return Err(
                    "fcff unavailable: aligned interest/debt implies invalid cost of debt".into(),
                );
            }
            let periods = annual_rates.iter().map(|(year, _, _)| *year).collect();
            let mut rates = annual_rates
                .iter()
                .map(|(_, rate, _)| *rate)
                .collect::<Vec<_>>();
            rates.sort_unstable();
            let rate = rates[rates.len() / 2];
            let average_debt = annual_rates.iter().map(|(_, _, debt)| *debt).sum::<f64>()
                / annual_rates.len() as f64;
            (
                rate,
                if provider_source.to_ascii_lowercase().contains("yahoo") {
                    WaccFieldSource::YahooAlignedInterestOverDebt
                } else {
                    WaccFieldSource::InterestOverAverageDebt
                },
                periods,
                average_debt,
            )
        } else {
            // Refusing here terminates the FCFF path for this issuer rather than
            // degrading to a lower rung: `edgar.rs` supplies `None` for both the
            // market yield and the rated/synthetic spread, and no producer for
            // either exists anywhere in the tree. This is the one terminal
            // message for every way the fit can come up empty -- no accounting
            // years at all, or every accounting year dropped for net basis --
            // because the caller cannot act on the two differently: either way
            // there is no gross interest-expense evidence left to fit. Channel
            // refusal is issuer blackout, and that is the honest outcome -- the
            // alternative is to synthesise a spread from an assumption, which is
            // choosing an estimator to keep a number publishing.
            return Err(
                "fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods"
                    .into(),
            );
        };

    let tax_source = [
        WaccFieldSource::TaxReconciliation,
        WaccFieldSource::JurisdictionStatutory,
        WaccFieldSource::DomicileTaxProxy,
    ]
    .into_iter()
    .find(|source| {
        marginal_tax
            .iter()
            .any(|(year, _, candidate)| candidate == source && valid_debt_periods.contains(year))
    });
    let Some(tax_source) = tax_source else {
        return Err(
            "fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources"
                .into(),
        );
    };

    let valid_tax_periods: Vec<i32> = marginal_tax
        .iter()
        .filter(|(year, _, source)| *source == tax_source && valid_debt_periods.contains(year))
        .map(|(year, _, _)| *year)
        .collect();
    let Some((_, marginal_tax_bps, _)) = marginal_tax
        .iter()
        .rev()
        .find(|(year, _, source)| *source == tax_source && valid_debt_periods.contains(year))
        .copied()
    else {
        return Err("fcff unavailable: tax source has no aligned fiscal periods".into());
    };

    let period_count = valid_debt_periods
        .iter()
        .filter(|year| valid_tax_periods.contains(year))
        .count();
    // Two aligned fiscal periods of interest/debt + non-proxy tax is enough for
    // solid rate quality when market rf is live. Three remains preferred depth;
    // a single period stays provisional.
    let quality = if period_count >= 2 && !matches!(tax_source, WaccFieldSource::DomicileTaxProxy) {
        EvidenceQuality::Solid
    } else if period_count >= 1 {
        EvidenceQuality::Provisional
    } else {
        return Err("fcff unavailable: no common valid debt and marginal-tax period".into());
    };

    let mut reasons = vec![
        format!("cost_of_debt_source={}", cost_of_debt_source.as_str()),
        format!("marginal_tax_source={}", tax_source.as_str()),
        format!("rate_quality={}", quality.as_str()),
        format!("aligned_debt_periods={}", join_years(&valid_debt_periods)),
        format!("aligned_tax_periods={}", join_years(&valid_tax_periods)),
    ];
    reasons.push(format!(
        "period_intersection=common_fiscal_years:{}",
        period_count
    ));

    Ok(Some(ResolvedRateInputs {
        cost_of_debt_bps,
        cost_of_debt_source,
        marginal_tax_bps,
        marginal_tax_source: tax_source,
        quality,
        valid_debt_periods,
        valid_tax_periods,
        average_debt_dollars,
        reasons,
    }))
}

fn join_years(years: &[i32]) -> String {
    years
        .iter()
        .map(ToString::to_string)
        .collect::<Vec<_>>()
        .join(",")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn point(year: i32, debt: Option<f64>, interest: Option<f64>, tax: Option<i32>) -> FcfPoint {
        FcfPoint::new(year, 100.0)
            .with_operating_drivers(120.0, 20.0, 1_000.0, interest, Some(2_100))
            .with_rate_resolution_inputs(debt, tax, None, None)
    }

    /// A year whose interest is written straight into the field instead of
    /// through `with_operating_drivers`.
    ///
    /// Every other test in this file builds its input through `point`, and that
    /// setter carried a blanket `.map(f64::abs)` until this wave removed it — so
    /// the `interest < 0.0` path below had never executed anywhere, in
    /// production or in a test. Writing the field directly keeps this test
    /// exercising the branch on its own terms: if a future change re-installs an
    /// absolute value on the way into `FcfPoint`, this test still feeds
    /// `resolve_rate_inputs` a negative and still measures what it claims to.
    ///
    /// Carries no basis (`interest_is_net_basis` stays `None`); tests below that
    /// need a net-basis year chain `.with_interest_basis(Some(true))` onto the
    /// result explicitly, so the concept declaration is visible at the call site
    /// rather than folded into this helper's default.
    fn point_with_net_interest(year: i32, debt: f64, interest: f64) -> FcfPoint {
        let mut point = point(year, Some(debt), None, Some(2_100));
        point.interest_expense_dollars = Some(interest);
        point
    }

    #[test]
    fn missing_debt_is_not_zero() {
        let error = resolve_rate_inputs(&[point(2023, None, Some(10.0), Some(2_100))], None, 430)
            .unwrap_err();
        assert!(error.contains("missing; missing debt is not zero"));
    }

    #[test]
    fn explicit_zero_debt_is_not_applicable() {
        assert!(
            resolve_rate_inputs(&[point(2023, Some(0.0), None, None)], Some(0), 430)
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn positive_interest_with_zero_debt_is_inconsistent() {
        let error = resolve_rate_inputs(
            &[point(2023, Some(0.0), Some(10.0), Some(2_100))],
            Some(0),
            430,
        )
        .unwrap_err();
        assert!(error.contains("provider inconsistency"));
    }

    #[test]
    fn aligned_accounting_evidence_is_provisional_or_solid() {
        let history = vec![
            point(2021, Some(100.0), Some(5.0), Some(2_100)),
            point(2022, Some(110.0), Some(5.5), Some(2_000)),
            point(2023, Some(120.0), Some(6.0), Some(1_900)),
        ];
        let resolved = resolve_rate_inputs(&history, Some(120), 430)
            .unwrap()
            .unwrap();
        assert_eq!(resolved.quality, EvidenceQuality::Solid);
        assert_eq!(
            resolved.cost_of_debt_source,
            WaccFieldSource::InterestOverAverageDebt
        );
        assert_eq!(resolved.cost_of_debt_bps, 522);
    }

    #[test]
    fn market_yield_precedes_aligned_accounting() {
        let history = vec![
            point(2021, Some(100.0), Some(5.0), Some(2_100)),
            point(2022, Some(110.0), Some(5.5), Some(2_000)),
            point(2023, Some(120.0), Some(6.0), Some(1_900)).with_rate_resolution_inputs(
                Some(120.0),
                Some(1_900),
                Some(700),
                None,
            ),
        ];
        let resolved = resolve_rate_inputs(&history, Some(120), 430)
            .unwrap()
            .unwrap();
        assert_eq!(resolved.cost_of_debt_source, WaccFieldSource::MarketYield);
        assert_eq!(resolved.cost_of_debt_bps, 700);
        assert_eq!(resolved.valid_debt_periods, vec![2023]);
    }

    #[test]
    fn rated_spread_precedes_aligned_accounting() {
        let history = vec![
            point(2021, Some(100.0), Some(5.0), Some(2_100)),
            point(2022, Some(110.0), Some(5.5), Some(2_000)),
            point(2023, Some(120.0), Some(6.0), Some(1_900)).with_rate_resolution_inputs(
                Some(120.0),
                Some(1_900),
                None,
                Some(250),
            ),
        ];
        let resolved = resolve_rate_inputs(&history, Some(120), 430)
            .unwrap()
            .unwrap();
        assert_eq!(
            resolved.cost_of_debt_source,
            WaccFieldSource::RatedOrSyntheticSpread
        );
        assert_eq!(resolved.cost_of_debt_bps, 680);
    }

    #[test]
    fn no_tax_does_not_get_a_default() {
        let error =
            resolve_rate_inputs(&[point(2021, Some(100.0), Some(5.0), None)], Some(100), 430)
                .unwrap_err();
        assert!(error.contains("marginal tax is unavailable"));
    }

    #[test]
    fn unlabelled_marginal_tax_is_not_used() {
        let error = resolve_rate_inputs(
            &[point(2021, Some(100.0), Some(5.0), Some(2_100))
                .with_marginal_tax_source(WaccFieldSource::Unavailable)],
            Some(100),
            430,
        )
        .unwrap_err();
        assert!(error.contains("marginal tax is unavailable"));
    }

    #[test]
    fn debt_and_tax_must_share_fiscal_periods() {
        let history = vec![
            point(2021, Some(100.0), Some(5.0), None),
            point(2022, Some(110.0), Some(5.5), None),
            point(2023, None, Some(5.5), Some(2_100)),
        ];
        let error = resolve_rate_inputs(&history, Some(110), 430).unwrap_err();
        assert!(error.contains("no aligned") || error.contains("marginal tax"));
    }

    #[test]
    fn tax_reconciliation_precedes_jurisdiction_proxy_and_is_provisional_when_sparse() {
        let history: Vec<_> = (2021..=2023)
            .map(|year| {
                point(year, Some(100.0), Some(8.0), Some(2_100))
                    .with_marginal_tax_source(WaccFieldSource::JurisdictionStatutory)
            })
            .map(|point| {
                if point.year == 2023 {
                    point
                        .with_rate_resolution_inputs(Some(100.0), Some(2_400), None, None)
                        .with_marginal_tax_source(WaccFieldSource::TaxReconciliation)
                } else {
                    point
                }
            })
            .collect();
        let resolved = resolve_rate_inputs(&history, Some(100), 430)
            .unwrap()
            .expect("non-zero debt");
        assert_eq!(
            resolved.marginal_tax_source,
            WaccFieldSource::TaxReconciliation
        );
        assert_eq!(resolved.marginal_tax_bps, 2_400);
        assert_eq!(resolved.quality, EvidenceQuality::Provisional);
        assert!(resolved
            .reasons
            .iter()
            .any(|reason| reason == "marginal_tax_source=tax_reconciliation"));
    }

    // ── T2.7: the accounting channel drops a year on basis (R-24) ───────────
    //
    // The two tests immediately below are rewrites, not new coverage: they
    // used to pin rule (A) -- an issuer-wide refusal keyed on the *sign* of a
    // negative interest year. R-20/R-23's measurement falsified (A) as an
    // approximation (`FN = 122` issuer-years / 3 issuers it could never see,
    // e.g. BKR, net every filed year and never once negative) and Juan ruled
    // rule (D) -- a per-year drop keyed on the *basis* of the winning concept
    // -- the T2.7 contract in `ORCHESTRATOR-RULINGS.md` R-24. Per R-24.3 this
    // is the one case where changing a test is not weakening it: the coverage
    // count does not fall (both tests below still exist, asserting the new
    // contract over the same fixtures they used before), and each assertion
    // below is strictly more specific than the outcome-only string match it
    // replaces -- it names the dropped year, states which concept it stands
    // in for, and checks the surviving fit directly rather than an error
    // string. Two further tests below them are new: direct boundary coverage
    // for "an issuer whose fittable set empties" and "a net year that was
    // never fittable anyway," the two boundary conditions the two rewrites
    // above do not themselves exercise.

    /// Rewritten from `a_net_interest_year_refuses_the_accounting_channel_for_the_whole_issuer`.
    ///
    /// Same fixture, same three years. 2023 is written negative here on
    /// purpose, even though sign plays no part in the (D) contract, to make
    /// the point R-24.1 states explicitly -- *"the sign of the filed value is
    /// NOT part of the contract"* -- unmissable: dropping 2023 is driven
    /// entirely by `.with_interest_basis(Some(true))` below, standing in for a
    /// year whose winning concept was `InterestIncomeExpenseNet`, and 2021/2022
    /// remain fittable on their own gross years exactly as
    /// `aligned_accounting_evidence_is_provisional_or_solid` fits a rate from
    /// the same shape.
    #[test]
    fn a_net_basis_year_is_dropped_and_the_issuer_still_fits_on_its_remaining_years() {
        let history = vec![
            point_with_net_interest(2021, 100.0, 5.0),
            point_with_net_interest(2022, 110.0, 5.5),
            point_with_net_interest(2023, 120.0, -6.0).with_interest_basis(Some(true)),
        ];
        let resolved = resolve_rate_inputs(&history, Some(120), 430)
            .unwrap()
            .expect("2021 and 2022 remain fittable once 2023 is dropped for basis");
        assert_eq!(
            resolved.valid_debt_periods,
            vec![2021, 2022],
            "2023 (InterestIncomeExpenseNet) must be dropped from the fit; the issuer itself \
             must not be refused"
        );
    }

    /// Rewritten from `a_refused_channel_is_an_error_rather_than_an_absent_rate`.
    ///
    /// Same single-fiscal-year shape, but the interest value is now POSITIVE --
    /// the opposite of what the retired rule (A) needed to fire -- so a reader
    /// cannot mistake this for the sign rule reappearing under a new name. Only
    /// `.with_interest_basis(Some(true))` drives the refusal, standing in for a
    /// year whose winning concept is one of `INTEREST_EXPENSE`'s two negated
    /// qnames. `resolve_rate_inputs` returning `Err` rather than `Ok(None)` is
    /// still what makes the FCFF path go dark instead of quietly continuing on
    /// a weaker source; `Ok(None)` would be indistinguishable from the
    /// debt-free case.
    #[test]
    fn a_solely_net_basis_year_still_refuses_as_an_error_not_an_absent_rate() {
        let history =
            vec![point_with_net_interest(2024, 100.0, 1.0).with_interest_basis(Some(true))];
        let error = resolve_rate_inputs(&history, Some(100), 430).unwrap_err();
        assert!(
            error.contains("no aligned market yield, spread, or SEC interest/debt periods"),
            "a solitary net-basis year (2024, filed positive) must empty the fittable set and \
             refuse as a terminal error, not degrade to Ok(None): got {error}"
        );
    }

    /// New boundary test: an issuer that files a net concept in EVERY year that
    /// has debt has no gross year to fall back on, so dropping the net years
    /// empties the fittable set and the issuer loses the channel -- the rule
    /// R-24.1 says already exists (`accounting_common`), not a new one. Mirrors
    /// COR's real filing history (net 18/18 years, R-20.4) and BKR's (net in
    /// every filed year, R-23.3), both measured REFUSED under (D).
    #[test]
    fn an_issuer_net_basis_in_every_year_empties_the_fittable_set_and_is_refused() {
        let history = vec![
            point_with_net_interest(2021, 100.0, 5.0).with_interest_basis(Some(true)),
            point_with_net_interest(2022, 110.0, 5.5).with_interest_basis(Some(true)),
        ];
        let error = resolve_rate_inputs(&history, Some(110), 430).unwrap_err();
        assert!(
            error.contains("no aligned market yield, spread, or SEC interest/debt periods"),
            "every fittable year is net-basis, so the accounting channel must refuse the whole \
             issuer rather than fit a rate on years that never measured gross expense: got {error}"
        );
    }

    /// New boundary test: a net-basis year that carries no filed debt was never
    /// a fit candidate in the first place -- the pre-existing
    /// `point.total_debt_dollars?` guard already excludes it -- so dropping it
    /// for basis changes nothing observable. Mirrors the real pattern Probe H
    /// measured for ABBV 2011, COR 2008, TYL 2009 and YUM 2007
    /// (`ORCHESTRATOR-RULINGS.md` R-20.2 Table 4): every one of those trigger
    /// years reads `debt = n/a`.
    #[test]
    fn a_net_basis_year_with_no_filed_debt_was_never_fittable_and_changes_nothing() {
        let with_net_year = vec![
            point_with_net_interest(2011, 100.0, 5.0),
            point_with_net_interest(2012, 110.0, 5.5),
            point(2013, None, Some(-20.0), Some(2_100)).with_interest_basis(Some(true)),
        ];
        let without_net_year = vec![
            point_with_net_interest(2011, 100.0, 5.0),
            point_with_net_interest(2012, 110.0, 5.5),
        ];
        let with_resolved = resolve_rate_inputs(&with_net_year, Some(110), 430)
            .unwrap()
            .expect("2011 and 2012 remain fittable");
        let without_resolved = resolve_rate_inputs(&without_net_year, Some(110), 430)
            .unwrap()
            .expect("identical fit with the undebted net year simply absent");
        assert_eq!(
            with_resolved.cost_of_debt_bps, without_resolved.cost_of_debt_bps,
            "a net-basis year with no filed debt (2013) was never a fit candidate either way; \
             including or omitting it must resolve to the same rate"
        );
    }
}
