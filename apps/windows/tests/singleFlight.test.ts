import assert from "node:assert/strict";
import test from "node:test";

import { singleFlight } from "../src/singleFlight.ts";

test("coalesces overlapping refreshes and allows the next poll after settlement", async () => {
  let calls = 0;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  const refresh = singleFlight(async () => {
    calls += 1;
    await gate;
  });

  const first = refresh();
  const overlapping = refresh();
  assert.strictEqual(overlapping, first);
  assert.equal(calls, 1);

  release();
  await first;
  await refresh();
  assert.equal(calls, 2);
});

test("normalizes synchronous throws and reopens after rejection", async () => {
  let calls = 0;
  const refresh = singleFlight(() => {
    calls += 1;
    if (calls === 1) throw new Error("sync failure");
    return Promise.resolve("ok");
  });

  await assert.rejects(refresh(), /sync failure/);
  assert.equal(await refresh(), "ok");
  assert.equal(calls, 2);
});
