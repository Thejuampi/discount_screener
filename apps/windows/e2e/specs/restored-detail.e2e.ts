import { $, $$, browser, expect } from "@wdio/globals";
import { detail, opportunity, startApp } from "../support/backend";

const restored = {
  ...detail,
  detail_source: "restored",
  asset_type: "stock",
  saved_at_epoch_seconds: 1_770_681_600,
};

async function openRestoredDetail() {
  const backend = await startApp("ready", "screener", false, {
    opportunities: [],
    detail: restored,
  });
  await backend.searchTickers.mockResolvedValue([{
    symbol: "AAPL",
    company_name: "Apple Inc.",
    profiles: ["sp500"],
    in_current_profile: true,
    exchange: null,
    is_remote: false,
    match_rank: 0,
  }]);
  const search = $(".ticker-search input[role=combobox]");
  await search.setValue("AAPL");
  await $(".search-suggestion").click();
  return backend;
}

describe("Restored ticker detail", () => {
  beforeEach(async () => {
    await browser.tauri.restoreAllMocks();
    await browser.url("/e2e.html");
  });

  it("opens the saved equity snapshot through search without a live row", async () => {
    const backend = await openRestoredDetail();
    const badge = $('[data-detail-source="restored"]');
    await expect(badge).toHaveText(expect.stringContaining("Restored snapshot"));
    await expect(badge.$("time")).toBeDisplayed();
    await expect($(".price-summary")).toHaveText(expect.stringContaining("$195.00"));
    await expect($(".price-summary")).toHaveText(expect.stringContaining("$220.00"));
    await expect($$("tr.stock-row")).toBeElementsArrayOfSize(0);
    for (const command of [backend.ensureSymbolLoaded, backend.refreshSymbolDetail, backend.priceProvenance]) {
      await command.update();
      expect(command).toHaveBeenCalledTimes(0);
    }
  });

  it("keeps restored evidence marked when refresh updates only the chart", async () => {
    const backend = await openRestoredDetail();
    await backend.refreshSymbolDetail.mockResolvedValue({
      quote_updated: false,
      chart_updated: true,
      issues: ["quote_unavailable"],
    });
    await $(".detail-header").$("button=Refresh").click();
    await expect($(".detail-panel")).toHaveText(expect.stringContaining("No new quote arrived."));
    await expect($('[data-detail-source="restored"]')).toBeDisplayed();
    await expect($(".price-summary")).toHaveText(expect.stringContaining("$195.00"));
  });

  it("keeps restored evidence until the feed can supply a session quote", async () => {
    const backend = await openRestoredDetail();
    const badge = $('[data-detail-source="restored"]');
    await expect(badge).toBeDisplayed();
    await backend.opportunities.mockResolvedValue([{ ...opportunity, market_price_cents: 20_100 }]);
    await browser.waitUntil(async () => {
      await backend.symbolDetail.update();
      return backend.symbolDetail.mock.calls.length >= 2;
    });
    await expect(badge).toBeDisplayed();
    await expect($(".price-summary")).toHaveText(expect.stringContaining("$195.00"));

    await backend.symbolDetail.mockResolvedValue({
      ...detail,
      market_price_cents: 20_100,
      detail_source: "session",
      asset_type: "stock",
      saved_at_epoch_seconds: null,
    });
    await expect($(".price-summary")).toHaveText(expect.stringContaining("$201.00"));
    await expect(badge).not.toBeDisplayed();
    await backend.refreshSymbolDetail.update();
    expect(backend.refreshSymbolDetail).toHaveBeenCalledTimes(0);
  });
});
