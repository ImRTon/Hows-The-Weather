package com.rton.howstheweather.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRenderStyleTest {
    @Test fun `cloud opacity starts transparent and ramps smoothly`() {
        assertEquals(0f, cloudOpacity(0.08f, 0.62f), 0.0001f)
        assertEquals(0.62f, cloudOpacity(1f, 0.62f), 0.0001f)
        assertTrue(cloudOpacity(0.2f, 0.62f) < cloudOpacity(0.5f, 0.62f))
    }

    @Test fun `pchip stop normalization preserves stop anchors and stays strictly monotonic`() {
        val radar = WeatherRenderStyle.radar(RenderTheme.DARK)
        val expectedAnchors = floatArrayOf(0.08f, 0.25f, 0.42f, 0.58f, 0.72f, 0.86f, 1f)
        radar.valueStops.forEachIndexed { index, stop ->
            assertEquals(expectedAnchors[index], radar.normalize(stop), 1e-4f)
        }
        var previous = radar.normalize(5f)
        for (step in 1..600) {
            val value = 5f + step * 0.1f
            val current = radar.normalize(value)
            assertTrue("Expected monotonic increase at $value", current >= previous)
            previous = current
        }
    }

    @Test fun `threshold edge opacity ramps in smoothly above minimum stop`() {
        val stops = floatArrayOf(5f, 15f, 25f, 35f, 45f, 55f, 65f)
        assertEquals(0f, thresholdEdgeOpacity(4.9f, stops), 1e-4f)
        val atThreshold = thresholdEdgeOpacity(5f, stops)
        val midRamp = thresholdEdgeOpacity(7f, stops)
        val full = thresholdEdgeOpacity(9.5f, stops)
        assertTrue(atThreshold in 0.14f..0.16f)
        assertTrue(midRamp > atThreshold && midRamp < full)
        assertEquals(1f, full, 1e-4f)
    }
}
