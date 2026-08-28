package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YahooOptionChainTest {

    @Test
    fun the_chain_names_the_ticker_it_belongs_to() {
        assertEquals("LVS", parseOptionChain(chainBody)?.symbol)
    }

    @Test
    fun the_expiry_dates_read_back_in_order() {
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 21),
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 9, 18),
            ),
            parseOptionChain(chainBody)?.expiries,
        )
    }

    @Test
    fun the_ladder_that_came_back_is_the_expiry_that_was_asked_for() {
        assertEquals(LocalDate.of(2026, 8, 28), parseOptionChain(chainBody)?.expiry)
    }

    @Test
    fun a_strike_quoted_on_one_side_only_never_reaches_the_ladder() {
        assertEquals(
            listOf(42.0, 43.0, 44.0, 44.5, 45.0),
            parseOptionChain(chainBody)?.rows?.map { it.strike },
        )
    }

    @Test
    fun each_row_carries_the_two_sides_of_its_own_strike() {
        var row = parseOptionChain(chainBody)?.rows?.first { it.strike == 44.0 }

        assertEquals(ChainRow(44.0, OptionQuote(1.60, 1.72), OptionQuote(1.38, 1.50)), row)
    }

    @Test
    fun the_underlying_price_reads_back_in_cents() {
        assertEquals(4_424L, parseOptionChain(chainBody)?.underlyingPriceCents)
    }

    @Test
    fun the_parsed_ladder_prices_the_move_the_report_is_worth() {
        var chain = parseOptionChain(chainBody)!!

        assertEquals(3.10, impliedMove(chain.rows, forward = 44.24)?.straddlePrice)
    }

    @Test
    fun the_at_the_money_strike_of_this_chain_is_the_one_nearest_the_price() {
        var chain = parseOptionChain(chainBody)!!

        assertEquals(44.0, impliedMove(chain.rows, forward = 44.24)?.strike)
    }

    @Test
    fun a_body_that_is_not_a_chain_refuses() {
        assertNull(parseOptionChain("""{"optionChain":{"result":[],"error":"Not Found"}}"""))
    }

    @Test
    fun a_body_that_is_not_json_refuses() {
        assertNull(parseOptionChain("<html>429 Too Many Requests</html>"))
    }

    @Test
    fun an_answer_that_lists_dates_but_carries_no_ladder_still_reads_its_dates() {
        var body = """{"optionChain":{"result":[{"underlyingSymbol":"LVS",
            "expirationDates":[1787875200],"options":[]}]}}"""

        assertTrue(parseOptionChain(body)!!.rows.isEmpty())
    }

    private val chainBody: String = javaClass.classLoader!!
        .getResource("yahoo/options/LVS-2026-08-28.json")!!
        .readText()
}
