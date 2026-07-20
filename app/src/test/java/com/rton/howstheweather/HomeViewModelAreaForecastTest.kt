package com.rton.howstheweather

import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.AreaForecastPeriod
import com.rton.howstheweather.domain.GeoPoint
import java.time.Instant
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeViewModelAreaForecastTest {
    private val target = GeoPoint(25.0478, 121.5319)

    @Test fun `keeps visible forecast when it finishes before the first snapshot`() {
        val visible = forecast(target)

        val resolved = areaForecastForTarget(
            target = target,
            incoming = null,
            previous = null,
            visible = visible,
        )

        assertSame(visible, resolved)
    }

    @Test fun `does not carry a visible forecast to another target`() {
        val resolved = areaForecastForTarget(
            target = target,
            incoming = null,
            previous = null,
            visible = forecast(GeoPoint(24.1477, 120.6736)),
        )

        assertNull(resolved)
    }

    private fun forecast(target: GeoPoint) = AreaForecast(
        target = target,
        areaCoordinate = target,
        countyName = "臺北市",
        districtName = "中正區",
        periods = listOf(
            AreaForecastPeriod(
                startAt = Instant.parse("2026-07-17T00:00:00Z"),
                endAt = Instant.parse("2026-07-17T03:00:00Z"),
                precipitationProbabilityPercent = 30,
                minimumTemperatureCelsius = 28,
                maximumTemperatureCelsius = 31,
                weatherDescription = "短暫陣雨",
                weatherCode = "08",
                relativeHumidityPercent = 75,
            ),
        ),
        sourceId = "F-D0047-061",
    )
}
