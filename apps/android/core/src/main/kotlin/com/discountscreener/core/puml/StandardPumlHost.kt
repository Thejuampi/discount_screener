package com.discountscreener.core.puml

import com.discountscreener.core.math.medianOf
import com.discountscreener.core.math.robustCentre
import com.discountscreener.core.runtime.ModelValue
import kotlin.math.sign

/**
 * Primitive catalog. The diagram names the call and supplies the arguments.
 *
 * A new hunt that only needs these names does not write a new engine.
 */
open class StandardPumlHost : PumlHost {
    override fun evaluate(
        phrase: String,
        env: MutableMap<String, ModelValue>,
    ): ModelValue = ModelValue.Missing

    override fun call(
        name: String,
        args: List<ModelValue>,
        env: MutableMap<String, ModelValue>,
        document: PumlDocument,
    ): ModelValue {
        var key = name.substringAfterLast('.').lowercase()
        return when (key) {
            "count" -> firstSeries(args)?.let { ModelValue.Num(it.size.toDouble()) } ?: ModelValue.Missing
            "robust_mean" -> {
                var series = firstSeries(args) ?: return ModelValue.Missing
                robustCentre(series)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            "ols" -> {
                var series = firstSeries(args) ?: return ModelValue.Missing
                ols(series)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            "median", "centre", "center" -> {
                var series = firstSeries(args) ?: return ModelValue.Missing
                medianOf(series)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            "sign" -> {
                var n = args.firstOrNull()?.asNum() ?: return ModelValue.Missing
                ModelValue.Num(sign(n))
            }
            else -> extraCall(key, args, env, document)
        }
    }

    protected open fun extraCall(
        name: String,
        args: List<ModelValue>,
        env: MutableMap<String, ModelValue>,
        document: PumlDocument,
    ): ModelValue = ModelValue.Missing

    private fun firstSeries(args: List<ModelValue>): List<Double>? {
        args.forEach { arg ->
            var series = arg as? ModelValue.Series
            if (series != null) return series.values
        }
        return null
    }

    protected fun ols(values: List<Double>): Double? {
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
