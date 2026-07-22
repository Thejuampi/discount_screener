use reqwest::blocking::Client;
use serde::Serialize;
use std::collections::HashMap;
use std::sync::Mutex;
/// Yahoo Finance news feed (RSS) + keyword-based sentiment analysis.
///
/// We use the public RSS feed:
///   https://feeds.finance.yahoo.com/rss/2.0/headline?s=AAPL&region=US&lang=en-US
/// which returns ~10-20 recent headlines per symbol with title + link + pubDate.
///
/// Sentiment is a simple lexicon match (no LLM):
///   positive_count - negative_count → -100..+100 per headline
///   aggregate = mean of per-headline scores
use std::time::Duration;

const NEWS_USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

const CACHE_TTL_SECS: u64 = 1800; // 30 minutes

// ── Lexicons ──────────────────────────────────────────────────────────────────

const POSITIVE: &[&str] = &[
    "beats",
    "beat",
    "exceeds",
    "exceeded",
    "surges",
    "surge",
    "surged",
    "rally",
    "rallies",
    "rallied",
    "soars",
    "soared",
    "jumps",
    "jumped",
    "climbs",
    "climbed",
    "upgrade",
    "upgrades",
    "upgraded",
    "outperform",
    "raises",
    "raised",
    "boost",
    "boosted",
    "boosts",
    "strong",
    "stronger",
    "record",
    "all-time high",
    "breakthrough",
    "approves",
    "approved",
    "approval",
    "wins",
    "won",
    "winning",
    "partnership",
    "deal",
    "acquisition",
    "expansion",
    "expand",
    "growth",
    "growing",
    "innovative",
    "milestone",
    "positive",
    "bullish",
    "buy",
    "rated buy",
    "overweight",
    "favorable",
    "exceeds estimates",
    "beat estimates",
    "tops estimates",
    "strong demand",
    "guidance raised",
    "raises guidance",
    "raises forecast",
    "blowout",
    "stellar",
    "robust",
    "momentum",
    "tailwind",
    "tailwinds",
];

const NEGATIVE: &[&str] = &[
    "misses",
    "missed",
    "miss",
    "falls",
    "fall",
    "fell",
    "drops",
    "drop",
    "dropped",
    "plunges",
    "plunged",
    "tumbles",
    "tumbled",
    "slides",
    "slid",
    "downgrade",
    "downgrades",
    "downgraded",
    "cuts",
    "cut",
    "warning",
    "warns",
    "warned",
    "lawsuit",
    "investigation",
    "investigated",
    "fraud",
    "concerns",
    "concern",
    "worry",
    "worried",
    "fears",
    "fear",
    "bearish",
    "selloff",
    "sell-off",
    "crash",
    "loss",
    "losses",
    "decline",
    "declined",
    "declines",
    "underperform",
    "sell",
    "rated sell",
    "underweight",
    "risk",
    "risky",
    "risks",
    "uncertainty",
    "uncertain",
    "delay",
    "delayed",
    "delays",
    "weak",
    "weakness",
    "weaker",
    "disappoints",
    "disappointed",
    "disappointment",
    "miss estimates",
    "below estimates",
    "lowered guidance",
    "cuts guidance",
    "guidance cut",
    "slashes",
    "slumps",
    "slump",
    "headwind",
    "headwinds",
    "recession",
    "bankruptcy",
    "default",
    "downturn",
    "subpoena",
    "fine",
    "fined",
    "penalty",
    "settlement",
    "recall",
];

// ── Types ─────────────────────────────────────────────────────────────────────

#[derive(Clone, Debug, Serialize)]
pub struct NewsItem {
    pub title: String,
    pub link: String,
    pub published_at: String, // ISO date or raw RSS pub_date
    pub published_epoch: i64, // unix seconds, 0 if unparseable
    pub source: Option<String>,
    pub sentiment_score: i32, // -100..+100
}

#[derive(Clone, Debug, Serialize)]
pub struct NewsBundle {
    pub items: Vec<NewsItem>,
    pub aggregate_sentiment: i32, // -100..+100, mean of per-item scores
    pub positive_count: u32,
    pub negative_count: u32,
    pub neutral_count: u32,
    pub fetched_at: i64,
}

// ── Cache ─────────────────────────────────────────────────────────────────────

pub struct NewsCache {
    inner: Mutex<HashMap<String, (NewsBundle, std::time::Instant)>>,
}

impl NewsCache {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(HashMap::new()),
        }
    }
    pub fn get(&self, symbol: &str) -> Option<NewsBundle> {
        let map = self.inner.lock().ok()?;
        let (b, at) = map.get(symbol)?;
        if at.elapsed().as_secs() > CACHE_TTL_SECS {
            return None;
        }
        Some(b.clone())
    }
    pub fn put(&self, symbol: String, bundle: NewsBundle) {
        if let Ok(mut map) = self.inner.lock() {
            map.insert(symbol, (bundle, std::time::Instant::now()));
        }
    }
}

// ── Client / fetch ────────────────────────────────────────────────────────────

pub fn news_client() -> Client {
    Client::builder()
        .timeout(Duration::from_secs(15))
        .user_agent(NEWS_USER_AGENT)
        .build()
        .expect("news http client")
}

pub fn fetch_news(client: &Client, symbol: &str) -> Result<NewsBundle, String> {
    let url = format!(
        "https://feeds.finance.yahoo.com/rss/2.0/headline?s={}&region=US&lang=en-US",
        symbol
    );
    let xml = client
        .get(&url)
        .send()
        .map_err(|e| format!("news fetch {}: {}", symbol, e))?
        .text()
        .map_err(|e| format!("news body {}: {}", symbol, e))?;

    let items = parse_rss(&xml);
    let scored: Vec<NewsItem> = items
        .into_iter()
        .map(|mut it| {
            it.sentiment_score = score_headline(&it.title);
            it
        })
        .collect();

    let mut pos = 0u32;
    let mut neg = 0u32;
    let mut neu = 0u32;
    for it in &scored {
        if it.sentiment_score > 5 {
            pos += 1;
        } else if it.sentiment_score < -5 {
            neg += 1;
        } else {
            neu += 1;
        }
    }
    let aggregate = if scored.is_empty() {
        0
    } else {
        let sum: i64 = scored.iter().map(|i| i.sentiment_score as i64).sum();
        (sum / scored.len() as i64) as i32
    };

    Ok(NewsBundle {
        items: scored,
        aggregate_sentiment: aggregate,
        positive_count: pos,
        negative_count: neg,
        neutral_count: neu,
        fetched_at: now_secs(),
    })
}

// ── Parsing ───────────────────────────────────────────────────────────────────

fn parse_rss(xml: &str) -> Vec<NewsItem> {
    let mut out = Vec::new();
    let mut cursor = 0usize;
    while let Some(start) = xml[cursor..].find("<item>") {
        let abs_start = cursor + start;
        let end = match xml[abs_start..].find("</item>") {
            Some(e) => abs_start + e,
            None => break,
        };
        let block = &xml[abs_start..end];
        cursor = end + 1;

        let title = extract_cdata_or_tag(block, "title").unwrap_or_default();
        let link = extract_cdata_or_tag(block, "link").unwrap_or_default();
        let pub_date = extract_cdata_or_tag(block, "pubDate").unwrap_or_default();
        let published_epoch = parse_rss_date(&pub_date);

        // Many Yahoo RSS items embed source in title as "Title - Source"
        let source = title.rsplit_once(" - ").map(|(_, s)| s.trim().to_string());

        if !title.is_empty() && !link.is_empty() {
            out.push(NewsItem {
                title: html_decode(&title),
                link,
                published_at: pub_date,
                published_epoch,
                source,
                sentiment_score: 0,
            });
        }
        if out.len() >= 12 {
            break;
        }
    }
    out
}

fn extract_cdata_or_tag(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<{}>", tag);
    let close = format!("</{}>", tag);
    let s = xml.find(&open)? + open.len();
    let e = xml[s..].find(&close)? + s;
    let raw = &xml[s..e];
    let trimmed = raw.trim();
    // Strip CDATA wrapper if present
    if let Some(rest) = trimmed.strip_prefix("<![CDATA[") {
        if let Some(content) = rest.strip_suffix("]]>") {
            return Some(content.trim().to_string());
        }
    }
    Some(trimmed.to_string())
}

/// Minimal HTML entity decoder for common cases in headlines.
fn html_decode(s: &str) -> String {
    s.replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
}

/// Parse RFC 822-ish RSS date → unix seconds.
/// Format examples: "Mon, 06 Jun 2026 14:35:00 +0000" or "Wed, 05 Jun 2026 09:00:00 GMT"
fn parse_rss_date(s: &str) -> i64 {
    // Try common date formats by extracting day/month/year/hour/minute/second
    let parts: Vec<&str> = s.split_whitespace().collect();
    if parts.len() < 5 {
        return 0;
    }
    let day: u32 = parts.get(1).and_then(|p| p.parse().ok()).unwrap_or(0);
    let month = month_to_num(parts.get(2).copied().unwrap_or(""));
    let year: i32 = parts.get(3).and_then(|p| p.parse().ok()).unwrap_or(0);
    let time = parts.get(4).copied().unwrap_or("00:00:00");
    let t_parts: Vec<&str> = time.split(':').collect();
    let hour: u32 = t_parts.first().and_then(|p| p.parse().ok()).unwrap_or(0);
    let minute: u32 = t_parts.get(1).and_then(|p| p.parse().ok()).unwrap_or(0);
    let second: u32 = t_parts.get(2).and_then(|p| p.parse().ok()).unwrap_or(0);
    if year == 0 || month == 0 || day == 0 {
        return 0;
    }
    civil_to_epoch(year, month, day, hour, minute, second)
}

fn month_to_num(s: &str) -> u32 {
    match s {
        "Jan" => 1,
        "Feb" => 2,
        "Mar" => 3,
        "Apr" => 4,
        "May" => 5,
        "Jun" => 6,
        "Jul" => 7,
        "Aug" => 8,
        "Sep" => 9,
        "Oct" => 10,
        "Nov" => 11,
        "Dec" => 12,
        _ => 0,
    }
}

/// Howard Hinnant's algorithm: (y, m, d) → days since 1970-01-01, then to seconds.
fn civil_to_epoch(y: i32, m: u32, d: u32, hour: u32, min: u32, sec: u32) -> i64 {
    let y = y as i64 - if m <= 2 { 1 } else { 0 };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = (y - era * 400) as i64;
    let doy = (153 * (if m > 2 { m as i64 - 3 } else { m as i64 + 9 }) + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days_since_epoch = era * 146_097 + doe - 719_468;
    days_since_epoch * 86_400 + hour as i64 * 3600 + min as i64 * 60 + sec as i64
}

// ── Sentiment scoring ─────────────────────────────────────────────────────────

fn score_headline(title: &str) -> i32 {
    let lower = title.to_lowercase();
    let mut pos = 0i32;
    let mut neg = 0i32;
    for kw in POSITIVE {
        if lower.contains(kw) {
            pos += 1;
        }
    }
    for kw in NEGATIVE {
        if lower.contains(kw) {
            neg += 1;
        }
    }
    let raw = pos - neg;
    // Scale to a -100..+100 range; max realistic is ±4
    (raw * 25).clamp(-100, 100)
}

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
