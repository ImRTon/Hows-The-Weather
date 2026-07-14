package com.rton.howstheweather.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class WindFieldTest {
    @Test
    fun `northerly wind particles travel south`() {
        val vector = observation(direction = 0f).travelComponents()
        assertEquals(0f, vector.eastMetersPerSecond, .001f)
        assertEquals(-5f, vector.northMetersPerSecond, .001f)
    }

    @Test
    fun `easterly wind particles travel west`() {
        val vector = observation(direction = 90f).travelComponents()
        assertEquals(-5f, vector.eastMetersPerSecond, .001f)
        assertEquals(0f, vector.northMetersPerSecond, .001f)
    }

    private fun observation(direction: Float) = WindObservation(
        stationName = "測試站",
        coordinate = GeoPoint(25.0, 121.5),
        speedMetersPerSecond = 5f,
        directionDegrees = direction,
        observedAt = Instant.parse("2026-07-11T06:00:00Z"),
    )
}
