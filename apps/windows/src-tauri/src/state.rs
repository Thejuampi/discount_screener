use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use crate::analyst_forecasts::AnalystForecastService;
use crate::crypto_cycle::FngCache;
use crate::db::Db;
use crate::engine::ScreenerState;
use crate::feed_log::FeedLog;
use crate::feed_refresh::FeedRefresh;
use crate::fetcher::YahooClient;
use crate::launch_profile::ForcedProfile;
use crate::news::NewsCache;
use crate::profiles::resolve_tracked_feed_membership;
use crate::regime::{CnnFngCache, RegimeCache};
use crate::ticker_search::YahooSearchQuote;

pub struct FeedStatus {
    pub running: bool,
    pub symbols_loaded: usize,
    pub symbols_total: usize,
    /// Profile or worker issue independent of Yahoo refresh results.
    pub last_error: Option<String>,
    pub profile_name: String,
    /// Informational: QA sample used snapshots older than reporting threshold.
    pub stale_snapshots: bool,
    pub(crate) refresh: FeedRefresh,
}

impl Default for FeedStatus {
    fn default() -> Self {
        Self {
            running: false,
            symbols_loaded: 0,
            symbols_total: 0,
            last_error: None,
            profile_name: "sp500".into(),
            stale_snapshots: false,
            refresh: FeedRefresh::default(),
        }
    }
}

impl FeedStatus {
    pub(crate) fn legacy_error(&self) -> Option<String> {
        self.last_error
            .clone()
            .or_else(|| self.refresh.legacy_error())
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
    pub(crate) market_data: Arc<crate::market_data_cache::MarketDataCache>,
    pub feed_status: Arc<Mutex<FeedStatus>>,
    pub(crate) feed_wakeup: Arc<Condvar>,
    pub(crate) save_universe_rows: bool,
    pub(crate) customize_tracked_feed: bool,
    /// Keep the GUI feed's Yahoo cooldown when replacing its worker generation.
    pub(crate) feed_yahoo: Arc<Mutex<Option<Arc<YahooClient>>>>,
    #[cfg(feature = "gui")]
    pub(crate) android_v3_year_charts: Arc<Mutex<crate::android_v3_charts::YearCharts>>,
    pub(crate) edgar_feed_schedules: Arc<Mutex<crate::commands::EdgarFeedSchedules>>,
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
    /// Serializes profile adoption through generation change, state reset, and worker spawn.
    pub profile_apply_gate: Arc<Mutex<()>>,
    /// When true, launch forced the profile; UI must not switch away.
    pub profile_locked: AtomicBool,
    /// Bumped on each universe switch so stale feed workers exit.
    pub feed_generation: Arc<AtomicU64>,
    /// Generation whose one-shot initial retry pass reached a terminal state.
    /// `u64::MAX` means no current generation has completed yet.
    pub initial_pass_completed_generation: Arc<AtomicU64>,
    /// Bumped on every ranking-affecting mutation (quote/fundamentals/year chart).
    /// Combined with feed + pass generations for `/status` ranked_rows cache.
    pub rank_input_generation: Arc<AtomicU64>,
    /// SEC ticker→CIK map (lazy; filled by EDGAR workers / demand valuation).
    pub edgar_cik_map: Arc<Mutex<Option<HashMap<String, u64>>>>,
    /// Symbols with an in-flight demand-driven valuation (avoid duplicate EDGAR hits).
    pub valuation_inflight: Arc<Mutex<HashSet<(u64, String)>>>,
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
        Self::new_with_profile_policy(db_path, forced, false)
    }

    pub(crate) fn new_with_profile_policy(
        db_path: PathBuf,
        forced: Option<ForcedProfile>,
        customize_tracked_feed: bool,
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
        let requested = forced
            .as_ref()
            .map_or("sp500", |profile| profile.name.as_str());
        let membership =
            resolve_tracked_feed_membership(requested, &db, customize_tracked_feed && !locked)?;
        let resolved = membership.profile;
        let profile = resolved.name;
        let symbols = resolved.symbols;
        let stale = resolved.stale_snapshots;
        let db_err = resolved.db_error;
        let mut screener = ScreenerState::new();
        screener.set_feed_exclusions(membership.exclusions);

        let mut last_error = db_err.map(|e| format!("qa db fallback: {e}"));
        if stale {
            let msg = "qa: stale_snapshots (reporting only; membership not excluded)";
            last_error = Some(match last_error {
                Some(e) => format!("{e}; {msg}"),
                None => msg.into(),
            });
        }

        Ok(Self {
            screener: Arc::new(Mutex::new(screener)),
            market_data: Arc::default(),
            feed_status: Arc::new(Mutex::new(FeedStatus {
                profile_name: profile.clone(),
                symbols_total: symbols.len(),
                last_error,
                stale_snapshots: stale,
                ..FeedStatus::default()
            })),
            feed_wakeup: Arc::new(Condvar::new()),
            save_universe_rows: false,
            customize_tracked_feed,
            feed_yahoo: Arc::default(),
            #[cfg(feature = "gui")]
            android_v3_year_charts: Arc::default(),
            edgar_feed_schedules: Arc::new(Mutex::new(
                crate::commands::EdgarFeedSchedules::default(),
            )),
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
            profile_apply_gate: Arc::new(Mutex::new(())),
            profile_locked: AtomicBool::new(locked),
            feed_generation: Arc::new(AtomicU64::new(0)),
            initial_pass_completed_generation: Arc::new(AtomicU64::new(u64::MAX)),
            rank_input_generation: Arc::new(AtomicU64::new(0)),
            edgar_cik_map: Arc::new(Mutex::new(None)),
            valuation_inflight: Arc::new(Mutex::new(HashSet::new())),
            valuation_yahoo,
        })
    }

    pub fn feed_generation_arc(&self) -> Arc<AtomicU64> {
        Arc::clone(&self.feed_generation)
    }

    pub fn bump_rank_inputs(&self) {
        self.rank_input_generation
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }

    pub fn is_profile_locked(&self) -> bool {
        self.profile_locked
            .load(std::sync::atomic::Ordering::Relaxed)
    }
}
