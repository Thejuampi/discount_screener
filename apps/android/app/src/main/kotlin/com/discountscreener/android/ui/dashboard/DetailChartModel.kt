package com.discountscreener.android.ui.dashboard

import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.ReplayWindow
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.ProjectedChartData
import com.discountscreener.core.model.ProjectedChartStatus
import com.discountscreener.core.model.ProjectedTechnicalSignal
import com.discountscreener.core.model.ProjectedTechnicalSignalBias
import com.discountscreener.core.model.ProjectedTechnicalSignalKind
import com.discountscreener.core.model.ProjectedVolumeProfileAnalysis
import com.discountscreener.core.model.ProjectedVolumeProfileBin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val SnapshotVolumeProfileBinCount = 18

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
    val latestEma20Cents: Long?,
    val latestEma50Cents: Long?,
    val latestEma200Cents: Long?,
) {
    val span: Float = (maxValue - minValue).coerceAtLeast(1f)
}

internal data class VolumeChartModel(
    val maxVolume: Long,
    val axisLabels: ChartAxisLabels,
)

internal data class VolumeProfileModel(
    val bins: List<ProjectedVolumeProfileBin>,
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

internal data class SnapshotChartModels(
    val replayWindow: ReplayWindow,
    val visibleCandles: List<HistoricalCandle>,
    val priceChartModel: PriceChartModel?,
    val volumeChartModel: VolumeChartModel?,
    val macdChartModel: MacdChartModel?,
    val volumeProfileModel: VolumeProfileModel?,
    val trendSignals: List<TrendSignal>,
    val dateTicks: List<ChartDateTick>,
)

internal fun buildSnapshotChartModels(
    chartRange: ChartRange,
    candles: List<HistoricalCandle>,
    replayOffset: Int,
    projectedChart: ProjectedChartData? = null,
): SnapshotChartModels {
    var projectedCandidate = projectedChart?.takeIf { chart ->
        chart.analysis.status != ProjectedChartStatus.Unavailable ||
            chart.analysis.replayWindow.totalCandles > 0 ||
            chart.candles.isNotEmpty()
    }
    if (projectedCandidate != null) {
        return buildProjectedSnapshotChartModels(projectedCandidate, replayOffset)
    }
    var replayWindow = ChartAnalysis.buildReplayWindow(candles, replayOffset)
    var visibleCandles = replayWindow.visibleCandles
    var priceChartModel = buildPriceChartModel(visibleCandles)
    var volumeChartModel = buildVolumeChartModel(visibleCandles)
    var macdChartModel = buildMacdChartModel(visibleCandles)
    var volumeProfileModel = priceChartModel?.let { priceModel ->
        buildVolumeProfileModel(
            candles = visibleCandles,
            minPriceCents = priceModel.minValue.roundToLong(),
            maxPriceCents = priceModel.maxValue.roundToLong(),
            binCount = SnapshotVolumeProfileBinCount,
        )
    }
    var trendSignals = trendSignals(priceChartModel, macdChartModel)
    return SnapshotChartModels(
        replayWindow = replayWindow,
        visibleCandles = visibleCandles,
        priceChartModel = priceChartModel,
        volumeChartModel = volumeChartModel,
        macdChartModel = macdChartModel,
        volumeProfileModel = volumeProfileModel,
        trendSignals = trendSignals,
        dateTicks = buildDateAxisTicks(visibleCandles, chartRange),
    )
}

private fun buildProjectedSnapshotChartModels(
    chart: ProjectedChartData,
    replayOffset: Int,
): SnapshotChartModels {
    var replayAdjustedChart = projectedChartForReplayOffset(chart, replayOffset)
    var projectedReplayWindow = replayAdjustedChart.analysis.replayWindow
    var replayWindow = ReplayWindow(
        visibleCandles = projectedReplayWindow.visibleCandles,
        totalCandles = projectedReplayWindow.totalCandles,
        replayOffset = projectedReplayWindow.replayOffset,
    )
    var priceChartModel = buildPriceChartModel(replayAdjustedChart)
    var volumeChartModel = buildVolumeChartModel(replayAdjustedChart)
    var macdChartModel = buildMacdChartModel(replayAdjustedChart)
    var volumeProfileModel = buildVolumeProfileModel(replayAdjustedChart.analysis.volumeProfile)
    var trendSignals = trendSignals(priceChartModel, macdChartModel)
    return SnapshotChartModels(
        replayWindow = replayWindow,
        visibleCandles = replayWindow.visibleCandles,
        priceChartModel = priceChartModel,
        volumeChartModel = volumeChartModel,
        macdChartModel = macdChartModel,
        volumeProfileModel = volumeProfileModel,
        trendSignals = trendSignals,
        dateTicks = buildDateAxisTicks(replayWindow.visibleCandles, replayAdjustedChart.range),
    )
}

private fun projectedChartForReplayOffset(
    chart: ProjectedChartData,
    replayOffset: Int,
): ProjectedChartData {
    if (chart.analysis.replayWindow.replayOffset == replayOffset || chart.candles.isEmpty()) {
        return chart
    }
    return ChartAnalysis.buildProjectedChartData(
        range = chart.range,
        candles = chart.candles,
        capturedAtEpochSeconds = chart.summary?.capturedAt ?: chart.candles.last().epochSeconds,
        replayOffset = replayOffset,
        volumeProfileBinCount = chart.analysis.volumeProfile?.bins?.size?.takeIf { binCount -> binCount > 0 }
            ?: SnapshotVolumeProfileBinCount,
        summary = chart.summary,
    )
}

private fun trendSignals(
    priceChartModel: PriceChartModel?,
    macdChartModel: MacdChartModel?,
): List<TrendSignal> = buildList {
    addAll(priceChartModel?.trendSignals.orEmpty())
    macdChartModel?.trendSignal?.let(::add)
}

internal fun buildPriceChartModel(candles: List<HistoricalCandle>): PriceChartModel? {
    if (candles.isEmpty()) return null
    var chart = projectedChartDataForVisibleCandles(candles)
    return buildPriceChartModel(chart)
}

internal fun buildPriceChartModel(chart: ProjectedChartData): PriceChartModel? {
    var price = chart.analysis.price ?: return null
    return PriceChartModel(
        minValue = price.domain.minValue,
        maxValue = price.domain.maxValue,
        ema20 = price.ema20,
        ema50 = price.ema50,
        ema200 = price.ema200,
        axisLabels = axisLabelsForCents(price.domain.minValue, price.domain.maxValue),
        trendSignals = chart.analysis.technicalSignals.filter { signal ->
            signal.kind == ProjectedTechnicalSignalKind.Ema20Ema50 || signal.kind == ProjectedTechnicalSignalKind.Ema50Ema200
        }.map(::adaptTrendSignal),
        latestEma20Cents = price.latestEma20Cents,
        latestEma50Cents = price.latestEma50Cents,
        latestEma200Cents = price.latestEma200Cents,
    )
}

internal fun buildVolumeChartModel(candles: List<HistoricalCandle>): VolumeChartModel? {
    if (candles.isEmpty()) return null
    var chart = projectedChartDataForVisibleCandles(candles)
    return buildVolumeChartModel(chart)
}

internal fun buildVolumeChartModel(chart: ProjectedChartData): VolumeChartModel? {
    var volume = chart.analysis.volume ?: return null
    return VolumeChartModel(
        maxVolume = volume.maxVolume,
        axisLabels = ChartAxisLabels(
            top = compactFinancialNumber(volume.maxVolume),
            middle = compactFinancialNumber(volume.maxVolume / 2),
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
    var profile = ChartAnalysis.buildVolumeProfileAnalysis(
        candles = candles,
        minPriceCents = minPriceCents,
        maxPriceCents = maxPriceCents,
        binCount = binCount,
    )
    return VolumeProfileModel(
        bins = profile.bins,
        maxBinVolume = profile.maxBinVolume,
    )
}

internal fun buildVolumeProfileModel(profile: ProjectedVolumeProfileAnalysis?): VolumeProfileModel? {
    profile ?: return null
    if (profile.bins.isEmpty()) return null
    return VolumeProfileModel(
        bins = profile.bins,
        maxBinVolume = profile.maxBinVolume,
    )
}

internal fun replayStatusText(
    visibleCount: Int,
    totalCount: Int,
    replayOffset: Int,
    maxVolume: Long,
): String {
    var replayLabel = if (replayOffset > 0) "Replay -$replayOffset from live" else "Live"
    return "Showing $visibleCount / $totalCount candles  |  $replayLabel  |  Volume max ${compactFinancialNumber(maxVolume)}"
}

internal fun buildMacdChartModel(candles: List<HistoricalCandle>): MacdChartModel? {
    if (candles.isEmpty()) return null
    var chart = projectedChartDataForVisibleCandles(candles)
    return buildMacdChartModel(chart)
}

internal fun buildMacdChartModel(chart: ProjectedChartData): MacdChartModel? {
    var macd = chart.analysis.macd ?: return null
    var scale = macdChartScale(macd.macdLine, macd.signalLine, macd.histogram)
    return MacdChartModel(
        macdLine = macd.macdLine,
        signalLine = macd.signalLine,
        histogram = macd.histogram,
        scale = scale,
        axisLabels = axisLabelsForCents(scale.minValue, scale.maxValue),
        trendSignal = chart.analysis.technicalSignals
            .firstOrNull { signal -> signal.kind == ProjectedTechnicalSignalKind.MacdSignal }
            ?.let(::adaptTrendSignal),
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
    var labelCount = maxLabels.coerceIn(2, candles.size)
    var lastIndex = candles.lastIndex
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

internal fun adaptTrendSignal(signal: ProjectedTechnicalSignal): TrendSignal = TrendSignal(
    title = signal.title,
    meaning = signal.meaning,
    bias = when (signal.bias) {
        ProjectedTechnicalSignalBias.Bull -> TrendSignalBias.Bull
        ProjectedTechnicalSignalBias.Bear -> TrendSignalBias.Bear
    },
    freshCross = signal.freshCross,
)

private fun axisLabelsForCents(minValue: Float, maxValue: Float): ChartAxisLabels {
    var midValue = minValue + ((maxValue - minValue) / 2f)
    return ChartAxisLabels(
        top = compactMoney(maxValue.roundToLong()),
        middle = compactMoney(midValue.roundToLong()),
        bottom = compactMoney(minValue.roundToLong()),
    )
}

private fun projectedChartDataForVisibleCandles(candles: List<HistoricalCandle>): ProjectedChartData =
    ChartAnalysis.buildProjectedChartData(
        range = ChartRange.Month,
        candles = candles,
        capturedAtEpochSeconds = candles.lastOrNull()?.epochSeconds ?: 0L,
        replayOffset = 0,
        volumeProfileBinCount = 0,
    )

private fun formatDateTick(epochSeconds: Long, range: ChartRange): String {
    var zonedDateTime = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
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
