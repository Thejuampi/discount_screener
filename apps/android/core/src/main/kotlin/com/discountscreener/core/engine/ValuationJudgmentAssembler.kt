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
import com.discountscreener.core.model.ValuationHonesty
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
        dcfAnalysis: DcfAnalysis? = null,
        includeStreetImplied: Boolean = true,
    ): ProjectedValuationJudgment {
        var analysis = judgment.identity
        var refuseSource = dcfAnalysis ?: analysis
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
        var implied = if (
            includeStreetImplied &&
            analysis != null &&
            street != null &&
            street.baseCents > 0L
        ) {
            StreetImpliedHonesty.reconcile(
                analysis = analysis,
                streetBaseCents = street.baseCents,
                shares = sharesOutstanding?.toDouble(),
            )
        } else {
            null
        }
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
            honestyMode = analysis?.honesty ?: ValuationHonesty.Honest,
            streetImplied = implied,
            identityUnavailableReason = refuseSource?.valuationUnavailableReason,
            providerRefuseLines = refuseSource?.providerReasons.orEmpty().mapNotNull { reason ->
                reason.upstreamStatus?.takeIf { it.isNotBlank() }?.let { status ->
                    "${reason.provider.name}: $status"
                }
            },
            identityCaveatLines = identityCaveatLines(analysis),
        )
    }

    internal fun identityCaveatLines(analysis: DcfAnalysis?): List<String> {
        if (analysis == null || analysis.baseIntrinsicValueCents <= 0L) return emptyList()
        return buildList {
            analysis.reasonCodes.mapNotNull { code ->
                when {
                    code.startsWith("interest=estimated:") -> estimatedInterestCaveat(code)
                    code.startsWith("interest=unfiled_with_period_debt:") -> {
                        var years = yearList(code.removePrefix("interest=unfiled_with_period_debt:"))
                        if (years.isBlank()) null
                        else "Interest expense is missing for $years. Confidence is too thin to estimate."
                    }
                    else -> null
                }
            }.forEach(::add)
            if (analysis.reasonCodes.any { it == "debt_stock=filed_year_end_instant" }) {
                add("Debt stock is the filed year-end instant.")
            }
            if (analysis.model == ValuationModel.ComponentSum) {
                add("Value is factory cash plus the lender book.")
            }
            costOfDebtCaveat(analysis.reasonCodes)?.let(::add)
        }
    }

    private fun costOfDebtCaveat(codes: List<String>): String? {
        var source = codes.firstOrNull { it.startsWith("cost_of_debt_source=") } ?: return null
        var token = source.removePrefix("cost_of_debt_source=")
        var coverage = codes.any { it.startsWith("coverage_synthetic=") }
        var current = codes.any { it == "market_yield=current_instrument" }
        var bps = codes.firstOrNull { it.startsWith("cost_of_debt_bps=") }
            ?.removePrefix("cost_of_debt_bps=")
            ?.toIntOrNull()
        var named = when {
            coverage -> "Cost of debt is a coverage synthetic from filed interest"
            token == "market_yield" && current -> "Cost of debt is the current instrument yield"
            token == "market_yield" -> "Cost of debt is the market yield"
            token == "rated_or_synthetic_spread" -> "Cost of debt is a rated or synthetic spread"
            token == "interest_over_average_debt" ||
                token == "yahoo_aligned_interest_over_debt" ->
                "Cost of debt is the filed coupon over average debt"
            else -> return null
        }
        return if (bps != null) "$named, $bps bps." else "$named."
    }

    private fun estimatedInterestCaveat(code: String): String? {
        var parts = code.removePrefix("interest=estimated:").split(":")
        if (parts.size < 3) return null
        var method = parts[0]
        var band = parts[1].replaceFirstChar { it.uppercase() }
        var years = yearList(parts.drop(2).joinToString(":"))
        if (years.isBlank()) return null
        var source = when (method) {
            "own_effective_rate" -> "this issuer's last filed coupon and debt"
            "peer_effective_rate" -> "similar issuers' filed coupon and debt"
            else -> "available coupon evidence"
        }
        return "Interest for $years is an estimate from $source. Confidence is $band. A later filed tag replaces the estimate."
    }

    private fun yearList(raw: String): String =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

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
        ValuationModel.ComponentSum -> "Factory plus lender"
        ValuationModel.None, null -> null
    }
}
