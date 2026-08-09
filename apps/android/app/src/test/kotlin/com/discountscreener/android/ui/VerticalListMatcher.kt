package com.discountscreener.android.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction

/**
 * The one vertically scrolling lazy list on screen.
 *
 * Both the dashboard and the ticker detail carry horizontally scrolling chip rows that answer
 * [hasScrollToIndexAction] just as a `LazyColumn` does, so a bare scroll matcher is ambiguous and
 * fails with "expected exactly 1 node but found 2". The scroll axis is what tells them apart.
 *
 * Scrolling this node is also the only way to reach a row a `LazyColumn` has not composed yet:
 * `performScrollTo` needs the node to already exist, which off-screen list items do not.
 */
internal fun verticalList(): SemanticsMatcher = hasScrollToIndexAction() and
    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
