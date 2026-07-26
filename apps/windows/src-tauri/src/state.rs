use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::crypto_cycle::FngCache;
use crate::db::Db;
use crate::engine::ScreenerState;
use crate::feed_log::FeedLog;
use crate::news::NewsCache;
use crate::profiles::compose_universe;
use crate::regime::{CnnFngCache, RegimeCache};
use crate::ticker_search::YahooSearchQuote;

#[derive(Clone)]
pub struct FeedStatus {
    pub running: bool,
    pub symbols_loaded: usize,
    pub last_error: Option<String>,
    pub profile_name: String,
}

impl Default for FeedStatus {
    fn default() -> Self {
        Self {
            running: false,
            symbols_loaded: 0,
            last_error: None,
            profile_name: "sp500".into(),
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
    /// Active index / universe profile id (`sp500`, `dow`, …).
    pub active_profile: Mutex<String>,
    /// Symbols currently tracked by the feed for `active_profile`.
    pub active_symbols: Mutex<Arc<Vec<String>>>,
    /// Bumped on each universe switch so stale feed workers exit.
    pub feed_generation: Arc<AtomicU64>,
}

impl AppState {
    pub fn new(db_path: PathBuf) -> Self {
        let log_path = db_path
            .parent()
            .map(|p| p.join("feed.log"))
            .unwrap_or_else(|| PathBuf::from("feed.log"));
        let db = Db::open(db_path).expect("open history db");
        let (scalp_ws_tx, _) = tokio::sync::watch::channel(String::new());
        let (profile, symbols) = compose_universe("sp500").expect("default sp500 universe");
        Self {
            screener: Arc::new(Mutex::new(ScreenerState::new())),
            feed_status: Arc::new(Mutex::new(FeedStatus {
                profile_name: profile.clone(),
                ..FeedStatus::default()
            })),
            db: Arc::new(db),
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
            feed_generation: Arc::new(AtomicU64::new(0)),
        }
    }

    pub fn feed_generation_arc(&self) -> Arc<AtomicU64> {
        Arc::clone(&self.feed_generation)
    }
}
