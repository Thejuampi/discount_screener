import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ValuationDossierView } from "../src/api.ts";
import {
  ANALYST_METHOD_POLL_INTERVAL_MS,
  analystMethodQuantLensSection,
  analystMethodIsIntrinsicSelection,
  analystMethodPresentation,
  attachAnalystMethodPresentation,
  composeQuantLensPanel,
  formatCentsAsCurrency,
  formatCentsAsDollars,
  formatMultipleHundredths,
} from "../src/detailValuationPresentation.ts";

function dossier(
  overrides: Partial<ValuationDossierView["analystMethod"]> & {
    status: ValuationDossierView["analystMethod"]["status"];
  },
): ValuationDossierView {
  return {
    symbol: "AMZN",
    viewVersion: 1,
    analystMethod: {
      status: overrides.status,
      runId: overrides.runId ?? null,
      projectionKey: overrides.projectionKey ?? null,
      targetValueCents: overrides.targetValueCents ?? null,
      epsCents: overrides.epsCents ?? null,
      multipleHundredths: overrides.multipleHundredths ?? null,
      forecastPeriodEnd: overrides.forecastPeriodEnd ?? null,
      economicPeriodStart: overrides.economicPeriodStart ?? null,
      targetAsOf: overrides.targetAsOf ?? null,
      datePrecision: overrides.datePrecision ?? null,
      currency: overrides.currency ?? null,
      metricId: overrides.metricId ?? null,
      metricBasis: overrides.metricBasis ?? null,
      multipleProvenance: overrides.multipleProvenance ?? null,
      scenario: overrides.scenario ?? null,
      quality: overrides.quality ?? null,
      importQualityLabel: overrides.importQualityLabel ?? null,
      sourceVerification: overrides.sourceVerification ?? null,
      methodLabel: overrides.methodLabel ?? "manual analyst method",
      engineId: overrides.engineId ?? null,
      methodPolicyVersion: overrides.methodPolicyVersion ?? null,
      decisionAtUnixMs: overrides.decisionAtUnixMs ?? null,
      computationCreatedAtUnixMs: overrides.computationCreatedAtUnixMs ?? null,
      evidenceObservedAtUnixMs: overrides.evidenceObservedAtUnixMs ?? null,
      replayMode: overrides.replayMode ?? null,
      issuerId: overrides.issuerId ?? null,
      securityId: overrides.securityId ?? null,
      ticker: overrides.ticker ?? null,
      identityVintage: overrides.identityVintage ?? null,
      identityFingerprint: overrides.identityFingerprint ?? null,
      shareBasisId: overrides.shareBasisId ?? null,
      shareBasisVintageFingerprint: overrides.shareBasisVintageFingerprint ?? null,
      shareBasisDescription: overrides.shareBasisDescription ?? null,
      perShareBasisId: overrides.perShareBasisId ?? null,
      corporateActionVintage: overrides.corporateActionVintage ?? null,
      fiscalCalendarVintage: overrides.fiscalCalendarVintage ?? null,
      fiscalPeriodCoordinate: overrides.fiscalPeriodCoordinate ?? null,
      fiscalCalendarVerification: overrides.fiscalCalendarVerification ?? null,
      horizonComparisonEligible: overrides.horizonComparisonEligible ?? false,
      epsObservationId: overrides.epsObservationId ?? null,
      multipleObservationId: overrides.multipleObservationId ?? null,
      lineageGroupId: overrides.lineageGroupId ?? null,
      epsProviderId: overrides.epsProviderId ?? null,
      multipleProviderId: overrides.multipleProviderId ?? null,
      epsProviderVintageId: overrides.epsProviderVintageId ?? null,
      multipleProviderVintageId: overrides.multipleProviderVintageId ?? null,
      epsSourceLocation: overrides.epsSourceLocation ?? null,
      multipleSourceLocation: overrides.multipleSourceLocation ?? null,
      epsExtractionMethod: overrides.epsExtractionMethod ?? null,
      multipleExtractionMethod: overrides.multipleExtractionMethod ?? null,
      epsRevisionId: overrides.epsRevisionId ?? null,
      multipleRevisionId: overrides.multipleRevisionId ?? null,
      epsPublicationAtUnixMs: overrides.epsPublicationAtUnixMs ?? null,
      multiplePublicationAtUnixMs: overrides.multiplePublicationAtUnixMs ?? null,
      epsSourceAvailableAtUnixMs: overrides.epsSourceAvailableAtUnixMs ?? null,
      multipleSourceAvailableAtUnixMs: overrides.multipleSourceAvailableAtUnixMs ?? null,
      epsIngestedAtUnixMs: overrides.epsIngestedAtUnixMs ?? null,
      multipleIngestedAtUnixMs: overrides.multipleIngestedAtUnixMs ?? null,
      reasonCode: overrides.reasonCode ?? null,
      diagnosticOnly: true,
      rankingEligible: false,
      strongEligible: false,
    },
  };
}

describe("analystMethodPresentation (1C)", () => {
  it("maps fixture $13 × 28 as diagnostic available, never ranking/Strong", () => {
    const p = analystMethodPresentation(
      dossier({
        status: "available",
        targetValueCents: "36400",
        epsCents: "1300",
        multipleHundredths: 2_800,
        forecastPeriodEnd: "2028-12-31",
        targetAsOf: "2027-12",
        datePrecision: "month_label",
        sourceVerification: "source_not_verified",
        metricId: "gaap_diluted_eps",
        metricBasis: "transcription_claim",
        importQualityLabel: "fixture_transcription",
        quality: "provisional",
        runId: "run:fixture:amzn-fem-1",
      }),
    );
    assert.equal(p.kind, "available");
    if (p.kind !== "available") return;
    assert.equal(p.targetValueCents, "36400");
    assert.equal(p.methodLabel, "manual analyst method");
    assert.equal(p.sourceVerification, "source_not_verified");
    assert.equal(p.forecastPeriodEnd, "2028-12-31");
    assert.equal(p.targetAsOf, "2027-12");
    assert.equal(p.datePrecision, "month_label");
    assert.equal(p.diagnosticOnly, true);
    assert.equal(p.rankingEligible, false);
    assert.equal(p.strongEligible, false);
    assert.equal(formatCentsAsDollars(p.targetValueCents), "$364.00");
    assert.equal(formatMultipleHundredths(p.multipleHundredths), "28.00x");
  });

  it("surfaces refusal without inventing a target value", () => {
    const p = analystMethodPresentation(
      dossier({
        status: "unavailable",
        reasonCode: "not_eligible_for_publication",
        runId: "run:x",
      }),
    );
    assert.equal(p.kind, "unavailable");
    if (p.kind !== "unavailable") return;
    assert.equal(p.reasonCode, "not_eligible_for_publication");
    assert.equal(p.rankingEligible, false);
    assert.equal(p.strongEligible, false);
  });

  it("treats absent as no UI lane", () => {
    assert.equal(analystMethodPresentation(dossier({ status: "absent" })).kind, "absent");
    assert.equal(analystMethodPresentation(null).kind, "absent");
  });

  it("never treats the lane as an intrinsic selection", () => {
    assert.equal(
      analystMethodIsIntrinsicSelection(
        dossier({
          status: "available",
          targetValueCents: "36400",
          epsCents: "1300",
          multipleHundredths: 2_800,
        }).analystMethod,
      ),
      false,
    );
  });

  it("preserves exact i64 cents and uses the dossier currency", () => {
    const p = analystMethodPresentation(dossier({
      status: "available",
      targetValueCents: "9223372036854775807",
      epsCents: "1300",
      multipleHundredths: 2_800,
      currency: "EUR",
    }));
    assert.equal(p.kind, "available");
    if (p.kind !== "available") return;
    assert.equal(formatCentsAsCurrency(p.targetValueCents, p.currency ?? "USD"), "€92233720368547758.07");
    const section = analystMethodQuantLensSection(p);
    assert.equal(section?.summary, "€13.00 EPS × 28.00x = €92233720368547758.07");
    assert.equal(section?.metrics.find(([key]) => key === "target_value_cents")?.[1], "9223372036854775807");
    assert.equal(section?.metrics.find(([key]) => key === "presentation_source")?.[1], "valuation_dossier_presenter");
  });

  it("deduplicates the backend lane and cannot change primary status", () => {
    const p = analystMethodPresentation(dossier({
      status: "available",
      targetValueCents: "36400",
      epsCents: "1300",
      multipleHundredths: 2_800,
    }));
    const merged = attachAnalystMethodPresentation({
      symbol: "AMZN",
      primary_status: "Strong",
      model_version: 4,
      sections: [
        { id: "manual_analyst_method", title: "stale", status: "old", summary: "old", metrics: [] },
        { id: "evidence", title: "Evidence", status: "Ready", summary: "ok", metrics: [] },
      ],
    }, p);
    assert.equal(merged.primary_status, "Strong");
    assert.equal(merged.sections.filter((section) => section.id === "manual_analyst_method").length, 1);
    assert.equal(merged.sections.at(-1)?.status, "Provisional");
  });

  it("keeps the cache-only dossier poll alive beyond the old 20 second cutoff", () => {
    assert.equal(ANALYST_METHOD_POLL_INTERVAL_MS, 15_000);
  });

  it("renders an independent dossier lane when the Quant Lens core fails", () => {
    const presentation = analystMethodPresentation(dossier({
      status: "available",
      targetValueCents: "36400",
      epsCents: "1300",
      multipleHundredths: 2_800,
    }));
    const composed = composeQuantLensPanel("AMZN", null, presentation, "core read failed");
    assert.equal(composed.report?.primary_status, "Unavailable");
    assert.equal(composed.report?.sections.length, 1);
    assert.equal(composed.report?.sections[0]?.id, "manual_analyst_method");
    assert.equal(composed.coreWarning, "core read failed");
  });

  it("renders an independent refusal lane but not an absent dossier", () => {
    const unavailable = analystMethodPresentation(dossier({
      status: "unavailable",
      reasonCode: "ambiguous_current_identity",
    }));
    const refused = composeQuantLensPanel("AMZN", null, unavailable, "core read failed");
    assert.equal(refused.report?.sections[0]?.status, "Unavailable");
    assert.equal(
      refused.report?.sections[0]?.metrics.find(([key]) => key === "presentation_source")?.[1],
      "valuation_dossier_presenter",
    );

    const absent = composeQuantLensPanel(
      "AMZN",
      null,
      analystMethodPresentation(dossier({ status: "absent" })),
      "core read failed",
    );
    assert.equal(absent.report, null);
  });
});
