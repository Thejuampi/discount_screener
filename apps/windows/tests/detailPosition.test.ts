import assert from "node:assert/strict";
import test from "node:test";
import type { PortfolioPosition } from "../src/api.ts";
import { projectDetailPosition } from "../src/detailPosition.ts";

function lot(id: number, symbol: string, quantity: number, cost: number): PortfolioPosition {
  return { id, symbol, quantity, avg_cost_cents: cost, opened_at: null, notes: null };
}

test("keeps matching lots separate and uses the whole book for each weight", () => {
  const position = projectDetailPosition(
    " brk.b ",
    [lot(1, "BRK.B", 1.25, 10_000), lot(2, "brk.b", 2, 13_000), lot(3, "BRK-B", 1, 100)],
    [{ symbol: "BRK.B", market_price_cents: 12_000 }, { symbol: "BRK-B", market_price_cents: 11_000 }],
    null,
  );

  assert.ok(position);
  assert.equal(position.weightAvailable, true);
  assert.deepEqual(position.lots.map((item) => item.id), [1, 2]);
  assert.deepEqual(position.lots.map((item) => item.pnlCents), [2_500, -2_000]);
  assert.deepEqual(position.lots.map((item) => item.weightPct), [30, 48]);
});

test("uses a session Detail quote when its row is missing and withholds incomplete book weights", () => {
  const position = projectDetailPosition(
    "AAPL",
    [lot(1, "AAPL", 2, 20_000), lot(2, "MSFT", 1, 22_000)],
    [{ symbol: "AAPL", market_price_cents: 0 }],
    { detail_source: "session", market_price_cents: 21_000 },
  );

  assert.ok(position);
  assert.equal(position.lots[0].priceCents, 21_000);
  assert.equal(position.lots[0].pnlCents, 2_000);
  assert.equal(position.lots[0].pnlPct, 5);
  assert.equal(position.lots[0].weightPct, null);
  assert.equal(position.weightAvailable, false);
  assert.equal(position.quoteCoverage.eligibleLots, 1);
  assert.equal(position.quoteCoverage.totalLots, 2);
});

test("a restored quote leaves session exposure unavailable and preserves saved lot facts", () => {
  const position = projectDetailPosition(
    "AAPL",
    [lot(1, "AAPL", 3, 20_000)],
    [],
    { detail_source: "restored", market_price_cents: 50_000 },
  );

  assert.ok(position);
  assert.equal(position.lots[0].quantity, 3);
  assert.equal(position.lots[0].unitCostCents, 20_000);
  assert.equal(position.lots[0].priceCents, null);
  assert.equal(position.lots[0].pnlCents, null);
  assert.equal(position.lots[0].weightPct, null);
  assert.equal(position.quoteCoverage.kind, "unavailable");
});

test("returns no position for a ticker absent from the book", () => {
  assert.equal(projectDetailPosition("AAPL", [lot(1, "MSFT", 1, 20_000)], [], null), null);
});

test("a session row prices its lot even while Detail still shows a restored snapshot", () => {
  const position = projectDetailPosition(
    "AAPL",
    [lot(1, "AAPL", 2, 20_000)],
    [{ symbol: "AAPL", market_price_cents: 21_000 }],
    { detail_source: "restored", market_price_cents: 50_000 },
  );

  assert.ok(position);
  assert.equal(position.lots[0].priceCents, 21_000);
  assert.equal(position.lots[0].pnlCents, 2_000);
  assert.equal(position.lots[0].weightPct, 100);
});
