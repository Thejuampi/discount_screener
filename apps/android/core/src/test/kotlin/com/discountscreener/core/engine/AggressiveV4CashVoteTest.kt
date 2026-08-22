package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One fact family, one vote.
 *
 * The cash-flow vote and the conversion term both read free cash flow. When the yield vote has
 * fired — FCF over a known market cap — the conversion ratio re-reads the same dollars and adds
 * nothing this bucket did not already count, so it stays silent. When only the sign voted (no
 * market cap), the ratio still says something new about quality of earnings, so it keeps its vote.
 */
class AggressiveV4CashVoteTest {

    /** FCF yield voted; conversion would be the same dollars a second time. */
    @Test
    fun the_yield_vote_retires_the_conversion_vote() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(
                marketCapDollars = 200_000_000_000,
                freeCashFlowDollars = 8_000_000_000,
                operatingCashFlowDollars = 11_000_000_000,
            )),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertTrue("FCFy" in keys)
        assertFalse("Conv" in keys, "conversion after a yield vote is one fact counted twice: $keys")
    }

    /** No market cap means only the sign voted; the ratio still adds the quality reading. */
    @Test
    fun conversion_still_speaks_when_only_the_sign_voted() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(
                freeCashFlowDollars = 8_000_000_000,
                operatingCashFlowDollars = 11_000_000_000,
            )),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertTrue("FCF" in keys)
        assertTrue("Conv" in keys)
    }
}
