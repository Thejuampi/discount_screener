package com.discountscreener.core.contracts

import com.discountscreener.core.engine.OpportunityContext
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.engine.ReportingEngine
import com.discountscreener.core.engine.ValuationDecisionPolicy
import com.discountscreener.core.engine.DcfSourceSelectionPolicy
import com.discountscreener.core.engine.DcfAnalysisEngine
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.DcfSourceCandidate
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.ProviderDecisionReasonCode
import com.discountscreener.core.model.ResolverState
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.ViewFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun dcf_source_selection_fixture_matches_core_policy_contract() {
        val fixture = loadDcfSourceSelectionFixture()

        val actual = fixture.cases.map { case ->
            var selection = DcfSourceSelectionPolicy.select(
                yahoo = candidate(DcfSource.YahooFinance, case.yahoo),
                sec = candidate(DcfSource.SecEdgar, case.sec),
            )
            DcfSelectionActual(
                name = case.name,
                resolverState = selection.resolverState,
                selectedSource = selection.selectedSource,
                reasonCodes = selection.reasons.map { it.code },
            )
        }
        val expected = fixture.cases.map { case ->
            DcfSelectionActual(
                name = case.name,
                resolverState = case.expectedResolverState,
                selectedSource = case.expectedSelectedSource,
                reasonCodes = case.expectedReasonCodes,
            )
        }
        assertEquals(expected, actual)
    }

    @Test
    fun valuation_model_family_policy2_fixtures_execute_against_core() {
        val fixture = loadValuationModelFamilyFixture()
        assertTrue("android" in fixture.policy2Adoption.executableSurfaces)
        assertTrue("desktop" in fixture.policy2Adoption.deferredSurfaces)

        fixture.regressionFixtures
            .filter { it.name in executablePolicy2FixtureNames }
            .forEach { case ->
                val input = case.sampledInputs
                val expected = case.expected
                val analysis = DcfAnalysisEngine.compute(
                    fundamentals = FundamentalSnapshot(
                        symbol = case.symbol,
                        sectorName = input.sectorName,
                        industryName = input.industryName,
                        marketCapDollars = input.marketCapDollars,
                        sharesOutstanding = input.sharesOutstanding,
                        betaMillis = input.betaMillis,
                        totalDebtDollars = input.totalDebtDollars,
                        totalCashDollars = input.totalCashDollars,
                    ),
                    timeseries = FundamentalTimeseries(
                        freeCashFlow = input.fcfAnnualDollars.map { point ->
                            AnnualReportedValue("${point.year}-12-31", point.valueDollars)
                        },
                        operatingCashFlow = input.operatingCashFlowAnnualDollars.map { point ->
                            AnnualReportedValue("${point.year}-12-31", point.valueDollars)
                        },
                        capitalExpenditure = input.capitalExpenditureAnnualDollars.map { point ->
                            AnnualReportedValue("${point.year}-12-31", point.valueDollars)
                        },
                        revenue = input.revenueAnnualDollars.map { point ->
                            AnnualReportedValue("${point.year}-12-31", point.valueDollars)
                        },
                        interestExpense = input.interestExpenseAnnualDollars.map { point ->
                            AnnualReportedValue("${point.year}-12-31", point.valueDollars)
                        },
                        taxRateForCalcs = input.taxRateBpsAnnual.map { point ->
                            AnnualReportedValue("${point.year}-12-31", point.valueDollars / 10_000.0)
                        },
                        totalDebt = input.totalDebtAnnualDollars.map { point ->
                            AnnualReportedValue("${point.year}-12-31", point.valueDollars)
                        },
                        marginalTaxRate = input.marginalTaxBpsAnnual.map { point ->
                            AnnualReportedValue(
                                "${point.year}-12-31",
                                point.valueDollars / 10_000.0,
                                concept = "JurisdictionStatutory",
                            )
                        },
                    ),
                    marketPriceCents = (input.marketPriceDollars * 100.0).toLong(),
                ).getOrThrow()

                assertEquals(expected.businessClass, analysis.businessClass.name, case.name)
                assertEquals(expected.model, analysis.model.name, case.name)
                assertEquals(expected.discountRateKind, analysis.discountRateKind.name, case.name)
                assertEquals(expected.modelPolicyVersion, analysis.modelPolicyVersion, case.name)
                assertEquals(expected.latestFcfDollars, analysis.latestFcfDollars, case.name)
                assertEquals(expected.fcfRunRateDollars, analysis.fcfRunRateDollars, case.name)
                expected.valuationDriver?.let { driver ->
                    assertEquals(driver, analysis.valuationDriver, case.name)
                }
                assertTrue(
                    analysis.bearIntrinsicValueCents <= analysis.baseIntrinsicValueCents &&
                        analysis.baseIntrinsicValueCents <= analysis.bullIntrinsicValueCents,
                    "${case.name} scenarios must be ordered",
                )
                expected.baseIntrinsicRangeDollars?.let { range ->
                    val base = analysis.baseIntrinsicValueCents / 100.0
                    assertTrue(base in range[0]..range[1], "${case.name} base $base outside $range")
                }
                assertTrue(analysis.reasonCodes.none { it.startsWith("calibration_target=") })
            }
    }

    @Test
    fun valuation_decision_policy_goldens_execute_against_core() {
        val fixture = loadValuationDecisionPolicyFixture()
        assertEquals(1, fixture.schemaVersion)
        assertEquals("valuation-decision-policy/1", fixture.policyVersion)

        fixture.fixtures.forEach { case ->
            when {
                case.left != null && case.right != null -> assertEquals(
                    case.expectedDifferenceBps,
                    ValuationDecisionPolicy.differenceBps(case.left, case.right),
                    case.name,
                )
                case.bear != null -> assertEquals(
                    case.expectedScenarioWidthBps,
                    ValuationDecisionPolicy.scenarioWidthBps(case.bear, requireNotNull(case.base), requireNotNull(case.bull)),
                    case.name,
                )
            }
        }
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

    private fun loadDcfSourceSelectionFixture(): DcfSourceSelectionFixture {
        val path = findFixturePath("dcf-source-selection.json")
        return contractJson.decodeFromString(Files.readString(path))
    }

    private fun loadValuationModelFamilyFixture(): ValuationModelFamilyFixture {
        val path = findFixturePath("valuation-model-family.json")
        return contractJson.decodeFromString(Files.readString(path))
    }

    private fun loadValuationDecisionPolicyFixture(): ValuationDecisionPolicyFixture {
        val path = findFixturePath("valuation-decision-policy.json")
        return contractJson.decodeFromString(Files.readString(path))
    }

    private fun candidate(source: DcfSource, fixtureState: String): DcfSourceCandidate? = when (fixtureState) {
        "absent" -> null
        "unavailable" -> DcfSourceCandidate(source = source)
        "unsupported" -> DcfSourceCandidate(source = source, timeseries = unsupportedTimeseries(), analysis = analysis())
        "usable" -> DcfSourceCandidate(source = source, timeseries = usableTimeseries(), analysis = analysis())
        "divergent" -> DcfSourceCandidate(source = source, timeseries = divergentTimeseries(), analysis = analysis())
        else -> error("unknown DCF source fixture state $fixtureState")
    }

    private fun usableTimeseries() = FundamentalTimeseries(
        freeCashFlow = listOf(
            AnnualReportedValue("2021-12-31", 100.0),
            AnnualReportedValue("2022-12-31", 120.0),
            AnnualReportedValue("2023-12-31", 140.0),
        ),
        operatingCashFlow = annual(150.0, 175.0, 200.0),
        capitalExpenditure = annual(-50.0, -55.0, -60.0),
        revenue = annual(500.0, 550.0, 600.0),
    )

    private fun divergentTimeseries() = FundamentalTimeseries(
        freeCashFlow = listOf(
            AnnualReportedValue("2021-12-31", 100.0),
            AnnualReportedValue("2022-12-31", 120.0),
            AnnualReportedValue("2023-12-31", 180.0),
        ),
        operatingCashFlow = annual(150.0, 175.0, 200.0),
        capitalExpenditure = annual(-50.0, -55.0, -60.0),
        revenue = annual(500.0, 550.0, 600.0),
    )

    private fun annual(vararg values: Double) = values.mapIndexed { index, value ->
        AnnualReportedValue("${2021 + index}-12-31", value)
    }

    private fun unsupportedTimeseries() = FundamentalTimeseries(
        freeCashFlow = listOf(AnnualReportedValue("2023-12-31", 140.0)),
    )

    private fun analysis() = DcfAnalysis(
        bearIntrinsicValueCents = 8_000L,
        baseIntrinsicValueCents = 10_000L,
        bullIntrinsicValueCents = 12_000L,
        waccBps = 800,
        baseGrowthBps = 500,
        netDebtDollars = 0L,
        source = DcfSource.YahooFinance,
        sourceFingerprint = "fixture",
        resolverState = ResolverState.Selected,
    )

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

private val executablePolicy2FixtureNames = setOf(
    "t_class_explicit_driver_fcff_no_analyst_calibration",
    "amzn_capex_trough_does_not_invert_fcff_scenarios",
)

@Serializable
private data class ValuationModelFamilyFixture(
    val policy2Adoption: Policy2Adoption,
    val regressionFixtures: List<ValuationRegressionFixture>,
)

@Serializable
private data class Policy2Adoption(
    val executableSurfaces: List<String>,
    val deferredSurfaces: List<String>,
)

@Serializable
private data class ValuationRegressionFixture(
    val name: String,
    val symbol: String,
    val sampledInputs: ValuationSampledInputs,
    val expected: ValuationExpected,
)

@Serializable
private data class ValuationSampledInputs(
    val marketPriceDollars: Double = 0.0,
    val sharesOutstanding: Long? = null,
    val marketCapDollars: Long? = null,
    val betaMillis: Int? = null,
    val totalDebtDollars: Long? = null,
    val totalCashDollars: Long? = null,
    val sectorName: String? = null,
    val industryName: String? = null,
    val fcfAnnualDollars: List<ValuationFcfPoint> = emptyList(),
    val operatingCashFlowAnnualDollars: List<ValuationFcfPoint> = emptyList(),
    val capitalExpenditureAnnualDollars: List<ValuationFcfPoint> = emptyList(),
    val revenueAnnualDollars: List<ValuationFcfPoint> = emptyList(),
    val interestExpenseAnnualDollars: List<ValuationFcfPoint> = emptyList(),
    val taxRateBpsAnnual: List<ValuationFcfPoint> = emptyList(),
    val totalDebtAnnualDollars: List<ValuationFcfPoint> = emptyList(),
    val marginalTaxBpsAnnual: List<ValuationFcfPoint> = emptyList(),
)

@Serializable
private data class ValuationFcfPoint(
    val year: Int,
    val valueDollars: Double,
)

@Serializable
private data class ValuationExpected(
    val businessClass: String,
    val model: String,
    val discountRateKind: String,
    val modelPolicyVersion: String = "legacy",
    val baseIntrinsicRangeDollars: List<Double>? = null,
    val latestFcfDollars: Long? = null,
    val fcfRunRateDollars: Long? = null,
    val valuationDriver: String? = null,
)

@Serializable
private data class ValuationDecisionPolicyFixture(
    val schemaVersion: Int,
    val policyVersion: String,
    val fixtures: List<ValuationDecisionPolicyCase>,
)

@Serializable
private data class ValuationDecisionPolicyCase(
    val name: String,
    val left: Long? = null,
    val right: Long? = null,
    val bear: Long? = null,
    val base: Long? = null,
    val bull: Long? = null,
    val expectedDifferenceBps: Int? = null,
    val expectedScenarioWidthBps: Int? = null,
)

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
private data class DcfSourceSelectionFixture(
    val cases: List<DcfSelectionCase>,
)

@Serializable
private data class DcfSelectionCase(
    val name: String,
    val yahoo: String,
    val sec: String,
    val expectedResolverState: ResolverState,
    val expectedSelectedSource: DcfSource? = null,
    val expectedReasonCodes: List<ProviderDecisionReasonCode> = emptyList(),
)

private data class DcfSelectionActual(
    val name: String,
    val resolverState: ResolverState,
    val selectedSource: DcfSource?,
    val reasonCodes: List<ProviderDecisionReasonCode>,
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
            source = DcfSource.YahooFinance,
            sourceFingerprint = "contract:$symbol",
            resolverState = ResolverState.Selected,
        )
}
