package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoPoint
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
}
