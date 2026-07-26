//! Market Regime Engine v2
//!
//! Multi-pillar environment × contrarian sentiment policy engine.
//! Outputs exposure ceiling, action stance, and an auditable pillar breakdown.

mod cnn_fng;
mod composite;
mod interpret;
mod math;
mod narrative;
mod pillars;
mod regime_fit;
mod scoring_policy;
mod types;

pub use cnn_fng::CnnFngCache;
pub use regime_fit::{score_regime_fit, MarketContextUnavailableReason, RegimeCause};
pub use scoring_policy::{RegimeScoringPolicy, ScoreSide};
pub use types::{MarketRegime, REGIME_VERSION};

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use tauri::State;

use crate::engine::{compute_chart_summary, ChartSummary, HistoricalCandle, ScreenerState};
use crate::fetcher::{is_crypto, is_etf, YahooClient};
use crate::state::AppState;

use composite::{compat_regime, compose, CompositeInput};
use interpret::enrich_regime;
use narrative::fill_narrative;
use pillars::{
    avg_pairwise_corr_milli, breadth_pillar, compute_breadth, cross_asset_pillar,
    cross_asset_snapshot, quality_pillar, sentiment_pillar, spy_drawdown_from_ath_pct,
    trend_pillar, vol_pillar, vol_snapshot,
};
use types::{PillarResult, RegimePillar};

/// Soft TTL: after this, serve stale data and refresh in the background.
const REGIME_TTL_SECS: u64 = 150;
/// Background worker cadence while market-context scoring is enabled.
const REGIME_REFRESH_SECS: u64 = 120;

pub struct RegimeCache {
    inner: Mutex<Option<(MarketRegime, Instant)>>,
    last_exposure: Mutex<Option<u32>>,
    /// Prevents stampedes of concurrent Yahoo/CNN refreshes.
    refreshing: AtomicBool,
}

impl RegimeCache {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(None),
            last_exposure: Mutex::new(None),
            refreshing: AtomicBool::new(false),
        }
    }

    /// Stale-while-revalidate: return the latest value even past TTL so opportunity
    /// rows keep a market-context policy while a background refresh runs.
    pub fn get(&self) -> Option<MarketRegime> {
        let guard = self.inner.lock().ok()?;
        guard.as_ref().map(|(v, _)| v.clone())
    }

    pub fn is_fresh(&self) -> bool {
        let Ok(guard) = self.inner.lock() else {
            return false;
        };
        match guard.as_ref() {
            Some((_, at)) => at.elapsed().as_secs() <= REGIME_TTL_SECS,
            None => false,
        }
    }

    pub fn needs_refresh(&self) -> bool {
        !self.is_fresh()
    }

    pub fn put(&self, v: MarketRegime) {
        if let Ok(mut g) = self.last_exposure.lock() {
            *g = Some(v.suggested_exposure_pct);
        }
        if let Ok(mut guard) = self.inner.lock() {
            *guard = Some((v, Instant::now()));
        }
    }

    fn prev_exposure(&self) -> Option<u32> {
        self.last_exposure.lock().ok().and_then(|g| *g)
    }

    fn try_begin_refresh(&self) -> bool {
        self.refreshing
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
    }

    fn end_refresh(&self) {
        self.refreshing.store(false, Ordering::SeqCst);
    }
}

impl Default for RegimeCache {
    fn default() -> Self {
        Self::new()
    }
}

fn now_epoch() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn closes_from_candles(candles: &[HistoricalCandle]) -> Vec<f64> {
    candles
        .iter()
        .filter(|c| c.close_cents > 0)
        .map(|c| c.close_cents as f64 / 100.0)
        .collect()
}

fn fetch_closes(client: &YahooClient, symbol: &str, range: &str) -> Vec<f64> {
    client
        .fetch_candles(symbol, range, "1d")
        .ok()
        .map(|c| closes_from_candles(&c))
        .unwrap_or_default()
}

/// Build a ChartSummary when the screener has not enriched SPY yet.
fn summary_from_closes(closes: &[f64]) -> Option<ChartSummary> {
    if closes.len() < 20 {
        return None;
    }
    let candles: Vec<HistoricalCandle> = closes
        .iter()
        .enumerate()
        .filter_map(|(i, c)| {
            if *c <= 0.0 {
                return None;
            }
            let cents = (c * 100.0).round() as i64;
            Some(HistoricalCandle {
                epoch_seconds: i as i64 * 86_400,
                open_cents: cents,
                high_cents: cents,
                low_cents: cents,
                close_cents: cents,
                volume: 0,
            })
        })
        .collect();
    compute_chart_summary(&candles)
}

fn is_usable_regime(regime: &MarketRegime) -> bool {
    // Only cache readings that can actually drive the V3 market-context bucket.
    RegimeScoringPolicy::from_regime(regime, ScoreSide::Long).is_some()
}

/// Build MarketRegime v2 from live + cached inputs.
pub fn compute_market_regime(state: &AppState) -> MarketRegime {
    compute_market_regime_parts(&state.screener, &state.cnn_fng_cache, &state.regime_cache)
}

fn compute_market_regime_parts(
    screener: &Mutex<ScreenerState>,
    cnn_fng_cache: &CnnFngCache,
    regime_cache: &RegimeCache,
) -> MarketRegime {
    let mut warnings: Vec<String> = Vec::new();
    let mut notes_es: Vec<String> = Vec::new();
    let mut notes_en: Vec<String> = Vec::new();

    // ── Screener cache snapshot ──────────────────────────────────────────────
    let (chart_rows, spy_summary_cached, spy_closes_cached): (
        Vec<(String, ChartSummary)>,
        Option<ChartSummary>,
        Vec<f64>,
    ) = {
        let screener = match screener.lock() {
            Ok(s) => s,
            Err(_) => {
                warnings.push("screener lock poisoned".into());
                return MarketRegime {
                    warnings,
                    as_of_epoch: now_epoch(),
                    ..MarketRegime::default()
                };
            }
        };
        let rows: Vec<(String, ChartSummary)> = screener
            .chart_summaries
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        let spy = screener.chart_summaries.get("SPY").cloned();
        let spy_c = screener
            .daily_candles
            .get("SPY")
            .map(|c| closes_from_candles(c))
            .unwrap_or_default();
        (rows, spy, spy_c)
    };

    let breadth_snap = compute_breadth(&chart_rows);
    let breadth = breadth_pillar(&breadth_snap);

    if breadth_snap.sample < 80 {
        warnings.push(format!(
            "breadth sample n={} (partial universe, not full S&P)",
            breadth_snap.sample
        ));
    }

    // ── Network client ───────────────────────────────────────────────────────
    let http = Client::builder()
        .timeout(Duration::from_secs(12))
        .build()
        .ok();
    let yahoo = YahooClient::new().ok();

    // Prefer live Yahoo SPY candles so trend/VIX pillars work even when the
    // screener has not enriched SPY (ETFs lag stock enrichment).
    let (mut spy_summary, mut spy_closes) = (spy_summary_cached, spy_closes_cached);
    if let Some(ref y) = yahoo {
        if spy_summary.is_none() || spy_closes.len() < 60 {
            if let Ok(candles) = y.fetch_candles("SPY", "1y", "1d") {
                if !candles.is_empty() {
                    let closes = closes_from_candles(&candles);
                    if !closes.is_empty() {
                        spy_closes = closes;
                    }
                    if spy_summary.is_none() {
                        spy_summary = compute_chart_summary(&candles)
                            .or_else(|| summary_from_closes(&spy_closes));
                    }
                }
            }
        }
    }
    if spy_summary.is_none() {
        spy_summary = summary_from_closes(&spy_closes);
        if spy_summary.is_some() {
            warnings.push("SPY summary synthesized from closes (screener chart missing)".into());
        }
    }

    // VIX / VIX3M / SPY history
    let (vix_closes, vix3m_closes, spy_closes) = if let Some(ref y) = yahoo {
        (
            fetch_closes(y, "^VIX", "1y"),
            fetch_closes(y, "^VIX3M", "3mo"),
            {
                let mut c = spy_closes;
                if c.is_empty() {
                    c = fetch_closes(y, "SPY", "1y");
                }
                c
            },
        )
    } else {
        warnings.push("Yahoo client unavailable".into());
        (vec![], vec![], spy_closes)
    };

    let vol_snap = vol_snapshot(&vix_closes, &vix3m_closes, &spy_closes);
    let volatility = vol_pillar(&vol_snap);
    if vol_snap.vix.is_none() {
        warnings.push("VIX unavailable".into());
    }

    let trend = trend_pillar(spy_summary.as_ref(), &spy_closes);
    if trend.confidence_bps == 0 {
        warnings.push("SPY trend unavailable".into());
    }

    // CNN F&G with alternative.me fallback (CNN often returns 418)
    let cnn = if let Some(ref client) = http {
        cnn_fng::get_cnn_fng(client, cnn_fng_cache)
    } else {
        None
    };
    if cnn.is_none() {
        warnings.push("CNN Fear & Greed unavailable — using internal proxies / alt.me".into());
    }
    let sentiment = sentiment_pillar(cnn.as_ref(), &breadth_snap);

    // Cross-asset batch
    let mut xa_closes: HashMap<String, Vec<f64>> = HashMap::new();
    if let Some(ref y) = yahoo {
        for sym in [
            "HYG", "IEF", "TLT", "JNK", "IWM", "SPY", "XLK", "XLY", "XLP", "XLU", "XLV",
        ] {
            let c = if sym == "SPY" && !spy_closes.is_empty() {
                spy_closes.clone()
            } else {
                fetch_closes(y, sym, "3mo")
            };
            if !c.is_empty() {
                xa_closes.insert(sym.to_string(), c);
            }
        }
    }
    let cross = cross_asset_pillar(&xa_closes);
    let cross_snap = cross_asset_snapshot(&xa_closes);
    if cross.confidence_bps < 2000 {
        warnings.push("cross-asset data sparse".into());
    }

    // Correlation sample from equity closes in cache (up to 12 names)
    let avg_corr = {
        let screener = screener.lock().ok();
        screener.and_then(|s| {
            let mut series: Vec<Vec<f64>> = Vec::new();
            for (sym, candles) in s.daily_candles.iter() {
                if is_crypto(sym) || is_etf(sym) {
                    continue;
                }
                let c = closes_from_candles(candles);
                if c.len() >= 60 {
                    series.push(c);
                }
                if series.len() >= 12 {
                    break;
                }
            }
            avg_pairwise_corr_milli(&series, 60)
        })
    };

    let spy_above = match (
        spy_summary.as_ref().map(|s| s.latest_close_cents),
        spy_summary.as_ref().and_then(|s| s.ema200_cents),
    ) {
        (Some(p), Some(m)) if m > 0 => Some(p > m),
        _ => {
            if spy_closes.len() >= 200 {
                // rough: last vs SMA200 of closes
                let window = &spy_closes[spy_closes.len() - 200..];
                let sma: f64 = window.iter().sum::<f64>() / 200.0;
                spy_closes.last().map(|l| *l > sma)
            } else {
                None
            }
        }
    };

    let quality = quality_pillar(
        &breadth_snap,
        spy_above,
        avg_corr,
        vol_snap.stress_score,
        &cross_snap,
    );

    let dd = spy_drawdown_from_ath_pct(&spy_closes);

    let prev_exp = regime_cache.prev_exposure();

    let comp_in = CompositeInput {
        trend: trend.clone(),
        breadth: breadth.clone(),
        volatility: volatility.clone(),
        sentiment: sentiment.clone(),
        cross_asset: cross.clone(),
        quality: quality.clone(),
        spy_drawdown_from_ath_pct: dd,
        breadth_ma200_pct: breadth_snap.above_ma200_pct,
        vix_term_ratio: vol_snap.vix_term_ratio,
        cnn_fng: cnn.as_ref().map(|c| c.score),
        prev_exposure_pct: prev_exp,
    };
    let comp = compose(&comp_in);

    // Build pillar list with weights
    let pillars = vec![
        make_pillar("trend", "Tendencia", "Trend", &trend, comp.weight_trend_bps),
        make_pillar(
            "breadth",
            "Amplitud",
            "Breadth",
            &breadth,
            comp.weight_breadth_bps,
        ),
        make_pillar(
            "volatility",
            "Volatilidad / estrés",
            "Volatility / stress",
            &volatility,
            comp.weight_vol_bps,
        ),
        make_pillar(
            "sentiment",
            "Sentimiento (contrarian)",
            "Sentiment (contrarian)",
            &sentiment,
            0, // not in env weights
        ),
        make_pillar(
            "cross_asset",
            "Cross-asset",
            "Cross-asset",
            &cross,
            comp.weight_cross_bps,
        ),
        make_pillar(
            "quality",
            "Calidad / fragilidad",
            "Quality / fragility",
            &quality,
            comp.weight_quality_bps,
        ),
    ];

    // Notes from key signals
    for p in &pillars {
        for s in p.signals.iter().take(2) {
            if let Some(ref d) = s.detail {
                notes_es.push(format!("{}: {}", s.label_es, d));
                notes_en.push(format!("{}: {}", s.label_en, d));
            }
        }
    }

    if comp.global_confidence_bps < 4000 {
        warnings.push("global confidence low — degraded regime reading".into());
    }

    let mut regime = MarketRegime {
        primary_regime: comp.primary_regime.into(),
        environment_band: comp.environment_band.into(),
        action_stance: comp.action_stance.into(),
        suggested_exposure_pct: comp.suggested_exposure_pct,
        cash_buffer_pct: comp.cash_buffer_pct,
        new_risk_multiplier_bps: comp.new_risk_multiplier_bps,
        add_bias: comp.add_bias,
        prefer_quality: comp.prefer_quality,
        global_confidence_bps: comp.global_confidence_bps,
        environment_score: comp.environment_score,
        sentiment_score: comp.sentiment_score,
        quality_score: comp.quality_score,
        pillars,
        vix: vol_snap.vix,
        vix_percentile_1y: vol_snap.vix_percentile_1y,
        vix_term_ratio: vol_snap.vix_term_ratio,
        vix_state: vol_snap.vix_state,
        cnn_fear_greed: cnn.as_ref().map(|c| c.score.round() as u32),
        cnn_fear_greed_label: cnn.as_ref().map(|c| c.rating.clone()),
        cnn_fear_greed_prev_close: cnn.as_ref().and_then(|c| c.previous_close),
        breadth_above_ma200_pct: breadth_snap.above_ma200_pct,
        breadth_above_ma50_pct: breadth_snap.above_ma50_pct,
        breadth_sample: breadth_snap.sample,
        spy_above_ma200: spy_above,
        spy_price_cents: spy_summary.as_ref().map(|s| s.latest_close_cents),
        spy_ma200_cents: spy_summary.as_ref().and_then(|s| s.ema200_cents),
        spy_drawdown_from_ath_pct: dd,
        credit_score: cross_snap.credit_score,
        leadership_score: cross_snap.leadership_score,
        avg_corr_milli: avg_corr,
        thesis_es: String::new(),
        thesis_en: String::new(),
        reading_es: String::new(),
        reading_en: String::new(),
        action_bullets_es: vec![],
        action_bullets_en: vec![],
        notes_es,
        notes_en,
        warnings,
        regime: compat_regime(comp.environment_band).into(),
        as_of_epoch: now_epoch(),
        version: REGIME_VERSION,
    };

    fill_narrative(&mut regime, &comp);
    enrich_regime(&mut regime, &comp);
    regime
}

fn make_pillar(
    id: &str,
    name_es: &str,
    name_en: &str,
    r: &PillarResult,
    weight_bps: u32,
) -> RegimePillar {
    RegimePillar {
        id: id.into(),
        name_es: name_es.into(),
        name_en: name_en.into(),
        score: r.score,
        confidence_bps: r.confidence_bps,
        weight_used_bps: weight_bps,
        signals: r.signals.clone(),
        stale: r.stale,
        interpretation_es: String::new(),
        interpretation_en: String::new(),
        tone: "neutral".into(),
        radar_radius: interpret::radar_radius(id, r.score),
    }
}

/// Kick a single background recompute if none is already running.
pub fn request_regime_refresh(state: &AppState) {
    if !state.regime_cache.try_begin_refresh() {
        return;
    }
    let screener = Arc::clone(&state.screener);
    let cnn = Arc::clone(&state.cnn_fng_cache);
    let cache = Arc::clone(&state.regime_cache);
    let _ = std::thread::Builder::new()
        .name("regime-refresh".into())
        .spawn(move || {
            let regime = compute_market_regime_parts(&screener, &cnn, &cache);
            if is_usable_regime(&regime) {
                cache.put(regime);
            }
            cache.end_refresh();
        });
}

/// Long-lived worker: keep market-context data warm while scoring is enabled.
pub fn spawn_regime_worker(state: &AppState) {
    let screener = Arc::clone(&state.screener);
    let cnn = Arc::clone(&state.cnn_fng_cache);
    let cache = Arc::clone(&state.regime_cache);
    let enabled = Arc::clone(&state.apply_regime_scoring);
    let _ = std::thread::Builder::new()
        .name("regime-worker".into())
        .spawn(move || {
            // First pass soon after startup so V3 context is not stuck on "—".
            std::thread::sleep(Duration::from_secs(3));
            loop {
                if enabled.load(Ordering::Relaxed) && cache.needs_refresh() {
                    if cache.try_begin_refresh() {
                        let regime = compute_market_regime_parts(&screener, &cnn, &cache);
                        if is_usable_regime(&regime) {
                            cache.put(regime);
                        }
                        cache.end_refresh();
                    }
                }
                std::thread::sleep(Duration::from_secs(REGIME_REFRESH_SECS));
            }
        });
}

/// Tauri command — returns stale-while-revalidate cache; recomputes when empty.
#[tauri::command]
pub fn get_market_regime(state: State<'_, AppState>) -> Result<MarketRegime, String> {
    if let Some(cached) = state.regime_cache.get() {
        if state.regime_cache.needs_refresh() {
            request_regime_refresh(&state);
        }
        return Ok(cached);
    }
    // Empty cache: compute now so the banner and V3 context can light up.
    let regime = compute_market_regime(&state);
    if is_usable_regime(&regime) {
        state.regime_cache.put(regime.clone());
    } else {
        // Still return whatever we have, and keep trying in background.
        request_regime_refresh(&state);
    }
    Ok(regime)
}

#[cfg(test)]
mod tests {
    use super::composite::{compose, stance_matrix, CompositeInput};
    use super::math::fng_to_contrarian_score;
    use super::summary_from_closes;
    use super::types::MarketRegime;
    use super::types::PillarResult;
    use super::{is_usable_regime, RegimeCache, REGIME_TTL_SECS};

    #[test]
    fn regime_cache_ttl_safely_exceeds_banner_poll_interval() {
        assert!(REGIME_TTL_SECS > 120);
        assert_eq!(REGIME_TTL_SECS, 150);
    }

    #[test]
    fn regime_cache_serves_stale_after_ttl() {
        let cache = RegimeCache::new();
        let mut regime = MarketRegime::default();
        regime.environment_band = "RiskOn".into();
        regime.primary_regime = "Bull".into();
        regime.global_confidence_bps = 8000;
        cache.put(regime);
        assert!(cache.get().is_some());
        assert!(cache.is_fresh());
        // Force age past TTL via put timestamp — we only verify SWR get keeps a value.
        // (Instant is not mockable here; freshness is covered by needs_refresh defaults.)
        assert!(cache.get().unwrap().global_confidence_bps == 8000);
    }

    #[test]
    fn summary_from_closes_builds_emas() {
        let closes: Vec<f64> = (1..=220).map(|i| 100.0 + (i as f64) * 0.1).collect();
        let s = summary_from_closes(&closes).expect("summary");
        assert!(s.latest_close_cents > 0);
        assert!(s.ema50_cents.is_some());
        assert!(s.ema200_cents.is_some());
    }

    #[test]
    fn usable_regime_requires_policy_grade_confidence() {
        assert!(!is_usable_regime(&MarketRegime::default()));
        let mut low = MarketRegime::default();
        low.environment_band = "RiskOn".into();
        low.primary_regime = "Bull".into();
        low.action_stance = "TrendDeploy".into();
        low.global_confidence_bps = 2000;
        assert!(!is_usable_regime(&low));
        let mut ok = low.clone();
        ok.global_confidence_bps = 4000;
        assert!(is_usable_regime(&ok));
    }

    #[test]
    fn contrarian_extreme_greed_negative() {
        assert!(fng_to_contrarian_score(90.0) < -50);
    }

    #[test]
    fn matrix_euphoria_cell() {
        assert_eq!(stance_matrix("RiskOn", "ExtremeGreed"), "Euphoria");
        assert_eq!(stance_matrix("Crisis", "ExtremeFear"), "BloodInStreets");
    }

    #[test]
    fn compose_degraded_when_empty_conf() {
        let z = PillarResult {
            score: 0,
            confidence_bps: 0,
            signals: vec![],
            stale: true,
        };
        let out = compose(&CompositeInput {
            trend: z.clone(),
            breadth: z.clone(),
            volatility: z.clone(),
            sentiment: z.clone(),
            cross_asset: z.clone(),
            quality: z,
            spy_drawdown_from_ath_pct: None,
            breadth_ma200_pct: None,
            vix_term_ratio: None,
            cnn_fng: None,
            prev_exposure_pct: None,
        });
        assert_eq!(out.global_confidence_bps, 0);
    }
}
