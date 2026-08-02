#!/usr/bin/env node
/**
 * Attach-mode UI control for the Windows Tauri app (agent + human).
 *
 * Prerequisites:
 *   - App running in **debug** build (`npm run tauri:dev:qa` or debug exe)
 *   - WebView2 CDP on loopback (default 127.0.0.1:9222 — auto-enabled in debug)
 *   - Prefer profile `qa` for live QA (AGENTS.md)
 *
 * Does NOT start or kill the app. Attach only — reuse one long-lived process.
 *
 * Usage (from apps/windows):
 *   npm run ds-ui -- status
 *   npm run ds-ui -- feed
 *   npm run ds-ui -- invoke get_symbol_detail '{"symbol":"COF"}'
 *   npm run ds-ui -- text ".price-summary .dcf-slot"
 *   npm run ds-ui -- screenshot
 *   npm run ds-ui -- open-detail COF
 *   npm run ds-ui -- help
 *
 * Env:
 *   DS_UI_CDP_HOST (default 127.0.0.1)
 *   DS_UI_CDP_PORT (default 9222)
 *   DS_UI_CDP_TIMEOUT_MS (default 10000)
 *   DS_UI_SCREENSHOT_DIR (default .agents/workspace/tmp/ui-shots)
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  attachToWebView,
  delay,
  listCdpTargets,
  pickPageTarget,
  probeAgentSurface,
  tauriInvoke,
  waitUntil,
} from "../e2e/native/cdp-client.mjs";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const windowsRoot = resolve(scriptDir, "..");
const repoRoot = resolve(windowsRoot, "../..");

function printJson(value) {
  process.stdout.write(`${JSON.stringify(value, null, 2)}\n`);
}

function printText(value) {
  if (value == null) {
    process.stdout.write("(null)\n");
    return;
  }
  if (typeof value === "string") {
    process.stdout.write(value.endsWith("\n") ? value : `${value}\n`);
    return;
  }
  printJson(value);
}

function usage() {
  return `ds-ui — attach to running Windows debug app via WebView2 CDP

USAGE
  node scripts/ds-ui.mjs <command> [args...]
  npm run ds-ui -- <command> [args...]

COMMANDS
  help                         This text
  status | ping                CDP up? Tauri bridge? feed profile?
  targets                      List CDP page targets (JSON)
  feed                         invoke get_feed_status
  invoke <cmd> [json-args]     Tauri IPC (same bridge as api.ts)
  eval <js>                    Runtime.evaluate expression
  text <css>                   textContent of first match
  html <css>                   innerHTML of first match
  exists <css>                 true/false if selector matches
  count <css>                  match count
  click <css>                  click first match
  click-text <needle> [css]    click first node whose text includes needle
                               default css: tr.stock-row, button, a, [role=button]
  type <css> <text>            set input value + input event
  nav <index|name>             click sidebar item (0-based index or label substring)
  open-detail <SYMBOL>         Open Detail (prefers window.__DS_AGENT__ in DEV)
  close-detail                 Close Detail via agent bridge / close button
  agent-snap                   window.__DS_AGENT__.snapshot() JSON
  dcf-slot                     text of .price-summary .dcf-slot
  detail-panel                 text of .detail-panel (truncated if huge)
  screenshot [path]            PNG via Page.captureScreenshot
  wait <css> [--contains s] [--timeout ms]
                               poll until selector exists / contains text
  qa-snapshot [SYMBOL]         feed + optional invoke detail + dcf-slot (JSON)
  self-check                   Fail-closed gate: CDP + Tauri + agent + qa feed
                               + open-detail smoke + screenshot (exit 0 only if all ok)

ENV
  DS_UI_CDP_HOST=127.0.0.1
  DS_UI_CDP_PORT=9222
  DS_UI_CDP_TIMEOUT_MS=10000
  DS_UI_SCREENSHOT_DIR=<repo>/.agents/workspace/tmp/ui-shots
  DS_UI_REQUIRE_AGENT=1        open-detail / self-check require __DS_AGENT__ (default 1)

NOTES
  - Debug builds only (release has no CDP).
  - Loopback-only; do not expose the port off-machine.
  - Live QA: keep one \`npm run tauri:dev:qa\` process; use this CLI against it.
  - Prefer DEV frontend so window.__DS_AGENT__ is present (tauri:dev).
`;
}

function requireAgentEnv() {
  const raw = process.env.DS_UI_REQUIRE_AGENT;
  if (raw == null || raw === "") return true;
  return !(raw === "0" || raw.toLowerCase() === "false" || raw === "no");
}

function parseArgs(argv) {
  const args = [];
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token === "--timeout" && argv[i + 1]) {
      flags.timeout = Number(argv[++i]);
    } else if (token === "--contains" && argv[i + 1]) {
      flags.contains = argv[++i];
    } else if (token === "--json") {
      flags.json = true;
    } else if (token.startsWith("--")) {
      flags[token.slice(2)] = true;
    } else {
      args.push(token);
    }
  }
  return { args, flags };
}

function parseJsonArg(raw) {
  if (raw == null || raw === "") return {};
  try {
    return JSON.parse(raw);
  } catch (error) {
    throw new Error(`Invalid JSON args: ${raw}\n${error.message}`);
  }
}

async function withClient(fn) {
  const { client, target, baseUrl } = await attachToWebView();
  try {
    return await fn(client, { target, baseUrl });
  } finally {
    client.close();
  }
}

async function cmdStatus() {
  let list;
  try {
    list = await listCdpTargets();
  } catch (error) {
    printJson({
      ok: false,
      cdp: false,
      agentBridge: false,
      qaReady: false,
      error: String(error.message ?? error),
      hint: "Start debug app: cd apps/windows && npm run tauri:dev:qa",
    });
    process.exitCode = 1;
    return;
  }

  const page = pickPageTarget(list.targets);
  if (!page) {
    printJson({
      ok: false,
      cdp: true,
      agentBridge: false,
      qaReady: false,
      baseUrl: list.baseUrl,
      targets: list.targets.length,
      error: "No usable page target with webSocketDebuggerUrl",
    });
    process.exitCode = 1;
    return;
  }

  await withClient(async (client) => {
    const surface = await probeAgentSurface(client);
    let feed = null;
    let feedError = null;
    if (surface.hasInvoke) {
      try {
        feed = await tauriInvoke(client, "get_feed_status");
      } catch (error) {
        feedError = String(error.message ?? error);
      }
    }
    const qaReady =
      !!feed &&
      feed.profile_name === "qa" &&
      feed.profile_locked === true &&
      feed.running === true;
    const ok = surface.hasInvoke && feed != null && surface.hasAgent;
    printJson({
      ok,
      cdp: true,
      baseUrl: list.baseUrl,
      title: surface.title,
      tauriInvoke: surface.hasInvoke,
      agentBridge: surface.hasAgent,
      agentVersion: surface.agentVersion,
      qaReady,
      requireAgent: requireAgentEnv(),
      feed,
      feedError,
      target: { id: page.id, title: page.title, url: page.url },
      hints: [
        !surface.hasInvoke ? "Tauri invoke missing — wrong target or app not ready" : null,
        !surface.hasAgent
          ? "window.__DS_AGENT__ missing — need DEV frontend (npm run tauri:dev:qa) with HMR App.tsx"
          : null,
        feed && !qaReady
          ? "Feed not qa+locked+running — relaunch with npm run tauri:dev:qa"
          : null,
      ].filter(Boolean),
    });
    if (!ok) process.exitCode = 1;
  });
}

async function cmdTargets() {
  const list = await listCdpTargets();
  printJson(list);
}

async function cmdInvoke(command, argsRaw) {
  if (!command) throw new Error("invoke requires <command> [json-args]");
  const args = parseJsonArg(argsRaw);
  await withClient(async (client) => {
    const result = await tauriInvoke(client, command, args);
    printJson(result);
  });
}

async function cmdEval(expression) {
  if (!expression) throw new Error("eval requires a JS expression");
  await withClient(async (client) => {
    const result = await client.evaluate(expression);
    printText(result);
  });
}

async function cmdText(selector) {
  if (!selector) throw new Error("text requires a CSS selector");
  await withClient(async (client) => {
    const text = await client.evaluate(
      `document.querySelector(${JSON.stringify(selector)})?.textContent ?? null`,
    );
    printText(text);
  });
}

async function cmdHtml(selector) {
  if (!selector) throw new Error("html requires a CSS selector");
  await withClient(async (client) => {
    const html = await client.evaluate(
      `document.querySelector(${JSON.stringify(selector)})?.innerHTML ?? null`,
    );
    printText(html);
  });
}

async function cmdExists(selector) {
  if (!selector) throw new Error("exists requires a CSS selector");
  await withClient(async (client) => {
    const ok = await client.evaluate(
      `document.querySelector(${JSON.stringify(selector)}) != null`,
    );
    printText(String(ok));
    if (!ok) process.exitCode = 1;
  });
}

async function cmdCount(selector) {
  if (!selector) throw new Error("count requires a CSS selector");
  await withClient(async (client) => {
    const n = await client.evaluate(
      `document.querySelectorAll(${JSON.stringify(selector)}).length`,
    );
    printText(String(n));
  });
}

async function cmdClick(selector) {
  if (!selector) throw new Error("click requires a CSS selector");
  await withClient(async (client) => {
    const ok = await client.evaluate(`(() => {
      const el = document.querySelector(${JSON.stringify(selector)});
      if (!el) return false;
      el.click();
      return true;
    })()`);
    if (!ok) {
      throw new Error(`No element for selector: ${selector}`);
    }
    printText("clicked");
  });
}

async function cmdClickText(needle, selector) {
  if (!needle) throw new Error("click-text requires <needle> [css]");
  const css = selector ?? "tr.stock-row, button, a, [role=button], .sidebar-item, [data-symbol]";
  await withClient(async (client) => {
    const ok = await client.evaluate(`(() => {
      const needle = ${JSON.stringify(needle)};
      const nodes = [...document.querySelectorAll(${JSON.stringify(css)})];
      const el = nodes.find((n) => (n.textContent ?? "").includes(needle));
      if (!el) return false;
      el.click();
      return true;
    })()`);
    if (!ok) {
      throw new Error(`No element containing ${JSON.stringify(needle)} under ${css}`);
    }
    printText("clicked");
  });
}

async function cmdType(selector, text) {
  if (!selector || text == null) throw new Error("type requires <css> <text>");
  await withClient(async (client) => {
    const ok = await client.evaluate(`(() => {
      const input = document.querySelector(${JSON.stringify(selector)});
      if (!input) return false;
      const setter = Object.getOwnPropertyDescriptor(
        input instanceof HTMLTextAreaElement
          ? HTMLTextAreaElement.prototype
          : HTMLInputElement.prototype,
        "value",
      )?.set;
      if (setter) setter.call(input, ${JSON.stringify(text)});
      else input.value = ${JSON.stringify(text)};
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    })()`);
    if (!ok) throw new Error(`No input for selector: ${selector}`);
    printText("typed");
  });
}

async function cmdNav(target) {
  if (target == null) throw new Error("nav requires <index|name>");
  await withClient(async (client) => {
    const result = await client.evaluate(`(() => {
      const items = [...document.querySelectorAll(".sidebar-nav .sidebar-item")];
      if (!items.length) return { ok: false, error: "no sidebar items" };
      const raw = ${JSON.stringify(String(target))};
      let el = null;
      if (/^\\d+$/.test(raw)) {
        el = items[Number(raw)] ?? null;
      } else {
        const lower = raw.toLowerCase();
        el = items.find((n) => (n.textContent ?? "").toLowerCase().includes(lower)) ?? null;
      }
      if (!el) {
        return {
          ok: false,
          error: "no match",
          labels: items.map((n) => (n.textContent ?? "").trim().slice(0, 80)),
        };
      }
      el.click();
      return { ok: true, label: (el.textContent ?? "").trim() };
    })()`);
    printJson(result);
    if (!result.ok) process.exitCode = 1;
  });
}

async function cmdOpenDetail(symbol) {
  if (!symbol) throw new Error("open-detail requires <SYMBOL>");
  const sym = symbol.toUpperCase();
  await withClient(async (client) => {
    // Prefer debug agent bridge (React state) — reliable even when list filters hide rows.
    const viaAgent = await client.evaluate(`(() => {
      const agent = window.__DS_AGENT__;
      if (!agent || typeof agent.openSymbol !== "function") return { ok: false, reason: "no_agent" };
      agent.openSymbol(${JSON.stringify(sym)});
      return { ok: true, reason: "agent" };
    })()`);

    if (!viaAgent?.ok) {
      // Fallback: DOM path (Spanish sidebar = Mercados; English = Screener/Markets).
      try {
        await tauriInvoke(client, "ensure_symbol_loaded", { symbol: sym });
      } catch {
        /* optional */
      }

      await client.evaluate(`(() => {
        const items = [...document.querySelectorAll(".sidebar-nav .sidebar-item")];
        const byLabel = items.find((n) =>
          /screener|mercados|oportun|markets|screen/i.test(n.textContent ?? "")
        );
        (byLabel ?? items[1] ?? items[0])?.click();
        document.querySelector(".detail-panel .close-btn")?.click();
      })()`);
      await delay(250);

      await waitUntil(
        "ticker search",
        () => client.evaluate(`document.querySelector(".ticker-search input[role=combobox]") != null`),
        15_000,
      );

      // Clear list text filter via search empty, then set symbol and submit Open.
      await client.evaluate(`(() => {
        const input = document.querySelector(".ticker-search input[role=combobox]");
        if (!input) return false;
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
        const setVal = (v) => {
          if (setter) setter.call(input, v);
          else input.value = v;
          input.dispatchEvent(new Event("input", { bubbles: true }));
          input.dispatchEvent(new Event("change", { bubbles: true }));
        };
        setVal("");
        setVal(${JSON.stringify(sym)});
        return true;
      })()`);
      await delay(450);
      await client.evaluate(`(() => {
        const suggestion = document.querySelector(".search-suggestion");
        if (suggestion) { suggestion.click(); return "suggestion"; }
        const openBtn = document.querySelector(".search-open-btn");
        if (openBtn) { openBtn.click(); return "open-btn"; }
        const input = document.querySelector(".ticker-search input[role=combobox]");
        if (input) {
          input.dispatchEvent(new KeyboardEvent("keydown", {
            key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true,
          }));
          return "enter";
        }
        const row = [...document.querySelectorAll("tr.stock-row")]
          .find((c) => (c.textContent ?? "").includes(${JSON.stringify(sym)}));
        if (row) { row.click(); return "row"; }
        return "none";
      })()`);
    }

    await waitUntil(
      `${sym} detail selected`,
      async () =>
        client.evaluate(`(() => {
          const agent = window.__DS_AGENT__?.snapshot?.();
          if (agent && agent.selectedSymbol !== ${JSON.stringify(sym)}) return false;
          const detail = document.querySelector(".detail-panel");
          if (!detail) return false;
          const probe = detail.textContent ?? "";
          return probe.includes(${JSON.stringify(sym)});
        })()`),
      15_000,
    );

    // Remount is async: wait until slot leaves loading while selection stays on symbol.
    let dcfSlot = null;
    try {
      dcfSlot = await waitUntil(
        `${sym} dcf slot settled`,
        async () =>
          client.evaluate(`(() => {
            const agent = window.__DS_AGENT__?.snapshot?.();
            if (agent && agent.selectedSymbol !== ${JSON.stringify(sym)}) return null;
            const t = document.querySelector(".price-summary .dcf-slot")?.textContent ?? "";
            if (!t) return null;
            if (/valoraci[oó]n\u2026|valuation\u2026/i.test(t)) return null;
            if (t.replace(/\s/g, "") === "···" || t.trim() === "…") return null;
            return t;
          })()`),
        45_000,
      );
    } catch {
      dcfSlot = await client.evaluate(
        `document.querySelector(".price-summary .dcf-slot")?.textContent ?? null`,
      );
    }

    const snap = await client.evaluate(
      `window.__DS_AGENT__?.snapshot?.() ?? null`,
    );
    const selected = snap?.selectedSymbol ?? null;
    const path = viaAgent?.ok ? "agent" : "dom";
    const ok =
      selected === sym &&
      typeof dcfSlot === "string" &&
      dcfSlot.length > 0 &&
      !/valoraci[oó]n\u2026|valuation\u2026/i.test(dcfSlot);
    printJson({
      ok,
      symbol: sym,
      path,
      selected,
      dcfSlot,
    });
    if (requireAgentEnv() && path !== "agent") {
      process.stderr.write(
        "ds-ui: open-detail used DOM fallback but DS_UI_REQUIRE_AGENT=1 (set 0 to allow)\n",
      );
      process.exitCode = 1;
      return;
    }
    if (!ok) process.exitCode = 1;
  });
}

/**
 * Fail-closed readiness gate for agents before claiming live QA is possible.
 */
async function cmdSelfCheck() {
  const steps = [];
  const record = (name, ok, detail) => {
    steps.push({ name, ok, detail: detail ?? null });
    return ok;
  };

  let list;
  try {
    list = await listCdpTargets();
    record("cdp_list", true, { baseUrl: list.baseUrl, targets: list.targets.length });
  } catch (error) {
    record("cdp_list", false, String(error.message ?? error));
    printJson({ ok: false, steps });
    process.exitCode = 1;
    return;
  }

  const page = pickPageTarget(list.targets);
  if (!record("page_target", !!page, page ? { title: page.title, url: page.url } : null)) {
    printJson({ ok: false, steps });
    process.exitCode = 1;
    return;
  }

  await withClient(async (client) => {
    const surface = await probeAgentSurface(client);
    record("tauri_invoke", surface.hasInvoke, null);
    record("agent_bridge", surface.hasAgent, { version: surface.agentVersion });

    let feed = null;
    try {
      feed = await tauriInvoke(client, "get_feed_status");
      record(
        "feed_qa_locked",
        feed?.profile_name === "qa" && feed?.profile_locked === true && feed?.running === true,
        {
          profile_name: feed?.profile_name,
          profile_locked: feed?.profile_locked,
          running: feed?.running,
          symbols_loaded: feed?.symbols_loaded,
        },
      );
    } catch (error) {
      record("feed_qa_locked", false, String(error.message ?? error));
    }

    // open-detail smoke (COF is residual contract; always one-shot loadable)
    const smokeSym = "COF";
    if (surface.hasAgent) {
      await client.evaluate(`window.__DS_AGENT__.openSymbol(${JSON.stringify(smokeSym)})`);
    } else {
      record("open_detail_smoke", false, "no agent bridge");
    }

    if (surface.hasAgent) {
      try {
        await waitUntil(
          `${smokeSym} selected`,
          async () =>
            client.evaluate(
              `window.__DS_AGENT__?.snapshot?.()?.selectedSymbol === ${JSON.stringify(smokeSym)}`,
            ),
          15_000,
        );
        const slot = await waitUntil(
          `${smokeSym} slot`,
          async () =>
            client.evaluate(`(() => {
              if (window.__DS_AGENT__?.snapshot?.()?.selectedSymbol !== ${JSON.stringify(smokeSym)}) return null;
              const t = document.querySelector(".price-summary .dcf-slot")?.textContent ?? "";
              if (!t || /valoraci[oó]n\u2026|valuation\u2026/i.test(t)) return null;
              if (t.replace(/\\s/g, "") === "···") return null;
              return t;
            })()`),
          45_000,
        );
        record("open_detail_smoke", true, { symbol: smokeSym, dcfSlot: String(slot).slice(0, 160) });
      } catch (error) {
        record("open_detail_smoke", false, String(error.message ?? error));
      }
    }

    // screenshot
    try {
      try {
        await client.call("Page.enable");
      } catch {
        /* ok */
      }
      const shot = await client.call("Page.captureScreenshot", { format: "png", fromSurface: true });
      const dir =
        process.env.DS_UI_SCREENSHOT_DIR ??
        resolve(repoRoot, ".agents", "workspace", "tmp", "ui-shots");
      mkdirSync(dir, { recursive: true });
      const outPath = join(dir, `self-check-${Date.now()}.png`);
      if (shot?.data) {
        writeFileSync(outPath, Buffer.from(shot.data, "base64"));
        record("screenshot", true, { path: outPath });
      } else {
        record("screenshot", false, "no data");
      }
    } catch (error) {
      record("screenshot", false, String(error.message ?? error));
    }

    // backend invoke smoke
    try {
      const detail = await tauriInvoke(client, "get_symbol_detail", { symbol: smokeSym });
      record(
        "invoke_detail",
        detail != null && (detail.dcf_analysis != null || detail.valuation_unavailable_reason != null),
        {
          model: detail?.dcf_analysis?.model ?? null,
          status: detail?.valuation_status ?? null,
        },
      );
    } catch (error) {
      record("invoke_detail", false, String(error.message ?? error));
    }
  });

  const ok = steps.every((s) => s.ok);
  printJson({ ok, steps, requireAgent: requireAgentEnv() });
  if (!ok) process.exitCode = 1;
}

async function cmdCloseDetail() {
  await withClient(async (client) => {
    const path = await client.evaluate(`(() => {
      if (window.__DS_AGENT__?.closeDetail) {
        window.__DS_AGENT__.closeDetail();
        return "agent";
      }
      const btn = document.querySelector(".detail-panel .close-btn");
      if (btn) { btn.click(); return "dom"; }
      return "none";
    })()`);
    printJson({ closed: path !== "none", path });
  });
}

async function cmdAgentSnap() {
  await withClient(async (client) => {
    const snap = await client.evaluate(`window.__DS_AGENT__?.snapshot?.() ?? null`);
    if (!snap) {
      printJson({
        ok: false,
        error: "window.__DS_AGENT__ missing — need DEV frontend (tauri:dev) after HMR/reload",
      });
      process.exitCode = 1;
      return;
    }
    printJson(snap);
  });
}

async function cmdDcfSlot() {
  await withClient(async (client) => {
    const text = await client.evaluate(
      `document.querySelector(".price-summary .dcf-slot")?.textContent ?? null`,
    );
    printText(text);
  });
}

async function cmdDetailPanel() {
  await withClient(async (client) => {
    const text = await client.evaluate(
      `document.querySelector(".detail-panel")?.textContent ?? null`,
    );
    if (text && text.length > 12_000) {
      printText(`${text.slice(0, 12_000)}\n… [truncated ${text.length} chars]`);
    } else {
      printText(text);
    }
  });
}

async function cmdScreenshot(pathArg) {
  const dir =
    process.env.DS_UI_SCREENSHOT_DIR ??
    resolve(repoRoot, ".agents", "workspace", "tmp", "ui-shots");
  mkdirSync(dir, { recursive: true });
  const outPath =
    pathArg != null
      ? resolve(pathArg)
      : join(dir, `shot-${new Date().toISOString().replace(/[:.]/g, "-")}.png`);

  await withClient(async (client) => {
    // Page domain is available on WebView2 for captureScreenshot.
    try {
      await client.call("Page.enable");
    } catch {
      // some hosts enable implicitly
    }
    const result = await client.call("Page.captureScreenshot", {
      format: "png",
      fromSurface: true,
    });
    if (!result?.data) throw new Error("Page.captureScreenshot returned no data");
    writeFileSync(outPath, Buffer.from(result.data, "base64"));
    printJson({ path: outPath, bytes: Buffer.from(result.data, "base64").length });
  });
}

async function cmdWait(selector, flags) {
  if (!selector) throw new Error("wait requires <css>");
  const timeoutMs = flags.timeout ?? 30_000;
  const contains = flags.contains;
  await withClient(async (client) => {
    const text = await waitUntil(
      contains ? `${selector} containing ${JSON.stringify(contains)}` : selector,
      async () => {
        const payload = await client.evaluate(`(() => {
          const el = document.querySelector(${JSON.stringify(selector)});
          if (!el) return null;
          return el.textContent ?? "";
        })()`);
        if (payload == null) return null;
        if (contains && !payload.includes(contains)) return null;
        return payload;
      },
      timeoutMs,
    );
    printText(text);
  });
}

async function cmdQaSnapshot(symbol) {
  await withClient(async (client) => {
    const feed = await tauriInvoke(client, "get_feed_status");
    let detail = null;
    let detailError = null;
    if (symbol) {
      try {
        detail = await tauriInvoke(client, "get_symbol_detail", {
          symbol: symbol.toUpperCase(),
        });
      } catch (error) {
        detailError = String(error.message ?? error);
      }
    }
    const dcfSlot = await client.evaluate(
      `document.querySelector(".price-summary .dcf-slot")?.textContent ?? null`,
    );
    const title = await client.evaluate(`document.title`);
    // Slim detail for agent readability
    let detailSummary = null;
    if (detail && typeof detail === "object") {
      detailSummary = {
        symbol: detail.symbol ?? symbol?.toUpperCase(),
        valuation_status: detail.valuation_status ?? null,
        valuation_unavailable_reason: detail.valuation_unavailable_reason ?? null,
        dcf_value_cents: detail.dcf_value_cents ?? null,
        dcf_analysis: detail.dcf_analysis
          ? {
              model: detail.dcf_analysis.model,
              business_class: detail.dcf_analysis.business_class,
              base_intrinsic_value_cents: detail.dcf_analysis.base_intrinsic_value_cents,
              bear_intrinsic_value_cents: detail.dcf_analysis.bear_intrinsic_value_cents,
              bull_intrinsic_value_cents: detail.dcf_analysis.bull_intrinsic_value_cents,
              quality: detail.dcf_analysis.quality ?? detail.dcf_analysis.model_quality,
            }
          : null,
      };
    }
    printJson({
      title,
      feed: {
        profile_name: feed?.profile_name,
        profile_locked: feed?.profile_locked,
        running: feed?.running,
        symbol_count: feed?.symbol_count ?? feed?.symbols?.length,
      },
      detail: detailSummary,
      detailError,
      dcfSlot,
    });
  });
}

async function main() {
  const raw = process.argv.slice(2);
  if (raw.length === 0 || raw[0] === "help" || raw[0] === "-h" || raw[0] === "--help") {
    process.stdout.write(usage());
    return;
  }

  const command = raw[0];
  const { args, flags } = parseArgs(raw.slice(1));

  switch (command) {
    case "status":
    case "ping":
      await cmdStatus();
      break;
    case "targets":
      await cmdTargets();
      break;
    case "feed":
      await cmdInvoke("get_feed_status");
      break;
    case "invoke":
      await cmdInvoke(args[0], args[1]);
      break;
    case "eval":
      await cmdEval(args.join(" "));
      break;
    case "text":
      await cmdText(args[0]);
      break;
    case "html":
      await cmdHtml(args[0]);
      break;
    case "exists":
      await cmdExists(args[0]);
      break;
    case "count":
      await cmdCount(args[0]);
      break;
    case "click":
      await cmdClick(args[0]);
      break;
    case "click-text":
      await cmdClickText(args[0], args[1]);
      break;
    case "type":
      await cmdType(args[0], args.slice(1).join(" "));
      break;
    case "nav":
      await cmdNav(args[0]);
      break;
    case "open-detail":
      await cmdOpenDetail(args[0]);
      break;
    case "close-detail":
      await cmdCloseDetail();
      break;
    case "agent-snap":
      await cmdAgentSnap();
      break;
    case "dcf-slot":
      await cmdDcfSlot();
      break;
    case "detail-panel":
      await cmdDetailPanel();
      break;
    case "screenshot":
      await cmdScreenshot(args[0]);
      break;
    case "wait":
      await cmdWait(args[0], flags);
      break;
    case "qa-snapshot":
      await cmdQaSnapshot(args[0]);
      break;
    case "self-check":
      await cmdSelfCheck();
      break;
    default:
      process.stderr.write(`Unknown command: ${command}\n\n${usage()}`);
      process.exitCode = 1;
  }
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error}\n`);
  process.exitCode = 1;
});
