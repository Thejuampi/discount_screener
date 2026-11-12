import { $, browser, expect } from "@wdio/globals";
import { opportunity, startApp } from "../support/backend";

const panel = "[data-tracked-feed]";
const ticker = "[data-tracked-feed-input]";
const submit = "[data-tracked-feed-submit]";
const error = "[data-tracked-feed-error]";
const marketRows = [
  opportunity,
  { ...opportunity, symbol: "MSFT", company_name: "Microsoft Corp." },
  { ...opportunity, symbol: "ORCL", company_name: "Oracle Corp." },
];

async function openAppPage(): Promise<void> {
  await browser.tauri.restoreAllMocks();
  await browser.url("/e2e.html");
}

async function openFeedTickers(profile = "SP500"): Promise<void> {
  const summary = `${panel} > summary`;
  await expect($(summary)).toHaveText(expect.stringContaining(profile));
  await $(summary).click();
  await expect($(panel)).toHaveAttribute("open");
}

async function enterTicker(symbol: string, action: "Add to feed" | "Remove from feed"): Promise<void> {
  await $(ticker).waitForEnabled();
  await $(ticker).setValue(symbol);
  await expect($(submit)).toHaveText(action);
}

describe("Tracked feed tickers", () => {
  beforeEach(openAppPage);

  it("adds a ticker and removes a base member, then restores both changes on reopen", async () => {
    const backend = await startApp("ready", "screener", false, {
      trackedFeed: { profile: "sp500", symbols: ["AAPL", "MSFT"] },
      opportunities: marketRows,
    });
    await expect($("strong=AAPL")).toBeDisplayed();
    await expect($("strong=ORCL")).not.toExist();
    await openFeedTickers();

    await enterTicker("orcl", "Add to feed");
    await $(submit).click();
    await expect($("strong=ORCL")).toBeDisplayed();
    await enterTicker("ORCL", "Remove from feed");
    await enterTicker("AAPL", "Remove from feed");
    await $(submit).click();
    await expect($("strong=AAPL")).not.toExist();
    await enterTicker("AAPL", "Add to feed");
    await expect($("strong=MSFT")).toBeDisplayed();
    await backend.setTrackedFeedMember.update();
    expect(backend.setTrackedFeedMember.mock.calls.map(([args]) => args)).toEqual([
      { profile: "sp500", symbol: "ORCL", included: true },
      { profile: "sp500", symbol: "AAPL", included: false },
    ]);

    await openAppPage();
    await startApp("ready", "screener", false, {
      trackedFeed: { profile: "sp500" },
      opportunities: marketRows,
    });
    await expect($("strong=ORCL")).toBeDisplayed();
    await expect($("strong=MSFT")).toBeDisplayed();
    await expect($("strong=AAPL")).not.toExist();
    await openFeedTickers();
    await enterTicker("ORCL", "Remove from feed");
    await enterTicker("AAPL", "Add to feed");
  });

  it("keeps the active ticker when a removal fails", async () => {
    const backend = await startApp("ready", "screener", false, {
      trackedFeed: { profile: "sp500", symbols: ["AAPL", "MSFT"] },
      opportunities: marketRows,
    });
    await backend.setTrackedFeedMember.mockRejectedValue("Write failed");
    await openFeedTickers();
    await enterTicker("AAPL", "Remove from feed");
    await $(submit).click();
    await expect($(error)).toHaveText("Could not save the feed change.");
    await expect($("strong=AAPL")).toBeDisplayed();
    await expect($(submit)).toHaveText("Remove from feed");
    await expect($(submit)).toBeEnabled();
  });

  it("shows the protected QA profile without edit controls", async () => {
    const backend = await startApp("ready", "screener", false, {
      trackedFeed: { profile: "qa", symbols: ["AAPL"], editable: false },
      opportunities: marketRows,
    });
    await openFeedTickers("QA");
    await expect($(panel)).toHaveText(expect.stringContaining("This feed profile is protected."));
    await expect($(ticker)).not.toExist();
    await expect($(submit)).not.toExist();
    await backend.setTrackedFeedMember.update();
    expect(backend.setTrackedFeedMember).toHaveBeenCalledTimes(0);
  });

  it("ignores a poll from before a membership change in the same profile", async () => {
    const backend = await startApp("ready", "screener", false, {
      trackedFeed: { profile: "sp500", symbols: ["AAPL", "MSFT"] },
      opportunities: marketRows,
    });
    await openFeedTickers();
    await enterTicker("AAPL", "Remove from feed");
    await backend.opportunities.mockImplementation(async () => {
      const fixture = JSON.parse(sessionStorage.getItem("e2e_tracked_feed_fixture")!);
      const symbols = JSON.parse(sessionStorage.getItem("e2e_tracked_feed_members:sp500") ?? "[]");
      const rows = fixture.rows.filter((row: { symbol: string }) => symbols.includes(row.symbol));
      if (sessionStorage.getItem("e2e_tracked_feed_poll") !== "armed") return rows;
      sessionStorage.setItem("e2e_tracked_feed_poll", "waiting");
      await new Promise<void>((resolve) => {
        const timer = setInterval(() => {
          if (sessionStorage.getItem("e2e_tracked_feed_poll") !== "release") return;
          clearInterval(timer);
          resolve();
        }, 20);
      });
      sessionStorage.setItem("e2e_tracked_feed_poll", "returned");
      return [...rows, { ...rows[0], symbol: "STALE", company_name: "Previous poll" }];
    });
    await browser.execute(() => { sessionStorage.setItem("e2e_tracked_feed_poll", "armed"); });
    await browser.waitUntil(
      () => browser.execute(() => sessionStorage.getItem("e2e_tracked_feed_poll") === "waiting"),
    );

    await $(submit).click();
    await expect($("strong=AAPL")).not.toExist();
    await expect($("strong=MSFT")).toBeDisplayed();
    await browser.execute(() => { sessionStorage.setItem("e2e_tracked_feed_poll", "release"); });
    await browser.waitUntil(
      () => browser.execute(() => sessionStorage.getItem("e2e_tracked_feed_poll") === "returned"),
    );
    await browser.execute(() => new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
    }));
    await expect($("strong=AAPL")).not.toExist();
    await expect($("strong=STALE")).not.toExist();
  });
});
