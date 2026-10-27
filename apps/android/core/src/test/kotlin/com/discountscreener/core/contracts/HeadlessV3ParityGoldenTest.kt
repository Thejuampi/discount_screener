package com.discountscreener.core.contracts

import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.DcfAnalysisEngine
import com.discountscreener.core.engine.ResidualFromDrivers
import com.discountscreener.core.engine.ValuationJudgmentAssembler
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.DataProvenance
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.model.ProjectedOpportunityDecisionFacts
import com.discountscreener.core.model.ProjectedOpportunityRow
import com.discountscreener.core.model.ProjectedProvenanceState
import com.discountscreener.core.model.ProjectedProviderCategory
import com.discountscreener.core.model.ProjectionRoute
import com.discountscreener.core.model.ProjectionSymbolState
import com.discountscreener.core.model.ProviderDecisionReasonCode
import com.discountscreener.core.model.ProviderState
import com.discountscreener.core.model.ResolverState
import com.discountscreener.core.model.ScreenDataProjectionRequest
import com.discountscreener.core.model.ValuationModel
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.core.replay.ScreenReplay
import com.discountscreener.core.engine.OpportunityContext
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.engine.ReportingEngine
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityRow
import com.discountscreener.core.model.OpportunityScoringModel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Android half of the headless V3 parity contract.
 *
 * `shared/contracts/opportunity-v3-headless-fixture.json` holds raw inputs. This test runs them
 * through the real Android core the way the app's Opps screen does (ReportingEngine →
 * OpportunityEngine.buildRows, AggressiveV3, regime scoring off, ChartRange.Year technicals →
 * ScreenDataProjectionRequest → ScreenReplay.project) and compares the result with
 * `opportunity-v3-headless-golden.json`. The Rust headless engine is tested against the same
 * golden (`headless::android_parity_tests`), so the golden is Kotlin output, never Rust output.
 *
 * Regenerate (only when Android behaviour intentionally changes):
 *   HEADLESS_GOLDEN_WRITE=1 ./gradlew :core:test --tests '*HeadlessV3ParityGoldenTest*'
 */
class HeadlessV3ParityGoldenTest {
    @Serializable
    data class Case(
        val snapshot: MarketSnapshot,
        val external: ExternalValuationSignal? = null,
        val fundamentals: FundamentalSnapshot? = null,
        val yearCandles: List<HistoricalCandle> = emptyList(),
        /** Injected DCF state (e.g. ProviderUncertain). Absent: the app's deterministic bulk-load state. */
        val dcf: DcfAnalysis? = null,
    )

    @Serializable
    data class Fixture(val capturedAtEpochSeconds: Long, val symbols: List<Case>)

    @Serializable
    data class GoldenRow(
        val rank: Int,
        val symbol: String,
        val compositeScore: Int,
        val coverageCount: Int,
        val fundamentalsScore: Int?,
        val technicalScore: Int?,
        val forecastScore: Int?,
        val confidence: String,
        val gapBps: Int,
        val upsideBps: Int,
        /** Projected (on-screen) fields, from ScreenDataProjectionEngine. */
        val projectedConfidence: String,
        val projectedGapBps: Int?,
        val projectedUpsideBps: Int?,
        val trustSignal: String?,
        val decision: String?,
    )

    @Serializable
    data class Golden(
        val contract: String,
        val generatedBy: String,
        val model: String,
        val regimeScoringEnabled: Boolean,
        val inputSymbols: Int,
        val rows: List<GoldenRow>,
    )

    @Test
    fun android_v3_ranking_matches_the_committed_golden() {
        val fixture = JSON.decodeFromString<Fixture>(Files.readString(find(FIXTURE)))
        val engine = ReportingEngine()
        val charts = mutableMapOf<String, Map<ChartRange, com.discountscreener.core.model.ChartRangeSummary>>()
        for (c in fixture.symbols) {
            engine.ingestSnapshot(c.snapshot)
            c.external?.let(engine::ingestExternal)
            c.fundamentals?.let(engine::ingestFundamentals)
            if (c.yearCandles.isNotEmpty()) {
                charts[c.snapshot.symbol] = mapOf(
                    ChartRange.Year to ChartAnalysis.buildSummary(ChartRange.Year, c.yearCandles, fixture.capturedAtEpochSeconds),
                )
            }
        }
        val dcf = fixture.symbols.mapNotNull { c ->
            val sym = c.snapshot.symbol
            (c.dcf ?: bulkLoadDcf(sym, engine.detail(sym)?.fundamentals, c.snapshot.marketPriceCents))?.let { sym to it }
        }.toMap()
        val rows = OpportunityEngine.buildRows(
            engine,
            OpportunityContext(
                chartSummariesBySymbol = charts,
                analysesBySymbol = dcf,
                scoringModel = OpportunityScoringModel.AggressiveV3,
                regimeScoringEnabled = false,
            ),
        )
        val projected = project(engine, rows, charts, dcf, fixture).associateBy { it.symbol }
        val golden = Golden(
            contract = "opportunity-v3-headless-golden",
            generatedBy = "apps/android/core HeadlessV3ParityGoldenTest (Kotlin OpportunityEngine + ScreenDataProjectionEngine)",
            model = "AggressiveV3",
            regimeScoringEnabled = false,
            inputSymbols = fixture.symbols.size,
            rows = rows.mapIndexed { i, r -> r.toGolden(i + 1, projected.getValue(r.symbol)) },
        )
        val out = PRETTY.encodeToString(Golden.serializer(), golden) + "\n"
        val goldenPath = find(FIXTURE).resolveSibling(GOLDEN)
        if (System.getenv("HEADLESS_GOLDEN_WRITE") == "1") {
            Files.writeString(goldenPath, out)
        }
        assertEquals(Files.readString(goldenPath), out)
    }

    private fun OpportunityRow.toGolden(rank: Int, p: ProjectedOpportunityRow) = GoldenRow(
        rank = rank,
        symbol = symbol,
        compositeScore = compositeScore,
        coverageCount = coverageCount,
        fundamentalsScore = fundamentalsScore,
        technicalScore = technicalScore,
        forecastScore = forecastScore,
        confidence = confidence.name,
        gapBps = gapBps,
        upsideBps = upsideBps,
        projectedConfidence = p.confidence.name,
        projectedGapBps = p.gapBps,
        projectedUpsideBps = p.upsideBps,
        trustSignal = p.trustSignal?.kind?.name,
        decision = p.decision?.name,
    )

    /**
     * The request `DefaultDashboardRepository.screenDataProjectionRequestLocked` builds for the Opps
     * list after a cold load: candidate rows from `candidateRowFromOpportunityRow`, decision facts
     * from the composite, every refreshed symbol Live, no issues, revisions or baselines.
     */
    private fun project(
        engine: ReportingEngine,
        rows: List<OpportunityRow>,
        charts: Map<String, Map<ChartRange, com.discountscreener.core.model.ChartRangeSummary>>,
        dcf: Map<String, DcfAnalysis>,
        fixture: Fixture,
    ): List<ProjectedOpportunityRow> {
        val tracked = fixture.symbols.map { it.snapshot.symbol }
        val details = tracked.mapNotNull { s -> engine.detail(s)?.let { s to it } }.toMap()
        val request = ScreenDataProjectionRequest(
            route = ProjectionRoute(ViewFilter(), null, ChartRange.Year, 0, OpportunityScoringModel.AggressiveV3),
            nowEpochSeconds = fixture.capturedAtEpochSeconds,
            trackedSymbols = tracked,
            detailsBySymbol = details,
            candidateRows = rows.map { r ->
                CandidateRow(r.symbol, r.marketPriceCents, r.intrinsicValueCents, r.gapBps, r.upsideBps, true, r.confidence, r.companyName)
            },
            opportunityDecisionFactsBySymbol = rows.associate { it.symbol to ProjectedOpportunityDecisionFacts(it.compositeScore) },
            chartSummariesBySymbol = charts,
            dcfBySymbol = dcf,
            symbolStateBySymbol = details.keys.associateWith { s ->
                ProjectionSymbolState(s, ProjectedProviderCategory.Live, ProjectedProvenanceState.Live, fixture.capturedAtEpochSeconds)
            },
        )
        // Through the replay codec, so the golden is what a captured request replays to.
        return ScreenReplay.project(ScreenReplay.decodeRequest(ScreenReplay.encodeRequest(request))).opportunityRows
    }

    /**
     * The DCF state a bulk load settles on without a timeseries fetch (`enrichSymbol`):
     * classification refuse -> the terminal analysis (source Unknown); financial services ->
     * `ResidualFromDrivers` on the Yahoo snapshot with SEC off; operating names -> none here
     * (their state needs the Yahoo timeseries, which the fixture does not carry).
     */
    private fun bulkLoadDcf(symbol: String, fund: FundamentalSnapshot?, priceCents: Long): DcfAnalysis? {
        fund ?: return null
        val cls = DcfAnalysisEngine.classifyBusiness(fund.sectorName, fund.industryName, fund.sectorKey, fund.industryKey, symbol = fund.symbol)
        return when (cls) {
            BusinessClass.Unclassified, BusinessClass.NotEligible -> terminalClassification(cls)
            BusinessClass.FinancialServices -> ResidualFromDrivers.compute(
                fund, null, false, priceCents, MarketParams(), symbol, ValuationJudgmentAssembler.SHARE_BASIS,
            ).analysis
            BusinessClass.OperatingNonFinancial -> null
        }
    }

    /** The projection-relevant fields of `DefaultDashboardRepository.terminalClassificationAnalysis`. */
    private fun terminalClassification(cls: BusinessClass): DcfAnalysis {
        val notEligible = cls == BusinessClass.NotEligible
        val code = if (notEligible) ProviderDecisionReasonCode.FundOrEtfUnsupported else ProviderDecisionReasonCode.SymbolUnsupported
        return DcfAnalysis(
            bearIntrinsicValueCents = 0L, baseIntrinsicValueCents = 0L, bullIntrinsicValueCents = 0L,
            waccBps = 0, baseGrowthBps = 0, netDebtDollars = 0L,
            source = DcfSource.Unknown,
            resolverState = if (notEligible) ResolverState.NotEligible else ResolverState.Unavailable,
            businessClass = cls,
            model = ValuationModel.None,
            provenance = DataProvenance(
                source = DcfSource.Unknown,
                providerState = if (notEligible) ProviderState.NotEligible else ProviderState.Unavailable,
                fallbackReason = code,
            ),
            valuationUnavailableReason = DcfAnalysisEngine.classificationUnavailableReason(cls).orEmpty(),
        )
    }

    private fun find(name: String): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(8) {
            val candidate = current.resolve(name).normalize()
            if (Files.exists(candidate)) return candidate
            current = current.parent ?: return@repeat
        }
        error("$name not found")
    }

    private companion object {
        const val FIXTURE = "shared/contracts/opportunity-v3-headless-fixture.json"
        const val GOLDEN = "opportunity-v3-headless-golden.json"
        val JSON = Json { ignoreUnknownKeys = true }
        val PRETTY = Json { prettyPrint = true; explicitNulls = true }
    }
}
