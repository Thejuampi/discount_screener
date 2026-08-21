package com.discountscreener.core.harness

import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.engine.baseDetail
import com.discountscreener.core.model.FundamentalSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The street as scoreboard for V4's fundamentals bucket, over the captured Yahoo payloads in
 * `yahoo-scoreboard/`.
 *
 * Every number here comes from a captured upstream response — no invented payload, no network.
 * The set carries up to four names per GICS sector (materials, real estate and utilities carry
 * one each) and the financials spread (banks JPM/BAC/C, insurers L/ACGL, payments V), which is
 * what the business-class and cash-vote rules claim to sort.
 *
 * For each symbol it records what the street anchors say (target mean/low/high against the then
 * price) beside what V4's fundamentals bucket says, plus the factor-level readouts that move when
 * the driver-math or business-class rules change. Runs write one JSON file per tag so a before/after
 * diff is mechanical:
 *
 * ```
 * gradlew :core:test --tests "*V4StreetScoreboard*" -Dv4.scoreboard.tag=baseline
 * ```
 *
 * This is a measurement instrument, not an assertion suite: its value is realized in the diff of
 * two tagged runs, and it skips when the fixtures are absent rather than failing a clean checkout.
 */
/** One universe for the capture assembler and the scoreboard, so neither can drift from the other. */
internal val V4_SCOREBOARD_SYMBOLS = listOf(
    // Technology
    "AAPL", "MSFT", "NVDA",
    // Communication services
    "GOOGL", "META", "T",
    // Consumer cyclical
    "AMZN", "HD", "TSLA", "F",
    // Consumer defensive
    "PG", "WMT",
    // Healthcare (CI is managed care)
    "UNH", "JNJ", "MRK", "CI",
    // Energy
    "XOM", "CVX",
    // Industrials
    "CAT", "UPS",
    // Basic materials / real estate / utilities
    "LIN", "AMT", "NEE",
    // Financial services: banks JPM/BAC/C, insurers L/ACGL, payments V
    "JPM", "BAC", "V", "ACGL", "C", "L",
)

class V4StreetScoreboardHarnessTest {

    @Test
    fun the_scoreboard_is_written_for_the_current_engine() {
        assumeTrue(
            javaClass.classLoader.getResource("yahoo-scoreboard/quoteSummary/AAPL.json") != null,
            "scoreboard fixtures absent — assemble them with V4ScoreboardFixtureCaptureTest (-Dv4.capture=live)",
        )

        var rows = SYMBOLS.map(::scoreboardRow)
        assertEquals(SYMBOLS.size, rows.size)

        var spearman = spearman(rows)
        Files.createDirectories(OUT_DIR)
        Files.writeString(OUT_DIR.resolve("v4-street-scoreboard-$TAG.json"), JSON.encodeToString(Scoreboard(rows, spearman)))

        rows.forEach { row ->
            println("${row.symbol}: v4=${row.v4FundamentalsScore} street=${row.streetUpsideBps}bps factors=${row.factors}")
        }
        println("spearman(v4, street) = $spearman")
    }

    private fun scoreboardRow(symbol: String): Row {
        var transport = ScoreboardFixtureTransport()
        var bundle = YahooQuantLiveClient(transport).fetch(symbol)
        var quote = parseQuoteSummary(transport.quoteSummary(symbol), symbol)
        var street = StreetAnchors.of(transport.quoteSummary(symbol))
        var evidence = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail = baseDetail(fundamentals = quote),
            sectorBenchmarks = null,
            timeseries = bundle.timeseries,
        )
        return Row(
            symbol = symbol,
            marketPriceCents = street.currentPriceCents,
            streetUpsideBps = street.upsideBps,
            streetLowBps = street.lowBps,
            streetHighBps = street.highBps,
            recommendationMeanHundredths = street.recommendationMeanHundredths,
            analystCount = street.analystCount,
            v4FundamentalsScore = evidence.score,
            factors = evidence.factors.map { "${it.key}:${it.bucketPoints}" },
        )
    }

    /** Rank correlation between the bucket and the street's target-mean upside, over shared rows. */
    internal fun spearman(rows: List<Row>): Double? {
        var pairs = rows.mapNotNull { row ->
            row.v4FundamentalsScore?.let { score -> row.streetUpsideBps?.let { score to it } }
        }
        if (pairs.size < 3) return null
        var scores = pairs.map { it.first.toDouble() }
        var upsides = pairs.map { it.second.toDouble() }
        // A flat series has no correlation, and 0.0 would dress "undefined" as a measured zero.
        if (scores.distinct().size <= 1 || upsides.distinct().size <= 1) return null
        return pearson(rank(scores), rank(upsides))
    }

    private fun rank(values: List<Double>): List<Double> {
        var sortedWithIndex = values.withIndex().sortedBy { it.value }
        var ranks = DoubleArray(values.size)
        var index = 0
        while (index < sortedWithIndex.size) {
            var last = index
            while (last + 1 < sortedWithIndex.size && sortedWithIndex[last + 1].value == sortedWithIndex[index].value) {
                last += 1
            }
            var averageRank = ((index + last) / 2.0) + 1.0
            for (position in index..last) {
                ranks[sortedWithIndex[position].index] = averageRank
            }
            index = last + 1
        }
        return ranks.toList()
    }

    private fun pearson(xs: List<Double>, ys: List<Double>): Double {
        var xMean = xs.average()
        var yMean = ys.average()
        var numerator = xs.indices.sumOf { (xs[it] - xMean) * (ys[it] - yMean) }
        var xDen = xs.sumOf { (it - xMean) * (it - xMean) }
        var yDen = ys.sumOf { (it - yMean) * (it - yMean) }
        var denominator = sqrt(xDen * yDen)
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    @Serializable
    internal data class Row(
        val symbol: String,
        val marketPriceCents: Long?,
        val streetUpsideBps: Int?,
        val streetLowBps: Int?,
        val streetHighBps: Int?,
        val recommendationMeanHundredths: Int?,
        val analystCount: Int?,
        val v4FundamentalsScore: Int?,
        val factors: List<String>,
    )

    @Serializable
    internal data class Scoreboard(val rows: List<Row>, val spearmanV4VsStreet: Double?)

    /** Street anchors read straight from the captured quoteSummary JSON. */
    private class StreetAnchors(
        val currentPriceCents: Long?,
        val upsideBps: Int?,
        val lowBps: Int?,
        val highBps: Int?,
        val recommendationMeanHundredths: Int?,
        val analystCount: Int?,
    ) {
        companion object {
            fun of(body: String): StreetAnchors {
                var result = Json.parseToJsonElement(body).jsonObject["quoteSummary"]!!
                    .jsonObject["result"]!!.jsonArray.first().jsonObject
                var financialData = result["financialData"]?.jsonObject
                var current = financialData.raw("currentPrice")
                var mean = financialData.raw("targetMeanPrice")
                var low = financialData.raw("targetLowPrice")
                var high = financialData.raw("targetHighPrice")

                fun vs(value: Double?, base: Double?): Int? {
                    if (value == null || base == null || base <= 0.0) return null
                    return (((value / base) - 1.0) * 10_000.0).roundToInt()
                }

                return StreetAnchors(
                    currentPriceCents = current?.times(100.0)?.roundToInt()?.toLong(),
                    upsideBps = vs(mean, current),
                    lowBps = vs(low, current),
                    highBps = vs(high, current),
                    recommendationMeanHundredths = financialData.raw("recommendationMean")?.times(100.0)?.roundToInt(),
                    analystCount = financialData.raw("numberOfAnalystOpinions")?.toInt(),
                )
            }

            /** Yahoo nests every metric as `{ "raw": …, "fmt": … }`; only the raw number speaks here. */
            private fun kotlinx.serialization.json.JsonObject?.raw(name: String): Double? =
                this?.get(name)?.jsonObject?.get("raw")?.jsonPrimitive?.doubleOrNull
        }
    }

    companion object {
        private val SYMBOLS = V4_SCOREBOARD_SYMBOLS

        // The test JVM's working directory is this module's directory (apps/android/core), so
        // three levels up is the repository root where .agents/workspace/tmp lives.
        private val OUT_DIR: Path = Path.of(
            "../../../.agents/workspace/tmp/v4-street-scoreboard",
        )

        private val TAG: String = System.getProperty("v4.scoreboard.tag") ?: "run"

        private val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }

    /**
     * The scoreboard's own fixture set, under `yahoo-scoreboard/`, assembled by
     * [V4ScoreboardFixtureCaptureTest] from one capture session — so a study never mixes vintages,
     * and the original five fixtures keep serving the parsing tests untouched.
     */
    private class ScoreboardFixtureTransport : YahooTransport {
        override fun quoteSummary(symbol: String): String = read("yahoo-scoreboard/quoteSummary/$symbol.json")

        override fun timeseries(symbol: String): String? =
            runCatching { read("yahoo-scoreboard/timeseries/$symbol.json") }.getOrNull()

        private fun read(path: String): String {
            var stream = javaClass.classLoader.getResourceAsStream(path)
                ?: error("scoreboard fixture missing: $path")
            return stream.bufferedReader().use { it.readText() }
        }
    }
}
