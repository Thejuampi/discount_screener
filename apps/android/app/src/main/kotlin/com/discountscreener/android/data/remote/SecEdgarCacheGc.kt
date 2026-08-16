package com.discountscreener.android.data.remote

import java.io.File

/**
 * Delete leftover SEC working files. Facts are not a durable cache.
 * A file still being read is young, so the age gate leaves it alone.
 */
class SecEdgarCacheGc(
    private val cacheDir: File,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val partMaxAgeMillis: Long = DEFAULT_PART_MAX_AGE_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun sweep(): Int {
        var files = cacheDir.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            if (!file.isFile) continue
            var age = clock() - file.lastModified()
            var abandonedPart = file.name.endsWith(".part") && age >= partMaxAgeMillis
            var expired = age >= maxAgeMillis
            if ((abandonedPart || expired) && file.delete()) {
                deleted += 1
            }
        }
        return deleted
    }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 15L * 60L * 1000L
        const val DEFAULT_PART_MAX_AGE_MILLIS = 2L * 60L * 1000L
        const val SWEEP_INTERVAL_MILLIS = 5L * 60L * 1000L
    }
}

internal fun <T> consumeCompanyFactsFile(file: File, read: (File) -> T): T {
    try {
        return read(file)
    } finally {
        file.delete()
    }
}
