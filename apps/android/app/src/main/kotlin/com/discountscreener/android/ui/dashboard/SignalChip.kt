package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.discountscreener.android.presentation.dashboard.QuantLensChipUi
import com.discountscreener.android.presentation.dashboard.QuantLensQualifier

/**
 * The one home for how a lens signal looks: its marker, its colour, and the pill it sits in.
 *
 * Three surfaces render these — the dense list row, the Snapshot strip and the Lens tab — and until
 * this file existed the palette was copy-pasted between two of them and the third had no colour at
 * all. A signal that reads green on one screen and plain on another is worse than one that is plain
 * everywhere, because only the first invites you to trust the difference.
 */

/**
 * `++`, `+`, nothing, `−`, `−−`.
 *
 * The neutral and unmeasured cases carry no marker on purpose. A `·` would be a third glyph to
 * learn, and it would make "the lens found nothing decisive" look like a reading of its own.
 * The minus is U+2212 rather than a hyphen so it sits at the same height as the plus.
 */
internal fun signalQualifierMark(qualifier: QuantLensQualifier): String = when (qualifier) {
    QuantLensQualifier.StrongPositive -> "++"
    QuantLensQualifier.Positive -> "+"
    QuantLensQualifier.Neutral -> ""
    QuantLensQualifier.Negative -> "−"
    QuantLensQualifier.StrongNegative -> "−−"
    QuantLensQualifier.Unknown -> ""
}

/** The marker leads, so a column of chips can be scanned down the left edge. */
internal fun signalChipLabel(chip: QuantLensChipUi): String {
    val mark = signalQualifierMark(chip.qualifier)
    return if (mark.isEmpty()) chip.label else "$mark ${chip.label}"
}

/**
 * The directional palette.
 *
 * Green for favourable, blue for neutral, amber for adverse and red for strongly adverse. The
 * emphatic positive shares green with the plain one — the second plus is what separates them —
 * while the negative side escalates in colour as well as marker, because the cost of missing an
 * adverse signal is not the cost of missing a favourable one.
 *
 * These are not [BullishChartColor] and [BearishChartColor]. Those are pure #00FF00 and #FF0000,
 * which are legible as a one-pixel line on a chart and very nearly illegible as small text on
 * either of this app's backgrounds.
 *
 * Null for [QuantLensQualifier.Unknown]: a lens that could not read has no colour of its own and
 * defers to the theme's outline. Keeping the palette out of the composition is what lets it be
 * asserted in a plain test — the alternative reads colours back out of a rendered tree, which
 * measures the harness at least as much as the app.
 */
internal fun signalChipContentColor(qualifier: QuantLensQualifier, dark: Boolean): Color? = when (qualifier) {
    QuantLensQualifier.StrongPositive,
    QuantLensQualifier.Positive,
    -> if (dark) Color(0xFF7FE0A0) else Color(0xFF14713C)
    QuantLensQualifier.Neutral -> if (dark) Color(0xFF8FC7FF) else Color(0xFF1B5FA8)
    QuantLensQualifier.Negative -> if (dark) Color(0xFFFFC94D) else Color(0xFF8A6E00)
    QuantLensQualifier.StrongNegative -> if (dark) Color(0xFFFF9A90) else Color(0xFFB3261E)
    QuantLensQualifier.Unknown -> null
}

/** Content colour and its background, as a pair. */
@Composable
internal fun signalChipColors(qualifier: QuantLensQualifier): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    val content = signalChipContentColor(qualifier, dark) ?: MaterialTheme.colorScheme.outline
    return content to content.copy(alpha = if (dark) 0.20f else 0.14f)
}

/**
 * One pill. [onClick] is optional because the dense list row's chips are not targets — the row
 * itself is — while the Snapshot strip's chips jump to the Lens tab that explains them.
 */
@Composable
internal fun SignalChip(chip: QuantLensChipUi, onClick: (() -> Unit)? = null) {
    val colors = signalChipColors(chip.qualifier)
    val shape = RoundedCornerShape(999.dp)
    var modifier = Modifier
        .clip(shape)
        .background(colors.second)
    if (onClick != null) {
        modifier = modifier.clickable(onClick = onClick)
    }
    Text(
        text = signalChipLabel(chip),
        color = colors.first,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
