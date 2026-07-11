package com.rton.howstheweather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class WeatherGridTest {
    private val grid = WeatherGrid(
        width = 2,
        height = 2,
        values = floatArrayOf(0f, 10f, 20f, 30f),
        unit = WeatherUnit.MILLIMETERS_PER_HOUR,
        bounds = GeoBounds(0.0, 0.0, 2.0, 2.0),
        resolutionKm = 1.25,
        validAt = Instant.EPOCH,
        sourceId = "fixture",
    )

    @Test fun `bilinear sampling preserves center average`() {
        assertEquals(15f, grid.sample(GeoPoint(1.0, 1.0))!!, .0001f)
    }

    @Test fun `sampling outside bounds returns null`() {
        assertNull(grid.sample(GeoPoint(3.0, 1.0)))
    }

    @Test fun `missing corner does not become dry weather`() {
        val missing = grid.copy(values = floatArrayOf(0f, Float.NaN, 20f, 30f))
        assertNull(missing.sample(GeoPoint(1.0, 1.0)))
    }
}
