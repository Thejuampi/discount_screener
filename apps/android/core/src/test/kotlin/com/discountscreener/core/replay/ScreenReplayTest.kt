package com.discountscreener.core.replay

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.ProjectedProviderCategory
import com.discountscreener.core.model.ProjectedProvenanceState
import com.discountscreener.core.model.ProjectionProfileFacts
import com.discountscreener.core.model.ProjectionRoute
import com.discountscreener.core.model.ProjectionSymbolState
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.ResolverState
import com.discountscreener.core.model.ScreenDataProjectionRequest
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRangeKey
import com.discountscreener.core.model.ValuationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenReplayTest {

    /**
     * The property the whole tool rests on: a captured file yields the screen the device drew.
     * If the round trip loses a field, the replayed numbers drift from the app's, and an experiment
     * measured here would say nothing about the product.
     */
    @Test
    fun a_captured_request_replays_to_the_same_screen() {
        var request = request()
        var replayed = ScreenReplay.project(ScreenReplay.decodeRequest(ScreenReplay.encodeRequest(request)))
        assertEquals(ScreenReplay.project(request), replayed)
    }

    @Test
    fun the_table_names_the_symbol_with_its_upside() {
        var table = ScreenReplay.renderTable(ScreenReplay.project(request()))
        assertTrue(
            table.lines().any { line -> line.startsWith("ACME") && line.contains("20.00") },
            "Expected an ACME row carrying its 2000 bps upside as 20.00, and got:\n$table",
        )
    }

    @Test
    fun an_empty_screen_says_so_instead_of_printing_a_bare_header() {
        assertEquals(
            "No rows. The captured request carried no symbol the engine could project.",
            ScreenReplay.renderTable(ScreenReplay.project(ScreenDataProjectionRequest())),
        )
    }

    /**
     * Candles are keyed by [SymbolRangeKey], and JSON object keys are strings. The capture throws
     * without `allowStructuredMapKeys`, so this holds the format setting in place.
     */
    private fun request() = ScreenDataProjectionRequest(
        profile = ProjectionProfileFacts(currentProfile = "test"),
        route = ProjectionRoute(selectedSymbol = "ACME", selectedRange = ChartRange.Month),
        nowEpochSeconds = 42L,
        trackedSymbols = listOf("ACME"),
        detailsBySymbol = mapOf("ACME" to detail()),
        dcfBySymbol = mapOf("ACME" to dcf()),
        chartCandles = mapOf(
            SymbolRangeKey(symbol = "ACME", range = ChartRange.Month) to listOf(
                HistoricalCandle(
                    epochSeconds = 40L,
                    openCents = 1_000L,
                    highCents = 1_050L,
                    lowCents = 980L,
                    closeCents = 1_000L,
                    volume = 10L,
                ),
            ),
        ),
        symbolStateBySymbol = mapOf(
            "ACME" to ProjectionSymbolState(
                symbol = "ACME",
                providerCategory = ProjectedProviderCategory.Live,
                provenanceState = ProjectedProvenanceState.Live,
            ),
        ),
    )

    /** The fair value the row anchors on: an owned DCF the engine will select. */
    private fun dcf() = DcfAnalysis(
        bearIntrinsicValueCents = 1_100L,
        baseIntrinsicValueCents = 1_200L,
        bullIntrinsicValueCents = 1_300L,
        waccBps = 900,
        baseGrowthBps = 300,
        netDebtDollars = 0L,
        source = DcfSource.YahooFinance,
        resolverState = ResolverState.Selected,
        businessClass = BusinessClass.OperatingNonFinancial,
        model = ValuationModel.FcffWacc,
    )

    /** Price 10.00 against fair 12.00: 1666 bps of discount and 2000 bps of upside. */
    private fun detail() = SymbolDetail(
        symbol = "ACME",
        profitable = true,
        marketPriceCents = 1_000L,
        intrinsicValueCents = 1_200L,
        gapBps = 1_666,
        upsideBps = 2_000,
        minimumGapBps = 2_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalAgeSeconds = 0L,
        externalSignalMaxAgeSeconds = 86_400L,
        fundamentals = FundamentalSnapshot(symbol = "ACME", marketCapDollars = 1_000L),
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )
}
