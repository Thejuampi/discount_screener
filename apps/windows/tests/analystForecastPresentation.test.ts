import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ForecastObservation } from "../src/api.ts";
import {
  DEFAULT_FORECAST_SORT_KEY,
  defaultSortDir,
  nextForecastSort,
  sortForecastObservations,
} from "../src/analystForecastPresentation.ts";

function obs(partial: Partial<ForecastObservation> & { identity: string }): ForecastObservation {
  return {
    symbol: "DVN",
    analyst: partial.analyst ?? partial.identity,
    firm: partial.firm ?? "Firm",
    issued_at_epoch: partial.issued_at_epoch ?? 1_700_000_000,
    horizon_epoch: partial.horizon_epoch ?? 1_730_000_000,
    horizon_label: "Assumed 12-month horizon",
    rating: partial.rating ?? "Buy",
    target_cents: partial.target_cents ?? 50_00,
    previous_target_cents: null,
    price_when_posted_cents: partial.price_when_posted_cents ?? 40_00,
    source: "TipRanks",
    identity: partial.identity,
    stars_hundredths: partial.stars_hundredths ?? null,
    rank: partial.rank ?? null,
    weight_hundredths: partial.weight_hundredths ?? null,
  };
}

describe("analyst forecast table sort", () => {
  it("defaults to weight descending", () => {
    assert.equal(DEFAULT_FORECAST_SORT_KEY, "weight");
    assert.equal(defaultSortDir("weight"), "desc");
    assert.equal(defaultSortDir("stars"), "desc");
    assert.equal(defaultSortDir("rank"), "asc");
  });

  it("sorts by weight desc with nulls last", () => {
    var rows = [
      obs({ identity: "low", weight_hundredths: 90 }),
      obs({ identity: "missing", weight_hundredths: null }),
      obs({ identity: "high", weight_hundredths: 130 }),
      obs({ identity: "mid", weight_hundredths: 100 }),
    ];
    var sorted = sortForecastObservations(rows, "weight", "desc");
    assert.deepEqual(
      sorted.map((r) => r.identity),
      ["high", "mid", "low", "missing"],
    );
  });

  it("sorts by stars and keeps nulls last when ascending", () => {
    var rows = [
      obs({ identity: "b", stars_hundredths: 300 }),
      obs({ identity: "null", stars_hundredths: null }),
      obs({ identity: "a", stars_hundredths: 450 }),
    ];
    var sorted = sortForecastObservations(rows, "stars", "asc");
    assert.deepEqual(
      sorted.map((r) => r.identity),
      ["b", "a", "null"],
    );
  });

  it("sorts rank ascending (better rank first) by default direction", () => {
    var rows = [
      obs({ identity: "third", rank: 210, weight_hundredths: 100 }),
      obs({ identity: "first", rank: 12, weight_hundredths: 100 }),
      obs({ identity: "second", rank: 42, weight_hundredths: 100 }),
    ];
    var sorted = sortForecastObservations(rows, "rank", defaultSortDir("rank"));
    assert.deepEqual(
      sorted.map((r) => r.identity),
      ["first", "second", "third"],
    );
  });

  it("toggles direction on same key and resets default on new key", () => {
    assert.deepEqual(nextForecastSort("weight", "desc", "weight"), {
      key: "weight",
      dir: "asc",
    });
    assert.deepEqual(nextForecastSort("weight", "asc", "issued"), {
      key: "issued",
      dir: "desc",
    });
    assert.deepEqual(nextForecastSort("weight", "desc", "rank"), {
      key: "rank",
      dir: "asc",
    });
  });

  it("does not mutate the input array", () => {
    var rows = [
      obs({ identity: "a", weight_hundredths: 80 }),
      obs({ identity: "b", weight_hundredths: 120 }),
    ];
    var before = rows.map((r) => r.identity);
    sortForecastObservations(rows, "weight", "desc");
    assert.deepEqual(
      rows.map((r) => r.identity),
      before,
    );
  });
});
