package com.rton.howstheweather.data

import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CwaResponseCacheTest {
    private val directory: File = Files.createTempDirectory("cwa-response-cache").toFile()
    private var now = Instant.parse("2026-10-21T04:00:00Z")
    private val cache = CwaResponseCache(directory) { now }
    private var downloads = 0

    @After fun cleanUp() {
        directory.deleteRecursively()
    }

    private suspend fun load(body: String = "fresh", maxAge: Duration = Duration.ofMinutes(30)): String =
        cache.getOrFetch("W-C0034-005", maxAge, fetch = { downloads++; body }, parse = { it.uppercase() })

    @Test fun `fresh entry is served without downloading`() = runTest {
        assertEquals("FRESH", load())
        now = now.plus(Duration.ofMinutes(29))

        assertEquals("FRESH", load(body = "newer"))
        assertEquals(1, downloads)
    }

    @Test fun `expired entry is downloaded again`() = runTest {
        load()
        now = now.plus(Duration.ofMinutes(31))

        assertEquals("NEWER", load(body = "newer"))
        assertEquals(2, downloads)
    }

    @Test fun `clock moving backwards invalidates the entry`() = runTest {
        load()
        now = now.minus(Duration.ofMinutes(5))

        load(body = "again")
        assertEquals(2, downloads)
    }

    @Test fun `bodies that fail to parse are never stored`() = runTest {
        runCatching {
            cache.getOrFetch("bad", Duration.ofHours(1), fetch = { "oops" }, parse = { error("schema") })
        }

        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("bad") && it.name.endsWith(".gz") })
    }

    @Test fun `corrupt entry is discarded and refetched`() = runTest {
        load()
        directory.listFiles().orEmpty().single { it.name.endsWith(".json.gz") }.writeText("not gzip")

        assertEquals("REPAIRED", load(body = "repaired"))
        assertEquals(2, downloads)
    }

    @Test fun `null directory disables caching`() = runTest {
        val uncached = CwaResponseCache(null) { now }
        repeat(2) { uncached.getOrFetch("x", Duration.ofHours(1), fetch = { downloads++; "a" }, parse = { it }) }

        assertEquals(2, downloads)
    }
}
