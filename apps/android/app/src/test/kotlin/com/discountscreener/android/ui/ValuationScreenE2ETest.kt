package com.discountscreener.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSourceTab
import com.discountscreener.android.presentation.dashboard.DetailSubtab
import com.discountscreener.android.presentation.dashboard.HistorySubview
import com.discountscreener.android.ui.dashboard.DetailScreen
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ValuationScreenE2ETest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun snapshot_valuation_screen_prioritizes_price_fair_value_and_analyst_concentration() {
        val actions = mutableListOf<DashboardAction>()

        composeRule.setContent {
            DiscountScreenerTheme {
                DetailScreen(
                    route = DetailRoute(
                        symbol = "NVDA",
                        sourceTab = DetailSourceTab.Tracked,
                        sourceSymbols = listOf("NVDA"),
                        subtab = DetailSubtab.Snapshot,
                        historySubview = HistorySubview.Graphs,
                    ),
                    detail = valuationDetail(),
                    charts = emptyMap(),
                    history = emptyList(),
                    alerts = emptyList(),
                    onAction = actions::add,
                )
            }
        }

        assertVisibleAfterScroll("Valuation")
        assertVisibleAfterScroll("Price $172.70")
        assertVisibleAfterScroll("Fair value $272.00")
        assertVisibleAfterScroll("Weighted")
        assertVisibleAfterScroll("Analyst concentration")
        assertVisibleAfterScroll("Strong Buy")
        assertVisibleAfterScroll("20  47.62%")
    }

    private fun assertVisibleAfterScroll(text: String) {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun valuationDetail() = SymbolDetail(
        symbol = "NVDA",
        profitable = true,
        marketPriceCents = 17_270,
        intrinsicValueCents = 26_923,
        gapBps = 5_446,
        minimumGapBps = 1_500,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalFairValueCents = 26_500,
        externalSignalLowFairValueCents = 18_500,
        externalSignalHighFairValueCents = 32_000,
        weightedExternalSignalFairValueCents = 27_200,
        weightedAnalystCount = 9,
        externalSignalGapBps = 5_446,
        externalSignalAgeSeconds = 0,
        externalSignalMaxAgeSeconds = 86_400,
        analystOpinionCount = 42,
        recommendationMeanHundredths = 185,
        strongBuyCount = 20,
        buyCount = 10,
        holdCount = 8,
        sellCount = 3,
        strongSellCount = 1,
        fundamentals = null,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )
}
