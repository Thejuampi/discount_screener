import assert from "node:assert/strict";
import test from "node:test";
import { canonicalUniverseName, planUniverseBoot } from "../src/universeBoot.ts";

test("locked backend wins over localStorage sp500", () => {
  const plan = planUniverseBoot(
    {
      name: "qa",
      symbols_total: 20,
      symbols_loaded: 0,
      profile_locked: true,
    },
    "sp500",
  );
  assert.equal(plan.kind, "use_locked");
  if (plan.kind === "use_locked") {
    assert.equal(plan.name, "qa");
    assert.equal(plan.startFeedOnly, true);
  }
});

test("unlocked applies saved localStorage profile", () => {
  const plan = planUniverseBoot(
    {
      name: "sp500",
      symbols_total: 500,
      symbols_loaded: 0,
      profile_locked: false,
    },
    "dow",
  );
  assert.equal(plan.kind, "apply_saved");
  if (plan.kind === "apply_saved") {
    assert.equal(plan.name, "dow");
  }
});

test("unlocked with empty localStorage defaults to sp500", () => {
  const plan = planUniverseBoot(
    {
      name: "sp500",
      symbols_total: 500,
      symbols_loaded: 0,
      profile_locked: false,
    },
    null,
  );
  assert.equal(plan.kind, "apply_saved");
  if (plan.kind === "apply_saved") {
    assert.equal(plan.name, "sp500");
  }
});

test("canonicalUniverseName never persists test alias", () => {
  assert.equal(canonicalUniverseName("test"), "qa");
  assert.equal(canonicalUniverseName("qa"), "qa");
  assert.equal(canonicalUniverseName("sp500"), "sp500");
});

test("unlocked ignores a QA value persisted by an earlier forced QA session", () => {
  const plan = planUniverseBoot(
    {
      name: "sp500",
      symbols_total: 500,
      symbols_loaded: 0,
      profile_locked: false,
    },
    "qa",
  );
  assert.equal(plan.kind, "apply_saved");
  if (plan.kind === "apply_saved") {
    assert.equal(plan.name, "sp500");
  }
});
