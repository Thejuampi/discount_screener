import { useEffect, useState } from "react";
import { api } from "../api";
import type { QuantLensReport } from "../api";
import { useT } from "../i18n";
import { UI, UiInspectable } from "../uiInspect";

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
    <UiInspectable
      as="div"
      className="ql-panel"
      source={UI.detailQuantLens}
      snapshot={{
        symbol,
        primaryStatus: report.primary_status,
        sectionCount: report.sections.length,
        sectionIds: report.sections.map((s) => s.id),
      }}
    >
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
    </UiInspectable>
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
    valuation_driver: "valuation driver",
    growth_driver: "growth driver",
    business_class: "class",
    scenario_width_bps: "scenario width",
    low_cents: "low",
    base_cents: "base",
    high_cents: "high",
    upside_bps: "vs price",
    model_bear_cents: "model bear",
    model_base_cents: "model base",
    model_bull_cents: "model bull",
    model_upside_bps: "model vs price",
    analyst_base_cents: "analyst base",
    analyst_upside_bps: "analyst vs price",
    model_analyst_diverge_bps: "model↔analyst",
    discount_rate_bps: "WACC / rₑ",
    discount_rate_kind: "rate kind",
    rate_quality: "rate quality",
    wacc_provenance: "WACC inputs",
    latest_fcf_dollars: "FCF latest fiscal",
    fcf_run_rate_dollars: "FCF run-rate",
    latest_revenue_dollars: "latest revenue",
    normalized_fcff_dollars: "normalized FCFF",
    normalized_ocf_margin_bps: "normalized OCF margin",
    normalized_capex_intensity_bps: "normalized CapEx intensity",
    capex_spike_years: "CapEx spikes",
    fcf_series: "FCF series",
    capex_imputed_years: "CapEx imputed",
    net_debt_dollars: "net debt",
    shares_outstanding: "shares",
    g_near_bps: "g near",
    g_stable_bps: "g stable",
    cost_of_equity_bps: "rₑ",
    cost_of_debt_bps: "r_d",
    after_tax_cod_bps: "r_d after-tax",
    equity_weight_bps: "equity weight",
    debt_weight_bps: "debt weight",
    wacc_bear_bps: "bear WACC",
    wacc_bull_bps: "bull WACC",
    scenario_stress: "scenarios",
    bvps_cents: "BVPS",
    roe0_bps: "ROE",
  };
  return map[key] ?? key;
}

function formatMetric(key: string, value: string): string {
  if (value === "n/a" || value === "null" || value === "—") return value;
  if (
    key.endsWith("_cents")
    || key === "low_cents"
    || key === "base_cents"
    || key === "high_cents"
    || key === "bvps_cents"
  ) {
    const n = Number(value);
    if (Number.isFinite(n)) return `$${(n / 100).toFixed(2)}`;
  }
  if (
    key === "latest_fcf_dollars"
    || key === "fcf_run_rate_dollars"
    || key === "latest_revenue_dollars"
    || key === "normalized_fcff_dollars"
    || key === "net_debt_dollars"
  ) {
    const n = Number(value);
    if (Number.isFinite(n)) {
      const abs = Math.abs(n);
      if (abs >= 1e9) return `$${(n / 1e9).toFixed(1)}B`;
      if (abs >= 1e6) return `$${(n / 1e6).toFixed(0)}M`;
      return `$${n.toFixed(0)}`;
    }
  }
  if (key === "shares_outstanding") {
    const n = Number(value);
    if (Number.isFinite(n) && n >= 1e6) return `${(n / 1e6).toFixed(0)}M`;
  }
  if (
    key.endsWith("_bps")
    || key === "gap_bps"
    || key === "upside_bps"
    || key === "scenario_width_bps"
  ) {
    const n = Number(value);
    if (Number.isFinite(n)) {
      // Rate-like bps at 2 decimals; gaps/upside at 1.
      if (
        key.includes("wacc")
        || key.includes("discount_rate")
        || key.includes("cost_of")
        || key.includes("after_tax")
        || key === "g_near_bps"
        || key === "g_stable_bps"
        || key === "roe0_bps"
        || key === "equity_weight_bps"
        || key === "debt_weight_bps"
      ) {
        return `${(n / 100).toFixed(2)}%`;
      }
      return `${(n / 100).toFixed(1)}%`;
    }
  }
  return value;
}
