package com.rton.howstheweather.data

import android.content.res.Resources
import com.rton.howstheweather.R
import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.LambertConformalProjection
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos

data class CwaSatelliteImageMetadata(
    val bounds: GeoBounds,
    val observedAt: java.time.Instant,
    val productUrl: String,
)

/** Parses CWA satellite metadata and turns the neutral IR image into cloud opacity data. */
class CwaSatelliteImageParser private constructor(
    private val precomputedEastAsiaProjection: PrecomputedProjection?,
) {
    constructor() : this(null)

    fun parseMetadata(json: String): CwaSatelliteImageMetadata {
        val dataset = JSONObject(json).getJSONObject("cwaopendata").getJSONObject("dataset")
        val geoInfo = dataset.objectFor("GeoInfo", "geoInfo")
        val (west, east) = parseRange(geoInfo.getString("LongitudeRange"))
        val (south, north) = parseRange(geoInfo.getString("LatitudeRange"))
        return CwaSatelliteImageMetadata(
            bounds = GeoBounds(south = south, west = west, north = north, east = east),
            observedAt = OffsetDateTime.parse(
                dataset.objectFor("ObsTime", "obsTime").stringFor("DateTime", "Datetime", "dateTime"),
            ).toInstant(),
            productUrl = dataset.objectFor("Resource", "resource").stringFor("ProductURL", "productURL"),
        )
    }

    fun toGrid(
        metadata: CwaSatelliteImageMetadata,
        width: Int,
        height: Int,
        argbPixels: IntArray,
        sourceId: String,
        maxGridDimension: Int = MAX_GRID_DIMENSION,
    ): WeatherGrid {
        require(width > 1 && height > 1)
        require(argbPixels.size == width * height)
        require(maxGridDimension >= 2)
        if (sourceId.startsWith(TAIWAN_DATASET_ID)) {
            return toDirectGrid(metadata, width, height, argbPixels, sourceId)
        }
        require(sourceId.startsWith(EAST_ASIA_DATASET_ID)) { "不支援的衛星投影：$sourceId" }
        val scale = maxOf(width, height).toDouble() / maxGridDimension
        val gridWidth = if (scale <= 1.0) width else (width / scale).toInt().coerceAtLeast(2)
        val gridHeight = if (scale <= 1.0) height else (height / scale).toInt().coerceAtLeast(2)
        val values = FloatArray(gridWidth * gridHeight) { Float.NaN }
        val luminancePixels = IntArray(argbPixels.size) { luminance(argbPixels[it]) }
        val neighborhoodSamples = IntArray(25)
        val projectedPixels = projectionIndices(metadata, width, height, gridWidth, gridHeight)
        for (y in 0 until gridHeight) {
            for (x in 0 until gridWidth) {
                val sourceIndex = projectedPixels[y * gridWidth + x]
                if (sourceIndex < 0) continue
                val sourceX = sourceIndex % width
                val sourceY = sourceIndex / width
                val luminance = lowerQuartileLuminance(
                    luminancePixels, width, height, sourceX, sourceY, neighborhoodSamples,
                )
                values[y * gridWidth + x] = luminance.takeIf { it >= MIN_VISIBLE_LUMINANCE } ?: 0f
            }
        }
        val midLatitude = (metadata.bounds.south + metadata.bounds.north) / 2.0
        val lonResolution = (metadata.bounds.east - metadata.bounds.west) / (gridWidth - 1)
        val latResolution = (metadata.bounds.north - metadata.bounds.south) / (gridHeight - 1)
        val approximateResolutionKm = kotlin.math.abs(minOf(
            lonResolution * 111.0 * kotlin.math.cos(Math.toRadians(midLatitude)),
            latResolution * 111.0,
        ))
        return WeatherGrid(
            width = gridWidth,
            height = gridHeight,
            values = values,
            unit = WeatherUnit.LUMINANCE,
            bounds = metadata.bounds,
            resolutionKm = approximateResolutionKm,
            validAt = metadata.observedAt,
            sourceId = sourceId,
        )
    }

    private fun projectionIndices(
        metadata: CwaSatelliteImageMetadata,
        imageWidth: Int,
        imageHeight: Int,
        gridWidth: Int,
        gridHeight: Int,
    ): IntArray {
        val key = ProjectionKey(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            gridWidth = gridWidth,
            gridHeight = gridHeight,
            south = metadata.bounds.south,
            west = metadata.bounds.west,
            north = metadata.bounds.north,
            east = metadata.bounds.east,
        )
        precomputedEastAsiaProjection?.takeIf { it.key == key }?.let { return it.indices }
        return projectionCache.computeIfAbsent(key) {
            IntArray(gridWidth * gridHeight) { index ->
                val x = index % gridWidth
                val y = index / gridWidth
                val latitude = metadata.bounds.north -
                    y.toDouble() / (gridHeight - 1) * (metadata.bounds.north - metadata.bounds.south)
                val longitude = metadata.bounds.west +
                    x.toDouble() / (gridWidth - 1) * (metadata.bounds.east - metadata.bounds.west)
                projectToEastAsia(latitude, longitude, imageWidth, imageHeight)?.let { (pixelX, pixelY) ->
                    pixelY * imageWidth + pixelX
                } ?: -1
            }
        }
    }

    /** Taiwan products are already rendered on a regular longitude/latitude rectangle. */
    private fun toDirectGrid(
        metadata: CwaSatelliteImageMetadata,
        width: Int,
        height: Int,
        argbPixels: IntArray,
        sourceId: String,
    ): WeatherGrid {
        val values = FloatArray(width * height)
        val luminancePixels = IntArray(argbPixels.size) { luminance(argbPixels[it]) }
        val neighborhoodSamples = IntArray(25)
        for (y in 0 until height) for (x in 0 until width) {
            // The source includes thick white coordinate/coast lines. A lower neighborhood
            // percentile removes narrow annotations while retaining broad cloud structures.
            val luminance = lowerQuartileLuminance(
                luminancePixels, width, height, x, y, neighborhoodSamples,
            )
            values[y * width + x] = luminance.takeIf { it >= MIN_VISIBLE_LUMINANCE } ?: 0f
        }
        val midLatitude = (metadata.bounds.south + metadata.bounds.north) / 2.0
        val resolutionKm = minOf(
            (metadata.bounds.east - metadata.bounds.west) / (width - 1) * 111.0 *
                cos(Math.toRadians(midLatitude)),
            (metadata.bounds.north - metadata.bounds.south) / (height - 1) * 111.0,
        )
        return WeatherGrid(
            width = width,
            height = height,
            values = values,
            unit = WeatherUnit.LUMINANCE,
            bounds = metadata.bounds,
            resolutionKm = resolutionKm,
            validAt = metadata.observedAt,
            sourceId = sourceId,
        )
    }

    /** Converts WGS84 coordinates into CWA's fixed East Asia Lambert image. */
    internal fun projectToEastAsia(
        latitude: Double,
        longitude: Double,
        imageWidth: Int,
        imageHeight: Int,
    ): Pair<Int, Int>? {
        if (latitude !in -89.999..89.999 || longitude !in -180.0..360.0) return null
        val projected = EAST_ASIA_PROJECTION.forward(GeoPoint(latitude, longitude))
        val normalizedX = (projected.x - EAST_ASIA_LOWER_LEFT.x) /
            (EAST_ASIA_UPPER_RIGHT.x - EAST_ASIA_LOWER_LEFT.x)
        val normalizedY = (projected.y - EAST_ASIA_LOWER_LEFT.y) /
            (EAST_ASIA_UPPER_RIGHT.y - EAST_ASIA_LOWER_LEFT.y)
        if (normalizedX !in 0.0..1.0 || normalizedY !in 0.0..1.0) return null
        val pixelX = (normalizedX * (imageWidth - 1)).toInt()
        val pixelY = ((1.0 - normalizedY) * (imageHeight - 1)).toInt()
        return if (pixelX in 0 until imageWidth && pixelY in 0 until imageHeight) pixelX to pixelY else null
    }

    private fun luminance(color: Int): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return (red * 0.2126f + green * 0.7152f + blue * 0.0722f).toInt().coerceIn(0, 255)
    }

    private fun lowerQuartileLuminance(
        luminancePixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        samples: IntArray,
    ): Float {
        var index = 0
        for (offsetY in -2..2) for (offsetX in -2..2) {
            val sampleX = (x + offsetX).coerceIn(0, width - 1)
            val sampleY = (y + offsetY).coerceIn(0, height - 1)
            samples[index++] = luminancePixels[sampleY * width + sampleX]
        }
        return selectKth(samples, LOWER_QUARTILE_INDEX) / 255f
    }

    internal fun selectKth(values: IntArray, k: Int): Int {
        require(k in values.indices)
        var left = 0
        var right = values.lastIndex
        while (left < right) {
            val pivot = values[(left + right) ushr 1]
            var low = left
            var high = right
            while (low <= high) {
                while (values[low] < pivot) low++
                while (values[high] > pivot) high--
                if (low <= high) {
                    val value = values[low]
                    values[low] = values[high]
                    values[high] = value
                    low++
                    high--
                }
            }
            when {
                k <= high -> right = high
                k >= low -> left = low
                else -> return values[k]
            }
        }
        return values[left]
    }

    private fun parseRange(value: String): Pair<Double, Double> {
        val match = RANGE.matchEntire(value)
            ?: error("無法解析衛星影像範圍：$value")
        return match.groupValues[1].toDouble() to match.groupValues[2].toDouble()
    }

    private fun JSONObject.objectFor(vararg keys: String): JSONObject = keys.firstNotNullOfOrNull {
        optJSONObject(it)
    } ?: error("缺少衛星影像欄位：${keys.joinToString()}")

    private fun JSONObject.stringFor(vararg keys: String): String = keys.firstNotNullOfOrNull {
        optString(it).takeIf(String::isNotBlank)
    } ?: error("缺少衛星影像欄位：${keys.joinToString()}")

    companion object {
        fun withBundledEastAsiaProjection(resources: Resources): CwaSatelliteImageParser =
            CwaSatelliteImageParser(
                runCatching { readBundledEastAsiaProjection(resources) }.getOrNull(),
            )

        private fun readBundledEastAsiaProjection(resources: Resources): PrecomputedProjection =
            DataInputStream(
                BufferedInputStream(resources.openRawResource(R.raw.east_asia_projection_lookup)),
            ).use { input ->
                check(input.readInt() == PRECOMPUTED_PROJECTION_MAGIC) {
                    "東亞投影索引格式錯誤"
                }
                check(input.readInt() == PRECOMPUTED_PROJECTION_VERSION) {
                    "東亞投影索引版本不支援"
                }
                val key = ProjectionKey(
                    imageWidth = input.readInt(),
                    imageHeight = input.readInt(),
                    gridWidth = input.readInt(),
                    gridHeight = input.readInt(),
                    south = input.readDouble(),
                    west = input.readDouble(),
                    north = input.readDouble(),
                    east = input.readDouble(),
                )
                val count = input.readInt()
                check(count == key.gridWidth * key.gridHeight) {
                    "東亞投影索引長度錯誤"
                }
                PrecomputedProjection(key, IntArray(count) { input.readInt() })
            }

        private const val PRECOMPUTED_PROJECTION_MAGIC = 0x45415031
        private const val PRECOMPUTED_PROJECTION_VERSION = 1
        private const val MIN_VISIBLE_LUMINANCE = 0.06f
        private const val LOWER_QUARTILE_INDEX = 6
        private const val MAX_GRID_DIMENSION = 800
        private const val TAIWAN_DATASET_ID = "O-C0042-004"
        private const val EAST_ASIA_DATASET_ID = "O-B0032-003"
        private val EAST_ASIA_PROJECTION = LambertConformalProjection(
            radius = 6_371_000.0,
            latitudeOfOriginDegrees = 0.0,
            centralLongitudeDegrees = 128.5,
            firstStandardParallelDegrees = 30.0,
            secondStandardParallelDegrees = 60.0,
        )
        private val EAST_ASIA_LOWER_LEFT = EAST_ASIA_PROJECTION.forward(GeoPoint(-1.503, 102.111))
        private val EAST_ASIA_UPPER_RIGHT = EAST_ASIA_PROJECTION.forward(GeoPoint(48.589, 155.270))
        private val RANGE = Regex("""\s*(-?\d+(?:\.\d+)?)\s*[-~–]\s*(-?\d+(?:\.\d+)?)\s*""")
        private val projectionCache = ConcurrentHashMap<ProjectionKey, IntArray>()
    }
}

private data class PrecomputedProjection(
    val key: ProjectionKey,
    val indices: IntArray,
)

private data class ProjectionKey(
    val imageWidth: Int,
    val imageHeight: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)
