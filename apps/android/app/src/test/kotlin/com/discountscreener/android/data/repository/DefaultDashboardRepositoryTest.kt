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
import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.YahooSearchQuote
import com.discountscreener.android.domain.model.ChangeDirection
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowExplanationKind
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.android.domain.model.ValuationChangeTier
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.PriceHistoryPoint
import com.discountscreener.core.model.ProjectedConfidence
import com.discountscreener.core.model.ProjectedOpportunityRow
import com.discountscreener.core.model.ProjectedProviderCategory
import com.discountscreener.core.model.ProjectedTrackedRow
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.model.QuantLensRowSummary
import com.discountscreener.core.model.ResolverState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
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
    fun bootstrap_filters_projected_tracked_rows_by_query() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            seedWarmState(store)
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            val snapshot = repository.bootstrap(ViewFilter(query = "MSFT"), null, ChartRange.Year, legacyModel)

            assertEquals(listOf("MSFT"), snapshot.trackedRows.map { it.symbol })
        } finally {
            store.close()
        }
    }

    @Test
    fun bootstrap_row_quant_lens_summary_uses_projected_row_summary() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            seedWarmState(store)
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            val snapshot = repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)

            assertEquals(
                QuantLensProjectionParityExpectation(
                    tracked = snapshot.screenData.trackedRows.first { it.symbol == "NVDA" }.quantLensSummary,
                    opportunity = snapshot.screenData.opportunityRows.first { it.symbol == "NVDA" }.quantLensSummary,
                ),
                QuantLensProjectionParityExpectation(
                    tracked = snapshot.trackedRows.first { it.symbol == "NVDA" }.quantLensSummary,
                    opportunity = snapshot.opportunityRows.first { it.symbol == "NVDA" }.quantLensSummary,
                ),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun bootstrap_provider_uncertain_quant_lens_summary_uses_projected_row_summary() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            seedWarmState(
                store,
                nvdaDcfAnalysis = providerUncertainDcfAnalysis(),
                nvdaHasExternalSignal = false,
            )
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            val snapshot = repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)

            assertEquals(
                snapshot.screenData.trackedRows.first { it.symbol == "NVDA" }.quantLensSummary,
                snapshot.trackedRows.first { it.symbol == "NVDA" }.quantLensSummary,
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun bootstrap_populates_projected_screen_data_for_warm_rows() = runTest(dispatcher) {
        var store = SQLiteStateStore(context)
        try {
            seedWarmState(store)
            var repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            var snapshot = repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)

            assertEquals(listOf("NVDA", "AAPL", "MSFT"), snapshot.screenData.trackedRows.take(3).map { it.symbol })
        } finally {
            store.close()
        }
    }

    @Test
    fun bootstrap_legacy_tracked_row_semantics_mirror_projected_screen_data() = runTest(dispatcher) {
        var store = SQLiteStateStore(context)
        try {
            seedWarmState(store)
            var repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            var snapshot = repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            var legacyRow = snapshot.trackedRows.first { it.symbol == "NVDA" }
            var projectedRow = snapshot.screenData.trackedRows.first { it.symbol == "NVDA" }

            assertEquals(projectedTrackedSemantics(projectedRow), legacyTrackedSemantics(legacyRow))
        } finally {
            store.close()
        }
    }

    @Test
    fun bootstrap_legacy_opportunity_row_semantics_mirror_projected_screen_data() = runTest(dispatcher) {
        var store = SQLiteStateStore(context)
        try {
            seedWarmState(store)
            var repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            var snapshot = repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            var projectedRow = snapshot.screenData.opportunityRows.first { it.symbol == "NVDA" }
            var legacyRow = snapshot.opportunityRows.first { it.symbol == "NVDA" }

            assertEquals(projectedOpportunitySemantics(projectedRow), legacyOpportunitySemantics(legacyRow))
        } finally {
            store.close()
        }
    }

    @Test
    fun bootstrap_source_free_dcf_projection_is_visible_in_legacy_row() = runTest(dispatcher) {
        var store = SQLiteStateStore(context)
        try {
            seedWarmState(store, nvdaDcfAnalysis = sourceFreeDcfAnalysis(), nvdaHasExternalSignal = false)
            var repository = buildRepository(store = store, client = FakeYahooFinanceClient())
            var snapshot = repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            var row = snapshot.trackedRows.first { it.symbol == "NVDA" }

            assertEquals(
                SourceFreeProjectionExpectation(
                    providerCategory = ProjectedProviderCategory.SourceUnknown,
                    trustNote = "Source unknown",
                    confidence = ConfidenceBand.Low,
                    freshness = RowFreshness.Restored,
                ),
                SourceFreeProjectionExpectation(
                    providerCategory = snapshot.screenData.providerState.category,
                    trustNote = row.trustNote,
                    confidence = row.confidence,
                    freshness = row.freshness,
                ),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun ticker_switching_with_reversed_analyst_range_keeps_detail_snapshots_alive() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            seedWarmState(
                store = store,
                nvdaWeightedFairValueCents = 20_000L,
                nvdaAnalystLowFairValueCents = 23_000L,
                nvdaAnalystHighFairValueCents = 17_000L,
            )
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())

            val initial = repository.bootstrap(ViewFilter(), "AAPL", ChartRange.Year, legacyModel)
            val malformed = repository.ensureDetailLoaded("NVDA", ViewFilter(), ChartRange.Year, legacyModel)
            val recovered = repository.ensureDetailLoaded("AAPL", ViewFilter(), ChartRange.Year, legacyModel)

            assertEquals(listOf("AAPL", "NVDA", "AAPL"), listOf(initial.selectedDetail?.symbol, malformed.selectedDetail?.symbol, recovered.selectedDetail?.symbol))
            assertNull(malformed.detailNotice)
            assertNotNull(malformed.selectedQuantLens)
            assertEquals(20_000L, malformed.selectedQuantLens?.expectedValueRange?.weightedFairValueCents)
            assertNull(recovered.detailNotice)
        } finally {
            store.close()
        }
    }

    @Test
    fun ticker_switching_with_extreme_valuation_upside_keeps_quant_lens_non_fatal() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            store.persistBatch(
                rawCaptures = listOf(
                    chartCapture("NVDA", 12_000),
                    chartCapture("AAPL", 11_000),
                    chartCapture("MSFT", 10_000),
                ),
                revisions = listOf(
                    revision(
                        "NVDA",
                        marketPriceCents = 10_000,
                        intrinsicValueCents = 250_000,
                        gapBps = 240_000,
                        weightedFairValueCents = 250_000,
                    ),
                    revision("AAPL", marketPriceCents = 10_000, intrinsicValueCents = 15_000, gapBps = 3_333),
                    revision("MSFT", marketPriceCents = 10_000, intrinsicValueCents = 12_000, gapBps = 1_666),
                ),
            )
            store.replaceWatchlist(listOf("AAPL"))
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())

            val initial = repository.bootstrap(ViewFilter(), "AAPL", ChartRange.Year, legacyModel)
            val extreme = repository.ensureDetailLoaded("NVDA", ViewFilter(), ChartRange.Year, legacyModel)
            val recovered = repository.ensureDetailLoaded("AAPL", ViewFilter(), ChartRange.Year, legacyModel)

            assertEquals(listOf("AAPL", "NVDA", "AAPL"), listOf(initial.selectedDetail?.symbol, extreme.selectedDetail?.symbol, recovered.selectedDetail?.symbol))
            assertNull(extreme.detailNotice)
            assertNotNull(extreme.selectedQuantLens)
            assertEquals(250_000L, extreme.selectedQuantLens?.expectedValueRange?.weightedFairValueCents)
            assertNull(recovered.detailNotice)
        } finally {
            store.close()
        }
    }

    @Test
    fun quant_lens_candle_fingerprint_is_deterministic_for_identical_large_series() {
        var candles = largeQuantLensCandleSeries()

        assertEquals(
            quantLensCandleFingerprint(candles),
            quantLensCandleFingerprint(candles.asReversed()),
        )
    }

    @Test
    fun quant_lens_candle_fingerprint_is_stable_when_duplicate_entries_are_reordered() {
        var original = baseQuantLensFingerprintCandle()
        var duplicate = original.copy(
            openCents = 9_000L,
            highCents = 9_500L,
            lowCents = 8_800L,
            closeCents = 9_250L,
            volume = 100_000L,
        )
        var next = original.copy(epochSeconds = original.epochSeconds + 86_400L)
        var canonicalFingerprint = quantLensCandleFingerprint(listOf(duplicate, next))

        assertEquals(
            listOf(canonicalFingerprint, canonicalFingerprint),
            listOf(
                quantLensCandleFingerprint(listOf(original, duplicate, next)),
                quantLensCandleFingerprint(listOf(duplicate, original, next)),
            ),
        )
    }

    @Test
    fun quant_lens_candle_fingerprint_changes_when_epoch_changes() {
        assertCandleFingerprintChanges(baseQuantLensFingerprintCandle().copy(epochSeconds = 1_700_086_400L))
    }

    @Test
    fun quant_lens_candle_fingerprint_changes_when_open_changes() {
        assertCandleFingerprintChanges(baseQuantLensFingerprintCandle().copy(openCents = 10_001L))
    }

    @Test
    fun quant_lens_candle_fingerprint_changes_when_high_changes() {
        assertCandleFingerprintChanges(baseQuantLensFingerprintCandle().copy(highCents = 10_501L))
    }

    @Test
    fun quant_lens_candle_fingerprint_changes_when_low_changes() {
        assertCandleFingerprintChanges(baseQuantLensFingerprintCandle().copy(lowCents = 9_801L))
    }

    @Test
    fun quant_lens_candle_fingerprint_changes_when_close_changes() {
        assertCandleFingerprintChanges(baseQuantLensFingerprintCandle().copy(closeCents = 10_251L))
    }

    @Test
    fun quant_lens_candle_fingerprint_changes_when_volume_changes() {
        assertCandleFingerprintChanges(baseQuantLensFingerprintCandle().copy(volume = 123_457L))
    }

    @Test
    fun quant_lens_candle_fingerprint_handles_large_series_with_bounded_output() {
        assertTrue(quantLensCandleFingerprint(largeQuantLensCandleSeries()).length <= 32)
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
    fun refresh_stable_sorted_rank_does_not_report_relative_rerank() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            seedWarmState(store)
            val client = object : FakeYahooFinanceClient(delayMs = 5) {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    delay(5)
                    return when (symbol) {
                        "NVDA" -> fetchResult(symbol, marketPriceCents = 10_000, intrinsicValueCents = 20_000, weightedFairValueCents = 20_000)
                        "AAPL" -> fetchResult(symbol, marketPriceCents = 10_000, intrinsicValueCents = 15_000, weightedFairValueCents = 15_000)
                        "MSFT" -> fetchResult(symbol, marketPriceCents = 10_000, intrinsicValueCents = 12_000, weightedFairValueCents = 12_000)
                        else -> super.fetchSymbol(symbol)
                    }
                }
            }
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, legacyModel)
            val snapshot = awaitSnapshot(repository) { current ->
                listOf("NVDA", "AAPL", "MSFT").all { symbol ->
                    current.trackedRows.any { it.symbol == symbol && it.state == TrackedRowState.Live }
                }
            }

            assertEquals(RowExplanationKind.NoMeaningfulChange, snapshot.trackedRows.first { it.symbol == "NVDA" }.explanation)
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
            candle(epochSeconds = 1_704_672_000, closeCents = 2_000),
            candle(epochSeconds = 1_704_067_200, closeCents = 1_000),
        )
        val incoming = listOf(
            candle(epochSeconds = 1_705_276_800, closeCents = 3_000),
            candle(epochSeconds = 1_704_715_200, closeCents = 9_999),
        )

        val merged = mergeHistoricalCandles(
            symbol = "NVDA",
            range = ChartRange.Year,
            persistedCandles = existing,
            incomingCandles = incoming,
        )

        assertEquals(listOf(1_704_067_200L, 1_704_715_200L, 1_705_276_800L), merged.map { it.epochSeconds })
        assertEquals(9_999L, merged.first { it.epochSeconds == 1_704_715_200L }.closeCents)
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
                            candle(epochSeconds = 1_704_110_400, closeCents = 2_000),
                            candle(epochSeconds = 1_704_672_000, closeCents = 1_000),
                        ),
                    ),
                    chartCapture(
                        symbol = "NVDA",
                        range = ChartRange.Year,
                        capturedAt = 200,
                        candles = listOf(
                            candle(epochSeconds = 1_704_067_200, closeCents = 9_999),
                            candle(epochSeconds = 1_705_276_800, closeCents = 3_000),
                        ),
                    ),
                ),
                revisions = emptyList(),
            )

            val chart = store.loadPricingHistory("NVDA").single {
                it.symbol == "NVDA" && it.range == ChartRange.Year
            }

            assertEquals(listOf(1_704_067_200L, 1_704_672_000L, 1_705_276_800L), chart.candles.map { it.epochSeconds })
            assertEquals(9_999L, chart.candles.first { it.epochSeconds == 1_704_067_200L }.closeCents)
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
    fun row_trust_note_surfaces_missing_or_thin_analyst_target_coverage() {
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
        assertEquals(
            "Unknown analyst coverage",
            rowTrustNote(
                detail = sampleDetail(
                    symbol = "NVDA",
                    marketPriceCents = 10_000L,
                    intrinsicValueCents = 18_000L,
                ).copy(
                    externalSignalFairValueCents = 18_300L,
                    analystOpinionCount = null,
                ),
                issueMessage = null,
            ),
        )
        assertEquals(
            "Thin analyst coverage",
            rowTrustNote(
                detail = sampleDetail(
                    symbol = "NVDA",
                    marketPriceCents = 10_000L,
                    intrinsicValueCents = 18_000L,
                ).copy(
                    externalSignalFairValueCents = 18_300L,
                    analystOpinionCount = 1,
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
    fun bounded_quant_lens_row_upside_clamps_extreme_positive_ev_anchor() {
        assertEquals(100_000, boundedQuantLensRowUpsideBps(marketPriceCents = 1L, fairValueCents = 10_001L))
    }

    @Test
    fun bounded_quant_lens_row_upside_preserves_normal_ev_anchor() {
        assertEquals(2_500, boundedQuantLensRowUpsideBps(marketPriceCents = 10_000L, fairValueCents = 12_500L))
    }

    @Test
    fun quant_lens_revision_fingerprint_changes_when_target_content_changes() {
        assertNotEquals(
            quantLensRevisionFingerprint(listOf(sampleRevision(weightedFairValueCents = 19_500L, evaluatedAtEpochSeconds = 1L))),
            quantLensRevisionFingerprint(listOf(sampleRevision(weightedFairValueCents = 24_000L, evaluatedAtEpochSeconds = 1L))),
        )
    }

    @Test
    fun quant_lens_ev_spread_uses_loaded_analyst_anchors() {
        assertEquals(
            2_500,
            quantLensEvSpreadBps(
                sampleDetail("NVDA", marketPriceCents = 10_000L, intrinsicValueCents = 12_000L).copy(
                    externalSignalLowFairValueCents = 10_000L,
                    weightedExternalSignalFairValueCents = 11_000L,
                    externalSignalHighFairValueCents = 12_500L,
                ),
                dcfAnalysis = null,
            ),
        )
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

    @Test
    fun enrichment_uses_yahoo_when_secondary_provider_is_not_configured() = runTest(dispatcher) {
        var store = SQLiteStateStore(context)
        try {
            var client = object : FakeYahooFinanceClient() {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    return super.fetchSymbol(symbol).copy(
                        fundamentals = dcfFundamentals(symbol),
                        coverage = ProviderCoverage(
                            core = ProviderComponentState.Fresh,
                            external = ProviderComponentState.Fresh,
                            fundamentals = ProviderComponentState.Fresh,
                        ),
                    )
                }

                override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                    return if (symbol == "AAPL") richTimeseries() else super.fetchFundamentalTimeseries(symbol)
                }
            }
            var repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)

            assertEquals(DcfSource.YahooFinance, awaitDcfSource(repository, "AAPL"))
        } finally {
            store.close()
        }
    }

    @Test
    fun refresh_recomputes_cached_dcf() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            var refreshedFundamentals = dcfFundamentals("AAPL")
            var aaplTimeseriesFetches = 0
            var omitAaplSnapshot = false
            val client = object : FakeYahooFinanceClient() {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    val response = super.fetchSymbol(symbol)
                    return response.copy(
                        snapshot = if (symbol == "AAPL" && omitAaplSnapshot) null else response.snapshot,
                        fundamentals = if (symbol == "AAPL") refreshedFundamentals else null,
                        coverage = ProviderCoverage(
                            core = if (symbol == "AAPL" && omitAaplSnapshot) ProviderComponentState.Missing else ProviderComponentState.Fresh,
                            external = ProviderComponentState.Fresh,
                            fundamentals = if (symbol == "AAPL") ProviderComponentState.Fresh else ProviderComponentState.Missing,
                        ),
                    )
                }

                override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                    if (symbol == "AAPL") {
                        aaplTimeseriesFetches += 1
                        return richTimeseries()
                    }
                    return super.fetchFundamentalTimeseries(symbol)
                }
            }
            val repository = buildRepository(store = store, client = client)

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)
            awaitDcfSource(repository, "AAPL")
            val before = repository.dcfSnapshot().getValue("AAPL")
            val fetchesBeforeRefresh = aaplTimeseriesFetches

            refreshedFundamentals = dcfFundamentals("AAPL").copy(
                betaMillis = 2_000,
                totalDebtDollars = 300_000_000_000L,
                totalCashDollars = 0L,
                sharesOutstanding = 4_000_000_000L,
            )
            omitAaplSnapshot = true
            repository.refreshAll(ViewFilter(), null, ChartRange.Year, legacyModel)
            val after = awaitDcfAnalysis(repository, "AAPL") { analysis ->
                analysis.waccBps != before.waccBps && analysis.baseIntrinsicValueCents != before.baseIntrinsicValueCents
            }

            assertEquals(before.source, after.source)
            assertEquals(before.sourceFingerprint, after.sourceFingerprint)
            assertEquals(before.decisionFingerprint, after.decisionFingerprint)
            assertEquals(before.provenance, after.provenance)
            assertEquals(before.providerReasons, after.providerReasons)
            assertEquals(fetchesBeforeRefresh, aaplTimeseriesFetches)
        } finally {
            store.close()
        }
    }

    @Test
    fun refresh_fallback_uses_sec_when_yahoo_timeseries_is_not_dcf_usable() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = object : FakeYahooFinanceClient() {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    if (symbol == "AAPL") {
                        return ProviderFetchResult(
                            symbol = symbol,
                            snapshot = null,
                            externalSignal = null,
                            fundamentals = dcfFundamentals(symbol),
                            coverage = ProviderCoverage(
                                core = ProviderComponentState.Missing,
                                external = ProviderComponentState.Missing,
                                fundamentals = ProviderComponentState.Fresh,
                            ),
                            diagnostics = emptyList(),
                        )
                    }
                    return super.fetchSymbol(symbol)
                }

                override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                    return if (symbol == "AAPL") unusableTimeseries() else super.fetchFundamentalTimeseries(symbol)
                }
            }
            val repository = buildRepository(
                store = store,
                client = client,
                secondaryTimeseriesProvider = FakeTimeseriesProvider(richTimeseries()),
            )

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)

            assertEquals(DcfSource.SecEdgar, awaitDcfSource(repository, "AAPL"))
        } finally {
            store.close()
        }
    }

    @Test
    fun detail_load_uses_sec_when_yahoo_timeseries_is_not_dcf_usable() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            store.persistBatch(
                rawCaptures = emptyList(),
                revisions = listOf(
                    revision(
                        symbol = "AAPL",
                        marketPriceCents = 10_000,
                        intrinsicValueCents = 15_000,
                        gapBps = 3_333,
                        fundamentals = dcfFundamentals("AAPL"),
                    ),
                ),
            )
            val client = object : FakeYahooFinanceClient() {
                override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                    return if (symbol == "AAPL") unusableTimeseries() else super.fetchFundamentalTimeseries(symbol)
                }
            }
            val repository = buildRepository(
                store = store,
                client = client,
                secondaryTimeseriesProvider = FakeTimeseriesProvider(richTimeseries()),
            )

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.ensureDetailLoaded("AAPL", ViewFilter(), ChartRange.Year, legacyModel)

            assertEquals(DcfSource.SecEdgar, repository.dcfSnapshot()["AAPL"]?.source)
        } finally {
            store.close()
        }
    }

    @Test
    fun enrichment_uses_sec_when_yahoo_timeseries_is_not_dcf_usable() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = object : FakeYahooFinanceClient() {
                override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
                    return super.fetchSymbol(symbol).copy(
                        fundamentals = dcfFundamentals(symbol),
                        coverage = ProviderCoverage(
                            core = ProviderComponentState.Fresh,
                            external = ProviderComponentState.Fresh,
                            fundamentals = ProviderComponentState.Fresh,
                        ),
                    )
                }

                override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
                    return if (symbol == "AAPL") unusableTimeseries() else super.fetchFundamentalTimeseries(symbol)
                }
            }
            val repository = buildRepository(
                store = store,
                client = client,
                secondaryTimeseriesProvider = FakeTimeseriesProvider(richTimeseries()),
            )

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("dow", ViewFilter(), ChartRange.Year, legacyModel)

            assertEquals(DcfSource.SecEdgar, awaitDcfSource(repository, "AAPL"))
        } finally {
            store.close()
        }
    }

    private fun buildRepository(
        store: SQLiteStateStore,
        client: FakeYahooFinanceClient,
        secondaryTimeseriesProvider: FundamentalTimeseriesProvider? = null,
    ) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = client,
        secondaryTimeseriesProvider = secondaryTimeseriesProvider,
        nowProvider = { 1_700_000_000L },
        ioDispatcher = dispatcher,
    )

    private fun assertCandleFingerprintChanges(mutated: HistoricalCandle) {
        var original = baseQuantLensFingerprintCandle()

        assertNotEquals(
            quantLensCandleFingerprint(listOf(original)),
            quantLensCandleFingerprint(listOf(mutated)),
        )
    }

    private fun projectedTrackedSemantics(row: ProjectedTrackedRow) = RowProjectionExpectation(
        fairValueCents = row.fairValueAnchor.valueCents,
        upsideBps = row.upsideBps,
        confidence = row.confidence.toLegacyConfidenceName(),
        trustNote = row.trustSignal?.label,
        decision = row.decision?.name,
    )

    private fun legacyTrackedSemantics(row: TrackedSymbolRow) = RowProjectionExpectation(
        fairValueCents = row.intrinsicValueCents,
        upsideBps = row.upsideBps,
        confidence = row.confidence?.name,
        trustNote = row.trustNote,
        decision = row.decisionState?.name,
    )

    private fun projectedOpportunitySemantics(row: ProjectedOpportunityRow) = RowProjectionExpectation(
        fairValueCents = row.fairValueAnchor.valueCents,
        upsideBps = row.upsideBps,
        confidence = row.confidence.toLegacyConfidenceName(),
        trustNote = row.trustSignal?.label,
        decision = row.decision?.name,
    )

    private fun legacyOpportunitySemantics(row: OpportunityListRow) = RowProjectionExpectation(
        fairValueCents = row.intrinsicValueCents,
        upsideBps = row.upsideBps,
        confidence = row.confidence.name,
        trustNote = row.trustNote,
        decision = row.decisionState?.name,
    )

    private fun ProjectedConfidence.toLegacyConfidenceName(): String? = when (this) {
        ProjectedConfidence.Unavailable -> null
        ProjectedConfidence.Low,
        ProjectedConfidence.Provisional,
        ProjectedConfidence.High -> name
    }

    private fun sourceFreeDcfAnalysis() = DcfAnalysis(
        bearIntrinsicValueCents = 10_000L,
        baseIntrinsicValueCents = 20_000L,
        bullIntrinsicValueCents = 24_000L,
        waccBps = 900,
        baseGrowthBps = 300,
        netDebtDollars = 0L,
    )

    private fun providerUncertainDcfAnalysis() = DcfAnalysis(
        bearIntrinsicValueCents = 0L,
        baseIntrinsicValueCents = 20_000L,
        bullIntrinsicValueCents = 0L,
        waccBps = 900,
        baseGrowthBps = 300,
        netDebtDollars = 0L,
        resolverState = ResolverState.ProviderUncertain,
    )

    private data class QuantLensProjectionParityExpectation(
        val tracked: QuantLensRowSummary?,
        val opportunity: QuantLensRowSummary?,
    )

    private data class RowProjectionExpectation(
        val fairValueCents: Long?,
        val upsideBps: Int?,
        val confidence: String?,
        val trustNote: String?,
        val decision: String?,
    )

    private data class SourceFreeProjectionExpectation(
        val providerCategory: ProjectedProviderCategory,
        val trustNote: String?,
        val confidence: ConfidenceBand?,
        val freshness: RowFreshness,
    )

    private fun baseQuantLensFingerprintCandle() = HistoricalCandle(
        epochSeconds = 1_700_000_000L,
        openCents = 10_000L,
        highCents = 10_500L,
        lowCents = 9_800L,
        closeCents = 10_250L,
        volume = 123_456L,
    )

    private fun largeQuantLensCandleSeries(count: Int = 100_000): List<HistoricalCandle> = List(count) { index ->
        HistoricalCandle(
            epochSeconds = 1_600_000_000L + index.toLong() * 86_400L,
            openCents = 10_000L + (index % 500),
            highCents = 10_050L + (index % 500),
            lowCents = 9_950L + (index % 500),
            closeCents = 10_025L + (index % 500),
            volume = 1_000_000L + index.toLong(),
        )
    }

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

    private suspend fun awaitDcfSource(
        repository: DefaultDashboardRepository,
        symbol: String,
        timeoutMs: Long = 5_000,
    ): DcfSource? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var source = repository.dcfSnapshot()[symbol]?.source
        while (source == null) {
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for $symbol DCF source; last snapshot=${repository.dcfSnapshot()}")
            }
            Thread.sleep(10)
            dispatcher.scheduler.advanceUntilIdle()
            source = repository.dcfSnapshot()[symbol]?.source
        }
        return source
    }

    private suspend fun awaitDcfAnalysis(
        repository: DefaultDashboardRepository,
        symbol: String,
        timeoutMs: Long = 5_000,
        predicate: (DcfAnalysis) -> Boolean,
    ): DcfAnalysis {
        val deadline = System.currentTimeMillis() + timeoutMs
        var analysis = repository.dcfSnapshot()[symbol]
        while (analysis == null || !predicate(analysis)) {
            if (System.currentTimeMillis() >= deadline) {
                fail("Timed out waiting for $symbol DCF analysis; last snapshot=${repository.dcfSnapshot()}")
            }
            Thread.sleep(10)
            dispatcher.scheduler.advanceUntilIdle()
            analysis = repository.dcfSnapshot()[symbol]
        }
        return analysis
    }

    private suspend fun seedWarmState(
        store: SQLiteStateStore,
        nvdaDcfAnalysis: DcfAnalysis? = null,
        nvdaHasExternalSignal: Boolean = true,
        nvdaWeightedFairValueCents: Long? = 20_000L,
        nvdaAnalystLowFairValueCents: Long? = nvdaWeightedFairValueCents?.minus(1_500L),
        nvdaAnalystHighFairValueCents: Long? = nvdaWeightedFairValueCents?.plus(1_500L),
    ) {
        store.persistBatch(
            rawCaptures = listOf(
                chartCapture("NVDA", 12_000),
                chartCapture("AAPL", 11_000),
                chartCapture("MSFT", 10_000),
            ),
            revisions = listOf(
                revision(
                    "NVDA",
                    marketPriceCents = 10_000,
                    intrinsicValueCents = 20_000,
                    gapBps = 5_000,
                    weightedFairValueCents = nvdaWeightedFairValueCents,
                    analystLowFairValueCents = nvdaAnalystLowFairValueCents,
                    analystHighFairValueCents = nvdaAnalystHighFairValueCents,
                    dcfAnalysis = nvdaDcfAnalysis,
                    hasExternalSignal = nvdaHasExternalSignal,
                ),
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
        analystLowFairValueCents: Long? = weightedFairValueCents?.minus(1_500L),
        analystHighFairValueCents: Long? = weightedFairValueCents?.plus(1_500L),
        evaluatedAt: Long = 1_700_000_000L,
        fundamentals: FundamentalSnapshot? = null,
        dcfAnalysis: DcfAnalysis? = null,
        hasExternalSignal: Boolean = true,
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
            externalSignal = if (hasExternalSignal) {
                ExternalValuationSignal(
                    symbol = symbol,
                    fairValueCents = intrinsicValueCents,
                    ageSeconds = 0,
                    weightedFairValueCents = weightedFairValueCents,
                    lowFairValueCents = analystLowFairValueCents,
                    highFairValueCents = analystHighFairValueCents,
                    weightedAnalystCount = weightedFairValueCents?.let { 18 },
                )
            } else {
                null
            },
            fundamentals = fundamentals,
            dcfAnalysis = dcfAnalysis,
            gapBps = gapBps,
            qualification = QualificationStatus.Qualified,
            externalStatus = if (hasExternalSignal) ExternalSignalStatus.Supportive else ExternalSignalStatus.Missing,
            coreStatus = MetricGroupStatus(available = true, stale = false),
            fundamentalsStatus = MetricGroupStatus(available = false, stale = false),
            relativeStatus = MetricGroupStatus(available = false, stale = false),
            dcfStatus = MetricGroupStatus(available = dcfAnalysis != null, stale = false),
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
    fun search_meli_returns_remote_suggestion() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = FakeYahooFinanceClient().apply {
                registerSearch(
                    "meli",
                    listOf(YahooSearchQuote("MELI", "MercadoLibre, Inc.", "NASDAQ", "EQUITY")),
                )
            }
            val repository = buildRepository(store = store, client = client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)

            val results = repository.searchTickers("MELI", "sp500")

            assertEquals("MELI", results.first().symbol)
            assertEquals("MercadoLibre, Inc.", results.first().companyName)
            assertTrue(results.first().isRemote)
            assertEquals(1, client.searchSymbolsCallCount)
        } finally {
            store.close()
        }
    }

    @Test
    fun search_mercado_returns_meli_from_remote_lookup() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = FakeYahooFinanceClient().apply {
                registerSearch(
                    "mercado",
                    listOf(
                        YahooSearchQuote("MELI", "MercadoLibre, Inc.", "NASDAQ", "EQUITY"),
                        YahooSearchQuote("MERC.CN", "Mercado Minerals Ltd.", "CNQ", "EQUITY"),
                    ),
                )
            }
            val repository = buildRepository(store = store, client = client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)

            val results = repository.searchTickers("mercado", "sp500")

            assertEquals("MELI", results.first().symbol)
            assertEquals(1, client.searchSymbolsCallCount)
        } finally {
            store.close()
        }
    }

    @Test
    fun search_ms_stays_local_without_remote_lookup() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val client = FakeYahooFinanceClient()
            val repository = buildRepository(store = store, client = client)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            repository.selectProfile("sp500", ViewFilter(), ChartRange.Year, legacyModel)

            val results = repository.searchTickers("MS", "sp500")

            assertTrue(results.isNotEmpty())
            assertTrue(results.all { "MS" in it.symbol })
            assertEquals(0, client.searchSymbolsCallCount)
        } finally {
            store.close()
        }
    }

    @Test
    fun ensure_detail_loaded_fetches_ad_hoc_symbol_without_tracking_it() = runTest(dispatcher) {
        val store = SQLiteStateStore(context)
        try {
            val repository = buildRepository(store = store, client = FakeYahooFinanceClient())

            repository.bootstrap(ViewFilter(), null, ChartRange.Year, legacyModel)
            val snapshot = repository.ensureDetailLoaded("SHOP", ViewFilter(), ChartRange.Year, legacyModel)

            assertEquals("SHOP", snapshot.selectedDetail?.symbol)
            assertTrue(snapshot.selectedCharts[ChartRange.Year].orEmpty().isNotEmpty())
            assertFalse(snapshot.trackedSymbols.contains("SHOP"))
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
        var searchSymbolsCallCount = 0
        private val searchResponses = linkedMapOf<String, List<YahooSearchQuote>>()

        fun registerSearch(query: String, quotes: List<YahooSearchQuote>) {
            searchResponses[query.trim().lowercase()] = quotes
        }

        override suspend fun searchSymbols(query: String, limit: Int): List<YahooSearchQuote> {
            searchSymbolsCallCount++
            return searchResponses[query.trim().lowercase()].orEmpty().take(limit)
        }

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
            delay(delayMs)
            val price = 10_000L + symbol.sumOf { it.code }.toLong()
            val fair = price + 2_500L
            val companyName = companyNameFor(symbol)
            return ProviderFetchResult(
                symbol = symbol,
                snapshot = MarketSnapshot(
                    symbol = symbol,
                    companyName = companyName,
                    profitable = true,
                    marketPriceCents = price,
                    intrinsicValueCents = fair,
                ),
                companyName = companyName,
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

    private class FakeTimeseriesProvider(
        private val timeseries: FundamentalTimeseries?,
    ) : FundamentalTimeseriesProvider {
        override suspend fun fetch(symbol: String): FundamentalTimeseries? = timeseries
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

private fun dcfFundamentals(symbol: String) = FundamentalSnapshot(
    symbol = symbol,
    marketCapDollars = 600_000_000_000L,
    sharesOutstanding = 4_800_000_000L,
    betaMillis = 1_000,
)

private fun unusableTimeseries() = FundamentalTimeseries(
    freeCashFlow = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 30_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 34_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", -1.0),
    ),
    dilutedAverageShares = listOf(
        com.discountscreener.core.model.AnnualReportedValue("2020-01-01", 5_100_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2021-01-01", 5_000_000_000.0),
        com.discountscreener.core.model.AnnualReportedValue("2022-01-01", 4_900_000_000.0),
    ),
)

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
