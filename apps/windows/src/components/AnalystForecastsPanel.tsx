import { useEffect, useMemo, useState } from "react";
import { api, fmt } from "../api";
import type { AnalystForecastPanel, ForecastActionKind } from "../api";
import { useT } from "../i18n";
import {
  DEFAULT_FORECAST_SORT_KEY,
  defaultSortDir,
  formatProviderDate,
  nextForecastSort,
  sortForecastObservations,
  type ForecastSortKey,
  type SortDir,
} from "../analystForecastPresentation";

interface Props {
  symbol: string;
}

type Translate = (key: string, vars?: Record<string, string | number>) => string;

const CHART_WIDTH = 760;
const CHART_HEIGHT = 240;
const CHART_PAD = 28;

function actionLabel(kind: ForecastActionKind, t: Translate, fallback: string): string {
  if (kind === "load") return t("tipranks.action.load");
  if (kind === "refresh") return t("tipranks.action.refresh");
  return fallback;
}

export function AnalystForecastsPanel({ symbol }: Props) {
  const { t, lang } = useT();
  const [model, setModel] = useState<AnalystForecastPanel | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);

  useEffect(() => {
    var active = true;
    setModel(null);
    setLoading(true);
    api.getAnalystForecasts(symbol)
      .then((value) => {
        if (active) setModel(value);
      })
      .catch(() => {
        if (active) setModel(null);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [symbol]);

  async function onAction() {
    if (model == null || !model.action.enabled || acting) return;
    if (model.action.call_cost > 0 && model.action.confirmation_message) {
      var ok = window.confirm(model.action.confirmation_message);
      if (!ok) return;
    }
    setActing(true);
    try {
      var next = await api.loadAnalystForecasts(symbol);
      setModel(next);
    } catch {
      // keep prior model; backend may still return error_banner on success path
    } finally {
      setActing(false);
    }
  }

  if (loading && model == null) {
    return (
      <section className="info-section analyst-forecasts">
        <h3>{t("tipranks.forecasts.title")}</h3>
        <div className="loading-msg" style={{ padding: 12 }}>
          {t("tipranks.forecasts.loading")}
        </div>
      </section>
    );
  }
  if (model == null) {
    return (
      <section className="info-section analyst-forecasts">
        <h3>{t("tipranks.forecasts.title")}</h3>
        <div className="forecast-empty forecast-empty--error">
          <p className="forecast-empty-msg">{t("tipranks.forecasts.loadFailed")}</p>
        </div>
      </section>
    );
  }
  if (model.state === "not_eligible") return null;

  var hasForecasts = model.observations.length > 0;
  var cacheLabel =
    model.cache_freshness == null
      ? t("tipranks.cache.none")
      : t(`tipranks.cache.${model.cache_freshness}`);
  var label = acting
    ? t("tipranks.forecasts.loading")
    : actionLabel(model.action.kind, t, model.action.label);
  var showHeaderAction = hasForecasts && model.action.kind !== "none";
  var showEmptyCallout = !hasForecasts;

  return (
    <section className="info-section analyst-forecasts">
      <div className="forecast-heading">
        <div>
          <h3>{t("tipranks.forecasts.title")}</h3>
          <div className="forecast-meta">
            <span className="forecast-provider">
              {t("tipranks.provider")} · {cacheLabel}
            </span>
            <span
              className={
                model.quota.warning || model.quota.exhausted
                  ? "forecast-chip forecast-chip--warn"
                  : "forecast-chip"
              }
            >
              {t("tipranks.quota.remaining", {
                remaining: model.quota.remaining,
                limit: model.quota.limit,
              })}
              {model.quota.estimated ? ` · ${t("tipranks.quota.estimated")}` : ""}
            </span>
          </div>
        </div>
        {showHeaderAction && (
          <button
            className="btn-secondary forecast-action-btn"
            disabled={!model.action.enabled || acting}
            onClick={onAction}
          >
            {label}
          </button>
        )}
      </div>

      {model.quota.warning && (
        <div className="forecast-budget-alert">
          {t("tipranks.quota.warning", {
            attempts: model.quota.attempts,
            limit: model.quota.limit,
            remaining: model.quota.remaining,
          })}
        </div>
      )}

      {model.error_banner && (
        <div className="forecast-empty forecast-empty--error">
          <p className="forecast-empty-msg">{model.error_banner}</p>
        </div>
      )}

      {model.cache_freshness === "stale" && (
        <div className="forecast-stale-alert">{t("tipranks.cache.staleBanner")}</div>
      )}
      {model.cache_freshness === "aging" && (
        <div className="forecast-aging-notice">{t("tipranks.cache.agingNotice")}</div>
      )}

      {showEmptyCallout && (
        <div className={`forecast-empty forecast-empty--${model.state}`}>
          <p className="forecast-empty-msg">{t(`tipranks.state.${model.state}`)}</p>
          {model.action.kind !== "none" && (
            <>
              <button
                className="btn-primary forecast-action-btn"
                disabled={!model.action.enabled || acting}
                onClick={onAction}
              >
                {label}
              </button>
              {model.action.call_cost > 0 && (
                <span className="forecast-empty-hint">
                  {t("tipranks.action.costHint", { cost: model.action.call_cost })}
                </span>
              )}
            </>
          )}
        </div>
      )}

      {hasForecasts && model.state !== "ready" && (
        <div className={`forecast-state forecast-state--${model.state}`}>
          {t(`tipranks.state.${model.state}`)}
        </div>
      )}

      {hasForecasts && (
        <>
          <div className="forecast-ages">
            {model.fetched_at_epoch != null && (
              <span>
                {t("tipranks.age.fetched", {
                  date: new Date(model.fetched_at_epoch * 1000).toLocaleString(lang),
                })}
              </span>
            )}
            {model.latest_observation_epoch != null && (
              <span>
                {t("tipranks.age.latestOpinion", {
                  date: new Date(model.latest_observation_epoch * 1000).toLocaleString(lang),
                  freshness: t(`tipranks.obs.${model.observation_freshness}`),
                })}
              </span>
            )}
          </div>

          <div className="forecast-chart-stack">
            <ForecastTimeline model={model} t={t} />
            <ForecastDistribution model={model} t={t} />
          </div>
          <div className="forecast-grid">
            <ForecastSummary model={model} t={t} />
          </div>
          <ForecastRows model={model} t={t} lang={lang} />

          <footer className="forecast-footer">
            <span>{t("tipranks.horizon.disclosure")}</span>
            <span>
              {t("tipranks.quota.resets", {
                date: new Date(model.quota.resets_at_epoch * 1000).toLocaleString(lang),
              })}
            </span>
          </footer>
        </>
      )}
    </section>
  );
}

function priceScale(model: AnalystForecastPanel) {
  var history = model.price_history;
  var targets = model.observations;
  var prices = [
    ...history.map((point) => point.close_cents),
    ...targets.map((item) => item.target_cents),
    ...targets.flatMap((item) =>
      item.price_when_posted_cents == null ? [] : [item.price_when_posted_cents]),
    ...model.histogram.flatMap((bin) => [bin.low_cents, bin.high_cents]),
  ];
  if (prices.length === 0) return { minPrice: 0, maxPrice: 1 };
  return {
    minPrice: Math.min(...prices),
    maxPrice: Math.max(...prices),
  };
}

function ForecastTimeline({ model, t }: { model: AnalystForecastPanel; t: Translate }) {
  var history = model.price_history;
  var targets = model.observations;
  var epochs = [
    ...history.map((point) => point.epoch_seconds),
    ...targets.flatMap((item) => [item.issued_at_epoch, item.horizon_epoch]),
  ];
  var { minPrice, maxPrice } = priceScale(model);
  var minEpoch = Math.min(...epochs);
  var maxEpoch = Math.max(...epochs);
  var x = (epoch: number) =>
    CHART_PAD + ((epoch - minEpoch) / Math.max(1, maxEpoch - minEpoch)) * (CHART_WIDTH - CHART_PAD * 2);
  var y = (price: number) =>
    CHART_HEIGHT - CHART_PAD -
    ((price - minPrice) / Math.max(1, maxPrice - minPrice)) * (CHART_HEIGHT - CHART_PAD * 2);
  var historyPath = history
    .map((point, index) => `${index === 0 ? "M" : "L"} ${x(point.epoch_seconds)} ${y(point.close_cents)}`)
    .join(" ");

  return (
    <div className="forecast-chart-wrap">
      <div className="forecast-subtitle">{t("tipranks.timeline.title")}</div>
      <svg
        className="forecast-timeline"
        viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
        role="img"
        aria-label={t("tipranks.timeline.aria", { count: targets.length, symbol: model.symbol })}
      >
        {historyPath && <path className="forecast-price-line" d={historyPath} />}
        {targets.map((item, index) => {
          var start = item.price_when_posted_cents ?? item.target_cents;
          return (
            <g key={`${item.identity ?? "unknown"}-${item.issued_at_epoch}-${index}`}>
              <line
                className="forecast-target-line"
                x1={x(item.issued_at_epoch)}
                y1={y(start)}
                x2={x(item.horizon_epoch)}
                y2={y(item.target_cents)}
              />
              <circle
                className="forecast-target-point"
                cx={x(item.horizon_epoch)}
                cy={y(item.target_cents)}
                r="3.5"
              >
                <title>
                  {item.analyst ?? item.firm ?? t("tipranks.unknownSource")}: {fmt.dollars(item.target_cents)}
                </title>
              </circle>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

function ForecastDistribution({ model, t }: { model: AnalystForecastPanel; t: Translate }) {
  var maxCount = Math.max(...model.histogram.map((bin) => bin.count), 1);
  var { minPrice, maxPrice } = priceScale(model);
  var span = Math.max(1, maxPrice - minPrice);
  return (
    <div className="forecast-distribution forecast-distribution--horizontal">
      <div className="forecast-subtitle">{t("tipranks.distribution.title")}</div>
      <div className="forecast-h-bars" style={{ height: CHART_HEIGHT }}>
        {model.histogram.map((bin, index) => {
          var mid = (bin.low_cents + bin.high_cents) / 2;
          var topPct = ((maxPrice - mid) / span) * 100;
          var widthPct = Math.max(4, (bin.count / maxCount) * 100);
          return (
            <div
              className="forecast-h-bin"
              key={`${bin.low_cents}-${bin.high_cents}-${index}`}
              style={{ top: `${Math.max(0, Math.min(92, topPct))}%` }}
              title={`${fmt.dollars(bin.low_cents)} – ${fmt.dollars(bin.high_cents)}: ${bin.count}`}
            >
              <div className="forecast-h-bin-label">{fmt.dollars(bin.low_cents)}</div>
              <div className="forecast-h-bin-track">
                <div className="forecast-h-bin-fill" style={{ width: `${widthPct}%` }} />
              </div>
              <div className="forecast-h-bin-count">{bin.count}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function ForecastSummary({ model, t }: { model: AnalystForecastPanel; t: Translate }) {
  var stats = model.statistics;
  if (stats == null) return null;
  return (
    <div className="forecast-summary">
      <div className="forecast-subtitle">{t("tipranks.summary.title")}</div>
      <div className="kv-grid">
        <span>{t("tipranks.summary.minimum")}</span><span>{fmt.dollars(stats.minimum_cents)}</span>
        <span>{t("tipranks.summary.maximum")}</span><span>{fmt.dollars(stats.maximum_cents)}</span>
        <span>{t("tipranks.summary.simpleMean")}</span><span>{fmt.dollars(stats.simple_mean_cents)}</span>
        <span>{t("tipranks.summary.weightedMean")}</span>
        <span>{stats.weighted_mean_cents == null ? t("tipranks.unavailable") : fmt.dollars(stats.weighted_mean_cents)}</span>
        <span>{t("tipranks.summary.identities")}</span><span>{model.identity_count}</span>
      </div>
      <div className="forecast-weight-note">
        {model.usable_weighted_consensus
          ? stats.weighting_label
          : t("tipranks.weighting.unavailable")}
      </div>
    </div>
  );
}

const SORTABLE_COLUMNS: { key: ForecastSortKey; labelKey: string; numeric?: boolean }[] = [
  { key: "analyst", labelKey: "tipranks.table.analystFirm" },
  { key: "weight", labelKey: "tipranks.table.weight", numeric: true },
  { key: "stars", labelKey: "tipranks.table.stars", numeric: true },
  { key: "rank", labelKey: "tipranks.table.rank", numeric: true },
  { key: "target", labelKey: "tipranks.table.target", numeric: true },
  { key: "rating", labelKey: "tipranks.table.rating" },
  { key: "issued", labelKey: "tipranks.table.issued", numeric: true },
  { key: "atPublication", labelKey: "tipranks.table.atPublication", numeric: true },
  { key: "horizon", labelKey: "tipranks.table.horizon", numeric: true },
];

function ForecastRows({
  model,
  t,
  lang,
}: {
  model: AnalystForecastPanel;
  t: Translate;
  lang: "es" | "en";
}) {
  const [sortKey, setSortKey] = useState<ForecastSortKey>(DEFAULT_FORECAST_SORT_KEY);
  const [sortDir, setSortDir] = useState<SortDir>(() => defaultSortDir(DEFAULT_FORECAST_SORT_KEY));

  var rows = useMemo(
    () => sortForecastObservations(model.observations, sortKey, sortDir),
    [model.observations, sortKey, sortDir],
  );

  var activeLabelKey =
    SORTABLE_COLUMNS.find((col) => col.key === sortKey)?.labelKey ?? "tipranks.table.weight";

  function onSort(key: ForecastSortKey) {
    var next = nextForecastSort(sortKey, sortDir, key);
    setSortKey(next.key);
    setSortDir(next.dir);
  }

  return (
    <div className="forecast-table-block">
      <div className="forecast-table-toolbar">
        <div className="forecast-subtitle forecast-table-title">
          {t("tipranks.table.caption", { count: rows.length })}
        </div>
        <div className="forecast-table-sort-status" aria-live="polite">
          {t("tipranks.table.sortedBy", {
            column: t(activeLabelKey),
            dir: t(`tipranks.table.dir.${sortDir}`),
          })}
        </div>
      </div>
      <div className="forecast-table-wrap">
        <table className="forecast-table">
          <thead>
            <tr>
              {SORTABLE_COLUMNS.map((col) => {
                var active = sortKey === col.key;
                var indicator = active ? (sortDir === "asc" ? " ↑" : " ↓") : "";
                return (
                  <th
                    key={col.key}
                    className={[
                      "forecast-th-sortable",
                      col.numeric ? "forecast-th-num" : "",
                      active ? "forecast-th-active" : "",
                      col.key === "weight" ? "forecast-th-weight" : "",
                    ]
                      .filter(Boolean)
                      .join(" ")}
                    aria-sort={
                      active ? (sortDir === "asc" ? "ascending" : "descending") : "none"
                    }
                  >
                    <button
                      type="button"
                      className="forecast-sort-btn"
                      onClick={() => onSort(col.key)}
                      title={t("tipranks.table.sortBy", { column: t(col.labelKey) })}
                    >
                      <span>{t(col.labelKey)}</span>
                      <span className="forecast-sort-indicator" aria-hidden="true">
                        {indicator || " ↕"}
                      </span>
                    </button>
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {rows.map((item, index) => (
              <tr key={`${item.identity ?? "unknown"}-${item.issued_at_epoch}-${index}`}>
                <td>
                  <strong>{item.analyst ?? item.firm ?? t("tipranks.unknown")}</strong>
                  {item.analyst && item.firm && <small>{item.firm}</small>}
                </td>
                <td className="forecast-td-num forecast-td-weight">
                  {item.weight_hundredths == null
                    ? "—"
                    : (item.weight_hundredths / 100).toFixed(2)}
                </td>
                <td className="forecast-td-num">
                  {item.stars_hundredths == null
                    ? "—"
                    : (item.stars_hundredths / 100).toFixed(1)}
                </td>
                <td className="forecast-td-num">{item.rank ?? "—"}</td>
                <td className="forecast-td-num">{fmt.dollars(item.target_cents)}</td>
                <td>{item.rating ?? "—"}</td>
                <td className="forecast-td-num">{formatProviderDate(item.issued_at_epoch, lang)}</td>
                <td className="forecast-td-num">
                  {item.price_when_posted_cents == null
                    ? "—"
                    : fmt.dollars(item.price_when_posted_cents)}
                </td>
                <td className="forecast-td-num">
                  {formatProviderDate(item.horizon_epoch, lang)}
                  <small>
                    {t(
                      item.horizon_label === "Provider horizon"
                        ? "tipranks.horizon.provider"
                        : "tipranks.horizon.assumed",
                    )}
                  </small>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
