package com.discountscreener.core.earnings

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

data class OptionQuote(val bid: Double, val ask: Double)

data class ChainRow(val strike: Double, val call: OptionQuote, val put: OptionQuote)

data class ImpliedMove(
    val fraction: Double,
    val strike: Double,
    val straddlePrice: Double,
    val quoteSpreadBps: Int,
)

private const val MAX_STRIKE_OFFSET = 0.025

fun impliedMove(rows: List<ChainRow>, forward: Double): ImpliedMove? {
    if (!forward.isFinite() || forward <= 0.0) return null
    var nearest = rows
        .filter { it.strike.isFinite() && it.strike > 0.0 }
        .minWithOrNull(compareBy({ abs(it.strike - forward) }, { it.strike }))
        ?: return null
    if (abs(nearest.strike - forward) / forward > MAX_STRIKE_OFFSET) return null
    var call = midOf(nearest.call) ?: return null
    var put = midOf(nearest.put) ?: return null
    var straddle = call + put
    if (straddle <= 0.0) return null
    var width = widthOf(nearest.call) + widthOf(nearest.put)
    return ImpliedMove(
        fraction = straddle / forward,
        strike = nearest.strike,
        straddlePrice = straddle,
        quoteSpreadBps = (width / straddle * 10_000.0).roundToInt(),
    )
}

private fun widthOf(quote: OptionQuote): Double = quote.ask - quote.bid

internal fun midOf(quote: OptionQuote): Double? {
    if (!quote.bid.isFinite() || !quote.ask.isFinite()) return null
    if (quote.bid <= 0.0) return null
    if (quote.ask < quote.bid) return null
    return (quote.bid + quote.ask) / 2.0
}

fun expiryAfterReport(expiries: List<LocalDate>, reportDate: LocalDate): LocalDate? =
    expiries.filter { it.isAfter(reportDate) }.minOrNull()
