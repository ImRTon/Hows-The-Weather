package com.rton.howstheweather.domain

import java.time.Instant
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class WindProvenance { MODEL, OBSERVATION, UNAVAILABLE }

/** A Lambert-conformal numerical wind field with earth-relative U/V components. */
data class WindGrid(
    val width: Int,
    val height: Int,
    val eastMetersPerSecond: FloatArray,
    val northMetersPerSecond: FloatArray,
    val earthRadiusMeters: Double,
    val latitudeOfOriginDegrees: Double,
    val centralLongitudeDegrees: Double,
    val firstStandardParallelDegrees: Double,
    val secondStandardParallelDegrees: Double,
    val firstPointX: Double,
    val firstPointY: Double,
    val spacingXMeters: Double,
    val spacingYMeters: Double,
    val validAt: Instant,
    val sourceId: String,
) {
    init {
        require(width > 1 && height > 1)
        require(eastMetersPerSecond.size == width * height)
        require(northMetersPerSecond.size == width * height)
        require(earthRadiusMeters > 0.0 && spacingXMeters > 0.0 && spacingYMeters > 0.0)
        require(
            listOf(
                latitudeOfOriginDegrees,
                centralLongitudeDegrees,
                firstStandardParallelDegrees,
                secondStandardParallelDegrees,
                firstPointX,
                firstPointY,
            ).all(Double::isFinite),
        )
    }

    val resolutionKm: Double get() = max(spacingXMeters, spacingYMeters) / 1_000.0

    fun sample(point: GeoPoint): WindComponents? {
        val projected = projection().forward(point)
        val rawX = (projected.x - firstPointX) / spacingXMeters
        val rawY = (projected.y - firstPointY) / spacingYMeters
        if (rawX < -GRID_EDGE_EPSILON || rawX > width - 1 + GRID_EDGE_EPSILON ||
            rawY < -GRID_EDGE_EPSILON || rawY > height - 1 + GRID_EDGE_EPSILON
        ) return null
        val fx = rawX.coerceIn(0.0, (width - 1).toDouble())
        val fy = rawY.coerceIn(0.0, (height - 1).toDouble())
        val x0 = floor(fx).toInt().coerceAtMost(width - 2)
        val y0 = floor(fy).toInt().coerceAtMost(height - 2)
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        fun interpolate(values: FloatArray): Float? {
            val topLeft = values[y0 * width + x0]
            val topRight = values[y0 * width + x0 + 1]
            val bottomLeft = values[(y0 + 1) * width + x0]
            val bottomRight = values[(y0 + 1) * width + x0 + 1]
            if (!topLeft.isFinite() || !topRight.isFinite() || !bottomLeft.isFinite() || !bottomRight.isFinite()) {
                return null
            }
            val top = topLeft + (topRight - topLeft) * tx
            val bottom = bottomLeft + (bottomRight - bottomLeft) * tx
            return top + (bottom - top) * ty
        }
        return WindComponents(
            eastMetersPerSecond = interpolate(eastMetersPerSecond) ?: return null,
            northMetersPerSecond = interpolate(northMetersPerSecond) ?: return null,
        )
    }

    fun coordinateAt(x: Int, y: Int): GeoPoint {
        require(x in 0 until width && y in 0 until height)
        return projection().inverse(
            ProjectedPoint(
                firstPointX + x * spacingXMeters,
                firstPointY + y * spacingYMeters,
            ),
        )
    }

    fun croppedTo(bounds: GeoBounds, marginCells: Int = 3): WindGrid? {
        val latitudes = listOf(bounds.south, (bounds.south + bounds.north) / 2.0, bounds.north)
        val longitudes = listOf(bounds.west, (bounds.west + bounds.east) / 2.0, bounds.east)
        val indices = latitudes.flatMap { latitude ->
            longitudes.map { longitude ->
                val projected = projection().forward(GeoPoint(latitude, longitude))
                (projected.x - firstPointX) / spacingXMeters to
                    (projected.y - firstPointY) / spacingYMeters
            }
        }
        val xStart = (floor(indices.minOf { it.first }).toInt() - marginCells).coerceAtLeast(0)
        val xEnd = (ceil(indices.maxOf { it.first }).toInt() + marginCells).coerceAtMost(width - 1)
        val yStart = (floor(indices.minOf { it.second }).toInt() - marginCells).coerceAtLeast(0)
        val yEnd = (ceil(indices.maxOf { it.second }).toInt() + marginCells).coerceAtMost(height - 1)
        if (xStart >= xEnd || yStart >= yEnd) return null
        val croppedWidth = xEnd - xStart + 1
        val croppedHeight = yEnd - yStart + 1
        fun crop(values: FloatArray) = FloatArray(croppedWidth * croppedHeight) { index ->
            val x = index % croppedWidth
            val y = index / croppedWidth
            values[(yStart + y) * width + xStart + x]
        }
        return copy(
            width = croppedWidth,
            height = croppedHeight,
            eastMetersPerSecond = crop(eastMetersPerSecond),
            northMetersPerSecond = crop(northMetersPerSecond),
            firstPointX = firstPointX + xStart * spacingXMeters,
            firstPointY = firstPointY + yStart * spacingYMeters,
        )
    }

    private fun projection() = LambertConformalProjection(
        earthRadiusMeters,
        latitudeOfOriginDegrees,
        centralLongitudeDegrees,
        firstStandardParallelDegrees,
        secondStandardParallelDegrees,
    )
}

private const val GRID_EDGE_EPSILON = 1e-6

internal data class ProjectedPoint(val x: Double, val y: Double)

internal class LambertConformalProjection(
    private val radius: Double,
    latitudeOfOriginDegrees: Double,
    centralLongitudeDegrees: Double,
    firstStandardParallelDegrees: Double,
    secondStandardParallelDegrees: Double,
) {
    private val latitudeOfOrigin = Math.toRadians(latitudeOfOriginDegrees)
    private val centralLongitude = Math.toRadians(centralLongitudeDegrees)
    private val firstParallel = Math.toRadians(firstStandardParallelDegrees)
    private val secondParallel = Math.toRadians(secondStandardParallelDegrees)
    private val coneConstant = if (kotlin.math.abs(firstParallel - secondParallel) < 1e-12) {
        sin(firstParallel)
    } else {
        ln(cos(firstParallel) / cos(secondParallel)) /
            ln(tan(PI / 4.0 + secondParallel / 2.0) / tan(PI / 4.0 + firstParallel / 2.0))
    }
    private val factor = cos(firstParallel) *
        tan(PI / 4.0 + firstParallel / 2.0).pow(coneConstant) / coneConstant
    private val originRadius = radius * factor /
        tan(PI / 4.0 + latitudeOfOrigin / 2.0).pow(coneConstant)

    fun forward(point: GeoPoint): ProjectedPoint {
        val latitude = Math.toRadians(point.latitude.coerceIn(-89.999999, 89.999999))
        val longitude = Math.toRadians(point.longitude)
        val radialDistance = radius * factor /
            tan(PI / 4.0 + latitude / 2.0).pow(coneConstant)
        val theta = coneConstant * normalizedLongitude(longitude - centralLongitude)
        return ProjectedPoint(
            radialDistance * sin(theta),
            originRadius - radialDistance * cos(theta),
        )
    }

    fun inverse(point: ProjectedPoint): GeoPoint {
        val radialDistance = sqrt(point.x * point.x + (originRadius - point.y) * (originRadius - point.y))
        val theta = atan2(point.x, originRadius - point.y)
        val latitude = 2.0 * atan((radius * factor / radialDistance).pow(1.0 / coneConstant)) - PI / 2.0
        val longitude = centralLongitude + theta / coneConstant
        return GeoPoint(Math.toDegrees(latitude), Math.toDegrees(longitude))
    }

    private fun normalizedLongitude(value: Double): Double {
        var normalized = value
        while (normalized > PI) normalized -= 2.0 * PI
        while (normalized < -PI) normalized += 2.0 * PI
        return normalized
    }
}
