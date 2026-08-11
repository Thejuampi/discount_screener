package com.discountscreener.core.engine

import com.discountscreener.core.model.MarketSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Qualification selects. Any statistic taken over the Opportunities list alone is taken over the
 * survivors, so an offline study of the engine needs a way to ask for the cohort — and the product
 * list must keep getting exactly what it got before.
 */
class OpportunityCohortTest {

    @Test
    fun the_cohort_scores_a_candidate_the_opportunity_list_drops() {
        var symbols = OpportunityEngine
            .buildRows(engineWithOneQualifiedAndOneNot(), includeUnqualified = true)
            .map { it.symbol }

        assertEquals(listOf("GOOD", "BAD"), symbols)
    }

    @Test
    fun the_default_still_scores_only_the_qualified() {
        var symbols = OpportunityEngine
            .buildRows(engineWithOneQualifiedAndOneNot())
            .map { it.symbol }

        assertEquals(listOf("GOOD"), symbols)
    }

    /** `BAD` is unprofitable, which is one of the two ways qualification is refused. */
    private fun engineWithOneQualifiedAndOneNot(): ReportingEngine {
        var engine = ReportingEngine()
        engine.ingestSnapshot(snapshot("GOOD", profitable = true))
        engine.ingestSnapshot(snapshot("BAD", profitable = false))
        return engine
    }

    private fun snapshot(symbol: String, profitable: Boolean) = MarketSnapshot(
        symbol = symbol,
        profitable = profitable,
        marketPriceCents = 8_000,
        intrinsicValueCents = 10_000,
    )
}
