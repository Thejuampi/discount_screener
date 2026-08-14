package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.plan.DipLane
import com.discountscreener.core.plan.DipRowInput
import com.discountscreener.core.plan.DipSignalEngine
import com.discountscreener.core.plan.DipTape
import com.discountscreener.core.plan.MacdPhase
import com.discountscreener.core.plan.PlanBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanBoardPresentationTest {
    @Test
    fun empty_now_uses_calm_copy() {
        var ui = presentPlanBoard(PlanBoard.EMPTY)
        assertEquals("No dip now", ui.emptyNowTitle)
    }

    @Test
    fun counts_line_names_the_universe() {
        var ui = presentPlanBoard(PlanBoard.EMPTY)
        assertTrue(ui.universeLine.contains("opportunities"))
    }

    @Test
    fun death_cross_is_visible_on_the_card() {
        var now = DipSignalEngine.classify(
            DipRowInput(
                symbol = "LVS",
                fundamentalsScore = 18,
                marketPriceCents = 10_000,
                streetFairValueCents = 13_000,
                analystCoverageCount = 5,
            ),
            DipTape(
                atrCents = 200,
                high20dCents = 13_000,
                lastCloseCents = 10_000,
                dipAtrUnits = 1.5,
                rsi = 34.0,
                rsiSlope = 0.5,
                rsiAccel = 0.1,
                histogram = -15.0,
                histSlope = 8.0,
                histAccel = 3.0,
                macdPhase = MacdPhase.Imminent,
                deathCross = true,
            ),
        )
        var ui = presentPlanBoard(DipSignalEngine.rank(listOf(now)))
        assertTrue(ui.now.single().deathCross)
    }

    @Test
    fun later_card_keeps_the_almost_lane() {
        var almost = DipSignalEngine.classify(
            DipRowInput(
                symbol = "DVN",
                fundamentalsScore = 18,
                marketPriceCents = 10_000,
                streetFairValueCents = 11_600,
                analystCoverageCount = 5,
            ),
            DipTape(
                atrCents = 200,
                high20dCents = 13_000,
                lastCloseCents = 10_000,
                dipAtrUnits = 1.5,
                rsi = 34.0,
                rsiSlope = 0.5,
                rsiAccel = 0.1,
                histogram = -20.0,
                histSlope = 5.0,
                histAccel = 1.0,
                macdPhase = MacdPhase.Turning,
                deathCross = false,
            ),
        )
        var ui = presentPlanBoard(DipSignalEngine.rank(listOf(almost)))
        assertEquals(DipLane.Almost, ui.later.single().lane)
    }
}
