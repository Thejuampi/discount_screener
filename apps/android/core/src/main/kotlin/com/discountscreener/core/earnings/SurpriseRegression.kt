package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

data class DatedAbnormalReturn(
    val date: LocalDate,
    val abnormalReturnBps: Int,
)

data class SurpriseObservation(
    val reportDate: LocalDate,
    val sueBps: Int,
    val abnormalReturnBps: Int,
)

sealed class SurpriseFit {
    data class Ready(
        val n: Int,
        val interceptBps: Int,
        val sueSlopeArBps: Int,
        val asymmetric: Boolean,
    ) : SurpriseFit()

    data class Unavailable(val reason: String) : SurpriseFit()
}

fun joinSueWithReturns(
    quarters: List<SueQuarter>,
    returns: List<DatedAbnormalReturn>,
    matchDays: Int = EarningsGatePolicy.current.sueMatchDays,
): List<SurpriseObservation> {
    if (returns.isEmpty()) return emptyList()
    var window = matchDays.toLong()
    return quarters.mapNotNull { quarter ->
        var day = quarter.reportedOn ?: return@mapNotNull null
        var hit = returns.minByOrNull { abs(it.date.toEpochDay() - day.toEpochDay()) }
            ?.takeIf { abs(it.date.toEpochDay() - day.toEpochDay()) <= window }
            ?: return@mapNotNull null
        SurpriseObservation(
            reportDate = day,
            sueBps = quarter.sueBps,
            abnormalReturnBps = hit.abnormalReturnBps,
        )
    }
}

fun fitSurpriseRegression(obs: List<SurpriseObservation>): SurpriseFit {
    var minN = EarningsGatePolicy.current.minSueQuarters
    if (obs.size < minN) return SurpriseFit.Unavailable("short_history")
    var symmetric = slopeFit(obs)
    var cvSymmetric = loocv(obs, asymmetric = false)
    var cvAsymmetric = loocv(obs, asymmetric = true)
    var asymmetric = cvAsymmetric < cvSymmetric
    var chosen = if (asymmetric) slopeFit(obs, asymmetric = true) else symmetric
    return SurpriseFit.Ready(
        n = obs.size,
        interceptBps = chosen.intercept.roundToInt(),
        sueSlopeArBps = chosen.slope.roundToInt(),
        asymmetric = asymmetric,
    )
}

private data class Slope(val intercept: Double, val slope: Double)

private fun slopeFit(obs: List<SurpriseObservation>, asymmetric: Boolean = false): Slope {
    var xs = obs.map { xOf(it, asymmetric) }
    var ys = obs.map { it.abnormalReturnBps.toDouble() }
    return ordinaryLeastSquares(xs, ys)
}

private fun xOf(obs: SurpriseObservation, asymmetric: Boolean): Double {
    var sue = obs.sueBps / 10_000.0
    return if (asymmetric && sue > 0.0) sue else if (asymmetric) 0.0 else sue
}

private fun ordinaryLeastSquares(xs: List<Double>, ys: List<Double>): Slope {
    var n = xs.size.toDouble()
    var sumX = xs.sum()
    var sumY = ys.sum()
    var sumXy = xs.zip(ys).sumOf { it.first * it.second }
    var sumX2 = xs.sumOf { it * it }
    var den = n * sumX2 - sumX * sumX
    if (den == 0.0) return Slope(intercept = sumY / n, slope = 0.0)
    var slope = (n * sumXy - sumX * sumY) / den
    var intercept = (sumY - slope * sumX) / n
    return Slope(intercept, slope)
}

private fun loocv(obs: List<SurpriseObservation>, asymmetric: Boolean): Double {
    var errors = obs.indices.map { held ->
        var train = obs.filterIndexed { index, _ -> index != held }
        var fit = slopeFit(train, asymmetric)
        var x = xOf(obs[held], asymmetric)
        var predicted = fit.intercept + fit.slope * x
        var error = obs[held].abnormalReturnBps - predicted
        error * error
    }
    return errors.average()
}
