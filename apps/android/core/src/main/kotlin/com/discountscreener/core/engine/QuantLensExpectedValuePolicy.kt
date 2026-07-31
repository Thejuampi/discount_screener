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
import com.discountscreener.core.model.ValuationModel
import kotlin.math.roundToInt

/**
 * Chooses the expected-value anchor without hiding model/analyst disagreement.
 *
 * A usable DCF is not automatically the primary expected value. Provisional or
 * wide models are soft evidence, and a material model-vs-analyst gap is
 * explicitly disputed so the UI cannot headline an absurd single upside.
 */
object QuantLensExpectedValuePolicy {
    private const val MODEL_ANALYST_AGREE_BPS = 2_500
    private const val WIDE_SCENARIO_BPS = 12_000

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
        val modelQuality = modelQuality(dcf, modelAnchors)
        val disagreement = if (modelAnchors.size == 3 && analystAnchors.size == 3) {
            relativeDisagreementBps(modelAnchors[1], analystAnchors[1])
        } else {
            null
        }

        if (modelAnchors.size == 3 && analystAnchors.size == 3 &&
            disagreement != null && disagreement > MODEL_ANALYST_AGREE_BPS
        ) {
            return QuantLensExpectedValueRange(
                primaryStatus = QuantLensPrimaryStatus.Disputed,
                band = ExpectedValueRangeBand.Disputed,
                modelLowFairValueCents = modelAnchors[0],
                modelBaseFairValueCents = modelAnchors[1],
                modelHighFairValueCents = modelAnchors[2],
                analystLowFairValueCents = analystAnchors[0],
                analystBaseFairValueCents = analystAnchors[1],
                analystHighFairValueCents = analystAnchors[2],
                disagreementBps = disagreement,
                reasonCodes = listOf(
                    QuantLensReasonCode.CompleteScenarioAnchors,
                    QuantLensReasonCode.ModelAnalystDisagreement,
                ),
            )
        }

        return when {
            modelAnchors.size == 3 && (analystAnchors.size != 3 || modelQuality == ModelQuality.Solid) ->
                scenarioRange(
                    source = ExpectedValueRangeSource.Dcf,
                    anchors = modelAnchors,
                    detail = detail,
                    modelQuality = modelQuality,
                    modelAnchors = modelAnchors.takeIf { analystAnchors.size == 3 },
                    analystAnchors = analystAnchors.takeIf { modelAnchors.size == 3 },
                    disagreement = disagreement,
                )

            analystAnchors.size == 3 ->
                scenarioRange(
                    source = ExpectedValueRangeSource.Analyst,
                    anchors = analystAnchors,
                    detail = detail,
                    modelQuality = modelQuality,
                    modelAnchors = modelAnchors.takeIf { it.size == 3 },
                    analystAnchors = analystAnchors,
                    disagreement = disagreement,
                )

            modelAnchors.size == 3 ->
                scenarioRange(
                    source = ExpectedValueRangeSource.Dcf,
                    anchors = modelAnchors,
                    detail = detail,
                    modelQuality = modelQuality,
                    modelAnchors = modelAnchors,
                    analystAnchors = null,
                    disagreement = disagreement,
                )

            modelAnchors.isNotEmpty() || analystAnchors.isNotEmpty() ->
                QuantLensExpectedValueRange(
                    primaryStatus = QuantLensPrimaryStatus.Sparse,
                    band = ExpectedValueRangeBand.ReferenceOnly,
                    lowFairValueCents = (modelAnchors + analystAnchors).minOrNull(),
                    highFairValueCents = (modelAnchors + analystAnchors).maxOrNull(),
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
        modelQuality: ModelQuality,
        modelAnchors: List<Long>?,
        analystAnchors: List<Long>?,
        disagreement: Int?,
    ): QuantLensExpectedValueRange {
        val low = anchors[0]
        val base = anchors[1]
        val high = anchors[2]
        val weighted = weightedThree(low, base, high)
        return QuantLensExpectedValueRange(
            primaryStatus = if (source == ExpectedValueRangeSource.Dcf && modelQuality == ModelQuality.Soft) {
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

    private fun modelQuality(
        dcf: DcfAnalysis?,
        anchors: List<Long>,
    ): ModelQuality {
        if (dcf == null || dcf.model == ValuationModel.None || anchors.size != 3) {
            return ModelQuality.Unusable
        }
        val base = anchors[1]
        val widthBps = if (base > 0L) {
            (((anchors[2] - anchors[0]).toDouble() / base.toDouble()) * 10_000.0).roundToInt()
        } else {
            Int.MAX_VALUE
        }
        return if (dcf.waccInputs.isProvisional() || dcf.pointEstimateUnreliable || widthBps > WIDE_SCENARIO_BPS) {
            ModelQuality.Soft
        } else {
            ModelQuality.Solid
        }
    }

    private fun weightedThree(low: Long, base: Long, high: Long): Long =
        (low + (base * 2L) + high) / 4L

    private fun relativeDisagreementBps(aCents: Long, bCents: Long): Int? {
        if (aCents <= 0L || bCents <= 0L) return null
        val midpoint = (aCents + bCents).toDouble() / 2.0
        if (!midpoint.isFinite() || midpoint <= 0.0) return null
        return (((kotlin.math.abs(aCents - bCents).toDouble() / midpoint) * 10_000.0)
            .roundToInt())
            .coerceIn(0, 100_000)
    }

    private enum class ModelQuality {
        Solid,
        Soft,
        Unusable,
    }
}
