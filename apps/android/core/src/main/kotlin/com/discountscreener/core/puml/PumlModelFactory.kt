package com.discountscreener.core.puml

/**
 * Pure constructor: PlantUML text in, [PumlModel] out.
 *
 * No I/O. Unknown activity syntax fails closed. The host is bound at load so
 * evaluate stays a single argument.
 */
interface PumlModelFactory {
    fun load(source: PumlSource, host: PumlHost): PumlModel
}

data class PumlSource(
    val uri: String,
    val text: String,
)
