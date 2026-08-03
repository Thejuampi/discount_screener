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

    let accounting: Vec<(i32, f64, f64)> = history
        .iter()
        .filter_map(|point| {
            let debt = point.total_debt_dollars?;
            let interest = point.interest_expense_dollars?;
            if !debt.is_finite() || !interest.is_finite() || debt < 0.0 || interest < 0.0 {
                return None;
            }
            if debt == 0.0 && interest > 0.0 {
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
}
