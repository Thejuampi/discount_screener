import { useEffect, useState } from "react";
import { api } from "../api";
import type { QuantLensReport } from "../api";
import {
  ANALYST_METHOD_POLL_INTERVAL_MS,
  analystMethodPresentation,
  composeQuantLensPanel,
  formatCentsAsCurrency,
  formatMultipleHundredths,
} from "../detailValuationPresentation";
import type { AnalystMethodPresentation } from "../detailValuationPresentation";
import { useT } from "../i18n";
import { UI, UiInspectable } from "../uiInspect";

interface Props {
  symbol: string;
}

export function QuantLensPanel({ symbol }: Props) {
  const { t } = useT();
  const [result, setResult] = useState<{ symbol: string; report: QuantLensReport } | null>(null);
  const [analystResult, setAnalystResult] = useState<{
    symbol: string;
    presentation: AnalystMethodPresentation;
  } | null>(null);
  const [failure, setFailure] = useState<{ symbol: string; message: string } | null>(null);
  const rawReport = result?.symbol === symbol ? result.report : null;
  const analyst = analystResult?.symbol === symbol ? analystResult.presentation : null;
  const err = failure?.symbol === symbol ? failure.message : null;
  const composed = composeQuantLensPanel(symbol, rawReport, analyst, err);
  const report = composed.report;

  useEffect(() => {
    let cancelled = false;
    let issuedGeneration = 0;
    let settledGeneration = 0;
    const timers: number[] = [];
    const refresh = () => {
      const generation = ++issuedGeneration;
      api
        .getQuantLens(symbol)
        .then((r) => {
          if (!cancelled && generation >= settledGeneration) {
            settledGeneration = generation;
            setResult({ symbol, report: r });
            setFailure(null);
          }
        })
        .catch((e) => {
          if (!cancelled && generation >= settledGeneration) {
            settledGeneration = generation;
            setFailure({ symbol, message: String(e) });
          }
        });
    };
    refresh();
    // The first command starts the bounded demand valuation. Re-read the
    // cache while that worker resolves instead of leaving Quant Lens frozen
    // on its pre-route report until the panel is reopened.
    for (const delay of [1_200, 3_500, 8_000, 15_000, 20_000]) {
      timers.push(window.setTimeout(refresh, delay));
    }
    return () => {
      cancelled = true;
      for (const timer of timers) window.clearTimeout(timer);
    };
  }, [symbol]);

  useEffect(() => {
    let cancelled = false;
    let issuedGeneration = 0;
    let settledGeneration = 0;
    const refreshDossier = () => {
      const generation = ++issuedGeneration;
      api
        .getValuationDossier(symbol)
        .then((dossier) => {
          if (!cancelled && generation >= settledGeneration) {
            settledGeneration = generation;
            setAnalystResult({ symbol, presentation: analystMethodPresentation(dossier) });
          }
        })
        .catch(() => {
          if (!cancelled && generation >= settledGeneration) {
            settledGeneration = generation;
            setAnalystResult({
              symbol,
              presentation: {
                kind: "unavailable",
                methodLabel: "manual analyst method",
                reasonCode: "publication_read_failed",
                diagnosticOnly: true,
                rankingEligible: false,
                strongEligible: false,
              },
            });
          }
        });
    };
    refreshDossier();
    // Cache-only read: keep observing publication for an import that completes
    // after the bounded demand-valuation refresh window. Cleanup is mandatory.
    const timer = window.setInterval(refreshDossier, ANALYST_METHOD_POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [symbol]);

  if (err && !report) return <div className="ql-panel muted">{err}</div>;
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
      {composed.coreWarning && (
        <p className="muted" data-ql-core-warning="true">
          Quant Lens core unavailable: {composed.coreWarning}
        </p>
      )}
      <div className="ql-sections">
        {report.sections.map((s) => (
          <div
            key={s.id}
            data-ql-section={s.id}
            data-presentation-source={metricValue(s.metrics, "presentation_source")}
            className={`ql-section${s.status === "Disputed" || s.status === "Mixed" ? " ql-section-warn" : ""}${s.id === "manual_analyst_method" ? " ql-section-diagnostic" : ""}`}
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
                      <span>{formatMetric(k, v, metricValue(s.metrics, "currency"))}</span>
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
    // Slice 1C manual analyst method (diagnostic lane)
    lane: "lane",
    method_label: "method",
    target_value_cents: "target value",
    eps_cents: "EPS claim",
    multiple_hundredths: "multiple",
    forecast_period_end: "forecast period",
    target_as_of: "target as-of",
    date_precision: "date precision",
    currency: "currency",
    metric_id: "metric",
    metric_basis: "metric basis",
    source_verification: "source verification",
    multiple_provenance: "multiple provenance",
    scenario: "scenario",
    import_quality_label: "import quality",
    quality: "quality",
    diagnostic_only: "diagnostic only",
    ranking_eligible: "ranking eligible",
    strong_eligible: "Strong eligible",
    engine_id: "engine",
    method_policy_version: "policy",
    reason_code: "reason",
    run_id: "run",
    share_basis_id: "share basis",
    identity_vintage: "identity vintage",
    lineage_group_id: "lineage group",
    method: "method id",
    presentation_source: "presentation source",
  };
  return map[key] ?? key;
}

function metricValue(metrics: [string, string][], key: string): string | undefined {
  return metrics.find(([candidate]) => candidate === key)?.[1];
}

function formatMetric(key: string, value: string, currency = "USD"): string {
  if (value === "n/a" || value === "null" || value === "—") return value;
  if (key === "multiple_hundredths") {
    return formatMultipleHundredths(value);
  }
  if (
    key.endsWith("_cents")
    || key === "low_cents"
    || key === "base_cents"
    || key === "high_cents"
    || key === "bvps_cents"
    || key === "target_value_cents"
    || key === "eps_cents"
  ) {
    return formatCentsAsCurrency(value, currency);
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
