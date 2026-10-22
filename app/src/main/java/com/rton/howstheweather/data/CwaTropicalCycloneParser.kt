package com.rton.howstheweather.data

import com.rton.howstheweather.domain.CycloneFix
import com.rton.howstheweather.domain.CycloneForecastPoint
import com.rton.howstheweather.domain.CycloneWindRadius
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.Quadrant
import com.rton.howstheweather.domain.TropicalCyclone
import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses CWA `W-C0034-005` (active tropical cyclones). An empty or absent
 * cyclone list is valid and means no tropical cyclone is currently active.
 */
class CwaTropicalCycloneParser {
    fun parse(json: String): List<TropicalCyclone> {
        val root = JSONObject(json)
        require(root.optString("success", "true").equals("true", ignoreCase = true)) {
            "CWA $DATASET_ID 回應失敗"
        }
        val records = root.optJSONObject("records") ?: error("CWA $DATASET_ID 缺少 records")
        val container = records.optJSONObject("TropicalCyclones") ?: return emptyList()
        return container.arrayOrSingle("TropicalCyclone").mapNotNull(::parseCyclone)
    }

    private fun parseCyclone(cyclone: JSONObject): TropicalCyclone? {
        val track = cyclone.optJSONObject("AnalysisData")
            ?.arrayOrSingle("Fix")
            ?.mapNotNull(::parseAnalysisFix)
            ?.sortedBy(CycloneFix::time)
            .orEmpty()
        if (track.isEmpty()) return null
        val forecast = cyclone.optJSONObject("ForecastData")
            ?.arrayOrSingle("Fix")
            ?.mapNotNull(::parseForecastFix)
            ?.sortedBy(CycloneForecastPoint::forecastHours)
            .orEmpty()
        return TropicalCyclone(
            year = cyclone.text("Year")?.toIntOrNull(),
            englishName = cyclone.text("TyphoonName").orEmpty(),
            cwaName = cyclone.text("CwaTyphoonName").orEmpty(),
            typhoonNumber = cyclone.text("CwaTyNo")?.toIntOrNull(),
            depressionNumber = cyclone.text("CwaTdNo")?.toIntOrNull(),
            track = track,
            forecast = forecast,
            sourceId = DATASET_ID,
        )
    }

    private fun parseAnalysisFix(fix: JSONObject): CycloneFix? {
        val time = fix.instant("DateTime") ?: return null
        val center = fix.coordinate() ?: return null
        return CycloneFix(
            time = time,
            center = center,
            maxWindMetersPerSecond = fix.positive("MaxWindSpeed"),
            maxGustMetersPerSecond = fix.positive("MaxGustSpeed"),
            pressureHectopascals = fix.positive("Pressure"),
            movingSpeedKmPerHour = fix.number("MovingSpeed")?.takeIf { it >= 0f },
            movingDirection = fix.text("MovingDirection"),
            movingPrediction = fix.arrayOrSingle("MovingPrediction")
                .firstOrNull { it.optString("lang") == "zh-hant" }
                ?.text("value"),
            galeRadius = fix.optJSONObject("Circle15ms")?.let(::parseRadius),
            stormRadius = fix.optJSONObject("Circle25ms")?.let(::parseRadius),
        )
    }

    private fun parseForecastFix(fix: JSONObject): CycloneForecastPoint? {
        val initialTime = fix.instant("InitialTime") ?: return null
        val hours = fix.text("ForecastHour")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val center = fix.coordinate() ?: return null
        return CycloneForecastPoint(
            initialTime = initialTime,
            forecastHours = hours,
            center = center,
            maxWindMetersPerSecond = fix.positive("MaxWindSpeed"),
            pressureHectopascals = fix.positive("Pressure"),
            galeRadius = fix.optJSONObject("Circle15ms")?.let(::parseRadius),
            stormRadius = fix.optJSONObject("Circle25ms")?.let(::parseRadius),
            probability70RadiusKm = fix.positive("Radius70PercentProbability"),
        )
    }

    private fun parseRadius(circle: JSONObject): CycloneWindRadius? {
        val mean = circle.positive("Radius") ?: return null
        val quadrants = circle.optJSONObject("QuadrantRadii")
            ?.arrayOrSingle("Radius")
            ?.mapNotNull { entry ->
                val quadrant = entry.text("dir")
                    ?.let { dir -> Quadrant.entries.firstOrNull { it.name.equals(dir, ignoreCase = true) } }
                    ?: return@mapNotNull null
                val value = entry.positive("value") ?: return@mapNotNull null
                quadrant to value
            }
            ?.toMap()
            .orEmpty()
        // A partial set cannot describe an outline; fall back to the mean radius.
        return CycloneWindRadius(
            meanKm = mean,
            quadrantsKm = quadrants.takeIf { it.size == Quadrant.entries.size }.orEmpty(),
        )
    }

    private fun JSONObject.coordinate(): GeoPoint? {
        val latitude = text("CoordinateLatitude")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val longitude = text("CoordinateLongitude")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..360.0) return null
        return GeoPoint(latitude, if (longitude > 180.0) longitude - 360.0 else longitude)
    }

    private fun JSONObject.instant(name: String): Instant? =
        text(name)?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private fun JSONObject.text(name: String): String? {
        val raw = opt(name) ?: return null
        if (raw == JSONObject.NULL) return null
        return raw.toString().trim().takeUnless { it.isEmpty() || it == "-" }
    }

    private fun JSONObject.number(name: String): Float? =
        text(name)?.toFloatOrNull()?.takeIf { it.isFinite() }

    private fun JSONObject.positive(name: String): Float? = number(name)?.takeIf { it > 0f }

    private fun JSONObject.arrayOrSingle(name: String): List<JSONObject> = when (val value = opt(name)) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is JSONObject -> listOf(value)
        else -> emptyList()
    }

    companion object {
        const val DATASET_ID = "W-C0034-005"
    }
}
