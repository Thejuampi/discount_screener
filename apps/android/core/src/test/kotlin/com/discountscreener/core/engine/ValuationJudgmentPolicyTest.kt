package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.ValuationAnchorSource
import com.discountscreener.core.model.ValuationModel
import com.discountscreener.core.model.WaccInputProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValuationJudgmentPolicyTest {
    @Test
    fun `unclassified identity without street is unavailable`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(identity = refused(BusinessClass.Unclassified, ValuationJudgmentReason.Unclassified)),
        )
        assertEquals(ValuationJudgmentStatus.Unavailable, judgment.status)
    }

    @Test
    fun `a class our model refuses does not hide a complete street`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = refused(BusinessClass.Unclassified, ValuationJudgmentReason.Unclassified),
                street = street(baseCents = 90_000L),
            ),
        )
        assertEquals(
            Stance(ValuationJudgmentStatus.Street, 90_000L, streetPresent = true),
            Stance(judgment.status, judgment.primaryCents, judgment.street != null),
        )
    }

    @Test
    fun `a class not eligible for our model still names the street`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = refused(BusinessClass.NotEligible, ValuationJudgmentReason.NotEligible),
                street = street(baseCents = 90_000L),
            ),
        )
        assertEquals(90_000L, judgment.primaryCents)
    }

    @Test
    fun `solid fcff without street is identity primary`() {
        var analysis = fcff(base = 100_000L)
        var judgment = ValuationJudgmentPolicy.judge(request(identity = computed(analysis)))
        assertEquals(
            Stance(ValuationJudgmentStatus.Identity, 100_000L, streetPresent = false),
            Stance(judgment.status, judgment.primaryCents, judgment.street != null),
        )
    }

    @Test
    fun `residual income without street is identity`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(
                    fcff(base = 80_000L).copy(
                        businessClass = BusinessClass.FinancialServices,
                        model = ValuationModel.ResidualIncomeEquity,
                        discountRateKind = DiscountRateKind.CostOfEquity,
                    ),
                ),
            ),
        )
        assertEquals(ValuationJudgmentStatus.Identity, judgment.status)
    }

    @Test
    fun `eligible missing drivers with complete street is street`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = refused(BusinessClass.OperatingNonFinancial, ValuationJudgmentReason.MissingDrivers),
                street = street(baseCents = 90_000L),
            ),
        )
        assertEquals(ValuationJudgmentStatus.Street, judgment.status)
    }

    @Test
    fun `soft identity aligned at 2500 prefers street`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(fcff(base = 700_000L, soft = true)),
                street = street(baseCents = 900_000L),
            ),
        )
        assertEquals(ValuationJudgmentStatus.Street, judgment.status)
    }

    @Test
    fun `a solid model beside the street does not take the primary`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(fcff(base = 700_000L)),
                street = street(baseCents = 900_000L),
            ),
        )
        assertEquals(ValuationJudgmentStatus.Street, judgment.status)
    }

    @Test
    fun `a gap of 2501 bps still names the street`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(fcff(base = 17_499L)),
                street = street(baseCents = 22_501L),
            ),
        )
        assertEquals(
            Stance(ValuationJudgmentStatus.Street, 22_501L, streetPresent = true),
            Stance(judgment.status, judgment.primaryCents, judgment.street != null),
        )
    }

    @Test
    fun `a gap of 5000 bps still names the street`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(fcff(base = 15_000L)),
                street = street(baseCents = 25_000L),
            ),
        )
        assertEquals(ValuationJudgmentStatus.Street, judgment.status)
    }

    @Test
    fun `a gap above 5000 bps still names the street`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(fcff(base = 14_999L)),
                street = street(baseCents = 25_001L),
            ),
        )
        assertEquals(
            Stance(ValuationJudgmentStatus.Street, 25_001L, streetPresent = true),
            Stance(judgment.status, judgment.primaryCents, judgment.street != null),
        )
    }

    @Test
    fun `a wide gap keeps both series`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(fcff(base = 14_999L)),
                street = street(baseCents = 25_001L),
            ),
        )
        assertTrue(judgment.identity != null && judgment.street != null)
    }

    @Test
    fun `fem only stays unavailable and keeps fem`() {
        var judgment = ValuationJudgmentPolicy.judge(request(justifiedMultiple = femAvailable()))
        assertEquals(
            Pair(ValuationJudgmentStatus.Unavailable, true),
            Pair(judgment.status, judgment.justifiedMultiple != null),
        )
    }

    @Test
    fun `market price does not change status or primary`() {
        var base = request(identity = computed(fcff(base = 100_000L)))
        var left = ValuationJudgmentPolicy.judge(base.copy(marketPriceCents = 1L))
        var right = ValuationJudgmentPolicy.judge(base.copy(marketPriceCents = 9_999_999L))
        assertEquals(Pair(left.status, left.primaryCents), Pair(right.status, right.primaryCents))
    }

    @Test
    fun `inverted street is incomplete`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(street = street(lowCents = 120_000L, baseCents = 100_000L, highCents = 90_000L)),
        )
        assertEquals(ValuationJudgmentStatus.Unavailable, judgment.status)
    }

    @Test
    fun `financial fcff input is not identity`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(
                    fcff(base = 100_000L).copy(
                        businessClass = BusinessClass.FinancialServices,
                        model = ValuationModel.FcffWacc,
                    ),
                ),
            ),
        )
        assertTrue(judgment.status != ValuationJudgmentStatus.Identity)
    }

    @Test
    fun `unavailable always carries a reason`() {
        var judgment = ValuationJudgmentPolicy.judge(request())
        assertTrue(judgment.reasonCodes.isNotEmpty())
    }

    @Test
    fun `a currency our model cannot match still names the street`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(fcff(base = 100_000L), currencyCode = "USD"),
                street = street(baseCents = 100_000L, currencyCode = "EUR"),
            ),
        )
        assertEquals(
            Stance(ValuationJudgmentStatus.Street, 100_000L, streetPresent = true),
            Stance(judgment.status, judgment.primaryCents, judgment.street != null),
        )
    }

    @Test
    fun `share basis mismatch drops the street family`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(fcff(base = 100_000L)),
                street = street(baseCents = 90_000L, subject = JudgmentSubject("AAPL", "preferred")),
            ),
        )
        assertEquals(ValuationJudgmentStatus.Identity, judgment.status)
    }

    @Test
    fun `judge accepts a finished refuse without calling compute`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(identity = refused(BusinessClass.Unclassified, ValuationJudgmentReason.Unclassified)),
        )
        assertEquals(ValuationJudgmentStatus.Unavailable, judgment.status)
    }

    @Test
    fun `nvda-like fan wider than usable cut with street is street and unusable`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(fcffFan(1_330L, 52_400L, 382_000L)),
                street = street(lowCents = 18_000L, baseCents = 30_000L, highCents = 50_000L),
            ),
        )
        assertEquals(
            FanStreet(ValuationJudgmentStatus.Street, 30_000L, true),
            FanStreet(
                judgment.status,
                judgment.primaryCents,
                ValuationJudgmentReason.UnusableIdentityFan in judgment.reasonCodes,
            ),
        )
    }

    @Test
    fun `meta-like synthetic fan wider than usable cut with street is street and unusable`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(fcffFan(35_100L, 134_000L, 716_000L)),
                street = street(lowCents = 58_000L, baseCents = 75_000L, highCents = 100_000L),
            ),
        )
        assertEquals(
            FanStreet(ValuationJudgmentStatus.Street, 75_000L, true),
            FanStreet(
                judgment.status,
                judgment.primaryCents,
                ValuationJudgmentReason.UnusableIdentityFan in judgment.reasonCodes,
            ),
        )
    }

    @Test
    fun `a ci-like thin residual far from the street still names the street`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(residualFan(16_622L, 17_768L, 18_651L)),
                street = street(lowCents = 29_000L, baseCents = 34_300L, highCents = 40_000L),
            ),
        )
        assertEquals(
            Stance(ValuationJudgmentStatus.Street, 34_300L, streetPresent = true),
            Stance(judgment.status, judgment.primaryCents, judgment.street != null),
        )
    }

    @Test
    fun `unusable fan with incomplete street is unavailable without primary`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(identity = computed(fcffFan(1_330L, 52_400L, 382_000L))),
        )
        assertEquals(
            FanStreet(ValuationJudgmentStatus.Unavailable, null, true),
            FanStreet(
                judgment.status,
                judgment.primaryCents,
                ValuationJudgmentReason.UnusableIdentityFan in judgment.reasonCodes,
            ),
        )
    }

    @Test
    fun `financial fcff wide fan is not identity`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(
                identity = computed(
                    fcffFan(1_330L, 52_400L, 382_000L).copy(
                        businessClass = BusinessClass.FinancialServices,
                        model = ValuationModel.FcffWacc,
                    ),
                ),
                street = street(baseCents = 90_000L),
            ),
        )
        assertTrue(judgment.status != ValuationJudgmentStatus.Identity)
    }

    @Test
    fun `width at usable cut stays complete identity`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(identity = computed(fcffFan(40_000L, 100_000L, 160_000L))),
        )
        assertEquals(ValuationJudgmentStatus.Identity, judgment.status)
    }

    @Test
    fun `width one bps above usable cut is incomplete and unusable`() {
        var judgment = ValuationJudgmentPolicy.judge(
            request(identity = computed(fcffFan(39_995L, 100_000L, 160_005L))),
        )
        assertEquals(
            FanStreet(ValuationJudgmentStatus.Unavailable, null, true),
            FanStreet(
                judgment.status,
                judgment.primaryCents,
                ValuationJudgmentReason.UnusableIdentityFan in judgment.reasonCodes,
            ),
        )
    }

    private data class Stance(
        val status: ValuationJudgmentStatus,
        val primary: Long?,
        val streetPresent: Boolean,
    )

    private data class FanStreet(
        val status: ValuationJudgmentStatus,
        val primary: Long?,
        val unusableFan: Boolean,
    )

    private fun request(
        identity: IdentityEnvelope? = null,
        street: StreetBook? = null,
        justifiedMultiple: ForwardEarningsMultiple.Result? = null,
    ): ValuationJudgmentRequest =
        ValuationJudgmentRequest(
            subject = SUBJECT,
            identity = identity,
            street = street,
            justifiedMultiple = justifiedMultiple,
        )

    private fun refused(
        businessClass: BusinessClass,
        reason: ValuationJudgmentReason,
    ): IdentityEnvelope =
        IdentityEnvelope(SUBJECT, FinishedIdentity.Refused(businessClass, reason))

    private fun computed(
        analysis: DcfAnalysis,
        currencyCode: String = "USD",
    ): IdentityEnvelope =
        IdentityEnvelope(SUBJECT, FinishedIdentity.Computed(analysis), currencyCode = currencyCode)

    private fun fcffFan(bear: Long, base: Long, bull: Long): DcfAnalysis =
        fcff(base).copy(
            bearIntrinsicValueCents = bear,
            baseIntrinsicValueCents = base,
            bullIntrinsicValueCents = bull,
        )

    private fun residualFan(bear: Long, base: Long, bull: Long): DcfAnalysis =
        fcffFan(bear, base, bull).copy(
            businessClass = BusinessClass.FinancialServices,
            model = ValuationModel.ResidualIncomeEquity,
            discountRateKind = DiscountRateKind.CostOfEquity,
        )

    private fun fcff(base: Long, soft: Boolean = false): DcfAnalysis {
        var pad = (base / 10L).coerceAtLeast(1L)
        return DcfAnalysis(
            bearIntrinsicValueCents = base - pad,
            baseIntrinsicValueCents = base,
            bullIntrinsicValueCents = base + pad,
            waccBps = 1_000,
            baseGrowthBps = 400,
            netDebtDollars = 0L,
            waccInputs = if (soft) WaccInputProvenance(waccClamped = true) else WaccInputProvenance(),
            pointEstimateUnreliable = soft,
            businessClass = BusinessClass.OperatingNonFinancial,
            model = ValuationModel.FcffWacc,
            discountRateKind = DiscountRateKind.Wacc,
        )
    }

    private fun street(
        baseCents: Long,
        lowCents: Long = (baseCents * 8L) / 10L,
        highCents: Long = (baseCents * 12L) / 10L,
        currencyCode: String = "USD",
        subject: JudgmentSubject = SUBJECT,
    ): StreetBook =
        StreetBook(
            subject = subject,
            source = ValuationAnchorSource.Yahoo,
            lowCents = lowCents,
            baseCents = baseCents,
            highCents = highCents,
            currencyCode = currencyCode,
            minorUnitScale = 2,
        )

    private fun femAvailable(): ForwardEarningsMultiple.Result =
        ForwardEarningsMultiple.Result.AvailableResult(
            ForwardEarningsMultiple.Available(
                targetValueCents = 3_640_000L,
                epsCents = 140_000L,
                multipleHundredths = 2_600,
                engineId = ForwardEarningsMultiple.ENGINE_ID,
                methodPolicyVersion = ForwardEarningsMultiple.METHOD_POLICY_VERSION,
                multipleProvenance = ForwardEarningsMultiple.MultipleProvenance.AnalystStated,
                quality = "stated",
                forecastPeriodEnd = "2026-12-31",
                targetAsOf = "2026-08-15",
                datePrecision = "year",
                currency = "USD",
            ),
        )

    companion object {
        private val SUBJECT = JudgmentSubject("AAPL", "common")
    }
}
