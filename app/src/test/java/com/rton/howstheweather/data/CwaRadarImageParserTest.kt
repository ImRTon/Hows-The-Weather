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
        val weakCyan = parser.decodeDbz(0xff00ffff.toInt())
        val weakBlue = parser.decodeDbz(0xff0000ff.toInt())
        val green = parser.decodeDbz(0xff00ff00.toInt())
        val yellow = parser.decodeDbz(0xffffff00.toInt())
        val red = parser.decodeDbz(0xffff0000.toInt())
        val magenta = parser.decodeDbz(0xffea00cc.toInt())
        val violet = parser.decodeDbz(0xff9600ff.toInt())

        assertEquals(0f, weakCyan, .001f)
        assertEquals(14f, weakBlue, .001f)
        assertEquals(15f, green, .001f)
        assertTrue(weakCyan < weakBlue)
        assertTrue(weakBlue < green)
        assertTrue(green < yellow)
        assertTrue(yellow < red)
        assertTrue(red < magenta)
        assertEquals(59f, magenta, .001f)
        assertEquals(65f, violet, .001f)
    }

    @Test fun `block downsampling preserves sub-block echo via coverage-weighted mean`() {
        val metadata = CwaRadarImageMetadata(
            bounds = com.rton.howstheweather.domain.GeoBounds(20.0, 120.0, 24.0, 124.0),
            observedAt = java.time.Instant.parse("2026-07-13T12:50:00Z"),
            productUrl = "https://example.test/O-A0058-005.png",
        )
        // 8x8 image downsampled by sampleSize=4 -> 2x2 grid.
        // Top-left 4x4 block has 4 pixels of 20 dBZ (0, 200, 0) and 12 dry pixels:
        // coverage = 4/16 = 0.25, sqrt(coverage) = 0.5 -> 20 * 0.5 = 10 dBZ (instead of 0 from point-sampling).
        val pixels = IntArray(8 * 8)
        val dbz20 = 0xff00c800.toInt()
        pixels[1 * 8 + 1] = dbz20
        pixels[1 * 8 + 2] = dbz20
        pixels[2 * 8 + 1] = dbz20
        pixels[2 * 8 + 2] = dbz20

        val grid = parser.toGrid(metadata, 8, 8, pixels, "O-A0058-005", sampleSize = 4)

        assertEquals(2, grid.width)
        assertEquals(2, grid.height)
        assertEquals(10f, grid.valueAt(0, 0), 0.01f)
        assertEquals(0f, grid.valueAt(1, 0), 0.01f)
    }
}
