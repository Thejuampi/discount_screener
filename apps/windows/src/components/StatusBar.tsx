import { useState, useEffect } from "react";
import { api } from "../api";
import type { FeedStatus } from "../api";
import { useT } from "../i18n";

interface Props {
  rowCount: number;
}

export function StatusBar({ rowCount }: Props) {
  const { t } = useT();
  const [status, setStatus] = useState<FeedStatus | null>(null);

  useEffect(() => {
    const tick = () => api.getFeedStatus().then(setStatus).catch(() => {});
    tick();
    const id = setInterval(tick, 3000);
    return () => clearInterval(id);
  }, []);

  const total = status?.symbols_total ?? 528;
  const loaded = status?.symbols_loaded ?? 0;
  const pct = total > 0 ? Math.round((loaded / total) * 100) : 0;
  const isFullyLoaded = status?.running && loaded >= total;
  const feedLabel = !status?.running
    ? t("status.starting")
    : isFullyLoaded
    ? `${t("status.live")} · ${total} ${t("status.symbols")}`
    : `${t("status.loading")} ${loaded}/${total} (${pct}%)`;

  return (
    <footer className="status-bar">
      <span className={`feed-dot ${isFullyLoaded ? "live" : "loading"}`} />
      <span>{feedLabel}</span>
      {status?.last_error && (
        <span className="feed-error" title={status.last_error}>
          ⚠ {status.last_error.slice(0, 60)}
        </span>
      )}
      <span className="status-sep" />
      <span style={{ fontFamily: "var(--font-mono)", fontVariantNumeric: "tabular-nums" }}>
        {rowCount} {t("status.visible")}
      </span>
    </footer>
  );
}
