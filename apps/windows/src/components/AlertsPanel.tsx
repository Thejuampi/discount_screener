import { useState, useEffect } from "react";
import { api } from "../api";
import type { AlertEvent } from "../api";
import { useT } from "../i18n";

interface Props {
  onClose: () => void;
}

export function AlertsPanel({ onClose }: Props) {
  const { t } = useT();
  const [alerts, setAlerts] = useState<AlertEvent[]>([]);

  useEffect(() => {
    api.getAlerts().then(setAlerts).catch(console.error);
  }, []);

  const KIND_LABELS: Record<string, { label: string; color: string }> = {
    EnteredQualified:   { label: t("alerts.entered"),  color: "var(--success)" },
    ExitedQualified:    { label: t("alerts.exited"),   color: "var(--danger)" },
    ConfidenceUpgraded: { label: t("alerts.upgraded"), color: "var(--warning)" },
  };

  return (
    <div className="alerts-panel">
      <div className="alerts-header">
        <span>{t("alerts.title")}</span>
        <button className="close-btn" onClick={onClose}>✕</button>
      </div>
      {alerts.length === 0 ? (
        <div className="alerts-empty">{t("alerts.empty")}</div>
      ) : (
        <ul className="alerts-list">
          {alerts.map((a, i) => {
            const meta = KIND_LABELS[a.kind] ?? { label: a.kind, color: "var(--text-3)" };
            const date = new Date(a.timestamp_seconds * 1000).toLocaleTimeString();
            return (
              <li key={i} className="alert-item">
                <span className="alert-symbol">{a.symbol}</span>
                <span className="alert-kind" style={{ color: meta.color }}>{meta.label}</span>
                <span className="alert-time">{date}</span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
