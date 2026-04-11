package com.discountscreener.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DiscountScreenerThemeTest {
    @Test
    fun light_scheme_uses_the_planned_60_30_10_palette() {
        val scheme = discountScreenerColorScheme(darkTheme = false)

        assertEquals(Color(0xFFF7F4FA), scheme.background)
        assertEquals(Color(0xFFE9E2F0), scheme.surfaceVariant)
        assertEquals(Color(0xFF6F5BD3), scheme.primary)
    }

    @Test
    fun dark_scheme_uses_the_requested_teal_palette() {
        val lightScheme = discountScreenerColorScheme(darkTheme = false)
        val darkScheme = discountScreenerColorScheme(darkTheme = true)

        assertEquals(Color(0xFF002E29), darkScheme.background)
        assertEquals(Color(0xFF105F57), darkScheme.surfaceVariant)
        assertEquals(Color(0xFF3C8981), darkScheme.primary)
        assertEquals(Color(0xFF22756C), darkScheme.secondary)
        assertNotEquals(lightScheme.background, darkScheme.background)
    }
}
