package com.rton.howstheweather.domain

import java.time.Instant

data class GeoPoint(val latitude: Double, val longitude: Double)

data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun contains(point: GeoPoint): Boolean =
        point.latitude in south..north && point.longitude in west..east
}

enum class WeatherUnit { DBZ, MILLIMETERS_PER_HOUR, MILLIMETERS_ONE_HOUR, LUMINANCE }

data class WeatherGrid(
    val width: Int,
    val height: Int,
    val values: FloatArray,
    val unit: WeatherUnit,
    val bounds: GeoBounds,
    val resolutionKm: Double,
    val validAt: Instant,
    val missingValue: Float = Float.NaN,
    val sourceId: String,
) {
    init {
        require(width > 1 && height > 1)
        require(values.size == width * height)
    }

    fun valueAt(x: Int, y: Int): Float = values[y * width + x]

    fun sample(point: GeoPoint): Float? {
        if (!bounds.contains(point)) return null
        val fx = ((point.longitude - bounds.west) / (bounds.east - bounds.west) * (width - 1))
            .coerceIn(0.0, (width - 1).toDouble())
        val fy = ((bounds.north - point.latitude) / (bounds.north - bounds.south) * (height - 1))
            .coerceIn(0.0, (height - 1).toDouble())
        val x0 = fx.toInt().coerceAtMost(width - 2)
        val y0 = fy.toInt().coerceAtMost(height - 2)
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        val samples = floatArrayOf(
            valueAt(x0, y0), valueAt(x0 + 1, y0),
            valueAt(x0, y0 + 1), valueAt(x0 + 1, y0 + 1),
        )
        if (samples.any { !it.isFinite() || it == missingValue }) return null
        val top = samples[0] + (samples[1] - samples[0]) * tx
        val bottom = samples[2] + (samples[3] - samples[2]) * tx
        return top + (bottom - top) * ty
    }
}

enum class PrimaryLayer { RADAR_RAIN, CLOUD }
enum class PanelAnchor { DECISION, BALANCED, MAP }
enum class ThemePreference { SYSTEM, LIGHT, DARK }
enum class RainState { DRY, LIGHT, MODERATE, HEAVY, EXTREME, UNAVAILABLE }

data class TargetLocation(
    val coordinate: GeoPoint,
    val displayName: String,
    val isDeviceLocation: Boolean,
)

data class ForecastPoint(val minutesFromNow: Int, val millimetersPerHour: Float?)

data class ForecastDecision(
    val state: RainState,
    val headline: String,
    val detail: String,
    val eventWindow: IntRange?,
    val series: List<ForecastPoint>,
    val issuedAt: Instant,
    val isStale: Boolean,
)

data class WindObservation(
    val stationName: String,
    val coordinate: GeoPoint,
    val speedMetersPerSecond: Float,
    val directionDegrees: Float,
    val observedAt: Instant,
)

data class LayerSelection(
    val primary: PrimaryLayer = PrimaryLayer.RADAR_RAIN,
    val windEnabled: Boolean = false,
    val opacity: Float = 0.72f,
)

data class HomeUiState(
    val target: TargetLocation,
    val decision: ForecastDecision,
    val selectedMinute: Int = 0,
    val panelAnchor: PanelAnchor = PanelAnchor.BALANCED,
    val layers: LayerSelection = LayerSelection(),
    val isPlaying: Boolean = false,
    val legendExpanded: Boolean = false,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val activeGrid: WeatherGrid? = null,
    val winds: List<WindObservation> = emptyList(),
    val reminderScheduled: Boolean = false,
    val message: String? = null,
)
