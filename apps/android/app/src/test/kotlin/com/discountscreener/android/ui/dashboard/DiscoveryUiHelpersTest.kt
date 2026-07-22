package com.discountscreener.android.ui.dashboard

import com.discountscreener.android.data.remote.isUsableCompanyName
import com.discountscreener.android.domain.model.DiscoveryJobKind
import com.discountscreener.android.domain.model.DiscoveryJobRecord
import com.discountscreener.android.domain.model.DiscoveryJobStatus
import com.discountscreener.android.domain.model.parseDiscoveryMembershipDelta
import com.discountscreener.android.presentation.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryUiHelpersTest {
    @Test
    fun relative_time_buckets() {
        val now = 1_700_000_000L
        assertEquals("just now", formatRelativeTime(now - 10, now))
        assertEquals("5m ago", formatRelativeTime(now - 5 * 60, now))
        assertEquals("3h ago", formatRelativeTime(now - 3 * 3_600, now))
        assertEquals("2d ago", formatRelativeTime(now - 2 * 86_400, now))
    }

    @Test
    fun price_and_bps_formatting() {
        assertEquals("$12.34", formatDiscoveryPrice(1_234L))
        assertEquals("+12.5%", formatBpsPercent(1_250))
        assertEquals("-3.0%", formatBpsPercent(-300))
    }

    @Test
    fun job_progress_label() {
        val job = DiscoveryJobRecord(
            jobId = 1,
            kind = DiscoveryJobKind.Refresh,
            status = DiscoveryJobStatus.Running,
            startedAtEpochSeconds = 1,
            finishedAtEpochSeconds = null,
            totalSymbols = 7090,
            completedSymbols = 1204,
            errorSummary = null,
        )
        assertEquals("Scoring 1,204 of 7,090", discoveryJobProgressLabel(job))
    }

    @Test
    fun tab_label_uses_result_count_and_busy_percent() {
        val idle = DashboardUiState(discoveryResultCount = 142)
        assertEquals("Discovery 142", discoveryTabLabel(idle))

        val busy = DashboardUiState(
            discoveryBusy = true,
            discoveryJob = DiscoveryJobRecord(
                jobId = 2,
                kind = DiscoveryJobKind.Refresh,
                status = DiscoveryJobStatus.Running,
                startedAtEpochSeconds = 1,
                finishedAtEpochSeconds = null,
                totalSymbols = 100,
                completedSymbols = 25,
                errorSummary = null,
            ),
        )
        assertEquals("Discovery 25%", discoveryTabLabel(busy))
    }

    @Test
    fun membership_delta_parser() {
        assertEquals(
            120 to 45,
            parseDiscoveryMembershipDelta("source=Remote;detail;removed=45;added=120;kept=7000"),
        )
        assertNull(parseDiscoveryMembershipDelta(null))
    }

    @Test
    fun usable_company_name_rejects_literal_null() {
        assertFalse(isUsableCompanyName(null))
        assertFalse(isUsableCompanyName("null"))
        assertFalse(isUsableCompanyName("  NULL  "))
        assertTrue(isUsableCompanyName("ACCO Brands Corporation"))
    }
}
