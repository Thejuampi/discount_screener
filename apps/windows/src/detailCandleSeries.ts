import type { Candle, DetailCandleSeries } from "./api";

export async function readDetailCandleSeries(
  read: () => Promise<DetailCandleSeries | null>,
  loadOnMiss?: () => Promise<Candle[] | null>,
): Promise<DetailCandleSeries | null> {
  const cached = await read();
  if (cached?.candles.length) return cached;

  const candles = await loadOnMiss?.();
  return candles?.length ? { candles, source: "session", saved_at_epoch_seconds: null } : null;
}
