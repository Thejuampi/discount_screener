package com.discountscreener.core.engine

import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.QualificationStatus

/**
 * Why a name is High, Provisional or Low.
 *
 * The band is Qualification first, then External. A name that is not Qualified never consults
 * the analyst signal. High needs a supportive signal and at least [HIGH_CONFIDENCE_ANALYST_COUNT]
 * opinions.
 */
const val HIGH_CONFIDENCE_ANALYST_COUNT = 3

/** What External is: Street median target versus price. */
const val EXTERNAL_STATUS_HELP =
    "Street median target versus price.\n\n" +
        "Supportive — median is ≥ 20% above price.\n" +
        "Divergent — median does not show that gap, or the ticker does not match.\n" +
        "Missing — no median target.\n" +
        "Stale — the signal is too old.\n\n" +
        "High also needs at least $HIGH_CONFIDENCE_ANALYST_COUNT opinions."

data class ConfidenceCause(
    val name: String,
    val value: String,
)

data class ConfidenceExplanation(
    val band: ConfidenceBand,
    val causes: List<ConfidenceCause>,
)

fun confidenceFor(
    qualification: QualificationStatus,
    externalStatus: ExternalSignalStatus,
    analystCount: Int?,
): ConfidenceBand {
    if (qualification != QualificationStatus.Qualified) {
        return ConfidenceBand.Low
    }
    return when (externalStatus) {
        ExternalSignalStatus.Missing -> ConfidenceBand.Provisional
        ExternalSignalStatus.Supportive -> if (hasHighConfidenceAnalystCoverage(analystCount)) {
            ConfidenceBand.High
        } else {
            ConfidenceBand.Provisional
        }
        ExternalSignalStatus.Stale, ExternalSignalStatus.Divergent -> ConfidenceBand.Low
    }
}

fun hasHighConfidenceAnalystCoverage(analystCount: Int?): Boolean =
    analystCount != null && analystCount >= HIGH_CONFIDENCE_ANALYST_COUNT

fun explainConfidence(
    qualification: QualificationStatus,
    externalStatus: ExternalSignalStatus,
    analystCount: Int?,
): ConfidenceExplanation {
    var band = confidenceFor(qualification, externalStatus, analystCount)
    var qualificationCause = ConfidenceCause("Qualification", qualificationLabel(qualification))
    if (qualification != QualificationStatus.Qualified) {
        return ConfidenceExplanation(band = band, causes = listOf(qualificationCause))
    }
    return ConfidenceExplanation(
        band = band,
        causes = listOf(qualificationCause, ConfidenceCause("External", externalLabel(externalStatus, analystCount))),
    )
}

private fun qualificationLabel(qualification: QualificationStatus): String = when (qualification) {
    QualificationStatus.Qualified -> "Qualified"
    QualificationStatus.Unprofitable -> "Unprofitable"
    QualificationStatus.GapTooSmall -> "Gap too small"
}

private fun externalLabel(status: ExternalSignalStatus, analystCount: Int?): String = when (status) {
    ExternalSignalStatus.Missing -> "Missing"
    ExternalSignalStatus.Stale -> "Stale"
    ExternalSignalStatus.Divergent -> "Divergent"
    ExternalSignalStatus.Supportive -> when {
        analystCount == null -> "Supportive"
        analystCount >= HIGH_CONFIDENCE_ANALYST_COUNT ->
            "Supportive · $analystCount ≥ $HIGH_CONFIDENCE_ANALYST_COUNT"
        else -> "Supportive · $analystCount < $HIGH_CONFIDENCE_ANALYST_COUNT"
    }
}
