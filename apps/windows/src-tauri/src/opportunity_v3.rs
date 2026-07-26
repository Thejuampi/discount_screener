//! Android AggressiveV3 opportunity scoring (parity with OpportunityEngine.kt).
//!
//! Do not mix Windows multi-TF technicals into this path — Android ranks on a
//! single preferred daily chart summary + the three V3 buckets only.

use crate::dcf_model::DcfAnalysis;
use crate::engine::{
    smooth_ramp, CandidateRow, ChartSummary, EvidenceAccumulator, ExternalSignalStatus,
};

// ── V3 constants (Android OpportunityEngine) ─────────────────────────────────
const V3_FUND_FCF_YIELD_LOWER: f64 = -0.02;
const V3_FUND_FCF_YIELD_UPPER: f64 = 0.08;
const V3_FUND_FCF_WEIGHT: f64 = 22.0;
const V3_FUND_OCF_FALLBACK_WEIGHT: f64 = 10.0;
const V3_FUND_ROE_LOWER_BPS: f64 = 0.0;
const V3_FUND_ROE_UPPER_BPS: f64 = 2_000.0;
const V3_FUND_ROE_WEIGHT: f64 = 16.0;
const V3_FUND_GROWTH_LOWER_BPS: f64 = -500.0;
const V3_FUND_GROWTH_UPPER_BPS: f64 = 1_500.0;
const V3_FUND_GROWTH_WEIGHT: f64 = 12.0;
const V3_FUND_BALANCE_DE_LOW: f64 = 30.0;
const V3_FUND_BALANCE_DE_HIGH: f64 = 200.0;
const V3_FUND_BALANCE_WEIGHT: f64 = 16.0;
const V3_FUND_VALUATION_WEIGHT: f64 = 24.0;
const V3_FUND_PE_LOW: f64 = 800.0;
const V3_FUND_PE_HIGH: f64 = 3_500.0;
const V3_FUND_EV_EBITDA_LOW: f64 = 600.0;
const V3_FUND_EV_EBITDA_HIGH: f64 = 2_000.0;
const V3_FUND_PB_LOW: f64 = 100.0;
const V3_FUND_PB_HIGH: f64 = 500.0;
const V3_FUND_CASH_QUALITY_WEIGHT: f64 = 10.0;
const V3_FUNDAMENTALS_FULL_WEIGHT: f64 = 100.0;

const V3_TECH_TREND_DELTA_BOUND: f64 = 0.10;
const V3_TECH_TREND_PRICE_20_WEIGHT: f64 = 12.0;
const V3_TECH_TREND_20_50_WEIGHT: f64 = 18.0;
const V3_TECH_TREND_50_200_WEIGHT: f64 = 15.0;
const V3_TECH_HISTOGRAM_BOUND: f64 = 0.005;
const V3_TECH_HISTOGRAM_WEIGHT: f64 = 12.0;
const V3_TECH_MACD_DIRECTION_WEIGHT: f64 = 8.0;
const V3_TECH_RSI_WEIGHT: f64 = 25.0;
const V3_TECH_VOLUME_WEIGHT: f64 = 10.0;
const V3_TECH_RSI_SLOPE_BOUND: f64 = 2.0;
const V3_TECH_VOLUME_RATIO_LOW: f64 = 70.0;
const V3_TECH_VOLUME_RATIO_HIGH: f64 = 150.0;
const V3_TECHNICALS_FULL_WEIGHT: f64 = 100.0;

const V3_FORECAST_UPSIDE_LOWER_BPS: f64 = -2_000.0;
const V3_FORECAST_UPSIDE_UPPER_BPS: f64 = 5_000.0;
const V3_FORECAST_VALUATION_WEIGHT: f64 = 42.0;
const V3_FORECAST_REC_LOW_HUNDREDTHS: f64 = 150.0;
const V3_FORECAST_REC_HIGH_HUNDREDTHS: f64 = 300.0;
const V3_FORECAST_REC_WEIGHT: f64 = 12.0;
const V3_FORECAST_SKEW_WEIGHT: f64 = 12.0;
const V3_FORECAST_MIN_ANALYST_OPINIONS: u32 = 3;
const V3_FORECAST_FULL_ANALYST_OPINIONS: f64 = 15.0;
const V3_FORECAST_BREADTH_WEIGHT: f64 = 14.0;
const V3_FORECAST_UNCERTAINTY_BOUND: f64 = 0.6;
const V3_FORECAST_ANALYST_UNCERTAINTY_WEIGHT: f64 = 8.0;
const V3_FORECAST_DCF_UNCERTAINTY_WEIGHT: f64 = 8.0;
const V3_FORECAST_DCF_WIDTH_LOWER: f64 = 0.2;
const V3_FORECAST_DCF_WIDTH_UPPER: f64 = 1.0;
const V3_FORECAST_FRESHNESS_WEIGHT: f64 = 4.0;
const V3_FORECAST_DCF_RELIABILITY: f64 = 0.75;
const V3_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT: f64 = 25.0;
const V3_FORECAST_FULL_WEIGHT: f64 = V3_FORECAST_VALUATION_WEIGHT
    + V3_FORECAST_REC_WEIGHT
    + V3_FORECAST_SKEW_WEIGHT
    + V3_FORECAST_BREADTH_WEIGHT
    + V3_FORECAST_ANALYST_UNCERTAINTY_WEIGHT
    + V3_FORECAST_DCF_UNCERTAINTY_WEIGHT
    + V3_FORECAST_FRESHNESS_WEIGHT;

const V3_COMPOSITE_COVERAGE_BONUS: f64 = 5.0;
const V3_COMPOSITE_BOUND: i32 = 110;
const V3_BETA_HAIRCUT_MAX: f64 = 10.0;
const V3_BETA_LOW_MILLIS: f64 = 800.0;
const V3_BETA_HIGH_MILLIS: f64 = 1_600.0;

pub const V3_AVOID_BELOW: i32 = 0;
pub const V3_ACT_AT_OR_ABOVE: i32 = 30;

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ScoringModel {
    AggressiveV2,
    AggressiveV3,
    /// Inverse of AggressiveV3: ranks short opportunities (weak forecast / expensive / bearish).
    ShortV3,
}

impl ScoringModel {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::AggressiveV2 => "aggressive_v2",
            Self::AggressiveV3 => "aggressive_v3",
            Self::ShortV3 => "short_v3",
        }
    }

    /// Parse a stored model id. Unknown values fall back to AggressiveV3.
    pub fn parse(s: &str) -> Self {
        match s {
            "aggressive_v2" | "v2" => Self::AggressiveV2,
            "short_v3" | "short" => Self::ShortV3,
            _ => Self::AggressiveV3,
        }
    }
}

/// Negate a bucket score for short ranking. `None` stays missing (no evidence).
pub fn invert_bucket(score: Option<i32>) -> Option<i32> {
    score.map(|s| -s)
}

/// Pure inverse of a V3 composite so high-beta haircuts become short-friendly.
pub fn invert_composite(composite: i32) -> i32 {
    (-composite).clamp(-V3_COMPOSITE_BOUND, V3_COMPOSITE_BOUND)
}

pub fn score_fundamentals_v3(row: &CandidateRow) -> (Option<i32>, Vec<String>) {
    let has_any = row.free_cash_flow_dollars.is_some()
        || row.operating_cash_flow_dollars.is_some()
        || row.return_on_equity_bps.is_some()
        || row.earnings_growth_bps.is_some()
        || row.debt_to_equity_hundredths.is_some()
        || row.forward_pe_hundredths.is_some()
        || row.enterprise_to_ebitda_hundredths.is_some()
        || row.price_to_book_hundredths.is_some();
    if !has_any {
        return (None, vec![]);
    }

    let mut acc = EvidenceAccumulator::new(V3_FUNDAMENTALS_FULL_WEIGHT);
    match (row.free_cash_flow_dollars, row.market_cap_dollars) {
        (Some(f), Some(mc)) if mc > 0 => {
            let y = f as f64 / mc as f64;
            acc.add(
                V3_FUND_FCF_WEIGHT,
                smooth_ramp(y, V3_FUND_FCF_YIELD_LOWER, V3_FUND_FCF_YIELD_UPPER),
                "FCFy",
            );
        }
        (Some(f), _) => acc.add(V3_FUND_FCF_WEIGHT, if f > 0 { 1.0 } else { -1.0 }, "FCF"),
        (None, _) => {
            if let Some(o) = row.operating_cash_flow_dollars {
                acc.add(
                    V3_FUND_OCF_FALLBACK_WEIGHT,
                    if o > 0 { 1.0 } else { -1.0 },
                    "OCF",
                );
            }
        }
    }
    if let Some(roe) = row.return_on_equity_bps {
        acc.add(
            V3_FUND_ROE_WEIGHT,
            smooth_ramp(roe as f64, V3_FUND_ROE_LOWER_BPS, V3_FUND_ROE_UPPER_BPS),
            "ROE",
        );
    }
    if let Some(g) = row.earnings_growth_bps {
        acc.add(
            V3_FUND_GROWTH_WEIGHT,
            smooth_ramp(g as f64, V3_FUND_GROWTH_LOWER_BPS, V3_FUND_GROWTH_UPPER_BPS),
            "Growth",
        );
    }
    if let Some(d) = row.debt_to_equity_hundredths {
        acc.add(
            V3_FUND_BALANCE_WEIGHT,
            -smooth_ramp(d as f64, V3_FUND_BALANCE_DE_LOW, V3_FUND_BALANCE_DE_HIGH),
            "D/E",
        );
    } else if let (Some(c), Some(d)) = (row.total_cash_dollars, row.total_debt_dollars) {
        acc.add(
            V3_FUND_BALANCE_WEIGHT,
            if c >= d { 1.0 } else { -0.5 },
            "Bal",
        );
    }

    let mut valuation = Vec::new();
    if let Some(pe) = row.forward_pe_hundredths.filter(|&p| p > 0) {
        valuation.push(-smooth_ramp(pe as f64, V3_FUND_PE_LOW, V3_FUND_PE_HIGH));
    }
    if let Some(ev) = row.enterprise_to_ebitda_hundredths.filter(|&p| p > 0) {
        valuation.push(-smooth_ramp(
            ev as f64,
            V3_FUND_EV_EBITDA_LOW,
            V3_FUND_EV_EBITDA_HIGH,
        ));
    }
    if let Some(pb) = row.price_to_book_hundredths.filter(|&p| p > 0) {
        valuation.push(-smooth_ramp(pb as f64, V3_FUND_PB_LOW, V3_FUND_PB_HIGH));
    }
    if !valuation.is_empty() {
        let blended = valuation.iter().sum::<f64>() / valuation.len() as f64;
        let cov = valuation.len() as f64 / 3.0;
        acc.add(V3_FUND_VALUATION_WEIGHT * cov, blended, "Mult");
    }

    if let (Some(fcf), Some(ocf)) = (row.free_cash_flow_dollars, row.operating_cash_flow_dollars) {
        if ocf > 0 {
            let conv = fcf as f64 / ocf as f64;
            acc.add(
                V3_FUND_CASH_QUALITY_WEIGHT,
                smooth_ramp(conv, 0.0, 1.0),
                "Conv",
            );
        }
    }

    (acc.normalized_score(), acc.signals)
}

/// Android-style V3 technicals on a single daily chart summary (+ RSI/volume).
/// Pure V3 — do not blend with multi-TF Windows technicals.
pub fn score_opportunity_technicals_v3(
    summary: Option<&ChartSummary>,
) -> (Option<i32>, Vec<String>) {
    let Some(s) = summary else {
        return (None, vec![]);
    };
    let close = s.latest_close_cents;
    if close <= 0 {
        return (None, vec![]);
    }
    let mut acc = EvidenceAccumulator::new(V3_TECHNICALS_FULL_WEIGHT);

    if let Some(ema20) = s.ema20_cents.filter(|&e| e > 0) {
        let delta = (close - ema20) as f64 / ema20 as f64;
        acc.add(
            V3_TECH_TREND_PRICE_20_WEIGHT,
            smooth_ramp(delta, -V3_TECH_TREND_DELTA_BOUND, V3_TECH_TREND_DELTA_BOUND),
            "Px/20",
        );
    }
    if let (Some(e20), Some(e50)) = (s.ema20_cents, s.ema50_cents.filter(|&e| e > 0)) {
        let delta = (e20 - e50) as f64 / e50 as f64;
        acc.add(
            V3_TECH_TREND_20_50_WEIGHT,
            smooth_ramp(delta, -V3_TECH_TREND_DELTA_BOUND, V3_TECH_TREND_DELTA_BOUND),
            "20/50",
        );
    }
    if let (Some(e50), Some(e200)) = (s.ema50_cents, s.ema200_cents.filter(|&e| e > 0)) {
        let delta = (e50 - e200) as f64 / e200 as f64;
        acc.add(
            V3_TECH_TREND_50_200_WEIGHT,
            smooth_ramp(delta, -V3_TECH_TREND_DELTA_BOUND, V3_TECH_TREND_DELTA_BOUND),
            "50/200",
        );
    }
    if let Some(hist) = s.histogram_cents {
        let ratio = hist as f64 / close as f64;
        acc.add(
            V3_TECH_HISTOGRAM_WEIGHT,
            smooth_ramp(ratio, -V3_TECH_HISTOGRAM_BOUND, V3_TECH_HISTOGRAM_BOUND),
            "Hist",
        );
    }
    if let (Some(macd), Some(sig)) = (s.macd_cents, s.signal_cents) {
        let dir = if macd > sig {
            1.0
        } else if macd < sig {
            -1.0
        } else {
            0.0
        };
        acc.add(V3_TECH_MACD_DIRECTION_WEIGHT, dir, "MACD");
    }
    if let Some(rsi) = s.rsi {
        let level = v3_rsi_level_ramp(rsi);
        let slope_ramp = s
            .rsi_slope
            .map(|slope| smooth_ramp(slope, -V3_TECH_RSI_SLOPE_BOUND, V3_TECH_RSI_SLOPE_BOUND))
            .unwrap_or(0.0);
        let combined = (0.65 * level + 0.35 * slope_ramp).clamp(-1.0, 1.0);
        acc.add(V3_TECH_RSI_WEIGHT, combined, "RSI");
    }
    if let Some(vr) = s.volume_ratio {
        // ChartSummary stores ratio (1.0 = median); Android uses hundredths.
        let hundredths = vr * 100.0;
        acc.add(
            V3_TECH_VOLUME_WEIGHT,
            smooth_ramp(
                hundredths,
                V3_TECH_VOLUME_RATIO_LOW,
                V3_TECH_VOLUME_RATIO_HIGH,
            ),
            "Vol",
        );
    }

    (acc.normalized_score(), acc.signals)
}

pub fn v3_rsi_level_ramp(rsi: f64) -> f64 {
    if rsi <= 30.0 {
        -1.0
    } else if rsi <= 55.0 {
        smooth_ramp(rsi, 30.0, 55.0)
    } else if rsi <= 80.0 {
        let t = (rsi - 55.0) / (80.0 - 55.0);
        (1.0 - (t * 1.5)).clamp(-1.0, 1.0)
    } else {
        -0.5
    }
}

struct WeightedForecastRamp {
    ramp: f64,
    reliability: f64,
}

/// Android AggressiveV3 forecast: reliability-weighted valuation blend + gates.
pub fn score_forecast_v3(
    row: &CandidateRow,
    dcf: Option<&DcfAnalysis>,
) -> (Option<i32>, Vec<String>) {
    let mut acc = EvidenceAccumulator::new(V3_FORECAST_FULL_WEIGHT);
    let mut sufficiency = Vec::new();
    let mut reliable = 0.0_f64;
    let mut has_anchor = false;

    let target_fair = preferred_forecast_fair_value_cents(row);
    let target_count = target_analyst_count(row);
    let recommendation_count = recommendation_analyst_count(row);
    let broadest = [target_count, recommendation_count]
        .into_iter()
        .flatten()
        .max();
    // Live feed → treat as fresh (age unknown on Windows candidate rows).
    let external_freshness = 1.0_f64;
    let status_rel = external_status_reliability(row.signal_status);

    let mut valuation_inputs: Vec<WeightedForecastRamp> = Vec::new();

    if let Some(fair) = target_fair {
        if let Some(upside_bps) = checked_upside_bps(row.market_price_cents, fair) {
            let target_rel =
                v3_analyst_coverage_reliability(target_count) * external_freshness * status_rel;
            if !v3_has_sufficient_analyst_coverage(target_count) {
                sufficiency.push(if target_count.is_none() {
                    "Cov?".into()
                } else {
                    format!("Cov<{V3_FORECAST_MIN_ANALYST_OPINIONS}")
                });
            } else if target_rel > 0.0 {
                valuation_inputs.push(WeightedForecastRamp {
                    ramp: smooth_ramp(
                        upside_bps as f64,
                        V3_FORECAST_UPSIDE_LOWER_BPS,
                        V3_FORECAST_UPSIDE_UPPER_BPS,
                    ),
                    reliability: target_rel,
                });
            }
        }
    }

    if let Some(analysis) = dcf {
        if analysis.base_intrinsic_value_cents > 0 && row.market_price_cents > 0 {
            if let Some(margin_bps) =
                checked_upside_bps(row.market_price_cents, analysis.base_intrinsic_value_cents)
            {
                valuation_inputs.push(WeightedForecastRamp {
                    ramp: smooth_ramp(
                        margin_bps as f64,
                        V3_FORECAST_UPSIDE_LOWER_BPS,
                        V3_FORECAST_UPSIDE_UPPER_BPS,
                    ),
                    reliability: V3_FORECAST_DCF_RELIABILITY,
                });
            }
        }
    }

    if !valuation_inputs.is_empty() {
        let reliability_sum: f64 = valuation_inputs.iter().map(|v| v.reliability).sum();
        if reliability_sum > 0.0 {
            let blended = valuation_inputs
                .iter()
                .map(|v| v.ramp * v.reliability)
                .sum::<f64>()
                / reliability_sum;
            let weight = V3_FORECAST_VALUATION_WEIGHT * reliability_sum.min(1.0);
            acc.add(weight, blended, "Val");
            reliable += weight;
            has_anchor = true;
        }
    }

    if let Some(rec) = row.recommendation_mean_hundredths {
        let rec_rel =
            v3_analyst_coverage_reliability(recommendation_count) * external_freshness * status_rel;
        if !v3_has_sufficient_analyst_coverage(recommendation_count) {
            sufficiency.push(if recommendation_count.is_none() {
                "RecCov?".into()
            } else {
                format!("RecCov<{V3_FORECAST_MIN_ANALYST_OPINIONS}")
            });
        } else if rec_rel > 0.0 {
            let weight = V3_FORECAST_REC_WEIGHT * rec_rel;
            acc.add(
                weight,
                -smooth_ramp(
                    rec as f64,
                    V3_FORECAST_REC_LOW_HUNDREDTHS,
                    V3_FORECAST_REC_HIGH_HUNDREDTHS,
                ),
                "Rec",
            );
            reliable += weight;
        }
    }

    if let Some(skew) = v3_recommendation_skew(row) {
        let skew_rel =
            v3_analyst_coverage_reliability(recommendation_count) * external_freshness * status_rel;
        if v3_has_sufficient_analyst_coverage(recommendation_count) && skew_rel > 0.0 {
            let weight = V3_FORECAST_SKEW_WEIGHT * skew_rel;
            acc.add(weight, skew, "Skew");
            reliable += weight;
        }
    }

    if let Some(count) = broadest {
        acc.add(
            V3_FORECAST_BREADTH_WEIGHT,
            v3_analyst_breadth_ramp(count),
            "Cov",
        );
        reliable += V3_FORECAST_BREADTH_WEIGHT * v3_analyst_coverage_reliability(Some(count));
    }

    let target_rel_no_fresh = v3_analyst_coverage_reliability(target_count) * status_rel;
    if let (Some(lo), Some(hi), Some(centre)) = (
        row.low_fair_value_cents,
        row.high_fair_value_cents,
        target_fair,
    ) {
        if centre > 0 && hi > lo && target_rel_no_fresh > 0.0 {
            let spread = (hi - lo) as f64 / centre as f64;
            let weight = V3_FORECAST_ANALYST_UNCERTAINTY_WEIGHT * target_rel_no_fresh;
            acc.add(
                weight,
                -smooth_ramp(spread, 0.0, V3_FORECAST_UNCERTAINTY_BOUND),
                "Unc",
            );
            reliable += weight * external_freshness;
        }
    }

    if let Some(analysis) = dcf {
        let base = analysis.base_intrinsic_value_cents;
        let bear = analysis.bear_intrinsic_value_cents;
        let bull = analysis.bull_intrinsic_value_cents;
        if base > 0 && bull >= base && base >= bear && bull > bear {
            let width = (bull - bear) as f64 / base as f64;
            acc.add(
                V3_FORECAST_DCF_UNCERTAINTY_WEIGHT,
                -smooth_ramp(
                    width,
                    V3_FORECAST_DCF_WIDTH_LOWER,
                    V3_FORECAST_DCF_WIDTH_UPPER,
                ),
                "DcfUnc",
            );
            reliable += V3_FORECAST_DCF_UNCERTAINTY_WEIGHT;
        }
    }

    if target_fair.is_some() && v3_has_sufficient_analyst_coverage(target_count) {
        let weight = V3_FORECAST_FRESHNESS_WEIGHT * v3_analyst_coverage_reliability(target_count);
        // freshnessRamp(1.0) = 1.0
        acc.add(weight, freshness_ramp(external_freshness), "Fresh");
        reliable += weight * external_freshness;
    }

    let mut signals = acc.signals.clone();
    for s in sufficiency {
        if !signals.contains(&s) {
            signals.push(s);
        }
    }
    if !has_anchor || reliable < V3_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT {
        return (None, signals);
    }
    match acc.normalized_score() {
        Some(s) => (Some(s.clamp(-100, 100)), signals),
        None => (None, signals),
    }
}

fn preferred_forecast_fair_value_cents(row: &CandidateRow) -> Option<i64> {
    // Windows maps Yahoo target mean into intrinsic_value_cents on the snapshot.
    if row.intrinsic_value_cents > 0 {
        Some(row.intrinsic_value_cents)
    } else {
        None
    }
}

fn target_analyst_count(row: &CandidateRow) -> Option<u32> {
    if preferred_forecast_fair_value_cents(row).is_some() {
        row.analyst_opinion_count
    } else {
        None
    }
}

fn recommendation_analyst_count(row: &CandidateRow) -> Option<u32> {
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
    let trend = if trend_sum > 0 { Some(trend_sum) } else { None };
    [row.analyst_opinion_count, trend]
        .into_iter()
        .flatten()
        .max()
}

fn checked_upside_bps(market_price_cents: i64, fair_value_cents: i64) -> Option<i32> {
    if market_price_cents <= 0 || fair_value_cents <= 0 {
        return None;
    }
    let bps =
        ((fair_value_cents - market_price_cents) as f64 / market_price_cents as f64) * 10_000.0;
    Some(bps.round() as i32)
}

fn v3_recommendation_skew(row: &CandidateRow) -> Option<f64> {
    let strong_buy = row.strong_buy_count.unwrap_or(0);
    let buy = row.buy_count.unwrap_or(0);
    let hold = row.hold_count.unwrap_or(0);
    let sell = row.sell_count.unwrap_or(0);
    let strong_sell = row.strong_sell_count.unwrap_or(0);
    let total = strong_buy + buy + hold + sell + strong_sell;
    if total == 0 {
        return None;
    }
    let bullish = (strong_buy + buy) as f64;
    let bearish = (sell + strong_sell) as f64;
    Some(((bullish - bearish) / total as f64).clamp(-1.0, 1.0))
}

fn v3_has_sufficient_analyst_coverage(count: Option<u32>) -> bool {
    count.is_some_and(|c| c >= V3_FORECAST_MIN_ANALYST_OPINIONS)
}

fn v3_analyst_coverage_reliability(count: Option<u32>) -> f64 {
    if !v3_has_sufficient_analyst_coverage(count) {
        return 0.0;
    }
    let c = count.unwrap() as f64;
    let progress = ((c - V3_FORECAST_MIN_ANALYST_OPINIONS as f64)
        / (V3_FORECAST_FULL_ANALYST_OPINIONS - V3_FORECAST_MIN_ANALYST_OPINIONS as f64))
        .clamp(0.0, 1.0);
    0.35 + 0.65 * progress
}

fn v3_analyst_breadth_ramp(count: u32) -> f64 {
    if count < V3_FORECAST_MIN_ANALYST_OPINIONS {
        return -1.0;
    }
    let progress = ((count as f64 - V3_FORECAST_MIN_ANALYST_OPINIONS as f64)
        / (V3_FORECAST_FULL_ANALYST_OPINIONS - V3_FORECAST_MIN_ANALYST_OPINIONS as f64))
        .clamp(0.0, 1.0);
    (-0.5 + 1.5 * progress).clamp(-1.0, 1.0)
}

fn external_status_reliability(status: ExternalSignalStatus) -> f64 {
    match status {
        ExternalSignalStatus::Supportive | ExternalSignalStatus::Divergent => 1.0,
        ExternalSignalStatus::Stale => 0.25,
        ExternalSignalStatus::Missing => 0.0,
    }
}

fn freshness_ramp(multiplier: f64) -> f64 {
    (2.0 * multiplier - 1.0).clamp(-1.0, 1.0)
}

/// Android V3 composite: coverage-weighted mean + bonus, then beta haircut.
pub fn composite_score_v3(
    fund: Option<i32>,
    tech: Option<i32>,
    forecast: Option<i32>,
    beta_millis: Option<i32>,
) -> i32 {
    composite_score_v3_ext(fund, tech, forecast, None, beta_millis, 1.0)
}

/// V3 composite with optional 4th bucket (regime fit) and beta haircut multiplier.
///
/// When `regime` is `None`, behavior matches classic three-bucket V3 (parity).
/// Coverage bonus still scales with number of present buckets (max +15 with 4).
pub fn composite_score_v3_ext(
    fund: Option<i32>,
    tech: Option<i32>,
    forecast: Option<i32>,
    regime: Option<i32>,
    beta_millis: Option<i32>,
    beta_haircut_mult: f64,
) -> i32 {
    let buckets = [fund, tech, forecast, regime];
    let coverage = buckets.iter().filter(|x| x.is_some()).count();
    if coverage == 0 {
        return 0;
    }
    let sum = fund.unwrap_or(0) + tech.unwrap_or(0) + forecast.unwrap_or(0) + regime.unwrap_or(0);
    let mean = sum as f64 / coverage as f64;
    let bonus = V3_COMPOSITE_COVERAGE_BONUS * (coverage as f64 - 1.0);
    let base = (mean + bonus).clamp(-V3_COMPOSITE_BOUND as f64, V3_COMPOSITE_BOUND as f64);
    let haircut = beta_risk_haircut(beta_millis) * beta_haircut_mult.clamp(0.0, 2.5);
    ((base - haircut).round() as i32).clamp(-V3_COMPOSITE_BOUND, V3_COMPOSITE_BOUND)
}

/// Assemble a Short V3 composite from already short-oriented buckets.
///
/// The legacy three-dimensional path remains the exact inverse of Long V3.
/// With context, all short-oriented buckets are converted back to Long space
/// together, then the complete V3 result is inverted once. This preserves the
/// inverse coverage-bonus and beta-haircut semantics without reversing context
/// relative to the other short evidence.
pub fn composite_score_v3_short_ext(
    fund: Option<i32>,
    tech: Option<i32>,
    forecast: Option<i32>,
    regime: Option<i32>,
    beta_millis: Option<i32>,
    beta_haircut_mult: f64,
) -> i32 {
    match regime {
        Some(regime) => invert_composite(composite_score_v3_ext(
            invert_bucket(fund),
            invert_bucket(tech),
            invert_bucket(forecast),
            invert_bucket(Some(regime)),
            beta_millis,
            beta_haircut_mult,
        )),
        None => invert_composite(composite_score_v3(
            invert_bucket(fund),
            invert_bucket(tech),
            invert_bucket(forecast),
            beta_millis,
        )),
    }
}

fn beta_risk_haircut(beta_millis: Option<i32>) -> f64 {
    let Some(b) = beta_millis else {
        return 0.0;
    };
    let t = smooth_ramp(b as f64, V3_BETA_LOW_MILLIS, V3_BETA_HIGH_MILLIS);
    ((t + 1.0) / 2.0) * V3_BETA_HAIRCUT_MAX
}

pub fn decision_state_v3(composite: i32) -> &'static str {
    if composite >= V3_ACT_AT_OR_ABOVE {
        "Act"
    } else if composite < V3_AVOID_BELOW {
        "Avoid"
    } else {
        "Watch"
    }
}

/// Setup column for V3 = pure composite (no Windows multi-TF setup adjustments).
pub fn setup_from_v3_composite(composite: i32) -> (i32, &'static str) {
    let label = if composite >= 50 {
        "StrongBuy"
    } else if composite >= V3_ACT_AT_OR_ABOVE {
        "Buy"
    } else if composite >= 15 {
        "Accumulate"
    } else if composite >= 0 {
        "Watch"
    } else if composite >= -20 {
        "Hold"
    } else if composite >= -40 {
        "Avoid"
    } else {
        "StrongAvoid"
    };
    (composite, label)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rsi_level_ramp_mid_zone_positive() {
        assert!((v3_rsi_level_ramp(55.0) - 1.0).abs() < 1e-9);
        assert!(v3_rsi_level_ramp(30.0) < 0.0);
        assert!(v3_rsi_level_ramp(85.0) < 0.0);
    }

    #[test]
    fn decision_cutoffs_continuous() {
        assert_eq!(decision_state_v3(30), "Act");
        assert_eq!(decision_state_v3(29), "Watch");
        assert_eq!(decision_state_v3(-1), "Avoid");
    }

    #[test]
    fn composite_coverage_bonus_three_buckets() {
        // mean of 40,40,40 = 40 + bonus 10 = 50
        assert_eq!(composite_score_v3(Some(40), Some(40), Some(40), None), 50);
    }

    #[test]
    fn four_bucket_coverage_bonus_and_parity() {
        // Classic 3-bucket path equals ext with regime=None
        let a = composite_score_v3(Some(40), Some(40), Some(40), Some(1000));
        let b = composite_score_v3_ext(Some(40), Some(40), Some(40), None, Some(1000), 1.0);
        assert_eq!(a, b);
        // 4 equal buckets: mean 40 + bonus 15 = 55
        let c = composite_score_v3_ext(Some(40), Some(40), Some(40), Some(40), None, 1.0);
        assert_eq!(c, 55);
        // Positive regime fit lifts vs zero regime absent when others fixed
        let with = composite_score_v3_ext(Some(40), Some(40), Some(40), Some(60), None, 1.0);
        let without = composite_score_v3_ext(Some(40), Some(40), Some(40), None, None, 1.0);
        assert!(with > without, "with={with} without={without}");
    }

    #[test]
    fn short_extended_composite_preserves_inverse_v3_semantics() {
        let supportive = composite_score_v3_short_ext(
            Some(-40),
            Some(-20),
            Some(-10),
            Some(60),
            Some(1_200),
            1.4,
        );
        let adverse = composite_score_v3_short_ext(
            Some(-40),
            Some(-20),
            Some(-10),
            Some(-60),
            Some(1_200),
            1.4,
        );
        assert!(
            supportive > adverse,
            "supportive={supportive} adverse={adverse}"
        );
        let expected = invert_composite(composite_score_v3_ext(
            Some(40),
            Some(20),
            Some(10),
            Some(-60),
            Some(1_200),
            1.4,
        ));
        assert_eq!(
            supportive, expected,
            "Short must preserve inverse-V3 coverage bonus and beta haircut semantics"
        );
    }

    #[test]
    fn short_extended_composite_has_exact_classic_parity_without_context() {
        let classic = invert_composite(composite_score_v3(
            Some(40),
            Some(20),
            Some(10),
            Some(1_200),
        ));
        assert_eq!(
            composite_score_v3_short_ext(Some(-40), Some(-20), Some(-10), None, Some(1_200), 1.0,),
            classic
        );
    }

    #[test]
    fn high_beta_haircuts_composite() {
        let low = composite_score_v3(Some(40), Some(40), Some(40), Some(700));
        let high = composite_score_v3(Some(40), Some(40), Some(40), Some(1_800));
        assert!(low > high, "low={low} high={high}");
    }

    #[test]
    fn missing_beta_not_penalized() {
        let a = composite_score_v3(Some(40), Some(40), None, None);
        let b = composite_score_v3(Some(40), Some(40), None, Some(700));
        assert_eq!(a, b);
    }

    #[test]
    fn setup_from_v3_equals_composite() {
        let (s, _) = setup_from_v3_composite(42);
        assert_eq!(s, 42);
    }

    #[test]
    fn scoring_model_parse_and_as_str_round_trip() {
        assert_eq!(
            ScoringModel::parse("aggressive_v2"),
            ScoringModel::AggressiveV2
        );
        assert_eq!(ScoringModel::parse("v2"), ScoringModel::AggressiveV2);
        assert_eq!(
            ScoringModel::parse("aggressive_v3"),
            ScoringModel::AggressiveV3
        );
        assert_eq!(ScoringModel::parse("short_v3"), ScoringModel::ShortV3);
        assert_eq!(ScoringModel::parse("short"), ScoringModel::ShortV3);
        assert_eq!(ScoringModel::parse("unknown"), ScoringModel::AggressiveV3);
        assert_eq!(ScoringModel::ShortV3.as_str(), "short_v3");
    }

    #[test]
    fn invert_bucket_negates_and_preserves_none() {
        assert_eq!(invert_bucket(Some(40)), Some(-40));
        assert_eq!(invert_bucket(Some(-25)), Some(25));
        assert_eq!(invert_bucket(None), None);
    }

    #[test]
    fn invert_composite_is_pure_negation() {
        let long = composite_score_v3(Some(40), Some(40), Some(40), None);
        assert_eq!(invert_composite(long), -long);
        assert_eq!(invert_composite(0), 0);
        assert_eq!(invert_composite(V3_COMPOSITE_BOUND), -V3_COMPOSITE_BOUND);
    }

    #[test]
    fn short_decision_uses_same_cutoffs_on_inverted_composite() {
        let long_strong = composite_score_v3(Some(50), Some(50), Some(50), None);
        let short = invert_composite(long_strong);
        // Strong long → weak short → Avoid
        assert_eq!(decision_state_v3(short), "Avoid");

        let long_weak = composite_score_v3(Some(-50), Some(-50), Some(-50), None);
        let short_strong = invert_composite(long_weak);
        assert!(short_strong >= V3_ACT_AT_OR_ABOVE);
        assert_eq!(decision_state_v3(short_strong), "Act");
    }

    #[test]
    fn high_beta_raises_short_composite_vs_low_beta() {
        let low_long = composite_score_v3(Some(40), Some(40), Some(40), Some(700));
        let high_long = composite_score_v3(Some(40), Some(40), Some(40), Some(1_800));
        let low_short = invert_composite(low_long);
        let high_short = invert_composite(high_long);
        assert!(
            high_short > low_short,
            "high_short={high_short} low_short={low_short}"
        );
    }

    #[test]
    fn setup_labels_follow_short_composite() {
        let (_, label) = setup_from_v3_composite(invert_composite(-55));
        assert_eq!(label, "StrongBuy"); // best short candidates reuse long tokens
        let (_, label) = setup_from_v3_composite(invert_composite(55));
        assert_eq!(label, "StrongAvoid");
    }
}
