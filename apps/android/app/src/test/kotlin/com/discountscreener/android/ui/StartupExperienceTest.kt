package com.discountscreener.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartupExperienceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        writeDisclaimerAccepted(context, false)
    }

    @Test
    fun startup_stage_stays_on_splash_until_loading_finishes_and_timer_elapses() {
        assertEquals(
            StartupStage.Splash,
            startupStage(
                loading = true,
                splashMinimumElapsed = false,
                disclaimerAccepted = false,
            ),
        )
        assertEquals(
            StartupStage.Splash,
            startupStage(
                loading = true,
                splashMinimumElapsed = true,
                disclaimerAccepted = true,
            ),
        )
    }

    @Test
    fun startup_stage_shows_disclaimer_after_splash_when_not_accepted() {
        assertEquals(
            StartupStage.Disclaimer,
            startupStage(
                loading = false,
                splashMinimumElapsed = true,
                disclaimerAccepted = false,
            ),
        )
    }

    @Test
    fun startup_stage_enters_content_after_disclaimer_acceptance() {
        assertEquals(
            StartupStage.Content,
            startupStage(
                loading = false,
                splashMinimumElapsed = true,
                disclaimerAccepted = true,
            ),
        )
    }

    @Test
    fun disclaimer_acceptance_persists_in_preferences() {
        assertFalse(readDisclaimerAccepted(context))

        writeDisclaimerAccepted(context, true)

        assertTrue(readDisclaimerAccepted(context))
    }
}
