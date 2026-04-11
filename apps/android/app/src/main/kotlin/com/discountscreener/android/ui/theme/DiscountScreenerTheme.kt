package com.discountscreener.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6F5BD3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2DBFF),
    onPrimaryContainer = Color(0xFF24165F),
    secondary = Color(0xFF6A627F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E0F4),
    onSecondaryContainer = Color(0xFF241E35),
    tertiary = Color(0xFFC67A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB2),
    onTertiaryContainer = Color(0xFF3F2500),
    background = Color(0xFFF7F4FA),
    onBackground = Color(0xFF17141F),
    surface = Color(0xFFFDF9FF),
    onSurface = Color(0xFF17141F),
    surfaceVariant = Color(0xFFE9E2F0),
    onSurfaceVariant = Color(0xFF4C455C),
    outline = Color(0xFF7F778F),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3C8981),
    onPrimary = Color(0xFF002E29),
    primaryContainer = Color(0xFF105F57),
    onPrimaryContainer = Color(0xFFE7F4F2),
    secondary = Color(0xFF22756C),
    onSecondary = Color(0xFFEAF3F1),
    secondaryContainer = Color(0xFF014840),
    onSecondaryContainer = Color(0xFFD5ECE8),
    tertiary = Color(0xFFFFBF69),
    onTertiary = Color(0xFF4B2800),
    tertiaryContainer = Color(0xFF6B3C00),
    onTertiaryContainer = Color(0xFFFFE2BF),
    background = Color(0xFF002E29),
    onBackground = Color(0xFFE7F4F2),
    surface = Color(0xFF014840),
    onSurface = Color(0xFFE7F4F2),
    surfaceVariant = Color(0xFF105F57),
    onSurfaceVariant = Color(0xFFB6D6D1),
    outline = Color(0xFF22756C),
)

internal fun discountScreenerColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) DarkColorScheme else LightColorScheme

@Composable
fun DiscountScreenerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = discountScreenerColorScheme(darkTheme)
    val view = LocalView.current

    if (!view.isInEditMode) {
        val activity = view.context.findActivity()
        if (activity != null) {
            SideEffect {
                activity.window.statusBarColor = colorScheme.background.toArgb()
                activity.window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
