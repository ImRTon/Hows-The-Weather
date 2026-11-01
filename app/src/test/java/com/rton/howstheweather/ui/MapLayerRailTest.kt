package com.rton.howstheweather.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapLayerRailTest {
    private fun resolve(start: Int, position: Float, velocity: Float = 0f) =
        resolveLayerSwipeIndex(
            startIndex = start,
            position = position,
            velocity = velocity,
            velocityThreshold = 100f,
            count = 3,
        )

    @Test
    fun `slow release snaps to nearest segment`() {
        assertEquals(0, resolve(start = 0, position = 0.4f))
        assertEquals(1, resolve(start = 0, position = 0.6f))
        assertEquals(1, resolve(start = 2, position = 1.3f))
    }

    @Test
    fun `fling advances at least one segment in its direction`() {
        assertEquals(1, resolve(start = 0, position = 0.1f, velocity = 500f))
        assertEquals(1, resolve(start = 2, position = 1.9f, velocity = -500f))
        assertEquals(2, resolve(start = 0, position = 1.2f, velocity = 500f))
    }

    @Test
    fun `fling past the ends stays within the segments`() {
        assertEquals(2, resolve(start = 2, position = 2f, velocity = 500f))
        assertEquals(0, resolve(start = 0, position = 0f, velocity = -500f))
    }

    @Test
    fun `slow drag below threshold does not count as a fling`() {
        assertEquals(0, resolve(start = 0, position = 0.3f, velocity = 80f))
    }
}
