package com.rton.howstheweather.domain

import java.time.Instant

const val DEFAULT_MAP_ZOOM = 12.075137f

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

enum class WeatherUnit { DBZ, MILLIMETERS_PER_HOUR, MILLIMETERS_ONE_HOUR, MILLIMETERS_TWELVE_HOURS, LUMINANCE }

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

    fun sample(point: GeoPoint): Float? = sample(point.latitude, point.longitude)

    fun sample(latitude: Double, longitude: Double): Float? {
        val gridLongitude = if (bounds.east > 180.0 && longitude < bounds.west) longitude + 360.0 else longitude
        if (latitude !in bounds.south..bounds.north || gridLongitude !in bounds.west..bounds.east) return null
        val fx = ((gridLongitude - bounds.west) / (bounds.east - bounds.west) * (width - 1))
            .coerceIn(0.0, (width - 1).toDouble())
        val fy = ((bounds.north - latitude) / (bounds.north - bounds.south) * (height - 1))
            .coerceIn(0.0, (height - 1).toDouble())
        val x0 = fx.toInt().coerceAtMost(width - 2)
        val y0 = fy.toInt().coerceAtMost(height - 2)
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        // This is called once per rendered tile pixel. Keep it allocation-free.
        val topLeft = valueAt(x0, y0)
        val topRight = valueAt(x0 + 1, y0)
        val bottomLeft = valueAt(x0, y0 + 1)
        val bottomRight = valueAt(x0 + 1, y0 + 1)
        if (!topLeft.isFinite() || !topRight.isFinite() || !bottomLeft.isFinite() || !bottomRight.isFinite() ||
            topLeft == missingValue || topRight == missingValue || bottomLeft == missingValue || bottomRight == missingValue
        ) return null
        val top = topLeft + (topRight - topLeft) * tx
        val bottom = bottomLeft + (bottomRight - bottomLeft) * tx
        return top + (bottom - top) * ty
    }

    /**
     * Creates a render-only temporal frame between two compatible numerical grids.
     * Official observations remain unchanged; this derived grid must not feed decisions.
     */
    fun interpolateForDisplay(next: WeatherGrid, fraction: Float): WeatherGrid {
        require(canInterpolateWith(next)) { "Weather grids are not compatible for temporal interpolation" }
        val progress = fraction.coerceIn(0f, 1f)
        if (progress <= 0f) return this
        if (progress >= 1f) return next

        val interpolatedValues = FloatArray(values.size) { index ->
            val from = values[index]
            val to = next.values[index]
            if (!from.isFinite() || !to.isFinite() || from == missingValue || to == next.missingValue) {
                Float.NaN
            } else {
                from + (to - from) * progress
            }
        }
        val intervalMillis = next.validAt.toEpochMilli() - validAt.toEpochMilli()
        return copy(
            values = interpolatedValues,
            validAt = validAt.plusMillis((intervalMillis * progress).toLong()),
            missingValue = Float.NaN,
            sourceId = if (sourceId == next.sourceId) {
                "$sourceId:temporal-interpolation"
            } else {
                "$sourceId->${next.sourceId}:temporal-interpolation"
            },
        )
    }

    fun canInterpolateWith(other: WeatherGrid): Boolean =
        width == other.width &&
            height == other.height &&
            unit == other.unit &&
            bounds == other.bounds &&
            resolutionKm == other.resolutionKm
}

enum class PrimaryLayer { RADAR_RAIN, ONE_HOUR_RAIN, CLOUD }
enum class CloudCoverage { EAST_ASIA, TAIWAN }
enum class RadarCoverage { WIDE, LOCAL }

object CloudCoverageSelector {
    private const val TAIWAN_ENTER_ZOOM = 7.25f
    private const val TAIWAN_EXIT_ZOOM = 6.75f

    fun select(
        current: CloudCoverage,
        zoom: Float,
        center: GeoPoint,
        taiwanBounds: GeoBounds?,
    ): CloudCoverage {
        if (taiwanBounds == null || !taiwanBounds.contains(center)) return CloudCoverage.EAST_ASIA
        return when (current) {
            CloudCoverage.EAST_ASIA -> if (zoom >= TAIWAN_ENTER_ZOOM) CloudCoverage.TAIWAN else current
            CloudCoverage.TAIWAN -> if (zoom <= TAIWAN_EXIT_ZOOM) CloudCoverage.EAST_ASIA else current
        }
    }
}

object RadarCoverageSelector {
    private const val LOCAL_ENTER_ZOOM = 7.25f
    private const val LOCAL_EXIT_ZOOM = 6.75f

    fun select(
        current: RadarCoverage,
        zoom: Float,
        center: GeoPoint,
        localBounds: GeoBounds?,
    ): RadarCoverage {
        if (localBounds == null || !localBounds.contains(center)) return RadarCoverage.WIDE
        return when (current) {
            RadarCoverage.WIDE -> if (zoom >= LOCAL_ENTER_ZOOM) RadarCoverage.LOCAL else current
            RadarCoverage.LOCAL -> if (zoom <= LOCAL_EXIT_ZOOM) RadarCoverage.WIDE else current
        }
    }
}
enum class PanelAnchor { DECISION, BALANCED, MAP }
enum class ThemePreference { SYSTEM, LIGHT, DARK }
enum class AppDestination { NOW, FORECAST, PRECIPITATION }
enum class RainState { DRY, LIGHT, MODERATE, HEAVY, EXTREME, UNAVAILABLE }

data class TargetLocation(
    val coordinate: GeoPoint,
    val displayName: String,
    val isDeviceLocation: Boolean,
)

data class ForecastPoint(val minutesFromNow: Int, val millimetersPerHour: Float?)

data class AreaForecastPeriod(
    val startAt: Instant,
    val endAt: Instant,
    val precipitationProbabilityPercent: Int?,
    val minimumTemperatureCelsius: Int?,
    val maximumTemperatureCelsius: Int?,
    val weatherDescription: String,
    val weatherCode: String?,
    val relativeHumidityPercent: Int? = null,
)

data class AreaForecast(
    val target: GeoPoint,
    val areaCoordinate: GeoPoint,
    val countyName: String,
    val districtName: String,
    val periods: List<AreaForecastPeriod>,
    val sourceId: String,
) {
    val displayName: String
        get() = if (districtName.startsWith(countyName)) districtName else countyName + districtName
}

data class AirQualityObservation(
    val stationName: String,
    val coordinate: GeoPoint,
    val aqi: Int,
    val status: String,
    val primaryPollutant: String?,
    val observedAt: Instant,
)

data class CurrentWeatherObservation(
    val stationName: String,
    val coordinate: GeoPoint,
    val weatherDescription: String?,
    val temperatureCelsius: Float?,
    val relativeHumidityPercent: Float?,
    val windSpeedMetersPerSecond: Float?,
    val windDirectionDegrees: Float?,
    val windDirectionVariable: Boolean = false,
    val precipitationTodayMillimeters: Float?,
    val precipitationTrace: Boolean = false,
    val airPressureHectopascals: Float?,
    val uvIndex: Int?,
    val observedAt: Instant,
    val sourceId: String,
)

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
    val currentWeather: CurrentWeatherObservation? = null,
    val currentWeatherLoading: Boolean = true,
    val currentWeatherUnavailableReason: String? = null,
    val areaForecast: AreaForecast? = null,
    val areaForecastLoading: Boolean = true,
    val areaForecastUnavailableReason: String? = null,
    val weeklyForecast: AreaForecast? = null,
    val weeklyForecastLoading: Boolean = true,
    val weeklyForecastUnavailableReason: String? = null,
    val airQuality: AirQualityObservation? = null,
    val airQualityLoading: Boolean = false,
    val airQualityUnavailableReason: String? = null,
    val destination: AppDestination = AppDestination.NOW,
    val selectedMinute: Int = 0,
    val panelAnchor: PanelAnchor = PanelAnchor.BALANCED,
    val layers: LayerSelection = LayerSelection(),
    val isPlaying: Boolean = false,
    val isPlaybackPending: Boolean = false,
    val isHistoryLoading: Boolean = false,
    val legendExpanded: Boolean = false,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val activeGrid: WeatherGrid? = null,
    val quantitativeRainGrid: WeatherGrid? = null,
    val quantitativeForecastFrames: List<WeatherGrid> = emptyList(),
    val quantitativeForecastIndex: Int = 0,
    val mapCenter: GeoPoint = target.coordinate,
    val mapZoom: Float = DEFAULT_MAP_ZOOM,
    val cloudCoverage: CloudCoverage = CloudCoverage.EAST_ASIA,
    val radarCoverage: RadarCoverage = RadarCoverage.WIDE,
    val radarRegionalLoading: Boolean = false,
    val windGrid: WindGrid? = null,
    val winds: List<WindObservation> = emptyList(),
    val windProvenance: WindProvenance = WindProvenance.UNAVAILABLE,
    val isUpdating: Boolean = false,
    val message: String? = null,
)
