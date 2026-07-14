package com.rton.howstheweather.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CwaHistoryMetadataParserTest {
    @Test fun `finds nested historical download urls and timestamps`() {
        val records = CwaHistoryMetadataParser().parse(
            """{
              "success": true,
              "records": {"metadata": [
                {"url":"https://example.test/older.json","DateTime":"2026-07-13T20:20:00+08:00"},
                {"downloadURL":"https://example.test/newer.png","dataTime":"2026-07-13T12:30:00Z"}
              ]}
            }""",
        )

        assertEquals(2, records.size)
        assertEquals("https://example.test/older.json", records[0].url)
        assertEquals("2026-07-13T12:20:00Z", records[0].observedAt.toString())
        assertEquals("https://example.test/newer.png", records[1].url)
    }
}
