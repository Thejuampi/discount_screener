package com.discountscreener.android.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SecEdgarCacheGcTest {
    @Test
    fun sweep_deletes_a_file_older_than_the_max_age() {
        var dir = Files.createTempDirectory("sec-gc-old").toFile()
        var stale = File(dir, "CIK0000320193.json")
        stale.writeText("{}")
        stale.setLastModified(1_000L)
        var deleted = SecEdgarCacheGc(
            cacheDir = dir,
            maxAgeMillis = 60_000L,
            clock = { 1_000L + 60_000L },
        ).sweep()
        assertEquals(1, deleted)
    }

    @Test
    fun sweep_keeps_a_file_still_inside_the_max_age() {
        var dir = Files.createTempDirectory("sec-gc-fresh").toFile()
        var fresh = File(dir, "CIK0000320193.json")
        fresh.writeText("{}")
        fresh.setLastModified(50_000L)
        var deleted = SecEdgarCacheGc(
            cacheDir = dir,
            maxAgeMillis = 60_000L,
            clock = { 50_000L + 10_000L },
        ).sweep()
        assertEquals(0, deleted)
    }

    @Test
    fun sweep_deletes_an_abandoned_part_file() {
        var dir = Files.createTempDirectory("sec-gc-part").toFile()
        var part = File(dir, "CIK0000320193.json.part")
        part.writeText("{}")
        part.setLastModified(1_000L)
        var deleted = SecEdgarCacheGc(
            cacheDir = dir,
            maxAgeMillis = 60_000L,
            partMaxAgeMillis = 1_000L,
            clock = { 1_000L + 5_000L },
        ).sweep()
        assertEquals(1, deleted)
    }

    @Test
    fun consume_deletes_the_facts_file_after_the_read() {
        var file = Files.createTempFile("sec-consume", ".json").toFile()
        file.writeText("{}")
        consumeCompanyFactsFile(file) { it.readText() }
        assertEquals(false, file.exists())
    }
}
