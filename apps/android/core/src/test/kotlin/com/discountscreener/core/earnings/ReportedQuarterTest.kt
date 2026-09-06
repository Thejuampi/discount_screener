package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ReportedQuarterTest {

    private val live = readResource("/yahoo/earningsHistory/AVGO-2026-08-28.json")

    @Test
    fun every_quarter_yahoo_has_already_reported_is_read() {
        assertEquals(4, quarters().size)
    }

    @Test
    fun the_quarters_come_back_oldest_first() {
        assertEquals(LocalDate.parse("2025-07-31"), quarters().first().quarterEndDate)
    }

    @Test
    fun the_newest_quarter_carries_the_eps_the_company_reported() {
        assertEquals(2.44, quarters().last().epsActual)
    }

    @Test
    fun the_newest_quarter_carries_the_estimate_it_was_measured_against() {
        assertEquals(2.39835, quarters().last().epsEstimate)
    }

    @Test
    fun the_revenue_of_the_same_quarter_rides_along() {
        assertEquals(22_187_000_000.0, quarters().last().revenueActual)
    }

    @Test
    fun the_revenue_is_matched_to_its_own_quarter_and_not_to_the_newest_one() {
        assertEquals(15_952_000_000.0, quarters().first().revenueActual)
    }

    @Test
    fun a_report_settles_against_the_quarter_that_closed_before_it() {
        assertEquals(
            LocalDate.parse("2026-04-30"),
            quarterReportedOn(quarters(), LocalDate.parse("2026-06-03"))?.quarterEndDate,
        )
    }

    @Test
    fun a_report_never_settles_against_a_quarter_that_has_not_closed_yet() {
        assertEquals(
            LocalDate.parse("2026-01-31"),
            quarterReportedOn(quarters(), LocalDate.parse("2026-03-05"))?.quarterEndDate,
        )
    }

    @Test
    fun a_quarter_too_old_to_be_the_one_reported_is_left_alone() {
        assertNull(quarterReportedOn(quarters(), LocalDate.parse("2027-06-03")))
    }

    @Test
    fun a_report_older_than_every_quarter_on_file_settles_against_none() {
        assertNull(quarterReportedOn(quarters(), LocalDate.parse("2024-01-01")))
    }

    @Test
    fun a_body_with_no_history_reports_no_quarter() {
        assertEquals(emptyList(), reportedQuartersOf(parse("""{"quoteSummary":{"result":[{}]}}""")))
    }

    @Test
    fun a_body_that_is_not_a_quote_summary_reports_no_quarter() {
        assertEquals(emptyList(), reportedQuartersOf(parse("""{"finance":{"error":"nope"}}""")))
    }

    @Test
    fun a_quarter_with_no_end_date_is_dropped_rather_than_guessed() {
        var body = """{"quoteSummary":{"result":[{"earningsHistory":{"history":[
            {"epsActual":{"raw":1.0}}]}}]}}"""

        assertEquals(emptyList(), reportedQuartersOf(parse(body)))
    }

    @Test
    fun a_quarter_that_reported_no_eps_still_carries_its_date() {
        var body = """{"quoteSummary":{"result":[{"earningsHistory":{"history":[
            {"quarter":{"fmt":"2026-04-30"},"epsActual":{}}]}}]}}"""

        assertNull(reportedQuartersOf(parse(body)).single().epsActual)
    }

    @Test
    fun a_revenue_of_zero_is_read_as_no_revenue_at_all() {
        var body = """{"quoteSummary":{"result":[{"earningsHistory":{"history":[
            {"quarter":{"fmt":"2026-04-30"},"epsActual":{"raw":2.44}}]},
            "incomeStatementHistoryQuarterly":{"incomeStatementHistory":[
            {"endDate":{"fmt":"2026-04-30"},"totalRevenue":{"raw":0}}]}}]}}"""

        assertNull(reportedQuartersOf(parse(body)).single().revenueActual)
    }

    @Test
    fun revenue_without_any_earnings_history_still_reaches_the_caller() {
        var body = """{"quoteSummary":{"result":[{"incomeStatementHistoryQuarterly":
            {"incomeStatementHistory":[{"endDate":{"fmt":"2026-04-30"},
            "totalRevenue":{"raw":22187000000}}]}}]}}"""

        assertEquals(22_187_000_000.0, reportedQuartersOf(parse(body)).single().revenueActual)
    }

    private fun quarters() = reportedQuartersOf(parse(live))

    private fun parse(body: String) = Json.parseToJsonElement(body).jsonObject

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)!!.bufferedReader().readText()
}
