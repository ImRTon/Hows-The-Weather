package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservedWeatherTimelineTest {
    @Test fun `keeps only recent observations in chronological order`() {
        val currentAt = Instant.parse("2026-07-13T13:00:00Z")
        val current = grid(currentAt)
        val candidates = (-100..-10 step 10).map { minute ->
            grid(currentAt.plusSeconds(minute * 60L))
        } + grid(currentAt.plusSeconds(10 * 60L), "OLD-MOTION-EXTRAPOLATED-+10M")

        val history = ObservedWeatherTimeline().merge(current, candidates)

        assertEquals((-90L..0L step 10L).toList(), history.map {
            java.time.Duration.between(currentAt, it.validAt).toMinutes()
        })
    }

    private fun grid(validAt: Instant, sourceId: String = "O-C0042-004") = WeatherGrid(
        width = 2,
        height = 2,
        values = floatArrayOf(.2f, .3f, .4f, .5f),
        unit = WeatherUnit.LUMINANCE,
        bounds = GeoBounds(19.0, 116.0, 28.0, 126.0),
        resolutionKm = 3.5,
        validAt = validAt,
        sourceId = sourceId,
    )
}
