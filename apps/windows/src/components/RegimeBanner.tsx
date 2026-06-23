import { useEffect, useState } from "react";
import { api } from "../api";
import type { MarketRegime, RegimeKind } from "../api";
import { useT } from "../i18n";

const REGIME_STYLE: Record<RegimeKind, { color: string; bg: string; border: string }> = {
  RiskOn:  { color: "#22c55e", bg: "rgba(34,197,94,0.10)",  border: "rgba(34,197,94,0.40)" },
  Neutral: { color: "#f59e0b", bg: "rgba(245,158,11,0.10)", border: "rgba(245,158,11,0.40)" },
  RiskOff: { color: "#f43f5e", bg: "rgba(244,63,94,0.10)",  border: "rgba(244,63,94,0.40)" },
  Unknown: { color: "#94a3b8", bg: "rgba(148,163,184,0.08)", border: "rgba(148,163,184,0.30)" },
};

export function RegimeBanner() {
  const { t, lang } = useT();
  const [regime, setRegime] = useState<MarketRegime | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = () => api.getMarketRegime()
      .then((r) => { if (!cancelled) setRegime(r); })
      .catch(console.error);
    load();
    const id = setInterval(load, 120_000); // refresh every 2 min
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  if (!regime) return null;
  const st = REGIME_STYLE[regime.regime];
  const notes = lang === "es" ? regime.notes_es : regime.notes_en;

  return (
    <div className="regime-banner" style={{
      display: "flex", alignItems: "center", flexWrap: "wrap", gap: 14,
      padding: "8px 14px", borderRadius: 8, marginBottom: 10,
      background: st.bg, border: `1px solid ${st.border}`,
    }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ width: 9, height: 9, borderRadius: "50%", background: st.color, boxShadow: `0 0 8px ${st.color}` }} />
        <span style={{ fontSize: 10, color: "var(--text-4)", textTransform: "uppercase", letterSpacing: "0.06em", fontWeight: 700 }}>
          {t("regime.title")}
        </span>
        <strong style={{ fontSize: 15, color: st.color }}>{t(`regime.${regime.regime}`)}</strong>
        {regime.regime !== "Unknown" && (
          <span style={{ fontSize: 11, color: "var(--text-3)" }}>
            · {t("regime.exposure")}: <strong style={{ color: st.color }}>{regime.suggested_exposure_pct}%</strong>
          </span>
        )}
      </div>

      <div style={{ display: "flex", gap: 14, marginLeft: "auto", flexWrap: "wrap" }}>
        {regime.vix != null && (
          <Chip label={t("regime.vix")} value={regime.vix.toFixed(1)} />
        )}
        {regime.breadth_above_ma200_pct != null && (
          <Chip label={t("regime.breadth")} value={`${regime.breadth_above_ma200_pct.toFixed(0)}%`} />
        )}
        {regime.spy_above_ma200 != null && (
          <Chip
            label={t("regime.spy")}
            value={regime.spy_above_ma200 ? `▲ ${t("regime.spyUp")}` : `▼ ${t("regime.spyDown")}`}
            color={regime.spy_above_ma200 ? "#22c55e" : "#f43f5e"}
          />
        )}
      </div>

      <div style={{ flexBasis: "100%", fontSize: 11, color: "var(--text-4)", lineHeight: 1.5 }}>
        {t(`regime.help.${regime.regime}`)}
        {notes.length > 0 && <span style={{ color: "var(--text-5)" }}> — {notes.join(" · ")}</span>}
      </div>
    </div>
  );
}

function Chip({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <span style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", lineHeight: 1.2 }}>
      <span style={{ fontSize: 9, color: "var(--text-5)", textTransform: "uppercase", letterSpacing: "0.04em" }}>{label}</span>
      <strong style={{ fontSize: 13, color: color ?? "var(--text-1)" }}>{value}</strong>
    </span>
  );
}
