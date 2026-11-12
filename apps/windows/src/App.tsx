import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { OpportunityList, type OpportunitySort } from "./components/OpportunityList";
import { AndroidLongV3List } from "./components/AndroidLongV3List";
import { MarketsUpsideList } from "./components/MarketsUpsideList";
import { RestoredUniverseList } from "./components/RestoredUniverseList";
import { WatchlistPanel } from "./components/WatchlistPanel";
import { TrackedFeedPanel } from "./components/TrackedFeedPanel";
import { DetailPanel } from "./components/DetailPanel";
import { AlertsPanel } from "./components/AlertsPanel";
import { BacktestPanel } from "./components/BacktestPanel";
import { CongressOverviewPanel } from "./components/CongressOverview";
import { AdvisorPanel } from "./components/AdvisorPanel";
import { RegimeBanner } from "./components/RegimeBanner";
import { ScalpingPanel } from "./components/ScalpingPanel";
import { DashboardPanel } from "./components/DashboardPanel";
import { DashboardEditionToggle, DashboardV2Panel, type DashboardEdition } from "./components/DashboardV2Panel";
import { SettingsPanel } from "./components/SettingsPanel";
import { CommandPalette } from "./components/CommandPalette";
import { canPublishModelRows, createGenerationRefresh } from "./opportunityRefresh";
import { createFeedRefreshRequest, isFeedRefreshBusy, type FeedRefreshRequestState } from "./feedRefresh";
import { TickerSearch } from "./components/TickerSearch";
import { EstimatesPanel } from "./components/EstimatesPanel";
import { Toaster } from "./toast";
import { StatusBar } from "./components/StatusBar";
import type { Profile } from "./components/TechnicalAnalysisPanel";

import { api } from "./api";
import type { AndroidLongV3Status, FeedStatus, OpportunityRow, SavedUniverseRow, TrackedFeedStatus, UniverseProfileInfo } from "./api";
import { useT } from "./i18n";
import { useTheme } from "./theme";
import { useSignalAlerts } from "./useSignalAlerts";
import { useEmailNotifications } from "./useEmailNotifications";
import { getScoringPresentation, isScoringModelId, type ScoringModelId } from "./scoringPresentation";
import { warmMarketContext } from "./marketContextWarmup";
import { canonicalUniverseName, planUniverseBoot } from "./universeBoot";
import { visibleRestoredUniverseRows } from "./restoredUniverse";
import { createWatchlist, INITIAL_WATCHLIST } from "./watchlist";
import { filterAndroidLongV3 } from "./androidLongV3";
import { projectMarketUpside } from "./marketUpside";
import { singleFlight } from "./singleFlight";
import { UiInspectProvider, UiInspectRoot } from "./uiInspect";
import "./App.css";

type ViewMode = "dashboard" | "screener" | "watchlist" | "congress" | "advisor" | "scalping" | "settings" | "estimates";
type MarketsView = "opportunities" | "upside";

const UNIVERSE_STORAGE_KEY = "ds_universe_profile";
const SCORING_STORAGE_KEY = "ds_scoring_model";
const REGIME_SCORING_KEY = "ds_regime_scoring";
const DASHBOARD_EDITION_KEY = "ds_dashboard_edition";

function readDashboardEdition(): DashboardEdition {
  const saved = localStorage.getItem(DASHBOARD_EDITION_KEY);
  return saved === "v2" ? "v2" : "legacy";
}

export default function App() {
  const { t, lang } = useT();
  const { theme, setTheme } = useTheme();

  const [appVersion, setAppVersion] = useState<string>("");
  const [rows, setRows] = useState<OpportunityRow[]>([]);
  const [savedUniverse, setSavedUniverse] = useState<{
    profile: string;
    rows: SavedUniverseRow[];
    unavailable: boolean;
  } | null>(null);
  const [trackedFeedRead, setTrackedFeedRead] = useState<{
    profile: string;
    feed: TrackedFeedStatus | null;
  } | null>(null);
  const [trackedFeedWriteFailed, setTrackedFeedWriteFailed] = useState(false);
  const [universeReadRevision, setUniverseReadRevision] = useState(0);
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null);
  const [watchlist, setWatchlist] = useState(INITIAL_WATCHLIST);
  const watchlistController = useMemo(() => createWatchlist({
    read: api.getWatchlist,
    write: api.setWatchlistMember,
    publish: setWatchlist,
  }), []);
  useEffect(() => {
    void watchlistController.load();
    return watchlistController.cancelRead;
  }, [watchlistController]);
  const selectedWatchlistSymbol = selectedSymbol?.trim().toUpperCase() ?? "";
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
  /** Launch --profile / DS_UNIVERSE_PROFILE lock: backend wins over localStorage. */
  const [profileLocked, setProfileLocked] = useState(false);
  const [universeSwitching, setUniverseSwitching] = useState(false);
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
  const [feedStatus, setFeedStatus] = useState<FeedStatus | null>(null);
  const [feedRefreshRequest, setFeedRefreshRequest] = useState<FeedRefreshRequestState>("idle");
  const [profile, setProfile] = useState<Profile>(() => {
    const saved = localStorage.getItem("ds_profile");
    return (saved === "investor" || saved === "swing" || saved === "daytrade") ? saved : "swing";
  });
  const [viewMode, setViewMode] = useState<ViewMode>(() => {
    const saved = localStorage.getItem("ds_view_mode");
    return (saved === "congress" || saved === "advisor" || saved === "scalping" || saved === "screener" || saved === "watchlist" || saved === "settings" || saved === "estimates") ? saved : "dashboard";
  });
  const [scoringModel, setScoringModel] = useState<ScoringModelId>(() => {
    const saved = localStorage.getItem(SCORING_STORAGE_KEY);
    return isScoringModelId(saved) ? saved : "aggressive_v3";
  });
  const [androidLongV3, setAndroidLongV3] = useState(false);
  const [marketsView, setMarketsView] = useState<MarketsView>("opportunities");
  const [opportunitySort, setOpportunitySort] = useState<OpportunitySort>({
    key: "composite_score",
    ascending: false,
  });
  const [androidV3Result, setAndroidV3Result] = useState<{
    profile: string;
    revision: number;
    status: AndroidLongV3Status | null;
    failed: boolean;
  } | null>(null);
  const [regimeScoring, setRegimeScoring] = useState(() => {
    const saved = localStorage.getItem(REGIME_SCORING_KEY);
    return saved === null ? true : saved === "1";
  });
  const [dashboardEdition, setDashboardEdition] = useState<DashboardEdition>(readDashboardEdition);
  const [modelReady, setModelReady] = useState(false);
  const scoringModelRef = useRef(scoringModel);
  const requestedModelRef = useRef(scoringModel);
  const modelSwitchingRef = useRef(false);
  const dataGenerationRef = useRef(0);
  const universeGenerationRef = useRef(0);
  const universeSwitchingRef = useRef(false);
  useSignalAlerts(rows, scoringModel);
  useEmailNotifications(rows, scoringModel);
  const handleViewModeChange = (v: ViewMode) => {
    if (v === "watchlist") setSelectedSymbol(null);
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

  // Debug-only agent bridge (WebView CDP / ds-ui). Not shipped in release production builds.
  useEffect(() => {
    void api.getAppVersion().then(setAppVersion);
  }, []);

  useEffect(() => {
    if (!import.meta.env.DEV) return;
    type AgentBridge = {
      version: number;
      openSymbol: (symbol: string) => void;
      closeDetail: () => void;
      setViewMode: (mode: ViewMode) => void;
      setListFilter: (query: string) => void;
      setAssetFilter: (filter: "all" | "stock" | "etf" | "crypto") => void;
      snapshot: () => {
        selectedSymbol: string | null;
        viewMode: ViewMode;
        listFilter: string;
        assetFilter: string;
        symbols: string[];
        profileLocked: boolean;
        universeProfile: string;
        scoringModel: string;
      };
    };
    const bridge: AgentBridge = {
      version: 1,
      openSymbol: (symbol: string) => {
        const sym = String(symbol || "").trim().toUpperCase();
        if (!sym) return;
        setFilter("");
        openSymbol(sym);
      },
      closeDetail: () => setSelectedSymbol(null),
      setViewMode: (mode: ViewMode) => handleViewModeChange(mode),
      setListFilter: (query: string) => setFilter(query),
      setAssetFilter: (next) => handleAssetFilterChange(next),
      snapshot: () => ({
        selectedSymbol,
        viewMode,
        listFilter: filter,
        assetFilter,
        symbols: rows.map((r) => r.symbol),
        profileLocked,
        universeProfile,
        scoringModel,
      }),
    };
    (window as Window & { __DS_AGENT__?: AgentBridge }).__DS_AGENT__ = bridge;
    return () => {
      const w = window as Window & { __DS_AGENT__?: AgentBridge };
      if (w.__DS_AGENT__ === bridge) delete w.__DS_AGENT__;
    };
  }, [
    openSymbol,
    selectedSymbol,
    viewMode,
    filter,
    assetFilter,
    rows,
    profileLocked,
    universeProfile,
    scoringModel,
  ]);

  useEffect(() => {
    api.getAutostartEnabled().then(setAutostartOn).catch(console.error);
    api.listUniverseProfiles().then(setUniverseProfiles).catch(console.error);
    void warmMarketContext(api.getMarketRegime);
  }, []);

  const scoringPresentation = getScoringPresentation(scoringModel);

  const toggleAutostart = async () => {
    const next = !autostartOn;
    try { await api.setAutostartEnabled(next); setAutostartOn(next); }
    catch (e) { console.error(e); }
  };

  // The callback reads mutable coordination refs only when invoked, never during render.
  // eslint-disable-next-line react-hooks/refs
  const refreshRows = useMemo(() => createGenerationRefresh({
    generation: () => `${dataGenerationRef.current}:${universeGenerationRef.current}`,
    canRefresh: () => !modelSwitchingRef.current && !universeSwitchingRef.current,
    fetch: () => api.getOpportunities().catch((error) => {
      console.error("opportunity refresh failed", error);
      return null;
    }),
    publish: (opportunities) => {
      if (opportunities) setRows(opportunities);
    },
  }), []);

  // eslint-disable-next-line react-hooks/refs
  const refreshFeedStatus = useMemo(() => createGenerationRefresh({
    generation: () => `${dataGenerationRef.current}:${universeGenerationRef.current}`,
    canRefresh: () => !modelSwitchingRef.current && !universeSwitchingRef.current,
    fetch: () => api.getFeedStatus().catch((error) => {
      console.error("feed status refresh failed", error);
      return null;
    }),
    publish: (status) => {
      if (!status) return;
      setFeedStatus(status);
      setSymbolsLoaded(status.symbols_loaded);
      setSymbolsTotal(status.symbols_total);
      setUniverseProfile(status.profile_name);
      setProfileLocked(status.profile_locked);
    },
  }), []);

  const refresh = useCallback(() => {
    void refreshRows();
    void refreshFeedStatus();
  }, [refreshRows, refreshFeedStatus]);

  // eslint-disable-next-line react-hooks/refs
  const requestFeedRefresh = useMemo(() => createFeedRefreshRequest({
    generation: () => universeGenerationRef.current,
    request: api.requestFeedRefresh,
    onStateChange: (state) => {
      setFeedRefreshRequest(state);
      if (state !== "requesting") void refreshFeedStatus();
    },
  }), [refreshFeedStatus]);

  const feedRefreshBusy = feedRefreshRequest === "requesting" || isFeedRefreshBusy(feedStatus?.refresh?.phase);
  const canRequestFeedRefresh = feedStatus?.running && !universeSwitching && !feedRefreshBusy;

  useEffect(() => {
    let cancelled = false;
    const restore = async () => {
      const saved = localStorage.getItem(SCORING_STORAGE_KEY);
      try {
        const raw = isScoringModelId(saved) ? await api.setScoringModel(saved) : await api.getScoringModel();
        if (!isScoringModelId(raw)) throw new Error(`Invalid scoring model from backend: ${raw}`);
        if (cancelled) return;
        scoringModelRef.current = raw;
        requestedModelRef.current = raw;
        setScoringModel(raw);
        localStorage.setItem(SCORING_STORAGE_KEY, raw);
      } catch (error) {
        console.error(error);
      }
      // Regime toggle is best-effort — must not block modelReady / feed restore.
      try {
        const wantRegime = localStorage.getItem(REGIME_SCORING_KEY);
        const enabled = wantRegime === null ? true : wantRegime === "1";
        const backendRegime = await api.setRegimeScoringEnabled(enabled);
        if (!cancelled) {
          setRegimeScoring(backendRegime);
          localStorage.setItem(REGIME_SCORING_KEY, backendRegime ? "1" : "0");
        }
      } catch (error) {
        console.error("regime scoring toggle unavailable", error);
      } finally {
        if (!cancelled) setModelReady(true);
      }
    };
    void restore();
    return () => { cancelled = true; };
  }, []);

  const toggleRegimeScoring = async () => {
    const next = !regimeScoring;
    const dataGeneration = dataGenerationRef.current;
    const universeGeneration = universeGenerationRef.current;
    try {
      const enabled = await api.setRegimeScoringEnabled(next);
      setRegimeScoring(enabled);
      localStorage.setItem(REGIME_SCORING_KEY, enabled ? "1" : "0");
      const nextRows = await api.getOpportunities();
      if (dataGeneration === dataGenerationRef.current &&
          universeGeneration === universeGenerationRef.current &&
          !modelSwitchingRef.current && !universeSwitchingRef.current) {
        setRows(nextRows);
      }
    } catch (e) {
      console.error(e);
    }
  };

  const handleDashboardEdition = (edition: DashboardEdition) => {
    setDashboardEdition(edition);
    localStorage.setItem(DASHBOARD_EDITION_KEY, edition);
    // Dashboard 2.0 only supports V3 long/short — migrate off V2 on enter.
    if (edition === "v2" && scoringModelRef.current === "aggressive_v2") {
      void selectScoringModel("aggressive_v3");
    }
  };

  const selectScoringModel = async (next: ScoringModelId) => {
    requestedModelRef.current = next;
    if (modelSwitchingRef.current || next === scoringModelRef.current) return;
    modelSwitchingRef.current = true;
    const generation = ++dataGenerationRef.current;
    const initialUniverseGeneration = universeGenerationRef.current;
    let refreshAfterUniverseChange = false;
    try {
      let backendModel = scoringModelRef.current;
      while (true) {
        const target = requestedModelRef.current;
        if (target !== backendModel) {
          const response = await api.setScoringModel(target);
          if (!isScoringModelId(response) || response !== target) {
            throw new Error(`Backend rejected scoring model ${target}: ${response}`);
          }
          backendModel = target;
        }
        if (requestedModelRef.current !== target) continue;
        if (target === scoringModelRef.current) break;
        const universeGeneration = universeGenerationRef.current;
        const [nextRows, status] = await Promise.all([api.getOpportunities(), api.getFeedStatus()]);
        if (requestedModelRef.current !== target) continue;
        scoringModelRef.current = target;
        setScoringModel(target);
        if (canPublishModelRows(
          initialUniverseGeneration,
          universeGeneration,
          universeGenerationRef.current,
          universeSwitchingRef.current,
        )) {
          setRows(nextRows);
          setFeedStatus(status);
          setSymbolsLoaded(status.symbols_loaded);
          setSymbolsTotal(status.symbols_total);
        } else {
          refreshAfterUniverseChange = true;
        }
        localStorage.setItem(SCORING_STORAGE_KEY, target);
        break;
      }
    } catch (e) {
      console.error(e);
      requestedModelRef.current = scoringModelRef.current;
      try { await api.setScoringModel(scoringModelRef.current); } catch (rollbackError) { console.error(rollbackError); }
    } finally {
      if (generation === dataGenerationRef.current) {
        modelSwitchingRef.current = false;
        if (refreshAfterUniverseChange || initialUniverseGeneration !== universeGenerationRef.current) {
          void refresh();
        }
      }
    }
  };

  const handleUniverseChange = async (name: string) => {
    if (!name || name === universeProfile || profileLocked || universeSwitchingRef.current) return;
    const generation = ++universeGenerationRef.current;
    universeSwitchingRef.current = true;
    setUniverseSwitching(true);
    setUniverseProfile(name);
    localStorage.setItem(UNIVERSE_STORAGE_KEY, name);
    setSelectedSymbol(null);
    setRows([]);
    setSavedUniverse(null);
    setTrackedFeedRead(null);
    setTrackedFeedWriteFailed(false);
    setSymbolsLoaded(0);
    setFeedStatus(null);
    setFeedRefreshRequest("idle");
    try {
      const status = await api.setUniverseProfile(name);
      if (generation !== universeGenerationRef.current) return;
      setSymbolsTotal(status.symbols_total);
      setSymbolsLoaded(status.symbols_loaded);
      setUniverseProfile(status.name);
      setProfileLocked(!!status.profile_locked);
      // Persist canonical id only (never alias `test`).
      localStorage.setItem(UNIVERSE_STORAGE_KEY, status.name);
    } catch (e) {
      if (generation !== universeGenerationRef.current) return;
      console.error("universe switch failed", e);
      // Restore UI from backend if switch was rejected (e.g. locked).
      try {
        const status = await api.getUniverseProfile();
        setUniverseProfile(status.name);
        setSymbolsTotal(status.symbols_total);
        setSymbolsLoaded(status.symbols_loaded);
        setProfileLocked(!!status.profile_locked);
        localStorage.setItem(UNIVERSE_STORAGE_KEY, canonicalUniverseName(status.name));
      } catch (restoreErr) {
        console.error(restoreErr);
      }
    } finally {
      if (generation === universeGenerationRef.current) {
        universeSwitchingRef.current = false;
        setUniverseSwitching(false);
        void refresh();
      }
    }
  };

  useEffect(() => {
    if (!modelReady) return;
    const generation = ++universeGenerationRef.current;
    universeSwitchingRef.current = true;
    const saved = localStorage.getItem(UNIVERSE_STORAGE_KEY);
    // If launch locked the profile, backend wins — never re-apply localStorage sp500.
    void (async () => {
      try {
        const current = await api.getUniverseProfile();
        if (generation !== universeGenerationRef.current) return;
        setUniverseSwitching(true);
        setProfileLocked(!!current.profile_locked);
        const plan = planUniverseBoot(
          {
            name: current.name,
            symbols_total: current.symbols_total,
            symbols_loaded: current.symbols_loaded,
            profile_locked: !!current.profile_locked,
          },
          saved,
        );
        if (plan.kind === "use_locked") {
          setUniverseProfile(plan.name);
          setSymbolsTotal(current.symbols_total);
          setSymbolsLoaded(current.symbols_loaded);
          // A forced QA launch is temporary: preserve the user's normal
          // universe preference for the next unforced application start.
          await api.startFeed();
          return;
        }
        const status = await api.setUniverseProfile(plan.name);
        if (generation !== universeGenerationRef.current) return;
        setUniverseProfile(status.name);
        setSymbolsTotal(status.symbols_total);
        setSymbolsLoaded(status.symbols_loaded);
        setProfileLocked(!!status.profile_locked);
        localStorage.setItem(UNIVERSE_STORAGE_KEY, canonicalUniverseName(status.name));
      } catch (e) {
        if (generation !== universeGenerationRef.current) return;
        console.error("universe restore failed", e);
        try {
          await api.startFeed();
        } catch (feedErr) {
          console.error(feedErr);
        }
      } finally {
        if (generation === universeGenerationRef.current) {
          universeSwitchingRef.current = false;
          setUniverseSwitching(false);
          void refresh();
        }
      }
    })();
  }, [refresh, modelReady]);

  const confirmedProfile =
    modelReady && !universeSwitching && feedStatus?.profile_name === universeProfile
      ? universeProfile
      : null;
  const activeTrackedFeed = trackedFeedRead?.profile === universeProfile ? trackedFeedRead : null;
  const showingUpside = viewMode === "screener" && marketsView === "upside";
  const showingAndroidV3 = viewMode === "screener" && marketsView === "opportunities" && androidLongV3;

  useEffect(() => {
    if (!confirmedProfile || universeSwitchingRef.current) return;
    const generation = universeGenerationRef.current;
    let cancelled = false;
    const isCurrent = () =>
      !cancelled && generation === universeGenerationRef.current && !universeSwitchingRef.current;
    const publishSavedRows = (savedRows: SavedUniverseRow[], unavailable: boolean) => {
      if (isCurrent()) {
        setSavedUniverse({ profile: confirmedProfile, rows: savedRows, unavailable });
      }
    };
    const publishTrackedFeed = (feed: TrackedFeedStatus | null) => {
      if (isCurrent()) {
        setTrackedFeedRead({ profile: confirmedProfile, feed: feed?.profile === confirmedProfile ? feed : null });
      }
    };
    void api.getSavedUniverseRows(confirmedProfile).then(
      (savedRows) => publishSavedRows(savedRows, false),
      () => publishSavedRows([], true),
    );
    void api.getTrackedFeed(confirmedProfile).then(
      publishTrackedFeed,
      () => publishTrackedFeed(null),
    );
    return () => { cancelled = true; };
  }, [confirmedProfile, universeReadRevision]);

  useEffect(() => {
    if (!showingAndroidV3 || !confirmedProfile || universeSwitchingRef.current) return;
    const generation = universeGenerationRef.current;
    let cancelled = false;
    const read = singleFlight(async () => {
      let status: AndroidLongV3Status | null = null;
      try {
        const response = await api.getAndroidLongV3(confirmedProfile);
        if (response.profile === confirmedProfile) status = response;
      } catch {
        // Keep the last result for this universe while the next poll retries.
      }
      if (cancelled || generation !== universeGenerationRef.current || universeSwitchingRef.current) return;
      setAndroidV3Result((current) => ({
        profile: confirmedProfile,
        revision: universeReadRevision,
        status: status ?? (current?.profile === confirmedProfile && current.revision === universeReadRevision
          ? current.status : null),
        failed: status === null,
      }));
    });
    void read();
    const timer = window.setInterval(read, 5_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [showingAndroidV3, confirmedProfile, universeReadRevision]);

  const retryTrackedFeed = () => {
    setTrackedFeedRead(null);
    setUniverseReadRevision((revision) => revision + 1);
  };

  const handleTrackedFeedChange = async (symbol: string, included: boolean) => {
    if (!confirmedProfile || !activeTrackedFeed?.feed?.editable || universeSwitchingRef.current) return;
    const generation = ++universeGenerationRef.current;
    universeSwitchingRef.current = true;
    setUniverseSwitching(true);
    setTrackedFeedWriteFailed(false);
    try {
      const feed = await api.setTrackedFeedMember(confirmedProfile, symbol, included);
      if (generation !== universeGenerationRef.current) return;
      setTrackedFeedRead({ profile: confirmedProfile, feed });
      setSymbolsTotal(feed.symbols.length);
      setFeedRefreshRequest("idle");
      if (!feed.symbols.includes(symbol)) {
        setRows((current) => current.filter((row) => row.symbol !== symbol));
        setSavedUniverse((current) => current?.profile === confirmedProfile
          ? { ...current, rows: current.rows.filter((row) => row.symbol !== symbol) }
          : current);
      }
    } catch {
      if (generation === universeGenerationRef.current) setTrackedFeedWriteFailed(true);
    } finally {
      if (generation === universeGenerationRef.current) {
        universeSwitchingRef.current = false;
        setUniverseSwitching(false);
        setUniverseReadRevision((revision) => revision + 1);
        void refresh();
      }
    }
  };

  // Fast poll while the feed is still filling rows; slower once full.
  useEffect(() => {
    const loading = symbolsTotal === 0 || (rows.length < 8 && symbolsLoaded < symbolsTotal);
    const ms = loading ? 1500 : 5000;
    const interval = window.setInterval(() => { void refreshRows(); }, ms);
    return () => window.clearInterval(interval);
  }, [refreshRows, rows.length, symbolsLoaded, symbolsTotal]);

  useEffect(() => {
    const interval = window.setInterval(() => { void refreshFeedStatus(); }, feedRefreshBusy ? 1500 : 3000);
    return () => window.clearInterval(interval);
  }, [refreshFeedStatus, feedRefreshBusy]);

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
  const activeSavedUniverse = savedUniverse?.profile === confirmedProfile ? savedUniverse : null;
  const activeAndroidV3 = showingAndroidV3 && androidV3Result?.profile === confirmedProfile
    && androidV3Result.revision === universeReadRevision ? androidV3Result : null;
  const androidRows = filterAndroidLongV3(activeAndroidV3?.status?.rows ?? [], filter);
  const androidIncomplete = filterAndroidLongV3(activeAndroidV3?.status?.incomplete ?? [], filter);
  const upsideSymbols = activeTrackedFeed?.feed?.symbols;
  const upsideRows = useMemo(
    () => showingUpside && upsideSymbols ? projectMarketUpside(upsideSymbols, rows, filter) : [],
    [showingUpside, upsideSymbols, rows, filter],
  );
  const restoredRows = activeSavedUniverse
    ? visibleRestoredUniverseRows(activeSavedUniverse.rows, rows, filter, assetFilter)
    : [];

  const uiAppContext = useMemo(
    () => ({
      view: viewMode,
      scoringModel,
      regimeScoring,
      dashboardEdition,
      profile,
      lang,
      theme,
      assetFilter,
      universeProfile,
    }),
    [
      viewMode,
      scoringModel,
      regimeScoring,
      dashboardEdition,
      profile,
      lang,
      theme,
      assetFilter,
      universeProfile,
    ],
  );

  return (
    <UiInspectProvider appContext={uiAppContext}>
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
            {appVersion && <span className="sidebar-version">{appVersion}</span>}
          </span>
        </div>
        <nav className="sidebar-nav">
          {([
            ["dashboard", "🏠"], ["screener", "📈"], ["watchlist", null], ["estimates", "Σ"], ["scalping", "⚡"], ["congress", "🏛"], ["advisor", "🧭"],
          ] as [ViewMode, string | null][]).map(([id, icon]) => (
            <button
              key={id}
              className={`sidebar-item ${viewMode === id ? "active" : ""}`}
              onClick={() => handleViewModeChange(id)}
            >
              <span className="sidebar-item-icon" aria-hidden={icon === null ? true : undefined}>
                {icon ?? (
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
                    <path d="M4 2.5h8v11l-4-2.75-4 2.75z" strokeLinejoin="round" />
                  </svg>
                )}
              </span>
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
          {!showingUpside && <>
          <div
            className={`scoring-segment${!androidLongV3 && scoringPresentation.isShort ? " scoring-segment--short" : ""}`}
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
              const active = !androidLongV3 && scoringModel === opt.id;
              return (
                <button
                  key={opt.id}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  className={`scoring-segment__btn${active ? " is-active" : ""}${opt.id === "short_v3" ? " is-short" : ""}`}
                  title={t(opt.titleKey)}
                  onClick={() => {
                    setAndroidLongV3(false);
                    void selectScoringModel(opt.id);
                  }}
                >
                  {t(opt.labelKey)}
                </button>
              );
            })}
            <button
              type="button"
              role="radio"
              aria-checked={androidLongV3}
              className={`scoring-segment__btn${androidLongV3 ? " is-active" : ""}`}
              onClick={() => setAndroidLongV3(true)}
            >
              Android Long V3
            </button>
          </div>
          {!androidLongV3 && (scoringModel === "aggressive_v3" || scoringModel === "short_v3") && (
            <button
              type="button"
              className={`scoring-segment__btn${regimeScoring ? " is-active" : ""}`}
              title={t("scoring.regime.hint")}
              onClick={() => void toggleRegimeScoring()}
              style={{ marginLeft: 4 }}
            >
              {regimeScoring ? t("scoring.regime.on") : t("scoring.regime.off")}
            </button>
          )}
          {!androidLongV3 && (
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
          )}
          </>}

          <select
            className="filter-select universe-select"
            value={universeProfile}
            title={profileLocked ? t("universe.lockedHint") : t("universe.hint")}
            onChange={(e) => void handleUniverseChange(e.target.value)}
            aria-label={t("universe.label")}
            disabled={profileLocked || universeSwitching}
          >
            {(universeProfiles.length > 0
              ? universeProfiles
              : [{ name: universeProfile, description: "", symbol_count: symbolsTotal }]
            ).map((p) => {
              const count = p.name === universeProfile && activeTrackedFeed?.feed
                ? activeTrackedFeed.feed.symbols.length
                : p.symbol_count;
              return <option key={p.name} value={p.name}>{p.name.toUpperCase()} · {count}</option>;
            })}
          </select>

          {/* Asset type segmented filter */}
          {!showingUpside && !androidLongV3 && <div className="asset-filter">
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
          </div>}
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
          <button
            type="button"
            className="btn-ghost"
            data-ui-action="refresh-universe"
            disabled={!canRequestFeedRefresh}
            aria-busy={feedRefreshBusy}
            onClick={() => {
              if (canRequestFeedRefresh && !universeSwitchingRef.current) {
                void requestFeedRefresh(universeProfile);
              }
            }}
          >
            <span aria-hidden="true">↺</span> {t("btn.refresh")}
          </button>
          <button className="btn-ghost" title={t("view.settings")} onClick={() => handleViewModeChange("settings")}>
            ⚙
          </button>
        </div>
      </header>

      <div className="main-layout">
        {viewMode === "dashboard" ? (
          <div className="congress-pane">
            {dashboardEdition === "v2" ? (
              <DashboardV2Panel
                rows={rows}
                symbolsLoaded={symbolsLoaded}
                symbolsTotal={symbolsTotal}
                scoringModel={scoringModel === "aggressive_v2" ? "aggressive_v3" : scoringModel}
                regimeScoring={regimeScoring}
                edition={dashboardEdition}
                onEditionChange={handleDashboardEdition}
                onSelectModel={(m) => void selectScoringModel(m)}
                onToggleRegime={() => void toggleRegimeScoring()}
                onOpenSymbol={openSymbol}
              />
            ) : (
              <>
                <div className="dash-legacy-edition-bar">
                  <DashboardEditionToggle
                    edition={dashboardEdition}
                    onEditionChange={handleDashboardEdition}
                  />
                </div>
                <DashboardPanel
                  rows={rows}
                  symbolsLoaded={symbolsLoaded}
                  symbolsTotal={symbolsTotal}
                  onNavigate={handleViewModeChange}
                  onOpenSymbol={openSymbol}
                  scoringModel={scoringModel}
                />
              </>
            )}
          </div>
        ) : viewMode === "settings" ? (
          <div className="congress-pane">
            <SettingsPanel
              autostartOn={autostartOn}
              onToggleAutostart={toggleAutostart}
            />
          </div>
        ) : viewMode === "screener" || viewMode === "watchlist" ? (
          <>
            <div className={`list-pane ${selectedSymbol ? "narrow" : ""}`}>
              {viewMode === "watchlist" ? (
                <WatchlistPanel
                  status={watchlist.status}
                  symbols={watchlist.symbols}
                  sessionRows={rows}
                  selectedSymbol={selectedSymbol}
                  onSelect={setSelectedSymbol}
                  onRetry={watchlistController.load}
                />
              ) : (
                <>
                  <div className="scoring-segment markets-view-switch" role="group" aria-label={t("markets.views")}>
                    {(["opportunities", "upside"] as const).map((view) => (
                      <button
                        key={view}
                        type="button"
                        className={`scoring-segment__btn${marketsView === view ? " is-active" : ""}`}
                        aria-pressed={marketsView === view}
                        onClick={() => setMarketsView(view)}
                      >
                        {t(`markets.${view}`)}
                      </button>
                    ))}
                  </div>
                  {!showingUpside && !androidLongV3 && !selectedSymbol && <RegimeBanner scoringModel={scoringModel} />}
                  <TrackedFeedPanel
                    key={universeProfile}
                    profile={universeProfile}
                    feed={activeTrackedFeed?.feed ?? null}
                    unavailable={activeTrackedFeed?.feed === null}
                    pending={universeSwitching}
                    writeFailed={trackedFeedWriteFailed}
                    onSetMember={(symbol, included) => void handleTrackedFeedChange(symbol, included)}
                    onRetry={retryTrackedFeed}
                  />
                  {showingUpside ? (
                    <MarketsUpsideList
                      rows={upsideRows}
                      status={activeTrackedFeed === null ? "loading" : activeTrackedFeed.feed ? "ready" : "unavailable"}
                      selectedSymbol={selectedSymbol}
                      onSelect={setSelectedSymbol}
                      onRetry={retryTrackedFeed}
                    />
                  ) : androidLongV3 ? (
                    <AndroidLongV3List
                      key={`android-v3:${universeProfile}`}
                      status={activeAndroidV3?.status ?? null}
                      rows={androidRows}
                      incomplete={androidIncomplete}
                      failed={activeAndroidV3?.failed ?? false}
                      selectedSymbol={selectedSymbol}
                      onSelect={setSelectedSymbol}
                    />
                  ) : (
                    <>
                      {scoringPresentation.banner && (
                        <div className="scoring-mode-banner scoring-mode-banner--short" role="status">
                          <span className="scoring-mode-banner__tag">{t(scoringPresentation.banner.tagKey)}</span>
                          <span className="scoring-mode-banner__text">{t(scoringPresentation.banner.textKey)}</span>
                        </div>
                      )}
                      {(filtered.length > 0 || restoredRows.length === 0) && (
                        <OpportunityList
                          rows={filtered}
                          selectedSymbol={selectedSymbol}
                          onSelect={setSelectedSymbol}
                          symbolsLoaded={symbolsLoaded}
                          symbolsTotal={symbolsTotal}
                          scoringModel={scoringModel}
                          sort={opportunitySort}
                          onSortChange={setOpportunitySort}
                        />
                      )}
                      {restoredRows.length > 0 && (
                        <RestoredUniverseList
                          rows={restoredRows}
                          selectedSymbol={selectedSymbol}
                          onSelect={setSelectedSymbol}
                        />
                      )}
                      {activeSavedUniverse?.unavailable && (
                        <p className="restored-universe__status" role="status">{t("restored.readFailed")}</p>
                      )}
                    </>
                  )}
                </>
              )}
            </div>

            {selectedSymbol && (
              <div className="detail-pane">
                <DetailPanel
                  key={selectedSymbol}
                  symbol={selectedSymbol}
                  sessionRows={rows}
                  watchlist={{
                    status: watchlist.status,
                    included: watchlist.symbols.includes(selectedWatchlistSymbol),
                    writeStatus: watchlist.writes.get(selectedWatchlistSymbol),
                    onToggle: () => void watchlistController.toggle(selectedWatchlistSymbol),
                    onRetry: watchlistController.load,
                  }}
                  scoringModel={scoringModel}
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
              scoringModel={scoringModel}
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

      <StatusBar
        rowCount={showingUpside ? upsideRows.length : showingAndroidV3 ? androidRows.length
          : viewMode === "watchlist" ? watchlist.symbols.length : filtered.length}
        status={feedStatus}
        refreshRequest={feedRefreshRequest}
      />
      </div>

      <CommandPalette
        rows={rows}
        onNavigate={handleViewModeChange}
        onOpenSymbol={openSymbol}
        onOpenSettings={() => handleViewModeChange("settings")}
        onToggleTheme={() => setTheme(theme === "dark" ? "light" : "dark")}
      />
      <UiInspectRoot />
      <Toaster />
    </div>
    </UiInspectProvider>
  );
}
