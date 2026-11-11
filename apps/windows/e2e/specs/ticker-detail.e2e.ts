import { $, browser, expect } from "@wdio/globals";
import { startApp } from "../support/backend";

describe("Ticker detail", () => {
  beforeEach(async () => {
    await browser.tauri.restoreAllMocks();
    await browser.url("/e2e.html");
  });

  it("opens the cached quote without requesting provider data", async () => {
    const backend = await startApp();
    await $("tr.stock-row").click();
    await expect($(".price-summary .price-value")).toHaveText(expect.stringContaining("$195.00"));

    for (const command of [
      backend.ensureSymbolLoaded, backend.refreshSymbolDetail, backend.news, backend.priceProvenance,
    ]) {
      await command.update();
      expect(command).toHaveBeenCalledTimes(0);
    }
  });

  it("keeps the quote visible when explicit refresh fails", async () => {
    const backend = await startApp();
    await backend.refreshSymbolDetail.mockRejectedValue("offline");
    await $("tr.stock-row").click();
    await $(".detail-header").$("button=Refresh").click();

    const detail = await $(".detail-panel");
    await expect(detail).toHaveText(expect.stringContaining("Could not update ticker details."));
    await expect($(".price-summary .price-value")).toHaveText(expect.stringContaining("$195.00"));
    await backend.refreshSymbolDetail.update();
    expect(backend.refreshSymbolDetail).toHaveBeenCalledTimes(1);
  });

  it("offers Load for a ticker without a cached quote", async () => {
    const backend = await startApp();
    await backend.symbolDetail.mockResolvedValue(null);
    await $("tr.stock-row").waitForDisplayed();
    await browser.execute(() => {
      const bridge = (window as Window & { __DS_AGENT__?: { openSymbol: (symbol: string) => void } }).__DS_AGENT__;
      if (!bridge) throw new Error("Detail navigation bridge is unavailable");
      bridge.openSymbol("MSFT");
    });

    const load = $(".detail-header").$("button=Load");
    await expect(load).toBeEnabled();
    await expect($(".detail-panel")).toHaveText(expect.stringContaining("No saved data for this ticker."));
    await load.click();
    await backend.refreshSymbolDetail.update();
    expect(backend.refreshSymbolDetail.mock.calls[0]?.[0]).toEqual({ symbol: "MSFT", range: "3mo" });
  });
});
