package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import java.time.OffsetDateTime
import org.json.JSONObject
import kotlin.math.roundToInt

data class CwaQuantitativeForecastMetadata(
    val issuedAt: java.time.Instant,
    val productUrl: String,
)

/**
 * Converts the public F-C0035-015…018 chart into a regular numerical grid.
 *
 * Only exact colors from CWA's finite rainfall legend are accepted. Titles,
 * coastlines, the logo and legend labels therefore cannot become rainfall;
 * a missing chart pixel is filled only by the bounded mean of adjacent valid
 * legend pixels before the app renders its own accessible palette.
 * The affine transform is calibrated to the main Taiwan panel; decorative
 * relocated inset maps are intentionally outside the output domain.
 */
class CwaQuantitativeForecastImageParser {
    fun parseMetadata(json: String): CwaQuantitativeForecastMetadata {
        val root = JSONObject(json).getJSONObject("cwaopendata")
        val dataset = root.objectFor("dataset", "Dataset")
        val resource = dataset.objectFor("resource", "Resource")
        return CwaQuantitativeForecastMetadata(
            issuedAt = OffsetDateTime.parse(root.stringFor("sent", "Sent")).toInstant(),
            productUrl = resource.stringFor("productURL", "ProductURL"),
        )
    }

    fun toGrid(
        metadata: CwaQuantitativeForecastMetadata,
        imageWidth: Int,
        imageHeight: Int,
        argbPixels: IntArray,
        sourceId: String,
        endHour: Int,
    ): WeatherGrid {
        require(imageWidth > 1 && imageHeight > 1)
        require(argbPixels.size == imageWidth * imageHeight)
        require(endHour in 12..48 && endHour % 12 == 0)
        val values = FloatArray(OUTPUT_WIDTH * OUTPUT_HEIGHT)
        for (y in 0 until OUTPUT_HEIGHT) {
            val latitude = SOURCE_BOUNDS.north -
                y.toDouble() / (OUTPUT_HEIGHT - 1) * (SOURCE_BOUNDS.north - SOURCE_BOUNDS.south)
            for (x in 0 until OUTPUT_WIDTH) {
                val longitude = SOURCE_BOUNDS.west +
                    x.toDouble() / (OUTPUT_WIDTH - 1) * (SOURCE_BOUNDS.east - SOURCE_BOUNDS.west)
                val (referenceX, referenceY) = sourcePixel(longitude, latitude)
                val sourceX = referenceX * imageWidth / REFERENCE_IMAGE_WIDTH
                val sourceY = referenceY * imageHeight / REFERENCE_IMAGE_HEIGHT
                values[y * OUTPUT_WIDTH + x] = decodeSample(
                    sourceX.roundToInt(), sourceY.roundToInt(), imageWidth, imageHeight, argbPixels,
                )
            }
        }
        return WeatherGrid(
            width = OUTPUT_WIDTH,
            height = OUTPUT_HEIGHT,
            values = values,
            unit = WeatherUnit.MILLIMETERS_TWELVE_HOURS,
            bounds = DISPLAY_BOUNDS,
            resolutionKm = OFFICIAL_GRID_RESOLUTION_KM,
            // The chart metadata exposes publication time; the selected 12-hour
            // interval is carried by list position rather than pretending this is
            // an instantaneous field at the end of the interval.
            validAt = metadata.issuedAt,
            sourceId = sourceId,
        )
    }

    internal fun decodeRain(argb: Int): Float? {
        if (argb ushr 24 and 0xff < MIN_ALPHA) return null
        return RAIN_PALETTE[argb and 0x00ffffff]
    }

    internal fun decodeSample(
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Float {
        if (centerX in 0 until width && centerY in 0 until height) {
            decodeRain(pixels[centerY * width + centerX])?.let { return it }
        }
        var total = 0f
        var count = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val x = centerX + dx
            val y = centerY + dy
            if (x !in 0 until width || y !in 0 until height) continue
            decodeRain(pixels[y * width + x])?.let { value ->
                total += value
                count += 1
            }
        }
        return if (count == 0) 0f else total / count
    }

    internal fun sourcePixel(longitude: Double, latitude: Double): Pair<Double, Double> {
        val adjustedLongitude = longitude - LONGITUDE_OFFSET
        val adjustedLatitude = latitude - LATITUDE_OFFSET
        val determinant = LONGITUDE_X * LATITUDE_Y - LONGITUDE_Y * LATITUDE_X
        val affineX = (adjustedLongitude * LATITUDE_Y - LONGITUDE_Y * adjustedLatitude) / determinant
        val affineY = (LONGITUDE_X * adjustedLatitude - adjustedLongitude * LATITUDE_X) / determinant
        val normalizedLongitude = longitude - CALIBRATION_LONGITUDE
        val normalizedLatitude = latitude - CALIBRATION_LATITUDE
        val correctedX = affineX + correction(X_CORRECTION, normalizedLongitude, normalizedLatitude)
        val correctedY = affineY + correction(Y_CORRECTION, normalizedLongitude, normalizedLatitude)
        return correctedX to correctedY
    }

    private fun correction(coefficients: DoubleArray, longitude: Double, latitude: Double): Double =
        coefficients[0] +
            coefficients[1] * longitude +
            coefficients[2] * latitude +
            coefficients[3] * longitude * longitude +
            coefficients[4] * longitude * latitude +
            coefficients[5] * latitude * latitude

    private fun JSONObject.objectFor(vararg keys: String): JSONObject = keys.firstNotNullOfOrNull(::optJSONObject)
        ?: error("缺少定量降水欄位：${keys.joinToString()}")

    private fun JSONObject.stringFor(vararg keys: String): String = keys.firstNotNullOfOrNull {
        optString(it).takeIf(String::isNotBlank)
    } ?: error("缺少定量降水欄位：${keys.joinToString()}")

    private companion object {
        const val OUTPUT_WIDTH = 128
        const val OUTPUT_HEIGHT = 170
        const val REFERENCE_IMAGE_WIDTH = 1245.0
        const val REFERENCE_IMAGE_HEIGHT = 1500.0
        const val MIN_ALPHA = 240
        const val OFFICIAL_GRID_RESOLUTION_KM = 2.5
        const val DISPLAY_LATITUDE_NUDGE_DEGREES = 0.02
        val SOURCE_BOUNDS = GeoBounds(south = 21.7, west = 119.2, north = 25.5, east = 122.2)
        val DISPLAY_BOUNDS = SOURCE_BOUNDS.copy(
            south = SOURCE_BOUNDS.south + DISPLAY_LATITUDE_NUDGE_DEGREES,
            north = SOURCE_BOUNDS.north + DISPLAY_LATITUDE_NUDGE_DEGREES,
        )

        // Pixel-to-WGS84 affine calibration for the 1245 × 1500 F-C0035 chart.
        // Coordinates scale with the chart dimensions before this inverse is used.
        const val LONGITUDE_X = 0.0033134352117158705
        const val LONGITUDE_Y = 0.00021967526265515708
        const val LONGITUDE_OFFSET = 118.38432823941436
        const val LATITUDE_X = -0.00005677597368143952
        const val LATITUDE_Y = -0.0028398599172238213
        const val LATITUDE_OFFSET = 25.96950546535076

        // Residual chart-projection correction fitted against the Ministry of
        // the Interior's TWD97 county boundaries. Unlike the original three-
        // point affine transform, this keeps the north, center, south and both
        // coasts aligned at the same time.
        const val CALIBRATION_LONGITUDE = 121.0
        const val CALIBRATION_LATITUDE = 23.6
        val X_CORRECTION = doubleArrayOf(
            4.701173945846176,
            51.56570799028519,
            -23.443512219141212,
            -9.241018550450033,
            8.034774210746601,
            0.09444260150674937,
        )
        val Y_CORRECTION = doubleArrayOf(
            -7.657750552488501,
            3.7111764600103374,
            -4.154802256285876,
            -5.187277752017548,
            5.906531455393173,
            -2.1334201294466992,
        )

        fun rgb(red: Int, green: Int, blue: Int) = (red shl 16) or (green shl 8) or blue

        val RAIN_PALETTE = mapOf(
            rgb(194, 194, 194) to .5f,
            rgb(156, 252, 255) to 1f,
            rgb(3, 200, 255) to 2f,
            rgb(5, 155, 255) to 5f,
            rgb(3, 99, 255) to 10f,
            rgb(5, 153, 2) to 15f,
            rgb(57, 255, 3) to 20f,
            rgb(255, 251, 3) to 30f,
            rgb(255, 200, 0) to 40f,
            rgb(255, 149, 0) to 50f,
            rgb(255, 0, 0) to 70f,
            rgb(204, 0, 0) to 90f,
            rgb(153, 0, 0) to 110f,
            rgb(150, 0, 153) to 130f,
            rgb(201, 0, 204) to 150f,
            rgb(251, 0, 255) to 200f,
            rgb(255, 206, 255) to 300f,
        )
    }
}
