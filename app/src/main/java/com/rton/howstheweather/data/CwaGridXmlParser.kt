package com.rton.howstheweather.data

import android.util.Xml
import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant

/** Streaming parser for CWA common grid XML. It deliberately accepts numerical grids only. */
class CwaGridXmlParser {
    fun parse(input: InputStream, unit: WeatherUnit, sourceId: String): WeatherGrid {
        val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val fields = mutableMapOf<String, String>()
        var currentTag = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> currentTag = parser.name.substringAfter(':')
                XmlPullParser.TEXT -> if (parser.text.isNotBlank()) {
                    fields[currentTag] = fields[currentTag].orEmpty() + parser.text.trim()
                }
            }
            parser.next()
        }
        fun field(vararg names: String): String? = names.firstNotNullOfOrNull { wanted ->
            fields.entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) }?.value
        }
        val width = field("GridDimensionX", "DimensionX", "nx")?.toIntOrNull()
            ?: error("CWA XML missing grid width")
        val height = field("GridDimensionY", "DimensionY", "ny")?.toIntOrNull()
            ?: error("CWA XML missing grid height")
        val west = field("StartPointLongitude", "WestLongitude")?.toDoubleOrNull()
            ?: error("CWA XML missing west longitude")
        val startLat = field("StartPointLatitude", "NorthLatitude")?.toDoubleOrNull()
            ?: error("CWA XML missing start latitude")
        val resolution = field("GridResolution", "Resolution")?.filter { it.isDigit() || it == '.' || it == '-' }
            ?.toDoubleOrNull() ?: 0.0125
        val raw = field("Reflectivity", "Rainfall", "content", "Content")
            ?: error("CWA XML does not contain a numerical grid")
        val values = raw.split(Regex("[,\\s]+"))
            .mapNotNull(String::toFloatOrNull)
            .toFloatArray()
        require(values.size >= width * height) { "CWA grid contains ${values.size} values; expected ${width * height}" }
        val north = startLat
        val south = north - resolution * (height - 1)
        val east = west + resolution * (width - 1)
        val validAt = field("DateTime", "ValidTime", "Time")?.let(::parseInstant) ?: Instant.now()
        return WeatherGrid(
            width, height, values.copyOf(width * height), unit,
            GeoBounds(south, west, north, east),
            resolutionKm = resolution * 111.0,
            validAt = validAt,
            missingValue = -999f,
            sourceId = sourceId,
        )
    }

    private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse { Instant.now() }
}
