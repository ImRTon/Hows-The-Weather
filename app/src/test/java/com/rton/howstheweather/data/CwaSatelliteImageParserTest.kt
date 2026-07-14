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

    @Test fun `taiwan product keeps its regular longitude latitude projection`() {
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
        assertEquals(1f, grid.valueAt(0, 0), .0001f)
        assertEquals(1f, grid.valueAt(7, 7), .0001f)
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

    @Test fun `East Asia product respects its reduced grid dimension`() {
        val metadata = CwaSatelliteImageMetadata(
            bounds = com.rton.howstheweather.domain.GeoBounds(0.0, 102.0, 50.0, 155.0),
            observedAt = java.time.Instant.parse("2026-07-14T00:00:00Z"),
            productUrl = "https://example.test/east-asia.jpg",
        )

        val grid = parser.toGrid(
            metadata = metadata,
            width = 40,
            height = 40,
            argbPixels = IntArray(1_600) { 0xff808080.toInt() },
            sourceId = "O-B0032-003",
            maxGridDimension = 8,
        )

        assertTrue(maxOf(grid.width, grid.height) <= 8)
    }
}
