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
          <div
            key={s.id}
            className={`ql-section${s.status === "Disputed" || s.status === "Mixed" ? " ql-section-warn" : ""}`}
          >
            <div className="ql-section-head">
              <strong>{s.title}</strong>
              <span className={`muted${s.status === "Disputed" ? " ql-status-disputed" : ""}`}>
                {s.status}
              </span>
            </div>
            <p>{s.summary}</p>
            {s.metrics?.length > 0 && (
              <ul className="ql-metrics">
                {s.metrics
                  .filter(([k]) => !shouldHideMetric(k))
                  .map(([k, v]) => (
                    <li key={k}>
                      <span>{metricLabel(k)}</span>
                      <span>{formatMetric(k, v)}</span>
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

/** Raw plumbing keys stay in the payload but do not need to clutter the panel. */
function shouldHideMetric(key: string): boolean {
  return key === "notes" || key === "source" || key === "primary";
}

function metricLabel(key: string): string {
  const map: Record<string, string> = {
    families: "families",
    conflict: "conflicts",
    gap_bps: "analyst gap",
    model_quality: "model quality",
    valuation_model: "model",
    business_class: "class",
    scenario_width_bps: "scenario width",
    low_cents: "low",
    base_cents: "base",
    high_cents: "high",
    upside_bps: "vs price",
    model_base_cents: "model base",
    model_upside_bps: "model vs price",
    analyst_base_cents: "analyst base",
    analyst_upside_bps: "analyst vs price",
    model_analyst_diverge_bps: "model↔analyst",
    discount_rate_bps: "discount rate",
    discount_rate_kind: "rate kind",
    bvps_cents: "BVPS",
    roe0_bps: "ROE",
  };
  return map[key] ?? key;
}

function formatMetric(key: string, value: string): string {
  if (value === "n/a" || value === "null" || value === "—") return value;
  if (key.endsWith("_cents") || key === "low_cents" || key === "base_cents" || key === "high_cents" || key === "bvps_cents") {
    const n = Number(value);
    if (Number.isFinite(n)) return `$${(n / 100).toFixed(2)}`;
  }
  if (key.endsWith("_bps") || key === "gap_bps" || key === "upside_bps" || key === "scenario_width_bps") {
    const n = Number(value);
    if (Number.isFinite(n)) return `${(n / 100).toFixed(1)}%`;
  }
  if (key === "discount_rate_bps" || key === "roe0_bps") {
    const n = Number(value);
    if (Number.isFinite(n)) return `${(n / 100).toFixed(2)}%`;
  }
  return value;
}
