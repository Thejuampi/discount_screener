import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

const apiSource = readFileSync(new URL("../src/api.ts", import.meta.url), "utf8");
const quantLensSource = readFileSync(
  new URL("../src/components/QuantLensPanel.tsx", import.meta.url),
  "utf8",
);
const backendSource = readFileSync(
  new URL("../src-tauri/src/quant_lens.rs", import.meta.url),
  "utf8",
);

describe("DCF diagnostic UI boundary", () => {
  it("transports latest fiscal FCF and normalized run-rate as distinct metrics", () => {
    assert.match(apiSource, /latest_fcf_dollars\?: number \| null/);
    assert.match(apiSource, /fcf_run_rate_dollars\?: number \| null/);
    assert.match(backendSource, /"latest_fcf_dollars"/);
    assert.match(backendSource, /"fcf_run_rate_dollars"/);
  });

  it("labels both FCF meanings honestly", () => {
    assert.match(quantLensSource, /latest_fcf_dollars: "FCF latest fiscal"/);
    assert.match(quantLensSource, /fcf_run_rate_dollars: "FCF run-rate"/);
    assert.match(
      quantLensSource,
      /key === "latest_fcf_dollars"[\s\S]*key === "fcf_run_rate_dollars"/,
    );
  });
});
