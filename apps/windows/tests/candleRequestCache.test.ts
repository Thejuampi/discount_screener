import assert from "node:assert/strict";
import test from "node:test";

import { CandleRequestCache } from "../src/candleRequestCache.ts";

test("chart presentation changes reuse one candle request", async () => {
  let calls = 0;
  let release!: (candles: number[]) => void;
  const pending = new Promise<number[]>((resolve) => { release = resolve; });
  const cache = new CandleRequestCache<number[]>({ ttlMs: 60_000 });
  const fetch = () => {
    calls += 1;
    return pending;
  };

  const first = cache.load("AAPL", "3mo", fetch);
  const second = cache.load("AAPL", "3mo", fetch);
  assert.strictEqual(second, first);
  assert.equal(calls, 1);

  release([1, 2, 3]);
  assert.deepEqual(await first, [1, 2, 3]);
  assert.deepEqual(await cache.load("AAPL", "3mo", fetch), [1, 2, 3]);
  assert.equal(calls, 1);
});

test("symbol and range changes request their own candles", async () => {
  const keys: string[] = [];
  const cache = new CandleRequestCache<string>();
  for (const [symbol, range] of [["AAPL", "3mo"], ["AAPL", "1y"], ["MSFT", "3mo"]]) {
    await cache.load(symbol, range, async () => {
      keys.push(`${symbol}:${range}`);
      return `${symbol}:${range}`;
    });
  }
  assert.deepEqual(keys, ["AAPL:3mo", "AAPL:1y", "MSFT:3mo"]);
});

test("expired candles and failed requests can reload", async () => {
  let now = 0;
  let calls = 0;
  const cache = new CandleRequestCache<number>({ ttlMs: 100, now: () => now });
  const fetch = async () => {
    calls += 1;
    if (calls === 1) throw new Error("provider failed");
    return calls;
  };

  await assert.rejects(cache.load("AAPL", "1d", fetch), /provider failed/);
  assert.equal(await cache.load("AAPL", "1d", fetch), 2);
  now = 99;
  assert.equal(await cache.load("AAPL", "1d", fetch), 2);
  now = 101;
  assert.equal(await cache.load("AAPL", "1d", fetch), 3);
});

test("empty candle responses remain retryable", async () => {
  let calls = 0;
  const cache = new CandleRequestCache<number[]>({ cacheValue: (candles) => candles.length > 0 });
  const fetch = async () => {
    calls += 1;
    return calls === 1 ? [] : [1, 2];
  };

  assert.deepEqual(await cache.load("AAPL", "3mo", fetch), []);
  assert.deepEqual(await cache.load("AAPL", "3mo", fetch), [1, 2]);
  assert.equal(calls, 2);
});
