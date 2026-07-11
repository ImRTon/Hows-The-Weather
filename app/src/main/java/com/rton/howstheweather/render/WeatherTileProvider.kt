package com.rton.howstheweather.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

class WeatherTileProvider(
    private val grid: WeatherGrid,
    private val style: WeatherRenderStyle,
) : TileProvider {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val values = Array(TILE_SIZE) { FloatArray(TILE_SIZE) { Float.NaN } }
        val pixels = IntArray(TILE_SIZE * TILE_SIZE)
        for (py in 0 until TILE_SIZE) {
            for (px in 0 until TILE_SIZE) {
                val point = tilePixelToGeo(x, y, zoom, px, py)
                val value = grid.sample(point) ?: Float.NaN
                values[py][px] = value
                pixels[py * TILE_SIZE + px] = style.colorFor(value)
            }
        }
        bitmap.setPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
        drawContours(bitmap, values)
        if (zoom >= 11) drawNativeResolutionGrid(bitmap, x, y, zoom)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        return Tile(TILE_SIZE, TILE_SIZE, bytes)
    }

    private fun drawContours(bitmap: Bitmap, values: Array<FloatArray>) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@WeatherTileProvider.style.contourColor
            strokeWidth = 1.25f
        }
        style.contours.forEach { threshold ->
            for (py in 1 until TILE_SIZE - 1) {
                for (px in 1 until TILE_SIZE - 1) {
                    val value = values[py][px]
                    if (!value.isFinite()) continue
                    val crosses = (values[py][px + 1] - threshold) * (value - threshold) < 0f ||
                        (values[py + 1][px] - threshold) * (value - threshold) < 0f
                    if (crosses) canvas.drawPoint(px.toFloat(), py.toFloat(), paint)
                }
            }
        }
    }

    private fun drawNativeResolutionGrid(bitmap: Bitmap, tileX: Int, tileY: Int, zoom: Int) {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.argb(34, 255, 255, 255); strokeWidth = 1f }
        val lonStep = (grid.bounds.east - grid.bounds.west) / (grid.width - 1)
        val latStep = (grid.bounds.north - grid.bounds.south) / (grid.height - 1)
        var lon = grid.bounds.west
        while (lon <= grid.bounds.east) {
            val px = geoToTilePixel(GeoPoint(grid.bounds.south, lon), tileX, tileY, zoom).first
            if (px in 0f..TILE_SIZE.toFloat()) canvas.drawLine(px, 0f, px, TILE_SIZE.toFloat(), paint)
            lon += lonStep
        }
        var lat = grid.bounds.south
        while (lat <= grid.bounds.north) {
            val py = geoToTilePixel(GeoPoint(lat, grid.bounds.west), tileX, tileY, zoom).second
            if (py in 0f..TILE_SIZE.toFloat()) canvas.drawLine(0f, py, TILE_SIZE.toFloat(), py, paint)
            lat += latStep
        }
    }

    private fun tilePixelToGeo(tileX: Int, tileY: Int, zoom: Int, px: Int, py: Int): GeoPoint {
        val n = 2.0.pow(zoom)
        val worldX = (tileX + px / TILE_SIZE.toDouble()) / n
        val worldY = (tileY + py / TILE_SIZE.toDouble()) / n
        val lon = worldX * 360.0 - 180.0
        val lat = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * worldY))))
        return GeoPoint(lat, lon)
    }

    private fun geoToTilePixel(point: GeoPoint, tileX: Int, tileY: Int, zoom: Int): Pair<Float, Float> {
        val n = 2.0.pow(zoom)
        val worldX = (point.longitude + 180.0) / 360.0 * n
        val latRad = Math.toRadians(point.latitude)
        val worldY = (1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * n
        return ((worldX - tileX) * TILE_SIZE).toFloat() to ((worldY - tileY) * TILE_SIZE).toFloat()
    }

    companion object { const val TILE_SIZE = 256 }
}
