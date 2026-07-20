package com.rton.howstheweather.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TileBlendWeightsTest {
    @Test fun `frame blend remains fully weighted throughout transition`() {
        listOf(0f, .25f, .5f, .75f, 1f).forEach { progress ->
            val weights = tileBlendWeights(frontSlot = 0, progress = progress)
            assertEquals(1f, weights.first + weights.second, .0001f)
            assertEquals(1f - progress, weights.first, .0001f)
            assertEquals(progress, weights.second, .0001f)
        }
    }

    @Test fun `back buffer becomes front without changing visible weights`() {
        val beforeSwap = tileBlendWeights(frontSlot = 0, progress = 1f)
        val afterSwap = tileBlendWeights(frontSlot = 1, progress = 0f)

        assertEquals(beforeSwap, afterSwap)
    }
}
