package com.discountscreener.android.ui.dashboard

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

class ScoreFactorPaletteTest {

    @Test
    fun light_inks_meet_aa_on_the_light_surfaces() {
        var palette = scoreFactorPalette(dark = false)
        assertEquals(emptyList<String>(), contrastFailures(palette, LIGHT_SURFACES))
    }

    @Test
    fun dark_inks_meet_aa_on_the_dark_surfaces() {
        var palette = scoreFactorPalette(dark = true)
        assertEquals(emptyList<String>(), contrastFailures(palette, DARK_SURFACES))
    }

    private fun contrastFailures(palette: ScoreFactorPalette, surfaces: List<Pair<String, Color>>): List<String> {
        var inks = listOf(
            "fundamentals" to palette.fundamentals,
            "technicals" to palette.technicals,
            "forecast" to palette.forecast,
            "market" to palette.market,
            "positive" to palette.positive,
            "negative" to palette.negative,
        )
        return inks.flatMap { (name, ink) ->
            surfaces.mapNotNull { (surfaceName, surface) ->
                var ratio = contrastRatio(ink, surface)
                if (ratio >= AA_CONTRAST) null else "$name on $surfaceName is $ratio"
            }
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        var light = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        var dark = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (light + 0.05) / (dark + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            var c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private companion object {
        const val AA_CONTRAST = 4.5
        val LIGHT_SURFACES = listOf(
            "background" to Color(0xFFF7F4FA),
            "surface" to Color(0xFFFDF9FF),
            "surfaceVariant" to Color(0xFFE9E2F0),
        )
        val DARK_SURFACES = listOf(
            "background" to Color(0xFF002E29),
            "surface" to Color(0xFF014840),
            "surfaceVariant" to Color(0xFF105F57),
        )
    }
}
