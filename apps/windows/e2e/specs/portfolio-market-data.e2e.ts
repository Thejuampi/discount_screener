import { $, browser, expect } from "@wdio/globals";
import { opportunity, startApp } from "../support/backend";

const holdings = [
  { id: 1, symbol: "AAPL", quantity: 1, avg_cost_cents: 10_000, opened_at: null, notes: null },
  { id: 2, symbol: "TSM", quantity: 1, avg_cost_cents: 10_000, opened_at: null, notes: null },
];

describe("Portfolio market data", () => {
  beforeEach(async () => {
    await browser.tauri.restoreAllMocks();
    await browser.url("/e2e.html");
  });

  it("shows a holding's quote and setup from the book's demand, outside the active feed", async () => {
    const analysis = await browser.tauri.mock("get_portfolio_analysis");
    await analysis.mockResolvedValue({
      rows: [{ symbol: "TSM", setup_label: "Buy", setup_score: 42, regime_score: 17 }],
      pending: false,
    });
    const backend = await startApp("ready", "advisor", false, {
      portfolioList: holdings,
      opportunities: [opportunity],
      quotePrices: { AAPL: 19_000, TSM: 15_000 },
    });
    const tsm = $("tr[data-portfolio-symbol=TSM]");
    await expect(tsm.$("[data-portfolio-price]")).toHaveText("$150.00");
    await expect(tsm.$("[data-portfolio-setup]")).toHaveText(expect.stringContaining("(+42)"));
    await expect(tsm.$("[data-portfolio-fit]")).toHaveText("+17");
    await analysis.update();
    expect(analysis).toHaveBeenCalledWith({ symbols: ["AAPL", "TSM"] }, null);
    await backend.quotePrices.update();
    expect(backend.quotePrices).toHaveBeenCalledWith({ symbols: ["AAPL", "TSM"] }, null);
  });

  it("keeps the price and withholds setup when ticker evidence is unavailable", async () => {
    const analysis = await browser.tauri.mock("get_portfolio_analysis");
    await analysis.mockResolvedValue({ rows: [], pending: false });
    await startApp("ready", "advisor", false, {
      portfolioList: [holdings[0]],
      opportunities: [opportunity],
      quotePrices: { AAPL: 19_000 },
    });
    const aapl = $("tr[data-portfolio-symbol=AAPL]");
    await expect(aapl.$("[data-portfolio-price]")).toHaveText("$190.00");
    await expect(aapl.$("[data-portfolio-setup]")).toHaveText("Unavailable");
  });

  it("fills a missing setup on the next pass without leaving Portfolio", async () => {
    const analysis = await browser.tauri.mock("get_portfolio_analysis");
    await analysis.mockResolvedValue({ rows: [], pending: true });
    await startApp("ready", "advisor", false, { portfolioList: [holdings[1]], opportunities: [] });
    const setup = $("[data-portfolio-symbol=TSM] [data-portfolio-setup]");
    await expect(setup).toHaveText("Loading…");
    await analysis.mockResolvedValue({
      rows: [{ symbol: "TSM", setup_label: "Buy", setup_score: 42, regime_score: 17 }],
      pending: false,
    });
    await expect(setup).toHaveText(expect.stringContaining("(+42)"));
  });
});
