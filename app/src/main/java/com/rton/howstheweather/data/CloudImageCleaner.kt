package com.rton.howstheweather.data

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal data class CloudBackgroundTemplate(
    val width: Int,
    val height: Int,
    val luminance: FloatArray,
    val annotationMask: BooleanArray,
    val excludedMask: BooleanArray,
) {
    init {
        require(width > 1 && height > 1)
        require(luminance.size == width * height)
        require(luminance.all { it.isFinite() && it in 0f..1f })
        require(annotationMask.size == width * height)
        require(excludedMask.size == width * height)
    }
}

/** A pixel crop, retaining its offset in the unscaled, original image. */
internal data class CroppedCloudImage(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val values: FloatArray,
) {
    fun valueAtSource(index: Int): Float? {
        if (index !in 0 until sourceWidth * sourceHeight) return null
        val x = index % sourceWidth - left
        val y = index / sourceWidth - top
        return if (x in 0 until width && y in 0 until height) values[y * width + x] else null
    }
}

/**
 * Separates the changing bright IR cloud signal from CWA's fixed cartographic background.
 *
 * CWA's grayscale JPG is a presentation product: coastlines, graticules, labels, and a
 * fixed surface background are already composited into every frame. Bundled annotation
 * masks identify temporally persistent thin lines independently of cloud brightness;
 * the background is a scalar clear-sky floor, never an image of historical clouds.
 */
internal class CloudImageCleaner(
    private val template: CloudBackgroundTemplate,
) {
    private val preparedBackgrounds = ConcurrentHashMap<Pair<Int, Int>, PreparedBackground>()
    // Detect thin cartography before sampling: a grid point can land on a JPEG halo
    // even when no output point lands on the bright line itself. Keep this small,
    // reusable integral mask in the template's own projection and resolution.
    private val annotationIntegral by lazy { IntArray((template.width + 1) * (template.height + 1)).also {
        val stride = template.width + 1
        for (y in 0 until template.height) {
            var rowCount = 0
            for (x in 0 until template.width) {
                if (template.annotationMask[y * template.width + x]) rowCount++
                it[(y + 1) * stride + x + 1] = it[y * stride + x + 1] + rowCount
            }
        }
    } }
    private val exclusionIntegral by lazy { IntArray((template.width + 1) * (template.height + 1)).also {
        val stride = template.width + 1
        for (y in 0 until template.height) {
            var rowCount = 0
            for (x in 0 until template.width) {
                if (template.excludedMask[y * template.width + x]) rowCount++
                it[(y + 1) * stride + x + 1] = it[y * stride + x + 1] + rowCount
            }
        }
    } }

    fun cleanArgb(
        width: Int,
        height: Int,
        argbPixels: IntArray,
    ): FloatArray {
        require(argbPixels.size == width * height)
        return cleanNative(width, height, {}) { sourceIndex -> argbLuminance(argbPixels[sourceIndex]) }
    }

    fun cleanCroppedArgb(
        width: Int,
        height: Int,
        argbPixels: IntArray,
        checkCancelled: () -> Unit = {},
    ): CroppedCloudImage {
        // Production templates are tied to one exact native product layout. Never
        // silently scale a mask to an unexpected replacement image.
        require(width == template.width && height == template.height) { "衛星影像與遮罩版型尺寸不符" }
        require(argbPixels.size == width * height)
        val full = cleanNative(width, height, checkCancelled) { argbLuminance(argbPixels[it]) }
        val prepared = requireNotNull(preparedBackgrounds[width to height])
        val cropped = FloatArray(prepared.cropWidth * prepared.cropHeight)
        for (y in 0 until prepared.cropHeight) {
            checkCancelled()
            full.copyInto(cropped, y * prepared.cropWidth,
                (y + prepared.cropTop) * width + prepared.cropLeft,
                (y + prepared.cropTop) * width + prepared.cropLeft + prepared.cropWidth)
        }
        return CroppedCloudImage(prepared.cropLeft, prepared.cropTop, prepared.cropWidth,
            prepared.cropHeight, width, height, cropped)
    }

    /** Remove and fill native pixels before downsampling or Lambert reprojection. */
    @JvmOverloads
    fun cleanSampledArgb(
        sourceWidth: Int,
        sourceHeight: Int,
        argbPixels: IntArray,
        outputWidth: Int,
        outputHeight: Int,
        sourceIndices: IntArray,
        checkCancelled: () -> Unit = {},
    ): FloatArray {
        require(argbPixels.size == sourceWidth * sourceHeight)
        require(outputWidth > 1 && outputHeight > 1)
        require(sourceIndices.size == outputWidth * outputHeight)
        val cleaned = cleanNative(sourceWidth, sourceHeight, checkCancelled) { sourceIndex ->
            argbLuminance(argbPixels[sourceIndex])
        }
        return FloatArray(sourceIndices.size) { index ->
            val sourceIndex = sourceIndices[index]
            if (sourceIndex < 0) 0f else cleaned[sourceIndex]
        }
    }

    private fun argbLuminance(color: Int): Float {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return (red * 0.2126f + green * 0.7152f + blue * 0.0722f) / 255f
    }

    internal fun cleanLuminance(width: Int, height: Int, current: FloatArray): FloatArray {
        require(current.size == width * height)
        return cleanNative(width, height, {}, current::get)
    }

    private inline fun cleanNative(
        width: Int,
        height: Int,
        checkCancelled: () -> Unit,
        luminanceAt: (sourceIndex: Int) -> Float,
    ): FloatArray {
        require(width > 1 && height > 1)
        val prepared = preparedBackgrounds.computeIfAbsent(width to height) {
            prepareBackground(width, height)
        }
        val initial = FloatArray(width * height)
        for (index in initial.indices) {
            if (index % width == 0) checkCancelled()
            if (prepared.excludedMask[index] || prepared.annotationMask[index]) continue
            val background = prepared.luminance[index]
            val denominator = max(MIN_BACKGROUND_HEADROOM, 1f - background)
            initial[index] = ((luminanceAt(index) - background - DIFFERENCE_NOISE_FLOOR) / denominator)
                .coerceIn(0f, 1f)
        }
        val filled = fillAnnotations(initial, prepared.inpaintPlan)
        for (index in filled.indices) {
            if (filled[index] < MIN_CLOUD_SIGNAL) filled[index] = 0f
        }
        return filled
    }

    private fun prepareBackground(
        sourceWidth: Int,
        sourceHeight: Int,
    ): PreparedBackground {
        if (sourceWidth == template.width && sourceHeight == template.height) {
            // The production path: share immutable native template arrays, with
            // no resampling and no whole-image integral masks.
            return preparedBackground(sourceWidth, sourceHeight, template.luminance,
                template.annotationMask, template.excludedMask, INPAINT_RADIUS)
        }
        val resized = FloatArray(sourceWidth * sourceHeight)
        val mask = BooleanArray(resized.size)
        val excludedMask = BooleanArray(resized.size)
        var cropLeft = sourceWidth
        var cropTop = sourceHeight
        var cropRight = 0
        var cropBottom = 0
        // Cover only the decoded pixel footprint. Antialiased line edges are already
        // present in the temporal mask; dilating again would erase adjacent clouds.
        val maskRadiusX = template.width.toDouble() / sourceWidth / 2
        val maskRadiusY = template.height.toDouble() / sourceHeight / 2
        for (index in resized.indices) {
            val pixelX = index % sourceWidth
            val pixelY = index / sourceWidth
            val sourceY = pixelY.toDouble() / (sourceHeight - 1) * (template.height - 1)
            val y0 = floor(sourceY).toInt().coerceAtMost(template.height - 2)
            val y1 = y0 + 1
            val ty = (sourceY - y0).toFloat()
            val sourceX = pixelX.toDouble() / (sourceWidth - 1) * (template.width - 1)
            val left = ceil(sourceX - maskRadiusX).toInt().coerceIn(0, template.width - 1)
            val top = ceil(sourceY - maskRadiusY).toInt().coerceIn(0, template.height - 1)
            val right = floor(sourceX + maskRadiusX).toInt().coerceIn(left, template.width - 1)
            val bottom = floor(sourceY + maskRadiusY).toInt().coerceIn(top, template.height - 1)
            mask[index] = integralValue(annotationIntegral, template.width + 1, left, top, right, bottom) > 0
            excludedMask[index] = integralValue(exclusionIntegral, template.width + 1, left, top, right, bottom) > 0
            if (!excludedMask[index]) {
                cropLeft = minOf(cropLeft, pixelX)
                cropTop = minOf(cropTop, pixelY)
                cropRight = maxOf(cropRight, pixelX + 1)
                cropBottom = maxOf(cropBottom, pixelY + 1)
            }
            val x0 = floor(sourceX).toInt().coerceAtMost(template.width - 2)
            val x1 = x0 + 1
            val tx = (sourceX - x0).toFloat()
            val topLuminance = template.luminance[y0 * template.width + x0] * (1f - tx) +
                template.luminance[y0 * template.width + x1] * tx
            val bottomLuminance = template.luminance[y1 * template.width + x0] * (1f - tx) +
                template.luminance[y1 * template.width + x1] * tx
            resized[index] = topLuminance * (1f - ty) + bottomLuminance * ty
        }
        val outputScale = maxOf(
            sourceWidth.toDouble() / template.width,
            sourceHeight.toDouble() / template.height,
        )
        val inpaintRadius = ceil(INPAINT_RADIUS * outputScale).toInt().coerceAtLeast(1)
        require(cropRight - cropLeft > 1 && cropBottom - cropTop > 1) { "衛星遮罩沒有有效影像範圍" }
        return PreparedBackground(resized, mask, excludedMask,
            buildInpaintPlan(sourceWidth, sourceHeight, mask, excludedMask, inpaintRadius * 4),
            cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop)
    }

    private fun preparedBackground(width: Int, height: Int, luminance: FloatArray,
        mask: BooleanArray, excluded: BooleanArray, radius: Int): PreparedBackground {
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        for (index in excluded.indices) if (!excluded[index]) {
            val x = index % width
            val y = index / width
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }
        require(right - left > 1 && bottom - top > 1) { "衛星遮罩沒有有效影像範圍" }
        return PreparedBackground(luminance, mask, excluded,
            buildInpaintPlan(width, height, mask, excluded, radius * 4),
            left, top, right - left, bottom - top)
    }

    /** Compute four nearest valid cardinal neighbors ONCE for this fixed mask. */
    private fun buildInpaintPlan(width: Int, height: Int, mask: BooleanArray,
        excluded: BooleanArray, searchRadius: Int): InpaintPlan {
        val targets = IntArray(mask.indices.count { mask[it] && !excluded[it] })
        val neighbors = IntArray(targets.size * 4) { -1 }
        var target = 0
        for (index in mask.indices) {
            if (!mask[index] || excluded[index]) continue
            targets[target] = index
            val x = index % width
            val y = index / width
            for (direction in 0..3) {
                for (distance in 1..searchRadius) {
                    val nx = x + when (direction) { 0 -> -distance; 1 -> distance; else -> 0 }
                    val ny = y + when (direction) { 2 -> -distance; 3 -> distance; else -> 0 }
                    if (nx !in 0 until width || ny !in 0 until height) break
                    val candidate = ny * width + nx
                    if (!mask[candidate] && !excluded[candidate]) {
                        neighbors[target * 4 + direction] = candidate
                        break
                    }
                }
            }
            // At a crossing every cardinal ray can stay on the grid line.
            // Reuse the same four slots for diagonals only in that case.
            if ((0..3).all { neighbors[target * 4 + it] < 0 }) {
                for (direction in 0..3) for (distance in 1..searchRadius) {
                    val nx = x + if (direction % 2 == 0) -distance else distance
                    val ny = y + if (direction < 2) -distance else distance
                    if (nx !in 0 until width || ny !in 0 until height) break
                    val candidate = ny * width + nx
                    if (!mask[candidate] && !excluded[candidate]) {
                        neighbors[target * 4 + direction] = candidate
                        break
                    }
                }
            }
            target++
        }
        return InpaintPlan(targets, neighbors)
    }

    /** Four reads per masked pixel; no per-frame integral images or iteration. */
    private fun fillAnnotations(values: FloatArray, plan: InpaintPlan): FloatArray {
        for (target in plan.targets.indices) {
            var sum = 0f
            var count = 0
            for (direction in 0..3) {
                val neighbor = plan.neighbors[target * 4 + direction]
                if (neighbor >= 0) {
                    sum += values[neighbor]
                    count++
                }
            }
            if (count > 0) values[plan.targets[target]] = sum / count
        }
        return values
    }

    private data class InpaintPlan(val targets: IntArray, val neighbors: IntArray)

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

    private data class PreparedBackground(
        val luminance: FloatArray,
        val annotationMask: BooleanArray,
        val excludedMask: BooleanArray,
        val inpaintPlan: InpaintPlan,
        val cropLeft: Int,
        val cropTop: Int,
        val cropWidth: Int,
        val cropHeight: Int,
    )

    private companion object {
        const val DIFFERENCE_NOISE_FLOOR = 0.025f
        const val MIN_BACKGROUND_HEADROOM = 0.08f
        const val MIN_CLOUD_SIGNAL = 0.04f
        const val INPAINT_RADIUS = 3
    }
}
