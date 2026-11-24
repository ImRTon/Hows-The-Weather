package com.rton.howstheweather.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import com.rton.howstheweather.domain.WeatherGrid
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Renders a bounded [WeatherGrid] into a single Web Mercator-aligned ARGB_8888 [Bitmap]
 * suitable for GPU-backed map GroundOverlays.
 *
 * Uses the exact same Catmull-Rom resampling, 256-entry OKLab LUT, optional radial alpha
 * feathering, and sub-pixel marching-squares contours as [WeatherTileProvider], while scaling
 * continuously on the GPU across every zoom level without ever blanking or re-rendering tiles.
 */
object WeatherGridBitmapRenderer {
    private const val MAX_TEXTURE_DIM = 960
    private const val MIN_TEXTURE_DIM = 480

    private val bitmapCache = object : LruCache<String, Bitmap>(16) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun render(
        grid: WeatherGrid,
        style: WeatherRenderStyle,
        maxDimension: Int = MAX_TEXTURE_DIM,
    ): Bitmap {
        val cacheKey = buildString {
            append(grid.sourceId).append('|')
            append(grid.validAt.toEpochMilli()).append('|')
            append(grid.width).append('x').append(grid.height).append('|')
            append(grid.bounds.south).append(',').append(grid.bounds.west).append(',')
            append(grid.bounds.north).append(',').append(grid.bounds.east).append('|')
            append(style.theme.name).append('|')
            append(style.unit.name).append('|')
            append(style.opacity).append('|')
            append(maxDimension)
        }
        bitmapCache.get(cacheKey)?.takeUnless(Bitmap::isRecycled)?.let { return it }

        val bounds = grid.bounds
        val yNorth = latToMercatorY(bounds.north)
        val ySouth = latToMercatorY(bounds.south)
        val lonSpan = (bounds.east - bounds.west).coerceAtLeast(1e-6)
        val mercXSpan = lonSpan / 360.0
        val ySpan = (ySouth - yNorth).coerceAtLeast(1e-6)
        val aspect = mercXSpan / ySpan

        val targetLongest = maxOf(grid.width, grid.height, MIN_TEXTURE_DIM).coerceAtMost(maxDimension)
        val width: Int
        val height: Int
        if (aspect >= 1.0) {
            width = targetLongest
            height = (targetLongest / aspect).roundToInt().coerceIn(64, maxDimension)
        } else {
            height = targetLongest
            width = (targetLongest * aspect).roundToInt().coerceIn(64, maxDimension)
        }

        val longitudes = DoubleArray(width) { px ->
            bounds.west + ((px + 0.5) / width) * lonSpan
        }
        val latitudes = DoubleArray(height) { py ->
            val mercY = yNorth + ((py + 0.5) / height) * ySpan
            mercatorYToLat(mercY)
        }

        val pixels = IntArray(width * height)
        val hasContours = style.contours.isNotEmpty()
        val sampledValues = if (hasContours) FloatArray(width * height) else null
        val hasAlphaWeights = grid.alphaWeights != null

        for (py in 0 until height) {
            val lat = latitudes[py]
            val rowOffset = py * width
            for (px in 0 until width) {
                val lon = longitudes[px]
                val value = grid.sampleForDisplayOrNaN(lat, lon)
                if (sampledValues != null) sampledValues[rowOffset + px] = value
                var color = style.colorFor(value)
                if (color != Color.TRANSPARENT && hasAlphaWeights) {
                    val weight = grid.sampleAlphaForDisplay(lat, lon)
                    color = when {
                        weight <= 0.004f -> Color.TRANSPARENT
                        weight < 0.996f -> {
                            val alpha = ((color ushr 24) * weight + 0.5f).toInt().coerceIn(0, 255)
                            (alpha shl 24) or (color and 0x00FFFFFF)
                        }
                        else -> color
                    }
                }
                pixels[rowOffset + px] = color
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
        if (sampledValues != null) {
            drawContours(bitmap, sampledValues, width, height, style)
        }
        bitmapCache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun drawContours(
        bitmap: Bitmap,
        values: FloatArray,
        width: Int,
        height: Int,
        renderStyle: WeatherRenderStyle,
    ) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = renderStyle.contourColor
            style = Paint.Style.STROKE
            strokeWidth = 1.35f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        renderStyle.contours.forEach { threshold ->
            for (y in 0 until height - 1) {
                val row0 = y * width
                val row1 = (y + 1) * width
                val y0 = y.toFloat()
                val y1 = y0 + 1f
                for (x in 0 until width - 1) {
                    val v00 = values[row0 + x]
                    val v10 = values[row0 + x + 1]
                    val v11 = values[row1 + x + 1]
                    val v01 = values[row1 + x]
                    if (!v00.isFinite() || !v10.isFinite() || !v11.isFinite() || !v01.isFinite()) continue
                    var mask = 0
                    if (v00 >= threshold) mask = mask or 1
                    if (v10 >= threshold) mask = mask or 2
                    if (v11 >= threshold) mask = mask or 4
                    if (v01 >= threshold) mask = mask or 8
                    if (mask == 0 || mask == 15) continue
                    val x0 = x.toFloat()
                    val x1 = x0 + 1f
                    drawMarchingSquareCell(
                        canvas = canvas,
                        paint = paint,
                        mask = mask,
                        x0 = x0,
                        y0 = y0,
                        x1 = x1,
                        y1 = y1,
                        v00 = v00,
                        v10 = v10,
                        v11 = v11,
                        v01 = v01,
                        threshold = threshold,
                    )
                }
            }
        }
    }

    private fun drawMarchingSquareCell(
        canvas: Canvas,
        paint: Paint,
        mask: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        v00: Float,
        v10: Float,
        v11: Float,
        v01: Float,
        threshold: Float,
    ) {
        fun topX(): Float = x0 + edgeFraction(v00, v10, threshold)
        fun rightY(): Float = y0 + edgeFraction(v10, v11, threshold)
        fun bottomX(): Float = x0 + edgeFraction(v01, v11, threshold)
        fun leftY(): Float = y0 + edgeFraction(v00, v01, threshold)

        when (mask) {
            1, 14 -> canvas.drawLine(x0, leftY(), topX(), y0, paint)
            2, 13 -> canvas.drawLine(topX(), y0, x1, rightY(), paint)
            3, 12 -> canvas.drawLine(x0, leftY(), x1, rightY(), paint)
            4, 11 -> canvas.drawLine(x1, rightY(), bottomX(), y1, paint)
            5 -> {
                val center = (v00 + v10 + v11 + v01) * 0.25f
                if (center >= threshold) {
                    canvas.drawLine(topX(), y0, x1, rightY(), paint)
                    canvas.drawLine(x0, leftY(), bottomX(), y1, paint)
                } else {
                    canvas.drawLine(x0, leftY(), topX(), y0, paint)
                    canvas.drawLine(x1, rightY(), bottomX(), y1, paint)
                }
            }
            6, 9 -> canvas.drawLine(topX(), y0, bottomX(), y1, paint)
            7, 8 -> canvas.drawLine(x0, leftY(), bottomX(), y1, paint)
            10 -> {
                val center = (v00 + v10 + v11 + v01) * 0.25f
                if (center >= threshold) {
                    canvas.drawLine(x0, leftY(), topX(), y0, paint)
                    canvas.drawLine(x1, rightY(), bottomX(), y1, paint)
                } else {
                    canvas.drawLine(topX(), y0, x1, rightY(), paint)
                    canvas.drawLine(x0, leftY(), bottomX(), y1, paint)
                }
            }
        }
    }

    private fun edgeFraction(a: Float, b: Float, threshold: Float): Float {
        val delta = b - a
        return if (kotlin.math.abs(delta) < 1e-6f) 0.5f else ((threshold - a) / delta).coerceIn(0f, 1f)
    }

    private fun latToMercatorY(latDeg: Double): Double {
        val rad = Math.toRadians(latDeg.coerceIn(-85.05112878, 85.05112878))
        return 0.5 - ln(tan(PI / 4.0 + rad / 2.0)) / (2.0 * PI)
    }

    private fun mercatorYToLat(y: Double): Double =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))
}
