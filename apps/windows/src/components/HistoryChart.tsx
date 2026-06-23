import { useEffect, useState, useMemo } from "react";
import { api } from "../api";
import type { HistorySnapshot, Decision } from "../api";
import { useT } from "../i18n";

interface Props {
  symbol: string;
}

const RANGES: { label: string; days: number }[] = [
  { label: "7d",  days: 7 },
  { label: "30d", days: 30 },
  { label: "90d", days: 90 },
];

const W = 520;
const H = 180;
const PAD = { top: 10, right: 10, bottom: 22, left: 28 };

const DECISION_COLOR: Record<Decision, string> = {
  Act: "#22c55e",
  Watch: "#f59e0b",
  Avoid: "#ef4444",
};

export function HistoryChart({ symbol }: Props) {
  const { t } = useT();
  const [days, setDays] = useState(30);
  const [data, setData] = useState<HistorySnapshot[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    api.getSymbolHistory(symbol, days)
      .then((d) => { setData(d); setLoading(false); })
      .catch((e) => { console.error(e); setLoading(false); });
  }, [symbol, days]);

  const transitions = useMemo(() => buildTransitions(data), [data]);

  return (
    <div className="info-section">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
        <h3 style={{ margin: 0 }}>📈 {t("history.title")}</h3>
        <div className="range-tabs">
          {RANGES.map((r) => (
            <button
              key={r.days}
              className={`range-tab ${days === r.days ? "active" : ""}`}
              onClick={() => setDays(r.days)}
            >{r.label}</button>
          ))}
        </div>
      </div>

      {loading ? (
        <div className="loading-msg" style={{ padding: 12 }}>{t("history.loading")}</div>
      ) : data.length < 2 ? (
        <div style={{ padding: 12, color: "var(--text-4)", fontSize: 12, lineHeight: 1.5 }}>
          {t("history.empty")}<br/>
          <span style={{ color: "var(--text-5)", fontSize: 11 }}>
            {t("history.empty.hint")}
          </span>
        </div>
      ) : (
        <>
          <ScoreSvg data={data} />
          <TimelineList transitions={transitions} />
        </>
      )}
    </div>
  );
}

// ── SVG chart ─────────────────────────────────────────────────────────────────

function ScoreSvg({ data }: { data: HistorySnapshot[] }) {
  const xMin = data[0].captured_at;
  const xMax = data[data.length - 1].captured_at;
  const xSpan = Math.max(xMax - xMin, 1);

  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const x = (t: number) => PAD.left + ((t - xMin) / xSpan) * innerW;
  const y = (score: number) => PAD.top + ((100 - score) / 200) * innerH; // -100..+100 → top..bottom

  // Path for composite score line
  const path = data.map((d, i) =>
    `${i === 0 ? "M" : "L"} ${x(d.captured_at).toFixed(1)} ${y(d.composite_score).toFixed(1)}`
  ).join(" ");

  // Background bands: Avoid (red <8), Watch (amber 8-10), Act (green ≥10)
  const yAct   = y(100);    // top
  const yActB  = y(10);     // act lower bound
  const yWatch = y(8);
  const yAvoid = y(-100);

  // Latest snapshot for highlight
  const latest = data[data.length - 1];

  // X-axis day ticks: 4 evenly spaced labels
  const ticks = [0, 0.33, 0.66, 1].map(f => {
    const t = xMin + f * xSpan;
    return { x: x(t), label: formatTickDate(t) };
  });

  return (
    <svg width={W} height={H} style={{ display: "block", maxWidth: "100%" }}>
      {/* Background zones */}
      <rect x={PAD.left} y={yAct}    width={innerW} height={yActB - yAct}   fill="rgba(34,197,94,0.10)" />
      <rect x={PAD.left} y={yActB}   width={innerW} height={yWatch - yActB} fill="rgba(245,158,11,0.10)" />
      <rect x={PAD.left} y={yWatch}  width={innerW} height={yAvoid - yWatch} fill="rgba(239,68,68,0.06)" />

      {/* Zero line */}
      <line x1={PAD.left} x2={W - PAD.right} y1={y(0)} y2={y(0)}
            stroke="#334155" strokeDasharray="2,3" strokeWidth={1} />

      {/* Y-axis labels */}
      <text x={4} y={y(100) + 4}  fill="#475569" fontSize={9}>+100</text>
      <text x={4} y={y(0) + 4}    fill="#64748b" fontSize={9}>0</text>
      <text x={4} y={y(-100) + 4} fill="#475569" fontSize={9}>-100</text>
      <text x={4} y={y(10) + 4}   fill="#22c55e" fontSize={9}>10</text>

      {/* Score line */}
      <path d={path} stroke="#6366f1" strokeWidth={2} fill="none" />

      {/* Latest point */}
      <circle
        cx={x(latest.captured_at)}
        cy={y(latest.composite_score)}
        r={4}
        fill={DECISION_COLOR[latest.decision]}
        stroke="#0f172a"
        strokeWidth={2}
      />

      {/* X-axis ticks */}
      {ticks.map((t, i) => (
        <text key={i} x={t.x} y={H - 4} fill="#64748b" fontSize={9} textAnchor="middle">
          {t.label}
        </text>
      ))}

      <title>
        {`Latest: ${latest.decision} (score ${latest.composite_score}) @ ${new Date(latest.captured_at * 1000).toLocaleString()}`}
      </title>
    </svg>
  );
}

// ── Decision transition timeline ──────────────────────────────────────────────

interface Transition {
  from: Decision | null;
  to: Decision;
  at: number;
  durationDays: number;
}

function buildTransitions(data: HistorySnapshot[]): Transition[] {
  if (data.length === 0) return [];
  const out: Transition[] = [];
  let currentDecision = data[0].decision;
  let segmentStart = data[0].captured_at;
  out.push({ from: null, to: currentDecision, at: segmentStart, durationDays: 0 });

  for (let i = 1; i < data.length; i++) {
    if (data[i].decision !== currentDecision) {
      // Close prior segment
      out[out.length - 1].durationDays =
        (data[i].captured_at - segmentStart) / 86_400;
      currentDecision = data[i].decision;
      segmentStart = data[i].captured_at;
      out.push({ from: out[out.length - 1].to, to: currentDecision, at: segmentStart, durationDays: 0 });
    }
  }
  // Close the final (current) segment up to now
  const now = data[data.length - 1].captured_at;
  out[out.length - 1].durationDays = (now - segmentStart) / 86_400;
  return out;
}

function TimelineList({ transitions }: { transitions: Transition[] }) {
  const { t } = useT();
  if (transitions.length === 0) return null;
  // Show most recent 4 transitions, newest first
  const recent = [...transitions].reverse().slice(0, 4);
  return (
    <div style={{ marginTop: 10, display: "flex", flexDirection: "column", gap: 4 }}>
      <div style={{ fontSize: 10, color: "var(--text-4)", textTransform: "uppercase", letterSpacing: "0.05em" }}>
        {t("history.changes")}
      </div>
      {recent.map((tr, i) => {
        const days = tr.durationDays.toFixed(1);
        const isCurrent = i === 0;
        return (
          <div key={i} style={{ fontSize: 11, color: "var(--text-2)", display: "flex", gap: 6, alignItems: "center" }}>
            <span style={{
              background: DECISION_COLOR[tr.to],
              color: "#0f172a",
              padding: "1px 6px",
              borderRadius: 4,
              fontWeight: 600,
              fontSize: 10,
            }}>{tr.to}</span>
            {isCurrent ? (
              <span>{t("history.current")} — {days} {t("history.daysIn")}</span>
            ) : (
              <span style={{ color: "var(--text-3)" }}>
                {t("history.lasted")} {days} {t("history.days")}
                {tr.from && <> ({t("history.cameFrom")} <span style={{ color: DECISION_COLOR[tr.from] }}>{tr.from}</span>)</>}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

function formatTickDate(epochSecs: number): string {
  const d = new Date(epochSecs * 1000);
  return `${d.getDate()}/${d.getMonth() + 1}`;
}
