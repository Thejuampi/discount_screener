package com.discountscreener.core.regime

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.FundamentalSnapshot
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
 * The Kotlin half of `shared/contracts/market-regime-fit-v1.json`.
 *
 * Windows and Android compute this bucket in different languages from mirrored arithmetic, with no
 * shared code between them. The expected values in that file are the Rust output, and Rust asserts
 * against them too — so this test is the one that can actually catch the port drifting. Nothing
 * here may be regenerated from Kotlin: doing so would make the file agree with whatever this side
 * happens to do, which is the failure mode the contract exists to prevent.
 */
class RegimeFitContractTest {
    @Test
    fun the_kotlin_port_reproduces_every_case_in_the_shared_contract() {
        assertEquals(emptyList(), contract.cases.mapNotNull(::disagreement))
    }

    /** Null when the case matches; otherwise a line naming the case and both readings. */
    private fun disagreement(case: Case): String? {
        val policy = requireNotNull(RegimeScoringPolicy.fromRegime(regimeOf(case.regime))) {
            "case ${case.name} must yield a policy"
        }
        val seenPolicy = policyOf(policy)
        val seenFit = fitOf(scoreRegimeFit(fundamentalsOf(case.symbol), case.chart?.let(::chartOf), policy))
        if (seenPolicy == case.expectedPolicy && seenFit == case.expectedFit) return null
        return "${case.name}: policy $seenPolicy vs ${case.expectedPolicy}; fit $seenFit vs ${case.expectedFit}"
    }

    private fun regimeOf(input: RegimeInput) = MarketRegime(
        primaryRegime = input.primaryRegime,
        environmentBand = input.environmentBand,
        actionStance = input.actionStance,
        globalConfidenceBps = input.globalConfidenceBps,
        preferQuality = input.preferQuality,
        breadthAboveMa200Pct = input.breadthAboveMa200Pct,
        creditScore = input.creditScore,
        cnnFearGreed = input.cnnFearGreed,
    )

    private fun fundamentalsOf(input: SymbolInput) = FundamentalSnapshot(
        symbol = "CASE",
        sectorName = input.sectorName,
        marketCapDollars = input.marketCapDollars,
        forwardPeHundredths = input.forwardPeHundredths,
        priceToBookHundredths = input.priceToBookHundredths,
        returnOnEquityBps = input.returnOnEquityBps,
        enterpriseToEbitdaHundredths = input.enterpriseToEbitdaHundredths,
        totalDebtDollars = input.totalDebtDollars,
        totalCashDollars = input.totalCashDollars,
        debtToEquityHundredths = input.debtToEquityHundredths,
        freeCashFlowDollars = input.freeCashFlowDollars,
        operatingCashFlowDollars = input.operatingCashFlowDollars,
        betaMillis = input.betaMillis,
    )

    /**
     * The contract carries `volume_ratio` as a raw ratio because that is what Rust stores; Kotlin
     * keeps hundredths. Converting here rather than storing hundredths in the file keeps the unit
     * split visible at the one place it matters — it is the mismatch that already produced a live
     * defect on Windows.
     */
    private fun chartOf(input: ChartInput) = ChartRangeSummary(
        range = ChartRange.Year,
        capturedAt = 0L,
        candleCount = 260,
        latestCloseCents = input.latestCloseCents,
        ema20Cents = input.ema20Cents,
        ema50Cents = input.ema50Cents,
        ema200Cents = input.ema200Cents,
        latestWilderRsi = input.rsi,
        volumeRatioHundredths = input.volumeRatio?.let { (it * 100.0).roundToInt() },
        pos52wPct = input.pos52wPct,
        bbPercentB = input.bbPercentB,
    )

    private fun policyOf(policy: RegimeScoringPolicy) = PolicyExpectation(
        wQuality = policy.wQuality,
        wLowBeta = policy.wLowBeta,
        wValue = policy.wValue,
        wOversoldQuality = policy.wOversoldQuality,
        wAntiExtension = policy.wAntiExtension,
        wTrend = policy.wTrend,
        wDefensive = policy.wDefensive,
        wGrowth = policy.wGrowth,
        wLiquidity = policy.wLiquidity,
        betaHaircutMult = policy.betaHaircutMult,
        strength = policy.strength,
    )

    private fun fitOf(fit: RegimeFitResult) = FitExpectation(
        score = fit.score,
        signals = fit.signals,
        unavailableReason = fit.unavailableReason?.name,
    )

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
        const val FIXTURE = "shared/contracts/market-regime-fit-v1.json"

        val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class Contract(val cases: List<Case>)

@Serializable
private data class Case(
    val name: String,
    val regime: RegimeInput,
    val symbol: SymbolInput,
    val chart: ChartInput? = null,
    @SerialName("expected_policy") val expectedPolicy: PolicyExpectation,
    @SerialName("expected_fit") val expectedFit: FitExpectation,
)

@Serializable
private data class RegimeInput(
    @SerialName("primary_regime") val primaryRegime: String,
    @SerialName("environment_band") val environmentBand: String,
    @SerialName("action_stance") val actionStance: String,
    @SerialName("global_confidence_bps") val globalConfidenceBps: Int,
    @SerialName("prefer_quality") val preferQuality: Boolean,
    @SerialName("breadth_above_ma200_pct") val breadthAboveMa200Pct: Double? = null,
    @SerialName("credit_score") val creditScore: Int? = null,
    @SerialName("cnn_fear_greed") val cnnFearGreed: Int? = null,
)

@Serializable
private data class SymbolInput(
    @SerialName("sector_name") val sectorName: String? = null,
    @SerialName("market_cap_dollars") val marketCapDollars: Long? = null,
    @SerialName("free_cash_flow_dollars") val freeCashFlowDollars: Long? = null,
    @SerialName("operating_cash_flow_dollars") val operatingCashFlowDollars: Long? = null,
    @SerialName("return_on_equity_bps") val returnOnEquityBps: Int? = null,
    @SerialName("debt_to_equity_hundredths") val debtToEquityHundredths: Int? = null,
    @SerialName("total_cash_dollars") val totalCashDollars: Long? = null,
    @SerialName("total_debt_dollars") val totalDebtDollars: Long? = null,
    @SerialName("forward_pe_hundredths") val forwardPeHundredths: Int? = null,
    @SerialName("price_to_book_hundredths") val priceToBookHundredths: Int? = null,
    @SerialName("enterprise_to_ebitda_hundredths") val enterpriseToEbitdaHundredths: Int? = null,
    @SerialName("beta_millis") val betaMillis: Int? = null,
)

@Serializable
private data class ChartInput(
    @SerialName("latest_close_cents") val latestCloseCents: Long,
    @SerialName("ema20_cents") val ema20Cents: Long? = null,
    @SerialName("ema50_cents") val ema50Cents: Long? = null,
    @SerialName("ema200_cents") val ema200Cents: Long? = null,
    val rsi: Double? = null,
    @SerialName("pos_52w_pct") val pos52wPct: Double? = null,
    @SerialName("bb_percent_b") val bbPercentB: Double? = null,
    @SerialName("volume_ratio") val volumeRatio: Double? = null,
)

@Serializable
private data class PolicyExpectation(
    @SerialName("w_quality") val wQuality: Double,
    @SerialName("w_low_beta") val wLowBeta: Double,
    @SerialName("w_value") val wValue: Double,
    @SerialName("w_oversold_quality") val wOversoldQuality: Double,
    @SerialName("w_anti_extension") val wAntiExtension: Double,
    @SerialName("w_trend") val wTrend: Double,
    @SerialName("w_defensive") val wDefensive: Double,
    @SerialName("w_growth") val wGrowth: Double,
    @SerialName("w_liquidity") val wLiquidity: Double,
    @SerialName("beta_haircut_mult") val betaHaircutMult: Double,
    val strength: Double,
)

@Serializable
private data class FitExpectation(
    val score: Int? = null,
    val signals: List<String>,
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
)
