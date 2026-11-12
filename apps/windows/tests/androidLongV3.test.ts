import assert from "node:assert/strict";
import test from "node:test";
import { filterAndroidLongV3 } from "../src/androidLongV3.ts";

const rows = [
  { symbol: "NVDA", company: "Nvidia" },
  { symbol: "MSFT", company: "Microsoft" },
  { symbol: "MU", company: "Micron" },
  { symbol: "BRK.B", company: null },
];

test("filters Android Long V3 by ticker or company in the supplied ranking order", () => {
  assert.deepEqual(filterAndroidLongV3(rows, " micro "), [rows[1], rows[2]]);
  assert.deepEqual(filterAndroidLongV3(rows, "brk.b"), [rows[3]]);
});

test("blank search preserves the native row order", () => {
  assert.equal(filterAndroidLongV3(rows, "  "), rows);
});
