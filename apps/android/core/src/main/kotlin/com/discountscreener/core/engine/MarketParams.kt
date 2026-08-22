package com.discountscreener.core.engine

import java.nio.file.Path
import java.time.Duration

val DEFAULT_RF_BPS: Int
    get() = ValuationPolicy.current.market.defaultRfBps
val DEFAULT_ERP_BPS: Int
    get() = ValuationPolicy.current.market.defaultErpBps
val STABLE_GROWTH_RF_BUFFER_BPS: Int
    get() = ValuationPolicy.current.market.stableGrowthRfBufferBps
val BOOTSTRAP_MACRO_STABLE_GROWTH_BPS: Int
    get() = ValuationPolicy.current.market.bootstrapMacroStableGrowthBps
val MIN_STABLE_GROWTH_BPS: Int
    get() = ValuationPolicy.current.market.minStableGrowthBps
const val RF_SOURCE_BOOTSTRAP = "bootstrap"
const val RF_SOURCE_FRED_DGS10 = "fred_dgs10"
const val RF_SOURCE_YAHOO_TNX = "yahoo_tnx"

fun interface MarketParamsSource {
    fun current(): MarketParams
}

object BootstrapMarketParamsSource : MarketParamsSource {
    override fun current(): MarketParams = MarketParams()
}

data class MarketParams(
    val rfBps: Int = DEFAULT_RF_BPS,
    val erpBps: Int = DEFAULT_ERP_BPS,
    val provisional: Boolean = true,
    val asOfEpochMillis: Long? = null,
    val erpSchool: ErpSchool = ErpSchool.Bootstrap,
    val erpPolicyVersion: String = ErpPolicy.VERSION,
    val rfSource: String = RF_SOURCE_BOOTSTRAP,
    val macroStableGrowthBps: Int = BOOTSTRAP_MACRO_STABLE_GROWTH_BPS,
    val macroPolicyVersion: String = MacroPolicy.VERSION,
) {
    fun stableGrowthBps(): Int =
        minOf(macroStableGrowthBps, rfBps - STABLE_GROWTH_RF_BUFFER_BPS)
            .coerceAtLeast(MIN_STABLE_GROWTH_BPS)

    fun fingerprint(): String =
        "market_params=rf:$rfBps,erp:$erpBps,school:${erpSchool.wireName},src:$rfSource,prov:$provisional"

    fun displayLabel(): String =
        "rf ${rfBps}bps · ERP ${erpBps}bps ${erpSchool.wireName} · $rfSource"

    companion object {
        const val FINGERPRINT_PREFIX = "market_params="

        fun displayLabelFromReasonCodes(reasonCodes: List<String>): String? {
            var raw = reasonCodes.firstOrNull { it.startsWith(FINGERPRINT_PREFIX) } ?: return null
            var body = raw.removePrefix(FINGERPRINT_PREFIX)
            var parts = body.split(',').associate { token ->
                var idx = token.indexOf(':')
                if (idx <= 0) "" to token else token.substring(0, idx) to token.substring(idx + 1)
            }
            var rf = parts["rf"] ?: return null
            var erp = parts["erp"] ?: return null
            var school = parts["school"] ?: "bootstrap"
            var src = parts["src"] ?: RF_SOURCE_BOOTSTRAP
            return "rf ${rf}bps · ERP ${erp}bps $school · $src"
        }

        fun observed(
            rfBps: Int,
            asOfEpochMillis: Long,
            rfSource: String = RF_SOURCE_FRED_DGS10,
            school: ErpSchool = ErpPolicy.DEFAULT_SCHOOL,
        ): MarketParams {
            if (rfBps < FredDgs10Parser.MIN_YIELD_BPS || rfBps > FredDgs10Parser.MAX_YIELD_BPS) {
                throw IllegalArgumentException("observed rf out of range: $rfBps")
            }
            var erp = ErpPolicy.resolve(school, asOfEpochMillis)
            var macro = MacroPolicy.resolve(asOfEpochMillis)
            return MarketParams(
                rfBps = rfBps,
                erpBps = erp.erpBps,
                provisional = erp.stale,
                asOfEpochMillis = asOfEpochMillis,
                erpSchool = school,
                erpPolicyVersion = erp.policyVersion,
                rfSource = rfSource,
                macroStableGrowthBps = macro.nominalGrowthCeilingBps,
                macroPolicyVersion = macro.policyVersion,
            )
        }
    }
}

/**
 * Production source: live FRED csv when it parses, else bootstrap (provisional).
 * The experiment harness must not use this fail-open path.
 */
class CachedObservedMarketParamsSource(
    private val fetchCsv: () -> String,
    private val cacheFile: Path,
    private val ttl: Duration = Duration.ofDays(1),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val school: ErpSchool = ErpPolicy.DEFAULT_SCHOOL,
) : MarketParamsSource {
    override fun current(): MarketParams = runCatching { loadObserved() }.getOrElse { MarketParams() }

    private fun loadObserved(): MarketParams {
        var file = cacheFile.toFile()
        file.parentFile?.mkdirs()
        var now = clock()
        if (file.isFile) {
            var age = now - file.lastModified()
            if (age < ttl.toMillis()) {
                return parseToParams(file.readText(), now)
            }
        }
        var csv = fetchCsv()
        var params = parseToParams(csv, now)
        file.writeText(csv)
        return params
    }

    private fun parseToParams(csv: String, now: Long): MarketParams {
        var obs = FredDgs10Parser.latest(csv)
        return MarketParams.observed(
            rfBps = obs.yieldBps,
            asOfEpochMillis = now,
            rfSource = RF_SOURCE_FRED_DGS10,
            school = school,
        )
    }
}

class CachedYahooTnxMarketParamsSource(
    private val fetchJson: () -> String,
    private val cacheFile: Path,
    private val ttl: Duration = Duration.ofDays(1),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val school: ErpSchool = ErpPolicy.DEFAULT_SCHOOL,
) : MarketParamsSource {
    override fun current(): MarketParams = runCatching { loadObserved() }.getOrElse { MarketParams() }

    private fun loadObserved(): MarketParams {
        var file = cacheFile.toFile()
        file.parentFile?.mkdirs()
        var now = clock()
        if (file.isFile) {
            var age = now - file.lastModified()
            if (age < ttl.toMillis()) {
                return parseToParams(file.readText(), now)
            }
        }
        var json = fetchJson()
        var params = parseToParams(json, now)
        file.writeText(json)
        return params
    }

    private fun parseToParams(json: String, now: Long): MarketParams {
        var obs = YahooTnxParser.parse(json)
        var asOf = obs.asOfEpochSeconds?.let { it * 1_000L } ?: now
        return MarketParams.observed(
            rfBps = obs.yieldBps,
            asOfEpochMillis = asOf,
            rfSource = RF_SOURCE_YAHOO_TNX,
            school = school,
        )
    }
}

class FredThenTnxMarketParamsSource(
    private val fred: MarketParamsSource,
    private val tnx: MarketParamsSource,
) : MarketParamsSource {
    override fun current(): MarketParams {
        var fromFred = fred.current()
        if (fromFred.rfSource == RF_SOURCE_FRED_DGS10) return fromFred
        return tnx.current()
    }
}
