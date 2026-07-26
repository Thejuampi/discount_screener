//! Pillar computations from market snapshots.

use crate::engine::ChartSummary;
use crate::fetcher::{is_crypto, is_etf};

use super::math::{
    clamp, clamp_i32, fng_label_from_zone, fng_to_contrarian_score, fng_zone, linmap,
    percentile_of, realized_vol_pct, rel_return_to_score, relative_return_pct, simple_returns,
    stress_from_vix_level, stress_from_vix_percentile, term_structure_stress, vix_state,
};
use super::types::{
    BreadthSnapshot, CnnFearGreed, CrossAssetSnapshot, PillarResult, RegimeSignal, VolSnapshot,
};

// ── Breadth from screener cache ───────────────────────────────────────────────

pub fn compute_breadth(chart_summaries: &[(String, ChartSummary)]) -> BreadthSnapshot {
    let mut above200 = 0usize;
    let mut have200 = 0usize;
    let mut above50 = 0usize;
    let mut have50 = 0usize;
    let mut rsi_n = 0usize;
    let mut rsi_gt50 = 0usize;
    let mut rsi_gt70 = 0usize;
    let mut rsi_lt30 = 0usize;
    let mut macd_n = 0usize;
    let mut macd_pos = 0usize;
    let mut pos52_n = 0usize;
    let mut near_hi = 0usize;
    let mut near_lo = 0usize;
    let mut pos52_vals: Vec<f64> = Vec::new();

    for (sym, cs) in chart_summaries {
        if is_crypto(sym) || is_etf(sym) {
            continue;
        }
        let price = cs.latest_close_cents;
        if price <= 0 {
            continue;
        }
        if let Some(ma) = cs.ema200_cents {
            have200 += 1;
            if price > ma {
                above200 += 1;
            }
        }
        if let Some(ma) = cs.ema50_cents {
            have50 += 1;
            if price > ma {
                above50 += 1;
            }
        }
        if let Some(rsi) = cs.rsi {
            rsi_n += 1;
            if rsi > 50.0 {
                rsi_gt50 += 1;
            }
            if rsi > 70.0 {
                rsi_gt70 += 1;
            }
            if rsi < 30.0 {
                rsi_lt30 += 1;
            }
        }
        if let Some(h) = cs.histogram_cents {
            macd_n += 1;
            if h > 0 {
                macd_pos += 1;
            }
        }
        if let Some(p) = cs.pos_52w_pct {
            pos52_n += 1;
            pos52_vals.push(p);
            if p >= 90.0 {
                near_hi += 1;
            }
            if p <= 10.0 {
                near_lo += 1;
            }
        }
    }

    let pct = |num: usize, den: usize| -> Option<f64> {
        if den == 0 {
            None
        } else {
            Some(num as f64 / den as f64 * 100.0)
        }
    };

    pos52_vals.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let median_pos_52w = if pos52_vals.is_empty() {
        None
    } else {
        Some(pos52_vals[pos52_vals.len() / 2])
    };

    BreadthSnapshot {
        above_ma200_pct: pct(above200, have200),
        above_ma50_pct: pct(above50, have50),
        sample: have200.max(have50),
        pct_rsi_above_50: pct(rsi_gt50, rsi_n),
        pct_rsi_above_70: pct(rsi_gt70, rsi_n),
        pct_rsi_below_30: pct(rsi_lt30, rsi_n),
        pct_macd_positive: pct(macd_pos, macd_n),
        pct_near_52w_high: pct(near_hi, pos52_n),
        pct_near_52w_low: pct(near_lo, pos52_n),
        median_pos_52w,
    }
}

pub fn breadth_pillar(b: &BreadthSnapshot) -> PillarResult {
    let mut signals = Vec::new();
    let mut parts: Vec<(i32, f64)> = Vec::new();

    if let Some(p) = b.above_ma200_pct {
        // 30% → −80, 50% → 0, 70% → +80
        let s = linmap(p, 25.0, 75.0, -90.0, 90.0).round() as i32;
        parts.push((s, 0.35));
        signals.push(sig(
            "breadth_ma200",
            "Amplitud > MA200",
            "% above 200-day MA",
            s,
            Some(format!("{p:.0}% (n={})", b.sample)),
        ));
    }
    if let Some(p) = b.above_ma50_pct {
        let s = linmap(p, 25.0, 75.0, -90.0, 90.0).round() as i32;
        parts.push((s, 0.25));
        signals.push(sig(
            "breadth_ma50",
            "Amplitud > MA50",
            "% above 50-day MA",
            s,
            Some(format!("{p:.0}%")),
        ));
    }
    if let Some(p) = b.pct_rsi_above_50 {
        let s = linmap(p, 30.0, 70.0, -70.0, 70.0).round() as i32;
        parts.push((s, 0.15));
        signals.push(sig(
            "breadth_rsi",
            "% con RSI>50",
            "% with RSI>50",
            s,
            Some(format!("{p:.0}%")),
        ));
    }
    if let Some(p) = b.pct_macd_positive {
        let s = linmap(p, 30.0, 70.0, -60.0, 60.0).round() as i32;
        parts.push((s, 0.10));
        signals.push(sig(
            "breadth_macd",
            "% MACD hist+",
            "% MACD hist positive",
            s,
            Some(format!("{p:.0}%")),
        ));
    }
    if let (Some(hi), Some(lo)) = (b.pct_near_52w_high, b.pct_near_52w_low) {
        let net = hi - lo; // positive = more highs
        let s = linmap(net, -20.0, 20.0, -80.0, 80.0).round() as i32;
        parts.push((s, 0.15));
        signals.push(sig(
            "breadth_52w",
            "NH/NL proxy",
            "52w high/low breadth",
            s,
            Some(format!("hi {hi:.0}% / lo {lo:.0}%")),
        ));
    }

    let score = weighted(&parts).unwrap_or(0);
    let conf = breadth_confidence(b.sample, parts.len());
    PillarResult {
        score: clamp_i32(score, -100, 100),
        confidence_bps: conf,
        signals,
        stale: false,
    }
}

fn breadth_confidence(sample: usize, n_signals: usize) -> u32 {
    if sample == 0 || n_signals == 0 {
        return 0;
    }
    let sample_f = if sample >= 200 {
        1.0
    } else if sample >= 80 {
        0.85
    } else if sample >= 40 {
        0.65
    } else {
        0.40
    };
    let sig_f = (n_signals as f64 / 5.0).clamp(0.3, 1.0);
    ((sample_f * sig_f) * 10_000.0).round() as u32
}

// ── Trend (SPY) ───────────────────────────────────────────────────────────────

pub fn trend_pillar(spy: Option<&ChartSummary>, spy_closes: &[f64]) -> PillarResult {
    let mut signals = Vec::new();
    let mut parts: Vec<(i32, f64)> = Vec::new();

    let Some(cs) = spy else {
        return PillarResult {
            score: 0,
            confidence_bps: 0,
            signals: vec![sig(
                "trend_missing",
                "SPY sin datos",
                "SPY data missing",
                0,
                None,
            )],
            stale: true,
        };
    };

    let price = cs.latest_close_cents;
    let e50 = cs.ema50_cents;
    let e200 = cs.ema200_cents;

    if price > 0 {
        if let Some(e200) = e200 {
            let above = price > e200;
            let s = if above { 55 } else { -55 };
            parts.push((s, 0.30));
            signals.push(sig(
                "spy_ma200",
                "SPY vs MA200",
                "SPY vs 200-day MA",
                s,
                Some(if above {
                    "above".into()
                } else {
                    "below".into()
                }),
            ));
        }
        if let (Some(e50), Some(e200)) = (e50, e200) {
            let stack = if e50 > e200 { 40 } else { -40 };
            parts.push((stack, 0.20));
            signals.push(sig(
                "ema_stack",
                "EMA50 vs EMA200",
                "EMA50 vs EMA200",
                stack,
                None,
            ));
        }
        if let Some(e50) = e50 {
            let s = if price > e50 { 30 } else { -30 };
            parts.push((s, 0.15));
            signals.push(sig("spy_ma50", "SPY vs MA50", "SPY vs 50-day MA", s, None));
        }
    }

    // Distance to MA200 in % → mild mean-reversion dampener
    if let (true, Some(e200)) = (price > 0, e200) {
        if e200 > 0 {
            let dist = (price - e200) as f64 / e200 as f64 * 100.0;
            // extended above → slightly less bullish for new risk; deep below → oversold boost
            let s = if dist > 12.0 {
                -25
            } else if dist > 6.0 {
                -10
            } else if dist < -15.0 {
                25
            } else if dist < -8.0 {
                10
            } else {
                0
            };
            if s != 0 {
                parts.push((s, 0.10));
                signals.push(sig(
                    "dist_ma200",
                    "Distancia a MA200",
                    "Distance to MA200",
                    s,
                    Some(format!("{dist:.1}%")),
                ));
            }
        }
    }

    if let Some(adx) = cs.adx {
        let plus = cs.plus_di.unwrap_or(0.0);
        let minus = cs.minus_di.unwrap_or(0.0);
        let dir = if plus > minus { 1.0 } else { -1.0 };
        // ADX strength scales directional score
        let strength = linmap(adx, 15.0, 40.0, 0.0, 1.0);
        let s = (dir * strength * 70.0).round() as i32;
        parts.push((s, 0.15));
        signals.push(sig(
            "adx",
            "ADX / DI",
            "ADX trend strength",
            s,
            Some(format!("ADX {adx:.0}")),
        ));
    }

    // Slope of log-price ~60d if enough closes
    if spy_closes.len() >= 65 {
        let a = spy_closes[spy_closes.len() - 61];
        let b = *spy_closes.last().unwrap();
        if a > 0.0 {
            let ret = (b / a).ln() * 100.0; // ~log return %
            let s = linmap(ret, -12.0, 12.0, -80.0, 80.0).round() as i32;
            parts.push((s, 0.10));
            signals.push(sig(
                "slope_60d",
                "Pendiente 60d",
                "60d log slope",
                s,
                Some(format!("{ret:.1}% log")),
            ));
        }
    }

    let score = weighted(&parts).unwrap_or(0);
    let conf = if parts.is_empty() {
        0
    } else {
        let base = 5000 + parts.len() as u32 * 800;
        base.min(9500)
    };

    PillarResult {
        score: clamp_i32(score, -100, 100),
        confidence_bps: conf,
        signals,
        stale: false,
    }
}

// ── Volatility / stress ───────────────────────────────────────────────────────

pub fn vol_snapshot(vix_closes: &[f64], vix3m_closes: &[f64], spy_closes: &[f64]) -> VolSnapshot {
    let vix = vix_closes.last().copied().filter(|&v| v > 0.0);
    let vix3m = vix3m_closes.last().copied().filter(|&v| v > 0.0);
    let term = match (vix, vix3m) {
        (Some(v), Some(m)) if m > 0.0 => Some(v / m),
        _ => None,
    };

    let mut sorted = vix_closes
        .iter()
        .copied()
        .filter(|v| *v > 0.0)
        .collect::<Vec<_>>();
    sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let pctl = vix.and_then(|v| percentile_of(&sorted, v));

    let rets = simple_returns(spy_closes);
    let window = if rets.len() > 20 {
        &rets[rets.len() - 20..]
    } else {
        rets.as_slice()
    };
    let realized = realized_vol_pct(window);

    let mut stress = 0.0;
    let mut signals = Vec::new();
    let mut conf_parts = 0u32;

    if let Some(p) = pctl {
        let s = stress_from_vix_percentile(p);
        stress += s * 0.55;
        conf_parts += 1;
        signals.push(sig(
            "vix_pctl",
            "VIX percentil 1y",
            "VIX 1y percentile",
            s.round() as i32,
            Some(format!("p{p:.0}")),
        ));
    } else if let Some(v) = vix {
        let s = stress_from_vix_level(v);
        stress += s * 0.55;
        conf_parts += 1;
        signals.push(sig(
            "vix_level",
            "VIX nivel",
            "VIX level",
            s.round() as i32,
            Some(format!("{v:.1}")),
        ));
    }

    if let Some(r) = term {
        let s = term_structure_stress(r);
        stress += s; // already scaled ~−15..+35
        conf_parts += 1;
        signals.push(sig(
            "vix_term",
            "Estructura VIX/VIX3M",
            "VIX term structure",
            s.round() as i32,
            Some(format!("ratio {r:.2}")),
        ));
    }

    // VIX velocity 5d
    if vix_closes.len() >= 6 {
        let now = *vix_closes.last().unwrap();
        let prev = vix_closes[vix_closes.len() - 6];
        if prev > 0.0 {
            let d = now - prev;
            let s = linmap(d, -8.0, 12.0, -30.0, 40.0);
            stress += s * 0.25;
            conf_parts += 1;
            signals.push(sig(
                "vix_vel",
                "ΔVIX 5d",
                "5d VIX change",
                s.round() as i32,
                Some(format!("{d:+.1}")),
            ));
        }
    }

    if let Some(rv) = realized {
        // realized vol 10% calm, 25% elevated, 40% crisis
        let s = linmap(rv, 10.0, 40.0, -20.0, 50.0);
        stress += s * 0.20;
        conf_parts += 1;
        signals.push(sig(
            "realized_vol",
            "Vol realizada SPY",
            "SPY realized vol",
            s.round() as i32,
            Some(format!("{rv:.1}% ann.")),
        ));
    }

    let state = vix.map(vix_state).unwrap_or("Unknown").to_string();
    let conf = match conf_parts {
        0 => 0,
        1 => 4500,
        2 => 6500,
        3 => 8000,
        _ => 9000,
    };

    VolSnapshot {
        vix,
        vix_percentile_1y: pctl,
        vix_term_ratio: term,
        vix_state: state,
        realized_vol_pct: realized,
        stress_score: clamp_i32(stress.round() as i32, -100, 100),
        confidence_bps: conf,
        signals,
    }
}

pub fn vol_pillar(v: &VolSnapshot) -> PillarResult {
    PillarResult {
        score: v.stress_score,
        confidence_bps: v.confidence_bps,
        signals: v.signals.clone(),
        stale: v.vix.is_none(),
    }
}

// ── Sentiment (CNN + internal) ────────────────────────────────────────────────

pub fn sentiment_pillar(cnn: Option<&CnnFearGreed>, breadth: &BreadthSnapshot) -> PillarResult {
    let mut signals = Vec::new();
    let mut parts: Vec<(i32, f64)> = Vec::new();

    if let Some(fg) = cnn {
        let s = fng_to_contrarian_score(fg.score);
        parts.push((s, 0.70));
        let zone = fng_zone(fg.score);
        signals.push(sig(
            "cnn_fng",
            "CNN Fear & Greed",
            "CNN Fear & Greed",
            s,
            Some(format!("{:.0} · {}", fg.score, fng_label_from_zone(zone))),
        ));
        if let Some(prev) = fg.previous_close {
            let d = fg.score - prev;
            // rising F&G (more greed) → slightly less contrarian-buy
            let vel = (-d * 3.0).round() as i32;
            let vel = clamp_i32(vel, -20, 20);
            parts.push((vel, 0.10));
            signals.push(sig(
                "fng_delta",
                "Δ F&G vs cierre",
                "F&G vs prev close",
                vel,
                Some(format!("{d:+.1}")),
            ));
        }
    }

    // Internal euphoria/fear proxy from universe
    if let (Some(hi70), Some(lo30)) = (breadth.pct_rsi_above_70, breadth.pct_rsi_below_30) {
        // more overbought → more greed → negative contrarian score
        let net = lo30 - hi70; // positive when fear dominates internals
        let s = linmap(net, -25.0, 25.0, -70.0, 70.0).round() as i32;
        parts.push((s, 0.20));
        signals.push(sig(
            "internal_rsi",
            "RSI extremos universo",
            "Universe RSI extremes",
            s,
            Some(format!(">70:{hi70:.0}% <30:{lo30:.0}%")),
        ));
    } else if let Some(med) = breadth.median_pos_52w {
        // high median 52w pos = euphoria
        let s = linmap(med, 30.0, 80.0, 50.0, -50.0).round() as i32;
        parts.push((s, 0.20));
        signals.push(sig(
            "internal_52w",
            "Mediana pos 52w",
            "Median 52w position",
            s,
            Some(format!("{med:.0}")),
        ));
    }

    let score = weighted(&parts).unwrap_or(0);
    let conf = if cnn.is_some() {
        if parts.len() >= 2 {
            9200
        } else {
            8000
        }
    } else if !parts.is_empty() {
        4500 // internal only
    } else {
        0
    };

    PillarResult {
        score: clamp_i32(score, -100, 100),
        confidence_bps: conf,
        signals,
        stale: cnn.is_none(),
    }
}

// ── Cross-asset ───────────────────────────────────────────────────────────────

/// closes map: symbol → daily closes (oldest→newest)
pub fn cross_asset_pillar(closes: &std::collections::HashMap<String, Vec<f64>>) -> PillarResult {
    let snap = cross_asset_snapshot(closes);
    PillarResult {
        score: weighted(&[
            (
                snap.credit_score.unwrap_or(0),
                if snap.credit_score.is_some() {
                    0.35
                } else {
                    0.0
                },
            ),
            (
                snap.equity_bond_score.unwrap_or(0),
                if snap.equity_bond_score.is_some() {
                    0.25
                } else {
                    0.0
                },
            ),
            (
                snap.leadership_score.unwrap_or(0),
                if snap.leadership_score.is_some() {
                    0.25
                } else {
                    0.0
                },
            ),
            (
                snap.small_large_score.unwrap_or(0),
                if snap.small_large_score.is_some() {
                    0.15
                } else {
                    0.0
                },
            ),
        ])
        .unwrap_or(0),
        confidence_bps: snap.confidence_bps,
        signals: snap.signals,
        stale: snap.confidence_bps < 2000,
    }
}

pub fn cross_asset_snapshot(
    closes: &std::collections::HashMap<String, Vec<f64>>,
) -> CrossAssetSnapshot {
    let mut signals = Vec::new();
    let mut n = 0u32;

    let pair = |a: &str, b: &str, lookback: usize| -> Option<f64> {
        let ca = closes.get(a)?;
        let cb = closes.get(b)?;
        relative_return_pct(ca, cb, lookback)
    };

    let credit = pair("HYG", "IEF", 20).or_else(|| pair("JNK", "TLT", 20));
    let credit_score = credit.map(|r| rel_return_to_score(r, 4.0));
    if let (Some(r), Some(s)) = (credit, credit_score) {
        n += 1;
        signals.push(sig(
            "credit_hy_ie",
            "Crédito HY vs IE",
            "HY vs IE credit",
            s,
            Some(format!("{r:+.1}% 20d")),
        ));
    }

    let eq_bond = pair("SPY", "TLT", 20).or_else(|| pair("SPY", "IEF", 20));
    let equity_bond_score = eq_bond.map(|r| rel_return_to_score(r, 5.0));
    if let (Some(r), Some(s)) = (eq_bond, equity_bond_score) {
        n += 1;
        signals.push(sig(
            "eq_bond",
            "Equity vs bonds",
            "Equity vs bonds",
            s,
            Some(format!("{r:+.1}% 20d")),
        ));
    }

    // Leadership: average growth ETFs vs defensive
    let growth = avg_last_return(closes, &["XLK", "XLY"], 20);
    let def = avg_last_return(closes, &["XLP", "XLU", "XLV"], 20);
    let leadership_score = match (growth, def) {
        (Some(g), Some(d)) => {
            let r = g - d;
            let s = rel_return_to_score(r, 4.0);
            n += 1;
            signals.push(sig(
                "leadership",
                "Crecimiento vs defensivo",
                "Growth vs defensive",
                s,
                Some(format!("{r:+.1}% 20d")),
            ));
            Some(s)
        }
        _ => None,
    };

    let small = pair("IWM", "SPY", 20);
    let small_large_score = small.map(|r| rel_return_to_score(r, 4.0));
    if let (Some(r), Some(s)) = (small, small_large_score) {
        n += 1;
        signals.push(sig(
            "small_large",
            "IWM vs SPY",
            "IWM vs SPY",
            s,
            Some(format!("{r:+.1}% 20d")),
        ));
    }

    let conf = match n {
        0 => 0,
        1 => 4000,
        2 => 6000,
        3 => 7800,
        _ => 9000,
    };

    CrossAssetSnapshot {
        credit_score,
        equity_bond_score,
        leadership_score,
        small_large_score,
        signals,
        confidence_bps: conf,
    }
}

fn avg_last_return(
    closes: &std::collections::HashMap<String, Vec<f64>>,
    syms: &[&str],
    lookback: usize,
) -> Option<f64> {
    let mut acc = 0.0;
    let mut n = 0;
    for s in syms {
        if let Some(c) = closes.get(*s) {
            if c.len() > lookback {
                let a = c[c.len() - 1 - lookback];
                let b = *c.last()?;
                if a > 0.0 {
                    acc += (b - a) / a * 100.0;
                    n += 1;
                }
            }
        }
    }
    if n == 0 {
        None
    } else {
        Some(acc / n as f64)
    }
}

// ── Quality / fragility ───────────────────────────────────────────────────────

pub fn quality_pillar(
    breadth: &BreadthSnapshot,
    spy_above_ma200: Option<bool>,
    avg_corr_milli: Option<i32>,
    stress: i32,
    cross: &CrossAssetSnapshot,
) -> PillarResult {
    let mut signals = Vec::new();
    let mut parts: Vec<(i32, f64)> = Vec::new();

    // Narrow rally: index up but breadth weak → fragile (negative quality)
    if let (Some(true), Some(b200)) = (spy_above_ma200, breadth.above_ma200_pct) {
        if b200 < 45.0 {
            let s = linmap(b200, 25.0, 45.0, -80.0, -20.0).round() as i32;
            parts.push((s, 0.30));
            signals.push(sig(
                "narrow_rally",
                "Rally estrecho",
                "Narrow rally",
                s,
                Some(format!("SPY↑ breadth {b200:.0}%")),
            ));
        } else if b200 > 55.0 {
            parts.push((40, 0.20));
            signals.push(sig(
                "broad_participation",
                "Participación amplia",
                "Broad participation",
                40,
                Some(format!("{b200:.0}%")),
            ));
        }
    }

    if let Some(c) = avg_corr_milli {
        // high corr = fragile
        let corr = c as f64 / 1000.0;
        let s = linmap(corr, 0.20, 0.80, 50.0, -70.0).round() as i32;
        parts.push((s, 0.25));
        signals.push(sig(
            "avg_corr",
            "Correlación media",
            "Avg pairwise corr",
            s,
            Some(format!("{corr:.2}")),
        ));
    }

    // Credit stress hurts quality
    if let Some(cs) = cross.credit_score {
        parts.push((cs, 0.20));
        signals.push(sig(
            "credit_quality",
            "Calidad crédito",
            "Credit quality",
            cs,
            None,
        ));
    }

    // Extreme stress + weak breadth = fragile
    if stress > 50 {
        if let Some(b) = breadth.above_ma200_pct {
            if b < 40.0 {
                parts.push((-50, 0.15));
                signals.push(sig(
                    "stress_breadth",
                    "Estrés + breadth débil",
                    "Stress + weak breadth",
                    -50,
                    None,
                ));
            }
        }
    }

    // Dispersion proxy: if many near 52w extremes mixed → healthier
    if let (Some(hi), Some(lo)) = (breadth.pct_near_52w_high, breadth.pct_near_52w_low) {
        let both = hi + lo;
        // very low both extremes in a rising market = low dispersion fragility
        let s = if both < 8.0 {
            -25
        } else if both > 25.0 {
            20
        } else {
            0
        };
        if s != 0 {
            parts.push((s, 0.10));
            signals.push(sig(
                "dispersion",
                "Dispersión 52w",
                "52w dispersion",
                s,
                Some(format!("ext {both:.0}%")),
            ));
        }
    }

    let score = weighted(&parts).unwrap_or(0);
    let conf = if parts.is_empty() {
        0
    } else {
        (4000 + parts.len() as u32 * 1000).min(8800)
    };

    PillarResult {
        score: clamp_i32(score, -100, 100),
        confidence_bps: conf,
        signals,
        stale: false,
    }
}

// ── SPY drawdown ──────────────────────────────────────────────────────────────

pub fn spy_drawdown_from_ath_pct(closes: &[f64]) -> Option<f64> {
    if closes.is_empty() {
        return None;
    }
    let ath = closes.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
    let last = *closes.last()?;
    if ath <= 0.0 {
        return None;
    }
    Some(clamp((ath - last) / ath * 100.0, 0.0, 100.0))
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fn weighted(parts: &[(i32, f64)]) -> Option<i32> {
    let mut num = 0.0;
    let mut den = 0.0;
    for &(s, w) in parts {
        if w > 0.0 {
            num += s as f64 * w;
            den += w;
        }
    }
    if den <= 0.0 {
        None
    } else {
        Some((num / den).round() as i32)
    }
}

fn sig(id: &str, es: &str, en: &str, contribution: i32, detail: Option<String>) -> RegimeSignal {
    RegimeSignal {
        id: id.into(),
        label_es: es.into(),
        label_en: en.into(),
        contribution,
        detail,
        hint_es: None,
        hint_en: None,
    }
}

/// Average pairwise correlation (milli) for a sample of close series.
pub fn avg_pairwise_corr_milli(series: &[Vec<f64>], lookback: usize) -> Option<i32> {
    if series.len() < 3 {
        return None;
    }
    let returns: Vec<Vec<f64>> = series
        .iter()
        .filter_map(|c| {
            if c.len() < lookback + 1 {
                return None;
            }
            let slice = &c[c.len() - (lookback + 1)..];
            let r = simple_returns(slice);
            if r.len() < 30 {
                None
            } else {
                Some(r)
            }
        })
        .collect();
    if returns.len() < 3 {
        return None;
    }
    let mut sum = 0.0;
    let mut n = 0;
    for i in 0..returns.len() {
        for j in (i + 1)..returns.len() {
            if let Some(c) = pearson(&returns[i], &returns[j]) {
                sum += c;
                n += 1;
            }
        }
    }
    if n == 0 {
        None
    } else {
        Some(((sum / n as f64) * 1000.0).round() as i32)
    }
}

fn pearson(xs: &[f64], ys: &[f64]) -> Option<f64> {
    let n = xs.len().min(ys.len());
    if n < 30 {
        return None;
    }
    let xs = &xs[xs.len() - n..];
    let ys = &ys[ys.len() - n..];
    let mx = xs.iter().sum::<f64>() / n as f64;
    let my = ys.iter().sum::<f64>() / n as f64;
    let mut cov = 0.0;
    let mut vx = 0.0;
    let mut vy = 0.0;
    for i in 0..n {
        let dx = xs[i] - mx;
        let dy = ys[i] - my;
        cov += dx * dy;
        vx += dx * dx;
        vy += dy * dy;
    }
    if vx <= 0.0 || vy <= 0.0 {
        return None;
    }
    Some(cov / (vx.sqrt() * vy.sqrt()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::ChartSummary;

    fn cs(price: i64, e50: i64, e200: i64, rsi: f64, pos: f64) -> ChartSummary {
        ChartSummary {
            latest_close_cents: price,
            ema20_cents: Some(price),
            ema50_cents: Some(e50),
            ema200_cents: Some(e200),
            macd_cents: Some(1),
            signal_cents: Some(0),
            histogram_cents: Some(1),
            rsi: Some(rsi),
            rsi_slope: None,
            adx: Some(25.0),
            plus_di: Some(30.0),
            minus_di: Some(15.0),
            bb_upper_cents: None,
            bb_middle_cents: None,
            bb_lower_cents: None,
            bb_percent_b: None,
            bb_bandwidth: None,
            obv_slope: None,
            volume_ratio: None,
            atr_cents: None,
            high_52w_cents: None,
            low_52w_cents: None,
            pos_52w_pct: Some(pos),
        }
    }

    #[test]
    fn breadth_bullish_universe() {
        let rows: Vec<(String, ChartSummary)> = (0..20)
            .map(|i| (format!("S{i}"), cs(11_000, 10_000, 9_000, 58.0, 75.0)))
            .collect();
        let b = compute_breadth(&rows);
        assert!(b.above_ma200_pct.unwrap() > 90.0);
        let p = breadth_pillar(&b);
        assert!(p.score > 30);
        assert!(p.confidence_bps > 0);
    }

    #[test]
    fn trend_above_ma200_positive() {
        let spy = cs(50_000, 49_000, 45_000, 55.0, 80.0);
        let closes: Vec<f64> = (0..80).map(|i| 400.0 + i as f64).collect();
        let p = trend_pillar(Some(&spy), &closes);
        assert!(p.score > 0);
    }

    #[test]
    fn fng_fear_positive_sentiment() {
        let cnn = CnnFearGreed {
            score: 15.0,
            rating: "Extreme Fear".into(),
            previous_close: Some(20.0),
            previous_1_week: Some(25.0),
            historical: vec![],
            fetched_at_epoch: 0,
        };
        let b = BreadthSnapshot::default();
        let p = sentiment_pillar(Some(&cnn), &b);
        assert!(
            p.score > 50,
            "extreme fear should be contrarian buy, got {}",
            p.score
        );
    }
}
