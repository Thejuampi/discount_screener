export const BOOK_AS_OF_STORAGE_KEY = "ds_advisor_book_as_of";

export type CsvKind = "holdings_snapshot" | "trades_window" | "trades_ledger";

export interface CsvTx {
  symbol: string;
  side: "buy" | "sell";
  quantity: number;
  price: number;
  date: string;
}

export interface PortfolioLot {
  symbol: string;
  quantity: number;
  avg_cost_cents: number;
  opened_at: string | null;
}

export interface ParsedCsv {
  txs: CsvTx[];
  ignored: number;
  format: string;
  kind: CsvKind;
  asOf: string | null;
}

export type RefuseReason = "trades_without_book" | "missing_book_as_of";

export type ImportPlan =
  | {
      action: "confirm_holdings_replace";
      format: string;
      kind: "holdings_snapshot";
      asOf: string | null;
      positions: PortfolioLot[];
      remove: string[];
      ignored: number;
    }
  | {
      action: "confirm_trades_merge";
      format: string;
      kind: "trades_window";
      asOf: string;
      positions: PortfolioLot[];
      applied: number;
      skipped: number;
      ignored: number;
    }
  | {
      action: "upsert_ledger";
      format: string;
      kind: "trades_ledger";
      positions: PortfolioLot[];
      ignored: number;
    }
  | {
      action: "refuse";
      reason: RefuseReason;
      format: string;
    };

type CsvFormatId = "coinbase" | "schwab" | "jpm_holdings" | "chase_trades" | "generic";

function splitCsvLine(line: string, delim: string): string[] {
  var out: string[] = [];
  var cur = "";
  var inQuotes = false;
  for (var i = 0; i < line.length; i++) {
    var ch = line[i];
    if (ch === '"') {
      if (inQuotes && line[i + 1] === '"') {
        cur += '"';
        i++;
      } else {
        inQuotes = !inQuotes;
      }
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

function detectFormat(lines: string[]): CsvFormatId {
  var head = lines.slice(0, 6).join("\n").toLowerCase();
  if (head.includes("transaction type") && head.includes("quantity transacted")) return "coinbase";
  var first = lines[0].toLowerCase();
  if (first.includes("action") && first.includes("symbol") && (first.includes("fees & comm") || first.includes("amount"))) {
    return "schwab";
  }
  if (first.includes("asset class") && first.includes("unit cost") && first.includes("ticker")) return "jpm_holdings";
  if (first.includes("trade date") && first.includes("price usd") && first.includes("type")) return "chase_trades";
  return "generic";
}

const COINBASE_BUY_TYPES = [
  "buy", "advanced trade buy", "staking income", "learning reward",
  "rewards income", "inflation reward", "receive", "coinbase earn",
];
const COINBASE_SELL_TYPES = ["sell", "advanced trade sell", "send", "withdrawal"];

function parseCoinbase(lines: string[]): { txs: CsvTx[]; ignored: number } {
  var headerIdx = lines.findIndex((l) => {
    var lo = l.toLowerCase();
    return lo.includes("transaction type") && lo.includes("quantity transacted");
  });
  if (headerIdx < 0) throw new Error("Header de Coinbase no encontrado");
  var headers = splitCsvLine(lines[headerIdx], ",").map((h) => h.toLowerCase());
  var iType = headers.findIndex((h) => h === "transaction type");
  var iAsset = headers.findIndex((h) => h === "asset");
  var iQty = headers.findIndex((h) => h === "quantity transacted");
  var iPrice = headers.findIndex((h) => h.startsWith("price at transaction"));
  var iDate = headers.findIndex((h) => h === "timestamp");
  if (iType < 0 || iAsset < 0 || iQty < 0 || iPrice < 0) {
    throw new Error("Columnas de Coinbase incompletas");
  }

  var txs: CsvTx[] = [];
  var ignored = 0;
  for (var i = headerIdx + 1; i < lines.length; i++) {
    var cols = splitCsvLine(lines[i], ",");
    var type = (cols[iType] ?? "").toLowerCase();
    var asset = (cols[iAsset] ?? "").toUpperCase();
    if (!asset || asset === "USD") { ignored++; continue; }
    var side: "buy" | "sell" | null =
      COINBASE_BUY_TYPES.includes(type) ? "buy"
      : COINBASE_SELL_TYPES.includes(type) ? "sell"
      : null;
    if (!side) { ignored++; continue; }
    var quantity = Math.abs(normalizeNum(cols[iQty] ?? "", false));
    var price = normalizeNum(cols[iPrice] ?? "", false);
    if (!isFinite(quantity) || quantity <= 0 || !isFinite(price) || price <= 0) { ignored++; continue; }
    var rawDate = (cols[iDate] ?? "").trim();
    var date = /^\d{4}-\d{2}-\d{2}/.test(rawDate) ? rawDate.slice(0, 10) : "";
    var symbol = asset.endsWith("-USD") ? asset : `${asset}-USD`;
    txs.push({ symbol, side, quantity, price, date });
  }
  if (txs.length === 0) throw new Error("Ninguna transacción de trading en el CSV de Coinbase");
  return { txs, ignored };
}

const SCHWAB_BUY_ACTIONS = ["buy", "reinvest shares"];
const SCHWAB_SELL_ACTIONS = ["sell"];

function parseSchwab(lines: string[]): { txs: CsvTx[]; ignored: number } {
  var headers = splitCsvLine(lines[0], ",").map((h) => h.toLowerCase());
  var iDate = headers.findIndex((h) => h === "date");
  var iAction = headers.findIndex((h) => h === "action");
  var iSym = headers.findIndex((h) => h === "symbol");
  var iQty = headers.findIndex((h) => h === "quantity");
  var iPrice = headers.findIndex((h) => h === "price");
  if (iAction < 0 || iSym < 0 || iQty < 0 || iPrice < 0) {
    throw new Error("Columnas de Schwab incompletas");
  }

  var txs: CsvTx[] = [];
  var ignored = 0;
  for (var i = 1; i < lines.length; i++) {
    var cols = splitCsvLine(lines[i], ",");
    var action = (cols[iAction] ?? "").toLowerCase();
    var symbol = (cols[iSym] ?? "").toUpperCase();
    var side: "buy" | "sell" | null =
      SCHWAB_BUY_ACTIONS.includes(action) ? "buy"
      : SCHWAB_SELL_ACTIONS.includes(action) ? "sell"
      : null;
    if (!side || !symbol || !/^[A-Z][A-Z0-9.]*$/.test(symbol)) { ignored++; continue; }
    var quantity = Math.abs(normalizeNum(cols[iQty] ?? "", false));
    var price = normalizeNum(cols[iPrice] ?? "", false);
    if (!isFinite(quantity) || quantity <= 0 || !isFinite(price) || price <= 0) { ignored++; continue; }
    var m = (cols[iDate] ?? "").match(/(\d{2})\/(\d{2})\/(\d{4})/);
    var date = m ? `${m[3]}-${m[1]}-${m[2]}` : "";
    txs.push({ symbol, side, quantity, price, date });
  }
  if (txs.length === 0) throw new Error("Ninguna transacción Buy/Sell en el CSV de Schwab");
  return { txs, ignored };
}

function normalizeNum(raw: string, semicolonCsv: boolean): number {
  var s = raw.replace(/[$\s"]/g, "");
  if (s.includes(".") && s.includes(",")) {
    s = s.replace(/,/g, "");
  } else if (s.includes(",") && (semicolonCsv || !s.includes("."))) {
    s = s.replace(",", ".");
  }
  return parseFloat(s);
}

function normalizeUsNum(raw: string): number {
  var s = raw.replace(/[$\s"]/g, "");
  if (!s) return NaN;
  if (s.includes(".") && s.includes(",")) {
    s = s.replace(/,/g, "");
  } else if (/^-?\d{1,3}(,\d{3})+$/.test(s)) {
    s = s.replace(/,/g, "");
  } else if (s.includes(",") && !s.includes(".")) {
    s = s.replace(",", ".");
  }
  return parseFloat(s);
}

function normalizeDate(raw: string): string {
  var s = raw.trim().replace(/"/g, "");
  if (!s) return "";
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) return s.slice(0, 10);
  var m = s.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
  if (m) {
    var d = m[1];
    var mo = m[2];
    var y = m[3];
    return `${y}-${mo.padStart(2, "0")}-${d.padStart(2, "0")}`;
  }
  return "";
}

function normalizeUsDate(raw: string): string {
  var s = raw.trim().replace(/"/g, "");
  if (!s) return "";
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) return s.slice(0, 10);
  var m = s.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})/);
  if (m) {
    return `${m[3]}-${m[1].padStart(2, "0")}-${m[2].padStart(2, "0")}`;
  }
  return "";
}

function parseCsvTransactions(text: string): CsvTx[] {
  var lines = text.split(/\r?\n/).filter((l) => l.trim() !== "");
  if (lines.length < 2) throw new Error("CSV vacío o sin filas de datos");

  var semicolonCsv = lines[0].includes(";") && !lines[0].includes(",");
  var delim = semicolonCsv ? ";" : ",";
  var headers = splitCsvLine(lines[0], delim).map((h) => h.toLowerCase().replace(/"/g, ""));

  var find = (names: string[]) => headers.findIndex((h) => names.includes(h));
  var iSym = find(["symbol", "ticker", "simbolo", "símbolo"]);
  var iSide = find(["side", "tipo", "operacion", "operación", "type", "transaction"]);
  var iQty = find(["quantity", "qty", "cantidad", "shares", "unidades"]);
  var iPrice = find(["price", "precio", "valor", "value", "cost", "costo"]);
  var iDate = find(["date", "fecha"]);

  var missing: string[] = [];
  if (iSym < 0) missing.push("symbol");
  if (iSide < 0) missing.push("side (compra/venta)");
  if (iQty < 0) missing.push("quantity");
  if (iPrice < 0) missing.push("price");
  if (missing.length > 0) {
    throw new Error(`Columnas faltantes: ${missing.join(", ")}. Encontradas: ${headers.join(", ")}`);
  }

  var txs: CsvTx[] = [];
  for (var i = 1; i < lines.length; i++) {
    var cols = splitCsvLine(lines[i], delim);
    var symbol = (cols[iSym] ?? "").replace(/"/g, "").toUpperCase();
    if (!symbol) continue;
    var sideRaw = (cols[iSide] ?? "").replace(/"/g, "").toLowerCase();
    var side: "buy" | "sell" | null =
      ["buy", "compra", "b", "c", "purchase"].includes(sideRaw) ? "buy"
      : ["sell", "venta", "v", "s", "sale"].includes(sideRaw) ? "sell"
      : null;
    if (!side) continue;
    var quantity = normalizeNum(cols[iQty] ?? "", semicolonCsv);
    var price = normalizeNum(cols[iPrice] ?? "", semicolonCsv);
    if (!isFinite(quantity) || quantity <= 0 || !isFinite(price) || price <= 0) continue;
    var date = iDate >= 0 ? normalizeDate(cols[iDate] ?? "") : "";
    txs.push({ symbol, side, quantity, price, date });
  }
  if (txs.length === 0) throw new Error("Ninguna fila válida en el CSV");
  return txs;
}

function parseJpmHoldings(lines: string[]): { txs: CsvTx[]; ignored: number; asOf: string | null } {
  var headers = splitCsvLine(lines[0], ",").map((h) => h.toLowerCase().replace(/"/g, ""));
  var iClass = headers.indexOf("asset class");
  var iTicker = headers.indexOf("ticker");
  var iQty = headers.indexOf("quantity");
  var iUnit = headers.indexOf("unit cost");
  var iAcq = headers.indexOf("acquisition date");
  var iAsOf = headers.indexOf("as of");
  if (iTicker < 0 || iQty < 0 || iUnit < 0) {
    throw new Error("Columnas de J.P. Morgan incompletas");
  }

  var txs: CsvTx[] = [];
  var ignored = 0;
  var asOf: string | null = null;
  for (var i = 1; i < lines.length; i++) {
    var cols = splitCsvLine(lines[i], ",");
    var assetClass = (cols[iClass] ?? "").replace(/"/g, "").toLowerCase();
    var symbol = (cols[iTicker] ?? "").replace(/"/g, "").toUpperCase();
    if (iAsOf >= 0 && !asOf) {
      var rowAsOf = normalizeUsDate(cols[iAsOf] ?? "");
      if (rowAsOf) asOf = rowAsOf;
    }
    if (!symbol || symbol === "QACDS" || assetClass.startsWith("cash")) {
      ignored++;
      continue;
    }
    if (!/^[A-Z][A-Z0-9.]*$/.test(symbol)) { ignored++; continue; }
    var quantity = normalizeUsNum(cols[iQty] ?? "");
    var price = normalizeUsNum(cols[iUnit] ?? "");
    if (!isFinite(quantity) || quantity <= 0 || !isFinite(price) || price <= 0) { ignored++; continue; }
    var date = iAcq >= 0 ? normalizeUsDate(cols[iAcq] ?? "") : "";
    txs.push({ symbol, side: "buy", quantity, price, date });
  }
  if (txs.length === 0) throw new Error("Ninguna posición en el CSV de J.P. Morgan");
  return { txs, ignored, asOf };
}

const CHASE_BUY_TYPES = ["buy", "reinvest"];
const CHASE_SELL_TYPES = ["sell"];

function parseChaseTrades(lines: string[]): { txs: CsvTx[]; ignored: number } {
  var headers = splitCsvLine(lines[0], ",").map((h) => h.toLowerCase().replace(/"/g, ""));
  var iDate = headers.indexOf("trade date");
  var iType = headers.indexOf("type");
  var iTicker = headers.indexOf("ticker");
  var iPrice = headers.indexOf("price usd");
  var iQty = headers.indexOf("quantity");
  if (iDate < 0 || iType < 0 || iTicker < 0 || iPrice < 0 || iQty < 0) {
    throw new Error("Columnas de Chase incompletas");
  }

  var txs: CsvTx[] = [];
  var ignored = 0;
  for (var i = 1; i < lines.length; i++) {
    var cols = splitCsvLine(lines[i], ",");
    var type = (cols[iType] ?? "").replace(/"/g, "").toLowerCase();
    var symbol = (cols[iTicker] ?? "").replace(/"/g, "").toUpperCase();
    var side: "buy" | "sell" | null =
      CHASE_BUY_TYPES.includes(type) ? "buy"
      : CHASE_SELL_TYPES.includes(type) ? "sell"
      : null;
    if (!side || !symbol || !/^[A-Z][A-Z0-9.]*$/.test(symbol)) { ignored++; continue; }
    var quantity = Math.abs(normalizeUsNum(cols[iQty] ?? ""));
    var price = normalizeUsNum(cols[iPrice] ?? "");
    if (!isFinite(quantity) || quantity <= 0 || !isFinite(price) || price <= 0) { ignored++; continue; }
    var date = normalizeUsDate(cols[iDate] ?? "");
    txs.push({ symbol, side, quantity, price, date });
  }
  if (txs.length === 0) throw new Error("Ninguna transacción Buy/Sell en el CSV de Chase");
  return { txs, ignored };
}

export function parseAnyCsv(text: string): ParsedCsv {
  var clean = text.replace(/^\uFEFF/, "");
  var lines = clean.split(/\r?\n/).filter((l) => l.trim() !== "");
  if (lines.length < 2) throw new Error("CSV vacío o sin filas de datos");
  lines[0] = lines[0].replace(/^\uFEFF/, "");
  var fmtKind = detectFormat(lines);
  if (fmtKind === "coinbase") {
    return { ...parseCoinbase(lines), format: "Coinbase", kind: "trades_ledger", asOf: null };
  }
  if (fmtKind === "schwab") {
    return { ...parseSchwab(lines), format: "Schwab", kind: "trades_ledger", asOf: null };
  }
  if (fmtKind === "jpm_holdings") {
    var jpm = parseJpmHoldings(lines);
    return { txs: jpm.txs, ignored: jpm.ignored, format: "J.P. Morgan", kind: "holdings_snapshot", asOf: jpm.asOf };
  }
  if (fmtKind === "chase_trades") {
    return { ...parseChaseTrades(lines), format: "Chase", kind: "trades_window", asOf: null };
  }
  return { txs: parseCsvTransactions(clean), ignored: 0, format: "genérico", kind: "trades_ledger", asOf: null };
}

export function aggregateToPositions(txs: CsvTx[]): PortfolioLot[] {
  var sorted = [...txs].sort((a, b) =>
    a.date && b.date ? a.date.localeCompare(b.date) : 0
  );

  interface Acc { qty: number; avgCost: number; openedAt: string | null }
  var acc = new Map<string, Acc>();

  for (var tx of sorted) {
    var cur = acc.get(tx.symbol) ?? { qty: 0, avgCost: 0, openedAt: null };
    if (tx.side === "buy") {
      var newQty = cur.qty + tx.quantity;
      cur.avgCost = (cur.avgCost * cur.qty + tx.price * tx.quantity) / newQty;
      if (cur.qty === 0) cur.openedAt = tx.date || null;
      cur.qty = newQty;
    } else {
      cur.qty -= tx.quantity;
      if (cur.qty <= 0.000001) {
        cur.qty = 0;
        cur.avgCost = 0;
        cur.openedAt = null;
      }
    }
    acc.set(tx.symbol, cur);
  }

  var out: PortfolioLot[] = [];
  for (var [symbol, a] of acc) {
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

export function mergeTradesOntoLots(args: {
  lots: PortfolioLot[];
  trades: CsvTx[];
  bookAsOf: string;
}): { positions: PortfolioLot[]; applied: number; skipped: number } {
  interface Acc { qty: number; avgCost: number; openedAt: string | null }
  var acc = new Map<string, Acc>();
  for (var lot of args.lots) {
    acc.set(lot.symbol, {
      qty: lot.quantity,
      avgCost: lot.avg_cost_cents / 100,
      openedAt: lot.opened_at,
    });
  }

  var sorted = [...args.trades].sort((a, b) =>
    a.date && b.date ? a.date.localeCompare(b.date) : 0
  );

  var applied = 0;
  var skipped = 0;
  for (var tx of sorted) {
    var cur = acc.get(tx.symbol) ?? { qty: 0, avgCost: 0, openedAt: null };
    if (!cur.openedAt && tx.side === "buy" && tx.date) {
      cur.openedAt = tx.date;
    }
    if (!tx.date || tx.date <= args.bookAsOf) {
      acc.set(tx.symbol, cur);
      continue;
    }
    if (tx.side === "buy") {
      var newQty = cur.qty + tx.quantity;
      if (newQty <= 0) { skipped++; continue; }
      cur.avgCost = cur.qty > 0
        ? (cur.avgCost * cur.qty + tx.price * tx.quantity) / newQty
        : tx.price;
      cur.qty = newQty;
      applied++;
    } else {
      if (tx.quantity > cur.qty + 0.000001) { skipped++; acc.set(tx.symbol, cur); continue; }
      cur.qty -= tx.quantity;
      if (cur.qty <= 0.000001) {
        cur.qty = 0;
        cur.avgCost = 0;
        cur.openedAt = null;
      }
      applied++;
    }
    acc.set(tx.symbol, cur);
  }

  var positions: PortfolioLot[] = [];
  for (var [symbol, a] of acc) {
    if (a.qty > 0 && a.avgCost > 0 && a.qty * a.avgCost >= 1) {
      positions.push({
        symbol,
        quantity: Math.round(a.qty * 10000) / 10000,
        avg_cost_cents: Math.round(a.avgCost * 100),
        opened_at: a.openedAt,
      });
    }
  }
  return { positions, applied, skipped };
}

export function planCsvImport(
  parsed: ParsedCsv,
  ctx: { lots: PortfolioLot[]; bookAsOf: string | null },
): ImportPlan {
  if (parsed.kind === "holdings_snapshot") {
    var holdings = aggregateToPositions(parsed.txs);
    var keep = new Set(holdings.map((row) => row.symbol));
    var remove = ctx.lots
      .map((lot) => lot.symbol)
      .filter((symbol) => !keep.has(symbol))
      .sort();
    return {
      action: "confirm_holdings_replace",
      format: parsed.format,
      kind: "holdings_snapshot",
      asOf: parsed.asOf,
      positions: holdings,
      remove,
      ignored: parsed.ignored,
    };
  }
  if (parsed.kind === "trades_window") {
    if (ctx.lots.length === 0) {
      return { action: "refuse", reason: "trades_without_book", format: parsed.format };
    }
    if (!ctx.bookAsOf) {
      return { action: "refuse", reason: "missing_book_as_of", format: parsed.format };
    }
    var merged = mergeTradesOntoLots({
      lots: ctx.lots,
      trades: parsed.txs,
      bookAsOf: ctx.bookAsOf,
    });
    return {
      action: "confirm_trades_merge",
      format: parsed.format,
      kind: "trades_window",
      asOf: ctx.bookAsOf,
      positions: merged.positions,
      applied: merged.applied,
      skipped: merged.skipped,
      ignored: parsed.ignored,
    };
  }
  var ledger = aggregateToPositions(parsed.txs);
  return {
    action: "upsert_ledger",
    format: parsed.format,
    kind: "trades_ledger",
    positions: ledger,
    ignored: parsed.ignored,
  };
}
