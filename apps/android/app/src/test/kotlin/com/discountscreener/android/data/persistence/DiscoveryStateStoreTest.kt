package com.discountscreener.android.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.domain.model.DiscoveryConfig
import com.discountscreener.android.domain.model.DiscoveryJobKind
import com.discountscreener.android.domain.model.DiscoveryJobStatus
import com.discountscreener.core.engine.DiscoveryScoreRow
import com.discountscreener.core.model.OpportunityScoringModel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscoveryStateStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun membership_merge_removes_adds_and_keeps_scores_for_intersection() = runTest {
        val store = SQLiteStateStore(context)
        try {
            store.applyDiscoveryMembershipMerge(listOf("AAPL", "IBM"), "us_total")
            store.upsertDiscoveryScores(
                listOf(
                    score("AAPL", 40),
                    score("IBM", 20),
                ),
            )

            val plan = store.applyDiscoveryMembershipMerge(listOf("AAPL", "MSFT"), "us_total")
            assertEquals(setOf("IBM"), plan.toRemove)
            assertEquals(setOf("MSFT"), plan.toAdd)
            assertEquals(setOf("AAPL"), plan.toKeep)

            assertEquals(listOf("AAPL", "MSFT"), store.loadDiscoverySymbols())
            val scores = store.queryDiscoveryScores(minScore = 0, limit = 50, offset = 0)
            assertEquals(listOf("AAPL"), scores.map { it.symbol })
            assertEquals(40, scores.single().compositeScore)
        } finally {
            store.close()
        }
    }

    @Test
    fun min_score_query_filters_without_changing_stored_rows() = runTest {
        val store = SQLiteStateStore(context)
        try {
            store.applyDiscoveryMembershipMerge(listOf("AAA", "BBB", "CCC"), "us_total")
            store.upsertDiscoveryScores(
                listOf(
                    score("AAA", 50),
                    score("BBB", 25),
                    score("CCC", 10),
                ),
            )

            assertEquals(listOf("AAA"), store.queryDiscoveryScores(minScore = 30, limit = 50).map { it.symbol })
            assertEquals(
                listOf("AAA", "BBB", "CCC"),
                store.queryDiscoveryScores(minScore = 0, limit = 50).map { it.symbol },
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun discovery_config_and_job_round_trip() = runTest {
        val store = SQLiteStateStore(context)
        try {
            store.saveDiscoveryConfig(
                DiscoveryConfig(
                    minScore = 12,
                    scoringModel = OpportunityScoringModel.AggressiveV3,
                    universeName = "us_total",
                ),
            )
            val config = store.loadDiscoveryConfig()
            assertEquals(12, config.minScore)
            assertEquals(OpportunityScoringModel.AggressiveV3, config.scoringModel)

            val jobId = store.createDiscoveryJob(DiscoveryJobKind.Refresh, totalSymbols = 3)
            store.updateDiscoveryJobProgress(jobId, completedSymbols = 2)
            store.finishDiscoveryJob(jobId, DiscoveryJobStatus.Completed, completedSymbols = 3)
            val job = store.loadLatestDiscoveryJob()
            assertEquals(DiscoveryJobKind.Refresh, job?.kind)
            assertEquals(DiscoveryJobStatus.Completed, job?.status)
            assertEquals(3, job?.completedSymbols)
            assertTrue(job?.finishedAtEpochSeconds != null)
        } finally {
            store.close()
        }
    }

    @Test
    fun company_name_literal_null_is_not_returned_or_stored() = runTest {
        val store = SQLiteStateStore(context)
        try {
            store.applyDiscoveryMembershipMerge(listOf("ACCO"), "us_total")
            store.upsertDiscoveryScores(
                listOf(
                    score("ACCO", 37).copy(companyName = "null"),
                ),
            )
            // Junk name must not be written to membership display field.
            val scores = store.queryDiscoveryScores(minScore = 0, limit = 10)
            assertEquals(listOf("ACCO"), scores.map { it.symbol })
            assertEquals(null, scores.single().companyName)

            store.upsertDiscoveryScores(
                listOf(
                    score("ACCO", 40).copy(companyName = "ACCO Brands Corporation"),
                ),
            )
            val named = store.queryDiscoveryScores(minScore = 0, limit = 10).single()
            assertEquals("ACCO Brands Corporation", named.companyName)
        } finally {
            store.close()
        }
    }

    @Test
    fun count_and_max_scored_at_support_discovery_ux() = runTest {
        val store = SQLiteStateStore(context)
        try {
            store.applyDiscoveryMembershipMerge(listOf("AAA", "BBB", "CCC"), "us_total")
            store.upsertDiscoveryScores(
                listOf(
                    score("AAA", 50).copy(scoredAtEpochSeconds = 1_700_000_100L),
                    score("BBB", 25).copy(scoredAtEpochSeconds = 1_700_000_200L),
                    score("CCC", 10).copy(scoredAtEpochSeconds = 1_700_000_050L, isQualified = false),
                ),
            )

            assertEquals(1, store.countDiscoveryScores(minScore = 30, qualifiedOnly = true))
            assertEquals(2, store.countDiscoveryScores(minScore = 0, qualifiedOnly = true))
            assertEquals(3, store.discoveryScoredSymbolCount())
            assertEquals(1_700_000_200L, store.loadDiscoveryMaxScoredAt())

            store.saveDiscoveryLastSourceHint("Live NASDAQ · 7,090")
            assertEquals("Live NASDAQ · 7,090", store.loadDiscoveryLastSourceHint())

            store.clearDiscoveryData()
            assertEquals(0, store.discoveryScoredSymbolCount())
            assertEquals(null, store.loadDiscoveryMaxScoredAt())
            assertEquals(null, store.loadDiscoveryLastSourceHint())
        } finally {
            store.close()
        }
    }

    private fun score(symbol: String, composite: Int) = DiscoveryScoreRow(
        symbol = symbol,
        companyName = null,
        compositeScore = composite,
        fundamentalsScore = 1,
        technicalScore = 1,
        forecastScore = 1,
        coverageCount = 3,
        marketPriceCents = 1_000L,
        upsideBps = 2_000,
        gapBps = 2_000,
        confidence = "High",
        isQualified = true,
        scoringModel = "AggressiveV2",
        scoredAtEpochSeconds = 1_700_000_000L,
        lastError = null,
    )

    companion object {
        private const val DB_NAME = "discount_screener_state.sqlite3"
    }
}
