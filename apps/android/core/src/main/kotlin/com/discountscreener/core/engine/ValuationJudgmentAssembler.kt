package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.ProjectedValuationJudgment
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ValuationAnchorSource
import com.discountscreener.core.model.ValuationAvailability
import com.discountscreener.core.model.ValuationCoverage
import com.discountscreener.core.model.ValuationFreshness
import com.discountscreener.core.model.ValuationModel

object ValuationJudgmentAssembler {
    const val SHARE_BASIS = "unspecified"

    fun assemble(detail: SymbolDetail, dcfAnalysis: DcfAnalysis?): ValuationJudgment {
        var subject = JudgmentSubject(detail.symbol, SHARE_BASIS)
        var request = ValuationJudgmentRequest(
            subject = subject,
            identity = identityEnvelope(detail, dcfAnalysis, subject),
            street = streetBook(detail, subject),
            marketPriceCents = detail.marketPriceCents,
        )
        return ValuationJudgmentPolicy.judge(request)
    }

    fun snapshot(
        judgment: ValuationJudgment,
        lastPriceCents: Long? = null,
        sharesOutstanding: Long? = null,
    ): ProjectedValuationJudgment {
        var analysis = judgment.identity
        var street = judgment.street
        var femCents = when (val fem = judgment.justifiedMultiple) {
            is ForwardEarningsMultiple.Result.AvailableResult -> fem.value.targetValueCents
            else -> null
        }
        var price = PriceSpeechPolicy.speak(
            lastPriceCents = lastPriceCents,
            streetTwelveMonthCents = street?.baseCents,
            analysis = analysis,
            sharesOutstanding = sharesOutstanding,
        )
        return ProjectedValuationJudgment(
            status = judgment.status,
            relation = judgment.relation,
            primaryCents = judgment.primaryCents,
            reasonCodes = judgment.reasonCodes,
            policyVersion = judgment.policyVersion,
            identityBearCents = analysis?.bearIntrinsicValueCents,
            identityBaseCents = analysis?.baseIntrinsicValueCents,
            identityBullCents = analysis?.bullIntrinsicValueCents,
            identityModelLabel = identityModelLabel(analysis),
            streetLowCents = street?.lowCents,
            streetBaseCents = street?.baseCents,
            streetHighCents = street?.highCents,
            femTargetCents = femCents,
            lastPriceCents = price.lastPriceCents,
            horizonPriceCents = price.expectedHorizonPriceCents,
            horizonDays = price.horizonDays,
            cashIdentityCents = price.modelPriceTodayCents,
            upsideToHorizonBps = price.upsideToHorizonBps,
            priceSpeechReasons = price.reasonCodes,
            priceSpeechPolicyVersion = price.policyVersion,
        )
    }

    private fun identityEnvelope(
        detail: SymbolDetail,
        dcfAnalysis: DcfAnalysis?,
        subject: JudgmentSubject,
    ): IdentityEnvelope {
        if (dcfAnalysis != null && isComputedIdentity(dcfAnalysis)) {
            return IdentityEnvelope(subject, FinishedIdentity.Computed(dcfAnalysis))
        }
        var fund = detail.fundamentals
        var businessClass = DcfAnalysisEngine.classifyBusiness(
            fund?.sectorName,
            fund?.industryName,
            fund?.sectorKey,
            fund?.industryKey,
            symbol = fund?.symbol ?: detail.symbol,
        )
        var reason = when (businessClass) {
            BusinessClass.Unclassified -> ValuationJudgmentReason.Unclassified
            BusinessClass.NotEligible -> ValuationJudgmentReason.NotEligible
            BusinessClass.OperatingNonFinancial, BusinessClass.FinancialServices ->
                ValuationJudgmentReason.MissingDrivers
        }
        if (dcfAnalysis != null &&
            (businessClass == BusinessClass.OperatingNonFinancial ||
                businessClass == BusinessClass.FinancialServices)
        ) {
            reason = ValuationJudgmentReason.MissingDrivers
        }
        return IdentityEnvelope(subject, FinishedIdentity.Refused(businessClass, reason))
    }

    private fun isComputedIdentity(analysis: DcfAnalysis): Boolean {
        if (analysis.model == ValuationModel.None) return false
        if (!analysis.valuationUnavailableReason.isNullOrBlank()) return false
        return analysis.baseIntrinsicValueCents > 0L
    }

    private fun streetBook(detail: SymbolDetail, subject: JudgmentSubject): StreetBook? {
        var low = detail.externalSignalLowFairValueCents
        var base = detail.weightedExternalSignalFairValueCents ?: detail.externalSignalFairValueCents
        var high = detail.externalSignalHighFairValueCents
        if (low == null && base == null && high == null) return null
        return StreetBook(
            subject = subject,
            source = ValuationAnchorSource.Yahoo,
            lowCents = low ?: 0L,
            baseCents = base ?: 0L,
            highCents = high ?: 0L,
            currencyCode = "USD",
            minorUnitScale = 2,
            availability = streetAvailability(detail),
            coverage = streetCoverage(detail),
            freshness = streetFreshness(detail),
        )
    }

    private fun streetAvailability(detail: SymbolDetail): ValuationAvailability =
        when (detail.externalStatus) {
            ExternalSignalStatus.Missing -> ValuationAvailability.Unavailable
            ExternalSignalStatus.Stale,
            ExternalSignalStatus.Supportive,
            ExternalSignalStatus.Divergent,
            -> ValuationAvailability.Available
        }

    private fun streetCoverage(detail: SymbolDetail): ValuationCoverage {
        var count = detail.weightedAnalystCount ?: detail.analystOpinionCount ?: 0
        return if (count > 0) ValuationCoverage.Sufficient else ValuationCoverage.Unknown
    }

    private fun streetFreshness(detail: SymbolDetail): ValuationFreshness {
        if (detail.externalStatus == ExternalSignalStatus.Stale) return ValuationFreshness.Stale
        var age = detail.externalSignalAgeSeconds ?: return ValuationFreshness.Unknown
        var maxAge = detail.externalSignalMaxAgeSeconds
        if (maxAge > 0L && age > maxAge) return ValuationFreshness.Stale
        return ValuationFreshness.Fresh
    }

    private fun identityModelLabel(analysis: DcfAnalysis?): String? = when (analysis?.model) {
        ValuationModel.FcffWacc -> "FCFF DCF"
        ValuationModel.ResidualIncomeEquity -> "Residual income"
        ValuationModel.None, null -> null
    }
}
