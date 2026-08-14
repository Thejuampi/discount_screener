package com.discountscreener.core.plan

object DipCopy {
    fun headline(lane: DipLane, streetBps: Int?, missing: String?): String {
        if (lane == DipLane.Now) {
            return "Dip and turn. ${streetLine(streetBps)}"
        }
        if (lane == DipLane.Almost) {
            return if (missing != null) "Close. $missing" else "Close. One gate still open."
        }
        return "Not a dip setup."
    }

    fun streetLine(bps: Int?): String {
        if (bps == null) return "No 12-month target."
        var pct = bps / 100.0
        var sign = if (bps >= 0) "+" else ""
        return "12-month target $sign${"%.0f".format(pct)}%."
    }

    fun dipLine(units: Double?): String {
        if (units == null) return "No ATR dip."
        return "Dip ${"%.1f".format(units)} ATR vs 20-day high."
    }

    fun rsiLine(rsi: Double?, easing: Boolean): String {
        if (rsi == null) return "No RSI."
        var turn = if (easing) "easing" else "not easing"
        return "RSI ${"%.0f".format(rsi)} $turn."
    }

    fun macdLine(phase: MacdPhase): String = when (phase) {
        MacdPhase.Imminent -> "MACD cross is imminent."
        MacdPhase.Turning -> "MACD histogram is turning up."
        MacdPhase.Flipped -> "MACD already flipped."
        MacdPhase.Distant -> "MACD is still far from a cross."
        MacdPhase.Unavailable -> "MACD needs more daily bars."
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

    fun almostGap(reasons: List<String>): String? {
        if (reasons.contains("street_almost")) return "Street 12-month target is under 20%."
        if (reasons.contains("macd_flipped")) return "MACD already flipped."
        if (reasons.contains("macd_distant")) return "MACD is still far from a cross."
        if (reasons.contains("rsi_band")) return "RSI is not in the 25–45 band."
        if (reasons.contains("rsi_not_easing")) return "RSI is not easing."
        return null
    }
}
