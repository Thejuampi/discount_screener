import assert from "node:assert/strict";
import test from "node:test";

import {
  classifyRegimeScore,
  createRegimePresentation,
  normalizeRegimeStatus,
  renderRegimeCause,
} from "../src/regimePresentation.ts";
import { warmMarketContext } from "../src/marketContextWarmup.ts";
import { translateScoringMessage } from "../src/scoringPresentationMessages.ts";

test("normalizes typed and legacy payloads conservatively", () => {
  assert.equal(normalizeRegimeStatus({ scoring_model: "aggressive_v3", asset_type: "stock", regime_status: "Included", regime_score: 0 }), "Included");
  assert.equal(normalizeRegimeStatus({ scoring_model: "aggressive_v3", asset_type: "stock", regime_status: "Included", regime_score: null }), "Unavailable");
  assert.equal(normalizeRegimeStatus({ scoring_model: "aggressive_v3", asset_type: "stock", regime_scoring_enabled: true, regime_score: 0 }), "Included");
  assert.equal(normalizeRegimeStatus({ scoring_model: "aggressive_v3", asset_type: "stock", regime_scoring_enabled: false, regime_score: null }), "Disabled");
  assert.equal(normalizeRegimeStatus({ scoring_model: "aggressive_v2", asset_type: "stock", regime_scoring_enabled: true, regime_score: 20 }), "NotApplicable");
  assert.equal(normalizeRegimeStatus({ scoring_model: "short_v3", asset_type: "etf", regime_scoring_enabled: true, regime_score: 20 }), "NotApplicable");
  assert.equal(normalizeRegimeStatus({ scoring_model: "short_v3", asset_type: "stock", regime_status: "NotApplicable", regime_score: null }), "Unavailable");
});

test("startup market-context warmup invokes the backend and remains best effort", async () => {
  let calls = 0;
  await warmMarketContext(async () => { calls += 1; return {}; });
  await warmMarketContext(async () => { calls += 1; throw new Error("offline"); });
  assert.equal(calls, 2);
});

test("classifies exact favorable and adverse boundaries", () => {
  assert.equal(classifyRegimeScore(15), "favorable");
  assert.equal(classifyRegimeScore(14), "neutral");
  assert.equal(classifyRegimeScore(-14), "neutral");
  assert.equal(classifyRegimeScore(-15), "adverse");
});

test("uses actual final minus base impact and preserves zero score", () => {
  const view = createRegimePresentation({
    scoring_model: "aggressive_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: 0,
    regime_signals: [],
    composite_score_base: 46,
    composite_score: 51,
  });
  assert.equal(view.score, 0);
  assert.equal(view.impact, 5);
  assert.equal(view.composition.context, "+5");
  assert.equal(view.status, "Included");

  const reduced = createRegimePresentation({
    scoring_model: "aggressive_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: -20,
    regime_signals: [],
    composite_score_base: 46,
    composite_score: 41,
  });
  assert.equal(reduced.impact, -5);
  assert.equal(reduced.composition.context, "-5");
  assert.equal(reduced.impactKey, "analysis.marketContext.impact.long.reduced");

  const unchanged = createRegimePresentation({
    scoring_model: "aggressive_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: 0,
    regime_signals: [],
    composite_score_base: 46,
    composite_score: 46,
  });
  assert.equal(unchanged.impactKey, "analysis.marketContext.impact.long.unchanged");
});

test("limits causes to three and exposes state-specific copy keys", () => {
  const included = createRegimePresentation({
    scoring_model: "aggressive_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: 25,
    regime_signals: ["+Quality", "+Value", "-Extension", "+Growth"],
    composite_score_base: 40,
    composite_score: 44,
  });
  assert.deepEqual(included.causes, ["+Quality", "+Value", "-Extension"]);
  assert.equal(included.statusKey, "analysis.marketContext.status.included");

  for (const [status, key] of [
    ["Disabled", "analysis.marketContext.status.disabled"],
    ["Unavailable", "analysis.marketContext.status.unavailable"],
    ["NotApplicable", "analysis.marketContext.status.notApplicable"],
  ] as const) {
    assert.equal(createRegimePresentation({
      scoring_model: "aggressive_v3",
      asset_type: status === "NotApplicable" ? "etf" : "stock",
      regime_status: status,
      regime_score: null,
      regime_signals: [],
      composite_score_base: 40,
      composite_score: 40,
    }).statusKey, key);
  }
});

test("short presentation identifies positive context as bearish support", () => {
  const view = createRegimePresentation({
    scoring_model: "short_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: 25,
    regime_signals: [],
    composite_score_base: 35,
    composite_score: 41,
  });
  assert.equal(view.side, "short");
  assert.equal(view.impactKey, "analysis.marketContext.impact.short.raised");
  assert.equal(view.bucketKey, "presentation.short.bucket.regime");
});

test("short context causes use explicit bilingual bearish support and risk framing", () => {
  for (const lang of ["es", "en"] as const) {
    const t = (key: string, vars?: Record<string, string | number>) =>
      translateScoringMessage(key, lang, vars);
    const support = renderRegimeCause("+Quality", "short", t);
    const risk = renderRegimeCause("-Extension", "short", t);
    assert.match(support, lang === "es" ? /apoya la tesis bajista/i : /supports the bearish thesis/i);
    assert.match(risk, lang === "es" ? /riesgo para la tesis bajista/i : /risk to the bearish thesis/i);
    assert.doesNotMatch(`${support} ${risk}`, /fortress|tape|policy|bucket/i);
  }
});
