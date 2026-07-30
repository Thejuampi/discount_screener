use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};

use chrono::{DateTime, Datelike, NaiveDate, NaiveDateTime, TimeZone, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::db::Db;

const SECONDS_PER_DAY: i64 = 86_400;
const TIPRANKS_MONTHLY_LIMIT: u16 = 50;
const TIPRANKS_WARNING_AT: u16 = 25;
const TIPRANKS_RATE_PER_MINUTE: usize = 10;
const CACHE_FRESH_SECS: i64 = SECONDS_PER_DAY; // ≤24 hours
const CACHE_AGING_SECS: i64 = 7 * SECONDS_PER_DAY; // ≤7 days
const OBS_CURRENT_SECS: i64 = 30 * SECONDS_PER_DAY;
const OBS_AGING_SECS: i64 = 90 * SECONDS_PER_DAY;
const DEFAULT_MCP_URI: &str = "https://mcp.tipranks.com/mcp/";

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawRating {
    #[serde(default)]
    symbol: Option<String>,
    #[serde(default, alias = "ticker")]
    ticker: Option<String>,
    #[serde(default, alias = "publishedDate", alias = "ratingDate", alias = "date")]
    published_date: Option<String>,
    /// Live TipRanks MCP field for the opinion date (`MM/DD/YYYY`).
    #[serde(default, alias = "recommendationDate")]
    recommendation_date: Option<String>,
    /// Live TipRanks MCP scrape/event timestamp (ISO-like, often without timezone).
    #[serde(default)]
    timestamp: Option<String>,
    // Do not alias convertedPriceTarget onto this field: live payloads include both
    // priceTarget and convertedPriceTarget, and serde rejects duplicate mappings.
    #[serde(default, alias = "targetPrice", alias = "pt")]
    price_target: Option<f64>,
    #[serde(default, alias = "convertedPriceTarget")]
    converted_price_target: Option<f64>,
    #[serde(default, alias = "adjPriceTarget")]
    adj_price_target: Option<f64>,
    #[serde(default, alias = "priceWhenPosted", alias = "stockPrice")]
    price_when_posted: Option<f64>,
    #[serde(
        default,
        alias = "analystName",
        alias = "analyst_name",
        alias = "expertName"
    )]
    analyst_name: Option<String>,
    #[serde(
        default,
        alias = "analystCompany",
        alias = "firm",
        alias = "firmName",
        alias = "company",
        alias = "expertFirmName"
    )]
    analyst_company: Option<String>,
    #[serde(default, alias = "recommendation")]
    rating: Option<String>,
    #[serde(default, alias = "newGrade", alias = "action", alias = "analystAction")]
    new_grade: Option<String>,
    #[serde(default, alias = "previousPriceTarget")]
    previous_price_target: Option<f64>,
    #[serde(default, alias = "targetDate")]
    target_date: Option<String>,
    #[serde(
        default,
        alias = "starRating",
        alias = "stars",
        alias = "numOfStars",
        alias = "expertRating"
    )]
    stars: Option<f64>,
    #[serde(default, alias = "analystRank", alias = "rank", alias = "expertRank")]
    rank: Option<i64>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ForecastObservation {
    pub symbol: String,
    pub analyst: Option<String>,
    pub firm: Option<String>,
    pub issued_at_epoch: i64,
    pub horizon_epoch: i64,
    pub horizon_label: String,
    pub rating: Option<String>,
    pub target_cents: i64,
    pub previous_target_cents: Option<i64>,
    pub price_when_posted_cents: Option<i64>,
    pub source: Option<String>,
    pub identity: Option<String>,
    pub stars_hundredths: Option<i64>,
    pub rank: Option<i64>,
    pub weight_hundredths: Option<i64>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct HistogramBin {
    pub low_cents: i64,
    pub high_cents: i64,
    pub count: usize,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct ForecastSummary {
    observations: Vec<ForecastObservation>,
    identity_count: usize,
    minimum_cents: i64,
    maximum_cents: i64,
    simple_mean_cents: i64,
    weighted_mean_cents: Option<i64>,
    weighting_label: String,
    histogram: Vec<HistogramBin>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForecastPanelState {
    Ready,
    InsufficientCoverage,
    Empty,
    Unloaded,
    MissingKey,
    InvalidKey,
    QuotaExhausted,
    RateLimited,
    ProviderUnavailable,
    NotEligible,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CacheFreshness {
    Fresh,
    Aging,
    Stale,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ObservationFreshness {
    Current,
    Aging,
    Stale,
    Empty,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForecastActionKind {
    None,
    Load,
    Refresh,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ForecastAction {
    pub kind: ForecastActionKind,
    pub enabled: bool,
    pub call_cost: u16,
    pub remaining_after: u16,
    pub label: String,
    pub confirmation_message: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ForecastStatistics {
    pub minimum_cents: i64,
    pub maximum_cents: i64,
    pub simple_mean_cents: i64,
    pub weighted_mean_cents: Option<i64>,
    pub weighting_label: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ForecastPricePoint {
    pub epoch_seconds: i64,
    pub close_cents: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TipRanksQuotaView {
    pub provider_month: String,
    pub attempts: u16,
    pub limit: u16,
    pub remaining: u16,
    pub warning: bool,
    pub exhausted: bool,
    pub estimated: bool,
    pub resets_at_epoch: i64,
    pub retry_after_epoch: Option<i64>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AnalystForecastPanel {
    pub symbol: String,
    pub state: ForecastPanelState,
    pub state_message: String,
    pub observations: Vec<ForecastObservation>,
    pub histogram: Vec<HistogramBin>,
    pub statistics: Option<ForecastStatistics>,
    pub identity_count: usize,
    pub usable_weighted_consensus: bool,
    pub price_history: Vec<ForecastPricePoint>,
    pub fetched_at_epoch: Option<i64>,
    pub latest_observation_epoch: Option<i64>,
    pub cache_freshness: Option<CacheFreshness>,
    pub observation_freshness: ObservationFreshness,
    pub from_cache: bool,
    pub horizon_disclosure: String,
    pub provider_label: String,
    pub quota: TipRanksQuotaView,
    pub action: ForecastAction,
    pub error_banner: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TipRanksSettingsStatus {
    pub configured: bool,
    pub quota: TipRanksQuotaView,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
struct CachedForecastPayload {
    observations: Vec<ForecastObservation>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum ProviderFailure {
    InvalidKey,
    QuotaExhausted,
    RateLimited { retry_after_epoch: Option<i64> },
    Unavailable,
    InvalidPayload,
}

#[derive(Clone, Debug)]
struct ProviderBlock {
    provider_month: String,
    failure: ProviderFailure,
    until_epoch: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct UsageSnapshot {
    used: u16,
    limit: u16,
    remaining: u16,
    resets_at_epoch: i64,
}

trait ForecastProvider: Send + Sync {
    fn fetch_ratings(&self, symbol: &str, api_key: &str)
        -> Result<Vec<RawRating>, ProviderFailure>;
    fn fetch_usage(&self, api_key: &str) -> Result<UsageSnapshot, ProviderFailure>;
}

trait CredentialStore: Send + Sync {
    fn load(&self) -> Result<Option<String>, String>;
    fn save(&self, api_key: &str) -> Result<(), String>;
    fn delete(&self) -> Result<(), String>;
}

trait Clock: Send + Sync {
    fn now(&self) -> DateTime<Utc>;
}

struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> DateTime<Utc> {
        Utc::now()
    }
}

struct RequestGate {
    active: Mutex<usize>,
    ready: Condvar,
    limit: usize,
}

impl RequestGate {
    fn new(limit: usize) -> Self {
        Self {
            active: Mutex::new(0),
            ready: Condvar::new(),
            limit,
        }
    }

    fn acquire(&self) -> RequestPermit<'_> {
        let mut active = self.active.lock().unwrap();
        while *active >= self.limit {
            active = self.ready.wait(active).unwrap();
        }
        *active += 1;
        RequestPermit { gate: self }
    }
}

struct RequestPermit<'a> {
    gate: &'a RequestGate,
}

impl Drop for RequestPermit<'_> {
    fn drop(&mut self) {
        let mut active = self.gate.active.lock().unwrap();
        *active = active.saturating_sub(1);
        self.gate.ready.notify_one();
    }
}

struct RateLimiter {
    stamps: Mutex<VecDeque<i64>>,
}

impl RateLimiter {
    fn new() -> Self {
        Self {
            stamps: Mutex::new(VecDeque::new()),
        }
    }

    fn try_acquire(&self, now_epoch: i64) -> Result<(), Option<i64>> {
        let mut stamps = self.stamps.lock().unwrap();
        while stamps.front().is_some_and(|stamp| now_epoch - *stamp >= 60) {
            stamps.pop_front();
        }
        if stamps.len() >= TIPRANKS_RATE_PER_MINUTE {
            let retry = stamps.front().map(|stamp| *stamp + 60);
            return Err(retry);
        }
        stamps.push_back(now_epoch);
        Ok(())
    }

    #[cfg(test)]
    fn force_fill(&self, now_epoch: i64) {
        let mut stamps = self.stamps.lock().unwrap();
        stamps.clear();
        for _ in 0..TIPRANKS_RATE_PER_MINUTE {
            stamps.push_back(now_epoch);
        }
    }
}

#[derive(Default)]
struct Flight {
    result: Mutex<Option<Result<(CachedForecastPayload, i64), ProviderFailure>>>,
    ready: Condvar,
}

pub struct AnalystForecastService {
    db: Arc<Db>,
    provider: Arc<dyn ForecastProvider>,
    credentials: Arc<dyn CredentialStore>,
    clock: Arc<dyn Clock>,
    flights: Mutex<HashMap<String, Arc<Flight>>>,
    provider_block: Mutex<Option<ProviderBlock>>,
    request_gate: RequestGate,
    rate_limiter: RateLimiter,
}

impl AnalystForecastService {
    pub fn new(db: Arc<Db>) -> Result<Self, String> {
        Ok(Self {
            db,
            provider: Arc::new(TipRanksMcpProvider::new(DEFAULT_MCP_URI.to_string())),
            credentials: Arc::new(WindowsCredentialStore),
            clock: Arc::new(SystemClock),
            flights: Mutex::new(HashMap::new()),
            provider_block: Mutex::new(None),
            request_gate: RequestGate::new(2),
            rate_limiter: RateLimiter::new(),
        })
    }

    #[cfg(test)]
    fn with_dependencies(
        db: Arc<Db>,
        provider: Arc<dyn ForecastProvider>,
        credentials: Arc<dyn CredentialStore>,
    ) -> Self {
        Self {
            db,
            provider,
            credentials,
            clock: Arc::new(SystemClock),
            flights: Mutex::new(HashMap::new()),
            provider_block: Mutex::new(None),
            request_gate: RequestGate::new(2),
            rate_limiter: RateLimiter::new(),
        }
    }

    #[cfg(test)]
    fn with_clock(
        db: Arc<Db>,
        provider: Arc<dyn ForecastProvider>,
        credentials: Arc<dyn CredentialStore>,
        clock: Arc<dyn Clock>,
    ) -> Self {
        Self {
            db,
            provider,
            credentials,
            clock,
            flights: Mutex::new(HashMap::new()),
            provider_block: Mutex::new(None),
            request_gate: RequestGate::new(2),
            rate_limiter: RateLimiter::new(),
        }
    }

    /// Cache-only detail read. Never spends TipRanks quota.
    pub fn get(
        &self,
        symbol: &str,
        price_history: Vec<ForecastPricePoint>,
    ) -> AnalystForecastPanel {
        let symbol = symbol.trim().to_uppercase();
        let month = provider_month(self.clock.now());
        if !valid_symbol(&symbol) {
            return self.failure_panel(
                symbol,
                ForecastPanelState::NotEligible,
                "TipRanks forecasts are available only for eligible stock symbols.",
                price_history,
                &month,
                None,
            );
        }

        match self.db.load_tipranks_forecast_cache(&month.key, &symbol) {
            Ok(Some(record)) => {
                if let Ok(payload) =
                    serde_json::from_str::<CachedForecastPayload>(&record.payload_json)
                {
                    return self.payload_panel(
                        symbol,
                        payload,
                        record.fetched_at_epoch,
                        true,
                        price_history,
                        &month,
                        None,
                    );
                }
            }
            Ok(None) => {}
            Err(_) => {
                return self.failure_panel(
                    symbol,
                    ForecastPanelState::ProviderUnavailable,
                    "The local TipRanks cache is unavailable.",
                    price_history,
                    &month,
                    None,
                );
            }
        }

        match self.credentials.load() {
            Ok(None) => self.failure_panel(
                symbol,
                ForecastPanelState::MissingKey,
                "Configure a TipRanks API key in Settings.",
                price_history,
                &month,
                None,
            ),
            Ok(Some(_)) => {
                let mut panel = self.failure_panel(
                    symbol,
                    ForecastPanelState::Unloaded,
                    "TipRanks analyst targets are not loaded for this symbol yet.",
                    price_history,
                    &month,
                    None,
                );
                panel.action = self.compute_action(
                    ForecastActionKind::Load,
                    true,
                    1,
                    &month,
                    "Load TipRanks analyst targets",
                    Some("Uses 1 TipRanks call.".to_string()),
                );
                panel
            }
            Err(_) => self.failure_panel(
                symbol,
                ForecastPanelState::ProviderUnavailable,
                "Windows Credential Manager is unavailable.",
                price_history,
                &month,
                None,
            ),
        }
    }

    /// Explicit user load/refresh. Counted only when backend action requires it.
    pub fn load(
        &self,
        symbol: &str,
        price_history: Vec<ForecastPricePoint>,
    ) -> AnalystForecastPanel {
        let symbol = symbol.trim().to_uppercase();
        let month = provider_month(self.clock.now());
        if !valid_symbol(&symbol) {
            return self.failure_panel(
                symbol,
                ForecastPanelState::NotEligible,
                "TipRanks forecasts are available only for eligible stock symbols.",
                price_history,
                &month,
                None,
            );
        }

        let existing = self
            .db
            .load_tipranks_forecast_cache(&month.key, &symbol)
            .ok()
            .flatten()
            .and_then(|record| {
                serde_json::from_str::<CachedForecastPayload>(&record.payload_json)
                    .ok()
                    .map(|payload| (payload, record.fetched_at_epoch))
            });

        if let Some((payload, fetched_at)) = existing.as_ref() {
            let freshness = cache_freshness(self.clock.now().timestamp(), *fetched_at);
            if matches!(freshness, CacheFreshness::Fresh | CacheFreshness::Aging) {
                return self.payload_panel(
                    symbol,
                    payload.clone(),
                    *fetched_at,
                    true,
                    price_history,
                    &month,
                    None,
                );
            }
        }

        let prior_cache = existing.clone();
        let api_key = match self.credentials.load() {
            Ok(Some(value)) => value,
            Ok(None) => {
                return self.failure_panel(
                    symbol,
                    ForecastPanelState::MissingKey,
                    "Configure a TipRanks API key in Settings.",
                    price_history,
                    &month,
                    None,
                );
            }
            Err(_) => {
                return self.failure_panel(
                    symbol,
                    ForecastPanelState::ProviderUnavailable,
                    "Windows Credential Manager is unavailable.",
                    price_history,
                    &month,
                    None,
                );
            }
        };

        if let Some(failure) = self.active_provider_block(&month) {
            return self.failure_with_optional_cache(
                symbol,
                failure,
                prior_cache,
                price_history,
                &month,
            );
        }

        let flight_key = format!("{}:{symbol}", month.key);
        let (flight, leader) = {
            let mut flights = self.flights.lock().unwrap();
            if let Some(flight) = flights.get(&flight_key) {
                (Arc::clone(flight), false)
            } else {
                let flight = Arc::new(Flight::default());
                flights.insert(flight_key.clone(), Arc::clone(&flight));
                (flight, true)
            }
        };

        let result = if leader {
            let fetched = self.fetch_and_cache(&symbol, &api_key, &month);
            if let Err(failure) = &fetched {
                self.record_provider_block(&month, failure.clone());
            }
            {
                let mut result = flight.result.lock().unwrap();
                *result = Some(fetched.clone());
                flight.ready.notify_all();
            }
            self.flights.lock().unwrap().remove(&flight_key);
            fetched
        } else {
            let mut result = flight.result.lock().unwrap();
            while result.is_none() {
                result = flight.ready.wait(result).unwrap();
            }
            result.clone().expect("single-flight result is present")
        };

        match result {
            Ok((payload, fetched_at_epoch)) => self.payload_panel(
                symbol,
                payload,
                fetched_at_epoch,
                false,
                price_history,
                &month,
                None,
            ),
            Err(failure) => self.failure_with_optional_cache(
                symbol,
                failure,
                prior_cache,
                price_history,
                &month,
            ),
        }
    }

    pub fn credential_configured(&self) -> Result<bool, String> {
        Ok(self.credentials.load()?.is_some())
    }

    pub fn settings_status(&self) -> Result<TipRanksSettingsStatus, String> {
        let month = provider_month(self.clock.now());
        if let Ok(Some(key)) = self.credentials.load() {
            let _ = self.reconcile_usage(&key, &month);
        }
        Ok(TipRanksSettingsStatus {
            configured: self.credential_configured()?,
            quota: self.quota_view_result(&month)?,
        })
    }

    pub fn save_key(&self, api_key: &str) -> Result<(), String> {
        let value = api_key.trim();
        if value.is_empty() {
            return Err("TipRanks API key cannot be empty".into());
        }
        self.credentials.save(value)?;
        *self.provider_block.lock().unwrap() = None;
        Ok(())
    }

    pub fn delete_key(&self) -> Result<(), String> {
        self.credentials.delete()?;
        *self.provider_block.lock().unwrap() = None;
        Ok(())
    }

    pub fn not_eligible(
        &self,
        symbol: &str,
        price_history: Vec<ForecastPricePoint>,
    ) -> AnalystForecastPanel {
        let month = provider_month(self.clock.now());
        self.failure_panel(
            symbol.trim().to_uppercase(),
            ForecastPanelState::NotEligible,
            "TipRanks forecasts are available only for eligible stock symbols.",
            price_history,
            &month,
            None,
        )
    }

    /// Budgeted credential validation. Bypasses cache intentionally.
    pub fn test_connection(&self, symbol: &str) -> AnalystForecastPanel {
        let symbol = symbol.trim().to_uppercase();
        let month = provider_month(self.clock.now());
        if !valid_symbol(&symbol) {
            return self.failure_panel(
                symbol,
                ForecastPanelState::NotEligible,
                "TipRanks forecasts are available only for eligible stock symbols.",
                vec![],
                &month,
                None,
            );
        }

        let api_key = match self.credentials.load() {
            Ok(Some(value)) => value,
            Ok(None) => {
                return self.failure_panel(
                    symbol,
                    ForecastPanelState::MissingKey,
                    "Configure a TipRanks API key in Settings.",
                    vec![],
                    &month,
                    None,
                );
            }
            Err(_) => {
                return self.failure_panel(
                    symbol,
                    ForecastPanelState::ProviderUnavailable,
                    "Windows Credential Manager is unavailable.",
                    vec![],
                    &month,
                    None,
                );
            }
        };

        if let Some(failure @ (ProviderFailure::InvalidKey | ProviderFailure::QuotaExhausted)) =
            self.active_provider_block(&month)
        {
            return self.failure_from_provider(symbol, failure, vec![], &month, None);
        }

        match self.fetch_and_cache(&symbol, &api_key, &month) {
            Ok((payload, fetched_at_epoch)) => {
                *self.provider_block.lock().unwrap() = None;
                self.payload_panel(
                    symbol,
                    payload,
                    fetched_at_epoch,
                    false,
                    vec![],
                    &month,
                    None,
                )
            }
            Err(failure) => {
                self.record_provider_block(&month, failure.clone());
                self.failure_from_provider(symbol, failure, vec![], &month, None)
            }
        }
    }

    fn fetch_and_cache(
        &self,
        symbol: &str,
        api_key: &str,
        month: &ProviderMonth,
    ) -> Result<(CachedForecastPayload, i64), ProviderFailure> {
        let _permit = self.request_gate.acquire();
        let now = self.clock.now();
        let current_month = provider_month(now);
        if current_month != *month {
            return Err(ProviderFailure::Unavailable);
        }
        if let Err(retry_after) = self.rate_limiter.try_acquire(now.timestamp()) {
            return Err(ProviderFailure::RateLimited {
                retry_after_epoch: retry_after,
            });
        }
        if self
            .db
            .reserve_tipranks_attempt(&month.key, TIPRANKS_MONTHLY_LIMIT)
            .map_err(|_| ProviderFailure::Unavailable)?
            .is_none()
        {
            return Err(ProviderFailure::QuotaExhausted);
        }
        let rows = self.provider.fetch_ratings(symbol, api_key)?;
        drop(_permit);
        let now = self.clock.now();
        let payload = CachedForecastPayload {
            observations: normalize_at(rows, symbol, now.timestamp()),
        };
        let fetched_at_epoch = now.timestamp();
        let json = serde_json::to_string(&payload).map_err(|_| ProviderFailure::InvalidPayload)?;
        self.db
            .save_tipranks_forecast_cache(&month.key, symbol, fetched_at_epoch, &json)
            .map_err(|_| ProviderFailure::Unavailable)?;
        let _ = self.reconcile_usage(api_key, month);
        Ok((payload, fetched_at_epoch))
    }

    fn reconcile_usage(&self, api_key: &str, month: &ProviderMonth) -> Result<(), ProviderFailure> {
        let snap = self.provider.fetch_usage(api_key)?;
        self.db
            .save_tipranks_usage_snapshot(
                &month.key,
                snap.used,
                snap.limit,
                snap.remaining,
                snap.resets_at_epoch,
                self.clock.now().timestamp(),
            )
            .map_err(|_| ProviderFailure::Unavailable)?;
        Ok(())
    }

    fn active_provider_block(&self, month: &ProviderMonth) -> Option<ProviderFailure> {
        let now = self.clock.now().timestamp();
        let mut block = self.provider_block.lock().unwrap();
        match block.as_ref() {
            Some(value) if value.provider_month == month.key && value.until_epoch > now => {
                Some(value.failure.clone())
            }
            Some(_) => {
                *block = None;
                None
            }
            None => None,
        }
    }

    fn record_provider_block(&self, month: &ProviderMonth, failure: ProviderFailure) {
        let until_epoch = match &failure {
            ProviderFailure::InvalidKey | ProviderFailure::QuotaExhausted => month.resets_at_epoch,
            ProviderFailure::RateLimited {
                retry_after_epoch: Some(epoch),
            } => *epoch,
            ProviderFailure::RateLimited { .. }
            | ProviderFailure::Unavailable
            | ProviderFailure::InvalidPayload => self.clock.now().timestamp() + 60,
        };
        *self.provider_block.lock().unwrap() = Some(ProviderBlock {
            provider_month: month.key.clone(),
            failure,
            until_epoch,
        });
    }

    fn failure_with_optional_cache(
        &self,
        symbol: String,
        failure: ProviderFailure,
        prior: Option<(CachedForecastPayload, i64)>,
        price_history: Vec<ForecastPricePoint>,
        month: &ProviderMonth,
    ) -> AnalystForecastPanel {
        let (state, message) = map_failure(&failure);
        if let Some((payload, fetched_at)) = prior {
            let mut panel = self.payload_panel(
                symbol,
                payload,
                fetched_at,
                true,
                price_history,
                month,
                Some(message.to_string()),
            );
            // Keep cached chart/state but surface the provider failure.
            panel.error_banner = Some(message.to_string());
            if state == ForecastPanelState::QuotaExhausted
                || state == ForecastPanelState::InvalidKey
                || state == ForecastPanelState::RateLimited
            {
                panel.state_message = message.to_string();
            }
            return panel;
        }
        self.failure_from_provider(symbol, failure, price_history, month, None)
    }

    fn failure_from_provider(
        &self,
        symbol: String,
        failure: ProviderFailure,
        price_history: Vec<ForecastPricePoint>,
        month: &ProviderMonth,
        error_banner: Option<String>,
    ) -> AnalystForecastPanel {
        let (state, message) = map_failure(&failure);
        let retry = match failure {
            ProviderFailure::RateLimited { retry_after_epoch } => retry_after_epoch,
            _ => None,
        };
        let mut panel =
            self.failure_panel(symbol, state, message, price_history, month, error_banner);
        if retry.is_some() {
            panel.quota.retry_after_epoch = retry;
        }
        panel
    }

    fn payload_panel(
        &self,
        symbol: String,
        payload: CachedForecastPayload,
        fetched_at_epoch: i64,
        from_cache: bool,
        price_history: Vec<ForecastPricePoint>,
        month: &ProviderMonth,
        error_banner: Option<String>,
    ) -> AnalystForecastPanel {
        let now = self.clock.now().timestamp();
        let freshness = cache_freshness(now, fetched_at_epoch);
        let latest = payload
            .observations
            .iter()
            .map(|item| item.issued_at_epoch)
            .max();
        let observation_freshness = observation_freshness(now, latest);
        let action = match freshness {
            CacheFreshness::Stale => self.compute_action(
                ForecastActionKind::Refresh,
                true,
                1,
                month,
                "Refresh stale TipRanks data",
                Some("Uses 1 TipRanks call.".to_string()),
            ),
            CacheFreshness::Fresh | CacheFreshness::Aging => self.compute_action(
                ForecastActionKind::None,
                false,
                0,
                month,
                "Cached TipRanks data",
                None,
            ),
        };

        match summarize(payload.observations) {
            Some(summary) => {
                let usable = summary.weighted_mean_cents.is_some();
                let state = if summary.identity_count >= 3 {
                    ForecastPanelState::Ready
                } else {
                    ForecastPanelState::InsufficientCoverage
                };
                let state_message = if state == ForecastPanelState::Ready {
                    "Individual TipRanks analyst targets.".to_string()
                } else {
                    "Fewer than three distinct analyst or firm identities.".to_string()
                };
                AnalystForecastPanel {
                    symbol,
                    state,
                    state_message,
                    observations: summary.observations,
                    histogram: summary.histogram,
                    statistics: Some(ForecastStatistics {
                        minimum_cents: summary.minimum_cents,
                        maximum_cents: summary.maximum_cents,
                        simple_mean_cents: summary.simple_mean_cents,
                        weighted_mean_cents: summary.weighted_mean_cents,
                        weighting_label: summary.weighting_label,
                    }),
                    identity_count: summary.identity_count,
                    usable_weighted_consensus: usable,
                    price_history,
                    fetched_at_epoch: Some(fetched_at_epoch),
                    latest_observation_epoch: latest,
                    cache_freshness: Some(freshness),
                    observation_freshness,
                    from_cache,
                    horizon_disclosure:
                        "Targets without an explicit date use an assumed 12-month horizon."
                            .to_string(),
                    provider_label: "Data by TipRanks".to_string(),
                    quota: self.quota_view(month),
                    action,
                    error_banner,
                }
            }
            None => AnalystForecastPanel {
                symbol,
                state: ForecastPanelState::Empty,
                state_message: "TipRanks returned no current price-target coverage.".to_string(),
                observations: vec![],
                histogram: vec![],
                statistics: None,
                identity_count: 0,
                usable_weighted_consensus: false,
                price_history,
                fetched_at_epoch: Some(fetched_at_epoch),
                latest_observation_epoch: None,
                cache_freshness: Some(freshness),
                observation_freshness: ObservationFreshness::Empty,
                from_cache,
                horizon_disclosure:
                    "Targets without an explicit date use an assumed 12-month horizon.".to_string(),
                provider_label: "Data by TipRanks".to_string(),
                quota: self.quota_view(month),
                action,
                error_banner,
            },
        }
    }

    fn failure_panel(
        &self,
        symbol: String,
        state: ForecastPanelState,
        message: &str,
        price_history: Vec<ForecastPricePoint>,
        month: &ProviderMonth,
        error_banner: Option<String>,
    ) -> AnalystForecastPanel {
        let action = if state == ForecastPanelState::Unloaded {
            self.compute_action(
                ForecastActionKind::Load,
                true,
                1,
                month,
                "Load TipRanks analyst targets",
                Some("Uses 1 TipRanks call.".to_string()),
            )
        } else if state == ForecastPanelState::MissingKey {
            self.compute_action(
                ForecastActionKind::Load,
                false,
                1,
                month,
                "Load TipRanks analyst targets",
                Some("Configure a TipRanks API key first.".to_string()),
            )
        } else if state == ForecastPanelState::QuotaExhausted {
            self.compute_action(
                ForecastActionKind::Load,
                false,
                1,
                month,
                "Load TipRanks analyst targets",
                Some("Monthly TipRanks budget is exhausted.".to_string()),
            )
        } else {
            ForecastAction {
                kind: ForecastActionKind::None,
                enabled: false,
                call_cost: 0,
                remaining_after: self.quota_view(month).remaining,
                label: String::new(),
                confirmation_message: None,
            }
        };
        AnalystForecastPanel {
            symbol,
            state,
            state_message: message.to_string(),
            observations: vec![],
            histogram: vec![],
            statistics: None,
            identity_count: 0,
            usable_weighted_consensus: false,
            price_history,
            fetched_at_epoch: None,
            latest_observation_epoch: None,
            cache_freshness: None,
            observation_freshness: ObservationFreshness::Empty,
            from_cache: false,
            horizon_disclosure: "Targets without an explicit date use an assumed 12-month horizon."
                .to_string(),
            provider_label: "Data by TipRanks".to_string(),
            quota: self.quota_view(month),
            action,
            error_banner,
        }
    }

    fn compute_action(
        &self,
        kind: ForecastActionKind,
        enabled: bool,
        call_cost: u16,
        month: &ProviderMonth,
        label: &str,
        confirmation_message: Option<String>,
    ) -> ForecastAction {
        let quota = self.quota_view(month);
        let enabled =
            enabled && !quota.exhausted && (call_cost == 0 || quota.remaining >= call_cost);
        let remaining_after = quota.remaining.saturating_sub(call_cost);
        let confirmation_message = confirmation_message.map(|base| {
            if call_cost > 0 {
                format!("{base} Remaining after: {remaining_after}/{}.", quota.limit)
            } else {
                base
            }
        });
        ForecastAction {
            kind,
            enabled,
            call_cost,
            remaining_after,
            label: label.to_string(),
            confirmation_message,
        }
    }

    fn quota_view(&self, month: &ProviderMonth) -> TipRanksQuotaView {
        self.quota_view_result(month)
            .unwrap_or_else(|_| Self::quota_view_for_attempts(month, 0, true, None))
    }

    fn quota_view_result(&self, month: &ProviderMonth) -> Result<TipRanksQuotaView, String> {
        let local = self.db.tipranks_attempts(&month.key)?;
        let reconciled = self.db.load_tipranks_usage_snapshot(&month.key)?;
        match reconciled {
            Some(snap) => {
                let attempts = local.max(snap.used);
                let limit = snap.limit_calls.max(TIPRANKS_MONTHLY_LIMIT);
                let remaining = limit.saturating_sub(attempts).min(snap.remaining);
                Ok(TipRanksQuotaView {
                    provider_month: month.key.clone(),
                    attempts,
                    limit,
                    remaining,
                    warning: attempts >= TIPRANKS_WARNING_AT,
                    exhausted: remaining == 0 || attempts >= limit,
                    estimated: false,
                    resets_at_epoch: snap.resets_at_epoch.max(month.resets_at_epoch),
                    retry_after_epoch: None,
                })
            }
            None => Ok(Self::quota_view_for_attempts(month, local, true, None)),
        }
    }

    fn quota_view_for_attempts(
        month: &ProviderMonth,
        attempts: u16,
        estimated: bool,
        retry_after_epoch: Option<i64>,
    ) -> TipRanksQuotaView {
        TipRanksQuotaView {
            provider_month: month.key.clone(),
            attempts,
            limit: TIPRANKS_MONTHLY_LIMIT,
            remaining: TIPRANKS_MONTHLY_LIMIT.saturating_sub(attempts),
            warning: attempts >= TIPRANKS_WARNING_AT,
            exhausted: attempts >= TIPRANKS_MONTHLY_LIMIT,
            estimated,
            resets_at_epoch: month.resets_at_epoch,
            retry_after_epoch,
        }
    }
}

fn map_failure(failure: &ProviderFailure) -> (ForecastPanelState, &'static str) {
    match failure {
        ProviderFailure::InvalidKey => (
            ForecastPanelState::InvalidKey,
            "The configured TipRanks API key was rejected.",
        ),
        ProviderFailure::QuotaExhausted => (
            ForecastPanelState::QuotaExhausted,
            "The TipRanks monthly request budget is exhausted.",
        ),
        ProviderFailure::RateLimited { .. } => (
            ForecastPanelState::RateLimited,
            "TipRanks rate limit reached. Wait before retrying.",
        ),
        ProviderFailure::Unavailable | ProviderFailure::InvalidPayload => (
            ForecastPanelState::ProviderUnavailable,
            "TipRanks forecasts are temporarily unavailable.",
        ),
    }
}

fn valid_symbol(symbol: &str) -> bool {
    !symbol.is_empty()
        && symbol.len() <= 15
        && symbol
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || matches!(character, '.' | '-'))
}

fn cache_freshness(now_epoch: i64, fetched_at_epoch: i64) -> CacheFreshness {
    let age = now_epoch.saturating_sub(fetched_at_epoch);
    if age <= CACHE_FRESH_SECS {
        CacheFreshness::Fresh
    } else if age <= CACHE_AGING_SECS {
        CacheFreshness::Aging
    } else {
        CacheFreshness::Stale
    }
}

fn observation_freshness(now_epoch: i64, latest: Option<i64>) -> ObservationFreshness {
    let Some(latest) = latest else {
        return ObservationFreshness::Empty;
    };
    let age = now_epoch.saturating_sub(latest);
    if age <= OBS_CURRENT_SECS {
        ObservationFreshness::Current
    } else if age <= OBS_AGING_SECS {
        ObservationFreshness::Aging
    } else {
        ObservationFreshness::Stale
    }
}

/// weight = clamp(1 + 0.15 * (stars - 3), 0.70, 1.30) as hundredths.
pub fn star_weight_hundredths(stars: f64) -> i64 {
    if !stars.is_finite() {
        return 100;
    }
    let raw = 1.0 + 0.15 * (stars - 3.0);
    let clamped = raw.clamp(0.70, 1.30);
    (clamped * 100.0).round() as i64
}

struct TipRanksMcpProvider {
    uri: String,
}

impl TipRanksMcpProvider {
    fn new(uri: String) -> Self {
        Self { uri }
    }

    fn call_tool(
        &self,
        api_key: &str,
        tool_name: &str,
        arguments: Value,
    ) -> Result<Value, ProviderFailure> {
        let uri = self.uri.clone();
        let api_key = api_key.to_string();
        let tool_name = tool_name.to_string();
        let handle = std::thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .map_err(|_| ProviderFailure::Unavailable)?;
            runtime.block_on(async move {
                use rmcp::{
                    model::{
                        CallToolRequestParams, ClientCapabilities, ClientInfo, Implementation,
                    },
                    transport::streamable_http_client::StreamableHttpClientTransportConfig,
                    transport::StreamableHttpClientTransport,
                    ServiceExt,
                };

                let config =
                    StreamableHttpClientTransportConfig::with_uri(uri).auth_header(api_key);
                let transport = StreamableHttpClientTransport::from_config(config);
                let client_info = ClientInfo::new(
                    ClientCapabilities::default(),
                    Implementation::new("vantage-tipranks", "0.1.0"),
                );
                let client = client_info
                    .serve(transport)
                    .await
                    .map_err(|_| ProviderFailure::Unavailable)?;
                let args = arguments.as_object().cloned().unwrap_or_default();
                let result = client
                    .call_tool(CallToolRequestParams::new(tool_name).with_arguments(args))
                    .await
                    .map_err(|error| map_rmcp_error(&error))?;
                let _ = client.cancel().await;
                if result.is_error.unwrap_or(false) {
                    return Err(ProviderFailure::Unavailable);
                }
                if let Some(structured) = result.structured_content {
                    return Ok(unwrap_tool_json_envelope(structured));
                }
                let text = result
                    .content
                    .iter()
                    .filter_map(|block| block.as_text().map(|text| text.text.clone()))
                    .collect::<Vec<_>>()
                    .join("\n");
                if text.trim().is_empty() {
                    return Ok(Value::Null);
                }
                match serde_json::from_str::<Value>(&text) {
                    Ok(parsed) => Ok(unwrap_tool_json_envelope(parsed)),
                    Err(_) => Ok(Value::String(text)),
                }
            })
        });
        handle.join().map_err(|_| ProviderFailure::Unavailable)?
    }
}

/// TipRanks MCP often wraps tool output as `{ "result": "<json string>" }` or a
/// stringified array. Normalize those envelopes before ratings/usage parsing.
fn unwrap_tool_json_envelope(value: Value) -> Value {
    match value {
        Value::String(text) => serde_json::from_str::<Value>(&text)
            .map(unwrap_tool_json_envelope)
            .unwrap_or(Value::String(text)),
        Value::Object(map) => {
            if let Some(inner) = map.get("result").cloned() {
                return unwrap_tool_json_envelope(inner);
            }
            Value::Object(map)
        }
        other => other,
    }
}

fn map_rmcp_error(error: &rmcp::ServiceError) -> ProviderFailure {
    let message = error.to_string().to_lowercase();
    if message.contains("401")
        || message.contains("403")
        || message.contains("unauthorized")
        || message.contains("invalid")
        || message.contains("auth")
    {
        return ProviderFailure::InvalidKey;
    }
    if message.contains("429") || message.contains("rate") {
        return ProviderFailure::RateLimited {
            retry_after_epoch: None,
        };
    }
    if message.contains("quota") || message.contains("limit") {
        return ProviderFailure::QuotaExhausted;
    }
    ProviderFailure::Unavailable
}

impl ForecastProvider for TipRanksMcpProvider {
    fn fetch_ratings(
        &self,
        symbol: &str,
        api_key: &str,
    ) -> Result<Vec<RawRating>, ProviderFailure> {
        let value = self.call_tool(
            api_key,
            "get_recent_analyst_ratings",
            serde_json::json!({ "ticker": symbol, "tickers": symbol, "symbol": symbol }),
        )?;
        parse_ratings_payload(value, symbol)
    }

    fn fetch_usage(&self, api_key: &str) -> Result<UsageSnapshot, ProviderFailure> {
        let value = self.call_tool(api_key, "get_my_usage", serde_json::json!({}))?;
        parse_usage_payload(value)
    }
}

fn parse_ratings_payload(value: Value, symbol: &str) -> Result<Vec<RawRating>, ProviderFailure> {
    let rows = extract_array(value).ok_or(ProviderFailure::InvalidPayload)?;
    let mut out = Vec::with_capacity(rows.len());
    for row in rows {
        match serde_json::from_value::<RawRating>(row) {
            Ok(mut item) => {
                if item.symbol.is_none() && item.ticker.is_none() {
                    item.symbol = Some(symbol.to_string());
                }
                out.push(item);
            }
            Err(_) => continue,
        }
    }
    Ok(out)
}

fn extract_array(value: Value) -> Option<Vec<Value>> {
    let value = unwrap_tool_json_envelope(value);
    match value {
        Value::Array(items) => Some(items),
        Value::Object(map) => {
            for key in [
                "result",
                "ratings",
                "data",
                "items",
                "results",
                "analystRatings",
                "recentRatings",
            ] {
                if let Some(nested) = map.get(key).cloned() {
                    if let Some(items) = extract_array(nested) {
                        return Some(items);
                    }
                }
            }
            // Single-key objects that wrap the array under an unknown name.
            if map.len() == 1 {
                if let Some(nested) = map.into_values().next() {
                    return extract_array(nested);
                }
            }
            None
        }
        Value::String(text) => serde_json::from_str::<Value>(&text)
            .ok()
            .and_then(extract_array),
        _ => None,
    }
}

fn parse_usage_payload(value: Value) -> Result<UsageSnapshot, ProviderFailure> {
    let value = unwrap_tool_json_envelope(value);
    let obj = match value {
        Value::Object(map) => map,
        Value::String(text) => serde_json::from_str::<Value>(&text)
            .ok()
            .and_then(|v| v.as_object().cloned())
            .ok_or(ProviderFailure::InvalidPayload)?,
        _ => return Err(ProviderFailure::InvalidPayload),
    };
    let used = read_u16(
        &obj,
        &["used", "calls_used", "requests_used", "usedThisMonth"],
    )
    .unwrap_or(0);
    let limit = read_u16(
        &obj,
        &["limit", "monthly_limit", "limit_calls", "monthlyLimit"],
    )
    .unwrap_or(TIPRANKS_MONTHLY_LIMIT);
    let remaining = read_u16(&obj, &["remaining", "remaining_calls", "callsRemaining"])
        .unwrap_or(limit.saturating_sub(used));
    let resets_at_epoch = read_i64(
        &obj,
        &["resets_at_epoch", "reset_at", "resetsAt", "resetEpoch"],
    )
    .unwrap_or_else(|| provider_month(Utc::now()).resets_at_epoch);
    Ok(UsageSnapshot {
        used,
        limit,
        remaining,
        resets_at_epoch,
    })
}

fn read_u16(map: &serde_json::Map<String, Value>, keys: &[&str]) -> Option<u16> {
    for key in keys {
        if let Some(value) = map.get(*key) {
            if let Some(n) = value.as_u64() {
                return Some(n.min(u64::from(u16::MAX)) as u16);
            }
            if let Some(n) = value.as_i64() {
                return Some(n.clamp(0, i64::from(u16::MAX)) as u16);
            }
            if let Some(n) = value.as_f64() {
                return Some(n.clamp(0.0, f64::from(u16::MAX)) as u16);
            }
        }
    }
    None
}

fn read_i64(map: &serde_json::Map<String, Value>, keys: &[&str]) -> Option<i64> {
    for key in keys {
        if let Some(value) = map.get(*key) {
            if let Some(n) = value.as_i64() {
                return Some(n);
            }
            if let Some(n) = value.as_u64() {
                return Some(n as i64);
            }
            if let Some(text) = value.as_str() {
                if let Some(epoch) = parse_provider_date(text) {
                    return Some(epoch);
                }
            }
        }
    }
    None
}

struct WindowsCredentialStore;

impl WindowsCredentialStore {
    fn entry() -> Result<keyring::Entry, String> {
        keyring::Entry::new("com.discount-screener.vantage", "tipranks-api-key")
            .map_err(|error| format!("open Windows Credential Manager entry: {error}"))
    }
}

impl CredentialStore for WindowsCredentialStore {
    fn load(&self) -> Result<Option<String>, String> {
        match Self::entry()?.get_password() {
            Ok(value) if !value.trim().is_empty() => Ok(Some(value)),
            Ok(_) | Err(keyring::Error::NoEntry) => Ok(None),
            Err(error) => Err(format!("read TipRanks credential: {error}")),
        }
    }

    fn save(&self, api_key: &str) -> Result<(), String> {
        Self::entry()?
            .set_password(api_key)
            .map_err(|error| format!("save TipRanks credential: {error}"))
    }

    fn delete(&self) -> Result<(), String> {
        match Self::entry()?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(error) => Err(format!("delete TipRanks credential: {error}")),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct ProviderMonth {
    key: String,
    resets_at_epoch: i64,
}

#[cfg(test)]
fn normalize(raw: Vec<RawRating>, requested_symbol: &str) -> Vec<ForecastObservation> {
    normalize_at(raw, requested_symbol, Utc::now().timestamp())
}

fn normalize_at(
    raw: Vec<RawRating>,
    requested_symbol: &str,
    now_epoch: i64,
) -> Vec<ForecastObservation> {
    raw.into_iter()
        .filter_map(|item| {
            let provider_symbol = clean_optional(item.symbol.clone())
                .or_else(|| clean_optional(item.ticker.clone()))
                .map(|symbol| symbol.to_uppercase());
            if provider_symbol
                .as_deref()
                .is_some_and(|symbol| !symbol.eq_ignore_ascii_case(requested_symbol))
            {
                return None;
            }
            let target_cents = item
                .price_target
                .and_then(dollars_to_cents)
                .or_else(|| item.converted_price_target.and_then(dollars_to_cents))
                .or_else(|| item.adj_price_target.and_then(dollars_to_cents))?;
            // Prefer the explicit opinion date over scrape timestamps.
            let issued_at_epoch = [
                item.recommendation_date.as_deref(),
                item.published_date.as_deref(),
                item.timestamp.as_deref(),
            ]
            .into_iter()
            .flatten()
            .find_map(parse_provider_date)?;
            if issued_at_epoch > now_epoch {
                return None;
            }
            let explicit_horizon = item
                .target_date
                .as_deref()
                .and_then(parse_provider_date)
                .filter(|epoch| *epoch > issued_at_epoch);
            let (horizon_epoch, horizon_label) = explicit_horizon
                .map(|epoch| (epoch, "Provider horizon".to_string()))
                .unwrap_or_else(|| {
                    (
                        issued_at_epoch + 365 * SECONDS_PER_DAY,
                        "Assumed 12-month horizon".to_string(),
                    )
                });
            if horizon_epoch <= now_epoch {
                return None;
            }
            let analyst = clean_optional(item.analyst_name);
            let firm = clean_optional(item.analyst_company);
            let identity = match (&analyst, &firm) {
                (Some(analyst), Some(firm)) => Some(format!(
                    "analyst:{}|firm:{}",
                    analyst.to_lowercase(),
                    firm.to_lowercase()
                )),
                (Some(analyst), None) => Some(format!("analyst:{}", analyst.to_lowercase())),
                (None, Some(firm)) => Some(format!("firm:{}", firm.to_lowercase())),
                (None, None) => None,
            };
            let stars_hundredths = item
                .stars
                .filter(|s| s.is_finite())
                .map(|s| (s * 100.0).round() as i64);
            let weight_hundredths = item
                .stars
                .filter(|s| s.is_finite())
                .map(star_weight_hundredths);
            Some(ForecastObservation {
                symbol: provider_symbol.unwrap_or_else(|| requested_symbol.to_uppercase()),
                analyst,
                firm,
                issued_at_epoch,
                horizon_epoch,
                horizon_label,
                rating: clean_optional(item.rating.or(item.new_grade)),
                target_cents,
                previous_target_cents: item.previous_price_target.and_then(dollars_to_cents),
                price_when_posted_cents: item.price_when_posted.and_then(dollars_to_cents),
                source: Some("TipRanks".to_string()),
                identity,
                stars_hundredths,
                rank: item.rank,
                weight_hundredths,
            })
        })
        .collect()
}

fn summarize(observations: Vec<ForecastObservation>) -> Option<ForecastSummary> {
    if observations.is_empty() {
        return None;
    }

    let mut identified = HashMap::<String, ForecastObservation>::new();
    let mut anonymous = Vec::new();
    for item in observations {
        if let Some(identity) = item.identity.clone() {
            match identified.get(&identity) {
                Some(current) if current.issued_at_epoch >= item.issued_at_epoch => {}
                _ => {
                    identified.insert(identity, item);
                }
            }
        } else if !anonymous.contains(&item) {
            anonymous.push(item);
        }
    }
    let identity_count = identified.len();
    let mut observations: Vec<_> = identified.into_values().chain(anonymous).collect();
    observations.sort_by(|left, right| {
        right
            .issued_at_epoch
            .cmp(&left.issued_at_epoch)
            .then_with(|| left.identity.cmp(&right.identity))
    });
    let minimum_cents = observations.iter().map(|item| item.target_cents).min()?;
    let maximum_cents = observations.iter().map(|item| item.target_cents).max()?;
    let total = observations
        .iter()
        .map(|item| i128::from(item.target_cents))
        .sum::<i128>();
    let simple_mean_cents = (total / observations.len() as i128) as i64;
    let histogram = histogram(&observations, minimum_cents, maximum_cents);

    let (weighted_mean_cents, weighting_label) = if identity_count >= 3 {
        let weighted = weighted_mean(&observations);
        (
            weighted,
            if weighted.is_some() {
                "TipRanks stars weight: clamp(1 + 0.15×(stars−3), 0.70, 1.30)".to_string()
            } else {
                "Unavailable: fewer than three weighted identities".to_string()
            },
        )
    } else {
        (
            None,
            "Unavailable: fewer than three distinct analyst identities".to_string(),
        )
    };

    Some(ForecastSummary {
        observations,
        identity_count,
        minimum_cents,
        maximum_cents,
        simple_mean_cents,
        weighted_mean_cents,
        weighting_label,
        histogram,
    })
}

fn weighted_mean(observations: &[ForecastObservation]) -> Option<i64> {
    let mut weighted_total = 0_i128;
    let mut weight_total = 0_i128;
    let mut weighted_identities = 0_usize;
    let mut seen = HashSet::new();
    for item in observations {
        let Some(identity) = item.identity.as_ref() else {
            continue;
        };
        if !seen.insert(identity.clone()) {
            continue;
        }
        let weight = item
            .weight_hundredths
            .or_else(|| {
                item.stars_hundredths
                    .map(|stars| star_weight_hundredths(stars as f64 / 100.0))
            })
            .unwrap_or(100);
        weighted_total += i128::from(item.target_cents) * i128::from(weight);
        weight_total += i128::from(weight);
        weighted_identities += 1;
    }
    if weighted_identities < 3 || weight_total <= 0 {
        return None;
    }
    Some((weighted_total / weight_total) as i64)
}

fn provider_month(now: DateTime<Utc>) -> ProviderMonth {
    let key = format!("{:04}-{:02}", now.year(), now.month());
    let (year, month) = if now.month() == 12 {
        (now.year() + 1, 1)
    } else {
        (now.year(), now.month() + 1)
    };
    let resets = Utc
        .with_ymd_and_hms(year, month, 1, 0, 0, 0)
        .single()
        .expect("month boundary is valid");
    ProviderMonth {
        key,
        resets_at_epoch: resets.timestamp(),
    }
}

fn parse_provider_date(value: &str) -> Option<i64> {
    let value = value.trim();
    if value.is_empty() {
        return None;
    }
    DateTime::parse_from_rfc3339(value)
        .map(|date| date.timestamp())
        .ok()
        .or_else(|| {
            // Live TipRanks timestamps often omit the timezone suffix.
            NaiveDateTime::parse_from_str(value, "%Y-%m-%dT%H:%M:%S%.f")
                .ok()
                .map(|date| date.and_utc().timestamp())
        })
        .or_else(|| {
            NaiveDateTime::parse_from_str(value, "%Y-%m-%dT%H:%M:%S")
                .ok()
                .map(|date| date.and_utc().timestamp())
        })
        .or_else(|| {
            NaiveDateTime::parse_from_str(value, "%Y-%m-%d %H:%M:%S")
                .ok()
                .map(|date| date.and_utc().timestamp())
        })
        .or_else(|| {
            NaiveDate::parse_from_str(value, "%Y-%m-%d")
                .ok()
                .and_then(|date| date.and_hms_opt(0, 0, 0))
                .map(|date| date.and_utc().timestamp())
        })
        .or_else(|| {
            // Live TipRanks recommendationDate: MM/DD/YYYY
            NaiveDate::parse_from_str(value, "%m/%d/%Y")
                .ok()
                .and_then(|date| date.and_hms_opt(0, 0, 0))
                .map(|date| date.and_utc().timestamp())
        })
}

fn clean_optional(value: Option<String>) -> Option<String> {
    value
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

fn dollars_to_cents(value: f64) -> Option<i64> {
    if !value.is_finite() || value <= 0.0 || value > i64::MAX as f64 / 100.0 {
        return None;
    }
    Some((value * 100.0).round() as i64)
}

fn histogram(
    observations: &[ForecastObservation],
    minimum_cents: i64,
    maximum_cents: i64,
) -> Vec<HistogramBin> {
    let bin_count = (observations.len() as f64).sqrt().ceil().clamp(5.0, 12.0) as usize;
    if minimum_cents == maximum_cents {
        return vec![HistogramBin {
            low_cents: minimum_cents,
            high_cents: maximum_cents,
            count: observations.len(),
        }];
    }

    let minimum = i128::from(minimum_cents);
    let maximum = i128::from(maximum_cents);
    let span = maximum - minimum;
    let width = ((span + bin_count as i128 - 1) / bin_count as i128).max(1);
    let mut bins = (0..bin_count)
        .map(|index| {
            let low = minimum + index as i128 * width;
            let high = if index + 1 == bin_count {
                maximum
            } else {
                (low + width - 1).min(maximum)
            };
            HistogramBin {
                low_cents: i64::try_from(low).unwrap_or(minimum_cents),
                high_cents: i64::try_from(high).unwrap_or(maximum_cents),
                count: 0,
            }
        })
        .collect::<Vec<_>>();
    for item in observations {
        let index = ((i128::from(item.target_cents) - minimum) / width)
            .clamp(0, bin_count as i128 - 1) as usize;
        bins[index].count += 1;
    }
    bins
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;
    use std::thread;

    fn raw(analyst: Option<&str>, firm: Option<&str>, published: &str, target: f64) -> RawRating {
        RawRating {
            symbol: Some("AAPL".into()),
            ticker: None,
            published_date: Some(published.into()),
            recommendation_date: None,
            timestamp: None,
            price_target: Some(target),
            converted_price_target: None,
            adj_price_target: None,
            price_when_posted: Some(190.25),
            analyst_name: analyst.map(str::to_string),
            analyst_company: firm.map(str::to_string),
            rating: Some("Buy".into()),
            new_grade: None,
            previous_price_target: Some(200.0),
            target_date: None,
            stars: Some(4.0),
            rank: Some(120),
        }
    }

    #[test]
    fn normalizes_fixed_point_fields_and_assumes_a_twelve_month_horizon() {
        let observations = normalize(
            vec![raw(
                Some("Jane Doe"),
                Some("Example Capital"),
                "2026-07-01 14:30:00",
                225.125,
            )],
            "AAPL",
        );

        assert_eq!(observations.len(), 1);
        let item = &observations[0];
        assert_eq!(item.symbol, "AAPL");
        assert_eq!(item.target_cents, 22_513);
        assert_eq!(item.price_when_posted_cents, Some(19_025));
        assert_eq!(item.previous_target_cents, Some(20_000));
        assert_eq!(
            item.horizon_epoch - item.issued_at_epoch,
            365 * SECONDS_PER_DAY
        );
        assert_eq!(item.horizon_label, "Assumed 12-month horizon");
        assert_eq!(item.stars_hundredths, Some(400));
        assert_eq!(item.weight_hundredths, Some(115));
        assert_eq!(item.rank, Some(120));
        assert_eq!(
            item.identity.as_deref(),
            Some("analyst:jane doe|firm:example capital")
        );
    }

    #[test]
    fn star_weight_formula_clamps_between_0_70_and_1_30() {
        assert_eq!(star_weight_hundredths(1.0), 70);
        assert_eq!(star_weight_hundredths(3.0), 100);
        assert_eq!(star_weight_hundredths(5.0), 130);
        assert_eq!(star_weight_hundredths(4.0), 115);
        assert_eq!(star_weight_hundredths(0.0), 70);
        assert_eq!(star_weight_hundredths(10.0), 130);
    }

    #[test]
    fn weighted_consensus_requires_three_identities() {
        let sparse = summarize(normalize(
            vec![
                raw(Some("A"), Some("F1"), "2026-07-01", 200.0),
                raw(Some("B"), Some("F2"), "2026-07-02", 220.0),
            ],
            "AAPL",
        ))
        .unwrap();
        assert!(sparse.weighted_mean_cents.is_none());

        let ready = summarize(normalize(
            vec![
                {
                    let mut row = raw(Some("A"), Some("F1"), "2026-07-01", 100.0);
                    row.stars = Some(5.0);
                    row
                },
                {
                    let mut row = raw(Some("B"), Some("F2"), "2026-07-02", 200.0);
                    row.stars = Some(3.0);
                    row
                },
                {
                    let mut row = raw(Some("C"), Some("F3"), "2026-07-03", 300.0);
                    row.stars = Some(1.0);
                    row
                },
            ],
            "AAPL",
        ))
        .unwrap();
        assert!(ready.weighted_mean_cents.is_some());
        // weights 1.30, 1.00, 0.70 → mean (100*1.3 + 200*1 + 300*0.7) / 3.0 = 600/3 = 200 dollars? in cents:
        // (10000*130 + 20000*100 + 30000*70) / 300 = (1_300_000 + 2_000_000 + 2_100_000)/300 = 5_400_000/300 = 18000
        assert_eq!(ready.weighted_mean_cents, Some(18_000));
    }

    #[test]
    fn uses_an_explicit_provider_horizon_when_present() {
        let mut item = raw(None, Some("Example Capital"), "2026-07-01", 225.0);
        item.target_date = Some("2027-03-31".into());

        let observations = normalize(vec![item], "AAPL");
        assert_eq!(observations[0].horizon_label, "Provider horizon");
        assert_eq!(
            observations[0].horizon_epoch,
            Utc.with_ymd_and_hms(2027, 3, 31, 0, 0, 0)
                .single()
                .unwrap()
                .timestamp()
        );
    }

    #[test]
    fn drops_invalid_rows_and_keeps_the_latest_target_per_identity() {
        let rows = vec![
            raw(
                Some("Jane Doe"),
                Some("Example Capital"),
                "2026-06-01",
                210.0,
            ),
            raw(
                Some(" Jane Doe "),
                Some("Example Capital"),
                "2026-07-01",
                225.0,
            ),
            raw(None, Some("Other Research"), "2026-06-15", 180.0),
            raw(None, None, "2026-06-20", 205.0),
        ];

        let summary = summarize(normalize(rows, "AAPL")).unwrap();
        assert_eq!(summary.observations.len(), 3);
        assert_eq!(summary.identity_count, 2);
        assert!(summary
            .observations
            .iter()
            .any(|item| item.target_cents == 22_500));
        assert!(!summary
            .observations
            .iter()
            .any(|item| item.target_cents == 21_000));
    }

    #[test]
    fn cache_and_observation_freshness_boundaries() {
        let now = 1_000_000_i64;
        assert_eq!(cache_freshness(now, now), CacheFreshness::Fresh);
        assert_eq!(
            cache_freshness(now, now - CACHE_FRESH_SECS),
            CacheFreshness::Fresh
        );
        assert_eq!(
            cache_freshness(now, now - CACHE_FRESH_SECS - 1),
            CacheFreshness::Aging
        );
        assert_eq!(
            cache_freshness(now, now - CACHE_AGING_SECS - 1),
            CacheFreshness::Stale
        );
        assert_eq!(
            observation_freshness(now, Some(now - OBS_CURRENT_SECS)),
            ObservationFreshness::Current
        );
        assert_eq!(
            observation_freshness(now, Some(now - OBS_CURRENT_SECS - 1)),
            ObservationFreshness::Aging
        );
        assert_eq!(
            observation_freshness(now, Some(now - OBS_AGING_SECS - 1)),
            ObservationFreshness::Stale
        );
        assert_eq!(
            observation_freshness(now, None),
            ObservationFreshness::Empty
        );
    }

    #[test]
    fn provider_month_is_utc_calendar_month() {
        let month = provider_month(
            Utc.with_ymd_and_hms(2026, 7, 29, 23, 59, 59)
                .single()
                .unwrap(),
        );
        assert_eq!(month.key, "2026-07");
        assert_eq!(
            month.resets_at_epoch,
            Utc.with_ymd_and_hms(2026, 8, 1, 0, 0, 0)
                .single()
                .unwrap()
                .timestamp()
        );
    }

    #[derive(Default)]
    struct MemoryCredentials {
        value: Mutex<Option<String>>,
    }

    impl MemoryCredentials {
        fn configured() -> Self {
            Self {
                value: Mutex::new(Some("test-secret".into())),
            }
        }
    }

    impl CredentialStore for MemoryCredentials {
        fn load(&self) -> Result<Option<String>, String> {
            Ok(self.value.lock().unwrap().clone())
        }

        fn save(&self, api_key: &str) -> Result<(), String> {
            *self.value.lock().unwrap() = Some(api_key.to_string());
            Ok(())
        }

        fn delete(&self) -> Result<(), String> {
            *self.value.lock().unwrap() = None;
            Ok(())
        }
    }

    struct FakeProvider {
        calls: AtomicUsize,
        usage_calls: AtomicUsize,
        result: Result<Vec<RawRating>, ProviderFailure>,
        usage: Result<UsageSnapshot, ProviderFailure>,
        delay_ms: u64,
    }

    impl FakeProvider {
        fn successful(rows: Vec<RawRating>) -> Self {
            Self {
                calls: AtomicUsize::new(0),
                usage_calls: AtomicUsize::new(0),
                result: Ok(rows),
                usage: Ok(UsageSnapshot {
                    used: 0,
                    limit: TIPRANKS_MONTHLY_LIMIT,
                    remaining: TIPRANKS_MONTHLY_LIMIT,
                    resets_at_epoch: provider_month(Utc::now()).resets_at_epoch,
                }),
                delay_ms: 0,
            }
        }
    }

    impl ForecastProvider for FakeProvider {
        fn fetch_ratings(
            &self,
            _symbol: &str,
            api_key: &str,
        ) -> Result<Vec<RawRating>, ProviderFailure> {
            assert_eq!(api_key, "test-secret");
            self.calls.fetch_add(1, Ordering::SeqCst);
            if self.delay_ms > 0 {
                thread::sleep(std::time::Duration::from_millis(self.delay_ms));
            }
            self.result.clone()
        }

        fn fetch_usage(&self, api_key: &str) -> Result<UsageSnapshot, ProviderFailure> {
            assert_eq!(api_key, "test-secret");
            self.usage_calls.fetch_add(1, Ordering::SeqCst);
            self.usage.clone()
        }
    }

    struct TestClock {
        now: Mutex<DateTime<Utc>>,
    }

    impl TestClock {
        fn new(now: DateTime<Utc>) -> Self {
            Self {
                now: Mutex::new(now),
            }
        }
    }

    impl Clock for TestClock {
        fn now(&self) -> DateTime<Utc> {
            *self.now.lock().unwrap()
        }
    }

    fn service(
        db: Arc<Db>,
        provider: Arc<FakeProvider>,
        configured: bool,
    ) -> AnalystForecastService {
        let credentials: Arc<dyn CredentialStore> = if configured {
            Arc::new(MemoryCredentials::configured())
        } else {
            Arc::new(MemoryCredentials::default())
        };
        AnalystForecastService::with_dependencies(db, provider, credentials)
    }

    fn service_with_clock(
        db: Arc<Db>,
        provider: Arc<dyn ForecastProvider>,
        clock: Arc<dyn Clock>,
    ) -> AnalystForecastService {
        AnalystForecastService::with_clock(
            db,
            provider,
            Arc::new(MemoryCredentials::configured()),
            clock,
        )
    }

    #[test]
    fn uncached_open_issues_zero_provider_calls_and_exposes_load_action() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let service = service(db, Arc::clone(&provider), true);

        let panel = service.get("AAPL", vec![]);
        assert_eq!(panel.state, ForecastPanelState::Unloaded);
        assert_eq!(panel.action.kind, ForecastActionKind::Load);
        assert!(panel.action.enabled);
        assert_eq!(panel.action.call_cost, 1);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn explicit_load_issues_one_counted_call_and_caches_result() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![
            raw(Some("A"), Some("F1"), "2026-07-01", 200.0),
            raw(Some("B"), Some("F2"), "2026-07-02", 210.0),
            raw(Some("C"), Some("F3"), "2026-07-03", 220.0),
        ]));
        let service = service(db, Arc::clone(&provider), true);

        let first = service.load("aapl", vec![]);
        let reopen = service.get("AAPL", vec![]);

        assert_eq!(first.state, ForecastPanelState::Ready);
        assert!(!first.from_cache);
        assert!(reopen.from_cache);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
        assert_eq!(reopen.quota.attempts, 1);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn fresh_and_aging_cache_reopen_never_calls_provider() {
        let now = Utc
            .with_ymd_and_hms(2026, 7, 29, 12, 0, 0)
            .single()
            .unwrap();
        let clock = Arc::new(TestClock::new(now));
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![raw(
            Some("A"),
            Some("F"),
            "2026-07-01",
            200.0,
        )]));
        let service = service_with_clock(
            Arc::clone(&db),
            Arc::clone(&provider) as Arc<dyn ForecastProvider>,
            Arc::clone(&clock) as Arc<dyn Clock>,
        );
        let loaded = service.load("AAPL", vec![]);
        assert!(!loaded.from_cache);

        let fresh = service.get("AAPL", vec![]);
        assert!(fresh.from_cache);
        assert_eq!(fresh.cache_freshness, Some(CacheFreshness::Fresh));
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);

        // Age the cache into the aging band without a second call.
        let fetched = loaded.fetched_at_epoch.unwrap();
        let month = provider_month(now);
        let payload = db
            .load_tipranks_forecast_cache(&month.key, "AAPL")
            .unwrap()
            .unwrap();
        db.save_tipranks_forecast_cache(
            &month.key,
            "AAPL",
            fetched - CACHE_FRESH_SECS - 60,
            &payload.payload_json,
        )
        .unwrap();
        let aging = service.get("AAPL", vec![]);
        assert_eq!(aging.cache_freshness, Some(CacheFreshness::Aging));
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
        assert!(aging.action.call_cost == 0);
    }

    #[test]
    fn stale_cache_stays_visible_with_refresh_cost_confirmation() {
        let now = Utc
            .with_ymd_and_hms(2026, 7, 29, 12, 0, 0)
            .single()
            .unwrap();
        let clock = Arc::new(TestClock::new(now));
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![raw(
            Some("A"),
            Some("F"),
            "2026-07-01",
            200.0,
        )]));
        let service = service_with_clock(
            Arc::clone(&db),
            Arc::clone(&provider) as Arc<dyn ForecastProvider>,
            Arc::clone(&clock) as Arc<dyn Clock>,
        );
        service.load("AAPL", vec![]);
        let month = provider_month(now);
        let payload = db
            .load_tipranks_forecast_cache(&month.key, "AAPL")
            .unwrap()
            .unwrap();
        db.save_tipranks_forecast_cache(
            &month.key,
            "AAPL",
            now.timestamp() - CACHE_AGING_SECS - 10,
            &payload.payload_json,
        )
        .unwrap();

        let panel = service.get("AAPL", vec![]);
        assert_eq!(panel.cache_freshness, Some(CacheFreshness::Stale));
        assert!(!panel.observations.is_empty());
        assert_eq!(panel.action.kind, ForecastActionKind::Refresh);
        assert_eq!(panel.action.call_cost, 1);
        assert!(panel
            .action
            .confirmation_message
            .as_deref()
            .unwrap_or("")
            .contains("1 TipRanks call"));
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn failed_refresh_keeps_prior_cache_and_error_banner() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let ok = Arc::new(FakeProvider::successful(vec![raw(
            Some("A"),
            Some("F"),
            "2026-07-01",
            225.0,
        )]));
        service(Arc::clone(&db), ok, true).load("AAPL", vec![]);
        let month = provider_month(Utc::now());
        let payload = db
            .load_tipranks_forecast_cache(&month.key, "AAPL")
            .unwrap()
            .unwrap();
        db.save_tipranks_forecast_cache(
            &month.key,
            "AAPL",
            Utc::now().timestamp() - CACHE_AGING_SECS - 10,
            &payload.payload_json,
        )
        .unwrap();

        let failing = Arc::new(FakeProvider {
            calls: AtomicUsize::new(0),
            usage_calls: AtomicUsize::new(0),
            result: Err(ProviderFailure::Unavailable),
            usage: Ok(UsageSnapshot {
                used: 1,
                limit: 50,
                remaining: 49,
                resets_at_epoch: month.resets_at_epoch,
            }),
            delay_ms: 0,
        });
        let service = service(db, Arc::clone(&failing), true);
        let panel = service.load("AAPL", vec![]);
        assert_eq!(panel.observations[0].target_cents, 22_500);
        assert!(panel.from_cache);
        assert!(panel.error_banner.is_some());
        assert_eq!(failing.calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn missing_key_and_exhausted_budget_never_call_the_provider() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let missing = service(Arc::clone(&db), Arc::clone(&provider), false);
        assert_eq!(
            missing.get("AAPL", vec![]).state,
            ForecastPanelState::MissingKey
        );
        assert_eq!(
            missing.load("AAPL", vec![]).state,
            ForecastPanelState::MissingKey
        );

        let month = provider_month(Utc::now());
        for _ in 0..TIPRANKS_MONTHLY_LIMIT {
            db.reserve_tipranks_attempt(&month.key, TIPRANKS_MONTHLY_LIMIT)
                .unwrap();
        }
        let exhausted = service(db, Arc::clone(&provider), true);
        assert_eq!(
            exhausted.load("MSFT", vec![]).state,
            ForecastPanelState::QuotaExhausted
        );
        assert_eq!(provider.calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn warning_threshold_fires_at_25_of_50() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let month = provider_month(Utc::now());
        for _ in 0..(TIPRANKS_WARNING_AT - 1) {
            db.reserve_tipranks_attempt(&month.key, TIPRANKS_MONTHLY_LIMIT)
                .unwrap();
        }
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let service = service(db, provider, true);
        let panel = service.load("AAPL", vec![]);
        assert_eq!(panel.quota.attempts, TIPRANKS_WARNING_AT);
        assert!(panel.quota.warning);
        assert_eq!(panel.quota.limit, TIPRANKS_MONTHLY_LIMIT);
    }

    #[test]
    fn external_usage_reconciliation_uses_stricter_remaining() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let month = provider_month(Utc::now());
        db.reserve_tipranks_attempt(&month.key, TIPRANKS_MONTHLY_LIMIT)
            .unwrap();
        let provider = Arc::new(FakeProvider {
            calls: AtomicUsize::new(0),
            usage_calls: AtomicUsize::new(0),
            result: Ok(vec![]),
            usage: Ok(UsageSnapshot {
                used: 30,
                limit: 50,
                remaining: 20,
                resets_at_epoch: month.resets_at_epoch,
            }),
            delay_ms: 0,
        });
        let service = service(db, provider, true);
        let status = service.settings_status().unwrap();
        assert_eq!(status.quota.attempts, 30);
        assert_eq!(status.quota.remaining, 20);
        assert!(!status.quota.estimated);
        assert!(status.quota.warning);
    }

    #[test]
    fn rate_limit_blocks_before_an_eleventh_call_in_one_minute() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let service = service(db, Arc::clone(&provider), true);
        service.rate_limiter.force_fill(Utc::now().timestamp());
        let panel = service.load("AAPL", vec![]);
        assert_eq!(panel.state, ForecastPanelState::RateLimited);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn overlapping_load_requests_share_one_provider_call() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider {
            calls: AtomicUsize::new(0),
            usage_calls: AtomicUsize::new(0),
            result: Ok(vec![raw(Some("Jane"), Some("Firm"), "2026-07-01", 225.0)]),
            usage: Ok(UsageSnapshot {
                used: 1,
                limit: 50,
                remaining: 49,
                resets_at_epoch: provider_month(Utc::now()).resets_at_epoch,
            }),
            delay_ms: 80,
        });
        let service = Arc::new(service(db, Arc::clone(&provider), true));
        let left = {
            let service = Arc::clone(&service);
            thread::spawn(move || service.load("AAPL", vec![]))
        };
        let right = {
            let service = Arc::clone(&service);
            thread::spawn(move || service.load("AAPL", vec![]))
        };
        left.join().unwrap();
        right.join().unwrap();
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn previous_month_cache_is_never_presented() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        db.save_tipranks_forecast_cache(
            "2026-06",
            "AAPL",
            1,
            &serde_json::to_string(&CachedForecastPayload {
                observations: normalize(
                    vec![raw(Some("A"), Some("F"), "2026-06-01", 200.0)],
                    "AAPL",
                ),
            })
            .unwrap(),
        )
        .unwrap();
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let service = service(db, provider, true);
        let panel = service.get("AAPL", vec![]);
        assert_eq!(panel.state, ForecastPanelState::Unloaded);
        assert!(panel.observations.is_empty());
    }

    #[test]
    fn credential_status_serialization_never_contains_the_secret() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let service = service(db, provider, true);
        let json = serde_json::to_string(&service.settings_status().unwrap()).unwrap();
        assert!(json.contains("\"configured\":true"));
        assert!(!json.contains("test-secret"));
        assert!(!json.to_lowercase().contains("api_key"));
    }

    #[test]
    fn settings_status_propagates_budget_database_failures() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let service = service(Arc::clone(&db), provider, true);
        db.drop_tipranks_budget_table_for_test();
        assert!(service.settings_status().is_err());
    }

    #[test]
    fn parses_tipranks_payload_shapes() {
        let nested = serde_json::json!({
            "ratings": [{
                "ticker": "AAPL",
                "analystName": "Jane",
                "firm": "Alpha",
                "priceTarget": 210.5,
                "date": "2026-07-01",
                "stars": 4.5,
                "rank": 42,
                "rating": "Buy"
            }]
        });
        let rows = parse_ratings_payload(nested, "AAPL").unwrap();
        let obs = normalize(rows, "AAPL");
        assert_eq!(obs.len(), 1);
        assert_eq!(obs[0].target_cents, 21_050);
        assert_eq!(obs[0].rank, Some(42));
        assert_eq!(obs[0].weight_hundredths, Some(123));
    }

    #[test]
    fn parses_live_mcp_structured_result_string_envelope() {
        // Live TipRanks MCP returns structuredContent as:
        // { "result": "<json string of rating array>" } with live field names.
        // Live rows include BOTH priceTarget and convertedPriceTarget.
        let row = serde_json::json!({
            "analystName": "Jane Doe",
            "firmName": "Example Capital",
            "recommendation": "Buy",
            "recommendationDate": "07/01/2026",
            "priceTarget": 225.0,
            "convertedPriceTarget": 225.0,
            "numOfStars": 4.0,
            "analystRank": 120,
            "ticker": "aapl",
            "timestamp": "2026-07-02T09:30:14.797",
            "analystAction": "reiterated"
        });
        let envelope = serde_json::json!({
            "result": serde_json::to_string(&vec![row]).unwrap()
        });
        let rows = parse_ratings_payload(envelope, "AAPL").unwrap();
        let now = Utc
            .with_ymd_and_hms(2026, 7, 15, 0, 0, 0)
            .single()
            .unwrap()
            .timestamp();
        let obs = normalize_at(rows, "AAPL", now);
        assert_eq!(obs.len(), 1);
        assert_eq!(obs[0].symbol, "AAPL");
        assert_eq!(obs[0].target_cents, 22_500);
        assert_eq!(obs[0].analyst.as_deref(), Some("Jane Doe"));
        assert_eq!(obs[0].firm.as_deref(), Some("Example Capital"));
        assert_eq!(obs[0].rating.as_deref(), Some("Buy"));
        assert_eq!(obs[0].stars_hundredths, Some(400));
        assert_eq!(obs[0].weight_hundredths, Some(115));
        assert_eq!(obs[0].rank, Some(120));
        assert_eq!(
            obs[0].issued_at_epoch,
            Utc.with_ymd_and_hms(2026, 7, 1, 0, 0, 0)
                .single()
                .unwrap()
                .timestamp()
        );
    }

    #[test]
    fn parses_live_mcp_text_content_array_with_mm_dd_yyyy_dates() {
        let payload = serde_json::json!([{
            "analystName": "Alex",
            "firmName": "Beta Research",
            "recommendation": "Hold",
            "recommendationDate": "06/15/2026",
            "priceTarget": 180.25,
            "numOfStars": 3.5,
            "analystRank": 55,
            "ticker": "MSFT"
        }]);
        let rows = parse_ratings_payload(payload, "MSFT").unwrap();
        let obs = normalize_at(
            rows,
            "MSFT",
            Utc.with_ymd_and_hms(2026, 7, 1, 0, 0, 0)
                .single()
                .unwrap()
                .timestamp(),
        );
        assert_eq!(obs.len(), 1);
        assert_eq!(obs[0].target_cents, 18_025);
        assert_eq!(
            obs[0].issued_at_epoch,
            Utc.with_ymd_and_hms(2026, 6, 15, 0, 0, 0)
                .single()
                .unwrap()
                .timestamp()
        );
        assert_eq!(obs[0].weight_hundredths, Some(108));
    }

    #[test]
    fn unwraps_double_encoded_tool_result_envelope() {
        let array = serde_json::json!([{
            "ticker": "TSLA",
            "analystName": "Pat",
            "firmName": "Gamma",
            "recommendation": "Buy",
            "recommendationDate": "05/01/2026",
            "priceTarget": 300.0,
            "numOfStars": 5.0,
            "analystRank": 10
        }]);
        let double_wrapped = serde_json::json!({
            "result": serde_json::json!({
                "result": serde_json::to_string(&array).unwrap()
            })
        });
        let unwrapped = unwrap_tool_json_envelope(double_wrapped);
        assert!(unwrapped.is_array());
        let rows = parse_ratings_payload(unwrapped, "TSLA").unwrap();
        let obs = normalize_at(
            rows,
            "TSLA",
            Utc.with_ymd_and_hms(2026, 6, 1, 0, 0, 0)
                .single()
                .unwrap()
                .timestamp(),
        );
        assert_eq!(obs.len(), 1);
        assert_eq!(obs[0].target_cents, 30_000);
    }

    #[test]
    fn uses_converted_price_target_when_native_target_missing() {
        let payload = serde_json::json!([{
            "analystName": "Sam",
            "firmName": "Delta",
            "recommendation": "Buy",
            "recommendationDate": "07/01/2026",
            "priceTarget": null,
            "convertedPriceTarget": 250.5,
            "numOfStars": 4.0,
            "ticker": "JPM"
        }]);
        let rows = parse_ratings_payload(payload, "JPM").unwrap();
        let obs = normalize_at(
            rows,
            "JPM",
            Utc.with_ymd_and_hms(2026, 7, 10, 0, 0, 0)
                .single()
                .unwrap()
                .timestamp(),
        );
        assert_eq!(obs.len(), 1);
        assert_eq!(obs[0].target_cents, 25_050);
    }

    #[test]
    fn parse_provider_date_accepts_live_tipranks_formats() {
        assert_eq!(
            parse_provider_date("07/28/2026"),
            Some(
                Utc.with_ymd_and_hms(2026, 7, 28, 0, 0, 0)
                    .single()
                    .unwrap()
                    .timestamp()
            )
        );
        assert_eq!(
            parse_provider_date("2026-07-29T09:30:14.797"),
            Some(
                NaiveDateTime::parse_from_str("2026-07-29T09:30:14.797", "%Y-%m-%dT%H:%M:%S%.f")
                    .unwrap()
                    .and_utc()
                    .timestamp()
            )
        );
        assert_eq!(
            parse_provider_date("2026-07-01 14:30:00"),
            Some(
                Utc.with_ymd_and_hms(2026, 7, 1, 14, 30, 0)
                    .single()
                    .unwrap()
                    .timestamp()
            )
        );
    }

    #[test]
    fn computes_integer_statistics_and_histogram_bins() {
        let rows = [100.0, 110.0, 120.0, 130.0, 140.0, 150.0]
            .into_iter()
            .enumerate()
            .map(|(index, target)| {
                raw(
                    Some(&format!("Analyst {index}")),
                    Some("Firm"),
                    &format!("2026-07-{:02}", index + 1),
                    target,
                )
            })
            .collect();
        let summary = summarize(normalize(rows, "AAPL")).unwrap();
        assert_eq!(summary.minimum_cents, 10_000);
        assert_eq!(summary.maximum_cents, 15_000);
        assert_eq!(summary.simple_mean_cents, 12_500);
        assert_eq!(
            summary.histogram.iter().map(|bin| bin.count).sum::<usize>(),
            6
        );
    }

    fn resolve_live_tipranks_api_key() -> Option<String> {
        if let Ok(value) = std::env::var("TIPRANKS_API_KEY") {
            let trimmed = value.trim();
            if !trimmed.is_empty() {
                return Some(trimmed.to_string());
            }
        }
        // Windows: cargo may run without User-scope env injected into the process.
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            let output = std::process::Command::new("powershell")
                .args([
                    "-NoProfile",
                    "-Command",
                    "[Environment]::GetEnvironmentVariable('TIPRANKS_API_KEY','User')",
                ])
                .creation_flags(CREATE_NO_WINDOW)
                .output()
                .ok()?;
            if !output.status.success() {
                return None;
            }
            let value = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if value.is_empty() {
                None
            } else {
                Some(value)
            }
        }
        #[cfg(not(windows))]
        {
            None
        }
    }

    #[test]
    #[ignore = "requires TIPRANKS_API_KEY"]
    fn opt_in_live_contract_covers_five_distinct_symbols() {
        let api_key = resolve_live_tipranks_api_key()
            .expect("TIPRANKS_API_KEY is required for the ignored live test");
        assert!(
            !api_key.is_empty() && api_key.len() >= 8,
            "TIPRANKS_API_KEY looks empty or too short"
        );
        let provider = TipRanksMcpProvider::new(DEFAULT_MCP_URI.to_string());
        let now = Utc::now().timestamp();
        for symbol in ["AAPL", "MSFT", "ACGL", "TSLA", "JPM"] {
            let rows = provider
                .fetch_ratings(symbol, &api_key)
                .unwrap_or_else(|failure| {
                    panic!("live TipRanks contract failed for {symbol}: {failure:?}")
                });
            let normalized = normalize_at(rows, symbol, now);
            assert!(
                !normalized.is_empty(),
                "live TipRanks contract returned no usable observations for {symbol}"
            );
            assert!(normalized.iter().all(|item| {
                item.symbol.eq_ignore_ascii_case(symbol)
                    && item.target_cents > 0
                    && item.horizon_epoch > now
            }));
        }
    }
}
