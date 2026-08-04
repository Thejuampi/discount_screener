//! Measurement instruments for the two assumptions the new Core is load-bearing on.
//!
//! These are **probes, not behaviour**. They answer "what does the data actually
//! look like" so the Core is specified against reality instead of against a
//! guess. That is why they live in the Shell as ignored network tests and carry
//! no `.feature` file: there is nothing here to specify, only something to find
//! out. Behaviour derived *from* what they find belongs in `valuation-core`,
//! specified by a row in an Examples table like everything else.
//!
//! Both are cheap and both can invalidate a chunk of the PRD:
//!
//! * **Probe A — analyst dispersion.** Inverse-variance fusion needs a *measured*
//!   variance for the Forward Channel. If the provider does not return a usable
//!   high/low/count spread for most names, the Forward Channel has no uncertainty
//!   and FR-12 has nothing to weight with.
//! * **Probe C — return on capital.** The retention charge `C = E(1 - g/r)` is
//!   the only place growth is priced, and `r` is hardcoded absent today, so the
//!   Core substitutes `r := w` and credits growth nothing for every issuer. This
//!   probe answers whether `r` is measurable at all from filed evidence, and —
//!   given three defensible estimators — which one the issuers' own realized
//!   reinvestment says is telling the truth.
//! * **Probe B — growth persistence.** The projection is an
//!   Ornstein-Uhlenbeck fade with `kappa = -ln(rho_1)`. If `rho_1` is small or
//!   noisy on real revenue series, the implied half-lives are short, the fade
//!   collapses toward the terminal rate almost immediately, and values move
//!   *down* — the opposite of what the undervaluation cluster needs. If it is
//!   too noisy to estimate at all, FR-16 must be replaced by a pooled prior,
//!   which is a constant wearing a hat.
//!
//! Run them with:
//!
//! ```text
//! cargo test --lib probe_analyst_dispersion_availability -- --ignored --nocapture
//! cargo test --lib probe_growth_persistence_rho1        -- --ignored --nocapture
//! cargo test --lib probe_return_on_capital_availability -- --ignored --nocapture
//! ```

/// The names the probes run over: the pinned screener cohort plus the four
/// anchors. Broad enough to speak about the cross-section, small enough to stay
/// polite to the providers.
#[cfg(test)]
const PROBE_COHORT: &[&str] = &[
    "DVN", "FIS", "AVY", "SW", "COF", "MPWR", "APH", "EME", "CHTR", "BKR", "INTU", "TER", "AVGO",
    "EPAM", "T", "GEHC", "DAL", "WDC", "GOOGL", "HPE", "CRM", "SLB", "EXE", "OMC", "PTC", "PG",
    "MSFT", "AMZN",
];

#[cfg(test)]
fn median(values: &mut [f64]) -> Option<f64> {
    if values.is_empty() {
        return None;
    }
    values.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let mid = values.len() / 2;
    Some(if values.len() % 2 == 0 {
        (values[mid - 1] + values[mid]) / 2.0
    } else {
        values[mid]
    })
}

/// Lag-1 autocorrelation of a series, or `None` when there is not enough
/// variation to speak about persistence at all.
#[cfg(test)]
fn lag_one_autocorrelation(series: &[f64]) -> Option<f64> {
    if series.len() < 3 {
        return None;
    }
    let n = series.len() as f64;
    let mean = series.iter().sum::<f64>() / n;
    let denominator: f64 = series.iter().map(|value| (value - mean).powi(2)).sum();
    if denominator <= f64::EPSILON {
        return None;
    }
    let numerator: f64 = series
        .windows(2)
        .map(|pair| (pair[0] - mean) * (pair[1] - mean))
        .sum();
    Some(numerator / denominator)
}

/// One issuer-year carrying every term a return on capital is measured from.
#[cfg(test)]
struct CapitalYear {
    year: i32,
    /// `(pretax income + interest expense) * (1 - marginal tax)` — the return the
    /// whole capital base earned, before any of it is paid out to lenders.
    nopat: f64,
    /// `book equity + total debt` — the capital that return was earned on.
    invested_capital: f64,
    /// What the adapter feeds the Core today, for the same year.
    free_cash_flow: f64,
}

/// How many years each qname in a driver independently accounts for.
///
/// `select_one_equivalent` merges its qnames in declared order and only fills
/// gaps, so the first qname with a non-zero count is the head of the series and
/// the rest are tail coverage. Reported per issuer because a precedence bug is
/// invisible in the merged series — it looks like a number, just the wrong one.
#[cfg(test)]
fn qname_coverage(
    facts: &serde_json::Value,
    driver: crate::sec_driver_normalization_policy_generated::DriverOperator,
) -> Vec<(&'static str, usize)> {
    (0..driver.qnames.len())
        .map(|index| {
            let single = crate::sec_driver_normalization_policy_generated::DriverOperator {
                qnames: &driver.qnames[index..=index],
                unit: driver.unit,
                period_shape: driver.period_shape,
                operation: driver.operation,
            };
            (
                driver.qnames[index],
                crate::edgar::extract_driver_annual(facts, single).len(),
            )
        })
        .filter(|(_, years)| *years > 0)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn autocorrelation_of_a_flat_series_is_undefined_rather_than_zero() {
        assert_eq!(lag_one_autocorrelation(&[5.0, 5.0, 5.0, 5.0]), None);
    }

    #[test]
    fn autocorrelation_of_a_persistent_series_is_positive() {
        let rising = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0];
        assert!(lag_one_autocorrelation(&rising).is_some_and(|rho| rho > 0.0));
    }

    #[test]
    fn autocorrelation_of_an_alternating_series_is_negative() {
        let alternating = [1.0, -1.0, 1.0, -1.0, 1.0, -1.0];
        assert!(lag_one_autocorrelation(&alternating).is_some_and(|rho| rho < 0.0));
    }

    /// Probe A. Does the provider actually return analyst dispersion?
    ///
    /// Reports, per name, whether a usable `low < mean < high` triple and an
    /// analyst count arrived, and what relative spread it implies. Asserts
    /// nothing about the answer — the point is to find out, and a threshold
    /// invented before the measurement would just be another guess.
    #[test]
    #[ignore = "network: Yahoo forward forecast dispersion probe; diagnostic only"]
    fn probe_analyst_dispersion_availability() {
        let yahoo = crate::fetcher::YahooClient::new().expect("Yahoo client");
        let observed_epoch_day = (std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock")
            .as_secs()
            / 86_400) as i64;

        let mut usable_triple = 0usize;
        let mut usable_count = 0usize;
        let mut fetched = 0usize;
        let mut relative_spreads: Vec<f64> = Vec::new();
        let mut analyst_counts: Vec<f64> = Vec::new();

        println!("=== PROBE A: analyst dispersion availability ===");
        println!(
            "{:<7} {:>9} {:>9} {:>9} {:>6} {:>10}",
            "symbol", "eps_low", "eps_mean", "eps_high", "n", "spread/mean"
        );
        for &symbol in PROBE_COHORT {
            let evidence = match yahoo.fetch_forward_forecast(symbol, observed_epoch_day) {
                Ok(evidence) => evidence,
                Err(error) => {
                    println!("{symbol:<7} fetch failed: {error:?}");
                    continue;
                }
            };
            fetched += 1;
            let triple = (
                evidence.eps_low_cents,
                evidence.eps_mean_cents,
                evidence.eps_high_cents,
            );
            let spread = match triple {
                (Some(low), Some(mean), Some(high)) if low < high && mean != 0 => {
                    usable_triple += 1;
                    let relative = (high - low) as f64 / (mean as f64).abs();
                    relative_spreads.push(relative);
                    Some(relative)
                }
                _ => None,
            };
            if let Some(count) = evidence.analyst_count.filter(|count| *count >= 3) {
                usable_count += 1;
                analyst_counts.push(count as f64);
            }
            println!(
                "{symbol:<7} {:>9} {:>9} {:>9} {:>6} {:>10}",
                triple.0.map_or("-".into(), |v| v.to_string()),
                triple.1.map_or("-".into(), |v| v.to_string()),
                triple.2.map_or("-".into(), |v| v.to_string()),
                evidence.analyst_count.map_or("-".into(), |v| v.to_string()),
                spread.map_or("-".into(), |v| format!("{v:.3}")),
            );
        }

        println!();
        println!(
            "fetched={fetched}/{}  usable_low_mean_high={usable_triple}  analyst_count>=3: {usable_count}",
            PROBE_COHORT.len()
        );
        println!(
            "median relative spread (high-low)/|mean| = {:?}",
            median(&mut relative_spreads).map(|v| format!("{v:.3}"))
        );
        println!(
            "median analyst count = {:?}",
            median(&mut analyst_counts).map(|v| format!("{v:.1}"))
        );
        println!(
            "VERDICT: the Forward Channel can carry a measured variance for {usable_triple} of {} names.",
            PROBE_COHORT.len()
        );
        println!(
            "NOTE: converting a high/low range to a standard deviation needs to know what the\n\
             range represents (full range vs a quantile). That is PRD Open Question 1 and this\n\
             probe does not settle it — it only establishes whether a range exists to convert."
        );
    }

    /// Probe C. Is a return on capital measurable, and which estimator is true?
    ///
    /// Two questions in one pass, because they need the same evidence.
    ///
    /// **Availability.** `r` needs pretax income, interest, a marginal rate,
    /// book equity and debt in the same filed year. Reports how many issuers
    /// have three such years, how many issuer-years carry a non-positive capital
    /// base, which qname each of the two new drivers actually resolved to, and
    /// how far the NOPAT base sits from the FCFF base the adapter feeds today.
    ///
    /// **Which estimator.** Three are defensible and they disagree most on
    /// exactly the issuers that matter — buyback-heavy names whose book capital
    /// has stopped tracking economic capital. Rather than choose on taste, the
    /// probe uses the identity the model already rests on, `g = b * r`: each
    /// candidate implies a reinvestment rate `b = g / r`, and the issuer filed
    /// what it actually reinvested. The candidate whose implied `b` sits closest
    /// to the realized one is the candidate the retention charge should use.
    ///
    /// Asserts nothing. The answer decides the adapter; a threshold invented
    /// before the measurement would just be the guess it replaces.
    #[test]
    #[ignore = "network: SEC return-on-capital availability probe; diagnostic only"]
    fn probe_return_on_capital_availability() {
        use crate::sec_driver_normalization_policy_generated::{
            INTEREST_EXPENSE, STOCKHOLDERS_EQUITY,
        };

        let edgar = crate::edgar::edgar_client();
        let cik_map = crate::edgar::fetch_cik_map(&edgar).expect("SEC CIK map");

        let mut issuers_with_enough = 0usize;
        let mut non_positive_capital_years = 0usize;
        let mut names_with_a_capital_deficit: Vec<String> = Vec::new();
        let mut base_ratios: Vec<f64> = Vec::new();
        let mut book_gaps: Vec<f64> = Vec::new();
        let mut slope_gaps: Vec<f64> = Vec::new();

        println!("=== PROBE C: return on capital — availability ===");
        println!("per-term year counts, so a collapse names the term that caused it");
        println!(
            "{:<7} {:>4} {:>5} {:>5} {:>5} {:>5} {:>5} {:>4} {:>5} {:>11} {:>11} {:>9} {:>10}",
            "symbol",
            "yrs",
            "ptax",
            "equit",
            "debt",
            "intr",
            "mtax",
            "cap",
            "IC<=0",
            "NOPAT $B",
            "IC $B",
            "ROIC bps",
            "NOPAT/FCFF"
        );

        let mut estimator_rows: Vec<String> = Vec::new();
        let mut resolution_rows: Vec<String> = Vec::new();

        for &symbol in PROBE_COHORT {
            let Some(&cik) = cik_map.get(symbol) else {
                println!("{symbol:<7} no CIK");
                continue;
            };
            let Ok(Some(history)) = crate::edgar::fetch_fcf_history(&edgar, symbol, cik) else {
                println!("{symbol:<7} no history");
                continue;
            };

            // Which qname each new driver actually resolved to. A dimensional
            // fact leaking in from the statement of changes in equity, or a net
            // interest *income* line resolving as interest expense, both read as
            // a plausible number in the merged series.
            if let Ok(facts) = crate::edgar::fetch_company_facts(&edgar, symbol, cik) {
                let describe = |coverage: Vec<(&'static str, usize)>| {
                    if coverage.is_empty() {
                        "none".to_string()
                    } else {
                        coverage
                            .iter()
                            .map(|(qname, years)| format!("{qname}:{years}"))
                            .collect::<Vec<_>>()
                            .join(" ")
                    }
                };
                resolution_rows.push(format!(
                    "{symbol:<7} equity   {}",
                    describe(qname_coverage(&facts, STOCKHOLDERS_EQUITY))
                ));
                resolution_rows.push(format!(
                    "{symbol:<7} interest {}",
                    describe(qname_coverage(&facts, INTEREST_EXPENSE))
                ));
            }

            let count = |present: fn(&crate::dcf_model::FcfPoint) -> bool| {
                history.iter().filter(|point| present(point)).count()
            };
            let terms = [
                count(|point| point.pretax_income_dollars.is_some()),
                count(|point| point.stockholders_equity_dollars.is_some()),
                count(|point| point.total_debt_dollars.is_some()),
                count(|point| point.interest_expense_dollars.is_some()),
                count(|point| point.marginal_tax_bps.is_some()),
            ];

            let mut years: Vec<CapitalYear> = history
                .iter()
                .filter_map(|point| {
                    let pretax = point.pretax_income_dollars?;
                    let equity = point.stockholders_equity_dollars?;
                    let debt = point.total_debt_dollars?;
                    let interest = point.interest_expense_dollars?;
                    let marginal_tax = point.marginal_tax_bps? as f64 / 10_000.0;
                    Some(CapitalYear {
                        year: point.year,
                        nopat: (pretax + interest) * (1.0 - marginal_tax),
                        invested_capital: equity + debt,
                        free_cash_flow: point.value_dollars,
                    })
                })
                .collect();
            years.sort_by_key(|year| year.year);

            let deficits = years
                .iter()
                .filter(|year| year.invested_capital <= 0.0)
                .count();
            non_positive_capital_years += deficits;
            if deficits > 0 {
                names_with_a_capital_deficit.push(format!("{symbol}({deficits})"));
            }
            if years.len() >= 3 {
                issuers_with_enough += 1;
            }

            let usable: Vec<&CapitalYear> = years
                .iter()
                .filter(|year| year.invested_capital > 0.0)
                .collect();
            let (Some(first), Some(last)) = (usable.first(), usable.last()) else {
                println!(
                    "{symbol:<7} {:>4} {:>5} {:>5} {:>5} {:>5} {:>5} {:>4} {:>5} {:>11} {:>11} {:>9} {:>10}",
                    history.len(),
                    terms[0],
                    terms[1],
                    terms[2],
                    terms[3],
                    terms[4],
                    years.len(),
                    deficits,
                    "-",
                    "-",
                    "-",
                    "-"
                );
                continue;
            };

            let total_nopat: f64 = usable.iter().map(|year| year.nopat).sum();
            let total_free_cash_flow: f64 = usable.iter().map(|year| year.free_cash_flow).sum();
            let latest_roic_bps = last.nopat / last.invested_capital * 10_000.0;
            let base_ratio = if total_free_cash_flow.abs() > 0.0 {
                let ratio = total_nopat / total_free_cash_flow;
                base_ratios.push(ratio);
                format!("{ratio:.2}x")
            } else {
                "-".into()
            };
            println!(
                "{symbol:<7} {:>4} {:>5} {:>5} {:>5} {:>5} {:>5} {:>4} {:>5} {:>11.2} {:>11.2} {:>9.0} {:>10}",
                history.len(),
                terms[0],
                terms[1],
                terms[2],
                terms[3],
                terms[4],
                years.len(),
                deficits,
                last.nopat / 1e9,
                last.invested_capital / 1e9,
                latest_roic_bps,
                base_ratio
            );

            // --- the estimator question -------------------------------------
            if usable.len() < 3 {
                continue;
            }
            let span = (last.year - first.year) as f64;
            // Realized earnings growth, in the same continuous units the Core's
            // projection uses. Only defined when both ends are profitable; a
            // negative NOPAT has no logarithm and no growth rate.
            let realized_growth = (first.nopat > 0.0 && last.nopat > 0.0 && span > 0.0)
                .then(|| (last.nopat / first.nopat).ln() / span);
            // What the issuer actually reinvested: the capital base grew by this
            // fraction of the earnings it produced over the same span.
            let realized_reinvestment =
                (total_nopat.abs() > 0.0).then(|| (last.invested_capital - first.invested_capital) / total_nopat);

            let average_book = usable
                .iter()
                .map(|year| year.nopat / year.invested_capital)
                .sum::<f64>()
                / usable.len() as f64;
            let pairs: Vec<(f64, f64)> = usable
                .iter()
                .map(|year| (year.invested_capital, year.nopat))
                .collect();
            let marginal =
                crate::valuation_core_adapter::least_squares(&pairs).map(|fit| fit.slope);
            let implied = match (realized_growth, realized_reinvestment) {
                (Some(growth), Some(reinvestment)) if reinvestment.abs() > 0.0 => {
                    Some(growth / reinvestment)
                }
                _ => None,
            };

            // Each candidate implies b = g / r. The realized b is filed. The gap
            // is what decides, and it needs no market price and no threshold.
            let gap = |candidate: Option<f64>| match (candidate, realized_growth, realized_reinvestment) {
                (Some(rate), Some(growth), Some(reinvestment)) if rate > 0.0 => {
                    let distance = (growth / rate - reinvestment).abs();
                    Some(distance)
                }
                _ => None,
            };
            let book_gap = gap(Some(average_book));
            let slope_gap = gap(marginal);
            // Deliberately *not* scored. `implied` is defined as `g / b`, so its
            // own `g / r` returns `b` by construction and its gap is identically
            // zero for every issuer. It measures nothing; it is printed because
            // the return it demands is worth reading next to the two that were
            // estimated independently.
            book_gaps.extend(book_gap);
            slope_gaps.extend(slope_gap);

            let show = |value: Option<f64>| value.map_or("-".into(), |v| format!("{v:.3}"));
            estimator_rows.push(format!(
                "{symbol:<7} {:>4} {:>8} {:>8} {:>9} {:>9} {:>9} {:>8} {:>8}",
                usable.len(),
                show(realized_growth),
                show(realized_reinvestment),
                format!("{average_book:.3}"),
                show(marginal),
                show(implied),
                show(book_gap),
                show(slope_gap),
            ));
        }

        println!();
        println!("=== driver resolution: which qname each series actually came from ===");
        for row in &resolution_rows {
            println!("{row}");
        }

        println!();
        println!("=== PROBE C: which estimator reproduces realized reinvestment ===");
        println!(
            "{:<7} {:>4} {:>8} {:>8} {:>9} {:>9} {:>9} {:>8} {:>8}",
            "symbol", "n", "g", "b", "r_book", "r_slope", "r_impl", "|db|bk", "|db|sl"
        );
        for row in &estimator_rows {
            println!("{row}");
        }

        println!();
        println!(
            "issuers with >=3 complete years: {issuers_with_enough}/{}",
            PROBE_COHORT.len()
        );
        println!(
            "issuer-years with IC <= 0: {non_positive_capital_years}  [{}]",
            names_with_a_capital_deficit.join(" ")
        );
        println!(
            "median NOPAT/FCFF = {:?}   (the size of the base change, before it lands)",
            median(&mut base_ratios.clone()).map(|v| format!("{v:.2}x"))
        );
        println!(
            "median |implied b - realized b|:  book {:?}   slope {:?}   (r_impl is not scored: its\n\
             gap is identically zero by construction, since it is defined as g/b)",
            median(&mut book_gaps.clone()).map(|v| format!("{v:.3}")),
            median(&mut slope_gaps.clone()).map(|v| format!("{v:.3}")),
        );
        println!(
            "READ THIS AS: the smaller median gap is the estimator whose retention charge is\n\
             consistent with what these issuers actually did with their earnings. Where the two are\n\
             close, prefer the higher r: an overstated return is bounded (1 - g/r -> 1, the charge\n\
             merely vanishes) while an understated or negative one is unbounded (negative value, or\n\
             refusal through the Core's r <= 0 guard). Market price is not consulted here and must\n\
             not be used to break the tie."
        );
    }

    /// Probe B. What does growth persistence actually look like?
    ///
    /// Reports lag-1 autocorrelation of realized revenue growth per name, the
    /// implied mean-reversion half-life, and how noisy the estimate is at the
    /// sample sizes really available. Asserts nothing.
    #[test]
    #[ignore = "network: SEC revenue history persistence probe; diagnostic only"]
    fn probe_growth_persistence_rho1() {
        let edgar = crate::edgar::edgar_client();
        let cik_map = crate::edgar::fetch_cik_map(&edgar).expect("SEC CIK map");

        let mut rhos: Vec<f64> = Vec::new();
        let mut half_lives: Vec<f64> = Vec::new();
        let mut non_reverting = 0usize;
        let mut immediate_reversion = 0usize;

        println!("=== PROBE B: growth persistence (lag-1 autocorrelation of revenue growth) ===");
        println!(
            "{:<7} {:>4} {:>8} {:>10} {:>12}",
            "symbol", "n_g", "rho_1", "half_life", "se(rho_1)"
        );
        for &symbol in PROBE_COHORT {
            let Some(&cik) = cik_map.get(symbol) else {
                println!("{symbol:<7} no CIK");
                continue;
            };
            let Ok(Some(history)) = crate::edgar::fetch_fcf_history(&edgar, symbol, cik) else {
                println!("{symbol:<7} no history");
                continue;
            };
            let mut revenues: Vec<(i32, f64)> = history
                .iter()
                .filter_map(|point| point.revenue_dollars.map(|value| (point.year, value)))
                .filter(|(_, value)| *value > 0.0)
                .collect();
            revenues.sort_by_key(|(year, _)| *year);
            let growths: Vec<f64> = revenues
                .windows(2)
                .map(|pair| pair[1].1 / pair[0].1 - 1.0)
                .collect();

            let Some(rho) = lag_one_autocorrelation(&growths) else {
                println!("{:<7} {:>4} {:>8}", symbol, growths.len(), "n/a");
                continue;
            };
            rhos.push(rho);
            // kappa = -ln(rho) is only defined for rho in (0, 1). Outside it the
            // process either does not revert (rho >= 1) or reverts within one
            // period (rho <= 0); both are reported rather than clamped.
            let half_life = if rho > 0.0 && rho < 1.0 {
                let half = std::f64::consts::LN_2 / -rho.ln();
                half_lives.push(half);
                format!("{half:.2}y")
            } else if rho <= 0.0 {
                immediate_reversion += 1;
                "<=1 period".into()
            } else {
                non_reverting += 1;
                "no reversion".into()
            };
            let standard_error = 1.0 / (growths.len() as f64).sqrt();
            println!(
                "{:<7} {:>4} {:>8.3} {:>10} {:>12.3}",
                symbol,
                growths.len(),
                rho,
                half_life,
                standard_error
            );
        }

        println!();
        let sample = rhos.len();
        println!(
            "n={sample}  median rho_1 = {:?}",
            median(&mut rhos.clone()).map(|v| format!("{v:.3}"))
        );
        println!(
            "median implied half-life = {:?}  (only the {} names with 0 < rho < 1)",
            median(&mut half_lives.clone()).map(|v| format!("{v:.2}y")),
            half_lives.len()
        );
        println!(
            "rho <= 0 (reverts within one period): {immediate_reversion}   rho >= 1 (never reverts): {non_reverting}"
        );
        println!(
            "READ THIS AGAINST: the shipping engine fades over 5 years (10 for secular names).\n\
             A median half-life materially below that means the OU projection shortens horizons\n\
             and pushes values DOWN, against the undervaluation cluster the redesign exists to fix.\n\
             A median se(rho_1) near 0.3 means per-issuer kappa is not estimable and FR-17\n\
             shrinkage will pull nearly every name to the pooled prior -- a constant in disguise."
        );
    }
}
