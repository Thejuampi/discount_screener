package com.discountscreener.core.engine

import com.discountscreener.core.model.OpportunityScoringModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoveryUniverseEngineTest {
    @Test
    fun merge_removes_missing_adds_new_and_keeps_intersection() {
        val plan = DiscoveryUniverseEngine.mergeMembership(
            seed = listOf("AAPL", "MSFT", "NVDA"),
            existing = listOf("AAPL", "IBM", "MSFT"),
        )

        assertEquals(setOf("IBM"), plan.toRemove)
        assertEquals(setOf("NVDA"), plan.toAdd)
        assertEquals(setOf("AAPL", "MSFT"), plan.toKeep)
    }

    @Test
    fun merge_normalizes_symbols_and_dedupes_seed() {
        val plan = DiscoveryUniverseEngine.mergeMembership(
            seed = listOf("aapl", "AAPL", " msft "),
            existing = listOf("AAPL"),
        )

        assertTrue(plan.toRemove.isEmpty())
        assertEquals(setOf("MSFT"), plan.toAdd)
        assertEquals(setOf("AAPL"), plan.toKeep)
    }

    @Test
    fun merge_empty_existing_adds_full_seed() {
        val plan = DiscoveryUniverseEngine.mergeMembership(
            seed = listOf("AAA", "BBB"),
            existing = emptyList(),
        )

        assertTrue(plan.toRemove.isEmpty())
        assertEquals(setOf("AAA", "BBB"), plan.toAdd)
        assertTrue(plan.toKeep.isEmpty())
    }

    @Test
    fun filter_and_rank_keeps_qualified_at_or_above_min_score_sorted_desc() {
        val rows = listOf(
            discoveryRow("LOW", compositeScore = 10, isQualified = true, coverageCount = 3),
            discoveryRow("HIGH", compositeScore = 50, isQualified = true, coverageCount = 2),
            discoveryRow("MID", compositeScore = 30, isQualified = true, coverageCount = 3),
            discoveryRow("UNQUAL", compositeScore = 90, isQualified = false, coverageCount = 3),
            discoveryRow("BELOW", compositeScore = 20, isQualified = true, coverageCount = 1),
        )

        val ranked = DiscoveryUniverseEngine.filterAndRank(
            rows = rows,
            minScore = 30,
        )

        assertEquals(listOf("HIGH", "MID"), ranked.map { it.symbol })
    }

    @Test
    fun filter_and_rank_breaks_ties_by_coverage_then_symbol() {
        val rows = listOf(
            discoveryRow("ZZZ", compositeScore = 40, isQualified = true, coverageCount = 1),
            discoveryRow("AAA", compositeScore = 40, isQualified = true, coverageCount = 3),
            discoveryRow("MMM", compositeScore = 40, isQualified = true, coverageCount = 3),
        )

        val ranked = DiscoveryUniverseEngine.filterAndRank(rows, minScore = 0)

        assertEquals(listOf("AAA", "MMM", "ZZZ"), ranked.map { it.symbol })
    }

    @Test
    fun triage_uses_opportunity_engine_cutoffs() {
        val model = OpportunityScoringModel.AggressiveV2
        val actAt = OpportunityEngine.actAtOrAboveScore(model)
        val avoidBelow = OpportunityEngine.avoidBelowScore(model)

        assertEquals(DiscoveryTriage.Act, DiscoveryUniverseEngine.triage(actAt, model))
        assertEquals(DiscoveryTriage.Watch, DiscoveryUniverseEngine.triage(avoidBelow, model))
        assertEquals(DiscoveryTriage.Avoid, DiscoveryUniverseEngine.triage(avoidBelow - 1, model))
    }

    private fun discoveryRow(
        symbol: String,
        compositeScore: Int,
        isQualified: Boolean,
        coverageCount: Int,
    ): DiscoveryScoreRow =
        DiscoveryScoreRow(
            symbol = symbol,
            companyName = null,
            compositeScore = compositeScore,
            fundamentalsScore = null,
            technicalScore = null,
            forecastScore = null,
            coverageCount = coverageCount,
            marketPriceCents = null,
            upsideBps = null,
            gapBps = null,
            confidence = null,
            isQualified = isQualified,
            scoringModel = "AggressiveV2",
            scoredAtEpochSeconds = 0L,
            lastError = null,
        )
}
