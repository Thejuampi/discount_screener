package com.discountscreener.core.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Exact semantic peer of Windows `source_continuity.rs`. */
const val CONTINUITY_POLICY_VERSION = "source-continuity/1"
const val DEFAULT_SCALE_RATIO_THRESHOLD = 5L
const val DEFAULT_MATERIALITY_FLOOR_DOLLARS = 10_000_000L
const val DEFAULT_MIN_CONFIDENT_SERIES_LENGTH = 4

@Serializable
enum class ContinuityStatus {
    @SerialName("continuous") Continuous,
    @SerialName("discontinuous") Discontinuous,
    @SerialName("insufficient_evidence") InsufficientEvidence,
}

@Serializable
enum class ContinuityReason {
    @SerialName("sec_series_absent") SecSeriesAbsent,
    @SerialName("sec_series_short") SecSeriesShort,
    @SerialName("sec_fiscal_lag_supporting") SecFiscalLagSupporting,
    @SerialName("scale_mismatch_ocf") ScaleMismatchOcf,
    @SerialName("scale_mismatch_fcf") ScaleMismatchFcf,
    @SerialName("scale_sign_conflict") ScaleSignConflict,
    @SerialName("entity_cik_mismatch") EntityCikMismatch,
    @SerialName("yahoo_cash_missing") YahooCashMissing,
    @SerialName("aligned_scale") AlignedScale,
    @SerialName("sec_series_present") SecSeriesPresent,
}

@Serializable
data class SourceContinuityPolicy(
    val version: String = CONTINUITY_POLICY_VERSION,
    val scaleRatioThreshold: Long = DEFAULT_SCALE_RATIO_THRESHOLD,
    val materialityFloorDollars: Long = DEFAULT_MATERIALITY_FLOOR_DOLLARS,
    val minConfidentSeriesLength: Int = DEFAULT_MIN_CONFIDENT_SERIES_LENGTH,
)

@Serializable
data class SourceContinuityEvidence(
    val latestSecFiscalYear: Int? = null,
    val secSeriesLength: Int = 0,
    val lastSecOcfDollars: Long? = null,
    val lastSecFcfDollars: Long? = null,
    val yahooOcfDollars: Long? = null,
    val yahooFcfDollars: Long? = null,
    val secCik: Long? = null,
    val yahooCik: Long? = null,
    val asOfEpochDay: Long,
)

@Serializable
data class SourceContinuityDecision(
    val status: ContinuityStatus,
    val reasons: List<ContinuityReason>,
    val policyVersion: String,
    val fingerprint: String,
)

object SourceContinuity {
    fun evaluate(
        evidence: SourceContinuityEvidence,
        policy: SourceContinuityPolicy = SourceContinuityPolicy(),
    ): SourceContinuityDecision = evaluateSourceContinuity(evidence, policy)

    fun emitsSourceDiscontinuity(decision: SourceContinuityDecision): Boolean =
        decision.status == ContinuityStatus.Discontinuous
}

fun evaluateSourceContinuity(
    evidence: SourceContinuityEvidence,
    policy: SourceContinuityPolicy = SourceContinuityPolicy(),
): SourceContinuityDecision {
    val reasons = mutableListOf<ContinuityReason>()
    val asOfYear = epochDayYear(evidence.asOfEpochDay)

    if (evidence.secSeriesLength == 0 || evidence.latestSecFiscalYear == null) {
        reasons += ContinuityReason.SecSeriesAbsent
        return finish(ContinuityStatus.InsufficientEvidence, reasons.sortedDistinct(), policy, evidence)
    }
    reasons += ContinuityReason.SecSeriesPresent

    if (evidence.secSeriesLength < policy.minConfidentSeriesLength) {
        reasons += ContinuityReason.SecSeriesShort
    }

    val latest = evidence.latestSecFiscalYear
    if (latest < asOfYear - 1) {
        reasons += ContinuityReason.SecFiscalLagSupporting
    }

    val secCik = evidence.secCik
    val yahooCik = evidence.yahooCik
    if (secCik != null && yahooCik != null && secCik != yahooCik) {
        reasons += ContinuityReason.EntityCikMismatch
        return finish(ContinuityStatus.Discontinuous, reasons.sortedDistinct(), policy, evidence)
    }

    if (evidence.yahooOcfDollars == null && evidence.yahooFcfDollars == null) {
        reasons += ContinuityReason.YahooCashMissing
        return finish(ContinuityStatus.InsufficientEvidence, reasons.sortedDistinct(), policy, evidence)
    }

    var scaleHit = false
    val secOcf = evidence.lastSecOcfDollars
    val yahooOcf = evidence.yahooOcfDollars
    if (secOcf != null && yahooOcf != null) {
        when (compareCashScale(secOcf, yahooOcf, policy)) {
            ScaleCompare.Aligned -> Unit
            ScaleCompare.RatioMismatch -> {
                reasons += ContinuityReason.ScaleMismatchOcf
                scaleHit = true
            }
            ScaleCompare.SignConflict -> {
                reasons += ContinuityReason.ScaleSignConflict
                reasons += ContinuityReason.ScaleMismatchOcf
                scaleHit = true
            }
        }
    }
    val secFcf = evidence.lastSecFcfDollars
    val yahooFcf = evidence.yahooFcfDollars
    if (secFcf != null && yahooFcf != null) {
        when (compareCashScale(secFcf, yahooFcf, policy)) {
            ScaleCompare.Aligned -> Unit
            ScaleCompare.RatioMismatch -> {
                reasons += ContinuityReason.ScaleMismatchFcf
                scaleHit = true
            }
            ScaleCompare.SignConflict -> {
                reasons += ContinuityReason.ScaleSignConflict
                reasons += ContinuityReason.ScaleMismatchFcf
                scaleHit = true
            }
        }
    }

    val comparable =
        (evidence.lastSecOcfDollars != null && evidence.yahooOcfDollars != null) ||
            (evidence.lastSecFcfDollars != null && evidence.yahooFcfDollars != null)
    if (!comparable) {
        reasons += ContinuityReason.YahooCashMissing
        return finish(ContinuityStatus.InsufficientEvidence, reasons.sortedDistinct(), policy, evidence)
    }

    if (scaleHit) {
        return finish(ContinuityStatus.Discontinuous, reasons.sortedDistinct(), policy, evidence)
    }

    reasons += ContinuityReason.AlignedScale
    return finish(ContinuityStatus.Continuous, reasons.sortedDistinct(), policy, evidence)
}

fun emitsSourceDiscontinuity(decision: SourceContinuityDecision): Boolean =
    decision.status == ContinuityStatus.Discontinuous

private fun finish(
    status: ContinuityStatus,
    reasons: List<ContinuityReason>,
    policy: SourceContinuityPolicy,
    evidence: SourceContinuityEvidence,
): SourceContinuityDecision =
    SourceContinuityDecision(
        status = status,
        reasons = reasons,
        policyVersion = policy.version,
        fingerprint = continuityFingerprint(status, reasons, policy, evidence),
    )

fun continuityFingerprint(
    status: ContinuityStatus,
    reasons: List<ContinuityReason>,
    policy: SourceContinuityPolicy,
    evidence: SourceContinuityEvidence,
): String {
    val reasonTokens = reasons.joinToString(",") { it.token() }
    return "source-continuity/1|policy=${policy.version}|status=${status.token()}|reasons=$reasonTokens" +
        "|sec_year=${evidence.latestSecFiscalYear?.toString() ?: "-"}" +
        "|sec_len=${evidence.secSeriesLength}" +
        "|sec_ocf=${evidence.lastSecOcfDollars?.toString() ?: "-"}" +
        "|sec_fcf=${evidence.lastSecFcfDollars?.toString() ?: "-"}" +
        "|yahoo_ocf=${evidence.yahooOcfDollars?.toString() ?: "-"}" +
        "|yahoo_fcf=${evidence.yahooFcfDollars?.toString() ?: "-"}" +
        "|ratio=${policy.scaleRatioThreshold}|floor=${policy.materialityFloorDollars}"
}

private enum class ScaleCompare { Aligned, RatioMismatch, SignConflict }

private fun compareCashScale(
    sec: Long,
    yahoo: Long,
    policy: SourceContinuityPolicy,
): ScaleCompare {
    val floor = policy.materialityFloorDollars
    val a = kotlin.math.abs(sec)
    val b = kotlin.math.abs(yahoo)
    if (a < floor && b < floor) return ScaleCompare.Aligned
    if (sec != 0L && yahoo != 0L && sec.sign() != yahoo.sign() && a >= floor && b >= floor) {
        return ScaleCompare.SignConflict
    }
    val larger = maxOf(a, b)
    val smaller = maxOf(minOf(a, b), 1L)
    return if (larger / smaller >= policy.scaleRatioThreshold) {
        ScaleCompare.RatioMismatch
    } else {
        ScaleCompare.Aligned
    }
}

private fun Long.sign(): Int = when {
    this > 0L -> 1
    this < 0L -> -1
    else -> 0
}

/** Howard Hinnant civil_from_days — peer of the Rust pure helper. */
internal fun epochDayYear(epochDay: Long): Int {
    val z = epochDay + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val doe = (z - era * 146_097).toULong()
    val yoe = (doe - doe / 1460u + doe / 36524u - doe / 146_096u) / 365u
    val y = yoe.toLong() + era * 400
    val doy = doe - (365u * yoe + yoe / 4u - yoe / 100u)
    val mp = (5u * doy + 2u) / 153u
    val m = if (mp < 10u) mp + 3u else mp - 9u
    val year = if (m.toInt() <= 2) y + 1 else y
    return year.toInt()
}

private fun ContinuityStatus.token(): String = when (this) {
    ContinuityStatus.Continuous -> "continuous"
    ContinuityStatus.Discontinuous -> "discontinuous"
    ContinuityStatus.InsufficientEvidence -> "insufficient_evidence"
}

private fun ContinuityReason.token(): String = when (this) {
    ContinuityReason.SecSeriesAbsent -> "sec_series_absent"
    ContinuityReason.SecSeriesShort -> "sec_series_short"
    ContinuityReason.SecFiscalLagSupporting -> "sec_fiscal_lag_supporting"
    ContinuityReason.ScaleMismatchOcf -> "scale_mismatch_ocf"
    ContinuityReason.ScaleMismatchFcf -> "scale_mismatch_fcf"
    ContinuityReason.ScaleSignConflict -> "scale_sign_conflict"
    ContinuityReason.EntityCikMismatch -> "entity_cik_mismatch"
    ContinuityReason.YahooCashMissing -> "yahoo_cash_missing"
    ContinuityReason.AlignedScale -> "aligned_scale"
    ContinuityReason.SecSeriesPresent -> "sec_series_present"
}

private fun List<ContinuityReason>.sortedDistinct(): List<ContinuityReason> =
    distinct().sortedBy { it.ordinal }
