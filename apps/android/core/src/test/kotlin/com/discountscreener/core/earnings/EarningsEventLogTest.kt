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
}
