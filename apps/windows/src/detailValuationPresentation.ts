export type DcfMarketRelation = {
  bps: number;
  key: "detail.dcfVsMarketUpside" | "detail.dcfVsMarketDownside" | "detail.dcfVsMarketFlat";
  pct: string;
  tone: "positive" | "negative" | "neutral";
};

/** Keep backend refusal semantics distinct: present-but-non-positive is not missing history. */
export function valuationUnavailableI18nKey(reason: string | null | undefined): string {
  if (!reason) return "detail.dcfUnavailableHint";
  const r = reason.toLowerCase();
  if (r.includes("unclassified")) return "detail.dcfUnavailableUnclassified";
  if (r.includes("not eligible") || r.includes("etf") || r.includes("reit")) {
    return "detail.dcfUnavailableNotEligible";
  }
  if (r.includes("non_positive_normalized_fcff") || r.includes("not positive")) {
    return "detail.dcfUnavailableNonPositiveFcff";
  }
  if (r.includes("acquisition-contaminated")) {
    return "detail.dcfUnavailableAcquisitionGrowth";
  }
  if (r.includes("book")) return "detail.dcfUnavailableMissingBook";
  if (r.includes("fcf") || r.includes("free cash")) return "detail.dcfUnavailableMissingFcf";
  if (r.includes("share")) return "detail.dcfUnavailableMissingShares";
  return "detail.dcfUnavailableHint";
}

/** DCF and analyst target are independent anchors; compute their price relations separately. */
export function dcfMarketRelation(
  dcfValueCents: number | null | undefined,
  marketPriceCents: number | null | undefined,
): DcfMarketRelation | null {
  if (
    dcfValueCents == null
    || marketPriceCents == null
    || !Number.isFinite(dcfValueCents)
    || !Number.isFinite(marketPriceCents)
    || dcfValueCents <= 0
    || marketPriceCents <= 0
  ) return null;

  const bps = Math.round((dcfValueCents / marketPriceCents - 1) * 10_000);
  const pct = Math.abs(bps / 100).toFixed(1);
  if (bps > 0) return { bps, key: "detail.dcfVsMarketUpside", pct, tone: "positive" };
  if (bps < 0) return { bps, key: "detail.dcfVsMarketDownside", pct, tone: "negative" };
  return { bps, key: "detail.dcfVsMarketFlat", pct, tone: "neutral" };
}

export type DetailValuationPresentation =
  | {
      kind: "selected";
      valueCents: number;
      model: "fcff_wacc" | "forward_earnings_power" | "residual_income_equity";
      labelKey: string;
      quality: "solid" | "soft";
      range: { lowCents: number; highCents: number } | null;
      diagnostics: string[];
    }
  | {
      kind: "disputed";
      fcffValueCents: number | null;
      forwardValueCents: number | null;
      differenceBps: number | null;
      diagnostics: string[];
    }
  | { kind: "unavailable"; reasonKey: string; diagnostics: string[] }
  | { kind: "none"; diagnostics: string[] };

function printable(value: unknown): string {
  if (value == null) return "none";
  if (typeof value === "string") return value;
  return JSON.stringify(value);
}

/** Developer-facing evidence trail: stable field names and code locators are intentional. */
export function valuationDiagnosticLines(
  envelope: OperatingValuationEnvelope | null | undefined,
  unavailableReason?: string | null,
): string[] {
  if (!envelope) {
    return unavailableReason ? [`legacy_reason=${unavailableReason}`] : [];
  }
  const { decision, diagnostics } = envelope;
  const lines = [
    `route_status=${decision.status}`,
    `selected_model=${decision.selectedModel ?? "none"}`,
    `provider=${diagnostics.provider}`,
    `forward_source_state=${diagnostics.forwardSourceState}`,
    `forward_source_failure=${printable(diagnostics.forwardSourceFailure)}`,
    `rate_failure=${printable(diagnostics.rateFailure)}`,
    `route_reasons=${decision.reasons.join(",") || "none"}`,
    `structural_distortions=${decision.structuralDistortions.join(",") || "none"}`,
    `forward_refusals=${decision.forwardCandidate.refusals.join(",") || "none"}`,
    `fcff_refusals=${decision.fcffCandidate.refusalCodes.join(",") || "none"}`,
    `forecast_period_end_epoch_day=${diagnostics.forecastPeriodEndEpochDay ?? "none"}`,
    `latest_fiscal_year=${diagnostics.latestFiscalYear ?? "none"}`,
    `computed_at_epoch_seconds=${diagnostics.computedAtEpochSeconds}`,
    `runtime_policy=${diagnostics.runtimePolicyVersion}`,
    `router_policy=${diagnostics.routerPolicyVersion}`,
    `model_policy=${diagnostics.modelPolicyVersion}`,
    `route_fingerprint=${decision.fingerprint}`,
    `source_fingerprints=${diagnostics.sourceFingerprints.join(" | ") || "none"}`,
    `code_locators=${diagnostics.codeLocators.join(" | ") || "none"}`,
  ];
  if (unavailableReason) lines.splice(1, 0, `unavailable_reason=${unavailableReason}`);
  return lines;
}

function dcfQuality(analysis: DcfAnalysis | null | undefined): "solid" | "soft" {
  if (!analysis || analysis.diagnostics?.point_estimate_unreliable) return "soft";
  const inputs = analysis.wacc_inputs;
  return inputs.cost_of_debt === "default"
    || inputs.tax_rate === "default"
    || inputs.beta === "default"
    || inputs.wacc_clamped
    ? "soft"
    : "solid";
}

export function detailValuationPresentation(
  detail: Pick<SymbolDetail,
    | "valuation_status"
    | "selected_valuation_value_cents"
    | "selected_valuation_model"
    | "operating_valuation"
    | "valuation_unavailable_reason"
    | "dcf_analysis"
    | "dcf_value_cents"
  > | null | undefined,
): DetailValuationPresentation {
  if (!detail) return { kind: "none", diagnostics: [] };
  const envelope = detail.operating_valuation;
  const diagnostics = valuationDiagnosticLines(envelope, detail.valuation_unavailable_reason);
  if (detail.valuation_status === "disputed" && envelope) {
    return {
      kind: "disputed",
      fcffValueCents: envelope.decision.fcffCandidate.intrinsicValueCents,
      forwardValueCents: envelope.decision.forwardCandidate.intrinsicValueCents,
      differenceBps: envelope.decision.candidateDifferenceBps,
      diagnostics,
    };
  }
  if (detail.valuation_status === "unavailable" || detail.valuation_status === "not_eligible") {
    return {
      kind: "unavailable",
      reasonKey: valuationUnavailableI18nKey(detail.valuation_unavailable_reason),
      diagnostics,
    };
  }
  const selected = detail.selected_valuation_value_cents;
  if (detail.valuation_status === "selected" && selected != null && selected > 0) {
    const forward = detail.selected_valuation_model === "forward_earnings_power";
    const analysis = detail.dcf_analysis;
    return {
      kind: "selected",
      valueCents: selected,
      model: forward ? "forward_earnings_power" : "fcff_wacc",
      labelKey: forward ? "detail.forwardEarningsValue" : "detail.dcfValue",
      quality: forward ? "soft" : dcfQuality(analysis),
      range: !forward && analysis && analysis.bear_intrinsic_value_cents > 0 && analysis.bull_intrinsic_value_cents > 0
        ? { lowCents: analysis.bear_intrinsic_value_cents, highCents: analysis.bull_intrinsic_value_cents }
        : null,
      diagnostics,
    };
  }

  // Current backends always serialize this additive field. Null means routing
  // is pending: never promote the legacy FCFF compatibility cache meanwhile.
  if (Object.prototype.hasOwnProperty.call(detail, "valuation_status")) {
    if (detail.valuation_unavailable_reason) {
      return { kind: "unavailable", reasonKey: valuationUnavailableI18nKey(detail.valuation_unavailable_reason), diagnostics };
    }
    return { kind: "none", diagnostics };
  }

  const analysis = detail.dcf_analysis;
  const legacyValue = analysis?.base_intrinsic_value_cents ?? detail.dcf_value_cents;
  if (legacyValue != null && legacyValue > 0) {
    const residual = analysis?.model === "residual_income_equity";
    return {
      kind: "selected",
      valueCents: legacyValue,
      model: residual ? "residual_income_equity" : "fcff_wacc",
      labelKey: residual ? "detail.residualIncomeValue" : "detail.dcfValue",
      quality: dcfQuality(analysis),
      range: analysis && analysis.bear_intrinsic_value_cents > 0 && analysis.bull_intrinsic_value_cents > 0
        ? { lowCents: analysis.bear_intrinsic_value_cents, highCents: analysis.bull_intrinsic_value_cents }
        : null,
      diagnostics,
    };
  }
  if (detail.valuation_unavailable_reason) {
    return { kind: "unavailable", reasonKey: valuationUnavailableI18nKey(detail.valuation_unavailable_reason), diagnostics };
  }
  return { kind: "none", diagnostics };
}
import type { DcfAnalysis, OperatingValuationEnvelope, SymbolDetail } from "./api";
