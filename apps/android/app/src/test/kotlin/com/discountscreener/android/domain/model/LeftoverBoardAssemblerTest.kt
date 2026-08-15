package com.discountscreener.android.domain.model

import com.discountscreener.core.plan.DipLane
import com.discountscreener.core.plan.DipRowInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeftoverBoardAssemblerTest {
    @Test
    fun missing_year_candles_cannot_put_a_name_on_primary() {
        var input = DipRowInput(
            symbol = "NEAR",
            fundamentalsScore = 20,
            marketPriceCents = 10_000,
            streetFairValueCents = 10_200,
            analystCoverageCount = 8,
            candles = emptyList(),
        )
        var board = LeftoverBoardAssembler.assemble(listOf(input), universeName = "qa")
        assertTrue(board.now.none { it.lane == DipLane.Now })
    }

    @Test
    fun universe_name_is_the_profile() {
        var board = LeftoverBoardAssembler.assemble(emptyList(), universeName = "russell")
        assertEquals("russell", board.universeName)
    }

    @Test
    fun scans_every_profile_member() {
        var members = listOf(
            DipRowInput(
                symbol = "OWNED",
                fundamentalsScore = 12,
                marketPriceCents = 10_000,
                streetFairValueCents = 10_100,
                analystCoverageCount = 4,
            ),
            DipRowInput(
                symbol = "CHEAP",
                fundamentalsScore = 30,
                marketPriceCents = 10_000,
                streetFairValueCents = 14_000,
                analystCoverageCount = 8,
            ),
        )
        var board = LeftoverBoardAssembler.assemble(members, universeName = "sp500")
        assertEquals(2, board.scanned)
    }
}
