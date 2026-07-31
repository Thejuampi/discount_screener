import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import {
  detailValuationPresentation,
  dcfMarketRelation,
  valuationDiagnosticLines,
  valuationUnavailableI18nKey,
} from "../src/detailValuationPresentation.ts";
import type { OperatingValuationEnvelope, SymbolDetail } from "../src/api.ts";
import {
  CLOSED_VALUATION_TOOLTIP,
  nextValuationTooltipState,
  valuationTooltipDescribedBy,
} from "../src/valuationTooltipState.ts";

function envelope(status: "selected" | "disputed" | "unavailable"): OperatingValuationEnvelope {
  return {
    decision: {
      status,
      selectedModel: status === "selected" ? "forward_earnings_power" : null,
      selectedValueCents: status === "selected" ? 31_000 : null,
      candidateDifferenceBps: status === "disputed" ? 8_000 : null,
      reasons: status === "disputed" ? ["candidate_disagreement"] : ["selected_forward_earnings_power"],
      structuralDistortions: ["thin_normalized_fcff_margin"],
      fcffCandidate: { status: "available", intrinsicValueCents: 9_000, quality: "soft", refusalCodes: [], fingerprint: "fcff:1" },
      forwardCandidate: {
        model: "forward_earnings_power", status: "available", intrinsicValueCents: 31_000,
        costOfEquityBps: 900, stableGrowthBps: 300, projectionYears: 11, quality: "soft",
        evidenceFamily: "analyst_derived_model", refusals: [], fingerprint: "forward:1",
        provenance: {
          asOfEpochDay: 20_665,
          forecast: { epsLowCents: 700, epsMeanCents: 900, epsHighCents: 1_100, analystCount: 20, nearGrowthBps: 800, currency: "USD", observedEpochDay: 20_665, forecastPeriodEndEpochDay: 21_100, sourceFingerprint: "yahoo:1" },
          costOfEquity: { costOfEquityBps: 900, betaSource: "industry_shrink", provisional: false, marketParamsAsOfEpoch: 1, sourceFingerprint: "rate:1" },
          policy: { version: "projection:1", expectedCurrency: "USD", maxAgeDays: 30, minForecastHorizonDays: 100, maxForecastHorizonDays: 700, minAnalystCount: 2, holdYears: 3, fadeYears: 7, maxProjectionYears: 20, macroStableGrowthBps: 300, riskFreeRateBps: 400, riskFreeBufferBps: 100, minimumTerminalSpreadBps: 100 },
        },
      },
      fingerprint: "route:1",
    },
    diagnostics: {
      provider: "yahoo_finance", forwardSourceState: "selected", forwardSourceFailure: null,
      rateFailure: null, forecastPeriodEndEpochDay: 21_100, latestFiscalYear: 2025,
      computedAtEpochSeconds: 1_786_000_000,
      runtimePolicyVersion: "runtime:1", routerPolicyVersion: "router:1", modelPolicyVersion: "model:1",
      sourceFingerprints: ["yahoo:1", "sec:1"], codeLocators: ["operating_valuation.rs#route_operating_models"],
    },
  };
}

function detail(overrides: Partial<SymbolDetail>): SymbolDetail {
  return overrides as SymbolDetail;
}

describe("detail valuation presentation", () => {
  it("does not describe non-positive normalized FCFF as missing history", () => {
    assert.equal(
      valuationUnavailableI18nKey(
        "non_positive_normalized_fcff: aligned annual FCFF evidence has a non-positive robust margin",
      ),
      "detail.dcfUnavailableNonPositiveFcff",
    );
    assert.equal(
      valuationUnavailableI18nKey("need at least 3 annual free cash flow points"),
      "detail.dcfUnavailableMissingFcf",
    );
  });

  it("labels an independently computed DCF discount to market", () => {
    assert.deepEqual(dcfMarketRelation(8_661, 38_678), {
      bps: -7_761,
      key: "detail.dcfVsMarketDownside",
      pct: "77.6",
      tone: "negative",
    });
  });

  it("labels DCF upside and rejects unusable prices", () => {
    assert.deepEqual(dcfMarketRelation(12_500, 10_000), {
      bps: 2_500,
      key: "detail.dcfVsMarketUpside",
      pct: "25.0",
      tone: "positive",
    });
    assert.equal(dcfMarketRelation(null, 10_000), null);
    assert.equal(dcfMarketRelation(10_000, 0), null);
  });

  it("presents selected forward evidence as a soft model without a fabricated range", () => {
    const p = detailValuationPresentation(detail({
      valuation_status: "selected", selected_valuation_value_cents: 31_000,
      selected_valuation_model: "forward_earnings_power", operating_valuation: envelope("selected"),
      dcf_analysis: null, dcf_value_cents: 9_000, valuation_unavailable_reason: null,
    }));
    assert.equal(p.kind, "selected");
    if (p.kind !== "selected") return;
    assert.equal(p.valueCents, 31_000);
    assert.equal(p.labelKey, "detail.forwardEarningsValue");
    assert.equal(p.quality, "soft");
    assert.equal(p.range, null);
  });

  it("presents both candidates for disputed routes and no unique hero value", () => {
    const p = detailValuationPresentation(detail({
      valuation_status: "disputed", selected_valuation_value_cents: null,
      selected_valuation_model: null, operating_valuation: envelope("disputed"),
      dcf_analysis: null, dcf_value_cents: 9_000, valuation_unavailable_reason: null,
    }));
    assert.deepEqual(p.kind === "disputed" ? [p.fcffValueCents, p.forwardValueCents] : null, [9_000, 31_000]);
    assert.equal("valueCents" in p, false);
  });

  it("exposes provider, refusals, policies, fingerprints and code locators", () => {
    const lines = valuationDiagnosticLines(envelope("unavailable"), "runtime refused").join("\n");
    for (const token of ["provider=yahoo_finance", "route_reasons=", "forward_refusals=", "runtime_policy=runtime:1", "source_fingerprints=", "code_locators=operating_valuation.rs#route_operating_models"]) {
      assert.match(lines, new RegExp(token.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
    }
  });

  it("does not promote a legacy FCFF while current-backend routing is pending", () => {
    const p = detailValuationPresentation(detail({
      valuation_status: null,
      selected_valuation_value_cents: null,
      selected_valuation_model: null,
      operating_valuation: null,
      dcf_analysis: null,
      dcf_value_cents: 885,
      valuation_unavailable_reason: null,
    }));
    assert.equal(p.kind, "none");
  });

  it("executes focus, click and Escape tooltip state with linked ARIA content", () => {
    const tipId = "valuation-info-test";
    let state = nextValuationTooltipState(CLOSED_VALUATION_TOOLTIP, "pointer_enter");
    state = nextValuationTooltipState(state, "focus");
    state = nextValuationTooltipState(state, "toggle");
    assert.deepEqual(state, { open: true, pinned: true });
    assert.equal(valuationTooltipDescribedBy(state, tipId), tipId);
    state = nextValuationTooltipState(state, "pointer_leave");
    assert.equal(state.open, true, "click-pinned tooltip survives pointer leave");
    state = nextValuationTooltipState(state, "escape");
    assert.deepEqual(state, CLOSED_VALUATION_TOOLTIP);
    assert.equal(valuationTooltipDescribedBy(state, tipId), undefined);

    const source = readFileSync(new URL("../src/components/DetailPanel.tsx", import.meta.url), "utf8");
    for (const token of ["valuationTooltipDescribedBy(tooltip, tipId)", 'event.key === "Escape"', 'role="tooltip"', "lines.map"]) {
      assert.ok(source.includes(token), `missing accessibility token: ${token}`);
    }
  });
});
