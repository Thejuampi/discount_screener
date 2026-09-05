package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlphaVantageEarningsTest {

    @Test
    fun the_ibm_earnings_body_names_the_latest_quarter_end() {
        assertEquals(LocalDate.of(2026, 6, 30), parseAlphaVantageEarnings(body("IBM-EARNINGS.json")).first().fiscalEnd)
    }

    @Test
    fun the_ibm_estimates_body_carries_the_high_for_that_quarter() {
        var june = parseAlphaVantageEstimates(body("IBM-EARNINGS_ESTIMATES.json"))
            .first { it.fiscalEnd == LocalDate.of(2026, 6, 30) }
        assertEquals(2.96, june.highEps)
    }

    @Test
    fun a_demo_key_refusal_is_not_a_history() {
        assertTrue(parseAlphaVantageEarnings(body("AAPL-EARNINGS.json")).isEmpty())
    }

    @Test
    fun a_missing_key_refusal_is_not_a_history() {
        assertEquals("missing_key", alphaVantageRefusal(body("empty-key-EARNINGS.json")))
    }

    @Test
    fun a_throttle_note_is_a_refusal() {
        assertEquals("throttled", alphaVantageRefusal("""{"Note":"Thank you for using Alpha Vantage!"}"""))
    }

    @Test
    fun joined_ibm_quarters_fill_the_sue_floor() {
        assertTrue(
            sueQuartersOf(body("IBM-EARNINGS.json"), body("IBM-EARNINGS_ESTIMATES.json")).size
                >= EarningsGatePolicy.current.minSueQuarters,
        )
    }

    @Test
    fun joined_ibm_quarters_score_sue_in_units_of_the_range() {
        var quarters = sueQuartersOf(body("IBM-EARNINGS.json"), body("IBM-EARNINGS_ESTIMATES.json"))
        var june = quarters.first { it.fiscalEnd == LocalDate.of(2026, 6, 30) }
        assertEquals(-1931, june.sueBps)
    }

    @Test
    fun a_panel_with_no_range_drops_the_quarter() {
        var earning = AlphaVantageEarning(
            fiscalEnd = LocalDate.of(2026, 6, 30),
            reportedOn = LocalDate.of(2026, 7, 22),
            actualEps = 2.93,
            meanEps = 2.93,
        )
        var estimate = AlphaVantageEstimate(
            fiscalEnd = LocalDate.of(2026, 6, 30),
            meanEps = 2.93,
            lowEps = 2.93,
            highEps = 2.93,
            analystCount = 1,
            meanRevenue = null,
        )
        assertTrue(sueQuartersOf(listOf(earning), listOf(estimate)).isEmpty())
    }

    private fun body(name: String): String =
        AlphaVantageEarningsTest::class.java.getResource("/alphavantage/$name")!!.readText()
}
