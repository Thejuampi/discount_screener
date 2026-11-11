import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

describe("Ticker navigation", () => {
  for (const entry of ["App.tsx", "components/TickerSearch.tsx", "components/CommandPalette.tsx"]) {
    it(`leaves ticker loading to the Detail action in ${entry}`, () => {
      const source = readFileSync(new URL(`../src/${entry}`, import.meta.url), "utf8");
      const loadCall = source.match(/api\s*\.\s*(ensureSymbolLoaded|refreshSymbol|refreshSymbolDetail)\(/);
      assert.equal(loadCall?.[1], undefined);
    });
  }
});
