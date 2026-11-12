import { $, $$, browser, expect } from "@wdio/globals";
import type { PortfolioPosition } from "../../src/api";
import { detail, opportunity, startApp, type E2eBackend } from "../support/backend";

const positions: PortfolioPosition[] = [
  { id: 1, symbol: "AAPL", quantity: 2.5, avg_cost_cents: 12_000, opened_at: "2026-02-10", notes: null },
  { id: 2, symbol: "AAPL", quantity: 1, avg_cost_cents: 21_000, opened_at: null, notes: null },
  { id: 3, symbol: "MSFT", quantity: 1.5, avg_cost_cents: 17_000, opened_at: null, notes: null },
];

const otherOpportunity = {
  ...opportunity,
  symbol: "MSFT",
  company_name: "Microsoft Corp.",
};

const restored = {
  ...detail,
  detail_source: "restored",
  saved_at_epoch_seconds: 1_770_681_600,
};

const heldBadge = ".detail-header [data-position-held]";
const lotRows = ".detail-position-lots [data-position-lot]";
const bookUnavailable = "[data-position-book-unavailable]";

async function openFromScreener(backend: E2eBackend): Promise<void> {
  const ticker = $("strong=AAPL");
  await ticker.waitForDisplayed();
  // Signal and email hooks also read the book on app startup.
  await backend.portfolioList.update();
  const startupReads = backend.portfolioList.mock.calls.length;
  await ticker.click();
  await browser.waitUntil(async () => {
    await backend.portfolioList.update();
    return backend.portfolioList.mock.calls.length > startupReads;
  });
}

async function openThroughSearch(backend: E2eBackend): Promise<void> {
  await backend.searchTickers.mockResolvedValue([{
    symbol: "AAPL",
    company_name: "Apple Inc.",
    profiles: ["sp500"],
    in_current_profile: true,
    exchange: null,
    is_remote: false,
    match_rank: 0,
  }]);
  await $(".ticker-search input[role=combobox]").setValue("AAPL");
  await $(".search-suggestion").click();
}

async function expectNoPositionFetch(backend: E2eBackend): Promise<void> {
  for (const command of [backend.quotePrices, backend.ensureSymbolLoaded, backend.refreshSymbolDetail]) {
    await command.update();
    expect(command).toHaveBeenCalledTimes(0);
  }
}

describe("Ticker Detail positions", () => {
  beforeEach(async () => {
    await browser.tauri.restoreAllMocks();
    await browser.url("/e2e.html");
  });

  it("shows each held lot and uses the whole book for its weight", async () => {
    const backend = await startApp("ready", "screener", false, {
      portfolioList: positions,
      opportunities: [opportunity, otherOpportunity],
    });
    await openFromScreener(backend);
    await expect($(heldBadge)).toHaveText("Held");
    await expect($$(lotRows)).toBeElementsArrayOfSize(2);
    const firstLot = $('.detail-position-lots [data-position-lot="1"]');
    const secondLot = $('.detail-position-lots [data-position-lot="2"]');
    await expect(firstLot).toHaveText(expect.stringContaining("2.5"));
    await expect(firstLot).toHaveText(expect.stringContaining("$120.00"));
    await expect(firstLot).toHaveText(expect.stringContaining("$195.00"));
    await expect(firstLot).toHaveText(expect.stringContaining("+$187.50"));
    await expect(firstLot).toHaveText(expect.stringContaining("+62.50%"));
    await expect(firstLot).toHaveText(expect.stringContaining("50.00%"));
    await expect(secondLot).toHaveText(expect.stringContaining("$210.00"));
    await expect(secondLot).toHaveText(expect.stringContaining("−$15.00"));
    await expect(secondLot).toHaveText(expect.stringContaining("−7.14%"));
    await expect(secondLot).toHaveText(expect.stringContaining("20.00%"));
    await expectNoPositionFetch(backend);
  });

  it("shows local lots but excludes the restored quote from their calculations", async () => {
    const backend = await startApp("ready", "screener", false, {
      portfolioList: positions,
      opportunities: [],
      detail: restored,
    });
    await openThroughSearch(backend);
    await expect($('[data-detail-source="restored"]')).toBeDisplayed();
    await expect($(heldBadge)).toBeDisplayed();
    await expect($$(lotRows)).toBeElementsArrayOfSize(2);
    const section = $(".detail-position-lots");
    await expect(section).toHaveText(expect.stringContaining("$120.00"));
    await expect(section).toHaveText(expect.stringContaining("No session price is available for this ticker."));
    await expect(section).toHaveText(expect.stringContaining("Weight requires a complete portfolio value."));
    await expect(section).toHaveText(expect.stringContaining("0/3 lots with value"));
    await expect(section).not.toHaveText(expect.stringContaining("$195.00"));
    await expect(section).not.toHaveText(expect.stringContaining("$187.50"));
    await expectNoPositionFetch(backend);
  });

  it("shows local lot facts when Detail has no quote", async () => {
    const backend = await startApp("ready", "screener", false, {
      portfolioList: positions,
      opportunities: [],
      detail: null,
    });
    await openThroughSearch(backend);
    await expect($(heldBadge)).toBeDisplayed();
    await expect($$(lotRows)).toBeElementsArrayOfSize(2);
    await expect($(".detail-position-lots")).toHaveText(
      expect.stringContaining("No session price is available for this ticker."),
    );
    await expectNoPositionFetch(backend);
  });

  it("leaves Held and the lot table absent for a ticker outside the book", async () => {
    const backend = await startApp("ready", "screener", false, {
      portfolioList: [positions[2]],
    });
    await openFromScreener(backend);
    await expect($(".price-summary")).toBeDisplayed();
    await expect($(heldBadge)).not.toExist();
    await expect($(".detail-position-lots")).not.toExist();
    await expect($(bookUnavailable)).not.toExist();
  });

  it("shows a local book read failure separately from an unheld ticker", async () => {
    await startApp("ready", "screener", false, {
      portfolioList: null,
    });
    await $("strong=AAPL").click();
    await expect($(bookUnavailable)).toHaveText("Saved positions could not be read.");
    await expect($(heldBadge)).not.toExist();
    await expect($(".detail-position-lots")).not.toExist();
    await expect($(".price-summary")).toBeDisplayed();
  });
});
