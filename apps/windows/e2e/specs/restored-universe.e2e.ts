import { $, $$, browser, expect } from "@wdio/globals";
import { detail, opportunity, startApp } from "../support/backend";

const savedAt = 1_770_681_600;
const savedRows = [
  {
    symbol: "AAPL",
    company_name: "Apple Inc.",
    asset_type: "stock",
    market_price_cents: 19_500,
    target_price_cents: 22_000,
    saved_at_epoch_seconds: savedAt,
  },
  {
    symbol: "MSFT",
    company_name: "Microsoft",
    asset_type: "stock",
    market_price_cents: 28_000,
    target_price_cents: null,
    saved_at_epoch_seconds: savedAt,
  },
];

const candles = [
  { epoch_seconds: savedAt - 86_400, open_cents: 19_000, high_cents: 19_400, low_cents: 18_900, close_cents: 19_300, volume: 12_000 },
  { epoch_seconds: savedAt, open_cents: 19_300, high_cents: 19_600, low_cents: 19_200, close_cents: 19_500, volume: 12_300 },
];

describe("Restored universe and candles", () => {
  beforeEach(async () => {
    await browser.tauri.restoreAllMocks();
    await browser.url("/e2e.html");
  });

  it("shows saved rows outside the ranking until each ticker has a session quote", async () => {
    const backend = await startApp("ready", "screener", false, {
      opportunities: [],
      savedUniverseRows: savedRows,
    });
    const restored = $(".restored-universe");
    await expect(restored.$("h2")).toHaveText("Restored rows");
    await expect(restored.$("time")).toBeDisplayed();
    await expect(restored.$("tbody")).toHaveText(expect.stringContaining("$195.00"));
    await expect($$('[data-row-source="restored"]')).toBeElementsArrayOfSize(2);
    await expect($$("tr.stock-row")).toBeElementsArrayOfSize(0);
    await backend.candles.update();
    expect(backend.candles).toHaveBeenCalledTimes(0);

    await backend.opportunities.mockResolvedValue([{ ...opportunity, market_price_cents: 20_100 }]);
    await expect($("tr.stock-row")).toHaveText(expect.stringContaining("$201.00"));
    await expect(restored.$("button=AAPL")).not.toExist();
    await expect(restored.$("button=MSFT")).toBeDisplayed();
  });

  it("reads saved candles only when Detail opens and promotes session candles independently", async () => {
    const backend = await startApp("ready", "screener", false, {
      opportunities: [],
      savedUniverseRows: savedRows,
      detail: { ...detail, detail_source: "restored", saved_at_epoch_seconds: savedAt },
      candles: { candles, source: "restored", saved_at_epoch_seconds: savedAt },
    });
    const badge = $('[data-candle-source="restored"]');
    await $(".restored-universe").$("button=AAPL").click();
    await expect(badge).toHaveText(expect.stringContaining("Restored candles"));
    await expect(badge.$("time")).toBeDisplayed();
    await backend.candles.update();
    expect(backend.candles.mock.calls[0]?.[0]).toMatchObject({ symbol: "AAPL", range: "3mo" });
    const rangeSavedAt = savedAt - 60;
    await backend.candles.mockResolvedValue({ candles, source: "restored", saved_at_epoch_seconds: rangeSavedAt });
    await $(".range-tabs").$("button=1mo").click();
    await expect($('[data-candle-source="restored"] time'))
      .toHaveAttribute("datetime", new Date(rangeSavedAt * 1_000).toISOString());
    await backend.candles.update();
    expect(backend.candles.mock.calls.some((call) => call[0]?.range === "1mo")).toBe(true);
    await backend.loadCandles.update();
    expect(backend.loadCandles).toHaveBeenCalledTimes(0);

    await backend.candles.mockResolvedValue({ candles, source: "session", saved_at_epoch_seconds: null });
    await browser.waitUntil(async () => !(await $('[data-candle-source="restored"]').isExisting()), { timeout: 12_000 });
    await expect($(".chart-container canvas")).toBeDisplayed();
    await expect($('[data-detail-source="restored"]')).toBeDisplayed();
    await expect($$("tr.stock-row")).toBeElementsArrayOfSize(0);
    await backend.loadCandles.update();
    expect(backend.loadCandles).toHaveBeenCalledTimes(0);
  });
});
