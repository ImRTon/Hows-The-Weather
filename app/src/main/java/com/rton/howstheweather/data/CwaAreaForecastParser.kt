package com.rton.howstheweather.data

import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.AreaForecastPeriod
import com.rton.howstheweather.domain.GeoPoint
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

data class CwaForecastLocation(
    val name: String,
    val coordinate: GeoPoint,
)

/** Parses CWA D0047 administrative-area forecasts without changing their native time resolution. */
class CwaAreaForecastParser {
    fun locations(json: String): List<CwaForecastLocation> = locations(JSONObject(json))

    fun nearestForecast(
        json: String,
        target: GeoPoint,
        sourceId: String,
        countyName: String,
        now: Instant = Instant.now(),
    ): AreaForecast? {
        val root = JSONObject(json)
        val location = locationObjects(root)
            .minByOrNull { distanceKm(target, it.coordinate()) }
            ?: return null
        val areaCoordinate = location.coordinate()
        if (distanceKm(target, areaCoordinate) > MAX_DISTRICT_DISTANCE_KM) return null

        val elements = objects(location.opt("WeatherElement"))
            .associateBy { it.optString("ElementName") }
        val temperatures = elements["溫度"]?.let { hourlyValues(it, "Temperature") }.orEmpty()
        val humidity = elements["相對濕度"]?.let { hourlyValues(it, "RelativeHumidity") }.orEmpty()
        val weather = elements["天氣現象"]?.let(::weatherPeriods).orEmpty()
        val probability = listOf("3小時降雨機率", "6小時降雨機率", "12小時降雨機率")
            .firstNotNullOfOrNull(elements::get)
            ?: return null
        val periods = objects(probability.opt("Time")).mapNotNull { time ->
            val start = time.instant("StartTime") ?: return@mapNotNull null
            val end = time.instant("EndTime") ?: return@mapNotNull null
            if (end <= now) return@mapNotNull null
            val values = objects(time.opt("ElementValue")).firstOrNull()
            val temperaturesInPeriod = temperatures
                .filterKeys { it >= start && it < end }
                .values
            val humidityInPeriod = humidity
                .filterKeys { it >= start && it < end }
                .values
            val condition = weather[start to end]
            AreaForecastPeriod(
                startAt = start,
                endAt = end,
                precipitationProbabilityPercent = values
                    ?.optString("ProbabilityOfPrecipitation")
                    ?.toIntOrNull(),
                minimumTemperatureCelsius = temperaturesInPeriod.minOrNull(),
                maximumTemperatureCelsius = temperaturesInPeriod.maxOrNull(),
                weatherDescription = condition?.first.orEmpty(),
                weatherCode = condition?.second,
                relativeHumidityPercent = humidityInPeriod.averageOrNull(),
            )
        }.sortedBy(AreaForecastPeriod::startAt).take(MAX_THREE_DAY_PERIODS)
        if (periods.isEmpty()) return null

        return AreaForecast(
            target = target,
            areaCoordinate = areaCoordinate,
            countyName = countyName,
            districtName = location.optString("LocationName"),
            periods = periods,
            sourceId = sourceId,
        )
    }

    fun nearestWeeklyForecast(
        json: String,
        target: GeoPoint,
        sourceId: String,
        countyName: String,
        now: Instant = Instant.now(),
    ): AreaForecast? {
        val root = JSONObject(json)
        val location = locationObjects(root)
            .minByOrNull { distanceKm(target, it.coordinate()) }
            ?: return null
        val areaCoordinate = location.coordinate()
        if (distanceKm(target, areaCoordinate) > MAX_DISTRICT_DISTANCE_KM) return null

        val elements = objects(location.opt("WeatherElement"))
            .associateBy { it.optString("ElementName") }
        val probability = elements["12小時降雨機率"] ?: return null
        val minimums = elements["最低溫度"]?.let { periodValues(it, "MinTemperature") }.orEmpty()
        val maximums = elements["最高溫度"]?.let { periodValues(it, "MaxTemperature") }.orEmpty()
        val humidity = elements["平均相對濕度"]?.let { periodValues(it, "RelativeHumidity") }.orEmpty()
        val weather = elements["天氣現象"]?.let(::weatherPeriods).orEmpty()

        val periods = objects(probability.opt("Time")).mapNotNull { time ->
            val start = time.instant("StartTime") ?: return@mapNotNull null
            val end = time.instant("EndTime") ?: return@mapNotNull null
            if (end <= now) return@mapNotNull null
            val value = objects(time.opt("ElementValue")).firstOrNull()
            val key = start to end
            val condition = weather[key]
            AreaForecastPeriod(
                startAt = start,
                endAt = end,
                precipitationProbabilityPercent = value
                    ?.optString("ProbabilityOfPrecipitation")
                    ?.toIntOrNull(),
                minimumTemperatureCelsius = minimums[key],
                maximumTemperatureCelsius = maximums[key],
                weatherDescription = condition?.first.orEmpty(),
                weatherCode = condition?.second,
                relativeHumidityPercent = humidity[key],
            )
        }.sortedBy(AreaForecastPeriod::startAt).take(MAX_WEEKLY_PERIODS)
        if (periods.isEmpty()) return null

        return AreaForecast(
            target = target,
            areaCoordinate = areaCoordinate,
            countyName = countyName,
            districtName = location.optString("LocationName"),
            periods = periods,
            sourceId = sourceId,
        )
    }

    fun distanceKm(first: GeoPoint, second: GeoPoint): Double {
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(second.longitude - first.longitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun locations(root: JSONObject): List<CwaForecastLocation> = locationObjects(root).map {
        CwaForecastLocation(it.optString("LocationName"), it.coordinate())
    }

    private fun locationObjects(root: JSONObject): List<JSONObject> {
        val records = root.getJSONObject("records")
        return objects(records.opt("Locations")).flatMap { group -> objects(group.opt("Location")) }
    }

    private fun hourlyValues(element: JSONObject, valueName: String): Map<Instant, Int> =
        objects(element.opt("Time")).mapNotNull { time ->
            val at = time.instant("DataTime") ?: return@mapNotNull null
            val value = objects(time.opt("ElementValue")).firstOrNull()
                ?.optString(valueName)
                ?.toIntOrNull()
                ?: return@mapNotNull null
            at to value
        }.toMap()

    private fun periodValues(element: JSONObject, valueName: String): Map<Pair<Instant, Instant>, Int> =
        objects(element.opt("Time")).mapNotNull { time ->
            val start = time.instant("StartTime") ?: return@mapNotNull null
            val end = time.instant("EndTime") ?: return@mapNotNull null
            val value = objects(time.opt("ElementValue")).firstOrNull()
                ?.optString(valueName)
                ?.toIntOrNull()
                ?: return@mapNotNull null
            (start to end) to value
        }.toMap()

    private fun weatherPeriods(element: JSONObject): Map<Pair<Instant, Instant>, Pair<String, String?>> =
        objects(element.opt("Time")).mapNotNull { time ->
            val start = time.instant("StartTime") ?: return@mapNotNull null
            val end = time.instant("EndTime") ?: return@mapNotNull null
            val value = objects(time.opt("ElementValue")).firstOrNull() ?: return@mapNotNull null
            (start to end) to (value.optString("Weather") to value.optString("WeatherCode").ifBlank { null })
        }.toMap()

    private fun JSONObject.coordinate() = GeoPoint(
        latitude = getString("Latitude").toDouble(),
        longitude = getString("Longitude").toDouble(),
    )

    private fun JSONObject.instant(name: String): Instant? =
        optString(name).takeIf(String::isNotBlank)?.let { OffsetDateTime.parse(it).toInstant() }

    private fun objects(value: Any?): List<JSONObject> = when (value) {
        is JSONObject -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        else -> emptyList()
    }

    private fun Collection<Int>.averageOrNull(): Int? =
        takeIf(Collection<Int>::isNotEmpty)?.average()?.toInt()

    private companion object {
        const val EARTH_RADIUS_KM = 6_371.0
        const val MAX_DISTRICT_DISTANCE_KM = 80.0
        const val MAX_THREE_DAY_PERIODS = 24
        const val MAX_WEEKLY_PERIODS = 14
    }
}
