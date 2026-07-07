package com.discountscreener.core.model

enum class ComputationArea {
    QuantLens,
    Projection,
    Estimates,
}

data class ComputationFailure(
    val code: String,
    val area: ComputationArea,
    val symbol: String? = null,
    val message: String,
    val recoverable: Boolean,
    val cause: Throwable? = null,
)

sealed interface ComputationResult<out T> {
    data class Success<T>(val value: T) : ComputationResult<T>

    data class Error(val failure: ComputationFailure) : ComputationResult<Nothing>
}

inline fun <T> captureComputationResult(
    area: ComputationArea,
    code: String,
    symbol: String? = null,
    recoverable: Boolean = true,
    crossinline message: (Throwable) -> String,
    block: () -> T,
): ComputationResult<T> = try {
    ComputationResult.Success(block())
} catch (error: Throwable) {
    ComputationResult.Error(
        ComputationFailure(
            code = code,
            area = area,
            symbol = symbol,
            message = message(error),
            recoverable = recoverable,
            cause = error,
        ),
    )
}

fun <T> ComputationResult<T>.getOrNull(): T? = when (this) {
    is ComputationResult.Success -> value
    is ComputationResult.Error -> null
}
