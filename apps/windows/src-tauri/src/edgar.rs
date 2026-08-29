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
use std::time::Duration;

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

/// A single annual (10-K) cash flow value from EDGAR XBRL.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnnualValue {
    pub year: i32,
    pub value_dollars: i64,
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
            (crate::sec_driver_normalization_policy_generated::MINIMUM_DURATION_DAYS
                ..=crate::sec_driver_normalization_policy_generated::MAXIMUM_DURATION_DAYS)
                .contains(&(end - start).num_days())
        }
    }
}

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

fn extract_annual_with_shape(
    facts: &serde_json::Value,
    concept: &str,
    shape: FactPeriodShape,
    unit: &str,
) -> Vec<AnnualValue> {
    annual_candidates_with_shape(facts, concept, shape, unit)
        .into_iter()
        .map(|(value, _)| value)
        .collect()
}

/// Same extraction as `extract_annual_with_shape`, but each year also reports
/// whether a later filing materially restated it. A discontinued operation
/// restates revenue to continuing operations while ASC 205-20 leaves the
/// cash-flow statement on the total-company basis, so the pair of flags is the
/// only evidence that a year's margin mixes two different entities.
fn annual_candidates_with_shape(
    facts: &serde_json::Value,
    concept: &str,
    shape: FactPeriodShape,
    unit: &str,
) -> Vec<(AnnualValue, bool)> {
    let units = facts.pointer(&format!("/facts/us-gaap/{concept}/units/{unit}"));
    let arr = match units.and_then(|v| v.as_array()) {
        Some(a) => a,
        None => return vec![],
    };

    #[derive(Clone)]
    struct AnnualCandidate {
        end: String,
        fiscal_year: i32,
        value_dollars: i64,
        filed: String,
        consolidated: bool,
    }

    // Keep one consolidated annual fact per end-date. Exact end dates can have
    // a re-filed fact and a segment fact; consolidated wins over segment, then
    // the most recently filed value wins.
    let mut by_end: HashMap<String, AnnualCandidate> = HashMap::new();
    // Every consolidated value seen for an end date, so the winner can be
    // compared against the ones it superseded. Segment facts are excluded:
    // a segment differing from the consolidated total is decomposition, not
    // a restatement.
    let mut consolidated_values: HashMap<String, Vec<i64>> = HashMap::new();
    for entry in arr {
        let form = entry["form"].as_str().unwrap_or("");
        if !crate::sec_driver_normalization_policy_generated::ACCEPTED_FORMS.contains(&form) {
            continue;
        }
        if !has_approved_period_shape(entry, shape) {
            continue;
        }
        // A 10-K may carry CY2019Q4/CY2020Q1 facts. They are not annual
        // observations and must not be mixed into a fiscal-year series.
        if entry["frame"]
            .as_str()
            .is_some_and(|frame| frame.contains('Q'))
        {
            continue;
        }
        let end = entry["end"].as_str().unwrap_or("").to_string();
        if end.is_empty() {
            continue;
        }
        let val = match entry["val"].as_i64() {
            Some(v) => v,
            None => continue,
        };
        let filed = entry["filed"].as_str().unwrap_or("").to_string();
        // The SEC `fy` field belongs to the filing, not necessarily to the
        // fact's period.  A current 10-K commonly carries comparative annual
        // facts for prior ends with the current filing's `fy` (e.g. NVDA's
        // 2025 and 2026 revenue facts both carry fy=2026).  Keying by `fy`
        // therefore discards valid comparative years and creates a broken
        // driver history.  The fiscal end year is the stable annual key; use
        // `fy` only as a malformed-end fallback.
        let end_year = end.get(..4).and_then(|year| year.parse::<i32>().ok());
        let fiscal_year = end_year.or_else(|| {
            entry["fy"]
                .as_i64()
                .and_then(|year| i32::try_from(year).ok())
        });
        let Some(fiscal_year) = fiscal_year else {
            continue;
        };
        let consolidated = entry
            .get("segment")
            .map_or(true, serde_json::Value::is_null);
        if consolidated {
            consolidated_values
                .entry(end.clone())
                .or_default()
                .push(val);
        }
        let candidate = AnnualCandidate {
            end: end.clone(),
            fiscal_year,
            value_dollars: val,
            filed,
            consolidated,
        };
        let replace = by_end.get(&end).is_none_or(|existing| {
            (candidate.consolidated && !existing.consolidated)
                || (candidate.consolidated == existing.consolidated
                    && candidate.filed > existing.filed)
        });
        if replace {
            by_end.insert(end, candidate);
        }
    }

    // A restated comparative fact can carry the same `fy` as the current fact
    // on a later fiscal end. Keep the latest fiscal end for that year so the
    // aligned series contains one observation per fiscal year.
    let mut by_fiscal_year: HashMap<i32, AnnualCandidate> = HashMap::new();
    for candidate in by_end.into_values() {
        let replace = by_fiscal_year
            .get(&candidate.fiscal_year)
            .is_none_or(|existing| candidate.end > existing.end);
        if replace {
            by_fiscal_year.insert(candidate.fiscal_year, candidate);
        }
    }
    let mut result: Vec<(AnnualValue, bool)> = by_fiscal_year
        .into_iter()
        .map(|(year, candidate)| {
            let restated = consolidated_values
                .get(&candidate.end)
                .is_some_and(|values| {
                    values
                        .iter()
                        .any(|value| materially_restated(*value, candidate.value_dollars))
                });
            (
                AnnualValue {
                    year,
                    value_dollars: candidate.value_dollars,
                },
                restated,
            )
        })
        .collect();
    result.sort_by_key(|(value, _)| value.year);
    result
}

/// Fiscal years for which a later filing materially restated this driver.
fn restated_years(
    facts: &serde_json::Value,
    driver: crate::sec_driver_normalization_policy_generated::DriverOperator,
) -> HashSet<i32> {
    let shape = match driver.period_shape {
        "duration" => FactPeriodShape::Duration,
        "instant" => FactPeriodShape::Instant,
        other => panic!("unsupported SEC driver shape: {other}"),
    };
    driver
        .qnames
        .iter()
        .flat_map(|concept| annual_candidates_with_shape(facts, concept, shape, driver.unit))
        .filter(|(_, restated)| *restated)
        .map(|(value, _)| value.year)
        .collect()
}

fn extract_annual(facts: &serde_json::Value, concept: &str) -> Vec<AnnualValue> {
    extract_annual_with_shape(facts, concept, FactPeriodShape::Any, "USD")
}

/// Extract the first usable annual USD series from a list of issuer-specific
/// XBRL concepts. SEC filers commonly rotate between equivalent revenue,
/// interest, and tax tags, so the provider layer resolves that taxonomy before
/// the valuation engine sees the aligned driver rows.
fn extract_annual_any(facts: &serde_json::Value, concepts: &[&str]) -> Vec<AnnualValue> {
    extract_annual_any_with_shape(facts, concepts, FactPeriodShape::Any, "USD")
}

fn extract_annual_any_with_shape(
    facts: &serde_json::Value,
    concepts: &[&str],
    shape: FactPeriodShape,
    unit: &str,
) -> Vec<AnnualValue> {
    // Equivalent XBRL concepts are often rotated during taxonomy migrations:
    // one tag may contain the older history while another contains recent
    // filings.  Selecting the longest series silently drops the newer tag and
    // can pair OCF with stale revenue.  Merge by fiscal year in declared
    // precedence order instead; overlaps use the canonical first concept and
    // gaps are filled from the alternatives.
    let mut by_year = HashMap::<i32, i64>::new();
    for concept in concepts {
        for value in extract_annual_with_shape(facts, concept, shape, unit) {
            by_year.entry(value.year).or_insert(value.value_dollars);
        }
    }
    let mut result: Vec<AnnualValue> = by_year
        .into_iter()
        .map(|(year, value_dollars)| AnnualValue {
            year,
            value_dollars,
        })
        .collect();
    result.sort_by_key(|value| value.year);
    result
}

pub fn extract_driver_annual(
    facts: &serde_json::Value,
    driver: crate::sec_driver_normalization_policy_generated::DriverOperator,
) -> Vec<AnnualValue> {
    assert!(
        matches!(driver.unit, "USD" | "shares"),
        "unsupported SEC driver unit: {}",
        driver.unit
    );
    let shape = match driver.period_shape {
        "duration" => FactPeriodShape::Duration,
        "instant" => FactPeriodShape::Instant,
        other => panic!("unsupported SEC driver shape: {other}"),
    };
    assert!(
        matches!(
            driver.operation,
            "select_one_equivalent" | "sum_disjoint_components" | "derive_effective_tax"
        ),
        "unsupported SEC driver operation: {}",
        driver.operation
    );
    extract_annual_any_with_shape(facts, driver.qnames, shape, driver.unit)
}

fn extract_total_debt(facts: &serde_json::Value) -> Vec<AnnualValue> {
    let current = extract_driver_annual(
        facts,
        crate::sec_driver_normalization_policy_generated::CURRENT_DEBT,
    );
    let noncurrent = extract_driver_annual(
        facts,
        crate::sec_driver_normalization_policy_generated::NON_CURRENT_DEBT,
    );
    let reported_total = extract_driver_annual(
        facts,
        crate::sec_driver_normalization_policy_generated::TOTAL_DEBT,
    );
    let mut by_year = HashMap::<i32, i64>::new();
    for value in current.into_iter().chain(noncurrent) {
        *by_year.entry(value.year).or_default() += value.value_dollars.abs();
    }
    for value in reported_total {
        by_year.insert(value.year, value.value_dollars.abs());
    }
    let mut result = by_year
        .into_iter()
        .map(|(year, value_dollars)| AnnualValue {
            year,
            value_dollars,
        })
        .collect::<Vec<_>>();
    result.sort_by_key(|value| value.year);
    result
}

/// Extract a filing's statutory federal rate from the tax reconciliation.  SEC
/// facts commonly encode this as `pure` or `percent`, not USD.
fn extract_annual_percent_any(facts: &serde_json::Value, concepts: &[&str]) -> Vec<AnnualValue> {
    let mut by_year = HashMap::<i32, i64>::new();
    for concept in concepts {
        let Some(units) = facts.pointer(&format!("/facts/us-gaap/{concept}/units")) else {
            continue;
        };
        let Some(obj) = units.as_object() else {
            continue;
        };
        let Some(arr) = obj.values().find_map(|value| value.as_array()) else {
            continue;
        };
        for entry in arr {
            if !crate::sec_driver_normalization_policy_generated::ACCEPTED_FORMS
                .contains(&entry["form"].as_str().unwrap_or_default())
                || !has_approved_period_shape(entry, FactPeriodShape::Duration)
                || entry["frame"]
                    .as_str()
                    .is_some_and(|frame| frame.contains('Q'))
            {
                continue;
            }
            let Some(end) = entry["end"].as_str() else {
                continue;
            };
            let Some(year) = end.get(..4).and_then(|value| value.parse::<i32>().ok()) else {
                continue;
            };
            let Some(value) = entry["val"].as_f64() else {
                continue;
            };
            let bps = if value.abs() <= 1.0 {
                value * 10_000.0
            } else if value.abs() <= 100.0 {
                value * 100.0
            } else {
                value
            };
            if (0.0..=5_000.0).contains(&bps) {
                by_year.entry(year).or_insert(bps.round() as i64);
            }
        }
    }
    let mut result = by_year
        .into_iter()
        .map(|(year, value_dollars)| AnnualValue {
            year,
            value_dollars,
        })
        .collect::<Vec<_>>();
    result.sort_by_key(|value| value.year);
    result
}

fn extract_reference_percent(
    facts: &serde_json::Value,
    driver: crate::sec_driver_normalization_policy_generated::DriverOperator,
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
    let mut by_year: HashMap<i32, i64> = HashMap::new();
    for s in series {
        for v in s {
            let abs = v.value_dollars.abs();
            let entry = by_year.entry(v.year).or_insert(0);
            if abs > entry.abs() {
                *entry = v.value_dollars;
            }
        }
    }
    let mut result: Vec<AnnualValue> = by_year
        .into_iter()
        .map(|(year, value_dollars)| AnnualValue {
            year,
            value_dollars,
        })
        .collect();
    result.sort_by_key(|v| v.year);
    result
}

fn extract_capex(facts: &serde_json::Value) -> Vec<AnnualValue> {
    extract_recurring_development(&extract_normalized_investment_evidence(facts))
}

/// Recurring development CapEx per fiscal year: tangible plus capitalized
/// software. Reading only the tangible component reports an issuer that invests
/// through software as barely reinvesting at all.
fn extract_recurring_development(
    evidence: &crate::sec_normalization::NormalizedInvestmentEvidence,
) -> Vec<AnnualValue> {
    evidence
        .development_total_by_end
        .iter()
        .filter_map(|(end, value_dollars)| {
            end.get(..4)
                .and_then(|year| year.parse::<i32>().ok())
                .map(|year| AnnualValue {
                    year,
                    value_dollars: *value_dollars,
                })
        })
        .collect()
}

fn extract_acquisition_investments(
    evidence: &crate::sec_normalization::NormalizedInvestmentEvidence,
) -> Vec<AnnualValue> {
    let mut by_year = HashMap::<i32, i64>::new();
    for entry in &evidence.ledger {
        if entry.state != crate::sec_normalization::EvidenceState::RejectedAcquisition {
            continue;
        }
        let Some(year) = entry
            .fact
            .end
            .get(..4)
            .and_then(|value| value.parse::<i32>().ok())
        else {
            continue;
        };
        // Investment concepts may be overlapping disclosure alternatives. For
        // the growth-evidence flag we retain the largest disclosed cash amount,
        // never sum them into a synthetic acquisition total.
        let value = entry.fact.value_dollars.abs();
        by_year
            .entry(year)
            .and_modify(|existing| *existing = (*existing).max(value))
            .or_insert(value);
    }
    let mut result = by_year
        .into_iter()
        .map(|(year, value_dollars)| AnnualValue {
            year,
            value_dollars,
        })
        .collect::<Vec<_>>();
    result.sort_by_key(|value| value.year);
    result
}

/// Materialize raw SEC cash-investment facts at the provider boundary, then
/// immediately hand them to the typed normalizer.  No EDGAR caller may decide
/// that a QName is recurring CapEx by itself.
fn extract_normalized_investment_evidence(
    facts: &serde_json::Value,
) -> crate::sec_normalization::NormalizedInvestmentEvidence {
    use crate::sec_driver_normalization_policy_generated as policy;
    use crate::sec_normalization::SecFact;

    let concepts = policy::DEVELOPMENT
        .iter()
        .chain(policy::DEVELOPMENT_SOFTWARE)
        .chain(policy::PROPERTY_ACQUISITION)
        .chain(policy::BUSINESS_ACQUISITION);
    let mut raw_facts = Vec::new();
    for concept in concepts {
        let Some(entries) = facts
            .pointer(&format!("/facts/us-gaap/{concept}/units/USD"))
            .and_then(serde_json::Value::as_array)
        else {
            continue;
        };
        for entry in entries {
            let Some(end) = entry["end"].as_str().filter(|end| !end.is_empty()) else {
                continue;
            };
            let Some(value_dollars) = entry["val"].as_i64() else {
                continue;
            };
            raw_facts.push(SecFact {
                qname: (*concept).to_owned(),
                taxonomy: "us-gaap".into(),
                value_dollars,
                start: entry["start"].as_str().map(str::to_owned),
                end: end.to_owned(),
                unit: "USD".into(),
                form: entry["form"].as_str().unwrap_or_default().to_owned(),
                accession: entry["accn"].as_str().map(str::to_owned),
                filed: entry["filed"].as_str().map(str::to_owned),
                consolidated: entry
                    .get("segment")
                    .map_or(true, serde_json::Value::is_null),
            });
        }
    }
    crate::sec_normalization::normalize_investments(raw_facts)
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

/// Resolve CapEx (absolute dollars) for an OCF fiscal year against the **merged**
/// multi-concept series (reported tags first; impute only residual holes).
fn resolve_capex_abs(year: i32, capex_sorted: &[(i32, i64)]) -> (i64, CapexFillKind) {
    if capex_sorted.is_empty() {
        return (0, CapexFillKind::Missing);
    }
    if let Some((_, v)) = capex_sorted.iter().find(|(y, _)| *y == year) {
        return (v.abs(), CapexFillKind::Reported);
    }
    let before = capex_sorted
        .iter()
        .copied()
        .filter(|(y, _)| *y < year)
        .max_by_key(|(y, _)| *y);
    let after = capex_sorted
        .iter()
        .copied()
        .filter(|(y, _)| *y > year)
        .min_by_key(|(y, _)| *y);
    match (before, after) {
        (Some((y0, v0)), Some((y1, v1))) if y1 > y0 => {
            let span = (y1 - y0) as f64;
            let t = (year - y0) as f64 / span;
            let a0 = v0.abs() as f64;
            let a1 = v1.abs() as f64;
            let interp = a0 + (a1 - a0) * t;
            (interp.round() as i64, CapexFillKind::Interpolated)
        }
        (Some((_, v0)), None) => (v0.abs(), CapexFillKind::Carried),
        (None, Some((_, v1))) => (v1.abs(), CapexFillKind::Carried),
        _ => (0, CapexFillKind::Missing),
    }
}

/// Annual FCF plus whether CapEx was imputed for that year (for diagnostics).
#[derive(Debug, Clone)]
struct FcfAnnual {
    year: i32,
    value_dollars: i64,
    capex_imputed: bool,
}

/// Compute FCF history = OCF - CapEx, aligned by year.
/// Gap years between reported CapEx points are interpolated (not zeroed).
fn fcf_history(ocf: &[AnnualValue], capex: &[AnnualValue]) -> Vec<AnnualValue> {
    fcf_history_detailed(ocf, capex)
        .into_iter()
        .map(|p| AnnualValue {
            year: p.year,
            value_dollars: p.value_dollars,
        })
        .collect()
}

fn fcf_history_detailed(ocf: &[AnnualValue], capex: &[AnnualValue]) -> Vec<FcfAnnual> {
    let mut capex_sorted: Vec<(i32, i64)> =
        capex.iter().map(|c| (c.year, c.value_dollars)).collect();
    capex_sorted.sort_by_key(|(y, _)| *y);
    ocf.iter()
        .map(|o| {
            let (cx, kind) = resolve_capex_abs(o.year, &capex_sorted);
            FcfAnnual {
                year: o.year,
                value_dollars: o.value_dollars - cx,
                capex_imputed: matches!(kind, CapexFillKind::Interpolated | CapexFillKind::Carried),
            }
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

fn compute_dcf(fcf: &[AnnualValue], shares_outstanding: u64) -> Option<DcfResult> {
    if fcf.len() < DCF_MIN_YEARS_HISTORY || shares_outstanding == 0 {
        return None;
    }

    // Use last 4 years (or whatever is available)
    let window = fcf.iter().rev().take(4).collect::<Vec<_>>();
    let window: Vec<_> = window.into_iter().rev().collect();

    let base_fcf = window.last()?.value_dollars;
    // Skip if base FCF is heavily negative (> -$5B): unreliable projection base
    if base_fcf < -5_000_000_000 {
        return None;
    }

    // CAGR from first to last in the window
    let first_fcf = window.first()?.value_dollars;
    let years_span = (window.last()?.year - window.first()?.year) as f64;
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
    let response = client
        .get(&url)
        .header("Accept", "application/json")
        .header("Accept-Encoding", "identity")
        .send()
        .map_err(|e| format!("FetchFailed: EDGAR {}: {}", symbol, e))?;
    let slim = crate::sec_company_facts_sieve::sieve(response);
    serde_json::from_str(&slim).map_err(|e| format!("FetchFailed: EDGAR parse {}: {}", symbol, e))
}

pub fn fetch_fcf_history(
    client: &Client,
    symbol: &str,
    cik: u64,
) -> Result<Option<Vec<crate::dcf_model::FcfPoint>>, String> {
    let body = fetch_company_facts(client, symbol, cik)?;

    let ocf = extract_driver_annual(
        &body,
        crate::sec_driver_normalization_policy_generated::OPERATING_CASH_FLOW,
    );
    let investment_evidence = extract_normalized_investment_evidence(&body);
    let capex = extract_recurring_development(&investment_evidence);
    let acquisition_investments = extract_acquisition_investments(&investment_evidence);
    let diluted_shares = extract_driver_annual(
        &body,
        crate::sec_driver_normalization_policy_generated::DILUTED_AVERAGE_SHARES,
    );
    let revenue = extract_driver_annual(
        &body,
        crate::sec_driver_normalization_policy_generated::REVENUE,
    );
    let interest = extract_driver_annual(
        &body,
        crate::sec_driver_normalization_policy_generated::INTEREST_EXPENSE,
    );
    let pretax = extract_driver_annual(
        &body,
        crate::sec_driver_normalization_policy_generated::PRETAX_INCOME,
    );
    let tax = extract_driver_annual(
        &body,
        crate::sec_driver_normalization_policy_generated::TAX_EXPENSE,
    );
    let equity = extract_driver_annual(
        &body,
        crate::sec_driver_normalization_policy_generated::STOCKHOLDERS_EQUITY,
    );
    let debt = extract_total_debt(&body);
    let marginal_tax = extract_reference_percent(
        &body,
        crate::sec_driver_normalization_policy_generated::MARGINAL_TAX_REFERENCE,
    );

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
    let restated_revenue_years = restated_years(
        &body,
        crate::sec_driver_normalization_policy_generated::REVENUE,
    );
    let restated_ocf_years = restated_years(
        &body,
        crate::sec_driver_normalization_policy_generated::OPERATING_CASH_FLOW,
    );

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
                let mut point = crate::dcf_model::FcfPoint::new(v.year, v.value_dollars as f64);
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
                            point = point.with_marginal_tax_source(
                                crate::dcf_model::WaccFieldSource::TaxReconciliation,
                            );
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
    let response = client
        .get(url)
        .header("Accept", "application/json")
        .header("Accept-Encoding", "identity")
        .send()
        .map_err(|e| format!("EDGAR shares {}: {}", symbol, e))?;
    let slim = crate::sec_company_facts_sieve::sieve(response);
    let body: serde_json::Value =
        serde_json::from_str(&slim).map_err(|e| format!("EDGAR shares parse {}: {}", symbol, e))?;
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
    let annual: Vec<AnnualValue> = points
        .iter()
        .map(|p| AnnualValue {
            year: p.year,
            value_dollars: p.value_dollars as i64,
        })
        .collect();
    Ok(compute_dcf(&annual, shares_outstanding))
}

#[cfg(test)]
mod sieve_parity_tests {
    use super::*;
    use crate::sec_driver_normalization_policy_generated as policy;

    /// The sieve is allowed to drop bytes. It is not allowed to change a number.
    ///
    /// Every cut it makes is a cut a reader makes for itself after the parse, so the readers must
    /// reach the same values whether they walk the source or the sieved copy. The fixture is a
    /// real JPM companyfacts slice, with its frames, accession numbers and labels in place.
    fn jpm() -> serde_json::Value {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../android/core/src/test/resources/sec-companyfacts/JPM.json");
        let raw = std::fs::read_to_string(path).expect("JPM companyfacts fixture");
        serde_json::from_str(&raw).expect("json")
    }

    fn sieved_jpm() -> serde_json::Value {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../android/core/src/test/resources/sec-companyfacts/JPM.json");
        let raw = std::fs::read_to_string(path).expect("JPM companyfacts fixture");
        let slim = crate::sec_company_facts_sieve::sieve(raw.as_bytes());
        serde_json::from_str(&slim).expect("sieved json")
    }

    #[test]
    fn the_operating_cash_flow_series_survives_the_sieve() {
        assert_eq!(
            extract_driver_annual(&jpm(), policy::OPERATING_CASH_FLOW),
            extract_driver_annual(&sieved_jpm(), policy::OPERATING_CASH_FLOW)
        );
    }

    #[test]
    fn the_equity_series_survives_the_sieve() {
        assert_eq!(
            extract_driver_annual(&jpm(), policy::STOCKHOLDERS_EQUITY),
            extract_driver_annual(&sieved_jpm(), policy::STOCKHOLDERS_EQUITY)
        );
    }

    #[test]
    fn the_interest_series_survives_the_sieve() {
        assert_eq!(
            extract_driver_annual(&jpm(), policy::INTEREST_EXPENSE),
            extract_driver_annual(&sieved_jpm(), policy::INTEREST_EXPENSE)
        );
    }

    #[test]
    fn the_investment_evidence_survives_the_sieve() {
        assert_eq!(
            extract_normalized_investment_evidence(&jpm()).development_total_by_end,
            extract_normalized_investment_evidence(&sieved_jpm()).development_total_by_end
        );
    }

    #[test]
    fn the_shares_count_survives_the_sieve() {
        let raw = "{\"facts\":{\"dei\":{\"EntityCommonStockSharesOutstanding\":{\"units\":{\"shares\":[{\"form\":\"10-Q\",\"end\":\"2025-06-30\",\"val\":2770000000,\"filed\":\"2025-07-30\"}]}}}}}";
        let slim: serde_json::Value =
            serde_json::from_str(&crate::sec_company_facts_sieve::sieve(raw.as_bytes()))
                .expect("json");
        assert_eq!(Some(2_770_000_000), extract_current_shares(&slim));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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

        let restated_revenue = restated_years(
            &facts,
            crate::sec_driver_normalization_policy_generated::REVENUE,
        );
        let restated_ocf = restated_years(
            &facts,
            crate::sec_driver_normalization_policy_generated::OPERATING_CASH_FLOW,
        );

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
        let series = extract_annual_with_shape(
            &separation_facts(),
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            FactPeriodShape::Duration,
            "USD",
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
        use crate::sec_driver_normalization_policy_generated as policy;
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
                (
                    "REV",
                    crate::sec_driver_normalization_policy_generated::REVENUE.qnames,
                ),
                (
                    "OCF",
                    crate::sec_driver_normalization_policy_generated::OPERATING_CASH_FLOW.qnames,
                ),
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
                        if !crate::sec_driver_normalization_policy_generated::ACCEPTED_FORMS
                            .contains(&form)
                        {
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
                let recognized = crate::sec_driver_normalization_policy_generated::DEVELOPMENT
                    .contains(&concept.as_str());
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
                    if !crate::sec_driver_normalization_policy_generated::ACCEPTED_FORMS
                        .contains(&form)
                    {
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
        let ppe = vec![AnnualValue {
            year: 2017,
            value_dollars: -20_000_000_000,
        }];
        let productive = vec![
            AnnualValue {
                year: 2017,
                value_dollars: -1_000_000_000,
            },
            AnnualValue {
                year: 2024,
                value_dollars: -20_260_000_000,
            },
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
                    "filed": "2025-02-20"
                }] } },
                "PaymentsToAcquireOilAndGasProperty": { "units": { "USD": [{
                    "form": "10-K", "start": "2024-01-01", "end": "2024-12-31", "val": 558600000,
                    "filed": "2025-02-20"
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
                && entry.category
                    == crate::sec_normalization::InvestmentCategory::PropertyAcquisition
                && entry.state == crate::sec_normalization::EvidenceState::RejectedAcquisition
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
                {"form":"10-K", "start":"2024-01-01", "end":"2024-12-31", "val":100, "filed":"2025-02-01"},
                {"form":"10-K/A", "start":"2024-01-01", "end":"2024-12-31", "val":120, "filed":"2025-03-01"}
            ] } } } }
        });
        let annual = extract_annual(&facts, "SyntheticCapex");
        assert_eq!(annual.len(), 1);
        assert_eq!(annual[0].value_dollars, 120);
    }

    #[test]
    fn annual_extraction_prefers_consolidated_annual_over_segment_and_quarter() {
        let facts = serde_json::json!({
            "facts": {"us-gaap": {"SyntheticRevenue": {"units": {"USD": [
                {"form":"10-K", "end":"2024-12-31", "fy":2024, "fp":"FY", "val":100, "filed":"2025-02-01"},
                {"form":"10-K", "end":"2024-12-31", "fy":2024, "fp":"FY", "val":20, "filed":"2026-02-01", "segment":{"dimension":"region"}},
                {"form":"10-K", "end":"2024-12-31", "fy":2024, "fp":"FY", "val":30, "filed":"2026-02-01", "frame":"CY2024Q4"},
                {"form":"10-K", "end":"2025-12-31", "fy":2025, "fp":"FY", "val":110, "filed":"2026-02-01"},
                {"form":"10-Q", "end":"2025-03-31", "fy":2025, "fp":"Q1", "val":25, "filed":"2025-05-01"}
            ]}}}}
        });

        let annual = extract_annual(&facts, "SyntheticRevenue");
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
                    {"form":"10-K", "end":"2025-01-26", "fy":2026, "fp":"FY", "frame":"CY2024", "val":130497, "filed":"2026-02-25"},
                    {"form":"10-K", "end":"2026-01-25", "fy":2026, "fp":"FY", "frame":"CY2025", "val":215938, "filed":"2026-02-25"}
                ]}}}}
            }
        );

        let annual = extract_annual(&facts, "SyntheticRevenue");
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
                        {"form":"10-K", "end":"2024-12-31", "fy":2024, "fp":"FY", "val":400, "filed":"2025-02-01"},
                        {"form":"10-K", "end":"2025-12-31", "fy":2025, "fp":"FY", "val":500, "filed":"2026-02-01"}
                    ]}},
                    "SyntheticRevenueLegacy": {"units": {"USD": [
                        {"form":"10-K", "end":"2023-12-31", "fy":2023, "fp":"FY", "val":300, "filed":"2024-02-01"},
                        {"form":"10-K", "end":"2024-12-31", "fy":2024, "fp":"FY", "val":999, "filed":"2025-02-01"}
                    ]}}
                }}
            }
        );

        let annual = extract_annual_any(
            &facts,
            &["SyntheticRevenuePrimary", "SyntheticRevenueLegacy"],
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
            AnnualValue {
                year: 2023,
                value_dollars: 38_310_000_000,
            },
            AnnualValue {
                year: 2024,
                value_dollars: 38_770_000_000,
            },
        ];
        let capex = vec![
            AnnualValue {
                year: 2023,
                value_dollars: -17_850_000_000,
            },
            AnnualValue {
                year: 2024,
                value_dollars: -20_260_000_000,
            },
        ];
        let fcf = fcf_history(&ocf, &capex);
        assert_eq!(fcf[0].value_dollars, 38_310_000_000 - 17_850_000_000);
        assert_eq!(fcf[1].value_dollars, 38_770_000_000 - 20_260_000_000);
        // Regression: missing CapEx must not leave FCF == OCF for capex-heavy issuers.
        assert!(fcf[1].value_dollars < ocf[1].value_dollars / 2 + 5_000_000_000);
    }

    #[test]
    fn missing_capex_year_zeros_not_panics() {
        let ocf = vec![AnnualValue {
            year: 2024,
            value_dollars: 40_000_000_000,
        }];
        let fcf = fcf_history(&ocf, &[]);
        assert_eq!(fcf[0].value_dollars, 40_000_000_000);
    }

    /// AT&T companyfacts: PPE stops 2017, but ProductiveAssets **files** 2018–2020.
    /// Merge must use those reported tags — not invent a false gap and interpolate.
    /// Values from SEC companyfacts CIK 0000732717 (10-K USD).
    #[test]
    fn att_2018_2020_uses_reported_productive_assets_not_interpolation() {
        let ocf: Vec<AnnualValue> = (2017..=2021)
            .map(|year| AnnualValue {
                year,
                value_dollars: 40_000_000_000,
            })
            .collect();
        let ppe = vec![AnnualValue {
            year: 2017,
            value_dollars: -20_650_000_000,
        }];
        let productive = vec![
            AnnualValue {
                year: 2017,
                value_dollars: -20_650_000_000,
            }, // overlap: PPE ≡ ProductiveAssets (ratio 1.0)
            AnnualValue {
                year: 2018,
                value_dollars: -21_251_000_000,
            },
            AnnualValue {
                year: 2019,
                value_dollars: -19_635_000_000,
            },
            AnnualValue {
                year: 2020,
                value_dollars: -14_690_000_000,
            },
            AnnualValue {
                year: 2021,
                value_dollars: -15_545_000_000,
            },
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
        let ocf = vec![AnnualValue {
            year: 2020,
            value_dollars: 43_130_000_000,
        }];
        let mda_capex_2020: i64 = 15_675_000_000;
        let xbrl_productive_2020: i64 = 14_690_000_000;
        let capex = vec![AnnualValue {
            year: 2020,
            value_dollars: -xbrl_productive_2020,
        }];
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
            .map(|year| AnnualValue {
                year,
                value_dollars: 40_000_000_000,
            })
            .collect();
        // Artificial endpoints-only series (unlike real AT&T, where 2018–2020 exist).
        let capex = vec![
            AnnualValue {
                year: 2017,
                value_dollars: -20_000_000_000,
            },
            AnnualValue {
                year: 2021,
                value_dollars: -16_000_000_000,
            },
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
        let sorted = vec![(2017, -20_000_000_000_i64), (2021, -16_000_000_000)];
        let (cx, kind) = resolve_capex_abs(2015, &sorted);
        assert_eq!(kind, CapexFillKind::Carried);
        assert_eq!(cx, 20_000_000_000);
        let (cx, kind) = resolve_capex_abs(2023, &sorted);
        assert_eq!(kind, CapexFillKind::Carried);
        assert_eq!(cx, 16_000_000_000);
    }

    #[test]
    fn ppe_productive_overlap_prefers_equal_max_not_sum() {
        // AT&T overlap audit: both tags same dollar amount — merge must not double-count.
        let ppe = vec![AnnualValue {
            year: 2017,
            value_dollars: -20_650_000_000,
        }];
        let prod = vec![AnnualValue {
            year: 2017,
            value_dollars: -20_650_000_000,
        }];
        let merged = merge_capex_by_year(&[ppe, prod]);
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].value_dollars.abs(), 20_650_000_000);
    }
}
