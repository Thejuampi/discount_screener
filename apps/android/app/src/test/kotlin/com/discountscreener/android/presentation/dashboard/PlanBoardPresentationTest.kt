package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ValuationModel
import com.discountscreener.core.plan.CrossSignalEngine
import com.discountscreener.core.plan.DipLane
import com.discountscreener.core.plan.DipRowInput
import com.discountscreener.core.plan.DipSignalEngine
import com.discountscreener.core.plan.DipTape
import com.discountscreener.core.plan.LeftoverCopy
import com.discountscreener.core.plan.LeftoverSignalEngine
import com.discountscreener.core.plan.MacdPhase
import com.discountscreener.core.plan.MacdTape
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
        assertTrue(ui.universeLine!!.contains("opportunities"))
    }

    // A board the refresh has not built states no count, no universe, and no scan result. The
    // whole object is asserted, because any field that slips back to a scanned board's copy
    // reports a measurement the app never made.
    @Test
    fun a_dip_board_the_refresh_has_not_built_states_no_scan_result() {
        assertEquals(
            PlanBoardUi(
                huntLabel = "DIP HUNTER",
                countsLine = "Not scored yet",
                offRadarLine = null,
                universeLine = null,
                nowTitle = "NOW · DIP",
                laterTitle = "ALMOST · REVIEW",
                now = emptyList(),
                later = emptyList(),
                emptyNow = true,
                emptyNowTitle = "Waiting for the refresh",
                emptyNowDetail = "This board reads the data the refresh is loading. It is built " +
                    "when the refresh ends.",
            ),
            presentPlanBoard(null),
        )
    }

    @Test
    fun a_leftover_board_the_refresh_has_not_built_states_no_scan_result() {
        assertEquals(
            PlanBoardUi(
                huntLabel = "LEFTOVER REVIEW",
                countsLine = "Not scored yet",
                offRadarLine = null,
                universeLine = null,
                nowTitle = "PRIMARY · FADE",
                laterTitle = "REVIEW · AT TARGET",
                now = emptyList(),
                later = emptyList(),
                emptyNow = true,
                emptyNowTitle = "Waiting for the refresh",
                emptyNowDetail = "This board reads the data the refresh is loading. It is built " +
                    "when the refresh ends.",
            ),
            presentLeftoverBoard(null),
        )
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

    @Test
    fun selected_dip_board_uses_the_profile_when_toggled() {
        var opps = PlanBoard.EMPTY.copy(universeName = "opportunities", scanned = 2)
        var profile = PlanBoard.EMPTY.copy(universeName = "qa", scanned = 20)
        var ui = presentSelectedDipBoard(opps, profile, PlanDipUniverse.Profile)
        assertTrue(ui.universeLine!!.contains("qa"))
    }

    @Test
    fun leftover_empty_primary_uses_fade_copy() {
        var ui = presentLeftoverBoard(PlanBoard.EMPTY.copy(universeName = "qa"))
        assertEquals("No leftover fade", ui.emptyNowTitle)
    }

    @Test
    fun leftover_counts_line_names_the_profile() {
        var ui = presentLeftoverBoard(PlanBoard.EMPTY.copy(universeName = "qa"))
        assertTrue(ui.universeLine!!.contains("qa"))
    }

    @Test
    fun leftover_copy_does_not_say_sell() {
        var ui = presentLeftoverBoard(PlanBoard.EMPTY.copy(universeName = "qa"))
        var blob = listOf(ui.huntLabel, ui.emptyNowTitle, ui.emptyNowDetail, ui.nowTitle, ui.laterTitle).joinToString(" ")
        assertTrue(!blob.contains("sell", ignoreCase = true))
    }

    @Test
    fun horizon_and_death_cross_keep_the_model_tag() {
        var now = DipSignalEngine.classify(
            DipRowInput(
                symbol = "JPM",
                fundamentalsScore = 18,
                marketPriceCents = 10_000,
                streetFairValueCents = 13_000,
                analystCoverageCount = 8,
                dcf = residualIncome(),
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
            MacdTape(-8.0, 4.0, 1.0, MacdPhase.Turning),
        )
        var ui = presentPlanBoard(DipSignalEngine.rank(listOf(now)))
        assertTrue(ui.now.single().evidence.any { it.contains("Residual income") })
    }

    @Test
    fun leftover_horizon_keeps_the_model_tag() {
        var now = LeftoverSignalEngine.classify(
            DipRowInput(
                symbol = "JPM",
                fundamentalsScore = 18,
                marketPriceCents = 10_000,
                streetFairValueCents = 10_200,
                analystCoverageCount = 8,
                dcf = residualIncome(),
            ),
            DipTape(
                atrCents = 200,
                high20dCents = 10_800,
                lastCloseCents = 10_000,
                dipAtrUnits = 0.4,
                rsi = 68.0,
                rsiSlope = -0.6,
                rsiAccel = -0.2,
                histogram = 12.0,
                histSlope = -5.0,
                histAccel = -1.0,
                macdPhase = MacdPhase.Flipped,
                deathCross = true,
            ),
            MacdTape(10.0, -3.0, -1.0, MacdPhase.Flipped),
        )
        var ui = presentLeftoverBoard(LeftoverSignalEngine.rank(listOf(now), "qa"))
        assertTrue(ui.now.single().evidence.any { it.contains("Residual income") })
    }

    @Test
    fun leftover_card_uses_leftover_street_copy() {
        var now = LeftoverSignalEngine.classify(
            DipRowInput(
                symbol = "T",
                fundamentalsScore = 12,
                marketPriceCents = 10_000,
                streetFairValueCents = 10_200,
                analystCoverageCount = 8,
            ),
            DipTape(
                atrCents = 200,
                high20dCents = 10_800,
                lastCloseCents = 10_000,
                dipAtrUnits = 0.4,
                rsi = 68.0,
                rsiSlope = -0.6,
                rsiAccel = -0.2,
                histogram = 12.0,
                histSlope = -5.0,
                histAccel = -1.0,
                macdPhase = MacdPhase.Flipped,
                deathCross = false,
            ),
        )
        var ui = presentLeftoverBoard(LeftoverSignalEngine.rank(listOf(now), "qa"))
        assertEquals(LeftoverCopy.streetLine(now.streetUpsideBps), ui.now.single().streetLabel)
    }

    @Test
    fun leftover_primary_badge_says_fade() {
        var now = LeftoverSignalEngine.classify(
            DipRowInput(
                symbol = "T",
                fundamentalsScore = 12,
                marketPriceCents = 10_000,
                streetFairValueCents = 10_200,
                analystCoverageCount = 8,
            ),
            DipTape(
                atrCents = 200,
                high20dCents = 10_800,
                lastCloseCents = 10_000,
                dipAtrUnits = 0.4,
                rsi = 68.0,
                rsiSlope = -0.6,
                rsiAccel = -0.2,
                histogram = 12.0,
                histSlope = -5.0,
                histAccel = -1.0,
                macdPhase = MacdPhase.Flipped,
                deathCross = false,
            ),
        )
        var ui = presentLeftoverBoard(LeftoverSignalEngine.rank(listOf(now), "qa"))
        assertEquals("Fade", ui.now.single().laneLabel)
    }

    @Test
    fun a_cross_board_the_refresh_has_not_built_states_no_scan_result() {
        assertEquals(
            PlanBoardUi(
                huntLabel = "CROSS HUNTER",
                countsLine = "Not scored yet",
                offRadarLine = null,
                universeLine = null,
                nowTitle = "NOW · CROSS",
                laterTitle = "ALMOST · REVIEW",
                now = emptyList(),
                later = emptyList(),
                emptyNow = true,
                emptyNowTitle = "Waiting for the refresh",
                emptyNowDetail = "This board reads the data the refresh is loading. It is built " +
                    "when the refresh ends.",
            ),
            presentCrossBoard(null),
        )
    }

    @Test
    fun cross_empty_now_uses_cross_copy() {
        var ui = presentCrossBoard(PlanBoard.EMPTY)
        assertEquals("No golden cross now", ui.emptyNowTitle)
    }

    @Test
    fun cross_now_badge_says_cross() {
        var setup = CrossSignalEngine.classify(
            DipRowInput(
                symbol = "X",
                fundamentalsScore = 20,
                marketPriceCents = 10_000,
                streetFairValueCents = 13_000,
                analystCoverageCount = 8,
            ),
            DipTape(
                atrCents = 200,
                high20dCents = 10_800,
                lastCloseCents = 10_000,
                dipAtrUnits = 0.4,
                rsi = 40.0,
                rsiSlope = 0.4,
                rsiAccel = 0.1,
                histogram = 12.0,
                histSlope = 8.0,
                histAccel = 3.0,
                macdPhase = MacdPhase.Flipped,
                deathCross = false,
            ),
            barsSinceCross = 0,
        )
        var ui = presentCrossBoard(CrossSignalEngine.rank(listOf(setup)))
        assertEquals("Cross", ui.now.first().laneLabel)
    }

    @Test
    fun selected_cross_board_uses_the_profile_when_toggled() {
        var opps = PlanBoard.EMPTY.copy(universeName = "opportunities", scanned = 2)
        var profile = PlanBoard.EMPTY.copy(universeName = "qa", scanned = 20)
        var ui = presentSelectedCrossBoard(opps, profile, PlanDipUniverse.Profile)
        assertTrue(ui.universeLine!!.contains("qa"))
    }
}

private fun residualIncome(): DcfAnalysis = DcfAnalysis(
    bearIntrinsicValueCents = 11_000,
    baseIntrinsicValueCents = 12_000,
    bullIntrinsicValueCents = 13_000,
    waccBps = 900,
    baseGrowthBps = 300,
    netDebtDollars = 0,
    businessClass = BusinessClass.FinancialServices,
    model = ValuationModel.ResidualIncomeEquity,
)
