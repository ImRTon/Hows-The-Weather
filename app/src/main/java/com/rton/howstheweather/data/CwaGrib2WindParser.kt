package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.LambertConformalProjection
import com.rton.howstheweather.domain.WindGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.pow

enum class WindComponent { EAST, NORTH }

data class GribMessageDescriptor(
    val length: Long,
    val component: WindComponent?,
)

data class DecodedWindComponent(
    val component: WindComponent,
    val definition: LambertGridDefinition,
    val values: FloatArray,
    val validAt: java.time.Instant,
)

data class LambertGridDefinition(
    val width: Int,
    val height: Int,
    val earthRadiusMeters: Double,
    val latitudeOfFirstPoint: Double,
    val longitudeOfFirstPoint: Double,
    val latitudeOfOrigin: Double,
    val centralLongitude: Double,
    val spacingXMeters: Double,
    val spacingYMeters: Double,
    val firstStandardParallel: Double,
    val secondStandardParallel: Double,
) {
    fun combine(
        east: FloatArray,
        north: FloatArray,
        validAt: java.time.Instant,
        sourceId: String,
    ): WindGrid {
        val first = LambertConformalProjection(
            earthRadiusMeters,
            latitudeOfOrigin,
            centralLongitude,
            firstStandardParallel,
            secondStandardParallel,
        ).forward(GeoPoint(latitudeOfFirstPoint, longitudeOfFirstPoint))
        return WindGrid(
            width = width,
            height = height,
            eastMetersPerSecond = east,
            northMetersPerSecond = north,
            earthRadiusMeters = earthRadiusMeters,
            latitudeOfOriginDegrees = latitudeOfOrigin,
            centralLongitudeDegrees = centralLongitude,
            firstStandardParallelDegrees = firstStandardParallel,
            secondStandardParallelDegrees = secondStandardParallel,
            firstPointX = first.x,
            firstPointY = first.y,
            spacingXMeters = spacingXMeters,
            spacingYMeters = spacingYMeters,
            validAt = validAt,
            sourceId = sourceId,
        )
    }
}

class CwaGrib2WindParser {
    fun describe(prefix: ByteArray): GribMessageDescriptor {
        require(prefix.size >= INDICATOR_LENGTH && prefix.copyOfRange(0, 4).contentEquals(GRIB)) {
            "M-A0064 GRIB2 訊息標頭無效"
        }
        require(prefix[7].toInt() and 0xff == 2) { "M-A0064 不是 GRIB2" }
        val length = prefix.buffer().getLong(8)
        require(length >= MIN_MESSAGE_LENGTH) { "M-A0064 GRIB2 訊息長度無效" }
        val product = sections(prefix).firstOrNull { it.number == 4 }
        return GribMessageDescriptor(length, product?.let { windComponent(prefix, it) })
    }

    fun decode(message: ByteArray): DecodedWindComponent {
        val descriptor = describe(message)
        require(descriptor.length == message.size.toLong()) { "M-A0064 GRIB2 訊息未完整下載" }
        require(message.takeLast(4).toByteArray().contentEquals(END_MARKER)) { "M-A0064 GRIB2 結尾無效" }
        val sections = sections(message).associateBy(GribSection::number)
        val component = descriptor.component ?: error("M-A0064 訊息不是 10 m U/V 風場")
        val definition = parseGrid(message, sections.getValue(3))
        val values = parseValues(
            message,
            sections.getValue(5),
            sections.getValue(6),
            sections.getValue(7),
            definition.width * definition.height,
        )
        return DecodedWindComponent(
            component = component,
            definition = definition,
            values = values,
            validAt = parseValidAt(message, sections.getValue(1), sections.getValue(4)),
        )
    }

    fun combine(east: DecodedWindComponent, north: DecodedWindComponent): WindGrid {
        require(east.component == WindComponent.EAST && north.component == WindComponent.NORTH)
        require(east.definition == north.definition) { "M-A0064 U/V 網格定義不一致" }
        require(east.validAt == north.validAt) { "M-A0064 U/V 有效時間不一致" }
        return east.definition.combine(east.values, north.values, east.validAt, MODEL_SOURCE_ID)
            .croppedTo(TAIWAN_WIND_BOUNDS)
            ?: error("M-A0064 風場不涵蓋臺灣")
    }

    private fun windComponent(bytes: ByteArray, section: GribSection): WindComponent? {
        if (section.length < 34 || section.start + 34 > bytes.size) return null
        val start = section.start
        val template = bytes.unsignedShort(start + 7)
        val category = bytes[start + 9].toInt() and 0xff
        val parameter = bytes[start + 10].toInt() and 0xff
        val surfaceType = bytes[start + 22].toInt() and 0xff
        val surfaceScale = signedMagnitude(bytes[start + 23].toInt() and 0xff, 8)
        val surfaceValue = signedMagnitude(bytes.buffer().getInt(start + 24), 32) * 10.0.pow(-surfaceScale)
        if (template != 0 || category != 2 || surfaceType != 103 || surfaceValue != 10.0) return null
        return when (parameter) {
            2 -> WindComponent.EAST
            3 -> WindComponent.NORTH
            else -> null
        }
    }

    private fun parseGrid(bytes: ByteArray, section: GribSection): LambertGridDefinition {
        require(section.length >= 81) { "M-A0064 Lambert 網格定義不完整" }
        val start = section.start
        require(bytes.unsignedShort(start + 12) == 30) { "M-A0064 不是 Lambert Conformal 網格" }
        require(bytes[start + 14].toInt() and 0xff == 6) { "M-A0064 地球模型不支援" }
        val flags = bytes[start + 46].toInt() and 0xff
        require(flags and 0x08 == 0) { "M-A0064 U/V 不是相對真東與真北" }
        val scanningMode = bytes[start + 64].toInt() and 0xff
        require(scanningMode == 0x40) { "M-A0064 掃描方向不支援：$scanningMode" }
        val width = bytes.buffer().getInt(start + 30)
        val height = bytes.buffer().getInt(start + 34)
        require(width > 1 && height > 1 && width.toLong() * height <= MAX_GRID_POINTS)
        return LambertGridDefinition(
            width = width,
            height = height,
            earthRadiusMeters = 6_371_229.0,
            latitudeOfFirstPoint = signedMagnitude(bytes.buffer().getInt(start + 38), 32) / 1_000_000.0,
            longitudeOfFirstPoint = signedMagnitude(bytes.buffer().getInt(start + 42), 32) / 1_000_000.0,
            latitudeOfOrigin = signedMagnitude(bytes.buffer().getInt(start + 47), 32) / 1_000_000.0,
            centralLongitude = signedMagnitude(bytes.buffer().getInt(start + 51), 32) / 1_000_000.0,
            spacingXMeters = (bytes.buffer().getInt(start + 55).toLong() and 0xffffffffL) / 1_000.0,
            spacingYMeters = (bytes.buffer().getInt(start + 59).toLong() and 0xffffffffL) / 1_000.0,
            firstStandardParallel = signedMagnitude(bytes.buffer().getInt(start + 65), 32) / 1_000_000.0,
            secondStandardParallel = signedMagnitude(bytes.buffer().getInt(start + 69), 32) / 1_000_000.0,
        )
    }

    private fun parseValues(
        bytes: ByteArray,
        representation: GribSection,
        bitmap: GribSection,
        data: GribSection,
        expectedCount: Int,
    ): FloatArray {
        val representationStart = representation.start
        require(representation.length >= 21 && bytes.unsignedShort(representationStart + 9) == 0) {
            "M-A0064 不是 GRIB2 simple packing"
        }
        val valueCount = bytes.buffer().getInt(representationStart + 5)
        require(valueCount == expectedCount) { "M-A0064 風場格點數不符" }
        require(bitmap.length == 6 && bytes[bitmap.start + 5].toInt() and 0xff == 255) {
            "M-A0064 風場 bitmap 尚不支援"
        }
        val reference = bytes.buffer().getFloat(representationStart + 11).toDouble()
        val binaryScale = signedMagnitude(bytes.unsignedShort(representationStart + 15), 16)
        val decimalScale = signedMagnitude(bytes.unsignedShort(representationStart + 17), 16)
        val bitsPerValue = bytes[representationStart + 19].toInt() and 0xff
        require(bitsPerValue in 1..31) { "M-A0064 bits per value 不支援：$bitsPerValue" }
        val packedStart = data.start + 5
        val requiredBits = valueCount.toLong() * bitsPerValue
        require(requiredBits <= (data.length - 5L) * 8L) { "M-A0064 packed data 長度不足" }
        val binaryMultiplier = 2.0.pow(binaryScale)
        val decimalMultiplier = 10.0.pow(-decimalScale)
        return FloatArray(valueCount) { index ->
            val packed = readBits(bytes, packedStart, index.toLong() * bitsPerValue, bitsPerValue)
            ((reference + packed * binaryMultiplier) * decimalMultiplier).toFloat()
        }
    }

    private fun parseValidAt(bytes: ByteArray, identification: GribSection, product: GribSection): java.time.Instant {
        val start = identification.start
        require(identification.length >= 21)
        val reference = LocalDateTime.of(
            bytes.unsignedShort(start + 12),
            bytes[start + 14].toInt() and 0xff,
            bytes[start + 15].toInt() and 0xff,
            bytes[start + 16].toInt() and 0xff,
            bytes[start + 17].toInt() and 0xff,
            bytes[start + 18].toInt() and 0xff,
        ).toInstant(ZoneOffset.UTC)
        val productStart = product.start
        require(bytes[productStart + 17].toInt() and 0xff == 1) { "M-A0064 預報時間單位不是小時" }
        val forecastHours = bytes.buffer().getInt(productStart + 18).toLong() and 0xffffffffL
        return reference.plusSeconds(forecastHours * 3_600L)
    }

    private fun sections(bytes: ByteArray): List<GribSection> {
        val result = mutableListOf<GribSection>()
        var cursor = INDICATOR_LENGTH
        while (cursor + 5 <= bytes.size && !bytes.copyOfRange(cursor, cursor + 4).contentEquals(END_MARKER)) {
            val length = bytes.buffer().getInt(cursor).toLong() and 0xffffffffL
            require(length >= 5 && length <= Int.MAX_VALUE) { "M-A0064 GRIB2 section 長度無效" }
            result += GribSection(bytes[cursor + 4].toInt() and 0xff, cursor, length.toInt())
            if (cursor.toLong() + length > bytes.size) break
            cursor += length.toInt()
        }
        return result
    }

    private fun readBits(bytes: ByteArray, dataStart: Int, bitOffset: Long, count: Int): Long {
        var remaining = count
        var cursor = bitOffset
        var result = 0L
        while (remaining > 0) {
            val byteIndex = dataStart + (cursor / 8L).toInt()
            val offsetInByte = (cursor % 8L).toInt()
            val take = minOf(remaining, 8 - offsetInByte)
            val shift = 8 - offsetInByte - take
            val mask = (1 shl take) - 1
            result = (result shl take) or ((bytes[byteIndex].toInt() ushr shift) and mask).toLong()
            cursor += take
            remaining -= take
        }
        return result
    }

    private fun ByteArray.buffer(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)

    private fun ByteArray.unsignedShort(offset: Int): Int = buffer().getShort(offset).toInt() and 0xffff

    private fun signedMagnitude(raw: Int, bits: Int): Int {
        val signMask = 1L shl (bits - 1)
        val unsigned = raw.toLong() and ((1L shl bits) - 1L)
        val magnitude = (unsigned and (signMask - 1L)).toInt()
        return if (unsigned and signMask != 0L) -magnitude else magnitude
    }

    private data class GribSection(val number: Int, val start: Int, val length: Int)

    private companion object {
        val GRIB = byteArrayOf('G'.code.toByte(), 'R'.code.toByte(), 'I'.code.toByte(), 'B'.code.toByte())
        val END_MARKER = byteArrayOf('7'.code.toByte(), '7'.code.toByte(), '7'.code.toByte(), '7'.code.toByte())
        const val INDICATOR_LENGTH = 16
        const val MIN_MESSAGE_LENGTH = 32L
        const val MAX_GRID_POINTS = 2_000_000L
        const val MODEL_SOURCE_ID = "M-A0064-000-WRF-3KM-10M-WIND"
        val TAIWAN_WIND_BOUNDS = GeoBounds(20.0, 117.5, 27.0, 124.5)
    }
}
