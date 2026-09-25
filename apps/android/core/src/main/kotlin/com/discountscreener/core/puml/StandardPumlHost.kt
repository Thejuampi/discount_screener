package com.discountscreener.core.puml

import com.discountscreener.core.engine.DcfAnalysisEngine
import com.discountscreener.core.math.clamp
import com.discountscreener.core.math.isForeignTo
import com.discountscreener.core.math.maximum
import com.discountscreener.core.math.medianOf
import com.discountscreener.core.math.minimum
import com.discountscreener.core.math.ols
import com.discountscreener.core.math.percentile
import com.discountscreener.core.math.ramp
import com.discountscreener.core.math.robustCentre
import com.discountscreener.core.runtime.ModelValue
import kotlin.math.sign

/**
 * Kotlin lib the PUML catalog includes at load.
 *
 * Names: count, robust_mean, median, ols, ramp, clamp, min, max, sign,
 * percentile, foreign, classify.
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
            "median" -> {
                var series = firstSeries(args) ?: return ModelValue.Missing
                medianOf(series)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            "ramp" -> {
                var observed = args.getOrNull(0)?.asNum() ?: return ModelValue.Missing
                var lower = args.getOrNull(1)?.asNum() ?: return ModelValue.Missing
                var upper = args.getOrNull(2)?.asNum() ?: return ModelValue.Missing
                ramp(observed, lower, upper)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            "clamp" -> {
                var observed = args.getOrNull(0)?.asNum() ?: return ModelValue.Missing
                var lower = args.getOrNull(1)?.asNum() ?: return ModelValue.Missing
                var upper = args.getOrNull(2)?.asNum() ?: return ModelValue.Missing
                clamp(observed, lower, upper)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            "min" -> {
                var nums = numericArgs(args)
                if (nums.isEmpty()) ModelValue.Missing else minimum(nums)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            "max" -> {
                var nums = numericArgs(args)
                if (nums.isEmpty()) ModelValue.Missing else maximum(nums)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            "sign" -> {
                var n = args.firstOrNull()?.asNum() ?: return ModelValue.Missing
                ModelValue.Num(sign(n))
            }
            "percentile" -> {
                var series = firstSeries(args) ?: return ModelValue.Missing
                var value = args.firstNotNullOfOrNull { it.asNum() } ?: return ModelValue.Missing
                percentile(series, value)?.let { ModelValue.Num(it) } ?: ModelValue.Missing
            }
            "foreign" -> {
                var series = firstSeries(args) ?: return ModelValue.Missing
                var value = args.firstNotNullOfOrNull { it.asNum() } ?: return ModelValue.Missing
                ModelValue.Flag(isForeignTo(value, series))
            }
            "classify" -> {
                var sector = args.getOrNull(0)?.asText()
                var industry = args.getOrNull(1)?.asText()
                if (sector == null && industry == null) return ModelValue.Missing
                var klass = DcfAnalysisEngine.classifyBusiness(sector, industry)
                ModelValue.Text(klass.name)
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

    private fun numericArgs(args: List<ModelValue>): List<Double> {
        var out = ArrayList<Double>()
        args.forEach { arg ->
            var n = arg.asNum()
            if (n != null) out.add(n)
        }
        return out
    }
}
