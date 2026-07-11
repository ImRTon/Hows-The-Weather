package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CwaJsonGridParserTest {
    @Test fun `parses CWA lower-left origin and reverses rows for north-first grid`() {
        val grid = CwaJsonGridParser().parse(fixture("1,2,3,4"), WeatherUnit.DBZ, "fixture")
        assertEquals(2, grid.width)
        assertEquals(2, grid.height)
        assertEquals(3f, grid.valueAt(0, 0), 0f)
        assertEquals(4f, grid.valueAt(1, 0), 0f)
        assertEquals(1f, grid.valueAt(0, 1), 0f)
        assertEquals(3f, grid.sample(GeoPoint(20.1, 120.0))!!, .001f)
    }

    @Test fun `CWA sentinels stay missing instead of becoming dry weather`() {
        val grid = CwaJsonGridParser().parse(fixture("-99,2,3,-999"), WeatherUnit.DBZ, "fixture")
        assertTrue(grid.valueAt(1, 0).isNaN())
        assertTrue(grid.valueAt(0, 1).isNaN())
    }

    private fun fixture(content: String) = """
        {
          "cwaopendata": {
            "dataset": {
              "datasetInfo": {
                "parameterSet": {
                  "StartPointLongitude": "120.0",
                  "StartPointLatitude": "20.0",
                  "GridResolution": "0.1",
                  "DateTime": "2026-07-11T20:40:00+08:00",
                  "GridDimensionX": "2",
                  "GridDimensionY": "2"
                }
              },
              "contents": {"content": "$content"}
            }
          }
        }
    """.trimIndent()
}
