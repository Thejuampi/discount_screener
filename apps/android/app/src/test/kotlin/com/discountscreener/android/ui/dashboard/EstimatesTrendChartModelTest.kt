package com.discountscreener.android.ui.dashboard

import com.discountscreener.core.model.DcfCoverageStatus
import com.discountscreener.core.model.DcfCoverageSummary
import com.discountscreener.core.model.DcfSourceDistribution
import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.ScenarioEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatesTrendChartModelTest {
    @Test
    fun from_requires_at_least_two_snapshots() {
        assertNull(EstimatesTrendChartModel.from(listOf(report(epoch = 1L, baseBps = 500))))
    }

    @Test
    fun from_uses_base_dcf_as_primary_series_with_bear_bull_band() {
        val model = EstimatesTrendChartModel.from(
            listOf(
                report(epoch = 100L, bearBps = -1_000, baseBps = 500, bullBps = 2_000),
                report(epoch = 200L, bearBps = -500, baseBps = 800, bullBps = 2_500),
            ),
        )

        assertNotNull(model)
        assertEquals(2, model!!.points.size)
        assertEquals(5f, model.points[0].baseUpsidePct)
        assertEquals(-10f, model.points[0].bearUpsidePct)
        assertEquals(20f, model.points[0].bullUpsidePct)
        assertEquals(8f, model.latestBaseUpsidePct)
        assertEquals(3f, model.baseChangePts, 0.01f)
        assertTrue(model.minUpside <= -5f)
        assertTrue(model.maxUpside >= 25f)
    }

    @Test
    fun from_ignores_analyst_scenarios_for_chart_series() {
        val model = EstimatesTrendChartModel.from(
            listOf(
                report(epoch = 1L, baseBps = 100, analystLowBps = 50_000, analystHighBps = 90_000),
                report(epoch = 2L, baseBps = 200, analystLowBps = 50_000, analystHighBps = 90_000),
            ),
        )

        assertNotNull(model)
        assertTrue(model!!.maxUpside < 100f)
    }

    @Test
    fun downsample_keeps_first_last_and_caps_dense_history() {
        val dense = (0 until 100).map { index ->
            EstimatesTrendPoint(
                epochSeconds = 1_700_000_000L + index * 60L,
                baseUpsidePct = index.toFloat(),
                bearUpsidePct = index - 2f,
                bullUpsidePct = index + 2f,
            )
        }

        val sampled = EstimatesTrendChartModel.downsample(dense, maxPoints = 10)

        assertTrue(sampled.size <= 10)
        assertEquals(dense.first().epochSeconds, sampled.first().epochSeconds)
        assertEquals(dense.last().epochSeconds, sampled.last().epochSeconds)
    }

    @Test
    fun hero_summary_verdict_undervalued_when_base_upside_at_least_5pct() {
        val hero = buildEstimatesHeroSummary(
            report(epoch = 1L, baseBps = 800, coverageStatus = DcfCoverageStatus.Ready, covered = 40, eligible = 40),
        )

        assertEquals(EstimatesVerdict.Undervalued, hero.verdict)
        assertEquals("Ready", hero.coverageLabel)
        assertTrue(hero.coverageDetail.contains("40/40"))
    }

    @Test
    fun hero_summary_includes_not_eligible_in_coverage_detail() {
        val hero = buildEstimatesHeroSummary(
            report(
                epoch = 1L,
                baseBps = 100,
                coverageStatus = DcfCoverageStatus.Provisional,
                covered = 440,
                eligible = 480,
                notEligible = 23,
            ),
        )

        assertEquals(EstimatesVerdict.Fair, hero.verdict)
        assertEquals("Provisional", hero.coverageLabel)
        assertTrue(hero.coverageDetail.contains("n/a"))
    }

    @Test
    fun verdict_sentence_explains_base_dcf_read() {
        assertTrue(
            verdictSentence(EstimatesVerdict.Undervalued, 1_200)
                .contains("undervalued"),
        )
    }

    private fun report(
        epoch: Long,
        baseBps: Int,
        bearBps: Int = baseBps - 500,
        bullBps: Int = baseBps + 500,
        analystLowBps: Int = baseBps - 200,
        analystHighBps: Int = baseBps + 200,
        coverageStatus: DcfCoverageStatus = DcfCoverageStatus.Ready,
        covered: Int = 8,
        eligible: Int = 10,
        notEligible: Int = 0,
    ) = IndexEstimatesReport(
        profileName = "sp500",
        currentWeightedPriceCents = 10_000L,
        totalSymbols = eligible + notEligible,
        scenarios = listOf(
            ScenarioEstimate(EstimateScenario.BearDcf, 9_000L, covered, bearBps),
            ScenarioEstimate(EstimateScenario.BaseDcf, 10_500L, covered, baseBps),
            ScenarioEstimate(EstimateScenario.BullDcf, 12_000L, covered, bullBps),
            ScenarioEstimate(EstimateScenario.AnalystLow, 10_200L, 5, analystLowBps),
            ScenarioEstimate(EstimateScenario.AnalystHigh, 11_000L, 5, analystHighBps),
        ),
        computedAtEpochSeconds = epoch,
        dcfCoverage = DcfCoverageSummary(
            totalEligibleSymbols = eligible,
            coveredSymbols = covered,
            coverageBps = if (eligible == 0) 0 else covered * 10_000 / eligible,
            status = coverageStatus,
            sourceDistribution = DcfSourceDistribution(notEligibleCount = notEligible),
        ),
    )
}
