import type { AssetType, ConfidenceBand, Decision, SetupLabel } from "./api";

export const SCORING_MODELS = ["aggressive_v2", "aggressive_v3", "short_v3"] as const;
export type ScoringModelId = (typeof SCORING_MODELS)[number];
export type ScoringSide = "long" | "short";
export type DirectionalTone = "favorable" | "adverse" | "neutral";
export type LongEvidenceDirection = "positive" | "negative" | "neutral";
export type TechnicalVerdict =
  | "Strong Bullish" | "Mildly Bullish" | "Neutral"
  | "Mildly Bearish" | "Strong Bearish" | "Insufficient data";

export interface TextRef {
  key: string;
  vars?: Record<string, string | number>;
}

export interface NarrativeContext {
  name: string;
  decision: Decision;
  compositeScore: number;
  gapBps: number | null;
  confidence: ConfidenceBand;
  technicalScore: number | null;
  assetType: AssetType;
  /** True when V3 composite includes the 4th regime_fit bucket. */
  regimeScoringEnabled?: boolean;
  regimeScore?: number | null;
}

export interface DirectionalText {
  text: TextRef;
  compactText: TextRef;
  tone: DirectionalTone;
  marker: "▲" | "▼" | "—";
}

export interface DecisionHeader {
  emoji: string;
  color: string;
  titleKey: string;
}

export interface TechnicalSignalPerspective<T> {
  supporting: T[];
  risks: T[];
  supportingLabelKey: string;
  risksLabelKey: string;
  noSupportingKey: string;
  noRisksKey: string;
}

export interface ScoringPresentationState {
  side: ScoringSide;
  isShort: boolean;
  analystTargetLabelKey: string;
  gapLabelKey: string;
  targetColumnKey: string;
  gapColumnKey: string;
  setupTooltipKey: string;
  trioTooltipKey: string;
  loadingKey: string;
  loadingHintKey: string;
  emptyKey: string;
  emptyHintKey: string;
  /** Fund / Tech / Forecast — and optionally Regime as 4th. */
  bucketLabelKeys: readonly [string, string, string] | readonly [string, string, string, string];
  regimeBucketLabelKey: string;
  riskNotice: TextRef;
  banner: { tagKey: string; textKey: string } | null;
  setupLabelKey(label: SetupLabel): string;
  setupDescriptionKey(label: SetupLabel): string;
  setupIcon(label: SetupLabel, defaultIcon: string): string;
  dashboardOpportunityKey: string;
  dashboardEmptyKey: string;
  advisorOpportunityKey: string;
  advisorEmptyKey: string;
  analystSectionNote: TextRef;
  frameEvidence(fact: string, direction: LongEvidenceDirection): TextRef;
  analystCoverage(count: number, recommendation: string): TextRef;
  decisionHeader(decision: Decision): DecisionHeader;
  summary(context: NarrativeContext): TextRef[];
  decisionReason(context: NarrativeContext): TextRef;
  gap(gapBps: number | null): DirectionalText;
  dcfTone(dcfValueCents: number, marketPriceCents: number): DirectionalTone;
  technicalActionKey(verdict: TechnicalVerdict): string;
  technicalSignals<T>(bullish: T[], bearish: T[]): TechnicalSignalPerspective<T>;
  insiderSignalKey(kind: "strongBuy" | "buy" | "heavySell"): string;
}

export function isScoringModelId(value: string | null): value is ScoringModelId {
  return value != null && (SCORING_MODELS as readonly string[]).includes(value);
}

export function scoringDimensionsTooltipKey(model: ScoringModelId): string {
  if (model === "aggressive_v2") return "col.trio3.tooltip";
  if (model === "short_v3") return "presentation.short.col.trio.tooltip";
  return "col.trio.tooltip";
}

export function renderText(
  ref: TextRef,
  translate: (key: string, vars?: Record<string, string | number>) => string,
): string {
  return translate(ref.key, ref.vars);
}

function gapValue(gapBps: number): string {
  return Math.abs(gapBps / 100).toFixed(1);
}

const LONG_DECISIONS: Record<Decision, DecisionHeader> = {
  Act: { emoji: "✅", color: "#22c55e", titleKey: "analysis.goodMoment" },
  Watch: { emoji: "👁️", color: "#f59e0b", titleKey: "analysis.watching" },
  Avoid: { emoji: "🚫", color: "#ef4444", titleKey: "analysis.avoid" },
};

const SHORT_DECISIONS: Record<Decision, DecisionHeader> = {
  Act: { emoji: "📉", color: "#f43f5e", titleKey: "presentation.short.analysis.act" },
  Watch: { emoji: "⏳", color: "#f59e0b", titleKey: "presentation.short.analysis.watch" },
  Avoid: { emoji: "🛡️", color: "#94a3b8", titleKey: "presentation.short.analysis.avoid" },
};

const LONG_TECHNICAL_ACTIONS: Record<TechnicalVerdict, string> = {
  "Strong Bullish": "ts.action.strongBull",
  "Mildly Bullish": "ts.action.mildBull",
  "Neutral": "ts.action.neutral",
  "Mildly Bearish": "ts.action.mildBear",
  "Strong Bearish": "ts.action.strongBear",
  "Insufficient data": "ts.action.insufficient",
};

const SHORT_TECHNICAL_ACTIONS: Record<TechnicalVerdict, string> = {
  "Strong Bullish": "presentation.short.tech.action.strongBull",
  "Mildly Bullish": "presentation.short.tech.action.mildBull",
  "Neutral": "presentation.short.tech.action.neutral",
  "Mildly Bearish": "presentation.short.tech.action.mildBear",
  "Strong Bearish": "presentation.short.tech.action.strongBear",
  "Insufficient data": "presentation.short.tech.action.insufficient",
};

const longState: ScoringPresentationState = {
  side: "long",
  isShort: false,
  analystTargetLabelKey: "detail.analystTarget",
  gapLabelKey: "detail.gap",
  targetColumnKey: "col.target",
  gapColumnKey: "col.gap",
  setupTooltipKey: "col.setup.tooltip",
  trioTooltipKey: "col.trio.tooltip",
  loadingKey: "empty.loading",
  loadingHintKey: "empty.loading.hint",
  emptyKey: "empty.nomatch",
  emptyHintKey: "empty.nomatch.hint",
  bucketLabelKeys: [
    "analysis.fundamentals",
    "analysis.technical",
    "analysis.forecast",
    "analysis.regime",
  ],
  regimeBucketLabelKey: "analysis.regime",
  riskNotice: { key: "" },
  banner: null,
  setupLabelKey: (label) => `setup.${label}`,
  setupDescriptionKey: (label) => `setup.${label}.desc`,
  setupIcon: (_label, defaultIcon) => defaultIcon,
  dashboardOpportunityKey: "dash.opportunities",
  dashboardEmptyKey: "dash.noOpportunities",
  advisorOpportunityKey: "advisor.opportunities",
  advisorEmptyKey: "advisor.opportunities.empty",
  analystSectionNote: { key: "" },
  frameEvidence: (fact) => ({ key: "presentation.evidence.raw", vars: { fact } }),
  analystCoverage: (n, rec) => ({ key: "ia.fore.coverage", vars: { n, rec } }),
  decisionHeader: (decision) => LONG_DECISIONS[decision],
  summary(context) {
    if (context.assetType === "crypto" || context.assetType === "etf") {
      const key = context.decision === "Act" ? "presentation.long.summary.act.technical"
        : context.decision === "Watch" ? "presentation.long.summary.watch.technical"
          : "presentation.long.summary.avoid.technical";
      return [{ key, vars: { name: context.name, cs: context.compositeScore } }];
    }
    if (context.decision === "Act") {
      const key = context.regimeScoringEnabled ? "ia.summary.act.regime" : "ia.summary.act";
      const refs: TextRef[] = [{ key, vars: { name: context.name, cs: context.compositeScore } }];
      if (context.gapBps != null && context.gapBps >= 1000) {
        refs.push({ key: "ia.summary.act.gap", vars: { gap: gapValue(context.gapBps) } });
      }
      if (context.regimeScoringEnabled && context.regimeScore != null) {
        refs.push({
          key: "ia.summary.regime.fit",
          vars: { rs: context.regimeScore },
        });
      }
      return refs;
    }
    if (context.decision === "Watch") {
      const key = context.regimeScoringEnabled ? "ia.summary.watch.regime" : "ia.summary.watch";
      return [{ key, vars: { name: context.name, cs: context.compositeScore } }];
    }
    return [{
      key: context.regimeScoringEnabled
        ? "presentation.long.summary.avoid.regime"
        : "presentation.long.summary.avoid",
      vars: { name: context.name, cs: context.compositeScore },
    }];
  },
  decisionReason(context): TextRef {
    const technicalOnly = context.assetType === "crypto" || context.assetType === "etf";
    if (technicalOnly) {
      if (context.technicalScore == null) return { key: "presentation.long.reason.technical.insufficient" };
      const key = context.decision === "Act" ? "reason.act.crypto"
        : context.decision === "Watch" ? "reason.watch.crypto" : "reason.avoid.crypto";
      return { key, vars: { ts: context.technicalScore ?? 0 } };
    }
    if (context.decision === "Avoid") {
      if (context.gapBps == null) return { key: "reason.avoid.noTarget" };
      if (context.gapBps <= 0) return { key: "reason.avoid.gap", vars: { gap: gapValue(context.gapBps) } };
      if (context.confidence === "Low") return { key: "reason.avoid.confLow" };
      return {
        key: context.regimeScoringEnabled ? "reason.avoid.score.regime" : "reason.avoid.score",
        vars: { cs: context.compositeScore },
      };
    }
    if (context.decision === "Watch") {
      return {
        key: context.regimeScoringEnabled ? "reason.watch.regime" : "reason.watch",
        vars: { cs: context.compositeScore },
      };
    }
    if (context.gapBps != null && context.gapBps > 0) {
      return {
        key: context.regimeScoringEnabled ? "reason.act.regime" : "reason.act",
        vars: { cs: context.compositeScore, gap: gapValue(context.gapBps) },
      };
    }
    return {
      key: context.regimeScoringEnabled ? "reason.act.noTarget.regime" : "reason.act.noTarget",
      vars: { cs: context.compositeScore },
    };
  },
  gap(gapBps) {
    if (gapBps == null || !Number.isFinite(gapBps)) return { text: { key: "presentation.long.gap.noTarget" }, compactText: { key: "presentation.long.gap.noTarget.compact" }, tone: "neutral", marker: "—" };
    if (gapBps === 0) return { text: { key: "presentation.long.gap.flat" }, compactText: { key: "presentation.long.gap.flat.compact" }, tone: "neutral", marker: "—" };
    if (gapBps > 0) {
      return {
        text: { key: "presentation.long.gap.upside", vars: { gap: gapValue(gapBps) } },
        compactText: { key: "presentation.long.gap.upside.compact", vars: { gap: gapValue(gapBps) } },
        tone: gapBps >= 1000 ? "favorable" : "neutral",
        marker: "▲",
      };
    }
    return {
      text: { key: "presentation.long.gap.aboveTarget", vars: { gap: gapValue(gapBps) } },
      compactText: { key: "presentation.long.gap.aboveTarget.compact", vars: { gap: gapValue(gapBps) } },
      tone: "adverse",
      marker: "▼",
    };
  },
  dcfTone: (dcf, market) => dcf > market ? "favorable" : dcf < market ? "adverse" : "neutral",
  technicalActionKey: (verdict) => LONG_TECHNICAL_ACTIONS[verdict],
  technicalSignals: (bullish, bearish) => ({
    supporting: bullish,
    risks: bearish,
    supportingLabelKey: "tech.proLabel",
    risksLabelKey: "tech.conLabel",
    noSupportingKey: "tech.noProSignals",
    noRisksKey: "tech.noConSignals",
  }),
  insiderSignalKey: (kind) => ({
    strongBuy: "ia.fore.insiderBuys",
    buy: "ia.fore.insiderBuy",
    heavySell: "ia.fore.insiderSells",
  })[kind],
};

const shortState: ScoringPresentationState = {
  side: "short",
  isShort: true,
  analystTargetLabelKey: "presentation.short.analystTarget",
  gapLabelKey: "presentation.short.gapLabel",
  targetColumnKey: "presentation.short.col.target",
  gapColumnKey: "presentation.short.col.gap",
  setupTooltipKey: "presentation.short.col.setup.tooltip",
  trioTooltipKey: "presentation.short.col.trio.tooltip",
  loadingKey: "presentation.short.empty.loading",
  loadingHintKey: "presentation.short.empty.loading.hint",
  emptyKey: "presentation.short.empty.nomatch",
  emptyHintKey: "presentation.short.empty.nomatch.hint",
  bucketLabelKeys: [
    "presentation.short.bucket.fundamentals",
    "presentation.short.bucket.technical",
    "presentation.short.bucket.forecast",
    "presentation.short.bucket.regime",
  ],
  regimeBucketLabelKey: "presentation.short.bucket.regime",
  riskNotice: { key: "presentation.short.risk" },
  banner: {
    tagKey: "presentation.short.banner.tag",
    textKey: "presentation.short.banner.text",
  },
  setupLabelKey: (label) => `setup.short.${shortSetupToken(label)}`,
  setupDescriptionKey: (label) => `setup.short.${shortSetupToken(label)}.desc`,
  setupIcon(label) {
    const token = shortSetupToken(label);
    if (token === "StrongBuy") return "▼▼▼";
    if (token === "Buy") return "▼▼";
    if (token === "Accumulate") return "▼";
    if (token === "Watch") return "◆";
    if (token === "Hold") return "—";
    if (token === "Avoid") return "▲";
    return "▲▲▲";
  },
  dashboardOpportunityKey: "presentation.short.dashboard.opportunities",
  dashboardEmptyKey: "presentation.short.dashboard.empty",
  advisorOpportunityKey: "presentation.short.advisor.opportunities",
  advisorEmptyKey: "presentation.short.advisor.empty",
  analystSectionNote: { key: "presentation.short.analyst.note" },
  frameEvidence(fact, direction) {
    const key = direction === "positive" ? "presentation.short.evidence.risk"
      : direction === "negative" ? "presentation.short.evidence.support" : "presentation.short.evidence.neutral";
    return { key, vars: { fact } };
  },
  analystCoverage: (n, rec) => ({ key: "presentation.short.analyst.coverage", vars: { n, rec } }),
  decisionHeader: (decision) => SHORT_DECISIONS[decision],
  summary(context) {
    if (context.assetType === "crypto" || context.assetType === "etf") {
      const key = context.decision === "Act" ? "presentation.short.summary.act.technical"
        : context.decision === "Watch" ? "presentation.short.summary.watch.technical"
          : "presentation.short.summary.avoid.technical";
      return [{ key, vars: { name: context.name, cs: context.compositeScore } }];
    }
    const baseKey = context.decision === "Act" ? "presentation.short.summary.act"
      : context.decision === "Watch" ? "presentation.short.summary.watch" : "presentation.short.summary.avoid";
    const key = context.regimeScoringEnabled ? `${baseKey}.regime` : baseKey;
    const refs: TextRef[] = [{ key, vars: { name: context.name, cs: context.compositeScore } }];
    if (context.regimeScoringEnabled && context.regimeScore != null) {
      refs.push({ key: "presentation.short.summary.regime.fit", vars: { rs: context.regimeScore } });
    }
    if (context.confidence === "Low") refs.push({ key: "presentation.short.summary.lowConfidence" });
    if (context.gapBps == null) refs.push({ key: "presentation.short.summary.noTarget" });
    else if (context.gapBps < 0) refs.push({ key: "presentation.short.summary.targetSupport", vars: { gap: gapValue(context.gapBps) } });
    else if (context.gapBps > 0) refs.push({ key: "presentation.short.summary.targetRisk", vars: { gap: gapValue(context.gapBps) } });
    return refs;
  },
  decisionReason(context): TextRef {
    const technicalOnly = context.assetType === "crypto" || context.assetType === "etf";
    if (technicalOnly) {
      if (context.technicalScore == null) return { key: "presentation.short.reason.technical.insufficient" };
      const key = context.decision === "Act" ? "presentation.short.reason.act.technical"
        : context.decision === "Watch" ? "presentation.short.reason.watch.technical"
          : "presentation.short.reason.avoid.technical";
      return { key, vars: { ts: context.technicalScore ?? 0 } };
    }
    const baseKey = context.decision === "Act" ? "presentation.short.reason.act"
      : context.decision === "Watch" ? "presentation.short.reason.watch" : "presentation.short.reason.avoid";
    const key = context.regimeScoringEnabled ? `${baseKey}.regime` : baseKey;
    return { key, vars: { cs: context.compositeScore } };
  },
  gap(gapBps) {
    if (gapBps == null || !Number.isFinite(gapBps)) return { text: { key: "presentation.short.gap.noTarget" }, compactText: { key: "presentation.short.gap.noTarget.compact" }, tone: "neutral", marker: "—" };
    if (gapBps < 0) {
      return {
        text: { key: "presentation.short.gap.support", vars: { gap: gapValue(gapBps) } },
        compactText: { key: "presentation.short.gap.support.compact", vars: { gap: gapValue(gapBps) } },
        tone: "favorable",
        marker: "▼",
      };
    }
    if (gapBps > 0) {
      return {
        text: { key: "presentation.short.gap.risk", vars: { gap: gapValue(gapBps) } },
        compactText: { key: "presentation.short.gap.risk.compact", vars: { gap: gapValue(gapBps) } },
        tone: "adverse",
        marker: "▲",
      };
    }
    return { text: { key: "presentation.short.gap.flat" }, compactText: { key: "presentation.short.gap.flat.compact" }, tone: "neutral", marker: "—" };
  },
  dcfTone: (dcf, market) => dcf < market ? "favorable" : dcf > market ? "adverse" : "neutral",
  technicalActionKey: (verdict) => SHORT_TECHNICAL_ACTIONS[verdict],
  technicalSignals: (bullish, bearish) => ({
    supporting: bearish,
    risks: bullish,
    supportingLabelKey: "presentation.short.tech.supporting",
    risksLabelKey: "presentation.short.tech.risks",
    noSupportingKey: "presentation.short.tech.noSupporting",
    noRisksKey: "presentation.short.tech.noRisks",
  }),
  insiderSignalKey: (kind) => ({
    strongBuy: "presentation.short.insiderBuys",
    buy: "presentation.short.insiderBuy",
    heavySell: "presentation.short.insiderSells",
  })[kind],
};

export function getScoringPresentation(model: ScoringModelId): ScoringPresentationState {
  return model === "short_v3" ? shortState : longState;
}

function shortSetupToken(label: SetupLabel): "StrongBuy" | "Buy" | "Accumulate" | "Watch" | "Hold" | "Avoid" | "StrongAvoid" {
  if (label === "StrongBuy") return "StrongBuy";
  if (label === "Buy") return "Buy";
  if (label === "StrongAccumulate" || label === "Accumulate") return "Accumulate";
  if (label === "Watch") return "Watch";
  if (label === "HoldWait" || label === "Hold" || label === "Neutral") return "Hold";
  if (label === "Caution" || label === "Distribute" || label === "Avoid") return "Avoid";
  return "StrongAvoid";
}
