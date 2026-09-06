package com.discountscreener.core.earnings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EarningsGatePolicyTest {

    @Test
    fun the_book_reads_min_sue_quarters() {
        var book = EarningsGatePolicyBook.parse(SAMPLE.replace("16", "20"))
        assertEquals(20, book.minSueQuarters)
    }

    @Test
    fun a_missing_required_key_fails_closed() {
        assertFailsWith<IllegalStateException> {
            EarningsGatePolicyBook.parse("version: earnings-gate-policy/2\n")
        }
    }

    @Test
    fun the_book_sets_the_trail_window() {
        var book = EarningsGatePolicyBook.parse(
            SAMPLE.replace("min_revenue_trail_quarters: 4", "min_revenue_trail_quarters: 3"),
        )
        EarningsGatePolicy.use(book) {
            assertEquals(0L, revenueTrailOf(listOf(100L, 100L, 50L))!!.scaleCents)
        }
    }

    @Test
    fun leftover_percent_keys_do_not_move_the_cell() {
        var book = EarningsGatePolicyBook.parse(
            SAMPLE + "\ncheap_price_to_fair_bps: 5000\nhigh_risk_ratio_bps: 20000\n",
        )
        EarningsGatePolicy.use(book) {
            assertEquals(
                DecisionCell.CheapHighRisk,
                decisionOf(
                    PreReport(
                        symbol = "LVS",
                        reportEpochDay = 20_692L,
                        timing = ReportTiming.AfterClose,
                        priceCents = 3_800L,
                        dcfFairValueCents = 4_000L,
                        impliedMoveBps = 700,
                        eventImpliedMoveBps = 700,
                        riskRatioBps = 11_000,
                        putSpreadCostBps = 80,
                    ),
                ).cell,
            )
        }
    }
}

private const val SAMPLE = """
version: earnings-gate-policy/2
min_sue_quarters: 16
av_daily_limit: 25
av_per_minute: 5
av_cache_fresh_days: 7
sue_match_days: 7
min_revenue_trail_quarters: 4
"""
