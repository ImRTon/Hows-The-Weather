package com.rton.howstheweather.data

import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WindGrid
import com.rton.howstheweather.domain.WindObservation
import com.rton.howstheweather.domain.WindProvenance
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class WeatherSnapshot(
    val radar: WeatherGrid,
    val radarRegional: WeatherGrid? = null,
    val radarFrames: List<WeatherGrid> = emptyList(),
    val radarRegionalFrames: List<WeatherGrid> = emptyList(),
    val rainForecast: List<WeatherGrid>,
    val quantitativeForecastFrames: List<WeatherGrid> = emptyList(),
    val cloudFrames: List<WeatherGrid>,
    val cloudRegionalFrames: List<WeatherGrid> = emptyList(),
    val forecastAtTarget: List<ForecastPoint>,
    val windGrid: WindGrid? = null,
    val winds: List<WindObservation>,
    val windsAreDemo: Boolean = false,
    val windProvenance: WindProvenance = WindProvenance.UNAVAILABLE,
    val issuedAt: Instant,
    val isDemo: Boolean,
    val hourlyAccumulationAtTarget: Float? = null,
    val areaForecast: AreaForecast? = null,
    val notice: String? = null,
)

data class WeatherSupplements(
    val cloudFrames: List<WeatherGrid>,
    val cloudRegionalFrames: List<WeatherGrid>,
    val winds: List<WindObservation>,
)

interface SupplementaryWeatherDataSource {
    suspend fun loadSupplements(): WeatherSupplements
}

interface WeatherDataSource {
    suspend fun load(target: GeoPoint): WeatherSnapshot

    /** Loads target-dependent administrative-area forecast context. */
    suspend fun loadAreaForecast(target: GeoPoint): AreaForecast? = null

    /** Loads the native day/night one-week administrative-area forecast. */
    suspend fun loadWeeklyForecast(target: GeoPoint): AreaForecast? = null

    /** Loads non-decision layers after radar and the official forecast are usable. */
    suspend fun enrich(snapshot: WeatherSnapshot, target: GeoPoint): WeatherSnapshot = snapshot

    /** Emits independently completed supplemental layers without blocking faster ones. */
    fun enrichmentUpdates(snapshot: WeatherSnapshot, target: GeoPoint): Flow<WeatherSnapshot> = flow {
        emit(enrich(snapshot, target))
    }
}
