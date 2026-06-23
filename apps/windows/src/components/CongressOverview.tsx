import { useEffect, useState, useCallback, useRef } from "react";
import { api } from "../api";
import type {
  CongressOverview as TOverview,
  CongressSyncProgress,
  CongressBacktestResult,
  PoliticianWithMetrics,
  PoliticianActivityRow,
  CongressTickerRow,
} from "../api";
import { useT } from "../i18n";
import { PoliticianDetailModal } from "./PoliticianDetailModal";

type Tab = "tickers" | "politicians";

// ── Sort helpers ─────────────────────────────────────────────────────────────

type TickerSortKey = keyof CongressTickerRow;
type PolSortKey =
  | "full_name" | "chamber" | "total_trades" | "purchase_count" | "sale_count"
  | "estimated_total_gain_cents" | "avg_return_90d_bps" | "avg_return_180d_bps"
  | "win_rate_90d_pct" | "win_rate_180d_pct"
  | "avg_alpha_90d_bps" | "avg_alpha_180d_bps"
  | "confidence_score" | "qualifying_trades";

function sortBy<T extends Record<string, any>>(rows: T[], key: keyof T, asc: boolean): T[] {
  return [...rows].sort((a, b) => {
    const av = a[key]; const bv = b[key];
    if (av == null && bv == null) return 0;
    if (av == null) return 1;
    if (bv == null) return -1;
    if (typeof av === "string") return asc ? av.localeCompare(bv as string) : (bv as string).localeCompare(av);
    return asc ? (av as number) - (bv as number) : (bv as number) - (av as number);
  });
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
  const dollars = cents / 100;
  if (Math.abs(dollars) >= 1_000_000) return `$${(dollars / 1_000_000).toFixed(2)}M`;
  if (Math.abs(dollars) >= 1_000) return `$${(dollars / 1_000).toFixed(1)}k`;
  return `$${dollars.toFixed(0)}`;
}
function fmtAmount(min: number | null, max: number | null): string {
  if (!max) return "—";
  const fmt = (n: number) =>
    n >= 1_000_000 ? `$${(n / 1_000_000).toFixed(1)}M`
    : n >= 1_000 ? `$${(n / 1_000).toFixed(0)}k`
    : `$${n}`;
  return `${fmt(min ?? 0)} – ${fmt(max)}`;
}

// ─────────────────────────────────────────────────────────────────────────────

export function CongressOverviewPanel() {
  const { t } = useT();
  const [overview, setOverview] = useState<TOverview | null>(null);
  const [polMetrics, setPolMetrics] = useState<PoliticianWithMetrics[]>([]);
  const [days, setDays] = useState<number>(180);
  const [loading, setLoading] = useState(true);
  const [backtesting, setBacktesting] = useState(false);
  const [backtestResult, setBacktestResult] = useState<CongressBacktestResult | null>(null);

  // Sync dialog state
  const [syncDialogOpen, setSyncDialogOpen] = useState(false);
  const [selectedYears, setSelectedYears] = useState<number[]>([new Date().getFullYear()]);
  const [maxPerYearStr, setMaxPerYearStr] = useState<string>("");  // empty = unlimited
  const [syncProgress, setSyncProgress] = useState<CongressSyncProgress | null>(null);
  const pollTimerRef = useRef<number | null>(null);

  // Filters
  const [tab, setTab] = useState<Tab>("tickers");
  const [searchSymbol, setSearchSymbol] = useState("");
  const [searchPolitician, setSearchPolitician] = useState("");
  const [chamberFilter, setChamberFilter] = useState<"all" | "House" | "Senate">("all");

  // Sort state
  const [tickerSort, setTickerSort] = useState<{ k: TickerSortKey; asc: boolean }>(
    { k: "buy_count", asc: false }
  );
  const [polSort, setPolSort] = useState<{ k: PolSortKey; asc: boolean }>(
    { k: "estimated_total_gain_cents", asc: false }
  );

  // Selected politician for modal
  const [selectedPoliticianId, setSelectedPoliticianId] = useState<number | null>(null);

  const refresh = useCallback(() => {
    setLoading(true);
    Promise.all([
      api.getCongressOverview(days),
      api.getTopPoliticiansRanked("gain", 200).catch(() => []),
    ]).then(([o, pm]) => {
      setOverview(o);
      setPolMetrics(pm);
      setLoading(false);
    }).catch((e) => { console.error(e); setLoading(false); });
  }, [days]);

  useEffect(() => { refresh(); }, [refresh]);

  // Poll progress while sync is running
  useEffect(() => {
    if (!syncDialogOpen) return;
    const tick = async () => {
      try {
        const p = await api.getCongressSyncProgress();
        setSyncProgress(p);
        if (!p.running && p.years_completed.length > 0) {
          // Finished — refresh main data
          refresh();
        }
      } catch (e) { console.error(e); }
    };
    tick();
    pollTimerRef.current = window.setInterval(tick, 1500);
    return () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
        pollTimerRef.current = null;
      }
    };
  }, [syncDialogOpen, refresh]);

  const handleStartSync = async () => {
    if (selectedYears.length === 0) return;
    try {
      const max = maxPerYearStr.trim() ? parseInt(maxPerYearStr, 10) : undefined;
      await api.syncCongressHouse(selectedYears, max);
    } catch (e) {
      console.error("sync start failed", e);
    }
  };

  const toggleYear = (y: number) => {
    setSelectedYears(prev =>
      prev.includes(y) ? prev.filter(x => x !== y) : [...prev, y].sort()
    );
  };

  const handleBacktest = async () => {
    setBacktesting(true);
    setBacktestResult(null);
    try {
      const result = await api.computeCongressMetrics();
      setBacktestResult(result);
      refresh();
    } catch (e) {
      console.error("backtest failed", e);
    } finally { setBacktesting(false); }
  };

  // Filter + sort tickers
  const filteredTickers = (overview?.top_tickers ?? [])
    .filter(t => !searchSymbol || t.symbol.toLowerCase().includes(searchSymbol.toLowerCase()));
  const sortedTickers = sortBy(filteredTickers, tickerSort.k, tickerSort.asc);

  // Build politician rows: prefer metrics where available, fall back to activity
  const polActivityById = new Map((overview?.top_politicians ?? []).map(p => [p.politician_id, p]));
  const allPolIds = new Set<number>([
    ...polMetrics.map(p => p.politician_id),
    ...(overview?.top_politicians ?? []).map(p => p.politician_id),
  ]);
  const merged: PoliticianRowExt[] = Array.from(allPolIds).map(id => {
    const m = polMetrics.find(p => p.politician_id === id);
    const a = polActivityById.get(id);
    if (m) {
      return {
        ...m,
        last_disclosure_date: a?.last_disclosure_date ?? null,
        has_metrics: true,
      };
    }
    if (a) {
      return {
        politician_id: a.politician_id,
        full_name: a.full_name,
        chamber: a.chamber,
        state: a.state,
        district: a.district,
        total_trades: a.trade_count,
        purchase_count: a.buy_count,
        sale_count: a.sell_count,
        avg_return_30d_bps: null, avg_return_90d_bps: null, avg_return_180d_bps: null,
        win_rate_30d_pct: null, win_rate_90d_pct: null, win_rate_180d_pct: null,
        avg_alpha_90d_bps: null, avg_alpha_180d_bps: null,
        estimated_total_gain_cents: 0,
        confidence_score: 0,
        qualifying_trades: 0,
        last_disclosure_date: a.last_disclosure_date ?? null,
        has_metrics: false,
      };
    }
    return null as any;
  }).filter(Boolean);

  const filteredPoliticians = merged
    .filter(p => !searchPolitician || p.full_name.toLowerCase().includes(searchPolitician.toLowerCase()))
    .filter(p => chamberFilter === "all" || p.chamber === chamberFilter);
  const sortedPoliticians = sortBy(filteredPoliticians, polSort.k as any, polSort.asc);

  const sortHeader = (label: string, key: any, current: { k: any; asc: boolean }, setter: (s: any) => void, tooltip?: string) => {
    const active = current.k === key;
    const arrow = active ? (current.asc ? " ▲" : " ▼") : "";
    return (
      <th
        className={`sortable-th ${active ? "sort-active" : ""}`}
        onClick={() => {
          if (active) setter({ k: key, asc: !current.asc });
          else setter({ k: key, asc: false });
        }}
        title={tooltip ?? label}
      >
        {label}{arrow}
      </th>
    );
  };

  return (
    <div className="congress-page">
      <header className="congress-header">
        <div>
          <h2 className="congress-title">🏛 {t("congress.title")}</h2>
          <p className="congress-subtitle">{t("congress.subtitle")}</p>
        </div>
        <div className="congress-actions">
          <button className="btn-ghost" onClick={() => setSyncDialogOpen(true)}>
            ↓ {t("congress.sync")}
          </button>
          <button
            className="congress-sync-btn"
            onClick={handleBacktest}
            disabled={backtesting || (overview?.trade_count ?? 0) === 0}
            title={t("congress.metricsHelp")}
          >
            {backtesting ? `⏳ ${t("congress.computingMetrics")}` : `📊 ${t("congress.computeMetrics")}`}
          </button>
        </div>
      </header>

      <div className="congress-disclaimer">{t("congress.disclaimer")}</div>

      {backtestResult && (
        <div className="congress-sync-result">
          <span>📊 Backtest:</span>
          <span><strong>{backtestResult.symbols_processed}</strong> symbols</span>
          <span><strong>{backtestResult.trades_with_outcomes}</strong> trades</span>
          <span><strong>{backtestResult.politicians_updated}</strong> politicians updated</span>
        </div>
      )}

      {/* Stats */}
      <div className="congress-stats">
        <div className="congress-stat-card">
          <span className="congress-stat-label">{t("congress.stats.politicians")}</span>
          <span className="congress-stat-value">{overview?.politician_count ?? 0}</span>
        </div>
        <div className="congress-stat-card">
          <span className="congress-stat-label">{t("congress.stats.trades")}</span>
          <span className="congress-stat-value">{overview?.trade_count ?? 0}</span>
        </div>
        <div className="congress-window-selector">
          <span className="congress-window-label">{t("congress.window")}</span>
          <div className="profile-options">
            {[30, 90, 180, 99999].map((d) => (
              <button
                key={d}
                className={`profile-btn ${days === d ? "active" : ""}`}
                onClick={() => setDays(d)}
              >
                {d === 30 ? t("congress.window.30")
                  : d === 90 ? t("congress.window.90")
                  : d === 180 ? t("congress.window.180")
                  : t("congress.window.all")}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Tabs + filters */}
      <div className="congress-tabs-bar">
        <div className="profile-options">
          <button
            className={`profile-btn ${tab === "tickers" ? "active" : ""}`}
            onClick={() => setTab("tickers")}
          >📈 {t("congress.tabs.tickers")}</button>
          <button
            className={`profile-btn ${tab === "politicians" ? "active" : ""}`}
            onClick={() => setTab("politicians")}
          >👥 {t("congress.tabs.politicians")}</button>
        </div>

        <div className="congress-filters">
          {tab === "tickers" && (
            <input
              className="search"
              placeholder={t("congress.searchSymbol")}
              value={searchSymbol}
              onChange={(e) => setSearchSymbol(e.target.value)}
              style={{ width: 200 }}
            />
          )}
          {tab === "politicians" && (
            <>
              <input
                className="search"
                placeholder={t("congress.searchPolitician")}
                value={searchPolitician}
                onChange={(e) => setSearchPolitician(e.target.value)}
                style={{ width: 220 }}
              />
              <select
                className="filter-select"
                value={chamberFilter}
                onChange={(e) => setChamberFilter(e.target.value as any)}
              >
                <option value="all">{t("congress.filterAll")}</option>
                <option value="House">{t("congress.filterHouse")}</option>
                <option value="Senate">{t("congress.filterSenate")}</option>
              </select>
            </>
          )}
        </div>
      </div>

      {/* Content */}
      {loading ? (
        <div className="loading-msg">{t("status.loading")}…</div>
      ) : overview && overview.trade_count === 0 ? (
        <div className="empty-state" style={{ height: 200 }}>
          <p>{t("congress.empty")}</p>
        </div>
      ) : tab === "tickers" ? (
        <div className="info-section">
          <h3>{t("congress.topTickers")}</h3>
          <table className="stock-table">
            <thead>
              <tr>
                {sortHeader(t("congress.col.symbol"), "symbol", tickerSort, setTickerSort)}
                {sortHeader(t("congress.col.buys"), "buy_count", tickerSort, setTickerSort)}
                {sortHeader(t("congress.col.sells"), "sell_count", tickerSort, setTickerSort)}
                {sortHeader(t("congress.col.politicians"), "unique_politicians", tickerSort, setTickerSort)}
                {sortHeader(t("congress.col.amount"), "total_amount_max", tickerSort, setTickerSort)}
                {sortHeader(t("congress.col.lastDisclosure"), "last_disclosure_date", tickerSort, setTickerSort)}
              </tr>
            </thead>
            <tbody>
              {sortedTickers.map((row) => (
                <tr key={row.symbol}>
                  <td><strong>{row.symbol}</strong></td>
                  <td className="num-cell" style={{ color: row.buy_count > 0 ? "var(--success)" : "var(--text-4)" }}>
                    {row.buy_count}
                  </td>
                  <td className="num-cell" style={{ color: row.sell_count > 0 ? "var(--danger)" : "var(--text-4)" }}>
                    {row.sell_count}
                  </td>
                  <td className="num-cell">{row.unique_politicians}</td>
                  <td className="num-cell" style={{ fontSize: 11 }}>
                    {fmtAmount(row.total_amount_min, row.total_amount_max)}
                  </td>
                  <td className="num-cell" style={{ fontSize: 11 }}>{row.last_disclosure_date ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="info-section">
          <h3>{t("congress.topPoliticians")}</h3>
          {polMetrics.length === 0 && (
            <div className="congress-hint">{t("congress.noBacktestYet")}</div>
          )}
          <table className="stock-table">
            <thead>
              <tr>
                {sortHeader(t("congress.col.name"), "full_name", polSort, setPolSort)}
                {sortHeader(t("congress.col.chamber"), "chamber", polSort, setPolSort)}
                {sortHeader(t("congress.col.trades"), "total_trades", polSort, setPolSort)}
                {sortHeader(t("congress.col.buys"), "purchase_count", polSort, setPolSort)}
                {sortHeader(t("congress.col.sells"), "sale_count", polSort, setPolSort)}
                {sortHeader(`${t("congress.col.avgReturn")} 90d`, "avg_return_90d_bps", polSort, setPolSort)}
                {sortHeader(`${t("congress.col.winRate")} 90d`, "win_rate_90d_pct", polSort, setPolSort)}
                {sortHeader(`${t("congress.col.alpha")} 90d`, "avg_alpha_90d_bps", polSort, setPolSort)}
                {sortHeader(t("congress.col.estGain"), "estimated_total_gain_cents", polSort, setPolSort)}
                {sortHeader(t("congress.col.confidence"), "confidence_score", polSort, setPolSort, t("congress.confidence.label"))}
              </tr>
            </thead>
            <tbody>
              {sortedPoliticians.map((p) => (
                <tr
                  key={p.politician_id}
                  className="stock-row"
                  onClick={() => setSelectedPoliticianId(p.politician_id)}
                >
                  <td><strong>{p.full_name}</strong></td>
                  <td style={{ fontSize: 11 }}>
                    {p.chamber} {p.state ?? ""}{p.district ? `-${p.district}` : ""}
                  </td>
                  <td className="num-cell">{p.total_trades}</td>
                  <td className="num-cell" style={{ color: "var(--success)" }}>{p.purchase_count}</td>
                  <td className="num-cell" style={{ color: "var(--danger)" }}>{p.sale_count}</td>
                  <td className="num-cell" style={{ color: bpsColor(p.avg_return_90d_bps) }}>
                    {fmtBps(p.avg_return_90d_bps)}
                  </td>
                  <td className="num-cell">{p.win_rate_90d_pct != null ? `${p.win_rate_90d_pct}%` : "—"}</td>
                  <td className="num-cell" style={{ color: bpsColor(p.avg_alpha_90d_bps) }}>
                    {fmtBps(p.avg_alpha_90d_bps)}
                  </td>
                  <td className="num-cell" style={{ color: p.estimated_total_gain_cents > 0 ? "var(--success)" : p.estimated_total_gain_cents < 0 ? "var(--danger)" : "var(--text-4)" }}>
                    {fmtMoney(p.estimated_total_gain_cents)}
                  </td>
                  <td className="num-cell">{p.confidence_score}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selectedPoliticianId !== null && (
        <PoliticianDetailModal
          politicianId={selectedPoliticianId}
          onClose={() => setSelectedPoliticianId(null)}
        />
      )}

      {syncDialogOpen && (
        <SyncDialog
          selectedYears={selectedYears}
          onToggleYear={toggleYear}
          maxPerYearStr={maxPerYearStr}
          onChangeMax={setMaxPerYearStr}
          progress={syncProgress}
          onStart={handleStartSync}
          onClose={() => {
            // Don't allow closing while running — protect from user closing mid-sync
            if (syncProgress?.running) return;
            setSyncDialogOpen(false);
            setSyncProgress(null);
          }}
        />
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────

interface SyncDialogProps {
  selectedYears: number[];
  onToggleYear: (y: number) => void;
  maxPerYearStr: string;
  onChangeMax: (v: string) => void;
  progress: CongressSyncProgress | null;
  onStart: () => void;
  onClose: () => void;
}

function SyncDialog({
  selectedYears, onToggleYear, maxPerYearStr, onChangeMax,
  progress, onStart, onClose,
}: SyncDialogProps) {
  const { t } = useT();
  const currentYear = new Date().getFullYear();
  const years = [currentYear - 2, currentYear - 1, currentYear];
  const running = progress?.running ?? false;
  const pct = progress && progress.total > 0
    ? Math.round((progress.processed / progress.total) * 100)
    : 0;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" style={{ maxWidth: 600 }} onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>↓ {t("congress.sync")}</h3>
          {!running && (
            <button className="close-btn" onClick={onClose}>✕</button>
          )}
        </div>

        {!running && (!progress || progress.years_completed.length === 0) && (
          <>
            <div className="sync-section">
              <div className="sync-section-label">{t("congress.syncYearLabel")}</div>
              <div className="sync-year-options">
                {years.map(y => (
                  <label key={y} className="sync-year-option">
                    <input
                      type="checkbox"
                      checked={selectedYears.includes(y)}
                      onChange={() => onToggleYear(y)}
                    />
                    <span>{y}</span>
                  </label>
                ))}
              </div>
            </div>
            <div className="sync-section">
              <div className="sync-section-label">
                Max PTRs por año (opcional, dejá vacío para todos)
              </div>
              <input
                className="search"
                type="number"
                placeholder="ej: 500"
                value={maxPerYearStr}
                onChange={(e) => onChangeMax(e.target.value)}
                style={{ width: 160 }}
              />
            </div>
            <div className="sync-estimate">{t("congress.syncEstimate")}</div>
            <button
              className="congress-sync-btn"
              onClick={onStart}
              disabled={selectedYears.length === 0}
              style={{ alignSelf: "flex-start" }}
            >
              {t("congress.startSync")}
            </button>
          </>
        )}

        {progress && (
          <div className="sync-progress">
            <div className="sync-step-text">{progress.current_step}</div>
            {progress.total > 0 && (
              <>
                <div className="sync-bar">
                  <div className="sync-bar-fill" style={{ width: `${pct}%` }} />
                </div>
                <div className="sync-counters">
                  <span>{t("congress.syncProcessed")}: <strong>{progress.processed}/{progress.total}</strong> ({pct}%)</span>
                  <span>{t("congress.sync.imported")} año {progress.current_year}: <strong style={{ color: "var(--success)" }}>{progress.trades_imported}</strong></span>
                </div>
              </>
            )}
            <div className="sync-totals">
              {t("congress.syncTotalImported")}: <strong style={{ color: "var(--success)" }}>{progress.total_imported_session}</strong>
              {progress.years_completed.length > 0 && (
                <> · Años completos: <strong>{progress.years_completed.join(", ")}</strong></>
              )}
            </div>
            {progress.last_error && (
              <div style={{ color: "var(--danger)", fontSize: 11 }}>⚠ {progress.last_error}</div>
            )}
            {!running && (
              <button className="btn-ghost" onClick={onClose} style={{ alignSelf: "flex-start", marginTop: 12 }}>
                {t("congress.cancelSync")}
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

interface PoliticianRowExt extends PoliticianWithMetrics {
  last_disclosure_date: string | null;
  has_metrics: boolean;
}

// Phantom (just to make TS happy) — PoliticianActivityRow is used inside refresh callback
const _phantom: PoliticianActivityRow | null = null;
void _phantom;
