package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfCoverageStatus
import com.discountscreener.core.model.DcfCoverageSummary
import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.ScenarioEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EstimatesHistoryPolicyTest {
    @Test
    fun decide_appends_when_history_empty() {
        val action = EstimatesHistoryPolicy.decide(previous = null, candidate = report(dayOffset = 0))
        assertEquals(EstimatesHistoryPolicy.PersistAction.AppendDay, action)
    }

    @Test
    fun decide_appends_on_new_utc_day() {
        val previous = report(epoch = DAY0, baseBps = 500, covered = 100)
        val nextDay = report(epoch = DAY0 + 86_400, baseBps = 500, covered = 100)

        assertEquals(
            EstimatesHistoryPolicy.PersistAction.AppendDay,
            EstimatesHistoryPolicy.decide(previous, nextDay),
        )
    }

    @Test
    fun decide_skips_same_day_micro_coverage_tick() {
        val previous = report(epoch = DAY0 + 100, baseBps = 500, covered = 100)
        val tick = report(epoch = DAY0 + 200, baseBps = 510, covered = 102)

        assertEquals(
            EstimatesHistoryPolicy.PersistAction.Skip,
            EstimatesHistoryPolicy.decide(previous, tick),
        )
    }

    @Test
    fun decide_replaces_same_day_when_base_moves_half_percent() {
        val previous = report(epoch = DAY0 + 100, baseBps = 500, covered = 100)
        val moved = report(epoch = DAY0 + 200, baseBps = 560, covered = 100)

        assertEquals(
            EstimatesHistoryPolicy.PersistAction.ReplaceDay,
            EstimatesHistoryPolicy.decide(previous, moved),
        )
    }

    @Test
    fun decide_replaces_same_day_when_coverage_gains_five() {
        val previous = report(epoch = DAY0 + 100, baseBps = 500, covered = 100)
        val better = report(epoch = DAY0 + 200, baseBps = 500, covered = 105)

        assertEquals(
            EstimatesHistoryPolicy.PersistAction.ReplaceDay,
            EstimatesHistoryPolicy.decide(previous, better),
        )
    }

    @Test
    fun decide_replaces_same_day_when_status_improves() {
        val previous = report(
            epoch = DAY0 + 100,
            baseBps = 500,
            covered = 100,
            status = DcfCoverageStatus.Partial,
        )
        val better = report(
            epoch = DAY0 + 200,
            baseBps = 500,
            covered = 100,
            status = DcfCoverageStatus.Provisional,
        )

        assertEquals(
            EstimatesHistoryPolicy.PersistAction.ReplaceDay,
            EstimatesHistoryPolicy.decide(previous, better),
        )
    }

    @Test
    fun coalesce_daily_keeps_one_point_per_day_preferring_higher_coverage() {
        val history = listOf(
            report(epoch = DAY0 + 10, baseBps = 400, covered = 80),
            report(epoch = DAY0 + 20, baseBps = 450, covered = 120),
            report(epoch = DAY0 + 30, baseBps = 460, covered = 110),
            report(epoch = DAY0 + 86_400, baseBps = 500, covered = 130),
        )

        val coalesced = EstimatesHistoryPolicy.coalesceDaily(history)

        assertEquals(2, coalesced.size)
        assertEquals(120, coalesced[0].dcfCoverage.coveredSymbols)
        assertEquals(130, coalesced[1].dcfCoverage.coveredSymbols)
    }

    @Test
    fun day_key_uses_utc_calendar_date() {
        assertEquals("2020-01-01", EstimatesHistoryPolicy.dayKey(DAY0))
        assertEquals("2020-01-02", EstimatesHistoryPolicy.dayKey(DAY0 + 86_400))
    }

    @Test
    fun material_change_false_for_tiny_base_wiggle() {
        val previous = report(epoch = DAY0, baseBps = 1_000, covered = 200)
        val wiggle = report(epoch = DAY0 + 1, baseBps = 1_020, covered = 201)
        assertTrue(!EstimatesHistoryPolicy.isMaterialChange(previous, wiggle))
    }

    private fun report(
        epoch: Long = DAY0,
        dayOffset: Int = 0,
        baseBps: Int = 0,
        covered: Int = 0,
        status: DcfCoverageStatus = DcfCoverageStatus.Provisional,
    ): IndexEstimatesReport {
        val at = if (dayOffset != 0) DAY0 + dayOffset * 86_400L else epoch
        return IndexEstimatesReport(
            profileName = "sp500",
            currentWeightedPriceCents = 10_000L,
            totalSymbols = 500,
            scenarios = EstimateScenario.entries.map { scenario ->
                ScenarioEstimate(
                    scenario = scenario,
                    weightedPriceCents = 10_000L,
                    coverageCount = covered,
                    impliedUpsideBps = if (scenario == EstimateScenario.BaseDcf) baseBps else baseBps - 100,
                )
            },
            computedAtEpochSeconds = at,
            dcfCoverage = DcfCoverageSummary(
                totalEligibleSymbols = 500,
                coveredSymbols = covered,
                coverageBps = covered * 10_000 / 500,
                status = status,
            ),
        )
    }

    companion object {
        // 2020-01-01T00:00:00Z
        private const val DAY0 = 1_577_836_800L
    }
}
