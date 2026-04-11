package com.discountscreener.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTransitionTest {
    @Test
    fun switch_requested_enters_switching_phase_with_profile_status() {
        val feedback = reduceProfileTransition(
            ProfileTransitionEvent.SwitchRequested(profile = "merval", symbolCount = 23),
        )

        assertEquals(DashboardStartupPhase.SwitchingProfile, feedback.startupPhase)
        assertEquals(0, feedback.refreshCompletedSymbols)
        assertEquals(23, feedback.refreshTargetSymbols)
        assertEquals("Switching to MERVAL…", feedback.statusMessage)
    }

    @Test
    fun refresh_progress_keeps_profile_specific_progress_message() {
        val feedback = reduceProfileTransition(
            ProfileTransitionEvent.RefreshProgress(
                profile = "russell",
                completedSymbols = 7,
                totalSymbols = 1937,
            ),
        )

        assertEquals(DashboardStartupPhase.Refreshing, feedback.startupPhase)
        assertEquals(7, feedback.refreshCompletedSymbols)
        assertEquals(1937, feedback.refreshTargetSymbols)
        assertEquals("Refreshing RUSSELL 7/1937…", feedback.statusMessage)
    }
}
