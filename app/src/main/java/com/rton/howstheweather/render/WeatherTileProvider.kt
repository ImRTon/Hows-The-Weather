package com.rton.howstheweather.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import com.rton.howstheweather.diagnostics.StartupDiagnostics
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.DataOutputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.pow

class WeatherTileProvider(
    initialGrid: WeatherGrid,
    private val style: WeatherRenderStyle,
    cacheDirectory: File? = null,
) : TileProvider {
    @Volatile
    private var grid: WeatherGrid = initialGrid
    private val diskCache = cacheDirectory?.let(::WeatherTileDiskCache)
    private var fingerprintGrid: WeatherGrid? = null
    private var fingerprint = ""

    // Computed lazily on the tile worker, never during Compose construction.
    @Synchronized
    private fun frameFingerprint(frame: WeatherGrid): String {
        if (fingerprintGrid === frame) return fingerprint
        val started = StartupDiagnostics.begin("weather.tile.fingerprint")
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(BufferedOutputStream(DigestOutputStream(
            object : OutputStream() {
                override fun write(value: Int) {}
                override fun write(bytes: ByteArray, offset: Int, length: Int) {}
            }, digest,
        ))).use { output ->
            output.writeUTF(frame.sourceId)
            output.writeUTF(frame.validAt.toString())
            output.writeInt(frame.width)
            output.writeInt(frame.height)
            output.writeUTF(frame.bounds.toString())
            output.writeFloat(frame.missingValue)
            // FloatBuffer bulk copies avoid millions of per-value stream calls
            // (especially costly in an instrumented debug process).
            val block = ByteBuffer.allocate(64 * 1024)
            val floats = block.asFloatBuffer()
            var offset = 0
            while (offset < frame.values.size) {
                val count = minOf(floats.capacity(), frame.values.size - offset)
                floats.clear()
                floats.put(frame.values, offset, count)
                output.write(block.array(), 0, count * Float.SIZE_BYTES)
                offset += count
            }
            val weights = frame.alphaWeights
            output.writeBoolean(weights != null)
            if (weights != null) {
                var wOffset = 0
                while (wOffset < weights.size) {
                    val count = minOf(floats.capacity(), weights.size - wOffset)
                    floats.clear()
                    floats.put(weights, wOffset, count)
                    output.write(block.array(), 0, count * Float.SIZE_BYTES)
                    wOffset += count
                }
            }
            output.writeUTF(style.theme.name)
            output.writeUTF(style.unit.name)
            output.writeFloat(style.opacity)
            output.writeInt(style.contourColor)
            output.writeInt(style.valueStops.size)
            style.valueStops.forEach { output.writeFloat(it) }
            output.writeInt(style.colorLut.size)
            style.colorLut.forEach { output.writeInt(it) }
            output.writeInt(style.contours.size)
            style.contours.forEach { output.writeFloat(it) }
        }
        fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        fingerprintGrid = frame
        StartupDiagnostics.end("weather.tile.fingerprint", started)
        return fingerprint
    }

    fun updateGrid(grid: WeatherGrid) {
        require(grid.unit == style.unit) { "A tile provider cannot switch weather units" }
        this.grid = grid
    }

    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        val bytes = tilePng(x, y, zoom) ?: return TileProvider.NO_TILE
        return Tile(TILE_SIZE, TILE_SIZE, bytes)
    }

    /** PNG bytes for a Web Mercator z/x/y tile, or null when the tile is fully transparent. */
    fun tilePng(x: Int, y: Int, zoom: Int): ByteArray? {
        val frameGrid = grid
        if (!intersectsGrid(frameGrid, x, y, zoom)) return null
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
                diskCache?.read(key)?.let { cached ->
                    StartupDiagnostics.event("weather.tile.diskHit zoom=$zoom")
                    val bytes = if (cached.isEmpty()) EMPTY_TILE else cached
                    tileCache.put(key, bytes)
                    return@synchronized bytes
                }
                val bytes = StartupDiagnostics.measure("weather.tile.render source=${grid.sourceId} zoom=$zoom") {
                    renderTile(grid, x, y, zoom)
                }
                tileCache.put(key, bytes)
                diskCache?.write(key, bytes)
                bytes
            }
        } finally {
            renderLocks.remove(key, lock)
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
                val value = grid.sampleForDisplayOrNaN(latitudes[py], longitudes[px])
                values[py][px] = value
            }
        }
        val hasAlphaWeights = grid.alphaWeights != null
        for (py in 0 until renderSize) {
            val lat = latitudes[py + if (values == null) 0 else CONTOUR_GUTTER]
            for (px in 0 until renderSize) {
                val value = if (values == null) {
                    grid.sampleForDisplayOrNaN(lat, longitudes[px])
                } else {
                    values[py + CONTOUR_GUTTER][px + CONTOUR_GUTTER]
                }
                var color = style.colorFor(value)
                if (color != Color.TRANSPARENT && hasAlphaWeights) {
                    val lon = longitudes[px + if (values == null) 0 else CONTOUR_GUTTER]
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
            style = Paint.Style.STROKE
            strokeWidth = 1.25f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        style.contours.forEach { threshold ->
            for (sy in 0..size) {
                val row0 = values[sy]
                val row1 = values[sy + 1]
                val y0 = (sy - CONTOUR_GUTTER).toFloat()
                val y1 = y0 + 1f
                for (sx in 0..size) {
                    val v00 = row0[sx]
                    val v10 = row0[sx + 1]
                    val v11 = row1[sx + 1]
                    val v01 = row1[sx]
                    if (!v00.isFinite() || !v10.isFinite() || !v11.isFinite() || !v01.isFinite()) continue
                    var mask = 0
                    if (v00 >= threshold) mask = mask or 1
                    if (v10 >= threshold) mask = mask or 2
                    if (v11 >= threshold) mask = mask or 4
                    if (v01 >= threshold) mask = mask or 8
                    if (mask == 0 || mask == 15) continue
                    val x0 = (sx - CONTOUR_GUTTER).toFloat()
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
            4, 11 -> canvas.drawLine(x1, rightY(), bottomX(), y1, paint)
            8, 7 -> canvas.drawLine(x0, leftY(), bottomX(), y1, paint)
            3, 12 -> canvas.drawLine(x0, leftY(), x1, rightY(), paint)
            6, 9 -> canvas.drawLine(topX(), y0, bottomX(), y1, paint)
            5 -> {
                val center = 0.25f * (v00 + v10 + v11 + v01)
                if (center >= threshold) {
                    canvas.drawLine(x0, leftY(), bottomX(), y1, paint)
                    canvas.drawLine(topX(), y0, x1, rightY(), paint)
                } else {
                    canvas.drawLine(x0, leftY(), topX(), y0, paint)
                    canvas.drawLine(bottomX(), y1, x1, rightY(), paint)
                }
            }
            10 -> {
                val center = 0.25f * (v00 + v10 + v11 + v01)
                if (center >= threshold) {
                    canvas.drawLine(x0, leftY(), topX(), y0, paint)
                    canvas.drawLine(bottomX(), y1, x1, rightY(), paint)
                } else {
                    canvas.drawLine(x0, leftY(), bottomX(), y1, paint)
                    canvas.drawLine(topX(), y0, x1, rightY(), paint)
                }
            }
        }
    }

    private fun edgeFraction(a: Float, b: Float, threshold: Float): Float {
        val denom = b - a
        if (kotlin.math.abs(denom) < 1e-6f) return 0.5f
        return ((threshold - a) / denom).coerceIn(0f, 1f)
    }

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
        append(frameFingerprint(grid))
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
        private const val RENDERER_VERSION = 5
        private const val CACHE_BYTES = 24 * 1024 * 1024
        private val EMPTY_TILE = ByteArray(0)
        private val renderLocks = ConcurrentHashMap<String, Any>()
        private val tileCache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
            // Count the key and entry overhead so cached empty tiles stay bounded.
            override fun sizeOf(key: String, value: ByteArray): Int = value.size + key.length * 2 + 64
        }
    }

}
