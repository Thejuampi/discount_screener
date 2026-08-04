/**
 * OPT-IN native integration test (IT).
 *
 * Spawns the real debug Tauri binary. Not part of `npm test` or `cargo test`.
 * Run only via: `npm run test:e2e:native:cof` or `npm run test:it`.
 */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdirSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { CdpClient, tauriInvoke, waitUntil } from "./cdp-client.mjs";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const windowsRoot = resolve(scriptDir, "../..");
const repoRoot = resolve(windowsRoot, "../..");
const debugPort = 9333;
const debugBase = `http://127.0.0.1:${debugPort}`;
const binary = join(windowsRoot, "src-tauri", "target", "debug", "discount-screener-windows.exe");
const tempBase = resolve(repoRoot, ".agents", "workspace", "tmp", "native-cof-e2e");
const runDir = join(tempBase, `${process.pid}-${Date.now()}`);

async function invoke(cdp, command, args = {}) {
  return tauriInvoke(cdp, command, args);
}

mkdirSync(runDir, { recursive: true });
const output = [];
const app = spawn(binary, ["--minimized"], {
  cwd: windowsRoot,
  windowsHide: true,
  env: {
    ...process.env,
    APPDATA: join(runDir, "appdata"),
    LOCALAPPDATA: join(runDir, "localappdata"),
    DS_UNIVERSE_PROFILE: "qa",
    DS_NATIVE_E2E: "1",
    DS_NATIVE_E2E_DATA_DIR: join(runDir, "tauri-data"),
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS:
      `--remote-debugging-address=127.0.0.1 --remote-debugging-port=${debugPort}`,
  },
  stdio: ["ignore", "pipe", "pipe"],
});
app.stdout.on("data", (chunk) => output.push(chunk.toString()));
app.stderr.on("data", (chunk) => output.push(chunk.toString()));

let cdp;
try {
  const target = await waitUntil("the native Vantage WebView debug target", async () => {
    const response = await fetch(`${debugBase}/json/list`);
    if (!response.ok) return null;
    const targets = await response.json();
    return targets.find((candidate) => candidate.type === "page" && candidate.webSocketDebuggerUrl);
  });

  cdp = new CdpClient(target.webSocketDebuggerUrl);
  await cdp.connect();
  await cdp.call("Runtime.enable");
  await waitUntil(
    "the real Tauri invoke bridge",
    () => cdp.evaluate(`typeof window.__TAURI_INTERNALS__?.invoke === "function"`),
  );
  await waitUntil("the locked QA feed initialization", async () => {
    const status = await invoke(cdp, "get_feed_status");
    return status.profile_name === "qa" && status.profile_locked && status.running;
  });

  await waitUntil("the Screener navigation action", () => cdp.evaluate(`(() => {
    const screenerButton = document.querySelectorAll(".sidebar-nav .sidebar-item")[1];
    if (!screenerButton) return false;
    screenerButton.click();
    return true;
  })()`));
  await waitUntil(
    "the real ticker filter",
    () => cdp.evaluate(`document.querySelector(".ticker-search input[role=combobox]") != null`),
  );

  const seeded = await invoke(cdp, "debug_seed_cof_native_e2e");
  assert.equal(seeded.symbol, "COF");
  assert.equal(seeded.valuation_status, null);
  assert.equal(seeded.valuation_unavailable_reason, null);
  assert.equal(seeded.dcf_analysis?.model, "residual_income_equity");
  assert.ok(seeded.dcf_analysis.base_intrinsic_value_cents > 0);

  // This is the exact backend protocol used by api.getSymbolDetail("COF").
  const detail = await invoke(cdp, "get_symbol_detail", { symbol: "COF" });
  assert.equal(detail.dcf_analysis?.model, "residual_income_equity");
  assert.equal(detail.dcf_value_cents, detail.dcf_analysis.base_intrinsic_value_cents);
  assert.equal(detail.valuation_unavailable_reason, null);

  const opportunities = await invoke(cdp, "get_opportunities");
  assert.ok(
    opportunities.some((row) => row.symbol === "COF"),
    `COF missing from backend opportunities: ${opportunities.map((row) => row.symbol).join(",")}`,
  );

  await cdp.evaluate(`(() => {
    const input = document.querySelector(".ticker-search input[role=combobox]");
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
    setter.call(input, "COF");
    input.dispatchEvent(new Event("input", { bubbles: true }));
  })()`);
  await waitUntil("the COF row from the real get_opportunities poll", () => cdp.evaluate(`(() => {
    const row = [...document.querySelectorAll("tr.stock-row")]
      .find((candidate) => candidate.textContent?.includes("COF"));
    if (!row) return false;
    row.click();
    return true;
  })()`));

  const expectedBase = `$${(detail.dcf_analysis.base_intrinsic_value_cents / 100).toFixed(2)}`;
  const expectedBear = `$${(detail.dcf_analysis.bear_intrinsic_value_cents / 100).toFixed(2)}`;
  const expectedBull = `$${(detail.dcf_analysis.bull_intrinsic_value_cents / 100).toFixed(2)}`;
  const slotText = await waitUntil("COF residual income to render in the hero valuation slot", async () => {
    const text = await cdp.evaluate(`document.querySelector(".price-summary .dcf-slot")?.textContent ?? ""`);
    const ready = /residual income/i.test(text)
      && text.includes(expectedBase)
      && text.includes(expectedBear)
      && text.includes(expectedBull);
    if (!ready && text) throw new Error(`current hero valuation text: ${JSON.stringify(text)}`);
    return ready ? text : null;
  });

  assert.doesNotMatch(slotText, /VALORACI[ÓO]N NO DISPONIBLE|VALUATION UNAVAILABLE/i);
  console.log(`PASS native COF contract: backend and DetailPanel render ${expectedBase} (${expectedBear}–${expectedBull})`);
} catch (error) {
  console.error(output.join(""));
  throw error;
} finally {
  if (cdp) {
    cdp.close();
  }
  if (app.exitCode == null) app.kill();
  const resolvedRunDir = resolve(runDir);
  if (resolvedRunDir.startsWith(`${tempBase}\\`)) {
    rmSync(resolvedRunDir, { recursive: true, force: true });
  }
}
