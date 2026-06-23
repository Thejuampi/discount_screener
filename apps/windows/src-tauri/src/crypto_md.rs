// Crypto Scalping — Market Data Layer (Phase 1: Coinbase REST).
//
// Independent service to fetch OHLCV for short timeframes. Coinbase Exchange's
// public candles API only supports {60,300,900,3600,21600,86400}s granularities,
// so 3m and 4h are built by aggregating 1m×3 and 1h×4, bucketed by timestamp.
//
// REST polling for now; a WebSocket feed is Phase 2. Reuses HistoricalCandle.

use std::io;
use std::time::Duration;

use reqwest::blocking::Client;
use serde_json::Value;

use crate::engine::HistoricalCandle;

const HTTP_TIMEOUT: Duration = Duration::from_secs(15);
const CANDLES_URL: &str = "https://api.exchange.coinbase.com/products";
const USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DiscountScreener/1.0";

/// Timeframes offered by the scalping module.
pub const SCALP_TIMEFRAMES: &[&str] = &["1m", "3m", "5m", "15m", "1h", "4h"];

/// (base granularity in seconds that Coinbase supports, aggregation factor).
fn tf_plan(tf: &str) -> Option<(u32, u32)> {
    match tf {
        "1m" => Some((60, 1)),
        "3m" => Some((60, 3)),   // aggregate 3×1m
        "5m" => Some((300, 1)),
        "15m" => Some((900, 1)),
        "1h" => Some((3600, 1)),
        "4h" => Some((3600, 4)), // aggregate 4×1h
        _ => None,
    }
}

/// Final candle width in seconds for a timeframe (used for bucket alignment).
pub fn tf_seconds(tf: &str) -> Option<i64> {
    tf_plan(tf).map(|(g, f)| (g * f) as i64)
}

fn client() -> io::Result<Client> {
    Client::builder().timeout(HTTP_TIMEOUT).build().map_err(io::Error::other)
}

/// Fetch candles for `product` (e.g. "BTC-USD") at `tf`, ascending by time.
/// Returns up to ~300 base candles (fewer after aggregation).
pub fn fetch_candles(product: &str, tf: &str) -> Result<Vec<HistoricalCandle>, String> {
    let (granularity, factor) = tf_plan(tf).ok_or_else(|| format!("timeframe inválido: {tf}"))?;
    let prod = product.trim().to_uppercase();
    let url = format!("{CANDLES_URL}/{prod}/candles?granularity={granularity}");

    let resp: Value = client().map_err(|e| e.to_string())?
        .get(&url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .send().map_err(|e| e.to_string())?
        .error_for_status().map_err(|e| e.to_string())?
        .json().map_err(|e| e.to_string())?;

    let arr = resp.as_array().ok_or("respuesta inesperada de Coinbase")?;
    // Coinbase row: [time, low, high, open, close, volume] (newest first).
    let mut base: Vec<HistoricalCandle> = arr.iter().filter_map(|row| {
        let r = row.as_array()?;
        if r.len() < 6 { return None; }
        let to_cents = |v: &Value| -> Option<i64> {
            let f = v.as_f64()?;
            if f.is_finite() && f > 0.0 { Some((f * 100.0).round() as i64) } else { None }
        };
        Some(HistoricalCandle {
            epoch_seconds: r[0].as_i64()?,
            low_cents: to_cents(&r[1])?,
            high_cents: to_cents(&r[2])?,
            open_cents: to_cents(&r[3])?,
            close_cents: to_cents(&r[4])?,
            volume: r[5].as_f64().map(|v| v.max(0.0) as u64).unwrap_or(0),
        })
    }).collect();

    base.sort_by_key(|c| c.epoch_seconds); // ascending
    if base.is_empty() { return Err("Coinbase no devolvió velas".into()); }

    if factor <= 1 {
        return Ok(base);
    }
    Ok(aggregate(&base, granularity as i64 * factor as i64))
}

/// Aggregate finer candles into `bucket_secs`-wide candles, aligned by timestamp.
fn aggregate(base: &[HistoricalCandle], bucket_secs: i64) -> Vec<HistoricalCandle> {
    let mut out: Vec<HistoricalCandle> = Vec::new();
    let mut cur_bucket: i64 = i64::MIN;
    for c in base {
        let bucket = c.epoch_seconds - c.epoch_seconds.rem_euclid(bucket_secs);
        if bucket != cur_bucket {
            out.push(HistoricalCandle {
                epoch_seconds: bucket,
                open_cents: c.open_cents,
                high_cents: c.high_cents,
                low_cents: c.low_cents,
                close_cents: c.close_cents,
                volume: c.volume,
            });
            cur_bucket = bucket;
        } else if let Some(agg) = out.last_mut() {
            agg.high_cents = agg.high_cents.max(c.high_cents);
            agg.low_cents = agg.low_cents.min(c.low_cents);
            agg.close_cents = c.close_cents; // last close in the bucket
            agg.volume += c.volume;
        }
    }
    out
}
