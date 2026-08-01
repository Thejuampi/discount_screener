package com.discountscreener.core.engine

import com.discountscreener.core.model.AnchorComparison
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.ValuationAnchor
import com.discountscreener.core.model.ValuationAnchorSource
import com.discountscreener.core.model.ValuationAvailability
import com.discountscreener.core.model.ValuationDecision
import com.discountscreener.core.model.ValuationDecisionReason
import com.discountscreener.core.model.ValuationConfidence
import java.math.BigInteger

/** Shared, presentation-independent valuation-anchor relation policy. */
object ValuationDecisionPolicy {
    const val ALIGNED_MAX_BPS = 2_500
    const val TENSION_MAX_BPS = 5_000
    const val WIDE_SCENARIO_BPS = 12_000

    /**
     * round(abs(a-b) / ((a+b)/2) * 10_000) using integer half-up rounding.
     * Null means invalid/non-positive input; callers add the appropriate reason.
     */
    fun differenceBps(left: Long, right: Long): Int? {
        if (left <= 0L || right <= 0L) return null
        val a = BigInteger.valueOf(left)
        val b = BigInteger.valueOf(right)
        val denominator = a + b
        val numerator = (a - b).abs() * BigInteger.valueOf(20_000L)
        return numerator.add(denominator / BigInteger.TWO)
            .divide(denominator)
            .intValueExact()
    }

    /** round((bull-bear)/base * 10_000), where ordered permits equality. */
    fun scenarioWidthBps(bear: Long, base: Long, bull: Long): Int? {
        if (bear <= 0L || base <= 0L || bull <= 0L || bear > base || base > bull) return null
        val numerator = (BigInteger.valueOf(bull) - BigInteger.valueOf(bear)) * BigInteger.valueOf(10_000L)
        val denominator = BigInteger.valueOf(base)
        return numerator.add(denominator / BigInteger.TWO)
            .divide(denominator)
            .intValueExact()
    }

    fun decide(anchors: List<ValuationAnchor>): ValuationDecision {
        val eligible = anchors.filter(::isDecisionEligible)
        if (eligible.isEmpty()) return ValuationDecision(AnchorRelation.Unavailable)
        if (eligible.size == 1) return ValuationDecision(AnchorRelation.SingleSource, eligible.single().source)

        val comparisons = buildList {
            for (leftIndex in 0 until eligible.lastIndex) {
                for (rightIndex in (leftIndex + 1)..eligible.lastIndex) {
                    add(compare(eligible[leftIndex], eligible[rightIndex]))
                }
            }
        }
        val comparable = comparisons.mapNotNull { it.differenceBps }
        if (comparable.isEmpty()) return ValuationDecision(AnchorRelation.Unavailable, comparisons = comparisons)
        val relation = when {
            comparable.any { it > TENSION_MAX_BPS } -> AnchorRelation.Disputed
            comparable.any { it > ALIGNED_MAX_BPS } -> AnchorRelation.Tension
            else -> AnchorRelation.Aligned
        }
        return ValuationDecision(relation, if (relation == AnchorRelation.Aligned) primary(eligible) else null, comparisons)
    }

    fun isDecisionEligible(anchor: ValuationAnchor): Boolean =
        anchor.availability == ValuationAvailability.Available &&
            anchor.valueMinorUnits != null && anchor.valueMinorUnits > 0L &&
            !anchor.currencyCode.isNullOrBlank() && anchor.minorUnitScale != null

    private fun compare(left: ValuationAnchor, right: ValuationAnchor): AnchorComparison {
        val reasons = mutableListOf<ValuationDecisionReason>()
        if (left.currencyCode != right.currencyCode) reasons += ValuationDecisionReason.IncomparableCurrency
        if (left.minorUnitScale != right.minorUnitScale) reasons += ValuationDecisionReason.IncomparableScale
        val difference = if (reasons.isEmpty()) differenceBps(requireNotNull(left.valueMinorUnits), requireNotNull(right.valueMinorUnits)) else null
        if (difference == null && reasons.isEmpty()) reasons += ValuationDecisionReason.InvalidAnchor
        return AnchorComparison(left.source, right.source, difference, reasons)
    }

    private fun primary(anchors: List<ValuationAnchor>): ValuationAnchorSource? =
        anchors.firstOrNull { it.source == ValuationAnchorSource.Model && it.confidence == ValuationConfidence.Solid }?.source
            ?: anchors.firstOrNull { it.source == ValuationAnchorSource.Yahoo }?.source
            ?: anchors.firstOrNull { it.source == ValuationAnchorSource.TipRanks }?.source
}

