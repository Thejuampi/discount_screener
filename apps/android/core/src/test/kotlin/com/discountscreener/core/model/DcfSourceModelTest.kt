package com.discountscreener.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DcfSourceModelTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun selected_dcf_analysis_serializes_source_provenance_and_fingerprints() {
        var analysis = DcfAnalysis(
            bearIntrinsicValueCents = 8_000L,
            baseIntrinsicValueCents = 10_000L,
            bullIntrinsicValueCents = 12_000L,
            waccBps = 800,
            baseGrowthBps = 500,
            netDebtDollars = 0L,
            source = DcfSource.YahooFinance,
            sourceFingerprint = "input",
            resolverState = ResolverState.Selected,
            decisionFingerprint = "decision",
        )

        var encoded = json.encodeToString(analysis)

        assertContains(encoded, "\"source\":\"YahooFinance\"")
        assertContains(encoded, "\"resolverState\":\"Selected\"")
        assertContains(encoded, "\"decisionFingerprint\":\"decision\"")
    }

    @Test
    fun legacy_source_free_dcf_analysis_decodes_as_restored_unknown() {
        var decoded = json.decodeFromString<DcfAnalysis>(
            """
            {
              "bearIntrinsicValueCents":8000,
              "baseIntrinsicValueCents":10000,
              "bullIntrinsicValueCents":12000,
              "waccBps":800,
              "baseGrowthBps":500,
              "netDebtDollars":0
            }
            """.trimIndent(),
        )

        assertEquals(ResolverState.RestoredOnly, decoded.resolverState)
        assertEquals(DcfSource.Unknown, decoded.provenance.source)
        assertEquals(
            listOf(ProviderDecisionReasonCode.LegacySourceFreePayload, ProviderDecisionReasonCode.RestoredWithoutLiveRefresh),
            decoded.providerReasons.map { it.code },
        )
        assertEquals(WaccInputProvenance(), decoded.waccInputs)
        assertFalse(decoded.waccInputs.isProvisional())
    }

    @Test
    fun provisional_wacc_inputs_round_trip_without_encode_defaults() {
        val sparseJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val analysis = DcfAnalysis(
            bearIntrinsicValueCents = 8_000L,
            baseIntrinsicValueCents = 10_000L,
            bullIntrinsicValueCents = 12_000L,
            waccBps = 900,
            baseGrowthBps = 500,
            netDebtDollars = 0L,
            source = DcfSource.YahooFinance,
            resolverState = ResolverState.Selected,
            waccInputs = WaccInputProvenance(
                marketCap = WaccFieldSource.DerivedPriceTimesShares,
                beta = WaccFieldSource.Default,
                taxRate = WaccFieldSource.Default,
                waccClamped = true,
            ),
        )

        val encoded = sparseJson.encodeToString(analysis)
        val decoded = sparseJson.decodeFromString<DcfAnalysis>(encoded)

        assertEquals(WaccFieldSource.DerivedPriceTimesShares, decoded.waccInputs.marketCap)
        assertEquals(WaccFieldSource.Default, decoded.waccInputs.beta)
        assertEquals(WaccFieldSource.Default, decoded.waccInputs.taxRate)
        assertTrue(decoded.waccInputs.waccClamped)
        assertTrue(decoded.waccInputs.isProvisional())
        assertContains(encoded, "DerivedPriceTimesShares")
        assertContains(encoded, "\"beta\":\"Default\"")
        assertContains(encoded, "\"taxRate\":\"Default\"")
    }
}
