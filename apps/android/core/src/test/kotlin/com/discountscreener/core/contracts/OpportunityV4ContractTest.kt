package com.discountscreener.core.contracts

import com.discountscreener.core.engine.FUND_COVERAGE_GAP_LABEL
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

    /**
     * The control the first two rounds of this contract did not have.
     *
     * Reproducing an expected value proves the arithmetic. It does not prove the case can tell a
     * right constant from a wrong one, and six cases here could not: each sat exactly on an anchor
     * computed from the constant it claimed to bind, so it scored the same under both. A mutation
     * round missed it by moving every constant in one direction only — the direction those cases
     * happened to see. The product was double-checked and the instrument was not.
     *
     * So each case now states what it discriminates, and this test refuses the claim if it is
     * empty: a mutant that scores what the case already expects has measured nothing, and a
     * constant probed on one side only is exactly the hole that let the six through.
     */
    @Test
    fun every_fundamentals_case_states_what_a_wrong_constant_would_cost_it() {
        assertEquals(emptyList(), contract.fundamentalsCases.flatMap(::undischargedClaims))
    }

    /**
     * What lets [scoreOf] drop the coverage flag.
     *
     * The filter is only sound while the flag says nothing about a case, and that holds because it
     * fires on all of them. This asserts it, so a flag that stopped firing — or fired on a subset —
     * fails here rather than passing silently through a filter that then hides a real difference.
     *
     * Every reading the filter touches is checked, and [scoreOf] runs twice for a case that
     * declares an absolute fallback. Checking only the benchmark reading would leave the other one
     * filtered with nothing behind it.
     */
    @Test
    fun the_coverage_flag_fires_on_every_reading_this_file_filters() {
        assertEquals(emptyList(), contract.fundamentalsCases.flatMap(::readingsWithoutCoverageFlag))
    }

    private fun readingsWithoutCoverageFlag(case: FundamentalsCase): List<String> = benchmarkReadings(case)
        .filterNot { (_, benchmarks) -> evidenceOf(case, benchmarks).signals.contains(FUND_COVERAGE_GAP_LABEL) }
        .map { (reading, _) -> "${case.name} [$reading]" }

    /** The benchmark configurations [fundamentalsDisagreements] scores this case under. */
    private fun benchmarkReadings(case: FundamentalsCase): List<Pair<String, SectorBenchmarks?>> = buildList {
        add("with benchmarks" to case.sectorBenchmarks?.toBenchmarks())
        if (case.expectedWithoutBenchmarks != null) add("absolute fallback" to null)
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

    /** Empty when the case's discrimination claim holds up; otherwise a line per hole in it. */
    private fun undischargedClaims(case: FundamentalsCase): List<String> {
        var mutants = case.mutants
        if (mutants.isEmpty()) {
            if (case.bindsNoConstant != null) return emptyList()
            return listOf("${case.name}: declares neither `mutants` nor `binds_no_constant`")
        }
        if (case.bindsNoConstant != null) {
            return listOf("${case.name}: declares both `mutants` and `binds_no_constant`")
        }
        var lines = mutableListOf<String>()
        for (mutant in mutants) {
            var trueValue = contract.provenance.constantsBound[mutant.constant]
            if (trueValue == null) {
                lines += "${case.name}: `${mutant.constant}` is not listed in constants_bound"
            } else if (mutant.value == trueValue) {
                lines += "${case.name}: `${mutant.constant}` mutant equals the true value $trueValue"
            }
            if (mutant.score == case.expected.score) {
                lines += "${case.name}: `${mutant.constant}` at ${mutant.value} scores ${mutant.score}, " +
                    "which is what the case already expects — it discriminates nothing"
            }
        }
        for ((constant, probes) in mutants.groupBy { it.constant }) {
            var trueValue = contract.provenance.constantsBound[constant] ?: continue
            if (probes.none { it.value < trueValue } || probes.none { it.value > trueValue }) {
                lines += "${case.name}: `$constant` is probed on one side of $trueValue only"
            }
        }
        return lines
    }

    /**
     * The reading the contract binds: the score, and the signals the terms produced.
     *
     * [FUND_COVERAGE_GAP_LABEL] is filtered out. It is not a term and carries no points; it says
     * the budget was mostly idle, which is true of every case in this file by construction — each
     * one supplies the inputs of a single term so the constant it probes is the only thing moving.
     * A flag that fires on the whole population separates no case from any other, and writing it
     * into the expected signals of all of them would put Kotlin's output in a file whose whole
     * value is that it was derived by hand. The claim that it fires everywhere is not assumed:
     * [the_coverage_flag_fires_on_every_reading_this_file_filters] asserts it.
     */
    private fun scoreOf(case: FundamentalsCase, benchmarks: SectorBenchmarks?): FundamentalsExpectation {
        var (score, signals) = evidenceOf(case, benchmarks)
        return FundamentalsExpectation(score = score, signals = signals.filter { it != FUND_COVERAGE_GAP_LABEL })
    }

    private fun evidenceOf(case: FundamentalsCase, benchmarks: SectorBenchmarks?) = OpportunityEngine
        .aggressiveV4FundamentalsScore(
            detail = detailOf(case.fundamentals),
            sectorBenchmarks = benchmarks,
            timeseries = timeseriesOf(case),
        )

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
            industryKey = input.industryKey,
            forwardPeHundredths = input.forwardPeHundredths,
            enterpriseToEbitdaHundredths = input.enterpriseToEbitdaHundredths,
            priceToBookHundredths = input.priceToBookHundredths,
            returnOnEquityBps = input.returnOnEquityBps,
            earningsGrowthBps = input.earningsGrowthBps,
            trailingEpsCents = input.trailingEpsCents,
            totalDebtDollars = input.totalDebtDollars,
            totalCashDollars = input.totalCashDollars,
            ebitdaDollars = input.ebitdaDollars,
            debtToEquityHundredths = input.debtToEquityHundredths,
        ),
    )

    /**
     * Annual series, oldest first. Dates only keep the order the providers guarantee.
     */
    private fun timeseriesOf(case: FundamentalsCase): FundamentalTimeseries? {
        if (case.dilutedAverageShares == null && case.revenue == null && case.netIncome == null) {
            return null
        }
        return FundamentalTimeseries(
            dilutedAverageShares = annual(case.dilutedAverageShares),
            revenue = annual(case.revenue),
            netIncome = annual(case.netIncome),
        )
    }

    private fun annual(values: List<Double>?) = values.orEmpty().mapIndexed { index, value ->
        AnnualReportedValue(asOfDate = "${2020 + index}-12-31", value = value)
    }

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
    val provenance: Provenance,
    @SerialName("composite_cases") val compositeCases: List<CompositeCase>,
    @SerialName("fundamentals_cases") val fundamentalsCases: List<FundamentalsCase>,
)

/** `constants_bound` is the true value a mutant must be measured against, so it lives in the file. */
@Serializable
private data class Provenance(
    @SerialName("constants_bound") val constantsBound: Map<String, Double>,
)

/** One wrong value of one constant, and what this case would score if a port used it. */
@Serializable
private data class Mutant(val constant: String, val value: Double, val score: Int?)

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
    val revenue: List<Double>? = null,
    @SerialName("net_income") val netIncome: List<Double>? = null,
    @SerialName("sector_benchmarks") val sectorBenchmarks: BenchmarksInput? = null,
    val expected: FundamentalsExpectation,
    @SerialName("expected_without_benchmarks") val expectedWithoutBenchmarks: FundamentalsExpectation? = null,
    val mutants: List<Mutant> = emptyList(),
    @SerialName("binds_no_constant") val bindsNoConstant: String? = null,
)

@Serializable
private data class FundamentalsInput(
    @SerialName("forward_pe_hundredths") val forwardPeHundredths: Int? = null,
    @SerialName("enterprise_to_ebitda_hundredths") val enterpriseToEbitdaHundredths: Int? = null,
    @SerialName("price_to_book_hundredths") val priceToBookHundredths: Int? = null,
    @SerialName("return_on_equity_bps") val returnOnEquityBps: Int? = null,
    @SerialName("earnings_growth_bps") val earningsGrowthBps: Int? = null,
    @SerialName("trailing_eps_cents") val trailingEpsCents: Long? = null,
    @SerialName("total_debt_dollars") val totalDebtDollars: Long? = null,
    @SerialName("total_cash_dollars") val totalCashDollars: Long? = null,
    @SerialName("ebitda_dollars") val ebitdaDollars: Long? = null,
    @SerialName("debt_to_equity_hundredths") val debtToEquityHundredths: Int? = null,
    /** Read before the sector name, so a case can put a cyclical industry inside any sector. */
    @SerialName("industry_key") val industryKey: String? = null,
)

@Serializable
private data class BenchmarksInput(
    @SerialName("forward_pe_hundredths") val forwardPeHundredths: Int? = null,
    @SerialName("enterprise_to_ebitda_hundredths") val enterpriseToEbitdaHundredths: Int? = null,
    @SerialName("price_to_book_hundredths") val priceToBookHundredths: Int? = null,
    @SerialName("return_on_equity_bps") val returnOnEquityBps: Int? = null,
    @SerialName("net_debt_to_ebitda_hundredths") val netDebtToEbitdaHundredths: Int? = null,
) {
    fun toBenchmarks() = SectorBenchmarks(
        forwardPeHundredths = forwardPeHundredths,
        enterpriseToEbitdaHundredths = enterpriseToEbitdaHundredths,
        priceToBookHundredths = priceToBookHundredths,
        returnOnEquityBps = returnOnEquityBps,
        netDebtToEbitdaHundredths = netDebtToEbitdaHundredths,
    )
}

@Serializable
private data class FundamentalsExpectation(val score: Int? = null, val signals: List<String>)
