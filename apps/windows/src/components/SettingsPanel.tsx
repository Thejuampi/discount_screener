import { useEffect, useState } from "react";
import { api } from "../api";
import { useTheme } from "../theme";
import { useT } from "../i18n";
import type { Lang } from "../i18n";
import { SchwabConnect } from "./SchwabConnect";
import { EmailNotifications } from "./EmailNotifications";

interface Props {
  autostartOn: boolean;
  onToggleAutostart: () => void;
}

export function SettingsPanel({ autostartOn, onToggleAutostart }: Props) {
  const { t, lang, setLang } = useT();
  const { theme, setTheme } = useTheme();
  const [name, setName] = useState(() => localStorage.getItem("ds_display_name") ?? "");
  const saveName = (v: string) => { setName(v); localStorage.setItem("ds_display_name", v); };

  return (
    <div className="congress-page">
      <header className="congress-header">
        <div>
          <h2 className="congress-title">{t("view.settings")}</h2>
          <p className="congress-subtitle">{t("settings.subtitle")}</p>
        </div>
      </header>

      {/* Preferences */}
      <div className="info-section">
        <h3>{t("settings.preferences")}</h3>
        <div className="settings-segment-row" style={{ marginTop: 10 }}>
          <span className="settings-segment-label">{t("settings.name")}</span>
          <input className="search" placeholder="Chris" value={name} onChange={(e) => saveName(e.target.value)} style={{ width: 220 }} />
        </div>
        <div className="settings-hint" style={{ marginBottom: 8 }}>{t("settings.nameHint")}</div>
        <div className="settings-segment-row">
          <span className="settings-segment-label">{t("settings.theme")}</span>
          <div className="settings-segment">
            <button className={`seg-btn ${theme === "dark" ? "active" : ""}`} onClick={() => setTheme("dark")}>🌙 {t("settings.theme.dark")}</button>
            <button className={`seg-btn ${theme === "light" ? "active" : ""}`} onClick={() => setTheme("light")}>☀ {t("settings.theme.light")}</button>
          </div>
        </div>
        <div className="settings-segment-row">
          <span className="settings-segment-label">{t("settings.language")}</span>
          <div className="settings-segment">
            <button className={`seg-btn ${lang === "es" ? "active" : ""}`} onClick={() => setLang("es" as Lang)}>🇪🇸 ES</button>
            <button className={`seg-btn ${lang === "en" ? "active" : ""}`} onClick={() => setLang("en" as Lang)}>🇬🇧 EN</button>
          </div>
        </div>
        <label className="settings-row" style={{ marginTop: 6 }}>
          <input type="checkbox" checked={autostartOn} onChange={onToggleAutostart} />
          <span>{t("settings.autostart")}</span>
        </label>
        <div className="settings-hint">{t("settings.hint")}</div>
      </div>

      {/* Data source */}
      <h3 className="settings-group-title">{t("settings.group.data")}</h3>
      <SchwabConnect />
      <div className="info-section">
        <SchwabImportButton />
      </div>

      {/* Notifications */}
      <h3 className="settings-group-title">{t("settings.group.notif")}</h3>
      <EmailNotifications />
    </div>
  );
}

/** Imports Schwab Equity Ratings PDFs (single or batch). */
function SchwabImportButton() {
  const { t } = useT();
  const [count, setCount] = useState(0);
  const [status, setStatus] = useState<string | null>(null);

  useEffect(() => {
    api.countSchwabReports().then(setCount).catch(console.error);
  }, []);

  const handleFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    setStatus("…");
    let imported = 0;
    const errors: string[] = [];
    for (const file of Array.from(files)) {
      try {
        const buf = await file.arrayBuffer();
        const bytes = Array.from(new Uint8Array(buf));
        const report = await api.importSchwabPdf(bytes, file.name);
        setStatus(t("schwab.importSuccess", { symbol: report.symbol, rating: report.rating }));
        imported++;
      } catch (e) {
        errors.push(`${file.name}: ${e}`);
        console.error("schwab import", e);
      }
    }
    setCount(await api.countSchwabReports());
    setStatus(errors.length > 0 ? t("schwab.importFailed", { error: errors[0] }) : `✓ ${imported}/${files.length}`);
    setTimeout(() => setStatus(null), 4000);
  };

  return (
    <div className="schwab-import-block">
      <label className="schwab-import-btn">
        📊 {t("settings.schwabImport")}
        <input type="file" accept="application/pdf" multiple style={{ display: "none" }}
          onChange={(e) => handleFiles(e.target.files)} />
      </label>
      <div className="schwab-import-status">{status ?? t("settings.schwabCount", { n: count })}</div>
    </div>
  );
}
