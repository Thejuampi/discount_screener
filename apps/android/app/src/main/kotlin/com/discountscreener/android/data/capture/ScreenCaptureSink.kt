package com.discountscreener.android.data.capture

import com.discountscreener.android.domain.logging.AppLogger
import com.discountscreener.core.model.ScreenDataProjectionRequest
import com.discountscreener.core.replay.ScreenReplay
import java.io.File

/**
 * Writes one screen input to disk, so the numbers can be reproduced later without the device.
 *
 * **Armed by a file, and disarmed by the capture.** A snapshot carries every symbol's detail,
 * candles and DCF, so writing one on every projection would cost the user real milliseconds for a
 * file nobody asked for. The capture happens only when [ARM_FILE_NAME] is present, and the arm file
 * is deleted as soon as the capture succeeds. So the cost in the normal path is one `exists()`
 * call, and an armed run yields exactly one file — the screen as it stood at that moment.
 *
 * Arm it, let the app draw the screen, then pull the file:
 * ```
 * adb shell touch /sdcard/Android/data/com.discountscreener.android/files/screen-capture/arm
 * adb pull /sdcard/Android/data/com.discountscreener.android/files/screen-capture/request.json
 * ./gradlew :core:replayScreen --args="--request=request.json"
 * ```
 *
 * This is the only step of the experiment loop that needs a device, and it is needed once per set
 * of inputs. Every model change after that is measured against the file.
 */
class ScreenCaptureSink(
    private val directory: File,
    private val logger: AppLogger,
) {

    fun capture(request: ScreenDataProjectionRequest) {
        var armFile = File(directory, ARM_FILE_NAME)
        if (!armFile.exists()) {
            return
        }
        var target = File(directory, REQUEST_FILE_NAME)
        runCatching {
            target.writeText(ScreenReplay.encodeRequest(request))
        }.onSuccess {
            armFile.delete()
            logger.info(TAG, "screen capture written to ${target.absolutePath} (${target.length()} bytes)")
        }.onFailure { error ->
            logger.error(TAG, "screen capture failed", error)
        }
    }

    companion object {
        const val ARM_FILE_NAME = "arm"
        const val REQUEST_FILE_NAME = "request.json"
        const val DIRECTORY_NAME = "screen-capture"
        private const val TAG = "ScreenCapture"
    }
}
