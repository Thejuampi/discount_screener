//! Android AggressiveV3 opportunity scoring (not Windows multi-TF technicals).

use crate::dcf_model::DcfAnalysis;
use crate::engine::{
    composite_score_v2, smooth_ramp, CandidateRow, ChartSummary, EvidenceAccumulator,
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
}

impl ScoringModel {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::AggressiveV2 => "aggressive_v2",
            Self::AggressiveV3 => "aggressive_v3",
        }
    }
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
        acc.add(V3_FUND_BALANCE_WEIGHT, if c >= d { 1.0 } else { -0.5 }, "Bal");
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
pub fn score_opportunity_technicals_v3(summary: Option<&ChartSummary>) -> (Option<i32>, Vec<String>) {
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
        // No separate RSI slope on Windows ChartSummary yet → level only.
        acc.add(V3_TECH_RSI_WEIGHT, level.coerce_like(), "RSI");
    }
    if let Some(vr) = s.volume_ratio {
        // Windows volume_ratio is ratio (1.0 = median); Android uses hundredths.
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

trait Coerce {
    fn coerce_like(self) -> f64;
}
impl Coerce for f64 {
    fn coerce_like(self) -> f64 {
        self.clamp(-1.0, 1.0)
    }
}

fn v3_rsi_level_ramp(rsi: f64) -> f64 {
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

pub fn score_forecast_v3(
    row: &CandidateRow,
    dcf: Option<&DcfAnalysis>,
) -> (Option<i32>, Vec<String>) {
    let mut acc = EvidenceAccumulator::new(V3_FORECAST_FULL_WEIGHT);
    let mut sufficiency = Vec::new();
    let mut reliable = 0.0;
    let mut has_anchor = false;

    // Analyst mean target upside
    if row.intrinsic_value_cents > 0 && row.market_price_cents > 0 {
        let count = row.analyst_opinion_count;
        if count.unwrap_or(0) < V3_FORECAST_MIN_ANALYST_OPINIONS {
            sufficiency.push(if count.is_none() {
                "Cov?".into()
            } else {
                format!("Cov<{V3_FORECAST_MIN_ANALYST_OPINIONS}")
            });
        } else {
            let upside = ((row.intrinsic_value_cents - row.market_price_cents) as f64
                / row.market_price_cents as f64)
                * 10_000.0;
            let cov_rel = (count.unwrap_or(0) as f64 / V3_FORECAST_FULL_ANALYST_OPINIONS).min(1.0);
            reliable += V3_FORECAST_VALUATION_WEIGHT * 0.5 * cov_rel;
            has_anchor = true;
            acc.add(
                V3_FORECAST_VALUATION_WEIGHT * 0.55 * cov_rel,
                smooth_ramp(
                    upside,
                    V3_FORECAST_UPSIDE_LOWER_BPS,
                    V3_FORECAST_UPSIDE_UPPER_BPS,
                ),
                "Tgt",
            );
        }
    }

    if let Some(analysis) = dcf {
        if analysis.base_intrinsic_value_cents > 0 && row.market_price_cents > 0 {
            let mos = ((analysis.base_intrinsic_value_cents - row.market_price_cents) as f64
                / row.market_price_cents as f64)
                * 10_000.0;
            reliable += V3_FORECAST_VALUATION_WEIGHT * 0.45 * V3_FORECAST_DCF_RELIABILITY;
            has_anchor = true;
            acc.add(
                V3_FORECAST_VALUATION_WEIGHT * 0.45 * V3_FORECAST_DCF_RELIABILITY,
                smooth_ramp(mos, V3_FORECAST_UPSIDE_LOWER_BPS, V3_FORECAST_UPSIDE_UPPER_BPS),
                "DCF",
            );
            if analysis.base_intrinsic_value_cents > 0 {
                let width = (analysis.bull_intrinsic_value_cents
                    - analysis.bear_intrinsic_value_cents) as f64
                    / analysis.base_intrinsic_value_cents as f64;
                acc.add(
                    V3_FORECAST_DCF_UNCERTAINTY_WEIGHT,
                    -smooth_ramp(width, V3_FORECAST_DCF_WIDTH_LOWER, V3_FORECAST_DCF_WIDTH_UPPER),
                    "DcfUnc",
                );
            }
        }
    }

    if let Some(rec) = row.recommendation_mean_hundredths {
        acc.add(
            V3_FORECAST_REC_WEIGHT,
            -smooth_ramp(
                rec as f64,
                V3_FORECAST_REC_LOW_HUNDREDTHS,
                V3_FORECAST_REC_HIGH_HUNDREDTHS,
            ),
            "Rec",
        );
    }

    let bull = row.strong_buy_count.unwrap_or(0) + row.buy_count.unwrap_or(0);
    let bear = row.sell_count.unwrap_or(0) + row.strong_sell_count.unwrap_or(0);
    let total = bull + bear + row.hold_count.unwrap_or(0);
    if total > 0 {
        let skew = (bull as f64 - bear as f64) / total as f64;
        acc.add(V3_FORECAST_SKEW_WEIGHT, skew.clamp(-1.0, 1.0), "Skew");
    }

    if let Some(n) = row.analyst_opinion_count {
        acc.add(
            V3_FORECAST_BREADTH_WEIGHT,
            smooth_ramp(n as f64, 3.0, V3_FORECAST_FULL_ANALYST_OPINIONS),
            "Breadth",
        );
    }

    if let (Some(lo), Some(hi), true) = (
        row.low_fair_value_cents,
        row.high_fair_value_cents,
        row.intrinsic_value_cents > 0,
    ) {
        if hi > lo {
            let spread = (hi - lo) as f64 / row.intrinsic_value_cents as f64;
            acc.add(
                V3_FORECAST_ANALYST_UNCERTAINTY_WEIGHT,
                -smooth_ramp(spread, 0.1, 0.6),
                "Unc",
            );
        }
    }

    // Freshness assumed live for Windows feed
    acc.add(V3_FORECAST_FRESHNESS_WEIGHT, 1.0, "Fresh");

    let mut signals = acc.signals.clone();
    signals.extend(sufficiency);
    if !has_anchor || reliable < V3_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT {
        return (None, signals);
    }
    match acc.normalized_score() {
        Some(s) => (Some(s.clamp(-100, 100)), signals),
        None => (None, signals),
    }
}

pub fn composite_score_v3(
    fund: Option<i32>,
    tech: Option<i32>,
    forecast: Option<i32>,
    beta_millis: Option<i32>,
) -> i32 {
    let base = composite_score_v2(fund, tech, forecast);
    let haircut = beta_risk_haircut(beta_millis);
    (base as f64 - haircut).round() as i32
}

fn beta_risk_haircut(beta_millis: Option<i32>) -> f64 {
    let Some(b) = beta_millis else {
        return 0.0;
    };
    let t = smooth_ramp(b as f64, V3_BETA_LOW_MILLIS, V3_BETA_HIGH_MILLIS);
    // ramp 0..1 → haircut 0..max (high beta worse)
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
}
