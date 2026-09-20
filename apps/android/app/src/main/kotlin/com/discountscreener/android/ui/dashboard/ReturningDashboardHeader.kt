package com.discountscreener.android.ui.dashboard

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.draw.clipToBounds
import kotlin.math.abs
import kotlin.math.sign

internal const val RETURNING_HEADER_TAG = "returningDashboardHeader"
internal const val DASHBOARD_CONTENT_TAG = "dashboardContent"

/** Pure scroll state for the measured dashboard header. */
internal class ReturningDashboardHeaderState(
    private val thresholdPx: Float = 8f,
) {
    var fullHeightPx by mutableIntStateOf(0)
        private set

    var hiddenHeightPx by mutableFloatStateOf(0f)
        private set

    var pinned by mutableStateOf(false)
        private set

    private var directionDistancePx = 0f
    private var lastDirection = 0
    private var boundaryRecoveryUsed = false

    val visibleHeightPx: Float
        get() = (fullHeightPx - hiddenHeightPx).coerceAtLeast(0f)

    val fullyHidden: Boolean
        get() = fullHeightPx > 0 && hiddenHeightPx >= fullHeightPx

    fun setMeasuredHeight(heightPx: Int) {
        fullHeightPx = heightPx.coerceAtLeast(0)
        hiddenHeightPx = hiddenHeightPx.coerceIn(0f, fullHeightPx.toFloat())
    }

    fun updatePinned(value: Boolean) {
        pinned = value
        if (value) {
            hiddenHeightPx = 0f
            directionDistancePx = 0f
            lastDirection = 0
            boundaryRecoveryUsed = false
        }
    }

    fun reset() {
        hiddenHeightPx = 0f
        directionDistancePx = 0f
        lastDirection = 0
        boundaryRecoveryUsed = false
    }

    /**
     * Applies one vertical user gesture. Negative deltas move content up and hide the header.
     * Positive deltas move content down and reveal the complete header after the threshold.
     */
    fun onUserScroll(
        deltaY: Float,
        deltaX: Float = 0f,
        childConsumed: Boolean,
        userInput: Boolean = true,
    ) {
        if (!userInput || pinned || fullHeightPx == 0) return
        if (abs(deltaY) <= abs(deltaX) || deltaY == 0f) return
        if (boundaryRecoveryUsed && childConsumed && deltaY < 0f) return

        if (!childConsumed) {
            if (deltaY < 0f) return
            if (boundaryRecoveryUsed) return
            val boundaryDirection = sign(deltaY).toInt()
            if (boundaryDirection != lastDirection) {
                directionDistancePx = 0f
                lastDirection = boundaryDirection
            }
            directionDistancePx += abs(deltaY)
            if (directionDistancePx < thresholdPx) return
            hiddenHeightPx = 0f
            boundaryRecoveryUsed = true
            return
        }

        val direction = sign(deltaY).toInt()
        if (direction != lastDirection) {
            directionDistancePx = 0f
            lastDirection = direction
        }
        val previousDistance = directionDistancePx
        directionDistancePx += abs(deltaY)

        if (direction > 0) {
            if (directionDistancePx >= thresholdPx) {
                val wasHidden = hiddenHeightPx > 0f
                hiddenHeightPx = 0f
                if (wasHidden) boundaryRecoveryUsed = true
            }
            return
        }

        val previousBeyondThreshold = (previousDistance - thresholdPx).coerceAtLeast(0f)
        val currentBeyondThreshold = (directionDistancePx - thresholdPx).coerceAtLeast(0f)
        hiddenHeightPx = (hiddenHeightPx + currentBeyondThreshold - previousBeyondThreshold)
            .coerceIn(0f, fullHeightPx.toFloat())
    }

    fun endGesture() {
        directionDistancePx = 0f
        lastDirection = 0
        boundaryRecoveryUsed = false
    }

    fun nestedScrollConnection(): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            val childConsumed = abs(consumed.y) > 0.01f
            val deltaY = if (childConsumed) consumed.y else available.y
            onUserScroll(
                deltaY = deltaY,
                deltaX = if (childConsumed) consumed.x else available.x,
                childConsumed = childConsumed,
            )
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            endGesture()
            return Velocity.Zero
        }
    }
}

@Composable
internal fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember(manager) {
        mutableStateOf(manager?.isTouchExplorationEnabled == true)
    }

    LaunchedEffect(manager) {
        enabled = manager?.isTouchExplorationEnabled == true
    }

    DisposableEffect(manager) {
        if (manager == null) {
            onDispose { }
        } else {
            val listener = object : AccessibilityManager.TouchExplorationStateChangeListener {
                override fun onTouchExplorationStateChanged(isTouchExplorationEnabled: Boolean) {
                    enabled = isTouchExplorationEnabled
                }
            }
            manager.addTouchExplorationStateChangeListener(listener)
            onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
        }
    }
    return enabled
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ReturningDashboardHeader(
    state: ReturningDashboardHeaderState,
    pinned: Boolean,
    resetKey: Any?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LaunchedEffect(resetKey) { state.reset() }
    LaunchedEffect(pinned) { state.updatePinned(pinned) }
    val internalScrollState = rememberScrollState()

    Layout(
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(internalScrollState),
                content = content,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .testTag(RETURNING_HEADER_TAG)
            .semantics(mergeDescendants = state.fullyHidden) {
                if (state.fullyHidden) invisibleToUser()
            },
    ) { measurables, constraints ->
        val child = measurables.single().measure(constraints.copy(minHeight = 0))
        if (state.fullHeightPx != child.height) state.setMeasuredHeight(child.height)
        val visibleHeight = state.visibleHeightPx.toInt().coerceIn(0, child.height)
        layout(child.width, visibleHeight) {
            child.place(0, -state.hiddenHeightPx.toInt())
        }
    }
}
