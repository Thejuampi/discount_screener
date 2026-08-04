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
