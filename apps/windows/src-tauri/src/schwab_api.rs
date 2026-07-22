// Charles Schwab Market Data API — OAuth2 + quotes.
//
// Flow (manual-paste redirect, so we don't need a local HTTPS server):
//   1. build_auth_url() → user opens it, logs in, authorizes.
//   2. Schwab redirects to the callback with ?code=… ; the user copies that URL.
//   3. complete_auth(redirect_url) extracts the code, exchanges it for tokens.
//   4. valid_access_token() returns a live token, auto-refreshing as needed.
//
// Tokens: access ~30 min (auto-refreshed), refresh ~7 days (then re-auth).
// Schwab is the *preferred* price source; Yahoo/Stooq remain the fallback.

use std::io;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use serde_json::Value;

use crate::db::Db;

const AUTH_URL: &str = "https://api.schwabapi.com/v1/oauth/authorize";
const TOKEN_URL: &str = "https://api.schwabapi.com/v1/oauth/token";
const QUOTES_URL: &str = "https://api.schwabapi.com/marketdata/v1/quotes";
const HTTP_TIMEOUT: Duration = Duration::from_secs(20);
const REFRESH_TOKEN_TTL: i64 = 7 * 24 * 3600; // Schwab refresh tokens last 7 days

#[derive(Debug)]
pub enum SchwabError {
    NotConfigured, // no app key/secret stored
    NotConnected,  // creds set but never authorized
    NeedsReauth,   // refresh token expired → user must log in again
    Network(String),
}

impl std::fmt::Display for SchwabError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SchwabError::NotConfigured => write!(f, "Schwab no configurado"),
            SchwabError::NotConnected => write!(f, "Schwab no conectado"),
            SchwabError::NeedsReauth => write!(f, "Sesión de Schwab expirada — reconectar"),
            SchwabError::Network(e) => write!(f, "Schwab: {e}"),
        }
    }
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn client() -> io::Result<Client> {
    Client::builder()
        .timeout(HTTP_TIMEOUT)
        .build()
        .map_err(io::Error::other)
}

struct TokenSet {
    access_token: String,
    refresh_token: String,
    expires_in: i64,
}

fn parse_token_response(v: &Value) -> Option<TokenSet> {
    Some(TokenSet {
        access_token: v.get("access_token")?.as_str()?.to_string(),
        refresh_token: v.get("refresh_token")?.as_str()?.to_string(),
        expires_in: v.get("expires_in").and_then(|x| x.as_i64()).unwrap_or(1800),
    })
}

// ── Public: authorization URL ──────────────────────────────────────────────────

pub fn build_auth_url(app_key: &str, callback: &str) -> String {
    format!(
        "{AUTH_URL}?client_id={}&redirect_uri={}&response_type=code",
        app_key,
        urlencode(callback)
    )
}

// ── Public: complete auth from the pasted redirect URL ─────────────────────────

pub fn complete_auth(db: &Db, redirect_url: &str) -> Result<(), SchwabError> {
    let auth = db
        .schwab_auth_get()
        .map_err(SchwabError::Network)?
        .ok_or(SchwabError::NotConfigured)?;
    let (app_key, secret, callback) = match (auth.app_key, auth.secret, auth.callback) {
        (Some(k), Some(s), Some(c)) => (k, s, c),
        _ => return Err(SchwabError::NotConfigured),
    };
    let code = extract_code(redirect_url)
        .ok_or_else(|| SchwabError::Network("No se encontró 'code' en la URL pegada".into()))?;

    let body = format!(
        "grant_type=authorization_code&code={}&redirect_uri={}",
        urlencode(&code),
        urlencode(&callback)
    );
    let resp = post_token(&app_key, &secret, body)?;
    let tok = parse_token_response(&resp)
        .ok_or_else(|| SchwabError::Network(format!("Respuesta de token inválida: {resp}")))?;

    let n = now();
    db.schwab_set_tokens(
        &tok.access_token,
        &tok.refresh_token,
        n + tok.expires_in,
        n + REFRESH_TOKEN_TTL,
    )
    .map_err(SchwabError::Network)?;
    Ok(())
}

// ── Public: a valid access token (auto-refresh) ────────────────────────────────

pub fn valid_access_token(db: &Db) -> Result<String, SchwabError> {
    let auth = db
        .schwab_auth_get()
        .map_err(SchwabError::Network)?
        .ok_or(SchwabError::NotConfigured)?;
    let (app_key, secret) = match (auth.app_key, auth.secret) {
        (Some(k), Some(s)) => (k, s),
        _ => return Err(SchwabError::NotConfigured),
    };
    let access = auth.access_token.ok_or(SchwabError::NotConnected)?;
    let refresh = auth.refresh_token.ok_or(SchwabError::NotConnected)?;
    let access_exp = auth.access_expires_at.unwrap_or(0);
    let refresh_exp = auth.refresh_expires_at.unwrap_or(0);
    let n = now();

    if access_exp > n + 30 {
        return Ok(access); // still valid
    }
    if refresh_exp <= n {
        return Err(SchwabError::NeedsReauth);
    }
    // Refresh.
    let body = format!(
        "grant_type=refresh_token&refresh_token={}",
        urlencode(&refresh)
    );
    let resp = post_token(&app_key, &secret, body)?;
    let tok = parse_token_response(&resp)
        .ok_or_else(|| SchwabError::Network(format!("Refresh inválido: {resp}")))?;
    // Refreshing does NOT extend the 7-day window → preserve refresh_exp.
    db.schwab_set_tokens(
        &tok.access_token,
        &tok.refresh_token,
        n + tok.expires_in,
        refresh_exp,
    )
    .map_err(SchwabError::Network)?;
    Ok(tok.access_token)
}

fn post_token(app_key: &str, secret: &str, body: String) -> Result<Value, SchwabError> {
    client()
        .map_err(|e| SchwabError::Network(e.to_string()))?
        .post(TOKEN_URL)
        .basic_auth(app_key, Some(secret))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body(body)
        .send()
        .and_then(|r| r.error_for_status())
        .and_then(|r| r.json::<Value>())
        .map_err(|e| SchwabError::Network(e.to_string()))
}

// ── Public: quote (last price in cents) ────────────────────────────────────────

/// Best-effort last price in cents for `symbol`. Returns None on any failure so
/// callers degrade gracefully to Yahoo/Stooq.
pub fn quote_cents(db: &Db, symbol: &str) -> Option<i64> {
    if crate::fetcher::is_crypto(symbol) {
        return None; // Schwab market data is equities/ETFs/options, not spot crypto
    }
    let token = valid_access_token(db).ok()?;
    let url = format!("{QUOTES_URL}?symbols={}&fields=quote", urlencode(symbol));
    let resp: Value = client()
        .ok()?
        .get(&url)
        .bearer_auth(token)
        .header("Accept", "application/json")
        .send()
        .ok()?
        .error_for_status()
        .ok()?
        .json()
        .ok()?;
    let q = resp.get(symbol)?.get("quote")?;
    // Prefer last trade; fall back to mark/close.
    let price = q
        .get("lastPrice")
        .and_then(|v| v.as_f64())
        .or_else(|| q.get("mark").and_then(|v| v.as_f64()))
        .or_else(|| q.get("closePrice").and_then(|v| v.as_f64()))?;
    if price.is_finite() && price > 0.0 {
        Some((price * 100.0).round() as i64)
    } else {
        None
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

/// Extract and percent-decode the `code` query param from the pasted redirect URL.
fn extract_code(url: &str) -> Option<String> {
    let q = url.split(['?', '#']).nth(1).unwrap_or(url);
    for pair in q.split('&') {
        if let Some(v) = pair.strip_prefix("code=") {
            return Some(percent_decode(v));
        }
    }
    None
}

/// Minimal application/x-www-form-urlencoded encoder for the chars we emit
/// (URLs, codes containing `@`, `/`, `=`, etc.).
fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(h) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(h);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}
