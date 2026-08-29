package com.discountscreener.android.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.discountscreener.android.domain.model.DashboardNotice
import com.discountscreener.android.domain.model.DashboardNoticeSeverity
import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.MarketReadStatus
import com.discountscreener.core.plan.PlanBoard

import com.discountscreener.android.domain.model.DiscoveryConfig
import com.discountscreener.android.domain.model.DiscoveryJobKind
import com.discountscreener.android.domain.model.DiscoveryJobRecord
import com.discountscreener.android.domain.model.DiscoveryJobStatus
import com.discountscreener.android.domain.model.DiscoverySnapshot
import com.discountscreener.android.domain.model.parseDiscoveryMembershipDelta
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.ScoringPreferences
import com.discountscreener.android.domain.model.SystemStats
import com.discountscreener.android.domain.model.TickerSearchSuggestion
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.domain.usecase.AddDashboardSymbolsUseCase
import com.discountscreener.android.domain.usecase.BootstrapDashboardUseCase
import com.discountscreener.android.domain.usecase.CancelDiscoveryJobUseCase
import com.discountscreener.android.domain.usecase.ClearAllDataUseCase
import com.discountscreener.android.domain.usecase.EarningsLogBackupUseCase
import com.discountscreener.android.domain.usecase.ExportScoresUseCase
import com.discountscreener.android.domain.usecase.RestoreEarningsLogUseCase
import com.discountscreener.android.domain.usecase.RunOutcomeReportUseCase
import com.discountscreener.android.domain.usecase.RunRetrospectiveUseCase
import com.discountscreener.android.domain.usecase.ClearDiscoveryDataUseCase
import com.discountscreener.android.domain.usecase.DashboardUseCases
import com.discountscreener.android.domain.usecase.GetDashboardSnapshotUseCase
import com.discountscreener.android.domain.usecase.GetEarningsEventsUseCase
import com.discountscreener.android.domain.usecase.GetIndexEstimatesUseCase
import com.discountscreener.android.domain.usecase.GetEstimatesHistoryUseCase
import com.discountscreener.android.domain.usecase.LoadDiscoverySnapshotUseCase
import com.discountscreener.android.domain.usecase.SaveDiscoveryConfigUseCase
import com.discountscreener.android.domain.usecase.SaveEstimatesSnapshotUseCase
import com.discountscreener.android.domain.usecase.SaveSymbolNoteUseCase
import com.discountscreener.android.domain.usecase.SearchTickersUseCase
import com.discountscreener.android.domain.usecase.LoadScoringPreferencesUseCase
import com.discountscreener.android.domain.usecase.LoadSymbolNotesUseCase
import com.discountscreener.android.domain.usecase.LoadSystemStatsUseCase
import com.discountscreener.android.domain.usecase.ObserveDashboardUpdatesUseCase
import com.discountscreener.android.domain.usecase.EnsureReplayBackingLoadedUseCase
import com.discountscreener.android.domain.usecase.ObserveDiscoveryProgressUseCase
import com.discountscreener.android.domain.usecase.PersistScoringPreferencesUseCase
import com.discountscreener.android.domain.usecase.PruneOldRevisionsUseCase
import com.discountscreener.android.domain.usecase.RecreateDiscoveryUniverseUseCase
import com.discountscreener.android.domain.usecase.RefreshDashboardUseCase
import com.discountscreener.android.domain.usecase.RefreshDiscoveryScoresUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardProfileUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardSymbolUseCase
import com.discountscreener.android.domain.usecase.ToggleDashboardWatchlistUseCase
import com.discountscreener.core.engine.DiscoveryScoreRow
import com.discountscreener.core.engine.TickerSearchEngine
import com.discountscreener.core.engine.TickerSearchRank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.Locale
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.model.AlertEvent
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.IssueRecord
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ProjectedDetailData
import com.discountscreener.core.model.ProjectedProviderState
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRevision
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

enum class DashboardTab {
    Opportunities,
    Market,
    Plans,
    Tracked,
    Watch,
    Discovery,
    System,
    Estimates,
    Earnings,
}

enum class PlanHunt {
    Dip,
    Cross,
    Leftover,
}

enum class PlanDipUniverse {
    Opportunities,
    Profile,
}

enum class DetailSubtab {
    Snapshot,
    Score,
    Lens,
    History,
}

enum class HistorySubview {
    Graphs,
    Table,
}

enum class HistoryMetricGroup {
    Core,
    Fundamentals,
    Relative,
    Dcf,
    Chart,
}

enum class DetailSourceTab {
    Tracked,
    Opportunities,
}

data class DetailRoute(
    val symbol: String,
    val sourceTab: DetailSourceTab,
    val sourceSymbols: List<String>,
    val subtab: DetailSubtab = DetailSubtab.Snapshot,
    val chartRange: ChartRange = ChartRange.Year,
    val historySubview: HistorySubview = HistorySubview.Graphs,
    val historyMetricGroup: HistoryMetricGroup = HistoryMetricGroup.Core,
    val historyTimeWindow: ChartRange = ChartRange.Year,
    val replayOffset: Int = 0,
)

sealed interface DashboardAction {
    data object Start : DashboardAction
    data object Refresh : DashboardAction
    data class SelectTab(val tab: DashboardTab) : DashboardAction
    data class SelectPlanHunt(val hunt: PlanHunt) : DashboardAction
    data class SelectPlanDipUniverse(val universe: PlanDipUniverse) : DashboardAction
    data class UpdateQuery(val query: String) : DashboardAction
    data class UpdateTickerSearchQuery(val query: String) : DashboardAction
    data class SelectTickerSuggestion(val symbol: String) : DashboardAction
    data object SubmitTickerSearch : DashboardAction
    data class SetTickerSearchExpanded(val expanded: Boolean) : DashboardAction
    data object ClearTickerSearch : DashboardAction
    data class OpenDetail(val symbol: String) : DashboardAction
    data object BackFromDetail : DashboardAction
    data object PrevTicker : DashboardAction
    data object NextTicker : DashboardAction
    data class SetDetailSubtab(val subtab: DetailSubtab) : DashboardAction
    data class SetChartRange(val range: ChartRange) : DashboardAction
    data class SetHistorySubview(val subview: HistorySubview) : DashboardAction
    data class SetHistoryMetricGroup(val group: HistoryMetricGroup) : DashboardAction
    data class SetHistoryTimeWindow(val window: ChartRange) : DashboardAction
    data class SetReplayOffset(val offset: Int) : DashboardAction
    data object StepReplayBack : DashboardAction
    data object StepReplayForward : DashboardAction
    data object ResetReplay : DashboardAction
    data class ToggleWatchlist(val symbol: String) : DashboardAction

    /** Writes the reader's own note for one symbol. A blank note clears it. */
    data class SaveSymbolNote(val symbol: String, val note: String) : DashboardAction
    data class AddSymbols(val rawInput: String) : DashboardAction
    data class SelectProfile(val profile: String) : DashboardAction
    data object ToggleOpportunityScoringModel : DashboardAction
    data class SetOpportunityScoringModel(val model: OpportunityScoringModel) : DashboardAction
    data class SetRegimeScoringEnabled(val enabled: Boolean) : DashboardAction
    data object RefreshSystemStats : DashboardAction

    /** Debug surface only — writes the score export and reports where it landed. */
    data object ExportScores : DashboardAction

    /**
     * Hand the earnings log out to a file the phone does not own.
     *
     * A release build is not debuggable and an uninstall takes the log with it, so a lost
     * signing key would cost every option chain ever captured. Nothing else here is
     * irreplaceable; those chains are never republished.
     */
    data object BackUpEarningsLog : DashboardAction

    data class EarningsLogBackupWritten(val eventCount: Int) : DashboardAction
    data object EarningsLogBackupDropped : DashboardAction
    data class RestoreEarningsLog(val text: String) : DashboardAction

    data object RunRetrospective : DashboardAction
    data object RunOutcomeReport : DashboardAction
    data class PruneOldRevisions(val retentionDays: Int) : DashboardAction
    data object ClearAllData : DashboardAction
    data object LoadDiscovery : DashboardAction
    data object RecreateDiscoveryUniverse : DashboardAction
    data object RefreshDiscoveryScores : DashboardAction
    data object CancelDiscoveryJob : DashboardAction
    data object ClearDiscoveryData : DashboardAction
    data class SetDiscoveryMinScore(val minScore: Int) : DashboardAction
    data class SetDiscoveryScoringModel(val model: OpportunityScoringModel) : DashboardAction
}

data class DashboardUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val currentTab: DashboardTab = DashboardTab.Opportunities,
    val availableProfiles: List<String> = emptyList(),
    val currentProfile: String = "qa",
    val query: String = "",
    val tickerSearchQuery: String = "",
    val tickerSearchSuggestions: List<TickerSearchSuggestion> = emptyList(),
    val tickerSearchExpanded: Boolean = false,
    val tickerSearchLoading: Boolean = false,
    val tickerSearchNotice: DashboardNotice? = null,
    val trackedSymbols: List<String> = emptyList(),
    val trackedRows: List<TrackedSymbolRow> = emptyList(),
    val watchlistSymbols: List<String> = emptyList(),
    /** The reader's own notes, by symbol. A symbol with no note is absent. */
    val symbolNotes: Map<String, String> = emptyMap(),
    val candidateRows: List<CandidateRow> = emptyList(),
    val opportunityRows: List<OpportunityListRow> = emptyList(),
    val opportunityScoringModel: OpportunityScoringModel = ScoringPreferences.DEFAULT_OPPORTUNITY_MODEL,
    /** The market dimension's runtime switch. Only V3 rows are affected by it. */
    val regimeScoringEnabled: Boolean = ScoringPreferences.DEFAULT_REGIME_ENABLED,
    val issues: List<IssueRecord> = emptyList(),
    val detailRoute: DetailRoute? = null,
    val detailData: SymbolDetail? = null,
    val projectedDetailData: ProjectedDetailData? = null,
    val detailCharts: Map<ChartRange, List<HistoricalCandle>> = emptyMap(),
    val detailHistory: List<SymbolRevision> = emptyList(),
    val detailAlerts: List<AlertEvent> = emptyList(),
    val detailQuantLens: QuantLensUiState? = null,
    val detailNotice: DashboardNotice? = null,
    val rowQuantLensChipsBySymbol: Map<String, List<QuantLensChipUi>> = emptyMap(),
    val lastUpdatedAtEpochSeconds: Long? = null,
    val startupPhase: DashboardStartupPhase = DashboardStartupPhase.Restoring,
    val refreshCompletedSymbols: Int = 0,
    val refreshTargetSymbols: Int = 0,
    val statusMessage: String? = null,
    val systemStats: SystemStats? = null,
    val systemStatsLoading: Boolean = false,
    val systemStatusMessage: String? = null,
    val providerState: ProjectedProviderState = ProjectedProviderState(),
    val indexEstimates: IndexEstimatesReport? = null,
    val indexEstimatesLoading: Boolean = false,
    val estimatesHistory: List<IndexEstimatesReport> = emptyList(),
    val estimatesNotice: DashboardNotice? = null,
    val discoveryConfig: DiscoveryConfig = DiscoveryConfig(),
    val discoveryMembershipCount: Int = 0,
    val discoveryJob: DiscoveryJobRecord? = null,
    val discoveryScores: List<DiscoveryScoreRow> = emptyList(),
    val discoveryResultCount: Int = 0,
    val discoveryScoredSymbolCount: Int = 0,
    val discoveryLastScoredAtEpochSeconds: Long? = null,
    val discoveryLastSourceHint: String? = null,
    val discoveryBusy: Boolean = false,
    val discoveryStatusMessage: String? = null,
    val replayBackingCharts: Map<ChartRange, List<HistoricalCandle>> = emptyMap(),
    /**
     * Score for the open ticker when it is not in [opportunityRows]. The ranked list is a
     * cache of qualified names; an ad-hoc search ticker is fetched and scored separately.
     */
    val selectedScoreRow: OpportunityListRow? = null,
    val marketRegime: MarketRegimeUi = presentMarketRegime(null, MarketReadStatus.Pending),
    val planHunt: PlanHunt = PlanHunt.Dip,
    val planDipUniverse: PlanDipUniverse = PlanDipUniverse.Opportunities,
    val planBoardOpps: PlanBoardUi = presentPlanBoard(null),
    val planBoardProfile: PlanBoardUi = presentPlanBoard(null),
    val leftoverBoard: PlanBoardUi = presentLeftoverBoard(null),
    val crossBoardOpps: PlanBoardUi = presentCrossBoard(null),
    val crossBoardProfile: PlanBoardUi = presentCrossBoard(null),
    val earningsGate: EarningsGateUi = EarningsGateUi(),
    val earningsGateLoading: Boolean = false,
    val earningsLogBackup: String? = null,
    val earningsGateNotice: String? = null,
) {
    val planBoard: PlanBoardUi
        get() = if (planDipUniverse == PlanDipUniverse.Opportunities) planBoardOpps else planBoardProfile
    val crossBoard: PlanBoardUi
        get() = if (planDipUniverse == PlanDipUniverse.Opportunities) crossBoardOpps else crossBoardProfile
    /**
     * The open ticker's score: the ranked-list row when present, otherwise the fetched
     * selected row. Never show a score that belongs to a different symbol.
     */
    val detailScoreRow: OpportunityListRow?
        get() = detailRoute?.let { route ->
            selectedScoreRow?.takeIf { row -> row.symbol == route.symbol }
                ?: opportunityRows.firstOrNull { row -> row.symbol == route.symbol }
        }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class DashboardViewModel(
    private val observeDashboardUpdates: ObserveDashboardUpdatesUseCase,
    private val bootstrapDashboard: BootstrapDashboardUseCase,
    private val refreshDashboard: RefreshDashboardUseCase,
    private val getDashboardSnapshot: GetDashboardSnapshotUseCase,
    private val selectDashboardSymbol: SelectDashboardSymbolUseCase,
    private val addDashboardSymbols: AddDashboardSymbolsUseCase,
    private val selectDashboardProfile: SelectDashboardProfileUseCase,
    private val toggleDashboardWatchlist: ToggleDashboardWatchlistUseCase,
    private val loadScoringPreferences: LoadScoringPreferencesUseCase,
    private val persistScoringPreferences: PersistScoringPreferencesUseCase,
    private val loadSymbolNotes: LoadSymbolNotesUseCase,
    private val saveSymbolNote: SaveSymbolNoteUseCase,
    private val loadSystemStats: LoadSystemStatsUseCase,
    private val pruneOldRevisions: PruneOldRevisionsUseCase,
    private val clearAllDataUseCase: ClearAllDataUseCase,
    private val exportScores: ExportScoresUseCase,
    private val runRetrospective: RunRetrospectiveUseCase,
    private val runOutcomeReport: RunOutcomeReportUseCase,
    private val getEarningsEvents: GetEarningsEventsUseCase,
    private val backUpEarningsLog: EarningsLogBackupUseCase,
    private val restoreEarningsLog: RestoreEarningsLogUseCase,
    private val getIndexEstimates: GetIndexEstimatesUseCase,
    private val saveEstimatesSnapshot: SaveEstimatesSnapshotUseCase,
    private val getEstimatesHistory: GetEstimatesHistoryUseCase,
    private val searchTickers: SearchTickersUseCase,
    private val loadDiscoverySnapshot: LoadDiscoverySnapshotUseCase,
    private val saveDiscoveryConfig: SaveDiscoveryConfigUseCase,
    private val recreateDiscoveryUniverse: RecreateDiscoveryUniverseUseCase,
    private val refreshDiscoveryScores: RefreshDiscoveryScoresUseCase,
    private val cancelDiscoveryJob: CancelDiscoveryJobUseCase,
    private val clearDiscoveryData: ClearDiscoveryDataUseCase,
    private val observeDiscoveryProgress: ObserveDiscoveryProgressUseCase,
    private val ensureReplayBackingLoaded: EnsureReplayBackingLoadedUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var started = false
    private var activeEstimatesJob: kotlinx.coroutines.Job? = null
    private var activeEarningsJob: kotlinx.coroutines.Job? = null
    private var tickerSearchJob: Job? = null
    private var discoveryProgressJob: Job? = null
    private var selectProfileJob: Job? = null
    private var detailLoadJob: Job? = null
    private var refreshJob: Job? = null
    private val detailSessions = linkedMapOf<String, CachedDetailSession>()

    fun dispatch(action: DashboardAction) {
        when (action) {
            DashboardAction.Start -> start()
            DashboardAction.Refresh -> refresh(force = true)
            is DashboardAction.SelectTab -> selectTab(action.tab)
            is DashboardAction.SelectPlanHunt -> _state.value = _state.value.copy(planHunt = action.hunt)
            is DashboardAction.SelectPlanDipUniverse -> selectPlanDipUniverse(action.universe)
            is DashboardAction.UpdateQuery -> updateQuery(action.query)
            is DashboardAction.UpdateTickerSearchQuery -> updateTickerSearchQuery(action.query)
            is DashboardAction.SelectTickerSuggestion -> selectTickerSuggestion(action.symbol)
            DashboardAction.SubmitTickerSearch -> submitTickerSearch()
            is DashboardAction.SetTickerSearchExpanded -> setTickerSearchExpanded(action.expanded)
            DashboardAction.ClearTickerSearch -> clearTickerSearch()
            is DashboardAction.OpenDetail -> openDetail(action.symbol)
            DashboardAction.BackFromDetail -> backFromDetail()
            DashboardAction.PrevTicker -> navigateTicker(-1)
            DashboardAction.NextTicker -> navigateTicker(1)
            is DashboardAction.SetDetailSubtab -> setDetailSubtab(action.subtab)
            is DashboardAction.SetChartRange -> setChartRange(action.range)
            is DashboardAction.SetHistorySubview -> _state.value = _state.value.copy(
                detailRoute = _state.value.detailRoute?.copy(historySubview = action.subview),
            )
            is DashboardAction.SetHistoryMetricGroup -> _state.value = _state.value.copy(
                detailRoute = _state.value.detailRoute?.copy(historyMetricGroup = action.group),
            )
            is DashboardAction.SetHistoryTimeWindow -> _state.value = _state.value.copy(
                detailRoute = _state.value.detailRoute?.copy(historyTimeWindow = action.window),
            )
            is DashboardAction.SetReplayOffset -> _state.value = _state.value.copy(
                detailRoute = _state.value.detailRoute?.copy(replayOffset = action.offset),
            )
            DashboardAction.StepReplayBack -> stepReplayBack()
            DashboardAction.StepReplayForward -> stepReplayForward()
            DashboardAction.ResetReplay -> resetReplay()
            is DashboardAction.ToggleWatchlist -> toggleWatchlist(action.symbol)
            is DashboardAction.SaveSymbolNote -> saveNote(action.symbol, action.note)
            is DashboardAction.AddSymbols -> addSymbols(action.rawInput)
            is DashboardAction.SelectProfile -> selectProfile(action.profile)
            DashboardAction.ToggleOpportunityScoringModel -> toggleOpportunityScoringModel()
            is DashboardAction.SetOpportunityScoringModel -> setOpportunityScoringModel(action.model)
            is DashboardAction.SetRegimeScoringEnabled -> setRegimeScoringEnabled(action.enabled)
            DashboardAction.RefreshSystemStats -> refreshSystemStats()
            DashboardAction.ExportScores -> exportScoreCsv()
            DashboardAction.BackUpEarningsLog -> prepareEarningsLogBackup()
            is DashboardAction.EarningsLogBackupWritten -> finishEarningsLogBackup(action.eventCount)
            DashboardAction.EarningsLogBackupDropped -> dropEarningsLogBackup()
            is DashboardAction.RestoreEarningsLog -> restoreEarningsLogFrom(action.text)
            DashboardAction.RunRetrospective -> runRetrospectiveReport()
            DashboardAction.RunOutcomeReport -> runOutcomeReportAction()
            is DashboardAction.PruneOldRevisions -> pruneOldRevisions(action.retentionDays)
            DashboardAction.ClearAllData -> performClearAllData()
            DashboardAction.LoadDiscovery -> loadDiscovery()
            DashboardAction.RecreateDiscoveryUniverse -> runRecreateDiscoveryUniverse()
            DashboardAction.RefreshDiscoveryScores -> runRefreshDiscoveryScores()
            DashboardAction.CancelDiscoveryJob -> runCancelDiscoveryJob()
            DashboardAction.ClearDiscoveryData -> runClearDiscoveryData()
            is DashboardAction.SetDiscoveryMinScore -> setDiscoveryMinScore(action.minScore)
            is DashboardAction.SetDiscoveryScoringModel -> setDiscoveryScoringModel(action.model)
        }
    }

    private fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            // Restored first: rendering the list under a model the user did not choose and then
            // re-ranking it a moment later is worse than waiting one database read.
            val preferences = loadScoringPreferences()
            _state.value = _state.value.copy(
                opportunityScoringModel = preferences.opportunityModel,
                regimeScoringEnabled = preferences.regimeScoringEnabled,
            )
            val initial = bootstrapDashboard(
                currentFilter(),
                _state.value.detailRoute?.symbol,
                _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                _state.value.opportunityScoringModel,
            )
            render(initial)
            // Collect after restore. A collector that starts on the default chip takes the
            // snapshot mutex in front of bootstrap.
            viewModelScope.launch {
                observeDashboardUpdates().collect {
                    val buildMillis = measureTimeMillis {
                        render(
                            getDashboardSnapshot(
                                currentFilter(),
                                _state.value.detailRoute?.symbol,
                                _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                                _state.value.opportunityScoringModel,
                            ),
                        )
                    }
                    // A rebuild reads every row of the profile under the repository's state
                    // mutex, so on the 1 937-symbol universe it costs about a second of a
                    // two-core device. The refresh signalled one every eight rows, and the
                    // device spent 57.8 s of a 44 s round rebuilding, in front of the HTTP
                    // that round was waiting on. So a rebuild now waits for what it cost
                    // before the next one starts: at most a third of the time goes to the
                    // screen, the rest to the refresh. The flow is a conflated StateFlow, so
                    // the wait loses no update; the next build reads the newest state.
                    delay(
                        (buildMillis * SNAPSHOT_REBUILD_COOLDOWN_FACTOR)
                            .coerceIn(MIN_SNAPSHOT_REBUILD_COOLDOWN_MS, MAX_SNAPSHOT_REBUILD_COOLDOWN_MS),
                    )
                }
            }
            viewModelScope.launch {
                observeDashboardUpdates()
                    .debounce(2_000L)
                    .collectLatest { loadEstimates() }
            }
            refresh(force = false)
            // After the list, never before it. A note changes no row, so the read of one small
            // table has no business sitting in front of the first screen the user sees.
            _state.value = _state.value.copy(symbolNotes = loadSymbolNotes())
            // Load discovery state from DB only — never auto recreate/refresh.
            applyDiscoverySnapshot(loadDiscoverySnapshot())
            loadEstimates()
        }
        discoveryProgressJob?.cancel()
        discoveryProgressJob = viewModelScope.launch {
            observeDiscoveryProgress().collectLatest {
                applyDiscoverySnapshot(loadDiscoverySnapshot())
            }
        }
    }

    private fun refresh(force: Boolean) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // A throw here reaches the coroutine handler and takes the process with it, which is a
            // hard exit for a button whose worst honest outcome is "the data did not update".
            // `loadDetailData` already guards its own call this way.
            try {
                val snapshot = refreshDashboard(
                    currentFilter(),
                    _state.value.detailRoute?.symbol,
                    _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                    _state.value.opportunityScoringModel,
                    force,
                )
                render(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    detailNotice = DashboardNotice(
                        title = "Refresh failed",
                        message = error.message ?: "The refresh could not complete.",
                        severity = DashboardNoticeSeverity.Warning,
                    ),
                )
            }
            _state.value.detailRoute?.symbol?.let { loadDetailData(it) }
            loadEstimates()
        }
    }

    private fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        viewModelScope.launch {
            render(
                getDashboardSnapshot(
                    currentFilter(),
                    _state.value.detailRoute?.symbol,
                    _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                    _state.value.opportunityScoringModel,
                ),
            )
        }
    }

    private fun selectPlanDipUniverse(universe: PlanDipUniverse) {
        _state.value = _state.value.copy(planDipUniverse = universe)
    }

    private fun selectTab(tab: DashboardTab) {
        _state.value = _state.value.copy(currentTab = tab)
        if (tab == DashboardTab.System && _state.value.systemStats == null) {
            refreshSystemStats()
        }
        if (tab == DashboardTab.Estimates) {
            loadEstimates()
        }
        if (tab == DashboardTab.Discovery) {
            loadDiscovery()
        }
        if (tab == DashboardTab.Earnings) {
            loadEarningsGate()
        }
    }

    private fun loadEarningsGate() {
        activeEarningsJob?.cancel()
        _state.value = _state.value.copy(earningsGateLoading = true)
        activeEarningsJob = viewModelScope.launch {
            try {
                _state.value = _state.value.copy(earningsGate = getEarningsEvents())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = _state.value.copy(earningsGate = EarningsGateUi())
            } finally {
                _state.value = _state.value.copy(earningsGateLoading = false)
            }
        }
    }

    private fun prepareEarningsLogBackup() {
        viewModelScope.launch {
            var backup = try {
                backUpEarningsLog()
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    earningsGateNotice = "Backup failed: ${error.message ?: "unknown error"}",
                )
                return@launch
            }
            _state.value = if (backup.eventCount == 0) {
                _state.value.copy(earningsGateNotice = "No reports logged yet, so nothing to back up.")
            } else {
                _state.value.copy(earningsLogBackup = backup.text, earningsGateNotice = null)
            }
        }
    }

    private fun finishEarningsLogBackup(eventCount: Int) {
        _state.value = _state.value.copy(
            earningsLogBackup = null,
            earningsGateNotice = "Backed up $eventCount report(s).",
        )
    }

    private fun dropEarningsLogBackup() {
        _state.value = _state.value.copy(earningsLogBackup = null)
    }

    /**
     * A restore reports the reports it added, including none.
     *
     * Silence would read the same whether the file was the wrong one or the phone already
     * held everything in it, and those call for opposite next moves.
     */
    private fun restoreEarningsLogFrom(text: String) {
        viewModelScope.launch {
            var message = try {
                "Restored ${restoreEarningsLog(text)} report(s) from the backup."
            } catch (error: Throwable) {
                "Restore failed: ${error.message ?: "unknown error"}"
            }
            _state.value = _state.value.copy(earningsGateNotice = message)
            loadEarningsGate()
        }
    }

    private fun loadDiscovery() {
        viewModelScope.launch {
            applyDiscoverySnapshot(loadDiscoverySnapshot())
        }
    }

    private fun runRecreateDiscoveryUniverse() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                discoveryBusy = true,
                discoveryStatusMessage = "Updating list from NASDAQ Trader (or bundled seed)…",
            )
            val snapshot = recreateDiscoveryUniverse()
            applyDiscoverySnapshot(snapshot)
            val delta = parseDiscoveryMembershipDelta(snapshot.job?.errorSummary)
            val source = snapshot.lastSourceHint ?: "source unknown"
            val deltaText = delta?.let { (added, removed) -> " · +$added −$removed" }.orEmpty()
            _state.value = _state.value.copy(
                discoveryBusy = false,
                discoveryStatusMessage = "List updated · $source$deltaText (no prices downloaded).",
            )
        }
    }

    private fun runRefreshDiscoveryScores() {
        viewModelScope.launch {
            if (_state.value.discoveryMembershipCount == 0) {
                _state.value = _state.value.copy(
                    discoveryStatusMessage = "Create the US list first, then score it.",
                )
                return@launch
            }
            val membership = _state.value.discoveryMembershipCount
            // Optimistic busy + 0/N so the tab badge/progress don't lag until first progress tick.
            _state.value = _state.value.copy(
                discoveryBusy = true,
                discoveryStatusMessage = "Scoring list (minimal quote + 1Y chart)… Keep the app open.",
                discoveryJob = DiscoveryJobRecord(
                    jobId = _state.value.discoveryJob?.jobId ?: -1L,
                    kind = DiscoveryJobKind.Refresh,
                    status = DiscoveryJobStatus.Running,
                    startedAtEpochSeconds = System.currentTimeMillis() / 1_000,
                    finishedAtEpochSeconds = null,
                    totalSymbols = membership,
                    completedSymbols = 0,
                    errorSummary = null,
                ),
            )
            applyDiscoverySnapshot(refreshDiscoveryScores())
            val aboveMin = _state.value.discoveryResultCount
            val job = _state.value.discoveryJob
            val completed = job?.completedSymbols ?: 0
            _state.value = _state.value.copy(
                discoveryBusy = false,
                discoveryStatusMessage = "Scoring finished · $completed scanned · $aboveMin above min.",
            )
        }
    }

    private fun runCancelDiscoveryJob() {
        viewModelScope.launch {
            applyDiscoverySnapshot(cancelDiscoveryJob())
            _state.value = _state.value.copy(
                discoveryBusy = false,
                discoveryStatusMessage = "Discovery job cancelled. Partial scores are kept.",
            )
        }
    }

    private fun runClearDiscoveryData() {
        viewModelScope.launch {
            applyDiscoverySnapshot(clearDiscoveryData())
            _state.value = _state.value.copy(
                discoveryBusy = false,
                discoveryStatusMessage = "Discovery list and scores cleared.",
            )
        }
    }

    private fun setDiscoveryMinScore(minScore: Int) {
        viewModelScope.launch {
            val clamped = minScore.coerceIn(0, 100)
            val config = _state.value.discoveryConfig.copy(minScore = clamped)
            applyDiscoverySnapshot(saveDiscoveryConfig(config))
        }
    }

    private fun setDiscoveryScoringModel(model: OpportunityScoringModel) {
        viewModelScope.launch {
            val config = _state.value.discoveryConfig.copy(scoringModel = model)
            applyDiscoverySnapshot(saveDiscoveryConfig(config))
            _state.value = _state.value.copy(
                discoveryStatusMessage = "Model saved. Score list recommended to recompute.",
            )
        }
    }

    private fun applyDiscoverySnapshot(snapshot: DiscoverySnapshot) {
        val running = snapshot.job?.status == DiscoveryJobStatus.Running
        _state.value = _state.value.copy(
            discoveryConfig = snapshot.config,
            discoveryMembershipCount = snapshot.membershipCount,
            discoveryJob = snapshot.job,
            discoveryScores = snapshot.scores,
            discoveryResultCount = snapshot.resultCount,
            discoveryScoredSymbolCount = snapshot.scoredSymbolCount,
            discoveryLastScoredAtEpochSeconds = snapshot.lastScoredAtEpochSeconds,
            discoveryLastSourceHint = snapshot.lastSourceHint,
            discoveryBusy = running,
        )
    }

    private fun updateTickerSearchQuery(query: String) {
        _state.value = _state.value.copy(
            tickerSearchQuery = query,
            tickerSearchExpanded = query.isNotBlank(),
            tickerSearchNotice = null,
            tickerSearchLoading = query.isNotBlank(),
        )
        tickerSearchJob?.cancel()
        tickerSearchJob = viewModelScope.launch {
            if (query.isBlank()) {
                _state.value = _state.value.copy(
                    tickerSearchSuggestions = emptyList(),
                    tickerSearchLoading = false,
                )
                return@launch
            }
            delay(TICKER_SEARCH_DEBOUNCE_MS)
            val suggestions = searchTickers(query, _state.value.currentProfile)
            if (_state.value.tickerSearchQuery != query) return@launch
            _state.value = _state.value.copy(
                tickerSearchSuggestions = suggestions,
                tickerSearchExpanded = query.isNotBlank(),
                tickerSearchLoading = false,
            )
        }
    }

    private fun selectTickerSuggestion(symbol: String) {
        _state.value = _state.value.copy(
            tickerSearchQuery = symbol,
            tickerSearchExpanded = false,
            tickerSearchSuggestions = emptyList(),
            tickerSearchNotice = null,
        )
        openDetail(symbol)
    }

    private fun submitTickerSearch() {
        val query = _state.value.tickerSearchQuery.trim()
        if (query.isBlank()) return

        viewModelScope.launch {
            val suggestions = suggestionsForSubmit(query)
            if (TickerSearchEngine.shouldDirectOpenTickerOnSubmit(
                    query,
                    suggestions.map(TickerSearchSuggestion::symbol),
                )
            ) {
                selectTickerSuggestion(query.uppercase(Locale.US))
                return@launch
            }

            val highConfidence = suggestions.filter { suggestion ->
                isHighConfidenceTickerMatch(query, suggestion)
            }
            when {
                highConfidence.size == 1 -> selectTickerSuggestion(highConfidence.single().symbol)
                suggestions.isEmpty() -> _state.value = _state.value.copy(
                    tickerSearchExpanded = false,
                    tickerSearchNotice = DashboardNotice(
                        title = "Ticker unavailable",
                        message = "No matches found for \"$query\".",
                        severity = DashboardNoticeSeverity.Warning,
                    ),
                )
                else -> _state.value = _state.value.copy(
                    tickerSearchSuggestions = suggestions,
                    tickerSearchExpanded = true,
                    tickerSearchNotice = DashboardNotice(
                        title = "Pick a match",
                        message = "Several companies match \"$query\". Select one from the list.",
                        severity = DashboardNoticeSeverity.Info,
                    ),
                )
            }
        }
    }

    private suspend fun suggestionsForSubmit(query: String): List<TickerSearchSuggestion> {
        if (_state.value.tickerSearchQuery.trim() == query &&
            _state.value.tickerSearchSuggestions.isNotEmpty()
        ) {
            return _state.value.tickerSearchSuggestions
        }
        return searchTickers(query, _state.value.currentProfile)
    }

    private fun isHighConfidenceTickerMatch(
        query: String,
        suggestion: TickerSearchSuggestion,
    ): Boolean {
        val trimmed = query.trim()
        if (suggestion.symbol.equals(trimmed, ignoreCase = true)) return true
        val companyName = suggestion.companyName ?: return false
        return TickerSearchEngine.companyNameMatchRank(trimmed, companyName) == TickerSearchRank.NAME_EXACT
    }

    private fun setTickerSearchExpanded(expanded: Boolean) {
        _state.value = _state.value.copy(tickerSearchExpanded = expanded)
    }

    private fun clearTickerSearch() {
        _state.value = _state.value.copy(
            tickerSearchQuery = "",
            tickerSearchExpanded = false,
            tickerSearchSuggestions = emptyList(),
            tickerSearchNotice = null,
        )
    }

    private fun openDetail(symbol: String) {
        val state = _state.value
        val sourceTab = when (state.currentTab) {
            DashboardTab.Opportunities,
            DashboardTab.Plans,
            -> DetailSourceTab.Opportunities
            else -> DetailSourceTab.Tracked
        }
        val sourceSymbols = sourceSymbolsForTab(state, sourceTab).takeIf { symbol in it } ?: listOf(symbol)
        val detailRoute = DetailRoute(
            symbol = symbol,
            sourceTab = sourceTab,
            sourceSymbols = sourceSymbols,
        )
        _state.value = applyCachedDetailSession(
            clearMismatchedDetail(
                state.copy(
                    detailRoute = detailRoute,
                    detailNotice = null,
                    tickerSearchQuery = "",
                    tickerSearchExpanded = false,
                    tickerSearchSuggestions = emptyList(),
                    tickerSearchNotice = null,
                ),
                symbol,
            ),
            symbol,
        )
        if (_state.value.earningsGate.isEmpty && !_state.value.earningsGateLoading) {
            loadEarningsGate()
        }
        detailLoadJob?.cancel()
        detailLoadJob = viewModelScope.launch {
            try {
                renderDetailOnFile(symbol)
                val snapshot = selectDashboardSymbol(
                    symbol,
                    currentFilter(),
                    _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                    _state.value.opportunityScoringModel,
                )
                render(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    detailRoute = null,
                    tickerSearchExpanded = false,
                    tickerSearchSuggestions = emptyList(),
                    tickerSearchNotice = DashboardNotice(
                        title = "Ticker unavailable",
                        message = error.message ?: "The ticker could not be opened.",
                        severity = DashboardNoticeSeverity.Warning,
                    ),
                )
            }
        }
    }

    private fun backFromDetail() {
        rememberDetailSession(_state.value)
        _state.value = _state.value.copy(
            detailRoute = null,
            selectedScoreRow = null,
            detailData = null,
            projectedDetailData = null,
            detailCharts = emptyMap(),
            replayBackingCharts = emptyMap(),
            detailHistory = emptyList(),
            detailAlerts = emptyList(),
            detailQuantLens = null,
            detailNotice = null,
            tickerSearchExpanded = false,
            tickerSearchSuggestions = emptyList(),
        )
    }

    private fun navigateTicker(direction: Int) {
        val route = _state.value.detailRoute ?: return
        val symbols = route.sourceSymbols
        val currentIndex = symbols.indexOf(route.symbol)
        if (currentIndex < 0) return
        val newIndex = (currentIndex + direction).coerceIn(0, symbols.lastIndex)
        val newSymbol = symbols[newIndex]
        _state.value = applyCachedDetailSession(
            clearMismatchedDetail(
                _state.value.copy(
                    detailRoute = route.copy(symbol = newSymbol, replayOffset = 0),
                    detailNotice = null,
                    tickerSearchQuery = newSymbol,
                ),
                newSymbol,
            ),
            newSymbol,
        )
        loadDetailData(newSymbol)
    }

    private fun clearMismatchedDetail(state: DashboardUiState, symbol: String): DashboardUiState {
        var keep = state.detailData?.symbol == symbol
        return state.copy(
            detailData = state.detailData?.takeIf { it.symbol == symbol },
            projectedDetailData = state.projectedDetailData?.takeIf { it.symbol == symbol },
            selectedScoreRow = state.opportunityRows.firstOrNull { it.symbol == symbol }
                ?: state.selectedScoreRow?.takeIf { it.symbol == symbol },
            detailCharts = if (keep) state.detailCharts else emptyMap(),
            replayBackingCharts = if (keep) state.replayBackingCharts else emptyMap(),
            detailHistory = if (keep) state.detailHistory else emptyList(),
            detailAlerts = if (keep) state.detailAlerts else emptyList(),
            detailQuantLens = if (keep) state.detailQuantLens else null,
        )
    }

    private fun setDetailSubtab(subtab: DetailSubtab) {
        _state.value = _state.value.copy(
            detailRoute = _state.value.detailRoute?.copy(subtab = subtab),
        )
    }

    private fun setChartRange(range: ChartRange) {
        val route = _state.value.detailRoute?.copy(chartRange = range, replayOffset = 0) ?: return
        _state.value = _state.value.copy(detailRoute = route, detailNotice = null)
        loadDetailData(route.symbol)
    }

    private fun stepReplayBack() {
        val state = _state.value
        val route = state.detailRoute ?: return
        var backingCandles = state.replayBackingCharts[route.chartRange]
        var totalCandles = projectedChartTotalCandles(state, route)
            ?: backingCandles?.size
            ?: state.detailCharts[route.chartRange].orEmpty().size
        _state.value = state.copy(
            detailRoute = route.copy(
                replayOffset = ChartAnalysis.stepReplayBack(route.replayOffset, totalCandles),
            ),
        )
        if (backingCandles == null) {
            viewModelScope.launch { ensureReplayBackingLoaded(route.symbol, route.chartRange) }
        }
    }

    private fun stepReplayForward() {
        val state = _state.value
        val route = state.detailRoute ?: return
        _state.value = state.copy(
            detailRoute = route.copy(
                replayOffset = ChartAnalysis.stepReplayForward(route.replayOffset),
            ),
        )
    }

    private fun resetReplay() {
        val state = _state.value
        val route = state.detailRoute ?: return
        _state.value = state.copy(detailRoute = route.copy(replayOffset = 0))
    }

    /**
     * Writes the note, then reads the table back.
     *
     * The store decides what a blank note means, and the screen must show what the next start will
     * show. Reading back keeps that rule in one place. A note is written when the reader leaves the
     * field, so the extra read costs nothing anybody can feel.
     */
    private fun saveNote(symbol: String, note: String) {
        viewModelScope.launch {
            saveSymbolNote(symbol, note)
            _state.value = _state.value.copy(symbolNotes = loadSymbolNotes())
        }
    }

    private fun toggleWatchlist(symbol: String) {
        viewModelScope.launch {
            val snapshot = toggleDashboardWatchlist(
                symbol,
                currentFilter(),
                _state.value.detailRoute?.symbol,
                _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                _state.value.opportunityScoringModel,
            )
            render(snapshot)
        }
    }

    private fun addSymbols(rawInput: String) {
        if (rawInput.isBlank()) return
        viewModelScope.launch {
            val snapshot = addDashboardSymbols(
                rawInput,
                currentFilter(),
                _state.value.detailRoute?.symbol,
                _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                _state.value.opportunityScoringModel,
            )
            render(snapshot)
        }
    }

    private fun selectProfile(profile: String) {
        detailSessions.clear()
        _state.value = _state.value.copy(
            detailRoute = null,
            detailData = null,
            projectedDetailData = null,
            detailCharts = emptyMap(),
            replayBackingCharts = emptyMap(),
            detailHistory = emptyList(),
            detailAlerts = emptyList(),
            detailQuantLens = null,
            detailNotice = null,
            estimatesNotice = null,
        )
        activeEstimatesJob?.cancel()
        var previousSelect = selectProfileJob
        var previousDetail = detailLoadJob
        var previousRefresh = refreshJob
        previousSelect?.cancel()
        previousDetail?.cancel()
        previousRefresh?.cancel()
        selectProfileJob = viewModelScope.launch {
            previousSelect?.join()
            previousDetail?.join()
            previousRefresh?.join()
            val snapshot = selectDashboardProfile(
                profile,
                currentFilter(),
                _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                _state.value.opportunityScoringModel,
            )
            render(snapshot)
        }
    }

    private fun sourceSymbolsForTab(state: DashboardUiState, sourceTab: DetailSourceTab): List<String> =
        when (sourceTab) {
            DetailSourceTab.Opportunities -> state.opportunityRows.map { it.symbol }
            DetailSourceTab.Tracked -> visibleTrackedRows(state).map { it.symbol }
        }

    /**
     * Draws the symbol from what is already on file, before anything is fetched for it.
     *
     * [selectDashboardSymbol] reaches the provider, and a provider that is refusing calls holds
     * every one of them: measured on a device on 2026-08-20, one refused call every eight seconds
     * for eight minutes without a break, and the detail screen stayed empty for all of it. What
     * the app filed for this symbol is on disk and costs no network, so the screen is drawn from
     * that first and drawn again when the fetch lands. A symbol with nothing on file draws nothing
     * and loses nothing, which is the screen this replaces.
     */
    private suspend fun renderDetailOnFile(symbol: String) {
        var route = _state.value.detailRoute ?: return
        if (route.symbol != symbol) return
        render(
            getDashboardSnapshot(
                currentFilter(),
                symbol,
                route.chartRange,
                _state.value.opportunityScoringModel,
            ),
        )
    }

    private fun loadDetailData(symbol: String) {
        detailLoadJob?.cancel()
        detailLoadJob = viewModelScope.launch {
            try {
                renderDetailOnFile(symbol)
                val snapshot = selectDashboardSymbol(
                    symbol,
                    currentFilter(),
                    _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                    _state.value.opportunityScoringModel,
                )
                render(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    detailNotice = DashboardNotice(
                        title = "Ticker unavailable",
                        message = error.message ?: "The ticker could not be opened.",
                        severity = DashboardNoticeSeverity.Warning,
                    ),
                )
            }
        }
    }

    private fun toggleOpportunityScoringModel() {
        val nextModel = when (_state.value.opportunityScoringModel) {
            OpportunityScoringModel.Legacy -> OpportunityScoringModel.Aggressive
            OpportunityScoringModel.Aggressive -> OpportunityScoringModel.AggressiveV2
            OpportunityScoringModel.AggressiveV2 -> OpportunityScoringModel.AggressiveV3
            OpportunityScoringModel.AggressiveV3 -> OpportunityScoringModel.AggressiveV4
            OpportunityScoringModel.AggressiveV4 -> OpportunityScoringModel.AggressiveV5
            OpportunityScoringModel.AggressiveV5 -> OpportunityScoringModel.Legacy
        }
        setOpportunityScoringModel(nextModel)
    }

    private fun setOpportunityScoringModel(model: OpportunityScoringModel) {
        if (_state.value.opportunityScoringModel == model) {
            return
        }
        _state.value = _state.value.copy(opportunityScoringModel = model)
        viewModelScope.launch {
            persistScoringPreferences(currentScoringPreferences())
            render(
                getDashboardSnapshot(
                    currentFilter(),
                    _state.value.detailRoute?.symbol,
                    _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                    model,
                ),
            )
        }
    }

    /**
     * The switch re-scores every V3 row, so the snapshot is rebuilt rather than the flag flipped in
     * place. Persisting first is what makes the repository apply it before the rows are rebuilt.
     */
    private fun setRegimeScoringEnabled(enabled: Boolean) {
        if (_state.value.regimeScoringEnabled == enabled) {
            return
        }
        _state.value = _state.value.copy(regimeScoringEnabled = enabled)
        viewModelScope.launch {
            persistScoringPreferences(currentScoringPreferences())
            render(
                getDashboardSnapshot(
                    currentFilter(),
                    _state.value.detailRoute?.symbol,
                    _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                    _state.value.opportunityScoringModel,
                ),
            )
        }
    }

    private fun currentScoringPreferences() = ScoringPreferences(
        opportunityModel = _state.value.opportunityScoringModel,
        regimeScoringEnabled = _state.value.regimeScoringEnabled,
    )

    private fun currentFilter(): ViewFilter =
        ViewFilter(query = _state.value.query, watchlistOnly = false)

    private fun visibleTrackedRows(state: DashboardUiState): List<TrackedSymbolRow> =
        if (state.currentTab == DashboardTab.Watch) {
            state.trackedRows.filter { it.isWatched }
        } else {
            state.trackedRows
        }

    private fun projectedChartTotalCandles(state: DashboardUiState, route: DetailRoute): Int? {
        var projectedDetail = state.projectedDetailData ?: return null
        if (projectedDetail.symbol != route.symbol || projectedDetail.chart.range != route.chartRange) return null
        var replayTotal = projectedDetail.chart.analysis.replayWindow.totalCandles
        if (replayTotal > 0) return replayTotal
        var candleTotal = projectedDetail.chart.candles.size
        return candleTotal.takeIf { total -> total > 0 }
    }

    private fun loadEstimates() {
        activeEstimatesJob?.cancel()
        _state.value = _state.value.copy(indexEstimatesLoading = true)
        activeEstimatesJob = viewModelScope.launch {
            try {
                when (val result = getIndexEstimates(_state.value.opportunityScoringModel)) {
                    is ComputationResult.Error -> {
                        _state.value = _state.value.copy(
                            estimatesNotice = DashboardNotice(
                                title = "Estimates unavailable",
                                message = result.failure.message,
                            ),
                        )
                        return@launch
                    }
                    is ComputationResult.Success -> {
                        val report = result.value
                        val profileName = report.profileName
                        // Policy lives in repository: one durable point per UTC day,
                        // skips enrichment micro-noise, coalesces legacy multi-row days on read.
                        saveEstimatesSnapshot(report)
                        val history = getEstimatesHistory(profileName)
                        _state.value = _state.value.copy(
                            indexEstimates = report,
                            estimatesHistory = history,
                            estimatesNotice = null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    estimatesNotice = DashboardNotice(
                        title = "Estimates unavailable",
                        message = error.message ?: "Estimates could not be refreshed.",
                    ),
                )
            } finally {
                _state.value = _state.value.copy(indexEstimatesLoading = false)
            }
        }
    }

    private fun refreshSystemStats() {
        _state.value = _state.value.copy(systemStatsLoading = true)
        viewModelScope.launch {
            val stats = loadSystemStats()
            _state.value = _state.value.copy(systemStats = stats, systemStatsLoading = false)
        }
    }

    private fun exportScoreCsv() {
        viewModelScope.launch {
            var snapshot = _state.value
            // A failed export must say so. A silent failure here would look like an export that
            // produced nothing to correlate, which is the one thing the measurement cannot survive.
            var message = try {
                var result = exportScores(snapshot.currentProfile, snapshot.opportunityScoringModel)
                "Exported ${result.rowCount} scored rows to ${result.path}"
            } catch (error: Throwable) {
                "Score export failed: ${error.message ?: "unknown error"}"
            }
            _state.value = _state.value.copy(systemStatusMessage = message)
        }
    }

    /**
     * The measurement Wave 4b exists to produce. Same failure discipline as the export above: a
     * silent failure would read as a retrospective that found nothing, which is the one answer this
     * report must never fake.
     */
    private fun runRetrospectiveReport() {
        viewModelScope.launch {
            var message = try {
                var result = runRetrospective(_state.value.currentProfile)
                "Retrospective over ${result.symbolCount} symbols written to ${result.path}"
            } catch (error: Throwable) {
                "Retrospective failed: ${error.message ?: "unknown error"}"
            }
            _state.value = _state.value.copy(systemStatusMessage = message)
        }
    }

    /**
     * The journal's reading half: joins every recorded scoring pass to the daily bars that
     * followed and writes the per-model outcome report. Same failure discipline as the
     * retrospective — a silent failure would read as a measurement that found nothing.
     */
    private fun runOutcomeReportAction() {
        viewModelScope.launch {
            var message = try {
                var result = runOutcomeReport(_state.value.currentProfile)
                "Outcome report over ${result.rowCount} journal rows written to ${result.path}"
            } catch (error: Throwable) {
                "Outcome report failed: ${error.message ?: "unknown error"}"
            }
            _state.value = _state.value.copy(systemStatusMessage = message)
        }
    }

    private fun pruneOldRevisions(retentionDays: Int) {
        viewModelScope.launch {
            val deleted = pruneOldRevisions(retentionDays)
            val message = "Pruned $deleted rows older than $retentionDays days"
            _state.value = _state.value.copy(systemStatusMessage = message)
            refreshSystemStats()
        }
    }

    private fun performClearAllData() {
        viewModelScope.launch {
            clearAllDataUseCase()
            started = false
            detailSessions.clear()
            _state.value = DashboardUiState()
            start()
        }
    }

    private fun rememberDetailSession(state: DashboardUiState) {
        var route = state.detailRoute ?: return
        var detail = state.detailData ?: return
        if (detail.symbol != route.symbol) return
        detailSessions.remove(route.symbol)
        detailSessions[route.symbol] = CachedDetailSession(
            symbol = route.symbol,
            detailData = detail,
            projectedDetailData = state.projectedDetailData,
            selectedScoreRow = state.selectedScoreRow,
            detailCharts = state.detailCharts,
            replayBackingCharts = state.replayBackingCharts,
            detailHistory = state.detailHistory,
            detailAlerts = state.detailAlerts,
            detailQuantLens = state.detailQuantLens,
        )
        while (detailSessions.size > MaxDetailSessions) {
            detailSessions.remove(detailSessions.keys.first())
        }
    }

    private fun applyCachedDetailSession(state: DashboardUiState, symbol: String): DashboardUiState {
        var session = detailSessions[symbol] ?: return state
        return state.copy(
            detailData = session.detailData,
            projectedDetailData = session.projectedDetailData,
            selectedScoreRow = session.selectedScoreRow?.takeIf { row -> row.symbol == symbol },
            detailCharts = session.detailCharts,
            replayBackingCharts = session.replayBackingCharts,
            detailHistory = session.detailHistory,
            detailAlerts = session.detailAlerts,
            detailQuantLens = session.detailQuantLens,
            detailNotice = null,
        )
    }

    private fun render(snapshot: DashboardSnapshot) {
        var currentState = _state.value
        var scoringMatches =
            snapshot.opportunityScoringModel == currentState.opportunityScoringModel &&
                snapshot.regimeScoringEnabled == currentState.regimeScoringEnabled
        var currentRoute = currentState.detailRoute
        var projectedDetail = snapshot.screenData.selectedDetail
        var selectedDetailMatchesRoute = currentRoute != null && snapshot.selectedDetail?.symbol == currentRoute.symbol
        var projectedDetailMatchesRoute = currentRoute != null && projectedDetail?.symbol == currentRoute.symbol
        var keepStaleDetail = currentRoute != null && currentState.detailData?.symbol == currentRoute.symbol
        var scoreRow = if (scoringMatches) snapshot.selectedScoreRow else currentState.selectedScoreRow
        var opportunityRows = if (scoringMatches) snapshot.opportunityRows else currentState.opportunityRows
        _state.value = currentState.copy(
            loading = snapshot.startupPhase == DashboardStartupPhase.Restoring,
            refreshing = snapshot.startupPhase == DashboardStartupPhase.SwitchingProfile ||
                snapshot.startupPhase == DashboardStartupPhase.Refreshing,
            availableProfiles = snapshot.availableProfiles,
            currentProfile = snapshot.currentProfile,
            trackedSymbols = snapshot.trackedSymbols,
            trackedRows = snapshot.trackedRows,
            watchlistSymbols = snapshot.watchlistSymbols,
            candidateRows = snapshot.candidateRows,
            opportunityRows = opportunityRows,
            selectedScoreRow = if (currentRoute != null && scoreRow?.symbol == currentRoute.symbol) {
                scoreRow
            } else {
                null
            },
            opportunityScoringModel = currentState.opportunityScoringModel,
            issues = snapshot.issues,
            detailData = if (selectedDetailMatchesRoute) {
                snapshot.selectedDetail
            } else if (keepStaleDetail) {
                currentState.detailData
            } else {
                null
            },
            projectedDetailData = if (projectedDetailMatchesRoute) {
                projectedDetail
            } else if (keepStaleDetail) {
                currentState.projectedDetailData
            } else {
                null
            },
            detailCharts = if (selectedDetailMatchesRoute) {
                snapshot.selectedCharts
            } else if (keepStaleDetail) {
                currentState.detailCharts
            } else {
                emptyMap()
            },
            detailHistory = if (selectedDetailMatchesRoute) {
                snapshot.selectedHistory
            } else if (keepStaleDetail) {
                currentState.detailHistory
            } else {
                emptyList()
            },
            detailAlerts = if (selectedDetailMatchesRoute) {
                snapshot.selectedAlerts
            } else if (keepStaleDetail) {
                currentState.detailAlerts
            } else {
                emptyList()
            },
            detailQuantLens = if (selectedDetailMatchesRoute) {
                mapQuantLensReport(snapshot.selectedQuantLens, snapshot.selectedDetail?.marketPriceCents)
            } else if (keepStaleDetail) {
                currentState.detailQuantLens
            } else {
                null
            },
            detailNotice = if (selectedDetailMatchesRoute) {
                snapshot.detailNotice
            } else if (keepStaleDetail) {
                currentState.detailNotice
            } else {
                null
            },
            replayBackingCharts = if (selectedDetailMatchesRoute) {
                snapshot.replayBackingCharts
            } else if (keepStaleDetail) {
                currentState.replayBackingCharts
            } else {
                emptyMap()
            },
            rowQuantLensChipsBySymbol = buildMap {
                snapshot.trackedRows.forEach { row ->
                    put(row.symbol, mapRowQuantLensSummary(row.quantLensSummary))
                }
                opportunityRows.forEach { row ->
                    put(row.symbol, mapRowQuantLensSummary(row.quantLensSummary))
                }
            },
            lastUpdatedAtEpochSeconds = snapshot.lastUpdatedAtEpochSeconds,
            startupPhase = snapshot.startupPhase,
            refreshCompletedSymbols = snapshot.refreshCompletedSymbols,
            refreshTargetSymbols = snapshot.refreshTargetSymbols,
            statusMessage = snapshot.statusMessage,
            providerState = snapshot.screenData.providerState,
            indexEstimates = snapshot.screenData.estimates.report,
            estimatesNotice = snapshot.estimatesNotice ?: currentState.estimatesNotice,
            marketRegime = presentMarketRegime(snapshot.marketRegime, snapshot.marketReadStatus),
            planBoardOpps = presentPlanBoard(snapshot.planBoard),
            planBoardProfile = presentPlanBoard(snapshot.planBoardProfile),
            leftoverBoard = presentLeftoverBoard(snapshot.leftoverBoard),
            crossBoardOpps = presentCrossBoard(snapshot.crossBoard),
            crossBoardProfile = presentCrossBoard(snapshot.crossBoardProfile),
        )
        rememberDetailSession(_state.value)
    }

    private data class CachedDetailSession(
        val symbol: String,
        val detailData: SymbolDetail,
        val projectedDetailData: ProjectedDetailData?,
        val selectedScoreRow: OpportunityListRow?,
        val detailCharts: Map<ChartRange, List<HistoricalCandle>>,
        val replayBackingCharts: Map<ChartRange, List<HistoricalCandle>>,
        val detailHistory: List<SymbolRevision>,
        val detailAlerts: List<AlertEvent>,
        val detailQuantLens: QuantLensUiState?,
    )

    companion object {
        private const val TICKER_SEARCH_DEBOUNCE_MS = 300L
        internal const val DashboardSnapshotDebounceMs = 300L
        private const val MaxDetailSessions = 12

        /** What a snapshot rebuild waits afterwards, as a multiple of what it cost. */
        private const val SNAPSHOT_REBUILD_COOLDOWN_FACTOR = 2

        /** Floor and ceiling of that wait: snappy on a small profile, bounded on a large one. */
        private const val MIN_SNAPSHOT_REBUILD_COOLDOWN_MS = 250L
        private const val MAX_SNAPSHOT_REBUILD_COOLDOWN_MS = 3_000L

        fun factory(useCases: DashboardUseCases): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    DashboardViewModel(
                        observeDashboardUpdates = useCases.observeDashboardUpdates,
                        bootstrapDashboard = useCases.bootstrapDashboard,
                        refreshDashboard = useCases.refreshDashboard,
                        getDashboardSnapshot = useCases.getDashboardSnapshot,
                        selectDashboardSymbol = useCases.selectDashboardSymbol,
                        addDashboardSymbols = useCases.addDashboardSymbols,
                        selectDashboardProfile = useCases.selectDashboardProfile,
                        toggleDashboardWatchlist = useCases.toggleDashboardWatchlist,
                        loadScoringPreferences = useCases.loadScoringPreferences,
                        persistScoringPreferences = useCases.persistScoringPreferences,
                        loadSymbolNotes = useCases.loadSymbolNotes,
                        saveSymbolNote = useCases.saveSymbolNote,
                        loadSystemStats = useCases.loadSystemStats,
                        pruneOldRevisions = useCases.pruneOldRevisions,
                        clearAllDataUseCase = useCases.clearAllData,
                        exportScores = useCases.exportScores,
                        runRetrospective = useCases.runRetrospective,
                        runOutcomeReport = useCases.runOutcomeReport,
                        getEarningsEvents = useCases.getEarningsEvents,
                        backUpEarningsLog = useCases.backUpEarningsLog,
                        restoreEarningsLog = useCases.restoreEarningsLog,
                        getIndexEstimates = useCases.getIndexEstimates,
                        saveEstimatesSnapshot = useCases.saveEstimatesSnapshot,
                        getEstimatesHistory = useCases.getEstimatesHistory,
                        searchTickers = useCases.searchTickers,
                        loadDiscoverySnapshot = useCases.loadDiscoverySnapshot,
                        saveDiscoveryConfig = useCases.saveDiscoveryConfig,
                        recreateDiscoveryUniverse = useCases.recreateDiscoveryUniverse,
                        refreshDiscoveryScores = useCases.refreshDiscoveryScores,
                        cancelDiscoveryJob = useCases.cancelDiscoveryJob,
                        clearDiscoveryData = useCases.clearDiscoveryData,
                        observeDiscoveryProgress = useCases.observeDiscoveryProgress,
                        ensureReplayBackingLoaded = useCases.ensureReplayBackingLoaded,
                    )
                }
            }
    }
}
