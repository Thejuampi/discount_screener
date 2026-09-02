package com.discountscreener.core.puml

import com.discountscreener.core.runtime.ModelEmission
import com.discountscreener.core.runtime.ModelInput
import com.discountscreener.core.runtime.ModelOutput
import com.discountscreener.core.runtime.ModelValue

/**
 * Walks an activity document. A call hits document functions first, then the Kotlin host lib.
 *
 * Hunt labels do not live here. An emit is a named box. Stop returns the last emit.
 */
object InterpretingPumlEngine : PumlEngine {
    override fun run(
        document: PumlDocument,
        input: ModelInput,
        host: PumlHost,
    ): ModelOutput {
        var env = input.toMutableEnv()
        var flags = LinkedHashSet<String>()
        var emission: ModelEmission? = null
        document.partitions.forEach { partition ->
            var walk = runSteps(partition.steps, env, flags, host, document, emission)
            emission = walk.emission
            if (walk.output != null) return walk.output
        }
        return ModelOutput(bindings = env.toMap(), flags = flags, emission = emission)
    }

    private data class Walk(
        val output: ModelOutput?,
        val emission: ModelEmission?,
        val lastValue: ModelValue? = null,
    )

    private fun runSteps(
        steps: List<PumlStep>,
        env: MutableMap<String, ModelValue>,
        flags: MutableSet<String>,
        host: PumlHost,
        document: PumlDocument,
        emissionIn: ModelEmission?,
        asFunction: Boolean = false,
    ): Walk {
        var emission = emissionIn
        var lastValue: ModelValue? = null
        steps.forEach { step ->
            when (step) {
                is PumlStep.Assign -> {
                    var value = assignValue(step.expression, env, flags, host, document)
                    env[step.name] = value
                    lastValue = value
                }
                is PumlStep.Clear -> {
                    env[step.name] = ModelValue.Empty
                    lastValue = ModelValue.Empty
                }
                is PumlStep.BareCall -> host.onBareCall(step.phrase, env)
                is PumlStep.Flag -> {
                    flags.add(step.name)
                    env[step.name] = ModelValue.Flag(true)
                }
                is PumlStep.Emit -> {
                    emission = applyEmit(step, env, flags, host, document)
                }
                is PumlStep.Stop -> {
                    if (asFunction) return Walk(output = null, emission = emission, lastValue = lastValue)
                    return Walk(
                        ModelOutput(bindings = env.toMap(), flags = flags.toSet(), emission = emission),
                        emission,
                        lastValue,
                    )
                }
                is PumlStep.Branch -> {
                    var yes = truthy(eval(step.condition, env, flags, host, document))
                    var branch = if (yes) step.yes else step.no
                    var nested = runSteps(branch, env, flags, host, document, emission, asFunction)
                    if (nested.output != null) return nested
                    emission = nested.emission
                    if (nested.lastValue != null) lastValue = nested.lastValue
                }
                is PumlStep.Split -> {
                    step.arms.forEach { arm ->
                        var nested = runSteps(arm, env, flags, host, document, emission, asFunction)
                        if (nested.output != null) return nested
                        emission = nested.emission
                        if (nested.lastValue != null) lastValue = nested.lastValue
                    }
                }
            }
        }
        return Walk(output = null, emission = emission, lastValue = lastValue)
    }

    private fun invokeFunction(
        fn: PumlFunction,
        args: List<ModelValue>,
        env: Map<String, ModelValue>,
        flags: MutableSet<String>,
        host: PumlHost,
        document: PumlDocument,
    ): ModelValue {
        if (args.size != fn.params.size) return ModelValue.Missing
        var local = LinkedHashMap(env)
        fn.params.forEachIndexed { i, name -> local[name] = args[i] }
        var walk = runSteps(fn.steps, local, flags, host, document, emissionIn = null, asFunction = true)
        return walk.lastValue ?: ModelValue.Missing
    }

    private fun applyEmit(
        step: PumlStep.Emit,
        env: MutableMap<String, ModelValue>,
        flags: Set<String>,
        host: PumlHost,
        document: PumlDocument,
    ): ModelEmission {
        var fields = host.decorateEmit(step.label, step.fields, env, flags, document)
        env[step.label] = ModelValue.Flag(true)
        var arg = fields["arg"]
        if (arg != null) {
            env["${step.label}($arg)"] = ModelValue.Flag(true)
        }
        fields.forEach { (key, value) ->
            env[key] = ModelValue.Text(value)
        }
        return ModelEmission(name = step.label, fields = fields)
    }

    private fun assignValue(
        expr: PumlExpr,
        env: MutableMap<String, ModelValue>,
        flags: MutableSet<String>,
        host: PumlHost,
        document: PumlDocument,
    ): ModelValue {
        var value = eval(expr, env, flags, host, document)
        if (value is ModelValue.Missing) {
            var label = when (expr) {
                is PumlExpr.Ident -> expr.name
                is PumlExpr.Phrase -> if (' ' !in expr.text) expr.text else null
                else -> null
            }
            if (label != null && label.first().isUpperCase()) return ModelValue.Text(label)
        }
        return value
    }

    private fun eval(
        expr: PumlExpr,
        env: MutableMap<String, ModelValue>,
        flags: MutableSet<String>,
        host: PumlHost,
        document: PumlDocument,
    ): ModelValue {
        return when (expr) {
            is PumlExpr.Number -> ModelValue.Num(expr.value)
            is PumlExpr.Bool -> ModelValue.Flag(expr.value)
            is PumlExpr.Ident -> resolveIdent(expr.name, env, host, document)
            is PumlExpr.Phrase -> {
                env[expr.text]?.let { return it }
                host.evaluate(expr.text, env)
            }
            is PumlExpr.Call -> {
                var args = expr.args.map { eval(it, env, flags, host, document) }
                var fn = document.functions[expr.name]
                if (fn != null) invokeFunction(fn, args, env, flags, host, document)
                else host.call(expr.name, args, env, document)
            }
            is PumlExpr.Unary -> evalUnary(expr.op, eval(expr.inner, env, flags, host, document))
            is PumlExpr.Binary -> evalBinary(expr.op, expr.left, expr.right, env, flags, host, document)
        }
    }

    private fun resolveIdent(
        name: String,
        env: MutableMap<String, ModelValue>,
        host: PumlHost,
        document: PumlDocument,
    ): ModelValue {
        env[name]?.let { return it }
        env.values.forEach { value ->
            if (value is ModelValue.Text && value.value == name) return ModelValue.Flag(true)
        }
        return host.evaluate(name, env)
    }

    private fun evalUnary(op: String, value: ModelValue): ModelValue = when (op) {
        "not" -> ModelValue.Flag(!truthy(value))
        "missing" -> ModelValue.Flag(value is ModelValue.Missing)
        "empty" -> ModelValue.Flag(value is ModelValue.Empty || value is ModelValue.Missing)
        else -> value
    }

    private fun evalBinary(
        op: String,
        leftExpr: PumlExpr,
        rightExpr: PumlExpr,
        env: MutableMap<String, ModelValue>,
        flags: MutableSet<String>,
        host: PumlHost,
        document: PumlDocument,
    ): ModelValue {
        if (op == "or") {
            var left = eval(leftExpr, env, flags, host, document)
            if (truthy(left)) return ModelValue.Flag(true)
            return ModelValue.Flag(truthy(eval(rightExpr, env, flags, host, document)))
        }
        if (op == "and") {
            var left = eval(leftExpr, env, flags, host, document)
            if (!truthy(left)) return ModelValue.Flag(false)
            return ModelValue.Flag(truthy(eval(rightExpr, env, flags, host, document)))
        }
        var left = eval(leftExpr, env, flags, host, document)
        var right = eval(rightExpr, env, flags, host, document)
        var ln = left.asNum()
        var rn = right.asNum()
        return when (op) {
            "+" -> nums(ln, rn) { a, b -> a + b }
            "-" -> nums(ln, rn) { a, b -> a - b }
            "×", "*" -> nums(ln, rn) { a, b -> a * b }
            "/" -> {
                if (ln == null || rn == null || rn == 0.0) ModelValue.Missing
                else ModelValue.Num(ln / rn)
            }
            "<" -> cmp(ln, rn) { a, b -> a < b }
            ">" -> cmp(ln, rn) { a, b -> a > b }
            "≤", "<=" -> cmp(ln, rn) { a, b -> a <= b }
            "≥", ">=" -> cmp(ln, rn) { a, b -> a >= b }
            "≠" -> {
                if (!left.isPresent() || !right.isPresent()) ModelValue.Missing
                else ModelValue.Flag(!same(left, right))
            }
            "==" -> {
                if (!left.isPresent() || !right.isPresent()) ModelValue.Missing
                else ModelValue.Flag(same(left, right))
            }
            else -> host.evaluate(op, env)
        }
    }

    private fun nums(a: Double?, b: Double?, op: (Double, Double) -> Double): ModelValue {
        if (a == null || b == null) return ModelValue.Missing
        return ModelValue.Num(op(a, b))
    }

    private fun cmp(a: Double?, b: Double?, op: (Double, Double) -> Boolean): ModelValue {
        if (a == null || b == null) return ModelValue.Flag(false)
        return ModelValue.Flag(op(a, b))
    }

    private fun same(a: ModelValue, b: ModelValue): Boolean = a == b

    private fun truthy(value: ModelValue): Boolean = when (value) {
        is ModelValue.Flag -> value.value
        is ModelValue.Num -> value.value != 0.0
        is ModelValue.Text -> value.value.isNotEmpty()
        is ModelValue.Series -> value.values.isNotEmpty()
        is ModelValue.Empty, is ModelValue.Missing -> false
    }
}
