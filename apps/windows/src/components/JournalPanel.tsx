import { useEffect, useState, useCallback } from "react";
import { api, fmt } from "../api";
import type { JournalEntry, OpportunityRow } from "../api";
import { useT } from "../i18n";

const ACTIONS = ["Buy", "Sell", "Hold", "Watch", "Trim", "Exit"] as const;

const ACTION_COLOR: Record<string, string> = {
  Buy: "#22c55e", Sell: "#f43f5e", Hold: "#94a3b8",
  Watch: "#60a5fa", Trim: "#f59e0b", Exit: "#be123c",
};

const fmtDate = (epoch: number) =>
  new Date(epoch * 1000).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });

export function JournalPanel({ rows }: { rows: OpportunityRow[] }) {
  const { t } = useT();
  const [entries, setEntries] = useState<JournalEntry[]>([]);
  const [symbol, setSymbol] = useState("");
  const [action, setAction] = useState<string>("Buy");
  const [thesis, setThesis] = useState("");
  const [reviewing, setReviewing] = useState<number | null>(null);
  const [exitPrice, setExitPrice] = useState("");
  const [outcome, setOutcome] = useState("");

  const refresh = useCallback(() => {
    api.journalList().then(setEntries).catch(console.error);
  }, []);
  useEffect(() => { refresh(); }, [refresh]);

  const handleAdd = async () => {
    const sym = symbol.trim().toUpperCase();
    if (!sym) return;
    const row = rows.find((r) => r.symbol === sym);
    try {
      await api.journalAdd({
        symbol: sym,
        action,
        thesis: thesis.trim() || null,
        priceCents: row?.market_price_cents ?? null,
        setupScore: row?.setup_score ?? null,
        setupLabel: row?.setup_label ?? null,
      });
      setSymbol(""); setThesis(""); setAction("Buy");
      refresh();
    } catch (e) { console.error(e); }
  };

  const handleReview = async (id: number) => {
    const exit = parseFloat(exitPrice);
    try {
      await api.journalClose(
        id,
        outcome.trim() || null,
        isFinite(exit) && exit > 0 ? Math.round(exit * 100) : null,
      );
      setReviewing(null); setExitPrice(""); setOutcome("");
      refresh();
    } catch (e) { console.error(e); }
  };

  const realizedPct = (e: JournalEntry): number | null => {
    if (e.price_cents == null || e.exit_price_cents == null || e.price_cents <= 0) return null;
    const dir = e.action === "Sell" || e.action === "Exit" || e.action === "Trim" ? -1 : 1;
    return dir * ((e.exit_price_cents - e.price_cents) / e.price_cents) * 100;
  };

  return (
    <div className="info-section">
      <h3>{t("journal.title")}</h3>
      <p style={{ fontSize: 11, color: "var(--text-4)", lineHeight: 1.5, margin: "8px 0" }}>
        {t("journal.help")}
      </p>

      {/* Add form */}
      <div className="advisor-form">
        <input className="search" placeholder={t("advisor.form.symbol")} value={symbol}
          onChange={(e) => setSymbol(e.target.value)} style={{ width: 110 }} list="journal-symbols" />
        <datalist id="journal-symbols">
          {rows.map((r) => <option key={r.symbol} value={r.symbol} />)}
        </datalist>
        <select className="search" value={action} onChange={(e) => setAction(e.target.value)} style={{ width: 120 }}>
          {ACTIONS.map((a) => <option key={a} value={a}>{t(`journal.action.${a}`)}</option>)}
        </select>
        <input className="search" placeholder={t("journal.thesis")} value={thesis}
          onChange={(e) => setThesis(e.target.value)} style={{ flex: 1, minWidth: 200 }} />
        <button className="congress-sync-btn" onClick={handleAdd}>+ {t("journal.add")}</button>
      </div>

      {entries.length === 0 ? (
        <div style={{ color: "var(--text-4)", fontSize: 13, padding: "12px 0" }}>{t("journal.empty")}</div>
      ) : (
        <table className="stock-table">
          <thead>
            <tr>
              <th>{t("journal.col.date")}</th>
              <th>{t("advisor.col.position")}</th>
              <th>{t("journal.action")}</th>
              <th>{t("journal.col.modelRead")}</th>
              <th style={{ textAlign: "right" }}>{t("journal.col.priceAt")}</th>
              <th>{t("journal.col.outcome")}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {entries.map((e) => {
              const rpct = realizedPct(e);
              return (
                <tr key={e.id}>
                  <td style={{ fontSize: 11, whiteSpace: "nowrap" }}>{fmtDate(e.created_at)}</td>
                  <td><strong>{e.symbol}</strong></td>
                  <td>
                    <span style={{ fontSize: 11, fontWeight: 700, color: ACTION_COLOR[e.action] ?? "var(--text-2)" }}>
                      {t(`journal.action.${e.action}`)}
                    </span>
                    {e.thesis && <div style={{ fontSize: 10, color: "var(--text-4)", maxWidth: 240, lineHeight: 1.3 }}>{e.thesis}</div>}
                  </td>
                  <td style={{ fontSize: 11 }}>
                    {e.setup_label
                      ? <span>{t(`setup.${e.setup_label}`)} {e.setup_score != null && `(${e.setup_score > 0 ? "+" : ""}${e.setup_score})`}</span>
                      : "—"}
                  </td>
                  <td className="num-cell" style={{ textAlign: "right" }}>
                    {e.price_cents != null ? fmt.dollars(e.price_cents) : "—"}
                  </td>
                  <td style={{ fontSize: 11 }}>
                    {e.closed_at != null ? (
                      <span>
                        {rpct != null && (
                          <strong style={{ color: rpct >= 0 ? "var(--success)" : "var(--danger)" }}>
                            {rpct >= 0 ? "+" : ""}{rpct.toFixed(1)}%
                          </strong>
                        )}
                        {e.outcome && <div style={{ color: "var(--text-4)", fontSize: 10, maxWidth: 220 }}>{e.outcome}</div>}
                      </span>
                    ) : reviewing === e.id ? (
                      <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                        <input className="search" type="number" placeholder={t("journal.exitPrice")}
                          value={exitPrice} onChange={(ev) => setExitPrice(ev.target.value)} style={{ width: 110 }} />
                        <input className="search" placeholder={t("journal.outcomeNotes")}
                          value={outcome} onChange={(ev) => setOutcome(ev.target.value)} style={{ width: 200 }} />
                        <button className="congress-sync-btn" style={{ padding: "2px 8px" }} onClick={() => handleReview(e.id)}>
                          {t("journal.saveReview")}
                        </button>
                      </div>
                    ) : (
                      <button className="btn-ghost" style={{ padding: "2px 8px", fontSize: 11 }} onClick={() => { setReviewing(e.id); setExitPrice(""); setOutcome(""); }}>
                        {t("journal.review")}
                      </button>
                    )}
                  </td>
                  <td>
                    <button className="btn-ghost" style={{ padding: "2px 8px" }} onClick={() => api.journalDelete(e.id).then(refresh)}>🗑</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}
