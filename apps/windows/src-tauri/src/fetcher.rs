//! Yahoo Finance fetcher.
//!
//! Primary path (Android parity): authenticated JSON `v10/finance/quoteSummary`
//! via cookie + crumb session. Chart / search remain public REST endpoints.
//! HTML quote-page scrape is optional fallback only (`html_fallback`, default off).

use std::io;
use std::time::Duration;

use reqwest::blocking::Client;
use serde_json::Value;

use crate::engine::HistoricalCandle;
use crate::quote_summary::{
    apply_asset_class_overrides, parse_quote_summary, with_price_fallback, yahoo_request_symbol,
    QUOTE_SUMMARY_MODULES,
};
use crate::ticker_search::{parse_search_quotes, YahooSearchQuote};
use crate::yahoo_session::{is_auth_error, YahooSession};

const HTTP_TIMEOUT: Duration = Duration::from_secs(20);
const QUOTE_PAGE_URL: &str = "https://finance.yahoo.com/quote/";
const CHART_API_URL: &str = "https://query1.finance.yahoo.com/v8/finance/chart/";
const QUOTE_SUMMARY_URL: &str = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/";
const SEARCH_API_URL: &str = "https://query2.finance.yahoo.com/v1/finance/search";

const USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

pub use crate::quote_summary::FetchResult;

// Markers for JSON blobs embedded in the Yahoo Finance quote page HTML.
// Yahoo serialises the store data as an escaped JSON string, so all key quotes
// appear as \" in the raw HTML — that's what we search for.
const FINANCIAL_DATA_MARKER: &str      = r#"\"financialData\":"#;
const KEY_STATISTICS_MARKER: &str      = r#"\"defaultKeyStatistics\":"#;
const PRICE_MARKER: &str               = r#"\"price\":"#;
const ASSET_PROFILE_MARKER: &str       = r#"\"assetProfile\":"#;
const SUMMARY_PROFILE_MARKER: &str     = r#"\"summaryProfile\":"#;
const QUOTE_TYPE_MARKER: &str          = r#"\"quoteType\":"#;
const RECOMMENDATION_TREND_MARKER: &str = r#"\"recommendationTrend\":"#;
const CALENDAR_EVENTS_MARKER: &str     = r#"\"calendarEvents\":"#;
const META_TITLE_MARKER: &str          = r#"<meta property="og:title" content=""#;

/// Top market cap cryptocurrencies (non-stablecoin) on Yahoo Finance.
/// All use the `-USD` suffix for spot price in dollars.
pub const CRYPTO_SYMBOLS: &[&str] = &[
    "BTC-USD",  // Bitcoin
    "ETH-USD",  // Ethereum
    "BNB-USD",  // Binance Coin
    "SOL-USD",  // Solana
    "XRP-USD",  // Ripple
    "DOGE-USD", // Dogecoin
    "ADA-USD",  // Cardano
    "TRX-USD",  // TRON
    "AVAX-USD", // Avalanche
    "DOT-USD",  // Polkadot
    "LINK-USD", // Chainlink
    "LTC-USD",  // Litecoin
    "SHIB-USD", // Shiba Inu
    "ATOM-USD", // Cosmos
    "UNI-USD",  // Uniswap
    "BCH-USD",  // Bitcoin Cash
    "XLM-USD",  // Stellar
    "NEAR-USD", // NEAR Protocol
    "ETC-USD",  // Ethereum Classic
    "APT-USD",  // Aptos
    "HBAR-USD", // Hedera
    "FIL-USD",  // Filecoin
    "ARB-USD",  // Arbitrum
    "OP-USD",   // Optimism
    "ICP-USD",  // Internet Computer
];

/// True if a symbol is a Yahoo-style crypto ticker (suffix `-USD`).
pub fn is_crypto(symbol: &str) -> bool {
    symbol.ends_with("-USD")
}

/// Top ETFs by AUM (US-listed). Treated like stocks for charts but skip EDGAR
/// (no XBRL filings) and base their Act/Watch/Avoid on technicals only.
pub const ETF_SYMBOLS: &[&str] = &[
    // Broad market / S&P 500
    "SPY", "IVV", "VOO", "VTI", "SCHB",
    // Nasdaq / large growth
    "QQQ", "QQQM", "VUG",
    // Small / mid cap
    "IWM", "IJR", "VB",
    // Dow Jones / value
    "DIA", "VTV",
    // International
    "VEA", "VWO", "EFA", "EEM",
    // Bonds
    "AGG", "BND", "LQD", "HYG", "TLT", "IEF", "SHY",
    // Commodities
    "GLD", "SLV", "USO",
    // Real estate
    "VNQ", "IYR",
    // Sector SPDRs
    "XLE", "XLF", "XLK", "XLV", "XLI",
    "XLY", "XLP", "XLU", "XLB", "XLRE", "XLC",
    // Thematic / dividend
    "ARKK", "ARKG", "ARKQ", "VYM", "DVY", "SCHD",
];

/// True if symbol is a known ETF (membership check).
pub fn is_etf(symbol: &str) -> bool {
    ETF_SYMBOLS.contains(&symbol)
}

/// Classify a symbol into one of: "crypto" | "etf" | "stock".
pub fn asset_type(symbol: &str) -> &'static str {
    if is_crypto(symbol) { "crypto" }
    else if is_etf(symbol) { "etf" }
    else { "stock" }
}

/// Map an ETF ticker to a human-readable description of the segment it covers.
/// Used to populate `sector_name` for ETFs (which don't expose sector via Yahoo).
pub fn etf_sector(symbol: &str) -> Option<&'static str> {
    Some(match symbol {
        // Broad market
        "SPY" | "IVV" | "VOO" | "VTI" | "SCHB" => "Broad Market (US)",
        // Nasdaq / Tech
        "QQQ" | "QQQM"                          => "Technology / Nasdaq 100",
        // Style: growth / value
        "VUG"                                   => "Large-cap Growth",
        "VTV"                                   => "Large-cap Value",
        // Small / mid cap
        "IWM" | "IJR" | "VB"                    => "Small Cap",
        // Blue chips
        "DIA"                                   => "Dow Jones Industrial",
        // International
        "VEA" | "EFA"                           => "Developed Markets ex-US",
        "VWO" | "EEM"                           => "Emerging Markets",
        // Bonds
        "AGG" | "BND"                           => "Aggregate Bonds",
        "LQD"                                   => "Investment Grade Corp Bonds",
        "HYG"                                   => "High Yield Corp Bonds",
        "TLT"                                   => "Long-Term Treasury",
        "IEF"                                   => "Mid-Term Treasury",
        "SHY"                                   => "Short-Term Treasury",
        // Commodities
        "GLD"                                   => "Gold",
        "SLV"                                   => "Silver",
        "USO"                                   => "Oil",
        // Real estate
        "VNQ" | "IYR"                           => "Real Estate",
        // Sector SPDRs (S&P 500 by sector)
        "XLE"                                   => "Energy Sector",
        "XLF"                                   => "Financial Sector",
        "XLK"                                   => "Technology Sector",
        "XLV"                                   => "Healthcare Sector",
        "XLI"                                   => "Industrial Sector",
        "XLY"                                   => "Consumer Discretionary",
        "XLP"                                   => "Consumer Staples",
        "XLU"                                   => "Utilities Sector",
        "XLB"                                   => "Materials Sector",
        "XLRE"                                  => "Real Estate Sector",
        "XLC"                                   => "Communication Services",
        // Thematic
        "ARKK"                                  => "Disruptive Innovation",
        "ARKG"                                  => "Genomics & Biotech",
        "ARKQ"                                  => "Autonomous Tech & Robotics",
        // Dividend / income
        "VYM" | "DVY" | "SCHD"                  => "Dividend Income",
        _                                       => return None,
    })
}

pub const DEFAULT_LIVE_SYMBOLS: [&str; 503] = [
    "MMM", "AOS", "ABT", "ABBV", "ACN", "ADBE", "AMD", "AES", "AFL", "A", "APD", "ABNB", "AKAM",
    "ALB", "ARE", "ALGN", "ALLE", "LNT", "ALL", "GOOGL", "GOOG", "MO", "AMZN", "AMCR", "AEE",
    "AEP", "AXP", "AIG", "AMT", "AWK", "AMP", "AME", "AMGN", "APH", "ADI", "AON", "APA", "APO",
    "AAPL", "AMAT", "APP", "APTV", "ACGL", "ADM", "ARES", "ANET", "AJG", "AIZ", "T", "ATO",
    "ADSK", "ADP", "AZO", "AVB", "AVY", "AXON", "BKR", "BALL", "BAC", "BAX", "BDX", "BRK.B",
    "BBY", "TECH", "BIIB", "BLK", "BX", "XYZ", "BK", "BA", "BKNG", "BSX", "BMY", "AVGO", "BR",
    "BRO", "BF.B", "BLDR", "BG", "BXP", "CHRW", "CDNS", "CPT", "CPB", "COF", "CAH", "CCL",
    "CARR", "CVNA", "CAT", "CBOE", "CBRE", "CDW", "COR", "CNC", "CNP", "CF", "CRL", "SCHW",
    "CHTR", "CVX", "CMG", "CB", "CHD", "CIEN", "CI", "CINF", "CTAS", "CSCO", "C", "CFG", "CLX",
    "CME", "CMS", "KO", "CTSH", "COHR", "COIN", "CL", "CMCSA", "FIX", "CAG", "COP", "ED",
    "STZ", "CEG", "COO", "CPRT", "GLW", "CPAY", "CTVA", "CSGP", "COST", "CTRA", "CRH", "CRWD",
    "CCI", "CSX", "CMI", "CVS", "DHR", "DRI", "DDOG", "DVA", "DECK", "DE", "DELL", "DAL",
    "DVN", "DXCM", "FANG", "DLR", "DG", "DLTR", "D", "DPZ", "DASH", "DOV", "DOW", "DHI",
    "DTE", "DUK", "DD", "ETN", "EBAY", "SATS", "ECL", "EIX", "EW", "EA", "ELV", "EME", "EMR",
    "ETR", "EOG", "EPAM", "EQT", "EFX", "EQIX", "EQR", "ERIE", "ESS", "EL", "EG", "EVRG",
    "ES", "EXC", "EXE", "EXPE", "EXPD", "EXR", "XOM", "FFIV", "FDS", "FICO", "FAST", "FRT",
    "FDX", "FIS", "FITB", "FSLR", "FE", "FISV", "F", "FTNT", "FTV", "FOXA", "FOX", "BEN",
    "FCX", "GRMN", "IT", "GE", "GEHC", "GEV", "GEN", "GNRC", "GD", "GIS", "GM", "GPC", "GILD",
    "GPN", "GL", "GDDY", "GS", "HAL", "HIG", "HAS", "HCA", "DOC", "HSIC", "HSY", "HPE", "HLT",
    "HOLX", "HD", "HON", "HRL", "HST", "HWM", "HPQ", "HUBB", "HUM", "HBAN", "HII", "IBM",
    "IEX", "IDXX", "ITW", "INCY", "IR", "PODD", "INTC", "IBKR", "ICE", "IFF", "IP", "INTU",
    "ISRG", "IVZ", "INVH", "IQV", "IRM", "JBHT", "JBL", "JKHY", "J", "JNJ", "JCI", "JPM",
    "KVUE", "KDP", "KEY", "KEYS", "KMB", "KIM", "KMI", "KKR", "KLAC", "KHC", "KR", "LHX",
    "LH", "LRCX", "LVS", "LDOS", "LEN", "LII", "LLY", "LIN", "LYV", "LMT", "L", "LOW", "LULU",
    "LITE", "LYB", "MTB", "MPC", "MAR", "MRSH", "MLM", "MAS", "MA", "MKC", "MCD", "MCK", "MDT",
    "MRK", "META", "MET", "MTD", "MGM", "MCHP", "MU", "MSFT", "MAA", "MRNA", "TAP", "MDLZ",
    "MPWR", "MNST", "MCO", "MS", "MOS", "MSI", "MSCI", "NDAQ", "NTAP", "NFLX", "NEM", "NWSA",
    "NWS", "NEE", "NKE", "NI", "NDSN", "NSC", "NTRS", "NOC", "NCLH", "NRG", "NUE", "NVDA",
    "NVR", "NXPI", "ORLY", "OXY", "ODFL", "OMC", "ON", "OKE", "ORCL", "OTIS", "PCAR", "PKG",
    "PLTR", "PANW", "PSKY", "PH", "PAYX", "PYPL", "PNR", "PEP", "PFE", "PCG", "PM", "PSX",
    "PNW", "PNC", "POOL", "PPG", "PPL", "PFG", "PG", "PGR", "PLD", "PRU", "PEG", "PTC", "PSA",
    "PHM", "PWR", "QCOM", "DGX", "Q", "RL", "RJF", "RTX", "O", "REG", "REGN", "RF", "RSG",
    "RMD", "RVTY", "HOOD", "ROK", "ROL", "ROP", "ROST", "RCL", "SPGI", "CRM", "SNDK", "SBAC",
    "SLB", "STX", "SRE", "NOW", "SHW", "SPG", "SWKS", "SJM", "SW", "SNA", "SOLV", "SO", "LUV",
    "SWK", "SBUX", "STT", "STLD", "STE", "SYK", "SMCI", "SYF", "SNPS", "SYY", "TMUS", "TROW",
    "TTWO", "TPR", "TRGP", "TGT", "TEL", "TDY", "TER", "TSLA", "TXN", "TPL", "TXT", "TMO",
    "TJX", "TKO", "TTD", "TSCO", "TT", "TDG", "TRV", "TRMB", "TFC", "TYL", "TSN", "USB",
    "UBER", "UDR", "ULTA", "UNP", "UAL", "UPS", "URI", "UNH", "UHS", "VLO", "VTR", "VLTO",
    "VRSN", "VRSK", "VZ", "VRTX", "VRT", "VTRS", "VICI", "V", "VST", "VMC", "WRB", "GWW",
    "WAB", "WMT", "DIS", "WBD", "WM", "WAT", "WEC", "WFC", "WELL", "WST", "WDC", "WY", "WSM",
    "WMB", "WTW", "WDAY", "WYNN", "XEL", "XYL", "YUM", "ZBRA", "ZBH", "ZTS",
];

pub struct YahooClient {
    client: Client,
    session: YahooSession,
    /// When true, fall back to multi-MB HTML scrape if quoteSummary fails. Off by default.
    html_fallback: bool,
}

impl YahooClient {
    pub fn new() -> io::Result<Self> {
        Self::with_options(false)
    }

    pub fn with_options(html_fallback: bool) -> io::Result<Self> {
        let client = Client::builder()
            .timeout(HTTP_TIMEOUT)
            .user_agent(USER_AGENT)
            .cookie_store(true)
            .gzip(true)
            .build()
            .map_err(io::Error::other)?;
        Ok(Self {
            client,
            session: YahooSession::new(),
            html_fallback,
        })
    }

    /// Fetch quote + analyst + fundamentals via quoteSummary JSON (primary).
    pub fn fetch_symbol(&self, symbol: &str) -> io::Result<FetchResult> {
        let display = symbol.trim().to_uppercase();
        let request_symbol = yahoo_request_symbol(&display);

        let mut result = match self.fetch_quote_summary_json(&request_symbol) {
            Ok(root) => parse_quote_summary(&root, &display),
            Err(e) => {
                if self.html_fallback {
                    match self.fetch_symbol_html(&display) {
                        Ok(r) => r,
                        Err(_) => {
                            return Err(e);
                        }
                    }
                } else {
                    // Soft-empty on total failure: chart/stooq may still fill price.
                    FetchResult {
                        symbol: display.clone(),
                        snapshot: None,
                        signal: None,
                        fundamentals: None,
                    }
                }
            }
        };

        if result.snapshot.is_none() {
            let fallback = self
                .fetch_candles(&display, "1d", "5m")
                .ok()
                .and_then(|c| c.last().map(|x| x.close_cents))
                .or_else(|| crate::stooq::fetch_quote_cents(&display));
            result = with_price_fallback(result, fallback, is_crypto(&display));
        }

        result = apply_asset_class_overrides(result, is_crypto(&display), etf_sector(&display));
        Ok(result)
    }

    fn fetch_quote_summary_json(&self, request_symbol: &str) -> io::Result<Value> {
        let once = || -> io::Result<Value> {
            let crumb = self.session.ensure_crumb(&self.client, USER_AGENT)?;
            let url = format!(
                "{QUOTE_SUMMARY_URL}{}?modules={}&crumb={}",
                urlencoding_minimal(request_symbol),
                urlencoding_minimal(QUOTE_SUMMARY_MODULES),
                urlencoding_minimal(&crumb),
            );
            let resp = self
                .client
                .get(&url)
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .send()
                .map_err(io::Error::other)?;
            let status = resp.status();
            let body = resp.text().map_err(io::Error::other)?;
            if !status.is_success() {
                return Err(io::Error::other(format!(
                    "HTTP {status} for quoteSummary {request_symbol}: {}",
                    body.chars().take(200).collect::<String>()
                )));
            }
            serde_json::from_str(&body).map_err(io::Error::other)
        };

        match once() {
            Ok(v) => Ok(v),
            Err(e) if is_auth_error(&e) => {
                self.session.clear();
                once()
            }
            Err(e) => Err(e),
        }
    }

    /// Legacy HTML scrape path (optional; Android default is off).
    fn fetch_symbol_html(&self, symbol: &str) -> io::Result<FetchResult> {
        let url = format!("{}{}", QUOTE_PAGE_URL, symbol);
        let body = self
            .client
            .get(&url)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Upgrade-Insecure-Requests", "1")
            .send()
            .and_then(|r| r.error_for_status())
            .and_then(|r| r.text())
            .map_err(io::Error::other)?;

        // Reuse the same field mapping by synthesizing a quoteSummary-shaped JSON
        // from embedded HTML blobs when present.
        let fd = extract_json(&body, FINANCIAL_DATA_MARKER);
        let stat = extract_json(&body, KEY_STATISTICS_MARKER);
        let rec = extract_json(&body, RECOMMENDATION_TREND_MARKER);
        let ap = extract_json(&body, ASSET_PROFILE_MARKER)
            .or_else(|| extract_json(&body, SUMMARY_PROFILE_MARKER))
            .or_else(|| extract_json(&body, QUOTE_TYPE_MARKER));
        let price_obj = extract_price_json(&body);
        let cal = extract_json(&body, CALENDAR_EVENTS_MARKER);

        let mut root = serde_json::Map::new();
        let mut qs = serde_json::Map::new();
        let mut result_obj = serde_json::Map::new();
        if let Some(v) = fd {
            result_obj.insert("financialData".into(), v);
        }
        if let Some(v) = stat {
            result_obj.insert("defaultKeyStatistics".into(), v);
        }
        if let Some(v) = rec {
            result_obj.insert("recommendationTrend".into(), v);
        }
        if let Some(v) = ap {
            result_obj.insert("assetProfile".into(), v);
        }
        if let Some(v) = price_obj {
            result_obj.insert("price".into(), v);
        }
        if let Some(v) = cal {
            result_obj.insert("calendarEvents".into(), v);
        }
        // Company name from meta when price.longName missing
        if !result_obj.contains_key("price") {
            if let Some(name) = parse_company_name(&body, symbol) {
                let mut price = serde_json::Map::new();
                price.insert("longName".into(), Value::String(name));
                result_obj.insert("price".into(), Value::Object(price));
            }
        } else if let Some(Value::Object(price)) = result_obj.get_mut("price") {
            if price.get("longName").is_none() {
                if let Some(name) = parse_company_name(&body, symbol) {
                    price.insert("longName".into(), Value::String(name));
                }
            }
        }
        qs.insert(
            "result".into(),
            Value::Array(vec![Value::Object(result_obj)]),
        );
        root.insert("quoteSummary".into(), Value::Object(qs));
        let mut result = parse_quote_summary(&Value::Object(root), symbol);
        result = apply_asset_class_overrides(result, is_crypto(symbol), etf_sector(symbol));
        Ok(result)
    }

    /// Yahoo `v1/finance/search` — EQUITY/ETF quotes only after parse.
    pub fn search_symbols(&self, query: &str, limit: usize) -> io::Result<Vec<YahooSearchQuote>> {
        let trimmed = query.trim();
        if trimmed.is_empty() {
            return Ok(Vec::new());
        }
        let quotes_count = limit.max(1);
        let url = format!(
            "{SEARCH_API_URL}?q={}&quotesCount={quotes_count}&newsCount=0",
            urlencoding_minimal(trimmed)
        );
        let body = self
            .client
            .get(&url)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .send()
            .and_then(|r| r.error_for_status())
            .and_then(|r| r.text())
            .map_err(io::Error::other)?;
        let root: Value = serde_json::from_str(&body).map_err(io::Error::other)?;
        Ok(parse_search_quotes(&root))
    }

    pub fn fetch_candles(
        &self,
        symbol: &str,
        range: &str,
        interval: &str,
    ) -> io::Result<Vec<HistoricalCandle>> {
        let url = format!(
            "{}{}?range={}&interval={}&includePrePost=false",
            CHART_API_URL, symbol, range, interval
        );
        let yahoo: io::Result<Vec<HistoricalCandle>> = self
            .client
            .get(&url)
            .header("Accept", "application/json")
            .header("Origin", "https://finance.yahoo.com")
            .header("Referer", "https://finance.yahoo.com/")
            .send()
            .and_then(|r| r.error_for_status())
            .and_then(|r| r.json::<Value>())
            .map_err(io::Error::other)
            .and_then(|resp| parse_candles(&resp));

        // Stooq fallback — only for daily resolution (Stooq has no intraday and
        // returning daily where weekly/monthly was asked would corrupt indicators).
        match yahoo {
            Ok(c) if !c.is_empty() => Ok(c),
            other => {
                if interval == "1d" {
                    if let Some(c) = crate::stooq::fetch_daily_candles(symbol) {
                        if !c.is_empty() {
                            return Ok(c);
                        }
                    }
                }
                other
            }
        }
    }
}

/// Minimal application/x-www-form-urlencoded for Yahoo search `q`.
fn urlencoding_minimal(value: &str) -> String {
    let mut out = String::with_capacity(value.len() * 3);
    for b in value.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char);
            }
            b' ' => out.push_str("%20"),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

// ── HTML extraction helpers ────────────────────────────────────────────────────

/// Find `marker` in `body`, then extract the JSON object that immediately follows it.
/// Yahoo escapes inner JSON with \", so we unescape before parsing.
fn extract_json(body: &str, marker: &str) -> Option<Value> {
    let pos = body.find(marker)?;
    extract_json_at(body, pos + marker.len())
}

fn extract_json_at(body: &str, start: usize) -> Option<Value> {
    let after = &body[start..];
    let brace = after.find('{')?;
    let obj_str = &after[brace..];
    // Try each closing brace as the candidate end of the JSON object.
    // This is the same O(n²) strategy as the Android app.
    for (i, _) in obj_str.match_indices('}') {
        let fragment = &obj_str[..=i];
        let decoded  = fragment.replace("\\\"", "\"").replace("\\'", "'");
        if let Ok(val) = serde_json::from_str::<Value>(&decoded) {
            return Some(val);
        }
    }
    None
}

/// The `price` block appears multiple times. Pick the first one that has `marketCap`.
fn extract_price_json(body: &str) -> Option<Value> {
    let mut search_start = 0;
    while let Some(pos) = body[search_start..].find(PRICE_MARKER) {
        let abs = search_start + pos + PRICE_MARKER.len();
        if let Some(val) = extract_json_at(body, abs) {
            if val.get("marketCap").is_some() {
                return Some(val);
            }
        }
        search_start = abs;
    }
    None
}

/// Parse company name from `<meta property="og:title" content="NAME (SYMBOL) …">`.
fn parse_company_name(body: &str, symbol: &str) -> Option<String> {
    let start = body.find(META_TITLE_MARKER)?;
    let content_start = start + META_TITLE_MARKER.len();
    let content_end   = body[content_start..].find('"')? + content_start;
    let meta_title    = &body[content_start..content_end];
    let pattern       = format!(" ({}) ", symbol);
    let name = meta_title.split(&pattern).next()?.trim();
    if name.is_empty() {
        return None;
    }
    Some(
        name.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">"),
    )
}

// ── Candle parsing ─────────────────────────────────────────────────────────────

fn parse_candles(resp: &Value) -> io::Result<Vec<HistoricalCandle>> {
    let result = resp
        .get("chart")
        .and_then(|c| c.get("result"))
        .and_then(|r| r.as_array())
        .and_then(|a| a.first())
        .ok_or_else(|| io::Error::other("no chart result"))?;

    let timestamps = result
        .get("timestamp")
        .and_then(|t| t.as_array())
        .ok_or_else(|| io::Error::other("no timestamps"))?;

    let quote = result
        .get("indicators")
        .and_then(|i| i.get("quote"))
        .and_then(|q| q.as_array())
        .and_then(|a| a.first())
        .ok_or_else(|| io::Error::other("no quote indicators"))?;

    let opens   = quote.get("open").and_then(|v| v.as_array()).cloned().unwrap_or_default();
    let highs   = quote.get("high").and_then(|v| v.as_array()).cloned().unwrap_or_default();
    let lows    = quote.get("low").and_then(|v| v.as_array()).cloned().unwrap_or_default();
    let closes  = quote.get("close").and_then(|v| v.as_array()).cloned().unwrap_or_default();
    let volumes = quote.get("volume").and_then(|v| v.as_array()).cloned().unwrap_or_default();

    let mut candles = Vec::new();
    for (i, ts) in timestamps.iter().enumerate() {
        let epoch       = ts.as_i64().unwrap_or(0);
        let close_cents = closes.get(i).and_then(|v| v.as_f64()).map(|p| (p * 100.0).round() as i64).unwrap_or(0);
        if close_cents <= 0 {
            continue;
        }
        candles.push(HistoricalCandle {
            epoch_seconds: epoch,
            open_cents:  opens.get(i).and_then(|v| v.as_f64()).map(|p| (p * 100.0).round() as i64).unwrap_or(close_cents),
            high_cents:  highs.get(i).and_then(|v| v.as_f64()).map(|p| (p * 100.0).round() as i64).unwrap_or(close_cents),
            low_cents:   lows.get(i).and_then(|v| v.as_f64()).map(|p| (p * 100.0).round() as i64).unwrap_or(close_cents),
            close_cents,
            volume:      volumes.get(i).and_then(|v| v.as_u64()).unwrap_or(0),
        });
    }

    Ok(candles)
}
