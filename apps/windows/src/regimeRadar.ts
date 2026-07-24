/** Pure helpers for the market-regime spider chart. */

export type RadarAxisId =
  | "trend"
  | "breadth"
  | "volatility"
  | "sentiment"
  | "cross_asset"
  | "quality";

/** Preferred display order around the radar. */
export const RADAR_AXIS_ORDER: RadarAxisId[] = [
  "trend",
  "breadth",
  "volatility",
  "sentiment",
  "cross_asset",
  "quality",
];

/**
 * Map pillar score (−100..+100) to radar radius 0..100.
 * Edge = better. Volatility (stress) is inverted.
 * Sentiment uses contrarian score (fear → outer = opportunity).
 */
export function radarRadius(pillarId: string, score: number): number {
  const s = Math.max(-100, Math.min(100, score));
  if (pillarId === "volatility") {
    return (100 - s) / 2;
  }
  return (s + 100) / 2;
}

/** Polar point for axis index i of n, radius r (0..1), centered at (cx,cy). */
export function polarPoint(
  i: number,
  n: number,
  r: number,
  cx: number,
  cy: number,
  maxR: number,
): { x: number; y: number } {
  // Start at top (−90°)
  const angle = -Math.PI / 2 + (i * 2 * Math.PI) / n;
  return {
    x: cx + maxR * r * Math.cos(angle),
    y: cy + maxR * r * Math.sin(angle),
  };
}

export function polygonPath(
  radii01: number[],
  cx: number,
  cy: number,
  maxR: number,
): string {
  if (radii01.length === 0) return "";
  return radii01
    .map((r, i) => {
      const p = polarPoint(i, radii01.length, r, cx, cy, maxR);
      return `${i === 0 ? "M" : "L"}${p.x.toFixed(2)},${p.y.toFixed(2)}`;
    })
    .join(" ") + " Z";
}
