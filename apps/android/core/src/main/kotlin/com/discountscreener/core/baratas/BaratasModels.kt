package com.discountscreener.core.baratas

import com.discountscreener.core.puml.ActivityPumlModelFactory
import com.discountscreener.core.puml.PumlModel
import com.discountscreener.core.puml.PumlSource
import com.discountscreener.core.runtime.ModelOutput

/**
 * Process-start binding of the EarningsCheapness document.
 *
 * The PUML is read once. A restart sees edits.
 */
object BaratasModels {
    const val RESOURCE = "/earnings-cheapness.puml"

    val model: PumlModel by lazy { load(readDefaultPuml()) }

    fun load(pumlText: String, uri: String = "earnings-cheapness.puml"): PumlModel =
        ActivityPumlModelFactory.load(PumlSource(uri, pumlText), BaratasPumlHost)

    fun readDefaultPuml(): String {
        var stream = BaratasModels::class.java.getResourceAsStream(RESOURCE)
            ?: error("earnings-cheapness.puml missing from classpath")
        return stream.bufferedReader().use { it.readText() }
    }
}

fun ModelOutput.huntTriage(): String = emission?.name ?: "Unavailable"

fun ModelOutput.huntReason(): String =
    emission?.fields?.get("reason")
        ?: emission?.fields?.get("arg")
        ?: huntTriage().lowercase()
