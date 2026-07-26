/**
 * Maps OpportunityRow + compact price_path → ConditionalPlan for Dashboard 2.0.
 * Pure presentation logic — no React, no I/O.
 *
 * SNR rules:
 * - ScaleIn is rare (partial entry only when score + zone conf are solid and risks are light).
 * - Mixed / weak Watch setups default to WaitZone, not ScaleIn spam.
 * - Low zone confidence is not shown as a precise $ band (avoids false precision).
 */
import type {
  CompactPricePath,
  Decision,
  OpportunityRow,
  PathMotiveCode,
  SetupLabel,
  ZoneConfidence,
} from "./api.ts";
import type { ScoringModelId } from "./scoringPresentation.ts";
import { isTechnicalAdverseForSide } from "./technicalVerdict.ts";

export type PlanStance = "ActNow" | "ScaleIn" | "WaitZone" | "Avoid";
export type PlanSide = "long" | "short";

export interface PlanEvidence {
  code: PathMotiveCode | "score" | "decision";
  textKey: string;
  vars?: Record<string, string | number>;
}

export interface ConditionalPlan {
  symbol: string;
  companyName: string | null;
  side: PlanSide;
  stance: PlanStance;
  zoneLowCents: number | null;
  zoneHighCents: number | null;
  zoneConfidence: ZoneConfidence | null;
  /** True when zone band is shown (med/high conf only). */
  zoneShown: boolean;
  pTouch20d: number | null;
  expectedSessions: number | null;
  invalidationCents: number | null;
  headlineKey: string;
  headlineVars: Record<string, string | number>;
  support: PlanEvidence[];
  caution: PlanEvidence[];
  urgency: number;
  /** Higher = clearer signal for priority list. */
  signalClarity: number;
  compositeScore: number;
  decision: Decision;
  setupLabel: SetupLabel;
  marketPriceCents: number;
  spark: number[];
  timingMethod: CompactPricePath["timing_method"] | null;
}

/** Path risks that block "enter now / scale" more than soft regime noise. */
const MATERIAL_RISKS: ReadonlySet<PathMotiveCode> = new Set([
  "extension",
  "far_from_support",
  "far_from_resistance",
  "rsi_rich",
  "rsi_washed",
  "trend_against",
  "earnings_soon",
  "weak_forecast",
  "above_value",
]);

const STRONG_SETUP: SetupLabel[] = ["StrongBuy", "Buy", "StrongAccumulate"];
const POSITIVE_SETUP: SetupLabel[] = [
  "StrongBuy",
  "Buy",
  "Accumulate",
  "StrongAccumulate",
];

export function planSideFromModel(model: ScoringModelId): PlanSide {
  return model === "short_v3" ? "short" : "long";
}

export function buildConditionalPlan(
  row: OpportunityRow,
  model: ScoringModelId,
): ConditionalPlan {
  const side = planSideFromModel(model);
  const path = row.price_path ?? null;
  const rawStance = deriveStance(row, path);
  // Same technical_score the detail panel / buckets show — never Act long into Strong Bearish.
  const stance = applyTechnicalConsistency(rawStance, row.technical_score, side);
  const zoneShown = shouldShowZone(path);
  const rawCaution = motivesToEvidence(path?.risk_codes ?? []);
  const rawSupport = motivesToEvidence(path?.support_codes ?? []);
  const techCaution = technicalCautionEvidence(row.technical_score, side);
  const mergedCaution =
    techCaution != null ? [techCaution, ...rawCaution] : rawCaution;
  const { caution, support } = pickEvidence(
    stance,
    mergedCaution,
    rawSupport,
    row.composite_score,
  );

  const { headlineKey, headlineVars } = buildHeadline(row, path, stance, side, zoneShown);
  const signalClarity = computeSignalClarity(row, path, stance, zoneShown);
  const urgency = computeUrgency(row, path, stance, zoneShown, signalClarity);

  return {
    symbol: row.symbol,
    companyName: row.company_name,
    side,
    stance,
    zoneLowCents: zoneShown ? (path?.zone_low_cents ?? null) : null,
    zoneHighCents: zoneShown ? (path?.zone_high_cents ?? null) : null,
    zoneConfidence: path?.zone_confidence ?? null,
    zoneShown,
    pTouch20d: path?.p_touch_20d ?? null,
    expectedSessions: path?.expected_sessions ?? null,
    invalidationCents: path?.invalidation_cents ?? null,
    headlineKey,
    headlineVars,
    support,
    caution,
    urgency,
    signalClarity,
    compositeScore: row.composite_score,
    decision: row.decision,
    setupLabel: row.setup_label,
    marketPriceCents: row.market_price_cents,
    spark: row.spark ?? [],
    timingMethod: path?.timing_method ?? null,
  };
}

/**
 * Align plan stance with the single technical engine score.
 * Mild adverse tech → never Act/Scale; strong adverse → Avoid.
 */
export function applyTechnicalConsistency(
  stance: PlanStance,
  technicalScore: number | null | undefined,
  side: PlanSide,
): PlanStance {
  const adverse = isTechnicalAdverseForSide(technicalScore, side);
  if (adverse == null) return stance;
  if (adverse === "strong") return "Avoid";
  // mild: demote actionable stances only
  if (stance === "ActNow" || stance === "ScaleIn") return "WaitZone";
  return stance;
}

function technicalCautionEvidence(
  technicalScore: number | null | undefined,
  side: PlanSide,
): PlanEvidence | null {
  const adverse = isTechnicalAdverseForSide(technicalScore, side);
  if (adverse == null || technicalScore == null) return null;
  return {
    code: "score",
    textKey:
      adverse === "strong"
        ? "dash.v2.ev.techStrongAdverse"
        : "dash.v2.ev.techMildAdverse",
    vars: { tech: technicalScore },
  };
}

/** Primary board: long/short entries you can act on (or scale into). Never Wait. */
export function isActionablePriority(plan: ConditionalPlan): boolean {
  if (plan.stance === "ActNow") {
    return plan.compositeScore >= 25 || plan.zoneShown;
  }
  if (plan.stance === "ScaleIn") {
    if (plan.compositeScore < 28) return false;
    if (!plan.zoneShown) return false;
    if (plan.zoneConfidence === "low") return false;
    if (plan.caution.length >= 2) return false;
    return true;
  }
  return false;
}

/**
 * Secondary radar: solid setups to re-check later — not shown as the main board.
 * Higher bar than old “everything is wait”.
 */
export function isWaitPriority(plan: ConditionalPlan): boolean {
  if (plan.stance !== "WaitZone") return false;
  if (plan.compositeScore < 35) return false;
  if (!plan.zoneShown) return false;
  if (plan.zoneConfidence !== "high" && plan.zoneConfidence !== "med") return false;
  return true;
}

/** @deprecated prefer isActionablePriority / isWaitPriority */
export function isPriorityPlan(plan: ConditionalPlan): boolean {
  return isActionablePriority(plan) || isWaitPriority(plan);
}

/** Human review horizon from expected sessions (for wait copy). */
export function reviewHorizonLabel(sessions: number | null | undefined): string {
  if (sessions == null || !Number.isFinite(sessions)) return "unas semanas";
  const s = Math.max(0, Math.round(sessions));
  if (s <= 2) return "1–2 días de sesión";
  if (s <= 5) return `${s} días de sesión`;
  if (s <= 12) return "1–2 semanas";
  if (s <= 25) return "2–4 semanas";
  if (s <= 45) return "1–2 meses";
  return "varios meses";
}

export function deriveStance(
  row: OpportunityRow,
  path: CompactPricePath | null,
): PlanStance {
  if (row.decision === "Avoid" || row.setup_label === "StrongAvoid") {
    return "Avoid";
  }
  if (row.composite_score < 0) {
    return "Avoid";
  }

  const riskCodes = path?.risk_codes ?? [];
  const material = riskCodes.filter((c) => MATERIAL_RISKS.has(c)).length;
  const softOnly =
    riskCodes.length > 0 && riskCodes.every((c) => !MATERIAL_RISKS.has(c));
  const inZone = path?.support_codes?.includes("in_zone") ?? false;
  const nearZone = path?.support_codes?.includes("near_zone") ?? false;
  const inOrNear = inZone || nearZone;
  const far =
    riskCodes.includes("far_from_support") ||
    riskCodes.includes("far_from_resistance") ||
    riskCodes.includes("extension") ||
    riskCodes.includes("rsi_rich");
  const zoneConf = path?.zone_confidence ?? null;
  const solidZone = zoneConf === "high" || zoneConf === "med";

  if (row.decision === "Act") {
    // Clean entry: in/near zone, no material path risk
    if (inOrNear && material === 0) return "ActNow";
    // One soft risk (e.g. regime) only — still act if strong setup
    if (inOrNear && material === 0 && softOnly && STRONG_SETUP.includes(row.setup_label)) {
      return "ActNow";
    }
    // Stretched / multiple material risks → wait
    if (far || material >= 2) return "WaitZone";
    // Partial: near zone, single material risk, solid zone band → rare ScaleIn
    if (nearZone && material === 1 && solidZone && row.composite_score >= 30) {
      return "ScaleIn";
    }
    // In zone but one material caution → wait for cleaner tape, not "scale"
    if (inZone && material >= 1) return "WaitZone";
    // Strong label, not extended
    if (STRONG_SETUP.includes(row.setup_label) && !far && material <= 1) {
      return material === 0 ? "ActNow" : "WaitZone";
    }
    return "WaitZone";
  }

  // Watch — default is Wait, not Scale
  if (row.composite_score < 15 && !POSITIVE_SETUP.includes(row.setup_label)) {
    return "WaitZone";
  }
  if (far || material >= 1) return "WaitZone";
  if (inOrNear && solidZone && row.composite_score >= 28 && material === 0) {
    // Only scale when score is solid and path is clean
    return "ScaleIn";
  }
  if (inOrNear && material === 0 && STRONG_SETUP.includes(row.setup_label) && solidZone) {
    return "ScaleIn";
  }
  return "WaitZone";
}

function shouldShowZone(path: CompactPricePath | null): boolean {
  if (path?.zone_low_cents == null || path?.zone_high_cents == null) return false;
  return path.zone_confidence === "med" || path.zone_confidence === "high";
}

function motivesToEvidence(codes: PathMotiveCode[]): PlanEvidence[] {
  return codes.map((code) => ({
    code,
    textKey: `dash.v2.motive.${code}`,
  }));
}

/** Prefer evidence that supports the stance; cap mixed noise at 3. */
function pickEvidence(
  stance: PlanStance,
  caution: PlanEvidence[],
  support: PlanEvidence[],
  composite: number,
): { caution: PlanEvidence[]; support: PlanEvidence[] } {
  if (stance === "ActNow") {
    const s = support.slice(0, 2);
    const c = caution.slice(0, 1);
    if (s.length === 0) {
      s.push({
        code: "score",
        textKey: "dash.v2.ev.composite",
        vars: { score: composite },
      });
    }
    return { caution: c, support: s };
  }
  if (stance === "WaitZone") {
    const c = caution.slice(0, 2);
    const s = support.slice(0, 1);
    if (c.length === 0) {
      c.push({ code: "decision", textKey: "dash.v2.ev.timingWeak" });
    }
    return { caution: c, support: s };
  }
  if (stance === "ScaleIn") {
    return {
      caution: caution.slice(0, 1),
      support: support.slice(0, 2),
    };
  }
  return { caution: caution.slice(0, 2), support: support.slice(0, 1) };
}

function buildHeadline(
  row: OpportunityRow,
  path: CompactPricePath | null,
  stance: PlanStance,
  side: PlanSide,
  zoneShown: boolean,
): { headlineKey: string; headlineVars: Record<string, string | number> } {
  const zone =
    zoneShown && path?.zone_low_cents != null && path?.zone_high_cents != null
      ? formatZone(path.zone_low_cents, path.zone_high_cents)
      : "";
  const p20 = path?.p_touch_20d;
  const inv =
    path?.invalidation_cents != null
      ? formatDollars(path.invalidation_cents)
      : "";

  const vars: Record<string, string | number> = {
    symbol: row.symbol,
    zone: zone || "—",
    p20: p20 ?? "—",
    inv: inv || "—",
    sessions: path?.expected_sessions ?? "—",
    review: reviewHorizonLabel(path?.expected_sessions),
  };

  const prefix = side === "short" ? "short." : "";
  switch (stance) {
    case "ActNow":
      return {
        headlineKey: zone
          ? `dash.v2.head.${prefix}actZone`
          : `dash.v2.head.${prefix}act`,
        headlineVars: vars,
      };
    case "ScaleIn":
      return {
        headlineKey: zone
          ? `dash.v2.head.${prefix}scaleZone`
          : `dash.v2.head.${prefix}scale`,
        headlineVars: vars,
      };
    case "WaitZone":
      return {
        headlineKey: zone
          ? `dash.v2.head.${prefix}waitReview`
          : `dash.v2.head.${prefix}wait`,
        headlineVars: vars,
      };
    case "Avoid":
      return {
        headlineKey: `dash.v2.head.${prefix}avoid`,
        headlineVars: vars,
      };
  }
}

function computeSignalClarity(
  row: OpportunityRow,
  path: CompactPricePath | null,
  stance: PlanStance,
  zoneShown: boolean,
): number {
  let c = 0;
  if (stance === "ActNow") c += 40;
  else if (stance === "WaitZone") c += 25;
  else if (stance === "ScaleIn") c += 10;
  else c -= 20;

  if (zoneShown && path?.zone_confidence === "high") c += 20;
  else if (zoneShown && path?.zone_confidence === "med") c += 10;
  else if (path?.zone_confidence === "low") c -= 25;

  const material = (path?.risk_codes ?? []).filter((x) => MATERIAL_RISKS.has(x)).length;
  if (stance === "ActNow" && material === 0) c += 15;
  if (stance === "WaitZone" && material >= 1) c += 10;
  // Mixed: support says in_zone but many material risks
  if ((path?.support_codes?.includes("in_zone") ?? false) && material >= 2) c -= 20;

  if (row.composite_score >= 40) c += 15;
  else if (row.composite_score >= 30) c += 8;
  else if (row.composite_score < 15) c -= 15;

  if (row.confidence === "High") c += 5;
  if (row.confidence === "Low") c -= 8;

  return c;
}

function computeUrgency(
  row: OpportunityRow,
  path: CompactPricePath | null,
  stance: PlanStance,
  zoneShown: boolean,
  signalClarity: number,
): number {
  let u = row.composite_score + signalClarity;

  if (stance === "ActNow") u += 50;
  else if (stance === "WaitZone") u += 20;
  else if (stance === "ScaleIn") u += 5;
  else u -= 40;

  if (path?.support_codes?.includes("in_zone") && stance === "ActNow") u += 30;
  if (path?.support_codes?.includes("near_zone") && stance === "WaitZone") u += 15;

  if (zoneShown && path?.zone_confidence === "high") u += 12;
  if (!zoneShown && path?.zone_confidence === "low") u -= 30;

  return u;
}

export function formatDollars(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}

export function formatZone(low: number, high: number): string {
  return `${formatDollars(low)}–${formatDollars(high)}`;
}
