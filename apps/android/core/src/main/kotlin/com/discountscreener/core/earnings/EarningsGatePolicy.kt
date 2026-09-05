package com.discountscreener.core.earnings

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

const val EARNINGS_GATE_POLICY_VERSION = "earnings-gate-policy/1"
const val EARNINGS_GATE_POLICY_RESOURCE = "/earnings-gate-policy.yaml"

object EarningsGatePolicy {
    const val VERSION = EARNINGS_GATE_POLICY_VERSION

    @Volatile
    var current: EarningsGatePolicyBook = EarningsGatePolicyBook.loadDefault()
        private set

    fun <T> use(book: EarningsGatePolicyBook, block: () -> T): T {
        var previous = current
        current = book
        try {
            return block()
        } finally {
            current = previous
        }
    }
}

data class EarningsGatePolicyBook(
    val version: String,
    val highRiskRatioBps: Int,
    val lowRiskRatioBps: Int,
    val cheapPriceToFairBps: Int,
    val hedgeCostCapBps: Int,
    val protectivePutCostCapBps: Int,
    val maxQuoteSpreadBps: Int,
    val minSueQuarters: Int,
    val avDailyLimit: Int,
    val avPerMinute: Int,
    val avCacheFreshDays: Int,
    val sueMatchDays: Int,
    val minRevenueTrailQuarters: Int,
    val revenueOverrideZBps: Int,
) {
    companion object {
        fun loadDefault(): EarningsGatePolicyBook {
            var stream = EarningsGatePolicyBook::class.java.getResourceAsStream(EARNINGS_GATE_POLICY_RESOURCE)
                ?: error("earnings-gate-policy.yaml is missing from the classpath")
            return stream.bufferedReader().use { parse(it.readText()) }
        }

        fun parse(text: String): EarningsGatePolicyBook {
            var yaml = Yaml(SafeConstructor(LoaderOptions()))
            @Suppress("UNCHECKED_CAST")
            var raw = yaml.load<Map<String, Any>>(text)
                ?: error("earnings-gate-policy.yaml is empty")
            var version = raw["version"] as? String ?: error("missing key: version")
            if (version != EARNINGS_GATE_POLICY_VERSION) {
                error("earnings-gate-policy version $version is not $EARNINGS_GATE_POLICY_VERSION")
            }
            return EarningsGatePolicyBook(
                version = version,
                highRiskRatioBps = int(raw, "high_risk_ratio_bps"),
                lowRiskRatioBps = int(raw, "low_risk_ratio_bps"),
                cheapPriceToFairBps = int(raw, "cheap_price_to_fair_bps"),
                hedgeCostCapBps = int(raw, "hedge_cost_cap_bps"),
                protectivePutCostCapBps = int(raw, "protective_put_cost_cap_bps"),
                maxQuoteSpreadBps = int(raw, "max_quote_spread_bps"),
                minSueQuarters = int(raw, "min_sue_quarters"),
                avDailyLimit = int(raw, "av_daily_limit"),
                avPerMinute = int(raw, "av_per_minute"),
                avCacheFreshDays = int(raw, "av_cache_fresh_days"),
                sueMatchDays = int(raw, "sue_match_days"),
                minRevenueTrailQuarters = int(raw, "min_revenue_trail_quarters"),
                revenueOverrideZBps = int(raw, "revenue_override_z_bps"),
            )
        }

        private fun int(raw: Map<String, Any>, key: String): Int {
            var value = raw[key] ?: error("missing key: $key")
            return when (value) {
                is Int -> value
                is Long -> value.toInt()
                is Double -> {
                    if (value % 1.0 != 0.0) error("$key is not a whole number: $value")
                    value.toInt()
                }
                else -> error("$key expected int, got ${value::class.simpleName}")
            }
        }
    }
}
