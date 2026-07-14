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
        val renderSize = if (zoom < FULL_RESOLUTION_ZOOM) PREVIEW_SIZE else TILE_SIZE
        var bitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888)
        val values = if (style.contours.isNotEmpty()) {
            Array(renderSize) { FloatArray(renderSize) { Float.NaN } }
        } else null
        val pixels = IntArray(renderSize * renderSize)
        val n = 2.0.pow(zoom)
        val longitudes = DoubleArray(renderSize) { px ->
            ((x + px / renderSize.toDouble()) / n) * 360.0 - 180.0
        }
        val latitudes = DoubleArray(renderSize) { py ->
            val worldY = (y + py / renderSize.toDouble()) / n
            Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * worldY))))
        }
        for (py in 0 until renderSize) {
            for (px in 0 until renderSize) {
                val value = grid.sample(latitudes[py], longitudes[px]) ?: Float.NaN
                values?.get(py)?.set(px, value)
                pixels[py * renderSize + px] = style.colorFor(value)
            }
        }
        bitmap.setPixels(pixels, 0, renderSize, 0, 0, renderSize, renderSize)
        if (values != null) drawContours(bitmap, values, renderSize)
        if (renderSize != TILE_SIZE) {
            val preview = bitmap
            bitmap = Bitmap.createScaledBitmap(preview, TILE_SIZE, TILE_SIZE, true)
            preview.recycle()
        }
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
            for (py in 1 until size - 1) {
                for (px in 1 until size - 1) {
                    val value = values[py][px]
                    if (!value.isFinite()) continue
                    val crosses = (values[py][px + 1] - threshold) * (value - threshold) < 0f ||
                        (values[py + 1][px] - threshold) * (value - threshold) < 0f
                    if (crosses) canvas.drawPoint(px.toFloat(), py.toFloat(), paint)
                }
            }
        }
    }

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
        private const val PREVIEW_SIZE = 128
        private const val FULL_RESOLUTION_ZOOM = 11
        private const val CACHE_BYTES = 24 * 1024 * 1024
        private val renderLocks = ConcurrentHashMap<String, Any>()
        private val tileCache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }
    }
}
