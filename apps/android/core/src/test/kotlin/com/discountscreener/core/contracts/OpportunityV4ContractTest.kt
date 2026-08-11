package com.discountscreener.core.contracts

import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.engine.SectorBenchmarks
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Kotlin half of `shared/contracts/opportunity-v4.json`.
 *
 * Windows has no V4, so unlike `market-regime-fit-v1.json` this contract cannot be the Rust output.
 * Its independence comes from somewhere else: every expected value in that file was derived by hand
 * from the constants and the formulas, and written down before this test was run. That is the only
 * thing standing between the fixture and a second reading of the same code, so **no expected value
 * in it may ever be regenerated from Kotlin**. A disagreement is a question about which side is
 * wrong, never a licence to copy this side's answer over.
 */
class OpportunityV4ContractTest {
    @Test
    fun the_composite_reproduces_every_case_in_the_shared_contract() {
        assertEquals(emptyList(), contract.compositeCases.mapNotNull(::compositeDisagreement))
    }

    @Test
    fun the_fundamentals_bucket_reproduces_every_case_in_the_shared_contract() {
        assertEquals(emptyList(), contract.fundamentalsCases.flatMap(::fundamentalsDisagreements))
    }

    /** Null when the case matches; otherwise a line naming the case and both readings. */
    private fun compositeDisagreement(case: CompositeCase): String? {
        var buckets = case.buckets
        var reading = OpportunityEngine.v4AgreementReading(
            fundamentals = buckets.fundamentals,
            technical = buckets.technical,
            forecast = buckets.forecast,
            regime = buckets.regime,
        )
        var seen = CompositeExpectation(
            centreHundredths = reading?.centre?.let(::hundredths),
            spreadHundredths = reading?.spread?.let(::hundredths),
            bonusHundredths = reading?.bonus?.let(::hundredths),
            composite = OpportunityEngine.compositeScoreFor(
                model = OpportunityScoringModel.AggressiveV4,
                fundamentals = buckets.fundamentals,
                technical = buckets.technical,
                forecast = buckets.forecast,
                regime = buckets.regime,
                coverageCount = buckets.presentCount(),
                betaMillis = case.betaMillis,
                betaHaircutMult = case.betaHaircutMult,
            ),
        )
        if (seen == case.expected) return null
        return "${case.name}: $seen vs ${case.expected}"
    }

    /**
     * Both readings of one case: with the sector benchmarks, and — when the case supplies the second
     * expectation — with them withheld. The pair is what proves the benchmark was read at all.
     */
    private fun fundamentalsDisagreements(case: FundamentalsCase): List<String> {
        var withBenchmarks = scoreOf(case, case.sectorBenchmarks?.toBenchmarks())
        var lines = mutableListOf<String>()
        if (withBenchmarks != case.expected) {
            lines += "${case.name} [with benchmarks]: $withBenchmarks vs ${case.expected}"
        }
        var absolute = case.expectedWithoutBenchmarks ?: return lines
        var withoutBenchmarks = scoreOf(case, null)
        if (withoutBenchmarks != absolute) {
            lines += "${case.name} [absolute fallback]: $withoutBenchmarks vs $absolute"
        }
        return lines
    }

    private fun scoreOf(case: FundamentalsCase, benchmarks: SectorBenchmarks?): FundamentalsExpectation {
        var (score, signals) = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail = detailOf(case.fundamentals),
            sectorBenchmarks = benchmarks,
            timeseries = case.dilutedAverageShares?.let(::timeseriesOf),
        )
        return FundamentalsExpectation(score = score, signals = signals)
    }

    private fun detailOf(input: FundamentalsInput) = SymbolDetail(
        symbol = SYMBOL,
        profitable = true,
        marketPriceCents = 10_000,
        intrinsicValueCents = 12_000,
        gapBps = 2_000,
        minimumGapBps = 1_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
        fundamentals = FundamentalSnapshot(
            symbol = SYMBOL,
            sectorName = SECTOR,
            forwardPeHundredths = input.forwardPeHundredths,
            enterpriseToEbitdaHundredths = input.enterpriseToEbitdaHundredths,
            priceToBookHundredths = input.priceToBookHundredths,
            returnOnEquityBps = input.returnOnEquityBps,
        ),
    )

    /**
     * The share series, oldest first. Only the last two points are read, and only their ratio, so
     * the dates carry no weight here beyond keeping the order the providers guarantee.
     */
    private fun timeseriesOf(shares: List<Double>) = FundamentalTimeseries(
        dilutedAverageShares = shares.mapIndexed { index, value ->
            AnnualReportedValue(asOfDate = "${2020 + index}-12-31", value = value)
        },
    )

    /** The contract carries intermediates as hundredths so no case turns on a last-bit difference. */
    private fun hundredths(value: Double): Int = (value * 100.0).roundToInt()

    private val contract: Contract by lazy { JSON.decodeFromString(Files.readString(findFixture())) }

    private fun findFixture(): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(8) {
            val candidate = current.resolve(FIXTURE).normalize()
            if (Files.exists(candidate)) return candidate
            current = current.parent ?: return@repeat
        }
        error("$FIXTURE not found from ${Paths.get("").toAbsolutePath()}")
    }

    private companion object {
        const val FIXTURE = "shared/contracts/opportunity-v4.json"
        const val SYMBOL = "CASE"
        const val SECTOR = "Technology"

        val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class Contract(
    @SerialName("composite_cases") val compositeCases: List<CompositeCase>,
    @SerialName("fundamentals_cases") val fundamentalsCases: List<FundamentalsCase>,
)

@Serializable
private data class CompositeCase(
    val name: String,
    val buckets: Buckets,
    @SerialName("beta_millis") val betaMillis: Int? = null,
    @SerialName("beta_haircut_mult") val betaHaircutMult: Double,
    val expected: CompositeExpectation,
)

@Serializable
private data class Buckets(
    val fundamentals: Int? = null,
    val technical: Int? = null,
    val forecast: Int? = null,
    val regime: Int? = null,
) {
    fun presentCount(): Int = listOfNotNull(fundamentals, technical, forecast, regime).size
}

@Serializable
private data class CompositeExpectation(
    @SerialName("centre_hundredths") val centreHundredths: Int? = null,
    @SerialName("spread_hundredths") val spreadHundredths: Int? = null,
    @SerialName("bonus_hundredths") val bonusHundredths: Int? = null,
    val composite: Int,
)

@Serializable
private data class FundamentalsCase(
    val name: String,
    val fundamentals: FundamentalsInput,
    @SerialName("diluted_average_shares") val dilutedAverageShares: List<Double>? = null,
    @SerialName("sector_benchmarks") val sectorBenchmarks: BenchmarksInput? = null,
    val expected: FundamentalsExpectation,
    @SerialName("expected_without_benchmarks") val expectedWithoutBenchmarks: FundamentalsExpectation? = null,
)

@Serializable
private data class FundamentalsInput(
    @SerialName("forward_pe_hundredths") val forwardPeHundredths: Int? = null,
    @SerialName("enterprise_to_ebitda_hundredths") val enterpriseToEbitdaHundredths: Int? = null,
    @SerialName("price_to_book_hundredths") val priceToBookHundredths: Int? = null,
    @SerialName("return_on_equity_bps") val returnOnEquityBps: Int? = null,
)

@Serializable
private data class BenchmarksInput(
    @SerialName("forward_pe_hundredths") val forwardPeHundredths: Int? = null,
    @SerialName("enterprise_to_ebitda_hundredths") val enterpriseToEbitdaHundredths: Int? = null,
    @SerialName("price_to_book_hundredths") val priceToBookHundredths: Int? = null,
    @SerialName("return_on_equity_bps") val returnOnEquityBps: Int? = null,
) {
    fun toBenchmarks() = SectorBenchmarks(
        forwardPeHundredths = forwardPeHundredths,
        enterpriseToEbitdaHundredths = enterpriseToEbitdaHundredths,
        priceToBookHundredths = priceToBookHundredths,
        returnOnEquityBps = returnOnEquityBps,
    )
}

@Serializable
private data class FundamentalsExpectation(val score: Int? = null, val signals: List<String>)
