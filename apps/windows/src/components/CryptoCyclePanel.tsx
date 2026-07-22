import { useEffect, useState } from "react";
import { api, fmt } from "../api";
import type { CryptoMetrics } from "../api";
import { useT } from "../i18n";
import { getScoringPresentation, type ScoringModelId } from "../scoringPresentation";

interface Props {
  symbol: string;
  isCrypto: boolean;
  scoringModel: ScoringModelId;
}

// Approx phase ranges in days (from halving)
const PHASE_RANGES = [
  { name: "PostHalvingExpansion", start: 0,    end: 200,  color: "#0ea5e9" },
  { name: "BullRun",              start: 200,  end: 500,  color: "#f59e0b" },
  { name: "BearMarket",           start: 500,  end: 1000, color: "#fb7185" },
  { name: "AccumulationZone",     start: 1000, end: 1400, color: "#06b6d4" },
  { name: "PreHalvingAccumulation", start: 1400, end: 1460, color: "#8b5cf6" },
];

const LABEL_COLOR: Record<string, string> = {
  StrongAccumulate: "#06b6d4",
  Accumulate:       "#22c55e",
  HoldWait:         "#fbbf24",
  Neutral:          "#94a3b8",
  Caution:          "#fb923c",
  Distribute:       "#f97316",
  Avoid:            "#f43f5e",
};

export function CryptoCyclePanel({ symbol, isCrypto, scoringModel }: Props) {
  const { t, lang } = useT();
  const presentation = getScoringPresentation(scoringModel);
  const [m, setM] = useState<CryptoMetrics | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!isCrypto) return;
    setLoading(true);
    api.getCryptoMetrics(symbol)
      .then((x) => { setM(x); setLoading(false); })
      .catch((e) => { console.error("crypto metrics", e); setLoading(false); });
  }, [symbol, isCrypto]);

  if (!isCrypto) return null;
  if (loading) return null;
  if (!m) return null;

  // Halving timeline position (0..1460 days = full cycle)
  const cycleDay = m.days_since_last_halving;
  const cycleProgress = Math.max(0, Math.min(100, (cycleDay / 1460) * 100));
  const labelColor = LABEL_COLOR[m.crypto_label] ?? "var(--text-3)";

  if (presentation.isShort) {
    const tone = m.crypto_score > 10 ? "risk" : m.crypto_score < -10 ? "support" : "neutral";
    const toneColor = tone === "risk" ? "var(--danger)" : tone === "support" ? "var(--success)" : "var(--warning)";
    return (
      <div className="info-section crypto-cycle-panel">
        <h3>{t("presentation.short.crypto.title")}</h3>
        <div className="crypto-verdict" style={{ borderLeftColor: toneColor }}>
          <div className="crypto-verdict-head">
            <span className="crypto-verdict-label" style={{ color: toneColor }}>{t(`presentation.short.crypto.${tone}`)}</span>
            <span className="crypto-verdict-score">{t("presentation.short.crypto.rawScore")}: <strong>{m.crypto_score > 0 ? "+" : ""}{m.crypto_score}</strong></span>
          </div>
          <p className="crypto-verdict-text">{t(`presentation.short.crypto.${tone}.detail`)}</p>
        </div>
        <div className="crypto-components">
          <Component label={t("presentation.short.crypto.technical")} value={m.technical_component ?? 0} weight={30} tooltip={t("presentation.short.crypto.componentHint")} invert />
          <Component label={t("presentation.short.crypto.accumulation")} value={m.accumulation_component} weight={30} tooltip={t("presentation.short.crypto.componentHint")} invert />
          <Component label={t("presentation.short.crypto.halving")} value={m.halving_component} weight={25} tooltip={t("presentation.short.crypto.componentHint")} invert />
          <Component label={t("presentation.short.crypto.sentiment")} value={m.sentiment_component} weight={15} tooltip={t("presentation.short.crypto.componentHint")} invert />
        </div>
        <div className="kv-grid" style={{ marginTop: 10 }}>
          <span>{t("presentation.short.crypto.cycleDay")}</span><span>{m.days_since_last_halving}</span>
          <span>{t("presentation.short.crypto.drawdown")}</span><span>-{m.drawdown_from_ath_pct.toFixed(1)}%</span>
          <span>{t("presentation.short.crypto.fearGreed")}</span><span>{m.fear_greed ? `${m.fear_greed.value}/100 · ${m.fear_greed.classification}` : "—"}</span>
        </div>
        <div className="crypto-disclaimer">{t("presentation.short.crypto.disclaimer")}</div>
      </div>
    );
  }

  return (
    <div className="info-section crypto-cycle-panel">
      <h3>₿ Crypto Cycle Analysis</h3>

      {/* Verdict banner */}
      <div className="crypto-verdict" style={{ borderLeftColor: labelColor }}>
        <div className="crypto-verdict-head">
          <span className="crypto-verdict-label" style={{ color: labelColor }}>
            {t(`setup.${m.crypto_label}`)}
          </span>
          <span className="crypto-verdict-score">
            Score: <strong style={{ color: labelColor }}>
              {m.crypto_score > 0 ? "+" : ""}{m.crypto_score}
            </strong>
          </span>
        </div>
        <p className="crypto-verdict-text">
          {lang === "es" ? m.explanation_es : m.explanation_en}
        </p>
      </div>

      {/* Component scores breakdown */}
      <div className="crypto-components">
        <Component
          label="Technical"
          value={m.technical_component ?? 0}
          weight={30}
          tooltip="Indicadores técnicos clásicos (EMAs, MACD, RSI) ajustados a volatilidad crypto. Peso reducido."
        />
        <Component
          label="Accumulation"
          value={m.accumulation_component}
          weight={30}
          tooltip="Distancia desde ATH. Drawdowns profundos (>60%) son históricamente las mejores zonas de entrada."
        />
        <Component
          label="Halving Cycle"
          value={m.halving_component}
          weight={25}
          tooltip="Posición en el ciclo de halving de Bitcoin. Pre-halving (1000-1400 días) es históricamente la mejor zona."
        />
        <Component
          label="Sentiment"
          value={m.sentiment_component}
          weight={15}
          tooltip="Fear & Greed Index (alternative.me). CONTRARIAN: Extreme Fear = buy signal histórico, Extreme Greed = sell."
        />
      </div>

      {/* Halving cycle timeline */}
      <div className="crypto-section-label">📅 Posición en ciclo de halving</div>
      <div className="halving-timeline">
        <div className="halving-timeline-track">
          {PHASE_RANGES.map((p) => (
            <div
              key={p.name}
              className="halving-phase"
              style={{
                left: `${(p.start / 1460) * 100}%`,
                width: `${((p.end - p.start) / 1460) * 100}%`,
                background: p.color,
                opacity: m.phase === p.name ? 0.95 : 0.20,
              }}
              title={p.name}
            />
          ))}
          <div
            className="halving-position-marker"
            style={{ left: `${cycleProgress}%` }}
            title={`${cycleDay} días desde el último halving`}
          />
        </div>
        <div className="halving-timeline-meta">
          <span>
            <strong>{cycleDay} días</strong> desde halving · Fase: <strong style={{ color: labelColor }}>
              {lang === "es" ? m.phase_label_es : m.phase_label_en}
            </strong>
          </span>
          <span style={{ color: "var(--text-4)" }}>
            ~{m.days_until_next_halving_est} días al próximo halving
          </span>
        </div>
      </div>

      {/* Drawdown gauge */}
      <div className="crypto-section-label">📉 Drawdown desde ATH</div>
      <div className="drawdown-row">
        <div className="drawdown-bar">
          <div
            className="drawdown-bar-fill"
            style={{
              width: `${Math.min(100, m.drawdown_from_ath_pct)}%`,
              background: drawdownColor(m.drawdown_from_ath_pct),
            }}
          />
          <div className="drawdown-thresholds">
            <span style={{ left: "15%" }} />
            <span style={{ left: "40%" }} />
            <span style={{ left: "65%" }} />
            <span style={{ left: "85%" }} />
          </div>
        </div>
        <div className="drawdown-meta">
          <span>
            ATH: <strong>{fmt.dollars(m.ath_price_cents)}</strong> · Actual: <strong>{fmt.dollars(m.current_price_cents)}</strong>
          </span>
          <span style={{ color: drawdownColor(m.drawdown_from_ath_pct) }}>
            <strong>-{m.drawdown_from_ath_pct.toFixed(1)}%</strong> · Zona: <strong>{m.drawdown_zone}</strong>
          </span>
        </div>
      </div>

      {/* Fear & Greed */}
      {m.fear_greed && (
        <>
          <div className="crypto-section-label">😱 Fear & Greed (Contrarian)</div>
          <div className="fng-row">
            <div className="fng-gauge">
              <div className="fng-gauge-track" />
              <div
                className="fng-marker"
                style={{
                  left: `${m.fear_greed.value}%`,
                  background: fngColor(m.fear_greed.value),
                  boxShadow: `0 0 10px ${fngColor(m.fear_greed.value)}`,
                }}
              />
            </div>
            <div className="fng-meta">
              <span><strong>{m.fear_greed.value}</strong> / 100 · <strong style={{ color: fngColor(m.fear_greed.value) }}>{m.fear_greed.classification}</strong></span>
              <span style={{ color: "var(--text-4)", fontSize: 11 }}>
                Contrarian: Extreme Fear = buy signal · Extreme Greed = sell signal
              </span>
            </div>
          </div>
        </>
      )}

      <div className="crypto-disclaimer">
        ⚠️ Modelo basado en ciclos históricos de BTC. <strong>No es asesoramiento financiero</strong>.
        Los patrones del pasado no garantizan rendimientos futuros — especialmente en crypto donde la
        adopción institucional puede cambiar la naturaleza de los ciclos.
      </div>
    </div>
  );
}

function Component({ label, value, weight, tooltip, invert = false }: { label: string; value: number; weight: number; tooltip: string; invert?: boolean }) {
  const displayValue = invert ? -value : value;
  const color = displayValue > 30 ? "#22c55e" : displayValue > 0 ? "#4ade80" : displayValue > -30 ? "#fbbf24" : displayValue > -60 ? "#fb923c" : "#f43f5e";
  return (
    <div className="crypto-component" title={tooltip}>
      <div className="crypto-component-head">
        <span className="crypto-component-label">{label}</span>
        <span className="crypto-component-weight">×{(weight / 100).toFixed(2)}</span>
      </div>
      <div className="crypto-component-value" style={{ color }}>
        {displayValue > 0 ? "+" : ""}{displayValue}
      </div>
    </div>
  );
}

function drawdownColor(pct: number): string {
  if (pct < 15) return "#f43f5e";
  if (pct < 40) return "#fb923c";
  if (pct < 65) return "#fbbf24";
  if (pct < 85) return "#22c55e";
  return "#06b6d4";
}

function fngColor(v: number): string {
  if (v <= 25) return "#22c55e";       // Extreme fear = green (contrarian buy)
  if (v <= 45) return "#4ade80";
  if (v <= 55) return "#94a3b8";
  if (v <= 75) return "#fb923c";
  return "#f43f5e";                    // Extreme greed = red (contrarian sell)
}
