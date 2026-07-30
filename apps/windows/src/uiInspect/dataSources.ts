/**
 * Shared catalog of backend / client data sources for UI inspect refs.
 * Paths are repo-relative from the discount_screener root.
 */

import type { UiDataSource } from "./types.ts";

const API = "apps/windows/src/api.ts";
const CMD = "apps/windows/src-tauri/src/commands.rs";
const SRC = "apps/windows/src";
const TAURI = "apps/windows/src-tauri/src";

function tauri(
  id: string,
  command: string,
  clientMethod: string,
  opts: {
    argKeys?: string[];
    listMatchKeys?: string[];
    domain?: string[];
    role?: UiDataSource["role"];
    note?: string;
    probeTemplate?: string;
    impl?: string;
  } = {},
): UiDataSource {
  return {
    id,
    kind: "tauri",
    command,
    client: `${API}#${clientMethod}`,
    impl: opts.impl ?? `${CMD}#${command}`,
    domain: opts.domain,
    argKeys: opts.argKeys,
    listMatchKeys: opts.listMatchKeys,
    role: opts.role ?? "primary",
    note: opts.note,
    probeTemplate:
      opts.probeTemplate ??
      (opts.argKeys && opts.argKeys.length > 0
        ? `api.${clientMethod}(${opts.argKeys.map((k) => `{${k}}`).join(", ")})`
        : `api.${clientMethod}()`),
  };
}

function client(
  id: string,
  modulePath: string,
  opts: {
    role?: UiDataSource["role"];
    note?: string;
  } = {},
): UiDataSource {
  return {
    id,
    kind: "client",
    client: modulePath.startsWith("apps/") ? modulePath : `${SRC}/${modulePath}`,
    role: opts.role ?? "enrich",
    note: opts.note ?? "Pure client transform — no Tauri invoke",
  };
}

// ── Reusable Tauri sources ───────────────────────────────────────────────────

export const DS = {
  opportunities: tauri("opportunities", "get_opportunities", "getOpportunities", {
    role: "primary",
    listMatchKeys: ["symbol"],
    domain: [
      `${TAURI}/engine.rs`,
      `${TAURI}/opportunity_v3.rs`,
      `${TAURI}/price_path.rs`,
      `${TAURI}/regime/mod.rs`,
    ],
    note: "Full board list; when match.symbol is set, take that row only",
  }),

  symbolDetail: tauri("symbol_detail", "get_symbol_detail", "getSymbolDetail", {
    argKeys: ["symbol"],
    role: "primary",
    domain: [`${TAURI}/engine.rs`, `${TAURI}/commands.rs`],
    note: "Per-symbol fundamentals + technicals snapshot",
    probeTemplate: 'api.getSymbolDetail("{symbol}")',
  }),

  marketRegime: tauri("market_regime", "get_market_regime", "getMarketRegime", {
    role: "context",
    domain: [`${TAURI}/regime/mod.rs`, `${TAURI}/regime/composite.rs`],
    note: "Market-wide regime reading (not per-symbol)",
  }),

  candles: tauri("candles", "get_candles", "getCandles", {
    argKeys: ["symbol", "range"],
    role: "primary",
    domain: [`${TAURI}/commands.rs`, `${TAURI}/fetcher.rs`],
    probeTemplate: 'api.getCandles("{symbol}", "{range}")',
  }),

  news: tauri("news", "get_news", "getNews", {
    argKeys: ["symbol"],
    role: "primary",
    domain: [`${TAURI}/news.rs`],
    probeTemplate: 'api.getNews("{symbol}")',
  }),

  quantLens: tauri("quant_lens", "get_quant_lens", "getQuantLens", {
    argKeys: ["symbol"],
    role: "primary",
    domain: [`${TAURI}/quant_lens.rs`],
    probeTemplate: 'api.getQuantLens("{symbol}")',
  }),

  cryptoMetrics: tauri("crypto_metrics", "get_crypto_metrics", "getCryptoMetrics", {
    argKeys: ["symbol"],
    role: "primary",
    domain: [`${TAURI}/crypto_cycle.rs`, `${TAURI}/crypto_md.rs`],
    probeTemplate: 'api.getCryptoMetrics("{symbol}")',
  }),

  indexEstimates: tauri("index_estimates", "get_index_estimates", "getIndexEstimates", {
    role: "primary",
    domain: [`${TAURI}/index_estimates.rs`],
  }),

  feedStatus: tauri("feed_status", "get_feed_status", "getFeedStatus", {
    role: "primary",
    domain: [`${TAURI}/commands.rs`, `${TAURI}/state.rs`],
  }),

  universeProfile: tauri("universe_profile", "get_universe_profile", "getUniverseProfile", {
    role: "context",
  }),

  alerts: tauri("alerts", "get_alerts", "getAlerts", {
    role: "primary",
  }),

  portfolioList: tauri("portfolio_list", "portfolio_list", "portfolioList", {
    role: "primary",
    domain: [`${TAURI}/db.rs`],
  }),

  modelAccuracy: tauri("model_accuracy", "get_model_accuracy", "getModelAccuracy", {
    argKeys: ["horizonDays"],
    role: "enrich",
    probeTemplate: "api.getModelAccuracy({horizonDays})",
  }),

  portfolioRisk: tauri("portfolio_risk", "get_portfolio_risk", "getPortfolioRisk", {
    argKeys: ["symbols"],
    role: "enrich",
    note: "Pass held symbols array from portfolio list",
    probeTemplate: "api.getPortfolioRisk(/* symbols[] */)",
  }),

  quotePrices: tauri("quote_prices", "get_quote_prices", "getQuotePrices", {
    argKeys: ["symbols"],
    role: "enrich",
    probeTemplate: "api.getQuotePrices(/* symbols[] */)",
  }),

  scalpAnalysis: tauri("scalp_analysis", "get_scalp_analysis", "getScalpAnalysis", {
    argKeys: ["product", "rr", "feePct"],
    role: "primary",
    domain: [`${TAURI}/scalping.rs`, `${TAURI}/smc.rs`],
    probeTemplate: 'api.getScalpAnalysis("{product}", {rr}, {feePct})',
  }),

  scalpCandles: tauri("scalp_candles", "get_scalp_candles", "getScalpCandles", {
    argKeys: ["product", "timeframe"],
    role: "enrich",
    domain: [`${TAURI}/scalping.rs`, `${TAURI}/scalp_ws.rs`],
    probeTemplate: 'api.getScalpCandles("{product}", "{timeframe}")',
  }),

  schwabReport: tauri("schwab_report", "get_schwab_report", "getSchwabReport", {
    argKeys: ["symbol"],
    role: "primary",
    domain: [`${TAURI}/schwab.rs`],
    probeTemplate: 'api.getSchwabReport("{symbol}")',
  }),

  schwabStatus: tauri("schwab_status", "schwab_status", "schwabStatus", {
    role: "primary",
    domain: [`${TAURI}/schwab_api.rs`],
  }),

  congressOverview: tauri("congress_overview", "get_congress_overview", "getCongressOverview", {
    argKeys: ["days"],
    role: "primary",
    domain: [`${TAURI}/congress.rs`, `${TAURI}/congress_scoring.rs`],
    probeTemplate: "api.getCongressOverview({days})",
  }),

  congressTradesForSymbol: tauri(
    "congress_trades_symbol",
    "get_congress_trades_for_symbol",
    "getCongressTradesForSymbol",
    {
      argKeys: ["symbol"],
      role: "primary",
      domain: [`${TAURI}/congress.rs`],
      probeTemplate: 'api.getCongressTradesForSymbol("{symbol}")',
    },
  ),

  topPoliticians: tauri(
    "top_politicians",
    "get_top_politicians_ranked",
    "getTopPoliticiansRanked",
    {
      argKeys: ["sortKey"],
      role: "enrich",
      domain: [`${TAURI}/congress.rs`],
      probeTemplate: 'api.getTopPoliticiansRanked("{sortKey}")',
    },
  ),

  symbolHistory: tauri("symbol_history", "get_symbol_history", "getSymbolHistory", {
    argKeys: ["symbol", "days"],
    role: "primary",
    domain: [`${TAURI}/db.rs`],
    probeTemplate: 'api.getSymbolHistory("{symbol}", {days})',
  }),

  backtest: tauri("backtest", "get_backtest", "getBacktest", {
    argKeys: ["decision", "daysAgo"],
    role: "primary",
    domain: [`${TAURI}/db.rs`],
    probeTemplate: 'api.getBacktest("{decision}", {daysAgo})',
  }),

  historyStatus: tauri("history_status", "get_history_status", "getHistoryStatus", {
    role: "context",
  }),

  priceProvenance: tauri("price_provenance", "get_price_provenance", "getPriceProvenance", {
    argKeys: ["symbol"],
    role: "enrich",
    probeTemplate: 'api.getPriceProvenance("{symbol}")',
  }),

  searchTickers: tauri("search_tickers", "search_tickers", "searchTickers", {
    argKeys: ["query"],
    role: "primary",
    domain: [`${TAURI}/ticker_search.rs`],
    probeTemplate: 'api.searchTickers("{query}")',
  }),

  emailConfig: tauri("email_config", "email_config_get", "emailConfigGet", {
    role: "primary",
    domain: [`${TAURI}/email.rs`],
  }),

  scoringModel: tauri("scoring_model", "get_scoring_model", "getScoringModel", {
    role: "context",
  }),

  // ── Pure client transforms ─────────────────────────────────────────────────

  conditionalPlan: client("conditionalPlan", "conditionalPlan.ts#buildConditionalPlan", {
    note: "Pure transform of OpportunityRow → stance/headline; no extra invoke",
  }),

  dashboardV2Ranking: client(
    "dashboardV2Ranking",
    "dashboardV2Ranking.ts#rankDashboardV2Sections",
    {
      note: "Section membership + actionable primary board filter",
    },
  ),

  technicalVerdict: client("technicalVerdict", "technicalVerdict.ts#verdictFromTechnicalScore", {
    note: "Canonical technical_score → verdict label (matches backend score)",
  }),

  scoringPresentation: client(
    "scoringPresentation",
    "scoringPresentation.ts#getScoringPresentation",
    {
      note: "Presentation labels/copy for scores and setup tokens",
    },
  ),

  regimePresentation: client(
    "regimePresentation",
    "regimePresentation.ts#createRegimePresentation",
    {
      note: "Regime bucket presentation from row + market regime",
    },
  ),

  marketContextNarrative: client(
    "marketContextNarrative",
    "marketContextNarrative.ts#buildMarketContextNarrative",
    {
      note: "Evidence-first market context copy",
    },
  ),

  regimeRadar: client("regimeRadar", "regimeRadar.ts", {
    note: "Radar geometry / pillar layout from MarketRegime",
  }),

  regimeSideLens: client("regimeSideLens", "regimeSideLens.ts", {
    note: "Short/long lens for regime banner copy under scoring model",
  }),

  portfolioRegimeEval: client(
    "portfolioRegimeEval",
    "portfolioRegimeEval.ts#evaluatePortfolioAgainstRegime",
    {
      note: "Portfolio actions/sizing/warnings from MarketRegime + holdings",
    },
  ),
} as const satisfies Record<string, UiDataSource>;

/** All known Tauri command strings in this catalog (for tests). */
export function allCatalogTauriCommands(): string[] {
  return Object.values(DS)
    .filter((s) => s.kind === "tauri" && s.command)
    .map((s) => s.command!);
}
