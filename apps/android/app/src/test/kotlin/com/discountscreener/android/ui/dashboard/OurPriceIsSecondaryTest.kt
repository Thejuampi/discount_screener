package com.discountscreener.android.ui.dashboard

import com.discountscreener.android.presentation.dashboard.HORIZON_PRICE_NOTE
import com.discountscreener.android.presentation.dashboard.presentValuationJudgment
import com.discountscreener.core.engine.ValuationJudgmentPolicy
import com.discountscreener.core.engine.ValuationJudgmentReason
import com.discountscreener.core.engine.ValuationJudgmentStatus
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.ProjectedDetailData
import com.discountscreener.core.model.ProjectedValuationJudgment
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Our price is a reference number. It stays out of the headline, even when it is the largest
 * number on the card, because the judgment names the anchor the reader must act on.
 */
class OurPriceIsSecondaryTest {

    @Test
    fun the_headline_names_the_anchor_the_judgment_names() {
        var headline = detailHeadline(detail, projectedDetail(horizonPriceCents = 36_274L))

        assertEquals("Price $12.52  Fair $21.00  Analyst range", headline)
    }

    @Test
    fun our_price_reads_as_a_reference_line_with_its_warning() {
        var ui = presentValuationJudgment(judgment(horizonPriceCents = 36_274L))

        assertEquals(
            listOf("Our price $362.74", "Reference only. Not in the score."),
            ownModelLines(ui) + ui.horizonPriceNote,
        )
    }

    @Test
    fun a_card_without_our_price_shows_no_reference_line() {
        var ui = presentValuationJudgment(judgment(horizonPriceCents = null))

        assertEquals(emptyList<String>(), ownModelLines(ui))
    }

    @Test
    fun the_note_says_the_score_ignores_our_price() {
        assertEquals("Reference only. Not in the score.", HORIZON_PRICE_NOTE)
    }

    private fun projectedDetail(horizonPriceCents: Long?) = ProjectedDetailData(
        symbol = detail.symbol,
        detail = detail,
        valuationJudgment = judgment(horizonPriceCents),
    )

    private fun judgment(horizonPriceCents: Long?) = ProjectedValuationJudgment(
        status = ValuationJudgmentStatus.Street,
        relation = AnchorRelation.Aligned,
        primaryCents = 2_100L,
        reasonCodes = listOf(ValuationJudgmentReason.StreetPrimary),
        policyVersion = ValuationJudgmentPolicy.POLICY_VERSION,
        identityModelLabel = "FCFF DCF",
        identityBaseCents = horizonPriceCents,
        streetBaseCents = 2_100L,
        lastPriceCents = 1_252L,
        horizonPriceCents = horizonPriceCents,
        horizonDays = 90,
    )

    private val detail = SymbolDetail(
        symbol = "MATV",
        profitable = true,
        marketPriceCents = 1_252L,
        intrinsicValueCents = 2_100L,
        gapBps = 4_038,
        minimumGapBps = 1_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )
}
