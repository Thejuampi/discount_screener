package com.discountscreener.android.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The keeper is the only thing that decides when the process is held up. A test that only checked
 * the service would never see the decision, so each test here reads the calls the keeper made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ForegroundLoadKeeperTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = mutableListOf<String>()

    @Test
    fun a_load_that_starts_holds_the_process_up() = runTest(UnconfinedTestDispatcher()) {
        var loadInFlight = MutableStateFlow(false)
        keep(loadInFlight)

        loadInFlight.value = true

        assertEquals(listOf("stop", "start"), calls)
    }

    @Test
    fun a_load_that_finishes_lets_the_process_go() = runTest(UnconfinedTestDispatcher()) {
        var loadInFlight = MutableStateFlow(true)
        keep(loadInFlight)

        loadInFlight.value = false

        assertEquals(listOf("start", "stop"), calls)
    }

    /**
     * The refresh half and the enrichment half of one load both report, and the flow can repeat a
     * reading. Stopping the service between them would let the platform freeze the process in the
     * middle of the work it was started for.
     */
    @Test
    fun a_load_reported_twice_is_still_one_hold() = runTest(UnconfinedTestDispatcher()) {
        var loadInFlight = MutableStateFlow(false)
        keep(loadInFlight)

        loadInFlight.value = true
        loadInFlight.value = true

        assertEquals(listOf("stop", "start"), calls)
    }

    /** The collection never ends on its own, so it lives in the scope `runTest` cancels. */
    private fun TestScope.keep(loadInFlight: MutableStateFlow<Boolean>) {
        ForegroundLoadKeeper(
            context = context,
            start = { calls.add("start") },
            stop = { calls.add("stop") },
        ).keep(loadInFlight, backgroundScope)
    }
}
