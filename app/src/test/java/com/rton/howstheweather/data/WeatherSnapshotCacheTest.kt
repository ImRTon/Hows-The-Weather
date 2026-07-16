package com.rton.howstheweather.data

import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import com.rton.howstheweather.domain.WindObservation
import com.rton.howstheweather.domain.WindProvenance
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WeatherSnapshotCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `round trip preserves official numerical grids and missing values`() = runTest {
        val issuedAt = Instant.parse("2026-07-12T01:00:00Z")
        val cache = WeatherSnapshotCache(temporaryFolder.newFolder(), Duration.ofHours(6))
        cache.write(snapshot(issuedAt))

        val restored = cache.read(issuedAt.plusSeconds(60))!!

        assertEquals("O-A0059-001", restored.radar.sourceId)
        assertTrue(restored.radar.valueAt(1, 0).isNaN())
        assertEquals(WeatherUnit.MILLIMETERS_ONE_HOUR, restored.rainForecast.single().unit)
        assertEquals(1, restored.winds.size)
        assertEquals(3.5f, restored.hourlyAccumulationAtTarget!!, 0f)
    }

    @Test fun `expired snapshot is not returned`() = runTest {
        val issuedAt = Instant.parse("2026-07-12T01:00:00Z")
        val cache = WeatherSnapshotCache(temporaryFolder.newFolder(), Duration.ofMinutes(30))
        cache.write(snapshot(issuedAt))

        assertNull(cache.read(issuedAt.plus(Duration.ofMinutes(31))))
    }

    @Test fun `cache preserves a full official station observation set`() = runTest {
        val issuedAt = Instant.parse("2026-07-12T01:00:00Z")
        val cache = WeatherSnapshotCache(temporaryFolder.newFolder(), Duration.ofHours(6))
        val winds = List(900) { index ->
            WindObservation(
                stationName = "測站 $index",
                coordinate = GeoPoint(22.0 + index / 10_000.0, 120.0 + index / 10_000.0),
                speedMetersPerSecond = 2f,
                directionDegrees = 90f,
                observedAt = issuedAt,
            )
        }

        cache.write(snapshot(issuedAt).copy(winds = winds))

        assertEquals(900, cache.read(issuedAt.plusSeconds(60))!!.winds.size)
    }

    @Test fun `cache preserves cropped WRF wind grid`() = runTest {
        val issuedAt = Instant.parse("2026-07-12T06:00:00Z")
        val cache = WeatherSnapshotCache(temporaryFolder.newFolder(), Duration.ofHours(6))
        val windGrid = LambertGridDefinition(
            width = 2,
            height = 2,
            earthRadiusMeters = 6_371_229.0,
            latitudeOfFirstPoint = 22.0,
            longitudeOfFirstPoint = 120.0,
            latitudeOfOrigin = 10.0,
            centralLongitude = 120.0,
            spacingXMeters = 3_000.0,
            spacingYMeters = 3_000.0,
            firstStandardParallel = 10.0,
            secondStandardParallel = 40.0,
        ).combine(
            east = floatArrayOf(1f, 2f, 3f, 4f),
            north = floatArrayOf(5f, 6f, 7f, 8f),
            validAt = issuedAt,
            sourceId = "M-A0064-000-WRF-3KM-10M-WIND",
        )
        cache.write(
            snapshot(issuedAt).copy(
                windGrid = windGrid,
                winds = emptyList(),
                windProvenance = WindProvenance.MODEL,
            ),
        )

        val restored = cache.read(issuedAt.plusSeconds(60))!!
        val restoredWind = restored.windGrid!!

        assertEquals(WindProvenance.MODEL, restored.windProvenance)
        assertEquals(2, restoredWind.width)
        assertEquals(1f, restoredWind.sample(GeoPoint(22.0, 120.0))!!.eastMetersPerSecond, 0.001f)
    }

    private fun snapshot(issuedAt: Instant): WeatherSnapshot {
        val radar = grid(WeatherUnit.DBZ, "O-A0059-001", issuedAt, floatArrayOf(1f, Float.NaN, 3f, 4f))
        val forecast = grid(
            WeatherUnit.MILLIMETERS_ONE_HOUR,
            "F-B0046-001",
            issuedAt.plusSeconds(3600),
            floatArrayOf(1f, 2f, 3f, 4f),
        )
        return WeatherSnapshot(
            radar = radar,
            radarRegional = radar,
            rainForecast = listOf(forecast),
            cloudFrames = emptyList(),
            forecastAtTarget = listOf(ForecastPoint(60, 3.5f)),
            winds = listOf(WindObservation("臺北", GeoPoint(25.0, 121.5), 2f, 90f, issuedAt)),
            issuedAt = issuedAt,
            hourlyAccumulationAtTarget = 3.5f,
        )
    }

    private fun grid(unit: WeatherUnit, sourceId: String, validAt: Instant, values: FloatArray) = WeatherGrid(
        width = 2,
        height = 2,
        values = values,
        unit = unit,
        bounds = GeoBounds(20.0, 120.0, 20.1, 120.1),
        resolutionKm = 1.25,
        validAt = validAt,
        sourceId = sourceId,
    )
}
