package com.discountscreener.core.contracts

import com.discountscreener.core.engine.OpportunityContext
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.engine.ReportingEngine
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.ViewFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

private val contractJson = Json { ignoreUnknownKeys = true }

class ContractFixtureTest {
    @Test
    fun chart_range_fixture_matches_core_surface_contract() {
        val fixture = loadChartRangesFixture()
        val actual = com.discountscreener.core.model.ChartRange.entries.map { range ->
            range.name to chartRangeLabel(range)
        }
        val expected = fixture.ranges.map { range ->
            range.id.name to range.label
        }
        assertEquals(expected, actual)
    }

    @Test
    fun portfolio_ranking_fixture_matches_core_behavior() {
        val fixture = loadFixture()
        val engine = ReportingEngine()

        fixture.snapshots.forEach(engine::ingestSnapshot)
        fixture.externalSignals.forEach(engine::ingestExternal)
        fixture.fundamentals.forEach(engine::ingestFundamentals)
        fixture.watchlist.forEach(engine::toggleWatchlist)

        val candidateOrder = engine.filteredRows(limit = 10, filter = ViewFilter())
            .filter { it.confidence > ConfidenceBand.Provisional }
            .map { it.symbol }
        assertEquals(fixture.expectedCandidateOrder, candidateOrder)

        val watchlistOnlyOrder = engine.filteredRows(
            limit = 10,
            filter = ViewFilter(query = "", watchlistOnly = true),
        ).filter { it.confidence > ConfidenceBand.Provisional }
            .map { it.symbol }
        assertEquals(fixture.expectedWatchlistOnlyOrder, watchlistOnlyOrder)

        val queryOrder = engine.filteredRows(
            limit = 10,
            filter = ViewFilter(query = fixture.query, watchlistOnly = false),
        ).filter { it.confidence > ConfidenceBand.Provisional }
            .map { it.symbol }
        assertEquals(fixture.expectedQueryOrder, queryOrder)

        val opportunityOrder = OpportunityEngine.buildRows(
            engine,
            OpportunityContext(
                chartSummariesBySymbol = fixture.chartSummaries.groupBy(
                    keySelector = { it.symbol },
                    valueTransform = { it.summary },
                ).mapValues { (_, summaries) -> summaries.associateBy { it.range } },
                analysesBySymbol = fixture.dcfAnalyses.associate { it.symbol to it.analysis },
            ),
        ).map { it.symbol }

        assertEquals(fixture.expectedOpportunityOrder, opportunityOrder)

        val detail = engine.detail(fixture.expectedSelectedDetail.symbol)
            ?: error("missing expected detail")
        assertEquals(fixture.expectedSelectedDetail.qualification, detail.qualification)
        assertEquals(fixture.expectedSelectedDetail.externalStatus, detail.externalStatus)
        assertEquals(fixture.expectedSelectedDetail.confidence, detail.confidence)
        assertEquals(fixture.expectedSelectedDetail.gapBps, detail.gapBps)
        assertEquals(fixture.expectedSelectedDetail.externalSignalGapBps, detail.externalSignalGapBps)
        assertEquals(
            fixture.expectedSelectedDetail.weightedExternalSignalFairValueCents,
            detail.weightedExternalSignalFairValueCents,
        )
        assertEquals(fixture.expectedSelectedDetail.weightedAnalystCount, detail.weightedAnalystCount)
        assertEquals(fixture.expectedSelectedDetail.analystOpinionCount, detail.analystOpinionCount)
        assertEquals(
            fixture.expectedSelectedDetail.recommendationMeanHundredths,
            detail.recommendationMeanHundredths,
        )
        assertEquals(fixture.expectedSelectedDetail.isWatched, detail.isWatched)
    }

    private fun loadFixture(): ContractFixture {
        val path = findFixturePath("portfolio-ranking.json")
        return contractJson.decodeFromString(Files.readString(path))
    }

    private fun loadChartRangesFixture(): ChartRangesFixture {
        val path = findFixturePath("chart-ranges.json")
        return contractJson.decodeFromString(Files.readString(path))
    }

    private fun findFixturePath(fileName: String): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(6) {
            val candidate = current.resolve("shared/contracts/$fileName").normalize()
            if (Files.exists(candidate)) {
                return candidate
            }
            current = current.parent ?: return@repeat
        }
        error("shared contract fixture not found from ${Paths.get("").toAbsolutePath()}")
    }

    private fun chartRangeLabel(range: com.discountscreener.core.model.ChartRange): String = when (range) {
        com.discountscreener.core.model.ChartRange.Day -> "D"
        com.discountscreener.core.model.ChartRange.Week -> "W"
        com.discountscreener.core.model.ChartRange.Month -> "M"
        com.discountscreener.core.model.ChartRange.Year -> "1Y"
        com.discountscreener.core.model.ChartRange.FiveYears -> "5Y"
        com.discountscreener.core.model.ChartRange.TenYears -> "10Y"
    }
}

@Serializable
private data class ContractFixture(
    val snapshots: List<MarketSnapshot>,
    val externalSignals: List<ExternalValuationSignal>,
    val fundamentals: List<FundamentalSnapshot>,
    val watchlist: List<String>,
    val query: String,
    val chartSummaries: List<NamedChartSummary>,
    val dcfAnalyses: List<NamedDcfAnalysis>,
    val expectedSelectedDetail: ExpectedSelectedDetail,
    val expectedCandidateOrder: List<String>,
    val expectedOpportunityOrder: List<String>,
    val expectedWatchlistOnlyOrder: List<String>,
    val expectedQueryOrder: List<String>,
)

@Serializable
private data class ExpectedSelectedDetail(
    val symbol: String,
    val qualification: QualificationStatus,
    val externalStatus: ExternalSignalStatus,
    val confidence: ConfidenceBand,
    val gapBps: Int,
    val externalSignalGapBps: Int? = null,
    val weightedExternalSignalFairValueCents: Long? = null,
    val weightedAnalystCount: Int? = null,
    val analystOpinionCount: Int? = null,
    val recommendationMeanHundredths: Int? = null,
    val isWatched: Boolean,
)

@Serializable
private data class ChartRangesFixture(
    val ranges: List<ChartRangeContract>,
)

@Serializable
private data class ChartRangeContract(
    val id: com.discountscreener.core.model.ChartRange,
    val label: String,
)

@Serializable
private data class NamedChartSummary(
    val symbol: String,
    val range: com.discountscreener.core.model.ChartRange,
    val latestCloseCents: Long,
    val ema20Cents: Long? = null,
    val ema50Cents: Long? = null,
    val ema200Cents: Long? = null,
    val macdCents: Long? = null,
    val signalCents: Long? = null,
    val histogramCents: Long? = null,
) {
    val summary: ChartRangeSummary
        get() = ChartRangeSummary(
            range = range,
            capturedAt = 0,
            candleCount = 52,
            latestCloseCents = latestCloseCents,
            ema20Cents = ema20Cents,
            ema50Cents = ema50Cents,
            ema200Cents = ema200Cents,
            macdCents = macdCents,
            signalCents = signalCents,
            histogramCents = histogramCents,
        )
}

@Serializable
private data class NamedDcfAnalysis(
    val symbol: String,
    val bearIntrinsicValueCents: Long,
    val baseIntrinsicValueCents: Long,
    val bullIntrinsicValueCents: Long,
    val waccBps: Int,
    val baseGrowthBps: Int,
    val netDebtDollars: Long,
) {
    val analysis: DcfAnalysis
        get() = DcfAnalysis(
            bearIntrinsicValueCents = bearIntrinsicValueCents,
            baseIntrinsicValueCents = baseIntrinsicValueCents,
            bullIntrinsicValueCents = bullIntrinsicValueCents,
            waccBps = waccBps,
            baseGrowthBps = baseGrowthBps,
            netDebtDollars = netDebtDollars,
        )
}
