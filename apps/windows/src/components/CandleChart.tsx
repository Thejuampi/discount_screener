import { useEffect, useRef, useState } from "react";
import {
  createChart,
  CandlestickSeries,
  HistogramSeries,
  LineSeries,
  ColorType,
} from "lightweight-charts";
import type { IChartApi } from "lightweight-charts";
import { api } from "../api";
import type { Candle, ChartPattern, FibAnalysis } from "../api";
import { drawPatternLines, drawFibLevels } from "../chartPatternDraw";

interface Props {
  symbol: string;
  range: string;
  patterns?: ChartPattern[];
  fib?: FibAnalysis | null;
  ema?: boolean;
  volume?: boolean;
}

function calcEMA(data: number[], period: number): number[] {
  const k = 2 / (period + 1);
  const result: number[] = [];
  let ema = data[0];
  for (let i = 0; i < data.length; i++) {
    if (i === 0) { result.push(data[0]); continue; }
    ema = data[i] * k + ema * (1 - k);
    result.push(ema);
  }
  return result;
}

// RSI and MACD reserved for Phase 2 technical analysis panel
export function calcRSI(closes: number[], period = 14): number[] {
  const result: number[] = new Array(period).fill(NaN);
  let avgGain = 0, avgLoss = 0;
  for (let i = 1; i <= period; i++) {
    const diff = closes[i] - closes[i - 1];
    if (diff > 0) avgGain += diff; else avgLoss -= diff;
  }
  avgGain /= period; avgLoss /= period;
  result.push(avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss));
  for (let i = period + 1; i < closes.length; i++) {
    const diff = closes[i] - closes[i - 1];
    const gain = diff > 0 ? diff : 0;
    const loss = diff < 0 ? -diff : 0;
    avgGain = (avgGain * (period - 1) + gain) / period;
    avgLoss = (avgLoss * (period - 1) + loss) / period;
    result.push(avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss));
  }
  return result;
}

export function calcMACD(closes: number[]) {
  const ema12 = calcEMA(closes, 12);
  const ema26 = calcEMA(closes, 26);
  const macd = ema12.map((v, i) => v - ema26[i]);
  const signal = calcEMA(macd.slice(26), 9);
  const hist = macd.slice(26 + 8).map((v, i) => v - signal[i + 8]);
  return { macd: macd.slice(26), signal: signal.slice(8), hist };
}

export function CandleChart({ symbol, range, patterns, fib, ema = true, volume = true }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;

    // Cancellation flag: if this effect re-runs (symbol/range changed) before
    // the async fetch resolves, we must NOT create a chart from the stale data,
    // otherwise multiple charts pile up in the DOM.
    let cancelled = false;

    // Cleanup previous chart synchronously
    if (chartRef.current) {
      chartRef.current.remove();
      chartRef.current = null;
    }
    // Belt-and-suspenders: clear any orphaned chart canvases from the container
    // (in case prior fetches resolved out of order before this effect ran)
    if (containerRef.current) {
      containerRef.current.innerHTML = "";
    }

    setLoading(true);
    setError(null);

    api.getCandles(symbol, range).then((candles: Candle[]) => {
      if (cancelled) return;   // ← stale fetch, drop it
      if (!containerRef.current || candles.length === 0) {
        setLoading(false);
        setError("No candle data available");
        return;
      }

      const h = containerRef.current.clientHeight || 400;

      const chart = createChart(containerRef.current, {
        width: containerRef.current.clientWidth,
        height: h,
        layout: {
          background: { type: ColorType.Solid, color: "#0f172a" },
          textColor: "#94a3b8",
        },
        grid: {
          vertLines: { color: "#1e293b" },
          horzLines: { color: "#1e293b" },
        },
        crosshair: { mode: 1 },
        rightPriceScale: { borderColor: "#334155" },
        timeScale: { borderColor: "#334155", timeVisible: true },
      });
      chartRef.current = chart;

      // Candlestick series
      const candleSeries = chart.addSeries(CandlestickSeries, {
        upColor: "#22c55e",
        downColor: "#ef4444",
        borderUpColor: "#22c55e",
        borderDownColor: "#ef4444",
        wickUpColor: "#22c55e",
        wickDownColor: "#ef4444",
      });

      const closes = candles.map((c) => c.close_cents / 100);
      const times = candles.map((c) => c.epoch_seconds as any);

      candleSeries.setData(
        candles.map((c) => ({
          time: c.epoch_seconds as any,
          open: c.open_cents / 100,
          high: c.high_cents / 100,
          low: c.low_cents / 100,
          close: c.close_cents / 100,
        }))
      );

      // EMA 20 + 50
      if (ema) {
      const ema20 = calcEMA(closes, 20);
      const emaSeries = chart.addSeries(LineSeries, {
        color: "#f59e0b",
        lineWidth: 1,
        title: "EMA20",
        priceLineVisible: false,
      });
      emaSeries.setData(
        times.slice(19).map((t: any, i: number) => ({ time: t, value: ema20[i + 19] }))
      );

      if (closes.length > 50) {
        const ema50 = calcEMA(closes, 50);
        const ema50Series = chart.addSeries(LineSeries, {
          color: "#818cf8",
          lineWidth: 1,
          title: "EMA50",
          priceLineVisible: false,
        });
        ema50Series.setData(
          times.slice(49).map((t: any, i: number) => ({ time: t, value: ema50[i + 49] }))
        );
      }
      }

      // Volume
      if (volume) {
      chart.addPane();
      const volSeries = chart.addSeries(HistogramSeries, {
        color: "#334155",
        priceFormat: { type: "volume" },
        priceScaleId: "",
      }, 1);
      volSeries.priceScale().applyOptions({ scaleMargins: { top: 0.1, bottom: 0 } });
      volSeries.setData(
        candles.map((c) => ({
          time: c.epoch_seconds as any,
          value: c.volume,
          color: c.close_cents >= c.open_cents ? "#16a34a55" : "#dc262655",
        }))
      );
      }

      // Chart patterns (detected on daily candles) — overlay only on
      // daily-interval ranges so the line timestamps align with bars.
      if (patterns && patterns.length > 0 && (range === "1mo" || range === "3mo") && candles.length > 0) {
        drawPatternLines(chart, patterns, candles[0].epoch_seconds, candles[candles.length - 1].epoch_seconds);
      }
      // Fibonacci levels + swing leg (forces scale to include extensions).
      if (fib && candles.length > 0) {
        drawFibLevels(chart, candleSeries, fib, candles[0].epoch_seconds, candles[candles.length - 1].epoch_seconds);
      }

      chart.timeScale().fitContent();
      setLoading(false);

      // Resize observer
      const ro = new ResizeObserver(() => {
        if (containerRef.current) {
          chart.applyOptions({ width: containerRef.current.clientWidth });
        }
      });
      ro.observe(containerRef.current);
      return () => ro.disconnect();
    }).catch((e: any) => {
      if (cancelled) return;
      setLoading(false);
      setError(String(e));
    });

    return () => {
      cancelled = true;
      if (chartRef.current) {
        chartRef.current.remove();
        chartRef.current = null;
      }
      if (containerRef.current) {
        containerRef.current.innerHTML = "";
      }
    };
  }, [symbol, range, patterns, fib, ema, volume]);

  return (
    <div className="chart-container">
      {loading && <div className="chart-loading">Loading chart…</div>}
      {error && <div className="chart-error">{error}</div>}
      <div ref={containerRef} className="chart-inner" style={{ height: "470px" }} />
    </div>
  );
}
