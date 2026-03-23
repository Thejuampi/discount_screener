use std::collections::HashMap;
use std::io;
use std::time::Duration;
use std::time::SystemTime;
use std::time::UNIX_EPOCH;

use discount_screener::AnalystOutcomeSample;
use discount_screener::ExternalValuationSignal;
use discount_screener::MarketSnapshot;
use discount_screener::build_analyst_score;
use reqwest::blocking::Client;
use serde::Deserialize;
use serde::de::DeserializeOwned;

const HTTP_TIMEOUT: Duration = Duration::from_secs(15);
const QUOTE_PAGE_URL: &str = "https://finance.yahoo.com/quote/";
const CHART_API_URL: &str = "https://query1.finance.yahoo.com/v8/finance/chart/";
const QUOTE_PAGE_USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";
const FINANCIAL_DATA_MARKER: &str = r#"\"financialData\":"#;
const DEFAULT_KEY_STATISTICS_MARKER: &str = r#"\"defaultKeyStatistics\":"#;
const RECOMMENDATION_TREND_MARKER: &str = r#"\"recommendationTrend\":"#;
const UPGRADE_DOWNGRADE_HISTORY_MARKER: &str = r#"\"upgradeDowngradeHistory\":"#;
const ANALYST_EVALUATION_HORIZON_DAYS: u32 = 90;
const SECONDS_PER_DAY: u64 = 24 * 60 * 60;
pub const DEFAULT_POLL_INTERVAL: Duration = Duration::from_secs(30);
pub const DEFAULT_LIVE_SYMBOLS: [&str; 8] = [
    "AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOG", "TSLA", "AMD",
];

pub struct MarketDataClient {
    client: Client,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LiveSymbolFeed {
    pub snapshot: MarketSnapshot,
    pub external_signal: Option<ExternalValuationSignal>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct HistoricalPricePoint {
    epoch_seconds: u64,
    close_cents: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct WeightedAnalystTarget {
    weighted_fair_value_cents: i64,
    scored_firm_count: u32,
}

impl MarketDataClient {
    pub fn new() -> io::Result<Self> {
        let client = Client::builder()
            .timeout(HTTP_TIMEOUT)
            .user_agent(QUOTE_PAGE_USER_AGENT)
            .build()
            .map_err(io::Error::other)?;
        Ok(Self { client })
    }

    pub fn fetch_symbol(&self, symbol: &str) -> io::Result<Option<LiveSymbolFeed>> {
        let url = format!("{QUOTE_PAGE_URL}{symbol}");
        let response = self
            .client
            .get(url)
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
            .map_err(io::Error::other)?;
        let body = response.text().map_err(io::Error::other)?;
        let Some(mut live_feed) = parse_quote_page(symbol, &body)? else {
            return Ok(None);
        };

        let Some(external_signal) = live_feed.external_signal.as_mut() else {
            return Ok(Some(live_feed));
        };

        let Some(upgrade_history) = parse_embedded_json_object::<YahooUpgradeDowngradeHistory>(
            &body,
            UPGRADE_DOWNGRADE_HISTORY_MARKER,
        )?
        else {
            return Ok(Some(live_feed));
        };

        let Some(oldest_epoch) = upgrade_history
            .history
            .iter()
            .map(|entry| entry.epoch_grade_date)
            .min()
        else {
            return Ok(Some(live_feed));
        };

        let price_history =
            self.fetch_price_history(symbol, oldest_epoch.saturating_sub(7 * SECONDS_PER_DAY))?;
        let now_epoch_seconds = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(io::Error::other)?
            .as_secs();

        if let Some(weighted_target) = compute_weighted_analyst_target(
            &upgrade_history.history,
            &price_history,
            ANALYST_EVALUATION_HORIZON_DAYS,
            now_epoch_seconds,
        ) {
            external_signal.weighted_fair_value_cents =
                Some(weighted_target.weighted_fair_value_cents);
            external_signal.weighted_analyst_count = Some(weighted_target.scored_firm_count);
        }

        Ok(Some(live_feed))
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
        let url = format!(
            "{CHART_API_URL}{symbol}?period1={period1_epoch_seconds}&period2={period2_epoch_seconds}&interval=1d&includePrePost=false"
        );
        let response = self
            .client
            .get(url)
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
            .map_err(io::Error::other)?;

        response
            .json::<YahooChartResponse>()
            .map_err(io::Error::other)
            .map(YahooChartResponse::price_history)
    }
}

pub fn default_live_symbols() -> Vec<String> {
    DEFAULT_LIVE_SYMBOLS
        .iter()
        .map(|symbol| symbol.to_string())
        .collect()
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
            profitable,
            market_price_cents,
            intrinsic_value_cents,
        },
        external_signal,
    }))
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
    let object_start =
        marker_index + marker.len() + body[marker_index + marker.len()..].find('{')?;
    let bytes = body.as_bytes();
    let mut depth = 0usize;

    for index in object_start..bytes.len() {
        match bytes[index] {
            b'{' => depth += 1,
            b'}' => {
                depth = depth.saturating_sub(1);
                if depth == 0 {
                    return Some(&body[object_start..=index]);
                }
            }
            _ => {}
        }
    }

    None
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
}

#[derive(Deserialize)]
struct YahooDefaultKeyStatistics {
    #[serde(rename = "trailingEps")]
    trailing_eps: Option<YahooRawValue<f64>>,
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
}

#[derive(Deserialize)]
struct YahooChartQuote {
    close: Option<Vec<Option<f64>>>,
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

        timestamps
            .into_iter()
            .zip(closes)
            .filter_map(|(timestamp, close)| {
                close
                    .and_then(dollars_to_cents)
                    .map(|close_cents| HistoricalPricePoint {
                        epoch_seconds: timestamp,
                        close_cents,
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

    for entries in history_by_firm.values_mut() {
        entries.sort_by(|left, right| right.epoch_grade_date.cmp(&left.epoch_grade_date));
        let Some(active_target_cents) = entries
            .iter()
            .find_map(|entry| entry.current_price_target.and_then(dollars_to_cents))
        else {
            continue;
        };

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

    Some(WeightedAnalystTarget {
        weighted_fair_value_cents: (weighted_target_sum / total_weight).round() as i64,
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
    use super::HistoricalPricePoint;
    use super::LiveSymbolFeed;
    use super::YahooUpgradeDowngradeEntry;
    use super::compute_weighted_analyst_target;
    use super::extract_embedded_json_object;
    use super::parse_quote_page;
    use discount_screener::ExternalValuationSignal;
    use discount_screener::MarketSnapshot;

    #[test]
    fn parses_a_quote_page_into_live_feed_events() {
        let body = r#"<!doctype html><html><head></head><body><script>
        window.__TEST__ = "{\"financialData\":{\"currentPrice\":{\"raw\":191.11},\"targetMeanPrice\":{\"raw\":225.50},\"targetMedianPrice\":{\"raw\":223.00},\"targetLowPrice\":{\"raw\":180.00},\"targetHighPrice\":{\"raw\":260.00},\"numberOfAnalystOpinions\":{\"raw\":42},\"recommendationMean\":{\"raw\":1.85}},\"defaultKeyStatistics\":{\"trailingEps\":{\"raw\":6.42}},\"recommendationTrend\":{\"trend\":[{\"period\":\"0m\",\"strongBuy\":20,\"buy\":10,\"hold\":8,\"sell\":3,\"strongSell\":1}]}}";
        </script></body></html>"#;

        assert_eq!(
            parse_quote_page("AAPL", body).expect("sample response should parse"),
            Some(LiveSymbolFeed {
                snapshot: MarketSnapshot {
                    symbol: "AAPL".to_string(),
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
            })
        );
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
            weighted_target
                .as_ref()
                .map(|target| target.scored_firm_count),
            Some(2)
        );
        assert!(
            weighted_target
                .as_ref()
                .map(|target| target.weighted_fair_value_cents > 12_000)
                .unwrap_or(false)
        );
        assert!(
            weighted_target
                .as_ref()
                .map(|target| target.weighted_fair_value_cents < 15_000)
                .unwrap_or(false)
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
