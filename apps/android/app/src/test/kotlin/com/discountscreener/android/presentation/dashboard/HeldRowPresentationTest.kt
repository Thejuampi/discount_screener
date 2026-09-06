package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.core.model.ConfidenceBand
import org.junit.Assert.assertEquals
import org.junit.Test

class HeldRowPresentationTest {

    @Test
    fun pin_opp_puts_the_held_name_first() {
        assertEquals(
            listOf("AMZN", "MSFT"),
            pinOpportunityRows(listOf(opp("MSFT", 80), opp("AMZN", 40)), setOf("AMZN")).map { it.symbol },
        )
    }

    @Test
    fun pin_opp_marks_the_held_row() {
        assertEquals(
            listOf(true, false),
            pinOpportunityRows(listOf(opp("MSFT", 80), opp("AMZN", 40)), setOf("AMZN")).map { it.held },
        )
    }

    @Test
    fun pin_tracked_marks_the_held_row() {
        assertEquals(
            listOf(true, false),
            pinTrackedRows(listOf(tracked("MSFT"), tracked("AMZN")), setOf("AMZN")).map { it.held },
        )
    }

    @Test
    fun pin_opp_keeps_pre_pin_scores() {
        assertEquals(
            listOf(40, 80),
            pinOpportunityRows(listOf(opp("MSFT", 80), opp("AMZN", 40)), setOf("AMZN")).map { it.compositeScore },
        )
    }

    @Test
    fun pin_opp_keeps_pre_pin_printed_rank() {
        assertEquals(
            listOf(2, 1),
            pinOpportunityRows(listOf(opp("MSFT", 80), opp("AMZN", 40)), setOf("AMZN")).map { it.scoreRank },
        )
    }

    @Test
    fun pin_does_not_invent_a_row() {
        assertEquals(
            listOf("MSFT"),
            pinOpportunityRows(listOf(opp("MSFT", 80)), setOf("PHYL")).map { it.symbol },
        )
    }

    @Test
    fun pin_tracked_puts_the_held_name_first() {
        assertEquals(
            listOf("AMZN", "MSFT"),
            pinTrackedRows(listOf(tracked("MSFT"), tracked("AMZN")), setOf("AMZN")).map { it.symbol },
        )
    }

    @Test
    fun pin_watch_runs_after_the_watched_filter() {
        assertEquals(
            listOf("AMZN", "MSFT"),
            pinWatchedRows(
                listOf(
                    tracked("MSFT", watched = true),
                    tracked("GOOG", watched = false, held = true),
                    tracked("AMZN", watched = true),
                ).let { pinTrackedRows(it, setOf("AMZN", "GOOG")) },
            ).map { it.symbol },
        )
    }

    private fun opp(symbol: String, score: Int) = OpportunityListRow(
        symbol = symbol,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 15_000L,
        gapBps = 5_000,
        confidence = ConfidenceBand.High,
        isWatched = false,
        compositeScore = score,
        coverageCount = 3,
    )

    private fun tracked(symbol: String, watched: Boolean = false, held: Boolean = false) = TrackedSymbolRow(
        symbol = symbol,
        isWatched = watched,
        held = held,
    )
}
