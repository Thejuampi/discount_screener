package com.discountscreener.android.ui.dashboard

import com.discountscreener.core.model.HistoricalCandle
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OhlcChartRenderTest {
    @Test
    fun volume_sized_ohlc_graph_paints_the_busy_candle_at_the_expected_width() {
        var candles = renderFixtureCandles()
        var model = buildPriceChartModel(candles)!!
        var graph = renderOhlcChartPixels(
            candles = candles,
            model = model,
            width = GraphWidth,
            height = GraphHeight,
            volumeSized = true,
        )
        var busy = layoutOhlcCandles(
            candles = candles,
            model = model,
            width = GraphWidth.toFloat(),
            height = GraphHeight.toFloat(),
            volumeSized = true,
        )[1]

        assertEquals(
            rasterSpan(busy.bodyLeft, busy.bodyWidth),
            measurePaintedBodyWidth(
                graph = graph,
                centerX = busy.centerX.roundToInt(),
                rowY = (busy.bodyTop + (busy.bodyHeight / 2f)).roundToInt(),
                colorArgb = busy.colorArgb,
            ),
        )
    }

    @Test
    fun volume_sized_ohlc_graph_paints_the_busy_candle_wider_than_the_quiet_candle() {
        var candles = renderFixtureCandles()
        var model = buildPriceChartModel(candles)!!
        var graph = renderOhlcChartPixels(
            candles = candles,
            model = model,
            width = GraphWidth,
            height = GraphHeight,
            volumeSized = true,
        )
        var layout = layoutOhlcCandles(
            candles = candles,
            model = model,
            width = GraphWidth.toFloat(),
            height = GraphHeight.toFloat(),
            volumeSized = true,
        )
        var quiet = layout[0]
        var busy = layout[1]
        var quietWidth = measurePaintedBodyWidth(
            graph = graph,
            centerX = quiet.centerX.roundToInt(),
            rowY = (quiet.bodyTop + (quiet.bodyHeight / 2f)).roundToInt(),
            colorArgb = quiet.colorArgb,
        )
        var busyWidth = measurePaintedBodyWidth(
            graph = graph,
            centerX = busy.centerX.roundToInt(),
            rowY = (busy.bodyTop + (busy.bodyHeight / 2f)).roundToInt(),
            colorArgb = busy.colorArgb,
        )

        assertTrue(busyWidth > quietWidth)
    }

    @Test
    fun equal_width_ohlc_graph_paints_both_candles_the_same_width() {
        var candles = renderFixtureCandles()
        var model = buildPriceChartModel(candles)!!
        var graph = renderOhlcChartPixels(
            candles = candles,
            model = model,
            width = GraphWidth,
            height = GraphHeight,
            volumeSized = false,
        )
        var layout = layoutOhlcCandles(
            candles = candles,
            model = model,
            width = GraphWidth.toFloat(),
            height = GraphHeight.toFloat(),
            volumeSized = false,
        )
        var quiet = layout[0]
        var busy = layout[1]
        var quietWidth = measurePaintedBodyWidth(
            graph = graph,
            centerX = quiet.centerX.roundToInt(),
            rowY = (quiet.bodyTop + (quiet.bodyHeight / 2f)).roundToInt(),
            colorArgb = quiet.colorArgb,
        )
        var busyWidth = measurePaintedBodyWidth(
            graph = graph,
            centerX = busy.centerX.roundToInt(),
            rowY = (busy.bodyTop + (busy.bodyHeight / 2f)).roundToInt(),
            colorArgb = busy.colorArgb,
        )

        assertEquals(quietWidth, busyWidth)
    }

    private fun renderFixtureCandles(): List<HistoricalCandle> = listOf(
        HistoricalCandle(
            epochSeconds = 1_704_067_200L,
            openCents = 10_000L,
            highCents = 11_200L,
            lowCents = 9_800L,
            closeCents = 11_000L,
            volume = 0L,
        ),
        HistoricalCandle(
            epochSeconds = 1_704_153_600L,
            openCents = 12_000L,
            highCents = 14_200L,
            lowCents = 11_800L,
            closeCents = 14_000L,
            volume = 100L,
        ),
    )

    private companion object {
        const val GraphWidth = 200
        const val GraphHeight = 100
    }
}
