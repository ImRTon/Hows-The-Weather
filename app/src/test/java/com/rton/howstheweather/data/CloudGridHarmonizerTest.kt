package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class CloudGridHarmonizerTest {
    @Test fun `matches local cloud contrast to global overlap without changing bounds`() {
        val bounds = GeoBounds(20.0, 118.0, 26.0, 124.0)
        fun grid(source: String, transform: (Float) -> Float): WeatherGrid = WeatherGrid(
            width = 16,
            height = 16,
            values = FloatArray(256) { transform(0.1f + it / 255f * 0.8f) },
            unit = WeatherUnit.LUMINANCE,
            bounds = bounds,
            resolutionKm = 2.0,
            validAt = Instant.parse("2026-07-13T14:20:00Z"),
            sourceId = source,
        )
        val global = grid("global") { it }
        val local = grid("local") { 0.2f + it * 0.6f }

        val result = CloudGridHarmonizer().harmonize(local, global)

        assertEquals(bounds, result.bounds)
        assertEquals(global.valueAt(8, 8), result.valueAt(8, 8), 0.02f)
    }
}
