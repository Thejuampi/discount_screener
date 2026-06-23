// Stooq.com — keyless CSV market data, used as a redundancy/fallback source so
// Yahoo is no longer a single point of failure. No API key, no auth.
//
//   Last quote:  https://stooq.com/q/l/?s=aapl.us&f=sd2t2ohlcv&h&e=csv
//   Daily hist:  https://stooq.com/q/d/l/?s=aapl.us&i=d
//
// Coverage is strong for US equities/ETFs; crypto is not reliably covered, so we
// only map equities/ETFs and return None for crypto (Yahoo stays primary there).

use std::io;
use std::time::Duration;

use reqwest::blocking::Client;

use crate::engine::HistoricalCandle;
use crate::fetcher::is_crypto;

const HTTP_TIMEOUT: Duration = Duration::from_secs(15);
const QUOTE_URL: &str = "https://stooq.com/q/l/?s=";
const HIST_URL: &str = "https://stooq.com/q/d/l/?s=";

fn client() -> io::Result<Client> {
    Client::builder()
        .timeout(HTTP_TIMEOUT)
        .build()
        .map_err(io::Error::other)
}

/// Map an app ticker to Stooq's symbol convention, or None if unsupported.
/// US listings get a `.us` suffix; dotted tickers (BRK.B) use a dash (brk-b.us).
pub fn to_stooq_symbol(symbol: &str) -> Option<String> {
    if is_crypto(symbol) {
        return None; // crypto coverage on Stooq is unreliable
    }
    let s = symbol.trim().to_lowercase().replace('.', "-");
    if s.is_empty() {
        return None;
    }
    Some(format!("{s}.us"))
}

fn parse_price_cents(field: &str) -> Option<i64> {
    let v: f64 = field.trim().parse().ok()?;
    if v.is_finite() && v > 0.0 {
        Some((v * 100.0).round() as i64)
    } else {
        None
    }
}

/// Latest close price in cents for `symbol`, via Stooq. None if unsupported/down.
pub fn fetch_quote_cents(symbol: &str) -> Option<i64> {
    let stooq_sym = to_stooq_symbol(symbol)?;
    let url = format!("{QUOTE_URL}{stooq_sym}&f=sd2t2ohlcv&h&e=csv");
    let body = client().ok()?.get(&url).send().ok()?.text().ok()?;
    // CSV: header row then "AAPL.US,2024-11-07,22:00:00,222.0,...,221.5,40000000"
    // Columns: Symbol,Date,Time,Open,High,Low,Close,Volume
    let line = body.lines().nth(1)?;
    if line.contains("N/D") {
        return None;
    }
    let cols: Vec<&str> = line.split(',').collect();
    if cols.len() < 7 {
        return None;
    }
    parse_price_cents(cols[6]) // Close
}

/// Daily candle history (most recent ~ years) for `symbol`, via Stooq.
pub fn fetch_daily_candles(symbol: &str) -> Option<Vec<HistoricalCandle>> {
    let stooq_sym = to_stooq_symbol(symbol)?;
    let url = format!("{HIST_URL}{stooq_sym}&i=d");
    let body = client().ok()?.get(&url).send().ok()?.text().ok()?;
    // CSV: Date,Open,High,Low,Close,Volume
    let mut out = Vec::new();
    for line in body.lines().skip(1) {
        let c: Vec<&str> = line.split(',').collect();
        if c.len() < 6 {
            continue;
        }
        let epoch = parse_date_epoch(c[0]);
        let (open, high, low, close) = (
            parse_price_cents(c[1]),
            parse_price_cents(c[2]),
            parse_price_cents(c[3]),
            parse_price_cents(c[4]),
        );
        let volume: u64 = c[5].trim().parse().unwrap_or(0);
        if let (Some(ep), Some(o), Some(h), Some(l), Some(cl)) = (epoch, open, high, low, close) {
            out.push(HistoricalCandle {
                epoch_seconds: ep,
                open_cents: o,
                high_cents: h,
                low_cents: l,
                close_cents: cl,
                volume,
            });
        }
    }
    if out.is_empty() {
        None
    } else {
        Some(out)
    }
}

/// Parse "YYYY-MM-DD" into a unix epoch (seconds, UTC midnight).
fn parse_date_epoch(s: &str) -> Option<i64> {
    let mut parts = s.trim().split('-');
    let y: i64 = parts.next()?.parse().ok()?;
    let m: i64 = parts.next()?.parse().ok()?;
    let d: i64 = parts.next()?.parse().ok()?;
    if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    // Days from epoch via a civil-calendar algorithm (Howard Hinnant's).
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = (y - era * 400) as i64;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some(days * 86_400)
}
