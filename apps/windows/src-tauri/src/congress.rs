/// Congressional trading data ingestion (US House STOCK Act).
///
/// Pipeline:
///   1. Download YYYY FD.zip from House Clerk official source
///   2. Extract embedded XML index → list every Periodic Transaction Report (PTR)
///   3. For each PTR, download the individual PDF
///   4. Parse PDF text to extract structured trades (symbol, type, date, amount)
///
/// All data is officially published. No scraping of third-party sites, no auth.

use std::collections::HashMap;
use std::io::Read;
use std::time::Duration;
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};

const USER_AGENT: &str =
    "Mozilla/5.0 (Discount Screener) DiscountScreenerCongressIngest/1.0";

// ── Public types ─────────────────────────────────────────────────────────────

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PoliticianStub {
    pub full_name: String,
    pub last_name: String,
    pub first_name: String,
    pub chamber: String,            // "House" or "Senate"
    pub state: Option<String>,
    pub district: Option<String>,
}

/// One entry in the year FD.xml — represents a filing (not a trade).
/// FilingType "P" = Periodic Transaction Report (contains trades).
#[derive(Clone, Debug)]
pub struct PtrFiling {
    pub politician: PoliticianStub,
    pub year: u32,
    pub doc_id: String,
    pub filing_date: String,        // raw MM/DD/YYYY
}

/// A single trade row parsed from a PTR PDF.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CongressTrade {
    pub doc_id: String,
    pub politician_full_name: String,
    pub chamber: String,
    pub state: Option<String>,
    pub district: Option<String>,
    pub owner: Option<String>,          // SP, JT, DC, or None (self)
    pub asset_name: String,
    pub symbol: Option<String>,         // ticker if present
    pub asset_type: Option<String>,     // ST, GS, OP, etc.
    pub transaction_type: String,       // "P" | "S" | "S (partial)" | "E"
    pub transaction_date: Option<String>,   // ISO YYYY-MM-DD
    pub disclosure_date: Option<String>,    // ISO YYYY-MM-DD
    pub amount_range_min: Option<i64>,      // USD
    pub amount_range_max: Option<i64>,      // USD
    pub cap_gains_over_200: Option<bool>,
}

// ── HTTP client ──────────────────────────────────────────────────────────────

pub fn congress_client() -> Client {
    Client::builder()
        .timeout(Duration::from_secs(45))
        .user_agent(USER_AGENT)
        .build()
        .expect("congress http client")
}

// ── Step 1: download + unzip + extract XML ──────────────────────────────────

pub fn fetch_year_index(client: &Client, year: u32) -> Result<String, String> {
    let url = format!(
        "https://disclosures-clerk.house.gov/public_disc/financial-pdfs/{}FD.zip",
        year
    );
    let bytes = client.get(&url).send()
        .map_err(|e| format!("FD.zip fetch {}: {}", year, e))?
        .bytes()
        .map_err(|e| format!("FD.zip body {}: {}", year, e))?;

    // Unzip in memory
    let cursor = std::io::Cursor::new(bytes);
    let mut zip = zip::ZipArchive::new(cursor)
        .map_err(|e| format!("FD.zip parse {}: {}", year, e))?;

    let xml_name = format!("{}FD.xml", year);
    let mut xml_file = zip.by_name(&xml_name)
        .map_err(|e| format!("XML not found in zip {}: {}", year, e))?;
    let mut xml = String::new();
    xml_file.read_to_string(&mut xml)
        .map_err(|e| format!("read XML {}: {}", year, e))?;
    Ok(xml)
}

// ── Step 2: parse XML for PTR filings ────────────────────────────────────────

/// Extract all PTR (FilingType=P) filings from the year XML index.
pub fn parse_ptr_filings(xml: &str, year: u32) -> Vec<PtrFiling> {
    let mut out = Vec::new();
    // The XML is regular. Each <Member> block has consistent fields.
    let mut cursor = 0usize;
    while let Some(start) = xml[cursor..].find("<Member>") {
        let abs_start = cursor + start;
        let end = match xml[abs_start..].find("</Member>") {
            Some(e) => abs_start + e,
            None => break,
        };
        let block = &xml[abs_start..end];
        cursor = end + 1;

        // Only keep PTRs
        let filing_type = inner(block, "FilingType");
        if filing_type.as_deref() != Some("P") { continue; }

        let last = inner(block, "Last").unwrap_or_default();
        let first = inner(block, "First").unwrap_or_default();
        let prefix = inner(block, "Prefix").unwrap_or_default();
        let suffix = inner(block, "Suffix").unwrap_or_default();
        let state_dst = inner(block, "StateDst").unwrap_or_default();
        let doc_id = match inner(block, "DocID") {
            Some(d) if !d.is_empty() => d,
            _ => continue,
        };
        let filing_date = inner(block, "FilingDate").unwrap_or_default();

        let full_name = build_full_name(&prefix, &first, &last, &suffix);
        let (state, district) = split_state_district(&state_dst);

        out.push(PtrFiling {
            politician: PoliticianStub {
                full_name,
                last_name: last,
                first_name: first,
                chamber: "House".to_string(),
                state,
                district,
            },
            year,
            doc_id,
            filing_date,
        });
    }
    out
}

/// Extract content of `<Tag>...</Tag>` (single line or simple).
fn inner(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<{}>", tag);
    let close = format!("</{}>", tag);
    let self_close = format!("<{} />", tag);
    if xml.contains(&self_close) { return Some(String::new()); }
    let s = xml.find(&open)? + open.len();
    let e = xml[s..].find(&close)? + s;
    Some(xml[s..e].trim().to_string())
}

fn build_full_name(prefix: &str, first: &str, last: &str, suffix: &str) -> String {
    let mut parts: Vec<&str> = Vec::new();
    if !prefix.is_empty() { parts.push(prefix); }
    if !first.is_empty() { parts.push(first); }
    if !last.is_empty() { parts.push(last); }
    if !suffix.is_empty() { parts.push(suffix); }
    parts.join(" ")
}

fn split_state_district(state_dst: &str) -> (Option<String>, Option<String>) {
    if state_dst.len() >= 2 {
        let st = state_dst[..2].to_string();
        let dst = if state_dst.len() > 2 { Some(state_dst[2..].to_string()) } else { None };
        (Some(st), dst)
    } else {
        (None, None)
    }
}

// ── Step 3: fetch + parse individual PTR PDF ─────────────────────────────────

pub fn fetch_ptr_pdf(client: &Client, year: u32, doc_id: &str) -> Result<Vec<u8>, String> {
    let url = format!(
        "https://disclosures-clerk.house.gov/public_disc/ptr-pdfs/{}/{}.pdf",
        year, doc_id
    );
    let resp = client.get(&url).send()
        .map_err(|e| format!("PTR {} fetch: {}", doc_id, e))?;
    if !resp.status().is_success() {
        return Err(format!("PTR {} HTTP {}", doc_id, resp.status()));
    }
    let bytes = resp.bytes()
        .map_err(|e| format!("PTR {} body: {}", doc_id, e))?;
    Ok(bytes.to_vec())
}

pub fn parse_ptr_pdf(bytes: &[u8], filing: &PtrFiling) -> Result<Vec<CongressTrade>, String> {
    let text = pdf_extract::extract_text_from_mem(bytes)
        .map_err(|e| format!("PTR PDF extract: {}", e))?;
    Ok(parse_ptr_text(&text, filing))
}

/// Parse the text of a PTR PDF into individual trade rows.
///
/// PTR structure (after text extraction):
///   - Header with "Name:", "State/District:" lines
///   - Table: ID | Owner | Asset | Transaction Type | Date | Notification Date | Amount | Cap.Gains
///   - Each row may span multiple lines (asset name + ticker + type + SUBHOLDING OF: line)
///
/// We use line-by-line scanning + heuristics. The structure is consistent but
/// pdf_extract may reorder some elements, so we focus on extracting the data
/// we recognize via pattern matching (dates, ticker codes, amount ranges).
pub fn parse_ptr_text(text: &str, filing: &PtrFiling) -> Vec<CongressTrade> {
    let mut trades = Vec::new();

    // Strategy:
    // For each line that contains a ticker in `(XXX)` and an asset code in `[XX]`,
    // try to recover the full transaction row by examining surrounding lines.
    // Many PTRs format one transaction across 2-3 lines.

    // Date regex: M/D/YYYY or MM/DD/YYYY (no regex crate — manual scan)
    fn extract_dates(s: &str) -> Vec<String> {
        // Return all MM/DD/YYYY-like substrings
        let mut out = Vec::new();
        let bytes = s.as_bytes();
        let mut i = 0;
        while i < bytes.len() {
            // Look for a digit followed by digits with slashes
            if bytes[i].is_ascii_digit() {
                let start = i;
                let mut slashes = 0;
                let mut j = i;
                while j < bytes.len() && (bytes[j].is_ascii_digit() || bytes[j] == b'/') {
                    if bytes[j] == b'/' { slashes += 1; }
                    j += 1;
                }
                if slashes == 2 && j - start >= 6 && j - start <= 10 {
                    let candidate = &s[start..j];
                    // Light validity check: M/D/YYYY pattern
                    let parts: Vec<&str> = candidate.split('/').collect();
                    if parts.len() == 3 && parts[2].len() == 4 {
                        out.push(candidate.to_string());
                    }
                }
                i = j;
            } else {
                i += 1;
            }
        }
        out
    }

    fn parse_amount_range(s: &str) -> (Option<i64>, Option<i64>) {
        // Look for patterns like $X,XXX - $YY,YYY
        // Find first '$', extract digits up to ' -' or whitespace
        let mut iter = s.match_indices('$');
        let mut nums: Vec<i64> = Vec::new();
        while let Some((idx, _)) = iter.next() {
            let rest = &s[idx + 1..];
            let n_str: String = rest.chars()
                .take_while(|c| c.is_ascii_digit() || *c == ',')
                .collect();
            let n_str = n_str.replace(',', "");
            if let Ok(n) = n_str.parse::<i64>() {
                nums.push(n);
            }
        }
        (nums.first().copied(), nums.get(1).copied())
    }

    /// Try to extract a ticker from a line containing "(TICKER)".
    fn extract_ticker(s: &str) -> Option<String> {
        let open = s.find('(')?;
        let close = s[open + 1..].find(')')? + open + 1;
        let ticker = s[open + 1..close].trim();
        // Tickers are usually 1-6 uppercase letters, sometimes with a dot (BRK.B)
        if !ticker.is_empty()
            && ticker.len() <= 8
            && ticker.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || c == '.')
        {
            Some(ticker.to_string())
        } else {
            None
        }
    }

    /// Extract asset type code in brackets [XX].
    fn extract_asset_type(s: &str) -> Option<String> {
        let open = s.find('[')?;
        let close = s[open + 1..].find(']')? + open + 1;
        Some(s[open + 1..close].to_string())
    }

    // Split into logical "transaction blocks": we look for lines with both a
    // ticker `(XXX)` and an asset type code `[XX]`. Each is one transaction.
    let lines: Vec<&str> = text.lines().collect();
    for (i, line) in lines.iter().enumerate() {
        let ticker = extract_ticker(line);
        let asset_type = extract_asset_type(line);
        if ticker.is_none() && asset_type.is_none() { continue; }
        // Heuristic: ticker may live on a line with the asset name, while the
        // [code] appears on the same or next line. We'll join the current line
        // with the next 1-2 lines to capture date + amount.
        let window = lines.iter().skip(i).take(3).cloned().collect::<Vec<_>>().join(" ");

        // Owner: look at start of the joined window for SP / JT / DC patterns,
        // or the previous line. PDFs put Owner before Asset cell.
        let owner = detect_owner(line, lines.get(i.saturating_sub(1)).copied());

        // Transaction type: P / S / S (partial) / E
        // It typically appears on the asset line near dates. We try to find it
        // after the closing ] or ).
        let tx_type = detect_tx_type(&window);

        // Dates: first date = transaction, second = notification (disclosure)
        let dates = extract_dates(&window);
        let tx_date_raw = dates.first().cloned();
        let disc_date_raw = dates.get(1).cloned();

        // Amount range
        let (amt_min, amt_max) = parse_amount_range(&window);

        // Cap gains > $200 detection: simple presence of "X" or check marks is unreliable
        // from PDF text extraction; we leave as None for now.

        if ticker.is_none() && asset_type.is_none() { continue; }

        // Asset name: text before "(" on the current line (if ticker is here)
        let asset_name = if let Some(open) = line.find('(') {
            line[..open].trim().to_string()
        } else if let Some(open) = line.find('[') {
            line[..open].trim().to_string()
        } else {
            line.trim().to_string()
        };

        if tx_date_raw.is_none() && amt_min.is_none() {
            // Probably not a real transaction row (could be a footer line)
            continue;
        }

        trades.push(CongressTrade {
            doc_id: filing.doc_id.clone(),
            politician_full_name: filing.politician.full_name.clone(),
            chamber: filing.politician.chamber.clone(),
            state: filing.politician.state.clone(),
            district: filing.politician.district.clone(),
            owner,
            asset_name,
            symbol: ticker,
            asset_type,
            transaction_type: tx_type.unwrap_or_else(|| "?".to_string()),
            transaction_date: tx_date_raw.and_then(|d| to_iso(&d)),
            disclosure_date: disc_date_raw.and_then(|d| to_iso(&d)),
            amount_range_min: amt_min,
            amount_range_max: amt_max,
            cap_gains_over_200: None,
        });
    }

    trades
}

fn detect_owner(line: &str, _prev: Option<&str>) -> Option<String> {
    // Look for SP, JT, DC tokens as whole-word at the start
    for token in &["SP", "JT", "DC"] {
        let needle_with_space = format!("{} ", token);
        if line.starts_with(&needle_with_space) || line.trim_start().starts_with(&needle_with_space) {
            return Some(token.to_string());
        }
    }
    None
}

fn detect_tx_type(window: &str) -> Option<String> {
    // Patterns like " P " or " S " or " E " appearing near dates in the row.
    // We look for whitespace-delimited single letters P/S/E within a small radius.
    // Fallback: search for "Purchase", "Sale", "Exchange" full words.
    if window.contains(" Purchase ") || window.contains("\nPurchase") { return Some("P".to_string()); }
    if window.contains(" Sale ") || window.contains("\nSale") { return Some("S".to_string()); }
    if window.contains(" Exchange ") { return Some("E".to_string()); }

    // Look for single-letter codes
    let chars: Vec<char> = window.chars().collect();
    for i in 1..chars.len().saturating_sub(1) {
        let c = chars[i];
        let prev = chars[i - 1];
        let next = chars[i + 1];
        if matches!(c, 'P' | 'S' | 'E')
            && prev.is_whitespace()
            && (next.is_whitespace() || next == '\n')
        {
            return Some(c.to_string());
        }
    }
    None
}

/// Convert MM/DD/YYYY → ISO YYYY-MM-DD.
fn to_iso(d: &str) -> Option<String> {
    let parts: Vec<&str> = d.split('/').collect();
    if parts.len() != 3 { return None; }
    let m: u32 = parts[0].parse().ok()?;
    let day: u32 = parts[1].parse().ok()?;
    let y: u32 = parts[2].parse().ok()?;
    Some(format!("{:04}-{:02}-{:02}", y, m, day))
}

// ── Step 4: aggregate ingestion ─────────────────────────────────────────────

/// Result of a full year sync operation.
#[derive(Clone, Debug, Serialize)]
pub struct CongressSyncResult {
    pub year: u32,
    pub ptr_count: usize,
    pub processed: usize,
    pub skipped: usize,
    pub failed: usize,
    pub trades_imported: usize,
    pub errors_sample: Vec<String>,
}

/// Aggregate trades by ticker for the overview dashboard.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct TickerActivity {
    pub symbol: String,
    pub buy_count: u32,
    pub sell_count: u32,
    pub unique_politicians: u32,
    pub last_disclosure_date: Option<String>,
    pub total_amount_min: i64,
    pub total_amount_max: i64,
}

pub fn aggregate_by_ticker(trades: &[CongressTrade]) -> Vec<TickerActivity> {
    let mut map: HashMap<String, TickerActivity> = HashMap::new();
    for t in trades {
        let Some(sym) = &t.symbol else { continue; };
        let entry = map.entry(sym.clone()).or_insert_with(|| TickerActivity {
            symbol: sym.clone(),
            ..Default::default()
        });
        match t.transaction_type.as_str() {
            "P" => entry.buy_count += 1,
            "S" | "S (partial)" => entry.sell_count += 1,
            _ => {}
        }
        entry.total_amount_min += t.amount_range_min.unwrap_or(0);
        entry.total_amount_max += t.amount_range_max.unwrap_or(0);
        if let Some(d) = &t.disclosure_date {
            entry.last_disclosure_date = match &entry.last_disclosure_date {
                Some(existing) if existing.as_str() > d.as_str() => Some(existing.clone()),
                _ => Some(d.clone()),
            };
        }
    }
    // unique_politicians not computed here — would require politician_id grouping
    map.into_values().collect()
}
