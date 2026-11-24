package com.rton.howstheweather.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.rton.howstheweather.domain.AirQualityObservation
import com.rton.howstheweather.domain.GeoPoint
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class AirQualityStationLayer(
    val stations: List<AirQualityObservation>,
    val selectedStationName: String?,
    val darkMap: Boolean,
    val onSelectStation: (AirQualityObservation) -> Unit,
    val onSelectCluster: (GeoPoint, Float) -> Unit = { _, _ -> },
)

internal data class AirQualityStationCluster(
    val key: String,
    val center: GeoPoint,
    val representativeAqi: Int,
    val stations: List<AirQualityObservation>,
    val containsSelected: Boolean,
)

/**
 * Groups nearby AQI stations in Web Mercator screen-space dp at [zoom] so station pills never
 * pile on top of each other when zoomed out, and smoothly separate as the user zooms in.
 */
internal fun clusterAqiStations(
    stations: List<AirQualityObservation>,
    zoom: Float,
    selectedStationName: String?,
    minSpacingDp: Double = 26.0,
): List<AirQualityStationCluster> {
    if (stations.isEmpty()) return emptyList()
    if (zoom >= 11.0f) {
        return stations.map { station ->
            AirQualityStationCluster(
                key = station.stationName,
                center = station.coordinate,
                representativeAqi = station.aqi,
                stations = listOf(station),
                containsSelected = station.stationName == selectedStationName,
            )
        }
    }
    val worldDp = 256.0 * 2.0.pow(zoom.toDouble())
    val minSpacingSq = minSpacingDp * minSpacingDp

    fun projectX(lon: Double): Double = ((lon + 180.0) / 360.0) * worldDp
    fun projectY(lat: Double): Double {
        val sinLat = sin(Math.toRadians(lat.coerceIn(-85.0511, 85.0511)))
        return (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * worldDp
    }

    // Prioritize selected station first, then higher AQI stations for stable deterministic anchors.
    val ordered = stations.sortedWith(
        compareByDescending<AirQualityObservation> { it.stationName == selectedStationName }
            .thenByDescending { it.aqi }
            .thenBy { it.stationName },
    )

    class MutableCluster(first: AirQualityObservation, val anchorX: Double, val anchorY: Double) {
        val members = mutableListOf(first)
        var sumLat = first.coordinate.latitude
        var sumLon = first.coordinate.longitude
        var sumAqi = first.aqi
        var hasSelected = first.stationName == selectedStationName

        fun add(station: AirQualityObservation) {
            members.add(station)
            sumLat += station.coordinate.latitude
            sumLon += station.coordinate.longitude
            sumAqi += station.aqi
            if (station.stationName == selectedStationName) hasSelected = true
        }
    }

    val clusters = mutableListOf<MutableCluster>()
    for (station in ordered) {
        val x = projectX(station.coordinate.longitude)
        val y = projectY(station.coordinate.latitude)
        var best: MutableCluster? = null
        var bestDistSq = minSpacingSq
        for (cluster in clusters) {
            // Compare against the fixed anchor so clusters never drift and chain-swallow a whole city.
            val dx = x - cluster.anchorX
            val dy = y - cluster.anchorY
            val distSq = dx * dx + dy * dy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                best = cluster
            }
        }
        if (best != null) {
            best.add(station)
        } else {
            clusters.add(MutableCluster(station, x, y))
        }
    }

    return clusters.map { cluster ->
        val count = cluster.members.size
        val center = if (count == 1) {
            cluster.members.first().coordinate
        } else {
            GeoPoint(cluster.sumLat / count, cluster.sumLon / count)
        }
        val key = if (count == 1) {
            cluster.members.first().stationName
        } else {
            cluster.members.map(AirQualityObservation::stationName).sorted().joinToString("|")
        }
        AirQualityStationCluster(
            key = key,
            center = center,
            representativeAqi = (cluster.sumAqi.toDouble() / count).roundToInt(),
            stations = cluster.members,
            containsSelected = cluster.hasSelected,
        )
    }
}

/** High-contrast foreground ink for AQI pill badges across all 6 tiers in light and dark themes. */
internal fun aqiPinContentColor(levelIndex: Int, dark: Boolean): Color = when (levelIndex.coerceIn(0, 5)) {
    0 -> if (dark) Color(0xFF072116) else Color.White
    1 -> Color(0xFF1C1606)
    2 -> if (dark) Color(0xFF1F1004) else Color.White
    else -> Color.White
}

internal fun renderAqiStationPinBitmap(
    aqi: Int,
    clusterCount: Int,
    selected: Boolean,
    darkMap: Boolean,
    density: Density,
    textMeasurer: TextMeasurer,
): Bitmap {
    val label = aqi.toString()
    val pillHeightPx = (if (selected) 25f else 21f) * density.density
    val minPillWidthPx = (if (selected) 32f else 25f) * density.density
    val horizontalPaddingPx = (if (selected) 8.5f else 7f) * density.density
    val badgeAllowancePx = if (clusterCount > 1) 8f * density.density else 0f
    val haloPaddingPx = (if (selected) 6f else 3.5f) * density.density + badgeAllowancePx

    val textStyle = TextStyle(
        color = aqiPinContentColor(aqiLevelIndex(aqi), darkMap),
        fontSize = if (selected) 12.sp else 10.5.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    )
    val measured = textMeasurer.measure(label, style = textStyle)
    val pillWidthPx = maxOf(minPillWidthPx, measured.size.width + horizontalPaddingPx * 2f)
    val totalWidth = ceil(pillWidthPx + haloPaddingPx * 2f).toInt().coerceAtLeast(1)
    val totalHeight = ceil(pillHeightPx + haloPaddingPx * 2f).toInt().coerceAtLeast(1)

    val image = ImageBitmap(totalWidth, totalHeight)
    CanvasDrawScope().draw(
        density = density,
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(image),
        size = Size(totalWidth.toFloat(), totalHeight.toFloat()),
    ) {
        drawAqiStationPill(
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            aqi = aqi,
            clusterCount = clusterCount,
            selected = selected,
            darkMap = darkMap,
            textMeasurer = textMeasurer,
        )
    }
    return image.asAndroidBitmap()
}

internal fun DrawScope.drawAqiStationPill(
    center: Offset,
    aqi: Int,
    clusterCount: Int = 1,
    selected: Boolean,
    darkMap: Boolean,
    textMeasurer: TextMeasurer,
) {
    val level = aqiLevelIndex(aqi)
    val fill = aqiTierTrackColor(level, darkMap)
    val border = if (darkMap) Color(0xFF0D1624) else Color.White
    val shadowColor = Color.Black.copy(alpha = if (darkMap) 0.36f else 0.22f)
    val label = aqi.toString()

    val pillHeight = (if (selected) 25f else 21f) * density
    val minPillWidth = (if (selected) 32f else 25f) * density
    val horizontalPad = (if (selected) 8.5f else 7f) * density
    val textStyle = TextStyle(
        color = aqiPinContentColor(level, darkMap),
        fontSize = if (selected) 12.sp else 10.5.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    )
    val measured = textMeasurer.measure(label, style = textStyle)
    val pillWidth = maxOf(minPillWidth, measured.size.width + horizontalPad * 2f)
    val corner = CornerRadius(pillHeight * 0.5f, pillHeight * 0.5f)
    val topLeft = Offset(center.x - pillWidth * 0.5f, center.y - pillHeight * 0.5f)
    val pillSize = Size(pillWidth, pillHeight)

    if (selected) {
        val haloExpand = 4.5f * density
        drawRoundRect(
            color = fill.copy(alpha = if (darkMap) 0.32f else 0.26f),
            topLeft = Offset(topLeft.x - haloExpand, topLeft.y - haloExpand),
            size = Size(pillWidth + haloExpand * 2f, pillHeight + haloExpand * 2f),
            cornerRadius = CornerRadius((pillHeight + haloExpand * 2f) * 0.5f),
        )
    }

    // Subtle grounded shadow directly beneath the pill.
    drawRoundRect(
        color = shadowColor,
        topLeft = Offset(topLeft.x, topLeft.y + 1.25f * density),
        size = pillSize,
        cornerRadius = corner,
    )

    // Main semantic AQI pill.
    drawRoundRect(
        color = fill,
        topLeft = topLeft,
        size = pillSize,
        cornerRadius = corner,
    )

    // Crisp surface-colored ring to separate the pill cleanly from roads and plumes.
    drawRoundRect(
        color = border,
        topLeft = topLeft,
        size = pillSize,
        cornerRadius = corner,
        style = Stroke(width = (if (selected) 2.2f else 1.5f) * density),
    )

    drawText(
        textLayoutResult = measured,
        topLeft = Offset(
            center.x - measured.size.width * 0.5f,
            center.y - measured.size.height * 0.5f,
        ),
    )

    // Compact corner count badge when multiple stations are grouped at this zoom level.
    if (clusterCount > 1) {
        val countLabel = clusterCount.toString()
        val badgeBg = if (darkMap) Color(0xFF142033) else Color(0xFF1C2430)
        val badgeTextStyle = TextStyle(
            color = Color.White,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
        )
        val countMeasured = textMeasurer.measure(countLabel, style = badgeTextStyle)
        val badgeHeight = 14f * density
        val badgeWidth = maxOf(badgeHeight, countMeasured.size.width + 6f * density)
        val badgeCenter = Offset(
            x = topLeft.x + pillWidth - 1.5f * density,
            y = topLeft.y + 1.5f * density,
        )
        val badgeTopLeft = Offset(badgeCenter.x - badgeWidth * 0.5f, badgeCenter.y - badgeHeight * 0.5f)
        val badgeCorner = CornerRadius(badgeHeight * 0.5f, badgeHeight * 0.5f)
        drawRoundRect(
            color = badgeBg,
            topLeft = badgeTopLeft,
            size = Size(badgeWidth, badgeHeight),
            cornerRadius = badgeCorner,
        )
        drawRoundRect(
            color = border,
            topLeft = badgeTopLeft,
            size = Size(badgeWidth, badgeHeight),
            cornerRadius = badgeCorner,
            style = Stroke(width = 1.2f * density),
        )
        drawText(
            textLayoutResult = countMeasured,
            topLeft = Offset(
                badgeCenter.x - countMeasured.size.width * 0.5f,
                badgeCenter.y - countMeasured.size.height * 0.5f,
            ),
        )
    }
}
