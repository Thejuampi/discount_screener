package com.discountscreener.core.plan

import kotlin.test.Test
import kotlin.test.assertEquals

class PlanBoardTest {
    @Test
    fun off_radar_almost_is_almost_count_minus_later_cards() {
        var setups = (1..82).map { index ->
            DipSignalEngine.classify(
                DipRowInput(
                    symbol = "A$index",
                    fundamentalsScore = 10,
                    marketPriceCents = 10_000,
                    streetFairValueCents = 11_600,
                    analystCoverageCount = 4,
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
        }
        var board = DipSignalEngine.rank(setups)
        assertEquals(2, board.offRadarAlmost)
    }
}
