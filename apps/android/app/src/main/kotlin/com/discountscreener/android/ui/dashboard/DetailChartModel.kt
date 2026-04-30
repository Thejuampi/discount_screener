package com.discountscreener.android.ui.dashboard

import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.VolumeProfileBin
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class ChartAxisLabels(
    val top: String,
    val middle: String,
    val bottom: String,
)

internal data class ChartDateTick(
    val index: Int,
    val positionFraction: Float,
    val label: String,
)

internal enum class TrendSignalBias {
    Bull,
    Bear,
}

internal data class TrendSignal(
    val title: String,
    val meaning: String,
    val bias: TrendSignalBias,
    val freshCross: Boolean,
)

internal data class PriceChartModel(
    val minValue: Float,
    val maxValue: Float,
    val ema20: List<Double>,
    val ema50: List<Double>,
    val ema200: List<Double>,
    val axisLabels: ChartAxisLabels,
    val trendSignals: List<TrendSignal>,
) {
    val span: Float = (maxValue - minValue).coerceAtLeast(1f)
    val latestEma20Cents: Long? = ema20.lastOrNull()?.roundToLong()
    val latestEma50Cents: Long? = ema50.lastOrNull()?.roundToLong()
    val latestEma200Cents: Long? = ema200.lastOrNull()?.roundToLong()
}

internal data class VolumeChartModel(
    val maxVolume: Long,
    val axisLabels: ChartAxisLabels,
)

internal data class VolumeProfileModel(
    val bins: List<VolumeProfileBin>,
    val maxBinVolume: Long,
)

internal data class MacdChartModel(
    val macdLine: List<Double>,
    val signalLine: List<Double>,
    val histogram: List<Double>,
    val scale: MacdChartScale,
    val axisLabels: ChartAxisLabels,
    val trendSignal: TrendSignal?,
)

internal fun buildPriceChartModel(candles: List<HistoricalCandle>): PriceChartModel? {
    if (candles.isEmpty()) return null
    val closes = candles.map { it.closeCents.toDouble() }
    val ema20 = emaValues(closes, 20)
    val ema50 = emaValues(closes, 50)
    val ema200 = emaValues(closes, 200)
    val allValues = buildList {
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
    val (minValue, maxValue) = paddedValueRange(
        min = allValues.minOrNull() ?: 0f,
        max = allValues.maxOrNull() ?: 0f,
    )
    return PriceChartModel(
        minValue = minValue,
        maxValue = maxValue,
        ema20 = ema20,
        ema50 = ema50,
        ema200 = ema200,
        axisLabels = axisLabelsForCents(minValue, maxValue),
        trendSignals = listOfNotNull(
            buildTrendSignal(
                fastSeries = ema20,
                slowSeries = ema50,
                label = "E20/E50",
                bullishMeaning = "Short trend is above medium trend",
                bearishMeaning = "Short trend is below medium trend",
                bullishCrossMeaning = "Short trend just crossed above medium trend",
                bearishCrossMeaning = "Short trend just crossed below medium trend",
            ),
            buildTrendSignal(
                fastSeries = ema50,
                slowSeries = ema200,
                label = "E50/E200",
                bullishMeaning = "Medium trend is above long trend",
                bearishMeaning = "Medium trend is below long trend",
                bullishCrossMeaning = "Medium trend just crossed above long trend",
                bearishCrossMeaning = "Medium trend just crossed below long trend",
            ),
        ),
    )
}

internal fun buildVolumeChartModel(candles: List<HistoricalCandle>): VolumeChartModel? {
    if (candles.isEmpty()) return null
    val maxVolume = candles.maxOfOrNull(HistoricalCandle::volume)?.coerceAtLeast(1L) ?: 1L
    return VolumeChartModel(
        maxVolume = maxVolume,
        axisLabels = ChartAxisLabels(
            top = compactFinancialNumber(maxVolume),
            middle = compactFinancialNumber(maxVolume / 2),
            bottom = "0",
        ),
    )
}

internal fun buildVolumeProfileModel(
    candles: List<HistoricalCandle>,
    minPriceCents: Long,
    maxPriceCents: Long,
    binCount: Int,
): VolumeProfileModel? {
    if (candles.isEmpty() || binCount <= 0) return null
    val bins = ChartAnalysis.computeVolumeProfile(
        candles = candles,
        minPriceCents = minPriceCents,
        maxPriceCents = maxPriceCents,
        binCount = binCount,
    )
    val maxBinVolume = bins.maxOfOrNull(VolumeProfileBin::totalVolume) ?: 0L
    return VolumeProfileModel(
        bins = bins,
        maxBinVolume = maxBinVolume,
    )
}

internal fun replayStatusText(
    visibleCount: Int,
    totalCount: Int,
    replayOffset: Int,
    maxVolume: Long,
): String {
    val replayLabel = if (replayOffset > 0) "Replay -$replayOffset from live" else "Live"
    return "Showing $visibleCount / $totalCount candles  |  $replayLabel  |  Volume max ${compactFinancialNumber(maxVolume)}"
}

internal fun buildMacdChartModel(candles: List<HistoricalCandle>): MacdChartModel? {
    if (candles.size < 26) return null
    val closes = candles.map { it.closeCents.toDouble() }
    val ema12 = emaValues(closes, 12)
    val ema26 = emaValues(closes, 26)
    val macdLine = ema12.zip(ema26).map { (fast, slow) -> fast - slow }
    val signalLine = emaValues(macdLine, 9)
    val histogram = macdLine.zip(signalLine).map { (macd, signal) -> macd - signal }
    val scale = macdChartScale(macdLine, signalLine, histogram)
    return MacdChartModel(
        macdLine = macdLine,
        signalLine = signalLine,
        histogram = histogram,
        scale = scale,
        axisLabels = axisLabelsForCents(scale.minValue, scale.maxValue),
        trendSignal = buildTrendSignal(
            fastSeries = macdLine,
            slowSeries = signalLine,
            label = "MACD",
            bullishMeaning = "Momentum is above the signal line",
            bearishMeaning = "Momentum is below the signal line",
            bullishCrossMeaning = "Momentum just crossed above the signal line",
            bearishCrossMeaning = "Momentum just crossed below the signal line",
        ),
    )
}

internal fun buildDateAxisTicks(
    candles: List<HistoricalCandle>,
    range: ChartRange,
    maxLabels: Int = 4,
): List<ChartDateTick> {
    if (candles.isEmpty()) return emptyList()
    if (candles.size == 1) {
        return listOf(
            ChartDateTick(
                index = 0,
                positionFraction = 0f,
                label = formatDateTick(candles.first().epochSeconds, range),
            ),
        )
    }
    val labelCount = maxLabels.coerceIn(2, candles.size)
    val lastIndex = candles.lastIndex
    return (0 until labelCount)
        .map { tick ->
            (lastIndex.toFloat() * tick.toFloat() / (labelCount - 1).toFloat()).roundToInt()
        }
        .distinct()
        .map { index ->
            ChartDateTick(
                index = index,
                positionFraction = index.toFloat() / lastIndex.toFloat(),
                label = formatDateTick(candles[index].epochSeconds, range),
            )
        }
}

internal fun emaValues(values: List<Double>, period: Int): List<Double> {
    if (values.isEmpty() || period <= 0) return emptyList()
    val multiplier = 2.0 / (period + 1)
    val output = ArrayList<Double>(values.size)
    var previous = values.first()
    values.forEachIndexed { index, value ->
        previous = if (index == 0) value else ((value - previous) * multiplier) + previous
        output += previous
    }
    return output
}

internal fun buildTrendSignal(
    fastSeries: List<Double>,
    slowSeries: List<Double>,
    label: String,
    bullishMeaning: String,
    bearishMeaning: String,
    bullishCrossMeaning: String,
    bearishCrossMeaning: String,
): TrendSignal? {
    if (fastSeries.size < 2 || slowSeries.size < 2) return null
    val latestDiff = fastSeries.last() - slowSeries.last()
    if (latestDiff == 0.0) return null
    val previousDiff = fastSeries[fastSeries.lastIndex - 1] - slowSeries[slowSeries.lastIndex - 1]
    val bullish = latestDiff > 0.0
    val freshCross = (bullish && previousDiff <= 0.0) || (!bullish && previousDiff >= 0.0)
    val bias = if (bullish) TrendSignalBias.Bull else TrendSignalBias.Bear
    val title = when {
        freshCross && bullish -> "Bull cross $label"
        freshCross -> "Bear cross $label"
        bullish -> "Bull $label"
        else -> "Bear $label"
    }
    val meaning = when {
        freshCross && bullish -> bullishCrossMeaning
        freshCross -> bearishCrossMeaning
        bullish -> bullishMeaning
        else -> bearishMeaning
    }
    return TrendSignal(
        title = title,
        meaning = meaning,
        bias = bias,
        freshCross = freshCross,
    )
}

private fun axisLabelsForCents(minValue: Float, maxValue: Float): ChartAxisLabels {
    val midValue = minValue + ((maxValue - minValue) / 2f)
    return ChartAxisLabels(
        top = compactMoney(maxValue.roundToLong()),
        middle = compactMoney(midValue.roundToLong()),
        bottom = compactMoney(minValue.roundToLong()),
    )
}

private fun paddedValueRange(min: Float, max: Float): Pair<Float, Float> {
    if (min == max) {
        val padding = maxOf(1f, kotlin.math.abs(min) * 0.05f)
        return (min - padding) to (max + padding)
    }
    val padding = ((max - min) * 0.05f).coerceAtLeast(1f)
    return (min - padding) to (max + padding)
}

private fun formatDateTick(epochSeconds: Long, range: ChartRange): String {
    val zonedDateTime = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    return when (range) {
        ChartRange.Day -> zonedDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        ChartRange.Week,
        ChartRange.Month,
        ChartRange.Year,
        -> zonedDateTime.format(DateTimeFormatter.ofPattern("MMM d"))

        ChartRange.FiveYears,
        ChartRange.TenYears,
        -> zonedDateTime.format(DateTimeFormatter.ofPattern("MMM yy"))
    }
}
