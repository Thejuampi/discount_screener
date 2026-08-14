package com.discountscreener.android.domain.model

import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.plan.DipLane
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanBoardAssemblerTest {
    @Test
    fun missing_year_candles_cannot_put_a_name_on_now() {
        var row = OpportunityListRow(
            symbol = "SNDK",
            marketPriceCents = 152_811,
            intrinsicValueCents = 200_000,
            gapBps = 3_000,
            confidence = ConfidenceBand.High,
            isWatched = false,
            fundamentalsScore = 25,
            compositeScore = 61,
            coverageCount = 3,
            analystCoverageCount = 8,
        )
        var board = PlanBoardAssembler.assemble(
            rows = listOf(row),
            yearCandlesBySymbol = emptyMap(),
            dcfBySymbol = emptyMap(),
        )
        assertTrue(board.now.none { it.lane == DipLane.Now })
    }
}
