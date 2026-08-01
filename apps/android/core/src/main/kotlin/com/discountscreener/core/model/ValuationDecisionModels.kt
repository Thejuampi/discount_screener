package com.discountscreener.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ValuationAnchorSource { Model, Yahoo, TipRanks }

@Serializable
enum class ValuationAvailability { Available, ReferenceOnly, Unavailable }

@Serializable
enum class ValuationCoverage { Sufficient, Sparse, Unknown }

@Serializable
enum class ValuationFreshness { Fresh, Aging, Stale, Unknown }

@Serializable
enum class ValuationConfidence { Solid, Soft, Provisional, Unknown }

@Serializable
enum class AnchorRelation { Unavailable, SingleSource, Aligned, Tension, Disputed }

@Serializable
enum class ValuationDecisionReason { InvalidAnchor, IncomparableCurrency, IncomparableScale }

@Serializable
data class ValuationAnchor(
    val source: ValuationAnchorSource,
    val valueMinorUnits: Long? = null,
    val currencyCode: String? = null,
    val minorUnitScale: Int? = null,
    val availability: ValuationAvailability = ValuationAvailability.Unavailable,
    val coverage: ValuationCoverage = ValuationCoverage.Unknown,
    val freshness: ValuationFreshness = ValuationFreshness.Unknown,
    val confidence: ValuationConfidence = ValuationConfidence.Unknown,
    val reasonCodes: List<ValuationDecisionReason> = emptyList(),
) {
    init {
        require(minorUnitScale == null || minorUnitScale >= 0) { "minorUnitScale cannot be negative." }
    }
}

@Serializable
data class AnchorComparison(
    val left: ValuationAnchorSource,
    val right: ValuationAnchorSource,
    val differenceBps: Int? = null,
    val reasonCodes: List<ValuationDecisionReason> = emptyList(),
)

@Serializable
data class ValuationDecision(
    val relation: AnchorRelation,
    val primaryAnchor: ValuationAnchorSource? = null,
    val comparisons: List<AnchorComparison> = emptyList(),
)

