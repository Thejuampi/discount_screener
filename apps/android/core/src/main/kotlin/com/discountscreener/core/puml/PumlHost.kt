package com.discountscreener.core.puml

import com.discountscreener.core.runtime.ModelValue

/**
 * Named phrases the diagram uses and does not define.
 *
 * A new hunt adds a host. It does not fork the engine.
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
     * A bare `:Name;` call with no assignment.
     *
     * Default writes the result under [phrase]. A host may also bind a short alias.
     */
    fun onBareCall(phrase: String, env: MutableMap<String, ModelValue>) {
        env[phrase] = evaluate(phrase, env)
    }

    /**
     * Optional rewrite of emit fields before the engine writes them.
     *
     * Default is identity. A host may fill empty fields the diagram leaves
     * to a table, such as Watch reason.
     */
    fun decorateEmit(
        name: String,
        fields: Map<String, String>,
        env: Map<String, ModelValue>,
        flags: Set<String>,
        document: PumlDocument,
    ): Map<String, String> = fields
}
