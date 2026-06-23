import { useEffect, useState, useCallback } from "react";
import { api } from "../api";
import type { EmailConfigView } from "../api";
import { useT } from "../i18n";
import { buildDigestEmail } from "../emailTemplates";
import { toast } from "../toast";

const PRESETS: Record<string, { host: string; port: number }> = {
  Gmail: { host: "smtp.gmail.com", port: 587 },
  Outlook: { host: "smtp-mail.outlook.com", port: 587 },
};

export function EmailNotifications() {
  const { t, lang } = useT();
  const [cfg, setCfg] = useState<EmailConfigView | null>(null);
  const [host, setHost] = useState("");
  const [port, setPort] = useState(587);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [enabled, setEnabled] = useState(false);
  const [dailyDigest, setDailyDigest] = useState(true);
  const [digestHour, setDigestHour] = useState(8);
  const [msg, setMsg] = useState<string | null>(null);
  const [previewHtml, setPreviewHtml] = useState<string | null>(null);

  const load = useCallback(() => {
    api.emailConfigGet().then((c) => {
      setCfg(c);
      setHost(c.smtp_host ?? "");
      setPort(c.smtp_port ?? 587);
      setUsername(c.username ?? "");
      setFrom(c.from_email ?? "");
      setTo(c.to_email ?? "");
      setEnabled(c.enabled);
      setDailyDigest(c.daily_digest);
      setDigestHour(c.digest_hour);
    }).catch(console.error);
  }, []);
  useEffect(() => { load(); }, [load]);

  const save = async () => {
    setMsg(null);
    try {
      await api.emailConfigSet({
        smtpHost: host, smtpPort: port, username, password: password || null,
        fromEmail: from || username, toEmail: to, enabled,
        dailyDigest, digestHour, instantAlerts: false,
      });
      setPassword("");
      setMsg(t("email.saved"));
      load();
    } catch (e) { setMsg(`${t("email.error")}: ${e}`); }
  };

  const sampleDigest = () => buildDigestEmail({
    lang, now: new Date(),
    changes: [{ symbol: "AAPL", company: "Apple Inc.", from: "Buy", to: "Avoid", score: -28, priceCents: 22150, dailyChangeBps: -240 }],
    opportunities: [
      { symbol: "NVDA", company: "NVIDIA Corp.", label: "StrongBuy", score: 72, priceCents: 132040, sector: "Technology", upsidePct: 18, dailyChangeBps: 150, earningsEpoch: Math.floor(Date.now() / 1000) + 5 * 86400 },
      { symbol: "LLY", company: "Eli Lilly", label: "Buy", score: 51, priceCents: 88010, sector: "Healthcare", upsidePct: 12, dailyChangeBps: 60, earningsEpoch: null },
    ],
    risks: [{ symbol: "TSLA", severity: "warn", text: lang === "es" ? "reporta resultados en 3 días" : "reports earnings in 3 days" }],
  });

  const sendTest = async () => {
    setMsg(null);
    try {
      // Save first so the backend has fresh credentials, then send a sample digest.
      await api.emailConfigSet({
        smtpHost: host, smtpPort: port, username, password: password || null,
        fromEmail: from || username, toEmail: to, enabled,
        dailyDigest, digestHour, instantAlerts: false,
      });
      setPassword("");
      const sample = sampleDigest();
      await api.emailSend(`[TEST] ${sample.subject}`, sample.html, sample.text);
      setMsg(t("email.testSent"));
      toast(t("email.testSent"), "success");
      load();
    } catch (e) { setMsg(`${t("email.error")}: ${e}`); toast(`${t("email.error")}: ${e}`, "error"); }
  };

  const applyPreset = (name: string) => {
    const p = PRESETS[name];
    if (p) { setHost(p.host); setPort(p.port); }
  };

  if (!cfg) return null;

  return (
    <div className="info-section">
      <p style={{ fontSize: 11, color: "var(--text-4)", lineHeight: 1.5, margin: "0 0 8px" }}>{t("email.help")}</p>

      <div className="advisor-form" style={{ flexWrap: "wrap", gap: 8 }}>
        <label style={lblStyle}>{t("email.to")}
          <input className="search" placeholder="tu@email.com" value={to} onChange={(e) => setTo(e.target.value)} style={{ width: 300 }} />
          <span style={{ fontSize: 10, color: "var(--text-5)" }}>{t("email.toHint")}</span>
        </label>
        <label style={lblStyle}>{t("email.preset")}
          <span style={{ display: "flex", gap: 4 }}>
            {Object.keys(PRESETS).map((n) => (
              <button key={n} className="btn-ghost" style={{ padding: "4px 10px" }} onClick={() => applyPreset(n)}>{n}</button>
            ))}
          </span>
        </label>
      </div>

      <div className="advisor-form" style={{ flexWrap: "wrap", gap: 8, marginTop: 8 }}>
        <label style={lblStyle}>{t("email.host")}
          <input className="search" value={host} onChange={(e) => setHost(e.target.value)} style={{ width: 200 }} />
        </label>
        <label style={lblStyle}>{t("email.port")}
          <input className="search" type="number" value={port} onChange={(e) => setPort(parseInt(e.target.value) || 587)} style={{ width: 80 }} />
        </label>
        <label style={lblStyle}>{t("email.username")}
          <input className="search" value={username} onChange={(e) => setUsername(e.target.value)} style={{ width: 220 }} />
        </label>
        <label style={lblStyle}>{t("email.password")}
          <input className="search" type="password"
            placeholder={cfg.has_password ? t("email.passwordKept") : ""}
            value={password} onChange={(e) => setPassword(e.target.value)} style={{ width: 200 }} />
        </label>
      </div>

      <div className="advisor-form" style={{ flexWrap: "wrap", gap: 14, marginTop: 8, alignItems: "center" }}>
        <label style={chkStyle}><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> {t("email.enabled")}</label>
        <label style={chkStyle}><input type="checkbox" checked={dailyDigest} onChange={(e) => setDailyDigest(e.target.checked)} /> {t("email.dailyDigest")}</label>
        <label style={chkStyle}>{t("email.digestHour")}
          <select className="search" value={digestHour} onChange={(e) => setDigestHour(parseInt(e.target.value))} style={{ width: 70, padding: "2px 4px" }}>
            {Array.from({ length: 24 }, (_, h) => <option key={h} value={h}>{String(h).padStart(2, "0")}:00</option>)}
          </select>
        </label>
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 12, alignItems: "center", flexWrap: "wrap" }}>
        <button className="congress-sync-btn" onClick={save}>{t("email.save")}</button>
        <button className="btn-ghost" onClick={() => setPreviewHtml(sampleDigest().html)}>👁 {t("email.preview")}</button>
        <button className="btn-ghost" onClick={sendTest}>{t("email.sendTest")}</button>
        {cfg.last_digest_date && (
          <span style={{ fontSize: 11, color: "var(--text-4)" }}>{t("email.lastDigest", { date: cfg.last_digest_date })}</span>
        )}
      </div>

      <p style={{ fontSize: 10, color: "var(--text-5)", marginTop: 8, lineHeight: 1.5 }}>{t("email.gmailHint")}</p>
      {msg && <div style={{ fontSize: 12, marginTop: 6, color: msg.startsWith(t("email.error")) ? "var(--danger)" : "var(--success)" }}>{msg}</div>}

      {previewHtml && (
        <div className="modal-overlay" onClick={() => setPreviewHtml(null)}>
          <div className="modal-content" style={{ maxWidth: 680, position: "relative" }} onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h3>{t("email.previewTitle")}</h3>
              <button className="btn-ghost" onClick={() => setPreviewHtml(null)}>✕ {t("settings.close")}</button>
            </div>
            <iframe
              title={t("email.previewTitle")}
              srcDoc={previewHtml}
              style={{ width: "100%", height: 620, border: "1px solid var(--border-default)", borderRadius: 8, background: "#fff" }}
            />
          </div>
        </div>
      )}
    </div>
  );
}

const lblStyle: React.CSSProperties = { fontSize: 11, color: "var(--text-3)", display: "flex", flexDirection: "column", gap: 3 };
const chkStyle: React.CSSProperties = { fontSize: 12, color: "var(--text-2)", display: "flex", alignItems: "center", gap: 5 };
