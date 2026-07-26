import { useMemo } from "react";
import { fmt } from "../api";
import type { OpportunityRow } from "../api";
import { useT } from "../i18n";
import { Sparkline } from "./Sparkline";
import {
  isFeedIncomplete,
  rankDashboardV2Sections,
  shouldShowStanceCounts,
  type DashboardV2Summary,
} from "../dashboardV2Ranking";
import type { ConditionalPlan, PlanStance } from "../conditionalPlan";
import { formatDollars, formatZone } from "../conditionalPlan";
import type { ScoringModelId } from "../scoringPresentation";
import { RegimeBanner } from "./RegimeBanner";

export type DashboardEdition = "legacy" | "v2";

interface Props {
  rows: OpportunityRow[];
  symbolsLoaded: number;
  symbolsTotal: number;
  scoringModel: ScoringModelId;
  regimeScoring: boolean;
  edition: DashboardEdition;
  onEditionChange: (e: DashboardEdition) => void;
  onSelectModel: (m: ScoringModelId) => void;
  onToggleRegime: () => void;
  onOpenSymbol: (s: string) => void;
}

const STANCE_CLASS: Record<PlanStance, string> = {
  ActNow: "dash-v2-stance--act",
  ScaleIn: "dash-v2-stance--scale",
  WaitZone: "dash-v2-stance--wait",
  Avoid: "dash-v2-stance--avoid",
};

export function DashboardV2Panel({
  rows,
  symbolsLoaded,
  symbolsTotal,
  scoringModel,
  regimeScoring,
  edition,
  onEditionChange,
  onSelectModel,
  onToggleRegime,
  onOpenSymbol,
}: Props) {
  const { t } = useT();
  const isShort = scoringModel === "short_v3";
  const sections = useMemo(
    () => rankDashboardV2Sections(rows, scoringModel, 6, 4, 3, 2),
    [rows, scoringModel],
  );
  const showCounts = shouldShowStanceCounts(rows.length, symbolsLoaded, symbolsTotal);
  const feedIncomplete = isFeedIncomplete(symbolsLoaded, symbolsTotal);
  const bootLoading = !showCounts;

  const displayName = localStorage.getItem("ds_display_name")?.trim();
  const sideLabel = isShort ? t("dash.v2.side.short") : t("dash.v2.side.long");

  return (
    <div className="congress-page dash-v2">
      <header className="congress-header dash-v2-header">
        <div>
          <h2 className="congress-title">
            {displayName ? `${t("dash.v2.title")}, ${displayName}` : t("dash.v2.title")}
          </h2>
          <p className="congress-subtitle">{t("dash.v2.subtitle")}</p>
        </div>
        <div className="dash-v2-controls">
          <div className="dash-v2-edition" role="group" aria-label={t("dash.edition.group")}>
            <button
              type="button"
              className={`scoring-segment__btn${edition === "legacy" ? " is-active" : ""}`}
              onClick={() => onEditionChange("legacy")}
            >
              {t("dash.edition.legacy")}
            </button>
            <button
              type="button"
              className={`scoring-segment__btn${edition === "v2" ? " is-active" : ""}`}
              onClick={() => onEditionChange("v2")}
            >
              {t("dash.edition.v2")}
            </button>
          </div>
          <div
            className={`scoring-segment${isShort ? " scoring-segment--short" : ""}`}
            role="radiogroup"
            aria-label={t("scoring.group")}
          >
            <button
              type="button"
              role="radio"
              aria-checked={!isShort}
              className={`scoring-segment__btn${!isShort ? " is-active" : ""}`}
              title={t("scoring.longV3.hint")}
              onClick={() => onSelectModel("aggressive_v3")}
            >
              {t("scoring.longV3")}
            </button>
            <button
              type="button"
              role="radio"
              aria-checked={isShort}
              className={`scoring-segment__btn is-short${isShort ? " is-active" : ""}`}
              title={t("scoring.short.hint")}
              onClick={() => onSelectModel("short_v3")}
            >
              {t("scoring.short")}
            </button>
          </div>
          <button
            type="button"
            className={`scoring-segment__btn${regimeScoring ? " is-active" : ""}`}
            title={t("scoring.regime.hint")}
            onClick={() => void onToggleRegime()}
          >
            {regimeScoring ? t("scoring.regime.on") : t("scoring.regime.off")}
          </button>
        </div>
      </header>

      <RegimeBanner />

      <section className="dash-v2-summary info-section" aria-live="polite">
        {bootLoading ? (
          <p className="dash-v2-muted">
            {t("empty.loading")} ({symbolsLoaded}/{symbolsTotal})
          </p>
        ) : (
          <>
            <div className="dash-v2-summary-block">
              <span className="dash-v2-summary-label">{t("dash.v2.section.market")}</span>
              <CountsLine summary={sections.market} t={t} />
            </div>
            <div className="dash-v2-summary-block">
              <span className="dash-v2-summary-label">{t("dash.v2.section.crypto")}</span>
              <CountsLine summary={sections.crypto} t={t} />
            </div>
            {feedIncomplete && (
              <p className="dash-v2-feed-hint">
                {t("dash.v2.feedPartial", { loaded: symbolsLoaded, total: symbolsTotal })}
              </p>
            )}
          </>
        )}
      </section>

      {/* 1) PRIMARY — opportunities first */}
      <PlanSection
        title={t("dash.v2.section.marketNow", { side: sideLabel })}
        plans={sections.market.actionable}
        bootLoading={bootLoading}
        emptyKey="dash.v2.empty.marketNow"
        emptyHintKey="dash.v2.empty.marketNowHint"
        emptyVars={{ side: sideLabel }}
        tone="primary"
        t={t}
        onOpen={onOpenSymbol}
      />

      <PlanSection
        title={t("dash.v2.section.cryptoNow", { side: sideLabel })}
        plans={sections.crypto.actionable}
        bootLoading={bootLoading}
        emptyKey="dash.v2.empty.cryptoNow"
        emptyHintKey="dash.v2.empty.cryptoNowHint"
        emptyVars={{ side: sideLabel }}
        tone="primary"
        t={t}
        onOpen={onOpenSymbol}
      />

      {/* 2) SECONDARY — wait / re-check later (only if any) */}
      {!bootLoading && sections.market.watchLater.length > 0 && (
        <PlanSection
          title={t("dash.v2.section.marketLater")}
          plans={sections.market.watchLater}
          bootLoading={false}
          emptyKey="dash.v2.empty.later"
          tone="secondary"
          t={t}
          onOpen={onOpenSymbol}
        />
      )}

      {!bootLoading && sections.crypto.watchLater.length > 0 && (
        <PlanSection
          title={t("dash.v2.section.cryptoLater")}
          plans={sections.crypto.watchLater}
          bootLoading={false}
          emptyKey="dash.v2.empty.later"
          tone="secondary"
          t={t}
          onOpen={onOpenSymbol}
        />
      )}
    </div>
  );
}

function CountsLine({
  summary,
  t,
}: {
  summary: DashboardV2Summary;
  t: (key: string, vars?: Record<string, string | number>) => string;
}) {
  return (
    <p className="dash-v2-counts">
      <span className="dash-v2-count dash-v2-count--act">
        {summary.act} {t("dash.v2.bucket.act")}
      </span>
      <span className="dash-v2-dot">·</span>
      <span className="dash-v2-count dash-v2-count--scale">
        {summary.scale} {t("dash.v2.bucket.scale")}
      </span>
      <span className="dash-v2-dot">·</span>
      <span className="dash-v2-count dash-v2-count--wait">
        {summary.wait} {t("dash.v2.bucket.wait")}
      </span>
      <span className="dash-v2-dot">·</span>
      <span className="dash-v2-count dash-v2-count--avoid">
        {summary.avoid} {t("dash.v2.bucket.avoid")}
      </span>
    </p>
  );
}

function PlanSection({
  title,
  plans,
  bootLoading,
  emptyKey,
  emptyHintKey,
  emptyVars,
  tone,
  t,
  onOpen,
}: {
  title: string;
  plans: ConditionalPlan[];
  bootLoading: boolean;
  emptyKey: string;
  emptyHintKey?: string;
  emptyVars?: Record<string, string | number>;
  tone: "primary" | "secondary";
  t: (key: string, vars?: Record<string, string | number>) => string;
  onOpen: (s: string) => void;
}) {
  return (
    <section className={`info-section dash-v2-section dash-v2-section--${tone}`}>
      <div className="dash-sec-head">
        <h3>{title}</h3>
      </div>
      {bootLoading ? (
        <p className="dash-v2-muted">{t("empty.loading")}</p>
      ) : plans.length === 0 ? (
        <div className="dash-v2-empty-block">
          <p className="dash-v2-muted">{t(emptyKey, emptyVars)}</p>
          {emptyHintKey && <p className="dash-v2-feed-hint">{t(emptyHintKey, emptyVars)}</p>}
        </div>
      ) : (
        <div className="dash-v2-plans">
          {plans.map((plan) => (
            <PlanCard key={plan.symbol} plan={plan} t={t} onOpen={onOpen} />
          ))}
        </div>
      )}
    </section>
  );
}

/** Edition toggle strip for legacy dashboard header. */
export function DashboardEditionToggle({
  edition,
  onEditionChange,
}: {
  edition: DashboardEdition;
  onEditionChange: (e: DashboardEdition) => void;
}) {
  const { t } = useT();
  return (
    <div className="dash-v2-edition" role="group" aria-label={t("dash.edition.group")}>
      <button
        type="button"
        className={`scoring-segment__btn${edition === "legacy" ? " is-active" : ""}`}
        onClick={() => onEditionChange("legacy")}
      >
        {t("dash.edition.legacy")}
      </button>
      <button
        type="button"
        className={`scoring-segment__btn${edition === "v2" ? " is-active" : ""}`}
        onClick={() => onEditionChange("v2")}
      >
        {t("dash.edition.v2")}
      </button>
    </div>
  );
}

function PlanCard({
  plan,
  t,
  onOpen,
}: {
  plan: ConditionalPlan;
  t: (key: string, vars?: Record<string, string | number>) => string;
  onOpen: (s: string) => void;
}) {
  const zone =
    plan.zoneLowCents != null && plan.zoneHighCents != null
      ? formatZone(plan.zoneLowCents, plan.zoneHighCents)
      : null;
  const headline = t(plan.headlineKey, plan.headlineVars);

  return (
    <article
      className={`dash-v2-card ${STANCE_CLASS[plan.stance]}`}
      onClick={() => onOpen(plan.symbol)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") onOpen(plan.symbol);
      }}
    >
      <div className="dash-v2-card-top">
        <strong>{plan.symbol}</strong>
        <span className={`dash-v2-badge ${STANCE_CLASS[plan.stance]}`}>
          {t(`dash.v2.stance.${plan.stance}`)}
        </span>
        {plan.pTouch20d != null && (
          <span className="dash-v2-meta" title={t("dash.v2.p20.hint")}>
            p20 {plan.pTouch20d}%
          </span>
        )}
        {zone && (
          <span className="dash-v2-zone" title={t("dash.v2.zone.hint")}>
            {zone}
            {plan.zoneConfidence && (
              <span className="dash-v2-zconf"> · {t(`dash.v2.zconf.${plan.zoneConfidence}`)}</span>
            )}
          </span>
        )}
      </div>
      <p className="dash-v2-headline">{headline}</p>
      {plan.spark.length > 1 && (
        <div className="dash-v2-spark">
          <Sparkline data={plan.spark} width={220} height={28} />
        </div>
      )}
      <ul className="dash-v2-evidence">
        {[...plan.caution, ...plan.support].slice(0, 3).map((ev, i) => (
          <li key={`${ev.code}-${i}`}>{t(ev.textKey, ev.vars)}</li>
        ))}
      </ul>
      <div className="dash-v2-card-foot">
        <span>{fmt.dollars(plan.marketPriceCents)}</span>
        {plan.invalidationCents != null && (
          <span className="dash-v2-inv">
            {t("dash.v2.invalidation")}: {formatDollars(plan.invalidationCents)}
          </span>
        )}
        <span className="dash-v2-score">Σ {plan.compositeScore}</span>
      </div>
    </article>
  );
}
