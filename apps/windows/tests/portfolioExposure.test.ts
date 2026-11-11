import assert from "node:assert/strict";
import test from "node:test";
import { projectPortfolioExposure } from "../src/portfolioExposure.ts";

test("each priced lot contributes to value and weight, including lots with the same symbol", () => {
  const exposure = projectPortfolioExposure([
    { symbol: "A", quantity: 2.5, avg_cost_cents: 100 },
    { symbol: "A", quantity: 1, avg_cost_cents: 125 },
    { symbol: "B", quantity: 2, avg_cost_cents: 60 },
  ], new Map([["A", { market_price_cents: 140 }]]), { A: 900, B: 75 });

  assert.equal(exposure.value.cents, 640);
  assert.deepEqual(exposure.value.coverage, { kind: "complete", eligibleLots: 3, totalLots: 3, rangeExcludedLots: 0 });
  assert.equal(exposure.completeValueCents, 640);
  assert.deepEqual(exposure.rows.map((row) => row.priceCents), [140, 140, 75]);
  assert.deepEqual(exposure.rows.map((row) => row.valueCents), [350, 140, 150]);
  assert.deepEqual(exposure.rows.map((row) => row.weightPct), [54.6875, 21.875, 23.4375]);
  assert.equal(exposure.pnl.cents, 145);
  assert.equal(exposure.pnl.pct, (145 / 495) * 100);
});

test("missing quotes make all weights unavailable and preserve the quoted subset", () => {
  const exposure = projectPortfolioExposure([
    { symbol: "A", quantity: 2, avg_cost_cents: 100 },
    { symbol: "B", quantity: 4, avg_cost_cents: 500 },
  ], new Map([["A", { market_price_cents: 110 }], ["B", { market_price_cents: 0 }]]), {});

  assert.equal(exposure.value.cents, 220);
  assert.deepEqual(exposure.value.coverage, { kind: "partial", eligibleLots: 1, totalLots: 2, rangeExcludedLots: 0 });
  assert.equal(exposure.completeValueCents, null);
  assert.deepEqual(exposure.rows.map((row) => row.weightPct), [null, null]);
  assert.equal(exposure.pnl.cents, 20);
  assert.equal(exposure.pnl.pct, 10);
  assert.deepEqual(exposure.pnl.coverage, { kind: "partial", eligibleLots: 1, totalLots: 2, rangeExcludedLots: 0 });
});

test("P/L uses the same subset for price and cost and tracks coverage separately", () => {
  const exposure = projectPortfolioExposure([
    { symbol: "A", quantity: 2, avg_cost_cents: 100 },
    { symbol: "B", quantity: 2, avg_cost_cents: null },
    { symbol: "C", quantity: 2, avg_cost_cents: -50 },
  ], new Map([["A", { market_price_cents: 0 }]]), { A: 110, B: 100, C: 100 });

  assert.equal(exposure.value.cents, 620);
  assert.equal(exposure.value.coverage.kind, "complete");
  assert.equal(exposure.completeValueCents, 620);
  assert.equal(exposure.pnl.cents, 20);
  assert.equal(exposure.pnl.pct, 10);
  assert.deepEqual(exposure.pnl.coverage, { kind: "partial", eligibleLots: 1, totalLots: 3, rangeExcludedLots: 0 });
  assert.deepEqual(exposure.rows.map((row) => row.pnlCents), [20, null, null]);
});

test("zero basis allows dollar P/L but does not produce a percentage", () => {
  const exposure = projectPortfolioExposure([
    { symbol: "A", quantity: 2, avg_cost_cents: 0 },
  ], new Map(), { A: 125 });

  assert.equal(exposure.pnl.cents, 250);
  assert.equal(exposure.pnl.pct, null);
  assert.equal(exposure.pnl.coverage.kind, "complete");
  assert.equal(exposure.rows[0].pnlPct, null);
});

test("a valid zero P/L is distinct from no usable quotes or no lots", () => {
  const lots = [{ symbol: "A", quantity: 1, avg_cost_cents: 100 }];
  const even = projectPortfolioExposure(lots, new Map(), { A: 100 });
  assert.equal(even.pnl.cents, 0);
  assert.equal(even.pnl.pct, 0);

  const missing = projectPortfolioExposure(lots, new Map(), { A: Infinity });
  assert.equal(missing.value.cents, null);
  assert.equal(missing.pnl.cents, null);
  assert.equal(missing.pnl.pct, null);
  assert.deepEqual(missing.value.coverage, { kind: "unavailable", eligibleLots: 0, totalLots: 1, rangeExcludedLots: 0 });
  assert.deepEqual(missing.pnl.coverage, missing.value.coverage);

  const empty = projectPortfolioExposure([], new Map(), {});
  assert.equal(empty.value.cents, null);
  assert.deepEqual(empty.value.coverage, { kind: "unavailable", eligibleLots: 0, totalLots: 0, rangeExcludedLots: 0 });
});

test("a total outside the supported cent range keeps its coverage and suppresses weights", () => {
  const large = Number.MAX_SAFE_INTEGER;
  const exposure = projectPortfolioExposure([
    { symbol: "A", quantity: 1, avg_cost_cents: large },
    { symbol: "B", quantity: 1, avg_cost_cents: large },
  ], new Map(), { A: large, B: large });

  assert.equal(exposure.value.cents, null);
  assert.deepEqual(exposure.value.coverage, { kind: "outOfRange", eligibleLots: 2, totalLots: 2, rangeExcludedLots: 0 });
  assert.equal(exposure.completeValueCents, null);
  assert.deepEqual(exposure.rows.map((row) => row.weightPct), [null, null]);
  assert.equal(exposure.pnl.cents, 0);
  assert.equal(exposure.pnl.pct, 0);
});

test("an individual value overflow keeps the quote but cannot enter either subtotal", () => {
  const exposure = projectPortfolioExposure([
    { symbol: "A", quantity: Number.MAX_SAFE_INTEGER, avg_cost_cents: 1 },
    { symbol: "B", quantity: 1, avg_cost_cents: 100 },
  ], new Map(), { A: 2, B: 125 });

  assert.equal(exposure.rows[0].priceCents, 2);
  assert.equal(exposure.rows[0].valueCents, null);
  assert.equal(exposure.rows[0].valueOutOfRange, true);
  assert.equal(exposure.rows[0].pnlOutOfRange, true);
  assert.equal(exposure.value.cents, 125);
  assert.equal(exposure.value.coverage.eligibleLots, 1);
  assert.equal(exposure.value.coverage.rangeExcludedLots, 1);
  assert.equal(exposure.pnl.cents, 25);
  assert.equal(exposure.pnl.coverage.eligibleLots, 1);
  assert.equal(exposure.pnl.coverage.rangeExcludedLots, 1);
  assert.equal(exposure.completeValueCents, null);
});

test("an individual basis overflow keeps market value and only excludes paired P/L", () => {
  const exposure = projectPortfolioExposure([
    { symbol: "A", quantity: Number.MAX_SAFE_INTEGER, avg_cost_cents: 2 },
  ], new Map(), { A: 1 });

  assert.equal(exposure.rows[0].unitCostCents, 2);
  assert.equal(exposure.rows[0].pnlCents, null);
  assert.equal(exposure.rows[0].pnlOutOfRange, true);
  assert.equal(exposure.value.cents, Number.MAX_SAFE_INTEGER);
  assert.equal(exposure.rows[0].weightPct, 100);
  assert.equal(exposure.pnl.cents, null);
  assert.equal(exposure.pnl.coverage.eligibleLots, 0);
  assert.equal(exposure.pnl.coverage.rangeExcludedLots, 1);
});
