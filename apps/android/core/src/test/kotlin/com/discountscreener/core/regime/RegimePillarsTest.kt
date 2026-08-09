package com.discountscreener.core.regime

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariants per pillar rather than golden weight snapshots: a recalibration on either platform
 * should make these fail only if it changed what the pillar *means*, not every time a constant
 * moves.
 *
 * The property each pillar has to hold is the same one: a pillar with no inputs answers with zero
 * confidence, not a zero score presented as a reading. "The market is neutral" and "we cannot
 * tell" are different claims, and only confidence separates them.
 */
class RegimePillarsTest {
    // ── Breadth ──────────────────────────────────────────────────────────────

    @Test
    fun a_universe_above_its_moving_averages_reads_bullish() {
        assertTrue(breadthPillar(computeBreadth(universe(count = 20, bullish = true))).score > 30)
    }

    @Test
    fun a_universe_below_its_moving_averages_reads_bearish() {
        assertTrue(breadthPillar(computeBreadth(universe(count = 20, bullish = false))).score < -30)
    }

    @Test
    fun breadth_without_a_universe_has_no_confidence() {
        assertEquals(0, breadthPillar(computeBreadth(emptyList())).confidenceBps)
    }

    @Test
    fun etfs_do_not_vote_on_whether_the_index_is_broad() {
        assertEquals(0, computeBreadth(listOf(SymbolChartView("SPY", summary(bullish = true)))).sample)
    }

    @Test
    fun crypto_does_not_vote_on_equity_breadth() {
        assertEquals(0, computeBreadth(listOf(SymbolChartView("BTC-USD", summary(bullish = true)))).sample)
    }

    @Test
    fun a_thin_sample_is_less_confident_than_a_deep_one() {
        assertTrue(
            breadthPillar(computeBreadth(universe(count = 10, bullish = true))).confidenceBps <
                breadthPillar(computeBreadth(universe(count = 250, bullish = true))).confidenceBps,
        )
    }

    // ── Trend ────────────────────────────────────────────────────────────────

    @Test
    fun spy_above_its_moving_averages_reads_positive() {
        assertTrue(trendPillar(summary(bullish = true), risingCloses()).score > 0)
    }

    @Test
    fun spy_below_its_moving_averages_reads_negative() {
        assertTrue(trendPillar(summary(bullish = false), risingCloses().reversed()).score < 0)
    }

    @Test
    fun a_missing_spy_is_stale_rather_than_neutral() {
        assertTrue(trendPillar(null, emptyList()).stale)
    }

    @Test
    fun a_missing_spy_has_no_confidence() {
        assertEquals(0, trendPillar(null, emptyList()).confidenceBps)
    }

    // ── Volatility ───────────────────────────────────────────────────────────

    @Test
    fun a_vix_spike_reads_as_stress() {
        assertTrue(volSnapshot(vixCloses = spikingVix(), vix3mCloses = listOf(20.0), spyCloses = risingCloses()).stressScore > 0)
    }

    /**
     * The VIX terms are read as a *percentile of their own year*, not as a level, so "calm" has to
     * be a series whose latest bar sits at the bottom of its own history. A flat low series would
     * put its last bar at the top of its range and read as stress — which is the pillar behaving
     * correctly, and the reason this fixture declines rather than merely being small.
     */
    @Test
    fun a_calm_vix_reads_as_the_absence_of_stress() {
        assertTrue(volSnapshot(vixCloses = decliningVix(), vix3mCloses = listOf(18.0), spyCloses = risingCloses()).stressScore < 0)
    }

    @Test
    fun no_vix_series_leaves_the_volatility_pillar_stale() {
        assertTrue(volPillar(volSnapshot(emptyList(), emptyList(), emptyList())).stale)
    }

    @Test
    fun no_vix_series_leaves_the_volatility_pillar_unconfident() {
        assertEquals(0, volPillar(volSnapshot(emptyList(), emptyList(), emptyList())).confidenceBps)
    }

    // ── Sentiment ────────────────────────────────────────────────────────────

    @Test
    fun extreme_fear_is_a_contrarian_buy() {
        assertTrue(sentimentPillar(fearGreed(15.0), BreadthSnapshot()).score > 50)
    }

    @Test
    fun extreme_greed_is_a_contrarian_sell() {
        assertTrue(sentimentPillar(fearGreed(90.0), BreadthSnapshot()).score < -50)
    }

    @Test
    fun sentiment_without_cnn_falls_back_to_internals_at_lower_confidence() {
        assertEquals(
            4500,
            sentimentPillar(null, BreadthSnapshot(pctRsiAbove70 = 5.0, pctRsiBelow30 = 40.0)).confidenceBps,
        )
    }

    @Test
    fun sentiment_with_no_input_at_all_has_no_confidence() {
        assertEquals(0, sentimentPillar(null, BreadthSnapshot()).confidenceBps)
    }

    // ── Cross-asset ──────────────────────────────────────────────────────────

    @Test
    fun credit_outperforming_treasuries_reads_risk_on() {
        assertTrue(crossAssetPillar(mapOf("HYG" to rising(), "IEF" to flat())).score > 0)
    }

    @Test
    fun credit_underperforming_treasuries_reads_risk_off() {
        assertTrue(crossAssetPillar(mapOf("HYG" to falling(), "IEF" to flat())).score < 0)
    }

    @Test
    fun cross_asset_with_no_series_is_stale() {
        assertTrue(crossAssetPillar(emptyMap()).stale)
    }

    // ── Quality ──────────────────────────────────────────────────────────────

    @Test
    fun an_index_rising_on_thin_breadth_reads_fragile() {
        assertTrue(
            qualityPillar(
                breadth = BreadthSnapshot(aboveMa200Pct = 30.0, sample = 100),
                spyAboveMa200 = true,
                avgCorrMilli = null,
                stress = 0,
                cross = CrossAssetSnapshot(),
            ).score < 0,
        )
    }

    @Test
    fun an_index_rising_on_broad_breadth_reads_healthy() {
        assertTrue(
            qualityPillar(
                breadth = BreadthSnapshot(aboveMa200Pct = 70.0, sample = 100),
                spyAboveMa200 = true,
                avgCorrMilli = null,
                stress = 0,
                cross = CrossAssetSnapshot(),
            ).score > 0,
        )
    }

    @Test
    fun quality_with_nothing_to_read_has_no_confidence() {
        assertEquals(
            0,
            qualityPillar(BreadthSnapshot(), null, null, 0, CrossAssetSnapshot()).confidenceBps,
        )
    }

    // ── Drawdown ─────────────────────────────────────────────────────────────

    @Test
    fun a_series_at_its_high_has_no_drawdown() {
        assertEquals(0.0, spyDrawdownFromAthPct(listOf(100.0, 120.0, 150.0))!!)
    }

    @Test
    fun a_series_off_its_high_reports_the_distance() {
        assertEquals(20.0, spyDrawdownFromAthPct(listOf(100.0, 150.0, 120.0))!!)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun summary(bullish: Boolean) = ChartRangeSummary(
        range = ChartRange.Year,
        capturedAt = 0L,
        candleCount = 260,
        latestCloseCents = if (bullish) 11_000L else 9_000L,
        ema20Cents = 10_000L,
        ema50Cents = if (bullish) 10_000L else 10_500L,
        ema200Cents = if (bullish) 9_000L else 11_000L,
        histogramCents = if (bullish) 5L else -5L,
        latestWilderRsi = if (bullish) 58.0 else 38.0,
        pos52wPct = if (bullish) 75.0 else 15.0,
        adx = 25.0,
        plusDi = if (bullish) 30.0 else 15.0,
        minusDi = if (bullish) 15.0 else 30.0,
    )

    private fun universe(count: Int, bullish: Boolean) =
        (0 until count).map { index -> SymbolChartView("SYM$index.BA", summary(bullish)) }

    private fun fearGreed(score: Double) = CnnFearGreed(score = score, rating = fngZone(score))

    private fun risingCloses() = (0 until 80).map { 400.0 + it }

    private fun spikingVix() = (0 until 60).map { 12.0 } + (0 until 6).map { 30.0 + it }

    private fun decliningVix() = (0 until 60).map { 30.0 - (it * 0.3) }

    private fun rising() = (0 until 40).map { 100.0 + (it * 2.0) }

    private fun falling() = (0 until 40).map { 100.0 - (it * 1.0) }

    private fun flat() = (0 until 40).map { 100.0 }
}
