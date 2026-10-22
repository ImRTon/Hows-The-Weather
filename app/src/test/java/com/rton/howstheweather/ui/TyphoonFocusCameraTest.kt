package com.rton.howstheweather.ui

import com.rton.howstheweather.domain.CycloneFix
import com.rton.howstheweather.domain.CycloneForecastPoint
import com.rton.howstheweather.domain.CycloneWindRadius
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.TropicalCyclone
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TyphoonFocusCameraTest {
    private val taipei = GeoPoint(25.0478, 121.5319)

    @Test
    fun framesTargetAndStormBetweenThem() {
        val camera = typhoonFocusCamera(cyclone(GeoPoint(18.1, 130.1)), taipei, 400f, 500f)

        assertTrue(camera.point.longitude in 121.5..130.1)
        assertTrue(camera.point.latitude in 18.1..25.1)
        assertTrue(camera.zoom in 3f..8f)
    }

    @Test
    fun widerSpanZoomsOut() {
        val near = typhoonFocusCamera(cyclone(GeoPoint(22.0, 123.0)), taipei, 400f, 500f)
        val far = typhoonFocusCamera(cyclone(GeoPoint(12.0, 140.0)), taipei, 400f, 500f)

        assertTrue(far.zoom < near.zoom)
    }

    @Test
    fun centerCameraCentersOnTheStormAndZoomsOut() {
        val storm = cyclone(GeoPoint(21.0, 126.0))
        val camera = typhoonCenterCamera(storm, 400f, 500f)

        assertEquals(storm.latest.center, camera.point)
        assertTrue(camera.zoom in 3f..8f)
        assertTrue(camera.zoom < 8f)
    }

    @Test
    fun nearestCycloneIsChosenForTheTarget() {
        val far = cyclone(GeoPoint(12.0, 150.0), name = "遠")
        val near = cyclone(GeoPoint(20.0, 125.0), name = "近")

        assertEquals("近", nearestCyclone(listOf(far, near), taipei)?.cwaName)
    }

    private fun cyclone(center: GeoPoint, name: String = "測試"): TropicalCyclone {
        val time = Instant.parse("2026-10-21T00:00:00Z")
        return TropicalCyclone(
            year = 2026,
            englishName = "TEST",
            cwaName = name,
            typhoonNumber = 1,
            depressionNumber = 1,
            track = listOf(
                CycloneFix(
                    time = time,
                    center = center,
                    maxWindMetersPerSecond = 40f,
                    maxGustMetersPerSecond = null,
                    pressureHectopascals = 950f,
                    movingSpeedKmPerHour = null,
                    movingDirection = null,
                    movingPrediction = null,
                    galeRadius = CycloneWindRadius(250f),
                    stormRadius = CycloneWindRadius(100f),
                ),
            ),
            forecast = listOf(
                CycloneForecastPoint(
                    initialTime = time,
                    forecastHours = 24,
                    center = GeoPoint(center.latitude + 1.0, center.longitude - 1.0),
                    maxWindMetersPerSecond = 40f,
                    pressureHectopascals = 950f,
                    galeRadius = null,
                    stormRadius = null,
                    probability70RadiusKm = 90f,
                ),
            ),
            sourceId = "W-C0034-005",
        )
    }
}
