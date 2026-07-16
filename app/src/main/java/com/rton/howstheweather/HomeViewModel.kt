package com.rton.howstheweather

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.rton.howstheweather.data.CwaWeatherDataSource
import com.rton.howstheweather.data.CwaSatelliteImageParser
import com.rton.howstheweather.data.MoenvAirQualityDataSource
import com.rton.howstheweather.data.UnavailableWeatherDataSource
import com.rton.howstheweather.data.WeatherSnapshot
import com.rton.howstheweather.data.WeatherSnapshotCache
import com.rton.howstheweather.data.WeatherDataSource
import com.rton.howstheweather.data.WeatherHistoryKind
import com.rton.howstheweather.data.ObservedWeatherTimeline
import com.rton.howstheweather.data.OBSERVATION_FRAME_INTERVAL_MINUTES
import com.rton.howstheweather.data.OBSERVATION_HISTORY_FRAME_COUNT
import com.rton.howstheweather.data.OBSERVATION_HISTORY_MINUTES
import com.rton.howstheweather.data.isObservedFrame
import com.rton.howstheweather.data.preserveRegionalRadarFrom
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.rton.howstheweather.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

private suspend fun <T> catchingCancellable(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

class HomeViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val source: WeatherDataSource = if (BuildConfig.CWA_API_KEY.isNotBlank()) {
        CwaWeatherDataSource(
            BuildConfig.CWA_API_KEY,
            satelliteImageParser = CwaSatelliteImageParser.withBundledEastAsiaProjection(
                application.resources,
            ),
        )
    } else {
        UnavailableWeatherDataSource("尚未設定 CWA_API_KEY，官方氣象資料不可用")
    }
    private val decisionEngine = ForecastDecisionEngine()
    private val airQualitySource = BuildConfig.MOENV_API_KEY
        .takeIf(String::isNotBlank)
        ?.let(::MoenvAirQualityDataSource)
    private val snapshotCache = WeatherSnapshotCache(application.filesDir.resolve("weather-cache"))
    private val observedTimeline = ObservedWeatherTimeline()
    private var snapshot: WeatherSnapshot? = null
    private var cachedSnapshot: WeatherSnapshot? = null
    private var refreshJob: Job? = null
    private var decisionForecastJob: Job? = null
    private var regionalRadarJob: Job? = null
    private var enrichmentJob: Job? = null
    private var historyJob: Job? = null
    private var windJob: Job? = null
    private var windIdlePreloadJob: Job? = null
    private var currentWeatherJob: Job? = null
    private var areaForecastJob: Job? = null
    private var cacheWriteJob: Job? = null
    private val cacheWriteMutex = Mutex()
    private var cacheWriteGeneration = 0L
    private var refreshSequence = 0L
    private var cacheWasRead = false
    private var panelSelectionChanged = false
    private var windSelectionChanged = false
    private var requestedHistoryKind: WeatherHistoryKind? = null
    private val initialTarget = TargetLocation(GeoPoint(25.0478, 121.5319), "臺北市中心", false)
    private val emptyDecision = decisionEngine.evaluate(listOf(ForecastPoint(0, null)), Instant.now())
    private val _uiState = MutableStateFlow(
        HomeUiState(
            target = initialTarget,
            decision = emptyDecision,
            panelAnchor = savedStateHandle.get<String>("panelAnchor")?.let { runCatching { PanelAnchor.valueOf(it) }.getOrNull() }
                ?: PanelAnchor.BALANCED,
            themePreference = savedStateHandle.get<String>("theme")?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
                ?: ThemePreference.SYSTEM,
            layers = LayerSelection(
                windEnabled = savedStateHandle.get<Boolean>(WIND_ENABLED_STATE_KEY) ?: false,
            ),
            destination = savedStateHandle.get<String>("destination")
                ?.let { runCatching { AppDestination.valueOf(it) }.getOrNull() }
                ?: AppDestination.NOW,
        ),
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        restorePanelAnchor()
        restoreWindPreference()
        refresh()
        viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_MILLIS)
                refresh()
            }
        }
    }

    private fun restorePanelAnchor() {
        viewModelScope.launch {
            val persistedAnchor = catchingCancellable {
                getApplication<Application>().userPreferencesDataStore.data.first()[PANEL_ANCHOR_KEY]
                    ?.let { value -> runCatching { PanelAnchor.valueOf(value) }.getOrNull() }
            }.getOrNull()
            if (persistedAnchor != null && !panelSelectionChanged) {
                savedStateHandle[PANEL_ANCHOR_STATE_KEY] = persistedAnchor.name
                _uiState.update { it.copy(panelAnchor = persistedAnchor) }
            }
        }
    }

    private fun restoreWindPreference() {
        viewModelScope.launch {
            val persistedEnabled = catchingCancellable {
                getApplication<Application>().userPreferencesDataStore.data.first()[WIND_ENABLED_KEY]
            }.getOrNull() ?: return@launch
            if (!windSelectionChanged && savedStateHandle.get<Boolean>(WIND_ENABLED_STATE_KEY) == null) {
                savedStateHandle[WIND_ENABLED_STATE_KEY] = persistedEnabled
                _uiState.update { state ->
                    state.copy(layers = state.layers.copy(windEnabled = persistedEnabled))
                }
                if (persistedEnabled) ensureWindLoaded(highPriority = true)
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        decisionForecastJob?.cancel()
        regionalRadarJob?.cancel()
        enrichmentJob?.cancel()
        historyJob?.cancel()
        requestedHistoryKind = historyKindNeededByCurrentUi() ?: requestedHistoryKind
        val sequence = ++refreshSequence
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true) }
            try {
                val target = _uiState.value.target
                refreshLocationDetails(target.coordinate)
                var criticalApplied = false
                if (!cacheWasRead) {
                    cacheWasRead = true
                    launch {
                        snapshotCache.read()?.let { cached ->
                            cachedSnapshot = cached
                            if (!criticalApplied) {
                                applySnapshot(cached)
                            }
                        }
                    }
                }

                val critical = withTimeoutOrNull(CRITICAL_LOAD_TIMEOUT_MILLIS) {
                    source.load(target.coordinate)
                } ?: error("CWA 核心資料超過 15 秒未完成")
                criticalApplied = true
                val previous = snapshot ?: cachedSnapshot
                val immediatelyUsable = critical.copy(
                    radarRegional = critical.radarRegional ?: previous?.radarRegional,
                    radarFrames = observedTimeline.merge(
                        critical.radar,
                        critical.radarFrames + previous?.radarFrames.orEmpty() + listOfNotNull(previous?.radar),
                    ).dropLast(1),
                    radarRegionalFrames = critical.radarRegional?.let { regional ->
                        observedTimeline.merge(
                            regional,
                            critical.radarRegionalFrames + previous?.radarRegionalFrames.orEmpty() +
                                listOfNotNull(previous?.radarRegional),
                        ).dropLast(1)
                    }.orEmpty(),
                    cloudFrames = previous?.cloudFrames.orEmpty(),
                    cloudRegionalFrames = previous?.cloudRegionalFrames.orEmpty(),
                    rainForecast = critical.rainForecast.ifEmpty { previous?.rainForecast.orEmpty() },
                    forecastAtTarget = critical.forecastAtTarget.ifEmpty { previous?.forecastAtTarget.orEmpty() },
                    quantitativeForecastFrames = critical.quantitativeForecastFrames
                        .ifEmpty { previous?.quantitativeForecastFrames.orEmpty() },
                    windGrid = previous?.windGrid,
                    winds = previous?.winds.orEmpty(),
                    windProvenance = previous?.windProvenance ?: critical.windProvenance,
                    issuedAt = if (critical.rainForecast.isEmpty() && previous?.rainForecast?.isNotEmpty() == true) {
                        previous.issuedAt
                    } else {
                        critical.issuedAt
                    },
                    hourlyAccumulationAtTarget = critical.hourlyAccumulationAtTarget
                        ?: previous?.hourlyAccumulationAtTarget,
                )
                applySnapshot(immediatelyUsable)
                scheduleCacheWrite(immediatelyUsable)
                startDecisionForecast(target.coordinate)
                ensureRegionalRadarLoaded(forceRefresh = true)
                startEnrichment(immediatelyUsable, target.coordinate)
                scheduleWindPreload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val lastOfficial = snapshot ?: cachedSnapshot
                if (lastOfficial != null) {
                    applySnapshot(
                        lastOfficial.copy(
                            notice = "CWA 載入失敗：${error.message ?: "未知錯誤"} · 保留上次官方資料",
                        ),
                    )
                    scheduleWindPreload()
                } else {
                    _uiState.update { it.copy(message = error.message ?: "資料載入失敗") }
                }
            } finally {
                if (sequence == refreshSequence) {
                    _uiState.update { it.copy(isUpdating = false) }
                }
            }
        }
    }

    private fun startDecisionForecast(target: GeoPoint) {
        decisionForecastJob?.cancel()
        decisionForecastJob = viewModelScope.launch {
            try {
                val forecast = source.loadDecisionForecast(target) ?: return@launch
                val current = snapshot ?: return@launch
                val updated = current.copy(
                    rainForecast = forecast.grids,
                    forecastAtTarget = forecast.forecastAtTarget,
                    issuedAt = forecast.issuedAt,
                    hourlyAccumulationAtTarget = forecast.hourlyAccumulationAtTarget,
                )
                applySnapshot(updated)
                scheduleCacheWrite(snapshot ?: updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(message = "一小時降雨預報載入失敗：${error.message ?: "未知錯誤"} · 雷達仍可使用")
                }
            }
        }
    }

    private fun ensureRegionalRadarLoaded(forceRefresh: Boolean) {
        val current = snapshot ?: return
        if (regionalRadarJob?.isActive == true) return
        if (!forceRefresh && current.radarRegional != null) return
        _uiState.update { state ->
            state.copy(radarRegionalLoading = current.radarRegional == null)
        }
        regionalRadarJob = viewModelScope.launch {
            try {
                val regional = source.loadRegionalRadar() ?: return@launch
                val latest = snapshot ?: return@launch
                val updated = latest.copy(radarRegional = regional)
                applySnapshot(updated)
                scheduleCacheWrite(snapshot ?: updated)
                val state = _uiState.value
                if (state.layers.primary == PrimaryLayer.RADAR_RAIN &&
                    state.radarCoverage == RadarCoverage.LOCAL &&
                    (state.selectedMinute < 0 || state.isPlaying)
                ) {
                    requestHistory(WeatherHistoryKind.RADAR)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val failure = error.message ?: "未知錯誤"
                val fallback = if (snapshot?.radarRegional != null) {
                    "保留上次區域雷達"
                } else {
                    "暫時顯示廣域雷達"
                }
                _uiState.update {
                    it.copy(message = "區域雷達載入失敗：$failure · $fallback")
                }
            } finally {
                if (regionalRadarJob === coroutineContext[Job]) {
                    regionalRadarJob = null
                    _uiState.update { it.copy(radarRegionalLoading = false) }
                }
            }
        }
    }

    private fun startEnrichment(base: WeatherSnapshot, target: GeoPoint) {
        enrichmentJob?.cancel()
        historyJob?.cancel()
        requestedHistoryKind = historyKindNeededByCurrentUi() ?: requestedHistoryKind
        enrichmentJob = viewModelScope.launch {
            try {
                source.enrichmentUpdates(base, target).collect { enriched ->
                    applySnapshot(enriched)
                    scheduleCacheWrite(snapshot ?: enriched)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update { it.copy(message = error.message ?: "補充圖層載入失敗") }
            } finally {
                if (enrichmentJob === coroutineContext[Job]) {
                    enrichmentJob = null
                    startRequestedHistoryIfReady()
                }
            }
        }
    }

    private fun scheduleCacheWrite(loaded: WeatherSnapshot) {
        val compact = loaded.copy(
            radarFrames = emptyList(),
            radarRegionalFrames = emptyList(),
            cloudFrames = listOfNotNull(loaded.cloudFrames.maxByOrNull(WeatherGrid::validAt)),
            cloudRegionalFrames = listOfNotNull(
                loaded.cloudRegionalFrames.maxByOrNull(WeatherGrid::validAt),
            ),
        )
        val generation = ++cacheWriteGeneration
        cacheWriteJob?.cancel()
        cacheWriteJob = viewModelScope.launch {
            delay(CACHE_WRITE_DEBOUNCE_MILLIS)
            cacheWriteMutex.withLock {
                if (generation == cacheWriteGeneration) {
                    catchingCancellable { snapshotCache.write(compact) }
                }
            }
        }
    }

    private fun applySnapshot(incoming: WeatherSnapshot) {
        val previous = snapshot
        val loaded = incoming.preserveRegionalRadarFrom(previous)
        val targetPoint = _uiState.value.target.coordinate
        val applicableAreaForecast = loaded.areaForecast?.takeIf { it.target == targetPoint }
            ?: previous?.areaForecast?.takeIf { it.target == targetPoint }
        val previousWind = previous?.takeIf { shouldPreserveWind(it, loaded) }
        val previousForecast = previous?.takeIf {
            loaded.rainForecast.isEmpty() && it.rainForecast.isNotEmpty()
        }
        val resolved = loaded.copy(
            areaForecast = applicableAreaForecast,
            rainForecast = previousForecast?.rainForecast ?: loaded.rainForecast,
            forecastAtTarget = previousForecast?.forecastAtTarget ?: loaded.forecastAtTarget,
            issuedAt = previousForecast?.issuedAt ?: loaded.issuedAt,
            hourlyAccumulationAtTarget = if (previousForecast != null) {
                previousForecast.hourlyAccumulationAtTarget
            } else {
                loaded.hourlyAccumulationAtTarget
            },
            radarFrames = observedTimeline.merge(
                loaded.radar,
                loaded.radarFrames + previous?.radarFrames.orEmpty() + listOfNotNull(previous?.radar),
            ).dropLast(1),
            radarRegionalFrames = loaded.radarRegional?.let { current ->
                observedTimeline.merge(
                    current,
                    loaded.radarRegionalFrames + previous?.radarRegionalFrames.orEmpty() +
                        listOfNotNull(previous?.radarRegional),
                ).dropLast(1)
            }.orEmpty(),
            cloudFrames = mergeObservedFrames(loaded.cloudFrames, previous?.cloudFrames.orEmpty()),
            cloudRegionalFrames = mergeObservedFrames(
                loaded.cloudRegionalFrames,
                previous?.cloudRegionalFrames.orEmpty(),
            ),
            windGrid = if (previousWind != null) previousWind.windGrid else loaded.windGrid,
            winds = if (previousWind != null) previousWind.winds else loaded.winds,
            windProvenance = if (previousWind != null) previousWind.windProvenance else loaded.windProvenance,
        )
        snapshot = resolved
        _uiState.update {
            val cloudCoverage = resolveCloudCoverage(
                current = it.cloudCoverage,
                zoom = it.mapZoom,
                center = it.mapCenter,
                loaded = resolved,
            )
            val radarCoverage = resolveRadarCoverage(
                current = it.radarCoverage,
                zoom = it.mapZoom,
                center = it.mapCenter,
                loaded = resolved,
            )
            val selectedMinute = it.selectedMinute.coerceIn(-OBSERVATION_HISTORY_MINUTES, TIMELINE_END_MINUTE)
            it.copy(
                target = applicableAreaForecast?.let { forecast ->
                    it.target.copy(displayName = forecast.displayName)
                } ?: it.target,
                decision = decisionFor(resolved, it.target.coordinate),
                areaForecast = applicableAreaForecast,
                areaForecastLoading = if (applicableAreaForecast != null) false else it.areaForecastLoading,
                selectedMinute = selectedMinute,
                activeGrid = gridFor(
                    selectedMinute, it.layers.primary, cloudCoverage, radarCoverage,
                ) ?: resolved.radar.takeIf { _ -> it.layers.primary == PrimaryLayer.RADAR_RAIN },
                quantitativeRainGrid = resolved.rainForecast
                    .firstOrNull { grid -> grid.unit == WeatherUnit.MILLIMETERS_ONE_HOUR }
                    ?: resolved.rainForecast.lastOrNull(),
                quantitativeForecastFrames = resolved.quantitativeForecastFrames,
                quantitativeForecastIndex = it.quantitativeForecastIndex.coerceIn(
                    0,
                    resolved.quantitativeForecastFrames.lastIndex.coerceAtLeast(0),
                ),
                cloudCoverage = cloudCoverage,
                radarCoverage = radarCoverage,
                radarRegionalLoading = it.radarRegionalLoading && resolved.radarRegional == null,
                windGrid = resolved.windGrid,
                winds = resolved.winds,
                windProvenance = resolved.windProvenance,
                message = resolved.notice,
            )
        }
        startRequestedHistoryIfReady()
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    fun showMessage(message: String) = _uiState.update { it.copy(message = message) }

    fun selectTarget(point: GeoPoint, deviceLocation: Boolean = false) {
        val loaded = snapshot
        currentWeatherJob?.cancel()
        areaForecastJob?.cancel()
        snapshot = loaded?.copy(areaForecast = null)
        _uiState.update {
            it.copy(
                target = TargetLocation(point, if (deviceLocation) "目前位置" else "地圖選取位置", deviceLocation),
                decision = loaded?.let { data -> decisionFor(data, point) } ?: it.decision,
                currentWeather = null,
                currentWeatherLoading = true,
                currentWeatherUnavailableReason = null,
                areaForecast = null,
                areaForecastLoading = true,
                weeklyForecast = null,
                weeklyForecastLoading = true,
                airQuality = null,
                airQualityLoading = airQualitySource != null,
                airQualityUnavailableReason = if (airQualitySource == null) "尚未設定 MOENV_API_KEY" else null,
            )
        }
        // Radar, forecast, and satellite observations do not depend on the
        // selected target. Location details do, so restart them even while the
        // initial critical-weather refresh is still running.
        if (loaded == null && refreshJob?.isActive != true) {
            refresh()
        } else {
            refreshLocationDetails(point)
        }
    }

    private fun refreshLocationDetails(target: GeoPoint) {
        currentWeatherJob?.cancel()
        areaForecastJob?.cancel()
        _uiState.update {
            it.copy(
                currentWeatherLoading = true,
                currentWeatherUnavailableReason = null,
                areaForecastLoading = true,
                weeklyForecastLoading = true,
                airQualityLoading = airQualitySource != null,
                airQualityUnavailableReason = if (airQualitySource == null) "尚未設定 MOENV_API_KEY" else null,
            )
        }
        currentWeatherJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val currentWeatherResult = catchingCancellable {
                val completed = withTimeoutOrNull(CURRENT_WEATHER_TIMEOUT_MILLIS) {
                    Unit to source.loadCurrentWeather(target)
                } ?: error("目前天氣查詢超過 10 秒")
                completed.second
            }
            if (_uiState.value.target.coordinate != target) return@launch
            _uiState.update { state ->
                state.copy(
                    currentWeather = currentWeatherResult.getOrNull(),
                    currentWeatherLoading = false,
                    currentWeatherUnavailableReason = when {
                        currentWeatherResult.isFailure -> {
                            val error = currentWeatherResult.exceptionOrNull()
                            val detail = error?.message
                                ?.takeIf(String::isNotBlank)
                                ?: error?.javaClass?.simpleName
                                ?: "未知錯誤"
                            "目前觀測無法載入：$detail"
                        }
                        currentWeatherResult.getOrNull() == null -> "附近沒有可用的目前天氣觀測"
                        else -> null
                    },
                )
            }
        }
        areaForecastJob = viewModelScope.launch {
            coroutineScope {
                val shortRequest = async { catchingCancellable { source.loadAreaForecast(target) } }
                val weeklyRequest = async { catchingCancellable { source.loadWeeklyForecast(target) } }
                val airRequest = async {
                    airQualitySource?.let { source -> catchingCancellable { source.loadNearest(target) } }
                }
                val shortResult = shortRequest.await()
                val weeklyResult = weeklyRequest.await()
                val airResult = airRequest.await()
                if (_uiState.value.target.coordinate != target) return@coroutineScope

                val shortForecast = shortResult.getOrNull()
                snapshot = snapshot?.copy(areaForecast = shortForecast)
                _uiState.update { state ->
                    state.copy(
                        target = shortForecast?.let { forecast ->
                            state.target.copy(displayName = forecast.displayName)
                        } ?: state.target,
                        areaForecast = shortForecast,
                        areaForecastLoading = false,
                        weeklyForecast = weeklyResult.getOrNull(),
                        weeklyForecastLoading = false,
                        airQuality = airResult?.getOrNull(),
                        airQualityLoading = false,
                        airQualityUnavailableReason = when {
                            airQualitySource == null -> "尚未設定 MOENV_API_KEY"
                            airResult?.isFailure == true -> {
                                val error = airResult.exceptionOrNull()
                                val detail = error?.message
                                    ?.takeIf(String::isNotBlank)
                                    ?: error?.javaClass?.simpleName
                                    ?: "未知錯誤"
                                "環境部 AQI 無法載入：$detail"
                            }
                            airResult?.getOrNull() == null -> "附近沒有可用的 AQI 測站"
                            else -> null
                        },
                    )
                }
            }
        }
    }

    private fun windTimestamp(value: WeatherSnapshot): Instant? =
        value.windGrid?.validAt ?: value.winds.maxOfOrNull { it.observedAt }

    private fun mergeObservedFrames(incoming: List<WeatherGrid>, existing: List<WeatherGrid>): List<WeatherGrid> {
        val current = incoming.maxByOrNull(WeatherGrid::validAt) ?: return existing
        return observedTimeline.merge(current, incoming + existing)
    }

    private fun shouldPreserveWind(previous: WeatherSnapshot, incoming: WeatherSnapshot): Boolean {
        val previousRank = previous.windProvenance.priority
        val incomingRank = incoming.windProvenance.priority
        if (previousRank != incomingRank) return previousRank > incomingRank
        val previousAt = windTimestamp(previous) ?: return false
        val incomingAt = windTimestamp(incoming) ?: return true
        return previousAt > incomingAt
    }

    private val WindProvenance.priority: Int
        get() = when (this) {
            WindProvenance.MODEL -> 3
            WindProvenance.OBSERVATION -> 2
            WindProvenance.UNAVAILABLE -> 0
        }

    private fun decisionFor(loaded: WeatherSnapshot, point: GeoPoint): ForecastDecision {
        val hourlyGrid = loaded.rainForecast.firstOrNull { it.unit == WeatherUnit.MILLIMETERS_ONE_HOUR }
        if (hourlyGrid != null) {
            return decisionEngine.evaluateHourlyAccumulation(hourlyGrid.sample(point), loaded.issuedAt)
        }
        val series = loaded.rainForecast.map { grid ->
            ForecastPoint(
                minutesFromNow = java.time.Duration.between(loaded.issuedAt, grid.validAt).toMinutes().toInt().coerceAtLeast(0),
                millimetersPerHour = grid.sample(point),
            )
        }
        return decisionEngine.evaluate(series.ifEmpty { loaded.forecastAtTarget }, loaded.issuedAt)
    }

    fun setMinute(minute: Int) {
        val value = minute.coerceIn(-OBSERVATION_HISTORY_MINUTES, TIMELINE_END_MINUTE)
        val primary = _uiState.value.layers.primary
        if (value < 0) {
            when (primary) {
                PrimaryLayer.RADAR_RAIN -> requestHistory(WeatherHistoryKind.RADAR)
                PrimaryLayer.CLOUD -> requestHistory(WeatherHistoryKind.CLOUD)
                PrimaryLayer.ONE_HOUR_RAIN -> Unit
            }
        }
        val grid = gridFor(value, primary)
        _uiState.update { it.copy(selectedMinute = value, activeGrid = grid) }
    }

    private fun gridFor(
        minute: Int,
        layer: PrimaryLayer,
        coverage: CloudCoverage = _uiState.value.cloudCoverage,
        radarCoverage: RadarCoverage = _uiState.value.radarCoverage,
    ): WeatherGrid? {
        val loaded = snapshot ?: return null
        if (layer == PrimaryLayer.ONE_HOUR_RAIN) {
            return loaded.rainForecast.firstOrNull { it.unit == WeatherUnit.MILLIMETERS_ONE_HOUR }
                ?: loaded.rainForecast.lastOrNull()
        }
        if (layer == PrimaryLayer.CLOUD) {
            val frames = cloudFrames(loaded, coverage).filter(WeatherGrid::isObservedFrame)
            val reference = frames.maxByOrNull { it.validAt } ?: return null
            return frames.minByOrNull {
                kotlin.math.abs(java.time.Duration.between(reference.validAt, it.validAt).toMinutes().toInt() - minute)
            }
        }
        val currentRadar = if (radarCoverage == RadarCoverage.LOCAL) loaded.radarRegional ?: loaded.radar else loaded.radar
        val frames = if (radarCoverage == RadarCoverage.LOCAL && loaded.radarRegionalFrames.isNotEmpty()) {
            loaded.radarRegionalFrames
        } else loaded.radarFrames
        return (frames.filter(WeatherGrid::isObservedFrame) + currentRadar).minByOrNull {
            kotlin.math.abs(java.time.Duration.between(currentRadar.validAt, it.validAt).toMinutes().toInt() - minute)
        }
    }

    fun setPanel(anchor: PanelAnchor) {
        panelSelectionChanged = true
        savedStateHandle[PANEL_ANCHOR_STATE_KEY] = anchor.name
        _uiState.update { it.copy(panelAnchor = anchor) }
        viewModelScope.launch {
            getApplication<Application>().userPreferencesDataStore.edit { preferences ->
                preferences[PANEL_ANCHOR_KEY] = anchor.name
            }
        }
    }

    fun cyclePanel() {
        setPanel(when (_uiState.value.panelAnchor) {
            PanelAnchor.DECISION -> PanelAnchor.BALANCED
            PanelAnchor.BALANCED -> PanelAnchor.MAP
            PanelAnchor.MAP -> PanelAnchor.DECISION
        })
    }

    fun setPrimaryLayer(layer: PrimaryLayer) {
        val minute = _uiState.value.selectedMinute
        if (minute < 0) {
            when (layer) {
                PrimaryLayer.RADAR_RAIN -> requestHistory(WeatherHistoryKind.RADAR)
                PrimaryLayer.CLOUD -> requestHistory(WeatherHistoryKind.CLOUD)
                PrimaryLayer.ONE_HOUR_RAIN -> Unit
            }
        }
        _uiState.update {
            val activeGrid = gridFor(minute, layer)
            it.copy(
                layers = it.layers.copy(primary = layer),
                selectedMinute = minute,
                activeGrid = activeGrid,
                isPlaying = if (layer == PrimaryLayer.ONE_HOUR_RAIN) false else it.isPlaying,
                message = it.message,
            )
        }
    }

    fun setMapViewport(center: GeoPoint, zoom: Float) {
        val previous = _uiState.value
        val coverage = resolveCloudCoverage(previous.cloudCoverage, zoom, center, snapshot)
        val radarCoverage = resolveRadarCoverage(previous.radarCoverage, zoom, center, snapshot)
        _uiState.update { state ->
            val selectedMinute = state.selectedMinute
            state.copy(
                mapCenter = center,
                mapZoom = zoom,
                cloudCoverage = coverage,
                radarCoverage = radarCoverage,
                selectedMinute = selectedMinute,
                activeGrid = gridFor(selectedMinute, state.layers.primary, coverage, radarCoverage),
            )
        }
        if (previous.radarCoverage != radarCoverage &&
            radarCoverage == RadarCoverage.LOCAL &&
            previous.layers.primary == PrimaryLayer.RADAR_RAIN &&
            (previous.selectedMinute < 0 || previous.isPlaying)
        ) {
            requestHistory(WeatherHistoryKind.RADAR)
        }
    }

    private fun activeCloudFrames(): List<WeatherGrid> = snapshot?.let {
        cloudFrames(it, _uiState.value.cloudCoverage)
    }.orEmpty()

    private fun cloudFrames(loaded: WeatherSnapshot, coverage: CloudCoverage): List<WeatherGrid> =
        if (coverage == CloudCoverage.TAIWAN && loaded.cloudRegionalFrames.isNotEmpty()) {
            loaded.cloudRegionalFrames
        } else {
            loaded.cloudFrames
        }

    private fun resolveCloudCoverage(
        current: CloudCoverage,
        zoom: Float,
        center: GeoPoint,
        loaded: WeatherSnapshot?,
    ): CloudCoverage = CloudCoverageSelector.select(
        current = current,
        zoom = zoom,
        center = center,
        taiwanBounds = loaded?.cloudRegionalFrames?.firstOrNull()?.bounds,
    )

    private fun resolveRadarCoverage(
        current: RadarCoverage,
        zoom: Float,
        center: GeoPoint,
        loaded: WeatherSnapshot?,
    ): RadarCoverage = RadarCoverageSelector.select(
        current = current,
        zoom = zoom,
        center = center,
        localBounds = loaded?.radarRegional?.bounds,
    )

    fun setWindEnabled(enabled: Boolean) {
        windSelectionChanged = true
        savedStateHandle[WIND_ENABLED_STATE_KEY] = enabled
        _uiState.update { it.copy(layers = it.layers.copy(windEnabled = enabled)) }
        if (enabled) ensureWindLoaded(highPriority = true) else windJob?.cancel()
        viewModelScope.launch {
            getApplication<Application>().userPreferencesDataStore.edit { preferences ->
                preferences[WIND_ENABLED_KEY] = enabled
            }
        }
    }

    private fun scheduleWindPreload() {
        windIdlePreloadJob?.cancel()
        if (_uiState.value.layers.windEnabled) {
            ensureWindLoaded(highPriority = true)
            return
        }
        if (snapshot?.let(::windTimestamp) != null) return
        windIdlePreloadJob = viewModelScope.launch {
            delay(WIND_IDLE_PRELOAD_DELAY_MILLIS)
            if (!_uiState.value.layers.windEnabled && snapshot?.let(::windTimestamp) == null) {
                ensureWindLoaded(highPriority = false)
            }
        }
    }

    private fun ensureWindLoaded(highPriority: Boolean) {
        if (windJob?.isActive == true || snapshot == null) return
        windJob = viewModelScope.launch {
            if (!highPriority) kotlinx.coroutines.yield()
            try {
                source.windUpdates().collect { loadedWind ->
                    val current = snapshot ?: return@collect
                    val updated = current.copy(
                        windGrid = loadedWind.windGrid,
                        winds = loadedWind.winds,
                        windProvenance = loadedWind.provenance,
                        notice = listOfNotNull(current.notice, loadedWind.notice)
                            .distinct()
                            .takeIf(List<String>::isNotEmpty)
                            ?.joinToString("；"),
                    )
                    applySnapshot(updated)
                    scheduleCacheWrite(snapshot ?: updated)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (_uiState.value.layers.windEnabled) {
                    _uiState.update { it.copy(message = error.message ?: "風場載入失敗") }
                }
            }
        }
    }

    private fun requestHistory(kind: WeatherHistoryKind) {
        requestedHistoryKind = kind
        if (kind == WeatherHistoryKind.RADAR) enrichmentJob?.cancel()
        startRequestedHistoryIfReady()
    }

    private fun historyKindNeededByCurrentUi(): WeatherHistoryKind? {
        val state = _uiState.value
        if (state.selectedMinute >= 0 && !state.isPlaying) return null
        return when (state.layers.primary) {
            PrimaryLayer.RADAR_RAIN -> WeatherHistoryKind.RADAR
            PrimaryLayer.CLOUD -> WeatherHistoryKind.CLOUD
            PrimaryLayer.ONE_HOUR_RAIN -> null
        }
    }

    private fun startRequestedHistoryIfReady() {
        val kind = requestedHistoryKind ?: return
        if (historyJob?.isActive == true) return
        val current = snapshot ?: return
        if (kind == WeatherHistoryKind.RADAR &&
            _uiState.value.radarCoverage == RadarCoverage.LOCAL &&
            current.radarRegional == null
        ) {
            ensureRegionalRadarLoaded(forceRefresh = false)
            return
        }
        if (enrichmentJob?.isActive == true) {
            if (kind == WeatherHistoryKind.RADAR) {
                enrichmentJob?.cancel()
                return
            }
            if (current.cloudFrames.isEmpty() && current.cloudRegionalFrames.isEmpty()) return
        }
        val alreadyLoaded = when (kind) {
            WeatherHistoryKind.RADAR -> {
                val frames = if (_uiState.value.radarCoverage == RadarCoverage.LOCAL) {
                    current.radarRegionalFrames
                } else {
                    current.radarFrames
                }
                frames.size >= OBSERVATION_HISTORY_FRAME_COUNT - 1
            }
            WeatherHistoryKind.CLOUD -> {
                val frames = if (current.cloudRegionalFrames.isNotEmpty()) {
                    current.cloudRegionalFrames
                } else {
                    current.cloudFrames
                }
                frames.size >= OBSERVATION_HISTORY_FRAME_COUNT
            }
        }
        if (alreadyLoaded) {
            requestedHistoryKind = null
            return
        }
        if (kind == WeatherHistoryKind.CLOUD &&
            current.cloudFrames.isEmpty() && current.cloudRegionalFrames.isEmpty()
        ) return
        requestedHistoryKind = null
        historyJob = viewModelScope.launch {
            try {
                source.historyUpdates(current, kind).collect { loaded ->
                    applySnapshot(loaded)
                    scheduleCacheWrite(snapshot ?: loaded)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update { it.copy(message = error.message ?: "歷史觀測載入失敗") }
            } finally {
                if (historyJob === coroutineContext[Job]) {
                    historyJob = null
                    startRequestedHistoryIfReady()
                }
            }
        }
    }

    fun setOpacity(value: Float) = _uiState.update { it.copy(layers = it.layers.copy(opacity = value)) }
    fun toggleLegend() = _uiState.update { it.copy(legendExpanded = !it.legendExpanded) }
    fun setQuantitativeForecastIndex(index: Int) = _uiState.update { state ->
        state.copy(
            quantitativeForecastIndex = index.coerceIn(
                0,
                state.quantitativeForecastFrames.lastIndex.coerceAtLeast(0),
            ),
        )
    }
    fun selectDestination(destination: AppDestination) {
        savedStateHandle["destination"] = destination.name
        _uiState.update { it.copy(destination = destination) }
    }
    fun cycleTheme() = _uiState.update {
        val next = when (it.themePreference) {
            ThemePreference.SYSTEM -> ThemePreference.LIGHT
            ThemePreference.LIGHT -> ThemePreference.DARK
            ThemePreference.DARK -> ThemePreference.SYSTEM
        }
        savedStateHandle["theme"] = next.name
        it.copy(themePreference = next)
    }

    fun togglePlayback() {
        val start = !_uiState.value.isPlaying
        _uiState.update { it.copy(isPlaying = start) }
        if (start) viewModelScope.launch {
            when (_uiState.value.layers.primary) {
                PrimaryLayer.RADAR_RAIN -> requestHistory(WeatherHistoryKind.RADAR)
                PrimaryLayer.CLOUD -> requestHistory(WeatherHistoryKind.CLOUD)
                PrimaryLayer.ONE_HOUR_RAIN -> Unit
            }
            // Playback is observation-only: move chronologically from history to now.
            if (_uiState.value.selectedMinute >= 0) setMinute(-OBSERVATION_HISTORY_MINUTES)
            while (_uiState.value.isPlaying) {
                delay(800)
                val next = _uiState.value.selectedMinute + OBSERVATION_FRAME_INTERVAL_MINUTES
                if (next >= TIMELINE_END_MINUTE) {
                    setMinute(TIMELINE_END_MINUTE)
                    _uiState.update { it.copy(isPlaying = false) }
                } else {
                    setMinute(next)
                }
            }
        }
    }

    private companion object {
        val PANEL_ANCHOR_KEY = stringPreferencesKey("panel_anchor")
        val WIND_ENABLED_KEY = booleanPreferencesKey("wind_enabled")
        const val PANEL_ANCHOR_STATE_KEY = "panelAnchor"
        const val WIND_ENABLED_STATE_KEY = "windEnabled"
        const val TIMELINE_END_MINUTE = 0
        const val CURRENT_WEATHER_TIMEOUT_MILLIS = 10_000L
        const val CRITICAL_LOAD_TIMEOUT_MILLIS = 15_000L
        const val CACHE_WRITE_DEBOUNCE_MILLIS = 1_000L
        const val WIND_IDLE_PRELOAD_DELAY_MILLIS = 5_000L
        const val AUTO_REFRESH_MILLIS = 10 * 60 * 1_000L
    }

}
