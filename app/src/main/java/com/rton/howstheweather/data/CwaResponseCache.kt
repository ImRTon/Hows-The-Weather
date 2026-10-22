package com.rton.howstheweather.data

import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Time-bounded disk cache for CWA REST products that carry no HTTP validators
 * (no ETag, Last-Modified, or Cache-Control). Freshness is measured from the
 * moment the body was downloaded, so a product is at most [maxAge] behind CWA.
 *
 * Only bodies that parse successfully are stored, and request URLs or
 * credentials are never written. A null [directory] disables caching.
 */
class CwaResponseCache(
    private val directory: File?,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun <T> getOrFetch(
        key: String,
        maxAge: Duration,
        fetch: suspend () -> String,
        parse: (String) -> T,
    ): T {
        val file = directory?.let { File(it, fileName(key)) }
        if (file != null) {
            readFresh(file, maxAge)?.let { cached ->
                try {
                    return withContext(Dispatchers.Default) { parse(cached) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    withContext(Dispatchers.IO) { file.delete() }
                }
            }
        }
        val body = fetch()
        val parsed = withContext(Dispatchers.Default) { parse(body) }
        if (file != null) {
            // A failed write only costs a future download.
            runCatching { write(file, body) }
        }
        return parsed
    }

    private suspend fun readFresh(file: File, maxAge: Duration): String? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching {
            GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { reader ->
                val savedAt = Instant.ofEpochMilli(checkNotNull(reader.readLine()).toLong())
                val age = Duration.between(savedAt, clock())
                // A clock moved backwards also invalidates the entry.
                if (age.isNegative || age > maxAge) return@use null
                reader.readText()
            }
        }.getOrElse {
            file.delete()
            null
        }
    }

    private suspend fun write(file: File, body: String) = withContext(Dispatchers.IO) {
        val parent = checkNotNull(file.parentFile)
        parent.mkdirs()
        val temporary = File.createTempFile(file.name, ".tmp", parent)
        try {
            GZIPOutputStream(temporary.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(clock().toEpochMilli().toString())
                writer.write("\n")
                writer.write(body)
            }
            if (!temporary.renameTo(file)) {
                file.delete()
                check(temporary.renameTo(file)) { "Unable to publish $file" }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun fileName(key: String): String =
        key.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("") + ".json.gz"
}
