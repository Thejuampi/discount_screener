import assert from "node:assert/strict";
import test from "node:test";

import {
  buildMarketContextNarrative,
  type MarketContextDetailMetrics,
} from "../src/marketContextNarrative.ts";
import { translateMarketContextMessage } from "../src/marketContextMessages.ts";
import {
  createRegimePresentation,
  parseLegacySignal,
} from "../src/regimePresentation.ts";
import { translateScoringMessage } from "../src/scoringPresentationMessages.ts";

type Lang = "es" | "en";

function tFor(lang: Lang) {
  return (key: string, vars?: Record<string, string | number>) => {
    const fromMc = translateMarketContextMessage(key, lang, vars);
    if (fromMc !== key) return fromMc;
    return translateScoringMessage(key, lang, vars);
  };
}

function baseDetail(
  fundOverrides: Partial<NonNullable<MarketContextDetailMetrics["fundamentals"]>> = {},
): MarketContextDetailMetrics {
  return {
    market_price_cents: 18_000,
    fundamentals: {
      sector_name: "Technology",
      market_cap_dollars: 3_000_000_000_000,
      forward_pe_hundredths: 880,
      return_on_equity_bps: 2590,
      debt_to_equity_hundredths: 150,
      free_cash_flow_dollars: 8_700_000_000,
      beta_millis: 900,
      ...fundOverrides,
    },
    chart_summary: {
      rsi: 42,
      pos_52w_pct: 55,
      volume_ratio: 110,
      ema50_cents: 17_000,
      ema200_cents: 16_000,
    },
  };
}

test("parses legacy signals without leaking unknown tokens", () => {
  assert.deepEqual(parseLegacySignal("+Quality"), {
    factor: "Quality",
    effect: "Support",
    contribution_bps: 0,
  });
  assert.deepEqual(parseLegacySignal("−Extension"), {
    factor: "Extension",
    effect: "Risk",
    contribution_bps: 0,
  });
  assert.equal(parseLegacySignal("+WeirdInternal").factor, "Other");
});

test("long narrative uses concrete evidence and never internal tokens", () => {
  const t = tFor("es");
  const view = createRegimePresentation({
    scoring_model: "aggressive_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: 26,
    regime_signals: ["+Quality", "+Value", "+Growth"],
    composite_score_base: 40,
    composite_score: 46,
  });
  const narrative = buildMarketContextNarrative(view, baseDetail(), t);

  assert.equal(narrative.scoreDisplay, "+26");
  assert.equal(narrative.classificationLabel, "Favorable");
  assert.match(narrative.summary ?? "", /favorece activos con este perfil/i);
  assert.equal(narrative.evidence.length, 3);
  assert.match(narrative.evidence[0], /Calidad financiera sólida/i);
  assert.match(narrative.evidence[0], /\$8\.7B|8\.7B/i);
  assert.match(narrative.evidence[0], /25\.9%/);
  assert.match(narrative.evidence[1], /Forward P\/E de 8\.8/i);
  assert.match(narrative.evidence[2], /Technolog|Tecnolog/i);
  assert.equal(narrative.chips.length, 3);
  assert.equal(narrative.chips[0].label, "Calidad");
  assert.equal(narrative.chips[0].mark, "+");
  const blob = `${narrative.summary}\n${narrative.evidence.join("\n")}\n${narrative.chips.map((c) => c.label).join(" ")}`;
  assert.doesNotMatch(blob, /RegimeFit|OversoldQual|LowBeta|policy|bucket|tesis long/i);
});

test("short classification avoids ambiguous Favorable label", () => {
  const t = tFor("es");
  const view = createRegimePresentation({
    scoring_model: "short_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: 26,
    regime_causes: [
      { factor: "Extension", effect: "Support", contribution_bps: 4000 },
      { factor: "Growth", effect: "Support", contribution_bps: 3000 },
    ],
    composite_score_base: 30,
    composite_score: 36,
  });
  const narrative = buildMarketContextNarrative(view, baseDetail(), t);
  assert.equal(narrative.classificationLabel, "A favor del short");
  assert.match(narrative.summary ?? "", /tesis bajista/i);
  assert.doesNotMatch(narrative.classificationLabel ?? "", /^Favorable$/);
});

test("disabled and unavailable states are muted without chips", () => {
  const t = tFor("es");
  for (const status of ["Disabled", "Unavailable"] as const) {
    const view = createRegimePresentation({
      scoring_model: "aggressive_v3",
      asset_type: "stock",
      regime_status: status,
      regime_score: null,
      regime_unavailable_reason: status === "Unavailable" ? "MarketReadingUnavailable" : null,
      composite_score_base: 40,
      composite_score: 40,
    });
    const narrative = buildMarketContextNarrative(view, baseDetail(), t);
    assert.equal(narrative.muted, true);
    assert.equal(narrative.scoreDisplay, null);
    assert.equal(narrative.evidence.length, 0);
    assert.equal(narrative.chips.length, 0);
    assert.ok(narrative.statusMessage && narrative.statusMessage.length > 10);
  }
});

test("zero regime score is included and classified neutral", () => {
  const t = tFor("en");
  const view = createRegimePresentation({
    scoring_model: "aggressive_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: 0,
    regime_signals: ["RegimeNeutral"],
    composite_score_base: 40,
    composite_score: 40,
  });
  const narrative = buildMarketContextNarrative(view, baseDetail(), t);
  assert.equal(narrative.scoreDisplay, "0");
  assert.equal(narrative.tone, "neutral");
  assert.match(narrative.summary ?? "", /clear advantage/i);
});

test("omits null metrics without inventing values", () => {
  const t = tFor("es");
  const view = createRegimePresentation({
    scoring_model: "aggressive_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: 20,
    regime_causes: [{ factor: "Value", effect: "Support", contribution_bps: 2000 }],
    composite_score_base: 40,
    composite_score: 44,
  });
  const detail = baseDetail({ forward_pe_hundredths: null });
  const narrative = buildMarketContextNarrative(view, detail, t);
  assert.equal(narrative.evidence.length, 1);
  assert.doesNotMatch(narrative.evidence[0], /null|undefined|NaN|P\/E de x/i);
  assert.match(narrative.evidence[0], /Valuaci[oó]n atractiva/i);
});

test("english long adverse uses Unfavorable pill", () => {
  const t = tFor("en");
  const view = createRegimePresentation({
    scoring_model: "aggressive_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: -20,
    regime_signals: ["−Extension"],
    composite_score_base: 40,
    composite_score: 36,
  });
  const narrative = buildMarketContextNarrative(view, baseDetail(), t);
  assert.equal(narrative.classificationLabel, "Unfavorable");
  assert.match(narrative.evidence[0], /chase risk|environment/i);
});

test("trend cause uses environment-fit copy and never restates EMAs", () => {
  const t = tFor("es");
  const view = createRegimePresentation({
    scoring_model: "aggressive_v3",
    asset_type: "stock",
    regime_status: "Included",
    regime_score: -18,
    regime_causes: [{ factor: "Trend", effect: "Risk", contribution_bps: -3500 }],
    composite_score_base: 40,
    composite_score: 36,
  });
  const narrative = buildMarketContextNarrative(view, baseDetail(), t);
  assert.equal(narrative.chips[0].label, "Alineación");
  assert.match(narrative.evidence[0], /entorno actual recompensa|alinea/i);
  assert.doesNotMatch(narrative.evidence[0], /EMA|MACD|RSI|death cross|bull market/i);
  assert.doesNotMatch(narrative.evidence[0], /Tendencia débil|Tendencia alcista/i);
});
