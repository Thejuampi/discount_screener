package com.discountscreener.core.baratas

import com.discountscreener.core.puml.PumlDocument
import com.discountscreener.core.puml.StandardPumlHost
import com.discountscreener.core.runtime.ModelValue

/**
 * Extra primitives for EarningsCheapness. Structure and coefficients live in the PUML.
 */
object BaratasPumlHost : StandardPumlHost() {
    override fun onBareCall(phrase: String, env: MutableMap<String, ModelValue>) {
        if (phrase == "FinancialClassPolicy.classify" || phrase == "classify") {
            env["class"] = env["class"] ?: ModelValue.Missing
            return
        }
        super.onBareCall(phrase, env)
    }

    override fun extraCall(
        name: String,
        args: List<ModelValue>,
        env: MutableMap<String, ModelValue>,
        document: PumlDocument,
    ): ModelValue = when (name) {
        "classify" -> env["class"] ?: ModelValue.Missing
        "cheapness" -> cheapness(args)
        else -> ModelValue.Missing
    }

    private fun cheapness(args: List<ModelValue>): ModelValue {
        var pe = args.getOrNull(0)?.asNum() ?: return ModelValue.Missing
        var centre = args.getOrNull(1)?.asNum() ?: return ModelValue.Missing
        var cheap = args.getOrNull(2)?.asNum() ?: return ModelValue.Missing
        var rich = args.getOrNull(3)?.asNum() ?: return ModelValue.Missing
        var lo = cheap * centre
        var hi = rich * centre
        if (hi <= lo) return ModelValue.Missing
        var ramp = 2.0 * (pe - lo) / (hi - lo) - 1.0
        ramp = ramp.coerceIn(-1.0, 1.0)
        return ModelValue.Num(-ramp)
    }
}
