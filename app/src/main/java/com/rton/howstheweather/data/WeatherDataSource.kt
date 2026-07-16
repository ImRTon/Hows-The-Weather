package com.rton.howstheweather.data

import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.CurrentWeatherObservation
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
    val windProvenance: WindProvenance = WindProvenance.UNAVAILABLE,
    val issuedAt: Instant,
    val hourlyAccumulationAtTarget: Float? = null,
    val areaForecast: AreaForecast? = null,
    val notice: String? = null,
)

/**
 * Supplemental updates are produced from the initial wide-radar snapshot and
 * can arrive after the independently loaded regional radar. Keep that newer
 * regional frame unless the update explicitly contains a replacement.
 */
internal fun WeatherSnapshot.preserveRegionalRadarFrom(previous: WeatherSnapshot?): WeatherSnapshot {
    val previousRegional = previous?.radarRegional ?: return this
    return if (radarRegional == null) copy(radarRegional = previousRegional) else this
}

data class WeatherWindData(
    val windGrid: WindGrid? = null,
    val winds: List<WindObservation> = emptyList(),
    val provenance: WindProvenance = WindProvenance.UNAVAILABLE,
    val notice: String? = null,
)

data class WeatherDecisionForecast(
    val grids: List<WeatherGrid>,
    val forecastAtTarget: List<ForecastPoint>,
    val issuedAt: Instant,
    val hourlyAccumulationAtTarget: Float? = null,
)

enum class WeatherHistoryKind { RADAR, CLOUD }

interface WeatherDataSource {
    suspend fun load(target: GeoPoint): WeatherSnapshot

    /** Loads the nearest valid 10-minute surface observation for the selected target. */
    suspend fun loadCurrentWeather(target: GeoPoint): CurrentWeatherObservation? = null

    /** Loads the official decision forecast without delaying the first radar frame. */
    suspend fun loadDecisionForecast(target: GeoPoint): WeatherDecisionForecast? = null

    /** Loads the higher-resolution regional radar independently from the wide radar. */
    suspend fun loadRegionalRadar(): WeatherGrid? = null

    /** Loads target-dependent administrative-area forecast context. */
    suspend fun loadAreaForecast(target: GeoPoint): AreaForecast? = null

    /** Loads the native day/night one-week administrative-area forecast. */
    suspend fun loadWeeklyForecast(target: GeoPoint): AreaForecast? = null

    /** Loads wind independently so its persisted switch can control priority. */
    suspend fun loadWind(): WeatherWindData = WeatherWindData()

    /** Emits official station observations first when available, then upgrades to the model grid. */
    fun windUpdates(): Flow<WeatherWindData> = flow {
        emit(loadWind())
    }

    /** Loads non-decision layers after radar and the official forecast are usable. */
    suspend fun enrich(snapshot: WeatherSnapshot, target: GeoPoint): WeatherSnapshot = snapshot

    /** Emits independently completed supplemental layers without blocking faster ones. */
    fun enrichmentUpdates(snapshot: WeatherSnapshot, target: GeoPoint): Flow<WeatherSnapshot> = flow {
        emit(enrich(snapshot, target))
    }

    /** Loads expensive observation history only after playback or scrubbing requests it. */
    fun historyUpdates(snapshot: WeatherSnapshot, kind: WeatherHistoryKind): Flow<WeatherSnapshot> = flow {
        emit(snapshot)
    }
}

/** Explicitly unavailable source used when official credentials are not configured. */
class UnavailableWeatherDataSource(private val reason: String) : WeatherDataSource {
    override suspend fun load(target: GeoPoint): WeatherSnapshot = error(reason)
    override suspend fun loadCurrentWeather(target: GeoPoint): CurrentWeatherObservation = error(reason)
}
