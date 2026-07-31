package com.discountscreener.core.engine

import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.ValuationAnchor
import com.discountscreener.core.model.ValuationAnchorSource
import com.discountscreener.core.model.ValuationAvailability
import com.discountscreener.core.model.ValuationConfidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValuationDecisionPolicyTest {
    @Test fun `difference uses symmetric midpoint denominator and half-up rounding`() {
        assertEquals(2_222, ValuationDecisionPolicy.differenceBps(100, 125))
        assertEquals(2_500, ValuationDecisionPolicy.differenceBps(700_000, 900_000))
        assertNull(ValuationDecisionPolicy.differenceBps(0, 100))
    }

    @Test fun `scenario width permits equal ordered anchors`() {
        assertEquals(0, ValuationDecisionPolicy.scenarioWidthBps(100, 100, 100))
        assertNull(ValuationDecisionPolicy.scenarioWidthBps(101, 100, 102))
    }

    @Test fun `relation thresholds and primary anchor are deterministic`() {
        val model = anchor(ValuationAnchorSource.Model, 100, ValuationConfidence.Solid)
        assertEquals(AnchorRelation.Aligned, ValuationDecisionPolicy.decide(listOf(anchor(ValuationAnchorSource.Model, 700_000, ValuationConfidence.Solid), anchor(ValuationAnchorSource.Yahoo, 900_000))).relation)
        assertEquals(AnchorRelation.Tension, ValuationDecisionPolicy.decide(listOf(anchor(ValuationAnchorSource.Model, 17_499, ValuationConfidence.Solid), anchor(ValuationAnchorSource.Yahoo, 22_501))).relation)
        assertEquals(AnchorRelation.Disputed, ValuationDecisionPolicy.decide(listOf(anchor(ValuationAnchorSource.Model, 14_999, ValuationConfidence.Solid), anchor(ValuationAnchorSource.Yahoo, 25_001))).relation)
        assertEquals(ValuationAnchorSource.Model, ValuationDecisionPolicy.decide(listOf(model, anchor(ValuationAnchorSource.Yahoo, 100))).primaryAnchor)
    }

    @Test fun `incomparable currencies do not create a relation`() {
        val result = ValuationDecisionPolicy.decide(listOf(anchor(ValuationAnchorSource.Model, 100, currency = "USD"), anchor(ValuationAnchorSource.Yahoo, 100, currency = "EUR")))
        assertEquals(AnchorRelation.Unavailable, result.relation)
        assertNull(result.comparisons.single().differenceBps)
    }

    private fun anchor(source: ValuationAnchorSource, value: Long, confidence: ValuationConfidence = ValuationConfidence.Soft, currency: String = "USD") =
        ValuationAnchor(source, value, currency, 2, ValuationAvailability.Available, confidence = confidence)
}
