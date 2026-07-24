import { useState } from "react";
import { fmt } from "../api";
import type { OpportunityRow, Decision, SetupLabel } from "../api";
import { useT } from "../i18n";
import { Sparkline } from "./Sparkline";
import {
  getScoringPresentation,
  renderText,
  scoringDimensionsTooltipKey,
  type ScoringModelId,
} from "../scoringPresentation";
import { createRegimePresentation, regimeRowInput } from "../regimePresentation";

interface Props {
  rows: OpportunityRow[];
  selectedSymbol: string | null;
  onSelect: (symbol: string) => void;
  symbolsLoaded?: number;
  symbolsTotal?: number;
  /** Backend scoring model id: aggressive_v2 | aggressive_v3 | short_v3 */
  scoringModel?: ScoringModelId;
}

type SortKey =
  | "symbol" | "company_name" | "market_price_cents" | "daily_change_bps" | "intrinsic_value_cents"
  | "gap_bps" | "decision" | "setup_score" | "composite_score" | "fundamentals_score"
  | "technical_score" | "forecast_score" | "analyst_opinion_count"
  | "recommendation_mean_hundredths" | "sector_name";

const SETUP_STYLE: Record<SetupLabel, { bg: string; color: string; shadow: string; icon: string }> = {
  StrongBuy:        { bg: "linear-gradient(135deg, #16a34a, #15803d)", color: "#fff",    shadow: "0 0 0 1px rgba(22,163,74,0.55), 0 3px 12px rgba(22,163,74,0.45)", icon: "▲▲▲" },
  Buy:              { bg: "linear-gradient(135deg, #22c55e, #16a34a)", color: "#fff",    shadow: "0 0 0 1px rgba(34,197,94,0.45), 0 2px 8px rgba(34,197,94,0.35)",   icon: "▲▲" },
  StrongAccumulate: { bg: "linear-gradient(135deg, #06b6d4, #0891b2)", color: "#fff",    shadow: "0 0 0 1px rgba(6,182,212,0.55), 0 3px 12px rgba(6,182,212,0.45)",  icon: "💎▲" },
  Accumulate:       { bg: "linear-gradient(135deg, #4ade80, #22c55e)", color: "#0a0e1c", shadow: "0 0 0 1px rgba(74,222,128,0.40), 0 2px 6px rgba(74,222,128,0.28)", icon: "▲" },
  Watch:            { bg: "linear-gradient(135deg, #fbbf24, #f59e0b)", color: "#0a0e1c", shadow: "0 0 0 1px rgba(251,191,36,0.40), 0 2px 6px rgba(251,191,36,0.28)", icon: "◆" },
  HoldWait:         { bg: "linear-gradient(135deg, #fbbf24, #d97706)", color: "#0a0e1c", shadow: "0 0 0 1px rgba(251,191,36,0.40), 0 2px 6px rgba(251,191,36,0.28)", icon: "◆" },
  Hold:             { bg: "rgba(148, 163, 184, 0.18)",                color: "#94a3b8", shadow: "0 0 0 1px rgba(148,163,184,0.28)",                                   icon: "—" },
  Neutral:          { bg: "rgba(148, 163, 184, 0.18)",                color: "#94a3b8", shadow: "0 0 0 1px rgba(148,163,184,0.28)",                                   icon: "—" },
  Caution:          { bg: "linear-gradient(135deg, #fb923c, #f97316)", color: "#fff",    shadow: "0 0 0 1px rgba(249,115,22,0.45), 0 2px 6px rgba(249,115,22,0.32)",  icon: "⚠" },
  Distribute:       { bg: "linear-gradient(135deg, #fb923c, #f97316)", color: "#fff",    shadow: "0 0 0 1px rgba(249,115,22,0.45), 0 2px 6px rgba(249,115,22,0.32)",  icon: "▼" },
  Avoid:            { bg: "linear-gradient(135deg, #fb923c, #f97316)", color: "#fff",    shadow: "0 0 0 1px rgba(249,115,22,0.45), 0 2px 6px rgba(249,115,22,0.32)",  icon: "▼" },
  StrongAvoid:      { bg: "linear-gradient(135deg, #f43f5e, #be123c)", color: "#fff",    shadow: "0 0 0 1px rgba(244,63,94,0.55), 0 3px 10px rgba(244,63,94,0.45)",   icon: "▼▼▼" },
};

const SHORT_SETUP_STYLE: Record<SetupLabel, { bg: string; color: string; shadow: string; icon: string }> = {
  StrongBuy:        { bg: "linear-gradient(135deg, #e11d48, #9f1239)", color: "#fff", shadow: "0 0 0 1px rgba(244,63,94,.55), 0 3px 12px rgba(225,29,72,.38)", icon: "▼▼▼" },
  Buy:              { bg: "linear-gradient(135deg, #f43f5e, #be123c)", color: "#fff", shadow: "0 0 0 1px rgba(244,63,94,.45), 0 2px 8px rgba(244,63,94,.30)", icon: "▼▼" },
  StrongAccumulate: { bg: "linear-gradient(135deg, #fb7185, #e11d48)", color: "#fff", shadow: "0 0 0 1px rgba(251,113,133,.42)", icon: "▼" },
  Accumulate:       { bg: "linear-gradient(135deg, #fb7185, #e11d48)", color: "#fff", shadow: "0 0 0 1px rgba(251,113,133,.42)", icon: "▼" },
  Watch:            SETUP_STYLE.Watch,
  HoldWait:         SETUP_STYLE.Hold,
  Hold:             SETUP_STYLE.Hold,
  Neutral:          SETUP_STYLE.Neutral,
  Caution:          { bg: "rgba(20,184,166,.16)", color: "#5eead4", shadow: "0 0 0 1px rgba(20,184,166,.30)", icon: "▲" },
  Distribute:       { bg: "rgba(20,184,166,.16)", color: "#5eead4", shadow: "0 0 0 1px rgba(20,184,166,.30)", icon: "▲" },
  Avoid:            { bg: "rgba(20,184,166,.16)", color: "#5eead4", shadow: "0 0 0 1px rgba(20,184,166,.30)", icon: "▲" },
  StrongAvoid:      { bg: "linear-gradient(135deg, #16a34a, #15803d)", color: "#fff", shadow: "0 0 0 1px rgba(34,197,94,.45)", icon: "▲▲▲" },
};

const DECISION_ORDER: Record<Decision, number> = { Act: 0, Watch: 1, Avoid: 2 };

const QUAL_LABEL: Record<string, string> = {
  Qualified: "✓",
  Unprofitable: "✗",
  GapTooSmall: "~",
};

function sortRows(rows: OpportunityRow[], key: SortKey, asc: boolean): OpportunityRow[] {
  return [...rows].sort((a, b) => {
    let av: number | string | null;
    let bv: number | string | null;

    if (key === "decision") {
      av = DECISION_ORDER[a.decision];
      bv = DECISION_ORDER[b.decision];
    } else if (key === "symbol" || key === "company_name" || key === "sector_name") {
      av = (a[key] ?? "").toLowerCase();
      bv = (b[key] ?? "").toLowerCase();
    } else {
      av = a[key] as number | null;
      bv = b[key] as number | null;
    }

    // nulls always last
    if (av == null && bv == null) return 0;
    if (av == null) return 1;
    if (bv == null) return -1;

    if (av < bv) return asc ? -1 : 1;
    if (av > bv) return asc ? 1 : -1;
    return 0;
  });
}

export function OpportunityList({
  rows,
  selectedSymbol,
  onSelect,
  symbolsLoaded = 0,
  symbolsTotal = 528,
  scoringModel = "aggressive_v3",
}: Props) {
  const { t } = useT();
  const presentation = getScoringPresentation(scoringModel);
  // Default: Android V3 ranks by composite; setup_score mirrors composite under V3/Short.
  const [sortKey, setSortKey] = useState<SortKey>("composite_score");
  const [sortAsc, setSortAsc] = useState(false);

  const handleSort = (key: SortKey) => {
    if (sortKey === key) setSortAsc((a) => !a);
    else { setSortKey(key); setSortAsc(key === "symbol" || key === "company_name" || key === "sector_name"); }
  };

  const sorted = sortRows(rows, sortKey, sortAsc);

  const th = (label: string, key: SortKey, title?: string) => {
    const active = sortKey === key;
    const arrow = active ? (sortAsc ? " ▲" : " ▼") : "";
    return (
      <th
        className={`sortable-th ${active ? "sort-active" : ""}`}
        title={title ?? label}
        onClick={() => handleSort(key)}
      >
        {label}{arrow}
      </th>
    );
  };

  if (rows.length === 0) {
    const isLoading = symbolsLoaded < symbolsTotal;
    return (
      <div className="empty-state">
        <div className="spinner" />
        {isLoading ? (
          <>
            <p>{t(presentation.loadingKey)} ({symbolsLoaded}/{symbolsTotal})</p>
            <p className="hint">{t(presentation.loadingHintKey)}</p>
          </>
        ) : (
          <>
            <p>{t(presentation.emptyKey)}</p>
            <p className="hint">{t(presentation.emptyHintKey)}</p>
          </>
        )}
      </div>
    );
  }

  return (
    <div className="opportunity-list">
      <table className="stock-table">
        <thead>
          <tr>
            {th(t("col.symbol"),    "symbol")}
            {th(t("col.setup"),     "setup_score",                    t(presentation.setupTooltipKey))}
            {th(t("col.company"),   "company_name")}
            {th(t("col.price"),     "market_price_cents")}
            {th(t("col.dailyChange"), "daily_change_bps", t("col.dailyChange.tooltip"))}
            <th className="sort-th">{t("col.trend")}</th>
            {th(t(presentation.targetColumnKey), "intrinsic_value_cents", t(presentation.analystTargetLabelKey))}
            {th(t(presentation.gapColumnKey), "gap_bps", t(presentation.gapLabelKey))}
            {th(
              scoringModel === "aggressive_v3" || scoringModel === "short_v3" ? "F·T·Fc·R" : "F·T·Fc",
              "fundamentals_score",
              t(scoringDimensionsTooltipKey(scoringModel)),
            )}
            {th(t("col.analysts"),  "analyst_opinion_count")}
            {th(t("col.sector"),    "sector_name")}
          </tr>
        </thead>
        <tbody>
          {sorted.map((row) => {
            const marketContext = createRegimePresentation(regimeRowInput(row, scoringModel));
            return (
              <tr
                key={row.symbol}
                className={`stock-row ${selectedSymbol === row.symbol ? "selected" : ""} ${row.qualification}`}
                onClick={() => onSelect(row.symbol)}
              >
                <td className="symbol-cell">
                  {row.asset_type === "crypto" ? (
                    <span className="crypto-badge" title="Cryptocurrency — scoring técnico solamente">₿</span>
                  ) : row.asset_type === "etf" ? (
                    <span className="etf-badge" title="ETF — scoring técnico solamente">ETF</span>
                  ) : (
                    <span className="qual-badge">{QUAL_LABEL[row.qualification]}</span>
                  )}
                  <strong>{row.symbol}</strong>
                </td>
                <td>
                  <SetupBadge label={row.setup_label} score={row.setup_score} t={t} scoringModel={scoringModel} />
                </td>
                <td className="company-cell">{row.company_name ?? "—"}</td>
                <td className="num-cell">{fmt.dollars(row.market_price_cents)}</td>
                <td className="num-cell">
                  {row.daily_change_bps != null ? (
                    <span style={{
                      color: row.daily_change_bps > 0 ? "var(--success)"
                        : row.daily_change_bps < 0 ? "var(--danger)" : "var(--text-4)",
                      fontWeight: 600,
                    }}>
                      {row.daily_change_bps > 0 ? "+" : ""}{(row.daily_change_bps / 100).toFixed(2)}%
                    </span>
                  ) : <span style={{ color: "var(--text-5)" }}>—</span>}
                </td>
                <td><Sparkline data={row.spark} /></td>
                <td className="num-cell">
                  {row.intrinsic_value_cents > 0 ? fmt.dollars(row.intrinsic_value_cents) : "—"}
                </td>
                <td className="num-cell gap-cell">
                  {(() => {
                    const gap = presentation.gap(row.gap_bps);
                    const className = gap.tone === "favorable" ? "gap-positive"
                      : gap.tone === "adverse" ? "gap-adverse" : "gap-neutral";
                    return (
                      <span className={className} title={renderText(gap.text, t)}>
                        {gap.marker} {renderText(gap.compactText, t)}
                      </span>
                    );
                  })()}
                </td>
                <td className="num-cell score-trio">
                  <ScorePip v={row.fundamentals_score} title={t(presentation.bucketLabelKeys[0])} />
                  <span className="score-sep">·</span>
                  <ScorePip v={row.technical_score} title={t(presentation.bucketLabelKeys[1])} />
                  <span className="score-sep">·</span>
                  <ScorePip v={row.forecast_score} title={t(presentation.bucketLabelKeys[2])} />
                  {marketContext.visible && (
                    <>
                      <span className="score-sep">·</span>
                      <ScorePip
                        v={marketContext.score}
                        title={`${t("analysis.marketContext.title")}: ${t(marketContext.statusKey)}`}
                        muted={marketContext.muted}
                      />
                    </>
                  )}
                </td>
                <td className="num-cell">{row.analyst_opinion_count ?? "—"}</td>
                <td className="sector-cell">{row.sector_name ?? "—"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function SetupBadge({
  label,
  score,
  t,
  scoringModel,
}: {
  label: SetupLabel;
  score: number;
  t: (k: string, v?: Record<string, string | number>) => string;
  scoringModel: ScoringModelId;
}) {
  const presentation = getScoringPresentation(scoringModel);
  const st = (presentation.isShort ? SHORT_SETUP_STYLE : SETUP_STYLE)[label] ?? SETUP_STYLE.Hold;
  const name = t(presentation.setupLabelKey(label));
  const desc = t(presentation.setupDescriptionKey(label));
  const icon = presentation.setupIcon(label, st.icon);
  return (
    <div
      className={`setup-badge${presentation.isShort ? " setup-badge--short" : ""}`}
      style={{ background: st.bg, color: st.color, boxShadow: st.shadow }}
      title={`${name} (${score > 0 ? "+" : ""}${score}) — ${desc}`}
    >
      <span className="setup-icon">{icon}</span>
      <span className="setup-label">{name}</span>
      <span className="setup-score-num">{score > 0 ? "+" : ""}{score}</span>
    </div>
  );
}


function ScorePip({ v, title, muted = false }: { v: number | null; title: string; muted?: boolean }) {
  if (v == null) return <span className="pip pip-none" title={title}>—</span>;
  const color = muted ? "#64748b" : v >= 30 ? "#22c55e" : v >= 0 ? "#f59e0b" : "#ef4444";
  return (
    <span className="pip" title={`${title}: ${v}`} style={{ color }}>{v}</span>
  );
}
