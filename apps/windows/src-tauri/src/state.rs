use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use crate::engine::ScreenerState;
use crate::db::Db;
use crate::news::NewsCache;
use crate::crypto_cycle::FngCache;

#[derive(Clone)]
pub struct FeedStatus {
    pub running: bool,
    pub symbols_loaded: usize,
    pub last_error: Option<String>,
}

impl Default for FeedStatus {
    fn default() -> Self {
        Self { running: false, symbols_loaded: 0, last_error: None }
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

pub struct AppState {
    pub screener: Arc<Mutex<ScreenerState>>,
    pub feed_status: Arc<Mutex<FeedStatus>>,
    pub db: Arc<Db>,
    pub news_cache: Arc<NewsCache>,
    pub congress_sync: Arc<Mutex<CongressSyncProgress>>,
    pub fng_cache: Arc<FngCache>,
    /// Carries the active scalping product to the WebSocket background task.
    pub scalp_ws_tx: tokio::sync::watch::Sender<String>,
}

impl AppState {
    pub fn new(db_path: PathBuf) -> Self {
        let db = Db::open(db_path).expect("open history db");
        let (scalp_ws_tx, _) = tokio::sync::watch::channel(String::new());
        Self {
            screener: Arc::new(Mutex::new(ScreenerState::new())),
            feed_status: Arc::new(Mutex::new(FeedStatus::default())),
            db: Arc::new(db),
            news_cache: Arc::new(NewsCache::new()),
            congress_sync: Arc::new(Mutex::new(CongressSyncProgress::default())),
            fng_cache: Arc::new(FngCache::new()),
            scalp_ws_tx,
        }
    }
}
