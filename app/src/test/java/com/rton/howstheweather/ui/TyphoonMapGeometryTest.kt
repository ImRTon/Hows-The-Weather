package com.rton.howstheweather.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TyphoonMapGeometryTest {
    @Test
    fun dashOffsetStaysWithinOnePeriod() {
        listOf(0f, .4f, 3.7f, 120f).forEach { seconds ->
            val offset = typhoonDashOffset(dash = 6f, gap = 4.8f, speed = 2f, seconds = seconds)
            assertTrue(offset >= 0f && offset < 10.8f)
        }
    }

    @Test
    fun oppositeWindingContourBecomesHoleOfContainingOutline() {
        val outer = square(0f, 0f, 100f)
        val hole = square(40f, 40f, 20f).reversed()
        val separate = square(200f, 0f, 30f)

        val polygons = polygonsFromContours(listOf(outer, hole, separate))

        assertEquals(2, polygons.size)
        assertEquals(listOf(hole), polygons.first { it.first == outer }.second)
        assertTrue(polygons.first { it.first == separate }.second.isEmpty())
    }

    @Test
    fun degenerateContoursAreDropped() {
        val sliver = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(2f, 0f))

        assertTrue(polygonsFromContours(listOf(sliver)).isEmpty())
    }

    private fun square(left: Float, top: Float, size: Float) = listOf(
        Offset(left, top),
        Offset(left + size, top),
        Offset(left + size, top + size),
        Offset(left, top + size),
    )
}
