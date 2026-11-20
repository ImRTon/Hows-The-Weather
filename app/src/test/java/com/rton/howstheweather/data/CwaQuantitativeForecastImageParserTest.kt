package com.rton.howstheweather.data

import com.rton.howstheweather.data.CwaQuantitativeForecastImageParser.ChartRegion
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `exact center color is not expanded by a stronger neighbor`() {
        val pixels = IntArray(9) { argb(0, 0, 0) }
        pixels[4] = argb(156, 252, 255) // 1 mm
        pixels[5] = argb(255, 0, 0) // 70 mm

        assertEquals(1f, parser.decodeSample(1, 1, 3, pixels, ChartRegion(0..2, 0..2)))
    }

    @Test
    fun `annotation pixel uses bounded neighboring average`() {
        val pixels = IntArray(9) { argb(0, 0, 0) }
        pixels[1] = argb(3, 200, 255) // 2 mm
        pixels[5] = argb(3, 99, 255) // 10 mm

        assertEquals(6f, parser.decodeSample(1, 1, 3, pixels, ChartRegion(0..2, 0..2)))
    }

    @Test
    fun `near-palette anti-aliased swatch pixel and multi-pixel border inpainting are recovered`() {
        // 1-LSB off from (3, 200, 255) = 2 mm in F-C0035 PNGs:
        assertEquals(2f, parser.decodeRain(argb(3, 201, 255)))
        // 2-px wide black county border surrounded by 10 mm at radius 2 is inpainted:
        val pixels = IntArray(5 * 5) { argb(0, 0, 0) }
        pixels[0] = argb(3, 99, 255)
        assertEquals(10f, parser.decodeSample(2, 2, 5, pixels, ChartRegion(0..4, 0..4)))
    }

    @Test
    fun `frame pixels cannot become rainfall or contribute to inpainting`() {
        val pixels = IntArray(25) { argb(255, 255, 255) }
        pixels[2] = argb(192, 192, 192) // Antialiased frame, within the 0.5 mm color tolerance.
        pixels[7] = argb(0, 0, 0)
        pixels[12] = argb(194, 194, 194) // Real 0.5 mm inside the map.
        val map = ChartRegion(1..3, 1..3)

        assertTrue(parser.decodeSample(2, 0, 5, pixels, map).isNaN())
        assertEquals(0.5f, parser.decodeSample(2, 1, 5, pixels, map))
        pixels[12] = argb(255, 255, 255)
        assertEquals(0f, parser.decodeSample(2, 1, 5, pixels, map))
    }

    @Test
    fun `the color bar cannot supply rain to nearby map pixels`() {
        val pixels = IntArray(25) { argb(255, 255, 255) }
        pixels[11] = argb(194, 194, 194) // 0.5 mm on the map.
        pixels[12] = argb(0, 0, 0)
        pixels[13] = argb(255, 206, 255) // 300 mm on the color bar.
        val map = ChartRegion(0..4, 0..4)
        val legend = ChartRegion(3..3, 1..3)

        assertTrue(parser.decodeSample(3, 2, 5, pixels, map, legend).isNaN())
        assertEquals(0.5f, parser.decodeSample(2, 2, 5, pixels, map, legend))
    }

    @Test
    fun `the native color bar is masked without removing the neighboring east grid cell`() {
        val width = 1245
        val height = 1500
        val pixels = IntArray(width * height) { argb(237, 249, 254) }
        val row = 763 * width
        pixels[row + 1156] = argb(3, 200, 255)
        pixels[row + 1162] = argb(255, 206, 255)
        val grid = parser.toGrid(
            metadata = CwaQuantitativeForecastMetadata(Instant.EPOCH, "https://example.test/chart.png"),
            imageWidth = width,
            imageHeight = height,
            argbPixels = pixels,
            sourceId = "F-C0035-015",
            endHour = 12,
        )

        assertTrue(grid.valueAt(grid.width - 1, 100).isNaN())
        assertEquals(2f, grid.valueAt(grid.width - 2, 100))
    }

    @Test
    fun `map coordinates land near surveyed coastal and inland controls on the chart`() {
        val fuguijiao = parser.sourcePixel(longitude = 121.53642, latitude = 25.29949)
        val taichungNantou = parser.sourcePixel(longitude = 120.82889, latitude = 24.07413)
        val eluanbi = parser.sourcePixel(longitude = 120.85069, latitude = 21.89700)

        // The expected pixels are traced boundaries in the native 1245 × 1500 forecast chart.
        assertEquals(933.0, fuguijiao.first, 1.5)
        assertEquals(202.0, fuguijiao.second, 1.5)
        assertEquals(679.0, taichungNantou.first, 1.5)
        assertEquals(642.0, taichungNantou.second, 1.5)
        assertEquals(685.0, eluanbi.first, 1.5)
        assertEquals(1421.0, eluanbi.second, 1.5)
    }

    @Test
    fun `display grid preserves its geographic extent and masks the chart frame`() {
        val grid = parser.toGrid(
            metadata = CwaQuantitativeForecastMetadata(
                issuedAt = Instant.parse("2026-07-14T03:30:00Z"),
                productUrl = "https://example.test/F-C0035-015.png",
            ),
            imageWidth = 1245,
            imageHeight = 1500,
            argbPixels = IntArray(1245 * 1500),
            sourceId = "F-C0035-015",
            endHour = 12,
        )

        assertEquals(21.72, grid.bounds.south, .0001)
        assertEquals(25.52, grid.bounds.north, .0001)
        assertTrue(grid.valueAt(80, 0).isNaN())
        assertTrue(grid.valueAt(40, grid.height - 1).isNaN())
        assertEquals(0f, grid.valueAt(0, grid.height - 1))
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}
