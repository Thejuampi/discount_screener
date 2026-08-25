package com.discountscreener.core.plan

object CrossCopy {
    fun headline(lane: DipLane, streetBps: Int?, missing: String?): String {
        if (lane == DipLane.Now) {
            return "MACD golden cross. ${streetLine(streetBps)}"
        }
        if (lane == DipLane.Almost) {
            return if (missing != null) "Close. $missing" else "Close. One gate still open."
        }
        return "Not a golden-cross setup."
    }

    fun streetLine(bps: Int?): String {
        if (bps == null) return "No 12-month target."
        var pct = bps / 100.0
        var sign = if (bps >= 0) "+" else ""
        return "12-month target $sign${"%.0f".format(pct)}%."
    }

    fun rsiLine(rsi: Double?): String {
        if (rsi == null) return "No RSI."
        return "RSI ${"%.0f".format(rsi)}."
    }

    fun horizonLine(score: Int): String? = when {
        score > 0 -> "1Y and 5Y MACD are expanding."
        score < 0 -> "5Y MACD is a drag."
        else -> null
    }

    fun macdLine(bars: Int?, phase: MacdPhase): String {
        if (phase == MacdPhase.Unavailable) return "MACD needs more daily bars."
        if (bars == 0) return "MACD is at the golden cross."
        if (bars != null) {
            var unit = if (bars == 1) "bar" else "bars"
            return "MACD crossed $bars $unit ago."
        }
        return "MACD is not at a golden cross."
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
        return null
    }
}
