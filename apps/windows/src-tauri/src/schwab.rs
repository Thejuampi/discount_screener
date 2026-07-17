/// Schwab Equity Ratings® Report PDF parser.
///
/// Extracts the structured data from a Schwab Equity Ratings PDF (the report
/// generated from research.schwab.com → Stocks → Schwab Equity Ratings tab).
///
/// We parse the extracted text with line-based scanning + targeted patterns
/// because Schwab's PDF template is highly consistent.
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SchwabReport {
    pub symbol: String,
    pub company_name: Option<String>,
    pub exchange: Option<String>,

    // Primary rating
    pub rating: String,          // "A" | "B" | "C" | "D" | "F"
    pub rating_label: String,    // "Strongly Outperform" etc.
    pub percentile: Option<u32>, // 1-100
    pub previous_rating: Option<String>,

    // Dates
    pub report_date: Option<String>, // "06/07/2026"
    pub data_as_of: Option<String>,  // "06/05/2026"

    // Price & overview
    pub price_at_report_cents: Option<i64>,
    pub market_cap_billions: Option<f64>,
    pub beta: Option<f64>,
    pub sector: Option<String>,
    pub industry: Option<String>,

    // Volatility
    pub price_volatility: Option<String>, // "Low" | "Medium" | "High" | "N/A"

    // Sub-grades (each A-F or none)
    pub growth_grade: Option<String>,
    pub quality_grade: Option<String>,
    pub sentiment_grade: Option<String>,
    pub stability_grade: Option<String>,
    pub valuation_grade: Option<String>,

    // EPS forecasts
    pub eps_forecast_y1: Option<f64>,
    pub eps_forecast_y2: Option<f64>,
    pub eps_growth_5yr_pct: Option<f64>,

    // ESG
    pub esg_rating: Option<String>, // "AAA" | "AA" | "A" | "BBB" | "BB" | "B" | "CCC"

    // Other opinions (raw — best-effort)
    pub cfra_stars: Option<u32>,        // 1-5 if detectable
    pub morningstar_stars: Option<u32>, // 1-5 if detectable

    // Provenance
    pub source_filename: Option<String>,
    pub imported_at_epoch: i64,
}

// ── Parsing ──────────────────────────────────────────────────────────────────

/// Parse a Schwab Equity Ratings PDF given its raw bytes.
pub fn parse_schwab_pdf(
    bytes: &[u8],
    source_filename: Option<String>,
) -> Result<SchwabReport, String> {
    let text = pdf_extract::extract_text_from_mem(bytes)
        .map_err(|e| format!("PDF text extraction failed: {}", e))?;

    let mut r = parse_schwab_text(&text, source_filename.as_deref())?;
    r.source_filename = source_filename;
    r.imported_at_epoch = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    Ok(r)
}

/// Parse the already-extracted text. Exposed for testing.
pub fn parse_schwab_text(
    text: &str,
    source_filename: Option<&str>,
) -> Result<SchwabReport, String> {
    let lines: Vec<&str> = text.lines().map(|l| l.trim()).collect();

    // ── Symbol: try multiple fallbacks (no single point of failure) ─────────
    let symbol = extract_symbol(text, source_filename)
        .ok_or_else(|| {
            let preview = text.chars().take(200).collect::<String>();
            format!(
                "Could not determine symbol from PDF or filename '{}'. First 200 chars of extracted text: {:?}",
                source_filename.unwrap_or("(none)"),
                preview
            )
        })?;

    // ── Rating: search for description anywhere in text ─────────────────────
    let (rating, rating_label) = extract_rating(text).ok_or_else(|| {
        "Could not find Schwab rating (Strongly Outperform/etc.) in PDF text".to_string()
    })?;

    // ── Company + exchange: best-effort, optional ───────────────────────────
    let (company, exchange) = find_company_and_exchange(&lines, &symbol);

    let percentile = find_percentile(&lines);
    let previous_rating = find_previous_rating(&lines);
    let report_date = find_report_date(&lines);
    let data_as_of = find_data_as_of(&lines);

    let (price_at_report_cents, _price_date) = find_price(&lines);
    let market_cap_billions = find_market_cap(&lines);
    let beta = find_beta(&lines);
    let sector = find_after_label(&lines, "Sector");
    let industry = find_after_label(&lines, "Industry");

    let price_volatility = find_price_volatility(&lines);

    let (growth_grade, quality_grade, sentiment_grade, stability_grade, valuation_grade) =
        find_subgrades(&lines);

    let (eps_y1, eps_y2, eps_5yr) = find_eps_forecasts(&lines);

    let esg_rating = find_esg_rating(&lines);

    Ok(SchwabReport {
        symbol,
        company_name: company,
        exchange,
        rating,
        rating_label,
        percentile,
        previous_rating,
        report_date,
        data_as_of,
        price_at_report_cents,
        market_cap_billions,
        beta,
        sector,
        industry,
        price_volatility,
        growth_grade,
        quality_grade,
        sentiment_grade,
        stability_grade,
        valuation_grade,
        eps_forecast_y1: eps_y1,
        eps_forecast_y2: eps_y2,
        eps_growth_5yr_pct: eps_5yr,
        esg_rating,
        cfra_stars: None, // hard to read from text reliably
        morningstar_stars: None,
        source_filename: None,
        imported_at_epoch: 0,
    })
}

// ── Helpers ──────────────────────────────────────────────────────────────────

const RATING_LABELS: &[&str] = &[
    // Order matters: longer first to avoid "Outperform" matching inside "Strongly Outperform"
    "Strongly Outperform",
    "Strongly Underperform",
    "Marketperform",
    "Outperform",
    "Underperform",
];

const KNOWN_EXCHANGES: &[&str] = &["NASDAQ", "NYSE", "AMEX", "NYSEArca", "BATS", "OTC"];

/// Extract ticker symbol from PDF text or filename.
/// Order of attempts:
///   1. "IMPORTANT DISCLOSURES FOR XXX" pattern (always present in Schwab reports)
///   2. Filename (strip extension + date suffix patterns)
fn extract_symbol(text: &str, filename: Option<&str>) -> Option<String> {
    // Pass 1: "IMPORTANT DISCLOSURES FOR XXX"
    if let Some(pos) = text.find("IMPORTANT DISCLOSURES FOR ") {
        let after = &text[pos + "IMPORTANT DISCLOSURES FOR ".len()..];
        let sym: String = after
            .chars()
            .take_while(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || *c == '.' || *c == '-')
            .collect();
        if !sym.is_empty() && sym.len() <= 8 {
            return Some(sym);
        }
    }

    // Pass 2: filename
    if let Some(name) = filename {
        // Strip path components
        let basename = name
            .rsplit('/')
            .next()
            .and_then(|s| s.rsplit('\\').next())
            .unwrap_or(name);
        // Strip extension
        let stem = basename
            .rsplit_once('.')
            .map(|(s, _)| s)
            .unwrap_or(basename);
        // Take leading ticker characters: uppercase letters / digits / '.' (e.g. BRK.B)
        let sym: String = stem
            .chars()
            .take_while(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || *c == '.')
            .collect();
        if !sym.is_empty() && sym.len() <= 8 {
            return Some(sym);
        }
    }

    None
}

/// Extract the Schwab rating (A-F) by searching the entire text for a known
/// rating label and reading the letter immediately before it.
fn extract_rating(text: &str) -> Option<(String, String)> {
    for label in RATING_LABELS {
        let mut search_start = 0;
        while let Some(pos) = text[search_start..].find(label) {
            let abs_pos = search_start + pos;
            search_start = abs_pos + label.len();
            // Find the last non-whitespace char before this label position
            let before = &text[..abs_pos];
            if let Some(c) = before.chars().rev().find(|c| !c.is_whitespace()) {
                if ('A'..='F').contains(&c) {
                    return Some((c.to_string(), label.to_string()));
                }
            }
        }
    }
    None
}

/// Best-effort extraction of company name + exchange.
fn find_company_and_exchange(lines: &[&str], symbol: &str) -> (Option<String>, Option<String>) {
    let mut exchange: Option<String> = None;
    let mut company: Option<String> = None;

    // Find a line that starts with the symbol — typically "AAPL Apple Inc ARating..."
    for (i, line) in lines.iter().enumerate() {
        let trimmed = line.trim();
        if let Some(rest) = trimmed.strip_prefix(symbol) {
            let rest = rest.trim_start();
            if !rest.is_empty()
                && rest
                    .chars()
                    .next()
                    .map(|c| c.is_alphabetic())
                    .unwrap_or(false)
            {
                // Try to find rating label in rest to split company from rating
                for label in RATING_LABELS {
                    if let Some(idx) = rest.rfind(label) {
                        if idx > 0 {
                            // Letter right before label is rating; company is before that letter
                            let letter_byte = rest.as_bytes()[idx - 1];
                            if (b'A'..=b'F').contains(&letter_byte) {
                                let c = rest[..idx - 1].trim().to_string();
                                if !c.is_empty() {
                                    company = Some(c);
                                }
                                break;
                            }
                        }
                    }
                }
                if company.is_none() {
                    // No rating label found inline — just take everything after symbol
                    let c = rest.trim().to_string();
                    if !c.is_empty() && c.len() < 100 {
                        company = Some(c);
                    }
                }
                // Check the previous line for an exchange name
                if i > 0 {
                    let prev = lines[i - 1].trim();
                    if KNOWN_EXCHANGES.iter().any(|e| prev == *e) {
                        exchange = Some(prev.to_string());
                    }
                }
                break;
            }
        }
    }

    (company, exchange)
}

fn find_percentile(lines: &[&str]) -> Option<u32> {
    for line in lines {
        if let Some(rest) = line.strip_prefix("Percentile Ranking:") {
            let trimmed = rest.trim();
            // May contain trailing text — extract leading digits
            let digits: String = trimmed.chars().take_while(|c| c.is_ascii_digit()).collect();
            if let Ok(n) = digits.parse::<u32>() {
                return Some(n);
            }
        }
    }
    None
}

/// Find the first "Last week's rating: X ..." occurrence.
fn find_previous_rating(lines: &[&str]) -> Option<String> {
    for line in lines {
        if let Some(rest) = line.strip_prefix("Last week's rating:") {
            let trimmed = rest.trim();
            let first_char = trimmed.chars().next()?;
            if ('A'..='F').contains(&first_char) {
                return Some(first_char.to_string());
            }
        }
    }
    None
}

fn find_report_date(lines: &[&str]) -> Option<String> {
    for line in lines {
        if let Some(rest) = line.strip_prefix("Report generated on") {
            // e.g. " 06/07/2026, 03:15 AM"
            let date_part = rest.trim().split(',').next()?.trim();
            return Some(date_part.to_string());
        }
    }
    None
}

fn find_data_as_of(lines: &[&str]) -> Option<String> {
    for line in lines {
        if let Some(rest) = line.strip_prefix("Data as of") {
            return Some(rest.trim().to_string());
        }
    }
    None
}

/// Parse "Price as of 6/5/26 $307.34" or "Price as of 6/5/26 $307.34" etc.
fn find_price(lines: &[&str]) -> (Option<i64>, Option<String>) {
    for line in lines {
        if let Some(rest) = line.strip_prefix("Price as of") {
            // rest = " 6/5/26 $307.34"
            let trimmed = rest.trim();
            // Find $ to split date from price
            if let Some(dollar_idx) = trimmed.find('$') {
                let date_str = trimmed[..dollar_idx].trim().to_string();
                let price_str: String = trimmed[dollar_idx + 1..]
                    .chars()
                    .take_while(|c| c.is_ascii_digit() || *c == '.' || *c == ',')
                    .collect();
                let price_str = price_str.replace(',', "");
                if let Ok(price) = price_str.parse::<f64>() {
                    return (Some((price * 100.0).round() as i64), Some(date_str));
                }
            }
        }
    }
    (None, None)
}

fn find_market_cap(lines: &[&str]) -> Option<f64> {
    for line in lines {
        if let Some(rest) = line.strip_prefix("Market Capitalization") {
            // e.g. " $4514.0 Billion"
            let trimmed = rest.trim().trim_start_matches('$');
            let num_str: String = trimmed
                .chars()
                .take_while(|c| c.is_ascii_digit() || *c == '.' || *c == ',')
                .collect();
            let num_str = num_str.replace(',', "");
            let multiplier = if trimmed.contains("Billion") {
                1.0
            } else if trimmed.contains("Million") {
                0.001
            } else if trimmed.contains("Trillion") {
                1000.0
            } else {
                1.0
            };
            if let Ok(n) = num_str.parse::<f64>() {
                return Some(n * multiplier);
            }
        }
    }
    None
}

fn find_beta(lines: &[&str]) -> Option<f64> {
    for line in lines {
        if let Some(rest) = line.strip_prefix("Beta") {
            let trimmed = rest.trim();
            if let Ok(n) = trimmed.parse::<f64>() {
                return Some(n);
            }
        }
    }
    None
}

/// Find the value after a label, on the SAME line ("Sector Information Technology").
fn find_after_label(lines: &[&str], label: &str) -> Option<String> {
    for line in lines {
        if let Some(rest) = line.strip_prefix(label) {
            let v = rest.trim();
            if !v.is_empty() {
                return Some(v.to_string());
            }
        }
    }
    None
}

/// Look for the volatility outlook value (Low/Medium/High) in the
/// "PRICE VOLATILITY OUTLOOK" section.
fn find_price_volatility(lines: &[&str]) -> Option<String> {
    // After the PRICE VOLATILITY OUTLOOK header, the first line is the value
    // followed by "Below average price volatility" or similar.
    let mut in_section = false;
    for line in lines {
        if line.contains("PRICE VOLATILITY OUTLOOK") {
            in_section = true;
            continue;
        }
        if !in_section {
            continue;
        }
        // Skip empty / data-as-of lines
        if line.is_empty() || line.starts_with("Data as of") || line.starts_with("Source:") {
            continue;
        }
        // The value line: "Low Below average price volatility" or just "Low"
        for v in &["Low", "Medium", "High", "N/A"] {
            if line.starts_with(v) {
                return Some(v.to_string());
            }
        }
        // If we hit the next section, give up
        if line.contains("RATIONALE BEHIND") {
            break;
        }
    }
    None
}

/// Find the 5 sub-grades from the rationale table.
fn find_subgrades(
    lines: &[&str],
) -> (
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
) {
    let mut g = (None, None, None, None, None);
    for line in lines {
        // Lines like: "B Growth Grade Positive Positive Medium"
        let trimmed = line.trim();
        if trimmed.len() < 3 {
            continue;
        }
        let first_char = trimmed.chars().next().unwrap();
        if !('A'..='F').contains(&first_char) {
            continue;
        }
        let after_letter = trimmed[1..].trim_start();
        if after_letter.starts_with("Growth Grade") {
            g.0 = Some(first_char.to_string());
        } else if after_letter.starts_with("Quality Grade") {
            g.1 = Some(first_char.to_string());
        } else if after_letter.starts_with("Sentiment Grade") {
            g.2 = Some(first_char.to_string());
        } else if after_letter.starts_with("Stability Grade") {
            g.3 = Some(first_char.to_string());
        } else if after_letter.starts_with("Valuation Grade") {
            g.4 = Some(first_char.to_string());
        }
    }
    g
}

/// Look for the "ANNUAL EARNINGS FORECAST" section table:
///   09/30/2026 $8.77 $0.01 $8.53-$9.01 39
///   09/30/2027 $9.67 $0.02 $8.81-$10.96 38
///   Next 5 Yr. Growth Rate 13.8% 0.0% 13.3%-14.2% 2
fn find_eps_forecasts(lines: &[&str]) -> (Option<f64>, Option<f64>, Option<f64>) {
    let mut in_section = false;
    let mut year_values: Vec<f64> = Vec::new();
    let mut growth: Option<f64> = None;

    for line in lines {
        if line.contains("ANNUAL EARNINGS FORECAST") {
            in_section = true;
            continue;
        }
        if !in_section {
            continue;
        }
        let trimmed = line.trim();
        // Date-prefixed line: starts with M/D/YY or MM/DD/YYYY
        if let Some(first_dollar) = trimmed.find('$') {
            // Crude: parse first dollar value
            let after = &trimmed[first_dollar + 1..];
            let num_str: String = after
                .chars()
                .take_while(|c| c.is_ascii_digit() || *c == '.' || *c == ',')
                .collect();
            let num_str = num_str.replace(',', "");
            if let Ok(n) = num_str.parse::<f64>() {
                year_values.push(n);
            }
        }
        if trimmed.starts_with("Next 5 Yr") || trimmed.starts_with("5 Yr") {
            // Find first percentage
            if let Some(pct_idx) = trimmed.find('%') {
                let preceding = &trimmed[..pct_idx];
                let num_str: String = preceding
                    .chars()
                    .rev()
                    .take_while(|c| c.is_ascii_digit() || *c == '.' || *c == ',' || *c == '-')
                    .collect::<String>()
                    .chars()
                    .rev()
                    .collect();
                let num_str = num_str.replace(',', "");
                if let Ok(n) = num_str.parse::<f64>() {
                    growth = Some(n);
                }
            }
            break;
        }
        // If section drifts too far, stop
        if trimmed.contains("DIVIDENDS") || trimmed.contains("OTHER OPINIONS") {
            break;
        }
    }

    let y1 = year_values.first().copied();
    let y2 = year_values.get(1).copied();
    (y1, y2, growth)
}

/// MSCI ESG Rating — values are CCC, B, BB, BBB, A, AA, AAA.
fn find_esg_rating(lines: &[&str]) -> Option<String> {
    const VALID: &[&str] = &["AAA", "AA", "A", "BBB", "BB", "B", "CCC"];
    let mut after_esg_header = false;
    for line in lines {
        if line.contains("ESG RATING") || line.contains("MSCI ESG Rating") {
            after_esg_header = true;
            continue;
        }
        if !after_esg_header {
            continue;
        }
        // Look for a standalone rating value on its own line
        let trimmed = line.trim();
        if VALID.contains(&trimmed) {
            return Some(trimmed.to_string());
        }
        if trimmed.contains("EARNINGS PER SHARE") {
            break;
        }
    }
    None
}
