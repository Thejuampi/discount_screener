import { useEffect, useState } from "react";
import { api } from "../api";
import type { QuantLensReport } from "../api";
import { useT } from "../i18n";

interface Props {
  symbol: string;
}

export function QuantLensPanel({ symbol }: Props) {
  const { t } = useT();
  const [report, setReport] = useState<QuantLensReport | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setReport(null);
    api
      .getQuantLens(symbol)
      .then((r) => {
        if (!cancelled) setReport(r);
      })
      .catch((e) => {
        if (!cancelled) setErr(String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [symbol]);

  if (err) return <div className="ql-panel muted">{err}</div>;
  if (!report) return <div className="ql-panel muted">{t("quant.loading")}</div>;

  return (
    <div className="ql-panel">
      <div className="ql-header">
        <h3>{t("quant.title")}</h3>
        <span className={`est-chip status-${report.primary_status.toLowerCase()}`}>
          {report.primary_status}
        </span>
      </div>
      <div className="ql-sections">
        {report.sections.map((s) => (
          <div key={s.id} className="ql-section">
            <div className="ql-section-head">
              <strong>{s.title}</strong>
              <span className="muted">{s.status}</span>
            </div>
            <p>{s.summary}</p>
            {s.metrics?.length > 0 && (
              <ul className="ql-metrics">
                {s.metrics.map(([k, v]) => (
                  <li key={k}>
                    <span>{k}</span>
                    <span>{v}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
