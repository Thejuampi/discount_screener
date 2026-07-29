import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { formatProviderDate } from "../src/analystForecastPresentation.ts";

const apiSource = readFileSync(new URL("../src/api.ts", import.meta.url), "utf8");
const panelSource = readFileSync(
  new URL("../src/components/AnalystForecastsPanel.tsx", import.meta.url),
  "utf8",
);
const settingsSource = readFileSync(
  new URL("../src/components/FmpConnect.tsx", import.meta.url),
  "utf8",
);
const tauriLibSource = readFileSync(new URL("../src-tauri/src/lib.rs", import.meta.url), "utf8");
const detailSource = readFileSync(
  new URL("../src/components/DetailPanel.tsx", import.meta.url),
  "utf8",
);
const settingsPanelSource = readFileSync(
  new URL("../src/components/SettingsPanel.tsx", import.meta.url),
  "utf8",
);

test("FMP command boundary exposes presentation models without returning the API key", () => {
  assert.match(apiSource, /invoke<AnalystForecastPanel>\("get_analyst_forecasts"/);
  assert.match(apiSource, /invoke<FmpSettingsStatus>\("fmp_settings_status"\)/);
  assert.match(apiSource, /invoke<FmpSettingsStatus>\("fmp_save_key"/);
  assert.match(apiSource, /invoke<FmpSettingsStatus>\("fmp_delete_key"\)/);
  assert.match(apiSource, /invoke<AnalystForecastPanel>\("fmp_test_key"\)/);
  assert.doesNotMatch(
    apiSource.slice(
      apiSource.indexOf("export interface FmpSettingsStatus"),
      apiSource.indexOf("export interface UniverseProfileInfo"),
    ),
    /api_key|apiKey|secret/i,
  );
});

test("forecast panel passively renders backend bins, states, quota and statistics", () => {
  assert.match(panelSource, /model\.histogram\.map/);
  assert.match(panelSource, /model\.statistics/);
  assert.match(panelSource, /model\.quota\.warning/);
  assert.match(panelSource, /t\(`fmp\.state\.\$\{model\.state\}`\)/);
  assert.match(panelSource, /t\("fmp\.horizon\.disclosure"\)/);
  assert.doesNotMatch(panelSource, /\.sort\(/);
  assert.doesNotMatch(panelSource, /Math\.sqrt/);
  assert.doesNotMatch(panelSource, />=\s*125|>=\s*250/);
  assert.doesNotMatch(panelSource, />Refresh<|>Retry</);
  assert.doesNotMatch(panelSource, /Analyst forecasts — Experimental/);
});

test("settings keeps the secret in a password input and distinguishes unavailable status", () => {
  assert.match(settingsSource, /type="password"/);
  assert.match(settingsSource, /api\.fmpSaveKey\(apiKey\)/);
  assert.doesNotMatch(settingsSource, />\s*\{apiKey\}\s*</);
  assert.doesNotMatch(settingsSource, /console\.(log|error)\([^)]*apiKey/);
  assert.match(settingsSource, /statusUnavailable/);
  assert.match(settingsSource, /fmp\.settings\.statusUnavailable/);
});

test("all five Tauri commands are registered and both UI integration points are mounted", () => {
  for (const command of [
    "get_analyst_forecasts",
    "fmp_settings_status",
    "fmp_save_key",
    "fmp_delete_key",
    "fmp_test_key",
  ]) {
    assert.match(tauriLibSource, new RegExp(`commands::${command}`));
  }
  assert.match(detailSource, /<AnalystForecastsPanel symbol=\{symbol\} \/>/);
  assert.match(settingsPanelSource, /<FmpConnect \/>/);
});

test("provider calendar dates render in UTC and do not shift to the prior ET day", () => {
  const justAfterUtcMidnight = Date.UTC(2026, 6, 1, 0, 30, 0) / 1000;
  assert.equal(formatProviderDate(justAfterUtcMidnight, "en"), "7/1/2026");
});
