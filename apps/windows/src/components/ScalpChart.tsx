import { useEffect, useRef, useState } from "react";
import {
  createChart, CandlestickSeries, LineSeries, HistogramSeries,
  createSeriesMarkers, ColorType, LineStyle,
} from "lightweight-charts";
import type { IChartApi, SeriesMarker, Time } from "lightweight-charts";
import { api } from "../api";
import type { Candle, ScalpSignal, SmcAnalysis, ChartPattern, FibAnalysis } from "../api";
import { drawPatternLines, drawFibLevels } from "../chartPatternDraw";

export interface ChartOverlays { signal: boolean; smc: boolean; patterns: boolean; fib: boolean; ema: boolean; volume: boolean }

interface Props {
  product: string;
  timeframe: string;
  signal: ScalpSignal | null;
  smc?: SmcAnalysis | null;
  patterns?: ChartPattern[];
  fib?: FibAnalysis | null;
  show?: ChartOverlays;
}

const ALL_ON: ChartOverlays = { signal: true, smc: true, patterns: true, fib: true, ema: true, volume: true };

function ema(data: number[], period: number): number[] {
  const k = 2 / (period + 1);
  const out: number[] = [];
  let e = data[0];
  for (let i = 0; i < data.length; i++) {
    e = i === 0 ? data[0] : data[i] * k + e * (1 - k);
    out.push(e);
  }
  return out;
}

/** Scalping candle chart with the active signal drawn in place: a buy/sell
 *  marker on the latest bar plus entry / stop / take-profit price lines. */
export function ScalpChart({ product, timeframe, signal, smc, patterns, fib, show = ALL_ON }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;
    let cancelled = false;
    if (chartRef.current) { chartRef.current.remove(); chartRef.current = null; }
    containerRef.current.innerHTML = "";
    setLoading(true); setError(null);

    api.getScalpCandles(product, timeframe).then((candles: Candle[]) => {
      if (cancelled || !containerRef.current) return;
      if (candles.length === 0) { setLoading(false); setError("Sin datos"); return; }
      setLoading(false);

      const chart = createChart(containerRef.current, {
        width: containerRef.current.clientWidth,
        height: containerRef.current.clientHeight || 520,
        layout: { background: { type: ColorType.Solid, color: "#0f172a" }, textColor: "#94a3b8" },
        grid: { vertLines: { color: "#1e293b" }, horzLines: { color: "#1e293b" } },
        crosshair: { mode: 1 },
        rightPriceScale: { borderColor: "#334155" },
        timeScale: { borderColor: "#334155", timeVisible: true, secondsVisible: false },
      });
      chartRef.current = chart;

      const candleSeries = chart.addSeries(CandlestickSeries, {
        upColor: "#22c55e", downColor: "#ef4444",
        borderUpColor: "#22c55e", borderDownColor: "#ef4444",
        wickUpColor: "#22c55e", wickDownColor: "#ef4444",
      });
      candleSeries.setData(candles.map((c) => ({
        time: c.epoch_seconds as never,
        open: c.open_cents / 100, high: c.high_cents / 100,
        low: c.low_cents / 100, close: c.close_cents / 100,
      })));

      const closes = candles.map((c) => c.close_cents / 100);
      const times = candles.map((c) => c.epoch_seconds as never);
      const addEma = (period: number, color: string) => {
        if (closes.length <= period) return;
        const e = ema(closes, period);
        const s = chart.addSeries(LineSeries, { color, lineWidth: 1, title: `EMA${period}`, priceLineVisible: false });
        s.setData(times.slice(period - 1).map((t, i) => ({ time: t, value: e[i + period - 1] })));
      };
      if (show.ema) {
        addEma(9, "#f59e0b");
        addEma(21, "#818cf8");
      }

      if (show.volume) {
        chart.addPane();
        const vol = chart.addSeries(HistogramSeries, { priceFormat: { type: "volume" }, priceScaleId: "" }, 1);
        vol.priceScale().applyOptions({ scaleMargins: { top: 0.1, bottom: 0 } });
        vol.setData(candles.map((c) => ({
          time: c.epoch_seconds as never, value: c.volume,
          color: c.close_cents >= c.open_cents ? "#16a34a55" : "#dc262655",
        })));
      }

      const line = (price: number, color: string, title: string, width = 1) =>
        candleSeries.createPriceLine({ price: price / 100, color, lineWidth: width as never, lineStyle: LineStyle.Dashed, axisLabelVisible: true, title });

      // ── Signal drawn in place ──
      const markers: SeriesMarker<Time>[] = [];
      if (show.signal && signal && signal.side !== "NONE") {
        const long = signal.side === "LONG";
        const lastTime = candles[candles.length - 1].epoch_seconds as Time;
        markers.push({
          time: lastTime,
          position: long ? "belowBar" : "aboveBar",
          color: long ? "#22c55e" : "#ef4444",
          shape: long ? "arrowUp" : "arrowDown",
          text: signal.side,
        });
        const entry = (signal.entry_low_cents + signal.entry_high_cents) / 2;
        line(entry, "#3b82f6", "Entry");
        line(signal.stop_cents, "#ef4444", "SL");
        line(signal.tp1_cents, "#22c55e", "TP1");
        if (signal.tp2_cents > 0) line(signal.tp2_cents, "#16a34a", "TP2");
      }

      // ── SMC overlay ──
      if (show.smc && smc) {
        // Volume profile levels (horizontal, valid on any timeframe).
        if (smc.volume_profile) {
          line(smc.volume_profile.poc_cents, "#eab308", "POC");
          line(smc.volume_profile.vah_cents, "#64748b", "VAH");
          line(smc.volume_profile.val_cents, "#64748b", "VAL");
        }
        // Structure events: only when the chart matches the SMC timeframe so
        // marker timestamps land on real bars.
        if (smc.tf === timeframe) {
          for (const e of smc.events) {
            const bull = e.direction === "Bullish";
            markers.push({
              time: e.at_epoch as Time,
              position: bull ? "belowBar" : "aboveBar",
              color: e.kind === "CHoCH" ? "#f59e0b" : e.kind === "LiquiditySweep" ? "#a855f7" : (bull ? "#22c55e" : "#ef4444"),
              shape: bull ? "arrowUp" : "arrowDown",
              text: e.kind,
            });
          }
          // Most recent FVG / order block boundaries.
          for (const z of [...smc.fvgs.slice(0, 1), ...smc.order_blocks.slice(0, 1)]) {
            const col = z.kind.startsWith("Bull") ? "#22c55e55" : "#ef444455";
            line(z.high_cents, col, z.kind);
            line(z.low_cents, col, "");
          }
        }
      }

      if (markers.length > 0) {
        markers.sort((a, b) => (a.time as number) - (b.time as number));
        createSeriesMarkers(candleSeries, markers);
      }

      // Chart patterns (detected on 15m) — overlay on the intraday timeframes
      // whose bars align with 15m boundaries.
      if (show.patterns && patterns && patterns.length > 0 && ["1m", "3m", "5m", "15m"].includes(timeframe)) {
        drawPatternLines(chart, patterns, candles[0].epoch_seconds, candles[candles.length - 1].epoch_seconds);
      }

      // Fibonacci levels + swing leg (forces scale to include extensions).
      if (show.fib && fib) {
        drawFibLevels(chart, candleSeries, fib, candles[0].epoch_seconds, candles[candles.length - 1].epoch_seconds);
      }

      chart.timeScale().fitContent();
      const onResize = () => containerRef.current && chart.applyOptions({ width: containerRef.current.clientWidth });
      window.addEventListener("resize", onResize);
      (chart as unknown as { _onResize?: () => void })._onResize = onResize;
    }).catch((e) => { if (!cancelled) { setLoading(false); setError(String(e)); } });

    return () => {
      cancelled = true;
      if (chartRef.current) {
        const r = (chartRef.current as unknown as { _onResize?: () => void })._onResize;
        if (r) window.removeEventListener("resize", r);
        chartRef.current.remove();
        chartRef.current = null;
      }
    };
  }, [product, timeframe, signal, smc, patterns, fib, show]);

  return (
    <div style={{ position: "relative", width: "100%", height: 520 }}>
      <div ref={containerRef} style={{ width: "100%", height: "100%" }} />
      {loading && <div style={{ position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center", color: "var(--text-4)" }}>…</div>}
      {error && <div style={{ position: "absolute", top: 8, left: 8, color: "var(--danger)", fontSize: 12 }}>{error}</div>}
    </div>
  );
}
