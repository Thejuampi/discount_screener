import { useState, useEffect } from "react";
import { api, fmt } from "../api";
import type { SymbolDetail, OpportunityRow, PriceProvenance } from "../api";
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
import {
  getScoringPresentation,
  renderText,
  type DirectionalTone,
  type ScoringModelId,
  type ScoringPresentationState,
} from "../scoringPresentation";
import {
  createRegimePresentation,
  regimeRowInput,
  renderRegimeCause,
  type RegimePresentation,
} from "../regimePresentation";

function toneColor(tone: DirectionalTone): string {
  if (tone === "favorable") return "#22c55e";
  if (tone === "adverse") return "#f87171";
  return "var(--text-4)";
}

function gapToneClass(tone: DirectionalTone): string {
  if (tone === "favorable") return "gap-green";
  if (tone === "adverse") return "gap-red";
  return "gap-grey";
}

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
  scoringModel: ScoringModelId;
  profile: Profile;
  onProfileChange: (p: Profile) => void;
  onClose: () => void;
}

const RANGES = ["1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y"];

export function DetailPanel({ symbol, row, scoringModel, profile, onProfileChange, onClose }: Props) {
  const { t } = useT();
  const presentation = getScoringPresentation(scoringModel);
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
  const gap = row ? row.gap_bps : detail?.gap_bps ?? null;
  const confidence = row?.confidence ?? detail?.confidence ?? "Low";
  const dcfValue = row?.dcf_value_cents ?? detail?.dcf_value_cents ?? null;
  const dcfAnalysis = detail?.dcf_analysis ?? null;

  const f = detail?.fundamentals;
  // Unknown row type stays neutral until the scored row arrives; never guess
  // that an outside-universe symbol is an equity and show valuation guidance.
  const technicalOnly = row == null || row.asset_type === "crypto" || row.asset_type === "etf";
  const isCrypto = row?.asset_type === "crypto" || symbol.endsWith("-USD");
  const gapPresentation = presentation.gap(gap);
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
        {!technicalOnly && <>
          <div className="price-arrow">→</div>
          <div className="price-main">
            <span className="price-value">{targetPrice > 0 ? fmt.dollars(targetPrice) : "—"}</span>
            <span className="price-label">{t(presentation.analystTargetLabelKey)}</span>
          </div>
        </>}
        {!technicalOnly && dcfValue && dcfValue > 0 && (
          <>
            <div className="price-main">
              <span className="price-value" style={{ color: toneColor(hasValidData ? presentation.dcfTone(dcfValue, marketPrice) : "neutral") }}>
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
        {hasValidData && !technicalOnly && (
          <div
            className={`gap-pill ${gapToneClass(gapPresentation.tone)}`}
            title={renderText(gapPresentation.text, t)}
          >
            {gapPresentation.marker} {renderText(gapPresentation.compactText, t)}
          </div>
        )}
        {!technicalOnly && (
          <div className={`conf-pill conf-${confidence.toLowerCase()}`}>
            {t(`conf.${confidence}`)}
          </div>
        )}
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
      <CryptoCyclePanel symbol={symbol} isCrypto={isCrypto} scoringModel={scoringModel} />

      {/* ── Analysis summary — needs both row and detail for full narrative ── */}
      {row && detail && <AnalysisSummary row={row} detail={detail} presentation={presentation} scoringModel={scoringModel} />}

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
          scoringModel={scoringModel}
        />
      )}

      {/* Analyst breakdown — needs detail */}
      {detail && f && !technicalOnly && (
        <div className="section-grid">
          <div className="info-section">
            <h3>{t("detail.analystConsensus")}</h3>
            {presentation.analystSectionNote.key && (
              <p className="analyst-perspective-note">{renderText(presentation.analystSectionNote, t)}</p>
            )}
            <div className="kv-grid">
              <span>{t("detail.opinionCount")}</span><span>{detail.analyst_opinion_count ?? "—"}</span>
              <span>{t("detail.recommendation")}</span><span>{fmt.recMean(detail.recommendation_mean_hundredths)}</span>
              <span>{t("detail.targetLow")}</span><span>{detail.low_fair_value_cents ? fmt.dollars(detail.low_fair_value_cents) : "—"}</span>
              <span>{t("detail.targetHigh")}</span><span>{detail.high_fair_value_cents ? fmt.dollars(detail.high_fair_value_cents) : "—"}</span>
              <span>{t("detail.signalAge")}</span><span>{detail.signal_age_seconds != null ? `${Math.round(detail.signal_age_seconds / 3600)}h` : "—"}</span>
            </div>
            {detail.strong_buy_count != null && <RecommendationBar detail={detail} />}
            <InsiderActivity detail={detail} presentation={presentation} />
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
      {row && detail && <AnalysisBuckets row={row} detail={detail} presentation={presentation} scoringModel={scoringModel} />}
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

function narrativeContext(
  row: OpportunityRow,
  detail: SymbolDetail,
  marketContext: RegimePresentation,
) {
  return {
    name: detail.company_name ?? row.symbol,
    decision: row.decision,
    compositeScore: row.composite_score,
    gapBps: row.gap_bps,
    confidence: detail.confidence,
    technicalScore: row.technical_score,
    assetType: row.asset_type,
    regimeScoringEnabled: marketContext.status === "Included",
    regimeScore: marketContext.score,
  };
}

/** Top section: verdict header + summary paragraph + reasoning. */
function AnalysisSummary({
  row,
  detail,
  presentation,
  scoringModel,
}: {
  row: OpportunityRow;
  detail: SymbolDetail;
  presentation: ScoringPresentationState;
  scoringModel: ScoringModelId;
}) {
  const { t } = useT();
  const dh = presentation.decisionHeader(row.decision);
  const marketContext = createRegimePresentation(regimeRowInput(row, scoringModel));
  const { summary } = buildReasons(row, detail, t, presentation, scoringModel);
  const reason = renderText(
    presentation.decisionReason(narrativeContext(row, detail, marketContext)),
    t,
  );
  const gap = presentation.gap(row.gap_bps);
  const technicalOnly = row.asset_type === "crypto" || row.asset_type === "etf";

  return (
    <div className="analysis-box" style={{ borderColor: dh.color }}>
      <div className="analysis-header" style={{ color: dh.color }}>
        <span className="analysis-emoji">{dh.emoji}</span>
        <span className="analysis-title">{t(dh.titleKey)}</span>
        <span className="analysis-score">
          {marketContext.visible ? (
            <>
              {t("analysis.marketContext.baseShort")}: {marketContext.composition.base}
              <span className="score-composition-sep">·</span>
              {t("analysis.marketContext.contextShort")}: {marketContext.composition.context}
              <span className="score-composition-sep">·</span>
              {t("analysis.marketContext.finalShort")}: {marketContext.composition.final}
            </>
          ) : `${t("analysis.score")}: ${row.composite_score}`}
        </span>
      </div>
      <p className="analysis-summary">{summary}</p>
      <div className="decision-reason" style={{ borderTopColor: dh.color }}>
        <div className="decision-reason-label">{t("reason.title")}</div>
        <p className="decision-reason-text">
          {reason}
          {marketContext.visible && (
            <> {t(marketContext.impactKey, { impact: Math.abs(marketContext.impact) })}</>
          )}
        </p>
        <div className="decision-reason-meta">
          <span>Score: <strong style={{ color: dh.color }}>{row.composite_score}</strong></span>
          {technicalOnly ? (
            <><span>·</span><span>{t("analysis.technical")}: <strong>{row.technical_score ?? "—"}</strong></span></>
          ) : (
            <>
              <span>·</span>
              <span>{t(presentation.bucketLabelKeys[0])}: <strong>{row.fundamentals_score ?? "—"}</strong></span>
              <span>·</span>
              <span>{t(presentation.bucketLabelKeys[1])}: <strong>{row.technical_score ?? "—"}</strong></span>
              <span>·</span>
              <span>{t(presentation.bucketLabelKeys[2])}: <strong>{row.forecast_score ?? "—"}</strong></span>
              {marketContext.visible && (
                <>
                  <span>·</span>
                  <span>{t("analysis.marketContext.contextShort")}: <strong>{marketContext.score ?? "—"}</strong></span>
                </>
              )}
              <span>·</span>
              <span>{t(presentation.gapLabelKey)}: <strong style={{ color: toneColor(gap.tone) }}>{renderText(gap.text, t)}</strong></span>
              <span>·</span>
              <span>{t("tech.confidence")}: <strong>{t(`conf.${detail.confidence}`)}</strong></span>
            </>
          )}
        </div>
      </div>
      {presentation.riskNotice.key && (
        <div className="short-risk-notice">⚠ {renderText(presentation.riskNotice, t)}</div>
      )}
    </div>
  );
}

/** Bottom section: 3- or 4-column breakdown (regime is the 4th V3 bucket). */
function AnalysisBuckets({
  row,
  detail,
  presentation,
  scoringModel,
}: {
  row: OpportunityRow;
  detail: SymbolDetail;
  presentation: ScoringPresentationState;
  scoringModel: ScoringModelId;
}) {
  const { t } = useT();
  const reasons = buildReasons(row, detail, t, presentation, scoringModel);
  const technicalOnly = row.asset_type === "crypto" || row.asset_type === "etf";
  const marketContext = createRegimePresentation(regimeRowInput(row, scoringModel));
  if (technicalOnly) {
    return (
      <div className="analysis-buckets-wrap analysis-buckets-wrap--single">
        <AnalysisBucket label={t(presentation.bucketLabelKeys[1])} score={row.technical_score} points={reasons.technical} signals={row.technical_signals} invertSignals={presentation.isShort} />
      </div>
    );
  }
  return (
    <div className={`analysis-buckets-wrap${marketContext.visible ? " analysis-buckets-wrap--four" : ""}`}>
      <AnalysisBucket label={t(presentation.bucketLabelKeys[0])} score={row.fundamentals_score} points={reasons.fundamentals} signals={row.fundamentals_signals} invertSignals={presentation.isShort} />
      <AnalysisBucket label={t(presentation.bucketLabelKeys[1])} score={row.technical_score} points={reasons.technical} signals={row.technical_signals} invertSignals={presentation.isShort} />
      <AnalysisBucket label={t(presentation.bucketLabelKeys[2])} score={row.forecast_score} points={reasons.forecast} signals={row.forecast_signals} invertSignals={presentation.isShort} />
      {marketContext.visible && (
        <MarketContextBucket
          marketContext={marketContext}
          points={reasons.regime}
        />
      )}
    </div>
  );
}

function MarketContextBucket({
  marketContext,
  points,
}: {
  marketContext: RegimePresentation;
  points: string[];
}) {
  const { t } = useT();
  const tone = marketContext.classification;
  const color = tone === "favorable" ? "#22c55e"
    : tone === "adverse" ? "#ef4444"
      : "#f59e0b";
  return (
    <div className={`analysis-bucket market-context-bucket${marketContext.muted ? " is-muted" : ""}`}>
      <div className="bucket-header">
        <span className="bucket-label">{t(marketContext.bucketKey)}</span>
        <span className="bucket-score" style={{ color: marketContext.score == null ? "#64748b" : color }}>
          {marketContext.score == null
            ? t(marketContext.statusKey)
            : `${marketContext.score > 0 ? "+" : ""}${marketContext.score} · ${t(`analysis.marketContext.bucket.${marketContext.classification}`)}`}
        </span>
      </div>
      <div className="market-context-explainer">
        {t("analysis.marketContext.explainer")}
      </div>
      {points.length > 0 && (
        <ul className="bucket-points">
          {points.map((point, index) => <li key={index}>{point}</li>)}
        </ul>
      )}
    </div>
  );
}

function AnalysisBucket({
  label, score, points, signals, invertSignals = false, subtitle,
}: {
  label: string;
  score: number | null;
  points: string[];
  signals: string[];
  invertSignals?: boolean;
  subtitle?: string;
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
      {subtitle && (
        <div style={{ fontSize: 10, color: "var(--text-5)", marginBottom: 8, lineHeight: 1.4 }}>
          {subtitle}
        </div>
      )}
      {points.length > 0 && (
        <ul className="bucket-points">
          {points.map((p, i) => <li key={i}>{p}</li>)}
        </ul>
      )}
      {signals.length > 0 && (
        <div className="bucket-signals">
          {signals.map((s, i) => (
            <span key={i} className={`signal-chip ${signalClass(s, invertSignals)}`}>{s}</span>
          ))}
        </div>
      )}
    </div>
  );
}

function signalClass(signal: string, invert: boolean): string {
  if (signal.endsWith("++")) return invert ? "sig-strong-neg" : "sig-strong-pos";
  if (signal.endsWith("+"))  return invert ? "sig-neg" : "sig-pos";
  if (signal.endsWith("--")) return invert ? "sig-strong-pos" : "sig-strong-neg";
  if (signal.endsWith("-"))  return invert ? "sig-pos" : "sig-neg";
  return "";
}

// ── Narrative builder ─────────────────────────────────────────────────────────

interface Reasons {
  summary: string;
  fundamentals: string[];
  technical: string[];
  forecast: string[];
  regime: string[];
}

type TFn = (key: string, vars?: Record<string, string | number>) => string;

function buildReasons(
  row: OpportunityRow,
  detail: SymbolDetail,
  t: TFn,
  presentation: ScoringPresentationState,
  scoringModel: ScoringModelId,
): Reasons {
  const f = detail.fundamentals;
  const analystCount = detail.analyst_opinion_count ?? 0;
  const recLabel = fmt.recMean(detail.recommendation_mean_hundredths);
  const evidence = (
    key: string,
    direction: "positive" | "negative" | "neutral",
    vars?: Record<string, string | number>,
  ) => renderText(presentation.frameEvidence(t(key, vars), direction), t);

  // ── Summary sentence ─────────────────────────────────────────────────────
  const marketContext = createRegimePresentation(regimeRowInput(row, scoringModel));
  const summary = presentation.summary(narrativeContext(row, detail, marketContext))
    .map((ref) => renderText(ref, t))
    .join(" ");

  // ── Fundamentals ─────────────────────────────────────────────────────────
  const fundPoints: string[] = [];
  if (f.free_cash_flow_dollars != null) {
    const fcf = f.free_cash_flow_dollars;
    fundPoints.push(fcf > 0
      ? evidence("ia.fund.fcfPositive", "positive", { fcf: fmt.billions(fcf) })
      : evidence("ia.fund.fcfNegative", "negative", { fcf: fmt.billions(fcf) }));
  }
  if (f.return_on_equity_bps != null) {
    const roe = (f.return_on_equity_bps / 100).toFixed(1);
    const roeNum = f.return_on_equity_bps / 100;
    if (roeNum >= 20)      fundPoints.push(evidence("ia.fund.roeExcellent", "positive", { roe }));
    else if (roeNum >= 10) fundPoints.push(evidence("ia.fund.roeHealthy", "positive", { roe }));
    else if (roeNum < 0)   fundPoints.push(evidence("ia.fund.roeNegative", "negative", { roe }));
    else                   fundPoints.push(evidence("ia.fund.roeModerate", "neutral", { roe }));
  }
  if (f.earnings_growth_bps != null) {
    const g = (f.earnings_growth_bps / 100).toFixed(1);
    const gNum = f.earnings_growth_bps / 100;
    if (gNum > 15)     fundPoints.push(evidence("ia.fund.growthStrong", "positive", { g }));
    else if (gNum > 0) fundPoints.push(evidence("ia.fund.growthPositive", "positive", { g }));
    else               fundPoints.push(evidence("ia.fund.growthNegative", "negative", { g }));
  }
  if (f.debt_to_equity_hundredths != null) {
    const de = (f.debt_to_equity_hundredths / 100).toFixed(2);
    const deNum = f.debt_to_equity_hundredths / 100;
    if (deNum <= 0.5)      fundPoints.push(evidence("ia.fund.debtLow", "positive", { de }));
    else if (deNum <= 1.5) fundPoints.push(evidence("ia.fund.debtModerate", "neutral", { de }));
    else                   fundPoints.push(evidence("ia.fund.debtHigh", "negative", { de }));
  }
  if (f.forward_pe_hundredths != null && f.forward_pe_hundredths > 0) {
    const pe = (f.forward_pe_hundredths / 100).toFixed(1);
    const peNum = f.forward_pe_hundredths / 100;
    if (peNum < 15)      fundPoints.push(evidence("ia.fund.peAttractive", "positive", { pe }));
    else if (peNum < 25) fundPoints.push(evidence("ia.fund.peReasonable", "neutral", { pe }));
    else                 fundPoints.push(evidence("ia.fund.peElevated", "negative", { pe }));
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
    const techFact = (signal: string | undefined, posKey: string, negKey: string) => {
      if (!signal) return;
      const positive = signal.includes("+");
      techPoints.push(evidence(positive ? posKey : negKey, positive ? "positive" : "negative"));
    };
    techFact(hasPx20, "ia.tech.px20Pos", "ia.tech.px20Neg");
    techFact(has2050, "ia.tech.2050Pos", "ia.tech.2050Neg");
    techFact(has50200, "ia.tech.50200Pos", "ia.tech.50200Neg");
    techFact(hasHist, "ia.tech.histPos", "ia.tech.histNeg");
    techFact(hasMACD, "ia.tech.macdPos", "ia.tech.macdNeg");
  }

  // ── Forecast ─────────────────────────────────────────────────────────────
  const forePoints: string[] = [];
  if (analystCount === 0) {
    forePoints.push(t("ia.fore.noCoverage"));
  } else {
    forePoints.push(renderText(presentation.analystCoverage(analystCount, recLabel), t));
    forePoints.push(renderText(presentation.gap(row.gap_bps).text, t));
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
    if (ib >= 2 && ib >= is_)         forePoints.push(t(presentation.insiderSignalKey("strongBuy"), { n: ib }));
    else if (ib >= 1 && ib >= is_)    forePoints.push(t(presentation.insiderSignalKey("buy"), { n: ib }));
    else if (is_ >= 5 && ib === 0)    forePoints.push(t(presentation.insiderSignalKey("heavySell"), { n: is_ }));
  }

  // ── Regime (4th bucket) ──────────────────────────────────────────────────
  const regimePoints: string[] = [];
  if (marketContext.visible) {
    if (marketContext.status !== "Included") {
      regimePoints.push(t(marketContext.statusKey));
    }
    for (const sig of marketContext.causes) {
      regimePoints.push(renderRegimeCause(sig, marketContext.side, t));
    }
  }

  return {
    summary,
    fundamentals: fundPoints,
    technical: techPoints,
    forecast: forePoints,
    regime: regimePoints,
  };
}

// ── Insider activity ──────────────────────────────────────────────────────────

function InsiderActivity({
  detail,
  presentation,
}: {
  detail: SymbolDetail;
  presentation: ScoringPresentationState;
}) {
  const { t } = useT();
  const buys = detail.insider_buy_count;
  const sells = detail.insider_sell_count;
  const net = detail.insider_net_shares_90d;
  if (buys == null && sells == null) return null;
  const b = buys ?? 0;
  const s = sells ?? 0;
  if (b + s === 0) {
    return (
      <div style={{ marginTop: 10, fontSize: 11, color: "#64748b" }}>
        {t("presentation.insider.inactive")}
      </div>
    );
  }
  const tone = b >= 2 && b >= s
    ? { color: toneColor(presentation.isShort ? "adverse" : "favorable"), label: t(presentation.insiderSignalKey("strongBuy"), { n: b }) }
    : b >= 1 && b >= s
      ? { color: toneColor(presentation.isShort ? "adverse" : "favorable"), label: t(presentation.insiderSignalKey("buy"), { n: b }) }
      : s >= 5 && b === 0
        ? { color: toneColor(presentation.isShort ? "favorable" : "adverse"), label: t(presentation.insiderSignalKey("heavySell"), { n: s }) }
        : { color: "#94a3b8", label: t("presentation.insider.mixed") };
  const netFmt =
    net == null ? "—"
    : net === 0 ? t("presentation.insider.neutral")
    : t("presentation.insider.netShares", { n: `${net > 0 ? "+" : ""}${(net / 1000).toFixed(1)}k` });
  return (
    <div style={{ marginTop: 10, fontSize: 11 }}>
      <span style={{ color: tone.color, fontWeight: 600 }}>{tone.label}</span>
      <span style={{ color: "#64748b", marginLeft: 8 }}>
        {t("presentation.insider.activity", { buys: b, sells: s, net: netFmt })}
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
