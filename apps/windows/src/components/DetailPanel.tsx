import { useState, useEffect } from "react";
import { api, fmt } from "../api";
import type { SymbolDetail, OpportunityRow, Decision, PriceProvenance } from "../api";
import { CandleChart } from "./CandleChart";
import { HistoryChart } from "./HistoryChart";
import { TechnicalAnalysisPanel } from "./TechnicalAnalysisPanel";
import type { Profile } from "./TechnicalAnalysisPanel";
import { NewsPanel } from "./NewsPanel";
import { SchwabPanel } from "./SchwabPanel";
import { CongressStockPanel } from "./CongressStockPanel";
import { CryptoCyclePanel } from "./CryptoCyclePanel";
import { ChartPatterns } from "./ChartPatterns";
import { FibLevels } from "./FibLevels";
import { QuantLensPanel } from "./QuantLensPanel";
import { useT } from "../i18n";
import type { DcfAnalysis } from "../api";

function waccLabels(a: DcfAnalysis): string[] {
  const labels: string[] = [];
  const i = a.wacc_inputs;
  if (i.market_cap === "derived_price_times_shares") labels.push("market cap=price×shares");
  if (i.beta === "default") labels.push("beta=default");
  if (i.total_debt === "assumed_zero") labels.push("debt=assumed 0");
  if (i.total_cash === "assumed_zero") labels.push("cash=assumed 0");
  if (i.cost_of_debt === "default") labels.push("cost of debt=default");
  if (i.tax_rate === "default") labels.push("tax=default");
  if (i.wacc_clamped) labels.push("wacc=clamped");
  return labels;
}

interface Props {
  symbol: string;
  row: OpportunityRow | null;
  profile: Profile;
  onProfileChange: (p: Profile) => void;
  onClose: () => void;
}

const RANGES = ["1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y"];

export function DetailPanel({ symbol, row, profile, onProfileChange, onClose }: Props) {
  const { t } = useT();
  const [detail, setDetail] = useState<SymbolDetail | null>(null);
  const [range, setRange] = useState("3mo");
  const [loading, setLoading] = useState(true);
  const [showPat, setShowPat] = useState(() => localStorage.getItem("ds_detail_pat") !== "0");
  const [showFib, setShowFib] = useState(() => localStorage.getItem("ds_detail_fib") !== "0");
  const [showEma, setShowEma] = useState(() => localStorage.getItem("ds_detail_ema") !== "0");
  const [showVol, setShowVol] = useState(() => localStorage.getItem("ds_detail_vol") !== "0");

  // Reset detail state immediately when symbol changes so we never show stale
  // data from the previously selected symbol. Use a cancellation flag so rapid
  // symbol changes don't race-condition the latest fetch.
  useEffect(() => {
    setLoading(true);
    setDetail(null);   // ← clear stale data
    let cancelled = false;

    api.getSymbolDetail(symbol)
      .then((d) => {
        if (!cancelled) { setDetail(d); setLoading(false); }
      })
      .catch(console.error);

    // Fast path (quote + daily) returns ASAP; multi-TF continues in background.
    // Poll a few times so weekly/hourly/monthly sections fill in without blocking.
    const refreshDetail = () =>
      api.getSymbolDetail(symbol).then((d) => {
        if (!cancelled) {
          if (d) setDetail(d);
          setLoading(false);
        }
      });

    api.ensureSymbolLoaded(symbol)
      .then(() => refreshDetail())
      .catch(() =>
        api.refreshSymbol(symbol)
          .then(() => refreshDetail())
          .catch(console.error),
      );

    const t1 = window.setTimeout(() => { void refreshDetail().catch(() => {}); }, 1200);
    const t2 = window.setTimeout(() => { void refreshDetail().catch(() => {}); }, 3500);

    return () => {
      cancelled = true;
      window.clearTimeout(t1);
      window.clearTimeout(t2);
    };
  }, [symbol]);

  // ── Render with whatever data is available immediately ──────────────────────
  // We prefer row data (synchronous from the parent's list) over detail.
  // Only the deep analyst/fundamentals/technical sections wait for `detail`.

  const companyName = row?.company_name ?? detail?.company_name ?? "";
  const marketPrice = row?.market_price_cents ?? detail?.market_price_cents ?? 0;
  const targetPrice = row?.intrinsic_value_cents ?? detail?.intrinsic_value_cents ?? 0;
  const gap = row?.gap_bps ?? detail?.gap_bps ?? null;
  const confidence = row?.confidence ?? detail?.confidence ?? "Low";
  const dcfValue = row?.dcf_value_cents ?? detail?.dcf_value_cents ?? null;
  const dcfAnalysis = detail?.dcf_analysis ?? null;

  const f = detail?.fundamentals;
  const isOpportunity = gap != null && gap >= 1000;
  const hasValidData = marketPrice > 0;

  // If we have absolutely nothing yet (no row, no detail) show a loader
  if (!hasValidData && !detail && loading) {
    return (
      <div className="detail-panel">
        <div className="detail-header">
          <span>{symbol}</span>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>
        <div className="loading-msg">{t("detail.loading")} {symbol}…</div>
      </div>
    );
  }

  return (
    <div className="detail-panel">
      <div className="detail-header">
        <div>
          <span className="detail-symbol">{symbol}</span>
          {companyName && <span className="detail-company">{companyName}</span>}
        </div>
        <button className="close-btn" onClick={onClose}>✕</button>
      </div>

      {/* Price summary — uses row data (immediate) when available */}
      <div className="price-summary">
        <div className="price-main">
          <span className="price-value">
            {hasValidData ? fmt.dollars(marketPrice) : "—"}
            {row?.daily_change_bps != null && (
              <span style={{
                fontSize: 13,
                marginLeft: 8,
                fontWeight: 600,
                color: row.daily_change_bps > 0 ? "var(--success)"
                  : row.daily_change_bps < 0 ? "var(--danger)" : "var(--text-4)",
              }}>
                {row.daily_change_bps > 0 ? "▲ +" : row.daily_change_bps < 0 ? "▼ " : ""}
                {(row.daily_change_bps / 100).toFixed(2)}%
              </span>
            )}
          </span>
          <span className="price-label">{t("detail.marketPrice")}</span>
        </div>
        <div className="price-arrow">→</div>
        <div className="price-main">
          <span className="price-value">{targetPrice > 0 ? fmt.dollars(targetPrice) : "—"}</span>
          <span className="price-label">{t("detail.analystTarget")}</span>
        </div>
        {dcfValue && dcfValue > 0 && (
          <>
            <div className="price-arrow">|</div>
            <div className="price-main">
              <span className="price-value" style={{ color: dcfValue > marketPrice ? "#22c55e" : "#f87171" }}>
                {fmt.dollars(dcfValue)}
              </span>
              <span className="price-label">{t("detail.dcfValue")}</span>
              {dcfAnalysis && (
                <div className="wacc-block">
                  <div>
                    WACC {(dcfAnalysis.wacc_bps / 100).toFixed(2)}%
                    {waccLabels(dcfAnalysis).length > 0 && (
                      <span className="wacc-provisional"> · {t("detail.provisional")}</span>
                    )}
                  </div>
                  <div className="muted small">
                    Bear {fmt.dollars(dcfAnalysis.bear_intrinsic_value_cents)} · Base{" "}
                    {fmt.dollars(dcfAnalysis.base_intrinsic_value_cents)} · Bull{" "}
                    {fmt.dollars(dcfAnalysis.bull_intrinsic_value_cents)}
                  </div>
                  {waccLabels(dcfAnalysis).length > 0 && (
                    <div className="muted small">
                      {t("detail.waccInputs")}: {waccLabels(dcfAnalysis).join("; ")}
                    </div>
                  )}
                </div>
              )}
            </div>
          </>
        )}
        {hasValidData && (
          <div className={`gap-pill ${isOpportunity ? "gap-green" : "gap-grey"}`}>
            {gap == null ? "—" : `${isOpportunity ? "▲" : "▼"} ${fmt.gap(gap)}`}{" "}
            {t("detail.gap")}
          </div>
        )}
        <div className={`conf-pill conf-${confidence.toLowerCase()}`}>
          {t(`conf.${confidence}`)}
        </div>
      </div>

      {/* Price provenance — cross-source verification badge */}
      <ProvenanceBadge symbol={symbol} />

      {/* Earnings warning — only when a date is known and upcoming within 21 days */}
      {(() => {
        const epoch = row?.next_earnings_epoch ?? detail?.next_earnings_epoch ?? null;
        if (epoch == null) return null;
        const days = Math.floor((epoch * 1000 - Date.now()) / 86_400_000);
        if (days < 0 || days > 21) return null;
        const imminent = days <= 5;
        const dateStr = new Date(epoch * 1000).toLocaleDateString();
        return (
          <div title={t("detail.earnings.date", { date: dateStr })} style={{
            margin: "10px 0", padding: "7px 12px", borderRadius: 8, fontSize: 12, fontWeight: 600,
            background: imminent ? "rgba(244,63,94,0.12)" : "rgba(245,158,11,0.10)",
            border: `1px solid ${imminent ? "rgba(244,63,94,0.4)" : "rgba(245,158,11,0.35)"}`,
            color: imminent ? "var(--danger)" : "var(--warning)",
          }}>
            {t(imminent ? "detail.earnings.imminent" : "detail.earnings.in", { days })}
          </div>
        );
      })()}

      {/* Crypto cycle analysis — shown only for crypto symbols, right after price */}
      <CryptoCyclePanel symbol={symbol} isCrypto={row?.asset_type === "crypto"} />

      {/* ── Analysis summary — needs both row and detail for full narrative ── */}
      {row && detail && <AnalysisSummary row={row} detail={detail} />}

      {/* Chart */}
      <div className="chart-section">
        <div className="range-tabs" style={{ alignItems: "center" }}>
          {RANGES.map((r) => (
            <button
              key={r}
              className={`range-tab ${range === r ? "active" : ""}`}
              onClick={() => setRange(r)}
            >
              {r}
            </button>
          ))}
          <div style={{ display: "flex", gap: 12, marginLeft: "auto", fontSize: 11, color: "var(--text-3)" }}>
            <label style={{ display: "flex", alignItems: "center", gap: 4, cursor: "pointer" }}>
              <input type="checkbox" checked={showPat} onChange={(e) => { setShowPat(e.target.checked); localStorage.setItem("ds_detail_pat", e.target.checked ? "1" : "0"); }} />
              {t("chart.toggle.patterns")}
            </label>
            <label style={{ display: "flex", alignItems: "center", gap: 4, cursor: "pointer" }}>
              <input type="checkbox" checked={showFib} onChange={(e) => { setShowFib(e.target.checked); localStorage.setItem("ds_detail_fib", e.target.checked ? "1" : "0"); }} />
              {t("chart.toggle.fib")}
            </label>
            <label style={{ display: "flex", alignItems: "center", gap: 4, cursor: "pointer" }}>
              <input type="checkbox" checked={showEma} onChange={(e) => { setShowEma(e.target.checked); localStorage.setItem("ds_detail_ema", e.target.checked ? "1" : "0"); }} />
              {t("chart.toggle.ema")}
            </label>
            <label style={{ display: "flex", alignItems: "center", gap: 4, cursor: "pointer" }}>
              <input type="checkbox" checked={showVol} onChange={(e) => { setShowVol(e.target.checked); localStorage.setItem("ds_detail_vol", e.target.checked ? "1" : "0"); }} />
              {t("chart.toggle.volume")}
            </label>
          </div>
        </div>
        <CandleChart symbol={symbol} range={range} patterns={showPat ? detail?.chart_patterns : undefined} fib={showFib ? detail?.fib : null} ema={showEma} volume={showVol} />
      </div>

      {/* Chart patterns (classic TA structures, from daily candles) */}
      {detail && detail.chart_patterns.length > 0 && (
        <div className="info-section">
          <h3>{t("pat.title")}</h3>
          <ChartPatterns patterns={detail.chart_patterns} />
        </div>
      )}

      {/* Fibonacci levels (from daily candles) */}
      {detail?.fib && (
        <div className="info-section">
          <h3>{t("fib.title")}</h3>
          <FibLevels fib={detail.fib} />
        </div>
      )}

      {/* Score history */}
      <HistoryChart symbol={symbol} />

      {/* News + sentiment */}
      <NewsPanel symbol={symbol} />

      {/* Schwab Equity Ratings (if imported) */}
      <SchwabPanel symbol={symbol} />

      {/* Congressional trading activity (if any) */}
      <CongressStockPanel symbol={symbol} />

      {/* Multi-TF technical analysis — only when detail is loaded */}
      {detail && (
        <TechnicalAnalysisPanel
          weekly={detail.weekly_summary}
          daily={detail.chart_summary}
          hourly={detail.hourly_summary}
          monthly={detail.monthly_summary}
          breakdown={detail.technical_breakdown}
          profile={profile}
          onProfileChange={onProfileChange}
        />
      )}

      {/* Analyst breakdown — needs detail */}
      {detail && f && (
        <div className="section-grid">
          <div className="info-section">
            <h3>{t("detail.analystConsensus")}</h3>
            <div className="kv-grid">
              <span>{t("detail.opinionCount")}</span><span>{detail.analyst_opinion_count ?? "—"}</span>
              <span>{t("detail.recommendation")}</span><span>{fmt.recMean(detail.recommendation_mean_hundredths)}</span>
              <span>{t("detail.targetLow")}</span><span>{detail.low_fair_value_cents ? fmt.dollars(detail.low_fair_value_cents) : "—"}</span>
              <span>{t("detail.targetHigh")}</span><span>{detail.high_fair_value_cents ? fmt.dollars(detail.high_fair_value_cents) : "—"}</span>
              <span>{t("detail.signalAge")}</span><span>{detail.signal_age_seconds != null ? `${Math.round(detail.signal_age_seconds / 3600)}h` : "—"}</span>
            </div>
            {detail.strong_buy_count != null && <RecommendationBar detail={detail} />}
            <InsiderActivity detail={detail} />
          </div>

          <div className="info-section">
            <h3>{t("detail.fundamentals")}</h3>
            <div className="kv-grid">
              <span>{t("detail.sector")}</span><span>{f.sector_name ?? "—"}</span>
              <span>{t("detail.industry")}</span><span>{f.industry_name ?? "—"}</span>
              <span>{t("detail.marketCap")}</span><span>{fmt.billions(f.market_cap_dollars ?? null)}</span>
              <span>{t("detail.trailingPe")}</span><span>{fmt.pe(f.trailing_pe_hundredths ?? null)}</span>
              <span>{t("detail.forwardPe")}</span><span>{fmt.pe(f.forward_pe_hundredths ?? null)}</span>
              <span>{t("detail.roe")}</span><span>{fmt.roe(f.return_on_equity_bps ?? null)}</span>
              <span>{t("detail.debtEquity")}</span><span>{f.debt_to_equity_hundredths != null ? (f.debt_to_equity_hundredths / 100).toFixed(2) : "—"}</span>
              <span>{t("detail.fcf")}</span><span>{fmt.billions(f.free_cash_flow_dollars ?? null)}</span>
              <span>{t("detail.beta")}</span><span>{f.beta_millis != null ? (f.beta_millis / 1000).toFixed(2) : "—"}</span>
              <span>{t("detail.eps")}</span><span>{f.trailing_eps_cents != null ? fmt.dollars(f.trailing_eps_cents) : "—"}</span>
              <span>{t("detail.earningsGrowth")}</span><span>{f.earnings_growth_bps != null ? `${(f.earnings_growth_bps / 100).toFixed(1)}%` : "—"}</span>
            </div>
          </div>
        </div>
      )}

      <QuantLensPanel symbol={symbol} />

      {/* ── Full analysis breakdown (bottom) ── */}
      {row && detail && <AnalysisBuckets row={row} detail={detail} />}
    </div>
  );
}

// ── Price provenance badge ────────────────────────────────────────────────────

/** Cross-source price verification: green when sources agree, red on conflict. */
function ProvenanceBadge({ symbol }: { symbol: string }) {
  const { t } = useT();
  const [p, setP] = useState<PriceProvenance | null>(null);

  useEffect(() => {
    let cancelled = false;
    setP(null);
    api.getPriceProvenance(symbol).then((x) => { if (!cancelled) setP(x); }).catch(() => {});
    return () => { cancelled = true; };
  }, [symbol]);

  if (!p) return null;
  const sources = [
    p.schwab_cents != null ? "Schwab" : null,
    p.yahoo_cents != null ? "Yahoo" : null,
    p.stooq_cents != null ? "Stooq" : null,
  ].filter(Boolean) as string[];

  let text: string;
  let color: string;
  if (p.sources_ok === 0) { text = t("prov.none"); color = "var(--text-5)"; }
  else if (p.sources_ok === 1) { text = t("prov.single"); color = "var(--warning)"; }
  else if (!p.agree) { text = t("prov.conflict", { spread: ((p.spread_bps ?? 0) / 100).toFixed(1) }); color = "var(--danger)"; }
  else { text = t("prov.agree", { n: p.sources_ok }); color = "var(--success)"; }

  return (
    <div style={{ fontSize: 10, color: "var(--text-5)", margin: "2px 0 8px", display: "flex", gap: 6, alignItems: "center", flexWrap: "wrap" }}>
      <span style={{ textTransform: "uppercase", letterSpacing: "0.04em" }}>{t("prov.label")}:</span>
      <span style={{ color, fontWeight: 600 }}>{text}</span>
      {sources.length > 0 && <span>· {sources.join(" · ")}</span>}
    </div>
  );
}

// ── Investment Analysis ───────────────────────────────────────────────────────

const DECISION_HEADER: Record<Decision, { emoji: string; color: string; titleKey: string }> = {
  Act:   { emoji: "✅", color: "#22c55e", titleKey: "analysis.goodMoment" },
  Watch: { emoji: "👁️", color: "#f59e0b", titleKey: "analysis.watching" },
  Avoid: { emoji: "🚫", color: "#ef4444", titleKey: "analysis.avoid" },
};

/** Top section: verdict header + summary paragraph + reasoning. */
function AnalysisSummary({ row, detail }: { row: OpportunityRow; detail: SymbolDetail }) {
  const { t } = useT();
  const dh = DECISION_HEADER[row.decision];
  const { summary } = buildReasons(row, detail, t);
  const reason = decisionReason(row, detail, t);
  const gapLabel = fmt.gap(row.gap_bps);
  const gapColor =
    row.gap_bps == null
      ? "var(--text-4)"
      : row.gap_bps > 0
        ? "var(--success)"
        : "var(--danger)";

  return (
    <div className="analysis-box" style={{ borderColor: dh.color }}>
      <div className="analysis-header" style={{ color: dh.color }}>
        <span className="analysis-emoji">{dh.emoji}</span>
        <span className="analysis-title">{t(dh.titleKey)}</span>
        <span className="analysis-score">{t("analysis.score")}: {row.composite_score}</span>
      </div>
      <p className="analysis-summary">{summary}</p>
      {reason && (
        <div className="decision-reason" style={{ borderTopColor: dh.color }}>
          <div className="decision-reason-label">{t("reason.title")}</div>
          <p className="decision-reason-text">{reason}</p>
          <div className="decision-reason-meta">
            <span>Score: <strong style={{ color: dh.color }}>{row.composite_score}</strong></span>
            <span>·</span>
            <span>Gap: <strong style={{ color: gapColor }}>{gapLabel}</strong></span>
            <span>·</span>
            <span>Confianza: <strong>{t(`conf.${detail.confidence}`)}</strong></span>
          </div>
        </div>
      )}
    </div>
  );
}

/** Build a Spanish/English explanation of WHY the verdict came out the way it did. */
function decisionReason(row: OpportunityRow, detail: SymbolDetail, t: TFn): string {
  const cs = row.composite_score;
  const gapPts = row.gap_bps == null ? null : (row.gap_bps / 100).toFixed(1);
  const ts = row.technical_score ?? 0;
  const isCryptoOrEtf = row.asset_type === "crypto" || row.asset_type === "etf";

  // Crypto / ETF path — decided purely on technical
  if (isCryptoOrEtf) {
    if (row.decision === "Act")   return t("reason.act.crypto",   { ts });
    if (row.decision === "Watch") return t("reason.watch.crypto", { ts });
    return t("reason.avoid.crypto", { ts });
  }

  // Stock path — multi-criteria
  if (row.decision === "Avoid") {
    // Priority: gap, confidence, score — pick the most informative
    if (row.gap_bps == null)          return t("reason.avoid.noTarget");
    if (row.gap_bps <= 0)             return t("reason.avoid.gap",     { gap: gapPts! });
    if (detail.confidence === "Low")  return t("reason.avoid.confLow");
    if (cs < 8)                       return t("reason.avoid.score",   { cs });
    return t("reason.avoid.score", { cs });
  }
  if (row.decision === "Watch") return t("reason.watch", { cs });
  if (gapPts != null && row.gap_bps != null && row.gap_bps > 0) {
    return t("reason.act", { cs, gap: gapPts });
  }
  return t("reason.act.noTarget", { cs });
}

/** Bottom section: 3-column breakdown */
function AnalysisBuckets({ row, detail }: { row: OpportunityRow; detail: SymbolDetail }) {
  const { t } = useT();
  const reasons = buildReasons(row, detail, t);
  return (
    <div className="analysis-buckets-wrap">
      <AnalysisBucket label={t("analysis.fundamentals")} score={row.fundamentals_score} points={reasons.fundamentals} signals={row.fundamentals_signals} />
      <AnalysisBucket label={t("analysis.technical")}    score={row.technical_score}    points={reasons.technical}    signals={row.technical_signals} />
      <AnalysisBucket label={t("analysis.forecast")}     score={row.forecast_score}     points={reasons.forecast}     signals={row.forecast_signals} />
    </div>
  );
}

function AnalysisBucket({
  label, score, points, signals,
}: {
  label: string;
  score: number | null;
  points: string[];
  signals: string[];
}) {
  const { t } = useT();
  const color = score == null ? "#64748b"
    : score >= 30 ? "#22c55e"
    : score >= 0  ? "#f59e0b"
    : "#ef4444";

  return (
    <div className="analysis-bucket">
      <div className="bucket-header">
        <span className="bucket-label">{label}</span>
        <span className="bucket-score" style={{ color }}>
          {score == null ? t("analysis.noData") : score > 0 ? `+${score}` : `${score}`}
        </span>
      </div>
      {points.length > 0 && (
        <ul className="bucket-points">
          {points.map((p, i) => <li key={i}>{p}</li>)}
        </ul>
      )}
      {signals.length > 0 && (
        <div className="bucket-signals">
          {signals.map((s, i) => (
            <span key={i} className={`signal-chip ${signalClass(s)}`}>{s}</span>
          ))}
        </div>
      )}
    </div>
  );
}

function signalClass(signal: string): string {
  if (signal.endsWith("++")) return "sig-strong-pos";
  if (signal.endsWith("+"))  return "sig-pos";
  if (signal.endsWith("--")) return "sig-strong-neg";
  if (signal.endsWith("-"))  return "sig-neg";
  return "";
}

// ── Narrative builder ─────────────────────────────────────────────────────────

interface Reasons {
  summary: string;
  fundamentals: string[];
  technical: string[];
  forecast: string[];
}

type TFn = (key: string, vars?: Record<string, string | number>) => string;

function buildReasons(row: OpportunityRow, detail: SymbolDetail, t: TFn): Reasons {
  const f = detail.fundamentals;
  const name = detail.company_name ?? row.symbol;
  const gapPct =
    row.gap_bps == null ? null : Math.abs(row.gap_bps / 100).toFixed(0);
  const analystCount = detail.analyst_opinion_count ?? 0;
  const recLabel = fmt.recMean(detail.recommendation_mean_hundredths);
  const cs = row.composite_score;

  // ── Summary sentence ─────────────────────────────────────────────────────
  let summary = "";
  if (row.decision === "Act") {
    summary = t("ia.summary.act", { name, cs }) + " ";
    if (row.gap_bps != null && row.gap_bps >= 1000 && gapPct != null) {
      summary += t("ia.summary.act.gap", { gap: gapPct });
    }
  } else if (row.decision === "Watch") {
    summary = t("ia.summary.watch", { name, cs });
  } else {
    const why: string[] = [];
    if (detail.confidence === "Low") why.push(t("ia.why.lowConfidence"));
    if (row.gap_bps == null)         why.push(t("ia.why.noTarget"));
    else if (row.gap_bps <= 0)       why.push(t("ia.why.priceExceedsTarget"));
    if (cs < 8)                      why.push(t("ia.why.lowScore", { cs }));
    summary = t("ia.summary.avoid", { name, why: why.join(", ") });
  }

  // ── Fundamentals ─────────────────────────────────────────────────────────
  const fundPoints: string[] = [];
  if (f.free_cash_flow_dollars != null) {
    const fcf = f.free_cash_flow_dollars;
    fundPoints.push(fcf > 0
      ? t("ia.fund.fcfPositive", { fcf: fmt.billions(fcf) })
      : t("ia.fund.fcfNegative", { fcf: fmt.billions(fcf) }));
  }
  if (f.return_on_equity_bps != null) {
    const roe = (f.return_on_equity_bps / 100).toFixed(1);
    const roeNum = f.return_on_equity_bps / 100;
    if (roeNum >= 20)      fundPoints.push(t("ia.fund.roeExcellent",  { roe }));
    else if (roeNum >= 10) fundPoints.push(t("ia.fund.roeHealthy",    { roe }));
    else if (roeNum < 0)   fundPoints.push(t("ia.fund.roeNegative",   { roe }));
    else                   fundPoints.push(t("ia.fund.roeModerate",   { roe }));
  }
  if (f.earnings_growth_bps != null) {
    const g = (f.earnings_growth_bps / 100).toFixed(1);
    const gNum = f.earnings_growth_bps / 100;
    if (gNum > 15)     fundPoints.push(t("ia.fund.growthStrong",   { g }));
    else if (gNum > 0) fundPoints.push(t("ia.fund.growthPositive", { g }));
    else               fundPoints.push(t("ia.fund.growthNegative", { g }));
  }
  if (f.debt_to_equity_hundredths != null) {
    const de = (f.debt_to_equity_hundredths / 100).toFixed(2);
    const deNum = f.debt_to_equity_hundredths / 100;
    if (deNum <= 0.5)      fundPoints.push(t("ia.fund.debtLow",      { de }));
    else if (deNum <= 1.5) fundPoints.push(t("ia.fund.debtModerate", { de }));
    else                   fundPoints.push(t("ia.fund.debtHigh",     { de }));
  }
  if (f.forward_pe_hundredths != null && f.forward_pe_hundredths > 0) {
    const pe = (f.forward_pe_hundredths / 100).toFixed(1);
    const peNum = f.forward_pe_hundredths / 100;
    if (peNum < 15)      fundPoints.push(t("ia.fund.peAttractive", { pe }));
    else if (peNum < 25) fundPoints.push(t("ia.fund.peReasonable", { pe }));
    else                 fundPoints.push(t("ia.fund.peElevated",   { pe }));
  }
  if (fundPoints.length === 0) fundPoints.push(t("ia.fund.noData"));

  // ── Technical ────────────────────────────────────────────────────────────
  const techPoints: string[] = [];
  const ts = row.technical_score;
  if (ts == null) {
    techPoints.push(t("ia.tech.loading"));
  } else {
    const sigs = row.technical_signals;
    const find = (prefix: string) => sigs.find(s => s.startsWith(prefix));
    const hasPx20 = find("Px/20"), has2050 = find("20/50"), has50200 = find("50/200");
    const hasHist = find("Hist"), hasMACD = find("MACD");
    if (hasPx20)  techPoints.push(t(hasPx20.includes("+")  ? "ia.tech.px20Pos"  : "ia.tech.px20Neg"));
    if (has2050)  techPoints.push(t(has2050.includes("+")  ? "ia.tech.2050Pos"  : "ia.tech.2050Neg"));
    if (has50200) techPoints.push(t(has50200.includes("+") ? "ia.tech.50200Pos" : "ia.tech.50200Neg"));
    if (hasHist)  techPoints.push(t(hasHist.includes("+")  ? "ia.tech.histPos"  : "ia.tech.histNeg"));
    if (hasMACD)  techPoints.push(t(hasMACD.includes("+")  ? "ia.tech.macdPos"  : "ia.tech.macdNeg"));
  }

  // ── Forecast ─────────────────────────────────────────────────────────────
  const forePoints: string[] = [];
  if (analystCount === 0) {
    forePoints.push(t("ia.fore.noCoverage"));
  } else {
    forePoints.push(t("ia.fore.coverage", { n: analystCount, rec: recLabel }));
    if (row.gap_bps != null && gapPct != null) {
      forePoints.push(row.gap_bps > 0
        ? t("ia.fore.upside",   { gap: gapPct })
        : t("ia.fore.downside", { gap: gapPct }));
    } else {
      forePoints.push(t("ia.fore.noTarget"));
    }
    if (detail.low_fair_value_cents && detail.high_fair_value_cents && detail.intrinsic_value_cents > 0) {
      const sNum = Math.abs(
        (detail.high_fair_value_cents - detail.low_fair_value_cents) / detail.intrinsic_value_cents * 100
      );
      const s = sNum.toFixed(0);
      if (sNum < 40)      forePoints.push(t("ia.fore.spreadLow",      { s }));
      else if (sNum < 80) forePoints.push(t("ia.fore.spreadModerate", { s }));
      else                forePoints.push(t("ia.fore.spreadHigh",     { s }));
    }
  }
  if (row.forecast_score == null) forePoints.push(t("ia.fore.notCalculable"));

  // Insider activity narrative
  const ib = row.insider_buy_count;
  const is_ = row.insider_sell_count;
  if (ib != null && is_ != null && ib + is_ > 0) {
    if (ib >= 2 && ib >= is_)         forePoints.push(t("ia.fore.insiderBuys",  { n: ib }));
    else if (ib >= 1 && ib >= is_)    forePoints.push(t("ia.fore.insiderBuy",   { n: ib }));
    else if (is_ >= 5 && ib === 0)    forePoints.push(t("ia.fore.insiderSells", { n: is_ }));
  }

  return { summary, fundamentals: fundPoints, technical: techPoints, forecast: forePoints };
}

// ── Insider activity ──────────────────────────────────────────────────────────

function InsiderActivity({ detail }: { detail: SymbolDetail }) {
  const buys = detail.insider_buy_count;
  const sells = detail.insider_sell_count;
  const net = detail.insider_net_shares_90d;
  if (buys == null && sells == null) return null;
  const b = buys ?? 0;
  const s = sells ?? 0;
  if (b + s === 0) {
    return (
      <div style={{ marginTop: 10, fontSize: 11, color: "#64748b" }}>
        Sin Form 4 en últimos 90 días (insiders inactivos)
      </div>
    );
  }
  const tone =
    b >= 2 && b >= s ? { color: "#22c55e", label: "Insider buying" }
    : b >= 1 && b >= s ? { color: "#4ade80", label: "Insider activity (positiva)" }
    : s >= 5 && b === 0 ? { color: "#f87171", label: "Heavy insider selling" }
    : { color: "#94a3b8", label: "Mixed insider activity" };
  const netFmt =
    net == null ? "—"
    : net === 0 ? "neutral"
    : `${net > 0 ? "+" : ""}${(net / 1000).toFixed(1)}k shares`;
  return (
    <div style={{ marginTop: 10, fontSize: 11 }}>
      <span style={{ color: tone.color, fontWeight: 600 }}>{tone.label}</span>
      <span style={{ color: "#64748b", marginLeft: 8 }}>
        90d: {b} buys · {s} sells · net {netFmt}
      </span>
    </div>
  );
}

// ── Recommendation bar ────────────────────────────────────────────────────────

function RecommendationBar({ detail }: { detail: SymbolDetail }) {
  const sb = detail.strong_buy_count ?? 0;
  const b  = detail.buy_count ?? 0;
  const h  = detail.hold_count ?? 0;
  const s  = detail.sell_count ?? 0;
  const ss = detail.strong_sell_count ?? 0;
  const total = sb + b + h + s + ss;
  if (total === 0) return null;
  const pct = (n: number) => `${((n / total) * 100).toFixed(0)}%`;
  return (
    <div className="rec-bar-wrap">
      <div className="rec-bar">
        {sb > 0 && <div style={{ width: pct(sb), background: "#16a34a" }} title={`Strong Buy: ${sb}`} />}
        {b > 0  && <div style={{ width: pct(b),  background: "#22c55e" }} title={`Buy: ${b}`} />}
        {h > 0  && <div style={{ width: pct(h),  background: "#f59e0b" }} title={`Hold: ${h}`} />}
        {s > 0  && <div style={{ width: pct(s),  background: "#f97316" }} title={`Sell: ${s}`} />}
        {ss > 0 && <div style={{ width: pct(ss), background: "#ef4444" }} title={`Strong Sell: ${ss}`} />}
      </div>
      <div className="rec-legend">
        <span style={{ color: "#16a34a" }}>SB:{sb}</span>
        <span style={{ color: "#22c55e" }}>B:{b}</span>
        <span style={{ color: "#f59e0b" }}>H:{h}</span>
        <span style={{ color: "#f97316" }}>S:{s}</span>
        <span style={{ color: "#ef4444" }}>SS:{ss}</span>
      </div>
    </div>
  );
}
