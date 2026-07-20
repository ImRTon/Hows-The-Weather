package com.rton.howstheweather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun `temporal interpolation blends numerical values and effective time`() {
        val next = grid.copy(
            values = floatArrayOf(10f, 20f, 30f, 40f),
            validAt = Instant.EPOCH.plusSeconds(600),
        )

        val halfway = grid.interpolateForDisplay(next, .5f)

        assertEquals(5f, halfway.valueAt(0, 0), .0001f)
        assertEquals(25f, halfway.valueAt(0, 1), .0001f)
        assertEquals(Instant.EPOCH.plusSeconds(300), halfway.validAt)
        assertEquals("fixture:temporal-interpolation", halfway.sourceId)
    }

    @Test fun `temporal interpolation preserves missing data`() {
        val missing = grid.copy(values = floatArrayOf(0f, Float.NaN, 20f, 30f))
        val next = grid.copy(
            values = floatArrayOf(10f, 10f, Float.NaN, 40f),
            validAt = Instant.EPOCH.plusSeconds(600),
        )

        val halfway = missing.interpolateForDisplay(next, .5f)

        assertEquals(5f, halfway.valueAt(0, 0), .0001f)
        assertTrue(halfway.valueAt(1, 0).isNaN())
        assertTrue(halfway.valueAt(0, 1).isNaN())
        assertEquals(35f, halfway.valueAt(1, 1), .0001f)
    }

    @Test fun `longitude wraps across antimeridian for satellite grids`() {
        val wrapped = grid.copy(bounds = GeoBounds(0.0, 60.0, 2.0, 240.0))
        assertEquals(
            wrapped.sample(GeoPoint(1.0, 181.0))!!,
            wrapped.sample(GeoPoint(1.0, -179.0))!!,
            .0001f,
        )
    }

    @Test fun `cloud coverage switches with zoom using hysteresis`() {
        val taiwan = GeoBounds(19.1, 115.9, 28.3, 126.1)
        val taipei = GeoPoint(25.0, 121.5)

        assertEquals(
            CloudCoverage.TAIWAN,
            CloudCoverageSelector.select(CloudCoverage.EAST_ASIA, 7.25f, taipei, taiwan),
        )
        assertEquals(
            CloudCoverage.TAIWAN,
            CloudCoverageSelector.select(CloudCoverage.TAIWAN, 7.0f, taipei, taiwan),
        )
        assertEquals(
            CloudCoverage.EAST_ASIA,
            CloudCoverageSelector.select(CloudCoverage.TAIWAN, 6.75f, taipei, taiwan),
        )
    }

    @Test fun `cloud coverage stays global outside taiwan product bounds`() {
        assertEquals(
            CloudCoverage.EAST_ASIA,
            CloudCoverageSelector.select(
                current = CloudCoverage.TAIWAN,
                zoom = 12f,
                center = GeoPoint(35.0, 139.0),
                taiwanBounds = GeoBounds(19.1, 115.9, 28.3, 126.1),
            ),
        )
    }

    @Test fun `radar coverage switches between wide and local numerical grids`() {
        val localBounds = GeoBounds(20.5, 118.0, 26.5, 124.0)
        val taipei = GeoPoint(25.0, 121.5)

        assertEquals(
            RadarCoverage.LOCAL,
            RadarCoverageSelector.select(RadarCoverage.WIDE, 7.25f, taipei, localBounds),
        )
        assertEquals(
            RadarCoverage.LOCAL,
            RadarCoverageSelector.select(RadarCoverage.LOCAL, 7.0f, taipei, localBounds),
        )
        assertEquals(
            RadarCoverage.WIDE,
            RadarCoverageSelector.select(RadarCoverage.LOCAL, 12f, GeoPoint(35.0, 139.0), localBounds),
        )
    }
}
