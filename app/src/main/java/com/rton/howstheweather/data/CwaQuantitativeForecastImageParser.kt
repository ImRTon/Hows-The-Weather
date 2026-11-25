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
 * Only map pixels can contribute to a sample. Within the map frame, colors
 * matching CWA's rainfall legend become amounts, and dark coastlines or
 * annotations can be filled from nearby rainfall pixels.
 * The main map and each offshore inset have independent geographic calibrations.
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
        val values = FloatArray(OUTPUT_WIDTH * OUTPUT_HEIGHT) { Float.NaN }
        val longitudes = DoubleArray(OUTPUT_WIDTH) { GRID_BOUNDS.east - (OUTPUT_WIDTH - 1 - it) * LONGITUDE_STEP }
        val latitudes = DoubleArray(OUTPUT_HEIGHT) { GRID_BOUNDS.south + (OUTPUT_HEIGHT - 1 - it) * LATITUDE_STEP }

        fun samplePanel(
            bounds: GeoBounds,
            frame: ChartRegion,
            exclusions: List<ChartRegion> = emptyList(),
            project: (longitude: Double, latitude: Double) -> Pair<Double, Double>,
        ) {
            val map = frame.scaled(xScale, yScale, outward = false)
            val columns = longitudes.indices.filter { longitudes[it] in bounds.west..bounds.east }
            for (y in latitudes.indices) {
                val latitude = latitudes[y]
                if (latitude !in bounds.south..bounds.north) continue
                for (x in columns) {
                    val (referenceX, referenceY) = project(longitudes[x], latitude)
                    values[y * OUTPUT_WIDTH + x] = decodeSample(
                        (referenceX * xScale).roundToInt(), (referenceY * yScale).roundToInt(),
                        imageWidth, argbPixels, map, exclusions,
                    )
                }
            }
        }

        val mainMapExclusions = MAIN_MAP_EXCLUSIONS.map { it.scaled(xScale, yScale, outward = true) }
        samplePanel(MAIN_MAP_BOUNDS, MAIN_MAP_FRAME, mainMapExclusions, ::sourcePixel)
        for (inset in OFFSHORE_INSETS) {
            samplePanel(inset.bounds, inset.frame, project = inset::sourcePixel)
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
        map: ChartRegion,
        exclusions: List<ChartRegion> = emptyList(),
    ): Float {
        if (!map.contains(centerX, centerY) || exclusions.any { it.contains(centerX, centerY) }) return Float.NaN
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
                if (!map.contains(x, y) || exclusions.any { it.contains(x, y) }) continue
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

    internal class ChartRegion(private val columns: IntRange, private val rows: IntRange) {
        fun contains(x: Int, y: Int): Boolean = x in columns && y in rows

        fun scaled(xScale: Double, yScale: Double, outward: Boolean): ChartRegion {
            fun scale(range: IntRange, factor: Double): IntRange {
                val start = range.first * factor
                val end = range.last * factor
                return if (outward) floor(start).toInt()..ceil(end).toInt()
                else ceil(start).toInt()..floor(end).toInt()
            }
            return ChartRegion(scale(columns, xScale), scale(rows, yScale))
        }
    }

    private class ChartInset(
        val bounds: GeoBounds,
        val frame: ChartRegion,
        private val longitudeOrigin: Double,
        private val latitudeOrigin: Double,
        private val pixelX: Double,
        private val pixelY: Double,
        private val xPerLongitude: Double,
        private val xPerLatitude: Double,
        private val yPerLongitude: Double,
        private val yPerLatitude: Double,
    ) {
        fun sourcePixel(longitude: Double, latitude: Double): Pair<Double, Double> {
            val dx = longitude - longitudeOrigin
            val dy = latitude - latitudeOrigin
            return (pixelX + xPerLongitude * dx + xPerLatitude * dy) to
                (pixelY + yPerLongitude * dx + yPerLatitude * dy)
        }
    }

    private companion object {
        // Quarter steps of the main map's original sampling grid (~500 m) preserve
        // the shapes of the small Matsu islands. The forecast resolution is 2.5 km.
        const val OUTPUT_WIDTH = 869
        const val OUTPUT_HEIGHT = 1053
        const val LONGITUDE_STEP = (122.2 - 119.2) / (4 * 159)
        const val LATITUDE_STEP = (25.52 - 21.72) / (4 * 211)
        val MAIN_MAP_BOUNDS = GeoBounds(south = 21.72, west = 119.2, north = 25.52, east = 122.2)
        val GRID_BOUNDS = GeoBounds(
            south = MAIN_MAP_BOUNDS.south,
            west = MAIN_MAP_BOUNDS.east - (OUTPUT_WIDTH - 1) * LONGITUDE_STEP,
            north = MAIN_MAP_BOUNDS.south + (OUTPUT_HEIGHT - 1) * LATITUDE_STEP,
            east = MAIN_MAP_BOUNDS.east,
        )
        const val REFERENCE_IMAGE_WIDTH = 1245.0
        const val REFERENCE_IMAGE_HEIGHT = 1500.0
        val MAIN_MAP_FRAME = ChartRegion(14..1229, 154..1487)
        val MAIN_MAP_EXCLUSIONS = listOf(
            ChartRegion(1157..1196, 689..1406), // Rainfall color bar.
            ChartRegion(89..501, 199..481), // Matsu inset block, including its frame.
            ChartRegion(89..321, 499..661), // Kinmen inset and frame.
        )

        // Affine transforms fitted to NLSC TWD97 shorelines (data.gov.tw/dataset/7441)
        // in the 1245 × 1500 F-C0035 charts. The pixel frames clip the geographic search bounds.
        val OFFSHORE_INSETS = listOf(
            // Nangan and Beigan (including Gaodeng).
            ChartInset(
                bounds = GeoBounds(south = 26.09, west = 119.87, north = 26.33, east = 120.06),
                frame = ChartRegion(92..318, 202..478),
                longitudeOrigin = 119.96, latitudeOrigin = 26.22,
                pixelX = 199.22, pixelY = 333.26,
                xPerLongitude = 1290.3, xPerLatitude = -15.9,
                yPerLongitude = -10.1, yPerLatitude = -1255.7,
            ),
            // Dongyin and Xiyin.
            ChartInset(
                bounds = GeoBounds(south = 26.31, west = 120.41, north = 26.44, east = 120.56),
                frame = ChartRegion(322..498, 202..343),
                longitudeOrigin = 120.474, latitudeOrigin = 26.368,
                pixelX = 394.56, pixelY = 280.30,
                xPerLongitude = 1300.8, xPerLatitude = 53.3,
                yPerLongitude = -6.1, yPerLatitude = -1301.7,
            ),
            // Dongju and Xiju.
            ChartInset(
                bounds = GeoBounds(south = 25.91, west = 119.88, north = 26.03, east = 120.03),
                frame = ChartRegion(322..498, 347..478),
                longitudeOrigin = 119.966, latitudeOrigin = 25.969,
                pixelX = 426.48, pixelY = 415.27,
                xPerLongitude = 1268.7, xPerLatitude = 0.0,
                yPerLongitude = -10.7, yPerLatitude = -1299.0,
            ),
            // Kinmen and Lieyu.
            ChartInset(
                bounds = GeoBounds(south = 24.32, west = 118.13, north = 24.61, east = 118.55),
                frame = ChartRegion(92..318, 502..658),
                longitudeOrigin = 118.33, latitudeOrigin = 24.45,
                pixelX = 198.49, pixelY = 588.89,
                xPerLongitude = 561.1, xPerLatitude = -5.9,
                yPerLongitude = 1.7, yPerLatitude = -573.5,
            ),
        )
        const val MIN_ALPHA = 240
        const val MAX_CHANNEL_TOLERANCE = 2
        const val MAX_INPAINT_RADIUS = 3
        const val OFFICIAL_GRID_RESOLUTION_KM = 2.5

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
