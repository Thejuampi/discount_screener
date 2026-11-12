import { $, browser, expect } from "@wdio/globals";
import { initialFeedStatus, startApp } from "../support/backend";

describe("Universe refresh", () => {
  beforeEach(async () => {
    await browser.tauri.restoreAllMocks();
    await browser.setWindowSize(1600, 1200);
    await browser.url("/e2e.html");
  });

  it("requests the active universe and keeps cached rows while progress advances", async () => {
    await browser.setWindowSize(1024, 768);
    const backend = await startApp();
    const row = $("tr.stock-row");
    await expect(row).toBeDisplayed();
    await backend.requestFeedRefresh.update();
    expect(backend.requestFeedRefresh).toHaveBeenCalledTimes(0);

    const button = $('[data-ui-action="refresh-universe"]');
    await expect(button).toBeEnabled();
    await expect(button).toBeDisplayedInViewport();
    await button.click();
    await backend.requestFeedRefresh.update();
    expect(backend.requestFeedRefresh).toHaveBeenCalledTimes(1);
    expect(backend.requestFeedRefresh.mock.calls[0]?.[0]).toEqual({ profile: "sp500" });

    await backend.feedStatus.mockResolvedValue({
      ...initialFeedStatus,
      symbols_total: 2,
      refresh: { ...initialFeedStatus.refresh, phase: "refreshing", trigger: "manual", processed: 1, total: 2 },
    });
    const phase = $('[data-feed-phase="refreshing"]');
    await expect(phase).toHaveText(expect.stringContaining("Refreshing universe · 1/2"));
    await expect(phase).toBeDisplayedInViewport();
    await expect(button).toBeDisabled();
    await expect(row).toBeDisplayed();
  });

  it("keeps an independent system error visible and clears recovered feed errors", async () => {
    const backend = await startApp();
    const status = $(".status-bar");
    const row = $("tr.stock-row");
    await expect(row).toBeDisplayed();
    const completed = {
      ...initialFeedStatus.refresh,
      phase: "idle",
      trigger: "manual",
      processed: 1,
      total: 1,
    };
    const systemError = "EDGAR calendar data unavailable";
    await backend.feedStatus.mockResolvedValue({
      ...initialFeedStatus,
      last_error: systemError,
      system_error: systemError,
      refresh: { ...completed, quote_failures: 1, chart_failures: 1, rate_limited: 1 },
    });
    await expect(status).toHaveText(expect.stringContaining(systemError));
    await expect(status).toHaveText(expect.stringContaining("Incomplete Yahoo quote data: 1"));
    await expect(status).toHaveText(expect.stringContaining("Yahoo chart failures: 1"));
    await expect(status).toHaveText(expect.stringContaining("Yahoo rate-limited symbols: 1"));
    await expect(row).toBeDisplayed();

    await backend.feedStatus.mockResolvedValue({
      ...initialFeedStatus,
      last_error: systemError,
      system_error: systemError,
      refresh: completed,
    });
    await expect(status).not.toHaveText(expect.stringMatching(/quote|chart|rate-limited/i));
    await expect(status).toHaveText(expect.stringContaining(systemError));
    await expect(row).toBeDisplayed();
  });

  it("keeps cached rows and enables retry after the refresh command rejects", async () => {
    const backend = await startApp();
    await backend.requestFeedRefresh.mockRejectedValue("profile changed");
    const row = $("tr.stock-row");
    await expect(row).toBeDisplayed();
    const button = $('[data-ui-action="refresh-universe"]');
    await expect(button).toBeEnabled();
    await button.click();

    await expect($(".status-bar")).toHaveText(
      expect.stringContaining("Could not start universe refresh. Try again."),
    );
    await expect(button).toBeEnabled();
    await expect(row).toBeDisplayed();
  });
});
