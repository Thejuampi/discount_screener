import { useEffect, useState } from "react";
import { api } from "../api";
import type { TipRanksSettingsStatus } from "../api";
import { useT } from "../i18n";

export function TipRanksConnect() {
  const { t } = useT();
  const [status, setStatus] = useState<TipRanksSettingsStatus | null>(null);
  const [apiKey, setApiKey] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [unavailable, setUnavailable] = useState(false);

  useEffect(() => {
    var active = true;
    api.tipranksSettingsStatus()
      .then((value) => {
        if (!active) return;
        setStatus(value);
        setUnavailable(false);
      })
      .catch(() => {
        if (!active) return;
        setUnavailable(true);
        setStatus(null);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  async function onSave() {
    try {
      setStatus(await api.tipranksSaveKey(apiKey));
      setApiKey("");
      setMessage(t("tipranks.settings.saved"));
    } catch (error) {
      setMessage(t("tipranks.settings.saveError", { error: String(error) }));
    }
  }

  async function onTest() {
    try {
      var result = await api.tipranksTestKey();
      setMessage(t(`tipranks.state.${result.state}`));
      setStatus(await api.tipranksSettingsStatus());
    } catch (error) {
      setMessage(t("tipranks.settings.testError", { error: String(error) }));
    }
  }

  async function onRemove() {
    try {
      setStatus(await api.tipranksDeleteKey());
      setMessage(t("tipranks.settings.removed"));
    } catch (error) {
      setMessage(t("tipranks.settings.removeError", { error: String(error) }));
    }
  }

  var statusText = unavailable
    ? t("tipranks.settings.statusUnavailable")
    : loading
      ? t("tipranks.settings.statusLoading")
      : status?.configured
        ? t("tipranks.settings.configured")
        : t("tipranks.settings.notConfigured");

  return (
    <div className="info-section tipranks-connect">
      <h3>{t("tipranks.settings.title")}</h3>
      <p className="settings-hint">{t("tipranks.settings.hint")}</p>
      <div className="tipranks-status-row">
        <span className={status?.configured ? "tipranks-status-ok" : "tipranks-status-muted"}>
          {statusText}
        </span>
        {status && (
          <span className={status.quota.warning ? "tipranks-quota-warning" : "tipranks-status-muted"}>
            {t("tipranks.settings.quota", {
              attempts: status.quota.attempts,
              limit: status.quota.limit,
              remaining: status.quota.remaining,
            })}
            {status.quota.estimated ? ` · ${t("tipranks.quota.estimated")}` : ""}
          </span>
        )}
      </div>
      <div className="advisor-form tipranks-key-form">
        <input
          className="search"
          type="password"
          autoComplete="off"
          value={apiKey}
          onChange={(e) => setApiKey(e.target.value)}
          placeholder={t("tipranks.settings.keyPlaceholder")}
        />
        <button className="btn-primary" onClick={onSave} disabled={!apiKey.trim()}>
          {t("tipranks.settings.save")}
        </button>
        <button className="btn-secondary" onClick={onTest} disabled={!status?.configured}>
          {t("tipranks.settings.test")}
        </button>
        <button className="btn-secondary" onClick={onRemove} disabled={!status?.configured}>
          {t("tipranks.settings.remove")}
        </button>
      </div>
      {message && <div className="tipranks-connect-message">{message}</div>}
    </div>
  );
}
