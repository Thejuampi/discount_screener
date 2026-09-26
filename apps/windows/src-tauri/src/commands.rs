use std::collections::{HashMap, HashSet};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

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
    generation: u64,
    inflight: Arc<Mutex<HashSet<(u64, String)>>>,
}

impl Drop for ValuationInflightGuard {
    fn drop(&mut self) {
        if let Ok(mut inflight) = self.inflight.lock() {
            inflight.remove(&(self.generation, self.symbol.clone()));
        }
    }
}

fn claim_demand_valuation(
    inflight: &Mutex<HashSet<(u64, String)>>,
    generation: u64,
    symbol: &str,
) -> bool {
    inflight
        .lock()
        .unwrap()
        .insert((generation, symbol.to_string()))
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
pub fn get_app_version() -> String {
    env!("DS_APP_VERSION").to_string()
}

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
    build_opportunity_rows_with_hook(screener, apply_regime, regime_snapshot, || {})
}

fn build_opportunity_rows_with_hook<F: FnOnce()>(
    screener: &std::sync::Mutex<crate::engine::ScreenerState>,
    apply_regime: bool,
    regime_snapshot: Option<crate::regime::MarketRegime>,
    projection_started: F,
) -> Vec<OpportunityRow> {
    use crate::regime::{RegimeScoringPolicy, ScoreSide};

    let policy_long = regime_snapshot
        .as_ref()
        .and_then(|r| RegimeScoringPolicy::from_regime(r, ScoreSide::Long));
    let policy_short = regime_snapshot
        .as_ref()
        .and_then(|r| RegimeScoringPolicy::from_regime(r, ScoreSide::Short));

    // Freeze one coherent view. Scoring and price paths then run without the feed lock.
    let projection = capture_opportunity_projection(screener);
    project_opportunity_rows(
        projection,
        apply_regime,
        policy_long,
        policy_short,
        projection_started,
    )
}

struct OpportunityProjection {
    model: ScoringModel,
    rows: Vec<OpportunityProjectionInput>,
}

struct OpportunityProjectionInput {
    row: CandidateRow,
    daily: Option<crate::engine::ChartSummary>,
    weekly: Option<crate::engine::ChartSummary>,
    hourly: Option<crate::engine::ChartSummary>,
    daily_candles: Vec<HistoricalCandle>,
    dcf_analysis: Option<crate::dcf_model::DcfAnalysis>,
    crypto_setup: Option<(i32, &'static str)>,
}

fn capture_opportunity_projection(
    screener: &std::sync::Mutex<crate::engine::ScreenerState>,
) -> OpportunityProjection {
    let mut screener = screener.lock().unwrap();
    // Purge/replace stale FCFF caches for financials before scoring/list DCF values.
    let recon_syms: Vec<String> = screener.fundamentals.keys().cloned().collect();
    for sym in recon_syms {
        screener.ensure_model_routed_valuation(&sym);
    }
    let model = ScoringModel::parse(&screener.scoring_model);
    let rows = screener
        .candidate_rows()
        .into_iter()
        .map(|row| {
            let symbol = row.symbol.as_str();
            OpportunityProjectionInput {
                daily: screener.chart_summaries.get(symbol).cloned(),
                weekly: screener.weekly_summaries.get(symbol).cloned(),
                hourly: screener.hourly_summaries.get(symbol).cloned(),
                daily_candles: screener
                    .daily_candles
                    .get(symbol)
                    .cloned()
                    .unwrap_or_default(),
                dcf_analysis: screener.selected_dcf_analysis(symbol).cloned(),
                crypto_setup: screener
                    .crypto_metrics
                    .get(symbol)
                    .map(|metrics| (metrics.crypto_score, metrics.crypto_label)),
                row,
            }
        })
        .collect();
    OpportunityProjection { model, rows }
}

fn project_opportunity_rows<F: FnOnce()>(
    projection: OpportunityProjection,
    apply_regime: bool,
    policy_long: Option<crate::regime::RegimeScoringPolicy>,
    policy_short: Option<crate::regime::RegimeScoringPolicy>,
    projection_started: F,
) -> Vec<OpportunityRow> {
    use crate::regime::score_regime_fit;

    let model = projection.model;
    let rows: Vec<CandidateRow> = projection
        .rows
        .iter()
        .map(|input| input.row.clone())
        .collect();
    let benchmarks = compute_sector_benchmarks(&rows);
    let mut projection_started = Some(projection_started);
    projection.rows.into_iter()
        .map(|input| {
            if let Some(hook) = projection_started.take() {
                hook();
            }
            let OpportunityProjectionInput {
                row,
                daily,
                weekly,
                hourly,
                daily_candles,
                dcf_analysis,
                crypto_setup,
            } = input;
            let daily = daily.as_ref();
            let weekly = weekly.as_ref();
            let hourly = hourly.as_ref();
            let daily_candles_ref = daily_candles.as_slice();
            let bench = row.sector_name.as_ref().and_then(|s| benchmarks.get(s));
            let dcf_analysis = dcf_analysis.as_ref();
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
                if let Some((score, label)) = crypto_setup {
                    (score, label)
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
                if daily_candles_ref.len() >= 2 && row.market_price_cents > 0 {
                    let prev = daily_candles_ref[daily_candles_ref.len() - 2].close_cents;
                    if prev > 0 {
                        Some(
                            (((row.market_price_cents - prev) as f64 / prev as f64) * 10_000.0)
                                .round() as i32,
                        )
                    } else {
                        None
                    }
                } else {
                    None
                }
            };
            let dcf = row.dcf_value_cents;
            let n = daily_candles_ref.len();
            let spark: Vec<i64> = daily_candles_ref[n.saturating_sub(24)..]
                .iter()
                .map(|c| c.close_cents)
                .collect();
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

#[cfg(test)]
mod opportunity_projection_tests {
    use super::*;
    use crate::engine::{
        ExternalValuationSignal, FundamentalSnapshot, MarketSnapshot, ScreenerState,
    };
    use crate::regime::MarketRegime;
    use std::sync::{mpsc, Arc, Mutex};

    fn fixture_state() -> Arc<Mutex<ScreenerState>> {
        let mut state = ScreenerState::new();
        for (symbol, price, intrinsic, sector) in [
            ("AAPL", 20_000, 27_000, "Technology"),
            ("COF", 15_000, 22_000, "Financial Services"),
            ("BTC-USD", 6_000_000, 6_500_000, "Cryptocurrency"),
        ] {
            state.ingest_snapshot(MarketSnapshot {
                symbol: symbol.into(),
                company_name: Some(format!("{symbol} Inc.")),
                profitable: true,
                market_price_cents: price,
                intrinsic_value_cents: intrinsic,
                previous_close_cents: price - 100,
                next_earnings_epoch: None,
            });
            state.ingest_signal(ExternalValuationSignal {
                symbol: symbol.into(),
                fair_value_cents: intrinsic,
                age_seconds: 0,
                low_fair_value_cents: Some(intrinsic - 1_000),
                high_fair_value_cents: Some(intrinsic + 1_000),
                analyst_opinion_count: Some(16),
                recommendation_mean_hundredths: Some(180),
                strong_buy_count: Some(5),
                buy_count: Some(8),
                hold_count: Some(2),
                sell_count: Some(1),
                strong_sell_count: Some(0),
                weighted_fair_value_cents: None,
                weighted_analyst_count: None,
            });
            if symbol != "BTC-USD" {
                state.ingest_fundamentals(FundamentalSnapshot {
                    symbol: symbol.into(),
                    sector_name: Some(sector.into()),
                    market_cap_dollars: Some(900_000_000_000),
                    shares_outstanding: Some(1_000_000_000),
                    return_on_equity_bps: Some(2_000),
                    earnings_growth_bps: Some(1_200),
                    free_cash_flow_dollars: Some(45_000_000_000),
                    operating_cash_flow_dollars: Some(60_000_000_000),
                    beta_millis: Some(1_200),
                    book_value_per_share_cents: (symbol == "COF").then_some(12_000),
                    retention_bps: (symbol == "COF").then_some(7_500),
                    ..Default::default()
                });
            }
            let candles: Vec<HistoricalCandle> = (0..35)
                .map(|day| HistoricalCandle {
                    epoch_seconds: day * 86_400,
                    open_cents: price - 300 + day * 10,
                    high_cents: price - 100 + day * 10,
                    low_cents: price - 500 + day * 10,
                    close_cents: price - 250 + day * 10,
                    volume: 1_000_000 + day as u64 * 10_000,
                })
                .collect();
            let summary = compute_chart_summary(&candles).expect("fixture chart");
            state.ingest_chart_summary(symbol.into(), summary.clone());
            state.ingest_weekly_summary(symbol.into(), summary.clone());
            state.ingest_hourly_summary(symbol.into(), summary);
            state.ingest_daily_candles(symbol.into(), candles.clone());
            if symbol == "BTC-USD" {
                state.ingest_crypto_metrics(
                    symbol.into(),
                    crate::crypto_cycle::compute_crypto_score(
                        symbol,
                        &candles,
                        Some(40),
                        None,
                        1_800_000_000,
                    ),
                );
            }
        }
        Arc::new(Mutex::new(state))
    }

    fn usable_regime() -> MarketRegime {
        MarketRegime {
            primary_regime: "LateBull".into(),
            environment_band: "RiskOn".into(),
            action_stance: "Euphoria".into(),
            global_confidence_bps: 9_000,
            cnn_fear_greed: Some(85),
            ..MarketRegime::default()
        }
    }

    #[test]
    fn projection_baseline_for_all_models_and_regime_states() {
        let state = fixture_state();
        // Fixed FNV-1a fingerprints cover every serialized field, including paths and signals.
        let expected = [
            [10_657_763_759_874_342_574; 3],
            [
                8_842_849_984_942_063_844,
                3_695_932_376_257_929_518,
                3_728_405_759_594_883_312,
            ],
            [
                8_098_799_701_833_326_822,
                8_720_776_105_178_677_850,
                6_051_405_844_356_966_017,
            ],
        ];
        for (model_index, model) in ["aggressive_v2", "aggressive_v3", "short_v3"]
            .into_iter()
            .enumerate()
        {
            state.lock().unwrap().scoring_model = model.into();
            for (case_index, (name, enabled, regime)) in [
                ("disabled", false, None),
                ("unavailable", true, None),
                ("available", true, Some(usable_regime())),
            ]
            .into_iter()
            .enumerate()
            {
                let rows = build_opportunity_rows(&state, enabled, regime);
                let json = serde_json::to_string(&rows).unwrap();
                let digest = json
                    .bytes()
                    .fold(14_695_981_039_346_656_037_u64, |hash, byte| {
                        (hash ^ byte as u64).wrapping_mul(1_099_511_628_211)
                    });
                assert_eq!(digest, expected[model_index][case_index], "{model} {name}");
                assert_eq!(rows.len(), 3);
            }
        }
    }

    #[test]
    fn feed_writer_can_acquire_lock_during_projection() {
        let state = fixture_state();
        let worker_state = Arc::clone(&state);
        let (started_tx, started_rx) = mpsc::channel();
        let (resume_tx, resume_rx) = mpsc::channel();
        let worker = thread::spawn(move || {
            build_opportunity_rows_with_hook(&worker_state, true, Some(usable_regime()), || {
                started_tx.send(()).unwrap();
                resume_rx.recv().unwrap();
            })
        });
        started_rx.recv_timeout(Duration::from_secs(10)).unwrap();
        let (writer_tx, writer_rx) = mpsc::channel();
        let writer_state = Arc::clone(&state);
        let writer = thread::spawn(move || {
            writer_state.lock().unwrap().scoring_model = "short_v3".into();
            writer_tx.send(()).unwrap();
        });
        let acquired = writer_rx.recv_timeout(Duration::from_secs(2)).is_ok();
        resume_tx.send(()).unwrap();
        writer.join().unwrap();
        let projected = worker.join().unwrap();
        assert!(
            acquired,
            "feed writer must acquire the lock while projection waits"
        );
        assert_eq!(projected[0].row.symbol, "COF");
        assert_eq!(projected[0].composite_score, 32);
        assert_eq!(state.lock().unwrap().scoring_model, "short_v3");
    }

    #[test]
    fn captured_projection_keeps_one_profile_and_model_after_live_state_changes() {
        use crate::regime::{RegimeScoringPolicy, ScoreSide};

        let state = fixture_state();
        let regime = usable_regime();
        let baseline = build_opportunity_rows(&state, true, Some(regime.clone()));
        let captured = capture_opportunity_projection(&state);

        {
            let mut live = state.lock().unwrap();
            live.clear_universe();
            live.scoring_model = "short_v3".into();
            live.ingest_snapshot(MarketSnapshot {
                symbol: "MSFT".into(),
                company_name: Some("Microsoft".into()),
                profitable: true,
                market_price_cents: 25_000,
                intrinsic_value_cents: 30_000,
                previous_close_cents: 24_000,
                next_earnings_epoch: None,
            });
        }

        let projected = project_opportunity_rows(
            captured,
            true,
            RegimeScoringPolicy::from_regime(&regime, ScoreSide::Long),
            RegimeScoringPolicy::from_regime(&regime, ScoreSide::Short),
            || {},
        );
        assert_eq!(
            serde_json::to_value(&projected).unwrap(),
            serde_json::to_value(&baseline).unwrap()
        );
        let live = build_opportunity_rows(&state, true, Some(regime));
        assert_eq!(live.len(), 1);
        assert_eq!(live[0].row.symbol, "MSFT");
        assert_eq!(
            live[0].price_path.as_ref().map(|path| path.side),
            Some(crate::price_path::PathSide::Short)
        );
    }

    #[test]
    fn financial_reconciliation_is_captured_before_projection() {
        let state = fixture_state();
        let baseline = build_opportunity_rows(&state, false, None);
        let old_dcf = baseline
            .iter()
            .find(|row| row.row.symbol == "COF")
            .unwrap()
            .dcf_value_cents;
        assert!(old_dcf.is_some_and(|value| value > 0));
        let captured = capture_opportunity_projection(&state);
        state
            .lock()
            .unwrap()
            .ingest_fundamentals(FundamentalSnapshot {
                symbol: "COF".into(),
                return_on_equity_bps: Some(800),
                ..Default::default()
            });

        let previous = project_opportunity_rows(captured, false, None, None, || {});
        let previous_dcf = previous
            .iter()
            .find(|row| row.row.symbol == "COF")
            .unwrap()
            .dcf_value_cents;
        let current = build_opportunity_rows(&state, false, None);
        let current_dcf = current
            .iter()
            .find(|row| row.row.symbol == "COF")
            .unwrap()
            .dcf_value_cents;
        assert_eq!(previous_dcf, old_dcf);
        assert_ne!(current_dcf, old_dcf);
    }
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
    let generation = state.feed_generation.load(Ordering::SeqCst);
    let request_context = {
        let mut s = state.screener.lock().unwrap();
        if !generation_is_current(&state.feed_generation, generation) {
            return;
        }
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
                    market_price_cents: None,
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
        DemandFailureContext::capture(&s, symbol)
    };
    if !claim_demand_valuation(&state.valuation_inflight, generation, symbol) {
        return; // already computing for this generation
    }

    let worker_symbol = symbol.to_string();
    let screener = Arc::clone(&state.screener);
    let cik_cache = Arc::clone(&state.edgar_cik_map);
    let inflight = Arc::clone(&state.valuation_inflight);
    let feed_log = Arc::clone(&state.feed_log);
    let feed_gen = state.feed_generation_arc();
    let valuation_yahoo = state.valuation_yahoo.clone();
    let mut worker_context = request_context.clone();

    let spawn_result = thread::Builder::new()
        .name(format!("edgar-dcf-{worker_symbol}"))
        .spawn(move || {
            let _inflight_guard = ValuationInflightGuard {
                symbol: worker_symbol.clone(),
                generation,
                inflight,
            };
            let result = catch_unwind(AssertUnwindSafe(|| {
                compute_demand_valuation_once(
                    &worker_symbol,
                    &screener,
                    &cik_cache,
                    &valuation_yahoo,
                    &feed_gen,
                    generation,
                    &mut worker_context,
                )
            }));

            match result {
                Ok(Ok(())) => {}
                Ok(Err(error)) => {
                    feed_log.warn(&format!("demand-valuation {worker_symbol}: {error}"));
                    record_demand_valuation_failure(
                        &worker_symbol,
                        &screener,
                        &feed_gen,
                        generation,
                        &worker_context.input_key,
                        &worker_context.dcf_revision,
                        &error,
                    );
                }
                Err(_) => {
                    let error = "valuation worker panicked";
                    feed_log.warn(&format!("demand-valuation {worker_symbol}: {error}"));
                    record_demand_valuation_failure(
                        &worker_symbol,
                        &screener,
                        &feed_gen,
                        generation,
                        &worker_context.input_key,
                        &worker_context.dcf_revision,
                        error,
                    );
                }
            }
        });
    if let Err(error) = spawn_result {
        state
            .valuation_inflight
            .lock()
            .unwrap()
            .remove(&(generation, symbol.to_string()));
        state
            .feed_log
            .warn(&format!("demand-valuation {symbol}: start worker: {error}"));
        record_demand_valuation_failure(
            symbol,
            &state.screener,
            &state.feed_generation,
            generation,
            &request_context.input_key,
            &request_context.dcf_revision,
            &format!("start valuation worker: {error}"),
        );
    }
}

fn record_demand_valuation_failure(
    symbol: &str,
    screener: &Arc<std::sync::Mutex<crate::engine::ScreenerState>>,
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    expected_input_key: &[u8],
    expected_dcf_revision: &[u8],
    error: &str,
) {
    let mut state = screener.lock().unwrap();
    if !generation_is_current(active_generation, generation) || error == DEMAND_INPUTS_CHANGED {
        return;
    }
    let Some(fund) = state.fundamentals.get(symbol).cloned() else {
        return;
    };
    let price = state
        .snapshots
        .get(symbol)
        .map(|snapshot| snapshot.market_price_cents);
    if financial_dcf_input_key(&fund, price) != expected_input_key
        || dcf_revision_key(&state, symbol) != expected_dcf_revision
    {
        return;
    }
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
            market_price_cents: None,
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

const DEMAND_INPUTS_CHANGED: &str = "valuation inputs changed during demand computation";

#[derive(Clone)]
struct DemandFailureContext {
    input_key: Vec<u8>,
    dcf_revision: Vec<u8>,
}

impl DemandFailureContext {
    fn capture(state: &crate::engine::ScreenerState, symbol: &str) -> Self {
        let input_key = state
            .fundamentals
            .get(symbol)
            .map(|fund| {
                let price = state
                    .snapshots
                    .get(symbol)
                    .map(|snapshot| snapshot.market_price_cents);
                financial_dcf_input_key(fund, price)
            })
            .unwrap_or_default();
        Self {
            input_key,
            dcf_revision: dcf_revision_key(state, symbol),
        }
    }

    fn matches_current(&self, state: &crate::engine::ScreenerState, symbol: &str) -> bool {
        let current = Self::capture(state, symbol);
        current.input_key == self.input_key && current.dcf_revision == self.dcf_revision
    }
}

fn demand_inputs_are_current(
    state: &crate::engine::ScreenerState,
    symbol: &str,
    fund: &crate::engine::FundamentalSnapshot,
    price: Option<i64>,
) -> bool {
    let Some(current_fund) = state.fundamentals.get(symbol) else {
        return false;
    };
    let current_price = state
        .snapshots
        .get(symbol)
        .map(|snapshot| snapshot.market_price_cents);
    financial_dcf_input_key(current_fund, current_price) == financial_dcf_input_key(fund, price)
}

fn dcf_revision_key(state: &crate::engine::ScreenerState, symbol: &str) -> Vec<u8> {
    // The worker can publish a forward-only envelope or an error without DCF.
    // Capture every valuation output that a concurrent worker could replace.
    serde_json::to_vec(&(
        state.dcf_analyses.get(symbol),
        state.dcf_values.get(symbol),
        state.operating_valuations.get(symbol),
        state.valuation_errors.get(symbol),
    ))
    .expect("valuation state is serializable")
}

fn demand_publication_inputs_are_current(
    state: &crate::engine::ScreenerState,
    symbol: &str,
    fund: &crate::engine::FundamentalSnapshot,
    price: Option<i64>,
    captured_dcf_revision: &[u8],
) -> bool {
    demand_inputs_are_current(state, symbol, fund, price)
        && dcf_revision_key(state, symbol) == captured_dcf_revision
}

/// Prefer live US 10Y for solid rate quality; fall back to provisional defaults.
fn resolve_market_params(yahoo: Option<&YahooClient>) -> crate::dcf_model::MarketParams {
    if let Some(client) = yahoo {
        if let Some((rf_bps, as_of)) = client.fetch_us_10y_yield_bps() {
            return crate::dcf_model::MarketParams::from_live_risk_free(rf_bps, as_of);
        }
    }
    crate::dcf_model::MarketParams::default_usd()
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
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    failure_context: &mut DemandFailureContext,
) -> Result<(), String> {
    compute_demand_valuation_once_with_financial_refresh(
        symbol,
        screener,
        cik_cache,
        valuation_yahoo,
        active_generation,
        generation,
        failure_context,
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
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    failure_context: &mut DemandFailureContext,
    refresh_financial_fundamentals: F,
) -> Result<(), String>
where
    F: FnOnce(&str) -> Result<Option<crate::engine::FundamentalSnapshot>, String>,
{
    let (mut fund, price, class, existing_fcff, mut dcf_revision) = {
        let s = screener.lock().unwrap();
        if !generation_is_current(active_generation, generation) {
            return Err("profile changed during demand valuation".into());
        }
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
        *failure_context = DemandFailureContext::capture(&s, symbol);
        (
            fund,
            price,
            class,
            existing_fcff,
            dcf_revision_key(&s, symbol),
        )
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
                if !generation_is_current(active_generation, generation) {
                    return Err("profile changed during demand valuation".into());
                }
                if !demand_publication_inputs_are_current(
                    &state,
                    symbol,
                    &fund,
                    price,
                    &dcf_revision,
                ) {
                    return Err(DEMAND_INPUTS_CHANGED.into());
                }
                state.ingest_fundamentals(refreshed);
                *failure_context = DemandFailureContext::capture(&state, symbol);
                dcf_revision = dcf_revision_key(&state, symbol);
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
            let previous_fund = fund.clone();
            fund.shares_outstanding = Some(shares);
            shares_resolved_from_sec = true;
            let mut state = screener.lock().unwrap();
            if !generation_is_current(active_generation, generation) {
                return Err("profile changed during demand valuation".into());
            }
            if !demand_publication_inputs_are_current(
                &state,
                symbol,
                &previous_fund,
                price,
                &dcf_revision,
            ) {
                return Err(DEMAND_INPUTS_CHANGED.into());
            }
            state.ingest_fundamentals(fund.clone());
            *failure_context = DemandFailureContext::capture(&state, symbol);
            dcf_revision = dcf_revision_key(&state, symbol);
        }
        if !matches!(fund.retention_bps, Some(0..=10_000)) {
            return Err("retention/payout is missing or invalid after Yahoo refresh".into());
        }
        let market_params = resolve_market_params(valuation_yahoo.as_deref());
        let mut analysis = crate::dcf_model::compute_with_params(
            &fund,
            &[],
            price,
            &market_params,
            "fundamentals",
            false,
        )?;
        if shares_resolved_from_sec {
            analysis.reason_codes.push("shares=sec_dei_fallback".into());
        }
        let mut state = screener.lock().unwrap();
        if !generation_is_current(active_generation, generation) {
            return Err("profile changed during demand valuation".into());
        }
        if !demand_publication_inputs_are_current(&state, symbol, &fund, price, &dcf_revision) {
            return Err(DEMAND_INPUTS_CHANGED.into());
        }
        state.ingest_dcf_analysis(symbol.to_string(), analysis);
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
                        let previous_fund = fund.clone();
                        fund.shares_outstanding = Some(shares);
                        shares_resolved_from_sec = true;
                        let mut state = screener.lock().unwrap();
                        if !generation_is_current(active_generation, generation) {
                            return Err("profile changed during demand valuation".into());
                        }
                        if !demand_publication_inputs_are_current(
                            &state,
                            symbol,
                            &previous_fund,
                            price,
                            &dcf_revision,
                        ) {
                            return Err(DEMAND_INPUTS_CHANGED.into());
                        }
                        state.ingest_fundamentals(fund.clone());
                        *failure_context = DemandFailureContext::capture(&state, symbol);
                        dcf_revision = dcf_revision_key(&state, symbol);
                    }
                    Ok(None) => fcff_failure = Some("missing_shares:yahoo_and_sec_dei".to_string()),
                    Err(error) => fcff_failure = Some(format!("sec_shares:{error}")),
                }
            }
            if analysis.is_none() && fcff_failure.is_none() {
                match edgar::fetch_fcf_history(&edgar::edgar_client(), symbol, cik) {
                    Ok(Some(fcf)) => {
                        let market_params = resolve_market_params(valuation_yahoo.as_deref());
                        match crate::dcf_model::compute_with_params(
                            &fund,
                            &fcf,
                            None,
                            &market_params,
                            "sec_edgar",
                            false,
                        ) {
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

    let market_params = resolve_market_params(valuation_yahoo.as_deref());
    let envelope = crate::operating_valuation_runtime::route_runtime_valuation(
        crate::operating_valuation_runtime::RuntimeValuationInput {
            business_class: class,
            fundamentals: &fund,
            fcff_analysis: analysis.as_ref(),
            fcff_failure: fcff_failure.as_deref(),
            forward_evidence,
            market_params: &market_params,
            as_of_epoch_day,
            market_price_cents: price,
        },
    );
    let mut state = screener.lock().unwrap();
    if !generation_is_current(active_generation, generation) {
        return Err("profile changed during demand valuation".into());
    }
    if !demand_publication_inputs_are_current(&state, symbol, &fund, price, &dcf_revision) {
        return Err(DEMAND_INPUTS_CHANGED.into());
    }
    state.ingest_operating_valuation(symbol.to_string(), analysis, envelope);
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
    with_profile_apply_gate(&state.profile_apply_gate, || {
        apply_universe_profile_serial(raw_name, state)
    })
}

fn with_profile_apply_gate<T>(gate: &Mutex<()>, apply: impl FnOnce() -> T) -> T {
    let _guard = gate.lock().unwrap_or_else(|poison| poison.into_inner());
    apply()
}

fn with_current_profile_generation<T>(
    gate: &Mutex<()>,
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    write: impl FnOnce() -> T,
) -> Option<T> {
    with_profile_apply_gate(gate, || {
        generation_is_current(active_generation, generation).then(write)
    })
}

fn apply_universe_profile_serial(raw_name: &str, state: &AppState) -> Result<(), String> {
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
    let generation = state.feed_generation.load(Ordering::SeqCst);
    let client = YahooClient::new().map_err(|e| e.to_string())?;
    let result = client.fetch_symbol(&symbol).map_err(|e| e.to_string())?;

    apply_refresh_quote_if_current(
        &state.feed_generation,
        generation,
        &state.screener,
        result,
        is_crypto(&symbol),
        is_etf(&symbol),
    )
    .ok_or_else(|| "profile changed during symbol refresh".to_string())?;
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
    let feed_gen = state.feed_generation_arc();
    let generation = feed_gen.load(Ordering::SeqCst);
    let valuation_yahoo = state.valuation_yahoo.clone();
    tauri::async_runtime::spawn_blocking(move || {
        for symbol in &symbols {
            let (needs_valuation, mut audit_context) = {
                let mut s = screener.lock().unwrap();
                if !generation_is_current(&feed_gen, generation) {
                    return Err("profile changed during QA valuation audit".into());
                }
                s.ensure_model_routed_valuation(symbol);
                let needs = !s.has_current_operating_valuation(symbol)
                    && !s.dcf_analyses.get(symbol).is_some_and(|analysis| {
                        analysis.business_class
                            == crate::dcf_model::BusinessClass::FinancialServices
                    });
                (needs, DemandFailureContext::capture(&s, symbol))
            };
            if needs_valuation {
                if let Err(error) = compute_demand_valuation_once(
                    symbol,
                    &screener,
                    &cik_cache,
                    &valuation_yahoo,
                    &feed_gen,
                    generation,
                    &mut audit_context,
                ) {
                    feed_log.warn(&format!("qa-divergence-audit {symbol}: {error}"));
                    if error == DEMAND_INPUTS_CHANGED {
                        return Err(DEMAND_INPUTS_CHANGED.into());
                    }
                    let mut s = screener.lock().unwrap();
                    if !generation_is_current(&feed_gen, generation) {
                        return Err("profile changed during QA valuation audit".into());
                    }
                    if !audit_context.matches_current(&s, symbol) {
                        return Err(DEMAND_INPUTS_CHANGED.into());
                    }
                    s.set_valuation_error(symbol.clone(), error);
                }
            }
        }

        let candidates = {
            let mut s = screener.lock().unwrap();
            if !generation_is_current(&feed_gen, generation) {
                return Err("profile changed during QA valuation audit".into());
            }
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
    let generation = state.feed_generation.load(Ordering::SeqCst);

    let client = YahooClient::new().map_err(|e| e.to_string())?;
    let _ = client.warm_session();

    match client.fetch_symbol(&symbol) {
        Ok(result) => {
            apply_refresh_quote_if_current(
                &state.feed_generation,
                generation,
                &state.screener,
                result,
                is_crypto(&symbol),
                is_etf(&symbol),
            )
            .ok_or_else(|| "profile changed during detail load".to_string())?;
        }
        Err(_) => {
            // Quote may fail; candles below can still recover a price path.
        }
    }

    if !generation_is_current(&state.feed_generation, generation) {
        return Err("profile changed during detail load".into());
    }

    if let Ok(candles) = client.fetch_candles(&symbol, "1y", "1d") {
        if let Some(summary) = compute_chart_summary(&candles) {
            if !mutate_current_enrichment_state(
                &state.feed_generation,
                generation,
                &state.screener,
                |s| {
                    s.ingest_chart_summary(symbol.clone(), summary);
                    s.ingest_daily_candles(symbol.clone(), candles);
                },
            ) {
                return Err("profile changed during detail load".into());
            }
        }
    }

    if !generation_is_current(&state.feed_generation, generation) {
        return Err("profile changed during detail load".into());
    }

    // Deep multi-TF in background — detail UI already has price + daily chart.
    let screener = Arc::clone(&state.screener);
    let fng_cache = Arc::clone(&state.fng_cache);
    let feed_gen = state.feed_generation_arc();
    let deep_symbol = symbol.clone();
    let _ = thread::Builder::new()
        .name(format!("ensure-deep-{}", deep_symbol))
        .spawn(move || {
            if !generation_is_current(&feed_gen, generation) {
                return;
            }
            let client = match YahooClient::new() {
                Ok(c) => c,
                Err(_) => return,
            };
            if let Ok(candles) = client.fetch_candles(&deep_symbol, "5y", "1wk") {
                if let Some(summary) = compute_chart_summary(&candles) {
                    let crypto = is_crypto(&deep_symbol);
                    if !mutate_current_enrichment_state(&feed_gen, generation, &screener, |s| {
                        s.ingest_weekly_summary(deep_symbol.clone(), summary);
                        if crypto {
                            s.ingest_weekly_candles(deep_symbol.clone(), candles.clone());
                        }
                    }) {
                        return;
                    }
                    if crypto {
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
                        if !mutate_current_enrichment_state(&feed_gen, generation, &screener, |s| {
                            s.ingest_crypto_metrics(deep_symbol.clone(), metrics)
                        }) {
                            return;
                        }
                    }
                }
            }
            if !generation_is_current(&feed_gen, generation) {
                return;
            }
            if let Ok(candles) = client.fetch_candles(&deep_symbol, "1mo", "1h") {
                if let Some(summary) = compute_chart_summary(&candles) {
                    if !mutate_current_enrichment_state(&feed_gen, generation, &screener, |s| {
                        s.ingest_hourly_summary(deep_symbol.clone(), summary)
                    }) {
                        return;
                    }
                }
            }
            if !generation_is_current(&feed_gen, generation) {
                return;
            }
            if let Ok(candles) = client.fetch_candles(
                &deep_symbol,
                DETAIL_MONTHLY_CHART_REQUEST.range,
                DETAIL_MONTHLY_CHART_REQUEST.interval,
            ) {
                if let Some(summary) = compute_chart_summary(&candles) {
                    let _ =
                        mutate_current_enrichment_state(&feed_gen, generation, &screener, |s| {
                            s.ingest_monthly_summary(deep_symbol.clone(), summary)
                        });
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
const INSIDER_FRESHNESS_INTERVAL_SECS: u64 = 24 * 60 * 60;
const INSIDER_RETRY_INTERVAL_SECS: u64 = 15 * 60;
const FINANCIAL_DCF_RETRY_INTERVAL_SECS: u64 = 15 * 60;
const EDGAR_SCAN_PAUSE_SECS: u64 = 5;

#[derive(Clone, Copy)]
enum BulkChartKind {
    Weekly,
    Hourly,
}

#[derive(Clone, Copy)]
struct ChartRequest {
    range: &'static str,
    interval: &'static str,
}

const BULK_CHART_REQUESTS: [(ChartRequest, BulkChartKind); 2] = [
    (
        ChartRequest {
            range: "5y",
            interval: "1wk",
        },
        BulkChartKind::Weekly,
    ),
    (
        ChartRequest {
            range: "1mo",
            interval: "1h",
        },
        BulkChartKind::Hourly,
    ),
];
const DETAIL_MONTHLY_CHART_REQUEST: ChartRequest = ChartRequest {
    range: "10y",
    interval: "1mo",
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum BulkChartAttemptOutcome {
    Complete,
    RateLimited,
    Cancelled,
}

struct EnrichmentJob {
    symbol: String,
    next_chart: usize,
    retry_round: usize,
}

impl EnrichmentJob {
    fn new(symbol: String) -> Self {
        Self {
            symbol,
            next_chart: 0,
            retry_round: 0,
        }
    }
}

struct DelayedEnrichmentJob {
    job: EnrichmentJob,
    ready_at: Instant,
}

#[derive(Default)]
struct DelayedEnrichmentQueue {
    jobs: HashMap<String, DelayedEnrichmentJob>,
}

impl DelayedEnrichmentQueue {
    fn schedule(&mut self, job: EnrichmentJob, now: Instant, delay: Duration) -> bool {
        use std::collections::hash_map::Entry;

        match self.jobs.entry(job.symbol.clone()) {
            Entry::Vacant(entry) => {
                entry.insert(DelayedEnrichmentJob {
                    job,
                    ready_at: now + delay,
                });
                true
            }
            Entry::Occupied(_) => false,
        }
    }

    fn take_due(&mut self, now: Instant) -> Option<EnrichmentJob> {
        let symbol = self
            .jobs
            .iter()
            .filter(|(_, delayed)| delayed.ready_at <= now)
            .min_by_key(|(_, delayed)| delayed.ready_at)
            .map(|(symbol, _)| symbol.clone())?;
        self.jobs.remove(&symbol).map(|delayed| delayed.job)
    }

    fn next_wait(&self, now: Instant) -> Duration {
        self.jobs
            .values()
            .map(|delayed| delayed.ready_at.saturating_duration_since(now))
            .min()
            .unwrap_or(Duration::from_millis(500))
            .min(Duration::from_millis(500))
    }

    fn len(&self) -> usize {
        self.jobs.len()
    }
}

fn take_ready_enrichment_job(
    receiver: &std::sync::mpsc::Receiver<String>,
    delayed: &mut DelayedEnrichmentQueue,
    now: Instant,
) -> Option<EnrichmentJob> {
    match receiver.try_recv() {
        Ok(symbol) => Some(EnrichmentJob::new(symbol)),
        Err(_) => delayed.take_due(now),
    }
}

fn run_bulk_chart_pass(
    start: usize,
    mut fetch: impl FnMut(ChartRequest, BulkChartKind) -> BulkChartAttemptOutcome,
) -> (usize, BulkChartAttemptOutcome) {
    for (index, (request, kind)) in BULK_CHART_REQUESTS.into_iter().enumerate().skip(start) {
        match fetch(request, kind) {
            BulkChartAttemptOutcome::Complete => {}
            other => return (index, other),
        }
    }
    (BULK_CHART_REQUESTS.len(), BulkChartAttemptOutcome::Complete)
}

#[cfg(test)]
fn for_each_bulk_chart_request(mut fetch: impl FnMut(ChartRequest, BulkChartKind) -> bool) -> bool {
    run_bulk_chart_pass(0, |request, kind| {
        if fetch(request, kind) {
            BulkChartAttemptOutcome::Complete
        } else {
            BulkChartAttemptOutcome::Cancelled
        }
    })
    .1 == BulkChartAttemptOutcome::Complete
}

#[derive(Default)]
struct InsiderRefreshSchedule {
    next_attempt: std::collections::HashMap<String, Instant>,
}

impl InsiderRefreshSchedule {
    fn is_due(&self, symbol: &str, now: Instant) -> bool {
        self.next_attempt
            .get(symbol)
            .is_none_or(|deadline| now >= *deadline)
    }

    fn record_result(
        &mut self,
        symbol: &str,
        now: Instant,
        result: &Result<Option<edgar::InsiderSummary>, String>,
    ) {
        let interval = if result.is_ok() {
            INSIDER_FRESHNESS_INTERVAL_SECS
        } else {
            INSIDER_RETRY_INTERVAL_SECS
        };
        self.next_attempt
            .insert(symbol.to_string(), now + Duration::from_secs(interval));
    }
}

fn financial_dcf_input_key(
    fund: &crate::engine::FundamentalSnapshot,
    price: Option<i64>,
) -> Vec<u8> {
    // Preserve every current and future fundamental field in the comparison.
    serde_json::to_vec(&(fund, price)).expect("financial fundamentals are serializable")
}

enum FinancialDcfAttempt {
    Succeeded(Vec<u8>),
    Failed { key: Vec<u8>, retry_at: Instant },
}

#[derive(Default)]
struct FinancialDcfSchedule {
    attempts: std::collections::HashMap<String, FinancialDcfAttempt>,
}

impl FinancialDcfSchedule {
    fn should_compute(&self, symbol: &str, key: &[u8], now: Instant, has_analysis: bool) -> bool {
        match self.attempts.get(symbol) {
            Some(FinancialDcfAttempt::Succeeded(previous)) => previous != key || !has_analysis,
            Some(FinancialDcfAttempt::Failed {
                key: previous,
                retry_at,
            }) => previous != key || now >= *retry_at,
            None => true,
        }
    }

    fn record_result(&mut self, symbol: &str, key: Vec<u8>, now: Instant, success: bool) {
        let attempt = if success {
            FinancialDcfAttempt::Succeeded(key)
        } else {
            FinancialDcfAttempt::Failed {
                key,
                retry_at: now + Duration::from_secs(FINANCIAL_DCF_RETRY_INTERVAL_SECS),
            }
        };
        self.attempts.insert(symbol.to_string(), attempt);
    }

    fn forget(&mut self, symbol: &str) {
        self.attempts.remove(symbol);
    }
}

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

fn chart_enrichment_retry_delay_ms(rate_limit_secs: u64, completed_round: usize) -> Option<u64> {
    if completed_round >= MAX_RETRY_ROUNDS {
        return None;
    }
    // Chart 429 may not update the quoteSummary session. Back off locally too.
    let chart_cooldown_secs = 90u64
        .saturating_mul(1u64 << completed_round.min(4))
        .min(15 * 60);
    Some(
        rate_limit_secs
            .saturating_add(1)
            .max(chart_cooldown_secs)
            .saturating_mul(1_000),
    )
}

fn wait_for_enrichment_retry(
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    wait_ms: u64,
) -> bool {
    let mut remaining = wait_ms;
    while remaining > 0 {
        if !generation_is_current(active_generation, generation) {
            return false;
        }
        let step = remaining.min(500);
        thread::sleep(Duration::from_millis(step));
        remaining -= step;
    }
    generation_is_current(active_generation, generation)
}

fn mutate_current_enrichment_state(
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    screener: &Mutex<crate::engine::ScreenerState>,
    mutate: impl FnOnce(&mut crate::engine::ScreenerState),
) -> bool {
    let mut state = screener.lock().unwrap();
    if !generation_is_current(active_generation, generation) {
        return false;
    }
    mutate(&mut state);
    true
}

fn mutate_current_feed_status(
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    feed_status: &Mutex<crate::state::FeedStatus>,
    mutate: impl FnOnce(&mut crate::state::FeedStatus),
) -> bool {
    let mut status = feed_status.lock().unwrap();
    if !generation_is_current(active_generation, generation) {
        return false;
    }
    mutate(&mut status);
    true
}

fn queue_visible_symbol_for_enrichment(
    sym: &str,
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    completed: &Mutex<HashSet<String>>,
    loaded: &AtomicUsize,
    feed_status: &Mutex<crate::state::FeedStatus>,
    enrichment_senders: &[std::sync::mpsc::Sender<String>],
    total: usize,
) -> bool {
    if !generation_is_current(active_generation, generation) {
        return false;
    }
    if !completed.lock().unwrap().insert(sym.to_string()) {
        return true;
    }
    let n = loaded.fetch_add(1, Ordering::Relaxed) + 1;
    if !mutate_current_feed_status(active_generation, generation, feed_status, |status| {
        status.symbols_loaded = status.symbols_loaded.max(n.min(total));
    }) {
        return false;
    }
    // A symbol enters this finite queue only once per feed generation.
    let worker = (n - 1) % enrichment_senders.len();
    let _ = enrichment_senders[worker].send(sym.to_string());
    true
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
    use std::time::{Duration, Instant};

    #[test]
    fn late_visible_symbol_reaches_enrichment_after_worker_consumes_first_symbol() {
        use std::collections::HashSet;
        use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
        use std::sync::{mpsc, Mutex};

        let (sender, receiver) = mpsc::channel();
        let completed = Mutex::new(HashSet::new());
        let loaded = AtomicUsize::new(0);
        let active = AtomicU64::new(1);
        let status = Mutex::new(crate::state::FeedStatus::default());
        let (first_done_sender, first_done_receiver) = mpsc::channel();
        let worker = std::thread::spawn(move || {
            let first = receiver.recv_timeout(Duration::from_secs(1)).unwrap();
            first_done_sender.send(first).unwrap();
            receiver.recv_timeout(Duration::from_secs(1)).unwrap()
        });

        super::queue_visible_symbol_for_enrichment(
            "EARLY",
            &active,
            1,
            &completed,
            &loaded,
            &status,
            &[sender.clone()],
            2,
        );
        assert_eq!(
            first_done_receiver
                .recv_timeout(Duration::from_secs(1))
                .unwrap(),
            "EARLY"
        );
        super::queue_visible_symbol_for_enrichment(
            "EARLY",
            &active,
            1,
            &completed,
            &loaded,
            &status,
            &[sender.clone()],
            2,
        );
        super::queue_visible_symbol_for_enrichment(
            "LATE",
            &active,
            1,
            &completed,
            &loaded,
            &status,
            &[sender],
            2,
        );

        assert_eq!(worker.join().unwrap(), "LATE");
        assert_eq!(loaded.load(Ordering::Relaxed), 2);
        assert_eq!(status.lock().unwrap().symbols_loaded, 2);
        assert_eq!(completed.lock().unwrap().len(), 2);
    }

    #[test]
    fn chart_rate_limit_retries_are_bounded_and_respect_shared_cooldown() {
        assert_eq!(
            super::chart_enrichment_retry_delay_ms(120, 0),
            Some(121_000)
        );
        assert_eq!(super::chart_enrichment_retry_delay_ms(0, 0), Some(90_000));
        assert_eq!(super::chart_enrichment_retry_delay_ms(0, 1), Some(180_000));
        assert_eq!(
            super::chart_enrichment_retry_delay_ms(0, super::MAX_RETRY_ROUNDS),
            None
        );
    }

    #[test]
    fn chart_retry_resumes_at_failed_request() {
        let mut requests = Vec::new();
        let (next, outcome) = super::run_bulk_chart_pass(0, |request, _| {
            requests.push((request.range, request.interval));
            if request.interval == "1h" {
                super::BulkChartAttemptOutcome::RateLimited
            } else {
                super::BulkChartAttemptOutcome::Complete
            }
        });
        assert_eq!(outcome, super::BulkChartAttemptOutcome::RateLimited);
        assert_eq!(next, 1);
        assert_eq!(requests, [("5y", "1wk"), ("1mo", "1h")]);

        requests.clear();
        let (next, outcome) = super::run_bulk_chart_pass(next, |request, _| {
            requests.push((request.range, request.interval));
            super::BulkChartAttemptOutcome::Complete
        });
        assert_eq!(outcome, super::BulkChartAttemptOutcome::Complete);
        assert_eq!(next, 2);
        assert_eq!(requests, [("1mo", "1h")]);
    }

    #[test]
    fn delayed_chart_retry_does_not_block_ready_symbol() {
        use std::sync::mpsc;

        let start = Instant::now();
        let (sender, receiver) = mpsc::channel();
        let mut delayed = super::DelayedEnrichmentQueue::default();
        let failed = super::EnrichmentJob {
            symbol: "EARLY".into(),
            next_chart: 1,
            retry_round: 1,
        };
        assert!(delayed.schedule(failed, start, Duration::from_secs(90)));
        sender.send("READY".into()).unwrap();

        let ready = super::take_ready_enrichment_job(&receiver, &mut delayed, start).unwrap();
        assert_eq!(ready.symbol, "READY");
        assert_eq!(ready.next_chart, 0);
        assert_eq!(ready.retry_round, 0);
        assert_eq!(delayed.len(), 1);
        assert!(super::take_ready_enrichment_job(
            &receiver,
            &mut delayed,
            start + Duration::from_secs(89)
        )
        .is_none());

        let retried = super::take_ready_enrichment_job(
            &receiver,
            &mut delayed,
            start + Duration::from_secs(90),
        )
        .unwrap();
        assert_eq!(retried.symbol, "EARLY");
        assert_eq!(retried.next_chart, 1);
        assert_eq!(retried.retry_round, 1);
    }

    #[test]
    fn delayed_chart_retry_keeps_only_one_job_per_symbol() {
        let start = Instant::now();
        let mut delayed = super::DelayedEnrichmentQueue::default();
        let first = super::EnrichmentJob {
            symbol: "AAPL".into(),
            next_chart: 1,
            retry_round: 1,
        };
        assert!(delayed.schedule(first, start, Duration::from_secs(90)));
        let duplicate = super::EnrichmentJob {
            symbol: "AAPL".into(),
            next_chart: 0,
            retry_round: 0,
        };
        assert!(!delayed.schedule(duplicate, start, Duration::from_secs(1)));
        assert_eq!(delayed.len(), 1);
        assert!(delayed.take_due(start + Duration::from_secs(1)).is_none());
        assert_eq!(
            delayed
                .take_due(start + Duration::from_secs(90))
                .unwrap()
                .next_chart,
            1
        );
    }

    #[test]
    fn stale_enrichment_cannot_write_after_generation_change() {
        use std::sync::atomic::{AtomicU64, Ordering};
        use std::sync::Mutex;

        let active = AtomicU64::new(4);
        let screener = Mutex::new(ScreenerState::new());
        assert!(super::mutate_current_enrichment_state(
            &active,
            4,
            &screener,
            |state| state.scoring_model = "current".into()
        ));
        active.store(5, Ordering::SeqCst);
        assert!(!super::mutate_current_enrichment_state(
            &active,
            4,
            &screener,
            |state| state.scoring_model = "stale".into()
        ));
        assert_eq!(screener.lock().unwrap().scoring_model, "current");
    }

    #[test]
    fn stale_bulk_quote_and_chart_cannot_repopulate_new_profile() {
        use std::sync::atomic::{AtomicU64, Ordering};
        use std::sync::Mutex;

        let active = AtomicU64::new(4);
        let screener = Mutex::new(ScreenerState::new());
        let old_quote = FetchResult {
            symbol: "OLD".into(),
            snapshot: Some(MarketSnapshot {
                symbol: "OLD".into(),
                company_name: None,
                profitable: true,
                market_price_cents: 10_000,
                intrinsic_value_cents: 0,
                previous_close_cents: 0,
                next_earnings_epoch: None,
            }),
            signal: None,
            fundamentals: None,
        };
        active.store(5, Ordering::SeqCst);
        screener.lock().unwrap().ingest_snapshot(MarketSnapshot {
            symbol: "NEW".into(),
            company_name: None,
            profitable: true,
            market_price_cents: 20_000,
            intrinsic_value_cents: 0,
            previous_close_cents: 0,
            next_earnings_epoch: None,
        });
        assert_eq!(
            super::apply_refresh_quote_if_current(&active, 4, &screener, old_quote, false, false),
            None
        );
        let old_chart = vec![crate::engine::HistoricalCandle {
            epoch_seconds: 1,
            open_cents: 10_000,
            high_cents: 10_000,
            low_cents: 10_000,
            close_cents: 10_000,
            volume: 100,
        }];
        assert_eq!(
            super::apply_refresh_chart_if_current(
                &active,
                4,
                &screener,
                "OLD",
                old_chart,
                RefreshOutcome::default(),
            ),
            None
        );
        let state = screener.lock().unwrap();
        assert_eq!(state.snapshots.len(), 1);
        assert!(state.snapshots.contains_key("NEW"));
        assert!(!state.chart_summaries.contains_key("OLD"));
    }

    #[test]
    fn stale_sec_result_and_feed_error_cannot_enter_new_profile() {
        use std::sync::atomic::{AtomicU64, Ordering};
        use std::sync::Mutex;

        let active = AtomicU64::new(7);
        let screener = Mutex::new(ScreenerState::new());
        let status = Mutex::new(crate::state::FeedStatus::default());
        active.store(8, Ordering::SeqCst);
        assert!(!super::mutate_current_enrichment_state(
            &active,
            7,
            &screener,
            |state| state.ingest_insider(
                "OLD".into(),
                crate::engine::InsiderData {
                    net_shares_90d: 100,
                    buy_count: 1,
                    sell_count: 0,
                }
            )
        ));
        assert!(!super::mutate_current_feed_status(
            &active,
            7,
            &status,
            |status| {
                status.last_error = Some("old SEC error".into());
            }
        ));
        assert!(screener.lock().unwrap().insider_data.is_empty());
        assert!(status.lock().unwrap().last_error.is_none());
    }

    #[test]
    fn concurrent_profile_adoptions_are_serialized() {
        use std::sync::mpsc;
        use std::sync::{Arc, Mutex};

        let gate = Arc::new(Mutex::new(()));
        let (first_entered_tx, first_entered_rx) = mpsc::channel();
        let (release_first_tx, release_first_rx) = mpsc::channel();
        let first_gate = Arc::clone(&gate);
        let first = std::thread::spawn(move || {
            super::with_profile_apply_gate(&first_gate, || {
                first_entered_tx.send(()).unwrap();
                release_first_rx.recv().unwrap();
            });
        });
        first_entered_rx
            .recv_timeout(Duration::from_secs(1))
            .unwrap();

        let (second_started_tx, second_started_rx) = mpsc::channel();
        let (second_entered_tx, second_entered_rx) = mpsc::channel();
        let second_gate = Arc::clone(&gate);
        let second = std::thread::spawn(move || {
            second_started_tx.send(()).unwrap();
            super::with_profile_apply_gate(&second_gate, || second_entered_tx.send(()).unwrap());
        });
        second_started_rx
            .recv_timeout(Duration::from_secs(1))
            .unwrap();
        assert!(second_entered_rx
            .recv_timeout(Duration::from_millis(50))
            .is_err());
        release_first_tx.send(()).unwrap();
        first.join().unwrap();
        second_entered_rx
            .recv_timeout(Duration::from_secs(1))
            .unwrap();
        second.join().unwrap();
    }

    #[test]
    fn old_demand_worker_cannot_block_or_release_new_generation_owner() {
        use std::collections::HashSet;
        use std::sync::{Arc, Mutex};

        let inflight = Arc::new(Mutex::new(HashSet::new()));
        assert!(super::claim_demand_valuation(&inflight, 4, "COF"));
        assert!(super::claim_demand_valuation(&inflight, 5, "COF"));
        let old = super::ValuationInflightGuard {
            symbol: "COF".into(),
            generation: 4,
            inflight: Arc::clone(&inflight),
        };
        drop(old);
        assert!(!super::claim_demand_valuation(&inflight, 5, "COF"));
        assert_eq!(inflight.lock().unwrap().len(), 1);
    }

    #[test]
    fn stale_demand_failure_cannot_mark_new_profile_or_newer_inputs() {
        use std::sync::atomic::AtomicU64;
        use std::sync::{Arc, Mutex};

        let active = AtomicU64::new(5);
        let mut state = ScreenerState::new();
        let fund = FundamentalSnapshot {
            symbol: "NEW".into(),
            sector_name: Some("Financial Services".into()),
            ..Default::default()
        };
        state.ingest_fundamentals(fund.clone());
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NEW".into(),
            company_name: None,
            profitable: true,
            market_price_cents: 15_000,
            intrinsic_value_cents: 0,
            previous_close_cents: 0,
            next_earnings_epoch: None,
        });
        let old_key = super::financial_dcf_input_key(&fund, Some(15_000));
        let old_revision = super::dcf_revision_key(&state, "NEW");
        let screener = Arc::new(Mutex::new(state));
        super::record_demand_valuation_failure(
            "NEW",
            &screener,
            &active,
            4,
            &old_key,
            &old_revision,
            "old failure",
        );
        assert!(screener.lock().unwrap().valuation_errors.is_empty());
        screener
            .lock()
            .unwrap()
            .snapshots
            .get_mut("NEW")
            .unwrap()
            .market_price_cents = 16_000;
        super::record_demand_valuation_failure(
            "NEW",
            &screener,
            &active,
            5,
            &old_key,
            &old_revision,
            "old input failure",
        );
        assert!(screener.lock().unwrap().valuation_errors.is_empty());
        let current_key = super::financial_dcf_input_key(&fund, Some(16_000));
        screener.lock().unwrap().dcf_values.insert("NEW".into(), 42);
        super::record_demand_valuation_failure(
            "NEW",
            &screener,
            &active,
            5,
            &current_key,
            &old_revision,
            "old DCF failure",
        );
        assert_eq!(screener.lock().unwrap().dcf_values.get("NEW"), Some(&42));
        assert!(screener.lock().unwrap().valuation_errors.is_empty());
        let current_revision = super::dcf_revision_key(&screener.lock().unwrap(), "NEW");
        super::record_demand_valuation_failure(
            "NEW",
            &screener,
            &active,
            5,
            &current_key,
            &current_revision,
            super::DEMAND_INPUTS_CHANGED,
        );
        assert_eq!(screener.lock().unwrap().dcf_values.get("NEW"), Some(&42));
        assert!(screener.lock().unwrap().valuation_errors.is_empty());
        super::record_demand_valuation_failure(
            "NEW",
            &screener,
            &active,
            5,
            &current_key,
            &current_revision,
            "current failure",
        );
        assert_eq!(
            screener
                .lock()
                .unwrap()
                .valuation_errors
                .get("NEW")
                .map(String::as_str),
            Some("current failure")
        );
    }

    #[test]
    fn financial_refresh_failure_keeps_its_reason_after_own_fundamental_write() {
        use std::sync::atomic::AtomicU64;
        use std::sync::{Arc, Mutex};

        let active = AtomicU64::new(7);
        let fund = FundamentalSnapshot {
            symbol: "COF".into(),
            sector_name: Some("Financial Services".into()),
            shares_outstanding: Some(10_000),
            book_value_per_share_cents: Some(10_000),
            return_on_equity_bps: Some(900),
            retention_bps: None,
            ..Default::default()
        };
        let mut state = ScreenerState::new();
        state.ingest_fundamentals(fund.clone());
        let initial_key = super::financial_dcf_input_key(&fund, None);
        let mut failure_context = super::DemandFailureContext::capture(&state, "COF");
        let screener = Arc::new(Mutex::new(state));
        let cik_cache = Arc::new(Mutex::new(None));
        let mut refreshed = fund;
        refreshed.book_value_per_share_cents = Some(11_000);

        let error = compute_demand_valuation_once_with_financial_refresh(
            "COF",
            &screener,
            &cik_cache,
            &None,
            &active,
            7,
            &mut failure_context,
            |_| Ok(Some(refreshed)),
        )
        .unwrap_err();
        assert!(error.contains("retention/payout"));
        super::record_demand_valuation_failure(
            "COF",
            &screener,
            &active,
            7,
            &failure_context.input_key,
            &failure_context.dcf_revision,
            &error,
        );
        assert_eq!(
            screener
                .lock()
                .unwrap()
                .valuation_errors
                .get("COF")
                .map(String::as_str),
            Some(error.as_str())
        );
        assert_ne!(failure_context.input_key, initial_key);
    }

    #[test]
    fn financial_refresh_failure_cannot_mark_later_unrelated_inputs() {
        use std::sync::atomic::AtomicU64;
        use std::sync::{Arc, Mutex};

        let active = AtomicU64::new(7);
        let fund = FundamentalSnapshot {
            symbol: "COF".into(),
            sector_name: Some("Financial Services".into()),
            shares_outstanding: Some(10_000),
            book_value_per_share_cents: Some(10_000),
            return_on_equity_bps: Some(900),
            retention_bps: None,
            ..Default::default()
        };
        let mut state = ScreenerState::new();
        state.ingest_fundamentals(fund.clone());
        let mut failure_context = super::DemandFailureContext::capture(&state, "COF");
        let screener = Arc::new(Mutex::new(state));
        let cik_cache = Arc::new(Mutex::new(None));
        let mut refreshed = fund;
        refreshed.book_value_per_share_cents = Some(11_000);
        let error = compute_demand_valuation_once_with_financial_refresh(
            "COF",
            &screener,
            &cik_cache,
            &None,
            &active,
            7,
            &mut failure_context,
            |_| Ok(Some(refreshed)),
        )
        .unwrap_err();
        screener
            .lock()
            .unwrap()
            .fundamentals
            .get_mut("COF")
            .unwrap()
            .book_value_per_share_cents = Some(12_000);

        super::record_demand_valuation_failure(
            "COF",
            &screener,
            &active,
            7,
            &failure_context.input_key,
            &failure_context.dcf_revision,
            &error,
        );
        assert!(screener.lock().unwrap().valuation_errors.is_empty());
    }

    #[test]
    fn stale_financial_refresh_cannot_publish_after_profile_switch() {
        use std::sync::atomic::{AtomicU64, Ordering};
        use std::sync::{Arc, Mutex};

        let active = AtomicU64::new(4);
        let mut state = ScreenerState::new();
        state.ingest_fundamentals(FundamentalSnapshot {
            symbol: "COF".into(),
            sector_name: Some("Financial Services".into()),
            shares_outstanding: Some(10_000),
            book_value_per_share_cents: Some(10_000),
            return_on_equity_bps: Some(900),
            retention_bps: None,
            ..Default::default()
        });
        let screener = Arc::new(Mutex::new(state));
        let cik_cache = Arc::new(Mutex::new(None));
        let mut failure_context =
            super::DemandFailureContext::capture(&screener.lock().unwrap(), "COF");
        let result = compute_demand_valuation_once_with_financial_refresh(
            "COF",
            &screener,
            &cik_cache,
            &None,
            &active,
            4,
            &mut failure_context,
            |_| {
                active.store(5, Ordering::SeqCst);
                let mut state = screener.lock().unwrap();
                state.clear_universe();
                state.ingest_fundamentals(FundamentalSnapshot {
                    symbol: "NEW".into(),
                    ..Default::default()
                });
                Ok(Some(FundamentalSnapshot {
                    symbol: "COF".into(),
                    retention_bps: Some(7_000),
                    ..Default::default()
                }))
            },
        );
        assert!(result.unwrap_err().contains("profile changed"));
        let state = screener.lock().unwrap();
        assert!(!state.fundamentals.contains_key("COF"));
        assert!(state.fundamentals.contains_key("NEW"));
        assert!(!state.dcf_analyses.contains_key("COF"));
    }

    #[test]
    fn financial_refresh_cannot_replace_newer_price_or_fundamentals_in_same_generation() {
        use std::sync::atomic::AtomicU64;
        use std::sync::{Arc, Mutex};

        let active = AtomicU64::new(4);
        let mut state = ScreenerState::new();
        let stale_fund = FundamentalSnapshot {
            symbol: "COF".into(),
            sector_name: Some("Financial Services".into()),
            shares_outstanding: Some(10_000),
            book_value_per_share_cents: Some(10_000),
            return_on_equity_bps: Some(900),
            retention_bps: None,
            ..Default::default()
        };
        state.ingest_fundamentals(stale_fund.clone());
        state.ingest_snapshot(MarketSnapshot {
            symbol: "COF".into(),
            company_name: None,
            profitable: true,
            market_price_cents: 15_000,
            intrinsic_value_cents: 0,
            previous_close_cents: 0,
            next_earnings_epoch: None,
        });
        let screener = Arc::new(Mutex::new(state));
        let cik_cache = Arc::new(Mutex::new(None));
        let mut refreshed = stale_fund;
        refreshed.retention_bps = Some(7_000);
        let mut failure_context =
            super::DemandFailureContext::capture(&screener.lock().unwrap(), "COF");
        let result = compute_demand_valuation_once_with_financial_refresh(
            "COF",
            &screener,
            &cik_cache,
            &None,
            &active,
            4,
            &mut failure_context,
            |_| {
                screener
                    .lock()
                    .unwrap()
                    .snapshots
                    .get_mut("COF")
                    .unwrap()
                    .market_price_cents = 16_000;
                Ok(Some(refreshed))
            },
        );
        assert!(result.unwrap_err().contains("inputs changed"));
        let state = screener.lock().unwrap();
        assert_eq!(state.fundamentals["COF"].retention_bps, None);
        assert_eq!(state.snapshots["COF"].market_price_cents, 16_000);
        assert!(!state.dcf_analyses.contains_key("COF"));
    }

    #[test]
    fn demand_input_identity_changes_with_price_and_fundamentals() {
        let mut state = ScreenerState::new();
        let fund = FundamentalSnapshot {
            symbol: "COF".into(),
            sector_name: Some("Financial Services".into()),
            retention_bps: Some(7_000),
            ..Default::default()
        };
        state.ingest_fundamentals(fund.clone());
        state.ingest_snapshot(MarketSnapshot {
            symbol: "COF".into(),
            company_name: None,
            profitable: true,
            market_price_cents: 15_000,
            intrinsic_value_cents: 0,
            previous_close_cents: 0,
            next_earnings_epoch: None,
        });
        assert!(super::demand_inputs_are_current(
            &state,
            "COF",
            &fund,
            Some(15_000)
        ));
        state.snapshots.get_mut("COF").unwrap().market_price_cents = 16_000;
        assert!(!super::demand_inputs_are_current(
            &state,
            "COF",
            &fund,
            Some(15_000)
        ));
        state.snapshots.get_mut("COF").unwrap().market_price_cents = 15_000;
        state.fundamentals.get_mut("COF").unwrap().retention_bps = Some(6_000);
        assert!(!super::demand_inputs_are_current(
            &state,
            "COF",
            &fund,
            Some(15_000)
        ));
    }

    #[test]
    fn demand_publication_refuses_a_replaced_dcf_analysis() {
        let mut state = ScreenerState::new();
        let fund = FundamentalSnapshot {
            symbol: "COF".into(),
            sector_name: Some("Financial Services".into()),
            shares_outstanding: Some(10_000),
            book_value_per_share_cents: Some(10_000),
            return_on_equity_bps: Some(900),
            retention_bps: Some(7_000),
            ..Default::default()
        };
        state.ingest_fundamentals(fund.clone());
        let captured_revision = super::dcf_revision_key(&state, "COF");
        assert!(super::demand_publication_inputs_are_current(
            &state,
            "COF",
            &fund,
            None,
            &captured_revision,
        ));

        let mut replacement = state.dcf_analyses["COF"].clone();
        replacement.base_intrinsic_value_cents += 1;
        state.dcf_analyses.insert("COF".into(), replacement);
        assert!(!super::demand_publication_inputs_are_current(
            &state,
            "COF",
            &fund,
            None,
            &captured_revision,
        ));
    }

    #[test]
    fn demand_publication_refuses_a_newer_operating_envelope_without_fcff() {
        let mut state = ScreenerState::new();
        let fund = FundamentalSnapshot {
            symbol: "TECH".into(),
            sector_name: Some("Technology".into()),
            ..Default::default()
        };
        state.ingest_fundamentals(fund.clone());
        let captured_revision = super::dcf_revision_key(&state, "TECH");
        let market_params = crate::dcf_model::MarketParams::default_usd();
        let envelope = crate::operating_valuation_runtime::route_runtime_valuation(
            crate::operating_valuation_runtime::RuntimeValuationInput {
                business_class: crate::dcf_model::BusinessClass::OperatingNonFinancial,
                fundamentals: &fund,
                fcff_analysis: None,
                fcff_failure: Some("missing SEC FCFF"),
                forward_evidence: Err(
                    crate::operating_valuation_runtime::ForwardSourceFailure::NotAttempted,
                ),
                market_params: &market_params,
                as_of_epoch_day: 20_000,
                market_price_cents: None,
            },
        );
        state.ingest_operating_valuation("TECH".into(), None, envelope);
        assert!(!super::demand_publication_inputs_are_current(
            &state,
            "TECH",
            &fund,
            None,
            &captured_revision,
        ));
    }

    #[test]
    fn demand_publication_refuses_a_newer_failure_without_dcf() {
        let mut state = ScreenerState::new();
        let fund = FundamentalSnapshot {
            symbol: "TECH".into(),
            sector_name: Some("Technology".into()),
            ..Default::default()
        };
        state.ingest_fundamentals(fund.clone());
        let captured_revision = super::dcf_revision_key(&state, "TECH");
        state.set_valuation_error("TECH".into(), "newer failure".into());
        assert!(!super::demand_publication_inputs_are_current(
            &state,
            "TECH",
            &fund,
            None,
            &captured_revision,
        ));
    }

    #[test]
    fn old_profile_history_insert_is_refused_after_switch() {
        use std::sync::atomic::AtomicU64;
        use std::sync::Mutex;

        let gate = Mutex::new(());
        let active = AtomicU64::new(9);
        let mut inserted = false;
        let result = super::with_current_profile_generation(&gate, &active, 8, || {
            inserted = true;
        });
        assert!(result.is_none());
        assert!(!inserted);
        assert!(
            super::with_current_profile_generation(&gate, &active, 9, || {
                inserted = true;
            })
            .is_some()
        );
        assert!(inserted);
    }

    #[test]
    fn bulk_charts_request_only_weekly_and_hourly_while_detail_keeps_monthly() {
        let mut requests = Vec::new();
        let completed = super::for_each_bulk_chart_request(|request, _kind| {
            requests.push((request.range, request.interval));
            true
        });

        assert!(completed);
        assert_eq!(requests, [("5y", "1wk"), ("1mo", "1h")]);
        assert_eq!(
            (
                super::DETAIL_MONTHLY_CHART_REQUEST.range,
                super::DETAIL_MONTHLY_CHART_REQUEST.interval
            ),
            ("10y", "1mo")
        );
    }

    #[test]
    fn insider_success_is_reused_until_freshness_expires() {
        let mut schedule = super::InsiderRefreshSchedule::default();
        let start = Instant::now();
        let empty_activity = Ok(Some(crate::edgar::InsiderSummary {
            net_shares_90d: 0,
            buy_count: 0,
            sell_count: 0,
            filing_count: 0,
        }));
        let mut requests = 0;

        for now in [
            start,
            start + Duration::from_secs(1),
            start + Duration::from_secs(super::INSIDER_FRESHNESS_INTERVAL_SECS - 1),
            start + Duration::from_secs(super::INSIDER_FRESHNESS_INTERVAL_SECS),
        ] {
            if schedule.is_due("AAPL", now) {
                requests += 1;
                schedule.record_result("AAPL", now, &empty_activity);
            }
        }

        assert_eq!(requests, 2);
        assert!(schedule.is_due("MSFT", start));
    }

    #[test]
    fn insider_failure_retries_after_backoff_and_empty_payload_stays_fresh() {
        let mut schedule = super::InsiderRefreshSchedule::default();
        let start = Instant::now();

        schedule.record_result("AAPL", start, &Err("SEC unavailable".into()));
        assert!(!schedule.is_due("AAPL", start + Duration::from_secs(1)));
        assert!(!schedule.is_due(
            "AAPL",
            start + Duration::from_secs(super::INSIDER_RETRY_INTERVAL_SECS - 1)
        ));
        assert!(schedule.is_due(
            "AAPL",
            start + Duration::from_secs(super::INSIDER_RETRY_INTERVAL_SECS)
        ));

        schedule.record_result("AAPL", start, &Ok(None));
        assert!(!schedule.is_due("AAPL", start + Duration::from_secs(1)));
        assert!(!schedule.is_due(
            "AAPL",
            start + Duration::from_secs(super::INSIDER_RETRY_INTERVAL_SECS)
        ));
        assert!(schedule.is_due(
            "AAPL",
            start + Duration::from_secs(super::INSIDER_FRESHNESS_INTERVAL_SECS)
        ));
    }

    #[test]
    fn financial_dcf_skips_identical_inputs_and_recomputes_changed_inputs() {
        let start = Instant::now();
        let mut schedule = super::FinancialDcfSchedule::default();
        let mut fund = FundamentalSnapshot {
            symbol: "COF".into(),
            return_on_equity_bps: Some(900),
            retention_bps: Some(8_000),
            ..Default::default()
        };
        let first = super::financial_dcf_input_key(&fund, Some(15_000));
        assert!(schedule.should_compute("COF", &first, start, false));
        schedule.record_result("COF", first.clone(), start, true);
        assert!(!schedule.should_compute("COF", &first, start + Duration::from_secs(60), true));
        assert!(schedule.should_compute("COF", &first, start + Duration::from_secs(60), false));

        let new_price = super::financial_dcf_input_key(&fund, Some(16_000));
        assert!(schedule.should_compute("COF", &new_price, start, true));
        fund.retention_bps = Some(7_000);
        let new_retention = super::financial_dcf_input_key(&fund, Some(15_000));
        assert!(schedule.should_compute("COF", &new_retention, start, true));
    }

    #[test]
    fn financial_dcf_retries_failed_input_after_backoff_or_input_change() {
        let start = Instant::now();
        let mut schedule = super::FinancialDcfSchedule::default();
        let mut fund = FundamentalSnapshot {
            symbol: "COF".into(),
            ..Default::default()
        };
        let incomplete = super::financial_dcf_input_key(&fund, Some(15_000));
        schedule.record_result("COF", incomplete.clone(), start, false);
        assert!(!schedule.should_compute(
            "COF",
            &incomplete,
            start + Duration::from_secs(1),
            false
        ));
        assert!(schedule.should_compute(
            "COF",
            &incomplete,
            start + Duration::from_secs(super::FINANCIAL_DCF_RETRY_INTERVAL_SECS),
            false
        ));

        fund.retention_bps = Some(8_000);
        let enriched = super::financial_dcf_input_key(&fund, Some(15_000));
        assert!(schedule.should_compute("COF", &enriched, start + Duration::from_secs(1), false));
        schedule.forget("COF");
        assert!(schedule.should_compute("COF", &incomplete, start + Duration::from_secs(1), false));
    }

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
        let active = std::sync::atomic::AtomicU64::new(1);
        let mut refresh_calls = 0;
        let mut failure_context =
            super::DemandFailureContext::capture(&screener.lock().unwrap(), "COF");
        compute_demand_valuation_once_with_financial_refresh(
            "COF",
            &screener,
            &cik_cache,
            &None,
            &active,
            1,
            &mut failure_context,
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
        assert_eq!(after.dcf_value_cents, Some(17_881));
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

fn apply_refresh_quote_if_current(
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    screener: &Mutex<crate::engine::ScreenerState>,
    result: crate::fetcher::FetchResult,
    crypto: bool,
    etf: bool,
) -> Option<RefreshOutcome> {
    let mut outcome = RefreshOutcome::default();
    mutate_current_enrichment_state(active_generation, generation, screener, |state| {
        outcome = ingest_fetch_result(state, result, crypto, etf);
    })
    .then_some(outcome)
}

fn apply_refresh_chart_if_current(
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    screener: &Mutex<crate::engine::ScreenerState>,
    sym: &str,
    candles: Vec<HistoricalCandle>,
    mut outcome: RefreshOutcome,
) -> Option<RefreshOutcome> {
    let summary = compute_chart_summary(&candles);
    let crypto = is_crypto(sym);
    let etf = is_etf(sym);
    mutate_current_enrichment_state(active_generation, generation, screener, |state| {
        let Some(summary) = summary else { return };
        let close = summary.latest_close_cents;
        let already = state.snapshots.contains_key(sym);
        state.ingest_chart_summary(sym.to_string(), summary);
        state.ingest_daily_candles(sym.to_string(), candles);
        if close <= 0 {
            return;
        }
        if already {
            let needs_price = state
                .snapshots
                .get(sym)
                .map(|x| x.market_price_cents <= 0)
                .unwrap_or(true);
            if needs_price {
                use crate::engine::MarketSnapshot;
                let prev = state.snapshots.get(sym).cloned();
                state.ingest_partial_snapshot(MarketSnapshot {
                    symbol: sym.to_string(),
                    company_name: prev.as_ref().and_then(|x| x.company_name.clone()),
                    profitable: prev.as_ref().map(|x| x.profitable).unwrap_or(crypto || etf),
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
            use crate::engine::{FundamentalSnapshot, MarketSnapshot};
            state.ingest_partial_snapshot(MarketSnapshot {
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
                state.ingest_fundamentals(FundamentalSnapshot {
                    symbol: sym.to_string(),
                    sector_name: sector,
                    ..Default::default()
                });
            }
            outcome.visible = true;
        }
    })
    .then_some(outcome)
}

/// Fetch one symbol progressively: price/name makes it visible immediately, while
/// quoteSummary completeness controls only whether it remains in the retry set.
fn refresh_one_symbol(
    client: &YahooClient,
    screener: &std::sync::Mutex<crate::engine::ScreenerState>,
    feed_status: &std::sync::Mutex<crate::state::FeedStatus>,
    active_generation: &std::sync::atomic::AtomicU64,
    generation: u64,
    sym: &str,
) -> Option<RefreshOutcome> {
    let crypto = is_crypto(sym);
    let etf = is_etf(sym);
    let mut outcome = RefreshOutcome::default();

    match client.fetch_symbol(sym) {
        Ok(result) => {
            outcome = apply_refresh_quote_if_current(
                active_generation,
                generation,
                screener,
                result,
                crypto,
                etf,
            )?;
            if outcome.enriched {
                if !mutate_current_feed_status(active_generation, generation, feed_status, |s| {
                    s.last_error = None;
                }) {
                    return None;
                }
            }
        }
        Err(e) => {
            let msg = e.to_string();
            if msg.contains("429") {
                if !mutate_current_feed_status(active_generation, generation, feed_status, |s| {
                    s.last_error = Some(
                        "Yahoo rate-limited — retrying until full quote columns are available"
                            .into(),
                    );
                }) {
                    return None;
                }
            } else if !(msg.contains("404") || msg.contains("401") || msg.contains("403")) {
                if !mutate_current_feed_status(active_generation, generation, feed_status, |s| {
                    s.last_error = Some(format!("{sym}: {e}"));
                }) {
                    return None;
                }
            }
        }
    }

    if !generation_is_current(active_generation, generation) {
        return None;
    }

    // Year chart for spark/technicals — only attach to symbols already in the list,
    // or create crypto/ETF rows that can stand without analyst columns.
    if let Ok(candles) = client.fetch_candles(sym, "1y", "1d") {
        outcome = apply_refresh_chart_if_current(
            active_generation,
            generation,
            screener,
            sym,
            candles,
            outcome,
        )?;
    }

    generation_is_current(active_generation, generation).then_some(outcome)
}

#[tauri::command]
pub fn start_feed(state: State<AppState>) -> Result<(), String> {
    with_profile_apply_gate(&state.profile_apply_gate, || {
        let status = state.feed_status.lock().unwrap();
        if status.running {
            return Ok(());
        }
        drop(status);
        // Cold start with the already-selected (or default) universe.
        let profile = state.active_profile.lock().unwrap().clone();
        apply_universe_profile_serial(&profile, &state)
    })
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
    let (enrichment_senders, enrichment_receivers): (Vec<_>, Vec<_>) = (0..ENRICHMENT_CONCURRENCY)
        .map(|_| std::sync::mpsc::channel::<String>())
        .unzip();
    let enrichment_senders = Arc::new(enrichment_senders);

    // ── Android-style refresh coordinator ───────────────────────────────────
    {
        let symbols = Arc::clone(&symbols);
        let client = Arc::clone(&shared_client);
        let screener = Arc::clone(&state.screener);
        let feed_status = Arc::clone(&state.feed_status);
        let feed_log = Arc::clone(&state.feed_log);
        let loaded = Arc::clone(&loaded);
        let completed = Arc::clone(&completed);
        let enrichment_senders = Arc::clone(&enrichment_senders);
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
                        if !mutate_current_feed_status(&feed_gen, generation, &feed_status, |s| {
                            s.last_error = Some(format_incomplete_retry_status(
                                round,
                                MAX_RETRY_ROUNDS,
                                &pending,
                            ));
                        }) {
                            return;
                        }
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
                        let enrichment_senders = Arc::clone(&enrichment_senders);
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
                                    let Some(outcome) = refresh_one_symbol(
                                        &client,
                                        &screener,
                                        &feed_status,
                                        &feed_gen,
                                        generation,
                                        sym,
                                    ) else {
                                        break;
                                    };
                                    if outcome.visible {
                                        if !queue_visible_symbol_for_enrichment(
                                            sym,
                                            &feed_gen,
                                            generation,
                                            &completed,
                                            &loaded,
                                            &feed_status,
                                            &enrichment_senders,
                                            total,
                                        ) {
                                            break;
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
                    if !mutate_current_feed_status(&feed_gen, generation, &feed_status, |s| {
                        s.last_error = Some(format_terminal_incomplete_status(&pending));
                    }) {
                        return;
                    }
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
                        let enrichment_senders = Arc::clone(&enrichment_senders);
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
                                    let Some(outcome) = refresh_one_symbol(
                                        &client,
                                        &screener,
                                        &feed_status,
                                        &feed_gen,
                                        generation,
                                        sym,
                                    ) else {
                                        break;
                                    };
                                    if outcome.visible {
                                        if !queue_visible_symbol_for_enrichment(
                                            sym,
                                            &feed_gen,
                                            generation,
                                            &completed,
                                            &loaded,
                                            &feed_status,
                                            &enrichment_senders,
                                            symbols.len(),
                                        ) {
                                            break;
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
        let client = Arc::clone(&shared_client);
        let screener = Arc::clone(&state.screener);
        let fng_cache = Arc::clone(&state.fng_cache);
        let feed_log = Arc::clone(&state.feed_log);
        let feed_gen = Arc::clone(&feed_gen);

        for (w, receiver) in enrichment_receivers.into_iter().enumerate() {
            let client = Arc::clone(&client);
            let screener = Arc::clone(&screener);
            let fng_cache = Arc::clone(&fng_cache);
            let feed_log = Arc::clone(&feed_log);
            let feed_gen = Arc::clone(&feed_gen);

            if let Err(e) = thread::Builder::new()
                .name(format!("enrich-{w}"))
                .spawn(move || {
                    let mut delayed = DelayedEnrichmentQueue::default();
                    loop {
                        if !generation_is_current(&feed_gen, generation) {
                            return;
                        }
                        let mut job = if let Some(job) =
                            take_ready_enrichment_job(&receiver, &mut delayed, Instant::now())
                        {
                            job
                        } else {
                            let timeout = delayed.next_wait(Instant::now());
                            match receiver.recv_timeout(timeout) {
                                Ok(symbol) => EnrichmentJob::new(symbol),
                                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => continue,
                                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                                    if delayed.len() == 0 {
                                        return;
                                    }
                                    if !wait_for_enrichment_retry(
                                        &feed_gen,
                                        generation,
                                        timeout.as_millis().max(1) as u64,
                                    ) {
                                        return;
                                    }
                                    continue;
                                }
                            }
                        };
                        let sym = job.symbol.as_str();
                        if !generation_is_current(&feed_gen, generation) {
                            return;
                        }
                        let cooldown = client.rate_limit_remaining_secs();
                        if cooldown > 0 {
                            let delay = Duration::from_secs(cooldown.saturating_add(1));
                            let _ = delayed.schedule(job, Instant::now(), delay);
                            continue;
                        }

                        let (chart_index, outcome) =
                            run_bulk_chart_pass(job.next_chart, |request, kind| {
                                if !generation_is_current(&feed_gen, generation) {
                                    return BulkChartAttemptOutcome::Cancelled;
                                }
                                let candles = match client.fetch_candles(
                                    sym,
                                    request.range,
                                    request.interval,
                                ) {
                                    Ok(candles) => candles,
                                    Err(error)
                                        if crate::yahoo_session::is_rate_limit_error(&error) =>
                                    {
                                        return BulkChartAttemptOutcome::RateLimited;
                                    }
                                    Err(_) => return BulkChartAttemptOutcome::Complete,
                                };
                                if !generation_is_current(&feed_gen, generation) {
                                    return BulkChartAttemptOutcome::Cancelled;
                                }
                                if let Some(summary) = compute_chart_summary(&candles) {
                                    match kind {
                                        BulkChartKind::Weekly => {
                                            if !mutate_current_enrichment_state(
                                                &feed_gen,
                                                generation,
                                                &screener,
                                                |state| {
                                                    state.ingest_weekly_summary(
                                                        sym.to_string(),
                                                        summary,
                                                    );
                                                    if is_crypto(sym) {
                                                        state.ingest_weekly_candles(
                                                            sym.to_string(),
                                                            candles.clone(),
                                                        );
                                                    }
                                                },
                                            ) {
                                                return BulkChartAttemptOutcome::Cancelled;
                                            }
                                            if is_crypto(sym) {
                                                let fng = fng_cache.get_cached().or_else(|| {
                                                    let http = crate::crypto_cycle::crypto_client();
                                                    let v = crate::crypto_cycle::fetch_fear_greed(
                                                        &http,
                                                    )
                                                    .ok();
                                                    if let Some(ref fng) = v {
                                                        fng_cache.put(fng.clone());
                                                    }
                                                    v
                                                });
                                                let now_e = std::time::SystemTime::now()
                                                    .duration_since(std::time::UNIX_EPOCH)
                                                    .map(|d| d.as_secs() as i64)
                                                    .unwrap_or(0);
                                                let metrics =
                                                    crate::crypto_cycle::compute_crypto_score(
                                                        sym,
                                                        &candles,
                                                        Some(0),
                                                        fng,
                                                        now_e,
                                                    );
                                                if !mutate_current_enrichment_state(
                                                    &feed_gen,
                                                    generation,
                                                    &screener,
                                                    |state| {
                                                        state.ingest_crypto_metrics(
                                                            sym.to_string(),
                                                            metrics,
                                                        );
                                                    },
                                                ) {
                                                    return BulkChartAttemptOutcome::Cancelled;
                                                }
                                            }
                                        }
                                        BulkChartKind::Hourly => {
                                            if !mutate_current_enrichment_state(
                                                &feed_gen,
                                                generation,
                                                &screener,
                                                |state| {
                                                    state.ingest_hourly_summary(
                                                        sym.to_string(),
                                                        summary,
                                                    );
                                                },
                                            ) {
                                                return BulkChartAttemptOutcome::Cancelled;
                                            }
                                        }
                                    }
                                }
                                BulkChartAttemptOutcome::Complete
                            });
                        job.next_chart = chart_index;
                        match outcome {
                            BulkChartAttemptOutcome::Complete => {
                                thread::sleep(Duration::from_millis(150));
                                continue;
                            }
                            BulkChartAttemptOutcome::Cancelled => return,
                            BulkChartAttemptOutcome::RateLimited => {}
                        }
                        let Some(wait_ms) = chart_enrichment_retry_delay_ms(
                            client.rate_limit_remaining_secs(),
                            job.retry_round,
                        ) else {
                            feed_log
                                .warn(&format!("chart enrichment rate limit exhausted for {sym}"));
                            continue;
                        };
                        job.retry_round += 1;
                        let _ =
                            delayed.schedule(job, Instant::now(), Duration::from_millis(wait_ms));
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
                let mut insider_schedule = InsiderRefreshSchedule::default();
                let mut financial_dcf_schedule = FinancialDcfSchedule::default();

                let cik_map: HashMap<String, u64> = match edgar::fetch_cik_map(&edgar_client) {
                    Ok(m) => m,
                    Err(e) => {
                        let _ = mutate_current_feed_status(
                            &feed_gen,
                            generation,
                            &feed_status,
                            |status| status.last_error = Some(format!("EDGAR CIK: {e}")),
                        );
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
                            if !generation_is_current(&feed_gen, generation) {
                                return;
                            }
                            let fund = s.fundamentals.get(sym).cloned();
                            let price = s.snapshots.get(sym).map(|x| x.market_price_cents);
                            if let Some(fund) = fund {
                                let key = financial_dcf_input_key(&fund, price);
                                let has_analysis =
                                    s.dcf_analyses.get(sym).is_some_and(|analysis| {
                                        analysis.model
                                        == crate::dcf_model::ValuationModel::ResidualIncomeEquity
                                    });
                                let now = Instant::now();
                                if financial_dcf_schedule.should_compute(
                                    sym,
                                    &key,
                                    now,
                                    has_analysis,
                                ) {
                                    let result = crate::dcf_model::compute_from_fundamentals(
                                        &fund,
                                        price,
                                        "fundamentals",
                                    );
                                    let success = if let Ok(analysis) = result {
                                        s.ingest_dcf_analysis(sym.to_string(), analysis);
                                        true
                                    } else {
                                        false
                                    };
                                    financial_dcf_schedule.record_result(sym, key, now, success);
                                }
                            }
                        } else {
                            financial_dcf_schedule.forget(sym);
                            if matches!(
                                business_class,
                                crate::dcf_model::BusinessClass::Unclassified
                                    | crate::dcf_model::BusinessClass::NotEligible
                            ) {
                                // Closed-world refusal also clears any legacy value restored
                                // before classification became available.
                                if !mutate_current_enrichment_state(
                                    &feed_gen,
                                    generation,
                                    &screener,
                                    |state| state.clear_dcf(sym),
                                ) {
                                    return;
                                }
                            }
                        }

                        if !insider_schedule.is_due(sym, Instant::now()) {
                            continue;
                        }
                        if !generation_is_current(&feed_gen, generation) {
                            return;
                        }
                        let insider_result = edgar::fetch_insider_activity(&edgar_client, cik);
                        if !generation_is_current(&feed_gen, generation) {
                            return;
                        }
                        insider_schedule.record_result(sym, Instant::now(), &insider_result);
                        if let Ok(Some(ins)) = insider_result {
                            if !mutate_current_enrichment_state(
                                &feed_gen,
                                generation,
                                &screener,
                                |state| {
                                    state.ingest_insider(
                                        sym.to_string(),
                                        InsiderData {
                                            net_shares_90d: ins.net_shares_90d,
                                            buy_count: ins.buy_count,
                                            sell_count: ins.sell_count,
                                        },
                                    );
                                },
                            ) {
                                return;
                            }
                        }
                        // Keep submissions requests below the existing SEC request pace.
                        thread::sleep(Duration::from_millis(125));
                    }
                    // Recheck changed financial fundamentals without spinning over the universe.
                    for _ in 0..EDGAR_SCAN_PAUSE_SECS {
                        if !generation_is_current(&feed_gen, generation) {
                            return;
                        }
                        thread::sleep(Duration::from_secs(1));
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
        let profile_gate = Arc::clone(&state.profile_apply_gate);

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
                        if with_current_profile_generation(
                            &profile_gate,
                            &feed_gen,
                            generation,
                            || db.insert_snapshots(&borrowed),
                        )
                        .is_none()
                        {
                            return;
                        }
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
    pub removed: usize,
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
        removed: 0,
    })
}

#[tauri::command]
pub fn portfolio_replace(
    positions: Vec<ImportPosition>,
    state: State<AppState>,
) -> Result<PortfolioImportResult, String> {
    let mut rows: Vec<(String, f64, i64, Option<String>)> = Vec::new();
    for p in positions {
        rows.push((
            p.symbol.trim().to_uppercase(),
            p.quantity,
            p.avg_cost_cents,
            p.opened_at,
        ));
    }
    let (created, updated, skipped, removed) = state.db.portfolio_replace(&rows)?;
    Ok(PortfolioImportResult {
        created,
        updated,
        skipped,
        removed,
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

/// Resolve uncached portfolio prices in one quote batch when multiple symbols need prices.
/// Keep chart lookup for missing quote rows and single-symbol requests.
fn resolve_missing_portfolio_prices<B, C>(
    missing: Vec<String>,
    mut batch_prices: B,
    mut chart_price: C,
) -> HashMap<String, i64>
where
    B: FnMut(&[String]) -> Result<HashMap<String, i64>, String>,
    C: FnMut(&str) -> Option<i64>,
{
    let mut unique = Vec::new();
    let mut seen = HashSet::new();
    for symbol in missing {
        let symbol = symbol.trim().to_ascii_uppercase();
        if !symbol.is_empty() && seen.insert(symbol.clone()) {
            unique.push(symbol);
        }
    }
    if unique.is_empty() {
        return HashMap::new();
    }

    // A cold Yahoo quote session uses two setup requests. Three or fewer
    // chart calls spend no more requests and keep the previous price path.
    let batch = if unique.len() >= 4 {
        batch_prices(&unique).unwrap_or_default()
    } else {
        HashMap::new()
    };
    let mut resolved = HashMap::new();
    for symbol in unique {
        let price = batch
            .get(&symbol)
            .copied()
            .filter(|price| *price > 0)
            .or_else(|| chart_price(&symbol).filter(|price| *price > 0));
        if let Some(price) = price {
            resolved.insert(symbol, price);
        }
    }
    resolved
}

/// Resolve current prices for arbitrary symbols. Read live screener prices first.
/// Batch Yahoo quotes cover multiple holdings outside the active universe.
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
            out.extend(resolve_missing_portfolio_prices(
                missing,
                |symbols| {
                    client
                        .fetch_quotes(symbols)
                        .map(|result| {
                            result
                                .quotes
                                .into_iter()
                                .map(|(symbol, quote)| (symbol, quote.market_price_cents))
                                .collect()
                        })
                        .map_err(|error| error.to_string())
                },
                |symbol| {
                    let price = client
                        .fetch_candles(symbol, "5d", "1d")
                        .ok()
                        .and_then(|candles| candles.last().map(|last| last.close_cents));
                    std::thread::sleep(std::time::Duration::from_millis(150));
                    price
                },
            ));
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

#[cfg(test)]
mod portfolio_quote_tests {
    use super::resolve_missing_portfolio_prices;
    use std::collections::HashMap;

    #[test]
    fn missing_holdings_share_one_batch_and_only_missing_quotes_use_charts() {
        let mut batch_calls = 0;
        let mut chart_symbols = Vec::new();
        let prices = resolve_missing_portfolio_prices(
            vec![
                "aapl".into(),
                "MSFT".into(),
                "JPM".into(),
                "TSM".into(),
                "AAPL".into(),
                "".into(),
            ],
            |symbols| {
                batch_calls += 1;
                assert_eq!(symbols, &["AAPL", "MSFT", "JPM", "TSM"]);
                Ok(HashMap::from([
                    ("AAPL".into(), 15_000),
                    ("JPM".into(), 20_000),
                    ("TSM".into(), 30_000),
                ]))
            },
            |symbol| {
                chart_symbols.push(symbol.to_string());
                Some(42_000)
            },
        );

        assert_eq!(batch_calls, 1);
        assert_eq!(chart_symbols, ["MSFT"]);
        assert_eq!(
            prices,
            HashMap::from([
                ("AAPL".into(), 15_000),
                ("MSFT".into(), 42_000),
                ("JPM".into(), 20_000),
                ("TSM".into(), 30_000),
            ])
        );
    }

    #[test]
    fn batch_failure_falls_back_without_losing_valid_chart_prices() {
        let mut chart_symbols = Vec::new();
        let prices = resolve_missing_portfolio_prices(
            vec!["AAPL".into(), "MSFT".into(), "JPM".into(), "TSM".into()],
            |_| Err("provider unavailable".to_string()),
            |symbol| {
                chart_symbols.push(symbol.to_string());
                (symbol == "MSFT").then_some(25_000)
            },
        );

        assert_eq!(chart_symbols, ["AAPL", "MSFT", "JPM", "TSM"]);
        assert_eq!(prices, HashMap::from([("MSFT".into(), 25_000)]));
    }

    #[test]
    fn three_or_fewer_missing_holdings_keep_the_existing_chart_path() {
        let mut batch_calls = 0;
        let prices = resolve_missing_portfolio_prices(
            vec!["AAPL".into(), "MSFT".into(), "JPM".into()],
            |_| {
                batch_calls += 1;
                Ok(HashMap::new())
            },
            |_| Some(14_000),
        );
        assert_eq!(batch_calls, 0);
        assert_eq!(
            prices,
            HashMap::from([
                ("AAPL".into(), 14_000),
                ("MSFT".into(), 14_000),
                ("JPM".into(), 14_000),
            ])
        );
    }
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
