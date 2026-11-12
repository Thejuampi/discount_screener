import assert from "node:assert/strict";
import test from "node:test";
import { trackedFeedSymbol } from "../src/trackedFeed.ts";

test("canonicalizes tickers while preserving provider punctuation", () => {
  for (const [raw, canonical] of [
    ["  brk.b ", "BRK.B"],
    ["brk-b", "BRK-B"],
    ["^gspc", "^GSPC"],
    ["eurusd=x", "EURUSD=X"],
  ]) {
    assert.equal(trackedFeedSymbol(raw), canonical);
  }
});

test("rejects input outside the native ticker contract", () => {
  for (const raw of [" ", "A APL", "AAPL,MSFT", "ABCDEFGHIJKLMNOPQ", "faß"]) {
    assert.equal(trackedFeedSymbol(raw), null);
  }
});
