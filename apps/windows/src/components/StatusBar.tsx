import type { FeedStatus } from "../api";
import { isFeedRefreshBusy, type FeedRefreshRequestState } from "../feedRefresh";
import { useT } from "../i18n";
import { UI, UiInspectable } from "../uiInspect";

interface Props {
  rowCount: number;
  status: FeedStatus | null;
  refreshRequest: FeedRefreshRequestState;
}

export function StatusBar({ rowCount, status, refreshRequest }: Props) {
  const { t } = useT();
  const total = status?.symbols_total ?? 0;
  const loaded = status?.symbols_loaded ?? 0;
  const isFullyLoaded = status?.running && total > 0 && loaded >= total;
  const profile = status?.profile_name.toUpperCase();
  const pass = status?.refresh;
  const phase = pass?.phase ?? "idle";
  const passBusy = isFeedRefreshBusy(phase);
  const requesting = refreshRequest === "requesting";
  const phaseLabel = pass && passBusy
    ? t(`status.refresh.${phase}`)
    : requesting ? t("status.refresh.requesting") : null;
  const issues = pass ? [
    ["status.refresh.quoteFailures", pass.quote_failures],
    ["status.refresh.chartFailures", pass.chart_failures],
    ["status.refresh.rateLimited", pass.rate_limited],
  ] as const : [];
  const hasIssues = issues.some(([, count]) => count > 0) || !!status?.system_error || refreshRequest === "rejected";
  const feedBusy = passBusy || requesting || status === null;
  const feedLive = isFullyLoaded && !feedBusy && !hasIssues;
  const dotState = feedBusy ? "loading" : feedLive ? "live" : "warning";
  const feedLabel = !status?.running
    ? t(status?.system_error ? "status.unavailable" : "status.starting")
    : feedLive
      ? `${t("status.live")} · ${profile} · ${total} ${t("status.symbols")}`
      : t("status.available", { profile: profile ?? "", loaded, total });

  return (
    <UiInspectable
      as="footer"
      className="status-bar"
      source={UI.statusBar}
      snapshot={{
        rowCount,
        symbolsLoaded: loaded,
        symbolsTotal: total,
        feedRunning: status?.running ?? false,
        profileName: status?.profile_name ?? null,
        refreshPhase: phase,
        refreshTrigger: pass?.trigger ?? null,
        refreshProcessed: pass?.processed ?? 0,
        refreshTotal: pass?.total ?? 0,
      }}
    >
      <div className="feed-status" role="status">
        <span aria-hidden="true" className={`feed-dot ${dotState}`} />
        <span>{feedLabel}</span>
        {phaseLabel && (
          <span className="feed-phase" data-feed-phase={phase}>
            {phaseLabel}
            {pass && passBusy && phase !== "queued" && ` · ${pass.processed}/${pass.total}`}
          </span>
        )}
        {issues.map(([key, count]) => count > 0 && (
          <span key={key} className="feed-error">{t(key, { count })}</span>
        ))}
        {status?.system_error && (
          <span className="feed-error feed-system-error" title={status.system_error}>{status.system_error}</span>
        )}
        {refreshRequest === "rejected" && (
          <span className="feed-error">{t("status.refresh.requestFailed")}</span>
        )}
      </div>
      <span className="status-visible">
        {rowCount} {t("status.visible")}
      </span>
    </UiInspectable>
  );
}
