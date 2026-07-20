package com.rton.howstheweather.domain

import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeUiStateTest {
    @Test fun `new state keeps location weather pending until the first request completes`() {
        val state = HomeUiState(
            target = TargetLocation(GeoPoint(25.0478, 121.5319), "臺北市中心", false),
            decision = ForecastDecisionEngine().evaluate(
                series = listOf(ForecastPoint(0, null)),
                issuedAt = Instant.parse("2026-07-17T00:00:00Z"),
            ),
        )

        assertTrue(state.currentWeatherLoading)
        assertTrue(state.areaForecastLoading)
        assertTrue(state.weeklyForecastLoading)
        assertFalse(state.isPlaybackPending)
        assertFalse(state.isHistoryLoading)
    }
}
