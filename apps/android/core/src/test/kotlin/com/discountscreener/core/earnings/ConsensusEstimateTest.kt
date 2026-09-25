package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConsensusEstimateTest {

    @Test
    fun the_quarter_that_is_reporting_is_the_one_that_comes_back() {
        assertEquals(CURRENT_QUARTER, consensus(lvs)?.period)
    }

    @Test
    fun the_consensus_eps_is_the_average_of_the_quarter_about_to_report() {
        assertEquals(0.62, consensus(lvs)?.avgEps)
    }

    @Test
    fun the_far_year_the_dcf_reads_is_a_different_number_from_the_same_answer() {
        assertEquals(2.94, consensus(lvs, period = "+1y")?.avgEps)
    }

    @Test
    fun the_spread_of_the_estimates_survives_for_the_surprise_denominator() {
        assertEquals(listOf(0.51, 0.74), listOf(consensus(lvs)?.lowEps, consensus(lvs)?.highEps))
    }

    @Test
    fun the_analyst_count_survives_for_the_surprise_denominator() {
        assertEquals(17, consensus(lvs)?.analystCount)
    }

    @Test
    fun the_revenue_consensus_reads_back_whole() {
        assertEquals(3_050_000_000.0, consensus(lvs)?.avgRevenue)
    }

    @Test
    fun the_period_end_date_reads_back_as_a_date() {
        assertEquals(LocalDate.of(2026, 9, 30), consensus(lvs)?.periodEndDate)
    }

    @Test
    fun a_missing_spread_never_costs_the_eps_consensus() {
        assertEquals(0.19, consensus(thin)?.avgEps)
    }

    @Test
    fun a_missing_spread_reads_back_as_missing_and_not_as_zero() {
        assertNull(consensus(thin)?.lowEps)
    }

    @Test
    fun a_period_the_answer_does_not_carry_refuses() {
        assertNull(consensus(lvs, period = "+5y"))
    }

    @Test
    fun an_answer_with_no_trend_block_refuses() {
        assertNull(consensus("""{"quoteSummary":{"result":[{"price":{}}],"error":null}}"""))
    }

    private fun consensus(body: String, period: String = CURRENT_QUARTER): ConsensusEstimate? =
        consensusOf(lenient.parseToJsonElement(body).jsonObject, period)

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    private val lvs: String = fixture("yahoo/earningsTrend/LVS.json")
    private val thin: String = fixture("yahoo/earningsTrend/THIN.json")

    private fun fixture(path: String): String =
        javaClass.classLoader!!.getResource(path)!!.readText()
}
