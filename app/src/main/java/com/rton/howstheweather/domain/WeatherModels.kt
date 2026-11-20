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

    fun sample(latitude: Double, longitude: Double): Float? =
        sampleOrNaN(latitude, longitude).takeUnless { it.isNaN() }

    /** Allocation-free variant for per-pixel rendering; missing or out-of-bounds values are NaN. */
    fun sampleOrNaN(latitude: Double, longitude: Double): Float {
        val gridLongitude = if (bounds.east > 180.0 && longitude < bounds.west) longitude + 360.0 else longitude
        if (latitude !in bounds.south..bounds.north || gridLongitude !in bounds.west..bounds.east) return Float.NaN
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
        ) return Float.NaN
        val top = topLeft + (topRight - topLeft) * tx
        val bottom = bottomLeft + (bottomRight - bottomLeft) * tx
        return top + (bottom - top) * ty
    }

    /**
     * Render-only C¹ monotonic Catmull-Rom (bicubic) sampler for map tile rendering.
     *
     * Unlike [sampleOrNaN] (which feeds domain decisions and strictly requires all four corners
     * to be finite), this sampler:
     * 1. eliminates bilinear C⁰ slope creases along grid lines when zoomed in,
     * 2. clamps each 1D cubic pass to the `[min, max]` of its central cell pair so it never
     *    overshoots peaks or invents negative rain, and
     * 3. treats `-99` missing sentinels in precipitation grids (`F-B0046-001` encodes dry outer
     *    cells as `-99`) with coverage-weighted bilinear tapering so isolated cells and outer
     *    rings are not eroded by a full cell width.
     */
    fun sampleForDisplayOrNaN(latitude: Double, longitude: Double): Float {
        val gridLongitude = if (bounds.east > 180.0 && longitude < bounds.west) longitude + 360.0 else longitude
        if (latitude !in bounds.south..bounds.north || gridLongitude !in bounds.west..bounds.east) return Float.NaN
        val fx = ((gridLongitude - bounds.west) / (bounds.east - bounds.west) * (width - 1))
            .coerceIn(0.0, (width - 1).toDouble())
        val fy = ((bounds.north - latitude) / (bounds.north - bounds.south) * (height - 1))
            .coerceIn(0.0, (height - 1).toDouble())
        val x1 = fx.toInt().coerceAtMost(width - 2)
        val y1 = fy.toInt().coerceAtMost(height - 2)
        val tx = (fx - x1).toFloat()
        val ty = (fy - y1).toFloat()

        val c00 = validOrNaN(valueAt(x1, y1))
        val c10 = validOrNaN(valueAt(x1 + 1, y1))
        val c01 = validOrNaN(valueAt(x1, y1 + 1))
        val c11 = validOrNaN(valueAt(x1 + 1, y1 + 1))
        if (c00.isNaN() || c10.isNaN() || c01.isNaN() || c11.isNaN()) {
            return samplePartialCellOrNaN(c00, c10, c01, c11, tx, ty)
        }

        val x0 = (x1 - 1).coerceAtLeast(0)
        val x2 = x1 + 1
        val x3 = (x1 + 2).coerceAtMost(width - 1)
        val y0 = (y1 - 1).coerceAtLeast(0)
        val y2 = y1 + 1
        val y3 = (y1 + 2).coerceAtMost(height - 1)

        val wx0 = catmullRomW0(tx)
        val wx1 = catmullRomW1(tx)
        val wx2 = catmullRomW2(tx)
        val wx3 = catmullRomW3(tx)

        val row1 = cubicRowClamped(x0, x1, x2, x3, y1, c00, c10, wx0, wx1, wx2, wx3)
        val row2 = cubicRowClamped(x0, x1, x2, x3, y2, c01, c11, wx0, wx1, wx2, wx3)
        val row0 = cubicRowOuter(x0, x1, x2, x3, y0, row1, wx0, wx1, wx2, wx3)
        val row3 = cubicRowOuter(x0, x1, x2, x3, y3, row2, wx0, wx1, wx2, wx3)

        val wy0 = catmullRomW0(ty)
        val wy1 = catmullRomW1(ty)
        val wy2 = catmullRomW2(ty)
        val wy3 = catmullRomW3(ty)
        val raw = wy0 * row0 + wy1 * row1 + wy2 * row2 + wy3 * row3
        val low = minOf(row1, row2)
        val high = maxOf(row1, row2)
        return raw.coerceIn(low, high)
    }

    private fun validOrNaN(v: Float): Float =
        if (v.isFinite() && v != missingValue) v else Float.NaN

    private fun validOrFallback(x: Int, y: Int, fallback: Float): Float {
        val v = valueAt(x, y)
        return if (v.isFinite() && v != missingValue) v else fallback
    }

    private fun cubicRowClamped(
        x0: Int,
        x1: Int,
        x2: Int,
        x3: Int,
        y: Int,
        v1: Float,
        v2: Float,
        w0: Float,
        w1: Float,
        w2: Float,
        w3: Float,
    ): Float {
        val v0 = validOrFallback(x0, y, v1)
        val v3 = validOrFallback(x3, y, v2)
        val raw = w0 * v0 + w1 * v1 + w2 * v2 + w3 * v3
        return raw.coerceIn(minOf(v1, v2), maxOf(v1, v2))
    }

    private fun cubicRowOuter(
        x0: Int,
        x1: Int,
        x2: Int,
        x3: Int,
        y: Int,
        fallbackRow: Float,
        w0: Float,
        w1: Float,
        w2: Float,
        w3: Float,
    ): Float {
        val v1 = validOrNaN(valueAt(x1, y))
        val v2 = validOrNaN(valueAt(x2, y))
        if (v1.isNaN() || v2.isNaN()) return fallbackRow
        val v0 = validOrFallback(x0, y, v1)
        val v3 = validOrFallback(x3, y, v2)
        val raw = w0 * v0 + w1 * v1 + w2 * v2 + w3 * v3
        return raw.coerceIn(minOf(v1, v2), maxOf(v1, v2))
    }

    private fun samplePartialCellOrNaN(
        c00: Float,
        c10: Float,
        c01: Float,
        c11: Float,
        tx: Float,
        ty: Float,
    ): Float {
        // Luminance/radar grids use NaN only outside the sensor mask; keep that boundary crisp.
        // Precipitation grids (`F-B0046-001`) encode dry cells as `-99` (`NaN`), so tapering
        // valid corners against 0 prevents eroding 1 cell ring and deleting isolated rain cells.
        val taperAgainstZero = unit == WeatherUnit.MILLIMETERS_ONE_HOUR ||
            unit == WeatherUnit.MILLIMETERS_PER_HOUR ||
            unit == WeatherUnit.MILLIMETERS_TWELVE_HOURS
        if (!taperAgainstZero) return Float.NaN
        var weightedSum = 0f
        var validWeight = 0f
        val w00 = (1f - tx) * (1f - ty)
        val w10 = tx * (1f - ty)
        val w01 = (1f - tx) * ty
        val w11 = tx * ty
        if (!c00.isNaN()) {
            weightedSum += c00 * w00
            validWeight += w00
        }
        if (!c10.isNaN()) {
            weightedSum += c10 * w10
            validWeight += w10
        }
        if (!c01.isNaN()) {
            weightedSum += c01 * w01
            validWeight += w01
        }
        if (!c11.isNaN()) {
            weightedSum += c11 * w11
            validWeight += w11
        }
        return if (validWeight <= 0f) Float.NaN else weightedSum
    }

    private fun catmullRomW0(t: Float): Float = t * (-0.5f + t * (1f - 0.5f * t))
    private fun catmullRomW1(t: Float): Float = 1f + t * t * (-2.5f + 1.5f * t)
    private fun catmullRomW2(t: Float): Float = t * (0.5f + t * (2f - 1.5f * t))
    private fun catmullRomW3(t: Float): Float = t * t * (-0.5f + 0.5f * t)

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
enum class AppDestination { NOW, SUMMARY, FORECAST, PRECIPITATION }
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
    /** CWA 體感溫度 range within the period, as published; never derived from the map. */
    val minimumApparentTemperatureCelsius: Int? = null,
    val maximumApparentTemperatureCelsius: Int? = null,
    val windSpeedMetersPerSecond: Int? = null,
    /** Daytime UV index; published only by the weekly product. */
    val uvIndex: Int? = null,
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
    val typhoonEnabled: Boolean = false,
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
    /** Personal feels-like offset in °C: negative dresses warmer, positive dresses lighter. */
    val clothingTolerance: Int = 0,
    val clothingWindow: ClothingWindow = ClothingWindow.HOURS_12,
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
    val tropicalCyclones: List<TropicalCyclone> = emptyList(),
    val isUpdating: Boolean = false,
    val message: String? = null,
)
