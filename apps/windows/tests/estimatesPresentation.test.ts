import assert from "node:assert/strict";
import test from "node:test";
import type { ScenarioEstimate } from "../src/api.ts";
import { analystPriceGaps } from "../src/estimatesPresentation.ts";

function scenario(scenario: string, coverage: number, gap: number): ScenarioEstimate {
  return { scenario, weighted_price_cents: 12_000, coverage_count: coverage, implied_upside_bps: gap };
}

test("shows both covered analyst endpoints with their own coverage", () => {
  const report = [
    scenario("base_dcf", 40, 9_999),
    scenario("analyst_high", 2, 800),
    scenario("analyst_low", 4, -125),
  ];
  assert.deepEqual(analystPriceGaps(report), {
    low: { priceGapBps: -125, coverageCount: 4 },
    high: { priceGapBps: 800, coverageCount: 2 },
  });
});

test("retains a covered zero and withholds the uncovered endpoint", () => {
  assert.deepEqual(analystPriceGaps([
    scenario("analyst_low", 1, 0),
    scenario("analyst_high", 0, 0),
  ]), {
    low: { priceGapBps: 0, coverageCount: 1 },
    high: null,
  });
});

test("withholds both endpoints when analyst coverage is missing or invalid", () => {
  assert.deepEqual(analystPriceGaps([
    scenario("base_dcf", 7, 0),
    scenario("analyst_low", 0, 0),
    scenario("analyst_high", 3, NaN),
  ]), { low: null, high: null });
  assert.deepEqual(analystPriceGaps([]), { low: null, high: null });
});
