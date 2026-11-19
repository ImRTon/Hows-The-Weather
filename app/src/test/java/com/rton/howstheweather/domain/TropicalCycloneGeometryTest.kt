package com.rton.howstheweather.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TropicalCycloneGeometryTest {
    private val radius = CycloneWindRadius(
        meanKm = 280f,
        quadrantsKm = mapOf(
            Quadrant.NE to 310f,
            Quadrant.SE to 290f,
            Quadrant.SW to 240f,
            Quadrant.NW to 270f,
        ),
    )

    @Test
    fun quadrantCentersUsePublishedRadii() {
        assertEquals(310f, radius.radiusTowardKm(45.0), 1e-3f)
        assertEquals(290f, radius.radiusTowardKm(135.0), 1e-3f)
        assertEquals(240f, radius.radiusTowardKm(225.0), 1e-3f)
        assertEquals(270f, radius.radiusTowardKm(315.0), 1e-3f)
    }

    @Test
    fun quadrantBoundariesBlendNeighbours() {
        assertEquals(300f, radius.radiusTowardKm(90.0), 1e-3f)
        assertEquals(290f, radius.radiusTowardKm(0.0), 1e-3f)
        assertEquals(290f, radius.radiusTowardKm(360.0), 1e-3f)
    }

    @Test
    fun radiusNeverLeavesPublishedRange() {
        for (bearing in 0 until 360) {
            val value = radius.radiusTowardKm(bearing.toDouble())
            assert(value in 240f..310f) { "bearing $bearing produced $value" }
        }
    }

    @Test
    fun meanRadiusIsUsedWithoutQuadrants() {
        val mean = CycloneWindRadius(meanKm = 120f)

        assertEquals(120f, mean.radiusTowardKm(200.0), 0f)
    }

    @Test
    fun outlineVerticesLieAtTheRequestedDistance() {
        val center = GeoPoint(18.1, 130.1)
        val outline = radius.outline(center, stepDegrees = 45)

        assertEquals(8, outline.size)
        assertEquals(310.0, greatCircleDistanceKm(center, outline[1]), .5)
        assertEquals(240.0, greatCircleDistanceKm(center, outline[5]), .5)
    }

    @Test
    fun activeCheckFiltersStaleAnalysisFixes() {
        val fixTime = Instant.parse("2026-11-11T06:00:00Z")
        val cyclone = TropicalCyclone(
            year = 2026,
            englishName = "CEMPAKA",
            cwaName = "查帕卡",
            typhoonNumber = 31,
            depressionNumber = 35,
            track = listOf(
                CycloneFix(
                    time = fixTime,
                    center = GeoPoint(28.9, 149.9),
                    maxWindMetersPerSecond = 30f,
                    maxGustMetersPerSecond = 38f,
                    pressureHectopascals = 975f,
                    movingSpeedKmPerHour = 54f,
                    movingDirection = "ENE",
                    movingPrediction = null,
                    galeRadius = null,
                    stormRadius = null,
                ),
            ),
            forecast = emptyList(),
            sourceId = "W-C0034-005",
        )

        assertTrue(cyclone.isActiveAt(fixTime.plus(Duration.ofHours(24))))
        assertFalse(cyclone.isActiveAt(fixTime.plus(Duration.ofHours(24).plusSeconds(1))))
        assertFalse(cyclone.isActiveAt(Instant.parse("2026-11-19T11:00:00Z")))
    }
}
