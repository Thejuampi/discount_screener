package com.discountscreener.core.plan

import kotlin.test.Test
import kotlin.test.assertEquals

class MacdHorizonScoreTest {
    @Test
    fun dip_aligns_when_both_years_are_turning() {
        var score = MacdHorizonScore.score(
            year = turningTape(),
            fiveYear = turningTape(),
            sense = MacdHorizonSense.DipTurn,
        )
        assertEquals(MacdHorizonScore.ALIGN, score)
    }

    @Test
    fun dip_keeps_one_year_lead_when_five_year_is_missing() {
        var score = MacdHorizonScore.score(
            year = turningTape(),
            fiveYear = null,
            sense = MacdHorizonSense.DipTurn,
        )
        assertEquals(MacdHorizonScore.FLAT, score)
    }

    @Test
    fun dip_does_not_align_when_only_five_year_is_turning() {
        var score = MacdHorizonScore.score(
            year = distantTape(),
            fiveYear = turningTape(),
            sense = MacdHorizonSense.DipTurn,
        )
        assertEquals(MacdHorizonScore.FLAT, score)
    }

    @Test
    fun dip_drags_when_five_year_is_stuck_negative() {
        var score = MacdHorizonScore.score(
            year = turningTape(),
            fiveYear = distantTape(),
            sense = MacdHorizonSense.DipTurn,
        )
        assertEquals(MacdHorizonScore.DRAG, score)
    }

    @Test
    fun leftover_aligns_when_both_years_are_fading() {
        var score = MacdHorizonScore.score(
            year = fadingTape(),
            fiveYear = fadingTape(),
            sense = MacdHorizonSense.LeftoverFade,
        )
        assertEquals(MacdHorizonScore.ALIGN, score)
    }

    @Test
    fun leftover_drags_when_five_year_is_still_expanding() {
        var score = MacdHorizonScore.score(
            year = fadingTape(),
            fiveYear = expandingTape(),
            sense = MacdHorizonSense.LeftoverFade,
        )
        assertEquals(MacdHorizonScore.DRAG, score)
    }

    @Test
    fun cross_aligns_when_both_years_are_expanding() {
        var score = MacdHorizonScore.score(
            year = expandingTape(),
            fiveYear = expandingTape(),
            sense = MacdHorizonSense.CrossFresh,
        )
        assertEquals(MacdHorizonScore.ALIGN, score)
    }
}

private fun turningTape(): MacdTape = MacdTape(
    histogram = -8.0,
    histSlope = 4.0,
    histAccel = 1.0,
    macdPhase = MacdPhase.Turning,
)

private fun distantTape(): MacdTape = MacdTape(
    histogram = -20.0,
    histSlope = -3.0,
    histAccel = -1.0,
    macdPhase = MacdPhase.Distant,
)

private fun fadingTape(): MacdTape = MacdTape(
    histogram = 12.0,
    histSlope = -5.0,
    histAccel = -1.0,
    macdPhase = MacdPhase.Flipped,
)

private fun expandingTape(): MacdTape = MacdTape(
    histogram = 12.0,
    histSlope = 4.0,
    histAccel = 1.0,
    macdPhase = MacdPhase.Flipped,
)
