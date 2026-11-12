import { $, $$, browser, expect } from "@wdio/globals";
import { startApp, type E2eBackend } from "../support/backend";

const view = "[data-watchlist-view]";
const toggle = "[data-watchlist-toggle]";
const error = "[data-watchlist-error]";
const aapl = '[data-watchlist-symbol="AAPL"]';

async function openAppPage(): Promise<void> {
  await browser.tauri.restoreAllMocks();
  await browser.url("/e2e.html");
}

async function openWatchlist(): Promise<void> {
  await $(".sidebar-nav").$("span=Watchlist").click();
  await expect($(view)).toBeDisplayed();
}

async function expectNoProviderLoad(backend: E2eBackend): Promise<void> {
  for (const command of [
    backend.quotePrices, backend.searchTickers, backend.ensureSymbolLoaded,
    backend.refreshSymbolDetail, backend.loadCandles,
  ]) {
    await command.update();
    expect(command).toHaveBeenCalledTimes(0);
  }
}

describe("Watchlist", () => {
  beforeEach(openAppPage);

  it("adds and removes the ticker in Detail and updates Watchlist", async () => {
    const backend = await startApp("ready", "screener", false, { watchlist: [] });
    await $("strong=AAPL").click();
    await expect($(toggle)).toHaveAttribute("aria-pressed", "false");
    await $(toggle).waitForEnabled();
    await $(toggle).click();
    await expect($(toggle)).toHaveAttribute("aria-pressed", "true");

    await openWatchlist();
    await expect($(view).$(aapl)).toBeDisplayed();
    await expect($(view).$("tbody tr")).toHaveText(expect.stringContaining("$195.00"));
    await $(view).$(aapl).click();
    await expect($(".detail-symbol")).toHaveText("AAPL");
    await expect($(toggle)).toHaveAttribute("aria-pressed", "true");
    await $(toggle).click();
    await expect($(toggle)).toHaveAttribute("aria-pressed", "false");

    await openWatchlist();
    await expect($(view).$(aapl)).not.toExist();
    await expect($(view)).toHaveText(expect.stringContaining("No tickers in Watchlist."));
    await backend.setWatchlistMember.update();
    expect(backend.setWatchlistMember.mock.calls.map(([args]) => args)).toEqual([
      { symbol: "AAPL", included: true },
      { symbol: "AAPL", included: false },
    ]);
    await expectNoProviderLoad(backend);
  });

  it("restores a saved ticker when the app reopens without a quote", async () => {
    await startApp("ready", "screener", false, { watchlist: [] });
    await $("strong=AAPL").click();
    await $(toggle).waitForEnabled();
    await $(toggle).click();
    await expect($(toggle)).toHaveAttribute("aria-pressed", "true");

    await openAppPage();
    const reopened = await startApp("ready", "screener", false, { opportunities: [], detail: null });
    await openWatchlist();
    await expect($(view).$(aapl)).toBeDisplayed();
    await expect($(view).$("tbody tr")).toHaveText(expect.stringContaining("Unavailable"));
    await $(view).$(aapl).click();
    await expect($(".detail-symbol")).toHaveText("AAPL");
    await expect($(toggle)).toHaveAttribute("aria-pressed", "true");
    await expectNoProviderLoad(reopened);

    await $(toggle).click();
    await expect($(toggle)).toHaveAttribute("aria-pressed", "false");

    await openAppPage();
    await startApp("ready", "screener", false, { opportunities: [], detail: null });
    await openWatchlist();
    await expect($(view)).toHaveText(expect.stringContaining("No tickers in Watchlist."));
    await expect($$("[data-watchlist-symbol]")).toBeElementsArrayOfSize(0);
  });

  it("retains the saved member after a failed removal", async () => {
    const backend = await startApp("ready", "screener", false, { watchlist: ["AAPL"] });
    await backend.setWatchlistMember.mockRejectedValue("Watchlist write failed");
    await $("strong=AAPL").click();
    await expect($(toggle)).toHaveAttribute("aria-pressed", "true");
    await $(toggle).click();
    await expect($(error)).toBeDisplayed();
    await expect($(toggle)).toHaveAttribute("aria-pressed", "true");

    await openWatchlist();
    await expect($(view).$(aapl)).toBeDisplayed();
  });

  it("shows a storage read error and retries from the Watchlist view", async () => {
    const backend = await startApp("ready", "screener", false, { watchlist: null });
    await openWatchlist();
    await expect($(view).$(error)).toBeDisplayed();
    await expect($(view)).not.toHaveText(expect.stringContaining("No tickers in Watchlist."));
    await backend.getWatchlist.mockResolvedValue(["AAPL"]);
    await $(view).$("button=Retry").click();
    await expect($(view).$(aapl)).toBeDisplayed();
    await expect($(view).$(error)).not.toExist();
  });
});
