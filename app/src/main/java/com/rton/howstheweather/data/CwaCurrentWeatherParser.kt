package com.rton.howstheweather.data

import com.rton.howstheweather.domain.CurrentWeatherObservation
import com.rton.howstheweather.domain.GeoPoint
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

class CwaCurrentWeatherParser {
    fun parse(json: String): List<CurrentWeatherObservation> {
        val root = JSONObject(json)
        require(root.optString("success", "true").equals("true", ignoreCase = true)) {
            "CWA O-A0003-001 回應失敗"
        }
        val stationValue = root.getJSONObject("records").get("Station")
        val stations = when (stationValue) {
            is JSONArray -> stationValue
            is JSONObject -> JSONArray().put(stationValue)
            else -> error("CWA O-A0003-001 測站格式不符")
        }
        val rejected = IntArray(Rejection.entries.size)
        return buildList {
            for (index in 0 until stations.length()) {
                val (observation, rejection) = runCatching {
                    parseStation(stations.getJSONObject(index))
                }.getOrElse {
                    null to Rejection.SCHEMA
                }
                if (observation != null) add(observation)
                else rejected[checkNotNull(rejection).ordinal]++
            }
        }.also {
            require(it.isNotEmpty()) {
                val reasons = Rejection.entries.mapNotNull { reason ->
                    rejected[reason.ordinal].takeIf { count -> count > 0 }?.let { count ->
                        "${reason.label} $count"
                    }
                }.joinToString("、")
                "CWA O-A0003-001 沒有有效的目前天氣觀測（共 ${stations.length()} 站；$reasons）"
            }
        }
    }

    private fun parseStation(station: JSONObject): Pair<CurrentWeatherObservation?, Rejection?> {
        val coordinate = station.getJSONObject("GeoInfo").coordinate()
            ?: return null to Rejection.COORDINATE
        val latitude = coordinate.number("StationLatitude")
            ?: return null to Rejection.COORDINATE
        val longitude = coordinate.number("StationLongitude")
            ?: return null to Rejection.COORDINATE
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return null to Rejection.COORDINATE
        }

        val observedAt = runCatching {
            OffsetDateTime.parse(station.getJSONObject("ObsTime").getString("DateTime")).toInstant()
        }.getOrNull() ?: return null to Rejection.TIME
        val weather = station.getJSONObject("WeatherElement")
        val description = weather.optString("Weather")
            .trim()
            .takeUnless { it.isBlank() || it == "-" || it == "-99" }
        val temperature = weather.validNumber("AirTemperature")
        val humidity = weather.validNumber("RelativeHumidity")
            ?.takeIf { it in 0.0..100.0 }
        val windSpeed = weather.validNumber("WindSpeed")
            ?.takeIf { it >= 0.0 }
        val rawWindDirection = weather.number("WindDirection")
        val windVariable = rawWindDirection == VARIABLE_WIND_DIRECTION
        val windDirection = rawWindDirection
            ?.takeIf { it in 0.0..360.0 }
        val precipitationContainer = weather.optJSONObject("Now") ?: weather
        val precipitationRaw = precipitationContainer.opt("Precipitation")
        val precipitationTrace = precipitationRaw?.toString()?.equals("T", ignoreCase = true) == true
        val precipitation = when {
            precipitationTrace -> null
            precipitationContainer.number("Precipitation") == NO_RECENT_PRECIPITATION -> 0.0
            else -> precipitationContainer.validNumber("Precipitation")?.takeIf { it >= 0.0 }
        }
        val pressure = weather.validNumber("AirPressure")?.takeIf { it > 0.0 }
        val uvIndex = weather.validNumber("UVIndex")?.toInt()?.takeIf { it >= 0 }

        if (description == null && temperature == null && humidity == null && windSpeed == null &&
            precipitation == null && !precipitationTrace && pressure == null && uvIndex == null
        ) return null to Rejection.WEATHER

        return CurrentWeatherObservation(
            stationName = station.optString("StationName", station.optString("StationId", "測站")),
            coordinate = GeoPoint(latitude, longitude),
            weatherDescription = description,
            temperatureCelsius = temperature?.toFloat(),
            relativeHumidityPercent = humidity?.toFloat(),
            windSpeedMetersPerSecond = windSpeed?.toFloat(),
            windDirectionDegrees = windDirection?.toFloat(),
            windDirectionVariable = windVariable,
            precipitationTodayMillimeters = precipitation?.toFloat(),
            precipitationTrace = precipitationTrace,
            airPressureHectopascals = pressure?.toFloat(),
            uvIndex = uvIndex,
            observedAt = observedAt,
            sourceId = DATASET_ID,
        ) to null
    }

    private fun JSONObject.coordinate(): JSONObject? {
        val coordinates = get("Coordinates")
        return when (coordinates) {
            is JSONArray -> (0 until coordinates.length())
                .map { coordinates.getJSONObject(it) }
                .firstOrNull { it.optString("CoordinateName").equals("WGS84", ignoreCase = true) }
                ?: coordinates.optJSONObject(0)
            is JSONObject -> coordinates
            else -> null
        }
    }

    private fun JSONObject.validNumber(name: String): Double? = number(name)
        ?.takeUnless { it == MISSING_VALUE || it == LEGACY_MISSING_VALUE }

    private fun JSONObject.number(name: String): Double? {
        val raw = opt(name) ?: return null
        if (raw == JSONObject.NULL) return null
        val value = when (raw) {
            is Number -> raw.toDouble()
            else -> raw.toString().toDoubleOrNull()
        } ?: return null
        return value.takeIf(Double::isFinite)
    }

    private companion object {
        const val DATASET_ID = "O-A0003-001"
        const val MISSING_VALUE = -99.0
        const val LEGACY_MISSING_VALUE = -999.0
        const val NO_RECENT_PRECIPITATION = -98.0
        const val VARIABLE_WIND_DIRECTION = 990.0
    }

    private enum class Rejection(val label: String) {
        COORDINATE("座標無效"),
        TIME("時間無效"),
        WEATHER("天氣欄位缺失"),
        SCHEMA("欄位格式不符"),
    }
}
