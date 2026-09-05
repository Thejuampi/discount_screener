package com.discountscreener.core.earnings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class EarningsEventRecordTest {

    @Test
    fun a_recorded_event_reads_back_as_the_event_that_was_written() {
        var json = Json.encodeToString(EarningsEventRecord.serializer(), settled)

        assertEquals(settled, Json.decodeFromString(EarningsEventRecord.serializer(), json))
    }

    @Test
    fun an_event_written_before_the_report_reads_back_with_no_outcome() {
        var live = settled.copy(post = null)

        var json = Json.encodeToString(EarningsEventRecord.serializer(), live)

        assertEquals(live, Json.decodeFromString(EarningsEventRecord.serializer(), json))
    }

    @Test
    fun a_stored_event_survives_a_reader_that_predates_its_decision_block() {
        var json = """{"pre":${Json.encodeToString(PreReport.serializer(), pre)}}"""

        assertEquals(
            EarningsEventRecord(pre = pre),
            Json.decodeFromString(EarningsEventRecord.serializer(), json),
        )
    }

    private val pre = PreReport(
        symbol = "LVS",
        reportEpochDay = 20_692L,
        timing = ReportTiming.AfterClose,
        dcfComputedOnEpochDay = 20_690L,
        dcfFairValueCents = 42_633L,
        priceCents = 4_424L,
        impliedMoveBps = 620,
        expiryEpochDay = 20_695L,
        forwardPriceCents = 4_430L,
        strikeCents = 4_450L,
        medianAbsoluteAbnormalReturnBps = 380,
        riskRatioBps = 16_316,
        consensusEpsCents = 62L,
        consensusEpsLowCents = 51L,
        consensusEpsHighCents = 74L,
        analystCount = 17,
        consensusRevenueCents = 305_000_000_000L,
    )

    private val settled = EarningsEventRecord(
        pre = pre,
        decision = EventDecision(
            cell = DecisionCell.CheapHighRisk,
            action = EventAction.Hedge,
            positionSizeBps = 5_000,
            hedge = HedgeKind.PutSpread,
            hedgeCostBps = 80,
            sectorOverrideApplied = false,
            justification = "Cheap on the DCF, and the market pays 1.6x this ticker's own history.",
        ),
        post = PostReport(
            epsActualCents = 71L,
            surpriseScoreBps = 1_450,
            revenueActualCents = 312_000_000_000L,
            revenueSurpriseBps = 230,
            stockReturnBps = 410,
            marketReturnBps = 60,
            abnormalReturnBps = 356,
        ),
    )
}
