import assert from "node:assert/strict";
import test from "node:test";
import type { OpportunityRow } from "../src/api.ts";
import {
  applyTechnicalConsistency,
  buildConditionalPlan,
  formatZone,
  isActionablePriority,
  isPriorityPlan,
  isWaitPriority,
  reviewHorizonLabel,
} from "../src/conditionalPlan.ts";
import {
  isFeedIncomplete,
  rankDashboardV2,
  rankDashboardV2Sections,
  shouldShowStanceCounts,
} from "../src/dashboardV2Ranking.ts";

function baseRow(over: Partial<OpportunityRow> = {}): OpportunityRow {
  return {
    symbol: "ACME",
    company_name: "Acme Corp",
    market_price_cents: 5000,
    intrinsic_value_cents: 4800,
    gap_bps: -400,
    qualification: "Qualified",
    confidence: "High",
    signal_status: "Supportive",
    analyst_opinion_count: 10,
    recommendation_mean_hundredths: 220,
    sector_name: "Tech",
    fundamentals_score: 40,
    technical_score: -20,
    forecast_score: 30,
    composite_score: 35,
    decision: "Act",
    fundamentals_signals: [],
    technical_signals: [],
    forecast_signals: [],
    dcf_value_cents: 4700,
    insider_net_shares_90d: null,
    insider_buy_count: null,
    insider_sell_count: null,
    asset_type: "stock",
    setup_score: 35,
    setup_label: "Buy",
    daily_change_bps: 50,
    atr_cents: 120,
    next_earnings_epoch: null,
    spark: [4800, 4900, 5000],
    price_path: {
      zone_low_cents: 4600,
      zone_high_cents: 4750,
      zone_confidence: "med",
      p_touch_20d: 48,
      expected_sessions: 18,
      invalidation_cents: 5400,
      risk_codes: ["extension", "rsi_rich"],
      support_codes: ["below_value"],
      timing_method: "hybrid",
      side: "long",
    },
    ...over,
  };
}

test("extended Act becomes WaitZone with zone in headline vars", () => {
  const plan = buildConditionalPlan(baseRow(), "aggressive_v3");
  assert.equal(plan.stance, "WaitZone");
  assert.equal(plan.headlineKey, "dash.v2.head.waitReview");
  assert.match(String(plan.headlineVars.zone), /\$/);
  assert.ok(String(plan.headlineVars.review).length > 0);
  assert.ok(plan.caution.length >= 1);
  assert.ok(plan.caution.length <= 3);
});

test("in-zone Act becomes ActNow", () => {
  const plan = buildConditionalPlan(
    baseRow({
      technical_score: 20,
      price_path: {
        zone_low_cents: 4900,
        zone_high_cents: 5100,
        zone_confidence: "high",
        p_touch_20d: 90,
        expected_sessions: 0,
        invalidation_cents: 5400,
        risk_codes: [],
        support_codes: ["in_zone", "below_value"],
        timing_method: "hybrid",
        side: "long",
      },
    }),
    "aggressive_v3",
  );
  assert.equal(plan.stance, "ActNow");
  assert.equal(isActionablePriority(plan), true);
});

test("Avoid decision maps to Avoid stance", () => {
  const plan = buildConditionalPlan(
    baseRow({ decision: "Avoid", setup_label: "Avoid", composite_score: -30 }),
    "aggressive_v3",
  );
  assert.equal(plan.stance, "Avoid");
  assert.equal(plan.headlineKey, "dash.v2.head.avoid");
});

test("short model uses short headline keys", () => {
  const plan = buildConditionalPlan(
    baseRow({
      price_path: {
        zone_low_cents: 5200,
        zone_high_cents: 5400,
        zone_confidence: "med",
        p_touch_20d: 40,
        expected_sessions: 22,
        invalidation_cents: 4500,
        risk_codes: ["extension"],
        support_codes: ["above_value"],
        timing_method: "hybrid",
        side: "short",
      },
    }),
    "short_v3",
  );
  assert.equal(plan.side, "short");
  assert.ok(plan.headlineKey.includes("short."));
});

test("missing zone omits dollar clause placeholder abuse", () => {
  const plan = buildConditionalPlan(
    baseRow({
      decision: "Watch",
      setup_label: "Accumulate",
      composite_score: 20,
      price_path: null,
    }),
    "aggressive_v3",
  );
  assert.equal(plan.zoneLowCents, null);
  assert.equal(plan.stance, "WaitZone");
  assert.equal(plan.headlineKey, "dash.v2.head.wait");
});

test("Watch in-zone with material risks is WaitZone not ScaleIn", () => {
  const plan = buildConditionalPlan(
    baseRow({
      decision: "Watch",
      setup_label: "Accumulate",
      composite_score: 22,
      price_path: {
        zone_low_cents: 4900,
        zone_high_cents: 5100,
        zone_confidence: "low",
        p_touch_20d: 90,
        expected_sessions: 0,
        invalidation_cents: 5400,
        risk_codes: ["trend_against", "regime_risk"],
        support_codes: ["in_zone"],
        timing_method: "hybrid",
        side: "long",
      },
    }),
    "aggressive_v3",
  );
  assert.equal(plan.stance, "WaitZone");
  assert.equal(plan.zoneShown, false);
});

test("low-conf Wait is not wait-priority and not actionable", () => {
  const plan = buildConditionalPlan(
    baseRow({
      decision: "Watch",
      setup_label: "Accumulate",
      composite_score: 18,
      price_path: {
        zone_low_cents: 4900,
        zone_high_cents: 5100,
        zone_confidence: "low",
        p_touch_20d: 88,
        expected_sessions: 2,
        invalidation_cents: 5400,
        risk_codes: ["regime_risk"],
        support_codes: ["in_zone", "below_value"],
        timing_method: "hybrid",
        side: "long",
      },
    }),
    "aggressive_v3",
  );
  assert.equal(plan.stance, "WaitZone");
  assert.equal(isActionablePriority(plan), false);
  assert.equal(isWaitPriority(plan), false);
  assert.equal(isPriorityPlan(plan), false);
});

test("primary board never fills with Wait-only noise", () => {
  const waitOnly = baseRow({
    symbol: "WAIT1",
    decision: "Act",
    composite_score: 55,
    price_path: {
      zone_low_cents: 4000,
      zone_high_cents: 4200,
      zone_confidence: "high",
      p_touch_20d: 70,
      expected_sessions: 10,
      invalidation_cents: 4800,
      risk_codes: ["extension", "far_from_support"],
      support_codes: ["below_value"],
      timing_method: "hybrid",
      side: "long",
    },
  });
  const wait2 = baseRow({
    symbol: "WAIT2",
    decision: "Act",
    composite_score: 51,
    price_path: {
      zone_low_cents: 3000,
      zone_high_cents: 3200,
      zone_confidence: "high",
      p_touch_20d: 65,
      expected_sessions: 12,
      invalidation_cents: 3600,
      risk_codes: ["extension"],
      support_codes: ["below_value"],
      timing_method: "hybrid",
      side: "long",
    },
  });
  const summary = rankDashboardV2([waitOnly, wait2], "aggressive_v3", 6, 4);
  assert.equal(summary.actionable.length, 0);
  assert.ok(summary.watchLater.length >= 1);
  assert.ok(summary.watchLater.every((p) => p.stance === "WaitZone"));
  assert.ok(summary.plans.every((p) => p.stance !== "WaitZone" || summary.actionable.includes(p)));
  // plans === actionable only
  assert.deepEqual(
    summary.plans.map((p) => p.symbol),
    summary.actionable.map((p) => p.symbol),
  );
});

test("actionable Act ranks in primary before waits", () => {
  const wait = baseRow({
    symbol: "WAIT",
    composite_score: 80,
    decision: "Act",
    price_path: {
      zone_low_cents: 4000,
      zone_high_cents: 4200,
      zone_confidence: "high",
      p_touch_20d: 50,
      expected_sessions: 15,
      invalidation_cents: 4800,
      risk_codes: ["extension", "rsi_rich"],
      support_codes: ["below_value"],
      timing_method: "hybrid",
      side: "long",
    },
  });
  const act = baseRow({
    symbol: "ACT",
    composite_score: 40,
    technical_score: 20,
    price_path: {
      zone_low_cents: 4900,
      zone_high_cents: 5100,
      zone_confidence: "high",
      p_touch_20d: 90,
      expected_sessions: 0,
      invalidation_cents: 5400,
      risk_codes: [],
      support_codes: ["in_zone"],
      timing_method: "hybrid",
      side: "long",
    },
  });
  const summary = rankDashboardV2([wait, act], "aggressive_v3", 6, 4);
  assert.equal(summary.actionable[0]?.symbol, "ACT");
  assert.ok(summary.watchLater.some((p) => p.symbol === "WAIT"));
});

test("formatZone is middle density", () => {
  const z = formatZone(4120, 4380);
  assert.equal(z, "$41.20–$43.80");
  assert.ok(z.length < 40);
});

test("reviewHorizonLabel maps sessions to human windows", () => {
  assert.match(reviewHorizonLabel(3), /días/);
  assert.match(reviewHorizonLabel(10), /semana/);
  assert.match(reviewHorizonLabel(30), /mes/);
});

test("stance counts show when rows exist even if feed incomplete", () => {
  assert.equal(shouldShowStanceCounts(500, 571, 572), true);
  assert.equal(shouldShowStanceCounts(0, 10, 572), false);
  assert.equal(shouldShowStanceCounts(0, 572, 572), true);
  assert.equal(isFeedIncomplete(571, 572), true);
  assert.equal(isFeedIncomplete(572, 572), false);
});

test("rankDashboardV2Sections keeps market and crypto separate", () => {
  const stockAct = baseRow({
    symbol: "AAPL",
    asset_type: "stock",
    composite_score: 40,
    technical_score: 20,
    price_path: {
      zone_low_cents: 4900,
      zone_high_cents: 5100,
      zone_confidence: "high",
      p_touch_20d: 90,
      expected_sessions: 0,
      invalidation_cents: 5400,
      risk_codes: [],
      support_codes: ["in_zone"],
      timing_method: "hybrid",
      side: "long",
    },
  });
  const cryptoHot = baseRow({
    symbol: "BTC-USD",
    asset_type: "crypto",
    composite_score: 99,
    setup_label: "StrongBuy",
    decision: "Act",
    technical_score: 20,
    price_path: {
      zone_low_cents: 4900,
      zone_high_cents: 5100,
      zone_confidence: "high",
      p_touch_20d: 95,
      expected_sessions: 0,
      invalidation_cents: 5400,
      risk_codes: [],
      support_codes: ["in_zone"],
      timing_method: "hybrid",
      side: "long",
    },
  });
  const etf = baseRow({ symbol: "SPY", asset_type: "etf", composite_score: 25 });
  const sections = rankDashboardV2Sections([cryptoHot, stockAct, etf], "aggressive_v3");
  assert.equal(sections.market.rowCount, 2);
  assert.equal(sections.crypto.rowCount, 1);
  assert.ok(sections.market.actionable.every((p) => p.symbol !== "BTC-USD"));
  assert.equal(sections.market.actionable[0]?.symbol, "AAPL");
  assert.equal(sections.crypto.actionable[0]?.symbol, "BTC-USD");
});

test("headline length stays mid-density for waitZone template key", () => {
  const plan = buildConditionalPlan(baseRow(), "aggressive_v3");
  assert.ok(plan.headlineKey.length < 40);
  assert.ok(Object.keys(plan.headlineVars).length >= 2);
});
