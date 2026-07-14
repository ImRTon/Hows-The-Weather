package com.rton.howstheweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CwaWindObservationParserTest {
    private val parser = CwaWindObservationParser()

    @Test
    fun `parses WGS84 wind and skips missing sentinels`() {
        val result = parser.parse(
            """
            {
              "records": {
                "Station": [
                  {
                    "StationName": "臺北",
                    "StationId": "466920",
                    "ObsTime": { "DateTime": "2026-07-11T14:00:00+08:00" },
                    "GeoInfo": {
                      "Coordinates": [
                        { "CoordinateName": "TWD67", "StationLatitude": 25.035, "StationLongitude": 121.506 },
                        { "CoordinateName": "WGS84", "StationLatitude": 25.0377, "StationLongitude": 121.5149 }
                      ]
                    },
                    "WeatherElement": { "WindDirection": 55.0, "WindSpeed": 4.2 }
                  },
                  {
                    "StationName": "缺值站",
                    "StationId": "BAD",
                    "ObsTime": { "DateTime": "2026-07-11T14:00:00+08:00" },
                    "GeoInfo": {
                      "Coordinates": { "CoordinateName": "WGS84", "StationLatitude": 24.0, "StationLongitude": 121.0 }
                    },
                    "WeatherElement": { "WindDirection": -99, "WindSpeed": -99 }
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, result.size)
        assertEquals("臺北", result.single().stationName)
        assertEquals(25.0377, result.single().coordinate.latitude, 0.0001)
        assertEquals(121.5149, result.single().coordinate.longitude, 0.0001)
        assertEquals(4.2f, result.single().speedMetersPerSecond, 0.001f)
        assertEquals(55f, result.single().directionDegrees, 0.001f)
    }

    @Test
    fun `reports why every station was rejected`() {
        val error = runCatching {
            parser.parse(
                """
                {
                  "success": "true",
                  "records": {
                    "Station": [{
                      "StationName": "缺值站",
                      "ObsTime": { "DateTime": "2026-07-12T09:00:00+08:00" },
                      "GeoInfo": { "Coordinates": {
                        "CoordinateName": "WGS84",
                        "StationLatitude": 25.0,
                        "StationLongitude": 121.5
                      }},
                      "WeatherElement": { "WindDirection": -99, "WindSpeed": -99 }
                    }]
                  }
                }
                """.trimIndent(),
            )
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("風值缺失 1"))
    }

    @Test
    fun `accepts a single station object`() {
        val result = parser.parse(
            """
            {
              "success": "true",
              "records": {
                "Station": {
                  "StationName": "臺北",
                  "ObsTime": { "DateTime": "2026-07-12T09:00:00+08:00" },
                  "GeoInfo": { "Coordinates": {
                    "CoordinateName": "WGS84",
                    "StationLatitude": 25.0377,
                    "StationLongitude": 121.5149
                  }},
                  "WeatherElement": { "WindDirection": 55.0, "WindSpeed": 4.2 }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, result.size)
    }
}
