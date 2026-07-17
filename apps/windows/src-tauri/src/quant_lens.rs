//! Quant Lens multi-section read-only report (Android QuantLensEngine port, condensed).

use serde::Serialize;

use crate::dcf_model::DcfAnalysis;
use crate::engine::{CandidateRow, ChartSummary, HistoricalCandle, SymbolDetail};

#[derive(Debug, Clone, Serialize)]
pub struct QuantLensSection {
    pub id: String,
    pub title: String,
    pub status: String,
    pub summary: String,
    pub metrics: Vec<(String, String)>,
}

#[derive(Debug, Clone, Serialize)]
pub struct QuantLensReport {
    pub symbol: String,
    pub primary_status: String,
    pub sections: Vec<QuantLensSection>,
    pub model_version: i32,
}

pub fn analyze(
    detail: &SymbolDetail,
    daily_candles: Option<&[HistoricalCandle]>,
    dcf: Option<&DcfAnalysis>,
    opportunity: Option<&CandidateRow>,
    peers: &[(String, Vec<HistoricalCandle>)],
) -> QuantLensReport {
    let mut sections = Vec::new();

    sections.push(evidence_strength(detail, daily_candles, dcf, opportunity));
    sections.push(expected_value_range(detail, dcf));
    sections.push(correlation_risk(daily_candles, peers));
    sections.push(trend_reliability(
        daily_candles,
        detail.chart_summary.as_ref(),
    ));
    sections.push(horizon_context(daily_candles));
    sections.push(similar_setups(opportunity, peers.len()));

    let primary = worst_status(sections.iter().map(|s| s.status.as_str()));
    QuantLensReport {
        symbol: detail.symbol.clone(),
        primary_status: primary.into(),
        sections,
        model_version: 2,
    }
}

fn evidence_strength(
    detail: &SymbolDetail,
    candles: Option<&[HistoricalCandle]>,
    dcf: Option<&DcfAnalysis>,
    opp: Option<&CandidateRow>,
) -> QuantLensSection {
    let mut support = 0;
    let mut conflict = 0;
    match detail.gap_bps {
        Some(g) if g >= 1000 => support += 1,
        Some(g) if g < 0 => conflict += 1,
        _ => {}
    }
    if detail.low_fair_value_cents.is_some() && detail.high_fair_value_cents.is_some() {
        support += 1;
    }
    if dcf.is_some_and(|d| d.base_intrinsic_value_cents > 0) {
        support += 1;
    }
    if candles.is_some_and(|c| c.len() >= 20) {
        support += 1;
    }
    if opp.is_some_and(|o| o.dcf_value_cents.is_some()) {
        support += 1;
    }
    let status = match (support, conflict) {
        (s, _) if s >= 4 => "Strong",
        (s, c) if s >= 2 && c == 0 => "Provisional",
        (s, c) if s >= 1 && c > 0 => "Mixed",
        (0, _) => "Unavailable",
        _ => "Sparse",
    };
    QuantLensSection {
        id: "evidence".into(),
        title: "Evidence strength".into(),
        status: status.into(),
        summary: format!("{support} supporting · {conflict} conflicting signals"),
        metrics: vec![
            ("support".into(), support.to_string()),
            ("conflict".into(), conflict.to_string()),
            (
                "gap_bps".into(),
                detail
                    .gap_bps
                    .map(|g| g.to_string())
                    .unwrap_or_else(|| "null".into()),
            ),
        ],
    }
}

fn expected_value_range(detail: &SymbolDetail, dcf: Option<&DcfAnalysis>) -> QuantLensSection {
    let (low, base, high, source) = if let Some(a) = dcf {
        (
            a.bear_intrinsic_value_cents,
            a.base_intrinsic_value_cents,
            a.bull_intrinsic_value_cents,
            "DCF scenarios",
        )
    } else {
        (
            detail.low_fair_value_cents.unwrap_or(0),
            detail.intrinsic_value_cents,
            detail.high_fair_value_cents.unwrap_or(0),
            "Analyst range",
        )
    };
    let status = if base > 0 { "Available" } else { "Unavailable" };
    let weighted = if low > 0 && high > 0 {
        (low + 2 * base + high) / 4
    } else {
        base
    };
    let upside = if detail.market_price_cents > 0 && weighted > 0 {
        ((weighted - detail.market_price_cents) as f64 / detail.market_price_cents as f64
            * 10_000.0)
            .round() as i32
    } else {
        0
    };
    QuantLensSection {
        id: "ev_range".into(),
        title: "Expected value range".into(),
        status: status.into(),
        summary: format!("{source}: weighted FV upside {upside} bps"),
        metrics: vec![
            ("low_cents".into(), low.to_string()),
            ("base_cents".into(), base.to_string()),
            ("high_cents".into(), high.to_string()),
            ("upside_bps".into(), upside.to_string()),
        ],
    }
}

fn correlation_risk(
    self_candles: Option<&[HistoricalCandle]>,
    peers: &[(String, Vec<HistoricalCandle>)],
) -> QuantLensSection {
    let Some(mine) = self_candles.filter(|c| c.len() >= 30) else {
        return QuantLensSection {
            id: "correlation".into(),
            title: "Correlation risk".into(),
            status: "Insufficient".into(),
            summary: "Need ≥30 daily closes for correlation".into(),
            metrics: vec![],
        };
    };
    let my_ret = returns(mine);
    let mut pairs = Vec::new();
    for (sym, candles) in peers {
        if candles.len() < 30 {
            continue;
        }
        let their = returns(candles);
        if let Some(rho) = pearson(&my_ret, &their) {
            pairs.push((sym.clone(), rho));
        }
    }
    pairs.sort_by(|a, b| {
        b.1.abs()
            .partial_cmp(&a.1.abs())
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    pairs.truncate(3);
    let high = pairs.iter().filter(|(_, r)| r.abs() >= 0.85).count()
        + if pairs.iter().filter(|(_, r)| r.abs() >= 0.70).count() >= 2 {
            1
        } else {
            0
        };
    let status = if pairs.len() < 3 {
        "Partial"
    } else if high > 0 {
        "High"
    } else {
        "Moderate"
    };
    QuantLensSection {
        id: "correlation".into(),
        title: "Correlation risk".into(),
        status: status.into(),
        summary: format!("Top peer |ρ| pairs: {}", pairs.len()),
        metrics: pairs
            .into_iter()
            .map(|(s, r)| (s, format!("{r:.2}")))
            .collect(),
    }
}

fn trend_reliability(
    candles: Option<&[HistoricalCandle]>,
    summary: Option<&ChartSummary>,
) -> QuantLensSection {
    let Some(c) = candles.filter(|x| x.len() >= 20) else {
        return QuantLensSection {
            id: "trend".into(),
            title: "Trend reliability".into(),
            status: "Insufficient".into(),
            summary: "Need ≥20 bars".into(),
            metrics: vec![],
        };
    };
    let closes: Vec<f64> = c.iter().map(|x| x.close_cents as f64).collect();
    let (r2, slope) = ols_r2_slope(&closes);
    let move_bps = if closes.first().copied().unwrap_or(0.0) > 0.0 {
        ((closes.last().unwrap() / closes.first().unwrap() - 1.0) * 10_000.0).abs()
    } else {
        0.0
    };
    let status = if move_bps < 200.0 {
        "Flat"
    } else if r2 >= 0.6 {
        "Reliable"
    } else if r2 >= 0.3 {
        "Moderate"
    } else {
        "Noisy"
    };
    let rsi = summary
        .and_then(|s| s.rsi)
        .map(|r| format!("{r:.1}"))
        .unwrap_or_else(|| "—".into());
    QuantLensSection {
        id: "trend".into(),
        title: "Trend reliability".into(),
        status: status.into(),
        summary: format!("R²={r2:.2} · move={move_bps:.0} bps · RSI {rsi}"),
        metrics: vec![
            ("r2".into(), format!("{r2:.3}")),
            ("slope".into(), format!("{slope:.4}")),
        ],
    }
}

fn horizon_context(candles: Option<&[HistoricalCandle]>) -> QuantLensSection {
    let Some(c) = candles.filter(|x| x.len() >= 60) else {
        return QuantLensSection {
            id: "horizon".into(),
            title: "Horizon context".into(),
            status: "Insufficient".into(),
            summary: "Need longer daily history".into(),
            metrics: vec![],
        };
    };
    let mut moves = Vec::new();
    for w in [5usize, 21, 63] {
        if c.len() > w {
            let a = c[c.len() - 1 - w].close_cents as f64;
            let b = c[c.len() - 1].close_cents as f64;
            if a > 0.0 {
                moves.push(((b / a - 1.0).abs() * 10_000.0) as i32);
            }
        }
    }
    moves.sort();
    let med = moves.get(moves.len() / 2).copied().unwrap_or(0);
    QuantLensSection {
        id: "horizon".into(),
        title: "Horizon context".into(),
        status: if moves.len() >= 3 {
            "Available"
        } else {
            "Partial"
        }
        .into(),
        summary: format!("Median absolute move across 1w/1m/3m windows: {med} bps"),
        metrics: moves
            .into_iter()
            .enumerate()
            .map(|(i, v)| (format!("window_{i}"), v.to_string()))
            .collect(),
    }
}

fn similar_setups(opp: Option<&CandidateRow>, peer_count: usize) -> QuantLensSection {
    let status = if peer_count >= 3 {
        "Available"
    } else {
        "Partial"
    };
    let score = opp.and_then(|o| o.gap_bps);
    let gap_ctx = score
        .map(|g| format!("{g} bps"))
        .unwrap_or_else(|| "n/a".into());
    QuantLensSection {
        id: "similar".into(),
        title: "Similar setups".into(),
        status: status.into(),
        summary: format!("Universe peers available: {peer_count} · gap context {gap_ctx}"),
        metrics: vec![("peers".into(), peer_count.to_string())],
    }
}

fn returns(c: &[HistoricalCandle]) -> Vec<f64> {
    c.windows(2)
        .filter_map(|w| {
            let a = w[0].close_cents as f64;
            let b = w[1].close_cents as f64;
            if a > 0.0 {
                Some(b / a - 1.0)
            } else {
                None
            }
        })
        .collect()
}

fn pearson(a: &[f64], b: &[f64]) -> Option<f64> {
    let n = a.len().min(b.len());
    if n < 30 {
        return None;
    }
    let a = &a[a.len() - n..];
    let b = &b[b.len() - n..];
    let mean_a = a.iter().sum::<f64>() / n as f64;
    let mean_b = b.iter().sum::<f64>() / n as f64;
    let mut num = 0.0;
    let mut da = 0.0;
    let mut db = 0.0;
    for i in 0..n {
        let x = a[i] - mean_a;
        let y = b[i] - mean_b;
        num += x * y;
        da += x * x;
        db += y * y;
    }
    let den = (da * db).sqrt();
    if den == 0.0 {
        None
    } else {
        Some(num / den)
    }
}

fn ols_r2_slope(y: &[f64]) -> (f64, f64) {
    let n = y.len() as f64;
    if n < 2.0 {
        return (0.0, 0.0);
    }
    let mean_x = (n - 1.0) / 2.0;
    let mean_y = y.iter().sum::<f64>() / n;
    let mut num = 0.0;
    let mut den = 0.0;
    let mut ss_tot = 0.0;
    for (i, yi) in y.iter().enumerate() {
        let x = i as f64;
        num += (x - mean_x) * (yi - mean_y);
        den += (x - mean_x) * (x - mean_x);
        ss_tot += (yi - mean_y) * (yi - mean_y);
    }
    let slope = if den == 0.0 { 0.0 } else { num / den };
    let intercept = mean_y - slope * mean_x;
    let mut ss_res = 0.0;
    for (i, yi) in y.iter().enumerate() {
        let pred = intercept + slope * i as f64;
        ss_res += (yi - pred) * (yi - pred);
    }
    let r2 = if ss_tot == 0.0 {
        0.0
    } else {
        (1.0 - ss_res / ss_tot).clamp(0.0, 1.0)
    };
    (r2, slope)
}

fn worst_status<'a>(statuses: impl Iterator<Item = &'a str>) -> &'a str {
    let order = [
        "Unavailable",
        "Insufficient",
        "Sparse",
        "Mixed",
        "High",
        "Noisy",
        "Partial",
        "Flat",
        "Moderate",
        "Provisional",
        "Reliable",
        "Available",
        "Strong",
    ];
    let mut best_idx = order.len();
    let mut best = "Available";
    for s in statuses {
        if let Some(i) = order.iter().position(|x| *x == s) {
            if i < best_idx {
                best_idx = i;
                best = s;
            }
        }
    }
    best
}
