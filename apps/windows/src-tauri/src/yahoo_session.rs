//! Yahoo cookie + crumb session for quoteSummary JSON APIs (port of Android YahooSession).
//!
//! Bootstrap mirrors the Yahoo web app:
//! 1. Visit finance.yahoo.com to obtain A1/A3 cookies
//! 2. GET /v1/test/getcrumb with those cookies

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

struct CrumbState {
    crumb: String,
    obtained_at: Instant,
}

pub struct YahooSession {
    state: Mutex<Option<CrumbState>>,
}

impl YahooSession {
    pub fn new() -> Self {
        Self {
            state: Mutex::new(None),
        }
    }

    pub fn clear(&self) {
        *self.state.lock().unwrap() = None;
    }

    pub fn current_crumb_or_none(&self) -> Option<String> {
        let guard = self.state.lock().unwrap();
        let current = guard.as_ref()?;
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
        if let Some(current) = guard.as_ref() {
            if current.obtained_at.elapsed() <= CRUMB_TTL {
                return Ok(current.crumb.clone());
            }
        }
        bootstrap_cookies(client, user_agent)?;
        let crumb = fetch_crumb(client, user_agent)?;
        *guard = Some(CrumbState {
            crumb: crumb.clone(),
            obtained_at: Instant::now(),
        });
        Ok(crumb)
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
            .send()
        {
            Ok(resp) => {
                let status = resp.status();
                let body = resp.text().unwrap_or_default().trim().to_string();
                if !status.is_success() {
                    last_err = Some(io::Error::other(format!(
                        "HTTP {status} for {url}: {body}"
                    )));
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
