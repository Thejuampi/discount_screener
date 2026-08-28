package com.discountscreener.android.data.earnings

import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.core.earnings.ChainRow
import com.discountscreener.core.earnings.ConsensusEstimate
import com.discountscreener.core.earnings.DailyClose
import com.discountscreener.core.earnings.DecisionCell
import com.discountscreener.core.earnings.EarningsAnnouncement
import com.discountscreener.core.earnings.EarningsEventLog
import com.discountscreener.core.earnings.OptionQuote
import com.discountscreener.core.earnings.OptionChainSnapshot
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.PostReport
import com.discountscreener.core.earnings.PreReport
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.earnings.ReportedQuarter
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class EarningsEventRecorderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun a_report_inside_the_window_is_written_to_the_log() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 3)))

        assertEquals("LVS", log.read().events.single().pre.symbol)
    }

    @Test
    fun the_written_block_carries_the_move_the_market_pays_for() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 3)))

        assertEquals(701, log.read().events.single().pre.impliedMoveBps)
    }

    @Test
    fun the_written_block_carries_the_price_of_the_hedge_the_chain_quoted() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 3)))

        assertEquals(95, log.read().events.single().pre.putSpreadCostBps)
    }

    @Test
    fun the_written_block_carries_the_strike_the_hedge_is_sold_at() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 3)))

        assertEquals(4_300L, log.read().events.single().pre.hedgeShortStrikeCents)
    }

    @Test
    fun the_written_block_carries_the_quiet_day_move_of_the_ticker() = runTest {
        var log = log()

        recorder(log, closes = quietDays()).capture(listOf(row(earningsIn = 3)))

        assertEquals(100, log.read().events.single().pre.normalDailyMoveBps)
    }

    @Test
    fun the_written_block_prices_the_event_under_the_move_priced_to_the_expiry() = runTest {
        var log = log()

        recorder(log, closes = quietDays()).capture(listOf(row(earningsIn = 3)))

        assertEquals(694, log.read().events.single().pre.eventImpliedMoveBps)
    }

    @Test
    fun the_written_block_carries_the_fair_value_the_decision_had() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 3)))

        assertEquals(42_633L, log.read().events.single().pre.dcfFairValueCents)
    }

    @Test
    fun the_written_block_carries_the_consensus_of_the_reporting_quarter() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 3)))

        assertEquals(62L, log.read().events.single().pre.consensusEpsCents)
    }

    @Test
    fun a_report_too_far_out_is_left_for_a_later_pass() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 40)))

        assertTrue(log.read().events.isEmpty())
    }

    @Test
    fun a_report_already_past_is_never_captured_after_the_fact() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = -2)))

        assertTrue(log.read().events.isEmpty())
    }

    @Test
    fun a_row_with_no_earnings_date_costs_no_network_call() = runTest {
        var chains = CountingChains()

        recorder(log(), chains = chains).capture(listOf(row(earningsIn = null)))

        assertEquals(0, chains.calls)
    }

    @Test
    fun a_second_pass_over_the_same_report_costs_no_second_network_call() = runTest {
        var chains = CountingChains(chain)
        var recorder = recorder(log(), chains = chains)
        recorder.capture(listOf(row(earningsIn = 3)))
        var afterFirst = chains.calls

        recorder.capture(listOf(row(earningsIn = 3)))

        assertEquals(afterFirst, chains.calls)
    }

    @Test
    fun a_second_pass_keeps_the_price_the_first_one_recorded() = runTest {
        var log = log()
        var recorder = recorder(log)
        recorder.capture(listOf(row(earningsIn = 3)))

        recorder.capture(listOf(row(earningsIn = 3, priceCents = 9_999L)))

        assertEquals(4_424L, log.read().events.single().pre.priceCents)
    }

    @Test
    fun a_provider_that_fails_never_stops_the_rows_behind_it() = runTest {
        var log = log()

        recorder(log, chains = { symbol, _ -> if (symbol == "LVS") error("boom") else chain })
            .capture(listOf(row(earningsIn = 3), row(earningsIn = 3, symbol = "WYNN")))

        assertEquals("WYNN", log.read().events.single().pre.symbol)
    }

    @Test
    fun a_provider_that_fails_reports_the_events_it_did_write() = runTest {
        var written = recorder(log(), chains = { _, _ -> error("boom") })
            .capture(listOf(row(earningsIn = 3)))

        assertEquals(0, written)
    }

    @Test
    fun a_symbol_with_no_chain_is_still_recorded_without_a_strike() = runTest {
        var log = log()

        recorder(log, chains = { _, _ -> null }).capture(listOf(row(earningsIn = 3)))

        assertNull(log.read().events.single().pre.strikeCents)
    }

    @Test
    fun a_ticker_with_no_settled_history_yet_carries_no_risk_ratio() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 3)))

        assertNull(log.read().events.single().pre.riskRatioBps)
    }

    @Test
    fun a_report_yahoo_dates_inside_the_session_is_still_recorded() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 3, atHour = 12)))

        assertEquals("LVS", log.read().events.single().pre.symbol)
    }

    @Test
    fun a_report_yahoo_dates_inside_the_session_keeps_its_timing_unknown() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 3, atHour = 12)))

        assertEquals(ReportTiming.Unknown, log.read().events.single().pre.timing)
    }

    @Test
    fun a_settled_reaction_of_the_same_ticker_feeds_the_risk_ratio() = runTest {
        var log = log()
        log.append(settledEvent("LVS", day = TODAY.minusDays(90).toEpochDay(), abnormalReturnBps = 400))

        recorder(log).capture(listOf(row(earningsIn = 3)))

        assertEquals(17_525, log.event("LVS", TODAY.plusDays(3).toEpochDay())?.pre?.riskRatioBps)
    }

    @Test
    fun a_report_that_already_happened_is_settled_with_its_own_reaction() = runTest {
        var log = log()
        log.append(pastEvent("LVS", day = TODAY.minusDays(3).toEpochDay()))

        recorder(log, closes = closeSource()).capture(emptyList())

        assertEquals(500, log.event("LVS", TODAY.minusDays(3).toEpochDay())?.post?.stockReturnBps)
    }

    @Test
    fun the_reaction_the_index_shared_is_taken_out_of_the_move() = runTest {
        var log = log()
        log.append(pastEvent("LVS", day = TODAY.minusDays(3).toEpochDay()))

        recorder(log, closes = closeSource()).capture(emptyList())

        assertEquals(300, log.event("LVS", TODAY.minusDays(3).toEpochDay())?.post?.abnormalReturnBps)
    }

    @Test
    fun the_quarter_the_company_reported_is_written_beside_its_reaction() = runTest {
        var log = log()
        log.append(pastEvent("LVS", day = TODAY.minusDays(3).toEpochDay()))

        recorder(log, closes = closeSource(), reported = quarters(endedDaysAgo = 33)).capture(emptyList())

        assertEquals(74L, log.event("LVS", TODAY.minusDays(3).toEpochDay())?.post?.epsActualCents)
    }

    @Test
    fun the_beat_over_the_consensus_is_scored_when_the_report_settles() = runTest {
        var log = log()
        log.append(estimatedEvent("LVS", day = TODAY.minusDays(3).toEpochDay()))

        recorder(log, closes = closeSource(), reported = quarters(endedDaysAgo = 33)).capture(emptyList())

        assertEquals(10_435, log.event("LVS", TODAY.minusDays(3).toEpochDay())?.post?.surpriseScoreBps)
    }

    @Test
    fun a_filing_archive_that_fails_never_costs_the_report_its_reaction() = runTest {
        var log = log()
        log.append(pastEvent("LVS", day = TODAY.minusDays(3).toEpochDay()))
        var angry = EarningsEventRecorder.ReportedQuarterSource { error("no actuals today") }

        recorder(log, closes = closeSource(), reported = angry).capture(emptyList())

        assertEquals(300, log.event("LVS", TODAY.minusDays(3).toEpochDay())?.post?.abnormalReturnBps)
    }

    @Test
    fun a_settled_reaction_reaches_the_block_written_in_the_same_pass() = runTest {
        var log = log()
        log.append(pastEvent("LVS", day = TODAY.minusDays(3).toEpochDay()))

        recorder(log, closes = closeSource()).capture(listOf(row(earningsIn = 3)))

        assertEquals(300, log.event("LVS", TODAY.plusDays(3).toEpochDay())?.pre?.medianAbsoluteAbnormalReturnBps)
    }

    @Test
    fun a_report_settled_once_is_never_priced_again() = runTest {
        var log = log()
        log.append(settledEvent("LVS", day = TODAY.minusDays(3).toEpochDay(), abnormalReturnBps = 111))

        recorder(log, closes = closeSource()).capture(emptyList())

        assertEquals(111, log.event("LVS", TODAY.minusDays(3).toEpochDay())?.post?.abnormalReturnBps)
    }

    @Test
    fun a_report_too_old_to_price_costs_no_network_call() = runTest {
        var log = log()
        log.append(pastEvent("LVS", day = TODAY.minusDays(120).toEpochDay()))
        var closes = CountingCloses()

        recorder(log, closes = closes).capture(emptyList())

        assertEquals(0, closes.calls)
    }

    @Test
    fun a_close_provider_that_fails_never_costs_the_capture() = runTest {
        var log = log()
        log.append(pastEvent("LVS", day = TODAY.minusDays(3).toEpochDay()))

        recorder(log, closes = { _ -> error("boom") }).capture(listOf(row(earningsIn = 3)))

        assertEquals("LVS", log.event("LVS", TODAY.plusDays(3).toEpochDay())?.pre?.symbol)
    }

    @Test
    fun a_captured_event_is_written_with_the_cell_it_falls_in() = runTest {
        var log = log()
        log.append(settledEvent("LVS", day = TODAY.minusDays(90).toEpochDay(), abnormalReturnBps = 400))

        recorder(log).capture(listOf(row(earningsIn = 3)))

        assertEquals(
            DecisionCell.CheapHighRisk,
            log.event("LVS", TODAY.plusDays(3).toEpochDay())?.decision?.cell,
        )
    }

    @Test
    fun an_event_with_no_history_behind_it_is_written_undecided() = runTest {
        var log = log()

        recorder(log).capture(listOf(row(earningsIn = 3)))

        assertEquals(
            DecisionCell.Undecided,
            log.event("LVS", TODAY.plusDays(3).toEpochDay())?.decision?.cell,
        )
    }

    private fun log() = EarningsEventLog(File(folder.newFolder(), "events.jsonl"))

    private fun recorder(
        log: EarningsEventLog,
        chains: EarningsEventRecorder.OptionChainSource = EarningsEventRecorder.OptionChainSource { _, _ -> chain },
        closes: EarningsEventRecorder.CloseSource = EarningsEventRecorder.CloseSource { emptyList() },
        history: EarningsEventRecorder.CloseSource = closes,
        announcements: EarningsEventRecorder.AnnouncementSource =
            EarningsEventRecorder.AnnouncementSource { emptyList() },
        reported: EarningsEventRecorder.ReportedQuarterSource =
            EarningsEventRecorder.ReportedQuarterSource { emptyList() },
    ) = EarningsEventRecorder(
        log = log,
        chains = chains,
        consensus = { _ -> consensus },
        closes = closes,
        history = history,
        announcements = announcements,
        reported = reported,
        nowProvider = { TODAY.atStartOfDay(ZoneOffset.UTC).toEpochSecond() },
    )

    private fun quarters(endedDaysAgo: Long) = EarningsEventRecorder.ReportedQuarterSource {
        listOf(
            ReportedQuarter(
                quarterEndDate = TODAY.minusDays(endedDaysAgo),
                epsActual = 0.74,
                epsEstimate = 0.62,
                revenueActual = 3_355_000_000.0,
            ),
        )
    }

    private fun filed(vararg daysAgo: Long) = EarningsEventRecorder.AnnouncementSource { symbol ->
        if (symbol == "SPY") {
            emptyList()
        } else {
            daysAgo.map { EarningsAnnouncement(TODAY.minusDays(it), ReportTiming.AfterClose) }
        }
    }

    private fun reactingCloses(jumpBps: Int) = EarningsEventRecorder.CloseSource { symbol ->
        var start = TODAY.minusDays(400)
        var jumpDays = setOf(390L, 300L, 200L, 100L).map { TODAY.minusDays(it) }
        var level = 10_000.0
        (0..400).map { index ->
            var day = start.plusDays(index.toLong())
            if (symbol != "SPY" && jumpDays.any { it.plusDays(1) == day }) {
                level *= 1.0 + jumpBps / 10_000.0
            }
            DailyClose(day, level.toLong())
        }
    }

    private fun quietDays() = EarningsEventRecorder.CloseSource { symbol ->
        if (symbol == "SPY") {
            emptyList()
        } else {
            var start = TODAY.minusDays(40)
            (0 until 30).map { index ->
                DailyClose(start.plusDays(index.toLong()), if (index % 2 == 0) 4_000L else 4_040L)
            }
        }
    }

    private fun closesOf(vararg cents: Long, from: LocalDate) = cents
        .mapIndexed { index, value -> DailyClose(from.plusDays(index.toLong()), value) }

    private fun row(
        earningsIn: Long?,
        symbol: String = "LVS",
        priceCents: Long = 4_424L,
        atHour: Int = 17,
    ) = OpportunityListRow(
        symbol = symbol,
        marketPriceCents = priceCents,
        intrinsicValueCents = 42_633L,
        confidence = ConfidenceBand.Provisional,
        isWatched = false,
        compositeScore = 0,
        coverageCount = 0,
        nextEarningsEpoch = earningsIn?.let {
            TODAY.plusDays(it).atTime(atHour, 0).toInstant(ZoneOffset.ofHours(-4)).epochSecond
        },
    )

    private fun closeSource() = EarningsEventRecorder.CloseSource { symbol ->
        if (symbol == "SPY") {
            closesOf(10_000L, 10_000L, 10_200L, from = TODAY.minusDays(4))
        } else {
            closesOf(4_000L, 4_000L, 4_200L, from = TODAY.minusDays(4))
        }
    }

    private fun pastEvent(symbol: String, day: Long) = EarningsEventRecord(
        pre = PreReport(
            symbol = symbol,
            reportEpochDay = day,
            timing = ReportTiming.AfterClose,
            priceCents = 4_000L,
        ),
    )

    private fun estimatedEvent(symbol: String, day: Long) = pastEvent(symbol, day).let { event ->
        event.copy(
            pre = event.pre.copy(
                consensusEpsCents = 62L,
                consensusEpsLowCents = 51L,
                consensusEpsHighCents = 74L,
                consensusRevenueCents = 305_000_000_000L,
            ),
        )
    }

    private fun settledEvent(symbol: String, day: Long, abnormalReturnBps: Int) = EarningsEventRecord(
        pre = PreReport(
            symbol = symbol,
            reportEpochDay = day,
            timing = ReportTiming.AfterClose,
            priceCents = 4_000L,
        ),
        post = PostReport(abnormalReturnBps = abnormalReturnBps),
    )

    private class CountingCloses : EarningsEventRecorder.CloseSource {
        var calls = 0

        override suspend fun closes(symbol: String): List<DailyClose> {
            calls++
            return emptyList()
        }
    }

    private class CountingChains(
        private val answer: OptionChainSnapshot? = null,
    ) : EarningsEventRecorder.OptionChainSource {
        var calls = 0

        override suspend fun chain(symbol: String, expiryEpochSeconds: Long?): OptionChainSnapshot? {
            calls++
            return answer
        }
    }

    private val chain = OptionChainSnapshot(
        symbol = "LVS",
        underlyingPriceCents = 4_424L,
        expiries = listOf(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 9, 18)),
        expiry = LocalDate.of(2026, 8, 28),
        rows = listOf(
            ChainRow(43.0, OptionQuote(2.22, 2.38), OptionQuote(0.97, 1.07)),
            ChainRow(44.0, OptionQuote(1.60, 1.72), OptionQuote(1.38, 1.50)),
            ChainRow(45.0, OptionQuote(1.10, 1.20), OptionQuote(1.92, 2.04)),
        ),
    )

    private val consensus = ConsensusEstimate(
        period = "0q",
        periodEndDate = LocalDate.of(2026, 9, 30),
        avgEps = 0.62,
        lowEps = 0.51,
        highEps = 0.74,
        analystCount = 17,
        avgRevenue = 3_050_000_000.0,
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 23)
    }

    @Test
    fun the_reports_the_company_already_filed_give_the_gate_its_denominator() = runTest {
        var log = log()

        recorder(
            log,
            closes = quietDays(),
            history = reactingCloses(jumpBps = 500),
            announcements = filed(390L, 300L, 200L, 100L),
        ).capture(listOf(row(earningsIn = 3)))

        assertEquals(
            500,
            log.read().events.single().pre.medianAbsoluteAbnormalReturnBps,
        )
    }

    @Test
    fun a_ticker_with_filed_history_stops_waiting_for_the_log_to_fill() = runTest {
        var log = log()

        recorder(
            log,
            closes = quietDays(),
            history = reactingCloses(jumpBps = 500),
            announcements = filed(390L, 300L, 200L, 100L),
        ).capture(listOf(row(earningsIn = 3)))

        assertTrue(
            log.read().events.single().pre.riskRatioBps != null,
        )
    }

    @Test
    fun a_company_that_filed_nothing_leaves_the_log_history_in_charge() = runTest {
        var log = log()

        recorder(log, closes = quietDays(), history = reactingCloses(jumpBps = 500))
            .capture(listOf(row(earningsIn = 3)))

        assertNull(log.read().events.single().pre.medianAbsoluteAbnormalReturnBps)
    }

    @Test
    fun a_filing_archive_that_fails_never_costs_the_event_its_record() = runTest {
        var log = log()

        recorder(
            log,
            closes = quietDays(),
            announcements = EarningsEventRecorder.AnnouncementSource { error("SEC is down") },
        ).capture(listOf(row(earningsIn = 3)))

        assertEquals("LVS", log.read().events.single().pre.symbol)
    }

    @Test
    fun a_report_the_chain_never_priced_is_asked_again_before_it_lands() = runTest {
        var log = log()
        var answers = mutableListOf<OptionChainSnapshot?>(null, chain)
        var recorder = recorder(
            log,
            chains = EarningsEventRecorder.OptionChainSource { _, _ -> answers.removeAt(0) },
        )
        recorder.capture(listOf(row(earningsIn = 3)))

        recorder(log, chains = EarningsEventRecorder.OptionChainSource { _, _ -> chain })
            .capture(listOf(row(earningsIn = 3)))

        assertEquals(701, log.event("LVS", TODAY.plusDays(3).toEpochDay())?.pre?.impliedMoveBps)
    }
}
