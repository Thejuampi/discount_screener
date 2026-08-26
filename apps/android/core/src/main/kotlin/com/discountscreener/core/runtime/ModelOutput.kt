package com.discountscreener.core.runtime

/**
 * What a model returns after one evaluate.
 *
 * Bindings are named values. Flags are boolean markers. Emission is the last
 * stop-facing statement, if any. Hunt names such as Act or Unavailable live
 * in the document. They are not types here.
 */
data class ModelOutput(
    val bindings: Map<String, ModelValue>,
    val flags: Set<String> = emptySet(),
    val emission: ModelEmission? = null,
) {
    fun num(name: String): Double? = bindings[name]?.asNum()

    fun text(name: String): String? = bindings[name]?.asText()

    fun flag(name: String): Boolean? = bindings[name]?.asFlag()
}

data class ModelEmission(
    val name: String,
    val fields: Map<String, String> = emptyMap(),
)
