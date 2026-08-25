package com.discountscreener.core.engine

/**
 * `toDoubleOrNull` screens the text against a Regex before it parses, and on ART every screen
 * builds a Matcher that owns a native ICU object that only a GC releases. `Double.parseDouble`
 * allocates no matcher; a bad value throws instead, and this helper turns that into null.
 */
internal fun String.plainDoubleOrNull(): Double? =
    try {
        toDouble()
    } catch (_: NumberFormatException) {
        null
    }
