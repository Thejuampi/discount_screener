package com.discountscreener.core.plan

object PlanCopy {
    fun motive(code: PathMotiveCode): String = when (code) {
        PathMotiveCode.Extension -> "Price extended vs average"
        PathMotiveCode.FarFromSupport -> "Far from support / zone"
        PathMotiveCode.RsiRich -> "Elevated RSI (relative overbought)"
        PathMotiveCode.RsiWashed -> "Depressed RSI (relative oversold)"
        PathMotiveCode.AboveValue -> "Above reference fair value"
        PathMotiveCode.BelowValue -> "Discount vs reference fair value"
        PathMotiveCode.RegimeRisk -> "Adverse market context"
        PathMotiveCode.EarningsSoon -> "Earnings soon"
        PathMotiveCode.TrendAgainst -> "Trend against the setup"
        PathMotiveCode.WeakForecast -> "Weak forecast for this side"
        PathMotiveCode.NearZone -> "Near preferred zone"
        PathMotiveCode.InZone -> "Inside preferred zone"
    }

    fun composite(score: Int): String = "Composite score $score"

    fun timingWeak(): String = "Timing not yet aligned with value"

    fun techMildAdverse(tech: Int): String = "Mild adverse tape (tech $tech)"

    fun techStrongAdverse(tech: Int): String = "Strong adverse tape (tech $tech)"

    fun headline(
        stance: PlanStance,
        zone: String?,
        p20: Int?,
        inv: String?,
        review: String,
    ): String {
        var p20Bit = p20?.let { " p20≈$it%." } ?: ""
        var invBit = inv?.let { " Inv. $it." } ?: ""
        return when (stance) {
            PlanStance.ActNow ->
                if (zone != null) "Entry viable near $zone.$p20Bit$invBit".trim()
                else "Enter now: path does not require a material pullback first."
            PlanStance.ScaleIn ->
                if (zone != null) "Scale toward $zone.$p20Bit Do not force outside the band."
                else "Scale in: useful setup, only partial timing."
            PlanStance.WaitZone ->
                if (zone != null) {
                    var fail = inv?.let { " If it breaks $it, the plan fails." } ?: ""
                    "On radar: zone $zone. Re-check in $review.$p20Bit$fail"
                } else {
                    "Not an entry now. Re-check in $review."
                }
            PlanStance.Avoid -> "Do not act: score and path do not support an entry."
        }
    }

    fun stanceLabel(stance: PlanStance): String = when (stance) {
        PlanStance.ActNow -> "Act now"
        PlanStance.ScaleIn -> "Scale in"
        PlanStance.WaitZone -> "Re-check later"
        PlanStance.Avoid -> "Avoid"
    }
}
