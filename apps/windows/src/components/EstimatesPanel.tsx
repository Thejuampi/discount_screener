import { useCallback, useEffect, useState } from "react";
import { api, fmt } from "../api";
import type { IndexEstimatesReport, ScenarioEstimate } from "../api";
import { useT } from "../i18n";

function upsideLabel(bps: number): string {
  const pct = (bps / 100).toFixed(1);
  return bps >= 0 ? `+${pct}%` : `${pct}%`;
}

function scenarioTitle(s: string, t: (k: string) => string): string {
  switch (s) {
    case "bear_dcf":
      return t("estimates.bear");
    case "base_dcf":
      return t("estimates.base");
    case "bull_dcf":
      return t("estimates.bull");
    case "analyst_low":
      return t("estimates.analystLow");
    case "analyst_high":
      return t("estimates.analystHigh");
    default:
      return s;
  }
}

function ScenarioCard({
  title,
  items,
}: {
  title: string;
  items: ScenarioEstimate[];
}) {
  const { t } = useT();
  return (
    <div className="est-card">
      <h3>{title}</h3>
      <div className="est-scenarios">
        {items.map((s) => (
          <div key={s.scenario} className="est-scenario-row">
            <span>{scenarioTitle(s.scenario, t)}</span>
            <strong className={s.implied_upside_bps >= 0 ? "pos" : "neg"}>
              {upsideLabel(s.implied_upside_bps)}
            </strong>
            <span className="muted">
              {fmt.dollars(s.weighted_price_cents)} · n={s.coverage_count}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

export function EstimatesPanel() {
  const { t } = useT();
  const [report, setReport] = useState<IndexEstimatesReport | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const r = await api.getIndexEstimates();
      setReport(r);
      setErr(null);
    } catch (e) {
      setErr(String(e));
    }
  }, []);

  useEffect(() => {
    load();
    const id = setInterval(load, 15_000);
    return () => clearInterval(id);
  }, [load]);

  if (err) {
    return <div className="est-panel">{t("estimates.error")}: {err}</div>;
  }
  if (!report) {
    return <div className="est-panel">{t("estimates.loading")}</div>;
  }

  const base = report.scenarios.find((s) => s.scenario === "base_dcf");
  const dcfScenarios = report.scenarios.filter((s) => s.scenario.endsWith("_dcf") || s.scenario.includes("dcf"));
  const analyst = report.scenarios.filter((s) => s.scenario.startsWith("analyst"));
  const cov = report.dcf_coverage;

  return (
    <div className="est-panel">
      <header className="est-hero">
        <div>
          <div className="est-kicker">
            {report.profile_name} · {report.total_symbols} {t("estimates.symbols")}
          </div>
          <div className={`est-upside ${(base?.implied_upside_bps ?? 0) >= 0 ? "pos" : "neg"}`}>
            {base ? upsideLabel(base.implied_upside_bps) : "—"}
          </div>
          <div className="muted">{t("estimates.baseUpside")}</div>
        </div>
        <div className="est-coverage">
          <span className={`est-chip status-${cov.status}`}>{cov.status}</span>
          <div className="muted">
            DCF {cov.covered_symbols}/{cov.total_eligible_symbols} (
            {(cov.coverage_bps / 100).toFixed(0)}%)
          </div>
        </div>
      </header>

      {cov.status !== "ready" && (
        <div className="est-banner">
          {t("estimates.coverageBanner")} {cov.covered_symbols}/{cov.total_eligible_symbols}
        </div>
      )}

      <div className="est-grid">
        <ScenarioCard title={t("estimates.internalDcf")} items={dcfScenarios} />
        <ScenarioCard title={t("estimates.wallStreet")} items={analyst} />
      </div>
    </div>
  );
}
