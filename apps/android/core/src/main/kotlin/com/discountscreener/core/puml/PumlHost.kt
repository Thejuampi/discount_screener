package com.discountscreener.core.puml

import com.discountscreener.core.runtime.ModelValue

/**
 * Irreducible primitives the diagram calls by name.
 *
 * Hunt formulas, coefficients, and ifs live in the `.puml`. A new primitive
 * name needs Kotlin. A new hunt formula does not.
 */
interface PumlHost {
    /**
     * Evaluate a phrase in the current environment.
     *
     * Unknown phrases return [ModelValue.Missing]. The engine then follows
     * the document's missing/empty branches.
     */
    fun evaluate(phrase: String, env: MutableMap<String, ModelValue>): ModelValue

    /**
     * A named primitive. The diagram supplies the arguments.
     *
     * Allowed names: count, robust_mean, ols, median, sign, and extras
     * a host adds (classify). Hunt identities are not primitives.
     */
    fun call(
        name: String,
        args: List<ModelValue>,
        env: MutableMap<String, ModelValue>,
        document: PumlDocument,
    ): ModelValue = ModelValue.Missing

    /**
     * A bare `:Name;` call with no assignment.
     *
     * Default writes the result under [phrase].
     */
    fun onBareCall(phrase: String, env: MutableMap<String, ModelValue>) {
        env[phrase] = evaluate(phrase, env)
    }

    /**
     * Optional rewrite of emit fields before the engine writes them.
     *
     * Default is identity. Reasons live in the document (`:Watch reason=x;`).
     */
    fun decorateEmit(
        name: String,
        fields: Map<String, String>,
        env: Map<String, ModelValue>,
        flags: Set<String>,
        document: PumlDocument,
    ): Map<String, String> = fields
}
