package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.SystemStats
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.android.domain.usecase.AddDashboardSymbolsUseCase
import com.discountscreener.android.domain.usecase.BootstrapDashboardUseCase
import com.discountscreener.android.domain.usecase.ClearAllDataUseCase
import com.discountscreener.android.domain.usecase.GetDashboardSnapshotUseCase
import com.discountscreener.android.domain.usecase.LoadSystemStatsUseCase
import com.discountscreener.android.domain.usecase.ObserveDashboardUpdatesUseCase
import com.discountscreener.android.domain.usecase.PruneOldRevisionsUseCase
import com.discountscreener.android.domain.usecase.RefreshDashboardUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardProfileUseCase
import com.discountscreener.android.domain.usecase.SelectDashboardSymbolUseCase
import com.discountscreener.android.domain.usecase.ToggleDashboardWatchlistUseCase
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.IssueRecord
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun update_query_requests_snapshot_with_latest_filter() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.UpdateQuery("MSFT"))
        advanceUntilIdle()

        assertEquals("MSFT", repository.lastCurrentFilter?.query)
        assertEquals("MSFT", viewModel.state.value.query)
    }

    @Test
    fun select_tab_updates_current_tab() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Watch))
        advanceUntilIdle()
        assertEquals(DashboardTab.Watch, viewModel.state.value.currentTab)
    }

    @Test
    fun watch_tab_keeps_snapshot_unfiltered_for_stable_counts() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Watch))
        advanceUntilIdle()

        assertEquals(DashboardTab.Watch, viewModel.state.value.currentTab)
        assertFalse(repository.lastCurrentFilter?.watchlistOnly == true)
    }

    @Test
    fun upside_tab_requests_full_snapshot() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Watch))
        advanceUntilIdle()
        assertFalse(repository.lastCurrentFilter?.watchlistOnly == true)

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Tracked))
        advanceUntilIdle()

        assertEquals(DashboardTab.Tracked, viewModel.state.value.currentTab)
        assertFalse(repository.lastCurrentFilter?.watchlistOnly == true)
    }

    @Test
    fun dashboard_tabs_match_simplified_order() {
        assertEquals(
            listOf("Tracked", "Opportunities", "Watch", "System"),
            DashboardTab.entries.map { it.name },
        )
    }

    @Test
    fun open_detail_sets_detail_route_with_source_tab() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Watch))
        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()

        val route = viewModel.state.value.detailRoute
        assertNotNull(route)
        assertEquals("AAPL", route?.symbol)
        assertEquals(DetailSourceTab.Tracked, route?.sourceTab)
    }

    @Test
    fun back_from_detail_clears_route() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.detailRoute)

        viewModel.dispatch(DashboardAction.BackFromDetail)
        assertNull(viewModel.state.value.detailRoute)
        assertNull(viewModel.state.value.detailData)
    }

    @Test
    fun prev_next_ticker_navigates_source_symbols() = runTest(dispatcher) {
        val rows = listOf(
            trackedRow("AAPL"),
            trackedRow("MSFT"),
            trackedRow("GOOG"),
        )
        val repository = RecordingDashboardRepository(trackedRows = rows)
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.Start)
        advanceUntilIdle()

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        assertEquals("AAPL", viewModel.state.value.detailRoute?.symbol)

        viewModel.dispatch(DashboardAction.NextTicker)
        assertEquals("MSFT", viewModel.state.value.detailRoute?.symbol)

        viewModel.dispatch(DashboardAction.NextTicker)
        assertEquals("GOOG", viewModel.state.value.detailRoute?.symbol)

        viewModel.dispatch(DashboardAction.NextTicker)
        assertEquals("GOOG", viewModel.state.value.detailRoute?.symbol)

        viewModel.dispatch(DashboardAction.PrevTicker)
        assertEquals("MSFT", viewModel.state.value.detailRoute?.symbol)
    }

    @Test
    fun set_detail_subtab_updates_route() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        assertEquals(DetailSubtab.Snapshot, viewModel.state.value.detailRoute?.subtab)

        viewModel.dispatch(DashboardAction.SetDetailSubtab(DetailSubtab.History))
        assertEquals(DetailSubtab.History, viewModel.state.value.detailRoute?.subtab)
    }

    @Test
    fun set_chart_range_resets_replay_offset() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        viewModel.dispatch(DashboardAction.SetReplayOffset(5))
        assertEquals(5, viewModel.state.value.detailRoute?.replayOffset)

        viewModel.dispatch(DashboardAction.SetChartRange(ChartRange.Month))
        assertEquals(0, viewModel.state.value.detailRoute?.replayOffset)
        assertEquals(ChartRange.Month, viewModel.state.value.detailRoute?.chartRange)
    }

    @Test
    fun history_subview_and_metric_group_update_route() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()

        viewModel.dispatch(DashboardAction.SetHistorySubview(HistorySubview.Table))
        assertEquals(HistorySubview.Table, viewModel.state.value.detailRoute?.historySubview)

        viewModel.dispatch(DashboardAction.SetHistoryMetricGroup(HistoryMetricGroup.Dcf))
        assertEquals(HistoryMetricGroup.Dcf, viewModel.state.value.detailRoute?.historyMetricGroup)
    }

    @Test
    fun set_replay_offset_updates_route() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()

        viewModel.dispatch(DashboardAction.SetReplayOffset(10))
        assertEquals(10, viewModel.state.value.detailRoute?.replayOffset)
    }

    @Test
    fun open_detail_from_watch_uses_tracked_source() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Watch))
        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()

        assertEquals(DetailSourceTab.Tracked, viewModel.state.value.detailRoute?.sourceTab)
    }

    @Test
    fun open_detail_from_opportunities_uses_opportunities_source() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Opportunities))
        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()

        assertEquals(DetailSourceTab.Opportunities, viewModel.state.value.detailRoute?.sourceTab)
    }

    @Test
    fun select_profile_clears_detail_and_shows_switching_state() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.detailRoute)

        viewModel.dispatch(DashboardAction.SelectProfile("merval"))
        advanceUntilIdle()

        assertNull(viewModel.state.value.detailRoute)
        assertEquals("merval", viewModel.state.value.currentProfile)
        assertEquals(DashboardStartupPhase.SwitchingProfile, viewModel.state.value.startupPhase)
        assertTrue(viewModel.state.value.refreshing)
        assertEquals("Switching to MERVAL…", viewModel.state.value.statusMessage)
    }

    private fun testViewModel(repository: DashboardRepository): DashboardViewModel {
        return DashboardViewModel(
            observeDashboardUpdates = ObserveDashboardUpdatesUseCase(repository),
            bootstrapDashboard = BootstrapDashboardUseCase(repository),
            refreshDashboard = RefreshDashboardUseCase(repository),
            getDashboardSnapshot = GetDashboardSnapshotUseCase(repository),
            selectDashboardSymbol = SelectDashboardSymbolUseCase(repository),
            addDashboardSymbols = AddDashboardSymbolsUseCase(repository),
            selectDashboardProfile = SelectDashboardProfileUseCase(repository),
            toggleDashboardWatchlist = ToggleDashboardWatchlistUseCase(repository),
            loadSystemStats = LoadSystemStatsUseCase(repository),
            pruneOldRevisions = PruneOldRevisionsUseCase(repository),
            clearAllDataUseCase = ClearAllDataUseCase(repository),
        )
    }

    private fun trackedRow(symbol: String) = TrackedSymbolRow(
        symbol = symbol,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        gapBps = 3_333,
        confidence = ConfidenceBand.High,
        qualification = QualificationStatus.Qualified,
        isWatched = false,
    )

    private class RecordingDashboardRepository(
        private val trackedRows: List<TrackedSymbolRow> = emptyList(),
    ) : DashboardRepository {
        var lastCurrentFilter: ViewFilter? = null
        private val updates = MutableStateFlow(0L)
        private var currentProfile = "dow"

        override fun observeUpdates(): Flow<Long> = updates

        override suspend fun bootstrap(filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange): DashboardSnapshot =
            emptySnapshot()

        override suspend fun currentSnapshot(filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange): DashboardSnapshot {
            lastCurrentFilter = filter
            return emptySnapshot()
        }

        override suspend fun refreshAll(filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange): DashboardSnapshot =
            emptySnapshot()

        override suspend fun ensureDetailLoaded(symbol: String, filter: ViewFilter, selectedRange: ChartRange): DashboardSnapshot =
            emptySnapshot()

        override suspend fun addSymbols(rawInput: String, filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange): DashboardSnapshot =
            emptySnapshot()

        override suspend fun selectProfile(profile: String, filter: ViewFilter, selectedRange: ChartRange): DashboardSnapshot {
            currentProfile = profile
            return emptySnapshot(
                startupPhase = DashboardStartupPhase.SwitchingProfile,
                statusMessage = "Switching to ${profile.uppercase()}…",
            )
        }

        override suspend fun toggleWatchlist(symbol: String, filter: ViewFilter, selectedSymbol: String?, selectedRange: ChartRange): DashboardSnapshot =
            emptySnapshot()

        override suspend fun loadSystemStats(): SystemStats = SystemStats(
            databaseFileSizeBytes = 0L,
            tables = emptyList(),
            logTables = emptyList(),
        )

        override suspend fun pruneOldRevisions(retentionDays: Int): Int = 0

        override suspend fun clearAllData() = Unit

        private fun emptySnapshot(
            startupPhase: DashboardStartupPhase = DashboardStartupPhase.Ready,
            statusMessage: String? = null,
        ) = DashboardSnapshot(
            availableProfiles = emptyList(),
            currentProfile = currentProfile,
            trackedSymbols = trackedRows.map { it.symbol },
            trackedRows = trackedRows,
            watchlistSymbols = emptyList(),
            candidateRows = emptyList(),
            opportunityRows = emptyList(),
            issues = emptyList(),
            selectedDetail = null,
            selectedCharts = emptyMap(),
            selectedHistory = emptyList(),
            selectedAlerts = emptyList(),
            lastUpdatedAtEpochSeconds = null,
            startupPhase = startupPhase,
            refreshCompletedSymbols = 0,
            refreshTargetSymbols = 0,
            statusMessage = statusMessage,
        )
    }
}
