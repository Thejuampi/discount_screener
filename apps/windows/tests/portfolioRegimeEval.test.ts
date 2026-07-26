import assert from "node:assert/strict";
import test from "node:test";
import type { MarketRegime } from "../src/api.ts";
import {
  evaluatePortfolioAgainstRegime,
  modulateAction,
  postureFromStance,
  recommendBase,
  REGIME_MIN_CONF_BPS,
} from "../src/portfolioRegimeEval.ts";

function regime(partial: Partial<MarketRegime>): MarketRegime {
  return {
    regime: "RiskOn",
    primary_regime: "Bull",
    environment_band: "RiskOn",
    action_stance: "Deploy",
    suggested_exposure_pct: 65,
    cash_buffer_pct: 35,
    new_risk_multiplier_bps: 6825,
    add_bias: 1,
    prefer_quality: false,
    global_confidence_bps: 8866,
    environment_score: 40,
    sentiment_score: 0,
    quality_score: 10,
    pillars: [],
    vix: 18.6,
    vix_percentile_1y: 68,
    vix_term_ratio: 0.95,
    vix_state: "normal",
    cnn_fear_greed: 39,
    cnn_fear_greed_label: "Fear",
    cnn_fear_greed_prev_close: null,
    breadth_above_ma200_pct: 64,
    breadth_above_ma50_pct: 63,
    breadth_sample: 500,
    spy_above_ma200: true,
    spy_price_cents: null,
    spy_ma200_cents: null,
    spy_drawdown_from_ath_pct: -2.7,
    credit_score: null,
    leadership_score: null,
    avg_corr_milli: null,
    thesis_es: "",
    thesis_en: "",
    notes_es: [],
    notes_en: [],
    warnings: [],
    as_of_epoch: 0,
    version: 1,
    ...partial,
  };
}

test("recommendBase: concentration and labels", () => {
  assert.equal(recommendBase("Buy", 30), "concentration");
  assert.equal(recommendBase("StrongBuy", 10), "addStrong");
  assert.equal(recommendBase("Buy", 10), "add");
  assert.equal(recommendBase("Avoid", 10), "trim");
  assert.equal(recommendBase("StrongAvoid", 10), "exit");
  assert.equal(recommendBase(null, 10), "noData");
  assert.equal(recommendBase("Hold", 10), "hold");
});

test("mult 0.6825 → effectiveRisk and ceiling", () => {
  const eval_ = evaluatePortfolioAgainstRegime({
    regime: regime({ new_risk_multiplier_bps: 6825 }),
    lens: "long",
    baseRiskPct: 1,
    holdings: [],
    isShort: false,
  });
  assert.ok(Math.abs(eval_.riskMult - 0.6825) < 1e-9);
  assert.ok(Math.abs(eval_.effectiveRiskPct - 0.6825) < 1e-9);
  assert.ok(Math.abs(eval_.totalRiskCeilingPct - 6 * 0.6825) < 1e-9);
  assert.equal(eval_.suggestedExposurePct, 65);
  assert.equal(eval_.posture, "Deploy");
  assert.equal(eval_.lowConfidence, false);
});

test("low conf → no demotion, mult 1, warning", () => {
  const eval_ = evaluatePortfolioAgainstRegime({
    regime: regime({
      global_confidence_bps: REGIME_MIN_CONF_BPS - 1,
      new_risk_multiplier_bps: 4000,
      add_bias: -2,
      action_stance: "Reduce",
    }),
    lens: "long",
    baseRiskPct: 1,
    holdings: [{ symbol: "AAPL", weightPct: 10, setupLabel: "StrongBuy" }],
    isShort: false,
  });
  assert.equal(eval_.lowConfidence, true);
  assert.equal(eval_.riskMult, 1);
  assert.equal(eval_.effectiveRiskPct, 1);
  assert.equal(eval_.actionsBySymbol.AAPL, "addStrong");
  assert.ok(eval_.warnings.some((w) => w.key === "advisor.regime.warn.lowConf"));
});

test("add_bias -1 demotes addStrong → add", () => {
  assert.equal(
    modulateAction({
      base: "addStrong",
      addBias: -1,
      posture: "Neutral",
      stance: "Hold",
      lowConfidence: false,
      isShort: false,
    }),
    "add",
  );
});

test("add_bias -2 + Reduce demotes add → hold", () => {
  const eval_ = evaluatePortfolioAgainstRegime({
    regime: regime({
      action_stance: "Reduce",
      add_bias: -2,
      new_risk_multiplier_bps: 5000,
      prefer_quality: true,
    }),
    lens: "long",
    baseRiskPct: 1,
    holdings: [{ symbol: "TSLA", weightPct: 8, setupLabel: "Buy", regimeScore: -40 }],
    isShort: false,
  });
  assert.equal(eval_.posture, "Defensive");
  assert.equal(eval_.actionsBySymbol.TSLA, "hold");
  assert.ok(eval_.warnings.some((w) => w.key === "advisor.regime.warn.defensiveNoAdd"));
  assert.ok(eval_.warnings.some((w) => w.key === "advisor.regime.warn.poorFit"));
});

test("add_bias -2 + Deploy does not full-demote (only one step)", () => {
  const eval_ = evaluatePortfolioAgainstRegime({
    regime: regime({
      action_stance: "Deploy",
      add_bias: -2,
      new_risk_multiplier_bps: 10_000,
    }),
    lens: "long",
    baseRiskPct: 1,
    holdings: [{ symbol: "MSFT", weightPct: 5, setupLabel: "StrongBuy" }],
    isShort: false,
  });
  // Deploy is not defensive → only -1 step path would apply if bias <= -1
  // bias -2 without defensive still hits add_bias <= -1 one-step demotion
  assert.equal(eval_.actionsBySymbol.MSFT, "add");
});

test("null regime → legacy sizing, empty actions ok", () => {
  const eval_ = evaluatePortfolioAgainstRegime({
    regime: null,
    lens: "long",
    baseRiskPct: 1.5,
    holdings: [{ symbol: "X", weightPct: 5, setupLabel: "Buy" }],
    isShort: false,
  });
  assert.equal(eval_.available, false);
  assert.equal(eval_.riskMult, 1);
  assert.equal(eval_.effectiveRiskPct, 1.5);
  assert.equal(eval_.totalRiskCeilingPct, 6);
  assert.equal(eval_.actionsBySymbol.X, "add");
});

test("short lens: posture inverted; isShort keeps shortRisk", () => {
  assert.equal(postureFromStance("Deploy", "short"), "Defensive");
  assert.equal(postureFromStance("Reduce", "short"), "Deploy");

  const eval_ = evaluatePortfolioAgainstRegime({
    regime: regime({ action_stance: "Reduce", add_bias: -2 }),
    lens: "short",
    baseRiskPct: 1,
    holdings: [{ symbol: "NVDA", weightPct: 10, setupLabel: "StrongBuy" }],
    isShort: true,
  });
  assert.equal(eval_.actionsBySymbol.NVDA, "shortRisk");
});
