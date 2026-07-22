import assert from "node:assert/strict";
import test from "node:test";
import type { SetupLabel } from "../src/api.ts";

import { SCORING_PRESENTATION_MESSAGES, translateScoringMessage, type ScoringLang as Lang } from "../src/scoringPresentationMessages.ts";
import {
  getScoringPresentation,
  renderText,
  type NarrativeContext,
  type TechnicalVerdict,
} from "../src/scoringPresentation.ts";

const baseContext: NarrativeContext = {
  name: "Example Corp.",
  decision: "Act",
  compositeScore: 70,
  gapBps: -1700,
  confidence: "High",
  technicalScore: 62,
  assetType: "stock",
};

const forbiddenShortTerms: Record<Lang, RegExp> = {
  es: /oportunidad de compra|buen momento de inversi[oó]n|comprar hoy|entrada alcista|espacio para subir/i,
  en: /buying opportunity|good investment timing|buy today|bullish entry|room to move up/i,
};

function renderAll(model: "aggressive_v3" | "short_v3", context: NarrativeContext, lang: Lang): string {
  const state = getScoringPresentation(model);
  const refs = [
    ...state.summary(context),
    state.decisionReason(context),
    state.riskNotice,
  ];
  if (context.assetType !== "crypto" && context.assetType !== "etf") {
    refs.splice(refs.length - 1, 0, state.gap(context.gapBps).text);
  }
  return refs.map((text) => renderText(text, (key, vars) => translateScoringMessage(key, lang, vars))).join(" ");
}

test("long models share the long presentation and retain buy-side semantics", () => {
  const v2 = getScoringPresentation("aggressive_v2");
  const v3 = getScoringPresentation("aggressive_v3");
  assert.equal(v2.side, "long");
  assert.equal(v3.side, "long");
  assert.equal(v3.summary({ ...baseContext, gapBps: 1700 })[0].key, "ia.summary.act");
});

test("short Act is explicitly a bearish position and never a purchase recommendation", () => {
  for (const lang of ["es", "en"] as const) {
    const copy = renderAll("short_v3", baseContext, lang);
    assert.match(copy, lang === "es" ? /apostar en contra|posici[oó]n short/i : /bet against|short position/i);
    assert.doesNotMatch(copy, forbiddenShortTerms[lang]);
  }
});

test("short target direction distinguishes support from contradiction", () => {
  const short = getScoringPresentation("short_v3");
  const supportive = short.gap(-1700);
  const contradictory = short.gap(4120);
  const missing = short.gap(null);

  assert.equal(supportive.tone, "favorable");
  assert.match(renderText(supportive.text, (k, v) => translateScoringMessage(k, "en", v)), /supports the short thesis/i);
  assert.equal(contradictory.tone, "adverse");
  assert.match(renderText(contradictory.text, (k, v) => translateScoringMessage(k, "en", v)), /risk to the short thesis/i);
  assert.equal(missing.tone, "neutral");
  assert.match(renderText(missing.text, (k, v) => translateScoringMessage(k, "en", v)), /no analyst anchor/i);
  assert.equal(short.gap(Number.NaN).tone, "neutral");
  assert.equal(short.gap(Number.POSITIVE_INFINITY).tone, "neutral");
});

test("short Watch and Avoid describe bearish confirmation rather than long entry", () => {
  for (const decision of ["Watch", "Avoid"] as const) {
    const copy = renderAll("short_v3", { ...baseContext, decision, compositeScore: decision === "Watch" ? 14 : -20 }, "en");
    assert.doesNotMatch(copy, forbiddenShortTerms.en);
    assert.match(copy, decision === "Watch" ? /bearish confirmation/i : /do not short/i);
  }
});

test("short crypto and ETF reasoning stays technical and avoids equity valuation claims", () => {
  for (const assetType of ["crypto", "etf"] as const) {
    const context = { ...baseContext, assetType, gapBps: null };
    const reason = getScoringPresentation("short_v3").decisionReason(context);
    const copy = renderText(reason, (k, v) => translateScoringMessage(k, "en", v));
    assert.match(copy, /bearish technical setup/i);
    assert.doesNotMatch(copy, /analyst target|valuation discount/i);
    const fullCopy = renderAll("short_v3", context, "en");
    assert.match(fullCopy, /price and volume signals/i);
    assert.doesNotMatch(fullCopy, /analyst|valuation|overvalu/i);
  }
});

test("technical evidence is regrouped from the active model perspective", () => {
  const bulls = ["bull-1", "bull-2"];
  const bears = ["bear-1"];
  const long = getScoringPresentation("aggressive_v3").technicalSignals(bulls, bears);
  const short = getScoringPresentation("short_v3").technicalSignals(bulls, bears);

  assert.deepEqual(long.supporting, bulls);
  assert.deepEqual(long.risks, bears);
  assert.deepEqual(short.supporting, bears);
  assert.deepEqual(short.risks, bulls);
});

test("technical action hints are model-aware for every verdict", () => {
  const verdicts: TechnicalVerdict[] = [
    "Strong Bullish", "Mildly Bullish", "Neutral", "Mildly Bearish", "Strong Bearish", "Insufficient data",
  ];
  const short = getScoringPresentation("short_v3");
  for (const verdict of verdicts) {
    const copy = translateScoringMessage(short.technicalActionKey(verdict), "en");
    assert.doesNotMatch(copy, forbiddenShortTerms.en);
  }
  assert.match(translateScoringMessage(short.technicalActionKey("Strong Bearish"), "en"), /supports a short setup/i);
  assert.match(translateScoringMessage(short.technicalActionKey("Strong Bullish"), "en"), /short-squeeze risk/i);
});

test("directional value tones invert without changing raw values", () => {
  const long = getScoringPresentation("aggressive_v3");
  const short = getScoringPresentation("short_v3");

  assert.equal(long.dcfTone(5000, 3000), "favorable");
  assert.equal(short.dcfTone(5000, 3000), "adverse");
  assert.equal(long.dcfTone(2000, 3000), "adverse");
  assert.equal(short.dcfTone(2000, 3000), "favorable");
  assert.equal(long.gap(0).tone, "neutral");
});

test("every backend setup token receives short copy and bearish direction", () => {
  const labels: SetupLabel[] = [
    "StrongBuy", "Buy", "StrongAccumulate", "Accumulate", "Watch", "HoldWait",
    "Hold", "Neutral", "Caution", "Distribute", "Avoid", "StrongAvoid",
  ];
  const short = getScoringPresentation("short_v3");
  for (const label of labels) {
    assert.match(short.setupLabelKey(label), /^setup\.short\./);
    assert.match(short.setupDescriptionKey(label), /^setup\.short\./);
    assert.notEqual(translateScoringMessage(short.setupLabelKey(label), "en"), short.setupLabelKey(label));
    assert.notEqual(translateScoringMessage(short.setupDescriptionKey(label), "en"), short.setupDescriptionKey(label));
  }
  assert.equal(short.setupIcon("StrongBuy", "▲▲▲"), "▼▼▼");
  assert.equal(short.setupIcon("StrongAvoid", "▼▼▼"), "▲▲▲");
});

test("short banner and sparse states identify bearish-position semantics", () => {
  const short = getScoringPresentation("short_v3");
  assert.ok(short.banner);
  const copy = [short.banner.textKey, short.loadingKey, short.emptyKey]
    .map((key) => translateScoringMessage(key, "en"))
    .join(" ");
  assert.match(copy, /bearish positions|short-position candidates|short candidate/i);
  assert.doesNotMatch(copy, forbiddenShortTerms.en);
});

test("short evidence framing preserves both support and contradictory facts", () => {
  const short = getScoringPresentation("short_v3");
  const risk = renderText(short.frameEvidence("positive free cash flow", "positive"), (k, v) => translateScoringMessage(k, "en", v));
  const support = renderText(short.frameEvidence("negative free cash flow", "negative"), (k, v) => translateScoringMessage(k, "en", v));
  assert.match(risk, /risk to the short/i);
  assert.match(support, /supports the short/i);
  assert.match(translateScoringMessage(short.analystCoverage(8, "Buy").key, "en", short.analystCoverage(8, "Buy").vars), /risk to the short/i);
});

test("technical-only missing scores stay unavailable rather than becoming zero", () => {
  const short = getScoringPresentation("short_v3");
  const reason = short.decisionReason({ ...baseContext, assetType: "crypto", technicalScore: null });
  assert.match(renderText(reason, (k, v) => translateScoringMessage(k, "en", v)), /insufficient technical data/i);
});

test("short crypto-cycle vocabulary contains no long entry guidance", () => {
  const keys = Object.keys(SCORING_PRESENTATION_MESSAGES).filter((key) => key.startsWith("presentation.short.crypto."));
  for (const lang of ["es", "en"] as const) {
    const copy = keys.map((key) => translateScoringMessage(key, lang)).join(" ");
    assert.doesNotMatch(copy, forbiddenShortTerms[lang]);
    assert.match(copy, lang === "es" ? /riesgo contra el short/i : /risk to the short/i);
  }
});
