package com.discountscreener.android.ui.dashboard

import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardListsTest {
    @Test
    fun freshness_time_label_formats_restored_and_live_rows() {
        assertEquals(
            "saved 2m ago",
            freshnessTimeLabel(
                freshness = RowFreshness.Restored,
                freshnessAsOfEpochSeconds = 100L,
                nowEpochSeconds = 220L,
            ),
        )
        assertEquals(
            "45m ago",
            freshnessTimeLabel(
                freshness = RowFreshness.Updated,
                freshnessAsOfEpochSeconds = 100L,
                nowEpochSeconds = 2_800L,
            ),
        )
    }

    @Test
    fun freshness_time_label_hides_unknown_timestamps() {
        assertNull(freshnessTimeLabel(RowFreshness.Updated, freshnessAsOfEpochSeconds = null))
    }

    @Test
    fun decision_state_labels_match_user_facing_triage_copy() {
        assertEquals(
            mapOf(
                RowDecisionState.Act to "Act",
                RowDecisionState.Watch to "Watch",
                RowDecisionState.Avoid to "Avoid",
            ),
            RowDecisionState.entries.associateWith { decisionStateLabel(it) },
        )
    }
}
