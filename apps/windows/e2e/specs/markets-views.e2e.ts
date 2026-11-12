import { $, $$, browser, expect } from "@wdio/globals";
import { Key } from "webdriverio";
import { opportunity, startApp } from "../support/backend";

const ordinal = "[data-market-ordinal]";
const upside = "[data-markets-upside]";
const nativeRows = "tr.stock-row";
const androidRows = "tr[data-android-v3-row]";
const upsideRows = "tr[data-upside-symbol]";
const opportunitiesButton = "button=Opportunities";
const upsideButton = "button=Upside";
const search = ".ticker-search input";

const marketRows = [
  {
    ...opportunity,
    market_price_cents: 10_000,
    intrinsic_value_cents: 12_000,
    gap_bps: 1_666,
    composite_score: 40,
  },
  {
    ...opportunity,
    symbol: "MSFT",
    company_name: "Microsoft Corp.",
    market_price_cents: 10_000,
    intrinsic_value_cents: 10_000,
    gap_bps: 0,
    qualification: "GapTooSmall",
    composite_score: 20,
  },
  {
    ...opportunity,
    symbol: "META",
    company_name: "Meta Platforms Inc.",
    market_price_cents: 10_000,
    intrinsic_value_cents: 9_000,
    gap_bps: -1_111,
    qualification: "GapTooSmall",
    composite_score: 55,
  },
  {
    ...opportunity,
    symbol: "AMD",
    company_name: "Advanced Micro Devices Inc.",
    intrinsic_value_cents: 0,
    gap_bps: null,
    qualification: "GapTooSmall",
    composite_score: 10,
  },
];

const trackedFeed = { profile: "sp500", symbols: ["MSFT", "AMZN", "META", "AAPL", "AMD"] };
const savedUniverseRows = [{
  symbol: "AMZN",
  company_name: "Amazon.com Inc.",
  asset_type: "stock",
  market_price_cents: 10_000,
  target_price_cents: 90_000,
  saved_at_epoch_seconds: 1_770_681_600,
}];

const androidRow = {
  symbol: "MSFT",
  company: "Microsoft Corp.",
  price_cents: 10_000,
  anchor_cents: 15_000,
  anchor: "street_consensus",
  upside_bps: 5_000,
  composite_score: 30,
  coverage: 3,
  fundamentals: 30,
  technical: 30,
  forecast: 30,
  decision: "Watch",
  confidence: "high",
  trust_signal: null,
  projection_gap: null,
};

async function expectOrder(
  selector: string,
  symbols: readonly string[],
  symbolAttribute: string | null = null,
): Promise<void> {
  await expect($$(selector)).toBeElementsArrayOfSize(symbols.length);
  const order = await browser.execute((rows, tickerAttribute, numberSelector) => Array.from(
    document.querySelectorAll(rows),
    (row) => [
      tickerAttribute === null ? row.querySelector("strong")?.textContent : row.getAttribute(tickerAttribute),
      row.querySelector(numberSelector)?.textContent,
    ],
  ), selector, symbolAttribute, ordinal);
  expect(order).toEqual(symbols.map((symbol, index) => [symbol, String(index + 1)]));
}

async function clearSearch(): Promise<void> {
  await $(search).click();
  await browser.keys([Key.Ctrl, "a"]);
  await browser.keys(Key.Backspace);
}

describe("Markets views", () => {
  beforeEach(async () => {
    await browser.tauri.restoreAllMocks();
    await browser.url("/e2e.html");
  });

  it("numbers the sorted and filtered Opportunities rows and remembers their sort", async () => {
    await startApp("ready", "screener", false, { opportunities: marketRows, trackedFeed, savedUniverseRows });

    await expect($(opportunitiesButton)).toHaveAttribute("aria-pressed", "true");
    await expectOrder(nativeRows, ["META", "AAPL", "MSFT", "AMD"]);
    await expect($(".restored-universe").$("button=AMZN")).toBeDisplayed();
    await expect($('[data-row-source="restored"] ' + ordinal)).not.toExist();

    await $("table.stock-table").$("button=Symbol").click();
    await expectOrder(nativeRows, ["AAPL", "AMD", "META", "MSFT"]);
    await $(search).setValue("Microsoft");
    await expectOrder(nativeRows, ["MSFT"]);
    await clearSearch();
    await expectOrder(nativeRows, ["AAPL", "AMD", "META", "MSFT"]);

    await $(upsideButton).click();
    await expect($(upsideButton)).toHaveAttribute("aria-pressed", "true");
    await expect($(upside)).toBeDisplayed();
    await $(opportunitiesButton).click();
    await expect($(upside)).not.toExist();
    await expectOrder(nativeRows, ["AAPL", "AMD", "META", "MSFT"]);
  });

  it("orders Upside across the feed using the session price and numbers the visible rows", async () => {
    await startApp("ready", "screener", false, { opportunities: marketRows, trackedFeed, savedUniverseRows });
    await $(upsideButton).click();

    await expectOrder(upsideRows, ["AAPL", "MSFT", "META", "AMD", "AMZN"], "data-upside-symbol");
    await expect($('tr[data-upside-symbol="AAPL"]')).toHaveText(expect.stringContaining("+20.00%"));
    await expect($('tr[data-upside-symbol="MSFT"]').$("td=0.00%")).toBeDisplayed();
    await expect($('tr[data-upside-symbol="META"]')).toHaveText(expect.stringContaining("-10.00%"));
    await expect($('tr[data-upside-symbol="AMZN"]')).not.toHaveText(expect.stringContaining("$900.00"));
    await expect($("tr.stock-row")).not.toExist();

    await $(search).setValue("META");
    await expectOrder(upsideRows, ["META"], "data-upside-symbol");
    await $("tr[data-upside-symbol] button").click();
    await expect($(".detail-symbol")).toHaveText("META");
  });

  it("numbers Android rows in the provided order and suspends Android polling in Upside", async () => {
    const backend = await startApp("ready", "screener", false, {
      opportunities: marketRows,
      trackedFeed,
      androidLongV3: {
        profile: "sp500",
        rows: [
          androidRow,
          { ...androidRow, symbol: "AAPL", company: "Apple Inc.", composite_score: 50 },
        ],
        incomplete: [],
        evaluated: 2,
        eligible: 2,
      },
    });

    await $("button=Android Long V3").click();
    await expectOrder(androidRows, ["MSFT", "AAPL"], "data-android-v3-row");
    await $(search).setValue("Apple");
    await expectOrder(androidRows, ["AAPL"], "data-android-v3-row");
    await clearSearch();

    await $(upsideButton).click();
    await expectOrder(upsideRows, ["AAPL", "MSFT", "META", "AMD", "AMZN"], "data-upside-symbol");
    await expect($('tr[data-upside-symbol="MSFT"]').$("td=0.00%")).toBeDisplayed();
    await expect($(androidRows)).not.toExist();
    await backend.androidLongV3.update();
    const requestsInUpside = backend.androidLongV3.mock.calls.length;
    await browser.pause(5_500);
    await backend.androidLongV3.update();
    expect(backend.androidLongV3).toHaveBeenCalledTimes(requestsInUpside);

    await $(opportunitiesButton).click();
    await expectOrder(androidRows, ["MSFT", "AAPL"], "data-android-v3-row");
  });
});
