package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.DataProvenance
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.ProviderState
import com.discountscreener.core.model.ResolverState
import com.discountscreener.core.model.ValuationModel

/**
 * SEC-first residual path for FinancialServices.
 * Overlay then existing [DcfAnalysisEngine.compute]. Never FCFF.
 */
object ResidualFromDrivers {
    const val SOURCE_SEC = "sec:companyfacts"
    const val SOURCE_YAHOO = "yahoo:quoteSummary"

    data class Outcome(
        val fundamentals: FundamentalSnapshot,
        val analysis: DcfAnalysis,
    )

    fun compute(
        yahoo: FundamentalSnapshot,
        secFactsJson: String?,
        secFetchAttempted: Boolean,
        marketPriceCents: Long?,
        marketParams: MarketParams,
        instrumentId: String,
        shareBasis: String,
    ): Outcome {
        var businessClass = DcfAnalysisEngine.classifyBusiness(
            yahoo.sectorName,
            yahoo.industryName,
            yahoo.sectorKey,
            yahoo.industryKey,
            symbol = yahoo.symbol,
        )
        var sourcesTried = buildList {
            if (secFetchAttempted) add(SOURCE_SEC)
            add(SOURCE_YAHOO)
        }
        if (businessClass != BusinessClass.FinancialServices) {
            return Outcome(
                yahoo,
                missingDrivers(
                    yahoo = yahoo,
                    businessClass = businessClass,
                    sourcesTried = sourcesTried,
                    instrumentId = instrumentId,
                    shareBasis = shareBasis,
                    extra = "class=${businessClass.name.lowercase()}",
                ),
            )
        }
        var sec = secFactsJson?.let { SecResidualFacts.extract(it) }
        var overlay = SecResidualFacts.overlay(yahoo, sec)
        var computed = DcfAnalysisEngine.compute(
            fundamentals = overlay.fundamentals,
            timeseries = FundamentalTimeseries(),
            marketPriceCents = marketPriceCents,
            marketParams = marketParams,
        ).getOrNull()
        if (computed == null || computed.model != ValuationModel.ResidualIncomeEquity) {
            return Outcome(
                overlay.fundamentals,
                missingDrivers(
                    yahoo = overlay.fundamentals,
                    businessClass = BusinessClass.FinancialServices,
                    sourcesTried = sourcesTried,
                    instrumentId = instrumentId,
                    shareBasis = shareBasis,
                    extra = overlayMiss(overlay),
                ),
            )
        }
        var identitySource = listOf(
            overlay.bookSource,
            overlay.sharesSource,
            overlay.roeSource,
            overlay.retentionSource,
        ).firstOrNull { it == SOURCE_SEC } ?: SOURCE_YAHOO
        var subject = "subject=$instrumentId|$shareBasis"
        var analysis = computed.copy(
            source = if (identitySource == SOURCE_SEC) DcfSource.SecEdgar else DcfSource.YahooFinance,
            resolverState = ResolverState.Selected,
            driverInputFingerprint = residualFingerprint(
                instrumentId = instrumentId,
                shareBasis = shareBasis,
                fundamentals = overlay.fundamentals,
                identitySource = identitySource,
            ),
            driverProvenance = computed.driverProvenance + listOf(
                subject,
                "identity_source=$identitySource",
                ValuationJudgmentPolicy.POLICY_VERSION,
            ) + overlay.sourcesTried.map { source -> "tried=$source" },
            reasonCodes = computed.reasonCodes + listOf(
                subject,
                "identity_source=$identitySource",
                ValuationJudgmentPolicy.POLICY_VERSION,
            ),
        )
        return Outcome(overlay.fundamentals, analysis)
    }

    private fun overlayMiss(overlay: SecResidualFacts.Overlay): String =
        "book=${overlay.bookSource};shares=${overlay.sharesSource};" +
            "roe=${overlay.roeSource};retention=${overlay.retentionSource}"

    private fun residualFingerprint(
        instrumentId: String,
        shareBasis: String,
        fundamentals: FundamentalSnapshot,
        identitySource: String,
    ): String = listOf(
        "residual",
        instrumentId,
        shareBasis,
        ValuationJudgmentPolicy.POLICY_VERSION,
        "book=${fundamentals.bookValuePerShareCents}",
        "roe=${fundamentals.returnOnEquityBps}",
        "retention=${fundamentals.retentionBps}",
        "shares=${fundamentals.sharesOutstanding}",
        "identity=$identitySource",
    ).joinToString("|")

    private fun missingDrivers(
        yahoo: FundamentalSnapshot,
        businessClass: BusinessClass,
        sourcesTried: List<String>,
        instrumentId: String,
        shareBasis: String,
        extra: String,
    ): DcfAnalysis {
        var named = sourcesTried.joinToString(",")
        return DcfAnalysis(
            bearIntrinsicValueCents = 0L,
            baseIntrinsicValueCents = 0L,
            bullIntrinsicValueCents = 0L,
            waccBps = 0,
            baseGrowthBps = 0,
            netDebtDollars = 0L,
            source = DcfSource.SecEdgar,
            resolverState = ResolverState.Unavailable,
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            businessClass = businessClass,
            model = ValuationModel.None,
            discountRateKind = DiscountRateKind.CostOfEquity,
            provenance = DataProvenance(
                source = DcfSource.SecEdgar,
                providerState = ProviderState.Unavailable,
            ),
            reasonCodes = listOf(
                "MissingDrivers",
                "subject=$instrumentId|$shareBasis",
                ValuationJudgmentPolicy.POLICY_VERSION,
            ) + sourcesTried,
            driverProvenance = listOf(
                "subject=$instrumentId|$shareBasis",
                "identity_source=missing",
                ValuationJudgmentPolicy.POLICY_VERSION,
            ),
            valuationUnavailableReason =
                "MissingDrivers after $named ($extra)",
        )
    }
}
