import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { polarPoint, polygonPath, radarRadius } from "../src/regimeRadar.ts";

describe("radarRadius", () => {
  it("maps trend extremes to edge/center correctly", () => {
    assert.equal(radarRadius("trend", 100), 100);
    assert.equal(radarRadius("trend", -100), 0);
    assert.equal(radarRadius("trend", 0), 50);
  });

  it("inverts volatility stress so calm is outer", () => {
    assert.ok(radarRadius("volatility", 80) < 20);
    assert.ok(radarRadius("volatility", -40) > 65);
  });

  it("treats contrarian fear as outer opportunity", () => {
    assert.ok(radarRadius("sentiment", 80) > 85);
    assert.ok(radarRadius("sentiment", -80) < 15);
  });
});

describe("polygonPath", () => {
  it("builds a closed polygon for six axes", () => {
    const path = polygonPath([0.5, 0.6, 0.4, 0.7, 0.5, 0.3], 100, 100, 80);
    assert.ok(path.startsWith("M"));
    assert.ok(path.endsWith(" Z"));
    assert.equal((path.match(/L/g) ?? []).length, 5);
  });
});

describe("polarPoint", () => {
  it("puts first axis at the top", () => {
    const p = polarPoint(0, 6, 1, 100, 100, 50);
    assert.ok(Math.abs(p.x - 100) < 0.01);
    assert.ok(p.y < 100);
  });
});
