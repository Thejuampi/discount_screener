package com.discountscreener.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val splashMinimumDurationMillis = 4_000L

private const val startupPreferencesName = "discount_screener_startup"
private const val disclaimerAcceptedKey = "disclaimer_accepted_v1"

private const val splashGithubPage = "https://github.com/Thejuampi"

private const val disclaimerBody = """
This project is provided for informational and educational purposes only. It is not trading advice, investment advice, financial advice, legal advice, tax advice, accounting advice, or a recommendation to buy, sell, or hold any security.

Market data, analyst targets, ratings, and derived signals may be delayed, incomplete, inaccurate, or unavailable. You are solely responsible for any decisions or actions you take based on this software or its output. Always verify information independently and consult a qualified professional where appropriate.
"""

internal enum class StartupStage {
    Splash,
    Disclaimer,
    Content,
}

internal fun startupStage(
    loading: Boolean,
    splashMinimumElapsed: Boolean,
    disclaimerAccepted: Boolean,
): StartupStage = when {
    !splashMinimumElapsed || loading -> StartupStage.Splash
    !disclaimerAccepted -> StartupStage.Disclaimer
    else -> StartupStage.Content
}

internal fun readDisclaimerAccepted(context: Context): Boolean =
    context
        .getSharedPreferences(startupPreferencesName, Context.MODE_PRIVATE)
        .getBoolean(disclaimerAcceptedKey, false)

internal fun writeDisclaimerAccepted(context: Context, accepted: Boolean) {
    context
        .getSharedPreferences(startupPreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(disclaimerAcceptedKey, accepted)
        .apply()
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun StartupSplashScreen(loading: Boolean) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "\uD83D\uDC6E",
                fontSize = 56.sp,
            )
            Text(
                text = "Discount Screener",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Copyright Juan Lescano",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = splashGithubPage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (loading) "Loading local database..." else "Starting app...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
internal fun DisclaimerGate(
    onAccept: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Disclaimer",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = disclaimerBody.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onAccept,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Accept and continue")
                        }
                        OutlinedButton(
                            onClick = onClose,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Close app")
                        }
                    }
                }
            }
        }
    }
}
