package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal fun WeatherGrid.downsampled(maxDimension: Int): WeatherGrid {
    require(maxDimension >= 2)
    val largestDimension = maxOf(width, height)
    if (largestDimension <= maxDimension) return this
    val scale = largestDimension.toDouble() / maxDimension
    val targetWidth = ((width - 1) / scale).roundToInt().coerceAtLeast(1) + 1
    val targetHeight = ((height - 1) / scale).roundToInt().coerceAtLeast(1) + 1
    val targetValues = FloatArray(targetWidth * targetHeight)
    val xStep = (width - 1).toDouble() / (targetWidth - 1)
    val yStep = (height - 1).toDouble() / (targetHeight - 1)
    val radiusX = (xStep * 0.5).coerceAtLeast(0.5)
    val radiusY = (yStep * 0.5).coerceAtLeast(0.5)
    for (y in 0 until targetHeight) {
        val centerY = y * yStep
        val y0 = ceil(centerY - radiusY - 1e-6).toInt().coerceIn(0, height - 1)
        val y1 = floor(centerY + radiusY + 1e-6).toInt().coerceIn(y0, height - 1)
        for (x in 0 until targetWidth) {
            // Preserve exact corner/edge samples while box-aggregating interior blocks.
            if ((x == 0 || x == targetWidth - 1) && (y == 0 || y == targetHeight - 1)) {
                val sx = if (x == 0) 0 else width - 1
                val sy = if (y == 0) 0 else height - 1
                targetValues[y * targetWidth + x] = valueAt(sx, sy)
                continue
            }
            val centerX = x * xStep
            val x0 = ceil(centerX - radiusX - 1e-6).toInt().coerceIn(0, width - 1)
            val x1 = floor(centerX + radiusX + 1e-6).toInt().coerceIn(x0, width - 1)
            var sum = 0f
            var count = 0
            for (sy in y0..y1) {
                for (sx in x0..x1) {
                    val v = valueAt(sx, sy)
                    if (v.isFinite() && v != missingValue) {
                        sum += v
                        count++
                    }
                }
            }
            targetValues[y * targetWidth + x] = if (count == 0) Float.NaN else sum / count
        }
    }
    return copy(
        width = targetWidth,
        height = targetHeight,
        values = targetValues,
        resolutionKm = resolutionKm * scale,
        sourceId = "$sourceId-WIDE-${targetWidth}x$targetHeight",
    )
}

internal fun WeatherGrid.croppedTo(requested: GeoBounds): WeatherGrid? {
    val west = maxOf(bounds.west, requested.west)
    val east = minOf(bounds.east, requested.east)
    val south = maxOf(bounds.south, requested.south)
    val north = minOf(bounds.north, requested.north)
    if (west >= east || south >= north) return null

    val longitudeStep = (bounds.east - bounds.west) / (width - 1)
    val latitudeStep = (bounds.north - bounds.south) / (height - 1)
    val xStart = ceil((west - bounds.west) / longitudeStep).toInt().coerceIn(0, width - 2)
    val xEnd = floor((east - bounds.west) / longitudeStep).toInt().coerceIn(xStart + 1, width - 1)
    val yStart = ceil((bounds.north - north) / latitudeStep).toInt().coerceIn(0, height - 2)
    val yEnd = floor((bounds.north - south) / latitudeStep).toInt().coerceIn(yStart + 1, height - 1)
    val targetWidth = xEnd - xStart + 1
    val targetHeight = yEnd - yStart + 1
    val targetValues = FloatArray(targetWidth * targetHeight)
    for (y in 0 until targetHeight) {
        values.copyInto(
            destination = targetValues,
            destinationOffset = y * targetWidth,
            startIndex = (yStart + y) * width + xStart,
            endIndex = (yStart + y) * width + xEnd + 1,
        )
    }
    return copy(
        width = targetWidth,
        height = targetHeight,
        values = targetValues,
        bounds = GeoBounds(
            south = bounds.north - yEnd * latitudeStep,
            west = bounds.west + xStart * longitudeStep,
            north = bounds.north - yStart * latitudeStep,
            east = bounds.west + xEnd * longitudeStep,
        ),
        sourceId = "$sourceId-LOCAL",
    )
}
