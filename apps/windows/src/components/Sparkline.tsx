interface Props {
  data: number[];
  width?: number;
  height?: number;
}

/** Tiny inline price sparkline — green when the series rose, red when it fell. */
export function Sparkline({ data, width = 64, height = 22 }: Props) {
  if (!data || data.length < 2) return <span style={{ color: "var(--text-5)" }}>—</span>;
  const min = Math.min(...data);
  const max = Math.max(...data);
  const range = max - min || 1;
  const dx = width / (data.length - 1);
  const pts = data.map((v, i) => `${(i * dx).toFixed(1)},${(height - ((v - min) / range) * height).toFixed(1)}`);
  const up = data[data.length - 1] >= data[0];
  const color = up ? "var(--success)" : "var(--danger)";
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} style={{ display: "block" }} aria-hidden="true">
      <polyline points={pts.join(" ")} fill="none" stroke={color} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx={pts[pts.length - 1].split(",")[0]} cy={pts[pts.length - 1].split(",")[1]} r="1.6" fill={color} />
    </svg>
  );
}
