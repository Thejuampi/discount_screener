//! Pure parse of Yahoo `v10/finance/quoteSummary` JSON (Android port).
//! Network + crumb stay in `fetcher` / `yahoo_session`.

use chrono::NaiveDate;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::engine::{ExternalValuationSignal, FundamentalSnapshot, MarketSnapshot};

/// One symbol's worth of ingestion-ready data (shared by REST + legacy HTML paths).
#[derive(Debug, Clone)]
pub struct FetchResult {
    pub symbol: String,
    pub snapshot: Option<MarketSnapshot>,
    pub signal: Option<ExternalValuationSignal>,
    pub fundamentals: Option<FundamentalSnapshot>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForwardForecastProviderError {
    MissingPayload,
    MissingForwardPeriod,
    MissingForecastEndDate,
    InvalidForecastEndDate,
    MissingCurrency,
    CurrencyMismatch,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ForwardForecastEvidence {
    pub eps_low_cents: Option<i64>,
    pub eps_mean_cents: Option<i64>,
    pub eps_high_cents: Option<i64>,
    pub analyst_count: Option<i32>,
    pub revenue_growth_bps: Option<i32>,
    pub revenue_analyst_count: Option<i32>,
    pub earnings_growth_bps: Option<i32>,
    pub currency: String,
    pub revenue_currency: String,
    pub reporting_currency: String,
    pub observed_epoch_day: i64,
    pub forecast_period_end_epoch_day: i64,
    pub source_fingerprint: String,
}

pub fn parse_forward_forecast_evidence(
    root: &Value,
    display_symbol: &str,
    observed_epoch_day: i64,
) -> Result<ForwardForecastEvidence, ForwardForecastProviderError> {
    let result = root
        .pointer("/quoteSummary/result/0")
        .or_else(|| root.pointer("/payload/quoteSummary/result/0"))
        .ok_or(ForwardForecastProviderError::MissingPayload)?;
    let trend = result
        .pointer("/earningsTrend/trend")
        .and_then(Value::as_array)
        .ok_or(ForwardForecastProviderError::MissingPayload)?;
    let forward = trend
        .iter()
        .find(|row| row.get("period").and_then(Value::as_str) == Some("+1y"))
        .ok_or(ForwardForecastProviderError::MissingForwardPeriod)?;
    let end_date = forward
        .get("endDate")
        .and_then(Value::as_str)
        .ok_or(ForwardForecastProviderError::MissingForecastEndDate)?;
    let end_date = NaiveDate::parse_from_str(end_date, "%Y-%m-%d")
        .map_err(|_| ForwardForecastProviderError::InvalidForecastEndDate)?;
    let unix_epoch = NaiveDate::from_ymd_opt(1970, 1, 1).expect("valid Unix epoch");
    let forecast_period_end_epoch_day = end_date.signed_duration_since(unix_epoch).num_days();

    let earnings = forward
        .get("earningsEstimate")
        .ok_or(ForwardForecastProviderError::MissingPayload)?;
    let revenue = forward.get("revenueEstimate");
    let currency = earnings
        .get("earningsCurrency")
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .ok_or(ForwardForecastProviderError::MissingCurrency)?
        .trim()
        .to_ascii_uppercase();
    let revenue_currency = revenue
        .and_then(|value| value.get("revenueCurrency"))
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .ok_or(ForwardForecastProviderError::MissingCurrency)?
        .trim()
        .to_ascii_uppercase();
    if revenue_currency != currency {
        return Err(ForwardForecastProviderError::CurrencyMismatch);
    }
    let reporting_currency = result
        .pointer("/price/currency")
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .ok_or(ForwardForecastProviderError::MissingCurrency)?
        .trim()
        .to_ascii_uppercase();
    if reporting_currency != currency {
        return Err(ForwardForecastProviderError::CurrencyMismatch);
    }

    let source_payload =
        serde_json::to_string(forward).map_err(|_| ForwardForecastProviderError::MissingPayload)?;
    let source_hash = fnv1a64(source_payload.as_bytes());
    Ok(ForwardForecastEvidence {
        eps_low_cents: raw_scaled(earnings, "low", 100),
        eps_mean_cents: raw_scaled(earnings, "avg", 100),
        eps_high_cents: raw_scaled(earnings, "high", 100),
        analyst_count: raw_scaled(earnings, "numberOfAnalysts", 1)
            .and_then(|value| i32::try_from(value).ok()),
        revenue_growth_bps: revenue
            .and_then(|value| raw_scaled(value, "growth", 10_000))
            .and_then(|value| i32::try_from(value).ok()),
        revenue_analyst_count: revenue
            .and_then(|value| raw_scaled(value, "numberOfAnalysts", 1))
            .and_then(|value| i32::try_from(value).ok()),
        earnings_growth_bps: raw_scaled(forward, "growth", 10_000)
            .and_then(|value| i32::try_from(value).ok()),
        currency,
        revenue_currency,
        reporting_currency,
        observed_epoch_day,
        forecast_period_end_epoch_day,
        source_fingerprint: format!(
            "yahoo-earnings-trend/1|symbol={}|hash={source_hash:016x}",
            display_symbol.trim().to_ascii_uppercase()
        ),
    })
}

fn raw_scaled(obj: &Value, field: &str, scale: i64) -> Option<i64> {
    let value = raw_double(obj, field)?;
    if !value.is_finite() {
        return None;
    }
    let scaled = value * scale as f64;
    if scaled < i64::MIN as f64 || scaled > i64::MAX as f64 {
        return None;
    }
    Some(scaled.round() as i64)
}

fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf29ce484222325_u64;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

/// Modules requested by the Android client (+ calendarEvents for Windows earnings field).
pub const QUOTE_SUMMARY_MODULES: &str =
    "price,financialData,defaultKeyStatistics,assetProfile,recommendationTrend,calendarEvents";
pub const FORWARD_FORECAST_MODULES: &str = "earningsTrend,price";

/// Map share-class dots to Yahoo path form: `BRK.B` → `BRK-B`, keep exchange suffixes.
pub fn yahoo_request_symbol(symbol: &str) -> String {
    let normalized = symbol.trim().to_uppercase();
    let Some(dot) = normalized.rfind('.') else {
        return normalized;
    };
    if dot == 0 || dot == normalized.len() - 1 {
        return normalized;
    }
    let suffix = &normalized[dot + 1..];
    if suffix.len() == 1
        && suffix
            .chars()
            .next()
            .is_some_and(|c| c.is_ascii_alphabetic())
    {
        format!("{}-{}", &normalized[..dot], suffix)
    } else {
        normalized
    }
}

/// Parse a live/fixture quoteSummary payload into ingestion-ready data.
pub fn parse_quote_summary(root: &Value, display_symbol: &str) -> FetchResult {
    let result = root.pointer("/quoteSummary/result/0").cloned().or_else(|| {
        root.get("quoteSummary")
            .and_then(|qs| qs.get("result"))
            .and_then(|r| r.as_array())
            .and_then(|a| a.first())
            .cloned()
    });

    let Some(result) = result else {
        return FetchResult {
            symbol: display_symbol.to_string(),
            snapshot: None,
            signal: None,
            fundamentals: None,
        };
    };

    let financial_data = result.get("financialData");
    let statistics = result.get("defaultKeyStatistics");
    let recommendation_trend = result.get("recommendationTrend");
    let price = result.get("price");
    let asset_profile = result.get("assetProfile");
    let calendar = result.get("calendarEvents");

    let company_name = price
        .and_then(|p| {
            p.get("longName")
                .or_else(|| p.get("shortName"))
                .and_then(|v| v.as_str())
                .map(str::to_string)
        })
        .filter(|n| is_usable_name(n, display_symbol));

    let market_price_cents = financial_data
        .and_then(|f| raw_money(f, "currentPrice"))
        .or_else(|| price.and_then(|p| raw_money(p, "regularMarketPrice")));

    let target_mean_cents = financial_data.and_then(|f| raw_money(f, "targetMeanPrice"));
    let target_median_cents = financial_data.and_then(|f| raw_money(f, "targetMedianPrice"));
    let target_low_cents = financial_data.and_then(|f| raw_money(f, "targetLowPrice"));
    let target_high_cents = financial_data.and_then(|f| raw_money(f, "targetHighPrice"));

    let price_dollars = financial_data
        .and_then(|f| raw_double(f, "currentPrice"))
        .or_else(|| price.and_then(|p| raw_double(p, "regularMarketPrice")))
        .or_else(|| market_price_cents.map(|c| c as f64 / 100.0));
    let reported_market_cap_dollars = price.and_then(|p| raw_double(p, "marketCap"));
    let reported_shares_outstanding = statistics.and_then(|s| raw_double(s, "sharesOutstanding"));
    // Yahoo can omit sharesOutstanding while still returning market cap and
    // price. Deriving shares from those two reported quantities is a bounded
    // unit conversion, not a valuation anchor, and prevents otherwise valid
    // DCFs (MU/CRM-class) from being discarded for a transport omission.
    let shares_outstanding = reported_shares_outstanding
        .or_else(|| derive_shares_outstanding(reported_market_cap_dollars, price_dollars));

    let trailing_eps = statistics.and_then(|s| raw_double(s, "trailingEps"));
    // Crypto profitability is applied by the fetcher shell after parse.
    let profitable = trailing_eps.map(|e| e > 0.0).unwrap_or(false);

    let previous_close_cents = price
        .and_then(|p| raw_money(p, "regularMarketPreviousClose"))
        .unwrap_or(0);

    let next_earnings_epoch = calendar
        .and_then(|c| c.get("earnings"))
        .and_then(|e| e.get("earningsDate"))
        .and_then(|d| d.as_array())
        .and_then(|arr| {
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0);
            let epochs: Vec<i64> = arr
                .iter()
                .filter_map(|d| d.get("raw").and_then(|v| v.as_i64()))
                .collect();
            epochs
                .iter()
                .filter(|&&e| e >= now)
                .min()
                .copied()
                .or_else(|| epochs.iter().max().copied())
        });

    let snapshot = market_price_cents.map(|market_price_cents| MarketSnapshot {
        symbol: display_symbol.to_string(),
        company_name: company_name.clone(),
        profitable,
        market_price_cents,
        intrinsic_value_cents: target_mean_cents.unwrap_or(0),
        previous_close_cents,
        next_earnings_epoch,
    });

    let trend = recommendation_trend
        .and_then(|r| r.get("trend"))
        .and_then(|t| t.as_array())
        .and_then(|arr| {
            arr.iter()
                .find(|p| p.get("period").and_then(|v| v.as_str()) == Some("0m"))
                .or_else(|| arr.first())
        })
        .cloned();

    let analyst_count = financial_data
        .and_then(|f| raw_int(f, "numberOfAnalystOpinions"))
        .or_else(|| {
            trend.as_ref().map(|t| {
                ["strongBuy", "buy", "hold", "sell", "strongSell"]
                    .iter()
                    .map(|k| t.get(*k).and_then(|v| v.as_i64()).unwrap_or(0))
                    .sum::<i64>() as u32
            })
        });

    let signal = target_median_cents
        .or(target_mean_cents)
        .map(|fair| ExternalValuationSignal {
            symbol: display_symbol.to_string(),
            fair_value_cents: fair,
            age_seconds: 0,
            low_fair_value_cents: target_low_cents,
            high_fair_value_cents: target_high_cents,
            analyst_opinion_count: analyst_count,
            recommendation_mean_hundredths: financial_data
                .and_then(|f| raw_double(f, "recommendationMean"))
                .map(|r| (r * 100.0).round() as u16),
            strong_buy_count: trend_count(&trend, "strongBuy"),
            buy_count: trend_count(&trend, "buy"),
            hold_count: trend_count(&trend, "hold"),
            sell_count: trend_count(&trend, "sell"),
            strong_sell_count: trend_count(&trend, "strongSell"),
            weighted_fair_value_cents: None,
            weighted_analyst_count: None,
        });

    let sector_name = asset_profile
        .and_then(|a| {
            a.get("sectorDisp")
                .or_else(|| a.get("sector"))
                .or_else(|| a.get("category"))
                .or_else(|| a.get("legalType"))
        })
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .map(str::to_string);

    let fundamentals = Some(FundamentalSnapshot {
        symbol: display_symbol.to_string(),
        sector_key: asset_profile
            .and_then(|a| a.get("sectorKey"))
            .and_then(|v| v.as_str())
            .map(str::to_string),
        sector_name,
        industry_key: asset_profile
            .and_then(|a| a.get("industryKey"))
            .and_then(|v| v.as_str())
            .map(str::to_string),
        industry_name: asset_profile
            .and_then(|a| {
                a.get("industryDisp")
                    .or_else(|| a.get("industry"))
                    .or_else(|| a.get("fundFamily"))
            })
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .map(str::to_string),
        market_cap_dollars: resolve_market_cap_dollars(
            reported_market_cap_dollars,
            shares_outstanding,
            price_dollars,
        ),
        shares_outstanding: shares_outstanding.map(|v| v as u64),
        trailing_pe_hundredths: statistics
            .and_then(|s| raw_double(s, "trailingPE"))
            .map(|v| (v * 100.0) as u32),
        forward_pe_hundredths: statistics
            .and_then(|s| raw_double(s, "forwardPE"))
            .map(|v| (v * 100.0) as u32),
        price_to_book_hundredths: statistics
            .and_then(|s| raw_double(s, "priceToBook"))
            .map(|v| (v * 100.0) as u32),
        return_on_equity_bps: financial_data
            .and_then(|f| raw_double(f, "returnOnEquity"))
            .map(|v| (v * 10_000.0) as i32),
        ebitda_dollars: financial_data
            .and_then(|f| raw_double(f, "ebitda"))
            .map(|v| v as i64),
        enterprise_value_dollars: statistics
            .and_then(|s| raw_double(s, "enterpriseValue"))
            .map(|v| v as i64),
        enterprise_to_ebitda_hundredths: statistics
            .and_then(|s| raw_double(s, "enterpriseToEbitda"))
            .map(|v| (v * 100.0) as i32),
        total_debt_dollars: financial_data
            .and_then(|f| raw_double(f, "totalDebt"))
            .map(|v| v as i64),
        total_cash_dollars: financial_data
            .and_then(|f| raw_double(f, "totalCash"))
            .map(|v| v as i64),
        debt_to_equity_hundredths: financial_data
            .and_then(|f| raw_double(f, "debtToEquity"))
            .map(|v| (v * 100.0) as i32),
        free_cash_flow_dollars: financial_data
            .and_then(|f| raw_double(f, "freeCashflow"))
            .map(|v| v as i64),
        operating_cash_flow_dollars: financial_data
            .and_then(|f| raw_double(f, "operatingCashflow"))
            .map(|v| v as i64),
        beta_millis: statistics
            .and_then(|s| raw_double(s, "beta"))
            .map(|v| (v * 1000.0) as i32),
        trailing_eps_cents: trailing_eps.map(|e| (e * 100.0).round() as i64),
        earnings_growth_bps: financial_data
            .and_then(|f| raw_double(f, "earningsGrowth"))
            .map(|v| (v * 10_000.0) as i32),
        book_value_per_share_cents: statistics
            .and_then(|s| raw_double(s, "bookValue").or_else(|| raw_double(s, "bookValuePerShare")))
            .filter(|v| *v > 0.0)
            .map(|v| (v * 100.0).round() as i64)
            .or_else(|| {
                // Derive BVPS = price / P/B when Yahoo omits bookValue.
                let price = financial_data
                    .and_then(|f| raw_double(f, "currentPrice"))
                    .or_else(|| price.and_then(|p| raw_double(p, "regularMarketPrice")))
                    .or_else(|| market_price_cents.map(|c| c as f64 / 100.0))?;
                let pb = statistics.and_then(|s| raw_double(s, "priceToBook"))?;
                if price > 0.0 && pb > 0.0 {
                    Some(((price / pb) * 100.0).round() as i64)
                } else {
                    None
                }
            }),
        retention_bps: financial_data
            .and_then(|f| raw_double(f, "payoutRatio"))
            .filter(|payout| payout.is_finite() && (0.0..=1.0).contains(payout))
            .map(|payout| ((1.0 - payout) * 10_000.0).round() as i32),
    });

    FetchResult {
        symbol: display_symbol.to_string(),
        snapshot,
        signal,
        fundamentals,
    }
}

/// Fill missing/zero market price from chart/stooq without re-fetching quoteSummary.
pub fn with_price_fallback(
    mut result: FetchResult,
    fallback_cents: Option<i64>,
    force_profitable: bool,
) -> FetchResult {
    let Some(price) = fallback_cents.filter(|&p| p > 0) else {
        return result;
    };
    if let Some(snap) = result.snapshot.as_mut() {
        if snap.market_price_cents <= 0 {
            snap.market_price_cents = price;
        }
        return result;
    }
    result.snapshot = Some(MarketSnapshot {
        symbol: result.symbol.clone(),
        company_name: None,
        profitable: force_profitable,
        market_price_cents: price,
        intrinsic_value_cents: 0,
        previous_close_cents: 0,
        next_earnings_epoch: None,
    });
    result
}

/// Apply Windows-specific sector labels for crypto / ETF after pure parse.
/// Creates a fundamentals shell when quoteSummary was missing so sector columns
/// still populate for non-stock asset classes.
pub fn apply_asset_class_overrides(
    mut result: FetchResult,
    is_crypto_sym: bool,
    etf_sector_name: Option<&str>,
) -> FetchResult {
    if is_crypto_sym {
        if let Some(snap) = result.snapshot.as_mut() {
            snap.profitable = true;
        }
        let fund = result
            .fundamentals
            .get_or_insert_with(|| FundamentalSnapshot {
                symbol: result.symbol.clone(),
                ..Default::default()
            });
        fund.sector_name = Some("Cryptocurrency".to_string());
    } else if let Some(sector) = etf_sector_name {
        let fund = result
            .fundamentals
            .get_or_insert_with(|| FundamentalSnapshot {
                symbol: result.symbol.clone(),
                ..Default::default()
            });
        if fund.sector_name.is_none() {
            fund.sector_name = Some(sector.to_string());
        }
    }
    result
}

/// True when a fetch has enough data to appear in the opportunity list.
///
/// Visibility only requires a usable price. Enrichment is deliberately tracked by
/// `is_enrichment_complete` so chart-recovered stocks can render immediately.
pub fn is_list_ready(result: &FetchResult, is_crypto_sym: bool, is_etf_sym: bool) -> bool {
    let has_price = result
        .snapshot
        .as_ref()
        .is_some_and(|s| s.market_price_cents > 0);
    if !has_price {
        return false;
    }
    let _ = (is_crypto_sym, is_etf_sym);
    true
}

/// True when no further list-column enrichment is needed for this result.
/// Crypto and ETFs do not depend on analyst coverage. For stocks, a successfully
/// parsed fundamentals payload completes the attempt even when Yahoo legitimately
/// has no target, analyst coverage, or sector; those sparse fields render as `—`.
pub fn is_enrichment_complete(result: &FetchResult, is_crypto_sym: bool, is_etf_sym: bool) -> bool {
    if !is_list_ready(result, is_crypto_sym, is_etf_sym) {
        return false;
    }
    if is_crypto_sym || is_etf_sym {
        return true;
    }

    result.fundamentals.is_some()
}

fn resolve_market_cap_dollars(
    reported: Option<f64>,
    shares: Option<f64>,
    price_dollars: Option<f64>,
) -> Option<u64> {
    if let Some(cap) = reported.filter(|v| v.is_finite() && *v > 0.0) {
        return Some(cap as u64);
    }
    match (shares, price_dollars) {
        (Some(s), Some(p)) if s > 0.0 && p > 0.0 && s.is_finite() && p.is_finite() => {
            Some((s * p) as u64)
        }
        _ => None,
    }
}

fn derive_shares_outstanding(
    market_cap_dollars: Option<f64>,
    price_dollars: Option<f64>,
) -> Option<f64> {
    match (market_cap_dollars, price_dollars) {
        (Some(market_cap), Some(price))
            if market_cap.is_finite() && market_cap > 0.0 && price.is_finite() && price > 0.0 =>
        {
            Some(market_cap / price)
        }
        _ => None,
    }
}

fn is_usable_name(name: &str, symbol: &str) -> bool {
    let n = name.trim();
    !n.is_empty() && !n.eq_ignore_ascii_case("null") && !n.eq_ignore_ascii_case(symbol)
}

/// Yahoo usually wraps numerics as `{"raw": X, "fmt": "..."}`; some payloads use a bare number.
pub fn raw_double(obj: &Value, field: &str) -> Option<f64> {
    let v = obj.get(field)?;
    if let Some(raw) = v.get("raw") {
        return raw.as_f64();
    }
    v.as_f64()
}

pub fn raw_money(obj: &Value, field: &str) -> Option<i64> {
    dollars_to_cents(raw_double(obj, field)?)
}

/// Convert a positive dollar price to integer cents.
///
/// Sub-cent assets (e.g. SHIB ≈ $0.000004) would otherwise round to 0 and never
/// satisfy `market_price_cents > 0`, leaving them stuck in the incomplete-retry
/// queue forever. Floor those to 1 cent so list readiness can complete.
pub fn dollars_to_cents(d: f64) -> Option<i64> {
    if !d.is_finite() || d <= 0.0 {
        return None;
    }
    let cents = (d * 100.0).round() as i64;
    Some(if cents > 0 { cents } else { 1 })
}

pub fn raw_int(obj: &Value, field: &str) -> Option<u32> {
    raw_double(obj, field).map(|v| v as u32)
}

fn trend_count(trend: &Option<Value>, key: &str) -> Option<u32> {
    trend.as_ref()?.get(key)?.as_u64().map(|n| n as u32)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn fixture(name: &str) -> Value {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/yahoo/quoteSummary")
            .join(name);
        let raw = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("missing fixture {}: {e}", path.display()));
        serde_json::from_str(&raw).expect("json")
    }

    fn forward_fixtures() -> Vec<Value> {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/yahoo/earningsTrend/reported5.json");
        let raw = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("missing fixture {}: {e}", path.display()));
        serde_json::from_str(&raw).expect("json")
    }

    #[test]
    fn parses_five_real_forward_forecasts_without_target_or_price_inputs() {
        let expected = [
            ("DVN", 382, 505, 629, 12, 1_176, 691),
            ("GDDY", 749, 897, 1_075, 13, 584, 1_477),
            ("WYNN", 402, 502, 581, 9, 417, 1_344),
            ("SNDK", 15_428, 21_295, 39_608, 21, 15_139, 21_934),
            ("BR", 1_024, 1_042, 1_057, 8, 463, 908),
        ];
        for (row, expected) in forward_fixtures().iter().zip(expected) {
            let symbol = row["symbol"].as_str().expect("symbol");
            let parsed = parse_forward_forecast_evidence(row, symbol, 20_665)
                .unwrap_or_else(|e| panic!("{symbol}: {e:?}"));
            assert_eq!(
                (
                    symbol,
                    parsed.eps_low_cents,
                    parsed.eps_mean_cents,
                    parsed.eps_high_cents,
                    parsed.analyst_count,
                    parsed.revenue_growth_bps,
                    parsed.earnings_growth_bps,
                ),
                (
                    expected.0,
                    Some(expected.1),
                    Some(expected.2),
                    Some(expected.3),
                    Some(expected.4),
                    Some(expected.5),
                    Some(expected.6),
                )
            );
            assert_eq!(parsed.currency, "USD");
            assert_eq!(parsed.revenue_currency, "USD");
            assert_eq!(parsed.reporting_currency, "USD");
            assert!(parsed.forecast_period_end_epoch_day > parsed.observed_epoch_day);
            assert!(parsed
                .source_fingerprint
                .starts_with("yahoo-earnings-trend/1|"));
        }
    }

    #[test]
    fn forward_parser_has_typed_missing_period_and_currency_failures() {
        let mut row = forward_fixtures().remove(0);
        row["quoteSummary"]["result"][0]["earningsTrend"]["trend"][0]["period"] =
            Value::String("0y".into());
        assert_eq!(
            parse_forward_forecast_evidence(&row, "DVN", 20_665),
            Err(ForwardForecastProviderError::MissingForwardPeriod)
        );

        let mut row = forward_fixtures().remove(0);
        row["quoteSummary"]["result"][0]["earningsTrend"]["trend"][0]["earningsEstimate"]
            ["earningsCurrency"] = Value::Null;
        assert_eq!(
            parse_forward_forecast_evidence(&row, "DVN", 20_665),
            Err(ForwardForecastProviderError::MissingCurrency)
        );

        let mut row = forward_fixtures().remove(0);
        row["quoteSummary"]["result"][0]["price"]["currency"] = Value::String("EUR".into());
        assert_eq!(
            parse_forward_forecast_evidence(&row, "DVN", 20_665),
            Err(ForwardForecastProviderError::CurrencyMismatch)
        );

        let mut row = forward_fixtures().remove(0);
        row["quoteSummary"]["result"][0]["earningsTrend"]["trend"][0]["endDate"] = Value::Null;
        assert_eq!(
            parse_forward_forecast_evidence(&row, "DVN", 20_665),
            Err(ForwardForecastProviderError::MissingForecastEndDate)
        );

        let mut row = forward_fixtures().remove(0);
        row["quoteSummary"]["result"][0]["earningsTrend"]["trend"][0]["endDate"] =
            Value::String("not-a-date".into());
        assert_eq!(
            parse_forward_forecast_evidence(&row, "DVN", 20_665),
            Err(ForwardForecastProviderError::InvalidForecastEndDate)
        );
    }

    #[test]
    fn target_and_market_price_mutations_do_not_change_forward_evidence() {
        let row = forward_fixtures().remove(0);
        let expected = parse_forward_forecast_evidence(&row, "DVN", 20_665).expect("baseline");
        let mut mutated = row;
        mutated["quoteSummary"]["result"][0]["price"]["regularMarketPrice"] =
            serde_json::json!({"raw": 999999.0});
        mutated["quoteSummary"]["result"][0]["financialData"]["targetMeanPrice"] =
            serde_json::json!({"raw": 0.01});
        assert_eq!(
            parse_forward_forecast_evidence(&mutated, "DVN", 20_665),
            Ok(expected)
        );
    }

    #[test]
    fn yahoo_request_symbol_maps_share_class_dot_to_hyphen() {
        assert_eq!(yahoo_request_symbol("BF.B"), "BF-B");
        assert_eq!(yahoo_request_symbol("BRK.B"), "BRK-B");
    }

    #[test]
    fn forward_consensus_remains_demand_only() {
        assert!(!QUOTE_SUMMARY_MODULES.contains("earningsTrend"));
        assert!(FORWARD_FORECAST_MODULES.starts_with("earningsTrend"));
    }

    #[test]
    fn dollars_to_cents_floors_sub_cent_crypto_so_list_ready_can_complete() {
        assert_eq!(dollars_to_cents(4.26e-6), Some(1)); // SHIB-scale
        assert_eq!(dollars_to_cents(0.0049), Some(1));
        assert_eq!(dollars_to_cents(0.01), Some(1));
        assert_eq!(dollars_to_cents(12.345), Some(1_235));
        assert_eq!(dollars_to_cents(0.0), None);
        assert_eq!(dollars_to_cents(-1.0), None);
    }

    #[test]
    fn derives_shares_when_yahoo_omits_share_count_but_reports_market_cap() {
        assert_eq!(
            derive_shares_outstanding(Some(120_000_000_000.0), Some(120.0)),
            Some(1_000_000_000.0)
        );
        assert_eq!(derive_shares_outstanding(None, Some(120.0)), None);
        assert_eq!(derive_shares_outstanding(Some(120.0), Some(0.0)), None);
    }

    #[test]
    fn yahoo_request_symbol_keeps_exchange_suffix() {
        assert_eq!(yahoo_request_symbol("YPFD.BA"), "YPFD.BA");
        assert_eq!(yahoo_request_symbol("MELI.BA"), "MELI.BA");
    }

    #[test]
    fn parse_aapl_fixture_has_price_and_targets() {
        let root = fixture("AAPL.json");
        let result = parse_quote_summary(&root, "AAPL");
        let snap = result.snapshot.expect("snapshot");
        assert!(snap.market_price_cents > 0);
        assert!(snap.intrinsic_value_cents > 0);
        assert_eq!(snap.company_name.as_deref(), Some("Apple Inc."));
        assert!(result.signal.is_some());
        let fund = result.fundamentals.expect("fundamentals");
        assert!(fund.market_cap_dollars.unwrap_or(0) > 0);
        assert!(
            fund.free_cash_flow_dollars.is_some() || fund.operating_cash_flow_dollars.is_some()
        );
    }

    #[test]
    fn parse_brk_b_fixture_uses_display_symbol() {
        let root = fixture("BRK-B.json");
        let result = parse_quote_summary(&root, "BRK.B");
        assert_eq!(result.symbol, "BRK.B");
        assert!(result.snapshot.is_some());
    }

    #[test]
    fn parse_all_live_fixtures() {
        for name in [
            "AAPL.json",
            "BRK-B.json",
            "C.json",
            "F.json",
            "L.json",
            "T.json",
        ] {
            let root = fixture(name);
            let sym = name.trim_end_matches(".json").replace('-', ".");
            let result = parse_quote_summary(&root, &sym);
            assert!(
                result.snapshot.is_some() || result.fundamentals.is_some(),
                "{name} should yield usable data"
            );
        }
    }

    #[test]
    fn aapl_fixture_is_list_ready_with_sector_and_target() {
        let root = fixture("AAPL.json");
        let result = parse_quote_summary(&root, "AAPL");
        assert!(is_list_ready(&result, false, false));
        let snap = result.snapshot.expect("snapshot");
        assert!(snap.intrinsic_value_cents > 0, "objetivo/target");
        let fund = result.fundamentals.expect("fundamentals");
        assert_eq!(fund.sector_name.as_deref(), Some("Technology"));
        let sig = result.signal.expect("signal");
        assert!(sig.analyst_opinion_count.unwrap_or(0) > 0);
    }

    #[test]
    fn chart_only_priced_stock_is_list_ready_for_progressive_hydration() {
        let result = with_price_fallback(
            FetchResult {
                symbol: "AAPL".into(),
                snapshot: None,
                signal: None,
                fundamentals: None,
            },
            Some(32_750),
            false,
        );
        assert!(result.snapshot.is_some());
        assert!(is_list_ready(&result, false, false));
        assert!(!is_enrichment_complete(&result, false, false));
    }

    #[test]
    fn stock_with_target_analysts_and_sector_is_enrichment_complete() {
        let result = parse_quote_summary(&fixture("AAPL.json"), "AAPL");
        assert!(is_enrichment_complete(&result, false, false));
    }

    #[test]
    fn successful_sparse_stock_response_does_not_retry_forever() {
        let mut result = with_price_fallback(
            FetchResult {
                symbol: "SPARSE".into(),
                snapshot: None,
                signal: None,
                fundamentals: Some(FundamentalSnapshot {
                    symbol: "SPARSE".into(),
                    ..Default::default()
                }),
            },
            Some(10_000),
            false,
        );
        result.snapshot.as_mut().unwrap().company_name = Some("Sparse Corp".into());

        assert!(is_enrichment_complete(&result, false, false));
    }
}
