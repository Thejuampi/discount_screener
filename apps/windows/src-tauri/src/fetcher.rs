/// Yahoo Finance fetcher — same approach as the Android app:
/// scrapes finance.yahoo.com/quote/{symbol} and extracts embedded JSON blobs.
/// No API key, no crumb, no batching — but gets ALL data (price + analyst + fundamentals)
/// in a single request per symbol.
use std::io;
use std::time::Duration;

use reqwest::blocking::Client;
use serde_json::Value;

use crate::engine::{
    ExternalValuationSignal, FundamentalSnapshot, HistoricalCandle, MarketSnapshot,
};

const HTTP_TIMEOUT: Duration = Duration::from_secs(20);
const QUOTE_PAGE_URL: &str = "https://finance.yahoo.com/quote/";
const CHART_API_URL: &str = "https://query1.finance.yahoo.com/v8/finance/chart/";

const USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

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
}

/// One symbol's worth of ingestion-ready data
pub struct FetchResult {
    pub symbol: String,
    pub snapshot: Option<MarketSnapshot>,
    pub signal: Option<ExternalValuationSignal>,
    pub fundamentals: Option<FundamentalSnapshot>,
}

impl YahooClient {
    pub fn new() -> io::Result<Self> {
        let client = Client::builder()
            .timeout(HTTP_TIMEOUT)
            .user_agent(USER_AGENT)
            .cookie_store(true)
            .gzip(true)
            .build()
            .map_err(io::Error::other)?;
        Ok(Self { client })
    }

    /// Fetch all data for a single symbol by scraping the Yahoo Finance quote page —
    /// identical strategy to the Android app.
    pub fn fetch_symbol(&self, symbol: &str) -> io::Result<FetchResult> {
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

        let fd   = extract_json(&body, FINANCIAL_DATA_MARKER);
        let stat = extract_json(&body, KEY_STATISTICS_MARKER);
        let rec  = extract_json(&body, RECOMMENDATION_TREND_MARKER);
        // Sector/industry can live in assetProfile (stocks) or summaryProfile (ETFs/funds).
        // Some pages also expose quoteType which has sector for ETFs.
        let ap   = extract_json(&body, ASSET_PROFILE_MARKER)
            .or_else(|| extract_json(&body, SUMMARY_PROFILE_MARKER))
            .or_else(|| extract_json(&body, QUOTE_TYPE_MARKER));
        let price_obj = extract_price_json(&body); // special: multiple occurrences
        let cal  = extract_json(&body, CALENDAR_EVENTS_MARKER);

        // ── Market price (multi-source, Yahoo → chart API → Stooq) ───────────
        // Redundancy so Yahoo is no longer a single point of failure: if the
        // scraped price is missing we try Yahoo's chart API, then Stooq (keyless).
        let market_price_cents = fd
            .as_ref()
            .and_then(|f| raw_money(f, "currentPrice"))
            .or_else(|| {
                self.fetch_candles(symbol, "1d", "5m")
                    .ok()
                    .and_then(|c| c.last().map(|x| x.close_cents))
            })
            .or_else(|| crate::stooq::fetch_quote_cents(symbol));

        let market_price_cents = match market_price_cents {
            Some(p) => p,
            None => {
                return Ok(FetchResult {
                    symbol: symbol.to_string(),
                    snapshot: None,
                    signal: None,
                    fundamentals: None,
                })
            }
        };

        // ── Analyst targets ───────────────────────────────────────────────────
        let target_mean_cents   = fd.as_ref().and_then(|f| raw_money(f, "targetMeanPrice"));
        let target_median_cents = fd.as_ref().and_then(|f| raw_money(f, "targetMedianPrice"));
        let target_low_cents    = fd.as_ref().and_then(|f| raw_money(f, "targetLowPrice"));
        let target_high_cents   = fd.as_ref().and_then(|f| raw_money(f, "targetHighPrice"));

        // ── Profitability ─────────────────────────────────────────────────────
        // Crypto has no EPS concept — treat as always "profitable" for qualification flow.
        let trailing_eps = stat.as_ref().and_then(|s| raw_double(s, "trailingEps"));
        let profitable   = if is_crypto(symbol) {
            true
        } else {
            trailing_eps.map(|e| e > 0.0).unwrap_or(false)
        };

        // ── Company name ──────────────────────────────────────────────────────
        let company_name = parse_company_name(&body, symbol);

        // ── Snapshot (always built if we have a price) ────────────────────────
        // Previous close enables daily % change in the UI.
        let previous_close_cents = price_obj.as_ref()
            .and_then(|p| raw_money(p, "regularMarketPreviousClose"))
            .unwrap_or(0);
        // Next earnings date: calendarEvents.earnings.earningsDate is an array of
        // {raw: epoch}. Prefer the first date in the future; else the latest known.
        let next_earnings_epoch = cal.as_ref()
            .and_then(|c| c.get("earnings"))
            .and_then(|e| e.get("earningsDate"))
            .and_then(|d| d.as_array())
            .and_then(|arr| {
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_secs() as i64).unwrap_or(0);
                let epochs: Vec<i64> = arr.iter()
                    .filter_map(|d| d.get("raw").and_then(|v| v.as_i64()))
                    .collect();
                epochs.iter().filter(|&&e| e >= now).min().copied()
                    .or_else(|| epochs.iter().max().copied())
            });

        let snapshot = Some(MarketSnapshot {
            symbol: symbol.to_string(),
            company_name: company_name.clone(),
            profitable,
            market_price_cents,
            intrinsic_value_cents: target_mean_cents.unwrap_or(0),
            previous_close_cents,
            next_earnings_epoch,
        });

        // ── Recommendation trend (current month = period "0m") ────────────────
        let trend = rec
            .as_ref()
            .and_then(|r| r.get("trend"))
            .and_then(|t| t.as_array())
            .and_then(|arr| {
                arr.iter()
                    .find(|p| p.get("period").and_then(|v| v.as_str()) == Some("0m"))
                    .or_else(|| arr.first())
            })
            .cloned();

        let analyst_count = fd
            .as_ref()
            .and_then(|f| raw_int(f, "numberOfAnalystOpinions"))
            .or_else(|| {
                trend.as_ref().map(|t| {
                    let sum = ["strongBuy", "buy", "hold", "sell", "strongSell"]
                        .iter()
                        .map(|k| t.get(k).and_then(|v| v.as_i64()).unwrap_or(0))
                        .sum::<i64>();
                    sum as u32
                })
            });

        // ── Signal (only when we have an analyst target) ──────────────────────
        let signal = target_median_cents.or(target_mean_cents).map(|fair| {
            ExternalValuationSignal {
                symbol: symbol.to_string(),
                fair_value_cents: fair,
                age_seconds: 0,
                low_fair_value_cents:  target_low_cents,
                high_fair_value_cents: target_high_cents,
                analyst_opinion_count: analyst_count,
                recommendation_mean_hundredths: fd
                    .as_ref()
                    .and_then(|f| raw_double(f, "recommendationMean"))
                    .map(|r| (r * 100.0).round() as u16),
                strong_buy_count:  trend_count(&trend, "strongBuy"),
                buy_count:         trend_count(&trend, "buy"),
                hold_count:        trend_count(&trend, "hold"),
                sell_count:        trend_count(&trend, "sell"),
                strong_sell_count: trend_count(&trend, "strongSell"),
                weighted_fair_value_cents: None,
                weighted_analyst_count: None,
            }
        });

        // ── Fundamentals ──────────────────────────────────────────────────────
        let fundamentals = Some(FundamentalSnapshot {
            symbol: symbol.to_string(),
            sector_key: ap.as_ref().and_then(|a| a.get("sectorKey")).and_then(|v| v.as_str()).map(str::to_string),
            // Crypto: always "Cryptocurrency". ETFs: hardcoded mapping. Stocks: from assetProfile.
            sector_name: if is_crypto(symbol) {
                Some("Cryptocurrency".to_string())
            } else if let Some(s) = etf_sector(symbol) {
                Some(s.to_string())
            } else {
                ap.as_ref()
                    .and_then(|a| {
                        a.get("sectorDisp")
                            .or_else(|| a.get("sector"))
                            .or_else(|| a.get("category"))
                            .or_else(|| a.get("legalType"))
                    })
                    .and_then(|v| v.as_str())
                    .filter(|s| !s.is_empty())
                    .map(str::to_string)
            },
            industry_key: ap.as_ref().and_then(|a| a.get("industryKey")).and_then(|v| v.as_str()).map(str::to_string),
            industry_name: ap.as_ref()
                .and_then(|a| {
                    a.get("industryDisp")
                        .or_else(|| a.get("industry"))
                        .or_else(|| a.get("fundFamily"))
                })
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(str::to_string),
            market_cap_dollars: price_obj.as_ref().and_then(|p| raw_double(p, "marketCap")).map(|v| v as u64),
            shares_outstanding: stat.as_ref().and_then(|s| raw_double(s, "sharesOutstanding")).map(|v| v as u64),
            trailing_pe_hundredths: stat.as_ref().and_then(|s| raw_double(s, "trailingPE")).map(|v| (v * 100.0) as u32),
            forward_pe_hundredths:  stat.as_ref().and_then(|s| raw_double(s, "forwardPE")).map(|v| (v * 100.0) as u32),
            price_to_book_hundredths: stat.as_ref().and_then(|s| raw_double(s, "priceToBook")).map(|v| (v * 100.0) as u32),
            return_on_equity_bps: fd.as_ref().and_then(|f| raw_double(f, "returnOnEquity")).map(|v| (v * 10_000.0) as i32),
            ebitda_dollars: fd.as_ref().and_then(|f| raw_double(f, "ebitda")).map(|v| v as i64),
            enterprise_value_dollars: stat.as_ref().and_then(|s| raw_double(s, "enterpriseValue")).map(|v| v as i64),
            enterprise_to_ebitda_hundredths: stat.as_ref().and_then(|s| raw_double(s, "enterpriseToEbitda")).map(|v| (v * 100.0) as i32),
            total_debt_dollars: fd.as_ref().and_then(|f| raw_double(f, "totalDebt")).map(|v| v as i64),
            total_cash_dollars: fd.as_ref().and_then(|f| raw_double(f, "totalCash")).map(|v| v as i64),
            debt_to_equity_hundredths: fd.as_ref().and_then(|f| raw_double(f, "debtToEquity")).map(|v| (v * 100.0) as i32),
            free_cash_flow_dollars: fd.as_ref().and_then(|f| raw_double(f, "freeCashflow")).map(|v| v as i64),
            operating_cash_flow_dollars: fd.as_ref().and_then(|f| raw_double(f, "operatingCashflow")).map(|v| v as i64),
            beta_millis: stat.as_ref().and_then(|s| raw_double(s, "beta")).map(|v| (v * 1000.0) as i32),
            trailing_eps_cents: trailing_eps.map(|e| (e * 100.0).round() as i64),
            earnings_growth_bps: fd.as_ref().and_then(|f| raw_double(f, "earningsGrowth")).map(|v| (v * 10_000.0) as i32),
        });

        Ok(FetchResult {
            symbol: symbol.to_string(),
            snapshot,
            signal,
            fundamentals,
        })
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

// ── Value accessors ────────────────────────────────────────────────────────────

/// Yahoo wraps numeric values as `{"raw": X, "fmt": "..."}`.
fn raw_double(obj: &Value, field: &str) -> Option<f64> {
    obj.get(field)?.get("raw")?.as_f64()
}

fn raw_money(obj: &Value, field: &str) -> Option<i64> {
    let d = raw_double(obj, field)?;
    if !d.is_finite() || d <= 0.0 {
        return None;
    }
    Some((d * 100.0).round() as i64)
}

fn raw_int(obj: &Value, field: &str) -> Option<u32> {
    raw_double(obj, field).map(|v| v as u32)
}

fn trend_count(trend: &Option<Value>, key: &str) -> Option<u32> {
    trend.as_ref()?.get(key)?.as_u64().map(|n| n as u32)
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
