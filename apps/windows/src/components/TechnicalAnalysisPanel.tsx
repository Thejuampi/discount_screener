import { useState } from "react";
import { fmt } from "../api";
import type {
  ChartSummary, TechnicalBreakdown, TrendState, TfAlignment, Divergence,
} from "../api";
import { useT } from "../i18n";

// ── Glossary ──────────────────────────────────────────────────────────────────

/** Cada patrón con una explicación de 1-2 líneas en español. */
const PATTERN_INFO: Record<string, string> = {
  "Doji":               "Apertura ≈ cierre. Indecisión del mercado. Puede anticipar un cambio de tendencia, pero por sí solo no es una señal — hay que ver el contexto.",
  "Hammer":             "Vela con cuerpo chico arriba y mecha larga abajo. Sugiere que los compradores rechazaron precios bajos. Bullish si aparece después de una tendencia bajista.",
  "Inverted Hammer":    "Cuerpo chico abajo y mecha larga arriba, después de bajadas. Los compradores intentaron subir el precio. Bullish, necesita confirmación al día siguiente.",
  "Shooting Star":      "Cuerpo chico abajo y mecha larga arriba, después de subidas. Los vendedores rechazaron precios altos. Bearish, señal de posible techo.",
  "Bullish Engulfing":  "Vela verde grande que envuelve completamente a la roja anterior. Cambio de control de vendedores a compradores. Señal alcista fuerte.",
  "Bearish Engulfing":  "Vela roja grande que envuelve completamente a la verde anterior. Cambio de control de compradores a vendedores. Señal bajista fuerte.",
  "Morning Star":       "Tres velas: roja fuerte, vela pequeña, verde fuerte. Marca el final de una caída. Señal alcista de reversión.",
  "Evening Star":       "Tres velas: verde fuerte, vela pequeña, roja fuerte. Marca el final de una subida. Señal bajista de reversión.",
};

const SUBSCORE_INFO: Record<string, string> = {
  "Trend":      "Mide si el precio viene subiendo o bajando en los 3 timeframes (weekly/daily/hourly) y qué tan fuerte es esa tendencia (ADX).",
  "Momentum":   "Mide la velocidad del movimiento. Combina RSI (sobrecompra/sobreventa) + MACD (cruces de medias móviles).",
  "Volatility": "Mide qué tan lejos del promedio está el precio usando Bandas de Bollinger. Detecta 'squeeze' (volatilidad baja → posible breakout).",
  "Volume":     "Mide si las subidas/bajadas vienen con volumen real (señal genuina) o sin volumen (sospechoso).",
  "Patterns":   "Mide si hay patrones de velas reconocibles en las últimas barras (Hammer, Engulfing, etc.).",
};

const DIVERGENCE_INFO: Record<string, string> = {
  "RegularBullish":
    "El precio hizo un mínimo más bajo pero el RSI hizo un mínimo más alto. Significa que la fuerza de los vendedores se está agotando — señal típica de reversión alcista. Aparece al final de tendencias bajistas.",
  "RegularBearish":
    "El precio hizo un máximo más alto pero el RSI hizo un máximo más bajo. Significa que los compradores pierden fuerza pese a la subida — señal típica de reversión bajista. Aparece al final de tendencias alcistas.",
  "HiddenBullish":
    "El precio hizo un mínimo más alto (pullback) pero el RSI hizo un mínimo más bajo. Señal de CONTINUACIÓN de una tendencia alcista existente — el pullback no rompió la estructura.",
  "HiddenBearish":
    "El precio hizo un máximo más bajo (rebote) pero el RSI hizo un máximo más alto. Señal de CONTINUACIÓN de una tendencia bajista existente — el rally es solo correctivo.",
};

const TF_INFO = "Análisis simultáneo en 3 ventanas de tiempo: Weekly (visión macro de meses), Daily (tendencia primaria), Hourly (timing de entrada).";

const ALIGNMENT_INFO: Record<TfAlignment, string> = {
  BullStack: "Los 3 timeframes apuntan hacia arriba. Es la situación más alcista posible.",
  BearStack: "Los 3 timeframes apuntan hacia abajo. Tendencia bajista confirmada en todas las escalas.",
  Mixed:     "Los timeframes están en conflicto (ej: weekly sube pero daily baja). Mejor esperar a que se alineen.",
  Unknown:   "Faltan datos para clasificar la tendencia.",
};

// ── Weighted summary builder ──────────────────────────────────────────────────

type SignalBias = "bull" | "bear" | "neutral";

interface KeySignal {
  label: string;        // short tag, e.g. "Alineación multi-TF"
  detail: string;       // what we observed, e.g. "Weekly + Daily + Hourly alcistas"
  weight: number;       // 0-100, importance assigned by us
  bias: SignalBias;
  strength: number;     // 0-1, how strongly the signal points in its direction
}

interface TechnicalSummary {
  verdict: "Strong Bullish" | "Mildly Bullish" | "Neutral" | "Mildly Bearish" | "Strong Bearish" | "Insufficient data";
  score: number;        // -100..+100, weighted aggregate
  confidence: "Alta" | "Media" | "Baja";  // based on total weight of evidence
  narrative: string;    // 1-2 sentence summary in Spanish
  action_hint: string;  // what this means for the trader
  positives: KeySignal[];  // bullish signals, ranked
  negatives: KeySignal[];  // bearish signals, ranked
  contradictions: string[];
}

type TFn = (key: string, vars?: Record<string, string | number>) => string;

function buildTechnicalSummary(
  _weekly: ChartSummary | null,
  daily: ChartSummary | null,
  _hourly: ChartSummary | null,
  breakdown: TechnicalBreakdown | null,
  t: TFn,
): TechnicalSummary {
  const signals: KeySignal[] = [];

  // 1. Multi-TF alignment
  if (breakdown && breakdown.alignment !== "Unknown") {
    if (breakdown.alignment === "BullStack") {
      signals.push({
        label: t("ts.sig.alignment"), detail: t("ts.sig.alignment.bull"),
        weight: 30, bias: "bull", strength: 1.0,
      });
    } else if (breakdown.alignment === "BearStack") {
      signals.push({
        label: t("ts.sig.alignment"), detail: t("ts.sig.alignment.bear"),
        weight: 30, bias: "bear", strength: 1.0,
      });
    } else {
      const trends = [
        ["Weekly", breakdown.weekly_trend],
        ["Daily", breakdown.daily_trend],
        ["Hourly", breakdown.hourly_trend],
      ].filter(([, tr]) => tr !== "Unknown") as [string, TrendState][];
      const bulls = trends.filter(([, tr]) => tr === "Bullish").map(([f]) => f);
      const bears = trends.filter(([, tr]) => tr === "Bearish").map(([f]) => f);
      if (bulls.length > 0 || bears.length > 0) {
        const detail = `${bulls.length ? `${bulls.join("+")} ${t("ts.sig.mixed.bull")}` : ""}${bulls.length && bears.length ? " vs " : ""}${bears.length ? `${bears.join("+")} ${t("ts.sig.mixed.bear")}` : ""}`;
        signals.push({ label: t("ts.sig.mixed"), detail, weight: 15, bias: "neutral", strength: 1.0 });
      }
    }
  }

  // 2. Trend score
  if (breakdown?.trend_score != null) {
    const s = breakdown.trend_score;
    if (Math.abs(s) > 20) {
      signals.push({
        label: t("ts.sig.trend"),
        detail: t(s > 0 ? "ts.sig.trend.bull" : "ts.sig.trend.bear"),
        weight: 20, bias: s > 0 ? "bull" : "bear",
        strength: Math.min(Math.abs(s) / 100, 1),
      });
    }
  }

  // 3. RSI extremes
  if (daily?.rsi != null) {
    const r = daily.rsi;
    if (r >= 70) {
      signals.push({
        label: t("ts.sig.rsiOverbought"),
        detail: t("ts.sig.rsiOverbought.det", { r: r.toFixed(0) }),
        weight: 12, bias: "bear", strength: Math.min((r - 70) / 20, 1),
      });
    } else if (r <= 30) {
      signals.push({
        label: t("ts.sig.rsiOversold"),
        detail: t("ts.sig.rsiOversold.det", { r: r.toFixed(0) }),
        weight: 12, bias: "bull", strength: Math.min((30 - r) / 20, 1),
      });
    }
  }

  // 4. ADX strength
  if (daily?.adx != null && daily.adx < 18) {
    signals.push({
      label: t("ts.sig.noTrend"),
      detail: t("ts.sig.noTrend.det", { a: daily.adx.toFixed(0) }),
      weight: 10, bias: "neutral", strength: 1.0,
    });
  } else if (daily?.adx != null && daily.adx > 30) {
    const dir = (daily.plus_di ?? 0) > (daily.minus_di ?? 0) ? "bull" : "bear";
    signals.push({
      label: t("ts.sig.strongTrend"),
      detail: t(dir === "bull" ? "ts.sig.strongTrend.bull" : "ts.sig.strongTrend.bear", { a: daily.adx.toFixed(0) }),
      weight: 15, bias: dir, strength: Math.min((daily.adx - 30) / 30, 1),
    });
  }

  // 5. MACD direction
  if (daily?.macd_cents != null && daily?.signal_cents != null) {
    const cross = daily.macd_cents > daily.signal_cents;
    const histPos = (daily.histogram_cents ?? 0) > 0;
    const detailKey = cross
      ? (histPos ? "ts.sig.macd.bullAccel" : "ts.sig.macd.bullPos")
      : (histPos ? "ts.sig.macd.bearNeg" : "ts.sig.macd.bearDecel");
    signals.push({
      label: t("ts.sig.macd"), detail: t(detailKey),
      weight: 10, bias: cross ? "bull" : "bear", strength: 0.7,
    });
  }

  // 6. Bollinger Band extremes
  if (daily?.bb_percent_b != null) {
    const pb = daily.bb_percent_b;
    if (pb >= 0.95) {
      signals.push({
        label: t("ts.sig.bbUpper"), detail: t("ts.sig.bbUpper.det"),
        weight: 8, bias: "bear", strength: 0.6,
      });
    } else if (pb <= 0.05) {
      signals.push({
        label: t("ts.sig.bbLower"), detail: t("ts.sig.bbLower.det"),
        weight: 8, bias: "bull", strength: 0.6,
      });
    }
  }

  // 7. Squeeze
  if (daily?.bb_bandwidth != null && daily.bb_bandwidth < 0.04) {
    signals.push({
      label: t("ts.sig.squeeze"), detail: t("ts.sig.squeeze.det"),
      weight: 10, bias: "neutral", strength: 1.0,
    });
  }

  // 8. Volume confirmation
  if (daily?.volume_ratio != null) {
    const vr = daily.volume_ratio;
    if (vr >= 1.8) {
      const obvBull = (daily.obv_slope ?? 0) > 0;
      signals.push({
        label: t("ts.sig.volumeAnomaly"),
        detail: t(obvBull ? "ts.sig.volumeAnomaly.bull" : "ts.sig.volumeAnomaly.bear", { x: vr.toFixed(1) }),
        weight: 12, bias: obvBull ? "bull" : "bear", strength: Math.min((vr - 1.5) / 1.5, 1),
      });
    }
  }

  // 9. OBV trend
  if (daily?.obv_slope != null && Math.abs(daily.obv_slope) > 0.4) {
    signals.push({
      label: t("ts.sig.obv"),
      detail: t(daily.obv_slope > 0 ? "ts.sig.obv.bull" : "ts.sig.obv.bear"),
      weight: 10, bias: daily.obv_slope > 0 ? "bull" : "bear", strength: Math.abs(daily.obv_slope),
    });
  }

  // 10. Divergences price/RSI
  if (breakdown && breakdown.divergences.length > 0) {
    for (const div of breakdown.divergences) {
      const baseWeight = div.kind.startsWith("Regular") ? 18 : 12;
      const recency = div.bars_ago <= 2 ? 1.0 : div.bars_ago <= 5 ? 0.7 : 0.4;
      signals.push({
        label: div.label,
        detail: DIVERGENCE_INFO[div.kind] ?? t("ts.sig.divergenceDetected"),
        weight: baseWeight,
        bias: div.bias === "Bullish" ? "bull" : "bear",
        strength: Math.max(div.strength * recency, 0.3),
      });
    }
  }

  // 11. Recent patterns
  if (breakdown && breakdown.patterns.length > 0) {
    const recent = breakdown.patterns.filter(p => p.bars_ago <= 1);
    for (const p of recent.slice(0, 2)) {
      if (p.bias === "Neutral") continue;
      signals.push({
        label: t("ts.sig.pattern", { name: p.name }),
        detail: PATTERN_INFO[p.name] ?? `${p.name}`,
        weight: 12, bias: p.bias === "Bullish" ? "bull" : "bear", strength: p.bars_ago === 0 ? 1.0 : 0.6,
      });
    }
  }

  // 12. 52w position
  if (daily?.pos_52w_pct != null) {
    const p = daily.pos_52w_pct;
    if (p >= 95) {
      signals.push({
        label: t("ts.sig.near52wHigh"),
        detail: t("ts.sig.near52wHigh.det", { p: p.toFixed(0) }),
        weight: 8, bias: "neutral", strength: 0.7,
      });
    } else if (p <= 10) {
      signals.push({
        label: t("ts.sig.near52wLow"),
        detail: t("ts.sig.near52wLow.det", { p: p.toFixed(0) }),
        weight: 8, bias: "neutral", strength: 0.7,
      });
    }
  }

  // ── Aggregate ──────────────────────────────────────────────────────────────
  let bullWeight = 0, bearWeight = 0, totalWeight = 0;
  for (const s of signals) {
    const eff = s.weight * s.strength;
    totalWeight += s.weight;
    if (s.bias === "bull") bullWeight += eff;
    else if (s.bias === "bear") bearWeight += eff;
  }
  const score = totalWeight === 0 ? 0
    : Math.round(((bullWeight - bearWeight) / totalWeight) * 100);

  let verdict: TechnicalSummary["verdict"] = "Insufficient data";
  if (totalWeight >= 25) {
    if (score >= 45) verdict = "Strong Bullish";
    else if (score >= 15) verdict = "Mildly Bullish";
    else if (score <= -45) verdict = "Strong Bearish";
    else if (score <= -15) verdict = "Mildly Bearish";
    else verdict = "Neutral";
  }

  const confidence: TechnicalSummary["confidence"] =
    totalWeight >= 70 ? "Alta" : totalWeight >= 40 ? "Media" : "Baja";

  const sortedSignals = [...signals].sort(
    (a, b) => (b.weight * b.strength) - (a.weight * a.strength)
  );
  const positives = sortedSignals.filter(s => s.bias === "bull").slice(0, 4);
  const negatives = sortedSignals.filter(s => s.bias === "bear").slice(0, 4);

  const contradictions: string[] = [];
  if (positives.length > 0 && negatives.length > 0) {
    const topBull = positives[0];
    const topBear = negatives[0];
    if (topBull.weight * topBull.strength > 10 && topBear.weight * topBear.strength > 10) {
      contradictions.push(t("ts.contradiction", { a: topBull.label, b: topBear.label }));
    }
  }
  if (breakdown?.alignment === "Mixed") {
    contradictions.push(t("ts.contradiction.mixedTf"));
  }

  // ── Narrative + action hint (i18n) ─────────────────────────────────────────
  const narrativeKeyMap: Record<TechnicalSummary["verdict"], { n: string; a: string }> = {
    "Strong Bullish":     { n: "ts.narrative.strongBull",   a: "ts.action.strongBull" },
    "Mildly Bullish":     { n: "ts.narrative.mildBull",     a: "ts.action.mildBull" },
    "Neutral":            { n: "ts.narrative.neutral",      a: "ts.action.neutral" },
    "Mildly Bearish":     { n: "ts.narrative.mildBear",     a: "ts.action.mildBear" },
    "Strong Bearish":     { n: "ts.narrative.strongBear",   a: "ts.action.strongBear" },
    "Insufficient data":  { n: "ts.narrative.insufficient", a: "ts.action.insufficient" },
  };
  const nk = narrativeKeyMap[verdict];
  return {
    verdict, score, confidence,
    narrative: t(nk.n),
    action_hint: t(nk.a),
    positives, negatives, contradictions,
  };
}

// ── Profile system ────────────────────────────────────────────────────────────

export type Profile = "investor" | "swing" | "daytrade";

const PROFILE_INFO: Record<Profile, {
  label: string;
  shortLabel: string;
  description: string;
  frames: [string, string, string];   // labels for the 3 frames shown (top→bottom temporal)
}> = {
  investor: {
    label: "Inversor (largo plazo)",
    shortLabel: "Inversor",
    description: "Holding de meses a años. Foco en ciclo y tendencia primaria.",
    frames: ["Monthly", "Weekly", "Daily"],
  },
  swing: {
    label: "Swing trader",
    shortLabel: "Swing",
    description: "Holding de días a semanas. Foco en tendencia + timing.",
    frames: ["Weekly", "Daily", "Hourly"],
  },
  daytrade: {
    label: "Day trader",
    shortLabel: "Day-trade",
    description: "Operaciones intradía. Foco en estructura corto plazo (15m no disponible aún — usando Daily/Hourly como proxy).",
    frames: ["Daily", "Hourly", "Hourly"],   // last frame is intraday-focused
  },
};

interface Props {
  weekly:  ChartSummary | null;
  daily:   ChartSummary | null;
  hourly:  ChartSummary | null;
  monthly: ChartSummary | null;
  breakdown: TechnicalBreakdown | null;
  profile: Profile;
  onProfileChange: (p: Profile) => void;
}

/** Picks the 3 chart summaries that correspond to the active profile. */
function framesForProfile(
  profile: Profile,
  monthly: ChartSummary | null,
  weekly: ChartSummary | null,
  daily: ChartSummary | null,
  hourly: ChartSummary | null,
): { top: ChartSummary | null; mid: ChartSummary | null; bottom: ChartSummary | null } {
  switch (profile) {
    case "investor":  return { top: monthly, mid: weekly, bottom: daily };
    case "swing":     return { top: weekly,  mid: daily,  bottom: hourly };
    case "daytrade":  return { top: daily,   mid: hourly, bottom: hourly };
  }
}

/** Classify trend for a given ChartSummary using EMA stack — same logic as backend. */
function classifyTrendLocal(c: ChartSummary | null): TrendState {
  if (!c) return "Unknown";
  const close = c.latest_close_cents;
  if (close <= 0) return "Unknown";
  const e20 = c.ema20_cents ?? 0;
  const e50 = c.ema50_cents ?? 0;
  const e200 = c.ema200_cents ?? 0;
  if (e20 === 0 || e50 === 0 || e200 === 0) return "Unknown";
  if (close > e20 && e20 > e50 && e50 > e200) return "Bullish";
  if (close < e20 && e20 < e50 && e50 < e200) return "Bearish";
  return "Neutral";
}

function alignmentLocal(top: TrendState, mid: TrendState, bottom: TrendState): TfAlignment {
  if (top === "Bullish" && mid === "Bullish" && bottom === "Bullish") return "BullStack";
  if (top === "Bearish" && mid === "Bearish" && bottom === "Bearish") return "BearStack";
  if (top === "Unknown" || mid === "Unknown") return "Unknown";
  return "Mixed";
}

const TREND_COLOR: Record<TrendState, string> = {
  Bullish: "#22c55e",
  Bearish: "#ef4444",
  Neutral: "#f59e0b",
  Unknown: "#475569",
};

const ALIGN_LABEL: Record<TfAlignment, { label: string; color: string; emoji: string }> = {
  BullStack: { label: "Alineación alcista (3 frames ↑)",  color: "#22c55e", emoji: "🚀" },
  BearStack: { label: "Alineación bajista (3 frames ↓)",  color: "#ef4444", emoji: "🔻" },
  Mixed:     { label: "Frames mixtos — esperar confirmación", color: "#f59e0b", emoji: "⚠️" },
  Unknown:   { label: "Datos insuficientes",              color: "#64748b", emoji: "·" },
};

export function TechnicalAnalysisPanel({
  weekly, daily, hourly, monthly, breakdown, profile, onProfileChange,
}: Props) {
  const { t } = useT();
  const [showGlossary, setShowGlossary] = useState(false);
  if (!daily && !weekly && !hourly && !monthly) return null;

  // Pick the 3 frames for the active profile
  const { top, mid, bottom } = framesForProfile(profile, monthly, weekly, daily, hourly);
  const frameLabels = PROFILE_INFO[profile].frames;

  // Compute per-profile trends & alignment (overrides backend's swing-default breakdown)
  const topTrend    = classifyTrendLocal(top);
  const midTrend    = classifyTrendLocal(mid);
  const bottomTrend = classifyTrendLocal(bottom);
  const profileAlignment = alignmentLocal(topTrend, midTrend, bottomTrend);

  // Build a profile-aware breakdown for the summary
  const profileBreakdown: TechnicalBreakdown | null = breakdown ? {
    ...breakdown,
    alignment: profileAlignment,
    weekly_trend: topTrend,
    daily_trend:  midTrend,
    hourly_trend: bottomTrend,
  } : null;

  const summary = buildTechnicalSummary(top, mid, bottom, profileBreakdown, t);

  return (
    <div className="info-section technical-panel">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h3 style={{ margin: 0 }}>{t("tech.title")}</h3>
        <button className="glossary-btn" onClick={() => setShowGlossary(!showGlossary)} title={t("tech.guide")}>
          {showGlossary ? t("tech.guideHide") : `❓ ${t("tech.guide")}`}
        </button>
      </div>

      {/* ── Profile selector ── */}
      <ProfileSelector profile={profile} onChange={onProfileChange} />

      {/* ── Weighted summary (TOP) ── */}
      <TechnicalSummaryBox summary={summary} />

      {profileBreakdown && (
        <div className="alignment-banner" style={{ borderColor: ALIGN_LABEL[profileBreakdown.alignment].color }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <span style={{ fontSize: 18 }}>{ALIGN_LABEL[profileBreakdown.alignment].emoji}</span>
            <span style={{ color: ALIGN_LABEL[profileBreakdown.alignment].color, fontWeight: 600, fontSize: 13 }}>
              {ALIGN_LABEL[profileBreakdown.alignment].label.replace("3 frames", `${frameLabels[0]}+${frameLabels[1]}+${frameLabels[2]}`)}
            </span>
          </div>
          <p className="banner-explain">{ALIGNMENT_INFO[profileBreakdown.alignment]}</p>
        </div>
      )}

      {/* ── Sub-bucket scores ── */}
      {breakdown && (
        <div className="subscore-grid">
          <SubScore label="Trend"      score={breakdown.trend_score} />
          <SubScore label="Momentum"   score={breakdown.momentum_score} />
          <SubScore label="Volatility" score={breakdown.volatility_score} />
          <SubScore label="Volume"     score={breakdown.volume_score} />
          <SubScore label="Patterns"   score={breakdown.pattern_score} />
        </div>
      )}

      {/* ── Multi-TF table (frames from active profile) ── */}
      <table className="tf-table">
        <thead>
          <tr>
            <th title={TF_INFO}>⏱ Indicador</th>
            <th title={frameTooltip(frameLabels[0])}>{frameLabels[0]}</th>
            <th title={frameTooltip(frameLabels[1])}>{frameLabels[1]}</th>
            <th title={frameTooltip(frameLabels[2])}>{frameLabels[2]}</th>
          </tr>
        </thead>
        <tbody>
          <tr title="¿Hacia dónde va el precio en este timeframe? Bullish = subiendo, Bearish = bajando, Neutral = lateral">
            <td>Tendencia</td>
            <td><TrendCell t={topTrend} /></td>
            <td><TrendCell t={midTrend} /></td>
            <td><TrendCell t={bottomTrend} /></td>
          </tr>
          <Row label="RSI(14)" tooltip="Relative Strength Index. 0-100. <30 = sobreventa (posible rebote), >70 = sobrecompra (posible corrección). Verde si <30, rojo si >70."
               w={fmtRsi(top?.rsi)} d={fmtRsi(mid?.rsi)} h={fmtRsi(bottom?.rsi)}
               wColor={rsiColor(top?.rsi)} dColor={rsiColor(mid?.rsi)} hColor={rsiColor(bottom?.rsi)} />
          <Row label="ADX" tooltip="Average Directional Index. Mide la FUERZA de la tendencia (no la dirección). >25 = tendencia clara y fuerte. <20 = mercado lateral, sin tendencia."
               w={fmtAdx(top?.adx)} d={fmtAdx(mid?.adx)} h={fmtAdx(bottom?.adx)} />
          <Row label="MACD" tooltip="Cruce de medias móviles. ↑ significa que el momentum corto plazo supera al largo plazo (alcista). ↓ es lo contrario."
               w={macdDir(top)} d={macdDir(mid)} h={macdDir(bottom)} />
          <Row label="BB %B" tooltip="Posición dentro de las Bandas de Bollinger. 0 = pegado a la banda inferior (oversold), 0.5 = en la media, 1 = pegado a la banda superior (overbought)."
               w={fmtPct01(top?.bb_percent_b)} d={fmtPct01(mid?.bb_percent_b)} h={fmtPct01(bottom?.bb_percent_b)} />
          <Row label="Vol vs MA" tooltip="Volumen actual comparado con el promedio de 20 períodos. 1.0x = volumen normal. 2.0x = volumen doble (movimiento con convicción). <0.5x = poco interés."
               w={fmtX(top?.volume_ratio)} d={fmtX(mid?.volume_ratio)} h={fmtX(bottom?.volume_ratio)} />
        </tbody>
      </table>

      {/* ── 52w position + ATR (always from daily — the most meaningful frame for these) ── */}
      {daily && (
        <div className="kv-grid" style={{ marginTop: 8 }}>
          <span>52w high</span><span>{daily.high_52w_cents ? fmt.dollars(daily.high_52w_cents) : "—"}</span>
          <span>52w low</span><span>{daily.low_52w_cents ? fmt.dollars(daily.low_52w_cents) : "—"}</span>
          <span>{t("tech.position52w")}</span><span>{daily.pos_52w_pct != null ? `${daily.pos_52w_pct.toFixed(0)}%` : "—"}</span>
          <span>ATR(14)</span><span>{daily.atr_cents ? fmt.dollars(daily.atr_cents) : "—"}</span>
        </div>
      )}

      {/* ── Support / Resistance ── */}
      {breakdown && (breakdown.levels.supports_cents.length > 0 || breakdown.levels.resistances_cents.length > 0) && (
        <div className="levels-section">
          <div style={{ fontSize: 10, color: "var(--text-4)", textTransform: "uppercase", letterSpacing: "0.05em", marginBottom: 4 }}>
            {t("tech.levels")}
          </div>
          <div className="levels-row">
            <span style={{ color: "var(--danger)" }}>{t("tech.resistances")}</span>
            {breakdown.levels.resistances_cents.length === 0
              ? <span className="level-empty">{t("tech.noResistance")}</span>
              : breakdown.levels.resistances_cents.map((r, i) =>
                  <span key={i} className="level-chip level-resistance">{fmt.dollars(r)}</span>)
            }
          </div>
          <div className="levels-row">
            <span style={{ color: "var(--success)" }}>{t("tech.supports")}</span>
            {breakdown.levels.supports_cents.length === 0
              ? <span className="level-empty">{t("tech.noSupport")}</span>
              : breakdown.levels.supports_cents.map((s, i) =>
                  <span key={i} className="level-chip level-support">{fmt.dollars(s)}</span>)
            }
          </div>
        </div>
      )}

      {/* ── Divergences price/RSI (high-impact signal) ── */}
      {breakdown && breakdown.divergences.length > 0 && (
        <div className="divergences-section">
          <div style={{ fontSize: 10, color: "#64748b", textTransform: "uppercase", letterSpacing: "0.05em", marginBottom: 6 }}>
            🔀 Divergencias precio-RSI detectadas
          </div>
          <div className="divergence-cards">
            {breakdown.divergences.map((div, i) => (
              <DivergenceCard key={i} div={div} />
            ))}
          </div>
        </div>
      )}

      {/* ── Detected patterns with explanations ── */}
      {breakdown && breakdown.patterns.length > 0 && (
        <div className="patterns-section">
          <div style={{ fontSize: 10, color: "#64748b", textTransform: "uppercase", letterSpacing: "0.05em", marginBottom: 6 }}>
            Patrones de velas detectados (últimas 5 sesiones)
          </div>
          <div className="pattern-cards">
            {breakdown.patterns.slice(0, 6).map((p, i) => (
              <div key={i} className={`pattern-card pattern-${p.bias.toLowerCase()}`}>
                <div className="pattern-card-head">
                  <span className="pattern-name">{p.name}</span>
                  <span className="pattern-meta">
                    {p.bias === "Bullish" ? "🟢 Alcista" : p.bias === "Bearish" ? "🔴 Bajista" : "⚪ Neutral"}
                    {" · "}
                    {p.bars_ago === 0 ? "vela actual" : `hace ${p.bars_ago} velas`}
                  </span>
                </div>
                {PATTERN_INFO[p.name] && (
                  <p className="pattern-explain">{PATTERN_INFO[p.name]}</p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── Glossary (collapsible) ── */}
      {showGlossary && (
        <div className="glossary-box">
          <h4>📖 Guía rápida</h4>

          <div className="glossary-section">
            <strong>¿Qué es multi-timeframe?</strong>
            <p>Analizamos la acción en 3 escalas a la vez:</p>
            <ul>
              <li><b>Weekly</b>: tendencia macro (¿qué viene haciendo en los últimos meses?)</li>
              <li><b>Daily</b>: tendencia primaria (¿qué hizo en las últimas semanas?)</li>
              <li><b>Hourly</b>: timing intraday (¿qué está haciendo ahora mismo?)</li>
            </ul>
            <p>Cuando los 3 timeframes coinciden (alcista o bajista), la señal es muy confiable. Cuando se contradicen, conviene esperar.</p>
          </div>

          <div className="glossary-section">
            <strong>Sub-scores (-100 a +100)</strong>
            <ul>
              {Object.entries(SUBSCORE_INFO).map(([k, v]) => (
                <li key={k}><b>{k}</b>: {v}</li>
              ))}
            </ul>
          </div>

          <div className="glossary-section">
            <strong>Indicadores</strong>
            <ul>
              <li><b>RSI</b>: 0-100. &lt;30 = sobreventa (posible rebote). &gt;70 = sobrecompra (posible corrección). 40-60 = zona neutral.</li>
              <li><b>ADX</b>: Mide cuán fuerte es la tendencia, no su dirección. &gt;25 = trending claro. &lt;20 = lateral/sin rumbo.</li>
              <li><b>MACD</b>: ↑ momentum acelerando hacia arriba. ↓ desacelerando.</li>
              <li><b>BB %B</b>: Dónde está el precio dentro de las Bandas de Bollinger. 0 = banda inferior, 1 = banda superior. Extremos suelen revertir.</li>
              <li><b>Vol vs MA</b>: Volumen actual / promedio 20 días. 2x = doble del normal (movimiento con convicción).</li>
              <li><b>OBV</b>: On-Balance Volume. Acumula volumen según si el precio subió o bajó — detecta si el dinero entra o sale.</li>
              <li><b>ATR</b>: Promedio del rango diario. Útil para definir stop-loss (ej: stop a 2× ATR del precio).</li>
            </ul>
          </div>

          <div className="glossary-section">
            <strong>Soporte y Resistencia</strong>
            <p><b>Soporte</b>: nivel donde el precio históricamente rebota hacia arriba (zona de compradores). <b>Resistencia</b>: nivel donde el precio históricamente se frena (zona de vendedores). Los detectamos automáticamente buscando los máximos y mínimos locales más recientes.</p>
          </div>

          <div className="glossary-section">
            <strong>Patrones de velas</strong>
            <ul>
              {Object.entries(PATTERN_INFO).map(([k, v]) => (
                <li key={k}><b>{k}</b>: {v}</li>
              ))}
            </ul>
          </div>

          <div className="glossary-section">
            <strong>Divergencias precio-RSI</strong>
            <p>Una divergencia ocurre cuando el precio y el RSI no se mueven en la misma dirección. Son señales avanzadas porque detectan debilidad oculta antes de que se vea en el precio.</p>
            <ul>
              <li><b>Regular Bullish</b>: precio LL pero RSI HL → reversión alcista probable. Aparece al final de bajadas.</li>
              <li><b>Regular Bearish</b>: precio HH pero RSI LH → reversión bajista probable. Aparece al final de subidas.</li>
              <li><b>Hidden Bullish</b>: precio HL pero RSI LL → continuación alcista. El pullback no rompió la estructura.</li>
              <li><b>Hidden Bearish</b>: precio LH pero RSI HH → continuación bajista. El rally es solo correctivo.</li>
            </ul>
            <p style={{ color: "#94a3b8", fontStyle: "italic" }}>HH=Higher High, HL=Higher Low, LH=Lower High, LL=Lower Low.</p>
          </div>
        </div>
      )}
    </div>
  );
}

function SubScore({ label, score }: { label: string; score: number | null }) {
  const color = score == null ? "#475569"
    : score >= 30 ? "#22c55e"
    : score >= 0  ? "#f59e0b"
    : "#ef4444";
  return (
    <div className="subscore" title={SUBSCORE_INFO[label] ?? ""}>
      <div className="subscore-label">{label}</div>
      <div className="subscore-value" style={{ color }}>
        {score == null ? "—" : score > 0 ? `+${score}` : `${score}`}
      </div>
    </div>
  );
}

function TrendCell({ t }: { t: TrendState }) {
  const arrow = t === "Bullish" ? "↑" : t === "Bearish" ? "↓" : t === "Neutral" ? "→" : "·";
  return <span style={{ color: TREND_COLOR[t], fontWeight: 600 }}>{arrow} {t}</span>;
}

function Row({
  label, tooltip, w, d, h, wColor, dColor, hColor,
}: {
  label: string; tooltip?: string; w: string; d: string; h: string;
  wColor?: string; dColor?: string; hColor?: string;
}) {
  return (
    <tr title={tooltip}>
      <td>{label}</td>
      <td style={{ color: wColor }}>{w}</td>
      <td style={{ color: dColor }}>{d}</td>
      <td style={{ color: hColor }}>{h}</td>
    </tr>
  );
}

const fmtRsi = (v: number | null | undefined) => v == null ? "—" : v.toFixed(1);
const fmtAdx = (v: number | null | undefined) => v == null ? "—" : v.toFixed(1);
const fmtPct01 = (v: number | null | undefined) => v == null ? "—" : v.toFixed(2);
const fmtX = (v: number | null | undefined) => v == null ? "—" : `${v.toFixed(2)}x`;

function rsiColor(v: number | null | undefined): string | undefined {
  if (v == null) return undefined;
  if (v >= 70) return "#ef4444";  // overbought
  if (v <= 30) return "#22c55e";  // oversold
  return undefined;
}

function macdDir(c: ChartSummary | null | undefined): string {
  if (!c || c.macd_cents == null || c.signal_cents == null) return "—";
  if (c.macd_cents > c.signal_cents) return "↑";
  if (c.macd_cents < c.signal_cents) return "↓";
  return "→";
}

function DivergenceCard({ div }: { div: Divergence }) {
  const isBull = div.bias === "Bullish";
  const isRegular = div.kind.startsWith("Regular");
  const kindLabel = isRegular ? "Reversión" : "Continuación";
  const priceDir = div.price_at_p2 > div.price_at_p1 ? "↑" : "↓";
  const rsiDir   = div.rsi_at_p2   > div.rsi_at_p1   ? "↑" : "↓";
  return (
    <div className={`divergence-card ${isBull ? "div-bull" : "div-bear"}`}>
      <div className="divergence-head">
        <span className="div-name">{div.label}</span>
        <span className="div-meta">
          {isBull ? "🟢" : "🔴"} {kindLabel}
          {" · "}
          hace {div.bars_ago}d
          {" · "}
          fuerza {(div.strength * 100).toFixed(0)}%
        </span>
      </div>
      <p className="div-explain">{DIVERGENCE_INFO[div.kind] ?? ""}</p>
      <div className="div-evidence">
        <span>Precio: {fmt.dollars(div.price_at_p1)} {priceDir} {fmt.dollars(div.price_at_p2)}</span>
        <span>RSI: {div.rsi_at_p1.toFixed(1)} {rsiDir} {div.rsi_at_p2.toFixed(1)}</span>
      </div>
    </div>
  );
}

function frameTooltip(label: string): string {
  switch (label) {
    case "Monthly": return "Ciclo macro: tendencia de varios años. Detecta si estamos en bull o bear market estructural.";
    case "Weekly":  return "Tendencia primaria de varios meses. El timeframe más importante para inversores swing.";
    case "Daily":   return "Tendencia del día a día. La 'verdad' operativa — el setup técnico clásico vive acá.";
    case "Hourly":  return "Estructura intraday. Útil para timing de entrada en operaciones swing o intraday.";
    default: return label;
  }
}

// ── Profile selector ──────────────────────────────────────────────────────────

function ProfileSelector({
  profile, onChange,
}: { profile: Profile; onChange: (p: Profile) => void }) {
  const { t } = useT();
  const labelKey: Record<Profile, string> = {
    investor: "tech.profile.investor",
    swing:    "tech.profile.swing",
    daytrade: "tech.profile.daytrade",
  };
  return (
    <div className="profile-selector">
      <div className="profile-label">{t("tech.profile")}</div>
      <div className="profile-options">
        {(["investor", "swing", "daytrade"] as Profile[]).map(p => (
          <button
            key={p}
            className={`profile-btn ${profile === p ? "active" : ""}`}
            onClick={() => onChange(p)}
            title={PROFILE_INFO[p].description}
          >
            {t(labelKey[p])}
          </button>
        ))}
      </div>
      <div className="profile-frames">
        {t("tech.frames")} <strong>{PROFILE_INFO[profile].frames.join(" → ")}</strong>
      </div>
    </div>
  );
}

// ── Summary box (top of panel) ────────────────────────────────────────────────

const VERDICT_STYLE: Record<TechnicalSummary["verdict"], { color: string; bg: string; emoji: string }> = {
  "Strong Bullish":     { color: "#22c55e", bg: "rgba(34,197,94,0.12)",  emoji: "🟢" },
  "Mildly Bullish":     { color: "#4ade80", bg: "rgba(34,197,94,0.06)",  emoji: "🟢" },
  "Neutral":            { color: "#f59e0b", bg: "rgba(245,158,11,0.08)", emoji: "⚪" },
  "Mildly Bearish":     { color: "#f87171", bg: "rgba(239,68,68,0.06)",  emoji: "🔴" },
  "Strong Bearish":     { color: "#ef4444", bg: "rgba(239,68,68,0.12)",  emoji: "🔴" },
  "Insufficient data":  { color: "#64748b", bg: "rgba(100,116,139,0.08)", emoji: "⏳" },
};

function TechnicalSummaryBox({ summary }: { summary: TechnicalSummary }) {
  const { t } = useT();
  const st = VERDICT_STYLE[summary.verdict];
  return (
    <div className="tech-summary" style={{ borderLeftColor: st.color, background: st.bg }}>
      {/* Headline */}
      <div className="tech-summary-head">
        <div>
          <span style={{ fontSize: 16 }}>{st.emoji}</span>
          <span className="tech-verdict" style={{ color: st.color }}>{summary.verdict}</span>
          <span className="tech-meta">
            score {summary.score > 0 ? "+" : ""}{summary.score} · {t("tech.confidence")} {t(`tech.conf.${summary.confidence === "Alta" ? "high" : summary.confidence === "Media" ? "medium" : "low"}`)}
          </span>
        </div>
        <ScoreGauge value={summary.score} color={st.color} />
      </div>

      {/* Narrative + action */}
      <p className="tech-narrative">{summary.narrative}</p>
      <p className="tech-action">{summary.action_hint}</p>

      {/* Contradictions (if any) */}
      {summary.contradictions.length > 0 && (
        <div className="tech-contradictions">
          {summary.contradictions.map((c, i) => (
            <div key={i}>⚠️ {c}</div>
          ))}
        </div>
      )}

      {/* Top signals breakdown */}
      {(summary.positives.length > 0 || summary.negatives.length > 0) && (
        <div className="tech-signals-grid">
          <div className="tech-signals-col">
            <div className="signals-col-header" style={{ color: "var(--success)" }}>
              ▲ {t("tech.proLabel")} ({summary.positives.length})
            </div>
            {summary.positives.length === 0 ? (
              <div className="signal-empty">{t("tech.noProSignals")}</div>
            ) : (
              summary.positives.map((s, i) => <SignalRow key={i} sig={s} />)
            )}
          </div>
          <div className="tech-signals-col">
            <div className="signals-col-header" style={{ color: "var(--danger)" }}>
              ▼ {t("tech.conLabel")} ({summary.negatives.length})
            </div>
            {summary.negatives.length === 0 ? (
              <div className="signal-empty">{t("tech.noConSignals")}</div>
            ) : (
              summary.negatives.map((s, i) => <SignalRow key={i} sig={s} />)
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function SignalRow({ sig }: { sig: KeySignal }) {
  const impact = sig.weight * sig.strength;
  const impactLabel = impact >= 20 ? "alto" : impact >= 10 ? "medio" : "bajo";
  return (
    <div className="signal-row" title={sig.detail}>
      <div className="signal-row-head">
        <span className="signal-label">{sig.label}</span>
        <span className="signal-impact">{impactLabel}</span>
      </div>
      <div className="signal-detail">{sig.detail}</div>
    </div>
  );
}

function ScoreGauge({ value, color }: { value: number; color: string }) {
  // Horizontal bar from -100..+100 with the marker positioned by value
  const pos = Math.max(0, Math.min(100, (value + 100) / 2)); // 0..100
  return (
    <div className="score-gauge" title={`Score técnico: ${value}`}>
      <div className="gauge-track">
        <div className="gauge-center" />
        <div className="gauge-marker" style={{ left: `${pos}%`, background: color }} />
      </div>
      <div className="gauge-scale">
        <span>-100</span><span>0</span><span>+100</span>
      </div>
    </div>
  );
}
