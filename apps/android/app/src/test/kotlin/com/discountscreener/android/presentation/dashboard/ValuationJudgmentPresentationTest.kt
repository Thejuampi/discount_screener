package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.engine.ValuationDecisionPolicy
import com.discountscreener.core.engine.ValuationJudgmentPolicy
import com.discountscreener.core.engine.ValuationJudgmentReason
import com.discountscreener.core.engine.ValuationJudgmentStatus
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.HonestyKnob
import com.discountscreener.core.model.HonestyTaggedKnob
import com.discountscreener.core.model.ImpliedStretch
import com.discountscreener.core.model.ProjectedValuationJudgment
import com.discountscreener.core.model.StreetImpliedView
import com.discountscreener.core.model.ValuationHonesty
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
        assertEquals("Identity model", ui.horizonPriceLabel)
    }

    @Test
    fun street_headline_is_price_and_analyst() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Street,
                relation = AnchorRelation.SingleSource,
                primaryCents = 155_000L,
                reasons = listOf(ValuationJudgmentReason.StreetPrimary),
                streetBaseCents = 155_000L,
            ).copy(lastPriceCents = 97_166L),
        )
        assertEquals("Price $971.66  Analyst $1550.00", ui.forecastHeadline)
    }

    @Test
    fun missing_street_headline_says_no_analyst_forecast() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Unavailable,
                relation = AnchorRelation.Unavailable,
                primaryCents = null,
                reasons = listOf(ValuationJudgmentReason.NoCompleteFamily),
            ).copy(lastPriceCents = 97_166L),
        )
        assertEquals("Price $971.66  No analyst forecast", ui.forecastHeadline)
    }

    @Test
    fun street_source_line_names_the_analyst_range() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Street,
                relation = AnchorRelation.SingleSource,
                primaryCents = 155_000L,
                reasons = listOf(ValuationJudgmentReason.StreetPrimary),
                streetBaseCents = 155_000L,
            ),
        )
        assertEquals("Forecast is the analyst range.", ui.forecastSourceLine)
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
    fun missing_drivers_shows_the_provider_status() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Street,
                relation = AnchorRelation.SingleSource,
                primaryCents = 33_500L,
                reasons = listOf(
                    ValuationJudgmentReason.MissingDrivers,
                    ValuationJudgmentReason.IncompleteIdentity,
                    ValuationJudgmentReason.StreetPrimary,
                ),
            ).copy(
                identityUnavailableReason =
                    "valuation unavailable: fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources",
                providerRefuseLines = listOf(
                    "YahooFinance: fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources",
                    "SecEdgar: fcff unavailable: interest is missing for 2024-09-28,2025-09-27",
                ),
            ),
        )
        assertEquals(
            listOf(
                "Required drivers are missing.",
                "Identity is incomplete.",
                "Primary is the analyst range.",
            ),
            ui.reasonLines,
        )
        assertEquals(
            listOf(
                "valuation unavailable: fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources",
                "YahooFinance: fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources",
                "SecEdgar: fcff unavailable: interest is missing for 2024-09-28,2025-09-27",
            ),
            ui.alertLines,
        )
    }

    @Test
    fun formed_identity_shows_cost_of_debt_source() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Identity,
                relation = AnchorRelation.SingleSource,
                primaryCents = 100_000L,
                reasons = listOf(ValuationJudgmentReason.IdentityPrimary),
                identityModelLabel = "FCFF DCF",
            ).copy(
                identityCaveatLines = listOf(
                    "Cost of debt is a coverage synthetic from filed interest.",
                ),
            ),
        )
        assertEquals(
            listOf("Cost of debt is a coverage synthetic from filed interest."),
            ui.caveatLines,
        )
    }

    @Test
    fun formed_identity_shows_current_instrument_yield_bps() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Identity,
                relation = AnchorRelation.SingleSource,
                primaryCents = 100_000L,
                reasons = listOf(ValuationJudgmentReason.IdentityPrimary),
                identityModelLabel = "FCFF DCF",
            ).copy(
                identityCaveatLines = listOf(
                    "Cost of debt is the current instrument yield, 471 bps.",
                ),
            ),
        )
        assertEquals(
            listOf("Cost of debt is the current instrument yield, 471 bps."),
            ui.caveatLines,
        )
    }

    @Test
    fun formed_identity_shows_estimated_interest_years() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Identity,
                relation = AnchorRelation.SingleSource,
                primaryCents = 100_000L,
                reasons = listOf(ValuationJudgmentReason.IdentityPrimary),
                identityModelLabel = "FCFF DCF",
            ).copy(
                identityCaveatLines = listOf(
                    "Interest for 2024-09-28, 2025-09-27 is an estimate from this issuer's last filed coupon and debt. Confidence is Medium. A later filed tag replaces the estimate.",
                ),
            ),
        )
        assertEquals(listOf("Primary is the identity model."), ui.reasonLines)
        assertEquals(
            listOf(
                "Interest for 2024-09-28, 2025-09-27 is an estimate from this issuer's last filed coupon and debt. Confidence is Medium. A later filed tag replaces the estimate.",
            ),
            ui.caveatLines,
        )
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
    fun honest_line_prints_the_honest_dollar() {
        var ui = presentValuationJudgment(nonHonestSnapshot())
        assertEquals("Honest $78.00", ui.honestValueLine)
    }

    @Test
    fun non_honest_line_prints_the_implied_dollar() {
        var ui = presentValuationJudgment(nonHonestSnapshot())
        assertEquals("Non-honest $29.00", ui.nonHonestValueLine)
    }

    @Test
    fun non_honest_reason_names_the_bent_input() {
        var ui = presentValuationJudgment(nonHonestSnapshot())
        assertEquals(
            "This number bends the stable cash margin from 15.60% to 5.80% so it matches Street.",
            ui.nonHonestReason,
        )
    }

    @Test
    fun aligned_pair_says_no_input_was_bent() {
        var ui = presentValuationJudgment(
            nonHonestSnapshot().copy(
                streetImplied = nonHonestSnapshot().streetImplied?.copy(
                    aligned = true,
                    impliedBaseCents = 7_800L,
                    winningStretch = ImpliedStretch.Modest,
                    winningImpliedBps = 1_560,
                    winningDeltaBps = 0,
                ),
            ),
        )
        assertEquals("Honest and Street already sit together. No input was bent.", ui.nonHonestReason)
    }

    @Test
    fun missing_street_implied_hides_the_non_honest_dollar() {
        var ui = presentValuationJudgment(
            snapshot(
                status = ValuationJudgmentStatus.Identity,
                relation = AnchorRelation.SingleSource,
                primaryCents = 7_800L,
                reasons = listOf(ValuationJudgmentReason.IdentityPrimary),
                identityBaseCents = 7_800L,
            ),
        )
        assertEquals(null, ui.nonHonestValueLine)
    }

    @Test
    fun working_number_stays_honest_when_both_print() {
        var ui = presentValuationJudgment(nonHonestSnapshot())
        assertEquals("Working number is Honest.", ui.honestyModeLabel)
    }

    @Test
    fun non_honest_line_says_the_input_is_not_honest() {
        var ui = presentValuationJudgment(nonHonestSnapshot())
        assertEquals(true, ui.nonHonestLines.single().contains("not honest"))
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

    private fun nonHonestSnapshot(): ProjectedValuationJudgment = snapshot(
        status = ValuationJudgmentStatus.Identity,
        relation = AnchorRelation.Aligned,
        primaryCents = 7_800L,
        reasons = listOf(ValuationJudgmentReason.IdentityPrimary),
        identityBaseCents = 7_800L,
        streetBaseCents = 2_900L,
    ).copy(
        honestyMode = ValuationHonesty.Honest,
        streetImplied = StreetImpliedView(
            streetBaseCents = 2_900L,
            honestBaseCents = 7_800L,
            impliedBaseCents = 2_900L,
            winningKnob = HonestyKnob.StableMargin,
            winningHonestBps = 1_560,
            winningImpliedBps = 580,
            winningDeltaBps = -980,
            winningStretch = ImpliedStretch.Absurd,
            aligned = false,
            knobs = listOf(
                HonestyTaggedKnob(
                    knob = HonestyKnob.StableMargin,
                    honesty = ValuationHonesty.NonHonest,
                    honestBps = 1_560,
                    impliedBps = 580,
                    impliedCents = 2_900L,
                    reachable = true,
                    note = "stable FCFF margin 1560 bps honest. Street needs 580 bps. This input is not honest.",
                ),
            ),
            policyVersion = "street-implied-honesty/3",
        ),
    )

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
