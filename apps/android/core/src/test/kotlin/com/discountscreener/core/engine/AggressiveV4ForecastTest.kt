package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.SymbolDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V4 forecast is one Street family, not six Street tags.
 *
 * Coverage and freshness already scale reliability. Paying them again as points is how a
 * 54% target book and a refused model still printed Fc 38 · Good (STZ, 2026-08-12).
 */
class AggressiveV4ForecastTest {

    @Test
    fun a_wide_street_book_is_good_under_v3() {
        assertEquals(ScoreReading.Good, scoreReading(v3(wideStreetBook()).score!!))
    }

    @Test
    fun a_wide_street_book_is_not_good_under_v4() {
        assertTrue(v4(wideStreetBook()).score!! < SCORE_READING_GOOD)
    }

    @Test
    fun v4_does_not_pay_points_for_coverage_or_freshness() {
        var keys = v4(wideStreetBook()).factors.map { it.key }

        assertEquals(emptyList(), keys.filter { it == "Cov" || it == "Fresh" })
    }

    @Test
    fun the_street_term_carries_the_upside_it_scored() {
        var factor = v4(wideStreetBook()).factors.single { it.key == "Val" }

        assertEquals(2_914, factor.inputBps)
    }

    @Test
    fun a_tight_street_book_with_the_same_upside_can_still_read_good() {
        assertEquals(ScoreReading.Good, scoreReading(v4(tightStreetBook()).score!!))
    }

    @Test
    fun bipolar_tails_score_below_the_same_net_with_empty_tails() {
        var sameNet = wideStreetBook().copy(
            strongBuyCount = 0,
            buyCount = 14,
            holdCount = 8,
            sellCount = 2,
            strongSellCount = 0,
        )
        var bipolar = sameNet.copy(
            strongBuyCount = 3,
            buyCount = 11,
            sellCount = 0,
            strongSellCount = 2,
        )

        assertTrue(v4(bipolar).score!! < v4(sameNet).score!!)
    }

    @Test
    fun v4_forecast_ignores_quant_engine_analysis() {
        var detail = wideStreetBook()
        var without = v4(detail, analysis = null).score
        var withHuge = v4(detail, analysis = dcf(base = 80_000, bear = 40_000, bull = 160_000)).score

        assertEquals(without, withHuge)
    }

    @Test
    fun v3_still_emits_coverage_and_freshness() {
        var keys = v3(wideStreetBook()).factors.map { it.key }

        assertEquals(listOf("Cov", "Fresh"), keys.filter { it == "Cov" || it == "Fresh" })
    }

    /**
     * STZ-shaped Street tape: +29% target, $115–$209 book, 24 opinions.
     * The live row printed Val +17, Cov +14, Skew +6, Unc −6, Fresh +4, Rec +3.
     */
    private fun wideStreetBook() = baseDetail(
        marketPriceCents = 13_396,
        weightedExternalSignalFairValueCents = 17_300,
        externalSignalLowFairValueCents = 11_500,
        externalSignalHighFairValueCents = 20_900,
        analystOpinionCount = 24,
        recommendationMeanHundredths = 206,
        strongBuyCount = 8,
        buyCount = 10,
        holdCount = 6,
        sellCount = 0,
        strongSellCount = 0,
        externalSignalAgeSeconds = 0,
    )

    /** Same upside and coverage, range about 12% of the centre. */
    private fun tightStreetBook() = wideStreetBook().copy(
        externalSignalLowFairValueCents = 16_300,
        externalSignalHighFairValueCents = 18_300,
    )

    private fun v3(detail: SymbolDetail, analysis: DcfAnalysis? = null) =
        OpportunityEngine.aggressiveV3ForecastScore(detail, analysis)

    private fun v4(detail: SymbolDetail, analysis: DcfAnalysis? = null) =
        OpportunityEngine.aggressiveV4ForecastScore(detail, analysis)
}
