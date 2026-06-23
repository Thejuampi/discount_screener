import { useEffect, useState, useCallback } from "react";
import { open } from "@tauri-apps/plugin-shell";
import { api } from "../api";
import type { SchwabStatus } from "../api";
import { useT } from "../i18n";

const DEFAULT_CALLBACK = "https://127.0.0.1:8182";

export function SchwabConnect() {
  const { t } = useT();
  const [status, setStatus] = useState<SchwabStatus | null>(null);
  const [editingCreds, setEditingCreds] = useState(false);
  const [appKey, setAppKey] = useState("");
  const [secret, setSecret] = useState("");
  const [callback, setCallback] = useState(DEFAULT_CALLBACK);
  const [redirect, setRedirect] = useState("");
  const [msg, setMsg] = useState<string | null>(null);

  const refresh = useCallback(() => {
    api.schwabStatus().then(setStatus).catch(console.error);
  }, []);
  useEffect(() => { refresh(); }, [refresh]);

  const saveCreds = async () => {
    setMsg(null);
    try {
      await api.schwabSetCredentials(appKey.trim(), secret.trim(), callback.trim() || DEFAULT_CALLBACK);
      setEditingCreds(false);
      setSecret("");
      setMsg(t("schwab.conn.saved"));
      refresh();
    } catch (e) { setMsg(`${t("schwab.conn.error")}: ${e}`); }
  };

  const openLogin = async () => {
    setMsg(null);
    try {
      const url = await api.schwabAuthUrl();
      await open(url);
    } catch (e) { setMsg(`${t("schwab.conn.error")}: ${e}`); }
  };

  const completeAuth = async () => {
    setMsg(null);
    try {
      await api.schwabCompleteAuth(redirect.trim());
      setRedirect("");
      refresh();
    } catch (e) { setMsg(`${t("schwab.conn.error")}: ${e}`); }
  };

  const disconnect = async () => {
    try { await api.schwabDisconnect(); refresh(); } catch (e) { console.error(e); }
  };

  if (!status) return null;
  const fmtDate = (epoch: number | null) =>
    epoch != null ? new Date(epoch * 1000).toLocaleString() : "—";

  // Show the credentials form when not configured, or when explicitly editing.
  const showCredsForm = !status.configured || editingCreds;
  // Show step 2 (authorize) when configured but not connected, or needs reauth.
  const showAuthStep = status.configured && !editingCreds && (!status.connected || status.needs_reauth);

  return (
    <div className="info-section">
      <p style={{ fontSize: 11, color: "var(--text-4)", lineHeight: 1.5, margin: "0 0 8px" }}>
        {t("schwab.conn.help")}
      </p>

      {/* Status line */}
      <div style={{ fontSize: 13, marginBottom: 12, fontWeight: 600,
        color: status.connected ? "var(--success)" : status.needs_reauth ? "var(--danger)" : "var(--text-4)" }}>
        {status.connected ? t("schwab.conn.connected")
          : status.needs_reauth ? t("schwab.conn.needsReauth")
          : status.configured ? t("schwab.conn.saved")
          : t("schwab.conn.notConfigured")}
        {status.connected && status.refresh_valid_until != null && (
          <span style={{ fontSize: 11, fontWeight: 400, color: "var(--text-4)", marginLeft: 8 }}>
            {t("schwab.conn.refreshUntil", { date: fmtDate(status.refresh_valid_until) })}
          </span>
        )}
      </div>

      {/* Step 1: credentials */}
      {showCredsForm && (
        <div className="advisor-form" style={{ flexWrap: "wrap" }}>
          <input className="search" placeholder={t("schwab.conn.appKey")} value={appKey}
            onChange={(e) => setAppKey(e.target.value)} style={{ width: 240 }} />
          <input className="search" type="password" placeholder={t("schwab.conn.secret")} value={secret}
            onChange={(e) => setSecret(e.target.value)} style={{ width: 200 }} />
          <input className="search" placeholder={t("schwab.conn.callback")} value={callback}
            onChange={(e) => setCallback(e.target.value)} style={{ width: 220 }} />
          <button className="congress-sync-btn" onClick={saveCreds}>{t("schwab.conn.saveCreds")}</button>
          {editingCreds && <button className="btn-ghost" onClick={() => setEditingCreds(false)}>✕</button>}
        </div>
      )}

      {/* Step 2: authorize */}
      {showAuthStep && (
        <div style={{ marginTop: 10 }}>
          <p style={{ fontSize: 11, color: "var(--text-3)", lineHeight: 1.5, margin: "0 0 8px" }}>
            {t("schwab.conn.step2")}
          </p>
          <div className="advisor-form" style={{ flexWrap: "wrap" }}>
            <button className="congress-sync-btn" onClick={openLogin}>{t("schwab.conn.openLogin")}</button>
            <input className="search" placeholder={t("schwab.conn.pasteRedirect")} value={redirect}
              onChange={(e) => setRedirect(e.target.value)} style={{ flex: 1, minWidth: 280 }} />
            <button className="congress-sync-btn" onClick={completeAuth} disabled={!redirect.trim()}>
              {t("schwab.conn.connect")}
            </button>
          </div>
        </div>
      )}

      {/* Connected actions */}
      {status.configured && !editingCreds && (
        <div style={{ marginTop: 10, display: "flex", gap: 8 }}>
          {status.connected && (
            <button className="btn-ghost" onClick={disconnect}>{t("schwab.conn.disconnect")}</button>
          )}
          <button className="btn-ghost" onClick={() => { setEditingCreds(true); setAppKey(""); setSecret(""); }}>
            {t("schwab.conn.changeCreds")}
          </button>
        </div>
      )}

      {msg && <div style={{ fontSize: 12, marginTop: 8, color: msg.startsWith(t("schwab.conn.error")) ? "var(--danger)" : "var(--success)" }}>{msg}</div>}
    </div>
  );
}
