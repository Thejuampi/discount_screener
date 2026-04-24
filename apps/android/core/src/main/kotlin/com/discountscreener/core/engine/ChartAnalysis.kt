package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.HistoricalCandle

data class ReplayWindow(
    val visibleCandles: List<HistoricalCandle>,
    val totalCandles: Int,
    val replayOffset: Int,
) {
    val visibleCount: Int = visibleCandles.size
    val isLive: Boolean = replayOffset == 0
}

data class VolumeProfileBin(
    val upVolume: Long,
    val downVolume: Long,
) {
    val totalVolume: Long = upVolume + downVolume
}

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

    fun buildReplayWindow(
        candles: List<HistoricalCandle>,
        replayOffset: Int,
    ): ReplayWindow {
        if (candles.isEmpty()) {
            return ReplayWindow(
                visibleCandles = emptyList(),
                totalCandles = 0,
                replayOffset = 0,
            )
        }
        val clampedOffset = replayOffset.coerceIn(0, candles.size - 1)
        val visibleEnd = (candles.size - clampedOffset).coerceAtLeast(1)
        return ReplayWindow(
            visibleCandles = candles.take(visibleEnd),
            totalCandles = candles.size,
            replayOffset = clampedOffset,
        )
    }

    fun stepReplayBack(replayOffset: Int, totalCandles: Int): Int {
        val maxOffset = totalCandles.saturatingLastIndex()
        return (replayOffset + 1).coerceIn(0, maxOffset)
    }

    fun stepReplayForward(replayOffset: Int): Int =
        (replayOffset - 1).coerceAtLeast(0)

    fun computeVolumeProfile(
        candles: List<HistoricalCandle>,
        minPriceCents: Long,
        maxPriceCents: Long,
        binCount: Int,
    ): List<VolumeProfileBin> {
        val bins = MutableList(binCount.coerceAtLeast(0)) {
            MutableVolumeProfileBin()
        }
        if (bins.isEmpty() || minPriceCents >= maxPriceCents) {
            return bins.map { it.toBin() }
        }

        val range = (maxPriceCents - minPriceCents).toDouble()
        val lastBin = bins.lastIndex
        candles.forEach { candle ->
            val low = candle.lowCents.coerceAtLeast(minPriceCents)
            val high = candle.highCents.coerceAtMost(maxPriceCents)
            if (low > high) return@forEach

            val lowBin = (((low - minPriceCents).toDouble() / range) * lastBin)
                .roundToInt()
                .coerceIn(0, lastBin)
            val highBin = (((high - minPriceCents).toDouble() / range) * lastBin)
                .roundToInt()
                .coerceIn(0, lastBin)
            val span = (highBin - lowBin + 1).coerceAtLeast(1)
            val volume = candle.volume.coerceAtLeast(0L)
            val perBin = volume / span
            val remainder = volume % span
            val isUp = candle.closeCents >= candle.openCents

            (lowBin..highBin).forEachIndexed { index, priceBin ->
                val row = lastBin - priceBin
                val binVolume = perBin + if (index.toLong() < remainder) 1L else 0L
                if (isUp) {
                    bins[row].upVolume += binVolume
                } else {
                    bins[row].downVolume += binVolume
                }
            }
        }
        return bins.map { it.toBin() }
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
    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
    private fun Int.saturatingLastIndex(): Int = (this - 1).coerceAtLeast(0)

    private data class MutableVolumeProfileBin(
        var upVolume: Long = 0,
        var downVolume: Long = 0,
    ) {
        fun toBin(): VolumeProfileBin = VolumeProfileBin(
            upVolume = upVolume,
            downVolume = downVolume,
        )
    }
}
