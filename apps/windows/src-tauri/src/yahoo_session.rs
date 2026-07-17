//! Yahoo cookie + crumb session for quoteSummary JSON APIs (port of Android YahooSession).
//!
//! Bootstrap mirrors the Yahoo web app:
//! 1. Visit finance.yahoo.com to obtain A1/A3 cookies
//! 2. GET /v1/test/getcrumb with those cookies
//!
//! Rate-limit handling: on HTTP 429 we cache a cooldown and refuse further crumb
//! network attempts until it expires. Without this, concurrent feed workers thrash
//! getcrumb and keep the IP blocked — leaving quoteSummary (target/gap/analysts/sector)
//! permanently empty while chart still fills price.

use std::io;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use reqwest::blocking::Client;

const BOOTSTRAP_URL: &str = "https://finance.yahoo.com/";
const CRUMB_URLS: &[&str] = &[
    "https://query2.finance.yahoo.com/v1/test/getcrumb",
    "https://query1.finance.yahoo.com/v1/test/getcrumb",
];
const CRUMB_TTL: Duration = Duration::from_secs(12 * 60);
/// First cooldown after a 429. Subsequent hits double up to MAX.
const RATE_LIMIT_BASE: Duration = Duration::from_secs(90);
const RATE_LIMIT_MAX: Duration = Duration::from_secs(10 * 60);

fn rate_limit_cooldown(streak: u32) -> Duration {
    let exponent = streak.saturating_sub(1).min(4);
    (RATE_LIMIT_BASE * (1u32 << exponent)).min(RATE_LIMIT_MAX)
}

struct CrumbState {
    crumb: String,
    obtained_at: Instant,
}

struct SessionInner {
    crumb: Option<CrumbState>,
    rate_limited_until: Option<Instant>,
    rate_limit_streak: u32,
}

pub struct YahooSession {
    state: Mutex<SessionInner>,
}

impl YahooSession {
    pub fn new() -> Self {
        Self {
            state: Mutex::new(SessionInner {
                crumb: None,
                rate_limited_until: None,
                rate_limit_streak: 0,
            }),
        }
    }

    pub fn clear(&self) {
        let mut guard = self.state.lock().unwrap();
        guard.crumb = None;
        // Keep rate-limit state — clearing crumb must not invite a request storm.
    }

    /// Seconds remaining on an active rate-limit cooldown (0 if clear).
    pub fn rate_limit_remaining_secs(&self) -> u64 {
        let guard = self.state.lock().unwrap();
        match guard.rate_limited_until {
            Some(until) => until
                .saturating_duration_since(Instant::now())
                .as_secs()
                .max(if until > Instant::now() { 1 } else { 0 }),
            None => 0,
        }
    }

    pub fn is_rate_limited(&self) -> bool {
        self.rate_limit_remaining_secs() > 0
    }

    pub fn current_crumb_or_none(&self) -> Option<String> {
        let guard = self.state.lock().unwrap();
        let current = guard.crumb.as_ref()?;
        if current.obtained_at.elapsed() > CRUMB_TTL {
            return None;
        }
        Some(current.crumb.clone())
    }

    pub fn ensure_crumb(&self, client: &Client, user_agent: &str) -> io::Result<String> {
        if let Some(c) = self.current_crumb_or_none() {
            return Ok(c);
        }

        let mut guard = self.state.lock().unwrap();

        // Double-check under lock.
        if let Some(current) = guard.crumb.as_ref() {
            if current.obtained_at.elapsed() <= CRUMB_TTL {
                return Ok(current.crumb.clone());
            }
        }

        if let Some(until) = guard.rate_limited_until {
            if Instant::now() < until {
                let secs = until
                    .saturating_duration_since(Instant::now())
                    .as_secs()
                    .max(1);
                return Err(io::Error::other(format!(
                    "HTTP 429 for getcrumb: rate limited, retry in {secs}s"
                )));
            }
            // Cooldown expired — allow one attempt.
            guard.rate_limited_until = None;
        }

        // Drop the crumb cache before network so a failed attempt does not look valid.
        guard.crumb = None;

        // Release lock during network I/O so other threads can observe rate_limit state
        // after we re-acquire — but only ONE thread should bootstrap at a time.
        // Hold the lock for the whole bootstrap to serialize crumb acquisition.
        bootstrap_cookies(client, user_agent)?;
        match fetch_crumb(client, user_agent) {
            Ok(crumb) => {
                guard.rate_limited_until = None;
                guard.crumb = Some(CrumbState {
                    crumb: crumb.clone(),
                    obtained_at: Instant::now(),
                });
                Ok(crumb)
            }
            Err(e) => {
                if e.to_string().contains("429") {
                    let streak = guard.rate_limit_streak.saturating_add(1);
                    guard.rate_limit_streak = streak;
                    let cool = rate_limit_cooldown(streak);
                    guard.rate_limited_until = Some(Instant::now() + cool);
                    Err(io::Error::other(format!(
                        "HTTP 429 for getcrumb: rate limited for {}s",
                        cool.as_secs()
                    )))
                } else {
                    Err(e)
                }
            }
        }
    }

    /// Call when quoteSummary itself returns 429 (crumb may still be "valid" but Yahoo is hot).
    pub fn mark_rate_limited(&self) {
        let mut guard = self.state.lock().unwrap();
        let streak = guard.rate_limit_streak.saturating_add(1);
        guard.rate_limit_streak = streak;
        let cool = rate_limit_cooldown(streak);
        guard.rate_limited_until = Some(Instant::now() + cool);
        // Drop crumb so we re-bootstrap after cooldown instead of replaying a hot token.
        guard.crumb = None;
    }

    /// Reset exponential backoff only after the protected quoteSummary request
    /// itself succeeds. A successful crumb bootstrap is not enough evidence.
    pub fn mark_request_succeeded(&self) {
        let mut guard = self.state.lock().unwrap();
        guard.rate_limit_streak = 0;
        guard.rate_limited_until = None;
    }
}

impl Default for YahooSession {
    fn default() -> Self {
        Self::new()
    }
}

fn bootstrap_cookies(client: &Client, user_agent: &str) -> io::Result<()> {
    // Cookies land in the shared cookie store even on non-2xx occasionally.
    let _ = client
        .get(BOOTSTRAP_URL)
        .header("User-Agent", user_agent)
        .header(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        .header("Accept-Language", "en-US,en;q=0.9")
        .send()
        .map_err(io::Error::other)?;
    Ok(())
}

fn fetch_crumb(client: &Client, user_agent: &str) -> io::Result<String> {
    let mut last_err: Option<io::Error> = None;
    for url in CRUMB_URLS {
        match client
            .get(*url)
            .header("User-Agent", user_agent)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://finance.yahoo.com/")
            .timeout(Duration::from_secs(8))
            .send()
        {
            Ok(resp) => {
                let status = resp.status();
                let body = resp.text().unwrap_or_default().trim().to_string();
                if status.as_u16() == 429 {
                    // Fail fast — walking every host on rate-limit freezes the feed.
                    return Err(io::Error::other(format!(
                        "HTTP 429 for {url}: rate limited"
                    )));
                }
                if !status.is_success() {
                    last_err = Some(io::Error::other(format!("HTTP {status} for {url}: {body}")));
                    continue;
                }
                if body.is_empty() || body.starts_with('{') || body.len() > 80 {
                    last_err = Some(io::Error::other(format!(
                        "invalid crumb payload from {url}"
                    )));
                    continue;
                }
                return Ok(body);
            }
            Err(e) => last_err = Some(io::Error::other(e)),
        }
    }
    Err(last_err.unwrap_or_else(|| io::Error::other("failed to obtain Yahoo crumb")))
}

/// True for HTTP 401/403-style auth failures that warrant crumb refresh.
pub fn is_auth_error(err: &io::Error) -> bool {
    let msg = err.to_string();
    msg.contains("401") || msg.contains("403") || msg.to_lowercase().contains("unauthorized")
}

/// True when Yahoo is asking us to back off (crumb or quoteSummary).
pub fn is_rate_limit_error(err: &io::Error) -> bool {
    err.to_string().contains("429")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rate_limit_remaining_zero_by_default() {
        let s = YahooSession::new();
        assert_eq!(s.rate_limit_remaining_secs(), 0);
        assert!(!s.is_rate_limited());
    }

    #[test]
    fn mark_rate_limited_sets_cooldown() {
        let s = YahooSession::new();
        s.mark_rate_limited();
        assert!(s.is_rate_limited());
        assert!(s.rate_limit_remaining_secs() >= 1);
        assert!(s.current_crumb_or_none().is_none());
    }

    #[test]
    fn first_rate_limit_cooldown_is_ninety_seconds() {
        assert_eq!(rate_limit_cooldown(1), Duration::from_secs(90));
        assert_eq!(rate_limit_cooldown(2), Duration::from_secs(180));
    }

    #[test]
    fn repeated_rate_limits_escalate_until_a_request_succeeds() {
        let s = YahooSession::new();
        s.mark_rate_limited();
        let first = s.rate_limit_remaining_secs();
        s.mark_rate_limited();
        let second = s.rate_limit_remaining_secs();
        assert!(first <= 90);
        assert!(second > first);

        s.mark_request_succeeded();
        assert_eq!(s.rate_limit_remaining_secs(), 0);
    }
}
