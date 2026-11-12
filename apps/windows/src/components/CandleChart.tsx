import { useEffect, useRef, useState } from "react";
import {
  createChart,
  CandlestickSeries,
  HistogramSeries,
  LineSeries,
  ColorType,
} from "lightweight-charts";
import type { IChartApi, UTCTimestamp } from "lightweight-charts";
import { api } from "../api";
import type { ChartPattern, DetailCandleSeries, FibAnalysis } from "../api";
import { drawPatternLines, drawFibLevels } from "../chartPatternDraw";
import { CandleRequestCache } from "../candleRequestCache";
import { observeChartWidth } from "../chartResizeObserver";
import { readDetailCandleSeries } from "../detailCandleSeries";
import { useT } from "../i18n";
import { singleFlight } from "../singleFlight";

interface Props {
  symbol: string;
  range: string;
  cacheRevision: number;
  loadRequest: number;
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

export function CandleChart({ symbol, range, cacheRevision, loadRequest, patterns, fib, ema = true, volume = true }: Props) {
  const { t, lang } = useT();
  const containerRef = useRef<HTMLDivElement>(null);
  const [candleCache] = useState(() => new CandleRequestCache<DetailCandleSeries | null>({
    cacheValue: (series) => series?.source === "session",
  }));
  const [result, setResult] = useState<{ symbol: string; range: string; series: DetailCandleSeries } | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "empty" | "failed">("loading");
  const [drawFailed, setDrawFailed] = useState(false);
  const series = result?.symbol === symbol && result.range === range ? result.series : null;
  const candles = series?.candles;
  const restored = series?.source === "restored";
  const dailyRange = range === "1mo" || range === "3mo";
  const savedAt = restored && series.saved_at_epoch_seconds != null
    ? new Date(series.saved_at_epoch_seconds * 1_000)
    : null;
  const sessionPatterns = restored ? undefined : patterns;
  const sessionFib = restored ? null : fib;

  useEffect(() => {
    let cancelled = false;
    setStatus("loading");
    void candleCache.load(symbol, `${range}:${cacheRevision}:${loadRequest}`, () => readDetailCandleSeries(
      () => api.getCandles(symbol, range),
      async () => loadRequest && !cancelled ? api.loadSymbolCandles(symbol, range) : null,
    )).then((next) => {
      if (cancelled) return;
      if (next) {
        setResult({ symbol, range, series: next });
        setStatus("ready");
      } else {
        setStatus(loadRequest ? "failed" : "empty");
      }
    }).catch(() => {
      if (!cancelled) setStatus("failed");
    });
    return () => { cancelled = true; };
  }, [symbol, range, cacheRevision, loadRequest, candleCache]);

  useEffect(() => {
    if (!restored || !dailyRange || status === "loading") return;

    let cancelled = false;
    const reread = singleFlight(async () => {
      const next = await api.getCandles(symbol, range).catch(() => null);
      if (cancelled || next?.source !== "session" || !next.candles.length) return;

      setResult({ symbol, range, series: next });
      setStatus("ready");
    });
    const timer = window.setInterval(() => void reread(), 5_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [symbol, range, cacheRevision, loadRequest, restored, dailyRange, status]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || !candles) return;

    let startedChart: IChartApi | undefined;
    try {
      const h = container.clientHeight || 400;

      const chart = createChart(container, {
        width: container.clientWidth,
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
      startedChart = chart;

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
      const times = candles.map((c) => c.epoch_seconds as UTCTimestamp);

      candleSeries.setData(
        candles.map((c, i) => ({
          time: times[i],
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
          times.slice(19).map((time, i) => ({ time, value: ema20[i + 19] }))
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
            times.slice(49).map((time, i) => ({ time, value: ema50[i + 49] }))
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
          candles.map((c, i) => ({
            time: times[i],
            value: c.volume,
            color: c.close_cents >= c.open_cents ? "#16a34a55" : "#dc262655",
          }))
        );
      }

      // Chart patterns (detected on daily candles) — overlay only on
      // daily-interval ranges so the line timestamps align with bars.
      if (sessionPatterns && sessionPatterns.length > 0 && dailyRange) {
        drawPatternLines(chart, sessionPatterns, candles[0].epoch_seconds, candles[candles.length - 1].epoch_seconds);
      }
      // Fibonacci levels + swing leg (forces scale to include extensions).
      if (sessionFib) {
        drawFibLevels(chart, candleSeries, sessionFib, candles[0].epoch_seconds, candles[candles.length - 1].epoch_seconds);
      }

      chart.timeScale().fitContent();
      const stopObserving = observeChartWidth(container, () => {
        chart.applyOptions({ width: container.clientWidth });
      });
      setDrawFailed(false);
      return () => {
        stopObserving();
        chart.remove();
      };
    } catch {
      startedChart?.remove();
      setDrawFailed(true);
    }
  }, [candles, dailyRange, sessionPatterns, sessionFib, ema, volume]);

  return (
    <div className={candles ? "chart-container" : "chart-container detail-chart--empty"}>
      {status === "loading" && !candles && <div className="detail-chart-status" role="status">{t("detail.chartLoading")}</div>}
      {status === "empty" && !candles && <div className="detail-chart-status">{t("detail.chartCacheEmpty")}</div>}
      {(status === "failed" || (candles && drawFailed)) && (
        <div className="detail-chart-status" role="status">{t("detail.chartUnavailable")}</div>
      )}
      {restored && (
        <div className="detail-restored-status detail-chart-provenance" data-candle-source="restored" role="status">
          <strong>{t("detail.restoredCandles")}</strong>
          {savedAt && Number.isFinite(savedAt.getTime()) && (
            <time dateTime={savedAt.toISOString()}>
              {t("detail.savedAt", { date: savedAt.toLocaleString(lang, { dateStyle: "medium", timeStyle: "short" }) })}
            </time>
          )}
        </div>
      )}
      {candles && <div ref={containerRef} className="chart-inner" style={{ height: "470px" }} />}
    </div>
  );
}
