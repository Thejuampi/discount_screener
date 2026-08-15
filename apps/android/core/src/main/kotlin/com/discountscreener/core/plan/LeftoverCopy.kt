package com.discountscreener.core.plan

object LeftoverCopy {
    fun headline(lane: DipLane, streetBps: Int?, missing: String?): String {
        if (lane == DipLane.Now) {
            return "Leftover gone. Tape is fading. ${streetLine(streetBps)}"
        }
        if (lane == DipLane.Almost) {
            return if (missing != null) "At target. $missing" else "At target. Fade is not on."
        }
        return "Leftover still open."
    }

    fun streetLine(bps: Int?): String {
        if (bps == null) return "No 12-month target."
        var pct = bps / 100.0
        var sign = if (bps >= 0) "+" else ""
        return "12-month target $sign${"%.0f".format(pct)}%."
    }

    fun stretchLine(units: Double?): String {
        if (units == null) return "No ATR stretch."
        return "Last ${"%.1f".format(units)} ATR below the 20-day high."
    }

    fun rsiLine(rsi: Double?, fading: Boolean): String {
        if (rsi == null) return "No RSI."
        var turn = if (fading) "fading" else "not fading"
        return "RSI ${"%.0f".format(rsi)} $turn."
    }

    fun horizonLine(score: Int): String? = when {
        score > 0 -> "1Y and 5Y MACD are fading."
        score < 0 -> "5Y MACD is still expanding."
        else -> null
    }

    fun macdLine(fading: Boolean, phase: MacdPhase): String {
        if (phase == MacdPhase.Unavailable) return "MACD needs more daily bars."
        return if (fading) "MACD histogram is fading." else "MACD is not fading."
    }

    fun fLine(score: Int?): String {
        if (score == null) return "F is missing."
        return "F $score."
    }

    fun valuationLine(label: String?, relation: String, quality: String?): String {
        if (label == null) return "No model tag."
        var q = quality ?: "unknown"
        return "$label $relation · $q."
    }

    fun reviewGap(reasons: List<String>): String? {
        if (reasons.contains("no_fade")) return "Tape is not fading."
        if (reasons.contains("not_near_high")) return "Last is not near the 20-day high."
        return null
    }
}
