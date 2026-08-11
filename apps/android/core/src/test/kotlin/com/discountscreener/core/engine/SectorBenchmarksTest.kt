package com.discountscreener.core.engine

import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A benchmark is a claim about a sector, and these tests are mostly about when the sector cannot
 * support one. A centre that is quietly produced from three symbols, or from a loss maker's
 * negative multiple, would be worse than no benchmark at all: the fundamentals bucket would score
 * against it with no way to tell that it did.
 */
class SectorBenchmarksTest {

    /**
     * One member trading at 900x does not make the sector expensive. A plain mean of this sector
     * is 16666 hundredths, which is a level none of the five real members is within reach of.
     */
    @Test
    fun one_extreme_member_does_not_become_the_sector_level() {
        var details = listOf(1_800, 1_900, 2_000, 2_100, 2_200, 90_000)
            .mapIndexed { index, pe -> detail("S$index", forwardPeHundredths = pe) }

        assertEquals(2_000, computeSectorBenchmarks(details)["Technology"]?.forwardPeHundredths)
    }

    /**
     * Four is a handful of companies, not an industry. The centre of four moves when one symbol
     * joins the universe, so it is refused and the caller falls back to the absolute band.
     */
    @Test
    fun a_sector_of_four_usable_values_is_not_a_benchmark() {
        var details = listOf(1_800, 1_900, 2_000, 2_100)
            .mapIndexed { index, pe -> detail("S$index", forwardPeHundredths = pe) }

        assertNull(computeSectorBenchmarks(details)["Technology"]?.forwardPeHundredths)
    }

    /** A symbol whose provider sent no sector belongs to no sector, and is not a sector of one. */
    @Test
    fun a_symbol_with_no_sector_name_is_in_no_sector() {
        var details = (0..5).map { detail("S$it", sector = null, forwardPeHundredths = 2_000) }

        assertEquals(emptySet(), computeSectorBenchmarks(details).keys)
    }

    /**
     * A loss maker's forward P/E is not a cheap price, and a negative book is not a cheap book, so
     * neither counts toward the level a buyer would pay. Return on equity is the other kind of
     * number: it crosses zero honestly, and a sector of loss makers really does centre below it.
     *
     * The negative multiples are spread out on purpose. Five identical ones would have no width
     * through their middle, so they would be refused for having no scale and the test would pass
     * whether or not anything filtered them — it would measure the wrong guard.
     */
    @Test
    fun a_multiple_at_or_below_zero_is_not_a_price_level_but_a_negative_return_is_a_level() {
        var details = listOf(-500, -400, -300, -200, -100).mapIndexed { index, roe ->
            detail("S$index", forwardPeHundredths = roe * 3, returnOnEquityBps = roe)
        }

        var benchmarks = computeSectorBenchmarks(details)["Technology"]

        assertEquals(
            listOf(null, -300),
            listOf(benchmarks?.forwardPeHundredths, benchmarks?.returnOnEquityBps),
        )
    }

    private fun detail(
        symbol: String,
        sector: String? = "Technology",
        forwardPeHundredths: Int? = null,
        returnOnEquityBps: Int? = null,
    ) = SymbolDetail(
        symbol = symbol,
        profitable = true,
        marketPriceCents = 19_950,
        intrinsicValueCents = 25_000,
        gapBps = 2_000,
        minimumGapBps = 1_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
        fundamentals = FundamentalSnapshot(
            symbol = symbol,
            sectorName = sector,
            forwardPeHundredths = forwardPeHundredths,
            returnOnEquityBps = returnOnEquityBps,
        ),
    )
}
