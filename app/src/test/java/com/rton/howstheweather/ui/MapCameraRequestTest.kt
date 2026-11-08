package com.rton.howstheweather.ui

import com.rton.howstheweather.domain.DEFAULT_MAP_ZOOM
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.TargetLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraRequestTest {
    private val taipei = GeoPoint(25.0478, 121.5319)
    private val hualien = GeoPoint(23.9912, 121.6197)
    private val pannedTaipei = GeoPoint(25.0800, 121.5600)

    @Test
    fun `user clicking locate button animates back to device location on panned map`() {
        val deviceTarget = TargetLocation(taipei, "目前位置", true)
        val pannedPosition = MapCameraPosition(pannedTaipei, DEFAULT_MAP_ZOOM)

        val request = resolveCameraRequest(
            target = deviceTarget,
            lastCameraTarget = deviceTarget,
            recenterRequest = taipei,
            currentPosition = pannedPosition,
            mapLoaded = true,
            userInitiatedLocate = true,
            initialPositionSettled = true,
        )

        assertNotNull(request)
        assertEquals(taipei, request?.point)
        assertEquals(DEFAULT_MAP_ZOOM, request?.zoom ?: 0f, 0.001f)
        assertTrue("Clicking locate button must animate smoothly", request?.animate == true)
        assertEquals(DEFAULT_CAMERA_ANIMATION_DURATION_MS, request?.durationMs)
    }

    @Test
    fun `user clicking locate button animates from custom location back to device location`() {
        val customTarget = TargetLocation(hualien, "地圖選取位置", false)
        val deviceTarget = TargetLocation(taipei, "目前位置", true)
        val currentPosition = MapCameraPosition(hualien, DEFAULT_MAP_ZOOM)

        val request = resolveCameraRequest(
            target = deviceTarget,
            lastCameraTarget = customTarget,
            recenterRequest = null,
            currentPosition = currentPosition,
            mapLoaded = true,
            userInitiatedLocate = true,
            initialPositionSettled = true,
        )

        assertNotNull(request)
        assertEquals(taipei, request?.point)
        assertEquals(DEFAULT_MAP_ZOOM, request?.zoom ?: 0f, 0.001f)
        assertTrue("Switching back to device location via locate button must animate", request?.animate == true)
    }

    @Test
    fun `cold start auto locate does not animate across the map`() {
        val initialDefault = TargetLocation(taipei, "臺北市中心", false)
        val deviceTarget = TargetLocation(hualien, "目前位置", true)
        val initialPosition = MapCameraPosition(taipei, DEFAULT_MAP_ZOOM)

        val request = resolveCameraRequest(
            target = deviceTarget,
            lastCameraTarget = initialDefault,
            recenterRequest = null,
            currentPosition = initialPosition,
            mapLoaded = true,
            userInitiatedLocate = false,
            initialPositionSettled = false,
        )

        assertNotNull(request)
        assertEquals(hualien, request?.point)
        assertFalse("Cold start auto locate must snap without slow fly animation", request?.animate == true)
    }

    @Test
    fun `map not loaded never requests animated camera move`() {
        val deviceTarget = TargetLocation(taipei, "目前位置", true)
        val pannedPosition = MapCameraPosition(pannedTaipei, DEFAULT_MAP_ZOOM)

        val request = resolveCameraRequest(
            target = deviceTarget,
            lastCameraTarget = deviceTarget,
            recenterRequest = taipei,
            currentPosition = pannedPosition,
            mapLoaded = false,
            userInitiatedLocate = true,
            initialPositionSettled = true,
        )

        assertNotNull(request)
        assertFalse("Unloaded base map must not animate", request?.animate == true)
    }

    @Test
    fun `long pressing to select custom target animates camera`() {
        val deviceTarget = TargetLocation(taipei, "目前位置", true)
        val customTarget = TargetLocation(hualien, "地圖選取位置", false)
        val currentPosition = MapCameraPosition(taipei, DEFAULT_MAP_ZOOM)

        val request = resolveCameraRequest(
            target = customTarget,
            lastCameraTarget = deviceTarget,
            recenterRequest = null,
            currentPosition = currentPosition,
            mapLoaded = true,
            userInitiatedLocate = false,
            initialPositionSettled = true,
        )

        assertNotNull(request)
        assertEquals(hualien, request?.point)
        assertTrue("Selecting custom target must animate", request?.animate == true)
    }

    @Test
    fun `already centered at target does not issue redundant camera request`() {
        val deviceTarget = TargetLocation(taipei, "目前位置", true)
        val centeredPosition = MapCameraPosition(taipei, DEFAULT_MAP_ZOOM)

        val request = resolveCameraRequest(
            target = deviceTarget,
            lastCameraTarget = deviceTarget,
            recenterRequest = taipei,
            currentPosition = centeredPosition,
            mapLoaded = true,
            userInitiatedLocate = true,
            initialPositionSettled = true,
        )

        assertNull("Camera should not move if already centered", request)
    }
}
