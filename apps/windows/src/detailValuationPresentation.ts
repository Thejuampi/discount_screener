import type {
  AnalystMethodCandidateView,
  DcfAnalysis,
  OperatingValuationEnvelope,
  QuantLensReport,
  QuantLensSection,
  SymbolDetail,
  ValuationDossierView,
} from "./api";

export type DcfMarketRelation = {
  bps: number;
  key: "detail.dcfVsMarketUpside" | "detail.dcfVsMarketDownside" | "detail.dcfVsMarketFlat";
  pct: string;
  tone: "positive" | "negative" | "neutral";
};

/** Slice 1C market-reference presentation — never an intrinsic/selected DCF substitute. */
export type AnalystMethodPresentation =
  | {
      kind: "available";
      methodLabel: string;
      targetValueCents: string;
      epsCents: string;
      multipleHundredths: number;
      forecastPeriodEnd: string;
      targetAsOf: string;
      datePrecision: string;
      sourceVerification: string;
      metricId: string | null;
      metricBasis: string | null;
      quality: string | null;
      importQualityLabel: string | null;
      currency: string | null;
      multipleProvenance: string | null;
      scenario: string | null;
      engineId: string | null;
      methodPolicyVersion: string | null;
      runId: string | null;
      identityVintage: string | null;
      shareBasisId: string | null;
      lineageGroupId: string | null;
      diagnosticOnly: true;
      rankingEligible: false;
      strongEligible: false;
    }
  | {
      kind: "unavailable";
      methodLabel: string;
      reasonCode: string;
      diagnosticOnly: true;
      rankingEligible: false;
      strongEligible: false;
    }
  | { kind: "absent" };

export function analystMethodPresentation(
  dossier: ValuationDossierView | null | undefined,
): AnalystMethodPresentation {
  const lane = dossier?.analystMethod;
  if (!lane || lane.status === "absent") return { kind: "absent" };
  if (lane.status === "unavailable") {
    return {
      kind: "unavailable",
      methodLabel: lane.methodLabel || "manual analyst method",
      reasonCode: lane.reasonCode || "unavailable",
      diagnosticOnly: true,
      rankingEligible: false,
      strongEligible: false,
    };
  }
  if (
    lane.targetValueCents == null
    || lane.epsCents == null
    || lane.multipleHundredths == null
  ) {
    return {
      kind: "unavailable",
      methodLabel: lane.methodLabel || "manual analyst method",
      reasonCode: lane.reasonCode || "missing_result",
      diagnosticOnly: true,
      rankingEligible: false,
      strongEligible: false,
    };
  }
  return {
    kind: "available",
    methodLabel: lane.methodLabel || "manual analyst method",
    targetValueCents: lane.targetValueCents,
    epsCents: lane.epsCents,
    multipleHundredths: lane.multipleHundredths,
    forecastPeriodEnd: lane.forecastPeriodEnd || "—",
    targetAsOf: lane.targetAsOf || "—",
    datePrecision: lane.datePrecision || "—",
    sourceVerification: lane.sourceVerification || "source_not_verified",
    metricId: lane.metricId,
    metricBasis: lane.metricBasis,
    quality: lane.quality,
    importQualityLabel: lane.importQualityLabel,
    currency: lane.currency,
    multipleProvenance: lane.multipleProvenance,
    scenario: lane.scenario,
    engineId: lane.engineId,
    methodPolicyVersion: lane.methodPolicyVersion,
    runId: lane.runId,
    identityVintage: lane.identityVintage,
    shareBasisId: lane.shareBasisId,
    lineageGroupId: lane.lineageGroupId,
    diagnosticOnly: true,
    rankingEligible: false,
    strongEligible: false,
  };
}

function exactInteger(value: string | number): bigint | null {
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) return null;
    return BigInt(value);
  }
  if (!/^-?\d+$/.test(value)) return null;
  try {
    return BigInt(value);
  } catch {
    return null;
  }
}

function fixedHundredths(value: string | number): string | null {
  const integer = exactInteger(value);
  if (integer == null) return null;
  const negative = integer < 0n;
  const absolute = negative ? -integer : integer;
  const whole = absolute / 100n;
  const fraction = (absolute % 100n).toString().padStart(2, "0");
  return `${negative ? "-" : ""}${whole}.${fraction}`;
}

/** Format cents without converting the fixed-point integer through JS Number. */
export function formatCentsAsCurrency(cents: string | number, currency = "USD"): string {
  const fixed = fixedHundredths(cents);
  if (fixed == null) return "—";
  const symbols: Record<string, string> = { USD: "$", EUR: "€", GBP: "£" };
  const normalized = currency.trim().toUpperCase() || "USD";
  return symbols[normalized] ? `${symbols[normalized]}${fixed}` : `${normalized} ${fixed}`;
}

/** Compatibility helper for USD diagnostic display. */
export function formatCentsAsDollars(cents: string | number): string {
  return formatCentsAsCurrency(cents, "USD");
}

/** Format multiple hundredths as X.XXx without precision loss. */
export function formatMultipleHundredths(hundredths: string | number): string {
  const fixed = fixedHundredths(hundredths);
  return fixed == null ? "—" : `${fixed}x`;
}

export const ANALYST_METHOD_POLL_INTERVAL_MS = 15_000;

/** Dedicated 1C presenter output consumed by the real Quant Lens panel. */
export function analystMethodQuantLensSection(
  presentation: AnalystMethodPresentation,
): QuantLensSection | null {
  if (presentation.kind === "absent") return null;
  if (presentation.kind === "unavailable") {
    return {
      id: "manual_analyst_method",
      title: presentation.methodLabel,
      status: "Unavailable",
      summary: `Diagnostic market-reference method unavailable (${presentation.reasonCode}).`,
      metrics: [
        ["presentation_source", "valuation_dossier_presenter"],
        ["lane", "diagnostic only"],
        ["reason_code", presentation.reasonCode],
        ["ranking_eligible", "false"],
        ["strong_eligible", "false"],
      ],
    };
  }
  const currency = presentation.currency || "USD";
  return {
    id: "manual_analyst_method",
    title: presentation.methodLabel,
    status: "Provisional",
    summary: `${formatCentsAsCurrency(presentation.epsCents, currency)} EPS × ${formatMultipleHundredths(presentation.multipleHundredths)} = ${formatCentsAsCurrency(presentation.targetValueCents, currency)}`,
    metrics: [
      ["presentation_source", "valuation_dossier_presenter"],
      ["lane", "diagnostic only"],
      ["target_value_cents", presentation.targetValueCents],
      ["eps_cents", presentation.epsCents],
      ["multiple_hundredths", String(presentation.multipleHundredths)],
      ["forecast_period_end", presentation.forecastPeriodEnd],
      ["target_as_of", presentation.targetAsOf],
      ["date_precision", presentation.datePrecision],
      ["currency", currency],
      ["source_verification", presentation.sourceVerification],
      ["multiple_provenance", presentation.multipleProvenance ?? "n/a"],
      ["scenario", presentation.scenario ?? "n/a"],
      ["metric_id", presentation.metricId ?? "n/a"],
      ["metric_basis", presentation.metricBasis ?? "n/a"],
      ["quality", presentation.quality ?? "n/a"],
      ["import_quality_label", presentation.importQualityLabel ?? "n/a"],
      ["diagnostic_only", "true"],
      ["ranking_eligible", "false"],
      ["strong_eligible", "false"],
      ["engine_id", presentation.engineId ?? "n/a"],
      ["method_policy_version", presentation.methodPolicyVersion ?? "n/a"],
      ["run_id", presentation.runId ?? "n/a"],
      ["share_basis_id", presentation.shareBasisId ?? "n/a"],
      ["identity_vintage", presentation.identityVintage ?? "n/a"],
      ["lineage_group_id", presentation.lineageGroupId ?? "n/a"],
      ["method", "forward_earnings_multiple"],
    ],
  };
}

export type QuantLensPanelComposition = {
  report: QuantLensReport | null;
  coreWarning: string | null;
};

/**
 * Compose the independently readable dossier lane with the Quant Lens core.
 * A core failure must not suppress an available/refused analyst-method lane.
 */
export function composeQuantLensPanel(
  symbol: string,
  report: QuantLensReport | null,
  presentation: AnalystMethodPresentation | null,
  coreFailure: string | null,
): QuantLensPanelComposition {
  if (report) {
    return {
      report: presentation ? attachAnalystMethodPresentation(report, presentation) : report,
      coreWarning: coreFailure,
    };
  }
  const section = presentation ? analystMethodQuantLensSection(presentation) : null;
  if (!section) return { report: null, coreWarning: coreFailure };
  return {
    report: {
      symbol,
      primary_status: "Unavailable",
      sections: [section],
      model_version: 0,
    },
    coreWarning: coreFailure || "Quant Lens core unavailable; showing the independent analyst-method dossier.",
  };
}

/** Dossier is authoritative for this lane; remove a backend duplicate before attaching it. */
export function attachAnalystMethodPresentation(
  report: QuantLensReport,
  presentation: AnalystMethodPresentation,
): QuantLensReport {
  const section = analystMethodQuantLensSection(presentation);
  return {
    ...report,
    sections: [
      ...report.sections.filter((candidate) => candidate.id !== "manual_analyst_method"),
      ...(section ? [section] : []),
    ],
  };
}

/** Guard: dossier candidate must never be treated as selected intrinsic. */
export function analystMethodIsIntrinsicSelection(
  lane: AnalystMethodCandidateView | null | undefined,
): false {
  void lane;
  return false;
}

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
  if (r.includes("retention") || r.includes("payout")) {
    return "detail.dcfUnavailableMissingRetention";
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

  // Financial-services valuations do not pass through the operating-model
  // router, so valuation_status is intentionally null. A current, typed
  // residual-income analysis is independently publishable and must not be
  // suppressed as if it were an unverified legacy FCFF cache.
  const analysis = detail.dcf_analysis;
  if (
    analysis?.model === "residual_income_equity"
    && analysis.base_intrinsic_value_cents > 0
  ) {
    return {
      kind: "selected",
      valueCents: analysis.base_intrinsic_value_cents,
      model: "residual_income_equity",
      labelKey: "detail.residualIncomeValue",
      quality: dcfQuality(analysis),
      range: analysis.bear_intrinsic_value_cents > 0 && analysis.bull_intrinsic_value_cents > 0
        ? {
            lowCents: analysis.bear_intrinsic_value_cents,
            highCents: analysis.bull_intrinsic_value_cents,
          }
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
