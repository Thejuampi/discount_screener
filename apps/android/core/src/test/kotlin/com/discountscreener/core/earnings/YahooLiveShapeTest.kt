package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class YahooLiveShapeTest {

    @Test
    fun the_live_chain_names_the_ticker_it_belongs_to() {
        assertEquals("LVS", chain()?.symbol)
    }

    @Test
    fun the_live_chain_lists_every_expiry_yahoo_quotes() {
        assertEquals(14, chain()?.expiries?.size)
    }

    @Test
    fun the_live_chain_starts_at_the_nearest_friday() {
        assertEquals(LocalDate.of(2026, 8, 28), chain()?.expiries?.first())
    }

    @Test
    fun the_live_chain_carries_the_price_of_the_underlying() {
        assertEquals(4_424L, chain()?.underlyingPriceCents)
    }

    @Test
    fun the_live_chain_keeps_only_the_strikes_quoted_on_both_sides() {
        assertEquals(15, chain()?.rows?.size)
    }

    @Test
    fun the_live_chain_prices_the_move_at_the_strike_nearest_the_price() {
        assertEquals(44.0, impliedMove(chain()!!.rows, forward = 44.24)?.strike)
    }

    @Test
    fun the_live_chain_prices_the_straddle_the_report_is_worth() {
        assertEquals(393, moveBps())
    }

    @Test
    fun the_live_chain_prices_the_put_that_would_cover_the_position() {
        assertEquals(84, hedge()?.protectivePutCostBps)
    }

    @Test
    fun a_chain_with_no_bid_under_the_money_quotes_no_spread() {
        assertNull(hedge()?.putSpreadCostBps)
    }

    @Test
    fun the_live_quote_summary_carries_the_quarter_about_to_report() {
        assertEquals(LocalDate.of(2026, 9, 30), consensus()?.periodEndDate)
    }

    @Test
    fun the_live_quote_summary_carries_the_consensus_eps() {
        assertEquals(0.75134, consensus()?.avgEps)
    }

    @Test
    fun the_live_quote_summary_carries_the_analyst_count() {
        assertEquals(13, consensus()?.analystCount)
    }

    @Test
    fun the_live_quote_summary_carries_the_revenue_consensus() {
        assertEquals(3_409_917_600.0, consensus()?.avgRevenue)
    }

    @Test
    fun the_live_chain_reports_how_wide_it_is_quoted() {
        assertEquals(15_402, impliedMove(chain()!!.rows, forward = 44.24)?.quoteSpreadBps)
    }

    @Test
    fun a_chain_quoted_that_wide_never_decides_anything() {
        assertEquals(DecisionCell.Undecided, decisionOf(livePre()).cell)
    }

    private fun livePre() = preReportOf(
        symbol = "LVS",
        reportDate = LocalDate.of(2026, 10, 21),
        timing = ReportTiming.AfterClose,
        priceCents = 4_424L,
        dcf = DcfAsOf(fairValueCents = 6_000L, computedOn = LocalDate.of(2026, 8, 27)),
        chain = chain(),
        pastAbnormalReturnsBps = listOf(-520, -300, 410, 640),
    )

    private fun chain() = parseOptionChain(fixture("yahoo/options/LVS-live-2026-08-27.json"))

    private fun moveBps(): Int? = impliedMove(chain()!!.rows, forward = 44.24)
        ?.fraction?.let { (it * 10_000.0).toInt() }

    private fun hedge(): HedgeQuote? {
        var rows = chain()!!.rows
        return hedgeQuoteOf(rows, impliedMove(rows, forward = 44.24)!!, forward = 44.24)
    }

    private fun consensus() = consensusOf(
        lenient.parseToJsonElement(fixture("yahoo/earningsTrend/LVS-live-2026-08-27.json")).jsonObject,
    )

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(path: String): String =
        javaClass.classLoader!!.getResource(path)!!.readText()
}
