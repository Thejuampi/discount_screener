package com.discountscreener.core.runtime

/**
 * A value the runtime can store in the environment.
 *
 * Missing is a value. Zero is not a stand-in for absence.
 */
sealed class ModelValue {
    data class Num(val value: Double) : ModelValue()

    data class Text(val value: String) : ModelValue()

    data class Flag(val value: Boolean) : ModelValue()

    data class Series(val values: List<Double>) : ModelValue()

    object Empty : ModelValue()

    object Missing : ModelValue()

    fun isPresent(): Boolean = this !is Missing && this !is Empty

    fun asNum(): Double? = (this as? Num)?.value

    fun asFlag(): Boolean? = (this as? Flag)?.value

    fun asText(): String? = (this as? Text)?.value
}
