use reqwest::blocking::Client;
use serde::Deserialize;
/// SEC EDGAR integration — ticker→CIK lookup + DCF valuation from annual filings.
///
/// Uses two public EDGAR endpoints (no auth required, 10 req/s limit):
///   https://www.sec.gov/files/company_tickers.json   → ticker→CIK map
///   https://data.sec.gov/api/xbrl/companyfacts/{CIK}.json → XBRL facts
///
/// DCF model (simple 2-stage):
///   Stage 1: 5 years at historical FCF CAGR (clamped to -5%..+25%)
///   Stage 2: terminal value at 3% perpetuity growth
///   Discount rate: 10% (conservative WACC)
///   Result: intrinsic value per share in cents
use std::collections::HashMap;
use std::collections::HashSet;
use std::fmt;
use std::iter::once;
use std::time::Duration;

use crate::dcf_model::{FcfPoint, WaccFieldSource};
use crate::sec_driver_normalization_policy_generated as policy;
use crate::sec_normalization::{
    normalize_investments, EvidenceState, NormalizedInvestmentEvidence, SecFact,
};

const EDGAR_USER_AGENT: &str = "DiscountScreener/1.0 contact@example.com";
const DCF_DISCOUNT_RATE: f64 = 0.10;
const DCF_TERMINAL_GROWTH: f64 = 0.03;
const DCF_PROJECTION_YEARS: usize = 5;
const DCF_MIN_GROWTH: f64 = -0.05;
const DCF_MAX_GROWTH: f64 = 0.25;
const DCF_MIN_YEARS_HISTORY: usize = 2; // need at least 2 data points for a growth rate

// ── CIK map ───────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct TickerEntry {
    cik_str: u64,
    ticker: String,
    #[allow(dead_code)]
    title: String,
}

/// Build a reqwest client suitable for EDGAR requests.
pub fn edgar_client() -> Client {
    Client::builder()
        .timeout(Duration::from_secs(20))
        .user_agent(EDGAR_USER_AGENT)
        .build()
        .expect("EDGAR HTTP client")
}

/// Fetch the SEC ticker→CIK mapping. Returns a HashMap<TICKER_UPPERCASE, CIK>.
/// The CIK is zero-padded to 10 digits when used in URLs.
pub fn fetch_cik_map(client: &Client) -> Result<HashMap<String, u64>, String> {
    let url = "https://www.sec.gov/files/company_tickers.json";
    let body: serde_json::Value = client
        .get(url)
        .header("Accept", "application/json")
        .send()
        .map_err(|e| format!("CIK map fetch: {}", e))?
        .json()
        .map_err(|e| format!("CIK map parse: {}", e))?;

    let mut map = HashMap::new();
    if let Some(obj) = body.as_object() {
        for entry in obj.values() {
            if let Ok(e) = serde_json::from_value::<TickerEntry>(entry.clone()) {
                map.insert(e.ticker.to_uppercase(), e.cik_str);
            }
        }
    }
    Ok(map)
}

// ── EDGAR companyfacts ────────────────────────────────────────────────────────

/// An ISO calendar date, parsed once at the extraction boundary so that no
/// upper layer ever slices a string to get a year or compares dates as text.
///
/// Ordering is chronological because the fields are declared most-significant
/// first and the derived `Ord` compares them in declaration order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct IsoDate {
    year: i32,
    month: u8,
    day: u8,
}

impl IsoDate {
    /// Strict `YYYY-MM-DD`. Rejects a short year, a non-numeric field, trailing
    /// text and any date the calendar does not have, so a malformed filing date
    /// can never be silently promoted into a usable one.
    pub fn parse(text: &str) -> Option<Self> {
        if !text
            .bytes()
            .all(|byte| byte.is_ascii_digit() || byte == b'-')
        {
            return None;
        }
        let mut fields = text.split('-');
        let year = fields.next()?;
        let month = fields.next()?;
        let day = fields.next()?;
        if fields.next().is_some() || year.len() != 4 || month.len() != 2 || day.len() != 2 {
            return None;
        }
        let year = year.parse::<i32>().ok()?;
        let month = month.parse::<u8>().ok()?;
        let day = day.parse::<u8>().ok()?;
        chrono::NaiveDate::from_ymd_opt(year, u32::from(month), u32::from(day))?;
        Some(Self { year, month, day })
    }

    /// The calendar year of this date. The only derivation of a fiscal year in
    /// this module: `AnnualValue::year` comes from the period end and from
    /// nothing else.
    pub fn year(&self) -> i32 {
        self.year
    }
}

/// One filed observation of one concept for one period. Nothing is collapsed:
/// a later filing that revises the same period is a separate observation, which
/// is what lets a reader ask what was knowable on a date rather than only what
/// is believed now.
///
/// `end` and `filed` are the parsed forms of `fact.end` and `fact.filed`. That
/// they parse at all is the invariant `AnnualObservation::from_fact` enforces,
/// and it is the reason provenance built from an observation can never carry a
/// fabricated date.
#[derive(Debug, Clone)]
pub struct AnnualObservation {
    pub fact: SecFact,
    pub end: IsoDate,
    pub filed: IsoDate,
}

impl AnnualObservation {
    /// One filed fact, or nothing at all.
    ///
    /// Fail-closed on three fields. A fact whose period end or filing date will
    /// not parse produces no observation, because an empty or defaulted date is
    /// a fabricated availability. A fact with no accession produces none
    /// either: the accession decides the precedence tie-break, so defaulting it
    /// is the same fabricated-identity defect in a third field.
    fn from_fact(fact: SecFact) -> Option<Self> {
        let end = IsoDate::parse(&fact.end)?;
        let filed = IsoDate::parse(fact.filed.as_deref()?)?;
        if fact.accession.as_deref().is_none_or(str::is_empty) {
            return None;
        }
        Some(Self { fact, end, filed })
    }
}

impl fmt::Display for IsoDate {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "{:04}-{:02}-{:02}",
            self.year, self.month, self.day
        )
    }
}

/// A single annual (10-K) value from EDGAR XBRL, together with everything
/// needed to say where it came from and when it became knowable.
#[derive(Debug, Clone)]
pub struct AnnualValue {
    pub year: i32,
    pub value_dollars: i64,
    pub provenance: AnnualProvenance,
}

/// Everything needed to answer the first two of the three questions
/// point-in-time evidence must answer (`docs/sec-point-in-time-provenance.md`):
///
/// 1. **which filing does this come from** — `sources`;
/// 2. **when did it become knowable** — `known_from`, the latest `filed` among
///    them.
///
/// The third — *what would a reader have believed on date D* — cannot be
/// answered by a value whose history has already been collapsed, and is
/// `AnnualSeries::as_of`'s job.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnnualProvenance {
    /// The period end exactly as filed. `AnnualValue::year` is derived from
    /// this and from nothing else.
    pub end: IsoDate,
    /// The date this observation became knowable: the LATEST `filed` among
    /// `sources`. A composite is knowable only once its last input was filed.
    pub known_from: IsoDate,
    /// Every fact that contributed, in combination order.
    pub sources: Vec<SecFact>,
    /// True when `sources` do not share one filing date. Real compositions
    /// legitimately mix vintages (OCF and CapEx can arrive in different
    /// filings); the flag makes that visible instead of silent.
    pub mixed_vintage: bool,
}

impl AnnualProvenance {
    /// Provenance for a value read straight from filed facts.
    ///
    /// `None` when there is nothing to read from: a value with no source has no
    /// date on which it became knowable, and inventing one is the defect this
    /// type exists to prevent.
    fn from_observations<'a>(
        end: IsoDate,
        parts: impl IntoIterator<Item = &'a AnnualObservation>,
    ) -> Option<Self> {
        let mut sources = Vec::new();
        let mut filings = Vec::new();
        for part in parts {
            sources.push(part.fact.clone());
            filings.push(part.filed);
        }
        let known_from = filings.iter().copied().max()?;
        Some(Self {
            end,
            known_from,
            mixed_vintage: filings.iter().any(|filed| *filed != known_from),
            sources,
        })
    }

    /// Provenance for a value composed from several already-provenanced values:
    /// total debt from its two components, FCF from operating cash flow and
    /// CapEx, recurring development from its tangible and software parts.
    ///
    /// `end` is supplied by the caller rather than derived, because a
    /// composition takes its period identity from one designated input — FCF is
    /// reported for the operating-cash-flow period even when the CapEx it
    /// subtracts was filed for another one.
    fn composed<'a>(
        end: IsoDate,
        parts: impl IntoIterator<Item = &'a AnnualProvenance>,
    ) -> Option<Self> {
        let mut sources = Vec::new();
        let mut latest_filings = Vec::new();
        let mut mixed_vintage = false;
        for part in parts {
            sources.extend(part.sources.iter().cloned());
            latest_filings.push(part.known_from);
            mixed_vintage |= part.mixed_vintage;
        }
        let known_from = latest_filings.iter().copied().max()?;
        mixed_vintage |= latest_filings.iter().any(|filed| *filed != known_from);
        Some(Self {
            end,
            known_from,
            sources,
            mixed_vintage,
        })
    }
}

/// Every vintage of one driver, in declared concept order, before any of them
/// has been resolved into a belief.
///
/// This is what `known_from` alone cannot give: `known_from` answers "when did
/// we first know what we now believe", `as_of` answers "what did we believe on
/// date D". Both are needed and they are different questions — the third of the
/// three in `docs/sec-point-in-time-provenance.md`.
///
/// `as_of(D)` admits an observation only when it was filed STRICTLY before `D`.
/// A filing made on `D` was not knowable at the start of that day, and an
/// inclusive bound leaks one day of hindsight at every cutoff a backtest takes.
///
/// Observations are held grouped by concept, in the order the concepts were
/// declared, and resolution depends on it: `select_one_equivalent` resolves
/// each concept on its own and only then fills the gaps from the alternatives.
#[derive(Debug, Clone)]
pub struct AnnualSeries {
    observations: Vec<AnnualObservation>,
}

/// One fiscal year of one concept after its vintages have been resolved, plus
/// whether a superseded consolidated vintage disagreed with the winner by
/// enough to be a restatement rather than a rounding revision.
struct ResolvedYear {
    value: AnnualValue,
    materially_restated: bool,
}

impl AnnualSeries {
    /// What a reader could have believed on `cutoff`: only observations filed
    /// STRICTLY before it.
    ///
    /// A fact filed on the cutoff date was not knowable at the start of that
    /// day, and a boundary that admits it is a one-day leak repeated at every
    /// cutoff.
    ///
    /// Single-concept drivers only. A composed driver — total debt, FCF,
    /// recurring development — carries provenance but has no cutoff-aware
    /// resolution, because one observation holds one fact and a composition is
    /// not representable in it. That is LD-6 in
    /// `docs/sec-point-in-time-provenance.md`.
    pub fn as_of(&self, cutoff: IsoDate) -> Vec<AnnualValue> {
        self.merge(Some(cutoff))
    }

    /// Everything filed to date. Defined as `as_of` with no upper bound, so
    /// there is exactly one resolution implementation.
    pub fn latest(&self) -> Vec<AnnualValue> {
        self.merge(None)
    }

    /// Fiscal years for which a later filing materially restated this driver.
    ///
    /// Read per concept and unioned, never after the cross-concept merge: a
    /// year restated under a concept that lost the merge is still a year whose
    /// two filings describe different reporting entities.
    fn materially_restated_years(&self) -> HashSet<i32> {
        self.resolve_by_concept(None)
            .into_iter()
            .flatten()
            .filter(|resolved| resolved.materially_restated)
            .map(|resolved| resolved.value.year)
            .collect()
    }

    /// Equivalent XBRL concepts are often rotated during taxonomy migrations:
    /// one tag may contain the older history while another contains recent
    /// filings. Selecting the longest series silently drops the newer tag and
    /// can pair OCF with stale revenue. Merge by fiscal year in declared
    /// precedence order instead; overlaps use the canonical first concept and
    /// gaps are filled from the alternatives, which is why a gap-filled year
    /// keeps the filling concept's provenance.
    fn merge(&self, cutoff: Option<IsoDate>) -> Vec<AnnualValue> {
        let mut by_year = HashMap::<i32, AnnualValue>::new();
        for concept in self.resolve_by_concept(cutoff) {
            for resolved in concept {
                by_year.entry(resolved.value.year).or_insert(resolved.value);
            }
        }
        sorted_by_year(by_year)
    }

    /// One resolved candidate list per concept, in declared precedence order.
    fn resolve_by_concept(&self, cutoff: Option<IsoDate>) -> Vec<Vec<ResolvedYear>> {
        let mut concepts: Vec<&str> = Vec::new();
        let mut by_concept: HashMap<&str, Vec<&AnnualObservation>> = HashMap::new();
        for observation in &self.observations {
            if cutoff.is_some_and(|cutoff| observation.filed >= cutoff) {
                continue;
            }
            let qname = observation.fact.qname.as_str();
            by_concept
                .entry(qname)
                .or_insert_with(|| {
                    concepts.push(qname);
                    Vec::new()
                })
                .push(observation);
        }
        concepts
            .into_iter()
            .map(|qname| resolve_one_concept(&by_concept[qname]))
            .collect()
    }
}

/// The standing precedence for one concept: consolidated over segment, then the
/// latest filing, then the latest accession. This is the only place in this
/// module that decides which vintage wins a period end.
fn resolve_one_concept(observations: &[&AnnualObservation]) -> Vec<ResolvedYear> {
    /// Exact end dates can carry a re-filed fact and a segment fact.
    /// `Option<&str>` for the accession rather than a defaulted empty string:
    /// an absent accession sorts below every present one instead of pretending
    /// to be one.
    fn precedence<'a>(observation: &'a AnnualObservation) -> (bool, IsoDate, Option<&'a str>) {
        (
            observation.fact.consolidated,
            observation.filed,
            observation.fact.accession.as_deref(),
        )
    }

    let mut by_end: HashMap<IsoDate, &AnnualObservation> = HashMap::new();
    // Every consolidated value seen for an end date, so the winner can be
    // compared against the ones it superseded. Segment facts are excluded:
    // a segment differing from the consolidated total is decomposition, not
    // a restatement.
    let mut consolidated_values: HashMap<IsoDate, Vec<i64>> = HashMap::new();
    for observation in observations {
        if observation.fact.consolidated {
            consolidated_values
                .entry(observation.end)
                .or_default()
                .push(observation.fact.value_dollars);
        }
        let replace = by_end
            .get(&observation.end)
            .is_none_or(|winner| precedence(observation) > precedence(winner));
        if replace {
            by_end.insert(observation.end, observation);
        }
    }

    // A restated comparative fact can carry a later fiscal end for the same
    // fiscal year. Keep the latest fiscal end for that year so the aligned
    // series contains one observation per fiscal year.
    let mut by_fiscal_year: HashMap<i32, &AnnualObservation> = HashMap::new();
    for observation in by_end.into_values() {
        let replace = by_fiscal_year
            .get(&observation.end.year())
            .is_none_or(|winner| observation.end > winner.end);
        if replace {
            by_fiscal_year.insert(observation.end.year(), observation);
        }
    }

    by_fiscal_year
        .into_values()
        .filter_map(|observation| {
            let provenance = AnnualProvenance::from_observations(observation.end, [observation])?;
            let materially_restated =
                consolidated_values
                    .get(&observation.end)
                    .is_some_and(|values| {
                        values.iter().any(|value| {
                            materially_restated(*value, observation.fact.value_dollars)
                        })
                    });
            Some(ResolvedYear {
                value: AnnualValue {
                    year: observation.end.year(),
                    value_dollars: observation.fact.value_dollars,
                    provenance,
                },
                materially_restated,
            })
        })
        .collect()
}

/// One observation per fiscal year, in chronological order — the shape every
/// annual reader in this module returns.
fn sorted_by_year(by_year: HashMap<i32, AnnualValue>) -> Vec<AnnualValue> {
    let mut result: Vec<AnnualValue> = by_year.into_values().collect();
    result.sort_by_key(|value| value.year);
    result
}

/// Extract annual values for a given XBRL concept from companyfacts JSON.
///
/// Companyfacts also contains quarterly facts embedded in a 10-K and segment
/// facts for the same end date. Taking the latest filed row blindly can select
/// a segment (INTU) or a quarter (NVDA), which poisons downstream drivers.
#[derive(Clone, Copy, PartialEq, Eq)]
enum FactPeriodShape {
    Any,
    Duration,
    Instant,
}

fn has_approved_period_shape(entry: &serde_json::Value, shape: FactPeriodShape) -> bool {
    match shape {
        FactPeriodShape::Any => true,
        FactPeriodShape::Instant => entry["start"].is_null(),
        FactPeriodShape::Duration => {
            let Some(start) = entry["start"].as_str() else {
                return false;
            };
            let Some(end) = entry["end"].as_str() else {
                return false;
            };
            let Ok(start) = chrono::NaiveDate::parse_from_str(start, "%Y-%m-%d") else {
                return false;
            };
            let Ok(end) = chrono::NaiveDate::parse_from_str(end, "%Y-%m-%d") else {
                return false;
            };
            (policy::MINIMUM_DURATION_DAYS..=policy::MAXIMUM_DURATION_DAYS)
                .contains(&(end - start).num_days())
        }
    }
}

/// The entry-level admissions every annual reader shares: an accepted form, an
/// approved period shape, and not a quarter embedded in a 10-K. A 10-K may
/// carry CY2019Q4/CY2020Q1 facts; they are not annual observations and must not
/// be mixed into a fiscal-year series.
fn is_accepted_annual_entry(entry: &serde_json::Value, shape: FactPeriodShape) -> bool {
    policy::ACCEPTED_FORMS.contains(&entry["form"].as_str().unwrap_or_default())
        && has_approved_period_shape(entry, shape)
        && !entry["frame"]
            .as_str()
            .is_some_and(|frame| frame.contains('Q'))
}

/// One companyfacts entry as a typed fact.
///
/// `value_dollars` is supplied by the caller because the caller decides the
/// magnitude a concept contributes: a USD concept files it directly, while a
/// unitless rate is admitted in basis points (`SecFact` carries one integer
/// magnitude and its unit string, and retyping it belongs to another wave).
/// A missing `end`, `filed` or `accn` is left absent here and refused by
/// `AnnualObservation::from_fact`; nothing is defaulted on the way in.
fn sec_fact_from_entry(
    entry: &serde_json::Value,
    qname: &str,
    unit: &str,
    value_dollars: i64,
) -> SecFact {
    SecFact {
        qname: qname.to_owned(),
        taxonomy: SEC_TAXONOMY.into(),
        value_dollars,
        start: entry["start"].as_str().map(str::to_owned),
        end: entry["end"].as_str().unwrap_or_default().to_owned(),
        unit: unit.to_owned(),
        form: entry["form"].as_str().unwrap_or_default().to_owned(),
        accession: entry["accn"].as_str().map(str::to_owned),
        filed: entry["filed"].as_str().map(str::to_owned),
        consolidated: entry
            .get("segment")
            .map_or(true, serde_json::Value::is_null),
    }
}

/// The only XBRL taxonomy this extractor reads.
const SEC_TAXONOMY: &str = "us-gaap";

/// A later filing that moves an annual value by at least this much is a
/// restatement, not a rounding revision. Deliberately separate from
/// `MATERIAL_ACQUISITION_REVENUE_BPS`: that one asks whether a business
/// combination is big enough to distort growth, this one asks whether two
/// filings describe the same reporting entity.
const MATERIAL_RESTATEMENT_BPS: i64 = 1_000;

fn materially_restated(candidate: i64, winner: i64) -> bool {
    let reference = winner.abs().max(candidate.abs());
    reference > 0 && (candidate - winner).abs() * 10_000 >= reference * MATERIAL_RESTATEMENT_BPS
}

/// Every filed vintage of one concept, unresolved. Nothing is collapsed here:
/// deciding which vintage wins is `AnnualSeries`'s job and happens once.
///
/// `sign` is a static property of the concept (declared on the contract, never
/// on the filed value), so it is applied here, before the fact exists, and
/// travels with the observation through precedence resolution, restatement
/// comparison and vintage retention. Applying it later, to a post-merge
/// scalar, would let a year filled from a second concept inherit the first
/// concept's sign instead of its own.
fn concept_observations(
    facts: &serde_json::Value,
    concept: &str,
    sign: i8,
    shape: FactPeriodShape,
    unit: &str,
) -> Vec<AnnualObservation> {
    let Some(entries) = facts
        .pointer(&format!("/facts/{SEC_TAXONOMY}/{concept}/units/{unit}"))
        .and_then(serde_json::Value::as_array)
    else {
        return Vec::new();
    };
    entries
        .iter()
        .filter(|entry| is_accepted_annual_entry(entry, shape))
        .filter_map(|entry| {
            let filed_value = entry["val"].as_i64()?;
            let value_dollars = filed_value * i64::from(sign);
            AnnualObservation::from_fact(sec_fact_from_entry(entry, concept, unit, value_dollars))
        })
        .collect()
}

/// Every filed vintage of a list of equivalent concepts, in declared precedence
/// order. All of one concept's observations precede the next concept's, which
/// is the ordering `AnnualSeries::resolve_by_concept` relies on.
///
/// `signs` is positional and parallel to `concepts`, mirroring the generated
/// contract's `qnames`/`qname_signs` pairing; a caller whose two lists
/// disagree in length has a broken contract, not a value worth guessing at.
fn concept_vintages(
    facts: &serde_json::Value,
    concepts: &[&str],
    signs: &[i8],
    shape: FactPeriodShape,
    unit: &str,
) -> AnnualSeries {
    assert_eq!(
        concepts.len(),
        signs.len(),
        "concept list and sign list must have equal length"
    );
    AnnualSeries {
        observations: concepts
            .iter()
            .zip(signs.iter())
            .flat_map(|(concept, sign)| concept_observations(facts, concept, *sign, shape, unit))
            .collect(),
    }
}

/// Fiscal years for which a later filing materially restated this driver.
///
/// A discontinued operation restates revenue to continuing operations while
/// ASC 205-20 leaves the cash-flow statement on the total-company basis, so
/// comparing this set across two drivers is the only evidence that a year's
/// margin mixes two different entities.
fn restated_years(facts: &serde_json::Value, driver: policy::DriverOperator) -> HashSet<i32> {
    extract_driver_vintages(facts, driver).materially_restated_years()
}

/// Every filed vintage of one driver, unresolved.
///
/// This is the point-in-time capable entry point: `fetch_company_facts` then
/// `extract_driver_vintages` then `AnnualSeries::as_of` answers what was
/// knowable on a date. `extract_driver_annual` is the `latest()` view of the
/// same series and answers only what is believed now.
pub fn extract_driver_vintages(
    facts: &serde_json::Value,
    driver: policy::DriverOperator,
) -> AnnualSeries {
    assert!(
        matches!(driver.unit, "USD" | "shares"),
        "unsupported SEC driver unit: {}",
        driver.unit
    );
    let shape = period_shape(&driver);
    assert!(
        matches!(
            driver.operation,
            "select_one_equivalent" | "sum_disjoint_components" | "derive_effective_tax"
        ),
        "unsupported SEC driver operation: {}",
        driver.operation
    );
    concept_vintages(facts, driver.qnames, driver.qname_signs, shape, driver.unit)
}

pub fn extract_driver_annual(
    facts: &serde_json::Value,
    driver: policy::DriverOperator,
) -> Vec<AnnualValue> {
    extract_driver_vintages(facts, driver).latest()
}

fn period_shape(driver: &policy::DriverOperator) -> FactPeriodShape {
    match driver.period_shape {
        "duration" => FactPeriodShape::Duration,
        "instant" => FactPeriodShape::Instant,
        other => panic!("unsupported SEC driver shape: {other}"),
    }
}

/// Every companyfacts entry a driver admits on form, period shape and frame —
/// before the fail-closed provenance rules see it.
///
/// The measurement seam for `probe_facts_without_a_filing_date`: what this
/// returns and what `extract_driver_vintages` keeps differ by exactly the facts
/// fail-closed extraction refuses, so the cost of refusing them can be counted
/// on live filings instead of assumed.
#[cfg(test)]
pub(crate) fn accepted_annual_entries<'a>(
    facts: &'a serde_json::Value,
    driver: &policy::DriverOperator,
) -> Vec<(&'static str, &'a serde_json::Value)> {
    let shape = period_shape(driver);
    driver
        .qnames
        .iter()
        .flat_map(|qname| {
            facts
                .pointer(&format!(
                    "/facts/{SEC_TAXONOMY}/{qname}/units/{}",
                    driver.unit
                ))
                .and_then(serde_json::Value::as_array)
                .map(Vec::as_slice)
                .unwrap_or_default()
                .iter()
                .filter(move |entry| is_accepted_annual_entry(entry, shape))
                .map(move |entry| (*qname, entry))
        })
        .collect()
}

fn extract_total_debt(facts: &serde_json::Value) -> Vec<AnnualValue> {
    let current = extract_driver_annual(facts, policy::CURRENT_DEBT);
    let noncurrent = extract_driver_annual(facts, policy::NON_CURRENT_DEBT);
    let reported_total = extract_driver_annual(facts, policy::TOTAL_DEBT);

    // The two components are disjoint lines of one balance sheet and sum; a
    // reported total supersedes the sum of its parts, and takes its provenance
    // with it rather than inheriting the components'.
    let mut by_year = HashMap::<i32, (i64, Vec<AnnualProvenance>)>::new();
    for value in current.into_iter().chain(noncurrent) {
        let composed = by_year.entry(value.year).or_insert_with(|| (0, Vec::new()));
        composed.0 += value.value_dollars.abs();
        composed.1.push(value.provenance);
    }
    for value in reported_total {
        by_year.insert(
            value.year,
            (value.value_dollars.abs(), vec![value.provenance]),
        );
    }
    sorted_by_year(
        by_year
            .into_iter()
            // Every entry was created by pushing at least one contributor, so
            // the composition never has nothing to take a date from.
            .filter_map(|(year, (value_dollars, parts))| {
                let end = parts.iter().map(|part| part.end).max()?;
                let provenance = AnnualProvenance::composed(end, &parts)?;
                Some((
                    year,
                    AnnualValue {
                        year,
                        value_dollars,
                        provenance,
                    },
                ))
            })
            .collect(),
    )
}

/// The widest rate this extractor will admit, in basis points. A filed value
/// that normalizes above it is a different quantity wearing a rate's unit.
const MAXIMUM_REFERENCE_RATE_BPS: f64 = 5_000.0;

/// A filed rate in basis points, whichever of the three conventions the issuer
/// used: a fraction, a percentage, or basis points already.
fn reference_rate_bps(filed: f64) -> Option<i64> {
    let bps = if filed.abs() <= 1.0 {
        filed * 10_000.0
    } else if filed.abs() <= 100.0 {
        filed * 100.0
    } else {
        filed
    };
    (0.0..=MAXIMUM_REFERENCE_RATE_BPS)
        .contains(&bps)
        .then(|| bps.round() as i64)
}

/// Extract a filing's statutory federal rate from the tax reconciliation.  SEC
/// facts commonly encode this as `pure` or `percent`, not USD.
///
/// The magnitude recorded on the source fact is the admitted basis-point
/// integer rather than the filed fraction, and its `unit` is the filed unit
/// string so nobody reads it as dollars. `SecFact` carries one integer
/// magnitude; retyping it belongs to the wave that owns `sec_normalization.rs`.
fn extract_annual_percent_any(facts: &serde_json::Value, concepts: &[&str]) -> Vec<AnnualValue> {
    let mut by_year = HashMap::<i32, AnnualValue>::new();
    for concept in concepts {
        let Some(units) = facts
            .pointer(&format!("/facts/{SEC_TAXONOMY}/{concept}/units"))
            .and_then(serde_json::Value::as_object)
        else {
            continue;
        };
        let Some((unit, entries)) = units
            .iter()
            .find_map(|(unit, value)| value.as_array().map(|entries| (unit, entries)))
        else {
            continue;
        };
        for entry in entries {
            if !is_accepted_annual_entry(entry, FactPeriodShape::Duration) {
                continue;
            }
            let Some(bps) = entry["val"].as_f64().and_then(reference_rate_bps) else {
                continue;
            };
            let Some(observation) =
                AnnualObservation::from_fact(sec_fact_from_entry(entry, concept, unit, bps))
            else {
                continue;
            };
            let Some(provenance) =
                AnnualProvenance::from_observations(observation.end, [&observation])
            else {
                continue;
            };
            by_year
                .entry(observation.end.year())
                .or_insert(AnnualValue {
                    year: observation.end.year(),
                    value_dollars: bps,
                    provenance,
                });
        }
    }
    sorted_by_year(by_year)
}

fn extract_reference_percent(
    facts: &serde_json::Value,
    driver: policy::DriverOperator,
) -> Vec<AnnualValue> {
    assert_eq!(driver.unit, "pure", "reference rate must be unitless");
    assert_eq!(driver.period_shape, "duration");
    assert_eq!(driver.operation, "reference_policy");
    extract_annual_percent_any(facts, driver.qnames)
}

/// Merge CapEx series by fiscal year. When multiple concepts report the same year,
/// keep the larger absolute amount (avoid understating reinvestment; do not sum —
/// tags can overlap in definition; for AT&T overlap years PPE≡ProductiveAssets).
fn merge_capex_by_year(series: &[Vec<AnnualValue>]) -> Vec<AnnualValue> {
    let mut by_year: HashMap<i32, AnnualValue> = HashMap::new();
    for one in series {
        for value in one {
            let replace = by_year
                .get(&value.year)
                .is_none_or(|winner| value.value_dollars.abs() > winner.value_dollars.abs());
            if replace {
                by_year.insert(value.year, value.clone());
            }
        }
    }
    sorted_by_year(by_year)
}

fn extract_capex(facts: &serde_json::Value) -> Vec<AnnualValue> {
    extract_recurring_development(&extract_normalized_investment_evidence(facts))
}

/// Recurring development CapEx per fiscal year: tangible plus capitalized
/// software. Reading only the tangible component reports an issuer that invests
/// through software as barely reinvesting at all.
fn extract_recurring_development(evidence: &NormalizedInvestmentEvidence) -> Vec<AnnualValue> {
    evidence
        .development_total_by_end
        .iter()
        .filter_map(|(end, value_dollars)| {
            let period_end = IsoDate::parse(end)?;
            let mut contributors = Vec::new();
            for fact in contributing_development_facts(evidence, end) {
                contributors.push(AnnualObservation::from_fact(fact.clone())?);
            }
            let provenance = AnnualProvenance::from_observations(period_end, &contributors)?;
            Some(AnnualValue {
                year: period_end.year(),
                value_dollars: *value_dollars,
                provenance,
            })
        })
        .collect()
}

/// The facts whose magnitudes `normalize_investments` actually added together
/// for one fiscal close.
///
/// `PaymentsToAcquireProductiveAssets` is defined by us-gaap as the cash
/// outflow for PP&E, software and other intangibles, so the normalizer leaves
/// the software fact out of the total when the tangible component is that
/// aggregate. A fact that did not contribute to the magnitude is not a source
/// of it, and `known_from` would be wrong if it were listed. Both this reader
/// and `sec_normalization::normalize_investments` decide it from the same
/// generated `DEVELOPMENT_AGGREGATE` constant; the normalizer publishing its
/// own contributing sources would remove the coupling, and that file belongs to
/// another wave.
fn contributing_development_facts<'a>(
    evidence: &'a NormalizedInvestmentEvidence,
    end: &str,
) -> impl Iterator<Item = &'a SecFact> {
    let tangible = evidence.recurring_development_by_end.get(end);
    let software = evidence.software_development_by_end.get(end);
    let aggregate_tangible =
        tangible.is_some_and(|fact| policy::DEVELOPMENT_AGGREGATE.contains(&fact.qname.as_str()));
    tangible
        .into_iter()
        .chain(software.filter(|_| !aggregate_tangible))
}

fn extract_acquisition_investments(evidence: &NormalizedInvestmentEvidence) -> Vec<AnnualValue> {
    let mut by_year = HashMap::<i32, AnnualValue>::new();
    for entry in &evidence.ledger {
        if entry.state != EvidenceState::RejectedAcquisition {
            continue;
        }
        let Some(observation) = AnnualObservation::from_fact(entry.fact.clone()) else {
            continue;
        };
        let Some(provenance) = AnnualProvenance::from_observations(observation.end, [&observation])
        else {
            continue;
        };
        // Investment concepts may be overlapping disclosure alternatives. For
        // the growth-evidence flag we retain the largest disclosed cash amount,
        // never sum them into a synthetic acquisition total.
        let value = AnnualValue {
            year: observation.end.year(),
            value_dollars: observation.fact.value_dollars.abs(),
            provenance,
        };
        let replace = by_year
            .get(&value.year)
            .is_none_or(|winner| value.value_dollars > winner.value_dollars);
        if replace {
            by_year.insert(value.year, value);
        }
    }
    sorted_by_year(by_year)
}

/// Materialize raw SEC cash-investment facts at the provider boundary, then
/// immediately hand them to the typed normalizer.  No EDGAR caller may decide
/// that a QName is recurring CapEx by itself.
fn extract_normalized_investment_evidence(
    facts: &serde_json::Value,
) -> NormalizedInvestmentEvidence {
    let concepts = policy::DEVELOPMENT
        .iter()
        .chain(policy::DEVELOPMENT_SOFTWARE)
        .chain(policy::PROPERTY_ACQUISITION)
        .chain(policy::BUSINESS_ACQUISITION);
    let mut raw_facts = Vec::new();
    for concept in concepts {
        let Some(entries) = facts
            .pointer(&format!("/facts/{SEC_TAXONOMY}/{concept}/units/USD"))
            .and_then(serde_json::Value::as_array)
        else {
            continue;
        };
        for entry in entries {
            let Some(value_dollars) = entry["val"].as_i64() else {
                continue;
            };
            raw_facts.push(sec_fact_from_entry(entry, concept, "USD", value_dollars));
        }
    }
    normalize_investments(raw_facts)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CapexFillKind {
    /// Filed under at least one CapEx concept for that fiscal year.
    Reported,
    /// Last resort: linear interpolation between nearest *merged* reported years.
    /// Only when no concept filed for this year (true taxonomy hole).
    Interpolated,
    /// Outside the merged span: nearest-neighbor abs CapEx.
    Carried,
    /// No CapEx series at all → 0 (cannot invent reinvestment).
    Missing,
}

/// The CapEx one OCF year is charged, and the filed evidence behind it. An
/// imputed year names the neighbours it was imputed from, because an imputed
/// value is knowable no earlier than its last input.
struct CapexFill<'a> {
    value_dollars: i64,
    kind: CapexFillKind,
    sources: Vec<&'a AnnualProvenance>,
}

/// Resolve CapEx (absolute dollars) for an OCF fiscal year against the **merged**
/// multi-concept series (reported tags first; impute only residual holes).
fn resolve_capex_abs<'a>(year: i32, capex_sorted: &[&'a AnnualValue]) -> CapexFill<'a> {
    let missing = CapexFill {
        value_dollars: 0,
        kind: CapexFillKind::Missing,
        sources: Vec::new(),
    };
    if capex_sorted.is_empty() {
        return missing;
    }
    if let Some(reported) = capex_sorted.iter().find(|value| value.year == year) {
        return CapexFill {
            value_dollars: reported.value_dollars.abs(),
            kind: CapexFillKind::Reported,
            sources: vec![&reported.provenance],
        };
    }
    let before = capex_sorted
        .iter()
        .copied()
        .filter(|value| value.year < year)
        .max_by_key(|value| value.year);
    let after = capex_sorted
        .iter()
        .copied()
        .filter(|value| value.year > year)
        .min_by_key(|value| value.year);
    match (before, after) {
        (Some(before), Some(after)) if after.year > before.year => {
            let span = (after.year - before.year) as f64;
            let t = (year - before.year) as f64 / span;
            let a0 = before.value_dollars.abs() as f64;
            let a1 = after.value_dollars.abs() as f64;
            let interp = a0 + (a1 - a0) * t;
            CapexFill {
                value_dollars: interp.round() as i64,
                kind: CapexFillKind::Interpolated,
                sources: vec![&before.provenance, &after.provenance],
            }
        }
        (Some(neighbour), None) | (None, Some(neighbour)) => CapexFill {
            value_dollars: neighbour.value_dollars.abs(),
            kind: CapexFillKind::Carried,
            sources: vec![&neighbour.provenance],
        },
        _ => missing,
    }
}

/// Annual FCF plus whether CapEx was imputed for that year (for diagnostics).
#[derive(Debug, Clone)]
struct FcfAnnual {
    year: i32,
    value_dollars: i64,
    capex_imputed: bool,
    provenance: AnnualProvenance,
}

/// Compute FCF history = OCF - CapEx, aligned by year.
/// Gap years between reported CapEx points are interpolated (not zeroed).
fn fcf_history(ocf: &[AnnualValue], capex: &[AnnualValue]) -> Vec<AnnualValue> {
    fcf_history_detailed(ocf, capex)
        .into_iter()
        .map(|point| AnnualValue {
            year: point.year,
            value_dollars: point.value_dollars,
            provenance: point.provenance,
        })
        .collect()
}

fn fcf_history_detailed(ocf: &[AnnualValue], capex: &[AnnualValue]) -> Vec<FcfAnnual> {
    let mut capex_sorted: Vec<&AnnualValue> = capex.iter().collect();
    capex_sorted.sort_by_key(|value| value.year);
    ocf.iter()
        .filter_map(|operating| {
            let fill = resolve_capex_abs(operating.year, &capex_sorted);
            let parts = once(&operating.provenance).chain(fill.sources);
            // FCF is reported for the operating-cash-flow period even when the
            // CapEx it subtracts was filed for another one, so the composition
            // takes its period identity from the OCF observation. The operating
            // provenance is always present, so there is always a date to
            // compose from.
            let provenance = AnnualProvenance::composed(operating.provenance.end, parts)?;
            Some(FcfAnnual {
                year: operating.year,
                value_dollars: operating.value_dollars - fill.value_dollars,
                capex_imputed: matches!(
                    fill.kind,
                    CapexFillKind::Interpolated | CapexFillKind::Carried
                ),
                provenance,
            })
        })
        .collect()
}

// ── DCF calculation ───────────────────────────────────────────────────────────

/// Result of a DCF computation.
#[derive(Debug, Clone)]
pub struct DcfResult {
    /// Intrinsic value per share in cents.
    pub value_per_share_cents: i64,
}

/// The legacy fixed-rate DCF, over the `(fiscal year, free cash flow)` pairs it
/// actually uses.
///
/// It takes the pairs rather than `AnnualValue`s on purpose: its caller holds
/// `FcfPoint`s, which carry no provenance, so rebuilding `AnnualValue`s here
/// would mean inventing one.
fn compute_dcf(fcf_by_year: &[(i32, i64)], shares_outstanding: u64) -> Option<DcfResult> {
    if fcf_by_year.len() < DCF_MIN_YEARS_HISTORY || shares_outstanding == 0 {
        return None;
    }

    // Use last 4 years (or whatever is available)
    let window = fcf_by_year.iter().rev().take(4).collect::<Vec<_>>();
    let window: Vec<_> = window.into_iter().rev().collect();

    let base_fcf = window.last()?.1;
    // Skip if base FCF is heavily negative (> -$5B): unreliable projection base
    if base_fcf < -5_000_000_000 {
        return None;
    }

    // CAGR from first to last in the window
    let first_fcf = window.first()?.1;
    let years_span = (window.last()?.0 - window.first()?.0) as f64;
    let cagr = if years_span > 0.0 && first_fcf > 0 && base_fcf > 0 {
        ((base_fcf as f64 / first_fcf as f64).powf(1.0 / years_span)) - 1.0
    } else if base_fcf > 0 {
        0.05 // default 5% if we can't compute CAGR
    } else {
        -0.05 // negative FCF → conservative
    };
    let cagr = cagr.clamp(DCF_MIN_GROWTH, DCF_MAX_GROWTH);

    // Stage 1: 5 projected years
    let mut pv = 0.0f64;
    let mut fcf_t = base_fcf as f64;
    for t in 1..=(DCF_PROJECTION_YEARS as i32) {
        fcf_t *= 1.0 + cagr;
        pv += fcf_t / (1.0 + DCF_DISCOUNT_RATE).powi(t);
    }

    // Stage 2: terminal value (Gordon growth model)
    let terminal_fcf = fcf_t * (1.0 + DCF_TERMINAL_GROWTH);
    let terminal_value = terminal_fcf / (DCF_DISCOUNT_RATE - DCF_TERMINAL_GROWTH);
    pv += terminal_value / (1.0 + DCF_DISCOUNT_RATE).powi(DCF_PROJECTION_YEARS as i32);

    if pv <= 0.0 {
        return None;
    }

    let value_per_share_dollars = pv / shares_outstanding as f64;
    let value_per_share_cents = (value_per_share_dollars * 100.0).round() as i64;

    // Sanity cap: if DCF is >10x or <0.1x market-implied, something is off
    // (we'll let callers decide what to do with extreme values)
    if value_per_share_cents <= 0 {
        return None;
    }

    Some(DcfResult {
        value_per_share_cents,
    })
}

// ── Public API ────────────────────────────────────────────────────────────────

// ── Insider activity (Form 4) ─────────────────────────────────────────────────

const INSIDER_WINDOW_DAYS: i64 = 90;
const INSIDER_MAX_FORM4_PER_SYMBOL: usize = 25; // skip noisy symbols (10b5-1 chains)

/// Net insider activity summary over the trailing INSIDER_WINDOW_DAYS.
#[derive(Debug, Clone, serde::Serialize)]
pub struct InsiderSummary {
    /// Total shares acquired by insiders minus total shares disposed.
    pub net_shares_90d: i64,
    /// Number of open-market purchase transactions (Form 4 code P).
    pub buy_count: u32,
    /// Number of sale transactions (Form 4 code S).
    pub sell_count: u32,
    /// Number of Form 4 filings inspected.
    pub filing_count: u32,
}

/// Naive YYYY-MM-DD date arithmetic: subtract `days` from today in UTC.
fn cutoff_date_iso(days: i64) -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let secs = now - days * 86_400;
    // Convert epoch seconds to YYYY-MM-DD using a simple civil-date algorithm.
    let days_since_epoch = secs / 86_400;
    civil_from_days(days_since_epoch)
}

/// Howard Hinnant's algorithm: epoch days → (year, month, day).
fn civil_from_days(days: i64) -> String {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 {
        mp + 3
    } else {
        mp.saturating_sub(9)
    };
    let y = y + if m <= 2 { 1 } else { 0 };
    format!("{:04}-{:02}-{:02}", y, m, d)
}

/// Parse a Form 4 XML and return (buys, sells, net_shares).
/// Simple string-matching parser — no XML library needed.
fn parse_form4_xml(xml: &str) -> (u32, u32, i64) {
    let mut buys = 0u32;
    let mut sells = 0u32;
    let mut net_shares: i64 = 0;

    // Iterate <nonDerivativeTransaction> blocks (skip derivative txns — options noise).
    let mut cursor = 0usize;
    while let Some(start) = xml[cursor..].find("<nonDerivativeTransaction>") {
        let abs_start = cursor + start;
        let end = match xml[abs_start..].find("</nonDerivativeTransaction>") {
            Some(e) => abs_start + e,
            None => break,
        };
        let block = &xml[abs_start..end];
        cursor = end + 1;

        // Extract transactionCode (P = open-market purchase, S = sale)
        let code = extract_tag(block, "transactionCode").unwrap_or_default();
        // Extract transactionShares value
        let shares = extract_nested_value(block, "transactionShares")
            .and_then(|s| s.parse::<f64>().ok())
            .unwrap_or(0.0);
        // Extract transactionAcquiredDisposedCode: A=acquired, D=disposed
        let direction =
            extract_nested_value(block, "transactionAcquiredDisposedCode").unwrap_or_default();

        if shares <= 0.0 {
            continue;
        }
        let signed = if direction == "A" {
            shares as i64
        } else {
            -(shares as i64)
        };
        net_shares += signed;

        match code.trim() {
            "P" => buys += 1,
            "S" => sells += 1,
            _ => {} // skip A (award), M (option exercise), G (gift) for the count
        }
    }
    (buys, sells, net_shares)
}

/// Extract the first occurrence of <tag>value</tag>, returning trimmed `value`.
fn extract_tag(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<{}>", tag);
    let close = format!("</{}>", tag);
    let s = xml.find(&open)? + open.len();
    let e = xml[s..].find(&close)? + s;
    Some(xml[s..e].trim().to_string())
}

/// Extract `<tag><value>X</value>...</tag>` — Form 4 wraps numbers in `<value>` children.
fn extract_nested_value(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<{}>", tag);
    let close = format!("</{}>", tag);
    let s = xml.find(&open)? + open.len();
    let e = xml[s..].find(&close)? + s;
    let block = &xml[s..e];
    extract_tag(block, "value").or_else(|| Some(block.trim().to_string()))
}

/// Fetch recent Form 4 filings for a CIK and aggregate insider activity.
pub fn fetch_insider_activity(client: &Client, cik: u64) -> Result<Option<InsiderSummary>, String> {
    let url = format!("https://data.sec.gov/submissions/CIK{:010}.json", cik);
    let body: serde_json::Value = client
        .get(&url)
        .header("Accept", "application/json")
        .send()
        .map_err(|e| format!("submissions: {}", e))?
        .json()
        .map_err(|e| format!("submissions parse: {}", e))?;

    let recent = match body.pointer("/filings/recent") {
        Some(v) => v,
        None => return Ok(None),
    };
    let forms = match recent["form"].as_array() {
        Some(a) => a,
        None => return Ok(None),
    };
    let dates = match recent["filingDate"].as_array() {
        Some(a) => a,
        None => return Ok(None),
    };
    let accessions = match recent["accessionNumber"].as_array() {
        Some(a) => a,
        None => return Ok(None),
    };
    let primary_docs = match recent["primaryDocument"].as_array() {
        Some(a) => a,
        None => return Ok(None),
    };

    let cutoff = cutoff_date_iso(INSIDER_WINDOW_DAYS);

    // Collect Form 4 filings within window, capped
    let mut targets: Vec<(String, String)> = Vec::new(); // (accession, primary_doc)
    for i in 0..forms
        .len()
        .min(dates.len())
        .min(accessions.len())
        .min(primary_docs.len())
    {
        if forms[i].as_str() != Some("4") {
            continue;
        }
        let date = dates[i].as_str().unwrap_or("");
        if date < cutoff.as_str() {
            continue;
        }
        let acc = accessions[i].as_str().unwrap_or("").to_string();
        let doc = primary_docs[i].as_str().unwrap_or("").to_string();
        if acc.is_empty() || doc.is_empty() {
            continue;
        }
        targets.push((acc, doc));
        if targets.len() >= INSIDER_MAX_FORM4_PER_SYMBOL {
            break;
        }
    }

    if targets.is_empty() {
        return Ok(Some(InsiderSummary {
            net_shares_90d: 0,
            buy_count: 0,
            sell_count: 0,
            filing_count: 0,
        }));
    }

    let mut total_buys = 0u32;
    let mut total_sells = 0u32;
    let mut total_net: i64 = 0;
    let mut inspected = 0u32;

    for (accession, doc) in &targets {
        let acc_no_dashes = accession.replace("-", "");
        let url = format!(
            "https://www.sec.gov/Archives/edgar/data/{}/{}/{}",
            cik, acc_no_dashes, doc
        );

        let xml = match client.get(&url).send().and_then(|r| r.text()) {
            Ok(t) => t,
            Err(_) => continue,
        };
        let (b, s, net) = parse_form4_xml(&xml);
        total_buys += b;
        total_sells += s;
        total_net += net;
        inspected += 1;

        // Respect SEC rate limit between Form 4 reads
        std::thread::sleep(std::time::Duration::from_millis(125));
    }

    Ok(Some(InsiderSummary {
        net_shares_90d: total_net,
        buy_count: total_buys,
        sell_count: total_sells,
        filing_count: inspected,
    }))
}

/// Fetch EDGAR annual FCF series (OCF − CapEx) for transparent multi-scenario DCF.
/// The raw companyfacts document for one issuer.
///
/// Separate from `fetch_fcf_history` so a caller that needs to see which qname a
/// driver actually resolved to can extract concept by concept, rather than only
/// seeing the merged series the history returns.
pub fn fetch_company_facts(
    client: &Client,
    symbol: &str,
    cik: u64,
) -> Result<serde_json::Value, String> {
    let url = format!(
        "https://data.sec.gov/api/xbrl/companyfacts/CIK{:010}.json",
        cik
    );
    client
        .get(&url)
        .header("Accept", "application/json")
        .send()
        .map_err(|e| format!("FetchFailed: EDGAR {}: {}", symbol, e))?
        .json()
        .map_err(|e| format!("FetchFailed: EDGAR parse {}: {}", symbol, e))
}

pub fn fetch_fcf_history(
    client: &Client,
    symbol: &str,
    cik: u64,
) -> Result<Option<Vec<FcfPoint>>, String> {
    let body = fetch_company_facts(client, symbol, cik)?;

    let ocf = extract_driver_annual(&body, policy::OPERATING_CASH_FLOW);
    let investment_evidence = extract_normalized_investment_evidence(&body);
    let capex = extract_recurring_development(&investment_evidence);
    let acquisition_investments = extract_acquisition_investments(&investment_evidence);
    let diluted_shares = extract_driver_annual(&body, policy::DILUTED_AVERAGE_SHARES);
    let revenue = extract_driver_annual(&body, policy::REVENUE);
    let interest = extract_driver_annual(&body, policy::INTEREST_EXPENSE);
    let pretax = extract_driver_annual(&body, policy::PRETAX_INCOME);
    let tax = extract_driver_annual(&body, policy::TAX_EXPENSE);
    let equity = extract_driver_annual(&body, policy::STOCKHOLDERS_EQUITY);
    let debt = extract_total_debt(&body);
    let marginal_tax = extract_reference_percent(&body, policy::MARGINAL_TAX_REFERENCE);

    if ocf.is_empty() {
        return Err("NoApprovedConcept: SEC operating cash-flow duration facts missing".into());
    }

    // The normalizer boundary refuses an invented recurring cash-flow driver.
    // Keep the legacy fill helper for historical diagnostics, but FCFF may only
    // consume periods backed by an approved reported development fact.
    let fcf: Vec<_> = fcf_history_detailed(&ocf, &capex)
        .into_iter()
        .filter(|point| !point.capex_imputed)
        .collect();
    if fcf.len() < 3 {
        return Err(
            "NoApprovedConcept: fewer than three approved recurring-development CapEx periods"
                .into(),
        );
    }
    let by_year = |series: &[AnnualValue], year: i32| {
        series
            .iter()
            .find(|value| value.year == year)
            .map(|value| value.value_dollars as f64)
    };

    // A discontinued operation restates revenue down to continuing operations
    // and leaves the cash-flow statement whole-company (ASC 205-20). Years where
    // only one of the two moved divide one entity by another.
    let restated_revenue_years = restated_years(&body, policy::REVENUE);
    let restated_ocf_years = restated_years(&body, policy::OPERATING_CASH_FLOW);

    Ok(Some(
        fcf.into_iter()
            .map(|v| {
                let operating_cash_flow = by_year(&ocf, v.year).unwrap_or(v.value_dollars as f64);
                let capital_expenditure = by_year(&capex, v.year);
                let revenue_dollars = by_year(&revenue, v.year);
                let acquisition_investment_dollars = by_year(&acquisition_investments, v.year);
                let interest_expense_dollars = by_year(&interest, v.year);
                let total_debt_dollars = by_year(&debt, v.year);
                let marginal_tax_bps = by_year(&marginal_tax, v.year).map(|value| value as i32);
                let pretax_income_dollars = by_year(&pretax, v.year);
                let stockholders_equity_dollars = by_year(&equity, v.year);
                let tax_rate_bps = match (by_year(&tax, v.year), pretax_income_dollars) {
                    (Some(tax_expense), Some(pretax_income)) if pretax_income.abs() > 0.0 => Some(
                        ((tax_expense.abs() / pretax_income.abs()) * 10_000.0)
                            .round()
                            .clamp(0.0, 3_500.0) as i32,
                    ),
                    _ => None,
                };
                let mut point = FcfPoint::new(v.year, v.value_dollars as f64);
                point.capex_imputed = v.capex_imputed;
                if !capex.is_empty() {
                    if let (Some(capital_expenditure), Some(revenue_dollars)) =
                        (capital_expenditure, revenue_dollars)
                    {
                        point = point.with_operating_drivers(
                            operating_cash_flow,
                            capital_expenditure,
                            revenue_dollars,
                            interest_expense_dollars,
                            tax_rate_bps,
                        );
                        point = point.with_rate_resolution_inputs(
                            total_debt_dollars,
                            marginal_tax_bps,
                            None,
                            None,
                        );
                        point = point.with_return_on_capital_inputs(
                            pretax_income_dollars,
                            stockholders_equity_dollars,
                        );
                        point = point.with_acquisition_investment(acquisition_investment_dollars);
                        point = point.with_diluted_average_shares(by_year(&diluted_shares, v.year));
                        point = point.with_reporting_basis_broken(
                            restated_revenue_years.contains(&v.year)
                                && !restated_ocf_years.contains(&v.year),
                        );
                        if marginal_tax_bps.is_some() {
                            point =
                                point.with_marginal_tax_source(WaccFieldSource::TaxReconciliation);
                        }
                    }
                }
                point
            })
            .collect(),
    ))
}

/// Resolve the latest issuer share count from SEC DEI facts when Yahoo omits
/// `sharesOutstanding`. This is a provider fallback for a required unit
/// conversion, not an analyst/market valuation input.
pub fn fetch_shares_outstanding(
    client: &Client,
    symbol: &str,
    cik: u64,
) -> Result<Option<u64>, String> {
    let cik_padded = format!("{:010}", cik);
    let url = format!(
        "https://data.sec.gov/api/xbrl/companyfacts/CIK{}.json",
        cik_padded
    );
    let body: serde_json::Value = client
        .get(url)
        .header("Accept", "application/json")
        .send()
        .map_err(|e| format!("EDGAR shares {}: {}", symbol, e))?
        .json()
        .map_err(|e| format!("EDGAR shares parse {}: {}", symbol, e))?;
    Ok(extract_current_shares(&body))
}

fn extract_current_shares(facts: &serde_json::Value) -> Option<u64> {
    let units = facts.pointer("/facts/dei/EntityCommonStockSharesOutstanding/units/shares")?;
    let values = units.as_array()?;
    values
        .iter()
        .filter(|entry| matches!(entry["form"].as_str(), Some("10-K" | "10-Q" | "8-K")))
        .filter_map(|entry| {
            Some((
                entry["end"].as_str()?.to_string(),
                entry["filed"].as_str().unwrap_or("").to_string(),
                u64::try_from(entry["val"].as_i64()?).ok()?,
            ))
        })
        .max_by(|left, right| {
            (left.0.as_str(), left.1.as_str()).cmp(&(right.0.as_str(), right.1.as_str()))
        })
        .map(|(_, _, shares)| shares)
}

/// Fetch EDGAR data for a symbol and compute legacy fixed-10% DCF intrinsic value.
/// Prefer `fetch_fcf_history` + `dcf_model::compute` for transparent WACC.
pub fn fetch_dcf(
    client: &Client,
    symbol: &str,
    cik: u64,
    shares_outstanding: u64,
) -> Result<Option<DcfResult>, String> {
    let Some(points) = fetch_fcf_history(client, symbol, cik)? else {
        return Ok(None);
    };
    let fcf_by_year: Vec<(i32, i64)> = points
        .iter()
        .map(|point| (point.year, point.value_dollars as i64))
        .collect();
    Ok(compute_dcf(&fcf_by_year, shares_outstanding))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sec_normalization::InvestmentCategory;

    /// One annual observation for a test, built the way production builds one:
    /// from a filed fact, so the period end decides the fiscal year and the
    /// filing date decides when the value became knowable. Nothing here can
    /// fabricate a provenance that production could not have produced.
    fn annual(year: i32, value_dollars: i64, filed: &str) -> AnnualValue {
        let end = IsoDate::parse(&format!("{year}-12-31")).expect("test period end");
        let observation =
            test_observation("SyntheticDriver", &end.to_string(), filed, value_dollars);
        AnnualValue {
            year: observation.end.year(),
            value_dollars,
            provenance: AnnualProvenance::from_observations(observation.end, [&observation])
                .expect("test provenance"),
        }
    }

    /// Resolve a synthetic USD concept the way a driver resolves its own.
    ///
    /// Production readers take a policy driver; these fixtures pin the
    /// resolution rules themselves, so they name the concepts directly rather
    /// than borrowing a driver whose qname list would then be part of the test.
    /// No driver contract backs this path, so every concept is treated as
    /// already-positive: an all-ones sign list the same length as `concepts`.
    fn resolved_annual(
        facts: &serde_json::Value,
        concepts: &[&str],
        shape: FactPeriodShape,
    ) -> Vec<AnnualValue> {
        let signs = vec![1i8; concepts.len()];
        concept_vintages(facts, concepts, &signs, shape, "USD").latest()
    }

    /// A filed -63 under the concept declared negated resolves to +63, while a
    /// filed +200 under the sibling concept in the same call is untouched —
    /// proof the sign is a per-concept property applied at resolution, not a
    /// blanket flip over the whole series.
    #[test]
    fn concept_vintages_applies_each_concept_its_own_sign() {
        let facts = serde_json::json!({
            "facts": {
                "us-gaap": {
                    "PositiveConcept": {
                        "units": { "USD": [{
                            "form": "10-K",
                            "start": "2024-01-01",
                            "end": "2024-12-31",
                            "filed": "2025-02-20",
                            "accn": "0000000000-25-000001",
                            "val": 200,
                        }] }
                    },
                    "NegatedConcept": {
                        "units": { "USD": [{
                            "form": "10-K",
                            "start": "2023-01-01",
                            "end": "2023-12-31",
                            "filed": "2024-02-20",
                            "accn": "0000000000-24-000001",
                            "val": -63,
                        }] }
                    }
                }
            }
        });

        let series = concept_vintages(
            &facts,
            &["PositiveConcept", "NegatedConcept"],
            &[1, -1],
            FactPeriodShape::Duration,
            "USD",
        )
        .latest();

        let observed: Vec<(i32, i64)> = series
            .iter()
            .map(|value| (value.year, value.value_dollars))
            .collect();
        assert_eq!(observed, vec![(2023, 63), (2024, 200)]);
    }

    /// A driver whose sign list disagrees in length with its qname list has a
    /// broken contract; pairing the two lists up by guesswork would fabricate
    /// a convention nobody declared, so this refuses instead of resolving.
    #[test]
    #[should_panic(expected = "concept list and sign list must have equal length")]
    fn concept_vintages_panics_when_signs_and_concepts_disagree_in_length() {
        let facts = serde_json::json!({ "facts": { "us-gaap": {} } });
        concept_vintages(
            &facts,
            &["OnlyConcept"],
            &[1, -1],
            FactPeriodShape::Duration,
            "USD",
        );
    }

    /// A filed fact for a test, complete enough to survive the fail-closed
    /// admission: a parseable period end, filing date and accession.
    fn test_observation(
        qname: &str,
        end: &str,
        filed: &str,
        value_dollars: i64,
    ) -> AnnualObservation {
        AnnualObservation::from_fact(SecFact {
            qname: qname.to_owned(),
            taxonomy: SEC_TAXONOMY.into(),
            value_dollars,
            start: None,
            end: end.to_owned(),
            unit: "USD".into(),
            form: "10-K".into(),
            accession: Some(format!("{qname}-{filed}")),
            filed: Some(filed.to_owned()),
            consolidated: true,
        })
        .expect("test fact is admissible")
    }

    /// Two 10-K filings reporting the same fiscal end. The 2025 filing restates
    /// FY2023 revenue down to continuing operations after a separation and
    /// leaves operating cash flow on the total-company basis — the exact WDC
    /// signature (12.318B → 6.255B revenue, OCF untouched).
    fn separation_facts() -> serde_json::Value {
        let annual = |filed: &str, val: i64| {
            serde_json::json!({
                "form": "10-K",
                "start": "2022-07-02",
                "end": "2023-06-30",
                "filed": filed,
                "accn": format!("0000106040-{}-000001", &filed[2..4]),
                "val": val,
            })
        };
        serde_json::json!({
            "facts": {
                "us-gaap": {
                    "RevenueFromContractWithCustomerExcludingAssessedTax": {
                        "units": {
                            "USD": [
                                annual("2023-08-22", 12_318_000_000_i64),
                                annual("2025-08-14", 6_255_000_000_i64),
                            ]
                        }
                    },
                    "NetCashProvidedByUsedInOperatingActivities": {
                        "units": {
                            "USD": [
                                annual("2023-08-22", -409_000_000_i64),
                                annual("2025-08-14", -409_000_000_i64),
                            ]
                        }
                    }
                }
            }
        })
    }

    #[test]
    fn discontinued_operation_marks_revenue_restated_but_not_cash_flow() {
        let facts = separation_facts();

        let restated_revenue = restated_years(&facts, policy::REVENUE);
        let restated_ocf = restated_years(&facts, policy::OPERATING_CASH_FLOW);

        assert_eq!(
            (
                restated_revenue.contains(&2023),
                restated_ocf.contains(&2023)
            ),
            (true, false)
        );
    }

    #[test]
    fn restatement_keeps_the_latest_filed_value() {
        let series = resolved_annual(
            &separation_facts(),
            &["RevenueFromContractWithCustomerExcludingAssessedTax"],
            FactPeriodShape::Duration,
        );

        let observed: Vec<(i32, i64)> = series
            .iter()
            .map(|value| (value.year, value.value_dollars))
            .collect();
        assert_eq!(observed, vec![(2023, 6_255_000_000)]);
    }

    #[test]
    #[ignore = "network: driver year-coverage probe"]
    fn probe_driver_coverage() {
        let client = edgar_client();
        let cik_map = fetch_cik_map(&client).expect("CIK");
        for &symbol in &[
            "AAPL", "MSFT", "GOOGL", "AMZN", "CSCO", "PG", "JNJ", "TXN", "HD", "KO",
        ] {
            let cik = cik_map[symbol];
            let url = format!(
                "https://data.sec.gov/api/xbrl/companyfacts/CIK{:010}.json",
                cik
            );
            let body: serde_json::Value = client
                .get(&url)
                .header("Accept", "application/json")
                .send()
                .unwrap()
                .json()
                .unwrap();
            let last = |series: &[AnnualValue]| series.last().map(|v| v.year).unwrap_or(-1);
            let ocf = extract_driver_annual(&body, policy::OPERATING_CASH_FLOW);
            let revenue = extract_driver_annual(&body, policy::REVENUE);
            let interest = extract_driver_annual(&body, policy::INTEREST_EXPENSE);
            let pretax = extract_driver_annual(&body, policy::PRETAX_INCOME);
            let tax = extract_driver_annual(&body, policy::TAX_EXPENSE);
            let evidence = extract_normalized_investment_evidence(&body);
            let capex = extract_recurring_development(&evidence);
            eprintln!(
                "COV {symbol:<6} ocf={:<5} capex={:<5} rev={:<5} int={:<5} pretax={:<5} tax={:<5}",
                last(&ocf),
                last(&capex),
                last(&revenue),
                last(&interest),
                last(&pretax),
                last(&tax),
            );
            let recent: Vec<i32> = capex.iter().rev().take(4).map(|v| v.year).collect();
            eprintln!("    capex years (last 4, desc) = {recent:?}");
        }
    }

    #[test]
    #[ignore = "network: raw XBRL restatement probe"]
    fn probe_restated_facts() {
        let client = edgar_client();
        let cik_map = fetch_cik_map(&client).expect("CIK");
        for &symbol in &["WDC", "AAPL", "MSFT", "GOOGL", "AMZN"] {
            let cik = cik_map[symbol];
            let url = format!(
                "https://data.sec.gov/api/xbrl/companyfacts/CIK{:010}.json",
                cik
            );
            let body: serde_json::Value = client
                .get(&url)
                .header("Accept", "application/json")
                .send()
                .unwrap()
                .json()
                .unwrap();
            for (label, concepts) in [
                ("REV", policy::REVENUE.qnames),
                ("OCF", policy::OPERATING_CASH_FLOW.qnames),
            ] {
                let mut by_end: HashMap<String, Vec<(String, i64, String)>> = HashMap::new();
                for concept in concepts {
                    let Some(arr) = body
                        .pointer(&format!("/facts/us-gaap/{concept}/units/USD"))
                        .and_then(|v| v.as_array())
                    else {
                        continue;
                    };
                    for entry in arr {
                        let form = entry["form"].as_str().unwrap_or("");
                        if !policy::ACCEPTED_FORMS.contains(&form) {
                            continue;
                        }
                        if !has_approved_period_shape(entry, FactPeriodShape::Duration) {
                            continue;
                        }
                        if entry["frame"].as_str().is_some_and(|f| f.contains('Q')) {
                            continue;
                        }
                        if entry.get("segment").is_some_and(|s| !s.is_null()) {
                            continue;
                        }
                        let end = entry["end"].as_str().unwrap_or("").to_string();
                        if end < "2021".to_string() {
                            continue;
                        }
                        by_end.entry(end).or_default().push((
                            entry["filed"].as_str().unwrap_or("").to_string(),
                            entry["val"].as_i64().unwrap_or(0),
                            (*concept).to_string(),
                        ));
                    }
                }
                let mut ends: Vec<_> = by_end.into_iter().collect();
                ends.sort();
                for (end, mut rows) in ends {
                    rows.sort();
                    rows.dedup_by(|a, b| a.1 == b.1 && a.2 == b.2);
                    if rows.len() < 2 {
                        continue;
                    }
                    let distinct: std::collections::HashSet<i64> =
                        rows.iter().map(|r| r.1).collect();
                    if distinct.len() < 2 {
                        continue;
                    }
                    eprintln!("{symbol} {label} end={end}");
                    for (filed, val, concept) in rows {
                        eprintln!("    filed={filed} val={:.3}B {concept}", val as f64 / 1e9);
                    }
                }
            }
        }
    }

    /// Every annual investing outflow the issuer actually files, whether or not
    /// the CapEx policy recognizes it. An issuer that invests through
    /// capitalized software rather than plant reports nothing under
    /// `PaymentsToAcquirePropertyPlantAndEquipment`, and the CapEx line comes
    /// back near zero without any refusal to say so.
    ///
    /// Run: cargo test --lib edgar::tests::probe_investing_outflows -- --ignored --nocapture
    #[test]
    #[ignore = "network: raw XBRL investing-concept probe"]
    fn probe_investing_outflows() {
        let client = edgar_client();
        let cik_map = fetch_cik_map(&client).expect("CIK");
        for &symbol in &[
            "DVN", "FIS", "AVY", "SW", "COF", "MPWR", "APH", "EME", "CHTR", "BKR", "INTU", "TER",
            "AVGO", "EPAM", "T", "GEHC", "DAL", "WDC", "GOOGL", "HPE", "CRM", "SLB", "EXE", "OMC",
            "PTC", "PG", "AMZN", "MSFT",
        ] {
            let Some(&cik) = cik_map.get(symbol) else {
                eprintln!("{symbol}: no cik");
                continue;
            };
            let url = format!(
                "https://data.sec.gov/api/xbrl/companyfacts/CIK{:010}.json",
                cik
            );
            let body: serde_json::Value = client
                .get(&url)
                .header("Accept", "application/json")
                .send()
                .unwrap()
                .json()
                .unwrap();
            let Some(gaap) = body.pointer("/facts/us-gaap").and_then(|v| v.as_object()) else {
                eprintln!("{symbol}: no us-gaap facts");
                continue;
            };
            // The quantity the software component actually changed, isolated from
            // every other input: the tangible-only selection this policy used
            // before against the summed total it uses now. A name whose two
            // columns match cannot have moved for this reason.
            let evidence = extract_normalized_investment_evidence(&body);
            let mut moved = Vec::new();
            for (end, total) in &evidence.development_total_by_end {
                let tangible = evidence
                    .recurring_development_by_end
                    .get(end)
                    .map_or(0, |fact| fact.value_dollars.abs());
                if tangible != total.abs() {
                    moved.push(format!(
                        "{end}: {:.3}B->{:.3}B",
                        tangible as f64 / 1e9,
                        total.abs() as f64 / 1e9
                    ));
                }
            }
            eprintln!(
                "CAPEX {symbol:<6} {}",
                if moved.is_empty() {
                    "unchanged".to_string()
                } else {
                    moved.join("  ")
                }
            );

            let mut rows: Vec<(String, String, f64)> = Vec::new();
            for (concept, node) in gaap {
                // Only the recognized CapEx concepts and every software-investment
                // concept, recognized or not — the question is whether an issuer
                // invests through a line the policy cannot see.
                let recognized = policy::DEVELOPMENT.contains(&concept.as_str());
                let software = concept.contains("Software");
                if !recognized && !software {
                    continue;
                }
                let Some(arr) = node.pointer("/units/USD").and_then(|v| v.as_array()) else {
                    continue;
                };
                let mut latest: HashMap<String, (String, i64)> = HashMap::new();
                for entry in arr {
                    let form = entry["form"].as_str().unwrap_or("");
                    if !policy::ACCEPTED_FORMS.contains(&form) {
                        continue;
                    }
                    if !has_approved_period_shape(entry, FactPeriodShape::Duration) {
                        continue;
                    }
                    if entry.get("segment").is_some_and(|s| !s.is_null()) {
                        continue;
                    }
                    let end = entry["end"].as_str().unwrap_or("").to_string();
                    if end.as_str() < "2023" {
                        continue;
                    }
                    let filed = entry["filed"].as_str().unwrap_or("").to_string();
                    let value = entry["val"].as_i64().unwrap_or(0);
                    let replace = latest.get(&end).is_none_or(|(prior, _)| *prior < filed);
                    if replace {
                        latest.insert(end, (filed, value));
                    }
                }
                for (end, (_, value)) in latest {
                    if value.abs() < 10_000_000 {
                        continue;
                    }
                    rows.push((end, concept.clone(), value as f64 / 1e9));
                }
            }
            // Only the newest fiscal end: the question is whether the current
            // CapEx line is complete, not how it evolved.
            let Some(newest) = rows.iter().map(|(end, _, _)| end.clone()).max() else {
                eprintln!("{symbol:<6} no capex-class facts");
                continue;
            };
            let mut current: Vec<&(String, String, f64)> =
                rows.iter().filter(|(end, _, _)| *end == newest).collect();
            current.sort_by(|a, b| b.2.abs().total_cmp(&a.2.abs()));
            let software_total: f64 = current
                .iter()
                .filter(|(_, concept, _)| {
                    concept.starts_with("Payments") && concept.contains("Software")
                })
                .map(|(_, _, value)| value.abs())
                .sum();
            eprintln!(
                "{symbol:<6} {newest}  software_payments={software_total:>7.3}B  {}",
                current
                    .iter()
                    .map(|(_, concept, value)| format!("{concept}={value:.3}B"))
                    .collect::<Vec<_>>()
                    .join("  ")
            );
        }
    }

    #[test]
    fn merge_capex_prefers_larger_abs_per_year() {
        let ppe = vec![annual(2017, -20_000_000_000, "2018-02-01")];
        let productive = vec![
            annual(2017, -1_000_000_000, "2018-02-01"),
            annual(2024, -20_260_000_000, "2025-02-01"),
        ];
        let merged = merge_capex_by_year(&[ppe, productive]);
        assert_eq!(merged.len(), 2);
        assert_eq!(merged[0].year, 2017);
        assert_eq!(merged[0].value_dollars.abs(), 20_000_000_000);
        assert_eq!(merged[1].year, 2024);
        assert_eq!(merged[1].value_dollars.abs(), 20_260_000_000);
    }

    #[test]
    fn crgy_property_acquisition_is_not_merged_into_development_capex() {
        let facts = serde_json::json!({
            "facts": { "us-gaap": {
                "PaymentsToExploreAndDevelopOilAndGasProperties": { "units": { "USD": [{
                    "form": "10-K", "start": "2024-01-01", "end": "2024-12-31", "val": 685700000,
                    "filed": "2025-02-20", "accn": "0001922446-25-000012"
                }] } },
                "PaymentsToAcquireOilAndGasProperty": { "units": { "USD": [{
                    "form": "10-K", "start": "2024-01-01", "end": "2024-12-31", "val": 558600000,
                    "filed": "2025-02-20", "accn": "0001922446-25-000012"
                }] } }
            }}
        });
        let capex = extract_capex(&facts);
        assert_eq!(capex.len(), 1);
        assert_eq!(capex[0].year, 2024);
        assert_eq!(capex[0].value_dollars, 685_700_000);
        let evidence = extract_normalized_investment_evidence(&facts);
        assert!(evidence.ledger.iter().any(|entry| {
            entry.fact.qname == "PaymentsToAcquireOilAndGasProperty"
                && entry.category == InvestmentCategory::PropertyAcquisition
                && entry.state == EvidenceState::RejectedAcquisition
        }));
        let acquisitions = extract_acquisition_investments(&evidence);
        assert_eq!(acquisitions.len(), 1);
        assert_eq!(acquisitions[0].year, 2024);
        assert_eq!(acquisitions[0].value_dollars, 558_600_000);
    }

    #[test]
    fn annual_extraction_accepts_later_ten_k_amendment() {
        let facts = serde_json::json!({
            "facts": { "us-gaap": { "SyntheticCapex": { "units": { "USD": [
                {"form":"10-K", "start":"2024-01-01", "end":"2024-12-31", "val":100, "filed":"2025-02-01", "accn":"0000000001-25-000001"},
                {"form":"10-K/A", "start":"2024-01-01", "end":"2024-12-31", "val":120, "filed":"2025-03-01", "accn":"0000000001-25-000002"}
            ] } } } }
        });
        let annual = resolved_annual(&facts, &["SyntheticCapex"], FactPeriodShape::Any);
        assert_eq!(annual.len(), 1);
        assert_eq!(annual[0].value_dollars, 120);
    }

    #[test]
    fn annual_extraction_prefers_consolidated_annual_over_segment_and_quarter() {
        let facts = serde_json::json!({
            "facts": {"us-gaap": {"SyntheticRevenue": {"units": {"USD": [
                {"form":"10-K", "end":"2024-12-31", "fy":2024, "fp":"FY", "val":100, "filed":"2025-02-01", "accn":"0000000002-25-000001"},
                {"form":"10-K", "end":"2024-12-31", "fy":2024, "fp":"FY", "val":20, "filed":"2026-02-01", "accn":"0000000002-26-000001", "segment":{"dimension":"region"}},
                {"form":"10-K", "end":"2024-12-31", "fy":2024, "fp":"FY", "val":30, "filed":"2026-02-01", "accn":"0000000002-26-000001", "frame":"CY2024Q4"},
                {"form":"10-K", "end":"2025-12-31", "fy":2025, "fp":"FY", "val":110, "filed":"2026-02-01", "accn":"0000000002-26-000001"},
                {"form":"10-Q", "end":"2025-03-31", "fy":2025, "fp":"Q1", "val":25, "filed":"2025-05-01", "accn":"0000000002-25-000002"}
            ]}}}}
        });

        let annual = resolved_annual(&facts, &["SyntheticRevenue"], FactPeriodShape::Any);
        assert_eq!(
            annual
                .iter()
                .map(|value| (value.year, value.value_dollars))
                .collect::<Vec<_>>(),
            vec![(2024, 100), (2025, 110)]
        );
    }

    #[test]
    fn annual_extraction_keeps_comparatives_with_current_filing_fy() {
        let facts = serde_json::json!(
            {
                "facts": {"us-gaap": {"SyntheticRevenue": {"units": {"USD": [
                    // A current 10-K reports the prior year comparative with
                    // the current filing fy. Both are valid annual facts.
                    {"form":"10-K", "end":"2025-01-26", "fy":2026, "fp":"FY", "frame":"CY2024", "val":130497, "filed":"2026-02-25", "accn":"0001045810-26-000023"},
                    {"form":"10-K", "end":"2026-01-25", "fy":2026, "fp":"FY", "frame":"CY2025", "val":215938, "filed":"2026-02-25", "accn":"0001045810-26-000023"}
                ]}}}}
            }
        );

        let annual = resolved_annual(&facts, &["SyntheticRevenue"], FactPeriodShape::Any);
        assert_eq!(
            annual
                .iter()
                .map(|value| (value.year, value.value_dollars))
                .collect::<Vec<_>>(),
            vec![(2025, 130497), (2026, 215938)]
        );
    }

    #[test]
    fn equivalent_xbrl_concepts_merge_history_without_overwriting_precedence() {
        let facts = serde_json::json!(
            {
                "facts": {"us-gaap": {
                    "SyntheticRevenuePrimary": {"units": {"USD": [
                        {"form":"10-K", "end":"2024-12-31", "fy":2024, "fp":"FY", "val":400, "filed":"2025-02-01", "accn":"0000000003-25-000001"},
                        {"form":"10-K", "end":"2025-12-31", "fy":2025, "fp":"FY", "val":500, "filed":"2026-02-01", "accn":"0000000003-26-000001"}
                    ]}},
                    "SyntheticRevenueLegacy": {"units": {"USD": [
                        {"form":"10-K", "end":"2023-12-31", "fy":2023, "fp":"FY", "val":300, "filed":"2024-02-01", "accn":"0000000003-24-000001"},
                        {"form":"10-K", "end":"2024-12-31", "fy":2024, "fp":"FY", "val":999, "filed":"2025-02-01", "accn":"0000000003-25-000001"}
                    ]}}
                }}
            }
        );

        let annual = resolved_annual(
            &facts,
            &["SyntheticRevenuePrimary", "SyntheticRevenueLegacy"],
            FactPeriodShape::Any,
        );
        assert_eq!(
            annual
                .iter()
                .map(|value| (value.year, value.value_dollars))
                .collect::<Vec<_>>(),
            vec![(2023, 300), (2024, 400), (2025, 500)]
        );
    }

    #[test]
    fn current_shares_prefers_latest_filed_dei_observation() {
        let facts = serde_json::json!(
            {
                "facts": {"dei": {"EntityCommonStockSharesOutstanding": {"units": {"shares": [
                    {"form":"10-Q", "end":"2025-03-31", "val":1000, "filed":"2025-05-01"},
                    {"form":"10-K", "end":"2025-12-31", "val":1200, "filed":"2026-02-01"},
                    {"form":"8-K", "end":"2026-01-15", "val":1250, "filed":"2026-02-10"}
                ]}}}}
            }
        );
        assert_eq!(extract_current_shares(&facts), Some(1_250));
    }

    #[test]
    fn fcf_subtracts_capex_not_ocf_alone() {
        let ocf = vec![
            annual(2023, 38_310_000_000, "2024-02-01"),
            annual(2024, 38_770_000_000, "2025-02-01"),
        ];
        let capex = vec![
            annual(2023, -17_850_000_000, "2024-02-01"),
            annual(2024, -20_260_000_000, "2025-02-01"),
        ];
        let fcf = fcf_history(&ocf, &capex);
        assert_eq!(fcf[0].value_dollars, 38_310_000_000 - 17_850_000_000);
        assert_eq!(fcf[1].value_dollars, 38_770_000_000 - 20_260_000_000);
        // Regression: missing CapEx must not leave FCF == OCF for capex-heavy issuers.
        assert!(fcf[1].value_dollars < ocf[1].value_dollars / 2 + 5_000_000_000);
    }

    #[test]
    fn missing_capex_year_zeros_not_panics() {
        let ocf = vec![annual(2024, 40_000_000_000, "2025-02-01")];
        let fcf = fcf_history(&ocf, &[]);
        assert_eq!(fcf[0].value_dollars, 40_000_000_000);
    }

    /// AT&T companyfacts: PPE stops 2017, but ProductiveAssets **files** 2018–2020.
    /// Merge must use those reported tags — not invent a false gap and interpolate.
    /// Values from SEC companyfacts CIK 0000732717 (10-K USD).
    #[test]
    fn att_2018_2020_uses_reported_productive_assets_not_interpolation() {
        let ocf: Vec<AnnualValue> = (2017..=2021)
            .map(|year| annual(year, 40_000_000_000, &format!("{}-02-01", year + 1)))
            .collect();
        let ppe = vec![annual(2017, -20_650_000_000, "2018-02-01")];
        let productive = vec![
            // overlap: PPE ≡ ProductiveAssets (ratio 1.0)
            annual(2017, -20_650_000_000, "2018-02-01"),
            annual(2018, -21_251_000_000, "2019-02-01"),
            annual(2019, -19_635_000_000, "2020-02-01"),
            annual(2020, -14_690_000_000, "2021-02-01"),
            annual(2021, -15_545_000_000, "2022-02-01"),
        ];
        let merged = merge_capex_by_year(&[ppe, productive]);
        let detailed = fcf_history_detailed(&ocf, &merged);
        let by_year: HashMap<i32, &FcfAnnual> = detailed.iter().map(|p| (p.year, p)).collect();

        for (year, expected_capex) in [
            (2018, 21_251_000_000_i64),
            (2019, 19_635_000_000),
            (2020, 14_690_000_000),
        ] {
            let p = by_year[&year];
            assert!(
                !p.capex_imputed,
                "{year} must use reported CapEx, not interpolation"
            );
            let implied = 40_000_000_000 - p.value_dollars;
            assert_eq!(implied, expected_capex, "{year} CapEx mismatch");
        }
        assert!(!by_year[&2017].capex_imputed);
        assert_eq!(
            40_000_000_000 - by_year[&2017].value_dollars,
            20_650_000_000
        );
    }

    /// Public MD&A CapEx for AT&T 2020 was ~$15.675B (2020 Annual Report).
    /// XBRL ProductiveAssets 2020 is $14.69B — same order of magnitude (not OCF ~$40B).
    #[test]
    fn att_2020_capex_matches_public_mda_order_of_magnitude() {
        let ocf = vec![annual(2020, 43_130_000_000, "2021-02-01")];
        let mda_capex_2020: i64 = 15_675_000_000;
        let xbrl_productive_2020: i64 = 14_690_000_000;
        let capex = vec![annual(2020, -xbrl_productive_2020, "2021-02-01")];
        let detailed = fcf_history_detailed(&ocf, &capex);
        let fcf = detailed[0].value_dollars;
        let used_capex = ocf[0].value_dollars - fcf;
        assert!(!detailed[0].capex_imputed);
        let err = (used_capex - mda_capex_2020).abs() as f64 / mda_capex_2020 as f64;
        assert!(
            err < 0.15,
            "XBRL CapEx {used_capex} vs MD&A {mda_capex_2020} err={err:.2}"
        );
        assert!(fcf < 30_000_000_000, "FCF still OCF-like: {fcf}");
    }

    /// True residual hole (no tag for intermediate years) → interpolate as last resort only.
    #[test]
    fn true_taxonomy_hole_interpolates_as_last_resort() {
        let ocf: Vec<AnnualValue> = (2017..=2021)
            .map(|year| annual(year, 40_000_000_000, &format!("{}-02-01", year + 1)))
            .collect();
        // Artificial endpoints-only series (unlike real AT&T, where 2018–2020 exist).
        let capex = vec![
            annual(2017, -20_000_000_000, "2018-02-01"),
            annual(2021, -16_000_000_000, "2022-02-01"),
        ];
        let detailed = fcf_history_detailed(&ocf, &capex);
        let by_year: HashMap<i32, &FcfAnnual> = detailed.iter().map(|p| (p.year, p)).collect();
        for year in [2018, 2019, 2020] {
            assert!(
                by_year[&year].capex_imputed,
                "{year} should be last-resort imputed"
            );
            let cx = 40_000_000_000 - by_year[&year].value_dollars;
            assert!(cx > 15_000_000_000 && cx < 21_000_000_000);
        }
    }

    #[test]
    fn resolve_capex_carries_outside_span() {
        let series = vec![
            annual(2017, -20_000_000_000, "2018-02-01"),
            annual(2021, -16_000_000_000, "2022-02-01"),
        ];
        let sorted: Vec<&AnnualValue> = series.iter().collect();
        let before_span = resolve_capex_abs(2015, &sorted);
        assert_eq!(before_span.kind, CapexFillKind::Carried);
        assert_eq!(before_span.value_dollars, 20_000_000_000);
        let after_span = resolve_capex_abs(2023, &sorted);
        assert_eq!(after_span.kind, CapexFillKind::Carried);
        assert_eq!(after_span.value_dollars, 16_000_000_000);
    }

    #[test]
    fn ppe_productive_overlap_prefers_equal_max_not_sum() {
        // AT&T overlap audit: both tags same dollar amount — merge must not double-count.
        let ppe = vec![annual(2017, -20_650_000_000, "2018-02-01")];
        let prod = vec![annual(2017, -20_650_000_000, "2018-02-01")];
        let merged = merge_capex_by_year(&[ppe, prod]);
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].value_dollars.abs(), 20_650_000_000);
    }

    // ── Point-in-time provenance (W1) ─────────────────────────────────────────

    /// Companyfacts JSON for a single concept in a single unit.
    fn companyfacts(concept: &str, unit: &str, entries: serde_json::Value) -> serde_json::Value {
        serde_json::json!({
            "facts": { "us-gaap": { concept: { "units": { unit: entries } } } }
        })
    }

    /// One filed annual duration entry, spanning the 365 days that `end` closes.
    fn duration_entry(end: &str, filed: &str, accession: &str, val: i64) -> serde_json::Value {
        let close = chrono::NaiveDate::parse_from_str(end, "%Y-%m-%d").expect("test period end");
        let start = close - chrono::Duration::days(364);
        serde_json::json!({
            "form": "10-K",
            "start": start.format("%Y-%m-%d").to_string(),
            "end": end,
            "val": val,
            "filed": filed,
            "accn": accession,
        })
    }

    /// One filed annual instant entry, as balance-sheet concepts are filed.
    fn instant_entry(end: &str, filed: &str, accession: &str, val: i64) -> serde_json::Value {
        serde_json::json!({
            "form": "10-K",
            "end": end,
            "val": val,
            "filed": filed,
            "accn": accession,
        })
    }

    fn provenance_of(series: &[AnnualValue], year: i32) -> AnnualProvenance {
        series
            .iter()
            .find(|value| value.year == year)
            .unwrap_or_else(|| panic!("no observation for {year} in {series:?}"))
            .provenance
            .clone()
    }

    fn source_accessions(provenance: &AnnualProvenance) -> Vec<String> {
        provenance
            .sources
            .iter()
            .filter_map(|fact| fact.accession.clone())
            .collect()
    }

    fn source_qnames(provenance: &AnnualProvenance) -> Vec<String> {
        let mut qnames: Vec<String> = provenance
            .sources
            .iter()
            .map(|fact| fact.qname.clone())
            .collect();
        qnames.sort();
        qnames
    }

    fn years_and_values(series: &[AnnualValue]) -> Vec<(i32, i64)> {
        series
            .iter()
            .map(|value| (value.year, value.value_dollars))
            .collect()
    }

    /// T1.1 — the parser is the only gate between a filed string and a date the
    /// extractor will compare, so it refuses everything that is not one.
    #[test]
    fn iso_date_refuses_anything_that_is_not_a_calendar_day() {
        let admitted: Vec<&str> = [
            "",
            "2024",
            "2024-13-01",
            "2024-02-30",
            "2024-1-01",
            "24-01-01",
            "2024-01-01T00:00:00",
            "2024/01/01",
        ]
        .into_iter()
        .filter(|text| IsoDate::parse(text).is_some())
        .collect();

        assert!(
            admitted.is_empty(),
            "these are not calendar days and must not parse: {admitted:?}"
        );
    }

    /// W1-P01 — a leaf observation names the filing it came from.
    #[test]
    fn leaf_observation_is_knowable_from_its_filing_date_and_names_its_accession() {
        let facts = companyfacts(
            "NetCashProvidedByUsedInOperatingActivities",
            "USD",
            serde_json::json!([duration_entry(
                "2024-12-31",
                "2025-02-14",
                "0000320193-25-000008",
                100_000_000_000_i64
            )]),
        );

        let provenance = provenance_of(
            &extract_driver_annual(&facts, policy::OPERATING_CASH_FLOW),
            2024,
        );

        assert_eq!(
            (
                provenance.known_from.to_string(),
                source_accessions(&provenance)
            ),
            (
                "2025-02-14".to_owned(),
                vec!["0000320193-25-000008".to_owned()]
            )
        );
    }

    /// W1-P02 — a composition is knowable only once its last input was filed.
    #[test]
    fn free_cash_flow_is_knowable_from_the_later_of_its_two_inputs() {
        let operating = vec![annual(2024, 38_770_000_000, "2025-02-14")];
        let capex = vec![annual(2024, -20_260_000_000, "2025-05-01")];

        let provenance = provenance_of(&fcf_history(&operating, &capex), 2024);

        assert_eq!(
            (provenance.known_from.to_string(), provenance.mixed_vintage),
            ("2025-05-01".to_owned(), true)
        );
    }

    /// W1-P03 — a summed driver names every fact it summed.
    #[test]
    fn total_debt_names_both_of_the_components_it_summed() {
        let facts = serde_json::json!({
            "facts": { "us-gaap": {
                "LongTermDebtCurrent": { "units": { "USD": [
                    instant_entry("2024-12-31", "2025-02-14", "0000320193-25-000008", 10_000)
                ] } },
                "LongTermDebtNoncurrent": { "units": { "USD": [
                    instant_entry("2024-12-31", "2025-02-14", "0000320193-25-000008", 90_000)
                ] } }
            } }
        });

        let provenance = provenance_of(&extract_total_debt(&facts), 2024);

        assert_eq!(
            source_qnames(&provenance),
            vec![
                "LongTermDebtCurrent".to_owned(),
                "LongTermDebtNoncurrent".to_owned()
            ]
        );
    }

    /// W1-P04 — a development total keyed by period end takes its year from that
    /// end, not from a string slice of it.
    #[test]
    fn development_total_takes_its_fiscal_year_from_the_period_end() {
        let facts = companyfacts(
            "PaymentsToAcquirePropertyPlantAndEquipment",
            "USD",
            serde_json::json!([duration_entry(
                "2023-09-30",
                "2023-11-20",
                "0000000004-23-000001",
                1_500_000_000_i64
            )]),
        );

        let development = extract_capex(&facts);

        assert_eq!(
            development
                .iter()
                .map(|value| (value.year, value.provenance.end.to_string()))
                .collect::<Vec<_>>(),
            vec![(2023, "2023-09-30".to_owned())]
        );
    }

    /// W1-P05 — a rejected acquisition carries the ledger fact's identity.
    #[test]
    fn rejected_acquisition_observation_carries_the_ledger_facts_identity() {
        let facts = companyfacts(
            "PaymentsToAcquireOilAndGasProperty",
            "USD",
            serde_json::json!([duration_entry(
                "2024-12-31",
                "2025-02-20",
                "0001922446-25-000012",
                558_600_000_i64
            )]),
        );

        let provenance = provenance_of(
            &extract_acquisition_investments(&extract_normalized_investment_evidence(&facts)),
            2024,
        );

        assert_eq!(
            (source_qnames(&provenance), source_accessions(&provenance)),
            (
                vec!["PaymentsToAcquireOilAndGasProperty".to_owned()],
                vec!["0001922446-25-000012".to_owned()]
            )
        );
    }

    /// W1-P06 — a rate is recorded under the unit it was filed in, never USD.
    #[test]
    fn percent_fact_records_its_fiscal_year_and_the_filed_unit() {
        // The statutory rate is filed as a fraction under the `pure` unit.
        let facts = companyfacts(
            "EffectiveIncomeTaxRateReconciliationAtFederalStatutoryIncomeTaxRate",
            "pure",
            serde_json::json!([{
                "form": "10-K",
                "start": "2024-01-01",
                "end": "2024-12-31",
                "val": 0.21,
                "filed": "2025-02-14",
                "accn": "0000320193-25-000008",
            }]),
        );

        let rates = extract_reference_percent(&facts, policy::MARGINAL_TAX_REFERENCE);

        assert_eq!(
            rates
                .iter()
                .map(|value| (
                    value.year,
                    value.value_dollars,
                    value.provenance.sources[0].unit.clone()
                ))
                .collect::<Vec<_>>(),
            vec![(2024, 2_100, "pure".to_owned())]
        );
    }

    /// W1-N01 — an undated filing is not a filing.
    #[test]
    fn a_fact_without_a_filing_date_produces_no_annual_value() {
        let facts = companyfacts(
            "NetCashProvidedByUsedInOperatingActivities",
            "USD",
            serde_json::json!([{
                "form": "10-K",
                "start": "2024-01-01",
                "end": "2024-12-31",
                "val": 100_000_000_000_i64,
                "accn": "0000320193-25-000008",
            }]),
        );

        assert!(
            extract_driver_annual(&facts, policy::OPERATING_CASH_FLOW).is_empty(),
            "a fact with no filing date must not become an annual value"
        );
    }

    /// W1-N02 — the `fy` fallback is gone: an unparseable end is refused even
    /// when the filer's own fiscal-year label is right there.
    ///
    /// The driver is an instant one deliberately. A duration driver checks its
    /// period length by parsing `end` and would refuse this entry before the
    /// fail-close is reached, so the test would pass without pinning anything;
    /// an instant driver leaves the fail-close as the only gate.
    #[test]
    fn a_fact_whose_end_will_not_parse_produces_no_annual_value_despite_fy() {
        let facts = companyfacts(
            "LongTermDebtCurrent",
            "USD",
            serde_json::json!([{
                "form": "10-K",
                "end": "2024",
                "fy": 2024,
                "fp": "FY",
                "val": 10_000_000_000_i64,
                "filed": "2025-02-14",
                "accn": "0000320193-25-000008",
            }]),
        );

        assert!(
            extract_driver_annual(&facts, policy::CURRENT_DEBT).is_empty(),
            "an unparseable period end must not fall back to the filer's fy"
        );
    }

    /// The third fail-closed field: the accession decides the precedence
    /// tie-break, so a fact without one has no identity to break it with.
    #[test]
    fn a_fact_without_an_accession_produces_no_annual_value() {
        let facts = companyfacts(
            "NetCashProvidedByUsedInOperatingActivities",
            "USD",
            serde_json::json!([{
                "form": "10-K",
                "start": "2024-01-01",
                "end": "2024-12-31",
                "val": 100_000_000_000_i64,
                "filed": "2025-02-14",
            }]),
        );

        assert!(
            extract_driver_annual(&facts, policy::OPERATING_CASH_FLOW).is_empty(),
            "a fact with no accession must not become an annual value"
        );
    }

    /// W1-N03 — `as_of` is strictly before its cutoff. An inclusive bound would
    /// leak one day of hindsight at every cutoff a backtest takes.
    #[test]
    fn as_of_excludes_an_observation_filed_exactly_on_the_cutoff() {
        let facts = companyfacts(
            "NetCashProvidedByUsedInOperatingActivities",
            "USD",
            serde_json::json!([duration_entry(
                "2024-12-31",
                "2025-02-14",
                "0000320193-25-000008",
                100_000_000_000_i64
            )]),
        );
        let cutoff = IsoDate::parse("2025-02-14").expect("cutoff");

        let knowable = extract_driver_vintages(&facts, policy::OPERATING_CASH_FLOW).as_of(cutoff);

        assert!(
            knowable.is_empty(),
            "a filing made on the cutoff date was not yet knowable before it"
        );
    }

    /// W1-E01 — the point-in-time property itself: two cutoffs, two beliefs.
    /// `known_from` alone cannot express this, which is why vintages are kept.
    #[test]
    fn two_cutoffs_over_one_restated_year_return_two_different_values() {
        let facts = companyfacts(
            "NetCashProvidedByUsedInOperatingActivities",
            "USD",
            serde_json::json!([
                duration_entry("2023-12-31", "2024-02-01", "0000000005-24-000001", 100),
                duration_entry("2023-12-31", "2025-02-01", "0000000005-25-000001", 60),
            ]),
        );
        let vintages = extract_driver_vintages(&facts, policy::OPERATING_CASH_FLOW);
        let believed = |cutoff: &str| {
            years_and_values(&vintages.as_of(IsoDate::parse(cutoff).expect("cutoff")))
        };

        assert_eq!(
            (believed("2024-06-01"), believed("2025-06-01")),
            (vec![(2023, 100)], vec![(2023, 60)])
        );
    }

    /// W1-E02 — an imputed value is knowable no earlier than its last input.
    #[test]
    fn an_interpolated_capex_year_is_knowable_only_from_its_later_neighbour() {
        let operating = vec![annual(2023, 500, "2024-03-01")];
        let capex = vec![
            annual(2022, -100, "2023-03-01"),
            annual(2024, -200, "2025-03-01"),
        ];

        let provenance = provenance_of(&fcf_history(&operating, &capex), 2023);

        assert_eq!(provenance.known_from.to_string(), "2025-03-01");
    }

    /// W1-E03 — absence stays absence. A driver with no facts has no value, and
    /// certainly not a zero one.
    #[test]
    fn an_issuer_with_no_facts_for_a_driver_yields_an_empty_series() {
        let facts = serde_json::json!({ "facts": { "us-gaap": {} } });

        assert!(
            extract_driver_annual(&facts, policy::OPERATING_CASH_FLOW).is_empty(),
            "a driver with no filed facts must produce no observation at all"
        );
    }

    /// W1-E04 — the fiscal year is the calendar year the period closes in, so a
    /// February close belongs to the year it ends in and not to the year it
    /// mostly covers.
    #[test]
    fn a_february_fiscal_close_belongs_to_the_calendar_year_of_its_end() {
        let facts = companyfacts(
            "NetCashProvidedByUsedInOperatingActivities",
            "USD",
            serde_json::json!([{
                "form": "10-K",
                "start": "2024-02-04",
                "end": "2025-02-01",
                "val": 100,
                "filed": "2025-03-20",
                "accn": "0000000006-25-000001",
            }]),
        );

        let series = extract_driver_annual(&facts, policy::OPERATING_CASH_FLOW);

        assert_eq!(
            series
                .iter()
                .map(|value| (value.year, value.provenance.end.to_string()))
                .collect::<Vec<_>>(),
            vec![(2025, "2025-02-01".to_owned())]
        );
    }

    /// W1-E05 — a fiscal-year-end change files two annual periods closing in one
    /// calendar year. The later close wins and the earlier one is dropped: the
    /// status quo, pinned here and named as a limitation in the doc.
    #[test]
    fn a_fiscal_year_end_change_keeps_the_later_period_end() {
        let facts = companyfacts(
            "NetCashProvidedByUsedInOperatingActivities",
            "USD",
            serde_json::json!([
                duration_entry("2024-06-30", "2024-08-20", "0000000007-24-000001", 400),
                duration_entry("2024-12-31", "2025-02-20", "0000000007-25-000001", 900),
            ]),
        );

        let series = extract_driver_annual(&facts, policy::OPERATING_CASH_FLOW);

        assert_eq!(years_and_values(&series), vec![(2024, 900)]);
    }

    /// W1-B01 — the cutoff boundary from both sides at once.
    #[test]
    fn as_of_admits_only_the_filing_strictly_before_the_cutoff() {
        let facts = companyfacts(
            "NetCashProvidedByUsedInOperatingActivities",
            "USD",
            serde_json::json!([
                duration_entry("2022-12-31", "2025-05-31", "0000000008-25-000001", 10),
                duration_entry("2023-12-31", "2025-06-01", "0000000008-25-000002", 20),
                duration_entry("2024-12-31", "2025-06-02", "0000000008-25-000003", 30),
            ]),
        );
        let cutoff = IsoDate::parse("2025-06-01").expect("cutoff");

        let knowable = extract_driver_vintages(&facts, policy::OPERATING_CASH_FLOW).as_of(cutoff);

        assert_eq!(years_and_values(&knowable), vec![(2022, 10)]);
    }
}
