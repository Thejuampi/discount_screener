package com.discountscreener.android.data.market

import com.discountscreener.android.data.remote.CnnFearGreedClient
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.regime.MARKET_SERIES
import com.discountscreener.core.regime.MarketDataBundle
import com.discountscreener.core.regime.MarketRegime
import com.discountscreener.core.regime.RegimeScoringPolicy
import com.discountscreener.core.regime.SymbolDailyView
import com.discountscreener.core.regime.computeMarketRegime
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The market reading for the fourth scoring dimension: fetches what `:core` cannot, then hands it
 * over as a [MarketDataBundle].
 *
 * **One thing here is persisted, and it used to be nothing.** The dashboard's own per-symbol charts
 * go through the chart cache, the merge and the raw-capture ledger because the user can scroll back
 * through them; the readings computed here exist only to answer "what is the market doing right
 * now", they are worthless a day later, and none of them is written.
 *
 * The daily *bars* are the exception, and the reason the earlier rule changed. The retrospective
 * needs dated prices and nothing else in the app has them: these bars are fetched at `1y`/`1d`,
 * used once, and were dropped on exit. They now go to a [DailyCandleSink] — written here, where
 * they are already in hand, rather than kept in the cache for a caller to collect later. That
 * distinction is the whole design: five hundred symbols of daily bars is several megabytes, and
 * retaining them for the life of the process to save a layering hop would be the worse defect.
 *
 * The original rule's last clause still holds. The sink stores them under a key that is not a
 * `ChartRange` name, so the second per-symbol series stays off a published contract.
 *
 * **[cachedRegime] never blocks and never fetches.** The dashboard renders from it — including on
 * a cold start, where it is null and the market dimension reports itself unavailable. Refreshing is
 * something a caller schedules; it is not something rendering waits for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class MarketDataRepository(
    private val yahooClient: YahooFinanceClient,
    private val fearGreedClient: CnnFearGreedClient,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    /**
     * Null keeps the old behaviour exactly: fetch, score, drop. The retrospective is the only
     * caller that needs the bars kept, so a repository built without one is not silently storing.
     */
    private val dailyCandleSink: DailyCandleSink? = null,
) {
    private val mutex = Mutex()
    private var cached: MarketRegime? = null
    private var cachedDailySummaries: Map<String, ChartRangeSummary> = emptyMap()
    private var lastFailureEpochSeconds: Long? = null
    private var refreshing = false

    /** The last usable reading, or null before one lands. Never fetches. */
    open suspend fun cachedRegime(): MarketRegime? = mutex.withLock { cached }

    /**
     * The daily-bar summaries behind the last usable reading, keyed by symbol.
     *
     * These are a by-product worth keeping rather than a second fetch: the universe is already
     * pulled at `1y`/`1d` for breadth and quality, and per-symbol regime fit needs exactly the same
     * bars. Discarding them and re-reading the dashboard's weekly summaries would score the fit on
     * a different interval than the pillars it is being fitted to.
     *
     * Replaced wholesale with each usable reading, never merged: a symbol that dropped out of the
     * universe must not keep scoring against bars from an older market.
     */
    open suspend fun cachedDailySummaries(): Map<String, ChartRangeSummary> =
        mutex.withLock { cachedDailySummaries }

    /**
     * Recompute when the cached reading has aged out, otherwise hand back what is held.
     *
     * Three separate things can say "not now", and they guard disjoint states so that each is the
     * only thing standing between a call and the network:
     *
     *  - **Freshness** applies when a usable reading is held. Rust's `RegimeCache::is_fresh`.
     *  - **Failure backoff** applies when the last attempt produced nothing usable. Rust simply
     *    retries — a desktop can afford that; a phone spending a hundred failing requests on every
     *    pull-to-refresh while offline cannot.
     *  - **Single flight** applies while a refresh is already running, so two callers arriving
     *    together produce one round of requests rather than two. Rust's `try_begin_refresh`.
     *
     * Only a reading a scoring policy accepts is cached, mirroring `is_usable_regime`. A degraded
     * reading is not a weaker steer to be kept warm; it is the absence of one, and callers must see
     * null so the dimension reports itself unavailable instead of quietly scoring against noise.
     */
    open suspend fun refreshIfStale(symbols: List<String>): MarketRegime? {
        val now = nowEpochSeconds()
        val shouldRefresh = mutex.withLock {
            val fresh = cached?.let { now - it.asOfEpoch < FRESH_FOR_SECONDS } ?: false
            val backingOff = lastFailureEpochSeconds?.let { now - it < RETRY_AFTER_FAILURE_SECONDS } ?: false
            val go = !fresh && !backingOff && !refreshing
            if (go) refreshing = true
            go
        }
        if (!shouldRefresh) return cachedRegime()

        try {
            val fetched = fetchUniverse(symbols)
            val regime = computeMarketRegime(
                bundle = fetchBundle(now),
                universe = fetched.views,
                previousExposurePct = cachedRegime()?.suggestedExposurePct,
            )
            val usable = RegimeScoringPolicy.fromRegime(regime) != null
            mutex.withLock {
                if (usable) {
                    cached = regime
                    cachedDailySummaries = fetched.views.mapNotNull { view ->
                        view.summary?.let { view.symbol to it }
                    }.toMap()
                    lastFailureEpochSeconds = null
                } else {
                    lastFailureEpochSeconds = now
                }
            }
            // Only a usable reading is kept, and only its bars are stored. A round that failed
            // hard enough to be unusable is a round whose bars are as likely to be partial.
            if (usable) {
                dailyCandleSink?.persistBacktestCandles(fetched.candlesBySymbol, now)
            }
        } finally {
            mutex.withLock { refreshing = false }
        }
        return cachedRegime()
    }

    private suspend fun fetchBundle(now: Long): MarketDataBundle {
        val spyCandles = dailyCandlesOrEmpty("SPY", YEAR_RANGE)
        val series = fetchConcurrently(MARKET_SERIES) { request ->
            request.symbol to closesOf(dailyCandlesOrEmpty(request.symbol, request.yahooRange))
        }
        return MarketDataBundle(
            spyCloses = closesOf(spyCandles),
            spySummary = spyCandles.takeIf { it.isNotEmpty() }
                ?.let { ChartAnalysis.buildSummary(ChartRange.Year, it, now) },
            closesBySymbol = series.filter { it.second.isNotEmpty() }.toMap(),
            cnnFearGreed = fearGreedClient.fetch(),
            asOfEpochSeconds = now,
        )
    }

    /**
     * The tracked universe at a *daily* interval, which is the interval Windows scores on and not
     * the one `ChartRange.Year` fetches — that is `1y`/`1wk`, fifty-two weekly bars. Breadth over
     * weekly bars would compare a 200-period average against 52 of them, so this is a second
     * request per symbol rather than a reuse of the summary the dashboard already holds.
     */
    private suspend fun fetchUniverse(symbols: List<String>): UniverseFetch {
        val fetched = fetchConcurrently(symbols) { symbol ->
            val candles = dailyCandlesOrEmpty(symbol, YEAR_RANGE)
            SymbolDailyView(
                symbol = symbol,
                summary = candles.takeIf { it.isNotEmpty() }
                    ?.let { ChartAnalysis.buildSummary(ChartRange.Year, it, nowEpochSeconds()) },
                closes = closesOf(candles),
            ) to candles
        }
        return UniverseFetch(
            views = fetched.map { it.first },
            candlesBySymbol = fetched.filter { it.second.isNotEmpty() }
                .associate { it.first.symbol to it.second },
        )
    }

    /**
     * The bars ride alongside the views instead of inside them, because [SymbolDailyView] is a
     * `:core` regime type and the bars are not something the regime engine has any use for.
     */
    private class UniverseFetch(
        val views: List<SymbolDailyView>,
        val candlesBySymbol: Map<String, List<HistoricalCandle>>,
    )

    /** One symbol failing costs its pillar a sample; it must not cost the whole reading. */
    private suspend fun dailyCandlesOrEmpty(symbol: String, rangeToken: String): List<HistoricalCandle> =
        try {
            yahooClient.fetchCandles(symbol, rangeToken, DAILY_INTERVAL)
        } catch (error: IOException) {
            emptyList()
        }

    private suspend fun <T, R> fetchConcurrently(items: List<T>, fetch: suspend (T) -> R): List<R> =
        coroutineScope {
            items.asFlow()
                .flatMapMerge(concurrency = MARKET_FETCH_CONCURRENCY) { item -> flow { emit(fetch(item)) } }
                .toList()
        }

    /** Rust's `closes_from_candles`: prices, with the non-trading bars dropped. */
    private fun closesOf(candles: List<HistoricalCandle>): List<Double> =
        candles.filter { it.closeCents > 0L }.map { it.closeCents.toDouble() / 100.0 }

    private companion object {
        /** `REGIME_TTL_SECS` in `regime/mod.rs`. */
        const val FRESH_FOR_SECONDS = 150L

        /**
         * How long a failed attempt suppresses the next one. Longer than [FRESH_FOR_SECONDS] on
         * purpose: a market that is unreachable now is usually unreachable a minute from now, and
         * each attempt costs a round of requests for every tracked symbol.
         */
        const val RETRY_AFTER_FAILURE_SECONDS = 300L

        const val YEAR_RANGE = "1y"
        const val DAILY_INTERVAL = "1d"

        /** Matches the dashboard's own refresh concurrency, so the two do not gang up on Yahoo. */
        const val MARKET_FETCH_CONCURRENCY = 4
    }
}
