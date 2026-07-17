// Crypto Scalping — Technical Analysis + Multi-Timeframe Score + Signal engine.
//
// Phase 1: trend/momentum/volume/structure/risk scored across 6 timeframes and
// turned into a LONG/SHORT signal with ATR-based stop, targets and R:R. Analysis
// only — never places orders. Reuses HistoricalCandle, compute_atr, compute_rsi_series.

use serde::Serialize;

use crate::crypto_md;
use crate::engine::{compute_atr, compute_rsi_series, HistoricalCandle};

// ── Indicator helpers (f64, cents) ──────────────────────────────────────────────

fn closes_f64(c: &[HistoricalCandle]) -> Vec<f64> {
    c.iter().map(|x| x.close_cents as f64).collect()
}

fn ema_series(v: &[f64], period: usize) -> Vec<f64> {
    if v.is_empty() {
        return vec![];
    }
    let k = 2.0 / (period as f64 + 1.0);
    let mut out = Vec::with_capacity(v.len());
    let mut e = v[0];
    out.push(e);
    for &x in &v[1..] {
        e = x * k + e * (1.0 - k);
        out.push(e);
    }
    out
}

fn ema_last(v: &[f64], period: usize) -> Option<f64> {
    if v.len() < period {
        return None;
    }
    ema_series(v, period).last().copied()
}

/// (macd, signal, histogram) on the last bar.
fn macd_hist(closes: &[f64]) -> Option<f64> {
    if closes.len() < 35 {
        return None;
    }
    let fast = ema_series(closes, 12);
    let slow = ema_series(closes, 26);
    let macd: Vec<f64> = fast.iter().zip(slow.iter()).map(|(f, s)| f - s).collect();
    let signal = ema_series(&macd, 9);
    Some(macd.last()? - signal.last()?)
}

/// Stochastic RSI on the last bar, 0..100.
fn stoch_rsi(candles: &[HistoricalCandle]) -> Option<f64> {
    let closes: Vec<i64> = candles.iter().map(|c| c.close_cents).collect();
    let rsi: Vec<f64> = compute_rsi_series(&closes, 14)
        .into_iter()
        .flatten()
        .collect();
    if rsi.len() < 14 {
        return None;
    }
    let win = &rsi[rsi.len() - 14..];
    let (mut lo, mut hi) = (f64::MAX, f64::MIN);
    for &r in win {
        lo = lo.min(r);
        hi = hi.max(r);
    }
    if hi - lo <= 0.0 {
        return Some(50.0);
    }
    Some(((rsi.last()? - lo) / (hi - lo)) * 100.0)
}

fn rsi_last(candles: &[HistoricalCandle]) -> Option<f64> {
    let closes: Vec<i64> = candles.iter().map(|c| c.close_cents).collect();
    compute_rsi_series(&closes, 14).into_iter().flatten().last()
}

/// Rolling VWAP (cents) over the loaded window.
fn vwap(candles: &[HistoricalCandle]) -> Option<i64> {
    let (mut pv, mut vol) = (0.0f64, 0.0f64);
    for c in candles {
        let typical = (c.high_cents + c.low_cents + c.close_cents) as f64 / 3.0;
        let v = c.volume.max(1) as f64;
        pv += typical * v;
        vol += v;
    }
    if vol <= 0.0 {
        return None;
    }
    Some((pv / vol).round() as i64)
}

/// Supertrend direction: +1 (up), -1 (down), 0 (n/a). period=10, mult=3.
fn supertrend_dir(candles: &[HistoricalCandle]) -> i8 {
    let n = candles.len();
    if n < 12 {
        return 0;
    }
    let period = 10usize;
    let mult = 3.0f64;
    let mut tr = vec![0.0f64; n];
    for i in 1..n {
        let h = candles[i].high_cents as f64;
        let l = candles[i].low_cents as f64;
        let pc = candles[i - 1].close_cents as f64;
        tr[i] = (h - l).max((h - pc).abs()).max((l - pc).abs());
    }
    let mut atr = vec![0.0f64; n];
    atr[period] = tr[1..=period].iter().sum::<f64>() / period as f64;
    for i in (period + 1)..n {
        atr[i] = (atr[i - 1] * (period as f64 - 1.0) + tr[i]) / period as f64;
    }
    let (mut fub, mut flb) = (0.0f64, 0.0f64);
    let mut dir: i8 = 1;
    for i in period..n {
        let mid = (candles[i].high_cents as f64 + candles[i].low_cents as f64) / 2.0;
        let bub = mid + mult * atr[i];
        let blb = mid - mult * atr[i];
        let close = candles[i].close_cents as f64;
        let pclose = candles[i - 1].close_cents as f64;
        fub = if bub < fub || pclose > fub { bub } else { fub };
        flb = if blb > flb || pclose < flb { blb } else { flb };
        if close > fub {
            dir = 1;
        } else if close < flb {
            dir = -1;
        }
    }
    dir
}

#[derive(Serialize)]
pub struct TimeframeAnalysis {
    pub tf: String,
    pub close_cents: i64,
    pub trend: &'static str, // "Bull" | "Bear" | "Neutral"
    pub ema9: Option<i64>,
    pub ema21: Option<i64>,
    pub ema50: Option<i64>,
    pub ema200: Option<i64>,
    pub supertrend_dir: i8,
    pub rsi: Option<f64>,
    pub stoch_rsi: Option<f64>,
    pub macd_hist_cents: Option<i64>,
    pub vwap_cents: Option<i64>,
    pub atr_cents: Option<i64>,
    pub structure: &'static str, // "HH-HL" | "LH-LL" | "Mixed"
}

fn analyze_tf(tf: &str, c: &[HistoricalCandle]) -> TimeframeAnalysis {
    let closes = closes_f64(c);
    let ema9 = ema_last(&closes, 9);
    let ema21 = ema_last(&closes, 21);
    let ema50 = ema_last(&closes, 50);
    let ema200 = ema_last(&closes, 200);
    let st = supertrend_dir(c);

    // Trend from EMA stack (price-relative), reinforced by Supertrend.
    let trend = match (ema9, ema21, ema50) {
        (Some(e9), Some(e21), Some(e50)) => {
            if e9 > e21 && e21 > e50 && st >= 0 {
                "Bull"
            } else if e9 < e21 && e21 < e50 && st <= 0 {
                "Bear"
            } else {
                "Neutral"
            }
        }
        _ => {
            if st > 0 {
                "Bull"
            } else if st < 0 {
                "Bear"
            } else {
                "Neutral"
            }
        }
    };

    TimeframeAnalysis {
        tf: tf.to_string(),
        close_cents: c.last().map(|x| x.close_cents).unwrap_or(0),
        trend,
        ema9: ema9.map(|v| v.round() as i64),
        ema21: ema21.map(|v| v.round() as i64),
        ema50: ema50.map(|v| v.round() as i64),
        ema200: ema200.map(|v| v.round() as i64),
        supertrend_dir: st,
        rsi: rsi_last(c),
        stoch_rsi: stoch_rsi(c),
        macd_hist_cents: macd_hist(&closes).map(|v| v.round() as i64),
        vwap_cents: vwap(c),
        atr_cents: compute_atr(c, 14),
        structure: market_structure(c),
    }
}

/// Basic HH/HL vs LH/LL read from the last swing pivots.
fn market_structure(c: &[HistoricalCandle]) -> &'static str {
    let n = c.len();
    if n < 20 {
        return "Mixed";
    }
    // Compare the two most recent ~10-bar swing highs and lows.
    let half = &c[n - 20..n - 10];
    let recent = &c[n - 10..];
    let max = |s: &[HistoricalCandle]| s.iter().map(|x| x.high_cents).max().unwrap_or(0);
    let min = |s: &[HistoricalCandle]| s.iter().map(|x| x.low_cents).min().unwrap_or(0);
    let (h1, h2) = (max(half), max(recent));
    let (l1, l2) = (min(half), min(recent));
    if h2 > h1 && l2 > l1 {
        "HH-HL"
    } else if h2 < h1 && l2 < l1 {
        "LH-LL"
    } else {
        "Mixed"
    }
}

// ── Scoring ─────────────────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct ScalpScore {
    pub trend: i32,
    pub momentum: i32,
    pub volume: i32,
    pub structure: i32,
    pub risk: i32,
    pub total: i32,
    pub label: &'static str, // "No-Trade" | "Weak" | "Strong" | "High-Conviction"
    pub bias: &'static str,  // "Long" | "Short" | "Neutral"
}

#[derive(Serialize)]
pub struct ScalpSignal {
    pub side: &'static str, // "LONG" | "SHORT" | "NONE"
    pub entry_low_cents: i64,
    pub entry_high_cents: i64,
    pub stop_cents: i64,
    pub tp1_cents: i64,
    pub tp2_cents: i64,
    pub risk_reward: f64,
    pub confidence: i32,
    // Fee-aware economics (per-side fee × 2 for the round trip).
    pub fee_pct: f64,
    pub round_trip_fee_pct: f64,
    pub tp1_gross_pct: f64,
    pub tp1_net_pct: f64,
    pub tp2_net_pct: f64,
    pub fee_viable: bool, // does TP1 clear the round-trip fee?
    pub reasons: Vec<String>,
}

#[derive(Serialize)]
pub struct ScalpAnalysis {
    pub product: String,
    pub timeframes: Vec<TimeframeAnalysis>,
    pub score: ScalpScore,
    pub signal: ScalpSignal,
    pub smc: crate::smc::SmcAnalysis,
    pub patterns: Vec<crate::chart_patterns::ChartPattern>,
    pub fib: Option<crate::fibonacci::FibAnalysis>,
    pub generated_at: i64,
}

fn dir_of(trend: &str) -> i32 {
    match trend {
        "Bull" => 1,
        "Bear" => -1,
        _ => 0,
    }
}

/// Build the weighted score + signal from the per-TF reads.
/// Hierarchy: 4H/1H bias, 15m setup, 5m entry, 1m timing.
fn score_and_signal(
    product: &str,
    tfs: &[TimeframeAnalysis],
    rr: f64,
    fee_pct: f64,
    smc: &crate::smc::SmcAnalysis,
) -> (ScalpScore, ScalpSignal) {
    let get = |name: &str| tfs.iter().find(|t| t.tf == name);
    let h4 = get("4h");
    let h1 = get("1h");
    let m15 = get("15m");
    let m5 = get("5m");
    let m1 = get("1m");
    let mut reasons: Vec<String> = Vec::new();

    // ── Directional consensus (higher TFs weigh more) ──
    let weighted: i32 = h4.map(|t| dir_of(t.trend) * 3).unwrap_or(0)
        + h1.map(|t| dir_of(t.trend) * 3).unwrap_or(0)
        + m15.map(|t| dir_of(t.trend) * 2).unwrap_or(0)
        + m5.map(|t| dir_of(t.trend) * 1).unwrap_or(0)
        + m1.map(|t| dir_of(t.trend) * 1).unwrap_or(0);
    let max_w = 10;
    let bias: &'static str = if weighted >= 3 {
        "Long"
    } else if weighted <= -3 {
        "Short"
    } else {
        "Neutral"
    };
    let dir: i32 = if bias == "Long" {
        1
    } else if bias == "Short" {
        -1
    } else {
        0
    };

    // ── Trend (0..100): magnitude of alignment ──
    let trend_score = ((weighted.abs() as f64 / max_w as f64) * 100.0).round() as i32;
    if let (Some(a), Some(b)) = (h4, h1) {
        if dir_of(a.trend) == dir && dir_of(b.trend) == dir && dir != 0 {
            reasons.push(format!("Bias {} alineado en 4H y 1H", bias));
        }
    }

    // ── Momentum (0..100) on the entry TF (5m) ──
    let momentum = m5
        .map(|t| {
            let mut s = 50i32;
            if let Some(h) = t.macd_hist_cents {
                if (h > 0) == (dir > 0) && dir != 0 {
                    s += 20;
                    reasons.push("MACD a favor en 5m".into());
                } else if dir != 0 {
                    s -= 15;
                }
            }
            if let Some(r) = t.rsi {
                if dir > 0 && r > 50.0 {
                    s += 15;
                } else if dir < 0 && r < 50.0 {
                    s += 15;
                }
                if r > 80.0 || r < 20.0 {
                    s -= 10;
                } // exhaustion
            }
            if let Some(sr) = t.stoch_rsi {
                if dir > 0 && sr < 80.0 && sr > 20.0 {
                    s += 10;
                }
                if dir < 0 && sr > 20.0 && sr < 80.0 {
                    s += 10;
                }
            }
            s.clamp(0, 100)
        })
        .unwrap_or(50);

    // ── Volume (0..100): price vs VWAP on entry TF ──
    let volume = m5
        .map(|t| {
            let mut s = 50i32;
            if let Some(vw) = t.vwap_cents {
                if dir > 0 && t.close_cents > vw {
                    s += 25;
                    reasons.push("Precio sobre VWAP (5m)".into());
                } else if dir < 0 && t.close_cents < vw {
                    s += 25;
                    reasons.push("Precio bajo VWAP (5m)".into());
                } else if dir != 0 {
                    s -= 15;
                }
            }
            s.clamp(0, 100)
        })
        .unwrap_or(50);

    // ── Structure (0..100) on 15m + 1h ──
    let structure = {
        let want = if dir > 0 {
            "HH-HL"
        } else if dir < 0 {
            "LH-LL"
        } else {
            ""
        };
        let mut s = 50i32;
        for t in [m15, h1].into_iter().flatten() {
            if !want.is_empty() && t.structure == want {
                s += 20;
            } else if t.structure == "Mixed" {
                s -= 5;
            } else if !want.is_empty() {
                s -= 15;
            }
        }
        if m15.map(|t| t.structure) == Some(want) && !want.is_empty() {
            reasons.push(format!("Estructura {} en 15m", want));
        }
        // ── Smart Money Concepts reinforcement ──
        for e in &smc.events {
            let with_bias = (e.direction == "Bullish") == (dir > 0) && dir != 0;
            match e.kind {
                "BOS" if with_bias => {
                    s += 12;
                    reasons.push(format!("BOS {} confirma la estructura", e.direction));
                }
                "BOS" if dir != 0 => {
                    s -= 8;
                }
                "CHoCH" if !with_bias && dir != 0 => {
                    s -= 12;
                    reasons.push(format!("⚠️ CHoCH {} — posible reversión", e.direction));
                }
                "LiquiditySweep" if with_bias => {
                    s += 6;
                    reasons.push("Liquidity sweep a favor (stop hunt)".into());
                }
                _ => {}
            }
        }
        s.clamp(0, 100)
    };

    // ── Risk (0..100): ATR sanity on entry TF ──
    let entry = m5
        .and_then(|t| {
            if t.close_cents > 0 {
                Some(t.close_cents)
            } else {
                None
            }
        })
        .unwrap_or(0);
    let atr = m5.and_then(|t| t.atr_cents).unwrap_or(0);
    let atr_pct = if entry > 0 {
        atr as f64 / entry as f64 * 100.0
    } else {
        0.0
    };
    let risk = {
        // Sweet spot ~0.15%..0.8% ATR per 5m candle for scalping.
        if atr_pct == 0.0 {
            40
        } else if atr_pct < 0.05 {
            30
        }
        // too quiet
        else if atr_pct <= 0.8 {
            90
        }
        // healthy
        else if atr_pct <= 1.5 {
            60
        }
        // wide
        else {
            35
        } // wild
    };

    let total = ((trend_score as f64) * 0.30
        + (momentum as f64) * 0.20
        + (volume as f64) * 0.20
        + (structure as f64) * 0.20
        + (risk as f64) * 0.10)
        .round() as i32;

    let label = if total >= 90 {
        "High-Conviction"
    } else if total >= 75 {
        "Strong"
    } else if total >= 50 {
        "Weak"
    } else {
        "No-Trade"
    };

    // Round-trip fee = fee on entry + fee on exit.
    let round_trip_fee_pct = (fee_pct * 2.0 * 100.0).round() / 100.0;

    // ── Signal ── (stop = 1.5×ATR defines risk; TPs scale by the chosen R:R)
    let signal = if dir != 0 && total >= 50 && entry > 0 && atr > 0 {
        let side = if dir > 0 { "LONG" } else { "SHORT" };
        let stop_dist = (1.5 * atr as f64).round() as i64;
        let reward1 = (stop_dist as f64 * rr).round() as i64;
        let reward2 = (stop_dist as f64 * rr * 2.0).round() as i64;
        let (stop, tp1, tp2, entry_lo, entry_hi) = if dir > 0 {
            (
                entry - stop_dist,
                entry + reward1,
                entry + reward2,
                entry - (atr as f64 * 0.25) as i64,
                entry + (atr as f64 * 0.1) as i64,
            )
        } else {
            (
                entry + stop_dist,
                entry - reward1,
                entry - reward2,
                entry - (atr as f64 * 0.1) as i64,
                entry + (atr as f64 * 0.25) as i64,
            )
        };
        let entry_mid = (entry_lo + entry_hi) as f64 / 2.0;
        let gross = |tp: i64| {
            if entry_mid > 0.0 {
                (if dir > 0 {
                    tp as f64 - entry_mid
                } else {
                    entry_mid - tp as f64
                }) / entry_mid
                    * 100.0
            } else {
                0.0
            }
        };
        let tp1_gross = gross(tp1);
        let tp1_net = tp1_gross - round_trip_fee_pct;
        let tp2_net = gross(tp2) - round_trip_fee_pct;
        let fee_viable = tp1_net > 0.0;

        reasons.push(format!(
            "Stop 1.5×ATR ({:.2}%), objetivo {:.1}:1",
            atr_pct * 1.5,
            rr
        ));
        if fee_viable {
            reasons.push(format!(
                "Neto tras fees ({:.2}% round-trip): TP1 +{:.2}%",
                round_trip_fee_pct, tp1_net
            ));
        } else {
            reasons.push(format!(
                "⚠️ TP1 ({:.2}%) NO supera fees round-trip ({:.2}%) — no rentable",
                tp1_gross, round_trip_fee_pct
            ));
        }
        let r2 = |v: f64| (v * 100.0).round() / 100.0;
        ScalpSignal {
            side,
            entry_low_cents: entry_lo,
            entry_high_cents: entry_hi,
            stop_cents: stop,
            tp1_cents: tp1,
            tp2_cents: tp2,
            risk_reward: (rr * 100.0).round() / 100.0,
            confidence: total,
            fee_pct: r2(fee_pct),
            round_trip_fee_pct,
            tp1_gross_pct: r2(tp1_gross),
            tp1_net_pct: r2(tp1_net),
            tp2_net_pct: r2(tp2_net),
            fee_viable,
            reasons,
        }
    } else {
        ScalpSignal {
            side: "NONE",
            entry_low_cents: 0,
            entry_high_cents: 0,
            stop_cents: 0,
            tp1_cents: 0,
            tp2_cents: 0,
            risk_reward: 0.0,
            confidence: total,
            fee_pct: (fee_pct * 100.0).round() / 100.0,
            round_trip_fee_pct,
            tp1_gross_pct: 0.0,
            tp1_net_pct: 0.0,
            tp2_net_pct: 0.0,
            fee_viable: false,
            reasons: if total < 50 {
                vec![format!("Score {} < 50: sin trade", total)]
            } else {
                vec!["Sin bias direccional claro".into()]
            },
        }
    };
    let _ = product;

    (
        ScalpScore {
            trend: trend_score,
            momentum,
            volume,
            structure,
            risk,
            total,
            label,
            bias,
        },
        signal,
    )
}

/// Full scalping analysis for `product` across all 6 timeframes.
/// `rr` is the reward:risk target that scales take-profit distances.
/// `fee_pct` is the per-side trading fee in percent (e.g. 0.6 for Coinbase taker).
pub fn analyze(product: &str, rr: f64, fee_pct: f64) -> Result<ScalpAnalysis, String> {
    let mut tfs = Vec::new();
    // Keep the setup-timeframe candles (15m, else 5m) to run SMC on.
    let mut smc_candles: Option<(String, Vec<HistoricalCandle>)> = None;
    for tf in crypto_md::SCALP_TIMEFRAMES {
        match crypto_md::fetch_candles(product, tf) {
            Ok(c) if c.len() >= 15 => {
                if *tf == "15m" {
                    smc_candles = Some(("15m".into(), c.clone()));
                } else if *tf == "5m" && smc_candles.is_none() {
                    smc_candles = Some(("5m".into(), c.clone()));
                }
                tfs.push(analyze_tf(tf, &c));
            }
            Ok(_) => {}
            Err(e) => return Err(format!("{tf}: {e}")),
        }
        std::thread::sleep(std::time::Duration::from_millis(120)); // be gentle on Coinbase
    }
    if tfs.is_empty() {
        return Err("Sin datos suficientes".into());
    }
    let (smc, patterns, fib) = match &smc_candles {
        Some((tf, c)) => (
            crate::smc::analyze(tf, c),
            crate::chart_patterns::detect(c, 2, tf),
            crate::fibonacci::analyze(c, tf),
        ),
        None => (crate::smc::analyze("15m", &[]), Vec::new(), None),
    };
    let (score, signal) = score_and_signal(product, &tfs, rr, fee_pct, &smc);
    let generated_at = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    Ok(ScalpAnalysis {
        product: product.to_uppercase(),
        timeframes: tfs,
        score,
        signal,
        smc,
        patterns,
        fib,
        generated_at,
    })
}
