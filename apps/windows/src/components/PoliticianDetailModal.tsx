import { useEffect, useState } from "react";
import { api } from "../api";
import type { PoliticianWithMetrics, PoliticianTradeRow } from "../api";
import { useT } from "../i18n";

interface Props {
  politicianId: number;
  onClose: () => void;
}

function fmtBps(bps: number | null | undefined): string {
  if (bps == null) return "—";
  return `${(bps / 100).toFixed(1)}%`;
}
function bpsColor(bps: number | null | undefined): string {
  if (bps == null) return "var(--text-4)";
  if (bps > 500) return "var(--success)";
  if (bps > 0) return "#4ade80";
  if (bps > -500) return "var(--warning)";
  return "var(--danger)";
}
function fmtMoney(cents: number | null | undefined): string {
  if (cents == null || cents === 0) return "—";
  const d = cents / 100;
  if (Math.abs(d) >= 1_000_000) return `$${(d / 1_000_000).toFixed(2)}M`;
  if (Math.abs(d) >= 1_000) return `$${(d / 1_000).toFixed(1)}k`;
  return `$${d.toFixed(0)}`;
}
function fmtAmount(min: number | null, max: number | null): string {
  if (!max) return "—";
  const fmt = (n: number) =>
    n >= 1_000_000 ? `$${(n / 1_000_000).toFixed(1)}M`
    : n >= 1_000 ? `$${(n / 1_000).toFixed(0)}k`
    : `$${n}`;
  return `${fmt(min ?? 0)} – ${fmt(max)}`;
}

export function PoliticianDetailModal({ politicianId, onClose }: Props) {
  const { t } = useT();
  const [metrics, setMetrics] = useState<PoliticianWithMetrics | null>(null);
  const [trades, setTrades] = useState<PoliticianTradeRow[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    api.getPoliticianDetail(politicianId)
      .then(([m, ts]) => {
        setMetrics(m);
        setTrades(ts);
        setLoading(false);
      })
      .catch((e) => { console.error(e); setLoading(false); });
  }, [politicianId]);

  const txLabel = (type: string) => {
    if (type === "P") return { label: t("congress.tx.purchase"), color: "var(--success)" };
    if (type.startsWith("S")) return { label: t("congress.tx.sale"), color: "var(--danger)" };
    if (type === "E") return { label: t("congress.tx.exchange"), color: "var(--warning)" };
    return { label: type, color: "var(--text-3)" };
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content politician-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>{metrics?.full_name ?? "…"}</h3>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>

        {loading ? (
          <div className="loading-msg">{t("status.loading")}…</div>
        ) : !metrics ? (
          <div className="loading-msg">No data</div>
        ) : (
          <>
            <div className="politician-meta">
              <span>{metrics.chamber}</span>
              {metrics.state && <span>{metrics.state}{metrics.district ? `-${metrics.district}` : ""}</span>}
              <span style={{ marginLeft: "auto", color: "var(--text-4)", fontSize: 11 }}>
                {t("congress.col.confidence")}: <strong style={{ color: "var(--text-2)" }}>{metrics.confidence_score}</strong>
                {" · "}
                {t("congress.col.qualifying")}: <strong style={{ color: "var(--text-2)" }}>{metrics.qualifying_trades}</strong>
              </span>
            </div>

            <div className="metrics-grid">
              <Metric label={t("congress.stats.trades")} value={metrics.total_trades.toString()} />
              <Metric label="Buys" value={metrics.purchase_count.toString()} color="var(--success)" />
              <Metric label="Sells" value={metrics.sale_count.toString()} color="var(--danger)" />
              <Metric label={`${t("congress.col.avgReturn")} 30d`} value={fmtBps(metrics.avg_return_30d_bps)} color={bpsColor(metrics.avg_return_30d_bps)} />
              <Metric label={`${t("congress.col.avgReturn")} 90d`} value={fmtBps(metrics.avg_return_90d_bps)} color={bpsColor(metrics.avg_return_90d_bps)} />
              <Metric label={`${t("congress.col.avgReturn")} 180d`} value={fmtBps(metrics.avg_return_180d_bps)} color={bpsColor(metrics.avg_return_180d_bps)} />
              <Metric label={`${t("congress.col.winRate")} 90d`} value={metrics.win_rate_90d_pct != null ? `${metrics.win_rate_90d_pct}%` : "—"} />
              <Metric label={`${t("congress.col.alpha")} 90d`} value={fmtBps(metrics.avg_alpha_90d_bps)} color={bpsColor(metrics.avg_alpha_90d_bps)} />
              <Metric label={`${t("congress.col.alpha")} 180d`} value={fmtBps(metrics.avg_alpha_180d_bps)} color={bpsColor(metrics.avg_alpha_180d_bps)} />
              <Metric
                label={t("congress.col.estGain")}
                value={fmtMoney(metrics.estimated_total_gain_cents)}
                color={metrics.estimated_total_gain_cents > 0 ? "var(--success)" : metrics.estimated_total_gain_cents < 0 ? "var(--danger)" : "var(--text-4)"}
              />
            </div>

            <h4 className="modal-section-title">{t("congress.modal.history")}</h4>
            <div className="trade-table-wrap">
              <table className="stock-table">
                <thead>
                  <tr>
                    <th>Symbol</th>
                    <th>Type</th>
                    <th>{t("congress.detail.txDate")}</th>
                    <th>{t("congress.detail.discDate")}</th>
                    <th>{t("congress.col.amount")}</th>
                    <th>30d</th>
                    <th>90d</th>
                    <th>180d</th>
                    <th>{t("congress.col.estGain")}</th>
                  </tr>
                </thead>
                <tbody>
                  {trades.map((tr) => {
                    const lbl = txLabel(tr.transaction_type);
                    return (
                      <tr key={tr.trade_id}>
                        <td><strong>{tr.symbol ?? "—"}</strong></td>
                        <td style={{ color: lbl.color, fontWeight: 700 }}>{lbl.label}</td>
                        <td style={{ fontSize: 11 }}>{tr.transaction_date ?? "—"}</td>
                        <td style={{ fontSize: 11 }}>{tr.disclosure_date ?? "—"}</td>
                        <td className="num-cell" style={{ fontSize: 11 }}>
                          {fmtAmount(tr.amount_range_min, tr.amount_range_max)}
                        </td>
                        <td className="num-cell" style={{ color: bpsColor(tr.return_30d_bps) }}>{fmtBps(tr.return_30d_bps)}</td>
                        <td className="num-cell" style={{ color: bpsColor(tr.return_90d_bps) }}>{fmtBps(tr.return_90d_bps)}</td>
                        <td className="num-cell" style={{ color: bpsColor(tr.return_180d_bps) }}>{fmtBps(tr.return_180d_bps)}</td>
                        <td className="num-cell" style={{ color: (tr.estimated_gain_cents ?? 0) > 0 ? "var(--success)" : (tr.estimated_gain_cents ?? 0) < 0 ? "var(--danger)" : "var(--text-4)" }}>
                          {fmtMoney(tr.estimated_gain_cents)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function Metric({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div className="metric-card">
      <div className="metric-label">{label}</div>
      <div className="metric-value" style={{ color: color ?? "var(--text-1)" }}>{value}</div>
    </div>
  );
}
