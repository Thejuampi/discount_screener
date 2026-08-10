package com.discountscreener.core.regime

import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.HistoricalCandle

/**
 * Assembly of the six pillars into one market reading, ported from
 * `regime/mod.rs::compute_market_regime_parts`.
 *
 * **The one structural departure from Rust: this function does no I/O.** The Rust version fetches
 * from Yahoo and CNN inline, mid-computation, and degrades by pushing a warning when a request
 * fails. `:core` has no HTTP client and must not grow one — so `:app` fetches, hands over a
 * [MarketDataBundle], and this stays a pure function of its inputs. Every "unavailable" branch Rust
 * reaches through a failed request, this reaches through an absent or short series, and the
 * warnings it emits are the same strings.
 *
 * That also makes the whole reading testable from a fixture, which is what the pillar and contract
 * tests in this package rely on.
 *
 * **What is deliberately not ported:** `interpret.rs` and `narrative.rs` — pillar prose, signal
 * hints, the multi-sentence reading and the action bullets, ~1000 lines of it in two languages.
 * Nothing on Android renders any of it: the detail view shows the market bucket's score, its ranked
 * causes and the base/context/final split, none of which come from there. [RegimePillar.tone] and
 * [RegimePillar.radarRadius] *are* ported, because they are classifications rather than copy and a
 * wrong tone is a wrong claim. [RegimePillar.interpretation] is left empty rather than filled with
 * something plausible.
 */

/** One Yahoo series the market read consumes, with the range it must be fetched over. */
data class MarketSeriesRequest(val symbol: String, val yahooRange: String)

internal const val VIX_SYMBOL = "^VIX"
internal const val VIX3M_SYMBOL = "^VIX3M"

private val CROSS_ASSET_SYMBOLS = listOf(
    "HYG", "IEF", "TLT", "JNK", "IWM", "SPY", "XLK", "XLY", "XLP", "XLU", "XLV",
)

/**
 * Exactly the series [computeMarketRegime] reads, so `:app` cannot fetch a set that drifts from
 * what the engine consumes. It is *derived* from the symbols the engine looks up rather than
 * written out beside them: a second list would be a hand-copy, and adding a cross-asset symbol to
 * one and not the other leaves the pillar permanently reading a series short with nothing failing.
 *
 * The ranges are not interchangeable. `^VIX` is read as a *percentile of its own year*, so a
 * three-month history would silently answer a different question under the same field name; the
 * cross-asset pillar measures three-month relative returns and Rust fetches exactly that.
 *
 * SPY is absent because it arrives as [MarketDataBundle.spyCloses] — the trend and volatility
 * pillars need a year of it, and the cross-asset pillar reuses those closes rather than fetching
 * the symbol twice.
 */
val MARKET_SERIES: List<MarketSeriesRequest> = buildList {
    add(MarketSeriesRequest(VIX_SYMBOL, "1y"))
    add(MarketSeriesRequest(VIX3M_SYMBOL, "3mo"))
    CROSS_ASSET_SYMBOLS.filterNot { it == "SPY" }.forEach { add(MarketSeriesRequest(it, "3mo")) }
}

/** The market-wide series and sentiment `:app` fetches; everything here may be absent. */
data class MarketDataBundle(
    /** SPY daily closes over a year, as prices. Rust's `closes_from_candles` unit. */
    val spyCloses: List<Double> = emptyList(),
    /** SPY's daily chart summary, when `:app` already has one. Its averages are re-derived. */
    val spySummary: ChartRangeSummary? = null,
    /** Closes keyed by the symbols of [MARKET_SERIES]. A missing key degrades a pillar, never fails. */
    val closesBySymbol: Map<String, List<Double>> = emptyMap(),
    val cnnFearGreed: CnnFearGreed? = null,
    val asOfEpochSeconds: Long = 0L,
)

/**
 * One tracked symbol as the market read sees it: the daily summary breadth and quality count, and
 * the daily closes the correlation sample draws from.
 */
data class SymbolDailyView(
    val symbol: String,
    val summary: ChartRangeSummary? = null,
    val closes: List<Double> = emptyList(),
)

/**
 * @param previousExposurePct the last published exposure, for the hysteresis that stops the
 *   suggested exposure jittering between refreshes. Rust reads it from its regime cache; on Android
 *   it lives in `:app`, so it is passed in.
 */
fun computeMarketRegime(
    bundle: MarketDataBundle,
    universe: List<SymbolDailyView>,
    previousExposurePct: Int? = null,
): MarketRegime {
    val warnings = ArrayList<String>()

    val breadthSnapshot = computeBreadth(universe.mapNotNull { view -> view.summary?.let { SymbolChartView(view.symbol, it) } })
    val breadth = breadthPillar(breadthSnapshot)
    if (breadthSnapshot.sample < BREADTH_FULL_UNIVERSE) {
        warnings.add("breadth sample n=${breadthSnapshot.sample} (partial universe, not full S&P)")
    }

    val spySummary = spyReading(bundle)
    if (bundle.spySummary == null && spySummary != null) {
        warnings.add("SPY summary synthesized from closes (screener chart missing)")
    }

    val vixCloses = bundle.closesBySymbol[VIX_SYMBOL].orEmpty()
    val volSnapshot = volSnapshot(vixCloses, bundle.closesBySymbol[VIX3M_SYMBOL].orEmpty(), bundle.spyCloses)
    val volatility = volPillar(volSnapshot)
    if (volSnapshot.vix == null) {
        warnings.add("VIX unavailable")
    }

    val trend = trendPillar(spySummary, bundle.spyCloses)
    if (trend.confidenceBps == 0) {
        warnings.add("SPY trend unavailable")
    }

    if (bundle.cnnFearGreed == null) {
        warnings.add("CNN Fear & Greed unavailable — using internal proxies / alt.me")
    }
    val sentiment = sentimentPillar(bundle.cnnFearGreed, breadthSnapshot)

    val crossCloses = crossAssetCloses(bundle)
    val cross = crossAssetPillar(crossCloses)
    val crossSnapshot = crossAssetSnapshot(crossCloses)
    if (cross.confidenceBps < CROSS_ASSET_SPARSE_BPS) {
        warnings.add("cross-asset data sparse")
    }

    val avgCorrMilli = avgPairwiseCorrMilli(correlationSample(universe), CORRELATION_LOOKBACK)
    val spyAboveMa200 = spyAboveMa200(spySummary, bundle.spyCloses)
    val quality = qualityPillar(breadthSnapshot, spyAboveMa200, avgCorrMilli, volSnapshot.stressScore, crossSnapshot)

    val drawdown = spyDrawdownFromAthPct(bundle.spyCloses)
    val composite = compose(
        CompositeInput(
            trend = trend,
            breadth = breadth,
            volatility = volatility,
            sentiment = sentiment,
            crossAsset = cross,
            quality = quality,
            spyDrawdownFromAthPct = drawdown,
            breadthMa200Pct = breadthSnapshot.aboveMa200Pct,
            vixTermRatio = volSnapshot.vixTermRatio,
            cnnFng = bundle.cnnFearGreed?.score,
            prevExposurePct = previousExposurePct,
        ),
    )

    val pillars = listOf(
        makePillar("trend", "Trend", trend, composite.weightTrendBps),
        makePillar("breadth", "Breadth", breadth, composite.weightBreadthBps),
        makePillar("volatility", "Volatility / stress", volatility, composite.weightVolBps),
        // Sentiment is read as contrarian and sits outside the environment weights, so it carries none.
        makePillar("sentiment", "Sentiment (contrarian)", sentiment, 0),
        makePillar("cross_asset", "Cross-asset", cross, composite.weightCrossBps),
        makePillar("quality", "Quality / fragility", quality, composite.weightQualityBps),
    )

    if (composite.globalConfidenceBps < DEGRADED_CONFIDENCE_BPS) {
        warnings.add("global confidence low — degraded regime reading")
    }

    return MarketRegime(
        primaryRegime = composite.primaryRegime,
        environmentBand = composite.environmentBand,
        actionStance = composite.actionStance,
        suggestedExposurePct = composite.suggestedExposurePct,
        cashBufferPct = composite.cashBufferPct,
        newRiskMultiplierBps = composite.newRiskMultiplierBps,
        addBias = composite.addBias,
        preferQuality = composite.preferQuality,
        globalConfidenceBps = composite.globalConfidenceBps,
        environmentScore = composite.environmentScore,
        sentimentScore = composite.sentimentScore,
        qualityScore = composite.qualityScore,
        pillars = pillars,
        vix = volSnapshot.vix,
        vixPercentile1y = volSnapshot.vixPercentile1y,
        vixTermRatio = volSnapshot.vixTermRatio,
        vixState = volSnapshot.vixState,
        // Rust rounds then casts to `u32`, which saturates at zero rather than going negative.
        cnnFearGreed = bundle.cnnFearGreed?.let { truncateToU32(roundHalfAwayFromZero(it.score).toDouble()) },
        cnnFearGreedLabel = bundle.cnnFearGreed?.rating,
        cnnFearGreedPrevClose = bundle.cnnFearGreed?.previousClose,
        breadthAboveMa200Pct = breadthSnapshot.aboveMa200Pct,
        breadthAboveMa50Pct = breadthSnapshot.aboveMa50Pct,
        breadthSample = breadthSnapshot.sample,
        spyAboveMa200 = spyAboveMa200,
        spyPriceCents = spySummary?.latestCloseCents,
        spyMa200Cents = spySummary?.ema200Cents,
        spyDrawdownFromAthPct = drawdown,
        creditScore = crossSnapshot.creditScore,
        leadershipScore = crossSnapshot.leadershipScore,
        avgCorrMilli = avgCorrMilli,
        notes = notesFrom(pillars),
        warnings = warnings,
        asOfEpoch = bundle.asOfEpochSeconds,
    )
}

/**
 * SPY's summary with its moving averages re-derived from closes through [regimeEmaCents].
 *
 * Rust builds this summary from daily candles with an average that refuses below its period and
 * seeds from a simple mean; the app's shared chart code does neither, and the gap is large enough
 * on the 200-period average to flip SPY's own "above the 200-day" verdict. That verdict is a single
 * boolean feeding two pillars, not an average over eighty names, so it is worth deriving correctly
 * here even though the per-symbol summaries keep the app's own numbers.
 *
 * Everything the averages do not cover — ADX and the directional pair, which need highs and lows —
 * is kept from the app's summary, or absent when the summary itself is, exactly as Rust's
 * `summary_from_closes` fallback leaves it.
 */
private fun spyReading(bundle: MarketDataBundle): ChartRangeSummary? {
    val base = bundle.spySummary ?: synthesizedSpySummary(bundle) ?: return null
    if (bundle.spyCloses.isEmpty()) return base
    return base.copy(
        ema20Cents = regimeEmaCents(bundle.spyCloses, 20),
        ema50Cents = regimeEmaCents(bundle.spyCloses, 50),
        ema200Cents = regimeEmaCents(bundle.spyCloses, 200),
    )
}

/** Rust's `summary_from_closes`: flat candles from the closes, refusing under twenty of them. */
private fun synthesizedSpySummary(bundle: MarketDataBundle): ChartRangeSummary? {
    val closes = bundle.spyCloses.filter { it > 0.0 }
    if (closes.size < MIN_CLOSES_FOR_SUMMARY) return null
    val candles = closes.mapIndexed { index, close ->
        val cents = roundHalfAwayFromZeroLong(close * 100.0)
        HistoricalCandle(
            epochSeconds = index.toLong() * SECONDS_PER_DAY,
            openCents = cents,
            highCents = cents,
            lowCents = cents,
            closeCents = cents,
            volume = 0L,
        )
    }
    return ChartAnalysis.buildSummary(ChartRange.Year, candles, bundle.asOfEpochSeconds)
}

/** SPY's own closes stand in for the SPY series when the cross-asset fetch skipped it, as Rust does. */
private fun crossAssetCloses(bundle: MarketDataBundle): Map<String, List<Double>> {
    val closes = LinkedHashMap<String, List<Double>>()
    for (symbol in CROSS_ASSET_SYMBOLS) {
        val series = if (symbol == "SPY" && bundle.spyCloses.isNotEmpty()) {
            bundle.spyCloses
        } else {
            bundle.closesBySymbol[symbol].orEmpty()
        }
        if (series.isNotEmpty()) closes[symbol] = series
    }
    return closes
}

/**
 * Up to twelve equity series for the pairwise-correlation sample.
 *
 * Rust draws these by iterating a `HashMap` and breaking at twelve, so *which* twelve it gets is
 * whatever the hasher's per-process seed decides — its own correlation reading, and therefore its
 * quality pillar, is not reproducible across restarts. This side walks the caller's order instead,
 * so the same universe always yields the same sample. Exact agreement with Windows on this field is
 * not achievable while that holds, and pinning it here is the half that can be pinned.
 */
private fun correlationSample(universe: List<SymbolDailyView>): List<List<Double>> =
    universe.asSequence()
        .filterNot { AssetClassification.isCrypto(it.symbol) || AssetClassification.isEtf(it.symbol) }
        .map { it.closes }
        .filter { it.size >= CORRELATION_LOOKBACK }
        .take(CORRELATION_SAMPLE_MAX)
        .toList()

/** The summary's own average when it has one, else a simple mean of the last two hundred closes. */
private fun spyAboveMa200(summary: ChartRangeSummary?, closes: List<Double>): Boolean? {
    val price = summary?.latestCloseCents
    val average = summary?.ema200Cents
    if (price != null && average != null && average > 0L) return price > average
    if (closes.size < MA200_WINDOW) return null
    val window = closes.takeLast(MA200_WINDOW)
    return closes.last() > window.sum() / MA200_WINDOW.toDouble()
}

/** The first two signals of each pillar that carry a detail, in pillar order. */
internal fun notesFrom(pillars: List<RegimePillar>): List<String> =
    pillars.flatMap { pillar ->
        pillar.signals.take(NOTES_PER_PILLAR).mapNotNull { signal ->
            signal.detail?.let { "${signal.label}: $it" }
        }
    }

private fun makePillar(id: String, name: String, result: PillarResult, weightBps: Int) = RegimePillar(
    id = id,
    name = name,
    score = result.score,
    confidenceBps = result.confidenceBps,
    weightUsedBps = weightBps,
    signals = result.signals,
    stale = result.stale,
    interpretation = "",
    tone = pillarTone(id, result.score),
    radarRadius = radarRadius(id, result.score),
)

/**
 * `interpret.rs::radar_radius`. Stress is hostile, so the volatility pillar is the one axis where a
 * high score must plot *near the centre* — every other pillar reaches the edge when it reads well.
 */
internal fun radarRadius(pillarId: String, score: Int): Int {
    val bounded = clampI32(score, -100, 100).toDouble()
    val radius = if (pillarId == "volatility") (100.0 - bounded) / 2.0 else (bounded + 100.0) / 2.0
    return clampI32(roundHalfAwayFromZero(radius), 0, 100)
}

/**
 * The tone `interpret.rs::interpret_pillar` picks, without its prose.
 *
 * Three pillars do not simply run bearish-to-bullish. Volatility is inverted, because its score is
 * stress. Sentiment is contrarian, so fear reads as `opportunity` and greed as `caution` — never
 * bullish or bearish, which is the distinction the whole pillar exists to make. And breadth, cross
 * asset and quality all say `caution` one band before they say `bearish`, where trend goes straight
 * there: a thinning tape is a warning about a rally, not yet a call against it.
 */
internal fun pillarTone(pillarId: String, score: Int): String {
    val band = scoreBand(score)
    return when (pillarId) {
        "trend" -> if (band >= 1) "bullish" else if (band == 0) "neutral" else "bearish"
        "volatility" -> when (band) {
            2 -> "bearish"
            1 -> "caution"
            0 -> "neutral"
            else -> "bullish"
        }
        "sentiment" -> if (band >= 1) "opportunity" else if (band == 0) "neutral" else "caution"
        "breadth", "cross_asset", "quality" -> when {
            band >= 1 -> "bullish"
            band == 0 -> "neutral"
            band == -1 -> "caution"
            else -> "bearish"
        }
        else -> "neutral"
    }
}

/** `interpret.rs::score_band`: two bands either side of a neutral middle. */
private fun scoreBand(score: Int): Int = when {
    score >= 50 -> 2
    score >= 15 -> 1
    score > -15 -> 0
    score > -50 -> -1
    else -> -2
}

private const val BREADTH_FULL_UNIVERSE = 80
private const val CROSS_ASSET_SPARSE_BPS = 2000
private const val DEGRADED_CONFIDENCE_BPS = 4000
private const val CORRELATION_LOOKBACK = 60
private const val CORRELATION_SAMPLE_MAX = 12
private const val MA200_WINDOW = 200
private const val MIN_CLOSES_FOR_SUMMARY = 20
private const val NOTES_PER_PILLAR = 2
private const val SECONDS_PER_DAY = 86_400L
