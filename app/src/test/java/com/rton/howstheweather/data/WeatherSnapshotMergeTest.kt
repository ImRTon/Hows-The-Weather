package com.rton.howstheweather.data

import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WeatherSnapshotMergeTest {
    @Test fun `cloud update preserves decision forecast that completed after its base snapshot`() {
        val staleForecast = grid(
            unit = WeatherUnit.MILLIMETERS_ONE_HOUR,
            validAt = Instant.parse("2026-07-24T14:20:00Z"),
            values = floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN),
        )
        val latestForecast = grid(
            unit = WeatherUnit.MILLIMETERS_ONE_HOUR,
            validAt = Instant.parse("2026-07-24T14:40:00Z"),
            values = floatArrayOf(2f, 2f, 2f, 2f),
        )
        val cloudUpdateFromOldBase = snapshot(staleForecast, Float.NaN)
        val current = snapshot(latestForecast, 2f)

        val merged = cloudUpdateFromOldBase.preserveDecisionForecastFrom(current)

        assertSame(latestForecast, merged.rainForecast.single())
        assertEquals(2f, merged.hourlyAccumulationAtTarget!!, 0f)
        assertEquals(current.forecastAtTarget, merged.forecastAtTarget)
        assertEquals(current.issuedAt, merged.issuedAt)
    }

    @Test fun `supplemental update without an existing decision keeps its own forecast fields`() {
        val incomingForecast = grid(
            unit = WeatherUnit.MILLIMETERS_ONE_HOUR,
            validAt = Instant.parse("2026-07-24T14:40:00Z"),
            values = floatArrayOf(1f, 1f, 1f, 1f),
        )
        val incoming = snapshot(incomingForecast, 1f)
        val currentWithoutDecision = snapshot(null, null)

        val merged = incoming.preserveDecisionForecastFrom(currentWithoutDecision)

        assertSame(incomingForecast, merged.rainForecast.single())
        assertEquals(1f, merged.hourlyAccumulationAtTarget!!, 0f)
    }

    private fun snapshot(forecast: WeatherGrid?, hourlyAmount: Float?) = WeatherSnapshot(
        radar = grid(
            unit = WeatherUnit.DBZ,
            validAt = Instant.parse("2026-07-24T14:40:00Z"),
            values = floatArrayOf(5f, 5f, 5f, 5f),
        ),
        rainForecast = listOfNotNull(forecast),
        cloudFrames = emptyList(),
        forecastAtTarget = forecast?.let { listOf(ForecastPoint(60, hourlyAmount)) }.orEmpty(),
        winds = emptyList(),
        issuedAt = forecast?.validAt ?: Instant.parse("2026-07-24T14:40:00Z"),
        hourlyAccumulationAtTarget = hourlyAmount,
    )

    private fun grid(
        unit: WeatherUnit,
        validAt: Instant,
        values: FloatArray,
    ) = WeatherGrid(
        width = 2,
        height = 2,
        values = values,
        unit = unit,
        bounds = GeoBounds(20.0, 120.0, 20.1, 120.1),
        resolutionKm = 1.25,
        validAt = validAt,
        sourceId = "fixture",
    )
}
