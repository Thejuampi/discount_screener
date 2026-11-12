import { browser } from "@wdio/globals";
import type { PortfolioPosition } from "../../src/api";

type ForecastState =
  | "ready"
  | "insufficient_coverage"
  | "empty"
  | "unloaded"
  | "missing_key"
  | "invalid_key"
  | "quota_exhausted"
  | "rate_limited"
  | "provider_unavailable";

export interface E2eBackend {
  feedStatus: WebdriverIO.Mock;
  opportunities: WebdriverIO.Mock;
  androidLongV3: WebdriverIO.Mock;
  setScoringModel: WebdriverIO.Mock;
  portfolioList: WebdriverIO.Mock;
  getWatchlist: WebdriverIO.Mock;
  setWatchlistMember: WebdriverIO.Mock;
  getTickerNote: WebdriverIO.Mock;
  setTickerNote: WebdriverIO.Mock;
  quotePrices: WebdriverIO.Mock;
  savedUniverseRows: WebdriverIO.Mock;
  getTrackedFeed: WebdriverIO.Mock;
  setTrackedFeedMember: WebdriverIO.Mock;
  requestFeedRefresh: WebdriverIO.Mock;
  searchTickers: WebdriverIO.Mock;
  symbolDetail: WebdriverIO.Mock;
  ensureSymbolLoaded: WebdriverIO.Mock;
  refreshSymbolDetail: WebdriverIO.Mock;
  candles: WebdriverIO.Mock;
  loadCandles: WebdriverIO.Mock;
  news: WebdriverIO.Mock;
  priceProvenance: WebdriverIO.Mock;
  analystForecasts: WebdriverIO.Mock;
  loadForecasts: WebdriverIO.Mock;
  saveKey: WebdriverIO.Mock;
  testKey: WebdriverIO.Mock;
  deleteKey: WebdriverIO.Mock;
}

export const initialFeedStatus = {
  running: true,
  symbols_loaded: 1,
  symbols_total: 1,
  last_error: null,
  system_error: null,
  profile_name: "sp500",
  profile_locked: false,
  stale_snapshots: false,
  refresh: {
    phase: "idle",
    trigger: null,
    processed: 0,
    total: 0,
    quote_failures: 0,
    chart_failures: 0,
    rate_limited: 0,
  },
};

const quota = {
  provider_month: "2026-07",
  attempts: 25,
  limit: 50,
  remaining: 25,
  warning: true,
  exhausted: false,
  estimated: true,
  resets_at_epoch: 1_775_001_600,
  retry_after_epoch: null as number | null,
};

const observations = [
  {
    symbol: "AAPL",
    analyst: "Alice Adams",
    firm: "North Star Research",
    issued_at_epoch: 1_767_225_600,
    horizon_epoch: 1_798_761_600,
    horizon_label: "Assumed 12-month horizon",
    rating: "Buy",
    target_cents: 22_000,
    previous_target_cents: 21_000,
    price_when_posted_cents: 18_500,
    source: "TipRanks",
    identity: "alice adams",
    stars_hundredths: 450,
    rank: 42,
    weight_hundredths: 123,
  },
  {
    symbol: "AAPL",
    analyst: "Bob Brown",
    firm: "Granite Capital",
    issued_at_epoch: 1_768_953_600,
    horizon_epoch: 1_800_489_600,
    horizon_label: "Provider horizon",
    rating: "Outperform",
    target_cents: 24_000,
    previous_target_cents: null,
    price_when_posted_cents: 19_000,
    source: "TipRanks",
    identity: "bob brown",
    stars_hundredths: 400,
    rank: 88,
    weight_hundredths: 115,
  },
  {
    symbol: "AAPL",
    analyst: null,
    firm: "Harbor Securities",
    issued_at_epoch: 1_770_681_600,
    horizon_epoch: 1_802_217_600,
    horizon_label: "Assumed 12-month horizon",
    rating: "Hold",
    target_cents: 20_000,
    previous_target_cents: 19_500,
    price_when_posted_cents: 19_250,
    source: "TipRanks",
    identity: "harbor securities",
    stars_hundredths: 300,
    rank: 210,
    weight_hundredths: 100,
  },
];

function forecastPanel(state: ForecastState) {
  const hasRows = state === "ready" || state === "insufficient_coverage";
  return {
    symbol: "AAPL",
    state,
    state_message: state,
    observations: hasRows
      ? state === "insufficient_coverage"
        ? observations.slice(0, 2)
        : observations
      : [],
    histogram: hasRows
      ? [
          { low_cents: 20_000, high_cents: 21_333, count: 1 },
          { low_cents: 21_334, high_cents: 22_667, count: 1 },
          { low_cents: 22_668, high_cents: 24_000, count: state === "ready" ? 1 : 0 },
        ]
      : [],
    statistics: hasRows
      ? {
          minimum_cents: 20_000,
          maximum_cents: state === "ready" ? 24_000 : 22_000,
          simple_mean_cents: state === "ready" ? 22_000 : 21_000,
          weighted_mean_cents: state === "ready" ? 22_100 : null,
          weighting_label:
            state === "ready"
              ? "TipRanks stars weight: clamp(1 + 0.15×(stars−3), 0.70, 1.30)"
              : "Unavailable",
        }
      : null,
    identity_count: state === "ready" ? 3 : state === "insufficient_coverage" ? 2 : 0,
    usable_weighted_consensus: state === "ready",
    price_history: hasRows
      ? [
          { epoch_seconds: 1_751_328_000, close_cents: 17_500 },
          { epoch_seconds: 1_759_104_000, close_cents: 18_400 },
          { epoch_seconds: 1_766_880_000, close_cents: 19_100 },
        ]
      : [],
    fetched_at_epoch: hasRows ? 1_770_681_600 : null,
    latest_observation_epoch: hasRows ? 1_770_681_600 : null,
    cache_freshness: hasRows ? "fresh" : null,
    observation_freshness: hasRows ? "current" : "empty",
    from_cache: true,
    horizon_disclosure: "Targets without an explicit date use an assumed 12-month horizon.",
    provider_label: "Data by TipRanks",
    quota:
      state === "quota_exhausted"
        ? { ...quota, attempts: 50, remaining: 0, exhausted: true }
        : quota,
    action:
      state === "unloaded"
        ? {
            kind: "load",
            enabled: true,
            call_cost: 1,
            remaining_after: 24,
            label: "Load TipRanks analyst targets",
            confirmation_message: "Uses 1 TipRanks call. Remaining after: 24/50.",
          }
        : {
            kind: "none",
            enabled: false,
            call_cost: 0,
            remaining_after: quota.remaining,
            label: "",
            confirmation_message: null,
          },
    error_banner: null,
  };
}

export const opportunity = {
  symbol: "AAPL",
  company_name: "Apple Inc.",
  market_price_cents: 19_500,
  intrinsic_value_cents: 22_000,
  gap_bps: 1_282,
  qualification: "Qualified",
  confidence: "High",
  signal_status: "Supportive",
  analyst_opinion_count: 30,
  recommendation_mean_hundredths: 180,
  sector_name: "Technology",
  fundamentals_score: 55,
  technical_score: 30,
  forecast_score: 60,
  regime_score: 20,
  composite_score: 48,
  composite_score_base: 50,
  decision: "Act",
  fundamentals_signals: [],
  technical_signals: [],
  forecast_signals: [],
  regime_signals: [],
  dcf_value_cents: 21_500,
  insider_net_shares_90d: null,
  insider_buy_count: null,
  insider_sell_count: null,
  asset_type: "stock",
  setup_score: 48,
  setup_label: "Buy",
  daily_change_bps: 125,
  atr_cents: 350,
  next_earnings_epoch: null,
  spark: [18_900, 19_100, 19_500],
};

export const detail = {
  symbol: "AAPL",
  detail_source: "session",
  saved_at_epoch_seconds: null,
  asset_type: "stock",
  company_name: "Apple Inc.",
  market_price_cents: 19_500,
  intrinsic_value_cents: 22_000,
  gap_bps: 1_282,
  qualification: "Qualified",
  confidence: "High",
  signal_status: "Supportive",
  signal_age_seconds: 3_600,
  low_fair_value_cents: 20_000,
  high_fair_value_cents: 24_000,
  analyst_opinion_count: 30,
  recommendation_mean_hundredths: 180,
  strong_buy_count: 10,
  buy_count: 12,
  hold_count: 7,
  sell_count: 1,
  strong_sell_count: 0,
  fundamentals: {
    sector_name: "Technology",
    industry_name: "Consumer Electronics",
    market_cap_dollars: 3_000_000_000_000,
    trailing_pe_hundredths: 2_900,
    forward_pe_hundredths: 2_700,
    return_on_equity_bps: 15_000,
    debt_to_equity_hundredths: 180,
    free_cash_flow_dollars: 100_000_000_000,
    operating_cash_flow_dollars: 120_000_000_000,
    beta_millis: 1_100,
    trailing_eps_cents: 670,
    earnings_growth_bps: 850,
    total_debt_dollars: 90_000_000_000,
    total_cash_dollars: 65_000_000_000,
    ebitda_dollars: 130_000_000_000,
  },
  chart_summary: null,
  weekly_summary: null,
  hourly_summary: null,
  monthly_summary: null,
  technical_breakdown: null,
  dcf_value_cents: 21_500,
  dcf_analysis: null,
  insider_net_shares_90d: null,
  insider_buy_count: null,
  insider_sell_count: null,
  next_earnings_epoch: null,
  chart_patterns: [],
  fib: null,
};

const settingsStatus = {
  configured: true,
  quota,
};

const marketRegime = {
  regime: "Neutral",
  primary_regime: "Range",
  environment_band: "Neutral",
  action_stance: "SelectiveBuy",
  suggested_exposure_pct: 50,
  cash_buffer_pct: 50,
  new_risk_multiplier_bps: 10_000,
  add_bias: 0,
  prefer_quality: true,
  global_confidence_bps: 7_500,
  environment_score: 0,
  sentiment_score: 0,
  quality_score: 0,
  pillars: [],
  vix: null,
  vix_percentile_1y: null,
  vix_term_ratio: null,
  vix_state: "unknown",
  cnn_fear_greed: null,
  cnn_fear_greed_label: null,
  cnn_fear_greed_prev_close: null,
  breadth_above_ma200_pct: null,
  breadth_above_ma50_pct: null,
  breadth_sample: 0,
  spy_above_ma200: null,
  spy_price_cents: null,
  spy_ma200_cents: null,
  spy_drawdown_from_ath_pct: null,
  credit_score: null,
  leadership_score: null,
  avg_corr_milli: null,
  thesis_es: "Mercado neutral.",
  thesis_en: "Neutral market.",
  reading_es: "Lectura neutral.",
  reading_en: "Neutral reading.",
  action_bullets_es: [],
  action_bullets_en: [],
  notes_es: [],
  notes_en: [],
  warnings: [],
  as_of_epoch: 1_770_681_600,
  version: 2,
};

async function mockResolved(command: string, value: unknown): Promise<WebdriverIO.Mock> {
  const mock = await browser.tauri.mock(command);
  await mock.mockResolvedValue(value);
  return mock;
}

export async function startApp(
  state: ForecastState = "ready",
  view: "screener" | "settings" | "estimates" | "advisor" = "screener",
  settingsStatusUnavailable = false,
  overrides: {
    opportunities?: readonly object[];
    androidLongV3?: object;
    savedUniverseRows?: readonly object[];
    detail?: object | null;
    candles?: object;
    portfolioList?: readonly PortfolioPosition[] | null;
    quotePrices?: Readonly<Record<string, number>>;
    watchlist?: readonly string[] | null;
    tickerNotes?: Readonly<Record<string, string>> | null;
    trackedFeed?: { profile: string; symbols?: readonly string[]; editable?: boolean };
  } = {},
): Promise<E2eBackend> {
  const tracked = overrides.trackedFeed;
  const rows = overrides.opportunities ?? [opportunity];
  const profile = tracked?.profile ?? "sp500";
  const editable = tracked?.editable ?? true;
  const trackedSymbols = await browser.execute((initialView, initialWatchlist, initialTickerNotes, trackedFeed) => {
    localStorage.clear();
    localStorage.setItem("ds_lang", "en");
    localStorage.setItem("ds_view_mode", initialView);
    localStorage.setItem("ds_dashboard_edition", "legacy");
    if (initialWatchlist != null) {
      sessionStorage.setItem("e2e_watchlist_backend", JSON.stringify(initialWatchlist));
    }
    if (initialTickerNotes != null) {
      sessionStorage.setItem("e2e_ticker_notes_backend", JSON.stringify(initialTickerNotes));
    }
    if (trackedFeed === null) return null;
    const key = `e2e_tracked_feed_members:${trackedFeed.profile}`;
    if (trackedFeed.symbols !== null) {
      sessionStorage.setItem(key, JSON.stringify(trackedFeed.symbols));
    }
    sessionStorage.setItem("e2e_tracked_feed_fixture", JSON.stringify(trackedFeed));
    return JSON.parse(sessionStorage.getItem(key) ?? "[]") as string[];
  }, view, overrides.watchlist ?? null, overrides.tickerNotes ?? null, tracked ? {
    profile,
    editable,
    symbols: tracked.symbols ?? null,
    rows: rows as readonly { symbol: string }[],
  } : null);

  await mockResolved("get_app_version", "0.1.0");
  await mockResolved("get_autostart_enabled", false);
  await mockResolved("list_universe_profiles", [
    { name: profile, description: profile === "qa" ? "QA" : "S&P 500", symbol_count: trackedSymbols?.length ?? 1 },
  ]);
  await mockResolved("get_market_regime", marketRegime);
  const portfolioList = await browser.tauri.mock("portfolio_list");
  if (overrides.portfolioList === null) {
    await portfolioList.mockRejectedValue("Saved positions unavailable");
  } else {
    await portfolioList.mockResolvedValue(overrides.portfolioList ?? []);
  }
  // The simulated database outlives the page. startApp clears app localStorage.
  const getWatchlist = await browser.tauri.mock("get_watchlist");
  if (overrides.watchlist === null) {
    await getWatchlist.mockRejectedValue("Watchlist could not be read");
  } else {
    await getWatchlist.mockImplementation(() =>
      JSON.parse(sessionStorage.getItem("e2e_watchlist_backend") ?? "[]"));
  }
  const setWatchlistMember = await browser.tauri.mock("set_watchlist_member");
  await setWatchlistMember.mockImplementation(({ symbol, included }) => {
    const members = new Set<string>(JSON.parse(sessionStorage.getItem("e2e_watchlist_backend") ?? "[]"));
    if (included) members.add(symbol);
    else members.delete(symbol);
    sessionStorage.setItem("e2e_watchlist_backend", JSON.stringify([...members].sort()));
    return null;
  });
  const getTickerNote = await browser.tauri.mock("get_ticker_note");
  if (overrides.tickerNotes === null) {
    await getTickerNote.mockRejectedValue("Ticker note could not be read");
  } else {
    await getTickerNote.mockImplementation(({ symbol }) => {
      const notes = new Map<string, string>(Object.entries(
        JSON.parse(sessionStorage.getItem("e2e_ticker_notes_backend") ?? "{}"),
      ));
      return notes.get(symbol) ?? null;
    });
  }
  const setTickerNote = await browser.tauri.mock("set_ticker_note");
  await setTickerNote.mockImplementation(({ symbol, note }) => {
    const notes = new Map<string, string>(Object.entries(
      JSON.parse(sessionStorage.getItem("e2e_ticker_notes_backend") ?? "{}"),
    ));
    if (note === null) notes.delete(symbol);
    else notes.set(symbol, note);
    sessionStorage.setItem("e2e_ticker_notes_backend", JSON.stringify(Object.fromEntries(notes)));
    return null;
  });
  const quotePrices = await mockResolved("get_quote_prices", overrides.quotePrices ?? {});
  await mockResolved("get_portfolio_risk", { per_symbol: [], correlation: [], high_corr_pairs: [], lookback_days: 120 });
  await mockResolved("get_model_accuracy", []);
  await mockResolved("journal_list", []);
  await mockResolved("email_config_get", {
    smtp_host: null,
    smtp_port: null,
    username: null,
    from_email: null,
    to_email: null,
    has_password: false,
    enabled: false,
    daily_digest: true,
    digest_hour: 8,
    instant_alerts: false,
    last_digest_date: null,
  });
  await mockResolved("get_scoring_model", "aggressive_v3");
  const setScoringModel = await browser.tauri.mock("set_scoring_model");
  await setScoringModel.mockImplementation(({ model }) => model);
  await mockResolved("set_regime_scoring_enabled", true);
  const universe = {
    name: profile,
    symbols_total: trackedSymbols?.length ?? 1,
    symbols_loaded: trackedSymbols?.length ?? 1,
    profile_locked: !editable,
    stale_snapshots: false,
  };
  await mockResolved("set_universe_profile", universe);
  await mockResolved("get_universe_profile", universe);
  await mockResolved("start_feed", null);
  const getTrackedFeed = await mockResolved("get_tracked_feed", { profile, symbols: ["AAPL"], editable });
  const setTrackedFeedMember = await browser.tauri.mock("set_tracked_feed_member");
  const opportunities = await browser.tauri.mock("get_opportunities");
  const androidLongV3 = await mockResolved("get_android_long_v3", overrides.androidLongV3 ?? {
    profile,
    rows: [],
    incomplete: [],
    evaluated: 0,
    eligible: 0,
  });
  const savedUniverseRows = await mockResolved("get_saved_universe_rows", overrides.savedUniverseRows ?? []);
  const feedStatus = await mockResolved("get_feed_status", {
    ...initialFeedStatus,
    symbols_loaded: rows.length,
  });
  if (tracked) {
    await getTrackedFeed.mockImplementation(({ profile }) => {
      const fixture = JSON.parse(sessionStorage.getItem("e2e_tracked_feed_fixture")!);
      if (profile !== fixture.profile) throw new Error("Unexpected profile");
      const symbols = JSON.parse(sessionStorage.getItem(`e2e_tracked_feed_members:${profile}`) ?? "[]");
      return { profile, symbols, editable: fixture.editable };
    });
    await setTrackedFeedMember.mockImplementation(({ profile, symbol, included }) => {
      const fixture = JSON.parse(sessionStorage.getItem("e2e_tracked_feed_fixture")!);
      if (profile !== fixture.profile || !fixture.editable) throw new Error("Feed edit refused");
      const key = `e2e_tracked_feed_members:${profile}`;
      const symbols = new Set<string>(JSON.parse(sessionStorage.getItem(key) ?? "[]"));
      if (included) symbols.add(symbol);
      else symbols.delete(symbol);
      sessionStorage.setItem(key, JSON.stringify([...symbols]));
      return { profile, symbols: [...symbols], editable: true };
    });
    await opportunities.mockImplementation(() => {
      const fixture = JSON.parse(sessionStorage.getItem("e2e_tracked_feed_fixture")!);
      const symbols = JSON.parse(sessionStorage.getItem(`e2e_tracked_feed_members:${fixture.profile}`) ?? "[]");
      return fixture.rows.filter((row: { symbol: string }) => symbols.includes(row.symbol));
    });
    await feedStatus.mockImplementation(() => {
      const fixture = JSON.parse(sessionStorage.getItem("e2e_tracked_feed_fixture")!);
      const symbols = JSON.parse(sessionStorage.getItem(`e2e_tracked_feed_members:${fixture.profile}`) ?? "[]");
      const loaded = fixture.rows.filter((row: { symbol: string }) => symbols.includes(row.symbol)).length;
      return {
        running: symbols.length > 0,
        symbols_loaded: loaded,
        symbols_total: symbols.length,
        last_error: null,
        system_error: null,
        profile_name: fixture.profile,
        profile_locked: !fixture.editable,
        stale_snapshots: false,
      };
    });
  } else {
    await opportunities.mockResolvedValue(rows);
  }
  const requestFeedRefresh = await mockResolved("request_feed_refresh", null);
  const searchTickers = await mockResolved("search_tickers", []);

  const symbolDetail = await mockResolved("get_symbol_detail", overrides.detail === undefined ? detail : overrides.detail);
  const ensureSymbolLoaded = await mockResolved("ensure_symbol_loaded", "AAPL");
  await mockResolved("refresh_symbol", "AAPL");
  const refreshSymbolDetail = await mockResolved("refresh_symbol_detail", {
    quote_updated: true,
    chart_updated: true,
    issues: [],
  });
  const candles = await mockResolved("get_candles", overrides.candles ?? null);
  const loadCandles = await mockResolved("load_symbol_candles", []);
  await mockResolved("get_symbol_history", []);
  const news = await mockResolved("get_news", {
    items: [],
    aggregate_sentiment: 0,
    positive_count: 0,
    negative_count: 0,
    neutral_count: 0,
    fetched_at: 1_770_681_600,
  });
  await mockResolved("get_schwab_report", null);
  await mockResolved("get_congress_trades_for_symbol", []);
  await mockResolved("get_quant_lens", {
    symbol: "AAPL",
    primary_status: "Moderate",
    sections: [],
    model_version: 4,
  });
  const priceProvenance = await mockResolved("get_price_provenance", {
    symbol: "AAPL",
    schwab_cents: null,
    yahoo_cents: 19_500,
    stooq_cents: null,
    consensus_cents: 19_500,
    spread_bps: 0,
    agree: true,
    sources_ok: 1,
  });

  const analystForecasts = await mockResolved(
    "get_analyst_forecasts",
    forecastPanel(state),
  );
  const loadForecasts = await mockResolved(
    "load_analyst_forecasts",
    forecastPanel(state === "unloaded" ? "ready" : state),
  );
  if (settingsStatusUnavailable) {
    const statusMock = await browser.tauri.mock("tipranks_settings_status");
    await statusMock.mockRejectedValue("TipRanks status unavailable");
  } else {
    await mockResolved("tipranks_settings_status", settingsStatus);
  }
  const saveKey = await mockResolved("tipranks_save_key", settingsStatus);
  const deleteKey = await mockResolved("tipranks_delete_key", {
    configured: false,
    quota,
  });
  const testKey = await mockResolved("tipranks_test_key", forecastPanel("ready"));
  await mockResolved("count_schwab_reports", 0);
  await mockResolved("schwab_status", {
    configured: false,
    connected: false,
    needs_reauth: false,
    access_valid_until: null,
    refresh_valid_until: null,
    callback: null,
  });

  await browser.execute(async () => {
    if (window.__startVantageE2e == null) {
      throw new Error("E2E app starter is unavailable");
    }
    await window.__startVantageE2e();
  });

  return {
    feedStatus, opportunities, androidLongV3, setScoringModel, portfolioList, getWatchlist, setWatchlistMember,
    getTickerNote, setTickerNote, quotePrices,
    savedUniverseRows, getTrackedFeed, setTrackedFeedMember, requestFeedRefresh, searchTickers,
    symbolDetail, ensureSymbolLoaded, refreshSymbolDetail, candles, loadCandles, news, priceProvenance,
    analystForecasts, loadForecasts, saveKey, testKey, deleteKey,
  };
}
