package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.GeoBounds
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CwaGrib2WindParserTest {
    private val parser = CwaGrib2WindParser()

    @Test
    fun `decodes simple-packed ten-meter U component`() {
        val decoded = parser.decode(message(parameter = 2, values = byteArrayOf(1, 2, 3, 4)))

        assertEquals(WindComponent.EAST, decoded.component)
        assertEquals(2, decoded.definition.width)
        assertEquals(2, decoded.definition.height)
        assertEquals(Instant.parse("2026-07-12T06:00:00Z"), decoded.validAt)
        assertEquals(listOf(1f, 2f, 3f, 4f), decoded.values.toList())
    }

    @Test
    fun `Lambert definition round trips grid coordinates`() {
        val decoded = parser.decode(message(parameter = 2, values = byteArrayOf(1, 2, 3, 4)))
        val grid = decoded.definition.combine(
            east = decoded.values,
            north = floatArrayOf(5f, 6f, 7f, 8f),
            validAt = decoded.validAt,
            sourceId = "TEST-M-A0064",
        )

        val first = grid.coordinateAt(0, 0)
        assertEquals(14.02224, first.latitude, 0.00001)
        assertEquals(105.25, first.longitude, 0.00001)
        val sampled = grid.sample(first)
        assertNotNull(sampled)
        assertEquals(1f, sampled!!.eastMetersPerSecond, 0.001f)
        assertEquals(5f, sampled.northMetersPerSecond, 0.001f)
    }

    @Test
    fun `cropped decode reads only requested packed cells and preserves samples`() {
        val width = 20
        val height = 20
        val values = ByteArray(width * height) { (it % 251).toByte() }
        val message = message(parameter = 2, values = values, width = width, height = height)
        val full = parser.decode(message)
        val fullGrid = full.definition.combine(
            east = full.values,
            north = FloatArray(full.values.size),
            validAt = full.validAt,
            sourceId = "TEST-FULL",
        )
        val center = fullGrid.coordinateAt(width / 2, height / 2)
        val bounds = GeoBounds(
            south = center.latitude - 0.01,
            west = center.longitude - 0.01,
            north = center.latitude + 0.01,
            east = center.longitude + 0.01,
        )

        val cropped = parser.decode(message, bounds)
        val croppedGrid = cropped.definition.combine(
            east = cropped.values,
            north = FloatArray(cropped.values.size),
            validAt = cropped.validAt,
            sourceId = "TEST-CROPPED",
        )

        assertEquals(true, cropped.values.size < full.values.size)
        assertEquals(
            fullGrid.sample(center)!!.eastMetersPerSecond,
            croppedGrid.sample(center)!!.eastMetersPerSecond,
            0.001f,
        )
    }

    private fun message(
        parameter: Int,
        values: ByteArray,
        width: Int = 2,
        height: Int = 2,
    ): ByteArray {
        require(values.size == width * height)
        val identification = ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(21)
            put(1.toByte())
            putShort(0)
            putShort(0)
            put(2.toByte())
            put(0.toByte())
            put(1.toByte())
            putShort(2026)
            put(7.toByte())
            put(12.toByte())
            put(6.toByte())
            put(0.toByte())
            put(0.toByte())
            put(0.toByte())
            put(1.toByte())
        }.array()
        val grid = hex(
            "000000510300000be4460000001e0600000000000000000000000000000000000486" +
                "000002a100d5f6600645fcd0300098968007270e00002dc6c0002dc6c000400098968" +
                "002625a00855d4a8000000000",
        ).also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(6, values.size)
                putInt(30, width)
                putInt(34, height)
            }
        }
        val product = hex("0000002204000000000202020002000000010000000067000000000aff0000000000")
            .also { it[10] = parameter.toByte() }
        val representation = ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(21)
            put(5.toByte())
            putInt(values.size)
            putShort(0)
            putFloat(0f)
            putShort(0)
            putShort(0)
            put(8.toByte())
            put(0.toByte())
        }.array()
        val bitmap = byteArrayOf(0, 0, 0, 6, 6, 0xff.toByte())
        val data = ByteBuffer.allocate(5 + values.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(5 + values.size)
            put(7.toByte())
            put(values)
        }.array()
        val sections = listOf(identification, grid, product, representation, bitmap, data)
        val totalLength = 16L + sections.sumOf(ByteArray::size) + 4L
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { stream ->
                stream.writeBytes("GRIB")
                stream.writeShort(0)
                stream.writeByte(0)
                stream.writeByte(2)
                stream.writeLong(totalLength)
                sections.forEach(stream::write)
                stream.writeBytes("7777")
            }
        }.toByteArray()
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
