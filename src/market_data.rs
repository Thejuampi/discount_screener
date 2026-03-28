use std::collections::HashMap;
use std::io;
use std::sync::Mutex;
use std::time::Duration;
use std::time::SystemTime;
use std::time::UNIX_EPOCH;

use discount_screener::AnalystOutcomeSample;
use discount_screener::ExternalValuationSignal;
use discount_screener::FundamentalSnapshot;
use discount_screener::MarketSnapshot;
use discount_screener::build_analyst_score;
use reqwest::Url;
use reqwest::blocking::Client;
use serde::Deserialize;
use serde::de::DeserializeOwned;
use serde_json::Value;

const HTTP_TIMEOUT: Duration = Duration::from_secs(15);
const QUOTE_PAGE_URL: &str = "https://finance.yahoo.com/quote/";
const CHART_API_URL: &str = "https://query1.finance.yahoo.com/v8/finance/chart/";
const FUNDAMENTALS_TIMESERIES_URL: &str =
    "https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/";
const QUOTE_PAGE_USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";
const PRICE_MARKER: &str = r#"\"price\":"#;
const FINANCIAL_DATA_MARKER: &str = r#"\"financialData\":"#;
const DEFAULT_KEY_STATISTICS_MARKER: &str = r#"\"defaultKeyStatistics\":"#;
const ASSET_PROFILE_MARKER: &str = r#"\"assetProfile\":"#;
const RECOMMENDATION_TREND_MARKER: &str = r#"\"recommendationTrend\":"#;
const UPGRADE_DOWNGRADE_HISTORY_MARKER: &str = r#"\"upgradeDowngradeHistory\":"#;
const ANALYST_EVALUATION_HORIZON_DAYS: u32 = 90;
const SECONDS_PER_DAY: u64 = 24 * 60 * 60;
pub const DEFAULT_POLL_INTERVAL: Duration = Duration::from_secs(30);
const WEIGHTED_TARGET_CACHE_TTL: Duration = Duration::from_secs(6 * 60 * 60);
pub const DEFAULT_LIVE_SYMBOLS: [&str; 503] = [
    "MMM", "AOS", "ABT", "ABBV", "ACN", "ADBE", "AMD", "AES", "AFL", "A", "APD", "ABNB", "AKAM",
    "ALB", "ARE", "ALGN", "ALLE", "LNT", "ALL", "GOOGL", "GOOG", "MO", "AMZN", "AMCR", "AEE",
    "AEP", "AXP", "AIG", "AMT", "AWK", "AMP", "AME", "AMGN", "APH", "ADI", "AON", "APA", "APO",
    "AAPL", "AMAT", "APP", "APTV", "ACGL", "ADM", "ARES", "ANET", "AJG", "AIZ", "T", "ATO", "ADSK",
    "ADP", "AZO", "AVB", "AVY", "AXON", "BKR", "BALL", "BAC", "BAX", "BDX", "BRK.B", "BBY", "TECH",
    "BIIB", "BLK", "BX", "XYZ", "BK", "BA", "BKNG", "BSX", "BMY", "AVGO", "BR", "BRO", "BF.B",
    "BLDR", "BG", "BXP", "CHRW", "CDNS", "CPT", "CPB", "COF", "CAH", "CCL", "CARR", "CVNA", "CAT",
    "CBOE", "CBRE", "CDW", "COR", "CNC", "CNP", "CF", "CRL", "SCHW", "CHTR", "CVX", "CMG", "CB",
    "CHD", "CIEN", "CI", "CINF", "CTAS", "CSCO", "C", "CFG", "CLX", "CME", "CMS", "KO", "CTSH",
    "COHR", "COIN", "CL", "CMCSA", "FIX", "CAG", "COP", "ED", "STZ", "CEG", "COO", "CPRT", "GLW",
    "CPAY", "CTVA", "CSGP", "COST", "CTRA", "CRH", "CRWD", "CCI", "CSX", "CMI", "CVS", "DHR",
    "DRI", "DDOG", "DVA", "DECK", "DE", "DELL", "DAL", "DVN", "DXCM", "FANG", "DLR", "DG", "DLTR",
    "D", "DPZ", "DASH", "DOV", "DOW", "DHI", "DTE", "DUK", "DD", "ETN", "EBAY", "SATS", "ECL",
    "EIX", "EW", "EA", "ELV", "EME", "EMR", "ETR", "EOG", "EPAM", "EQT", "EFX", "EQIX", "EQR",
    "ERIE", "ESS", "EL", "EG", "EVRG", "ES", "EXC", "EXE", "EXPE", "EXPD", "EXR", "XOM", "FFIV",
    "FDS", "FICO", "FAST", "FRT", "FDX", "FIS", "FITB", "FSLR", "FE", "FISV", "F", "FTNT", "FTV",
    "FOXA", "FOX", "BEN", "FCX", "GRMN", "IT", "GE", "GEHC", "GEV", "GEN", "GNRC", "GD", "GIS",
    "GM", "GPC", "GILD", "GPN", "GL", "GDDY", "GS", "HAL", "HIG", "HAS", "HCA", "DOC", "HSIC",
    "HSY", "HPE", "HLT", "HOLX", "HD", "HON", "HRL", "HST", "HWM", "HPQ", "HUBB", "HUM", "HBAN",
    "HII", "IBM", "IEX", "IDXX", "ITW", "INCY", "IR", "PODD", "INTC", "IBKR", "ICE", "IFF", "IP",
    "INTU", "ISRG", "IVZ", "INVH", "IQV", "IRM", "JBHT", "JBL", "JKHY", "J", "JNJ", "JCI", "JPM",
    "KVUE", "KDP", "KEY", "KEYS", "KMB", "KIM", "KMI", "KKR", "KLAC", "KHC", "KR", "LHX", "LH",
    "LRCX", "LVS", "LDOS", "LEN", "LII", "LLY", "LIN", "LYV", "LMT", "L", "LOW", "LULU", "LITE",
    "LYB", "MTB", "MPC", "MAR", "MRSH", "MLM", "MAS", "MA", "MKC", "MCD", "MCK", "MDT", "MRK",
    "META", "MET", "MTD", "MGM", "MCHP", "MU", "MSFT", "MAA", "MRNA", "TAP", "MDLZ", "MPWR",
    "MNST", "MCO", "MS", "MOS", "MSI", "MSCI", "NDAQ", "NTAP", "NFLX", "NEM", "NWSA", "NWS", "NEE",
    "NKE", "NI", "NDSN", "NSC", "NTRS", "NOC", "NCLH", "NRG", "NUE", "NVDA", "NVR", "NXPI", "ORLY",
    "OXY", "ODFL", "OMC", "ON", "OKE", "ORCL", "OTIS", "PCAR", "PKG", "PLTR", "PANW", "PSKY", "PH",
    "PAYX", "PYPL", "PNR", "PEP", "PFE", "PCG", "PM", "PSX", "PNW", "PNC", "POOL", "PPG", "PPL",
    "PFG", "PG", "PGR", "PLD", "PRU", "PEG", "PTC", "PSA", "PHM", "PWR", "QCOM", "DGX", "Q", "RL",
    "RJF", "RTX", "O", "REG", "REGN", "RF", "RSG", "RMD", "RVTY", "HOOD", "ROK", "ROL", "ROP",
    "ROST", "RCL", "SPGI", "CRM", "SNDK", "SBAC", "SLB", "STX", "SRE", "NOW", "SHW", "SPG", "SWKS",
    "SJM", "SW", "SNA", "SOLV", "SO", "LUV", "SWK", "SBUX", "STT", "STLD", "STE", "SYK", "SMCI",
    "SYF", "SNPS", "SYY", "TMUS", "TROW", "TTWO", "TPR", "TRGP", "TGT", "TEL", "TDY", "TER",
    "TSLA", "TXN", "TPL", "TXT", "TMO", "TJX", "TKO", "TTD", "TSCO", "TT", "TDG", "TRV", "TRMB",
    "TFC", "TYL", "TSN", "USB", "UBER", "UDR", "ULTA", "UNP", "UAL", "UPS", "URI", "UNH", "UHS",
    "VLO", "VTR", "VLTO", "VRSN", "VRSK", "VZ", "VRTX", "VRT", "VTRS", "VICI", "V", "VST", "VMC",
    "WRB", "GWW", "WAB", "WMT", "DIS", "WBD", "WM", "WAT", "WEC", "WFC", "WELL", "WST", "WDC",
    "WY", "WSM", "WMB", "WTW", "WDAY", "WYNN", "XEL", "XYL", "YUM", "ZBRA", "ZBH", "ZTS",
];

pub struct MarketDataClient {
    client: Client,
    weighted_target_cache: Mutex<HashMap<String, CachedWeightedTarget>>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LiveSymbolFeed {
    pub snapshot: MarketSnapshot,
    pub external_signal: Option<ExternalValuationSignal>,
    pub fundamentals: Option<FundamentalSnapshot>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ChartRange {
    Day,
    Week,
    Month,
    Year,
    FiveYears,
    TenYears,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HistoricalCandle {
    pub epoch_seconds: u64,
    pub open_cents: i64,
    pub high_cents: i64,
    pub low_cents: i64,
    pub close_cents: i64,
    pub volume: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct AnnualReportedValue {
    pub as_of_date: String,
    pub value: f64,
}

#[derive(Clone, Debug, PartialEq, Default)]
pub struct FundamentalTimeseries {
    pub free_cash_flow: Vec<AnnualReportedValue>,
    pub operating_cash_flow: Vec<AnnualReportedValue>,
    pub capital_expenditure: Vec<AnnualReportedValue>,
    pub diluted_average_shares: Vec<AnnualReportedValue>,
    pub interest_expense: Vec<AnnualReportedValue>,
    pub pretax_income: Vec<AnnualReportedValue>,
    pub tax_rate_for_calcs: Vec<AnnualReportedValue>,
    pub net_income: Vec<AnnualReportedValue>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct HistoricalPricePoint {
    epoch_seconds: u64,
    close_cents: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct ChartRangeSpec {
    range: &'static str,
    interval: &'static str,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct WeightedAnalystTarget {
    weighted_fair_value_cents: i64,
    scored_firm_count: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct CachedWeightedTarget {
    fetched_at_epoch_seconds: u64,
    weighted_target: WeightedAnalystTarget,
}

impl MarketDataClient {
    pub fn new() -> io::Result<Self> {
        let client = Client::builder()
            .timeout(HTTP_TIMEOUT)
            .user_agent(QUOTE_PAGE_USER_AGENT)
            .build()
            .map_err(io::Error::other)?;
        Ok(Self {
            client,
            weighted_target_cache: Mutex::new(HashMap::new()),
        })
    }

    pub fn fetch_symbol_with_options(
        &self,
        symbol: &str,
        refresh_weighted_target: bool,
    ) -> io::Result<Option<LiveSymbolFeed>> {
        let response = self
            .client
            .get(quote_page_url(symbol)?)
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
            .map_err(io::Error::other)?;

        let body = response.text().map_err(io::Error::other)?;
        let Some(mut live_feed) = parse_quote_page(symbol, &body)? else {
            return Ok(None);
        };

        if let Some(external_signal) = live_feed.external_signal.as_mut() {
            let now_epoch_seconds = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_err(io::Error::other)?
                .as_secs();

            if !refresh_weighted_target {
                self.apply_cached_weighted_target(symbol, external_signal);
                return Ok(Some(live_feed));
            }

            if self.apply_fresh_cached_weighted_target(symbol, external_signal, now_epoch_seconds) {
                return Ok(Some(live_feed));
            }

            populate_weighted_target(
                &body,
                external_signal,
                ANALYST_EVALUATION_HORIZON_DAYS,
                now_epoch_seconds,
                |period1_epoch_seconds| self.fetch_price_history(symbol, period1_epoch_seconds),
            )?;

            if let (Some(weighted_fair_value_cents), Some(weighted_analyst_count)) = (
                external_signal.weighted_fair_value_cents,
                external_signal.weighted_analyst_count,
            ) {
                self.cache_weighted_target(
                    symbol,
                    CachedWeightedTarget {
                        fetched_at_epoch_seconds: now_epoch_seconds,
                        weighted_target: WeightedAnalystTarget {
                            weighted_fair_value_cents,
                            scored_firm_count: weighted_analyst_count,
                        },
                    },
                );
            }
        }

        Ok(Some(live_feed))
    }

    pub fn fetch_historical_candles(
        &self,
        symbol: &str,
        range: ChartRange,
    ) -> io::Result<Vec<HistoricalCandle>> {
        let spec = chart_range_spec(range);
        let response = self
            .client
            .get(chart_range_api_url(symbol, spec.range, spec.interval)?)
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
            .map_err(io::Error::other)?;

        response
            .json::<YahooChartResponse>()
            .map_err(io::Error::other)
            .map(YahooChartResponse::historical_candles)
    }

    pub fn fetch_fundamental_timeseries(&self, symbol: &str) -> io::Result<FundamentalTimeseries> {
        let response = self
            .client
            .get(fundamentals_timeseries_url(symbol)?)
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
            .map_err(io::Error::other)?;

        let body = response.text().map_err(io::Error::other)?;
        let root = serde_json::from_str::<Value>(&body).map_err(io::Error::other)?;

        Ok(FundamentalTimeseries {
            free_cash_flow: parse_timeseries_metric(&root, "annualFreeCashFlow"),
            operating_cash_flow: parse_timeseries_metric(&root, "annualOperatingCashFlow"),
            capital_expenditure: parse_timeseries_metric(&root, "annualCapitalExpenditure"),
            diluted_average_shares: parse_timeseries_metric(&root, "annualDilutedAverageShares"),
            interest_expense: parse_timeseries_metric(&root, "annualInterestExpense"),
            pretax_income: parse_timeseries_metric(&root, "annualPretaxIncome"),
            tax_rate_for_calcs: parse_timeseries_metric(&root, "annualTaxRateForCalcs"),
            net_income: parse_timeseries_metric(&root, "annualNetIncome"),
        })
    }

    fn fetch_price_history(
        &self,
        symbol: &str,
        period1_epoch_seconds: u64,
    ) -> io::Result<Vec<HistoricalPricePoint>> {
        let period2_epoch_seconds = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(io::Error::other)?
            .as_secs();
        let response = self
            .client
            .get(chart_api_url(
                symbol,
                period1_epoch_seconds,
                period2_epoch_seconds,
            )?)
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
            .map_err(io::Error::other)?;

        response
            .json::<YahooChartResponse>()
            .map_err(io::Error::other)
            .map(YahooChartResponse::price_history)
    }

    fn apply_cached_weighted_target(
        &self,
        symbol: &str,
        external_signal: &mut ExternalValuationSignal,
    ) -> bool {
        let cached_weighted_target = self
            .weighted_target_cache
            .lock()
            .ok()
            .and_then(|cache| cache.get(symbol).cloned());

        if let Some(cached_weighted_target) = cached_weighted_target {
            external_signal.weighted_fair_value_cents = Some(
                cached_weighted_target
                    .weighted_target
                    .weighted_fair_value_cents,
            );
            external_signal.weighted_analyst_count =
                Some(cached_weighted_target.weighted_target.scored_firm_count);
            sanitize_weighted_target(external_signal);
            return true;
        }

        false
    }

    fn apply_fresh_cached_weighted_target(
        &self,
        symbol: &str,
        external_signal: &mut ExternalValuationSignal,
        now_epoch_seconds: u64,
    ) -> bool {
        let cached_weighted_target = self
            .weighted_target_cache
            .lock()
            .ok()
            .and_then(|cache| cache.get(symbol).cloned())
            .filter(|cached_weighted_target| {
                now_epoch_seconds.saturating_sub(cached_weighted_target.fetched_at_epoch_seconds)
                    < WEIGHTED_TARGET_CACHE_TTL.as_secs()
            });

        if let Some(cached_weighted_target) = cached_weighted_target {
            external_signal.weighted_fair_value_cents = Some(
                cached_weighted_target
                    .weighted_target
                    .weighted_fair_value_cents,
            );
            external_signal.weighted_analyst_count =
                Some(cached_weighted_target.weighted_target.scored_firm_count);
            sanitize_weighted_target(external_signal);
            return true;
        }

        false
    }

    fn cache_weighted_target(&self, symbol: &str, cached_weighted_target: CachedWeightedTarget) {
        if let Ok(mut cache) = self.weighted_target_cache.lock() {
            cache.insert(symbol.to_string(), cached_weighted_target);
        }
    }
}

pub fn default_live_symbols() -> Vec<String> {
    DEFAULT_LIVE_SYMBOLS
        .iter()
        .map(|symbol| symbol.to_string())
        .collect()
}

fn quote_page_url(symbol: &str) -> io::Result<Url> {
    let mut url = Url::parse(QUOTE_PAGE_URL).map_err(io::Error::other)?;
    url.path_segments_mut()
        .map_err(|_| io::Error::other("invalid quote page base url"))?
        .pop_if_empty()
        .push(symbol);
    Ok(url)
}

fn fundamentals_timeseries_url(symbol: &str) -> io::Result<Url> {
    let mut url = Url::parse(FUNDAMENTALS_TIMESERIES_URL).map_err(io::Error::other)?;
    url.path_segments_mut()
        .map_err(|_| io::Error::other("invalid fundamentals timeseries base url"))?
        .pop_if_empty()
        .push(symbol);
    url.query_pairs_mut()
        .append_pair(
            "type",
            "annualFreeCashFlow,annualOperatingCashFlow,annualCapitalExpenditure,annualDilutedAverageShares,annualInterestExpense,annualPretaxIncome,annualTaxRateForCalcs,annualNetIncome",
        )
        .append_pair("period1", "1262304000")
        .append_pair("period2", "2524608000");
    Ok(url)
}

fn chart_api_url(
    symbol: &str,
    period1_epoch_seconds: u64,
    period2_epoch_seconds: u64,
) -> io::Result<Url> {
    let mut url = Url::parse(CHART_API_URL).map_err(io::Error::other)?;
    url.path_segments_mut()
        .map_err(|_| io::Error::other("invalid chart api base url"))?
        .pop_if_empty()
        .push(symbol);
    url.query_pairs_mut()
        .append_pair("period1", &period1_epoch_seconds.to_string())
        .append_pair("period2", &period2_epoch_seconds.to_string())
        .append_pair("interval", "1d")
        .append_pair("includePrePost", "false");
    Ok(url)
}

fn chart_range_api_url(symbol: &str, range: &str, interval: &str) -> io::Result<Url> {
    let mut url = Url::parse(CHART_API_URL).map_err(io::Error::other)?;
    url.path_segments_mut()
        .map_err(|_| io::Error::other("invalid chart api base url"))?
        .pop_if_empty()
        .push(symbol);
    url.query_pairs_mut()
        .append_pair("range", range)
        .append_pair("interval", interval)
        .append_pair("includePrePost", "false");
    Ok(url)
}

fn chart_range_spec(range: ChartRange) -> ChartRangeSpec {
    match range {
        ChartRange::Day => ChartRangeSpec {
            range: "1d",
            interval: "5m",
        },
        ChartRange::Week => ChartRangeSpec {
            range: "5d",
            interval: "30m",
        },
        ChartRange::Month => ChartRangeSpec {
            range: "1mo",
            interval: "1d",
        },
        ChartRange::Year => ChartRangeSpec {
            range: "1y",
            interval: "1wk",
        },
        ChartRange::FiveYears => ChartRangeSpec {
            range: "5y",
            interval: "1mo",
        },
        ChartRange::TenYears => ChartRangeSpec {
            range: "10y",
            interval: "1mo",
        },
    }
}

fn parse_quote_page(symbol: &str, body: &str) -> io::Result<Option<LiveSymbolFeed>> {
    let Some(financial_data) =
        parse_embedded_json_object::<YahooFinancialData>(body, FINANCIAL_DATA_MARKER)?
    else {
        return Ok(None);
    };
    let Some(statistics) = parse_embedded_json_object::<YahooDefaultKeyStatistics>(
        body,
        DEFAULT_KEY_STATISTICS_MARKER,
    )?
    else {
        return Ok(None);
    };
    let recommendation_trend =
        parse_embedded_json_object::<YahooRecommendationTrend>(body, RECOMMENDATION_TREND_MARKER)?;
    let current_recommendation = recommendation_trend
        .as_ref()
        .and_then(YahooRecommendationTrend::current_period);
    let price = parse_quote_summary_price(body)?;
    let asset_profile =
        parse_embedded_json_object::<YahooAssetProfile>(body, ASSET_PROFILE_MARKER)?;

    let market_price_cents = money_from_field(financial_data.current_price.as_ref());
    let intrinsic_value_cents = money_from_field(financial_data.target_mean_price.as_ref());
    let low_fair_value_cents = money_from_field(financial_data.target_low_price.as_ref());
    let high_fair_value_cents = money_from_field(financial_data.target_high_price.as_ref());
    let analyst_opinion_count = financial_data
        .number_of_analyst_opinions
        .as_ref()
        .map(|value| value.raw)
        .or_else(|| {
            current_recommendation
                .as_ref()
                .map(|period| period.total_count())
        });
    let recommendation_mean_hundredths = financial_data
        .recommendation_mean
        .as_ref()
        .and_then(|value| decimal_to_hundredths(value.raw));
    let profitable = statistics
        .trailing_eps
        .as_ref()
        .map(|value| value.raw > 0.0);
    let company_name = parse_company_name(body, symbol);
    let fundamentals = build_fundamental_snapshot(
        symbol,
        &financial_data,
        &statistics,
        price.as_ref(),
        asset_profile.as_ref(),
    );

    let (Some(market_price_cents), Some(intrinsic_value_cents), Some(profitable)) =
        (market_price_cents, intrinsic_value_cents, profitable)
    else {
        return Ok(None);
    };

    let external_signal = financial_data
        .target_median_price
        .as_ref()
        .and_then(|value| dollars_to_cents(value.raw))
        .map(|fair_value_cents| ExternalValuationSignal {
            symbol: symbol.to_string(),
            fair_value_cents,
            age_seconds: 0,
            low_fair_value_cents,
            high_fair_value_cents,
            analyst_opinion_count,
            recommendation_mean_hundredths,
            strong_buy_count: current_recommendation
                .as_ref()
                .map(|period| period.strong_buy),
            buy_count: current_recommendation.as_ref().map(|period| period.buy),
            hold_count: current_recommendation.as_ref().map(|period| period.hold),
            sell_count: current_recommendation.as_ref().map(|period| period.sell),
            strong_sell_count: current_recommendation
                .as_ref()
                .map(|period| period.strong_sell),
            weighted_fair_value_cents: None,
            weighted_analyst_count: None,
        });

    Ok(Some(LiveSymbolFeed {
        snapshot: MarketSnapshot {
            symbol: symbol.to_string(),
            company_name,
            profitable,
            market_price_cents,
            intrinsic_value_cents,
        },
        external_signal,
        fundamentals,
    }))
}

fn build_fundamental_snapshot(
    symbol: &str,
    financial_data: &YahooFinancialData,
    statistics: &YahooDefaultKeyStatistics,
    price: Option<&YahooPrice>,
    asset_profile: Option<&YahooAssetProfile>,
) -> Option<FundamentalSnapshot> {
    let snapshot = FundamentalSnapshot {
        symbol: symbol.to_string(),
        sector_key: asset_profile.and_then(|profile| profile.sector_key.clone()),
        sector_name: asset_profile.and_then(|profile| {
            profile
                .sector_disp
                .clone()
                .or_else(|| profile.sector.clone())
        }),
        industry_key: asset_profile.and_then(|profile| profile.industry_key.clone()),
        industry_name: asset_profile.and_then(|profile| {
            profile
                .industry_disp
                .clone()
                .or_else(|| profile.industry.clone())
        }),
        market_cap_dollars: price
            .and_then(|price| price.market_cap.as_ref())
            .and_then(|value| non_negative_f64_to_u64(value.raw)),
        shares_outstanding: statistics
            .shares_outstanding
            .as_ref()
            .and_then(|value| non_negative_f64_to_u64(value.raw)),
        trailing_pe_hundredths: statistics
            .trailing_pe
            .as_ref()
            .and_then(|value| scale_non_negative_ratio_to_hundredths(value.raw)),
        forward_pe_hundredths: statistics
            .forward_pe
            .as_ref()
            .and_then(|value| scale_non_negative_ratio_to_hundredths(value.raw)),
        price_to_book_hundredths: statistics
            .price_to_book
            .as_ref()
            .and_then(|value| scale_non_negative_ratio_to_hundredths(value.raw)),
        return_on_equity_bps: financial_data
            .return_on_equity
            .as_ref()
            .and_then(|value| scale_ratio_to_bps(value.raw)),
        ebitda_dollars: financial_data
            .ebitda
            .as_ref()
            .and_then(|value| finite_f64_to_i64(value.raw)),
        enterprise_value_dollars: statistics
            .enterprise_value
            .as_ref()
            .and_then(|value| finite_f64_to_i64(value.raw)),
        enterprise_to_ebitda_hundredths: statistics
            .enterprise_to_ebitda
            .as_ref()
            .and_then(|value| scale_ratio_to_hundredths_signed(value.raw)),
        total_debt_dollars: financial_data
            .total_debt
            .as_ref()
            .and_then(|value| finite_f64_to_i64(value.raw)),
        total_cash_dollars: financial_data
            .total_cash
            .as_ref()
            .and_then(|value| finite_f64_to_i64(value.raw)),
        debt_to_equity_hundredths: financial_data
            .debt_to_equity
            .as_ref()
            .and_then(|value| scale_ratio_to_hundredths_signed(value.raw)),
        free_cash_flow_dollars: financial_data
            .free_cashflow
            .as_ref()
            .and_then(|value| finite_f64_to_i64(value.raw)),
        operating_cash_flow_dollars: financial_data
            .operating_cashflow
            .as_ref()
            .and_then(|value| finite_f64_to_i64(value.raw)),
        beta_millis: statistics
            .beta
            .as_ref()
            .and_then(|value| scale_ratio_to_millis(value.raw)),
        trailing_eps_cents: statistics
            .trailing_eps
            .as_ref()
            .and_then(|value| scale_money_to_cents_signed(value.raw)),
        earnings_growth_bps: financial_data
            .earnings_growth
            .as_ref()
            .and_then(|value| scale_ratio_to_bps(value.raw)),
    };

    snapshot.has_any_values().then_some(snapshot)
}

fn populate_weighted_target<F>(
    body: &str,
    external_signal: &mut ExternalValuationSignal,
    evaluation_horizon_days: u32,
    now_epoch_seconds: u64,
    mut fetch_price_history: F,
) -> io::Result<()>
where
    F: FnMut(u64) -> io::Result<Vec<HistoricalPricePoint>>,
{
    let Some(upgrade_history) = parse_embedded_json_object::<YahooUpgradeDowngradeHistory>(
        body,
        UPGRADE_DOWNGRADE_HISTORY_MARKER,
    )?
    else {
        return Ok(());
    };

    let Some(oldest_epoch) = upgrade_history
        .history
        .iter()
        .map(|entry| entry.epoch_grade_date)
        .min()
    else {
        return Ok(());
    };

    let price_history = match fetch_price_history(oldest_epoch.saturating_sub(7 * SECONDS_PER_DAY))
    {
        Ok(price_history) => price_history,
        Err(_) => return Ok(()),
    };

    if let Some(weighted_target) = compute_weighted_analyst_target(
        &upgrade_history.history,
        &price_history,
        evaluation_horizon_days,
        now_epoch_seconds,
    ) {
        external_signal.weighted_fair_value_cents = Some(weighted_target.weighted_fair_value_cents);
        external_signal.weighted_analyst_count = Some(weighted_target.scored_firm_count);
        sanitize_weighted_target(external_signal);
    }

    Ok(())
}

fn sanitize_weighted_target(signal: &mut ExternalValuationSignal) {
    let Some(mut weighted_fair_value_cents) = signal.weighted_fair_value_cents else {
        return;
    };

    if let (Some(low_fair_value_cents), Some(high_fair_value_cents)) =
        (signal.low_fair_value_cents, signal.high_fair_value_cents)
    {
        weighted_fair_value_cents = weighted_fair_value_cents.clamp(
            low_fair_value_cents.min(high_fair_value_cents),
            low_fair_value_cents.max(high_fair_value_cents),
        );
    }

    if weighted_fair_value_cents <= 0 {
        signal.weighted_fair_value_cents = None;
        signal.weighted_analyst_count = None;
        return;
    }

    signal.weighted_fair_value_cents = Some(weighted_fair_value_cents);
}

fn non_negative_f64_to_u64(value: f64) -> Option<u64> {
    if !value.is_finite() || value < 0.0 {
        return None;
    }

    Some(value.round() as u64)
}

fn finite_f64_to_i64(value: f64) -> Option<i64> {
    if !value.is_finite() {
        return None;
    }

    Some(value.round() as i64)
}

fn scale_money_to_cents_signed(value: f64) -> Option<i64> {
    if !value.is_finite() {
        return None;
    }

    Some((value * 100.0).round() as i64)
}

fn scale_non_negative_ratio_to_hundredths(value: f64) -> Option<u32> {
    if !value.is_finite() || value < 0.0 {
        return None;
    }

    Some((value * 100.0).round() as u32)
}

fn scale_ratio_to_hundredths_signed(value: f64) -> Option<i32> {
    if !value.is_finite() {
        return None;
    }

    Some((value * 100.0).round() as i32)
}

fn scale_ratio_to_bps(value: f64) -> Option<i32> {
    if !value.is_finite() {
        return None;
    }

    Some((value * 10_000.0).round() as i32)
}

fn scale_ratio_to_millis(value: f64) -> Option<i32> {
    if !value.is_finite() {
        return None;
    }

    Some((value * 1_000.0).round() as i32)
}

fn parse_timeseries_metric(root: &Value, metric_name: &str) -> Vec<AnnualReportedValue> {
    let Some(results) = root
        .get("timeseries")
        .and_then(|value| value.get("result"))
        .and_then(Value::as_array)
    else {
        return Vec::new();
    };

    let mut values = results
        .iter()
        .find_map(|entry| entry.get(metric_name).and_then(Value::as_array))
        .into_iter()
        .flatten()
        .filter_map(|entry| {
            Some(AnnualReportedValue {
                as_of_date: entry.get("asOfDate")?.as_str()?.to_string(),
                value: entry.get("reportedValue")?.get("raw")?.as_f64()?,
            })
        })
        .collect::<Vec<_>>();

    values.sort_by(|left, right| left.as_of_date.cmp(&right.as_of_date));
    values
}

fn parse_quote_summary_price(body: &str) -> io::Result<Option<YahooPrice>> {
    let mut search_start = 0usize;

    while let Some(relative_index) = body[search_start..].find(PRICE_MARKER) {
        let marker_index = search_start + relative_index;
        let Some(fragment) = extract_embedded_json_object_at(body, marker_index, PRICE_MARKER)
        else {
            search_start = marker_index + PRICE_MARKER.len();
            continue;
        };

        let decoded = fragment.replace(r#"\""#, r#"""#);
        let price = serde_json::from_str::<YahooPrice>(&decoded).map_err(io::Error::other)?;
        if price.market_cap.is_some() {
            return Ok(Some(price));
        }

        search_start = marker_index + PRICE_MARKER.len();
    }

    Ok(None)
}

fn parse_embedded_json_object<T>(body: &str, marker: &str) -> io::Result<Option<T>>
where
    T: DeserializeOwned,
{
    let Some(fragment) = extract_embedded_json_object(body, marker) else {
        return Ok(None);
    };

    let decoded = fragment.replace(r#"\""#, r#"""#);
    let value = serde_json::from_str(&decoded).map_err(io::Error::other)?;
    Ok(Some(value))
}

fn extract_embedded_json_object<'a>(body: &'a str, marker: &str) -> Option<&'a str> {
    let marker_index = body.find(marker)?;
    extract_embedded_json_object_at(body, marker_index, marker)
}

fn extract_embedded_json_object_at<'a>(
    body: &'a str,
    marker_index: usize,
    marker: &str,
) -> Option<&'a str> {
    let object_start =
        marker_index + marker.len() + body[marker_index + marker.len()..].find('{')?;
    let bytes = body.as_bytes();

    for index in object_start..bytes.len() {
        if bytes[index] != b'}' {
            continue;
        }

        let fragment = &body[object_start..=index];
        let decoded = fragment.replace(r#"\""#, r#"""#);
        if serde_json::from_str::<Value>(&decoded).is_ok() {
            return Some(fragment);
        }
    }

    None
}

fn parse_company_name(body: &str, symbol: &str) -> Option<String> {
    let marker = r#"<meta property="og:title" content=""#;
    let meta_title = extract_meta_content(body, marker)?;
    let pattern = format!(" ({symbol}) ");
    let company_name = meta_title.split_once(&pattern)?.0.trim();

    let company_name = html_unescape_basic(company_name);
    if company_name.is_empty() {
        None
    } else {
        Some(company_name)
    }
}

fn extract_meta_content(body: &str, marker: &str) -> Option<String> {
    let start = body.find(marker)? + marker.len();
    let end = body[start..].find('"')? + start;
    Some(body[start..end].to_string())
}

fn html_unescape_basic(text: &str) -> String {
    text.replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

fn money_from_field(field: Option<&YahooRawValue<f64>>) -> Option<i64> {
    field.and_then(|value| dollars_to_cents(value.raw))
}

fn dollars_to_cents(value: f64) -> Option<i64> {
    if !value.is_finite() || value <= 0.0 {
        return None;
    }

    Some((value * 100.0).round() as i64)
}

fn decimal_to_hundredths(value: f64) -> Option<u16> {
    if !value.is_finite() || value <= 0.0 {
        return None;
    }

    Some((value * 100.0).round() as u16)
}

#[derive(Deserialize)]
struct YahooFinancialData {
    #[serde(rename = "currentPrice")]
    current_price: Option<YahooRawValue<f64>>,
    #[serde(rename = "targetMeanPrice")]
    target_mean_price: Option<YahooRawValue<f64>>,
    #[serde(rename = "targetMedianPrice")]
    target_median_price: Option<YahooRawValue<f64>>,
    #[serde(rename = "targetLowPrice")]
    target_low_price: Option<YahooRawValue<f64>>,
    #[serde(rename = "targetHighPrice")]
    target_high_price: Option<YahooRawValue<f64>>,
    #[serde(rename = "numberOfAnalystOpinions")]
    number_of_analyst_opinions: Option<YahooRawValue<u32>>,
    #[serde(rename = "recommendationMean")]
    recommendation_mean: Option<YahooRawValue<f64>>,
    #[serde(rename = "returnOnEquity")]
    return_on_equity: Option<YahooRawValue<f64>>,
    ebitda: Option<YahooRawValue<f64>>,
    #[serde(rename = "totalDebt")]
    total_debt: Option<YahooRawValue<f64>>,
    #[serde(rename = "totalCash")]
    total_cash: Option<YahooRawValue<f64>>,
    #[serde(rename = "debtToEquity")]
    debt_to_equity: Option<YahooRawValue<f64>>,
    #[serde(rename = "freeCashflow")]
    free_cashflow: Option<YahooRawValue<f64>>,
    #[serde(rename = "operatingCashflow")]
    operating_cashflow: Option<YahooRawValue<f64>>,
    #[serde(rename = "earningsGrowth")]
    earnings_growth: Option<YahooRawValue<f64>>,
}

#[derive(Deserialize)]
struct YahooDefaultKeyStatistics {
    #[serde(rename = "sharesOutstanding")]
    shares_outstanding: Option<YahooRawValue<f64>>,
    #[serde(rename = "trailingPE")]
    trailing_pe: Option<YahooRawValue<f64>>,
    #[serde(rename = "forwardPE")]
    forward_pe: Option<YahooRawValue<f64>>,
    #[serde(rename = "priceToBook")]
    price_to_book: Option<YahooRawValue<f64>>,
    #[serde(rename = "enterpriseValue")]
    enterprise_value: Option<YahooRawValue<f64>>,
    #[serde(rename = "enterpriseToEbitda")]
    enterprise_to_ebitda: Option<YahooRawValue<f64>>,
    beta: Option<YahooRawValue<f64>>,
    #[serde(rename = "trailingEps")]
    trailing_eps: Option<YahooRawValue<f64>>,
}

#[derive(Deserialize)]
struct YahooPrice {
    #[serde(rename = "marketCap")]
    market_cap: Option<YahooRawValue<f64>>,
}

#[derive(Deserialize)]
struct YahooAssetProfile {
    #[serde(rename = "sectorKey")]
    sector_key: Option<String>,
    #[serde(rename = "sectorDisp")]
    sector_disp: Option<String>,
    sector: Option<String>,
    #[serde(rename = "industryKey")]
    industry_key: Option<String>,
    #[serde(rename = "industryDisp")]
    industry_disp: Option<String>,
    industry: Option<String>,
}

#[derive(Deserialize)]
struct YahooRawValue<T> {
    raw: T,
}

#[derive(Deserialize)]
struct YahooRecommendationTrend {
    trend: Vec<YahooRecommendationPeriod>,
}

impl YahooRecommendationTrend {
    fn current_period(&self) -> Option<&YahooRecommendationPeriod> {
        self.trend
            .iter()
            .find(|period| period.period == "0m")
            .or_else(|| self.trend.first())
    }
}

#[derive(Deserialize)]
struct YahooRecommendationPeriod {
    period: String,
    #[serde(rename = "strongBuy")]
    strong_buy: u32,
    buy: u32,
    hold: u32,
    sell: u32,
    #[serde(rename = "strongSell")]
    strong_sell: u32,
}

impl YahooRecommendationPeriod {
    fn total_count(&self) -> u32 {
        self.strong_buy + self.buy + self.hold + self.sell + self.strong_sell
    }
}

#[derive(Deserialize)]
struct YahooUpgradeDowngradeHistory {
    history: Vec<YahooUpgradeDowngradeEntry>,
}

#[derive(Clone, Debug, Deserialize, PartialEq)]
struct YahooUpgradeDowngradeEntry {
    #[serde(rename = "epochGradeDate")]
    epoch_grade_date: u64,
    firm: String,
    #[serde(rename = "toGrade")]
    to_grade: Option<String>,
    #[serde(rename = "fromGrade")]
    from_grade: Option<String>,
    action: Option<String>,
    #[serde(rename = "priceTargetAction")]
    price_target_action: Option<String>,
    #[serde(rename = "currentPriceTarget")]
    current_price_target: Option<f64>,
    #[serde(rename = "priorPriceTarget")]
    prior_price_target: Option<f64>,
}

#[derive(Deserialize)]
struct YahooChartResponse {
    chart: YahooChart,
}

#[derive(Deserialize)]
struct YahooChart {
    result: Option<Vec<YahooChartResult>>,
}

#[derive(Deserialize)]
struct YahooChartResult {
    timestamp: Option<Vec<u64>>,
    indicators: YahooChartIndicators,
}

#[derive(Deserialize)]
struct YahooChartIndicators {
    quote: Option<Vec<YahooChartQuote>>,
    adjclose: Option<Vec<YahooChartAdjustedClose>>,
}

#[derive(Deserialize)]
struct YahooChartQuote {
    open: Option<Vec<Option<f64>>>,
    high: Option<Vec<Option<f64>>>,
    low: Option<Vec<Option<f64>>>,
    close: Option<Vec<Option<f64>>>,
    volume: Option<Vec<Option<u64>>>,
}

#[derive(Deserialize)]
struct YahooChartAdjustedClose {
    adjclose: Option<Vec<Option<f64>>>,
}

impl YahooChartResponse {
    fn price_history(self) -> Vec<HistoricalPricePoint> {
        let Some(mut results) = self.chart.result else {
            return Vec::new();
        };
        let Some(result) = results.pop() else {
            return Vec::new();
        };
        let Some(timestamps) = result.timestamp else {
            return Vec::new();
        };
        let Some(mut quotes) = result.indicators.quote else {
            return Vec::new();
        };
        let Some(quote) = quotes.pop() else {
            return Vec::new();
        };
        let Some(closes) = quote.close else {
            return Vec::new();
        };
        let adjusted_closes = result
            .indicators
            .adjclose
            .and_then(|mut adjusted_quotes| adjusted_quotes.pop())
            .and_then(|adjusted_quote| adjusted_quote.adjclose);

        timestamps
            .into_iter()
            .enumerate()
            .filter_map(|(index, timestamp)| {
                adjusted_closes
                    .as_ref()
                    .and_then(|prices| prices.get(index).copied().flatten())
                    .or_else(|| closes.get(index).copied().flatten())
                    .and_then(dollars_to_cents)
                    .map(|close_cents| HistoricalPricePoint {
                        epoch_seconds: timestamp,
                        close_cents,
                    })
            })
            .collect()
    }

    fn historical_candles(self) -> Vec<HistoricalCandle> {
        let Some(mut results) = self.chart.result else {
            return Vec::new();
        };
        let Some(result) = results.pop() else {
            return Vec::new();
        };
        let Some(timestamps) = result.timestamp else {
            return Vec::new();
        };
        let Some(mut quotes) = result.indicators.quote else {
            return Vec::new();
        };
        let Some(quote) = quotes.pop() else {
            return Vec::new();
        };
        let (Some(opens), Some(highs), Some(lows), Some(closes)) =
            (quote.open, quote.high, quote.low, quote.close)
        else {
            return Vec::new();
        };
        let volumes = quote.volume.unwrap_or_default();

        timestamps
            .into_iter()
            .enumerate()
            .filter_map(|(index, epoch_seconds)| {
                let open_cents = opens
                    .get(index)
                    .copied()
                    .flatten()
                    .and_then(dollars_to_cents)?;
                let high_cents = highs
                    .get(index)
                    .copied()
                    .flatten()
                    .and_then(dollars_to_cents)?;
                let low_cents = lows
                    .get(index)
                    .copied()
                    .flatten()
                    .and_then(dollars_to_cents)?;
                let close_cents = closes
                    .get(index)
                    .copied()
                    .flatten()
                    .and_then(dollars_to_cents)?;
                Some(HistoricalCandle {
                    epoch_seconds,
                    open_cents,
                    high_cents,
                    low_cents,
                    close_cents,
                    volume: volumes.get(index).copied().flatten().unwrap_or(0),
                })
            })
            .collect()
    }
}

fn compute_weighted_analyst_target(
    history: &[YahooUpgradeDowngradeEntry],
    price_history: &[HistoricalPricePoint],
    evaluation_horizon_days: u32,
    now_epoch_seconds: u64,
) -> Option<WeightedAnalystTarget> {
    if history.is_empty() || price_history.is_empty() {
        return None;
    }

    let mut history_by_firm = HashMap::<&str, Vec<&YahooUpgradeDowngradeEntry>>::new();
    for entry in history {
        history_by_firm
            .entry(entry.firm.as_str())
            .or_default()
            .push(entry);
    }

    let mut weighted_target_sum = 0.0f32;
    let mut total_weight = 0.0f32;
    let mut scored_firm_count = 0u32;
    let mut min_target_cents: Option<i64> = None;
    let mut max_target_cents: Option<i64> = None;

    for entries in history_by_firm.values_mut() {
        entries.sort_by(|left, right| right.epoch_grade_date.cmp(&left.epoch_grade_date));
        let Some(active_target_cents) = entries
            .iter()
            .find_map(|entry| entry.current_price_target.and_then(dollars_to_cents))
        else {
            continue;
        };

        min_target_cents = Some(
            min_target_cents
                .map(|value| value.min(active_target_cents))
                .unwrap_or(active_target_cents),
        );
        max_target_cents = Some(
            max_target_cents
                .map(|value| value.max(active_target_cents))
                .unwrap_or(active_target_cents),
        );

        let mut samples = Vec::new();
        for entry in entries.iter().copied() {
            let Some(target_cents) = entry.current_price_target.and_then(dollars_to_cents) else {
                continue;
            };

            let maturity_epoch = entry
                .epoch_grade_date
                .saturating_add(evaluation_horizon_days as u64 * SECONDS_PER_DAY);
            if maturity_epoch > now_epoch_seconds {
                continue;
            }

            let Some(issue_close_cents) =
                closing_price_cents_on_or_after(price_history, entry.epoch_grade_date)
            else {
                continue;
            };
            let Some(realized_close_cents) =
                closing_price_cents_on_or_after(price_history, maturity_epoch)
            else {
                continue;
            };

            let target_error_bps = (((target_cents - realized_close_cents).abs() * 10_000)
                / realized_close_cents.max(1)) as u32;
            let target_direction = (target_cents - issue_close_cents).signum();
            let realized_direction = (realized_close_cents - issue_close_cents).signum();
            let age_days = ((now_epoch_seconds.saturating_sub(entry.epoch_grade_date))
                / SECONDS_PER_DAY)
                .min(u32::MAX as u64) as u32;

            samples.push(AnalystOutcomeSample {
                target_error_bps,
                direction_correct: target_direction == realized_direction,
                age_days,
            });
        }

        let Some(score) = build_analyst_score(&samples) else {
            continue;
        };

        weighted_target_sum += active_target_cents as f32 * score.value;
        total_weight += score.value;
        scored_firm_count += 1;
    }

    if scored_firm_count == 0 || total_weight <= 0.0 {
        return None;
    }

    let Some(min_target_cents) = min_target_cents else {
        return None;
    };
    let Some(max_target_cents) = max_target_cents else {
        return None;
    };

    let weighted_fair_value_cents = (weighted_target_sum / total_weight).round() as i64;

    Some(WeightedAnalystTarget {
        weighted_fair_value_cents: weighted_fair_value_cents
            .clamp(min_target_cents, max_target_cents),
        scored_firm_count,
    })
}

fn closing_price_cents_on_or_after(
    price_history: &[HistoricalPricePoint],
    epoch_seconds: u64,
) -> Option<i64> {
    price_history
        .iter()
        .filter(|point| point.epoch_seconds >= epoch_seconds)
        .min_by_key(|point| point.epoch_seconds)
        .map(|point| point.close_cents)
        .or_else(|| {
            price_history
                .iter()
                .filter(|point| point.epoch_seconds <= epoch_seconds)
                .max_by_key(|point| point.epoch_seconds)
                .map(|point| point.close_cents)
        })
}

#[cfg(test)]
mod tests {
    use super::AnnualReportedValue;
    use super::ChartRange;
    use super::HistoricalCandle;
    use super::HistoricalPricePoint;
    use super::LiveSymbolFeed;
    use super::YahooChart;
    use super::YahooChartAdjustedClose;
    use super::YahooChartIndicators;
    use super::YahooChartQuote;
    use super::YahooChartResponse;
    use super::YahooChartResult;
    use super::YahooUpgradeDowngradeEntry;
    use super::chart_api_url;
    use super::chart_range_api_url;
    use super::chart_range_spec;
    use super::compute_weighted_analyst_target;
    use super::default_live_symbols;
    use super::extract_embedded_json_object;
    use super::parse_quote_page;
    use super::parse_timeseries_metric;
    use super::populate_weighted_target;
    use super::quote_page_url;
    use discount_screener::ExternalValuationSignal;
    use discount_screener::MarketSnapshot;
    use serde_json::json;
    use std::collections::HashSet;
    use std::io;

    #[test]
    fn default_live_symbols_expand_the_built_in_universe() {
        let symbols = default_live_symbols();
        let unique_symbols = symbols.iter().collect::<HashSet<_>>();
        let has = |symbol: &str| symbols.iter().any(|value| value == symbol);

        assert!(
            symbols.len() == 503
                && unique_symbols.len() == 503
                && has("GOOGL")
                && has("GOOG")
                && has("BRK.B")
                && has("BF.B")
                && has("FOXA")
                && has("FOX")
                && has("NWSA")
                && has("NWS")
                && has("DAL")
                && has("UAL")
                && has("LUV")
                && !has("AAL")
        );
    }

    #[test]
    fn encodes_symbols_when_building_provider_urls() {
        assert_eq!(
            (
                quote_page_url("AAPL")
                    .expect("quote page url should build")
                    .to_string(),
                quote_page_url("BRK/B?")
                    .expect("quote page url should build")
                    .to_string(),
                quote_page_url("YPFD.BA")
                    .expect("quote page url should build")
                    .to_string(),
                chart_api_url("BRK/B?", 1, 2)
                    .expect("chart api url should build")
                    .to_string(),
            ),
            (
                "https://finance.yahoo.com/quote/AAPL".to_string(),
                "https://finance.yahoo.com/quote/BRK%2FB%3F".to_string(),
                "https://finance.yahoo.com/quote/YPFD.BA".to_string(),
                "https://query1.finance.yahoo.com/v8/finance/chart/BRK%2FB%3F?period1=1&period2=2&interval=1d&includePrePost=false".to_string(),
            )
        );
    }

    #[test]
    fn parses_a_quote_page_into_live_feed_events() {
        let body = r#"<!doctype html><html><head><meta property="og:title" content="Apple Inc. (AAPL) Stock Price, News, Quote &amp; History - Yahoo Finance"></head><body><script>
        window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":191.11},\"targetMeanPrice\":{\"raw\":225.50},\"targetMedianPrice\":{\"raw\":223.00},\"targetLowPrice\":{\"raw\":180.00},\"targetHighPrice\":{\"raw\":260.00},\"numberOfAnalystOpinions\":{\"raw\":42},\"recommendationMean\":{\"raw\":1.85}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":6.42}},\"recommendationTrend\":{\"trend\":[{\"period\":\"0m\",\"strongBuy\":20,\"buy\":10,\"hold\":8,\"sell\":3,\"strongSell\":1}]}}";
        </script></body></html>"#;

        assert_eq!(
            parse_quote_page("AAPL", body).expect("sample response should parse"),
            Some(LiveSymbolFeed {
                snapshot: MarketSnapshot {
                    symbol: "AAPL".to_string(),
                    company_name: Some("Apple Inc.".to_string()),
                    profitable: true,
                    market_price_cents: 19_111,
                    intrinsic_value_cents: 22_550,
                },
                external_signal: Some(ExternalValuationSignal {
                    symbol: "AAPL".to_string(),
                    fair_value_cents: 22_300,
                    age_seconds: 0,
                    low_fair_value_cents: Some(18_000),
                    high_fair_value_cents: Some(26_000),
                    analyst_opinion_count: Some(42),
                    recommendation_mean_hundredths: Some(185),
                    strong_buy_count: Some(20),
                    buy_count: Some(10),
                    hold_count: Some(8),
                    sell_count: Some(3),
                    strong_sell_count: Some(1),
                    weighted_fair_value_cents: None,
                    weighted_analyst_count: None,
                }),
                fundamentals: Some(discount_screener::FundamentalSnapshot {
                    symbol: "AAPL".to_string(),
                    sector_key: None,
                    sector_name: None,
                    industry_key: None,
                    industry_name: None,
                    market_cap_dollars: None,
                    shares_outstanding: None,
                    trailing_pe_hundredths: None,
                    forward_pe_hundredths: None,
                    price_to_book_hundredths: None,
                    return_on_equity_bps: None,
                    ebitda_dollars: None,
                    enterprise_value_dollars: None,
                    enterprise_to_ebitda_hundredths: None,
                    total_debt_dollars: None,
                    total_cash_dollars: None,
                    debt_to_equity_hundredths: None,
                    free_cash_flow_dollars: None,
                    operating_cash_flow_dollars: None,
                    beta_millis: None,
                    trailing_eps_cents: Some(642),
                    earnings_growth_bps: None,
                }),
            })
        );
    }

    #[test]
    fn parses_extended_fundamental_fields_from_quote_page() {
        let body = r#"<!doctype html><html><head><meta property="og:title" content="NVIDIA Corporation (NVDA) Stock Price, News, Quote &amp; History - Yahoo Finance"></head><body><script>
        window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":912.34},\"targetMeanPrice\":{\"raw\":1050.00},\"targetMedianPrice\":{\"raw\":1040.00},\"returnOnEquity\":{\"raw\":0.44},\"ebitda\":{\"raw\":145000000000},\"totalDebt\":{\"raw\":120000000000},\"totalCash\":{\"raw\":70000000000},\"debtToEquity\":{\"raw\":180.55},\"freeCashflow\":{\"raw\":99500000000},\"operatingCashflow\":{\"raw\":118000000000},\"earningsGrowth\":{\"raw\":0.153}},\"defaultKeyStatistics\":{\"sharesOutstanding\":{\"raw\":15550000000},\"trailingPE\":{\"raw\":31.27},\"forwardPE\":{\"raw\":28.10},\"priceToBook\":{\"raw\":42.65},\"enterpriseValue\":{\"raw\":3075000000000},\"enterpriseToEbitda\":{\"raw\":21.21},\"beta\":{\"raw\":1.24},\"trailingEps\":{\"raw\":12.34}},\"assetProfile\":{\"sectorKey\":\"technology\",\"sectorDisp\":\"Technology\",\"industryKey\":\"semiconductors\",\"industryDisp\":\"Semiconductors\"}}";
        </script></body></html>"#;

        let live_feed = parse_quote_page("NVDA", body)
            .expect("extended quote page should parse")
            .expect("quote page should produce a live feed");
        let fundamentals = live_feed
            .fundamentals
            .expect("extended quote page should expose fundamentals");

        assert_eq!(
            fundamentals,
            discount_screener::FundamentalSnapshot {
                symbol: "NVDA".to_string(),
                sector_key: Some("technology".to_string()),
                sector_name: Some("Technology".to_string()),
                industry_key: Some("semiconductors".to_string()),
                industry_name: Some("Semiconductors".to_string()),
                market_cap_dollars: None,
                shares_outstanding: Some(15_550_000_000),
                trailing_pe_hundredths: Some(3_127),
                forward_pe_hundredths: Some(2_810),
                price_to_book_hundredths: Some(4_265),
                return_on_equity_bps: Some(4_400),
                ebitda_dollars: Some(145_000_000_000),
                enterprise_value_dollars: Some(3_075_000_000_000),
                enterprise_to_ebitda_hundredths: Some(2_121),
                total_debt_dollars: Some(120_000_000_000),
                total_cash_dollars: Some(70_000_000_000),
                debt_to_equity_hundredths: Some(18_055),
                free_cash_flow_dollars: Some(99_500_000_000),
                operating_cash_flow_dollars: Some(118_000_000_000),
                beta_millis: Some(1_240),
                trailing_eps_cents: Some(1_234),
                earnings_growth_bps: Some(1_530),
            }
        );
    }

    #[test]
    fn prefers_quote_summary_price_block_when_earlier_price_objects_exist() {
        let body = r#"<!doctype html><html><head><meta property="og:title" content="A. O. Smith Corporation (AOS) Stock Price, News, Quote &amp; History - Yahoo Finance"></head><body><script>
        window.__TEST__ = "{\"screener\":{\"records\":[{\"ticker\":\"ADC\",\"price\":{\"raw\":74.41,\"fmt\":\"74.41\"}}]},\"financialData\":{\"currentPrice\":{\"raw\":64.42},\"targetMeanPrice\":{\"raw\":79.91},\"targetMedianPrice\":{\"raw\":77.00},\"returnOnEquity\":{\"raw\":0.29197},\"ebitda\":{\"raw\":813100032},\"totalDebt\":{\"raw\":203900000},\"totalCash\":{\"raw\":193200000},\"debtToEquity\":{\"raw\":10.974},\"freeCashflow\":{\"raw\":426162496},\"operatingCashflow\":{\"raw\":616800000},\"earningsGrowth\":{\"raw\":0.191}},\"defaultKeyStatistics\":{\"sharesOutstanding\":{\"raw\":138300000},\"trailingPE\":{\"raw\":15.16},\"forwardPE\":{\"raw\":13.84},\"priceToBook\":{\"raw\":4.63},\"enterpriseValue\":{\"raw\":8919335936},\"enterpriseToEbitda\":{\"raw\":10.97},\"beta\":{\"raw\":1.18},\"trailingEps\":{\"raw\":4.25}},\"pageViews\":{\"shortTermTrend\":\"DOWN\"},\"price\":{\"maxAge\":1,\"marketCap\":{\"raw\":8908636160},\"symbol\":\"AOS\"},\"assetProfile\":{\"sectorKey\":\"industrials\",\"sectorDisp\":\"Industrials\",\"industryKey\":\"specialtyindustrialmachinery\",\"industryDisp\":\"Specialty Industrial Machinery\"}}";
        </script></body></html>"#;

        let live_feed = parse_quote_page("AOS", body)
            .expect("quote page should parse")
            .expect("quote page should produce a live feed");
        let fundamentals = live_feed
            .fundamentals
            .expect("fundamentals should be available");

        assert_eq!(fundamentals.market_cap_dollars, Some(8_908_636_160));
    }

    #[test]
    fn parses_fundamental_timeseries_metrics_and_sorts_by_date() {
        let root = json!({
            "timeseries": {
                "result": [
                    {
                        "annualFreeCashFlow": [
                            {"asOfDate": "2024-12-31", "reportedValue": {"raw": 120.0}},
                            {"asOfDate": "2022-12-31", "reportedValue": {"raw": 80.0}},
                            {"asOfDate": "2023-12-31", "reportedValue": {"raw": 100.0}},
                            {"asOfDate": "2021-12-31", "reportedValue": {}}
                        ]
                    },
                    {
                        "annualOperatingCashFlow": [
                            {"asOfDate": "2023-12-31", "reportedValue": {"raw": 140.0}}
                        ]
                    }
                ]
            }
        });

        assert_eq!(
            parse_timeseries_metric(&root, "annualFreeCashFlow"),
            vec![
                AnnualReportedValue {
                    as_of_date: "2022-12-31".to_string(),
                    value: 80.0,
                },
                AnnualReportedValue {
                    as_of_date: "2023-12-31".to_string(),
                    value: 100.0,
                },
                AnnualReportedValue {
                    as_of_date: "2024-12-31".to_string(),
                    value: 120.0,
                },
            ]
        );
        assert_eq!(
            parse_timeseries_metric(&root, "annualOperatingCashFlow"),
            vec![AnnualReportedValue {
                as_of_date: "2023-12-31".to_string(),
                value: 140.0,
            }]
        );
        assert!(parse_timeseries_metric(&root, "annualPretaxIncome").is_empty());
    }

    #[test]
    fn skips_symbols_without_complete_quote_page_inputs() {
        let body = r#"<!doctype html><html><head></head><body><script>
        window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":191.11}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":6.42}}}";
        </script></body></html>"#;

        assert_eq!(
            parse_quote_page("AAPL", body).expect("sample response should parse"),
            None
        );
    }

    #[test]
    fn extracts_an_embedded_json_object_from_quote_html() {
        let body = r#"prefix \"financialData\":{\"currentPrice\":{\"raw\":191.11},\"targetMeanPrice\":{\"raw\":225.50}} suffix"#;

        assert_eq!(
            extract_embedded_json_object(body, r#"\"financialData\":"#),
            Some(r#"{\"currentPrice\":{\"raw\":191.11},\"targetMeanPrice\":{\"raw\":225.50}}"#),
        );
    }

    #[test]
    fn extracts_embedded_json_objects_with_braces_inside_strings() {
        let body = r#"prefix \"financialData\":{\"firm\":\"A{B}\",\"currentPrice\":{\"raw\":191.11}} suffix"#;

        assert_eq!(
            extract_embedded_json_object(body, r#"\"financialData\":"#),
            Some(r#"{\"firm\":\"A{B}\",\"currentPrice\":{\"raw\":191.11}}"#),
        );
    }

    #[test]
    fn prefers_adjusted_closes_when_available() {
        let price_history = YahooChartResponse {
            chart: YahooChart {
                result: Some(vec![YahooChartResult {
                    timestamp: Some(vec![1_700_000_000, 1_700_000_000 + 86_400]),
                    indicators: YahooChartIndicators {
                        quote: Some(vec![YahooChartQuote {
                            open: None,
                            high: None,
                            low: None,
                            close: Some(vec![Some(100.0), Some(50.0)]),
                            volume: None,
                        }]),
                        adjclose: Some(vec![YahooChartAdjustedClose {
                            adjclose: Some(vec![Some(100.0), Some(100.0)]),
                        }]),
                    },
                }]),
            },
        }
        .price_history();

        assert_eq!(
            price_history
                .into_iter()
                .map(|point| point.close_cents)
                .collect::<Vec<_>>(),
            vec![10_000, 10_000]
        );
    }

    #[test]
    fn chart_ranges_map_to_expected_yahoo_queries() {
        assert_eq!(
            (
                chart_range_spec(ChartRange::Day),
                chart_range_spec(ChartRange::Week),
                chart_range_spec(ChartRange::Month),
                chart_range_spec(ChartRange::Year),
                chart_range_spec(ChartRange::FiveYears),
                chart_range_spec(ChartRange::TenYears),
            ),
            (
                super::ChartRangeSpec {
                    range: "1d",
                    interval: "5m",
                },
                super::ChartRangeSpec {
                    range: "5d",
                    interval: "30m",
                },
                super::ChartRangeSpec {
                    range: "1mo",
                    interval: "1d",
                },
                super::ChartRangeSpec {
                    range: "1y",
                    interval: "1wk",
                },
                super::ChartRangeSpec {
                    range: "5y",
                    interval: "1mo",
                },
                super::ChartRangeSpec {
                    range: "10y",
                    interval: "1mo",
                },
            )
        );
        assert_eq!(
            chart_range_api_url("BRK/B?", "1y", "1wk")
                .expect("range chart url should build")
                .to_string(),
            "https://query1.finance.yahoo.com/v8/finance/chart/BRK%2FB%3F?range=1y&interval=1wk&includePrePost=false"
        );
    }

    #[test]
    fn historical_candles_parse_complete_ohlc_rows_and_drop_partial_rows() {
        let candles = YahooChartResponse {
            chart: YahooChart {
                result: Some(vec![YahooChartResult {
                    timestamp: Some(vec![1, 2, 3]),
                    indicators: YahooChartIndicators {
                        quote: Some(vec![YahooChartQuote {
                            open: Some(vec![Some(100.0), None, Some(120.0)]),
                            high: Some(vec![Some(110.0), Some(111.0), Some(130.0)]),
                            low: Some(vec![Some(90.0), Some(91.0), Some(118.0)]),
                            close: Some(vec![Some(105.0), Some(100.0), Some(125.0)]),
                            volume: Some(vec![Some(50), None, Some(75)]),
                        }]),
                        adjclose: None,
                    },
                }]),
            },
        }
        .historical_candles();

        assert_eq!(
            candles,
            vec![
                HistoricalCandle {
                    epoch_seconds: 1,
                    open_cents: 10_000,
                    high_cents: 11_000,
                    low_cents: 9_000,
                    close_cents: 10_500,
                    volume: 50,
                },
                HistoricalCandle {
                    epoch_seconds: 3,
                    open_cents: 12_000,
                    high_cents: 13_000,
                    low_cents: 11_800,
                    close_cents: 12_500,
                    volume: 75,
                },
            ]
        );
    }

    #[test]
    fn historical_candles_return_empty_when_quote_arrays_are_missing() {
        assert_eq!(
            YahooChartResponse {
                chart: YahooChart {
                    result: Some(vec![YahooChartResult {
                        timestamp: Some(vec![1]),
                        indicators: YahooChartIndicators {
                            quote: Some(vec![YahooChartQuote {
                                open: None,
                                high: None,
                                low: None,
                                close: Some(vec![Some(1.0)]),
                                volume: Some(vec![Some(1)]),
                            }]),
                            adjclose: None,
                        },
                    }]),
                },
            }
            .historical_candles(),
            Vec::<HistoricalCandle>::new()
        );
    }

    #[test]
    fn historical_candles_default_missing_volume_to_zero() {
        assert_eq!(
            YahooChartResponse {
                chart: YahooChart {
                    result: Some(vec![YahooChartResult {
                        timestamp: Some(vec![1]),
                        indicators: YahooChartIndicators {
                            quote: Some(vec![YahooChartQuote {
                                open: Some(vec![Some(10.0)]),
                                high: Some(vec![Some(12.0)]),
                                low: Some(vec![Some(9.0)]),
                                close: Some(vec![Some(11.0)]),
                                volume: None,
                            }]),
                            adjclose: None,
                        },
                    }]),
                },
            }
            .historical_candles(),
            vec![HistoricalCandle {
                epoch_seconds: 1,
                open_cents: 1_000,
                high_cents: 1_200,
                low_cents: 900,
                close_cents: 1_100,
                volume: 0,
            }]
        );
    }

    #[test]
    fn keeps_base_signal_when_price_history_fetch_fails() {
        let body = r#"prefix \"upgradeDowngradeHistory\":{\"history\":[{\"epochGradeDate\":1700000000,\"firm\":\"Accurate Capital\",\"currentPriceTarget\":120.0}]} suffix"#;
        let mut external_signal = ExternalValuationSignal {
            symbol: "AAPL".to_string(),
            fair_value_cents: 22_300,
            age_seconds: 0,
            low_fair_value_cents: None,
            high_fair_value_cents: None,
            analyst_opinion_count: None,
            recommendation_mean_hundredths: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            weighted_fair_value_cents: None,
            weighted_analyst_count: None,
        };

        assert_eq!(
            (
                populate_weighted_target(
                    body,
                    &mut external_signal,
                    90,
                    1_700_000_000 + 120 * 24 * 60 * 60,
                    |_| { Err(io::Error::other("provider timeout")) }
                )
                .is_ok(),
                external_signal.weighted_fair_value_cents,
            ),
            (true, None)
        );
    }

    #[test]
    fn computes_a_firm_weighted_target_from_history_and_realized_prices() {
        let weighted_target = compute_weighted_analyst_target(
            &[
                YahooUpgradeDowngradeEntry {
                    epoch_grade_date: 1_700_000_000,
                    firm: "Accurate Capital".to_string(),
                    to_grade: Some("Buy".to_string()),
                    from_grade: Some("Hold".to_string()),
                    action: Some("up".to_string()),
                    price_target_action: Some("Raises".to_string()),
                    current_price_target: Some(120.0),
                    prior_price_target: Some(110.0),
                },
                YahooUpgradeDowngradeEntry {
                    epoch_grade_date: 1_700_000_000,
                    firm: "Wildcard Research".to_string(),
                    to_grade: Some("Buy".to_string()),
                    from_grade: Some("Hold".to_string()),
                    action: Some("up".to_string()),
                    price_target_action: Some("Raises".to_string()),
                    current_price_target: Some(180.0),
                    prior_price_target: Some(130.0),
                },
            ],
            &[
                HistoricalPricePoint {
                    epoch_seconds: 1_700_000_000,
                    close_cents: 10_000,
                },
                HistoricalPricePoint {
                    epoch_seconds: 1_700_000_000 + 90 * 24 * 60 * 60,
                    close_cents: 11_800,
                },
            ],
            90,
            1_700_000_000 + 120 * 24 * 60 * 60,
        );

        assert_eq!(
            weighted_target.as_ref().map(|target| (
                target.scored_firm_count,
                (12_000..=18_000).contains(&target.weighted_fair_value_cents),
            )),
            Some((2, true))
        );
    }

    #[test]
    fn returns_none_when_no_firm_has_a_mature_observable_outcome() {
        assert_eq!(
            compute_weighted_analyst_target(
                &[YahooUpgradeDowngradeEntry {
                    epoch_grade_date: 1_700_000_000,
                    firm: "Fresh Research".to_string(),
                    to_grade: Some("Buy".to_string()),
                    from_grade: Some("Hold".to_string()),
                    action: Some("up".to_string()),
                    price_target_action: Some("Raises".to_string()),
                    current_price_target: Some(120.0),
                    prior_price_target: Some(110.0),
                }],
                &[HistoricalPricePoint {
                    epoch_seconds: 1_700_000_000,
                    close_cents: 10_000,
                }],
                90,
                1_700_000_000 + 30 * 24 * 60 * 60,
            ),
            None
        );
    }
}
