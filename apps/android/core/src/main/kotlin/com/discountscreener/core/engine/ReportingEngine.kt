package com.discountscreener.core.engine

import com.discountscreener.core.model.AlertEvent
import com.discountscreener.core.model.AlertKind
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.PersistedReportState
import com.discountscreener.core.model.PersistedSymbolState
import com.discountscreener.core.model.PriceHistoryPoint
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.TapeEvent
import com.discountscreener.core.model.ViewFilter
import java.math.BigInteger

private const val MAX_PRICE_HISTORY_PER_SYMBOL = 240

class ReportingEngine(
    private val minGapBps: Int = 2_000,
    private val externalSignalMaxAgeSeconds: Long = 30,
    private val tapeCapacity: Int = 8,
) {
    private data class SymbolState(
        var snapshot: MarketSnapshot? = null,
        var externalSignal: ExternalValuationSignal? = null,
        var fundamentals: FundamentalSnapshot? = null,
        var lastSequence: Int = 0,
        var updateCount: Int = 0,
        val priceHistory: ArrayDeque<PriceHistoryPoint> = ArrayDeque(),
    )

    private val symbols = linkedMapOf<String, SymbolState>()
    private val watchlist = linkedSetOf<String>()
    private val recentTape = ArrayDeque<TapeEvent>()
    private val recentAlerts = ArrayDeque<AlertEvent>()

    var totalEvents: Int = 0
        private set

    var latestSequence: Int = 0
        private set

    fun symbolCount(): Int = symbols.size

    fun trackedSymbols(): List<String> = symbols.keys.toList()

    fun ingestSnapshot(snapshot: MarketSnapshot) {
        if (snapshot.marketPriceCents <= 0 || snapshot.intrinsicValueCents <= 0) {
            return
        }

        val previousDetail = detail(snapshot.symbol)
        val sequence = nextSequence()
        val state = symbols.getOrPut(snapshot.symbol) { SymbolState() }
        state.snapshot = snapshot
        state.lastSequence = sequence
        state.updateCount += 1
        while (state.priceHistory.size >= MAX_PRICE_HISTORY_PER_SYMBOL) {
            state.priceHistory.removeFirst()
        }
        state.priceHistory.addLast(
            PriceHistoryPoint(sequence = sequence, marketPriceCents = snapshot.marketPriceCents),
        )
        totalEvents += 1
        pushTape(snapshot.symbol)
        pushAlerts(snapshot.symbol, previousDetail)
    }

    fun ingestExternal(signal: ExternalValuationSignal) {
        if (signal.fairValueCents <= 0) {
            return
        }

        val sanitized = sanitizeExternalSignal(signal)
        val previousDetail = detail(sanitized.symbol)
        val sequence = nextSequence()
        val state = symbols.getOrPut(sanitized.symbol) { SymbolState() }
        state.externalSignal = sanitized
        state.lastSequence = sequence
        state.updateCount += 1
        totalEvents += 1
        pushTape(sanitized.symbol)
        pushAlerts(sanitized.symbol, previousDetail)
    }

    fun ingestFundamentals(fundamentals: FundamentalSnapshot) {
        if (!fundamentals.hasAnyValues()) {
            return
        }

        val sequence = nextSequence()
        val state = symbols.getOrPut(fundamentals.symbol) { SymbolState() }
        state.fundamentals = fundamentals
        state.lastSequence = sequence
        state.updateCount += 1
        totalEvents += 1
    }

    fun candidate(symbol: String): CandidateRow? = symbols[symbol]?.let(::buildCandidate)

    fun detail(symbol: String): SymbolDetail? = symbols[symbol]?.let(::buildDetail)

    fun topRows(limit: Int): List<CandidateRow> = sortedRows().take(limit)

    fun filteredRows(limit: Int = Int.MAX_VALUE, filter: ViewFilter = ViewFilter()): List<CandidateRow> {
        val query = filter.query.trim()
        val rows = symbols
            .filter { (symbol, _) ->
                val queryMatches = query.isEmpty() || symbol.contains(query, ignoreCase = true)
                val watchlistMatches = !filter.watchlistOnly || watchlist.contains(symbol)
                queryMatches && watchlistMatches
            }
            .values
            .mapNotNull(::buildCandidate)
            .toMutableList()
        sortRows(rows)
        return rows.take(limit)
    }

    fun priceHistory(symbol: String, limit: Int = MAX_PRICE_HISTORY_PER_SYMBOL): List<PriceHistoryPoint> =
        symbols[symbol]
            ?.priceHistory
            ?.toList()
            ?.takeLast(limit)
            ?: emptyList()

    fun watchlistSymbols(): List<String> = watchlist.toList().sorted()

    fun replaceWatchlist(symbols: List<String>) {
        watchlist.clear()
        watchlist.addAll(symbols)
    }

    fun toggleWatchlist(symbol: String): Boolean {
        return if (watchlist.remove(symbol)) {
            false
        } else {
            watchlist.add(symbol)
            true
        }
    }

    fun isWatched(symbol: String): Boolean = watchlist.contains(symbol)

    fun recentTape(): List<TapeEvent> = recentTape.toList()

    fun alerts(): List<AlertEvent> = recentAlerts.toList()

    fun restore(state: PersistedReportState) {
        totalEvents = 0
        latestSequence = 0
        symbols.clear()
        recentTape.clear()
        recentAlerts.clear()
        watchlist.clear()

        state.symbolStates.forEach { persisted ->
            latestSequence = maxOf(latestSequence, persisted.lastSequence)
            totalEvents += persisted.updateCount
            val priceHistory = ArrayDeque<PriceHistoryPoint>()
            persisted.priceHistory.take(MAX_PRICE_HISTORY_PER_SYMBOL).forEach(priceHistory::addLast)
            symbols[persisted.symbol] = SymbolState(
                snapshot = persisted.snapshot,
                externalSignal = persisted.externalSignal?.let(::sanitizeExternalSignal),
                fundamentals = persisted.fundamentals,
                lastSequence = persisted.lastSequence,
                updateCount = persisted.updateCount,
                priceHistory = priceHistory,
            )
        }
        watchlist.addAll(state.watchlist)
    }

    fun persistedState(): List<PersistedSymbolState> = symbols
        .map { (symbol, state) ->
            PersistedSymbolState(
                symbol = symbol,
                snapshot = state.snapshot,
                externalSignal = state.externalSignal,
                fundamentals = state.fundamentals,
                lastSequence = state.lastSequence,
                updateCount = state.updateCount,
                priceHistory = state.priceHistory.toList(),
            )
        }
        .sortedBy { it.symbol }

    private fun buildCandidate(state: SymbolState): CandidateRow? {
        val detail = buildDetail(state) ?: return null
        return CandidateRow(
            symbol = detail.symbol,
            marketPriceCents = detail.marketPriceCents,
            intrinsicValueCents = detail.intrinsicValueCents,
            gapBps = detail.gapBps,
            upsideBps = detail.upsideBps,
            isQualified = detail.qualification == QualificationStatus.Qualified,
            confidence = detail.confidence,
            companyName = detail.companyName,
        )
    }

    private fun buildDetail(state: SymbolState): SymbolDetail? {
        val snapshot = state.snapshot ?: return null
        val internalGapBps = checkedGapBps(snapshot.marketPriceCents, snapshot.intrinsicValueCents) ?: 0
        val internalUpsideBps = checkedUpsideBps(snapshot.marketPriceCents, snapshot.intrinsicValueCents) ?: 0
        val qualification = qualificationFor(snapshot, internalGapBps)
        val externalStatus = externalStatusFor(snapshot, state.externalSignal)
        val confidence = confidenceFor(qualification, externalStatus)
        val external = state.externalSignal

        return SymbolDetail(
            symbol = snapshot.symbol,
            profitable = snapshot.profitable,
            marketPriceCents = snapshot.marketPriceCents,
            intrinsicValueCents = snapshot.intrinsicValueCents,
            gapBps = internalGapBps,
            upsideBps = internalUpsideBps,
            minimumGapBps = minGapBps,
            qualification = qualification,
            externalStatus = externalStatus,
            externalSignalFairValueCents = external?.fairValueCents,
            externalSignalLowFairValueCents = external?.lowFairValueCents,
            externalSignalHighFairValueCents = external?.highFairValueCents,
            weightedExternalSignalFairValueCents = clampedWeightedFairValue(external),
            weightedAnalystCount = if (clampedWeightedFairValue(external) != null) external?.weightedAnalystCount else null,
            externalSignalGapBps = external?.let { checkedGapBps(snapshot.marketPriceCents, it.fairValueCents) },
            externalSignalAgeSeconds = external?.ageSeconds,
            externalSignalMaxAgeSeconds = externalSignalMaxAgeSeconds,
            analystOpinionCount = external?.analystOpinionCount,
            recommendationMeanHundredths = external?.recommendationMeanHundredths,
            strongBuyCount = external?.strongBuyCount,
            buyCount = external?.buyCount,
            holdCount = external?.holdCount,
            sellCount = external?.sellCount,
            strongSellCount = external?.strongSellCount,
            fundamentals = state.fundamentals,
            confidence = confidence,
            lastSequence = state.lastSequence,
            updateCount = state.updateCount,
            isWatched = watchlist.contains(snapshot.symbol),
            companyName = snapshot.companyName,
        )
    }

    private fun qualificationFor(snapshot: MarketSnapshot, gapBps: Int): QualificationStatus = when {
        !snapshot.profitable -> QualificationStatus.Unprofitable
        gapBps >= minGapBps -> QualificationStatus.Qualified
        else -> QualificationStatus.GapTooSmall
    }

    private fun externalStatusFor(
        snapshot: MarketSnapshot,
        externalSignal: ExternalValuationSignal?,
    ): ExternalSignalStatus {
        externalSignal ?: return ExternalSignalStatus.Missing
        if (externalSignal.symbol != snapshot.symbol) return ExternalSignalStatus.Divergent
        if (externalSignal.ageSeconds > externalSignalMaxAgeSeconds) return ExternalSignalStatus.Stale
        return if ((checkedGapBps(snapshot.marketPriceCents, externalSignal.fairValueCents) ?: Int.MIN_VALUE) >= minGapBps) {
            ExternalSignalStatus.Supportive
        } else {
            ExternalSignalStatus.Divergent
        }
    }

    private fun confidenceFor(
        qualification: QualificationStatus,
        externalStatus: ExternalSignalStatus,
    ): ConfidenceBand {
        if (qualification != QualificationStatus.Qualified) {
            return ConfidenceBand.Low
        }
        return when (externalStatus) {
            ExternalSignalStatus.Missing -> ConfidenceBand.Provisional
            ExternalSignalStatus.Supportive -> ConfidenceBand.High
            ExternalSignalStatus.Stale, ExternalSignalStatus.Divergent -> ConfidenceBand.Low
        }
    }

    private fun sortedRows(): List<CandidateRow> = buildList {
        symbols.values.mapNotNullTo(this, ::buildCandidate)
    }.toMutableList().also(::sortRows)

    private fun sortRows(rows: MutableList<CandidateRow>) {
        rows.sortWith(
            compareByDescending<CandidateRow> { it.isQualified }
                .thenByDescending { it.upsideBps }
                .thenByDescending { confidenceRank(it.confidence) }
                .thenBy { it.symbol },
        )
    }

    private fun confidenceRank(confidence: ConfidenceBand): Int = when (confidence) {
        ConfidenceBand.Low -> 0
        ConfidenceBand.Provisional -> 1
        ConfidenceBand.High -> 2
    }

    private fun nextSequence(): Int {
        latestSequence += 1
        return latestSequence
    }

    private fun pushTape(symbol: String) {
        if (tapeCapacity <= 0) return
        val candidate = candidate(symbol) ?: return
        while (recentTape.size >= tapeCapacity) {
            recentTape.removeFirst()
        }
        recentTape.addLast(
            TapeEvent(
                symbol = candidate.symbol,
                gapBps = candidate.gapBps,
                isQualified = candidate.isQualified,
                confidence = candidate.confidence,
            ),
        )
    }

    private fun pushAlerts(symbol: String, previousDetail: SymbolDetail?) {
        val currentDetail = detail(symbol) ?: return
        val previousQualified = previousDetail?.qualification == QualificationStatus.Qualified
        val currentQualified = currentDetail.qualification == QualificationStatus.Qualified

        when {
            !previousQualified && currentQualified -> pushAlert(symbol, AlertKind.EnteredQualified, currentDetail.lastSequence)
            previousQualified && !currentQualified -> pushAlert(symbol, AlertKind.ExitedQualified, currentDetail.lastSequence)
            previousQualified && currentQualified &&
                previousDetail?.confidence != ConfidenceBand.High &&
                currentDetail.confidence == ConfidenceBand.High ->
                pushAlert(symbol, AlertKind.ConfidenceUpgraded, currentDetail.lastSequence)
        }
    }

    private fun pushAlert(symbol: String, kind: AlertKind, sequence: Int) {
        if (tapeCapacity <= 0) return
        while (recentAlerts.size >= tapeCapacity) {
            recentAlerts.removeFirst()
        }
        recentAlerts.addLast(AlertEvent(symbol = symbol, kind = kind, sequence = sequence))
    }
}

fun checkedGapBps(marketPriceCents: Long, fairValueCents: Long): Int? {
    if (fairValueCents <= 0) {
        return null
    }
    val fair = BigInteger.valueOf(fairValueCents)
    val market = BigInteger.valueOf(marketPriceCents)
    val scaledGapBps = ((fair - market) * BigInteger.valueOf(10_000L)) / fair
    return scaledGapBps.coerceIn(BigInteger.valueOf(Int.MIN_VALUE.toLong()), BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
}

fun checkedUpsideBps(marketPriceCents: Long, fairValueCents: Long): Int? {
    if (marketPriceCents <= 0 || fairValueCents <= 0) {
        return null
    }
    val fair = BigInteger.valueOf(fairValueCents)
    val market = BigInteger.valueOf(marketPriceCents)
    val scaledUpsideBps = ((fair - market) * BigInteger.valueOf(10_000L)) / market
    return scaledUpsideBps.coerceIn(BigInteger.valueOf(Int.MIN_VALUE.toLong()), BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
}

fun clampedWeightedFairValue(signal: ExternalValuationSignal?): Long? {
    signal ?: return null
    var weighted = signal.weightedFairValueCents ?: return null
    if (signal.lowFairValueCents != null && signal.highFairValueCents != null) {
        val low = minOf(signal.lowFairValueCents, signal.highFairValueCents)
        val high = maxOf(signal.lowFairValueCents, signal.highFairValueCents)
        weighted = weighted.coerceIn(low, high)
    }
    return weighted.takeIf { it > 0 }
}

fun sanitizeExternalSignal(signal: ExternalValuationSignal): ExternalValuationSignal {
    val weighted = clampedWeightedFairValue(signal)
    return if (weighted == null) {
        signal.copy(weightedFairValueCents = null, weightedAnalystCount = null)
    } else {
        signal.copy(weightedFairValueCents = weighted)
    }
}
