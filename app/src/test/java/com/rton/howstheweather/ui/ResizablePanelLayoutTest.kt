package com.rton.howstheweather.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ResizablePanelLayoutTest {
    @Test
    fun `normal height preserves requested card and map minimums`() {
        val bounds = boundsFor(totalHeight = 600f)

        assertEquals(8f, bounds.handleHeight, 0f)
        assertEquals(64f, bounds.minDecisionHeight, 0f)
        assertEquals(412f, bounds.maxDecisionHeight, 0f)
        assertEquals(552f, bounds.maxHandleTouchOffset, 0f)
    }

    @Test
    fun `short height collapses decision range instead of creating an empty range`() {
        val bounds = boundsFor(totalHeight = 180f)

        assertEquals(64f, bounds.minDecisionHeight, 0f)
        assertEquals(64f, bounds.maxDecisionHeight, 0f)
        assertEquals(64f, bounds.clampDecisionHeight(122.4f), 0f)
    }

    @Test
    fun `height below card minimum remains valid`() {
        val bounds = boundsFor(totalHeight = 48f)

        assertEquals(40f, bounds.minDecisionHeight, 0f)
        assertEquals(40f, bounds.maxDecisionHeight, 0f)
        assertEquals(0f, bounds.maxHandleTouchOffset, 0f)
    }

    @Test
    fun `zero and non finite heights produce safe zero bounds`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { height ->
            val bounds = boundsFor(totalHeight = height)

            assertEquals(0f, bounds.totalHeight, 0f)
            assertEquals(0f, bounds.handleHeight, 0f)
            assertEquals(0f, bounds.minDecisionHeight, 0f)
            assertEquals(0f, bounds.maxDecisionHeight, 0f)
            assertEquals(0f, bounds.clampDecisionHeight(100f), 0f)
        }
    }

    private fun boundsFor(totalHeight: Float) = calculateResizablePanelBounds(
        totalHeight = totalHeight,
        preferredMinDecisionHeight = 64f,
        preferredMinMapHeight = 180f,
        preferredHandleHeight = 8f,
        handleTouchHeight = 48f,
    )
}
