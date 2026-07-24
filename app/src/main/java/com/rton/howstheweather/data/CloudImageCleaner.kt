package com.rton.howstheweather.data

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal data class CloudBackgroundTemplate(
    val width: Int,
    val height: Int,
    val luminance: FloatArray,
) {
    init {
        require(width > 1 && height > 1)
        require(luminance.size == width * height)
        require(luminance.all { it.isFinite() && it in 0f..1f })
    }
}

/**
 * Separates the changing bright IR cloud signal from CWA's fixed cartographic background.
 *
 * CWA's grayscale JPG is a presentation product: coastlines, graticules, labels, and a
 * fixed surface background are already composited into every frame. The bundled template
 * is a temporal-mode estimate of that background in the source image's projection.
 */
internal class CloudImageCleaner(
    private val template: CloudBackgroundTemplate,
) {
    private val preparedBackgrounds = ConcurrentHashMap<SamplingKey, PreparedBackground>()

    fun cleanArgb(
        width: Int,
        height: Int,
        argbPixels: IntArray,
    ): FloatArray {
        require(argbPixels.size == width * height)
        return cleanSampledArgb(
            sourceWidth = width,
            sourceHeight = height,
            argbPixels = argbPixels,
            outputWidth = width,
            outputHeight = height,
            sourceIndices = IntArray(argbPixels.size) { it },
        )
    }

    /**
     * Cleans only the pixels that will survive into the numerical grid.
     *
     * Running background subtraction and inpainting at the 800–1000 px presentation-image
     * resolution retained several large arrays per frame. Sampling first keeps cloud history
     * decoding off the map's rendering budget while preserving the same output-grid result.
     */
    fun cleanSampledArgb(
        sourceWidth: Int,
        sourceHeight: Int,
        argbPixels: IntArray,
        outputWidth: Int,
        outputHeight: Int,
        sourceIndices: IntArray,
    ): FloatArray {
        require(sourceWidth > 1 && sourceHeight > 1)
        require(argbPixels.size == sourceWidth * sourceHeight)
        require(outputWidth > 1 && outputHeight > 1)
        require(sourceIndices.size == outputWidth * outputHeight)
        return clean(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            sourceIndices = sourceIndices,
        ) { sourceIndex ->
            val color = argbPixels[sourceIndex]
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            (red * 0.2126f + green * 0.7152f + blue * 0.0722f) / 255f
        }
    }

    internal fun cleanLuminance(
        width: Int,
        height: Int,
        current: FloatArray,
    ): FloatArray {
        require(current.size == width * height)
        val sourceIndices = IntArray(current.size) { it }
        return clean(
            sourceWidth = width,
            sourceHeight = height,
            outputWidth = width,
            outputHeight = height,
            sourceIndices = sourceIndices,
            luminanceAt = current::get,
        )
    }

    private inline fun clean(
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        sourceIndices: IntArray,
        luminanceAt: (sourceIndex: Int) -> Float,
    ): FloatArray {
        val key = SamplingKey(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            sourceIndicesHash = sourceIndices.contentHashCode(),
        )
        val prepared = preparedBackgrounds.computeIfAbsent(key) {
            prepareBackground(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                sourceIndices = sourceIndices,
            )
        }
        val initial = FloatArray(outputWidth * outputHeight)
        for (index in initial.indices) {
            val sourceIndex = sourceIndices[index]
            if (sourceIndex < 0 || prepared.excludedMask[index] || prepared.annotationMask[index]) continue
            val background = prepared.luminance[index]
            val denominator = max(MIN_BACKGROUND_HEADROOM, 1f - background)
            initial[index] = (
                (luminanceAt(sourceIndex) - background - DIFFERENCE_NOISE_FLOOR) / denominator
                ).coerceIn(0f, 1f)
        }
        val filled = fillAnnotations(
            width = outputWidth,
            height = outputHeight,
            values = initial,
            annotationMask = prepared.annotationMask,
            excludedMask = prepared.excludedMask,
            inpaintRadius = prepared.inpaintRadius,
        )
        for (index in filled.indices) {
            if (filled[index] < MIN_CLOUD_SIGNAL) filled[index] = 0f
        }
        return filled
    }

    private fun prepareBackground(
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        sourceIndices: IntArray,
    ): PreparedBackground {
        val resized = FloatArray(outputWidth * outputHeight)
        val excludedMask = BooleanArray(outputWidth * outputHeight)
        val headerRows = ceil(sourceHeight * HEADER_FRACTION).toInt()
        val frameMargin = ceil(minOf(sourceWidth, sourceHeight) * FRAME_MARGIN_FRACTION).toInt()
        for (index in resized.indices) {
            val sourceIndex = sourceIndices[index]
            if (sourceIndex < 0) {
                excludedMask[index] = true
                continue
            }
            val pixelX = sourceIndex % sourceWidth
            val pixelY = sourceIndex / sourceWidth
            if (
                pixelY < headerRows ||
                pixelY >= sourceHeight - frameMargin ||
                pixelX < frameMargin ||
                pixelX >= sourceWidth - frameMargin
            ) {
                excludedMask[index] = true
            }
            val sourceY = pixelY.toDouble() / (sourceHeight - 1) * (template.height - 1)
            val y0 = floor(sourceY).toInt().coerceAtMost(template.height - 2)
            val y1 = y0 + 1
            val ty = (sourceY - y0).toFloat()
            val sourceX = pixelX.toDouble() / (sourceWidth - 1) * (template.width - 1)
            val x0 = floor(sourceX).toInt().coerceAtMost(template.width - 2)
            val x1 = x0 + 1
            val tx = (sourceX - x0).toFloat()
            val top = template.luminance[y0 * template.width + x0] * (1f - tx) +
                template.luminance[y0 * template.width + x1] * tx
            val bottom = template.luminance[y1 * template.width + x0] * (1f - tx) +
                template.luminance[y1 * template.width + x1] * tx
            resized[index] = top * (1f - ty) + bottom * ty
        }
        val outputScale = maxOf(
            outputWidth.toDouble() / sourceWidth,
            outputHeight.toDouble() / sourceHeight,
        )
        val annotationDilation = ceil(ANNOTATION_DILATION * outputScale).toInt().coerceAtLeast(1)
        val inpaintRadius = ceil(INPAINT_RADIUS * outputScale).toInt().coerceAtLeast(1)
        val mask = BooleanArray(outputWidth * outputHeight)
        for (y in 0 until outputHeight) for (x in 0 until outputWidth) {
            if (resized[y * outputWidth + x] < ANNOTATION_LUMINANCE) continue
            val top = (y - annotationDilation).coerceAtLeast(0)
            val bottom = (y + annotationDilation).coerceAtMost(outputHeight - 1)
            val left = (x - annotationDilation).coerceAtLeast(0)
            val right = (x + annotationDilation).coerceAtMost(outputWidth - 1)
            for (maskY in top..bottom) for (maskX in left..right) {
                mask[maskY * outputWidth + maskX] = true
            }
        }
        return PreparedBackground(resized, mask, excludedMask, inpaintRadius)
    }

    /** Reconstructs cloud continuity across thin fixed labels instead of leaving map-shaped holes. */
    private fun fillAnnotations(
        width: Int,
        height: Int,
        values: FloatArray,
        annotationMask: BooleanArray,
        excludedMask: BooleanArray,
        inpaintRadius: Int,
    ): FloatArray {
        val stride = width + 1
        val sums = FloatArray((height + 1) * stride)
        val counts = IntArray((height + 1) * stride)
        for (y in 0 until height) {
            var rowSum = 0f
            var rowCount = 0
            for (x in 0 until width) {
                val index = y * width + x
                if (
                    !annotationMask[index] &&
                    !excludedMask[index]
                ) {
                    rowSum += values[index]
                    rowCount++
                }
                val integralIndex = (y + 1) * stride + x + 1
                sums[integralIndex] = sums[y * stride + x + 1] + rowSum
                counts[integralIndex] = counts[y * stride + x + 1] + rowCount
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!annotationMask[index] || excludedMask[index]) continue
                val left = (x - inpaintRadius).coerceAtLeast(0)
                val right = (x + inpaintRadius).coerceAtMost(width - 1)
                val top = (y - inpaintRadius).coerceAtLeast(0)
                val bottom = (y + inpaintRadius).coerceAtMost(height - 1)
                val count = integralValue(counts, stride, left, top, right, bottom)
                if (count > 0) {
                    values[index] = (
                        integralValue(sums, stride, left, top, right, bottom) / count
                        )
                }
            }
        }
        return values
    }

    private fun integralValue(
        integral: FloatArray,
        stride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Float = integral[(bottom + 1) * stride + right + 1] -
        integral[top * stride + right + 1] -
        integral[(bottom + 1) * stride + left] +
        integral[top * stride + left]

    private fun integralValue(
        integral: IntArray,
        stride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Int = integral[(bottom + 1) * stride + right + 1] -
        integral[top * stride + right + 1] -
        integral[(bottom + 1) * stride + left] +
        integral[top * stride + left]

    private data class SamplingKey(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val sourceIndicesHash: Int,
    )

    private data class PreparedBackground(
        val luminance: FloatArray,
        val annotationMask: BooleanArray,
        val excludedMask: BooleanArray,
        val inpaintRadius: Int,
    )

    private companion object {
        const val HEADER_FRACTION = 0.04
        const val FRAME_MARGIN_FRACTION = 0.06
        const val DIFFERENCE_NOISE_FLOOR = 0.025f
        const val MIN_BACKGROUND_HEADROOM = 0.08f
        const val MIN_CLOUD_SIGNAL = 0.04f
        const val ANNOTATION_LUMINANCE = 0.68f
        const val ANNOTATION_DILATION = 2
        const val INPAINT_RADIUS = 6
    }
}
