import { $, $$, browser, expect } from "@wdio/globals";
import { startApp } from "../support/backend";

describe("TipRanks analyst forecasts", () => {
  beforeEach(async () => {
    await browser.tauri.restoreAllMocks();
    await browser.url("/e2e.html");
  });

  it("loads the cache-only stock-detail forecast and presents its full backend model", async () => {
    const backend = await startApp("ready");

    const appleRow = await $("tr.stock-row");
    await appleRow.waitForDisplayed();
    await backend.analystForecasts.update();
    expect(backend.analystForecasts).toHaveBeenCalledTimes(0);
    await appleRow.click();

    const panel = await $(".analyst-forecasts");
    await panel.waitForDisplayed();
    await expect(panel.$("h3")).toHaveText("ANALYST FORECASTS — EXPERIMENTAL");
    await expect(panel).toHaveText(expect.stringContaining("Data by TipRanks · fresh cache"));
    await expect(panel).toHaveText(
      expect.stringContaining("TipRanks request budget: 25/50 used · 25 remaining"),
    );
    await expect(panel).toHaveText(expect.stringContaining("Minimum"));
    await expect(panel).toHaveText(expect.stringContaining("$200.00"));
    await expect(panel).toHaveText(expect.stringContaining("Maximum"));
    await expect(panel).toHaveText(expect.stringContaining("$240.00"));
    await expect(panel).toHaveText(expect.stringContaining("Simple mean"));
    await expect(panel).toHaveText(expect.stringContaining("$220.00"));
    await expect(panel).toHaveText(expect.stringContaining("Weighted mean"));
    await expect(panel).toHaveText(
      expect.stringContaining("TipRanks stars weight"),
    );
    await expect(panel).toHaveText(expect.stringContaining("Assumed 12-month horizon"));
    await expect(panel).toHaveText(expect.stringContaining("Provider horizon"));

    await expect($('svg[role="img"]')).toHaveAttribute(
      "aria-label",
      "Historical price and 3 TipRanks price targets for AAPL",
    );
    await expect($$(".forecast-h-bin")).toBeElementsArrayOfSize(3);
    await expect($$(".forecast-table tbody tr")).toBeElementsArrayOfSize(3);

    await backend.analystForecasts.update();
    expect(backend.analystForecasts).toHaveBeenCalledTimes(1);
    expect(backend.analystForecasts.mock.calls[0]?.[0]).toEqual({ symbol: "AAPL" });
    expect(backend.loadForecasts).toHaveBeenCalledTimes(0);
  });

  it("shows an explicit unload action without auto-calling TipRanks", async () => {
    const backend = await startApp("unloaded");
    await $("tr.stock-row").click();
    const panel = await $(".analyst-forecasts");
    await panel.waitForDisplayed();
    await expect(panel).toHaveText(
      expect.stringContaining("TipRanks analyst targets are not loaded for this symbol yet."),
    );
    await expect(panel.$("button")).toHaveText("Load TipRanks analyst targets");
    await backend.analystForecasts.update();
    expect(backend.analystForecasts).toHaveBeenCalledTimes(1);
    expect(backend.loadForecasts).toHaveBeenCalledTimes(0);
  });

  for (const [state, message] of [
    ["insufficient_coverage", "Fewer than three distinct analyst or firm identities."],
    ["empty", "TipRanks returned no current price-target coverage."],
    ["missing_key", "Configure a TipRanks API key in Settings."],
    ["invalid_key", "The configured TipRanks API key was rejected."],
    ["quota_exhausted", "The TipRanks monthly request budget is exhausted."],
    ["provider_unavailable", "TipRanks forecasts are temporarily unavailable."],
  ] as const) {
    it(`shows the explicit ${state} provider state`, async () => {
      await startApp(state);
      await $("tr.stock-row").click();

      const panel = await $(".analyst-forecasts");
      await panel.waitForDisplayed();
      await expect(panel).toHaveText(expect.stringContaining(message));
      await expect(panel).toHaveText(expect.stringContaining("Data by TipRanks"));
    });
  }
});
