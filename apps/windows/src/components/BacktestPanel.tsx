import { useState, useEffect } from "react";
import { api, fmt } from "../api";
import type { BacktestResult, Decision, HistoryStatus } from "../api";
import { useT } from "../i18n";

interface Props {
  onClose: () => void;
}

const DECISIONS: Decision[] = ["Act", "Watch", "Avoid"];
export function BacktestPanel({ onClose }: Props) {
  const { t } = useT();
  const WINDOWS: { label: string; days: number }[] = [
    { label: t("backtest.days7"),  days: 7 },
    { label: t("backtest.days30"), days: 30 },
    { label: t("backtest.days90"), days: 90 },
  ];
  const [decision, setDecision] = useState<Decision>("Act");
  const [daysAgo, setDaysAgo] = useState(30);
  const [result, setResult] = useState<BacktestResult | null>(null);
  const [status, setStatus] = useState<HistoryStatus | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    api.getHistoryStatus().then(setStatus).catch(console.error);
  }, []);

  useEffect(() => {
    setLoading(true);
    api.getBacktest(decision, daysAgo)
      .then((r) => { setResult(r); setLoading(false); })
      .catch((e) => { console.error(e); setLoading(false); });
  }, [decision, daysAgo]);

  const retPct = (bps: number) => `${(bps / 100).toFixed(2)}%`;
  const retColor = (bps: number) =>
    bps > 200 ? "#22c55e" : bps > 0 ? "#4ade80" : bps > -200 ? "#f59e0b" : "#ef4444";

  return (
    <div className="backtest-panel">
      <div className="backtest-header">
        <span style={{ fontSize: 16, fontWeight: 700 }}>📊 {t("backtest.title")}</span>
        <button className="close-btn" onClick={onClose}>✕</button>
      </div>

      <div className="backtest-toolbar">
        <div className="backtest-tabs">
          {DECISIONS.map((d) => (
            <button
              key={d}
              className={`range-tab ${decision === d ? "active" : ""}`}
              onClick={() => setDecision(d)}
            >{d}</button>
          ))}
        </div>
        <div className="backtest-tabs">
          {WINDOWS.map((w) => (
            <button
              key={w.days}
              className={`range-tab ${daysAgo === w.days ? "active" : ""}`}
              onClick={() => setDaysAgo(w.days)}
            >{w.label}</button>
          ))}
        </div>
      </div>

      <p className="backtest-question">
        {t("backtest.question")} <strong>{decision}</strong> {t("backtest.daysAgo", { n: daysAgo })}
      </p>

      {loading && <div className="loading-msg">{t("backtest.calculating")}</div>}

      {!loading && result && result.sample_size === 0 && (
        <div className="empty-state" style={{ height: 200 }}>
          <p>{t("backtest.empty")}</p>
          <p className="hint">
            {t("backtest.empty.hint")}
            {status && ` ${t("backtest.snapshotsSoFar", { n: status.snapshot_count.toLocaleString() })}`}
          </p>
        </div>
      )}

      {!loading && result && result.sample_size > 0 && (
        <>
          <div className="backtest-summary">
            <div className="backtest-stat">
              <span className="stat-label">{t("backtest.sample")}</span>
              <span className="stat-value">{result.sample_size}</span>
            </div>
            <div className="backtest-stat">
              <span className="stat-label">{t("backtest.meanReturn")}</span>
              <span className="stat-value" style={{ color: retColor(result.mean_return_bps) }}>
                {retPct(result.mean_return_bps)}
              </span>
            </div>
            <div className="backtest-stat">
              <span className="stat-label">{t("backtest.median")}</span>
              <span className="stat-value" style={{ color: retColor(result.median_return_bps) }}>
                {retPct(result.median_return_bps)}
              </span>
            </div>
            <div className="backtest-stat">
              <span className="stat-label">{t("backtest.winRate")}</span>
              <span className="stat-value">{result.win_rate_pct}%</span>
            </div>
          </div>

          <div className="backtest-columns">
            <div className="backtest-col">
              <h4>{t("backtest.topWinners")}</h4>
              <ul className="backtest-list">
                {result.top_winners.map((e) => (
                  <li key={e.symbol}>
                    <strong>{e.symbol}</strong>
                    <span style={{ color: retColor(e.return_bps), fontWeight: 600 }}>
                      {retPct(e.return_bps)}
                    </span>
                    <span className="entry-prices">
                      {fmt.dollars(e.entry_price_cents)} → {fmt.dollars(e.current_price_cents)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
            <div className="backtest-col">
              <h4>{t("backtest.topLosers")}</h4>
              <ul className="backtest-list">
                {result.top_losers.map((e) => (
                  <li key={e.symbol}>
                    <strong>{e.symbol}</strong>
                    <span style={{ color: retColor(e.return_bps), fontWeight: 600 }}>
                      {retPct(e.return_bps)}
                    </span>
                    <span className="entry-prices">
                      {fmt.dollars(e.entry_price_cents)} → {fmt.dollars(e.current_price_cents)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </>
      )}

      {status && (
        <div className="backtest-footer">
          {t("backtest.snapshotsTotal", { n: status.snapshot_count.toLocaleString() })}
        </div>
      )}
    </div>
  );
}
