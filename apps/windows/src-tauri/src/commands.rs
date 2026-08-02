use std::collections::{HashMap, HashSet};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;

use serde::Serialize;
use tauri::State;

use crate::analyst_forecasts::{AnalystForecastPanel, ForecastPricePoint, TipRanksSettingsStatus};
use crate::db::{BacktestResult, HistorySnapshot, SnapshotInsert};
use crate::edgar;
use crate::engine::{
    composite_score_v2, compute_chart_summary, compute_sector_benchmarks, compute_setup_score,
    decision_state, score_forecast_v2, score_fundamentals_v2, score_technicals_v3, AlertEvent,
    CandidateRow, ConfidenceBand, HistoricalCandle, InsiderData, SymbolDetail,
};
use crate::fetcher::{
    asset_type, etf_sector, is_crypto, is_enrichment_complete, is_etf, is_list_ready,
    ForwardForecastFetchError, YahooClient,
};
use crate::opportunity_v3::{
    composite_score_v3, composite_score_v3_ext, composite_score_v3_short_ext, decision_state_v3,
    invert_bucket, invert_composite, score_forecast_v3, score_fundamentals_v3,
    score_opportunity_technicals_v3, setup_from_v3_composite, ScoringModel,
};
use crate::profiles::{
    compose_universe, profile_definitions, profile_symbols, resolve_profile_membership,
    resolve_profile_name, QA_MAX_SYMBOLS,
};
use crate::state::AppState;
use crate::ticker_search::{
    local_universe_candidates, merge_and_rank, normalize_search_query_key, remote_candidates,
    remote_search_query_variants, resolve_search_submit, should_trigger_remote_search,
    SearchSubmitOutcome, TickerSearchResult, YahooSearchQuote,
};

const SNAPSHOT_INTERVAL_SECS: u64 = 3600; // capture once per hour

struct ValuationInflightGuard {
    symbol: String,
    inflight: Arc<Mutex<HashSet<String>>>,
}

impl Drop for ValuationInflightGuard {
    fn drop(&mut self) {
        if let Ok(mut inflight) = self.inflight.lock() {
            inflight.remove(&self.symbol);
        }
    }
}

// ── Response types ────────────────────────────────────────────────────────────

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum RegimeScoreStatus {
    Included,
    Disabled,
    Unavailable,
    NotApplicable,
}

fn resolve_regime_score_status(
    model: ScoringModel,
    is_equity: bool,
    toggle_enabled: bool,
    policy_available: bool,
    regime_score: Option<i32>,
) -> RegimeScoreStatus {
    if model == ScoringModel::AggressiveV2 || !is_equity {
        RegimeScoreStatus::NotApplicable
    } else if !toggle_enabled {
        RegimeScoreStatus::Disabled
    } else if !policy_available || regime_score.is_none() {
        RegimeScoreStatus::Unavailable
    } else {
        RegimeScoreStatus::Included
    }
}

#[derive(Serialize)]
pub struct OpportunityRow {
    #[serde(flatten)]
    pub row: CandidateRow,
    // Bucket scores (-100..+100 each, null = insufficient data). Under short_v3 these are inverted.
    pub fundamentals_score: Option<i32>,
    pub technical_score: Option<i32>,
    pub forecast_score: Option<i32>,
    /// 4th V3 bucket: fit with active market-regime policy (null if off/unavailable).
    pub regime_score: Option<i32>,
    pub composite_score: i32,
    /// Classic 3-bucket V3 composite (debug / tooltip parity).
    pub composite_score_base: i32,
    pub decision: &'static str, // "Act" | "Watch" | "Avoid"
    pub fundamentals_signals: Vec<String>,
    pub technical_signals: Vec<String>,
    pub forecast_signals: Vec<String>,
    pub regime_signals: Vec<String>,
    /// Typed regime causes (preferred by the presentation layer).
    pub regime_causes: Vec<crate::regime::RegimeCause>,
    /// Why market context is unavailable when status is Unavailable.
    pub regime_unavailable_reason: Option<crate::regime::MarketContextUnavailableReason>,
    pub regime_status: RegimeScoreStatus,
    // DCF from SEC EDGAR (cents/share, null = not yet computed)
    pub dcf_value_cents: Option<i64>,
    // Insider activity (Form 4, 90-day window)
    pub insider_net_shares_90d: Option<i64>,
    pub insider_buy_count: Option<u32>,
    pub insider_sell_count: Option<u32>,
    /// "stock" | "crypto" | "etf"
    pub asset_type: &'static str,
    /// Unified Setup Score combining ALL factors. Use this as the primary action signal.
    pub setup_score: i32, // -100..+100
    pub setup_label: &'static str, // "StrongBuy" | "Buy" | "Accumulate" | "Watch" | "Hold" | "Avoid" | "StrongAvoid"
    /// Daily price change vs previous close, in basis points. None if unknown.
    pub daily_change_bps: Option<i32>,
    /// 14-period daily ATR in cents (volatility) — drives stop & position sizing.
    pub atr_cents: Option<i64>,
    /// Recent daily closes (cents, oldest→newest) for an inline sparkline.
    pub spark: Vec<i64>,
    /// Compact multi-anchor price path (Dashboard 2.0). None when price missing.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub price_path: Option<crate::price_path::CompactPricePath>,
}

#[derive(Serialize)]
pub struct FeedStatusResponse {
    pub running: bool,
    pub symbols_loaded: usize,
    pub symbols_total: usize,
    pub last_error: Option<String>,
    pub profile_name: String,
    pub profile_locked: bool,
    pub stale_snapshots: bool,
}

#[derive(Serialize)]
pub struct UniverseProfileInfo {
    pub name: String,
    pub description: String,
    pub symbol_count: usize,
}

#[derive(Serialize)]
pub struct UniverseProfileStatus {
    pub name: String,
    pub symbols_total: usize,
    pub symbols_loaded: usize,
    pub profile_locked: bool,
    pub stale_snapshots: bool,
}

// ── Commands ──────────────────────────────────────────────────────────────────

#[tauri::command]
pub fn get_opportunities(state: State<AppState>) -> Vec<OpportunityRow> {
    use std::sync::atomic::Ordering;

    let apply_regime = state.apply_regime_scoring.load(Ordering::Relaxed);
    // Never compute regime inline here — that path hits Yahoo/CNN and would block the
    // opportunity list (polled every few seconds). Use cache only (stale-while-revalidate);
    // background worker + get_market_regime / toggle keep the cache warm.
    let regime_snapshot = if apply_regime {
        let snap = state.regime_cache.get();
        if snap.is_none() || state.regime_cache.needs_refresh() {
            crate::regime::request_regime_refresh(&state);
        }
        snap
    } else {
        None
    };
    build_opportunity_rows(&state.screener, apply_regime, regime_snapshot)
}

fn build_opportunity_rows(
    screener: &std::sync::Mutex<crate::engine::ScreenerState>,
    apply_regime: bool,
    regime_snapshot: Option<crate::regime::MarketRegime>,
) -> Vec<OpportunityRow> {
    use crate::regime::{score_regime_fit, RegimeScoringPolicy, ScoreSide};

    let policy_long = regime_snapshot
        .as_ref()
        .and_then(|r| RegimeScoringPolicy::from_regime(r, ScoreSide::Long));
    let policy_short = regime_snapshot
        .as_ref()
        .and_then(|r| RegimeScoringPolicy::from_regime(r, ScoreSide::Short));

    let mut screener = screener.lock().unwrap();
    // Purge/replace stale FCFF caches for financials before scoring/list DCF values.
    let recon_syms: Vec<String> = screener.fundamentals.keys().cloned().collect();
    for sym in recon_syms {
        screener.ensure_model_routed_valuation(&sym);
    }
    let rows = screener.candidate_rows();
    let benchmarks = compute_sector_benchmarks(&rows);
    rows.into_iter()
        .map(|row| {
            let daily = screener.chart_summaries.get(&row.symbol);
            let weekly = screener.weekly_summaries.get(&row.symbol);
            let hourly = screener.hourly_summaries.get(&row.symbol);
            let daily_candles_default: Vec<HistoricalCandle> = Vec::new();
            let daily_candles_ref = screener
                .daily_candles
                .get(&row.symbol)
                .unwrap_or(&daily_candles_default);
            let bench = row.sector_name.as_ref().and_then(|s| benchmarks.get(s));
            let model = ScoringModel::parse(&screener.scoring_model);
            let dcf_analysis = screener.selected_dcf_analysis(&row.symbol);
            let equity = !is_crypto(row.symbol.as_str()) && !is_etf(row.symbol.as_str());
            let (
                fund_score,
                fund_signals,
                tech_score,
                tech_signals,
                tech_breakdown,
                fore_score,
                fore_signals,
                regime_score,
                regime_signals,
                regime_causes,
                regime_unavailable_reason,
                composite,
                composite_base,
                decision,
                regime_status,
            ) = match model {
                ScoringModel::AggressiveV2 => {
                    let (fs, fsig) = score_fundamentals_v2(&row, bench);
                    let (ts, tsig, tb) =
                        score_technicals_v3(weekly, daily, hourly, daily_candles_ref);
                    let (fr, frsig) = score_forecast_v2(&row);
                    let comp = composite_score_v2(fs, ts, fr);
                    let tech_only = is_crypto(row.symbol.as_str()) || is_etf(row.symbol.as_str());
                    let dec = decision_state(
                        row.confidence,
                        row.gap_bps,
                        comp,
                        row.free_cash_flow_dollars,
                        row.market_cap_dollars,
                        tech_only,
                        ts,
                    );
                    (
                        fs,
                        fsig,
                        ts,
                        tsig,
                        tb,
                        fr,
                        frsig,
                        None,
                        vec![],
                        vec![],
                        None,
                        comp,
                        comp,
                        dec,
                        RegimeScoreStatus::NotApplicable,
                    )
                }
                ScoringModel::AggressiveV3 => {
                    let (fs, fsig) = score_fundamentals_v3(&row);
                    let (ts, tsig) = score_opportunity_technicals_v3(daily);
                    let (_, _, tb) = score_technicals_v3(weekly, daily, hourly, daily_candles_ref);
                    let (fr, frsig) = score_forecast_v3(&row, dcf_analysis);
                    let base = composite_score_v3(fs, ts, fr, row.beta_millis);
                    let (rs, rsig, rcauses, runavail, haircut_mult) = if apply_regime && equity {
                        if let Some(ref pol) = policy_long {
                            let fit = score_regime_fit(&row, daily, pol);
                            (
                                fit.score,
                                fit.signals,
                                fit.causes,
                                fit.unavailable_reason,
                                pol.beta_haircut_mult,
                            )
                        } else {
                            (
                                None,
                                vec![],
                                vec![],
                                Some(crate::regime::MarketContextUnavailableReason::MarketReadingUnavailable),
                                1.0,
                            )
                        }
                    } else {
                        (None, vec![], vec![], None, 1.0)
                    };
                    let status = resolve_regime_score_status(
                        model,
                        equity,
                        apply_regime,
                        policy_long.is_some(),
                        rs,
                    );
                    let comp = if status == RegimeScoreStatus::Included {
                        composite_score_v3_ext(fs, ts, fr, rs, row.beta_millis, haircut_mult)
                    } else {
                        base
                    };
                    let dec = decision_state_v3(comp);
                    (
                        fs,
                        fsig,
                        ts,
                        tsig,
                        tb,
                        fr,
                        frsig,
                        rs,
                        rsig,
                        rcauses,
                        runavail,
                        comp,
                        base,
                        dec,
                        status,
                    )
                }
                ScoringModel::ShortV3 => {
                    let (fs0, fsig) = score_fundamentals_v3(&row);
                    let (ts0, tsig) = score_opportunity_technicals_v3(daily);
                    let (_, _, tb) = score_technicals_v3(weekly, daily, hourly, daily_candles_ref);
                    let (fr0, frsig) = score_forecast_v3(&row, dcf_analysis);
                    let (rs, rsig, rcauses, runavail, haircut_mult) = if apply_regime && equity {
                        if let Some(ref pol) = policy_short {
                            let fit = score_regime_fit(&row, daily, pol);
                            (
                                fit.score,
                                fit.signals,
                                fit.causes,
                                fit.unavailable_reason,
                                pol.beta_haircut_mult,
                            )
                        } else {
                            (
                                None,
                                vec![],
                                vec![],
                                Some(crate::regime::MarketContextUnavailableReason::MarketReadingUnavailable),
                                1.0,
                            )
                        }
                    } else {
                        (None, vec![], vec![], None, 1.0)
                    };
                    let long_base = composite_score_v3(fs0, ts0, fr0, row.beta_millis);
                    let fs = invert_bucket(fs0);
                    let ts = invert_bucket(ts0);
                    let fr = invert_bucket(fr0);
                    let base = invert_composite(long_base);
                    let status = resolve_regime_score_status(
                        model,
                        equity,
                        apply_regime,
                        policy_short.is_some(),
                        rs,
                    );
                    let comp = if status == RegimeScoreStatus::Included {
                        composite_score_v3_short_ext(fs, ts, fr, rs, row.beta_millis, haircut_mult)
                    } else {
                        base
                    };
                    let dec = decision_state_v3(comp);
                    (
                        fs,
                        fsig,
                        ts,
                        tsig,
                        tb,
                        fr,
                        frsig,
                        rs,
                        rsig,
                        rcauses,
                        runavail,
                        comp,
                        base,
                        dec,
                        status,
                    )
                }
            };
            let sym_str = row.symbol.as_str();
            let technical_only = is_crypto(sym_str) || is_etf(sym_str);

            // ── Setup column ──────────────────────────────────────────────────
            // V3: setup_score == composite (Android ranking parity).
            // V2 / crypto: Windows setup helper (or crypto cycle score).
            let (setup_score, setup_label) = if is_crypto(sym_str) {
                if let Some(cm) = screener.crypto_metrics.get(sym_str) {
                    (cm.crypto_score, cm.crypto_label)
                } else {
                    compute_setup_score(
                        composite,
                        decision,
                        row.confidence,
                        row.gap_bps,
                        Some(&tech_breakdown),
                        row.free_cash_flow_dollars,
                        row.market_cap_dollars,
                        row.insider_buy_count,
                        row.insider_sell_count,
                        technical_only,
                    )
                }
            } else if model == ScoringModel::AggressiveV3 || model == ScoringModel::ShortV3 {
                // V3 long and short: setup mirrors composite (short uses inverted composite).
                setup_from_v3_composite(composite)
            } else {
                compute_setup_score(
                    composite,
                    decision,
                    row.confidence,
                    row.gap_bps,
                    Some(&tech_breakdown),
                    row.free_cash_flow_dollars,
                    row.market_cap_dollars,
                    row.insider_buy_count,
                    row.insider_sell_count,
                    technical_only,
                )
            };
            let at = asset_type(sym_str);
            // Daily change: prefer previous close from the quote page, fall back
            // to yesterday's close from the daily candle series.
            let daily_change_bps = if row.previous_close_cents > 0 && row.market_price_cents > 0 {
                Some(
                    (((row.market_price_cents - row.previous_close_cents) as f64
                        / row.previous_close_cents as f64)
                        * 10_000.0)
                        .round() as i32,
                )
            } else {
                screener.daily_candles.get(sym_str).and_then(|c| {
                    if c.len() >= 2 && row.market_price_cents > 0 {
                        let prev = c[c.len() - 2].close_cents;
                        if prev > 0 {
                            return Some(
                                (((row.market_price_cents - prev) as f64 / prev as f64) * 10_000.0)
                                    .round() as i32,
                            );
                        }
                    }
                    None
                })
            };
            let dcf = row.dcf_value_cents;
            let spark: Vec<i64> = screener
                .daily_candles
                .get(sym_str)
                .map(|c| {
                    let n = c.len();
                    c[n.saturating_sub(24)..]
                        .iter()
                        .map(|x| x.close_cents)
                        .collect()
                })
                .unwrap_or_default();
            let ins_net = row.insider_net_shares_90d;
            let ins_buy = row.insider_buy_count;
            let ins_sell = row.insider_sell_count;
            let path_side = match model {
                ScoringModel::ShortV3 => crate::price_path::PathSide::Short,
                _ => crate::price_path::PathSide::Long,
            };
            // Legacy signal tags use "−" / "-" prefix for adverse regime causes.
            let regime_risk = regime_signals.iter().any(|s| {
                s.starts_with('−') || s.starts_with('-')
            });
            let now_epoch = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0);
            let price_path = if row.market_price_cents > 0 {
                let input = crate::price_path::PricePathInput {
                    side: path_side,
                    market_price_cents: row.market_price_cents,
                    intrinsic_value_cents: row.intrinsic_value_cents,
                    dcf_value_cents: dcf,
                    low_fair_value_cents: row.low_fair_value_cents,
                    high_fair_value_cents: row.high_fair_value_cents,
                    gap_bps: row.gap_bps,
                    daily,
                    candles: daily_candles_ref,
                    next_earnings_epoch: row.next_earnings_epoch,
                    now_epoch,
                    regime_risk,
                    forecast_score: fore_score,
                    technical_score: tech_score,
                };
                let est = crate::price_path::estimate_price_path(&input);
                Some(crate::price_path::compact_price_path(&est))
            } else {
                None
            };
            OpportunityRow {
                row,
                fundamentals_score: fund_score,
                technical_score: tech_score,
                forecast_score: fore_score,
                regime_score,
                composite_score: composite,
                composite_score_base: composite_base,
                decision,
                fundamentals_signals: fund_signals,
                technical_signals: tech_signals,
                forecast_signals: fore_signals,
                regime_signals,
                regime_causes,
                regime_unavailable_reason,
                regime_status,
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
                price_path,
            }
        })
        .collect()
}

#[tauri::command]
pub fn get_regime_scoring_enabled(state: State<AppState>) -> bool {
    use std::sync::atomic::Ordering;
    state.apply_regime_scoring.load(Ordering::Relaxed)
}

#[tauri::command]
pub fn set_regime_scoring_enabled(enabled: bool, state: State<AppState>) -> bool {
    use std::sync::atomic::Ordering;
    state.apply_regime_scoring.store(enabled, Ordering::Relaxed);
    // Turning context on must not wait for the banner: warm/refresh regime data now.
    if enabled {
        crate::regime::request_regime_refresh(&state);
    }
    enabled
}

#[tauri::command]
pub fn get_symbol_detail(symbol: String, state: State<AppState>) -> Option<SymbolDetail> {
    let symbol = symbol.trim().to_uppercase();
    {
        let mut screener = state.screener.lock().unwrap();
        // Replace stale FCFF-for-financials caches (e.g. ACGL $875) before serving detail.
        screener.ensure_model_routed_valuation(&symbol);
    }
    // Universe EDGAR worker can take minutes on SP500. Opening detail must not
    // leave the valuation slot stuck on loading→timeout — demand-drive one symbol.
    request_demand_valuation_if_needed(&symbol, &state);
    let mut screener = state.screener.lock().unwrap();
    screener.ensure_model_routed_valuation(&symbol);
    screener.detail(&symbol)
}

/// Deterministic native-E2E setup. This is inert in release builds and unless
/// the dedicated runner opts in; the assertion path itself still uses the real
/// `get_symbol_detail` command and the normal DetailPanel renderer.
#[tauri::command]
pub fn debug_seed_cof_native_e2e(state: State<AppState>) -> Result<SymbolDetail, String> {
    if !cfg!(debug_assertions) || std::env::var("DS_NATIVE_E2E").as_deref() != Ok("1") {
        return Err("native E2E fixture seeding is disabled".into());
    }

    seed_cof_native_e2e(&state)
}

pub(crate) fn seed_cof_native_e2e(state: &AppState) -> Result<SymbolDetail, String> {
    if !cfg!(debug_assertions) || std::env::var("DS_NATIVE_E2E").as_deref() != Ok("1") {
        return Err("native E2E fixture seeding is disabled".into());
    }

    let fixture: serde_json::Value = serde_json::from_str(include_str!(
        "../tests/fixtures/yahoo/quoteSummary/COF-retention.json"
    ))
    .map_err(|error| format!("parse COF native E2E fixture: {error}"))?;
    let fetched = crate::quote_summary::parse_quote_summary(&fixture, "COF");
    let snapshot = fetched
        .snapshot
        .ok_or_else(|| "COF native E2E fixture has no market snapshot".to_string())?;
    let fundamentals = fetched
        .fundamentals
        .ok_or_else(|| "COF native E2E fixture has no fundamentals".to_string())?;

    let mut screener = state.screener.lock().unwrap();
    screener.ingest_snapshot(snapshot);
    screener.ingest_fundamentals(fundamentals);
    screener.ensure_model_routed_valuation("COF");
    screener
        .detail("COF")
        .ok_or_else(|| "COF detail missing after native E2E seed".to_string())
}

/// Kick a background valuation for one equity when detail is open and no DCF yet.
fn request_demand_valuation_if_needed(symbol: &str, state: &AppState) {
    if is_crypto(symbol) || is_etf(symbol) {
        return;
    }
    {
        let mut s = state.screener.lock().unwrap();
        // Need fundamentals before EDGAR compute is useful. Missing shares may
        // be recovered from SEC DEI in the demand path below; do not refuse
        // before trying the provider fallback.
        let Some(fund) = s.fundamentals.get(symbol).cloned() else {
            return;
        };
        // Closed-world refuse: do not start EDGAR / FCFF for unclassifiable names.
        let class = crate::dcf_model::classify_business(
            fund.sector_name.as_deref(),
            fund.industry_name.as_deref(),
            fund.sector_key.as_deref(),
            fund.industry_key.as_deref(),
            false,
        );
        if matches!(
            class,
            crate::dcf_model::BusinessClass::Unclassified
                | crate::dcf_model::BusinessClass::NotEligible
        ) {
            let market_params = crate::dcf_model::MarketParams::default_usd();
            let envelope = crate::operating_valuation_runtime::route_runtime_valuation(
                crate::operating_valuation_runtime::RuntimeValuationInput {
                    business_class: class,
                    fundamentals: &fund,
                    fcff_analysis: None,
                    fcff_failure: Some("business_class_refusal"),
                    forward_evidence: Err(
                        crate::operating_valuation_runtime::ForwardSourceFailure::NotAttempted,
                    ),
                    market_params: &market_params,
                    as_of_epoch_day: current_epoch_day(),
                },
            );
            s.ingest_operating_valuation(symbol.to_string(), None, envelope);
            return;
        }
        if (class == crate::dcf_model::BusinessClass::FinancialServices
            && (s.dcf_analyses.contains_key(symbol) || s.dcf_values.contains_key(symbol)))
            || (class == crate::dcf_model::BusinessClass::OperatingNonFinancial
                && s.has_current_operating_valuation(symbol))
        {
            return;
        }
    }
    {
        let mut inflight = state.valuation_inflight.lock().unwrap();
        if !inflight.insert(symbol.to_string()) {
            return; // already computing
        }
    }

    let worker_symbol = symbol.to_string();
    let screener = Arc::clone(&state.screener);
    let cik_cache = Arc::clone(&state.edgar_cik_map);
    let inflight = Arc::clone(&state.valuation_inflight);
    let feed_log = Arc::clone(&state.feed_log);
    let valuation_yahoo = state.valuation_yahoo.clone();

    let spawn_result = thread::Builder::new()
        .name(format!("edgar-dcf-{worker_symbol}"))
        .spawn(move || {
            let _inflight_guard = ValuationInflightGuard {
                symbol: worker_symbol.clone(),
                inflight,
            };
            let result = catch_unwind(AssertUnwindSafe(|| {
                compute_demand_valuation_once(
                    &worker_symbol,
                    &screener,
                    &cik_cache,
                    &valuation_yahoo,
                )
            }));

            match result {
                Ok(Ok(())) => {}
                Ok(Err(error)) => {
                    feed_log.warn(&format!("demand-valuation {worker_symbol}: {error}"));
                    record_demand_valuation_failure(&worker_symbol, &screener, &error);
                }
                Err(_) => {
                    let error = "valuation worker panicked";
                    feed_log.warn(&format!("demand-valuation {worker_symbol}: {error}"));
                    record_demand_valuation_failure(&worker_symbol, &screener, error);
                }
            }
        });
    if let Err(error) = spawn_result {
        state.valuation_inflight.lock().unwrap().remove(symbol);
        state
            .feed_log
            .warn(&format!("demand-valuation {symbol}: start worker: {error}"));
        record_demand_valuation_failure(
            symbol,
            &state.screener,
            &format!("start valuation worker: {error}"),
        );
    }
}

fn record_demand_valuation_failure(
    symbol: &str,
    screener: &Arc<std::sync::Mutex<crate::engine::ScreenerState>>,
    error: &str,
) {
    let mut state = screener.lock().unwrap();
    let Some(fund) = state.fundamentals.get(symbol).cloned() else {
        state.set_valuation_error(symbol.to_string(), error.to_string());
        return;
    };
    let class = crate::dcf_model::classify_business(
        fund.sector_name.as_deref(),
        fund.industry_name.as_deref(),
        fund.sector_key.as_deref(),
        fund.industry_key.as_deref(),
        false,
    );
    if class != crate::dcf_model::BusinessClass::OperatingNonFinancial {
        state.clear_dcf(symbol);
        state.set_valuation_error(symbol.to_string(), error.to_string());
        return;
    }
    let market_params = crate::dcf_model::MarketParams::default_usd();
    let envelope = crate::operating_valuation_runtime::route_runtime_valuation(
        crate::operating_valuation_runtime::RuntimeValuationInput {
            business_class: class,
            fundamentals: &fund,
            fcff_analysis: None,
            fcff_failure: Some(error),
            forward_evidence: Err(
                crate::operating_valuation_runtime::ForwardSourceFailure::Transport,
            ),
            market_params: &market_params,
            as_of_epoch_day: current_epoch_day(),
        },
    );
    state.clear_dcf(symbol);
    state.ingest_operating_valuation(symbol.to_string(), None, envelope);
}

fn current_epoch_day() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| (duration.as_secs() / 86_400) as i64)
        .unwrap_or(0)
}

fn financial_required_drivers_missing(fund: &crate::engine::FundamentalSnapshot) -> bool {
    fund.shares_outstanding.unwrap_or(0) == 0
        || fund.book_value_per_share_cents.unwrap_or(0) <= 0
        || !matches!(fund.return_on_equity_bps, Some(1..=9_999))
        || !matches!(fund.retention_bps, Some(0..=10_000))
}

/// Compute one demand-driven valuation using the same route as Detail.
///
/// The helper is intentionally synchronous so the bounded QA audit can process
/// at most 20 names sequentially. Financial services still avoid EDGAR FCF;
/// they only use the CIK when SEC DEI is needed to recover missing shares.
fn compute_demand_valuation_once(
    symbol: &str,
    screener: &Arc<std::sync::Mutex<crate::engine::ScreenerState>>,
    cik_cache: &Arc<std::sync::Mutex<Option<HashMap<String, u64>>>>,
    valuation_yahoo: &Option<Arc<YahooClient>>,
) -> Result<(), String> {
    compute_demand_valuation_once_with_financial_refresh(
        symbol,
        screener,
        cik_cache,
        valuation_yahoo,
        |symbol| {
            let Some(yahoo) = valuation_yahoo.as_deref() else {
                return Ok(None);
            };
            yahoo
                .fetch_symbol(symbol)
                .map(|result| result.fundamentals)
                .map_err(|error| format!("financial fundamentals refresh failed: {error}"))
        },
    )
}

fn compute_demand_valuation_once_with_financial_refresh<F>(
    symbol: &str,
    screener: &Arc<std::sync::Mutex<crate::engine::ScreenerState>>,
    cik_cache: &Arc<std::sync::Mutex<Option<HashMap<String, u64>>>>,
    valuation_yahoo: &Option<Arc<YahooClient>>,
    refresh_financial_fundamentals: F,
) -> Result<(), String>
where
    F: FnOnce(&str) -> Result<Option<crate::engine::FundamentalSnapshot>, String>,
{
    let (mut fund, price, class, existing_fcff) = {
        let s = screener.lock().unwrap();
        let fund = s
            .fundamentals
            .get(symbol)
            .cloned()
            .ok_or_else(|| "fundamentals missing".to_string())?;
        let price = s
            .snapshots
            .get(symbol)
            .map(|snapshot| snapshot.market_price_cents);
        let class = crate::dcf_model::classify_business(
            fund.sector_name.as_deref(),
            fund.industry_name.as_deref(),
            fund.sector_key.as_deref(),
            fund.industry_key.as_deref(),
            false,
        );
        let existing_fcff = s.dcf_analyses.get(symbol).cloned().filter(|analysis| {
            analysis.model == crate::dcf_model::ValuationModel::FcffWacc
                && analysis.engine_version == crate::dcf_model::ENGINE_VERSION
                && analysis.model_policy_version == crate::dcf_model::MODEL_POLICY_VERSION
        });
        (fund, price, class, existing_fcff)
    };

    if matches!(
        class,
        crate::dcf_model::BusinessClass::Unclassified
            | crate::dcf_model::BusinessClass::NotEligible
    ) {
        return Err(crate::dcf_model::classification_unavailable_reason(class)
            .unwrap_or("valuation unavailable")
            .into());
    }

    if class == crate::dcf_model::BusinessClass::FinancialServices {
        // A symbol can remain in memory from a quoteSummary fetched before a newly
        // required module/field was available. Detail is the bounded recovery
        // boundary: refresh the full Yahoo fundamentals once before refusing the
        // residual-income model. This avoids requiring an app restart for COF-like
        // missing payout/retention snapshots.
        if financial_required_drivers_missing(&fund) {
            if let Some(refreshed) = refresh_financial_fundamentals(symbol)? {
                let mut state = screener.lock().unwrap();
                state.ingest_fundamentals(refreshed);
                fund = state
                    .fundamentals
                    .get(symbol)
                    .cloned()
                    .ok_or_else(|| "refreshed financial fundamentals missing".to_string())?;
            }
        }

        let cik = if fund.shares_outstanding.unwrap_or(0) == 0 {
            let client = edgar::edgar_client();
            let mut guard = cik_cache.lock().unwrap();
            if guard.is_none() {
                *guard = Some(edgar::fetch_cik_map(&client)?);
            }
            guard
                .as_ref()
                .and_then(|map| map.get(symbol).copied())
                .ok_or_else(|| format!("no CIK for {symbol}"))?
        } else {
            0
        };
        let mut shares_resolved_from_sec = false;
        if fund.shares_outstanding.unwrap_or(0) == 0 {
            let shares = edgar::fetch_shares_outstanding(&edgar::edgar_client(), symbol, cik)?
                .ok_or_else(|| "share count is missing from Yahoo and SEC DEI".to_string())?;
            fund.shares_outstanding = Some(shares);
            shares_resolved_from_sec = true;
            screener.lock().unwrap().ingest_fundamentals(fund.clone());
        }
        if !matches!(fund.retention_bps, Some(0..=10_000)) {
            return Err("retention/payout is missing or invalid after Yahoo refresh".into());
        }
        let mut analysis =
            crate::dcf_model::compute_from_fundamentals(&fund, price, "fundamentals")?;
        if shares_resolved_from_sec {
            analysis.reason_codes.push("shares=sec_dei_fallback".into());
        }
        screener
            .lock()
            .unwrap()
            .ingest_dcf_analysis(symbol.to_string(), analysis);
        return Ok(());
    }

    let as_of_epoch_day = current_epoch_day();
    let forward_evidence = valuation_yahoo.as_deref().map_or_else(
        || Err(crate::operating_valuation_runtime::ForwardSourceFailure::Transport),
        |valuation_yahoo| {
            valuation_yahoo
                .fetch_forward_forecast(symbol, as_of_epoch_day)
                .map_err(|error| match error {
                    ForwardForecastFetchError::Provider(reason) => {
                        crate::operating_valuation_runtime::ForwardSourceFailure::Provider(reason)
                    }
                    ForwardForecastFetchError::Transport(error)
                        if crate::yahoo_session::is_rate_limit_error(&error) =>
                    {
                        crate::operating_valuation_runtime::ForwardSourceFailure::RateLimited
                    }
                    ForwardForecastFetchError::Transport(_) => {
                        crate::operating_valuation_runtime::ForwardSourceFailure::Transport
                    }
                })
        },
    );

    let mut fcff_failure = None;
    let mut analysis = existing_fcff;
    // A current FCFF candidate plus a resolved share count needs only the
    // demand-only Yahoo forecast. Avoid a redundant SEC round trip on Detail.
    let cik_result = (analysis.is_none() || fund.shares_outstanding.unwrap_or(0) == 0).then(|| {
        (|| -> Result<u64, String> {
            let client = edgar::edgar_client();
            let mut guard = cik_cache.lock().unwrap();
            if guard.is_none() {
                *guard = Some(edgar::fetch_cik_map(&client)?);
            }
            guard
                .as_ref()
                .and_then(|map| map.get(symbol).copied())
                .ok_or_else(|| format!("no CIK for {symbol}"))
        })()
    });
    match cik_result {
        Some(Ok(cik)) => {
            let mut shares_resolved_from_sec = false;
            if fund.shares_outstanding.unwrap_or(0) == 0 {
                match edgar::fetch_shares_outstanding(&edgar::edgar_client(), symbol, cik) {
                    Ok(Some(shares)) => {
                        fund.shares_outstanding = Some(shares);
                        shares_resolved_from_sec = true;
                        screener.lock().unwrap().ingest_fundamentals(fund.clone());
                    }
                    Ok(None) => fcff_failure = Some("missing_shares:yahoo_and_sec_dei".to_string()),
                    Err(error) => fcff_failure = Some(format!("sec_shares:{error}")),
                }
            }
            if analysis.is_none() && fcff_failure.is_none() {
                match edgar::fetch_fcf_history(&edgar::edgar_client(), symbol, cik) {
                    Ok(Some(fcf)) => {
                        match crate::dcf_model::compute(&fund, &fcf, None, "sec_edgar") {
                            Ok(mut computed) => {
                                if shares_resolved_from_sec {
                                    computed.reason_codes.push("shares=sec_dei_fallback".into());
                                    computed
                                        .diagnostics
                                        .driver_provenance
                                        .push("shares=sec_dei_fallback".into());
                                }
                                analysis = Some(computed);
                            }
                            Err(error) => fcff_failure = Some(format!("fcff_compute:{error}")),
                        }
                    }
                    Ok(None) => fcff_failure = Some("missing_sec_fcff_history".into()),
                    Err(error) => fcff_failure = Some(format!("sec_fcff:{error}")),
                }
            }
        }
        Some(Err(error)) => fcff_failure = Some(format!("sec_cik:{error}")),
        None => {}
    }

    let market_params = crate::dcf_model::MarketParams::default_usd();
    let envelope = crate::operating_valuation_runtime::route_runtime_valuation(
        crate::operating_valuation_runtime::RuntimeValuationInput {
            business_class: class,
            fundamentals: &fund,
            fcff_analysis: analysis.as_ref(),
            fcff_failure: fcff_failure.as_deref(),
            forward_evidence,
            market_params: &market_params,
            as_of_epoch_day,
        },
    );
    let final_fundamentals_fingerprint =
        crate::operating_valuation_runtime::fundamentals_fingerprint(&fund);
    let mut state = screener.lock().unwrap();
    let inputs_are_current = state.fundamentals.get(symbol).is_some_and(|current| {
        crate::operating_valuation_runtime::fundamentals_fingerprint(current)
            == final_fundamentals_fingerprint
    });
    if inputs_are_current {
        state.ingest_operating_valuation(symbol.to_string(), analysis, envelope);
    }
    Ok(())
}

fn analyst_price_history(state: &AppState, symbol: &str) -> Vec<ForecastPricePoint> {
    state
        .screener
        .lock()
        .unwrap()
        .daily_candles
        .get(symbol)
        .map(|candles| {
            candles
                .iter()
                .map(|candle| ForecastPricePoint {
                    epoch_seconds: candle.epoch_seconds,
                    close_cents: candle.close_cents,
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Cache-only detail read. Never spends TipRanks quota.
#[tauri::command]
pub async fn get_analyst_forecasts(
    symbol: String,
    state: State<'_, AppState>,
) -> Result<AnalystForecastPanel, String> {
    let symbol = symbol.trim().to_uppercase();
    let eligible = !is_crypto(&symbol) && !is_etf(&symbol);
    let price_history = analyst_price_history(&state, &symbol);
    let service = Arc::clone(&state.analyst_forecasts);
    tauri::async_runtime::spawn_blocking(move || {
        if eligible {
            service.get(&symbol, price_history)
        } else {
            service.not_eligible(&symbol, price_history)
        }
    })
    .await
    .map_err(|error| format!("join TipRanks forecast request: {error}"))
}

/// Explicit user load/refresh action. May spend one counted TipRanks call.
#[tauri::command]
pub async fn load_analyst_forecasts(
    symbol: String,
    state: State<'_, AppState>,
) -> Result<AnalystForecastPanel, String> {
    let symbol = symbol.trim().to_uppercase();
    let eligible = !is_crypto(&symbol) && !is_etf(&symbol);
    let price_history = analyst_price_history(&state, &symbol);
    let service = Arc::clone(&state.analyst_forecasts);
    tauri::async_runtime::spawn_blocking(move || {
        if eligible {
            service.load(&symbol, price_history)
        } else {
            service.not_eligible(&symbol, price_history)
        }
    })
    .await
    .map_err(|error| format!("join TipRanks forecast load: {error}"))
}

#[tauri::command]
pub fn tipranks_settings_status(state: State<AppState>) -> Result<TipRanksSettingsStatus, String> {
    state.analyst_forecasts.settings_status()
}

#[tauri::command]
pub fn tipranks_save_key(
    api_key: String,
    state: State<AppState>,
) -> Result<TipRanksSettingsStatus, String> {
    state.analyst_forecasts.save_key(&api_key)?;
    state.analyst_forecasts.settings_status()
}

#[tauri::command]
pub fn tipranks_delete_key(state: State<AppState>) -> Result<TipRanksSettingsStatus, String> {
    state.analyst_forecasts.delete_key()?;
    state.analyst_forecasts.settings_status()
}

#[tauri::command]
pub async fn tipranks_test_key(state: State<'_, AppState>) -> Result<AnalystForecastPanel, String> {
    let service = Arc::clone(&state.analyst_forecasts);
    tauri::async_runtime::spawn_blocking(move || service.test_connection("AAPL"))
        .await
        .map_err(|error| format!("join TipRanks credential test: {error}"))
}

#[tauri::command]
pub fn get_alerts(state: State<AppState>) -> Vec<AlertEvent> {
    let screener = state.screener.lock().unwrap();
    screener.alerts.iter().rev().take(50).cloned().collect()
}

#[tauri::command]
pub fn get_feed_status(state: State<AppState>) -> FeedStatusResponse {
    let status = state.feed_status.lock().unwrap();
    let symbols_total = state.active_symbols.lock().unwrap().len();
    FeedStatusResponse {
        running: status.running,
        symbols_loaded: status.symbols_loaded,
        symbols_total,
        last_error: status.last_error.clone(),
        profile_name: status.profile_name.clone(),
        profile_locked: state.is_profile_locked(),
        stale_snapshots: status.stale_snapshots,
    }
}

#[tauri::command]
pub fn list_universe_profiles() -> Vec<UniverseProfileInfo> {
    profile_definitions()
        .iter()
        .map(|def| {
            let symbol_count = match def.name {
                "sp500" => compose_universe("sp500")
                    .map(|(_, u)| u.len())
                    .unwrap_or_else(|_| profile_symbols(def.name).map(|s| s.len()).unwrap_or(0)),
                // Dynamic sample — report hard cap for UI.
                "qa" => QA_MAX_SYMBOLS,
                _ => profile_symbols(def.name).map(|s| s.len()).unwrap_or(0),
            };
            UniverseProfileInfo {
                name: def.name.to_string(),
                description: def.description.to_string(),
                symbol_count,
            }
        })
        .collect()
}

#[tauri::command]
pub fn get_universe_profile(state: State<AppState>) -> UniverseProfileStatus {
    universe_profile_status(&state)
}

#[tauri::command]
pub fn set_universe_profile(
    name: String,
    state: State<AppState>,
) -> Result<UniverseProfileStatus, String> {
    apply_universe_profile(&name, &state)?;
    Ok(universe_profile_status(&state))
}

fn universe_profile_status(state: &AppState) -> UniverseProfileStatus {
    let name = state.active_profile.lock().unwrap().clone();
    let symbols_total = state.active_symbols.lock().unwrap().len();
    let status = state.feed_status.lock().unwrap();
    UniverseProfileStatus {
        name,
        symbols_total,
        symbols_loaded: status.symbols_loaded,
        profile_locked: state.is_profile_locked(),
        stale_snapshots: status.stale_snapshots,
    }
}

/// Validate, clear screener, install new universe, bump generation, and start feed workers.
///
/// Idempotent by **canonical symbol set** when profile name matches: same membership
/// set does not restart workers (order changes alone are ignored).
fn apply_universe_profile(raw_name: &str, state: &AppState) -> Result<(), String> {
    let requested = resolve_profile_name(raw_name)
        .ok_or_else(|| format!("unknown universe profile: {raw_name}"))?
        .to_string();

    // Lock check before any worker/state mutation.
    if state.is_profile_locked() {
        let current = state.active_profile.lock().unwrap().clone();
        if requested != current {
            return Err(format!(
                "universe profile locked to {current} (launch --profile / DS_UNIVERSE_PROFILE)"
            ));
        }
    }

    let resolved = resolve_profile_membership(&requested, &state.db)?;
    if resolved.name == "qa" && resolved.symbols.len() > QA_MAX_SYMBOLS {
        return Err(format!(
            "qa membership exceeded hard cap: {} > {QA_MAX_SYMBOLS}",
            resolved.symbols.len()
        ));
    }

    let new_set: std::collections::HashSet<String> = resolved.symbol_set();
    {
        let current_name = state.active_profile.lock().unwrap().clone();
        let current_symbols = state.active_symbols.lock().unwrap();
        let current_set: std::collections::HashSet<String> =
            current_symbols.iter().cloned().collect();
        let feed_running = state.feed_status.lock().unwrap().running;
        if feed_running && current_name == resolved.name && current_set == new_set {
            // Same membership set — do not thrash workers.
            return Ok(());
        }
    }

    let profile_name = resolved.name.clone();
    let symbols = Arc::new(resolved.symbols);
    // Fail-closed gate immediately before spawning workers.
    if profile_name == "qa" && symbols.len() > QA_MAX_SYMBOLS {
        return Err(format!(
            "qa refuse spawn: {} symbols > {QA_MAX_SYMBOLS}",
            symbols.len()
        ));
    }

    // Invalidate any in-flight workers from the previous universe.
    let generation = state
        .feed_generation
        .fetch_add(1, std::sync::atomic::Ordering::SeqCst)
        + 1;
    reset_initial_pass_completion(&state.initial_pass_completed_generation);

    {
        let mut screener = state.screener.lock().unwrap();
        screener.clear_universe();
    }
    *state.active_profile.lock().unwrap() = profile_name.clone();
    *state.active_symbols.lock().unwrap() = Arc::clone(&symbols);

    {
        let mut status = state.feed_status.lock().unwrap();
        status.running = true;
        status.symbols_loaded = 0;
        status.profile_name = profile_name.clone();
        status.stale_snapshots = resolved.stale_snapshots;
        let mut err_parts = Vec::new();
        if let Some(e) = resolved.db_error {
            err_parts.push(format!("qa db fallback: {e}"));
        }
        if resolved.stale_snapshots {
            err_parts.push("qa: stale_snapshots (reporting only; membership not excluded)".into());
        }
        status.last_error = if err_parts.is_empty() {
            None
        } else {
            Some(err_parts.join("; "))
        };
    }

    state.feed_log.info(&format!(
        "universe apply profile={profile_name} symbols={} ranked={} fill={} source={:?} locked={}",
        symbols.len(),
        resolved.ranked_count,
        resolved.fill_count,
        resolved.source,
        state.is_profile_locked()
    ));

    spawn_feed_workers(state, symbols, generation)
}

fn ingest_fetch_result(
    screener: &mut crate::engine::ScreenerState,
    result: crate::fetcher::FetchResult,
    crypto: bool,
    etf: bool,
) -> RefreshOutcome {
    let visible = is_list_ready(&result, crypto, etf);
    let enriched = is_enrichment_complete(&result, crypto, etf);
    let has_fundamentals = result.fundamentals.is_some();

    if visible {
        if let Some(snap) = result.snapshot {
            if has_fundamentals {
                screener.ingest_snapshot_preserving_known(snap);
            } else {
                screener.ingest_partial_snapshot(snap);
            }
        }
        if let Some(sig) = result.signal {
            screener.ingest_signal(sig);
        }
        if let Some(fund) = result.fundamentals {
            screener.ingest_fundamentals(fund);
        }
    }

    RefreshOutcome { visible, enriched }
}

#[tauri::command]
pub fn refresh_symbol(symbol: String, state: State<AppState>) -> Result<String, String> {
    let client = YahooClient::new().map_err(|e| e.to_string())?;
    let result = client.fetch_symbol(&symbol).map_err(|e| e.to_string())?;

    let mut screener = state.screener.lock().unwrap();
    let _ = ingest_fetch_result(&mut screener, result, is_crypto(&symbol), is_etf(&symbol));
    Ok(symbol)
}

#[tauri::command]
pub fn get_scoring_model(state: State<AppState>) -> String {
    state.screener.lock().unwrap().scoring_model.clone()
}

#[tauri::command]
pub fn set_scoring_model(model: String, state: State<AppState>) -> Result<String, String> {
    let normalized = ScoringModel::parse(&model).as_str().to_string();
    state.screener.lock().unwrap().scoring_model = normalized.clone();
    Ok(normalized)
}

#[tauri::command]
pub fn get_index_estimates(state: State<AppState>) -> crate::index_estimates::IndexEstimatesReport {
    let profile_name = state.active_profile.lock().unwrap().clone();
    let screener = state.screener.lock().unwrap();
    let rows = screener.candidate_rows();
    let selected_dcf = rows
        .iter()
        .filter_map(|row| {
            screener
                .selected_dcf_analysis(&row.symbol)
                .cloned()
                .map(|analysis| (row.symbol.clone(), analysis))
        })
        .collect();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    crate::index_estimates::compute(&rows, &selected_dcf, &profile_name, now)
}

#[tauri::command]
pub fn get_quant_lens(
    symbol: String,
    state: State<AppState>,
) -> Result<crate::quant_lens::QuantLensReport, String> {
    request_demand_valuation_if_needed(&symbol, &state);
    let mut screener = state.screener.lock().unwrap();
    screener.ensure_model_routed_valuation(&symbol);

    let detail = screener
        .detail(&symbol)
        .ok_or_else(|| format!("no detail for {symbol}"))?;
    let candles = screener.daily_candles.get(&symbol).map(|c| c.as_slice());
    let dcf = screener.dcf_analyses.get(&symbol);
    let rows = screener.candidate_rows();
    let opp = rows.iter().find(|r| r.symbol == symbol);
    let peers: Vec<(String, Vec<crate::engine::HistoricalCandle>)> = screener
        .daily_candles
        .iter()
        .filter(|(s, _)| *s != &symbol)
        .take(40)
        .map(|(s, c)| (s.clone(), c.clone()))
        .collect();
    let report = crate::quant_lens::analyze(&detail, candles, dcf, opp, &peers);
    // Release screener before SQLite dossier read; FEM is diagnostic-only and
    // must never write dcf/selected/intrinsic maps or change primary_status.
    drop(screener);
    let extras = match crate::valuation_dossier_view::load_valuation_dossier(&state.db, &symbol) {
        Ok(dossier) => {
            crate::valuation_dossier_view::analyst_method_quant_section(&dossier.analyst_method)
                .into_iter()
                .collect::<Vec<_>>()
        }
        Err(_) => {
            let dossier = crate::valuation_dossier_view::publication_read_failure_dossier(&symbol);
            crate::valuation_dossier_view::analyst_method_quant_section(&dossier.analyst_method)
                .into_iter()
                .collect::<Vec<_>>()
        }
    };
    Ok(crate::quant_lens::attach_diagnostic_sections(
        report, extras,
    ))
}

/// Cache-only ValuationDossierView for the additive market-reference lane (1C).
/// Never triggers providers, FCFF, or ranking mutation.
#[tauri::command]
pub fn get_valuation_dossier(
    symbol: String,
    state: State<AppState>,
) -> Result<crate::valuation_dossier_view::ValuationDossierView, String> {
    Ok(
        crate::valuation_dossier_view::load_valuation_dossier(&state.db, &symbol).unwrap_or_else(
            |_| crate::valuation_dossier_view::publication_read_failure_dossier(&symbol),
        ),
    )
}

/// Seed AMZN-shaped identity + fixture analyst-method import for native 1C E2E.
/// Inert unless debug + DS_NATIVE_E2E=1.
#[tauri::command]
pub fn debug_seed_amzn_analyst_method_e2e(
    state: State<AppState>,
) -> Result<crate::valuation_dossier_view::ValuationDossierView, String> {
    if !cfg!(debug_assertions) || std::env::var("DS_NATIVE_E2E").as_deref() != Ok("1") {
        return Err("native E2E fixture seeding is disabled".into());
    }
    seed_amzn_analyst_method_e2e(&state)
}

pub(crate) fn seed_amzn_analyst_method_e2e(
    state: &AppState,
) -> Result<crate::valuation_dossier_view::ValuationDossierView, String> {
    if !cfg!(debug_assertions) || std::env::var("DS_NATIVE_E2E").as_deref() != Ok("1") {
        return Err("native E2E fixture seeding is disabled".into());
    }
    let identity = crate::issuer_identity::fixture_amzn_shaped();
    state.db.upsert_identity_bundle(
        &identity.issuer.issuer_id,
        &identity.issuer.cik,
        identity.issuer.legal_name.as_deref(),
        &identity.security.security_id,
        &identity.security.currency,
        identity.security.share_class_label.as_deref(),
        &identity.ticker_alias.ticker,
        &identity.ticker_alias.effective_from,
        &identity.ticker_alias.identity_vintage,
        &identity.share_basis.basis_id,
        &identity.share_basis.vintage_fingerprint,
        &identity.share_basis.description,
    )?;
    let import_path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../shared/contracts/valuation-forward-earnings-import-v1.json");
    let raw =
        std::fs::read_to_string(&import_path).map_err(|e| format!("read import fixture: {e}"))?;
    let contract: serde_json::Value =
        serde_json::from_str(&raw).map_err(|e| format!("parse import fixture: {e}"))?;
    let import_json = contract["fixtures"]["available"][0]["import"].to_string();
    let decision_at = contract["fixtures"]["available"][0]["admissionContext"]["decisionAtUnixMs"]
        .as_i64()
        .unwrap_or(1_753_920_000_000);
    crate::analyst_method_service::commit_analyst_method_import(
        &state.db,
        &import_json,
        &identity,
        decision_at,
    )?;
    crate::valuation_dossier_view::load_valuation_dossier(&state.db, "AMZN")
}

/// Run the bounded DCF-vs-analyst audit used to investigate model outliers.
///
/// This command is intentionally fail-closed: it only runs for a launch-locked
/// `qa` profile and never changes universe membership.  Missing DCFs are
/// computed sequentially through the same Detail route, so the audit cannot
/// create a Yahoo burst or silently compare stale/partial models.
#[tauri::command]
pub async fn run_qa_valuation_divergence_audit(
    state: State<'_, AppState>,
) -> Result<crate::valuation_divergence::ValuationDivergenceAudit, String> {
    let profile = state.active_profile.lock().unwrap().clone();
    if profile != "qa" || !state.is_profile_locked() {
        return Err("valuation divergence audit requires launch-locked profile qa".into());
    }
    let symbols = state.active_symbols.lock().unwrap().as_ref().clone();
    if symbols.len() > crate::valuation_divergence::AUDIT_MAX_SYMBOLS {
        return Err(format!(
            "valuation divergence audit refused: {} active symbols > {}",
            symbols.len(),
            crate::valuation_divergence::AUDIT_MAX_SYMBOLS
        ));
    }

    let screener = Arc::clone(&state.screener);
    let cik_cache = Arc::clone(&state.edgar_cik_map);
    let feed_log = Arc::clone(&state.feed_log);
    let valuation_yahoo = state.valuation_yahoo.clone();
    tauri::async_runtime::spawn_blocking(move || {
        for symbol in &symbols {
            let needs_valuation = {
                let mut s = screener.lock().unwrap();
                s.ensure_model_routed_valuation(symbol);
                !s.has_current_operating_valuation(symbol)
                    && !s.dcf_analyses.get(symbol).is_some_and(|analysis| {
                        analysis.business_class
                            == crate::dcf_model::BusinessClass::FinancialServices
                    })
            };
            if needs_valuation {
                if let Err(error) =
                    compute_demand_valuation_once(symbol, &screener, &cik_cache, &valuation_yahoo)
                {
                    feed_log.warn(&format!("qa-divergence-audit {symbol}: {error}"));
                    screener
                        .lock()
                        .unwrap()
                        .set_valuation_error(symbol.clone(), error);
                }
            }
        }

        let candidates = {
            let mut s = screener.lock().unwrap();
            symbols
                .iter()
                .map(|symbol| {
                    s.ensure_model_routed_valuation(symbol);
                    let detail = s.detail(symbol);
                    crate::valuation_divergence::AuditCandidate {
                        symbol: symbol.clone(),
                        analyst_value_cents: detail
                            .as_ref()
                            .map(|value| value.intrinsic_value_cents)
                            .filter(|value| *value > 0),
                        analyst_low_cents: detail
                            .as_ref()
                            .and_then(|value| value.low_fair_value_cents),
                        analyst_high_cents: detail
                            .as_ref()
                            .and_then(|value| value.high_fair_value_cents),
                        analyst_opinion_count: detail
                            .as_ref()
                            .and_then(|value| value.analyst_opinion_count),
                        dcf: detail.as_ref().and_then(|value| value.dcf_analysis.clone()),
                        unavailable_reason: detail
                            .as_ref()
                            .and_then(|value| value.valuation_unavailable_reason.clone()),
                    }
                })
                .collect::<Vec<_>>()
        };
        let computed_at = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|duration| duration.as_secs() as i64)
            .unwrap_or(0);
        Ok(crate::valuation_divergence::build_audit(
            "qa",
            candidates,
            computed_at,
        ))
    })
    .await
    .map_err(|error| format!("join QA valuation divergence audit: {error}"))?
}

/// Local + optional remote Yahoo search (Android ticker/company search parity).
#[tauri::command]
pub fn search_tickers(
    query: String,
    limit: Option<usize>,
    state: State<AppState>,
) -> Vec<TickerSearchResult> {
    let limit = limit.unwrap_or(8).max(1);
    let trimmed = query.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }

    let mut company_names = HashMap::new();
    {
        let screener = state.screener.lock().unwrap();
        for (sym, snap) in &screener.snapshots {
            if let Some(name) = &snap.company_name {
                company_names.insert(sym.to_uppercase(), name.clone());
            }
        }
    }

    let active = state.active_symbols.lock().unwrap().clone();
    let profile_name = state.active_profile.lock().unwrap().clone();
    let universe: Vec<&str> = active.iter().map(|s| s.as_str()).collect();

    let local = local_universe_candidates(trimmed, &universe, &company_names, &profile_name);
    let mut ranked = merge_and_rank(&local, limit);

    if should_trigger_remote_search(trimmed, &ranked) {
        let remote_quotes = fetch_remote_search_quotes(trimmed, limit, &state);
        let mut combined = local;
        combined.extend(remote_candidates(trimmed, &remote_quotes));
        ranked = merge_and_rank(&combined, limit);
    }

    ranked
}

/// Resolve Yahoo search with query variants (spaced brand names often 404 empty).
fn fetch_remote_search_quotes(
    query: &str,
    limit: usize,
    state: &State<AppState>,
) -> Vec<YahooSearchQuote> {
    let cache_key = normalize_search_query_key(query);
    if let Some(q) = state.remote_search_cache.lock().unwrap().get(&cache_key) {
        return q;
    }

    let client = match YahooClient::new() {
        Ok(c) => c,
        Err(_) => return Vec::new(),
    };

    let mut fetched: Vec<YahooSearchQuote> = Vec::new();
    for variant in remote_search_query_variants(query) {
        let variant_key = normalize_search_query_key(&variant);
        if let Some(cached) = state.remote_search_cache.lock().unwrap().get(&variant_key) {
            if !cached.is_empty() {
                fetched = cached;
                break;
            }
            continue;
        }
        match client.search_symbols(&variant, limit) {
            Ok(quotes) if !quotes.is_empty() => {
                state
                    .remote_search_cache
                    .lock()
                    .unwrap()
                    .put(variant_key, quotes.clone());
                fetched = quotes;
                break;
            }
            Ok(empty) => {
                state
                    .remote_search_cache
                    .lock()
                    .unwrap()
                    .put(variant_key, empty);
            }
            Err(_) => {}
        }
    }

    state
        .remote_search_cache
        .lock()
        .unwrap()
        .put(cache_key, fetched.clone());
    fetched
}

#[tauri::command]
pub fn resolve_ticker_search_submit(
    query: String,
    suggestions: Vec<TickerSearchResult>,
) -> SearchSubmitOutcome {
    resolve_search_submit(&query, &suggestions)
}

/// One-shot load for ad-hoc detail. Fast path (quote + daily) returns ASAP;
/// multi-TF charts continue on a background thread so the detail panel is usable
/// within ~1 request instead of waiting on 4 candle ranges.
#[tauri::command]
pub fn ensure_symbol_loaded(symbol: String, state: State<AppState>) -> Result<String, String> {
    ensure_symbol_loaded_inner(symbol, &state)
}

/// One-shot load into screener cache. Must **not** grow `active_symbols` or spawn
/// persistent feed workers (QA hard-cap contract).
pub(crate) fn ensure_symbol_loaded_inner(
    symbol: String,
    state: &AppState,
) -> Result<String, String> {
    let symbol = symbol.trim().to_uppercase();
    if symbol.is_empty() {
        return Err("empty symbol".into());
    }

    let client = YahooClient::new().map_err(|e| e.to_string())?;
    let _ = client.warm_session();

    match client.fetch_symbol(&symbol) {
        Ok(result) => {
            let mut screener = state.screener.lock().unwrap();
            let _ = ingest_fetch_result(&mut screener, result, is_crypto(&symbol), is_etf(&symbol));
        }
        Err(_) => {
            // Quote may fail; candles below can still recover a price path.
        }
    }

    if let Ok(candles) = client.fetch_candles(&symbol, "1y", "1d") {
        if let Some(summary) = compute_chart_summary(&candles) {
            let mut s = state.screener.lock().unwrap();
            s.ingest_chart_summary(symbol.clone(), summary);
            s.ingest_daily_candles(symbol.clone(), candles);
        }
    }

    // Deep multi-TF in background — detail UI already has price + daily chart.
    let screener = Arc::clone(&state.screener);
    let fng_cache = Arc::clone(&state.fng_cache);
    let deep_symbol = symbol.clone();
    let _ = thread::Builder::new()
        .name(format!("ensure-deep-{}", deep_symbol))
        .spawn(move || {
            let client = match YahooClient::new() {
                Ok(c) => c,
                Err(_) => return,
            };
            if let Ok(candles) = client.fetch_candles(&deep_symbol, "5y", "1wk") {
                if let Some(summary) = compute_chart_summary(&candles) {
                    let mut s = screener.lock().unwrap();
                    s.ingest_weekly_summary(deep_symbol.clone(), summary);
                    if is_crypto(&deep_symbol) {
                        s.ingest_weekly_candles(deep_symbol.clone(), candles.clone());
                        drop(s);
                        let fng = fng_cache.get_cached().or_else(|| {
                            let http = crate::crypto_cycle::crypto_client();
                            let v = crate::crypto_cycle::fetch_fear_greed(&http).ok();
                            if let Some(ref fng) = v {
                                fng_cache.put(fng.clone());
                            }
                            v
                        });
                        let now_e = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .map(|d| d.as_secs() as i64)
                            .unwrap_or(0);
                        let metrics = crate::crypto_cycle::compute_crypto_score(
                            &deep_symbol,
                            &candles,
                            Some(0),
                            fng,
                            now_e,
                        );
                        screener
                            .lock()
                            .unwrap()
                            .ingest_crypto_metrics(deep_symbol.clone(), metrics);
                    }
                }
            }
            if let Ok(candles) = client.fetch_candles(&deep_symbol, "1mo", "1h") {
                if let Some(summary) = compute_chart_summary(&candles) {
                    screener
                        .lock()
                        .unwrap()
                        .ingest_hourly_summary(deep_symbol.clone(), summary);
                }
            }
            if let Ok(candles) = client.fetch_candles(&deep_symbol, "10y", "1mo") {
                if let Some(summary) = compute_chart_summary(&candles) {
                    screener
                        .lock()
                        .unwrap()
                        .ingest_monthly_summary(deep_symbol.clone(), summary);
                }
            }
        });

    Ok(symbol)
}

#[tauri::command]
pub fn get_candles(
    symbol: String,
    range: String,
    _state: State<AppState>,
) -> Result<Vec<HistoricalCandle>, String> {
    let client = YahooClient::new().map_err(|e| e.to_string())?;
    let (range_str, interval_str) = match range.as_str() {
        "1d" => ("1d", "5m"),
        "5d" => ("5d", "15m"),
        "1mo" => ("1mo", "1d"),
        "3mo" => ("3mo", "1d"),
        "6mo" => ("6mo", "1wk"),
        "1y" => ("1y", "1wk"),
        "2y" => ("2y", "1wk"),
        "5y" => ("5y", "1mo"),
        _ => ("3mo", "1d"),
    };
    client
        .fetch_candles(&symbol, range_str, interval_str)
        .map_err(|e| e.to_string())
}

// Android DefaultDashboardRepository constants — concurrency kept modest so
// quoteSummary/crumb is not thrashed (429 leaves rows without target/gap/sector).
const REFRESH_CONCURRENCY: usize = 2;
const ENRICHMENT_CONCURRENCY: usize = 2;
const MAX_RETRY_ROUNDS: usize = 6;
const FULL_REFRESH_INTERVAL_SECS: u64 = 15 * 60;

fn generation_is_current(state_gen: &std::sync::atomic::AtomicU64, gen: u64) -> bool {
    state_gen.load(std::sync::atomic::Ordering::SeqCst) == gen
}

fn reset_initial_pass_completion(completed_generation: &std::sync::atomic::AtomicU64) {
    completed_generation.store(u64::MAX, std::sync::atomic::Ordering::SeqCst);
}

fn mark_initial_pass_complete_if_current(
    active_generation: &std::sync::atomic::AtomicU64,
    completed_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
) -> bool {
    if !generation_is_current(active_generation, generation) {
        return false;
    }
    completed_generation.store(generation, std::sync::atomic::Ordering::SeqCst);
    generation_is_current(active_generation, generation)
}

fn warmable_completed_generation(
    active_generation: &std::sync::atomic::AtomicU64,
    completed_generation: &std::sync::atomic::AtomicU64,
) -> Option<u64> {
    let active = active_generation.load(std::sync::atomic::Ordering::SeqCst);
    let completed = completed_generation.load(std::sync::atomic::Ordering::SeqCst);
    (active == completed && completed != u64::MAX).then_some(active)
}

fn pending_as_refs(pending: &[String]) -> Vec<&str> {
    pending.iter().map(|s| s.as_str()).collect()
}

fn retry_backoff_ms(round: usize) -> u64 {
    match round {
        0 => 2_000,
        1 => 5_000,
        2 => 12_000,
        3 => 30_000,
        _ => 60_000,
    }
}

fn batch_retry_delay_ms(rate_limit_secs: u64, completed_round: usize) -> u64 {
    if rate_limit_secs > 0 {
        rate_limit_secs.saturating_add(1).saturating_mul(1_000)
    } else {
        retry_backoff_ms(completed_round)
    }
}

/// Short status-bar summary. Kept ≤60 chars — `StatusBar` truncates `last_error`
/// at that length. Full pending sets still go to the diagnostics log on disk.
fn format_incomplete_retry_status(round: usize, max_rounds: usize, pending: &[String]) -> String {
    let tail = format_pending_tail(pending);
    format!("Quotes retry {round}/{max_rounds}: {tail}")
}

fn format_terminal_incomplete_status(pending: &[String]) -> String {
    format!("Quotes incomplete: {}", format_pending_tail(pending))
}

/// Compact pending-ticker summary that fits the status bar.
fn format_pending_tail(pending: &[String]) -> String {
    match pending.len() {
        0 => "0 pending".into(),
        1 => pending[0].clone(),
        2 => format!("{}, {}", pending[0], pending[1]),
        3 => format!("{}, {}, {}", pending[0], pending[1], pending[2]),
        n => format!("{}, {} +{}", pending[0], pending[1], n - 2),
    }
}

/// True when screener already has list-column enrichment for `sym` (price for
/// crypto/ETF; fundamentals payload for stocks). Used so a rate-limited
/// price-only re-fetch does not re-queue symbols that already completed earlier.
fn symbol_state_enrichment_complete(state: &crate::engine::ScreenerState, sym: &str) -> bool {
    let has_price = state
        .snapshots
        .get(sym)
        .is_some_and(|s| s.market_price_cents > 0);
    if !has_price {
        return false;
    }
    if is_crypto(sym) || is_etf(sym) {
        return true;
    }
    state.fundamentals.contains_key(sym)
}

fn needs_enrichment_retry(
    outcome: RefreshOutcome,
    state: &crate::engine::ScreenerState,
    sym: &str,
) -> bool {
    if outcome.enriched {
        return false;
    }
    !symbol_state_enrichment_complete(state, sym)
}

#[cfg(test)]
mod feed_coordinator_tests {
    use super::{
        batch_retry_delay_ms, compute_demand_valuation_once_with_financial_refresh,
        financial_required_drivers_missing, format_incomplete_retry_status,
        format_terminal_incomplete_status, ingest_fetch_result,
        mark_initial_pass_complete_if_current, needs_enrichment_retry,
        reset_initial_pass_completion, resolve_regime_score_status,
        symbol_state_enrichment_complete, warmable_completed_generation, RefreshOutcome,
        RegimeScoreStatus,
    };
    use crate::engine::{FundamentalSnapshot, MarketSnapshot, ScreenerState};
    use crate::fetcher::FetchResult;
    use crate::opportunity_v3::ScoringModel;

    #[test]
    fn financial_detail_refreshes_when_retention_is_missing() {
        let mut cof = FundamentalSnapshot {
            symbol: "COF".into(),
            shares_outstanding: Some(480_000_000),
            book_value_per_share_cents: Some(15_000),
            return_on_equity_bps: Some(903),
            retention_bps: None,
            ..Default::default()
        };
        assert!(financial_required_drivers_missing(&cof));

        cof.retention_bps = Some(8_347);
        assert!(!financial_required_drivers_missing(&cof));
    }

    #[test]
    fn cof_stale_detail_demand_refresh_replaces_unavailable_with_residual_income() {
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../tests/fixtures/yahoo/quoteSummary/COF-retention.json"
        ))
        .expect("COF fixture JSON");
        let fetched = crate::quote_summary::parse_quote_summary(&fixture, "COF");
        let snapshot = fetched.snapshot.expect("COF snapshot");
        let fresh_fundamentals = fetched.fundamentals.expect("COF fundamentals");

        let mut stale_fundamentals = fresh_fundamentals.clone();
        stale_fundamentals.retention_bps = None;
        let mut state = ScreenerState::new();
        state.ingest_snapshot(snapshot);
        state.ingest_fundamentals(stale_fundamentals);
        state.set_valuation_error(
            "COF".into(),
            "retention/payout is missing or invalid".into(),
        );
        let before = state.detail("COF").expect("stale COF detail");
        assert!(before.dcf_analysis.is_none());
        assert!(before
            .valuation_unavailable_reason
            .as_deref()
            .is_some_and(|reason| reason.contains("retention/payout")));

        let screener = std::sync::Arc::new(std::sync::Mutex::new(state));
        let cik_cache = std::sync::Arc::new(std::sync::Mutex::new(None));
        let mut refresh_calls = 0;
        compute_demand_valuation_once_with_financial_refresh(
            "COF",
            &screener,
            &cik_cache,
            &None,
            |requested_symbol| {
                refresh_calls += 1;
                assert_eq!(requested_symbol, "COF");
                Ok(Some(fresh_fundamentals))
            },
        )
        .expect("demand valuation should recover stale COF");

        assert_eq!(refresh_calls, 1, "Detail must issue one bounded refresh");
        let state = screener.lock().unwrap();
        let after = state.detail("COF").expect("refreshed COF detail");
        assert_eq!(after.dcf_value_cents, Some(16_881));
        assert_eq!(
            after.dcf_analysis.as_ref().map(|analysis| analysis.model),
            Some(crate::dcf_model::ValuationModel::ResidualIncomeEquity)
        );
        assert_eq!(after.valuation_unavailable_reason, None);
    }

    #[test]
    fn regime_row_status_distinguishes_all_four_states_and_keeps_zero_included() {
        assert_eq!(
            resolve_regime_score_status(ScoringModel::AggressiveV3, true, true, true, Some(0)),
            RegimeScoreStatus::Included
        );
        assert_eq!(
            resolve_regime_score_status(ScoringModel::AggressiveV3, true, false, false, None),
            RegimeScoreStatus::Disabled
        );
        assert_eq!(
            resolve_regime_score_status(ScoringModel::ShortV3, true, true, false, None),
            RegimeScoreStatus::Unavailable
        );
        assert_eq!(
            resolve_regime_score_status(ScoringModel::AggressiveV3, true, true, true, None),
            RegimeScoreStatus::Unavailable
        );
        for model in [ScoringModel::AggressiveV2] {
            assert_eq!(
                resolve_regime_score_status(model, true, true, true, Some(25)),
                RegimeScoreStatus::NotApplicable
            );
        }
        for equity in [false] {
            assert_eq!(
                resolve_regime_score_status(
                    ScoringModel::AggressiveV3,
                    equity,
                    true,
                    true,
                    Some(25)
                ),
                RegimeScoreStatus::NotApplicable
            );
        }
    }

    #[test]
    fn shared_yahoo_cooldown_is_applied_once_to_the_retry_batch() {
        assert_eq!(batch_retry_delay_ms(37, 0), 38_000);
        assert_eq!(batch_retry_delay_ms(0, 0), 2_000);
        assert_eq!(batch_retry_delay_ms(0, 2), 12_000);
    }

    #[test]
    fn incomplete_retry_status_is_short_for_status_bar() {
        let pending = ["APT-USD", "ARB-USD", "CTRA", "HOLX", "SHIB-USD", "UNI-USD"]
            .map(String::from)
            .to_vec();
        let msg = format_incomplete_retry_status(4, 6, &pending);
        assert_eq!(msg, "Quotes retry 4/6: APT-USD, ARB-USD +4");
        assert!(
            msg.len() <= 60,
            "status bar truncates at 60 chars; got {}",
            msg.len()
        );
        assert!(
            !msg.to_ascii_lowercase().contains("feed.log"),
            "status bar must not mention diagnostics file path"
        );
    }

    #[test]
    fn terminal_incomplete_status_lists_few_tickers() {
        let pending = ["CTRA".into(), "HOLX".into()];
        let msg = format_terminal_incomplete_status(&pending);
        assert_eq!(msg, "Quotes incomplete: CTRA, HOLX");
        assert!(msg.len() <= 60, "status bar truncates at 60 chars");
        assert!(!msg.to_ascii_lowercase().contains("feed.log"));
    }

    #[test]
    fn generation_is_current_detects_stale_workers() {
        let gen = std::sync::atomic::AtomicU64::new(3);
        assert!(super::generation_is_current(&gen, 3));
        assert!(!super::generation_is_current(&gen, 2));
    }

    #[test]
    fn initial_pass_completion_is_generation_bound_and_resettable() {
        use std::sync::atomic::{AtomicU64, Ordering};

        let active = AtomicU64::new(4);
        let completed = AtomicU64::new(u64::MAX);
        assert_eq!(warmable_completed_generation(&active, &completed), None);
        assert!(!mark_initial_pass_complete_if_current(
            &active, &completed, 3
        ));
        assert_eq!(completed.load(Ordering::SeqCst), u64::MAX);

        assert!(mark_initial_pass_complete_if_current(
            &active, &completed, 4
        ));
        assert_eq!(warmable_completed_generation(&active, &completed), Some(4));

        active.store(5, Ordering::SeqCst);
        reset_initial_pass_completion(&completed);
        assert_eq!(warmable_completed_generation(&active, &completed), None);
    }

    #[test]
    fn clear_universe_preserves_scoring_model() {
        let mut state = ScreenerState::new();
        state.scoring_model = "aggressive_v2".into();
        state.ingest_snapshot(MarketSnapshot {
            symbol: "AAPL".into(),
            company_name: Some("Apple".into()),
            profitable: true,
            market_price_cents: 20_000,
            intrinsic_value_cents: 24_000,
            previous_close_cents: 19_500,
            next_earnings_epoch: None,
        });
        state.clear_universe();
        assert!(state.snapshots.is_empty());
        assert_eq!(state.scoring_model, "aggressive_v2");
    }

    #[test]
    fn price_only_refetch_does_not_requeue_when_state_already_enriched() {
        let mut state = ScreenerState::new();
        state.ingest_snapshot(MarketSnapshot {
            symbol: "AAPL".into(),
            company_name: Some("Apple Inc.".into()),
            profitable: true,
            market_price_cents: 20_000,
            intrinsic_value_cents: 24_000,
            previous_close_cents: 19_500,
            next_earnings_epoch: Some(1_800_000_000),
        });
        state.ingest_fundamentals(FundamentalSnapshot {
            symbol: "AAPL".into(),
            sector_name: Some("Technology".into()),
            ..Default::default()
        });

        let outcome = ingest_fetch_result(
            &mut state,
            FetchResult {
                symbol: "AAPL".into(),
                snapshot: Some(MarketSnapshot {
                    symbol: "AAPL".into(),
                    company_name: None,
                    profitable: false,
                    market_price_cents: 20_500,
                    intrinsic_value_cents: 0,
                    previous_close_cents: 0,
                    next_earnings_epoch: None,
                }),
                signal: None,
                fundamentals: None,
            },
            false,
            false,
        );

        assert!(outcome.visible);
        assert!(!outcome.enriched);
        assert!(symbol_state_enrichment_complete(&state, "AAPL"));
        assert!(!needs_enrichment_retry(outcome, &state, "AAPL"));
        let merged = state.snapshots.get("AAPL").unwrap();
        assert_eq!(merged.company_name.as_deref(), Some("Apple Inc."));
        assert_eq!(merged.market_price_cents, 20_500);
        assert_eq!(merged.intrinsic_value_cents, 24_000);
        assert_eq!(
            state.fundamentals["AAPL"].sector_name.as_deref(),
            Some("Technology")
        );
    }

    #[test]
    fn chart_only_stock_without_fundamentals_still_needs_retry() {
        let mut state = ScreenerState::new();
        let outcome = RefreshOutcome {
            visible: true,
            enriched: false,
        };
        state.ingest_partial_snapshot(MarketSnapshot {
            symbol: "SPARSE".into(),
            company_name: None,
            profitable: false,
            market_price_cents: 10_000,
            intrinsic_value_cents: 0,
            previous_close_cents: 0,
            next_earnings_epoch: None,
        });
        assert!(needs_enrichment_retry(outcome, &state, "SPARSE"));
    }

    #[test]
    fn chart_only_refresh_is_visible_and_preserves_existing_enrichment() {
        let mut state = ScreenerState::new();
        state.ingest_snapshot(MarketSnapshot {
            symbol: "AAPL".into(),
            company_name: Some("Apple Inc.".into()),
            profitable: true,
            market_price_cents: 20_000,
            intrinsic_value_cents: 24_000,
            previous_close_cents: 19_500,
            next_earnings_epoch: Some(1_800_000_000),
        });
        state.ingest_fundamentals(FundamentalSnapshot {
            symbol: "AAPL".into(),
            sector_name: Some("Technology".into()),
            ..Default::default()
        });

        let outcome = ingest_fetch_result(
            &mut state,
            FetchResult {
                symbol: "AAPL".into(),
                snapshot: Some(MarketSnapshot {
                    symbol: "AAPL".into(),
                    company_name: None,
                    profitable: false,
                    market_price_cents: 20_500,
                    intrinsic_value_cents: 0,
                    previous_close_cents: 0,
                    next_earnings_epoch: None,
                }),
                signal: None,
                fundamentals: None,
            },
            false,
            false,
        );

        assert!(outcome.visible);
        assert!(!outcome.enriched);
        let merged = state.snapshots.get("AAPL").unwrap();
        assert_eq!(merged.company_name.as_deref(), Some("Apple Inc."));
        assert_eq!(merged.market_price_cents, 20_500);
        assert_eq!(merged.intrinsic_value_cents, 24_000);
        assert_eq!(
            state.fundamentals["AAPL"].sector_name.as_deref(),
            Some("Technology")
        );
    }
}

/// Android refresh path for one symbol: fetchSymbol + Year chart.
///
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct RefreshOutcome {
    visible: bool,
    enriched: bool,
}

/// Fetch one symbol progressively: price/name makes it visible immediately, while
/// quoteSummary completeness controls only whether it remains in the retry set.
fn refresh_one_symbol(
    client: &YahooClient,
    screener: &std::sync::Mutex<crate::engine::ScreenerState>,
    feed_status: &std::sync::Mutex<crate::state::FeedStatus>,
    sym: &str,
) -> RefreshOutcome {
    let crypto = is_crypto(sym);
    let etf = is_etf(sym);
    let mut outcome = RefreshOutcome::default();

    match client.fetch_symbol(sym) {
        Ok(result) => {
            let mut s = screener.lock().unwrap();
            outcome = ingest_fetch_result(&mut s, result, crypto, etf);
            if outcome.enriched {
                feed_status.lock().unwrap().last_error = None;
            }
        }
        Err(e) => {
            let msg = e.to_string();
            if msg.contains("429") {
                feed_status.lock().unwrap().last_error = Some(
                    "Yahoo rate-limited — retrying until full quote columns are available".into(),
                );
            } else if !(msg.contains("404") || msg.contains("401") || msg.contains("403")) {
                feed_status.lock().unwrap().last_error = Some(format!("{sym}: {e}"));
            }
        }
    }

    // Year chart for spark/technicals — only attach to symbols already in the list,
    // or create crypto/ETF rows that can stand without analyst columns.
    if let Ok(candles) = client.fetch_candles(sym, "1y", "1d") {
        if let Some(summary) = compute_chart_summary(&candles) {
            let close = summary.latest_close_cents;
            let mut s = screener.lock().unwrap();
            let already = s.snapshots.contains_key(sym);
            s.ingest_chart_summary(sym.to_string(), summary);
            s.ingest_daily_candles(sym.to_string(), candles);
            if close > 0 {
                if already {
                    let needs_price = s
                        .snapshots
                        .get(sym)
                        .map(|x| x.market_price_cents <= 0)
                        .unwrap_or(true);
                    if needs_price {
                        use crate::engine::MarketSnapshot;
                        let prev = s.snapshots.get(sym).cloned();
                        s.ingest_partial_snapshot(MarketSnapshot {
                            symbol: sym.to_string(),
                            company_name: prev.as_ref().and_then(|x| x.company_name.clone()),
                            profitable: prev
                                .as_ref()
                                .map(|x| x.profitable)
                                .unwrap_or(crypto || etf),
                            market_price_cents: close,
                            intrinsic_value_cents: prev
                                .as_ref()
                                .map(|x| x.intrinsic_value_cents)
                                .unwrap_or(0),
                            previous_close_cents: prev
                                .as_ref()
                                .map(|x| x.previous_close_cents)
                                .unwrap_or(0),
                            next_earnings_epoch: prev.and_then(|x| x.next_earnings_epoch),
                        });
                    }
                    outcome.visible = true;
                } else {
                    // Chart alone is enough for progressive visibility. Asset-specific
                    // sector labels are enrichment, not a prerequisite for stock rows.
                    use crate::engine::{FundamentalSnapshot, MarketSnapshot};
                    s.ingest_partial_snapshot(MarketSnapshot {
                        symbol: sym.to_string(),
                        company_name: None,
                        profitable: crypto || etf,
                        market_price_cents: close,
                        intrinsic_value_cents: 0,
                        previous_close_cents: 0,
                        next_earnings_epoch: None,
                    });
                    let sector = if crypto {
                        Some("Cryptocurrency".to_string())
                    } else {
                        etf_sector(sym).map(|s| s.to_string())
                    };
                    if sector.is_some() {
                        s.ingest_fundamentals(FundamentalSnapshot {
                            symbol: sym.to_string(),
                            sector_name: sector,
                            ..Default::default()
                        });
                    }
                    outcome.visible = true;
                }
            }
        }
    }

    outcome
}

#[tauri::command]
pub fn start_feed(state: State<AppState>) -> Result<(), String> {
    {
        let status = state.feed_status.lock().unwrap();
        if status.running {
            return Ok(());
        }
    }
    // Cold start with the already-selected (or default) universe.
    let profile = state.active_profile.lock().unwrap().clone();
    apply_universe_profile(&profile, &state)
}

/// Spawn refresh / enrich / EDGAR / snapshot workers for symbols at generation.
/// Workers exit when eed_generation no longer matches generation.
fn spawn_feed_workers(
    state: &AppState,
    symbols: Arc<Vec<String>>,
    generation: u64,
) -> Result<(), String> {
    let total = symbols.len();
    let feed_gen = state.feed_generation_arc();

    // One shared client + session (Android: single YahooSession / OkHttp client).
    let shared_client = match YahooClient::new() {
        Ok(c) => Arc::new(c),
        Err(e) => {
            state.feed_status.lock().unwrap().last_error = Some(e.to_string());
            state.feed_status.lock().unwrap().running = false;
            return Err(e.to_string());
        }
    };
    let loaded = Arc::new(AtomicUsize::new(0));
    let completed = Arc::new(std::sync::Mutex::new(
        std::collections::HashSet::<String>::with_capacity(total),
    ));

    // ── Android-style refresh coordinator ───────────────────────────────────
    {
        let symbols = Arc::clone(&symbols);
        let client = Arc::clone(&shared_client);
        let screener = Arc::clone(&state.screener);
        let feed_status = Arc::clone(&state.feed_status);
        let feed_log = Arc::clone(&state.feed_log);
        let loaded = Arc::clone(&loaded);
        let completed = Arc::clone(&completed);
        let feed_gen = Arc::clone(&feed_gen);
        let initial_pass_completed_generation =
            Arc::clone(&state.initial_pass_completed_generation);

        thread::Builder::new()
            .name("feed-refresh".into())
            .spawn(move || {
                if !generation_is_current(&feed_gen, generation) {
                    return;
                }
                feed_log.info(&format!(
                    "feed refresh started gen={generation}: {} symbols, log={}",
                    symbols.len(),
                    feed_log.path().display()
                ));
                let mut pending: Vec<String> = symbols.iter().cloned().collect();

                for round in 0..=MAX_RETRY_ROUNDS {
                    if !generation_is_current(&feed_gen, generation) {
                        return;
                    }
                    if pending.is_empty() {
                        break;
                    }
                    if round > 0 {
                        let cool = client.rate_limit_remaining_secs();
                        let wait_ms = batch_retry_delay_ms(cool, round - 1);
                        let pending_refs = pending_as_refs(&pending);
                        feed_log.log_pending_retry(round, MAX_RETRY_ROUNDS, &pending_refs);
                        feed_status.lock().unwrap().last_error = Some(
                            format_incomplete_retry_status(round, MAX_RETRY_ROUNDS, &pending),
                        );
                        thread::sleep(std::time::Duration::from_millis(wait_ms));
                        if !generation_is_current(&feed_gen, generation) {
                            return;
                        }
                    }

                    let batch = Arc::new(pending);
                    let cursor = Arc::new(AtomicUsize::new(0));
                    let failed = Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
                    let mut handles = Vec::new();

                    for w in 0..REFRESH_CONCURRENCY {
                        let batch = Arc::clone(&batch);
                        let cursor = Arc::clone(&cursor);
                        let failed = Arc::clone(&failed);
                        let client = Arc::clone(&client);
                        let screener = Arc::clone(&screener);
                        let feed_status = Arc::clone(&feed_status);
                        let loaded = Arc::clone(&loaded);
                        let completed = Arc::clone(&completed);
                        let feed_gen = Arc::clone(&feed_gen);

                        handles.push(
                            thread::Builder::new()
                                .name(format!("refresh-{w}"))
                                .spawn(move || loop {
                                    if !generation_is_current(&feed_gen, generation) {
                                        break;
                                    }
                                    if client.is_rate_limited() {
                                        loop {
                                            let j = cursor.fetch_add(1, Ordering::Relaxed);
                                            if j >= batch.len() {
                                                break;
                                            }
                                            failed.lock().unwrap().push(batch[j].clone());
                                        }
                                        break;
                                    }

                                    let i = cursor.fetch_add(1, Ordering::Relaxed);
                                    if i >= batch.len() {
                                        break;
                                    }
                                    let sym = batch[i].as_str();
                                    let outcome =
                                        refresh_one_symbol(&client, &screener, &feed_status, sym);
                                    if !generation_is_current(&feed_gen, generation) {
                                        break;
                                    }
                                    if outcome.visible {
                                        let mut done = completed.lock().unwrap();
                                        if done.insert(sym.to_string()) {
                                            let n = loaded.fetch_add(1, Ordering::Relaxed) + 1;
                                            feed_status.lock().unwrap().symbols_loaded =
                                                n.min(total);
                                        }
                                    }
                                    let retry = {
                                        let s = screener.lock().unwrap();
                                        needs_enrichment_retry(outcome, &s, sym)
                                    };
                                    if retry {
                                        failed.lock().unwrap().push(sym.to_string());
                                    }
                                })
                                .expect("spawn refresh worker"),
                        );
                    }

                    for h in handles {
                        let _ = h.join();
                    }
                    if !generation_is_current(&feed_gen, generation) {
                        return;
                    }
                    pending = failed.lock().unwrap().clone();
                    pending.sort_unstable();
                    pending.dedup();
                }

                if !generation_is_current(&feed_gen, generation) {
                    return;
                }

                if !pending.is_empty() {
                    let pending_refs = pending_as_refs(&pending);
                    feed_log.log_terminal_incomplete(&pending_refs);
                    feed_status.lock().unwrap().last_error =
                        Some(format_terminal_incomplete_status(&pending));
                } else {
                    feed_log.info("feed initial enrichment complete: no pending symbols");
                }

                let _ = mark_initial_pass_complete_if_current(
                    &feed_gen,
                    &initial_pass_completed_generation,
                    generation,
                );

                loop {
                    thread::sleep(std::time::Duration::from_secs(FULL_REFRESH_INTERVAL_SECS));
                    if !generation_is_current(&feed_gen, generation) {
                        return;
                    }
                    let cursor = Arc::new(AtomicUsize::new(0));
                    let mut handles = Vec::new();
                    for w in 0..REFRESH_CONCURRENCY {
                        let symbols = Arc::clone(&symbols);
                        let cursor = Arc::clone(&cursor);
                        let client = Arc::clone(&client);
                        let screener = Arc::clone(&screener);
                        let feed_status = Arc::clone(&feed_status);
                        let loaded = Arc::clone(&loaded);
                        let completed = Arc::clone(&completed);
                        let feed_gen = Arc::clone(&feed_gen);
                        handles.push(
                            thread::Builder::new()
                                .name(format!("refresh-loop-{w}"))
                                .spawn(move || loop {
                                    if !generation_is_current(&feed_gen, generation) {
                                        break;
                                    }
                                    let i = cursor.fetch_add(1, Ordering::Relaxed);
                                    if i >= symbols.len() {
                                        break;
                                    }
                                    let sym = symbols[i].as_str();
                                    let outcome =
                                        refresh_one_symbol(&client, &screener, &feed_status, sym);
                                    if !generation_is_current(&feed_gen, generation) {
                                        break;
                                    }
                                    if outcome.visible {
                                        let mut done = completed.lock().unwrap();
                                        if done.insert(sym.to_string()) {
                                            let n = loaded.fetch_add(1, Ordering::Relaxed) + 1;
                                            feed_status.lock().unwrap().symbols_loaded =
                                                n.min(symbols.len());
                                        }
                                    }
                                })
                                .expect("spawn refresh loop worker"),
                        );
                    }
                    for h in handles {
                        let _ = h.join();
                    }
                }
            })
            .map_err(|e| {
                let message = format!("start feed coordinator: {e}");
                let mut status = state.feed_status.lock().unwrap();
                status.running = false;
                status.last_error = Some(message.clone());
                message
            })?;
    }

    // ── Enrichment ──────────────────────────────────────────────────────────
    {
        let symbols = Arc::clone(&symbols);
        let client = Arc::clone(&shared_client);
        let screener = Arc::clone(&state.screener);
        let fng_cache = Arc::clone(&state.fng_cache);
        let completed = Arc::clone(&completed);
        let enrich_cursor = Arc::new(AtomicUsize::new(0));
        let feed_gen = Arc::clone(&feed_gen);

        for w in 0..ENRICHMENT_CONCURRENCY {
            let symbols = Arc::clone(&symbols);
            let client = Arc::clone(&client);
            let screener = Arc::clone(&screener);
            let fng_cache = Arc::clone(&fng_cache);
            let completed = Arc::clone(&completed);
            let cursor = Arc::clone(&enrich_cursor);
            let feed_gen = Arc::clone(&feed_gen);

            if let Err(e) = thread::Builder::new()
                .name(format!("enrich-{w}"))
                .spawn(move || {
                    while completed.lock().unwrap().is_empty() {
                        if !generation_is_current(&feed_gen, generation) {
                            return;
                        }
                        thread::sleep(std::time::Duration::from_millis(500));
                    }
                    loop {
                        if !generation_is_current(&feed_gen, generation) {
                            return;
                        }
                        let i = cursor.fetch_add(1, Ordering::Relaxed);
                        if i >= symbols.len() {
                            break;
                        }
                        let sym = symbols[i].as_str();
                        if !completed.lock().unwrap().contains(sym) {
                            continue;
                        }
                        if client.is_rate_limited() {
                            break;
                        }

                        if let Ok(candles) = client.fetch_candles(sym, "5y", "1wk") {
                            if !generation_is_current(&feed_gen, generation) {
                                return;
                            }
                            if let Some(summary) = compute_chart_summary(&candles) {
                                let mut s = screener.lock().unwrap();
                                s.ingest_weekly_summary(sym.to_string(), summary);
                                if is_crypto(sym) {
                                    s.ingest_weekly_candles(sym.to_string(), candles.clone());
                                    drop(s);
                                    let fng = fng_cache.get_cached().or_else(|| {
                                        let http = crate::crypto_cycle::crypto_client();
                                        let v = crate::crypto_cycle::fetch_fear_greed(&http).ok();
                                        if let Some(ref fng) = v {
                                            fng_cache.put(fng.clone());
                                        }
                                        v
                                    });
                                    let now_e = std::time::SystemTime::now()
                                        .duration_since(std::time::UNIX_EPOCH)
                                        .map(|d| d.as_secs() as i64)
                                        .unwrap_or(0);
                                    let metrics = crate::crypto_cycle::compute_crypto_score(
                                        sym,
                                        &candles,
                                        Some(0),
                                        fng,
                                        now_e,
                                    );
                                    if generation_is_current(&feed_gen, generation) {
                                        screener
                                            .lock()
                                            .unwrap()
                                            .ingest_crypto_metrics(sym.to_string(), metrics);
                                    }
                                }
                            }
                        }
                        if let Ok(candles) = client.fetch_candles(sym, "1mo", "1h") {
                            if !generation_is_current(&feed_gen, generation) {
                                return;
                            }
                            if let Some(summary) = compute_chart_summary(&candles) {
                                screener
                                    .lock()
                                    .unwrap()
                                    .ingest_hourly_summary(sym.to_string(), summary);
                            }
                        }
                        if let Ok(candles) = client.fetch_candles(sym, "10y", "1mo") {
                            if !generation_is_current(&feed_gen, generation) {
                                return;
                            }
                            if let Some(summary) = compute_chart_summary(&candles) {
                                screener
                                    .lock()
                                    .unwrap()
                                    .ingest_monthly_summary(sym.to_string(), summary);
                            }
                        }
                        thread::sleep(std::time::Duration::from_millis(150));
                    }
                })
            {
                state.feed_status.lock().unwrap().last_error =
                    Some(format!("start enrichment worker {w}: {e}"));
            }
        }
    }

    // ── EDGAR DCF worker ────────────────────────────────────────────────────
    {
        let symbols = Arc::clone(&symbols);
        let screener = Arc::clone(&state.screener);
        let feed_status = Arc::clone(&state.feed_status);
        let feed_gen = Arc::clone(&feed_gen);

        thread::Builder::new()
            .name("edgar-dcf".to_string())
            .spawn(move || {
                let edgar_client = edgar::edgar_client();

                let cik_map: HashMap<String, u64> = match edgar::fetch_cik_map(&edgar_client) {
                    Ok(m) => m,
                    Err(e) => {
                        if generation_is_current(&feed_gen, generation) {
                            feed_status.lock().unwrap().last_error =
                                Some(format!("EDGAR CIK: {}", e));
                        }
                        return;
                    }
                };

                loop {
                    if !generation_is_current(&feed_gen, generation) {
                        return;
                    }
                    for sym in symbols.iter() {
                        if !generation_is_current(&feed_gen, generation) {
                            return;
                        }
                        let sym = sym.as_str();
                        if is_crypto(sym) || is_etf(sym) {
                            continue;
                        }
                        let cik = match cik_map.get(sym) {
                            Some(&c) => c,
                            None => continue,
                        };

                        let shares = screener
                            .lock()
                            .unwrap()
                            .fundamentals
                            .get(sym)
                            .and_then(|f| f.shares_outstanding)
                            .unwrap_or(0);

                        if shares == 0 {
                            continue;
                        }

                        // Operating valuations are demand-driven and must pass through the
                        // single evidence router (including Yahoo forward evidence). This
                        // periodic worker only maintains residual-income financials plus
                        // insider evidence; it must never publish a legacy FCFF candidate.
                        let business_class = {
                            let s = screener.lock().unwrap();
                            s.fundamentals
                                .get(sym)
                                .map(|fund| {
                                    crate::dcf_model::classify_business(
                                        fund.sector_name.as_deref(),
                                        fund.industry_name.as_deref(),
                                        fund.sector_key.as_deref(),
                                        fund.industry_key.as_deref(),
                                        false,
                                    )
                                })
                                .unwrap_or(crate::dcf_model::BusinessClass::Unclassified)
                        };

                        if business_class == crate::dcf_model::BusinessClass::FinancialServices {
                            let mut s = screener.lock().unwrap();
                            let fund = s.fundamentals.get(sym).cloned();
                            let price = s.snapshots.get(sym).map(|x| x.market_price_cents);
                            if let Some(fund) = fund {
                                if let Ok(analysis) = crate::dcf_model::compute_from_fundamentals(
                                    &fund,
                                    price,
                                    "fundamentals",
                                ) {
                                    s.ingest_dcf_analysis(sym.to_string(), analysis);
                                }
                            }
                        } else if matches!(
                            business_class,
                            crate::dcf_model::BusinessClass::Unclassified
                                | crate::dcf_model::BusinessClass::NotEligible
                        ) {
                            // Closed-world refusal also clears any legacy value restored
                            // before classification became available.
                            screener.lock().unwrap().clear_dcf(sym);
                        }

                        if let Ok(Some(ins)) = edgar::fetch_insider_activity(&edgar_client, cik) {
                            if !generation_is_current(&feed_gen, generation) {
                                return;
                            }
                            screener.lock().unwrap().ingest_insider(
                                sym.to_string(),
                                InsiderData {
                                    net_shares_90d: ins.net_shares_90d,
                                    buy_count: ins.buy_count,
                                    sell_count: ins.sell_count,
                                },
                            );
                        }
                        thread::sleep(std::time::Duration::from_millis(125));
                    }
                }
            })
            .map_err(|e| e.to_string())?;
    }

    // ── Snapshot worker (one per generation; exits when generation changes) ─
    {
        let screener = Arc::clone(&state.screener);
        let db = Arc::clone(&state.db);
        let feed_gen = Arc::clone(&feed_gen);

        thread::Builder::new()
            .name("snapshot".to_string())
            .spawn(move || {
                thread::sleep(std::time::Duration::from_secs(120));
                loop {
                    if !generation_is_current(&feed_gen, generation) {
                        return;
                    }
                    let now = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_secs() as i64)
                        .unwrap_or(0);

                    let rows = {
                        let s = screener.lock().unwrap();
                        let candidates = s.candidate_rows();
                        let bench = compute_sector_benchmarks(&candidates);
                        candidates
                            .into_iter()
                            .filter_map(|row| {
                                if row.market_price_cents <= 0 {
                                    return None;
                                }
                                let daily = s.chart_summaries.get(&row.symbol);
                                let weekly = s.weekly_summaries.get(&row.symbol);
                                let hourly = s.hourly_summaries.get(&row.symbol);
                                let candles_empty: Vec<HistoricalCandle> = Vec::new();
                                let candles_ref =
                                    s.daily_candles.get(&row.symbol).unwrap_or(&candles_empty);
                                let bench_for = row.sector_name.as_ref().and_then(|x| bench.get(x));
                                let (fund_score, _) = score_fundamentals_v2(&row, bench_for);
                                let (tech_score, _, _) =
                                    score_technicals_v3(weekly, daily, hourly, candles_ref);
                                let (fore_score, _) = score_forecast_v2(&row);
                                let composite =
                                    composite_score_v2(fund_score, tech_score, fore_score);
                                let technical_only = is_crypto(&row.symbol) || is_etf(&row.symbol);
                                let decision = decision_state(
                                    row.confidence,
                                    row.gap_bps,
                                    composite,
                                    row.free_cash_flow_dollars,
                                    row.market_cap_dollars,
                                    technical_only,
                                    tech_score,
                                );
                                Some(SnapshotRowOwned {
                                    symbol: row.symbol,
                                    captured_at: now,
                                    market_price_cents: row.market_price_cents,
                                    intrinsic_value_cents: row.intrinsic_value_cents,
                                    gap_bps: row.gap_bps.unwrap_or(0),
                                    decision: decision.to_string(),
                                    composite_score: composite,
                                    fundamentals_score: fund_score,
                                    technical_score: tech_score,
                                    forecast_score: fore_score,
                                    confidence: confidence_label(row.confidence).to_string(),
                                })
                            })
                            .collect::<Vec<_>>()
                    };

                    if !rows.is_empty() {
                        let borrowed: Vec<SnapshotInsert> = rows
                            .iter()
                            .map(|r| SnapshotInsert {
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
                            })
                            .collect();
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
pub fn compute_congress_metrics(state: State<AppState>) -> Result<CongressBacktestResult, String> {
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
                if errors.len() < 10 {
                    errors.push(format!("history unavailable: {}", symbol));
                }
                std::thread::sleep(std::time::Duration::from_millis(150));
                continue;
            }
        };

        let trades = state
            .db
            .trades_with_meta_for_symbol(symbol)
            .unwrap_or_default();
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
    state
        .db
        .top_politicians_with_metrics(&sort_key, limit.unwrap_or(50))
}

#[tauri::command]
pub fn get_politician_detail(
    politician_id: i64,
    state: State<AppState>,
) -> Result<
    (
        Option<crate::db::PoliticianWithMetrics>,
        Vec<crate::db::PoliticianTradeRow>,
    ),
    String,
> {
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
                    progress.lock().unwrap().last_error = Some(format!("Year {year}: {e}"));
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
    let weekly = client
        .fetch_candles(&symbol, "5y", "1wk")
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
    if sym.is_empty() {
        return Err("symbol required".into());
    }
    if quantity <= 0.0 {
        return Err("quantity must be > 0".into());
    }
    if avg_cost_cents <= 0 {
        return Err("avg cost must be > 0".into());
    }
    state
        .db
        .portfolio_add(&sym, quantity, avg_cost_cents, opened_at, notes)
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
    if quantity <= 0.0 {
        return Err("quantity must be > 0".into());
    }
    state
        .db
        .portfolio_update(id, quantity, avg_cost_cents, opened_at, notes)
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
        match state
            .db
            .portfolio_upsert_by_symbol(&sym, p.quantity, p.avg_cost_cents, p.opened_at)
        {
            Ok(true) => created += 1,
            Ok(false) => updated += 1,
            Err(_) => skipped += 1,
        }
    }
    Ok(PortfolioImportResult {
        created,
        updated,
        skipped,
    })
}

// ── Crypto Scalping ─────────────────────────────────────────────────────────────

/// Candles for the scalping chart (Coinbase, single timeframe).
#[tauri::command]
pub fn get_scalp_candles(
    product: String,
    timeframe: String,
) -> Result<Vec<crate::engine::HistoricalCandle>, String> {
    crate::crypto_md::fetch_candles(&product, &timeframe)
}

/// Set the product the real-time WebSocket feed should stream ticks for.
#[tauri::command]
pub fn scalp_ws_subscribe(product: String, state: State<AppState>) -> Result<(), String> {
    state
        .scalp_ws_tx
        .send(product.trim().to_uppercase())
        .map_err(|e| e.to_string())
}

/// Full multi-timeframe scalping analysis: per-TF indicators + score + signal.
/// `rr` is the reward:risk target (default 1.5); `fee_pct` is the per-side fee in
/// percent (default 0.6) so take-profit economics are net of round-trip fees.
#[tauri::command]
pub fn get_scalp_analysis(
    product: String,
    rr: Option<f64>,
    fee_pct: Option<f64>,
) -> Result<crate::scalping::ScalpAnalysis, String> {
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
        has_password: c
            .password
            .as_deref()
            .map(|p| !p.is_empty())
            .unwrap_or(false),
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
    smtp_host: String,
    smtp_port: i64,
    username: String,
    password: Option<String>,
    from_email: String,
    to_email: String,
    enabled: bool,
    daily_digest: bool,
    digest_hour: i64,
    instant_alerts: bool,
    state: State<AppState>,
) -> Result<(), String> {
    // Empty password string means "keep existing"; a real value replaces it.
    let pass = password.filter(|p| !p.is_empty());
    state.db.email_config_set(
        smtp_host.trim(),
        smtp_port,
        username.trim(),
        pass,
        from_email.trim(),
        to_email.trim(),
        enabled,
        daily_digest,
        digest_hour.clamp(0, 23),
        instant_alerts,
    )
}

/// Send an email using the stored SMTP config. Content is composed by the UI.
#[tauri::command]
pub fn email_send(
    subject: String,
    html: String,
    text: String,
    state: State<AppState>,
) -> Result<(), String> {
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
    pub configured: bool,   // app key + secret stored
    pub connected: bool,    // has a usable token (access or refreshable)
    pub needs_reauth: bool, // refresh token expired
    pub access_valid_until: Option<i64>,
    pub refresh_valid_until: Option<i64>,
    pub callback: Option<String>,
}

#[tauri::command]
pub fn schwab_set_credentials(
    app_key: String,
    secret: String,
    callback: String,
    state: State<AppState>,
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
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let st = match auth {
        None => SchwabStatus {
            configured: false,
            connected: false,
            needs_reauth: false,
            access_valid_until: None,
            refresh_valid_until: None,
            callback: None,
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
pub fn get_price_provenance(
    symbol: String,
    state: State<AppState>,
) -> Result<PriceProvenance, String> {
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
        } else {
            None
        }
    } else {
        None
    };
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
    if sym.is_empty() {
        return Err("symbol required".into());
    }
    if action.trim().is_empty() {
        return Err("action required".into());
    }
    let thesis = thesis.filter(|s| !s.trim().is_empty());
    state.db.journal_add(
        &sym,
        action.trim(),
        thesis,
        price_cents,
        setup_score,
        setup_label,
    )
}

#[tauri::command]
pub fn journal_close(
    id: i64,
    outcome: Option<String>,
    exit_price_cents: Option<i64>,
    state: State<AppState>,
) -> Result<(), String> {
    state.db.journal_close(
        id,
        outcome.filter(|s| !s.trim().is_empty()),
        exit_price_cents,
    )
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

#[cfg(test)]
mod qa_universe_apply_tests {
    use super::{apply_universe_profile, ensure_symbol_loaded_inner};
    use crate::launch_profile::ForcedProfile;
    use crate::profiles::QA_MAX_SYMBOLS;
    use crate::state::AppState;
    use std::sync::atomic::Ordering;
    use std::sync::Arc;

    fn temp_state(forced: Option<ForcedProfile>) -> AppState {
        let dir = std::env::temp_dir().join(format!(
            "ds_qa_state_{}_{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = std::fs::create_dir_all(&dir);
        let path = dir.join("history.sqlite");
        AppState::new_with_forced_profile(path, forced).expect("state")
    }

    #[test]
    fn qa_membership_never_exceeds_hard_cap() {
        let state = temp_state(Some(ForcedProfile { name: "qa".into() }));
        assert!(state.is_profile_locked());
        let n = state.active_symbols.lock().unwrap().len();
        assert!(n <= QA_MAX_SYMBOLS, "got {n}");
        assert_eq!(state.active_profile.lock().unwrap().as_str(), "qa");
    }

    #[test]
    fn locked_profile_rejects_switch_without_mutation() {
        let state = temp_state(Some(ForcedProfile { name: "qa".into() }));
        let before_gen = state.feed_generation.load(Ordering::SeqCst);
        let before_set: std::collections::HashSet<_> = state
            .active_symbols
            .lock()
            .unwrap()
            .iter()
            .cloned()
            .collect();
        let err = apply_universe_profile("sp500", &state).unwrap_err();
        assert!(err.contains("locked"), "{err}");
        assert_eq!(state.feed_generation.load(Ordering::SeqCst), before_gen);
        let after_set: std::collections::HashSet<_> = state
            .active_symbols
            .lock()
            .unwrap()
            .iter()
            .cloned()
            .collect();
        assert_eq!(before_set, after_set);
    }

    #[test]
    fn reapply_same_symbol_set_is_idempotent() {
        let state = temp_state(None);
        apply_universe_profile("qa", &state).unwrap();
        let gen1 = state.feed_generation.load(Ordering::SeqCst);
        // Reorder active symbols without changing set — should still match via set compare
        // after re-resolve (resolve order is stable; re-apply same qa is the main case).
        apply_universe_profile("qa", &state).unwrap();
        let gen2 = state.feed_generation.load(Ordering::SeqCst);
        assert_eq!(gen1, gen2, "same membership must not bump generation");
    }

    #[test]
    fn ensure_symbol_loaded_does_not_grow_active_symbols() {
        let state = temp_state(Some(ForcedProfile { name: "qa".into() }));
        let before = state.active_symbols.lock().unwrap().clone();
        // Network may fail; contract is membership size unchanged either way.
        let _ = ensure_symbol_loaded_inner("ZZZZNOPE".into(), &state);
        let after = state.active_symbols.lock().unwrap().clone();
        assert_eq!(before.len(), after.len());
        let before_set: std::collections::HashSet<_> = before.iter().cloned().collect();
        let after_set: std::collections::HashSet<_> = after.iter().cloned().collect();
        assert_eq!(before_set, after_set);
        // Ensure we did not replace Arc membership with a larger list.
        assert!(Arc::ptr_eq(&before, &after) || before_set == after_set);
    }
}
