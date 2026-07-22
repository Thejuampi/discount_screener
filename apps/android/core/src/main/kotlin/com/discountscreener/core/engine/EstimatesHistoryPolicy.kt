package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfCoverageStatus
import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.IndexEstimatesReport
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

/**
 * Controls how index-estimate snapshots are stored for trend charts.
 *
 * Goals:
 * - one durable point per UTC day (avoid enrichment spam)
 * - only rewrite the day when the signal moved in a meaningful way
 * - collapse legacy multi-row days when reading history
 */
object EstimatesHistoryPolicy {
    /** Minimum Base DCF move to justify rewriting the same-day snapshot (0.50%). */
    const val MIN_BASE_MOVE_BPS: Int = 50

    /** Absolute live-DCF coverage gain that counts as material. */
    const val MIN_COVERAGE_GAIN: Int = 5

    /** Keep roughly half a year of daily points. */
    const val MAX_DAILY_POINTS: Int = 180

    private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    enum class PersistAction {
        /** Do not write — noise. */
        Skip,

        /** Overwrite the existing snapshot for the same UTC day. */
        ReplaceDay,

        /** Insert a new day. */
        AppendDay,
    }

    fun dayKey(epochSeconds: Long): String =
        Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .format(dayFormatter)

    fun decide(
        previous: IndexEstimatesReport?,
        candidate: IndexEstimatesReport,
    ): PersistAction {
        if (previous == null) return PersistAction.AppendDay
        val previousDay = dayKey(previous.computedAtEpochSeconds)
        val candidateDay = dayKey(candidate.computedAtEpochSeconds)
        if (candidateDay != previousDay) return PersistAction.AppendDay
        return if (isMaterialChange(previous, candidate)) {
            PersistAction.ReplaceDay
        } else {
            PersistAction.Skip
        }
    }

    fun isMaterialChange(
        previous: IndexEstimatesReport,
        candidate: IndexEstimatesReport,
    ): Boolean {
        val prevBase = baseUpsideBps(previous)
        val nextBase = baseUpsideBps(candidate)
        if (prevBase != null && nextBase != null && (nextBase - prevBase).absoluteValue >= MIN_BASE_MOVE_BPS) {
            return true
        }
        if (prevBase == null && nextBase != null) return true

        val coverageGain = candidate.dcfCoverage.coveredSymbols - previous.dcfCoverage.coveredSymbols
        if (coverageGain >= MIN_COVERAGE_GAIN) return true

        if (statusRank(candidate.dcfCoverage.status) > statusRank(previous.dcfCoverage.status)) {
            return true
        }

        // Large coverage regression is also worth recording (provider outage).
        if (coverageGain <= -MIN_COVERAGE_GAIN) return true

        return false
    }

    /**
     * Collapse multiple snapshots that landed on the same UTC day into a single point.
     * Prefers higher live coverage, then higher status, then later timestamp.
     */
    fun coalesceDaily(history: List<IndexEstimatesReport>): List<IndexEstimatesReport> {
        if (history.size <= 1) return history
        val bestByDay = linkedMapOf<String, IndexEstimatesReport>()
        for (report in history.sortedWith(
            compareBy<IndexEstimatesReport> { it.computedAtEpochSeconds }
                .thenBy { it.dcfCoverage.coveredSymbols },
        )) {
            val key = dayKey(report.computedAtEpochSeconds)
            val existing = bestByDay[key]
            bestByDay[key] = if (existing == null || isPreferred(report, existing)) report else existing
        }
        return bestByDay.values.sortedBy { it.computedAtEpochSeconds }
    }

    private fun isPreferred(candidate: IndexEstimatesReport, incumbent: IndexEstimatesReport): Boolean {
        val coverageCmp = candidate.dcfCoverage.coveredSymbols.compareTo(incumbent.dcfCoverage.coveredSymbols)
        if (coverageCmp != 0) return coverageCmp > 0
        val statusCmp = statusRank(candidate.dcfCoverage.status)
            .compareTo(statusRank(incumbent.dcfCoverage.status))
        if (statusCmp != 0) return statusCmp > 0
        return candidate.computedAtEpochSeconds >= incumbent.computedAtEpochSeconds
    }

    private fun baseUpsideBps(report: IndexEstimatesReport): Int? =
        report.scenarios.find { it.scenario == EstimateScenario.BaseDcf }?.impliedUpsideBps

    private fun statusRank(status: DcfCoverageStatus): Int = when (status) {
        DcfCoverageStatus.Unavailable -> 0
        DcfCoverageStatus.LowConfidence -> 1
        DcfCoverageStatus.Partial -> 2
        DcfCoverageStatus.Provisional -> 3
        DcfCoverageStatus.Ready -> 4
    }
}
