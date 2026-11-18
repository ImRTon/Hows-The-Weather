package com.rton.howstheweather.data

import android.graphics.Bitmap
import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import org.json.JSONObject
import java.time.OffsetDateTime
import kotlin.math.cos

data class CwaRadarImageMetadata(
    val bounds: GeoBounds,
    val observedAt: java.time.Instant,
    val productUrl: String,
)

/** Decodes CWA's annotation-free O-A0058 transparent radar PNG into numerical dBZ bins. */
class CwaRadarImageParser {
    fun parseMetadata(json: String): CwaRadarImageMetadata {
        val dataset = JSONObject(json).getJSONObject("cwaopendata").getJSONObject("dataset")
        val info = dataset.getJSONObject("datasetInfo").getJSONObject("parameterSet")
        val (west, east) = parseRange(info.getString("LongitudeRange"))
        val (south, north) = parseRange(info.getString("LatitudeRange"))
        val resource = dataset.objectFor("resource", "Resource")
        return CwaRadarImageMetadata(
            bounds = GeoBounds(south, west, north, east),
            observedAt = OffsetDateTime.parse(dataset.stringFor("DateTime", "Datetime", "dateTime")).toInstant(),
            productUrl = resource.stringFor("ProductURL", "productURL"),
        )
    }

    /**
     * Converts a full-resolution radar [bitmap] into a downsampled [WeatherGrid] by reading
     * horizontal strips of [sampleSize] rows at a time, avoiding a 51.8 MB full-frame `IntArray`
     * allocation on the managed heap while aggregating every `sampleSize × sampleSize` block.
     */
    fun toGrid(
        bitmap: Bitmap,
        metadata: CwaRadarImageMetadata,
        sourceId: String,
        sampleSize: Int = 1,
    ): WeatherGrid {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 1 && height > 1)
        val blockSize = sampleSize.coerceAtLeast(1)
        val outWidth = (width / blockSize).coerceAtLeast(2)
        val outHeight = (height / blockSize).coerceAtLeast(2)
        val values = FloatArray(outWidth * outHeight)
        val stripBlockRows = 32
        val maxStripRows = (stripBlockRows * blockSize).coerceAtMost(height)
        val stripPixels = IntArray(width * maxStripRows)
        var blockRowStart = 0
        while (blockRowStart < outHeight) {
            val blockRowEnd = (blockRowStart + stripBlockRows).coerceAtMost(outHeight)
            val yStart = blockRowStart * blockSize
            val yEnd = if (blockRowEnd == outHeight) height else (blockRowEnd * blockSize).coerceAtMost(height)
            val stripHeight = yEnd - yStart
            bitmap.getPixels(stripPixels, 0, width, 0, yStart, width, stripHeight)
            decodeStripToBlocks(
                stripPixels = stripPixels,
                imageWidth = width,
                stripHeight = stripHeight,
                outWidth = outWidth,
                blockRowStart = blockRowStart,
                blockRowEnd = blockRowEnd,
                blockSize = blockSize,
                target = values,
            )
            blockRowStart = blockRowEnd
        }
        val midLatitude = (metadata.bounds.south + metadata.bounds.north) / 2.0
        val pixelResolutionKm = minOf(
            (metadata.bounds.east - metadata.bounds.west) / (outWidth - 1) * 111.0 * cos(Math.toRadians(midLatitude)),
            (metadata.bounds.north - metadata.bounds.south) / (outHeight - 1) * 111.0,
        )
        return WeatherGrid(
            width = outWidth,
            height = outHeight,
            values = values,
            unit = WeatherUnit.DBZ,
            bounds = metadata.bounds,
            resolutionKm = maxOf(OFFICIAL_RADAR_RESOLUTION_KM, pixelResolutionKm),
            validAt = metadata.observedAt,
            sourceId = sourceId,
        )
    }

    fun toGrid(
        metadata: CwaRadarImageMetadata,
        width: Int,
        height: Int,
        argbPixels: IntArray,
        sourceId: String,
        sampleSize: Int = 1,
    ): WeatherGrid {
        require(width > 1 && height > 1)
        require(argbPixels.size == width * height)
        require(sampleSize >= 1)
        val blockSize = sampleSize.coerceAtLeast(1)
        val outWidth = (width / blockSize).coerceAtLeast(2)
        val outHeight = (height / blockSize).coerceAtLeast(2)
        val values = if (blockSize == 1 && outWidth == width && outHeight == height) {
            FloatArray(argbPixels.size) { index -> decodeDbz(argbPixels[index]) }
        } else {
            FloatArray(outWidth * outHeight).also { target ->
                for (by in 0 until outHeight) {
                    val yStart = by * height / outHeight
                    val yEnd = ((by + 1) * height / outHeight).coerceAtLeast(yStart + 1)
                    for (bx in 0 until outWidth) {
                        val xStart = bx * width / outWidth
                        val xEnd = ((bx + 1) * width / outWidth).coerceAtLeast(xStart + 1)
                        target[by * outWidth + bx] = aggregateBlockDbz(
                            argbPixels = argbPixels,
                            stride = width,
                            xStart = xStart,
                            xEnd = xEnd,
                            yStart = yStart,
                            yEnd = yEnd,
                        )
                    }
                }
            }
        }
        val midLatitude = (metadata.bounds.south + metadata.bounds.north) / 2.0
        val pixelResolutionKm = minOf(
            (metadata.bounds.east - metadata.bounds.west) / (outWidth - 1) * 111.0 * cos(Math.toRadians(midLatitude)),
            (metadata.bounds.north - metadata.bounds.south) / (outHeight - 1) * 111.0,
        )
        return WeatherGrid(
            width = outWidth,
            height = outHeight,
            values = values,
            unit = WeatherUnit.DBZ,
            bounds = metadata.bounds,
            // The PNG can contain more pixels than the underlying observation supports.
            resolutionKm = maxOf(OFFICIAL_RADAR_RESOLUTION_KM, pixelResolutionKm),
            validAt = metadata.observedAt,
            sourceId = sourceId,
        )
    }

    /**
     * Decodes a strip of full-resolution rows into [target] block cells (`[blockRowStart, blockRowEnd)`),
     * aggregating echo pixels using coverage-weighted mean reflectivity so thin bands and small cells
     * are preserved instead of being dropped by point-sampling.
     */
    internal fun decodeStripToBlocks(
        stripPixels: IntArray,
        imageWidth: Int,
        stripHeight: Int,
        outWidth: Int,
        blockRowStart: Int,
        blockRowEnd: Int,
        blockSize: Int,
        target: FloatArray,
    ) {
        val blockArea = (blockSize * blockSize).toFloat()
        for (by in blockRowStart until blockRowEnd) {
            val localYStart = (by - blockRowStart) * blockSize
            val localYEnd = (localYStart + blockSize).coerceAtMost(stripHeight)
            val rowOffset = by * outWidth
            for (bx in 0 until outWidth) {
                val xStart = bx * blockSize
                val xEnd = (xStart + blockSize).coerceAtMost(imageWidth)
                target[rowOffset + bx] = aggregateBlockDbz(
                    argbPixels = stripPixels,
                    stride = imageWidth,
                    xStart = xStart,
                    xEnd = xEnd,
                    yStart = localYStart,
                    yEnd = localYEnd,
                    nominalArea = blockArea,
                )
            }
        }
    }

    internal fun aggregateBlockDbz(
        argbPixels: IntArray,
        stride: Int,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        nominalArea: Float = ((xEnd - xStart) * (yEnd - yStart)).toFloat(),
    ): Float {
        var echoSum = 0f
        var echoCount = 0
        for (y in yStart until yEnd) {
            val rowBase = y * stride
            for (x in xStart until xEnd) {
                val dbz = decodeDbzOrNegative(argbPixels[rowBase + x])
                if (dbz >= 0f) {
                    echoSum += dbz
                    echoCount++
                }
            }
        }
        if (echoCount == 0) return 0f
        val echoMean = echoSum / echoCount
        val coverage = (echoCount / nominalArea.coerceAtLeast(1f)).coerceIn(0f, 1f)
        // Soft coverage taper: full block keeps echoMean; partial edge/thin-band blocks
        // taper smoothly via sqrt(coverage) so anti-aliased edges neither bloat nor vanish.
        return echoMean * kotlin.math.sqrt(coverage)
    }

    internal fun decodeDbz(argb: Int): Float {
        val decoded = decodeDbzOrNegative(argb)
        return if (decoded < 0f) 0f else decoded
    }

    private fun decodeDbzOrNegative(argb: Int): Float {
        val alpha = argb ushr 24 and 0xff
        // In this annotation-free product alpha means "no visible echo", not a
        // missing numerical observation. Keeping it at zero prevents bilinear
        // sampling from eroding the edge of every echo by one source pixel.
        if (alpha < MIN_WEATHER_ALPHA) return -1f
        val rgb = argb and 0x00ffffff
        // Historical frames used by CWA's animation page are RGB composites.
        // Only exact radar-palette colors are signal; map, labels and background
        // are discarded to the same transparent no-echo value.
        return lookupPaletteDbz(rgb)
    }

    private fun parseRange(value: String): Pair<Double, Double> {
        val match = RANGE.matchEntire(value) ?: error("無法解析雷達影像範圍：$value")
        return match.groupValues[1].toDouble() to match.groupValues[2].toDouble()
    }

    private fun JSONObject.objectFor(vararg keys: String): JSONObject = keys.firstNotNullOfOrNull(::optJSONObject)
        ?: error("缺少雷達影像欄位：${keys.joinToString()}")

    private fun JSONObject.stringFor(vararg keys: String): String = keys.firstNotNullOfOrNull {
        optString(it).takeIf(String::isNotBlank)
    } ?: error("缺少雷達影像欄位：${keys.joinToString()}")

    private companion object {
        const val MIN_WEATHER_ALPHA = 240
        const val OFFICIAL_RADAR_RESOLUTION_KM = 1.25
        val RANGE = Regex("""\s*(-?\d+(?:\.\d+)?)\s*[-~–]\s*(-?\d+(?:\.\d+)?)\s*""")

        fun rgb(red: Int, green: Int, blue: Int): Int = (red shl 16) or (green shl 8) or blue

        // Official CWA radar colorbar (`colorbar_n.png`) has 66 discrete 1-dBZ swatches (0..65 dBZ).
        // Restricting decoding to these exact RGB values discards map lines, labels and background
        // in historical composite frames while preserving 1:1 dBZ calibration against O-A0059-001.
        val RADAR_PALETTE: List<Int> = buildList {
            // 0..14 dBZ: cyan (0, 255, 255) down to pure blue (0, 0, 255).
            listOf(255, 236, 218, 200, 182, 163, 145, 127, 109, 91, 72, 54, 36, 18, 0)
                .forEach { add(rgb(0, it, 255)) }
            // 15..25 dBZ: greens.
            listOf(255, 244, 233, 222, 211, 200, 190, 180, 170, 160, 150)
                .forEach { add(rgb(0, it, 0)) }
            // 26..30 dBZ: yellow-greens to yellow.
            listOf(51 to 171, 102 to 192, 153 to 213, 204 to 234, 255 to 255)
                .forEach { (red, green) -> add(rgb(red, green, 0)) }
            // 31..45 dBZ: yellows, oranges, and bright red.
            listOf(244, 233, 222, 211, 200, 184, 168, 152, 136, 120, 96, 72, 48, 24, 0)
                .forEach { add(rgb(255, it, 0)) }
            // 46..55 dBZ: dark reds.
            listOf(244, 233, 222, 211, 200, 190, 180, 170, 160, 150)
                .forEach { add(rgb(it, 0, 0)) }
            // 56..59 dBZ: magentas.
            listOf(171 to 51, 192 to 102, 213 to 153, 234 to 204)
                .forEach { (red, blue) -> add(rgb(red, 0, blue)) }
            // 60..65 dBZ: bright magenta to violet.
            listOf(255, 234, 213, 192, 171, 150)
                .forEach { add(rgb(it, 0, 255)) }
        }

        private const val HASH_BITS = 8
        private const val HASH_SIZE = 1 shl HASH_BITS
        private const val HASH_MASK = HASH_SIZE - 1
        private const val HASH_MULTIPLIER = -0x61c88647 // 0x9E3779B9

        private val HASH_KEYS = IntArray(HASH_SIZE) { -1 }
        private val HASH_DBZ = FloatArray(HASH_SIZE) { -1f }

        init {
            RADAR_PALETTE.forEachIndexed { index, color ->
                var slot = ((color * HASH_MULTIPLIER) ushr (32 - HASH_BITS)) and HASH_MASK
                while (HASH_KEYS[slot] != -1) {
                    slot = (slot + 1) and HASH_MASK
                }
                HASH_KEYS[slot] = color
                HASH_DBZ[slot] = index.toFloat()
            }
        }

        fun lookupPaletteDbz(rgb: Int): Float {
            var slot = ((rgb * HASH_MULTIPLIER) ushr (32 - HASH_BITS)) and HASH_MASK
            while (true) {
                val key = HASH_KEYS[slot]
                if (key == rgb) return HASH_DBZ[slot]
                if (key == -1) return -1f
                slot = (slot + 1) and HASH_MASK
            }
        }
    }
}
