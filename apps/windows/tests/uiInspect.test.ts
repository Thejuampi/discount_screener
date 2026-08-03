import assert from "node:assert/strict";
import test from "node:test";
import { buildUiRefPayload } from "../src/uiInspect/buildPayload.ts";
import { isBlockedKey, sanitizeSnapshot } from "../src/uiInspect/sanitize.ts";
import { allUiSources, getUiSource, UI } from "../src/uiInspect/sources.ts";
import { allCatalogTauriCommands, DS } from "../src/uiInspect/dataSources.ts";
import { resolveDataSources } from "../src/uiInspect/resolveDataSources.ts";
import { copyUiRef } from "../src/uiInspect/copy.ts";
import {
  clearUiRegistry,
  registerUiNode,
  registrySize,
  lookupUiNode,
} from "../src/uiInspect/registry.ts";

/** Commands actually invoked from api.ts (keep in sync when adding DS entries). */
const KNOWN_API_COMMANDS = new Set([
  "get_opportunities",
  "get_regime_scoring_enabled",
  "set_regime_scoring_enabled",
  "get_symbol_detail",
  "get_candles",
  "get_alerts",
  "refresh_symbol",
  "search_tickers",
  "resolve_ticker_search_submit",
  "ensure_symbol_loaded",
  "get_scoring_model",
  "set_scoring_model",
  "get_index_estimates",
  "get_quant_lens",
  "get_valuation_dossier",
  "start_feed",
  "get_feed_status",
  "list_universe_profiles",
  "get_universe_profile",
  "set_universe_profile",
  "get_symbol_history",
  "get_backtest",
  "get_history_status",
  "get_autostart_enabled",
  "set_autostart_enabled",
  "quit_app",
  "get_news",
  "import_schwab_pdf",
  "get_schwab_report",
  "count_schwab_reports",
  "delete_schwab_report",
  "get_congress_overview",
  "get_congress_trades_for_symbol",
  "sync_congress_house",
  "get_congress_sync_progress",
  "compute_congress_metrics",
  "get_top_politicians_ranked",
  "get_politician_detail",
  "get_crypto_metrics",
  "portfolio_list",
  "portfolio_add",
  "portfolio_update",
  "portfolio_delete",
  "portfolio_import",
  "get_quote_prices",
  "get_model_accuracy",
  "get_portfolio_risk",
  "get_market_regime",
  "get_price_provenance",
  "schwab_status",
  "schwab_set_credentials",
  "schwab_auth_url",
  "schwab_complete_auth",
  "schwab_disconnect",
  "email_config_get",
  "email_config_set",
  "email_send",
  "email_mark_digest_sent",
  "get_scalp_candles",
  "get_scalp_analysis",
  "scalp_ws_subscribe",
  "journal_list",
  "journal_add",
  "journal_close",
  "journal_delete",
]);

/** UI chrome / pure prefs that intentionally omit dataSources. */
const UI_CHROME_WITHOUT_DATA = new Set(["dashboard.v2.editionToggle"]);

test("all UI source ids are unique and well-formed", () => {
  const ids = allUiSources().map((s) => s.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const s of allUiSources()) {
    assert.match(s.id, /^[a-z0-9]+(\.[a-z0-9]+)+$/i);
    assert.ok(s.component.startsWith("apps/windows/src/"));
    assert.ok(s.region.length > 0);
    assert.ok(s.label.length > 0);
  }
  assert.ok(ids.length >= 30, `expected full catalog, got ${ids.length}`);
});

test("getUiSource finds plan card", () => {
  const s = getUiSource("dashboard.v2.planCard");
  assert.ok(s);
  assert.equal(s!.region, "PlanCard");
  assert.ok(s!.related?.some((r) => r.includes("conditionalPlan")));
});

test("data-backed sources declare dataSources; chrome may omit", () => {
  for (const s of allUiSources()) {
    if (UI_CHROME_WITHOUT_DATA.has(s.id)) {
      assert.equal(s.dataSources, undefined, `${s.id} should omit dataSources`);
      continue;
    }
    assert.ok(
      s.dataSources && s.dataSources.length > 0,
      `${s.id} should list at least one data source`,
    );
  }
});

test("catalog tauri commands exist in api.ts invoke set", () => {
  for (const cmd of allCatalogTauriCommands()) {
    assert.ok(KNOWN_API_COMMANDS.has(cmd), `unknown tauri command in catalog: ${cmd}`);
  }
});

test("sanitize strips secrets and truncates series", () => {
  const out = sanitizeSnapshot({
    symbol: "AAPL",
    access_token: "super-secret",
    smtpPassword: "x",
    email: "a@b.com",
    display_name: "Juan",
    spark: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13],
    longText: "x".repeat(500),
    nested: { apiKey: "nope", score: 12 },
  });
  assert.equal(out.symbol, "AAPL");
  assert.equal(out.access_token, undefined);
  assert.equal(out.smtpPassword, undefined);
  assert.equal(out.email, undefined);
  assert.equal(out.display_name, undefined);
  assert.equal(out.spark, undefined);
  assert.equal(out.sparkLen, 13);
  assert.ok(typeof out.longText === "string" && (out.longText as string).endsWith("…"));
  const nested = out.nested as Record<string, unknown>;
  assert.equal(nested.apiKey, undefined);
  assert.equal(nested.score, 12);
});

test("isBlockedKey covers common secret names", () => {
  assert.equal(isBlockedKey("access_token"), true);
  assert.equal(isBlockedKey("compositeScore"), false);
});

test("buildUiRefPayload has required sections and related paths", () => {
  const text = buildUiRefPayload({
    def: UI.dashboardV2PlanCard,
    snapshot: {
      symbol: "AAPL",
      stance: "ActNow",
      technicalScore: -52,
      technicalVerdict: "Strong Bearish",
    },
    appContext: {
      view: "dashboard",
      scoringModel: "aggressive_v3",
      regimeScoring: true,
      dashboardEdition: "v2",
      profile: "swing",
    },
    visibleText: "AAPL · Act now · Σ 71",
  });
  assert.match(text, /```ds-ui-ref v1/);
  assert.match(text, /## What/);
  assert.match(text, /id: dashboard\.v2\.planCard/);
  assert.match(text, /## Where \(construction\)/);
  assert.match(text, /region: PlanCard/);
  assert.match(text, /conditionalPlan\.ts#buildConditionalPlan/);
  assert.match(text, /## Data sources/);
  assert.match(text, /command: get_opportunities/);
  assert.match(text, /kind: tauri/);
  assert.match(text, /kind: client/);
  assert.match(text, /id: conditionalPlan/);
  assert.match(text, /probe: api\.getOpportunities\(\)/);
  assert.match(text, /## Runtime \(safe snapshot\)/);
  assert.match(text, /stance: ActNow/);
  assert.match(text, /technicalScore: -52/);
  assert.match(text, /scoringModel: aggressive_v3/);
  assert.match(text, /## Agent hints/);
  assert.match(text, /```\s*$/);
});

test("resolveDataSources fills symbol args from snapshot", () => {
  const resolved = resolveDataSources(
    { ...UI.detailNews, dataSources: [DS.news] },
    { symbol: "TEL" },
    {},
  );
  assert.equal(resolved.length, 1);
  assert.equal(resolved[0].args.symbol, "TEL");
  assert.equal(resolved[0].probe, 'api.getNews("TEL")');
});

test("list endpoints emit match + probe filter when symbol is in snapshot", () => {
  const resolved = resolveDataSources(UI.screenerRow, { symbol: "MA" }, {});
  const opps = resolved.find((r) => r.source.id === "opportunities");
  assert.ok(opps);
  assert.deepEqual(opps!.args, {});
  assert.deepEqual(opps!.match, { symbol: "MA" });
  assert.equal(opps!.probe, 'api.getOpportunities() /* find symbol==="MA" */');
});

test("screener row payload includes match, get_symbol_detail, and decision hints", () => {
  const text = buildUiRefPayload({
    def: UI.screenerRow,
    snapshot: {
      symbol: "MA",
      decision: "Act",
      setupLabel: "Buy",
      compositeScore: 38,
      technicalScore: 2,
    },
    appContext: { view: "screener", scoringModel: "aggressive_v3" },
  });
  assert.match(text, /command: get_opportunities/);
  assert.match(text, /match: \{"symbol":"MA"\}/);
  assert.match(text, /probe: api\.getOpportunities\(\) \/\* find symbol==="MA" \*\//);
  assert.match(text, /command: get_symbol_detail/);
  assert.match(text, /args: \{"symbol":"MA"\}/);
  assert.match(text, /decision_state_v3|opportunity_v3/);
});

test("symbol-scoped detail payload includes filled get_symbol_detail args", () => {
  const text = buildUiRefPayload({
    def: UI.detailAnalysisSummary,
    snapshot: { symbol: "TEL", technicalScore: 19 },
    appContext: { view: "screener", scoringModel: "aggressive_v3" },
  });
  assert.match(text, /## Data sources/);
  assert.match(text, /command: get_symbol_detail/);
  assert.match(text, /args: \{"symbol":"TEL"\}/);
  assert.match(text, /probe: api\.getSymbolDetail\("TEL"\)/);
  assert.match(text, /command: get_opportunities/);
  assert.match(text, /kind: client/);
});

test("registry register/lookup/unregister", () => {
  clearUiRegistry();
  const unreg = registerUiNode({
    instanceId: "test-1",
    def: UI.screenerRow,
    getSnapshot: () => ({ symbol: "MSFT" }),
  });
  assert.equal(registrySize(), 1);
  assert.equal(lookupUiNode("test-1")?.def.id, "screener.row");
  unreg();
  assert.equal(registrySize(), 0);
  clearUiRegistry();
});

test("copyUiRef writes payload via injected writer", async () => {
  let written = "";
  const ok = await copyUiRef(
    {
      instanceId: "x",
      def: UI.detailAnalysisSummary,
      getSnapshot: () => ({ symbol: "TEL", technicalScore: 19 }),
      getVisibleText: () => "TEL summary",
    },
    { view: "screener", scoringModel: "aggressive_v3" },
    {
      writeText: async (t) => {
        written = t;
      },
      successMsg: "ok",
      errorMsg: "fail",
    },
  );
  assert.equal(ok, true);
  assert.match(written, /detail\.analysisSummary/);
  assert.match(written, /technicalScore: 19/);
  assert.match(written, /command: get_symbol_detail/);
});
