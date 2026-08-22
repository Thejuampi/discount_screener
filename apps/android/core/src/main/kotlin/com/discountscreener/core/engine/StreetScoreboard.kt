package com.discountscreener.core.engine

import kotlin.math.abs

/** Street is the scoreboard only. Honest and non-honest errors stay separate. */
object StreetScoreboard {
    fun ape(identityCents: Long?, streetCents: Long?): Double? {
        if (identityCents == null || streetCents == null) return null
        if (identityCents <= 0L || streetCents <= 0L) return null
        return abs(identityCents - streetCents).toDouble() / streetCents.toDouble()
    }

    fun formatApe(value: Double?): String =
        if (value == null) "" else "%.3f".format(value)
}
