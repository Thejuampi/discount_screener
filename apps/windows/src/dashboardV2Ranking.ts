/**
 * Ranking for Dashboard 2.0 — primary = actionable (Act/Scale), secondary = wait radar.
 * Never fills the primary board with Wait-only noise.
 */
import type { ConditionalPlan } from "./conditionalPlan.ts";
import {
  buildConditionalPlan,
  isActionablePriority,
  isWaitPriority,
} from "./conditionalPlan.ts";
import type { AssetType, OpportunityRow } from "./api.ts";
import type { ScoringModelId } from "./scoringPresentation.ts";

export interface DashboardV2Summary {
  act: number;
  wait: number;
  scale: number;
  avoid: number;
  /** Primary cards: ActNow + ScaleIn only. */
  actionable: ConditionalPlan[];
  /** Secondary cards: WaitZone worth tracking. */
  watchLater: ConditionalPlan[];
  /** @deprecated use actionable — kept empty for any legacy callers */
  plans: ConditionalPlan[];
  rowCount: number;
  filteredNoise: number;
}

export interface DashboardV2Sections {
  market: DashboardV2Summary;
  crypto: DashboardV2Summary;
}

const MARKET_TYPES: ReadonlySet<AssetType> = new Set(["stock", "etf"]);

export function isMarketAsset(assetType: AssetType): boolean {
  return MARKET_TYPES.has(assetType);
}

export function rankDashboardV2(
  rows: OpportunityRow[],
  model: ScoringModelId,
  actionTopN = 6,
  waitTopN = 4,
): DashboardV2Summary {
  return rankSlice(rows, model, actionTopN, waitTopN);
}

export function rankDashboardV2Sections(
  rows: OpportunityRow[],
  model: ScoringModelId,
  marketActionTopN = 6,
  marketWaitTopN = 4,
  cryptoActionTopN = 3,
  cryptoWaitTopN = 2,
): DashboardV2Sections {
  const marketRows = rows.filter((r) => isMarketAsset(r.asset_type));
  const cryptoRows = rows.filter((r) => r.asset_type === "crypto");
  return {
    market: rankSlice(marketRows, model, marketActionTopN, marketWaitTopN),
    crypto: rankSlice(cryptoRows, model, cryptoActionTopN, cryptoWaitTopN),
  };
}

export function shouldShowStanceCounts(
  rowCount: number,
  symbolsLoaded: number,
  symbolsTotal: number,
): boolean {
  if (rowCount > 0) return true;
  return symbolsTotal > 0 && symbolsLoaded >= symbolsTotal;
}

export function isFeedIncomplete(symbolsLoaded: number, symbolsTotal: number): boolean {
  return symbolsTotal > 0 && symbolsLoaded < symbolsTotal;
}

function rankSlice(
  rows: OpportunityRow[],
  model: ScoringModelId,
  actionTopN: number,
  waitTopN: number,
): DashboardV2Summary {
  const plans = rows.map((r) => buildConditionalPlan(r, model));
  const act = plans.filter((p) => p.stance === "ActNow").length;
  const wait = plans.filter((p) => p.stance === "WaitZone").length;
  const scale = plans.filter((p) => p.stance === "ScaleIn").length;
  const avoid = plans.filter((p) => p.stance === "Avoid").length;

  const actionableAll = plans
    .filter(isActionablePriority)
    .sort(byClarityThenUrgency);

  const waitAll = plans
    .filter(isWaitPriority)
    .sort(byClarityThenUrgency);

  // Prefer ActNow over ScaleIn in primary list
  const actionable = prioritizeActionable(actionableAll, actionTopN);
  const watchLater = waitAll.slice(0, waitTopN);

  const surfaced = new Set([
    ...actionable.map((p) => p.symbol),
    ...watchLater.map((p) => p.symbol),
  ]);
  const filteredNoise = plans.filter(
    (p) => p.stance !== "Avoid" && !surfaced.has(p.symbol),
  ).length;

  return {
    act,
    wait,
    scale,
    avoid,
    actionable,
    watchLater,
    plans: actionable, // primary only — never Wait-as-hero
    rowCount: rows.length,
    filteredNoise,
  };
}

function byClarityThenUrgency(a: ConditionalPlan, b: ConditionalPlan): number {
  return (
    b.signalClarity - a.signalClarity ||
    b.urgency - a.urgency ||
    b.compositeScore - a.compositeScore
  );
}

function prioritizeActionable(plans: ConditionalPlan[], topN: number): ConditionalPlan[] {
  if (plans.length <= topN) return plans;
  const acts = plans.filter((p) => p.stance === "ActNow");
  const scales = plans.filter((p) => p.stance === "ScaleIn");
  const out: ConditionalPlan[] = [];
  for (const p of acts) {
    if (out.length >= topN) break;
    out.push(p);
  }
  for (const p of scales) {
    if (out.length >= topN) break;
    if (!out.some((x) => x.symbol === p.symbol)) out.push(p);
  }
  return out.slice(0, topN);
}
