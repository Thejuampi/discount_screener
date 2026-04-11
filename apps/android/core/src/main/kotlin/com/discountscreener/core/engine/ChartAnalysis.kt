package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.HistoricalCandle

object ChartAnalysis {
    fun buildSummary(
        range: ChartRange,
        candles: List<HistoricalCandle>,
        capturedAtEpochSeconds: Long,
    ): ChartRangeSummary {
        val closes = candles.map { it.closeCents.toDouble() }
        val latest = closes.lastOrNull()?.toLong()
        val ema20 = ema(closes, 20).lastOrNull()?.roundToLong()
        val ema50 = ema(closes, 50).lastOrNull()?.roundToLong()
        val ema200 = ema(closes, 200).lastOrNull()?.roundToLong()
        val macd = macd(closes)
        return ChartRangeSummary(
            range = range,
            capturedAt = capturedAtEpochSeconds,
            candleCount = candles.size,
            latestCloseCents = latest,
            ema20Cents = ema20,
            ema50Cents = ema50,
            ema200Cents = ema200,
            macdCents = macd.macd.lastOrNull()?.roundToLong(),
            signalCents = macd.signal.lastOrNull()?.roundToLong(),
            histogramCents = macd.histogram.lastOrNull()?.roundToLong(),
        )
    }

    private fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val multiplier = 2.0 / (period + 1)
        val output = ArrayList<Double>(values.size)
        var previous = values.first()
        values.forEachIndexed { index, value ->
            previous = if (index == 0) value else ((value - previous) * multiplier) + previous
            output += previous
        }
        return output
    }

    private data class MacdValues(
        val macd: List<Double>,
        val signal: List<Double>,
        val histogram: List<Double>,
    )

    private fun macd(values: List<Double>): MacdValues {
        if (values.isEmpty()) return MacdValues(emptyList(), emptyList(), emptyList())
        val fast = ema(values, 12)
        val slow = ema(values, 26)
        val macdLine = fast.zip(slow).map { (fastValue, slowValue) -> fastValue - slowValue }
        val signalLine = ema(macdLine, 9)
        val histogram = macdLine.zip(signalLine).map { (macdValue, signalValue) -> macdValue - signalValue }
        return MacdValues(macdLine, signalLine, histogram)
    }

    private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()
}
