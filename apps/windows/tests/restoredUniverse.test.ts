import assert from "node:assert/strict";
import test from "node:test";
import type { SavedUniverseRow } from "../src/api.ts";
import { visibleRestoredUniverseRows } from "../src/restoredUniverse.ts";

const saved: SavedUniverseRow[] = [
  { symbol: "AAPL", company_name: "Apple Inc.", market_price_cents: 19_500, target_price_cents: 22_000,
    asset_type: "stock", saved_at_epoch_seconds: 1_770_681_600 },
  { symbol: "MSFT", company_name: "Microsoft", market_price_cents: 28_000, target_price_cents: null,
    asset_type: "stock", saved_at_epoch_seconds: 1_770_681_600 },
  { symbol: "SPY", company_name: null, market_price_cents: 55_000, target_price_cents: null,
    asset_type: "etf", saved_at_epoch_seconds: 1_770_681_600 },
];

test("each usable session quote replaces its saved row without affecting the others", () => {
  const session = [
    { symbol: "AAPL", market_price_cents: 20_000 },
    { symbol: "MSFT", market_price_cents: 0 },
  ];
  assert.deepEqual(visibleRestoredUniverseRows(saved, session, "", "all"), [saved[1], saved[2]]);
});

test("restored rows follow text and asset filters", () => {
  assert.deepEqual(visibleRestoredUniverseRows(saved, [], "  apple ", "stock"), [saved[0]]);
  assert.deepEqual(visibleRestoredUniverseRows(saved, [], "apple", "etf"), []);
  assert.deepEqual(visibleRestoredUniverseRows(saved, [], "spy", "all"), [saved[2]]);
});
