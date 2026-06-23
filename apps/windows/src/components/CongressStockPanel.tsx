import { useEffect, useState } from "react";
import { api } from "../api";
import type { CongressTrade } from "../api";
import { useT } from "../i18n";

interface Props {
  symbol: string;
}

export function CongressStockPanel({ symbol }: Props) {
  const { t } = useT();
  const [trades, setTrades] = useState<CongressTrade[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    api.getCongressTradesForSymbol(symbol, 20)
      .then((ts) => { setTrades(ts); setLoading(false); })
      .catch((e) => { console.error(e); setLoading(false); });
  }, [symbol]);

  // Hide entire section when no data — don't show empty boxes
  if (loading || trades.length === 0) return null;

  const txLabel = (type: string) => {
    if (type === "P") return { label: t("congress.tx.purchase"), color: "var(--success)" };
    if (type.startsWith("S")) return { label: t("congress.tx.sale"), color: "var(--danger)" };
    if (type === "E") return { label: t("congress.tx.exchange"), color: "var(--warning)" };
    return { label: type, color: "var(--text-3)" };
  };

  const fmtAmount = (min: number | null, max: number | null) => {
    if (!max) return "—";
    const fmt = (n: number) =>
      n >= 1_000_000 ? `$${(n / 1_000_000).toFixed(1)}M`
      : n >= 1_000 ? `$${(n / 1_000).toFixed(0)}k`
      : `$${n}`;
    return `${fmt(min ?? 0)} – ${fmt(max)}`;
  };

  return (
    <div className="info-section">
      <h3>{t("congress.detail.title")}</h3>
      <ul className="congress-trade-list">
        {trades.map((tr) => {
          const lbl = txLabel(tr.transaction_type);
          return (
            <li key={tr.trade_id} className="congress-trade-item">
              <div className="congress-trade-head">
                <span className="congress-trade-politician">
                  <strong>{tr.politician_name}</strong>
                  <span className="congress-trade-meta">
                    {tr.chamber}{tr.state ? ` · ${tr.state}${tr.district ? `-${tr.district}` : ""}` : ""}
                    {tr.owner && tr.owner !== "" && ` · ${tr.owner}`}
                  </span>
                </span>
                <span className="congress-trade-type" style={{ color: lbl.color, fontWeight: 700 }}>
                  {lbl.label}
                </span>
              </div>
              <div className="congress-trade-foot">
                <span className="congress-trade-amount">{fmtAmount(tr.amount_range_min, tr.amount_range_max)}</span>
                <span className="congress-trade-dates">
                  {t("congress.detail.txDate")}: {tr.transaction_date ?? "—"} ·{" "}
                  {t("congress.detail.discDate")}: {tr.disclosure_date ?? "—"}
                </span>
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
