@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package parity

import com.discountscreener.android.data.remote.FredDgs10Client
import com.discountscreener.android.data.remote.MarketsInsiderYieldClient
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.SecIssuerComponentClient
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.YahooTnxClient
import com.discountscreener.android.data.repository.DcfSourceCoordinator
import com.discountscreener.android.domain.logging.NoOpAppLogger
import com.discountscreener.core.engine.*
import com.discountscreener.core.model.*
import com.discountscreener.core.replay.ScreenReplay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.ZoneId

/**
 * Cold-start S&P 500 load through the Android app's own data path, on a plain JVM:
 * YahooFinanceClient quote pass (+3 retry rounds) -> Year chart pass (4 passes) ->
 * DCF enrichment (4 passes) -> OpportunityEngine.buildRows(AggressiveV3, regime off) ->
 * ScreenDataProjectionEngine via ScreenReplay.project. Mirrors DefaultDashboardRepository
 * (runRefresh / fetchRefreshResult / applyRefreshResultLocked / runEnrichment / enrichSymbol /
 * snapshotLocked) minus persistence. Writes to $PARITY_OUT (default /tmp/android-jvm-parity):
 *   opps.json     the projected Opps list, in app order
 *   request.json  the ScreenDataProjectionRequest (replay with ScreenReplay)
 *   fixture.json  the raw inputs in headless-v3 fixture format (rank with Rust dump_fixture_ranking)
 */
private val ET = ZoneId.of("America/New_York")
private fun ts() = ZonedDateTime.now(ET).toString()
private fun log(m: String) = System.err.println("[${ts()}] $m")
private fun now() = System.currentTimeMillis() / 1000
private fun retryable(e: Throwable) = e is IOException || (e.message ?: "").let { it.contains("HTTP 429") || it.contains("HTTP 5") }
private const val PASSES = 4

class State {
    val engine = ReportingEngine()
    val names = mutableMapOf<String, String>()
    val candles = mutableMapOf<String, List<HistoricalCandle>>()
    val summaries = mutableMapOf<String, MutableMap<ChartRange, ChartRangeSummary>>()
    val dcf = mutableMapOf<String, DcfAnalysis>()
    val timeseries = mutableMapOf<String, FundamentalTimeseries>()
    val refreshedAt = mutableMapOf<String, Long>()
    val raw = mutableMapOf<String, ProviderFetchResult>()
    var symbols: List<String> = emptyList()
    var nowSec: Long = 0
}

fun main() = runBlocking {
    val outDir = File(System.getenv("PARITY_OUT") ?: "/tmp/android-jvm-parity").apply { mkdirs() }
    System.getenv("PARITY_REPLAY")?.let { replay(File(it), outDir); return@runBlocking }
    val profile = File("apps/android/app/src/main/assets/profiles/sp500.txt")
    val universe = LinkedHashSet(profile.readLines().map(String::trim).filter(String::isNotEmpty)).toList()
    val limit = System.getenv("PARITY_LIMIT")?.toInt()
    val only = System.getenv("PARITY_SYMBOLS")?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
    val symbols = only ?: if (limit != null) universe.take(limit) else universe
    val started = ts()
    log("start universe=${symbols.size}")

    val yahoo = YahooFinanceClient(logger = NoOpAppLogger)
    val paramsDir = File(outDir, "market-params").apply { mkdirs() }
    val params = FredThenTnxMarketParamsSource(
        fred = CachedObservedMarketParamsSource(FredDgs10Client()::csv, File(paramsDir, "dgs10.csv").toPath()),
        tnx = CachedYahooTnxMarketParamsSource({ runBlocking { YahooTnxClient().chart() } }, File(paramsDir, "tnx.json").toPath()),
    ).current()
    log("marketParams=$params")
    val issuerYield = MarketsInsiderYieldClient()
    val components = SecIssuerComponentClient(File(outDir, "sec-cache").apply { mkdirs() })
    val coordinator = DcfSourceCoordinator(yahoo)
    val s = State()

    suspend fun <T> parallel(items: List<String>, f: suspend (String) -> T): List<T> =
        items.asFlow().flatMapMerge(concurrency = yahoo.requestCeiling.coerceAtLeast(1)) { sym -> flow { emit(f(sym)) } }.toList()

    fun putChart(sym: String, c: List<HistoricalCandle>, at: Long) {
        if (c.isEmpty()) return
        s.candles[sym] = c
        s.summaries.getOrPut(sym) { linkedMapOf() }[ChartRange.Year] = ChartAnalysis.buildSummary(ChartRange.Year, c, at)
    }
    fun bclass(f: FundamentalSnapshot) = DcfAnalysisEngine.classifyBusiness(f.sectorName, f.industryName, f.sectorKey, f.industryKey, symbol = f.symbol)
    fun financial(f: FundamentalSnapshot) = bclass(f) == BusinessClass.FinancialServices
    fun admit(sym: String, a: DcfAnalysis, f: FundamentalSnapshot?) {
        // DefaultDashboardRepository.admitDcfAnalysisLocked: terminal/restored states are kept as-is,
        // then policy-version and class gates.
        val terminal = a.resolverState == ResolverState.NotEligible || a.resolverState == ResolverState.Unavailable ||
            a.resolverState == ResolverState.ProviderUncertain || a.resolverState == ResolverState.RestoredOnly
        if (!terminal && !DcfAnalysisEngine.isCurrentPolicy(a)) { s.dcf.remove(sym); return }
        if (f != null && !terminal) {
            val c = bclass(f)
            if (c == BusinessClass.Unclassified || c == BusinessClass.NotEligible) { s.dcf.remove(sym); return }
            if (a.model == ValuationModel.FcffWacc && c == BusinessClass.FinancialServices) { s.dcf.remove(sym); return }
        }
        s.dcf[sym] = a
    }

    // ---- quote pass (fetchRefreshResult + applyRefreshResultLocked), MAX_RETRY_ROUNDS = 3
    data class Q(val sym: String, val r: ProviderFetchResult, val chart: List<HistoricalCandle>?, val residual: ResidualFromDrivers.Outcome?, val retry: Boolean, val at: Long)
    suspend fun quote(sym: String): Q {
        val r = yahoo.fetchSymbol(sym)
        var chartErr: Throwable? = null
        val chart = if (r.snapshot == null) runCatching { yahoo.fetchHistoricalCandles(sym, ChartRange.Year) }.onFailure { chartErr = it }.getOrNull() else null
        val residual = r.fundamentals?.takeIf(::financial)?.let { f ->
            ResidualFromDrivers.compute(f, null, false, r.snapshot?.marketPriceCents ?: chart?.lastOrNull()?.closeCents, params, sym, ValuationJudgmentAssembler.SHARE_BASIS)
        }
        return Q(sym, r, chart, residual, r.diagnostics.any { it.retryable } || chartErr?.let(::retryable) == true, now())
    }
    fun apply(q: Q) {
        s.raw[q.sym] = q.r
        q.r.snapshot?.let { snap ->
            val name = q.r.companyName?.takeIf { it.isNotBlank() }
            name?.let { s.names[q.sym] = it }
            s.engine.ingestSnapshot(if (snap.companyName.isNullOrBlank() && name != null) snap.copy(companyName = name) else snap)
            s.refreshedAt[q.sym] = q.at
        }
        q.r.externalSignal?.let(s.engine::ingestExternal)
        val fund = q.residual?.fundamentals ?: q.r.fundamentals
        fund?.let(s.engine::ingestFundamentals)
        q.residual?.let { admit(q.sym, it.analysis, it.fundamentals) }
        q.chart?.let { putChart(q.sym, it, q.at) }
    }
    var pending = symbols
    for (round in 0..3) {
        if (pending.isEmpty()) break
        if (round > 0) delay(when (round - 1) { 0 -> 1_500L; 1 -> 4_000L; else -> 8_000L })
        val results = parallel(pending) { quote(it) }
        results.forEach { if (it.r.snapshot != null || !it.retry) apply(it) }
        pending = results.filter { it.r.snapshot == null && it.retry }.map { it.sym }
        log("quote round ${round + 1}/4: ok=${results.count { it.r.snapshot != null }} retryLeft=${pending.size}")
    }
    log("quoted=${s.refreshedAt.size} unquoted=${symbols.size - s.refreshedAt.size}")
    symbols.filter { it !in s.refreshedAt }.forEach { sym ->
        log("unquoted $sym: ${s.raw[sym]?.diagnostics?.joinToString { "${it.component}/${it.kind}: ${it.detail}" } ?: "retry rounds exhausted"}")
    }

    // ---- Year chart pass (runEnrichmentRounds { fetchYearChart })
    pending = symbols.filter { it !in s.candles }
    for (pass in 1..PASSES) {
        if (pending.isEmpty()) break
        val res = parallel(pending) { sym -> sym to runCatching { yahoo.fetchHistoricalCandles(sym, ChartRange.Year) } }
        res.forEach { (sym, r) -> r.getOrNull()?.let { putChart(sym, it, now()) } }
        pending = res.filter { (_, r) -> r.exceptionOrNull()?.let(::retryable) == true }.map { it.first }
        log("chart pass $pass/$PASSES: charted=${s.candles.size} retryLeft=${pending.size}")
    }

    // ---- DCF enrichment (runEnrichment -> enrichSymbol), 4 passes
    val resolved = mutableSetOf<String>()
    pending = symbols
    for (pass in 1..PASSES) {
        if (pending.isEmpty()) break
        val retry = java.util.Collections.synchronizedList(mutableListOf<String>())
        parallel(pending) { sym ->
            if (sym !in s.candles) runCatching { yahoo.fetchHistoricalCandles(sym, ChartRange.Year) }
                .onSuccess { synchronized(s) { putChart(sym, it, now()) } }.onFailure { if (retryable(it)) retry += sym }
            if (sym in resolved) return@parallel
            val detail = synchronized(s) { s.engine.detail(sym) }
            val fund = detail?.fundamentals ?: return@parallel
            val price = detail.marketPriceCents.takeIf { it > 0L }
            try {
                val c = bclass(fund)
                if (c == BusinessClass.Unclassified || c == BusinessClass.NotEligible) {
                    synchronized(s) { admit(sym, terminalClassificationAnalysis(c, fund), fund); resolved += sym }
                } else if (c == BusinessClass.FinancialServices) {
                    val o = ResidualFromDrivers.compute(fund, null, false, price, params, sym, ValuationJudgmentAssembler.SHARE_BASIS)
                    synchronized(s) { s.engine.ingestFundamentals(o.fundamentals); admit(sym, o.analysis, o.fundamentals); resolved += sym }
                } else {
                    val peers = synchronized(s) {
                        similarIssuerCoupons(fund.sectorName, fund.industryName, s.timeseries.mapNotNull { (o, ts) ->
                            if (o.equals(sym, true)) null else s.engine.detail(o)?.fundamentals?.let { lastFiledIssuerSample(o, it.sectorName, it.industryName, ts) }
                        })
                    }
                    val name = detail.companyName ?: s.names[sym]
                    val iy = runCatching { issuerYield.lookup(sym, name) }.getOrNull()
                    val comps = runCatching { components.lookup(sym, name) }.getOrNull()
                    val res = coordinator.resolve(sym, allowSecondary = false) { t ->
                        DcfAnalysisEngine.compute(fund, t, price, params, peerCoupons = peers, issuerYield = iy, components = comps).getOrThrow()
                    }
                    synchronized(s) {
                        res.selection.timeseries?.let { s.timeseries[sym] = it }
                        analysisFromSelection(res.selection, fund)?.let { admit(sym, it, fund) }; resolved += sym
                    }
                }
            } catch (e: Exception) {
                if (retryable(e)) retry += sym
            }
        }
        pending = retry.distinct()
        log("enrichment pass $pass/$PASSES: dcf=${s.dcf.size} retryLeft=${pending.size}")
    }

    val nowSec = now()
    s.symbols = symbols
    s.nowSec = nowSec
    rankProjectWrite(s, outDir)
    writeFixture(s, outDir)
    log("done started=$started out=$outDir")
}

private val enc = Json { encodeDefaults = true }
private val lenient = Json { ignoreUnknownKeys = true }

/**
 * Offline: rebuild State from a recorded fixture (inputs + the DCF state the live run settled on)
 * and run the same buildRows -> ScreenReplay.project path. No network.
 */
fun replay(fixtureFile: File, outDir: File) {
    val fx = Json.parseToJsonElement(fixtureFile.readText()).jsonObject
    val s = State()
    s.nowSec = fx.getValue("capturedAtEpochSeconds").jsonPrimitive.long
    val syms = mutableListOf<String>()
    fx.getValue("symbols").jsonArray.forEach { el ->
        val c = el.jsonObject
        val snap = lenient.decodeFromJsonElement(MarketSnapshot.serializer(), c.getValue("snapshot"))
        val sym = snap.symbol
        syms += sym
        s.engine.ingestSnapshot(snap)
        snap.companyName?.let { s.names[sym] = it }
        s.refreshedAt[sym] = s.nowSec
        c["external"]?.takeIf { it !is JsonNull }?.let { s.engine.ingestExternal(lenient.decodeFromJsonElement(ExternalValuationSignal.serializer(), it)) }
        c["fundamentals"]?.takeIf { it !is JsonNull }?.let { s.engine.ingestFundamentals(lenient.decodeFromJsonElement(FundamentalSnapshot.serializer(), it)) }
        c["dcf"]?.takeIf { it !is JsonNull }?.let { s.dcf[sym] = lenient.decodeFromJsonElement(DcfAnalysis.serializer(), it) }
        val candles = c["yearCandles"]?.jsonArray?.map { lenient.decodeFromJsonElement(HistoricalCandle.serializer(), it) }.orEmpty()
        if (candles.isNotEmpty()) {
            s.candles[sym] = candles
            s.summaries.getOrPut(sym) { linkedMapOf() }[ChartRange.Year] = ChartAnalysis.buildSummary(ChartRange.Year, candles, s.nowSec)
        }
    }
    s.symbols = syms
    rankProjectWrite(s, outDir)
    log("replay done symbols=${syms.size} out=$outDir")
}

fun rankProjectWrite(s: State, outDir: File) {
    val symbols = s.symbols
    val nowSec = s.nowSec
    // ---- snapshotLocked: rank, then project
    val rows = OpportunityEngine.buildRows(
        s.engine,
        OpportunityContext(
            filter = ViewFilter(),
            chartSummariesBySymbol = s.summaries,
            analysesBySymbol = s.dcf,
            scoringModel = OpportunityScoringModel.AggressiveV3,
            regimeSummariesBySymbol = emptyMap(),
            marketRegime = null,
            regimeScoringEnabled = false,
            sectorBenchmarks = if (OpportunityScoringModel.AggressiveV3.readsSectorBenchmarks())
                computeSectorBenchmarks(s.engine.trackedSymbols().mapNotNull { s.engine.detail(it) }) else emptyMap(),
            timeseriesBySymbol = s.timeseries,
        ),
    )
    val candidates = rows.map { r -> CandidateRow(r.symbol, r.marketPriceCents, r.intrinsicValueCents, r.gapBps, r.upsideBps, true, r.confidence, r.companyName) }
    val tracked = symbols
    val detailSymbols = (tracked + candidates.map { it.symbol } + s.dcf.keys).distinct()
    val details = detailSymbols.mapNotNull { sym -> s.engine.detail(sym)?.let { sym to it } }.toMap()
    val request = ScreenDataProjectionRequest(
        profile = ProjectionProfileFacts(currentProfile = "sp500"),
        route = ProjectionRoute(ViewFilter(), null, ChartRange.Year, 0, OpportunityScoringModel.AggressiveV3),
        nowEpochSeconds = nowSec,
        trackedSymbols = tracked,
        watchlistSymbols = emptySet(),
        detailsBySymbol = details,
        candidateRows = candidates,
        opportunityDecisionFactsBySymbol = rows.associate {
            it.symbol to ProjectedOpportunityDecisionFacts(
                it.compositeScore,
                it.technicalScore,
                V31ActAdjustment.histogramRatio(s.summaries[it.symbol]?.get(ChartRange.Year)),
            )
        },
        chartCandles = detailSymbols.mapNotNull { sym -> s.candles[sym]?.let { SymbolRangeKey(sym, ChartRange.Year) to it } }.toMap(),
        chartSummariesBySymbol = s.summaries.mapValues { it.value.toMap() },
        dcfBySymbol = s.dcf.toMap(),
        alertsBySymbol = s.engine.alerts().groupBy { it.symbol },
        symbolStateBySymbol = details.keys.associateWith { sym ->
            ProjectionSymbolState(sym, ProjectedProviderCategory.Live, ProjectedProvenanceState.Live, s.refreshedAt[sym])
        },
    )
    File(outDir, "request.json").writeText(ScreenReplay.encodeRequest(request))
    val projected = ScreenReplay.project(request)
    val byRow = rows.associateBy { it.symbol }
    val thr = OpportunityScoringModel.AggressiveV3
    val opps = buildJsonArray {
        projected.opportunityRows.forEachIndexed { i, p ->
            val r = byRow.getValue(p.symbol)
            add(buildJsonObject {
                put("rank", i + 1); put("symbol", p.symbol)
                put("composite", r.compositeScore); put("coverage", r.coverageCount)
                put("fundamentals", r.fundamentalsScore); put("technicals", r.technicalScore); put("forecast", r.forecastScore)
                put("rowConfidence", r.confidence.name); put("projectedConfidence", p.confidence.name)
                put("rowGapBps", r.gapBps); put("rowUpsideBps", r.upsideBps)
                put("projectedGapBps", p.gapBps); put("projectedUpsideBps", p.upsideBps)
                put("anchorCents", p.fairValueAnchor.valueCents); put("anchorSource", p.fairValueAnchor.toString().take(160))
                put("trustSignal", p.trustSignal?.kind?.name)
                put("decision", p.decision?.name)
                put("judgmentPrimaryCents", p.valuationJudgment?.primaryCents)
                // V3.1 SHADOW (not official output); diff_headless compares it separately.
                p.shadow?.let { grid ->
                    put("shadow", buildJsonArray {
                        grid.forEach { g ->
                            add(buildJsonObject {
                                put("configId", g.configId); put("bound", g.bound); put("lambdaT", g.lambdaT); put("lambdaM", g.lambdaM)
                                put("m", g.m); put("sAdj", g.sAdj); put("shadowDecision", g.shadowDecision?.name)
                            })
                        }
                    })
                }
            })
        }
    }
    File(outDir, "opps.json").writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), opps))
    val counts = projected.opportunityRows.groupingBy { it.decision?.toString() ?: "null" }.eachCount()
    log("opps=${projected.opportunityRows.size} decisions=$counts")
}

/** Raw inputs plus the settled DCF state, headless-v3 fixture format (replayable with PARITY_REPLAY). */
fun writeFixture(s: State, outDir: File) {
    val symbols = s.symbols
    val nowSec = s.nowSec
    val fixture = buildJsonObject {
        put("capturedAtEpochSeconds", nowSec)
        putJsonArray("symbols") {
            symbols.forEach { sym ->
                val r = s.raw[sym] ?: return@forEach
                val snap = r.snapshot ?: return@forEach
                add(buildJsonObject {
                    val name = r.companyName?.takeIf { it.isNotBlank() }
                    put("snapshot", enc.encodeToJsonElement(MarketSnapshot.serializer(),
                        if (snap.companyName.isNullOrBlank() && name != null) snap.copy(companyName = name) else snap))
                    r.externalSignal?.let { put("external", enc.encodeToJsonElement(ExternalValuationSignal.serializer(), it)) }
                    s.engine.detail(sym)?.fundamentals?.let { put("fundamentals", enc.encodeToJsonElement(FundamentalSnapshot.serializer(), it)) }
                    s.dcf[sym]?.let { put("dcf", enc.encodeToJsonElement(DcfAnalysis.serializer(), it)) }
                    putJsonArray("yearCandles") { s.candles[sym].orEmpty().forEach { add(enc.encodeToJsonElement(HistoricalCandle.serializer(), it)) } }
                })
            }
        }
    }
    File(outDir, "fixture.json").writeText(fixture.toString())
}
