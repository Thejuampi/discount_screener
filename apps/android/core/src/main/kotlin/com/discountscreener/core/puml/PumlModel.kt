package com.discountscreener.core.puml

import com.discountscreener.core.runtime.Model

/**
 * A [Model] whose policy is a parsed PlantUML activity document.
 *
 * The document is the model. Kotlin supplies the host and the interpreter.
 */
interface PumlModel : Model {
    val document: PumlDocument
}
