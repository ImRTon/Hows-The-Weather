package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WindObservation
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

class CwaWindObservationParser {
    fun parse(json: String): List<WindObservation> {
        val root = JSONObject(json)
        require(root.optString("success", "true").equals("true", ignoreCase = true)) {
            "CWA O-A0001-001 回應失敗"
        }
        val stationValue = root.getJSONObject("records").get("Station")
        val stations = when (stationValue) {
            is JSONArray -> stationValue
            is JSONObject -> JSONArray().put(stationValue)
            else -> error("CWA O-A0001-001 測站格式不符")
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
                "CWA O-A0001-001 沒有有效風場觀測（共 ${stations.length()} 站；$reasons）"
            }
        }
    }

    private fun parseStation(station: JSONObject): Pair<WindObservation?, Rejection?> {
        val coordinates = station.getJSONObject("GeoInfo").get("Coordinates")
        val coordinate = when (coordinates) {
            is JSONArray -> (0 until coordinates.length())
                .map { coordinates.getJSONObject(it) }
                .firstOrNull { it.optString("CoordinateName").equals("WGS84", ignoreCase = true) }
                ?: coordinates.optJSONObject(0)
            is JSONObject -> coordinates
            else -> null
        } ?: return null to Rejection.COORDINATE
        val weather = station.getJSONObject("WeatherElement")
        val latitude = coordinate.number("StationLatitude") ?: return null to Rejection.COORDINATE
        val longitude = coordinate.number("StationLongitude") ?: return null to Rejection.COORDINATE
        val speed = weather.number("WindSpeed")?.toFloat() ?: return null to Rejection.WIND
        val direction = weather.number("WindDirection")?.toFloat() ?: return null to Rejection.WIND
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return null to Rejection.COORDINATE
        }
        if (speed < 0f || direction !in 0f..360f) return null to Rejection.WIND
        val observedAt = runCatching {
            OffsetDateTime.parse(station.getJSONObject("ObsTime").getString("DateTime")).toInstant()
        }.getOrNull() ?: return null to Rejection.TIME
        return WindObservation(
            stationName = station.optString("StationName", station.optString("StationId", "測站")),
            coordinate = GeoPoint(latitude, longitude),
            speedMetersPerSecond = speed,
            directionDegrees = direction,
            observedAt = observedAt,
        ) to null
    }

    private fun JSONObject.number(name: String): Double? {
        val raw = opt(name) ?: return null
        if (raw == JSONObject.NULL) return null
        val value = when (raw) {
            is Number -> raw.toDouble()
            else -> raw.toString().toDoubleOrNull()
        } ?: return null
        return value.takeIf { it.isFinite() }
    }

    private enum class Rejection(val label: String) {
        COORDINATE("座標無效"),
        WIND("風值缺失"),
        TIME("時間無效"),
        SCHEMA("欄位格式不符"),
    }
}
