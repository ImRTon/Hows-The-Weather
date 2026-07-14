package com.rton.howstheweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CwaRadarImageParserTest {
    private val parser = CwaRadarImageParser()

    @Test fun `parses official transparent radar bounds and time`() {
        val metadata = parser.parseMetadata(
            """{"cwaopendata":{"dataset":{
              "datasetInfo":{"parameterSet":{
                "LongitudeRange":"115.00-126.50",
                "LatitudeRange":"17.75-29.25",
                "ImageDimension":"3600x3600"
              }},
              "resource":{"ProductURL":"https://example.test/O-A0058-005.png"},
              "DateTime":"2026-07-13T20:50:00+08:00"
            }}}""",
        )

        assertEquals(115.0, metadata.bounds.west, 0.0)
        assertEquals(126.5, metadata.bounds.east, 0.0)
        assertEquals(17.75, metadata.bounds.south, 0.0)
        assertEquals(29.25, metadata.bounds.north, 0.0)
        assertEquals("2026-07-13T12:50:00Z", metadata.observedAt.toString())
    }

    @Test fun `treats transparent no echo and non radar artwork as zero`() {
        assertEquals(0f, parser.decodeDbz(0x00000000), 0f)
        assertEquals(0f, parser.decodeDbz(0xff123456.toInt()), 0f)
        assertEquals(0f, parser.decodeDbz(0x88000000.toInt()), 0f)
    }

    @Test fun `decodes official palette monotonically`() {
        val weakBlue = parser.decodeDbz(0xff0000ff.toInt())
        val green = parser.decodeDbz(0xff00ff00.toInt())
        val yellow = parser.decodeDbz(0xffffff00.toInt())
        val red = parser.decodeDbz(0xffff0000.toInt())

        assertTrue(weakBlue < green)
        assertTrue(green < yellow)
        assertTrue(yellow < red)
        assertEquals(65f, parser.decodeDbz(0xffea00cc.toInt()), .001f)
    }
}
