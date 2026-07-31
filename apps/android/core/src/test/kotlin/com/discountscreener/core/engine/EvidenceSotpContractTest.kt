package com.discountscreener.core.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EvidenceSotpContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun shared_point_in_time_fixtures_execute_on_android() {
        val contract = json.decodeFromString<EvidenceSotpContract>(Files.readString(findFixture()))
        contract.pointInTimeFixtures.forEach { fixture ->
            val replay = EvidenceSotpEngine.replay(fixture.observations, fixture.decisionAt)
            assertEquals(fixture.expected.selectedIds, replay.selected.map { it.id }, fixture.name)
            assertEquals(fixture.expected.selectedValuesCents, replay.selected.mapNotNull { it.valueCents }, fixture.name)
            assertEquals(fixture.expected.rejectedCodes, replay.rejected.map { it.code.serialized }, fixture.name)
            assertEquals(fixture.expected.fingerprint, replay.fingerprint, fixture.name)
        }
    }

    @Test
    fun shared_routing_fixtures_execute_on_android() {
        val contract = json.decodeFromString<EvidenceSotpContract>(Files.readString(findFixture()))
        contract.routingFixtures.forEach { fixture ->
            val family = EvidenceSotpEngine.route(ClassificationInput(fixture.sector, fixture.industry, fixture.assetClass))
            assertEquals(fixture.expectedFamily, family, fixture.name)
            assertEquals(fixture.expectedModel, family.model(), fixture.name)
        }
    }

    @Test
    fun shared_sotp_fixtures_execute_on_android_and_one_cent_mutation_fails() {
        val contract = json.decodeFromString<EvidenceSotpContract>(Files.readString(findFixture()))
        val engineVersion = contract.engineVersion
        val modelPolicyVersion = contract.modelPolicyVersion
        val resolverPolicyVersion = contract.resolverPolicyVersion
        contract.consolidationFixtures.forEach { fixture ->
            val output = EvidenceSotpEngine.consolidate(fixture.toInput())
            assertEquals(fixture.expected.status, output.status, fixture.name)
            assertEquals(fixture.expected.coveredEnterpriseValueCents, output.coveredEnterpriseValueCents, fixture.name)
            assertEquals(fixture.expected.equityValueCents, output.equityValueCents, fixture.name)
            assertEquals(fixture.expected.intrinsicPriceCents, output.intrinsicPriceCents, fixture.name)
            assertEquals(fixture.expected.valuationScoreEligible, output.valuationScoreEligible, fixture.name)
            assertEquals(fixture.expected.reasonCodes, output.reasonCodes, fixture.name)
            assertEquals(engineVersion, output.engineVersion, fixture.name)
            assertEquals(modelPolicyVersion, output.modelPolicyVersion, fixture.name)
            assertEquals(resolverPolicyVersion, output.resolverPolicyVersion, fixture.name)
            if (fixture.name == "complete_bridge_publishes_price") {
                assertNotEquals(fixture.expected.equityValueCents?.plus(1L), output.equityValueCents, "one-cent mutation must not pass")
            }
        }
    }

    @Test
    fun historical_accuracy_is_primary_and_market_outcomes_are_not_inputs() {
        val forecast = DriverForecast("AAA", "production", "2023-01-01T00:00:00Z", 1_100)
        val actual = DriverActual("AAA", "production", "2023-12-31", "2024-02-01T00:00:00Z", 1_000)
        val coverage = HistoricalValidationCoverage(
            membership = listOf(HistoricalMembership("AAA", "2022-01-01", "2024-01-01", "2022-01-01T00:00:00Z", "licensed:index:2022")),
        )
        val result = EvidenceSotpEngine.validateDriverForecast(coverage, forecast, actual)
        assertEquals(ValidationStatus.Measured, result.status)
        assertEquals(1_000, result.meanAbsoluteErrorBps)
        assertEquals(false, result.marketOutcomeDiagnosticUsedForPrimary)
    }

    private fun findFixture(): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(8) {
            val candidate = current.resolve("shared/contracts/valuation-evidence-sotp.json").normalize()
            if (Files.exists(candidate)) return candidate
            current = current.parent ?: return@repeat
        }
        error("shared valuation evidence SOTP fixture not found from ${Paths.get("").toAbsolutePath()}")
    }
}

@Serializable
private data class EvidenceSotpContract(
    val engineVersion: String,
    val modelPolicyVersion: String,
    val resolverPolicyVersion: String,
    val pointInTimeFixtures: List<PitFixture> = emptyList(),
    val routingFixtures: List<RouteFixture> = emptyList(),
    val consolidationFixtures: List<ConsolidationFixture> = emptyList(),
)

@Serializable
private data class PitFixture(
    val name: String,
    val decisionAt: String,
    val observations: List<EvidenceObservation>,
    val expected: PitExpected,
)

@Serializable
private data class PitExpected(
    val selectedIds: List<String>,
    val selectedValuesCents: List<Long>,
    val rejectedCodes: List<String>,
    val fingerprint: String,
)

@Serializable
private data class RouteFixture(
    val name: String,
    val sector: String? = null,
    val industry: String? = null,
    val assetClass: AssetClass,
    val expectedFamily: ComponentFamily,
    val expectedModel: ComponentModel,
)

@Serializable
private data class ConsolidationFixture(
    val name: String,
    val components: List<ContractComponent>,
    val corporateOverheadEnterpriseValueCents: Long,
    val separatelyValuedInvestmentsCents: Long,
    val netDebtCents: Long,
    val nciCents: Long,
    val preferredClaimsCents: Long,
    val otherSeniorClaimsCents: Long,
    val shares: Long,
    val expected: ConsolidationExpected,
) {
    fun toInput(): SotpInput = SotpInput(
        issuer = "FIXTURE",
        components = components.map { component ->
            SotpComponent(
                componentId = component.id,
                material = component.material,
                valuation = component.enterpriseValueCents?.let { enterpriseValue ->
                    ComponentValuation(
                        componentId = component.id,
                        family = ComponentFamily.OperatingNonFinancial,
                        model = component.model,
                        status = ComponentStatus.Publishable,
                        enterpriseValueCents = enterpriseValue,
                        scenarios = ScenarioValues(enterpriseValue, enterpriseValue, enterpriseValue),
                        discountRateBps = 800,
                        discountRateKind = DiscountRateKind.Wacc,
                        sourceRegime = SourceRegime.DomesticUsGaap,
                        evidenceRefs = listOf("fixture:${component.id}"),
                        quality = ComponentQuality(
                            evidenceQuality = component.quality,
                            confidence = if (component.quality == EvidenceQuality.Solid) ConfidenceBand.Solid else ConfidenceBand.Provisional,
                            uncertaintyBps = 0,
                            sensitivityBps = 0,
                            solverStabilityBps = 0,
                        ),
                        reasonCodes = emptyList(),
                    )
                },
                refusal = if (component.enterpriseValueCents == null) ValuationRefusalWire("incomplete_segment_disclosures", "fixture unresolved component") else null,
            )
        },
        corporateOverhead = CorporateOverhead(corporateOverheadEnterpriseValueCents, true, listOf("fixture:overhead")),
        bridge = CapitalBridge(
            netDebt = BridgeEvidence(netDebtCents, listOf("fixture:net_debt")),
            nonControllingInterest = BridgeEvidence(nciCents, listOf("fixture:nci")),
            preferredClaims = BridgeEvidence(preferredClaimsCents, listOf("fixture:preferred")),
            otherSeniorClaims = BridgeEvidence(otherSeniorClaimsCents, listOf("fixture:senior")),
            separatelyValuedInvestments = if (separatelyValuedInvestmentsCents == 0L) emptyList() else listOf(BridgeEvidence(separatelyValuedInvestmentsCents, listOf("fixture:investment"))),
        ),
        shares = BridgeEvidence(shares, listOf("fixture:shares")),
        sourceFingerprint = "fixture:$name",
    )
}

@Serializable
private data class ContractComponent(
    val id: String,
    val enterpriseValueCents: Long? = null,
    val material: Boolean,
    val quality: EvidenceQuality,
    val model: ComponentModel,
)

@Serializable
private data class ConsolidationExpected(
    val status: SotpStatus,
    val coveredEnterpriseValueCents: Long?,
    val equityValueCents: Long?,
    val intrinsicPriceCents: Long?,
    val valuationScoreEligible: Boolean,
    val reasonCodes: List<String>,
)
