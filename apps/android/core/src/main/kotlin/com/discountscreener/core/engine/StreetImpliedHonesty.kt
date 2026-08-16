package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.HonestyKnob
import com.discountscreener.core.model.HonestyTaggedKnob
import com.discountscreener.core.model.ImpliedStretch
import com.discountscreener.core.model.StreetImpliedView
import com.discountscreener.core.model.ValuationHonesty
import com.discountscreener.core.model.ValuationModel
import kotlin.math.abs

object StreetImpliedHonesty {
    const val POLICY_VERSION = "street-implied-honesty/3"
    const val ALIGNED_APE_BPS = 200
    const val DISCOUNT_MODEST_BPS = 200
    const val DISCOUNT_STRETCH_BPS = 500
    const val MARGIN_MODEST_BPS = 400
    const val MARGIN_STRETCH_BPS = 1_000
    const val GROWTH_MODEST_BPS = 400
    const val GROWTH_STRETCH_BPS = 1_200
    const val ROE_MODEST_BPS = 300
    const val ROE_STRETCH_BPS = 800

    fun classifyStretch(
        knob: HonestyKnob,
        honestBps: Int,
        impliedBps: Int?,
        reachable: Boolean,
    ): ImpliedStretch {
        if (!reachable || impliedBps == null) return ImpliedStretch.Unreachable
        var gap = abs(impliedBps - honestBps)
        var modest = when (knob) {
            HonestyKnob.DiscountRate -> DISCOUNT_MODEST_BPS
            HonestyKnob.StableMargin -> MARGIN_MODEST_BPS
            HonestyKnob.NearTermGrowth -> GROWTH_MODEST_BPS
            HonestyKnob.StartingRoe -> ROE_MODEST_BPS
        }
        var stretch = when (knob) {
            HonestyKnob.DiscountRate -> DISCOUNT_STRETCH_BPS
            HonestyKnob.StableMargin -> MARGIN_STRETCH_BPS
            HonestyKnob.NearTermGrowth -> GROWTH_STRETCH_BPS
            HonestyKnob.StartingRoe -> ROE_STRETCH_BPS
        }
        return when {
            gap <= modest -> ImpliedStretch.Modest
            gap <= stretch -> ImpliedStretch.Stretched
            else -> ImpliedStretch.Absurd
        }
    }

    fun reconcile(
        analysis: DcfAnalysis,
        streetBaseCents: Long,
        shares: Double?,
    ): StreetImpliedView? {
        if (streetBaseCents <= 0L) return null
        var honest = analysis.baseIntrinsicValueCents
        if (honest <= 0L) return null
        var apeBps = ((abs(honest - streetBaseCents) * 10_000L) / streetBaseCents).toInt()
        var aligned = apeBps <= ALIGNED_APE_BPS
        var knobs = when (analysis.model) {
            ValuationModel.FcffWacc -> fcffKnobs(analysis, streetBaseCents, shares)
            ValuationModel.ResidualIncomeEquity -> residualKnobs(analysis, streetBaseCents, shares)
            ValuationModel.None -> emptyList()
        }
        if (knobs.isEmpty()) return null
        var winner = knobs
            .filter { it.impliedCents != null && it.impliedCents!! > 0L }
            .minByOrNull { abs(it.impliedCents!! - streetBaseCents) }
        var winningStretch = if (aligned) ImpliedStretch.Modest else winner?.stretch
        var winningImplied = if (aligned) winner?.honestBps else winner?.impliedBps
        var winningDelta = if (aligned) 0 else winner?.deltaBps
        return StreetImpliedView(
            streetBaseCents = streetBaseCents,
            honestBaseCents = honest,
            impliedBaseCents = if (aligned) honest else winner?.impliedCents,
            winningKnob = winner?.knob,
            winningHonestBps = winner?.honestBps,
            winningImpliedBps = winningImplied,
            winningDeltaBps = winningDelta,
            winningStretch = winningStretch,
            aligned = aligned,
            knobs = knobs,
            policyVersion = POLICY_VERSION,
        )
    }

    private fun fcffKnobs(
        analysis: DcfAnalysis,
        streetBaseCents: Long,
        shares: Double?,
    ): List<HonestyTaggedKnob> {
        var path = analysis.honestPath ?: return emptyList()
        var revenue = analysis.latestRevenueDollars?.toDouble() ?: return emptyList()
        var count = shares?.takeIf { it > 0.0 } ?: return emptyList()
        var startMargin = path.startMarginBps ?: return emptyList()
        var stableMargin = path.stableMarginBps ?: startMargin
        var growth = analysis.baseGrowthBps
        var discount = analysis.waccBps
        var gStable = analysis.stableGrowthBps
        var hold = path.holdYears ?: 0
        var fade = path.fadeYears ?: 5
        var exponent = (path.fadeExponentHundredths ?: 100) / 100.0
        fun price(
            margin: Int = startMargin,
            stable: Int = stableMargin,
            g: Int = growth,
            wacc: Int = discount,
        ): Long? = FcffFadePricer.equityCentsPerShare(
            latestRevenueDollars = revenue,
            fcffMarginBps = margin,
            stableFcffMarginBps = stable,
            revenueGrowthBps = g,
            currentShares = count,
            netDebtDollars = analysis.netDebtDollars,
            gStableBps = gStable,
            discountRateBps = wacc,
            growthFadeExponent = exponent,
            holdYears = hold,
            fadeYears = fade,
        )
        var marginHit = invertIncreasing(50, 8_000, streetBaseCents) { trial ->
            price(margin = trial, stable = trial)
        }
        var growthHit = invertIncreasing(-1_200, 4_000, streetBaseCents) { trial ->
            price(g = trial)
        }
        var waccFloor = (gStable + 50).coerceAtLeast(300)
        var discountHit = invertDecreasing(waccFloor, 3_000, streetBaseCents) { trial ->
            price(wacc = trial)
        }
        return listOf(
            tag(HonestyKnob.StableMargin, stableMargin, marginHit, "stable FCFF margin"),
            tag(HonestyKnob.NearTermGrowth, growth, growthHit, "near-term growth"),
            tag(HonestyKnob.DiscountRate, discount, discountHit, "discount rate"),
        )
    }

    private fun residualKnobs(
        analysis: DcfAnalysis,
        streetBaseCents: Long,
        shares: Double?,
    ): List<HonestyTaggedKnob> {
        var path = analysis.honestPath ?: return emptyList()
        var count = shares?.takeIf { it > 0.0 } ?: return emptyList()
        var bvps = analysis.bookValuePerShareCents?.takeIf { it > 0L } ?: return emptyList()
        var book0 = (bvps / 100.0) * count
        var roe = analysis.roe0Bps ?: return emptyList()
        var coe = analysis.waccBps
        var retentionBps = path.residualRetentionBps ?: return emptyList()
        var fade = path.residualFadeYears ?: ResidualPathPolicy.DEFAULT_FADE_YEARS
        var spread = path.residualFranchiseSpreadBps ?: FRANCHISE_PERSIST_SPREAD_BPS
        var gStable = analysis.stableGrowthBps
        fun price(roeTrial: Int = roe, coeTrial: Int = coe, spreadTrial: Int = spread): Long? {
            var longRun = ResidualIncomeMath.longRunRoeBps(roeTrial, coeTrial, spreadTrial)
            return ResidualIncomeMath.valuePerShareCents(
                book0 = book0,
                shares = count,
                roe0Bps = roeTrial,
                costOfEquityBps = coeTrial,
                retention = retentionBps / 10_000.0,
                fadeYears = fade,
                longRunRoeBps = longRun,
                stableGrowthBps = gStable,
            )
        }
        var roeHit = invertIncreasing(100, 9_000, streetBaseCents) { trial -> price(roeTrial = trial) }
        var discountHit = invertDecreasing(300, 2_500, streetBaseCents) { trial ->
            price(coeTrial = trial)
        }
        return listOf(
            tag(HonestyKnob.StartingRoe, roe, roeHit, "starting ROE"),
            tag(HonestyKnob.DiscountRate, coe, discountHit, "cost of equity"),
        )
    }

    private fun tag(
        knob: HonestyKnob,
        honestBps: Int,
        hit: InvertHit,
        label: String,
    ): HonestyTaggedKnob {
        var implied = hit.valueBps
        var delta = if (implied != null) implied - honestBps else null
        var stretch = classifyStretch(knob, honestBps, implied, hit.reachable)
        var note = if (hit.reachable && implied != null && delta != null) {
            "$label $honestBps bps honest. Street needs $implied bps (delta $delta, $stretch). This input is not honest."
        } else {
            "$label $honestBps bps honest. Street is not reachable on this knob alone."
        }
        return HonestyTaggedKnob(
            knob = knob,
            honesty = ValuationHonesty.NonHonest,
            honestBps = honestBps,
            impliedBps = implied,
            impliedCents = hit.pricedCents,
            reachable = hit.reachable,
            deltaBps = delta,
            stretch = stretch,
            note = note,
        )
    }

    private data class InvertHit(val valueBps: Int?, val reachable: Boolean, val pricedCents: Long?)

    private fun invertIncreasing(
        lo: Int,
        hi: Int,
        target: Long,
        priceAt: (Int) -> Long?,
    ): InvertHit = invert(lo, hi, target, increasing = true, priceAt)

    private fun invertDecreasing(
        lo: Int,
        hi: Int,
        target: Long,
        priceAt: (Int) -> Long?,
    ): InvertHit = invert(lo, hi, target, increasing = false, priceAt)

    private fun invert(
        lo: Int,
        hi: Int,
        target: Long,
        increasing: Boolean,
        priceAt: (Int) -> Long?,
    ): InvertHit {
        var low = lo
        var high = hi
        var pLow = priceAt(low)
        var pHigh = priceAt(high)
        if (pLow == null && pHigh == null) return InvertHit(null, false, null)
        if (pLow != null && pHigh != null) {
            var minP = minOf(pLow, pHigh)
            var maxP = maxOf(pLow, pHigh)
            if (target < minP || target > maxP) {
                var nearer = if (abs(pLow - target) <= abs(pHigh - target)) low else high
                var nearerPrice = if (nearer == low) pLow else pHigh
                return InvertHit(nearer, false, nearerPrice)
            }
        }
        var best: Int? = null
        var guard = 0
        while (low <= high && guard < 48) {
            guard += 1
            var mid = (low + high) / 2
            var priced = priceAt(mid)
            if (priced == null) {
                if (increasing) high = mid - 1 else low = mid + 1
                continue
            }
            best = mid
            var cmp = priced.compareTo(target)
            if (cmp == 0) return InvertHit(mid, true, priced)
            var goUp = if (increasing) cmp < 0 else cmp > 0
            if (goUp) low = mid + 1 else high = mid - 1
        }
        return InvertHit(best, best != null, best?.let(priceAt))
    }
}
