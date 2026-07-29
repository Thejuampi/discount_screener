import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import type { FmpSettingsStatus } from "../api";
import { useT } from "../i18n";

export function FmpConnect() {
  const { t } = useT();
  const [status, setStatus] = useState<FmpSettingsStatus | null>(null);
  const [statusUnavailable, setStatusUnavailable] = useState(false);
  const [apiKey, setApiKey] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(() => {
    setStatusUnavailable(false);
    api.fmpSettingsStatus()
      .then((value) => {
        setStatus(value);
        setStatusUnavailable(false);
      })
      .catch(() => {
        setStatus(null);
        setStatusUnavailable(true);
      });
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const save = async () => {
    setBusy(true);
    setMessage(null);
    try {
      setStatus(await api.fmpSaveKey(apiKey));
      setStatusUnavailable(false);
      setApiKey("");
      setMessage(t("fmp.settings.saved"));
    } catch (error) {
      setMessage(t("fmp.settings.saveError", { error: String(error) }));
    } finally {
      setBusy(false);
    }
  };

  const test = async () => {
    setBusy(true);
    setMessage(null);
    try {
      const result = await api.fmpTestKey();
      setMessage(t(`fmp.state.${result.state}`));
      refresh();
    } catch (error) {
      setMessage(t("fmp.settings.testError", { error: String(error) }));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    setMessage(null);
    try {
      setStatus(await api.fmpDeleteKey());
      setStatusUnavailable(false);
      setApiKey("");
      setMessage(t("fmp.settings.removed"));
    } catch (error) {
      setMessage(t("fmp.settings.removeError", { error: String(error) }));
    } finally {
      setBusy(false);
    }
  };

  const statusLabel = statusUnavailable
    ? t("fmp.settings.statusUnavailable")
    : status == null
      ? t("fmp.settings.statusLoading")
      : status.configured
        ? t("fmp.settings.configured")
        : t("fmp.settings.notConfigured");

  return (
    <div className="info-section fmp-connect">
      <h3>{t("fmp.settings.title")}</h3>
      <p className="settings-hint">{t("fmp.settings.hint")}</p>
      <div className="fmp-status-row">
        <span className={status?.configured ? "fmp-status-ok" : "fmp-status-muted"}>
          {statusLabel}
        </span>
        {status && (
          <span className={status.quota.warning ? "fmp-quota-warning" : "fmp-status-muted"}>
            {t("fmp.settings.quota", {
              attempts: status.quota.attempts,
              limit: status.quota.limit,
              remaining: status.quota.remaining,
            })}
          </span>
        )}
      </div>
      <div className="advisor-form fmp-key-form">
        <input
          className="search"
          type="password"
          autoComplete="off"
          placeholder={t("fmp.settings.keyPlaceholder")}
          value={apiKey}
          onChange={(event) => setApiKey(event.target.value)}
        />
        <button className="congress-sync-btn" disabled={busy || apiKey.trim() === ""} onClick={save}>
          {t("fmp.settings.save")}
        </button>
        <button className="btn-ghost" disabled={busy || !status?.configured} onClick={test}>
          {t("fmp.settings.test")}
        </button>
        <button className="btn-ghost" disabled={busy || !status?.configured} onClick={remove}>
          {t("fmp.settings.remove")}
        </button>
      </div>
      {message && <div className="fmp-connect-message">{message}</div>}
    </div>
  );
}
