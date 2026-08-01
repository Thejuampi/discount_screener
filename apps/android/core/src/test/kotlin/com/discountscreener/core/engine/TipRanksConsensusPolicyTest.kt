package com.discountscreener.core.engine

import com.discountscreener.core.model.TipRanksForecast
import com.discountscreener.core.model.TipRanksObservation
import com.discountscreener.core.model.ValuationAvailability
import com.discountscreener.core.model.ValuationConfidence
import com.discountscreener.core.model.ValuationCoverage
import com.discountscreener.core.model.ValuationFreshness
import kotlin.test.Test
import kotlin.test.assertEquals

class TipRanksConsensusPolicyTest {
    private val now = 10_000_000L

    @Test fun `inclusive cache and opinion windows are explicit`() {
        assertEquals(ValuationFreshness.Fresh, TipRanksConsensusPolicy.cacheFreshness(86_400))
        assertEquals(ValuationFreshness.Aging, TipRanksConsensusPolicy.cacheFreshness(86_401))
        assertEquals(ValuationFreshness.Aging, TipRanksConsensusPolicy.cacheFreshness(604_800))
        assertEquals(ValuationFreshness.Stale, TipRanksConsensusPolicy.cacheFreshness(604_801))
        assertEquals(ValuationFreshness.Aging, TipRanksConsensusPolicy.opinionFreshness(7_776_000))
        assertEquals(ValuationFreshness.Stale, TipRanksConsensusPolicy.opinionFreshness(7_776_001))
    }

    @Test fun `three distinct recent identities make a weighted decision eligible`() {
        val consensus = TipRanksConsensusPolicy.derive(forecast(observations = listOf(
            observation("a", 10_000, 1_000), observation("b", 12_000, 2_000), observation("c", 14_000, 3_000),
        )), now)
        assertEquals(ValuationAvailability.Available, consensus.availability)
        assertEquals(ValuationCoverage.Sufficient, consensus.coverage)
        assertEquals(ValuationConfidence.Solid, consensus.confidence)
        assertEquals(12_000L, consensus.weightedTargetMinorUnits)
    }

    @Test fun `sparse and stale observations remain visible but reference only`() {
        val sparse = TipRanksConsensusPolicy.derive(forecast(observations = listOf(observation("a", 10_000, 1_000))), now)
        assertEquals(ValuationAvailability.ReferenceOnly, sparse.availability)
        assertEquals(ValuationCoverage.Sparse, sparse.coverage)
        assertEquals(10_000L, sparse.simpleTargetMinorUnits)

        val oldOpinion = TipRanksConsensusPolicy.derive(forecast(observations = listOf(
            observation("a", 10_000, 1_000), observation("b", 11_000, 2_000), observation("c", 12_000, 7_776_001),
        )), now)
        assertEquals(ValuationAvailability.ReferenceOnly, oldOpinion.availability)
        assertEquals(2, oldOpinion.eligibleIdentityCount)
    }

    @Test fun `three qualifying opinions survive a stale fourth opinion`() {
        val consensus = TipRanksConsensusPolicy.derive(forecast(observations = listOf(
            observation("a", 10_000, 1_000), observation("b", 11_000, 2_000), observation("c", 12_000, 3_000), observation("old", 99_000, 7_776_001),
        )), now)
        assertEquals(ValuationAvailability.Available, consensus.availability)
        assertEquals(3, consensus.eligibleIdentityCount)
    }

    private fun forecast(observations: List<TipRanksObservation>) = TipRanksForecast(
        symbol = "TEST", currencyCode = "USD", minorUnitScale = 2, observations = observations,
        fetchedAtEpochSeconds = now - 86_400,
    )

    private fun observation(identity: String, target: Long, age: Long) = TipRanksObservation(
        analystIdentity = identity, targetMinorUnits = target, observedAtEpochSeconds = now - age, weightMillis = 1_000,
    )
}
