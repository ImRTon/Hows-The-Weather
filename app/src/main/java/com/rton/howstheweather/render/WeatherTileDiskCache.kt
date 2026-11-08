package com.rton.howstheweather.render

import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Best-effort render cache, accessed only from tile worker threads. */
internal class WeatherTileDiskCache(private val directory: File) {
    fun read(key: String): ByteArray? = synchronized(lock) {
        val file = fileFor(key)
        try {
            if (!file.isFile || file.length() !in 8L..MAX_ENTRY_BYTES) return@synchronized null
            val bytes = DataInputStream(file.inputStream().buffered()).use { input ->
                val checksum = input.readLong()
                val payload = input.readBytes()
                if (CRC32().apply { update(payload) }.value != checksum) {
                    file.delete()
                    return@synchronized null
                }
                payload
            }
            file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (_: java.io.IOException) { null }
    }

    fun write(key: String, bytes: ByteArray) = synchronized(lock) {
        if (bytes.size + 8L > MAX_ENTRY_BYTES) return@synchronized
        val file = fileFor(key)
        val temporary = File(directory, file.name + ".tmp")
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized
            DataOutputStream(temporary.outputStream().buffered()).use {
                it.writeLong(CRC32().apply { update(bytes) }.value)
                it.write(bytes)
            }
            if (!temporary.renameTo(file)) return@synchronized
            val entries = directory.listFiles { entry -> entry.extension == "tile" }
                ?.sortedBy { it.lastModified() }.orEmpty()
            var size = entries.sumOf { it.length() }
            var count = entries.size
            for (entry in entries) {
                if (size <= MAX_BYTES && count <= MAX_ENTRIES) break
                val length = entry.length()
                if (entry.delete()) { size -= length; count-- }
            }
        } catch (_: java.io.IOException) {
            // Storage pressure must not prevent rendering live weather.
        } finally {
            temporary.delete()
        }
    }

    private fun fileFor(key: String): File = File(directory,
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".tile")

    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 1024L * 1024
        private const val MAX_ENTRIES = 512
    }
}
