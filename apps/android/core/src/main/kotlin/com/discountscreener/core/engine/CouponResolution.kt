package com.discountscreener.core.engine

import com.discountscreener.core.math.medianOf
import com.discountscreener.core.model.FundamentalTimeseries
import kotlin.math.abs

const val COUPON_RESOLUTION_VERSION = "coupon-resolution/1"

enum class CouponKind {
    Filed,
    Zero,
    Estimated,
    Absent,
}

enum class CouponEstimateMethod {
    OwnEffectiveRate,
    PeerEffectiveRate,
}

enum class CouponConfidence {
    High,
    Medium,
    Low,
    Insufficient,
}

data class PeerCouponEvidence(
    val symbol: String,
    val couponDollars: Double,
    val debtDollars: Double,
)

data class IssuerCouponSample(
    val symbol: String,
    val sectorName: String?,
    val industryName: String?,
    val couponDollars: Double,
    val debtDollars: Double,
)

data class CouponYearInput(
    val period: String,
    val filedCouponDollars: Double?,
    val debtDollars: Double?,
)

data class CouponYear(
    val period: String,
    val kind: CouponKind,
    val dollars: Double?,
    val confidence: CouponConfidence,
    val method: CouponEstimateMethod? = null,
    val ownFiledCount: Int,
    val holeCount: Int,
    val peerCount: Int,
)

fun resolveCoupons(
    years: List<CouponYearInput>,
    peers: List<PeerCouponEvidence> = emptyList(),
): List<CouponYear> {
    var sorted = years.sortedBy { it.period }
    var ownFiled = sorted.filter { filedDollars(it) != null }
    var holes = sorted.filter { isHole(it) }
    var ownFiledCount = ownFiled.size
    var holeCount = holes.size
    var lastPriorByPeriod = lastPriorFiledRate(sorted)
    var firstLaterRate = firstLaterFiledRate(sorted)
    var peerRates = peers.mapNotNull { rate(it.couponDollars, it.debtDollars) }
    var peerMedian = if (peerRates.size >= 3) medianOf(peerRates) else null
    var peerCount = peerRates.size
    return sorted.map { year ->
        var filed = filedDollars(year)
        if (filed != null) {
            return@map CouponYear(
                period = year.period,
                kind = CouponKind.Filed,
                dollars = filed,
                confidence = CouponConfidence.High,
                ownFiledCount = ownFiledCount,
                holeCount = holeCount,
                peerCount = peerCount,
            )
        }
        if (noPeriodDebt(year)) {
            return@map CouponYear(
                period = year.period,
                kind = CouponKind.Zero,
                dollars = 0.0,
                confidence = CouponConfidence.High,
                ownFiledCount = ownFiledCount,
                holeCount = holeCount,
                peerCount = peerCount,
            )
        }
        var debt = year.debtDollars?.takeIf { it.isFinite() && it > 0.0 }
        if (debt == null) {
            return@map absent(year.period, ownFiledCount, holeCount, peerCount)
        }
        var prior = lastPriorByPeriod[year.period]
        var ownRate = prior?.rate ?: firstLaterRate
        var usedLaterOnly = prior == null && firstLaterRate != null
        if (ownRate != null) {
            var confidence = ownConfidence(ownFiledCount, holeCount)
            if (usedLaterOnly) {
                confidence = stepDown(confidence)
            }
            var lastDebt = prior?.debt
            if (lastDebt != null && lastDebt > 0.0 && extremeDebtMove(debt, lastDebt)) {
                confidence = stepDown(confidence)
            }
            if (confidence != CouponConfidence.Insufficient) {
                return@map CouponYear(
                    period = year.period,
                    kind = CouponKind.Estimated,
                    dollars = ownRate * debt,
                    confidence = confidence,
                    method = CouponEstimateMethod.OwnEffectiveRate,
                    ownFiledCount = ownFiledCount,
                    holeCount = holeCount,
                    peerCount = peerCount,
                )
            }
        }
        if (peerMedian != null) {
            var confidence = peerConfidence(peerCount)
            if (confidence != CouponConfidence.Insufficient) {
                return@map CouponYear(
                    period = year.period,
                    kind = CouponKind.Estimated,
                    dollars = peerMedian * debt,
                    confidence = confidence,
                    method = CouponEstimateMethod.PeerEffectiveRate,
                    ownFiledCount = ownFiledCount,
                    holeCount = holeCount,
                    peerCount = peerCount,
                )
            }
        }
        absent(year.period, ownFiledCount, holeCount, peerCount)
    }
}

fun similarIssuerCoupons(
    subjectSector: String?,
    subjectIndustry: String?,
    others: List<IssuerCouponSample>,
): List<PeerCouponEvidence> {
    var industry = normalizeName(subjectIndustry)
    var sector = normalizeName(subjectSector)
    var industryHits = others.filter { normalizeName(it.industryName) == industry && industry != null }
    var chosen = if (industryHits.isNotEmpty()) {
        industryHits
    } else {
        others.filter { normalizeName(it.sectorName) == sector && sector != null }
    }
    return chosen.map { PeerCouponEvidence(it.symbol, it.couponDollars, it.debtDollars) }
}

fun couponInputsFrom(timeseries: FundamentalTimeseries): List<CouponYearInput> {
    var interestByPeriod = timeseries.interestExpense.associateBy(::annualKey)
    var debtByPeriod = timeseries.totalDebt.associateBy(::annualKey)
    return timeseries.operatingCashFlow.sortedBy(::annualKey).map { operating ->
        var period = annualKey(operating)
        CouponYearInput(
            period = period,
            filedCouponDollars = interestByPeriod[period]
                ?.takeIf { !isCashPaidCouponConcept(it.concept) }
                ?.value,
            debtDollars = debtByPeriod[period]?.value,
        )
    }
}

fun lastFiledIssuerSample(
    symbol: String,
    sectorName: String?,
    industryName: String?,
    timeseries: FundamentalTimeseries,
): IssuerCouponSample? = lastFiledIssuerSample(
    symbol,
    sectorName,
    industryName,
    couponInputsFrom(timeseries),
)

fun lastFiledIssuerSample(
    symbol: String,
    sectorName: String?,
    industryName: String?,
    years: List<CouponYearInput>,
): IssuerCouponSample? {
    var last = years.sortedBy { it.period }.mapNotNull { year ->
        var filed = filedDollars(year) ?: return@mapNotNull null
        var debt = year.debtDollars?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
        IssuerCouponSample(symbol, sectorName, industryName, filed, debt)
    }.lastOrNull()
    return last
}

internal fun ownConfidence(filed: Int, holes: Int): CouponConfidence {
    if (filed <= 0) return CouponConfidence.Insufficient
    var holeCount = holes.coerceAtLeast(0)
    var coverage = filed.toDouble() / (filed + holeCount).toDouble()
    if (filed >= 4 && holeCount <= 1 && coverage >= 0.80) return CouponConfidence.High
    if (filed >= 3 && coverage >= 0.50) return CouponConfidence.Medium
    if (filed >= 2 && holeCount <= 1) return CouponConfidence.Medium
    return CouponConfidence.Low
}

internal fun peerConfidence(peerCount: Int): CouponConfidence {
    if (peerCount >= 5) return CouponConfidence.Medium
    if (peerCount >= 3) return CouponConfidence.Low
    return CouponConfidence.Insufficient
}

private data class FiledRate(
    val rate: Double,
    val debt: Double,
)

private fun lastPriorFiledRate(sorted: List<CouponYearInput>): Map<String, FiledRate> {
    var last: FiledRate? = null
    var out = linkedMapOf<String, FiledRate>()
    for (year in sorted) {
        last?.let { out[year.period] = it }
        var filed = filedDollars(year)
        var debt = year.debtDollars
        var next = if (filed != null && debt != null) rate(filed, debt)?.let { FiledRate(it, debt) } else null
        if (next != null) {
            last = next
        }
    }
    return out
}

private fun firstLaterFiledRate(sorted: List<CouponYearInput>): Double? {
    for (year in sorted) {
        var filed = filedDollars(year) ?: continue
        var debt = year.debtDollars ?: continue
        var value = rate(filed, debt)
        if (value != null) return value
    }
    return null
}

private fun filedDollars(year: CouponYearInput): Double? =
    year.filedCouponDollars?.takeIf { it.isFinite() }?.let { abs(it) }

private fun noPeriodDebt(year: CouponYearInput): Boolean {
    var debt = year.debtDollars
    return debt == null || !debt.isFinite() || debt == 0.0
}

private fun isHole(year: CouponYearInput): Boolean =
    filedDollars(year) == null && !noPeriodDebt(year)

private fun rate(coupon: Double, debt: Double): Double? {
    if (!coupon.isFinite() || !debt.isFinite() || debt <= 0.0) return null
    var value = abs(coupon) / debt
    return value.takeIf { it.isFinite() && it > 0.0 }
}

private fun extremeDebtMove(thisDebt: Double, lastDebt: Double): Boolean {
    var ratio = thisDebt / lastDebt
    return !ratio.isFinite() || ratio < 0.25 || ratio > 4.0
}

private fun stepDown(confidence: CouponConfidence): CouponConfidence = when (confidence) {
    CouponConfidence.High -> CouponConfidence.Medium
    CouponConfidence.Medium -> CouponConfidence.Low
    CouponConfidence.Low -> CouponConfidence.Insufficient
    CouponConfidence.Insufficient -> CouponConfidence.Insufficient
}

private fun absent(
    period: String,
    ownFiledCount: Int,
    holeCount: Int,
    peerCount: Int,
): CouponYear = CouponYear(
    period = period,
    kind = CouponKind.Absent,
    dollars = null,
    confidence = CouponConfidence.Insufficient,
    ownFiledCount = ownFiledCount,
    holeCount = holeCount,
    peerCount = peerCount,
)

private fun normalizeName(value: String?): String? {
    var trimmed = value?.trim()?.lowercase().orEmpty()
    return trimmed.takeIf { it.isNotEmpty() }
}
