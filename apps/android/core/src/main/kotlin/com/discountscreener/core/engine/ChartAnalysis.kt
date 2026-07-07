package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.ProjectedChartAnalysis
import com.discountscreener.core.model.ProjectedChartData
import com.discountscreener.core.model.ProjectedChartStatus
import com.discountscreener.core.model.ProjectedMacdChartAnalysis
import com.discountscreener.core.model.ProjectedPriceChartAnalysis
import com.discountscreener.core.model.ProjectedPriceDomain
import com.discountscreener.core.model.ProjectedReplayWindow
import com.discountscreener.core.model.ProjectedTechnicalSignal
import com.discountscreener.core.model.ProjectedTechnicalSignalBias
import com.discountscreener.core.model.ProjectedTechnicalSignalKind
import com.discountscreener.core.model.ProjectedVolumeChartAnalysis
import com.discountscreener.core.model.ProjectedVolumeProfileAnalysis
import com.discountscreener.core.model.ProjectedVolumeProfileBin
import kotlin.math.abs
import kotlin.math.round

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
    fun buildProjectedChartData(
        range: ChartRange,
        candles: List<HistoricalCandle>,
        capturedAtEpochSeconds: Long,
        replayOffset: Int,
        volumeProfileBinCount: Int,
        summary: ChartRangeSummary? = null,
    ): ProjectedChartData {
        var chartSummary = summary ?: candles.takeIf { candidateCandles -> candidateCandles.isNotEmpty() }?.let { visibleCandles ->
            buildSummary(
                range = range,
                candles = visibleCandles,
                capturedAtEpochSeconds = capturedAtEpochSeconds,
            )
        }
        var replayWindow = buildReplayWindow(candles, replayOffset)
        return ProjectedChartData(
            range = range,
            candles = candles,
            summary = chartSummary,
            analysis = buildProjectedAnalysis(
                replayWindow = replayWindow,
                summary = chartSummary,
                volumeProfileBinCount = volumeProfileBinCount,
            ),
        )
    }

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

    fun buildVolumeProfileAnalysis(
        candles: List<HistoricalCandle>,
        minPriceCents: Long,
        maxPriceCents: Long,
        binCount: Int,
    ): ProjectedVolumeProfileAnalysis {
        var bins = computeVolumeProfile(
            candles = candles,
            minPriceCents = minPriceCents,
            maxPriceCents = maxPriceCents,
            binCount = binCount,
        ).map { bin ->
            ProjectedVolumeProfileBin(
                upVolume = bin.upVolume,
                downVolume = bin.downVolume,
            )
        }
        return ProjectedVolumeProfileAnalysis(
            bins = bins,
            maxBinVolume = bins.maxOfOrNull(ProjectedVolumeProfileBin::totalVolume) ?: 0L,
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

    private fun buildProjectedAnalysis(
        replayWindow: ReplayWindow,
        summary: ChartRangeSummary?,
        volumeProfileBinCount: Int,
    ): ProjectedChartAnalysis {
        var visibleCandles = replayWindow.visibleCandles
        var projectedReplayWindow = replayWindow.toProjectedReplayWindow()
        if (visibleCandles.isEmpty()) {
            return ProjectedChartAnalysis(
                status = if (summary == null) ProjectedChartStatus.Unavailable else ProjectedChartStatus.SummaryOnly,
                replayWindow = projectedReplayWindow,
            )
        }

        var closes = visibleCandles.map { candle -> candle.closeCents.toDouble() }
        var ema20 = ema(closes, 20)
        var ema50 = ema(closes, 50)
        var ema200 = ema(closes, 200)
        var price = priceAnalysis(
            candles = visibleCandles,
            ema20 = ema20,
            ema50 = ema50,
            ema200 = ema200,
        )
        var macd = macdAnalysis(visibleCandles, closes)
        var volume = ProjectedVolumeChartAnalysis(
            maxVolume = visibleCandles.maxOfOrNull(HistoricalCandle::volume)?.coerceAtLeast(1L) ?: 1L,
        )
        var volumeProfile = buildVolumeProfileAnalysis(
            candles = visibleCandles,
            minPriceCents = price.domain.minValue.roundToLong(),
            maxPriceCents = price.domain.maxValue.roundToLong(),
            binCount = volumeProfileBinCount,
        )
        return ProjectedChartAnalysis(
            status = ProjectedChartStatus.Available,
            replayWindow = projectedReplayWindow,
            price = price,
            volume = volume,
            volumeProfile = volumeProfile,
            macd = macd,
            technicalSignals = technicalSignals(
                ema20 = ema20,
                ema50 = ema50,
                ema200 = ema200,
                macd = macd,
            ),
        )
    }

    private fun priceAnalysis(
        candles: List<HistoricalCandle>,
        ema20: List<Double>,
        ema50: List<Double>,
        ema200: List<Double>,
    ): ProjectedPriceChartAnalysis {
        var allValues = buildList {
            candles.forEach { candle ->
                add(candle.lowCents.toFloat())
                add(candle.highCents.toFloat())
                add(candle.openCents.toFloat())
                add(candle.closeCents.toFloat())
            }
            addAll(ema20.map(Double::toFloat))
            addAll(ema50.map(Double::toFloat))
            addAll(ema200.map(Double::toFloat))
        }
        var domain = paddedPriceDomain(
            min = allValues.minOrNull() ?: 0f,
            max = allValues.maxOrNull() ?: 0f,
        )
        return ProjectedPriceChartAnalysis(
            domain = domain,
            latestCloseCents = candles.lastOrNull()?.closeCents,
            ema20 = ema20,
            ema50 = ema50,
            ema200 = ema200,
            latestEma20Cents = ema20.lastOrNull()?.roundToLong(),
            latestEma50Cents = ema50.lastOrNull()?.roundToLong(),
            latestEma200Cents = ema200.lastOrNull()?.roundToLong(),
        )
    }

    private fun macdAnalysis(
        candles: List<HistoricalCandle>,
        closes: List<Double>,
    ): ProjectedMacdChartAnalysis? {
        if (candles.size < 26) return null
        var macd = macd(closes)
        return ProjectedMacdChartAnalysis(
            macdLine = macd.macd,
            signalLine = macd.signal,
            histogram = macd.histogram,
            latestMacdCents = macd.macd.lastOrNull()?.roundToLong(),
            latestSignalCents = macd.signal.lastOrNull()?.roundToLong(),
            latestHistogramCents = macd.histogram.lastOrNull()?.roundToLong(),
        )
    }

    private fun technicalSignals(
        ema20: List<Double>,
        ema50: List<Double>,
        ema200: List<Double>,
        macd: ProjectedMacdChartAnalysis?,
    ): List<ProjectedTechnicalSignal> = buildList {
        buildTechnicalSignal(ema20, ema50, ProjectedTechnicalSignalKind.Ema20Ema50)?.let(::add)
        buildTechnicalSignal(ema50, ema200, ProjectedTechnicalSignalKind.Ema50Ema200)?.let(::add)
        macd?.let { macdAnalysis ->
            buildTechnicalSignal(
                macdAnalysis.macdLine,
                macdAnalysis.signalLine,
                ProjectedTechnicalSignalKind.MacdSignal,
            )?.let(::add)
        }
    }

    private fun buildTechnicalSignal(
        fastSeries: List<Double>,
        slowSeries: List<Double>,
        kind: ProjectedTechnicalSignalKind,
    ): ProjectedTechnicalSignal? {
        var latestIndex = minOf(fastSeries.lastIndex, slowSeries.lastIndex)
        if (latestIndex < 1) return null
        var latestDiff = fastSeries[latestIndex] - slowSeries[latestIndex]
        if (latestDiff == 0.0) return null
        var previousDiff = fastSeries[latestIndex - 1] - slowSeries[latestIndex - 1]
        var bullish = latestDiff > 0.0
        var freshCross = (bullish && previousDiff <= 0.0) || (!bullish && previousDiff >= 0.0)
        var bias = if (bullish) ProjectedTechnicalSignalBias.Bull else ProjectedTechnicalSignalBias.Bear
        return ProjectedTechnicalSignal(
            kind = kind,
            title = technicalSignalTitle(kind, bias, freshCross),
            meaning = technicalSignalMeaning(kind, bias, freshCross),
            bias = bias,
            freshCross = freshCross,
        )
    }

    private fun technicalSignalTitle(
        kind: ProjectedTechnicalSignalKind,
        bias: ProjectedTechnicalSignalBias,
        freshCross: Boolean,
    ): String {
        var label = technicalSignalLabel(kind)
        return when {
            freshCross && bias == ProjectedTechnicalSignalBias.Bull -> "Bull cross $label"
            freshCross -> "Bear cross $label"
            bias == ProjectedTechnicalSignalBias.Bull -> "Bull $label"
            else -> "Bear $label"
        }
    }

    private fun technicalSignalMeaning(
        kind: ProjectedTechnicalSignalKind,
        bias: ProjectedTechnicalSignalBias,
        freshCross: Boolean,
    ): String = when (kind) {
        ProjectedTechnicalSignalKind.Ema20Ema50 -> when {
            freshCross && bias == ProjectedTechnicalSignalBias.Bull -> "Short trend just crossed above medium trend"
            freshCross -> "Short trend just crossed below medium trend"
            bias == ProjectedTechnicalSignalBias.Bull -> "Short trend is above medium trend"
            else -> "Short trend is below medium trend"
        }
        ProjectedTechnicalSignalKind.Ema50Ema200 -> when {
            freshCross && bias == ProjectedTechnicalSignalBias.Bull -> "Medium trend just crossed above long trend"
            freshCross -> "Medium trend just crossed below long trend"
            bias == ProjectedTechnicalSignalBias.Bull -> "Medium trend is above long trend"
            else -> "Medium trend is below long trend"
        }
        ProjectedTechnicalSignalKind.MacdSignal -> when {
            freshCross && bias == ProjectedTechnicalSignalBias.Bull -> "Momentum just crossed above the signal line"
            freshCross -> "Momentum just crossed below the signal line"
            bias == ProjectedTechnicalSignalBias.Bull -> "Momentum is above the signal line"
            else -> "Momentum is below the signal line"
        }
    }

    private fun technicalSignalLabel(kind: ProjectedTechnicalSignalKind): String = when (kind) {
        ProjectedTechnicalSignalKind.Ema20Ema50 -> "E20/E50"
        ProjectedTechnicalSignalKind.Ema50Ema200 -> "E50/E200"
        ProjectedTechnicalSignalKind.MacdSignal -> "MACD"
    }

    private fun paddedPriceDomain(min: Float, max: Float): ProjectedPriceDomain {
        if (min == max) {
            var padding = maxOf(1f, abs(min) * 0.05f)
            return ProjectedPriceDomain(minValue = min - padding, maxValue = max + padding)
        }
        var padding = ((max - min) * 0.05f).coerceAtLeast(1f)
        return ProjectedPriceDomain(minValue = min - padding, maxValue = max + padding)
    }

    private fun ReplayWindow.toProjectedReplayWindow(): ProjectedReplayWindow = ProjectedReplayWindow(
        visibleCandles = visibleCandles,
        totalCandles = totalCandles,
        replayOffset = replayOffset,
    )

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

    private fun Double.roundToLong(): Long = round(this).toLong()
    private fun Float.roundToLong(): Long = round(this).toLong()
    private fun Double.roundToInt(): Int = round(this).toInt()
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
