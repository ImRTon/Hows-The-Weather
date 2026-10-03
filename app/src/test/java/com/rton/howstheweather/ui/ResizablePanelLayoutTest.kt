package com.rton.howstheweather.ui

import com.rton.howstheweather.domain.PanelAnchor
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

    @Test
    fun `anchors wrap measured content and stay ordered within bounds`() {
        val bounds = boundsFor(totalHeight = 600f)
        val anchors = calculatePanelAnchorHeights(bounds, 48f, 180f, 900f)

        assertEquals(64f, anchors.map, 0f)
        assertEquals(180f, anchors.balanced, 0f)
        assertEquals(412f, anchors.decision, 0f)

        val tiny = calculatePanelAnchorHeights(bounds, 48f, 20f, 10f)
        assertEquals(64f, tiny.balanced, 0f)
        assertEquals(64f, tiny.decision, 0f)
    }

    @Test
    fun `slow release springs back to the nearest anchor`() {
        val anchors = PanelAnchorHeights(map = 48f, balanced = 180f, decision = 300f)

        assertEquals(
            PanelAnchor.DECISION,
            resolvePanelSettleAnchor(anchors, 270f, velocity = -100f, projectedHeight = 260f, velocityThreshold = 400f),
        )
        assertEquals(
            PanelAnchor.BALANCED,
            resolvePanelSettleAnchor(anchors, 200f, velocity = 0f, projectedHeight = 200f, velocityThreshold = 400f),
        )
    }

    @Test
    fun `fling advances at least one anchor and may skip with momentum`() {
        val anchors = PanelAnchorHeights(map = 48f, balanced = 180f, decision = 300f)

        assertEquals(
            PanelAnchor.BALANCED,
            resolvePanelSettleAnchor(anchors, 290f, velocity = -800f, projectedHeight = 240f, velocityThreshold = 400f),
        )
        assertEquals(
            PanelAnchor.MAP,
            resolvePanelSettleAnchor(anchors, 290f, velocity = -3000f, projectedHeight = 20f, velocityThreshold = 400f),
        )
        assertEquals(
            PanelAnchor.DECISION,
            resolvePanelSettleAnchor(anchors, 300f, velocity = 2000f, projectedHeight = 600f, velocityThreshold = 400f),
        )
    }

    @Test
    fun `progress interpolates between anchors and tolerates equal anchors`() {
        val anchors = PanelAnchorHeights(map = 48f, balanced = 148f, decision = 148f)

        assertEquals(0.5f, anchors.summaryProgress(98f), 0.001f)
        assertEquals(1f, anchors.detailProgress(148f), 0f)
        assertEquals(0f, anchors.detailProgress(120f), 0f)
    }

    private fun boundsFor(totalHeight: Float) = calculateResizablePanelBounds(
        totalHeight = totalHeight,
        preferredMinDecisionHeight = 64f,
        preferredMinMapHeight = 180f,
        preferredHandleHeight = 8f,
        handleTouchHeight = 48f,
    )
}
