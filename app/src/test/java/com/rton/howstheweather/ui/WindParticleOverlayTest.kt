package com.rton.howstheweather.ui

import androidx.compose.ui.geometry.Offset
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WindObservation
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindParticleOverlayTest {
    @Test
    fun `screen center remains the camera target`() {
        val result = screenToGeo(
            screen = Offset(540f, 960f),
            width = 1080f,
            height = 1920f,
            center = GeoPoint(25.04, 121.52),
            zoom = 12f,
            density = 3f,
        )

        assertEquals(25.04, result.latitude, 1e-6)
        assertEquals(121.52, result.longitude, 1e-6)
    }

    @Test
    fun `projection converts physical pixels using display density`() {
        val result = screenToGeo(
            screen = Offset(1_536f, 960f),
            width = 1_536f,
            height = 1920f,
            center = GeoPoint(0.0, 0.0),
            zoom = 10f,
            density = 3f,
        )

        // 768 physical pixels are one 256 dp tile at 3x density.
        assertEquals(360.0 / 1024.0, result.longitude, 1e-6)
        assertEquals(0.0, result.latitude, 1e-6)
    }

    @Test
    fun `wind particles become sparser as the map zooms in`() {
        assertTrue(windParticleSpacingDp(7f) < windParticleSpacingDp(12f))
        assertTrue(windParticleSpacingDp(12f) < windParticleSpacingDp(16f))
        assertEquals(26f, windParticleSpacingDp(3f), 0f)
        assertEquals(64f, windParticleSpacingDp(20f), 0f)
    }

    @Test
    fun `regional zoom keeps a dense particle lattice`() {
        // Taiwan-wide views (about zoom 7) previously spaced particles 46 dp apart.
        assertTrue(windParticleSpacingDp(7f) < 36f)
    }

    @Test
    fun `overscanned layout survives small gestures and rebuilds before edges show`() {
        val reference = WindParticleCamera(GeoPoint(24.0, 121.0), zoom = 8f)
        fun needsRebuild(current: WindParticleCamera) = windParticleLayoutNeedsRebuild(
            reference = reference,
            current = current,
            width = 1_080f,
            height = 1_200f,
            density = 3f,
        )

        assertFalse(needsRebuild(reference))
        assertFalse(needsRebuild(reference.copy(zoom = 7.8f)))
        assertTrue(needsRebuild(reference.copy(zoom = 7.5f)))
        assertTrue(needsRebuild(reference.copy(zoom = 8.5f)))
        // One screen width east at zoom 8 is far outside the 35% overscan.
        val oneScreenEast = 1_080.0 / (256.0 * 3.0 * 256.0) * 360.0
        assertTrue(needsRebuild(reference.copy(center = GeoPoint(24.0, 121.0 + oneScreenEast))))
        assertFalse(needsRebuild(reference.copy(center = GeoPoint(24.0, 121.0 + oneScreenEast * .1))))
    }

    @Test
    fun `wind particle stays anchored to the world while viewport pans`() {
        val before = worldAnchoredParticleOrigin(
            column = 123,
            row = 456,
            viewportLeft = 10_000.0,
            viewportTop = 20_000.0,
            spacing = 54f,
        )
        val after = worldAnchoredParticleOrigin(
            column = 123,
            row = 456,
            viewportLeft = 10_037.5,
            viewportTop = 19_976.0,
            spacing = 54f,
        )

        assertEquals(before.x - 37.5f, after.x, 0.001f)
        assertEquals(before.y + 24f, after.y, 0.001f)
        assertTrue(windParticleSeed(123, 456) != windParticleSeed(124, 456))
    }

    @Test
    fun `fractional zoom crossfades stable particle lattices`() {
        val beforeBoundary = windParticleLayoutLayers(11.999f)
        val afterBoundary = windParticleLayoutLayers(12.001f)

        assertEquals(12, beforeBoundary.maxBy { it.alpha }.zoom)
        assertEquals(12, afterBoundary.maxBy { it.alpha }.zoom)
        assertEquals(1f, beforeBoundary.sumOf { it.alpha.toDouble() }.toFloat(), .0001f)
        assertEquals(1f, afterBoundary.sumOf { it.alpha.toDouble() }.toFloat(), .0001f)
    }

    @Test
    fun `particle position scales continuously within a layout zoom`() {
        val base = worldAnchoredParticleOrigin(
            column = 123,
            row = 456,
            viewportLeft = 10_000.0,
            viewportTop = 20_000.0,
            spacing = 54f,
        )
        val enlarged = worldAnchoredParticleOrigin(
            column = 123,
            row = 456,
            viewportLeft = 10_000.0,
            viewportTop = 20_000.0,
            spacing = 54f,
            screenScale = 1.25f,
        )

        assertEquals(base.x * 1.25f, enlarged.x, .001f)
        assertEquals(base.y * 1.25f, enlarged.y, .001f)
    }

    @Test
    fun `moving camera reprojects settled particles without rebuilding their layout`() {
        val reference = WindParticleCamera(GeoPoint(0.0, 0.0), zoom = 10f)
        val onePhysicalPixelEast = GeoPoint(
            latitude = 0.0,
            longitude = 360.0 / (256.0 * 3.0 * 1024.0),
        )

        val pan = windParticleCameraTransform(
            reference = reference,
            current = WindParticleCamera(onePhysicalPixelEast, zoom = 10f),
            density = 3f,
        )
        val zoom = windParticleCameraTransform(
            reference = reference,
            current = WindParticleCamera(reference.center, zoom = 11f),
            density = 3f,
        )

        assertEquals(-1f, pan.translation.x, .001f)
        assertEquals(0f, pan.translation.y, .001f)
        assertEquals(1f, pan.scale, 0f)
        assertEquals(2f, zoom.scale, 0f)
        assertEquals(0f, zoom.translation.x, 0f)
        assertEquals(0f, zoom.translation.y, 0f)
    }

    @Test
    fun `wind particle fades in only at the start of its life`() {
        assertEquals(0f, windParticleAlpha(0f), 0f)
        assertEquals(1f, windParticleAlpha(.5f), 0f)
        assertEquals(1f, windParticleAlpha(.9f), 0f)
        assertEquals(1f, windParticleAlpha(1f), 0f)
    }

    @Test
    fun `wind particle shrinks smoothly at the end of its life`() {
        assertEquals(1f, windParticleScale(.5f), 0f)
        assertEquals(1f, windParticleScale(.78f), 0f)
        assertTrue(windParticleScale(.9f) in 0f..1f)
        assertTrue(windParticleScale(.9f) < windParticleScale(.8f))
        assertEquals(0f, windParticleScale(1f), 0f)
    }

    @Test
    fun `renderer caps observations to nearby stations`() {
        val center = GeoPoint(25.0, 121.5)
        val winds = (0 until 100).map { index ->
            WindObservation(
                stationName = "station-$index",
                coordinate = GeoPoint(25.0 + index * .01, 121.5),
                speedMetersPerSecond = 3f,
                directionDegrees = 90f,
                observedAt = Instant.EPOCH,
            )
        }

        val selected = selectRelevantWinds(winds, center, limit = 8)

        assertEquals(8, selected.size)
        assertEquals("station-0", selected.first().stationName)
        assertEquals("station-7", selected.last().stationName)
    }
}
