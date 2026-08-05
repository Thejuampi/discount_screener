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
//!   Core refuses rather than valuing growth at any line for every operating
//!   issuer (FR-29). This probe answers whether `r` is measurable at all from
//!   filed evidence, and — given three defensible estimators — which one the
//!   issuers' own realized reinvestment says is telling the truth.
//! * **Probe B — growth persistence.** The projection is an
//!   Ornstein-Uhlenbeck fade with `kappa = -ln(rho_1)`. If `rho_1` is small or
//!   noisy on real revenue series, the implied half-lives are short, the fade
//!   collapses toward the terminal rate almost immediately, and values move
//!   *down* — the opposite of what the undervaluation cluster needs. If it is
//!   too noisy to estimate at all, FR-16 must be replaced by a pooled prior,
//!   which is a constant wearing a hat.
//! * **Probe G — the published value under the corrected interest sign.** Wave 2b
//!   moves live published numbers on purpose, against a pre-registered set of six
//!   issuers. A wave that moves published numbers has to *measure* which ones and
//!   by how much; arguing it from the mechanism has been wrong three times on this
//!   one change. This probe runs each issuer twice through the real router — once
//!   with every interest year read as `|filed|`, which is exactly what the code
//!   published before the three `.abs()` sites were removed, and once with the
//!   history as this tree now reads it — and reports the published delta.
//!
//! Run them with:
//!
//! ```text
//! cargo test --lib probe_analyst_dispersion_availability -- --ignored --nocapture
//! cargo test --lib probe_growth_persistence_rho1        -- --ignored --nocapture
//! cargo test --lib probe_return_on_capital_availability -- --ignored --nocapture
//! cargo test --lib probe_facts_without_a_filing_date    -- --ignored --nocapture
//! cargo test --lib probe_published_value_under_the_corrected_interest_sign -- --ignored --nocapture
//! ```

#[cfg(test)]
use std::cmp::Ordering;

#[cfg(test)]
use chrono::Utc;

#[cfg(test)]
use valuation_core::{robust_centre, RobustCentre};

#[cfg(test)]
use crate::dcf_model::{
    classify_business, compute_with_params, BusinessClass, DcfAnalysis, FcfPoint, MarketParams,
};
#[cfg(test)]
use crate::driver_resolution::resolve_rate_inputs;
#[cfg(test)]
use crate::edgar::{
    accepted_annual_entries, edgar_client, extract_driver_annual, fetch_cik_map,
    fetch_company_facts, fetch_fcf_history, fetch_shares_outstanding, IsoDate,
};
#[cfg(test)]
use crate::engine::FundamentalSnapshot;
#[cfg(test)]
use crate::fetcher::{ForwardForecastFetchError, YahooClient};
#[cfg(test)]
use crate::operating_valuation::{OperatingModel, RouteStatus};
#[cfg(test)]
use crate::operating_valuation_runtime::{
    route_runtime_valuation, ForwardSourceFailure, RuntimeValuationInput,
};
#[cfg(test)]
use crate::quote_summary::ForwardForecastEvidence;
#[cfg(test)]
use crate::sec_driver_normalization_policy_generated::{
    DriverOperator, CURRENT_DEBT, DILUTED_AVERAGE_SHARES, INTEREST_EXPENSE, MARGINAL_TAX_REFERENCE,
    NON_CURRENT_DEBT, OPERATING_CASH_FLOW, PRETAX_INCOME, REVENUE, STOCKHOLDERS_EQUITY,
    TAX_EXPENSE, TOTAL_DEBT,
};
#[cfg(test)]
use crate::yahoo_session::is_rate_limit_error;

/// The names the probes run over: the pinned screener cohort plus the four
/// anchors. Broad enough to speak about the cross-section, small enough to stay
/// polite to the providers.
#[cfg(test)]
const PROBE_COHORT: &[&str] = &[
    "DVN", "FIS", "AVY", "SW", "COF", "MPWR", "APH", "EME", "CHTR", "BKR", "INTU", "TER", "AVGO",
    "EPAM", "T", "GEHC", "DAL", "WDC", "GOOGL", "HPE", "CRM", "SLB", "EXE", "OMC", "PTC", "PG",
    "MSFT", "AMZN",
];

/// The sample for the point-in-time coverage probe, named rather than left to
/// whoever runs it: the four valuation anchors, the issuers whose published
/// number the interest-sign wave moves, and the cohort names with the longest
/// filing histories — pre-1990 registrants, where a companyfacts entry filed
/// before the modern XBRL discipline is likeliest to be missing a field.
///
/// COF, DAL, CHTR and BKR are the interest-sign wave's named issuers; MPWR is
/// the one cohort member whose published value that wave was measured to move,
/// so leaving it out would sample everywhere except where the effect is.
#[cfg(test)]
const FILING_DATE_PROBE_SAMPLE: &[&str] = &[
    "PG", "GOOGL", "AMZN", "MSFT", "COF", "DAL", "CHTR", "BKR", "MPWR", "T", "SLB", "OMC", "AVY",
    "DVN", "TER", "EME", "APH",
];

/// The drivers the probe walks, with the label used in its table. Every driver
/// the valuation bridge reads from SEC facts, so a coverage hole cannot hide in
/// the one nobody counted.
#[cfg(test)]
const FILING_DATE_PROBE_DRIVERS: &[(&str, DriverOperator)] = &[
    ("ocf", OPERATING_CASH_FLOW),
    ("revenue", REVENUE),
    ("interest", INTEREST_EXPENSE),
    ("debt_total", TOTAL_DEBT),
    ("debt_current", CURRENT_DEBT),
    ("debt_noncurr", NON_CURRENT_DEBT),
    ("equity", STOCKHOLDERS_EQUITY),
    ("tax", TAX_EXPENSE),
    ("pretax", PRETAX_INCOME),
    ("shares", DILUTED_AVERAGE_SHARES),
    ("tax_reference", MARGINAL_TAX_REFERENCE),
];

/// One companyfacts entry that passed form, period-shape and frame admission but
/// will never become an annual value, and the field it is missing.
#[cfg(test)]
struct DroppedFact {
    driver: &'static str,
    qname: &'static str,
    end: String,
    filed: String,
    accession: String,
    reason: &'static str,
}

/// What the fail-closed rules refuse for one issuer, and what a point-in-time
/// read would see that a latest-only read cannot.
#[cfg(test)]
#[derive(Default)]
struct FilingDateCoverage {
    accepted: usize,
    no_filed: usize,
    unparseable_end: usize,
    no_accession: usize,
    disagreeing_vintages: usize,
    earliest_filed: Option<String>,
    dropped: Vec<DroppedFact>,
}

/// Count, for one issuer, every fact the fail-closed rules refuse and every
/// period end whose vintages disagree.
///
/// Nothing here judges: it reports what is in the filings. Column three is the
/// one that decides whether `as_of` can ever differ from `latest` on live data.
#[cfg(test)]
fn measure_filing_date_coverage(facts: &serde_json::Value) -> FilingDateCoverage {
    use std::collections::HashMap;

    let mut coverage = FilingDateCoverage::default();
    for (label, driver) in FILING_DATE_PROBE_DRIVERS {
        let mut values_by_period: HashMap<(&str, String), Vec<String>> = HashMap::new();
        for (qname, entry) in accepted_annual_entries(facts, driver) {
            coverage.accepted += 1;
            let end = entry["end"].as_str().unwrap_or("<absent>");
            let filed = entry["filed"].as_str();
            let accession = entry["accn"].as_str();
            if let Some(filed) = filed {
                coverage.earliest_filed = Some(match coverage.earliest_filed.take() {
                    Some(earliest) if earliest.as_str() <= filed => earliest,
                    _ => filed.to_owned(),
                });
            }

            let reason = if filed.is_none() {
                coverage.no_filed += 1;
                Some("no filed date")
            } else if IsoDate::parse(end).is_none() {
                coverage.unparseable_end += 1;
                Some("end will not parse")
            } else if accession.is_none_or(str::is_empty) {
                coverage.no_accession += 1;
                Some("no accession")
            } else {
                None
            };
            if let Some(reason) = reason {
                coverage.dropped.push(DroppedFact {
                    driver: label,
                    qname,
                    end: end.to_owned(),
                    filed: filed.unwrap_or("<absent>").to_owned(),
                    accession: accession.unwrap_or("<absent>").to_owned(),
                    reason,
                });
                continue;
            }
            // Values are compared as filed text: two vintages that print the
            // same digits are the same claim, and rounding one to f64 first
            // could merge two claims that differ in the last dollar.
            values_by_period
                .entry((qname, end.to_owned()))
                .or_default()
                .push(entry["val"].to_string());
        }
        coverage.disagreeing_vintages += values_by_period
            .into_values()
            .filter(|values| values.iter().any(|value| *value != values[0]))
            .count();
    }
    coverage
}

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
fn qname_coverage(facts: &serde_json::Value, driver: DriverOperator) -> Vec<(&'static str, usize)> {
    (0..driver.qnames.len())
        .map(|index| {
            let single = DriverOperator {
                qnames: &driver.qnames[index..=index],
                qname_signs: &driver.qname_signs[index..=index],
                unit: driver.unit,
                period_shape: driver.period_shape,
                operation: driver.operation,
            };
            (
                driver.qnames[index],
                extract_driver_annual(facts, single).len(),
            )
        })
        .filter(|(_, years)| *years > 0)
        .collect()
}

/// The four names whose published value must not move at all.
///
/// They are the reference points every other number in the table is read
/// against, so a move here is not a magnitude to be reported — it is a stop
/// condition (`plan.v6.md` pause trigger (a)/(a′)).
#[cfg(test)]
const VALUATION_ANCHORS: &[&str] = &["PG", "GOOGL", "AMZN", "MSFT"];

/// The issuers whose *interest series* the sign convention rewrites: every
/// `OperatingNonFinancial` S&P 500 name with at least one fiscal year whose
/// winning interest concept is one the contract negates.
///
/// Pinned as the measured result of the wide scan (`ORCHESTRATOR-RULINGS.md`
/// R-10) rather than re-derived here, so the published-effect measurement runs
/// over exactly the population the pre-registration was taken on. Re-deriving it
/// would answer a different question on a different set and silently lose the
/// comparison. NWS and NWSA are two share classes of one CIK and are both kept,
/// because both are published separately.
///
/// This is not a ticker special case: nothing in production reads it, every step
/// the probe takes is symbol-agnostic, and the probe asserts nothing about any
/// name on it.
#[cfg(test)]
const INTEREST_SIGN_AFFECTED_COHORT: &[&str] = &[
    "ABBV", "ADSK", "AXON", "CARR", "COR", "CPRT", "DDOG", "JKHY", "MPWR", "NKE", "NWS", "NWSA",
    "OTIS", "PAYX", "RMD", "ROL", "ROST", "TPR", "TTD", "TYL", "ULTA", "WSM", "XYZ", "YUM", "ZBRA",
];

/// The issuers whose interest series is net-basis while never negative -- the
/// population `INTEREST_SIGN_AFFECTED_COHORT` cannot reach, because that cohort
/// is a sign-detected scan (R-10) and these two never trip a sign test at all.
/// CHTR and BKR are R-24's own counterexample to keying this rule on sign rather
/// than basis: BKR is net in every filed year and never once negative.
///
/// This is a separate, additive cohort, not a widening of
/// `INTEREST_SIGN_AFFECTED_COHORT` -- that constant stays byte-identical because
/// R-13.1's numbers are measured against exactly that population. Chaining this
/// cohort in alongside it (`ORCHESTRATOR-RULINGS.md` R-24.2, R-24.4) is what puts
/// at least one observation on the ground rule (D) was written to change and
/// rule (A) could never reach -- without that, this probe can only ever exercise
/// the population where (A) and (D) agree, and would never be able to falsify a
/// regression specific to the basis-only branch.
///
/// Pinned by R-24.2: CHTR −2289c (513→708bps, a lane flip), BKR +3035c
/// (+7889bps, a lane flip to refused).
///
/// Not a ticker special case: nothing in production reads it, every step the
/// probe takes over it is symbol-agnostic, and the probe asserts nothing about
/// either name -- it is printed beside the registration exactly like every
/// other symbol in the loop.
#[cfg(test)]
const BASIS_ONLY_COHORT: &[&str] = &["CHTR", "BKR"];

/// Everything one operating-lane run needs.
///
/// The forward evidence and the market parameters are fetched once per issuer
/// and shared between the two runs, so the *only* thing that differs between the
/// before and after run is the interest reading. Two runs in one process also
/// means no market price moves between them, which a paired run across two trees
/// cannot promise.
#[cfg(test)]
struct GateRunInput<'a> {
    fundamentals: &'a FundamentalSnapshot,
    history: &'a [FcfPoint],
    market_price_cents: Option<i64>,
    market_params: &'a MarketParams,
    forward_evidence: Result<ForwardForecastEvidence, ForwardSourceFailure>,
    as_of_epoch_day: i64,
}

/// What the router decided, and what the screener would publish from it.
#[cfg(test)]
struct GateRun {
    /// The number the product publishes, derived by the same three-branch rule
    /// as `valuation_high_signal::recompute_member` (`:474-541`): the selected
    /// value, or on a disputed route the resolved lane falling back to whichever
    /// candidate exists, or nothing at all.
    published_base_cents: Option<i64>,
    fcff_candidate_cents: Option<i64>,
    lane: String,
    /// The after-tax interest add-back the FCFF model actually used, in bps of
    /// revenue. Carried because it is the one place the interest series is
    /// visible *inside* the model: if a rewritten history leaves this unchanged,
    /// the rewrite never reached the valuation, and saying so is a measurement
    /// rather than an inference from two equal published values.
    interest_add_back_margin_bps: Option<i32>,
}

/// Run one issuer through the real operating lane: FCFF model, then the runtime
/// router, then the value the screener would publish from it.
///
/// This reproduces the `OperatingNonFinancial` branch of the private
/// `valuation_high_signal::recompute_member` because that function is not
/// callable from here and widening it for a probe would be a production edit.
/// Only the fields this probe prints are carried over; the gate verdict, the
/// scale annotation and the note text are not, because no column reads them.
#[cfg(test)]
fn run_operating_lane(input: GateRunInput<'_>) -> GateRun {
    let mut fcff_failure: Option<String> = None;
    let mut analysis: Option<DcfAnalysis> = None;
    match compute_with_params(
        input.fundamentals,
        input.history,
        input.market_price_cents,
        input.market_params,
        PUBLISHED_VALUE_PROBE_SOURCE,
        false,
    ) {
        Ok(value) => analysis = Some(value),
        Err(error) => fcff_failure = Some(format!("fcff_compute:{error}")),
    }

    let envelope = route_runtime_valuation(RuntimeValuationInput {
        business_class: BusinessClass::OperatingNonFinancial,
        fundamentals: input.fundamentals,
        fcff_analysis: analysis.as_ref(),
        fcff_failure: fcff_failure.as_deref(),
        forward_evidence: input.forward_evidence,
        market_params: input.market_params,
        as_of_epoch_day: input.as_of_epoch_day,
        market_price_cents: input.market_price_cents,
    });
    let decision = envelope.decision;

    let fcff_candidate_cents = decision.fcff_candidate.intrinsic_value_cents;
    let published_base_cents = match decision.status {
        RouteStatus::Selected => decision.selected_value_cents,
        RouteStatus::Disputed => decision
            .selected_value_cents
            .or(fcff_candidate_cents)
            .or(decision.forward_candidate.intrinsic_value_cents),
        RouteStatus::Unavailable | RouteStatus::NotEligible => None,
    };
    let status = match decision.status {
        RouteStatus::Selected => "sel",
        RouteStatus::Disputed => "disp",
        RouteStatus::Unavailable => "unav",
        RouteStatus::NotEligible => "ineligible",
    };
    let selected = match decision.selected_model {
        Some(OperatingModel::FcffWacc) => "fcff",
        Some(OperatingModel::ForwardEarningsPower) => "fwd",
        None => "none",
    };

    GateRun {
        published_base_cents,
        fcff_candidate_cents,
        lane: format!("{status}:{selected}"),
        interest_add_back_margin_bps: analysis.and_then(|analysis| {
            analysis
                .diagnostics
                .normalized_after_tax_interest_margin_bps
        }),
    }
}

/// The provenance string the probe's own model runs carry, so a run of this
/// diagnostic can never be mistaken for a screener recompute in a log.
#[cfg(test)]
const PUBLISHED_VALUE_PROBE_SOURCE: &str = "published_value_sign_probe";

/// Whether this tree still un-negates the interest sign on the way into
/// `FcfPoint` — LD-1's setter site.
///
/// Asked of the setter directly, with a value only an `.abs()` could change, so
/// the answer is a fact about the code rather than an inference from a table of
/// zeroes. It settles the one question a per-issuer "did anything move" check
/// cannot: an unchanged published number means "absorbed" only if the change was
/// capable of arriving in the first place.
#[cfg(test)]
fn tree_preserves_the_interest_sign() -> bool {
    const PROBE_INTEREST_DOLLARS: f64 = -1.0;
    FcfPoint::new(2000, 0.0)
        .with_operating_drivers(0.0, 0.0, 1.0, Some(PROBE_INTEREST_DOLLARS), Some(0))
        .interest_expense_dollars
        == Some(PROBE_INTEREST_DOLLARS)
}

/// The history as the code read it *before* LD-1's three `.abs()` sites were
/// removed: every interest year as `|filed|`.
///
/// The pre-wave pipeline stored `|filed|` in the setter and both read sites
/// re-applied `.abs()` on top, so mapping the field through `f64::abs` here
/// reproduces the old published number exactly rather than approximating it.
#[cfg(test)]
fn history_as_published_before_the_sign_correction(history: &[FcfPoint]) -> Vec<FcfPoint> {
    history
        .iter()
        .cloned()
        .map(|mut point| {
            point.interest_expense_dollars = point.interest_expense_dollars.map(f64::abs);
            // `interest_is_net_basis` postdates this pre-wave reading entirely --
            // the legacy pipeline reconstructed here had no per-year basis
            // awareness, only the (now-abs'd) sign. Clearing it keeps this
            // "before" reconstruction faithful to what the accounting fit could
            // see at the time: under (D) the field, not the sign, drives
            // dropping, so leaving it populated would silently run today's rule
            // against a history meant to represent code that predates it.
            point.interest_is_net_basis = None;
            point
        })
        .collect()
}

/// How the accounting cost-of-debt channel resolves for one history: a fitted
/// rate, "not applicable" for a debt-free issuer, or a refusal with its reason.
#[cfg(test)]
fn cost_of_debt_channel(
    history: &[FcfPoint],
    fundamentals: &FundamentalSnapshot,
    rf_bps: i32,
) -> String {
    match resolve_rate_inputs(history, fundamentals.total_debt_dollars, rf_bps) {
        Ok(Some(resolved)) => format!("{}bps", resolved.cost_of_debt_bps),
        Ok(None) => "n/a".to_string(),
        Err(reason) => format!("REFUSED({})", reason.replace("fcff unavailable: ", "")),
    }
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

        let edgar = edgar_client();
        let cik_map = fetch_cik_map(&edgar).expect("SEC CIK map");

        let mut issuers_with_enough = 0usize;
        let mut non_positive_capital_years = 0usize;
        let mut names_with_a_capital_deficit: Vec<String> = Vec::new();
        let mut base_ratios: Vec<f64> = Vec::new();
        // Availability per candidate: every issuer whose gap resolved, whether
        // or not the other candidate also resolved for that issuer.
        let mut book_gaps: Vec<f64> = Vec::new();
        let mut slope_gaps: Vec<f64> = Vec::new();
        // The comparison population: only issuers where BOTH candidates
        // resolved, so the two medians read against the same names rather
        // than against two differently sized samples.
        let mut paired_gaps: Vec<(String, f64, f64)> = Vec::new();
        // Absence never becomes a fabricated statutory rate: an issuer-year
        // with no filed marginal rate has no NOPAT and does not enter the
        // measurement. Tracked so the cost of that refusal is reported, not
        // assumed.
        let mut dropped_for_missing_marginal_tax_total = 0usize;
        let mut dropped_for_missing_marginal_tax_by_issuer: Vec<String> = Vec::new();

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
            let Ok(Some(history)) = fetch_fcf_history(&edgar, symbol, cik) else {
                println!("{symbol:<7} no history");
                continue;
            };

            // Which qname each new driver actually resolved to. A dimensional
            // fact leaking in from the statement of changes in equity, or a net
            // interest *income* line resolving as interest expense, both read as
            // a plausible number in the merged series.
            if let Ok(facts) = fetch_company_facts(&edgar, symbol, cik) {
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

            let count = |present: fn(&FcfPoint) -> bool| {
                history.iter().filter(|point| present(point)).count()
            };
            let terms = [
                count(|point| point.pretax_income_dollars.is_some()),
                count(|point| point.stockholders_equity_dollars.is_some()),
                count(|point| point.total_debt_dollars.is_some()),
                count(|point| point.interest_expense_dollars.is_some()),
                count(|point| point.marginal_tax_bps.is_some()),
            ];

            let mut years: Vec<CapitalYear> = Vec::new();
            let mut dropped_for_missing_marginal_tax_here = 0usize;
            for point in &history {
                let (Some(pretax), Some(equity), Some(debt), Some(interest)) = (
                    point.pretax_income_dollars,
                    point.stockholders_equity_dollars,
                    point.total_debt_dollars,
                    point.interest_expense_dollars,
                ) else {
                    continue;
                };
                // An issuer-year with no filed marginal rate has no NOPAT and
                // does not enter the measurement -- absence never becomes a
                // fabricated statutory rate.
                let Some(marginal_tax_bps) = point.marginal_tax_bps else {
                    dropped_for_missing_marginal_tax_here += 1;
                    continue;
                };
                let marginal_tax = f64::from(marginal_tax_bps) / 10_000.0;
                years.push(CapitalYear {
                    year: point.year,
                    nopat: (pretax + interest) * (1.0 - marginal_tax),
                    invested_capital: equity + debt,
                    // The same FCFF the adapter feeds the Core: `point.value_dollars`
                    // is already `OCF - CapEx` (edgar.rs), with the after-tax
                    // interest add-back applied by the one function both call.
                    free_cash_flow: crate::valuation_core_adapter::after_tax_fcff(
                        point.value_dollars,
                        interest,
                        marginal_tax_bps,
                    ),
                });
            }
            years.sort_by_key(|year| year.year);
            dropped_for_missing_marginal_tax_total += dropped_for_missing_marginal_tax_here;
            if dropped_for_missing_marginal_tax_here > 0 {
                dropped_for_missing_marginal_tax_by_issuer
                    .push(format!("{symbol}({dropped_for_missing_marginal_tax_here})"));
            }

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
            // What the issuer actually reinvested, from the flow statements:
            // NOPAT is earnings before growth reinvestment and FCFF is what is
            // left after it, so their difference *is* the reinvestment, and the
            // ratio is the retention rate the identity `g = b * r` means.
            //
            // The previous reading, `dIC / sum(NOPAT)`, was measuring something
            // else. A capital base grows by retained earnings *and* by debt
            // raised, shares issued, and businesses bought, so it read b > 1 for
            // ten of twenty-one issuers — capital formation, not retention, and
            // the identity presumes internally funded growth.
            let reinvested: f64 = total_nopat - total_free_cash_flow;
            let realized_reinvestment = (total_nopat.abs() > 0.0).then(|| reinvested / total_nopat);
            // Kept alongside so the contaminated reading stays visible rather
            // than being quietly replaced.
            let capital_formation = (total_nopat.abs() > 0.0)
                .then(|| (last.invested_capital - first.invested_capital) / total_nopat);

            // The book candidate is the centre of the annual returns, not their
            // plain average. A nineteen-year history contains restructuring
            // years, spin-off years and years whose capital base was tagged
            // wrong, and an average reports every one of them as return.
            let annual_returns: Vec<f64> = usable
                .iter()
                .map(|year| year.nopat / year.invested_capital)
                .collect();
            let centre = robust_centre(&annual_returns);
            let book = centre
                .as_ref()
                .map(RobustCentre::centre)
                .map_err(|reason| *reason);
            let discarded = centre
                .as_ref()
                .map(RobustCentre::discarded)
                .unwrap_or_default();
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
            let gap = |candidate: Option<f64>| match (
                candidate,
                realized_growth,
                realized_reinvestment,
            ) {
                (Some(rate), Some(growth), Some(reinvestment)) if rate > 0.0 => {
                    let distance = (growth / rate - reinvestment).abs();
                    Some(distance)
                }
                _ => None,
            };
            let book_gap = gap(book.clone().ok());
            let slope_gap = gap(marginal);
            // Deliberately *not* scored. `implied` is defined as `g / b`, so its
            // own `g / r` returns `b` by construction and its gap is identically
            // zero for every issuer. It measures nothing; it is printed because
            // the return it demands is worth reading next to the two that were
            // estimated independently.
            book_gaps.extend(book_gap);
            slope_gaps.extend(slope_gap);
            if let (Some(book_value), Some(slope_value)) = (book_gap, slope_gap) {
                paired_gaps.push((symbol.to_string(), book_value, slope_value));
            }

            let show = |value: Option<f64>| value.map_or("-".into(), |v| format!("{v:.3}"));
            estimator_rows.push(format!(
                "{symbol:<7} {:>4} {:>8} {:>8} {:>8} {:>9} {:>4} {:>9} {:>9} {:>8} {:>8}",
                usable.len(),
                show(realized_growth),
                show(realized_reinvestment),
                show(capital_formation),
                show(book.clone().ok()),
                discarded,
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
        println!("b = (NOPAT - FCFF)/NOPAT, retention from the flow statements.");
        println!(
            "b_cap = dIC/NOPAT, the contaminated reading, shown so the difference is visible."
        );
        println!("out = annual returns discarded as |z| > 3 before the book centre was taken.");
        println!(
            "{:<7} {:>4} {:>8} {:>8} {:>8} {:>9} {:>4} {:>9} {:>9} {:>8} {:>8}",
            "symbol",
            "n",
            "g",
            "b",
            "b_cap",
            "r_book",
            "out",
            "r_slope",
            "r_impl",
            "|db|bk",
            "|db|sl"
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
            "issuer-years dropped for missing marginal tax rate: \
             {dropped_for_missing_marginal_tax_total}  [{}]",
            dropped_for_missing_marginal_tax_by_issuer.join(" ")
        );
        println!(
            "median NOPAT/FCFF = {:?}   (the size of the base change, before it lands)",
            median(&mut base_ratios.clone()).map(|v| format!("{v:.2}x"))
        );

        // The two candidates are scored on the population where BOTH resolve --
        // book resolves for `book_gaps.len()` issuers and slope for
        // `slope_gaps.len()`, but those are not the same names, so only the
        // paired subset is a comparison rather than two unrelated summaries.
        let paired_symbols: Vec<&str> = paired_gaps.iter().map(|(s, _, _)| s.as_str()).collect();
        let paired_book: Vec<f64> = paired_gaps.iter().map(|(_, book, _)| *book).collect();
        let paired_slope: Vec<f64> = paired_gaps.iter().map(|(_, _, slope)| *slope).collect();
        println!(
            "candidate availability: book resolves for {} issuers, slope for {} issuers, \
             paired (both resolve): {}",
            book_gaps.len(),
            slope_gaps.len(),
            paired_gaps.len()
        );

        let report_robust_centre = |label: &str, values: &[f64]| match robust_centre(values) {
            Ok(centre) => {
                let trimmed: Vec<String> = centre
                    .outliers()
                    .iter()
                    .map(|&index| format!("{}({:.3})", paired_symbols[index], values[index]))
                    .collect();
                println!(
                    "{label} robust centre = {:.3}  (retained {} of {}, trimmed [{}])",
                    centre.centre(),
                    centre.retained(),
                    values.len(),
                    trimmed.join(" ")
                );
            }
            Err(reason) => println!("{label} robust centre: refused ({reason:?})"),
        };
        report_robust_centre("|implied b - realized b|, book,", &paired_book);
        report_robust_centre("|implied b - realized b|, slope,", &paired_slope);
        println!(
            "(r_impl is not scored: its gap is identically zero by construction, since it is\n\
             defined as g/b)"
        );
        println!(
            "READ THIS AS: the smaller robust centre is the estimator whose retention charge is\n\
             consistent with what these issuers actually did with their earnings, on the paired\n\
             population above. Where the two are close, prefer the higher r: an overstated return\n\
             is bounded (1 - g/r -> 1, the charge merely vanishes) while an understated or negative\n\
             one is unbounded (negative value, or refusal through the Core's r <= 0 guard). Market\n\
             price is not consulted here and must not be used to break the tie."
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
        let edgar = edgar_client();
        let cik_map = fetch_cik_map(&edgar).expect("SEC CIK map");

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
            let Ok(Some(history)) = fetch_fcf_history(&edgar, symbol, cik) else {
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

    /// Probe D. What does point-in-time evidence cost, and does it ever differ?
    ///
    /// Three questions, over a named sample rather than whichever issuers make
    /// the answer comfortable:
    ///
    /// 1. how many accepted 10-K facts carry no filing date;
    /// 2. how many carry a period end that will not parse;
    /// 3. how many `(concept, period_end)` pairs were filed more than once at
    ///    **different values**.
    ///
    /// Columns 1 and 2 (and the accession column, the third fail-closed field)
    /// measure what refusing an undated fact costs. Column 3 measures whether
    /// `as_of` can ever differ from `latest` on live filings — which is the only
    /// reason retained vintages exist. Every refused fact is printed
    /// individually: a count does not explain a moved anchor, a named fact does.
    ///
    /// Asserts nothing.
    #[test]
    #[ignore = "network: SEC filing-date coverage probe; diagnostic only"]
    fn probe_facts_without_a_filing_date() {
        let edgar = edgar_client();
        let cik_map = fetch_cik_map(&edgar).expect("SEC CIK map");

        let mut totals = FilingDateCoverage::default();
        let mut all_dropped: Vec<(&str, DroppedFact)> = Vec::new();

        println!("=== PROBE D: point-in-time coverage of accepted 10-K facts ===");
        println!(
            "{:<7} {:>9} {:>9} {:>10} {:>9} {:>11}  {}",
            "symbol", "accepted", "no_filed", "bad_end", "no_accn", "disagreeing", "earliest_filed"
        );
        for &symbol in FILING_DATE_PROBE_SAMPLE {
            let Some(&cik) = cik_map.get(symbol) else {
                println!("{symbol:<7} no CIK");
                continue;
            };
            let Ok(facts) = fetch_company_facts(&edgar, symbol, cik) else {
                println!("{symbol:<7} no companyfacts");
                continue;
            };
            let coverage = measure_filing_date_coverage(&facts);
            println!(
                "{:<7} {:>9} {:>9} {:>10} {:>9} {:>11}  {}",
                symbol,
                coverage.accepted,
                coverage.no_filed,
                coverage.unparseable_end,
                coverage.no_accession,
                coverage.disagreeing_vintages,
                coverage.earliest_filed.as_deref().unwrap_or("none")
            );
            totals.accepted += coverage.accepted;
            totals.no_filed += coverage.no_filed;
            totals.unparseable_end += coverage.unparseable_end;
            totals.no_accession += coverage.no_accession;
            totals.disagreeing_vintages += coverage.disagreeing_vintages;
            all_dropped.extend(
                coverage
                    .dropped
                    .into_iter()
                    .map(|dropped| (symbol, dropped)),
            );
        }

        println!();
        println!(
            "TOTAL accepted={} no_filed={} bad_end={} no_accn={} disagreeing_period_ends={}",
            totals.accepted,
            totals.no_filed,
            totals.unparseable_end,
            totals.no_accession,
            totals.disagreeing_vintages
        );

        println!();
        if all_dropped.is_empty() {
            println!(
                "No accepted fact was refused. Fail-closed extraction costs this sample nothing."
            );
        } else {
            println!("EVERY REFUSED FACT (an unexplained anchor delta must appear here):");
            println!(
                "{:<7} {:<14} {:<52} {:<12} {:<12} {:<22} {}",
                "symbol", "driver", "qname", "end", "filed", "accn", "reason"
            );
            for (symbol, dropped) in &all_dropped {
                println!(
                    "{:<7} {:<14} {:<52} {:<12} {:<12} {:<22} {}",
                    symbol,
                    dropped.driver,
                    dropped.qname,
                    dropped.end,
                    dropped.filed,
                    dropped.accession,
                    dropped.reason
                );
            }
        }

        println!();
        println!(
            "READ THIS AS: columns 1, 2 and 4 are what fail-closed extraction refuses; each one\n\
             is a driver-year an issuer may lose, and every one of them is named above.\n\
             Column 3 is why vintages are retained at all -- a zero there would mean as_of and\n\
             latest can never disagree on this sample, and the point-in-time API is untested by\n\
             live data rather than merely unused."
        );
    }

    /// Probe G. Which published numbers does the corrected interest sign move,
    /// in which direction, and by how much?
    ///
    /// Each issuer is valued twice in one process through the real FCFF model
    /// and the real runtime router. The two runs share one market price, one
    /// risk-free rate and one forward forecast, so the only difference between
    /// them is how the interest series is read:
    ///
    /// * **before** — every interest year as `|filed|`. That is what the code
    ///   published while LD-1's three `.abs()` sites stood: the setter stored
    ///   the absolute value and both read sites re-applied `.abs()` on top.
    /// * **after** — the history exactly as this tree reads it.
    ///
    /// On a tree that still carries the `.abs()` sites the two are identical by
    /// construction and every delta is zero; the TREE CHECK line says which tree
    /// this is, so a table of zeroes can be read as *absorbed* or as *incapable
    /// of arriving* without guessing. That distinction is the whole difficulty
    /// of the measurement: 19 of the 25 issuers whose interest series is
    /// rewritten publish the same number afterwards, and reporting that as "the
    /// change did not work" would invert the conclusion.
    ///
    /// Asserts nothing. The pre-registered set is printed beside the observed
    /// one so a disagreement is visible, never so a number can be adjusted
    /// toward it.
    #[test]
    #[ignore = "network: SEC + Yahoo published-value probe; diagnostic only"]
    fn probe_published_value_under_the_corrected_interest_sign() {
        /// The six issuers the isolated counterfactual measured as moving a
        /// published number, with the move it measured, in cents. Printed for
        /// comparison only (`ORCHESTRATOR-RULINGS.md` R-13.1).
        const PRE_REGISTERED_MOVERS: &[(&str, i64)] = &[
            ("ROST", -279),
            ("MPWR", -357),
            ("JKHY", -135),
            ("ULTA", -124),
            ("CPRT", -23),
            ("NKE", -12),
        ];

        let edgar = edgar_client();
        let cik_map = fetch_cik_map(&edgar).expect("SEC CIK map");
        let yahoo = YahooClient::new().expect("Yahoo client");

        println!("=== PROBE G: the published value under the corrected interest sign ===");
        println!("retrieved {}", Utc::now().to_rfc3339());
        println!(
            "TREE CHECK: the interest sign survives FcfPoint::with_operating_drivers = {}",
            if tree_preserves_the_interest_sign() {
                "YES -- the after column can differ"
            } else {
                "NO -- every delta below is forced to zero by the surviving abs(); \
                 this run measures the instrument, not the change"
            }
        );

        let market_params = yahoo.fetch_us_10y_yield_bps().map_or_else(
            || {
                println!("live risk-free UNAVAILABLE -- market params are provisional defaults");
                MarketParams::default_usd()
            },
            |(risk_free_bps, as_of)| {
                println!("live risk-free {risk_free_bps} bps as of {as_of}");
                MarketParams::from_live_risk_free(risk_free_bps, as_of)
            },
        );
        let as_of_epoch_day = Utc::now().timestamp() / 86_400;

        println!();
        println!(
            "{:<7} {:>4} {:>10} {:>10} {:>9} {:>9} {:>10} {:>10} {:>11} {:<22} {:<22} {:<10} {:<10} {:<5}",
            "symbol",
            "yrs",
            "before c",
            "after c",
            "delta c",
            "delta bps",
            "fcff b c",
            "fcff a c",
            "addbk b/a",
            "cod before",
            "cod after",
            "lane b",
            "lane a",
            "flip"
        );

        let mut deltas_bps: Vec<f64> = Vec::new();
        let mut movers: Vec<String> = Vec::new();
        let mut selection_flips: Vec<String> = Vec::new();
        let mut anchors_that_moved: Vec<String> = Vec::new();
        let mut non_positive_after: Vec<String> = Vec::new();
        let mut new_channel_refusals: Vec<String> = Vec::new();
        let mut not_operating: Vec<String> = Vec::new();
        let mut unmeasurable: Vec<String> = Vec::new();
        let mut fcff_candidate_moved: Vec<&str> = Vec::new();
        let mut rewritten_and_capable = 0_usize;

        for &symbol in VALUATION_ANCHORS
            .iter()
            .chain(INTEREST_SIGN_AFFECTED_COHORT)
            .chain(BASIS_ONLY_COHORT)
        {
            let Some(&cik) = cik_map.get(symbol) else {
                unmeasurable.push(format!("{symbol}(no CIK)"));
                continue;
            };
            let Ok(fetched) = yahoo.fetch_symbol(symbol) else {
                unmeasurable.push(format!("{symbol}(yahoo)"));
                continue;
            };
            let market_price_cents = fetched
                .snapshot
                .as_ref()
                .map(|snapshot| snapshot.market_price_cents);
            let Some(mut fundamentals) = fetched.fundamentals else {
                unmeasurable.push(format!("{symbol}(no fundamentals)"));
                continue;
            };
            if fundamentals.shares_outstanding.unwrap_or(0) == 0 {
                fundamentals.shares_outstanding =
                    fetch_shares_outstanding(&edgar, symbol, cik).unwrap_or(None);
            }
            let class = classify_business(
                fundamentals.sector_name.as_deref(),
                fundamentals.industry_name.as_deref(),
                fundamentals.sector_key.as_deref(),
                fundamentals.industry_key.as_deref(),
                false,
            );
            if class != BusinessClass::OperatingNonFinancial {
                not_operating.push(format!("{symbol}({class:?})"));
                continue;
            }
            let Ok(Some(after_history)) = fetch_fcf_history(&edgar, symbol, cik) else {
                unmeasurable.push(format!("{symbol}(no history)"));
                continue;
            };
            let before_history = history_as_published_before_the_sign_correction(&after_history);
            let rewritten_years = before_history
                .iter()
                .zip(&after_history)
                .filter(|(before, after)| {
                    before.interest_expense_dollars != after.interest_expense_dollars
                })
                .count();

            let forward_evidence = yahoo
                .fetch_forward_forecast(symbol, as_of_epoch_day)
                .map_err(|error| match error {
                    ForwardForecastFetchError::Provider(reason) => {
                        ForwardSourceFailure::Provider(reason)
                    }
                    ForwardForecastFetchError::Transport(error) if is_rate_limit_error(&error) => {
                        ForwardSourceFailure::RateLimited
                    }
                    ForwardForecastFetchError::Transport(_) => ForwardSourceFailure::Transport,
                });

            let run = |history: &[FcfPoint]| {
                run_operating_lane(GateRunInput {
                    fundamentals: &fundamentals,
                    history,
                    market_price_cents,
                    market_params: &market_params,
                    forward_evidence: forward_evidence.clone(),
                    as_of_epoch_day,
                })
            };
            let before = run(&before_history);
            let after = run(&after_history);
            let cod_before =
                cost_of_debt_channel(&before_history, &fundamentals, market_params.rf_bps);
            let cod_after =
                cost_of_debt_channel(&after_history, &fundamentals, market_params.rf_bps);

            if rewritten_years > 0 && tree_preserves_the_interest_sign() {
                rewritten_and_capable += 1;
            }
            let delta_cents = before
                .published_base_cents
                .zip(after.published_base_cents)
                .map(|(before, after)| after - before);
            let delta_bps = before
                .published_base_cents
                .filter(|before| *before > 0)
                .zip(delta_cents)
                .map(|(before, delta)| delta as f64 / before as f64 * 10_000.0);
            let flipped = before.lane != after.lane;

            let cents = |value: Option<i64>| {
                value.map_or_else(|| "absent".to_string(), |value: i64| value.to_string())
            };
            let bps = |value: Option<i32>| {
                value.map_or_else(|| "-".to_string(), |value: i32| value.to_string())
            };
            println!(
                "{symbol:<7} {rewritten_years:>4} {:>10} {:>10} {:>9} {:>9} {:>10} {:>10} {:>11} {:<22} {:<22} {:<10} {:<10} {:<5}",
                cents(before.published_base_cents),
                cents(after.published_base_cents),
                delta_cents.map_or_else(|| "-".to_string(), |value| value.to_string()),
                delta_bps.map_or_else(|| "-".to_string(), |value| format!("{value:.0}")),
                cents(before.fcff_candidate_cents),
                cents(after.fcff_candidate_cents),
                format!(
                    "{}/{}",
                    bps(before.interest_add_back_margin_bps),
                    bps(after.interest_add_back_margin_bps)
                ),
                cod_before,
                cod_after,
                before.lane,
                after.lane,
                if flipped { "FLIP" } else { "-" },
            );

            if before.fcff_candidate_cents != after.fcff_candidate_cents {
                fcff_candidate_moved.push(symbol);
            }
            if flipped {
                selection_flips.push(format!("{symbol} {} -> {}", before.lane, after.lane));
            }
            if cod_before != cod_after {
                new_channel_refusals.push(format!("{symbol} {cod_before} -> {cod_after}"));
            }
            if after
                .fcff_candidate_cents
                .is_some_and(|candidate| candidate <= 0)
            {
                non_positive_after.push(format!("{symbol} fcff={:?}c", after.fcff_candidate_cents));
            }
            match delta_cents {
                Some(delta) if delta != 0 => {
                    movers.push(format!("{symbol}({delta:+}c)"));
                    if VALUATION_ANCHORS.contains(&symbol) {
                        anchors_that_moved.push(format!("{symbol}({delta:+}c)"));
                    }
                    if let Some(bps) = delta_bps {
                        deltas_bps.push(bps);
                    }
                }
                Some(_) => {}
                None => unmeasurable.push(format!("{symbol}(no published base on one side)")),
            }
        }

        println!();
        println!("=== ANCHORS ===");
        if anchors_that_moved.is_empty() {
            println!(
                "all four anchors move $0.00: [{}] -- triggers (a)/(a') do not fire",
                VALUATION_ANCHORS.join(" ")
            );
        } else {
            println!(
                "*** STOP: AN ANCHOR MOVED *** [{}]",
                anchors_that_moved.join(" ")
            );
        }

        println!();
        println!("=== THE ANSWER ===");
        println!(
            "issuers with a live rewrite (>=1 year changed, and the tree can carry it): \
             {rewritten_and_capable}"
        );
        println!(
            "issuers whose FCFF CANDIDATE moves: {} [{}]",
            fcff_candidate_moved.len(),
            fcff_candidate_moved.join(" ")
        );
        println!(
            "issuers whose PUBLISHED value moves: {} [{}]",
            movers.len(),
            movers.join(" ")
        );
        println!(
            "the difference between those two lines is absorption: a rewrite that reached the \
             model\nand was swallowed either by the robust normalization over the whole series \
             or by the\nrouter publishing the forward lane instead"
        );
        println!(
            "router selection flips: {} [{}] -- a flip is qualitatively different from a \
             magnitude,\nbecause the published number changes lane rather than value",
            selection_flips.len(),
            selection_flips.join(" ")
        );
        println!(
            "accounting cost-of-debt channel changes (T2.7): {} [{}]",
            new_channel_refusals.len(),
            new_channel_refusals.join(" ")
        );
        println!(
            "corrected FCFF candidate non-positive (a refusal path, not a magnitude): {} [{}]",
            non_positive_after.len(),
            non_positive_after.join(" ")
        );
        println!(
            "reclassified away from OperatingNonFinancial since the scan: [{}]",
            not_operating.join(" ")
        );
        println!("not measurable: [{}]", unmeasurable.join(" "));

        println!();
        println!("=== against the pre-registration (R-13.1) ===");
        println!(
            "registered: [{}]",
            PRE_REGISTERED_MOVERS
                .iter()
                .map(|(symbol, cents)| format!("{symbol}({cents:+}c)"))
                .collect::<Vec<_>>()
                .join(" ")
        );
        println!("observed:   [{}]", movers.join(" "));
        let registered_but_still: Vec<&str> = PRE_REGISTERED_MOVERS
            .iter()
            .map(|(symbol, _)| *symbol)
            .filter(|symbol| !movers.iter().any(|mover| mover.starts_with(*symbol)))
            .collect();
        let moved_but_unregistered: Vec<&String> = movers
            .iter()
            .filter(|mover| {
                !PRE_REGISTERED_MOVERS
                    .iter()
                    .any(|(symbol, _)| mover.starts_with(symbol))
            })
            .collect();
        println!(
            "registered and did NOT move: [{}]",
            registered_but_still.join(" ")
        );
        println!(
            "moved and NOT registered (each one is a stop under trigger (c.2)): [{}]",
            moved_but_unregistered
                .iter()
                .map(|mover| mover.as_str())
                .collect::<Vec<_>>()
                .join(" ")
        );

        println!();
        println!("=== distribution of published-value delta, in bps of the before value ===");
        if deltas_bps.is_empty() {
            println!("no issuer's published value moved");
        } else {
            let mut sorted = deltas_bps.clone();
            sorted.sort_by(|left, right| left.partial_cmp(right).unwrap_or(Ordering::Equal));
            println!(
                "n={} min={:.0} median={:.0} max={:.0}",
                sorted.len(),
                sorted[0],
                median(&mut deltas_bps).unwrap_or_default(),
                sorted[sorted.len() - 1]
            );
            println!(
                "full sorted series: [{}]",
                sorted
                    .iter()
                    .map(|value| format!("{value:.0}"))
                    .collect::<Vec<_>>()
                    .join(" ")
            );
        }
    }
}
