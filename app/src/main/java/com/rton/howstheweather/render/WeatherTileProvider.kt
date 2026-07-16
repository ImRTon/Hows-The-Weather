package com.rton.howstheweather.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.pow

class WeatherTileProvider(
    private val grid: WeatherGrid,
    private val style: WeatherRenderStyle,
) : TileProvider {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        if (!intersectsGrid(x, y, zoom)) return TileProvider.NO_TILE
        val key = cacheKey(x, y, zoom)
        tileCache.get(key)?.let { return Tile(TILE_SIZE, TILE_SIZE, it) }
        val candidateLock = Any()
        val lock = renderLocks.putIfAbsent(key, candidateLock) ?: candidateLock
        return try {
            synchronized(lock) {
                tileCache.get(key)?.let { return@synchronized Tile(TILE_SIZE, TILE_SIZE, it) }
                val bytes = renderTile(x, y, zoom)
                tileCache.put(key, bytes)
                Tile(TILE_SIZE, TILE_SIZE, bytes)
            }
        } finally {
            renderLocks.remove(key, lock)
        }
    }

    private fun renderTile(x: Int, y: Int, zoom: Int): ByteArray {
        val renderSize = TILE_SIZE
        val bitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888)
        val values = if (style.contours.isNotEmpty()) {
            Array(renderSize + CONTOUR_GUTTER * 2) {
                FloatArray(renderSize + CONTOUR_GUTTER * 2) { Float.NaN }
            }
        } else null
        val pixels = IntArray(renderSize * renderSize)
        val n = 2.0.pow(zoom)
        val sampleSize = values?.size ?: renderSize
        val sampleOffset = if (values == null) 0 else -CONTOUR_GUTTER
        val longitudes = DoubleArray(sampleSize) { index ->
            val px = index + sampleOffset
            ((x + (px + PIXEL_CENTER) / renderSize) / n) * 360.0 - 180.0
        }
        val latitudes = DoubleArray(sampleSize) { index ->
            val py = index + sampleOffset
            val worldY = (y + (py + PIXEL_CENTER) / renderSize) / n
            Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * worldY))))
        }
        if (values != null) {
            for (py in values.indices) for (px in values[py].indices) {
                val value = grid.sample(latitudes[py], longitudes[px]) ?: Float.NaN
                values[py][px] = value
            }
        }
        for (py in 0 until renderSize) {
            for (px in 0 until renderSize) {
                val value = if (values == null) {
                    grid.sample(latitudes[py], longitudes[px]) ?: Float.NaN
                } else {
                    values[py + CONTOUR_GUTTER][px + CONTOUR_GUTTER]
                }
                pixels[py * renderSize + px] = style.colorFor(value)
            }
        }
        bitmap.setPixels(pixels, 0, renderSize, 0, 0, renderSize, renderSize)
        if (values != null) drawContours(bitmap, values, renderSize)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        return bytes
    }

    private fun drawContours(bitmap: Bitmap, values: Array<FloatArray>, size: Int) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@WeatherTileProvider.style.contourColor
            strokeWidth = 1.25f
        }
        style.contours.forEach { threshold ->
            for (py in 0 until size) {
                for (px in 0 until size) {
                    val sampleX = px + CONTOUR_GUTTER
                    val sampleY = py + CONTOUR_GUTTER
                    val value = values[sampleY][sampleX]
                    if (!value.isFinite()) continue
                    val crosses = crossesThreshold(value, values[sampleY][sampleX + 1], threshold) ||
                        crossesThreshold(value, values[sampleY + 1][sampleX], threshold)
                    if (crosses) canvas.drawPoint(px.toFloat(), py.toFloat(), paint)
                }
            }
        }
    }

    private fun crossesThreshold(first: Float, second: Float, threshold: Float): Boolean =
        first.isFinite() && second.isFinite() &&
            ((first < threshold && second >= threshold) || (first >= threshold && second < threshold))

    private fun intersectsGrid(tileX: Int, tileY: Int, zoom: Int): Boolean {
        val northWest = tilePixelToGeo(tileX, tileY, zoom, 0, 0)
        val southEast = tilePixelToGeo(tileX, tileY, zoom, TILE_SIZE, TILE_SIZE)
        if (southEast.latitude > grid.bounds.north || northWest.latitude < grid.bounds.south) return false
        val directIntersection = southEast.longitude >= grid.bounds.west && northWest.longitude <= grid.bounds.east
        val wrappedIntersection = grid.bounds.east > 180.0 &&
            southEast.longitude + 360.0 >= grid.bounds.west && northWest.longitude + 360.0 <= grid.bounds.east
        return directIntersection || wrappedIntersection
    }

    private fun cacheKey(x: Int, y: Int, zoom: Int): String = buildString(96) {
        append(RENDERER_VERSION).append('|')
        append(grid.sourceId).append('|').append(grid.validAt.epochSecond)
        append('|').append(grid.width).append('x').append(grid.height)
        append('|').append(style.theme).append('|').append(style.unit)
        append('|').append(style.opacity.toBits())
        append('|').append(zoom).append('/').append(x).append('/').append(y)
    }

    private fun tilePixelToGeo(tileX: Int, tileY: Int, zoom: Int, px: Int, py: Int): GeoPoint {
        val n = 2.0.pow(zoom)
        val worldX = (tileX + px / TILE_SIZE.toDouble()) / n
        val worldY = (tileY + py / TILE_SIZE.toDouble()) / n
        val lon = worldX * 360.0 - 180.0
        val lat = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * worldY))))
        return GeoPoint(lat, lon)
    }

    companion object {
        const val TILE_SIZE = 256
        private const val PIXEL_CENTER = 0.5
        private const val CONTOUR_GUTTER = 1
        private const val RENDERER_VERSION = 2
        private const val CACHE_BYTES = 24 * 1024 * 1024
        private val renderLocks = ConcurrentHashMap<String, Any>()
        private val tileCache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }
    }
}
