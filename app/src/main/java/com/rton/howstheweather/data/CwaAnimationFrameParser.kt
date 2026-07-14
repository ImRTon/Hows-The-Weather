package com.rton.howstheweather.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Parses the public frame inventory used by CWA's own radar and satellite animations. */
class CwaAnimationFrameParser {
    fun parse(
        script: String,
        filePrefix: String,
        baseUrl: String,
    ): List<CwaHistoryRecord> = FRAME.findAll(script)
        .mapNotNull { match ->
            val path = match.groupValues[1]
            val fileName = path.substringAfterLast('/')
            if (!fileName.startsWith(filePrefix) || path.contains("..")) return@mapNotNull null
            val observedAt = runCatching {
                LocalDateTime.parse(match.groupValues[2], DISPLAY_TIME)
                    .atZone(TAIPEI_ZONE)
                    .toInstant()
            }.getOrNull() ?: return@mapNotNull null
            CwaHistoryRecord(
                url = baseUrl.trimEnd('/') + "/" + path.trimStart('/'),
                observedAt = observedAt,
            )
        }
        .distinctBy { it.observedAt }
        .sortedBy { it.observedAt }
        .toList()

    private companion object {
        val FRAME = Regex(
            """[\"']img[\"']\s*:\s*[\"']([^\"']+)[\"']\s*,\s*[\"']text[\"']\s*:\s*[\"'](\d{4}/\d{2}/\d{2}\s+\d{2}:\d{2})[\"']""",
        )
        val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")
    }
}
