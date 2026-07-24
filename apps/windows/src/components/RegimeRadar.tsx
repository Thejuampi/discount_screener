import { useMemo, useState } from "react";
import type { RegimePillar } from "../api";
import {
  polarPoint,
  polygonPath,
  radarRadius,
  RADAR_AXIS_ORDER,
  type RadarAxisId,
} from "../regimeRadar";
import { useT } from "../i18n";

const SIZE = 240;
const CX = SIZE / 2;
const CY = SIZE / 2;
const MAX_R = 88;
const RINGS = [0.25, 0.5, 0.75, 1];

type Props = {
  pillars: RegimePillar[];
  color: string;
  lang: string;
};

function axisLabelKey(id: RadarAxisId): string {
  return `regime.radar.axis.${id}`;
}

export function RegimeRadar({ pillars, color, lang }: Props) {
  const { t } = useT();
  const [hover, setHover] = useState<string | null>(null);

  const byId = useMemo(() => {
    const m = new Map<string, RegimePillar>();
    for (const p of pillars) m.set(p.id, p);
    return m;
  }, [pillars]);

  const axes = RADAR_AXIS_ORDER.map((id) => {
    const p = byId.get(id);
    const score = p?.score ?? 0;
    const radius =
      p?.radar_radius != null
        ? p.radar_radius
        : radarRadius(id, score);
    const weak = !p || p.stale || (p.confidence_bps ?? 0) < 2000;
    return {
      id,
      pillar: p,
      score,
      radius01: Math.max(0, Math.min(100, radius)) / 100,
      weak,
      label: t(axisLabelKey(id)),
      interpretation:
        lang === "es"
          ? p?.interpretation_es
          : p?.interpretation_en,
    };
  });

  const poly = polygonPath(
    axes.map((a) => a.radius01),
    CX,
    CY,
    MAX_R,
  );

  const hoverAxis = hover ? axes.find((a) => a.id === hover) : null;

  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6 }}>
      <svg
        width={SIZE}
        height={SIZE}
        viewBox={`0 0 ${SIZE} ${SIZE}`}
        role="img"
        aria-label={t("regime.radar.title")}
        style={{ display: "block" }}
      >
        {/* rings */}
        {RINGS.map((r) => (
          <polygon
            key={r}
            fill="none"
            stroke="rgba(148,163,184,0.22)"
            strokeWidth={1}
            points={RADAR_AXIS_ORDER.map((_, i) => {
              const p = polarPoint(i, RADAR_AXIS_ORDER.length, r, CX, CY, MAX_R);
              return `${p.x},${p.y}`;
            }).join(" ")}
          />
        ))}

        {/* axis lines + labels */}
        {axes.map((a, i) => {
          const edge = polarPoint(i, axes.length, 1, CX, CY, MAX_R);
          const labelPt = polarPoint(i, axes.length, 1.18, CX, CY, MAX_R);
          return (
            <g key={a.id}>
              <line
                x1={CX}
                y1={CY}
                x2={edge.x}
                y2={edge.y}
                stroke={a.weak ? "rgba(148,163,184,0.25)" : "rgba(148,163,184,0.35)"}
                strokeWidth={1}
                strokeDasharray={a.weak ? "3 3" : undefined}
              />
              <text
                x={labelPt.x}
                y={labelPt.y}
                textAnchor="middle"
                dominantBaseline="middle"
                fill="var(--text-4)"
                fontSize={9}
                fontWeight={600}
                style={{ pointerEvents: "none", userSelect: "none" }}
              >
                {a.label}
              </text>
            </g>
          );
        })}

        {/* data polygon */}
        <path
          d={poly}
          fill={color}
          fillOpacity={0.22}
          stroke={color}
          strokeWidth={2}
          strokeLinejoin="round"
        />

        {/* vertices */}
        {axes.map((a, i) => {
          const p = polarPoint(i, axes.length, a.radius01, CX, CY, MAX_R);
          const active = hover === a.id;
          return (
            <circle
              key={`v-${a.id}`}
              cx={p.x}
              cy={p.y}
              r={active ? 5.5 : 4}
              fill={color}
              stroke="#0f172a"
              strokeWidth={1}
              opacity={a.weak ? 0.45 : 1}
              style={{ cursor: "pointer" }}
              onMouseEnter={() => setHover(a.id)}
              onMouseLeave={() => setHover(null)}
            />
          );
        })}
      </svg>

      <div style={{ fontSize: 10, color: "var(--text-5)", textAlign: "center" }}>
        {t("regime.radar.legend")}
      </div>

      {hoverAxis && (
        <div style={{
          maxWidth: SIZE + 20,
          fontSize: 11,
          color: "var(--text-2)",
          lineHeight: 1.45,
          textAlign: "center",
          minHeight: 48,
        }}>
          <strong style={{ color }}>
            {hoverAxis.label}
            {" · "}
            {hoverAxis.score > 0 ? "+" : ""}
            {hoverAxis.score}
            {" · r"}
            {Math.round(hoverAxis.radius01 * 100)}
          </strong>
          {hoverAxis.interpretation && (
            <div style={{ color: "var(--text-3)", marginTop: 2 }}>
              {hoverAxis.interpretation}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
