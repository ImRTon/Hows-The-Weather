package com.rton.howstheweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CwaSatelliteImageParserTest {
    private val parser = CwaSatelliteImageParser()

    @Test fun `parses East Asia satellite metadata`() {
        val metadata = parser.parseMetadata(
            """{
              "cwaopendata": { "dataset": {
                "GeoInfo": {
                  "LongitudeRange": "102.0-155.0",
                  "LatitudeRange": "0.0-50.0"
                },
                "ObsTime": { "Datetime": "2026-07-11T12:30:00+08:00" },
                "Resource": { "ProductURL": "https://example.test/cloud.jpg" }
              }}
            }""".trimIndent(),
        )

        assertEquals(102.0, metadata.bounds.west, 0.0)
        assertEquals(155.0, metadata.bounds.east, 0.0)
        assertEquals(0.0, metadata.bounds.south, 0.0)
        assertEquals(50.0, metadata.bounds.north, 0.0)
        assertEquals("2026-07-11T04:30:00Z", metadata.observedAt.toString())
    }

    @Test fun `reprojects Lambert East Asia pixels to a longitude latitude grid`() {
        val metadata = parser.parseMetadata(
            """{"cwaopendata":{"dataset":{
              "GeoInfo":{"LongitudeRange":"102-155","LatitudeRange":"0-50"},
              "ObsTime":{"DateTime":"2026-07-11T04:30:00Z"},
              "Resource":{"ProductURL":"https://example.test/cloud.jpg"}
            }}}""",
        )
        val grid = parser.toGrid(
            metadata = metadata,
            width = 5,
            height = 5,
            argbPixels = IntArray(25) { 0xffffffff.toInt() },
            sourceId = "O-B0032-003",
        )

        assertEquals(1f, grid.valueAt(2, 2), .0001f)
        assertEquals(102.0, grid.bounds.west, 0.0)
        assertEquals(155.0, grid.bounds.east, 0.0)
    }

    @Test fun `Taiwan TM reprojects to regular latitude longitude and leaves outside corners missing`() {
        val metadata = parser.parseMetadata(
            """{"cwaopendata":{"dataset":{
              "GeoInfo":{"LongitudeRange":"115.976888855-126.02300114","LatitudeRange":"19.100625745-28.29937425"},
              "ObsTime":{"Datetime":"2026-07-11T22:10:00+08:00"},
              "Resource":{"ProductURL":"https://example.test/taiwan.jpg"}
            }}}""",
        )
        val grid = parser.toGrid(
            metadata = metadata,
            width = 8,
            height = 8,
            argbPixels = IntArray(64) { 0xffffffff.toInt() },
            sourceId = "O-C0042-004",
        )

        assertEquals(8, grid.width)
        assertEquals(1f, grid.valueAt(4, 4), .0001f)
        assertTrue(grid.valueAt(0, 0).isNaN())
        assertEquals(115.976888855, grid.bounds.west, 0.0)
        assertEquals(126.02300114, grid.bounds.east, 0.0)
    }

    @Test fun `East Asia Lambert transform contains Taiwan and preserves axis directions`() {
        val taipei = parser.projectToEastAsia(25.04, 121.52, 800, 800)!!
        val east = parser.projectToEastAsia(25.04, 140.0, 800, 800)!!
        val north = parser.projectToEastAsia(40.0, 121.52, 800, 800)!!

        assertEquals(true, east.first > taipei.first)
        assertEquals(true, north.second < taipei.second)
        assertEquals(null, parser.projectToEastAsia(-20.0, 121.52, 800, 800))
    }

    @Test fun `rank selection matches a full sort with duplicate luminance values`() {
        val random = Random(42)
        repeat(500) {
            val values = IntArray(25) { random.nextInt(0, 256) }
            val expected = values.sorted()[6]

            assertEquals(expected, parser.selectKth(values.copyOf(), 6))
        }
    }

    @Test fun `East Asia preserves the 1200 pixel source dimensions through reprojection`() {
        val metadata = CwaSatelliteImageMetadata(
            bounds = com.rton.howstheweather.domain.GeoBounds(0.0, 102.0, 50.0, 155.0),
            observedAt = java.time.Instant.parse("2026-07-14T00:00:00Z"),
            productUrl = "https://example.test/east-asia.jpg",
        )

        val grid = parser.toGrid(
            metadata = metadata,
            width = 1200,
            height = 1200,
            argbPixels = IntArray(1200 * 1200) { 0xff808080.toInt() },
            sourceId = "O-B0032-003",
        )

        assertEquals(1200, grid.width)
        assertEquals(1200, grid.height)
    }

    @Test fun `Taiwan preserves native image dimensions and cancellation rows`() {
        val metadata = CwaSatelliteImageMetadata(
            bounds = com.rton.howstheweather.domain.GeoBounds(
                19.100625745,
                115.976888855,
                28.29937425,
                126.02300114,
            ),
            observedAt = java.time.Instant.parse("2026-07-14T00:00:00Z"),
            productUrl = "https://example.test/taiwan.jpg",
        )
        var cancellationChecks = 0

        val grid = parser.toGrid(
            metadata = metadata,
            width = 1000,
            height = 800,
            argbPixels = IntArray(1000 * 800) { 0xff808080.toInt() },
            sourceId = "O-C0042-004",
            checkCancelled = { cancellationChecks++ },
        )

        assertEquals(1000, grid.width)
        assertEquals(800, grid.height)
        assertEquals(grid.height, cancellationChecks)
    }

    @Test fun `Taiwan TM sampling preserves native crop offsets and missing title`() {
        val sourceId = CwaSatelliteProduct.TAIWAN.sourceId
        val parser = CwaSatelliteImageParser(mapOf(sourceId to croppedTestCleaner()))
        val bounds = CwaSatelliteProduct.TAIWAN.bounds
        val metadata = CwaSatelliteImageMetadata(bounds,
            java.time.Instant.parse("2026-11-10T11:40:00Z"), "https://example.test/cloud.jpg")
        val pixels = IntArray(64) { index ->
            val gray = 80 + index % 8 * 4 + index / 8 * 8
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val grid = parser.toGrid(metadata, 8, 8, pixels, sourceId)
        var valid = 0
        var missing = 0
        for (y in 0..7) for (x in 0..7) {
            val latitude = bounds.north - y * (bounds.north - bounds.south) / 7
            val longitude = bounds.west + x * (bounds.east - bounds.west) / 7
            val source = parser.projectToTaiwan(latitude, longitude, 8, 8)
            if (source != null && source.first in 1..6 && source.second in 2..6) {
                val gray = 80 + source.first * 4 + source.second * 8
                assertEquals(gray / 255f - .025f, grid.valueAt(x, y), .0001f)
                valid++
            } else {
                assertTrue(grid.valueAt(x, y).isNaN())
                missing++
            }
        }
        assertTrue(valid > 0 && missing > 0)
        assertEquals(8, grid.width)
        assertEquals(8, grid.height)
        assertEquals(bounds, grid.bounds)
    }

    @Test fun `Taiwan TM aligns to independently measured original graticule controls`() {
        assertMeasuredControls("taiwan", 2.0)
    }

    @Test fun `East Asia Lambert aligns to independently measured original graticule controls`() {
        assertMeasuredControls("east_asia", 3.0)
    }

    private fun assertMeasuredControls(region: String, tolerance: Double) {
        val fixture = org.json.JSONObject(javaClass.getResourceAsStream("/satellite_control_points.json")!!
            .bufferedReader().use { it.readText() }).getJSONObject(region)
        val size = fixture.getInt("size")
        val points = fixture.getJSONArray("controls")
        for (index in 0 until points.length()) {
            val point = points.getJSONObject(index)
            val latitude = point.getDouble("latitude")
            val longitude = point.getDouble("longitude")
            val predicted = if (region == "taiwan") parser.projectToTaiwan(latitude, longitude, size, size)
                else parser.projectToEastAsia(latitude, longitude, size, size)
            requireNotNull(predicted)
            val error = kotlin.math.hypot(predicted.first - point.getDouble("x"),
                predicted.second - point.getDouble("y"))
            assertTrue("$region ($latitude,$longitude) differs from original graticule by $error pixels", error <= tolerance)
        }
    }

    @Test fun `Lambert crop keeps original projection offsets and missing title transparent`() {
        val sourceId = CwaSatelliteProduct.EAST_ASIA.sourceId
        val parser = CwaSatelliteImageParser(mapOf(sourceId to croppedTestCleaner()))
        val metadata = CwaSatelliteImageMetadata(CwaSatelliteProduct.EAST_ASIA.bounds,
            java.time.Instant.parse("2026-11-10T11:40:00Z"), "https://example.test/cloud.jpg")

        val grid = parser.toGrid(metadata, 8, 8, IntArray(64) { 0xffaaaaaa.toInt() }, sourceId)

        var valid = 0
        var missing = 0
        for (y in 0..7) for (x in 0..7) {
            val pixel = parser.projectToEastAsia(50.0 - y * 50.0 / 7, 102.0 + x * 53.0 / 7, 8, 8)
            val inside = pixel != null && pixel.first in 1..6 && pixel.second in 2..6
            if (inside) {
                assertEquals(170f / 255 - 0.025f, grid.valueAt(x, y), 0.0001f)
                valid++
            } else {
                assertTrue(grid.valueAt(x, y).isNaN())
                missing++
            }
        }
        assertTrue(valid > 0 && missing > 0)
        assertEquals(8, grid.width)
        assertEquals(8, grid.height)
    }

    @Test fun `native mask rejects an animation source with the same dataset ID`() {
        val parser = CwaSatelliteImageParser(mapOf(CwaSatelliteProduct.TAIWAN.sourceId to croppedTestCleaner()))
        val metadata = CwaSatelliteImageMetadata(CwaSatelliteProduct.TAIWAN.bounds,
            java.time.Instant.parse("2026-11-10T11:40:00Z"), "https://example.test/cloud.jpg")
        assertTrue(runCatching {
            parser.toGrid(metadata, 8, 8, IntArray(64), "O-C0042-004|animation:TWI_IR1_Gray_800")
        }.exceptionOrNull() is IllegalStateException)
    }

    private fun croppedTestCleaner() = CloudImageCleaner(CloudBackgroundTemplate(
        width = 8, height = 8, luminance = FloatArray(64), annotationMask = BooleanArray(64),
        excludedMask = BooleanArray(64) { it / 8 < 2 || it / 8 == 7 || it % 8 == 0 || it % 8 == 7 },
    ))
}
