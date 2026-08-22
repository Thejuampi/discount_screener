package com.discountscreener.core.engine

import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.ValuationAnchorSource
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Our model is a reference number. It cannot change what the card says.
 *
 * The same analyst range is judged against a model that agrees, one that is 3x above the range,
 * one that is a tenth of it, and none at all. The stance, the relation and the primary must be the
 * same four times. This measures the rule instead of reading it.
 */
class OurModelNeverChangesTheStanceTest {

    @Test
    fun the_stance_is_the_same_whatever_our_model_says() {
        var stances = models.map { analysis ->
            var judgment = ValuationJudgmentPolicy.judge(
                ValuationJudgmentRequest(
                    subject = SUBJECT,
                    identity = analysis?.let { IdentityEnvelope(SUBJECT, FinishedIdentity.Computed(it)) },
                    street = street,
                ),
            )
            Stance(judgment.status, judgment.relation, judgment.primaryCents)
        }

        assertEquals(
            listOf(Stance(ValuationJudgmentStatus.Street, AnchorRelation.SingleSource, 20_000L)),
            stances.distinct(),
        )
    }

    @Test
    fun the_probe_moves_our_model_across_the_street_by_enough_to_be_seen() {
        var bases = models.mapNotNull { it?.baseIntrinsicValueCents }

        assertEquals(listOf(20_000L, 60_000L, 2_000L), bases)
    }

    private data class Stance(
        val status: ValuationJudgmentStatus,
        val relation: AnchorRelation,
        val primary: Long?,
    )

    private val models = listOf(
        fcff(base = 20_000L),
        fcff(base = 60_000L),
        fcff(base = 2_000L),
        null,
    )

    private val street = StreetBook(
        subject = SUBJECT,
        source = ValuationAnchorSource.Yahoo,
        lowCents = 18_500L,
        baseCents = 20_000L,
        highCents = 21_500L,
        currencyCode = "USD",
        minorUnitScale = 2,
    )

    private companion object {
        val SUBJECT = JudgmentSubject("MATV", ValuationJudgmentAssembler.SHARE_BASIS)

        fun fcff(base: Long): DcfAnalysis {
            var pad = base / 10L
            return DcfAnalysis(
                bearIntrinsicValueCents = base - pad,
                baseIntrinsicValueCents = base,
                bullIntrinsicValueCents = base + pad,
                waccBps = 1_000,
                baseGrowthBps = 400,
                netDebtDollars = 0L,
                businessClass = BusinessClass.OperatingNonFinancial,
                model = ValuationModel.FcffWacc,
                discountRateKind = DiscountRateKind.Wacc,
            )
        }
    }
}
