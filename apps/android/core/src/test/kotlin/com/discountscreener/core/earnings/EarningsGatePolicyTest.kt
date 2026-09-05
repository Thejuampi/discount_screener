package com.discountscreener.core.earnings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EarningsGatePolicyTest {

    @Test
    fun the_book_reads_the_high_risk_threshold() {
        var book = EarningsGatePolicyBook.parse(SAMPLE.replace("13000", "11111"))
        assertEquals(11111, book.highRiskRatioBps)
    }

    @Test
    fun a_missing_required_key_fails_closed() {
        assertFailsWith<IllegalStateException> {
            EarningsGatePolicyBook.parse("version: earnings-gate-policy/1\n")
        }
    }

    @Test
    fun a_patched_high_risk_threshold_moves_the_cut() {
        var patched = EarningsGatePolicy.current.copy(highRiskRatioBps = 20_000)
        EarningsGatePolicy.use(patched) {
            assertEquals(EventRisk.Normal, eventRiskOf(15_000))
        }
    }

    @Test
    fun a_patched_cheap_cut_moves_the_cell() {
        var patched = EarningsGatePolicy.current.copy(cheapPriceToFairBps = 5_000)
        EarningsGatePolicy.use(patched) {
            assertEquals(
                DecisionCell.ExpensiveNormalRisk,
                decisionOf(pre(price = 3_600L, ratio = 10_000)).cell,
            )
        }
    }

    private fun pre(price: Long, ratio: Int) = PreReport(
        symbol = "LVS",
        reportEpochDay = 20_692L,
        timing = ReportTiming.AfterClose,
        priceCents = price,
        dcfFairValueCents = 4_000L,
        riskRatioBps = ratio,
        putSpreadCostBps = 80,
    )
}

private const val SAMPLE = """
version: earnings-gate-policy/1
high_risk_ratio_bps: 13000
low_risk_ratio_bps: 8000
cheap_price_to_fair_bps: 9000
hedge_cost_cap_bps: 100
protective_put_cost_cap_bps: 150
max_quote_spread_bps: 5000
min_sue_quarters: 16
av_daily_limit: 25
av_per_minute: 5
av_cache_fresh_days: 7
sue_match_days: 7
min_revenue_trail_quarters: 4
revenue_override_z_bps: 10000
"""
