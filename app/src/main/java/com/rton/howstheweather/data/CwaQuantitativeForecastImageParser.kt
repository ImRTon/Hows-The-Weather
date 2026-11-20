package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import java.time.OffsetDateTime
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class CwaQuantitativeForecastMetadata(
    val issuedAt: java.time.Instant,
    val productUrl: String,
)

/**
 * Converts the four public 12-hour rainfall charts into regular numerical grids.
 *
 * Only pixels inside the map frame can contribute to a sample. Within that
 * frame, colors matching CWA's rainfall legend become amounts, and dark
 * coastlines or annotations can be filled from nearby rainfall pixels.
 * The projection is calibrated to the main Taiwan panel.
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
        val xScale = imageWidth / REFERENCE_IMAGE_WIDTH
        val yScale = imageHeight / REFERENCE_IMAGE_HEIGHT
        val mapColumns = ceil(MAP_LEFT * xScale).toInt()..floor(MAP_RIGHT * xScale).toInt()
        val mapRows = ceil(MAP_TOP * yScale).toInt()..floor(MAP_BOTTOM * yScale).toInt()
        val values = FloatArray(OUTPUT_WIDTH * OUTPUT_HEIGHT)
        for (y in 0 until OUTPUT_HEIGHT) {
            val latitude = GRID_BOUNDS.north -
                y.toDouble() / (OUTPUT_HEIGHT - 1) * (GRID_BOUNDS.north - GRID_BOUNDS.south)
            for (x in 0 until OUTPUT_WIDTH) {
                val longitude = GRID_BOUNDS.west +
                    x.toDouble() / (OUTPUT_WIDTH - 1) * (GRID_BOUNDS.east - GRID_BOUNDS.west)
                val (referenceX, referenceY) = sourcePixel(longitude, latitude)
                values[y * OUTPUT_WIDTH + x] = decodeSample(
                    (referenceX * xScale).roundToInt(), (referenceY * yScale).roundToInt(),
                    imageWidth, argbPixels, mapColumns, mapRows,
                )
            }
        }
        return WeatherGrid(
            width = OUTPUT_WIDTH,
            height = OUTPUT_HEIGHT,
            values = values,
            unit = WeatherUnit.MILLIMETERS_TWELVE_HOURS,
            bounds = GRID_BOUNDS,
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
        val rgb = argb and 0x00ffffff
        RAIN_PALETTE[rgb]?.let { return it }
        // Allow slight RGB variation at antialiased or quantized rainfall seams.
        val r = rgb ushr 16 and 0xff
        val g = rgb ushr 8 and 0xff
        val b = rgb and 0xff
        var bestAmount: Float? = null
        var bestDist = MAX_CHANNEL_TOLERANCE + 1
        for (i in PALETTE_COLORS.indices) {
            val pr = PALETTE_R[i]
            val pg = PALETTE_G[i]
            val pb = PALETTE_B[i]
            val d = maxOf(kotlin.math.abs(r - pr), kotlin.math.abs(g - pg), kotlin.math.abs(b - pb))
            if (d < bestDist) {
                bestDist = d
                bestAmount = PALETTE_AMOUNTS[i]
            }
        }
        return bestAmount
    }

    internal fun decodeSample(
        centerX: Int,
        centerY: Int,
        width: Int,
        pixels: IntArray,
        mapColumns: IntRange,
        mapRows: IntRange,
    ): Float {
        if (centerX !in mapColumns || centerY !in mapRows) return Float.NaN
        val centerPixel = pixels[centerY * width + centerX]
        decodeRain(centerPixel)?.let { return it }
        // Dry land (white) and the ocean background (light blue) indicate 0 mm.
        if (isChartBackground(centerPixel)) return 0f
        for (radius in 1..MAX_INPAINT_RADIUS) {
            var total = 0f
            var count = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (kotlin.math.abs(dx) != radius && kotlin.math.abs(dy) != radius) continue
                val x = centerX + dx
                val y = centerY + dy
                if (x !in mapColumns || y !in mapRows) continue
                decodeRain(pixels[y * width + x])?.let { value ->
                    total += value
                    count += 1
                }
            }
            if (count > 0) return total / count
        }
        return 0f
    }

    private fun isChartBackground(argb: Int): Boolean {
        if (argb ushr 24 and 0xff < MIN_ALPHA) return true
        val r = argb ushr 16 and 0xff
        val g = argb ushr 8 and 0xff
        val b = argb and 0xff
        // White land fill (#FFFFFF) or pale sky-blue sea fill (#EDF9FE) — both have high luminance
        // and minimal saturation, distinct from dark county/coastline strokes and grey 0.5 mm (#C2C2C2).
        return r >= 230 && g >= 242 && b >= 248
    }

    internal fun sourcePixel(longitude: Double, latitude: Double): Pair<Double, Double> {
        val relativeLongitude = longitude - CALIBRATION_LONGITUDE
        val relativeLatitude = latitude - CALIBRATION_LATITUDE
        val chartLongitude = relativeLongitude + correction(LONGITUDE_CORRECTION, relativeLongitude, relativeLatitude)
        val chartLatitude = relativeLatitude + correction(LATITUDE_CORRECTION, relativeLongitude, relativeLatitude)
        val adjustedLongitude = chartLongitude + CALIBRATION_LONGITUDE - LONGITUDE_OFFSET
        val adjustedLatitude = chartLatitude + CALIBRATION_LATITUDE - LATITUDE_OFFSET
        val determinant = LONGITUDE_X * LATITUDE_Y - LONGITUDE_Y * LATITUDE_X
        val affineX = (adjustedLongitude * LATITUDE_Y - LONGITUDE_Y * adjustedLatitude) / determinant
        val affineY = (LONGITUDE_X * adjustedLatitude - adjustedLongitude * LATITUDE_X) / determinant
        val correctedX = affineX + correction(X_CORRECTION, chartLongitude, chartLatitude)
        val correctedY = affineY + correction(Y_CORRECTION, chartLongitude, chartLatitude)
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
        const val OUTPUT_WIDTH = 160
        const val OUTPUT_HEIGHT = 212
        const val REFERENCE_IMAGE_WIDTH = 1245.0
        const val REFERENCE_IMAGE_HEIGHT = 1500.0
        // Interior pixels of the 1245 × 1500 CWA frame, excluding its antialiased strokes.
        const val MAP_LEFT = 14
        const val MAP_TOP = 154
        const val MAP_RIGHT = 1229
        const val MAP_BOTTOM = 1487
        const val MIN_ALPHA = 240
        const val MAX_CHANNEL_TOLERANCE = 2
        const val MAX_INPAINT_RADIUS = 3
        const val OFFICIAL_GRID_RESOLUTION_KM = 2.5
        val GRID_BOUNDS = GeoBounds(south = 21.72, west = 119.2, north = 25.52, east = 122.2)

        // Pixel-to-WGS84 affine calibration for the 1245 × 1500 F-C0035 chart.
        // Coordinates scale with the chart dimensions before this inverse is used.
        const val LONGITUDE_X = 0.0033134352117158705
        const val LONGITUDE_Y = 0.00021967526265515708
        const val LONGITUDE_OFFSET = 118.38432823941436
        const val LATITUDE_X = -0.00005677597368143952
        const val LATITUDE_Y = -0.0028398599172238213
        const val LATITUDE_OFFSET = 25.96950546535076

        // The geographic correction uses NLSC mainland shoreline and inland county boundaries
        // (TWD97). Validation holds out whole latitude bands. Corrections are in degrees and
        // replace the previous uniform northward display shift. The pixel corrections below
        // describe the source chart's projection.
        const val CALIBRATION_LONGITUDE = 121.0
        const val CALIBRATION_LATITUDE = 23.6
        val LONGITUDE_CORRECTION = doubleArrayOf(
            0.00602046, 0.01143965, 0.00126133, 0.00855466, -0.00895490, -0.00387677,
        )
        val LATITUDE_CORRECTION = doubleArrayOf(
            -0.00219993, -0.00253451, 0.00478925, -0.00335776, 0.00574187, -0.00309678,
        )
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
        val PALETTE_COLORS = RAIN_PALETTE.keys.toIntArray()
        val PALETTE_R = IntArray(PALETTE_COLORS.size) { PALETTE_COLORS[it] ushr 16 and 0xff }
        val PALETTE_G = IntArray(PALETTE_COLORS.size) { PALETTE_COLORS[it] ushr 8 and 0xff }
        val PALETTE_B = IntArray(PALETTE_COLORS.size) { PALETTE_COLORS[it] and 0xff }
        val PALETTE_AMOUNTS = RAIN_PALETTE.values.toFloatArray()
    }
}
