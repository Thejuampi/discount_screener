package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.domain.model.MarketReadStatus
import com.discountscreener.core.regime.MarketRegime
import com.discountscreener.core.regime.RegimePillar
import org.junit.Assert.assertEquals
import org.junit.Test

class MarketRegimePresentationTest {

    @Test
    fun pending_without_a_reading_shows_the_loading_line() {
        assertEquals(
            "Loading market regime…",
            presentMarketRegime(null, MarketReadStatus.Pending).unavailableReason,
        )
    }

    @Test
    fun ready_hold_trim_maps_the_header_fields() {
        var ui = presentMarketRegime(holdTrim(), MarketReadStatus.Ready)
        assertEquals("Bull|Hold / trim|75|0.56×|80%", "${ui.phaseLabel}|${ui.stanceLabel}|${ui.exposurePct}|${ui.newRiskLabel}|${ui.confidencePct}")
    }

    @Test
    fun radar_uses_windows_axis_order_and_labels() {
        var ui = presentMarketRegime(holdTrim(), MarketReadStatus.Ready)
        assertEquals(
            listOf("Trend", "Breadth", "Calm", "F&G opp.", "Cross-asset", "Quality"),
            ui.radar.map { it.label },
        )
    }

    @Test
    fun a_missing_pillar_uses_the_windows_zero_score_radius() {
        var regime = holdTrim().copy(pillars = holdTrim().pillars.filterNot { it.id == "trend" })
        assertEquals(0.50f, presentMarketRegime(regime, MarketReadStatus.Ready).radar.first().radius01)
    }

    @Test
    fun fear_and_greed_chip_carries_the_score_and_label() {
        var chip = presentMarketRegime(holdTrim(), MarketReadStatus.Ready).chips.single { it.label == "Fear & Greed" }
        assertEquals("66 Greed", chip.value)
    }

    private fun holdTrim() = MarketRegime(
        primaryRegime = "Bull",
        environmentBand = "RiskOn",
        actionStance = "HoldTrim",
        suggestedExposurePct = 75,
        cashBufferPct = 25,
        newRiskMultiplierBps = 5625,
        globalConfidenceBps = 8000,
        environmentScore = 24,
        sentimentScore = -21,
        qualityScore = 6,
        cnnFearGreed = 66,
        cnnFearGreedLabel = "Greed",
        thesis = "Regime bull.",
        reading = "Market phase: bull.",
        actionBullets = listOf("Respect 75% exposure ceiling for new risk (not a forced portfolio target)."),
        pillars = listOf(
            RegimePillar("trend", "Trend", 35, 9500, 3000, emptyList(), false, "Moderately positive trend.", "bullish", 68),
            RegimePillar("breadth", "Breadth", -42, 4000, 2000, emptyList(), false, "Weak breadth.", "caution", 29),
            RegimePillar("volatility", "Volatility / stress", -34, 9000, 2000, emptyList(), false, "Contained volatility.", "bullish", 67),
            RegimePillar("sentiment", "Sentiment (contrarian)", -21, 8000, 0, emptyList(), false, "Rising greed.", "caution", 40),
            RegimePillar("cross_asset", "Cross-asset", 20, 7000, 1000, emptyList(), false, "Mildly constructive.", "bullish", 60),
            RegimePillar("quality", "Quality / fragility", 6, 6000, 1000, emptyList(), false, "Neutral quality.", "neutral", 53),
        ),
    )
}
