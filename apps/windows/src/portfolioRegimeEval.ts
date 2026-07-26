/**
 * Pure portfolio evaluation against market-regime policy.
 * Reuses MarketRegime fields already computed for the banner — no re-composite.
 */

import type { MarketRegime, SetupLabel } from "./api.ts";
import type { RegimeSideLens } from "./regimeSideLens.ts";

export type PortfolioActionKey =
  | "addStrong"
  | "add"
  | "hold"
  | "trim"
  | "exit"
  | "concentration"
  | "noData"
  | "shortRisk";

export type PortfolioPosture = "Deploy" | "Neutral" | "Defensive";

export type RegimeWarning = {
  key: string;
  params?: Record<string, string | number>;
};

export type HoldingForRegimeEval = {
  symbol: string;
  weightPct: number;
  setupLabel: SetupLabel | null;
  /** From OpportunityRow when screener knows the name. */
  regimeScore?: number | null;
};

export type PortfolioRegimeEvalInput = {
  regime: MarketRegime | null;
  lens: RegimeSideLens;
  /** User risk-per-trade % (e.g. 1). */
  baseRiskPct: number;
  holdings: HoldingForRegimeEval[];
  /** When true (short_v3), long action modulation is skipped. */
  isShort: boolean;
};

export type PortfolioRegimeEval = {
  available: boolean;
  lowConfidence: boolean;
  riskMult: number;
  effectiveRiskPct: number;
  suggestedExposurePct: number | null;
  cashBufferPct: number | null;
  addBias: number;
  preferQuality: boolean;
  stance: string | null;
  primaryRegime: string | null;
  globalConfidenceBps: number | null;
  posture: PortfolioPosture;
  /** Dynamic ceiling for total portfolio risk-at-stop % (base 6% × mult). */
  totalRiskCeilingPct: number;
  actionsBySymbol: Record<string, PortfolioActionKey>;
  warnings: RegimeWarning[];
};

/** Align with RegimeScoringPolicy::MIN_CONF_BPS. */
export const REGIME_MIN_CONF_BPS = 3500;

const BASE_TOTAL_RISK_CEILING_PCT = 6;

const POSITIVE_LABELS: SetupLabel[] = ["StrongBuy", "StrongAccumulate"];
const MILD_POSITIVE: SetupLabel[] = ["Buy", "Accumulate"];
const NEGATIVE_LABELS: SetupLabel[] = ["Avoid", "Distribute", "Caution"];
const STRONG_NEGATIVE: SetupLabel[] = ["StrongAvoid"];

const DEFENSIVE_STANCES = new Set([
  "Reduce",
  "Defend",
  "Distribute",
  "Euphoria",
  "Denial",
  "UnstableBlowoff",
  "HoldTrim",
]);

const DEPLOY_STANCES = new Set([
  "Deploy",
  "TrendDeploy",
  "Accumulate",
  "SelectiveBuy",
  "HealthyPullback",
  "BloodInStreets",
  "Washout",
]);

function clamp(n: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, n));
}

/** Base recommendation from setup label + concentration (regime-blind). */
export function recommendBase(
  label: SetupLabel | null,
  weightPct: number,
): PortfolioActionKey {
  if (weightPct > 25) return "concentration";
  if (label == null) return "noData";
  if (STRONG_NEGATIVE.includes(label)) return "exit";
  if (NEGATIVE_LABELS.includes(label)) return "trim";
  if (POSITIVE_LABELS.includes(label)) return "addStrong";
  if (MILD_POSITIVE.includes(label)) return "add";
  return "hold";
}

type AddishAction = "addStrong" | "add" | "hold";

function demoteAdd(action: AddishAction, full: boolean): AddishAction {
  if (action === "addStrong") return full ? "hold" : "add";
  if (action === "add") return "hold";
  return action;
}

export function postureFromStance(
  stance: string | null | undefined,
  lens: RegimeSideLens,
): PortfolioPosture {
  const s = stance ?? "";
  if (lens === "short") {
    // Long-defensive stances are more open for shorts → not "Defensive" for short desk.
    if (DEFENSIVE_STANCES.has(s) || s === "Bear") return "Deploy";
    if (DEPLOY_STANCES.has(s)) return "Defensive";
    return "Neutral";
  }
  if (DEFENSIVE_STANCES.has(s)) return "Defensive";
  if (DEPLOY_STANCES.has(s)) return "Deploy";
  return "Neutral";
}

function riskMultFromRegime(regime: MarketRegime | null, lowConfidence: boolean): number {
  if (!regime || lowConfidence) return 1;
  const bps = regime.new_risk_multiplier_bps ?? 10_000;
  return clamp(bps / 10_000, 0.25, 1.25);
}

/**
 * Modulate a base action with regime bias. Short lens skips long-centric demotion.
 */
export function modulateAction(args: {
  base: PortfolioActionKey;
  addBias: number;
  posture: PortfolioPosture;
  stance: string | null;
  lowConfidence: boolean;
  isShort: boolean;
}): PortfolioActionKey {
  const { base, addBias, posture, stance, lowConfidence, isShort } = args;
  if (isShort || lowConfidence) return base;
  if (base === "concentration" || base === "exit" || base === "trim" || base === "noData" || base === "shortRisk") {
    return base;
  }

  var action = base;
  const defensive = posture === "Defensive" || DEFENSIVE_STANCES.has(stance ?? "");

  if (addBias <= -2 && defensive) {
    action = demoteAdd(action, true);
  } else if (addBias <= -1) {
    action = demoteAdd(action, false);
  }

  return action;
}

export function evaluatePortfolioAgainstRegime(
  input: PortfolioRegimeEvalInput,
): PortfolioRegimeEval {
  const { regime, lens, baseRiskPct, holdings, isShort } = input;

  const available = regime != null;
  const confBps = regime?.global_confidence_bps ?? null;
  const lowConfidence =
    !available || confBps == null || confBps < REGIME_MIN_CONF_BPS;

  const riskMult = riskMultFromRegime(regime, lowConfidence);
  const effectiveRiskPct = clamp(baseRiskPct * riskMult, 0.25, 3);
  const totalRiskCeilingPct = clamp(
    BASE_TOTAL_RISK_CEILING_PCT * riskMult,
    3,
    8,
  );

  const stance = regime?.action_stance != null ? String(regime.action_stance) : null;
  const primaryRegime =
    regime?.primary_regime != null ? String(regime.primary_regime) : null;
  const addBias = regime?.add_bias ?? 0;
  const preferQuality = regime?.prefer_quality ?? false;
  const posture = postureFromStance(stance, lens);

  // Aggregate weight by symbol (multi-source lots)
  const weightBySymbol = new Map<string, number>();
  const labelBySymbol = new Map<string, SetupLabel | null>();
  const scoreBySymbol = new Map<string, number | null>();
  for (const h of holdings) {
    weightBySymbol.set(h.symbol, (weightBySymbol.get(h.symbol) ?? 0) + h.weightPct);
    if (!labelBySymbol.has(h.symbol)) {
      labelBySymbol.set(h.symbol, h.setupLabel);
    }
    if (h.regimeScore != null && !scoreBySymbol.has(h.symbol)) {
      scoreBySymbol.set(h.symbol, h.regimeScore);
    }
  }

  const actionsBySymbol: Record<string, PortfolioActionKey> = {};
  for (const [symbol, weightPct] of weightBySymbol) {
    const label = labelBySymbol.get(symbol) ?? null;
    var base = recommendBase(label, weightPct);
    // Match Advisor: shortRisk only when the name has screener data.
    if (isShort && label != null) base = "shortRisk";
    actionsBySymbol[symbol] = modulateAction({
      base,
      addBias,
      posture,
      stance,
      lowConfidence,
      isShort,
    });
  }

  const warnings: RegimeWarning[] = [];
  if (available && lowConfidence) {
    warnings.push({
      key: "advisor.regime.warn.lowConf",
      params: {
        conf: confBps != null ? (confBps / 100).toFixed(0) : "—",
      },
    });
  }
  if (available && !lowConfidence && !isShort && posture === "Defensive" && addBias < 0) {
    warnings.push({ key: "advisor.regime.warn.defensiveNoAdd" });
  }
  if (available && !lowConfidence && preferQuality) {
    for (const [symbol, score] of scoreBySymbol) {
      if (score != null && score < -30) {
        warnings.push({
          key: "advisor.regime.warn.poorFit",
          params: { symbol, score },
        });
      }
    }
  }

  return {
    available,
    lowConfidence,
    riskMult,
    effectiveRiskPct,
    suggestedExposurePct: regime?.suggested_exposure_pct ?? null,
    cashBufferPct: regime?.cash_buffer_pct ?? null,
    addBias,
    preferQuality,
    stance,
    primaryRegime,
    globalConfidenceBps: confBps,
    posture,
    totalRiskCeilingPct,
    actionsBySymbol,
    warnings,
  };
}
