import { useEffect, useState, useCallback, useMemo, useRef } from "react";
import { api, fmt } from "../api";
import type { PortfolioPosition, OpportunityRow, AccuracyRow, SetupLabel, ImportPosition, PortfolioRiskResponse } from "../api";
import { useT } from "../i18n";
import { JournalPanel } from "./JournalPanel";

interface Props {
  rows: OpportunityRow[];
  onOpenSymbol: (symbol: string) => void;
}

// ── Recommendation engine (rule-based, explainable) ──────────────────────────

type ActionKey = "addStrong" | "add" | "hold" | "trim" | "exit" | "concentration" | "noData";

const ACTION_STYLE: Record<ActionKey, { color: string; bg: string }> = {
  addStrong:     { color: "#fff",    bg: "linear-gradient(135deg, #16a34a, #15803d)" },
  add:           { color: "#0a0e1c", bg: "linear-gradient(135deg, #4ade80, #22c55e)" },
  hold:          { color: "#94a3b8", bg: "rgba(148,163,184,0.15)" },
  trim:          { color: "#fff",    bg: "linear-gradient(135deg, #fb923c, #f97316)" },
  exit:          { color: "#fff",    bg: "linear-gradient(135deg, #f43f5e, #be123c)" },
  concentration: { color: "#0a0e1c", bg: "linear-gradient(135deg, #fbbf24, #f59e0b)" },
  noData:        { color: "#64748b", bg: "rgba(100,116,139,0.12)" },
};

const POSITIVE_LABELS: SetupLabel[] = ["StrongBuy", "StrongAccumulate"];
const MILD_POSITIVE: SetupLabel[] = ["Buy", "Accumulate"];
const NEGATIVE_LABELS: SetupLabel[] = ["Avoid", "Distribute", "Caution"];
const STRONG_NEGATIVE: SetupLabel[] = ["StrongAvoid"];

function recommend(label: SetupLabel | null, weightPct: number): ActionKey {
  if (weightPct > 25) return "concentration";
  if (label == null) return "noData";
  if (STRONG_NEGATIVE.includes(label)) return "exit";
  if (NEGATIVE_LABELS.includes(label)) return "trim";
  if (POSITIVE_LABELS.includes(label)) return "addStrong";
  if (MILD_POSITIVE.includes(label)) return "add";
  return "hold";
}

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

// ── CSV transaction parsing + aggregation ─────────────────────────────────────

interface CsvTx {
  symbol: string;
  side: "buy" | "sell";
  quantity: number;
  price: number;       // dollars per share
  date: string;        // ISO or "" if absent
}

/// CSV line splitter with double-quote support (Schwab quotes every field and
/// embeds commas inside, e.g. "Tfr JPMORGAN CHASE BAN, N/A").
function splitCsvLine(line: string, delim: string): string[] {
  const out: string[] = [];
  let cur = "";
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') {
      if (inQuotes && line[i + 1] === '"') { cur += '"'; i++; }
      else inQuotes = !inQuotes;
    } else if (ch === delim && !inQuotes) {
      out.push(cur.trim());
      cur = "";
    } else {
      cur += ch;
    }
  }
  out.push(cur.trim());
  return out;
}

type CsvFormat = "coinbase" | "schwab" | "generic";

function detectFormat(lines: string[]): CsvFormat {
  const head = lines.slice(0, 6).join("\n").toLowerCase();
  if (head.includes("transaction type") && head.includes("quantity transacted")) return "coinbase";
  const first = lines[0].toLowerCase();
  if (first.includes("action") && first.includes("symbol") && (first.includes("fees & comm") || first.includes("amount"))) return "schwab";
  return "generic";
}

// ── Coinbase native format ────────────────────────────────────────────────────
// Preamble lines before the real header; assets without -USD suffix; staking
// income counts as a buy at market price (that's its cost basis).

const COINBASE_BUY_TYPES = [
  "buy", "advanced trade buy", "staking income", "learning reward",
  "rewards income", "inflation reward", "receive", "coinbase earn",
];
const COINBASE_SELL_TYPES = ["sell", "advanced trade sell", "send", "withdrawal"];

function parseCoinbase(lines: string[]): { txs: CsvTx[]; ignored: number } {
  const headerIdx = lines.findIndex((l) => {
    const lo = l.toLowerCase();
    return lo.includes("transaction type") && lo.includes("quantity transacted");
  });
  if (headerIdx < 0) throw new Error("Header de Coinbase no encontrado");
  const headers = splitCsvLine(lines[headerIdx], ",").map((h) => h.toLowerCase());
  const iType = headers.findIndex((h) => h === "transaction type");
  const iAsset = headers.findIndex((h) => h === "asset");
  const iQty = headers.findIndex((h) => h === "quantity transacted");
  const iPrice = headers.findIndex((h) => h.startsWith("price at transaction"));
  const iDate = headers.findIndex((h) => h === "timestamp");
  if (iType < 0 || iAsset < 0 || iQty < 0 || iPrice < 0) {
    throw new Error("Columnas de Coinbase incompletas");
  }

  const txs: CsvTx[] = [];
  let ignored = 0;
  for (let i = headerIdx + 1; i < lines.length; i++) {
    const cols = splitCsvLine(lines[i], ",");
    const type = (cols[iType] ?? "").toLowerCase();
    const asset = (cols[iAsset] ?? "").toUpperCase();
    // Skip fiat rows (USD deposits/withdrawals are cash moves, not positions)
    if (!asset || asset === "USD") { ignored++; continue; }
    const side: "buy" | "sell" | null =
      COINBASE_BUY_TYPES.includes(type) ? "buy"
      : COINBASE_SELL_TYPES.includes(type) ? "sell"
      : null;
    if (!side) { ignored++; continue; }
    const quantity = Math.abs(normalizeNum(cols[iQty] ?? "", false));
    const price = normalizeNum(cols[iPrice] ?? "", false);
    if (!isFinite(quantity) || quantity <= 0 || !isFinite(price) || price <= 0) { ignored++; continue; }
    const rawDate = (cols[iDate] ?? "").trim();
    const date = /^\d{4}-\d{2}-\d{2}/.test(rawDate) ? rawDate.slice(0, 10) : "";
    // Map to the app's Yahoo-style crypto symbol (BTC → BTC-USD)
    const symbol = asset.endsWith("-USD") ? asset : `${asset}-USD`;
    txs.push({ symbol, side, quantity, price, date });
  }
  if (txs.length === 0) throw new Error("Ninguna transacción de trading en el CSV de Coinbase");
  return { txs, ignored };
}

// ── Charles Schwab native format ──────────────────────────────────────────────
// "Reinvest Shares" is a real share purchase (dividend reinvestment).
// Dividends/interest/transfers/splits have no qty+price → skipped automatically.
// Schwab dates are US-format MM/DD/YYYY, sometimes with "as of ..." suffix.

const SCHWAB_BUY_ACTIONS = ["buy", "reinvest shares"];
const SCHWAB_SELL_ACTIONS = ["sell"];

function parseSchwab(lines: string[]): { txs: CsvTx[]; ignored: number } {
  const headers = splitCsvLine(lines[0], ",").map((h) => h.toLowerCase());
  const iDate = headers.findIndex((h) => h === "date");
  const iAction = headers.findIndex((h) => h === "action");
  const iSym = headers.findIndex((h) => h === "symbol");
  const iQty = headers.findIndex((h) => h === "quantity");
  const iPrice = headers.findIndex((h) => h === "price");
  if (iAction < 0 || iSym < 0 || iQty < 0 || iPrice < 0) {
    throw new Error("Columnas de Schwab incompletas");
  }

  const txs: CsvTx[] = [];
  let ignored = 0;
  for (let i = 1; i < lines.length; i++) {
    const cols = splitCsvLine(lines[i], ",");
    const action = (cols[iAction] ?? "").toLowerCase();
    const symbol = (cols[iSym] ?? "").toUpperCase();
    const side: "buy" | "sell" | null =
      SCHWAB_BUY_ACTIONS.includes(action) ? "buy"
      : SCHWAB_SELL_ACTIONS.includes(action) ? "sell"
      : null;
    // Reject non-trades and odd identifiers (CUSIPs from splits start with a digit)
    if (!side || !symbol || !/^[A-Z][A-Z0-9.]*$/.test(symbol)) { ignored++; continue; }
    const quantity = Math.abs(normalizeNum(cols[iQty] ?? "", false));
    const price = normalizeNum(cols[iPrice] ?? "", false);
    if (!isFinite(quantity) || quantity <= 0 || !isFinite(price) || price <= 0) { ignored++; continue; }
    const m = (cols[iDate] ?? "").match(/(\d{2})\/(\d{2})\/(\d{4})/);
    const date = m ? `${m[3]}-${m[1]}-${m[2]}` : "";   // MM/DD/YYYY → ISO
    txs.push({ symbol, side, quantity, price, date });
  }
  if (txs.length === 0) throw new Error("Ninguna transacción Buy/Sell en el CSV de Schwab");
  return { txs, ignored };
}

/// Entry point: detect format, dispatch to the right parser.
function parseAnyCsv(text: string): { txs: CsvTx[]; ignored: number; format: string } {
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== "");
  if (lines.length < 2) throw new Error("CSV vacío o sin filas de datos");
  const fmtKind = detectFormat(lines);
  if (fmtKind === "coinbase") return { ...parseCoinbase(lines), format: "Coinbase" };
  if (fmtKind === "schwab") return { ...parseSchwab(lines), format: "Schwab" };
  return { txs: parseCsvTransactions(text), ignored: 0, format: "genérico" };
}

function normalizeNum(raw: string, semicolonCsv: boolean): number {
  let s = raw.replace(/[$\s"]/g, "");
  if (s.includes(".") && s.includes(",")) {
    // "1,234.56" → thousands commas
    s = s.replace(/,/g, "");
  } else if (s.includes(",") && (semicolonCsv || !s.includes("."))) {
    // latin decimal comma "250,50"
    s = s.replace(",", ".");
  }
  return parseFloat(s);
}

function normalizeDate(raw: string): string {
  const s = raw.trim().replace(/"/g, "");
  if (!s) return "";
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) return s.slice(0, 10);
  // DD/MM/YYYY (latin convention)
  const m = s.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
  if (m) {
    const [, d, mo, y] = m;
    return `${y}-${mo.padStart(2, "0")}-${d.padStart(2, "0")}`;
  }
  return "";
}

function parseCsvTransactions(text: string): CsvTx[] {
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== "");
  if (lines.length < 2) throw new Error("CSV vacío o sin filas de datos");

  const semicolonCsv = lines[0].includes(";") && !lines[0].includes(",");
  const delim = semicolonCsv ? ";" : ",";
  const headers = splitCsvLine(lines[0], delim).map((h) => h.toLowerCase().replace(/"/g, ""));

  const find = (names: string[]) => headers.findIndex((h) => names.includes(h));
  const iSym = find(["symbol", "ticker", "simbolo", "símbolo"]);
  const iSide = find(["side", "tipo", "operacion", "operación", "type", "transaction"]);
  const iQty = find(["quantity", "qty", "cantidad", "shares", "unidades"]);
  const iPrice = find(["price", "precio", "valor", "value", "cost", "costo"]);
  const iDate = find(["date", "fecha"]);

  const missing: string[] = [];
  if (iSym < 0) missing.push("symbol");
  if (iSide < 0) missing.push("side (compra/venta)");
  if (iQty < 0) missing.push("quantity");
  if (iPrice < 0) missing.push("price");
  if (missing.length > 0) {
    throw new Error(`Columnas faltantes: ${missing.join(", ")}. Encontradas: ${headers.join(", ")}`);
  }

  const txs: CsvTx[] = [];
  for (let i = 1; i < lines.length; i++) {
    const cols = splitCsvLine(lines[i], delim);
    const symbol = (cols[iSym] ?? "").replace(/"/g, "").toUpperCase();
    if (!symbol) continue;
    const sideRaw = (cols[iSide] ?? "").replace(/"/g, "").toLowerCase();
    const side: "buy" | "sell" | null =
      ["buy", "compra", "b", "c", "purchase"].includes(sideRaw) ? "buy"
      : ["sell", "venta", "v", "s", "sale"].includes(sideRaw) ? "sell"
      : null;
    if (!side) continue;
    const quantity = normalizeNum(cols[iQty] ?? "", semicolonCsv);
    const price = normalizeNum(cols[iPrice] ?? "", semicolonCsv);
    if (!isFinite(quantity) || quantity <= 0 || !isFinite(price) || price <= 0) continue;
    const date = iDate >= 0 ? normalizeDate(cols[iDate] ?? "") : "";
    txs.push({ symbol, side, quantity, price, date });
  }
  if (txs.length === 0) throw new Error("Ninguna fila válida en el CSV");
  return txs;
}

/// Aggregate chronological transactions into net positions with average cost.
function aggregateToPositions(txs: CsvTx[]): ImportPosition[] {
  // Stable sort by date (rows without date keep their relative order)
  const sorted = [...txs].sort((a, b) =>
    a.date && b.date ? a.date.localeCompare(b.date) : 0
  );

  interface Acc { qty: number; avgCost: number; openedAt: string | null }
  const acc = new Map<string, Acc>();

  for (const tx of sorted) {
    const cur = acc.get(tx.symbol) ?? { qty: 0, avgCost: 0, openedAt: null };
    if (tx.side === "buy") {
      const newQty = cur.qty + tx.quantity;
      cur.avgCost = (cur.avgCost * cur.qty + tx.price * tx.quantity) / newQty;
      if (cur.qty === 0) cur.openedAt = tx.date || null;  // position (re)opened
      cur.qty = newQty;
    } else {
      cur.qty -= tx.quantity;
      if (cur.qty <= 0.000001) {
        cur.qty = 0;
        cur.avgCost = 0;
        cur.openedAt = null;  // fully closed
      }
    }
    acc.set(tx.symbol, cur);
  }

  const out: ImportPosition[] = [];
  for (const [symbol, a] of acc) {
    // Skip dust positions (< $1 total cost) — e.g. micro staking rewards
    if (a.qty > 0 && a.avgCost > 0 && a.qty * a.avgCost >= 1) {
      out.push({
        symbol,
        quantity: Math.round(a.qty * 10000) / 10000,
        avg_cost_cents: Math.round(a.avgCost * 100),
        opened_at: a.openedAt,
      });
    }
  }
  if (out.length === 0) throw new Error("El CSV no resulta en ninguna posición abierta (todas las posiciones quedaron en 0)");
  return out;
}

// ── Component ─────────────────────────────────────────────────────────────────

export function AdvisorPanel({ rows, onOpenSymbol }: Props) {
  const { t } = useT();
  const [positions, setPositions] = useState<PortfolioPosition[]>([]);
  const [accuracy, setAccuracy] = useState<AccuracyRow[]>([]);
  const [horizon, setHorizon] = useState(30);
  const [loading, setLoading] = useState(true);
  const [extraPrices, setExtraPrices] = useState<Record<string, number | null>>({});
  const extraPricesRef = useRef<Record<string, number | null>>({});
  const [csvMsg, setCsvMsg] = useState<string | null>(null);

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

  // Position sizing for a new buy: shares that risk `riskPct`% of capital,
  // stopping out at `stopMult` × ATR, capped at 25% of capital (concentration).
  const suggestSize = useCallback((atrCents: number | null, priceCents: number) => {
    const capital = totals.value;
    if (!atrCents || atrCents <= 0 || priceCents <= 0 || capital <= 0) return null;
    const stopDist = stopMult * atrCents;
    const budget = capital * (riskPct / 100);
    let shares = budget / stopDist;
    let alloc = shares * priceCents;
    const maxAlloc = capital * 0.25;
    if (alloc > maxAlloc) { alloc = maxAlloc; shares = alloc / priceCents; }
    return { shares, allocCents: Math.round(alloc), stopCents: Math.round(priceCents - stopDist) };
  }, [totals.value, stopMult, riskPct]);

  // ── Risk warnings ─────────────────────────────────────────────────────────
  const warnings = useMemo(() => {
    const out: string[] = [];
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
      if (label && (STRONG_NEGATIVE.includes(label) || NEGATIVE_LABELS.includes(label))) {
        out.push(t("advisor.warn.negative", { symbol: h.pos.symbol, label: t(`setup.${label}`) }));
      }
    }
    return out;
  }, [holdings, t]);

  // ── Opportunities not owned ───────────────────────────────────────────────
  const ownedSymbols = useMemo(() => new Set(positions.map(p => p.symbol)), [positions]);
  const opportunities = useMemo(() =>
    rows
      .filter(r => !ownedSymbols.has(r.symbol))
      .filter(r => POSITIVE_LABELS.includes(r.setup_label) || MILD_POSITIVE.includes(r.setup_label))
      .sort((a, b) => b.composite_score - a.composite_score || b.setup_score - a.setup_score)
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

  // ── CSV import ────────────────────────────────────────────────────────────
  const handleCsvFile = async (file: File | null) => {
    if (!file) return;
    setCsvMsg("…");
    try {
      const text = await file.text();
      const { txs, ignored, format } = parseAnyCsv(text);
      const finals = aggregateToPositions(txs);
      const res = await api.portfolioImport(finals);
      let msg = `[${format}] ` + t("advisor.csv.result", { created: res.created, updated: res.updated, skipped: res.skipped });
      if (ignored > 0) msg += ` · ${ignored} filas no-trade omitidas`;
      setCsvMsg(msg);
      // Invalidate price cache for newly added unknown symbols
      extraPricesRef.current = {};
      setExtraPrices({});
      refresh();
    } catch (e) {
      setCsvMsg(`⚠ ${e instanceof Error ? e.message : String(e)}`);
    }
    setTimeout(() => setCsvMsg(null), 8000);
  };

  const decisionRows = accuracy.filter(a => a.bucket_type === "decision");
  const scoreRows = accuracy.filter(a => a.bucket_type === "score");

  return (
    <div className="congress-page">
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

      {/* ── Risk warnings ── */}
      {positions.length > 0 && (
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
                <th>{t("advisor.col.action")}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {holdings.map((h) => {
                const action = recommend(h.row?.setup_label ?? null, h.weightPct);
                const st = ACTION_STYLE[action];
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
                          {t(`setup.${h.row.setup_label}`)} ({h.row.setup_score > 0 ? "+" : ""}{h.row.setup_score})
                        </span>
                      ) : "—"}
                    </td>
                    <td>
                      <span className="advisor-action-chip" style={{ background: st.bg, color: st.color }}>
                        {t(`advisor.action.${action}`)}
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

          {/* Total portfolio risk budget */}
          <div className="advisor-risk-total" style={{
            display: "flex", justifyContent: "space-between", alignItems: "center", gap: 10,
            padding: "10px 14px", borderRadius: 8, marginBottom: 12,
            background: totalRiskPct > 6 ? "rgba(244,63,94,0.12)" : "rgba(34,197,94,0.10)",
            border: `1px solid ${totalRiskPct > 6 ? "rgba(244,63,94,0.4)" : "rgba(34,197,94,0.3)"}`,
          }}>
            <div>
              <div style={{ fontSize: 11, color: "var(--text-3)" }}>{t("advisor.risk.totalAtRisk")}</div>
              <div style={{ fontSize: 10, color: "var(--text-5)", maxWidth: 520, lineHeight: 1.4 }}>
                {t("advisor.risk.totalAtRiskHelp")}
              </div>
            </div>
            <div style={{ textAlign: "right", whiteSpace: "nowrap" }}>
              <span style={{ fontSize: 20, fontWeight: 800, color: totalRiskPct > 6 ? "var(--danger)" : "var(--success)" }}>
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
        <h3>{t("advisor.opportunities")}</h3>
        {opportunities.length === 0 ? (
          <div style={{ color: "var(--text-4)", fontSize: 13 }}>{t("advisor.opportunities.empty")}</div>
        ) : (
          <div className="advisor-opps">
            {opportunities.map((r) => {
              const size = suggestSize(r.atr_cents, r.market_price_cents);
              return (
                <div key={r.symbol} className="advisor-opp-card" onClick={() => onOpenSymbol(r.symbol)}>
                  <div className="advisor-opp-head">
                    <strong>{r.symbol}</strong>
                    <span style={{ fontSize: 11, fontWeight: 800, color: "var(--success)" }}>
                      {t(`setup.${r.setup_label}`)} +{r.setup_score}
                    </span>
                  </div>
                  <div className="advisor-opp-meta">
                    <span>{r.company_name ?? "—"}</span>
                    <span>{fmt.dollars(r.market_price_cents)}</span>
                  </div>
                  <div style={{ fontSize: 10, color: "var(--text-4)", marginTop: 6, borderTop: "1px solid var(--border)", paddingTop: 5 }}>
                    {size ? (
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
    </div>
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
