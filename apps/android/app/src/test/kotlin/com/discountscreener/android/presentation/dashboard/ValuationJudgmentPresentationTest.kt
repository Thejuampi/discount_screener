package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.engine.ValuationDecisionPolicy
import com.discountscreener.core.engine.ValuationJudgmentPolicy
import com.discountscreener.core.engine.ValuationJudgmentReason
import com.discountscreener.core.engine.ValuationJudgmentStatus
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.ProjectedValuationJudgment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValuationJudgmentPresentationTest {
    @Test
    fun `identity snapshot shows identity stance and primary source`() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Identity,
                relation = AnchorRelation.SingleSource,
                primaryCents = 100_000L,
                reasons = listOf(ValuationJudgmentReason.IdentityPrimary),
                identityModelLabel = "FCFF DCF",
            ),
        )
        assertEquals("Identity", ui.stanceLabel)
    }

    @Test
    fun `street snapshot labels primary as analyst range`() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Street,
                relation = AnchorRelation.Aligned,
                primaryCents = 90_000L,
                reasons = listOf(ValuationJudgmentReason.StreetPrimary),
            ),
        )
        assertEquals("Analyst range", ui.primarySourceLabel)
    }

    @Test
    fun `tension snapshot hides primary`() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Tension,
                relation = AnchorRelation.Tension,
                primaryCents = null,
                reasons = listOf(ValuationJudgmentReason.TensionNoPrimary),
            ),
        )
        assertEquals(false, ui.showPrimary)
    }

    @Test
    fun `disputed snapshot does not repeat the stance as relation`() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Disputed,
                relation = AnchorRelation.Disputed,
                primaryCents = null,
                reasons = listOf(ValuationJudgmentReason.DisputedGap),
            ),
        )
        assertEquals("", ui.relationLabel)
    }

    @Test
    fun `snapshot labels the 90 day price`() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Disputed,
                relation = AnchorRelation.Disputed,
                primaryCents = null,
                reasons = listOf(ValuationJudgmentReason.DisputedGap),
                streetBaseCents = 28_000L,
            ).copy(horizonDays = 90, horizonPriceCents = 28_000L),
        )
        assertEquals("Our price", ui.horizonPriceLabel)
    }

    @Test
    fun `disputed snapshot keeps primary null`() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Disputed,
                relation = AnchorRelation.Disputed,
                primaryCents = null,
                reasons = listOf(ValuationJudgmentReason.DisputedGap),
            ),
        )
        assertNull(ui.primaryCents)
    }

    @Test
    fun `unavailable maps the unclassified reason`() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Unavailable,
                relation = AnchorRelation.Unavailable,
                primaryCents = null,
                reasons = listOf(ValuationJudgmentReason.Unclassified),
            ),
        )
        assertEquals("Business class unclassified. Valuation refused.", ui.reasonLines.single())
    }

    @Test
    fun `unusable identity fan maps to a non-empty reason line`() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Street,
                relation = AnchorRelation.SingleSource,
                primaryCents = 30_000L,
                reasons = listOf(ValuationJudgmentReason.UnusableIdentityFan),
            ),
        )
        assertEquals(true, ui.reasonLines.single().isNotBlank())
    }

    @Test
    fun `displayed official gap equals decision difference bps`() {
        var identityBase = 700_000L
        var streetBase = 900_000L
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Identity,
                relation = AnchorRelation.Aligned,
                primaryCents = identityBase,
                reasons = listOf(ValuationJudgmentReason.IdentityPrimary),
                identityBaseCents = identityBase,
                streetBaseCents = streetBase,
            ),
        )
        assertEquals(
            ValuationDecisionPolicy.differenceBps(identityBase, streetBase),
            ui.officialGapBps,
        )
    }

    private fun snapshot(
        status: ValuationJudgmentStatus,
        relation: AnchorRelation,
        primaryCents: Long?,
        reasons: List<ValuationJudgmentReason>,
        identityModelLabel: String? = null,
        identityBaseCents: Long? = null,
        streetBaseCents: Long? = null,
    ): ProjectedValuationJudgment = ProjectedValuationJudgment(
        status = status,
        relation = relation,
        primaryCents = primaryCents,
        reasonCodes = reasons,
        policyVersion = ValuationJudgmentPolicy.POLICY_VERSION,
        identityModelLabel = identityModelLabel,
        identityBaseCents = identityBaseCents,
        streetBaseCents = streetBaseCents,
    )
}
