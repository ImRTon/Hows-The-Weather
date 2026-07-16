package com.rton.howstheweather.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class PriorityForecastSelectorTest {
    @Test
    fun `daytime shows today day tonight and tomorrow day`() {
        val now = Instant.parse("2026-07-16T02:00:00Z") // 10:00 in Taipei

        val result = PriorityForecastSelector.select(periods(), now)

        assertEquals(listOf("今天白天", "今晚", "明天白天"), result.map { it.label })
        assertEquals(60, result.first().precipitationProbabilityPercent)
        assertEquals("短暫雷陣雨", result.first().weatherDescription)
        assertEquals(28, result.first().minimumTemperatureCelsius)
        assertEquals(34, result.first().maximumTemperatureCelsius)
    }

    @Test
    fun `nighttime skips elapsed daytime and advances to tomorrow night`() {
        val now = Instant.parse("2026-07-16T12:30:00Z") // 20:30 in Taipei

        val result = PriorityForecastSelector.select(periods(), now)

        assertEquals(listOf("今晚", "明天白天", "明晚"), result.map { it.label })
    }

    @Test
    fun `missing values remain unavailable instead of becoming zero`() {
        val now = Instant.parse("2026-07-16T04:00:00Z")
        val result = PriorityForecastSelector.select(
            listOf(period("2026-07-16T06:00:00+08:00", "2026-07-16T18:00:00+08:00")),
            now,
        ).single()

        assertEquals(null, result.precipitationProbabilityPercent)
        assertEquals(null, result.minimumTemperatureCelsius)
        assertEquals(null, result.maximumTemperatureCelsius)
    }

    private fun periods() = listOf(
        period("2026-07-16T06:00:00+08:00", "2026-07-16T12:00:00+08:00", "多雲", 20, 28, 32),
        period("2026-07-16T12:00:00+08:00", "2026-07-16T18:00:00+08:00", "短暫雷陣雨", 60, 30, 34),
        period("2026-07-16T18:00:00+08:00", "2026-07-17T06:00:00+08:00", "短暫雨", 40, 25, 29),
        period("2026-07-17T06:00:00+08:00", "2026-07-17T18:00:00+08:00", "晴時多雲", 20, 27, 34),
        period("2026-07-17T18:00:00+08:00", "2026-07-18T06:00:00+08:00", "多雲", 10, 25, 29),
    )

    private fun period(
        start: String,
        end: String,
        weather: String = "",
        rain: Int? = null,
        minimum: Int? = null,
        maximum: Int? = null,
    ) = AreaForecastPeriod(
        startAt = java.time.OffsetDateTime.parse(start).toInstant(),
        endAt = java.time.OffsetDateTime.parse(end).toInstant(),
        precipitationProbabilityPercent = rain,
        minimumTemperatureCelsius = minimum,
        maximumTemperatureCelsius = maximum,
        weatherDescription = weather,
        weatherCode = null,
    )
}
