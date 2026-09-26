import assert from "node:assert/strict";
import test from "node:test";

import { observeChartWidth } from "../src/chartResizeObserver.ts";

test("chart resize observer stops after a chart redraw", () => {
  const target = {} as Element;
  let callback: (() => void) | null = null;
  let observed: Element | null = null;
  let disconnects = 0;
  let applied = 0;

  const stop = observeChartWidth(target, () => { applied += 1; }, (onResize) => {
    callback = onResize;
    return {
      observe(element) { observed = element; },
      disconnect() { disconnects += 1; },
    };
  });

  assert.strictEqual(observed, target);
  assert.ok(callback);
  callback();
  assert.equal(applied, 1);
  stop();
  assert.equal(disconnects, 1);
});
