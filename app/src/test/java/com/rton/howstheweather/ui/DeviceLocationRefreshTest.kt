package com.rton.howstheweather.ui

import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.TargetLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLocationRefreshTest {
    private val taipei = GeoPoint(25.0478, 121.5319)

    @Test fun `first device fix refreshes a non-device target`() {
        val current = TargetLocation(taipei, "臺北市中心", false)

        assertTrue(shouldRefreshDeviceTarget(current, taipei))
    }

    @Test fun `small GPS drift only recenters without refreshing weather`() {
        val current = TargetLocation(taipei, "目前位置", true)
        val aboutFiftyMetersNorth = GeoPoint(25.04825, 121.5319)

        assertFalse(shouldRefreshDeviceTarget(current, aboutFiftyMetersNorth))
    }

    @Test fun `meaningful device movement refreshes location weather`() {
        val current = TargetLocation(taipei, "目前位置", true)
        val aboutTwoHundredMetersNorth = GeoPoint(25.0496, 121.5319)

        assertTrue(shouldRefreshDeviceTarget(current, aboutTwoHundredMetersNorth))
    }
}
