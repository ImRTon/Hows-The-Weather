package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import org.json.JSONObject
import java.time.OffsetDateTime

/** Parses the current CWA common JSON grid format without allocating one String per cell. */
class CwaJsonGridParser {
    fun parse(json: String, unit: WeatherUnit, sourceId: String): WeatherGrid {
        val root = JSONObject(json).getJSONObject("cwaopendata").getJSONObject("dataset")
        val parameters = root.getJSONObject("datasetInfo").getJSONObject("parameterSet")
        val width = parameters.getString("GridDimensionX").toInt()
        val height = parameters.getString("GridDimensionY").toInt()
        val west = parameters.getString("StartPointLongitude").toDouble()
        val south = parameters.getString("StartPointLatitude").toDouble()
        val resolution = parameters.getString("GridResolution").toDouble()
        val content = root.getJSONObject("contents").getString("content")
        val values = parseSouthToNorthRows(content, width, height)
        return WeatherGrid(
            width = width,
            height = height,
            values = values,
            unit = unit,
            bounds = GeoBounds(
                south = south,
                west = west,
                north = south + resolution * (height - 1),
                east = west + resolution * (width - 1),
            ),
            resolutionKm = resolution * 111.0,
            validAt = OffsetDateTime.parse(parameters.getString("DateTime")).toInstant(),
            missingValue = Float.NaN,
            sourceId = sourceId,
        )
    }

    private fun parseSouthToNorthRows(content: String, width: Int, height: Int): FloatArray {
        val expected = width * height
        val result = FloatArray(expected) { Float.NaN }
        var sourceIndex = 0
        var tokenStart = 0
        for (index in 0..content.length) {
            if (index != content.length && content[index] != ',') continue
            if (sourceIndex >= expected) break
            val raw = content.substring(tokenStart, index).trim().toFloatOrNull()
            val sourceY = sourceIndex / width
            val x = sourceIndex % width
            val targetY = height - 1 - sourceY
            result[targetY * width + x] = raw?.takeUnless { it <= -90f } ?: Float.NaN
            sourceIndex++
            tokenStart = index + 1
        }
        require(sourceIndex == expected) { "CWA $width x $height grid contains $sourceIndex values" }
        return result
    }
}
