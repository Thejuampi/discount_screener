package com.discountscreener.android.data.repository

import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.UniverseSeedResolver
import com.discountscreener.android.data.profile.UniverseSeedSource
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.isRateLimitDetail
import com.discountscreener.android.data.remote.isUsableCompanyName
import com.discountscreener.android.domain.model.DiscoveryConfig
import com.discountscreener.android.domain.model.DiscoveryJobKind
import com.discountscreener.android.domain.model.DiscoveryJobStatus
import com.discountscreener.android.domain.model.DiscoverySnapshot
import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.DiscoveryScoreRow
import com.discountscreener.core.engine.DiscoveryUniverseEngine
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.engine.ReportingEngine
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Discovery membership recreate + score refresh.
 * Never mutates the dashboard tracked book.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryCoordinator(
    private val stateStore: SQLiteStateStore,
    private val universeSeedResolver: UniverseSeedResolver,
    private val yahooClient: YahooFinanceClient,
    private val nowProvider: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scorePageSize: Int = DEFAULT_SCORE_PAGE_SIZE,
    private val refreshConcurrency: Int = DEFAULT_REFRESH_CONCURRENCY,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val jobMutex = Mutex()
    private var activeJob: Job? = null
    private var activeGeneration = 0L

    private val _progressTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val progressTicks: SharedFlow<Unit> = _progressTicks.asSharedFlow()

    suspend fun loadSnapshot(): DiscoverySnapshot {
        val config = stateStore.loadDiscoveryConfig()
        return DiscoverySnapshot(
            config = config,
            membershipCount = stateStore.discoverySymbolCount(),
            job = stateStore.loadLatestDiscoveryJob(),
            scores = stateStore.queryDiscoveryScores(
                minScore = config.minScore,
                limit = scorePageSize,
                offset = 0,
                qualifiedOnly = true,
            ),
            resultCount = stateStore.countDiscoveryScores(
                minScore = config.minScore,
                qualifiedOnly = true,
            ),
            scoredSymbolCount = stateStore.discoveryScoredSymbolCount(),
            lastScoredAtEpochSeconds = stateStore.loadDiscoveryMaxScoredAt(),
            lastSourceHint = stateStore.loadDiscoveryLastSourceHint(),
        )
    }

    suspend fun saveConfig(config: DiscoveryConfig): DiscoverySnapshot {
        stateStore.saveDiscoveryConfig(config)
        return loadSnapshot()
    }

    suspend fun clearDiscoveryData(): DiscoverySnapshot {
        cancelActiveJob()
        stateStore.clearDiscoveryData()
        return loadSnapshot()
    }

    suspend fun cancelActiveJob() {
        jobMutex.withLock {
            activeGeneration += 1
            activeJob?.cancelAndJoin()
            activeJob = null
            val latest = stateStore.loadLatestDiscoveryJob()
            if (latest != null && latest.status == DiscoveryJobStatus.Running) {
                stateStore.finishDiscoveryJob(
                    jobId = latest.jobId,
                    status = DiscoveryJobStatus.Cancelled,
                    completedSymbols = latest.completedSymbols,
                    errorSummary = "cancelled by user",
                )
            }
        }
        emitProgress()
    }

    /**
     * Rebuild membership from seed: live NASDAQ Trader directory when available,
     * otherwise bundled asset. Does not fetch market quotes/scores.
     */
    suspend fun recreateUniverse(): DiscoverySnapshot {
        cancelActiveJob()
        val config = stateStore.loadDiscoveryConfig()
        val seedResult = universeSeedResolver.resolve(config.universeName)
        val seed = seedResult.symbols
        val jobId = stateStore.createDiscoveryJob(
            kind = DiscoveryJobKind.Recreate,
            totalSymbols = seed.size,
        )
        return try {
            if (seed.isEmpty()) {
                stateStore.finishDiscoveryJob(
                    jobId = jobId,
                    status = DiscoveryJobStatus.Failed,
                    completedSymbols = 0,
                    errorSummary = "empty universe seed for ${config.universeName}",
                )
                return loadSnapshot()
            }
            val plan = stateStore.applyDiscoveryMembershipMerge(
                seed = seed,
                sourceUniverse = config.universeName,
            )
            val sourceHint = humanSourceHint(seedResult.source, seed.size)
            stateStore.saveDiscoveryLastSourceHint(sourceHint)
            stateStore.updateDiscoveryJobProgress(
                jobId = jobId,
                completedSymbols = seed.size,
                totalSymbols = seed.size,
            )
            stateStore.finishDiscoveryJob(
                jobId = jobId,
                status = DiscoveryJobStatus.Completed,
                completedSymbols = seed.size,
                errorSummary =
                    "source=${seedResult.source.name};${seedResult.detail};" +
                        "removed=${plan.toRemove.size};added=${plan.toAdd.size};kept=${plan.toKeep.size}",
            )
            loadSnapshot()
        } catch (error: CancellationException) {
            stateStore.finishDiscoveryJob(
                jobId = jobId,
                status = DiscoveryJobStatus.Cancelled,
                errorSummary = error.message,
            )
            throw error
        } catch (error: Throwable) {
            stateStore.finishDiscoveryJob(
                jobId = jobId,
                status = DiscoveryJobStatus.Failed,
                errorSummary = error.message ?: "recreate failed",
            )
            loadSnapshot()
        }.also { emitProgress() }
    }

    /**
     * Refresh scores for the current membership set only (list unchanged).
     * Fetches minimal quote + 1Y chart data for OpportunityEngine.
     */
    suspend fun refreshScores(): DiscoverySnapshot {
        cancelActiveJob()
        val config = stateStore.loadDiscoveryConfig()
        val symbols = stateStore.loadDiscoverySymbols()
        if (symbols.isEmpty()) {
            return loadSnapshot()
        }
        val jobId = stateStore.createDiscoveryJob(
            kind = DiscoveryJobKind.Refresh,
            totalSymbols = symbols.size,
        )
        // Immediate tick so UI shows Running 0/N before the first symbol finishes.
        emitProgress()
        val generation = jobMutex.withLock {
            activeGeneration += 1
            activeGeneration
        }
        val completed = AtomicInteger(0)
        val job = scope.launch {
            try {
                symbols.asFlow()
                    .flatMapMerge(concurrency = refreshConcurrency) { symbol ->
                        flow {
                            if (generation != activeGeneration) return@flow
                            val row = scoreSymbol(
                                symbol = symbol,
                                scoringModel = config.scoringModel,
                            )
                            if (generation != activeGeneration) return@flow
                            // Persist only qualified scores (slim membership book; min-score is a view filter).
                            if (row != null && row.isQualified) {
                                stateStore.upsertDiscoveryScores(listOf(row))
                            }
                            val done = completed.incrementAndGet()
                            if (done == 1 || done % PROGRESS_EVERY == 0 || done == symbols.size) {
                                stateStore.updateDiscoveryJobProgress(jobId, done, symbols.size)
                                emitProgress()
                            }
                            emit(Unit)
                        }
                    }
                    .toList()
                if (generation == activeGeneration) {
                    stateStore.finishDiscoveryJob(
                        jobId = jobId,
                        status = DiscoveryJobStatus.Completed,
                        completedSymbols = completed.get(),
                    )
                }
            } catch (error: CancellationException) {
                stateStore.finishDiscoveryJob(
                    jobId = jobId,
                    status = DiscoveryJobStatus.Cancelled,
                    completedSymbols = completed.get(),
                    errorSummary = "cancelled",
                )
                throw error
            } catch (error: Throwable) {
                stateStore.finishDiscoveryJob(
                    jobId = jobId,
                    status = DiscoveryJobStatus.Failed,
                    completedSymbols = completed.get(),
                    errorSummary = error.message ?: "refresh failed",
                )
            } finally {
                emitProgress()
            }
        }
        jobMutex.withLock { activeJob = job }
        job.join()
        return loadSnapshot()
    }

    private suspend fun scoreSymbol(
        symbol: String,
        scoringModel: OpportunityScoringModel,
    ): DiscoveryScoreRow? {
        val now = nowProvider()
        return try {
            val fetch = yahooClient.fetchSymbol(symbol)
            maybeBackoff(fetch)
            val candles = try {
                yahooClient.fetchHistoricalCandles(symbol, ChartRange.Year)
            } catch (_: IOException) {
                emptyList()
            }
            val summary = if (candles.isNotEmpty()) {
                ChartAnalysis.buildSummary(ChartRange.Year, candles, now)
            } else {
                null
            }
            val engine = ReportingEngine()
            fetch.snapshot?.let(engine::ingestSnapshot)
            fetch.externalSignal?.let(engine::ingestExternal)
            fetch.fundamentals?.let(engine::ingestFundamentals)
            val resolvedCompanyName = listOf(
                fetch.companyName,
                fetch.snapshot?.companyName,
            ).firstOrNull(::isUsableCompanyName)
            val detail = engine.detail(symbol) ?: return DiscoveryScoreRow(
                symbol = DiscoveryUniverseEngine.normalizeSymbol(symbol) ?: symbol,
                companyName = resolvedCompanyName,
                compositeScore = 0,
                fundamentalsScore = null,
                technicalScore = null,
                forecastScore = null,
                coverageCount = 0,
                marketPriceCents = fetch.snapshot?.marketPriceCents,
                upsideBps = null,
                gapBps = null,
                confidence = null,
                isQualified = false,
                scoringModel = scoringModel.name,
                scoredAtEpochSeconds = now,
                lastError = "insufficient quote data",
            )
            val score = OpportunityEngine.scoreWithModel(
                detail = detail,
                summary = summary,
                analysis = null,
                model = scoringModel,
            )
            DiscoveryScoreRow(
                symbol = detail.symbol,
                companyName = listOf(
                    detail.companyName,
                    fetch.companyName,
                    fetch.snapshot?.companyName,
                ).firstOrNull(::isUsableCompanyName),
                compositeScore = score.compositeScore,
                fundamentalsScore = score.fundamentalsScore,
                technicalScore = score.technicalScore,
                forecastScore = score.forecastScore,
                coverageCount = score.coverageCount,
                marketPriceCents = detail.marketPriceCents,
                upsideBps = detail.upsideBps,
                gapBps = detail.gapBps,
                confidence = detail.confidence.name,
                isQualified = detail.qualification == QualificationStatus.Qualified,
                scoringModel = scoringModel.name,
                scoredAtEpochSeconds = now,
                lastError = null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DiscoveryScoreRow(
                symbol = DiscoveryUniverseEngine.normalizeSymbol(symbol) ?: symbol,
                companyName = null,
                compositeScore = 0,
                fundamentalsScore = null,
                technicalScore = null,
                forecastScore = null,
                coverageCount = 0,
                marketPriceCents = null,
                upsideBps = null,
                gapBps = null,
                confidence = null,
                isQualified = false,
                scoringModel = scoringModel.name,
                scoredAtEpochSeconds = now,
                lastError = error.message ?: "score failed",
            )
        }
    }

    private suspend fun maybeBackoff(fetch: ProviderFetchResult) {
        val rateLimited = fetch.diagnostics.any { diagnostic ->
            isRateLimitDetail(diagnostic.detail) || diagnostic.detail.contains("429")
        }
        if (rateLimited) {
            delay(RATE_LIMIT_BACKOFF_MS)
        }
    }

    private fun emitProgress() {
        _progressTicks.tryEmit(Unit)
    }

    companion object {
        private const val DEFAULT_SCORE_PAGE_SIZE = 100
        private const val DEFAULT_REFRESH_CONCURRENCY = 2
        private const val PROGRESS_EVERY = 10
        private const val RATE_LIMIT_BACKOFF_MS = 1_500L

        fun humanSourceHint(source: UniverseSeedSource, symbolCount: Int): String =
            when (source) {
                UniverseSeedSource.RemoteNasdaqTrader ->
                    "Live NASDAQ · ${"%,d".format(symbolCount)}"
                UniverseSeedSource.BundledAsset ->
                    "Bundled seed · ${"%,d".format(symbolCount)}"
            }
    }
}
