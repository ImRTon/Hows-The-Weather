package com.rton.howstheweather.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/** Parses the public frame inventory used by CWA's own radar and satellite animations. */
class CwaAnimationFrameParser {
    fun parse(
        script: String,
        filePrefix: String,
        baseUrl: String,
    ): List<CwaHistoryRecord> {
        val records = mutableListOf<CwaHistoryRecord>()
        forEachFrame(script, filePrefix, baseUrl, {}) { records += it }
        return records.distinctBy { it.observedAt }.sortedBy { it.observedAt }
    }

    /** Retain only the latest requested frame, not a list of historical records. */
    fun parseLatest(
        script: String,
        filePrefix: String,
        baseUrl: String,
        checkCancelled: () -> Unit = {},
    ): CwaHistoryRecord? {
        var latest: CwaHistoryRecord? = null
        forEachFrame(script, filePrefix, baseUrl, checkCancelled) { record ->
            if (latest == null || record.observedAt!! > latest!!.observedAt!!) latest = record
        }
        return latest
    }

    private fun forEachFrame(
        script: String,
        filePrefix: String,
        baseUrl: String,
        checkCancelled: () -> Unit,
        accept: (CwaHistoryRecord) -> Unit,
    ) {
        // Kotlin Regex.findAll creates/resets a matcher for each next match.
        // Android's ICU backend repeatedly copies the entire ~1.7 MB inventory.
        // Reuse ONE matcher and its input for the complete scan instead.
        val matcher = FRAME.matcher(script)
        while (matcher.find()) {
            checkCancelled()
            val path = matcher.group(1) ?: continue
            val fileName = path.substringAfterLast('/')
            if (!fileName.startsWith(filePrefix) || path.contains("..")) continue
            val observedAt = runCatching {
                LocalDateTime.parse(matcher.group(2), DISPLAY_TIME)
                    .atZone(TAIPEI_ZONE)
                    .toInstant()
            }.getOrNull() ?: continue
            accept(CwaHistoryRecord(
                url = baseUrl.trimEnd('/') + "/" + path.trimStart('/'),
                observedAt = observedAt,
            ))
        }
    }

    private companion object {
        val FRAME: Pattern = Pattern.compile(
            """[\"']img[\"']\s*:\s*[\"']([^\"']+)[\"']\s*,\s*[\"']text[\"']\s*:\s*[\"'](\d{4}/\d{2}/\d{2}\s+\d{2}:\d{2})[\"']""",
        )
        val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")
    }
}
