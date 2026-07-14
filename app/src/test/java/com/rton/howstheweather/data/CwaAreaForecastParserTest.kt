package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CwaAreaForecastParserTest {
    private val parser = CwaAreaForecastParser()

    @Test fun `selects nearest district and preserves native three hour probability`() {
        val forecast = parser.nearestForecast(
            json = fixture(),
            target = GeoPoint(25.095, 121.512),
            sourceId = "F-D0047-061",
            countyName = "臺北市",
            now = Instant.parse("2026-07-14T09:00:00Z"),
        )

        assertNotNull(forecast)
        assertEquals("臺北市士林區", forecast!!.displayName)
        assertEquals(1, forecast.periods.size)
        assertEquals(30, forecast.periods.single().precipitationProbabilityPercent)
        assertEquals(30, forecast.periods.single().minimumTemperatureCelsius)
        assertEquals(32, forecast.periods.single().maximumTemperatureCelsius)
        assertEquals("短暫陣雨", forecast.periods.single().weatherDescription)
        assertEquals(74, forecast.periods.single().relativeHumidityPercent)
    }

    @Test fun `parses native weekly day and night values without inventing hourly detail`() {
        val forecast = parser.nearestWeeklyForecast(
            json = weeklyFixture(),
            target = GeoPoint(25.095, 121.512),
            sourceId = "F-D0047-063",
            countyName = "臺北市",
            now = Instant.parse("2026-07-13T20:00:00Z"),
        )

        assertNotNull(forecast)
        assertEquals(2, forecast!!.periods.size)
        assertEquals(listOf(40, 30), forecast.periods.map { it.precipitationProbabilityPercent })
        assertEquals(listOf(26, 25), forecast.periods.map { it.minimumTemperatureCelsius })
        assertEquals(listOf(34, 29), forecast.periods.map { it.maximumTemperatureCelsius })
        assertEquals(listOf(70, 84), forecast.periods.map { it.relativeHumidityPercent })
    }

    @Test fun `exposes county locator coordinates without forecast interpolation`() {
        val locations = parser.locations(fixture())

        assertEquals(listOf("松山區", "士林區"), locations.map(CwaForecastLocation::name))
        assertEquals(25.094612, locations.last().coordinate.latitude, 0.0)
    }

    private fun fixture() = """
        {
          "records": {
            "Locations": {
              "LocationsName": "臺北市",
              "Location": [
                {
                  "LocationName": "松山區",
                  "Latitude": "25.051608",
                  "Longitude": "121.568983",
                  "WeatherElement": []
                },
                {
                  "LocationName": "士林區",
                  "Latitude": "25.094612",
                  "Longitude": "121.511458",
                  "WeatherElement": [
                    {
                      "ElementName": "溫度",
                      "Time": [
                        {"DataTime":"2026-07-14T18:00:00+08:00","ElementValue":[{"Temperature":"32"}]},
                        {"DataTime":"2026-07-14T19:00:00+08:00","ElementValue":[{"Temperature":"31"}]},
                        {"DataTime":"2026-07-14T20:00:00+08:00","ElementValue":[{"Temperature":"30"}]}
                      ]
                    },
                    {
                      "ElementName": "相對濕度",
                      "Time": [
                        {"DataTime":"2026-07-14T18:00:00+08:00","ElementValue":[{"RelativeHumidity":"70"}]},
                        {"DataTime":"2026-07-14T19:00:00+08:00","ElementValue":[{"RelativeHumidity":"74"}]},
                        {"DataTime":"2026-07-14T20:00:00+08:00","ElementValue":[{"RelativeHumidity":"78"}]}
                      ]
                    },
                    {
                      "ElementName": "3小時降雨機率",
                      "Time": [
                        {
                          "StartTime":"2026-07-14T18:00:00+08:00",
                          "EndTime":"2026-07-14T21:00:00+08:00",
                          "ElementValue":[{"ProbabilityOfPrecipitation":"30"}]
                        }
                      ]
                    },
                    {
                      "ElementName": "天氣現象",
                      "Time": [
                        {
                          "StartTime":"2026-07-14T18:00:00+08:00",
                          "EndTime":"2026-07-14T21:00:00+08:00",
                          "ElementValue":[{"Weather":"短暫陣雨","WeatherCode":"08"}]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
          }
        }
    """.trimIndent()

    private fun weeklyFixture() = """
        {
          "records": {
            "Locations": {
              "Location": {
                "LocationName": "士林區",
                "Latitude": "25.094612",
                "Longitude": "121.511458",
                "WeatherElement": [
                  {"ElementName":"12小時降雨機率","Time":[
                    {"StartTime":"2026-07-14T06:00:00+08:00","EndTime":"2026-07-14T18:00:00+08:00","ElementValue":{"ProbabilityOfPrecipitation":"40"}},
                    {"StartTime":"2026-07-14T18:00:00+08:00","EndTime":"2026-07-15T06:00:00+08:00","ElementValue":{"ProbabilityOfPrecipitation":"30"}}
                  ]},
                  {"ElementName":"最低溫度","Time":[
                    {"StartTime":"2026-07-14T06:00:00+08:00","EndTime":"2026-07-14T18:00:00+08:00","ElementValue":{"MinTemperature":"26"}},
                    {"StartTime":"2026-07-14T18:00:00+08:00","EndTime":"2026-07-15T06:00:00+08:00","ElementValue":{"MinTemperature":"25"}}
                  ]},
                  {"ElementName":"最高溫度","Time":[
                    {"StartTime":"2026-07-14T06:00:00+08:00","EndTime":"2026-07-14T18:00:00+08:00","ElementValue":{"MaxTemperature":"34"}},
                    {"StartTime":"2026-07-14T18:00:00+08:00","EndTime":"2026-07-15T06:00:00+08:00","ElementValue":{"MaxTemperature":"29"}}
                  ]},
                  {"ElementName":"平均相對濕度","Time":[
                    {"StartTime":"2026-07-14T06:00:00+08:00","EndTime":"2026-07-14T18:00:00+08:00","ElementValue":{"RelativeHumidity":"70"}},
                    {"StartTime":"2026-07-14T18:00:00+08:00","EndTime":"2026-07-15T06:00:00+08:00","ElementValue":{"RelativeHumidity":"84"}}
                  ]},
                  {"ElementName":"天氣現象","Time":[
                    {"StartTime":"2026-07-14T06:00:00+08:00","EndTime":"2026-07-14T18:00:00+08:00","ElementValue":{"Weather":"多雲短暫陣雨","WeatherCode":"08"}},
                    {"StartTime":"2026-07-14T18:00:00+08:00","EndTime":"2026-07-15T06:00:00+08:00","ElementValue":{"Weather":"多雲","WeatherCode":"04"}}
                  ]}
                ]
              }
            }
          }
        }
    """.trimIndent()
}
