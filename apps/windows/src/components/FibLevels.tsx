import { fmt } from "../api";
import type { FibAnalysis } from "../api";
import { useT } from "../i18n";

const pct = (r: number) => `${(r * 100).toFixed(r % 1 === 0 ? 0 : 1)}%`;

/** Fibonacci retracement/extension levels for the dominant recent swing. */
export function FibLevels({ fib }: { fib: FibAnalysis | null }) {
  const { t } = useT();
  if (!fib) return <div style={{ fontSize: 12, color: "var(--text-4)" }}>{t("fib.none")}</div>;
  const dirColor = fib.direction === "Up" ? "var(--success)" : "var(--danger)";
  return (
    <div>
      <div style={{ fontSize: 12, color: "var(--text-3)", marginBottom: 10, display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
        <span className="pat-tf">{fib.timeframe}</span>
        <span>{t("fib.swing")}:</span>
        <strong style={{ color: dirColor }}>{fib.direction === "Up" ? `▲ ${t("fib.up")}` : `▼ ${t("fib.down")}`}</strong>
        <span style={{ color: "var(--text-4)" }}>{fmt.dollars(fib.swing_low_cents)} – {fmt.dollars(fib.swing_high_cents)}</span>
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(118px, 1fr))", gap: 6 }}>
        {fib.levels.map((lv, i) => {
          const golden = Math.abs(lv.ratio - 0.618) < 0.001;
          const col = golden ? "#eab308" : lv.kind === "extension" ? "#a855f7" : lv.kind === "anchor" ? "var(--text-4)" : "var(--warning)";
          return (
            <div key={i} style={{
              display: "flex", justifyContent: "space-between", alignItems: "center",
              padding: "5px 9px", borderRadius: 6, background: "var(--surface-3)",
              border: `1px solid ${golden ? col : "var(--border-default)"}`,
            }}>
              <span style={{ fontSize: 11, fontWeight: 700, color: col }}>{pct(lv.ratio)}</span>
              <span style={{ fontSize: 12, color: "var(--text-2)" }}>{fmt.dollars(lv.price_cents)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
