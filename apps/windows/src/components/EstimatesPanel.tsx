import { useCallback, useEffect, useState } from "react";
import { api, fmt } from "../api";
import type {
  IndexEstimatesReport,
  ValuationDivergenceAudit,
} from "../api";
import { useT } from "../i18n";
import { UI, UiInspectable } from "../uiInspect";
import { analystPriceGaps, type AnalystPriceGap } from "../estimatesPresentation";
import { singleFlight } from "../singleFlight";

function priceGapLabel(bps: number): string {
  const pct = (bps / 100).toFixed(1);
  return bps > 0 ? `+${pct}%` : `${pct}%`;
}

function AnalystEstimates({ low, high }: { low: AnalystPriceGap | null; high: AnalystPriceGap | null }) {
  const { t } = useT();
  const endpoints = [
    { side: "low", label: t("estimates.analystLow"), gap: low },
    { side: "high", label: t("estimates.analystHigh"), gap: high },
  ];
  return (
    <UiInspectable
      as="section"
      className="est-card"
      data-estimates-analysts
      source={UI.estimatesScenario}
      snapshot={{ low, high, timedReturnBps: null, targetDate: null }}
    >
      <h3>{t("estimates.wallStreet")}</h3>
      <div data-estimates-price-gap>
        <h4>{t("estimates.priceGap")}</h4>
        {low || high ? (
          <dl className="est-scenarios" data-estimates-range={low && high ? "" : undefined}>
            {endpoints.map(({ side, label, gap }) => (
              <div key={side} className="est-scenario-row" data-estimates-endpoint={side}>
                <dt>{label}</dt>
                <dd>
                  {gap ? (
                    <strong className={gap.priceGapBps < 0 ? "neg" : gap.priceGapBps > 0 ? "pos" : undefined}>
                      {priceGapLabel(gap.priceGapBps)}
                    </strong>
                  ) : t("estimates.unavailable")}
                </dd>
                {gap && (
                  <dd className="muted">
                    {t(gap.coverageCount === 1 ? "estimates.targetCoverageOne" : "estimates.targetCoverage", { count: gap.coverageCount })}
                  </dd>
                )}
              </div>
            ))}
          </dl>
        ) : (
          <p className="muted">{t("estimates.noAnalystCoverage")}</p>
        )}
      </div>
      <div className="est-timed-return" data-estimates-timed-return>
        <h4>{t("estimates.timedReturn")}</h4>
        <p className="muted">{t("estimates.timedReturnUnavailable")}</p>
      </div>
    </UiInspectable>
  );
}

export function EstimatesPanel() {
  const { t } = useT();
  const [report, setReport] = useState<IndexEstimatesReport | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [audit, setAudit] = useState<ValuationDivergenceAudit | null>(null);
  const [auditErr, setAuditErr] = useState<string | null>(null);
  const [auditLoading, setAuditLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const load = singleFlight(async () => {
      try {
        const r = await api.getIndexEstimates();
        if (cancelled) return;
        setReport(r);
        setErr(null);
      } catch (e) {
        if (!cancelled) setErr(String(e));
      }
    });
    void load();
    const id = setInterval(load, 15_000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, []);

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

  const analyst = analystPriceGaps(report.scenarios);
  const cov = report.dcf_coverage;

  return (
    <UiInspectable
      as="div"
      className="est-panel"
      source={UI.estimatesRoot}
      snapshot={{
        profileName: report.profile_name,
        totalSymbols: report.total_symbols,
        dcfCoverageStatus: cov.status,
        dcfCovered: cov.covered_symbols,
        dcfEligible: cov.total_eligible_symbols,
        analystLowCoverage: analyst.low?.coverageCount ?? 0,
        analystHighCoverage: analyst.high?.coverageCount ?? 0,
      }}
    >
      <header className="est-hero">
        <div>
          <h2 className="est-profile">{report.profile_name.toUpperCase()}</h2>
          <div className="muted">{report.total_symbols} {t("estimates.symbols")}</div>
        </div>
        <div className="est-coverage" data-estimates-dcf-coverage>
          <span className={`est-chip status-${cov.status}`}>{t(`estimates.dcfCoverage.${cov.status}`)}</span>
          <div className="muted">
            {t("estimates.dcfCoverage", { covered: cov.covered_symbols, total: cov.total_eligible_symbols })}
          </div>
        </div>
      </header>

      <AnalystEstimates {...analyst} />

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
    </UiInspectable>
  );
}
