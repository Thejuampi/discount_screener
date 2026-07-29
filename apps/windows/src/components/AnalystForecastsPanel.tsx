import { useEffect, useState } from "react";
import { api, fmt } from "../api";
import type { AnalystForecastPanel } from "../api";
import { useT } from "../i18n";
import { formatProviderDate } from "../analystForecastPresentation";

interface Props {
  symbol: string;
}

type Translate = (key: string, vars?: Record<string, string | number>) => string;

const CHART_WIDTH = 760;
const CHART_HEIGHT = 240;
const CHART_PAD = 28;

export function AnalystForecastsPanel({ symbol }: Props) {
  const { t, lang } = useT();
  const [model, setModel] = useState<AnalystForecastPanel | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
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

  if (loading && model == null) {
    return (
      <section className="info-section analyst-forecasts">
        <h3>{t("fmp.forecasts.title")}</h3>
        <div className="forecast-state">{t("fmp.forecasts.loading")}</div>
      </section>
    );
  }
  if (model == null) {
    return (
      <section className="info-section analyst-forecasts">
        <h3>{t("fmp.forecasts.title")}</h3>
        <div className="forecast-state forecast-state--error">
          {t("fmp.forecasts.loadFailed")}
        </div>
      </section>
    );
  }
  if (model.state === "not_eligible") return null;

  const hasForecasts = model.observations.length > 0;
  return (
    <section className="info-section analyst-forecasts">
      <div className="forecast-heading">
        <div>
          <h3>{t("fmp.forecasts.title")}</h3>
          <div className="forecast-provider">
            {t("fmp.provider")} · {model.from_cache ? t("fmp.cache.daily") : t("fmp.cache.fresh")} ·{" "}
            {t("fmp.quota.remaining", {
              remaining: model.quota.remaining,
              limit: model.quota.limit,
            })}
          </div>
        </div>
      </div>

      {model.quota.warning && (
        <div className="forecast-budget-alert">
          {t("fmp.quota.warning", {
            attempts: model.quota.attempts,
            limit: model.quota.limit,
            remaining: model.quota.remaining,
          })}
        </div>
      )}

      <div className={`forecast-state forecast-state--${model.state}`}>
        {t(`fmp.state.${model.state}`)}
      </div>

      {hasForecasts && (
        <>
          <ForecastTimeline model={model} t={t} />
          <div className="forecast-grid">
            <ForecastDistribution model={model} t={t} />
            <ForecastSummary model={model} t={t} />
          </div>
          <ForecastRows model={model} t={t} lang={lang} />
        </>
      )}

      <footer className="forecast-footer">
        <span>{t("fmp.horizon.disclosure")}</span>
        <span>
          {t("fmp.quota.resets", {
            date: new Date(model.quota.resets_at_epoch * 1000).toLocaleString(lang),
          })}
        </span>
      </footer>
    </section>
  );
}

function ForecastTimeline({ model, t }: { model: AnalystForecastPanel; t: Translate }) {
  const history = model.price_history;
  const targets = model.observations;
  const epochs = [
    ...history.map((point) => point.epoch_seconds),
    ...targets.flatMap((item) => [item.issued_at_epoch, item.horizon_epoch]),
  ];
  const prices = [
    ...history.map((point) => point.close_cents),
    ...targets.map((item) => item.target_cents),
    ...targets.flatMap((item) =>
      item.price_when_posted_cents == null ? [] : [item.price_when_posted_cents]),
  ];
  const minEpoch = Math.min(...epochs);
  const maxEpoch = Math.max(...epochs);
  const minPrice = Math.min(...prices);
  const maxPrice = Math.max(...prices);
  const x = (epoch: number) =>
    CHART_PAD + ((epoch - minEpoch) / Math.max(1, maxEpoch - minEpoch)) * (CHART_WIDTH - CHART_PAD * 2);
  const y = (price: number) =>
    CHART_HEIGHT - CHART_PAD -
    ((price - minPrice) / Math.max(1, maxPrice - minPrice)) * (CHART_HEIGHT - CHART_PAD * 2);
  const historyPath = history
    .map((point, index) => `${index === 0 ? "M" : "L"} ${x(point.epoch_seconds)} ${y(point.close_cents)}`)
    .join(" ");

  return (
    <div className="forecast-chart-wrap">
      <div className="forecast-subtitle">{t("fmp.timeline.title")}</div>
      <svg
        className="forecast-timeline"
        viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
        role="img"
        aria-label={t("fmp.timeline.aria", { count: targets.length, symbol: model.symbol })}
      >
        {historyPath && <path className="forecast-price-line" d={historyPath} />}
        {targets.map((item, index) => {
          const start = item.price_when_posted_cents ?? item.target_cents;
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
                  {item.analyst ?? item.firm ?? t("fmp.unknownSource")}: {fmt.dollars(item.target_cents)}
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
  const maxCount = Math.max(...model.histogram.map((bin) => bin.count), 1);
  return (
    <div className="forecast-distribution">
      <div className="forecast-subtitle">{t("fmp.distribution.title")}</div>
      <div className="forecast-bars">
        {model.histogram.map((bin, index) => (
          <div className="forecast-bin" key={`${bin.low_cents}-${bin.high_cents}-${index}`}>
            <div className="forecast-bin-count">{bin.count}</div>
            <div className="forecast-bin-track">
              <div
                className="forecast-bin-fill"
                style={{ height: `${Math.max(4, (bin.count / maxCount) * 100)}%` }}
              />
            </div>
            <div className="forecast-bin-label">{fmt.dollars(bin.low_cents)}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

function ForecastSummary({ model, t }: { model: AnalystForecastPanel; t: Translate }) {
  const stats = model.statistics;
  if (stats == null) return null;
  return (
    <div className="forecast-summary">
      <div className="forecast-subtitle">{t("fmp.summary.title")}</div>
      <div className="kv-grid">
        <span>{t("fmp.summary.minimum")}</span><span>{fmt.dollars(stats.minimum_cents)}</span>
        <span>{t("fmp.summary.maximum")}</span><span>{fmt.dollars(stats.maximum_cents)}</span>
        <span>{t("fmp.summary.simpleMean")}</span><span>{fmt.dollars(stats.simple_mean_cents)}</span>
        <span>{t("fmp.summary.weightedMean")}</span>
        <span>{stats.weighted_mean_cents == null ? t("fmp.unavailable") : fmt.dollars(stats.weighted_mean_cents)}</span>
        <span>{t("fmp.summary.identities")}</span><span>{model.identity_count}</span>
      </div>
      <div className="forecast-weight-note">{t("fmp.weighting.unavailable")}</div>
    </div>
  );
}

function ForecastRows({
  model,
  t,
  lang,
}: {
  model: AnalystForecastPanel;
  t: Translate;
  lang: "es" | "en";
}) {
  return (
    <div className="forecast-table-wrap">
      <table className="forecast-table">
        <thead>
          <tr>
            <th>{t("fmp.table.analystFirm")}</th>
            <th>{t("fmp.table.issued")}</th>
            <th>{t("fmp.table.rating")}</th>
            <th>{t("fmp.table.target")}</th>
            <th>{t("fmp.table.atPublication")}</th>
            <th>{t("fmp.table.horizon")}</th>
          </tr>
        </thead>
        <tbody>
          {model.observations.map((item, index) => (
            <tr key={`${item.identity ?? "unknown"}-${item.issued_at_epoch}-${index}`}>
              <td>
                <strong>{item.analyst ?? item.firm ?? t("fmp.unknown")}</strong>
                {item.analyst && item.firm && <small>{item.firm}</small>}
              </td>
              <td>{formatProviderDate(item.issued_at_epoch, lang)}</td>
              <td>{item.rating ?? "—"}</td>
              <td>{fmt.dollars(item.target_cents)}</td>
              <td>
                {item.price_when_posted_cents == null ? "—" : fmt.dollars(item.price_when_posted_cents)}
              </td>
              <td>
                {formatProviderDate(item.horizon_epoch, lang)}
                <small>
                  {t(item.horizon_label === "Provider horizon"
                    ? "fmp.horizon.provider"
                    : "fmp.horizon.assumed")}
                </small>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
