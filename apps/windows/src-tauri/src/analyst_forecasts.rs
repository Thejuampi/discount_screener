use std::collections::HashMap;
use std::collections::HashSet;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};

use chrono::{DateTime, Datelike, NaiveDate, NaiveDateTime, TimeZone, Timelike, Utc};
use chrono_tz::America::New_York;
use serde::{Deserialize, Serialize};

use crate::db::Db;

const SECONDS_PER_DAY: i64 = 86_400;
const FMP_DAILY_LIMIT: u16 = 250;
const FMP_WARNING_AT: u16 = 125;

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawForecast {
    symbol: Option<String>,
    published_date: Option<String>,
    price_target: Option<f64>,
    adj_price_target: Option<f64>,
    price_when_posted: Option<f64>,
    analyst_name: Option<String>,
    analyst_company: Option<String>,
    news_publisher: Option<String>,
    rating: Option<String>,
    new_grade: Option<String>,
    previous_price_target: Option<f64>,
    target_date: Option<String>,
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
    histogram: Vec<HistogramBin>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForecastPanelState {
    Ready,
    InsufficientCoverage,
    Empty,
    MissingKey,
    InvalidKey,
    QuotaExhausted,
    ProviderUnavailable,
    NotEligible,
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
pub struct FmpQuotaView {
    pub provider_day: String,
    pub attempts: u16,
    pub limit: u16,
    pub remaining: u16,
    pub warning: bool,
    pub exhausted: bool,
    pub resets_at_epoch: i64,
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
    pub from_cache: bool,
    pub horizon_disclosure: String,
    pub provider_label: String,
    pub quota: FmpQuotaView,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct FmpSettingsStatus {
    pub configured: bool,
    pub quota: FmpQuotaView,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
struct CachedForecastPayload {
    observations: Vec<ForecastObservation>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum ProviderFailure {
    InvalidKey,
    QuotaExhausted,
    Unavailable,
    InvalidPayload,
    Cancelled,
    Rollover,
}

#[derive(Clone, Debug)]
struct ProviderBlock {
    provider_day: String,
    failure: ProviderFailure,
    until_epoch: i64,
}

trait ForecastProvider: Send + Sync {
    fn fetch(&self, symbol: &str, api_key: &str) -> Result<Vec<RawForecast>, ProviderFailure>;
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

#[derive(Clone)]
struct GenerationGuard {
    active_generation: Arc<AtomicU64>,
    expected_generation: u64,
}

impl GenerationGuard {
    fn is_current(&self) -> bool {
        self.active_generation.load(Ordering::SeqCst) == self.expected_generation
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

    fn acquire(&self, generation: Option<&GenerationGuard>) -> Option<RequestPermit<'_>> {
        let mut active = self.active.lock().unwrap();
        loop {
            if generation.is_some_and(|guard| !guard.is_current()) {
                return None;
            }
            if *active < self.limit {
                *active += 1;
                return Some(RequestPermit { gate: self });
            }
            let waited = self
                .ready
                .wait_timeout(active, std::time::Duration::from_millis(20))
                .unwrap();
            active = waited.0;
        }
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
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WarmCandidate {
    pub symbol: String,
    pub composite_score: i32,
    pub is_stock: bool,
}

pub fn rank_warm_candidates(candidates: Vec<WarmCandidate>) -> Vec<String> {
    let mut candidates = candidates
        .into_iter()
        .filter(|candidate| candidate.is_stock)
        .collect::<Vec<_>>();
    candidates.sort_by(|left, right| {
        right
            .composite_score
            .cmp(&left.composite_score)
            .then_with(|| left.symbol.cmp(&right.symbol))
    });
    let mut seen = HashSet::new();
    candidates
        .into_iter()
        .filter_map(|candidate| {
            let symbol = candidate.symbol.trim().to_uppercase();
            if valid_symbol(&symbol) && seen.insert(symbol.clone()) {
                Some(symbol)
            } else {
                None
            }
        })
        .take(10)
        .collect()
}

impl AnalystForecastService {
    pub fn new(db: Arc<Db>) -> Result<Self, String> {
        Ok(Self {
            db,
            provider: Arc::new(FmpRestProvider::new()?),
            credentials: Arc::new(WindowsCredentialStore),
            clock: Arc::new(SystemClock),
            flights: Mutex::new(HashMap::new()),
            provider_block: Mutex::new(None),
            request_gate: RequestGate::new(2),
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
        }
    }

    pub fn get(
        &self,
        symbol: &str,
        price_history: Vec<ForecastPricePoint>,
    ) -> AnalystForecastPanel {
        self.get_inner(symbol, price_history, None)
    }

    fn get_for_generation(
        &self,
        symbol: &str,
        generation: GenerationGuard,
    ) -> AnalystForecastPanel {
        self.get_inner(symbol, vec![], Some(generation))
    }

    fn get_inner(
        &self,
        symbol: &str,
        price_history: Vec<ForecastPricePoint>,
        generation: Option<GenerationGuard>,
    ) -> AnalystForecastPanel {
        let symbol = symbol.trim().to_uppercase();
        let day = provider_day(self.clock.now());
        if !valid_symbol(&symbol) {
            return self.failure_panel(
                symbol,
                ForecastPanelState::NotEligible,
                "FMP forecasts are available only for eligible stock symbols.",
                price_history,
                &day,
            );
        }

        match self.db.load_fmp_forecast_cache(&day.key, &symbol) {
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
                        &day,
                    );
                }
            }
            Ok(None) => {}
            Err(_) => {
                return self.failure_panel(
                    symbol,
                    ForecastPanelState::ProviderUnavailable,
                    "The local FMP cache is unavailable.",
                    price_history,
                    &day,
                )
            }
        }

        let api_key = match self.credentials.load() {
            Ok(Some(value)) => value,
            Ok(None) => {
                return self.failure_panel(
                    symbol,
                    ForecastPanelState::MissingKey,
                    "Configure an FMP API key in Settings.",
                    price_history,
                    &day,
                )
            }
            Err(_) => {
                return self.failure_panel(
                    symbol,
                    ForecastPanelState::ProviderUnavailable,
                    "Windows Credential Manager is unavailable.",
                    price_history,
                    &day,
                )
            }
        };
        if let Some(failure) = self.active_provider_block(&day) {
            return self.failure_from_provider(symbol, failure, price_history, &day);
        }

        let flight_key = format!("{}:{symbol}", day.key);
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
            let fetched = self.fetch_and_cache(&symbol, &api_key, &day, generation.as_ref());
            if let Err(failure) = &fetched {
                if !matches!(
                    failure,
                    ProviderFailure::Cancelled | ProviderFailure::Rollover
                ) {
                    self.record_provider_block(&day, failure.clone());
                }
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
                &day,
            ),
            Err(ProviderFailure::Rollover) => self.get_inner(&symbol, price_history, generation),
            Err(failure) => self.failure_from_provider(symbol, failure, price_history, &day),
        }
    }

    pub fn credential_configured(&self) -> Result<bool, String> {
        Ok(self.credentials.load()?.is_some())
    }

    pub fn settings_status(&self) -> Result<FmpSettingsStatus, String> {
        let day = provider_day(self.clock.now());
        Ok(FmpSettingsStatus {
            configured: self.credential_configured()?,
            quota: self.quota_view_result(&day)?,
        })
    }

    pub fn save_key(&self, api_key: &str) -> Result<(), String> {
        let value = api_key.trim();
        if value.is_empty() {
            return Err("FMP API key cannot be empty".into());
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
        let day = provider_day(self.clock.now());
        self.failure_panel(
            symbol.trim().to_uppercase(),
            ForecastPanelState::NotEligible,
            "FMP forecasts are available only for eligible stock symbols.",
            price_history,
            &day,
        )
    }

    /// Validate the currently stored credential with one real, budgeted
    /// provider request. This intentionally bypasses the symbol cache: cached
    /// licensed data proves nothing about a newly saved key.
    pub fn test_connection(&self, symbol: &str) -> AnalystForecastPanel {
        let symbol = symbol.trim().to_uppercase();
        if !valid_symbol(&symbol) {
            let day = provider_day(self.clock.now());
            return self.failure_panel(
                symbol,
                ForecastPanelState::NotEligible,
                "FMP forecasts are available only for eligible stock symbols.",
                vec![],
                &day,
            );
        }

        loop {
            let day = provider_day(self.clock.now());
            let api_key = match self.credentials.load() {
                Ok(Some(value)) => value,
                Ok(None) => {
                    return self.failure_panel(
                        symbol,
                        ForecastPanelState::MissingKey,
                        "Configure an FMP API key in Settings.",
                        vec![],
                        &day,
                    );
                }
                Err(_) => {
                    return self.failure_panel(
                        symbol,
                        ForecastPanelState::ProviderUnavailable,
                        "Windows Credential Manager is unavailable.",
                        vec![],
                        &day,
                    );
                }
            };
            if let Some(failure @ (ProviderFailure::InvalidKey | ProviderFailure::QuotaExhausted)) =
                self.active_provider_block(&day)
            {
                return self.failure_from_provider(symbol, failure, vec![], &day);
            }

            match self.fetch_and_cache(&symbol, &api_key, &day, None) {
                Ok((payload, fetched_at_epoch)) => {
                    *self.provider_block.lock().unwrap() = None;
                    return self.payload_panel(
                        symbol,
                        payload,
                        fetched_at_epoch,
                        false,
                        vec![],
                        &day,
                    );
                }
                Err(ProviderFailure::Rollover) => continue,
                Err(failure) => {
                    self.record_provider_block(&day, failure.clone());
                    return self.failure_from_provider(symbol, failure, vec![], &day);
                }
            }
        }
    }

    pub fn spawn_generation_warm(
        self: &Arc<Self>,
        symbols: Vec<String>,
        feed_generation: Arc<AtomicU64>,
        generation: u64,
    ) {
        if symbols.is_empty() {
            return;
        }
        let service = Arc::clone(self);
        let _ = std::thread::Builder::new()
            .name("fmp-top10-warm".into())
            .spawn(move || {
                let symbols = Arc::new(symbols);
                let cursor = Arc::new(AtomicUsize::new(0));
                let mut workers = Vec::new();
                for worker_index in 0..2 {
                    let service = Arc::clone(&service);
                    let symbols = Arc::clone(&symbols);
                    let cursor = Arc::clone(&cursor);
                    let feed_generation = Arc::clone(&feed_generation);
                    if let Ok(worker) = std::thread::Builder::new()
                        .name(format!("fmp-warm-{worker_index}"))
                        .spawn(move || loop {
                            if feed_generation.load(Ordering::SeqCst) != generation {
                                return;
                            }
                            let index = cursor.fetch_add(1, Ordering::Relaxed);
                            if index >= symbols.len() {
                                return;
                            }
                            let guard = GenerationGuard {
                                active_generation: Arc::clone(&feed_generation),
                                expected_generation: generation,
                            };
                            let _ = service.get_for_generation(&symbols[index], guard);
                        })
                    {
                        workers.push(worker);
                    }
                }
                for worker in workers {
                    let _ = worker.join();
                }
            });
    }

    fn fetch_and_cache(
        &self,
        symbol: &str,
        api_key: &str,
        day: &ProviderDay,
        generation: Option<&GenerationGuard>,
    ) -> Result<(CachedForecastPayload, i64), ProviderFailure> {
        let _permit = self
            .request_gate
            .acquire(generation)
            .ok_or(ProviderFailure::Cancelled)?;
        if generation.is_some_and(|guard| !guard.is_current()) {
            return Err(ProviderFailure::Cancelled);
        }
        let current_day = provider_day(self.clock.now());
        if current_day != *day {
            return Err(ProviderFailure::Rollover);
        }
        if self
            .db
            .reserve_fmp_attempt(&day.key, FMP_DAILY_LIMIT)
            .map_err(|_| ProviderFailure::Unavailable)?
            .is_none()
        {
            return Err(ProviderFailure::QuotaExhausted);
        }
        let rows = self.provider.fetch(symbol, api_key)?;
        drop(_permit);
        let now = self.clock.now();
        let payload = CachedForecastPayload {
            observations: normalize_at(rows, symbol, now.timestamp()),
        };
        let fetched_at_epoch = now.timestamp();
        let json = serde_json::to_string(&payload).map_err(|_| ProviderFailure::InvalidPayload)?;
        self.db
            .save_fmp_forecast_cache(&day.key, symbol, fetched_at_epoch, &json)
            .map_err(|_| ProviderFailure::Unavailable)?;
        Ok((payload, fetched_at_epoch))
    }

    fn active_provider_block(&self, day: &ProviderDay) -> Option<ProviderFailure> {
        let now = self.clock.now().timestamp();
        let mut block = self.provider_block.lock().unwrap();
        match block.as_ref() {
            Some(value) if value.provider_day == day.key && value.until_epoch > now => {
                Some(value.failure.clone())
            }
            Some(_) => {
                *block = None;
                None
            }
            None => None,
        }
    }

    fn record_provider_block(&self, day: &ProviderDay, failure: ProviderFailure) {
        let until_epoch = match failure {
            ProviderFailure::InvalidKey | ProviderFailure::QuotaExhausted => day.resets_at_epoch,
            ProviderFailure::Unavailable | ProviderFailure::InvalidPayload => {
                self.clock.now().timestamp() + 60
            }
            ProviderFailure::Cancelled | ProviderFailure::Rollover => return,
        };
        *self.provider_block.lock().unwrap() = Some(ProviderBlock {
            provider_day: day.key.clone(),
            failure,
            until_epoch,
        });
    }

    fn failure_from_provider(
        &self,
        symbol: String,
        failure: ProviderFailure,
        price_history: Vec<ForecastPricePoint>,
        day: &ProviderDay,
    ) -> AnalystForecastPanel {
        let (state, message) = match failure {
            ProviderFailure::InvalidKey => (
                ForecastPanelState::InvalidKey,
                "The configured FMP API key was rejected.",
            ),
            ProviderFailure::QuotaExhausted => (
                ForecastPanelState::QuotaExhausted,
                "The estimated FMP daily request budget is exhausted.",
            ),
            ProviderFailure::Unavailable
            | ProviderFailure::InvalidPayload
            | ProviderFailure::Cancelled
            | ProviderFailure::Rollover => (
                ForecastPanelState::ProviderUnavailable,
                "FMP forecasts are temporarily unavailable.",
            ),
        };
        self.failure_panel(symbol, state, message, price_history, day)
    }

    fn payload_panel(
        &self,
        symbol: String,
        payload: CachedForecastPayload,
        fetched_at_epoch: i64,
        from_cache: bool,
        price_history: Vec<ForecastPricePoint>,
        day: &ProviderDay,
    ) -> AnalystForecastPanel {
        match summarize(payload.observations) {
            Some(summary) => {
                let state = if summary.identity_count >= 3 {
                    ForecastPanelState::Ready
                } else {
                    ForecastPanelState::InsufficientCoverage
                };
                let state_message = if state == ForecastPanelState::Ready {
                    "Individual FMP price targets.".to_string()
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
                        weighted_mean_cents: None,
                        weighting_label: "Unavailable: no licensed analyst-accuracy history"
                            .to_string(),
                    }),
                    identity_count: summary.identity_count,
                    usable_weighted_consensus: false,
                    price_history,
                    fetched_at_epoch: Some(fetched_at_epoch),
                    from_cache,
                    horizon_disclosure:
                        "Targets without an explicit date use an assumed 12-month horizon."
                            .to_string(),
                    provider_label: "Data by FMP".to_string(),
                    quota: self.quota_view(day),
                }
            }
            None => AnalystForecastPanel {
                symbol,
                state: ForecastPanelState::Empty,
                state_message: "FMP returned no current price-target coverage.".to_string(),
                observations: vec![],
                histogram: vec![],
                statistics: None,
                identity_count: 0,
                usable_weighted_consensus: false,
                price_history,
                fetched_at_epoch: Some(fetched_at_epoch),
                from_cache,
                horizon_disclosure:
                    "Targets without an explicit date use an assumed 12-month horizon.".to_string(),
                provider_label: "Data by FMP".to_string(),
                quota: self.quota_view(day),
            },
        }
    }

    fn failure_panel(
        &self,
        symbol: String,
        state: ForecastPanelState,
        message: &str,
        price_history: Vec<ForecastPricePoint>,
        day: &ProviderDay,
    ) -> AnalystForecastPanel {
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
            from_cache: false,
            horizon_disclosure: "Targets without an explicit date use an assumed 12-month horizon."
                .to_string(),
            provider_label: "Data by FMP".to_string(),
            quota: self.quota_view(day),
        }
    }

    fn quota_view(&self, day: &ProviderDay) -> FmpQuotaView {
        self.quota_view_result(day)
            .unwrap_or_else(|_| Self::quota_view_for_attempts(day, 0))
    }

    fn quota_view_result(&self, day: &ProviderDay) -> Result<FmpQuotaView, String> {
        let attempts = self.db.fmp_attempts(&day.key)?;
        Ok(Self::quota_view_for_attempts(day, attempts))
    }

    fn quota_view_for_attempts(day: &ProviderDay, attempts: u16) -> FmpQuotaView {
        FmpQuotaView {
            provider_day: day.key.clone(),
            attempts,
            limit: FMP_DAILY_LIMIT,
            remaining: FMP_DAILY_LIMIT.saturating_sub(attempts),
            warning: attempts >= FMP_WARNING_AT,
            exhausted: attempts >= FMP_DAILY_LIMIT,
            resets_at_epoch: day.resets_at_epoch,
        }
    }
}

fn valid_symbol(symbol: &str) -> bool {
    !symbol.is_empty()
        && symbol.len() <= 15
        && symbol
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || matches!(character, '.' | '-'))
}

struct FmpRestProvider {
    client: reqwest::blocking::Client,
    endpoint: String,
}

impl FmpRestProvider {
    fn new() -> Result<Self, String> {
        let client = reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(20))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|error| format!("build FMP HTTP client: {error}"))?;
        Ok(Self {
            client,
            endpoint: "https://financialmodelingprep.com/stable/price-target-news".to_string(),
        })
    }

    #[cfg(test)]
    fn with_endpoint(endpoint: String) -> Result<Self, String> {
        let mut provider = Self::new()?;
        provider.endpoint = endpoint;
        Ok(provider)
    }
}

impl ForecastProvider for FmpRestProvider {
    fn fetch(&self, symbol: &str, api_key: &str) -> Result<Vec<RawForecast>, ProviderFailure> {
        let response = self
            .client
            .get(&self.endpoint)
            .header("apikey", api_key)
            .query(&[("symbol", symbol), ("page", "0"), ("limit", "100")])
            .send()
            .map_err(|_| ProviderFailure::Unavailable)?;
        match response.status().as_u16() {
            200 => response
                .json::<Vec<RawForecast>>()
                .map_err(|_| ProviderFailure::InvalidPayload),
            401 | 403 => Err(ProviderFailure::InvalidKey),
            429 => Err(ProviderFailure::QuotaExhausted),
            _ => Err(ProviderFailure::Unavailable),
        }
    }
}

struct WindowsCredentialStore;

impl WindowsCredentialStore {
    fn entry() -> Result<keyring::Entry, String> {
        keyring::Entry::new("com.discount-screener.vantage", "fmp-api-key")
            .map_err(|error| format!("open Windows Credential Manager entry: {error}"))
    }
}

impl CredentialStore for WindowsCredentialStore {
    fn load(&self) -> Result<Option<String>, String> {
        match Self::entry()?.get_password() {
            Ok(value) if !value.trim().is_empty() => Ok(Some(value)),
            Ok(_) | Err(keyring::Error::NoEntry) => Ok(None),
            Err(error) => Err(format!("read FMP credential: {error}")),
        }
    }

    fn save(&self, api_key: &str) -> Result<(), String> {
        Self::entry()?
            .set_password(api_key)
            .map_err(|error| format!("save FMP credential: {error}"))
    }

    fn delete(&self) -> Result<(), String> {
        match Self::entry()?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(error) => Err(format!("delete FMP credential: {error}")),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct ProviderDay {
    key: String,
    resets_at_epoch: i64,
}

#[cfg(test)]
fn normalize(raw: Vec<RawForecast>, requested_symbol: &str) -> Vec<ForecastObservation> {
    normalize_at(raw, requested_symbol, Utc::now().timestamp())
}

fn normalize_at(
    raw: Vec<RawForecast>,
    requested_symbol: &str,
    now_epoch: i64,
) -> Vec<ForecastObservation> {
    raw.into_iter()
        .filter_map(|item| {
            let provider_symbol = clean_optional(item.symbol);
            if provider_symbol
                .as_deref()
                .is_some_and(|symbol| !symbol.eq_ignore_ascii_case(requested_symbol))
            {
                return None;
            }
            let target_cents = item
                .price_target
                .and_then(dollars_to_cents)
                .or_else(|| item.adj_price_target.and_then(dollars_to_cents))?;
            let issued_at_epoch = parse_provider_date(item.published_date.as_deref()?)?;
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
                source: clean_optional(item.news_publisher),
                identity,
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
        } else {
            if !anonymous.contains(&item) {
                anonymous.push(item);
            }
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

    Some(ForecastSummary {
        observations,
        identity_count,
        minimum_cents,
        maximum_cents,
        simple_mean_cents,
        histogram,
    })
}

fn provider_day(now: DateTime<Utc>) -> ProviderDay {
    let local = now.with_timezone(&New_York);
    let local_date = local.date_naive();
    let reset_date = if local.hour() < 15 {
        local_date
    } else {
        local_date
            .succ_opt()
            .expect("provider reset date remains representable")
    };
    let quota_date = reset_date
        .pred_opt()
        .expect("provider quota date remains representable");
    let reset_local = New_York
        .with_ymd_and_hms(
            reset_date.year(),
            reset_date.month(),
            reset_date.day(),
            15,
            0,
            0,
        )
        .single()
        .expect("3 PM local time is never ambiguous");
    ProviderDay {
        key: quota_date.format("%Y-%m-%d").to_string(),
        resets_at_epoch: reset_local.with_timezone(&Utc).timestamp(),
    }
}

fn parse_provider_date(value: &str) -> Option<i64> {
    DateTime::parse_from_rfc3339(value)
        .map(|date| date.timestamp())
        .ok()
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
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::mpsc;
    use std::thread;

    fn raw(analyst: Option<&str>, firm: Option<&str>, published: &str, target: f64) -> RawForecast {
        RawForecast {
            symbol: Some("AAPL".into()),
            published_date: Some(published.into()),
            price_target: Some(target),
            adj_price_target: None,
            price_when_posted: Some(190.25),
            analyst_name: analyst.map(str::to_string),
            analyst_company: firm.map(str::to_string),
            news_publisher: Some("FMP".into()),
            rating: Some("Buy".into()),
            new_grade: None,
            previous_price_target: Some(200.0),
            target_date: None,
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
        assert_eq!(
            item.identity.as_deref(),
            Some("analyst:jane doe|firm:example capital")
        );
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
        assert_eq!(
            observations[0].identity.as_deref(),
            Some("firm:example capital")
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
    fn normalization_rejects_wrong_symbol_future_and_expired_rows() {
        let now = Utc
            .with_ymd_and_hms(2026, 7, 29, 12, 0, 0)
            .single()
            .unwrap()
            .timestamp();
        let mut wrong_symbol = raw(Some("Jane"), Some("Firm"), "2026-07-01", 225.0);
        wrong_symbol.symbol = Some("MSFT".into());
        let future = raw(Some("Future"), Some("Firm"), "2026-08-01", 230.0);
        let expired = raw(Some("Old"), Some("Firm"), "2025-01-01", 180.0);

        assert!(normalize_at(vec![wrong_symbol, future, expired], "AAPL", now).is_empty());
    }

    #[test]
    fn adjusted_target_is_used_when_primary_target_is_invalid() {
        let now = Utc
            .with_ymd_and_hms(2026, 7, 29, 12, 0, 0)
            .single()
            .unwrap()
            .timestamp();
        let mut item = raw(Some("Jane"), Some("Firm"), "2026-07-01", -1.0);
        item.adj_price_target = Some(230.0);

        let observations = normalize_at(vec![item], "AAPL", now);
        assert_eq!(observations[0].target_cents, 23_000);
    }

    #[test]
    fn exact_anonymous_duplicates_are_counted_once() {
        let item = raw(None, None, "2026-07-01", 225.0);
        let summary = summarize(normalize(vec![item.clone(), item], "AAPL")).unwrap();

        assert_eq!(summary.observations.len(), 1);
        assert_eq!(summary.identity_count, 0);
        assert_eq!(
            summary.histogram.iter().map(|bin| bin.count).sum::<usize>(),
            1
        );
    }

    #[test]
    fn histogram_handles_the_full_i64_range_without_overflow() {
        let observation = |target_cents| ForecastObservation {
            symbol: "AAPL".into(),
            analyst: None,
            firm: None,
            issued_at_epoch: 1,
            horizon_epoch: 2,
            horizon_label: "Provider horizon".into(),
            rating: None,
            target_cents,
            previous_target_cents: None,
            price_when_posted_cents: None,
            source: None,
            identity: None,
        };
        let bins = histogram(
            &[observation(i64::MIN), observation(i64::MAX)],
            i64::MIN,
            i64::MAX,
        );

        assert_eq!(bins.iter().map(|bin| bin.count).sum::<usize>(), 2);
        assert_eq!(bins.first().unwrap().low_cents, i64::MIN);
        assert_eq!(bins.last().unwrap().high_cents, i64::MAX);
    }

    #[test]
    fn computes_integer_statistics_and_backend_owned_histogram_bins() {
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
        assert_eq!(summary.histogram.len(), 5);
        assert_eq!(
            summary.histogram.iter().map(|bin| bin.count).sum::<usize>(),
            6
        );
    }

    #[test]
    fn quota_day_rolls_at_three_pm_new_york_time() {
        let before = provider_day(
            Utc.with_ymd_and_hms(2026, 7, 29, 18, 59, 59)
                .single()
                .unwrap(),
        );
        let after = provider_day(
            Utc.with_ymd_and_hms(2026, 7, 29, 19, 0, 0)
                .single()
                .unwrap(),
        );

        assert_eq!(before.key, "2026-07-28");
        assert_eq!(after.key, "2026-07-29");
        assert_eq!(
            before.resets_at_epoch,
            Utc.with_ymd_and_hms(2026, 7, 29, 19, 0, 0)
                .single()
                .unwrap()
                .timestamp()
        );
        assert_eq!(
            after.resets_at_epoch,
            Utc.with_ymd_and_hms(2026, 7, 30, 19, 0, 0)
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
        result: Result<Vec<RawForecast>, ProviderFailure>,
        delay_ms: u64,
    }

    impl FakeProvider {
        fn successful(rows: Vec<RawForecast>) -> Self {
            Self {
                calls: AtomicUsize::new(0),
                result: Ok(rows),
                delay_ms: 0,
            }
        }
    }

    impl ForecastProvider for FakeProvider {
        fn fetch(&self, _symbol: &str, api_key: &str) -> Result<Vec<RawForecast>, ProviderFailure> {
            assert_eq!(api_key, "test-secret");
            self.calls.fetch_add(1, Ordering::SeqCst);
            if self.delay_ms > 0 {
                thread::sleep(std::time::Duration::from_millis(self.delay_ms));
            }
            self.result.clone()
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

        fn set(&self, now: DateTime<Utc>) {
            *self.now.lock().unwrap() = now;
        }
    }

    impl Clock for TestClock {
        fn now(&self) -> DateTime<Utc> {
            *self.now.lock().unwrap()
        }
    }

    struct RolloverClock {
        calls: AtomicUsize,
        before_reset: DateTime<Utc>,
        after_reset: DateTime<Utc>,
    }

    impl Clock for RolloverClock {
        fn now(&self) -> DateTime<Utc> {
            if self.calls.fetch_add(1, Ordering::SeqCst) == 0 {
                self.before_reset
            } else {
                self.after_reset
            }
        }
    }

    struct ControlledProvider {
        calls: AtomicUsize,
        started: (Mutex<usize>, Condvar),
        released: (Mutex<Vec<bool>>, Condvar),
    }

    impl ControlledProvider {
        fn new(capacity: usize) -> Self {
            Self {
                calls: AtomicUsize::new(0),
                started: (Mutex::new(0), Condvar::new()),
                released: (Mutex::new(vec![false; capacity]), Condvar::new()),
            }
        }

        fn wait_for_started(&self, count: usize) {
            let (lock, ready) = &self.started;
            let mut started = lock.lock().unwrap();
            while *started < count {
                started = ready.wait(started).unwrap();
            }
        }

        fn release(&self, index: usize) {
            let (lock, ready) = &self.released;
            lock.lock().unwrap()[index] = true;
            ready.notify_all();
        }
    }

    impl ForecastProvider for ControlledProvider {
        fn fetch(&self, _symbol: &str, api_key: &str) -> Result<Vec<RawForecast>, ProviderFailure> {
            assert_eq!(api_key, "test-secret");
            let index = self.calls.fetch_add(1, Ordering::SeqCst);
            {
                let (lock, ready) = &self.started;
                *lock.lock().unwrap() += 1;
                ready.notify_all();
            }
            let (lock, ready) = &self.released;
            let mut released = lock.lock().unwrap();
            while !released[index] {
                released = ready.wait(released).unwrap();
            }
            Ok(vec![raw(
                Some(&format!("Analyst {index}")),
                Some("Firm"),
                "2026-07-01",
                200.0 + index as f64,
            )])
        }
    }

    struct ConcurrencyProvider {
        calls: AtomicUsize,
        active: AtomicUsize,
        maximum: AtomicUsize,
    }

    impl ForecastProvider for ConcurrencyProvider {
        fn fetch(
            &self,
            _symbol: &str,
            _api_key: &str,
        ) -> Result<Vec<RawForecast>, ProviderFailure> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            let active = self.active.fetch_add(1, Ordering::SeqCst) + 1;
            self.maximum.fetch_max(active, Ordering::SeqCst);
            thread::sleep(std::time::Duration::from_millis(60));
            self.active.fetch_sub(1, Ordering::SeqCst);
            Ok(vec![])
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
    fn same_provider_day_cache_prevents_a_second_network_call() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![raw(
            Some("Jane"),
            Some("Firm"),
            "2026-07-01",
            225.0,
        )]));
        let service = service(db, Arc::clone(&provider), true);

        let first = service.get("aapl", vec![]);
        let second = service.get("AAPL", vec![]);

        assert_eq!(first.state, ForecastPanelState::InsufficientCoverage);
        assert!(!first.from_cache);
        assert!(second.from_cache);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
        assert_eq!(second.quota.attempts, 1);
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

        let day = provider_day(Utc::now());
        for _ in 0..FMP_DAILY_LIMIT {
            db.reserve_fmp_attempt(&day.key, FMP_DAILY_LIMIT).unwrap();
        }
        let exhausted = service(db, Arc::clone(&provider), true);
        assert_eq!(
            exhausted.get("MSFT", vec![]).state,
            ForecastPanelState::QuotaExhausted
        );
        assert_eq!(provider.calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn valid_empty_results_are_cached_and_expose_the_warning_boundary() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let day = provider_day(Utc::now());
        for _ in 0..(FMP_WARNING_AT - 1) {
            db.reserve_fmp_attempt(&day.key, FMP_DAILY_LIMIT).unwrap();
        }
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let service = service(db, Arc::clone(&provider), true);

        let first = service.get("AAPL", vec![]);
        let second = service.get("AAPL", vec![]);

        assert_eq!(first.state, ForecastPanelState::Empty);
        assert_eq!(first.quota.attempts, FMP_WARNING_AT);
        assert!(first.quota.warning);
        assert!(second.from_cache);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn provider_failures_map_to_explicit_states_without_being_cached() {
        for (failure, expected) in [
            (ProviderFailure::InvalidKey, ForecastPanelState::InvalidKey),
            (
                ProviderFailure::QuotaExhausted,
                ForecastPanelState::QuotaExhausted,
            ),
            (
                ProviderFailure::Unavailable,
                ForecastPanelState::ProviderUnavailable,
            ),
            (
                ProviderFailure::InvalidPayload,
                ForecastPanelState::ProviderUnavailable,
            ),
        ] {
            let db = Arc::new(Db::open_in_memory().unwrap());
            let provider = Arc::new(FakeProvider {
                calls: AtomicUsize::new(0),
                result: Err(failure),
                delay_ms: 0,
            });
            let service = service(db, Arc::clone(&provider), true);

            assert_eq!(service.get("AAPL", vec![]).state, expected);
            assert_eq!(service.get("AAPL", vec![]).state, expected);
            assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
        }
    }

    #[test]
    fn overlapping_requests_share_one_in_flight_provider_call() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider {
            calls: AtomicUsize::new(0),
            result: Ok(vec![raw(Some("Jane"), Some("Firm"), "2026-07-01", 225.0)]),
            delay_ms: 100,
        });
        let service = Arc::new(service(db, Arc::clone(&provider), true));
        let left = {
            let service = Arc::clone(&service);
            thread::spawn(move || service.get("AAPL", vec![]))
        };
        let right = {
            let service = Arc::clone(&service);
            thread::spawn(move || service.get("AAPL", vec![]))
        };

        assert_eq!(
            left.join().unwrap().state,
            ForecastPanelState::InsufficientCoverage
        );
        assert_eq!(
            right.join().unwrap().state,
            ForecastPanelState::InsufficientCoverage
        );
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn new_provider_day_request_does_not_join_an_old_day_flight() {
        let old_now = Utc
            .with_ymd_and_hms(2026, 7, 29, 18, 59, 0)
            .single()
            .unwrap();
        let new_now = Utc
            .with_ymd_and_hms(2026, 7, 29, 19, 1, 0)
            .single()
            .unwrap();
        let clock = Arc::new(TestClock::new(old_now));
        let provider = Arc::new(ControlledProvider::new(2));
        let service = Arc::new(service_with_clock(
            Arc::new(Db::open_in_memory().unwrap()),
            Arc::clone(&provider) as Arc<dyn ForecastProvider>,
            Arc::clone(&clock) as Arc<dyn Clock>,
        ));

        let old_request = {
            let service = Arc::clone(&service);
            thread::spawn(move || service.get("AAPL", vec![]))
        };
        provider.wait_for_started(1);
        clock.set(new_now);
        let new_request = {
            let service = Arc::clone(&service);
            thread::spawn(move || service.get("AAPL", vec![]))
        };
        provider.wait_for_started(2);

        provider.release(1);
        let new_panel = new_request.join().unwrap();
        provider.release(0);
        let _old_panel = old_request.join().unwrap();
        let cached_new_panel = service.get("AAPL", vec![]);

        assert_eq!(provider.calls.load(Ordering::SeqCst), 2);
        assert_eq!(new_panel.observations[0].target_cents, 20_100);
        assert!(cached_new_panel.from_cache);
        assert_eq!(cached_new_panel.observations[0].target_cents, 20_100);
    }

    #[test]
    fn network_concurrency_is_globally_limited_to_two() {
        let provider = Arc::new(ConcurrencyProvider {
            calls: AtomicUsize::new(0),
            active: AtomicUsize::new(0),
            maximum: AtomicUsize::new(0),
        });
        let service = Arc::new(service_with_clock(
            Arc::new(Db::open_in_memory().unwrap()),
            Arc::clone(&provider) as Arc<dyn ForecastProvider>,
            Arc::new(SystemClock),
        ));
        let handles = ["AAPL", "MSFT", "TSLA", "JPM", "NVDA", "AMD"]
            .into_iter()
            .map(|symbol| {
                let service = Arc::clone(&service);
                thread::spawn(move || service.get(symbol, vec![]))
            })
            .collect::<Vec<_>>();
        for handle in handles {
            handle.join().unwrap();
        }

        assert_eq!(provider.calls.load(Ordering::SeqCst), 6);
        assert_eq!(provider.maximum.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn stale_warm_request_queued_at_the_gate_uses_no_call_or_budget() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let day = provider_day(Utc::now());
        let provider = Arc::new(ControlledProvider::new(2));
        let service = Arc::new(service_with_clock(
            Arc::clone(&db),
            Arc::clone(&provider) as Arc<dyn ForecastProvider>,
            Arc::new(SystemClock),
        ));
        let first = {
            let service = Arc::clone(&service);
            thread::spawn(move || service.get("AAPL", vec![]))
        };
        let second = {
            let service = Arc::clone(&service);
            thread::spawn(move || service.get("MSFT", vec![]))
        };
        provider.wait_for_started(2);

        let active_generation = Arc::new(AtomicU64::new(7));
        let queued = {
            let service = Arc::clone(&service);
            let guard = GenerationGuard {
                active_generation: Arc::clone(&active_generation),
                expected_generation: 7,
            };
            thread::spawn(move || service.get_for_generation("TSLA", guard))
        };
        thread::sleep(std::time::Duration::from_millis(40));
        active_generation.store(8, Ordering::SeqCst);
        provider.release(0);
        provider.release(1);

        first.join().unwrap();
        second.join().unwrap();
        let queued_panel = queued.join().unwrap();
        assert_eq!(queued_panel.state, ForecastPanelState::ProviderUnavailable);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 2);
        assert_eq!(db.fmp_attempts(&day.key).unwrap(), 2);
    }

    #[test]
    fn queued_request_crossing_reset_retries_under_only_the_new_provider_day() {
        let before_reset = Utc
            .with_ymd_and_hms(2026, 7, 29, 18, 59, 59)
            .single()
            .unwrap();
        let after_reset = Utc
            .with_ymd_and_hms(2026, 7, 29, 19, 0, 1)
            .single()
            .unwrap();
        let old_day = provider_day(before_reset);
        let new_day = provider_day(after_reset);
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let clock = Arc::new(RolloverClock {
            calls: AtomicUsize::new(0),
            before_reset,
            after_reset,
        });
        let service = Arc::new(service_with_clock(
            Arc::clone(&db),
            Arc::clone(&provider) as Arc<dyn ForecastProvider>,
            Arc::clone(&clock) as Arc<dyn Clock>,
        ));
        let queued_service = Arc::clone(&service);
        let first_permit = service.request_gate.acquire(None).unwrap();
        let second_permit = service.request_gate.acquire(None).unwrap();
        let queued = thread::spawn(move || queued_service.get("TSLA", vec![]));

        while clock.calls.load(Ordering::SeqCst) < 2 {
            thread::yield_now();
        }
        drop(first_permit);
        let panel = queued.join().unwrap();
        drop(second_permit);

        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
        assert_eq!(db.fmp_attempts(&old_day.key).unwrap(), 0);
        assert_eq!(db.fmp_attempts(&new_day.key).unwrap(), 1);
        assert!(db
            .load_fmp_forecast_cache(&old_day.key, "TSLA")
            .unwrap()
            .is_none());
        assert!(db
            .load_fmp_forecast_cache(&new_day.key, "TSLA")
            .unwrap()
            .is_some());
        assert_eq!(panel.quota.provider_day, new_day.key);
    }

    #[test]
    fn explicit_test_crossing_reset_transparently_uses_the_new_provider_day() {
        let before_reset = Utc
            .with_ymd_and_hms(2026, 7, 29, 18, 59, 59)
            .single()
            .unwrap();
        let after_reset = Utc
            .with_ymd_and_hms(2026, 7, 29, 19, 0, 1)
            .single()
            .unwrap();
        let old_day = provider_day(before_reset);
        let new_day = provider_day(after_reset);
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let clock = Arc::new(RolloverClock {
            calls: AtomicUsize::new(0),
            before_reset,
            after_reset,
        });
        let service = service_with_clock(
            Arc::clone(&db),
            Arc::clone(&provider) as Arc<dyn ForecastProvider>,
            clock,
        );

        let panel = service.test_connection("AAPL");

        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
        assert_eq!(db.fmp_attempts(&old_day.key).unwrap(), 0);
        assert_eq!(db.fmp_attempts(&new_day.key).unwrap(), 1);
        assert_eq!(panel.quota.provider_day, new_day.key);
    }

    #[test]
    #[ignore = "requires FMP_API_KEY"]
    fn opt_in_live_contract_covers_five_distinct_symbols() {
        let api_key = std::env::var("FMP_API_KEY")
            .expect("FMP_API_KEY is required for the ignored live test");
        let provider = FmpRestProvider::new().unwrap();
        let now = Utc::now().timestamp();
        for symbol in ["AAPL", "MSFT", "ACGL", "TSLA", "JPM"] {
            let rows = provider.fetch(symbol, &api_key).unwrap_or_else(|failure| {
                panic!("live FMP contract failed for {symbol}: {failure:?}")
            });
            let normalized = normalize_at(rows, symbol, now);
            assert!(
                !normalized.is_empty(),
                "live FMP contract returned no usable observations for {symbol}"
            );
            assert!(normalized.iter().all(|item| {
                item.symbol.eq_ignore_ascii_case(symbol)
                    && item.target_cents > 0
                    && item.horizon_epoch > now
            }));
        }
    }

    #[test]
    fn warm_ranking_uses_final_backend_scores_and_caps_distinct_stocks_at_ten() {
        let mut candidates = (0..12)
            .map(|index| WarmCandidate {
                symbol: format!("S{index:02}"),
                composite_score: index,
                is_stock: true,
            })
            .collect::<Vec<_>>();
        candidates.push(WarmCandidate {
            symbol: "CRYPTO".into(),
            composite_score: 100,
            is_stock: false,
        });
        candidates.push(WarmCandidate {
            symbol: "S11".into(),
            composite_score: 99,
            is_stock: true,
        });

        let ranked = rank_warm_candidates(candidates);
        assert_eq!(ranked.len(), 10);
        assert_eq!(ranked[0], "S11");
        assert_eq!(ranked[1], "S10");
        assert!(!ranked.contains(&"CRYPTO".to_string()));
        assert_eq!(ranked.iter().collect::<HashSet<_>>().len(), ranked.len());
    }

    #[test]
    fn warm_workers_stop_before_provider_calls_when_generation_is_stale() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let provider = Arc::new(FakeProvider::successful(vec![]));
        let service = Arc::new(service(db, Arc::clone(&provider), true));
        let feed_generation = Arc::new(AtomicU64::new(8));

        service.spawn_generation_warm(vec!["AAPL".into(), "MSFT".into()], feed_generation, 7);
        thread::sleep(std::time::Duration::from_millis(50));

        assert_eq!(provider.calls.load(Ordering::SeqCst), 0);
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
        db.drop_fmp_budget_table_for_test();

        assert!(service.settings_status().is_err());
    }

    fn serve_one_http_response(
        status: &str,
        body: &str,
    ) -> (String, mpsc::Receiver<String>, thread::JoinHandle<()>) {
        serve_one_http_response_with_headers(status, "", body)
    }

    fn serve_one_http_response_with_headers(
        status: &str,
        extra_headers: &str,
        body: &str,
    ) -> (String, mpsc::Receiver<String>, thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let (request_tx, request_rx) = mpsc::channel();
        let status = status.to_string();
        let extra_headers = extra_headers.to_string();
        let body = body.to_string();
        let handle = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(2)))
                .unwrap();
            let mut bytes = Vec::new();
            let mut buffer = [0_u8; 1024];
            loop {
                let read = stream.read(&mut buffer).unwrap();
                if read == 0 {
                    break;
                }
                bytes.extend_from_slice(&buffer[..read]);
                if bytes.windows(4).any(|window| window == b"\r\n\r\n") {
                    break;
                }
            }
            request_tx
                .send(String::from_utf8_lossy(&bytes).to_string())
                .unwrap();
            let response = format!(
                "HTTP/1.1 {status}\r\n{extra_headers}Content-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            stream.write_all(response.as_bytes()).unwrap();
        });
        (
            format!("http://{address}/stable/price-target-news"),
            request_rx,
            handle,
        )
    }

    #[test]
    fn rest_adapter_sends_the_key_only_in_a_header() {
        let (endpoint, request_rx, handle) = serve_one_http_response("200 OK", "[]");
        let provider = FmpRestProvider::with_endpoint(endpoint).unwrap();

        assert_eq!(provider.fetch("AAPL", "super-secret").unwrap().len(), 0);
        handle.join().unwrap();
        let request = request_rx.recv().unwrap();
        let request_line = request.lines().next().unwrap();
        assert!(request_line.contains("symbol=AAPL"));
        assert!(!request_line.contains("super-secret"));
        assert!(request
            .lines()
            .any(|line| line.eq_ignore_ascii_case("apikey: super-secret")));
    }

    #[test]
    fn rest_adapter_maps_authentication_failure_without_exposing_a_body() {
        let (endpoint, _request_rx, handle) =
            serve_one_http_response("401 Unauthorized", r#"{"error":"rejected"}"#);
        let provider = FmpRestProvider::with_endpoint(endpoint).unwrap();

        assert!(matches!(
            provider.fetch("AAPL", "super-secret"),
            Err(ProviderFailure::InvalidKey)
        ));
        handle.join().unwrap();
    }

    #[test]
    fn rest_adapter_does_not_follow_redirects() {
        let redirect_target = TcpListener::bind("127.0.0.1:0").unwrap();
        redirect_target.set_nonblocking(true).unwrap();
        let location = format!(
            "Location: http://{}/redirected\r\n",
            redirect_target.local_addr().unwrap()
        );
        let (endpoint, _request_rx, handle) =
            serve_one_http_response_with_headers("302 Found", &location, "");
        let provider = FmpRestProvider::with_endpoint(endpoint).unwrap();

        assert!(matches!(
            provider.fetch("AAPL", "super-secret"),
            Err(ProviderFailure::Unavailable)
        ));
        handle.join().unwrap();
        thread::sleep(std::time::Duration::from_millis(20));
        assert!(matches!(
            redirect_target.accept(),
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock
        ));
    }

    #[test]
    fn repeated_explicit_test_honors_invalid_key_breaker_until_key_changes() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let day = provider_day(Utc::now());
        let provider = Arc::new(FakeProvider {
            calls: AtomicUsize::new(0),
            result: Err(ProviderFailure::InvalidKey),
            delay_ms: 0,
        });
        let service = service(db, Arc::clone(&provider), true);

        assert_eq!(
            service.test_connection("AAPL").state,
            ForecastPanelState::InvalidKey
        );
        assert_eq!(
            service.test_connection("AAPL").state,
            ForecastPanelState::InvalidKey
        );
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
        assert_eq!(service.db.fmp_attempts(&day.key).unwrap(), 1);

        service.save_key("test-secret").unwrap();
        assert_eq!(
            service.test_connection("AAPL").state,
            ForecastPanelState::InvalidKey
        );
        assert_eq!(provider.calls.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn cached_aapl_does_not_bypass_explicit_credential_validation() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let successful_provider = Arc::new(FakeProvider::successful(vec![raw(
            Some("Jane"),
            Some("Firm"),
            "2026-07-01",
            225.0,
        )]));
        let cached_service = service(Arc::clone(&db), successful_provider, true);
        assert_eq!(
            cached_service.get("AAPL", vec![]).state,
            ForecastPanelState::InsufficientCoverage
        );

        let invalid_provider = Arc::new(FakeProvider {
            calls: AtomicUsize::new(0),
            result: Err(ProviderFailure::InvalidKey),
            delay_ms: 0,
        });
        let validating_service = service(db, Arc::clone(&invalid_provider), true);
        let validation = validating_service.test_connection("AAPL");
        let cached_after_failure = validating_service.get("AAPL", vec![]);

        assert_eq!(validation.state, ForecastPanelState::InvalidKey);
        assert!(!validation.from_cache);
        assert_eq!(validation.quota.attempts, 2);
        assert_eq!(cached_after_failure.observations[0].target_cents, 22_500);
        assert!(cached_after_failure.from_cache);
        assert_eq!(invalid_provider.calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn successful_explicit_validation_replaces_the_cached_result() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let initial_provider = Arc::new(FakeProvider::successful(vec![raw(
            Some("Jane"),
            Some("Firm"),
            "2026-07-01",
            225.0,
        )]));
        service(Arc::clone(&db), initial_provider, true).get("AAPL", vec![]);

        let updated_provider = Arc::new(FakeProvider::successful(vec![raw(
            Some("Jane"),
            Some("Firm"),
            "2026-07-02",
            250.0,
        )]));
        let validating_service = service(db, Arc::clone(&updated_provider), true);
        let validation = validating_service.test_connection("AAPL");
        let cached = validating_service.get("AAPL", vec![]);

        assert_eq!(validation.observations[0].target_cents, 25_000);
        assert!(!validation.from_cache);
        assert_eq!(validation.quota.attempts, 2);
        assert_eq!(cached.observations[0].target_cents, 25_000);
        assert!(cached.from_cache);
        assert_eq!(updated_provider.calls.load(Ordering::SeqCst), 1);
    }
}
