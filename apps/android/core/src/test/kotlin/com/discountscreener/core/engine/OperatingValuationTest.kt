package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.WaccFieldSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatingValuationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `forward candidate uses the Rust fixed-point recurrence and fingerprint`() {
        val candidate = valueForwardEarnings(input())

        assertEquals(CandidateStatus.Available, candidate.status)
        assertEquals(18_176L, candidate.intrinsicValueCents)
        assertEquals(300, candidate.stableGrowthBps)
        assertEquals(7, candidate.projectionYears)
        assertEquals(ModelQuality.Soft, candidate.quality)
        assertEquals(EvidenceFamily.AnalystDerivedModel, candidate.evidenceFamily)
        assertTrue(candidate.fingerprint.contains("|terminal_spread=100|"))
        assertTrue(candidate.fingerprint.contains("|observed=19990|period_end=20200|"))
    }

    @Test
    fun `router covers structural representative stale and fail-closed matrix rows`() {
        val forward = valueForwardEarnings(input())
        val structural = routeOperatingModels(
            OperatingRouteInput(
                businessClass = BusinessClass.OperatingNonFinancial,
                fcffCandidate = fcff(5_000),
                forwardCandidate = forward,
                structuralDistortions = listOf(StructuralDistortion.LatestCapexSpike),
            ),
        )
        assertEquals(RouteStatus.Disputed, structural.status)
        assertNull(structural.selectedModel)
        assertNull(structural.selectedValueCents)
        assertEquals(11_370L, structural.candidateDifferenceBps)
        assertEquals(
            listOf(
                RouteReason.StructuralDistortionPresent,
                RouteReason.CandidateDisagreement,
            ),
            structural.reasons,
        )

        val representative = routeOperatingModels(
            OperatingRouteInput(BusinessClass.OperatingNonFinancial, fcff(20_000), forward, emptyList()),
        )
        assertEquals(RouteStatus.Selected, representative.status)
        assertEquals(OperatingModel.FcffWacc, representative.selectedModel)
        assertEquals(20_000L, representative.selectedValueCents)

        val staleInput = input().copy(forecast = input().forecast.copy(observedEpochDay = 19_000))
        val stale = valueForwardEarnings(staleInput)
        assertEquals(listOf(CandidateRefusal.StaleForecast), stale.refusals)
        val staleRoute = routeOperatingModels(
            OperatingRouteInput(
                BusinessClass.OperatingNonFinancial,
                fcff(20_000),
                stale,
                listOf(StructuralDistortion.SourceDiscontinuity),
            ),
        )
        assertEquals(OperatingModel.FcffWacc, staleRoute.selectedModel)

        listOf(
            Triple(BusinessClass.FinancialServices, RouteStatus.Unavailable, RouteReason.FamilyFinancialServices),
            Triple(BusinessClass.NotEligible, RouteStatus.NotEligible, RouteReason.FamilyNotEligible),
            Triple(BusinessClass.Unclassified, RouteStatus.Unavailable, RouteReason.FamilyUnclassified),
        ).forEach { (businessClass, status, reason) ->
            val decision = routeOperatingModels(
                OperatingRouteInput(
                    businessClass,
                    fcff(20_000),
                    forward,
                    listOf(StructuralDistortion.LatestCapexSpike),
                ),
            )
            assertEquals(status, decision.status)
            assertNull(decision.selectedModel)
            assertNull(decision.selectedValueCents)
            assertEquals(listOf(reason), decision.reasons)
        }
    }

    @Test
    fun `invalid and sparse evidence refuse without clamping in canonical order`() {
        val invalidEconomics = input().copy(policy = input().policy.copy(minimumTerminalSpreadBps = 0))
        assertEquals(
            listOf(CandidateRefusal.InvalidPolicy),
            valueForwardEarnings(invalidEconomics).refusals,
        )

        val sparse = input().copy(
            forecast = input().forecast.copy(
                epsLowCents = null,
                analystCount = 1,
                currency = "EUR",
            ),
        )
        assertEquals(
            listOf(
                CandidateRefusal.MissingForwardEps,
                CandidateRefusal.SparseCoverage,
                CandidateRefusal.CurrencyMismatch,
            ),
            valueForwardEarnings(sparse).refusals,
        )
    }

    @Test
    fun `shared executable matrix matches exact candidate routing and fingerprints`() {
        val contract = json.decodeFromString<OperatingContract>(Files.readString(findContract()))
        assertEquals(OperatingValuation.ENGINE_VERSION, contract.engineVersion)
        assertEquals(OperatingValuation.ROUTER_POLICY_VERSION, contract.routerPolicyVersion)
        contract.executableFixtures.forEach { fixture ->
            val candidate = valueForwardEarnings(fixture.input.forwardInput())
            assertEquals(fixture.expected.forwardCandidate, candidate, fixture.name)
            val decision = routeOperatingModels(
                OperatingRouteInput(
                    businessClass = fixture.input.route.businessClass,
                    fcffCandidate = fixture.input.route.fcffCandidate,
                    forwardCandidate = candidate,
                    structuralDistortions = fixture.input.route.structuralDistortions,
                ),
            )
            assertEquals(fixture.expected.routeDecision.status, decision.status, fixture.name)
            assertEquals(fixture.expected.routeDecision.selectedModel, decision.selectedModel, fixture.name)
            assertEquals(fixture.expected.routeDecision.selectedValueCents, decision.selectedValueCents, fixture.name)
            assertEquals(fixture.expected.routeDecision.candidateDifferenceBps, decision.candidateDifferenceBps, fixture.name)
            assertEquals(fixture.expected.routeDecision.reasons, decision.reasons, fixture.name)
            assertEquals(fixture.expected.routeDecision.structuralDistortions, decision.structuralDistortions, fixture.name)
            assertEquals(fixture.expected.routeDecision.fingerprint, decision.fingerprint, fixture.name)
        }
        contract.routerGoldenCases.forEach { case ->
            val forward = valueForwardEarnings(input())
            assertEquals(case.forwardValueCents, forward.intrinsicValueCents, case.name)
            val decision = routeOperatingModels(
                OperatingRouteInput(
                    BusinessClass.OperatingNonFinancial,
                    fcff(case.fcffValueCents),
                    forward,
                    case.structuralDistortions,
                ),
            )
            assertEquals(case.expected.status, decision.status, case.name)
            assertEquals(case.expected.selectedModel, decision.selectedModel, case.name)
            assertEquals(case.expected.selectedValueCents, decision.selectedValueCents, case.name)
            assertEquals(case.expected.candidateDifferenceBps, decision.candidateDifferenceBps, case.name)
            assertEquals(case.expected.reasons, decision.reasons, case.name)
            assertEquals(case.expected.structuralDistortions, decision.structuralDistortions, case.name)
            assertEquals(case.expected.fingerprint, decision.fingerprint, case.name)
        }
        contract.arithmeticGoldenCases.forEach { case ->
            val arithmeticInput = input().copy(
                forecast = input().forecast.copy(nearGrowthBps = case.nearGrowthBps),
                policy = input().policy.copy(holdYears = case.holdYears, fadeYears = case.fadeYears),
            )
            val candidate = valueForwardEarnings(arithmeticInput)
            assertEquals(case.expectedIntrinsicValueCents, candidate.intrinsicValueCents, case.name)
            assertEquals(case.expectedFingerprint, candidate.fingerprint, case.name)
        }
    }

    @Test
    fun `required synthetic matrix and anchor exclusion remain explicit`() {
        val contract = json.decodeFromString<OperatingContract>(Files.readString(findContract()))
        assertEquals(10, contract.executableSyntheticCases.size)
        assertEquals(15, contract.validationCohorts.reported.size)
        assertEquals(12, contract.validationCohorts.holdout.size)

        val baseInput = contract.executableFixtures.single().input.forwardInput()
        val cases = listOf(
            baseInput.copy(forecast = baseInput.forecast.copy(observedEpochDay = baseInput.asOfEpochDay - 365)) to CandidateRefusal.StaleForecast,
            baseInput.copy(forecast = baseInput.forecast.copy(currency = "EUR")) to CandidateRefusal.CurrencyMismatch,
            baseInput.copy(forecast = baseInput.forecast.copy(analystCount = 1)) to CandidateRefusal.SparseCoverage,
            baseInput.copy(policy = baseInput.policy.copy(minimumTerminalSpreadBps = 0)) to CandidateRefusal.InvalidPolicy,
        )
        cases.forEach { (mutated, refusal) ->
            val candidate = valueForwardEarnings(mutated)
            assertTrue(refusal in candidate.refusals)
            val decision = routeOperatingModels(
                OperatingRouteInput(
                    BusinessClass.OperatingNonFinancial,
                    fcff(12_321),
                    candidate,
                    listOf(StructuralDistortion.ThroughCycleRequired),
                ),
            )
            assertEquals(OperatingModel.FcffWacc, decision.selectedModel)
        }

        val nonPositive = valueForwardEarnings(baseInput.copy(forecast = baseInput.forecast.copy(epsMeanCents = 0)))
        assertEquals(listOf(CandidateRefusal.NonPositiveForwardEps), nonPositive.refusals)
        val overflow = valueForwardEarnings(
            baseInput.copy(
                forecast = baseInput.forecast.copy(
                    epsLowCents = Long.MAX_VALUE,
                    epsMeanCents = Long.MAX_VALUE,
                    epsHighCents = Long.MAX_VALUE,
                    nearGrowthBps = Int.MAX_VALUE,
                ),
                policy = baseInput.policy.copy(holdYears = 10, fadeYears = 9),
            ),
        )
        assertEquals(CandidateStatus.Unavailable, overflow.status)
        assertEquals(listOf(CandidateRefusal.ArithmeticOverflow), overflow.refusals)

        val encodedInput = Json.encodeToString(baseInput)
        assertFalse(encodedInput.contains("marketPrice", ignoreCase = true))
        assertFalse(encodedInput.contains("analystTarget", ignoreCase = true))
        assertTrue(encodedInput.contains("\"betaSource\":\"industry_shrink\""))
        assertTrue(encodedInput.contains("\"businessClass\"").not())

        contract.executableSyntheticCases.forEach { case ->
            var syntheticInput = baseInput
            syntheticInput = when (case.mutation) {
                "none" -> syntheticInput
                "stale_forecast" -> syntheticInput.copy(forecast = syntheticInput.forecast.copy(observedEpochDay = 19_000))
                "sparse_coverage" -> syntheticInput.copy(forecast = syntheticInput.forecast.copy(analystCount = 1))
                "non_positive_eps" -> syntheticInput.copy(forecast = syntheticInput.forecast.copy(epsLowCents = 0, epsMeanCents = 0, epsHighCents = 0))
                "invalid_terminal_policy" -> syntheticInput.copy(policy = syntheticInput.policy.copy(minimumTerminalSpreadBps = 0))
                "arithmetic_overflow" -> syntheticInput.copy(
                    forecast = syntheticInput.forecast.copy(
                        epsLowCents = Long.MAX_VALUE,
                        epsMeanCents = Long.MAX_VALUE,
                        epsHighCents = Long.MAX_VALUE,
                        nearGrowthBps = Int.MAX_VALUE,
                    ),
                    policy = syntheticInput.policy.copy(holdYears = 10, fadeYears = 9),
                )
                "multiple_refusals" -> syntheticInput.copy(
                    forecast = syntheticInput.forecast.copy(epsLowCents = null, analystCount = 1, currency = "EUR"),
                )
                else -> error("unknown synthetic mutation ${case.mutation}")
            }
            val candidate = valueForwardEarnings(syntheticInput)
            assertEquals(case.expectedCandidateStatus, candidate.status, case.name)
            assertEquals(case.expectedRefusals, candidate.refusals, case.name)
            val decision = routeOperatingModels(
                OperatingRouteInput(case.businessClass, fcff(5_000), candidate, case.structuralDistortions),
            )
            assertEquals(case.expectedRouteStatus, decision.status, case.name)
            assertEquals(case.expectedSelectedModel, decision.selectedModel, case.name)
        }
    }

    @Test
    fun `policy provenance window and structural work limits fail closed`() {
        val missingProvenance = input().copy(forecast = input().forecast.copy(sourceFingerprint = ""))
        assertTrue(CandidateRefusal.MissingSourceFingerprint in valueForwardEarnings(missingProvenance).refusals)

        val distant = input().copy(forecast = input().forecast.copy(forecastPeriodEndEpochDay = 21_000))
        assertTrue(CandidateRefusal.InvalidForecastPeriod in valueForwardEarnings(distant).refusals)

        val unbounded = input().copy(policy = input().policy.copy(holdYears = 100, fadeYears = 1, maxProjectionYears = Int.MAX_VALUE))
        assertTrue(CandidateRefusal.InvalidProjectionHorizon in valueForwardEarnings(unbounded).refusals)

        val zeroMinimum = input().copy(policy = input().policy.copy(minAnalystCount = 0))
        assertTrue(CandidateRefusal.InvalidPolicy in valueForwardEarnings(zeroMinimum).refusals)
    }

    @Test
    fun `gordon headroom is part of effective stable growth`() {
        val candidate = valueForwardEarnings(input().copy(costOfEquity = input().costOfEquity.copy(costOfEquityBps = 301)))
        assertEquals(201, candidate.stableGrowthBps)
        assertEquals(CandidateStatus.Available, candidate.status)
    }

    @Test
    fun `router rejects contradictory candidates and fingerprints material inputs`() {
        val base = input()
        val candidate = valueForwardEarnings(base)
        listOf(
            base.copy(forecast = base.forecast.copy(forecastPeriodEndEpochDay = base.forecast.forecastPeriodEndEpochDay + 1)),
            base.copy(costOfEquity = base.costOfEquity.copy(costOfEquityBps = base.costOfEquity.costOfEquityBps + 1)),
            base.copy(costOfEquity = base.costOfEquity.copy(provisional = true)),
            base.copy(policy = base.policy.copy(maxAgeDays = base.policy.maxAgeDays + 1)),
        ).forEach { assertTrue(candidate.fingerprint != valueForwardEarnings(it).fingerprint) }

        val contradictory = candidate.copy(refusals = listOf(CandidateRefusal.MissingCoverage))
        val decision = routeOperatingModels(
            OperatingRouteInput(
                BusinessClass.OperatingNonFinancial,
                fcff(20_000),
                contradictory,
                listOf(StructuralDistortion.LatestCapexSpike),
            ),
        )
        assertEquals(OperatingModel.FcffWacc, decision.selectedModel)
        assertTrue(RouteReason.InvalidForwardCandidate in decision.reasons)
    }

    @Test
    fun `validation anchor mutation is bit identical`() {
        val raw = Files.readString(findContract())
        val baseContract = json.decodeFromString<OperatingContract>(raw)
        val baseInput = baseContract.executableFixtures.single().input.forwardInput()
        val before = Json.encodeToString(valueForwardEarnings(baseInput))
        val mutatedRaw = raw.replace("\"analystTargetCents\":5938", "\"analystTargetCents\":9223372036854775807,\"marketPriceCents\":1")
        val mutatedContract = json.decodeFromString<OperatingContract>(mutatedRaw)
        val after = Json.encodeToString(valueForwardEarnings(mutatedContract.executableFixtures.single().input.forwardInput()))
        assertEquals(before, after)
    }

    @Test
    fun `durable reported and holdout cohorts recompute in normal gate`() {
        val contract = json.decodeFromString<OperatingContract>(Files.readString(findContract()))
        val expected = listOf(
            "DVN" to 5_613L, "GDDY" to 12_827L, "WYNN" to 10_741L, "SNDK" to 212_949L,
            "BR" to 22_403L, "BSX" to 6_481L, "AMZN" to 25_081L, "AVGO" to 46_264L,
            "HPE" to 7_938L, "MU" to 161_129L, "ORCL" to 23_228L, "AAPL" to 28_177L,
            "CPRT" to 3_406L, "CEG" to 31_403L, "ALB" to 18_233L, "T" to 3_127L,
            "MSFT" to 61_424L, "NVDA" to 27_522L, "JNJ" to 24_928L, "XOM" to 13_227L,
            "V" to null, "WMT" to 11_246L, "GOOGL" to 40_710L, "META" to 93_724L,
            "HD" to 33_234L, "PG" to 17_400L, "MRK" to 14_303L,
        )
        val reportedErrors = mutableListOf<Double>()
        val holdoutErrors = mutableListOf<Double>()
        (contract.validationCohorts.reported + contract.validationCohorts.holdout)
            .zip(expected)
            .forEachIndexed { index, (row, expectedRow) ->
                assertEquals(expectedRow.first, row.symbol)
                val forward = valueForwardEarnings(row.forwardInput())
                val fcffValue = row.fcffValidationOnlyCents?.takeIf { it > 0 }
                val decision = routeOperatingModels(
                    OperatingRouteInput(
                        row.businessClass,
                        FcffCandidate(
                            if (fcffValue == null) CandidateStatus.Unavailable else CandidateStatus.Available,
                            fcffValue,
                            ModelQuality.Solid,
                            if (fcffValue == null) listOf("trailing_unavailable") else emptyList(),
                            "frozen-fcff:${row.symbol}",
                        ),
                        forward,
                        row.distortions(),
                    ),
                )
                val diagnostic = if (decision.status == RouteStatus.Disputed) forward.intrinsicValueCents else decision.selectedValueCents
                assertEquals(expectedRow.second, diagnostic, row.symbol)
                if (diagnostic != null) {
                    val error = kotlin.math.abs(diagnostic - row.validationOnly.analystTargetCents).toDouble() /
                        row.validationOnly.analystTargetCents.toDouble() * 100.0
                    if (index < 15) reportedErrors += error else holdoutErrors += error
                }
            }
        assertEquals(15, reportedErrors.size)
        assertTrue(reportedErrors.average() < 11.0)
        assertTrue(reportedErrors.max() < 24.0)
        assertEquals(11, holdoutErrors.size)
        assertTrue(holdoutErrors.average() < 11.5)
        assertTrue(holdoutErrors.max() < 21.0)
    }

    private fun input(): ForwardEarningsInput = ForwardEarningsInput(
        asOfEpochDay = 20_000,
        forecast = ForwardForecast(
            epsLowCents = 900,
            epsMeanCents = 1_000,
            epsHighCents = 1_100,
            analystCount = 8,
            nearGrowthBps = 600,
            currency = "USD",
            observedEpochDay = 19_990,
            forecastPeriodEndEpochDay = 20_200,
            sourceFingerprint = "forecast:test",
        ),
        costOfEquity = ResolvedCostOfEquity(
            costOfEquityBps = 900,
            betaSource = WaccFieldSource.IndustryShrink,
            provisional = false,
            marketParamsAsOfEpoch = 1_728_000_000,
            sourceFingerprint = "rate:test",
        ),
        policy = ProjectionPolicy(
            version = "forward-earnings-policy/1",
            expectedCurrency = "USD",
            maxAgeDays = 90,
            minForecastHorizonDays = 180,
            maxForecastHorizonDays = 730,
            minAnalystCount = 3,
            holdYears = 2,
            fadeYears = 4,
            maxProjectionYears = 20,
            macroStableGrowthBps = 300,
            riskFreeRateBps = 430,
            riskFreeBufferBps = 100,
            minimumTerminalSpreadBps = 100,
        ),
    )

    private fun fcff(value: Long?): FcffCandidate = FcffCandidate(
        status = if (value == null) CandidateStatus.Unavailable else CandidateStatus.Available,
        intrinsicValueCents = value,
        quality = ModelQuality.Solid,
        refusalCodes = if (value == null) listOf("missing_fcff") else emptyList(),
        fingerprint = "fcff:test",
    )

    private fun findContract(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = current.resolve("shared/contracts/operating-valuation-router-v1.json")
            if (Files.exists(candidate)) return candidate
            current = current.parent ?: return@repeat
        }
        error("shared operating valuation contract not found")
    }
}

@Serializable
private data class OperatingContract(
    val engineVersion: String,
    val routerPolicyVersion: String,
    val executableFixtures: List<ExecutableFixture>,
    val executableSyntheticCases: List<SyntheticCase>,
    val routerGoldenCases: List<RouterGoldenCase>,
    val arithmeticGoldenCases: List<ArithmeticGoldenCase>,
    val validationCohorts: ValidationCohorts,
)

@Serializable
private data class ArithmeticGoldenCase(
    val name: String,
    val nearGrowthBps: Int,
    val holdYears: Int,
    val fadeYears: Int,
    val expectedIntrinsicValueCents: Long,
    val expectedFingerprint: String,
)

@Serializable
private data class RouterGoldenCase(
    val name: String,
    val forwardValueCents: Long,
    val fcffValueCents: Long,
    val structuralDistortions: List<StructuralDistortion>,
    val expected: ExpectedRouteDecision,
)

@Serializable
private data class SyntheticCase(
    val name: String,
    val mutation: String,
    @Serializable(with = BusinessClassSnakeCaseSerializer::class)
    val businessClass: BusinessClass,
    val structuralDistortions: List<StructuralDistortion>,
    val expectedCandidateStatus: CandidateStatus,
    val expectedRefusals: List<CandidateRefusal>,
    val expectedRouteStatus: RouteStatus,
    val expectedSelectedModel: OperatingModel?,
)

@Serializable
private data class ExecutableFixture(
    val name: String,
    val input: ExecutableInput,
    val expected: ExecutableExpected,
)

@Serializable
private data class ExecutableInput(
    val asOfEpochDay: Long,
    val forecast: ForwardForecast,
    val costOfEquity: ResolvedCostOfEquity,
    val policy: ProjectionPolicy,
    val route: ExecutableRoute,
) {
    fun forwardInput() = ForwardEarningsInput(asOfEpochDay, forecast, costOfEquity, policy)
}

@Serializable
private data class ExecutableRoute(
    @Serializable(with = BusinessClassSnakeCaseSerializer::class)
    val businessClass: BusinessClass,
    val fcffCandidate: FcffCandidate,
    val structuralDistortions: List<StructuralDistortion>,
)

@Serializable
private data class ExecutableExpected(
    val forwardCandidate: ForwardEarningsCandidate,
    val routeDecision: ExpectedRouteDecision,
)

@Serializable
private data class ExpectedRouteDecision(
    val status: RouteStatus,
    val selectedModel: OperatingModel?,
    val selectedValueCents: Long?,
    val candidateDifferenceBps: Long?,
    val reasons: List<RouteReason>,
    val structuralDistortions: List<StructuralDistortion>,
    val fingerprint: String,
)

@Serializable
private data class ValidationCohorts(
    val reported: List<ValidationName>,
    val holdout: List<ValidationName>,
)

@Serializable
private data class ValidationName(
    val symbol: String,
    @Serializable(with = BusinessClassSnakeCaseSerializer::class)
    val businessClass: BusinessClass,
    val epsLowCents: Long,
    val epsMeanCents: Long,
    val epsHighCents: Long,
    val analystCount: Int,
    val forecastEndEpochDay: Long,
    val nearGrowthBps: Int,
    val resolvedCostOfEquityBps: Int,
    val holdYears: Int,
    val fcffValidationOnlyCents: Long?,
    val routeEvidence: List<String>,
    val validationOnly: ValidationOnly,
) {
    fun forwardInput() = ForwardEarningsInput(
        20_665,
        ForwardForecast(
            epsLowCents,
            epsMeanCents,
            epsHighCents,
            analystCount,
            nearGrowthBps,
            "USD",
            20_665,
            forecastEndEpochDay,
            "frozen-yahoo:$symbol:2026-07-31",
        ),
        ResolvedCostOfEquity(
            resolvedCostOfEquityBps,
            WaccFieldSource.IndustryShrink,
            true,
            null,
            "poc5-resolved-rate:$symbol",
        ),
        ProjectionPolicy(
            "forward-earnings-policy/1-poc",
            "USD",
            90,
            180,
            730,
            3,
            holdYears,
            10,
            25,
            300,
            400,
            100,
            100,
        ),
    )

    fun distortions() = routeEvidence.mapNotNull {
        when (it) {
            "trailing_unavailable" -> StructuralDistortion.TrailingCashUnavailable
            "through_cycle_required" -> StructuralDistortion.ThroughCycleRequired
            "extreme_leverage" -> StructuralDistortion.ExtremeLeverage
            "stale_sec_period" -> StructuralDistortion.SourceDiscontinuity
            "thin_normalized_fcff_margin" -> StructuralDistortion.ThinNormalizedFcffMargin
            "latest_capex_spike" -> StructuralDistortion.LatestCapexSpike
            "acquisition_discontinuity" -> StructuralDistortion.AcquisitionDiscontinuity
            "durable_excess_return_evidence" -> StructuralDistortion.DurableExcessReturnEvidence
            else -> null
        }
    }
}

@Serializable
private data class ValidationOnly(val analystTargetCents: Long)
