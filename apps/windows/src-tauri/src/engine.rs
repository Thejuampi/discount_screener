/// Core domain types and scoring logic — mirrors apps/desktop/src/lib.rs
/// but standalone so we don't pull in ratatui/crossterm as compile deps.
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ── Constants ────────────────────────────────────────────────────────────────

pub const DEFAULT_MIN_GAP_BPS: i32 = 1_000;
pub const DEFAULT_SIGNAL_MAX_AGE_SECONDS: u64 = 6 * 3600;

// AggressiveV2 fundamentals weights (mirrors Android OpportunityEngine)
const V2_FUND_FCF_YIELD_LOWER: f64 = -0.02;
const V2_FUND_FCF_YIELD_UPPER: f64 = 0.08;
const V2_FUND_FCF_WEIGHT: f64 = 25.0;
const V2_FUND_OCF_FALLBACK_WEIGHT: f64 = 10.0;
const V2_FUND_ROE_LOWER_BPS: f64 = 0.0;
const V2_FUND_ROE_UPPER_BPS: f64 = 2_000.0;
const V2_FUND_ROE_WEIGHT: f64 = 20.0;
const V2_FUND_GROWTH_LOWER_BPS: f64 = -500.0;
const V2_FUND_GROWTH_UPPER_BPS: f64 = 1_500.0;
const V2_FUND_GROWTH_WEIGHT: f64 = 15.0;
const V2_FUND_BALANCE_DE_LOW: f64 = 30.0;
const V2_FUND_BALANCE_DE_HIGH: f64 = 200.0;
const V2_FUND_BALANCE_WEIGHT: f64 = 20.0;
const V2_FUND_PE_LOW: f64 = 800.0;
const V2_FUND_PE_HIGH: f64 = 3_500.0;
const V2_FUND_PE_WEIGHT: f64 = 20.0;
const V2_FUNDAMENTALS_FULL_WEIGHT: f64 = 100.0;

// AggressiveV2 forecast weights
const V2_FORECAST_UPSIDE_LOWER_BPS: f64 = -2_000.0;
const V2_FORECAST_UPSIDE_UPPER_BPS: f64 = 5_000.0;
const V2_FORECAST_VALUATION_WEIGHT: f64 = 50.0;
const V2_FORECAST_REC_LOW_HUNDREDTHS: f64 = 150.0;
const V2_FORECAST_REC_HIGH_HUNDREDTHS: f64 = 300.0;
const V2_FORECAST_REC_WEIGHT: f64 = 15.0;
const V2_FORECAST_MIN_ANALYST_OPINIONS: u32 = 3;
const V2_FORECAST_FULL_ANALYST_OPINIONS: f64 = 15.0;
const V2_FORECAST_BREADTH_WEIGHT: f64 = 20.0;
const V2_FORECAST_UNCERTAINTY_BOUND: f64 = 0.6;
const V2_FORECAST_UNCERTAINTY_WEIGHT: f64 = 10.0;
const V2_FORECAST_FRESHNESS_WEIGHT: f64 = 5.0;
const V2_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT: f64 = 25.0;
const V2_FORECAST_FULL_WEIGHT: f64 = 100.0; // sum of all weights

// Composite
const V2_COMPOSITE_COVERAGE_BONUS: i32 = 5;
const V2_COMPOSITE_BOUND: i32 = 110;

// Decision thresholds (Android DefaultDashboardRepository)
const DECISION_ACT_THRESHOLD: i32 = 10;
const DECISION_AVOID_THRESHOLD: i32 = 8;

// ── Enums ────────────────────────────────────────────────────────────────────

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum ConfidenceBand {
    Low,
    Provisional,
    High,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum QualificationStatus {
    Qualified,
    Unprofitable,
    GapTooSmall,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum ExternalSignalStatus {
    Missing,
    Stale,
    Supportive,
    Divergent,
}

// ── Market data structs ───────────────────────────────────────────────────────

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct MarketSnapshot {
    pub symbol: String,
    pub company_name: Option<String>,
    pub profitable: bool,
    pub market_price_cents: i64,
    pub intrinsic_value_cents: i64,
    #[serde(default)]
    pub previous_close_cents: i64,
    /// Next scheduled earnings date (unix epoch). None if unknown / not applicable.
    #[serde(default)]
    pub next_earnings_epoch: Option<i64>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ExternalValuationSignal {
    pub symbol: String,
    pub fair_value_cents: i64,
    pub age_seconds: u64,
    pub low_fair_value_cents: Option<i64>,
    pub high_fair_value_cents: Option<i64>,
    pub analyst_opinion_count: Option<u32>,
    pub recommendation_mean_hundredths: Option<u16>,
    pub strong_buy_count: Option<u32>,
    pub buy_count: Option<u32>,
    pub hold_count: Option<u32>,
    pub sell_count: Option<u32>,
    pub strong_sell_count: Option<u32>,
    pub weighted_fair_value_cents: Option<i64>,
    pub weighted_analyst_count: Option<u32>,
}

#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct FundamentalSnapshot {
    pub symbol: String,
    pub sector_key: Option<String>,
    pub sector_name: Option<String>,
    pub industry_key: Option<String>,
    pub industry_name: Option<String>,
    pub market_cap_dollars: Option<u64>,
    pub shares_outstanding: Option<u64>,
    pub trailing_pe_hundredths: Option<u32>,
    pub forward_pe_hundredths: Option<u32>,
    pub price_to_book_hundredths: Option<u32>,
    pub return_on_equity_bps: Option<i32>,
    pub ebitda_dollars: Option<i64>,
    pub enterprise_value_dollars: Option<i64>,
    pub enterprise_to_ebitda_hundredths: Option<i32>,
    pub total_debt_dollars: Option<i64>,
    pub total_cash_dollars: Option<i64>,
    pub debt_to_equity_hundredths: Option<i32>,
    pub free_cash_flow_dollars: Option<i64>,
    pub operating_cash_flow_dollars: Option<i64>,
    pub beta_millis: Option<i32>,
    pub trailing_eps_cents: Option<i64>,
    pub earnings_growth_bps: Option<i32>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct HistoricalCandle {
    pub epoch_seconds: i64,
    pub open_cents: i64,
    pub high_cents: i64,
    pub low_cents: i64,
    pub close_cents: i64,
    pub volume: u64,
}

/// Technical indicators computed from a candle series.
/// Works for any timeframe — daily / weekly / hourly all use the same struct.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ChartSummary {
    pub latest_close_cents: i64,
    pub ema20_cents: Option<i64>,
    pub ema50_cents: Option<i64>,
    pub ema200_cents: Option<i64>,
    pub macd_cents: Option<i64>,
    pub signal_cents: Option<i64>,
    pub histogram_cents: Option<i64>,
    // ── Multi-TF v3 indicators ───────────────────────────────────────────────
    pub rsi: Option<f64>, // 14-period RSI, 0..100
    /// EMA-smoothed 1-bar RSI change (Android `latestRsiSlope` parity for V3).
    pub rsi_slope: Option<f64>,
    pub adx: Option<f64>,             // 14-period ADX, 0..100 (trend strength)
    pub plus_di: Option<f64>,         // +DI
    pub minus_di: Option<f64>,        // -DI
    pub bb_upper_cents: Option<i64>,  // Bollinger upper (SMA20 + 2σ)
    pub bb_middle_cents: Option<i64>, // SMA20
    pub bb_lower_cents: Option<i64>,  // Bollinger lower
    pub bb_percent_b: Option<f64>,    // (close - lower) / (upper - lower), 0..1 inside bands
    pub bb_bandwidth: Option<f64>,    // (upper - lower) / middle
    pub obv_slope: Option<f64>,       // normalized slope of OBV over last 20 bars
    pub volume_ratio: Option<f64>,    // latest volume / SMA20(volume)
    pub atr_cents: Option<i64>,       // 14-period ATR
    pub high_52w_cents: Option<i64>,
    pub low_52w_cents: Option<i64>,
    pub pos_52w_pct: Option<f64>, // 0..100, position within 52w range
}

/// Detected candlestick pattern on the most recent N bars.
#[derive(Clone, Debug, Serialize)]
pub struct DetectedPattern {
    pub name: String,
    pub bias: &'static str, // "Bullish" | "Bearish" | "Neutral"
    pub bars_ago: usize,    // 0 = latest closed bar
}

/// Price/RSI divergence — when price and RSI disagree on direction.
/// Regular = reversal signal. Hidden = continuation signal in existing trend.
#[derive(Clone, Debug, Serialize)]
pub struct Divergence {
    pub kind: &'static str, // "RegularBullish" | "RegularBearish" | "HiddenBullish" | "HiddenBearish"
    pub label: &'static str, // human-readable Spanish
    pub bias: &'static str, // "Bullish" | "Bearish"
    pub bars_ago: usize,    // when the most recent pivot of the divergence occurred
    pub strength: f64,      // 0-1, magnitude of the divergence
    pub price_at_p1: i64,
    pub price_at_p2: i64,
    pub rsi_at_p1: f64,
    pub rsi_at_p2: f64,
}

/// Auto-detected support and resistance levels (in cents).
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct SupportResistance {
    pub supports_cents: Vec<i64>,    // up to 3 nearest below current price
    pub resistances_cents: Vec<i64>, // up to 3 nearest above current price
}

/// Multi-timeframe technical breakdown.
#[derive(Clone, Debug, Serialize)]
pub struct TechnicalBreakdown {
    pub trend_score: Option<i32>, // -100..+100
    pub momentum_score: Option<i32>,
    pub volatility_score: Option<i32>,
    pub volume_score: Option<i32>,
    pub pattern_score: Option<i32>,
    pub alignment: &'static str, // "BullStack" | "BearStack" | "Mixed" | "Unknown"
    pub weekly_trend: &'static str, // "Bullish" | "Bearish" | "Neutral" | "Unknown"
    pub daily_trend: &'static str,
    pub hourly_trend: &'static str,
    pub patterns: Vec<DetectedPattern>,
    pub levels: SupportResistance,
    pub divergences: Vec<Divergence>,
}

// ── Derived/scored structs ────────────────────────────────────────────────────

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CandidateRow {
    pub symbol: String,
    pub company_name: Option<String>,
    pub market_price_cents: i64,
    pub previous_close_cents: i64,
    pub next_earnings_epoch: Option<i64>,
    pub intrinsic_value_cents: i64,
    /// Discount to fair value in bps; `None` when no usable target (not a sentinel).
    pub gap_bps: Option<i32>,
    pub qualification: QualificationStatus,
    pub confidence: ConfidenceBand,
    pub signal_status: ExternalSignalStatus,
    pub analyst_opinion_count: Option<u32>,
    pub recommendation_mean_hundredths: Option<u16>,
    pub sector_name: Option<String>,
    // signal details needed for forecast scoring
    pub low_fair_value_cents: Option<i64>,
    pub high_fair_value_cents: Option<i64>,
    pub strong_buy_count: Option<u32>,
    pub buy_count: Option<u32>,
    pub hold_count: Option<u32>,
    pub sell_count: Option<u32>,
    pub strong_sell_count: Option<u32>,
    // fundamentals needed for scoring
    pub free_cash_flow_dollars: Option<i64>,
    pub operating_cash_flow_dollars: Option<i64>,
    pub market_cap_dollars: Option<u64>,
    pub return_on_equity_bps: Option<i32>,
    pub earnings_growth_bps: Option<i32>,
    pub debt_to_equity_hundredths: Option<i32>,
    pub total_cash_dollars: Option<i64>,
    pub total_debt_dollars: Option<i64>,
    pub forward_pe_hundredths: Option<u32>,
    pub price_to_book_hundredths: Option<u32>,
    pub enterprise_to_ebitda_hundredths: Option<i32>,
    pub beta_millis: Option<i32>,
    pub shares_outstanding: Option<u64>,
    // DCF intrinsic value computed from SEC EDGAR (cents per share)
    pub dcf_value_cents: Option<i64>,
    // Insider activity (Form 4) over trailing 90 days
    pub insider_net_shares_90d: Option<i64>,
    pub insider_buy_count: Option<u32>,
    pub insider_sell_count: Option<u32>,
}

#[derive(Clone, Debug, Serialize)]
pub struct SymbolDetail {
    pub symbol: String,
    pub company_name: Option<String>,
    pub market_price_cents: i64,
    pub intrinsic_value_cents: i64,
    /// Discount to fair value in bps; `None` when no usable target (not a sentinel).
    pub gap_bps: Option<i32>,
    pub qualification: QualificationStatus,
    pub confidence: ConfidenceBand,
    pub signal_status: ExternalSignalStatus,
    pub signal_age_seconds: Option<u64>,
    pub low_fair_value_cents: Option<i64>,
    pub high_fair_value_cents: Option<i64>,
    pub analyst_opinion_count: Option<u32>,
    pub recommendation_mean_hundredths: Option<u16>,
    pub strong_buy_count: Option<u32>,
    pub buy_count: Option<u32>,
    pub hold_count: Option<u32>,
    pub sell_count: Option<u32>,
    pub strong_sell_count: Option<u32>,
    pub fundamentals: FundamentalSnapshot,
    pub chart_summary: Option<ChartSummary>,
    pub weekly_summary: Option<ChartSummary>,
    pub hourly_summary: Option<ChartSummary>,
    pub monthly_summary: Option<ChartSummary>,
    pub technical_breakdown: Option<TechnicalBreakdown>,
    pub dcf_value_cents: Option<i64>,
    pub dcf_analysis: Option<crate::dcf_model::DcfAnalysis>,
    pub insider_net_shares_90d: Option<i64>,
    pub insider_buy_count: Option<u32>,
    pub insider_sell_count: Option<u32>,
    pub next_earnings_epoch: Option<i64>,
    pub chart_patterns: Vec<crate::chart_patterns::ChartPattern>,
    pub fib: Option<crate::fibonacci::FibAnalysis>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AlertEvent {
    pub symbol: String,
    pub kind: AlertKind,
    pub timestamp_seconds: u64,
}

/// Cached insider activity from EDGAR Form 4 filings.
#[derive(Clone, Debug, Default)]
pub struct InsiderData {
    pub net_shares_90d: i64,
    pub buy_count: u32,
    pub sell_count: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum AlertKind {
    EnteredQualified,
    ExitedQualified,
    ConfidenceUpgraded,
}

// ── Chart / Technical ─────────────────────────────────────────────────────────

/// Compute EMA from a slice of close prices (in cents).
/// Returns None if fewer candles than `period`.
fn ema(closes: &[i64], period: usize) -> Option<i64> {
    if closes.len() < period {
        return None;
    }
    let k = 2.0 / (period as f64 + 1.0);
    let mut ema = closes[..period].iter().sum::<i64>() as f64 / period as f64;
    for &close in &closes[period..] {
        ema = close as f64 * k + ema * (1.0 - k);
    }
    Some(ema.round() as i64)
}

/// Compute EMA of a series of f64 values.
fn ema_f64(values: &[f64], period: usize) -> Option<f64> {
    if values.len() < period {
        return None;
    }
    let k = 2.0 / (period as f64 + 1.0);
    let mut e = values[..period].iter().sum::<f64>() / period as f64;
    for &v in &values[period..] {
        e = v * k + e * (1.0 - k);
    }
    Some(e)
}

pub fn compute_chart_summary(candles: &[HistoricalCandle]) -> Option<ChartSummary> {
    if candles.is_empty() {
        return None;
    }
    let closes: Vec<i64> = candles.iter().map(|c| c.close_cents).collect();
    let latest = *closes.last()?;

    let ema20 = ema(&closes, 20);
    let ema50 = ema(&closes, 50);
    let ema200 = ema(&closes, 200);

    // MACD = EMA12 - EMA26
    let (macd_val, signal_val, histogram_val) = if closes.len() >= 26 {
        let closes_f: Vec<f64> = closes.iter().map(|&c| c as f64).collect();
        let k12 = 2.0 / 13.0_f64;
        let k26 = 2.0 / 27.0_f64;
        let mut ema12 = closes_f[..12].iter().sum::<f64>() / 12.0;
        let mut ema26 = closes_f[..26].iter().sum::<f64>() / 26.0;
        let mut macd_series: Vec<f64> = Vec::with_capacity(closes.len() - 25);
        for (i, &c) in closes_f.iter().enumerate() {
            if i >= 12 {
                ema12 = c * k12 + ema12 * (1.0 - k12);
            }
            if i >= 26 {
                ema26 = c * k26 + ema26 * (1.0 - k26);
                macd_series.push(ema12 - ema26);
            }
        }
        let macd_now = macd_series.last().copied().unwrap_or(0.0);
        let signal_now = ema_f64(&macd_series, 9).unwrap_or(macd_now);
        let histogram_now = macd_now - signal_now;
        (
            Some(macd_now.round() as i64),
            Some(signal_now.round() as i64),
            Some(histogram_now.round() as i64),
        )
    } else {
        (None, None, None)
    };

    // RSI(14) + slope (Android ChartAnalysis projected RSI slope)
    let rsi = compute_rsi(&closes, 14);
    let rsi_slope = compute_rsi_slope(&closes, 14);

    // ADX(14) + DI
    let (adx, plus_di, minus_di) = compute_adx(candles, 14);

    // Bollinger Bands(20, 2σ)
    let (bb_upper, bb_middle, bb_lower, bb_pct_b, bb_bw) = compute_bollinger(&closes, 20, 2.0);

    // OBV slope (last 20 bars normalized)
    let obv_slope = compute_obv_slope(candles, 20);

    // Volume ratio: latest / SMA20(volume)
    let volume_ratio = compute_volume_ratio(candles, 20);

    // ATR(14)
    let atr = compute_atr(candles, 14);

    // 52-week range — slice last 252 bars (or whatever is available) for daily;
    // for weekly that's 52 bars; for hourly we just use the entire window.
    let win = candles.len().min(252);
    let recent = &candles[candles.len() - win..];
    let high_52w = recent.iter().map(|c| c.high_cents).max();
    let low_52w = recent.iter().map(|c| c.low_cents).min();
    let pos_52w = match (high_52w, low_52w) {
        (Some(h), Some(l)) if h > l => {
            Some(((latest - l) as f64 / (h - l) as f64 * 100.0).clamp(0.0, 100.0))
        }
        _ => None,
    };

    Some(ChartSummary {
        latest_close_cents: latest,
        ema20_cents: ema20,
        ema50_cents: ema50,
        ema200_cents: ema200,
        macd_cents: macd_val,
        signal_cents: signal_val,
        histogram_cents: histogram_val,
        rsi,
        rsi_slope,
        adx,
        plus_di,
        minus_di,
        bb_upper_cents: bb_upper,
        bb_middle_cents: bb_middle,
        bb_lower_cents: bb_lower,
        bb_percent_b: bb_pct_b,
        bb_bandwidth: bb_bw,
        obv_slope,
        volume_ratio,
        atr_cents: atr,
        high_52w_cents: high_52w,
        low_52w_cents: low_52w,
        pos_52w_pct: pos_52w,
    })
}

// ── Indicator implementations ────────────────────────────────────────────────

/// Standard Wilder-smoothed RSI.
fn compute_rsi(closes: &[i64], period: usize) -> Option<f64> {
    if closes.len() <= period {
        return None;
    }
    let mut gains = 0.0f64;
    let mut losses = 0.0f64;
    for i in 1..=period {
        let d = closes[i] as f64 - closes[i - 1] as f64;
        if d > 0.0 {
            gains += d;
        } else {
            losses -= d;
        }
    }
    let mut avg_gain = gains / period as f64;
    let mut avg_loss = losses / period as f64;
    for i in (period + 1)..closes.len() {
        let d = closes[i] as f64 - closes[i - 1] as f64;
        let (g, l) = if d > 0.0 { (d, 0.0) } else { (0.0, -d) };
        avg_gain = (avg_gain * (period as f64 - 1.0) + g) / period as f64;
        avg_loss = (avg_loss * (period as f64 - 1.0) + l) / period as f64;
    }
    if avg_loss == 0.0 {
        return Some(100.0);
    }
    let rs = avg_gain / avg_loss;
    Some(100.0 - 100.0 / (1.0 + rs))
}

/// RSI computed for every bar (Wilder smoothing). First `period` entries are None.
pub(crate) fn compute_rsi_series(closes: &[i64], period: usize) -> Vec<Option<f64>> {
    let n = closes.len();
    let mut out: Vec<Option<f64>> = vec![None; n];
    if n <= period {
        return out;
    }

    let mut gains = 0.0f64;
    let mut losses = 0.0f64;
    for i in 1..=period {
        let d = closes[i] as f64 - closes[i - 1] as f64;
        if d > 0.0 {
            gains += d;
        } else {
            losses -= d;
        }
    }
    let mut avg_gain = gains / period as f64;
    let mut avg_loss = losses / period as f64;

    let rsi_value = |g: f64, l: f64| -> f64 {
        if l == 0.0 {
            100.0
        } else {
            100.0 - 100.0 / (1.0 + g / l)
        }
    };
    out[period] = Some(rsi_value(avg_gain, avg_loss));

    for i in (period + 1)..n {
        let d = closes[i] as f64 - closes[i - 1] as f64;
        let (g, l) = if d > 0.0 { (d, 0.0) } else { (0.0, -d) };
        avg_gain = (avg_gain * (period as f64 - 1.0) + g) / period as f64;
        avg_loss = (avg_loss * (period as f64 - 1.0) + l) / period as f64;
        out[i] = Some(rsi_value(avg_gain, avg_loss));
    }
    out
}

/// Find indices of local pivot highs (max within ±lookback window).
fn pivot_highs_idx(values: &[i64], lookback: usize) -> Vec<usize> {
    let mut out = Vec::new();
    if values.len() < lookback * 2 + 1 {
        return out;
    }
    for i in lookback..(values.len() - lookback) {
        let v = values[i];
        if (i - lookback..i)
            .chain(i + 1..=i + lookback)
            .all(|j| values[j] <= v)
        {
            out.push(i);
        }
    }
    out
}

/// Find indices of local pivot lows.
fn pivot_lows_idx(values: &[i64], lookback: usize) -> Vec<usize> {
    let mut out = Vec::new();
    if values.len() < lookback * 2 + 1 {
        return out;
    }
    for i in lookback..(values.len() - lookback) {
        let v = values[i];
        if (i - lookback..i)
            .chain(i + 1..=i + lookback)
            .all(|j| values[j] >= v)
        {
            out.push(i);
        }
    }
    out
}

/// Detect price/RSI divergences in the most recent `scan_window` bars.
/// Compares the last two pivot highs (for bearish divergences) and the last two pivot lows (for bullish).
pub fn detect_divergences(
    candles: &[HistoricalCandle],
    period: usize,
    lookback: usize,
    scan_window: usize,
) -> Vec<Divergence> {
    let mut out = Vec::new();
    let n = candles.len();
    if n < scan_window || n < period + lookback * 2 + 1 {
        return out;
    }

    let closes: Vec<i64> = candles.iter().map(|c| c.close_cents).collect();
    let rsi = compute_rsi_series(&closes, period);

    let start = n - scan_window;
    let highs: Vec<i64> = candles[start..].iter().map(|c| c.high_cents).collect();
    let lows: Vec<i64> = candles[start..].iter().map(|c| c.low_cents).collect();

    let pv_highs = pivot_highs_idx(&highs, lookback);
    let pv_lows = pivot_lows_idx(&lows, lookback);

    // Bearish divergences: compare last 2 price pivot highs
    if pv_highs.len() >= 2 {
        let i1_local = pv_highs[pv_highs.len() - 2];
        let i2_local = pv_highs[pv_highs.len() - 1];
        let p1 = highs[i1_local];
        let p2 = highs[i2_local];
        let r1 = rsi[start + i1_local];
        let r2 = rsi[start + i2_local];
        if let (Some(r1), Some(r2)) = (r1, r2) {
            let bars_ago = scan_window - i2_local - 1;
            if p2 > p1 && r2 < r1 {
                // Regular bearish: price made higher high, RSI made lower high
                let strength = ((r1 - r2) / 30.0).clamp(0.0, 1.0);
                out.push(Divergence {
                    kind: "RegularBearish",
                    label: "Divergencia bajista regular",
                    bias: "Bearish",
                    bars_ago,
                    strength,
                    price_at_p1: p1,
                    price_at_p2: p2,
                    rsi_at_p1: r1,
                    rsi_at_p2: r2,
                });
            } else if p2 < p1 && r2 > r1 {
                // Hidden bearish: price LH while RSI HH → continuation of downtrend
                let strength = ((r2 - r1) / 30.0).clamp(0.0, 1.0);
                out.push(Divergence {
                    kind: "HiddenBearish",
                    label: "Divergencia bajista oculta (continuación)",
                    bias: "Bearish",
                    bars_ago,
                    strength,
                    price_at_p1: p1,
                    price_at_p2: p2,
                    rsi_at_p1: r1,
                    rsi_at_p2: r2,
                });
            }
        }
    }

    // Bullish divergences: compare last 2 price pivot lows
    if pv_lows.len() >= 2 {
        let i1_local = pv_lows[pv_lows.len() - 2];
        let i2_local = pv_lows[pv_lows.len() - 1];
        let p1 = lows[i1_local];
        let p2 = lows[i2_local];
        let r1 = rsi[start + i1_local];
        let r2 = rsi[start + i2_local];
        if let (Some(r1), Some(r2)) = (r1, r2) {
            let bars_ago = scan_window - i2_local - 1;
            if p2 < p1 && r2 > r1 {
                // Regular bullish: price made lower low, RSI made higher low
                let strength = ((r2 - r1) / 30.0).clamp(0.0, 1.0);
                out.push(Divergence {
                    kind: "RegularBullish",
                    label: "Divergencia alcista regular",
                    bias: "Bullish",
                    bars_ago,
                    strength,
                    price_at_p1: p1,
                    price_at_p2: p2,
                    rsi_at_p1: r1,
                    rsi_at_p2: r2,
                });
            } else if p2 > p1 && r2 < r1 {
                // Hidden bullish: price HL while RSI LL → continuation of uptrend
                let strength = ((r1 - r2) / 30.0).clamp(0.0, 1.0);
                out.push(Divergence {
                    kind: "HiddenBullish",
                    label: "Divergencia alcista oculta (continuación)",
                    bias: "Bullish",
                    bars_ago,
                    strength,
                    price_at_p1: p1,
                    price_at_p2: p2,
                    rsi_at_p1: r1,
                    rsi_at_p2: r2,
                });
            }
        }
    }

    out
}

/// Average Directional Index — Wilder smoothing. Returns (ADX, +DI, -DI) in 0..100.
fn compute_adx(
    candles: &[HistoricalCandle],
    period: usize,
) -> (Option<f64>, Option<f64>, Option<f64>) {
    if candles.len() < period * 2 + 1 {
        return (None, None, None);
    }
    let n = candles.len();
    let mut tr = vec![0.0; n];
    let mut plus_dm = vec![0.0; n];
    let mut minus_dm = vec![0.0; n];
    for i in 1..n {
        let h = candles[i].high_cents as f64;
        let l = candles[i].low_cents as f64;
        let ph = candles[i - 1].high_cents as f64;
        let pl = candles[i - 1].low_cents as f64;
        let pc = candles[i - 1].close_cents as f64;
        tr[i] = (h - l).max((h - pc).abs()).max((l - pc).abs());
        let up = h - ph;
        let dn = pl - l;
        plus_dm[i] = if up > dn && up > 0.0 { up } else { 0.0 };
        minus_dm[i] = if dn > up && dn > 0.0 { dn } else { 0.0 };
    }
    // Wilder smoothing: initial sum, then EMA-ish
    let mut atr = tr[1..=period].iter().sum::<f64>();
    let mut pdm = plus_dm[1..=period].iter().sum::<f64>();
    let mut mdm = minus_dm[1..=period].iter().sum::<f64>();
    let mut adx_series = Vec::new();
    for i in (period + 1)..n {
        atr = atr - atr / period as f64 + tr[i];
        pdm = pdm - pdm / period as f64 + plus_dm[i];
        mdm = mdm - mdm / period as f64 + minus_dm[i];
        if atr == 0.0 {
            continue;
        }
        let plus_di = 100.0 * pdm / atr;
        let minus_di = 100.0 * mdm / atr;
        let dx_den = plus_di + minus_di;
        if dx_den == 0.0 {
            continue;
        }
        let dx = 100.0 * (plus_di - minus_di).abs() / dx_den;
        adx_series.push((dx, plus_di, minus_di));
    }
    if adx_series.len() < period {
        return (None, None, None);
    }
    // ADX = SMA of DX for the first period, then Wilder-smoothed
    let mut adx = adx_series[..period]
        .iter()
        .map(|(dx, _, _)| dx)
        .sum::<f64>()
        / period as f64;
    for &(dx, _, _) in adx_series[period..].iter() {
        adx = (adx * (period as f64 - 1.0) + dx) / period as f64;
    }
    let (_, last_plus, last_minus) = adx_series.last().unwrap();
    (Some(adx), Some(*last_plus), Some(*last_minus))
}

/// Bollinger Bands. Returns (upper, middle, lower, %B, bandwidth).
fn compute_bollinger(
    closes: &[i64],
    period: usize,
    k: f64,
) -> (
    Option<i64>,
    Option<i64>,
    Option<i64>,
    Option<f64>,
    Option<f64>,
) {
    if closes.len() < period {
        return (None, None, None, None, None);
    }
    let recent = &closes[closes.len() - period..];
    let mean = recent.iter().map(|&c| c as f64).sum::<f64>() / period as f64;
    let var = recent
        .iter()
        .map(|&c| (c as f64 - mean).powi(2))
        .sum::<f64>()
        / period as f64;
    let sd = var.sqrt();
    let upper = mean + k * sd;
    let lower = mean - k * sd;
    let close = *closes.last().unwrap() as f64;
    let pct_b = if upper > lower {
        (close - lower) / (upper - lower)
    } else {
        0.5
    };
    let bw = if mean > 0.0 {
        (upper - lower) / mean
    } else {
        0.0
    };
    (
        Some(upper.round() as i64),
        Some(mean.round() as i64),
        Some(lower.round() as i64),
        Some(pct_b),
        Some(bw),
    )
}

/// On-Balance Volume slope over the last `window` bars, normalized to roughly ±1.
fn compute_obv_slope(candles: &[HistoricalCandle], window: usize) -> Option<f64> {
    if candles.len() < window + 1 {
        return None;
    }
    let mut obv = 0.0f64;
    let mut obv_series = Vec::with_capacity(candles.len());
    obv_series.push(obv);
    for i in 1..candles.len() {
        if candles[i].close_cents > candles[i - 1].close_cents {
            obv += candles[i].volume as f64;
        } else if candles[i].close_cents < candles[i - 1].close_cents {
            obv -= candles[i].volume as f64;
        }
        obv_series.push(obv);
    }
    let n = obv_series.len();
    let recent = &obv_series[n - window..];
    let first = recent[0];
    let last = recent[recent.len() - 1];
    let denom = recent
        .iter()
        .map(|v| v.abs())
        .fold(0.0f64, f64::max)
        .max(1.0);
    Some(((last - first) / denom).clamp(-1.0, 1.0))
}

/// Latest RSI slope: EMA(3) of first differences of the Wilder RSI series
/// (mirrors Android ChartAnalysis.derivative on the RSI signal).
fn compute_rsi_slope(closes: &[i64], period: usize) -> Option<f64> {
    let series = compute_rsi_series(closes, period);
    let vals: Vec<f64> = series.into_iter().flatten().collect();
    if vals.len() < 3 {
        return None;
    }
    let mut diffs = Vec::with_capacity(vals.len());
    diffs.push(0.0);
    for i in 1..vals.len() {
        diffs.push(vals[i] - vals[i - 1]);
    }
    ema_f64(&diffs, 3)
}

/// Latest volume / median volume (Android ChartAnalysis.volumeRatioHundredths base ratio).
/// `period` is unused — kept for call-site compatibility; Android uses the full window median.
fn compute_volume_ratio(candles: &[HistoricalCandle], _period: usize) -> Option<f64> {
    if candles.is_empty() {
        return None;
    }
    let mut volumes: Vec<u64> = candles.iter().map(|c| c.volume).collect();
    if volumes.iter().all(|&v| v == 0) {
        return None;
    }
    volumes.sort_unstable();
    let n = volumes.len();
    let median = if n % 2 == 0 {
        (volumes[n / 2 - 1] as f64 + volumes[n / 2] as f64) / 2.0
    } else {
        volumes[n / 2] as f64
    };
    if median <= 0.0 {
        return None;
    }
    let latest = candles.last().unwrap().volume as f64;
    Some(latest / median)
}

pub(crate) fn compute_atr(candles: &[HistoricalCandle], period: usize) -> Option<i64> {
    if candles.len() < period + 1 {
        return None;
    }
    let n = candles.len();
    let mut tr = Vec::with_capacity(n);
    for i in 1..n {
        let h = candles[i].high_cents as f64;
        let l = candles[i].low_cents as f64;
        let pc = candles[i - 1].close_cents as f64;
        tr.push((h - l).max((h - pc).abs()).max((l - pc).abs()));
    }
    let mut atr = tr[..period].iter().sum::<f64>() / period as f64;
    for &t in &tr[period..] {
        atr = (atr * (period as f64 - 1.0) + t) / period as f64;
    }
    Some(atr.round() as i64)
}

// ── Candle patterns ──────────────────────────────────────────────────────────

/// Detect candlestick patterns on the most recent ~5 bars.
pub fn detect_candle_patterns(candles: &[HistoricalCandle]) -> Vec<DetectedPattern> {
    let mut out = Vec::new();
    let n = candles.len();
    if n < 3 {
        return out;
    }

    // Look at last 5 bars (or fewer)
    let scan = 5.min(n);
    for offset in 0..scan {
        let idx = n - 1 - offset;
        let c = &candles[idx];
        let body = (c.close_cents - c.open_cents).abs() as f64;
        let upper_wick = (c.high_cents - c.open_cents.max(c.close_cents)) as f64;
        let lower_wick = (c.open_cents.min(c.close_cents) - c.low_cents) as f64;
        let range = (c.high_cents - c.low_cents).max(1) as f64;

        // Doji: body < 10% of range
        if body / range < 0.10 && range > 0.0 {
            out.push(DetectedPattern {
                name: "Doji".into(),
                bias: "Neutral",
                bars_ago: offset,
            });
        }
        // Hammer: small body at the top, long lower wick (>2x body), little upper wick
        if body / range < 0.35 && lower_wick > 2.0 * body && upper_wick < body {
            out.push(DetectedPattern {
                name: "Hammer".into(),
                bias: "Bullish",
                bars_ago: offset,
            });
        }
        // Inverted Hammer / Shooting Star: small body at the bottom, long upper wick
        if body / range < 0.35 && upper_wick > 2.0 * body && lower_wick < body {
            // Bullish if appears after downtrend → just report as "Shooting Star" if recent close < open
            let name = if c.close_cents < c.open_cents {
                "Shooting Star"
            } else {
                "Inverted Hammer"
            };
            let bias = if name == "Shooting Star" {
                "Bearish"
            } else {
                "Bullish"
            };
            out.push(DetectedPattern {
                name: name.into(),
                bias,
                bars_ago: offset,
            });
        }

        // Engulfing patterns require previous bar
        if idx >= 1 {
            let p = &candles[idx - 1];
            let p_body = (p.close_cents - p.open_cents).abs();
            let c_body_signed = c.close_cents - c.open_cents;
            let p_body_signed = p.close_cents - p.open_cents;

            // Bullish engulfing: prev red, current green, current body > prev body
            if p_body_signed < 0
                && c_body_signed > 0
                && c.close_cents > p.open_cents
                && c.open_cents < p.close_cents
                && c_body_signed.abs() > p_body
            {
                out.push(DetectedPattern {
                    name: "Bullish Engulfing".into(),
                    bias: "Bullish",
                    bars_ago: offset,
                });
            }
            // Bearish engulfing
            if p_body_signed > 0
                && c_body_signed < 0
                && c.open_cents > p.close_cents
                && c.close_cents < p.open_cents
                && c_body_signed.abs() > p_body
            {
                out.push(DetectedPattern {
                    name: "Bearish Engulfing".into(),
                    bias: "Bearish",
                    bars_ago: offset,
                });
            }
        }

        // Morning Star / Evening Star (3-bar): use idx-2, idx-1, idx
        if idx >= 2 {
            let p2 = &candles[idx - 2];
            let p1 = &candles[idx - 1];
            let p2_body_signed = p2.close_cents - p2.open_cents;
            let p1_body = (p1.close_cents - p1.open_cents).abs();
            let p1_range = (p1.high_cents - p1.low_cents).max(1);
            let c_body_signed = c.close_cents - c.open_cents;

            // Small middle candle (star)
            let star_small = (p1_body as f64 / p1_range as f64) < 0.35;
            // Morning Star: bearish, star, strong bullish
            if p2_body_signed < 0
                && star_small
                && c_body_signed > 0
                && c.close_cents > (p2.open_cents + p2.close_cents) / 2
            {
                out.push(DetectedPattern {
                    name: "Morning Star".into(),
                    bias: "Bullish",
                    bars_ago: offset,
                });
            }
            // Evening Star: bullish, star, strong bearish
            if p2_body_signed > 0
                && star_small
                && c_body_signed < 0
                && c.close_cents < (p2.open_cents + p2.close_cents) / 2
            {
                out.push(DetectedPattern {
                    name: "Evening Star".into(),
                    bias: "Bearish",
                    bars_ago: offset,
                });
            }
        }
    }
    out
}

// ── Support / Resistance ─────────────────────────────────────────────────────

/// Detect local pivot highs/lows (within `lookback` bars) and return the levels
/// nearest to the latest close.
pub fn find_support_resistance(candles: &[HistoricalCandle], lookback: usize) -> SupportResistance {
    let mut out = SupportResistance::default();
    let n = candles.len();
    if n < lookback * 2 + 1 {
        return out;
    }
    let latest = candles[n - 1].close_cents;

    let mut pivots_high: Vec<i64> = Vec::new();
    let mut pivots_low: Vec<i64> = Vec::new();
    for i in lookback..(n - lookback) {
        let h = candles[i].high_cents;
        let l = candles[i].low_cents;
        let is_high = (i - lookback..i)
            .chain(i + 1..=i + lookback)
            .all(|j| candles[j].high_cents <= h);
        let is_low = (i - lookback..i)
            .chain(i + 1..=i + lookback)
            .all(|j| candles[j].low_cents >= l);
        if is_high {
            pivots_high.push(h);
        }
        if is_low {
            pivots_low.push(l);
        }
    }
    // De-dupe levels within 0.5% of each other
    let cluster = |mut v: Vec<i64>| -> Vec<i64> {
        v.sort_unstable();
        let mut out: Vec<i64> = Vec::new();
        for x in v {
            if out
                .last()
                .map(|&y| (x - y).abs() as f64 / y.max(1) as f64 > 0.005)
                .unwrap_or(true)
            {
                out.push(x);
            }
        }
        out
    };
    let highs = cluster(pivots_high);
    let lows = cluster(pivots_low);

    let mut resistances: Vec<i64> = highs.into_iter().filter(|&h| h > latest).collect();
    let mut supports: Vec<i64> = lows.into_iter().filter(|&l| l < latest).collect();
    resistances.sort_by_key(|&r| r - latest); // nearest first
    supports.sort_by_key(|&s| latest - s);
    out.resistances_cents = resistances.into_iter().take(3).collect();
    out.supports_cents = supports.into_iter().take(3).collect();
    out
}

// ── Multi-TF trend classifier ─────────────────────────────────────────────────

/// Classify a single timeframe as Bullish / Bearish / Neutral based on EMA stack.
fn classify_trend(c: &ChartSummary) -> &'static str {
    let close = c.latest_close_cents;
    if close <= 0 {
        return "Unknown";
    }
    let e20 = c.ema20_cents.unwrap_or(0);
    let e50 = c.ema50_cents.unwrap_or(0);
    let e200 = c.ema200_cents.unwrap_or(0);
    if e20 == 0 || e50 == 0 || e200 == 0 {
        return "Unknown";
    }
    let strong_bull = close > e20 && e20 > e50 && e50 > e200;
    let strong_bear = close < e20 && e20 < e50 && e50 < e200;
    if strong_bull {
        "Bullish"
    } else if strong_bear {
        "Bearish"
    } else {
        "Neutral"
    }
}

fn classify_alignment(w: &'static str, d: &'static str, h: &'static str) -> &'static str {
    if w == "Bullish" && d == "Bullish" && h == "Bullish" {
        "BullStack"
    } else if w == "Bearish" && d == "Bearish" && h == "Bearish" {
        "BearStack"
    } else if w == "Unknown" || d == "Unknown" {
        "Unknown"
    } else {
        "Mixed"
    }
}

// ── AggressiveV2 Scoring ──────────────────────────────────────────────────────

/// Smooth piecewise-linear ramp: -1 at or below lower, +1 at or above upper.
pub fn smooth_ramp(observed: f64, lower: f64, upper: f64) -> f64 {
    if observed <= lower {
        return -1.0;
    }
    if observed >= upper {
        return 1.0;
    }
    2.0 * (observed - lower) / (upper - lower) - 1.0
}

pub struct EvidenceAccumulator {
    norm_weight: f64,
    weighted_sum: f64,
    evidence_weight: f64,
    pub signals: Vec<String>,
}

impl EvidenceAccumulator {
    pub fn new(norm_weight: f64) -> Self {
        Self {
            norm_weight,
            weighted_sum: 0.0,
            evidence_weight: 0.0,
            signals: Vec::new(),
        }
    }

    pub fn add(&mut self, weight: f64, ramp: f64, label: &str) {
        let clamped = ramp.clamp(-1.0, 1.0);
        self.weighted_sum += weight * clamped;
        self.evidence_weight += weight;
        let suffix = if clamped >= 0.5 {
            "++"
        } else if clamped > 0.0 {
            "+"
        } else if clamped >= -0.5 {
            "-"
        } else {
            "--"
        };
        self.signals.push(format!("{}{}", label, suffix));
    }

    pub fn normalized_score(&self) -> Option<i32> {
        if self.evidence_weight == 0.0 {
            return None;
        }
        let n = (self.weighted_sum / self.norm_weight * 100.0).clamp(-100.0, 100.0);
        Some(n.round() as i32)
    }
}

fn analyst_coverage_reliability(count: Option<u32>) -> f64 {
    let count = match count {
        Some(c) if c >= V2_FORECAST_MIN_ANALYST_OPINIONS => c,
        _ => return 0.0,
    };
    let progress = ((count as f64 - V2_FORECAST_MIN_ANALYST_OPINIONS as f64)
        / (V2_FORECAST_FULL_ANALYST_OPINIONS - V2_FORECAST_MIN_ANALYST_OPINIONS as f64))
        .clamp(0.0, 1.0);
    0.35 + 0.65 * progress
}

fn analyst_breadth_ramp(count: u32) -> f64 {
    if count < V2_FORECAST_MIN_ANALYST_OPINIONS {
        return -1.0;
    }
    let progress = ((count as f64 - V2_FORECAST_MIN_ANALYST_OPINIONS as f64)
        / (V2_FORECAST_FULL_ANALYST_OPINIONS - V2_FORECAST_MIN_ANALYST_OPINIONS as f64))
        .clamp(0.0, 1.0);
    (-0.5 + 1.5 * progress).clamp(-1.0, 1.0)
}

/// Per-sector benchmarks (medians across the qualified universe).
/// Used to make Fundamentals scoring sector-relative: a P/E of 25x is cheap
/// for tech but expensive for utilities.
#[derive(Clone, Debug, Default)]
pub struct SectorBenchmarks {
    /// Median forward P/E (hundredths) for this sector.
    pub forward_pe_median: Option<u32>,
    /// Median ROE (bps) for this sector.
    pub roe_median_bps: Option<i32>,
}

/// Compute median forward P/E and ROE per sector from a slice of rows.
pub fn compute_sector_benchmarks(rows: &[CandidateRow]) -> HashMap<String, SectorBenchmarks> {
    let mut pe_by_sector: HashMap<String, Vec<u32>> = HashMap::new();
    let mut roe_by_sector: HashMap<String, Vec<i32>> = HashMap::new();

    for row in rows {
        let sector = match &row.sector_name {
            Some(s) if !s.is_empty() => s.clone(),
            _ => continue,
        };
        if let Some(pe) = row.forward_pe_hundredths {
            if pe > 0 {
                pe_by_sector.entry(sector.clone()).or_default().push(pe);
            }
        }
        if let Some(roe) = row.return_on_equity_bps {
            roe_by_sector.entry(sector).or_default().push(roe);
        }
    }

    fn median_u32(mut v: Vec<u32>) -> Option<u32> {
        if v.is_empty() {
            return None;
        }
        v.sort_unstable();
        Some(v[v.len() / 2])
    }
    fn median_i32(mut v: Vec<i32>) -> Option<i32> {
        if v.is_empty() {
            return None;
        }
        v.sort_unstable();
        Some(v[v.len() / 2])
    }

    let mut out: HashMap<String, SectorBenchmarks> = HashMap::new();
    for (sector, vals) in pe_by_sector {
        out.entry(sector).or_default().forward_pe_median = median_u32(vals);
    }
    for (sector, vals) in roe_by_sector {
        out.entry(sector).or_default().roe_median_bps = median_i32(vals);
    }
    // Only keep sectors with at least 3 samples worth of context (heuristic)
    out.retain(|_, b| b.forward_pe_median.is_some() || b.roe_median_bps.is_some());
    out
}

pub fn score_fundamentals_v2(
    row: &CandidateRow,
    bench: Option<&SectorBenchmarks>,
) -> (Option<i32>, Vec<String>) {
    let fcf = row.free_cash_flow_dollars;
    let ocf = row.operating_cash_flow_dollars;
    let market_cap = row.market_cap_dollars;
    let roe_bps = row.return_on_equity_bps;
    let growth_bps = row.earnings_growth_bps;
    let de = row.debt_to_equity_hundredths;
    let cash = row.total_cash_dollars;
    let debt = row.total_debt_dollars;
    let fwd_pe = row.forward_pe_hundredths;

    // Must have at least one data point
    if fcf.is_none()
        && ocf.is_none()
        && roe_bps.is_none()
        && growth_bps.is_none()
        && de.is_none()
        && cash.is_none()
        && fwd_pe.is_none()
    {
        return (None, vec![]);
    }

    let mut acc = EvidenceAccumulator::new(V2_FUNDAMENTALS_FULL_WEIGHT);

    match (fcf, market_cap) {
        (Some(f), Some(mc)) if mc > 0 => {
            let yield_frac = f as f64 / mc as f64;
            acc.add(
                V2_FUND_FCF_WEIGHT,
                smooth_ramp(yield_frac, V2_FUND_FCF_YIELD_LOWER, V2_FUND_FCF_YIELD_UPPER),
                "FCFy",
            );
        }
        (Some(f), _) => {
            acc.add(V2_FUND_FCF_WEIGHT, if f > 0 { 1.0 } else { -1.0 }, "FCF");
        }
        (None, _) => {
            if let Some(o) = ocf {
                acc.add(
                    V2_FUND_OCF_FALLBACK_WEIGHT,
                    if o > 0 { 1.0 } else { -1.0 },
                    "OCF",
                );
            }
        }
    }

    if let Some(roe) = roe_bps {
        // Sector-adjusted ramp: lower = sector median - 500bps, upper = sector median + 1500bps.
        // Falls back to absolute bounds if no sector benchmark.
        let (lower, upper, label) = match bench.and_then(|b| b.roe_median_bps) {
            Some(med) => ((med as f64) - 500.0, (med as f64) + 1500.0, "ROE§"),
            None => (V2_FUND_ROE_LOWER_BPS, V2_FUND_ROE_UPPER_BPS, "ROE"),
        };
        acc.add(
            V2_FUND_ROE_WEIGHT,
            smooth_ramp(roe as f64, lower, upper),
            label,
        );
    }

    if let Some(g) = growth_bps {
        acc.add(
            V2_FUND_GROWTH_WEIGHT,
            smooth_ramp(g as f64, V2_FUND_GROWTH_LOWER_BPS, V2_FUND_GROWTH_UPPER_BPS),
            "Growth",
        );
    }

    if let Some(d) = de {
        acc.add(
            V2_FUND_BALANCE_WEIGHT,
            -smooth_ramp(d as f64, V2_FUND_BALANCE_DE_LOW, V2_FUND_BALANCE_DE_HIGH),
            "D/E",
        );
    } else if let (Some(c), Some(d)) = (cash, debt) {
        acc.add(
            V2_FUND_BALANCE_WEIGHT,
            if c >= d { 1.0 } else { -0.5 },
            "Bal",
        );
    }

    if let Some(pe) = fwd_pe {
        if pe > 0 {
            // Sector-adjusted: cheap if < 70% of sector median, expensive if > 150% of median.
            // Negative sign because lower P/E is better.
            let (lower, upper, label) = match bench.and_then(|b| b.forward_pe_median) {
                Some(med) if med > 0 => {
                    let m = med as f64;
                    (m * 0.7, m * 1.5, "FwdPE§")
                }
                _ => (V2_FUND_PE_LOW, V2_FUND_PE_HIGH, "FwdPE"),
            };
            acc.add(
                V2_FUND_PE_WEIGHT,
                -smooth_ramp(pe as f64, lower, upper),
                label,
            );
        }
    }

    (acc.normalized_score(), acc.signals)
}

/// AggressiveV3 multi-timeframe technical scoring.
/// Combines weekly/daily/hourly into sub-buckets (Trend, Momentum, Volatility, Volume, Patterns).
/// Returns (composite_technical_score, signals, breakdown).
pub fn score_technicals_v3(
    weekly: Option<&ChartSummary>,
    daily: Option<&ChartSummary>,
    hourly: Option<&ChartSummary>,
    daily_candles: &[HistoricalCandle],
) -> (Option<i32>, Vec<String>, TechnicalBreakdown) {
    let mut signals: Vec<String> = Vec::new();
    let weekly_trend = weekly.map(classify_trend).unwrap_or("Unknown");
    let daily_trend = daily.map(classify_trend).unwrap_or("Unknown");
    let hourly_trend = hourly.map(classify_trend).unwrap_or("Unknown");
    let alignment = classify_alignment(weekly_trend, daily_trend, hourly_trend);

    // ── Trend sub-score: multi-TF alignment + ADX strength ───────────────────
    let trend_score = {
        let mut acc = 0.0f64;
        let mut weight = 0.0f64;
        // Each timeframe's classification: ±50 pts (with weekly counting more)
        let frame_pts = |t: &str| match t {
            "Bullish" => 1.0,
            "Bearish" => -1.0,
            _ => 0.0,
        };
        if weekly.is_some() {
            acc += 35.0 * frame_pts(weekly_trend);
            weight += 35.0;
        }
        if daily.is_some() {
            acc += 35.0 * frame_pts(daily_trend);
            weight += 35.0;
        }
        if hourly.is_some() {
            acc += 15.0 * frame_pts(hourly_trend);
            weight += 15.0;
        }
        // ADX (daily): strong trends get amplified, weak trends discounted
        if let Some(d) = daily {
            if let (Some(adx), Some(p), Some(m)) = (d.adx, d.plus_di, d.minus_di) {
                let strength = (adx / 50.0).min(1.0); // 0..1
                let dir = if p > m { 1.0 } else { -1.0 };
                acc += 15.0 * strength * dir;
                weight += 15.0;
                if adx > 25.0 {
                    signals.push(format!("ADX{}", if dir > 0.0 { "+" } else { "-" }));
                }
            }
        }
        if weight > 0.0 {
            Some(((acc / weight) * 100.0).round() as i32)
        } else {
            None
        }
    };
    if let Some(s) = trend_score {
        signals.push(format!(
            "Trend{}",
            if s > 0 {
                "+"
            } else if s < 0 {
                "-"
            } else {
                "·"
            }
        ));
    }

    // ── Momentum sub-score: RSI + MACD (daily) + multi-TF MACD alignment ─────
    let momentum_score = {
        let mut acc = 0.0f64;
        let mut weight = 0.0f64;
        if let Some(d) = daily {
            // RSI: ramp scaled so 30→-1, 70→+1 (with extreme 80+ capping toward bearish exhaustion)
            if let Some(rsi) = d.rsi {
                let r = smooth_ramp(rsi, 30.0, 70.0);
                // Extreme overbought: discount
                let r = if rsi > 80.0 {
                    r * 0.5
                } else if rsi < 20.0 {
                    r * 0.5
                } else {
                    r
                };
                acc += 30.0 * r;
                weight += 30.0;
                signals.push(format!(
                    "RSI{}",
                    if rsi >= 60.0 {
                        "+"
                    } else if rsi <= 40.0 {
                        "-"
                    } else {
                        "·"
                    }
                ));
            }
            if let (Some(macd), Some(sig)) = (d.macd_cents, d.signal_cents) {
                let dir = if macd > sig {
                    1.0
                } else if macd < sig {
                    -1.0
                } else {
                    0.0
                };
                acc += 25.0 * dir;
                weight += 25.0;
            }
            if let Some(h) = d.histogram_cents {
                if d.latest_close_cents > 0 {
                    let ratio = h as f64 / d.latest_close_cents as f64;
                    acc += 15.0 * smooth_ramp(ratio, -0.005, 0.005);
                    weight += 15.0;
                }
            }
        }
        // Hourly MACD direction confirms timing
        if let Some(h) = hourly {
            if let (Some(macd), Some(sig)) = (h.macd_cents, h.signal_cents) {
                let dir = if macd > sig {
                    1.0
                } else if macd < sig {
                    -1.0
                } else {
                    0.0
                };
                acc += 10.0 * dir;
                weight += 10.0;
            }
        }
        if weight > 0.0 {
            Some(((acc / weight) * 100.0).round() as i32)
        } else {
            None
        }
    };

    // ── Volatility sub-score: Bollinger %B + bandwidth context ───────────────
    let volatility_score = if let Some(d) = daily {
        match (d.bb_percent_b, d.bb_bandwidth) {
            (Some(pb), Some(bw)) => {
                // %B: <0.2 = touching lower band (oversold, mildly bullish for mean-revert)
                //     >0.8 = touching upper band (overbought, mildly bearish)
                let mean_revert = if pb < 0.2 {
                    0.7
                } else if pb > 0.8 {
                    -0.7
                } else {
                    0.0
                };
                // Bandwidth: very low = squeeze (breakout imminent, slight positive bias)
                let squeeze_bonus: f64 = if bw < 0.05 { 0.3 } else { 0.0 };
                if pb < 0.2 {
                    signals.push("BB-lower+".into());
                } else if pb > 0.8 {
                    signals.push("BB-upper-".into());
                }
                let raw: f64 = (mean_revert + squeeze_bonus).clamp(-1.0, 1.0);
                Some(((raw * 100.0).round()) as i32)
            }
            _ => None,
        }
    } else {
        None
    };

    // ── Volume sub-score: OBV trend + volume_ratio confirmation ──────────────
    let volume_score = if let Some(d) = daily {
        let mut acc = 0.0f64;
        let mut weight = 0.0f64;
        if let Some(obv) = d.obv_slope {
            acc += 60.0 * obv;
            weight += 60.0;
            if obv > 0.3 {
                signals.push("OBV+".into());
            } else if obv < -0.3 {
                signals.push("OBV-".into());
            }
        }
        if let Some(vr) = d.volume_ratio {
            // Volume spike with up bar = bullish, with down bar = bearish
            let last_close = d.latest_close_cents;
            let prev_close = daily_candles
                .iter()
                .rev()
                .nth(1)
                .map(|c| c.close_cents)
                .unwrap_or(last_close);
            let dir = if last_close > prev_close {
                1.0
            } else if last_close < prev_close {
                -1.0
            } else {
                0.0
            };
            // Ramp volume_ratio: 1.0 = neutral, 2.0+ = strong signal
            let intensity = ((vr - 1.0).max(0.0)).min(1.0);
            acc += 40.0 * intensity * dir;
            weight += 40.0;
        }
        if weight > 0.0 {
            Some(((acc / weight) * 100.0).round() as i32)
        } else {
            None
        }
    } else {
        None
    };

    // ── Pattern sub-score: candle patterns + divergences (computed below) ────
    let patterns = detect_candle_patterns(daily_candles);
    // We compute divergences here too so the pattern_score can use them
    let divergences_preview = detect_divergences(daily_candles, 14, 4, 60);
    let pattern_score = {
        let mut acc = 0.0f64;
        let mut total_w = 0.0f64;
        for p in &patterns {
            let recency_w = if p.bars_ago == 0 {
                1.0
            } else if p.bars_ago == 1 {
                0.6
            } else {
                0.3
            };
            let bias_val = match p.bias {
                "Bullish" => 1.0,
                "Bearish" => -1.0,
                _ => 0.0,
            };
            acc += recency_w * bias_val;
            total_w += recency_w;
            if p.bars_ago <= 1 {
                let suffix = match p.bias {
                    "Bullish" => "+",
                    "Bearish" => "-",
                    _ => "·",
                };
                signals.push(format!(
                    "{}{}",
                    p.name.split_whitespace().next().unwrap_or("Pat"),
                    suffix
                ));
            }
        }
        // Divergences contribute too — they're more reliable than single candles
        for div in &divergences_preview {
            // Hidden divergences are continuation, regular are reversal — weight regular higher
            let kind_w = if div.kind.starts_with("Regular") {
                1.5
            } else {
                1.0
            };
            let recency_w = if div.bars_ago <= 2 { 1.0 } else { 0.6 };
            let w = kind_w * recency_w * div.strength;
            let bias_val = if div.bias == "Bullish" { 1.0 } else { -1.0 };
            acc += w * bias_val;
            total_w += w;
        }
        if total_w > 0.0 {
            Some(((acc / total_w) * 60.0).clamp(-100.0, 100.0).round() as i32)
        } else {
            None
        }
    };

    // ── Levels ───────────────────────────────────────────────────────────────
    let levels = find_support_resistance(daily_candles, 5);

    // ── Divergences: scan last 60 daily bars, pivot lookback 4 ──────────────
    let divergences = divergences_preview;
    for div in &divergences {
        let suffix = if div.bias == "Bullish" { "+" } else { "-" };
        let tag = match div.kind {
            "RegularBullish" => "DivBull",
            "RegularBearish" => "DivBear",
            "HiddenBullish" => "HidBull",
            "HiddenBearish" => "HidBear",
            _ => "Div",
        };
        signals.push(format!("{}{}", tag, suffix));
    }

    if alignment == "BullStack" {
        signals.push("Stack++".into());
    } else if alignment == "BearStack" {
        signals.push("Stack--".into());
    }

    // ── Composite: weighted average of available sub-scores ──────────────────
    let composite = {
        let entries = [
            (trend_score, 35.0),
            (momentum_score, 25.0),
            (volatility_score, 10.0),
            (volume_score, 15.0),
            (pattern_score, 15.0),
        ];
        let mut acc = 0.0f64;
        let mut w = 0.0f64;
        for (score, weight) in entries {
            if let Some(s) = score {
                acc += s as f64 * weight;
                w += weight;
            }
        }
        if w > 0.0 {
            Some((acc / w).round() as i32)
        } else {
            None
        }
    };

    let breakdown = TechnicalBreakdown {
        trend_score,
        momentum_score,
        volatility_score,
        volume_score,
        pattern_score,
        alignment,
        weekly_trend,
        daily_trend,
        hourly_trend,
        patterns,
        levels,
        divergences,
    };
    (composite, signals, breakdown)
}

pub fn score_forecast_v2(row: &CandidateRow) -> (Option<i32>, Vec<String>) {
    let mut acc = EvidenceAccumulator::new(V2_FORECAST_FULL_WEIGHT);
    let mut reliable_evidence_weight = 0.0f64;
    let mut has_valuation_anchor = false;

    let target_fair = if row.intrinsic_value_cents > 0 {
        Some(row.intrinsic_value_cents)
    } else {
        None
    };
    let analyst_count = row.analyst_opinion_count;
    let rec_count = {
        let trend_sum = [
            row.strong_buy_count,
            row.buy_count,
            row.hold_count,
            row.sell_count,
            row.strong_sell_count,
        ]
        .iter()
        .filter_map(|&x| x)
        .sum::<u32>();
        let trend_opt = if trend_sum > 0 { Some(trend_sum) } else { None };
        [analyst_count, trend_opt].iter().filter_map(|&x| x).max()
    };
    let broadest = [analyst_count, rec_count].iter().filter_map(|&x| x).max();
    let reliability = analyst_coverage_reliability(analyst_count);
    let status_reliability: f64 = match row.signal_status {
        ExternalSignalStatus::Supportive | ExternalSignalStatus::Divergent => 1.0,
        ExternalSignalStatus::Stale => 0.25,
        ExternalSignalStatus::Missing => 0.0,
    };

    // Valuation component
    if let Some(fair) = target_fair {
        if analyst_count
            .map(|c| c >= V2_FORECAST_MIN_ANALYST_OPINIONS)
            .unwrap_or(false)
        {
            let upside_bps = if fair > 0 {
                ((fair - row.market_price_cents) as f64 / fair as f64 * 10_000.0) as f64
            } else {
                -10_000.0
            };
            let target_reliability = reliability * status_reliability;
            if target_reliability > 0.0 {
                let weight = V2_FORECAST_VALUATION_WEIGHT * target_reliability;
                acc.add(
                    weight,
                    smooth_ramp(
                        upside_bps,
                        V2_FORECAST_UPSIDE_LOWER_BPS,
                        V2_FORECAST_UPSIDE_UPPER_BPS,
                    ),
                    "Val",
                );
                reliable_evidence_weight += weight;
                has_valuation_anchor = true;
            }
        }
    }

    // Recommendation mean
    if let Some(rec) = row.recommendation_mean_hundredths {
        let rec_reliability = analyst_coverage_reliability(rec_count) * status_reliability;
        if rec_count
            .map(|c| c >= V2_FORECAST_MIN_ANALYST_OPINIONS)
            .unwrap_or(false)
            && rec_reliability > 0.0
        {
            let weight = V2_FORECAST_REC_WEIGHT * rec_reliability;
            acc.add(
                weight,
                -smooth_ramp(
                    rec as f64,
                    V2_FORECAST_REC_LOW_HUNDREDTHS,
                    V2_FORECAST_REC_HIGH_HUNDREDTHS,
                ),
                "Rec",
            );
            reliable_evidence_weight += weight;
        }
    }

    // Analyst breadth
    if let Some(count) = broadest {
        acc.add(
            V2_FORECAST_BREADTH_WEIGHT,
            analyst_breadth_ramp(count),
            "Cov",
        );
        reliable_evidence_weight +=
            V2_FORECAST_BREADTH_WEIGHT * analyst_coverage_reliability(Some(count));
    }

    // Uncertainty (spread between high and low targets)
    if let (Some(low), Some(high), Some(centre)) = (
        row.low_fair_value_cents,
        row.high_fair_value_cents,
        target_fair,
    ) {
        if centre > 0 && high > low {
            let spread = (high - low) as f64 / centre as f64;
            let target_reliability_no_freshness = reliability * status_reliability;
            if target_reliability_no_freshness > 0.0 {
                let weight = V2_FORECAST_UNCERTAINTY_WEIGHT * target_reliability_no_freshness;
                acc.add(
                    weight,
                    -smooth_ramp(spread, 0.0, V2_FORECAST_UNCERTAINTY_BOUND),
                    "Unc",
                );
                reliable_evidence_weight += weight;
            }
        }
    }

    // Freshness bonus (we don't track age so assume fresh = 1.0)
    if target_fair.is_some()
        && analyst_count
            .map(|c| c >= V2_FORECAST_MIN_ANALYST_OPINIONS)
            .unwrap_or(false)
    {
        let weight = V2_FORECAST_FRESHNESS_WEIGHT * reliability;
        acc.add(weight, 1.0, "Fresh"); // freshness multiplier = 1.0 (current data)
        reliable_evidence_weight += weight;
    }

    // DCF confirmation signal: independent valuation from SEC EDGAR filings
    // Weight: 15 pts. Positive if DCF > market price, scaled by upside magnitude.
    if let Some(dcf) = row.dcf_value_cents {
        if dcf > 0 {
            let dcf_upside_bps =
                ((dcf - row.market_price_cents) as f64 / dcf as f64 * 10_000.0) as f64;
            let weight = 15.0;
            acc.add(
                weight,
                smooth_ramp(
                    dcf_upside_bps,
                    V2_FORECAST_UPSIDE_LOWER_BPS,
                    V2_FORECAST_UPSIDE_UPPER_BPS,
                ),
                "DCF",
            );
            reliable_evidence_weight += weight;
            has_valuation_anchor = true;
        }
    }

    // Insider activity signal (Form 4, 90-day window).
    // Buying = strong positive (insiders rarely buy without conviction).
    // Selling alone = neutral (10b5-1 plans / diversification — too noisy).
    // Only counts when we have at least one Form 4 inspected.
    if let (Some(buys), Some(sells)) = (row.insider_buy_count, row.insider_sell_count) {
        if buys + sells > 0 {
            let weight = 10.0;
            let ramp = if buys >= 2 && buys >= sells {
                1.0 // multiple insiders buying → strong bullish
            } else if buys >= 1 && buys >= sells {
                0.5 // at least one buyer, no net selling
            } else if sells >= 5 && buys == 0 {
                -0.5 // heavy selling with zero buyers → mild bearish
            } else {
                0.0 // mixed or low activity
            };
            acc.add(weight, ramp, "Ins");
            reliable_evidence_weight += weight;
        }
    }

    let signals = acc.signals.clone();
    if !has_valuation_anchor || reliable_evidence_weight < V2_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT
    {
        return (None, signals);
    }

    let raw = match acc.normalized_score() {
        Some(s) => s.clamp(-100, 100),
        None => return (None, signals),
    };
    (Some(raw), signals)
}

pub fn composite_score_v2(fund: Option<i32>, tech: Option<i32>, forecast: Option<i32>) -> i32 {
    let coverage = [fund, tech, forecast]
        .iter()
        .filter(|x| x.is_some())
        .count() as i32;
    if coverage == 0 {
        return 0;
    }
    let sum = fund.unwrap_or(0) + tech.unwrap_or(0) + forecast.unwrap_or(0);
    let mean = sum as f64 / coverage as f64;
    let bonus = V2_COMPOSITE_COVERAGE_BONUS * (coverage - 1);
    let raw = (mean + bonus as f64).round() as i32;
    raw.clamp(-V2_COMPOSITE_BOUND, V2_COMPOSITE_BOUND)
}

/// Unified Setup Score (-100 to +100) combining all available factors into one signal.
/// Returns (score, label) where label is one of:
/// "StrongBuy" | "Buy" | "Accumulate" | "Watch" | "Hold" | "Avoid" | "StrongAvoid"
pub fn compute_setup_score(
    composite: i32,
    decision: &str,
    confidence: ConfidenceBand,
    gap_bps: Option<i32>,
    breakdown: Option<&TechnicalBreakdown>,
    fcf_dollars: Option<i64>,
    market_cap_dollars: Option<u64>,
    insider_buys: Option<u32>,
    insider_sells: Option<u32>,
    is_crypto_or_etf: bool,
) -> (i32, &'static str) {
    let mut score = composite as f64 * 0.7; // weight composite heavily but leave room for adjustments

    // ── Decision-level adjustments ───────────────────────────────────────────
    match decision {
        "Act" => score += 15.0,
        "Watch" => score -= 5.0,
        "Avoid" => score -= 30.0,
        _ => {}
    }

    // ── Multi-TF alignment (very high signal) ────────────────────────────────
    if let Some(b) = breakdown {
        match b.alignment {
            "BullStack" => score += 15.0,
            "BearStack" => score -= 15.0,
            _ => {}
        }
        // Patterns
        if let Some(p) = b.pattern_score {
            if p > 40 {
                score += 5.0;
            } else if p < -40 {
                score -= 5.0;
            }
        }
        // Recent divergences add weighty signal
        for div in &b.divergences {
            if div.bars_ago <= 2 {
                let kind_w = if div.kind.starts_with("Regular") {
                    8.0
                } else {
                    4.0
                };
                let bias_v = if div.bias == "Bullish" { 1.0 } else { -1.0 };
                score += kind_w * div.strength * bias_v;
            }
        }
    }

    // ── Confidence ───────────────────────────────────────────────────────────
    if !is_crypto_or_etf {
        match confidence {
            ConfidenceBand::High => score += 5.0,
            ConfidenceBand::Provisional => {}
            ConfidenceBand::Low => score -= 10.0,
        }
    }

    // ── Gap to analyst target (stocks only; missing target is neutral) ───────
    if !is_crypto_or_etf {
        match gap_bps {
            Some(g) if g <= 0 => score -= 20.0,
            Some(g) if g >= 2000 => score += 5.0,
            _ => {}
        }
    }

    // ── FCF veto / boost (stocks only) ───────────────────────────────────────
    if !is_crypto_or_etf {
        if let (Some(fcf), Some(mc)) = (fcf_dollars, market_cap_dollars) {
            if mc > 0 {
                let ratio = fcf as f64 / mc as f64;
                if ratio < -0.10 {
                    score -= 15.0;
                } else if ratio > 0.05 {
                    score += 5.0;
                }
            }
        }
    }

    // ── Insider activity ─────────────────────────────────────────────────────
    let buys = insider_buys.unwrap_or(0);
    let sells = insider_sells.unwrap_or(0);
    if buys >= 2 && buys >= sells {
        score += 5.0;
    } else if sells >= 5 && buys == 0 {
        score -= 5.0;
    }

    let final_score = score.round().clamp(-100.0, 100.0) as i32;

    let label = if final_score >= 70 {
        "StrongBuy"
    } else if final_score >= 50 {
        "Buy"
    } else if final_score >= 30 {
        "Accumulate"
    } else if final_score >= 10 {
        "Watch"
    } else if final_score >= -10 {
        "Hold"
    } else if final_score >= -40 {
        "Avoid"
    } else {
        "StrongAvoid"
    };

    (final_score, label)
}

/// Act / Watch / Avoid decision.
/// FCF veto: if the company is burning >10% of its market cap per year, cap at Watch.
/// Crypto path: decided by technical_score only (no gap, no fundamentals).
pub fn decision_state(
    confidence: ConfidenceBand,
    gap_bps: Option<i32>,
    composite: i32,
    fcf_dollars: Option<i64>,
    market_cap_dollars: Option<u64>,
    is_crypto_or_etf: bool,
    technical_score: Option<i32>,
) -> &'static str {
    // ── Crypto + ETFs: technical-only decision (no fundamentals/DCF) ─────────
    if is_crypto_or_etf {
        return match technical_score {
            Some(t) if t >= 30 => "Act",
            Some(t) if t >= 0 => "Watch",
            _ => "Avoid",
        };
    }

    // ── Stocks: original logic ───────────────────────────────────────────────
    // No usable target (None) is not Act-eligible — same spirit as gap ≤ 0.
    let gap_blocks_act = gap_bps.map(|g| g <= 0).unwrap_or(true);
    if confidence == ConfidenceBand::Low || gap_blocks_act || composite < DECISION_AVOID_THRESHOLD {
        return "Avoid";
    }
    // FCF veto: heavy cash burn caps Act → Watch
    let fcf_veto = match (fcf_dollars, market_cap_dollars) {
        (Some(fcf), Some(mc)) if mc > 0 => (fcf as f64 / mc as f64) < -0.10,
        (Some(fcf), None) => fcf < -1_000_000_000,
        _ => false,
    };
    if composite >= DECISION_ACT_THRESHOLD && !fcf_veto {
        "Act"
    } else {
        "Watch"
    }
}

// ── State ─────────────────────────────────────────────────────────────────────

#[derive(Default)]
pub struct ScreenerState {
    pub snapshots: HashMap<String, MarketSnapshot>,
    pub signals: HashMap<String, ExternalValuationSignal>,
    pub fundamentals: HashMap<String, FundamentalSnapshot>,
    pub chart_summaries: HashMap<String, ChartSummary>, // daily
    pub weekly_summaries: HashMap<String, ChartSummary>,
    pub hourly_summaries: HashMap<String, ChartSummary>,
    pub monthly_summaries: HashMap<String, ChartSummary>,
    pub daily_candles: HashMap<String, Vec<HistoricalCandle>>, // for pattern detection
    pub weekly_candles: HashMap<String, Vec<HistoricalCandle>>, // for crypto ATH/drawdown
    pub crypto_metrics: HashMap<String, crate::crypto_cycle::CryptoMetrics>,
    pub dcf_values: HashMap<String, i64>, // symbol → base dcf value in cents/share
    pub dcf_analyses: HashMap<String, crate::dcf_model::DcfAnalysis>,
    pub insider_data: HashMap<String, InsiderData>, // symbol → insider activity
    pub alerts: Vec<AlertEvent>,
    pub min_gap_bps: i32,
    pub signal_max_age_seconds: u64,
    /// Opportunity scoring model: aggressive_v2 | aggressive_v3 | short_v3
    pub scoring_model: String,
}

impl ScreenerState {
    pub fn new() -> Self {
        Self {
            min_gap_bps: DEFAULT_MIN_GAP_BPS,
            signal_max_age_seconds: DEFAULT_SIGNAL_MAX_AGE_SECONDS,
            scoring_model: "aggressive_v3".into(),
            ..Default::default()
        }
    }

    /// Drop all per-symbol market state while keeping scoring preferences.
    pub fn clear_universe(&mut self) {
        let scoring_model = self.scoring_model.clone();
        let min_gap_bps = self.min_gap_bps;
        let signal_max_age_seconds = self.signal_max_age_seconds;
        *self = Self {
            scoring_model,
            min_gap_bps,
            signal_max_age_seconds,
            ..Default::default()
        };
    }

    pub fn ingest_snapshot(&mut self, snap: MarketSnapshot) {
        let was_qualified = self.snapshots.get(&snap.symbol).map(|s| {
            let g = gap_bps(s.market_price_cents, s.intrinsic_value_cents);
            qualification(s.profitable, g, self.min_gap_bps) == QualificationStatus::Qualified
        });

        let g = gap_bps(snap.market_price_cents, snap.intrinsic_value_cents);
        let now_qualified =
            qualification(snap.profitable, g, self.min_gap_bps) == QualificationStatus::Qualified;

        let symbol = snap.symbol.clone();
        self.snapshots.insert(symbol.clone(), snap);

        let ts = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        match (was_qualified, now_qualified) {
            (Some(false), true) | (None, true) => {
                self.alerts.push(AlertEvent {
                    symbol,
                    kind: AlertKind::EnteredQualified,
                    timestamp_seconds: ts,
                });
            }
            (Some(true), false) => {
                self.alerts.push(AlertEvent {
                    symbol,
                    kind: AlertKind::ExitedQualified,
                    timestamp_seconds: ts,
                });
            }
            _ => {}
        }

        if self.alerts.len() > 200 {
            self.alerts.drain(0..100);
        }
    }

    pub fn ingest_signal(&mut self, mut signal: ExternalValuationSignal) {
        if let Some(previous) = self.signals.get(&signal.symbol) {
            macro_rules! preserve {
                ($($field:ident),+ $(,)?) => {$ (
                    if signal.$field.is_none() {
                        signal.$field = previous.$field;
                    }
                )+ };
            }
            preserve!(
                low_fair_value_cents,
                high_fair_value_cents,
                analyst_opinion_count,
                recommendation_mean_hundredths,
                strong_buy_count,
                buy_count,
                hold_count,
                sell_count,
                strong_sell_count,
                weighted_fair_value_cents,
                weighted_analyst_count,
            );
        }
        self.signals.insert(signal.symbol.clone(), signal);
    }

    /// Merge sparse quote fields without clearing known enrichment.
    pub fn ingest_snapshot_preserving_known(&mut self, mut snap: MarketSnapshot) {
        if let Some(previous) = self.snapshots.get(&snap.symbol) {
            if snap.company_name.is_none() {
                snap.company_name = previous.company_name.clone();
            }
            if snap.market_price_cents <= 0 {
                snap.market_price_cents = previous.market_price_cents;
            }
            if snap.intrinsic_value_cents <= 0 {
                snap.intrinsic_value_cents = previous.intrinsic_value_cents;
            }
            if snap.previous_close_cents <= 0 {
                snap.previous_close_cents = previous.previous_close_cents;
            }
            if snap.next_earnings_epoch.is_none() {
                snap.next_earnings_epoch = previous.next_earnings_epoch;
            }
        }
        self.ingest_snapshot(snap);
    }

    /// Merge a lightweight price/name refresh without treating its placeholder
    /// profitability as authoritative.
    pub fn ingest_partial_snapshot(&mut self, mut snap: MarketSnapshot) {
        if let Some(previous) = self.snapshots.get(&snap.symbol) {
            snap.profitable = previous.profitable;
        }
        self.ingest_snapshot_preserving_known(snap);
    }

    pub fn ingest_fundamentals(&mut self, mut fund: FundamentalSnapshot) {
        if let Some(previous) = self.fundamentals.get(&fund.symbol) {
            macro_rules! preserve {
                ($($field:ident),+ $(,)?) => {$ (
                    if fund.$field.is_none() {
                        fund.$field = previous.$field.clone();
                    }
                )+ };
            }
            preserve!(
                sector_key,
                sector_name,
                industry_key,
                industry_name,
                market_cap_dollars,
                shares_outstanding,
                trailing_pe_hundredths,
                forward_pe_hundredths,
                price_to_book_hundredths,
                return_on_equity_bps,
                ebitda_dollars,
                enterprise_value_dollars,
                enterprise_to_ebitda_hundredths,
                total_debt_dollars,
                total_cash_dollars,
                debt_to_equity_hundredths,
                free_cash_flow_dollars,
                operating_cash_flow_dollars,
                beta_millis,
                trailing_eps_cents,
                earnings_growth_bps,
            );
        }
        self.fundamentals.insert(fund.symbol.clone(), fund);
    }

    pub fn ingest_chart_summary(&mut self, symbol: String, summary: ChartSummary) {
        self.chart_summaries.insert(symbol, summary);
    }

    pub fn ingest_weekly_summary(&mut self, symbol: String, summary: ChartSummary) {
        self.weekly_summaries.insert(symbol, summary);
    }

    pub fn ingest_hourly_summary(&mut self, symbol: String, summary: ChartSummary) {
        self.hourly_summaries.insert(symbol, summary);
    }

    pub fn ingest_monthly_summary(&mut self, symbol: String, summary: ChartSummary) {
        self.monthly_summaries.insert(symbol, summary);
    }

    pub fn ingest_daily_candles(&mut self, symbol: String, candles: Vec<HistoricalCandle>) {
        self.daily_candles.insert(symbol, candles);
    }

    pub fn ingest_weekly_candles(&mut self, symbol: String, candles: Vec<HistoricalCandle>) {
        self.weekly_candles.insert(symbol, candles);
    }

    pub fn ingest_crypto_metrics(&mut self, symbol: String, m: crate::crypto_cycle::CryptoMetrics) {
        self.crypto_metrics.insert(symbol, m);
    }

    pub fn ingest_dcf(&mut self, symbol: String, value_cents: i64) {
        self.dcf_values.insert(symbol, value_cents);
    }

    pub fn ingest_dcf_analysis(&mut self, symbol: String, analysis: crate::dcf_model::DcfAnalysis) {
        self.dcf_values
            .insert(symbol.clone(), analysis.base_intrinsic_value_cents);
        self.dcf_analyses.insert(symbol, analysis);
    }

    pub fn ingest_insider(&mut self, symbol: String, data: InsiderData) {
        self.insider_data.insert(symbol, data);
    }

    pub fn candidate_rows(&self) -> Vec<CandidateRow> {
        let mut rows: Vec<CandidateRow> = self
            .snapshots
            .values()
            .map(|snap| {
                let signal = self.signals.get(&snap.symbol);
                // Prefer snapshot target; fall back to signal fair value so Objetivo/Gap
                // stay filled if only the valuation signal was updated.
                let intrinsic = if snap.intrinsic_value_cents > 0 {
                    snap.intrinsic_value_cents
                } else {
                    signal
                        .map(|s| s.fair_value_cents)
                        .filter(|&v| v > 0)
                        .unwrap_or(0)
                };
                let g = gap_bps(snap.market_price_cents, intrinsic);
                let ss = external_signal_status(
                    signal,
                    snap.market_price_cents,
                    self.signal_max_age_seconds,
                );
                let qual = qualification(snap.profitable, g, self.min_gap_bps);
                let analyst_count = signal.and_then(|s| s.analyst_opinion_count);
                let conf = confidence(qual, ss, analyst_count);
                let fund = self.fundamentals.get(&snap.symbol);
                CandidateRow {
                    symbol: snap.symbol.clone(),
                    company_name: snap.company_name.clone(),
                    market_price_cents: snap.market_price_cents,
                    previous_close_cents: snap.previous_close_cents,
                    next_earnings_epoch: snap.next_earnings_epoch,
                    intrinsic_value_cents: intrinsic,
                    gap_bps: g,
                    qualification: qual,
                    confidence: conf,
                    signal_status: ss,
                    analyst_opinion_count: signal.and_then(|s| s.analyst_opinion_count),
                    recommendation_mean_hundredths: signal
                        .and_then(|s| s.recommendation_mean_hundredths),
                    sector_name: fund.and_then(|f| f.sector_name.clone()),
                    low_fair_value_cents: signal.and_then(|s| s.low_fair_value_cents),
                    high_fair_value_cents: signal.and_then(|s| s.high_fair_value_cents),
                    strong_buy_count: signal.and_then(|s| s.strong_buy_count),
                    buy_count: signal.and_then(|s| s.buy_count),
                    hold_count: signal.and_then(|s| s.hold_count),
                    sell_count: signal.and_then(|s| s.sell_count),
                    strong_sell_count: signal.and_then(|s| s.strong_sell_count),
                    free_cash_flow_dollars: fund.and_then(|f| f.free_cash_flow_dollars),
                    operating_cash_flow_dollars: fund.and_then(|f| f.operating_cash_flow_dollars),
                    market_cap_dollars: fund.and_then(|f| f.market_cap_dollars),
                    return_on_equity_bps: fund.and_then(|f| f.return_on_equity_bps),
                    earnings_growth_bps: fund.and_then(|f| f.earnings_growth_bps),
                    debt_to_equity_hundredths: fund.and_then(|f| f.debt_to_equity_hundredths),
                    total_cash_dollars: fund.and_then(|f| f.total_cash_dollars),
                    total_debt_dollars: fund.and_then(|f| f.total_debt_dollars),
                    forward_pe_hundredths: fund.and_then(|f| f.forward_pe_hundredths),
                    price_to_book_hundredths: fund.and_then(|f| f.price_to_book_hundredths),
                    enterprise_to_ebitda_hundredths: fund
                        .and_then(|f| f.enterprise_to_ebitda_hundredths),
                    beta_millis: fund.and_then(|f| f.beta_millis),
                    shares_outstanding: fund.and_then(|f| f.shares_outstanding),
                    dcf_value_cents: self.dcf_values.get(&snap.symbol).copied(),
                    insider_net_shares_90d: self
                        .insider_data
                        .get(&snap.symbol)
                        .map(|i| i.net_shares_90d),
                    insider_buy_count: self.insider_data.get(&snap.symbol).map(|i| i.buy_count),
                    insider_sell_count: self.insider_data.get(&snap.symbol).map(|i| i.sell_count),
                }
            })
            .collect();

        rows.sort_by(|a, b| {
            b.confidence
                .cmp(&a.confidence)
                .then(b.gap_bps.cmp(&a.gap_bps))
        });
        rows
    }

    pub fn detail(&self, symbol: &str) -> Option<SymbolDetail> {
        let snap = self.snapshots.get(symbol)?;
        let signal = self.signals.get(symbol);
        let intrinsic = if snap.intrinsic_value_cents > 0 {
            snap.intrinsic_value_cents
        } else {
            signal
                .map(|s| s.fair_value_cents)
                .filter(|&v| v > 0)
                .unwrap_or(0)
        };
        let g = gap_bps(snap.market_price_cents, intrinsic);
        let ss =
            external_signal_status(signal, snap.market_price_cents, self.signal_max_age_seconds);
        let qual = qualification(snap.profitable, g, self.min_gap_bps);
        let analyst_count = signal.and_then(|s| s.analyst_opinion_count);
        let conf = confidence(qual, ss, analyst_count);
        let fund = self
            .fundamentals
            .get(symbol)
            .cloned()
            .unwrap_or_else(|| FundamentalSnapshot {
                symbol: symbol.to_string(),
                ..Default::default()
            });

        Some(SymbolDetail {
            symbol: snap.symbol.clone(),
            company_name: snap.company_name.clone(),
            market_price_cents: snap.market_price_cents,
            intrinsic_value_cents: intrinsic,
            gap_bps: g,
            qualification: qual,
            confidence: conf,
            signal_status: ss,
            signal_age_seconds: signal.map(|s| s.age_seconds),
            low_fair_value_cents: signal.and_then(|s| s.low_fair_value_cents),
            high_fair_value_cents: signal.and_then(|s| s.high_fair_value_cents),
            analyst_opinion_count: signal.and_then(|s| s.analyst_opinion_count),
            recommendation_mean_hundredths: signal.and_then(|s| s.recommendation_mean_hundredths),
            strong_buy_count: signal.and_then(|s| s.strong_buy_count),
            buy_count: signal.and_then(|s| s.buy_count),
            hold_count: signal.and_then(|s| s.hold_count),
            sell_count: signal.and_then(|s| s.sell_count),
            strong_sell_count: signal.and_then(|s| s.strong_sell_count),
            fundamentals: fund,
            chart_summary: self.chart_summaries.get(symbol).cloned(),
            weekly_summary: self.weekly_summaries.get(symbol).cloned(),
            hourly_summary: self.hourly_summaries.get(symbol).cloned(),
            monthly_summary: self.monthly_summaries.get(symbol).cloned(),
            technical_breakdown: {
                let daily = self.chart_summaries.get(symbol);
                let weekly = self.weekly_summaries.get(symbol);
                let hourly = self.hourly_summaries.get(symbol);
                let candles = self.daily_candles.get(symbol);
                match (daily, candles) {
                    (Some(_), Some(c)) => {
                        let (_, _, breakdown) = score_technicals_v3(weekly, daily, hourly, c);
                        Some(breakdown)
                    }
                    _ => None,
                }
            },
            dcf_value_cents: self.dcf_values.get(symbol).copied(),
            dcf_analysis: self.dcf_analyses.get(symbol).cloned(),
            insider_net_shares_90d: self.insider_data.get(symbol).map(|i| i.net_shares_90d),
            insider_buy_count: self.insider_data.get(symbol).map(|i| i.buy_count),
            insider_sell_count: self.insider_data.get(symbol).map(|i| i.sell_count),
            next_earnings_epoch: snap.next_earnings_epoch,
            chart_patterns: self
                .daily_candles
                .get(symbol)
                .map(|c| crate::chart_patterns::detect(c, 3, "1D"))
                .unwrap_or_default(),
            fib: self
                .daily_candles
                .get(symbol)
                .and_then(|c| crate::fibonacci::analyze(c, "1D")),
        })
    }
}

// ── Domain helpers ─────────────────────────────────────────────────────────────

/// Discount to fair value in basis points: `(fair - market) / fair * 10_000`.
/// Android parity: `checkedGapBps` — `None` when there is no usable fair value.
/// Integer arithmetic only (no f64 → i32 cast of a magic sentinel).
pub fn checked_gap_bps(market_price_cents: i64, fair_value_cents: i64) -> Option<i32> {
    if fair_value_cents <= 0 {
        return None;
    }
    let scaled = ((fair_value_cents as i128 - market_price_cents as i128) * 10_000)
        / fair_value_cents as i128;
    Some(scaled.clamp(i32::MIN as i128 + 1, i32::MAX as i128) as i32)
}

pub fn gap_bps(market_price_cents: i64, intrinsic_value_cents: i64) -> Option<i32> {
    checked_gap_bps(market_price_cents, intrinsic_value_cents)
}

pub fn qualification(profitable: bool, gap: Option<i32>, min_gap_bps: i32) -> QualificationStatus {
    if !profitable {
        QualificationStatus::Unprofitable
    } else if gap.is_some_and(|g| g >= min_gap_bps) {
        QualificationStatus::Qualified
    } else {
        QualificationStatus::GapTooSmall
    }
}

pub fn external_signal_status(
    signal: Option<&ExternalValuationSignal>,
    market_price_cents: i64,
    max_age_seconds: u64,
) -> ExternalSignalStatus {
    match signal {
        None => ExternalSignalStatus::Missing,
        Some(s) => {
            if s.age_seconds > max_age_seconds {
                ExternalSignalStatus::Stale
            } else if s.fair_value_cents > market_price_cents {
                ExternalSignalStatus::Supportive
            } else {
                ExternalSignalStatus::Divergent
            }
        }
    }
}

pub fn confidence(
    qual: QualificationStatus,
    signal_status: ExternalSignalStatus,
    analyst_count: Option<u32>,
) -> ConfidenceBand {
    if qual != QualificationStatus::Qualified {
        return ConfidenceBand::Low;
    }
    match signal_status {
        ExternalSignalStatus::Missing => ConfidenceBand::Provisional,
        ExternalSignalStatus::Supportive => {
            // Require at least 3 analysts for High confidence; thin coverage = Provisional
            if analyst_count.unwrap_or(0) >= 3 {
                ConfidenceBand::High
            } else {
                ConfidenceBand::Provisional
            }
        }
        ExternalSignalStatus::Stale | ExternalSignalStatus::Divergent => ConfidenceBand::Low,
    }
}

#[cfg(test)]
mod gap_tests {
    use super::checked_gap_bps;

    #[test]
    fn missing_fair_value_is_none() {
        assert_eq!(checked_gap_bps(10_000, 0), None);
        assert_eq!(checked_gap_bps(10_000, -1), None);
    }

    #[test]
    fn discount_to_fair_value_positive() {
        // market 80, fair 100 → 20% discount = 2000 bps
        assert_eq!(checked_gap_bps(8_000, 10_000), Some(2_000));
    }

    #[test]
    fn premium_to_fair_value_negative() {
        // market 120, fair 100 → -20% = -2000 bps
        assert_eq!(checked_gap_bps(12_000, 10_000), Some(-2_000));
    }
}

#[cfg(test)]
mod progressive_merge_tests {
    use super::{ExternalValuationSignal, FundamentalSnapshot, MarketSnapshot, ScreenerState};

    #[test]
    fn partial_price_refresh_preserves_known_snapshot_enrichment() {
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

        state.ingest_partial_snapshot(MarketSnapshot {
            symbol: "AAPL".into(),
            company_name: None,
            profitable: false,
            market_price_cents: 20_500,
            intrinsic_value_cents: 0,
            previous_close_cents: 0,
            next_earnings_epoch: None,
        });

        let merged = state.snapshots.get("AAPL").unwrap();
        assert_eq!(merged.market_price_cents, 20_500);
        assert_eq!(merged.company_name.as_deref(), Some("Apple Inc."));
        assert!(merged.profitable);
        assert_eq!(merged.intrinsic_value_cents, 24_000);
        assert_eq!(merged.previous_close_cents, 19_500);
        assert_eq!(merged.next_earnings_epoch, Some(1_800_000_000));
    }

    #[test]
    fn sparse_fundamentals_refresh_preserves_known_fields() {
        let mut state = ScreenerState::new();
        state.ingest_fundamentals(FundamentalSnapshot {
            symbol: "AAPL".into(),
            sector_name: Some("Technology".into()),
            market_cap_dollars: Some(3_000_000_000_000),
            beta_millis: Some(1_200),
            ..Default::default()
        });

        state.ingest_fundamentals(FundamentalSnapshot {
            symbol: "AAPL".into(),
            free_cash_flow_dollars: Some(100_000_000_000),
            ..Default::default()
        });

        let merged = state.fundamentals.get("AAPL").unwrap();
        assert_eq!(merged.sector_name.as_deref(), Some("Technology"));
        assert_eq!(merged.market_cap_dollars, Some(3_000_000_000_000));
        assert_eq!(merged.beta_millis, Some(1_200));
        assert_eq!(merged.free_cash_flow_dollars, Some(100_000_000_000));
    }

    #[test]
    fn sparse_signal_refresh_preserves_known_analyst_fields() {
        let mut state = ScreenerState::new();
        state.ingest_signal(ExternalValuationSignal {
            symbol: "AAPL".into(),
            fair_value_cents: 24_000,
            age_seconds: 0,
            low_fair_value_cents: Some(22_000),
            high_fair_value_cents: Some(28_000),
            analyst_opinion_count: Some(40),
            recommendation_mean_hundredths: Some(175),
            strong_buy_count: Some(10),
            buy_count: Some(20),
            hold_count: Some(8),
            sell_count: Some(2),
            strong_sell_count: Some(0),
            weighted_fair_value_cents: Some(25_000),
            weighted_analyst_count: Some(35),
        });

        state.ingest_signal(ExternalValuationSignal {
            symbol: "AAPL".into(),
            fair_value_cents: 25_000,
            age_seconds: 0,
            low_fair_value_cents: None,
            high_fair_value_cents: None,
            analyst_opinion_count: None,
            recommendation_mean_hundredths: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            weighted_fair_value_cents: None,
            weighted_analyst_count: None,
        });

        let merged = state.signals.get("AAPL").unwrap();
        assert_eq!(merged.fair_value_cents, 25_000);
        assert_eq!(merged.analyst_opinion_count, Some(40));
        assert_eq!(merged.recommendation_mean_hundredths, Some(175));
        assert_eq!(merged.low_fair_value_cents, Some(22_000));
        assert_eq!(merged.weighted_analyst_count, Some(35));
    }
}
