package com.rton.howstheweather.domain

import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Official CWA tropical cyclone analysis and forecast (`W-C0034-005`). */
data class TropicalCyclone(
    val year: Int?,
    val englishName: String,
    val cwaName: String,
    /** CWA typhoon number; null while the system is only a tropical depression. */
    val typhoonNumber: Int?,
    val depressionNumber: Int?,
    val track: List<CycloneFix>,
    val forecast: List<CycloneForecastPoint>,
    val sourceId: String,
) {
    init {
        require(track.isNotEmpty()) { "Tropical cyclone needs at least one analysis fix" }
    }

    val latest: CycloneFix get() = track.last()

    val displayName: String
        get() {
            val name = cwaName.ifBlank { englishName }
            val kind = if (typhoonNumber != null) "颱風" else "熱帶性低氣壓"
            return if (name.isBlank()) kind else "$kind $name"
        }
}

data class CycloneFix(
    val time: Instant,
    val center: GeoPoint,
    val maxWindMetersPerSecond: Float?,
    val maxGustMetersPerSecond: Float?,
    val pressureHectopascals: Float?,
    val movingSpeedKmPerHour: Float?,
    val movingDirection: String?,
    val movingPrediction: String?,
    /** 7級風 (≥15 m/s) radius. */
    val galeRadius: CycloneWindRadius?,
    /** 10級風 (≥25 m/s) radius. */
    val stormRadius: CycloneWindRadius?,
)

data class CycloneForecastPoint(
    val initialTime: Instant,
    val forecastHours: Int,
    val center: GeoPoint,
    val maxWindMetersPerSecond: Float?,
    val pressureHectopascals: Float?,
    val galeRadius: CycloneWindRadius?,
    val stormRadius: CycloneWindRadius?,
    /** Radius of the official 70% probability circle around the forecast center. */
    val probability70RadiusKm: Float?,
) {
    val validAt: Instant get() = initialTime.plusSeconds(forecastHours * 3_600L)
}

enum class Quadrant(val centerBearingDegrees: Double) {
    NE(45.0), SE(135.0), SW(225.0), NW(315.0),
}

/**
 * Official wind radius. Quadrant radii, when published, describe an asymmetric
 * wind field; otherwise only the mean radius is known.
 */
data class CycloneWindRadius(
    val meanKm: Float,
    val quadrantsKm: Map<Quadrant, Float> = emptyMap(),
) {
    val hasQuadrants: Boolean get() = Quadrant.entries.all { quadrantsKm[it] != null }

    /**
     * Radius toward [bearingDegrees] (clockwise from north). Quadrant values are
     * anchored at each quadrant's center bearing and eased between neighbours, so
     * the outline is smooth without inventing values outside the published range.
     */
    fun radiusTowardKm(bearingDegrees: Double): Float {
        if (!hasQuadrants) return meanKm
        val bearing = ((bearingDegrees % 360.0) + 360.0) % 360.0
        val shifted = ((bearing - 45.0) + 360.0) % 360.0
        val index = (shifted / 90.0).toInt().coerceIn(0, 3)
        val fraction = (shifted - index * 90.0) / 90.0
        val from = checkNotNull(quadrantsKm[Quadrant.entries[index]])
        val to = checkNotNull(quadrantsKm[Quadrant.entries[(index + 1) % 4]])
        val eased = (1.0 - cos(fraction * PI)) / 2.0
        return (from + (to - from) * eased).toFloat()
    }
}

/** Closed outline of a wind radius around [center], one vertex every [stepDegrees]. */
fun CycloneWindRadius.outline(center: GeoPoint, stepDegrees: Int = 6): List<GeoPoint> {
    require(stepDegrees in 1..90)
    return (0 until 360 step stepDegrees).map { bearing ->
        destinationPoint(center, bearing.toDouble(), radiusTowardKm(bearing.toDouble()).toDouble())
    }
}

/** Great-circle destination from [start] travelling [distanceKm] along [bearingDegrees]. */
fun destinationPoint(start: GeoPoint, bearingDegrees: Double, distanceKm: Double): GeoPoint {
    val angular = distanceKm / EARTH_RADIUS_KM
    val bearing = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(start.latitude)
    val lon1 = Math.toRadians(start.longitude)
    val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angular) * cos(lat1),
        cos(angular) - sin(lat1) * sin(lat2),
    )
    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

fun greatCircleDistanceKm(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_KM * asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS_KM = 6_371.0
