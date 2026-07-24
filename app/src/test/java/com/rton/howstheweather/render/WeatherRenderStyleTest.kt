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
}
