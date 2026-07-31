use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::analyst_forecasts::AnalystForecastService;
use crate::crypto_cycle::FngCache;
use crate::db::Db;
use crate::engine::ScreenerState;
use crate::feed_log::FeedLog;
use crate::fetcher::YahooClient;
use crate::launch_profile::ForcedProfile;
use crate::news::NewsCache;
use crate::profiles::{compose_universe, resolve_profile_membership};
use crate::regime::{CnnFngCache, RegimeCache};
use crate::ticker_search::YahooSearchQuote;

#[derive(Clone)]
pub struct FeedStatus {
    pub running: bool,
    pub symbols_loaded: usize,
    pub last_error: Option<String>,
    pub profile_name: String,
    /// Informational: QA sample used snapshots older than reporting threshold.
    pub stale_snapshots: bool,
}

impl Default for FeedStatus {
    fn default() -> Self {
        Self {
            running: false,
            symbols_loaded: 0,
            last_error: None,
            profile_name: "sp500".into(),
            stale_snapshots: false,
        }
    }
}

#[derive(Clone, Default, serde::Serialize)]
pub struct CongressSyncProgress {
    pub running: bool,
    pub current_year: u32,
    pub current_step: String,
    pub processed: usize,
    pub total: usize,
    pub trades_imported: usize,
    pub years_completed: Vec<u32>,
    pub total_imported_session: usize,
    pub last_error: Option<String>,
}

/// TTL cache for Yahoo remote search (mirrors Android: 300s, max 50 keys).
pub struct RemoteSearchCache {
    entries: HashMap<String, (Instant, Vec<YahooSearchQuote>)>,
    max_entries: usize,
    ttl: Duration,
}

impl RemoteSearchCache {
    pub fn new() -> Self {
        Self {
            entries: HashMap::new(),
            max_entries: 50,
            ttl: Duration::from_secs(300),
        }
    }

    pub fn get(&mut self, key: &str) -> Option<Vec<YahooSearchQuote>> {
        let now = Instant::now();
        if let Some((at, quotes)) = self.entries.get(key) {
            if now.duration_since(*at) < self.ttl {
                return Some(quotes.clone());
            }
        }
        self.entries.remove(key);
        None
    }

    pub fn put(&mut self, key: String, quotes: Vec<YahooSearchQuote>) {
        if self.entries.len() >= self.max_entries {
            // Drop an arbitrary oldest-ish entry (first key) — good enough for v1.
            if let Some(evict) = self.entries.keys().next().cloned() {
                self.entries.remove(&evict);
            }
        }
        self.entries.insert(key, (Instant::now(), quotes));
    }
}

impl Default for RemoteSearchCache {
    fn default() -> Self {
        Self::new()
    }
}

pub struct AppState {
    pub screener: Arc<Mutex<ScreenerState>>,
    pub feed_status: Arc<Mutex<FeedStatus>>,
    pub db: Arc<Db>,
    pub analyst_forecasts: Arc<AnalystForecastService>,
    /// Append-only diagnostics next to the DB (`feed.log`).
    pub feed_log: Arc<FeedLog>,
    pub news_cache: Arc<NewsCache>,
    pub congress_sync: Arc<Mutex<CongressSyncProgress>>,
    pub fng_cache: Arc<FngCache>,
    /// CNN equity Fear & Greed cache (regime engine).
    pub cnn_fng_cache: Arc<CnnFngCache>,
    /// Full market-regime response cache + exposure hysteresis.
    pub regime_cache: Arc<RegimeCache>,
    /// When true, V3 composite includes the 4th regime_fit bucket.
    pub apply_regime_scoring: Arc<AtomicBool>,
    /// Carries the active scalping product to the WebSocket background task.
    pub scalp_ws_tx: tokio::sync::watch::Sender<String>,
    pub remote_search_cache: Arc<Mutex<RemoteSearchCache>>,
    /// Active index / universe profile id (`sp500`, `dow`, `qa`, …).
    pub active_profile: Mutex<String>,
    /// Symbols currently tracked by the feed for `active_profile`.
    pub active_symbols: Mutex<Arc<Vec<String>>>,
    /// When true, launch forced the profile; UI must not switch away.
    pub profile_locked: AtomicBool,
    /// Bumped on each universe switch so stale feed workers exit.
    pub feed_generation: Arc<AtomicU64>,
    /// Generation whose one-shot initial retry pass reached a terminal state.
    /// `u64::MAX` means no current generation has completed yet.
    pub initial_pass_completed_generation: Arc<AtomicU64>,
    /// SEC ticker→CIK map (lazy; filled by EDGAR workers / demand valuation).
    pub edgar_cik_map: Arc<Mutex<Option<HashMap<String, u64>>>>,
    /// Symbols with an in-flight demand-driven valuation (avoid duplicate EDGAR hits).
    pub valuation_inflight: Arc<Mutex<HashSet<String>>>,
    /// One bounded Yahoo session for demand-only operating forecasts.
    pub valuation_yahoo: Option<Arc<YahooClient>>,
}

impl AppState {
    #[allow(dead_code)] // convenience for tests / non-launch callers
    pub fn new(db_path: PathBuf) -> Self {
        Self::new_with_forced_profile(db_path, None).expect("open app state")
    }

    /// Build state; optional launch-forced profile locks membership and skips localStorage restore.
    pub fn new_with_forced_profile(
        db_path: PathBuf,
        forced: Option<ForcedProfile>,
    ) -> Result<Self, String> {
        let log_path = db_path
            .parent()
            .map(|p| p.join("feed.log"))
            .unwrap_or_else(|| PathBuf::from("feed.log"));
        let db = Db::open(db_path).map_err(|e| format!("open history db: {e}"))?;
        let db = Arc::new(db);
        let analyst_forecasts = Arc::new(
            AnalystForecastService::new(Arc::clone(&db))
                .map_err(|e| format!("initialize FMP analyst forecast service: {e}"))?,
        );
        let (scalp_ws_tx, _) = tokio::sync::watch::channel(String::new());
        // Forward forecasts are demand-only optional evidence. A client/TLS
        // construction failure must not prevent the workstation from opening;
        // the runtime router will expose provider unavailability instead.
        let valuation_yahoo = YahooClient::new().ok().map(Arc::new);

        let locked = forced.is_some();
        let (profile, symbols, stale, db_err) = match forced {
            Some(f) => {
                let resolved = resolve_profile_membership(&f.name, &db)?;
                (
                    resolved.name,
                    resolved.symbols,
                    resolved.stale_snapshots,
                    resolved.db_error,
                )
            }
            None => {
                let (p, s) = compose_universe("sp500").expect("default sp500 universe");
                (p, s, false, None)
            }
        };

        let mut last_error = db_err.map(|e| format!("qa db fallback: {e}"));
        if stale {
            let msg = "qa: stale_snapshots (reporting only; membership not excluded)";
            last_error = Some(match last_error {
                Some(e) => format!("{e}; {msg}"),
                None => msg.into(),
            });
        }

        Ok(Self {
            screener: Arc::new(Mutex::new(ScreenerState::new())),
            feed_status: Arc::new(Mutex::new(FeedStatus {
                profile_name: profile.clone(),
                last_error,
                stale_snapshots: stale,
                ..FeedStatus::default()
            })),
            db,
            analyst_forecasts,
            feed_log: Arc::new(FeedLog::new(log_path)),
            news_cache: Arc::new(NewsCache::new()),
            congress_sync: Arc::new(Mutex::new(CongressSyncProgress::default())),
            fng_cache: Arc::new(FngCache::new()),
            cnn_fng_cache: Arc::new(CnnFngCache::new()),
            regime_cache: Arc::new(RegimeCache::new()),
            apply_regime_scoring: Arc::new(AtomicBool::new(true)),
            scalp_ws_tx,
            remote_search_cache: Arc::new(Mutex::new(RemoteSearchCache::new())),
            active_profile: Mutex::new(profile),
            active_symbols: Mutex::new(Arc::new(symbols)),
            profile_locked: AtomicBool::new(locked),
            feed_generation: Arc::new(AtomicU64::new(0)),
            initial_pass_completed_generation: Arc::new(AtomicU64::new(u64::MAX)),
            edgar_cik_map: Arc::new(Mutex::new(None)),
            valuation_inflight: Arc::new(Mutex::new(HashSet::new())),
            valuation_yahoo,
        })
    }

    pub fn feed_generation_arc(&self) -> Arc<AtomicU64> {
        Arc::clone(&self.feed_generation)
    }

    pub fn is_profile_locked(&self) -> bool {
        self.profile_locked
            .load(std::sync::atomic::Ordering::Relaxed)
    }
}
