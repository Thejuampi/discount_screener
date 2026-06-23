// Classic chart-pattern recognition (technical-analysis "structures").
//
// Works from swing pivots + trendline fits over a candle series. Detects:
//   Double Top / Double Bottom, Head & Shoulders / Inverse H&S,
//   Symmetric / Ascending / Descending Triangle, Rising / Falling Wedge, Channel.
//
// Shared by the scalping engine (intraday TFs) and the screener's long-term
// chart analysis (daily/weekly). Conservative thresholds to limit false positives.

use serde::Serialize;

use crate::engine::HistoricalCandle;

#[derive(Serialize, Clone, Debug)]
pub struct PatternPoint { pub epoch: i64, pub price_cents: i64 }

#[derive(Serialize, Clone, Debug)]
pub struct PatternLine { pub points: Vec<PatternPoint> }

#[derive(Serialize, Clone, Debug)]
pub struct ChartPattern {
    pub kind: &'static str,       // machine id (e.g. "DoubleTop")
    pub direction: &'static str,  // "Bullish" | "Bearish" | "Neutral"
    pub confidence: i32,          // 0..100
    pub key_level_cents: i64,     // neckline / breakout trigger
    pub target_cents: i64,        // measured-move objective
    pub label_es: String,
    pub label_en: String,
    pub forming: bool,            // true = potential (forming), false = confirmed (broke level)
    pub timeframe: String,        // TF the pattern was detected on (e.g. "15m", "1D")
    pub explanation_es: String,
    pub explanation_en: String,
    pub lines: Vec<PatternLine>,  // trendlines / necklines to draw on the chart
}

/// Plain-language description + most-likely resolution for each pattern kind.
fn explain(kind: &str) -> (&'static str, &'static str) {
    match kind {
        "DoubleTop" => (
            "Dos máximos a nivel similar tras una subida: el precio chocó dos veces con la misma resistencia y no pudo superarla. Resolución más probable: BAJISTA al romper el valle intermedio (neckline); el objetivo es la altura del patrón proyectada hacia abajo.",
            "Two peaks at a similar level after a rally: price hit the same resistance twice and failed. Most likely resolution: BEARISH on a break of the intervening trough (neckline); target is the pattern height projected down."),
        "DoubleBottom" => (
            "Dos mínimos a nivel similar tras una caída: el soporte aguantó dos veces. Resolución más probable: ALCISTA al romper el techo intermedio; objetivo = altura del patrón hacia arriba.",
            "Two troughs at a similar level after a decline: support held twice. Most likely resolution: BULLISH on a break of the intervening peak; target = pattern height projected up."),
        "HeadAndShoulders" => (
            "Tres máximos: hombro, una cabeza más alta y otro hombro a la altura del primero. Señala agotamiento de la tendencia alcista. Resolución más probable: BAJISTA al romper la neckline que une los valles.",
            "Three peaks: a shoulder, a higher head, and a shoulder near the first. Signals exhaustion of the uptrend. Most likely resolution: BEARISH on a break of the neckline joining the troughs."),
        "InverseHeadAndShoulders" => (
            "Tres mínimos: hombro, una cabeza más baja y otro hombro. Señala agotamiento de la tendencia bajista. Resolución más probable: ALCISTA al romper la neckline.",
            "Three troughs: a shoulder, a lower head, and a shoulder. Signals exhaustion of the downtrend. Most likely resolution: BULLISH on a break of the neckline."),
        "SymmetricTriangle" => (
            "Máximos descendentes y mínimos ascendentes que convergen: compresión de volatilidad antes de un movimiento fuerte. Suele ser de continuación; la dirección la define la ruptura de una de las líneas.",
            "Lower highs and higher lows converging: volatility compression before a strong move. Usually a continuation; direction is set by which trendline breaks."),
        "AscendingTriangle" => (
            "Resistencia horizontal con mínimos ascendentes: la presión compradora empuja contra un techo fijo. Resolución más probable: ALCISTA al romper la resistencia.",
            "Flat resistance with rising lows: buyers press against a fixed ceiling. Most likely resolution: BULLISH on a break of the resistance."),
        "DescendingTriangle" => (
            "Soporte horizontal con máximos descendentes: la presión vendedora empuja contra un piso fijo. Resolución más probable: BAJISTA al romper el soporte.",
            "Flat support with falling highs: sellers press against a fixed floor. Most likely resolution: BEARISH on a break of the support."),
        "RisingWedge" => (
            "Dos líneas ascendentes que convergen (los mínimos suben más rápido que los máximos): impulso comprador que se agota. Resolución más probable: BAJISTA.",
            "Two rising lines converging (lows rise faster than highs): fading buying momentum. Most likely resolution: BEARISH."),
        "FallingWedge" => (
            "Dos líneas descendentes que convergen (los máximos bajan más rápido que los mínimos): presión vendedora que se agota. Resolución más probable: ALCISTA.",
            "Two falling lines converging (highs fall faster than lows): fading selling pressure. Most likely resolution: BULLISH."),
        "Channel" => (
            "Máximos y mínimos sobre dos líneas paralelas: la tendencia avanza ordenada dentro del canal. Se opera el rebote en los bordes o la ruptura del canal.",
            "Highs and lows on two parallel lines: the trend advances in an orderly channel. Trade the bounce at the edges or the channel breakout."),
        _ => ("", ""),
    }
}

struct Pivot { i: usize, price: f64, high: bool }

/// Swing pivots: a high/low that dominates ±k neighbours.
fn pivots(c: &[HistoricalCandle], k: usize) -> Vec<Pivot> {
    let n = c.len();
    let mut out = Vec::new();
    if n < 2 * k + 1 { return out; }
    for i in k..n - k {
        let hi = c[i].high_cents as f64;
        let lo = c[i].low_cents as f64;
        let is_high = (i - k..=i + k).all(|j| c[j].high_cents as f64 <= hi)
            && (i - k..i).chain(i + 1..=i + k).any(|j| (c[j].high_cents as f64) < hi);
        let is_low = (i - k..=i + k).all(|j| c[j].low_cents as f64 >= lo)
            && (i - k..i).chain(i + 1..=i + k).any(|j| (c[j].low_cents as f64) > lo);
        if is_high { out.push(Pivot { i, price: hi, high: true }); }
        if is_low { out.push(Pivot { i, price: lo, high: false }); }
    }
    out
}

fn eq(a: f64, b: f64, tol: f64) -> bool {
    let m = a.abs().max(b.abs());
    m > 0.0 && (a - b).abs() / m <= tol
}

/// Least-squares slope (price per bar) over (index, price) points.
fn slope(pts: &[(f64, f64)]) -> f64 {
    let n = pts.len() as f64;
    if n < 2.0 { return 0.0; }
    let (sx, sy) = pts.iter().fold((0.0, 0.0), |(ax, ay), (x, y)| (ax + x, ay + y));
    let sxy: f64 = pts.iter().map(|(x, y)| x * y).sum();
    let sxx: f64 = pts.iter().map(|(x, _)| x * x).sum();
    let den = n * sxx - sx * sx;
    if den.abs() < 1e-9 { 0.0 } else { (n * sxy - sx * sy) / den }
}

#[allow(clippy::too_many_arguments)]
fn mk(kind: &'static str, direction: &'static str, confidence: i32, key: f64, target: f64,
      es: &str, en: &str, forming: bool, tf: &str, lines: Vec<PatternLine>) -> ChartPattern {
    let (ex_es, ex_en) = explain(kind);
    ChartPattern {
        kind, direction, confidence: confidence.clamp(0, 100),
        key_level_cents: key.round() as i64, target_cents: target.round() as i64,
        label_es: es.to_string(), label_en: en.to_string(), forming,
        timeframe: tf.to_string(), explanation_es: ex_es.to_string(), explanation_en: ex_en.to_string(),
        lines,
    }
}

fn seg(a: (i64, f64), b: (i64, f64)) -> PatternLine {
    PatternLine { points: vec![
        PatternPoint { epoch: a.0, price_cents: a.1.round() as i64 },
        PatternPoint { epoch: b.0, price_cents: b.1.round() as i64 },
    ] }
}

/// Detect chart patterns. `k` = pivot strength (2 intraday, 3 for daily/weekly).
/// `tf` is a human label for the timeframe the candles represent (e.g. "15m", "1D").
pub fn detect(c: &[HistoricalCandle], k: usize, tf: &str) -> Vec<ChartPattern> {
    let n = c.len();
    if n < 2 * k + 6 { return Vec::new(); }
    let pv = pivots(c, k);
    let highs: Vec<&Pivot> = pv.iter().filter(|p| p.high).collect();
    let lows: Vec<&Pivot> = pv.iter().filter(|p| !p.high).collect();
    let last = c[n - 1].close_cents as f64;
    let ep = |i: usize| c[i].epoch_seconds;
    let last_ep = c[n - 1].epoch_seconds;
    let mut out: Vec<ChartPattern> = Vec::new();

    // ── Double Top: two ~equal highs with a trough between ──
    if highs.len() >= 2 && !lows.is_empty() {
        let h2 = highs[highs.len() - 1];
        let h1 = highs[highs.len() - 2];
        if let Some(mid) = lows.iter().filter(|l| l.i > h1.i && l.i < h2.i).min_by(|a, b| a.price.partial_cmp(&b.price).unwrap()) {
            let peak = h1.price.max(h2.price);
            if eq(h1.price, h2.price, 0.03) && (peak - mid.price) / peak > 0.025 {
                let neck = mid.price;
                let forming = last > neck;
                let lines = vec![
                    seg((ep(h1.i), h1.price), (ep(h2.i), h2.price)),
                    seg((ep(h1.i), neck), (last_ep, neck)),
                ];
                out.push(mk("DoubleTop", "Bearish", if forming { 60 } else { 78 }, neck, neck - (peak - neck),
                    "Doble techo", "Double Top", forming, tf, lines));
            }
        }
    }
    // ── Double Bottom: two ~equal lows with a peak between ──
    if lows.len() >= 2 && !highs.is_empty() {
        let l2 = lows[lows.len() - 1];
        let l1 = lows[lows.len() - 2];
        if let Some(mid) = highs.iter().filter(|h| h.i > l1.i && h.i < l2.i).max_by(|a, b| a.price.partial_cmp(&b.price).unwrap()) {
            let trough = l1.price.min(l2.price);
            if eq(l1.price, l2.price, 0.03) && (mid.price - trough) / mid.price > 0.025 {
                let neck = mid.price;
                let forming = last < neck;
                let lines = vec![
                    seg((ep(l1.i), l1.price), (ep(l2.i), l2.price)),
                    seg((ep(l1.i), neck), (last_ep, neck)),
                ];
                out.push(mk("DoubleBottom", "Bullish", if forming { 60 } else { 78 }, neck, neck + (neck - trough),
                    "Doble piso", "Double Bottom", forming, tf, lines));
            }
        }
    }
    // ── Head & Shoulders: 3 highs, middle highest, shoulders ~equal ──
    if highs.len() >= 3 {
        let (l, h, r) = (highs[highs.len() - 3], highs[highs.len() - 2], highs[highs.len() - 1]);
        if h.price > l.price && h.price > r.price && eq(l.price, r.price, 0.04) {
            let neck = lows.iter().filter(|x| x.i > l.i && x.i < r.i).map(|x| x.price).fold(f64::MAX, f64::min);
            if neck.is_finite() {
                let forming = last > neck;
                let lines = vec![
                    seg((ep(l.i), l.price), (ep(h.i), h.price)),
                    seg((ep(h.i), h.price), (ep(r.i), r.price)),
                    seg((ep(l.i), neck), (last_ep, neck)),
                ];
                out.push(mk("HeadAndShoulders", "Bearish", if forming { 58 } else { 80 }, neck, neck - (h.price - neck),
                    "Hombro-Cabeza-Hombro", "Head & Shoulders", forming, tf, lines));
            }
        }
    }
    // ── Inverse H&S: 3 lows, middle lowest, shoulders ~equal ──
    if lows.len() >= 3 {
        let (l, h, r) = (lows[lows.len() - 3], lows[lows.len() - 2], lows[lows.len() - 1]);
        if h.price < l.price && h.price < r.price && eq(l.price, r.price, 0.04) {
            let neck = highs.iter().filter(|x| x.i > l.i && x.i < r.i).map(|x| x.price).fold(f64::MIN, f64::max);
            if neck.is_finite() {
                let forming = last < neck;
                let lines = vec![
                    seg((ep(l.i), l.price), (ep(h.i), h.price)),
                    seg((ep(h.i), h.price), (ep(r.i), r.price)),
                    seg((ep(l.i), neck), (last_ep, neck)),
                ];
                out.push(mk("InverseHeadAndShoulders", "Bullish", if forming { 58 } else { 80 }, neck, neck + (neck - h.price),
                    "HCH invertido", "Inverse Head & Shoulders", forming, tf, lines));
            }
        }
    }
    // ── Triangles / Wedges / Channel: trendline fit on recent pivots ──
    if highs.len() >= 2 && lows.len() >= 2 {
        let hp: Vec<(f64, f64)> = highs.iter().rev().take(4).rev().map(|p| (p.i as f64, p.price)).collect();
        let lp: Vec<(f64, f64)> = lows.iter().rev().take(4).rev().map(|p| (p.i as f64, p.price)).collect();
        let sh = slope(&hp);
        let sl = slope(&lp);
        let span = (n as f64).max(1.0);
        let price = last.max(1.0);
        // Convert to %-move across the window for thresholding.
        let sh_pct = sh * span / price;
        let sl_pct = sl * span / price;
        let flat = 0.02;
        let conv = (sh < 0.0 && sl > 0.0) // both moving toward each other
            || (sh.signum() == sl.signum() && (sh - sl).abs() > 0.0);
        let hi_now = highs.last().unwrap().price;
        let lo_now = lows.last().unwrap().price;
        let height = (hi_now - lo_now).abs();

        let tri: Option<(&'static str, &'static str, &str, &str)> =
            if sh_pct.abs() < flat && sl_pct > flat {
                Some(("AscendingTriangle", "Bullish", "Triángulo ascendente", "Ascending Triangle"))
            } else if sl_pct.abs() < flat && sh_pct < -flat {
                Some(("DescendingTriangle", "Bearish", "Triángulo descendente", "Descending Triangle"))
            } else if sh_pct < -flat && sl_pct > flat {
                Some(("SymmetricTriangle", "Neutral", "Triángulo simétrico", "Symmetric Triangle"))
            } else if sh_pct > flat && sl_pct > flat && sl > sh {
                Some(("RisingWedge", "Bearish", "Cuña ascendente", "Rising Wedge"))
            } else if sh_pct < -flat && sl_pct < -flat && sl > sh {
                Some(("FallingWedge", "Bullish", "Cuña descendente", "Falling Wedge"))
            } else if (sh_pct - sl_pct).abs() < flat && sh_pct.abs() > flat {
                let (d, es, en) = if sh > 0.0 { ("Bullish", "Canal alcista", "Bullish Channel") } else { ("Bearish", "Canal bajista", "Bearish Channel") };
                Some(("Channel", d, es, en))
            } else { None };

        if let Some((kind, dir, es, en)) = tri {
            let _ = conv;
            // Breakout direction's measured move = current ± triangle height.
            let target = if dir == "Bearish" { last - height } else { last + height };
            let hf = highs[highs.len().saturating_sub(4)];
            let lf = lows[lows.len().saturating_sub(4)];
            let hl = highs[highs.len() - 1];
            let ll = lows[lows.len() - 1];
            let lines = vec![
                seg((ep(hf.i), hf.price), (ep(hl.i), hl.price)),
                seg((ep(lf.i), lf.price), (ep(ll.i), ll.price)),
            ];
            out.push(mk(kind, dir, 62, last, target, es, en, true, tf, lines));
        }
    }

    // Keep the most confident few.
    out.sort_by(|a, b| b.confidence.cmp(&a.confidence));
    out.truncate(3);
    out
}
