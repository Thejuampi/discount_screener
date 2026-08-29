package com.discountscreener.core.earnings

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class EarningsEventLogTest {

    @TempDir
    lateinit var folder: Path

    @Test
    fun a_log_that_does_not_exist_yet_reads_as_empty() {
        assertTrue(log().read().events.isEmpty())
    }

    @Test
    fun a_written_event_reads_back_whole() {
        var log = log()

        log.append(event("LVS", day = 20_692L))

        assertEquals(event("LVS", day = 20_692L), log.event("LVS", reportEpochDay = 20_692L))
    }

    @Test
    fun the_first_write_creates_the_folder_it_needs() {
        var log = EarningsEventLog(folder.resolve("nested/deeper/events.jsonl").toFile())

        log.append(event("LVS", day = 20_692L))

        assertEquals(1, log.read().events.size)
    }

    @Test
    fun a_decision_written_later_joins_the_event_it_belongs_to() {
        var log = log()
        log.append(event("LVS", day = 20_692L))

        log.decide("LVS", reportEpochDay = 20_692L, decision = decision)

        assertEquals(decision, log.event("LVS", reportEpochDay = 20_692L)?.decision)
    }

    @Test
    fun an_outcome_written_days_later_joins_the_event_it_belongs_to() {
        var log = log()
        log.append(event("LVS", day = 20_692L))

        log.settle("LVS", reportEpochDay = 20_692L, post = post)

        assertEquals(post, log.event("LVS", reportEpochDay = 20_692L)?.post)
    }

    @Test
    fun an_outcome_never_erases_the_decision_that_preceded_it() {
        var log = log()
        log.append(event("LVS", day = 20_692L))
        log.decide("LVS", reportEpochDay = 20_692L, decision = decision)

        log.settle("LVS", reportEpochDay = 20_692L, post = post)

        assertEquals(decision, log.event("LVS", reportEpochDay = 20_692L)?.decision)
    }

    @Test
    fun an_outcome_for_an_event_the_log_never_saw_refuses() {
        assertFalse(log().settle("LVS", reportEpochDay = 20_692L, post = post))
    }

    @Test
    fun an_outcome_for_an_event_the_log_never_saw_writes_nothing() {
        var log = log()

        log.settle("LVS", reportEpochDay = 20_692L, post = post)

        assertNull(log.event("LVS", reportEpochDay = 20_692L))
    }

    @Test
    fun two_reports_of_one_ticker_stay_two_events() {
        var log = log()

        log.append(event("LVS", day = 20_692L))
        log.append(event("LVS", day = 20_783L))

        assertEquals(2, log.read().events.size)
    }

    @Test
    fun the_events_read_back_in_report_order() {
        var log = log()
        log.append(event("LVS", day = 20_783L))
        log.append(event("WYNN", day = 20_692L))

        assertEquals(listOf("WYNN", "LVS"), log.read().events.map { it.pre.symbol })
    }

    @Test
    fun a_damaged_line_never_costs_the_events_around_it() {
        var file = file()
        var log = EarningsEventLog(file)
        log.append(event("LVS", day = 20_692L))
        file.appendText("{\"pre\":{\"symbol\":\n")
        log.append(event("WYNN", day = 20_695L))

        assertEquals(listOf("LVS", "WYNN"), log.read().events.map { it.pre.symbol })
    }

    @Test
    fun a_damaged_line_is_counted_and_not_hidden() {
        var file = file()
        var log = EarningsEventLog(file)
        log.append(event("LVS", day = 20_692L))
        file.appendText("{\"pre\":{\"symbol\":\n")

        assertEquals(1, log.read().unreadableLines)
    }

    @Test
    fun a_field_a_later_version_added_never_makes_the_line_unreadable() {
        var file = file()
        file.writeText(
            """{"pre":{"symbol":"LVS","reportEpochDay":20692,"timing":"AfterClose",
            "dcfComputedOnEpochDay":null,"dcfFairValueCents":null,"priceCents":4424,
            "impliedMoveBps":null,"expiryEpochDay":null,"forwardPriceCents":null,
            "strikeCents":null,"medianAbsoluteAbnormalReturnBps":null,"riskRatioBps":null,
            "consensusEpsCents":null,"consensusEpsLowCents":null,"consensusEpsHighCents":null,
            "analystCount":null,"consensusRevenueCents":null,"aFieldFromTheFuture":7}}
            """.trimIndent().replace("\n", ""),
        )

        assertEquals("LVS", EarningsEventLog(file).read().events.single().pre.symbol)
    }

    @Test
    fun a_backup_carries_one_line_for_every_report() {
        var log = log()
        log.append(event("LVS", day = 20_692L))
        log.append(event("AVGO", day = 20_693L))

        assertEquals(2, log.backupText().trim().lines().size)
    }

    @Test
    fun a_report_written_twice_is_backed_up_once() {
        var log = log()
        log.append(event("LVS", day = 20_692L))
        log.settle("LVS", 20_692L, post)

        assertEquals(1, log.backupText().trim().lines().size)
    }

    @Test
    fun a_backup_keeps_what_the_report_finally_said() {
        var log = log()
        log.append(event("LVS", day = 20_692L))
        log.settle("LVS", 20_692L, post)

        assertTrue(log.backupText().contains("\"abnormalReturnBps\":356"))
    }

    @Test
    fun an_empty_log_backs_up_to_nothing() {
        assertEquals("", log().backupText())
    }

    @Test
    fun a_restore_brings_back_a_report_this_phone_never_saw() {
        var backup = log().also { it.append(event("LVS", day = 20_692L)) }.backupText()
        var fresh = EarningsEventLog(folder.resolve("other/events.jsonl").toFile())

        fresh.restore(backup)

        assertEquals(event("LVS", day = 20_692L), fresh.event("LVS", reportEpochDay = 20_692L))
    }

    @Test
    fun a_restore_counts_the_reports_it_added() {
        var backup = log().also { it.append(event("LVS", day = 20_692L)) }.backupText()
        var fresh = EarningsEventLog(folder.resolve("other/events.jsonl").toFile())

        assertEquals(1, fresh.restore(backup))
    }

    @Test
    fun a_report_this_phone_already_holds_is_not_restored_twice() {
        var log = log()
        log.append(event("LVS", day = 20_692L))

        assertEquals(0, log.restore(log.backupText()))
    }

    @Test
    fun a_settled_report_is_never_overwritten_by_an_unsettled_backup() {
        var backup = log().also { it.append(event("LVS", day = 20_692L)) }.backupText()
        var log = log()
        log.settle("LVS", 20_692L, post)

        log.restore(backup)

        assertEquals(356, log.event("LVS", reportEpochDay = 20_692L)?.post?.abnormalReturnBps)
    }

    @Test
    fun a_settled_backup_fills_in_a_report_this_phone_never_settled() {
        var settled = log()
        settled.append(event("LVS", day = 20_692L))
        settled.settle("LVS", 20_692L, post)
        var backup = settled.backupText()
        var fresh = EarningsEventLog(folder.resolve("other/events.jsonl").toFile())
        fresh.append(event("LVS", day = 20_692L))

        fresh.restore(backup)

        assertEquals(356, fresh.event("LVS", reportEpochDay = 20_692L)?.post?.abnormalReturnBps)
    }

    @Test
    fun a_damaged_line_in_a_backup_never_costs_the_rest_of_it() {
        var backup = log().also { it.append(event("LVS", day = 20_692L)) }.backupText()
        var fresh = EarningsEventLog(folder.resolve("other/events.jsonl").toFile())

        assertEquals(1, fresh.restore("not json at all\n" + backup))
    }

    @Test
    fun a_backup_of_nothing_restores_nothing() {
        assertEquals(0, log().restore(""))
    }

    private fun file(): File = folder.resolve("events.jsonl").toFile()

    private fun log() = EarningsEventLog(file())

    private fun event(symbol: String, day: Long) = EarningsEventRecord(
        pre = PreReport(
            symbol = symbol,
            reportEpochDay = day,
            timing = ReportTiming.AfterClose,
            dcfComputedOnEpochDay = day - 2L,
            dcfFairValueCents = 42_633L,
            priceCents = 4_424L,
            impliedMoveBps = 700,
            expiryEpochDay = day + 2L,
            forwardPriceCents = 4_424L,
            strikeCents = 4_400L,
            medianAbsoluteAbnormalReturnBps = 380,
            riskRatioBps = 18_421,
            consensusEpsCents = 62L,
            consensusEpsLowCents = 51L,
            consensusEpsHighCents = 74L,
            analystCount = 17,
            consensusRevenueCents = 305_000_000_000L,
        ),
    )

    private val decision = EventDecision(
        cell = DecisionCell.CheapHighRisk,
        action = EventAction.Hedge,
        positionSizeBps = 5_000,
        hedge = HedgeKind.PutSpread,
        hedgeCostBps = 80,
        sectorOverrideApplied = false,
        justification = "Cheap on the DCF, and the market pays 1.8x this ticker's own history.",
    )

    private val post = PostReport(
        epsActualCents = 71L,
        surpriseScoreBps = 1_450,
        revenueActualCents = 312_000_000_000L,
        revenueSurpriseBps = 230,
        stockReturnBps = 410,
        marketReturnBps = 60,
        abnormalReturnBps = 356,
    )

    @Test
    fun a_pass_that_wrote_nothing_still_leaves_the_time_it_ran() {
        var log = log()
        log.stampCapture(1_787_000_000L)

        assertEquals(1_787_000_000L, log.read().lastCaptureEpochSeconds)
    }

    @Test
    fun a_log_that_never_ran_reports_no_time() {
        assertNull(log().read().lastCaptureEpochSeconds)
    }

    @Test
    fun the_newest_pass_replaces_the_time_of_the_one_before() {
        var log = log()
        log.stampCapture(1_787_000_000L)
        log.stampCapture(1_787_000_600L)

        assertEquals(1_787_000_600L, log.read().lastCaptureEpochSeconds)
    }

    @Test
    fun the_symbol_the_calendar_stopped_on_reads_back() {
        var log = log()

        log.stampCalendarCursor("NTAP")

        assertEquals("NTAP", log.calendarCursor())
    }

    @Test
    fun a_log_whose_calendar_never_ran_reports_no_symbol() {
        assertNull(log().calendarCursor())
    }

    @Test
    fun a_report_date_bought_from_the_calendar_reads_back() {
        var log = log()

        log.rememberCalendarAsks(mapOf("NTAP" to ask(1_787_875_200L)))

        assertEquals(1_787_875_200L, log.calendarAsks()["NTAP"]?.nextEarningsEpoch)
    }

    @Test
    fun the_hour_the_calendar_was_asked_reads_back() {
        var log = log()

        log.rememberCalendarAsks(mapOf("NTAP" to ask(1_787_875_200L)))

        assertEquals(1_787_000_000L, log.calendarAsks()["NTAP"]?.askedAtEpochSeconds)
    }

    @Test
    fun a_symbol_the_calendar_could_not_answer_still_reads_back_as_asked() {
        var log = log()

        log.rememberCalendarAsks(mapOf("SATS" to ask(null)))

        assertEquals(1_787_000_000L, log.calendarAsks()["SATS"]?.askedAtEpochSeconds)
    }

    @Test
    fun a_later_pass_keeps_the_dates_the_one_before_bought() {
        var log = log()
        log.rememberCalendarAsks(mapOf("NTAP" to ask(1_787_875_200L)))

        log.rememberCalendarAsks(mapOf("DELL" to ask(1_788_048_000L)))

        assertEquals(1_787_875_200L, log.calendarAsks()["NTAP"]?.nextEarningsEpoch)
    }

    @Test
    fun a_date_asked_for_twice_keeps_the_newest_answer() {
        var log = log()
        log.rememberCalendarAsks(mapOf("NTAP" to ask(1_787_875_200L)))

        log.rememberCalendarAsks(mapOf("NTAP" to ask(1_788_048_000L)))

        assertEquals(1_788_048_000L, log.calendarAsks()["NTAP"]?.nextEarningsEpoch)
    }

    @Test
    fun a_log_whose_calendar_bought_nothing_reads_back_no_dates() {
        assertTrue(log().calendarAsks().isEmpty())
    }

    private fun ask(epoch: Long?) = CalendarAsk(epoch, askedAtEpochSeconds = 1_787_000_000L)
}
