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
import com.discountscreener.android.data.remote.isRateLimitDetail
import com.discountscreener.android.data.remote.isUsableCompanyName
import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.DashboardNotice
import com.discountscreener.android.domain.model.DashboardNoticeSeverity
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.ProfileTransitionEvent
import com.discountscreener.android.domain.model.ProfileTransitionFeedback
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowExplanationKind
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.domain.model.SystemStats
import com.discountscreener.android.domain.model.TickerSearchSuggestion
import com.discountscreener.android.domain.model.TrackedRowState
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.domain.logging.AppLogger
import com.discountscreener.android.domain.logging.NoOpAppLogger
import com.discountscreener.android.domain.model.preferredAnalystTargetFairValueCents
import com.discountscreener.android.domain.model.rankMovement
import com.discountscreener.android.domain.model.significantValuationChange
import com.discountscreener.android.domain.model.reduceProfileTransition
import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.DcfAnalysisEngine
import com.discountscreener.core.engine.EstimatesHistoryPolicy
import com.discountscreener.core.engine.IndexEstimatesEngine
import com.discountscreener.core.engine.OpportunityContext
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.engine.PricingHistoryMerge
import com.discountscreener.core.engine.QuantLensEngine
import com.discountscreener.core.engine.ReportingEngine
import com.discountscreener.core.engine.ScreenDataProjectionEngine
import com.discountscreener.core.engine.buildSymbolDetail
import com.discountscreener.core.engine.checkedUpsideBps
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ComputationArea
import com.discountscreener.core.model.ComputationFailure
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.DcfSourceSelection
import com.discountscreener.core.model.DataProvenance
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.IssueRecord
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.OpportunityRow
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.PersistedReportState
import com.discountscreener.core.model.PricingCandle
import com.discountscreener.core.model.ProjectedConfidence
import com.discountscreener.core.model.ProjectedOpportunityDecisionFacts
import com.discountscreener.core.model.ProjectedOpportunityRow
import com.discountscreener.core.model.ProjectedProviderCategory
import com.discountscreener.core.model.ProjectedProvenanceState
import com.discountscreener.core.model.ProjectedRowDecision
import com.discountscreener.core.model.ProjectedRowFreshness
import com.discountscreener.core.model.ProjectedTrackedRow
import com.discountscreener.core.model.ProjectionComparisonBaselines
import com.discountscreener.core.model.ProjectionProfileFacts
import com.discountscreener.core.model.ProjectionRoute
import com.discountscreener.core.model.ProjectionSymbolState
import com.discountscreener.core.model.ProviderDecisionReason
import com.discountscreener.core.model.ProviderDecisionReasonCode
import com.discountscreener.core.model.ProviderState
import com.discountscreener.core.model.ResolverState
import com.discountscreener.core.model.getOrNull
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
import com.discountscreener.core.model.ScreenDataProjectionRequest
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRevision
import com.discountscreener.core.model.SymbolRangeKey
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    val result: ComputationResult<QuantLensReport>,
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
    private val computeDispatcher: CoroutineDispatcher = ioDispatcher,
    private val logger: AppLogger = NoOpAppLogger,
) : DashboardRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stateMutex = Mutex()
    private val updates = MutableStateFlow(0L)
    private val dcfSourceCoordinator = DcfSourceCoordinator(yahooClient, secondaryTimeseriesProvider)
    private val screenDataProjectionEngine = ScreenDataProjectionEngine()

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
    private val companyNameBySymbol = linkedMapOf<String, String>()

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
    ): DashboardSnapshot = withContext(computeDispatcher) {
        stateMutex.withLock {
            snapshotLocked(filter, selectedSymbol, selectedRange, opportunityScoringModel)
        }
    }

    override suspend fun currentIndexEstimates(): ComputationResult<IndexEstimatesReport> = withContext(computeDispatcher) {
        stateMutex.withLock {
            safeEstimatesReportLocked().also { result ->
                if (result is ComputationResult.Error) {
                    logComputationFailure("current index estimates", result.failure)
                }
            }
        }
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
        ensureAdHocSymbolLoaded(symbol)
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

        val detailForDcf = stateMutex.withLock { engine.detail(symbol) }
        val fundamentals = detailForDcf?.fundamentals
        val marketPriceCents = detailForDcf?.marketPriceCents?.takeIf { it > 0L }
        val needsDcfResolve = stateMutex.withLock {
            fundamentals != null && needsDcfResolutionLocked(symbol)
        }
        if (fundamentals != null && needsDcfResolve) {
            val selection = dcfSourceCoordinator.resolve(symbol) { timeseries ->
                DcfAnalysisEngine.compute(fundamentals, timeseries, marketPriceCents).getOrNull()
            }
            val resolvedAnalysis = analysisFromSelection(selection, fundamentals)
            selection.timeseries?.let { timeseries ->
                stateMutex.withLock {
                    timeseriesCache[symbol] = timeseries
                    resolvedAnalysis?.let { analysis -> dcfCache[symbol] = analysis }
                }
                captures += fundamentalTimeseriesCapture(
                    symbol = symbol,
                    timeseries = timeseries,
                    analysis = resolvedAnalysis,
                    capturedAt = now(),
                )
            } ?: run {
                // Terminal not-eligible / unavailable without timeseries still needs a coverage marker.
                resolvedAnalysis?.let { analysis ->
                    stateMutex.withLock { dcfCache[symbol] = analysis }
                }
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

    override suspend fun searchTickers(
        query: String,
        currentProfile: String,
        limit: Int,
    ): List<TickerSearchSuggestion> {
        val localSuggestions = profileCatalog.searchTickers(query, currentProfile, limit)
        // Include single-letter queries (e.g. L / Loews) so names can be filled from Yahoo chart meta
        // when quote HTML 404s.
        if (query.trim().isNotEmpty()) {
            localSuggestions
                .take(4)
                .filter { suggestion -> localCompanyNameFor(suggestion.symbol).isNullOrBlank() }
                .forEach { suggestion ->
                    runCatching {
                        yahooClient.fetchSymbol(suggestion.symbol).companyName
                    }.getOrNull()?.takeIf(String::isNotBlank)?.let { companyName ->
                        stateMutex.withLock {
                            companyNameBySymbol[suggestion.symbol] = companyName
                        }
                    }
                }
        }
        return localSuggestions.map { suggestion ->
            TickerSearchSuggestion(
                symbol = suggestion.symbol,
                companyName = localCompanyNameFor(suggestion.symbol),
                profiles = suggestion.profiles,
                inCurrentProfile = suggestion.inCurrentProfile,
            )
        }
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

    private suspend fun ensureAdHocSymbolLoaded(symbol: String) {
        val normalizedSymbol = symbol.trim().uppercase()
        val alreadyLoaded = stateMutex.withLock { engine.detail(normalizedSymbol) != null }
        if (alreadyLoaded) {
            return
        }

        val fetchedAt = now()
        val providerResult = yahooClient.fetchSymbol(normalizedSymbol)
        val chartCaptures = mutableListOf<Pair<ChartRange, List<HistoricalCandle>>>()
        ChartRange.entries.forEach { range ->
            val candles = runCatching { yahooClient.fetchHistoricalCandles(normalizedSymbol, range) }.getOrDefault(emptyList())
            if (candles.isNotEmpty()) {
                chartCaptures += range to candles
            }
        }

        stateMutex.withLock {
            providerResult.companyName?.takeIf(String::isNotBlank)?.let { companyName ->
                companyNameBySymbol[normalizedSymbol] = companyName
            }
            providerResult.snapshot?.let { snapshot ->
                val snapshotToIngest = if (snapshot.companyName.isNullOrBlank()) {
                    companyNameBySymbol[normalizedSymbol]?.let { companyName ->
                        snapshot.copy(companyName = companyName)
                    } ?: snapshot
                } else {
                    snapshot
                }
                engine.ingestSnapshot(snapshotToIngest)
                snapshotToIngest.companyName?.takeIf(String::isNotBlank)?.let { companyName ->
                    companyNameBySymbol[normalizedSymbol] = companyName
                }
            }
            providerResult.externalSignal?.let(engine::ingestExternal)
            providerResult.fundamentals?.let(engine::ingestFundamentals)
            providerResult.snapshot?.let {
                refreshedSymbols += normalizedSymbol
                freshnessTimestampBySymbol[normalizedSymbol] = fetchedAt
                lastUpdatedAtEpochSeconds = fetchedAt
            }
            chartCaptures.forEach { (range, candles) ->
                chartCache[chartKey(normalizedSymbol, range)] = candles
                chartSummaries.getOrPut(normalizedSymbol) { linkedMapOf() }[range] =
                    ChartAnalysis.buildSummary(range, candles, fetchedAt)
            }
            if (engine.detail(normalizedSymbol) != null) {
                appendRevisionLocked(normalizedSymbol)
            }
        }
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
        processRefreshRound(
            symbols = symbols,
            retryQueue = retryQueue,
            generation = generation,
            recordTerminalIssues = false,
        )
        repeat(MAX_RETRY_ROUNDS) { round ->
            if (retryQueue.isEmpty()) return
            delay(retryBackoffMillis(round))
            val batch = buildList {
                while (retryQueue.isNotEmpty()) {
                    add(retryQueue.removeFirst())
                }
            }
            val isFinalRound = round == MAX_RETRY_ROUNDS - 1
            processRefreshRound(
                symbols = batch,
                retryQueue = retryQueue,
                generation = generation,
                recordTerminalIssues = isFinalRound,
            )
        }
        // Any symbols still only retryable and never recorded need a terminal settle.
        if (retryQueue.isNotEmpty()) {
            processRefreshRound(
                symbols = retryQueue.toList(),
                retryQueue = ArrayDeque(),
                generation = generation,
                recordTerminalIssues = true,
            )
        }
    }

    private suspend fun processRefreshRound(
        symbols: List<String>,
        retryQueue: ArrayDeque<String>,
        generation: Long,
        recordTerminalIssues: Boolean,
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
                val needsRecovery = result.retryable && isRefreshResultIncomplete(result)
                if (needsRecovery && !recordTerminalIssues && result.symbol !in retryQueue) {
                    retryQueue.add(result.symbol)
                }
                val persistenceDelta = stateMutex.withLock {
                    applyRefreshResultLocked(
                        result = result,
                        suppressTransientRateLimits = !recordTerminalIssues,
                        recordTerminalFailure = recordTerminalIssues || !needsRecovery,
                    )
                }
                queuePersist(persistenceDelta)
                emitUpdate()
            }
    }

    private fun isRefreshResultIncomplete(result: SymbolRefreshResult): Boolean {
        val hasSnapshot = result.providerResult?.snapshot != null ||
            result.fallbackSnapshot != null
        val hasName = !result.providerResult?.companyName.isNullOrBlank()
        val hasChart = !result.chartCandles.isNullOrEmpty()
        // Incomplete when we have neither a live snapshot nor even a chart-backed recovery signal.
        return !hasSnapshot && !hasName && !hasChart
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
        val hasCachedDcfInputs = stateMutex.withLock {
            dcfCache[symbol] != null && timeseriesCache[symbol] != null
        }
        val dcfFallback = if (providerResult.snapshot == null && !hasCachedDcfInputs) {
            resolveDcfFallback(
                symbol = symbol,
                companyName = providerResult.companyName,
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

    private fun applyRefreshResultLocked(
        result: SymbolRefreshResult,
        suppressTransientRateLimits: Boolean = false,
        recordTerminalFailure: Boolean = true,
    ): PersistenceDelta {
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

        providerResult?.companyName?.takeIf { name -> isUsableCompanyName(name) }?.let { companyName ->
            companyNameBySymbol[result.symbol] = companyName
        }
        effectiveSnapshot?.let {
            val snapshotToIngest = if (it.companyName.isNullOrBlank()) {
                companyNameBySymbol[result.symbol]?.let { companyName -> it.copy(companyName = companyName) } ?: it
            } else {
                it
            }
            engine.ingestSnapshot(snapshotToIngest)
            snapshotToIngest.companyName?.takeIf { name -> isUsableCompanyName(name) }?.let { companyName ->
                companyNameBySymbol[result.symbol] = companyName
            }
            rawCaptures += RawCapture(
                symbol = result.symbol,
                captureKind = CaptureKind.Snapshot,
                scopeKey = null,
                capturedAt = result.refreshedAtEpochSeconds,
                payload = RawCapturePayload.Snapshot(snapshotToIngest),
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
            recomputeCachedDcfLocked(result.symbol, it)
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

        val recovered =
            effectiveSnapshot != null ||
                isUsableCompanyName(providerResult?.companyName) ||
                !result.chartCandles.isNullOrEmpty()
        val diagnostics = providerResult?.diagnostics.orEmpty().let { list ->
            if (suppressTransientRateLimits) {
                list.filterNot { diagnostic ->
                    diagnostic.retryable && isRateLimitDetail(diagnostic.detail)
                }
            } else {
                list
            }
        }
        applyDiagnosticsLocked(
            symbol = result.symbol,
            diagnostics = diagnostics,
            chartError = if (suppressTransientRateLimits && result.chartError != null && isRetryable(result.chartError)) {
                null
            } else {
                result.chartError
            },
            suppressQuoteHtml404 = fallbackSnapshot != null || result.fallbackSnapshot != null,
            suppressCoreMissing = result.fallbackSnapshot != null || recovered,
        )
        if (recordTerminalFailure && !recovered && engine.detail(result.symbol) == null) {
            recordIssueLocked(
                key = "${result.symbol}:provider:terminal",
                severity = PersistenceIssueSeverity.Warning,
                title = "Provider unavailable",
                detail = "No market data after retries for ${result.symbol}. Will use cache when available.",
            )
        }
        if (recovered) {
            // Success clears prior terminal noise for this symbol.
            issues.keys.filter { key ->
                key.startsWith("${result.symbol}:provider:") ||
                    key.startsWith("${result.symbol}:chart:") ||
                    key.startsWith("${result.symbol}:enrichment:")
            }.forEach { key ->
                issues[key]?.let { issue -> issues[key] = issue.copy(active = false) }
            }
        }

        if (refreshAttemptedSymbols.add(result.symbol)) {
            refreshCompletedSymbols += 1
        }
        if (recovered) {
            refreshedSymbols += result.symbol
        }
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
        var normalizedFilter = filter.copy(query = filter.query.trim())
        var normalizedSelectedSymbol = selectedSymbol?.trim()?.takeIf { it.isNotBlank() }
        var selectedDetail = normalizedSelectedSymbol?.let(engine::detail)
        var selectedCharts = if (normalizedSelectedSymbol == null) {
            emptyMap()
        } else {
            ChartRange.entries.associateWith { range ->
                chartCache[chartKey(normalizedSelectedSymbol, range)].orEmpty()
            }
        }
        var issueRecords = issues.values
            .sortedByDescending { it.lastSeenEvent }
            .map(::toIssueRecord)
            .toMutableList()
        var trackedIssueMessages = issues.values
            .filter { it.active }
            .associateBy({ it.key.substringBefore(':', it.key) }, { it.detail })
        var dashboardCandidateRows = engine.filteredRows(limit = trackedSymbols.size.coerceAtLeast(1), filter = normalizedFilter)
        var scoredOpportunityRows = filteredScoredOpportunityRowsLocked(normalizedFilter, opportunityScoringModel)
        var projectionCandidateRows = scoredOpportunityRows.map(::candidateRowFromOpportunityRow)
        var projectionRequest = screenDataProjectionRequestLocked(
            filter = normalizedFilter,
            selectedSymbol = normalizedSelectedSymbol,
            selectedRange = selectedRange,
            opportunityScoringModel = opportunityScoringModel,
            candidateRows = projectionCandidateRows,
            opportunityDecisionFactsBySymbol = projectedOpportunityDecisionFactsBySymbol(scoredOpportunityRows),
            issues = issueRecords,
            issueMessagesBySymbol = trackedIssueMessages,
        )
        var detailNotice: DashboardNotice? = null
        var estimatesNotice: DashboardNotice? = null
        var screenDataResult = screenDataProjectionEngine.project(
            projectionRequest,
        )
        var screenData: com.discountscreener.core.model.ProjectedDashboardData
        var trackedRows: List<TrackedSymbolRow>
        var opportunityRows: List<OpportunityListRow>
        when (screenDataResult) {
            is ComputationResult.Success -> {
                screenData = screenDataResult.value
                trackedRows = trackedRowsFromProjectionLocked(screenData.trackedRows, trackedIssueMessages)
                opportunityRows = opportunityRowsFromProjectionLocked(
                    projectedRows = screenData.opportunityRows,
                    scoredRows = scoredOpportunityRows,
                    issueMessagesBySymbol = trackedIssueMessages,
                    scoringModel = opportunityScoringModel,
                )
            }
            is ComputationResult.Error -> {
                var failure = screenDataResult.failure
                logComputationFailure("snapshot projection", failure)
                detailNotice = dashboardNoticeForFailure(failure)
                if (shouldSurfaceTransientIssue(failure)) {
                    issueRecords.add(transientIssueForFailure(failure))
                }
                var estimatesResult = safeEstimatesReportLocked()
                screenData = projectionFallbackScreenData(
                    estimatesReport = estimatesResult.getOrNull() ?: emptyEstimatesReport(),
                    issues = issueRecords,
                    symbol = failure.symbol,
                )
                if (estimatesResult is ComputationResult.Error) {
                    logComputationFailure("projection fallback estimates", estimatesResult.failure)
                    estimatesNotice = dashboardNoticeForFailure(estimatesResult.failure)
                }
                trackedRows = trackedRowsLocked(normalizedFilter, trackedIssueMessages)
                opportunityRows = opportunityRowsLocked(normalizedFilter, opportunityScoringModel)
            }
        }
        var projectedSelectedDetail = screenData.selectedDetail
        selectedDetail = projectedSelectedDetail?.detail ?: selectedDetail
        if (projectedSelectedDetail != null && normalizedSelectedSymbol != null) {
            selectedCharts = selectedCharts + (selectedRange to projectedSelectedDetail.chart.candles)
        }
        // Eventual good state: never leave detail on eternal "Loading" after refresh has tried.
        if (
            selectedDetail == null &&
            normalizedSelectedSymbol != null &&
            (
                normalizedSelectedSymbol in refreshAttemptedSymbols ||
                    startupPhase == DashboardStartupPhase.Ready
                )
        ) {
            val issueDetail = trackedIssueMessages[normalizedSelectedSymbol]
            val companyName = companyNameBySymbol[normalizedSelectedSymbol]
            detailNotice = detailNotice ?: DashboardNotice(
                title = "Market data unavailable",
                message = buildString {
                    append(normalizedSelectedSymbol)
                    if (!companyName.isNullOrBlank()) {
                        append(" (")
                        append(companyName)
                        append(')')
                    }
                    append(" has no usable quote after refresh.")
                    if (!issueDetail.isNullOrBlank()) {
                        append(' ')
                        append(issueDetail)
                    }
                    append(" Open another symbol or retry Refresh later.")
                },
                severity = DashboardNoticeSeverity.Warning,
            )
        }
        var selectedQuantLens: QuantLensReport? = null
        selectedDetail?.let {
            when (
                val quantLensResult = buildSelectedQuantLensLocked(
                detail = it,
                selectedRange = selectedRange,
                opportunityRows = opportunityRows,
                opportunityScoringModel = opportunityScoringModel,
                )
            ) {
                is ComputationResult.Success -> {
                    selectedQuantLens = quantLensResult.value
                }
                is ComputationResult.Error -> {
                    var failure = quantLensResult.failure
                    detailNotice = detailNotice ?: dashboardNoticeForFailure(failure)
                    if (shouldSurfaceTransientIssue(failure)) {
                        issueRecords.add(transientIssueForFailure(failure))
                    }
                }
            }
        }
        screenData = screenData.copy(
            providerState = screenData.providerState.copy(issues = issueRecords),
        )

        return DashboardSnapshot(
            availableProfiles = profileCatalog.availableProfiles(),
            currentProfile = currentProfile,
            trackedSymbols = trackedSymbols.toList(),
            trackedRows = trackedRows,
            watchlistSymbols = engine.watchlistSymbols(),
            candidateRows = dashboardCandidateRows,
            opportunityRows = opportunityRows,
            opportunityScoringModel = opportunityScoringModel,
            issues = issueRecords,
            selectedDetail = selectedDetail,
            selectedCharts = selectedCharts,
            selectedHistory = screenData.selectedDetail?.revisions ?: revisions[normalizedSelectedSymbol].orEmpty(),
            selectedAlerts = screenData.selectedDetail?.alerts ?: engine.alerts().filter { it.symbol == normalizedSelectedSymbol }.takeLast(6),
            selectedQuantLens = selectedQuantLens,
            detailNotice = detailNotice,
            lastUpdatedAtEpochSeconds = lastUpdatedAtEpochSeconds,
            startupPhase = startupPhase,
            refreshCompletedSymbols = refreshCompletedSymbols,
            refreshTargetSymbols = refreshTargetSymbols,
            statusMessage = statusMessage,
            estimatesNotice = estimatesNotice,
            screenData = screenData,
        )
    }

    private fun estimatesReportLocked(): IndexEstimatesReport {
        var details = trackedSymbols.mapNotNull { symbol -> engine.detail(symbol) }
        return IndexEstimatesEngine.compute(
            symbols = details,
            dcfBySymbol = dcfCache,
            profileName = currentProfile,
            nowEpochSeconds = now(),
        )
    }

    private fun safeEstimatesReportLocked(): ComputationResult<IndexEstimatesReport> = try {
        ComputationResult.Success(estimatesReportLocked())
    } catch (error: Throwable) {
        ComputationResult.Error(
            ComputationFailure(
                code = "index_estimates_failed",
                area = ComputationArea.Estimates,
                message = error.message ?: "Index estimates computation failed.",
                recoverable = true,
                cause = error,
            ),
        )
    }

    private fun projectionFallbackScreenData(
        estimatesReport: IndexEstimatesReport,
        issues: List<IssueRecord>,
        symbol: String?,
    ) = com.discountscreener.core.model.ProjectedDashboardData(
        candidateRows = emptyList(),
        estimates = com.discountscreener.core.model.ProjectedEstimatesData(report = estimatesReport),
        providerState = com.discountscreener.core.model.ProjectedProviderState(
            category = ProjectedProviderCategory.ProviderUncertain,
            statusCopy = "Local projections degraded; showing raw rows",
            retryable = false,
            computedAtEpochSeconds = now(),
            issues = issues,
            affectedSymbols = listOfNotNull(symbol),
        ),
    )

    private fun emptyEstimatesReport(): IndexEstimatesReport =
        com.discountscreener.core.model.ProjectedEstimatesData().report

    private fun dashboardNoticeForFailure(failure: ComputationFailure): DashboardNotice {
        val title = when (failure.area) {
            ComputationArea.QuantLens -> "Quant Lens unavailable"
            ComputationArea.Projection -> "Projection degraded"
            ComputationArea.Estimates -> "Estimates unavailable"
        }
        return DashboardNotice(
            title = title,
            message = failure.message,
            severity = if (failure.recoverable) DashboardNoticeSeverity.Warning else DashboardNoticeSeverity.Error,
        )
    }

    private fun shouldSurfaceTransientIssue(failure: ComputationFailure): Boolean =
        failure.symbol != null

    private fun transientIssueForFailure(failure: ComputationFailure): IssueRecord =
        IssueRecord(
            key = "transient:${failure.area.name.lowercase()}:${failure.symbol ?: failure.code}",
            title = dashboardNoticeForFailure(failure).title,
            detail = failure.message,
            severity = if (failure.recoverable) "warning" else "error",
            active = true,
            count = 1,
            lastSeenEpochSeconds = now(),
        )

    private fun logComputationFailure(context: String, failure: ComputationFailure) {
        var symbolSuffix = failure.symbol?.let { " for $it" }.orEmpty()
        logger.error(
            TAG,
            "$context failed area=${failure.area.name} code=${failure.code}$symbolSuffix recoverable=${failure.recoverable}: ${failure.message}",
            failure.cause,
        )
    }

    private fun screenDataProjectionRequestLocked(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
        candidateRows: List<CandidateRow>,
        opportunityDecisionFactsBySymbol: Map<String, ProjectedOpportunityDecisionFacts>,
        issues: List<IssueRecord>,
        issueMessagesBySymbol: Map<String, String>,
    ): ScreenDataProjectionRequest {
        var tracked = trackedSymbols.toList()
        var watchlist = engine.watchlistSymbols().toSet()
        var detailSymbols = (tracked + watchlist + candidateRows.map { it.symbol } + listOfNotNull(selectedSymbol) + dcfCache.keys)
            .distinct()
        var detailsBySymbol = detailSymbols.mapNotNull { symbol ->
            engine.detail(symbol)?.let { detail -> symbol to detail }
        }.toMap()
        return ScreenDataProjectionRequest(
            profile = ProjectionProfileFacts(
                currentProfile = currentProfile,
                availableProfiles = profileCatalog.availableProfiles(),
            ),
            route = ProjectionRoute(
                filter = filter,
                selectedSymbol = selectedSymbol,
                selectedRange = selectedRange,
                replayOffset = 0,
                opportunityScoringModel = opportunityScoringModel,
            ),
            nowEpochSeconds = now(),
            trackedSymbols = tracked,
            watchlistSymbols = watchlist,
            detailsBySymbol = detailsBySymbol,
            candidateRows = candidateRows,
            opportunityDecisionFactsBySymbol = opportunityDecisionFactsBySymbol,
            chartCandles = projectionChartCandlesLocked(detailSymbols),
            chartSummariesBySymbol = chartSummaries.mapValues { entry -> entry.value.toMap() },
            dcfBySymbol = dcfCache.toMap(),
            revisionsBySymbol = revisions.mapValues { entry -> entry.value.toList() },
            alertsBySymbol = engine.alerts().groupBy { alert -> alert.symbol },
            issues = issues,
            symbolStateBySymbol = projectionSymbolStatesLocked(detailSymbols, issueMessagesBySymbol),
            baselines = ProjectionComparisonBaselines(
                previousRankBySymbol = comparisonBaselineRankBySymbol.toMap(),
                previousFairValueCentsBySymbol = comparisonBaselineWeightedFairValueBySymbol.toMap(),
            ),
        )
    }

    private fun projectionChartCandlesLocked(symbols: List<String>): Map<SymbolRangeKey, List<HistoricalCandle>> = buildMap {
        symbols.forEach { symbol ->
            ChartRange.entries.forEach { range ->
                var candles = chartCache[chartKey(symbol, range)].orEmpty()
                if (candles.isNotEmpty()) {
                    put(SymbolRangeKey(symbol = symbol, range = range), candles)
                }
            }
        }
    }

    private fun projectionSymbolStatesLocked(
        symbols: List<String>,
        issueMessagesBySymbol: Map<String, String>,
    ): Map<String, ProjectionSymbolState> = buildMap {
        symbols.forEach { symbol ->
            projectionSymbolStateLocked(symbol, issueMessagesBySymbol[symbol])?.let { state ->
                put(symbol, state)
            }
        }
    }

    private fun projectionSymbolStateLocked(
        symbol: String,
        issueMessage: String?,
    ): ProjectionSymbolState? {
        var detail = engine.detail(symbol)
        if (detail == null && issueMessage == null) {
            return null
        }
        var category = when {
            detail == null && issueMessage != null -> ProjectedProviderCategory.Unavailable
            symbol in refreshedSymbols -> ProjectedProviderCategory.Live
            detail != null && symbol in staleSymbols && startupPhase == DashboardStartupPhase.ShowingCached -> ProjectedProviderCategory.Restored
            detail != null && symbol in staleSymbols -> ProjectedProviderCategory.Stale
            detail != null -> ProjectedProviderCategory.Live
            else -> ProjectedProviderCategory.Unavailable
        }
        var provenanceState = when (category) {
            ProjectedProviderCategory.Live -> ProjectedProvenanceState.Live
            ProjectedProviderCategory.Restored -> ProjectedProvenanceState.Restored
            ProjectedProviderCategory.Stale -> ProjectedProvenanceState.Stale
            ProjectedProviderCategory.Unavailable -> ProjectedProvenanceState.Unavailable
            ProjectedProviderCategory.ProviderUncertain -> ProjectedProvenanceState.ProviderUncertain
            ProjectedProviderCategory.ParseUncertain -> ProjectedProvenanceState.ParseUncertain
            ProjectedProviderCategory.NotEligible -> ProjectedProvenanceState.NotEligible
            ProjectedProviderCategory.Disabled -> ProjectedProvenanceState.Disabled
            ProjectedProviderCategory.Superseded -> ProjectedProvenanceState.Superseded
            ProjectedProviderCategory.SourceUnknown -> ProjectedProvenanceState.SourceUnknown
        }
        return ProjectionSymbolState(
            symbol = symbol,
            providerCategory = category,
            provenanceState = provenanceState,
            capturedAtEpochSeconds = freshnessTimestampBySymbol[symbol],
            stale = detail != null && symbol in staleSymbols,
        )
    }

    private fun filteredScoredOpportunityRowsLocked(
        filter: ViewFilter,
        scoringModel: OpportunityScoringModel,
    ): List<OpportunityRow> = rankedOpportunityRowsLocked(scoringModel)
        .filter { row ->
            filter.query.isBlank() ||
                row.symbol.contains(filter.query, ignoreCase = true) ||
                row.companyName?.contains(filter.query, ignoreCase = true) == true
        }

    private fun candidateRowFromOpportunityRow(row: OpportunityRow) = CandidateRow(
        symbol = row.symbol,
        marketPriceCents = row.marketPriceCents,
        intrinsicValueCents = row.intrinsicValueCents,
        gapBps = row.gapBps,
        upsideBps = row.upsideBps,
        isQualified = true,
        confidence = row.confidence,
        companyName = row.companyName,
    )

    private fun projectedOpportunityDecisionFactsBySymbol(rows: List<OpportunityRow>): Map<String, ProjectedOpportunityDecisionFacts> =
        rows.associate { row -> row.symbol to ProjectedOpportunityDecisionFacts(compositeScore = row.compositeScore) }

    private fun trackedRowsFromProjectionLocked(
        projectedRows: List<ProjectedTrackedRow>,
        issueMessagesBySymbol: Map<String, String>,
    ): List<TrackedSymbolRow> {
        var sortedRows = projectedRows.sortedWith(
            compareByDescending<ProjectedTrackedRow> { it.upsideBps ?: Int.MIN_VALUE }
                .thenBy { projectedRow ->
                    trackedRowStateRank(
                        trackedRowStateFromProjection(
                            projectedRow.symbol,
                            projectedRow.detail ?: engine.detail(projectedRow.symbol),
                            issueMessagesBySymbol[projectedRow.symbol],
                        ),
                    )
                }
                .thenBy { it.symbol },
        )
        return sortedRows.mapIndexed { currentIndex, projectedRow ->
            var detail = projectedRow.detail ?: engine.detail(projectedRow.symbol)
            var state = trackedRowStateFromProjection(projectedRow.symbol, detail, issueMessagesBySymbol[projectedRow.symbol])
            var fairValueCents = projectedRow.fairValueAnchor.valueCents
            TrackedSymbolRow(
                symbol = projectedRow.symbol,
                marketPriceCents = projectedRow.marketPriceCents ?: detail?.marketPriceCents,
                intrinsicValueCents = fairValueCents,
                gapBps = projectedRow.gapBps ?: detail?.gapBps,
                upsideBps = projectedRow.upsideBps ?: detail?.upsideBps,
                confidence = projectedRow.confidence.toConfidenceBandOrNull(),
                qualification = detail?.qualification,
                isWatched = engine.isWatched(projectedRow.symbol),
                state = state,
                freshness = projectedRow.freshness.toRowFreshness(),
                stale = detail != null && projectedRow.symbol in staleSymbols,
                providerIssue = issueMessagesBySymbol[projectedRow.symbol],
                trustNote = projectedRow.trustSignal?.label,
                freshnessAsOfEpochSeconds = freshnessTimestampBySymbol[projectedRow.symbol],
                companyName = resolvedCompanyNameLocked(projectedRow.symbol, detail),
                rankMovement = rankMovement(comparisonBaselineRankBySymbol[projectedRow.symbol], currentIndex),
                valuationChange = significantValuationChange(
                    comparisonBaselineWeightedFairValueBySymbol[projectedRow.symbol],
                    fairValueCents,
                ),
                explanation = trackedExplanationFromProjection(
                    symbol = projectedRow.symbol,
                    currentIndex = currentIndex,
                    marketPriceCents = projectedRow.marketPriceCents ?: detail?.marketPriceCents,
                    fairValueCents = fairValueCents,
                ),
                decisionState = projectedRow.decision.toRowDecisionState(),
                quantLensSummary = projectedRow.quantLensSummary ?: detail?.let {
                    buildRowQuantLensSummaryLocked(
                        detail = it,
                        opportunityRow = null,
                    )
                },
            )
        }
    }

    private fun trackedRowStateFromProjection(
        symbol: String,
        detail: SymbolDetail?,
        issueMessage: String?,
    ): TrackedRowState = when {
        detail != null && symbol in refreshedSymbols -> TrackedRowState.Live
        detail != null -> TrackedRowState.Cached
        issueMessage != null -> TrackedRowState.Failed
        // After refresh has attempted this symbol, never leave the UI stuck on Loading.
        symbol in refreshAttemptedSymbols -> TrackedRowState.Failed
        else -> TrackedRowState.Loading
    }

    private fun opportunityRowsFromProjectionLocked(
        projectedRows: List<ProjectedOpportunityRow>,
        scoredRows: List<OpportunityRow>,
        issueMessagesBySymbol: Map<String, String>,
        scoringModel: OpportunityScoringModel,
    ): List<OpportunityListRow> {
        var scoredBySymbol = scoredRows.associateBy { row -> row.symbol }
        return projectedRows.mapIndexed { currentIndex, projectedRow ->
            var scoredRow = scoredBySymbol[projectedRow.symbol]
            var detail = engine.detail(projectedRow.symbol)
            var fairValueCents = projectedRow.fairValueAnchor.valueCents ?: projectedRow.candidateRow.intrinsicValueCents
            var gapBps = projectedRow.gapBps ?: projectedRow.candidateRow.gapBps
            var upsideBps = projectedRow.upsideBps ?: projectedRow.candidateRow.upsideBps
            var freshness = projectedRow.freshness.toRowFreshness()
            var confidence = projectedRow.confidence.toConfidenceBandOrNull() ?: scoredRow?.confidence ?: projectedRow.candidateRow.confidence
            var baselineRank = comparisonBaselineOpportunityRankByModel.getValue(scoringModel)[projectedRow.symbol]
            OpportunityListRow(
                symbol = projectedRow.symbol,
                marketPriceCents = projectedRow.candidateRow.marketPriceCents,
                intrinsicValueCents = fairValueCents,
                gapBps = gapBps,
                upsideBps = upsideBps,
                confidence = confidence,
                isWatched = detail?.isWatched ?: scoredRow?.isWatched ?: false,
                freshness = freshness,
                providerIssue = issueMessagesBySymbol[projectedRow.symbol],
                trustNote = projectedRow.trustSignal?.label,
                freshnessAsOfEpochSeconds = freshnessTimestampBySymbol[projectedRow.symbol],
                fundamentalsScore = scoredRow?.fundamentalsScore,
                technicalScore = scoredRow?.technicalScore,
                forecastScore = scoredRow?.forecastScore,
                compositeScore = scoredRow?.compositeScore ?: 0,
                coverageCount = scoredRow?.coverageCount ?: 0,
                fundamentalsSignals = scoredRow?.fundamentalsSignals.orEmpty(),
                technicalSignals = scoredRow?.technicalSignals.orEmpty(),
                forecastSignals = scoredRow?.forecastSignals.orEmpty(),
                companyName = resolvedCompanyNameLocked(projectedRow.symbol, detail)
                    ?: projectedRow.candidateRow.companyName,
                rankMovement = rankMovement(baselineRank, currentIndex),
                valuationChange = significantValuationChange(
                    comparisonBaselineWeightedFairValueBySymbol[projectedRow.symbol],
                    fairValueCents,
                ),
                explanation = opportunityExplanationFromProjection(projectedRow.symbol, currentIndex, fairValueCents, baselineRank),
                decisionState = projectedRow.decision.toRowDecisionState(),
                quantLensSummary = projectedRow.quantLensSummary ?: detail?.let {
                    buildRowQuantLensSummaryLocked(
                        detail = it,
                        opportunityRow = scoredRow,
                    )
                },
            )
        }
    }

    private fun opportunityExplanationFromProjection(
        symbol: String,
        currentIndex: Int,
        fairValueCents: Long?,
        baselineRank: Int?,
    ): RowExplanationKind {
        var previousFairValueCents = comparisonBaselineWeightedFairValueBySymbol[symbol]
        return rowExplanationFor(
            hasComparableBaseline = baselineRank != null ||
                comparisonBaselineMarketPriceBySymbol[symbol] != null ||
                previousFairValueCents != null,
            hasRankMovement = baselineRank != null && baselineRank != currentIndex,
            hasPriceMovement = hasSignificantRelativeMove(
                previousCents = comparisonBaselineMarketPriceBySymbol[symbol],
                currentCents = engine.detail(symbol)?.marketPriceCents,
            ),
            hasTargetMovement = significantValuationChange(previousFairValueCents, fairValueCents) != null,
        )
    }

    private fun trackedExplanationFromProjection(
        symbol: String,
        currentIndex: Int,
        marketPriceCents: Long?,
        fairValueCents: Long?,
    ): RowExplanationKind {
        var previousFairValueCents = comparisonBaselineWeightedFairValueBySymbol[symbol]
        return rowExplanationFor(
            hasComparableBaseline = comparisonBaselineRankBySymbol[symbol] != null ||
                comparisonBaselineMarketPriceBySymbol[symbol] != null ||
                previousFairValueCents != null,
            hasRankMovement = comparisonBaselineRankBySymbol[symbol] != null &&
                comparisonBaselineRankBySymbol[symbol] != currentIndex,
            hasPriceMovement = hasSignificantRelativeMove(
                previousCents = comparisonBaselineMarketPriceBySymbol[symbol],
                currentCents = marketPriceCents,
            ),
            hasTargetMovement = significantValuationChange(previousFairValueCents, fairValueCents) != null,
        )
    }

    private fun ProjectedConfidence.toConfidenceBandOrNull(): ConfidenceBand? = when (this) {
        ProjectedConfidence.High -> ConfidenceBand.High
        ProjectedConfidence.Provisional -> ConfidenceBand.Provisional
        ProjectedConfidence.Low -> ConfidenceBand.Low
        ProjectedConfidence.Unavailable -> null
    }

    private fun ProjectedRowFreshness.toRowFreshness(): RowFreshness = when (this) {
        ProjectedRowFreshness.Loading -> RowFreshness.Loading
        ProjectedRowFreshness.Updating -> RowFreshness.Updating
        ProjectedRowFreshness.Updated -> RowFreshness.Updated
        ProjectedRowFreshness.Restored -> RowFreshness.Restored
        ProjectedRowFreshness.Stale -> RowFreshness.Stale
        ProjectedRowFreshness.Issue -> RowFreshness.Issue
    }

    private fun ProjectedRowDecision?.toRowDecisionState(): RowDecisionState? = when (this) {
        ProjectedRowDecision.Act -> RowDecisionState.Act
        ProjectedRowDecision.Watch -> RowDecisionState.Watch
        ProjectedRowDecision.Avoid -> RowDecisionState.Avoid
        null -> null
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
            symbol in refreshAttemptedSymbols -> TrackedRowState.Failed
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
            companyName = resolvedCompanyNameLocked(symbol, detail),
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
        row: OpportunityRow,
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
                scoringModel = scoringModel,
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
    ): ComputationResult<QuantLensReport> {
        val fingerprint = quantLensFingerprintLocked(detail, selectedRange, opportunityRows, opportunityScoringModel)
        quantLensCache[detail.symbol]?.takeIf { it.fingerprint == fingerprint }?.let { return it.result }

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
        val result = QuantLensEngine.analyze(input)
        if (result is ComputationResult.Error) {
            logComputationFailure("selected quant lens", result.failure)
        }
        quantLensCache[detail.symbol] = QuantLensCacheEntry(fingerprint, result)
        return result
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
                valuationUpsideBps = detail.upsideBps
                    ?.coerceIn(QUANT_LENS_ROW_MIN_UPSIDE_BPS, QUANT_LENS_ROW_MAX_UPSIDE_BPS),
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
        opportunityRow: OpportunityRow?,
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

    private fun applyTransitionLocked(feedback: ProfileTransitionFeedback) {
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

    private fun resolvedCompanyNameLocked(symbol: String, detail: SymbolDetail?): String? =
        detail?.companyName?.takeIf(String::isNotBlank)
            ?: companyNameBySymbol[symbol]
            ?: revisions[symbol]?.lastOrNull()?.detail?.companyName

    private suspend fun localCompanyNameFor(symbol: String): String? = stateMutex.withLock {
        resolvedCompanyNameLocked(symbol, engine.detail(symbol))
    }

    internal fun isSuppressibleQuoteHtml404(diagnostic: ProviderDiagnostic): Boolean =
        (
            diagnostic.component == "quoteHtml" &&
                diagnostic.kind == "error" &&
                diagnostic.detail.contains("HTTP 404") &&
                diagnostic.detail.contains("finance.yahoo.com/quote/")
            ) ||
            (
                diagnostic.component == "quoteSummary" &&
                    diagnostic.kind == "error" &&
                    (
                        diagnostic.detail.contains("Invalid Crumb", ignoreCase = true) ||
                            diagnostic.detail.contains("Invalid Cookie", ignoreCase = true)
                        ) &&
                    diagnostic.retryable
                )

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
            // Keep market cap only when the provider reported it. Otherwise let DCF
            // derive price×shares with WaccFieldSource.DerivedPriceTimesShares.
            marketCapDollars = providerFundamentals?.marketCapDollars?.takeIf { it > 0L },
            sharesOutstanding = providerFundamentals?.sharesOutstanding ?: latestShares.roundToLong().takeIf { it > 0L },
            freeCashFlowDollars = timeseries.freeCashFlow.lastOrNull()?.value?.roundToLong(),
            operatingCashFlowDollars = timeseries.operatingCashFlow.lastOrNull()?.value?.roundToLong(),
            trailingEpsCents = ((latestNetIncome / latestShares) * 100.0).roundToLong(),
        )
        val fundamentals = mergeFundamentals(providerFundamentals, derivedFundamentals)
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = timeseries,
            marketPriceCents = marketPriceCents,
        ).getOrNull() ?: return null
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
            // Re-open NotEligible once per enrichment cycle (FCF may have improved upstream).
            clearNotEligibleTimeseriesLocked(symbols)
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
        val pending = symbols.toMutableList()
        var round = 0
        while (pending.isNotEmpty() && round <= MAX_RETRY_ROUNDS) {
            if (round > 0) {
                delay(retryBackoffMillis(round - 1))
            }
            val batch = pending.toList()
            pending.clear()
            val finalRound = round == MAX_RETRY_ROUNDS
            batch
                .asFlow()
                .flatMapMerge(concurrency = ENRICHMENT_CONCURRENCY) { symbol ->
                    flow { emit(enrichSymbol(symbol, generation, recordErrors = finalRound)) }
                }
                .collect { result ->
                    val isActiveGeneration = stateMutex.withLock { result.generation == activeProfileGeneration }
                    if (!isActiveGeneration) {
                        return@collect
                    }
                    if (!finalRound && result.errors.any { it.retryable }) {
                        pending += result.symbol
                    }
                    // Persist recovered charts immediately; only surface issues on the final round.
                    val toApply = if (finalRound) result else result.copy(errors = emptyList())
                    val delta = stateMutex.withLock { applyEnrichmentResultLocked(toApply) }
                    if (delta.rawCaptures.isNotEmpty() || delta.revisions.isNotEmpty()) {
                        queuePersist(delta)
                    }
                    emitUpdate()
                }
            round += 1
        }
    }

    private suspend fun enrichSymbol(
        symbol: String,
        generation: Long,
        recordErrors: Boolean,
    ): EnrichmentResult {
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
                    retryable = isRetryable(error),
                )
            }
        }

        var timeseries: FundamentalTimeseries? = null
        var dcfAnalysis: DcfAnalysis? = null

        val needsDcfResolve = stateMutex.withLock { needsDcfResolutionLocked(symbol) }
        if (needsDcfResolve) {
            try {
                val detailForDcf = stateMutex.withLock { engine.detail(symbol) }
                val fundamentals = detailForDcf?.fundamentals
                val marketPriceCents = detailForDcf?.marketPriceCents?.takeIf { it > 0L }
                if (fundamentals != null) {
                    val selection = dcfSourceCoordinator.resolve(symbol) { selectedTimeseries ->
                        DcfAnalysisEngine.compute(fundamentals, selectedTimeseries, marketPriceCents).getOrNull()
                    }
                    timeseries = selection.timeseries
                    dcfAnalysis = analysisFromSelection(selection, fundamentals)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                errors += ProviderDiagnostic(
                    component = "enrichment",
                    kind = "error",
                    detail = "timeseries for $symbol: ${error.message ?: "failed"}",
                    retryable = isRetryable(error),
                )
            }
        }

        return EnrichmentResult(
            generation = generation,
            symbol = symbol,
            chartCaptures = chartCaptures,
            timeseries = timeseries,
            dcfAnalysis = dcfAnalysis,
            // Caller drops errors on non-final rounds; keep retryable markers for queue detection.
            errors = if (recordErrors) {
                errors
            } else {
                errors.filter { it.retryable }
            },
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

        // Clear prior enrichment issues when this pass recovered chart/DCF data.
        if (result.chartCaptures.isNotEmpty() || result.timeseries != null || result.dcfAnalysis != null) {
            issues.keys
                .filter { key -> key.startsWith("${result.symbol}:enrichment:") }
                .forEach { key -> issues[key]?.let { issue -> issues[key] = issue.copy(active = false) } }
        }

        for (error in result.errors) {
            // Skip pure rate-limit noise when we already have Year candles from refresh.
            val hasYearChart = chartCache[chartKey(result.symbol, ChartRange.Year)].orEmpty().isNotEmpty()
            if (hasYearChart && error.retryable && isRateLimitDetail(error.detail)) {
                continue
            }
            recordIssueLocked(
                key = "${result.symbol}:${error.component}:${error.kind}:${error.detail.hashCode()}",
                severity = PersistenceIssueSeverity.Warning,
                title = "Enrichment failed",
                detail = error.detail,
            )
        }

        appendRevisionLocked(result.symbol)
        return snapshotPersistenceDeltaLocked(rawCaptures, result.symbol)
    }

    private fun recomputeCachedDcfLocked(
        symbol: String,
        fundamentals: FundamentalSnapshot,
    ) {
        val cachedAnalysis = dcfCache[symbol] ?: return
        // NotEligible stays until a live resolve reopens it (see needsDcfResolutionLocked).
        if (cachedAnalysis.resolverState == ResolverState.NotEligible) return
        val selectedTimeseries = timeseriesCache[symbol] ?: return
        val marketPriceCents = engine.detail(symbol)?.marketPriceCents?.takeIf { it > 0L }
        val recomputed = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = selectedTimeseries,
            marketPriceCents = marketPriceCents,
        ).getOrNull() ?: return
        // Update numeric DCF only. Never promote RestoredOnly → Selected from cache-only recompute;
        // live Selected requires dcfSourceCoordinator.resolve (enrichment / ensureDetailLoaded).
        dcfCache[symbol] = recomputed.copy(
            source = cachedAnalysis.source,
            sourceFingerprint = cachedAnalysis.sourceFingerprint,
            resolverState = cachedAnalysis.resolverState,
            decisionFingerprint = cachedAnalysis.decisionFingerprint,
            provenance = cachedAnalysis.provenance,
            providerReasons = cachedAnalysis.providerReasons,
        )
    }

    private fun needsDcfResolutionLocked(symbol: String): Boolean {
        val analysis = dcfCache[symbol] ?: return true
        return when (analysis.resolverState) {
            ResolverState.Selected ->
                analysis.bearIntrinsicValueCents <= 0L ||
                    analysis.baseIntrinsicValueCents <= 0L ||
                    analysis.bullIntrinsicValueCents <= 0L
            // Terminal until inputs change: re-open when fundamentals fingerprint moves or
            // timeseries was cleared (e.g. start of enrichment after a full refresh).
            ResolverState.NotEligible -> shouldReevaluateNotEligibleLocked(symbol, analysis)
            // Restored / unavailable / uncertain still need a live resolve pass.
            ResolverState.RestoredOnly,
            ResolverState.Unavailable,
            ResolverState.ProviderUncertain,
            ResolverState.Cancelled -> true
        }
    }

    private fun shouldReevaluateNotEligibleLocked(
        symbol: String,
        analysis: DcfAnalysis,
    ): Boolean {
        val fundamentals = engine.detail(symbol)?.fundamentals
        if (fundamentals == null) return true
        val currentFundFp = fundamentalsInputFingerprint(fundamentals)
        val storedFundFp = notEligibleFundamentalsFingerprint(analysis)
        if (storedFundFp == null || storedFundFp != currentFundFp) return true
        // Same fundamentals: only re-fetch if we dropped timeseries (new enrichment cycle).
        return timeseriesCache[symbol] == null
    }

    private fun analysisFromSelection(
        selection: DcfSourceSelection,
        fundamentals: FundamentalSnapshot?,
    ): DcfAnalysis? {
        selection.analysis?.let { return it }
        return when (selection.resolverState) {
            ResolverState.NotEligible -> terminalNotEligibleAnalysis(selection, fundamentals)
            else -> null
        }
    }

    private fun terminalNotEligibleAnalysis(
        selection: DcfSourceSelection,
        fundamentals: FundamentalSnapshot?,
    ): DcfAnalysis {
        val source = selection.providerQualities.firstOrNull()?.source
            ?: selection.reasons.firstOrNull()?.provider
            ?: DcfSource.Unknown
        val reasons = selection.reasons.ifEmpty {
            listOf(
                ProviderDecisionReason(
                    code = ProviderDecisionReasonCode.MissingAnnualFcf,
                    provider = source,
                ),
            )
        }
        val fundFp = fundamentals?.let(::fundamentalsInputFingerprint).orEmpty()
        return DcfAnalysis(
            bearIntrinsicValueCents = 0L,
            baseIntrinsicValueCents = 0L,
            bullIntrinsicValueCents = 0L,
            waccBps = 0,
            baseGrowthBps = 0,
            netDebtDollars = 0L,
            source = source,
            sourceFingerprint = selection.inputFingerprint ?: selection.decisionFingerprint,
            resolverState = ResolverState.NotEligible,
            // Encode fundamentals fingerprint so we can re-open when inputs change.
            decisionFingerprint = notEligibleDecisionFingerprint(fundFp, selection.decisionFingerprint),
            provenance = DataProvenance(
                source = source,
                providerState = ProviderState.NotEligible,
                fallbackReason = reasons.firstOrNull()?.code,
            ),
            providerReasons = reasons,
        )
    }

    private fun fundamentalsInputFingerprint(fundamentals: FundamentalSnapshot): String =
        listOf(
            fundamentals.marketCapDollars,
            fundamentals.sharesOutstanding,
            fundamentals.betaMillis,
            fundamentals.totalDebtDollars,
            fundamentals.totalCashDollars,
        ).joinToString("|")

    private fun notEligibleDecisionFingerprint(
        fundamentalsFingerprint: String,
        selectionDecisionFingerprint: String?,
    ): String = "ne|$fundamentalsFingerprint|${selectionDecisionFingerprint.orEmpty()}"

    private fun notEligibleFundamentalsFingerprint(analysis: DcfAnalysis): String? {
        val raw = analysis.decisionFingerprint ?: return null
        if (!raw.startsWith("ne|")) return null
        val parts = raw.split('|', limit = 3)
        return parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    /** Drop cached timeseries for NotEligible names so each enrichment cycle re-checks FCF. */
    private fun clearNotEligibleTimeseriesLocked(symbols: Collection<String>) {
        for (symbol in symbols) {
            if (dcfCache[symbol]?.resolverState == ResolverState.NotEligible) {
                timeseriesCache.remove(symbol)
            }
        }
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

    override suspend fun recordEstimatesSnapshot(report: IndexEstimatesReport): Boolean {
        return try {
            val rawHistory = stateStore.getEstimatesHistory(report.profileName)
            val history = EstimatesHistoryPolicy.coalesceDaily(rawHistory)
            // One-shot cleanup of legacy enrichment spam (many rows per day).
            if (rawHistory.size > history.size) {
                stateStore.replaceEstimatesHistory(report.profileName, history)
            }
            val previous = history.lastOrNull()
            when (EstimatesHistoryPolicy.decide(previous, report)) {
                EstimatesHistoryPolicy.PersistAction.Skip -> false
                EstimatesHistoryPolicy.PersistAction.ReplaceDay,
                EstimatesHistoryPolicy.PersistAction.AppendDay,
                -> {
                    // Always same-day replace so a race can't insert two rows for the day.
                    stateStore.saveEstimatesSnapshot(report, replaceSameDay = true)
                    true
                }
            }
        } catch (error: Throwable) {
            logger.error(TAG, "Failed to record estimates snapshot for ${report.profileName}", error)
            throw error
        }
    }

    override suspend fun estimatesHistory(profileName: String): List<IndexEstimatesReport> = try {
        // Collapse legacy multi-row days so charts stay readable without a DB wipe.
        EstimatesHistoryPolicy.coalesceDaily(stateStore.getEstimatesHistory(profileName))
    } catch (error: Throwable) {
        logger.error(TAG, "Failed to load estimates history for $profileName", error)
        throw error
    }

    companion object {
        private const val DEFAULT_PROFILE = "sp500"
        private const val REFRESH_CONCURRENCY = 4
        private const val ENRICHMENT_CONCURRENCY = 2
        private const val MAX_RETRY_ROUNDS = 3
        private const val MAX_REVISION_HISTORY = 240
        private const val TAG = "DiscountScreener"

        private fun retryBackoffMillis(round: Int): Long = when (round) {
            0 -> 1_500L
            1 -> 4_000L
            else -> 8_000L
        }
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
            revision.dcfAnalysis?.waccBps,
            revision.dcfAnalysis?.waccInputs?.isProvisional(),
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
    scoringModel: OpportunityScoringModel = OpportunityScoringModel.Legacy,
): RowDecisionState? = when {
    freshness != RowFreshness.Updated -> null
    confidence == ConfidenceBand.Low -> RowDecisionState.Avoid
    upsideBps <= 0 -> RowDecisionState.Avoid
    compositeScore < OpportunityEngine.avoidBelowScore(scoringModel) -> RowDecisionState.Avoid
    trustNote != null -> RowDecisionState.Watch
    confidence == ConfidenceBand.High &&
        compositeScore >= OpportunityEngine.actAtOrAboveScore(scoringModel) -> RowDecisionState.Act
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
