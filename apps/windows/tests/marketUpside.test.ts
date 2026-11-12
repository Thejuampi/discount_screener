import assert from "node:assert/strict";
import test from "node:test";
import { marketUpsideBps, projectMarketUpside } from "../src/marketUpside.ts";

function quote(symbol: string, price: number, target: number, company: string | null = null) {
  return { symbol, market_price_cents: price, intrinsic_value_cents: target, company_name: company };
}

test("calculates price-relative basis points with fixed-point truncation", () => {
  assert.equal(marketUpsideBps(10_000, 12_500), 2_500);
  assert.equal(marketUpsideBps(3, 4), 3_333);
  assert.equal(marketUpsideBps(3, 2), -3_333);
  assert.equal(marketUpsideBps(10_000, 10_000), 0);
});

test("withholds upside when price, target, or the result cannot be represented", () => {
  assert.equal(marketUpsideBps(0, 100), null);
  assert.equal(marketUpsideBps(100, 0), null);
  assert.equal(marketUpsideBps(100, null), null);
  assert.equal(marketUpsideBps(100, 50.5), null);
  assert.equal(marketUpsideBps(1, Number.MAX_SAFE_INTEGER), null);
  assert.equal(marketUpsideBps(10_000, Number.MAX_SAFE_INTEGER), Number.MAX_SAFE_INTEGER - 10_000);
});

test("projects only feed members and orders available, negative, and missing values", () => {
  const symbols = ["PENDING", "ZERO", "NEG", "NO-TARGET", "ZZZ", "AAA", "POS"];
  const session = [
    quote("NEG", 100, 80),
    quote("ZERO", 100, 100),
    quote("NO-TARGET", 120, 0),
    quote("POS", 100, 125),
    quote("ZZZ", 100, 110),
    quote("AAA", 100, 110),
    quote("ADHOC", 100, 10_000),
  ];
  const rows = projectMarketUpside(symbols, session, "");
  assert.deepEqual(rows.map((row) => row.symbol), ["POS", "AAA", "ZZZ", "ZERO", "NEG", "NO-TARGET", "PENDING"]);
  assert.deepEqual(rows.map((row) => row.upsideBps), [2_500, 1_000, 1_000, 0, -2_000, null, null]);
  assert.equal(rows.at(-1)?.priceCents, null);
  assert.equal(rows.at(-2)?.targetCents, null);
  assert.equal(symbols[0], "PENDING");
  assert.equal(session[0].symbol, "NEG");
});

test("matches ticker or company searches and keeps unquoted ticker matches", () => {
  const symbols = ["MSFT", "PENDING", "MU"];
  const session = [quote("MSFT", 100, 130, "Microsoft"), quote("MU", 100, 140, "Micron")];
  assert.deepEqual(projectMarketUpside(symbols, session, " micro ").map((row) => row.symbol), ["MU", "MSFT"]);
  assert.deepEqual(projectMarketUpside(symbols, session, "pending").map((row) => row.symbol), ["PENDING"]);
});
