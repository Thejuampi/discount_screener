package com.discountscreener.core.contracts

import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.OpportunityContext
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.engine.ReportingEngine
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
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
 * through the real Android core (ReportingEngine → OpportunityEngine.buildRows, AggressiveV3,
 * regime scoring off, ChartRange.Year technicals) and compares the result with
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
        val decision: String,
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
        val rows = OpportunityEngine.buildRows(
            engine,
            OpportunityContext(
                chartSummariesBySymbol = charts,
                scoringModel = OpportunityScoringModel.AggressiveV3,
                regimeScoringEnabled = false,
            ),
        )
        val golden = Golden(
            contract = "opportunity-v3-headless-golden",
            generatedBy = "apps/android/core HeadlessV3ParityGoldenTest (Kotlin OpportunityEngine)",
            model = "AggressiveV3",
            regimeScoringEnabled = false,
            inputSymbols = fixture.symbols.size,
            rows = rows.mapIndexed { i, r -> r.toGolden(i + 1) },
        )
        val out = PRETTY.encodeToString(Golden.serializer(), golden) + "\n"
        val goldenPath = find(FIXTURE).resolveSibling(GOLDEN)
        if (System.getenv("HEADLESS_GOLDEN_WRITE") == "1") {
            Files.writeString(goldenPath, out)
        }
        assertEquals(Files.readString(goldenPath), out)
    }

    private fun OpportunityRow.toGolden(rank: Int) = GoldenRow(
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
        decision = decisionFor(this),
    )

    /** Verbatim gates of `apps/android/app/.../OpportunityDecision.kt` (no trust note in core). */
    private fun decisionFor(row: OpportunityRow): String = when {
        row.confidence == ConfidenceBand.Low -> "Avoid"
        row.upsideBps <= 0 -> "Avoid"
        row.compositeScore < 0 -> "Avoid"
        row.confidence == ConfidenceBand.High && row.compositeScore >= 30 -> "Act"
        else -> "Watch"
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
