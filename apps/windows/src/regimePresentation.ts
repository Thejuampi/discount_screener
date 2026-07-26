import type {
  OpportunityRow,
  RegimeCause,
  RegimeCauseEffect,
  RegimeCauseFactor,
  RegimeScoreStatus,
  RegimeUnavailableReason,
} from "./api";
import type {
  DirectionalTone,
  ScoringModelId,
  ScoringSide,
} from "./scoringPresentation";

type RegimeRowLike = Pick<
  OpportunityRow,
  | "asset_type"
  | "regime_status"
  | "regime_scoring_enabled"
  | "regime_score"
  | "regime_signals"
  | "regime_causes"
  | "regime_unavailable_reason"
  | "composite_score_base"
  | "composite_score"
> & {
  scoring_model: ScoringModelId;
};

export type RegimeClassification = DirectionalTone;

export interface RegimePresentation {
  status: RegimeScoreStatus;
  visible: boolean;
  muted: boolean;
  side: ScoringSide;
  score: number | null;
  impact: number;
  classification: RegimeClassification | null;
  bucketKey: string;
  statusKey: string;
  impactKey: string;
  /** Typed causes preferred by the narrative layer. */
  typedCauses: RegimeCause[];
  /** Legacy string causes (compat / tests). */
  causes: string[];
  unavailableReason: RegimeUnavailableReason | null;
  composition: {
    base: string;
    context: string;
    final: string;
  };
}

const VALID_STATUSES = new Set<RegimeScoreStatus>([
  "Included",
  "Disabled",
  "Unavailable",
  "NotApplicable",
]);

const KNOWN_FACTORS = new Set<string>([
  "Quality",
  "LowBeta",
  "Value",
  "OversoldQual",
  "Extension",
  "Trend",
  "Defensive",
  "Growth",
  "Liquidity",
  "GeneralFit",
  "Neutral",
  "RegimeFit",
  "RegimeNeutral",
]);

export function normalizeRegimeStatus(
  row: Pick<
    RegimeRowLike,
    "scoring_model" | "asset_type" | "regime_status" | "regime_scoring_enabled" | "regime_score"
  >,
): RegimeScoreStatus {
  const applicable =
    (row.scoring_model === "aggressive_v3" || row.scoring_model === "short_v3")
    && row.asset_type === "stock";
  if (!applicable) return "NotApplicable";

  if (row.regime_status && VALID_STATUSES.has(row.regime_status)) {
    if (row.regime_status === "Included" && row.regime_score == null) {
      return "Unavailable";
    }
    if (row.regime_status === "NotApplicable") {
      return "Unavailable";
    }
    return row.regime_status;
  }
  if (row.regime_scoring_enabled === false) return "Disabled";
  if (row.regime_score != null || row.regime_scoring_enabled === true && row.regime_score != null) {
    return "Included";
  }
  return "Unavailable";
}

function parseLegacyFactor(tag: string): RegimeCauseFactor {
  if (tag === "RegimeFit") return "GeneralFit";
  if (tag === "RegimeNeutral") return "Neutral";
  if (KNOWN_FACTORS.has(tag) && tag !== "RegimeFit" && tag !== "RegimeNeutral") {
    return tag as RegimeCauseFactor;
  }
  return "Other";
}

/** Normalize a legacy `+Quality` / `−Extension` / `RegimeNeutral` string. */
export function parseLegacySignal(signal: string): RegimeCause {
  const cleaned = signal.replace(/^[+−–—-]\s*/, "").trim();
  const negative = /^[-−–—]/.test(signal);
  const neutral = cleaned === "RegimeNeutral" || cleaned === "Neutral";
  const factor = parseLegacyFactor(cleaned);
  const effect: RegimeCauseEffect = neutral
    ? "Neutral"
    : negative
      ? "Risk"
      : "Support";
  return { factor, effect, contribution_bps: 0 };
}

export function normalizeRegimeCauses(row: RegimeRowLike): RegimeCause[] {
  if (row.regime_causes && row.regime_causes.length > 0) {
    return row.regime_causes.slice(0, 3).map((c) => {
      const raw = String(c.factor);
      let factor: RegimeCauseFactor;
      if (raw === "RegimeFit" || raw === "GeneralFit") factor = "GeneralFit";
      else if (raw === "RegimeNeutral" || raw === "Neutral") factor = "Neutral";
      else if (KNOWN_FACTORS.has(raw) || raw === "Other") factor = raw as RegimeCauseFactor;
      else factor = "Other";
      return {
        factor,
        effect: c.effect,
        contribution_bps: c.contribution_bps ?? 0,
      };
    });
  }
  return (row.regime_signals ?? []).slice(0, 3).map(parseLegacySignal);
}

/** @deprecated Prefer marketContextNarrative evidence; kept for legacy tests/callers. */
export function renderRegimeCause(
  signal: string,
  side: ScoringSide,
  translate: (key: string, vars?: Record<string, string | number>) => string,
): string {
  const cleaned = signal.replace(/^[+−–—-]\s*/, "").trim();
  const negative = /^[-−–—]/.test(signal);
  const neutral = cleaned === "RegimeNeutral";
  const causeKey = `presentation.context.cause.${cleaned}`;
  const key = neutral
    ? `presentation.${side}.context.neutral`
    : negative
      ? `presentation.${side}.context.risk`
      : `presentation.${side}.context.support`;
  const translatedCause = translate(causeKey);
  const cause = translatedCause === causeKey ? cleaned : translatedCause;
  return translate(key, { cause });
}

export function classifyRegimeScore(score: number): RegimeClassification {
  if (score >= 15) return "favorable";
  if (score <= -15) return "adverse";
  return "neutral";
}

function signed(value: number): string {
  return value > 0 ? `+${value}` : `${value}`;
}

function statusKeyFor(
  status: RegimeScoreStatus,
  reason: RegimeUnavailableReason | null,
): string {
  if (status === "Unavailable") {
    if (reason === "MarketReadingUnavailable") {
      return "analysis.marketContext.status.unavailable.marketReading";
    }
    if (reason === "InsufficientAssetData") {
      return "analysis.marketContext.status.unavailable.insufficientData";
    }
    if (reason === "Unknown") {
      return "analysis.marketContext.status.unavailable.unknown";
    }
    return "analysis.marketContext.status.unavailable";
  }
  return `analysis.marketContext.status.${status[0].toLowerCase()}${status.slice(1)}`;
}

export function createRegimePresentation(row: RegimeRowLike): RegimePresentation {
  const status = normalizeRegimeStatus(row);
  const side: ScoringSide = row.scoring_model === "short_v3" ? "short" : "long";
  const base = Number.isFinite(row.composite_score_base)
    ? row.composite_score_base as number
    : row.composite_score;
  const impact = row.composite_score - base;
  const score = status === "Included" && row.regime_score != null ? row.regime_score : null;
  const direction = impact > 0 ? "raised" : impact < 0 ? "reduced" : "unchanged";
  const typedCauses = status === "Included" ? normalizeRegimeCauses(row) : [];
  const unavailableReason =
    status === "Unavailable"
      ? (row.regime_unavailable_reason ?? null)
      : null;

  return {
    status,
    visible: status !== "NotApplicable",
    muted: status === "Disabled" || status === "Unavailable",
    side,
    score,
    impact,
    classification: score == null ? null : classifyRegimeScore(score),
    bucketKey: side === "short"
      ? "presentation.short.bucket.regime"
      : "analysis.marketContext.title",
    statusKey: statusKeyFor(status, unavailableReason),
    impactKey: `analysis.marketContext.impact.${side}.${direction}`,
    typedCauses,
    causes: typedCauses.map((c) => {
      if (c.effect === "Support") return `+${legacyTag(c.factor)}`;
      if (c.effect === "Risk") return `−${legacyTag(c.factor)}`;
      return legacyTag(c.factor);
    }),
    unavailableReason,
    composition: {
      base: `${base}`,
      context: signed(impact),
      final: `${row.composite_score}`,
    },
  };
}

function legacyTag(factor: RegimeCauseFactor): string {
  if (factor === "GeneralFit") return "RegimeFit";
  if (factor === "Neutral") return "RegimeNeutral";
  if (factor === "Other") return "Other";
  return factor;
}

export function regimeRowInput(
  row: OpportunityRow,
  scoringModel: ScoringModelId,
): RegimeRowLike {
  return { ...row, scoring_model: scoringModel };
}
