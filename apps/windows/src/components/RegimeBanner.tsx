import { useEffect, useState } from "react";
import { api } from "../api";
import type { MarketRegime, RegimePillar } from "../api";
import { useT } from "../i18n";
import { RegimeRadar } from "./RegimeRadar";

const PHASE_STYLE: Record<string, { color: string; bg: string; border: string }> = {
  StrongBull:    { color: "#22c55e", bg: "rgba(34,197,94,0.12)",  border: "rgba(34,197,94,0.45)" },
  Bull:          { color: "#4ade80", bg: "rgba(74,222,128,0.10)", border: "rgba(74,222,128,0.40)" },
  LateBull:      { color: "#f59e0b", bg: "rgba(245,158,11,0.12)", border: "rgba(245,158,11,0.45)" },
  Range:         { color: "#94a3b8", bg: "rgba(148,163,184,0.10)", border: "rgba(148,163,184,0.35)" },
  Correction:    { color: "#fb923c", bg: "rgba(251,146,60,0.12)", border: "rgba(251,146,60,0.40)" },
  Bear:          { color: "#f43f5e", bg: "rgba(244,63,94,0.12)",  border: "rgba(244,63,94,0.40)" },
  Capitulation:  { color: "#e11d48", bg: "rgba(225,29,72,0.14)",  border: "rgba(225,29,72,0.50)" },
  Snapback:      { color: "#38bdf8", bg: "rgba(56,189,248,0.12)", border: "rgba(56,189,248,0.40)" },
  Unknown:       { color: "#94a3b8", bg: "rgba(148,163,184,0.08)", border: "rgba(148,163,184,0.30)" },
};

function phaseStyle(phase: string) {
  return PHASE_STYLE[phase] ?? PHASE_STYLE.Unknown;
}

function scoreColor(score: number, invert = false): string {
  const s = invert ? -score : score;
  if (s >= 35) return "#22c55e";
  if (s <= -35) return "#f43f5e";
  if (s >= 10) return "#4ade80";
  if (s <= -10) return "#fb7185";
  return "#f59e0b";
}

function toneColor(tone?: string): string {
  switch (tone) {
    case "bullish":
      return "#22c55e";
    case "opportunity":
      return "#38bdf8";
    case "caution":
      return "#fbbf24";
    case "bearish":
      return "#f43f5e";
    default:
      return "var(--text-4)";
  }
}

function PillarCard({ pillar, lang }: { pillar: RegimePillar; lang: string }) {
  const score = pillar.score;
  const pct = Math.min(100, Math.abs(score));
  const positive = score >= 0;
  const name = lang === "es" ? pillar.name_es : pillar.name_en;
  const conf = (pillar.confidence_bps / 100).toFixed(0);
  const invert = pillar.id === "volatility";
  const color = scoreColor(score, invert);
  const interpretation =
    lang === "es" ? pillar.interpretation_es : pillar.interpretation_en;
  const strongSignals = pillar.signals.filter((s) => Math.abs(s.contribution) >= 25);

  return (
    <div style={{
      marginBottom: 10,
      paddingBottom: 10,
      borderBottom: "1px solid rgba(148,163,184,0.10)",
    }}>
      <div style={{ display: "flex", justifyContent: "space-between", fontSize: 11, marginBottom: 3 }}>
        <span style={{ color: "var(--text-2)", fontWeight: 600 }}>
          <span style={{
            display: "inline-block", width: 7, height: 7, borderRadius: "50%",
            background: toneColor(pillar.tone), marginRight: 6,
          }} />
          {name}
          {pillar.stale && <span style={{ color: "var(--text-5)", marginLeft: 6 }}>(stale)</span>}
        </span>
        <span style={{ color, fontWeight: 700 }}>
          {score > 0 ? "+" : ""}{score}
          <span style={{ color: "var(--text-5)", fontWeight: 400, marginLeft: 6 }}>conf {conf}%</span>
        </span>
      </div>
      <div style={{
        position: "relative", height: 5, borderRadius: 3,
        background: "rgba(148,163,184,0.15)", overflow: "hidden", marginBottom: 5,
      }}>
        <div style={{
          position: "absolute", left: "50%", top: 0, bottom: 0, width: 1,
          background: "rgba(148,163,184,0.35)",
        }} />
        <div style={{
          position: "absolute",
          top: 0, bottom: 0,
          left: positive ? "50%" : `${50 - pct / 2}%`,
          width: `${pct / 2}%`,
          background: color,
          borderRadius: 3,
          opacity: 0.85,
        }} />
      </div>
      {interpretation && (
        <div style={{ fontSize: 11, color: "var(--text-3)", lineHeight: 1.45 }}>
          {interpretation}
        </div>
      )}
      {strongSignals.length > 0 && (
        <ul style={{
          margin: "4px 0 0", paddingLeft: 16, fontSize: 10, color: "var(--text-5)",
          lineHeight: 1.4,
        }}>
          {strongSignals.slice(0, 3).map((s) => {
            const hint = lang === "es" ? s.hint_es : s.hint_en;
            return (
              <li key={s.id}>
                {lang === "es" ? s.label_es : s.label_en}
                {s.detail ? ` (${s.detail})` : ""}
                {hint ? ` — ${hint}` : ""}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

export function RegimeBanner() {
  const { t, lang } = useT();
  const [regime, setRegime] = useState<MarketRegime | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const load = () => api.getMarketRegime()
      .then((r) => { if (!cancelled) setRegime(r); })
      .catch(console.error);
    load();
    const id = setInterval(load, 120_000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  // Soft loading state — never blank the whole dashboard if regime is slow.
  if (!regime) {
    return (
      <div className="regime-banner" style={{
        borderRadius: 8, marginBottom: 10, padding: "8px 14px",
        background: "rgba(148,163,184,0.08)", border: "1px solid rgba(148,163,184,0.25)",
        fontSize: 11, color: "var(--text-4)",
      }}>
        {t("regime.title")}… {t("empty.loading")}
      </div>
    );
  }

  const phase = regime.primary_regime || "Unknown";
  const st = phaseStyle(phase);
  const thesis = lang === "es" ? regime.thesis_es : regime.thesis_en;
  const reading = lang === "es" ? regime.reading_es : regime.reading_en;
  const bullets = (lang === "es" ? regime.action_bullets_es : regime.action_bullets_en) ?? [];
  const notes = lang === "es" ? regime.notes_es : regime.notes_en;
  const mult = (regime.new_risk_multiplier_bps ?? 10000) / 10000;
  const conf = ((regime.global_confidence_bps ?? 0) / 100).toFixed(0);
  const stanceKey = `regime.stance.${regime.action_stance}`;
  const phaseKey = `regime.phase.${regime.primary_regime}`;
  const stanceLabel = t(stanceKey) !== stanceKey ? t(stanceKey) : regime.action_stance;
  const phaseLabel = t(phaseKey) !== phaseKey ? t(phaseKey) : regime.primary_regime;

  const fngColor = (() => {
    const v = regime.cnn_fear_greed;
    if (v == null) return "var(--text-1)";
    if (v <= 24) return "#38bdf8";
    if (v <= 44) return "#60a5fa";
    if (v <= 55) return "#94a3b8";
    if (v <= 75) return "#fbbf24";
    return "#f43f5e";
  })();

  return (
    <div className="regime-banner" style={{
      borderRadius: 8, marginBottom: 10,
      background: st.bg, border: `1px solid ${st.border}`,
      padding: "8px 14px",
    }}>
      {/* ── Compact header ── */}
      <div style={{ display: "flex", alignItems: "center", flexWrap: "wrap", gap: 12 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
          <span style={{
            width: 9, height: 9, borderRadius: "50%", background: st.color,
            boxShadow: `0 0 8px ${st.color}`,
          }} />
          <span style={{
            fontSize: 10, color: "var(--text-4)", textTransform: "uppercase",
            letterSpacing: "0.06em", fontWeight: 700,
          }}>
            {t("regime.title")}
          </span>
          <strong style={{ fontSize: 15, color: st.color }}>{phaseLabel}</strong>
          <span style={{ fontSize: 11, color: "var(--text-3)" }}>
            · {t("regime.exposure")}:{" "}
            <strong style={{ color: st.color }}>{regime.suggested_exposure_pct}%</strong>
          </span>
          <span style={{ fontSize: 11, color: "var(--text-3)" }}>
            · {t("regime.stance")}:{" "}
            <strong style={{ color: "var(--text-1)" }}>{stanceLabel}</strong>
          </span>
          <span style={{ fontSize: 11, color: "var(--text-4)" }}>
            · {t("regime.riskMult")}: <strong>{mult.toFixed(2)}×</strong>
          </span>
          <span style={{ fontSize: 10, color: "var(--text-5)" }}>
            · {t("regime.conf")} {conf}%
          </span>
        </div>

        <div style={{ display: "flex", gap: 12, marginLeft: "auto", flexWrap: "wrap", alignItems: "center" }}>
          {regime.vix != null && (
            <Chip
              label={t("regime.vix")}
              value={`${regime.vix.toFixed(1)}${regime.vix_percentile_1y != null ? ` p${regime.vix_percentile_1y.toFixed(0)}` : ""}${
                regime.vix_term_ratio != null
                  ? (regime.vix_term_ratio > 1 ? " ⌄" : " ⌃")
                  : ""
              }`}
            />
          )}
          {regime.cnn_fear_greed != null && (
            <Chip
              label={t("regime.fng")}
              value={`${regime.cnn_fear_greed}${regime.cnn_fear_greed_label ? ` ${regime.cnn_fear_greed_label}` : ""}`}
              color={fngColor}
            />
          )}
          {regime.breadth_above_ma200_pct != null && (
            <Chip
              label={t("regime.breadth")}
              value={`${regime.breadth_above_ma200_pct.toFixed(0)}%${
                regime.breadth_above_ma50_pct != null
                  ? ` / ${regime.breadth_above_ma50_pct.toFixed(0)}%`
                  : ""
              }`}
            />
          )}
          {regime.spy_above_ma200 != null && (
            <Chip
              label={t("regime.spy")}
              value={regime.spy_above_ma200 ? `▲ ${t("regime.spyUp")}` : `▼ ${t("regime.spyDown")}`}
              color={regime.spy_above_ma200 ? "#22c55e" : "#f43f5e"}
            />
          )}
          {regime.spy_drawdown_from_ath_pct != null && regime.spy_drawdown_from_ath_pct > 1 && (
            <Chip
              label={t("regime.drawdown")}
              value={`−${regime.spy_drawdown_from_ath_pct.toFixed(1)}%`}
              color="#fb923c"
            />
          )}
          <button
            type="button"
            onClick={() => setOpen((o) => !o)}
            style={{
              background: "transparent", border: `1px solid ${st.border}`,
              color: "var(--text-3)", borderRadius: 6, padding: "2px 8px",
              fontSize: 10, cursor: "pointer", fontWeight: 600,
            }}
          >
            {open ? t("regime.hideDetails") : t("regime.showDetails")}
          </button>
        </div>
      </div>

      {/* Thesis */}
      <div style={{ marginTop: 6, fontSize: 11, color: "var(--text-3)", lineHeight: 1.5 }}>
        {thesis || t(`regime.help.${regime.regime}`)}
        {!open && notes.length > 0 && (
          <span style={{ color: "var(--text-5)" }}> — {notes.slice(0, 3).join(" · ")}</span>
        )}
      </div>

      {/* ── Expanded panel ── */}
      {open && (
        <div style={{
          marginTop: 12, paddingTop: 12,
          borderTop: `1px solid ${st.border}`,
        }}>
          <div style={{
            display: "grid",
            gridTemplateColumns: "minmax(200px, 260px) minmax(200px, 1fr) minmax(220px, 1.1fr)",
            gap: 16,
            alignItems: "start",
          }}>
            {/* Radar */}
            <div>
              <div style={{
                fontSize: 10, textTransform: "uppercase", letterSpacing: "0.06em",
                color: "var(--text-5)", fontWeight: 700, marginBottom: 6, textAlign: "center",
              }}>
                {t("regime.radar.title")}
              </div>
              <RegimeRadar
                pillars={regime.pillars ?? []}
                color={st.color}
                lang={lang}
              />
            </div>

            {/* Aggregate reading */}
            <div>
              <div style={{
                fontSize: 10, textTransform: "uppercase", letterSpacing: "0.06em",
                color: "var(--text-5)", fontWeight: 700, marginBottom: 8,
              }}>
                {t("regime.reading")}
              </div>
              <div style={{ fontSize: 12, color: "var(--text-2)", lineHeight: 1.55 }}>
                {reading || thesis}
              </div>

              {bullets.length > 0 && (
                <>
                  <div style={{
                    fontSize: 10, textTransform: "uppercase", letterSpacing: "0.06em",
                    color: "var(--text-5)", fontWeight: 700, margin: "12px 0 6px",
                  }}>
                    {t("regime.actions")}
                  </div>
                  <ul style={{
                    margin: 0, paddingLeft: 18, fontSize: 11, color: "var(--text-3)",
                    lineHeight: 1.5,
                  }}>
                    {bullets.map((b, i) => (
                      <li key={i} style={{ marginBottom: 4 }}>{b}</li>
                    ))}
                  </ul>
                </>
              )}

              <div style={{ fontSize: 10, color: "var(--text-5)", marginTop: 10 }}>
                E={regime.environment_score} · S={regime.sentiment_score} · Q={regime.quality_score}
                {" · "}
                {t("regime.cashBuffer")}: {regime.cash_buffer_pct}%
                {regime.prefer_quality ? ` · ${t("regime.preferQuality")}` : ""}
              </div>

              {regime.warnings?.length > 0 && (
                <div style={{ marginTop: 8, fontSize: 10, color: "#fbbf24" }}>
                  {regime.warnings.join(" · ")}
                </div>
              )}
              <div style={{ marginTop: 8, fontSize: 10, color: "var(--text-5)" }}>
                {t("regime.disclaimer")}
              </div>
            </div>

            {/* Per-pillar interpretations */}
            <div>
              <div style={{
                fontSize: 10, textTransform: "uppercase", letterSpacing: "0.06em",
                color: "var(--text-5)", fontWeight: 700, marginBottom: 8,
              }}>
                {t("regime.pillarsExplain")}
              </div>
              <div style={{ maxHeight: 340, overflowY: "auto", paddingRight: 4 }}>
                {(regime.pillars ?? []).map((p) => (
                  <PillarCard key={p.id} pillar={p} lang={lang} />
                ))}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function Chip({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <span style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", lineHeight: 1.2 }}>
      <span style={{
        fontSize: 9, color: "var(--text-5)", textTransform: "uppercase", letterSpacing: "0.04em",
      }}>
        {label}
      </span>
      <strong style={{ fontSize: 12, color: color ?? "var(--text-1)", maxWidth: 140, textAlign: "right" }}>
        {value}
      </strong>
    </span>
  );
}
