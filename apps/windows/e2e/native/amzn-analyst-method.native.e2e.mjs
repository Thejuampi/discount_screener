/**
 * OPT-IN native integration test (IT).
 *
 * Spawns the real debug Tauri binary. Not part of `npm test` or `cargo test`.
 * Run only via: `npm run test:e2e:native:amzn-fem` or `npm run test:it`.
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
const debugPort = 9334;
const debugBase = `http://127.0.0.1:${debugPort}`;
const binary = join(windowsRoot, "src-tauri", "target", "debug", "discount-screener-windows.exe");
const tempBase = resolve(repoRoot, ".agents", "workspace", "tmp", "native-amzn-fem-e2e");
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

  // Start with no analyst-method publication. A backend-only happy-path assertion
  // cannot prove that a mounted panel observes a run committed later.
  const dossierBefore = await invoke(cdp, "get_valuation_dossier", { symbol: "AMZN" });
  assert.equal(
    dossierBefore.analystMethod?.status ?? dossierBefore.analyst_method?.status,
    "absent",
  );

  // One-shot load AMZN into the locked QA process and open the real Detail UI.
  // There is deliberately no "no detail" skip here.
  await invoke(cdp, "ensure_symbol_loaded", { symbol: "AMZN" });
  await waitUntil("AMZN detail cache", async () => {
    try {
      return await invoke(cdp, "get_symbol_detail", { symbol: "AMZN" });
    } catch {
      return null;
    }
  }, 45_000);

  const openedWithAgent = await cdp.evaluate(`(() => {
    const agent = window.__DS_AGENT__;
    if (!agent || typeof agent.openSymbol !== "function") return false;
    agent.openSymbol("AMZN");
    return true;
  })()`);

  if (!openedWithAgent) {
    await cdp.evaluate(`(() => {
      const items = [...document.querySelectorAll(".sidebar-nav .sidebar-item")];
      const screener = items.find((node) =>
        /screener|mercados|oportun|markets|screen/i.test(node.textContent ?? "")
      ) ?? items[1] ?? items[0];
      screener?.click();
      document.querySelector(".detail-panel .close-btn")?.click();
    })()`);
    await waitUntil(
      "the real ticker filter",
      () => cdp.evaluate(`document.querySelector(".ticker-search input[role=combobox]") != null`),
    );
    await cdp.evaluate(`(() => {
      const input = document.querySelector(".ticker-search input[role=combobox]");
      if (!input) return false;
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
      if (setter) setter.call(input, "AMZN");
      else input.value = "AMZN";
      input.dispatchEvent(new Event("input", { bubbles: true }));
      return true;
    })()`);
    await waitUntil("the AMZN search submit", () => cdp.evaluate(`(() => {
      const input = document.querySelector(".ticker-search input[role=combobox]");
      const button = document.querySelector(".ticker-search .search-open-btn");
      if (!input || input.value !== "AMZN" || !button) return false;
      button.click();
      return true;
    })()`), 30_000);
  }

  await waitUntil("AMZN Detail selection", () => cdp.evaluate(`(() => {
    const selected = window.__DS_AGENT__?.snapshot?.()?.selectedSymbol;
    if (selected && selected !== "AMZN") return false;
    const detail = document.querySelector(".detail-panel");
    return !!detail && (detail.textContent ?? "").includes("AMZN");
  })()`), 30_000);

  // Let the independent demand-valuation worker settle before taking the
  // isolation baseline; otherwise its legitimate FCFF update can race the FEM seed.
  let stableKey = null;
  let stableCount = 0;
  const baseline = await waitUntil("stable AMZN core valuation baseline", async () => {
    const lens = await invoke(cdp, "get_quant_lens", { symbol: "AMZN" });
    const detail = await invoke(cdp, "get_symbol_detail", { symbol: "AMZN" });
    if (!detail?.dcf_analysis) {
      stableKey = null;
      stableCount = 0;
      return null;
    }
    const intrinsic = {
      dcfValueCents: detail.dcf_value_cents ?? null,
      selectedValuationValueCents: detail.selected_valuation_value_cents ?? null,
      intrinsicValueCents: detail.intrinsic_value_cents ?? null,
      dcfAnalysisBaseCents: detail.dcf_analysis.base_intrinsic_value_cents ?? null,
    };
    const key = JSON.stringify([lens.primary_status, intrinsic]);
    if (key === stableKey) stableCount += 1;
    else {
      stableKey = key;
      stableCount = 1;
    }
    return stableCount >= 20 ? { lens, intrinsic } : null;
  }, 60_000);
  const lensBefore = baseline.lens;
  assert.ok(
    !(lensBefore.sections || []).some((s) => s.id === "manual_analyst_method"),
    "analyst-method lane existed before publication",
  );
  const intrinsicBefore = baseline.intrinsic;

  await waitUntil("Quant Lens mounted without analyst-method lane", async () => {
    const state = await cdp.evaluate(`(() => ({
      mounted: document.querySelector(".ql-panel .ql-sections") != null,
      lanePresent: document.querySelector('[data-ql-section="manual_analyst_method"]') != null,
    }))()`);
    if (!state?.mounted) return null;
    assert.equal(state.lanePresent, false, "analyst-method DOM lane existed before publication");
    return true;
  }, 30_000);

  // Publish only after Detail and Quant Lens are mounted. The new lane must arrive
  // through the cache-only dossier poll, not by remounting the panel.
  const seeded = await invoke(cdp, "debug_seed_amzn_analyst_method_e2e");
  assert.equal(seeded.symbol, "AMZN");
  assert.equal(seeded.analystMethod?.status ?? seeded.analyst_method?.status, "available");
  const targetCents =
    seeded.analystMethod?.targetValueCents
    ?? seeded.analyst_method?.target_value_cents
    ?? seeded.analystMethod?.target_value_cents;
  assert.equal(targetCents, "36400");
  assert.equal(
    seeded.analystMethod?.rankingEligible
      ?? seeded.analyst_method?.ranking_eligible
      ?? seeded.analystMethod?.ranking_eligible,
    false,
  );
  assert.equal(
    seeded.analystMethod?.strongEligible
      ?? seeded.analyst_method?.strong_eligible
      ?? seeded.analystMethod?.strong_eligible,
    false,
  );

  const dossier = await invoke(cdp, "get_valuation_dossier", { symbol: "AMZN" });
  const dossierTarget =
    dossier.analystMethod?.targetValueCents
    ?? dossier.analyst_method?.targetValueCents
    ?? dossier.analyst_method?.target_value_cents;
  assert.equal(dossierTarget, "36400");

  const lensAfter = await invoke(cdp, "get_quant_lens", { symbol: "AMZN" });
  const section = (lensAfter.sections || []).find((s) => s.id === "manual_analyst_method");
  assert.ok(section, "manual_analyst_method section missing from Quant Lens backend");
  assert.ok(
    (section.metrics || []).some(([k, v]) => k === "ranking_eligible" && v === "false"),
  );
  assert.ok(
    (section.metrics || []).some(([k, v]) => k === "strong_eligible" && v === "false"),
  );
  const detailAfter = await invoke(cdp, "get_symbol_detail", { symbol: "AMZN" });
  const intrinsicAfter = {
    dcfValueCents: detailAfter?.dcf_value_cents ?? null,
    selectedValuationValueCents: detailAfter?.selected_valuation_value_cents ?? null,
    intrinsicValueCents: detailAfter?.intrinsic_value_cents ?? null,
    dcfAnalysisBaseCents: detailAfter?.dcf_analysis?.base_intrinsic_value_cents ?? null,
  };
  assert.equal(
    lensAfter.primary_status,
    lensBefore.primary_status,
    "analyst-method publication changed Quant Lens primary_status",
  );
  assert.deepEqual(
    intrinsicAfter,
    intrinsicBefore,
    "analyst-method publication changed legacy/DCF intrinsic values",
  );

  const laneText = await waitUntil(
    "the scoped manual analyst method Quant Lens element",
    async () => cdp.evaluate(`(() => {
      const lane = document.querySelector('[data-ql-section="manual_analyst_method"]');
      const text = lane?.textContent ?? "";
      const complete = lane?.getAttribute("data-presentation-source") === "valuation_dossier_presenter"
        && /manual analyst method/i.test(text)
        && text.includes("$364.00")
        && text.includes("$13.00")
        && text.includes("28.00x")
        && text.includes("2028-12-31")
        && text.includes("2027-12")
        && text.includes("month_label")
        && /source[_ ]not[_ ]verified/i.test(text)
        && /fixture[_ ]transcription/i.test(text)
        && /diagnostic only/i.test(text)
        && /presentation source\\s*valuation[_ ]dossier[_ ]presenter/i.test(text)
        && /ranking eligible\\s*false/i.test(text)
        && /Strong eligible\\s*false/i.test(text);
      if (lane && text && !complete) {
        throw new Error("current analyst lane text: " + JSON.stringify(text));
      }
      return complete ? text : null;
    })()`),
    45_000,
  );
  assert.match(laneText, /manual analyst method/i);

  console.log("amzn-analyst-method.native.e2e: OK");
} catch (error) {
  console.error(output.join(""));
  throw error;
} finally {
  if (cdp) cdp.close();
  app.kill();
  try {
    rmSync(runDir, { recursive: true, force: true });
  } catch {
    /* temp cleanup best-effort */
  }
}
