//! Public types for Market Regime Engine v2.

use serde::Serialize;

pub const REGIME_VERSION: u32 = 2;

#[derive(Clone, Debug, Serialize)]
pub struct RegimeSignal {
    pub id: String,
    pub label_es: String,
    pub label_en: String,
    /// Contribution toward the pillar score, roughly −100..+100.
    pub contribution: i32,
    pub detail: Option<String>,
    /// Short human hint when |contribution| is material.
    pub hint_es: Option<String>,
    pub hint_en: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct RegimePillar {
    pub id: String,
    pub name_es: String,
    pub name_en: String,
    /// −100..+100 (for volatility pillar: higher = more stress / more hostile).
    pub score: i32,
    /// 0..10000 basis points of confidence.
    pub confidence_bps: u32,
    /// Weight used after confidence renormalization (bps of total env weights).
    pub weight_used_bps: u32,
    pub signals: Vec<RegimeSignal>,
    pub stale: bool,
    /// Human interpretation of this pillar (1–2 sentences).
    pub interpretation_es: String,
    pub interpretation_en: String,
    /// bullish | bearish | neutral | opportunity | caution
    pub tone: String,
    /// 0..100 radius for radar chart (edge = better). Volatility inverted; sentiment = contrarian opportunity.
    pub radar_radius: u32,
}

#[derive(Clone, Debug, Serialize)]
#[allow(clippy::struct_excessive_bools)]
pub struct MarketRegime {
    // ── Headline ────────────────────────────────────────────────────────────
    /// StrongBull | Bull | LateBull | Range | Correction | Bear | Capitulation | Snapback | Unknown
    pub primary_regime: String,
    /// StrongRiskOn | RiskOn | Neutral | RiskOff | Crisis | Unknown
    pub environment_band: String,
    /// BloodInStreets | Washout | SelectiveBuy | Accumulate | HealthyPullback |
    /// Deploy | TrendDeploy | Neutral | HoldTrim | Reduce | Euphoria | Distribute |
    /// Denial | Defend | UnstableBlowoff | Mixed | Unknown
    pub action_stance: String,
    /// Suggested equity exposure ceiling 15..100.
    pub suggested_exposure_pct: u32,
    pub cash_buffer_pct: u32,
    /// New-risk sizing multiplier in bps (2500 = 0.25× … 12500 = 1.25×).
    pub new_risk_multiplier_bps: i32,
    /// Add-bias −2..+2 (want to add vs reduce).
    pub add_bias: i32,
    pub prefer_quality: bool,
    pub global_confidence_bps: u32,

    // ── Composite scores ────────────────────────────────────────────────────
    pub environment_score: i32,
    pub sentiment_score: i32,
    pub quality_score: i32,

    pub pillars: Vec<RegimePillar>,

    // ── Raw chips ───────────────────────────────────────────────────────────
    pub vix: Option<f64>,
    pub vix_percentile_1y: Option<f64>,
    pub vix_term_ratio: Option<f64>,
    pub vix_state: String,
    pub cnn_fear_greed: Option<u32>,
    pub cnn_fear_greed_label: Option<String>,
    pub cnn_fear_greed_prev_close: Option<f64>,
    pub breadth_above_ma200_pct: Option<f64>,
    pub breadth_above_ma50_pct: Option<f64>,
    pub breadth_sample: usize,
    pub spy_above_ma200: Option<bool>,
    pub spy_price_cents: Option<i64>,
    pub spy_ma200_cents: Option<i64>,
    pub spy_drawdown_from_ath_pct: Option<f64>,
    pub credit_score: Option<i32>,
    pub leadership_score: Option<i32>,
    pub avg_corr_milli: Option<i32>,

    // ── Narrative ───────────────────────────────────────────────────────────
    pub thesis_es: String,
    pub thesis_en: String,
    /// Longer multi-sentence reading (environment + sentiment + action).
    pub reading_es: String,
    pub reading_en: String,
    /// Concrete policy bullets (3–5).
    pub action_bullets_es: Vec<String>,
    pub action_bullets_en: Vec<String>,
    pub notes_es: Vec<String>,
    pub notes_en: Vec<String>,
    pub warnings: Vec<String>,

    // ── Compat with old banner fields ───────────────────────────────────────
    /// Alias of environment_band for older clients (RiskOn|Neutral|RiskOff|Unknown).
    pub regime: String,

    pub as_of_epoch: i64,
    pub version: u32,
}

impl Default for MarketRegime {
    fn default() -> Self {
        Self {
            primary_regime: "Unknown".into(),
            environment_band: "Unknown".into(),
            action_stance: "Unknown".into(),
            suggested_exposure_pct: 60,
            cash_buffer_pct: 20,
            new_risk_multiplier_bps: 10_000,
            add_bias: 0,
            prefer_quality: false,
            global_confidence_bps: 0,
            environment_score: 0,
            sentiment_score: 0,
            quality_score: 0,
            pillars: vec![],
            vix: None,
            vix_percentile_1y: None,
            vix_term_ratio: None,
            vix_state: "Unknown".into(),
            cnn_fear_greed: None,
            cnn_fear_greed_label: None,
            cnn_fear_greed_prev_close: None,
            breadth_above_ma200_pct: None,
            breadth_above_ma50_pct: None,
            breadth_sample: 0,
            spy_above_ma200: None,
            spy_price_cents: None,
            spy_ma200_cents: None,
            spy_drawdown_from_ath_pct: None,
            credit_score: None,
            leadership_score: None,
            avg_corr_milli: None,
            thesis_es: String::new(),
            thesis_en: String::new(),
            reading_es: String::new(),
            reading_en: String::new(),
            action_bullets_es: vec![],
            action_bullets_en: vec![],
            notes_es: vec![],
            notes_en: vec![],
            warnings: vec![],
            regime: "Unknown".into(),
            as_of_epoch: 0,
            version: REGIME_VERSION,
        }
    }
}

#[derive(Clone, Debug)]
pub struct CnnFearGreed {
    pub score: f64,
    pub rating: String,
    pub previous_close: Option<f64>,
    #[allow(dead_code)]
    pub previous_1_week: Option<f64>,
    #[allow(dead_code)]
    pub historical: Vec<(i64, f64)>, // epoch_ms, score
    #[allow(dead_code)]
    pub fetched_at_epoch: i64,
}

#[derive(Clone, Debug, Default)]
pub struct PillarResult {
    pub score: i32,
    pub confidence_bps: u32,
    pub signals: Vec<RegimeSignal>,
    pub stale: bool,
}

#[derive(Clone, Debug, Default)]
pub struct BreadthSnapshot {
    pub above_ma200_pct: Option<f64>,
    pub above_ma50_pct: Option<f64>,
    pub sample: usize,
    pub pct_rsi_above_50: Option<f64>,
    pub pct_rsi_above_70: Option<f64>,
    pub pct_rsi_below_30: Option<f64>,
    pub pct_macd_positive: Option<f64>,
    pub pct_near_52w_high: Option<f64>,
    pub pct_near_52w_low: Option<f64>,
    pub median_pos_52w: Option<f64>,
}

#[derive(Clone, Debug, Default)]
pub struct CrossAssetSnapshot {
    pub credit_score: Option<i32>,
    pub equity_bond_score: Option<i32>,
    pub leadership_score: Option<i32>,
    pub small_large_score: Option<i32>,
    pub signals: Vec<RegimeSignal>,
    pub confidence_bps: u32,
}

#[derive(Clone, Debug, Default)]
pub struct VolSnapshot {
    pub vix: Option<f64>,
    pub vix_percentile_1y: Option<f64>,
    pub vix_term_ratio: Option<f64>,
    pub vix_state: String,
    #[allow(dead_code)]
    pub realized_vol_pct: Option<f64>,
    pub stress_score: i32,
    pub confidence_bps: u32,
    pub signals: Vec<RegimeSignal>,
}
