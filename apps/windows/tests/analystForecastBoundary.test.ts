import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

const apiSource = readFileSync(
  new URL("../src/api.ts", import.meta.url),
  "utf8",
);
const panelSource = readFileSync(
  new URL("../src/components/AnalystForecastsPanel.tsx", import.meta.url),
  "utf8",
);
const settingsSource = readFileSync(
  new URL("../src/components/TipRanksConnect.tsx", import.meta.url),
  "utf8",
);
const detailSource = readFileSync(
  new URL("../src/components/DetailPanel.tsx", import.meta.url),
  "utf8",
);
const settingsPanelSource = readFileSync(
  new URL("../src/components/SettingsPanel.tsx", import.meta.url),
  "utf8",
);
const libSource = readFileSync(
  new URL("../src-tauri/src/lib.rs", import.meta.url),
  "utf8",
);

describe("TipRanks analyst forecast UI boundary", () => {
  it("exposes cache-only get and explicit load commands", () => {
    assert.match(apiSource, /invoke<AnalystForecastPanel>\("get_analyst_forecasts"/);
    assert.match(apiSource, /invoke<AnalystForecastPanel>\("load_analyst_forecasts"/);
    assert.match(apiSource, /invoke<TipRanksSettingsStatus>\("tipranks_settings_status"\)/);
    assert.match(apiSource, /invoke<TipRanksSettingsStatus>\("tipranks_save_key"/);
    assert.match(apiSource, /invoke<TipRanksSettingsStatus>\("tipranks_delete_key"\)/);
    assert.match(apiSource, /invoke<AnalystForecastPanel>\("tipranks_test_key"\)/);
    assert.match(apiSource, /export interface TipRanksSettingsStatus/);
    assert.match(apiSource, /action: ForecastAction/);
    assert.match(apiSource, /cache_freshness/);
    assert.match(apiSource, /latest_observation_epoch/);
  });

  it("renders backend-owned states and does not invent load eligibility", () => {
    assert.match(panelSource, /t\(`tipranks\.state\.\$\{model\.state\}`\)/);
    assert.match(panelSource, /t\("tipranks\.horizon\.disclosure"\)/);
    assert.match(panelSource, /api\.getAnalystForecasts\(symbol\)/);
    assert.match(panelSource, /api\.loadAnalystForecasts\(symbol\)/);
    assert.match(panelSource, /model\.action\.enabled/);
    assert.match(panelSource, /model\.action\.confirmation_message/);
    assert.doesNotMatch(panelSource, /250|FMP|fmp_/);
  });

  it("settings connect only issues credential commands", () => {
    assert.match(settingsSource, /api\.tipranksSaveKey\(apiKey\)/);
    assert.match(settingsSource, /api\.tipranksTestKey\(\)/);
    assert.match(settingsSource, /api\.tipranksDeleteKey\(\)/);
    assert.match(settingsSource, /tipranks\.settings\.statusUnavailable/);
  });

  it("registers TipRanks commands and mounts panels", () => {
    for (const command of [
      "get_analyst_forecasts",
      "load_analyst_forecasts",
      "tipranks_settings_status",
      "tipranks_save_key",
      "tipranks_delete_key",
      "tipranks_test_key",
    ]) {
      assert.match(libSource, new RegExp(command));
    }
    assert.match(detailSource, /<AnalystForecastsPanel symbol=\{symbol\} \/>/);
    assert.match(settingsPanelSource, /<TipRanksConnect \/>/);
  });
});
