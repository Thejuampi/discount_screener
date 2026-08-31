import { useEffect, useState, useCallback, useMemo, useRef } from "react";
import { api, fmt } from "../api";
import type { PortfolioPosition, OpportunityRow, AccuracyRow, SetupLabel, PortfolioRiskResponse } from "../api";
import { useT } from "../i18n";
import { JournalPanel } from "./JournalPanel";
import {
  BOOK_AS_OF_STORAGE_KEY,
  parseAnyCsv,
  planCsvImport,
  type ImportPlan,
  type PortfolioLot,
} from "../portfolioCsv";
import {
  evaluatePortfolioAgainstRegime,
  type PortfolioActionKey,
} from "../portfolioRegimeEval";
import {
  regimeLensFromModel,
  regimeStanceLabelKey,
} from "../regimeSideLens";
import { getScoringPresentation, type ScoringModelId } from "../scoringPresentation";
import { UI, UiInspectable } from "../uiInspect";
import { useMarketRegime } from "../useMarketRegime";

interface Props {
  rows: OpportunityRow[];
  onOpenSymbol: (symbol: string) => void;
  scoringModel: ScoringModelId;
}

// ── Recommendation styles (logic lives in portfolioRegimeEval) ───────────────

type ActionKey = PortfolioActionKey;

const ACTION_STYLE: Record<ActionKey, { color: string; bg: string }> = {
  addStrong:     { color: "#fff",    bg: "linear-gradient(135deg, #16a34a, #15803d)" },
  add:           { color: "#0a0e1c", bg: "linear-gradient(135deg, #4ade80, #22c55e)" },
  hold:          { color: "#94a3b8", bg: "rgba(148,163,184,0.15)" },
  trim:          { color: "#fff",    bg: "linear-gradient(135deg, #fb923c, #f97316)" },
  exit:          { color: "#fff",    bg: "linear-gradient(135deg, #f43f5e, #be123c)" },
  concentration: { color: "#0a0e1c", bg: "linear-gradient(135deg, #fbbf24, #f59e0b)" },
  noData:        { color: "#64748b", bg: "rgba(100,116,139,0.12)" },
  shortRisk:     { color: "#fda4af", bg: "rgba(244,63,94,0.14)" },
};

const POSITIVE_LABELS: SetupLabel[] = ["StrongBuy", "StrongAccumulate"];
const MILD_POSITIVE: SetupLabel[] = ["Buy", "Accumulate"];
const NEGATIVE_LABELS: SetupLabel[] = ["Avoid", "Distribute", "Caution"];
const STRONG_NEGATIVE: SetupLabel[] = ["StrongAvoid"];

// ── Helpers ───────────────────────────────────────────────────────────────────

const money = (cents: number) => {
  const d = cents / 100;
  if (Math.abs(d) >= 1_000_000) return `$${(d / 1_000_000).toFixed(2)}M`;
  if (Math.abs(d) >= 10_000) return `$${(d / 1_000).toFixed(1)}k`;
  return `$${d.toFixed(2)}`;
};

const daysOpen = (openedAt: string | null): number | null => {
  if (!openedAt) return null;
  const d = new Date(openedAt + "T00:00:00");
  if (isNaN(d.getTime())) return null;
  return Math.max(0, Math.floor((Date.now() - d.getTime()) / 86_400_000));
};

// ── Component ─────────────────────────────────────────────────────────────────

export function AdvisorPanel({ rows, onOpenSymbol, scoringModel }: Props) {
  const { t } = useT();
  const presentation = getScoringPresentation(scoringModel);
  const regime = useMarketRegime();
  const lens = regimeLensFromModel(scoringModel);
  const [positions, setPositions] = useState<PortfolioPosition[]>([]);
  const [accuracy, setAccuracy] = useState<AccuracyRow[]>([]);
  const [horizon, setHorizon] = useState(30);
  const [loading, setLoading] = useState(true);
  const [extraPrices, setExtraPrices] = useState<Record<string, number | null>>({});
  const extraPricesRef = useRef<Record<string, number | null>>({});
  const [csvMsg, setCsvMsg] = useState<string | null>(null);
  const [csvPending, setCsvPending] = useState<ImportPlan | null>(null);

  // Risk engine state
  const [risk, setRisk] = useState<PortfolioRiskResponse | null>(null);
  const [riskPct, setRiskPct] = useState(() => {
    const v = parseFloat(localStorage.getItem("ds_risk_per_trade") ?? "1");
    return isFinite(v) && v > 0 ? v : 1;
  });
  const [stopMult, setStopMult] = useState(() => {
    const v = parseFloat(localStorage.getItem("ds_stop_atr_mult") ?? "2");
    return isFinite(v) && v > 0 ? v : 2;
  });
  useEffect(() => { localStorage.setItem("ds_risk_per_trade", String(riskPct)); }, [riskPct]);
  useEffect(() => { localStorage.setItem("ds_stop_atr_mult", String(stopMult)); }, [stopMult]);

  // Form state
  const [formSymbol, setFormSymbol] = useState("");
  const [formQty, setFormQty] = useState("");
  const [formCost, setFormCost] = useState("");
  const [formDate, setFormDate] = useState("");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    setLoading(true);
    api.portfolioList()
      .then((p) => { setPositions(p); setLoading(false); })
      .catch((e) => { console.error(e); setLoading(false); });
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  useEffect(() => {
    api.getModelAccuracy(horizon).then(setAccuracy).catch(console.error);
  }, [horizon]);

  const rowsBySymbol = useMemo(
    () => new Map(rows.map((r) => [r.symbol, r])),
    [rows]
  );

  // Fetch prices for symbols outside the screener universe (on demand, cached)
  useEffect(() => {
    const missing = positions
      .map((p) => p.symbol)
      .filter((s) => !rowsBySymbol.has(s))
      .filter((s) => extraPricesRef.current[s] === undefined);
    if (missing.length === 0) return;
    // Mark as in-flight (null) immediately to avoid duplicate fetches
    for (const s of missing) extraPricesRef.current[s] = null;
    api.getQuotePrices(missing)
      .then((map) => {
        for (const s of missing) {
          extraPricesRef.current[s] = map[s] ?? null;
        }
        setExtraPrices({ ...extraPricesRef.current });
      })
      .catch(console.error);
  }, [positions, rowsBySymbol]);

  // ── Enriched holdings ─────────────────────────────────────────────────────
  const holdings = useMemo(() => {
    const enriched = positions.map((p) => {
      const row = rowsBySymbol.get(p.symbol) ?? null;
      const price = row?.market_price_cents ?? extraPrices[p.symbol] ?? null;
      const value = price != null ? Math.round(p.quantity * price) : null;
      const cost = Math.round(p.quantity * p.avg_cost_cents);
      const pnl = value != null ? value - cost : null;
      const pnlPct = value != null && cost > 0 ? ((value - cost) / cost) * 100 : null;
      return { pos: p, row, price, value, cost, pnl, pnlPct, days: daysOpen(p.opened_at) };
    });
    const totalValue = enriched.reduce((s, h) => s + (h.value ?? h.cost), 0);
    return enriched.map((h) => ({
      ...h,
      weightPct: totalValue > 0 ? ((h.value ?? h.cost) / totalValue) * 100 : 0,
    }));
  }, [positions, rowsBySymbol, extraPrices]);

  const totals = useMemo(() => {
    const value = holdings.reduce((s, h) => s + (h.value ?? 0), 0);
    const cost = holdings.reduce((s, h) => s + h.cost, 0);
    const knownCost = holdings.filter(h => h.value != null).reduce((s, h) => s + h.cost, 0);
    const pnl = value - knownCost;
    const pnlPct = knownCost > 0 ? (pnl / knownCost) * 100 : 0;
    return { value, cost, pnl, pnlPct };
  }, [holdings]);

  // ── Risk engine: fetch ATR + correlation for held symbols ─────────────────
  const heldKey = useMemo(
    () => positions.map((p) => p.symbol).sort().join(","),
    [positions]
  );
  useEffect(() => {
    if (heldKey === "") { setRisk(null); return; }
    let cancelled = false;
    api.getPortfolioRisk(heldKey.split(","))
      .then((r) => { if (!cancelled) setRisk(r); })
      .catch(console.error);
    return () => { cancelled = true; };
  }, [heldKey]);

  const atrBySym = useMemo(() => {
    const m = new Map<string, number>();
    for (const s of risk?.per_symbol ?? []) {
      if (s.atr_cents != null) m.set(s.symbol, s.atr_cents);
    }
    return m;
  }, [risk]);

  // Per-holding stop + risk-at-stop, using ATR (cents).
  const riskRows = useMemo(() => holdings.map((h) => {
    const atr = atrBySym.get(h.pos.symbol) ?? h.row?.atr_cents ?? null;
    const price = h.price;
    if (atr == null || atr <= 0 || price == null || price <= 0) {
      return { h, atr: null as number | null, atrPct: null as number | null,
               stop: null as number | null, riskCents: null as number | null,
               riskPct: null as number | null };
    }
    const stopDist = Math.round(stopMult * atr);
    const stop = Math.max(0, price - stopDist);
    const riskCents = Math.round(h.pos.quantity * stopDist);
    const riskPctOfPort = totals.value > 0 ? (riskCents / totals.value) * 100 : null;
    return { h, atr, atrPct: (atr / price) * 100, stop, riskCents, riskPct: riskPctOfPort };
  }), [holdings, atrBySym, stopMult, totals.value]);

  const totalRiskCents = useMemo(
    () => riskRows.reduce((s, r) => s + (r.riskCents ?? 0), 0),
    [riskRows]
  );
  const totalRiskPct = totals.value > 0 ? (totalRiskCents / totals.value) * 100 : 0;

  // ── Regime-aware portfolio policy (same intel as RegimeBanner) ────────────
  const regimeEval = useMemo(
    () =>
      evaluatePortfolioAgainstRegime({
        regime,
        lens,
        baseRiskPct: riskPct,
        isShort: presentation.isShort,
        holdings: holdings.map((h) => ({
          symbol: h.pos.symbol,
          weightPct: h.weightPct,
          setupLabel: h.row?.setup_label ?? null,
          regimeScore: h.row?.regime_score ?? null,
        })),
      }),
    [regime, lens, riskPct, presentation.isShort, holdings],
  );

  // Position sizing: effective risk % (base × regime mult), stop at stopMult × ATR.
  const suggestSize = useCallback((atrCents: number | null, priceCents: number) => {
    const capital = totals.value;
    if (!atrCents || atrCents <= 0 || priceCents <= 0 || capital <= 0) return null;
    const stopDist = stopMult * atrCents;
    const budget = capital * (regimeEval.effectiveRiskPct / 100);
    let shares = budget / stopDist;
    let alloc = shares * priceCents;
    const maxAlloc = capital * 0.25;
    if (alloc > maxAlloc) { alloc = maxAlloc; shares = alloc / priceCents; }
    return { shares, allocCents: Math.round(alloc), stopCents: Math.round(priceCents - stopDist) };
  }, [totals.value, stopMult, regimeEval.effectiveRiskPct]);

  // ── Risk warnings ─────────────────────────────────────────────────────────
  const warnings = useMemo(() => {
    const out: string[] = [];
    for (const w of regimeEval.warnings) {
      out.push(t(w.key, w.params));
    }
    for (const h of holdings) {
      if (h.weightPct > 25) {
        out.push(t("advisor.warn.position", { symbol: h.pos.symbol, pct: h.weightPct.toFixed(0) }));
      }
    }
    const sectorWeights = new Map<string, number>();
    for (const h of holdings) {
      const sector = h.row?.sector_name ?? null;
      if (!sector || h.value == null) continue;
      sectorWeights.set(sector, (sectorWeights.get(sector) ?? 0) + h.weightPct);
    }
    for (const [sector, pct] of sectorWeights) {
      if (pct > 40) out.push(t("advisor.warn.sector", { sector, pct: pct.toFixed(0) }));
    }
    const cryptoPct = holdings
      .filter(h => h.row?.asset_type === "crypto")
      .reduce((s, h) => s + h.weightPct, 0);
    if (cryptoPct > 30) out.push(t("advisor.warn.crypto", { pct: cryptoPct.toFixed(0) }));
    for (const h of holdings) {
      const label = h.row?.setup_label;
      const adverseForHeldLong = presentation.isShort
        ? label && (POSITIVE_LABELS.includes(label) || MILD_POSITIVE.includes(label))
        : label && (STRONG_NEGATIVE.includes(label) || NEGATIVE_LABELS.includes(label));
      if (label && adverseForHeldLong) {
        out.push(t("advisor.warn.negative", { symbol: h.pos.symbol, label: t(presentation.setupLabelKey(label)) }));
      }
    }
    return out;
  }, [holdings, presentation, regimeEval.warnings, t]);

  // ── Opportunities not owned ───────────────────────────────────────────────
  const ownedSymbols = useMemo(() => new Set(positions.map(p => p.symbol)), [positions]);
  const opportunities = useMemo(() =>
    rows
      .filter(r => !ownedSymbols.has(r.symbol))
      .filter(r => POSITIVE_LABELS.includes(r.setup_label) || MILD_POSITIVE.includes(r.setup_label))
      .sort((a, b) =>
        b.composite_score - a.composite_score ||
        b.setup_score - a.setup_score ||
        (b.regime_score ?? 0) - (a.regime_score ?? 0)
      )
      .slice(0, 6),
    [rows, ownedSymbols]
  );

  // ── Form handlers ─────────────────────────────────────────────────────────
  const resetForm = () => {
    setFormSymbol(""); setFormQty(""); setFormCost(""); setFormDate("");
    setEditingId(null); setFormError(null);
  };

  const handleSubmit = async () => {
    setFormError(null);
    const sym = formSymbol.trim().toUpperCase();
    const qty = parseFloat(formQty);
    const cost = Math.round(parseFloat(formCost) * 100);
    const openedAt = formDate || null;
    if (!sym || !isFinite(qty) || qty <= 0 || !isFinite(cost) || cost <= 0) {
      setFormError("Datos inválidos");
      return;
    }
    try {
      if (editingId != null) {
        await api.portfolioUpdate(editingId, qty, cost, openedAt, null);
      } else {
        await api.portfolioAdd(sym, qty, cost, openedAt, null);
      }
      resetForm();
      refresh();
    } catch (e) {
      setFormError(String(e));
    }
  };

  const startEdit = (p: PortfolioPosition) => {
    setEditingId(p.id);
    setFormSymbol(p.symbol);
    setFormQty(String(p.quantity));
    setFormCost((p.avg_cost_cents / 100).toFixed(2));
    setFormDate(p.opened_at ?? "");
  };

  const handleDelete = async (id: number) => {
    try { await api.portfolioDelete(id); refresh(); }
    catch (e) { console.error(e); }
  };

  const currentLots = (): PortfolioLot[] =>
    positions.map((p) => ({
      symbol: p.symbol,
      quantity: p.quantity,
      avg_cost_cents: p.avg_cost_cents,
      opened_at: p.opened_at,
    }));

  const applyCsvPositions = async (
    format: string,
    lots: PortfolioLot[],
    ignored: number,
    asOf: string | null,
    replaceBook: boolean,
  ) => {
    var res = replaceBook
      ? await api.portfolioReplace(lots)
      : await api.portfolioImport(lots);
    var msg = `[${format}] ` + t("advisor.csv.result", {
      created: res.created,
      updated: res.updated,
      skipped: res.skipped,
      removed: res.removed,
    });
    if (ignored > 0) msg += ` · ${ignored} filas no-trade omitidas`;
    if (asOf) localStorage.setItem(BOOK_AS_OF_STORAGE_KEY, asOf);
    setCsvMsg(msg);
    extraPricesRef.current = {};
    setExtraPrices({});
    refresh();
    setTimeout(() => setCsvMsg(null), 8000);
  };

  const handleCsvFile = async (file: File | null) => {
    if (!file) return;
    setCsvMsg("…");
    setCsvPending(null);
    try {
      var text = await file.text();
      var parsed = parseAnyCsv(text);
      var plan = planCsvImport(parsed, {
        lots: currentLots(),
        bookAsOf: localStorage.getItem(BOOK_AS_OF_STORAGE_KEY),
      });
      if (plan.action === "refuse") {
        var refuseKey = plan.reason === "trades_without_book"
          ? "advisor.csv.refuseNoBook"
          : "advisor.csv.refuseNoAsOf";
        setCsvMsg(`⚠ ${t(refuseKey)}`);
        setTimeout(() => setCsvMsg(null), 8000);
        return;
      }
      if (plan.action === "upsert_ledger") {
        await applyCsvPositions(plan.format, plan.positions, plan.ignored, null, false);
        return;
      }
      setCsvMsg(null);
      setCsvPending(plan);
    } catch (e) {
      setCsvMsg(`⚠ ${e instanceof Error ? e.message : String(e)}`);
      setTimeout(() => setCsvMsg(null), 8000);
    }
  };

  const confirmCsvImport = async () => {
    if (!csvPending) return;
    var plan = csvPending;
    setCsvPending(null);
    try {
      if (plan.action === "confirm_holdings_replace") {
        await applyCsvPositions(plan.format, plan.positions, plan.ignored, plan.asOf, true);
        return;
      }
      if (plan.action === "confirm_trades_merge") {
        await applyCsvPositions(plan.format, plan.positions, plan.ignored, plan.asOf, false);
      }
    } catch (e) {
      setCsvMsg(`⚠ ${e instanceof Error ? e.message : String(e)}`);
      setTimeout(() => setCsvMsg(null), 8000);
    }
  };

  const decisionRows = accuracy.filter(a => a.bucket_type === "decision");
  const scoreRows = accuracy.filter(a => a.bucket_type === "score");

  return (
    <UiInspectable
      as="div"
      className="congress-page"
      source={UI.advisorRoot}
      snapshot={{
        scoringModel,
        positionCount: positions.length,
        loading,
        riskPct,
        stopMult,
        primaryRegime: regimeEval.primaryRegime,
        actionStance: regimeEval.stance,
        riskMult: regimeEval.riskMult,
        effectiveRiskPct: regimeEval.effectiveRiskPct,
        posture: regimeEval.posture,
        suggestedExposurePct: regimeEval.suggestedExposurePct,
        totalRiskCeilingPct: regimeEval.totalRiskCeilingPct,
      }}
    >
      <header className="congress-header">
        <div>
          <h2 className="congress-title">{t("advisor.title")}</h2>
          <p className="congress-subtitle">{t("advisor.subtitle")}</p>
        </div>
      </header>

      {/* ── Health cards ── */}
      <div className="congress-stats">
        <div className="congress-stat-card">
          <span className="congress-stat-label">{t("advisor.totalValue")}</span>
          <span className="congress-stat-value">{money(totals.value)}</span>
        </div>
        <div className="congress-stat-card">
          <span className="congress-stat-label">{t("advisor.totalPnl")}</span>
          <span className="congress-stat-value" style={{ color: totals.pnl >= 0 ? "var(--success)" : "var(--danger)" }}>
            {totals.pnl >= 0 ? "+" : ""}{money(totals.pnl)}
            <span style={{ fontSize: 13, marginLeft: 6 }}>
              ({totals.pnlPct >= 0 ? "+" : ""}{totals.pnlPct.toFixed(1)}%)
            </span>
          </span>
        </div>
        <div className="congress-stat-card">
          <span className="congress-stat-label">{t("advisor.positions")}</span>
          <span className="congress-stat-value">{positions.length}</span>
        </div>
      </div>

      {/* ── Market regime strip (same source as RegimeBanner) ── */}
      <div
        style={{
          marginBottom: 12,
          padding: "10px 14px",
          borderRadius: 8,
          fontSize: 12,
          lineHeight: 1.45,
          background:
            regimeEval.posture === "Defensive"
              ? "rgba(244,63,94,0.08)"
              : regimeEval.posture === "Deploy"
                ? "rgba(34,197,94,0.08)"
                : "rgba(148,163,184,0.08)",
          border: `1px solid ${
            regimeEval.posture === "Defensive"
              ? "rgba(244,63,94,0.35)"
              : regimeEval.posture === "Deploy"
                ? "rgba(34,197,94,0.3)"
                : "rgba(148,163,184,0.3)"
          }`,
        }}
      >
        {!regimeEval.available ? (
          <span style={{ color: "var(--text-4)" }}>{t("advisor.regime.loading")}</span>
        ) : (
          <>
            <div style={{ fontWeight: 600, color: "var(--text-1)" }}>
              {t("advisor.regime.strip", {
                phase: (() => {
                  const k = `regime.phase.${regimeEval.primaryRegime}`;
                  const lab = t(k);
                  return lab !== k ? lab : String(regimeEval.primaryRegime ?? "—");
                })(),
                stance: (() => {
                  const sk = regimeStanceLabelKey(regimeEval.stance, lens);
                  const lab = t(sk);
                  if (lab !== sk) return lab;
                  const longK = `regime.stance.${regimeEval.stance}`;
                  const longLab = t(longK);
                  return longLab !== longK ? longLab : String(regimeEval.stance ?? "—");
                })(),
                exp: regimeEval.suggestedExposurePct ?? "—",
                mult: regimeEval.riskMult.toFixed(2),
                conf:
                  regimeEval.globalConfidenceBps != null
                    ? (regimeEval.globalConfidenceBps / 100).toFixed(0)
                    : "—",
              })}
            </div>
            <div style={{ marginTop: 4, color: "var(--text-3)", fontSize: 11 }}>
              {t("advisor.regime.effectiveRisk", {
                effective: regimeEval.effectiveRiskPct.toFixed(2),
                base: riskPct,
                mult: regimeEval.riskMult.toFixed(2),
              })}
            </div>
          </>
        )}
      </div>

      {/* ── Risk warnings ── */}
      {(positions.length > 0 || regimeEval.warnings.length > 0) && (
        <div className="info-section">
          <h3>{t("advisor.warnings")}</h3>
          {warnings.length === 0 ? (
            <div style={{ color: "var(--success)", fontSize: 13 }}>{t("advisor.noWarnings")}</div>
          ) : (
            <ul className="advisor-warning-list">
              {warnings.map((w, i) => <li key={i}>{w}</li>)}
            </ul>
          )}
        </div>
      )}

      {/* ── Portfolio table + form ── */}
      <div className="info-section">
        <h3>{t("advisor.portfolio")}</h3>
        {presentation.isShort && (
          <p className="analyst-perspective-note">{t("presentation.short.advisor.longActionsPaused")}</p>
        )}

        <div className="advisor-form">
          <input
            className="search"
            placeholder={t("advisor.form.symbol")}
            value={formSymbol}
            onChange={(e) => setFormSymbol(e.target.value)}
            disabled={editingId != null}
            style={{ width: 120 }}
            list="advisor-symbols"
          />
          <datalist id="advisor-symbols">
            {rows.map(r => <option key={r.symbol} value={r.symbol} />)}
          </datalist>
          <input
            className="search"
            type="number"
            placeholder={t("advisor.form.quantity")}
            value={formQty}
            onChange={(e) => setFormQty(e.target.value)}
            style={{ width: 100 }}
          />
          <input
            className="search"
            type="number"
            placeholder={t("advisor.form.avgCost")}
            value={formCost}
            onChange={(e) => setFormCost(e.target.value)}
            style={{ width: 120 }}
          />
          <input
            className="search"
            type="date"
            title={t("advisor.form.date")}
            value={formDate}
            onChange={(e) => setFormDate(e.target.value)}
            style={{ width: 150 }}
          />
          <button className="congress-sync-btn" onClick={handleSubmit}>
            {editingId != null ? t("advisor.form.save") : `+ ${t("advisor.form.add")}`}
          </button>
          {editingId != null && (
            <button className="btn-ghost" onClick={resetForm}>{t("advisor.form.cancel")}</button>
          )}

          {/* CSV bulk import */}
          <label className="btn-ghost" style={{ cursor: "pointer" }} title={t("advisor.csv.help")}>
            📄 {t("advisor.csv.import")}
            <input
              type="file"
              accept=".csv,text/csv"
              style={{ display: "none" }}
              onChange={(e) => {
                handleCsvFile(e.target.files?.[0] ?? null);
                e.target.value = "";
              }}
            />
          </label>

          {formError && <span style={{ color: "var(--danger)", fontSize: 12 }}>{formError}</span>}
          {csvMsg && <span style={{ color: csvMsg.startsWith("⚠") ? "var(--danger)" : "var(--success)", fontSize: 12 }}>{csvMsg}</span>}
          {formSymbol.trim() !== "" && !rowsBySymbol.has(formSymbol.trim().toUpperCase()) && editingId == null && (
            <span style={{ color: "var(--warning)", fontSize: 11 }}>{t("advisor.form.unknownSymbol")}</span>
          )}
        </div>
        {csvPending && (csvPending.action === "confirm_holdings_replace" || csvPending.action === "confirm_trades_merge") && (
          <div
            style={{
              marginBottom: 10,
              padding: "10px 14px",
              borderRadius: 8,
              fontSize: 12,
              lineHeight: 1.45,
              background: "rgba(251,191,36,0.12)",
              border: "1px solid rgba(251,191,36,0.45)",
              color: "var(--text-2)",
            }}
          >
            <div>
              {csvPending.action === "confirm_holdings_replace"
                ? t("advisor.csv.warnHoldings", {
                    count: csvPending.positions.length,
                    remove: csvPending.remove.length,
                    asOf: csvPending.asOf ?? "—",
                  })
                : t("advisor.csv.warnTrades", { asOf: csvPending.asOf })}
            </div>
            {csvPending.action === "confirm_holdings_replace" && csvPending.remove.length > 0 && (
              <div style={{ marginTop: 6, fontFamily: "var(--font-mono, ui-monospace, monospace)" }}>
                {t("advisor.csv.warnHoldingsRemove", { names: csvPending.remove.join(", ") })}
              </div>
            )}
            <div style={{ marginTop: 8, display: "flex", gap: 8 }}>
              <button className="congress-sync-btn" onClick={confirmCsvImport}>
                {t("advisor.csv.confirm")}
              </button>
              <button className="btn-ghost" onClick={() => setCsvPending(null)}>
                {t("advisor.form.cancel")}
              </button>
            </div>
          </div>
        )}
        <div style={{ fontSize: 10, color: "var(--text-5)", marginBottom: 10 }}>
          {t("advisor.csv.help")}
        </div>

        {loading ? (
          <div className="loading-msg">…</div>
        ) : positions.length === 0 ? (
          <div style={{ color: "var(--text-4)", fontSize: 13, padding: "12px 0" }}>
            {t("advisor.empty")}
          </div>
        ) : (
          <table className="stock-table">
            <thead>
              <tr>
                <th>{t("advisor.col.position")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.col.qty")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.col.avgCost")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.col.price")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.col.value")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.col.pnl")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.col.weight")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.col.days")}</th>
                <th>{t("advisor.col.signal")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.col.fit")}</th>
                <th>{t("advisor.col.action")}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {holdings.map((h) => {
                const action =
                  regimeEval.actionsBySymbol[h.pos.symbol] ??
                  (presentation.isShort && h.row ? "shortRisk" : "noData");
                const st = ACTION_STYLE[action];
                const fit = h.row?.regime_score;
                const fitColor =
                  fit == null
                    ? "var(--text-5)"
                    : fit >= 20
                      ? "var(--success)"
                      : fit <= -20
                        ? "var(--danger)"
                        : "var(--text-3)";
                return (
                  <tr key={h.pos.id}>
                    <td>
                      <strong
                        style={{ cursor: h.row ? "pointer" : "default", color: h.row ? "var(--accent)" : "var(--text-1)" }}
                        onClick={() => h.row && onOpenSymbol(h.pos.symbol)}
                        title={h.row ? t("advisor.openInScreener") : undefined}
                      >
                        {h.pos.symbol}
                      </strong>
                    </td>
                    <td className="num-cell" style={{ textAlign: "right" }}>{h.pos.quantity}</td>
                    <td className="num-cell" style={{ textAlign: "right" }}>{fmt.dollars(h.pos.avg_cost_cents)}</td>
                    <td className="num-cell" style={{ textAlign: "right" }}>
                      {h.price != null ? fmt.dollars(h.price) : "—"}
                    </td>
                    <td className="num-cell" style={{ textAlign: "right" }}>
                      {h.value != null ? money(h.value) : "—"}
                    </td>
                    <td className="num-cell" style={{
                      textAlign: "right",
                      color: h.pnl == null ? "var(--text-4)" : h.pnl >= 0 ? "var(--success)" : "var(--danger)",
                      fontWeight: 700,
                    }}>
                      {h.pnl != null
                        ? `${h.pnl >= 0 ? "+" : ""}${money(h.pnl)} (${h.pnlPct! >= 0 ? "+" : ""}${h.pnlPct!.toFixed(1)}%)`
                        : "—"}
                    </td>
                    <td className="num-cell" style={{ textAlign: "right" }}>{h.weightPct.toFixed(1)}%</td>
                    <td className="num-cell" style={{ textAlign: "right" }}>
                      {h.days != null ? `${h.days}d` : "—"}
                    </td>
                    <td>
                      {h.row ? (
                        <span style={{ fontSize: 11, fontWeight: 700 }}>
                          {t(presentation.setupLabelKey(h.row.setup_label))} ({h.row.setup_score > 0 ? "+" : ""}{h.row.setup_score})
                        </span>
                      ) : "—"}
                    </td>
                    <td className="num-cell" style={{ textAlign: "right", color: fitColor, fontWeight: 700, fontSize: 12 }}>
                      {fit != null ? `${fit > 0 ? "+" : ""}${fit}` : "—"}
                    </td>
                    <td>
                      <span className="advisor-action-chip" style={{ background: st.bg, color: st.color }}>
                        {action === "shortRisk" ? t("presentation.short.advisor.action") : t(`advisor.action.${action}`)}
                      </span>
                    </td>
                    <td>
                      <button className="btn-ghost" style={{ padding: "2px 8px" }} onClick={() => startEdit(h.pos)}>✎</button>
                      <button className="btn-ghost" style={{ padding: "2px 8px", marginLeft: 4 }} onClick={() => handleDelete(h.pos.id)}>🗑</button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {/* ── Risk management (stops + sizing + correlation) ── */}
      {positions.length > 0 && (
        <div className="info-section">
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 8 }}>
            <h3 style={{ margin: 0 }}>{t("advisor.risk.title")}</h3>
            <div style={{ display: "flex", gap: 14, alignItems: "center" }}>
              <label style={{ fontSize: 11, color: "var(--text-3)", display: "flex", alignItems: "center", gap: 5 }}>
                {t("advisor.risk.perTrade")}
                <select className="search" style={{ width: 64, padding: "2px 4px" }}
                  value={riskPct} onChange={(e) => setRiskPct(parseFloat(e.target.value))}>
                  {[0.5, 1, 1.5, 2, 3].map((v) => <option key={v} value={v}>{v}%</option>)}
                </select>
              </label>
              <label style={{ fontSize: 11, color: "var(--text-3)", display: "flex", alignItems: "center", gap: 5 }}>
                {t("advisor.risk.stopMult")}
                <select className="search" style={{ width: 60, padding: "2px 4px" }}
                  value={stopMult} onChange={(e) => setStopMult(parseFloat(e.target.value))}>
                  {[1.5, 2, 2.5, 3].map((v) => <option key={v} value={v}>{v}×</option>)}
                </select>
              </label>
            </div>
          </div>
          <p style={{ fontSize: 11, color: "var(--text-4)", lineHeight: 1.5, margin: "8px 0" }}>
            {t("advisor.risk.help")}
          </p>

          {/* Total portfolio risk budget (ceiling scales with regime mult) */}
          <div className="advisor-risk-total" style={{
            display: "flex", justifyContent: "space-between", alignItems: "center", gap: 10,
            padding: "10px 14px", borderRadius: 8, marginBottom: 12,
            background: totalRiskPct > regimeEval.totalRiskCeilingPct ? "rgba(244,63,94,0.12)" : "rgba(34,197,94,0.10)",
            border: `1px solid ${totalRiskPct > regimeEval.totalRiskCeilingPct ? "rgba(244,63,94,0.4)" : "rgba(34,197,94,0.3)"}`,
          }}>
            <div>
              <div style={{ fontSize: 11, color: "var(--text-3)" }}>{t("advisor.risk.totalAtRisk")}</div>
              <div style={{ fontSize: 10, color: "var(--text-5)", maxWidth: 520, lineHeight: 1.4 }}>
                {t("advisor.risk.totalAtRiskHelp")}
                {" · "}
                {t("advisor.risk.ceilingNote", {
                  ceiling: regimeEval.totalRiskCeilingPct.toFixed(1),
                })}
              </div>
            </div>
            <div style={{ textAlign: "right", whiteSpace: "nowrap" }}>
              <span style={{ fontSize: 20, fontWeight: 800, color: totalRiskPct > regimeEval.totalRiskCeilingPct ? "var(--danger)" : "var(--success)" }}>
                {money(totalRiskCents)}
              </span>
              <span style={{ fontSize: 13, marginLeft: 6, color: "var(--text-3)" }}>
                ({totalRiskPct.toFixed(1)}%)
              </span>
            </div>
          </div>

          <table className="stock-table">
            <thead>
              <tr>
                <th>{t("advisor.col.position")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.col.price")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.risk.col.atr")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.risk.col.stop")}</th>
                <th style={{ textAlign: "right" }}>{t("advisor.risk.col.atRisk")}</th>
              </tr>
            </thead>
            <tbody>
              {riskRows.map((r) => (
                <tr key={r.h.pos.id}>
                  <td><strong>{r.h.pos.symbol}</strong></td>
                  <td className="num-cell" style={{ textAlign: "right" }}>
                    {r.h.price != null ? fmt.dollars(r.h.price) : "—"}
                  </td>
                  <td className="num-cell" style={{ textAlign: "right" }}>
                    {r.atrPct != null
                      ? <span title={r.atr != null ? fmt.dollars(r.atr) : ""}>{r.atrPct.toFixed(1)}%</span>
                      : <span style={{ color: "var(--text-5)", fontSize: 11 }}>{t("advisor.risk.noAtr")}</span>}
                  </td>
                  <td className="num-cell" style={{ textAlign: "right" }}>
                    {r.stop != null ? fmt.dollars(r.stop) : "—"}
                  </td>
                  <td className="num-cell" style={{ textAlign: "right" }}>
                    {r.riskCents != null ? (
                      <span style={{ color: (r.riskPct ?? 0) > 2 ? "var(--danger)" : "var(--text-1)", fontWeight: 700 }}>
                        {money(r.riskCents)}
                        {r.riskPct != null && <span style={{ fontSize: 11, color: "var(--text-4)", marginLeft: 4 }}>({r.riskPct.toFixed(1)}%)</span>}
                      </span>
                    ) : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Hidden concentration via correlation */}
          <div style={{ marginTop: 16 }}>
            <div style={{ fontSize: 10, color: "var(--text-4)", textTransform: "uppercase", letterSpacing: "0.06em", fontWeight: 700, marginBottom: 6 }}>
              {t("advisor.risk.corrTitle")}
            </div>
            <p style={{ fontSize: 11, color: "var(--text-4)", lineHeight: 1.5, margin: "0 0 8px" }}>
              {t("advisor.risk.corrHelp", { days: risk?.lookback_days ?? 120 })}
            </p>
            {!risk || risk.high_corr_pairs.length === 0 ? (
              <div style={{ color: "var(--success)", fontSize: 13 }}>{t("advisor.risk.corrNone")}</div>
            ) : (
              <ul className="advisor-warning-list">
                {risk.high_corr_pairs.map((p, i) => (
                  <li key={i}>
                    {t("advisor.risk.corrPair", { a: p.a, b: p.b, corr: (p.corr_milli / 1000).toFixed(2) })}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}

      {/* ── Opportunities not owned ── */}
      <div className="info-section">
        <h3>{t(presentation.advisorOpportunityKey)}</h3>
        {!presentation.isShort &&
          regimeEval.available &&
          !regimeEval.lowConfidence &&
          regimeEval.posture === "Defensive" &&
          regimeEval.addBias < 0 && (
            <p style={{ fontSize: 11, color: "var(--warning, #fbbf24)", lineHeight: 1.45, margin: "0 0 10px" }}>
              {t("advisor.regime.oppCaution")}
            </p>
          )}
        {opportunities.length === 0 ? (
          <div style={{ color: "var(--text-4)", fontSize: 13 }}>{t(presentation.advisorEmptyKey)}</div>
        ) : (
          <div className="advisor-opps">
            {opportunities.map((r) => {
              const size = suggestSize(r.atr_cents, r.market_price_cents);
              const fit = r.regime_score;
              return (
                <div key={r.symbol} className="advisor-opp-card" onClick={() => onOpenSymbol(r.symbol)}>
                  <div className="advisor-opp-head">
                    <strong>{r.symbol}</strong>
                    <span style={{ fontSize: 11, fontWeight: 800, color: "var(--success)" }}>
                      {t(presentation.setupLabelKey(r.setup_label))} +{r.setup_score}
                      {fit != null && (
                        <span style={{ marginLeft: 6, color: "var(--text-3)", fontWeight: 600 }}>
                          · fit {fit > 0 ? "+" : ""}{fit}
                        </span>
                      )}
                    </span>
                  </div>
                  <div className="advisor-opp-meta">
                    <span>{r.company_name ?? "—"}</span>
                    <span>{fmt.dollars(r.market_price_cents)}</span>
                  </div>
                  <div style={{ fontSize: 10, color: "var(--text-4)", marginTop: 6, borderTop: "1px solid var(--border)", paddingTop: 5 }}>
                    {presentation.isShort ? t("presentation.short.advisor.noSizing") : size ? (
                      <>
                        <span style={{ color: "var(--text-3)" }}>{t("advisor.risk.sizeHint")}: </span>
                        {t("advisor.risk.sizeDetail", {
                          shares: size.shares >= 10 ? Math.round(size.shares) : size.shares.toFixed(2),
                          alloc: money(size.allocCents),
                          stop: fmt.dollars(size.stopCents),
                        })}
                      </>
                    ) : t("advisor.risk.needValue")}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* ── Model accuracy (live snapshots) ── */}
      <div className="info-section">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 8 }}>
          <h3 style={{ margin: 0 }}>{t("advisor.accuracy")}</h3>
          <div className="profile-options">
            {[7, 30, 90].map((d) => (
              <button
                key={d}
                className={`profile-btn ${horizon === d ? "active" : ""}`}
                onClick={() => setHorizon(d)}
              >{d}d</button>
            ))}
          </div>
        </div>
        <p style={{ fontSize: 11, color: "var(--text-4)", lineHeight: 1.5, margin: "8px 0" }}>
          {t("advisor.accuracy.help")}
        </p>

        {accuracy.length === 0 ? (
          <div style={{ color: "var(--text-4)", fontSize: 13 }}>{t("advisor.accuracy.empty")}</div>
        ) : (
          <div className="accuracy-grid">
            <AccuracyTable title={t("advisor.accuracy.byDecision")} rows={decisionRows} t={t} />
            <AccuracyTable title={t("advisor.accuracy.byScore")} rows={scoreRows} t={t} />
          </div>
        )}
      </div>

      {/* ── Investment journal ── */}
      <JournalPanel rows={rows} />
    </UiInspectable>
  );
}

function AccuracyTable({
  title, rows, t,
}: {
  title: string;
  rows: AccuracyRow[];
  t: (k: string, v?: Record<string, string | number>) => string;
}) {
  return (
    <div>
      <div style={{ fontSize: 10, color: "var(--text-4)", textTransform: "uppercase", letterSpacing: "0.06em", fontWeight: 700, marginBottom: 6 }}>
        {title}
      </div>
      <table className="stock-table">
        <thead>
          <tr>
            <th></th>
            <th style={{ textAlign: "right" }}>{t("advisor.accuracy.samples")}</th>
            <th style={{ textAlign: "right" }}>{t("advisor.accuracy.avgReturn")}</th>
            <th style={{ textAlign: "right" }}>{t("advisor.accuracy.winRate")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((a) => (
            <tr key={a.bucket}>
              <td><strong>{a.bucket}</strong></td>
              <td className="num-cell" style={{ textAlign: "right" }}>
                {a.samples}
                {a.samples < 30 && (
                  <span style={{ color: "var(--warning)", fontSize: 9, marginLeft: 4 }}>
                    ({t("advisor.accuracy.lowSample")})
                  </span>
                )}
              </td>
              <td className="num-cell" style={{
                textAlign: "right",
                color: a.avg_return_bps > 0 ? "var(--success)" : a.avg_return_bps < 0 ? "var(--danger)" : "var(--text-4)",
                fontWeight: 700,
              }}>
                {(a.avg_return_bps / 100).toFixed(2)}%
              </td>
              <td className="num-cell" style={{ textAlign: "right" }}>{a.win_rate_pct}%</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
