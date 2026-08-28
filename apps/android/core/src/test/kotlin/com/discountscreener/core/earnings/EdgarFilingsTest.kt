package com.discountscreener.core.earnings

import com.discountscreener.core.math.medianOf
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class EdgarFilingsTest {

    @Test
    fun the_filing_list_yields_one_announcement_per_quarter() {
        assertEquals(20, parseEarningsAnnouncements(submissions).size)
    }

    @Test
    fun the_announcements_read_back_oldest_first() {
        assertEquals(LocalDate.of(2021, 9, 2), parseEarningsAnnouncements(submissions).first().date)
    }

    @Test
    fun the_newest_announcement_is_the_last_report_this_ticker_filed() {
        assertEquals(LocalDate.of(2026, 6, 3), parseEarningsAnnouncements(submissions).last().date)
    }

    @Test
    fun a_filing_accepted_after_the_bell_reads_as_an_after_close_report() {
        assertEquals(ReportTiming.AfterClose, parseEarningsAnnouncements(submissions).last().timing)
    }

    @Test
    fun a_filing_accepted_before_the_bell_reads_as_a_before_open_report() {
        assertEquals(ReportTiming.BeforeOpen, parseEarningsAnnouncements(beforeOpen).single().timing)
    }

    @Test
    fun a_filing_accepted_while_the_market_trades_refuses_to_guess_its_timing() {
        assertEquals(ReportTiming.Unknown, parseEarningsAnnouncements(midSession).single().timing)
    }

    @Test
    fun a_report_released_after_hours_belongs_to_the_day_the_archive_accepted_it() {
        assertEquals(LocalDate.of(2026, 6, 3), parseEarningsAnnouncements(lateFiling).single().date)
    }

    @Test
    fun a_report_released_after_hours_still_reads_as_an_after_close_report() {
        assertEquals(ReportTiming.AfterClose, parseEarningsAnnouncements(lateFiling).single().timing)
    }

    @Test
    fun a_filing_with_no_acceptance_time_falls_back_to_the_day_the_archive_stamped() {
        assertEquals(LocalDate.of(2026, 6, 3), parseEarningsAnnouncements(noAcceptance).single().date)
    }

    @Test
    fun a_filing_with_no_acceptance_time_refuses_to_guess_its_timing() {
        assertEquals(ReportTiming.Unknown, parseEarningsAnnouncements(noAcceptance).single().timing)
    }

    @Test
    fun a_form_that_is_not_the_results_filing_never_counts_as_a_report() {
        assertTrue(parseEarningsAnnouncements(tenQOnly).isEmpty())
    }

    @Test
    fun an_eight_k_without_the_results_item_never_counts_as_a_report() {
        assertTrue(parseEarningsAnnouncements(otherItems).isEmpty())
    }

    @Test
    fun an_item_that_only_starts_with_the_results_code_never_counts_as_a_report() {
        assertTrue(parseEarningsAnnouncements(lookalikeItem).isEmpty())
    }

    @Test
    fun two_filings_on_one_day_stay_one_report() {
        assertEquals(1, parseEarningsAnnouncements(sameDayTwice).size)
    }

    @Test
    fun a_body_that_is_not_json_refuses() {
        assertTrue(parseEarningsAnnouncements("<html>429 Too Many Requests</html>").isEmpty())
    }

    @Test
    fun a_body_with_no_filing_block_refuses() {
        assertTrue(parseEarningsAnnouncements("""{"cik":1730168}""").isEmpty())
    }

    @Test
    fun every_announcement_the_price_series_covers_prices_its_own_reaction() {
        assertEquals(20, reactions().size)
    }

    @Test
    fun the_reaction_the_index_did_not_explain_reads_back_in_basis_points() {
        assertEquals(120, reactions().first())
    }

    @Test
    fun the_worst_reaction_of_this_ticker_survives_into_the_history() {
        assertEquals(-1_297, reactions().last())
    }

    @Test
    fun the_history_gives_the_denominator_the_risk_score_asks_for() {
        assertEquals(584.0, medianOf(reactions().map { abs(it.toDouble()) }))
    }

    @Test
    fun a_ticker_with_no_price_series_yields_no_history() {
        assertTrue(pastAbnormalReturnsOf(parseEarningsAnnouncements(submissions), emptyList(), spy).isEmpty())
    }

    @Test
    fun a_run_with_no_index_series_yields_no_history() {
        assertTrue(pastAbnormalReturnsOf(parseEarningsAnnouncements(submissions), avgo, emptyList()).isEmpty())
    }

    @Test
    fun an_announcement_the_price_series_never_reached_is_dropped_and_not_zeroed() {
        var future = listOf(EarningsAnnouncement(LocalDate.of(2030, 1, 7), ReportTiming.AfterClose))

        assertTrue(pastAbnormalReturnsOf(future, avgo, spy).isEmpty())
    }

    private fun reactions(): List<Int> =
        pastAbnormalReturnsOf(parseEarningsAnnouncements(submissions), avgo, spy)

    private fun filings(rows: String): String =
        """{"cik":1730168,"filings":{"recent":$rows}}"""

    private val beforeOpen = filings(
        """{"form":["8-K"],"filingDate":["2026-06-03"],"items":["2.02,9.01"],
        "acceptanceDateTime":["2026-06-03T11:05:00.000Z"]}""",
    )

    private val midSession = filings(
        """{"form":["8-K"],"filingDate":["2026-06-03"],"items":["2.02,9.01"],
        "acceptanceDateTime":["2026-06-03T17:05:00.000Z"]}""",
    )

    private val noAcceptance = filings(
        """{"form":["8-K"],"filingDate":["2026-06-03"],"items":["2.02,9.01"],
        "acceptanceDateTime":[""]}""",
    )

    private val lateFiling = filings(
        """{"form":["8-K"],"filingDate":["2026-06-04"],"items":["2.02,9.01"],
        "acceptanceDateTime":["2026-06-03T22:05:00.000Z"]}""",
    )

    private val tenQOnly = filings(
        """{"form":["10-Q"],"filingDate":["2026-06-09"],"items":["2.02"],
        "acceptanceDateTime":["2026-06-09T20:21:35.000Z"]}""",
    )

    private val otherItems = filings(
        """{"form":["8-K"],"filingDate":["2026-06-03"],"items":["5.02,9.01"],
        "acceptanceDateTime":["2026-06-03T20:21:35.000Z"]}""",
    )

    private val lookalikeItem = filings(
        """{"form":["8-K"],"filingDate":["2026-06-03"],"items":["2.021,9.01"],
        "acceptanceDateTime":["2026-06-03T20:21:35.000Z"]}""",
    )

    private val sameDayTwice = filings(
        """{"form":["8-K","8-K"],"filingDate":["2026-06-03","2026-06-03"],
        "items":["2.02,9.01","2.02,8.01"],
        "acceptanceDateTime":["2026-06-03T20:21:35.000Z","2026-06-03T21:40:00.000Z"]}""",
    )

    private val submissions: String = fixture("edgar/AVGO-submissions-2026-08-28.json")
    private val avgo: List<DailyClose> = closes("edgar/AVGO-closes-2026-08-28.json")
    private val spy: List<DailyClose> = closes("edgar/SPY-closes-2026-08-28.json")

    private fun closes(path: String): List<DailyClose> =
        Json.decodeFromString(ListSerializer(SavedClose.serializer()), fixture(path))
            .map { DailyClose(LocalDate.parse(it.date), it.closeCents) }

    private fun fixture(path: String): String =
        javaClass.classLoader!!.getResource(path)!!.readText()

    @Serializable
    private data class SavedClose(val date: String, val closeCents: Long)
}
