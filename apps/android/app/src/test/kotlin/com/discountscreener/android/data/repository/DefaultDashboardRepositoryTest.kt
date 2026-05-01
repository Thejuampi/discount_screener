package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.CaptureKind
import com.discountscreener.android.data.persistence.EvaluatedSymbolState
import com.discountscreener.android.data.persistence.MetricGroupStatus
import com.discountscreener.android.data.persistence.RawCapture
import com.discountscreener.android.data.persistence.RawCapturePayload
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.persistence.SymbolRevisionInput
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderDiagnostic
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.domain.model.ChangeDirection
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.RowExplanationKind
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.android.domain.model.ValuationChangeTier
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.PriceHistoryPoint
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRevision
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultDashboardRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    private val legacyModel = OpportunityScoringModel.Legacy

    @Test
    fun bootstrap_uses_sp500_even_when_db_remembers_single_symbol() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            store.replaceTrackedSymbols(listOf("MSTR"))
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            val snapshot = repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)

            assertEquals("sp500", snapshot.currentProfile)
            assertTrue(snapshot.trackedSymbols.size > 400)
            assertFalse(snapshot.trackedSymbols.contains("MSTR"))
        } finally {
            store.close()
        }
    }

    @Test
    fun bootstrap_restores_cached_rows_and_reorders_by_persisted_ranking() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            seedWarmState(store)
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            val snapshot = repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)

            assertEquals(DashboardStartupPhase.ShowingCached, snapshot.startupPhase)
            assertEquals(listOf("NVDA", "AAPL", "MSFT"), snapshot.trackedRows.take(3).map { it.symbol })
            assertTrue(snapshot.trackedRows.take(3).all { it.state == TrackedRowState.Cached })
            assertEquals("NVIDIA Corporation", snapshot.trackedRows.first().companyName)
            assertEquals(
                "NVIDIA Corporation",
                snapshot.candidateRows.first { it.symbol == "NVDA" }.companyName,
            )
            assertTrue(snapshot.candidateRows.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun refresh_compares_live_rows_against_cached_rank_and_weighted_fair_value() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            seedWarmState(store)
            val client = object : FakeYahooFinanceClient(delayMs = 5) {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    delay(5)
                    return when (symbol) {
                        "AAPL" -> fetchResult(symbol, marketPriceCents = 10_000, intrinsicValueCents = 25_000, weightedFairValueCents = 24_000)
                        "NVDA" -> fetchResult(symbol, marketPriceCents = 10_000, intrinsicValueCents = 18_000, weightedFairValueCents = 18_000)
                        "MSFT" -> fetchResult(symbol, marketPriceCents = 10_000, intrinsicValueCents = 11_500, weightedFairValueCents = 11_400)
                        else -> super.fetchSymbol(symbol)
                    }
                }
            }
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, legacyModel)
            val snapshot = awaitSnapshot(repository) { current ->
                current.trackedRows.count { it.state == TrackedRowState.Live } >= 3
            }

            val aapl = snapshot.trackedRows.first { it.symbol == "AAPL" }
            assertEquals(ChangeDirection.Up, aapl.rankMovement?.direction)
            assertEquals(1, aapl.rankMovement?.places)
            assertEquals(ChangeDirection.Up, aapl.valuationChange?.direction)
            assertEquals(15_000L, aapl.valuationChange?.previousFairValueCents)
            assertEquals(24_000L, aapl.valuationChange?.currentFairValueCents)
            assertEquals(ValuationChangeTier.Major, aapl.valuationChange?.tier)

            val nvda = snapshot.trackedRows.first { it.symbol == "NVDA" }
            assertEquals(ChangeDirection.Down, nvda.rankMovement?.direction)
            assertEquals(1, nvda.rankMovement?.places)
            assertEquals(ChangeDirection.Down, nvda.valuationChange?.direction)
            assertEquals(20_000L, nvda.valuationChange?.previousFairValueCents)
            assertEquals(18_000L, nvda.valuationChange?.currentFairValueCents)
            assertEquals(ValuationChangeTier.Significant, nvda.valuationChange?.tier)
        } finally {
            store.close()
        }
    }

    @Test
    fun ensure_detail_loaded_replays_distinct_historical_weighted_fair_values() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            store.persistBatch(
                rawCaptures = emptyList(),
                revisions = listOf(
                    revision(
                        symbol = "NVDA",
                        marketPriceCents = 10_000,
                        intrinsicValueCents = 18_000,
                        gapBps = 4_444,
                        weightedFairValueCents = 19_500,
                        evaluatedAt = 1_699_000_000L,
                    ),
                    revision(
                        symbol = "NVDA",
                        marketPriceCents = 11_000,
                        intrinsicValueCents = 22_000,
                        gapBps = 5_000,
                        weightedFairValueCents = 24_000,
                        evaluatedAt = 1_700_000_000L,
                    ),
                ),
            )
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            val snapshot = repository.ensureDetailLoaded("NVDA", ViewFilter(), ChartRange.Year, legacyModel)

            val weightedHistory = snapshot.selectedHistory
                .take(2)
                .map { it.detail.weightedExternalSignalFairValueCents }
            assertEquals(listOf(19_500L, 24_000L), weightedHistory)
        } finally {
            store.close()
        }
    }

    @Test
    fun merge_revision_history_keeps_persisted_weighted_entries_when_runtime_history_is_already_present() {
        val persistedHistory = listOf(
            sampleRevision(weightedFairValueCents = 19_500L, evaluatedAtEpochSeconds = 1_699_000_000L),
            sampleRevision(weightedFairValueCents = 24_000L, evaluatedAtEpochSeconds = 1_700_000_000L),
        )
        val runtimeHistory = listOf(
            sampleRevision(weightedFairValueCents = null, evaluatedAtEpochSeconds = 1_700_000_500L),
        )

        val merged = mergeRevisionHistory(persistedHistory, runtimeHistory)

        assertEquals(
            listOf(19_500L, 24_000L, null),
            merged.map { it.detail.weightedExternalSignalFairValueCents },
        )
    }

    @Test
    fun merge_historical_candles_replaces_existing_duplicate_and_sorts_new_candles() {
        val existing = listOf(
            candle(epochSeconds = 20, closeCents = 2_000),
            candle(epochSeconds = 10, closeCents = 1_000),
        )
        val incoming = listOf(
            candle(epochSeconds = 30, closeCents = 3_000),
            candle(epochSeconds = 20, closeCents = 9_999),
        )

        val merged = mergeHistoricalCandles(
            symbol = "NVDA",
            range = ChartRange.Year,
            persistedCandles = existing,
            incomingCandles = incoming,
        )

        assertEquals(listOf(10L, 20L, 30L), merged.map { it.epochSeconds })
        assertEquals(9_999L, merged.first { it.epochSeconds == 20L }.closeCents)
    }

    @Test
    fun sqlite_persists_complete_deduped_pricing_candles_across_chart_captures() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            store.persistBatch(
                rawCaptures = listOf(
                    chartCapture(
                        symbol = "NVDA",
                        range = ChartRange.Year,
                        capturedAt = 100,
                        candles = listOf(
                            candle(epochSeconds = 20, closeCents = 2_000),
                            candle(epochSeconds = 10, closeCents = 1_000),
                        ),
                    ),
                    chartCapture(
                        symbol = "NVDA",
                        range = ChartRange.Year,
                        capturedAt = 200,
                        candles = listOf(
                            candle(epochSeconds = 20, closeCents = 9_999),
                            candle(epochSeconds = 30, closeCents = 3_000),
                        ),
                    ),
                ),
                revisions = emptyList(),
            )

            val chart = store.loadPricingHistory("NVDA").single {
                it.symbol == "NVDA" && it.range == ChartRange.Year
            }

            assertEquals(listOf(10L, 20L, 30L), chart.candles.map { it.epochSeconds })
            assertEquals(9_999L, chart.candles.first { it.epochSeconds == 20L }.closeCents)
            assertEquals(200L, chart.fetchedAt)
        } finally {
            store.close()
        }
    }

    @Test
    fun sqlite_load_pricing_history_merges_pricing_table_and_legacy_raw_ranges() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            store.persistBatch(
                rawCaptures = listOf(
                    chartCapture(
                        symbol = "NVDA",
                        range = ChartRange.Year,
                        capturedAt = 100,
                        candles = listOf(candle(epochSeconds = 10, closeCents = 1_000)),
                    ),
                    chartCapture(
                        symbol = "NVDA",
                        range = ChartRange.Month,
                        capturedAt = 200,
                        candles = listOf(candle(epochSeconds = 20, closeCents = 2_000)),
                    ),
                ),
                revisions = emptyList(),
            )
            store.writableDatabase.delete(
                "pricing_candle",
                "symbol = ? AND chart_range = ?",
                arrayOf("NVDA", ChartRange.Month.name),
            )

            val history = store.loadPricingHistory("NVDA")

            assertEquals(listOf(ChartRange.Month, ChartRange.Year), history.map { it.range }.sortedBy { it.name })
        } finally {
            store.close()
        }
    }

    @Test
    fun row_freshness_prioritizes_restored_updating_updated_and_stale_states() {
        assertEquals(
            RowFreshness.Restored,
            rowFreshnessFor(
                hasDetail = true,
                issueMessage = null,
                isRefreshed = false,
                stale = true,
                startupPhase = DashboardStartupPhase.ShowingCached,
            ),
        )
        assertEquals(
            RowFreshness.Updating,
            rowFreshnessFor(
                hasDetail = true,
                issueMessage = null,
                isRefreshed = false,
                stale = true,
                startupPhase = DashboardStartupPhase.Refreshing,
            ),
        )
        assertEquals(
            RowFreshness.Updated,
            rowFreshnessFor(
                hasDetail = true,
                issueMessage = null,
                isRefreshed = true,
                stale = false,
                startupPhase = DashboardStartupPhase.Ready,
            ),
        )
        assertEquals(
            RowFreshness.Stale,
            rowFreshnessFor(
                hasDetail = true,
                issueMessage = null,
                isRefreshed = false,
                stale = true,
                startupPhase = DashboardStartupPhase.Ready,
            ),
        )
        assertEquals(
            RowFreshness.Issue,
            rowFreshnessFor(
                hasDetail = false,
                issueMessage = "Provider error",
                isRefreshed = false,
                stale = false,
                startupPhase = DashboardStartupPhase.Ready,
            ),
        )
    }

    @Test
    fun row_explanation_prioritizes_combined_target_price_rank_and_empty_states() {
        assertEquals(
            RowExplanationKind.CombinedMove,
            rowExplanationFor(
                hasComparableBaseline = true,
                hasRankMovement = true,
                hasPriceMovement = true,
                hasTargetMovement = true,
            ),
        )
        assertEquals(
            RowExplanationKind.TargetChanged,
            rowExplanationFor(
                hasComparableBaseline = true,
                hasRankMovement = false,
                hasPriceMovement = false,
                hasTargetMovement = true,
            ),
        )
        assertEquals(
            RowExplanationKind.RelativeReRank,
            rowExplanationFor(
                hasComparableBaseline = true,
                hasRankMovement = true,
                hasPriceMovement = false,
                hasTargetMovement = false,
            ),
        )
        assertEquals(
            RowExplanationKind.NoBaseline,
            rowExplanationFor(
                hasComparableBaseline = false,
                hasRankMovement = false,
                hasPriceMovement = false,
                hasTargetMovement = false,
            ),
        )
        assertEquals(
            RowExplanationKind.NoMeaningfulChange,
            rowExplanationFor(
                hasComparableBaseline = true,
                hasRankMovement = false,
                hasPriceMovement = false,
                hasTargetMovement = false,
            ),
        )
    }

    @Test
    fun row_trust_note_only_surfaces_missing_analyst_target() {
        assertEquals(
            "No analyst target",
            rowTrustNote(
                detail = sampleDetail(
                    symbol = "NVDA",
                    marketPriceCents = 10_000L,
                    intrinsicValueCents = 18_000L,
                ),
                issueMessage = null,
            ),
        )
        assertNull(
            rowTrustNote(
                detail = sampleDetail(
                    symbol = "NVDA",
                    marketPriceCents = 10_000L,
                    intrinsicValueCents = 18_000L,
                ).copy(
                    weightedExternalSignalFairValueCents = 18_500L,
                    weightedAnalystCount = 20,
                ),
                issueMessage = null,
            ),
        )
        assertNull(
            rowTrustNote(
                detail = sampleDetail(
                    symbol = "NVDA",
                    marketPriceCents = 10_000L,
                    intrinsicValueCents = 18_000L,
                ).copy(
                    externalSignalFairValueCents = 18_300L,
                    analystOpinionCount = 16,
                ),
                issueMessage = null,
            ),
        )
    }

    @Test
    fun tracked_decision_state_marks_fresh_high_confidence_qualified_rows_as_act() {
        assertEquals(
            RowDecisionState.Act,
            trackedDecisionStateFor(
                state = TrackedRowState.Live,
                freshness = RowFreshness.Updated,
                qualification = QualificationStatus.Qualified,
                confidence = ConfidenceBand.High,
                upsideBps = 2_500,
                trustNote = null,
            ),
        )
    }

    @Test
    fun tracked_decision_state_marks_unprofitable_rows_as_avoid() {
        assertEquals(
            RowDecisionState.Avoid,
            trackedDecisionStateFor(
                state = TrackedRowState.Live,
                freshness = RowFreshness.Updated,
                qualification = QualificationStatus.Unprofitable,
                confidence = ConfidenceBand.High,
                upsideBps = 2_500,
                trustNote = null,
            ),
        )
    }

    @Test
    fun tracked_decision_state_allows_act_without_recent_change_explanation() {
        assertEquals(
            RowDecisionState.Act,
            trackedDecisionStateFor(
                state = TrackedRowState.Live,
                freshness = RowFreshness.Updated,
                qualification = QualificationStatus.Qualified,
                confidence = ConfidenceBand.High,
                upsideBps = 2_500,
                trustNote = null,
            ),
        )
    }

    @Test
    fun tracked_decision_state_keeps_trust_cautioned_rows_on_watch() {
        assertEquals(
            RowDecisionState.Watch,
            trackedDecisionStateFor(
                state = TrackedRowState.Live,
                freshness = RowFreshness.Updated,
                qualification = QualificationStatus.Qualified,
                confidence = ConfidenceBand.High,
                upsideBps = 2_500,
                trustNote = "No analyst target",
            ),
        )
    }

    @Test
    fun tracked_decision_state_hides_non_live_rows() {
        assertNull(
            trackedDecisionStateFor(
                state = TrackedRowState.Cached,
                freshness = RowFreshness.Restored,
                qualification = QualificationStatus.Qualified,
                confidence = ConfidenceBand.High,
                upsideBps = 2_500,
                trustNote = null,
            ),
        )
        assertNull(
            trackedDecisionStateFor(
                state = TrackedRowState.Live,
                freshness = RowFreshness.Issue,
                qualification = QualificationStatus.Qualified,
                confidence = ConfidenceBand.High,
                upsideBps = 2_500,
                trustNote = null,
            ),
        )
    }

    @Test
    fun opportunity_decision_state_marks_fresh_high_score_rows_as_act() {
        assertEquals(
            RowDecisionState.Act,
            opportunityDecisionStateFor(
                freshness = RowFreshness.Updated,
                confidence = ConfidenceBand.High,
                upsideBps = 2_500,
                compositeScore = 10,
                trustNote = null,
            ),
        )
    }

    @Test
    fun opportunity_decision_state_marks_low_score_rows_as_avoid() {
        assertEquals(
            RowDecisionState.Avoid,
            opportunityDecisionStateFor(
                freshness = RowFreshness.Updated,
                confidence = ConfidenceBand.Provisional,
                upsideBps = 400,
                compositeScore = 7,
                trustNote = null,
            ),
        )
    }

    @Test
    fun opportunity_decision_state_keeps_missing_target_rows_on_watch() {
        assertEquals(
            RowDecisionState.Watch,
            opportunityDecisionStateFor(
                freshness = RowFreshness.Updated,
                confidence = ConfidenceBand.High,
                upsideBps = 2_500,
                compositeScore = 12,
                trustNote = "No analyst target",
            ),
        )
    }

    @Test
    fun opportunity_decision_state_hides_non_live_rows() {
        assertNull(
            opportunityDecisionStateFor(
                freshness = RowFreshness.Restored,
                confidence = ConfidenceBand.High,
                upsideBps = 2_500,
                compositeScore = 12,
                trustNote = null,
            ),
        )
        assertNull(
            opportunityDecisionStateFor(
                freshness = RowFreshness.Issue,
                confidence = ConfidenceBand.High,
                upsideBps = 2_500,
                compositeScore = 12,
                trustNote = null,
            ),
        )
    }

    @Test
    fun significant_relative_move_uses_five_percent_threshold() {
        assertFalse(hasSignificantRelativeMove(previousCents = 10_000L, currentCents = 10_499L))
        assertTrue(hasSignificantRelativeMove(previousCents = 10_000L, currentCents = 10_500L))
    }

    @Test
    fun first_launch_shows_loading_rows_then_progressively_refreshes_live_data() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = FakeYahooFinanceClient(delayMs = 25)
            val repository = buildRepository(store = store, client = client)

            val bootstrap = repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            assertTrue(bootstrap.trackedRows.isNotEmpty())
            assertTrue(bootstrap.trackedRows.take(10).all { it.state == TrackedRowState.Loading })

            val scheduled = repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)
            assertEquals("dow", scheduled.currentProfile)
            assertEquals(DashboardStartupPhase.SwitchingProfile, scheduled.startupPhase)
            assertEquals("Switching to DOW…", scheduled.statusMessage)
            assertEquals(0, scheduled.refreshCompletedSymbols)
            assertEquals(scheduled.trackedSymbols.size, scheduled.refreshTargetSymbols)
            assertTrue(scheduled.trackedRows.all { it.state == TrackedRowState.Loading })

            val finished = awaitSnapshot(repository) { snapshot ->
                snapshot.trackedRows.any { it.state == TrackedRowState.Live } &&
                    snapshot.candidateRows.isNotEmpty()
            }
            assertTrue(finished.trackedRows.any { it.state == TrackedRowState.Live })
            assertTrue(finished.candidateRows.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun refresh_keeps_cached_symbol_live_when_quote_html_404s_but_chart_succeeds() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            var quoteHtml404Mode = false
            val client = object : FakeYahooFinanceClient() {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    if (quoteHtml404Mode && symbol == "AAPL") {
                        return ProviderFetchResult(
                            symbol = symbol,
                            snapshot = null,
                            externalSignal = null,
                            fundamentals = null,
                            coverage = ProviderCoverage(
                                core = ProviderComponentState.Missing,
                                external = ProviderComponentState.Missing,
                                fundamentals = ProviderComponentState.Missing,
                            ),
                            diagnostics = listOf(
                                ProviderDiagnostic(
                                    component = "quoteHtml",
                                    kind = "error",
                                    detail = "HTTP 404 for https://finance.yahoo.com/quote/AAPL",
                                    retryable = false,
                                ),
                            ),
                        )
                    }
                    return super.fetchSymbol(symbol)
                }

                override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                    if (quoteHtml404Mode && symbol == "AAPL" && range == ChartRange.Year) {
                        return listOf(
                            HistoricalCandle(
                                epochSeconds = 1_700_000_000L,
                                openCents = 12_100L,
                                highCents = 12_500L,
                                lowCents = 12_000L,
                                closeCents = 12_345L,
                                volume = 1_000L,
                            ),
                        )
                    }
                    return super.fetchHistoricalCandles(symbol, range)
                }
            }
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)
            awaitSnapshot(repository) { snapshot ->
                snapshot.trackedRows.any { it.symbol == "AAPL" && it.state == TrackedRowState.Live }
            }

            quoteHtml404Mode = true
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, legacyModel)
            val row = awaitSnapshot(repository) { snapshot ->
                snapshot.trackedRows.any {
                    it.symbol == "AAPL" &&
                        it.state == TrackedRowState.Live &&
                        it.marketPriceCents == 12_345L &&
                        it.providerIssue.isNullOrBlank()
                }
            }
                .trackedRows
                .first { it.symbol == "AAPL" }

            assertEquals(TrackedRowState.Live, row.state)
            assertEquals(12_345L, row.marketPriceCents)
            assertTrue(row.providerIssue.isNullOrBlank())
        } finally {
            store.close()
        }
    }

    @Test
    fun refresh_uses_dcf_fallback_for_quote_html_404() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = object : FakeYahooFinanceClient() {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    if (symbol == "AAPL") {
                        return ProviderFetchResult(
                            symbol = symbol,
                            snapshot = null,
                            externalSignal = null,
                            fundamentals = null,
                            coverage = ProviderCoverage(
                                core = ProviderComponentState.Missing,
                                external = ProviderComponentState.Missing,
                                fundamentals = ProviderComponentState.Missing,
                            ),
                            diagnostics = listOf(
                                ProviderDiagnostic(
                                    component = "quoteHtml",
                                    kind = "error",
                                    detail = "HTTP 404 for https://finance.yahoo.com/quote/AAPL",
                                    retryable = false,
                                ),
                            ),
                        )
                    }
                    return super.fetchSymbol(symbol)
                }

                override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                    if (symbol == "AAPL" && range == ChartRange.Year) {
                        return listOf(
                            HistoricalCandle(
                                epochSeconds = 1_700_000_000L,
                                openCents = 12_000L,
                                highCents = 12_800L,
                                lowCents = 11_900L,
                                closeCents = 12_345L,
                                volume = 1_000L,
                            ),
                        )
                    }
                    return super.fetchHistoricalCandles(symbol, range)
                }

                override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                    if (symbol == "AAPL") {
                        return richTimeseries()
                    }
                    return super.fetchFundamentalTimeseries(symbol)
                }
            }
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)
            val row = awaitSnapshot(repository) { snapshot ->
                snapshot.trackedRows.any {
                    it.symbol == "AAPL" &&
                        it.state == TrackedRowState.Live &&
                        it.marketPriceCents == 12_345L &&
                        (it.intrinsicValueCents ?: 0L) > 12_345L &&
                        it.providerIssue.isNullOrBlank()
                }
            }
                .trackedRows
                .first { it.symbol == "AAPL" }

            assertEquals(TrackedRowState.Live, row.state)
            assertEquals(12_345L, row.marketPriceCents)
            assertTrue((row.intrinsicValueCents ?: 0L) > 12_345L)
            assertTrue(row.providerIssue.isNullOrBlank())
        } finally {
            store.close()
        }
    }

    @Test
    fun refresh_uses_dcf_fallback_without_chart_when_quote_page_has_market_cap() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = object : FakeYahooFinanceClient() {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    if (symbol == "AAPL") {
                        return ProviderFetchResult(
                            symbol = symbol,
                            snapshot = null,
                            externalSignal = null,
                            fundamentals = FundamentalSnapshot(
                                symbol = symbol,
                                marketCapDollars = 600_000_000_000L,
                                sharesOutstanding = 4_800_000_000L,
                            ),
                            coverage = ProviderCoverage(
                                core = ProviderComponentState.Missing,
                                external = ProviderComponentState.Missing,
                                fundamentals = ProviderComponentState.Fresh,
                            ),
                            diagnostics = listOf(
                                ProviderDiagnostic(
                                    component = "core",
                                    kind = "missing",
                                    detail = "core snapshot is missing target mean price",
                                    retryable = false,
                                ),
                            ),
                        )
                    }
                    return super.fetchSymbol(symbol)
                }

                override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                    if (symbol == "AAPL" && range == ChartRange.Year) {
                        return emptyList()
                    }
                    return super.fetchHistoricalCandles(symbol, range)
                }

                override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                    if (symbol == "AAPL") {
                        return richTimeseries()
                    }
                    return super.fetchFundamentalTimeseries(symbol)
                }
            }
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)
            val row = awaitSnapshot(repository) { snapshot ->
                snapshot.trackedRows.any {
                    it.symbol == "AAPL" &&
                        it.state == TrackedRowState.Live &&
                        it.marketPriceCents == 12_500L &&
                        (it.intrinsicValueCents ?: 0L) > 12_500L &&
                        it.providerIssue.isNullOrBlank()
                }
            }
                .trackedRows
                .first { it.symbol == "AAPL" }

            assertEquals(TrackedRowState.Live, row.state)
            assertEquals(12_500L, row.marketPriceCents)
            assertTrue((row.intrinsicValueCents ?: 0L) > 12_500L)
            assertTrue(row.providerIssue.isNullOrBlank())
        } finally {
            store.close()
        }
    }

    private fun buildRepository(
        store: SQLiteStateStore,
        client: FakeYahooFinanceClient,
    ) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = client,
        nowProvider = { 1_700_000_000L },
        ioDispatcher = dispatcher,
    )

    private suspend fun awaitSnapshot(
        repository: DefaultDashboardRepository,
        selectedSymbol: String? = null,
        selectedRange: ChartRange = ChartRange.Year,
        timeoutMs: Long = 5_000,
        predicate: (com.discountscreener.android.domain.model.DashboardSnapshot) -> Boolean,
    ): com.discountscreener.android.domain.model.DashboardSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        var snapshot = repository.currentSnapshot(ViewFilter(), selectedSymbol, selectedRange, legacyModel)
        while (!predicate(snapshot)) {
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for repository snapshot to satisfy predicate; last snapshot=$snapshot")
            }
            Thread.sleep(10)
            dispatcher.scheduler.advanceUntilIdle()
            snapshot = repository.currentSnapshot(ViewFilter(), selectedSymbol, selectedRange, legacyModel)
        }
        return snapshot
    }

    private suspend fun seedWarmState(store: SQLiteStateStore) {
        store.persistBatch(
            rawCaptures = listOf(
                chartCapture("NVDA", 12_000),
                chartCapture("AAPL", 11_000),
                chartCapture("MSFT", 10_000),
            ),
            revisions = listOf(
                revision("NVDA", marketPriceCents = 10_000, intrinsicValueCents = 20_000, gapBps = 5_000),
                revision("AAPL", marketPriceCents = 10_000, intrinsicValueCents = 15_000, gapBps = 3_333),
                revision("MSFT", marketPriceCents = 10_000, intrinsicValueCents = 12_000, gapBps = 1_666),
            ),
        )
        store.replaceWatchlist(listOf("AAPL"))
    }

    private fun chartCapture(symbol: String, closeCents: Long) = RawCapture(
        symbol = symbol,
        captureKind = CaptureKind.ChartCandles,
        scopeKey = ChartRange.Year.name,
        capturedAt = 1_700_000_000L,
        payload = RawCapturePayload.Chart(
            range = ChartRange.Year,
            candles = listOf(
                HistoricalCandle(
                    epochSeconds = 1_699_999_000L,
                    openCents = closeCents - 100,
                    highCents = closeCents + 100,
                    lowCents = closeCents - 200,
                    closeCents = closeCents,
                    volume = 1_000,
                ),
            ),
        ),
    )

    private fun chartCapture(
        symbol: String,
        range: ChartRange,
        capturedAt: Long,
        candles: List<HistoricalCandle>,
    ) = RawCapture(
        symbol = symbol,
        captureKind = CaptureKind.ChartCandles,
        scopeKey = range.name,
        capturedAt = capturedAt,
        payload = RawCapturePayload.Chart(range = range, candles = candles),
    )

    private fun revision(
        symbol: String,
        marketPriceCents: Long,
        intrinsicValueCents: Long,
        gapBps: Int,
        weightedFairValueCents: Long? = intrinsicValueCents,
        evaluatedAt: Long = 1_700_000_000L,
    ) = SymbolRevisionInput(
        symbol = symbol,
        evaluatedAt = evaluatedAt,
        lastSequence = 1,
        updateCount = 1,
        priceHistory = listOf(PriceHistoryPoint(sequence = 1, marketPriceCents = marketPriceCents)),
        payload = EvaluatedSymbolState(
            snapshot = MarketSnapshot(
                symbol = symbol,
                companyName = companyNameFor(symbol),
                profitable = true,
                marketPriceCents = marketPriceCents,
                intrinsicValueCents = intrinsicValueCents,
            ),
            externalSignal = ExternalValuationSignal(
                symbol = symbol,
                fairValueCents = intrinsicValueCents,
                ageSeconds = 0,
                weightedFairValueCents = weightedFairValueCents,
                lowFairValueCents = weightedFairValueCents?.minus(1_500),
                highFairValueCents = weightedFairValueCents?.plus(1_500),
                weightedAnalystCount = weightedFairValueCents?.let { 18 },
            ),
            gapBps = gapBps,
            qualification = QualificationStatus.Qualified,
            externalStatus = ExternalSignalStatus.Supportive,
            coreStatus = MetricGroupStatus(available = true, stale = false),
            fundamentalsStatus = MetricGroupStatus(available = false, stale = false),
            relativeStatus = MetricGroupStatus(available = false, stale = false),
            dcfStatus = MetricGroupStatus(available = false, stale = false),
            chartStatus = MetricGroupStatus(available = true, stale = false),
            isWatched = symbol == "AAPL",
        ),
    )

    private fun fetchResult(
        symbol: String,
        marketPriceCents: Long,
        intrinsicValueCents: Long,
        weightedFairValueCents: Long,
    ) = ProviderFetchResult(
        symbol = symbol,
        snapshot = MarketSnapshot(
            symbol = symbol,
            companyName = companyNameFor(symbol),
            profitable = true,
            marketPriceCents = marketPriceCents,
            intrinsicValueCents = intrinsicValueCents,
        ),
        externalSignal = ExternalValuationSignal(
            symbol = symbol,
            fairValueCents = intrinsicValueCents,
            ageSeconds = 0,
            lowFairValueCents = weightedFairValueCents - 1_500,
            highFairValueCents = weightedFairValueCents + 1_500,
            weightedFairValueCents = weightedFairValueCents,
            weightedAnalystCount = 20,
        ),
        fundamentals = null,
        coverage = ProviderCoverage(
            core = ProviderComponentState.Fresh,
            external = ProviderComponentState.Fresh,
            fundamentals = ProviderComponentState.Missing,
        ),
        diagnostics = emptyList(),
    )

    @Test
    fun enrichment_populates_all_chart_ranges_after_refresh() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = FakeYahooFinanceClient(delayMs = 5)
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)
            val snapshot = awaitSnapshot(repository) { refreshed ->
                refreshed.trackedRows.any { it.state == TrackedRowState.Live }
            }
            val symbol = snapshot.trackedSymbols.first()
            awaitSnapshot(repository, selectedSymbol = symbol) { enriched ->
                ChartRange.entries.all { range ->
                    enriched.selectedCharts[range].orEmpty().isNotEmpty()
                }
            }
            val allCharts = ChartRange.entries.associateWith { range ->
                repository.currentSnapshot(ViewFilter(), symbol, range, legacyModel).selectedCharts[range].orEmpty()
            }
            assertTrue(
                "Expected all chart ranges populated after enrichment, but got: ${allCharts.map { (k, v) -> "$k=${v.size}" }}",
                allCharts.all { (_, candles) -> candles.isNotEmpty() }
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun enrichment_records_issues_for_failed_fetches_without_retry() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            var fetchCount = 0
            val failingClient = object : FakeYahooFinanceClient(delayMs = 5) {
                override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                    fetchCount++
                    if (range != ChartRange.Year) {
                        throw java.io.IOException("enrichment chart $range failed for $symbol")
                    }
                    return super.fetchHistoricalCandles(symbol, range)
                }
            }
            val repository = buildRepository(store = store, client = failingClient)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)
            val snapshot = awaitSnapshot(repository) { current ->
                current.issues.any { it.key.contains("enrichment") }
            }
            val enrichmentIssues = snapshot.issues.filter { it.key.contains("enrichment") }
            assertTrue("Expected enrichment issues for failed chart fetches", enrichmentIssues.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun ensure_detail_is_instant_after_enrichment() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            var enrichmentFetchRequestCount = 0
            val countingClient = object : FakeYahooFinanceClient(delayMs = 5) {
                override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
                    enrichmentFetchRequestCount++
                    return super.fetchHistoricalCandles(symbol, range)
                }
            }
            val repository = buildRepository(store = store, client = countingClient)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)
            val switched = awaitSnapshot(repository) { current ->
                current.trackedRows.any { it.state == TrackedRowState.Live }
            }
            val symbol = switched.trackedSymbols.first()
            awaitSnapshot(repository, selectedSymbol = symbol) { enriched ->
                ChartRange.entries.all { range ->
                    enriched.selectedCharts[range].orEmpty().isNotEmpty()
                }
            }

            val afterEnrichment = enrichmentFetchRequestCount

            repository.ensureDetailLoaded(symbol, ViewFilter(), ChartRange.Year, legacyModel)
            advanceUntilIdle()

            val afterDetail = enrichmentFetchRequestCount
            assertEquals(
                "ensureDetailLoaded should not make additional network calls after enrichment",
                afterEnrichment,
                afterDetail
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun fallback_snapshot_uses_latest_chart_close_when_quote_html_is_missing() {
        val store = SQLiteStateStore(context)
        try {
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())

            val fallback = repository.fallbackSnapshotFromCachedDetail(
                symbol = "PODD",
                detail = sampleDetail(
                    symbol = "PODD",
                    marketPriceCents = 9_500L,
                    intrinsicValueCents = 18_500L,
                ),
                chartCandles = listOf(
                    HistoricalCandle(
                        epochSeconds = 1_700_000_000L,
                        openCents = 10_000L,
                        highCents = 10_400L,
                        lowCents = 9_800L,
                        closeCents = 10_250L,
                        volume = 1_000L,
                    ),
                ),
            )

            assertEquals(10_250L, fallback?.marketPriceCents)
            assertEquals(18_500L, fallback?.intrinsicValueCents)
            assertEquals("PODD", fallback?.symbol)
        } finally {
            store.close()
        }
    }

    @Test
    fun quote_html_404_is_suppressible_only_for_quote_page_errors() {
        val store = SQLiteStateStore(context)
        try {
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())

            assertTrue(
                repository.isSuppressibleQuoteHtml404(
                    ProviderDiagnostic(
                        component = "quoteHtml",
                        kind = "error",
                        detail = "HTTP 404 for https://finance.yahoo.com/quote/PODD",
                        retryable = false,
                    ),
                ),
            )
            assertFalse(
                repository.isSuppressibleQuoteHtml404(
                    ProviderDiagnostic(
                        component = "chart",
                        kind = "error",
                        detail = "HTTP 404 for https://query1.finance.yahoo.com/v8/finance/chart/PODD",
                        retryable = false,
                    ),
                ),
            )
        } finally {
            store.close()
        }
    }

    private open class FakeYahooFinanceClient(
        private val delayMs: Long = 0,
    ) : YahooFinanceClient() {
        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            delay(delayMs)
            val price = 10_000L + symbol.sumOf { it.code }.toLong()
            val fair = price + 2_500L
            return ProviderFetchResult(
                symbol = symbol,
                snapshot = MarketSnapshot(
                    symbol = symbol,
                    companyName = companyNameFor(symbol),
                    profitable = true,
                    marketPriceCents = price,
                    intrinsicValueCents = fair,
                ),
                externalSignal = ExternalValuationSignal(
                    symbol = symbol,
                    fairValueCents = fair,
                    ageSeconds = 0,
                ),
                fundamentals = null,
                coverage = ProviderCoverage(
                    core = ProviderComponentState.Fresh,
                    external = ProviderComponentState.Fresh,
                    fundamentals = ProviderComponentState.Missing,
                ),
                diagnostics = emptyList(),
            )
        }

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
            delay(delayMs)
            val close = 10_000L + symbol.length * 100L
            return listOf(
                HistoricalCandle(
                    epochSeconds = 1_699_999_000L,
                    openCents = close - 50,
                    highCents = close + 50,
                    lowCents = close - 75,
                    closeCents = close,
                    volume = 1_000,
                ),
            )
        }

        override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
            delay(delayMs)
            return FundamentalTimeseries(
                freeCashFlow = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 50_000_000_000.0)),
                operatingCashFlow = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 60_000_000_000.0)),
                capitalExpenditure = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 10_000_000_000.0)),
                dilutedAverageShares = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 5_000_000_000.0)),
                interestExpense = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 1_000_000_000.0)),
                pretaxIncome = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 20_000_000_000.0)),
                taxRateForCalcs = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 0.21)),
                netIncome = listOf(com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 15_000_000_000.0)),
            )
        }
    }

    companion object {
        private const val DB_NAME = "discount_screener_state.sqlite3"
    }
}

private fun sampleDetail(
    symbol: String,
    marketPriceCents: Long,
    intrinsicValueCents: Long,
) = SymbolDetail(
    symbol = symbol,
    profitable = true,
    marketPriceCents = marketPriceCents,
    intrinsicValueCents = intrinsicValueCents,
    gapBps = 5_000,
    minimumGapBps = 1_500,
    qualification = QualificationStatus.Qualified,
    externalStatus = ExternalSignalStatus.Supportive,
    externalSignalMaxAgeSeconds = 86_400L,
    confidence = ConfidenceBand.High,
    lastSequence = 1,
    updateCount = 1,
    isWatched = false,
    companyName = "$symbol Inc.",
)

private fun sampleRevision(
    weightedFairValueCents: Long?,
    evaluatedAtEpochSeconds: Long,
    symbol: String = "NVDA",
) = SymbolRevision(
    symbol = symbol,
    evaluatedAtEpochSeconds = evaluatedAtEpochSeconds,
    detail = sampleDetail(
        symbol = symbol,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 18_000L,
    ).copy(
        externalSignalFairValueCents = weightedFairValueCents,
        externalSignalLowFairValueCents = weightedFairValueCents?.minus(1_500L),
        externalSignalHighFairValueCents = weightedFairValueCents?.plus(1_500L),
        weightedExternalSignalFairValueCents = weightedFairValueCents,
        weightedAnalystCount = weightedFairValueCents?.let { 18 },
    ),
)

private fun candle(
    epochSeconds: Long,
    closeCents: Long,
) = HistoricalCandle(
    epochSeconds = epochSeconds,
    openCents = closeCents - 100,
    highCents = closeCents + 100,
    lowCents = closeCents - 200,
    closeCents = closeCents,
    volume = 1_000L,
)

private fun companyNameFor(symbol: String): String = when (symbol) {
    "NVDA" -> "NVIDIA Corporation"
    "AAPL" -> "Apple Inc."
    "MSFT" -> "Microsoft Corporation"
    else -> "$symbol Holdings"
}

private fun richTimeseries() = FundamentalTimeseries(
    freeCashFlow = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 30_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 34_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 40_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 46_000_000_000.0),
    ),
    operatingCashFlow = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 70_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 75_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 82_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 90_000_000_000.0),
    ),
    capitalExpenditure = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 10_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 11_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 12_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 13_000_000_000.0),
    ),
    dilutedAverageShares = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 5_100_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 5_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 4_900_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 4_800_000_000.0),
    ),
    interestExpense = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 1_200_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 1_100_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 1_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 900_000_000.0),
    ),
    pretaxIncome = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 60_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 65_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 72_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 80_000_000_000.0),
    ),
    taxRateForCalcs = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 0.21),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 0.21),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 0.21),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 0.21),
    ),
    netIncome = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 48_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 52_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 58_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2023-01-01", 64_000_000_000.0),
    ),
)
