package com.discountscreener.android.data.capture

import com.discountscreener.android.domain.logging.NoOpAppLogger
import com.discountscreener.core.model.ProjectionProfileFacts
import com.discountscreener.core.model.ScreenDataProjectionRequest
import com.discountscreener.core.replay.ScreenReplay
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreenCaptureSinkTest {

    private val directory: File = Files.createTempDirectory("screen-capture").toFile()
    private val sink = ScreenCaptureSink(directory = directory, logger = NoOpAppLogger)
    private val request = ScreenDataProjectionRequest(
        profile = ProjectionProfileFacts(currentProfile = "sp500"),
        trackedSymbols = listOf("ACME"),
    )

    @Test
    fun an_unarmed_sink_writes_nothing() {
        sink.capture(request)
        assertFalse(File(directory, ScreenCaptureSink.REQUEST_FILE_NAME).exists())
    }

    @Test
    fun an_armed_sink_writes_the_request_it_was_given() {
        arm()
        sink.capture(request)
        assertEquals(request, ScreenReplay.decodeRequest(File(directory, ScreenCaptureSink.REQUEST_FILE_NAME).readText()))
    }

    /** One arm, one file. Otherwise every later snapshot pays for a capture nobody asked for. */
    @Test
    fun a_capture_disarms_the_sink() {
        arm()
        sink.capture(request)
        assertFalse(File(directory, ScreenCaptureSink.ARM_FILE_NAME).exists())
    }

    @Test
    fun a_second_snapshot_leaves_the_first_capture_alone() {
        arm()
        sink.capture(request)
        var captured = File(directory, ScreenCaptureSink.REQUEST_FILE_NAME).readText()
        sink.capture(request.copy(trackedSymbols = listOf("OTHER")))
        assertEquals(captured, File(directory, ScreenCaptureSink.REQUEST_FILE_NAME).readText())
    }

    private fun arm() {
        check(File(directory, ScreenCaptureSink.ARM_FILE_NAME).createNewFile()) { "The arm file was already there." }
    }
}
