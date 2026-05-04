package com.discountscreener.android.data.repository

import com.discountscreener.android.data.persistence.CaptureKind
import com.discountscreener.android.data.persistence.EvaluatedSymbolState
import com.discountscreener.android.data.persistence.MetricGroupStatus
import com.discountscreener.android.data.persistence.PersistenceBootstrap
import com.discountscreener.android.data.persistence.PersistenceIssueSeverity
import com.discountscreener.android.data.persistence.PersistenceIssueSource
import com.discountscreener.android.data.persistence.PersistedIssueRecord
import com.discountscreener.android.data.persistence.RawCapture
import com.discountscreener.android.data.persistence.RawCapturePayload
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.persistence.SymbolRevisionInput
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.ProviderDiagnostic
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.ProfileTransitionEvent
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowExplanationKind
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.domain.model.SystemStats
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.domain.model.preferredAnalystTargetFairValueCents
import com.discountscreener.android.domain.model.rankMovement
import com.discountscreener.android.domain.model.significantValuationChange
import com.discountscreener.android.domain.model.reduceProfileTransition
import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.DcfAnalysisEngine
import com.discountscreener.core.engine.OpportunityContext
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.engine.PricingHistoryMerge
import com.discountscreener.core.engine.QuantLensEngine
import com.discountscreener.core.engine.ReportingEngine
import com.discountscreener.core.engine.buildSymbolDetail
import com.discountscreener.core.engine.checkedUpsideBps
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DataProvenance
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.IssueRecord
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.PersistedReportState
import com.discountscreener.core.model.PricingCandle
import com.discountscreener.core.model.QuantLensComparable
import com.discountscreener.core.model.QuantLensCorrelationSeries
import com.discountscreener.core.model.QuantLensInput
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.model.QuantLensLensRowState
import com.discountscreener.core.model.QuantLensModelVersion
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReasonCode
import com.discountscreener.core.model.QuantLensReport
import com.discountscreener.core.model.QuantLensRowLabel
import com.discountscreener.core.model.QuantLensRowSummary
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRevision
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.roundToLong

private data class SymbolRefreshResult(
    val generation: Long,
    val symbol: String,
    val providerResult: ProviderFetchResult? = null,
    val chartCandles: List<HistoricalCandle>? = null,
    val fallbackSnapshot: MarketSnapshot? = null,
    val fallbackFundamentals: FundamentalSnapshot? = null,
    val fallbackTimeseries: FundamentalTimeseries? = null,
    val fallbackDcfAnalysis: DcfAnalysis? = null,
    val chartError: Throwable? = null,
    val retryable: Boolean = false,
    val refreshedAtEpochSeconds: Long,
)

private data class EnrichmentResult(
    val generation: Long,
    val symbol: String,
    val chartCaptures: List<Pair<ChartRange, List<HistoricalCandle>>>,
    val timeseries: FundamentalTimeseries?,
    val dcfAnalysis: DcfAnalysis?,
    val errors: List<ProviderDiagnostic>,
)

private data class PersistenceDelta(
    val rawCaptures: List<RawCapture>,
    val revisions: List<SymbolRevisionInput>,
    val issues: List<PersistedIssueRecord>,
)

private data class QuantLensCacheEntry(
    val fingerprint: String,
    val report: QuantLensReport,
)

private data class ProfileSwitchRequest(
    val generation: Long,
    val profile: String,
    val symbols: List<String>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDashboardRepository(
    private val stateStore: SQLiteStateStore,
    private val profileCatalog: ProfileCatalog,
    private val yahooClient: YahooFinanceClient,
    private val secondaryTimeseriesProvider: FundamentalTimeseriesProvider? = null,
    private val nowProvider: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DashboardRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stateMutex = Mutex()
    private val updates = MutableStateFlow(0L)
    private val dcfSourceCoordinator = DcfSourceCoordinator(yahooClient, secondaryTimeseriesProvider)

    private var engine = ReportingEngine()
    private var trackedSymbols = mutableListOf<String>()
    private val revisions = linkedMapOf<String, MutableList<SymbolRevision>>()
    private val chartCache = linkedMapOf<String, List<HistoricalCandle>>()
    private val chartSummaries = linkedMapOf<String, MutableMap<ChartRange, ChartRangeSummary>>()
    private val dcfCache = linkedMapOf<String, DcfAnalysis>()
    private val timeseriesCache = linkedMapOf<String, FundamentalTimeseries>()
    private val quantLensCache = linkedMapOf<String, QuantLensCacheEntry>()
    private val issues = linkedMapOf<String, PersistedIssueRecord>()
    private val staleSymbols = linkedSetOf<String>()
    private val placeholderSymbols = linkedSetOf<String>()
    private val refreshedSymbols = linkedSetOf<String>()
    private val refreshAttemptedSymbols = linkedSetOf<String>()
    private val comparisonBaselineRankBySymbol = linkedMapOf<String, Int>()
    private val comparisonBaselineOpportunityRankByModel =
        OpportunityScoringModel.entries.associateWith { linkedMapOf<String, Int>() }.toMutableMap()
    private val comparisonBaselineWeightedFairValueBySymbol = linkedMapOf<String, Long>()
    private val comparisonBaselineMarketPriceBySymbol = linkedMapOf<String, Long>()
    private val freshnessTimestampBySymbol = linkedMapOf<String, Long>()

    private var currentProfile = DEFAULT_PROFILE
    private var lastUpdatedAtEpochSeconds: Long? = null
    private var startupPhase = DashboardStartupPhase.Restoring
    private var refreshCompletedSymbols = 0
    private var refreshTargetSymbols = 0
    private var issueEventCounter = 0
    private var statusMessage: String? = null
    private var restored = false
    private var activeProfileGeneration = 0L
    private var activeProfileSwitchJob: Job? = null
    private var activeRefreshJob: Job? = null
    private var activeEnrichmentJob: Job? = null

    override fun observeUpdates(): Flow<Long> = updates.asStateFlow()

    override suspend fun bootstrap(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot {
        if (!restored) {
            loadUniverse(DEFAULT_PROFILE)
            restored = true
        }
        return currentSnapshot(filter, selectedSymbol, selectedRange, opportunityScoringModel)
    }

    override suspend fun currentSnapshot(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = stateMutex.withLock {
        snapshotLocked(filter, selectedSymbol, selectedRange, opportunityScoringModel)
    }

    override suspend fun refreshAll(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot {
        startRefreshForCurrentProfile(stateMutex.withLock { trackedSymbols.toList() })
        return currentSnapshot(filter, selectedSymbol, selectedRange, opportunityScoringModel)
    }

    override suspend fun ensureDetailLoaded(
        symbol: String,
        filter: ViewFilter,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot {
        ensureRevisionHistoryLoaded(symbol)
        hydratePricingHistoryForDetail(symbol)

        val captures = mutableListOf<RawCapture>()
        ChartRange.entries.forEach { range ->
            val key = chartKey(symbol, range)
            if (stateMutex.withLock { chartCache[key] } == null) {
                val candles = runCatching { yahooClient.fetchHistoricalCandles(symbol, range) }.getOrNull()
                if (!candles.isNullOrEmpty()) {
                    stateMutex.withLock {
                        chartCache[key] = candles
                        chartSummaries.getOrPut(symbol) { linkedMapOf() }[range] =
                            ChartAnalysis.buildSummary(range, candles, now())
                    }
                    captures += RawCapture(
                        symbol = symbol,
                        captureKind = CaptureKind.ChartCandles,
                        scopeKey = range.name,
                        capturedAt = now(),
                        payload = RawCapturePayload.Chart(range, candles),
                    )
                }
            }
        }

        val fundamentals = stateMutex.withLock { engine.detail(symbol)?.fundamentals }
        val needsTimeseries = stateMutex.withLock { fundamentals != null && timeseriesCache[symbol] == null }
        if (fundamentals != null && needsTimeseries) {
            val selection = dcfSourceCoordinator.resolve(symbol) { timeseries ->
                DcfAnalysisEngine.compute(fundamentals, timeseries).getOrNull()
            }
            selection.timeseries?.let { timeseries ->
                stateMutex.withLock {
                    timeseriesCache[symbol] = timeseries
                    selection.analysis?.let { analysis -> dcfCache[symbol] = analysis }
                }
                captures += fundamentalTimeseriesCapture(
                    symbol = symbol,
                    timeseries = timeseries,
                    analysis = selection.analysis,
                    capturedAt = now(),
                )
            }
        }

        val persistenceDelta = stateMutex.withLock {
            appendRevisionLocked(symbol)
            snapshotPersistenceDeltaLocked(captures, symbol)
        }
        persistDelta(persistenceDelta)
        emitUpdate()
        return currentSnapshot(filter, symbol, selectedRange, opportunityScoringModel)
    }

    override suspend fun addSymbols(
        rawInput: String,
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot {
        val symbols = rawInput
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(String::uppercase)
            .distinct()
        if (symbols.isEmpty()) {
            return currentSnapshot(filter, selectedSymbol, selectedRange, opportunityScoringModel)
        }

        val newSymbols = stateMutex.withLock {
            symbols.filter { it !in trackedSymbols }.also { additions ->
                trackedSymbols.addAll(additions)
                placeholderSymbols.addAll(additions)
                trackedSymbols = reorderSymbolsByPersistedRanking(trackedSymbols).toMutableList()
                statusMessage = "Tracking ${additions.joinToString(", ")}"
            }
        }

        if (newSymbols.isNotEmpty()) {
            stateStore.replaceTrackedSymbols(stateMutex.withLock { trackedSymbols.toList() })
            emitUpdate()
            startRefreshForCurrentProfile(newSymbols)
        }

        return currentSnapshot(
            filter,
            selectedSymbol ?: newSymbols.firstOrNull(),
            selectedRange,
            opportunityScoringModel,
        )
    }

    override suspend fun selectProfile(
        profile: String,
        filter: ViewFilter,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot {
        val request = beginProfileSwitch(profile)
        return currentSnapshot(filter, request.symbols.firstOrNull(), selectedRange, opportunityScoringModel)
    }

    private suspend fun beginProfileSwitch(profile: String): ProfileSwitchRequest {
        val symbols = profileCatalog.loadProfile(profile).distinct().ifEmpty {
            profileCatalog.loadProfile(DEFAULT_PROFILE).distinct()
        }
        val generation = stateMutex.withLock {
            activeProfileGeneration += 1
            activeProfileGeneration
        }
        cancelActiveProfileWork()
        stateMutex.withLock {
            resetInMemoryLocked()
            currentProfile = profile
            trackedSymbols = symbols.toMutableList()
            placeholderSymbols.addAll(trackedSymbols)
            applyTransitionLocked(
                reduceProfileTransition(
                    ProfileTransitionEvent.SwitchRequested(
                        profile = profile,
                        symbolCount = trackedSymbols.size,
                    ),
                ),
            )
        }
        emitUpdate()
        val request = ProfileSwitchRequest(
            generation = generation,
            profile = profile,
            symbols = symbols,
        )
        val job = repositoryScope.launch {
            try {
                hydrateProfileSwitch(request)
            } finally {
                stateMutex.withLock {
                    if (activeProfileGeneration == request.generation) {
                        activeProfileSwitchJob = null
                    }
                }
            }
        }
        stateMutex.withLock {
            if (activeProfileGeneration == generation) {
                activeProfileSwitchJob = job
            }
        }
        return request
    }

    override suspend fun toggleWatchlist(
        symbol: String,
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot {
        stateMutex.withLock {
            engine.toggleWatchlist(symbol)
            appendRevisionLocked(symbol)
        }
        stateStore.replaceWatchlist(stateMutex.withLock { engine.watchlistSymbols() })
        persistDelta(stateMutex.withLock { snapshotPersistenceDeltaLocked(emptyList(), symbol) })
        emitUpdate()
        return currentSnapshot(filter, selectedSymbol, selectedRange, opportunityScoringModel)
    }

    private suspend fun loadUniverse(profile: String) {
        val symbols = profileCatalog.loadProfile(profile).distinct().ifEmpty {
            profileCatalog.loadProfile(DEFAULT_PROFILE).distinct()
        }
        val bootstrap = runCatching { stateStore.loadWarmStart() }
            .getOrElse { error ->
                stateStore.resetWarmStartState()
                stateMutex.withLock {
                    resetInMemoryLocked()
                    statusMessage = "SQLite warm-start reset after restore failure: ${error.message ?: "unknown error"}"
                }
                PersistenceBootstrap()
            }

        stateMutex.withLock {
            resetInMemoryLocked()
            currentProfile = profile
            trackedSymbols = symbols.toMutableList()
            hydrateWarmStartLocked(bootstrap)
            trackedSymbols = reorderSymbolsByPersistedRanking(trackedSymbols).toMutableList()
            placeholderSymbols.clear()
            placeholderSymbols.addAll(trackedSymbols.filter { engine.detail(it) == null })
            applyTransitionLocked(
                reduceProfileTransition(
                    ProfileTransitionEvent.CachedHydrated(
                        profile = currentProfile,
                        symbolCount = trackedSymbols.size,
                        cachedSymbolCount = staleSymbols.size,
                    ),
                ),
            )
        }

        stateStore.replaceTrackedSymbols(stateMutex.withLock { trackedSymbols.toList() })
        stateStore.replaceWatchlist(stateMutex.withLock { engine.watchlistSymbols() })
        stateStore.replaceIssues(stateMutex.withLock { issues.values.toList() })
        emitUpdate()
    }

    private suspend fun hydrateProfileSwitch(request: ProfileSwitchRequest) {
        val bootstrap = runCatching { stateStore.loadWarmStart() }
            .getOrElse { error ->
                stateStore.resetWarmStartState()
                stateMutex.withLock {
                    if (request.generation != activeProfileGeneration) {
                        return
                    }
                    resetInMemoryLocked()
                    currentProfile = request.profile
                    trackedSymbols = request.symbols.toMutableList()
                    placeholderSymbols.addAll(trackedSymbols)
                    applyTransitionLocked(
                        reduceProfileTransition(
                            ProfileTransitionEvent.SwitchRequested(
                                profile = request.profile,
                                symbolCount = trackedSymbols.size,
                            ),
                        ),
                    )
                    statusMessage = "SQLite warm-start reset after restore failure: ${error.message ?: "unknown error"}"
                }
                PersistenceBootstrap()
            }

        stateMutex.withLock {
            if (request.generation != activeProfileGeneration) {
                return
            }
            currentProfile = request.profile
            trackedSymbols = request.symbols.toMutableList()
            hydrateWarmStartLocked(bootstrap)
            trackedSymbols = reorderSymbolsByPersistedRanking(trackedSymbols).toMutableList()
            placeholderSymbols.clear()
            placeholderSymbols.addAll(trackedSymbols.filter { engine.detail(it) == null })
            applyTransitionLocked(
                reduceProfileTransition(
                    ProfileTransitionEvent.CachedHydrated(
                        profile = currentProfile,
                        symbolCount = trackedSymbols.size,
                        cachedSymbolCount = staleSymbols.size,
                    ),
                ),
            )
        }

        stateStore.replaceTrackedSymbols(stateMutex.withLock { trackedSymbols.toList() })
        stateStore.replaceWatchlist(stateMutex.withLock { engine.watchlistSymbols() })
        stateStore.replaceIssues(stateMutex.withLock { issues.values.toList() })
        emitUpdate()
        startRefresh(request.symbols, request.generation)
    }

    private suspend fun startRefreshForCurrentProfile(symbols: List<String>) {
        val generation = stateMutex.withLock { activeProfileGeneration }
        startRefresh(symbols, generation)
    }

    private suspend fun startRefresh(symbols: List<String>, generation: Long) {
        if (symbols.isEmpty()) {
            return
        }

        val (previousRefreshJob, previousEnrichmentJob) = stateMutex.withLock {
            val existingRefresh = activeRefreshJob
            activeRefreshJob = null
            val existingEnrichment = activeEnrichmentJob
            activeEnrichmentJob = null
            Pair(existingRefresh, existingEnrichment)
        }
        previousRefreshJob?.cancelAndJoin()
        previousEnrichmentJob?.cancelAndJoin()

        stateMutex.withLock {
            captureRefreshComparisonBaselineLocked()
            refreshedSymbols.clear()
            refreshAttemptedSymbols.clear()
            applyTransitionLocked(
                reduceProfileTransition(
                    ProfileTransitionEvent.RefreshStarted(
                        profile = currentProfile,
                        symbolCount = symbols.size,
                    ),
                ),
            )
            activeRefreshJob = repositoryScope.launch {
                try {
                    runRefresh(symbols, generation)
                } finally {
                    val symbolsToEnrich = stateMutex.withLock {
                        if (generation != activeProfileGeneration) {
                            return@withLock emptyList()
                        }
                        activeRefreshJob = null
                        applyTransitionLocked(
                            reduceProfileTransition(
                                ProfileTransitionEvent.RefreshFinished(
                                    activeIssueCount = issues.values.count { it.active },
                                ),
                            ),
                        )
                        trackedSymbols.filter { engine.detail(it) != null }
                    }
                    emitUpdate()
                    startEnrichment(symbolsToEnrich, generation)
                }
            }
        }
        emitUpdate()
    }

    private suspend fun runRefresh(symbols: List<String>, generation: Long) {
        val retryQueue = ArrayDeque<String>()
        processRefreshRound(symbols, retryQueue, generation)
        repeat(MAX_RETRY_ROUNDS) {
            if (retryQueue.isEmpty()) return
            val batch = buildList {
                while (retryQueue.isNotEmpty()) {
                    add(retryQueue.removeFirst())
                }
            }
            processRefreshRound(batch, retryQueue, generation)
        }
    }

    private suspend fun processRefreshRound(
        symbols: List<String>,
        retryQueue: ArrayDeque<String>,
        generation: Long,
    ) = coroutineScope {
        symbols
            .asFlow()
            .flatMapMerge(concurrency = REFRESH_CONCURRENCY) { symbol ->
                flow { emit(fetchRefreshResult(symbol, generation)) }
            }
            .collect { result ->
                val isActiveGeneration = stateMutex.withLock { result.generation == activeProfileGeneration }
                if (!isActiveGeneration) {
                    return@collect
                }
                if (result.retryable && result.symbol !in retryQueue) {
                    retryQueue.add(result.symbol)
                }
                val persistenceDelta = stateMutex.withLock { applyRefreshResultLocked(result) }
                queuePersist(persistenceDelta)
                emitUpdate()
            }
    }

    private suspend fun fetchRefreshResult(symbol: String, generation: Long): SymbolRefreshResult {
        val refreshedAt = now()
        val providerResult = runCatching { yahooClient.fetchSymbol(symbol) }.getOrElse { error ->
            if (error is CancellationException) throw error
            return SymbolRefreshResult(
                generation = generation,
                symbol = symbol,
                chartError = error,
                retryable = isRetryable(error),
                refreshedAtEpochSeconds = refreshedAt,
            )
        }

        val chartResult = runCatching { yahooClient.fetchHistoricalCandles(symbol, ChartRange.Year) }
        val chartCandles = chartResult.getOrNull()
        val dcfFallback = if (providerResult.snapshot == null) {
            resolveDcfFallback(
                symbol = symbol,
                companyName = providerResult.snapshot?.companyName,
                providerFundamentals = providerResult.fundamentals,
                chartCandles = chartCandles,
            )
        } else {
            null
        }
        return SymbolRefreshResult(
            generation = generation,
            symbol = symbol,
            providerResult = providerResult,
            chartCandles = chartCandles,
            fallbackSnapshot = dcfFallback?.snapshot,
            fallbackFundamentals = dcfFallback?.fundamentals,
            fallbackTimeseries = dcfFallback?.timeseries,
            fallbackDcfAnalysis = dcfFallback?.analysis,
            chartError = chartResult.exceptionOrNull(),
            retryable = providerResult.diagnostics.any { it.retryable } ||
                chartResult.exceptionOrNull()?.let(::isRetryable) == true,
            refreshedAtEpochSeconds = refreshedAt,
        )
    }

    private fun applyRefreshResultLocked(result: SymbolRefreshResult): PersistenceDelta {
        val rawCaptures = mutableListOf<RawCapture>()
        val providerResult = result.providerResult
        val fallbackSnapshot = if (providerResult?.snapshot == null) {
            fallbackSnapshotFromCachedDetail(
                symbol = result.symbol,
                detail = engine.detail(result.symbol),
                chartCandles = result.chartCandles,
            )
        } else {
            null
        }
        val effectiveSnapshot = providerResult?.snapshot ?: fallbackSnapshot ?: result.fallbackSnapshot
        val effectiveFundamentals = providerResult?.fundamentals ?: result.fallbackFundamentals

        effectiveSnapshot?.let {
            engine.ingestSnapshot(it)
            rawCaptures += RawCapture(
                symbol = result.symbol,
                captureKind = CaptureKind.Snapshot,
                scopeKey = null,
                capturedAt = result.refreshedAtEpochSeconds,
                payload = RawCapturePayload.Snapshot(it),
            )
        }
        providerResult?.externalSignal?.let {
            engine.ingestExternal(it)
            rawCaptures += RawCapture(
                symbol = result.symbol,
                captureKind = CaptureKind.External,
                scopeKey = null,
                capturedAt = result.refreshedAtEpochSeconds,
                payload = RawCapturePayload.External(it),
            )
        }
        effectiveFundamentals?.let {
            engine.ingestFundamentals(it)
            rawCaptures += RawCapture(
                symbol = result.symbol,
                captureKind = CaptureKind.Fundamentals,
                scopeKey = null,
                capturedAt = result.refreshedAtEpochSeconds,
                payload = RawCapturePayload.Fundamentals(it),
            )
        }
        result.fallbackTimeseries?.let { timeseries ->
            timeseriesCache[result.symbol] = timeseries
        }
        result.fallbackDcfAnalysis?.let { analysis ->
            dcfCache[result.symbol] = analysis
        }

        result.chartCandles?.takeIf(List<HistoricalCandle>::isNotEmpty)?.let { candles ->
            val key = chartKey(result.symbol, ChartRange.Year)
            val mergedCandles = mergeHistoricalCandles(
                symbol = result.symbol,
                range = ChartRange.Year,
                persistedCandles = chartCache[key].orEmpty(),
                incomingCandles = candles,
            )
            chartCache[key] = mergedCandles
            chartSummaries.getOrPut(result.symbol) { linkedMapOf() }[ChartRange.Year] =
                ChartAnalysis.buildSummary(ChartRange.Year, mergedCandles, result.refreshedAtEpochSeconds)
            rawCaptures += RawCapture(
                symbol = result.symbol,
                captureKind = CaptureKind.ChartCandles,
                scopeKey = ChartRange.Year.name,
                capturedAt = result.refreshedAtEpochSeconds,
                payload = RawCapturePayload.Chart(ChartRange.Year, candles),
            )
        }

        applyDiagnosticsLocked(
            symbol = result.symbol,
            diagnostics = providerResult?.diagnostics.orEmpty(),
            chartError = result.chartError,
            suppressQuoteHtml404 = fallbackSnapshot != null || result.fallbackSnapshot != null,
            suppressCoreMissing = result.fallbackSnapshot != null,
        )

        if (refreshAttemptedSymbols.add(result.symbol)) {
            refreshCompletedSymbols += 1
        }
        refreshedSymbols += result.symbol
        applyTransitionLocked(
            reduceProfileTransition(
                ProfileTransitionEvent.RefreshProgress(
                    profile = currentProfile,
                    completedSymbols = refreshCompletedSymbols,
                    totalSymbols = refreshTargetSymbols,
                ),
            ),
        )

        if (engine.detail(result.symbol) != null) {
            staleSymbols.remove(result.symbol)
            placeholderSymbols.remove(result.symbol)
            freshnessTimestampBySymbol[result.symbol] = result.refreshedAtEpochSeconds
            appendRevisionLocked(result.symbol)
            lastUpdatedAtEpochSeconds = result.refreshedAtEpochSeconds
        }

        return snapshotPersistenceDeltaLocked(rawCaptures, result.symbol)
    }

    private fun snapshotLocked(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot {
        val normalizedFilter = filter.copy(query = filter.query.trim())
        val selectedDetail = selectedSymbol?.let(engine::detail)
        val selectedCharts = if (selectedSymbol == null) {
            emptyMap()
        } else {
            ChartRange.entries.associateWith { range ->
                chartCache[chartKey(selectedSymbol, range)].orEmpty()
            }
        }
        val trackedIssueMessages = issues.values
            .filter { it.active }
            .associateBy({ it.key.substringBefore(':', it.key) }, { it.detail })

        val trackedRows = trackedRowsLocked(normalizedFilter, trackedIssueMessages)
        val opportunityRows = opportunityRowsLocked(normalizedFilter, opportunityScoringModel)
        val selectedQuantLens = selectedDetail?.let {
            buildSelectedQuantLensLocked(
                detail = it,
                selectedRange = selectedRange,
                opportunityRows = opportunityRows,
                opportunityScoringModel = opportunityScoringModel,
            )
        }

        return DashboardSnapshot(
            availableProfiles = profileCatalog.availableProfiles(),
            currentProfile = currentProfile,
            trackedSymbols = trackedSymbols.toList(),
            trackedRows = trackedRows,
            watchlistSymbols = engine.watchlistSymbols(),
            candidateRows = engine.filteredRows(limit = trackedSymbols.size.coerceAtLeast(1), filter = normalizedFilter),
            opportunityRows = opportunityRows,
            opportunityScoringModel = opportunityScoringModel,
            issues = issues.values
                .sortedByDescending { it.lastSeenEvent }
                .map(::toIssueRecord),
            selectedDetail = selectedDetail,
            selectedCharts = selectedCharts,
            selectedHistory = revisions[selectedSymbol].orEmpty(),
            selectedAlerts = engine.alerts().filter { it.symbol == selectedSymbol }.takeLast(6),
            selectedQuantLens = selectedQuantLens,
            lastUpdatedAtEpochSeconds = lastUpdatedAtEpochSeconds,
            startupPhase = startupPhase,
            refreshCompletedSymbols = refreshCompletedSymbols,
            refreshTargetSymbols = refreshTargetSymbols,
            statusMessage = statusMessage,
        )
    }

    private fun trackedRowsLocked(
        filter: ViewFilter,
        issueMessagesBySymbol: Map<String, String>,
    ): List<TrackedSymbolRow> = rankedTrackedRowsLocked(issueMessagesBySymbol)
        .mapIndexed { currentIndex, row ->
            var currentRankMovement = rankMovement(comparisonBaselineRankBySymbol[row.symbol], currentIndex)
            var currentExplanation = rowExplanationFor(
                hasComparableBaseline = comparisonBaselineRankBySymbol[row.symbol] != null ||
                    comparisonBaselineMarketPriceBySymbol[row.symbol] != null ||
                    comparisonBaselineWeightedFairValueBySymbol[row.symbol] != null,
                hasRankMovement = comparisonBaselineRankBySymbol[row.symbol] != null &&
                    comparisonBaselineRankBySymbol[row.symbol] != currentIndex,
                hasPriceMovement = hasSignificantRelativeMove(
                    previousCents = comparisonBaselineMarketPriceBySymbol[row.symbol],
                    currentCents = row.marketPriceCents,
                ),
                hasTargetMovement = row.valuationChange != null,
            )
            row.copy(
                rankMovement = currentRankMovement,
                explanation = currentExplanation,
                decisionState = trackedDecisionStateFor(
                    state = row.state,
                    freshness = row.freshness,
                    qualification = row.qualification,
                    confidence = row.confidence,
                    upsideBps = row.upsideBps,
                    trustNote = row.trustNote,
                ),
            )
        }
        .filter { row ->
            val queryMatches = filter.query.isBlank() || row.symbol.contains(filter.query, ignoreCase = true)
            val watchlistMatches = !filter.watchlistOnly || row.isWatched
            queryMatches && watchlistMatches
        }
        .sortedWith(
            compareByDescending<TrackedSymbolRow> { it.upsideBps ?: Int.MIN_VALUE }
                .thenBy { trackedRowStateRank(it.state) }
                .thenBy { it.symbol },
        )

    private fun opportunityRowsLocked(
        filter: ViewFilter,
        scoringModel: OpportunityScoringModel,
    ): List<OpportunityListRow> {
        val issueMessagesBySymbol = activeIssueMessagesBySymbolLocked()
        return rankedOpportunityRowsLocked(scoringModel)
        .mapIndexed { currentIndex, row ->
            buildOpportunityRowLocked(row, currentIndex, scoringModel, issueMessagesBySymbol[row.symbol])
        }
        .filter { row ->
            filter.query.isBlank() ||
                row.symbol.contains(filter.query, ignoreCase = true) ||
                row.companyName?.contains(filter.query, ignoreCase = true) == true
        }
    }

    private fun trackedRowStateRank(state: TrackedRowState): Int = when (state) {
        TrackedRowState.Live -> 0
        TrackedRowState.Cached -> 1
        TrackedRowState.Loading -> 2
        TrackedRowState.Failed -> 3
    }

    private fun rankedTrackedRowsLocked(
        issueMessagesBySymbol: Map<String, String>,
    ): List<TrackedSymbolRow> = trackedSymbols
        .map { symbol -> buildTrackedRowLocked(symbol, issueMessagesBySymbol[symbol]) }
        .sortedWith(
            compareByDescending<TrackedSymbolRow> { it.upsideBps ?: Int.MIN_VALUE }
                .thenBy { trackedRowStateRank(it.state) }
                .thenBy { it.symbol },
        )

    private fun rankedOpportunityRowsLocked(
        scoringModel: OpportunityScoringModel,
    ) = OpportunityEngine.buildRows(
        engine,
        OpportunityContext(
            filter = ViewFilter(),
            chartSummariesBySymbol = chartSummaries,
            analysesBySymbol = dcfCache,
            scoringModel = scoringModel,
        ),
    )

    private fun buildTrackedRowLocked(
        symbol: String,
        issueMessage: String?,
    ): TrackedSymbolRow {
        val watched = engine.isWatched(symbol)
        val detail = engine.detail(symbol)
        val state = when {
            detail != null && symbol in refreshedSymbols -> TrackedRowState.Live
            detail != null -> TrackedRowState.Cached
            issueMessage != null -> TrackedRowState.Failed
            else -> TrackedRowState.Loading
        }
        val freshness = rowFreshnessFor(
            hasDetail = detail != null,
            issueMessage = issueMessage,
            isRefreshed = symbol in refreshedSymbols,
            stale = detail != null && symbol in staleSymbols,
            startupPhase = startupPhase,
        )

        return TrackedSymbolRow(
            symbol = symbol,
            marketPriceCents = detail?.marketPriceCents,
            intrinsicValueCents = detail?.intrinsicValueCents,
            gapBps = detail?.gapBps,
            upsideBps = detail?.upsideBps,
            confidence = detail?.confidence,
            qualification = detail?.qualification,
            isWatched = watched,
            state = state,
            freshness = freshness,
            stale = detail != null && symbol in staleSymbols,
            providerIssue = issueMessage,
            trustNote = rowTrustNote(
                detail = detail,
                issueMessage = issueMessage,
            ),
            freshnessAsOfEpochSeconds = freshnessTimestampBySymbol[symbol],
            companyName = detail?.companyName,
            valuationChange = significantValuationChange(
                comparisonBaselineWeightedFairValueBySymbol[symbol],
                preferredAnalystTargetFairValueCents(detail),
            ),
            quantLensSummary = detail?.let {
                buildRowQuantLensSummaryLocked(
                    detail = it,
                    opportunityRow = null,
                )
            },
        )
    }

    private fun buildOpportunityRowLocked(
        row: com.discountscreener.core.model.OpportunityRow,
        currentIndex: Int,
        scoringModel: OpportunityScoringModel,
        issueMessage: String?,
    ): OpportunityListRow {
        val detail = engine.detail(row.symbol)
        val baselineRank = comparisonBaselineOpportunityRankByModel
            .getValue(scoringModel)[row.symbol]
        val freshness = rowFreshnessFor(
            hasDetail = detail != null,
            issueMessage = issueMessage,
            isRefreshed = row.symbol in refreshedSymbols,
            stale = detail != null && row.symbol in staleSymbols,
            startupPhase = startupPhase,
        )
        var currentRankMovement = rankMovement(baselineRank, currentIndex)
        var currentValuationChange = significantValuationChange(
            comparisonBaselineWeightedFairValueBySymbol[row.symbol],
            preferredAnalystTargetFairValueCents(detail),
        )
        var currentExplanation = rowExplanationFor(
            hasComparableBaseline = baselineRank != null ||
                comparisonBaselineMarketPriceBySymbol[row.symbol] != null ||
                comparisonBaselineWeightedFairValueBySymbol[row.symbol] != null,
            hasRankMovement = baselineRank != null && baselineRank != currentIndex,
            hasPriceMovement = hasSignificantRelativeMove(
                previousCents = comparisonBaselineMarketPriceBySymbol[row.symbol],
                currentCents = detail?.marketPriceCents,
            ),
            hasTargetMovement = currentValuationChange != null,
        )
        var currentTrustNote = rowTrustNote(
            detail = detail,
            issueMessage = issueMessage,
        )
        return OpportunityListRow(
            symbol = row.symbol,
            marketPriceCents = row.marketPriceCents,
            intrinsicValueCents = row.intrinsicValueCents,
            gapBps = row.gapBps,
            upsideBps = row.upsideBps,
            confidence = row.confidence,
            isWatched = row.isWatched,
            freshness = freshness,
            providerIssue = issueMessage,
            trustNote = currentTrustNote,
            freshnessAsOfEpochSeconds = freshnessTimestampBySymbol[row.symbol],
            fundamentalsScore = row.fundamentalsScore,
            technicalScore = row.technicalScore,
            forecastScore = row.forecastScore,
            compositeScore = row.compositeScore,
            coverageCount = row.coverageCount,
            fundamentalsSignals = row.fundamentalsSignals,
            technicalSignals = row.technicalSignals,
            forecastSignals = row.forecastSignals,
            companyName = row.companyName,
            rankMovement = currentRankMovement,
            valuationChange = currentValuationChange,
            explanation = currentExplanation,
            decisionState = opportunityDecisionStateFor(
                freshness = freshness,
                confidence = row.confidence,
                upsideBps = row.upsideBps,
                compositeScore = row.compositeScore,
                trustNote = currentTrustNote,
            ),
            quantLensSummary = detail?.let {
                buildRowQuantLensSummaryLocked(
                    detail = it,
                    opportunityRow = row,
                )
            },
        )
    }

    private fun buildSelectedQuantLensLocked(
        detail: SymbolDetail,
        selectedRange: ChartRange,
        opportunityRows: List<OpportunityListRow>,
        opportunityScoringModel: OpportunityScoringModel,
    ): QuantLensReport {
        val fingerprint = quantLensFingerprintLocked(detail, selectedRange, opportunityRows, opportunityScoringModel)
        quantLensCache[detail.symbol]?.takeIf { it.fingerprint == fingerprint }?.let { return it.report }

        val input = QuantLensInput(
            detail = detail,
            selectedRange = selectedRange,
            inputFingerprint = fingerprint,
            selectedCandlesByRange = ChartRange.entries.associateWith { range ->
                chartCache[chartKey(detail.symbol, range)].orEmpty()
            },
            chartSummaries = chartSummaries[detail.symbol].orEmpty(),
            dcfAnalysis = dcfCache[detail.symbol],
            revisions = revisions[detail.symbol].orEmpty(),
            opportunityRows = rankedOpportunityRowsLocked(opportunityScoringModel),
            comparableUniverse = comparableUniverseLocked(detail.symbol, opportunityRows),
            correlationSeries = correlationSeriesLocked(detail.symbol, selectedRange, opportunityRows),
            scoringModel = opportunityScoringModel,
            scoringVersion = opportunityScoringModel.ordinal,
            nowEpochSeconds = now(),
        )
        val report = QuantLensEngine.analyze(input)
        quantLensCache[detail.symbol] = QuantLensCacheEntry(fingerprint, report)
        return report
    }

    private fun quantLensFingerprintLocked(
        detail: SymbolDetail,
        selectedRange: ChartRange,
        opportunityRows: List<OpportunityListRow>,
        opportunityScoringModel: OpportunityScoringModel,
    ): String {
        val dcf = dcfCache[detail.symbol]
        val selectedCandlesByRange = ChartRange.entries.associateWith { range ->
            chartCache[chartKey(detail.symbol, range)].orEmpty()
        }
        val selectedChartHash = selectedCandlesByRange.entries
            .sortedBy { it.key.name }
            .joinToString(";") { (range, candles) -> "${range.name}:${quantLensCandleFingerprint(candles)}" }
        val selectedSummaryHash = chartSummaries[detail.symbol].orEmpty().entries
            .sortedBy { it.key.name }
            .joinToString(";") { (range, summary) ->
                listOf(
                    range.name,
                    summary.capturedAt,
                    summary.candleCount,
                    summary.latestCloseCents,
                    summary.ema20Cents,
                    summary.ema50Cents,
                    summary.ema200Cents,
                    summary.macdCents,
                    summary.signalCents,
                    summary.histogramCents,
                ).joinToString(":")
            }
        val comparableHash = comparableUniverseLocked(detail.symbol, opportunityRows)
            .joinToString(";") {
                listOf(
                    it.symbol,
                    it.valuationUpsideBps,
                    it.evidenceStrengthBps,
                    it.opportunityScore,
                    it.trendReliabilityBps,
                    it.evSpreadBps,
                ).joinToString(":")
            }
        val correlationHash = correlationSeriesLocked(detail.symbol, selectedRange, opportunityRows)
            .joinToString(";") { "${it.symbol}:${it.range.name}:${quantLensCandleFingerprint(it.candles)}" }
        return listOf(
            currentProfile,
            trackedSymbols.joinToString(","),
            QuantLensModelVersion.CURRENT,
            detail.symbol,
            selectedRange.name,
            detail.marketPriceCents,
            detail.intrinsicValueCents,
            detail.upsideBps,
            detail.externalSignalLowFairValueCents,
            detail.externalSignalFairValueCents,
            detail.weightedExternalSignalFairValueCents,
            detail.externalSignalHighFairValueCents,
            dcf?.bearIntrinsicValueCents,
            dcf?.baseIntrinsicValueCents,
            dcf?.bullIntrinsicValueCents,
            dcf?.source,
            dcf?.sourceFingerprint,
            selectedChartHash,
            selectedSummaryHash,
            quantLensRevisionFingerprint(revisions[detail.symbol].orEmpty()),
            opportunityScoringModel.name,
            comparableHash,
            correlationHash,
        ).joinToString("|")
    }

    private fun comparableUniverseLocked(
        selectedSymbol: String,
        opportunityRows: List<OpportunityListRow>,
    ): List<QuantLensComparable> {
        val opportunityBySymbol = opportunityRows.associateBy { it.symbol }
        val symbols = (trackedSymbols + opportunityRows.map { it.symbol } + selectedSymbol)
            .distinct()
            .sorted()
        return symbols.mapNotNull { symbol ->
            val detail = engine.detail(symbol) ?: return@mapNotNull null
            val opportunity = opportunityBySymbol[symbol]
            QuantLensComparable(
                symbol = symbol,
                valuationUpsideBps = detail.upsideBps,
                evidenceStrengthBps = evidenceOrdinalBps(detail.confidence),
                opportunityScore = opportunity?.compositeScore,
                trendReliabilityBps = chartSummaries[symbol]?.values?.maxOfOrNull { it.candleCount }?.coerceAtMost(100)
                    ?.times(100),
                evSpreadBps = quantLensEvSpreadBps(detail, dcfCache[symbol]),
            )
        }
    }

    private fun correlationSeriesLocked(
        selectedSymbol: String,
        selectedRange: ChartRange,
        opportunityRows: List<OpportunityListRow>,
    ): List<QuantLensCorrelationSeries> {
        val symbols = (trackedSymbols + opportunityRows.map { it.symbol })
            .distinct()
            .filterNot { it == selectedSymbol }
            .sorted()
        return symbols.mapNotNull { symbol ->
            val candles = chartCache[chartKey(symbol, selectedRange)].orEmpty()
            if (candles.isEmpty()) {
                null
            } else {
                QuantLensCorrelationSeries(symbol, selectedRange, candles)
            }
        }
    }

    private fun buildRowQuantLensSummaryLocked(
        detail: SymbolDetail,
        opportunityRow: com.discountscreener.core.model.OpportunityRow?,
    ): QuantLensRowSummary {
        val evidenceStatus = if (detail.marketPriceCents > 0L && detail.intrinsicValueCents > 0L) {
            if ((opportunityRow?.coverageCount ?: 0) >= 3 || detail.confidence == ConfidenceBand.High) {
                QuantLensPrimaryStatus.Available
            } else {
                QuantLensPrimaryStatus.Sparse
            }
        } else {
            QuantLensPrimaryStatus.Unavailable
        }
        val evidenceLabel = when (evidenceStatus) {
            QuantLensPrimaryStatus.Available -> QuantLensRowLabel.EvidenceStrong
            QuantLensPrimaryStatus.Sparse -> QuantLensRowLabel.EvidenceSparse
            else -> QuantLensRowLabel.EvidenceUnavailable
        }
        val states = mutableListOf(
            QuantLensLensRowState(
                lensId = QuantLensLensId.EvidenceStrength,
                primaryStatus = evidenceStatus,
                band = evidenceLabel.name,
                label = evidenceLabel,
                reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
            ),
        )

        val analystAnchors = listOfNotNull(
            detail.externalSignalLowFairValueCents,
            detail.weightedExternalSignalFairValueCents ?: detail.externalSignalFairValueCents,
            detail.externalSignalHighFairValueCents,
        ).filter { it > 0L }
        val dcf = dcfCache[detail.symbol]
        val dcfAnchors = listOfNotNull(
            dcf?.bearIntrinsicValueCents,
            dcf?.baseIntrinsicValueCents,
            dcf?.bullIntrinsicValueCents,
        ).filter { it > 0L }
        if (detail.marketPriceCents > 0L && (dcfAnchors.size == 3 || analystAnchors.size == 3)) {
            val anchors = if (dcfAnchors.size == 3) dcfAnchors else analystAnchors
            states += QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = QuantLensPrimaryStatus.Available,
                band = QuantLensRowLabel.EvRange.name,
                label = QuantLensRowLabel.EvRange,
                reasonCodes = listOf(QuantLensReasonCode.CompleteScenarioAnchors),
                evLowUpsideBps = boundedQuantLensRowUpsideBps(detail.marketPriceCents, anchors.first()),
                evHighUpsideBps = boundedQuantLensRowUpsideBps(detail.marketPriceCents, anchors.last()),
            )
        } else {
            val evLabel = if (detail.marketPriceCents > 0L) QuantLensRowLabel.EvSparse else QuantLensRowLabel.EvUnavailable
            states += QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = if (detail.marketPriceCents > 0L) {
                    QuantLensPrimaryStatus.Sparse
                } else {
                    QuantLensPrimaryStatus.Unavailable
                },
                band = evLabel.name,
                label = evLabel,
                reasonCodes = listOf(
                    if (detail.marketPriceCents > 0L) {
                        QuantLensReasonCode.MissingScenarioAnchors
                    } else {
                        QuantLensReasonCode.MissingMarketPrice
                    },
                ),
            )
        }

        states += QuantLensLensRowState(
            lensId = QuantLensLensId.CorrelationRisk,
            primaryStatus = QuantLensPrimaryStatus.Unavailable,
            band = QuantLensRowLabel.CorrUnavailable.name,
            label = QuantLensRowLabel.CorrUnavailable,
            reasonCodes = listOf(QuantLensReasonCode.InsufficientLocalHistory),
        )

        val trendSummary = chartSummaries[detail.symbol]?.values?.maxByOrNull { it.candleCount }
        if (trendSummary != null && trendSummary.candleCount >= 20) {
            states += QuantLensLensRowState(
                lensId = QuantLensLensId.TrendReliability,
                primaryStatus = QuantLensPrimaryStatus.Available,
                band = QuantLensRowLabel.TrendModerate.name,
                label = QuantLensRowLabel.TrendModerate,
                reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
            )
        } else {
            states += QuantLensLensRowState(
                lensId = QuantLensLensId.TrendReliability,
                primaryStatus = QuantLensPrimaryStatus.Sparse,
                band = QuantLensRowLabel.TrendSparse.name,
                label = QuantLensRowLabel.TrendSparse,
                reasonCodes = listOf(QuantLensReasonCode.InsufficientTrendSamples),
            )
        }

        states += QuantLensLensRowState(
            lensId = QuantLensLensId.SimilarSetups,
            primaryStatus = QuantLensPrimaryStatus.Sparse,
            band = QuantLensRowLabel.SimilarSparse.name,
            label = QuantLensRowLabel.SimilarSparse,
            reasonCodes = listOf(QuantLensReasonCode.InsufficientComparables),
        )

        return QuantLensRowSummary(
            symbol = detail.symbol,
            fingerprint = listOf(
                detail.symbol,
                detail.marketPriceCents,
                detail.intrinsicValueCents,
                detail.upsideBps,
                opportunityRow?.coverageCount,
                opportunityRow?.compositeScore,
                dcfCache[detail.symbol]?.sourceFingerprint,
                trendSummary?.candleCount,
            ).joinToString("|"),
            lensStates = states,
        )
    }

    private fun evidenceOrdinalBps(confidence: ConfidenceBand): Int = when (confidence) {
        ConfidenceBand.High -> 8_000
        ConfidenceBand.Provisional -> 5_500
        ConfidenceBand.Low -> 3_000
    }

    private fun captureRefreshComparisonBaselineLocked() {
        comparisonBaselineRankBySymbol.clear()
        comparisonBaselineWeightedFairValueBySymbol.clear()
        comparisonBaselineMarketPriceBySymbol.clear()
        comparisonBaselineOpportunityRankByModel.values.forEach { it.clear() }
        val issueMessagesBySymbol = activeIssueMessagesBySymbolLocked()
        rankedTrackedRowsLocked(issueMessagesBySymbol)
            .forEachIndexed { index, row ->
                comparisonBaselineRankBySymbol[row.symbol] = index
            }
        OpportunityScoringModel.entries.forEach { scoringModel ->
            rankedOpportunityRowsLocked(scoringModel).forEachIndexed { index, row ->
                comparisonBaselineOpportunityRankByModel.getValue(scoringModel)[row.symbol] = index
            }
        }
        trackedSymbols.forEach { symbol ->
            engine.detail(symbol)?.let { detail ->
                preferredAnalystTargetFairValueCents(detail)
                    ?.let { comparisonBaselineWeightedFairValueBySymbol[symbol] = it }
                comparisonBaselineMarketPriceBySymbol[symbol] = detail.marketPriceCents
            }
        }
    }

    private fun activeIssueMessagesBySymbolLocked(): Map<String, String> = issues.values
        .filter { it.active }
        .associateBy({ it.key.substringBefore(':', it.key) }, { it.detail })

    private fun hydrateWarmStartLocked(bootstrap: PersistenceBootstrap) {
        val trackedSymbolSet = trackedSymbols.toSet()
        val hydratedStates = bootstrap.symbolStates.filter { it.symbol in trackedSymbolSet }
        val watchlist = bootstrap.watchlist.filter { it in trackedSymbolSet }

        engine.restore(
            PersistedReportState(
                trackedSymbols = trackedSymbols,
                watchlist = watchlist,
                symbolStates = hydratedStates,
            ),
        )

        staleSymbols += hydratedStates.map { it.symbol }
        bootstrap.lastPersistedAtEpochSeconds?.let { restoredAt ->
            hydratedStates.forEach { state ->
                freshnessTimestampBySymbol[state.symbol] = restoredAt
            }
        }

        hydratedStates.forEach { state ->
            state.dcfAnalysis?.let { dcfCache[state.symbol] = it }
        }

        chartCache.clear()
        chartSummaries.clear()
        bootstrap.chartCache
            .filter { it.symbol in trackedSymbolSet }
            .forEach { chart ->
                chartCache[chartKey(chart.symbol, chart.range)] = chart.candles
                chartSummaries.getOrPut(chart.symbol) { linkedMapOf() }[chart.range] =
                    ChartAnalysis.buildSummary(
                        chart.range,
                        chart.candles,
                        bootstrap.lastPersistedAtEpochSeconds ?: chart.fetchedAt,
                    )
            }

        issues.clear()
        bootstrap.issues
            .filter { issueAppliesToUniverse(it.key, trackedSymbolSet) }
            .forEach { issues[it.key] = it }
        lastUpdatedAtEpochSeconds = bootstrap.lastPersistedAtEpochSeconds
    }

    private suspend fun ensureRevisionHistoryLoaded(symbol: String) {
        val loaded = stateStore.loadRevisionHistory(symbol)
        stateMutex.withLock {
            val persistedHistory = loaded.mapNotNull { persisted ->
                val detail = buildSymbolDetail(
                    snapshot = persisted.payload.snapshot,
                    externalSignal = persisted.payload.externalSignal,
                    fundamentals = persisted.payload.fundamentals,
                    lastSequence = persisted.lastSequence,
                    updateCount = persisted.updateCount,
                    isWatched = persisted.payload.isWatched,
                ) ?: return@mapNotNull null
                SymbolRevision(
                    symbol = persisted.symbol,
                    evaluatedAtEpochSeconds = persisted.evaluatedAt,
                    detail = detail,
                    chartSummaries = persisted.payload.chartSummaries.associateBy { it.range },
                    dcfAnalysis = persisted.payload.dcfAnalysis,
                )
            }
            val mergedHistory = mergeRevisionHistory(persistedHistory, revisions[symbol].orEmpty())
            if (mergedHistory.isNotEmpty()) {
                revisions[symbol] = mergedHistory
            }
        }
    }

    private suspend fun hydratePricingHistoryForDetail(symbol: String) {
        val loaded = stateStore.loadPricingHistory(symbol)
        if (loaded.isEmpty()) return
        stateMutex.withLock {
            loaded.forEach { chart ->
                val key = chartKey(chart.symbol, chart.range)
                val mergedCandles = mergeHistoricalCandles(
                    symbol = chart.symbol,
                    range = chart.range,
                    persistedCandles = chartCache[key].orEmpty(),
                    incomingCandles = chart.candles,
                )
                chartCache[key] = mergedCandles
                chartSummaries.getOrPut(chart.symbol) { linkedMapOf() }[chart.range] =
                    ChartAnalysis.buildSummary(chart.range, mergedCandles, chart.fetchedAt)
            }
        }
    }

    private fun appendRevisionLocked(symbol: String) {
        val detail = engine.detail(symbol) ?: return
        val history = revisions.getOrPut(symbol) { mutableListOf() }
        history += SymbolRevision(
            symbol = symbol,
            evaluatedAtEpochSeconds = now(),
            detail = detail,
            chartSummaries = chartSummaries[symbol].orEmpty(),
            dcfAnalysis = dcfCache[symbol],
        )
        while (history.size > MAX_REVISION_HISTORY) {
            history.removeAt(0)
        }
    }

    private fun snapshotPersistenceDeltaLocked(
        rawCaptures: List<RawCapture>,
        symbol: String,
    ): PersistenceDelta {
        val revision = buildRevisionInputLocked(symbol)
        return PersistenceDelta(
            rawCaptures = rawCaptures,
            revisions = listOfNotNull(revision),
            issues = issues.values.toList(),
        )
    }

    private fun buildRevisionInputLocked(symbol: String): SymbolRevisionInput? {
        val persisted = engine.persistedState().firstOrNull { it.symbol == symbol } ?: return null
        val detail = engine.detail(symbol) ?: return null
        return SymbolRevisionInput(
            symbol = symbol,
            evaluatedAt = now(),
            lastSequence = persisted.lastSequence,
            updateCount = persisted.updateCount,
            priceHistory = persisted.priceHistory,
            payload = EvaluatedSymbolState(
                snapshot = persisted.snapshot,
                externalSignal = persisted.externalSignal,
                fundamentals = persisted.fundamentals,
                gapBps = detail.gapBps,
                qualification = detail.qualification,
                externalStatus = detail.externalStatus,
                chartSummaries = chartSummaries[symbol].orEmpty().values.toList(),
                dcfAnalysis = dcfCache[symbol],
                coreStatus = MetricGroupStatus(available = persisted.snapshot != null, stale = symbol in staleSymbols),
                fundamentalsStatus = MetricGroupStatus(available = persisted.fundamentals != null, stale = symbol in staleSymbols),
                relativeStatus = MetricGroupStatus(available = false, stale = symbol in staleSymbols),
                dcfStatus = MetricGroupStatus(available = dcfCache[symbol] != null, stale = symbol in staleSymbols),
                chartStatus = MetricGroupStatus(available = chartSummaries[symbol]?.isNotEmpty() == true, stale = symbol in staleSymbols),
                isWatched = engine.isWatched(symbol),
            ),
        )
    }

    private suspend fun persistDelta(delta: PersistenceDelta) {
        if (delta.rawCaptures.isNotEmpty() || delta.revisions.isNotEmpty()) {
            stateStore.persistBatch(delta.rawCaptures, delta.revisions)
        }
        stateStore.replaceIssues(delta.issues)
    }

    private fun queuePersist(delta: PersistenceDelta) {
        repositoryScope.launch {
            persistDelta(delta)
        }
    }

    private suspend fun cancelActiveProfileWork() {
        val (previousSwitchJob, previousRefreshJob, previousEnrichmentJob) = stateMutex.withLock {
            val existingSwitch = activeProfileSwitchJob
            activeProfileSwitchJob = null
            val existingRefresh = activeRefreshJob
            activeRefreshJob = null
            val existingEnrichment = activeEnrichmentJob
            activeEnrichmentJob = null
            Triple(existingSwitch, existingRefresh, existingEnrichment)
        }
        previousSwitchJob?.cancelAndJoin()
        previousRefreshJob?.cancelAndJoin()
        previousEnrichmentJob?.cancelAndJoin()
    }

    private fun applyTransitionLocked(feedback: com.discountscreener.android.domain.model.ProfileTransitionFeedback) {
        startupPhase = feedback.startupPhase
        refreshCompletedSymbols = feedback.refreshCompletedSymbols
        refreshTargetSymbols = feedback.refreshTargetSymbols
        statusMessage = feedback.statusMessage
    }

    private fun applyDiagnosticsLocked(
        symbol: String,
        diagnostics: List<ProviderDiagnostic>,
        chartError: Throwable?,
        suppressQuoteHtml404: Boolean = false,
        suppressCoreMissing: Boolean = false,
    ) {
        issues.keys.filter { it.startsWith("$symbol:") }.toList().forEach(issues::remove)
        diagnostics
            .filterNot { diagnostic ->
                (suppressQuoteHtml404 && isSuppressibleQuoteHtml404(diagnostic)) ||
                    (suppressCoreMissing && isSuppressibleCoreMissing(diagnostic))
            }
            .filter { diagnostic ->
                diagnostic.kind == "error" ||
                    (diagnostic.component == "core" && diagnostic.kind == "missing")
            }
            .forEach { diagnostic ->
                recordIssueLocked(
                    key = "$symbol:provider:${diagnostic.component}",
                    severity = if (diagnostic.kind == "missing") {
                        PersistenceIssueSeverity.Warning
                    } else {
                        PersistenceIssueSeverity.Error
                    },
                    title = if (diagnostic.kind == "missing") "Provider missing" else "Provider error",
                    detail = diagnostic.detail,
                )
            }

        if (chartError != null) {
            recordIssueLocked(
                key = "$symbol:chart:${ChartRange.Year.name}",
                severity = PersistenceIssueSeverity.Error,
                title = "Chart load failed",
                detail = chartError.message ?: "chart request failed",
            )
        }
    }

    private fun recordIssueLocked(
        key: String,
        severity: PersistenceIssueSeverity,
        title: String,
        detail: String,
    ) {
        issueEventCounter += 1
        val existing = issues[key]
        issues[key] = PersistedIssueRecord(
            key = key,
            source = PersistenceIssueSource.Feed,
            severity = severity,
            title = title,
            detail = detail,
            count = (existing?.count ?: 0) + 1,
            firstSeenEvent = existing?.firstSeenEvent ?: issueEventCounter,
            lastSeenEvent = issueEventCounter,
            active = true,
        )
    }

    internal fun fallbackSnapshotFromCachedDetail(
        symbol: String,
        detail: SymbolDetail?,
        chartCandles: List<HistoricalCandle>?,
    ): MarketSnapshot? {
        val cachedDetail = detail ?: return null
        val latestCloseCents = chartCandles?.lastOrNull()?.closeCents ?: return null
        if (latestCloseCents <= 0L) return null
        return MarketSnapshot(
            symbol = symbol,
            companyName = cachedDetail.companyName,
            profitable = cachedDetail.profitable,
            marketPriceCents = latestCloseCents,
            intrinsicValueCents = cachedDetail.intrinsicValueCents,
        )
    }

    internal fun isSuppressibleQuoteHtml404(diagnostic: ProviderDiagnostic): Boolean =
        diagnostic.component == "quoteHtml" &&
            diagnostic.kind == "error" &&
            diagnostic.detail.contains("HTTP 404") &&
            diagnostic.detail.contains("finance.yahoo.com/quote/")

    internal fun isSuppressibleCoreMissing(diagnostic: ProviderDiagnostic): Boolean =
        diagnostic.component == "core" &&
            diagnostic.kind == "missing" &&
            diagnostic.detail.contains("core snapshot is missing")

    internal fun dcfFallbackFromTimeseries(
        symbol: String,
        companyName: String?,
        providerFundamentals: FundamentalSnapshot?,
        chartCandles: List<HistoricalCandle>?,
        timeseries: FundamentalTimeseries,
    ): TimeseriesFallback? {
        val latestShares = timeseries.dilutedAverageShares.lastOrNull()?.value?.takeIf { it > 0.0 }
            ?: providerFundamentals?.sharesOutstanding?.toDouble()
            ?: return null
        val latestNetIncome = timeseries.netIncome.lastOrNull()?.value ?: return null
        val marketPriceCents = chartCandles?.lastOrNull()?.closeCents?.takeIf { it > 0L }
            ?: providerFundamentals?.marketCapDollars
                ?.takeIf { it > 0L }
                ?.let { marketCap ->
                    ((marketCap.toDouble() / latestShares) * 100.0)
                        .takeIf { it.isFinite() && it > 0.0 }
                        ?.roundToLong()
                }
            ?: return null
        val derivedFundamentals = FundamentalSnapshot(
            symbol = symbol,
            marketCapDollars = providerFundamentals?.marketCapDollars
                ?: ((marketPriceCents / 100.0) * latestShares).roundToLong().takeIf { it > 0L },
            sharesOutstanding = providerFundamentals?.sharesOutstanding ?: latestShares.roundToLong().takeIf { it > 0L },
            freeCashFlowDollars = timeseries.freeCashFlow.lastOrNull()?.value?.roundToLong(),
            operatingCashFlowDollars = timeseries.operatingCashFlow.lastOrNull()?.value?.roundToLong(),
            trailingEpsCents = ((latestNetIncome / latestShares) * 100.0).roundToLong(),
        )
        val fundamentals = mergeFundamentals(providerFundamentals, derivedFundamentals)
        val analysis = DcfAnalysisEngine.compute(fundamentals, timeseries).getOrNull() ?: return null
        return TimeseriesFallback(
            snapshot = MarketSnapshot(
                symbol = symbol,
                companyName = companyName,
                profitable = latestNetIncome > 0.0,
                marketPriceCents = marketPriceCents,
                intrinsicValueCents = analysis.baseIntrinsicValueCents,
            ),
            fundamentals = fundamentals,
            timeseries = timeseries,
            analysis = analysis,
        )
    }

    private suspend fun resolveDcfFallback(
        symbol: String,
        companyName: String?,
        providerFundamentals: FundamentalSnapshot?,
        chartCandles: List<HistoricalCandle>?,
    ): TimeseriesFallback? {
        val selection = dcfSourceCoordinator.resolve(symbol) { timeseries ->
            dcfFallbackFromTimeseries(
                symbol = symbol,
                companyName = companyName,
                providerFundamentals = providerFundamentals,
                chartCandles = chartCandles,
                timeseries = timeseries,
            )?.analysis
        }
        val selectedTimeseries = selection.timeseries ?: return null
        val fallback = dcfFallbackFromTimeseries(
            symbol = symbol,
            companyName = companyName,
            providerFundamentals = providerFundamentals,
            chartCandles = chartCandles,
            timeseries = selectedTimeseries,
        ) ?: return null
        return fallback.copy(analysis = selection.analysis ?: fallback.analysis)
    }

    private fun mergeFundamentals(
        existing: FundamentalSnapshot?,
        derived: FundamentalSnapshot,
    ): FundamentalSnapshot = existing?.copy(
        sectorKey = existing.sectorKey ?: derived.sectorKey,
        sectorName = existing.sectorName ?: derived.sectorName,
        industryKey = existing.industryKey ?: derived.industryKey,
        industryName = existing.industryName ?: derived.industryName,
        marketCapDollars = existing.marketCapDollars ?: derived.marketCapDollars,
        sharesOutstanding = existing.sharesOutstanding ?: derived.sharesOutstanding,
        trailingPeHundredths = existing.trailingPeHundredths ?: derived.trailingPeHundredths,
        forwardPeHundredths = existing.forwardPeHundredths ?: derived.forwardPeHundredths,
        priceToBookHundredths = existing.priceToBookHundredths ?: derived.priceToBookHundredths,
        returnOnEquityBps = existing.returnOnEquityBps ?: derived.returnOnEquityBps,
        ebitdaDollars = existing.ebitdaDollars ?: derived.ebitdaDollars,
        enterpriseValueDollars = existing.enterpriseValueDollars ?: derived.enterpriseValueDollars,
        enterpriseToEbitdaHundredths = existing.enterpriseToEbitdaHundredths ?: derived.enterpriseToEbitdaHundredths,
        totalDebtDollars = existing.totalDebtDollars ?: derived.totalDebtDollars,
        totalCashDollars = existing.totalCashDollars ?: derived.totalCashDollars,
        debtToEquityHundredths = existing.debtToEquityHundredths ?: derived.debtToEquityHundredths,
        freeCashFlowDollars = existing.freeCashFlowDollars ?: derived.freeCashFlowDollars,
        operatingCashFlowDollars = existing.operatingCashFlowDollars ?: derived.operatingCashFlowDollars,
        betaMillis = existing.betaMillis ?: derived.betaMillis,
        trailingEpsCents = existing.trailingEpsCents ?: derived.trailingEpsCents,
        earningsGrowthBps = existing.earningsGrowthBps ?: derived.earningsGrowthBps,
    ) ?: derived

    private fun issueAppliesToUniverse(key: String, trackedSymbols: Set<String>): Boolean {
        val symbol = key.substringBefore(':', "")
        return symbol.isEmpty() || symbol in trackedSymbols
    }

    private fun reorderSymbolsByPersistedRanking(symbols: List<String>): List<String> {
        if (symbols.isEmpty()) {
            return emptyList()
        }
        val rankedSymbols = engine.topRows(engine.symbolCount())
            .map { it.symbol }
            .filter { it in symbols }
            .toMutableList()
        symbols.forEach { symbol ->
            if (symbol !in rankedSymbols) {
                rankedSymbols += symbol
            }
        }
        return rankedSymbols
    }

    private suspend fun emitUpdate() {
        updates.emit(updates.value + 1)
    }

    private fun toIssueRecord(issue: PersistedIssueRecord): IssueRecord =
        IssueRecord(
            key = issue.key,
            title = issue.title,
            detail = issue.detail,
            severity = issue.severity.name.lowercase(),
            active = issue.active,
            count = issue.count,
            lastSeenEpochSeconds = issue.lastSeenEvent.toLong(),
        )

    private suspend fun startEnrichment(symbols: List<String>, generation: Long) {
        if (symbols.isEmpty()) return
        stateMutex.withLock {
            activeEnrichmentJob = repositoryScope.launch {
                try {
                    runEnrichment(symbols, generation)
                } finally {
                    stateMutex.withLock {
                        if (generation == activeProfileGeneration) {
                            activeEnrichmentJob = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun runEnrichment(symbols: List<String>, generation: Long) = coroutineScope {
        symbols
            .asFlow()
            .flatMapMerge(concurrency = REFRESH_CONCURRENCY) { symbol ->
                flow { emit(enrichSymbol(symbol, generation)) }
            }
            .collect { result ->
                val isActiveGeneration = stateMutex.withLock { result.generation == activeProfileGeneration }
                if (!isActiveGeneration) {
                    return@collect
                }
                val delta = stateMutex.withLock { applyEnrichmentResultLocked(result) }
                if (delta.rawCaptures.isNotEmpty() || delta.revisions.isNotEmpty()) {
                    queuePersist(delta)
                }
                emitUpdate()
            }
    }

    private suspend fun enrichSymbol(symbol: String, generation: Long): EnrichmentResult {
        val chartCaptures = mutableListOf<Pair<ChartRange, List<HistoricalCandle>>>()
        val errors = mutableListOf<ProviderDiagnostic>()

        val missingRanges = stateMutex.withLock {
            ChartRange.entries.filter { range ->
                chartCache[chartKey(symbol, range)] == null
            }
        }

        for (range in missingRanges) {
            try {
                val candles = yahooClient.fetchHistoricalCandles(symbol, range)
                if (candles.isNotEmpty()) {
                    chartCaptures += range to candles
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                errors += ProviderDiagnostic(
                    component = "enrichment",
                    kind = "error",
                    detail = "chart ${range.name} for $symbol: ${error.message ?: "failed"}",
                    retryable = false,
                )
            }
        }

        var timeseries: FundamentalTimeseries? = null
        var dcfAnalysis: DcfAnalysis? = null

        val needsTimeseries = stateMutex.withLock { timeseriesCache[symbol] == null }
        if (needsTimeseries) {
            try {
                val fundamentals = stateMutex.withLock { engine.detail(symbol)?.fundamentals }
                if (fundamentals != null) {
                    val selection = dcfSourceCoordinator.resolve(symbol) { selectedTimeseries ->
                        DcfAnalysisEngine.compute(fundamentals, selectedTimeseries).getOrNull()
                    }
                    timeseries = selection.timeseries
                    dcfAnalysis = selection.analysis
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                errors += ProviderDiagnostic(
                    component = "enrichment",
                    kind = "error",
                    detail = "timeseries for $symbol: ${error.message ?: "failed"}",
                    retryable = false,
                )
            }
        }

        return EnrichmentResult(
            generation = generation,
            symbol = symbol,
            chartCaptures = chartCaptures,
            timeseries = timeseries,
            dcfAnalysis = dcfAnalysis,
            errors = errors,
        )
    }

    private fun applyEnrichmentResultLocked(result: EnrichmentResult): PersistenceDelta {
        val rawCaptures = mutableListOf<RawCapture>()
        val capturedAt = now()

        for ((range, candles) in result.chartCaptures) {
            val key = chartKey(result.symbol, range)
            val mergedCandles = mergeHistoricalCandles(
                symbol = result.symbol,
                range = range,
                persistedCandles = chartCache[key].orEmpty(),
                incomingCandles = candles,
            )
            chartCache[key] = mergedCandles
            chartSummaries.getOrPut(result.symbol) { linkedMapOf() }[range] =
                ChartAnalysis.buildSummary(range, mergedCandles, capturedAt)
            rawCaptures += RawCapture(
                symbol = result.symbol,
                captureKind = CaptureKind.ChartCandles,
                scopeKey = range.name,
                capturedAt = capturedAt,
                payload = RawCapturePayload.Chart(range, candles),
            )
        }

        result.timeseries?.let { ts ->
            timeseriesCache[result.symbol] = ts
            rawCaptures += fundamentalTimeseriesCapture(
                symbol = result.symbol,
                timeseries = ts,
                analysis = result.dcfAnalysis,
                capturedAt = capturedAt,
            )
        }
        result.dcfAnalysis?.let { analysis ->
            dcfCache[result.symbol] = analysis
        }

        for (error in result.errors) {
            recordIssueLocked(
                key = "${result.symbol}:${error.component}:${error.kind}",
                severity = PersistenceIssueSeverity.Warning,
                title = "Enrichment failed",
                detail = error.detail,
            )
        }

        appendRevisionLocked(result.symbol)
        return snapshotPersistenceDeltaLocked(rawCaptures, result.symbol)
    }

    private fun resetInMemoryLocked() {
        engine = ReportingEngine()
        trackedSymbols.clear()
        revisions.clear()
        chartCache.clear()
        chartSummaries.clear()
        dcfCache.clear()
        timeseriesCache.clear()
        quantLensCache.clear()
        issues.clear()
        staleSymbols.clear()
        placeholderSymbols.clear()
        refreshedSymbols.clear()
        refreshAttemptedSymbols.clear()
        comparisonBaselineRankBySymbol.clear()
        comparisonBaselineOpportunityRankByModel.values.forEach { it.clear() }
        comparisonBaselineWeightedFairValueBySymbol.clear()
        comparisonBaselineMarketPriceBySymbol.clear()
        freshnessTimestampBySymbol.clear()
        activeProfileSwitchJob = null
        activeRefreshJob = null
        activeEnrichmentJob = null
        refreshCompletedSymbols = 0
        refreshTargetSymbols = 0
        lastUpdatedAtEpochSeconds = null
        startupPhase = DashboardStartupPhase.Restoring
    }

    private fun chartKey(symbol: String, range: ChartRange): String = "$symbol|${range.name}"

    private fun now(): Long = nowProvider()

    private fun isRetryable(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return error is IOException || message.contains("HTTP 429") || message.contains("HTTP 5")
    }

    override suspend fun loadSystemStats(): SystemStats = stateStore.getSystemStats()

    override suspend fun pruneOldRevisions(retentionDays: Int): Int =
        stateStore.pruneOldRevisions(retentionDays)

    override suspend fun clearAllData() {
        cancelActiveProfileWork()
        stateStore.resetWarmStartState()
        stateMutex.withLock { resetInMemoryLocked() }
        emitUpdate()
    }

    override suspend fun dcfSnapshot(): Map<String, DcfAnalysis> = stateMutex.withLock { dcfCache.toMap() }

    override suspend fun trackedSymbolDetails(): List<SymbolDetail> = stateMutex.withLock {
        trackedSymbols.mapNotNull { engine.detail(it) }
    }

    override suspend fun saveEstimatesSnapshot(report: IndexEstimatesReport) {
        stateStore.saveEstimatesSnapshot(report)
    }

    override suspend fun estimatesHistory(profileName: String): List<IndexEstimatesReport> =
        stateStore.getEstimatesHistory(profileName)

    companion object {
        private const val DEFAULT_PROFILE = "sp500"
        private const val REFRESH_CONCURRENCY = 8
        private const val MAX_RETRY_ROUNDS = 2
        private const val MAX_REVISION_HISTORY = 240
    }
}

private fun fundamentalTimeseriesCapture(
    symbol: String,
    timeseries: FundamentalTimeseries,
    analysis: DcfAnalysis?,
    capturedAt: Long,
) = RawCapture(
    symbol = symbol,
    captureKind = CaptureKind.FundamentalTimeseries,
    scopeKey = analysis?.source?.name ?: "unknown",
    capturedAt = capturedAt,
    payload = RawCapturePayload.FundamentalTimeseries(
        value = timeseries,
        provenance = analysis?.provenance ?: DataProvenance(),
    ),
)

private const val QUANT_LENS_ROW_MIN_UPSIDE_BPS = -100_000
private const val QUANT_LENS_ROW_MAX_UPSIDE_BPS = 100_000

internal fun mergeRevisionHistory(
    persistedHistory: List<SymbolRevision>,
    runtimeHistory: List<SymbolRevision>,
): MutableList<SymbolRevision> = (persistedHistory + runtimeHistory)
    .sortedWith(compareBy<SymbolRevision> { it.evaluatedAtEpochSeconds }.thenBy { revisionHistoryKey(it) })
    .distinctBy(::revisionHistoryKey)
    .toMutableList()

internal fun mergeHistoricalCandles(
    symbol: String,
    range: ChartRange,
    persistedCandles: List<HistoricalCandle>,
    incomingCandles: List<HistoricalCandle>,
): List<HistoricalCandle> = PricingHistoryMerge.merge(
    existing = persistedCandles.map { PricingCandle(symbol, range, it) },
    incoming = incomingCandles.map { PricingCandle(symbol, range, it) },
).map { it.candle }

internal fun rowFreshnessFor(
    hasDetail: Boolean,
    issueMessage: String?,
    isRefreshed: Boolean,
    stale: Boolean,
    startupPhase: DashboardStartupPhase,
): RowFreshness = when {
    !hasDetail && issueMessage != null -> RowFreshness.Issue
    !hasDetail -> RowFreshness.Loading
    startupPhase in setOf(DashboardStartupPhase.SwitchingProfile, DashboardStartupPhase.Refreshing) && !isRefreshed ->
        RowFreshness.Updating
    stale && startupPhase == DashboardStartupPhase.ShowingCached -> RowFreshness.Restored
    stale -> RowFreshness.Stale
    issueMessage != null -> RowFreshness.Issue
    isRefreshed -> RowFreshness.Updated
    else -> RowFreshness.Updated
}

internal fun boundedQuantLensRowUpsideBps(marketPriceCents: Long, fairValueCents: Long): Int? =
    checkedUpsideBps(marketPriceCents, fairValueCents)?.coerceIn(QUANT_LENS_ROW_MIN_UPSIDE_BPS, QUANT_LENS_ROW_MAX_UPSIDE_BPS)

internal fun quantLensRevisionFingerprint(history: List<SymbolRevision>): String = history
    .sortedWith(compareBy<SymbolRevision> { it.evaluatedAtEpochSeconds }.thenBy { it.symbol })
    .joinToString(";") { revision ->
        val detail = revision.detail
        listOf(
            revision.symbol,
            revision.evaluatedAtEpochSeconds,
            detail.marketPriceCents,
            detail.intrinsicValueCents,
            detail.upsideBps,
            detail.externalSignalLowFairValueCents,
            detail.externalSignalFairValueCents,
            detail.weightedExternalSignalFairValueCents,
            detail.externalSignalHighFairValueCents,
            detail.weightedAnalystCount,
            revision.dcfAnalysis?.bearIntrinsicValueCents,
            revision.dcfAnalysis?.baseIntrinsicValueCents,
            revision.dcfAnalysis?.bullIntrinsicValueCents,
            revision.dcfAnalysis?.source,
            revision.dcfAnalysis?.sourceFingerprint,
            revision.chartSummaries.entries.sortedBy { it.key.name }.joinToString(",") { (range, summary) ->
                listOf(range.name, summary.candleCount, summary.latestCloseCents, summary.capturedAt).joinToString(":")
            },
        ).joinToString(":")
    }

private const val FNV_64_OFFSET_BASIS = -3_750_763_034_362_895_579L
private const val FNV_64_PRIME = 1_099_511_628_211L

internal fun quantLensCandleFingerprint(candles: List<HistoricalCandle>): String {
    val canonicalCandles = canonicalizeQuantLensCandlesByEpoch(candles)
    var hash = FNV_64_OFFSET_BASIS
    hash = quantLensFingerprintHashLong(hash, canonicalCandles.size.toLong())
    for (candle in canonicalCandles) {
        hash = quantLensFingerprintHashLong(hash, candle.epochSeconds)
        hash = quantLensFingerprintHashLong(hash, candle.openCents)
        hash = quantLensFingerprintHashLong(hash, candle.highCents)
        hash = quantLensFingerprintHashLong(hash, candle.lowCents)
        hash = quantLensFingerprintHashLong(hash, candle.closeCents)
        hash = quantLensFingerprintHashLong(hash, candle.volume)
    }
    return "${canonicalCandles.size}:$hash"
}

private fun canonicalizeQuantLensCandlesByEpoch(candles: List<HistoricalCandle>): List<HistoricalCandle> = candles
    .sortedWith(
        compareBy<HistoricalCandle> { it.epochSeconds }
            .thenBy { it.openCents }
            .thenBy { it.highCents }
            .thenBy { it.lowCents }
            .thenBy { it.closeCents }
            .thenBy { it.volume },
    )
    .distinctBy { it.epochSeconds }

private fun quantLensFingerprintHashLong(seed: Long, value: Long): Long {
    var hash = seed
    var shift = 0
    while (shift < 64) {
        hash = (hash xor ((value ushr shift) and 0xffL)) * FNV_64_PRIME
        shift += 8
    }
    return hash
}

internal fun quantLensEvSpreadBps(detail: SymbolDetail, dcfAnalysis: DcfAnalysis?): Int? {
    val dcfAnchors = dcfAnalysis?.let {
        listOf(it.bearIntrinsicValueCents, it.baseIntrinsicValueCents, it.bullIntrinsicValueCents)
    }.orEmpty().filter { it > 0L }
    val analystAnchors = listOfNotNull(
        detail.externalSignalLowFairValueCents,
        detail.weightedExternalSignalFairValueCents ?: detail.externalSignalFairValueCents,
        detail.externalSignalHighFairValueCents,
    ).filter { it > 0L }
    val anchors = when {
        dcfAnchors.size == 3 -> dcfAnchors
        analystAnchors.size == 3 -> analystAnchors
        else -> return null
    }
    return checkedUpsideBps(anchors.first().coerceAtLeast(1L), anchors.last())
        ?.coerceAtLeast(0)
        ?.coerceAtMost(QUANT_LENS_ROW_MAX_UPSIDE_BPS)
}

internal fun trackedDecisionStateFor(
    state: TrackedRowState,
    freshness: RowFreshness,
    qualification: QualificationStatus?,
    confidence: ConfidenceBand?,
    upsideBps: Int?,
    trustNote: String?,
): RowDecisionState? = when {
    state != TrackedRowState.Live -> null
    freshness != RowFreshness.Updated -> null
    qualification == QualificationStatus.Unprofitable -> RowDecisionState.Avoid
    upsideBps != null && upsideBps <= 0 -> RowDecisionState.Avoid
    trustNote != null -> RowDecisionState.Watch
    qualification == QualificationStatus.Qualified &&
        confidence == ConfidenceBand.High &&
        upsideBps != null &&
        upsideBps > 0 -> RowDecisionState.Act
    else -> RowDecisionState.Watch
}

internal fun opportunityDecisionStateFor(
    freshness: RowFreshness,
    confidence: ConfidenceBand,
    upsideBps: Int,
    compositeScore: Int,
    trustNote: String?,
): RowDecisionState? = when {
    freshness != RowFreshness.Updated -> null
    confidence == ConfidenceBand.Low -> RowDecisionState.Avoid
    upsideBps <= 0 -> RowDecisionState.Avoid
    compositeScore < 8 -> RowDecisionState.Avoid
    trustNote != null -> RowDecisionState.Watch
    confidence == ConfidenceBand.High &&
        compositeScore >= 10 -> RowDecisionState.Act
    else -> RowDecisionState.Watch
}

internal fun rowTrustNote(
    detail: SymbolDetail?,
    issueMessage: String?,
): String? {
    val analystCount = analystTargetOpinionCount(detail)
    return when {
        issueMessage != null -> null
        preferredAnalystTargetFairValueCents(detail) == null -> "No analyst target"
        analystCount == null -> "Unknown analyst coverage"
        analystCount < 3 -> "Thin analyst coverage"
        else -> null
    }
}

private fun analystTargetOpinionCount(detail: SymbolDetail?): Int? = when {
    detail?.weightedExternalSignalFairValueCents != null -> detail.weightedAnalystCount ?: detail.analystOpinionCount
    detail?.externalSignalFairValueCents != null -> detail.analystOpinionCount
    else -> null
}

internal fun rowExplanationFor(
    hasComparableBaseline: Boolean,
    hasRankMovement: Boolean,
    hasPriceMovement: Boolean,
    hasTargetMovement: Boolean,
): RowExplanationKind = when {
    !hasComparableBaseline -> RowExplanationKind.NoBaseline
    hasPriceMovement && hasTargetMovement -> RowExplanationKind.CombinedMove
    hasTargetMovement -> RowExplanationKind.TargetChanged
    hasPriceMovement -> RowExplanationKind.PriceMoved
    hasRankMovement -> RowExplanationKind.RelativeReRank
    else -> RowExplanationKind.NoMeaningfulChange
}

internal fun hasSignificantRelativeMove(
    previousCents: Long?,
    currentCents: Long?,
): Boolean {
    if (previousCents == null || currentCents == null || previousCents <= 0L || currentCents <= 0L) {
        return false
    }
    return kotlin.math.abs(checkedUpsideBps(previousCents, currentCents) ?: return false) >= 500
}

private fun revisionHistoryKey(revision: SymbolRevision): String = listOf(
    revision.symbol,
    revision.evaluatedAtEpochSeconds.toString(),
    revision.detail.lastSequence.toString(),
    revision.detail.updateCount.toString(),
    revision.detail.marketPriceCents.toString(),
    revision.detail.intrinsicValueCents.toString(),
    preferredAnalystTargetFairValueCents(revision.detail)?.toString() ?: "null",
).joinToString("|")

internal data class TimeseriesFallback(
    val snapshot: MarketSnapshot,
    val fundamentals: FundamentalSnapshot,
    val timeseries: FundamentalTimeseries,
    val analysis: DcfAnalysis,
)
