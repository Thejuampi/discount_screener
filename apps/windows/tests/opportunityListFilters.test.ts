import assert from "node:assert/strict";
import test from "node:test";
import type { OpportunityRow } from "../src/api.ts";
import {
  applyListFilters,
  countActiveFilters,
  EMPTY_LIST_FILTERS,
  gapBpsFromPct,
  hasActiveFilters,
  listFiltersEqual,
  loadListFiltersFromStorage,
  parseFilterNumber,
  saveListFiltersToStorage,
  type ListFilterState,
} from "../src/opportunityListFilters.ts";

function row(partial: Partial<OpportunityRow> & { symbol: string }): OpportunityRow {
  return {
    company_name: partial.company_name ?? partial.symbol,
    market_price_cents: partial.market_price_cents ?? 10000,
    intrinsic_value_cents: partial.intrinsic_value_cents ?? 12000,
    gap_bps: partial.gap_bps ?? null,
    qualification: partial.qualification ?? "Qualified",
    confidence: partial.confidence ?? "High",
    signal_status: partial.signal_status ?? "Missing",
    analyst_opinion_count: partial.analyst_opinion_count ?? 10,
    recommendation_mean_hundredths: partial.recommendation_mean_hundredths ?? 200,
    sector_name: partial.sector_name ?? "Tech",
    fundamentals_score: partial.fundamentals_score ?? 20,
    technical_score: partial.technical_score ?? 20,
    forecast_score: partial.forecast_score ?? 20,
    composite_score: partial.composite_score ?? 40,
    decision: partial.decision ?? "Watch",
    fundamentals_signals: [],
    technical_signals: [],
    forecast_signals: [],
    dcf_value_cents: partial.dcf_value_cents ?? null,
    insider_net_shares_90d: null,
    insider_buy_count: null,
    insider_sell_count: null,
    asset_type: partial.asset_type ?? "stock",
    setup_score: partial.setup_score ?? 40,
    setup_label: partial.setup_label ?? "Buy",
    daily_change_bps: partial.daily_change_bps ?? 0,
    atr_cents: null,
    next_earnings_epoch: null,
    spark: [],
    ...partial,
  };
}

const sample: OpportunityRow[] = [
  row({ symbol: "NEM", setup_score: 56, gap_bps: 2680, composite_score: 56 }),
  row({ symbol: "DVN", setup_score: 47, gap_bps: 1200, composite_score: 47 }),
  row({ symbol: "FLAT", setup_score: 30, gap_bps: 0, composite_score: 30 }),
  row({ symbol: "RICH", setup_score: 55, gap_bps: -800, composite_score: 55 }),
  row({ symbol: "NOTGT", setup_score: 80, gap_bps: null, composite_score: 80 }),
];

test("parseFilterNumber accepts decimals and comma, rejects junk", () => {
  assert.equal(parseFilterNumber(""), null);
  assert.equal(parseFilterNumber("  "), null);
  assert.equal(parseFilterNumber("15"), 15);
  assert.equal(parseFilterNumber("15.5"), 15.5);
  assert.equal(parseFilterNumber("15,5"), 15.5);
  assert.equal(parseFilterNumber("-10"), -10);
  assert.equal(parseFilterNumber("abc"), null);
  assert.equal(parseFilterNumber("-"), null);
});

test("gap percent converts to bps for comparison", () => {
  assert.equal(gapBpsFromPct(15), 1500);
  assert.equal(gapBpsFromPct(26.8), 2680);
});

test("empty filters pass all rows through", () => {
  var out = applyListFilters(sample, EMPTY_LIST_FILTERS);
  assert.equal(out.length, sample.length);
  assert.equal(hasActiveFilters(EMPTY_LIST_FILTERS), false);
  assert.equal(countActiveFilters(EMPTY_LIST_FILTERS), 0);
});

test("setup filter keeps rows with setup_score ≥ min", () => {
  var filters: ListFilterState = { ...EMPTY_LIST_FILTERS, minSetupScore: 50 };
  var out = applyListFilters(sample, filters);
  assert.deepEqual(out.map((r) => r.symbol), ["NEM", "RICH", "NOTGT"]);
});

test("gap filter keeps rows with gap ≥ pct and drops null targets", () => {
  var filters: ListFilterState = { ...EMPTY_LIST_FILTERS, minGapPct: 15 };
  var out = applyListFilters(sample, filters);
  assert.deepEqual(out.map((r) => r.symbol), ["NEM"]);
});

test("gap filter at 12% includes DVN (12% = 1200 bps)", () => {
  var filters: ListFilterState = { ...EMPTY_LIST_FILTERS, minGapPct: 12 };
  var out = applyListFilters(sample, filters);
  assert.deepEqual(out.map((r) => r.symbol), ["NEM", "DVN"]);
});

test("composite filter works independently", () => {
  var filters: ListFilterState = { ...EMPTY_LIST_FILTERS, minCompositeScore: 50 };
  var out = applyListFilters(sample, filters);
  assert.deepEqual(out.map((r) => r.symbol), ["NEM", "RICH", "NOTGT"]);
});

test("combined setup + gap filters AND together", () => {
  var filters: ListFilterState = {
    minSetupScore: 40,
    minGapPct: 15,
    minCompositeScore: null,
  };
  var out = applyListFilters(sample, filters);
  assert.deepEqual(out.map((r) => r.symbol), ["NEM"]);
  assert.equal(countActiveFilters(filters), 2);
});

test("listFiltersEqual compares thresholds", () => {
  assert.equal(
    listFiltersEqual(
      { minSetupScore: 40, minGapPct: null, minCompositeScore: null },
      { minSetupScore: 40, minGapPct: null, minCompositeScore: null },
    ),
    true,
  );
  assert.equal(
    listFiltersEqual(
      { minSetupScore: 40, minGapPct: null, minCompositeScore: null },
      { minSetupScore: 41, minGapPct: null, minCompositeScore: null },
    ),
    false,
  );
});

test("storage round-trip persists active filters and clears empty", () => {
  var mem = new Map<string, string>();
  var storage = {
    getItem: (k: string) => mem.get(k) ?? null,
    setItem: (k: string, v: string) => { mem.set(k, v); },
    removeItem: (k: string) => { mem.delete(k); },
  };

  var filters: ListFilterState = {
    minSetupScore: 40,
    minGapPct: 15,
    minCompositeScore: null,
  };
  saveListFiltersToStorage(filters, storage);
  assert.deepEqual(loadListFiltersFromStorage(storage), filters);

  saveListFiltersToStorage(EMPTY_LIST_FILTERS, storage);
  assert.deepEqual(loadListFiltersFromStorage(storage), EMPTY_LIST_FILTERS);
  assert.equal(mem.size, 0);
});
