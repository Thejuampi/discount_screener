import { $, browser, expect } from "@wdio/globals";
import { startApp } from "../support/backend";

describe("TipRanks settings", () => {
  beforeEach(async () => {
    await browser.tauri.restoreAllMocks();
    await browser.url("/e2e.html");
  });

  it("saves, tests, and removes the key without echoing it into the UI", async () => {
    const backend = await startApp("ready", "settings");

    const keyInput = await $('input[placeholder="TipRanks API key"]');
    await keyInput.waitForDisplayed();
    await expect(keyInput).toHaveAttribute("type", "password");
    await keyInput.setValue("qa-test-key");
    await $("button=Save").click();

    await expect($(".tipranks-connect-message")).toHaveText(
      "TipRanks key saved in Windows Credential Manager.",
    );
    await expect(keyInput).toHaveValue("");
    await expect($("body")).not.toHaveText(expect.stringContaining("qa-test-key"));
    await backend.saveKey.update();
    expect(backend.saveKey.mock.calls[0]?.[0]).toEqual({ apiKey: "qa-test-key" });

    await $("button=Test").click();
    await expect($(".tipranks-connect-message")).toHaveText(
      "Individual TipRanks analyst targets.",
    );
    await backend.testKey.update();
    expect(backend.testKey).toHaveBeenCalledTimes(1);

    await $("button=Remove").click();
    await expect($(".tipranks-connect-message")).toHaveText("TipRanks key removed.");
    await backend.deleteKey.update();
    expect(backend.deleteKey).toHaveBeenCalledTimes(1);
  });

  it("shows an explicit unavailable state when key status cannot be read", async () => {
    await startApp("ready", "settings", true);

    const status = await $(".tipranks-status-row");
    await status.waitForDisplayed();
    await expect(status).toHaveText(expect.stringContaining("TipRanks status unavailable"));
  });
});
