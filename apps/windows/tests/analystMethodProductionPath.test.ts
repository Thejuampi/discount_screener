import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

const source = readFileSync(
  new URL("../src/components/QuantLensPanel.tsx", import.meta.url),
  "utf8",
);

describe("Quant Lens analyst-method production path", () => {
  it("polls the cache-only dossier endpoint for the panel lifetime with cleanup", () => {
    assert.match(source, /api\s*\.getValuationDossier\(symbol\)/);
    assert.match(source, /window\.setInterval\(refreshDossier, ANALYST_METHOD_POLL_INTERVAL_MS\)/);
    assert.match(source, /window\.clearInterval\(timer\)/);
  });

  it("routes the dossier through its dedicated presenter and deduplicating attach helper", () => {
    assert.match(source, /analystMethodPresentation\(dossier\)/);
    assert.match(source, /composeQuantLensPanel\(symbol, rawReport, analyst, err\)/);
    assert.match(source, /data-presentation-source=/);
  });
});
