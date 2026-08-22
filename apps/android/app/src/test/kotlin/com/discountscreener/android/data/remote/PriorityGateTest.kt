package com.discountscreener.android.data.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate is a semaphore with a front of the line, and a semaphore that loses a permit to a cancel
 * closes for good. Each test drives one hand-over under a scheduler that runs nothing until told.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PriorityGateTest {

    @Test
    fun an_urgent_caller_is_served_before_the_ordinary_callers_already_in_line() = runTest {
        var gate = PriorityGate(permits = 1)
        var served = mutableListOf<String>()
        gate.acquire()
        launch { gate.acquire(); served += "ordinary" }
        runCurrent()
        launch { gate.acquire(urgent = true); served += "urgent" }
        runCurrent()

        gate.release()
        runCurrent()
        gate.release()
        runCurrent()

        assertEquals(listOf("urgent", "ordinary"), served)
    }

    @Test
    fun a_cancelled_waiter_gives_its_place_in_line_back() = runTest {
        var gate = PriorityGate(permits = 1)
        gate.acquire()
        var waiter = launch { gate.acquire() }
        runCurrent()

        waiter.cancel()
        runCurrent()
        gate.release()

        assertTrue("the permit went to a waiter that had left", gate.tryAcquire())
    }

    /** The permit was handed to the waiter in the same moment it was cancelled; it must not be lost. */
    @Test
    fun a_waiter_cancelled_as_its_permit_is_handed_over_passes_the_permit_on() = runTest {
        var gate = PriorityGate(permits = 1)
        gate.acquire()
        var waiter = launch { gate.acquire() }
        runCurrent()

        gate.release()
        waiter.cancel()
        runCurrent()

        assertTrue("the permit died with the cancelled waiter", gate.tryAcquire())
    }
}
