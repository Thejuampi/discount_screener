package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.domain.model.DashboardNotice
import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
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
import com.discountscreener.android.domain.usecase.GetEstimatesHistoryUseCase
import com.discountscreener.android.domain.usecase.GetIndexEstimatesUseCase
import com.discountscreener.android.domain.usecase.SaveEstimatesSnapshotUseCase
import com.discountscreener.android.domain.usecase.ToggleDashboardWatchlistUseCase
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.ScenarioEstimate
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ComputationArea
import com.discountscreener.core.model.ComputationFailure
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.IssueRecord
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ProjectedDashboardData
import com.discountscreener.core.model.ProjectedDetailData
import com.discountscreener.core.model.ProjectedEstimatesData
import com.discountscreener.core.model.ProjectedFairValueAnchor
import com.discountscreener.core.model.ProjectedFairValueRole
import com.discountscreener.core.model.ProjectedProvenanceState
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRevision
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
    fun select_tab_does_not_rebuild_dashboard_snapshot() = runTest(dispatcher) {
        var repository = RecordingDashboardRepository()
        var viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Watch))
        advanceUntilIdle()

        assertEquals(0, repository.currentSnapshotCallCount)
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
    fun dashboard_tabs_match_default_order() {
        assertEquals(
            listOf("Opportunities", "Tracked", "Watch", "System", "Estimates"),
            DashboardTab.entries.map { it.name },
        )
    }

    @Test
    fun dashboard_defaults_to_opportunities_with_aggressive_v2_scoring() {
        val state = DashboardUiState()

        assertEquals(DashboardTab.Opportunities, state.currentTab)
        assertEquals(OpportunityScoringModel.AggressiveV2, state.opportunityScoringModel)
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

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Tracked))
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
    fun replay_back_and_forward_clamp_to_loaded_chart_history() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository(
            detailData = detail("AAPL"),
            detailCharts = mapOf(
                ChartRange.Year to listOf(
                    candle(1),
                    candle(2),
                    candle(3),
                    candle(4),
                ),
            ),
        )
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()

        repeat(5) { viewModel.dispatch(DashboardAction.StepReplayBack) }
        assertEquals(3, viewModel.state.value.detailRoute?.replayOffset)

        viewModel.dispatch(DashboardAction.StepReplayForward)
        assertEquals(2, viewModel.state.value.detailRoute?.replayOffset)

        repeat(5) { viewModel.dispatch(DashboardAction.StepReplayForward) }
        assertEquals(0, viewModel.state.value.detailRoute?.replayOffset)
    }

    @Test
    fun reset_replay_returns_detail_chart_to_live() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository(
            detailData = detail("AAPL"),
            detailCharts = mapOf(ChartRange.Year to listOf(candle(1), candle(2))),
        )
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        viewModel.dispatch(DashboardAction.StepReplayBack)
        assertEquals(1, viewModel.state.value.detailRoute?.replayOffset)

        viewModel.dispatch(DashboardAction.ResetReplay)
        assertEquals(0, viewModel.state.value.detailRoute?.replayOffset)
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
    fun open_detail_keeps_loaded_history_available_when_switching_to_history_tab() = runTest(dispatcher) {
        val history = listOf(
            SymbolRevision(
                symbol = "AAPL",
                evaluatedAtEpochSeconds = 1_700_000_000L,
                detail = SymbolDetail(
                    symbol = "AAPL",
                    profitable = true,
                    marketPriceCents = 10_000L,
                    intrinsicValueCents = 15_000L,
                    gapBps = 5_000,
                    minimumGapBps = 1_500,
                    qualification = QualificationStatus.Qualified,
                    externalStatus = com.discountscreener.core.model.ExternalSignalStatus.Supportive,
                    externalSignalFairValueCents = 15_000L,
                    weightedExternalSignalFairValueCents = 15_000L,
                    weightedAnalystCount = 12,
                    externalSignalGapBps = 5_000,
                    externalSignalAgeSeconds = 0,
                    externalSignalMaxAgeSeconds = 86_400L,
                    confidence = ConfidenceBand.High,
                    lastSequence = 1,
                    updateCount = 1,
                    isWatched = false,
                ),
            ),
        )
        val repository = RecordingDashboardRepository(
            detailHistory = history,
            detailData = history.single().detail,
        )
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        viewModel.dispatch(DashboardAction.SetDetailSubtab(DetailSubtab.History))

        assertEquals(DetailSubtab.History, viewModel.state.value.detailRoute?.subtab)
        assertEquals(1, viewModel.state.value.detailHistory.size)
        assertEquals(15_000L, viewModel.state.value.detailHistory.single().detail.weightedExternalSignalFairValueCents)
    }

    @Test
    fun render_stores_projected_selected_detail_for_matching_route_symbol() = runTest(dispatcher) {
        var projectedDetail = projectedDetail("AAPL")
        var repository = RecordingDashboardRepository(
            detailData = projectedDetail.detail,
            projectedDetailData = projectedDetail,
        )
        var viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()

        assertEquals(projectedDetail, viewModel.state.value.projectedDetailData)
    }

    @Test
    fun render_retains_projected_detail_when_next_projection_is_for_different_symbol() = runTest(dispatcher) {
        var aaplProjection = projectedDetail("AAPL")
        var msftProjection = projectedDetail("MSFT")
        var repository = RecordingDashboardRepository(
            detailData = aaplProjection.detail,
            projectedDetailData = aaplProjection,
        )
        var viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        repository.setDetailProjection(msftProjection.detail, msftProjection)
        viewModel.dispatch(DashboardAction.UpdateQuery("route still AAPL"))
        advanceUntilIdle()

        assertEquals(aaplProjection, viewModel.state.value.projectedDetailData)
    }

    @Test
    fun render_does_not_apply_detail_notice_when_next_snapshot_is_for_different_symbol() = runTest(dispatcher) {
        var aaplProjection = projectedDetail("AAPL")
        var msftProjection = projectedDetail("MSFT")
        var repository = RecordingDashboardRepository(
            detailData = aaplProjection.detail,
            projectedDetailData = aaplProjection,
        )
        var viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        repository.setDetailProjection(
            detail = msftProjection.detail,
            projectedDetail = msftProjection,
            notice = DashboardNotice(
                title = "Quant Lens unavailable",
                message = "MSFT returned a malformed estimate range.",
            ),
        )
        viewModel.dispatch(DashboardAction.UpdateQuery("route still AAPL"))
        advanceUntilIdle()

        assertEquals(aaplProjection, viewModel.state.value.projectedDetailData)
        assertNull(viewModel.state.value.detailNotice)
    }

    @Test
    fun back_and_profile_switch_clear_projected_detail() = runTest(dispatcher) {
        var projectedDetail = projectedDetail("AAPL")
        var repository = RecordingDashboardRepository(
            detailData = projectedDetail.detail,
            projectedDetailData = projectedDetail,
        )
        var viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        viewModel.dispatch(DashboardAction.BackFromDetail)
        var afterBack = viewModel.state.value.projectedDetailData
        viewModel.dispatch(DashboardAction.OpenDetail("AAPL"))
        advanceUntilIdle()
        viewModel.dispatch(DashboardAction.SelectProfile("merval"))
        advanceUntilIdle()
        var afterProfileSwitch = viewModel.state.value.projectedDetailData

        assertEquals(
            ProjectedDetailLifecycleExpectation(afterBack = null, afterProfileSwitch = null),
            ProjectedDetailLifecycleExpectation(afterBack = afterBack, afterProfileSwitch = afterProfileSwitch),
        )
    }

    @Test
    fun toggle_opportunity_model_cycles_v2_to_legacy_to_aggressive_to_v2() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository(
            opportunityRows = listOf(OpportunityListRow(symbol = "LEGACY", marketPriceCents = 10_000L, intrinsicValueCents = 15_000L, gapBps = 3_333, confidence = ConfidenceBand.High, isWatched = false, compositeScore = 15, coverageCount = 3)),
            aggressiveRows = listOf(OpportunityListRow(symbol = "AGGRO", marketPriceCents = 10_000L, intrinsicValueCents = 20_000L, gapBps = 5_000, confidence = ConfidenceBand.High, isWatched = false, compositeScore = 27, coverageCount = 3)),
        )
        val viewModel = testViewModel(repository)
        assertEquals(OpportunityScoringModel.AggressiveV2, viewModel.state.value.opportunityScoringModel)

        // Cycle direction: Legacy -> Aggressive (V1) -> AggressiveV2 -> Legacy.
        // Starting from the V2 default, the first toggle wraps to Legacy.
        viewModel.dispatch(DashboardAction.ToggleOpportunityScoringModel)
        advanceUntilIdle()
        assertEquals(OpportunityScoringModel.Legacy, viewModel.state.value.opportunityScoringModel)
        assertEquals(OpportunityScoringModel.Legacy, repository.lastRequestedOpportunityModel)
        assertEquals(listOf("LEGACY"), viewModel.state.value.opportunityRows.map { it.symbol })

        viewModel.dispatch(DashboardAction.ToggleOpportunityScoringModel)
        advanceUntilIdle()
        assertEquals(OpportunityScoringModel.Aggressive, viewModel.state.value.opportunityScoringModel)
        assertEquals(OpportunityScoringModel.Aggressive, repository.lastRequestedOpportunityModel)

        viewModel.dispatch(DashboardAction.ToggleOpportunityScoringModel)
        advanceUntilIdle()
        assertEquals(OpportunityScoringModel.AggressiveV2, viewModel.state.value.opportunityScoringModel)
        assertEquals(OpportunityScoringModel.AggressiveV2, repository.lastRequestedOpportunityModel)
    }

    @Test
    fun set_opportunity_model_selects_the_requested_chip_model() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository(
            opportunityRows = listOf(OpportunityListRow(symbol = "LEGACY", marketPriceCents = 10_000L, intrinsicValueCents = 15_000L, gapBps = 3_333, confidence = ConfidenceBand.High, isWatched = false, compositeScore = 15, coverageCount = 3)),
            aggressiveRows = listOf(OpportunityListRow(symbol = "AGGRO", marketPriceCents = 10_000L, intrinsicValueCents = 20_000L, gapBps = 5_000, confidence = ConfidenceBand.High, isWatched = false, compositeScore = 27, coverageCount = 3)),
        )
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.SetOpportunityScoringModel(OpportunityScoringModel.Legacy))
        advanceUntilIdle()

        assertEquals(OpportunityScoringModel.Legacy, viewModel.state.value.opportunityScoringModel)
        assertEquals(OpportunityScoringModel.Legacy, repository.lastRequestedOpportunityModel)
        assertEquals(listOf("LEGACY"), viewModel.state.value.opportunityRows.map { it.symbol })
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

    @Test
    fun load_estimates_saves_snapshot_when_history_is_empty() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        val viewModel = testViewModel(repository)
        viewModel.dispatch(DashboardAction.Start)
        advanceUntilIdle()

        assertEquals(1, repository.saveSnapshotCallCount)
        assertFalse(viewModel.state.value.estimatesHistory.isEmpty())
    }

    @Test
    fun load_estimates_skips_save_when_upside_unchanged() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository()
        // Pre-seed with an identical zero-upside report to match what the engine returns with empty data
        val zeroReport = IndexEstimatesReport(
            profileName = "dow",
            currentWeightedPriceCents = 0L,
            totalSymbols = 0,
            scenarios = EstimateScenario.entries.map { s ->
                ScenarioEstimate(scenario = s, weightedPriceCents = 0L, coverageCount = 0, impliedUpsideBps = 0)
            },
            computedAtEpochSeconds = 1_000L,
        )
        repository.savedSnapshots.add(zeroReport)

        val viewModel = testViewModel(repository)
        viewModel.dispatch(DashboardAction.Start)
        advanceUntilIdle()

        // saveSnapshotCallCount must remain 0 because the upside values are identical
        assertEquals(0, repository.saveSnapshotCallCount)
    }

    @Test
    fun load_estimates_uses_projected_screen_data_report_without_separate_reads() = runTest(dispatcher) {
        var report = estimatesReport(profileName = "projected", currentWeightedPriceCents = 123_456L)
        var repository = RecordingDashboardRepository(projectedEstimatesReport = report)
        var viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Estimates))
        advanceUntilIdle()

        assertEquals(
            EstimatesProjectionUseCaseExpectation(
                report = report,
                currentSnapshotCalls = 0,
                currentIndexEstimatesCalls = 1,
                trackedDetailsCalls = 0,
                dcfSnapshotCalls = 0,
            ),
            EstimatesProjectionUseCaseExpectation(
                report = viewModel.state.value.indexEstimates,
                currentSnapshotCalls = repository.currentSnapshotCallCount,
                currentIndexEstimatesCalls = repository.currentIndexEstimatesCallCount,
                trackedDetailsCalls = repository.trackedSymbolDetailsCallCount,
                dcfSnapshotCalls = repository.dcfSnapshotCallCount,
            ),
        )
    }

    @Test
    fun load_estimates_surfaces_non_fatal_notice_when_result_is_error() = runTest(dispatcher) {
        val repository = RecordingDashboardRepository(
            currentIndexEstimatesResult = ComputationResult.Error(
                ComputationFailure(
                    code = "index_estimates_failed",
                    area = ComputationArea.Estimates,
                    message = "Estimate model fell outside the supported range.",
                    recoverable = true,
                ),
            ),
        )
        val viewModel = testViewModel(repository)

        viewModel.dispatch(DashboardAction.SelectTab(DashboardTab.Estimates))
        advanceUntilIdle()

        assertEquals("Estimates unavailable", viewModel.state.value.estimatesNotice?.title)
        assertEquals(
            "Estimate model fell outside the supported range.",
            viewModel.state.value.estimatesNotice?.message,
        )
        assertFalse(viewModel.state.value.indexEstimatesLoading)
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
            getIndexEstimates = GetIndexEstimatesUseCase(repository),
            saveEstimatesSnapshot = SaveEstimatesSnapshotUseCase(repository),
            getEstimatesHistory = GetEstimatesHistoryUseCase(repository),
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

    private fun candle(epochSeconds: Long) = HistoricalCandle(
        epochSeconds = epochSeconds,
        openCents = 10_000L,
        highCents = 10_100L,
        lowCents = 9_900L,
        closeCents = 10_050L,
        volume = 1_000L,
    )

    private fun detail(symbol: String) = SymbolDetail(
        symbol = symbol,
        profitable = true,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        gapBps = 3_333,
        minimumGapBps = 1_500,
        qualification = QualificationStatus.Qualified,
        externalStatus = com.discountscreener.core.model.ExternalSignalStatus.Supportive,
        externalSignalFairValueCents = 15_000L,
        weightedExternalSignalFairValueCents = 15_000L,
        weightedAnalystCount = 12,
        externalSignalGapBps = 5_000,
        externalSignalAgeSeconds = 0,
        externalSignalMaxAgeSeconds = 86_400L,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )

    private fun projectedDetail(symbol: String) = ProjectedDetailData(
        symbol = symbol,
        detail = detail(symbol).copy(intrinsicValueCents = 18_000L),
        fairValueAnchor = ProjectedFairValueAnchor.model(
            valueCents = 18_000L,
            sourceLabel = "Source unknown - saved model",
            role = ProjectedFairValueRole.SourceFreeModel,
            compactLabel = "Saved model",
            provenanceState = ProjectedProvenanceState.SourceUnknown,
        ),
    )

    private class RecordingDashboardRepository(
        private val trackedRows: List<TrackedSymbolRow> = emptyList(),
        private val opportunityRows: List<OpportunityListRow> = emptyList(),
        private val aggressiveRows: List<OpportunityListRow> = opportunityRows,
        private val detailHistory: List<SymbolRevision> = emptyList(),
        private var detailData: SymbolDetail? = null,
        private val detailCharts: Map<ChartRange, List<HistoricalCandle>> = emptyMap(),
        private val projectedEstimatesReport: IndexEstimatesReport = estimatesReport(),
        private val currentIndexEstimatesResult: ComputationResult<IndexEstimatesReport> =
            ComputationResult.Success(projectedEstimatesReport),
        private var projectedDetailData: ProjectedDetailData? = null,
        private var detailNotice: DashboardNotice? = null,
    ) : DashboardRepository {
        var saveSnapshotCallCount = 0
        var currentSnapshotCallCount = 0
        var currentIndexEstimatesCallCount = 0
        var dcfSnapshotCallCount = 0
        var trackedSymbolDetailsCallCount = 0
        val savedSnapshots = mutableListOf<IndexEstimatesReport>()
        var lastCurrentFilter: ViewFilter? = null
        var lastRequestedOpportunityModel: OpportunityScoringModel? = null
        private val updates = MutableStateFlow(0L)
        private var currentProfile = "dow"

        fun setDetailProjection(
            detail: SymbolDetail?,
            projectedDetail: ProjectedDetailData?,
            notice: DashboardNotice? = null,
        ) {
            detailData = detail
            projectedDetailData = projectedDetail
            detailNotice = notice
        }

        override fun observeUpdates(): Flow<Long> = updates

        override suspend fun bootstrap(
            filter: ViewFilter,
            selectedSymbol: String?,
            selectedRange: ChartRange,
            opportunityScoringModel: OpportunityScoringModel,
        ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

        override suspend fun currentSnapshot(
            filter: ViewFilter,
            selectedSymbol: String?,
            selectedRange: ChartRange,
            opportunityScoringModel: OpportunityScoringModel,
        ): DashboardSnapshot {
            currentSnapshotCallCount++
            lastCurrentFilter = filter
            lastRequestedOpportunityModel = opportunityScoringModel
            return emptySnapshot(opportunityScoringModel)
        }

        override suspend fun currentIndexEstimates(): ComputationResult<IndexEstimatesReport> {
            currentIndexEstimatesCallCount++
            return currentIndexEstimatesResult
        }

        override suspend fun refreshAll(
            filter: ViewFilter,
            selectedSymbol: String?,
            selectedRange: ChartRange,
            opportunityScoringModel: OpportunityScoringModel,
        ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

        override suspend fun ensureDetailLoaded(
            symbol: String,
            filter: ViewFilter,
            selectedRange: ChartRange,
            opportunityScoringModel: OpportunityScoringModel,
        ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

        override suspend fun addSymbols(
            rawInput: String,
            filter: ViewFilter,
            selectedSymbol: String?,
            selectedRange: ChartRange,
            opportunityScoringModel: OpportunityScoringModel,
        ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

        override suspend fun selectProfile(
            profile: String,
            filter: ViewFilter,
            selectedRange: ChartRange,
            opportunityScoringModel: OpportunityScoringModel,
        ): DashboardSnapshot {
            currentProfile = profile
            return emptySnapshot(
                opportunityScoringModel = opportunityScoringModel,
                startupPhase = DashboardStartupPhase.SwitchingProfile,
                statusMessage = "Switching to ${profile.uppercase()}…",
            )
        }

        override suspend fun toggleWatchlist(
            symbol: String,
            filter: ViewFilter,
            selectedSymbol: String?,
            selectedRange: ChartRange,
            opportunityScoringModel: OpportunityScoringModel,
        ): DashboardSnapshot = emptySnapshot(opportunityScoringModel)

        override suspend fun loadSystemStats(): SystemStats = SystemStats(
            databaseFileSizeBytes = 0L,
            tables = emptyList(),
            logTables = emptyList(),
        )

        override suspend fun pruneOldRevisions(retentionDays: Int): Int = 0

        override suspend fun clearAllData() = Unit

        override suspend fun saveEstimatesSnapshot(report: IndexEstimatesReport) {
            saveSnapshotCallCount++
            savedSnapshots.add(report)
        }

        override suspend fun estimatesHistory(profileName: String): List<IndexEstimatesReport> =
            savedSnapshots.toList()

        override suspend fun dcfSnapshot(): Map<String, DcfAnalysis> {
            dcfSnapshotCallCount++
            return emptyMap()
        }

        override suspend fun trackedSymbolDetails(): List<SymbolDetail> {
            trackedSymbolDetailsCallCount++
            return trackedRows.map { row ->
                SymbolDetail(
                symbol = row.symbol,
                profitable = true,
                marketPriceCents = row.marketPriceCents ?: 0L,
                intrinsicValueCents = row.intrinsicValueCents ?: 0L,
                gapBps = row.gapBps ?: 0,
                minimumGapBps = 1_500,
                qualification = row.qualification ?: QualificationStatus.Qualified,
                externalStatus = com.discountscreener.core.model.ExternalSignalStatus.Missing,
                externalSignalMaxAgeSeconds = 86_400L,
                confidence = row.confidence ?: ConfidenceBand.Low,
                lastSequence = 1,
                updateCount = 1,
                isWatched = row.isWatched,
                )
            }
        }

        private fun emptySnapshot(
            opportunityScoringModel: OpportunityScoringModel = OpportunityScoringModel.AggressiveV2,
            startupPhase: DashboardStartupPhase = DashboardStartupPhase.Ready,
            statusMessage: String? = null,
        ) = DashboardSnapshot(
            availableProfiles = emptyList(),
            currentProfile = currentProfile,
            trackedSymbols = trackedRows.map { it.symbol },
            trackedRows = trackedRows,
            watchlistSymbols = emptyList(),
            candidateRows = emptyList(),
            opportunityRows = if (opportunityScoringModel == OpportunityScoringModel.Legacy) opportunityRows else aggressiveRows,
            opportunityScoringModel = opportunityScoringModel,
            issues = emptyList(),
            selectedDetail = detailData,
            selectedCharts = detailCharts,
            selectedHistory = detailHistory,
            selectedAlerts = emptyList(),
            detailNotice = detailNotice,
            lastUpdatedAtEpochSeconds = null,
            startupPhase = startupPhase,
            refreshCompletedSymbols = 0,
            refreshTargetSymbols = 0,
            statusMessage = statusMessage,
            screenData = ProjectedDashboardData(
                selectedDetail = projectedDetailData,
                estimates = ProjectedEstimatesData(report = projectedEstimatesReport),
            ),
        )
    }

    private data class ProjectedDetailLifecycleExpectation(
        val afterBack: ProjectedDetailData?,
        val afterProfileSwitch: ProjectedDetailData?,
    )

    private data class EstimatesProjectionUseCaseExpectation(
        val report: IndexEstimatesReport?,
        val currentSnapshotCalls: Int,
        val currentIndexEstimatesCalls: Int,
        val trackedDetailsCalls: Int,
        val dcfSnapshotCalls: Int,
    )

    companion object {
        private fun estimatesReport(
            profileName: String = "dow",
            currentWeightedPriceCents: Long = 0L,
        ) = IndexEstimatesReport(
            profileName = profileName,
            currentWeightedPriceCents = currentWeightedPriceCents,
            totalSymbols = 0,
            scenarios = EstimateScenario.entries.map { scenario ->
                ScenarioEstimate(
                    scenario = scenario,
                    weightedPriceCents = currentWeightedPriceCents,
                    coverageCount = 0,
                    impliedUpsideBps = 0,
                )
            },
            computedAtEpochSeconds = 1_000L,
        )
    }
}
