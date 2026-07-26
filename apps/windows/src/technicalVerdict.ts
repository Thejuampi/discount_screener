/**
 * Single technical verdict engine for the Windows app.
 *
 * Canonical score = OpportunityRow.technical_score from backend
 * `score_technicals_v3` (engine.rs). Never invent a parallel score in UI.
 *
 * Breakdown sub-scores use the same weights as the Rust engine when we need
 * a fallback composite (detail loaded before the opportunity row updates).
 */
import type { TechnicalBreakdown } from "./api.ts";
import type { TechnicalVerdict } from "./scoringPresentation.ts";

/** Same weights as engine.rs::score_technicals_v3 composite. */
export const TECH_SUBSCORE_WEIGHTS = {
  trend: 35,
  momentum: 25,
  volatility: 10,
  volume: 15,
  pattern: 15,
} as const;

export type TechConfidence = "Alta" | "Media" | "Baja";

/** Map engine technical_score (-100..+100) → display verdict. */
export function verdictFromTechnicalScore(score: number | null | undefined): TechnicalVerdict {
  if (score == null || !Number.isFinite(score)) return "Insufficient data";
  if (score >= 45) return "Strong Bullish";
  if (score >= 15) return "Mildly Bullish";
  if (score <= -45) return "Strong Bearish";
  if (score <= -15) return "Mildly Bearish";
  return "Neutral";
}

/**
 * Rebuild composite from breakdown sub-scores with backend weights.
 * Used only when opportunity technical_score is missing.
 */
export function technicalScoreFromBreakdown(
  breakdown: TechnicalBreakdown | null | undefined,
): number | null {
  if (!breakdown) return null;
  const entries: Array<[number | null, number]> = [
    [breakdown.trend_score, TECH_SUBSCORE_WEIGHTS.trend],
    [breakdown.momentum_score, TECH_SUBSCORE_WEIGHTS.momentum],
    [breakdown.volatility_score, TECH_SUBSCORE_WEIGHTS.volatility],
    [breakdown.volume_score, TECH_SUBSCORE_WEIGHTS.volume],
    [breakdown.pattern_score, TECH_SUBSCORE_WEIGHTS.pattern],
  ];
  let acc = 0;
  let w = 0;
  for (const [score, weight] of entries) {
    if (score != null && Number.isFinite(score)) {
      acc += score * weight;
      w += weight;
    }
  }
  if (w <= 0) return null;
  return Math.round(acc / w);
}

/**
 * Prefer opportunity row score (what ranking/decision used); fall back to breakdown.
 */
export function resolveCanonicalTechnicalScore(
  opportunityTechScore: number | null | undefined,
  breakdown: TechnicalBreakdown | null | undefined,
): number | null {
  if (opportunityTechScore != null && Number.isFinite(opportunityTechScore)) {
    return opportunityTechScore;
  }
  return technicalScoreFromBreakdown(breakdown);
}

/** Confidence from how complete the multi-TF / sub-score evidence is. */
export function confidenceFromBreakdown(
  breakdown: TechnicalBreakdown | null | undefined,
): TechConfidence {
  if (!breakdown) return "Baja";
  const present = [
    breakdown.trend_score,
    breakdown.momentum_score,
    breakdown.volatility_score,
    breakdown.volume_score,
    breakdown.pattern_score,
  ].filter((s) => s != null).length;
  if (present >= 4) return "Alta";
  if (present >= 2) return "Media";
  return "Baja";
}

export function narrativeKeyForVerdict(verdict: TechnicalVerdict): string {
  const map: Record<TechnicalVerdict, string> = {
    "Strong Bullish": "ts.narrative.strongBull",
    "Mildly Bullish": "ts.narrative.mildBull",
    Neutral: "ts.narrative.neutral",
    "Mildly Bearish": "ts.narrative.mildBear",
    "Strong Bearish": "ts.narrative.strongBear",
    "Insufficient data": "ts.narrative.insufficient",
  };
  return map[verdict];
}

/**
 * Technical direction that blocks aggressive long/short action stances.
 * Aligns dashboard ConditionalPlan with the same technical_score buckets/detail use.
 */
export function isTechnicalAdverseForSide(
  technicalScore: number | null | undefined,
  side: "long" | "short",
): "strong" | "mild" | null {
  if (technicalScore == null || !Number.isFinite(technicalScore)) return null;
  if (side === "long") {
    if (technicalScore <= -45) return "strong";
    if (technicalScore <= -15) return "mild";
    return null;
  }
  // short: bullish tape is adverse
  if (technicalScore >= 45) return "strong";
  if (technicalScore >= 15) return "mild";
  return null;
}
