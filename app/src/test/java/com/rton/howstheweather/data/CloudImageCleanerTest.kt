package com.rton.howstheweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudImageCleanerTest {
    @Test fun `fixed neighbor plan reuses geometry but samples each new frame`() {
        val size = 25
        val mask = BooleanArray(size * size) { it % size == 12 }
        val cleaner = CloudImageCleaner(CloudBackgroundTemplate(size, size,
            FloatArray(size * size), mask, BooleanArray(size * size)))
        fun frame(value: Float) = FloatArray(size * size) { if (mask[it]) 1f else value }

        val first = cleaner.cleanLuminance(size, size, frame(.3f))
        val second = cleaner.cleanLuminance(size, size, frame(.8f))

        assertEquals(.275f, first[12 * size + 12], .0001f)
        assertEquals(.775f, second[12 * size + 12], .0001f)
        assertEquals(second[12 * size + 11], second[12 * size + 12], .0001f)
    }

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
            testTemplate(width, height, background),
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
            testTemplate(width, height, background),
        )

        val cleaned = cleaner.cleanLuminance(width, height, background.copyOf())

        assertTrue(cleaned.all { it == 0f })
    }

    @Test fun `returns only the sampled output after native cleaning`() {
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
            testTemplate(sourceWidth, sourceHeight, background),
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

    @Test fun `removes line fringes when output sampling misses the bright line core`() {
        val width = 80
        val height = 80
        val background = FloatArray(width * height) { index ->
            if (index % width == 39) 0.95f else 0.3f
        }
        val current = IntArray(width * height) { index ->
            // The output samples even coordinates: only the JPEG fringe survives,
            // never the fixed bright core at x=39.
            val gray = when (index % width) {
                39 -> 242
                38, 40 -> 160
                else -> 77
            }
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val outputSize = 40
        val indices = IntArray(outputSize * outputSize) { index ->
            (index / outputSize * 2) * width + index % outputSize * 2
        }

        val cleaned = CloudImageCleaner(testTemplate(width, height, background, BooleanArray(width * height) { it % width in 38..40 }))
            .cleanSampledArgb(width, height, current, outputSize, outputSize, indices)

        assertTrue("A missed line core must not leave bright parallel fringes", cleaned.all { it == 0f })
    }

    @Test fun `masks cartography before resizing the background to decoded image dimensions`() {
        val templateSize = 80
        val decodedSize = 40
        val background = FloatArray(templateSize * templateSize) { index ->
            if (index % templateSize == 39 || index / templateSize == 39) 0.95f else 0.3f
        }
        val current = IntArray(decodedSize * decodedSize) { index ->
            // Downscaling averages a one-pixel white line below the detection threshold.
            val gray = if (index % decodedSize == 19 || index / decodedSize == 19) 159 else 77
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        val cleaned = CloudImageCleaner(testTemplate(templateSize, templateSize, background))
            .cleanArgb(decodedSize, decodedSize, current)

        assertTrue("Resized lines and their intersection must stay transparent", cleaned.all { it == 0f })
    }

    @Test fun `reconstructs clouds across sampled graticules without using invalid projection pixels`() {
        val width = 80
        val outputSize = 40
        val background = FloatArray(width * width) { index ->
            if (index % width == 39 || index / width == 39) 0.95f else 0.3f
        }
        val current = IntArray(width * width) { index ->
            val gray = if (index % width in 38..40 || index / width in 38..40) 242 else 166
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val indices = IntArray(outputSize * outputSize) { index ->
            if (index % outputSize < 3) -1 else (index / outputSize * 2) * width + index % outputSize * 2
        }

        val cleaned = CloudImageCleaner(testTemplate(width, width, background, BooleanArray(width * width) { it % width in 38..40 || it / width in 38..40 }))
            .cleanSampledArgb(width, width, current, outputSize, outputSize, indices)

        val cloud = cleaned[15 * outputSize + 15]
        assertTrue(cloud > 0.4f)
        for (y in 15..24) for (x in 15..24) {
            assertEquals("Cloud must remain continuous through line intersections", cloud, cleaned[y * outputSize + x], 0.001f)
        }
        assertTrue(cleaned.indices.filter { indices[it] < 0 }.all { cleaned[it] == 0f })
    }
    @Test fun `keeps bright clouds immediately beside a thin fixed line`() {
        val size = 40
        val background = FloatArray(size * size) { 0.3f }
        val mask = BooleanArray(size * size) { it % size == 20 }
        val current = FloatArray(size * size) { index ->
            when (index % size) {
                19 -> 0.9f
                20 -> 1f
                else -> 0.5f
            }
        }

        val cleaned = CloudImageCleaner(testTemplate(size, size, background, mask))
            .cleanLuminance(size, size, current)

        assertEquals((0.9f - 0.3f - 0.025f) / 0.7f, cleaned[20 * size + 19], 0.001f)
        assertEquals((0.5f - 0.3f - 0.025f) / 0.7f, cleaned[20 * size + 21], 0.001f)
    }

    @Test fun `preserves healthy cloud content along sides and bottom`() {
        val size = 100
        val background = FloatArray(size * size) { 0.3f }
        val current = FloatArray(size * size) { 0.8f }
        val cleaner = CloudImageCleaner(testTemplate(size, size, background))

        val cleaned = cleaner.cleanLuminance(size, size, current)

        for ((x, y) in listOf(1 to 50, 98 to 50, 50 to 98)) {
            assertTrue("Do not crop a 6-percent border", cleaned[y * size + x] > 0.6f)
        }
    }

    @Test fun `uses the explicit annotation mask independently of background brightness`() {
        val size = 30
        val template = testTemplate(size, size, FloatArray(size * size) { 0.8f }, BooleanArray(size * size))
        val cleaned = CloudImageCleaner(template).cleanLuminance(size, size, FloatArray(size * size) { 1f })

        assertTrue("Bright areas must not automatically become annotations", cleaned[15 * size + 15] > 0.8f)
    }

    @Test fun `fills bold coastline junctions without leaving zero value holes`() {
        val size = 50
        val background = FloatArray(size * size) { 0.3f }
        val mask = BooleanArray(size * size) { it % size in 20..28 && it / size in 20..28 }
        val current = FloatArray(size * size) { if (mask[it]) 1f else 0.7f }

        val cleaned = CloudImageCleaner(testTemplate(size, size, background, mask))
            .cleanLuminance(size, size, current)

        for (y in 20..28) for (x in 20..28) {
            assertEquals(cleaned[15 * size + 15], cleaned[y * size + x], 0.001f)
        }
    }

    @Test fun `fills from native image neighbors before irregular projection sampling`() {
        val size = 80
        val background = FloatArray(size * size) { 0.3f }
        val annotations = BooleanArray(size * size) { it % size == 40 }
        val pixels = IntArray(size * size) { index ->
            val gray = if (annotations[index]) 255 else ((0.35f + index % size * 0.005f) * 255).toInt()
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        // Surround the projected line point with source pixels that are far away.
        // Inpainting on this output grid would wrongly use those distant clouds.
        val indices = IntArray(16) { 20 * size + 10 }.apply { this[5] = 40 * size + 40 }
        val cleaned = CloudImageCleaner(testTemplate(size, size, background, annotations))
            .cleanSampledArgb(size, size, pixels, 4, 4, indices)

        assertEquals((0.55f - 0.3f - 0.025f) / 0.7f, cleaned[5], 0.01f)
        assertTrue(cleaned[5] > cleaned[4] + 0.15f)
    }

    @Test fun `native cleanup checks cancellation before completing the full source image`() {
        val size = 80
        val cleaner = CloudImageCleaner(testTemplate(size, size, FloatArray(size * size) { 0.3f }))
        var rows = 0
        try {
            cleaner.cleanSampledArgb(size, size, IntArray(size * size), 2, 2, IntArray(4)) {
                rows++
                if (rows == 8) throw java.util.concurrent.CancellationException("foreground ended")
            }
            throw AssertionError("Cancellation must stop native cleanup")
        } catch (_: java.util.concurrent.CancellationException) {
            assertEquals(8, rows)
        }
    }

    private fun testTemplate(
        width: Int,
        height: Int,
        background: FloatArray,
        annotations: BooleanArray = BooleanArray(background.size) { background[it] >= 0.68f },
    ) = CloudBackgroundTemplate(
        width, height, background, annotations,
        BooleanArray(background.size) { it < width },
    )
}
