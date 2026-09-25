package com.discountscreener.core.runtime

/**
 * Common evaluate surface for a scoring or hunt model.
 *
 * A code model and a PUML-backed model both implement this. The app holds a [Model],
 * not a hunt-specific type.
 */
interface Model {
    val identity: ModelIdentity

    fun evaluate(input: ModelInput): ModelOutput
}

data class ModelIdentity(
    val id: String,
    val version: String,
    val source: ModelSource,
)

sealed class ModelSource {
    data class Puml(val uri: String, val sha256: String) : ModelSource()

    data class Code(val typeName: String) : ModelSource()
}
