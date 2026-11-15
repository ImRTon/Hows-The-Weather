package com.rton.howstheweather.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/** Spherical transverse Mercator, used by CWA's Taiwan satellite presentation.
 * Formula: https://proj.org/en/stable/operations/projections/tmerc.html
 * Source graticule checks bound raster alignment error; this is not TWD97/TM2.
 */
internal class TransverseMercatorProjection(
    private val radius: Double,
    latitudeOfOriginDegrees: Double,
    centralLongitudeDegrees: Double,
) {
    private val latitudeOfOrigin = Math.toRadians(latitudeOfOriginDegrees)
    private val centralLongitude = Math.toRadians(centralLongitudeDegrees)

    fun forward(point: GeoPoint): ProjectedPoint {
        val latitude = Math.toRadians(point.latitude)
        var delta = Math.toRadians(point.longitude) - centralLongitude
        while (delta > PI) delta -= 2 * PI
        while (delta < -PI) delta += 2 * PI
        if (abs(delta) >= PI / 2) return ProjectedPoint(Double.NaN, Double.NaN)
        val b = cos(latitude) * sin(delta)
        return ProjectedPoint(
            radius * .5 * ln((1 + b) / (1 - b)),
            radius * (atan2(tan(latitude), cos(delta)) - latitudeOfOrigin),
        )
    }
}
