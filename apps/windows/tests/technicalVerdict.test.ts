import assert from "node:assert/strict";
import test from "node:test";
import type { TechnicalBreakdown } from "../src/api.ts";
import {
  confidenceFromBreakdown,
  isTechnicalAdverseForSide,
  resolveCanonicalTechnicalScore,
  technicalScoreFromBreakdown,
  verdictFromTechnicalScore,
} from "../src/technicalVerdict.ts";

function breakdown(over: Partial<TechnicalBreakdown> = {}): TechnicalBreakdown {
  return {
    trend_score: 40,
    momentum_score: 20,
    volatility_score: 0,
    volume_score: -10,
    pattern_score: 10,
    alignment: "Mixed",
    weekly_trend: "Bullish",
    daily_trend: "Neutral",
    hourly_trend: "Bullish",
    patterns: [],
    levels: { supports_cents: [], resistances_cents: [] },
    divergences: [],
    ...over,
  };
}

test("verdict thresholds match engine display bands", () => {
  assert.equal(verdictFromTechnicalScore(null), "Insufficient data");
  assert.equal(verdictFromTechnicalScore(50), "Strong Bullish");
  assert.equal(verdictFromTechnicalScore(19), "Mildly Bullish");
  assert.equal(verdictFromTechnicalScore(0), "Neutral");
  assert.equal(verdictFromTechnicalScore(-20), "Mildly Bearish");
  assert.equal(verdictFromTechnicalScore(-46), "Strong Bearish");
});

test("breakdown composite uses backend weights", () => {
  // 40*35 + 20*25 + 0*10 + (-10)*15 + 10*15 = 1400+500+0-150+150 = 1900 / 100 = 19
  assert.equal(technicalScoreFromBreakdown(breakdown()), 19);
});

test("opportunity technical_score wins over breakdown recompute", () => {
  assert.equal(resolveCanonicalTechnicalScore(19, breakdown({ trend_score: -80 })), 19);
  assert.equal(resolveCanonicalTechnicalScore(null, breakdown()), 19);
  assert.equal(resolveCanonicalTechnicalScore(undefined, null), null);
});

test("confidence tracks sub-score coverage", () => {
  assert.equal(confidenceFromBreakdown(breakdown()), "Alta");
  assert.equal(
    confidenceFromBreakdown(
      breakdown({
        trend_score: null,
        momentum_score: 10,
        volatility_score: null,
        volume_score: null,
        pattern_score: null,
      }),
    ),
    "Baja",
  );
  assert.equal(confidenceFromBreakdown(null), "Baja");
});

test("adverse technical direction is side-aware", () => {
  assert.equal(isTechnicalAdverseForSide(-50, "long"), "strong");
  assert.equal(isTechnicalAdverseForSide(-20, "long"), "mild");
  assert.equal(isTechnicalAdverseForSide(19, "long"), null);
  assert.equal(isTechnicalAdverseForSide(50, "short"), "strong");
  assert.equal(isTechnicalAdverseForSide(20, "short"), "mild");
  assert.equal(isTechnicalAdverseForSide(-20, "short"), null);
});

test("TEL/CHTR-style case: tech +19 is mildly bullish not strong bearish", () => {
  // Regression: UI must not invent Strong Bearish while buckets show +19
  const score = resolveCanonicalTechnicalScore(19, breakdown());
  assert.equal(score, 19);
  assert.equal(verdictFromTechnicalScore(score), "Mildly Bullish");
  assert.notEqual(verdictFromTechnicalScore(score), "Strong Bearish");
});
