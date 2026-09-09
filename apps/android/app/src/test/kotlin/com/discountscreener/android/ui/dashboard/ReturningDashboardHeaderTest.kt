package com.discountscreener.android.ui.dashboard

import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.DpRect
import com.discountscreener.android.StuckTestWatchdog
import com.discountscreener.android.domain.model.DashboardStartupPhase
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.TickerSearchSuggestion
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DashboardTab
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import com.discountscreener.android.ui.verticalList
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.OpportunityScoringModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.unit.dp

@RunWith(RobolectricTestRunner::class)
class ReturningDashboardHeaderTest {
    @get:Rule
    val stuckTestWatchdog = StuckTestWatchdog()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var fullHeaderHeight = 0f

    @Test
    fun header_short_expanded_search_stays_in_bounds_and_scrolls_to_tabs_and_search() {
        val hostTag = "shortDashboardHost"
        composeRule.setContent {
            DiscountScreenerTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .testTag(hostTag),
                ) {
                    DashboardScreen(
                        state = longDashboardState().copy(
                            tickerSearchQuery = "A",
                            tickerSearchExpanded = true,
                            tickerSearchSuggestions = List(2) { index ->
                                TickerSearchSuggestion(symbol = "A$index.BA")
                            },
                        ),
                        onAction = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val host = composeRule.onNodeWithTag(hostTag).getUnclippedBoundsInRoot()
        val header = bounds(RETURNING_HEADER_TAG)
        assertTrue(header.top >= host.top)
        assertTrue(header.bottom <= host.bottom)
        composeRule.onNodeWithText("Ticker or company").assertIsDisplayed()

        repeat(4) {
            composeRule.onNodeWithTag(RETURNING_HEADER_TAG).performTouchInput {
                swipe(start = Offset(120f, 165f), end = Offset(120f, 20f), durationMillis = 100)
            }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Opps 40").assertIsDisplayed()

        repeat(4) {
            composeRule.onNodeWithTag(RETURNING_HEADER_TAG).performTouchInput {
                swipe(start = Offset(120f, 20f), end = Offset(120f, 165f), durationMillis = 100)
            }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Ticker or company").assertIsDisplayed()
    }

    @Test
    fun header_down_hides_the_complete_header_and_reclaims_list_space() {
        renderDashboard()
        val headerBefore = bounds(RETURNING_HEADER_TAG)
        val contentBefore = bounds(DASHBOARD_CONTENT_TAG)

        composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        val headerAfter = bounds(RETURNING_HEADER_TAG)
        val contentAfter = bounds(DASHBOARD_CONTENT_TAG)
        assertTrue("header should hide after a downward content scroll", height(headerAfter) < height(headerBefore))
        assertTrue("content should reclaim the hidden header height", contentAfter.top < contentBefore.top)
        composeRule.onNodeWithText("Discount Screener").assertIsNotDisplayed()
        composeRule.onNodeWithText("Ticker or company").assertIsNotDisplayed()
        composeRule.onNodeWithText("Positions 0").assertIsNotDisplayed()
    }

    @Test
    fun header_bottom_continued_downward_content_motion_keeps_header_hidden() {
        renderDashboard()
        repeat(12) {
            composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        val hiddenHeight = height(bounds(RETURNING_HEADER_TAG))
        assertTrue(hiddenHeight < fullHeaderHeight)

        composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertEquals(hiddenHeight, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
        composeRule.onNodeWithText("Discount Screener").assertIsNotDisplayed()
    }

    @Test
    fun header_up_restores_the_complete_header_without_resetting_the_list() {
        renderDashboard()
        val contentAtTop = bounds(DASHBOARD_CONTENT_TAG).top.value
        repeat(6) {
            composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("SYM39.BA").assertIsDisplayed()
        composeRule.onNodeWithText("SYM0.BA").assertDoesNotExist()
        composeRule.waitForIdle()
        composeRule.onNode(verticalList()).performTouchInput {
            swipe(start = Offset(200f, 300f), end = Offset(200f, 360f), durationMillis = 100)
        }
        composeRule.waitForIdle()

        assertEquals(fullHeaderHeight, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
        assertEquals(contentAtTop, bounds(DASHBOARD_CONTENT_TAG).top.value, 0.01f)
        composeRule.onNodeWithText("SYM0.BA").assertDoesNotExist()
    }

    @Test
    fun header_small_reverses_direction_below_eight_dp_without_visibility_change() {
        val state = ReturningDashboardHeaderState()
        state.setMeasuredHeight(100)
        state.onUserScroll(deltaY = -7f, childConsumed = true)
        state.onUserScroll(deltaY = 7f, childConsumed = true)
        assertEquals(0f, state.hiddenHeightPx, 0.001f)
    }

    @Test
    fun header_small_reverses_direction_below_eight_dp_on_the_mounted_dashboard() {
        renderDashboard()
        val headerBefore = bounds(RETURNING_HEADER_TAG)
        val contentBefore = bounds(DASHBOARD_CONTENT_TAG)

        composeRule.onNode(verticalList()).performTouchInput {
            down(Offset(200f, 300f))
            moveBy(Offset(0f, -4f))
            moveBy(Offset(0f, 4f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(height(headerBefore), height(bounds(RETURNING_HEADER_TAG)), 0.01f)
        assertEquals(contentBefore.top.value, bounds(DASHBOARD_CONTENT_TAG).top.value, 0.01f)
    }

    @Test
    fun header_horizontal_keeps_the_mounted_header_unchanged() {
        renderDashboard()
        composeRule.onNodeWithText("Opps 40").assertIsDisplayed()
        val before = height(bounds(RETURNING_HEADER_TAG))
        composeRule.onNodeWithText("Opps 40").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(before, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
    }

    @Test
    fun header_focus_pins_an_empty_search_on_the_mounted_dashboard() {
        renderDashboard()
        composeRule.onNodeWithText("Ticker or company").performClick()
        composeRule.waitForIdle()
        val focusedHeaderHeight = height(bounds(RETURNING_HEADER_TAG))
        composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals(focusedHeaderHeight, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
        composeRule.onNodeWithText("Discount Screener").assertIsDisplayed()
    }

    @Test
    fun header_focus_back_releases_empty_search_and_allows_hide() {
        renderDashboard()
        composeRule.onNodeWithText("Ticker or company").performClick()
        composeRule.waitForIdle()
        val focusedHeaderHeight = height(bounds(RETURNING_HEADER_TAG))

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertTrue(height(bounds(RETURNING_HEADER_TAG)) < focusedHeaderHeight)
        composeRule.onNodeWithText("Discount Screener").assertIsNotDisplayed()
    }

    @Test
    fun header_active_pins_search_with_existing_query() {
        renderDashboard(longDashboardState().copy(tickerSearchQuery = "AA", tickerSearchExpanded = true))
        composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals(fullHeaderHeight, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
    }

    @Test
    fun header_tab_reveals_after_selected_tab_changes() {
        composeRule.setContent {
            DiscountScreenerTheme {
                var state by remember { mutableStateOf(longDashboardState()) }
                DashboardScreen(
                    state = state,
                    onAction = { action ->
                        if (action is DashboardAction.SelectTab) state = state.copy(currentTab = action.tab)
                    },
                )
            }
        }
        composeRule.waitForIdle()
        fullHeaderHeight = height(bounds(RETURNING_HEADER_TAG))
        composeRule.onNode(verticalList()).performTouchInput {
            swipe(start = Offset(200f, 300f), end = Offset(200f, 280f), durationMillis = 100)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Market").performClick()
        composeRule.waitForIdle()
        assertEquals(fullHeaderHeight, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
    }

    @Test
    fun header_detail_return_starts_with_a_full_header() {
        val dashboardVisible = mutableStateOf(true)
        composeRule.setContent {
            DiscountScreenerTheme {
                if (dashboardVisible.value) {
                    DashboardScreen(state = longDashboardState(), onAction = {})
                } else {
                    BackHandler { }
                }
            }
        }
        composeRule.waitForIdle()
        fullHeaderHeight = height(bounds(RETURNING_HEADER_TAG))
        composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        dashboardVisible.value = false
        composeRule.waitForIdle()
        dashboardVisible.value = true
        composeRule.waitForIdle()
        assertEquals(fullHeaderHeight, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
        composeRule.onNodeWithText("Discount Screener").assertIsDisplayed()
    }

    @Test
    fun header_short_keeps_the_header_visible() {
        renderDashboard(DashboardUiState(loading = false, startupPhase = DashboardStartupPhase.Ready))
        composeRule.onNodeWithTag(DASHBOARD_CONTENT_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals(fullHeaderHeight, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
    }

    @Test
    fun header_programmatic_list_motion_does_not_hide_the_header() {
        val state = mutableStateOf(longDashboardState())
        composeRule.setContent {
            DiscountScreenerTheme { DashboardScreen(state = state.value, onAction = {}) }
        }
        composeRule.waitForIdle()
        fullHeaderHeight = height(bounds(RETURNING_HEADER_TAG))
        state.value = state.value.copy(opportunityRows = state.value.opportunityRows.drop(1))
        composeRule.waitForIdle()
        assertEquals(fullHeaderHeight, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
    }

    @Test
    fun header_accessibility_pinning_keeps_the_mounted_header_visible() {
        val manager = composeRule.activity.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setTouchExplorationEnabled(true)
        renderDashboard()
        composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals(fullHeaderHeight, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
    }

    @Test
    fun header_other_tab_hides_and_recovers_with_the_same_gesture_rules() {
        renderDashboard(
            longDashboardState().copy(
                currentTab = DashboardTab.Tracked,
                opportunityRows = emptyList(),
                trackedRows = List(40) { index -> TrackedSymbolRow(symbol = "TRACK$index.BA") },
            ),
        )
        composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val hiddenHeight = height(bounds(RETURNING_HEADER_TAG))
        composeRule.onNode(verticalList()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertTrue(height(bounds(RETURNING_HEADER_TAG)) > hiddenHeight)
    }

    @Test
    fun header_shrink_recovers_after_content_becomes_short() {
        val state = mutableStateOf(longDashboardState())
        composeRule.setContent {
            DiscountScreenerTheme { DashboardScreen(state = state.value, onAction = {}) }
        }
        composeRule.waitForIdle()
        fullHeaderHeight = height(bounds(RETURNING_HEADER_TAG))
        composeRule.onNode(verticalList()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        state.value = state.value.copy(opportunityRows = emptyList())
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DASHBOARD_CONTENT_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertEquals(fullHeaderHeight, height(bounds(RETURNING_HEADER_TAG)), 0.01f)
    }

    @Test
    fun pure_state_restores_completely_after_the_upward_threshold() {
        val state = ReturningDashboardHeaderState()
        state.setMeasuredHeight(100)
        state.onUserScroll(deltaY = -30f, childConsumed = true)
        assertTrue(state.hiddenHeightPx > 0f)
        state.onUserScroll(deltaY = 8f, childConsumed = true)
        assertEquals(0f, state.hiddenHeightPx, 0.001f)
    }

    @Test
    fun pure_state_ignores_programmatic_motion_and_empty_content_cannot_hide() {
        val state = ReturningDashboardHeaderState()
        state.setMeasuredHeight(100)
        state.onUserScroll(deltaY = -100f, childConsumed = false, userInput = false)
        state.onUserScroll(deltaY = -100f, childConsumed = false)
        assertEquals(0f, state.hiddenHeightPx, 0.001f)
    }

    @Test
    fun pure_state_ignores_unconsumed_downward_content_motion_when_hidden() {
        val state = ReturningDashboardHeaderState()
        state.setMeasuredHeight(100)
        state.onUserScroll(deltaY = -30f, childConsumed = true)
        val hiddenHeight = state.hiddenHeightPx

        state.onUserScroll(deltaY = -30f, childConsumed = false)

        assertEquals(hiddenHeight, state.hiddenHeightPx, 0.001f)
    }

    @Test
    fun mounted_programmatic_lazy_list_scroll_does_not_hide_the_header() {
        composeRule.setContent {
            DiscountScreenerTheme {
                val listState = rememberLazyListState()
                val headerState = remember { ReturningDashboardHeaderState() }
                val connection = remember(headerState) { headerState.nestedScrollConnection() }
                LaunchedEffect(Unit) { listState.scrollToItem(30) }
                Column(modifier = Modifier.fillMaxSize()) {
                    ReturningDashboardHeader(
                        state = headerState,
                        pinned = false,
                        resetKey = Unit,
                    ) {
                        androidx.compose.material3.Text("Programmatic header")
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .nestedScroll(connection)
                            .testTag("programmaticList"),
                    ) {
                        items((0 until 40).toList()) { index ->
                            androidx.compose.material3.Text("Programmatic item $index")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Programmatic item 30").assertIsDisplayed()
        composeRule.onNodeWithText("Programmatic header").assertIsDisplayed()
    }

    @Test
    fun boundary_recovery_accumulates_then_blocks_recollapse_until_the_gesture_ends() {
        val state = ReturningDashboardHeaderState()
        state.setMeasuredHeight(100)
        state.onUserScroll(deltaY = -30f, childConsumed = true)
        assertTrue(state.hiddenHeightPx > 0f)

        repeat(4) { state.onUserScroll(deltaY = 2f, childConsumed = false) }
        assertEquals(0f, state.hiddenHeightPx, 0.001f)
        state.onUserScroll(deltaY = -30f, childConsumed = true)
        assertEquals(0f, state.hiddenHeightPx, 0.001f)

        state.endGesture()
        state.onUserScroll(deltaY = -30f, childConsumed = true)
        assertTrue(state.hiddenHeightPx > 0f)
    }

    @Test
    fun completed_consumed_recovery_blocks_recollapse_until_the_gesture_ends() {
        val state = ReturningDashboardHeaderState()
        state.setMeasuredHeight(100)
        state.onUserScroll(deltaY = -30f, childConsumed = true)
        state.onUserScroll(deltaY = 12f, childConsumed = true)
        assertEquals(0f, state.hiddenHeightPx, 0.001f)
        state.onUserScroll(deltaY = -30f, childConsumed = true)
        assertEquals(0f, state.hiddenHeightPx, 0.001f)
        state.endGesture()
        state.onUserScroll(deltaY = -30f, childConsumed = true)
        assertTrue(state.hiddenHeightPx > 0f)
    }

    private fun renderDashboard(state: DashboardUiState = longDashboardState()) {
        composeRule.setContent {
            DiscountScreenerTheme { DashboardScreen(state = state, onAction = {}) }
        }
        composeRule.waitForIdle()
        fullHeaderHeight = height(bounds(RETURNING_HEADER_TAG))
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun height(rect: DpRect): Float = (rect.bottom - rect.top).value

    private fun longDashboardState() = DashboardUiState(
        loading = false,
        startupPhase = DashboardStartupPhase.Ready,
        opportunityScoringModel = OpportunityScoringModel.AggressiveV3,
        opportunityRows = List(40) { index ->
            OpportunityListRow(
                symbol = "SYM$index.BA",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 15_000L,
                gapBps = 5_000,
                confidence = ConfidenceBand.High,
                isWatched = false,
                fundamentalsScore = 20,
                technicalScore = 20,
                forecastScore = 20,
                compositeScore = 34,
                compositeScoreBase = 34,
                coverageCount = 3,
            )
        },
    )
}
