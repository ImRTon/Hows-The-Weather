package com.rton.howstheweather.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

data class CwaHistoryRecord(val url: String, val observedAt: Instant?)

/** Tolerant reader for the lightly documented CWA short-term-history response. */
class CwaHistoryMetadataParser {
    fun parse(json: String): List<CwaHistoryRecord> {
        val records = mutableListOf<CwaHistoryRecord>()
        visit(JSONObject(json), records)
        return records
            .filter { it.url.startsWith("https://") }
            .distinctBy(CwaHistoryRecord::url)
            .sortedBy { it.observedAt ?: Instant.EPOCH }
    }

    private fun visit(value: Any?, records: MutableList<CwaHistoryRecord>) {
        when (value) {
            is JSONObject -> {
                val url = URL_KEYS.firstNotNullOfOrNull { key ->
                    value.optString(key).takeIf { it.startsWith("https://") }
                }
                if (url != null) records += CwaHistoryRecord(url, timestamp(value))
                value.keys().forEach { visit(value.opt(it), records) }
            }
            is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), records)
        }
    }

    private fun timestamp(value: JSONObject): Instant? = TIME_KEYS.firstNotNullOfOrNull { key ->
        value.optString(key).takeIf(String::isNotBlank)?.let(::parseInstantOrNull)
    }

    private fun parseInstantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

    private companion object {
        val URL_KEYS = listOf("url", "URL", "downloadUrl", "downloadURL", "fileUrl", "fileURL")
        val TIME_KEYS = listOf("DateTime", "Datetime", "dateTime", "dataTime", "time", "lastModified")
    }
}
