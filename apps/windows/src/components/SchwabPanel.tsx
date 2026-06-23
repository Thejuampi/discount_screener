import { useEffect, useState } from "react";
import { api, fmt } from "../api";
import type { SchwabReport } from "../api";
import { useT } from "../i18n";

interface Props {
  symbol: string;
}

const RATING_COLORS: Record<string, { bg: string; color: string; label: string }> = {
  A: { bg: "linear-gradient(135deg, #16a34a, #15803d)", color: "#fff",     label: "A" },
  B: { bg: "linear-gradient(135deg, #4ade80, #22c55e)", color: "#0a0e1c",  label: "B" },
  C: { bg: "linear-gradient(135deg, #fbbf24, #f59e0b)", color: "#0a0e1c",  label: "C" },
  D: { bg: "linear-gradient(135deg, #fb923c, #f97316)", color: "#fff",     label: "D" },
  F: { bg: "linear-gradient(135deg, #f43f5e, #be123c)", color: "#fff",     label: "F" },
};

const SUBGRADE_COLOR: Record<string, string> = {
  A: "var(--success)",
  B: "#4ade80",
  C: "var(--warning)",
  D: "#fb923c",
  F: "var(--danger)",
};

export function SchwabPanel({ symbol }: Props) {
  const { t } = useT();
  const [report, setReport] = useState<SchwabReport | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    api.getSchwabReport(symbol)
      .then((r) => { setReport(r); setLoading(false); })
      .catch((e) => { console.error(e); setLoading(false); });
  }, [symbol]);

  // Hide the entire panel when no report is imported for this symbol
  if (loading || !report) return null;

  const rc = RATING_COLORS[report.rating] ?? RATING_COLORS["C"];
  const reportDate = report.report_date ?? "—";

  // Freshness check
  const daysSinceImport = Math.floor((Date.now() / 1000 - report.imported_at_epoch) / 86400);
  const isStale = daysSinceImport > 14;

  return (
    <div className="info-section schwab-panel">
      <h3>{t("schwab.title")}</h3>

      {/* Primary rating hero */}
      <div className="schwab-hero">
        <div
          className="schwab-rating-big"
          style={{ background: rc.bg, color: rc.color, boxShadow: `0 0 0 1px rgba(255,255,255,0.08), 0 4px 16px ${rc.color === "#fff" ? "rgba(0,0,0,0.3)" : "rgba(0,0,0,0.15)"}` }}
          title={report.rating_label}
        >
          {rc.label}
        </div>
        <div className="schwab-hero-info">
          <div className="schwab-rating-label">{report.rating_label}</div>
          <div className="schwab-meta">
            {report.percentile != null && (
              <span>{t("schwab.percentile")}: <strong>{report.percentile}</strong></span>
            )}
            {report.previous_rating && report.previous_rating !== report.rating && (
              <span>· {t("schwab.previousRating")}: <strong>{report.previous_rating}</strong></span>
            )}
            {report.previous_rating && report.previous_rating === report.rating && (
              <span style={{ color: "var(--text-4)" }}>· sin cambio</span>
            )}
          </div>
          <div className="schwab-dates">
            {report.data_as_of && <>{t("schwab.reportDate")}: {reportDate}</>}
          </div>
        </div>
      </div>

      {isStale && (
        <div className="schwab-stale-warning">
          {t("schwab.staleWarning", { days: daysSinceImport })}
        </div>
      )}

      {/* Sub-grades */}
      <div className="schwab-subgrades">
        <div className="schwab-subgrades-label">{t("schwab.subgrades")}</div>
        <div className="schwab-subgrade-row">
          <SubGrade name={t("schwab.growth")}     grade={report.growth_grade} />
          <SubGrade name={t("schwab.quality")}    grade={report.quality_grade} />
          <SubGrade name={t("schwab.sentiment")}  grade={report.sentiment_grade} />
          <SubGrade name={t("schwab.stability")}  grade={report.stability_grade} />
          <SubGrade name={t("schwab.valuation")}  grade={report.valuation_grade} />
        </div>
      </div>

      {/* Key metrics */}
      <div className="kv-grid" style={{ marginTop: 8 }}>
        {report.price_at_report_cents != null && (
          <>
            <span>{t("schwab.priceAtReport")}</span>
            <span>{fmt.dollars(report.price_at_report_cents)}</span>
          </>
        )}
        {report.beta != null && (
          <>
            <span>Beta</span>
            <span>{report.beta.toFixed(2)}</span>
          </>
        )}
        {report.market_cap_billions != null && (
          <>
            <span>Market Cap</span>
            <span>${report.market_cap_billions.toFixed(1)}B</span>
          </>
        )}
        {report.price_volatility && (
          <>
            <span>{t("schwab.priceVolatility")}</span>
            <span>{report.price_volatility}</span>
          </>
        )}
        {report.eps_forecast_y1 != null && (
          <>
            <span>{t("schwab.epsForecast")} Y1</span>
            <span>${report.eps_forecast_y1.toFixed(2)}</span>
          </>
        )}
        {report.eps_forecast_y2 != null && (
          <>
            <span>{t("schwab.epsForecast")} Y2</span>
            <span>${report.eps_forecast_y2.toFixed(2)}</span>
          </>
        )}
        {report.eps_growth_5yr_pct != null && (
          <>
            <span>{t("schwab.growth5yr")}</span>
            <span>{report.eps_growth_5yr_pct.toFixed(1)}%</span>
          </>
        )}
        {report.esg_rating && (
          <>
            <span>{t("schwab.esg")}</span>
            <span>{report.esg_rating}</span>
          </>
        )}
      </div>

      <div className="schwab-footer">
        {t("schwab.imported")} {t("schwab.daysAgo", { n: daysSinceImport })}
        {report.source_filename && <> · {report.source_filename}</>}
      </div>
    </div>
  );
}

function SubGrade({ name, grade }: { name: string; grade: string | null }) {
  const color = grade ? SUBGRADE_COLOR[grade] ?? "var(--text-4)" : "var(--text-5)";
  return (
    <div className="schwab-subgrade">
      <div className="schwab-subgrade-name">{name}</div>
      <div className="schwab-subgrade-badge" style={{ color, borderColor: color }}>
        {grade ?? "—"}
      </div>
    </div>
  );
}
