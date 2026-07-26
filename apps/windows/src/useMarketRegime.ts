/**
 * Shared MarketRegime fetch + 120s poll for banner and advisor.
 * Module-level cache avoids duplicate Tauri invokes when both mount.
 */

import { useEffect, useState } from "react";
import { api, type MarketRegime } from "./api";

const POLL_MS = 120_000;

let cached: MarketRegime | null = null;
let inflight: Promise<MarketRegime> | null = null;
const listeners = new Set<(r: MarketRegime | null) => void>();
let intervalId: ReturnType<typeof setInterval> | null = null;
let subscriberCount = 0;

function notify(r: MarketRegime | null) {
  cached = r;
  for (const l of listeners) l(r);
}

async function load(): Promise<void> {
  if (inflight) {
    try {
      await inflight;
    } catch {
      /* already notified */
    }
    return;
  }
  inflight = api.getMarketRegime();
  try {
    const r = await inflight;
    notify(r);
  } catch (e) {
    console.error(e);
  } finally {
    inflight = null;
  }
}

function ensurePolling() {
  if (intervalId != null) return;
  void load();
  intervalId = setInterval(() => {
    void load();
  }, POLL_MS);
}

function stopPollingIfIdle() {
  if (subscriberCount > 0) return;
  if (intervalId != null) {
    clearInterval(intervalId);
    intervalId = null;
  }
}

/** Latest cached regime (may be null before first success). */
export function getCachedMarketRegime(): MarketRegime | null {
  return cached;
}

/** React hook: shared regime snapshot, polled every 2 minutes. */
export function useMarketRegime(): MarketRegime | null {
  const [regime, setRegime] = useState<MarketRegime | null>(() => cached);

  useEffect(() => {
    subscriberCount += 1;
    listeners.add(setRegime);
    ensurePolling();
    // Sync if another subscriber already filled the cache
    if (cached) setRegime(cached);

    return () => {
      listeners.delete(setRegime);
      subscriberCount -= 1;
      stopPollingIfIdle();
    };
  }, []);

  return regime;
}
