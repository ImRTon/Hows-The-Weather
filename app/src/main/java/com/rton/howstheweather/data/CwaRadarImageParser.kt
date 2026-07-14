package com.rton.howstheweather.data

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

    fun toGrid(
        metadata: CwaRadarImageMetadata,
        width: Int,
        height: Int,
        argbPixels: IntArray,
        sourceId: String,
    ): WeatherGrid {
        require(width > 1 && height > 1)
        require(argbPixels.size == width * height)
        val values = FloatArray(argbPixels.size) { index -> decodeDbz(argbPixels[index]) }
        val midLatitude = (metadata.bounds.south + metadata.bounds.north) / 2.0
        val pixelResolutionKm = minOf(
            (metadata.bounds.east - metadata.bounds.west) / (width - 1) * 111.0 * cos(Math.toRadians(midLatitude)),
            (metadata.bounds.north - metadata.bounds.south) / (height - 1) * 111.0,
        )
        return WeatherGrid(
            width = width,
            height = height,
            values = values,
            unit = WeatherUnit.DBZ,
            bounds = metadata.bounds,
            // The PNG can contain more pixels than the underlying observation supports.
            resolutionKm = maxOf(OFFICIAL_RADAR_RESOLUTION_KM, pixelResolutionKm),
            validAt = metadata.observedAt,
            sourceId = sourceId,
        )
    }

    internal fun decodeDbz(argb: Int): Float {
        val alpha = argb ushr 24 and 0xff
        // In this annotation-free product alpha means "no visible echo", not a
        // missing numerical observation. Keeping it at zero prevents bilinear
        // sampling from eroding the edge of every echo by one source pixel.
        if (alpha < MIN_WEATHER_ALPHA) return 0f
        val rgb = argb and 0x00ffffff
        // Historical frames used by CWA's animation page are RGB composites.
        // Only exact radar-palette colors are signal; map, labels and background
        // are discarded to the same transparent no-echo value.
        val index = PALETTE_INDEX[rgb] ?: return 0f
        return index * MAX_DBZ / (RADAR_PALETTE.size - 1)
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
        const val MAX_DBZ = 65f
        const val OFFICIAL_RADAR_RESOLUTION_KM = 1.25
        val RANGE = Regex("""\s*(-?\d+(?:\.\d+)?)\s*[-~–]\s*(-?\d+(?:\.\d+)?)\s*""")

        fun rgb(red: Int, green: Int, blue: Int): Int = (red shl 16) or (green shl 8) or blue

        // O-A0058 uses a finite, non-antialiased palette. Restricting decoding to these
        // colors discards the few labels/registration marks that can occur in the PNG.
        val RADAR_PALETTE: List<Int> = buildList {
            listOf(0, 18, 36, 54, 72, 91, 109, 127, 145, 163, 182, 200, 218, 236, 255)
                .forEach { add(rgb(0, it, 255)) }
            listOf(255, 244, 233, 222, 211, 200, 190, 180, 170, 160, 150)
                .forEach { add(rgb(0, it, 0)) }
            listOf(51 to 171, 102 to 192, 153 to 213, 204 to 234, 255 to 255)
                .forEach { (red, green) -> add(rgb(red, green, 0)) }
            listOf(244, 233, 222, 211, 200, 184, 168, 152, 136, 120, 96, 72, 48, 24, 0)
                .forEach { add(rgb(255, it, 0)) }
            listOf(244, 233, 222, 211, 200, 190, 180, 170, 160, 150)
                .forEach { add(rgb(it, 0, 0)) }
            listOf(171 to 51, 192 to 102, 213 to 153, 234 to 204)
                .forEach { (red, blue) -> add(rgb(red, 0, blue)) }
        }
        val PALETTE_INDEX = RADAR_PALETTE.mapIndexed { index, color -> color to index }.toMap()
    }
}
