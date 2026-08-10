package com.discountscreener.core.regime

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary

/**
 * Synthetic market inputs shared by the pillar tests and the engine test.
 *
 * They are top-level and package-private rather than a fixture class, so a test using one reads the
 * same as it did when it owned a private copy — the point of pulling them out was that a second
 * caller appeared, not to introduce a scaffolding layer.
 *
 * Each is deliberately unambiguous: `bullish` means every input a pillar reads points the same way.
 * A fixture that hedged would leave a failing test unable to say *which* input the pillar misread.
 */

internal fun summary(bullish: Boolean) = ChartRangeSummary(
    range = ChartRange.Year,
    capturedAt = 0L,
    candleCount = 260,
    latestCloseCents = if (bullish) 11_000L else 9_000L,
    ema20Cents = 10_000L,
    ema50Cents = if (bullish) 10_000L else 10_500L,
    ema200Cents = if (bullish) 9_000L else 11_000L,
    histogramCents = if (bullish) 5L else -5L,
    latestWilderRsi = if (bullish) 58.0 else 38.0,
    pos52wPct = if (bullish) 75.0 else 15.0,
    adx = 25.0,
    plusDi = if (bullish) 30.0 else 15.0,
    minusDi = if (bullish) 15.0 else 30.0,
)

internal fun universe(count: Int, bullish: Boolean) =
    (0 until count).map { index -> SymbolChartView("SYM$index.BA", summary(bullish)) }

internal fun fearGreed(score: Double) = CnnFearGreed(score = score, rating = fngZone(score))

internal fun risingCloses() = (0 until 80).map { 400.0 + it }

internal fun spikingVix() = (0 until 60).map { 12.0 } + (0 until 6).map { 30.0 + it }

/**
 * The VIX terms are read as a *percentile of their own year*, so calm is a series whose latest bar
 * sits at the bottom of its own history — not a series of low numbers. A flat low series would put
 * its last bar at the top of its range and read as stress.
 */
internal fun decliningVix() = (0 until 60).map { 30.0 - (it * 0.3) }

internal fun rising() = (0 until 40).map { 100.0 + (it * 2.0) }

internal fun falling() = (0 until 40).map { 100.0 - (it * 1.0) }

internal fun flat() = (0 until 40).map { 100.0 }

/** A year of SPY dailies drifting up, long enough for every average and the sixty-day slope. */
internal fun spyCloses() = (0 until 260).map { 400.0 + (it * 0.5) }

/** Every series [MARKET_SERIES] asks for, so the fixture reads exactly what `:app` will fetch. */
internal fun fullBundle() = MarketDataBundle(
    spyCloses = spyCloses(),
    spySummary = summary(bullish = true).copy(latestCloseCents = 52_950L),
    closesBySymbol = MARKET_SERIES.associate { request ->
        request.symbol to when (request.symbol) {
            VIX_SYMBOL -> decliningVix()
            VIX3M_SYMBOL -> (0 until 60).map { 20.0 }
            else -> rising()
        }
    },
    cnnFearGreed = fearGreed(55.0),
)

internal fun fullUniverse() = (0 until 90).map { index ->
    SymbolDailyView(
        symbol = "SYM$index.BA",
        summary = summary(bullish = index % 3 != 0),
        closes = (0 until 80).map { bar -> 100.0 + (bar * 0.4) + ((bar + index) % 7) },
    )
}

/**
 * A market reading confident enough that [RegimeScoringPolicy.fromRegime] accepts it — the state
 * the composite tests need, and the one the app is in whenever the fourth dimension shows up.
 */
internal fun confidentRegime() = computeMarketRegime(fullBundle(), fullUniverse())
