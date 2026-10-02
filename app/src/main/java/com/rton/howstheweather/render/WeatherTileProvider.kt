package com.rton.howstheweather.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.pow

class WeatherTileProvider(
    initialGrid: WeatherGrid,
    private val style: WeatherRenderStyle,
) : TileProvider {
    @Volatile
    private var grid: WeatherGrid = initialGrid
    private val recentTilesLock = Any()
    private val recentTiles = LinkedHashMap<TileCoordinate, Unit>(
        MAX_RECENT_TILES,
        .75f,
        true,
    )

    fun updateGrid(grid: WeatherGrid) {
        require(grid.unit == style.unit) { "A tile provider cannot switch weather units" }
        this.grid = grid
    }

    /** Pre-renders current viewport tiles before the Maps SDK cache is invalidated. */
    fun preloadGrid(grid: WeatherGrid) {
        require(grid.unit == style.unit) { "A tile provider cannot switch weather units" }
        val tiles = synchronized(recentTilesLock) { recentTiles.keys.toList() }
        tiles.forEach { tile ->
            if (intersectsGrid(grid, tile.x, tile.y, tile.zoom)) {
                tileBytes(grid, tile.x, tile.y, tile.zoom)
            }
        }
    }

    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        val bytes = tilePng(x, y, zoom) ?: return TileProvider.NO_TILE
        return Tile(TILE_SIZE, TILE_SIZE, bytes)
    }

    /** PNG bytes for a Web Mercator z/x/y tile, or null when the tile is fully transparent. */
    fun tilePng(x: Int, y: Int, zoom: Int): ByteArray? {
        val frameGrid = grid
        if (!intersectsGrid(frameGrid, x, y, zoom)) return null
        rememberTile(TileCoordinate(x, y, zoom))
        val bytes = tileBytes(frameGrid, x, y, zoom)
        return bytes.takeUnless { it === EMPTY_TILE }
    }

    private fun tileBytes(grid: WeatherGrid, x: Int, y: Int, zoom: Int): ByteArray {
        val key = cacheKey(grid, x, y, zoom)
        tileCache.get(key)?.let { return it }
        val candidateLock = Any()
        val lock = renderLocks.putIfAbsent(key, candidateLock) ?: candidateLock
        return try {
            synchronized(lock) {
                tileCache.get(key)?.let { return@synchronized it }
                val bytes = renderTile(grid, x, y, zoom)
                tileCache.put(key, bytes)
                bytes
            }
        } finally {
            renderLocks.remove(key, lock)
        }
    }

    private fun rememberTile(tile: TileCoordinate) = synchronized(recentTilesLock) {
        recentTiles[tile] = Unit
        while (recentTiles.size > MAX_RECENT_TILES) {
            val iterator = recentTiles.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    private fun renderTile(grid: WeatherGrid, x: Int, y: Int, zoom: Int): ByteArray {
        val renderSize = TILE_SIZE
        val values = if (style.contours.isNotEmpty()) {
            Array(renderSize + CONTOUR_GUTTER * 2) {
                FloatArray(renderSize + CONTOUR_GUTTER * 2) { Float.NaN }
            }
        } else null
        val pixels = IntArray(renderSize * renderSize)
        var anyVisible = false
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
                val value = grid.sampleOrNaN(latitudes[py], longitudes[px])
                values[py][px] = value
            }
        }
        for (py in 0 until renderSize) {
            for (px in 0 until renderSize) {
                val value = if (values == null) {
                    grid.sampleOrNaN(latitudes[py], longitudes[px])
                } else {
                    values[py + CONTOUR_GUTTER][px + CONTOUR_GUTTER]
                }
                val color = style.colorFor(value)
                if (color != Color.TRANSPARENT) anyVisible = true
                pixels[py * renderSize + px] = color
            }
        }
        // Most of the radar domain is dry most of the time. A contour needs one
        // sample at or above its threshold, so a tile with neither is empty and
        // can skip bitmap allocation and PNG encoding entirely.
        val hasContour = values != null && style.contours.minOrNull()?.let { lowest ->
            values.any { row -> row.any { it.isFinite() && it >= lowest } }
        } == true
        if (!anyVisible && !hasContour) return EMPTY_TILE
        val bitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888)
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

    private fun intersectsGrid(grid: WeatherGrid, tileX: Int, tileY: Int, zoom: Int): Boolean {
        val northWest = tilePixelToGeo(tileX, tileY, zoom, 0, 0)
        val southEast = tilePixelToGeo(tileX, tileY, zoom, TILE_SIZE, TILE_SIZE)
        if (southEast.latitude > grid.bounds.north || northWest.latitude < grid.bounds.south) return false
        val directIntersection = southEast.longitude >= grid.bounds.west && northWest.longitude <= grid.bounds.east
        val wrappedIntersection = grid.bounds.east > 180.0 &&
            southEast.longitude + 360.0 >= grid.bounds.west && northWest.longitude + 360.0 <= grid.bounds.east
        return directIntersection || wrappedIntersection
    }

    private fun cacheKey(grid: WeatherGrid, x: Int, y: Int, zoom: Int): String = buildString(96) {
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
        private const val MAX_RECENT_TILES = 16
        private val EMPTY_TILE = ByteArray(0)
        private val renderLocks = ConcurrentHashMap<String, Any>()
        private val tileCache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
            // Count the key and entry overhead so cached empty tiles stay bounded.
            override fun sizeOf(key: String, value: ByteArray): Int = value.size + key.length * 2 + 64
        }
    }

    private data class TileCoordinate(val x: Int, val y: Int, val zoom: Int)
}
