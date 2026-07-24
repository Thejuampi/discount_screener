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
pub use regime_fit::score_regime_fit;
pub use scoring_policy::{RegimeScoringPolicy, ScoreSide};
pub use types::{MarketRegime, REGIME_VERSION};

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use tauri::State;

use crate::engine::ChartSummary;
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

/// TTL for the full computed regime response.
const REGIME_TTL_SECS: u64 = 150;

pub struct RegimeCache {
    inner: Mutex<Option<(MarketRegime, Instant)>>,
    last_exposure: Mutex<Option<u32>>,
}

impl RegimeCache {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(None),
            last_exposure: Mutex::new(None),
        }
    }

    pub fn get(&self) -> Option<MarketRegime> {
        let guard = self.inner.lock().ok()?;
        let (v, at) = guard.as_ref()?;
        if at.elapsed().as_secs() > REGIME_TTL_SECS {
            return None;
        }
        Some(v.clone())
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

fn closes_from_candles(candles: &[crate::engine::HistoricalCandle]) -> Vec<f64> {
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

/// Build MarketRegime v2 from live + cached inputs.
pub fn compute_market_regime(state: &AppState) -> MarketRegime {
    let mut warnings: Vec<String> = Vec::new();
    let mut notes_es: Vec<String> = Vec::new();
    let mut notes_en: Vec<String> = Vec::new();

    // ── Screener cache snapshot ──────────────────────────────────────────────
    let (chart_rows, spy_summary, spy_closes_cached): (
        Vec<(String, ChartSummary)>,
        Option<ChartSummary>,
        Vec<f64>,
    ) = {
        let screener = match state.screener.lock() {
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

    // VIX / VIX3M / SPY history
    let (vix_closes, vix3m_closes, spy_closes) = if let Some(ref y) = yahoo {
        (
            fetch_closes(y, "^VIX", "1y"),
            fetch_closes(y, "^VIX3M", "3mo"),
            {
                let mut c = fetch_closes(y, "SPY", "1y");
                if c.is_empty() {
                    c = spy_closes_cached;
                }
                c
            },
        )
    } else {
        warnings.push("Yahoo client unavailable".into());
        (vec![], vec![], spy_closes_cached)
    };

    let vol_snap = vol_snapshot(&vix_closes, &vix3m_closes, &spy_closes);
    let volatility = vol_pillar(&vol_snap);
    if vol_snap.vix.is_none() {
        warnings.push("VIX unavailable".into());
    }

    let spy_for_trend = spy_summary.as_ref().or_else(|| {
        // synthesize minimal from closes if needed
        None
    });
    let trend = trend_pillar(spy_for_trend, &spy_closes);

    // CNN F&G
    let cnn = if let Some(ref client) = http {
        cnn_fng::get_cnn_fng(client, &state.cnn_fng_cache)
    } else {
        None
    };
    if cnn.is_none() {
        warnings.push("CNN Fear & Greed unavailable — using internal proxies".into());
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
        let screener = state.screener.lock().ok();
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

    let prev_exp = state.regime_cache.prev_exposure();

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

/// Tauri command — returns cached regime if fresh, else recomputes.
#[tauri::command]
pub fn get_market_regime(state: State<'_, AppState>) -> Result<MarketRegime, String> {
    if let Some(cached) = state.regime_cache.get() {
        return Ok(cached);
    }
    // Heavy network work off the async runtime: command is sync (blocking OK for Tauri invoke).
    let regime = compute_market_regime(&state);
    state.regime_cache.put(regime.clone());
    Ok(regime)
}

#[cfg(test)]
mod tests {
    use super::composite::{compose, stance_matrix, CompositeInput};
    use super::math::fng_to_contrarian_score;
    use super::types::PillarResult;
    use super::REGIME_TTL_SECS;

    #[test]
    fn regime_cache_ttl_safely_exceeds_banner_poll_interval() {
        assert!(REGIME_TTL_SECS > 120);
        assert_eq!(REGIME_TTL_SECS, 150);
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
