package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.ExpectedValueRangeBand
import com.discountscreener.core.model.ExpectedValueRangeSource
import com.discountscreener.core.model.QuantLensExpectedValueRange
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReasonCode
import com.discountscreener.core.model.ResolverState
import com.discountscreener.core.model.SymbolDetail

/**
 * Uses analyst anchors while DCF model validation remains pending.
 * DCF anchors can fill a missing analyst range, but remain provisional.
 */
object QuantLensExpectedValuePolicy {

    fun select(
        detail: SymbolDetail,
        dcf: DcfAnalysis?,
    ): QuantLensExpectedValueRange {
        val modelAnchors = dcf
            ?.takeIf {
                it.source != null &&
                    it.source != DcfSource.Unknown &&
                    it.resolverState != ResolverState.RestoredOnly
            }
            ?.let(::normalizedAnchors)
            .orEmpty()
        val analystAnchors = analystAnchors(detail)
        return when {
            analystAnchors.size == 3 ->
                scenarioRange(
                    source = ExpectedValueRangeSource.Analyst,
                    anchors = analystAnchors,
                    detail = detail,
                    modelAnchors = null,
                    analystAnchors = analystAnchors,
                    disagreement = null,
                )

            modelAnchors.size == 3 ->
                scenarioRange(
                    source = ExpectedValueRangeSource.Dcf,
                    anchors = modelAnchors,
                    detail = detail,
                    modelAnchors = modelAnchors,
                    analystAnchors = null,
                    disagreement = null,
                )

            modelAnchors.isNotEmpty() || analystAnchors.isNotEmpty() ->
                QuantLensExpectedValueRange(
                    primaryStatus = QuantLensPrimaryStatus.Sparse,
                    band = ExpectedValueRangeBand.ReferenceOnly,
                    lowFairValueCents = (analystAnchors.ifEmpty { modelAnchors }).minOrNull(),
                    highFairValueCents = (analystAnchors.ifEmpty { modelAnchors }).maxOrNull(),
                    reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
                )

            else ->
                QuantLensExpectedValueRange(
                    primaryStatus = QuantLensPrimaryStatus.Sparse,
                    band = ExpectedValueRangeBand.Sparse,
                    reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
                )
        }
    }

    private fun scenarioRange(
        source: ExpectedValueRangeSource,
        anchors: List<Long>,
        detail: SymbolDetail,
        modelAnchors: List<Long>?,
        analystAnchors: List<Long>?,
        disagreement: Int?,
    ): QuantLensExpectedValueRange {
        val low = anchors[0]
        val base = anchors[1]
        val high = anchors[2]
        val weighted = weightedThree(low, base, high)
        return QuantLensExpectedValueRange(
            primaryStatus = if (source == ExpectedValueRangeSource.Dcf) {
                QuantLensPrimaryStatus.Provisional
            } else {
                QuantLensPrimaryStatus.Available
            },
            band = ExpectedValueRangeBand.ScenarioWeighted,
            source = source,
            weightedFairValueCents = weighted,
            weightedUpsideBps = checkedUpsideBps(detail.marketPriceCents, weighted)
                ?.coerceIn(-100_000, 100_000),
            lowFairValueCents = low,
            highFairValueCents = high,
            spreadBps = checkedUpsideBps(low, high)?.coerceIn(0, 100_000),
            modelLowFairValueCents = modelAnchors?.getOrNull(0),
            modelBaseFairValueCents = modelAnchors?.getOrNull(1),
            modelHighFairValueCents = modelAnchors?.getOrNull(2),
            analystLowFairValueCents = analystAnchors?.getOrNull(0),
            analystBaseFairValueCents = analystAnchors?.getOrNull(1),
            analystHighFairValueCents = analystAnchors?.getOrNull(2),
            disagreementBps = disagreement,
            reasonCodes = listOf(QuantLensReasonCode.CompleteScenarioAnchors),
        )
    }

    private fun analystAnchors(detail: SymbolDetail): List<Long> {
        val base = detail.weightedExternalSignalFairValueCents
            ?: detail.externalSignalFairValueCents
            ?: return emptyList()
        val anchors = listOfNotNull(
            detail.externalSignalLowFairValueCents,
            base,
            detail.externalSignalHighFairValueCents,
        ).filter { it > 0L }
        return if (anchors.size == 3) anchors.sorted() else anchors
    }

    private fun normalizedAnchors(dcf: DcfAnalysis): List<Long> {
        val anchors = listOf(
            dcf.bearIntrinsicValueCents,
            dcf.baseIntrinsicValueCents,
            dcf.bullIntrinsicValueCents,
        ).filter { it > 0L }
        return if (anchors.size == 3) anchors.sorted() else anchors
    }

    private fun weightedThree(low: Long, base: Long, high: Long): Long =
        (low + (base * 2L) + high) / 4L

}
