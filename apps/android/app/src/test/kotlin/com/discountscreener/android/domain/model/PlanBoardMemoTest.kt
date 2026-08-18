package com.discountscreener.android.domain.model

import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.plan.DipRowInput
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class PlanBoardMemoTest {
    @Test
    fun leftover_unchanged_reuses_instance() {
        var memo = PlanBoardMemo()
        var inputs = listOf(row("EXPD", marketPriceCents = 12_000L))

        var first = memo.leftover(inputs, universeName = "qa")
        var second = memo.leftover(inputs, universeName = "qa")

        assertSame(first, second)
    }

    @Test
    fun leftover_price_change_rebuilds() {
        var memo = PlanBoardMemo()
        var first = memo.leftover(listOf(row("EXPD", marketPriceCents = 12_000L)), universeName = "qa")
        var second = memo.leftover(listOf(row("EXPD", marketPriceCents = 12_100L)), universeName = "qa")

        assertNotSame(first, second)
    }

    @Test
    fun second_dip_profile_assemble_reuses_the_same_board() {
        var memo = PlanBoardMemo()
        var inputs = listOf(row("EXPD", marketPriceCents = 12_000L))

        var first = memo.dipProfile(inputs, universeName = "qa")
        var second = memo.dipProfile(inputs, universeName = "qa")

        assertSame(first, second)
    }

    @Test
    fun leftover_clear_drops_cache() {
        var memo = PlanBoardMemo()
        var inputs = listOf(row("EXPD", marketPriceCents = 12_000L))
        var first = memo.leftover(inputs, universeName = "qa")
        memo.clear()
        var second = memo.leftover(inputs, universeName = "qa")

        assertNotSame(first, second)
    }

    private fun row(symbol: String, marketPriceCents: Long) = DipRowInput(
        symbol = symbol,
        fundamentalsScore = 20,
        marketPriceCents = marketPriceCents,
        streetFairValueCents = 12_400L,
        analystCoverageCount = 8,
        candles = listOf(
            HistoricalCandle(
                epochSeconds = 1_700_000_000L,
                openCents = marketPriceCents - 50,
                highCents = marketPriceCents + 50,
                lowCents = marketPriceCents - 80,
                closeCents = marketPriceCents,
                volume = 1_000L,
            ),
        ),
    )
}
