import assert from "node:assert/strict";
import test from "node:test";

import type { Candle, DetailCandleSeries } from "../src/api.ts";
import { readDetailCandleSeries } from "../src/detailCandleSeries.ts";

const candle: Candle = {
  epoch_seconds: 1_700_000_000,
  open_cents: 1_000,
  high_cents: 1_100,
  low_cents: 950,
  close_cents: 1_075,
  volume: 500,
};
const restored: DetailCandleSeries = {
  candles: [candle],
  source: "restored",
  saved_at_epoch_seconds: 1_700_090_000,
};

test("a passive chart read returns the saved series with its provenance", async () => {
  assert.strictEqual(await readDetailCandleSeries(async () => restored), restored);
});

test("a passive cache miss returns no candles", async () => {
  assert.equal(await readDetailCandleSeries(async () => null), null);
});

test("an explicit range request uses saved candles on a cache hit", async () => {
  let providerCalls = 0;
  const result = await readDetailCandleSeries(async () => restored, async () => {
    providerCalls += 1;
    return [candle];
  });
  assert.strictEqual(result, restored);
  assert.equal(providerCalls, 0);
});

test("an explicit range miss returns provider candles as session data", async () => {
  const calls: string[] = [];
  const result = await readDetailCandleSeries(async () => {
    calls.push("cache");
    return null;
  }, async () => {
    calls.push("provider");
    return [candle];
  });
  assert.deepEqual(calls, ["cache", "provider"]);
  assert.deepEqual(result, { candles: [candle], source: "session", saved_at_epoch_seconds: null });
});

test("an empty provider response does not create a session series", async () => {
  assert.equal(await readDetailCandleSeries(async () => null, async () => []), null);
});

test("a provider failure remains visible to the chart", async () => {
  await assert.rejects(readDetailCandleSeries(async () => null, async () => {
    throw new Error("provider unavailable");
  }), /provider unavailable/);
});
