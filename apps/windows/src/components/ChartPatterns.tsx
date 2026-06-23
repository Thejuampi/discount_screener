import { useState } from "react";
import { fmt } from "../api";
import type { ChartPattern } from "../api";
import { useT } from "../i18n";

/** Renders detected classic chart patterns (double tops, triangles, H&S, …). */
export function ChartPatterns({ patterns }: { patterns: ChartPattern[] }) {
  const { t, lang } = useT();
  const [open, setOpen] = useState<number | null>(null);

  if (!patterns || patterns.length === 0) {
    return <div style={{ fontSize: 12, color: "var(--text-4)" }}>{t("pat.none")}</div>;
  }
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {patterns.map((p, i) => {
        const col = p.direction === "Bullish" ? "var(--success)" : p.direction === "Bearish" ? "var(--danger)" : "var(--text-3)";
        const arrow = p.direction === "Bullish" ? "▲" : p.direction === "Bearish" ? "▼" : "◆";
        const label = lang === "es" ? p.label_es : p.label_en;
        const expl = lang === "es" ? p.explanation_es : p.explanation_en;
        const isOpen = open === i;
        return (
          <div key={i} style={{ borderRadius: 8, border: `1px solid ${col}`, background: "var(--surface-3)", overflow: "hidden" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", padding: "9px 12px", cursor: "pointer" }}
              onClick={() => setOpen(isOpen ? null : i)}>
              <span className="pat-tf">{p.timeframe}</span>
              <span style={{ color: col, fontWeight: 700, fontSize: 13 }}>{arrow} {label}</span>
              <span style={{
                fontSize: 10, textTransform: "uppercase", letterSpacing: "0.04em", fontWeight: 700,
                padding: "2px 7px", borderRadius: 6,
                background: p.forming ? "rgba(245,158,11,0.15)" : "rgba(34,197,94,0.15)",
                color: p.forming ? "var(--warning)" : "var(--success)",
              }}>
                {p.forming ? t("pat.forming") : t("pat.confirmed")}
              </span>
              <span style={{ marginLeft: "auto", fontSize: 11, color: "var(--text-4)" }}>
                {t("pat.target")} {fmt.dollars(p.target_cents)} · {t("pat.level")} {fmt.dollars(p.key_level_cents)}
              </span>
              <span style={{ fontSize: 11, fontWeight: 700, color: "var(--text-2)" }}>{p.confidence}%</span>
              <span style={{ fontSize: 11, color: "var(--text-4)" }}>{isOpen ? "▲" : "▼"}</span>
            </div>
            {isOpen && expl && (
              <div style={{ padding: "0 12px 11px", fontSize: 12, color: "var(--text-3)", lineHeight: 1.55 }}>{expl}</div>
            )}
          </div>
        );
      })}
    </div>
  );
}
