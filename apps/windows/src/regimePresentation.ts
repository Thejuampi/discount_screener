import type {
  OpportunityRow,
  RegimeScoreStatus,
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
  causes: string[];
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

export function createRegimePresentation(row: RegimeRowLike): RegimePresentation {
  const status = normalizeRegimeStatus(row);
  const side: ScoringSide = row.scoring_model === "short_v3" ? "short" : "long";
  const base = Number.isFinite(row.composite_score_base)
    ? row.composite_score_base as number
    : row.composite_score;
  const impact = row.composite_score - base;
  const score = status === "Included" && row.regime_score != null ? row.regime_score : null;
  const direction = impact > 0 ? "raised" : impact < 0 ? "reduced" : "unchanged";

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
    statusKey: `analysis.marketContext.status.${status[0].toLowerCase()}${status.slice(1)}`,
    impactKey: `analysis.marketContext.impact.${side}.${direction}`,
    causes: (row.regime_signals ?? []).slice(0, 3),
    composition: {
      base: `${base}`,
      context: signed(impact),
      final: `${row.composite_score}`,
    },
  };
}

export function regimeRowInput(
  row: OpportunityRow,
  scoringModel: ScoringModelId,
): RegimeRowLike {
  return { ...row, scoring_model: scoringModel };
}
