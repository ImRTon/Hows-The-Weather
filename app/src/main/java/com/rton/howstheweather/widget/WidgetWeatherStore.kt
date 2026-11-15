package com.rton.howstheweather.widget

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.rton.howstheweather.BuildConfig
import com.rton.howstheweather.data.CwaSatelliteImageParser
import com.rton.howstheweather.data.CwaWeatherDataSource
import com.rton.howstheweather.data.WeatherSnapshotCache
import com.rton.howstheweather.domain.ForecastDecisionEngine
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.HomeUiState
import com.rton.howstheweather.domain.RainState
import com.rton.howstheweather.domain.WeatherUnit
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object WidgetWeatherStore {
    private const val FILE_NAME = "widget-weather.json"
    private const val DEFAULT_LATITUDE = 25.0478
    private const val DEFAULT_LONGITUDE = 121.5319
    private const val FRESHNESS_WINDOW_MILLIS = 20 * 60 * 1000L
    private const val NETWORK_TIMEOUT_MILLIS = 12_000L

    private fun storeFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    fun read(context: Context): WidgetWeatherSummary? = runCatching {
        val file = storeFile(context).takeIf(File::isFile) ?: return null
        val json = JSONObject(file.readText())
        val condition = json.optString("conditionEnglish").takeIf { it.isNotBlank() }
        val rainState = runCatching {
            RainState.valueOf(json.optString("rainState", RainState.UNAVAILABLE.name))
        }.getOrDefault(RainState.UNAVAILABLE)
        WidgetWeatherSummary(
            locationName = json.optString("locationName", "Taipei").ifBlank { "Taipei" },
            conditionEnglish = condition,
            weatherIcon = json.optString("weatherIcon").takeIf { it.isNotBlank() }
                ?: WidgetWeatherFormatter.weatherIconFor(null, condition, rainState),
            currentTempCelsius = json.optDouble("currentTempCelsius", Double.NaN)
                .takeIf(Double::isFinite)?.toFloat(),
            highTempCelsius = if (json.has("highTempCelsius") && !json.isNull("highTempCelsius")) {
                json.optInt("highTempCelsius")
            } else {
                null
            },
            lowTempCelsius = if (json.has("lowTempCelsius") && !json.isNull("lowTempCelsius")) {
                json.optInt("lowTempCelsius")
            } else {
                null
            },
            rainProbabilityPercent = if (json.has("rainProbabilityPercent") && !json.isNull("rainProbabilityPercent")) {
                json.optInt("rainProbabilityPercent")
            } else {
                null
            },
            rainState = rainState,
            hourlyRainMillimeters = json.optDouble("hourlyRainMillimeters", Double.NaN)
                .takeIf(Double::isFinite)?.toFloat(),
            updatedAtEpochMillis = json.optLong("updatedAtEpochMillis", 0L),
        )
    }.getOrNull()

    fun readTargetCoordinate(context: Context): GeoPoint = runCatching {
        val file = storeFile(context).takeIf(File::isFile) ?: return GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
        val json = JSONObject(file.readText())
        val lat = json.optDouble("latitude", DEFAULT_LATITUDE)
        val lon = json.optDouble("longitude", DEFAULT_LONGITUDE)
        if (lat in -90.0..90.0 && lon in -180.0..180.0) {
            GeoPoint(lat, lon)
        } else {
            GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
        }
    }.getOrDefault(GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))

    /**
     * Resolves the coordinate for periodic widget updates without activating GPS:
     * reads the system's passive/cached network location if permission is already granted,
     * falling back to the last saved app target (or Taipei).
     */
    @SuppressLint("MissingPermission")
    private fun resolvePassiveOrSavedCoordinate(context: Context): GeoPoint {
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCoarse || hasFine) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val passiveLocation = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ).mapNotNull { provider ->
                runCatching { locationManager?.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }

            if (passiveLocation != null &&
                passiveLocation.latitude in 21.5..26.5 &&
                passiveLocation.longitude in 118.0..122.5
            ) {
                return GeoPoint(passiveLocation.latitude, passiveLocation.longitude)
            }
        }
        return readTargetCoordinate(context)
    }

    fun write(context: Context, summary: WidgetWeatherSummary, target: GeoPoint? = null) {
        runCatching {
            val existingCoord = target ?: readTargetCoordinate(context)
            val json = JSONObject().apply {
                put("locationName", summary.locationName)
                summary.conditionEnglish?.let { put("conditionEnglish", it) }
                put("weatherIcon", summary.weatherIcon)
                summary.currentTempCelsius?.takeIf(Float::isFinite)?.let { put("currentTempCelsius", it.toDouble()) }
                summary.highTempCelsius?.let { put("highTempCelsius", it) }
                summary.lowTempCelsius?.let { put("lowTempCelsius", it) }
                summary.rainProbabilityPercent?.let { put("rainProbabilityPercent", it) }
                put("rainState", summary.rainState.name)
                summary.hourlyRainMillimeters?.takeIf(Float::isFinite)?.let { put("hourlyRainMillimeters", it.toDouble()) }
                put("updatedAtEpochMillis", summary.updatedAtEpochMillis)
                put("latitude", existingCoord.latitude)
                put("longitude", existingCoord.longitude)
            }
            val file = storeFile(context)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(json.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    fun syncFromUiState(context: Context, state: HomeUiState) {
        if (state.currentWeather == null &&
            state.areaForecast == null &&
            state.weeklyForecast == null &&
            state.decision.state == RainState.UNAVAILABLE
        ) {
            return
        }
        val summary = WidgetWeatherFormatter.fromAppState(
            targetDisplayName = state.target.displayName,
            currentWeather = state.currentWeather,
            areaForecast = state.areaForecast,
            weeklyForecast = state.weeklyForecast,
            decision = state.decision,
        )
        write(context, summary, state.target.coordinate)
        WeatherDesktopWidgetProvider.updateAllWidgets(context)
    }

    suspend fun refreshOnDemand(context: Context, force: Boolean = false): WidgetWeatherSummary? =
        withContext(Dispatchers.IO) {
            val cached = read(context)
            val nowMillis = System.currentTimeMillis()
            if (!force &&
                cached != null &&
                cached.conditionEnglish != null &&
                nowMillis - cached.updatedAtEpochMillis in 0..FRESHNESS_WINDOW_MILLIS
            ) {
                return@withContext cached
            }

            val target = resolvePassiveOrSavedCoordinate(context)
            val decisionEngine = ForecastDecisionEngine()

            val snapshotCache = WeatherSnapshotCache(context.filesDir.resolve("weather-cache"))
            val cachedSnapshot = runCatching { snapshotCache.read() }.getOrNull()
            val snapshotHourlyMm = cachedSnapshot?.let { snap ->
                snap.rainForecast.firstOrNull { it.unit == WeatherUnit.MILLIMETERS_ONE_HOUR }?.sample(target)
                    ?: snap.hourlyAccumulationAtTarget
            }

            if (BuildConfig.CWA_API_KEY.isBlank()) {
                val fallbackDecision = decisionEngine.evaluateHourlyAccumulation(snapshotHourlyMm, Instant.now())
                val summary = cached ?: WidgetWeatherFormatter.fromAppState(
                    targetDisplayName = cachedSnapshot?.areaForecast?.displayName ?: "Taipei",
                    currentWeather = null,
                    areaForecast = cachedSnapshot?.areaForecast,
                    weeklyForecast = null,
                    decision = fallbackDecision,
                )
                write(context, summary, target)
                return@withContext summary
            }

            try {
                val source = CwaWeatherDataSource(
                    apiKey = BuildConfig.CWA_API_KEY,
                    httpCacheDirectory = context.cacheDir.resolve("cwa-http"),
                    responseCacheDirectory = context.cacheDir.resolve("cwa-rest"),
                    satelliteImageParserFactory = {
                        CwaSatelliteImageParser.withBundledEastAsiaProjection(context.resources)
                    },
                )
                coroutineScope {
                    val currentDeferred = async {
                        withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) {
                            runCatching { source.loadCurrentWeather(target) }.getOrNull()
                        }
                    }
                    val areaDeferred = async {
                        withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) {
                            runCatching { source.loadAreaForecast(target) }.getOrNull()
                        }
                    }
                    val weeklyDeferred = async {
                        withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) {
                            runCatching { source.loadWeeklyForecast(target) }.getOrNull()
                        }
                    }
                    val decisionDeferred = async {
                        withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) {
                            runCatching { source.loadDecisionForecast(target) }.getOrNull()
                        }
                    }

                    val currentWeather = currentDeferred.await()
                    val areaForecast = areaDeferred.await() ?: cachedSnapshot?.areaForecast
                    val weeklyForecast = weeklyDeferred.await()
                    val decisionForecast = decisionDeferred.await()

                    val hourlyMm = decisionForecast?.hourlyAccumulationAtTarget
                        ?: decisionForecast?.grids
                            ?.firstOrNull { it.unit == WeatherUnit.MILLIMETERS_ONE_HOUR }
                            ?.sample(target)
                        ?: snapshotHourlyMm
                    val decision = decisionEngine.evaluateHourlyAccumulation(
                        millimeters = hourlyMm,
                        issuedAt = decisionForecast?.issuedAt ?: Instant.now(),
                    )

                    if (currentWeather == null && areaForecast == null && weeklyForecast == null && cached != null) {
                        return@coroutineScope cached
                    }

                    val summary = WidgetWeatherFormatter.fromAppState(
                        targetDisplayName = areaForecast?.countyName
                            ?: currentWeather?.stationName
                            ?: cached?.locationName
                            ?: "Taipei",
                        currentWeather = currentWeather,
                        areaForecast = areaForecast,
                        weeklyForecast = weeklyForecast,
                        decision = decision,
                    )
                    write(context, summary, target)
                    summary
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                cached
            }
        }
}
