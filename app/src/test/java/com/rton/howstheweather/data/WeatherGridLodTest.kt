package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WeatherGridLodTest {
    private val grid = WeatherGrid(
        width = 5,
        height = 5,
        values = FloatArray(25) { it.toFloat() },
        unit = WeatherUnit.DBZ,
        bounds = GeoBounds(0.0, 0.0, 4.0, 4.0),
        resolutionKm = 1.0,
        validAt = Instant.EPOCH,
        sourceId = "fixture",
    )

    @Test fun `wide LOD downsamples while preserving endpoints and bounds`() {
        val wide = grid.downsampled(3)

        assertEquals(3, wide.width)
        assertEquals(3, wide.height)
        assertEquals(grid.bounds, wide.bounds)
        assertEquals(0f, wide.valueAt(0, 0), 0f)
        assertEquals(24f, wide.valueAt(2, 2), 0f)
    }

    @Test fun `local LOD crops original numerical cells`() {
        val result = grid.croppedTo(GeoBounds(1.0, 1.0, 3.0, 3.0))
        assertNotNull(result)
        val local = result!!

        assertEquals(3, local.width)
        assertEquals(3, local.height)
        assertEquals(GeoBounds(1.0, 1.0, 3.0, 3.0), local.bounds)
        assertEquals(6f, local.valueAt(0, 0), 0f)
        assertEquals(18f, local.valueAt(2, 2), 0f)
    }
}
