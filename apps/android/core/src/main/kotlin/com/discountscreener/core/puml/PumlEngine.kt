package com.discountscreener.core.puml

import com.discountscreener.core.runtime.ModelInput
import com.discountscreener.core.runtime.ModelOutput

/**
 * Interprets a [PumlDocument].
 *
 * Hunt arithmetic does not live here. Formulas in the document run as expressions.
 * Phrases the document does not define go to [PumlHost].
 */
interface PumlEngine {
    fun run(
        document: PumlDocument,
        input: ModelInput,
        host: PumlHost,
    ): ModelOutput
}
