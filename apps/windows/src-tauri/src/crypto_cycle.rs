use crate::engine::HistoricalCandle;
use reqwest::blocking::Client;
/// Crypto-specific cycle analysis: Bitcoin halving cycles + drawdown from ATH
/// + Fear & Greed sentiment + composite crypto scoring.
///
/// Why this module exists:
/// Traditional technical indicators (EMA, MACD, RSI) calibrated for equity
/// markets generate persistent false "avoid" signals during what are
/// historically the BEST accumulation zones in crypto. This module corrects
/// for that by adding cycle-aware and sentiment-contrarian context.
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use std::time::Duration;

// ── Bitcoin halving history (epoch seconds, UTC) ─────────────────────────────
// Source: bitcoin.org, public blockchain history
const HALVINGS_EPOCH: &[i64] = &[
    1354060800, // 2012-11-28  (1st halving)
    1467974400, // 2016-07-09  (2nd)
    1589155200, // 2020-05-11  (3rd)
    1713484800, // 2024-04-19  (4th — most recent)
                // Next expected ~2028-04 (every ~210k blocks ≈ 4 years)
];
const NEXT_HALVING_ESTIMATE_EPOCH: i64 = 1839196800; // 2028-04-13 est.

// ── Public types ─────────────────────────────────────────────────────────────

#[derive(Clone, Debug, Serialize)]
pub enum CyclePhase {
    PostHalvingExpansion,   // 0-200 days after halving
    BullRun,                // 200-500 days  (historic ATH zone)
    BearMarket,             // 500-1000 days
    AccumulationZone,       // 1000-1400 days (deep bear, smart money buying)
    PreHalvingAccumulation, // 1400+ days (close to next halving)
}

#[derive(Clone, Debug, Serialize)]
pub struct FearGreed {
    pub value: u32,             // 0-100
    pub classification: String, // "Extreme Fear" | "Fear" | "Neutral" | "Greed" | "Extreme Greed"
    pub fetched_at_epoch: i64,
}

#[derive(Clone, Debug, Serialize)]
pub struct CryptoMetrics {
    pub symbol: String,
    pub days_since_last_halving: i64,
    pub days_until_next_halving_est: i64,
    pub phase: CyclePhase,
    pub phase_label_es: String,
    pub phase_label_en: String,
    pub ath_price_cents: i64,
    pub current_price_cents: i64,
    pub drawdown_from_ath_pct: f64, // 0 (at ATH) to 100 (at zero)
    pub drawdown_zone: String, // "late-bull" | "correction" | "early-bear" | "deep-bear" | "capitulation"

    pub fear_greed: Option<FearGreed>,

    // Component scores (-100..+100 each)
    pub technical_component: Option<i32>,
    pub accumulation_component: i32,
    pub halving_component: i32,
    pub sentiment_component: i32,

    // Final composite (-100..+100)
    pub crypto_score: i32,
    pub crypto_label: &'static str, // "StrongAccumulate" | "Accumulate" | "HoldWait" | "Neutral" | "Caution" | "Distribute" | "Avoid"

    pub explanation_es: String,
    pub explanation_en: String,
}

// ── Halving cycle math ──────────────────────────────────────────────────────

/// Returns (days_since_last_halving, days_until_next_estimate).
pub fn halving_cycle_position(now_epoch: i64) -> (i64, i64) {
    let last = HALVINGS_EPOCH
        .iter()
        .filter(|&&h| h <= now_epoch)
        .max()
        .copied()
        .unwrap_or(HALVINGS_EPOCH[0]);
    let days_since = (now_epoch - last) / 86_400;
    let days_until = (NEXT_HALVING_ESTIMATE_EPOCH - now_epoch).max(0) / 86_400;
    (days_since, days_until)
}

pub fn classify_phase(days_since: i64) -> CyclePhase {
    match days_since {
        d if d < 200 => CyclePhase::PostHalvingExpansion,
        d if d < 500 => CyclePhase::BullRun,
        d if d < 1000 => CyclePhase::BearMarket,
        d if d < 1400 => CyclePhase::AccumulationZone,
        _ => CyclePhase::PreHalvingAccumulation,
    }
}

/// Score the cycle phase: how favorable for entering a new position.
/// Historic pattern says accumulation zones score highest.
fn phase_score(p: &CyclePhase) -> i32 {
    match p {
        CyclePhase::PostHalvingExpansion => 35,
        CyclePhase::BullRun => 10,
        CyclePhase::BearMarket => 25, // not zero — bear lows are good entry too
        CyclePhase::AccumulationZone => 60,
        CyclePhase::PreHalvingAccumulation => 75,
    }
}

fn phase_label(p: &CyclePhase) -> (&'static str, &'static str) {
    match p {
        CyclePhase::PostHalvingExpansion => ("Post-halving (expansión)", "Post-halving expansion"),
        CyclePhase::BullRun => ("Bull run", "Bull run"),
        CyclePhase::BearMarket => ("Bear market", "Bear market"),
        CyclePhase::AccumulationZone => ("Zona de acumulación", "Accumulation zone"),
        CyclePhase::PreHalvingAccumulation => (
            "Pre-halving (acumulación profunda)",
            "Pre-halving (deep accumulation)",
        ),
    }
}

// ── Drawdown from ATH ───────────────────────────────────────────────────────

/// Compute ATH (max close), current price, and drawdown pct from a series of
/// weekly or daily candles. We use the FULL series available (5y typically).
pub fn ath_and_drawdown(candles: &[HistoricalCandle]) -> (i64, i64, f64) {
    if candles.is_empty() {
        return (0, 0, 0.0);
    }
    let ath = candles.iter().map(|c| c.close_cents).max().unwrap_or(0);
    let current = candles.last().map(|c| c.close_cents).unwrap_or(0);
    if ath <= 0 {
        return (ath, current, 0.0);
    }
    let dd = ((ath - current) as f64 / ath as f64 * 100.0).max(0.0);
    (ath, current, dd)
}

/// Score the drawdown zone: deeper drawdowns historically have been better
/// accumulation opportunities (rewarded), while close-to-ATH is risky.
fn drawdown_score(dd_pct: f64) -> (i32, &'static str) {
    if dd_pct < 15.0 {
        (-25, "late-bull")
    } else if dd_pct < 40.0 {
        (0, "correction")
    } else if dd_pct < 65.0 {
        (35, "early-bear")
    } else if dd_pct < 85.0 {
        (60, "deep-bear")
    } else {
        (75, "capitulation")
    }
}

fn drawdown_label(zone: &str) -> (&'static str, &'static str) {
    match zone {
        "late-bull" => ("Late bull (cuidado)", "Late bull (caution)"),
        "correction" => ("Corrección normal", "Normal correction"),
        "early-bear" => ("Bear temprano", "Early bear"),
        "deep-bear" => ("Deep bear / acumulación", "Deep bear / accumulation"),
        "capitulation" => ("Capitulación (raro)", "Capitulation (rare)"),
        _ => ("?", "?"),
    }
}

// ── Fear & Greed (alternative.me free API) ──────────────────────────────────

const FNG_CACHE_TTL_SECS: u64 = 3600;

pub struct FngCache {
    inner: Mutex<Option<(FearGreed, std::time::Instant)>>,
}

impl FngCache {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(None),
        }
    }
    pub fn get_cached(&self) -> Option<FearGreed> {
        let guard = self.inner.lock().ok()?;
        let (v, at) = guard.as_ref()?;
        if at.elapsed().as_secs() > FNG_CACHE_TTL_SECS {
            return None;
        }
        Some(v.clone())
    }
    pub fn put(&self, v: FearGreed) {
        if let Ok(mut guard) = self.inner.lock() {
            *guard = Some((v, std::time::Instant::now()));
        }
    }
}

pub fn fetch_fear_greed(client: &Client) -> Result<FearGreed, String> {
    #[derive(Deserialize)]
    struct Resp {
        data: Vec<Entry>,
    }
    #[derive(Deserialize)]
    struct Entry {
        value: String,
        value_classification: String,
        timestamp: String,
    }

    let url = "https://api.alternative.me/fng/?limit=1";
    let resp: Resp = client
        .get(url)
        .timeout(Duration::from_secs(10))
        .send()
        .map_err(|e| format!("F&G fetch: {}", e))?
        .json()
        .map_err(|e| format!("F&G parse: {}", e))?;
    let first = resp.data.into_iter().next().ok_or("F&G empty response")?;
    let value: u32 = first.value.parse().map_err(|_| "F&G value not number")?;
    let ts: i64 = first.timestamp.parse().unwrap_or(now_secs());
    Ok(FearGreed {
        value,
        classification: first.value_classification,
        fetched_at_epoch: ts,
    })
}

/// Contrarian sentiment score: Extreme Fear is HISTORICALLY a buy signal,
/// Extreme Greed is HISTORICALLY a sell signal.
fn sentiment_score(fng: &FearGreed) -> i32 {
    let v = fng.value as i32;
    if v <= 25 {
        70
    }
    // Extreme Fear → strong contrarian buy
    else if v <= 45 {
        35
    }
    // Fear
    else if v <= 55 {
        0
    }
    // Neutral
    else if v <= 75 {
        -35
    }
    // Greed
    else {
        -70
    } // Extreme Greed → strong contrarian sell
}

// ── Composite crypto score ──────────────────────────────────────────────────

const W_TECHNICAL: f64 = 0.30;
const W_ACCUMULATION: f64 = 0.30;
const W_HALVING: f64 = 0.25;
const W_SENTIMENT: f64 = 0.15;

/// Compute the full crypto-aware score for a symbol.
/// `weekly_candles` should be the 5y/1wk fetch (best long-term cycle context).
pub fn compute_crypto_score(
    symbol: &str,
    weekly_candles: &[HistoricalCandle],
    technical_score: Option<i32>,
    fng: Option<FearGreed>,
    now_epoch: i64,
) -> CryptoMetrics {
    let (days_since, days_until) = halving_cycle_position(now_epoch);
    let phase = classify_phase(days_since);
    let phase_pts = phase_score(&phase);
    let (phase_es, phase_en) = phase_label(&phase);

    let (ath, current, dd_pct) = ath_and_drawdown(weekly_candles);
    let (dd_pts, dd_zone) = drawdown_score(dd_pct);

    let sent_pts = fng.as_ref().map(sentiment_score).unwrap_or(0);

    // Compose: each component is -100..+100, weights sum to 1.0
    let tech_pts = technical_score.unwrap_or(0) as f64;
    let composite = tech_pts * W_TECHNICAL
        + dd_pts as f64 * W_ACCUMULATION
        + phase_pts as f64 * W_HALVING
        + sent_pts as f64 * W_SENTIMENT;
    let final_score = composite.round().clamp(-100.0, 100.0) as i32;

    let label = score_to_label(final_score);
    let (exp_es, exp_en) = build_explanation(
        symbol,
        &phase,
        phase_es,
        phase_en,
        dd_pct,
        dd_zone,
        fng.as_ref(),
        final_score,
        label,
    );

    CryptoMetrics {
        symbol: symbol.to_string(),
        days_since_last_halving: days_since,
        days_until_next_halving_est: days_until,
        phase,
        phase_label_es: phase_es.to_string(),
        phase_label_en: phase_en.to_string(),
        ath_price_cents: ath,
        current_price_cents: current,
        drawdown_from_ath_pct: dd_pct,
        drawdown_zone: dd_zone.to_string(),
        fear_greed: fng,
        technical_component: technical_score,
        accumulation_component: dd_pts,
        halving_component: phase_pts,
        sentiment_component: sent_pts,
        crypto_score: final_score,
        crypto_label: label,
        explanation_es: exp_es,
        explanation_en: exp_en,
    }
}

fn score_to_label(score: i32) -> &'static str {
    if score >= 60 {
        "StrongAccumulate"
    } else if score >= 30 {
        "Accumulate"
    } else if score >= 10 {
        "HoldWait"
    } else if score >= -10 {
        "Neutral"
    } else if score >= -30 {
        "Caution"
    } else if score >= -60 {
        "Distribute"
    } else {
        "Avoid"
    }
}

fn build_explanation(
    symbol: &str,
    _phase: &CyclePhase,
    phase_es: &str,
    phase_en: &str,
    dd_pct: f64,
    dd_zone: &str,
    fng: Option<&FearGreed>,
    final_score: i32,
    label: &str,
) -> (String, String) {
    let (dd_es, dd_en) = drawdown_label(dd_zone);
    let fng_es = match fng {
        Some(f) => format!(" Fear & Greed: {} ({}).", f.value, f.classification),
        None => String::new(),
    };
    let fng_en = fng_es.clone();

    let label_es = match label {
        "StrongAccumulate" => "ACUMULAR FUERTE — zona histórica de mejor entrada",
        "Accumulate" => "ACUMULAR — DCA recomendado en este rango",
        "HoldWait" => "ESPERAR — mantener si tenés, no entrar agresivo",
        "Neutral" => "NEUTRAL — sin sesgo direccional claro",
        "Caution" => "PRECAUCIÓN — sesgo a tomar ganancias",
        "Distribute" => "DISTRIBUIR — empezar a reducir exposición",
        "Avoid" => "EVITAR — distribución total / late bull peligroso",
        _ => "—",
    };
    let label_en = match label {
        "StrongAccumulate" => "STRONG ACCUMULATE — historic best-entry zone",
        "Accumulate" => "ACCUMULATE — DCA recommended in this range",
        "HoldWait" => "HOLD/WAIT — hold if owned, don't enter aggressively",
        "Neutral" => "NEUTRAL — no clear directional bias",
        "Caution" => "CAUTION — bias to take profits",
        "Distribute" => "DISTRIBUTE — start reducing exposure",
        "Avoid" => "AVOID — full distribution / dangerous late bull",
        _ => "—",
    };

    (
        format!(
            "{}: {} (score {}). Fase de halving: {}. Drawdown desde ATH: {:.0}% ({}).{}",
            symbol, label_es, final_score, phase_es, dd_pct, dd_es, fng_es
        ),
        format!(
            "{}: {} (score {}). Halving phase: {}. ATH drawdown: {:.0}% ({}).{}",
            symbol, label_en, final_score, phase_en, dd_pct, dd_en, fng_en
        ),
    )
}

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub fn crypto_client() -> Client {
    Client::builder()
        .timeout(Duration::from_secs(15))
        .user_agent("DiscountScreener/1.0")
        .build()
        .expect("crypto http client")
}
