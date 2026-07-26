/**
 * Reframe global market-regime labels for the active scoring side.
 * Backend composite is long-centric (Deploy / risk-on); short desk needs the inverse reading.
 */
import type { ScoringModelId } from "./scoringPresentation.ts";

export type RegimeSideLens = "long" | "short";

export function regimeLensFromModel(model: ScoringModelId | null | undefined): RegimeSideLens {
  return model === "short_v3" ? "short" : "long";
}

/** Stances that mean "risk-on / add long exposure" in the long lens. */
const LONG_RISK_ON = new Set([
  "Deploy",
  "TrendDeploy",
  "Accumulate",
  "SelectiveBuy",
  "HealthyPullback",
]);

/** Stances that mean "reduce / defend" in the long lens → more open for shorts. */
const LONG_RISK_OFF = new Set([
  "Reduce",
  "Defend",
  "Distribute",
  "HoldTrim",
  "Euphoria",
  "Denial",
  "UnstableBlowoff",
]);

const BULL_PHASES = new Set(["StrongBull", "Bull", "LateBull", "Snapback"]);
const BEAR_PHASES = new Set(["Bear", "Correction", "Capitulation"]);

export type ShortRegimeTone = "hostile" | "friendly" | "neutral";

export function shortRegimeTone(
  actionStance: string | null | undefined,
  primaryRegime: string | null | undefined,
): ShortRegimeTone {
  const stance = actionStance ?? "";
  const phase = primaryRegime ?? "";
  if (LONG_RISK_ON.has(stance) || BULL_PHASES.has(phase)) return "hostile";
  if (LONG_RISK_OFF.has(stance) || BEAR_PHASES.has(phase)) return "friendly";
  return "neutral";
}

/** i18n key for stance label; falls back to long key if short-specific missing. */
export function regimeStanceLabelKey(
  actionStance: string | null | undefined,
  lens: RegimeSideLens,
): string {
  const s = actionStance && actionStance.length > 0 ? actionStance : "Unknown";
  if (lens === "short") return `regime.short.stance.${s}`;
  return `regime.stance.${s}`;
}

export function regimeImplicationKey(
  actionStance: string | null | undefined,
  primaryRegime: string | null | undefined,
  lens: RegimeSideLens,
): string | null {
  if (lens !== "short") return null;
  const tone = shortRegimeTone(actionStance, primaryRegime);
  return `regime.short.implication.${tone}`;
}

/** Optional exposure label: long ceiling vs short caution. */
export function regimeExposureLabelKey(lens: RegimeSideLens): string {
  return lens === "short" ? "regime.short.exposure" : "regime.exposure";
}
