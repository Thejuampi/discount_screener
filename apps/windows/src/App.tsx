import { useState, useEffect, useCallback, useMemo } from "react";
import { OpportunityList } from "./components/OpportunityList";
import { DetailPanel } from "./components/DetailPanel";
import { AlertsPanel } from "./components/AlertsPanel";
import { BacktestPanel } from "./components/BacktestPanel";
import { CongressOverviewPanel } from "./components/CongressOverview";
import { AdvisorPanel } from "./components/AdvisorPanel";
import { RegimeBanner } from "./components/RegimeBanner";
import { ScalpingPanel } from "./components/ScalpingPanel";
import { DashboardPanel } from "./components/DashboardPanel";
import { SettingsPanel } from "./components/SettingsPanel";
import { CommandPalette } from "./components/CommandPalette";
import { singleFlight } from "./singleFlight";
import { TickerSearch } from "./components/TickerSearch";
import { EstimatesPanel } from "./components/EstimatesPanel";
import { Toaster } from "./toast";
import { StatusBar } from "./components/StatusBar";
import type { Profile } from "./components/TechnicalAnalysisPanel";

type ViewMode = "dashboard" | "screener" | "congress" | "advisor" | "scalping" | "settings" | "estimates";
import { api } from "./api";
import type { OpportunityRow, UniverseProfileInfo } from "./api";
import { useT } from "./i18n";
import { useTheme } from "./theme";
import { useSignalAlerts } from "./useSignalAlerts";
import { useEmailNotifications } from "./useEmailNotifications";
import "./App.css";

const UNIVERSE_STORAGE_KEY = "ds_universe_profile";
const SCORING_STORAGE_KEY = "ds_scoring_model";
const SCORING_MODELS = ["aggressive_v2", "aggressive_v3", "short_v3"] as const;
type ScoringModelId = (typeof SCORING_MODELS)[number];

function isScoringModelId(value: string | null): value is ScoringModelId {
  return value === "aggressive_v2" || value === "aggressive_v3" || value === "short_v3";
}

export default function App() {
  const { t } = useT();
  const { theme, setTheme } = useTheme();

  const [rows, setRows] = useState<OpportunityRow[]>([]);
  useSignalAlerts(rows);
  useEmailNotifications(rows);
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null);
  const [showAlerts, setShowAlerts] = useState(false);
  const [showBacktest, setShowBacktest] = useState(false);
  const [autostartOn, setAutostartOn] = useState(false);
  const [filter, setFilter] = useState("");
  const [confidenceFilter, setConfidenceFilter] = useState<string>("all");
  const [universeProfiles, setUniverseProfiles] = useState<UniverseProfileInfo[]>([]);
  const [universeProfile, setUniverseProfile] = useState<string>(() => {
    const saved = localStorage.getItem(UNIVERSE_STORAGE_KEY);
    return saved && saved.length > 0 ? saved : "sp500";
  });
  const [assetFilter, setAssetFilter] = useState<"all" | "stock" | "etf" | "crypto">(() => {
    const saved = localStorage.getItem("ds_asset_filter");
    if (saved === "stock" || saved === "etf" || saved === "crypto" || saved === "all") return saved;
    return "all";
  });

  const handleAssetFilterChange = (f: "all" | "stock" | "etf" | "crypto") => {
    setAssetFilter(f);
    localStorage.setItem("ds_asset_filter", f);
    // These filters only apply to the screener — jump back to it from advisor/congress.
    setViewMode("screener");
    localStorage.setItem("ds_view_mode", "screener");
  };
  const [symbolsLoaded, setSymbolsLoaded] = useState(0);
  const [symbolsTotal, setSymbolsTotal] = useState(528);
  const [profile, setProfile] = useState<Profile>(() => {
    const saved = localStorage.getItem("ds_profile");
    return (saved === "investor" || saved === "swing" || saved === "daytrade") ? saved : "swing";
  });
  const [viewMode, setViewMode] = useState<ViewMode>(() => {
    const saved = localStorage.getItem("ds_view_mode");
    return (saved === "congress" || saved === "advisor" || saved === "scalping" || saved === "screener" || saved === "settings" || saved === "estimates") ? saved : "dashboard";
  });
  const [scoringModel, setScoringModel] = useState<string>(() => {
    const saved = localStorage.getItem(SCORING_STORAGE_KEY);
    return isScoringModelId(saved) ? saved : "aggressive_v3";
  });
  const handleViewModeChange = (v: ViewMode) => {
    setViewMode(v);
    localStorage.setItem("ds_view_mode", v);
  };

  const handleProfileChange = (p: Profile) => {
    setProfile(p);
    localStorage.setItem("ds_profile", p);
  };

  const openSymbol = useCallback((symbol: string) => {
    handleViewModeChange("screener");
    setSelectedSymbol(symbol);
  }, []);

  useEffect(() => {
    api.getAutostartEnabled().then(setAutostartOn).catch(console.error);
    api.listUniverseProfiles().then(setUniverseProfiles).catch(console.error);
    // Restore preferred scoring model: localStorage wins if set, else backend default.
    const saved = localStorage.getItem(SCORING_STORAGE_KEY);
    if (isScoringModelId(saved)) {
      api
        .setScoringModel(saved)
        .then((m) => {
          setScoringModel(m);
          localStorage.setItem(SCORING_STORAGE_KEY, m);
        })
        .catch(console.error);
    } else {
      api.getScoringModel().then((m) => {
        setScoringModel(m);
        localStorage.setItem(SCORING_STORAGE_KEY, m);
      }).catch(console.error);
    }
  }, []);

  const isShortMode = scoringModel === "short_v3";

  const toggleAutostart = async () => {
    const next = !autostartOn;
    try { await api.setAutostartEnabled(next); setAutostartOn(next); }
    catch (e) { console.error(e); }
  };

  const refresh = useMemo(() => singleFlight(async () => {
    const [opportunities, status] = await Promise.allSettled([
      api.getOpportunities(),
      api.getFeedStatus(),
    ]);
    if (opportunities.status === "fulfilled") {
      setRows(opportunities.value);
    } else {
      console.error("opportunity refresh failed", opportunities.reason);
    }

    if (status.status === "fulfilled") {
      setSymbolsLoaded(status.value.symbols_loaded);
      setSymbolsTotal(status.value.symbols_total);
      if (status.value.profile_name) {
        setUniverseProfile(status.value.profile_name);
      }
    } else {
      console.error("feed status refresh failed", status.reason);
    }
  }), []);

  const selectScoringModel = async (next: ScoringModelId) => {
    if (next === scoringModel) return;
    try {
      const m = await api.setScoringModel(next);
      setScoringModel(m);
      localStorage.setItem(SCORING_STORAGE_KEY, m);
      refresh();
    } catch (e) {
      console.error(e);
    }
  };

  const handleUniverseChange = async (name: string) => {
    if (!name || name === universeProfile) return;
    setUniverseProfile(name);
    localStorage.setItem(UNIVERSE_STORAGE_KEY, name);
    setSelectedSymbol(null);
    setRows([]);
    setSymbolsLoaded(0);
    try {
      const status = await api.setUniverseProfile(name);
      setSymbolsTotal(status.symbols_total);
      setSymbolsLoaded(status.symbols_loaded);
      setUniverseProfile(status.name);
      localStorage.setItem(UNIVERSE_STORAGE_KEY, status.name);
      void refresh();
    } catch (e) {
      console.error("universe switch failed", e);
    }
  };

  useEffect(() => {
    const saved = localStorage.getItem(UNIVERSE_STORAGE_KEY) || "sp500";
    // Apply saved universe (starts feed workers). startFeed is a no-op if already running.
    api
      .setUniverseProfile(saved)
      .then((status) => {
        setUniverseProfile(status.name);
        setSymbolsTotal(status.symbols_total);
        setSymbolsLoaded(status.symbols_loaded);
        localStorage.setItem(UNIVERSE_STORAGE_KEY, status.name);
      })
      .catch((e) => {
        console.error("universe restore failed", e);
        api.startFeed().catch(console.error);
      })
      .finally(() => {
        void refresh();
      });
  }, [refresh]);

  // Fast poll while the feed is still filling rows; slower once full.
  useEffect(() => {
    const loading = symbolsTotal === 0 || (rows.length < 8 && symbolsLoaded < symbolsTotal);
    const ms = loading ? 1500 : 5000;
    const interval = window.setInterval(() => { void refresh(); }, ms);
    return () => window.clearInterval(interval);
  }, [refresh, rows.length, symbolsLoaded, symbolsTotal]);

  const filtered = rows.filter((r) => {
    const matchText =
      filter === "" ||
      r.symbol.toLowerCase().includes(filter.toLowerCase()) ||
      (r.company_name?.toLowerCase().includes(filter.toLowerCase()) ?? false);
    const matchConf =
      confidenceFilter === "all" ||
      r.confidence === confidenceFilter ||
      (confidenceFilter === "qualified" && r.qualification === "Qualified");
    const matchAsset = assetFilter === "all" || r.asset_type === assetFilter;
    return matchText && matchConf && matchAsset;
  });

  return (
    <div className="app app-shell">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <span className="sidebar-logo" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M4 19h16" stroke="white" strokeWidth="2" strokeLinecap="round"/>
              <rect x="5" y="14" width="3" height="5" rx="0.8" fill="rgba(255,255,255,0.65)"/>
              <rect x="10" y="11" width="3" height="8" rx="0.8" fill="rgba(255,255,255,0.85)"/>
              <rect x="15" y="7" width="3" height="12" rx="0.8" fill="white"/>
              <polyline points="4,12 9,8 14,10 20,4" stroke="white" strokeWidth="1.6" fill="none" strokeLinecap="round" strokeLinejoin="round"/>
              <polyline points="17,4 20,4 20,7" stroke="white" strokeWidth="1.6" fill="none" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </span>
          <span className="sidebar-name">
            {t("app.title")}
            <span className="sidebar-tagline">investment intelligence</span>
          </span>
        </div>
        <nav className="sidebar-nav">
          {([
            ["dashboard", "🏠"], ["screener", "📈"], ["estimates", "Σ"], ["scalping", "⚡"], ["congress", "🏛"], ["advisor", "🧭"],
          ] as [ViewMode, string][]).map(([id, icon]) => (
            <button
              key={id}
              className={`sidebar-item ${viewMode === id ? "active" : ""}`}
              onClick={() => handleViewModeChange(id)}
            >
              <span className="sidebar-item-icon">{icon}</span>
              <span>{t(`view.${id}`)}</span>
            </button>
          ))}
        </nav>
        <div className="sidebar-foot">
          <button
            className={`sidebar-item ${viewMode === "settings" ? "active" : ""}`}
            onClick={() => handleViewModeChange("settings")}
          >
            <span className="sidebar-item-icon">⚙</span>
            <span>{t("view.settings")}</span>
          </button>
        </div>
      </aside>
      <div className="app-main">
      <header className="app-header">
        <div className="header-left">
          {viewMode === "screener" && (<>
          <TickerSearch
            onOpenSymbol={openSymbol}
            onQueryChange={setFilter}
          />
          <div
            className={`scoring-segment${isShortMode ? " scoring-segment--short" : ""}`}
            role="radiogroup"
            aria-label={t("scoring.group")}
          >
            {(
              [
                { id: "aggressive_v2" as const, labelKey: "scoring.longV2", titleKey: "scoring.longV2.hint" },
                { id: "aggressive_v3" as const, labelKey: "scoring.longV3", titleKey: "scoring.longV3.hint" },
                { id: "short_v3" as const, labelKey: "scoring.short", titleKey: "scoring.short.hint" },
              ] as const
            ).map((opt) => {
              const active = scoringModel === opt.id;
              return (
                <button
                  key={opt.id}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  className={`scoring-segment__btn${active ? " is-active" : ""}${opt.id === "short_v3" ? " is-short" : ""}`}
                  title={t(opt.titleKey)}
                  onClick={() => void selectScoringModel(opt.id)}
                >
                  {t(opt.labelKey)}
                </button>
              );
            })}
          </div>
          <select
            className="filter-select"
            value={confidenceFilter}
            onChange={(e) => setConfidenceFilter(e.target.value)}
          >
            <option value="all">{t("filter.all")}</option>
            <option value="High">{t("filter.high")}</option>
            <option value="Provisional">{t("filter.provisional")}</option>
            <option value="qualified">{t("filter.qualified")}</option>
          </select>

          <select
            className="filter-select universe-select"
            value={universeProfile}
            title={t("universe.hint")}
            onChange={(e) => void handleUniverseChange(e.target.value)}
            aria-label={t("universe.label")}
          >
            {(universeProfiles.length > 0
              ? universeProfiles
              : [{ name: universeProfile, description: "", symbol_count: symbolsTotal }]
            ).map((p) => (
              <option key={p.name} value={p.name}>
                {p.name.toUpperCase()}
                {p.symbol_count > 0 ? ` · ${p.symbol_count}` : ""}
              </option>
            ))}
          </select>

          {/* Asset type segmented filter */}
          <div className="asset-filter">
            {[
              { id: "all",    label: t("filter.type.all"),    icon: "◎" },
              { id: "stock",  label: t("filter.type.stocks"), icon: "📊" },
              { id: "etf",    label: t("filter.type.etfs"),   icon: "📦" },
              { id: "crypto", label: t("filter.type.crypto"), icon: "₿" },
            ].map((opt) => (
              <button
                key={opt.id}
                className={`asset-filter-btn ${assetFilter === opt.id ? "active" : ""}`}
                onClick={() => handleAssetFilterChange(opt.id as "all" | "stock" | "etf" | "crypto")}
                title={opt.label}
              >
                <span className="asset-filter-icon">{opt.icon}</span>
                <span className="asset-filter-label">{opt.label}</span>
              </button>
            ))}
          </div>
          </>)}
        </div>
        <div className="header-right">
          <button
            className="btn-ghost cmdk-trigger"
            title={t("cmd.placeholder")}
            onClick={() => window.dispatchEvent(new KeyboardEvent("keydown", { key: "k", ctrlKey: true }))}
          >
            <span className="cmdk-trigger-icon">⌕</span>
            <span className="cmdk-trigger-kbd">⌘K</span>
          </button>
          <button className="btn-ghost" onClick={() => setShowBacktest(!showBacktest)}>
            📊 {t("btn.backtest")}
          </button>
          <button className="btn-ghost" onClick={() => setShowAlerts(!showAlerts)}>
            🔔 {t("btn.alerts")}
          </button>
          <button className="btn-ghost" onClick={refresh}>
            ↺ {t("btn.refresh")}
          </button>
          <button className="btn-ghost" title={t("view.settings")} onClick={() => handleViewModeChange("settings")}>
            ⚙
          </button>
        </div>
      </header>

      <div className="main-layout">
        {viewMode === "dashboard" ? (
          <div className="congress-pane">
            <DashboardPanel
              rows={rows}
              symbolsLoaded={symbolsLoaded}
              symbolsTotal={symbolsTotal}
              onNavigate={handleViewModeChange}
              onOpenSymbol={openSymbol}
            />
          </div>
        ) : viewMode === "settings" ? (
          <div className="congress-pane">
            <SettingsPanel
              autostartOn={autostartOn}
              onToggleAutostart={toggleAutostart}
            />
          </div>
        ) : viewMode === "screener" ? (
          <>
            <div className={`list-pane ${selectedSymbol ? "narrow" : ""}`}>
              {!selectedSymbol && <RegimeBanner />}
              {isShortMode && (
                <div className="scoring-mode-banner scoring-mode-banner--short" role="status">
                  <span className="scoring-mode-banner__tag">{t("scoring.banner.short.tag")}</span>
                  <span className="scoring-mode-banner__text">{t("scoring.banner.short")}</span>
                </div>
              )}
              <OpportunityList
                rows={filtered}
                selectedSymbol={selectedSymbol}
                onSelect={setSelectedSymbol}
                symbolsLoaded={symbolsLoaded}
                symbolsTotal={symbolsTotal}
                scoringModel={scoringModel}
              />
            </div>

            {selectedSymbol && (
              <div className="detail-pane">
                <DetailPanel
                  symbol={selectedSymbol}
                  row={rows.find((r) => r.symbol === selectedSymbol) ?? null}
                  profile={profile}
                  onProfileChange={handleProfileChange}
                  onClose={() => setSelectedSymbol(null)}
                />
              </div>
            )}
          </>
        ) : viewMode === "estimates" ? (
          <div className="congress-pane">
            <EstimatesPanel />
          </div>
        ) : viewMode === "congress" ? (
          <div className="congress-pane">
            <CongressOverviewPanel />
          </div>
        ) : viewMode === "scalping" ? (
          <div className="congress-pane">
            <ScalpingPanel />
          </div>
        ) : (
          <div className="congress-pane">
            <AdvisorPanel
              rows={rows}
              onOpenSymbol={openSymbol}
            />
          </div>
        )}

        {showAlerts && (
          <div className="alerts-overlay">
            <AlertsPanel onClose={() => setShowAlerts(false)} />
          </div>
        )}

        {showBacktest && (
          <div className="backtest-overlay">
            <BacktestPanel onClose={() => setShowBacktest(false)} />
          </div>
        )}

      </div>

      <StatusBar rowCount={filtered.length} />
      </div>

      <CommandPalette
        rows={rows}
        onNavigate={handleViewModeChange}
        onOpenSymbol={openSymbol}
        onOpenSettings={() => handleViewModeChange("settings")}
        onToggleTheme={() => setTheme(theme === "dark" ? "light" : "dark")}
      />
      <Toaster />
    </div>
  );
}
