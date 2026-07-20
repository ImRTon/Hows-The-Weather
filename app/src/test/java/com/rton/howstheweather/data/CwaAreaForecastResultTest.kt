package com.rton.howstheweather.data

import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class CwaAreaForecastResultTest {
    private val parser = CwaAreaForecastParser()
    private val target = GeoPoint(25.0478, 121.5319)

    @Test fun `all candidate request failures remain failures instead of becoming no forecast`() {
        val first = IllegalStateException("first request failed")
        val second = IllegalStateException("second request failed")

        try {
            resolveAreaForecastResults(
                results = listOf(Result.failure(first), Result.failure(second)),
                target = target,
                parser = parser,
                failureMessage = "forecast failed",
            )
            fail("Expected the aggregate failure")
        } catch (error: IllegalStateException) {
            assertEquals("forecast failed", error.message)
            assertSame(first, error.cause)
            assertSame(second, first.suppressed.single())
        }
    }

    @Test fun `a valid empty candidate response can still mean no nearby forecast`() {
        val forecast = resolveAreaForecastResults(
            results = listOf(Result.success(null), Result.failure(IllegalStateException("other county failed"))),
            target = target,
            parser = parser,
            failureMessage = "forecast failed",
        )

        assertNull(forecast)
    }

    @Test fun `available candidate wins even when another county request fails`() {
        val available = AreaForecast(
            target = target,
            areaCoordinate = GeoPoint(25.0324, 121.5199),
            countyName = "臺北市",
            districtName = "中正區",
            periods = emptyList(),
            sourceId = "F-D0047-061",
        )

        val forecast = resolveAreaForecastResults(
            results = listOf(Result.failure(IllegalStateException("other county failed")), Result.success(available)),
            target = target,
            parser = parser,
            failureMessage = "forecast failed",
        )

        assertSame(available, forecast)
    }
}
