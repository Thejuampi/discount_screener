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
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.SystemStats
import com.discountscreener.android.domain.model.TickerSearchSuggestion
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.domain.usecase.AddDashboardSymbolsUseCase
import com.discountscreener.android.domain.usecase.BootstrapDashboardUseCase
import com.discountscreener.android.domain.usecase.ClearAllDataUseCase
import com.discountscreener.android.domain.usecase.DashboardUseCases
import com.discountscreener.android.domain.usecase.GetDashboardSnapshotUseCase
import com.discountscreener.android.domain.usecase.GetIndexEstimatesUseCase
import com.discountscreener.android.domain.usecase.GetEstimatesHistoryUseCase
import com.discountscreener.android.domain.usecase.SaveEstimatesSnapshotUseCase
import com.discountscreener.android.domain.usecase.SearchTickersUseCase
import com.discountscreener.android.domain.usecase.LoadSystemStatsUseCase
import com.discountscreener.android.domain.usecase.ObserveDashboardUpdatesUseCase
import com.discountscreener.android.domain.usecase.PruneOldRevisionsUseCase
import com.discountscreener.android.domain.usecase.RefreshDashboardUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardProfileUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardSymbolUseCase
import com.discountscreener.android.domain.usecase.ToggleDashboardWatchlistUseCase
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

enum class DashboardTab {
    Opportunities,
    Tracked,
    Watch,
    System,
    Estimates,
}

enum class DetailSubtab {
    Snapshot,
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
    data class AddSymbols(val rawInput: String) : DashboardAction
    data class SelectProfile(val profile: String) : DashboardAction
    data object ToggleOpportunityScoringModel : DashboardAction
    data class SetOpportunityScoringModel(val model: OpportunityScoringModel) : DashboardAction
    data object RefreshSystemStats : DashboardAction
    data class PruneOldRevisions(val retentionDays: Int) : DashboardAction
    data object ClearAllData : DashboardAction
}

data class DashboardUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val currentTab: DashboardTab = DashboardTab.Opportunities,
    val availableProfiles: List<String> = emptyList(),
    val currentProfile: String = "sp500",
    val query: String = "",
    val tickerSearchQuery: String = "",
    val tickerSearchSuggestions: List<TickerSearchSuggestion> = emptyList(),
    val tickerSearchExpanded: Boolean = false,
    val tickerSearchNotice: DashboardNotice? = null,
    val trackedSymbols: List<String> = emptyList(),
    val trackedRows: List<TrackedSymbolRow> = emptyList(),
    val watchlistSymbols: List<String> = emptyList(),
    val candidateRows: List<CandidateRow> = emptyList(),
    val opportunityRows: List<OpportunityListRow> = emptyList(),
    val opportunityScoringModel: OpportunityScoringModel = OpportunityScoringModel.AggressiveV2,
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
)

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
    private val loadSystemStats: LoadSystemStatsUseCase,
    private val pruneOldRevisions: PruneOldRevisionsUseCase,
    private val clearAllDataUseCase: ClearAllDataUseCase,
    private val getIndexEstimates: GetIndexEstimatesUseCase,
    private val saveEstimatesSnapshot: SaveEstimatesSnapshotUseCase,
    private val getEstimatesHistory: GetEstimatesHistoryUseCase,
    private val searchTickers: SearchTickersUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var started = false
    private var activeEstimatesJob: kotlinx.coroutines.Job? = null

    fun dispatch(action: DashboardAction) {
        when (action) {
            DashboardAction.Start -> start()
            DashboardAction.Refresh -> refresh()
            is DashboardAction.SelectTab -> selectTab(action.tab)
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
            is DashboardAction.AddSymbols -> addSymbols(action.rawInput)
            is DashboardAction.SelectProfile -> selectProfile(action.profile)
            DashboardAction.ToggleOpportunityScoringModel -> toggleOpportunityScoringModel()
            is DashboardAction.SetOpportunityScoringModel -> setOpportunityScoringModel(action.model)
            DashboardAction.RefreshSystemStats -> refreshSystemStats()
            is DashboardAction.PruneOldRevisions -> pruneOldRevisions(action.retentionDays)
            DashboardAction.ClearAllData -> performClearAllData()
        }
    }

    private fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            observeDashboardUpdates().collectLatest {
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
        viewModelScope.launch {
            observeDashboardUpdates()
                .debounce(2_000L)
                .collectLatest { loadEstimates() }
        }
        viewModelScope.launch {
            val initial = bootstrapDashboard(
                currentFilter(),
                _state.value.detailRoute?.symbol,
                _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                _state.value.opportunityScoringModel,
            )
            render(initial)
            refresh()
            loadEstimates()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val snapshot = refreshDashboard(
                currentFilter(),
                _state.value.detailRoute?.symbol,
                _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                _state.value.opportunityScoringModel,
            )
            render(snapshot)
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

    private fun selectTab(tab: DashboardTab) {
        _state.value = _state.value.copy(currentTab = tab)
        if (tab == DashboardTab.System && _state.value.systemStats == null) {
            refreshSystemStats()
        }
        if (tab == DashboardTab.Estimates) {
            loadEstimates()
        }
    }

    private fun updateTickerSearchQuery(query: String) {
        _state.value = _state.value.copy(
            tickerSearchQuery = query,
            tickerSearchExpanded = query.isNotBlank(),
            tickerSearchNotice = null,
        )
        viewModelScope.launch {
            val suggestions = if (query.isBlank()) {
                emptyList()
            } else {
                searchTickers(query, _state.value.currentProfile)
            }
            _state.value = _state.value.copy(
                tickerSearchSuggestions = suggestions,
                tickerSearchExpanded = query.isNotBlank() && suggestions.isNotEmpty(),
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
        val symbol = _state.value.tickerSearchQuery.trim().uppercase()
        if (symbol.isBlank()) return
        selectTickerSuggestion(symbol)
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
            DashboardTab.Opportunities -> DetailSourceTab.Opportunities
            else -> DetailSourceTab.Tracked
        }
        val sourceSymbols = sourceSymbolsForTab(state, sourceTab).takeIf { symbol in it } ?: listOf(symbol)
        val detailRoute = DetailRoute(
            symbol = symbol,
            sourceTab = sourceTab,
            sourceSymbols = sourceSymbols,
        )
        _state.value = state.copy(
            detailRoute = detailRoute,
            detailNotice = null,
            tickerSearchQuery = "",
            tickerSearchExpanded = false,
            tickerSearchSuggestions = emptyList(),
            tickerSearchNotice = null,
        )
        viewModelScope.launch {
            try {
                val snapshot = selectDashboardSymbol(
                    symbol,
                    currentFilter(),
                    _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                    _state.value.opportunityScoringModel,
                )
                render(snapshot)
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
        _state.value = _state.value.copy(
            detailRoute = null,
            detailData = null,
            projectedDetailData = null,
            detailCharts = emptyMap(),
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
        _state.value = _state.value.copy(
            detailRoute = route.copy(symbol = newSymbol, replayOffset = 0),
            detailNotice = null,
            tickerSearchQuery = newSymbol,
        )
        loadDetailData(newSymbol)
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
        var totalCandles = projectedChartTotalCandles(state, route) ?: state.detailCharts[route.chartRange].orEmpty().size
        _state.value = state.copy(
            detailRoute = route.copy(
                replayOffset = ChartAnalysis.stepReplayBack(route.replayOffset, totalCandles),
            ),
        )
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
        _state.value = _state.value.copy(
            detailRoute = null,
            detailData = null,
            projectedDetailData = null,
            detailCharts = emptyMap(),
            detailHistory = emptyList(),
            detailAlerts = emptyList(),
            detailQuantLens = null,
            detailNotice = null,
            estimatesNotice = null,
        )
        viewModelScope.launch {
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

    private fun loadDetailData(symbol: String) {
        viewModelScope.launch {
            try {
                val snapshot = selectDashboardSymbol(
                    symbol,
                    currentFilter(),
                    _state.value.detailRoute?.chartRange ?: ChartRange.Year,
                    _state.value.opportunityScoringModel,
                )
                render(snapshot)
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
            OpportunityScoringModel.AggressiveV3 -> OpportunityScoringModel.Legacy
        }
        setOpportunityScoringModel(nextModel)
    }

    private fun setOpportunityScoringModel(model: OpportunityScoringModel) {
        if (_state.value.opportunityScoringModel == model) {
            return
        }
        _state.value = _state.value.copy(opportunityScoringModel = model)
        viewModelScope.launch {
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
            _state.value = DashboardUiState()
            start()
        }
    }

    private fun render(snapshot: DashboardSnapshot) {
        var currentState = _state.value
        var currentRoute = currentState.detailRoute
        var projectedDetail = snapshot.screenData.selectedDetail
        var selectedDetailMatchesRoute = currentRoute != null && snapshot.selectedDetail?.symbol == currentRoute.symbol
        var projectedDetailMatchesRoute = currentRoute != null && projectedDetail?.symbol == currentRoute.symbol
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
            opportunityRows = snapshot.opportunityRows,
            opportunityScoringModel = snapshot.opportunityScoringModel,
            issues = snapshot.issues,
            detailData = if (selectedDetailMatchesRoute) {
                snapshot.selectedDetail
            } else {
                currentState.detailData
            },
            projectedDetailData = if (projectedDetailMatchesRoute) {
                projectedDetail
            } else {
                currentState.projectedDetailData
            },
            detailCharts = if (selectedDetailMatchesRoute) {
                snapshot.selectedCharts
            } else {
                currentState.detailCharts
            },
            detailHistory = if (selectedDetailMatchesRoute) {
                snapshot.selectedHistory
            } else {
                currentState.detailHistory
            },
            detailAlerts = if (selectedDetailMatchesRoute) {
                snapshot.selectedAlerts
            } else {
                currentState.detailAlerts
            },
            detailQuantLens = if (selectedDetailMatchesRoute) {
                mapQuantLensReport(snapshot.selectedQuantLens, snapshot.selectedDetail?.marketPriceCents)
            } else {
                currentState.detailQuantLens
            },
            detailNotice = if (selectedDetailMatchesRoute) snapshot.detailNotice else currentState.detailNotice,
            rowQuantLensChipsBySymbol = buildMap {
                snapshot.trackedRows.forEach { row ->
                    put(row.symbol, mapRowQuantLensSummary(row.quantLensSummary))
                }
                snapshot.opportunityRows.forEach { row ->
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
        )
    }

    companion object {
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
                        loadSystemStats = useCases.loadSystemStats,
                        pruneOldRevisions = useCases.pruneOldRevisions,
                        clearAllDataUseCase = useCases.clearAllData,
                        getIndexEstimates = useCases.getIndexEstimates,
                        saveEstimatesSnapshot = useCases.saveEstimatesSnapshot,
                        getEstimatesHistory = useCases.getEstimatesHistory,
                        searchTickers = useCases.searchTickers,
                    )
                }
            }
    }
}
