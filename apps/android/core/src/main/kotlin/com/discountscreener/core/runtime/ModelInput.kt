package com.discountscreener.core.runtime

/**
 * Named fields the host and the diagram share.
 *
 * The engine never invents a field. A missing name is [ModelValue.Missing].
 */
class ModelInput internal constructor(
    private val fields: Map<String, ModelValue>,
) {
    fun get(name: String): ModelValue = fields[name] ?: ModelValue.Missing

    fun names(): Set<String> = fields.keys

    fun toMutableEnv(): MutableMap<String, ModelValue> = fields.toMutableMap()

    companion object {
        fun of(vararg fields: Pair<String, ModelValue>): ModelInput = ModelInput(fields.toMap())

        fun from(fields: Map<String, ModelValue>): ModelInput = ModelInput(fields)
    }
}
