import { useEffect, useState, useMemo } from "react";
import { api, fmt } from "../api";
import type { OpportunityRow, AlertEvent, SetupLabel } from "../api";
import { useT } from "../i18n";
import { RegimeBanner } from "./RegimeBanner";
import { Sparkline } from "./Sparkline";

type ViewMode = "screener" | "congress" | "advisor" | "scalping" | "dashboard";

const POSITIVE: SetupLabel[] = ["StrongBuy", "Buy", "Accumulate", "StrongAccumulate"];

interface Props {
  rows: OpportunityRow[];
  symbolsLoaded: number;
  symbolsTotal: number;
  onOpenSymbol: (s: string) => void;
  onNavigate: (v: ViewMode) => void;
}

const day = (bps: number | null) => {
  if (bps == null) return null;
  const v = bps / 100;
  return { v, col: v > 0 ? "var(--success)" : v < 0 ? "var(--danger)" : "var(--text-4)", arrow: v > 0 ? "▲" : v < 0 ? "▼" : "" };
};

export function DashboardPanel({ rows, symbolsLoaded, symbolsTotal, onOpenSymbol, onNavigate }: Props) {
  const { t } = useT();
  const [alerts, setAlerts] = useState<AlertEvent[]>([]);
  const isLoading = symbolsTotal === 0 || symbolsLoaded < symbolsTotal;
  const marketPlaceholder = isLoading
    ? `${t("empty.loading")} (${symbolsLoaded}/${symbolsTotal})`
    : t("dash.noMarketData");

  useEffect(() => { api.getAlerts().then(setAlerts).catch(() => {}); }, []);

  const opportunities = useMemo(() =>
    rows.filter((r) => POSITIVE.includes(r.setup_label) || r.decision === "Act")
      .sort((a, b) => b.composite_score - a.composite_score || b.setup_score - a.setup_score)
      .slice(0, 6),
    [rows]);
  const withDay = useMemo(() => rows.filter((r) => r.daily_change_bps != null), [rows]);
  const gainers = useMemo(() => [...withDay].sort((a, b) => (b.daily_change_bps ?? 0) - (a.daily_change_bps ?? 0)).slice(0, 5), [withDay]);
  const losers = useMemo(() => [...withDay].sort((a, b) => (a.daily_change_bps ?? 0) - (b.daily_change_bps ?? 0)).slice(0, 5), [withDay]);

  const moverRow = (r: OpportunityRow) => {
    const d = day(r.daily_change_bps);
    return (
      <div key={r.symbol} className="dash-mover" onClick={() => onOpenSymbol(r.symbol)}>
        <strong>{r.symbol}</strong>
        <span className="dash-mover-price">{fmt.dollars(r.market_price_cents)}</span>
        {d && <span style={{ color: d.col, fontWeight: 700 }}>{d.arrow} {d.v >= 0 ? "+" : ""}{d.v.toFixed(2)}%</span>}
      </div>
    );
  };

  return (
    <div className="congress-page">
      <header className="congress-header">
        <div>
          <h2 className="congress-title">
            {(() => { const n = localStorage.getItem("ds_display_name")?.trim(); return n ? `${t("dash.title")}, ${n}` : t("dash.title"); })()}
          </h2>
          <p className="congress-subtitle">{t("dash.subtitle")}</p>
        </div>
      </header>

      <RegimeBanner />

      {/* Top opportunities */}
      <div className="info-section">
        <div className="dash-sec-head">
          <h3>{t("dash.opportunities")}</h3>
          <button className="btn-ghost" onClick={() => onNavigate("screener")}>{t("dash.viewAll")} →</button>
        </div>
        {opportunities.length === 0 ? (
          <div style={{ color: "var(--text-4)", fontSize: 13 }}>
            {rows.length === 0 ? marketPlaceholder : t("dash.noOpportunities")}
          </div>
        ) : (
          <div className="dash-opps">
            {opportunities.map((r) => {
              const d = day(r.daily_change_bps);
              return (
                <div key={r.symbol} className="dash-opp" onClick={() => onOpenSymbol(r.symbol)}>
                  <div className="dash-opp-top">
                    <strong>{r.symbol}</strong>
                    <span className="dash-opp-score">{t(`setup.${r.setup_label}`)} +{r.setup_score}</span>
                  </div>
                  <div className="dash-opp-meta">
                    <span>{r.company_name ?? "—"}</span>
                  </div>
                  <div style={{ margin: "4px 0 8px" }}><Sparkline data={r.spark} width={200} height={28} /></div>
                  <div className="dash-opp-foot">
                    <span>{fmt.dollars(r.market_price_cents)}</span>
                    {d && <span style={{ color: d.col, fontWeight: 600 }}>{d.arrow} {d.v >= 0 ? "+" : ""}{d.v.toFixed(2)}%</span>}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Movers + Alerts */}
      <div className="dash-grid">
        <div className="info-section">
          <h3>{t("dash.gainers")}</h3>
          {gainers.length > 0 ? gainers.map(moverRow) : (
            <div style={{ color: "var(--text-4)", fontSize: 13 }}>{marketPlaceholder}</div>
          )}
        </div>
        <div className="info-section">
          <h3>{t("dash.losers")}</h3>
          {losers.length > 0 ? losers.map(moverRow) : (
            <div style={{ color: "var(--text-4)", fontSize: 13 }}>{marketPlaceholder}</div>
          )}
        </div>
        <div className="info-section">
          <div className="dash-sec-head">
            <h3>{t("dash.alerts")}</h3>
            <button className="btn-ghost" onClick={() => onNavigate("advisor")}>{t("view.advisor")} →</button>
          </div>
          {alerts.length === 0 ? (
            <div style={{ color: "var(--text-4)", fontSize: 13 }}>{t("dash.noAlerts")}</div>
          ) : (
            <ul className="dash-alerts">
              {alerts.slice(0, 8).map((a, i) => (
                <li key={i} onClick={() => onOpenSymbol(a.symbol)}>
                  <span className={`dash-alert-dot ${a.kind === "ExitedQualified" ? "down" : "up"}`} />
                  <strong>{a.symbol}</strong>
                  <span className="dash-alert-kind">{a.kind === "EnteredQualified" ? t("dash.entered") : a.kind === "ExitedQualified" ? t("dash.exited") : t("dash.upgraded")}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
