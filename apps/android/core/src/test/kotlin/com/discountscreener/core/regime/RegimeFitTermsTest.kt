package com.discountscreener.core.regime

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.FundamentalSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The market bucket's per-term view, which exists so a study can measure the mechanism instead of
 * inferring it from the composite score.
 *
 * A correlation of the market bucket against the technicals bucket says the two move together. It
 * cannot say through which term, because the bucket is a weighted mean of up to nine of them. These
 * tests fix the properties such a study depends on: that every term is reported, and that the two
 * terms reading the same stretch report it with opposite signs.
 */
class RegimeFitTermsTest {

    /**
     * One observable, two readings. A price far above its own averages is momentum to the trend
     * term and over-extension to the anti-extension term, and the regime policy arbitrates between
     * them by weight. Any study that treated the pair as one duplicated fact would be wrong about
     * the sign, so the opposition is asserted here rather than assumed.
     */
    @Test
    fun trend_and_anti_extension_read_the_same_stretch_with_opposite_signs() {
        var signs = regimeFitTerms(qualityFundamentals(), stretchedChart(), deploy())
            .filter { it.factor == RegimeCauseFactor.Trend || it.factor == RegimeCauseFactor.Extension }
            .sortedBy { it.factor.name }
            .map { it.factor to (it.signed > 0.0) }

        assertEquals(
            listOf(RegimeCauseFactor.Extension to false, RegimeCauseFactor.Trend to true),
            signs,
        )
    }

    /**
     * Which terms the active stance has switched off is evidence about the stance, so a zero weight
     * must not remove the term from the report. Defend pays nothing for growth; the term still has
     * to appear, carrying that zero.
     */
    @Test
    fun a_term_the_stance_weights_at_zero_is_still_reported() {
        var growth = regimeFitTerms(qualityFundamentals("Technology"), stretchedChart(), defend())
            .single { it.factor == RegimeCauseFactor.Growth }

        assertEquals(0.0, growth.weight)
    }

    /**
     * The causes list keeps at most three, and only those past a magnitude and a weight cut. A
     * study that correlated it would be correlating what survived a filter, so the terms must
     * outnumber it on a symbol where the filter bites.
     */
    @Test
    fun the_terms_report_more_than_the_ranked_causes_keep() {
        var fundamentals = qualityFundamentals("Technology")
        var terms = regimeFitTerms(fundamentals, stretchedChart(), deploy())
        var causes = scoreRegimeFit(fundamentals, stretchedChart(), deploy()).causes

        assertEquals(true, terms.size > causes.size)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** Trend at full weight, anti-extension near zero — the stance that leans into a rally. */
    private fun deploy() = requireNotNull(
        RegimeScoringPolicy.fromRegime(
            MarketRegime(
                primaryRegime = "Bull",
                environmentBand = "RiskOn",
                actionStance = "Deploy",
                globalConfidenceBps = 8_000,
            ),
        ),
    )

    private fun defend() = requireNotNull(
        RegimeScoringPolicy.fromRegime(
            MarketRegime(
                primaryRegime = "Correction",
                environmentBand = "RiskOff",
                actionStance = "Defend",
                globalConfidenceBps = 8_000,
            ),
        ),
    )

    private fun qualityFundamentals(sector: String? = null) = FundamentalSnapshot(
        symbol = "GOOD",
        sectorName = sector,
        marketCapDollars = 10_000_000_000L,
        returnOnEquityBps = 2_000,
        debtToEquityHundredths = 30,
        freeCashFlowDollars = 800_000_000L,
        operatingCashFlowDollars = 1_000_000_000L,
        betaMillis = 700,
    )

    /** Price above every average, near the top of its year, and hot on RSI. */
    private fun stretchedChart() = ChartRangeSummary(
        range = ChartRange.Year,
        capturedAt = 0L,
        candleCount = 260,
        latestCloseCents = 12_000L,
        ema20Cents = 11_000L,
        ema50Cents = 10_000L,
        ema200Cents = 8_000L,
        latestWilderRsi = 78.0,
        volumeRatioHundredths = 100,
        pos52wPct = 96.0,
        bbPercentB = 1.0,
    )
}
