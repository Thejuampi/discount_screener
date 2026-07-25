/**
 * Pure presentation layer for the Market Context card.
 * Builds summary, evidence bullets, and chips from typed causes + SymbolDetail.
 * No JSX.
 */
import type { RegimePresentation } from "./regimePresentation";
import type { ScoringSide } from "./scoringPresentation";

export type RegimeCauseFactor =
  | "Quality"
  | "LowBeta"
  | "Value"
  | "OversoldQual"
  | "Extension"
  | "Trend"
  | "Defensive"
  | "Growth"
  | "Liquidity"
  | "GeneralFit"
  | "Neutral"
  | "Other";

export type RegimeCauseEffect = "Support" | "Risk" | "Neutral";

export interface RegimeCause {
  factor: RegimeCauseFactor;
  effect: RegimeCauseEffect;
  contribution_bps: number;
}

/** Minimal detail shape used for evidence metrics (avoids importing api.ts in Node tests). */
export interface MarketContextDetailMetrics {
  market_price_cents?: number | null;
  fundamentals?: {
    sector_name?: string | null;
    market_cap_dollars?: number | null;
    forward_pe_hundredths?: number | null;
    return_on_equity_bps?: number | null;
    debt_to_equity_hundredths?: number | null;
    free_cash_flow_dollars?: number | null;
    beta_millis?: number | null;
  } | null;
  chart_summary?: {
    rsi?: number | null;
    pos_52w_pct?: number | null;
    volume_ratio?: number | null;
    ema50_cents?: number | null;
    ema200_cents?: number | null;
  } | null;
}

export type TranslateFn = (key: string, vars?: Record<string, string | number>) => string;

function formatBillions(dollars: number | null | undefined): string {
  if (dollars == null || !Number.isFinite(dollars)) return "—";
  if (Math.abs(dollars) >= 1e9) return `$${(dollars / 1e9).toFixed(1)}B`;
  if (Math.abs(dollars) >= 1e6) return `$${(dollars / 1e6).toFixed(0)}M`;
  return `$${dollars.toFixed(0)}`;
}

function formatRoe(bps: number | null | undefined): string {
  if (bps == null) return "—";
  return `${(bps / 100).toFixed(1)}%`;
}

export interface MarketContextChip {
  factor: RegimeCauseFactor;
  label: string;
  effect: RegimeCauseEffect;
  /** Visible + / − / · */
  mark: string;
  ariaLabel: string;
  className: "sig-pos" | "sig-neg" | "";
}

export interface MarketContextNarrative {
  scoreDisplay: string | null;
  classificationKey: string | null;
  classificationLabel: string | null;
  summary: string | null;
  evidence: string[];
  chips: MarketContextChip[];
  tooltipKey: string;
  statusMessage: string | null;
  muted: boolean;
  tone: "favorable" | "adverse" | "neutral" | "muted";
}

function formatScore(score: number): string {
  return score > 0 ? `+${score}` : `${score}`;
}

function classificationKey(side: ScoringSide, tone: "favorable" | "neutral" | "adverse"): string {
  if (side === "short") {
    if (tone === "favorable") return "analysis.marketContext.bucket.short.favorable";
    if (tone === "adverse") return "analysis.marketContext.bucket.short.adverse";
    return "analysis.marketContext.bucket.short.neutral";
  }
  if (tone === "adverse") return "analysis.marketContext.bucket.adverse";
  if (tone === "favorable") return "analysis.marketContext.bucket.favorable";
  return "analysis.marketContext.bucket.neutral";
}

function summaryKey(side: ScoringSide, tone: "favorable" | "neutral" | "adverse"): string {
  return `analysis.marketContext.summary.${side}.${tone}`;
}

function chipLabelKey(factor: RegimeCauseFactor): string {
  return `analysis.marketContext.chip.${factor}`;
}

function chipMark(effect: RegimeCauseEffect): string {
  if (effect === "Support") return "+";
  if (effect === "Risk") return "−";
  return "·";
}

function chipClass(effect: RegimeCauseEffect): "sig-pos" | "sig-neg" | "" {
  if (effect === "Support") return "sig-pos";
  if (effect === "Risk") return "sig-neg";
  return "";
}

function ariaEffectKey(side: ScoringSide, effect: RegimeCauseEffect): string {
  if (side === "short") {
    if (effect === "Support") return "analysis.marketContext.chip.aria.short.support";
    if (effect === "Risk") return "analysis.marketContext.chip.aria.short.risk";
    return "analysis.marketContext.chip.aria.neutral";
  }
  if (effect === "Support") return "analysis.marketContext.chip.aria.support";
  if (effect === "Risk") return "analysis.marketContext.chip.aria.risk";
  return "analysis.marketContext.chip.aria.neutral";
}

function metricsFromDetail(detail: MarketContextDetailMetrics | null | undefined) {
  const f = detail?.fundamentals;
  const c = detail?.chart_summary;
  return {
    fcf: f?.free_cash_flow_dollars ?? null,
    roeBps: f?.return_on_equity_bps ?? null,
    deHundredths: f?.debt_to_equity_hundredths ?? null,
    peHundredths: f?.forward_pe_hundredths ?? null,
    betaMillis: f?.beta_millis ?? null,
    sector: f?.sector_name ?? null,
    marketCap: f?.market_cap_dollars ?? null,
    rsi: c?.rsi ?? null,
    pos52: c?.pos_52w_pct ?? null,
    volumeRatio: c?.volume_ratio ?? null,
    price: detail?.market_price_cents ?? null,
    ema50: c?.ema50_cents ?? null,
    ema200: c?.ema200_cents ?? null,
  };
}

function buildEvidenceLine(
  cause: RegimeCause,
  side: ScoringSide,
  detail: MarketContextDetailMetrics | null | undefined,
  t: TranslateFn,
): string {
  const m = metricsFromDetail(detail);
  const support = cause.effect === "Support";
  const risk = cause.effect === "Risk";

  switch (cause.factor) {
    case "Quality": {
      const facts: string[] = [];
      if (m.fcf != null) {
        facts.push(
          m.fcf > 0
            ? t("analysis.marketContext.metric.fcfPositive", { fcf: formatBillions(m.fcf) })
            : t("analysis.marketContext.metric.fcf", { fcf: formatBillions(m.fcf) }),
        );
      }
      if (m.roeBps != null && facts.length < 2) {
        facts.push(t("analysis.marketContext.metric.roe", { roe: formatRoe(m.roeBps) }));
      }
      if (m.deHundredths != null && facts.length < 2) {
        facts.push(t("analysis.marketContext.metric.de", {
          de: (m.deHundredths / 100).toFixed(1),
        }));
      }
      if (facts.length === 0) {
        return t(support
          ? "analysis.marketContext.evidence.Quality.support.fallback"
          : "analysis.marketContext.evidence.Quality.risk.fallback");
      }
      return t(support
        ? "analysis.marketContext.evidence.Quality.support"
        : "analysis.marketContext.evidence.Quality.risk", {
        facts: facts.join(t("analysis.marketContext.factsJoin")),
      });
    }
    case "LowBeta": {
      if (m.betaMillis != null) {
        const beta = (m.betaMillis / 1000).toFixed(2);
        return t(support
          ? "analysis.marketContext.evidence.LowBeta.support"
          : "analysis.marketContext.evidence.LowBeta.risk", { beta });
      }
      return t(support
        ? "analysis.marketContext.evidence.LowBeta.support.fallback"
        : "analysis.marketContext.evidence.LowBeta.risk.fallback");
    }
    case "Value": {
      if (m.peHundredths != null) {
        const pe = (m.peHundredths / 100).toFixed(1);
        return t(support
          ? "analysis.marketContext.evidence.Value.support"
          : "analysis.marketContext.evidence.Value.risk", { pe });
      }
      return t(support
        ? "analysis.marketContext.evidence.Value.support.fallback"
        : "analysis.marketContext.evidence.Value.risk.fallback");
    }
    case "OversoldQual": {
      const facts: string[] = [];
      if (m.rsi != null) facts.push(t("analysis.marketContext.metric.rsi", { rsi: Math.round(m.rsi) }));
      if (m.fcf != null && m.fcf > 0 && facts.length < 2) {
        facts.push(t("analysis.marketContext.metric.fcfPositive", { fcf: formatBillions(m.fcf) }));
      }
      if (facts.length === 0) {
        return t(support
          ? "analysis.marketContext.evidence.OversoldQual.support.fallback"
          : "analysis.marketContext.evidence.OversoldQual.risk.fallback");
      }
      return t(support
        ? "analysis.marketContext.evidence.OversoldQual.support"
        : "analysis.marketContext.evidence.OversoldQual.risk", {
        facts: facts.join(t("analysis.marketContext.factsJoin")),
      });
    }
    case "Extension": {
      const facts: string[] = [];
      if (m.rsi != null) facts.push(t("analysis.marketContext.metric.rsi", { rsi: Math.round(m.rsi) }));
      if (m.pos52 != null && facts.length < 2) {
        facts.push(t("analysis.marketContext.metric.pos52", { pos: Math.round(m.pos52) }));
      }
      if (side === "short") {
        if (facts.length === 0) {
          return t(support
            ? "analysis.marketContext.evidence.Extension.short.support.fallback"
            : "analysis.marketContext.evidence.Extension.short.risk.fallback");
        }
        return t(support
          ? "analysis.marketContext.evidence.Extension.short.support"
          : "analysis.marketContext.evidence.Extension.short.risk", {
          facts: facts.join(t("analysis.marketContext.factsJoin")),
        });
      }
      if (facts.length === 0) {
        return t(support
          ? "analysis.marketContext.evidence.Extension.long.support.fallback"
          : "analysis.marketContext.evidence.Extension.long.risk.fallback");
      }
      return t(support
        ? "analysis.marketContext.evidence.Extension.long.support"
        : "analysis.marketContext.evidence.Extension.long.risk", {
        facts: facts.join(t("analysis.marketContext.factsJoin")),
      });
    }
    case "Trend": {
      // Frame as environment fit — never restate EMA/MACD structure (Technical owns that).
      if (side === "short") {
        return t(support
          ? "analysis.marketContext.evidence.Trend.short.support"
          : "analysis.marketContext.evidence.Trend.short.risk");
      }
      return t(support
        ? "analysis.marketContext.evidence.Trend.long.support"
        : "analysis.marketContext.evidence.Trend.long.risk");
    }
    case "Defensive": {
      const sector = m.sector ?? t("analysis.marketContext.metric.sectorUnknown");
      return t("analysis.marketContext.evidence.Defensive", { sector });
    }
    case "Growth": {
      const sector = m.sector ?? t("analysis.marketContext.metric.sectorUnknown");
      if (side === "short") {
        return t("analysis.marketContext.evidence.Growth.short", { sector });
      }
      return t("analysis.marketContext.evidence.Growth.long", { sector });
    }
    case "Liquidity": {
      const facts: string[] = [];
      if (m.marketCap != null) {
        facts.push(t("analysis.marketContext.metric.marketCap", { cap: formatBillions(m.marketCap) }));
      }
      if (m.volumeRatio != null && facts.length < 2) {
        facts.push(t("analysis.marketContext.metric.volume", {
          volume: (m.volumeRatio / 100).toFixed(1),
        }));
      }
      if (facts.length === 0) {
        return t(support
          ? "analysis.marketContext.evidence.Liquidity.support.fallback"
          : "analysis.marketContext.evidence.Liquidity.risk.fallback");
      }
      return t(support
        ? "analysis.marketContext.evidence.Liquidity.support"
        : "analysis.marketContext.evidence.Liquidity.risk", {
        facts: facts.join(t("analysis.marketContext.factsJoin")),
      });
    }
    case "GeneralFit":
      return t(support
        ? "analysis.marketContext.evidence.GeneralFit.support"
        : risk
          ? "analysis.marketContext.evidence.GeneralFit.risk"
          : "analysis.marketContext.evidence.Neutral");
    case "Neutral":
      return t("analysis.marketContext.evidence.Neutral");
    case "Other":
    default:
      return t("analysis.marketContext.evidence.Other");
  }
}

function buildChips(
  causes: RegimeCause[],
  side: ScoringSide,
  t: TranslateFn,
): MarketContextChip[] {
  return causes.map((c) => {
    const label = t(chipLabelKey(c.factor));
    const aria = t(ariaEffectKey(side, c.effect), { label });
    return {
      factor: c.factor,
      label,
      effect: c.effect,
      mark: chipMark(c.effect),
      ariaLabel: aria,
      className: chipClass(c.effect),
    };
  });
}

/**
 * Build the full narrative view model for the Market Context card.
 * When `detail` is missing, evidence falls back to qualitative factor lines.
 */
export function buildMarketContextNarrative(
  marketContext: RegimePresentation,
  detail: MarketContextDetailMetrics | null | undefined,
  t: TranslateFn,
): MarketContextNarrative {
  const tooltipKey = "analysis.marketContext.tooltip";

  if (marketContext.status !== "Included" || marketContext.score == null) {
    return {
      scoreDisplay: null,
      classificationKey: null,
      classificationLabel: null,
      summary: null,
      evidence: [],
      chips: [],
      tooltipKey,
      statusMessage: t(marketContext.statusKey),
      muted: true,
      tone: "muted",
    };
  }

  const tone = marketContext.classification ?? "neutral";
  const classKey = classificationKey(marketContext.side, tone);
  const evidence = marketContext.typedCauses
    .map((c) => buildEvidenceLine(c, marketContext.side, detail, t))
    .filter((line) => line.length > 0)
    .slice(0, 3);

  return {
    scoreDisplay: formatScore(marketContext.score),
    classificationKey: classKey,
    classificationLabel: t(classKey),
    summary: t(summaryKey(marketContext.side, tone)),
    evidence,
    chips: buildChips(marketContext.typedCauses, marketContext.side, t),
    tooltipKey,
    statusMessage: null,
    muted: false,
    tone,
  };
}

