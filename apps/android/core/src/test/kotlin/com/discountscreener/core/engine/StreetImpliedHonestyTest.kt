package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.HonestyKnob
import com.discountscreener.core.model.HonestPathInputs
import com.discountscreener.core.model.ImpliedStretch
import com.discountscreener.core.model.ValuationHonesty
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreetImpliedHonestyTest {
    @Test
    fun discount_rate_800_bps_off_is_absurd() {
        assertEquals(
            ImpliedStretch.Absurd,
            StreetImpliedHonesty.classifyStretch(
                knob = HonestyKnob.DiscountRate,
                honestBps = 639,
                impliedBps = 1_150,
                reachable = true,
            ),
        )
    }

    @Test
    fun street_below_honest_implies_a_negative_margin_delta() {
        var analysis = fcffPath(baseCents = 8_000L, startMarginBps = 1_500, stableMarginBps = 1_500)
        var view = StreetImpliedHonesty.reconcile(
            analysis = analysis,
            streetBaseCents = analysis.baseIntrinsicValueCents / 2L,
            shares = 100_000_000.0,
        )
        var margin = requireNotNull(view).knobs.first { it.knob == HonestyKnob.StableMargin }
        assertTrue((margin.deltaBps ?: 0) < 0)
    }

    @Test
    fun street_above_honest_needs_a_higher_margin() {
        var analysis = fcffPath(baseCents = 8_000L, startMarginBps = 1_500, stableMarginBps = 1_500)
        var view = StreetImpliedHonesty.reconcile(
            analysis = analysis,
            streetBaseCents = analysis.baseIntrinsicValueCents * 2L,
            shares = 100_000_000.0,
        )
        var margin = requireNotNull(view).knobs.first { it.knob == HonestyKnob.StableMargin }
        assertTrue((margin.impliedBps ?: 0) > margin.honestBps)
    }

    @Test
    fun implied_knobs_are_typed_non_honest() {
        var view = StreetImpliedHonesty.reconcile(
            analysis = fcffPath(baseCents = 8_000L, startMarginBps = 1_500, stableMarginBps = 1_500),
            streetBaseCents = 16_000L,
            shares = 100_000_000.0,
        )
        assertTrue(requireNotNull(view).knobs.all { it.honesty == ValuationHonesty.NonHonest })
    }

    @Test
    fun aligned_view_does_not_publish_an_absurd_stretch() {
        var analysis = fcffPath(baseCents = 10_000L, startMarginBps = 2_000, stableMarginBps = 2_000)
        var view = StreetImpliedHonesty.reconcile(
            analysis = analysis,
            streetBaseCents = analysis.baseIntrinsicValueCents,
            shares = 100_000_000.0,
        )
        assertEquals(ImpliedStretch.Modest, requireNotNull(view).winningStretch)
    }

    @Test
    fun close_street_marks_the_view_aligned() {
        var analysis = fcffPath(baseCents = 10_000L, startMarginBps = 2_000, stableMarginBps = 2_000)
        var view = StreetImpliedHonesty.reconcile(
            analysis = analysis,
            streetBaseCents = analysis.baseIntrinsicValueCents,
            shares = 100_000_000.0,
        )
        assertEquals(true, requireNotNull(view).aligned)
    }

    @Test
    fun non_honest_price_is_closer_to_street_than_honest() {
        var analysis = fcffPath(baseCents = 8_000L, startMarginBps = 1_500, stableMarginBps = 1_500)
        var street = analysis.baseIntrinsicValueCents * 2L
        var view = requireNotNull(
            StreetImpliedHonesty.reconcile(
                analysis = analysis,
                streetBaseCents = street,
                shares = 100_000_000.0,
            ),
        )
        var honestGap = kotlin.math.abs(analysis.baseIntrinsicValueCents - street)
        var impliedGap = kotlin.math.abs(requireNotNull(view.impliedBaseCents) - street)
        assertTrue(impliedGap < honestGap)
    }

    @Test
    fun residual_implied_knobs_are_typed_non_honest() {
        var view = StreetImpliedHonesty.reconcile(
            analysis = residualPath(),
            streetBaseCents = 20_000L,
            shares = 100_000_000.0,
        )
        assertEquals(true, requireNotNull(view).knobs.all { it.honesty == ValuationHonesty.NonHonest })
    }

    private fun residualPath(): DcfAnalysis {
        var book0 = 10_000_000_000.0
        var shares = 100_000_000.0
        var roe = 1_500
        var coe = 1_000
        var spread = 500
        var priced = requireNotNull(
            ResidualIncomeMath.valuePerShareCents(
                book0 = book0,
                shares = shares,
                roe0Bps = roe,
                costOfEquityBps = coe,
                retention = 0.60,
                fadeYears = 5,
                longRunRoeBps = ResidualIncomeMath.longRunRoeBps(roe, coe, spread),
                stableGrowthBps = 300,
            ),
        )
        return DcfAnalysis(
            bearIntrinsicValueCents = priced - 100,
            baseIntrinsicValueCents = priced,
            bullIntrinsicValueCents = priced + 100,
            waccBps = coe,
            baseGrowthBps = 900,
            netDebtDollars = 0L,
            businessClass = BusinessClass.FinancialServices,
            model = ValuationModel.ResidualIncomeEquity,
            discountRateKind = DiscountRateKind.CostOfEquity,
            stableGrowthBps = 300,
            bookValuePerShareCents = 10_000L,
            roe0Bps = roe,
            honesty = ValuationHonesty.Honest,
            honestPath = HonestPathInputs(
                residualFadeYears = 5,
                residualFranchiseSpreadBps = spread,
                residualRetentionBps = 6_000,
            ),
        )
    }

    private fun fcffPath(
        baseCents: Long,
        startMarginBps: Int,
        stableMarginBps: Int,
    ): DcfAnalysis {
        var priced = FcffFadePricer.equityCentsPerShare(
            latestRevenueDollars = 10_000_000_000.0,
            fcffMarginBps = startMarginBps,
            stableFcffMarginBps = stableMarginBps,
            revenueGrowthBps = 400,
            currentShares = 100_000_000.0,
            netDebtDollars = 0L,
            gStableBps = 300,
            discountRateBps = 800,
            growthFadeExponent = 1.0,
            holdYears = 0,
            fadeYears = 5,
        )
        require(priced != null)
        return DcfAnalysis(
            bearIntrinsicValueCents = priced - 100,
            baseIntrinsicValueCents = if (baseCents > 0L) priced else baseCents,
            bullIntrinsicValueCents = priced + 100,
            waccBps = 800,
            baseGrowthBps = 400,
            netDebtDollars = 0L,
            businessClass = BusinessClass.OperatingNonFinancial,
            model = ValuationModel.FcffWacc,
            discountRateKind = DiscountRateKind.Wacc,
            stableGrowthBps = 300,
            latestRevenueDollars = 10_000_000_000L,
            honesty = ValuationHonesty.Honest,
            honestPath = HonestPathInputs(
                holdYears = 0,
                fadeYears = 5,
                startMarginBps = startMarginBps,
                stableMarginBps = stableMarginBps,
                fadeExponentHundredths = 100,
            ),
        )
    }
}
