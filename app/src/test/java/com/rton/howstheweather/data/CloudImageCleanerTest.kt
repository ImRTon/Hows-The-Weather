package com.rton.howstheweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudImageCleanerTest {
    @Test fun `removes fixed cartography and reconstructs cloud across its mask`() {
        val width = 25
        val height = 25
        val background = FloatArray(width * height) { 0.25f }
        for (y in 0 until height) {
            background[y * width + 12] = 0.95f
        }
        val current = background.copyOf()
        for (y in 6..16) for (x in 4..20) {
            if (x != 12) current[y * width + x] = 0.65f
        }
        for (x in 0 until width) current[x] = 1f

        val cleaner = CloudImageCleaner(
            CloudBackgroundTemplate(width, height, background),
        )
        val cleaned = cleaner.cleanLuminance(width, height, current)

        assertTrue(cleaned[11 * width + 5] > 0.35f)
        assertTrue(cleaned[11 * width + 12] > 0.2f)
        assertTrue(cleaned[22 * width + 12] < 0.08f)
        assertEquals(0f, cleaned[0], 0.0001f)
    }

    @Test fun `leaves an unchanged fixed background transparent`() {
        val width = 12
        val height = 12
        val background = FloatArray(width * height) { index ->
            if (index % width == 6) 0.9f else 0.3f
        }
        val cleaner = CloudImageCleaner(
            CloudBackgroundTemplate(width, height, background),
        )

        val cleaned = cleaner.cleanLuminance(width, height, background.copyOf())

        assertTrue(cleaned.all { it == 0f })
    }

    @Test fun `cleans only the sampled output grid`() {
        val sourceWidth = 40
        val sourceHeight = 32
        val outputWidth = 10
        val outputHeight = 8
        val background = FloatArray(sourceWidth * sourceHeight) { 0.3f }
        val current = IntArray(background.size) { 0xff4d4d4d.toInt() }
        val sourceIndices = IntArray(outputWidth * outputHeight) { index ->
            val x = index % outputWidth
            val y = index / outputWidth
            val sourceX = x * (sourceWidth - 1) / (outputWidth - 1)
            val sourceY = y * (sourceHeight - 1) / (outputHeight - 1)
            sourceY * sourceWidth + sourceX
        }
        val cleaner = CloudImageCleaner(
            CloudBackgroundTemplate(sourceWidth, sourceHeight, background),
        )

        val cleaned = cleaner.cleanSampledArgb(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            argbPixels = current,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            sourceIndices = sourceIndices,
        )

        assertEquals(outputWidth * outputHeight, cleaned.size)
        assertTrue(cleaned.all { it == 0f })
    }
}
