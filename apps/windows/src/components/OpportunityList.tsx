import { useState } from "react";
import { fmt } from "../api";
import type { OpportunityRow, Decision, SetupLabel } from "../api";
import { useT } from "../i18n";
import { Sparkline } from "./Sparkline";

interface Props {
  rows: OpportunityRow[];
  selectedSymbol: string | null;
  onSelect: (symbol: string) => void;
  symbolsLoaded?: number;
  symbolsTotal?: number;
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

export function OpportunityList({ rows, selectedSymbol, onSelect, symbolsLoaded = 0, symbolsTotal = 528 }: Props) {
  const { t } = useT();
  const [sortKey, setSortKey] = useState<SortKey>("setup_score");
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
            <p>{t("empty.loading")} ({symbolsLoaded}/{symbolsTotal})</p>
            <p className="hint">{t("empty.loading.hint")}</p>
          </>
        ) : (
          <>
            <p>{t("empty.nomatch")}</p>
            <p className="hint">{t("empty.nomatch.hint")}</p>
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
            {th(t("col.setup"),     "setup_score",                    t("col.setup.tooltip"))}
            {th(t("col.company"),   "company_name")}
            {th(t("col.price"),     "market_price_cents")}
            {th(t("col.dailyChange"), "daily_change_bps", t("col.dailyChange.tooltip"))}
            <th className="sort-th">{t("col.trend")}</th>
            {th(t("col.target"),    "intrinsic_value_cents")}
            {th(t("col.gap"),       "gap_bps")}
            {th("F·T·Fc",           "fundamentals_score",             t("col.trio.tooltip"))}
            {th(t("col.analysts"),  "analyst_opinion_count")}
            {th(t("col.sector"),    "sector_name")}
          </tr>
        </thead>
        <tbody>
          {sorted.map((row) => {
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
                  <SetupBadge label={row.setup_label} score={row.setup_score} t={t} />
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
                  <span className={row.gap_bps >= 1000 ? "gap-positive" : "gap-neutral"}>
                    {fmt.gap(row.gap_bps)}
                  </span>
                </td>
                <td className="num-cell score-trio">
                  <ScorePip v={row.fundamentals_score} title="Fundamentals" />
                  <span className="score-sep">·</span>
                  <ScorePip v={row.technical_score} title="Technical" />
                  <span className="score-sep">·</span>
                  <ScorePip v={row.forecast_score} title="Forecast" />
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

function SetupBadge({ label, score, t }: { label: SetupLabel; score: number; t: (k: string, v?: Record<string, string | number>) => string }) {
  const st = SETUP_STYLE[label];
  return (
    <div
      className="setup-badge"
      style={{ background: st.bg, color: st.color, boxShadow: st.shadow }}
      title={`${t(`setup.${label}`)} (${score > 0 ? "+" : ""}${score}) — ${t(`setup.${label}.desc`)}`}
    >
      <span className="setup-icon">{st.icon}</span>
      <span className="setup-label">{t(`setup.${label}`)}</span>
      <span className="setup-score-num">{score > 0 ? "+" : ""}{score}</span>
    </div>
  );
}


function ScorePip({ v, title }: { v: number | null; title: string }) {
  if (v === null) return <span className="pip pip-none" title={`${title}: no data`}>—</span>;
  const color = v >= 30 ? "#22c55e" : v >= 0 ? "#f59e0b" : "#ef4444";
  return (
    <span className="pip" title={`${title}: ${v}`} style={{ color }}>{v}</span>
  );
}
