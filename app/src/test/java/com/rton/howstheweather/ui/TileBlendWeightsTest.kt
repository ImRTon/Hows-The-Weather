package com.rton.howstheweather.ui

import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileBlendWeightsTest {
    @Test fun `frame blend remains fully weighted throughout transition`() {
        listOf(0f, .25f, .5f, .75f, 1f).forEach { progress ->
            val weights = tileBlendWeights(frontSlot = 0, progress = progress)
            assertEquals(1f, weights.first + weights.second, .0001f)
            assertEquals(1f - progress, weights.first, .0001f)
            assertEquals(progress, weights.second, .0001f)
        }
    }

    @Test fun `back buffer becomes front without changing visible weights`() {
        val beforeSwap = tileBlendWeights(frontSlot = 0, progress = 1f)
        val afterSwap = tileBlendWeights(frontSlot = 1, progress = 0f)

        assertEquals(beforeSwap, afterSwap)
    }

    @Test fun `reloaded grid with the same frame identity skips the tile swap`() {
        val cached = grid(validAt = Instant.parse("2026-10-02T03:00:00Z"))
        val reloaded = grid(validAt = Instant.parse("2026-10-02T03:00:00Z"))
        val newer = grid(validAt = Instant.parse("2026-10-02T03:10:00Z"))

        assertTrue(cached.rendersSameFrameAs(reloaded))
        assertFalse(cached.rendersSameFrameAs(newer))
        assertFalse(cached.rendersSameFrameAs(reloaded.copy(sourceId = "O-A0059-001-regional")))
        assertFalse(cached.rendersSameFrameAs(reloaded.copy(bounds = GeoBounds(21.0, 119.0, 26.0, 123.0))))
    }

    @Test fun `coverage switch between wide and regional radar triggers crossfade`() {
        val time = Instant.parse("2026-10-02T03:00:00Z")
        val wide = grid(validAt = time).copy(
            sourceId = "O-A0058-005",
            bounds = GeoBounds(17.75, 115.0, 29.25, 126.5),
        )
        val regional = grid(validAt = time).copy(
            sourceId = "O-A0058-006",
            bounds = GeoBounds(20.5, 118.0, 26.5, 124.0),
        )
        val newerWide = wide.copy(validAt = Instant.parse("2026-10-02T03:10:00Z"))
        val rain = wide.copy(unit = WeatherUnit.MILLIMETERS_ONE_HOUR, sourceId = "F-B0046-001")

        assertTrue(wide.isCoverageTransitionTo(regional))
        assertTrue(regional.isCoverageTransitionTo(wide))
        assertFalse(wide.isCoverageTransitionTo(newerWide))
        assertFalse(wide.isCoverageTransitionTo(rain))
    }

    private fun grid(validAt: Instant) = WeatherGrid(
        width = 2,
        height = 2,
        values = floatArrayOf(0f, 1f, 2f, 3f),
        unit = WeatherUnit.DBZ,
        bounds = GeoBounds(21.0, 118.0, 26.0, 123.0),
        resolutionKm = 1.25,
        validAt = validAt,
        sourceId = "O-A0059-001",
    )
}
