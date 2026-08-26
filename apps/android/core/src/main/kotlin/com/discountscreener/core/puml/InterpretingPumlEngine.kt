package com.discountscreener.core.puml

import com.discountscreener.core.runtime.ModelEmission
import com.discountscreener.core.runtime.ModelInput
import com.discountscreener.core.runtime.ModelOutput
import com.discountscreener.core.runtime.ModelValue

/**
 * Walks an activity document. Formulas in the tree run here. Named phrases go to the host.
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
    )

    private fun runSteps(
        steps: List<PumlStep>,
        env: MutableMap<String, ModelValue>,
        flags: MutableSet<String>,
        host: PumlHost,
        document: PumlDocument,
        emissionIn: ModelEmission?,
    ): Walk {
        var emission = emissionIn
        steps.forEach { step ->
            when (step) {
                is PumlStep.Assign -> env[step.name] = eval(step.expression, env, host)
                is PumlStep.Clear -> env[step.name] = ModelValue.Empty
                is PumlStep.BareCall -> host.onBareCall(step.phrase, env)
                is PumlStep.Flag -> {
                    flags.add(step.name)
                    env[step.name] = ModelValue.Flag(true)
                }
                is PumlStep.Emit -> {
                    emission = applyEmit(step, env, flags, host, document)
                }
                is PumlStep.Stop -> {
                    return Walk(
                        ModelOutput(bindings = env.toMap(), flags = flags.toSet(), emission = emission),
                        emission,
                    )
                }
                is PumlStep.Branch -> {
                    var yes = truthy(eval(step.condition, env, host))
                    var branch = if (yes) step.yes else step.no
                    var nested = runSteps(branch, env, flags, host, document, emission)
                    if (nested.output != null) return nested
                    emission = nested.emission
                }
                is PumlStep.Split -> {
                    step.arms.forEach { arm ->
                        var nested = runSteps(arm, env, flags, host, document, emission)
                        if (nested.output != null) return nested
                        emission = nested.emission
                    }
                }
            }
        }
        return Walk(output = null, emission = emission)
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

    private fun eval(
        expr: PumlExpr,
        env: MutableMap<String, ModelValue>,
        host: PumlHost,
    ): ModelValue = when (expr) {
        is PumlExpr.Number -> ModelValue.Num(expr.value)
        is PumlExpr.Bool -> ModelValue.Flag(expr.value)
        is PumlExpr.Ident -> resolveIdent(expr.name, env, host)
        is PumlExpr.Phrase -> {
            env[expr.text]?.let { return it }
            host.evaluate(expr.text, env)
        }
        is PumlExpr.Unary -> evalUnary(expr.op, eval(expr.inner, env, host))
        is PumlExpr.Binary -> evalBinary(expr.op, expr.left, expr.right, env, host)
    }

    private fun resolveIdent(
        name: String,
        env: MutableMap<String, ModelValue>,
        host: PumlHost,
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
        host: PumlHost,
    ): ModelValue {
        if (op == "or") {
            var left = eval(leftExpr, env, host)
            if (truthy(left)) return ModelValue.Flag(true)
            return ModelValue.Flag(truthy(eval(rightExpr, env, host)))
        }
        if (op == "and") {
            var left = eval(leftExpr, env, host)
            if (!truthy(left)) return ModelValue.Flag(false)
            return ModelValue.Flag(truthy(eval(rightExpr, env, host)))
        }
        var left = eval(leftExpr, env, host)
        var right = eval(rightExpr, env, host)
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
