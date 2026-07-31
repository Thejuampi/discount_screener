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
    const val ENGINE_VERSION = "operating-valuation-router/1"
    const val ROUTER_POLICY_VERSION = "operating-model-router-policy/1"

    fun valueForwardEarnings(input: ForwardEarningsInput): ForwardEarningsCandidate =
        com.discountscreener.core.engine.valueForwardEarnings(input)

    fun routeOperatingModels(input: OperatingRouteInput): OperatingRouteDecision =
        com.discountscreener.core.engine.routeOperatingModels(input)
}

private val BPS_SCALE = BigInteger.valueOf(10_000)
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
)

@Serializable
data class ForwardEarningsInput(
    val asOfEpochDay: Long,
    val forecast: ForwardForecast,
    val costOfEquity: ResolvedCostOfEquity,
    val policy: ProjectionPolicy,
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
    )
    return if (value != null && value > 0) {
        ForwardEarningsCandidate(
            OperatingModel.ForwardEarningsPower,
            CandidateStatus.Available,
            value,
            input.costOfEquity.costOfEquityBps,
            stableGrowthBps,
            projectionYears,
            ModelQuality.Soft,
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
                    status = if (difference != null && difference > DISPUTED_DIFFERENCE_BPS) {
                        reasons += RouteReason.CandidateDisagreement
                        RouteStatus.Disputed
                    } else {
                        reasons += RouteReason.SelectedForwardEarningsPower
                        selectedModel = OperatingModel.ForwardEarningsPower
                        selectedValue = forward
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

private fun projectForwardValue(
    epsMeanCents: Long,
    nearGrowthBps: Int,
    costOfEquityBps: Int,
    stableGrowthBps: Int,
    holdYears: Int,
    fadeYears: Int,
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
    val terminalMultiplier = checkedAdd(BPS_SCALE, BigInteger.valueOf(stableGrowthBps.toLong())) ?: return null
    val terminalDenominator = BigInteger.valueOf(costOfEquityBps.toLong() - stableGrowthBps.toLong())
    val terminal = mulDivHalfUp(discounted, terminalMultiplier, terminalDenominator) ?: return null
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
        "|stable=${stableGrowthBps?.toString() ?: "none"}"

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
