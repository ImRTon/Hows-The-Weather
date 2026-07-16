package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import com.rton.howstheweather.domain.WindProvenance
import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherDataSourceTest {
    @Test fun `stale supplemental update preserves independently loaded regional radar`() {
        val wide = radar("wide", GeoBounds(0.0, 100.0, 50.0, 160.0))
        val regional = radar("regional", GeoBounds(20.0, 118.0, 27.0, 124.0))
        val previous = snapshot(wide).copy(radarRegional = regional)
        val staleSupplementalUpdate = snapshot(wide)

        val merged = staleSupplementalUpdate.preserveRegionalRadarFrom(previous)

        assertEquals(regional, merged.radarRegional)
    }

    @Test fun `supplemental update can replace regional radar with a newer frame`() {
        val wide = radar("wide", GeoBounds(0.0, 100.0, 50.0, 160.0))
        val previousRegional = radar("regional-old", GeoBounds(20.0, 118.0, 27.0, 124.0))
        val replacement = radar("regional-new", GeoBounds(20.0, 118.0, 27.0, 124.0))
        val previous = snapshot(wide).copy(radarRegional = previousRegional)

        val merged = snapshot(wide).copy(radarRegional = replacement)
            .preserveRegionalRadarFrom(previous)

        assertEquals(replacement, merged.radarRegional)
    }

    @Test fun `missing official configuration never returns synthetic weather`() = runTest {
        val reason = "official weather unavailable"
        val source = UnavailableWeatherDataSource(reason)

        val failure = runCatching { source.load(GeoPoint(25.0, 121.5)) }.exceptionOrNull()
        val wind = source.loadWind()

        assertTrue(failure is IllegalStateException)
        assertEquals(reason, failure?.message)
        assertNull(wind.windGrid)
        assertTrue(wind.winds.isEmpty())
        assertEquals(WindProvenance.UNAVAILABLE, wind.provenance)
    }

    @Test fun `current weather requests only nearby stations with the required CWA schema`() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(CURRENT_WEATHER_RESPONSE.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val source = CwaWeatherDataSource(apiKey = "test-key", client = client)

        val observation = source.loadCurrentWeather(GeoPoint(25.0478, 121.5319))

        assertEquals("臺北", observation?.stationName)
        assertEquals(1, requests.size)
        val requestUrl = requests.single().url
        val stationIds = requestUrl.queryParameter("StationId").orEmpty().split(',')
        assertEquals(8, stationIds.size)
        assertTrue("466920" in stationIds)
        assertEquals("Coordinates", requestUrl.queryParameter("GeoInfo"))
        assertEquals(
            listOf("Weather", "AirTemperature"),
            requestUrl.queryParameter("WeatherElement").orEmpty().split(','),
        )
        assertNull(requestUrl.queryParameter("elementName"))
    }

    private companion object {
        fun radar(sourceId: String, bounds: GeoBounds) = WeatherGrid(
            width = 2,
            height = 2,
            bounds = bounds,
            unit = WeatherUnit.DBZ,
            validAt = Instant.parse("2026-07-16T12:00:00Z"),
            sourceId = sourceId,
            resolutionKm = 1.25,
            values = floatArrayOf(0f, 10f, 20f, 30f),
        )

        fun snapshot(radar: WeatherGrid) = WeatherSnapshot(
            radar = radar,
            rainForecast = emptyList(),
            cloudFrames = emptyList(),
            forecastAtTarget = emptyList(),
            winds = emptyList(),
            issuedAt = radar.validAt,
        )

        val CURRENT_WEATHER_RESPONSE = """
            {
              "success": "true",
              "records": {
                "Station": [{
                  "StationName": "臺北",
                  "StationId": "466920",
                  "ObsTime": { "DateTime": "2026-07-16T20:20:00+08:00" },
                  "GeoInfo": {
                    "Coordinates": [
                      {
                        "CoordinateName": "WGS84",
                        "StationLatitude": "25.0377",
                        "StationLongitude": "121.5149"
                      }
                    ]
                  },
                  "WeatherElement": {
                    "Weather": "多雲",
                    "AirTemperature": "29.1",
                    "RelativeHumidity": "81",
                    "WindDirection": "210.0",
                    "WindSpeed": "2.6",
                    "Now": { "Precipitation": "0.5" },
                    "AirPressure": "1004.2",
                    "UVIndex": "0"
                  }
                }]
              }
            }
        """.trimIndent()
    }
}
