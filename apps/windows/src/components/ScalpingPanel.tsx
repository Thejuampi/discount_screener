import { useEffect, useState, useCallback, useRef } from "react";
import { listen } from "@tauri-apps/api/event";
import { api, fmt } from "../api";
import type { ScalpAnalysis, ScalpTick } from "../api";
import { useT } from "../i18n";
import { ScalpChart } from "./ScalpChart";
import type { ChartOverlays } from "./ScalpChart";
import { ChartPatterns } from "./ChartPatterns";
import { FibLevels } from "./FibLevels";

const DEFAULT_OVERLAYS: ChartOverlays = { signal: true, smc: true, patterns: true, fib: true, ema: true, volume: true };

const PRODUCTS = ["BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD", "AVAX-USD", "LINK-USD", "ADA-USD"];
const TIMEFRAMES = ["1m", "3m", "5m", "15m", "1h", "4h"];

const LABEL_COLOR: Record<string, string> = {
  "High-Conviction": "#16a34a", Strong: "#22c55e", Weak: "#f59e0b", "No-Trade": "#64748b",
};
const trendColor = (t: string) => (t === "Bull" ? "var(--success)" : t === "Bear" ? "var(--danger)" : "var(--text-4)");

export function ScalpingPanel() {
  const { t } = useT();
  const [product, setProduct] = useState("BTC-USD");
  const [chartTf, setChartTf] = useState("5m");
  const [rr, setRr] = useState(() => {
    const v = parseFloat(localStorage.getItem("ds_scalp_rr") ?? "1.5");
    return isFinite(v) && v > 0 ? v : 1.5;
  });
  const [fee, setFee] = useState(() => {
    const v = parseFloat(localStorage.getItem("ds_scalp_fee") ?? "0.6");
    return isFinite(v) && v >= 0 ? v : 0.6;
  });
  const [a, setA] = useState<ScalpAnalysis | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState<{ price: number; at: number } | null>(null);
  const productRef = useRef(product);
  productRef.current = product;
  const [overlays, setOverlays] = useState<ChartOverlays>(() => {
    try { return { ...DEFAULT_OVERLAYS, ...JSON.parse(localStorage.getItem("ds_scalp_overlays") ?? "{}") }; }
    catch { return DEFAULT_OVERLAYS; }
  });
  const toggleOverlay = (k: keyof ChartOverlays) => setOverlays((o) => {
    const next = { ...o, [k]: !o[k] };
    localStorage.setItem("ds_scalp_overlays", JSON.stringify(next));
    return next;
  });

  const load = useCallback((p: string, ratio: number, feePct: number) => {
    setLoading(true); setError(null);
    api.getScalpAnalysis(p, ratio, feePct)
      .then((res) => { setA(res); setLoading(false); })
      .catch((e) => { setError(String(e)); setLoading(false); });
  }, []);

  useEffect(() => {
    localStorage.setItem("ds_scalp_rr", String(rr));
    localStorage.setItem("ds_scalp_fee", String(fee));
    load(product, rr, fee);
    const id = setInterval(() => load(product, rr, fee), 25_000);
    return () => clearInterval(id);
  }, [product, rr, fee, load]);

  // Real-time WebSocket: tell the backend which product to stream.
  useEffect(() => {
    setLive(null);
    api.scalpWsSubscribe(product).catch(() => {});
  }, [product]);

  // Listen for live ticks once; keep only those for the selected product.
  useEffect(() => {
    let un: (() => void) | undefined;
    let cancelled = false;
    listen<ScalpTick>("scalp_tick", (e) => {
      if (e.payload.product === productRef.current) {
        setLive({ price: e.payload.price_cents, at: Date.now() });
      }
    }).then((u) => { if (cancelled) u(); else un = u; });
    return () => { cancelled = true; un?.(); };
  }, []);

  const sc = a?.score;
  const sig = a?.signal ?? null;
  const sideColor = sig?.side === "LONG" ? "var(--success)" : sig?.side === "SHORT" ? "var(--danger)" : "var(--text-4)";

  return (
    <div className="congress-page">
      <header className="congress-header">
        <div>
          <h2 className="congress-title">
            {t("scalp.title")}
            {live ? (
              <span className="scalp-live"><span className="scalp-live-dot" /> {t("scalp.live")} {fmt.dollars(live.price)}</span>
            ) : (
              <span className="scalp-live off"><span className="scalp-live-dot off" /> {t("scalp.waiting")}</span>
            )}
          </h2>
          <p className="congress-subtitle">{t("scalp.subtitle")}</p>
        </div>
        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <label style={{ fontSize: 11, color: "var(--text-3)", display: "flex", alignItems: "center", gap: 5 }}>
            {t("scalp.rrTarget")}
            <select className="search" value={rr} onChange={(e) => setRr(parseFloat(e.target.value))} style={{ width: 70, padding: "2px 4px" }}>
              {[1, 1.5, 2, 2.5, 3].map((v) => <option key={v} value={v}>{v}:1</option>)}
            </select>
          </label>
          <label style={{ fontSize: 11, color: "var(--text-3)", display: "flex", alignItems: "center", gap: 5 }}>
            {t("scalp.fee")}
            <input className="search" type="number" step="0.05" min="0" value={fee}
              onChange={(e) => setFee(Math.max(0, parseFloat(e.target.value) || 0))} style={{ width: 64, padding: "2px 4px" }} />
            <span style={{ color: "var(--text-5)" }}>%</span>
          </label>
          <select className="search" value={product} onChange={(e) => setProduct(e.target.value)} style={{ width: 130 }}>
            {PRODUCTS.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
          <button className="btn-ghost" onClick={() => load(product, rr, fee)}>↺</button>
        </div>
      </header>

      {error && <div className="info-section" style={{ color: "var(--danger)" }}>{error}</div>}

      {sc && sig && (
        <>
          {/* Score + signal side by side */}
          <div className="info-section" style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
            {/* Score */}
            <div>
              <h3 style={{ marginTop: 0 }}>{t("scalp.score")}</h3>
              <div style={{ display: "flex", alignItems: "baseline", gap: 10, marginBottom: 10 }}>
                <span style={{ fontSize: 38, fontWeight: 800, color: LABEL_COLOR[sc.label] }}>{sc.total}</span>
                <span style={{ fontSize: 14, fontWeight: 700, color: LABEL_COLOR[sc.label] }}>{sc.label}</span>
                <span style={{ marginLeft: "auto", fontSize: 12, color: "var(--text-3)" }}>
                  {t("scalp.bias")}: <strong style={{ color: sc.bias === "Long" ? "var(--success)" : sc.bias === "Short" ? "var(--danger)" : "var(--text-4)" }}>{sc.bias}</strong>
                </span>
              </div>
              {([
                ["scalp.comp.trend", sc.trend, 30], ["scalp.comp.momentum", sc.momentum, 20],
                ["scalp.comp.volume", sc.volume, 20], ["scalp.comp.structure", sc.structure, 20],
                ["scalp.comp.risk", sc.risk, 10],
              ] as [string, number, number][]).map(([k, v, w]) => (
                <div key={k} style={{ display: "flex", alignItems: "center", gap: 8, margin: "5px 0" }}>
                  <span style={{ fontSize: 11, color: "var(--text-3)", width: 90 }}>{t(k)} <span style={{ color: "var(--text-5)" }}>{w}%</span></span>
                  <div style={{ flex: 1, height: 8, background: "var(--surface-3)", borderRadius: 4, overflow: "hidden" }}>
                    <div style={{ width: `${v}%`, height: "100%", background: v >= 75 ? "var(--success)" : v >= 50 ? "var(--warning)" : "var(--text-4)" }} />
                  </div>
                  <span style={{ fontSize: 11, fontWeight: 700, width: 26, textAlign: "right" }}>{v}</span>
                </div>
              ))}
            </div>

            {/* Signal */}
            <div style={{ borderLeft: "1px solid var(--border-default)", paddingLeft: 16 }}>
              <h3 style={{ marginTop: 0 }}>{t("scalp.signal")}</h3>
              {sig.side === "NONE" ? (
                <div style={{ color: "var(--text-4)", fontSize: 13, padding: "12px 0" }}>{t("scalp.noSignal")}</div>
              ) : (
                <>
                  <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 10, flexWrap: "wrap" }}>
                    <span style={{ display: "inline-block", padding: "4px 14px", borderRadius: 8, background: sideColor, color: "#fff", fontWeight: 800, fontSize: 16 }}>
                      {sig.side === "LONG" ? "▲ LONG" : "▼ SHORT"}
                    </span>
                    <span style={{
                      fontSize: 12, fontWeight: 700, padding: "5px 10px", borderRadius: 8,
                      background: sig.fee_viable ? "rgba(34,197,94,0.12)" : "rgba(244,63,94,0.12)",
                      border: `1px solid ${sig.fee_viable ? "rgba(34,197,94,0.4)" : "rgba(244,63,94,0.4)"}`,
                      color: sig.fee_viable ? "var(--success)" : "var(--danger)",
                    }}>
                      {sig.fee_viable ? t("scalp.feeViable") : t("scalp.feeNotViable")}
                    </span>
                  </div>
                  {(() => {
                    const entryMid = (sig.entry_low_cents + sig.entry_high_cents) / 2;
                    const gain = (tp: number) => entryMid > 0
                      ? (sig.side === "LONG" ? (tp - entryMid) / entryMid : (entryMid - tp) / entryMid) * 100
                      : 0;
                    const lossPct = entryMid > 0 ? (Math.abs(sig.stop_cents - entryMid) / entryMid) * 100 : 0;
                    const tp = (cents: number, netPct: number) => (
                      <span>
                        <span style={{ color: "var(--text-1)" }}>{fmt.dollars(cents)}</span>{" "}
                        <span style={{ fontSize: 11, color: "var(--text-4)" }}>+{gain(cents).toFixed(2)}%</span>{" "}
                        <span style={{ fontSize: 11, fontWeight: 700, color: netPct > 0 ? "var(--success)" : "var(--danger)" }}>
                          ({netPct > 0 ? "+" : ""}{netPct.toFixed(2)}% {t("scalp.net")})
                        </span>
                      </span>
                    );
                    return (
                      <div className="kv-grid" style={{ fontSize: 13 }}>
                        <span>{t("scalp.entry")}</span><span>{fmt.dollars(sig.entry_low_cents)} – {fmt.dollars(sig.entry_high_cents)}</span>
                        <span>{t("scalp.stop")}</span><span style={{ color: "var(--danger)" }}>{fmt.dollars(sig.stop_cents)} <span style={{ fontSize: 11, opacity: 0.85 }}>(−{lossPct.toFixed(2)}%)</span></span>
                        <span>{t("scalp.tp1")}</span>{tp(sig.tp1_cents, sig.tp1_net_pct)}
                        <span>{t("scalp.tp2")}</span>{tp(sig.tp2_cents, sig.tp2_net_pct)}
                        <span>{t("scalp.feeRoundTrip")}</span><span>{sig.round_trip_fee_pct.toFixed(2)}% <span style={{ fontSize: 11, color: "var(--text-5)" }}>({sig.fee_pct.toFixed(2)}% ×2)</span></span>
                        <span>{t("scalp.rr")}</span><span><strong>{sig.risk_reward.toFixed(2)} : 1</strong></span>
                        <span>{t("scalp.confidence")}</span><span><strong>{sig.confidence}</strong>/100</span>
                      </div>
                    );
                  })()}
                </>
              )}
              {sig.reasons.length > 0 && (
                <div style={{ marginTop: 10 }}>
                  <div style={{ fontSize: 10, textTransform: "uppercase", letterSpacing: "0.05em", color: "var(--text-4)", marginBottom: 4 }}>{t("scalp.reasons")}</div>
                  <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, color: "var(--text-3)", lineHeight: 1.6 }}>
                    {sig.reasons.map((r, i) => <li key={i}>{r}</li>)}
                  </ul>
                </div>
              )}
            </div>
          </div>

          {/* Chart with signal in place */}
          <div className="info-section">
            <div style={{ display: "flex", gap: 4, marginBottom: 8, alignItems: "center", flexWrap: "wrap" }}>
              {TIMEFRAMES.map((tf) => (
                <button key={tf} className={`range-tab ${chartTf === tf ? "active" : ""}`} onClick={() => setChartTf(tf)}>{tf}</button>
              ))}
              <div style={{ display: "flex", gap: 12, marginLeft: "auto", fontSize: 11, color: "var(--text-3)" }}>
                {(["signal", "smc", "patterns", "fib", "ema", "volume"] as (keyof ChartOverlays)[]).map((k) => (
                  <label key={k} style={{ display: "flex", alignItems: "center", gap: 4, cursor: "pointer" }}>
                    <input type="checkbox" checked={overlays[k]} onChange={() => toggleOverlay(k)} />
                    {t(`chart.toggle.${k}`)}
                  </label>
                ))}
              </div>
            </div>
            <ScalpChart product={product} timeframe={chartTf} signal={sig} smc={a.smc} patterns={a.patterns} fib={a.fib} show={overlays} />
          </div>

          {/* Smart Money Concepts / Market Structure */}
          <SmcSection a={a} t={t} />

          {/* Classic chart patterns */}
          <div className="info-section">
            <h3>{t("pat.title")}</h3>
            <ChartPatterns patterns={a.patterns} />
          </div>

          {/* Fibonacci levels */}
          <div className="info-section">
            <h3>{t("fib.title")}</h3>
            <FibLevels fib={a.fib} />
          </div>


          {/* Per-timeframe breakdown */}
          <div className="info-section">
            <h3>{t("scalp.timeframes")}</h3>

            {/* Quick Long/Short summary across timeframes */}
            <div style={{ fontSize: 10, textTransform: "uppercase", letterSpacing: "0.05em", color: "var(--text-4)", marginBottom: 6 }}>{t("scalp.mtfSummary")}</div>
            <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginBottom: 14 }}>
              {a.timeframes.map((tf) => {
                const col = tf.trend === "Bull" ? "var(--success)" : tf.trend === "Bear" ? "var(--danger)" : "var(--text-4)";
                const txt = tf.trend === "Bull" ? `▲ ${t("scalp.long")}` : tf.trend === "Bear" ? `▼ ${t("scalp.short")}` : t("scalp.neutral");
                return (
                  <span key={tf.tf} style={{ display: "flex", flexDirection: "column", alignItems: "center", padding: "5px 12px", borderRadius: 8, border: `1px solid ${col}`, minWidth: 58 }}>
                    <span style={{ fontSize: 10, color: "var(--text-4)", fontWeight: 700 }}>{tf.tf}</span>
                    <strong style={{ fontSize: 12, color: col }}>{txt}</strong>
                  </span>
                );
              })}
            </div>

            <table className="stock-table">
              <thead>
                <tr>
                  <th>{t("scalp.col.tf")}</th>
                  <th>{t("scalp.col.trend")}</th>
                  <th>{t("scalp.col.super")}</th>
                  <th style={{ textAlign: "right" }}>RSI</th>
                  <th style={{ textAlign: "right" }}>StochRSI</th>
                  <th style={{ textAlign: "right" }}>VWAP Δ</th>
                  <th>{t("scalp.col.struct")}</th>
                </tr>
              </thead>
              <tbody>
                {a.timeframes.map((tf) => {
                  const vwapDev = tf.vwap_cents && tf.close_cents ? ((tf.close_cents - tf.vwap_cents) / tf.vwap_cents) * 100 : null;
                  return (
                    <tr key={tf.tf}>
                      <td><strong>{tf.tf}</strong></td>
                      <td style={{ color: trendColor(tf.trend), fontWeight: 700, fontSize: 12 }}>{tf.trend}</td>
                      <td style={{ color: tf.supertrend_dir > 0 ? "var(--success)" : tf.supertrend_dir < 0 ? "var(--danger)" : "var(--text-4)", fontWeight: 700 }}>
                        {tf.supertrend_dir > 0 ? "▲" : tf.supertrend_dir < 0 ? "▼" : "—"}
                      </td>
                      <td className="num-cell" style={{ textAlign: "right" }}>{tf.rsi != null ? tf.rsi.toFixed(0) : "—"}</td>
                      <td className="num-cell" style={{ textAlign: "right" }}>{tf.stoch_rsi != null ? tf.stoch_rsi.toFixed(0) : "—"}</td>
                      <td className="num-cell" style={{ textAlign: "right", color: vwapDev == null ? "var(--text-4)" : vwapDev >= 0 ? "var(--success)" : "var(--danger)" }}>
                        {vwapDev != null ? `${vwapDev >= 0 ? "+" : ""}${vwapDev.toFixed(2)}%` : "—"}
                      </td>
                      <td style={{ fontSize: 12, color: tf.structure === "HH-HL" ? "var(--success)" : tf.structure === "LH-LL" ? "var(--danger)" : "var(--text-4)" }}>{tf.structure}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <p style={{ fontSize: 10, color: "var(--text-5)", marginTop: 10, lineHeight: 1.5 }}>{t("scalp.disclaimer")}</p>
          </div>
        </>
      )}

      {loading && !a && <div className="info-section" style={{ color: "var(--text-4)" }}>…</div>}
    </div>
  );
}

type TFn = (k: string, v?: Record<string, string | number>) => string;

function SmcSection({ a, t }: { a: ScalpAnalysis; t: TFn }) {
  const smc = a.smc;
  const structColor = smc.structure === "Bullish" ? "var(--success)" : smc.structure === "Bearish" ? "var(--danger)" : "var(--text-4)";
  const structLabel = smc.structure === "Bullish" ? t("scalp.smc.bull") : smc.structure === "Bearish" ? t("scalp.smc.bear") : t("scalp.smc.range");
  const volLabel = smc.squeeze ? t("scalp.smc.squeeze") : smc.expansion ? t("scalp.smc.expansion") : t("scalp.smc.normal");
  const volColor = smc.squeeze ? "var(--warning)" : smc.expansion ? "var(--accent)" : "var(--text-4)";

  const chip = (label: string, value: string, color: string) => (
    <span style={{ display: "flex", flexDirection: "column", padding: "6px 12px", borderRadius: 8, background: "var(--surface-3)", minWidth: 90 }}>
      <span style={{ fontSize: 10, color: "var(--text-4)", textTransform: "uppercase", letterSpacing: "0.04em" }}>{label}</span>
      <strong style={{ fontSize: 13, color }}>{value}</strong>
    </span>
  );

  const zoneRow = (kind: string, lo: number, hi: number) => {
    const bull = kind.startsWith("Bull");
    return (
      <li key={`${kind}-${lo}`} style={{ fontSize: 12, color: "var(--text-2)" }}>
        <span style={{ color: bull ? "var(--success)" : "var(--danger)", fontWeight: 700 }}>{bull ? "▲" : "▼"}</span>{" "}
        {fmt.dollars(lo)} – {fmt.dollars(hi)}
      </li>
    );
  };

  return (
    <div className="info-section">
      <h3>{t("scalp.smc.title")}</h3>

      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", marginBottom: 14 }}>
        {chip(t("scalp.smc.structure"), structLabel, structColor)}
        {chip(t("scalp.smc.volatility"), volLabel, volColor)}
        {smc.volume_profile && chip(t("scalp.smc.poc"), fmt.dollars(smc.volume_profile.poc_cents), "#eab308")}
      </div>

      {/* Events */}
      <div style={{ fontSize: 10, textTransform: "uppercase", letterSpacing: "0.05em", color: "var(--text-4)", marginBottom: 6 }}>{t("scalp.smc.events")}</div>
      {smc.events.length === 0 ? (
        <div style={{ fontSize: 12, color: "var(--text-4)", marginBottom: 12 }}>{t("scalp.smc.noEvents")}</div>
      ) : (
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 12 }}>
          {smc.events.map((e, i) => {
            const col = e.kind === "CHoCH" ? "var(--warning)" : e.kind === "LiquiditySweep" ? "#a855f7" : (e.direction === "Bullish" ? "var(--success)" : "var(--danger)");
            return (
              <span key={i} style={{ fontSize: 12, fontWeight: 700, padding: "4px 10px", borderRadius: 8, border: `1px solid ${col}`, color: col }}>
                {e.direction === "Bullish" ? "▲" : "▼"} {e.kind} · {fmt.dollars(e.price_cents)}
              </span>
            );
          })}
        </div>
      )}

      {/* FVG + Order Blocks */}
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
        <div>
          <div style={{ fontSize: 10, textTransform: "uppercase", letterSpacing: "0.05em", color: "var(--text-4)", marginBottom: 4 }}>{t("scalp.smc.fvg")}</div>
          {smc.fvgs.length === 0 ? <div style={{ fontSize: 12, color: "var(--text-4)" }}>{t("scalp.smc.zonesNone")}</div>
            : <ul style={{ margin: 0, paddingLeft: 16, lineHeight: 1.7 }}>{smc.fvgs.map((z) => zoneRow(z.kind, z.low_cents, z.high_cents))}</ul>}
        </div>
        <div>
          <div style={{ fontSize: 10, textTransform: "uppercase", letterSpacing: "0.05em", color: "var(--text-4)", marginBottom: 4 }}>{t("scalp.smc.ob")}</div>
          {smc.order_blocks.length === 0 ? <div style={{ fontSize: 12, color: "var(--text-4)" }}>{t("scalp.smc.zonesNone")}</div>
            : <ul style={{ margin: 0, paddingLeft: 16, lineHeight: 1.7 }}>{smc.order_blocks.map((z) => zoneRow(z.kind, z.low_cents, z.high_cents))}</ul>}
        </div>
      </div>
    </div>
  );
}
