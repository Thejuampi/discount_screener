package com.discountscreener.core.harness

import com.discountscreener.core.engine.ErpSchool
import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

enum class QuantDataMode {
    Live,
    Cached,
    Hardcoded,
}

data class QuantBundle(
    val symbol: String,
    val fundamentals: FundamentalSnapshot,
    val timeseries: FundamentalTimeseries,
    val marketParams: MarketParams? = null,
    val mode: QuantDataMode,
    val asOfEpochMillis: Long,
    val cacheHit: Boolean = false,
)

data class HardcodedCase(
    val symbol: String,
    val fundamentals: FundamentalSnapshot,
    val timeseries: FundamentalTimeseries,
    val marketParams: MarketParams? = null,
)

fun interface QuantLiveClient {
    fun fetch(symbol: String): QuantBundle
}

interface QuantDataSource {
    val mode: QuantDataMode
    fun load(symbol: String): QuantBundle
}

object QuantHarness {
    fun live(
        client: QuantLiveClient = YahooQuantLiveClient(),
        rates: QuantRatesSource? = null,
    ): QuantDataSource = LiveSource(client, rates)

    fun cached(
        cacheDir: Path,
        ttl: Duration = Duration.ofDays(1),
        clock: () -> Long = { System.currentTimeMillis() },
    ): QuantDataSource = cached(YahooQuantLiveClient(), cacheDir, ttl, clock)

    fun cached(
        client: QuantLiveClient,
        cacheDir: Path,
        ttl: Duration = Duration.ofDays(1),
        clock: () -> Long = { System.currentTimeMillis() },
        rates: QuantRatesSource? = null,
    ): QuantDataSource = CachedSource(client, cacheDir, ttl, clock, rates)

    fun hardcoded(vararg cases: HardcodedCase): QuantDataSource = HardcodedSource(cases.toList())

    fun liveRates(client: QuantRatesLiveClient = FredThenTnxRatesClient()): QuantRatesSource =
        LiveRatesSource(client)

    fun cachedRates(
        cacheDir: Path,
        ttl: Duration = Duration.ofDays(1),
        clock: () -> Long = { System.currentTimeMillis() },
    ): QuantRatesSource = cachedRates(FredThenTnxRatesClient(), cacheDir, ttl, clock)

    fun cachedRates(
        client: QuantRatesLiveClient,
        cacheDir: Path,
        ttl: Duration = Duration.ofDays(1),
        clock: () -> Long = { System.currentTimeMillis() },
    ): QuantRatesSource = CachedRatesSource(client, cacheDir, ttl, clock)

    fun hardcodedRates(params: MarketParams): QuantRatesSource = HardcodedRatesSource(params)
}

private class LiveSource(
    private val client: QuantLiveClient,
    private val rates: QuantRatesSource?,
) : QuantDataSource {
    override val mode: QuantDataMode = QuantDataMode.Live

    override fun load(symbol: String): QuantBundle {
        var fetched = client.fetch(symbol)
        var withRates = rates?.let { fetched.copy(marketParams = it.load().marketParams) } ?: fetched
        return withRates.copy(mode = QuantDataMode.Live, cacheHit = false)
    }
}

private class HardcodedSource(
    cases: List<HardcodedCase>,
) : QuantDataSource {
    override val mode: QuantDataMode = QuantDataMode.Hardcoded
    private val bySymbol = cases.associateBy { it.symbol.uppercase() }

    override fun load(symbol: String): QuantBundle {
        var match = bySymbol[symbol.uppercase()]
            ?: throw IllegalArgumentException("hardcoded case missing for $symbol")
        return QuantBundle(
            symbol = match.symbol,
            fundamentals = match.fundamentals,
            timeseries = match.timeseries,
            marketParams = match.marketParams,
            mode = QuantDataMode.Hardcoded,
            asOfEpochMillis = 0L,
            cacheHit = false,
        )
    }
}

private class CachedSource(
    private val client: QuantLiveClient,
    private val cacheDir: Path,
    private val ttl: Duration,
    private val clock: () -> Long,
    private val rates: QuantRatesSource?,
) : QuantDataSource {
    override val mode: QuantDataMode = QuantDataMode.Cached

    override fun load(symbol: String): QuantBundle {
        Files.createDirectories(cacheDir)
        var packPath = cacheDir.resolve("${symbol.uppercase()}.json")
        var now = clock()
        if (Files.exists(packPath)) {
            var pack = JSON.decodeFromString<QuantCachePack>(Files.readString(packPath))
            var age = now - pack.writtenAtEpochMillis
            if (age < ttl.toMillis()) {
                return pack.toBundle(cacheHit = true)
            }
        }
        var fetched = client.fetch(symbol)
        var withRates = rates?.let { fetched.copy(marketParams = it.load().marketParams) } ?: fetched
        var pack = QuantCachePack.from(withRates, now)
        Files.writeString(packPath, JSON.encodeToString(pack))
        return pack.toBundle(cacheHit = false)
    }
}

@Serializable
private data class QuantCachePack(
    val symbol: String,
    val writtenAtEpochMillis: Long,
    val fundamentals: FundamentalSnapshot,
    val timeseries: FundamentalTimeseries,
    val rfBps: Int? = null,
    val erpBps: Int? = null,
    val provisional: Boolean? = null,
    val erpSchool: String? = null,
    val rfSource: String? = null,
    val macroStableGrowthBps: Int? = null,
    val asOfEpochMillis: Long? = null,
) {
    fun toBundle(cacheHit: Boolean): QuantBundle {
        var params = if (rfBps != null && erpBps != null && provisional != null) {
            MarketParams(
                rfBps = rfBps,
                erpBps = erpBps,
                provisional = provisional,
                asOfEpochMillis = asOfEpochMillis,
                erpSchool = erpSchool?.let { wire ->
                    ErpSchool.entries.firstOrNull { it.wireName == wire }
                } ?: ErpSchool.Bootstrap,
                rfSource = rfSource ?: "bootstrap",
                macroStableGrowthBps = macroStableGrowthBps ?: 300,
            )
        } else {
            null
        }
        return QuantBundle(
            symbol = symbol,
            fundamentals = fundamentals,
            timeseries = timeseries,
            marketParams = params,
            mode = QuantDataMode.Cached,
            asOfEpochMillis = writtenAtEpochMillis,
            cacheHit = cacheHit,
        )
    }

    companion object {
        fun from(bundle: QuantBundle, writtenAtEpochMillis: Long): QuantCachePack =
            QuantCachePack(
                symbol = bundle.symbol,
                writtenAtEpochMillis = writtenAtEpochMillis,
                fundamentals = bundle.fundamentals,
                timeseries = bundle.timeseries,
                rfBps = bundle.marketParams?.rfBps,
                erpBps = bundle.marketParams?.erpBps,
                provisional = bundle.marketParams?.provisional,
                erpSchool = bundle.marketParams?.erpSchool?.wireName,
                rfSource = bundle.marketParams?.rfSource,
                macroStableGrowthBps = bundle.marketParams?.macroStableGrowthBps,
                asOfEpochMillis = bundle.marketParams?.asOfEpochMillis,
            )
    }
}

enum class QuantRatesMode {
    Live,
    Cached,
    Hardcoded,
}

data class QuantRatesBundle(
    val marketParams: MarketParams,
    val mode: QuantRatesMode,
    val asOfEpochMillis: Long,
    val cacheHit: Boolean = false,
)

fun interface QuantRatesLiveClient {
    fun fetch(): QuantRatesBundle
}

interface QuantRatesSource {
    val mode: QuantRatesMode
    fun load(): QuantRatesBundle
}

private class LiveRatesSource(
    private val client: QuantRatesLiveClient,
) : QuantRatesSource {
    override val mode: QuantRatesMode = QuantRatesMode.Live

    override fun load(): QuantRatesBundle = client.fetch().copy(mode = QuantRatesMode.Live, cacheHit = false)
}

private class HardcodedRatesSource(
    private val params: MarketParams,
) : QuantRatesSource {
    override val mode: QuantRatesMode = QuantRatesMode.Hardcoded

    override fun load(): QuantRatesBundle = QuantRatesBundle(
        marketParams = params,
        mode = QuantRatesMode.Hardcoded,
        asOfEpochMillis = 0L,
        cacheHit = false,
    )
}

private class CachedRatesSource(
    private val client: QuantRatesLiveClient,
    private val cacheDir: Path,
    private val ttl: Duration,
    private val clock: () -> Long,
) : QuantRatesSource {
    override val mode: QuantRatesMode = QuantRatesMode.Cached

    override fun load(): QuantRatesBundle {
        Files.createDirectories(cacheDir)
        var packPath = cacheDir.resolve("rates.json")
        var now = clock()
        if (Files.exists(packPath)) {
            var pack = JSON.decodeFromString<RatesCachePack>(Files.readString(packPath))
            var age = now - pack.writtenAtEpochMillis
            if (age < ttl.toMillis()) {
                return pack.toBundle(cacheHit = true)
            }
        }
        var fetched = client.fetch()
        var pack = RatesCachePack.from(fetched, now)
        Files.writeString(packPath, JSON.encodeToString(pack))
        return pack.toBundle(cacheHit = false)
    }
}

@Serializable
private data class RatesCachePack(
    val writtenAtEpochMillis: Long,
    val rfBps: Int,
    val erpBps: Int,
    val provisional: Boolean,
    val erpSchool: String,
    val rfSource: String,
    val macroStableGrowthBps: Int,
    val asOfEpochMillis: Long?,
) {
    fun toBundle(cacheHit: Boolean): QuantRatesBundle = QuantRatesBundle(
        marketParams = MarketParams(
            rfBps = rfBps,
            erpBps = erpBps,
            provisional = provisional,
            asOfEpochMillis = asOfEpochMillis,
            erpSchool = ErpSchool.entries.firstOrNull { it.wireName == erpSchool } ?: ErpSchool.Bootstrap,
            rfSource = rfSource,
            macroStableGrowthBps = macroStableGrowthBps,
        ),
        mode = QuantRatesMode.Cached,
        asOfEpochMillis = writtenAtEpochMillis,
        cacheHit = cacheHit,
    )

    companion object {
        fun from(bundle: QuantRatesBundle, writtenAtEpochMillis: Long): RatesCachePack =
            RatesCachePack(
                writtenAtEpochMillis = writtenAtEpochMillis,
                rfBps = bundle.marketParams.rfBps,
                erpBps = bundle.marketParams.erpBps,
                provisional = bundle.marketParams.provisional,
                erpSchool = bundle.marketParams.erpSchool.wireName,
                rfSource = bundle.marketParams.rfSource,
                macroStableGrowthBps = bundle.marketParams.macroStableGrowthBps,
                asOfEpochMillis = bundle.marketParams.asOfEpochMillis,
            )
    }
}

private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
