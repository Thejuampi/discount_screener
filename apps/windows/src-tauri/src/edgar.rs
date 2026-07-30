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
#[derive(Debug, Clone)]
pub struct AnnualValue {
    pub year: i32,
    pub value_dollars: i64,
}

/// Extract annual values for a given XBRL concept from the companyfacts JSON.
/// Filters to 10-K filings and de-dupes by fiscal year (takes the most-recently filed).
fn extract_annual(facts: &serde_json::Value, concept: &str) -> Vec<AnnualValue> {
    let units = facts.pointer(&format!("/facts/us-gaap/{}/units/USD", concept));
    let arr = match units.and_then(|v| v.as_array()) {
        Some(a) => a,
        None => return vec![],
    };

    // Keep only annual (10-K) filings; de-dup by end-date (latest filed wins)
    let mut by_year: HashMap<String, (i64, String)> = HashMap::new();
    for entry in arr {
        let form = entry["form"].as_str().unwrap_or("");
        if form != "10-K" {
            continue;
        }
        let end = entry["end"].as_str().unwrap_or("").to_string();
        let val = match entry["val"].as_i64() {
            Some(v) => v,
            None => continue,
        };
        let filed = entry["filed"].as_str().unwrap_or("").to_string();
        let existing = by_year.entry(end.clone()).or_insert((val, filed.clone()));
        if filed > existing.1 {
            *existing = (val, filed);
        }
    }

    let mut result: Vec<AnnualValue> = by_year
        .into_iter()
        .filter_map(|(end, (val, _))| {
            let year = end.get(..4)?.parse::<i32>().ok()?;
            Some(AnnualValue {
                year,
                value_dollars: val,
            })
        })
        .collect();
    result.sort_by_key(|v| v.year);
    result
}

/// CapEx XBRL concepts (operating reinvestment). Order does not matter after merge.
///
/// **Do not** include M&A (`PaymentsToAcquireBusinesses*`) — that is not PPE CapEx.
///
/// Issuers rotate tags (e.g. AT&T): PPE filings stop after 2017, but
/// `PaymentsToAcquireProductiveAssets` continues through the "gap" years and is
/// dollar-identical to PPE on every overlap year audited (2011, 2012, 2016, 2017
/// → ratio 1.0). Using only PPE zeroed CapEx post-2017 and made FCF≈OCF.
///
/// Interpolation is a **last resort** after all listed concepts are merged — never
/// preferred over a reported tag for that fiscal year.
const CAPEX_CONCEPTS: &[&str] = &[
    "PaymentsToAcquirePropertyPlantAndEquipment",
    "PaymentsToAcquireProductiveAssets",
    // Additional rare tags some issuers use for network/PPE spend:
    "PaymentsToAcquireOtherPropertyPlantAndEquipment",
    "PaymentsForCapitalImprovements",
];

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
    let series: Vec<Vec<AnnualValue>> = CAPEX_CONCEPTS
        .iter()
        .map(|c| extract_annual(facts, c))
        .filter(|s| !s.is_empty())
        .collect();
    merge_capex_by_year(&series)
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
pub fn fetch_fcf_history(
    client: &Client,
    symbol: &str,
    cik: u64,
) -> Result<Option<Vec<crate::dcf_model::FcfPoint>>, String> {
    let cik_padded = format!("{:010}", cik);
    let url = format!(
        "https://data.sec.gov/api/xbrl/companyfacts/CIK{}.json",
        cik_padded
    );

    let body: serde_json::Value = client
        .get(&url)
        .header("Accept", "application/json")
        .send()
        .map_err(|e| format!("EDGAR {}: {}", symbol, e))?
        .json()
        .map_err(|e| format!("EDGAR parse {}: {}", symbol, e))?;

    let ocf = extract_annual(&body, "NetCashProvidedByUsedInOperatingActivities");
    let capex = extract_capex(&body);

    if ocf.is_empty() {
        return Ok(None);
    }

    let fcf = fcf_history_detailed(&ocf, &capex);
    if fcf.len() < 3 {
        return Ok(None);
    }
    Ok(Some(
        fcf.into_iter()
            .map(|v| crate::dcf_model::FcfPoint {
                year: v.year,
                value_dollars: v.value_dollars as f64,
                capex_imputed: v.capex_imputed,
            })
            .collect(),
    ))
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
mod tests {
    use super::*;

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
