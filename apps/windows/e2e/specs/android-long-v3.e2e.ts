import { $, browser, expect } from "@wdio/globals";
import { startApp } from "../support/backend";

const panel = "[data-android-long-v3]";
const ranked = "[data-android-v3-row]";
const incompleteValuation = "[data-android-v3-projection-gap]";
const group = '[role="radiogroup"]';

const rankedRow = {
  symbol: "MSFT",
  company: "Microsoft Corp.",
  sector: "Technology",
  composite_score: 36,
  coverage: 3,
  fundamentals: 36,
  technical: 36,
  forecast: 36,
  decision: "Act",
  confidence: "high",
  price_cents: 40_000,
  intrinsic_cents: 52_000,
  anchor_cents: 52_000,
  anchor: "street_consensus",
  gap_bps: 2_307,
  upside_bps: 3_000,
  trust_signal: null,
  row_confidence: "high",
  row_gap_bps: 2_307,
  row_upside_bps: 3_000,
  projection_gap: null,
};

const androidRanking = {
  profile: "sp500",
  rows: [
    rankedRow,
    {
      ...rankedRow,
      symbol: "META",
      company: "Meta Platforms Inc.",
      coverage: 2,
      technical: null,
      decision: null,
      anchor: "identity",
      anchor_cents: null,
      gap_bps: null,
      upside_bps: null,
      trust_signal: "NoAnalystTarget",
      projection_gap: "identity_value_not_computed_headless",
    },
  ],
  incomplete: [
    { symbol: "ORCL", company: "Oracle Corp.", reason: "no_intrinsic_value" },
  ],
  evaluated: 3,
  eligible: 3,
};

async function openAppPage(): Promise<void> {
  await browser.tauri.restoreAllMocks();
  await browser.url("/e2e.html");
}

describe("Android Long V3 ranking", () => {
  beforeEach(openAppPage);

  it("opts in to Android ranking and keeps the native scoring model", async () => {
    const backend = await startApp("ready", "screener", false, { androidLongV3: androidRanking });
    const nativeMode = $(group).$("button=Long V3");
    const androidMode = $(group).$("button=Android Long V3");

    await expect($("strong=AAPL")).toBeDisplayed();
    await expect(nativeMode).toHaveAttribute("aria-checked", "true");
    await expect(androidMode).toHaveAttribute("aria-checked", "false");
    await expect($(panel)).not.toExist();
    await backend.androidLongV3.update();
    expect(backend.androidLongV3).toHaveBeenCalledTimes(0);
    await backend.setScoringModel.update();
    const initialModelWrites = backend.setScoringModel.mock.calls.length;

    await androidMode.click();
    await expect(androidMode).toHaveAttribute("aria-checked", "true");
    await expect(nativeMode).toHaveAttribute("aria-checked", "false");
    await expect($(panel)).toBeDisplayed();
    const metaRow = $(panel).$('tr[data-android-v3-row="META"]');
    await expect(metaRow.$(incompleteValuation)).toHaveText("Incomplete valuation");
    await expect(metaRow).toHaveText(expect.stringContaining("Weekly technical signal unavailable"));
    const displayed = await browser.execute((selector) => Array.from(
      document.querySelectorAll(selector),
      (row) => row.getAttribute("data-android-v3-row"),
    ), `${panel} tr${ranked}`);
    expect(displayed).toEqual(["MSFT", "META"]);
    const missingInputs = $(panel).$("[data-android-v3-incomplete]");
    await missingInputs.$("summary").click();
    await expect(missingInputs.$("button=ORCL")).toBeDisplayed();
    await expect(missingInputs).toHaveText(expect.stringContaining("Intrinsic value unavailable"));
    await expect($("tr.stock-row")).not.toExist();
    await backend.androidLongV3.update();
    expect(backend.androidLongV3.mock.calls[0]?.[0]).toEqual({ profile: "sp500" });

    await nativeMode.click();
    await expect(nativeMode).toHaveAttribute("aria-checked", "true");
    await expect($(panel)).not.toExist();
    await expect($("strong=AAPL")).toBeDisplayed();
    await backend.setScoringModel.update();
    expect(backend.setScoringModel).toHaveBeenCalledTimes(initialModelWrites);
  });

  it("stops requesting Android rankings after leaving the screener", async () => {
    const backend = await startApp("ready", "screener", false, { androidLongV3: androidRanking });
    await $(group).$("button=Android Long V3").click();
    await expect($(`${panel} ${ranked}`)).toBeDisplayed();
    await browser.waitUntil(async () => {
      await backend.androidLongV3.update();
      return backend.androidLongV3.mock.calls.length >= 2;
    }, { timeout: 12_000, timeoutMsg: "Android ranking did not refresh while the screener was open" });

    await $(".sidebar-nav").$("span=Watchlist").click();
    await expect($("[data-watchlist-view]")).toBeDisplayed();
    await expect($(panel)).not.toExist();
    await backend.androidLongV3.update();
    const requestsAtExit = backend.androidLongV3.mock.calls.length;
    await browser.pause(5_500);
    await backend.androidLongV3.update();
    expect(backend.androidLongV3).toHaveBeenCalledTimes(requestsAtExit);
  });
});
