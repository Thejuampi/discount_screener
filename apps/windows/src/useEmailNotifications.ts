import { useEffect, useRef } from "react";
import { api } from "./api";
import type { OpportunityRow, SetupLabel, EmailConfigView } from "./api";
import { useT } from "./i18n";
import type { ScoringModelId } from "./scoringPresentation";
import {
  buildDigestEmail,
  type EmailChange, type EmailOpportunity, type EmailRiskAlert,
} from "./emailTemplates";

const POS: SetupLabel[] = ["StrongBuy", "Buy", "Accumulate", "StrongAccumulate"];
const NEG: SetupLabel[] = ["Avoid", "StrongAvoid", "Caution", "Distribute"];

const todayISO = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};
const loadMap = (k: string): Record<string, SetupLabel> => {
  try { return JSON.parse(localStorage.getItem(k) ?? "{}"); } catch { return {}; }
};

/**
 * Email notifications: a fixed-hour daily digest + immediate alerts on critical
 * rating changes for held positions. Driven from the frontend (the webview stays
 * alive in the tray); the Rust side only does SMTP delivery.
 */
export function useEmailNotifications(rows: OpportunityRow[], scoringModel: ScoringModelId) {
  const { lang } = useT();
  const cfgRef = useRef<EmailConfigView | null>(null);
  const heldRef = useRef<Set<string>>(new Set());
  const langRef = useRef(lang);
  // Read rows via a ref so the digest interval doesn't restart on every rows
  // update (which caused bursts of concurrent ticks → duplicate digests).
  const rowsRef = useRef(rows);
  const digestSendingRef = useRef(false);
  useEffect(() => { langRef.current = lang; }, [lang]);
  useEffect(() => { rowsRef.current = rows; }, [rows]);

  // Refresh config + held symbols periodically.
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const [cfg, port] = await Promise.all([api.emailConfigGet(), api.portfolioList()]);
        if (cancelled) return;
        cfgRef.current = cfg;
        heldRef.current = new Set(port.map((p) => p.symbol));
      } catch { /* ignore */ }
    };
    load();
    const id = setInterval(load, 60_000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  // Instant trend-change emails were removed (too noisy) — the daily digest
  // already reports expectation changes once per day.

  // ── Daily digest at the configured hour (exactly once per day) ─────────────
  // Runs a single interval (mount-only); reads rows/cfg via refs so it never
  // restarts on rows updates. The day-slot is claimed synchronously before the
  // async send, so concurrent ticks can't produce duplicate emails.
  useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      const cfg = cfgRef.current;
      const rows = rowsRef.current;
      // Digest templates and portfolio actions are long-side only. Never send
      // them with inverted Short rows, where the same backend tokens mean the opposite trade.
      if (scoringModel === "short_v3") return;
      if (cancelled || digestSendingRef.current) return;
      if (!cfg?.enabled || !cfg.daily_digest || rows.length === 0) return;
      const now = new Date();
      if (now.getHours() < cfg.digest_hour) return;
      const today = todayISO();
      if (cfg.last_digest_date === today) return;

      const data = buildDigestData(rows, heldRef.current, langRef.current, now);
      if (data.changes.length + data.opportunities.length + data.risks.length === 0) return;

      // Claim the slot BEFORE awaiting, so any other tick bails immediately.
      digestSendingRef.current = true;
      cfgRef.current = { ...cfg, last_digest_date: today };

      const { subject, html, text } = buildDigestEmail(data);
      try {
        await api.emailSend(subject, html, text);
        await api.emailMarkDigestSent(today);
        const store = loadMap("ds_email_digest_labels");
        for (const r of rows) if (heldRef.current.has(r.symbol)) store[r.symbol] = r.setup_label;
        localStorage.setItem("ds_email_digest_labels", JSON.stringify(store));
      } catch (e) {
        console.error(e);
        // Send failed — release the slot so it retries on a later tick.
        cfgRef.current = { ...(cfgRef.current as EmailConfigView), last_digest_date: cfg.last_digest_date };
      } finally {
        digestSendingRef.current = false;
      }
    };
    const id = setInterval(tick, 60_000);
    tick();
    return () => { cancelled = true; clearInterval(id); };
  }, [scoringModel]);
}

function toChange(r: OpportunityRow, from: SetupLabel): EmailChange {
  return {
    symbol: r.symbol, company: r.company_name, from, to: r.setup_label,
    score: r.setup_score, priceCents: r.market_price_cents, dailyChangeBps: r.daily_change_bps,
  };
}

function buildDigestData(
  rows: OpportunityRow[], held: Set<string>, lang: "es" | "en", now: Date,
) {
  const bySym = new Map(rows.map((r) => [r.symbol, r]));

  // Changes since last digest (held symbols only).
  const store = loadMap("ds_email_digest_labels");
  const changes: EmailChange[] = [];
  for (const sym of held) {
    const r = bySym.get(sym);
    if (!r) continue;
    const prev = store[sym];
    if (prev && prev !== r.setup_label) changes.push(toChange(r, prev));
  }

  // Top opportunities not owned.
  const opportunities: EmailOpportunity[] = rows
    .filter((r) => !held.has(r.symbol))
    .filter((r) => POS.includes(r.setup_label))
    .sort((a, b) => b.composite_score - a.composite_score || b.setup_score - a.setup_score)
    .slice(0, 8)
    .map((r) => ({
      symbol: r.symbol, company: r.company_name, label: r.setup_label, score: r.setup_score,
      priceCents: r.market_price_cents, sector: r.sector_name,
      upsidePct: r.intrinsic_value_cents > 0 && r.market_price_cents > 0
        ? ((r.intrinsic_value_cents - r.market_price_cents) / r.market_price_cents) * 100 : null,
      dailyChangeBps: r.daily_change_bps, earningsEpoch: r.next_earnings_epoch,
    }));

  // Risk alerts on holdings: upcoming earnings + negative signal.
  const risks: EmailRiskAlert[] = [];
  for (const sym of held) {
    const r = bySym.get(sym);
    if (!r) continue;
    if (r.next_earnings_epoch != null) {
      const d = Math.floor((r.next_earnings_epoch * 1000 - Date.now()) / 86_400_000);
      if (d >= 0 && d <= 7) {
        risks.push({ symbol: sym, severity: d <= 2 ? "danger" : "warn",
          text: lang === "es" ? `reporta resultados en ${d} días` : `reports earnings in ${d} days` });
      }
    }
    if (NEG.includes(r.setup_label)) {
      risks.push({ symbol: sym, severity: r.setup_label === "StrongAvoid" ? "danger" : "warn",
        text: lang === "es" ? `señal negativa (${r.setup_label})` : `negative signal (${r.setup_label})` });
    }
  }

  return { changes, opportunities, risks, lang, now };
}
