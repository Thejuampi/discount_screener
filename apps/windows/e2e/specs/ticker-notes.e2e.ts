import { $, browser, expect } from "@wdio/globals";
import { opportunity, startApp, type E2eBackend } from "../support/backend";

const section = "[data-ticker-note]";
const input = "[data-ticker-note-input]";
const save = "[data-ticker-note-save]";
const remove = "[data-ticker-note-delete]";
const error = "[data-ticker-note-error]";
const retry = "[data-ticker-note-retry]";
const firstNote = "Revisar márgenes.\nComparar el próximo trimestre.";
const editedNote = "Revisar márgenes y flujo.\nFecha de revisión actualizada.";

async function openAppPage(): Promise<void> {
  await browser.tauri.restoreAllMocks();
  await browser.url("/e2e.html");
}

async function openTicker(symbol: string): Promise<void> {
  await $(`strong=${symbol}`).click();
  await expect($(".detail-symbol")).toHaveText(symbol);
  await expect($(section)).toBeDisplayed();
}

async function saveNote(value: string): Promise<void> {
  await $(input).waitForEnabled();
  await $(input).setValue(value);
  await $(save).click();
  await expect($(section)).toHaveText(expect.stringContaining("Note saved."));
  await expect($(save)).toBeDisabled();
}

async function expectNoProviderLoad(backend: E2eBackend): Promise<void> {
  for (const command of [backend.ensureSymbolLoaded, backend.refreshSymbolDetail, backend.loadCandles]) {
    await command.update();
    expect(command).toHaveBeenCalledTimes(0);
  }
}

describe("Ticker notes", () => {
  beforeEach(openAppPage);

  it("persists creating, editing, and deleting a multiline note across reopen", async () => {
    const initial = await startApp("ready", "screener", false, { tickerNotes: {} });
    await openTicker("AAPL");
    await saveNote(firstNote);
    await initial.setTickerNote.update();
    expect(initial.setTickerNote.mock.calls.map(([args]) => args)).toEqual([
      { symbol: "AAPL", note: firstNote },
    ]);

    await openAppPage();
    const reopened = await startApp();
    await openTicker("AAPL");
    await expect($(input)).toHaveValue(firstNote);
    await saveNote(editedNote);
    await expectNoProviderLoad(reopened);

    await openAppPage();
    const beforeDelete = await startApp("ready", "screener", false, {
      opportunities: [],
      detail: null,
      watchlist: ["AAPL"],
    });
    await $(".sidebar-nav").$("span=Watchlist").click();
    const watchlist = $("[data-watchlist-view]");
    await expect(watchlist.$("tbody tr")).toHaveText(expect.stringContaining("Unavailable"));
    await watchlist.$('[data-watchlist-symbol="AAPL"]').click();
    await expect($(".detail-symbol")).toHaveText("AAPL");
    await expect($(input)).toHaveValue(editedNote);
    await $(remove).click();
    await expect($(section)).toHaveText(expect.stringContaining("Note deleted."));
    await expect($(input)).toHaveValue("");
    await beforeDelete.setTickerNote.update();
    expect(beforeDelete.setTickerNote.mock.calls.map(([args]) => args)).toEqual([
      { symbol: "AAPL", note: null },
    ]);
    await expectNoProviderLoad(beforeDelete);

    await openAppPage();
    await startApp();
    await openTicker("AAPL");
    await $(input).waitForEnabled();
    await expect($(input)).toHaveValue("");
  });

  it("keeps separate saved notes when switching tickers", async () => {
    const otherOpportunity = { ...opportunity, symbol: "MSFT", company_name: "Microsoft Corp." };
    await startApp("ready", "screener", false, {
      opportunities: [opportunity, otherOpportunity],
      detail: null,
      tickerNotes: { AAPL: "Apple note", MSFT: "Microsoft note" },
    });
    await openTicker("AAPL");
    await expect($(input)).toHaveValue("Apple note");
    await openTicker("MSFT");
    await expect($(input)).toHaveValue("Microsoft note");
    await saveNote("Updated Microsoft note");
    await openTicker("AAPL");
    await expect($(input)).toHaveValue("Apple note");
    await openTicker("MSFT");
    await expect($(input)).toHaveValue("Updated Microsoft note");
  });

  it("keeps the draft when saving fails and lets the user retry", async () => {
    const backend = await startApp("ready", "screener", false, { tickerNotes: { AAPL: firstNote } });
    await backend.setTickerNote.mockRejectedValue("Ticker note could not be saved");
    await openTicker("AAPL");
    await expect($(input)).toHaveValue(firstNote);
    await $(input).setValue(editedNote);
    await $(save).click();
    await expect($(error)).toBeDisplayed();
    await expect($(input)).toHaveValue(editedNote);
    await backend.setTickerNote.mockResolvedValue(null);
    await $(save).click();
    await expect($(section)).toHaveText(expect.stringContaining("Note saved."));
    await expect($(save)).toBeDisabled();
    await expect($(error)).not.toExist();
  });

  it("shows a storage read error and retries the saved note", async () => {
    const backend = await startApp("ready", "screener", false, { tickerNotes: null });
    await openTicker("AAPL");
    await expect($(error)).toBeDisplayed();
    await backend.getTickerNote.mockResolvedValue(firstNote);
    await $(retry).click();
    await expect($(input)).toHaveValue(firstNote);
    await expect($(error)).not.toExist();
  });
});
