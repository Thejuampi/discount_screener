#!/usr/bin/env node
/**
 * Live valuation checklist against a running debug app (profile qa).
 * Fail-closed: requires CDP + window.__DS_AGENT__ + qa locked feed.
 * Does not start/stop the app.
 *
 *   npm run tauri:dev:qa   # once
 *   npm run ds-ui -- self-check
 *   npm run live-qa:checklist
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  attachToWebView,
  delay,
  probeAgentSurface,
  tauriInvoke,
  waitUntil,
} from "../e2e/native/cdp-client.mjs";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, "../../..");
const stamp = new Date().toISOString().replace(/[:.]/g, "-");
const outDir = resolve(
  repoRoot,
  process.env.DS_LIVE_QA_OUTDIR ?? `.agents/workspace/tmp/live-qa-${stamp}`,
);
mkdirSync(outDir, { recursive: true });

function normClass(c) {
  return String(c ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");
}

function isFinancialClass(c) {
  const n = normClass(c);
  // Do NOT use includes("financial") — "operating_non_financial" contains that substring.
  return (
    n === "financialservices" ||
    n === "financial" ||
    n.startsWith("financialservices") ||
    (n.includes("financial") && !n.includes("nonfinancial"))
  );
}

function isOperatingClass(c) {
  const n = normClass(c);
  return n === "operatingnonfinancial" || n === "operating" || n.startsWith("operatingnonfinancial");
}

/** @typedef {{ symbol: string, expect: (row: any) => string[] }} Case */

/** @type {Case[]} */
const CASES = [
  {
    symbol: "T",
    expect: (row) => {
      const fails = [];
      if (row.model === "residual_income_equity") {
        fails.push(`T must not be residual_income (got ${row.model})`);
      }
      if (isFinancialClass(row.business_class)) {
        fails.push("T must not be FinancialServices");
      }
      if (normClass(row.business_class) === "unclassified" && !row.unavailable) {
        fails.push("Unclassified without unavailable reason");
      }
      if (row.base != null && row.base > 0 && row.base < 50) {
        fails.push(`T base absurdly low cents=${row.base}`);
      }
      if (row.base != null && row.bear != null && row.bull != null) {
        if (!(row.bear <= row.base && row.base <= row.bull)) {
          fails.push("T scenarios inverted");
        }
      }
      return fails;
    },
  },
  {
    symbol: "AMZN",
    expect: (row) => {
      const fails = [];
      if (row.model === "residual_income_equity") {
        fails.push("AMZN must not be residual_income primary");
      }
      if (row.base != null && row.bear != null && row.bull != null) {
        if (!(row.bear <= row.base && row.base <= row.bull)) {
          fails.push(`AMZN scenarios inverted bear=${row.bear} base=${row.base} bull=${row.bull}`);
        }
      }
      // Investment-wave understatement: full gross CapEx FCFF → ~$50 is absurd vs ~$270 market.
      if (row.base != null && row.base > 0 && row.base < 10_000) {
        fails.push(
          `AMZN base under $100 (cents=${row.base}); owner-earnings CapEx-wave path required`,
        );
      }
      return fails;
    },
  },
  {
    symbol: "CI",
    expect: (row) => {
      const fails = [];
      if (row.model === "fcff_wacc") {
        fails.push("CI must not be FCFF primary (float mirage)");
      }
      if (row.model && row.model !== "residual_income_equity" && !row.unavailable) {
        fails.push(`CI expected residual_income or unavailable, got ${row.model}`);
      }
      if (row.business_class && isOperatingClass(row.business_class) && !isFinancialClass(row.business_class)) {
        fails.push(
          `CI classified as operating (got ${row.business_class}); expected financial/managed care`,
        );
      }
      return fails;
    },
  },
  {
    symbol: "JPM",
    expect: (row) => {
      const fails = [];
      if (row.model === "fcff_wacc") {
        fails.push("JPM must not be FCFF primary");
      }
      if (row.model && row.model !== "residual_income_equity" && !row.unavailable) {
        fails.push(`JPM expected residual_income or unavailable, got ${row.model}`);
      }
      return fails;
    },
  },
  {
    symbol: "ACGL",
    expect: (row) => {
      const fails = [];
      if (row.model === "fcff_wacc") {
        fails.push("ACGL must not be FCFF primary (float OCF)");
      }
      if (row.model && row.model !== "residual_income_equity" && !row.unavailable) {
        fails.push(`ACGL expected residual_income or unavailable, got ${row.model}`);
      }
      return fails;
    },
  },
  {
    symbol: "AAPL",
    expect: (row) => {
      const fails = [];
      if (row.model === "residual_income_equity") {
        fails.push("AAPL must not be residual_income");
      }
      if (row.base != null && row.base > 0 && row.base < 1000) {
        fails.push(`AAPL base looks OOM low: cents=${row.base}`);
      }
      if (row.base != null && row.bear != null && row.bull != null) {
        if (!(row.bear <= row.base && row.base <= row.bull)) {
          fails.push("AAPL inverted scenarios");
        }
      }
      return fails;
    },
  },
  {
    symbol: "COF",
    expect: (row) => {
      const fails = [];
      if (row.model === "fcff_wacc") {
        fails.push("COF must not be FCFF primary");
      }
      if (row.model === "residual_income_equity" && (row.base == null || row.base <= 0)) {
        fails.push("COF residual_income with non-positive base");
      }
      return fails;
    },
  },
];

function summarizeDetail(symbol, detail) {
  const a = detail?.dcf_analysis ?? null;
  return {
    symbol,
    valuation_status: detail?.valuation_status ?? null,
    unavailable: detail?.valuation_unavailable_reason ?? null,
    dcf_value_cents: detail?.dcf_value_cents ?? null,
    model: a?.model ?? null,
    business_class: a?.business_class ?? null,
    base: a?.base_intrinsic_value_cents ?? null,
    bear: a?.bear_intrinsic_value_cents ?? null,
    bull: a?.bull_intrinsic_value_cents ?? null,
    discount_rate_kind: a?.discount_rate_kind ?? null,
    quality: a?.quality ?? a?.model_quality ?? null,
    sector: detail?.sector ?? detail?.fundamentals?.sector ?? null,
    industry: detail?.industry ?? detail?.fundamentals?.industry ?? null,
  };
}

function dollars(cents) {
  if (cents == null) return null;
  return `$${(cents / 100).toFixed(2)}`;
}

async function openDetailUi(client, symbol) {
  const symJson = JSON.stringify(symbol);
  const viaAgent = await client.evaluate(`(() => {
    const agent = window.__DS_AGENT__;
    if (!agent?.openSymbol) return false;
    agent.openSymbol(${symJson});
    return true;
  })()`);

  if (!viaAgent) {
    return { uiOk: false, reason: "no_agent_bridge", dcfSlot: null, viaAgent: false };
  }

  try {
    await waitUntil(
      `${symbol} selected`,
      async () =>
        client.evaluate(`(() => {
          const agent = window.__DS_AGENT__?.snapshot?.();
          if (!agent || agent.selectedSymbol !== ${symJson}) return false;
          const detail = document.querySelector(".detail-panel");
          return !!(detail && (detail.textContent ?? "").includes(${symJson}));
        })()`),
      15_000,
    );
  } catch {
    return { uiOk: false, reason: "detail not selected", dcfSlot: null, viaAgent: true };
  }

  let dcfSlot = null;
  try {
    dcfSlot = await waitUntil(
      `${symbol} dcf slot`,
      async () =>
        client.evaluate(`(() => {
          const agent = window.__DS_AGENT__?.snapshot?.();
          if (!agent || agent.selectedSymbol !== ${symJson}) return null;
          const t = document.querySelector(".price-summary .dcf-slot")?.textContent ?? "";
          if (!t || /valoraci[oó]n\u2026|valuation\u2026/i.test(t)) return null;
          if (t.replace(/\s/g, "") === "···") return null;
          return t;
        })()`),
      45_000,
    );
  } catch {
    dcfSlot = await client.evaluate(
      `document.querySelector(".price-summary .dcf-slot")?.textContent ?? null`,
    );
  }

  return { uiOk: Boolean(dcfSlot), viaAgent: true, dcfSlot };
}

function assertUiSlot(symbol, row, dcfSlot, uiOk) {
  const fails = [];
  if (!uiOk || !dcfSlot) {
    fails.push("UI detail slot not settled for symbol");
    return fails;
  }
  const lower = dcfSlot.toLowerCase();
  const baseStr = dollars(row.base);

  // Stale previous-symbol traps
  if (symbol !== "AMZN" && /amazon|\bAMZN\b/i.test(dcfSlot) && row.model === "residual_income_equity") {
    fails.push("stale AMZN text in residual detail");
  }
  if (symbol !== "COF" && /\bCOF\b/.test(dcfSlot) && /residual/i.test(dcfSlot) && row.model === "fcff_wacc") {
    fails.push("stale COF residual text on FCFF name");
  }

  if (row.model === "residual_income_equity" && !row.unavailable) {
    if (!/residual/i.test(dcfSlot) && !/disput/i.test(dcfSlot)) {
      fails.push(`residual model without residual label: ${JSON.stringify(dcfSlot.slice(0, 140))}`);
    }
    if (/fcff/i.test(dcfSlot) && !/residual/i.test(dcfSlot)) {
      fails.push(`UI looks FCFF-labeled for residual: ${JSON.stringify(dcfSlot.slice(0, 120))}`);
    }
    if (baseStr && !/disput/i.test(dcfSlot) && !dcfSlot.includes(baseStr)) {
      fails.push(`UI slot missing residual base ${baseStr}: ${JSON.stringify(dcfSlot.slice(0, 140))}`);
    }
    if (/no disponible|unavailable/i.test(dcfSlot) && !/disput/i.test(dcfSlot)) {
      fails.push(`UI unavailable but backend residual ready: ${JSON.stringify(dcfSlot.slice(0, 160))}`);
    }
  }

  if (String(row.valuation_status).toLowerCase() === "disputed") {
    if (!/disput|no concuerdan|no hay un valor único/i.test(dcfSlot)) {
      fails.push(`backend disputed but UI not disputed: ${JSON.stringify(dcfSlot.slice(0, 160))}`);
    }
    // Disputed should still surface the model anchor somewhere
    if (baseStr && !dcfSlot.includes(baseStr) && !dcfSlot.includes(String((row.base / 100).toFixed(0)))) {
      fails.push(`disputed UI missing DCF base ${baseStr}`);
    }
  }

  if (row.model === "fcff_wacc" && row.base != null && !row.unavailable) {
    if (/no disponible|unavailable/i.test(dcfSlot) && !/disput/i.test(dcfSlot)) {
      fails.push(`UI unavailable but backend has FCFF base: ${JSON.stringify(dcfSlot.slice(0, 160))}`);
    }
    // Non-disputed FCFF should show base or forward path
    if (
      String(row.valuation_status).toLowerCase() !== "disputed" &&
      baseStr &&
      !dcfSlot.includes(baseStr) &&
      !/forward|ganancias/i.test(dcfSlot)
    ) {
      fails.push(`FCFF UI missing base ${baseStr}: ${JSON.stringify(dcfSlot.slice(0, 140))}`);
    }
  }

  if (row.unavailable && !/no disponible|unavailable|no catalog|not eligible|faltan|missing|disput/i.test(lower)) {
    if (dcfSlot.trim() === "—" || dcfSlot.trim() === "-") {
      fails.push("UI mute dash for unavailable backend reason");
    }
  }
  return fails;
}

async function main() {
  const { client, baseUrl } = await attachToWebView({ timeoutMs: 15_000 });
  const report = {
    startedAt: new Date().toISOString(),
    baseUrl,
    outDir,
    feed: null,
    agent: null,
    cases: [],
    passed: 0,
    failed: 0,
    elicitationHardening: true,
  };

  try {
    const surface = await probeAgentSurface(client);
    report.agent = surface;
    if (!surface.hasAgent || !surface.hasInvoke) {
      console.error(
        "FAIL: require DEV agent bridge + Tauri invoke. Run: npm run tauri:dev:qa (and ensure App.tsx HMR loaded).",
      );
      console.error(JSON.stringify(surface, null, 2));
      process.exitCode = 2;
      writeFileSync(join(outDir, "report.json"), JSON.stringify(report, null, 2));
      return;
    }

    report.feed = await tauriInvoke(client, "get_feed_status");
    if (report.feed?.profile_name !== "qa" || !report.feed?.profile_locked || !report.feed?.running) {
      console.error("FAIL: feed is not locked qa profile running", report.feed);
      process.exitCode = 2;
      writeFileSync(join(outDir, "report.json"), JSON.stringify(report, null, 2));
      return;
    }

    try {
      await client.call("Page.enable");
    } catch {
      /* ok */
    }
    const shot = await client.call("Page.captureScreenshot", { format: "png", fromSurface: true });
    if (shot?.data) {
      writeFileSync(join(outDir, "app-home.png"), Buffer.from(shot.data, "base64"));
    }

    for (const c of CASES) {
      process.stderr.write(`… ${c.symbol}\n`);
      let detail;
      let detailError = null;
      try {
        try {
          await tauriInvoke(client, "ensure_symbol_loaded", { symbol: c.symbol });
        } catch {
          /* optional one-shot */
        }
        await delay(400);
        detail = await tauriInvoke(client, "get_symbol_detail", { symbol: c.symbol });
      } catch (error) {
        detailError = String(error.message ?? error);
        detail = null;
      }

      if (detail && !detail.dcf_analysis && !detail.valuation_unavailable_reason) {
        await delay(2500);
        try {
          detail = await tauriInvoke(client, "get_symbol_detail", { symbol: c.symbol });
        } catch (error) {
          detailError = String(error.message ?? error);
        }
      }

      writeFileSync(join(outDir, `detail-${c.symbol}.json`), JSON.stringify(detail, null, 2));
      const row = detail ? summarizeDetail(c.symbol, detail) : { symbol: c.symbol, error: detailError };
      writeFileSync(join(outDir, `summary-${c.symbol}.json`), JSON.stringify(row, null, 2));

      const fails = [];
      if (detailError) fails.push(`invoke error: ${detailError}`);
      else fails.push(...c.expect(row));

      const ui = await openDetailUi(client, c.symbol);
      writeFileSync(join(outDir, `ui-${c.symbol}.json`), JSON.stringify(ui, null, 2));
      if (detail && !detailError) {
        fails.push(...assertUiSlot(c.symbol, row, ui.dcfSlot, ui.uiOk));
      } else if (!ui.uiOk) {
        fails.push("UI open-detail failed");
      }

      try {
        const s = await client.call("Page.captureScreenshot", { format: "png", fromSurface: true });
        if (s?.data) {
          writeFileSync(join(outDir, `detail-${c.symbol}.png`), Buffer.from(s.data, "base64"));
        }
      } catch {
        /* non-fatal */
      }

      const ok = fails.length === 0;
      if (ok) report.passed += 1;
      else report.failed += 1;

      report.cases.push({
        symbol: c.symbol,
        ok,
        fails,
        backend: row,
        ui,
      });

      const flag = ok ? "PASS" : "FAIL";
      const model = row.model ?? "none";
      const base = dollars(row.base) ?? "—";
      console.log(
        `${flag} ${c.symbol.padEnd(5)} model=${String(model).padEnd(24)} base=${String(base).padEnd(10)} ui=${ui.dcfSlot ? "slot" : "no-slot"}`,
      );
      if (!ok) {
        for (const f of fails) console.log(`       - ${f}`);
        if (ui.dcfSlot) console.log(`       slot: ${ui.dcfSlot.slice(0, 140)}`);
      }
    }
  } finally {
    client.close();
  }

  report.finishedAt = new Date().toISOString();
  writeFileSync(join(outDir, "report.json"), JSON.stringify(report, null, 2));
  // also stamp a stable latest pointer file
  writeFileSync(
    resolve(repoRoot, ".agents/workspace/tmp/live-qa-latest.json"),
    JSON.stringify({ outDir, passed: report.passed, failed: report.failed, finishedAt: report.finishedAt }, null, 2),
  );
  console.log(`\n${report.passed} passed / ${report.failed} failed → ${outDir}`);
  if (report.failed > 0) process.exitCode = 1;
}

main().catch((error) => {
  console.error(error.stack ?? error);
  process.exitCode = 1;
});
