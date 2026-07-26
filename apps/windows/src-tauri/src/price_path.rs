//! Multi-anchor price path estimator for Dashboard 2.0.
//!
//! Builds an explainable entry zone (confluence of structure + value + vol anchors),
//! path motives (why price may move against the entry first), and a timing
//! *distribution* (pTouch 5/20/60d) — never a lone "wait N weeks" claim.

use crate::engine::{find_support_resistance, ChartSummary, HistoricalCandle};

// ── Public types ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PathSide {
    Long,
    Short,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ZoneConfidence {
    Low,
    Med,
    High,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ZoneComponentKind {
    Support,
    Resistance,
    Fib,
    AtrBand,
    Bb,
    Intrinsic,
    Dcf,
    AnalystLow,
    AnalystHigh,
    Ema,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TimingMethod {
    EmpiricalTouches,
    AtrDistance,
    Hybrid,
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PathMotiveCode {
    Extension,
    FarFromSupport,
    FarFromResistance,
    RsiRich,
    RsiWashed,
    AboveValue,
    BelowValue,
    RegimeRisk,
    EarningsSoon,
    TrendAgainst,
    WeakForecast,
    NearZone,
    InZone,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MotiveSeverity {
    Low,
    Med,
    High,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct ZoneComponent {
    pub kind: ZoneComponentKind,
    pub price_cents: i64,
    /// Contribution weight in basis points (sum need not be 10_000).
    pub weight_bps: i32,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PathMotive {
    pub code: PathMotiveCode,
    pub severity: MotiveSeverity,
    /// Short metric fragment for UI templates, e.g. "RSI 68" or "-1.8 ATR".
    pub metric_label: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PriceZone {
    pub low_cents: i64,
    pub high_cents: i64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PathTiming {
    pub expected_sessions_to_zone: Option<i32>,
    pub p_touch_5d: Option<i32>,
    pub p_touch_20d: Option<i32>,
    pub p_touch_60d: Option<i32>,
    pub method: TimingMethod,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PathInvalidation {
    pub price_cents: Option<i64>,
    pub session_budget: Option<i32>,
    pub reason: String,
}

/// Compact fields safe to attach to every opportunity row.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct CompactPricePath {
    pub zone_low_cents: Option<i64>,
    pub zone_high_cents: Option<i64>,
    pub zone_confidence: Option<ZoneConfidence>,
    pub p_touch_20d: Option<i32>,
    pub expected_sessions: Option<i32>,
    pub invalidation_cents: Option<i64>,
    pub risk_codes: Vec<PathMotiveCode>,
    pub support_codes: Vec<PathMotiveCode>,
    pub timing_method: TimingMethod,
    pub side: PathSide,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PricePathEstimate {
    pub side: PathSide,
    pub zone: Option<PriceZone>,
    pub zone_confidence: ZoneConfidence,
    pub zone_components: Vec<ZoneComponent>,
    pub path_risks: Vec<PathMotive>,
    pub path_supports: Vec<PathMotive>,
    pub adverse_price_cents: Option<i64>,
    pub base_zone_mid_cents: Option<i64>,
    pub timing: PathTiming,
    pub invalidation: PathInvalidation,
}

/// Inputs available without a full SymbolDetail round-trip.
#[derive(Debug, Clone)]
pub struct PricePathInput<'a> {
    pub side: PathSide,
    pub market_price_cents: i64,
    pub intrinsic_value_cents: i64,
    pub dcf_value_cents: Option<i64>,
    pub low_fair_value_cents: Option<i64>,
    pub high_fair_value_cents: Option<i64>,
    pub gap_bps: Option<i32>,
    pub daily: Option<&'a ChartSummary>,
    pub candles: &'a [HistoricalCandle],
    pub next_earnings_epoch: Option<i64>,
    pub now_epoch: i64,
    /// Optional regime risk flag from caller (context ON + adverse causes).
    pub regime_risk: bool,
    pub forecast_score: Option<i32>,
    pub technical_score: Option<i32>,
}

// ── Constants ────────────────────────────────────────────────────────────────

const CLUSTER_ATR_FRAC: f64 = 0.50;
const MIN_ZONE_ATR_FRAC: f64 = 0.30;
const MAX_ZONE_ATR_FRAC: f64 = 1.50;
const ATR_BAND_MULT: f64 = 1.0;
const NEAR_ZONE_ATR: f64 = 0.35;
const IN_ZONE_EPS_ATR: f64 = 0.05;
const MAX_MOTIVES: usize = 3;
const SESSIONS_CAP: i32 = 120;
const BOOTSTRAP_PATHS: usize = 200;
const BOOTSTRAP_MIN_RETURNS: usize = 40;

// ── Public API ───────────────────────────────────────────────────────────────

pub fn estimate_price_path(input: &PricePathInput<'_>) -> PricePathEstimate {
    let price = input.market_price_cents;
    if price <= 0 {
        return empty_estimate(input.side);
    }

    let atr = input
        .daily
        .and_then(|d| d.atr_cents)
        .filter(|&a| a > 0)
        .unwrap_or_else(|| (price as f64 * 0.015).round().max(1.0) as i64);

    let sr = if input.candles.len() >= 15 {
        find_support_resistance(input.candles, 5)
    } else {
        Default::default()
    };

    let anchors = collect_anchors(input, atr, &sr);
    let (zone, confidence, components) = build_zone(price, atr, input.side, &anchors);
    let mid = zone.as_ref().map(|z| (z.low_cents + z.high_cents) / 2);

    let (risks, supports) = build_motives(input, atr, zone.as_ref(), &sr);
    let timing = estimate_timing(price, mid, atr, input.side, input.candles);
    let invalidation = build_invalidation(input, atr, &sr, &timing);
    let adverse = adverse_stretch(price, atr, input.side);

    PricePathEstimate {
        side: input.side,
        zone,
        zone_confidence: confidence,
        zone_components: components,
        path_risks: risks,
        path_supports: supports,
        adverse_price_cents: Some(adverse),
        base_zone_mid_cents: mid,
        timing,
        invalidation,
    }
}

pub fn compact_price_path(est: &PricePathEstimate) -> CompactPricePath {
    CompactPricePath {
        zone_low_cents: est.zone.as_ref().map(|z| z.low_cents),
        zone_high_cents: est.zone.as_ref().map(|z| z.high_cents),
        zone_confidence: est.zone.as_ref().map(|_| est.zone_confidence),
        p_touch_20d: est.timing.p_touch_20d,
        expected_sessions: est.timing.expected_sessions_to_zone,
        invalidation_cents: est.invalidation.price_cents,
        risk_codes: est
            .path_risks
            .iter()
            .map(|m| m.code)
            .take(MAX_MOTIVES)
            .collect(),
        support_codes: est
            .path_supports
            .iter()
            .map(|m| m.code)
            .take(MAX_MOTIVES)
            .collect(),
        timing_method: est.timing.method,
        side: est.side,
    }
}

// ── Internals ────────────────────────────────────────────────────────────────

#[derive(Clone)]
struct Anchor {
    kind: ZoneComponentKind,
    price_cents: i64,
    weight_bps: i32,
}

fn empty_estimate(side: PathSide) -> PricePathEstimate {
    PricePathEstimate {
        side,
        zone: None,
        zone_confidence: ZoneConfidence::Low,
        zone_components: vec![],
        path_risks: vec![],
        path_supports: vec![],
        adverse_price_cents: None,
        base_zone_mid_cents: None,
        timing: PathTiming {
            expected_sessions_to_zone: None,
            p_touch_5d: None,
            p_touch_20d: None,
            p_touch_60d: None,
            method: TimingMethod::Unavailable,
        },
        invalidation: PathInvalidation {
            price_cents: None,
            session_budget: None,
            reason: "insufficient_price".into(),
        },
    }
}

fn collect_anchors(
    input: &PricePathInput<'_>,
    atr: i64,
    sr: &crate::engine::SupportResistance,
) -> Vec<Anchor> {
    let price = input.market_price_cents;
    let mut anchors = Vec::new();

    match input.side {
        PathSide::Long => {
            for &s in &sr.supports_cents {
                if s < price {
                    anchors.push(Anchor {
                        kind: ZoneComponentKind::Support,
                        price_cents: s,
                        weight_bps: 2_800,
                    });
                }
            }
            if let Some(d) = input.daily {
                if let Some(bb) = d.bb_lower_cents {
                    if bb < price {
                        anchors.push(Anchor {
                            kind: ZoneComponentKind::Bb,
                            price_cents: bb,
                            weight_bps: 1_600,
                        });
                    }
                }
                for (ema, w) in [(d.ema50_cents, 1_400), (d.ema200_cents, 1_800)] {
                    if let Some(e) = ema {
                        if e < price {
                            anchors.push(Anchor {
                                kind: ZoneComponentKind::Ema,
                                price_cents: e,
                                weight_bps: w,
                            });
                        }
                    }
                }
            }
            // ATR pullback band below price
            let atr_lvl = price - atr;
            if atr_lvl > 0 {
                anchors.push(Anchor {
                    kind: ZoneComponentKind::AtrBand,
                    price_cents: atr_lvl,
                    weight_bps: 900,
                });
            }
            if input.intrinsic_value_cents > 0 && input.intrinsic_value_cents < price {
                anchors.push(Anchor {
                    kind: ZoneComponentKind::Intrinsic,
                    price_cents: input.intrinsic_value_cents,
                    weight_bps: 2_200,
                });
            }
            if let Some(dcf) = input.dcf_value_cents {
                if dcf > 0 && dcf < price {
                    anchors.push(Anchor {
                        kind: ZoneComponentKind::Dcf,
                        price_cents: dcf,
                        weight_bps: 1_800,
                    });
                }
            }
            if let Some(low) = input.low_fair_value_cents {
                if low > 0 && low < price {
                    anchors.push(Anchor {
                        kind: ZoneComponentKind::AnalystLow,
                        price_cents: low,
                        weight_bps: 1_500,
                    });
                }
            }
            // Fib-like mid pullback from 52w range if available
            if let Some(d) = input.daily {
                if let (Some(hi), Some(lo)) = (d.high_52w_cents, d.low_52w_cents) {
                    if hi > lo {
                        let fib618 = lo + ((hi - lo) as f64 * 0.382).round() as i64;
                        if fib618 < price {
                            anchors.push(Anchor {
                                kind: ZoneComponentKind::Fib,
                                price_cents: fib618,
                                weight_bps: 1_200,
                            });
                        }
                    }
                }
            }
        }
        PathSide::Short => {
            for &r in &sr.resistances_cents {
                if r > price {
                    anchors.push(Anchor {
                        kind: ZoneComponentKind::Resistance,
                        price_cents: r,
                        weight_bps: 2_800,
                    });
                }
            }
            if let Some(d) = input.daily {
                if let Some(bb) = d.bb_upper_cents {
                    if bb > price {
                        anchors.push(Anchor {
                            kind: ZoneComponentKind::Bb,
                            price_cents: bb,
                            weight_bps: 1_600,
                        });
                    }
                }
                for (ema, w) in [(d.ema50_cents, 1_400), (d.ema200_cents, 1_800)] {
                    if let Some(e) = ema {
                        if e > price {
                            anchors.push(Anchor {
                                kind: ZoneComponentKind::Ema,
                                price_cents: e,
                                weight_bps: w,
                            });
                        }
                    }
                }
            }
            let atr_lvl = price + atr;
            anchors.push(Anchor {
                kind: ZoneComponentKind::AtrBand,
                price_cents: atr_lvl,
                weight_bps: 900,
            });
            if input.intrinsic_value_cents > price {
                anchors.push(Anchor {
                    kind: ZoneComponentKind::Intrinsic,
                    price_cents: input.intrinsic_value_cents,
                    weight_bps: 2_200,
                });
            }
            if let Some(dcf) = input.dcf_value_cents {
                if dcf > price {
                    anchors.push(Anchor {
                        kind: ZoneComponentKind::Dcf,
                        price_cents: dcf,
                        weight_bps: 1_800,
                    });
                }
            }
            if let Some(hi) = input.high_fair_value_cents {
                if hi > price {
                    anchors.push(Anchor {
                        kind: ZoneComponentKind::AnalystHigh,
                        price_cents: hi,
                        weight_bps: 1_500,
                    });
                }
            }
            if let Some(d) = input.daily {
                if let (Some(hi), Some(lo)) = (d.high_52w_cents, d.low_52w_cents) {
                    if hi > lo {
                        let fib = lo + ((hi - lo) as f64 * 0.618).round() as i64;
                        if fib > price {
                            anchors.push(Anchor {
                                kind: ZoneComponentKind::Fib,
                                price_cents: fib,
                                weight_bps: 1_200,
                            });
                        }
                    }
                }
            }
        }
    }

    let _ = atr; // used by callers for clustering scale
    anchors
}

fn build_zone(
    price: i64,
    atr: i64,
    side: PathSide,
    anchors: &[Anchor],
) -> (Option<PriceZone>, ZoneConfidence, Vec<ZoneComponent>) {
    if anchors.is_empty() {
        // Synthetic ATR-only zone
        let (low, high) = match side {
            PathSide::Long => {
                let mid = price - atr;
                (
                    mid - (atr as f64 * 0.25) as i64,
                    mid + (atr as f64 * 0.25) as i64,
                )
            }
            PathSide::Short => {
                let mid = price + atr;
                (
                    mid - (atr as f64 * 0.25) as i64,
                    mid + (atr as f64 * 0.25) as i64,
                )
            }
        };
        if low <= 0 {
            return (None, ZoneConfidence::Low, vec![]);
        }
        return (
            Some(PriceZone {
                low_cents: low,
                high_cents: high.max(low + 1),
            }),
            ZoneConfidence::Low,
            vec![ZoneComponent {
                kind: ZoneComponentKind::AtrBand,
                price_cents: (low + high) / 2,
                weight_bps: 900,
            }],
        );
    }

    let cluster_radius = ((atr as f64) * CLUSTER_ATR_FRAC).round().max(1.0) as i64;
    // Greedy: for each anchor as seed, gather neighbors within radius; pick max weight cluster.
    let mut best: Vec<&Anchor> = vec![];
    let mut best_weight = 0i32;
    for seed in anchors {
        let mut cluster: Vec<&Anchor> = anchors
            .iter()
            .filter(|a| (a.price_cents - seed.price_cents).abs() <= cluster_radius)
            .collect();
        cluster.sort_by_key(|a| a.price_cents);
        let w: i32 = cluster.iter().map(|a| a.weight_bps).sum();
        if w > best_weight
            || (w == best_weight
                && cluster_mid(&cluster).map(|m| dist(m, price)) < best_mid_dist(&best, price))
        {
            best_weight = w;
            best = cluster;
        }
    }

    if best.is_empty() {
        return (None, ZoneConfidence::Low, vec![]);
    }

    let mut low = best.iter().map(|a| a.price_cents).min().unwrap();
    let mut high = best.iter().map(|a| a.price_cents).max().unwrap();
    let min_w = ((atr as f64) * MIN_ZONE_ATR_FRAC).round() as i64;
    let max_w = ((atr as f64) * MAX_ZONE_ATR_FRAC).round() as i64;
    let width = high - low;
    if width < min_w {
        let pad = (min_w - width) / 2;
        low -= pad;
        high += min_w - width - pad;
    } else if width > max_w {
        let mid = (low + high) / 2;
        low = mid - max_w / 2;
        high = mid + max_w / 2;
    }
    if low <= 0 {
        low = 1;
    }
    if high <= low {
        high = low + 1;
    }

    // Distinct kinds in cluster (structure-ish count)
    let mut kinds = best.iter().map(|a| a.kind).collect::<Vec<_>>();
    kinds.sort_by_key(|k| format!("{:?}", k));
    kinds.dedup();
    let structure_like = kinds
        .iter()
        .filter(|k| {
            matches!(
                k,
                ZoneComponentKind::Support
                    | ZoneComponentKind::Resistance
                    | ZoneComponentKind::Fib
                    | ZoneComponentKind::Ema
                    | ZoneComponentKind::Bb
                    | ZoneComponentKind::Intrinsic
                    | ZoneComponentKind::Dcf
            )
        })
        .count();
    let confidence = if structure_like >= 3 || kinds.len() >= 3 {
        ZoneConfidence::High
    } else if structure_like >= 2 || kinds.len() >= 2 {
        ZoneConfidence::Med
    } else {
        ZoneConfidence::Low
    };

    let components: Vec<ZoneComponent> = best
        .iter()
        .map(|a| ZoneComponent {
            kind: a.kind,
            price_cents: a.price_cents,
            weight_bps: a.weight_bps,
        })
        .collect();

    (
        Some(PriceZone {
            low_cents: low,
            high_cents: high,
        }),
        confidence,
        components,
    )
}

fn cluster_mid(cluster: &[&Anchor]) -> Option<i64> {
    if cluster.is_empty() {
        return None;
    }
    let sum: i64 = cluster.iter().map(|a| a.price_cents).sum();
    Some(sum / cluster.len() as i64)
}

fn best_mid_dist(cluster: &[&Anchor], price: i64) -> Option<i64> {
    cluster_mid(cluster).map(|m| dist(m, price))
}

fn dist(a: i64, b: i64) -> i64 {
    (a - b).abs()
}

fn build_motives(
    input: &PricePathInput<'_>,
    atr: i64,
    zone: Option<&PriceZone>,
    sr: &crate::engine::SupportResistance,
) -> (Vec<PathMotive>, Vec<PathMotive>) {
    let price = input.market_price_cents;
    let mut risks = Vec::new();
    let mut supports = Vec::new();
    let atr_f = atr as f64;

    // Zone proximity
    if let Some(z) = zone {
        let in_zone = price >= z.low_cents - (atr_f * IN_ZONE_EPS_ATR) as i64
            && price <= z.high_cents + (atr_f * IN_ZONE_EPS_ATR) as i64;
        let mid = (z.low_cents + z.high_cents) / 2;
        let d_atr = (price - mid).abs() as f64 / atr_f;
        if in_zone {
            supports.push(PathMotive {
                code: PathMotiveCode::InZone,
                severity: MotiveSeverity::High,
                metric_label: format!("in zone ${:.2}", mid as f64 / 100.0),
            });
        } else if d_atr <= NEAR_ZONE_ATR {
            supports.push(PathMotive {
                code: PathMotiveCode::NearZone,
                severity: MotiveSeverity::Med,
                metric_label: format!("{:.1} ATR to zone", d_atr),
            });
        } else {
            match input.side {
                PathSide::Long => risks.push(PathMotive {
                    code: PathMotiveCode::FarFromSupport,
                    severity: if d_atr >= 1.5 {
                        MotiveSeverity::High
                    } else {
                        MotiveSeverity::Med
                    },
                    metric_label: format!("-{:.1} ATR vs zone", d_atr),
                }),
                PathSide::Short => risks.push(PathMotive {
                    code: PathMotiveCode::FarFromResistance,
                    severity: if d_atr >= 1.5 {
                        MotiveSeverity::High
                    } else {
                        MotiveSeverity::Med
                    },
                    metric_label: format!("+{:.1} ATR vs zone", d_atr),
                }),
            }
        }
    }

    if let Some(d) = input.daily {
        if let Some(rsi) = d.rsi {
            match input.side {
                PathSide::Long if rsi >= 65.0 => risks.push(PathMotive {
                    code: PathMotiveCode::RsiRich,
                    severity: if rsi >= 75.0 {
                        MotiveSeverity::High
                    } else {
                        MotiveSeverity::Med
                    },
                    metric_label: format!("RSI {:.0}", rsi),
                }),
                PathSide::Long if rsi <= 40.0 => supports.push(PathMotive {
                    code: PathMotiveCode::RsiWashed,
                    severity: MotiveSeverity::Med,
                    metric_label: format!("RSI {:.0}", rsi),
                }),
                PathSide::Short if rsi <= 35.0 => risks.push(PathMotive {
                    code: PathMotiveCode::RsiWashed,
                    severity: if rsi <= 25.0 {
                        MotiveSeverity::High
                    } else {
                        MotiveSeverity::Med
                    },
                    metric_label: format!("RSI {:.0}", rsi),
                }),
                PathSide::Short if rsi >= 60.0 => supports.push(PathMotive {
                    code: PathMotiveCode::RsiRich,
                    severity: MotiveSeverity::Med,
                    metric_label: format!("RSI {:.0}", rsi),
                }),
                _ => {}
            }
        }

        // Extension vs EMA50
        if let Some(ema50) = d.ema50_cents {
            let ext = (price - ema50) as f64 / atr_f;
            match input.side {
                PathSide::Long if ext >= 1.2 => risks.push(PathMotive {
                    code: PathMotiveCode::Extension,
                    severity: if ext >= 2.0 {
                        MotiveSeverity::High
                    } else {
                        MotiveSeverity::Med
                    },
                    metric_label: format!("+{:.1} ATR vs EMA50", ext),
                }),
                PathSide::Short if ext <= -1.2 => risks.push(PathMotive {
                    code: PathMotiveCode::Extension,
                    severity: if ext <= -2.0 {
                        MotiveSeverity::High
                    } else {
                        MotiveSeverity::Med
                    },
                    metric_label: format!("{:.1} ATR vs EMA50", ext),
                }),
                _ => {}
            }
        }
    }

    // Value gap
    if let Some(gap) = input.gap_bps {
        match input.side {
            // gap_bps: negative often means market below target (discount) in this app —
            // use price vs intrinsic when available for clarity.
            PathSide::Long => {
                if input.intrinsic_value_cents > 0 && price > input.intrinsic_value_cents {
                    let prem = (price - input.intrinsic_value_cents) as f64 / price as f64 * 100.0;
                    risks.push(PathMotive {
                        code: PathMotiveCode::AboveValue,
                        severity: if prem >= 10.0 {
                            MotiveSeverity::High
                        } else {
                            MotiveSeverity::Med
                        },
                        metric_label: format!("+{:.0}% vs fair", prem),
                    });
                } else if input.intrinsic_value_cents > 0 && price < input.intrinsic_value_cents {
                    let disc = (input.intrinsic_value_cents - price) as f64 / price as f64 * 100.0;
                    supports.push(PathMotive {
                        code: PathMotiveCode::BelowValue,
                        severity: MotiveSeverity::Med,
                        metric_label: format!("-{:.0}% vs fair", disc),
                    });
                } else if gap < -800 {
                    supports.push(PathMotive {
                        code: PathMotiveCode::BelowValue,
                        severity: MotiveSeverity::Med,
                        metric_label: format!("gap {:.1}%", gap as f64 / 100.0),
                    });
                }
            }
            PathSide::Short => {
                if input.intrinsic_value_cents > 0 && price < input.intrinsic_value_cents {
                    risks.push(PathMotive {
                        code: PathMotiveCode::BelowValue,
                        severity: MotiveSeverity::Med,
                        metric_label: "below fair (short risk)".into(),
                    });
                } else if input.intrinsic_value_cents > 0 && price > input.intrinsic_value_cents {
                    supports.push(PathMotive {
                        code: PathMotiveCode::AboveValue,
                        severity: MotiveSeverity::Med,
                        metric_label: "above fair".into(),
                    });
                }
            }
        }
    }

    if input.regime_risk {
        risks.push(PathMotive {
            code: PathMotiveCode::RegimeRisk,
            severity: MotiveSeverity::Med,
            metric_label: "market context".into(),
        });
    }

    if let Some(ee) = input.next_earnings_epoch {
        let days = (ee - input.now_epoch) as f64 / 86_400.0;
        if (0.0..14.0).contains(&days) {
            risks.push(PathMotive {
                code: PathMotiveCode::EarningsSoon,
                severity: if days < 5.0 {
                    MotiveSeverity::High
                } else {
                    MotiveSeverity::Med
                },
                metric_label: format!("earnings {:.0}d", days.max(0.0)),
            });
        }
    }

    // Forecast / tech disagreement
    if let (Some(f), Some(t)) = (input.forecast_score, input.technical_score) {
        match input.side {
            PathSide::Long if f >= 20 && t <= -10 => risks.push(PathMotive {
                code: PathMotiveCode::TrendAgainst,
                severity: MotiveSeverity::Med,
                metric_label: format!("tech {t} vs forecast {f}"),
            }),
            PathSide::Short if f <= -20 && t >= 10 => risks.push(PathMotive {
                code: PathMotiveCode::TrendAgainst,
                severity: MotiveSeverity::Med,
                metric_label: format!("tech {t} vs forecast {f}"),
            }),
            PathSide::Long if f <= -25 => risks.push(PathMotive {
                code: PathMotiveCode::WeakForecast,
                severity: MotiveSeverity::Med,
                metric_label: format!("forecast {f}"),
            }),
            PathSide::Short if f >= 25 => risks.push(PathMotive {
                code: PathMotiveCode::WeakForecast,
                severity: MotiveSeverity::Med,
                metric_label: format!("forecast {f}"),
            }),
            _ => {}
        }
    }

    // Nearest structure distance as extra risk if no zone motive yet
    if risks.iter().all(|r| {
        r.code != PathMotiveCode::FarFromSupport && r.code != PathMotiveCode::FarFromResistance
    }) {
        match input.side {
            PathSide::Long => {
                if let Some(&s) = sr.supports_cents.first() {
                    let d = (price - s) as f64 / atr_f;
                    if d >= 1.5 {
                        risks.push(PathMotive {
                            code: PathMotiveCode::FarFromSupport,
                            severity: MotiveSeverity::Med,
                            metric_label: format!("-{:.1} ATR vs support", d),
                        });
                    }
                }
            }
            PathSide::Short => {
                if let Some(&r) = sr.resistances_cents.first() {
                    let d = (r - price) as f64 / atr_f;
                    if d >= 1.5 {
                        risks.push(PathMotive {
                            code: PathMotiveCode::FarFromResistance,
                            severity: MotiveSeverity::Med,
                            metric_label: format!("+{:.1} ATR vs resist", d),
                        });
                    }
                }
            }
        }
    }

    risks.truncate(MAX_MOTIVES);
    supports.truncate(MAX_MOTIVES);
    (risks, supports)
}

fn estimate_timing(
    price: i64,
    zone_mid: Option<i64>,
    atr: i64,
    side: PathSide,
    candles: &[HistoricalCandle],
) -> PathTiming {
    let Some(mid) = zone_mid else {
        return PathTiming {
            expected_sessions_to_zone: None,
            p_touch_5d: None,
            p_touch_20d: None,
            p_touch_60d: None,
            method: TimingMethod::Unavailable,
        };
    };

    // Already at/through zone
    let already = match side {
        PathSide::Long => price <= mid,
        PathSide::Short => price >= mid,
    };
    if already {
        return PathTiming {
            expected_sessions_to_zone: Some(0),
            p_touch_5d: Some(95),
            p_touch_20d: Some(98),
            p_touch_60d: Some(99),
            method: TimingMethod::Hybrid,
        };
    }

    let distance = (price - mid).abs() as f64;
    let atr_f = atr.max(1) as f64;
    let units = distance / atr_f;
    // Super-linear ATR prior so large gaps aren't "2 days"
    let atr_sessions = ((2.2 * units * units) + 1.0)
        .round()
        .clamp(1.0, SESSIONS_CAP as f64) as i32;

    let returns = log_returns(candles);
    if returns.len() < BOOTSTRAP_MIN_RETURNS {
        let (p5, p20, p60) = atr_prior_probs(atr_sessions);
        return PathTiming {
            expected_sessions_to_zone: Some(atr_sessions),
            p_touch_5d: Some(p5),
            p_touch_20d: Some(p20),
            p_touch_60d: Some(p60),
            method: TimingMethod::AtrDistance,
        };
    }

    let (p5, p20, p60, median_hit) =
        bootstrap_touch_probs(&returns, price, mid, side, BOOTSTRAP_PATHS);
    // Blend ATR prior with bootstrap
    let (ap5, ap20, ap60) = atr_prior_probs(atr_sessions);
    let blend = |emp: i32, prior: i32| -> i32 { ((emp * 65) + (prior * 35)) / 100 };
    let expected = median_hit.unwrap_or(atr_sessions).clamp(0, SESSIONS_CAP);

    PathTiming {
        expected_sessions_to_zone: Some(expected),
        p_touch_5d: Some(blend(p5, ap5).clamp(0, 100)),
        p_touch_20d: Some(blend(p20, ap20).clamp(0, 100)),
        p_touch_60d: Some(blend(p60, ap60).clamp(0, 100)),
        method: TimingMethod::Hybrid,
    }
}

fn atr_prior_probs(expected: i32) -> (i32, i32, i32) {
    // Soft exponential CDF-like mapping from expected sessions
    let e = expected.max(1) as f64;
    let p = |n: f64| -> i32 {
        let x = (-(n / e)).exp(); // survival-ish inverted
        let touch = (1.0 - x) * 100.0;
        touch.round().clamp(2.0, 98.0) as i32
    };
    // Higher n → higher touch probability
    let p5 = p(5.0);
    let p20 = p(20.0).max(p5);
    let p60 = p(60.0).max(p20);
    (p5, p20, p60)
}

fn log_returns(candles: &[HistoricalCandle]) -> Vec<f64> {
    let mut out = Vec::new();
    for w in candles.windows(2) {
        let a = w[0].close_cents as f64;
        let b = w[1].close_cents as f64;
        if a > 0.0 && b > 0.0 {
            out.push((b / a).ln());
        }
    }
    out
}

/// Deterministic LCG bootstrap for reproducible tests (no external RNG).
fn bootstrap_touch_probs(
    returns: &[f64],
    price: i64,
    target: i64,
    side: PathSide,
    paths: usize,
) -> (i32, i32, i32, Option<i32>) {
    let n = returns.len();
    let mut seed: u64 = 0xC0FFEE ^ (price as u64).wrapping_mul(0x9E3779B97F4A7C15);
    let mut next = || {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
        seed
    };

    let mut hit5 = 0usize;
    let mut hit20 = 0usize;
    let mut hit60 = 0usize;
    let mut first_hits: Vec<i32> = Vec::new();

    for _ in 0..paths {
        let mut px = price as f64;
        let mut first: Option<i32> = None;
        for day in 1..=60 {
            let idx = (next() as usize) % n;
            px *= returns[idx].exp();
            let touched = match side {
                PathSide::Long => px <= target as f64,
                PathSide::Short => px >= target as f64,
            };
            if touched {
                if first.is_none() {
                    first = Some(day);
                }
                if day <= 5 {
                    hit5 += 1;
                }
                if day <= 20 {
                    hit20 += 1;
                }
                hit60 += 1;
                break;
            }
        }
        if let Some(d) = first {
            first_hits.push(d);
        }
    }

    let pct = |h: usize| -> i32 { ((h as f64 / paths as f64) * 100.0).round() as i32 };
    first_hits.sort_unstable();
    let median = if first_hits.is_empty() {
        None
    } else {
        Some(first_hits[first_hits.len() / 2])
    };
    (pct(hit5), pct(hit20), pct(hit60), median)
}

fn build_invalidation(
    input: &PricePathInput<'_>,
    atr: i64,
    sr: &crate::engine::SupportResistance,
    timing: &PathTiming,
) -> PathInvalidation {
    let price = input.market_price_cents;
    let session_budget = Some(
        timing
            .expected_sessions_to_zone
            .map(|e| (e * 2).clamp(20, SESSIONS_CAP))
            .unwrap_or(60),
    );

    let price_lvl = match input.side {
        PathSide::Long => {
            // Break above recent resistance or +2 ATR extension
            let r = sr.resistances_cents.first().copied();
            Some(r.unwrap_or(price + 2 * atr).max(price + atr))
        }
        PathSide::Short => {
            let s = sr.supports_cents.first().copied();
            Some(s.unwrap_or(price - 2 * atr).min(price - atr).max(1))
        }
    };

    PathInvalidation {
        price_cents: price_lvl,
        session_budget,
        reason: "break_or_time".into(),
    }
}

fn adverse_stretch(price: i64, atr: i64, side: PathSide) -> i64 {
    match side {
        PathSide::Long => (price - (atr as f64 * ATR_BAND_MULT * 1.5) as i64).max(1),
        PathSide::Short => price + (atr as f64 * ATR_BAND_MULT * 1.5) as i64,
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn candle(close: i64, i: i64) -> HistoricalCandle {
        HistoricalCandle {
            epoch_seconds: 1_700_000_000 + i * 86_400,
            open_cents: close,
            high_cents: close + 50,
            low_cents: close - 50,
            close_cents: close,
            volume: 1_000_000,
        }
    }

    /// Mild mean-reverting-ish series around 10_000 with a dip then recovery.
    fn series_mean_revert() -> Vec<HistoricalCandle> {
        let mut v = Vec::new();
        let mut px = 10_000i64;
        for i in 0..120 {
            // deterministic pseudo-walk
            let delta = if i % 7 == 0 {
                -80
            } else if i % 5 == 0 {
                60
            } else {
                ((i * 17) % 21) - 10
            };
            px = (px + delta).clamp(8_000, 12_000);
            v.push(candle(px, i));
        }
        v
    }

    fn daily_at(close: i64, atr: i64, rsi: f64, ema50: i64) -> ChartSummary {
        ChartSummary {
            latest_close_cents: close,
            ema20_cents: Some(ema50 + 50),
            ema50_cents: Some(ema50),
            ema200_cents: Some(ema50 - 200),
            macd_cents: Some(0),
            signal_cents: Some(0),
            histogram_cents: Some(0),
            rsi: Some(rsi),
            rsi_slope: Some(0.0),
            adx: Some(20.0),
            plus_di: Some(20.0),
            minus_di: Some(20.0),
            bb_upper_cents: Some(close + atr),
            bb_middle_cents: Some(close),
            bb_lower_cents: Some(close - atr),
            bb_percent_b: Some(0.7),
            bb_bandwidth: Some(0.05),
            obv_slope: Some(0.0),
            volume_ratio: Some(1.0),
            atr_cents: Some(atr),
            high_52w_cents: Some(close + 3 * atr),
            low_52w_cents: Some(close - 4 * atr),
            pos_52w_pct: Some(70.0),
        }
    }

    #[test]
    fn long_zone_below_price_when_extended() {
        let candles = series_mean_revert();
        let daily = daily_at(11_000, 150, 72.0, 10_200);
        let input = PricePathInput {
            side: PathSide::Long,
            market_price_cents: 11_000,
            intrinsic_value_cents: 10_500,
            dcf_value_cents: Some(10_400),
            low_fair_value_cents: Some(10_200),
            high_fair_value_cents: Some(12_000),
            gap_bps: Some(-500),
            daily: Some(&daily),
            candles: &candles,
            next_earnings_epoch: None,
            now_epoch: 1_710_000_000,
            regime_risk: false,
            forecast_score: Some(30),
            technical_score: Some(-15),
        };
        let est = estimate_price_path(&input);
        let zone = est.zone.expect("zone");
        assert!(zone.high_cents < 11_000, "long zone should be below price");
        assert!(zone.low_cents > 0);
        assert!(zone.high_cents >= zone.low_cents);
        assert!(
            !est.path_risks.is_empty(),
            "extended long should surface path risks"
        );
        assert!(est.timing.p_touch_20d.is_some());
        let p20 = est.timing.p_touch_20d.unwrap();
        assert!((0..=100).contains(&p20));
    }

    #[test]
    fn short_zone_above_price() {
        let candles = series_mean_revert();
        let daily = daily_at(9_000, 120, 28.0, 9_400);
        let input = PricePathInput {
            side: PathSide::Short,
            market_price_cents: 9_000,
            intrinsic_value_cents: 9_800,
            dcf_value_cents: Some(9_700),
            low_fair_value_cents: Some(8_500),
            high_fair_value_cents: Some(10_200),
            gap_bps: Some(800),
            daily: Some(&daily),
            candles: &candles,
            next_earnings_epoch: None,
            now_epoch: 1_710_000_000,
            regime_risk: false,
            forecast_score: Some(-30),
            technical_score: Some(10),
        };
        let est = estimate_price_path(&input);
        let zone = est.zone.expect("zone");
        assert!(zone.low_cents > 9_000, "short zone should be above price");
    }

    #[test]
    fn zone_width_clamped_to_atr_bounds() {
        let candles = series_mean_revert();
        let atr = 200i64;
        let daily = daily_at(10_000, atr, 55.0, 9_800);
        let input = PricePathInput {
            side: PathSide::Long,
            market_price_cents: 10_000,
            intrinsic_value_cents: 9_000,
            dcf_value_cents: Some(8_000),
            low_fair_value_cents: Some(7_000),
            high_fair_value_cents: None,
            gap_bps: Some(-2000),
            daily: Some(&daily),
            candles: &candles,
            next_earnings_epoch: None,
            now_epoch: 1_710_000_000,
            regime_risk: false,
            forecast_score: None,
            technical_score: None,
        };
        let est = estimate_price_path(&input);
        let zone = est.zone.expect("zone");
        let width = zone.high_cents - zone.low_cents;
        let max_w = (atr as f64 * MAX_ZONE_ATR_FRAC).round() as i64 + 2;
        let min_w = (atr as f64 * MIN_ZONE_ATR_FRAC).round() as i64 - 2;
        assert!(width <= max_w, "width {width} > max {max_w}");
        assert!(width >= min_w, "width {width} < min {min_w}");
    }

    #[test]
    fn zero_price_returns_unavailable() {
        let candles: Vec<HistoricalCandle> = vec![];
        let input = PricePathInput {
            side: PathSide::Long,
            market_price_cents: 0,
            intrinsic_value_cents: 0,
            dcf_value_cents: None,
            low_fair_value_cents: None,
            high_fair_value_cents: None,
            gap_bps: None,
            daily: None,
            candles: &candles,
            next_earnings_epoch: None,
            now_epoch: 0,
            regime_risk: false,
            forecast_score: None,
            technical_score: None,
        };
        let est = estimate_price_path(&input);
        assert!(est.zone.is_none());
        assert_eq!(est.timing.method, TimingMethod::Unavailable);
    }

    #[test]
    fn compact_limits_motive_codes() {
        let candles = series_mean_revert();
        let daily = daily_at(11_500, 100, 80.0, 10_000);
        let input = PricePathInput {
            side: PathSide::Long,
            market_price_cents: 11_500,
            intrinsic_value_cents: 10_000,
            dcf_value_cents: Some(9_800),
            low_fair_value_cents: Some(9_500),
            high_fair_value_cents: None,
            gap_bps: Some(500),
            daily: Some(&daily),
            candles: &candles,
            next_earnings_epoch: Some(1_710_000_000 + 3 * 86_400),
            now_epoch: 1_710_000_000,
            regime_risk: true,
            forecast_score: Some(40),
            technical_score: Some(-20),
        };
        let est = estimate_price_path(&input);
        let c = compact_price_path(&est);
        assert!(c.risk_codes.len() <= MAX_MOTIVES);
        assert!(c.zone_low_cents.is_some());
        assert!(c.p_touch_20d.is_some());
    }

    #[test]
    fn in_zone_has_high_touch_probability() {
        let candles = series_mean_revert();
        let daily = daily_at(10_000, 150, 45.0, 10_100);
        // Price near intrinsic/support cluster
        let input = PricePathInput {
            side: PathSide::Long,
            market_price_cents: 10_000,
            intrinsic_value_cents: 10_050,
            dcf_value_cents: Some(9_950),
            low_fair_value_cents: Some(9_900),
            high_fair_value_cents: None,
            gap_bps: Some(-200),
            daily: Some(&daily),
            candles: &candles,
            next_earnings_epoch: None,
            now_epoch: 1_710_000_000,
            regime_risk: false,
            forecast_score: Some(25),
            technical_score: Some(10),
        };
        let est = estimate_price_path(&input);
        if let Some(p20) = est.timing.p_touch_20d {
            assert!(
                p20 >= 40,
                "near/in zone should not show tiny p20, got {p20}"
            );
        }
    }

    #[test]
    fn motives_capped_at_three() {
        let candles = series_mean_revert();
        let daily = daily_at(12_000, 100, 82.0, 10_000);
        let input = PricePathInput {
            side: PathSide::Long,
            market_price_cents: 12_000,
            intrinsic_value_cents: 9_000,
            dcf_value_cents: Some(9_100),
            low_fair_value_cents: Some(8_800),
            high_fair_value_cents: None,
            gap_bps: Some(1500),
            daily: Some(&daily),
            candles: &candles,
            next_earnings_epoch: Some(1_710_000_000 + 2 * 86_400),
            now_epoch: 1_710_000_000,
            regime_risk: true,
            forecast_score: Some(50),
            technical_score: Some(-30),
        };
        let est = estimate_price_path(&input);
        assert!(est.path_risks.len() <= 3);
        assert!(est.path_supports.len() <= 3);
    }
}
