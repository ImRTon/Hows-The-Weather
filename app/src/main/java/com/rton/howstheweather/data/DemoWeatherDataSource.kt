package com.rton.howstheweather.data

import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.AreaForecastPeriod
import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import com.rton.howstheweather.domain.WindObservation
import com.rton.howstheweather.domain.WindProvenance
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.sin

class DemoWeatherDataSource : WeatherDataSource, SupplementaryWeatherDataSource {
    override suspend fun load(target: GeoPoint): WeatherSnapshot = withContext(DEMO_COMPUTE_DISPATCHER) {
        val now = Instant.now()
        val supplements = createSupplements(now)
        val radarFullResolution = grid(WeatherUnit.DBZ, now, 0)
        val radar = radarFullResolution.downsampled(256)
        val radarRegional = radarFullResolution.croppedTo(GeoBounds(20.5, 118.0, 26.5, 124.0))
        val radarFramesFullResolution = (
            -OBSERVATION_HISTORY_MINUTES until 0 step OBSERVATION_FRAME_INTERVAL_MINUTES
        ).map { minute ->
            grid(WeatherUnit.DBZ, now.plusSeconds(minute * 60L), minute)
        }
        val forecasts = (0..60 step 10).map { minute ->
            grid(WeatherUnit.MILLIMETERS_PER_HOUR, now.plusSeconds(minute * 60L), minute)
        }
        val hourlyAccumulation = grid(WeatherUnit.MILLIMETERS_ONE_HOUR, now.plusSeconds(60 * 60L), 60)
        val quantitativeForecasts = (1..4).map { period ->
            grid(
                WeatherUnit.MILLIMETERS_TWELVE_HOURS,
                now.plusSeconds(period * 12L * 60L * 60L),
                period * 45,
            )
        }
        WeatherSnapshot(
            radar = radar,
            radarRegional = radarRegional,
            radarFrames = radarFramesFullResolution.map { it.downsampled(256) },
            radarRegionalFrames = radarFramesFullResolution.mapNotNull {
                it.croppedTo(GeoBounds(20.5, 118.0, 26.5, 124.0))
            },
            rainForecast = listOf(hourlyAccumulation) + forecasts,
            quantitativeForecastFrames = quantitativeForecasts,
            cloudFrames = supplements.cloudFrames,
            cloudRegionalFrames = supplements.cloudRegionalFrames,
            forecastAtTarget = forecasts.mapIndexed { index, weatherGrid ->
                ForecastPoint(index * 10, weatherGrid.sample(target))
            },
            winds = supplements.winds,
            windsAreDemo = true,
            windProvenance = WindProvenance.DEMO,
            issuedAt = now,
            isDemo = true,
            areaForecast = areaForecast(target, now),
        )
    }

    override suspend fun loadAreaForecast(target: GeoPoint): AreaForecast =
        areaForecast(target, Instant.now())

    override suspend fun loadWeeklyForecast(target: GeoPoint): AreaForecast =
        weeklyForecast(target, Instant.now())

    override suspend fun loadSupplements(): WeatherSupplements = withContext(DEMO_COMPUTE_DISPATCHER) {
        createSupplements(Instant.now())
    }

    private fun areaForecast(target: GeoPoint, now: Instant): AreaForecast {
        val localHour = now.atZone(TAIPEI_ZONE).withMinute(0).withSecond(0).withNano(0)
        val firstStart = localHour.plusHours(((3 - localHour.hour % 3) % 3).toLong())
        val descriptions = listOf("多雲", "短暫陣雨", "陰", "多雲時晴", "晴", "晴時多雲", "多雲", "短暫陣雨")
        return AreaForecast(
            target = target,
            areaCoordinate = target,
            countyName = "",
            districtName = "目標附近",
            periods = descriptions.mapIndexed { index, description ->
                val start = firstStart.plusHours(index * 3L).toInstant()
                val baseTemperature = 29 - (index % 4)
                AreaForecastPeriod(
                    startAt = start,
                    endAt = start.plusSeconds(3 * 60 * 60L),
                    precipitationProbabilityPercent = listOf(20, 40, 30, 20, 10, 20, 30, 50)[index],
                    minimumTemperatureCelsius = baseTemperature - 1,
                    maximumTemperatureCelsius = baseTemperature + 1,
                    weatherDescription = description,
                    weatherCode = when {
                        "雨" in description -> "08"
                        "晴" in description -> "02"
                        description == "陰" -> "07"
                        else -> "04"
                    },
                    relativeHumidityPercent = 68 + index % 4 * 4,
                )
            },
            sourceId = "DEMO-F-D0047",
            isDemo = true,
        )
    }

    private fun weeklyForecast(target: GeoPoint, now: Instant): AreaForecast {
        val firstStart = now.atZone(TAIPEI_ZONE).toLocalDate().atTime(6, 0).atZone(TAIPEI_ZONE)
        val descriptions = listOf("晴時多雲", "多雲", "多雲短暫陣雨", "陰短暫雨")
        return AreaForecast(
            target = target,
            areaCoordinate = target,
            countyName = "",
            districtName = "目標附近",
            periods = (0 until 14).map { index ->
                val start = firstStart.plusHours(index * 12L).toInstant()
                val daytime = index % 2 == 0
                val description = descriptions[(index / 2) % descriptions.size]
                AreaForecastPeriod(
                    startAt = start,
                    endAt = start.plusSeconds(12 * 60 * 60L),
                    precipitationProbabilityPercent = listOf(20, 30, 40, 50)[(index / 2) % 4],
                    minimumTemperatureCelsius = if (daytime) 27 else 25,
                    maximumTemperatureCelsius = if (daytime) 34 else 29,
                    weatherDescription = description,
                    weatherCode = if ("雨" in description) "08" else "03",
                    relativeHumidityPercent = if (daytime) 68 else 82,
                )
            },
            sourceId = "DEMO-F-D0047-WEEK",
            isDemo = true,
        )
    }

    private fun createSupplements(now: Instant) = WeatherSupplements(
        cloudFrames = (
            -OBSERVATION_HISTORY_MINUTES..0 step OBSERVATION_FRAME_INTERVAL_MINUTES
        ).map { minute -> cloudGrid(now.plusSeconds(minute * 60L), minute) },
        cloudRegionalFrames = (
            -OBSERVATION_HISTORY_MINUTES..0 step OBSERVATION_FRAME_INTERVAL_MINUTES
        ).map { minute ->
            regionalCloudGrid(now.plusSeconds(minute * 60L), minute)
        },
        winds = listOf(
            wind("臺北", 25.04, 121.52, 4.2f, 55f, now),
            wind("新竹", 24.80, 120.97, 6.1f, 38f, now),
            wind("臺中", 24.15, 120.68, 3.6f, 22f, now),
            wind("嘉義", 23.48, 120.44, 2.8f, 290f, now),
            wind("高雄", 22.63, 120.30, 5.0f, 260f, now),
            wind("花蓮", 23.98, 121.61, 3.1f, 78f, now),
        ),
    )

    private fun grid(unit: WeatherUnit, validAt: Instant, minute: Int): WeatherGrid {
        val width = if (unit == WeatherUnit.DBZ) 920 else 96
        val height = if (unit == WeatherUnit.DBZ) 920 else 132
        val values = FloatArray(width * height)
        val scaleX = width / 96f
        val scaleY = height / 132f
        val shift = minute / 60f * 18f * scaleX
        for (y in 0 until height) for (x in 0 until width) {
            val cellA = gaussian(
                x, y, 54f * scaleX + shift, 29f * scaleY + shift * .25f,
                15f * scaleX, 22f * scaleY,
            )
            val cellB = gaussian(
                x, y, 37f * scaleX + shift * .5f, 86f * scaleY - shift * .15f,
                12f * scaleX, 17f * scaleY,
            )
            val texture = (sin(x / scaleX * .31f) + sin(y / scaleY * .19f)) * 0.8f
            values[y * width + x] = when (unit) {
                WeatherUnit.DBZ -> ((cellA * 58f + cellB * 42f) + texture).coerceAtLeast(0f)
                WeatherUnit.MILLIMETERS_PER_HOUR,
                WeatherUnit.MILLIMETERS_ONE_HOUR -> ((cellA * 48f + cellB * 18f) + texture * .2f).coerceAtLeast(0f)
                WeatherUnit.MILLIMETERS_TWELVE_HOURS ->
                    ((cellA * 180f + cellB * 72f) + texture).coerceAtLeast(0f)
                WeatherUnit.LUMINANCE -> 0f
            }
        }
        val bounds = if (unit == WeatherUnit.DBZ) {
            GeoBounds(17.75, 115.0, 29.25, 126.5)
        } else {
            GeoBounds(20.8, 118.0, 26.4, 123.2)
        }
        return WeatherGrid(
            width, height, values, unit,
            bounds,
            resolutionKm = if (unit == WeatherUnit.DBZ) 1.4 else 1.25,
            validAt = validAt,
            sourceId = when (unit) {
                WeatherUnit.DBZ -> "DEMO-O-A0058-005"
                WeatherUnit.MILLIMETERS_TWELVE_HOURS -> "DEMO-F-C0035"
                else -> "DEMO-F-B0046-001"
            },
        )
    }

    private fun cloudGrid(validAt: Instant, minute: Int): WeatherGrid {
        val width = 360
        val height = 360
        val values = FloatArray(width * height)
        val shift = minute / 10f * 2.1f
        for (y in 0 until height) for (x in 0 until width) {
            val band = gaussian(x, y, 124f + shift, 132f + shift * .3f, 70f, 22f)
            val tower = gaussian(x, y, 92f + shift * .7f, 165f, 25f, 38f)
            val pacificBand = gaussian(x, y, 250f - shift * .25f, 205f, 90f, 28f)
            values[y * width + x] =
                (band * .72f + tower * .55f + pacificBand * .42f + sin(x * .12f + y * .08f) * .045f)
                    .coerceIn(0f, 1f)
        }
        return WeatherGrid(
            width, height, values, WeatherUnit.LUMINANCE,
            GeoBounds(0.0, 102.0, 50.0, 155.0), 16.0, validAt = validAt,
            sourceId = "DEMO-CLOUD-OBSERVED",
        )
    }

    private fun regionalCloudGrid(validAt: Instant, minute: Int): WeatherGrid {
        val width = 320
        val height = 292
        val values = FloatArray(width * height)
        val shift = minute / 10f * 2.4f
        for (y in 0 until height) for (x in 0 until width) {
            val northernBand = gaussian(x, y, 146f + shift, 72f + shift * .25f, 94f, 25f)
            val taiwanCell = gaussian(x, y, 184f + shift * .6f, 142f, 34f, 48f)
            val southernBand = gaussian(x, y, 94f - shift * .2f, 226f, 82f, 28f)
            values[y * width + x] = (
                northernBand * .64f + taiwanCell * .48f + southernBand * .36f +
                    sin(x * .15f + y * .11f) * .04f
                ).coerceIn(0f, 1f)
        }
        return WeatherGrid(
            width = width,
            height = height,
            values = values,
            unit = WeatherUnit.LUMINANCE,
            bounds = GeoBounds(19.100625745, 115.976888855, 28.29937425, 126.02300114),
            resolutionKm = 3.5,
            validAt = validAt,
            sourceId = "DEMO-CLOUD-TAIWAN-OBSERVED",
        )
    }

    private fun gaussian(x: Int, y: Int, cx: Float, cy: Float, sx: Float, sy: Float): Float =
        exp(-(((x - cx) * (x - cx)) / (2f * sx * sx) + ((y - cy) * (y - cy)) / (2f * sy * sy)))

    private fun wind(name: String, lat: Double, lon: Double, speed: Float, direction: Float, at: Instant) =
        WindObservation(name, GeoPoint(lat, lon), speed, direction, at)

    private companion object {
        val DEMO_COMPUTE_DISPATCHER = Dispatchers.Default.limitedParallelism(1)
        val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")
    }
}
