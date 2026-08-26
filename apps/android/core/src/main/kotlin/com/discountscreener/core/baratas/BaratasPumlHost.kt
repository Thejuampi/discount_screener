package com.discountscreener.core.baratas

import com.discountscreener.core.math.medianOf
import com.discountscreener.core.math.robustCentre
import com.discountscreener.core.puml.PumlDocument
import com.discountscreener.core.puml.PumlHost
import com.discountscreener.core.runtime.ModelValue
import kotlin.math.sign

/**
 * Host for the EarningsCheapness document.
 *
 * Phrases come from the PUML. A new document needs a new host, not a new engine.
 */
object BaratasPumlHost : PumlHost {
    override fun onBareCall(phrase: String, env: MutableMap<String, ModelValue>) {
        if (phrase == "FinancialClassPolicy.classify") {
            env["class"] = env["class"] ?: evaluate(phrase, env)
            return
        }
        super.onBareCall(phrase, env)
    }

    override fun decorateEmit(
        name: String,
        fields: Map<String, String>,
        env: Map<String, ModelValue>,
        flags: Set<String>,
        document: PumlDocument,
    ): Map<String, String> {
        var out = fields
        if (name == "Unavailable" && "reason" !in out) {
            var arg = out["arg"]
            if (arg != null) out = out + ("reason" to arg)
        }
        if (name == "Watch" && "reason" !in out) {
            var reason = firstWatchReason(document, env, flags)
            if (reason != null) out = out + ("reason" to reason)
        }
        return out
    }

    override fun evaluate(phrase: String, env: MutableMap<String, ModelValue>): ModelValue {
        var p = phrase.trim()
        when {
            p == "FinancialClassPolicy.classify" -> return env["class"] ?: ModelValue.Missing
            p == "count of annual EPS years" -> {
                var series = env["annual_eps"] as? ModelValue.Series ?: return ModelValue.Missing
                return ModelValue.Num(series.values.size.toDouble())
            }
            p.startsWith("robust_mean") -> {
                var series = env["annual_eps"] as? ModelValue.Series ?: return ModelValue.Missing
                var centre = robustCentre(series.values) ?: return ModelValue.Missing
                return ModelValue.Num(centre)
            }
            p == "OLS slope of EPS_t vs year t" -> {
                var series = env["annual_eps"] as? ModelValue.Series ?: return ModelValue.Missing
                return ols(series.values)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            p == "OLS revenue slope" -> {
                var series = env["annual_revenue"] as? ModelValue.Series ?: return ModelValue.Missing
                return ols(series.values)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            p == "through-cycle industry" -> return env["through_cycle"] ?: ModelValue.Flag(false)
            p == "hunt on" -> return env["hunt_on"] ?: ModelValue.Flag(true)
            p.startsWith("cycle_universe") -> return env["through_cycle"] ?: ModelValue.Flag(false)
            p.startsWith("centre of members' pe_now") -> return centre(env["peer_pe_now"])
            p.startsWith("centre of members' pe_next") -> return centre(env["peer_pe_next"])
            p.startsWith("cheapness of") -> return cheapness(p, env)
            p == "own-history percentile" -> return env["r_own"] ?: ModelValue.Missing
            p.startsWith("sign(") -> {
                var name = p.removePrefix("sign(").removeSuffix(")").trim()
                var value = env[name]?.asNum() ?: return ModelValue.Missing
                return ModelValue.Num(sign(value))
            }
            p.startsWith("ZONE(") -> {
                var pe = env["pe_now"]?.asNum() ?: return ModelValue.Missing
                return ModelValue.Text(zone(pe))
            }
            p.startsWith("V5 F minus Mult") || p.startsWith("Σ") -> {
                return env["q"] ?: ModelValue.Missing
            }
            p == "n_members" -> {
                var series = env["peer_pe_next"] as? ModelValue.Series
                    ?: env["peer_pe_now"] as? ModelValue.Series
                    ?: return ModelValue.Missing
                return ModelValue.Num(series.values.size.toDouble())
            }
            p == "true" -> return ModelValue.Flag(true)
            else -> return ModelValue.Missing
        }
    }

    private fun firstWatchReason(
        document: PumlDocument,
        env: Map<String, ModelValue>,
        flags: Set<String>,
    ): String? {
        var order = document.tables["reason"].orEmpty()
        return order.firstOrNull { token -> watchTokenHits(token, env, flags) }
    }

    private fun watchTokenHits(
        token: String,
        env: Map<String, ModelValue>,
        flags: Set<String>,
    ): Boolean = when (token) {
        "quality" -> env["q"]?.asNum()?.let { it < 0.0 } == true
        "eps_window_short" -> token in flags || env[token]?.asFlag() == true
        else -> env[token]?.asFlag() == true
    }

    private fun centre(value: ModelValue?): ModelValue {
        var series = value as? ModelValue.Series ?: return ModelValue.Missing
        var centre = medianOf(series.values) ?: return ModelValue.Missing
        return ModelValue.Num(centre)
    }

    private fun cheapness(phrase: String, env: MutableMap<String, ModelValue>): ModelValue {
        var bands = Regex("""([0-9.]+)×–([0-9.]+)×""").find(phrase) ?: return ModelValue.Missing
        var cheap = bands.groupValues[1].toDouble()
        var rich = bands.groupValues[2].toDouble()
        var peName = if ("pe_next" in phrase) "pe_next" else "pe_now"
        var centreName = if ("sector_next" in phrase) "sector_next" else "sector_now"
        var pe = env[peName]?.asNum() ?: return ModelValue.Missing
        var centre = env[centreName]?.asNum() ?: return ModelValue.Missing
        var lo = cheap * centre
        var hi = rich * centre
        if (hi <= lo) return ModelValue.Missing
        var ramp = 2.0 * (pe - lo) / (hi - lo) - 1.0
        ramp = ramp.coerceIn(-1.0, 1.0)
        return ModelValue.Num(-ramp)
    }

    private fun zone(pe: Double): String = when {
        pe < 10.0 -> "HistoricalBuy"
        pe <= 20.0 -> "Reasonable"
        pe <= 30.0 -> "Attention"
        pe <= 50.0 -> "Euphoria"
        else -> "Alert"
    }

    private fun ols(values: List<Double>): Double? {
        if (values.size < 2) return null
        var n = values.size.toDouble()
        var sumT = 0.0
        var sumY = 0.0
        var sumTy = 0.0
        var sumTt = 0.0
        values.forEachIndexed { i, y ->
            if (!y.isFinite()) return null
            var t = i.toDouble()
            sumT += t
            sumY += y
            sumTy += t * y
            sumTt += t * t
        }
        var denom = n * sumTt - sumT * sumT
        if (denom == 0.0) return 0.0
        return (n * sumTy - sumT * sumY) / denom
    }
}
