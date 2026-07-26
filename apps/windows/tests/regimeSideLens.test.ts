import assert from "node:assert/strict";
import test from "node:test";
import {
  regimeImplicationKey,
  regimeLensFromModel,
  regimeStanceLabelKey,
  shortRegimeTone,
} from "../src/regimeSideLens.ts";

test("short_v3 uses short lens", () => {
  assert.equal(regimeLensFromModel("short_v3"), "short");
  assert.equal(regimeLensFromModel("aggressive_v3"), "long");
});

test("Deploy + Bull is hostile to shorts", () => {
  assert.equal(shortRegimeTone("Deploy", "Bull"), "hostile");
  assert.equal(
    regimeImplicationKey("Deploy", "Bull", "short"),
    "regime.short.implication.hostile",
  );
  assert.equal(regimeStanceLabelKey("Deploy", "short"), "regime.short.stance.Deploy");
});

test("Reduce + Bear is friendly to shorts", () => {
  assert.equal(shortRegimeTone("Reduce", "Bear"), "friendly");
  assert.equal(
    regimeImplicationKey("Reduce", "Bear", "short"),
    "regime.short.implication.friendly",
  );
});

test("long lens has no implication line", () => {
  assert.equal(regimeImplicationKey("Deploy", "Bull", "long"), null);
  assert.equal(regimeStanceLabelKey("Deploy", "long"), "regime.stance.Deploy");
});
