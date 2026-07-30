import { browser } from "@wdio/globals";

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
  analystForecasts: WebdriverIO.Mock;
  loadForecasts: WebdriverIO.Mock;
  saveKey: WebdriverIO.Mock;
  testKey: WebdriverIO.Mock;
  deleteKey: WebdriverIO.Mock;
}

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

const opportunity = {
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

const detail = {
  symbol: "AAPL",
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
  view: "screener" | "settings" = "screener",
  settingsStatusUnavailable = false,
): Promise<E2eBackend> {
  await browser.execute((initialView) => {
    localStorage.clear();
    localStorage.setItem("ds_lang", "en");
    localStorage.setItem("ds_view_mode", initialView);
    localStorage.setItem("ds_dashboard_edition", "legacy");
  }, view);

  await mockResolved("get_autostart_enabled", false);
  await mockResolved("list_universe_profiles", [
    { name: "sp500", description: "S&P 500", symbol_count: 1 },
  ]);
  await mockResolved("get_market_regime", marketRegime);
  await mockResolved("portfolio_list", []);
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
  await mockResolved("set_regime_scoring_enabled", true);
  await mockResolved("set_universe_profile", {
    name: "sp500",
    symbols_total: 1,
    symbols_loaded: 1,
    profile_locked: false,
    stale_snapshots: false,
  });
  await mockResolved("get_universe_profile", {
    name: "sp500",
    symbols_total: 1,
    symbols_loaded: 1,
    profile_locked: false,
    stale_snapshots: false,
  });
  await mockResolved("get_opportunities", [opportunity]);
  await mockResolved("get_feed_status", {
    running: true,
    symbols_loaded: 1,
    symbols_total: 1,
    last_error: null,
    profile_name: "sp500",
    profile_locked: false,
    stale_snapshots: false,
  });

  await mockResolved("get_symbol_detail", detail);
  await mockResolved("ensure_symbol_loaded", "AAPL");
  await mockResolved("refresh_symbol", "AAPL");
  await mockResolved("get_candles", []);
  await mockResolved("get_symbol_history", []);
  await mockResolved("get_news", {
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
  await mockResolved("get_price_provenance", {
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

  return { analystForecasts, loadForecasts, saveKey, testKey, deleteKey };
}
