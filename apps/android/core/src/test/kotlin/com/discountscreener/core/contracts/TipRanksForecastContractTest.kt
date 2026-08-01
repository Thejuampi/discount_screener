package com.discountscreener.core.contracts

import com.discountscreener.core.engine.TipRanksConsensusPolicy
import com.discountscreener.core.model.TipRanksForecast
import com.discountscreener.core.model.TipRanksObservation
import com.discountscreener.core.model.ValuationAvailability
import com.discountscreener.core.model.ValuationConfidence
import com.discountscreener.core.model.ValuationCoverage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class TipRanksForecastContractTest {
    @Test fun shared_forecast_panel_goldens_execute_on_android() {
        val fixture = Json { ignoreUnknownKeys = true }.decodeFromString<ForecastContract>(Files.readString(findFixture()))
        assertEquals(1, fixture.schemaVersion)
        fixture.fixtures.forEach { case ->
            val now = 10_000_000L
            val consensus = TipRanksConsensusPolicy.derive(
                TipRanksForecast(
                    symbol = "TEST", currencyCode = "USD", minorUnitScale = 2,
                    fetchedAtEpochSeconds = now - case.cacheAgeSeconds,
                    observations = case.observations.map {
                        TipRanksObservation(it.identity, it.target, now - it.ageSeconds, it.weightMillis)
                    },
                ), now,
            )
            assertEquals(ValuationAvailability.valueOf(case.expected.availability), consensus.availability, case.name)
            assertEquals(ValuationCoverage.valueOf(case.expected.coverage), consensus.coverage, case.name)
            assertEquals(ValuationConfidence.valueOf(case.expected.confidence), consensus.confidence, case.name)
            assertEquals(case.expected.simple, consensus.simpleTargetMinorUnits, case.name)
            assertEquals(case.expected.weighted, consensus.weightedTargetMinorUnits, case.name)
            assertEquals(case.expected.identities, consensus.eligibleIdentityCount, case.name)
        }
    }

    private fun findFixture() = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .map { it.resolve("shared/contracts/tipranks-forecast-panel.json") }
        .firstOrNull(Files::exists) ?: error("shared TipRanks fixture not found")
}

@Serializable private data class ForecastContract(val schemaVersion: Int, val fixtures: List<ForecastCase>)
@Serializable private data class ForecastCase(val name: String, val cacheAgeSeconds: Long, val observations: List<ForecastObservationCase>, val expected: ForecastExpected)
@Serializable private data class ForecastObservationCase(val identity: String, val target: Long, val ageSeconds: Long, val weightMillis: Int)
@Serializable private data class ForecastExpected(val availability: String, val coverage: String, val confidence: String, val simple: Long, val weighted: Long? = null, val identities: Int)
