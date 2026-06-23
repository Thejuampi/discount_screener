import { LineSeries, LineStyle } from "lightweight-charts";
import type { IChartApi, ISeriesApi, SeriesType, Time } from "lightweight-charts";
import type { ChartPattern, FibAnalysis } from "./api";

/** Draw Fibonacci levels (horizontal price lines) + the swing leg they were
 *  traced from, and force the price scale to include the extension levels. */
export function drawFibLevels<T extends SeriesType>(
  chart: IChartApi, series: ISeriesApi<T>, fib: FibAnalysis, minEpoch: number, maxEpoch: number,
) {
  for (const lv of fib.levels) {
    const golden = Math.abs(lv.ratio - 0.618) < 0.001;
    const anchor = lv.kind === "anchor";
    const ext = lv.kind === "extension";
    series.createPriceLine({
      price: lv.price_cents / 100,
      color: golden ? "#eab308" : anchor ? "#94a3b8" : ext ? "#a855f7" : "#f59e0baa",
      lineWidth: golden || ext ? 2 : 1,
      lineStyle: ext ? LineStyle.Dashed : LineStyle.Dotted,
      axisLabelVisible: true,
      title: `${(lv.ratio * 100).toFixed(lv.ratio % 1 === 0 ? 0 : 1)}%`,
    });
  }

  // Swing leg the fib is traced from (low → high in time order) — the "from/to".
  const a = { epoch: fib.swing_low_epoch, price: fib.swing_low_cents };
  const b = { epoch: fib.swing_high_epoch, price: fib.swing_high_cents };
  if (a.epoch >= minEpoch && a.epoch <= maxEpoch && b.epoch >= minEpoch && b.epoch <= maxEpoch) {
    const pts = [a, b].sort((x, y) => x.epoch - y.epoch)
      .map((p) => ({ time: p.epoch as Time, value: p.price / 100 }));
    const swing = chart.addSeries(LineSeries, {
      color: "#e5e7eb", lineWidth: 2, lineStyle: LineStyle.Solid,
      priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
    });
    swing.setData(pts);
  }

  // Force the price scale to span all fib levels (so extensions aren't off-screen).
  const prices = fib.levels.map((l) => l.price_cents / 100);
  const lo = Math.min(...prices), hi = Math.max(...prices);
  const scaler = chart.addSeries(LineSeries, {
    color: "rgba(0,0,0,0)", lineWidth: 1,
    priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
  });
  scaler.setData([
    { time: minEpoch as Time, value: lo },
    { time: maxEpoch as Time, value: hi },
  ]);
}

const DIR_COLOR: Record<string, string> = {
  Bullish: "#22c55e", Bearish: "#f43f5e", Neutral: "#94a3b8",
};

/**
 * Draw a pattern's trendlines / necklines onto a lightweight-charts chart as
 * thin dashed line series. Points outside [minEpoch, maxEpoch] are dropped so we
 * never extend the chart's time scale; a line needs ≥2 in-range points to draw.
 */
export function drawPatternLines(
  chart: IChartApi, patterns: ChartPattern[], minEpoch: number, maxEpoch: number,
) {
  for (const p of patterns) {
    const color = DIR_COLOR[p.direction] ?? "#94a3b8";
    for (const line of p.lines) {
      const pts = line.points
        .filter((pt) => pt.epoch >= minEpoch && pt.epoch <= maxEpoch)
        .map((pt) => ({ time: pt.epoch as Time, value: pt.price_cents / 100 }))
        .sort((a, b) => (a.time as number) - (b.time as number));
      if (pts.length < 2) continue;
      const s = chart.addSeries(LineSeries, {
        color, lineWidth: 2, lineStyle: LineStyle.Dashed,
        priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
      });
      s.setData(pts);
    }
  }
}
