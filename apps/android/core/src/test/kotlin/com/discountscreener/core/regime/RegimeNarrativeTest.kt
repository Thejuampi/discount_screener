package com.discountscreener.core.regime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * English sentences from Windows `interpret.rs` / `narrative.rs`. One exact string per test.
 */
class RegimeNarrativeTest {

    @Test
    fun trend_good_uses_the_windows_english_sentence() {
        assertEquals(
            "Moderately positive trend; mild upside bias without a clean breakout.",
            interpretPillar("trend", 35, 9500, stale = false),
        )
    }

    @Test
    fun breadth_weak_uses_the_windows_english_sentence() {
        assertEquals(
            "Weak breadth: the move is narrow; fragility sits under the surface.",
            interpretPillar("breadth", -42, 9500, stale = false),
        )
    }

    @Test
    fun partial_data_appends_the_windows_caution_clause() {
        assertEquals(
            "Moderately positive trend; mild upside bias without a clean breakout. (partial data — treat with caution)",
            interpretPillar("trend", 35, 1999, stale = false),
        )
    }

    @Test
    fun fng_greed_hint_uses_the_windows_english_sentence() {
        assertEquals(
            "F&G in greed zone: bias against chasing (66 Greed).",
            interpretSignal("cnn_fng", -40, "66 Greed"),
        )
    }

    @Test
    fun hold_trim_thesis_uses_the_default_regime_template() {
        assertEquals(
            "Regime bull: environment risk-on, stance hold / trim. Exposure ceiling 75% · F&G 66 · breadth 40%.",
            thesisOf(holdTrimRegime(), holdTrimComposite()),
        )
    }

    @Test
    fun hold_trim_first_action_is_the_exposure_ceiling() {
        assertEquals(
            "Respect 75% exposure ceiling for new risk (not a forced portfolio target).",
            actionBullets(holdTrimRegime(), holdTrimComposite()).first(),
        )
    }

    @Test
    fun compute_market_regime_fills_every_pillar_interpretation() {
        assertEquals(
            6,
            computeMarketRegime(fullBundle(), fullUniverse()).pillars.count { it.interpretation.isNotEmpty() },
        )
    }

    @Test
    fun compute_market_regime_fills_the_aggregate_reading() {
        assertTrue(computeMarketRegime(fullBundle(), fullUniverse()).reading.isNotEmpty())
    }

    private fun holdTrimRegime() = MarketRegime(
        primaryRegime = "Bull",
        environmentBand = "RiskOn",
        actionStance = "HoldTrim",
        suggestedExposurePct = 75,
        cashBufferPct = 25,
        newRiskMultiplierBps = 5625,
        cnnFearGreed = 66,
        cnnFearGreedLabel = "Greed",
        breadthAboveMa200Pct = 40.0,
        pillars = listOf(
            RegimePillar("trend", "Trend", 35, 9500, 3000, emptyList(), false, "", "bullish", 68),
            RegimePillar("breadth", "Breadth", -42, 4000, 2000, emptyList(), false, "", "caution", 29),
        ),
    )

    private fun holdTrimComposite() = CompositeOutput(
        environmentScore = 24,
        sentimentScore = -21,
        qualityScore = 6,
        environmentBand = "RiskOn",
        actionStance = "HoldTrim",
        primaryRegime = "Bull",
        suggestedExposurePct = 75,
        cashBufferPct = 25,
        newRiskMultiplierBps = 5625,
        addBias = 0,
        preferQuality = false,
        globalConfidenceBps = 8000,
        weightTrendBps = 3000,
        weightBreadthBps = 3000,
        weightVolBps = 2000,
        weightCrossBps = 1000,
        weightQualityBps = 1000,
        crisisCapApplied = false,
        qualityHaircutApplied = false,
    )
}
