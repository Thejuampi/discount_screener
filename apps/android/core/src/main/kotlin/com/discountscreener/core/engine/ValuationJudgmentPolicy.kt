package com.discountscreener.core.engine

import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ValuationAnchor
import com.discountscreener.core.model.ValuationAnchorSource
import com.discountscreener.core.model.ValuationAvailability
import com.discountscreener.core.model.ValuationCoverage
import com.discountscreener.core.model.ValuationFreshness
import com.discountscreener.core.model.ValuationModel
import kotlinx.serialization.Serializable

@Serializable
enum class ValuationJudgmentStatus {
    Identity,
    Street,
    Tension,
    Disputed,
    Unavailable,
}

@Serializable
enum class ValuationJudgmentReason {
    Unclassified,
    NotEligible,
    MissingDrivers,
    NoCompleteFamily,
    ShareBasisMismatch,
    FemOnly,
    IncomparableAnchors,
    TensionNoPrimary,
    DisputedGap,
    SoftIdentity,
    StreetPrimary,
    IdentityPrimary,
    IllegalModelPair,
    IncompleteStreet,
    IncompleteIdentity,
    UnusableIdentityFan,
}

data class JudgmentSubject(
    val instrumentId: String,
    val shareBasis: String,
)

sealed class FinishedIdentity {
    data class Computed(val analysis: DcfAnalysis) : FinishedIdentity()
    data class Refused(
        val businessClass: BusinessClass,
        val reason: ValuationJudgmentReason,
    ) : FinishedIdentity()
}

data class IdentityEnvelope(
    val subject: JudgmentSubject,
    val outcome: FinishedIdentity,
    val currencyCode: String = "USD",
    val minorUnitScale: Int = 2,
)

data class StreetBook(
    val subject: JudgmentSubject,
    val source: ValuationAnchorSource,
    val lowCents: Long,
    val baseCents: Long,
    val highCents: Long,
    val currencyCode: String,
    val minorUnitScale: Int,
    val availability: ValuationAvailability = ValuationAvailability.Available,
    val coverage: ValuationCoverage = ValuationCoverage.Sufficient,
    val freshness: ValuationFreshness = ValuationFreshness.Fresh,
)

data class ValuationJudgmentRequest(
    val subject: JudgmentSubject,
    val identity: IdentityEnvelope? = null,
    val street: StreetBook? = null,
    val justifiedMultiple: ForwardEarningsMultiple.Result? = null,
    val femSubject: JudgmentSubject? = null,
    val marketPriceCents: Long? = null,
)

data class ValuationJudgment(
    val status: ValuationJudgmentStatus,
    val relation: AnchorRelation,
    val identity: DcfAnalysis?,
    val justifiedMultiple: ForwardEarningsMultiple.Result?,
    val street: StreetBook?,
    val primaryCents: Long?,
    val reasonCodes: List<ValuationJudgmentReason>,
    val policyVersion: String,
) {
    init {
        when (status) {
            ValuationJudgmentStatus.Identity, ValuationJudgmentStatus.Street ->
                require(primaryCents != null && primaryCents > 0L) {
                    "Identity and Street must name a positive primary"
                }
            ValuationJudgmentStatus.Tension,
            ValuationJudgmentStatus.Disputed,
            ValuationJudgmentStatus.Unavailable,
            ->
                require(primaryCents == null) { "Tension, Disputed, and Unavailable name no primary" }
        }
        if (status == ValuationJudgmentStatus.Unavailable ||
            status == ValuationJudgmentStatus.Tension ||
            status == ValuationJudgmentStatus.Disputed
        ) {
            require(reasonCodes.isNotEmpty()) { "This stance requires a typed reason" }
        }
    }
}

object ValuationJudgmentPolicy {
    const val POLICY_VERSION = "valuation-judgment/3+valuation-decision-policy/1"

    /** Thinkability cut. Distinct from WIDE_SCENARIO_BPS (soft quality), even when the number matches. */
    val IDENTITY_USABLE_MAX_WIDTH_BPS: Int
        get() = ValuationPolicy.current.judgment.identityUsableMaxWidthBps

    /**
     * The analyst range decides. Our model is a reference number.
     *
     * The model is experimental, so it never names the primary while a usable analyst range
     * exists, and it never turns the screen into Tension or Disputed by disagreeing with that
     * range. Both series stay on the card; only one of them is the anchor to act on. The model
     * speaks alone only when no usable analyst range exists, and the card says so.
     */
    fun judge(request: ValuationJudgmentRequest): ValuationJudgment {
        var fem = attachedFem(request)
        var identityAnalysis = analysisOf(request.identity)
        var classRefuse = classRefuseOf(request.identity)
        var identityUsable = identityComplete(request)
        var streetUsable = streetComplete(request)
        var reasons = ArrayList<ValuationJudgmentReason>()
        collectPresenceReasons(request, identityUsable, streetUsable, reasons)

        if (streetUsable) {
            if (classRefuse != null) reasons += classRefuse
            if (identityUsable) {
                var analysis = requireNotNull(identityAnalysis)
                var streetBook = requireNotNull(request.street)
                var envelope = requireNotNull(request.identity)
                if (isSoft(analysis)) reasons += ValuationJudgmentReason.SoftIdentity
                if (envelope.currencyCode != streetBook.currencyCode ||
                    envelope.minorUnitScale != streetBook.minorUnitScale
                ) {
                    reasons += ValuationJudgmentReason.IncomparableAnchors
                }
            }
            reasons += ValuationJudgmentReason.StreetPrimary
            return finish(
                status = ValuationJudgmentStatus.Street,
                relation = AnchorRelation.SingleSource,
                request = request,
                fem = fem,
                identityAnalysis = identityAnalysis,
                primaryCents = requireNotNull(request.street).baseCents,
                reasons = reasons,
            )
        }

        if (classRefuse != null) {
            reasons += classRefuse
            return finish(
                status = ValuationJudgmentStatus.Unavailable,
                relation = AnchorRelation.Unavailable,
                request = request,
                fem = fem,
                identityAnalysis = identityAnalysis,
                primaryCents = null,
                reasons = reasons,
            )
        }

        if (identityUsable) {
            var analysis = requireNotNull(identityAnalysis)
            reasons += ValuationJudgmentReason.IdentityPrimary
            if (isSoft(analysis)) reasons += ValuationJudgmentReason.SoftIdentity
            return finish(
                status = ValuationJudgmentStatus.Identity,
                relation = AnchorRelation.SingleSource,
                request = request,
                fem = fem,
                identityAnalysis = analysis,
                primaryCents = analysis.baseIntrinsicValueCents,
                reasons = reasons,
            )
        }

        if (fem != null) reasons += ValuationJudgmentReason.FemOnly
        if (reasons.none {
                it == ValuationJudgmentReason.FemOnly ||
                    it == ValuationJudgmentReason.NoCompleteFamily
            }
        ) {
            reasons += ValuationJudgmentReason.NoCompleteFamily
        }
        return finish(
            status = ValuationJudgmentStatus.Unavailable,
            relation = AnchorRelation.Unavailable,
            request = request,
            fem = fem,
            identityAnalysis = identityAnalysis,
            primaryCents = null,
            reasons = reasons,
        )
    }

    private fun finish(
        status: ValuationJudgmentStatus,
        relation: AnchorRelation,
        request: ValuationJudgmentRequest,
        fem: ForwardEarningsMultiple.Result?,
        identityAnalysis: DcfAnalysis?,
        primaryCents: Long?,
        reasons: List<ValuationJudgmentReason>,
    ): ValuationJudgment {
        var codes = reasons.distinct()
        if (codes.isEmpty() &&
            (
                status == ValuationJudgmentStatus.Unavailable ||
                    status == ValuationJudgmentStatus.Tension ||
                    status == ValuationJudgmentStatus.Disputed
                )
        ) {
            codes = listOf(ValuationJudgmentReason.NoCompleteFamily)
        }
        return ValuationJudgment(
            status = status,
            relation = relation,
            identity = identityAnalysis,
            justifiedMultiple = fem,
            street = request.street,
            primaryCents = primaryCents,
            reasonCodes = codes,
            policyVersion = POLICY_VERSION,
        )
    }

    private fun attachedFem(request: ValuationJudgmentRequest): ForwardEarningsMultiple.Result? {
        var result = request.justifiedMultiple
        if (result !is ForwardEarningsMultiple.Result.AvailableResult) return null
        var key = request.femSubject
        if (key != null && key != request.subject) return null
        return result
    }

    private fun analysisOf(identity: IdentityEnvelope?): DcfAnalysis? =
        when (val outcome = identity?.outcome) {
            is FinishedIdentity.Computed -> outcome.analysis
            else -> null
        }

    private fun classRefuseOf(identity: IdentityEnvelope?): ValuationJudgmentReason? {
        if (identity == null) return null
        var businessClass = when (val outcome = identity.outcome) {
            is FinishedIdentity.Computed -> outcome.analysis.businessClass
            is FinishedIdentity.Refused -> outcome.businessClass
        }
        return when (businessClass) {
            BusinessClass.Unclassified -> ValuationJudgmentReason.Unclassified
            BusinessClass.NotEligible -> ValuationJudgmentReason.NotEligible
            BusinessClass.OperatingNonFinancial, BusinessClass.FinancialServices -> null
        }
    }

    private fun identityComplete(request: ValuationJudgmentRequest): Boolean {
        var identity = request.identity ?: return false
        if (identity.subject != request.subject) return false
        if (identity.currencyCode.isBlank() || identity.minorUnitScale < 0) return false
        var analysis = when (val outcome = identity.outcome) {
            is FinishedIdentity.Computed -> outcome.analysis
            is FinishedIdentity.Refused -> return false
        }
        if (classRefuseOf(identity) != null) return false
        if (!analysis.valuationUnavailableReason.isNullOrBlank()) return false
        if (!legalPair(analysis)) return false
        return !identityFanUnusable(analysis)
    }

    private fun streetComplete(request: ValuationJudgmentRequest): Boolean {
        var street = request.street ?: return false
        if (street.subject != request.subject) return false
        if (street.source != ValuationAnchorSource.Yahoo &&
            street.source != ValuationAnchorSource.TipRanks
        ) {
            return false
        }
        if (street.lowCents <= 0L ||
            street.baseCents <= 0L ||
            street.highCents <= 0L ||
            street.lowCents > street.baseCents ||
            street.baseCents > street.highCents
        ) {
            return false
        }
        if (street.coverage != ValuationCoverage.Sufficient) return false
        if (street.freshness == ValuationFreshness.Stale ||
            street.freshness == ValuationFreshness.Unknown
        ) {
            return false
        }
        return ValuationDecisionPolicy.isDecisionEligible(streetAnchor(street))
    }

    private fun identityFanUnusable(analysis: DcfAnalysis): Boolean {
        var width = ValuationDecisionPolicy.scenarioWidthBps(
            analysis.bearIntrinsicValueCents,
            analysis.baseIntrinsicValueCents,
            analysis.bullIntrinsicValueCents,
        )
        return width == null || width > IDENTITY_USABLE_MAX_WIDTH_BPS
    }

    private fun legalPair(analysis: DcfAnalysis): Boolean =
        when (analysis.businessClass) {
            BusinessClass.OperatingNonFinancial ->
                analysis.model == ValuationModel.FcffWacc || analysis.model == ValuationModel.ComponentSum
            BusinessClass.FinancialServices -> analysis.model == ValuationModel.ResidualIncomeEquity
            BusinessClass.Unclassified, BusinessClass.NotEligible -> false
        }

    private fun isSoft(analysis: DcfAnalysis): Boolean =
        ValuationDecisionPolicy.isSoftModel(analysis)

    private fun collectPresenceReasons(
        request: ValuationJudgmentRequest,
        identityUsable: Boolean,
        streetUsable: Boolean,
        reasons: MutableList<ValuationJudgmentReason>,
    ) {
        var identity = request.identity
        if (identity != null && identity.subject != request.subject) {
            reasons += ValuationJudgmentReason.ShareBasisMismatch
        }
        var street = request.street
        if (street != null && street.subject != request.subject) {
            reasons += ValuationJudgmentReason.ShareBasisMismatch
        }
        when (val outcome = identity?.outcome) {
            is FinishedIdentity.Refused -> {
                if (outcome.reason == ValuationJudgmentReason.MissingDrivers ||
                    outcome.businessClass == BusinessClass.OperatingNonFinancial ||
                    outcome.businessClass == BusinessClass.FinancialServices
                ) {
                    if (outcome.reason == ValuationJudgmentReason.MissingDrivers) {
                        reasons += ValuationJudgmentReason.MissingDrivers
                    }
                }
            }
            is FinishedIdentity.Computed -> {
                if (!legalPair(outcome.analysis)) {
                    reasons += ValuationJudgmentReason.IllegalModelPair
                } else if (identity?.subject == request.subject && identityFanUnusable(outcome.analysis)) {
                    reasons += ValuationJudgmentReason.UnusableIdentityFan
                }
            }
            null -> Unit
        }
        if (identity != null && !identityUsable && classRefuseOf(identity) == null) {
            reasons += ValuationJudgmentReason.IncompleteIdentity
        }
        if (street != null && !streetUsable) {
            reasons += ValuationJudgmentReason.IncompleteStreet
        }
    }

    private fun streetAnchor(street: StreetBook): ValuationAnchor =
        ValuationAnchor(
            source = street.source,
            valueMinorUnits = street.baseCents,
            currencyCode = street.currencyCode,
            minorUnitScale = street.minorUnitScale,
            availability = street.availability,
            coverage = street.coverage,
            freshness = street.freshness,
        )
}
