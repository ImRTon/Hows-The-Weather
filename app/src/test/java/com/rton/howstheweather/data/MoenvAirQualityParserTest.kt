package com.rton.howstheweather.data

import com.rton.howstheweather.domain.AirQualityGridBuilder
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherUnit
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MoenvAirQualityParserTest {
    @Test fun `selects nearest valid AQI station and preserves observed status`() {
        val result = MoenvAirQualityParser().nearest(
            json = """
                {
                  "records": [
                    {"sitename":"高雄","aqi":"80","status":"普通","pollutant":"臭氧八小時","publishtime":"2026-07-14 12:00:00","longitude":"120.30","latitude":"22.63"},
                    {"sitename":"士林","aqi":"42","status":"良好","pollutant":"","publishtime":"2026-07-14 13:00:00","longitude":"121.515","latitude":"25.095"},
                    {"sitename":"無效","aqi":"","status":"","pollutant":"","publishtime":"","longitude":"121.5","latitude":"25.1"}
                  ]
                }
            """.trimIndent(),
            target = GeoPoint(25.10, 121.51),
        )

        assertNotNull(result)
        assertEquals("士林", result!!.stationName)
        assertEquals(42, result.aqi)
        assertEquals("良好", result.status)
        assertEquals(Instant.parse("2026-07-14T05:00:00Z"), result.observedAt)
    }

    @Test fun `accepts current MOENV top-level array response`() {
        val result = MoenvAirQualityParser().nearest(
            json = """
                [
                  {"sitename":"士林","aqi":"42","status":"良好","pollutant":"","publishtime":"2026-07-14 13:00:00","longitude":"121.515","latitude":"25.095"}
                ]
            """.trimIndent(),
            target = GeoPoint(25.10, 121.51),
        )

        assertNotNull(result)
        assertEquals("士林", result!!.stationName)
        assertEquals(42, result.aqi)
    }

    @Test fun `parses slash-separated publish time from the live API`() {
        val result = MoenvAirQualityParser().nearest(
            json = """
                [
                  {"sitename":"龍潭","aqi":"33","status":"良好","pollutant":"","publishtime":"2026/10/22 17:00:00","longitude":"121.21646","latitude":"24.864"}
                ]
            """.trimIndent(),
            target = GeoPoint(24.86, 121.21),
        )

        assertNotNull(result)
        assertEquals(Instant.parse("2026-10-22T09:00:00Z"), result!!.observedAt)
    }

    @Test fun `loads AQI away from the caller thread`() = runTest {
        val callerThread = Thread.currentThread().name
        val requestThread = AtomicReference<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestThread.set(Thread.currentThread().name)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """[{"sitename":"士林","aqi":"42","status":"良好","pollutant":"","publishtime":"2026-07-14 13:00:00","longitude":"121.515","latitude":"25.095"}]"""
                            .toResponseBody(),
                    )
                    .build()
            }
            .build()

        val result = MoenvAirQualityDataSource("test-key", client)
            .loadNearest(GeoPoint(25.10, 121.51))

        assertNotNull(result)
        assertNotEquals(callerThread, requestThread.get())
    }

    @Test fun `snapshot returns all valid stations and builds bounded IDW grid`() {
        val snapshot = MoenvAirQualityParser().snapshot(
            json = """
                [
                  {"sitename":"高雄","aqi":"80","status":"普通","pollutant":"臭氧八小時","publishtime":"2026-07-14 12:00:00","longitude":"120.30","latitude":"22.63"},
                  {"sitename":"士林","aqi":"42","status":"良好","pollutant":"","publishtime":"2026-07-14 13:00:00","longitude":"121.515","latitude":"25.095"},
                  {"sitename":"無效","aqi":"","status":"","pollutant":"","publishtime":"","longitude":"121.5","latitude":"25.1"}
                ]
            """.trimIndent(),
            target = GeoPoint(25.10, 121.51),
        )

        assertEquals(2, snapshot.stations.size)
        assertEquals("士林", snapshot.nearest?.stationName)

        val grid = AirQualityGridBuilder.build(snapshot.stations)
        assertNotNull(grid)
        assertEquals(WeatherUnit.AQI, grid!!.unit)
        val shilinSample = grid.sample(GeoPoint(25.095, 121.515))
        assertNotNull(shilinSample)
        assertEquals(42f, shilinSample!!, 2f)
        assertEquals(1f, grid.sampleAlphaForDisplay(25.095, 121.515), 0.05f)
        val featherAlpha = grid.sampleAlphaForDisplay(25.195, 121.515)
        assert(featherAlpha in 0.05f..0.95f) { "Expected feathered alpha at ~11 km, got $featherAlpha" }
        assertNull(grid.sample(GeoPoint(25.295, 121.515)))
        assertEquals(0f, grid.sampleAlphaForDisplay(25.295, 121.515), 0.01f)
        val distantOceanSample = grid.sample(GeoPoint(22.0, 118.2))
        assertNull(distantOceanSample)
    }

    @Test fun `clusterAqiStations groups nearby stations when zoomed out and separates when zoomed in`() {
        val observedAt = Instant.parse("2026-07-14T05:00:00Z")
        val stations = listOf(
            com.rton.howstheweather.domain.AirQualityObservation(
                stationName = "士林",
                aqi = 40,
                status = "良好",
                primaryPollutant = null,
                observedAt = observedAt,
                coordinate = GeoPoint(25.095, 121.515),
            ),
            com.rton.howstheweather.domain.AirQualityObservation(
                stationName = "中山",
                aqi = 56,
                status = "普通",
                primaryPollutant = null,
                observedAt = observedAt,
                coordinate = GeoPoint(25.062, 121.526),
            ),
            com.rton.howstheweather.domain.AirQualityObservation(
                stationName = "高雄",
                aqi = 80,
                status = "普通",
                primaryPollutant = null,
                observedAt = observedAt,
                coordinate = GeoPoint(22.630, 120.300),
            ),
        )

        val zoomedOut = com.rton.howstheweather.ui.clusterAqiStations(
            stations = stations,
            zoom = 8.0f,
            selectedStationName = null,
        )
        assertEquals(2, zoomedOut.size)
        val taipeiCluster = zoomedOut.first { it.stations.size == 2 }
        assertEquals(48, taipeiCluster.representativeAqi)

        val zoomedIn = com.rton.howstheweather.ui.clusterAqiStations(
            stations = stations,
            zoom = 12.0f,
            selectedStationName = null,
        )
        assertEquals(3, zoomedIn.size)
    }

    @Test fun `AQI render style keeps Good values green and varies continuously with numeric value`() {
        val style = com.rton.howstheweather.render.WeatherRenderStyle.aqi(
            com.rton.howstheweather.render.RenderTheme.LIGHT,
        )
        val aqi15 = style.colorFor(15f)
        val aqi42 = style.colorFor(42f)
        val aqi65 = style.colorFor(65f)

        assertNotEquals(aqi15, aqi42)
        val green42 = (aqi42 ushr 8) and 0xFF
        val red42 = (aqi42 ushr 16) and 0xFF
        assert(green42 > red42 + 40) { "Expected AQI 42 to remain green-dominant, got r=$red42 g=$green42" }

        val red65 = (aqi65 ushr 16) and 0xFF
        val green65 = (aqi65 ushr 8) and 0xFF
        assert(red65 > green65) { "Expected AQI 65 to be yellow/amber-dominant, got r=$red65 g=$green65" }
    }
}
