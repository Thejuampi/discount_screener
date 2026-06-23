use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;

use serde::Serialize;
use tauri::State;

use crate::db::{BacktestResult, HistorySnapshot, SnapshotInsert};
use crate::edgar;
use crate::engine::{
    CandidateRow, ConfidenceBand, HistoricalCandle, InsiderData, SymbolDetail, AlertEvent,
    composite_score_v2, compute_chart_summary, compute_sector_benchmarks, compute_setup_score,
    decision_state, score_fundamentals_v2, score_technicals_v3, score_forecast_v2,
};
use crate::fetcher::{YahooClient, DEFAULT_LIVE_SYMBOLS, CRYPTO_SYMBOLS, ETF_SYMBOLS, is_crypto, is_etf, asset_type};
use crate::state::AppState;

const SNAPSHOT_INTERVAL_SECS: u64 = 3600; // capture once per hour

// ── Response types ────────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct OpportunityRow {
    #[serde(flatten)]
    pub row: CandidateRow,
    // AggressiveV2 scores (-100..+100 each, null = insufficient data)
    pub fundamentals_score: Option<i32>,
    pub technical_score: Option<i32>,
    pub forecast_score: Option<i32>,
    pub composite_score: i32,
    pub decision: &'static str,           // "Act" | "Watch" | "Avoid"
    pub fundamentals_signals: Vec<String>,
    pub technical_signals: Vec<String>,
    pub forecast_signals: Vec<String>,
    // DCF from SEC EDGAR (cents/share, null = not yet computed)
    pub dcf_value_cents: Option<i64>,
    // Insider activity (Form 4, 90-day window)
    pub insider_net_shares_90d: Option<i64>,
    pub insider_buy_count: Option<u32>,
    pub insider_sell_count: Option<u32>,
    /// "stock" | "crypto" | "etf"
    pub asset_type: &'static str,
    /// Unified Setup Score combining ALL factors. Use this as the primary action signal.
    pub setup_score: i32,           // -100..+100
    pub setup_label: &'static str,  // "StrongBuy" | "Buy" | "Accumulate" | "Watch" | "Hold" | "Avoid" | "StrongAvoid"
    /// Daily price change vs previous close, in basis points. None if unknown.
    pub daily_change_bps: Option<i32>,
    /// 14-period daily ATR in cents (volatility) — drives stop & position sizing.
    pub atr_cents: Option<i64>,
    /// Recent daily closes (cents, oldest→newest) for an inline sparkline.
    pub spark: Vec<i64>,
}

#[derive(Serialize)]
pub struct FeedStatusResponse {
    pub running: bool,
    pub symbols_loaded: usize,
    pub symbols_total: usize,
    pub last_error: Option<String>,
}

// ── Commands ──────────────────────────────────────────────────────────────────

#[tauri::command]
pub fn get_opportunities(state: State<AppState>) -> Vec<OpportunityRow> {
    let screener = state.screener.lock().unwrap();
    let rows = screener.candidate_rows();
    let benchmarks = compute_sector_benchmarks(&rows);
    rows
        .into_iter()
        .map(|row| {
            let daily = screener.chart_summaries.get(&row.symbol);
            let weekly = screener.weekly_summaries.get(&row.symbol);
            let hourly = screener.hourly_summaries.get(&row.symbol);
            let daily_candles_default: Vec<HistoricalCandle> = Vec::new();
            let daily_candles_ref = screener.daily_candles.get(&row.symbol)
                .unwrap_or(&daily_candles_default);
            let bench = row.sector_name.as_ref().and_then(|s| benchmarks.get(s));
            let (fund_score, fund_signals) = score_fundamentals_v2(&row, bench);
            let (tech_score, tech_signals, tech_breakdown) =
                score_technicals_v3(weekly, daily, hourly, daily_candles_ref);
            let (fore_score, fore_signals) = score_forecast_v2(&row);
            let composite = composite_score_v2(fund_score, tech_score, fore_score);
            let sym_str = row.symbol.as_str();
            let technical_only = is_crypto(sym_str) || is_etf(sym_str);
            let decision  = decision_state(
                row.confidence, row.gap_bps, composite,
                row.free_cash_flow_dollars, row.market_cap_dollars,
                technical_only, tech_score,
            );

            // ── Crypto-specific override ──────────────────────────────────────
            // For crypto symbols, we use the cycle-aware score (halving + drawdown
            // + sentiment) instead of the equity-centric setup score.
            let (setup_score, setup_label) = if is_crypto(sym_str) {
                if let Some(cm) = screener.crypto_metrics.get(sym_str) {
                    (cm.crypto_score, cm.crypto_label)
                } else {
                    // No metrics yet (feed worker hasn't reached this symbol)
                    // Fall back to the equity setup score but cap labels conservatively.
                    compute_setup_score(
                        composite, decision, row.confidence, row.gap_bps,
                        Some(&tech_breakdown),
                        row.free_cash_flow_dollars, row.market_cap_dollars,
                        row.insider_buy_count, row.insider_sell_count,
                        technical_only,
                    )
                }
            } else {
                compute_setup_score(
                    composite, decision, row.confidence, row.gap_bps,
                    Some(&tech_breakdown),
                    row.free_cash_flow_dollars, row.market_cap_dollars,
                    row.insider_buy_count, row.insider_sell_count,
                    technical_only,
                )
            };
            let at = asset_type(sym_str);
            // Daily change: prefer previous close from the quote page, fall back
            // to yesterday's close from the daily candle series.
            let daily_change_bps = if row.previous_close_cents > 0 && row.market_price_cents > 0 {
                Some((((row.market_price_cents - row.previous_close_cents) as f64
                    / row.previous_close_cents as f64) * 10_000.0).round() as i32)
            } else {
                screener.daily_candles.get(sym_str).and_then(|c| {
                    if c.len() >= 2 && row.market_price_cents > 0 {
                        let prev = c[c.len() - 2].close_cents;
                        if prev > 0 {
                            return Some((((row.market_price_cents - prev) as f64
                                / prev as f64) * 10_000.0).round() as i32);
                        }
                    }
                    None
                })
            };
            let dcf = row.dcf_value_cents;
            let spark: Vec<i64> = screener.daily_candles.get(sym_str)
                .map(|c| { let n = c.len(); c[n.saturating_sub(24)..].iter().map(|x| x.close_cents).collect() })
                .unwrap_or_default();
            let ins_net = row.insider_net_shares_90d;
            let ins_buy = row.insider_buy_count;
            let ins_sell = row.insider_sell_count;
            OpportunityRow {
                row,
                fundamentals_score: fund_score,
                technical_score: tech_score,
                forecast_score: fore_score,
                composite_score: composite,
                decision,
                fundamentals_signals: fund_signals,
                technical_signals: tech_signals,
                forecast_signals: fore_signals,
                dcf_value_cents: dcf,
                insider_net_shares_90d: ins_net,
                insider_buy_count: ins_buy,
                insider_sell_count: ins_sell,
                asset_type: at,
                setup_score,
                setup_label,
                daily_change_bps,
                atr_cents: daily.and_then(|d| d.atr_cents),
                spark,
            }
        })
        .collect()
}

#[tauri::command]
pub fn get_symbol_detail(symbol: String, state: State<AppState>) -> Option<SymbolDetail> {
    let screener = state.screener.lock().unwrap();
    screener.detail(&symbol)
}

#[tauri::command]
pub fn get_alerts(state: State<AppState>) -> Vec<AlertEvent> {
    let screener = state.screener.lock().unwrap();
    screener.alerts.iter().rev().take(50).cloned().collect()
}

#[tauri::command]
pub fn get_feed_status(state: State<AppState>) -> FeedStatusResponse {
    let status = state.feed_status.lock().unwrap();
    FeedStatusResponse {
        running: status.running,
        symbols_loaded: status.symbols_loaded,
        symbols_total: DEFAULT_LIVE_SYMBOLS.len() + CRYPTO_SYMBOLS.len() + ETF_SYMBOLS.len(),
        last_error: status.last_error.clone(),
    }
}

#[tauri::command]
pub fn refresh_symbol(symbol: String, state: State<AppState>) -> Result<String, String> {
    let client = YahooClient::new().map_err(|e| e.to_string())?;
    let result = client.fetch_symbol(&symbol).map_err(|e| e.to_string())?;

    let mut screener = state.screener.lock().unwrap();
    if let Some(snap) = result.snapshot { screener.ingest_snapshot(snap); }
    if let Some(sig)  = result.signal   { screener.ingest_signal(sig);   }
    if let Some(fund) = result.fundamentals { screener.ingest_fundamentals(fund); }
    Ok(symbol)
}

#[tauri::command]
pub fn get_candles(
    symbol: String,
    range: String,
    state: State<AppState>,
) -> Result<Vec<HistoricalCandle>, String> {
    let client = YahooClient::new().map_err(|e| e.to_string())?;
    let (range_str, interval_str) = match range.as_str() {
        "1d"  => ("1d", "5m"),
        "5d"  => ("5d", "15m"),
        "1mo" => ("1mo", "1d"),
        "3mo" => ("3mo", "1d"),
        "6mo" => ("6mo", "1wk"),
        "1y"  => ("1y", "1wk"),
        "2y"  => ("2y", "1wk"),
        "5y"  => ("5y", "1mo"),
        _     => ("3mo", "1d"),
    };
    client
        .fetch_candles(&symbol, range_str, interval_str)
        .map_err(|e| e.to_string())
}

// High-priority symbols fetched first so the UI shows useful data within seconds
const PRIORITY_SYMBOLS: &[&str] = &[
    "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "BRK.B", "JPM", "V",
    "UNH", "LLY", "XOM", "MA", "AVGO", "PG", "HD", "COST", "JNJ", "ABBV",
    "MRK", "WMT", "BAC", "NFLX", "CRM", "ORCL", "AMD", "ACN", "TMO", "CSCO",
];

const FEED_WORKERS: usize = 8;

#[tauri::command]
pub fn start_feed(state: State<AppState>) -> Result<(), String> {
    {
        let mut status = state.feed_status.lock().unwrap();
        if status.running {
            return Ok(());
        }
        status.running = true;
        status.last_error = None;
    }

    // Build ordered symbol list: priority first, then ETFs, then crypto, then the rest
    let mut symbols: Vec<&'static str> = PRIORITY_SYMBOLS.to_vec();
    for s in ETF_SYMBOLS.iter() {
        symbols.push(s);
    }
    for s in CRYPTO_SYMBOLS.iter() {
        symbols.push(s);
    }
    for s in DEFAULT_LIVE_SYMBOLS.iter() {
        if !PRIORITY_SYMBOLS.contains(s) {
            symbols.push(s);
        }
    }
    let symbols = Arc::new(symbols);
    let total = symbols.len();

    // Shared cursor — workers claim next symbol atomically
    let cursor = Arc::new(AtomicUsize::new(0));
    let loaded = Arc::new(AtomicUsize::new(0));

    for worker_id in 0..FEED_WORKERS {
        let symbols     = Arc::clone(&symbols);
        let cursor      = Arc::clone(&cursor);
        let loaded      = Arc::clone(&loaded);
        let screener    = Arc::clone(&state.screener);
        let feed_status = Arc::clone(&state.feed_status);
        let fng_cache   = Arc::clone(&state.fng_cache);

        thread::Builder::new()
            .name(format!("feed-{}", worker_id))
            .spawn(move || {
                let client = match YahooClient::new() {
                    Ok(c) => c,
                    Err(e) => {
                        feed_status.lock().unwrap().last_error = Some(e.to_string());
                        return;
                    }
                };

                loop {
                    let idx = cursor.fetch_add(1, Ordering::Relaxed) % total;
                    let sym = symbols[idx];

                    // ── Fetch HTML page (price + analyst + fundamentals) ───────
                    // Some tickers (e.g. DOV at the time of writing) have a broken
                    // Yahoo HTML quote page that 404s indefinitely. We:
                    //   1. Don't bubble 404s to the user-visible status (not actionable)
                    //   2. Still count the symbol as "loaded" so the progress bar advances
                    //   3. Fall through to the chart API below which often still works
                    match client.fetch_symbol(sym) {
                        Ok(result) => {
                            let mut s = screener.lock().unwrap();
                            if let Some(snap) = result.snapshot { s.ingest_snapshot(snap); }
                            if let Some(sig)  = result.signal   { s.ingest_signal(sig);   }
                            if let Some(fund) = result.fundamentals { s.ingest_fundamentals(fund); }
                            drop(s);

                            let mut fs = feed_status.lock().unwrap();
                            let n = loaded.fetch_add(1, Ordering::Relaxed) + 1;
                            fs.symbols_loaded = n.min(total);
                            // Clear stale errors once we get any successful fetch — old
                            // errors from previous cycles shouldn't linger in the footer.
                            fs.last_error = None;
                        }
                        Err(e) => {
                            let msg = e.to_string();
                            // 404 is a permanent quirk of Yahoo's HTML page for some tickers.
                            // 401/403 sometimes appears for crypto/ETF — also not actionable.
                            // Suppress these from the user-visible last_error so the footer
                            // doesn't get spammed by tickers we can't fix.
                            let is_silent = msg.contains("404") || msg.contains("401") || msg.contains("403");
                            if !is_silent {
                                feed_status.lock().unwrap().last_error =
                                    Some(format!("{}: {}", sym, e));
                            }
                            // Still count as "attempted" so the progress bar advances.
                            let n = loaded.fetch_add(1, Ordering::Relaxed) + 1;
                            feed_status.lock().unwrap().symbols_loaded = n.min(total);
                        }
                    }

                    // ── Multi-timeframe technical data ─────────────────────────
                    // Daily (1y / 1d) — primary, also kept raw for pattern detection
                    if let Ok(candles) = client.fetch_candles(sym, "1y", "1d") {
                        if let Some(summary) = compute_chart_summary(&candles) {
                            let mut s = screener.lock().unwrap();
                            s.ingest_chart_summary(sym.to_string(), summary);
                            s.ingest_daily_candles(sym.to_string(), candles);
                        }
                    }
                    thread::sleep(std::time::Duration::from_millis(150));

                    // Weekly (5y / 1wk) — primary trend, enough history for EMA200
                    if let Ok(candles) = client.fetch_candles(sym, "5y", "1wk") {
                        if let Some(summary) = compute_chart_summary(&candles) {
                            let mut s = screener.lock().unwrap();
                            s.ingest_weekly_summary(sym.to_string(), summary.clone());
                            // For crypto: also keep raw weekly candles + compute crypto-aware score
                            if crate::fetcher::is_crypto(sym) {
                                s.ingest_weekly_candles(sym.to_string(), candles.clone());
                                drop(s);
                                // Reuse cached F&G to avoid hammering alternative.me
                                let fng = fng_cache.get_cached().or_else(|| {
                                    let http = crate::crypto_cycle::crypto_client();
                                    let v = crate::crypto_cycle::fetch_fear_greed(&http).ok();
                                    if let Some(ref fng) = v { fng_cache.put(fng.clone()); }
                                    v
                                });
                                let now_e = std::time::SystemTime::now()
                                    .duration_since(std::time::UNIX_EPOCH)
                                    .map(|d| d.as_secs() as i64).unwrap_or(0);
                                let tech_score = Some(0); // placeholder; refined later from full breakdown
                                let metrics = crate::crypto_cycle::compute_crypto_score(
                                    sym, &candles, tech_score, fng, now_e
                                );
                                screener.lock().unwrap().ingest_crypto_metrics(sym.to_string(), metrics);
                            }
                        }
                    }
                    thread::sleep(std::time::Duration::from_millis(150));

                    // Hourly (1mo / 1h) — entry timing for swing
                    if let Ok(candles) = client.fetch_candles(sym, "1mo", "1h") {
                        if let Some(summary) = compute_chart_summary(&candles) {
                            screener.lock().unwrap().ingest_hourly_summary(sym.to_string(), summary);
                        }
                    }
                    thread::sleep(std::time::Duration::from_millis(150));

                    // Monthly (10y / 1mo) — macro cycle for investor view
                    if let Ok(candles) = client.fetch_candles(sym, "10y", "1mo") {
                        if let Some(summary) = compute_chart_summary(&candles) {
                            screener.lock().unwrap().ingest_monthly_summary(sym.to_string(), summary);
                        }
                    }
                    thread::sleep(std::time::Duration::from_millis(300));
                }
            })
            .map_err(|e| e.to_string())?;
    }

    // ── EDGAR DCF worker (separate, low-frequency) ────────────────────────────
    // Fetches SEC EDGAR companyfacts for each symbol once per cycle.
    // Rate-limited to ~8 req/s (SEC allows 10/s). Full cycle ~60-90s.
    {
        let symbols     = Arc::clone(&symbols);
        let screener    = Arc::clone(&state.screener);
        let feed_status = Arc::clone(&state.feed_status);

        thread::Builder::new()
            .name("edgar-dcf".to_string())
            .spawn(move || {
                let edgar_client = edgar::edgar_client();

                // Fetch CIK map once at startup
                let cik_map: HashMap<String, u64> = match edgar::fetch_cik_map(&edgar_client) {
                    Ok(m) => m,
                    Err(e) => {
                        feed_status.lock().unwrap().last_error = Some(format!("EDGAR CIK: {}", e));
                        return;
                    }
                };

                loop {
                    for &sym in symbols.iter() {
                        // Crypto and ETFs are not on SEC EDGAR — skip without lookup
                        if is_crypto(sym) || is_etf(sym) { continue; }
                        let cik = match cik_map.get(sym) {
                            Some(&c) => c,
                            None => continue, // not on EDGAR (ETFs, foreign listings, etc.)
                        };

                        let shares = screener.lock().unwrap()
                            .fundamentals.get(sym)
                            .and_then(|f| f.shares_outstanding)
                            .unwrap_or(0);

                        if shares == 0 { continue; }

                        match edgar::fetch_dcf(&edgar_client, sym, cik, shares) {
                            Ok(Some(dcf)) => {
                                screener.lock().unwrap()
                                    .ingest_dcf(sym.to_string(), dcf.value_per_share_cents);
                            }
                            Ok(None) => {}
                            Err(_) => {} // silently skip (many symbols 404)
                        }
                        thread::sleep(std::time::Duration::from_millis(125));

                        // Insider activity (Form 4) — fetches submissions + each Form 4 XML
                        if let Ok(Some(ins)) = edgar::fetch_insider_activity(&edgar_client, cik) {
                            screener.lock().unwrap().ingest_insider(sym.to_string(), InsiderData {
                                net_shares_90d: ins.net_shares_90d,
                                buy_count: ins.buy_count,
                                sell_count: ins.sell_count,
                            });
                        }
                        thread::sleep(std::time::Duration::from_millis(125));
                    }
                }
            })
            .map_err(|e| e.to_string())?;
    }

    // ── Snapshot worker (history persistence) ─────────────────────────────────
    // Captures the full set of opportunity rows once per hour.
    {
        let screener = Arc::clone(&state.screener);
        let db       = Arc::clone(&state.db);

        thread::Builder::new()
            .name("snapshot".to_string())
            .spawn(move || {
                // Initial delay: wait for first data load before snapshotting
                thread::sleep(std::time::Duration::from_secs(120));
                loop {
                    let now = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_secs() as i64)
                        .unwrap_or(0);

                    // Build snapshot rows from current screener state
                    let rows = {
                        let s = screener.lock().unwrap();
                        let candidates = s.candidate_rows();
                        let bench = compute_sector_benchmarks(&candidates);
                        candidates.into_iter().filter_map(|row| {
                            // Skip rows that don't have a real price yet
                            if row.market_price_cents <= 0 { return None; }
                            let daily = s.chart_summaries.get(&row.symbol);
                            let weekly = s.weekly_summaries.get(&row.symbol);
                            let hourly = s.hourly_summaries.get(&row.symbol);
                            let candles_empty: Vec<HistoricalCandle> = Vec::new();
                            let candles_ref = s.daily_candles.get(&row.symbol).unwrap_or(&candles_empty);
                            let bench_for = row.sector_name.as_ref().and_then(|x| bench.get(x));
                            let (fund_score, _) = score_fundamentals_v2(&row, bench_for);
                            let (tech_score, _, _) = score_technicals_v3(weekly, daily, hourly, candles_ref);
                            let (fore_score, _) = score_forecast_v2(&row);
                            let composite = composite_score_v2(fund_score, tech_score, fore_score);
                            let technical_only = is_crypto(&row.symbol) || is_etf(&row.symbol);
                            let decision  = decision_state(
                                row.confidence, row.gap_bps, composite,
                                row.free_cash_flow_dollars, row.market_cap_dollars,
                                technical_only, tech_score,
                            );
                            Some(SnapshotRowOwned {
                                symbol: row.symbol,
                                captured_at: now,
                                market_price_cents: row.market_price_cents,
                                intrinsic_value_cents: row.intrinsic_value_cents,
                                gap_bps: row.gap_bps,
                                decision: decision.to_string(),
                                composite_score: composite,
                                fundamentals_score: fund_score,
                                technical_score: tech_score,
                                forecast_score: fore_score,
                                confidence: confidence_label(row.confidence).to_string(),
                            })
                        }).collect::<Vec<_>>()
                    };

                    if !rows.is_empty() {
                        let borrowed: Vec<SnapshotInsert> = rows.iter().map(|r| SnapshotInsert {
                            symbol: &r.symbol,
                            captured_at: r.captured_at,
                            market_price_cents: r.market_price_cents,
                            intrinsic_value_cents: r.intrinsic_value_cents,
                            gap_bps: r.gap_bps,
                            decision: &r.decision,
                            composite_score: r.composite_score,
                            fundamentals_score: r.fundamentals_score,
                            technical_score: r.technical_score,
                            forecast_score: r.forecast_score,
                            confidence: &r.confidence,
                        }).collect();
                        let _ = db.insert_snapshots(&borrowed);
                    }

                    thread::sleep(std::time::Duration::from_secs(SNAPSHOT_INTERVAL_SECS));
                }
            })
            .map_err(|e| e.to_string())?;
    }

    Ok(())
}

// ── History / backtest commands ───────────────────────────────────────────────

#[tauri::command]
pub fn get_symbol_history(
    symbol: String,
    days: i64,
    state: State<AppState>,
) -> Result<Vec<HistorySnapshot>, String> {
    state.db.symbol_history(&symbol, days)
}

#[tauri::command]
pub fn get_backtest(
    decision: String,
    days_ago: i64,
    state: State<AppState>,
) -> Result<BacktestResult, String> {
    state.db.backtest(&decision, days_ago)
}

#[derive(Serialize)]
pub struct HistoryStatus {
    pub snapshot_count: i64,
}

#[tauri::command]
pub fn get_history_status(state: State<AppState>) -> Result<HistoryStatus, String> {
    Ok(HistoryStatus {
        snapshot_count: state.db.snapshot_count()?,
    })
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/// Owned version of SnapshotInsert (the SQLite version is borrowed).
/// Used to detach from the `screener` lock before doing the DB write.
struct SnapshotRowOwned {
    symbol: String,
    captured_at: i64,
    market_price_cents: i64,
    intrinsic_value_cents: i64,
    gap_bps: i32,
    decision: String,
    composite_score: i32,
    fundamentals_score: Option<i32>,
    technical_score: Option<i32>,
    forecast_score: Option<i32>,
    confidence: String,
}

fn confidence_label(c: ConfidenceBand) -> &'static str {
    match c {
        ConfidenceBand::High => "High",
        ConfidenceBand::Provisional => "Provisional",
        ConfidenceBand::Low => "Low",
    }
}

// ── Autostart / tray commands ─────────────────────────────────────────────────

#[tauri::command]
pub fn get_autostart_enabled(app: tauri::AppHandle) -> Result<bool, String> {
    use tauri_plugin_autostart::ManagerExt;
    app.autolaunch().is_enabled().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn set_autostart_enabled(app: tauri::AppHandle, enabled: bool) -> Result<(), String> {
    use tauri_plugin_autostart::ManagerExt;
    let manager = app.autolaunch();
    if enabled {
        manager.enable().map_err(|e| e.to_string())
    } else {
        manager.disable().map_err(|e| e.to_string())
    }
}

#[tauri::command]
pub fn quit_app(app: tauri::AppHandle) {
    app.exit(0);
}

// ── Congress Alpha commands ───────────────────────────────────────────────────

#[derive(Serialize)]
pub struct CongressOverview {
    pub politician_count: i64,
    pub trade_count: i64,
    pub top_tickers: Vec<crate::db::CongressTickerRow>,
    pub top_politicians: Vec<crate::db::PoliticianActivityRow>,
}

#[tauri::command]
pub fn get_congress_overview(
    days: Option<i64>,
    state: State<AppState>,
) -> Result<CongressOverview, String> {
    let d = days.unwrap_or(180);
    Ok(CongressOverview {
        politician_count: state.db.count_politicians()?,
        trade_count: state.db.count_congressional_trades()?,
        top_tickers: state.db.top_congress_tickers(d, 30)?,
        top_politicians: state.db.top_politicians_by_activity(30)?,
    })
}

#[tauri::command]
pub fn get_congress_trades_for_symbol(
    symbol: String,
    limit: Option<i64>,
    state: State<AppState>,
) -> Result<Vec<crate::db::CongressTradeWithPolitician>, String> {
    state.db.trades_for_symbol(&symbol, limit.unwrap_or(20))
}

/// Sync House PTRs for a given year. Returns progress summary.
/// This is long-running (~3-5 min for a full year) — UI should show progress.
#[derive(Serialize)]
pub struct CongressBacktestResult {
    pub symbols_processed: usize,
    pub trades_with_outcomes: usize,
    pub politicians_updated: usize,
    pub errors_sample: Vec<String>,
}

/// Run the full backtest: fetch SPY history, then for each symbol with trades
/// fetch history and compute forward returns. Then aggregate per politician.
///
/// This is long-running (~3-10 min depending on # of unique symbols). UI should
/// show progress and run it as a background operation.
#[tauri::command]
pub fn compute_congress_metrics(
    state: State<AppState>,
) -> Result<CongressBacktestResult, String> {
    use crate::congress_scoring::*;

    let client = crate::fetcher::YahooClient::new().map_err(|e| e.to_string())?;
    let mut errors: Vec<String> = Vec::new();

    // 1. Fetch SPY benchmark history
    let spy_candles = fetch_history(&client, "SPY")
        .ok_or_else(|| "Failed to fetch SPY history for benchmark".to_string())?;
    std::thread::sleep(std::time::Duration::from_millis(200));

    // 2. For each unique symbol, fetch history + compute outcomes
    let symbols = state.db.congress_symbols()?;
    let mut total_outcomes = 0usize;
    for (i, symbol) in symbols.iter().enumerate() {
        let candles = match fetch_history(&client, symbol) {
            Some(c) if !c.is_empty() => c,
            _ => {
                if errors.len() < 10 { errors.push(format!("history unavailable: {}", symbol)); }
                std::thread::sleep(std::time::Duration::from_millis(150));
                continue;
            }
        };

        let trades = state.db.trades_with_meta_for_symbol(symbol).unwrap_or_default();
        for t in trades {
            let amt_mid_dollars = match (t.amount_range_min, t.amount_range_max) {
                (Some(a), Some(b)) => (a + b) / 2,
                (Some(a), None) => a,
                (None, Some(b)) => b,
                _ => 0,
            };
            let outcome = compute_outcome(
                t.trade_id,
                &t.disclosure_date,
                &candles,
                &spy_candles,
                &t.transaction_type,
                amt_mid_dollars,
            );
            if outcome.base_price_cents.is_some() {
                let _ = state.db.upsert_outcome(&outcome);
                total_outcomes += 1;
            }
        }
        // Rate limit per symbol (be polite to Yahoo)
        std::thread::sleep(std::time::Duration::from_millis(150));
        let _ = i;
    }

    // 3. Aggregate per politician
    let politicians = state.db.politicians_with_outcomes()?;
    let mut updated = 0usize;
    for pid in &politicians {
        let outcomes = state.db.outcomes_for_politician(*pid).unwrap_or_default();
        let metrics = aggregate_metrics(*pid, &outcomes);
        if state.db.upsert_politician_metrics(&metrics).is_ok() {
            updated += 1;
        }
    }

    Ok(CongressBacktestResult {
        symbols_processed: symbols.len(),
        trades_with_outcomes: total_outcomes,
        politicians_updated: updated,
        errors_sample: errors,
    })
}

#[tauri::command]
pub fn get_top_politicians_ranked(
    sort_key: String,
    limit: Option<i64>,
    state: State<AppState>,
) -> Result<Vec<crate::db::PoliticianWithMetrics>, String> {
    state.db.top_politicians_with_metrics(&sort_key, limit.unwrap_or(50))
}

#[tauri::command]
pub fn get_politician_detail(
    politician_id: i64,
    state: State<AppState>,
) -> Result<(Option<crate::db::PoliticianWithMetrics>, Vec<crate::db::PoliticianTradeRow>), String> {
    let metrics = state.db.get_politician_metrics(politician_id)?;
    let trades = state.db.trades_for_politician(politician_id, 200)?;
    Ok((metrics, trades))
}

/// Start a multi-year sync in a background thread. Returns immediately.
/// Frontend should poll `get_congress_sync_progress` for live status.
#[tauri::command]
pub fn sync_congress_house(
    years: Vec<u32>,
    max_per_year: Option<usize>,
    state: State<AppState>,
) -> Result<bool, String> {
    use crate::state::CongressSyncProgress;

    // Reject if already running
    {
        let mut p = state.congress_sync.lock().map_err(|_| "lock")?;
        if p.running {
            return Err("Sync already in progress".to_string());
        }
        *p = CongressSyncProgress {
            running: true,
            current_year: years.first().copied().unwrap_or(0),
            current_step: "Starting…".to_string(),
            processed: 0,
            total: 0,
            trades_imported: 0,
            years_completed: Vec::new(),
            total_imported_session: 0,
            last_error: None,
        };
    }

    let db = Arc::clone(&state.db);
    let progress = Arc::clone(&state.congress_sync);

    std::thread::spawn(move || {
        let client = crate::congress::congress_client();

        for year in years {
            // Update: starting this year
            {
                let mut p = progress.lock().unwrap();
                p.current_year = year;
                p.current_step = format!("Descargando índice {year}…");
                p.processed = 0;
                p.total = 0;
                p.trades_imported = 0;
            }

            // 1. Fetch index
            let xml = match crate::congress::fetch_year_index(&client, year) {
                Ok(x) => x,
                Err(e) => {
                    progress.lock().unwrap().last_error =
                        Some(format!("Year {year}: {e}"));
                    continue;
                }
            };
            let filings = crate::congress::parse_ptr_filings(&xml, year);
            let total = filings.len();
            let cap = max_per_year.unwrap_or(total).min(total);

            {
                let mut p = progress.lock().unwrap();
                p.total = cap;
                p.current_step = format!("Procesando {cap} PTRs de {year}…");
            }

            // 2. Process each PTR
            let mut year_imported = 0usize;
            for (i, filing) in filings.into_iter().take(cap).enumerate() {
                // Update progress every 5 PTRs to avoid lock contention
                if i % 5 == 0 {
                    let mut p = progress.lock().unwrap();
                    p.processed = i;
                    p.current_step = format!(
                        "Año {year}: PTR {}/{cap} — {}",
                        i + 1,
                        filing.politician.full_name
                    );
                }

                let pol_id = match db.upsert_politician(&filing.politician) {
                    Ok(id) => id,
                    Err(_) => continue,
                };
                let bytes = match crate::congress::fetch_ptr_pdf(&client, year, &filing.doc_id) {
                    Ok(b) => b,
                    Err(_) => continue,
                };
                let trades = match crate::congress::parse_ptr_pdf(&bytes, &filing) {
                    Ok(t) => t,
                    Err(_) => continue,
                };
                for t in &trades {
                    if db.insert_congressional_trade(pol_id, t).unwrap_or(false) {
                        year_imported += 1;
                    }
                }

                // Rate limit per PDF
                std::thread::sleep(std::time::Duration::from_millis(250));
            }

            // Year complete
            {
                let mut p = progress.lock().unwrap();
                p.processed = cap;
                p.trades_imported = year_imported;
                p.total_imported_session += year_imported;
                p.years_completed.push(year);
                p.current_step = format!("✓ Año {year} completo: {year_imported} trades");
            }
        }

        // Done
        {
            let mut p = progress.lock().unwrap();
            p.running = false;
            p.current_step = "✓ Sincronización completa".to_string();
        }
    });

    Ok(true)
}

/// Compute crypto cycle metrics for a given symbol.
/// Combines: technical score (existing), drawdown from ATH, halving cycle phase,
/// Fear & Greed index sentiment. Returns the full breakdown for the UI.
#[tauri::command]
pub fn get_crypto_metrics(
    symbol: String,
    state: State<AppState>,
) -> Result<crate::crypto_cycle::CryptoMetrics, String> {
    use crate::fetcher::is_crypto;
    if !is_crypto(&symbol) {
        return Err(format!("{} is not a crypto symbol", symbol));
    }

    // Pull what we have cached: weekly candles for ATH, daily summary for tech score
    let screener = state.screener.lock().map_err(|_| "screener lock")?;

    // We need raw weekly candles to find ATH. We have weekly_summary but only the
    // computed indicators. So we fetch fresh 5y/1wk for crypto symbols on demand.
    drop(screener);
    let client = crate::fetcher::YahooClient::new().map_err(|e| e.to_string())?;
    let weekly = client.fetch_candles(&symbol, "5y", "1wk")
        .map_err(|e| format!("weekly candles: {}", e))?;

    // Compute basic technical from this (re-use existing engine)
    let chart = crate::engine::compute_chart_summary(&weekly);
    let tech = chart.as_ref().and_then(|c| {
        let (s, _, _) = crate::engine::score_technicals_v3(Some(c), Some(c), Some(c), &weekly);
        s
    });

    // Fetch Fear & Greed (cached for 1h)
    let fng = if let Some(cached) = state.fng_cache.get_cached() {
        Some(cached)
    } else {
        let http = crate::crypto_cycle::crypto_client();
        match crate::crypto_cycle::fetch_fear_greed(&http) {
            Ok(v) => {
                state.fng_cache.put(v.clone());
                Some(v)
            }
            Err(_) => None,
        }
    };

    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);

    let metrics = crate::crypto_cycle::compute_crypto_score(&symbol, &weekly, tech, fng, now);
    Ok(metrics)
}

/// Get current sync progress for the UI to poll.
#[tauri::command]
pub fn get_congress_sync_progress(
    state: State<AppState>,
) -> Result<crate::state::CongressSyncProgress, String> {
    let p = state.congress_sync.lock().map_err(|_| "lock")?;
    Ok(p.clone())
}

// ── Portfolio / Advisor commands ──────────────────────────────────────────────

#[tauri::command]
pub fn portfolio_list(state: State<AppState>) -> Result<Vec<crate::db::PortfolioPosition>, String> {
    state.db.portfolio_list()
}

#[tauri::command]
pub fn portfolio_add(
    symbol: String,
    quantity: f64,
    avg_cost_cents: i64,
    opened_at: Option<String>,
    notes: Option<String>,
    state: State<AppState>,
) -> Result<i64, String> {
    let sym = symbol.trim().to_uppercase();
    if sym.is_empty() { return Err("symbol required".into()); }
    if quantity <= 0.0 { return Err("quantity must be > 0".into()); }
    if avg_cost_cents <= 0 { return Err("avg cost must be > 0".into()); }
    state.db.portfolio_add(&sym, quantity, avg_cost_cents, opened_at, notes)
}

#[tauri::command]
pub fn portfolio_update(
    id: i64,
    quantity: f64,
    avg_cost_cents: i64,
    opened_at: Option<String>,
    notes: Option<String>,
    state: State<AppState>,
) -> Result<(), String> {
    if quantity <= 0.0 { return Err("quantity must be > 0".into()); }
    state.db.portfolio_update(id, quantity, avg_cost_cents, opened_at, notes)
}

/// One aggregated position coming from a CSV bulk import.
#[derive(serde::Deserialize)]
pub struct ImportPosition {
    pub symbol: String,
    pub quantity: f64,
    pub avg_cost_cents: i64,
    pub opened_at: Option<String>,
}

#[derive(Serialize)]
pub struct PortfolioImportResult {
    pub created: usize,
    pub updated: usize,
    pub skipped: usize,
}

/// Bulk import: upsert each position keyed by symbol.
/// The frontend has already aggregated buy/sell transactions into net positions.
#[tauri::command]
pub fn portfolio_import(
    positions: Vec<ImportPosition>,
    state: State<AppState>,
) -> Result<PortfolioImportResult, String> {
    let mut created = 0usize;
    let mut updated = 0usize;
    let mut skipped = 0usize;
    for p in positions {
        let sym = p.symbol.trim().to_uppercase();
        if sym.is_empty() || p.quantity <= 0.0 || p.avg_cost_cents <= 0 {
            skipped += 1;
            continue;
        }
        match state.db.portfolio_upsert_by_symbol(&sym, p.quantity, p.avg_cost_cents, p.opened_at) {
            Ok(true) => created += 1,
            Ok(false) => updated += 1,
            Err(_) => skipped += 1,
        }
    }
    Ok(PortfolioImportResult { created, updated, skipped })
}

// ── Crypto Scalping ─────────────────────────────────────────────────────────────

/// Candles for the scalping chart (Coinbase, single timeframe).
#[tauri::command]
pub fn get_scalp_candles(product: String, timeframe: String) -> Result<Vec<crate::engine::HistoricalCandle>, String> {
    crate::crypto_md::fetch_candles(&product, &timeframe)
}

/// Set the product the real-time WebSocket feed should stream ticks for.
#[tauri::command]
pub fn scalp_ws_subscribe(product: String, state: State<AppState>) -> Result<(), String> {
    state.scalp_ws_tx.send(product.trim().to_uppercase()).map_err(|e| e.to_string())
}

/// Full multi-timeframe scalping analysis: per-TF indicators + score + signal.
/// `rr` is the reward:risk target (default 1.5); `fee_pct` is the per-side fee in
/// percent (default 0.6) so take-profit economics are net of round-trip fees.
#[tauri::command]
pub fn get_scalp_analysis(product: String, rr: Option<f64>, fee_pct: Option<f64>) -> Result<crate::scalping::ScalpAnalysis, String> {
    let rr = rr.unwrap_or(1.5).clamp(0.5, 5.0);
    let fee_pct = fee_pct.unwrap_or(0.6).clamp(0.0, 5.0);
    crate::scalping::analyze(&product, rr, fee_pct)
}

// ── Email notifications ─────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct EmailConfigView {
    pub smtp_host: Option<String>,
    pub smtp_port: Option<i64>,
    pub username: Option<String>,
    pub from_email: Option<String>,
    pub to_email: Option<String>,
    pub has_password: bool,
    pub enabled: bool,
    pub daily_digest: bool,
    pub digest_hour: i64,
    pub instant_alerts: bool,
    pub last_digest_date: Option<String>,
}

#[tauri::command]
pub fn email_config_get(state: State<AppState>) -> Result<EmailConfigView, String> {
    let c = state.db.email_config_get()?;
    Ok(EmailConfigView {
        smtp_host: c.smtp_host,
        smtp_port: c.smtp_port,
        username: c.username,
        from_email: c.from_email,
        to_email: c.to_email,
        has_password: c.password.as_deref().map(|p| !p.is_empty()).unwrap_or(false),
        enabled: c.enabled,
        daily_digest: c.daily_digest,
        digest_hour: c.digest_hour,
        instant_alerts: c.instant_alerts,
        last_digest_date: c.last_digest_date,
    })
}

#[allow(clippy::too_many_arguments)]
#[tauri::command]
pub fn email_config_set(
    smtp_host: String, smtp_port: i64, username: String, password: Option<String>,
    from_email: String, to_email: String, enabled: bool,
    daily_digest: bool, digest_hour: i64, instant_alerts: bool,
    state: State<AppState>,
) -> Result<(), String> {
    // Empty password string means "keep existing"; a real value replaces it.
    let pass = password.filter(|p| !p.is_empty());
    state.db.email_config_set(
        smtp_host.trim(), smtp_port, username.trim(), pass,
        from_email.trim(), to_email.trim(), enabled,
        daily_digest, digest_hour.clamp(0, 23), instant_alerts,
    )
}

/// Send an email using the stored SMTP config. Content is composed by the UI.
#[tauri::command]
pub fn email_send(subject: String, html: String, text: String, state: State<AppState>) -> Result<(), String> {
    let cfg = state.db.email_config_get()?;
    crate::email::send(&cfg, &subject, &html, &text)
}

#[tauri::command]
pub fn email_mark_digest_sent(date: String, state: State<AppState>) -> Result<(), String> {
    state.db.email_mark_digest_sent(date.trim())
}

// ── Schwab connection (OAuth + market data) ─────────────────────────────────────

#[derive(Serialize)]
pub struct SchwabStatus {
    pub configured: bool,        // app key + secret stored
    pub connected: bool,         // has a usable token (access or refreshable)
    pub needs_reauth: bool,      // refresh token expired
    pub access_valid_until: Option<i64>,
    pub refresh_valid_until: Option<i64>,
    pub callback: Option<String>,
}

#[tauri::command]
pub fn schwab_set_credentials(
    app_key: String, secret: String, callback: String, state: State<AppState>,
) -> Result<(), String> {
    let k = app_key.trim();
    let s = secret.trim();
    let c = callback.trim();
    if k.is_empty() || s.is_empty() || c.is_empty() {
        return Err("app key, secret y callback son obligatorios".into());
    }
    state.db.schwab_set_credentials(k, s, c)
}

#[tauri::command]
pub fn schwab_auth_url(state: State<AppState>) -> Result<String, String> {
    let auth = state.db.schwab_auth_get()?.ok_or("Schwab no configurado")?;
    match (auth.app_key, auth.callback) {
        (Some(k), Some(c)) => Ok(crate::schwab_api::build_auth_url(&k, &c)),
        _ => Err("Falta app key o callback".into()),
    }
}

#[tauri::command]
pub fn schwab_complete_auth(redirect_url: String, state: State<AppState>) -> Result<(), String> {
    crate::schwab_api::complete_auth(&state.db, redirect_url.trim()).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn schwab_disconnect(state: State<AppState>) -> Result<(), String> {
    state.db.schwab_clear()
}

#[tauri::command]
pub fn schwab_status(state: State<AppState>) -> Result<SchwabStatus, String> {
    let auth = state.db.schwab_auth_get()?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
    let st = match auth {
        None => SchwabStatus {
            configured: false, connected: false, needs_reauth: false,
            access_valid_until: None, refresh_valid_until: None, callback: None,
        },
        Some(a) => {
            let configured = a.app_key.is_some() && a.secret.is_some();
            let refresh_valid = a.refresh_expires_at.map(|e| e > now).unwrap_or(false);
            let has_tokens = a.refresh_token.is_some();
            SchwabStatus {
                configured,
                connected: has_tokens && refresh_valid,
                needs_reauth: has_tokens && !refresh_valid,
                access_valid_until: a.access_expires_at,
                refresh_valid_until: a.refresh_expires_at,
                callback: a.callback,
            }
        }
    };
    Ok(st)
}

// ── Data provenance / cross-validation ─────────────────────────────────────────

#[derive(Serialize)]
pub struct PriceProvenance {
    pub symbol: String,
    pub schwab_cents: Option<i64>, // None until Schwab is connected
    pub yahoo_cents: Option<i64>,
    pub stooq_cents: Option<i64>,
    pub consensus_cents: Option<i64>, // median of available sources
    pub spread_bps: Option<i32>,      // max-min disagreement across sources
    pub agree: bool,                  // spread within tolerance (≤1.5%)
    pub sources_ok: u32,
}

/// Cross-check a symbol's price across independent sources so a single bad/stale
/// feed can't silently poison the signal. On-demand (used by the detail panel).
#[tauri::command]
pub fn get_price_provenance(symbol: String, state: State<AppState>) -> Result<PriceProvenance, String> {
    let sym = symbol.trim().to_uppercase();
    let client = YahooClient::new().map_err(|e| e.to_string())?;

    // Schwab is the *preferred* source when connected; Yahoo/Stooq are the net.
    let schwab_cents = crate::schwab_api::quote_cents(&state.db, &sym).filter(|p| *p > 0);
    // Live Yahoo price via the chart API (more stable than HTML scraping).
    let yahoo_cents = client
        .fetch_candles(&sym, "1d", "5m")
        .ok()
        .and_then(|c| c.last().map(|x| x.close_cents))
        .filter(|p| *p > 0);
    let stooq_cents = crate::stooq::fetch_quote_cents(&sym).filter(|p| *p > 0);

    let mut vals: Vec<i64> = [schwab_cents, yahoo_cents, stooq_cents]
        .into_iter()
        .flatten()
        .collect();
    vals.sort_unstable();
    let sources_ok = vals.len() as u32;

    // Consensus: Schwab wins when present (user's chosen primary); otherwise the
    // median of the keyless sources.
    let consensus_cents = match (schwab_cents, vals.len()) {
        (Some(s), _) => Some(s),
        (None, 0) => None,
        (None, n) if n % 2 == 1 => Some(vals[n / 2]),
        (None, n) => Some((vals[n / 2 - 1] + vals[n / 2]) / 2),
    };
    let spread_bps = if vals.len() >= 2 {
        let (lo, hi) = (vals[0], vals[vals.len() - 1]);
        if lo > 0 {
            Some((((hi - lo) as f64 / lo as f64) * 10_000.0).round() as i32)
        } else { None }
    } else { None };
    let agree = spread_bps.map(|s| s <= 150).unwrap_or(true);

    Ok(PriceProvenance {
        symbol: sym,
        schwab_cents,
        yahoo_cents,
        stooq_cents,
        consensus_cents,
        spread_bps,
        agree,
        sources_ok,
    })
}

// ── Investment journal ────────────────────────────────────────────────────────

#[tauri::command]
pub fn journal_list(state: State<AppState>) -> Result<Vec<crate::db::JournalEntry>, String> {
    state.db.journal_list()
}

#[tauri::command]
pub fn journal_add(
    symbol: String,
    action: String,
    thesis: Option<String>,
    price_cents: Option<i64>,
    setup_score: Option<i64>,
    setup_label: Option<String>,
    state: State<AppState>,
) -> Result<i64, String> {
    let sym = symbol.trim().to_uppercase();
    if sym.is_empty() { return Err("symbol required".into()); }
    if action.trim().is_empty() { return Err("action required".into()); }
    let thesis = thesis.filter(|s| !s.trim().is_empty());
    state.db.journal_add(&sym, action.trim(), thesis, price_cents, setup_score, setup_label)
}

#[tauri::command]
pub fn journal_close(
    id: i64,
    outcome: Option<String>,
    exit_price_cents: Option<i64>,
    state: State<AppState>,
) -> Result<(), String> {
    state.db.journal_close(id, outcome.filter(|s| !s.trim().is_empty()), exit_price_cents)
}

#[tauri::command]
pub fn journal_delete(id: i64, state: State<AppState>) -> Result<(), String> {
    state.db.journal_delete(id)
}

/// Resolve current prices for arbitrary symbols. Checks the in-memory screener
/// snapshots first (instant, zero network), then falls back to Yahoo's chart
/// API for symbols outside the app's universe (e.g. custom portfolio holdings).
#[tauri::command]
pub async fn get_quote_prices(
    symbols: Vec<String>,
    state: State<'_, AppState>,
) -> Result<HashMap<String, i64>, String> {
    // Run the (blocking) cache read + network fallback on a worker thread so the
    // UI thread never stalls while custom holdings resolve their prices.
    let screener = state.screener.clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<HashMap<String, i64>, String> {
        let mut out: HashMap<String, i64> = HashMap::new();
        let mut missing: Vec<String> = Vec::new();
        {
            let s = screener.lock().map_err(|_| "screener lock")?;
            for sym in symbols {
                let key = sym.trim().to_uppercase();
                if let Some(snap) = s.snapshots.get(&key) {
                    if snap.market_price_cents > 0 {
                        out.insert(key, snap.market_price_cents);
                        continue;
                    }
                }
                missing.push(key);
            }
        }
        if !missing.is_empty() {
            let client = crate::fetcher::YahooClient::new().map_err(|e| e.to_string())?;
            for sym in missing {
                if let Ok(candles) = client.fetch_candles(&sym, "5d", "1d") {
                    if let Some(last) = candles.last() {
                        out.insert(sym.clone(), last.close_cents);
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(150));
            }
        }
        Ok(out)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
pub fn portfolio_delete(id: i64, state: State<AppState>) -> Result<(), String> {
    state.db.portfolio_delete(id)
}

#[tauri::command]
pub fn get_model_accuracy(
    horizon_days: i64,
    state: State<AppState>,
) -> Result<Vec<crate::db::AccuracyRow>, String> {
    state.db.model_accuracy(horizon_days.clamp(1, 365))
}

// ── Schwab commands ───────────────────────────────────────────────────────────

#[tauri::command]
pub fn import_schwab_pdf(
    bytes: Vec<u8>,
    filename: Option<String>,
    state: State<AppState>,
) -> Result<crate::schwab::SchwabReport, String> {
    let report = crate::schwab::parse_schwab_pdf(&bytes, filename)?;
    state.db.upsert_schwab_report(&report)?;
    Ok(report)
}

#[tauri::command]
pub fn get_schwab_report(
    symbol: String,
    state: State<AppState>,
) -> Result<Option<crate::schwab::SchwabReport>, String> {
    state.db.get_schwab_report(&symbol)
}

#[tauri::command]
pub fn count_schwab_reports(state: State<AppState>) -> Result<i64, String> {
    state.db.count_schwab_reports()
}

#[tauri::command]
pub fn delete_schwab_report(symbol: String, state: State<AppState>) -> Result<(), String> {
    state.db.delete_schwab_report(&symbol)
}

// ── News commands ─────────────────────────────────────────────────────────────

#[tauri::command]
pub fn get_news(symbol: String, state: State<AppState>) -> Result<crate::news::NewsBundle, String> {
    // Hit cache first to avoid hammering Yahoo on rapid re-selections
    if let Some(cached) = state.news_cache.get(&symbol) {
        return Ok(cached);
    }
    let client = crate::news::news_client();
    let bundle = crate::news::fetch_news(&client, &symbol)?;
    state.news_cache.put(symbol, bundle.clone());
    Ok(bundle)
}
