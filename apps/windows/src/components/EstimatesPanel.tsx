import { useCallback, useEffect, useState } from "react";
import { api, fmt } from "../api";
import type {
  IndexEstimatesReport,
  ScenarioEstimate,
  ValuationDivergenceAudit,
} from "../api";
import { useT } from "../i18n";
import { UI, UiInspectable } from "../uiInspect";

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
  const [audit, setAudit] = useState<ValuationDivergenceAudit | null>(null);
  const [auditErr, setAuditErr] = useState<string | null>(null);
  const [auditLoading, setAuditLoading] = useState(false);

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

  const runAudit = useCallback(async () => {
    setAuditLoading(true);
    setAuditErr(null);
    try {
      setAudit(await api.runQaValuationDivergenceAudit());
    } catch (e) {
      setAuditErr(String(e));
    } finally {
      setAuditLoading(false);
    }
  }, []);

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
    <UiInspectable
      as="div"
      className="est-panel"
      source={UI.estimatesRoot}
      snapshot={{
        profileName: report.profile_name,
        totalSymbols: report.total_symbols,
        baseUpsideBps: base?.implied_upside_bps ?? null,
        dcfCoverageStatus: cov.status,
        dcfCovered: cov.covered_symbols,
        dcfEligible: cov.total_eligible_symbols,
        scenarioCount: report.scenarios.length,
      }}
    >
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

      <section className="est-card est-audit-card">
        <div className="est-audit-header">
          <div>
            <h3>{t("estimates.audit.title")}</h3>
            <div className="muted">{t("estimates.audit.policy")}: {audit?.model_policy_version ?? "—"}</div>
          </div>
          <button className="btn-ghost" onClick={() => void runAudit()} disabled={auditLoading}>
            {auditLoading ? t("estimates.audit.running") : t("estimates.audit.run")}
          </button>
        </div>
        {auditErr && <div className="est-banner">{t("estimates.error")}: {auditErr}</div>}
        {!audit && !auditErr && <div className="muted">{t("estimates.audit.empty")}</div>}
        {audit && (
          <>
            <div className="muted est-audit-summary">
              {audit.profile_name} · {audit.candidate_count} candidatos · {audit.comparable_count} comparables · {audit.unavailable_count} sin comparación
            </div>
            <div className="est-audit-list">
              {audit.rows.map((row) => (
                <details key={row.symbol} className="est-audit-row">
                  <summary>
                    <span className="est-audit-rank">#{row.rank}</span>
                    <strong>{row.symbol}</strong>
                    <span>{fmt.dollars(row.dcf_value_cents)} vs {fmt.dollars(row.analyst_value_cents)}</span>
                    <strong className={row.direction === "dcf_above_analyst" ? "pos" : "neg"}>
                      {(row.relative_disagreement_bps / 100).toFixed(1)}%
                    </strong>
                    <span className="muted">{row.primary_cause}</span>
                  </summary>
                  <div className="est-audit-detail">
                    <div><strong>{t("estimates.audit.cause")}:</strong> {row.causes.join(", ")}</div>
                    <div><strong>{t("estimates.audit.evidence")}:</strong> {row.evidence.join(" · ")}</div>
                  </div>
                </details>
              ))}
            </div>
            {audit.unavailable.length > 0 && (
              <div className="est-audit-unavailable">
                <strong>{t("estimates.audit.unavailableReason")}</strong>
                {audit.unavailable.map((item) => (
                  <div key={item.symbol}>
                    <strong>{item.symbol}</strong>: {item.reason}
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </section>

      <div className="est-grid">
        <ScenarioCard title={t("estimates.internalDcf")} items={dcfScenarios} />
        <ScenarioCard title={t("estimates.wallStreet")} items={analyst} />
      </div>
    </UiInspectable>
  );
}
