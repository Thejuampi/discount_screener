package com.discountscreener.core.plan

object MacdHorizonScore {
    const val ALIGN = 3
    const val FLAT = 0
    const val DRAG = -1

    fun score(
        year: MacdTape?,
        fiveYear: MacdTape?,
        sense: MacdHorizonSense,
    ): Int {
        if (year == null || fiveYear == null) return FLAT
        if (fiveYear.macdPhase == MacdPhase.Unavailable) return FLAT
        return when (sense) {
            MacdHorizonSense.DipTurn -> dipScore(year, fiveYear)
            MacdHorizonSense.LeftoverFade -> leftoverScore(year, fiveYear)
        }
    }

    fun fromTape(tape: DipTape): MacdTape = MacdTape(
        histogram = tape.histogram,
        histSlope = tape.histSlope,
        histAccel = tape.histAccel,
        macdPhase = tape.macdPhase,
    )

    private fun dipScore(year: MacdTape, fiveYear: MacdTape): Int {
        if (isDipTurn(year) && isDipTurn(fiveYear)) return ALIGN
        if (isDipDrag(fiveYear)) return DRAG
        return FLAT
    }

    private fun leftoverScore(year: MacdTape, fiveYear: MacdTape): Int {
        if (isFade(year) && isFade(fiveYear)) return ALIGN
        if (isExpanding(fiveYear)) return DRAG
        return FLAT
    }

    private fun isDipTurn(tape: MacdTape): Boolean =
        tape.histogram <= 0.0 &&
            (tape.macdPhase == MacdPhase.Imminent || tape.macdPhase == MacdPhase.Turning)

    private fun isDipDrag(tape: MacdTape): Boolean =
        tape.histogram < 0.0 &&
            tape.histSlope <= 0.0 &&
            tape.macdPhase == MacdPhase.Distant

    private fun isFade(tape: MacdTape): Boolean = tape.histogram >= 0.0 && tape.histSlope < 0.0

    private fun isExpanding(tape: MacdTape): Boolean = tape.histogram >= 0.0 && tape.histSlope > 0.0
}
