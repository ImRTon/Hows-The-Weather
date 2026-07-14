package com.rton.howstheweather.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CwaQuantitativeForecastImageParserTest {
    private val parser = CwaQuantitativeForecastImageParser()

    @Test
    fun `metadata keeps official publication time and product url`() {
        val metadata = parser.parseMetadata(
            """
            {
              "cwaopendata": {
                "sent": "2026-07-14T11:30:00+08:00",
                "dataset": {
                  "resource": {
                    "productURL": "https://example.test/F-C0035-015.png"
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(Instant.parse("2026-07-14T03:30:00Z"), metadata.issuedAt)
        assertEquals("https://example.test/F-C0035-015.png", metadata.productUrl)
    }

    @Test
    fun `only official rainfall colors become amounts`() {
        assertEquals(.5f, parser.decodeRain(argb(194, 194, 194)))
        assertEquals(20f, parser.decodeRain(argb(57, 255, 3)))
        assertEquals(200f, parser.decodeRain(argb(251, 0, 255)))
        assertNull(parser.decodeRain(argb(237, 249, 254)))
        assertNull(parser.decodeRain(argb(0, 0, 0)))
    }

    @Test
    fun `multi point calibration follows Taiwan chart projection`() {
        val north = parser.sourcePixel(longitude = 121.536, latitude = 25.298)
        val center = parser.sourcePixel(longitude = 121.0, latitude = 23.6)
        val south = parser.sourcePixel(longitude = 120.85, latitude = 21.9)

        assertEquals(934.2, north.first, 1.0)
        assertEquals(202.7, north.second, 1.0)
        assertEquals(739.8, center.first, 1.0)
        assertEquals(812.0, center.second, 1.0)
        assertEquals(688.9, south.first, 1.0)
        assertEquals(1414.1, south.second, 1.0)
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}
