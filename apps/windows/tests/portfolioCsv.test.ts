import assert from "node:assert/strict";
import test from "node:test";
import {
  aggregateToPositions,
  mergeTradesOntoLots,
  parseAnyCsv,
  planCsvImport,
  type PortfolioLot,
} from "../src/portfolioCsv.ts";

const JPM_HEADER =
  "Asset Class,Asset Strategy,Asset Strategy Detail,Description,Ticker,CUSIP,Quantity,Base CCY,Local CCY,Price,PriceInd,Local Price,Today's Price Change,Price Change %,Pricing Date,Value,Today's Value Change,Value Change %,Local Value,Cost,Unit Cost,Local Unit Cost,Orig Cost (Base),Orig Cost (Local),Cost Source,Local Cost,Unrealized G/L Amt.,Orig. $ Gain/Loss (Base),Orig. $ Gain/Loss (Local),Local Unrealized G/L Amt.,Unrealized Gain/Loss (%),Orig. % Gain/Loss (Base),Orig. % Gain/Loss (Local),Local Unrealized Gain/Loss (%),Disallowed Loss (Base),Disallowed Loss (Local),Acquisition Date,Adj Date,Accrued/Income Earned,Local Accrued/Income Earned,Accrued Income,Local Accrued Income,Est. Annual Income,Local Est. Annual Income,YTM,Maturity Date,Coupon Rate,S&P Rating,Moody Rating,Buy/Call Amount,Buy/Call Currency,Sell/Put Amount,Sell/Put Currency,Market Spot Rate,Market Forward Rate,Contract Rate,Subscription Amount,Net Distributions,Used/Outstanding,Interest Rate,Finance Charges (MTD),As of,Acct Type,Accounting Method,Current Face Value,Disclaimers-Cost,Disclaimers-Quantity,Dividend Yield,Amount invested,7-day average yield,ISIN";

const PHYL =
  `"Fixed Income","Non-Investment Grade Corporate","","PGIM ETF TRUST PGIM ACTIVE HIGH YIELD BOND ETF","PHYL","69344A206","1,273","USD","","34.61","false","","-0.17","-0.49","08/30/2026 08:00:00","44,058.53","-216.41","-0.49","","44,906.43","35.28","","44,906.43","","","","-847.9","-847.9","","","-1.89","-1.89","","","","","","","3,156.7","","0","","3,080.66","","0","","0","","","","","","","1","0","0","1,273","0","44,058.53","0","","08/31/2026","Cash","Original Cost","","","","0","","","US69344A2069"`;

const CASH =
  `"Cash & Money Market Funds","Cash","","US DOLLAR","","0USDPRAA7","16,339.32","USD","","1","false","","0","0","","16,339.32","0","0","","16,339.32","1","","0","","","","0","0","","","0","0","","","","","","","","","0","","","","0","","0","","","","","","","1","0","0","16,339.32","0","16,339.32","0","","08/31/2026","Cash","Original Cost","","","","0","","",""`;

const AMZN =
  `"Equity","US Large Cap","","AMAZON.COM INC","AMZN","023135106","36.29536","USD","","259.45","false","","-6.98","-2.62","08/30/2026 08:00:00","9,416.83","-253.34","-2.62","","7,768.21","214.03","","7,768.21","","","","1,648.62","1,648.62","","","21.22","21.22","","","","","","","","","0","","","","0","","0","","","","","","","1","0","0","36.29536","0","9,416.83","0","","08/31/2026","Cash","Original Cost","","","","0","","","US0231351067"`;

const SWEEP =
  `"Cash & Money Market Funds","Money Market Funds","","CHASE DEPOSIT SWEEP JPMORGAN CHASE BANK NA","QACDS","","1,879.55","USD","","1","false","","0","0","08/28/2026 08:00:00","1,879.55","0","0","","1,879.55","1","","1,879.55","","","","0","0","","","0","0","","","","","","","","","0","","","","0","","0","","","","","","","1","0","0","1,879.55","0","1,879.55","0","","08/31/2026","Cash","Original Cost","","","","0","","",""`;

function sample(): string {
  return [JPM_HEADER, PHYL, CASH, AMZN, SWEEP, "FOOTNOTES"].join("\n");
}

test("jpm positions csv is a holdings snapshot not a trade blotter", () => {
  var parsed = parseAnyCsv(sample());
  assert.equal(parsed.format, "J.P. Morgan");
});

test("jpm thousands commas stay thousands", () => {
  var phyl = parseAnyCsv(sample()).txs.find((tx) => tx.symbol === "PHYL");
  assert.equal(phyl?.quantity, 1273);
});

test("jpm cost basis is unit cost not the market print", () => {
  var phyl = parseAnyCsv(sample()).txs.find((tx) => tx.symbol === "PHYL");
  assert.equal(phyl?.price, 35.28);
});

test("jpm holdings are buys", () => {
  var amzn = parseAnyCsv(sample()).txs.find((tx) => tx.symbol === "AMZN");
  assert.equal(amzn?.side, "buy");
});

test("jpm cash rows do not become positions", () => {
  var symbols = parseAnyCsv(sample()).txs.map((tx) => tx.symbol);
  assert.deepEqual(symbols, ["PHYL", "AMZN"]);
});

test("jpm snapshot aggregates to open lots", () => {
  var positions = aggregateToPositions(parseAnyCsv(sample()).txs);
  var phyl = positions.find((row) => row.symbol === "PHYL");
  assert.equal(phyl?.avg_cost_cents, 3528);
});

test("jpm as-of becomes the book as-of", () => {
  assert.equal(parseAnyCsv(sample()).asOf, "2026-08-31");
});

test("jpm holdings import plans a confirm replace", () => {
  var plan = planCsvImport(parseAnyCsv(sample()), { lots: [], bookAsOf: null });
  assert.equal(plan.action, "confirm_holdings_replace");
});

const CHASE_HEADER =
  "Trade Date,Post Date,Settlement Date,Account Name,Account Number,Account Type,Type,Description,Cusip,Ticker,Security Type,Local Currency,Price USD,Price Local,Quantity,G/L Short USD,G/L Short Local,G/L Long USDs,G/L Long Local,Amount USD,Amount Local,Income USD,Income Local,Balance,Commissions USD,Commissions Local,Tran Code,Tran Code Description,Broker,Check Number,Tax Withheld";

function chaseRow(fields: {
  date: string;
  type: string;
  ticker: string;
  price: string;
  qty: string;
}): string {
  var cols = new Array(31).fill("");
  cols[0] = fields.date;
  cols[6] = fields.type;
  cols[9] = fields.ticker;
  cols[12] = fields.price;
  cols[14] = fields.qty;
  return cols.map((c) => `"${c}"`).join(",");
}

function chaseSample(): string {
  return [
    CHASE_HEADER,
    chaseRow({ date: "8/31/2026", type: "Sell", ticker: "AXON", price: "563.35", qty: "-4" }),
    chaseRow({ date: "8/15/2026", type: "Reinvest", ticker: "PHYL", price: "35.00", qty: "5" }),
    chaseRow({ date: "8/10/2026", type: "Dividend", ticker: "AMZN", price: "0", qty: "0" }),
    chaseRow({ date: "9/1/2026", type: "Buy", ticker: "AMZN", price: "220", qty: "10" }),
  ].join("\n");
}

test("chase transactions csv is a trades window", () => {
  var parsed = parseAnyCsv(chaseSample());
  assert.equal(parsed.kind, "trades_window");
});

test("chase reads price usd", () => {
  var axon = parseAnyCsv(chaseSample()).txs.find((tx) => tx.symbol === "AXON");
  assert.equal(axon?.price, 563.35);
});

test("chase sell quantity is absolute", () => {
  var axon = parseAnyCsv(chaseSample()).txs.find((tx) => tx.symbol === "AXON");
  assert.equal(axon?.quantity, 4);
});

test("chase reinvest is a buy", () => {
  var phyl = parseAnyCsv(chaseSample()).txs.find((tx) => tx.symbol === "PHYL");
  assert.equal(phyl?.side, "buy");
});

test("chase dividend rows do not form positions", () => {
  var symbols = parseAnyCsv(chaseSample()).txs.map((tx) => tx.symbol);
  assert.deepEqual(symbols, ["AXON", "PHYL", "AMZN"]);
});

const AMZN_LOT: PortfolioLot = {
  symbol: "AMZN",
  quantity: 10,
  avg_cost_cents: 20000,
  opened_at: "2024-01-15",
};

test("a trades window without lots refuses", () => {
  var plan = planCsvImport(parseAnyCsv(chaseSample()), { lots: [], bookAsOf: "2026-08-31" });
  assert.equal(plan.action === "refuse" ? plan.reason : "", "trades_without_book");
});

test("a trades window without book as-of refuses", () => {
  var plan = planCsvImport(parseAnyCsv(chaseSample()), { lots: [AMZN_LOT], bookAsOf: null });
  assert.equal(plan.action === "refuse" ? plan.reason : "", "missing_book_as_of");
});

test("trades on as-of do not change quantity or cost", () => {
  var merged = mergeTradesOntoLots({
    lots: [AMZN_LOT],
    trades: [{ symbol: "AMZN", side: "buy", quantity: 10, price: 50, date: "2026-08-31" }],
    bookAsOf: "2026-08-31",
  });
  var amzn = merged.positions.find((row) => row.symbol === "AMZN");
  assert.deepEqual(
    { quantity: amzn?.quantity, avg_cost_cents: amzn?.avg_cost_cents },
    { quantity: 10, avg_cost_cents: 20000 },
  );
});

test("a buy after as-of adds size and blends cost", () => {
  var merged = mergeTradesOntoLots({
    lots: [AMZN_LOT],
    trades: [{ symbol: "AMZN", side: "buy", quantity: 10, price: 220, date: "2026-09-01" }],
    bookAsOf: "2026-08-31",
  });
  var amzn = merged.positions.find((row) => row.symbol === "AMZN");
  assert.deepEqual(
    { quantity: amzn?.quantity, avg_cost_cents: amzn?.avg_cost_cents },
    { quantity: 20, avg_cost_cents: 21000 },
  );
});

test("an empty opened_at takes the earliest blotter buy", () => {
  var merged = mergeTradesOntoLots({
    lots: [{ symbol: "AMZN", quantity: 10, avg_cost_cents: 20000, opened_at: null }],
    trades: [{ symbol: "AMZN", side: "buy", quantity: 10, price: 200, date: "2026-06-02" }],
    bookAsOf: "2026-08-31",
  });
  var amzn = merged.positions.find((row) => row.symbol === "AMZN");
  assert.equal(amzn?.opened_at, "2026-06-02");
});
