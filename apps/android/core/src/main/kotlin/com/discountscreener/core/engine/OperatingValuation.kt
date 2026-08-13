package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.WaccFieldSource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigInteger

const val DISPUTED_DIFFERENCE_BPS = 5_000L
private const val HARD_MAX_PROJECTION_YEARS = 100

object OperatingValuation {
    const val ENGINE_VERSION = "operating-valuation-router/2"
    const val ROUTER_POLICY_VERSION = "operating-model-router-policy/1"

    fun valueForwardEarnings(input: ForwardEarningsInput): ForwardEarningsCandidate =
        com.discountscreener.core.engine.valueForwardEarnings(input)

    fun routeOperatingModels(input: OperatingRouteInput): OperatingRouteDecision =
        com.discountscreener.core.engine.routeOperatingModels(input)
}

private const val BPS_SCALE_INT = 10_000
private val BPS_SCALE = BigInteger.valueOf(BPS_SCALE_INT.toLong())
private val I128_MIN = BigInteger.ONE.shiftLeft(127).negate()
private val I128_MAX = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)

@Serializable
enum class OperatingModel {
    @SerialName("fcff_wacc") FcffWacc,
    @SerialName("forward_earnings_power") ForwardEarningsPower,
}

@Serializable
enum class CandidateStatus {
    @SerialName("available") Available,
    @SerialName("unavailable") Unavailable,
}

@Serializable
enum class RouteStatus {
    @SerialName("selected") Selected,
    @SerialName("disputed") Disputed,
    @SerialName("unavailable") Unavailable,
    @SerialName("not_eligible") NotEligible,
}

@Serializable
enum class ModelQuality {
    @SerialName("solid") Solid,
    @SerialName("soft") Soft,
}

@Serializable
enum class EvidenceFamily {
    @SerialName("cash_flow_model") CashFlowModel,
    @SerialName("analyst_derived_model") AnalystDerivedModel,
}

@Serializable
enum class CandidateRefusal {
    @SerialName("missing_forward_eps") MissingForwardEps,
    @SerialName("non_positive_forward_eps") NonPositiveForwardEps,
    @SerialName("invalid_forecast_range") InvalidForecastRange,
    @SerialName("missing_coverage") MissingCoverage,
    @SerialName("sparse_coverage") SparseCoverage,
    @SerialName("stale_forecast") StaleForecast,
    @SerialName("currency_mismatch") CurrencyMismatch,
    @SerialName("missing_currency") MissingCurrency,
    @SerialName("missing_source_fingerprint") MissingSourceFingerprint,
    @SerialName("invalid_policy") InvalidPolicy,
    @SerialName("invalid_forecast_period") InvalidForecastPeriod,
    @SerialName("invalid_projection_horizon") InvalidProjectionHorizon,
    @SerialName("invalid_growth") InvalidGrowth,
    @SerialName("invalid_cost_of_equity") InvalidCostOfEquity,
    @SerialName("cost_of_equity_not_above_stable_growth") CostOfEquityNotAboveStableGrowth,
    @SerialName("arithmetic_overflow") ArithmeticOverflow,
    @SerialName("non_positive_projected_value") NonPositiveProjectedValue,
}

@Serializable
enum class StructuralDistortion {
    @SerialName("trailing_cash_unavailable") TrailingCashUnavailable,
    @SerialName("through_cycle_required") ThroughCycleRequired,
    @SerialName("extreme_leverage") ExtremeLeverage,
    @SerialName("source_discontinuity") SourceDiscontinuity,
    @SerialName("thin_normalized_fcff_margin") ThinNormalizedFcffMargin,
    @SerialName("latest_capex_spike") LatestCapexSpike,
    @SerialName("acquisition_discontinuity") AcquisitionDiscontinuity,
    @SerialName("durable_excess_return_evidence") DurableExcessReturnEvidence,
}

@Serializable
enum class RouteReason {
    @SerialName("family_financial_services") FamilyFinancialServices,
    @SerialName("family_not_eligible") FamilyNotEligible,
    @SerialName("family_unclassified") FamilyUnclassified,
    @SerialName("structural_distortion_present") StructuralDistortionPresent,
    @SerialName("selected_forward_earnings_power") SelectedForwardEarningsPower,
    @SerialName("selected_representative_fcff") SelectedRepresentativeFcff,
    @SerialName("forward_candidate_unavailable") ForwardCandidateUnavailable,
    @SerialName("fcff_candidate_unavailable") FcffCandidateUnavailable,
    @SerialName("forward_requires_structural_distortion") ForwardRequiresStructuralDistortion,
    @SerialName("candidate_disagreement") CandidateDisagreement,
    @SerialName("invalid_forward_candidate") InvalidForwardCandidate,
    @SerialName("invalid_fcff_candidate") InvalidFcffCandidate,

    /**
     * The two lanes disagreed materially and the dispute was resolved to the lane whose evidence
     * set strictly contains the other's. Recorded alongside [CandidateDisagreement], never instead
     * of it.
     *
     * Declared last on purpose: reasons are canonicalised by sorting, and on both platforms that
     * sort is by declaration order. Inserting this anywhere else would reorder every fingerprint
     * that carries it and silently break parity with Rust's `RouteReason`.
     */
    @SerialName("disagreement_resolved_to_forward_evidence") DisagreementResolvedToForwardEvidence,
}

@Serializable
data class ForwardForecast(
    val epsLowCents: Long?,
    val epsMeanCents: Long?,
    val epsHighCents: Long?,
    val analystCount: Int?,
    val nearGrowthBps: Int,
    val currency: String,
    val observedEpochDay: Long,
    val forecastPeriodEndEpochDay: Long,
    val sourceFingerprint: String,
)

@Serializable
data class ProjectionPolicy(
    val version: String,
    val expectedCurrency: String,
    val maxAgeDays: Long,
    val minForecastHorizonDays: Long,
    val maxForecastHorizonDays: Long,
    val minAnalystCount: Int,
    val holdYears: Int,
    val fadeYears: Int,
    val maxProjectionYears: Int,
    val macroStableGrowthBps: Int,
    val riskFreeRateBps: Int,
    val riskFreeBufferBps: Int,
    val minimumTerminalSpreadBps: Int,
) {
    fun stableGrowthBps(costOfEquityBps: Int): Int? = try {
        minOf(
            macroStableGrowthBps,
            Math.subtractExact(riskFreeRateBps, riskFreeBufferBps),
            Math.subtractExact(costOfEquityBps, minimumTerminalSpreadBps),
        )
    } catch (_: ArithmeticException) {
        null
    }
}

@Serializable
data class ResolvedCostOfEquity(
    val costOfEquityBps: Int,
    @Serializable(with = WaccFieldSourceSnakeCaseSerializer::class)
    val betaSource: WaccFieldSource,
    val provisional: Boolean,
    val marketParamsAsOfEpoch: Long?,
    val sourceFingerprint: String,
    /** Industry prior used in shrink (millis); from industry-beta-policy/1. */
    val industryBetaMillis: Int = 1_000,
    val throughCyclePrior: Boolean = false,
    val industryBetaPolicyVersion: String = INDUSTRY_BETA_POLICY_VERSION,
    val industryBetaEntryId: String = "default",
)

@Serializable
data class ForwardEarningsInput(
    val asOfEpochDay: Long,
    val forecast: ForwardForecast,
    val costOfEquity: ResolvedCostOfEquity,
    val policy: ProjectionPolicy,
    /**
     * Return on total capital (bps) used to charge perpetual growth. `null` means *no evidence*,
     * and [terminalPayoutBps] then assumes the issuer earns exactly its cost of capital so growth
     * is value-neutral. It is not a floor on measured returns — see that function.
     */
    val returnOnCapitalBps: Int? = null,
)

@Serializable
data class ForwardEarningsCandidate(
    val model: OperatingModel,
    val status: CandidateStatus,
    val intrinsicValueCents: Long?,
    val costOfEquityBps: Int,
    val stableGrowthBps: Int?,
    val projectionYears: Int?,
    val quality: ModelQuality,
    val evidenceFamily: EvidenceFamily,
    val refusals: List<CandidateRefusal>,
    val provenance: ForwardEarningsInput,
    val fingerprint: String,
)

@Serializable
data class FcffCandidate(
    val status: CandidateStatus,
    val intrinsicValueCents: Long?,
    val quality: ModelQuality,
    val refusalCodes: List<String>,
    val fingerprint: String,
)

@Serializable
data class OperatingRouteInput(
    @Serializable(with = BusinessClassSnakeCaseSerializer::class)
    val businessClass: BusinessClass,
    val fcffCandidate: FcffCandidate,
    val forwardCandidate: ForwardEarningsCandidate,
    val structuralDistortions: List<StructuralDistortion>,
)

@Serializable
data class OperatingRouteDecision(
    val status: RouteStatus,
    val selectedModel: OperatingModel?,
    val selectedValueCents: Long?,
    val candidateDifferenceBps: Long?,
    val reasons: List<RouteReason>,
    val structuralDistortions: List<StructuralDistortion>,
    val fcffCandidate: FcffCandidate,
    val forwardCandidate: ForwardEarningsCandidate,
    val fingerprint: String,
)

fun valueForwardEarnings(input: ForwardEarningsInput): ForwardEarningsCandidate {
    val refusals = validateForwardInput(input).distinct().sorted()
    val stableGrowthBps = input.policy.stableGrowthBps(input.costOfEquity.costOfEquityBps)
    val projectionYears = projectionYears(input.policy)
    val fingerprint = forwardFingerprint(input, stableGrowthBps)
    if (refusals.isNotEmpty()) {
        return unavailableForward(input, stableGrowthBps, projectionYears, refusals, fingerprint)
    }

    val value = projectForwardValue(
        epsMeanCents = requireNotNull(input.forecast.epsMeanCents),
        nearGrowthBps = input.forecast.nearGrowthBps,
        costOfEquityBps = input.costOfEquity.costOfEquityBps,
        stableGrowthBps = requireNotNull(stableGrowthBps),
        holdYears = input.policy.holdYears,
        fadeYears = input.policy.fadeYears,
        terminalPayoutBps = terminalPayoutBps(
            input.returnOnCapitalBps,
            input.costOfEquity.costOfEquityBps,
            requireNotNull(stableGrowthBps),
        ),
    )
    return if (value != null && value > 0) {
        ForwardEarningsCandidate(
            OperatingModel.ForwardEarningsPower,
            CandidateStatus.Available,
            value,
            input.costOfEquity.costOfEquityBps,
            stableGrowthBps,
            projectionYears,
            // Solid when CoE is market-sourced (non-provisional). Soft when rates still bootstrap
            // from policy defaults.
            if (input.costOfEquity.provisional) ModelQuality.Soft else ModelQuality.Solid,
            EvidenceFamily.AnalystDerivedModel,
            emptyList(),
            input,
            fingerprint,
        )
    } else if (value != null) {
        unavailableForward(
            input,
            stableGrowthBps,
            projectionYears,
            listOf(CandidateRefusal.NonPositiveProjectedValue),
            fingerprint,
        )
    } else {
        unavailableForward(input, stableGrowthBps, projectionYears, listOf(CandidateRefusal.ArithmeticOverflow), fingerprint)
    }
}

fun routeOperatingModels(input: OperatingRouteInput): OperatingRouteDecision {
    val structural = input.structuralDistortions.distinct().sorted()
    val forwardConsistent = validForwardCandidate(input.forwardCandidate)
    val fcffConsistent = validFcffCandidate(input.fcffCandidate)
    val forward = if (forwardConsistent) candidateValue(input.forwardCandidate.status, input.forwardCandidate.intrinsicValueCents) else null
    val fcff = if (fcffConsistent) candidateValue(input.fcffCandidate.status, input.fcffCandidate.intrinsicValueCents) else null
    val reasons = mutableListOf<RouteReason>()
    if (!forwardConsistent) reasons += RouteReason.InvalidForwardCandidate
    if (!fcffConsistent) reasons += RouteReason.InvalidFcffCandidate
    var status: RouteStatus
    var selectedModel: OperatingModel? = null
    var selectedValue: Long? = null
    var difference: Long? = null

    when (input.businessClass) {
        BusinessClass.FinancialServices -> {
            reasons += RouteReason.FamilyFinancialServices
            status = RouteStatus.Unavailable
        }
        BusinessClass.NotEligible -> {
            reasons += RouteReason.FamilyNotEligible
            status = RouteStatus.NotEligible
        }
        BusinessClass.Unclassified -> {
            reasons += RouteReason.FamilyUnclassified
            status = RouteStatus.Unavailable
        }
        BusinessClass.OperatingNonFinancial -> if (structural.isNotEmpty()) {
            reasons += RouteReason.StructuralDistortionPresent
            when {
                forward != null && fcff != null -> {
                    difference = differenceBps(forward, fcff)
                    val forwardSolid = input.forwardCandidate.quality == ModelQuality.Solid
                    val fcffSolid = input.fcffCandidate.quality == ModelQuality.Solid
                    status = if (difference != null && difference > DISPUTED_DIFFERENCE_BPS) {
                        // Material disagreement stays labelled `Disputed`, but it no longer
                        // suppresses the number. This branch is reached only under structural
                        // distortion — the exact condition under which the trailing series is
                        // known to be contaminated — and the forward lane observes that same
                        // filed history plus a forecast. Its evidence set strictly contains the
                        // FCFF lane's, so the disagreement resolves toward it on evidence grounds
                        // alone. `Disputed` keeps the disagreement visible and keeps the name out
                        // of ranking scores; what changes is that a reader gets a value.
                        reasons += RouteReason.CandidateDisagreement
                        reasons += RouteReason.DisagreementResolvedToForwardEvidence
                        selectedModel = OperatingModel.ForwardEarningsPower
                        selectedValue = forward
                        RouteStatus.Disputed
                    } else if (forwardSolid || !fcffSolid) {
                        reasons += RouteReason.SelectedForwardEarningsPower
                        selectedModel = OperatingModel.ForwardEarningsPower
                        selectedValue = forward
                        RouteStatus.Selected
                    } else {
                        // Forward soft, FCFF solid — keep FCFF under distortion only when forward
                        // quality is weaker.
                        reasons += RouteReason.SelectedRepresentativeFcff
                        selectedModel = OperatingModel.FcffWacc
                        selectedValue = fcff
                        RouteStatus.Selected
                    }
                }
                forward != null -> {
                    reasons += RouteReason.FcffCandidateUnavailable
                    reasons += RouteReason.SelectedForwardEarningsPower
                    status = RouteStatus.Selected
                    selectedModel = OperatingModel.ForwardEarningsPower
                    selectedValue = forward
                }
                fcff != null -> {
                    reasons += RouteReason.ForwardCandidateUnavailable
                    reasons += RouteReason.SelectedRepresentativeFcff
                    status = RouteStatus.Selected
                    selectedModel = OperatingModel.FcffWacc
                    selectedValue = fcff
                }
                else -> {
                    reasons += RouteReason.ForwardCandidateUnavailable
                    reasons += RouteReason.FcffCandidateUnavailable
                    status = RouteStatus.Unavailable
                }
            }
        } else if (fcff != null) {
            if (forward != null) reasons += RouteReason.ForwardRequiresStructuralDistortion
            reasons += RouteReason.SelectedRepresentativeFcff
            status = RouteStatus.Selected
            selectedModel = OperatingModel.FcffWacc
            selectedValue = fcff
        } else {
            reasons += RouteReason.FcffCandidateUnavailable
            reasons += if (forward != null) {
                RouteReason.ForwardRequiresStructuralDistortion
            } else {
                RouteReason.ForwardCandidateUnavailable
            }
            status = RouteStatus.Unavailable
        }
    }

    val canonicalReasons = reasons.distinct().sorted()
    val fingerprint = routeFingerprint(
        input.businessClass,
        status,
        selectedModel,
        input.fcffCandidate,
        input.forwardCandidate,
        structural,
        canonicalReasons,
    )
    return OperatingRouteDecision(
        status,
        selectedModel,
        selectedValue,
        difference,
        canonicalReasons,
        structural,
        input.fcffCandidate,
        input.forwardCandidate,
        fingerprint,
    )
}

private fun unavailableForward(
    input: ForwardEarningsInput,
    stableGrowthBps: Int?,
    projectionYears: Int?,
    refusals: List<CandidateRefusal>,
    fingerprint: String,
) = ForwardEarningsCandidate(
    OperatingModel.ForwardEarningsPower,
    CandidateStatus.Unavailable,
    null,
    input.costOfEquity.costOfEquityBps,
    stableGrowthBps,
    projectionYears,
    ModelQuality.Soft,
    EvidenceFamily.AnalystDerivedModel,
    refusals,
    input,
    fingerprint,
)

private fun validateForwardInput(input: ForwardEarningsInput): List<CandidateRefusal> = buildList {
    val low = input.forecast.epsLowCents
    val mean = input.forecast.epsMeanCents
    val high = input.forecast.epsHighCents
    when {
        low == null || mean == null || high == null -> add(CandidateRefusal.MissingForwardEps)
        low <= 0 || mean <= 0 || high <= 0 -> add(CandidateRefusal.NonPositiveForwardEps)
        low > mean || mean > high -> add(CandidateRefusal.InvalidForecastRange)
    }
    when (val count = input.forecast.analystCount) {
        null -> add(CandidateRefusal.MissingCoverage)
        else -> if (count < 0 || input.policy.minAnalystCount < 0 || count < input.policy.minAnalystCount) {
            add(CandidateRefusal.SparseCoverage)
        }
    }
    if (input.forecast.currency.isBlank()) add(CandidateRefusal.MissingCurrency)
    else if (input.forecast.currency != input.policy.expectedCurrency) add(CandidateRefusal.CurrencyMismatch)
    if (input.forecast.sourceFingerprint.isBlank() || input.costOfEquity.sourceFingerprint.isBlank()) {
        add(CandidateRefusal.MissingSourceFingerprint)
    }
    if (
        input.policy.version.isBlank() || input.policy.expectedCurrency.isBlank() || input.policy.maxAgeDays < 0 ||
        input.policy.minForecastHorizonDays <= 0 ||
        input.policy.maxForecastHorizonDays < input.policy.minForecastHorizonDays ||
        input.policy.minAnalystCount <= 0 || input.policy.minimumTerminalSpreadBps <= 0
    ) add(CandidateRefusal.InvalidPolicy)
    val age = try {
        Math.subtractExact(input.asOfEpochDay, input.forecast.observedEpochDay)
    } catch (_: ArithmeticException) {
        null
    }
    if (age == null || age < 0 || age > input.policy.maxAgeDays) add(CandidateRefusal.StaleForecast)
    val forecastHorizon = try { Math.subtractExact(input.forecast.forecastPeriodEndEpochDay, input.asOfEpochDay) } catch (_: ArithmeticException) { null }
    if (forecastHorizon == null || forecastHorizon < input.policy.minForecastHorizonDays || forecastHorizon > input.policy.maxForecastHorizonDays) {
        add(CandidateRefusal.InvalidForecastPeriod)
    }
    val years = projectionYears(input.policy)
    if (input.policy.holdYears < 0 || input.policy.fadeYears <= 0 || input.policy.maxProjectionYears <= 0 ||
        years == null || years > input.policy.maxProjectionYears || years > HARD_MAX_PROJECTION_YEARS) {
        add(CandidateRefusal.InvalidProjectionHorizon)
    }
    if (input.forecast.nearGrowthBps <= -10_000) add(CandidateRefusal.InvalidGrowth)
    val rate = input.costOfEquity.costOfEquityBps
    if (rate <= 0) add(CandidateRefusal.InvalidCostOfEquity)
    when (val stable = input.policy.stableGrowthBps(rate)) {
        null -> add(CandidateRefusal.ArithmeticOverflow)
        else -> when {
            stable <= -10_000 -> add(CandidateRefusal.InvalidGrowth)
            rate.toLong() - stable.toLong() < input.policy.minimumTerminalSpreadBps.toLong() ->
                add(CandidateRefusal.CostOfEquityNotAboveStableGrowth)
        }
    }
}

private fun projectionYears(policy: ProjectionPolicy): Int? = try {
    if (policy.holdYears < 0 || policy.fadeYears < 0) null
    else Math.addExact(Math.addExact(policy.holdYears, policy.fadeYears), 1)
} catch (_: ArithmeticException) {
    null
}

/**
 * Minimum spread the return on capital must keep over perpetual growth.
 *
 * This is a **mathematical guard only** — it keeps `1 - g/ROIC` positive and bounded when the
 * measured return approaches or falls below `g`. It carries no economic claim about the business,
 * and it deliberately sits just above `g` rather than at the cost of capital. Mirrors the FCFF
 * lane's `minimumTerminalSpreadBps`.
 */
const val MIN_TERMINAL_ROIC_SPREAD_BPS: Int = 100

/**
 * Share of terminal earnings that is actually distributable (bps of earnings).
 *
 * A business growing perpetually at `g` must retain `b = g / ROIC` of its earnings to fund the
 * capital that growth consumes; only `1 - b` reaches the owner. Capitalizing the **full** analyst
 * EPS while also granting `g` forever is free-lunch growth, and it is the reason the forward lane
 * priced the cohort at a median 1.5x market with the error rising monotonically as return on
 * capital fell.
 *
 * Two different problems meet here and are kept strictly apart:
 *
 * * **Missing evidence** — [returnOnCapitalBps] is null. The honest prior is that the issuer merely
 *   earns its cost of capital, so growth is value-neutral (`terminal` collapses to `EPS / r`). That
 *   is an economic statement about ignorance.
 * * **Arithmetic safety** — a measured return at or below `g` would make the payout non-positive.
 *   [MIN_TERMINAL_ROIC_SPREAD_BPS] bounds it. That is a statement about the formula, not about the
 *   business.
 *
 * Collapsing the two — flooring *observed* returns at the cost of equity — is what flattened every
 * sub-cost-of-capital issuer onto one payout, erasing exactly the differentiation this function
 * exists to make.
 */
fun terminalPayoutBps(returnOnCapitalBps: Int?, costOfEquityBps: Int, stableGrowthBps: Int): Int {
    if (costOfEquityBps <= 0) return BPS_SCALE_INT
    if (stableGrowthBps <= 0) return BPS_SCALE_INT
    val observed = returnOnCapitalBps ?: costOfEquityBps
    val effective = maxOf(observed, stableGrowthBps + MIN_TERMINAL_ROIC_SPREAD_BPS)
    val retention = stableGrowthBps.toLong() * BPS_SCALE_INT / effective.toLong()
    return (BPS_SCALE_INT - retention).coerceIn(0L, BPS_SCALE_INT.toLong()).toInt()
}

/**
 * Analyst EPS over the explicit horizon is taken as given evidence — the forecast already prices
 * whatever reinvestment the next few years need. Only the **perpetuity** is charged for the capital
 * its growth consumes, via [terminalPayoutBps]; that is where the free-growth error concentrated
 * (~70% of a typical name's value sits in the terminal).
 */
private fun projectForwardValue(
    epsMeanCents: Long,
    nearGrowthBps: Int,
    costOfEquityBps: Int,
    stableGrowthBps: Int,
    holdYears: Int,
    fadeYears: Int,
    terminalPayoutBps: Int,
): Long? {
    val denominator = checkedAdd(BPS_SCALE, BigInteger.valueOf(costOfEquityBps.toLong())) ?: return null
    var discounted = mulDivHalfUp(BigInteger.valueOf(epsMeanCents), BPS_SCALE, denominator) ?: return null
    var presentValue = discounted
    repeat(holdYears) {
        discounted = growDiscounted(discounted, nearGrowthBps, denominator) ?: return null
        presentValue = checkedAdd(presentValue, discounted) ?: return null
    }
    for (fadeStep in 1..fadeYears) {
        val growthDelta = checkedSubtract(
            BigInteger.valueOf(stableGrowthBps.toLong()),
            BigInteger.valueOf(nearGrowthBps.toLong()),
        ) ?: return null
        val fadedNumerator = checkedMultiply(growthDelta, BigInteger.valueOf(fadeStep.toLong())) ?: return null
        val fadedDelta = signedDivHalfUp(fadedNumerator, BigInteger.valueOf(fadeYears.toLong())) ?: return null
        val growth = checkedAdd(BigInteger.valueOf(nearGrowthBps.toLong()), fadedDelta)?.intValueExactOrNull() ?: return null
        discounted = growDiscounted(discounted, growth, denominator) ?: return null
        presentValue = checkedAdd(presentValue, discounted) ?: return null
    }
    val distributable = mulDivHalfUp(discounted, BigInteger.valueOf(terminalPayoutBps.toLong()), BPS_SCALE) ?: return null
    val terminalMultiplier = checkedAdd(BPS_SCALE, BigInteger.valueOf(stableGrowthBps.toLong())) ?: return null
    val terminalDenominator = BigInteger.valueOf(costOfEquityBps.toLong() - stableGrowthBps.toLong())
    val terminal = mulDivHalfUp(distributable, terminalMultiplier, terminalDenominator) ?: return null
    return checkedAdd(presentValue, terminal)?.longValueExactOrNull()
}

private fun growDiscounted(value: BigInteger, growthBps: Int, rateDenominator: BigInteger): BigInteger? {
    val multiplier = checkedAdd(BPS_SCALE, BigInteger.valueOf(growthBps.toLong())) ?: return null
    return mulDivHalfUp(value, multiplier, rateDenominator)
}

private fun mulDivHalfUp(value: BigInteger, multiplier: BigInteger, denominator: BigInteger): BigInteger? {
    if (value.signum() < 0 || multiplier.signum() < 0 || denominator.signum() <= 0) return null
    val product = checkedMultiply(value, multiplier) ?: return null
    val rounded = checkedAdd(product, denominator.divide(BigInteger.TWO)) ?: return null
    return checkedI128(rounded.divide(denominator))
}

private fun signedDivHalfUp(numerator: BigInteger, denominator: BigInteger): BigInteger? {
    if (denominator.signum() <= 0) return null
    return if (numerator.signum() >= 0) {
        checkedAdd(numerator, denominator.divide(BigInteger.TWO))?.divide(denominator)?.let(::checkedI128)
    } else {
        val absolute = if (numerator == I128_MIN) return null else numerator.abs()
        val rounded = checkedAdd(absolute, denominator.divide(BigInteger.TWO)) ?: return null
        checkedI128(rounded.divide(denominator).negate())
    }
}

private fun candidateValue(status: CandidateStatus, value: Long?): Long? =
    value?.takeIf { status == CandidateStatus.Available && it > 0 }

private fun validForwardCandidate(candidate: ForwardEarningsCandidate): Boolean =
    candidate == valueForwardEarnings(candidate.provenance)

private fun validFcffCandidate(candidate: FcffCandidate): Boolean = candidate.fingerprint.isNotBlank() &&
    when (candidate.status) {
        CandidateStatus.Available -> candidate.intrinsicValueCents?.let { it > 0 } == true && candidate.refusalCodes.isEmpty()
        CandidateStatus.Unavailable -> candidate.intrinsicValueCents == null && candidate.refusalCodes.isNotEmpty()
    }

private fun differenceBps(left: Long, right: Long): Long? {
    if (left <= 0 || right <= 0) return null
    val leftBig = BigInteger.valueOf(left)
    val rightBig = BigInteger.valueOf(right)
    val denominator = checkedAdd(leftBig, rightBig) ?: return null
    val numerator = checkedMultiply(checkedSubtract(leftBig, rightBig)?.abs() ?: return null, BigInteger.valueOf(20_000)) ?: return null
    val rounded = checkedAdd(numerator, denominator.divide(BigInteger.TWO)) ?: return null
    return rounded.divide(denominator).longValueExactOrNull()
}

private fun forwardFingerprint(input: ForwardEarningsInput, stableGrowthBps: Int?): String =
    "${OperatingValuation.ENGINE_VERSION}|policy=${fingerprintPart(input.policy.version)}" +
        "|expected_currency=${fingerprintPart(input.policy.expectedCurrency)}|max_age=${input.policy.maxAgeDays}" +
        "|forecast_window=${input.policy.minForecastHorizonDays}/${input.policy.maxForecastHorizonDays}" +
        "|min_coverage=${input.policy.minAnalystCount}|projection=${input.policy.holdYears}/${input.policy.fadeYears}/${input.policy.maxProjectionYears}" +
        "|macro_growth=${input.policy.macroStableGrowthBps}|rf=${input.policy.riskFreeRateBps}|rf_buffer=${input.policy.riskFreeBufferBps}" +
        "|terminal_spread=${input.policy.minimumTerminalSpreadBps}" +
        "|forecast=${fingerprintPart(input.forecast.sourceFingerprint)}" +
        "|rate=${fingerprintPart(input.costOfEquity.sourceFingerprint)}" +
        "|rate_bps=${input.costOfEquity.costOfEquityBps}|beta_source=${input.costOfEquity.betaSource.toSnakeCase()}" +
        "|provisional=${input.costOfEquity.provisional}|market_asof=${input.costOfEquity.marketParamsAsOfEpoch ?: "none"}" +
        "|asof=${input.asOfEpochDay}|observed=${input.forecast.observedEpochDay}|period_end=${input.forecast.forecastPeriodEndEpochDay}" +
        "|currency=${fingerprintPart(input.forecast.currency)}" +
        "|eps=${optionalLong(input.forecast.epsLowCents)}/${optionalLong(input.forecast.epsMeanCents)}/${optionalLong(input.forecast.epsHighCents)}" +
        "|coverage=${input.forecast.analystCount?.toString() ?: "none"}" +
        "|growth=${input.forecast.nearGrowthBps}" +
        "|stable=${stableGrowthBps?.toString() ?: "none"}" +
        "|roic=${input.returnOnCapitalBps?.toString() ?: "none"}" +
        "|terminal_payout=${
            stableGrowthBps?.let {
                terminalPayoutBps(input.returnOnCapitalBps, input.costOfEquity.costOfEquityBps, it)
            }?.toString() ?: "none"
        }"

private fun routeFingerprint(
    businessClass: BusinessClass,
    status: RouteStatus,
    selectedModel: OperatingModel?,
    fcff: FcffCandidate,
    forward: ForwardEarningsCandidate,
    structuralDistortions: List<StructuralDistortion>,
    reasons: List<RouteReason>,
): String {
    val structural = structuralDistortions.joinToString(",") { it.name.lowercase() }
    val reasonTokens = reasons.joinToString(",") { it.name.lowercase() }
    return "${OperatingValuation.ROUTER_POLICY_VERSION}|class=${businessClass.name.lowercase()}" +
        "|status=${status.name.lowercase()}" +
        "|selected=${selectedModel?.name?.lowercase() ?: "none"}" +
        "|fcff=${fcff.status.name.lowercase()}/${fcff.intrinsicValueCents ?: "none"}/${fcff.quality.name.lowercase()}/${fcff.refusalCodes.joinToString(",") { fingerprintPart(it) }}/${fingerprintPart(fcff.fingerprint)}" +
        "|forward=${forward.status.name.lowercase()}/${forward.intrinsicValueCents ?: "none"}/${forward.quality.name.lowercase()}/${forward.evidenceFamily.name.lowercase()}/${forward.refusals.joinToString(",") { it.name.lowercase() }}/${forward.projectionYears ?: "none"}/${fingerprintPart(forward.fingerprint)}" +
        "|structural=$structural|reasons=$reasonTokens"
}

private fun fingerprintPart(value: String) = "${value.toByteArray(Charsets.UTF_8).size}:$value"
private fun optionalLong(value: Long?) = value?.toString() ?: "none"
private fun checkedI128(value: BigInteger): BigInteger? = value.takeIf { it >= I128_MIN && it <= I128_MAX }
private fun checkedAdd(left: BigInteger, right: BigInteger) = checkedI128(left.add(right))
private fun checkedSubtract(left: BigInteger, right: BigInteger) = checkedI128(left.subtract(right))
private fun checkedMultiply(left: BigInteger, right: BigInteger) = checkedI128(left.multiply(right))
private fun BigInteger.longValueExactOrNull(): Long? = try { longValueExact() } catch (_: ArithmeticException) { null }
private fun BigInteger.intValueExactOrNull(): Int? = try { intValueExact() } catch (_: ArithmeticException) { null }

object BusinessClassSnakeCaseSerializer : KSerializer<BusinessClass> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BusinessClass", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BusinessClass) = encoder.encodeString(value.toSnakeCase())
    override fun deserialize(decoder: Decoder): BusinessClass = enumFromSnakeCase(decoder.decodeString())
}

object WaccFieldSourceSnakeCaseSerializer : KSerializer<WaccFieldSource> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("WaccFieldSource", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: WaccFieldSource) = encoder.encodeString(value.toSnakeCase())
    override fun deserialize(decoder: Decoder): WaccFieldSource = enumFromSnakeCase(decoder.decodeString())
}

private inline fun <reified T : Enum<T>> enumFromSnakeCase(token: String): T =
    enumValues<T>().firstOrNull { it.toSnakeCase() == token }
        ?: throw IllegalArgumentException("unknown ${T::class.simpleName} token: $token")

private fun Enum<*>.toSnakeCase(): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
