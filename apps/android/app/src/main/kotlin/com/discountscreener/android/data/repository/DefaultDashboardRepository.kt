package com.discountscreener.android.data.repository

import com.discountscreener.android.data.debug.ScoreExport
import com.discountscreener.android.data.persistence.CaptureKind
import com.discountscreener.android.data.persistence.EvaluatedSymbolState
import com.discountscreener.android.data.persistence.MetricGroupStatus
import com.discountscreener.android.data.persistence.PersistenceBootstrap
import com.discountscreener.android.data.persistence.PersistenceIssueSeverity
import com.discountscreener.android.data.persistence.PersistenceIssueSource
import com.discountscreener.android.data.persistence.PersistedChartRecord
import com.discountscreener.android.data.persistence.PersistedIssueRecord
import com.discountscreener.android.data.persistence.RawCapture
import com.discountscreener.android.data.persistence.RawCapturePayload
import com.discountscreener.android.data.persistence.RefreshMarks
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.persistence.SymbolRevisionInput
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.profile.UniverseSeedResolver
import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.InteractiveRequest
import com.discountscreener.android.data.remote.NasdaqTraderSymbolDirectoryClient
import com.discountscreener.android.data.remote.ProviderDiagnostic
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.QUOTE_BATCH_SIZE
import com.discountscreener.android.data.remote.QuoteBatchEntry
import com.discountscreener.android.data.remote.ResidualCompanyFactsProvider
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.isRateLimitDetail
import com.discountscreener.android.data.remote.isUsableCompanyName
import com.discountscreener.android.domain.model.explainOpportunityDecision
import com.discountscreener.android.domain.model.DashboardSnapshot
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.MarketReadStatus
import com.discountscreener.android.domain.model.DashboardNotice
import com.discountscreener.android.domain.model.DashboardNoticeSeverity
import com.discountscreener.android.data.market.MarketDataRepository
import com.discountscreener.android.domain.model.DiscoveryConfig
import com.discountscreener.android.domain.model.DiscoverySnapshot
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.presentation.dashboard.presentValuationJudgment
import com.discountscreener.android.domain.model.DipSetupMemo
import com.discountscreener.android.domain.model.LeftoverBoardAssembler
import com.discountscreener.android.domain.model.PlanBoardAssembler
import com.discountscreener.core.plan.DipRowInput
import com.discountscreener.core.plan.DipSignalEngine
import com.discountscreener.core.plan.LeftoverSignalEngine
import com.discountscreener.core.plan.PlanBoard
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
import com.discountscreener.android.domain.model.preferredAnalystCoverageCount
import com.discountscreener.android.domain.model.preferredAnalystTargetFairValueCents
import com.discountscreener.android.domain.model.rankMovement
import com.discountscreener.android.domain.model.significantValuationChange
import com.discountscreener.android.domain.model.reduceProfileTransition
import com.discountscreener.android.domain.repository.DashboardRepository
import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.BootstrapMarketParamsSource
import com.discountscreener.core.engine.DcfAnalysisEngine
import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.engine.MarketParamsSource
import com.discountscreener.core.engine.ENGINE_VERSION
import com.discountscreener.core.engine.EstimatesHistoryPolicy
import com.discountscreener.core.engine.IndexEstimatesEngine
import com.discountscreener.core.engine.MODEL_POLICY_VERSION
import com.discountscreener.core.engine.OpportunityContext
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.engine.PricingHistoryMerge
import com.discountscreener.core.engine.QuantLensEngine
import com.discountscreener.core.engine.QuantLensExpectedValuePolicy
import com.discountscreener.core.engine.ReportingEngine
import com.discountscreener.core.engine.ResidualFromDrivers
import com.discountscreener.core.engine.ScreenDataProjectionEngine
import com.discountscreener.core.engine.ValuationJudgmentAssembler
import com.discountscreener.core.engine.SectorBenchmarks
import com.discountscreener.core.engine.TickerSearchCandidate
import com.discountscreener.core.engine.TickerSearchEngine
import com.discountscreener.core.engine.TickerSearchRank
import com.discountscreener.core.engine.TickerSearchResult
import com.discountscreener.core.engine.buildSymbolDetail
import com.discountscreener.core.engine.checkedUpsideBps
import com.discountscreener.core.engine.computeSectorBenchmarks
import com.discountscreener.core.model.BusinessClass
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
import com.discountscreener.core.model.ExpectedValueRangeBand
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.ValuationModel
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.IssueRecord
import com.discountscreener.android.domain.model.JournalFactors
import com.discountscreener.android.domain.model.ScoreJournalRow
import com.discountscreener.android.domain.model.ScoringPreferences
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.readsSectorBenchmarks
import com.discountscreener.core.model.OpportunityRow
import com.discountscreener.core.model.OutcomeConfidence
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
import com.discountscreener.core.regime.MarketRegime
import com.discountscreener.core.regime.RegimeScoreStatus
import com.discountscreener.core.regime.RegimeScoringPolicy
import com.discountscreener.core.regime.regimeFitTerms
import com.discountscreener.core.model.SymbolRangeKey
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    val residualOutcome: ResidualFromDrivers.Outcome? = null,
    val chartError: Throwable? = null,
    val retryable: Boolean = false,
    val refreshedAtEpochSeconds: Long,
)

private data class EnrichmentResult(
    val generation: Long,
    val symbol: String,
    val chartCaptures: List<Pair<ChartRange, List<HistoricalCandle>>>,
    /** The timeseries the DCF was chosen from, for the cache. */
    val timeseries: FundamentalTimeseries?,
    val dcfAnalysis: DcfAnalysis?,
    val residualFundamentals: FundamentalSnapshot? = null,
    val errors: List<ProviderDiagnostic>,
    /** Every timeseries a provider sent this round, usable or not, for the file. */
    val fetchedTimeseries: Map<DcfSource, FundamentalTimeseries> = emptyMap(),
    /** A timeseries read back from the file, for the cache only: it is already captured there. */
    val timeseriesFromFile: FundamentalTimeseries? = null,
)

private data class PersistenceDelta(
    val rawCaptures: List<RawCapture>,
    val revisions: List<SymbolRevisionInput>,
    val issues: List<PersistedIssueRecord>,
)

/**
 * The deltas of a round since the last write, so a batch of rows costs one transaction.
 *
 * A write per symbol was the round: on the largest profile the persist took 5.3 s of a 7.7 s
 * round, at nine milliseconds a symbol, all of it in front of the next row. Captures and revisions
 * add up; the issue list is whole each time, so the last one is the one to write.
 */
private class PendingDeltas {
    private val rawCaptures = mutableListOf<RawCapture>()
    private val revisions = mutableListOf<SymbolRevisionInput>()
    private var issues: List<PersistedIssueRecord>? = null

    fun add(delta: PersistenceDelta) {
        rawCaptures += delta.rawCaptures
        revisions += delta.revisions
        issues = delta.issues
    }

    /** Hands back everything added since the last take, or null when nothing was. */
    fun take(): PersistenceDelta? {
        var taken = issues?.let { PersistenceDelta(rawCaptures.toList(), revisions.toList(), it) }
        rawCaptures.clear()
        revisions.clear()
        issues = null
        return taken
    }
}

private data class QuantLensCacheEntry(
    val fingerprint: String,
    val result: ComputationResult<QuantLensReport>,
)

private data class ProfileSwitchRequest(
    val generation: Long,
    val profile: String,
    val symbols: List<String>,
)

private data class RemoteSearchCacheEntry(
    val results: List<TickerSearchCandidate>,
    val cachedAtEpochSeconds: Long,
)

/**
 * What a refresh leaves on file because the file's copy is less than a day old. [quotedAt] is the
 * symbols whose `quoteSummary` is fresh, with the time of that capture; [chart] the symbols whose
 * year chart is fresh; [timeseries] the symbols whose fundamental timeseries is fresh, so a DCF
 * that needs one reads it from the file instead of the wire. A forced refresh leaves all three
 * empty and buys everything again.
 */
private data class FreshCaptureSkip(
    val quotedAt: Map<String, Long> = emptyMap(),
    val chart: Set<String> = emptySet(),
    val timeseries: Set<String> = emptySet(),
) {
    val quote: Set<String> get() = quotedAt.keys
}

private const val REMOTE_SEARCH_CACHE_MAX_ENTRIES = 50
private const val REMOTE_SEARCH_CACHE_TTL_SECONDS = 300L

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDashboardRepository(
    private val stateStore: SQLiteStateStore,
    private val profileCatalog: ProfileCatalog,
    private val yahooClient: YahooFinanceClient,
    private val universeCatalog: UniverseCatalog,
    private val universeSeedResolver: UniverseSeedResolver = UniverseSeedResolver(
        universeCatalog = universeCatalog,
        remoteDirectoryClient = NasdaqTraderSymbolDirectoryClient(),
    ),
    private val secondaryTimeseriesProvider: FundamentalTimeseriesProvider? = null,
    /**
     * The market read behind the 4th scoring dimension. Null leaves every row's dimension
     * `Unavailable`, which is what the tests that predate it want and what an install with no
     * network gets.
     */
    private val marketDataRepository: MarketDataRepository? = null,
    private val nowProvider: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = ioDispatcher,
    private val logger: AppLogger = NoOpAppLogger,
    /**
     * Startup universe. Debug/agent QA must use [QA_PROFILE] (≤20 symbols).
     * Release product default remains [PRODUCT_DEFAULT_PROFILE] (`sp500`).
     */
    private val defaultProfile: String = PRODUCT_DEFAULT_PROFILE,
    /**
     * Live FRED + versioned ERP in production. Tests keep the bootstrap default
     * so they stay on rf=430 / erp=450 / provisional=true.
     */
    private val marketParamsSource: MarketParamsSource = BootstrapMarketParamsSource,
    /**
     * Test probe. Runs while [stateMutex] is held, before the snapshot is built.
     * Production leaves this null.
     */
    private val beforeSnapshotLocked: (suspend () -> Unit)? = null,
    /**
     * Offered every screen input, so it can be replayed off the device.
     *
     * The repository does not know what the sink does with it. Production wires a file writer that
     * stays asleep until it is armed; tests leave this null.
     */
    private val projectionCapture: ((ScreenDataProjectionRequest) -> Unit)? = null,
) : DashboardRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stateMutex = Mutex()
    private val updates = MutableStateFlow(0L)

    private val loadsInFlight = AtomicInteger(0)
    private val loadRunning = MutableStateFlow(false)

    /**
     * True while a refresh or an enrichment still has symbols to load.
     *
     * Android freezes a process it believes is idle, and a load that stops when the user leaves the
     * screen is the thing Juan asked to fix. The app layer reads this and holds the process up for
     * as long as it says true. Nothing in the repository acts on it.
     */
    val loadInFlight: StateFlow<Boolean> = loadRunning.asStateFlow()
    private val dcfSourceCoordinator = DcfSourceCoordinator(yahooClient, secondaryTimeseriesProvider)
    private val residualFactsProvider: ResidualCompanyFactsProvider? =
        secondaryTimeseriesProvider as? ResidualCompanyFactsProvider
    private val residualChainRan = ConcurrentHashMap.newKeySet<String>()
    private val screenDataProjectionEngine = ScreenDataProjectionEngine()
    private val discoveryCoordinator = DiscoveryCoordinator(
        stateStore = stateStore,
        universeSeedResolver = universeSeedResolver,
        yahooClient = yahooClient,
        nowProvider = nowProvider,
        ioDispatcher = ioDispatcher,
    )

    private var engine = ReportingEngine()
    private var trackedSymbols = mutableListOf<String>()
    private val revisions = linkedMapOf<String, MutableList<SymbolRevision>>()
    private val chartCache = linkedMapOf<String, List<HistoricalCandle>>()
    private val replayBackingCache = linkedMapOf<String, List<HistoricalCandle>>()
    private val chartSummaries = linkedMapOf<String, MutableMap<ChartRange, ChartRangeSummary>>()
    private val dipSetups = DipSetupMemo(DipSignalEngine::evaluate)
    private val leftoverSetups = DipSetupMemo(LeftoverSignalEngine::evaluate)
    private val dcfCache = linkedMapOf<String, DcfAnalysis>()

    /**
     * Symbols the audited source was already asked for, so it is asked once and not once per open.
     *
     * A symbol SEC has nothing for (a foreign issuer, a fund) would otherwise pay the whole
     * companyfacts round trip again every time the user opens it.
     */
    private val secondaryAsked = linkedSetOf<String>()
    @Volatile
    private var lastMarketParams: MarketParams = MarketParams()
    private val marketParamsPrefetch = repositoryScope.launch {
        logger.info(TAG, "market params prefetch start")
        var params = runCatching { marketParamsSource.current() }
            .onFailure { error -> logger.error(TAG, "market params prefetch failed", error) }
            .getOrElse { MarketParams() }
        logger.info(TAG, "market params ${params.displayLabel()}")
        lastMarketParams = params
        var evicted = false
        stateMutex.withLock {
            var stale = dcfCache.filterValues { analysis ->
                !analysis.reasonCodes.contains(params.fingerprint())
            }.keys.toList()
            stale.forEach { symbol -> dcfCache.remove(symbol) }
            evicted = stale.isNotEmpty()
        }
        if (evicted) {
            updates.value += 1L
        }
    }
    private val timeseriesCache = linkedMapOf<String, FundamentalTimeseries>()
    private val quantLensCache = linkedMapOf<String, QuantLensCacheEntry>()
    private val issues = linkedMapOf<String, PersistedIssueRecord>()
    private val staleSymbols = linkedSetOf<String>()
    private val placeholderSymbols = linkedSetOf<String>()
    private val refreshedSymbols = linkedSetOf<String>()

    /**
     * Rows this refresh priced in the batch pass and otherwise left as the file had them, because
     * their own `quoteSummary` is less than a day old. They read Restored, with the time of that
     * quote, not Live: the price is today's and the valuation around it is yesterday's at best.
     * Cleared when a refresh starts; a symbol leaves when its own quote lands.
     */
    private val keptSymbols = linkedSetOf<String>()
    private val refreshAttemptedSymbols = linkedSetOf<String>()
    private val comparisonBaselineRankBySymbol = linkedMapOf<String, Int>()
    private val comparisonBaselineOpportunityRankByModel =
        OpportunityScoringModel.entries.associateWith { linkedMapOf<String, Int>() }.toMutableMap()
    private val comparisonBaselineWeightedFairValueBySymbol = linkedMapOf<String, Long>()
    private val comparisonBaselineMarketPriceBySymbol = linkedMapOf<String, Long>()
    private val freshnessTimestampBySymbol = linkedMapOf<String, Long>()
    private val companyNameBySymbol = linkedMapOf<String, String>()
    private val remoteSearchCache = linkedMapOf<String, RemoteSearchCacheEntry>()

    private var marketRegime: MarketRegime? = null
    private var marketReadAttempted = false
    private var activeMarketReadJob: Job? = null
    private var regimeDailySummaries: Map<String, ChartRangeSummary> = emptyMap()
    @Volatile
    private var regimeScoringEnabled = ScoringPreferences.DEFAULT_REGIME_ENABLED

    private var currentProfile = defaultProfile
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

    /**
     * Held for the whole of [startRefresh], so a refresh replaces the one before it.
     *
     * [startRefresh] reads [activeRefreshJob], clears it, cancels it, and only then launches the
     * new one. The cancel has to happen with [stateMutex] free, because the job being cancelled
     * takes that lock to clean up. So there was a gap where [activeRefreshJob] was already null
     * and no new job had been put there yet, and a second [startRefresh] landing in that gap found
     * nothing to cancel and launched a second refresh beside the first.
     *
     * Measured on the device: open the app on sp500 and press Refresh while the opening refresh is
     * still running. Two passes ran, both asked Yahoo for the same batch of prices, and the log
     * read `refresh.prices.first-batch rows=0` twice and `refresh.prices.done priced=0 of 497`.
     * The forced refresh then bought all 497 quotes one at a time, which is the pass the batch was
     * there to avoid, and the user saw a button that changed nothing.
     *
     * A lock of its own rather than [stateMutex]: no refresh job ever takes this one, so holding it
     * across `cancelAndJoin` cannot wait on the job being joined.
     */
    private val refreshStartMutex = Mutex()

    /**
     * Refresh passes alive at once, and the most there have ever been. Read by a test.
     *
     * A second pass is invisible from outside: it asks the same provider for the same symbols and
     * writes the same rows, so it reads as one slow refresh rather than as two. It is counted
     * because nothing in the shape of the code says two passes cannot overlap.
     */
    private var refreshPassesRunning = 0
    private var peakRefreshPassesRunning = 0

    internal fun peekPeakRefreshPasses(): Int = peakRefreshPassesRunning

    /**
     * When the running refresh was asked for.
     *
     * Read by the round to time the first symbol that reaches the screen, which is the number a
     * user feels as "the refresh does not start". Written by [startRefresh] and read by the job it
     * starts, so it is volatile; a stale read costs one wrong timing line and nothing else.
     */
    @Volatile
    private var refreshRequestedNanos = 0L

    /** Whether this refresh has logged its first symbol yet; the retry rounds must not log it again. */
    @Volatile
    private var refreshFirstSymbolLogged = false

    override fun observeUpdates(): Flow<Long> = updates.asStateFlow()

    override suspend fun bootstrap(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot {
        if (!restored) {
            loadUniverse(defaultProfile)
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
            beforeSnapshotLocked?.invoke()
            timedStage("snapshot.build") {
                snapshotLocked(filter, selectedSymbol, selectedRange, opportunityScoringModel)
            }
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

    override suspend fun loadScoringPreferences(): ScoringPreferences {
        val preferences = stateStore.loadScoringPreferences()
        regimeScoringEnabled = preferences.regimeScoringEnabled
        return preferences
    }

    override suspend fun persistScoringPreferences(preferences: ScoringPreferences) {
        regimeScoringEnabled = preferences.regimeScoringEnabled
        stateStore.saveScoringPreferences(preferences)
    }

    /**
     * Read the market after the symbol refresh, never beside it.
     *
     * A market read is one daily chart per tracked symbol plus a dozen index series, through the
     * same governor and the same store as the refresh. Started beside the refresh it doubled the
     * calls on the wire while the list was still filling, and its write of two thousand years of
     * bars held the store's connection for forty-three seconds with the quotes waiting behind it.
     * So it starts when the refresh has ended, from [finishRefresh], and is launched and forgotten:
     * when a reading arrives it bumps [updates], the snapshot is rebuilt, and the fourth dimension
     * appears. Until then every row reports it `Pending`, which is the truth.
     */
    private suspend fun startMarketReadForCurrentProfile() {
        val market = marketDataRepository
        if (market == null) {
            repositoryScope.launch {
                stateMutex.withLock { marketReadAttempted = true }
                updates.value = updates.value + 1
            }
            return
        }
        // Tracked so a profile switch can stop it with the rest of the profile's work. It is one
        // daily chart per tracked symbol through the same governor as the refresh, and left alone
        // it kept a switched-away profile on the wire beside the new one.
        val job = repositoryScope.launch(start = CoroutineStart.LAZY) {
            val symbols = stateMutex.withLock { trackedSymbols.toList() }
            val regime = runCatching { market.refreshIfStale(symbols) }.getOrNull()
            val usable = regime != null && RegimeScoringPolicy.fromRegime(regime) != null
            val dailySummaries = if (usable) market.cachedDailySummaries() else emptyMap()
            stateMutex.withLock {
                marketReadAttempted = true
                if (regime != null) {
                    marketRegime = regime
                    if (usable) {
                        regimeDailySummaries = dailySummaries
                    }
                }
            }
            updates.value = updates.value + 1
        }
        stateMutex.withLock {
            activeMarketReadJob?.cancel()
            activeMarketReadJob = job
        }
        job.start()
    }

    override suspend fun refreshAll(
        filter: ViewFilter,
        selectedSymbol: String?,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
        force: Boolean,
    ): DashboardSnapshot {
        reclaimPersistenceSpaceIfNeeded()
        startRefreshForCurrentProfile(
            stateMutex.withLock { trackedSymbols.toList() },
            opportunityScoringModel,
            force = force,
        )
        return currentSnapshot(filter, selectedSymbol, selectedRange, opportunityScoringModel)
    }

    /**
     * Records what this pass scored, so that in some weeks there is evidence about which model
     * works rather than only about which model is cleaner.
     *
     * Called from the refresh job once the pass has finished, which is the only moment the rows
     * exist. `refreshAll` returns before any of that lands — the dashboard has to render first —
     * so journalling at its return would record an empty list every time.
     *
     * Not called from `currentSnapshot` either, and the difference matters: a snapshot is rebuilt
     * on every state change, several times a second while data arrives, and journalling there
     * would record one pass a dozen times under a dozen timestamps.
     *
     * Failures are logged and dropped. A journal that could break a refresh would be a feature
     * that costs the app its main job in exchange for a measurement.
     *
     * One clock, on purpose. The rows are stamped with [now] and the retention cutoff is cut from
     * the same reading, so the window is always ninety days of *scoring passes*. Letting the store
     * fall back to its own clock made a pass whose stamp trailed the wall clock by more than the
     * window delete itself the moment it was written, which is what the wiring test caught.
     */
    private suspend fun journalScores(rows: List<OpportunityListRow>, model: OpportunityScoringModel) {
        if (rows.isEmpty()) return
        val scoredAt = now()
        runCatching {
            stateStore.appendScoreJournal(
                rows = rows.map { row ->
                    ScoreJournalRow(
                        symbol = row.symbol,
                        scoringModel = model.name,
                        scoredAtEpochSeconds = scoredAt,
                        fundamentalsScore = row.fundamentalsScore,
                        technicalScore = row.technicalScore,
                        forecastScore = row.forecastScore,
                        regimeScore = row.regimeScore,
                        compositeScore = row.compositeScore,
                        compositeScoreBase = row.compositeScoreBase,
                        marketPriceCents = row.marketPriceCents,
                        factors = JournalFactors(
                            fundamentals = row.fundamentalsFactors,
                            technical = row.technicalFactors,
                            forecast = row.forecastFactors,
                        ),
                    )
                },
                retentionSeconds = SCORE_JOURNAL_RETENTION_SECONDS,
                nowEpochSeconds = scoredAt,
            )
        }.onSuccess { dropped ->
            if (dropped > 0) {
                logger.info(
                    TAG,
                    "score journal: dropped $dropped row(s) older than " +
                        "${SCORE_JOURNAL_RETENTION_SECONDS / 86_400} days",
                )
            }
        }.onFailure { error ->
            logger.error(TAG, "score journal append failed", error)
        }
    }

    override suspend fun ensureDetailLoaded(
        symbol: String,
        filter: ViewFilter,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot = withContext(InteractiveRequest) {
        // The user is looking at this symbol. Every call it makes goes to the front of the
        // providers' lines, ahead of the bulk load: measured at 13.5 s behind a load of five
        // hundred without this, for two calls.
        loadDetail(symbol, filter, selectedRange, opportunityScoringModel)
    }

    private suspend fun loadDetail(
        symbol: String,
        filter: ViewFilter,
        selectedRange: ChartRange,
        opportunityScoringModel: OpportunityScoringModel,
    ): DashboardSnapshot {
        ensureAdHocSymbolLoaded(symbol)
        ensureRevisionHistoryLoaded(symbol)
        hydratePricingHistoryForDetail(symbol)

        var captures = mutableListOf<RawCapture>()
        var range = selectedRange
        var key = chartKey(symbol, range)
        if (stateMutex.withLock { chartCache[key] } == null) {
            var candles = runCatching { yahooClient.fetchHistoricalCandles(symbol, range) }.getOrNull()
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

        val detailForDcf = stateMutex.withLock { engine.detail(symbol) }
        val fundamentals = detailForDcf?.fundamentals
        val marketPriceCents = detailForDcf?.marketPriceCents?.takeIf { it > 0L }
        // Opening a symbol is where the audited filing is worth its 4 MB: the list resolved from
        // Yahoo so it could load at all, and this is the first moment one symbol is worth one file.
        val needsDcfResolve = stateMutex.withLock {
            fundamentals != null && (needsDcfResolutionLocked(symbol) || !secondaryAsked.contains(symbol))
        }
        if (fundamentals != null && needsDcfResolve && isFinancialServices(fundamentals)) {
            val outcome = residualFromDrivers(symbol, fundamentals, marketPriceCents)
            stateMutex.withLock {
                secondaryAsked.add(symbol)
                engine.ingestFundamentals(outcome.fundamentals)
                putDcfAnalysisLocked(symbol, outcome.analysis, outcome.fundamentals)
            }
        } else if (fundamentals != null && needsDcfResolve) {
            // The audited file is paid once per symbol. A symbol SEC has nothing for keeps its
            // Yahoo-sourced analysis, so it still needs resolving, and it must not pay again.
            val askSecondary = stateMutex.withLock { secondaryAsked.add(symbol) }
            val resolution = dcfSourceCoordinator.resolve(symbol, allowSecondary = askSecondary) { timeseries ->
                DcfAnalysisEngine.compute(
                    fundamentals,
                    timeseries,
                    marketPriceCents,
                    marketParams(),
                ).getOrThrow()
            }
            val resolvedAnalysis = analysisFromSelection(resolution.selection, fundamentals)
            stateMutex.withLock {
                resolution.selection.timeseries?.let { timeseries -> timeseriesCache[symbol] = timeseries }
                // Terminal not-eligible / unavailable without timeseries still needs a coverage marker.
                resolvedAnalysis?.let { analysis -> putDcfAnalysisLocked(symbol, analysis, fundamentals) }
            }
            captures += fundamentalTimeseriesCaptures(symbol, resolution.fetched, resolvedAnalysis, now())
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
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return emptyList()

        val normalizedCurrentProfile = currentProfile.trim().lowercase()
        val localProfileSuggestions = profileCatalog.searchTickers(query, currentProfile, limit)
        val candidates = mutableListOf<TickerSearchCandidate>()

        localProfileSuggestions.forEach { suggestion ->
            candidates += TickerSearchCandidate(
                symbol = suggestion.symbol,
                companyName = localCompanyNameFor(suggestion.symbol),
                profiles = suggestion.profiles,
                inCurrentProfile = suggestion.inCurrentProfile,
                matchRank = TickerSearchEngine.remapProfileMatchRank(suggestion.matchRank),
            )
        }

        companyNameIndexLocked().forEach { (symbol, companyName) ->
            val matchRank = TickerSearchEngine.companyNameMatchRank(query, companyName) ?: return@forEach
            val profiles = profileCatalog.profileMembership(symbol)
            candidates += TickerSearchCandidate(
                symbol = symbol,
                companyName = companyName,
                profiles = profiles,
                inCurrentProfile = normalizedCurrentProfile in profiles,
                matchRank = matchRank,
            )
        }

        var searchCandidates = candidates.toMutableList()
        var rankedResults = TickerSearchEngine.mergeAndRank(searchCandidates, limit)
        if (TickerSearchEngine.shouldTriggerRemoteSearch(query, rankedResults)) {
            searchCandidates += remoteSearchCandidates(query, limit)
            rankedResults = TickerSearchEngine.mergeAndRank(searchCandidates, limit)
        }
        TickerSearchEngine.typedQueryFallbackRank(trimmedQuery)?.let { fallbackRank ->
            var typedSymbol = trimmedQuery.uppercase()
            if (rankedResults.none { result -> result.symbol.equals(typedSymbol, ignoreCase = true) }) {
                var profiles = profileCatalog.profileMembership(typedSymbol)
                searchCandidates += TickerSearchCandidate(
                    symbol = typedSymbol,
                    companyName = localCompanyNameFor(typedSymbol),
                    profiles = profiles,
                    inCurrentProfile = normalizedCurrentProfile in profiles,
                    matchRank = fallbackRank,
                )
                rankedResults = TickerSearchEngine.mergeAndRank(searchCandidates, limit)
            }
        }

        hydrateMissingCompanyNames(rankedResults)

        return buildList {
            rankedResults.forEach { result ->
                add(toTickerSearchSuggestion(result))
            }
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
            startRefreshForCurrentProfile(newSymbols, opportunityScoringModel, force = false)
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
        val switchStartedNanos = System.nanoTime()
        val symbols = timedStage("switch.resolve-symbols") { resolveProfileSymbols(profile) }
        val generation = stateMutex.withLock {
            activeProfileGeneration += 1
            activeProfileGeneration
        }
        timedStage("switch.cancel-active-work") { cancelActiveProfileWork() }
        val bootstrap = timedStage("switch.load-warm-start") { loadWarmStartOrReset(symbols) }
        timedStage("switch.adopt-profile") { adoptProfileFromStore(profile, symbols, bootstrap) }
        emitUpdate()
        logStageMillis("switch.to-first-emit", millisSince(switchStartedNanos), " symbols=${symbols.size}")
        val request = ProfileSwitchRequest(
            generation = generation,
            profile = profile,
            symbols = symbols,
        )
        // The new load starts now. The load being left was cancelled above and is not waited for:
        // its calls end as their sockets are cancelled, its results are dropped by the generation
        // guard, and joining it here held the new profile off the wire for as long as the slowest
        // of its twenty-four calls took.
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

    private fun resolveProfileSymbols(profile: String): List<String> {
        val symbols = profileCatalog.loadProfile(profile).distinct().ifEmpty {
            profileCatalog.loadProfile(defaultProfile).distinct()
        }
        if (normalizeProfileName(profile) == QA_PROFILE && symbols.size > QA_MAX_SYMBOLS) {
            error(
                "qa profile must have ≤$QA_MAX_SYMBOLS symbols (got ${symbols.size}); refuse full-universe thrash",
            )
        }
        return symbols
    }

    private fun normalizeProfileName(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private suspend fun loadUniverse(profile: String) {
        var symbols = resolveProfileSymbols(profile)
        adoptProfileFromStore(profile, symbols, loadWarmStartOrReset(symbols))
        emitUpdate()
    }

    private suspend fun loadWarmStartOrReset(symbols: List<String>): PersistenceBootstrap =
        runCatching { stateStore.loadWarmStart(symbols) }
            .getOrElse { error ->
                stateStore.resetWarmStartState()
                stateMutex.withLock {
                    resetInMemoryLocked()
                    statusMessage = "SQLite warm-start reset after restore failure: ${error.message ?: "unknown error"}"
                }
                PersistenceBootstrap()
            }

    /**
     * Move to `profile` and fill it from the database, with nothing published in between.
     *
     * [resetInMemoryLocked] empties every cache. Any state the dashboard reads between that reset
     * and [hydrateWarmStartLocked] is an empty dashboard, and the rows only return when the network
     * answers — which is what a profile switch used to show: the list cleared, then refilled from
     * Yahoo while the database sat there holding the same rows. Startup always did this correctly;
     * the switch emitted the gap. The two halves are one locked step here, and the single
     * [emitUpdate] belongs to the caller.
     */
    private suspend fun adoptProfileFromStore(
        profile: String,
        symbols: List<String>,
        bootstrap: PersistenceBootstrap,
    ) {
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
        var yearCandles = runCatching {
            yahooClient.fetchHistoricalCandles(normalizedSymbol, ChartRange.Year)
        }.getOrDefault(emptyList())
        if (yearCandles.isNotEmpty()) {
            chartCaptures += ChartRange.Year to yearCandles
        }
        var adHocResidual = providerResult.fundamentals
            ?.takeIf(::isFinancialServices)
            ?.let { fund ->
                residualFromDrivers(
                    normalizedSymbol,
                    fund,
                    providerResult.snapshot?.marketPriceCents,
                    allowSecondary = false,
                )
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
            if (adHocResidual != null) {
                engine.ingestFundamentals(adHocResidual.fundamentals)
                putDcfAnalysisLocked(normalizedSymbol, adHocResidual.analysis, adHocResidual.fundamentals)
            } else {
                providerResult.fundamentals?.let(engine::ingestFundamentals)
            }
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

    /**
     * The live half of a profile switch.
     *
     * The cached half already ran, before the caller published anything, so by the time this starts
     * the dashboard is showing the database rows for the new profile. This only puts live data on
     * top of them.
     */
    private suspend fun hydrateProfileSwitch(request: ProfileSwitchRequest) {
        // A profile switch carries no model of its own — the caller is changing the universe, not
        // the scoring — so the journal records the model the user is actually looking at.
        startRefresh(
            request.symbols,
            request.generation,
            stateStore.loadScoringPreferences().opportunityModel,
            force = false,
        )
    }

    private suspend fun startRefreshForCurrentProfile(
        symbols: List<String>,
        scoringModel: OpportunityScoringModel,
        force: Boolean,
    ) {
        val generation = stateMutex.withLock { activeProfileGeneration }
        startRefresh(symbols, generation, scoringModel, force)
    }

    private suspend fun startRefresh(
        symbols: List<String>,
        generation: Long,
        scoringModel: OpportunityScoringModel,
        force: Boolean,
    ) {
        if (symbols.isEmpty()) {
            return
        }
        // One start at a time, from here to the launch. See [refreshStartMutex]: the read, the
        // cancel and the launch are one swap, and splitting them let two refreshes run at once.
        refreshStartMutex.withLock {
            refreshRequestedNanos = System.nanoTime()
            refreshFirstSymbolLogged = false

            val (previousRefreshJob, previousEnrichmentJob) = stateMutex.withLock {
                val existingRefresh = activeRefreshJob
                activeRefreshJob = null
                val existingEnrichment = activeEnrichmentJob
                activeEnrichmentJob = null
                Pair(existingRefresh, existingEnrichment)
            }
            timedStage("refresh.cancel-previous") {
                previousRefreshJob?.cancelAndJoin()
                previousEnrichmentJob?.cancelAndJoin()
            }
            val skip = if (force) FreshCaptureSkip() else freshCaptureSkip(symbols, stateStore.loadRefreshMarks())

            stateMutex.withLock {
                captureRefreshComparisonBaselineLocked()
                refreshedSymbols.clear()
                refreshAttemptedSymbols.clear()
                keptSymbols.clear()
                applyTransitionLocked(
                    reduceProfileTransition(
                        ProfileTransitionEvent.RefreshStarted(
                            profile = currentProfile,
                            // Every row of the profile, for the whole refresh. The banner used to
                            // count only the rows this refresh would buy, and it counted them twice
                            // by two different rules: the stale ones here, then the stale ones plus
                            // whatever the batch price pass missed. The total moved under the user
                            // mid-refresh and read as a number picked at random. A row already fresh
                            // on file is reported done, which it is.
                            symbolCount = symbols.size,
                        ),
                    ),
                )
                activeRefreshJob = repositoryScope.launch {
                    val thisJob = coroutineContext.job
                    loadStarted()
                    stateMutex.withLock {
                        refreshPassesRunning += 1
                        peakRefreshPassesRunning = maxOf(peakRefreshPassesRunning, refreshPassesRunning)
                    }
                    try {
                        runRefresh(symbols, generation, skip)
                        finishRefresh(generation, scoringModel, skip)
                    } finally {
                        // A cancelled refresh used to skip this: the first suspension in a cancelled
                        // coroutine throws, so the load stayed counted in and the process was held in
                        // the foreground for a load that had ended. The bookkeeping runs whatever
                        // ended the refresh; only the enrichment above needs the refresh to be whole.
                        withContext(NonCancellable) {
                            stateMutex.withLock {
                                refreshPassesRunning -= 1
                                if (activeRefreshJob === thisJob) {
                                    activeRefreshJob = null
                                }
                                if (generation == activeProfileGeneration) {
                                    applyTransitionLocked(
                                        reduceProfileTransition(
                                            ProfileTransitionEvent.RefreshFinished(
                                                activeIssueCount = issues.values.count { it.active },
                                            ),
                                        ),
                                    )
                                }
                            }
                            loadFinished()
                        }
                    }
                }
            }
        }
        emitUpdate()
    }

    /**
     * What a refresh that ran to its end does next: journal what it scored, publish, and start the
     * enrichment on the rows it brought and the market read. A refresh that was cancelled does none
     * of this, because a switch or a new refresh is already on its way and would run beside them.
     *
     * The enrichment is counted in before the refresh is counted out, so the two halves of one load
     * read as one load and the process is never let go between them. The market read is free while
     * its reading is fresh, so starting it after every refresh costs nothing on the wire between
     * readings; a switch stops the read of the profile it left, and this brings it for the new one.
     */
    private suspend fun finishRefresh(
        generation: Long,
        scoringModel: OpportunityScoringModel,
        skip: FreshCaptureSkip,
    ) {
        val symbolsToEnrich = stateMutex.withLock {
            if (generation != activeProfileGeneration) {
                return
            }
            trackedSymbols.filter { engine.detail(it) != null }
        }
        journalScores(
            stateMutex.withLock { opportunityRowsLocked(ViewFilter(), scoringModel) },
            scoringModel,
        )
        emitUpdate()
        startEnrichment(symbolsToEnrich, generation, skip)
        startMarketReadForCurrentProfile()
    }

    private fun loadStarted() {
        if (loadsInFlight.incrementAndGet() == 1) {
            loadRunning.value = true
        }
    }

    private fun loadFinished() {
        if (loadsInFlight.decrementAndGet() == 0) {
            loadRunning.value = false
            repositoryScope.launch { emitUpdate() }
        }
    }

    /**
     * A refresh in two passes: every quote, then every chart.
     *
     * A symbol used to cost its quote and its year chart back to back, so the list was fully quoted
     * only when the last chart had landed: two round trips a symbol on the one path the user watches.
     * The quotes go first now, alone on the wire, and the list is whole in half the time. The charts
     * follow for every symbol the first pass did not chart, which is all of them on a normal day. A
     * warm start hydrates the chart cache from disk, so the cache being full says nothing about
     * whether a chart is from this refresh; the pass keeps its own account.
     *
     * The first pass still charts a symbol whose quote came back empty, because the fallback that
     * stands in for the quote is built from the chart's last close.
     *
     * The charts start when the first quote round is done, beside the retry rounds of whatever that
     * round could not quote. They used to start after the retry rounds, and one symbol the server
     * would not answer held every chart back for the four backoffs of its rounds: measured on a
     * device on 2026-08-18 at twenty-four seconds of idle wire behind one symbol. A straggler whose
     * quote comes back empty in a retry round charts itself again; one chart twice is the price.
     *
     * Before either pass, [primeWarmPrices] puts today's price on every row the store already
     * knows, a few calls for the whole list; the quote pass then serves the rows it could not
     * price first, since those have nothing to show until it does.
     *
     * A capture less than a day old is left on file, per [skip]. Start asks for this path. The
     * Refresh button forces, and [skip] is empty: a new quoteSummary and year chart for every row.
     */
    private suspend fun runRefresh(
        symbols: List<String>,
        generation: Long,
        skip: FreshCaptureSkip,
    ) = coroutineScope {
        if (skip.chart.isNotEmpty()) {
            launch { timedStage("refresh.charts.restore") { restoreYearChartsFromFile(skip.chart, generation) } }
        }
        val charted = HashSet<String>()
        val retryQueue = ArrayDeque<String>()
        // The batch price pass used to run to completion before the first quoteSummary was asked
        // for. On the 1 937-symbol universe the last price batch landed 8.0 s after the switch and
        // the first quote result 8.1 s, so eight of those seconds bought nothing but prices. A row
        // whose own quote is not fresh needs a quoteSummary whatever the batch returns, so its
        // round starts now and the prices land beside it.
        val pricing = async { timedStage("refresh.prices") { primeWarmPrices(symbols, generation) } }
        val keeping = async {
            val priced = pricing.await()
            val kept = symbols.filter { symbol -> symbol in skip.quote && symbol in priced }
            stateMutex.withLock {
                if (generation == activeProfileGeneration) {
                    keepRowsLocked(kept, skip.quotedAt)
                    applyTransitionLocked(
                        reduceProfileTransition(
                            ProfileTransitionEvent.RefreshProgress(
                                profile = currentProfile,
                                // The kept rows are done: this refresh read them off file and
                                // asked nothing for them. They count against the same total as
                                // the rest.
                                completedSymbols = refreshCompletedSymbols + kept.size,
                                totalSymbols = symbols.size,
                            ),
                        ),
                    )
                }
            }
            emitUpdate()
            priced
        }
        // A row with no state on file gets no batch price at all, so it has nothing to show until
        // its own quote lands. Those go first, as they did when this pass waited for the prices.
        val warmOnFile = stateMutex.withLock { symbols.filter { symbol -> engine.detail(symbol) != null }.toSet() }
        val stale = symbols.filter { symbol -> symbol !in skip.quote }
        val staleQuote = stale.filter { symbol -> symbol !in warmOnFile } +
            stale.filter { symbol -> symbol in warmOnFile }
        if (staleQuote.isNotEmpty()) {
            processRefreshRound(
                symbols = staleQuote,
                retryQueue = retryQueue,
                charted = charted,
                generation = generation,
                recordTerminalIssues = false,
            )
        }
        val priced = keeping.await()
        // What is left is a row the day calls fresh that the batch could not price. It carries no
        // price at all, so it is asked for the full quote after all.
        val lateQuote = symbols.filter { symbol -> symbol in skip.quote && symbol !in priced }
        if (lateQuote.isNotEmpty()) {
            processRefreshRound(
                symbols = lateQuote,
                retryQueue = retryQueue,
                charted = charted,
                generation = generation,
                recordTerminalIssues = false,
            )
        }
        val uncharted = symbols.filter { symbol -> symbol !in charted && symbol !in skip.chart }
        if (uncharted.isNotEmpty()) {
            launch {
                timedStage("refresh.charts") {
                    runEnrichmentRounds(uncharted, generation, ::fetchYearChart)
                }
            }
        }
        retryUnquotedSymbols(retryQueue, generation, charted)
    }

    /** What [marks] say is fresh enough to keep, for the symbols of this refresh. */
    private fun freshCaptureSkip(symbols: List<String>, marks: Map<String, RefreshMarks>): FreshCaptureSkip {
        val nowEpoch = now()
        fun fresh(at: (RefreshMarks) -> Long?): Map<String, Long> = symbols
            .mapNotNull { symbol -> marks[symbol]?.let(at)?.let { capturedAt -> symbol to capturedAt } }
            .filter { (_, capturedAt) -> isFreshCapture(capturedAt, nowEpoch) }
            .toMap()
        return FreshCaptureSkip(
            quotedAt = fresh { it.quotedAtEpochSeconds },
            chart = fresh { it.yearChartedAtEpochSeconds }.keys,
            timeseries = fresh { it.timeseriesCapturedAtEpochSeconds }.keys,
        )
    }

    /** The rows kept as the file had them, stamped with the time of the quote they keep. */
    private fun keepRowsLocked(kept: List<String>, quotedAt: Map<String, Long>) {
        keptSymbols += kept
        kept.forEach { symbol -> quotedAt[symbol]?.let { at -> freshnessTimestampBySymbol[symbol] = at } }
    }

    /**
     * The year charts a refresh leaves on file, read back into memory so the plan board and the
     * detail see candles and not only the summaries the revisions carry.
     */
    private suspend fun restoreYearChartsFromFile(symbols: Set<String>, generation: Long) {
        val records = stateStore.loadPricingCandles(ChartRange.Year, symbols)
        stateMutex.withLock {
            if (generation != activeProfileGeneration) return
            hydrateChartRecordsLocked(records)
        }
        emitUpdate()
    }

    private fun isFreshCapture(capturedAtEpochSeconds: Long?, nowEpochSeconds: Long): Boolean {
        if (capturedAtEpochSeconds == null) return false
        return nowEpochSeconds - capturedAtEpochSeconds < FRESH_CAPTURE_SECONDS
    }

    /**
     * Pass zero of a refresh: today's price on every row the store already knows.
     *
     * Yahoo's batch quote endpoint prices hundreds of symbols in one call where `quoteSummary`
     * costs one call a symbol, so on a warm start every row shows today's price seconds in, while
     * the per-symbol pass that used to be the only source of it takes minutes on a large list.
     * The endpoint carries no analyst target and no fundamentals, so a row it prices keeps the
     * target, the signal and the fundamentals it was restored with, and keeps reading as restored
     * until its own `quoteSummary` lands: the price is fresh, the valuation around it is not, and
     * the label says the latter. A symbol the store does not know is left for the quote pass; the
     * endpoint cannot make a row from nothing.
     *
     * Returns the symbols it priced.
     */
    private suspend fun primeWarmPrices(symbols: List<String>, generation: Long): Set<String> {
        val warm = stateMutex.withLock { symbols.filter { symbol -> engine.detail(symbol) != null } }
        if (warm.isEmpty()) return emptySet()
        val priced = HashSet<String>()
        val unwritten = PendingDeltas()
        warm.chunked(QUOTE_BATCH_SIZE)
            .asFlow()
            .flatMapMerge(concurrency = yahooClient.requestCeiling) { batch ->
                flow { emit(yahooClient.fetchQuotes(batch)) }
            }
            .collect { quotes ->
                val refreshedAt = now()
                val applied = stateMutex.withLock {
                    if (generation != activeProfileGeneration) return@collect
                    quotes.values.mapNotNull { entry -> applyWarmPriceLocked(entry, refreshedAt) }
                }
                applied.forEach(unwritten::add)
                if (priced.isEmpty()) {
                    logStageMillis("refresh.prices.first-batch", millisSince(refreshRequestedNanos), " rows=${quotes.size}")
                }
                priced += quotes.keys
                emitUpdate()
                timedStage("refresh.persist") { persistPending(unwritten) }
            }
        logStageMillis("refresh.prices.done", millisSince(refreshRequestedNanos), " priced=${priced.size} of ${warm.size}")
        return priced
    }

    /** Today's price on a restored row: the snapshot is the one on file with the price replaced. */
    private fun applyWarmPriceLocked(entry: QuoteBatchEntry, refreshedAt: Long): PersistenceDelta? {
        // The batch price stands in until the row's own quote lands. Now that the two passes run
        // side by side a batch can answer after the quote did, and this used to put the batch
        // price back over it: a row whose quoteSummary 404s takes its price from the chart, and a
        // late batch overwrote that with the placeholder. What the quote settled, the batch leaves.
        if (entry.symbol in refreshedSymbols) return null
        val detail = engine.detail(entry.symbol) ?: return null
        val snapshot = MarketSnapshot(
            symbol = entry.symbol,
            companyName = entry.companyName ?: detail.companyName,
            profitable = entry.profitable ?: detail.profitable,
            marketPriceCents = entry.marketPriceCents,
            intrinsicValueCents = detail.intrinsicValueCents,
            nextEarningsEpoch = entry.nextEarningsEpoch ?: detail.nextEarningsEpoch,
        )
        engine.ingestSnapshot(snapshot)
        snapshot.companyName?.takeIf(::isUsableCompanyName)?.let { name -> companyNameBySymbol[entry.symbol] = name }
        // Its own key on file, so a batch price never reads as a quoteSummary of today.
        val capture = RawCapture(
            symbol = entry.symbol,
            captureKind = CaptureKind.Snapshot,
            scopeKey = BATCH_QUOTE_SCOPE,
            capturedAt = refreshedAt,
            payload = RawCapturePayload.Snapshot(snapshot),
        )
        return snapshotPersistenceDeltaLocked(listOf(capture), entry.symbol)
    }

    /** The retry rounds of the quote pass, for what the first round could not quote. */
    private suspend fun retryUnquotedSymbols(
        retryQueue: ArrayDeque<String>,
        generation: Long,
        charted: MutableSet<String>,
    ) {
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
                charted = charted,
                generation = generation,
                recordTerminalIssues = isFinalRound,
            )
        }
        // Any symbols still only retryable and never recorded need a terminal settle.
        if (retryQueue.isNotEmpty()) {
            processRefreshRound(
                symbols = retryQueue.toList(),
                retryQueue = ArrayDeque(),
                charted = charted,
                generation = generation,
                recordTerminalIssues = true,
            )
        }
    }

    private suspend fun processRefreshRound(
        symbols: List<String>,
        retryQueue: ArrayDeque<String>,
        charted: MutableSet<String>,
        generation: Long,
        recordTerminalIssues: Boolean,
    ) = coroutineScope {
        val roundStartedNanos = System.nanoTime()
        var applied = 0
        val unwritten = PendingDeltas()
        symbols
            .asFlow()
            // Fan-out only. What Yahoo is asked, and how fast, is the client's governor: one
            // permit per request. A permit here covered a whole symbol, which is two or three
            // round trips, so the controller was steering by a number it never measured.
            .flatMapMerge(concurrency = yahooClient.requestCeiling) { symbol ->
                flow { emit(timedStage("refresh.symbol") { fetchRefreshResult(symbol, generation) }) }
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
                if (result.chartCandles != null) {
                    charted += result.symbol
                }
                unwritten.add(
                    timedStage("refresh.apply") {
                        stateMutex.withLock {
                            applyRefreshResultLocked(
                                result = result,
                                suppressTransientRateLimits = !recordTerminalIssues,
                                recordTerminalFailure = recordTerminalIssues || !needsRecovery,
                            )
                        }
                    },
                )
                applied += 1
                if (!refreshFirstSymbolLogged) {
                    refreshFirstSymbolLogged = true
                    logStageMillis("refresh.first-symbol", millisSince(refreshRequestedNanos))
                }
                // The first row is published on its own. A batch of eight is right for the middle
                // of a round and wrong at its start: it holds the first result until seven more
                // land, and over a network that is seconds of a screen that shows nothing new.
                if (applied == 1 || applied % EMIT_UPDATE_BATCH == 0) {
                    emitUpdate()
                }
                if (applied % PERSIST_BATCH == 0) {
                    timedStage("refresh.persist") { persistPending(unwritten) }
                }
            }
        if (applied > 1 && applied % EMIT_UPDATE_BATCH != 0) {
            emitUpdate()
        }
        timedStage("refresh.persist") { persistPending(unwritten) }
        logStageMillis("refresh.round", millisSince(roundStartedNanos), " symbols=$applied")
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

        // The chart is the second pass's job; here it is fetched only when the quote came back
        // empty, because the fallback for a missing quote is built from the chart's last close.
        val chartResult = if (providerResult.snapshot == null) {
            runCatching { yahooClient.fetchHistoricalCandles(symbol, ChartRange.Year) }
        } else {
            null
        }
        val chartCandles = chartResult?.getOrNull()
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
        var residualOutcome = providerResult.fundamentals
            ?.takeIf(::isFinancialServices)
            ?.let { fund ->
                residualFromDrivers(
                    symbol,
                    fund,
                    providerResult.snapshot?.marketPriceCents
                        ?: chartCandles?.lastOrNull()?.closeCents,
                    allowSecondary = false,
                )
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
            residualOutcome = residualOutcome,
            chartError = chartResult?.exceptionOrNull(),
            retryable = providerResult.diagnostics.any { it.retryable } ||
                chartResult?.exceptionOrNull()?.let(::isRetryable) == true,
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
        val effectiveFundamentals = result.residualOutcome?.fundamentals
            ?: providerResult?.fundamentals
            ?: result.fallbackFundamentals

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
            if (result.residualOutcome != null) {
                putDcfAnalysisLocked(
                    result.symbol,
                    result.residualOutcome.analysis,
                    result.residualOutcome.fundamentals,
                )
            } else {
                recomputeCachedDcfLocked(result.symbol, it)
            }
        }
        result.fallbackTimeseries?.let { timeseries ->
            timeseriesCache[result.symbol] = timeseries
        }
        if (result.residualOutcome == null) {
            result.fallbackDcfAnalysis?.let { analysis ->
                putDcfAnalysisLocked(result.symbol, analysis, effectiveFundamentals)
            }
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
            keptSymbols.remove(result.symbol)
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
        var dashboardCandidateRows = timedPart("snapshot.candidates") { engine.filteredRows(limit = trackedSymbols.size.coerceAtLeast(1), filter = normalizedFilter) }
        var scoredOpportunityRows = timedPart("snapshot.score") { filteredScoredOpportunityRowsLocked(normalizedFilter, opportunityScoringModel) }
        var projectionCandidateRows = scoredOpportunityRows.map(::candidateRowFromOpportunityRow)
        var projectionRequest = timedPart("snapshot.request") { screenDataProjectionRequestLocked(
            filter = normalizedFilter,
            selectedSymbol = normalizedSelectedSymbol,
            selectedRange = selectedRange,
            opportunityScoringModel = opportunityScoringModel,
            candidateRows = projectionCandidateRows,
            opportunityDecisionFactsBySymbol = projectedOpportunityDecisionFactsBySymbol(scoredOpportunityRows),
            issues = issueRecords,
            issueMessagesBySymbol = trackedIssueMessages,
        ) }
        projectionCapture?.invoke(projectionRequest)
        var detailNotice: DashboardNotice? = null
        var estimatesNotice: DashboardNotice? = null
        var screenDataResult = timedPart("snapshot.project") { screenDataProjectionEngine.project(
            projectionRequest,
        ) }
        var screenData: com.discountscreener.core.model.ProjectedDashboardData
        var trackedRows: List<TrackedSymbolRow>
        var opportunityRows: List<OpportunityListRow>
        when (screenDataResult) {
            is ComputationResult.Success -> {
                screenData = screenDataResult.value
                trackedRows = timedPart("snapshot.trackedRows") { trackedRowsFromProjectionLocked(screenData.trackedRows, trackedIssueMessages) }
                opportunityRows = timedPart("snapshot.opportunityRows") { opportunityRowsFromProjectionLocked(
                    projectedRows = screenData.opportunityRows,
                    scoredRows = scoredOpportunityRows,
                    issueMessagesBySymbol = trackedIssueMessages,
                    scoringModel = opportunityScoringModel,
                ) }
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
            regimeScoringEnabled = regimeScoringEnabled,
            issues = issueRecords,
            selectedDetail = selectedDetail,
            selectedScoreRow = selectedScoreRowLocked(
                symbol = normalizedSelectedSymbol,
                scoringModel = opportunityScoringModel,
                rankedRows = opportunityRows,
            ),
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
            replayBackingCharts = if (normalizedSelectedSymbol == null) {
                emptyMap()
            } else {
                ChartRange.entries.mapNotNull { range ->
                    replayBackingCache[chartKey(normalizedSelectedSymbol, range)]?.let { range to it }
                }.toMap()
            },
            marketRegime = marketRegime,
            marketReadStatus = when {
                marketRegime != null -> MarketReadStatus.Ready
                marketDataRepository == null || marketReadAttempted -> MarketReadStatus.Unavailable
                else -> MarketReadStatus.Pending
            },
            planBoard = if (skipBoardsDuringLoadLocked()) {
                PlanBoard.EMPTY
            } else {
                timedPart("snapshot.planBoard") { PlanBoardAssembler.assemble(
                    rows = opportunityRows,
                    yearCandlesBySymbol = opportunityRows.associate { row ->
                        row.symbol to chartCache[chartKey(row.symbol, ChartRange.Year)].orEmpty()
                    },
                    fiveYearCandlesBySymbol = opportunityRows.associate { row ->
                        row.symbol to chartCache[chartKey(row.symbol, ChartRange.FiveYears)].orEmpty()
                    },
                    dcfBySymbol = dcfCache.toMap(),
                ) }
            },
            planBoardProfile = if (skipBoardsDuringLoadLocked()) {
                PlanBoard.EMPTY
            } else {
                timedPart("snapshot.planBoardProfile") { PlanBoardAssembler.assemble(
                    inputs = profileMemberInputsLocked(
                        opportunityRows = opportunityRows,
                        scoringModel = opportunityScoringModel,
                        fillMissingFundamentals = true,
                    ),
                    universeName = currentProfile,
                    evaluate = dipSetups::setup,
                ) }
            },
            leftoverBoard = if (skipBoardsDuringLoadLocked()) {
                PlanBoard.EMPTY
            } else {
                timedPart("snapshot.leftoverBoard") { LeftoverBoardAssembler.assemble(
                    inputs = leftoverInputsLocked(opportunityRows),
                    universeName = currentProfile,
                    evaluate = leftoverSetups::setup,
                ) }
            },
        )
    }

    /**
     * The profile plan board scores every name the list dropped. Under a load that is 1.4 s of
     * CPU on the same mutex the refresh needs, every eight rows. The Opportunities list does not
     * read those boards, so they wait until the first refresh has ended ([DashboardStartupPhase.Ready]).
     */
    private fun skipBoardsDuringLoadLocked(): Boolean =
        loadRunning.value || startupPhase != DashboardStartupPhase.Ready

    private fun leftoverInputsLocked(opportunityRows: List<OpportunityListRow>): List<DipRowInput> {
        return profileMemberInputsLocked(
            opportunityRows = opportunityRows,
            scoringModel = null,
            fillMissingFundamentals = false,
        )
    }

    private fun profileMemberInputsLocked(
        opportunityRows: List<OpportunityListRow>,
        scoringModel: OpportunityScoringModel?,
        fillMissingFundamentals: Boolean,
    ): List<DipRowInput> {
        var scoredBySymbol = opportunityRows.associateBy { row -> row.symbol }
        // The benchmark table reads a detail for every tracked symbol, and scoring one symbol
        // used to rebuild it. This loop scores each symbol the Opportunities list dropped, so on
        // the 1 937-symbol universe one profile plan board built the table about 1 900 times:
        // 3.7 million detail reads, 18 s of a two-core device, on the mutex the refresh needs.
        // Engine state cannot change while this holds the state mutex, so one table serves the
        // whole loop.
        var benchmarks = scoringModel?.let(::sectorBenchmarksLocked).orEmpty()
        return trackedSymbols.map { symbol ->
            var detail = engine.detail(symbol)
            var scored = scoredBySymbol[symbol]
            var fundamentalsScore = scored?.fundamentalsScore
            if (fundamentalsScore == null && fillMissingFundamentals && scoringModel != null) {
                fundamentalsScore = scoreSymbolLocked(symbol, scoringModel, benchmarks)?.fundamentalsScore
            }
            DipRowInput(
                symbol = symbol,
                companyName = resolvedCompanyNameLocked(symbol, detail) ?: scored?.companyName,
                fundamentalsScore = fundamentalsScore,
                marketPriceCents = detail?.marketPriceCents ?: scored?.marketPriceCents ?: 0L,
                streetFairValueCents = preferredAnalystTargetFairValueCents(detail)
                    ?: scored?.intrinsicValueCents
                    ?: 0L,
                analystCoverageCount = preferredAnalystCoverageCount(detail) ?: scored?.analystCoverageCount,
                technicalSignals = scored?.technicalSignals.orEmpty(),
                candles = chartCache[chartKey(symbol, ChartRange.Year)].orEmpty(),
                horizonCandles = chartCache[chartKey(symbol, ChartRange.FiveYears)].orEmpty(),
                dcf = dcfCache[symbol],
            )
        }
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
            detail != null && symbol in keptSymbols -> ProjectedProviderCategory.Restored
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
            var namesPrimary = projectedRow.valuationJudgment?.primaryCents != null
            TrackedSymbolRow(
                symbol = projectedRow.symbol,
                marketPriceCents = projectedRow.marketPriceCents ?: detail?.marketPriceCents,
                intrinsicValueCents = if (namesPrimary) fairValueCents else null,
                gapBps = if (namesPrimary) projectedRow.gapBps else null,
                upsideBps = if (namesPrimary) projectedRow.upsideBps else null,
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
                valuationStanceLabel = projectedRow.valuationJudgment?.let {
                    presentValuationJudgment(it).stanceLabel
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
            var namesPrimary = projectedRow.valuationJudgment?.primaryCents != null
            var namedFairValueCents = if (namesPrimary) {
                projectedRow.fairValueAnchor.valueCents ?: projectedRow.candidateRow.intrinsicValueCents
            } else {
                null
            }
            var gapBps = if (namesPrimary) projectedRow.gapBps else null
            var upsideBps = if (namesPrimary) projectedRow.upsideBps else null
            var freshness = projectedRow.freshness.toRowFreshness()
            var confidence = projectedRow.confidence.toConfidenceBandOrNull() ?: scoredRow?.confidence ?: projectedRow.candidateRow.confidence
            var baselineRank = comparisonBaselineOpportunityRankByModel.getValue(scoringModel)[projectedRow.symbol]
            OpportunityListRow(
                symbol = projectedRow.symbol,
                marketPriceCents = projectedRow.candidateRow.marketPriceCents,
                intrinsicValueCents = namedFairValueCents,
                nextEarningsEpoch = detail?.nextEarningsEpoch,
                outcomeConfidence = scoredRow?.outcomeConfidence ?: OutcomeConfidence.Unmeasured,
                outcomeWidthBps = scoredRow?.outcomeWidthBps,
                gapBps = gapBps,
                upsideBps = upsideBps,
                confidence = confidence,
                qualification = detail?.qualification,
                externalStatus = detail?.externalStatus,
                analystCoverageCount = preferredAnalystCoverageCount(detail),
                isWatched = detail?.isWatched ?: scoredRow?.isWatched ?: false,
                freshness = freshness,
                providerIssue = issueMessagesBySymbol[projectedRow.symbol],
                trustNote = projectedRow.trustSignal?.label,
                freshnessAsOfEpochSeconds = freshnessTimestampBySymbol[projectedRow.symbol],
                fundamentalsScore = scoredRow?.fundamentalsScore,
                technicalScore = scoredRow?.technicalScore,
                forecastScore = scoredRow?.forecastScore,
                regimeScore = scoredRow?.regimeScore,
                compositeScore = scoredRow?.compositeScore ?: 0,
                compositeScoreBase = scoredRow?.compositeScoreBase ?: 0,
                coverageCount = scoredRow?.coverageCount ?: 0,
                fundamentalsSignals = scoredRow?.fundamentalsSignals.orEmpty(),
                technicalSignals = scoredRow?.technicalSignals.orEmpty(),
                forecastSignals = scoredRow?.forecastSignals.orEmpty(),
                fundamentalsFactors = scoredRow?.fundamentalsFactors.orEmpty(),
                technicalFactors = scoredRow?.technicalFactors.orEmpty(),
                forecastFactors = scoredRow?.forecastFactors.orEmpty(),
                regimeStatus = scoredRow?.regimeStatus ?: RegimeScoreStatus.NotApplicable,
                regimeCauses = scoredRow?.regimeCauses.orEmpty(),
                regimeSignals = scoredRow?.regimeSignals.orEmpty(),
                regimeUnavailableReason = scoredRow?.regimeUnavailableReason,
                companyName = resolvedCompanyNameLocked(projectedRow.symbol, detail)
                    ?: projectedRow.candidateRow.companyName,
                rankMovement = rankMovement(baselineRank, currentIndex),
                valuationChange = significantValuationChange(
                    comparisonBaselineWeightedFairValueBySymbol[projectedRow.symbol],
                    namedFairValueCents,
                ),
                explanation = opportunityExplanationFromProjection(
                    projectedRow.symbol,
                    currentIndex,
                    namedFairValueCents,
                    baselineRank,
                ),
                decisionState = projectedRow.decision.toRowDecisionState(),
                quantLensSummary = projectedRow.quantLensSummary ?: detail?.let {
                    buildRowQuantLensSummaryLocked(
                        detail = it,
                        opportunityRow = scoredRow,
                    )
                },
                valuationStanceLabel = projectedRow.valuationJudgment?.let {
                    presentValuationJudgment(it).stanceLabel
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
            regimeSummariesBySymbol = regimeDailySummaries,
            marketRegime = marketRegime,
            regimeScoringEnabled = regimeScoringEnabled,
            sectorBenchmarks = sectorBenchmarksLocked(scoringModel),
            timeseriesBySymbol = timeseriesCache,
        ),
    )

    /**
     * The sector levels for one pass of the list, computed from **every** ingested symbol.
     *
     * The cohort, not the qualified list: qualification keeps roughly one symbol in eight, and a
     * sector centre taken over the survivors would be the level of the cheap tail rather than the
     * level of the sector. It is recomputed per pass rather than cached — it is one pass over the
     * same details [OpportunityEngine.buildRows] is about to score, so a cache here would buy
     * nothing and would need an invalidation rule that could go stale.
     */
    private fun sectorBenchmarksLocked(
        scoringModel: OpportunityScoringModel,
    ): Map<String, SectorBenchmarks> {
        if (!scoringModel.readsSectorBenchmarks()) return emptyMap()
        sectorBenchmarkBuilds += 1
        return computeSectorBenchmarks(engine.trackedSymbols().mapNotNull { engine.detail(it) })
    }

    /**
     * How many sector tables this repository has built. Read by a test; nothing else reads it.
     *
     * The table is one walk over every ingested detail, so it belongs above a loop over rows. A
     * call inside that loop turns one board into a walk of the universe per row: on the
     * 1 937-symbol universe that was 3.7 million detail reads and 18 s of a two-core device, and
     * all of it under [stateMutex], which is the lock the refresh needs to apply its next result.
     * Nothing in the shape of the code says which side of a loop a call sits on, so it is counted.
     */
    private var sectorBenchmarkBuilds = 0

    internal fun peekSectorBenchmarkBuilds(): Int = sectorBenchmarkBuilds

    /**
     * Street upside per symbol, in bps, for the outcome report's context line.
     *
     * Diagnostic only, and the name says the quiet part out loud: this is the analyst anchor's
     * distance from price, computed for display beside the outcome spreads and consumed by
     * nothing. The weighted anchor is preferred, the plain one is the fallback, and a symbol with
     * neither or an unpriced row is absent rather than zero.
     */
    internal suspend fun streetDiagnosticUpsideBps(): Map<String, Int> = stateMutex.withLock {
        engine.trackedSymbols().mapNotNull { symbol ->
            val detail = engine.detail(symbol) ?: return@mapNotNull null
            val fair = detail.weightedExternalSignalFairValueCents ?: detail.externalSignalFairValueCents
            val price = detail.marketPriceCents
            if (fair == null || fair <= 0L || price <= 0L) return@mapNotNull null
            symbol to (((fair.toDouble() / price) - 1.0) * 10_000.0).toInt()
        }.toMap()
    }

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
            kept = symbol in keptSymbols,
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

    private fun selectedScoreRowLocked(
        symbol: String?,
        scoringModel: OpportunityScoringModel,
        rankedRows: List<OpportunityListRow>,
    ): OpportunityListRow? {
        if (symbol.isNullOrBlank()) {
            return null
        }
        rankedRows.firstOrNull { row -> row.symbol == symbol }?.let { return it }
        var scored = scoreSymbolLocked(symbol, scoringModel, sectorBenchmarksLocked(scoringModel))
            ?: return null
        return buildOpportunityRowLocked(
            row = scored,
            currentIndex = rankedRows.size,
            scoringModel = scoringModel,
            issueMessage = activeIssueMessagesBySymbolLocked()[symbol],
        )
    }

    /**
     * @param sectorBenchmarks the table to score against. It has no default on purpose: building
     * it reads a detail for every tracked symbol, so a caller in a loop must build it once above
     * the loop. A default hid that cost inside this call and made one plan board over the
     * 1 937-symbol universe do 3.7 million detail reads, 18 s of a two-core device, on the mutex
     * the refresh needs.
     */
    private fun scoreSymbolLocked(
        symbol: String,
        scoringModel: OpportunityScoringModel,
        sectorBenchmarks: Map<String, SectorBenchmarks>,
    ): OpportunityRow? {
        var detail = engine.detail(symbol) ?: return null
        var score = OpportunityEngine.scoreWithModel(
            detail = detail,
            summary = preferredChartSummaryLocked(symbol),
            analysis = dcfCache[symbol],
            model = scoringModel,
            regimeSummary = regimeDailySummaries[symbol],
            marketRegime = marketRegime,
            regimeScoringEnabled = regimeScoringEnabled,
            sectorBenchmarks = detail.fundamentals?.sectorName
                ?.let { sectorName -> sectorBenchmarks[sectorName] },
            timeseries = timeseriesCache[symbol],
        )
        return OpportunityRow(
            symbol = detail.symbol,
            marketPriceCents = detail.marketPriceCents,
            intrinsicValueCents = detail.intrinsicValueCents,
            gapBps = detail.gapBps,
            upsideBps = detail.upsideBps,
            confidence = detail.confidence,
            isWatched = detail.isWatched,
            fundamentalsScore = score.fundamentalsScore,
            technicalScore = score.technicalScore,
            forecastScore = score.forecastScore,
            regimeScore = score.regimeScore,
            compositeScore = score.compositeScore,
            compositeScoreBase = score.compositeScoreBase,
            coverageCount = score.coverageCount,
            fundamentalsSignals = score.fundamentalsSignals,
            technicalSignals = score.technicalSignals,
            forecastSignals = score.forecastSignals,
            fundamentalsFactors = score.fundamentalsFactors,
            technicalFactors = score.technicalFactors,
            forecastFactors = score.forecastFactors,
            regimeStatus = score.regimeStatus,
            regimeCauses = score.regimeCauses,
            regimeSignals = score.regimeSignals,
            regimeUnavailableReason = score.regimeUnavailableReason,
            companyName = detail.companyName,
        )
    }

    private fun preferredChartSummaryLocked(symbol: String): ChartRangeSummary? {
        var summaries = chartSummaries[symbol] ?: return null
        return summaries[ChartRange.Year] ?: summaries.values.maxByOrNull { it.candleCount }
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
            kept = row.symbol in keptSymbols,
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
            nextEarningsEpoch = row.nextEarningsEpoch,
            outcomeConfidence = row.outcomeConfidence,
            outcomeWidthBps = row.outcomeWidthBps,
            gapBps = row.gapBps,
            upsideBps = row.upsideBps,
            confidence = row.confidence,
            qualification = detail?.qualification,
            externalStatus = detail?.externalStatus,
            analystCoverageCount = preferredAnalystCoverageCount(detail),
            isWatched = row.isWatched,
            freshness = freshness,
            providerIssue = issueMessage,
            trustNote = currentTrustNote,
            freshnessAsOfEpochSeconds = freshnessTimestampBySymbol[row.symbol],
            fundamentalsScore = row.fundamentalsScore,
            technicalScore = row.technicalScore,
            forecastScore = row.forecastScore,
            regimeScore = row.regimeScore,
            compositeScore = row.compositeScore,
            compositeScoreBase = row.compositeScoreBase,
            coverageCount = row.coverageCount,
            fundamentalsSignals = row.fundamentalsSignals,
            technicalSignals = row.technicalSignals,
            forecastSignals = row.forecastSignals,
            fundamentalsFactors = row.fundamentalsFactors,
            technicalFactors = row.technicalFactors,
            forecastFactors = row.forecastFactors,
            regimeStatus = row.regimeStatus,
            regimeCauses = row.regimeCauses,
            regimeSignals = row.regimeSignals,
            regimeUnavailableReason = row.regimeUnavailableReason,
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

        val dcf = dcfCache[detail.symbol]
        val selection = if (detail.marketPriceCents > 0L) {
            QuantLensExpectedValuePolicy.select(detail, dcf)
        } else {
            null
        }
        val evState = when {
            dcf?.resolverState == ResolverState.ProviderUncertain -> QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = QuantLensPrimaryStatus.Unavailable,
                band = ExpectedValueRangeBand.Unavailable.name,
                label = QuantLensRowLabel.EvUnavailable,
                freshnessQualifier = com.discountscreener.core.model.QuantLensFreshnessQualifier.ProviderUncertain,
                reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
            )
            selection?.band == ExpectedValueRangeBand.Disputed || selection?.band == ExpectedValueRangeBand.Tension -> QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = QuantLensPrimaryStatus.Disputed,
                band = selection.band.name,
                label = if (selection.band == ExpectedValueRangeBand.Tension) QuantLensRowLabel.EvTension else QuantLensRowLabel.EvDisputed,
                reasonCodes = selection.reasonCodes,
            )
            selection?.band == ExpectedValueRangeBand.ScenarioWeighted -> QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = selection.primaryStatus,
                band = selection.band.name,
                label = QuantLensRowLabel.EvRange,
                reasonCodes = selection.reasonCodes,
                evLowUpsideBps = boundedQuantLensRowUpsideBps(detail.marketPriceCents, selection.lowFairValueCents ?: 0L),
                evHighUpsideBps = boundedQuantLensRowUpsideBps(detail.marketPriceCents, selection.highFairValueCents ?: 0L),
            )
            selection?.band == ExpectedValueRangeBand.ReferenceOnly -> QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = QuantLensPrimaryStatus.Partial,
                band = selection.band.name,
                label = QuantLensRowLabel.EvRange,
                reasonCodes = selection.reasonCodes,
                evLowUpsideBps = selection.lowFairValueCents?.let {
                    boundedQuantLensRowUpsideBps(detail.marketPriceCents, it)
                },
                evHighUpsideBps = selection.highFairValueCents?.let {
                    boundedQuantLensRowUpsideBps(detail.marketPriceCents, it)
                },
            )
            detail.marketPriceCents > 0L -> QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = QuantLensPrimaryStatus.Sparse,
                band = QuantLensRowLabel.EvSparse.name,
                label = QuantLensRowLabel.EvSparse,
                reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
            )
            else -> QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = QuantLensPrimaryStatus.Unavailable,
                band = QuantLensRowLabel.EvUnavailable.name,
                label = QuantLensRowLabel.EvUnavailable,
                reasonCodes = listOf(QuantLensReasonCode.MissingMarketPrice),
            )
        }
        states += evState

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
            state.dcfAnalysis?.let { analysis ->
                putDcfAnalysisLocked(state.symbol, analysis, state.fundamentals)
            }
        }

        chartCache.clear()
        chartSummaries.clear()
        dipSetups.clear()
        leftoverSetups.clear()
        hydratedStates.forEach { state ->
            if (state.chartSummaries.isNotEmpty()) {
                chartSummaries.getOrPut(state.symbol) { linkedMapOf() }.putAll(
                    state.chartSummaries.associateBy { it.range },
                )
            }
        }
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
        stateMutex.withLock { hydrateChartRecordsLocked(loaded) }
    }

    private fun hydrateChartRecordsLocked(records: List<PersistedChartRecord>) {
        records.forEach { chart ->
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
        val persisted = engine.persistedState(symbol) ?: return null
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

    /**
     * Persist one delta, waiting for the write.
     *
     * Callers used to hand this to `repositoryScope.launch` — fire-and-forget, one coroutine per
     * symbol. On a 20-symbol profile that is harmless: the writes finish as fast as they arrive. On
     * the 501-symbol universe it is an unbounded producer feeding a consumer that serialises on a
     * single SQLite writer, so the launches pile up, each holding its delta and a write in flight.
     * Measured on emulator-5554 (3 GB): native memory went from 12 MB to 1.7 GB in twenty seconds
     * until Scudo could not map another page and aborted the process. The same run capped at 20
     * symbols sat flat at 18 MB for over two minutes — the cost is the pile-up, not the data.
     *
     * Waiting is the whole fix. Both callers already collect sequentially, so awaiting the write
     * back-pressures the pipeline end to end: the upstream `flatMapMerge` buffer fills, fetches stop
     * being issued, and the refresh runs at the speed the disk can absorb rather than the speed the
     * network can produce. Slower by the cost of the writes, and bounded.
     */
    private suspend fun persistDelta(delta: PersistenceDelta) {
        if (delta.rawCaptures.isNotEmpty() || delta.revisions.isNotEmpty()) {
            stateStore.persistBatch(delta.rawCaptures, delta.revisions)
        }
        stateStore.replaceIssues(delta.issues)
    }

    /** Writes what a round has gathered since its last write, in one transaction. */
    private suspend fun persistPending(pending: PendingDeltas) {
        pending.take()?.let { delta -> persistDelta(delta) }
    }


    /**
     * Stops the work of the profile being left and waits for it to end.
     *
     * For [clearAllData], where the wait is the point: the database is about to be emptied, and a
     * write still in flight would refill it.
     */
    private suspend fun stopActiveProfileWork() {
        takeActiveProfileJobs().forEach { job -> job.cancelAndJoin() }
    }

    /**
     * Stops that same work without waiting for it.
     *
     * `cancel` returns at once; `join` does not. A fetch inside a socket read used to end when the
     * read ended, so joining held the new profile off the screen for a whole network call: measured
     * at 1 185 ms against 44 ms idle, with a fetch of 600 ms. Nobody waits for the cancelled work.
     * The socket calls are cancellable, so it unwinds in a few milliseconds on its own.
     *
     * Nothing it still holds can land in the new profile. The generation is bumped before this
     * call, and every refresh result is dropped unless its generation is the active one.
     */
    private suspend fun cancelActiveProfileWork() {
        takeActiveProfileJobs().forEach { job -> job.cancel() }
    }

    private suspend fun takeActiveProfileJobs(): List<Job> = stateMutex.withLock {
        val jobs = listOfNotNull(activeProfileSwitchJob, activeRefreshJob, activeEnrichmentJob, activeMarketReadJob)
        activeProfileSwitchJob = null
        activeRefreshJob = null
        activeEnrichmentJob = null
        activeMarketReadJob = null
        jobs
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

    private suspend fun companyNameIndexLocked(): List<Pair<String, String>> = stateMutex.withLock {
        buildList {
            companyNameBySymbol.forEach { (symbol, name) ->
                if (name.isNotBlank()) add(symbol to name)
            }
            trackedSymbols.forEach { symbol ->
                val detailName = engine.detail(symbol)?.companyName?.takeIf(String::isNotBlank)
                if (detailName != null && companyNameBySymbol[symbol].isNullOrBlank()) {
                    add(symbol to detailName)
                }
            }
        }.distinctBy { (symbol, _) -> symbol.uppercase() }
    }

    private suspend fun remoteSearchCandidates(query: String, limit: Int): List<TickerSearchCandidate> {
        val cacheKey = TickerSearchEngine.normalizeSearchQueryKey(query)
        val now = nowProvider()
        stateMutex.withLock {
            remoteSearchCache[cacheKey]?.let { entry ->
                if (now - entry.cachedAtEpochSeconds <= REMOTE_SEARCH_CACHE_TTL_SECONDS) {
                    return entry.results
                }
                remoteSearchCache.remove(cacheKey)
            }
        }

        val remoteQuotes = runCatching {
            yahooClient.searchSymbols(query, limit)
        }.getOrElse { emptyList() }

        var remoteSymbols = remoteQuotes.map { quote -> quote.symbol }
        val candidates = remoteQuotes
            .filter { quote ->
                TickerSearchEngine.admitsRemoteSearchHit(quote.symbol, query, remoteSymbols)
            }
            .map { quote ->
                TickerSearchCandidate(
                    symbol = quote.symbol,
                    companyName = quote.companyName,
                    exchange = quote.exchange,
                    matchRank = TickerSearchEngine.remoteMatchRank(quote.symbol, query),
                    isRemote = true,
                )
            }

        stateMutex.withLock {
            remoteSearchCache[cacheKey] = RemoteSearchCacheEntry(
                results = candidates,
                cachedAtEpochSeconds = now,
            )
            while (remoteSearchCache.size > REMOTE_SEARCH_CACHE_MAX_ENTRIES) {
                remoteSearchCache.remove(remoteSearchCache.keys.first())
            }
            candidates.forEach { candidate ->
                candidate.companyName?.takeIf(String::isNotBlank)?.let { companyName ->
                    companyNameBySymbol.putIfAbsent(candidate.symbol, companyName)
                }
            }
        }

        return candidates
    }

    private suspend fun hydrateMissingCompanyNames(results: List<TickerSearchResult>) {
        results
            .take(4)
            .filter { result -> result.companyName.isNullOrBlank() && !result.isRemote }
            .forEach { result ->
                runCatching {
                    withContext(InteractiveRequest) { yahooClient.fetchSymbol(result.symbol).companyName }
                }.getOrNull()?.takeIf(String::isNotBlank)?.let { companyName ->
                    stateMutex.withLock {
                        companyNameBySymbol[result.symbol] = companyName
                    }
                }
            }
    }

    private suspend fun toTickerSearchSuggestion(result: TickerSearchResult): TickerSearchSuggestion {
        val companyName = result.companyName ?: localCompanyNameFor(result.symbol)
        return TickerSearchSuggestion(
            symbol = result.symbol,
            companyName = companyName,
            profiles = result.profiles,
            inCurrentProfile = result.inCurrentProfile,
            exchange = result.exchange,
            isRemote = result.isRemote,
        )
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
            marketParams = marketParams(),
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
        val selection = dcfSourceCoordinator.resolve(symbol, allowSecondary = false) { timeseries ->
            dcfFallbackFromTimeseries(
                symbol = symbol,
                companyName = companyName,
                providerFundamentals = providerFundamentals,
                chartCandles = chartCandles,
                timeseries = timeseries,
            )?.analysis
        }.selection
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
        bookValuePerShareCents = existing.bookValuePerShareCents ?: derived.bookValuePerShareCents,
        retentionBps = existing.retentionBps ?: derived.retentionBps,
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

    /**
     * Times one stage and writes the reading to the log.
     *
     * The reading goes through [logger] because that seam already exists and a bench can read it
     * back. [nowProvider] cannot serve here: it gives seconds, and every stage below is expected to
     * land under one. The `finally` keeps the line honest when the stage throws or is cancelled,
     * which is exactly the case the refresh path is suspected of.
     */
    private suspend fun <T> timedStage(stage: String, block: suspend () -> T): T {
        val startedNanos = System.nanoTime()
        try {
            return block()
        } finally {
            logStageMillis(stage, millisSince(startedNanos))
        }
    }

    private fun millisSince(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

    /** [timedStage] for a block that does not suspend. */
    private inline fun <T> timedPart(stage: String, block: () -> T): T {
        val startedNanos = System.nanoTime()
        try {
            return block()
        } finally {
            logStageMillis(stage, millisSince(startedNanos))
        }
    }

    private fun logStageMillis(stage: String, millis: Long, detail: String = "") {
        logger.info(TAG, "$STAGE_TIMING_PREFIX stage=$stage ms=$millis$detail")
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

    private suspend fun startEnrichment(
        symbols: List<String>,
        generation: Long,
        skip: FreshCaptureSkip = FreshCaptureSkip(),
    ) {
        if (symbols.isEmpty()) return
        stateMutex.withLock {
            // Re-open NotEligible once per enrichment cycle (FCF may have improved upstream).
            clearNotEligibleTimeseriesLocked(symbols)
            activeEnrichmentJob = repositoryScope.launch {
                val thisJob = coroutineContext.job
                loadStarted()
                try {
                    runEnrichment(symbols, generation, skip)
                } finally {
                    withContext(NonCancellable) {
                        stateMutex.withLock {
                            if (activeEnrichmentJob === thisJob) {
                                activeEnrichmentJob = null
                            }
                        }
                        loadFinished()
                    }
                }
            }
        }
    }

    private suspend fun runEnrichment(
        symbols: List<String>,
        generation: Long,
        skip: FreshCaptureSkip,
    ) {
        runEnrichmentRounds(symbols, generation) { symbol, gen, recordErrors ->
            enrichSymbol(symbol, gen, recordErrors, skip)
        }
    }

    /**
     * Fans [fetch] over [symbols] and applies what comes back, retrying what failed in a retryable
     * way for [MAX_RETRY_ROUNDS] more rounds. The chart pass of a refresh and the enrichment are
     * the same shape with a different fetch.
     */
    private suspend fun runEnrichmentRounds(
        symbols: List<String>,
        generation: Long,
        fetch: suspend (symbol: String, generation: Long, recordErrors: Boolean) -> EnrichmentResult,
    ) = coroutineScope {
        val pending = symbols.toMutableList()
        var round = 0
        while (pending.isNotEmpty() && round <= MAX_RETRY_ROUNDS) {
            if (round > 0) {
                delay(retryBackoffMillis(round - 1))
            }
            val batch = pending.toList()
            pending.clear()
            val finalRound = round == MAX_RETRY_ROUNDS
            var applied = 0
            val unwritten = PendingDeltas()
            batch
                .asFlow()
                .flatMapMerge(concurrency = yahooClient.requestCeiling) { symbol ->
                    flow { emit(fetch(symbol, generation, finalRound)) }
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
                        unwritten.add(delta)
                    }
                    applied += 1
                    if (applied % EMIT_UPDATE_BATCH == 0) {
                        emitUpdate()
                    }
                    if (applied % PERSIST_BATCH == 0) {
                        persistPending(unwritten)
                    }
                }
            if (applied > 0 && applied % EMIT_UPDATE_BATCH != 0) {
                emitUpdate()
            }
            persistPending(unwritten)
            round += 1
        }
    }

    /** The second pass of a refresh, one symbol: its year chart, whatever the cache holds. */
    private suspend fun fetchYearChart(
        symbol: String,
        generation: Long,
        recordErrors: Boolean,
    ): EnrichmentResult {
        val chartCaptures = mutableListOf<Pair<ChartRange, List<HistoricalCandle>>>()
        val errors = mutableListOf<ProviderDiagnostic>()
        fetchChartInto(symbol, ChartRange.Year, chartCaptures, errors)
        return EnrichmentResult(
            generation = generation,
            symbol = symbol,
            chartCaptures = chartCaptures,
            timeseries = null,
            dcfAnalysis = null,
            errors = if (recordErrors) errors else errors.filter { it.retryable },
        )
    }

    private suspend fun fetchChartInto(
        symbol: String,
        range: ChartRange,
        chartCaptures: MutableList<Pair<ChartRange, List<HistoricalCandle>>>,
        errors: MutableList<ProviderDiagnostic>,
    ) {
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

    private suspend fun enrichSymbol(
        symbol: String,
        generation: Long,
        recordErrors: Boolean,
        skip: FreshCaptureSkip = FreshCaptureSkip(),
    ): EnrichmentResult {
        val chartCaptures = mutableListOf<Pair<ChartRange, List<HistoricalCandle>>>()
        val errors = mutableListOf<ProviderDiagnostic>()

        val missingRanges = if (symbol in skip.chart) {
            emptyList()
        } else {
            stateMutex.withLock {
                listOf(ChartRange.Year).filter { range ->
                    chartCache[chartKey(symbol, range)] == null
                }
            }
        }

        for (range in missingRanges) {
            fetchChartInto(symbol, range, chartCaptures, errors)
        }

        var timeseries: FundamentalTimeseries? = null
        var fetchedTimeseries: Map<DcfSource, FundamentalTimeseries> = emptyMap()
        var timeseriesFromFile: FundamentalTimeseries? = null
        var dcfAnalysis: DcfAnalysis? = null

        val needsDcfResolve = stateMutex.withLock { needsDcfResolutionLocked(symbol) }
        var residualFundamentals: FundamentalSnapshot? = null
        if (needsDcfResolve) {
            try {
                val detailForDcf = stateMutex.withLock { engine.detail(symbol) }
                val fundamentals = detailForDcf?.fundamentals
                val marketPriceCents = detailForDcf?.marketPriceCents?.takeIf { it > 0L }
                if (fundamentals != null && isFinancialServices(fundamentals)) {
                    val outcome = residualFromDrivers(symbol, fundamentals, marketPriceCents, allowSecondary = false)
                    residualFundamentals = outcome.fundamentals
                    dcfAnalysis = outcome.analysis
                } else if (fundamentals != null) {
                    val evaluate: (FundamentalTimeseries) -> DcfAnalysis? = { selectedTimeseries ->
                        DcfAnalysisEngine.compute(
                            fundamentals,
                            selectedTimeseries,
                            marketPriceCents,
                            marketParams(),
                        ).getOrThrow()
                    }
                    // A DCF most often needs resolving because the market parameters moved, and a
                    // one-basis-point move in the risk-free rate evicts every DCF. The timeseries
                    // it is computed from is a day's worth of data, and when the file's copy is
                    // younger than that the DCF is recomputed from it. Measured 2026-08-19: this
                    // was one Yahoo timeseries call per symbol on every launch.
                    val onFile = if (symbol in skip.timeseries) timeseriesOnFile(symbol) else null
                    if (onFile != null) {
                        val selection = dcfSourceCoordinator.resolveFromFile(onFile.first, onFile.second, evaluate)
                        timeseriesFromFile = selection.timeseries
                        dcfAnalysis = analysisFromSelection(selection, fundamentals)
                    } else {
                        val resolution = dcfSourceCoordinator.resolve(symbol, allowSecondary = false, evaluate)
                        timeseries = resolution.selection.timeseries
                        fetchedTimeseries = resolution.fetched
                        dcfAnalysis = analysisFromSelection(resolution.selection, fundamentals)
                    }
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
            fetchedTimeseries = fetchedTimeseries,
            timeseriesFromFile = timeseriesFromFile,
            dcfAnalysis = dcfAnalysis,
            residualFundamentals = residualFundamentals,
            // Caller drops errors on non-final rounds; keep retryable markers for queue detection.
            errors = if (recordErrors) {
                errors
            } else {
                errors.filter { it.retryable }
            },
        )
    }

    /** The file's timeseries for [symbol] and the source it came from, or null when either is unknown. */
    private suspend fun timeseriesOnFile(symbol: String): Pair<DcfSource, FundamentalTimeseries>? {
        val persisted = stateStore.loadFundamentalTimeseries(symbol) ?: return null
        val source = DcfSource.entries.firstOrNull { it.name == persisted.sourceName } ?: return null
        return source to persisted.timeseries
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

        result.timeseriesFromFile?.let { ts -> timeseriesCache[result.symbol] = ts }
        result.timeseries?.let { ts -> timeseriesCache[result.symbol] = ts }
        rawCaptures += fundamentalTimeseriesCaptures(result.symbol, result.fetchedTimeseries, result.dcfAnalysis, capturedAt)
        result.residualFundamentals?.let { fund ->
            engine.ingestFundamentals(fund)
        }
        result.dcfAnalysis?.let { analysis ->
            putDcfAnalysisLocked(
                result.symbol,
                analysis,
                result.residualFundamentals ?: engine.detail(result.symbol)?.fundamentals,
            )
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
        ensureModelRoutedValuationLocked(symbol, fundamentals)
        val cachedAnalysis = dcfCache[symbol] ?: return
        // NotEligible stays until a live resolve reopens it (see needsDcfResolutionLocked).
        if (cachedAnalysis.resolverState == ResolverState.NotEligible) return
        if (!DcfAnalysisEngine.isCurrentPolicy(cachedAnalysis)) {
            dcfCache.remove(symbol)
            return
        }
        val selectedTimeseries = timeseriesCache[symbol]
            ?: if (cachedAnalysis.model == ValuationModel.ResidualIncomeEquity) {
                FundamentalTimeseries()
            } else {
                return
            }
        val marketPriceCents = engine.detail(symbol)?.marketPriceCents?.takeIf { it > 0L }
        val recomputed = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = selectedTimeseries,
            marketPriceCents = marketPriceCents,
            marketParams = marketParams(),
        ).getOrNull()
        if (recomputed == null) {
            dcfCache.remove(symbol)
            return
        }
        // Update numeric DCF only. Never promote RestoredOnly → Selected from cache-only recompute;
        // live Selected requires dcfSourceCoordinator.resolve (enrichment / ensureDetailLoaded).
        putDcfAnalysisLocked(
            symbol,
            recomputed.copy(
                source = cachedAnalysis.source,
                sourceFingerprint = cachedAnalysis.sourceFingerprint,
                resolverState = cachedAnalysis.resolverState,
                decisionFingerprint = cachedAnalysis.decisionFingerprint,
                provenance = cachedAnalysis.provenance,
                providerReasons = cachedAnalysis.providerReasons,
            ),
            fundamentals,
        )
    }

    /**
     * Windows `ensure_model_routed_valuation` / `ingest_dcf_analysis` parity:
     * drop stale engine/policy, refuse Unclassified/NotEligible caches, and
     * replace FCFF-on-financials with residual income when possible.
     */
    private fun putDcfAnalysisLocked(
        symbol: String,
        analysis: DcfAnalysis,
        fundamentals: FundamentalSnapshot? = null,
    ) {
        if (!admitDcfAnalysisLocked(symbol, analysis, fundamentals)) return
        dcfCache[symbol] = analysis
    }

    private fun admitDcfAnalysisLocked(
        symbol: String,
        analysis: DcfAnalysis,
        fundamentals: FundamentalSnapshot? = null,
    ): Boolean {
        if (!DcfAnalysisEngine.isCurrentPolicy(analysis)) {
            dcfCache.remove(symbol)
            return false
        }
        // Terminal NotEligible markers are intentional coverage states (missing FCF), not model-family refuse.
        if (analysis.resolverState == ResolverState.NotEligible) {
            return true
        }
        val fund = fundamentals ?: engine.detail(symbol)?.fundamentals
        if (fund != null) {
            val businessClass = DcfAnalysisEngine.classifyBusiness(
                fund.sectorName,
                fund.industryName,
                fund.sectorKey,
                fund.industryKey,
                symbol = symbol,
            )
            if (
                businessClass == BusinessClass.Unclassified ||
                businessClass == BusinessClass.NotEligible
            ) {
                dcfCache.remove(symbol)
                return false
            }
            if (
                analysis.model == ValuationModel.FcffWacc &&
                businessClass == BusinessClass.FinancialServices
            ) {
                dcfCache.remove(symbol)
                return false
            }
        }
        return true
    }

    private fun ensureModelRoutedValuationLocked(
        symbol: String,
        fundamentals: FundamentalSnapshot? = null,
    ) {
        val fund = fundamentals ?: engine.detail(symbol)?.fundamentals ?: return
        val cached = dcfCache[symbol]
        if (cached != null && !DcfAnalysisEngine.isCurrentPolicy(cached)) {
            dcfCache.remove(symbol)
        }
        val businessClass = DcfAnalysisEngine.classifyBusiness(
            fund.sectorName,
            fund.industryName,
            fund.sectorKey,
            fund.industryKey,
            symbol = symbol,
        )
        if (
            businessClass == BusinessClass.Unclassified ||
            businessClass == BusinessClass.NotEligible
        ) {
            dcfCache.remove(symbol)
            return
        }
        if (businessClass != BusinessClass.FinancialServices) return

        val current = dcfCache[symbol]
        val needsReplace = when (current?.model) {
            null -> true
            ValuationModel.FcffWacc, ValuationModel.None -> true
            ValuationModel.ResidualIncomeEquity -> !DcfAnalysisEngine.isCurrentPolicy(current)
        }
        if (!needsReplace) return

        val marketPriceCents = engine.detail(symbol)?.marketPriceCents?.takeIf { it > 0L }
        val chainRan = residualChainRan.contains(symbol)
        val outcome = ResidualFromDrivers.compute(
            yahoo = fund,
            secFactsJson = null,
            secFetchAttempted = chainRan,
            marketPriceCents = marketPriceCents,
            marketParams = marketParams(),
            instrumentId = symbol,
            shareBasis = ValuationJudgmentAssembler.SHARE_BASIS,
        )
        if (outcome.analysis.model == ValuationModel.ResidualIncomeEquity) {
            putDcfAnalysisLocked(symbol, outcome.analysis, outcome.fundamentals)
            return
        }
        if (chainRan) {
            putDcfAnalysisLocked(symbol, outcome.analysis, outcome.fundamentals)
            return
        }
        dcfCache.remove(symbol)
    }

    private fun marketParams(): MarketParams = lastMarketParams

    private fun isFinancialServices(fundamentals: FundamentalSnapshot): Boolean =
        DcfAnalysisEngine.classifyBusiness(
            fundamentals.sectorName,
            fundamentals.industryName,
            fundamentals.sectorKey,
            fundamentals.industryKey,
            symbol = fundamentals.symbol,
        ) == BusinessClass.FinancialServices

    /**
     * The residual-income chain for one financial-services symbol.
     *
     * [allowSecondary] is the same rule the DCF coordinator follows: SEC EDGAR costs a whole
     * companyfacts file per symbol, so a bulk load never asks for one. A run without SEC is not
     * recorded in [residualChainRan], because that set means "SEC was tried and gave nothing", and
     * the locked path turns it into a terminal answer.
     */
    private suspend fun residualFromDrivers(
        symbol: String,
        yahoo: FundamentalSnapshot,
        marketPriceCents: Long?,
        allowSecondary: Boolean = true,
    ): ResidualFromDrivers.Outcome {
        var provider = residualFactsProvider?.takeIf { allowSecondary }
        var slim = provider?.fetchSievedCompanyFacts(symbol)
        if (provider != null) {
            residualChainRan.add(symbol)
        }
        return ResidualFromDrivers.compute(
            yahoo = yahoo,
            secFactsJson = slim,
            secFetchAttempted = provider != null,
            marketPriceCents = marketPriceCents,
            marketParams = marketParams(),
            instrumentId = symbol,
            shareBasis = ValuationJudgmentAssembler.SHARE_BASIS,
        )
    }

    internal fun peekMarketParams(): MarketParams = lastMarketParams

    private fun needsDcfResolutionLocked(symbol: String): Boolean {
        val analysis = dcfCache[symbol] ?: return true
        if (!DcfAnalysisEngine.isCurrentPolicy(analysis)) {
            dcfCache.remove(symbol)
            return true
        }
        var params = marketParams()
        if (!analysis.reasonCodes.contains(params.fingerprint())) {
            dcfCache.remove(symbol)
            return true
        }
        val fund = engine.detail(symbol)?.fundamentals
        if (fund != null) {
            val businessClass = DcfAnalysisEngine.classifyBusiness(
                fund.sectorName,
                fund.industryName,
                fund.sectorKey,
                fund.industryKey,
                symbol = symbol,
            )
            if (
                businessClass == BusinessClass.Unclassified ||
                businessClass == BusinessClass.NotEligible
            ) {
                dcfCache.remove(symbol)
                return false
            }
            if (
                businessClass == BusinessClass.FinancialServices &&
                analysis.model == ValuationModel.FcffWacc
            ) {
                return true
            }
        }
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
            ResolverState.Unavailable,
            ResolverState.ProviderUncertain,
            -> unavailableAnalysis(selection, fundamentals)
            else -> null
        }
    }

    /** Zero-valued state only: no intrinsic/gap/scoring anchor is created. */
    private fun unavailableAnalysis(
        selection: DcfSourceSelection,
        fundamentals: FundamentalSnapshot?,
    ): DcfAnalysis {
        val source = selection.providerQualities.firstOrNull()?.source
            ?: selection.reasons.firstOrNull()?.provider
            ?: DcfSource.Unknown
        val reason = selection.reasons
            .firstOrNull { it.upstreamStatus?.isNotBlank() == true }
            ?.let { "valuation unavailable: ${it.upstreamStatus}" }
            ?: "valuation unavailable: required annual driver evidence was exhausted"
        return DcfAnalysis(
            bearIntrinsicValueCents = 0L,
            baseIntrinsicValueCents = 0L,
            bullIntrinsicValueCents = 0L,
            waccBps = 0,
            baseGrowthBps = 0,
            netDebtDollars = 0L,
            source = source,
            sourceFingerprint = selection.inputFingerprint ?: selection.decisionFingerprint,
            resolverState = ResolverState.Unavailable,
            decisionFingerprint = selection.decisionFingerprint,
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            businessClass = fundamentals?.let {
                DcfAnalysisEngine.classifyBusiness(
                    it.sectorName,
                    it.industryName,
                    it.sectorKey,
                    it.industryKey,
                    symbol = it.symbol,
                )
            } ?: BusinessClass.Unclassified,
            model = ValuationModel.None,
            provenance = DataProvenance(
                source = source,
                providerState = ProviderState.Unavailable,
                fallbackReason = selection.reasons.firstOrNull()?.code,
            ),
            providerReasons = selection.reasons,
            reasonCodes = selection.reasons.map { it.code.name },
            valuationUnavailableReason = reason,
        )
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
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            businessClass = fundamentals?.let {
                DcfAnalysisEngine.classifyBusiness(
                    it.sectorName,
                    it.industryName,
                    it.sectorKey,
                    it.industryKey,
                    symbol = it.symbol,
                )
            } ?: BusinessClass.Unclassified,
            model = ValuationModel.None,
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
            fundamentals.retentionBps,
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
        dipSetups.clear()
        leftoverSetups.clear()
        dcfCache.clear()
        secondaryAsked.clear()
        timeseriesCache.clear()
        residualChainRan.clear()
        quantLensCache.clear()
        issues.clear()
        staleSymbols.clear()
        placeholderSymbols.clear()
        refreshedSymbols.clear()
        keptSymbols.clear()
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

    override suspend fun loadSymbolNotes(): Map<String, String> = stateStore.loadSymbolNotes()

    /**
     * Straight to disk, and no `emitUpdate`. A note changes no score and no row, so a snapshot
     * rebuild here would cost the whole list to publish a sentence the caller already has.
     */
    override suspend fun saveSymbolNote(symbol: String, note: String) = stateStore.saveSymbolNote(symbol, note)

    override suspend fun loadSystemStats(): SystemStats = stateStore.getSystemStats()

    private suspend fun reclaimPersistenceSpaceIfNeeded() {
        val deleted = timedStage("refresh.reclaim") {
            runCatching { stateStore.reclaimPersistenceSpace() }.getOrDefault(0)
        }
        if (deleted > 0) {
            stateMutex.withLock {
                statusMessage = "Compacted $deleted stale database row(s)"
            }
            emitUpdate()
        }
    }

    override suspend fun pruneOldRevisions(retentionDays: Int): Int =
        stateStore.pruneOldRevisions(retentionDays)

    override suspend fun clearAllData() {
        stopActiveProfileWork()
        discoveryCoordinator.cancelActiveJob()
        stateStore.resetWarmStartState()
        stateMutex.withLock { resetInMemoryLocked() }
        emitUpdate()
    }

    override suspend fun loadDiscoverySnapshot(): DiscoverySnapshot =
        discoveryCoordinator.loadSnapshot()

    override suspend fun saveDiscoveryConfig(config: DiscoveryConfig): DiscoverySnapshot =
        discoveryCoordinator.saveConfig(config)

    override suspend fun recreateDiscoveryUniverse(): DiscoverySnapshot =
        discoveryCoordinator.recreateUniverse()

    override suspend fun refreshDiscoveryScores(): DiscoverySnapshot =
        discoveryCoordinator.refreshScores()

    override suspend fun cancelDiscoveryJob(): DiscoverySnapshot {
        discoveryCoordinator.cancelActiveJob()
        return discoveryCoordinator.loadSnapshot()
    }

    override suspend fun clearDiscoveryData(): DiscoverySnapshot =
        discoveryCoordinator.clearDiscoveryData()

    override fun observeDiscoveryProgress(): Flow<Unit> =
        discoveryCoordinator.progressTicks

    override suspend fun dcfSnapshot(): Map<String, DcfAnalysis> = stateMutex.withLock { dcfCache.toMap() }

    override suspend fun trackedSymbolDetails(): List<SymbolDetail> = stateMutex.withLock {
        trackedSymbols.mapNotNull { engine.detail(it) }
    }

    override suspend fun scoreExportCsv(
        opportunityScoringModel: OpportunityScoringModel,
    ): String = stateMutex.withLock {
        // Every candidate, qualified or not, and no view filter. The Opportunities list keeps
        // roughly one symbol in eight; a correlation over the survivors would be range-restricted
        // and would understate the very overlap this file exists to size.
        var rows = OpportunityEngine.buildRows(
            engine,
            OpportunityContext(
                filter = ViewFilter(),
                chartSummariesBySymbol = chartSummaries,
                analysesBySymbol = dcfCache,
                scoringModel = opportunityScoringModel,
                regimeSummariesBySymbol = regimeDailySummaries,
                marketRegime = marketRegime,
                regimeScoringEnabled = regimeScoringEnabled,
                sectorBenchmarks = sectorBenchmarksLocked(opportunityScoringModel),
                timeseriesBySymbol = timeseriesCache,
            ),
            includeUnqualified = true,
        )
        var qualified = engine
            .filteredRows(engine.symbolCount().coerceAtLeast(1), ViewFilter())
            .filter { it.isQualified }
            .map { it.symbol }
            .toSet()
        var details = rows.mapNotNull { engine.detail(it.symbol) }.associateBy { it.symbol }
        // The market bucket is a weighted mean of up to nine terms, so a correlation against the
        // bucket alone cannot say which term carries which sign. The terms go in the file next to
        // it, unfiltered, with the stance that weighted them.
        var policy = marketRegime?.let(RegimeScoringPolicy::fromRegime)
        var terms = if (policy == null) {
            emptyMap()
        } else {
            rows.associate { row ->
                row.symbol to regimeFitTerms(
                    details[row.symbol]?.fundamentals,
                    regimeDailySummaries[row.symbol],
                    policy,
                )
            }
        }
        ScoreExport.buildCsv(rows, qualified, details, regimeDailySummaries, terms, policy?.stance)
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

    override suspend fun ensureReplayBackingLoaded(symbol: String, range: ChartRange) {
        var key = chartKey(symbol, range)
        if (stateMutex.withLock { replayBackingCache[key] } != null) return
        try {
            var candles = withContext(InteractiveRequest) { yahooClient.fetchReplayBackingCandles(symbol, range) }
            stateMutex.withLock {
                replayBackingCache[key] = candles
            }
            emitUpdate()
        } catch (error: Throwable) {
            logger.error(TAG, "Failed to fetch replay backing candles for $symbol/$range", error)
        }
    }

    companion object {
        /** Product cold-start for release builds. */
        const val PRODUCT_DEFAULT_PROFILE = "sp500"

        /**
         * Live / agent / debug QA universe. Hard-capped membership (≤[QA_MAX_SYMBOLS]).
         * Same standing rule as Windows `npm run tauri:dev:qa`.
         */
        const val QA_PROFILE = "qa"
        const val QA_MAX_SYMBOLS = 20

        private const val MAX_RETRY_ROUNDS = 3
        private const val MAX_REVISION_HISTORY = 240
        private const val EMIT_UPDATE_BATCH = 8

        /**
         * How many applied symbols a round gathers before it writes.
         *
         * The write used to ride on [EMIT_UPDATE_BATCH], so a round of the largest universe took
         * forty-one transactions against a 298 MB file and spent 18.6 s inside `persistPending`,
         * all of it in front of the next result the round wanted to apply. The rows written are
         * the same either way; what drops is the transaction count. A round always flushes what
         * is left when it ends, so nothing waits past the round that produced it.
         */
        private const val PERSIST_BATCH = 32
        /** A quoteSummary or year chart younger than this is left on file at start. */
        private const val FRESH_CAPTURE_SECONDS = 86_400L
        private const val BATCH_QUOTE_SCOPE = "batch-quote"
        private const val TAG = "DiscountScreener"
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * First word of every timing line. A bench matches on it to pull the readings out of the
         * log; nothing in the app reads them.
         */
        internal const val STAGE_TIMING_PREFIX = "stage-timing"

        /**
         * How long a journalled score is kept: ninety days.
         *
         * Long enough to hold the 21-, 63- and 126-day horizons the retrospective reports on, and
         * short enough that a five-hundred-symbol profile refreshed daily stays in the low
         * hundreds of thousands of rows.
         */
        internal const val SCORE_JOURNAL_RETENTION_SECONDS = 220L * 24L * 60L * 60L

        private fun retryBackoffMillis(round: Int): Long = when (round) {
            0 -> 1_500L
            1 -> 4_000L
            else -> 8_000L
        }
    }
}

/**
 * One capture per provider answer, filed under the provider that sent it. The file is read back
 * by source inside the day, so the key must be who sent it and never what was made of it.
 */
private fun fundamentalTimeseriesCaptures(
    symbol: String,
    fetched: Map<DcfSource, FundamentalTimeseries>,
    analysis: DcfAnalysis?,
    capturedAt: Long,
): List<RawCapture> = fetched.map { (source, timeseries) ->
    RawCapture(
        symbol = symbol,
        captureKind = CaptureKind.FundamentalTimeseries,
        scopeKey = source.name,
        capturedAt = capturedAt,
        payload = RawCapturePayload.FundamentalTimeseries(
            value = timeseries,
            provenance = analysis?.takeIf { it.source == source }?.provenance ?: DataProvenance(),
        ),
    )
}

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
    /** Priced today, valued from a quote of the day before. Reads Restored through the refresh. */
    kept: Boolean = false,
): RowFreshness = when {
    !hasDetail && issueMessage != null -> RowFreshness.Issue
    !hasDetail -> RowFreshness.Loading
    kept -> RowFreshness.Restored
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

/**
 * Act, Watch or Avoid for a tracked row, or null when the row has nothing on file to judge.
 *
 * Freshness is not read here. A row restored from the database carries the qualification, the
 * confidence and the upside of the refresh that filed it, and those are the same numbers that made
 * its tag, so the tag is still what the app last decided about it. The screen fades it instead of
 * dropping it, and `decisionTagIsCurrent` is the one place that says which of the two it is.
 *
 * [TrackedRowState.Loading] and [TrackedRowState.Failed] are the two states with no detail on file.
 * A tag there would be built out of nulls and would read as a judgment on a row nobody has valued.
 */
internal fun trackedDecisionStateFor(
    state: TrackedRowState,
    qualification: QualificationStatus?,
    confidence: ConfidenceBand?,
    upsideBps: Int?,
    trustNote: String?,
): RowDecisionState? = when {
    state == TrackedRowState.Loading || state == TrackedRowState.Failed -> null
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
): RowDecisionState? = explainOpportunityDecision(
    freshness = freshness,
    confidence = confidence,
    upsideBps = upsideBps,
    compositeScore = compositeScore,
    trustNote = trustNote,
    scoringModel = scoringModel,
).state

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
