package com.rton.howstheweather.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CwaCurrentWeatherParserTest {
    private val parser = CwaCurrentWeatherParser()

    @Test
    fun `parses WGS84 current weather without turning missing values into zero`() {
        val observation = parser.parse(response(weather = """
            {
              "Weather": "多雲有陣雨",
              "AirTemperature": 28.4,
              "RelativeHumidity": 83,
              "WindDirection": 55.0,
              "WindSpeed": 2.7,
              "Now": { "Precipitation": "T" },
              "AirPressure": -99,
              "UVIndex": 4
            }
        """.trimIndent())).single()

        assertEquals(25.0377, observation.coordinate.latitude, 0.0001)
        assertEquals(121.5149, observation.coordinate.longitude, 0.0001)
        assertEquals("多雲有陣雨", observation.weatherDescription)
        assertEquals(28.4f, observation.temperatureCelsius!!, 0.001f)
        assertEquals(83f, observation.relativeHumidityPercent!!, 0.001f)
        assertTrue(observation.precipitationTrace)
        assertNull(observation.precipitationTodayMillimeters)
        assertNull(observation.airPressureHectopascals)
        assertEquals("O-A0003-001", observation.sourceId)
        assertEquals(Instant.parse("2026-07-15T06:20:00Z"), observation.observedAt)
    }

    @Test
    fun `preserves variable wind and no recent precipitation codes`() {
        val observation = parser.parse(response(weather = """
            {
              "Weather": "陰",
              "AirTemperature": 24.0,
              "RelativeHumidity": 91,
              "WindDirection": 990,
              "WindSpeed": 1.2,
              "Now": { "Precipitation": -98 },
              "AirPressure": 1004.3,
              "UVIndex": -99
            }
        """.trimIndent())).single()

        assertTrue(observation.windDirectionVariable)
        assertNull(observation.windDirectionDegrees)
        assertEquals(0f, observation.precipitationTodayMillimeters!!, 0.001f)
        assertNull(observation.uvIndex)
    }

    @Test
    fun `parses string encoded values used by the live CWA response`() {
        val observation = parser.parse(response(weather = """
            {
              "Weather": "陰",
              "AirTemperature": "29.1",
              "RelativeHumidity": "81",
              "WindDirection": "210.0",
              "WindSpeed": "2.6",
              "Now": { "Precipitation": "0.5" },
              "AirPressure": "1004.2",
              "UVIndex": "0"
            }
        """.trimIndent(), coordinatesAsStrings = true)).single()

        assertEquals(25.0377, observation.coordinate.latitude, 0.0001)
        assertEquals(121.5149, observation.coordinate.longitude, 0.0001)
        assertEquals(29.1f, observation.temperatureCelsius!!, 0.001f)
        assertEquals(0.5f, observation.precipitationTodayMillimeters!!, 0.001f)
        assertEquals(1004.2f, observation.airPressureHectopascals!!, 0.001f)
        assertEquals(0, observation.uvIndex)
    }

    private fun response(weather: String, coordinatesAsStrings: Boolean = false): String {
        val latitude = if (coordinatesAsStrings) "\"25.0377\"" else "25.0377"
        val longitude = if (coordinatesAsStrings) "\"121.5149\"" else "121.5149"
        return """
        {
          "success": "true",
          "records": {
            "Station": [{
              "StationName": "臺北",
              "StationId": "466920",
              "ObsTime": { "DateTime": "2026-07-15T14:20:00+08:00" },
              "GeoInfo": {
                "Coordinates": [
                  { "CoordinateName": "TWD67", "StationLatitude": 25.035, "StationLongitude": 121.506 },
                  { "CoordinateName": "WGS84", "StationLatitude": $latitude, "StationLongitude": $longitude }
                ]
              },
              "WeatherElement": $weather
            }]
          }
        }
    """.trimIndent()
    }
}
