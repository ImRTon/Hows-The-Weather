package com.rton.howstheweather.data

import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import com.rton.howstheweather.domain.WindObservation
import java.time.Instant
import kotlin.math.exp
import kotlin.math.sin

class DemoWeatherDataSource : WeatherDataSource {
    override suspend fun load(target: GeoPoint): WeatherSnapshot {
        val now = Instant.now()
        val radar = grid(WeatherUnit.DBZ, now, 0)
        val forecasts = (0..60 step 10).map { minute ->
            grid(WeatherUnit.MILLIMETERS_PER_HOUR, now.plusSeconds(minute * 60L), minute)
        }
        return WeatherSnapshot(
            radar = radar,
            rainForecast = forecasts,
            cloudFrames = (0..60 step 10).map { minute -> cloudGrid(now.plusSeconds(minute * 60L), minute) },
            forecastAtTarget = forecasts.mapIndexed { index, weatherGrid ->
                ForecastPoint(index * 10, weatherGrid.sample(target))
            },
            winds = listOf(
                wind("臺北", 25.04, 121.52, 4.2f, 55f, now),
                wind("新竹", 24.80, 120.97, 6.1f, 38f, now),
                wind("臺中", 24.15, 120.68, 3.6f, 22f, now),
                wind("嘉義", 23.48, 120.44, 2.8f, 290f, now),
                wind("高雄", 22.63, 120.30, 5.0f, 260f, now),
                wind("花蓮", 23.98, 121.61, 3.1f, 78f, now),
            ),
            issuedAt = now,
            isDemo = true,
        )
    }

    private fun grid(unit: WeatherUnit, validAt: Instant, minute: Int): WeatherGrid {
        val width = 96
        val height = 132
        val values = FloatArray(width * height)
        val shift = minute / 60f * 18f
        for (y in 0 until height) for (x in 0 until width) {
            val cellA = gaussian(x, y, 54f + shift, 29f + shift * .25f, 15f, 22f)
            val cellB = gaussian(x, y, 37f + shift * .5f, 86f - shift * .15f, 12f, 17f)
            val texture = (sin(x * .31f) + sin(y * .19f)) * 0.8f
            values[y * width + x] = when (unit) {
                WeatherUnit.DBZ -> ((cellA * 58f + cellB * 42f) + texture).coerceAtLeast(0f)
                WeatherUnit.MILLIMETERS_PER_HOUR -> ((cellA * 48f + cellB * 18f) + texture * .2f).coerceAtLeast(0f)
                WeatherUnit.MILLIMETERS_ONE_HOUR -> 0f
                WeatherUnit.LUMINANCE -> 0f
            }
        }
        return WeatherGrid(
            width, height, values, unit,
            GeoBounds(20.8, 118.0, 26.4, 123.2),
            resolutionKm = 1.25,
            validAt = validAt,
            sourceId = if (unit == WeatherUnit.DBZ) "DEMO-O-A0059-001" else "DEMO-F-B0046-001",
        )
    }

    private fun cloudGrid(validAt: Instant, minute: Int): WeatherGrid {
        val width = 80
        val height = 110
        val values = FloatArray(width * height)
        val shift = minute / 10f * 1.6f
        for (y in 0 until height) for (x in 0 until width) {
            val band = gaussian(x, y, 42f + shift, 44f + shift * .3f, 26f, 12f)
            val tower = gaussian(x, y, 59f + shift * .7f, 72f, 11f, 19f)
            values[y * width + x] =
                (band * .72f + tower * .55f + sin(x * .21f + y * .13f) * .06f).coerceIn(0f, 1f)
        }
        return WeatherGrid(
            width, height, values, WeatherUnit.LUMINANCE,
            GeoBounds(20.8, 118.0, 26.4, 123.2), 2.2, validAt = validAt,
            sourceId = "DEMO-CLOUD-OPTICAL-FLOW",
        )
    }

    private fun gaussian(x: Int, y: Int, cx: Float, cy: Float, sx: Float, sy: Float): Float =
        exp(-(((x - cx) * (x - cx)) / (2f * sx * sx) + ((y - cy) * (y - cy)) / (2f * sy * sy)))

    private fun wind(name: String, lat: Double, lon: Double, speed: Float, direction: Float, at: Instant) =
        WindObservation(name, GeoPoint(lat, lon), speed, direction, at)
}
