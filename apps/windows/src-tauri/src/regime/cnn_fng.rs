//! CNN Fear & Greed Index fetch + parse (unofficial dataviz endpoint).

use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use serde::Deserialize;

use super::types::CnnFearGreed;

const FNG_CACHE_TTL_SECS: u64 = 1800; // 30 min
const CNN_GRAPHDATA_BASE: &str = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata/";

pub struct CnnFngCache {
    inner: Mutex<Option<(CnnFearGreed, Instant)>>,
}

impl CnnFngCache {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(None),
        }
    }

    pub fn get_cached(&self) -> Option<CnnFearGreed> {
        let guard = self.inner.lock().ok()?;
        let (v, at) = guard.as_ref()?;
        if at.elapsed().as_secs() > FNG_CACHE_TTL_SECS {
            return None;
        }
        Some(v.clone())
    }

    pub fn put(&self, v: CnnFearGreed) {
        if let Ok(mut guard) = self.inner.lock() {
            *guard = Some((v, Instant::now()));
        }
    }
}

impl Default for CnnFngCache {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Deserialize)]
struct CnnRoot {
    fear_and_greed: CnnCurrent,
    #[serde(default)]
    fear_and_greed_historical: Option<CnnHistorical>,
}

#[derive(Debug, Deserialize)]
struct CnnCurrent {
    score: f64,
    #[serde(default)]
    rating: String,
    #[serde(default)]
    timestamp: Option<String>,
    #[serde(default)]
    previous_close: Option<f64>,
    #[serde(default)]
    previous_1_week: Option<f64>,
    #[serde(default)]
    #[allow(dead_code)]
    previous_1_month: Option<f64>,
    #[serde(default)]
    #[allow(dead_code)]
    previous_1_year: Option<f64>,
}

#[derive(Debug, Deserialize)]
struct CnnHistorical {
    #[serde(default)]
    data: Vec<CnnHistPoint>,
}

#[derive(Debug, Deserialize)]
struct CnnHistPoint {
    x: f64,
    y: f64,
    #[serde(default)]
    #[allow(dead_code)]
    rating: String,
}

/// Parse CNN graphdata JSON into domain type.
pub fn parse_cnn_fng_json(body: &str) -> Result<CnnFearGreed, String> {
    let root: CnnRoot = serde_json::from_str(body).map_err(|e| format!("CNN F&G parse: {e}"))?;
    let score = root.fear_and_greed.score;
    if !(0.0..=100.0).contains(&score) && !(0.0..=100.0).contains(&(score.round())) {
        // allow slight float noise outside after clamp later
        if score < -5.0 || score > 105.0 {
            return Err(format!("CNN F&G score out of range: {score}"));
        }
    }
    let rating = if root.fear_and_greed.rating.is_empty() {
        rating_from_score(score).to_string()
    } else {
        // Normalize casing: "extreme fear" → "Extreme Fear"
        title_case_rating(&root.fear_and_greed.rating)
    };

    let mut historical: Vec<(i64, f64)> = root
        .fear_and_greed_historical
        .map(|h| h.data.into_iter().map(|p| (p.x as i64, p.y)).collect())
        .unwrap_or_default();
    historical.sort_by_key(|(t, _)| *t);

    let fetched_at_epoch =
        parse_ts(root.fear_and_greed.timestamp.as_deref()).unwrap_or_else(now_secs);

    Ok(CnnFearGreed {
        score: score.clamp(0.0, 100.0),
        rating,
        previous_close: root.fear_and_greed.previous_close,
        previous_1_week: root.fear_and_greed.previous_1_week,
        historical,
        fetched_at_epoch,
    })
}

fn title_case_rating(s: &str) -> String {
    s.split_whitespace()
        .map(|w| {
            let mut c = w.chars();
            match c.next() {
                None => String::new(),
                Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn rating_from_score(score: f64) -> &'static str {
    if score <= 24.0 {
        "Extreme Fear"
    } else if score <= 44.0 {
        "Fear"
    } else if score <= 55.0 {
        "Neutral"
    } else if score <= 75.0 {
        "Greed"
    } else {
        "Extreme Greed"
    }
}

fn parse_ts(ts: Option<&str>) -> Option<i64> {
    let ts = ts?;
    // "2026-07-23T23:59:49+00:00" — accept date prefix as rough epoch via chrono-less parse
    // Without chrono, approximate: if we can't parse fully, return None.
    // Simple approach: try unix if all digits, else None (use now).
    if let Ok(n) = ts.parse::<i64>() {
        return Some(n);
    }
    // Parse YYYY-MM-DD roughly
    if ts.len() >= 10 {
        let y: i64 = ts[0..4].parse().ok()?;
        let m: i64 = ts[5..7].parse().ok()?;
        let d: i64 = ts[8..10].parse().ok()?;
        // days since 1970 rough (not leap-perfect; good enough for display age)
        let days = (y - 1970) * 365 + (y - 1969) / 4 + day_of_year(y, m, d) - 1;
        return Some(days * 86_400);
    }
    None
}

fn day_of_year(y: i64, m: i64, d: i64) -> i64 {
    let leap = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0);
    let mdays = [0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    let mut n = d;
    for i in 1..m as usize {
        n += mdays.get(i).copied().unwrap_or(30);
        if i == 2 && leap {
            n += 1;
        }
    }
    n
}

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// Start date for historical window (~6 months back by calendar string).
fn graphdata_start_date() -> String {
    let now = now_secs();
    let then = now - 180 * 86_400;
    // Approximate Y-M-D from epoch (good enough for API start param)
    let days = then / 86_400;
    // 1970-01-01 + days — use a simple civil-from-days
    let (y, m, d) = civil_from_days(days as i32);
    format!("{y:04}-{m:02}-{d:02}")
}

/// Howard Hinnant civil_from_days (simplified).
fn civil_from_days(z: i32) -> (i32, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u32;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe as i32 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    (y, m, d)
}

pub fn fetch_cnn_fear_greed(client: &Client) -> Result<CnnFearGreed, String> {
    let start = graphdata_start_date();
    let url = format!("{CNN_GRAPHDATA_BASE}{start}");
    let resp = client
        .get(&url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )
        .header("Accept", "application/json,text/plain,*/*")
        .header("Referer", "https://www.cnn.com/markets/fear-and-greed")
        .header("Origin", "https://www.cnn.com")
        .timeout(Duration::from_secs(12))
        .send()
        .map_err(|e| format!("CNN F&G fetch: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("CNN F&G HTTP {}", resp.status()));
    }
    let body = resp.text().map_err(|e| format!("CNN F&G body: {e}"))?;
    parse_cnn_fng_json(&body)
}

/// Get cached or fetch.
pub fn get_cnn_fng(client: &Client, cache: &CnnFngCache) -> Option<CnnFearGreed> {
    if let Some(v) = cache.get_cached() {
        return Some(v);
    }
    match fetch_cnn_fear_greed(client) {
        Ok(v) => {
            cache.put(v.clone());
            Some(v)
        }
        Err(_) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn load_fixture(name: &str) -> String {
        let path = format!(
            "{}/src/regime/fixtures/{}",
            env!("CARGO_MANIFEST_DIR"),
            name
        );
        std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {path}: {e}"))
    }

    #[test]
    fn parse_live_sample_structure() {
        let body = load_fixture("cnn_fng_sample1.json");
        let fg = parse_cnn_fng_json(&body).expect("parse sample1");
        assert!(fg.score >= 0.0 && fg.score <= 100.0);
        assert!(!fg.rating.is_empty());
        assert!(
            !fg.historical.is_empty(),
            "expected historical series in live sample"
        );
    }

    #[test]
    fn parse_five_zone_fixtures() {
        let names = [
            ("cnn_fng_1.json", "Extreme Fear", 0.0, 24.0),
            ("cnn_fng_2.json", "Fear", 25.0, 44.0),
            ("cnn_fng_3.json", "Neutral", 45.0, 55.0),
            ("cnn_fng_4.json", "Greed", 56.0, 75.0),
            ("cnn_fng_5.json", "Extreme Greed", 76.0, 100.0),
        ];
        for (name, expect_rating, lo, hi) in names {
            let fg =
                parse_cnn_fng_json(&load_fixture(name)).unwrap_or_else(|e| panic!("{name}: {e}"));
            assert!(
                fg.score >= lo && fg.score <= hi,
                "{name} score {} not in {lo}..{hi}",
                fg.score
            );
            assert_eq!(
                fg.rating.to_lowercase(),
                expect_rating.to_lowercase(),
                "{name} rating"
            );
        }
    }

    #[test]
    fn reject_garbage() {
        assert!(parse_cnn_fng_json("not json").is_err());
        assert!(parse_cnn_fng_json("{}").is_err());
    }
}
