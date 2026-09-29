package com.rton.howstheweather

import android.app.Application
import android.content.Context
import android.os.SystemClock
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
import com.rton.howstheweather.data.preserveDecisionForecastFrom
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
    private var historyJobKind: WeatherHistoryKind? = null
    private var windJob: Job? = null
    private var windIdlePreloadJob: Job? = null
    private var currentWeatherJob: Job? = null
    private var areaForecastJob: Job? = null
    private var cacheWriteJob: Job? = null
    private var playbackJob: Job? = null
    private var historyLoadFailure: Throwable? = null
    private var historyLoadFailureKind: WeatherHistoryKind? = null
    private var historyLoadingRequest: HistoryLoadingRequest? = null
    private val cacheWriteMutex = Mutex()
    private var cacheWriteGeneration = 0L
    private var refreshSequence = 0L
    private var cacheWasRead = false
    private var panelSelectionChanged = false
    private var windSelectionChanged = false
    private var requestedHistoryKind: WeatherHistoryKind? = null
    private val presentedWeatherFrame = MutableStateFlow<WeatherFramePresentationKey?>(null)
    private val initialTarget = TargetLocation(GeoPoint(25.0478, 121.5319), "臺北市中心", false)
    private val emptyDecision = decisionEngine.loadingHourly(Instant.now())
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
            mapCenter = GeoPoint(
                savedStateHandle.get<Double>(MAP_CENTER_LATITUDE_STATE_KEY) ?: initialTarget.coordinate.latitude,
                savedStateHandle.get<Double>(MAP_CENTER_LONGITUDE_STATE_KEY) ?: initialTarget.coordinate.longitude,
            ),
            mapZoom = savedStateHandle.get<Float>(MAP_ZOOM_STATE_KEY) ?: DEFAULT_MAP_ZOOM,
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
                    _uiState.update {
                        it.copy(
                            decision = decisionEngine.evaluateHourlyAccumulation(null, Instant.now()),
                            message = error.message ?: "資料載入失敗",
                        )
                    }
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
                    requestHistory(
                        WeatherHistoryKind.RADAR,
                        targetMinute = state.selectedMinute.takeUnless { state.isPlaying },
                    )
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
                // Location-specific forecasts answer the user's immediate question.
                // Keep cloud/precipitation enrichment out of the network queue until
                // those requests have completed.
                areaForecastJob?.join()
                source.enrichmentUpdates(base, target).collect { enriched ->
                    val supplemental = enriched.preserveDecisionForecastFrom(snapshot)
                    applySnapshot(supplemental)
                    scheduleCacheWrite(snapshot ?: supplemental)
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
        val applicableAreaForecast = areaForecastForTarget(
            target = targetPoint,
            incoming = loaded.areaForecast,
            previous = previous?.areaForecast,
            visible = _uiState.value.areaForecast,
        )
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
        updateHistoryLoadingState()
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
                areaForecastUnavailableReason = null,
                weeklyForecast = null,
                weeklyForecastLoading = true,
                weeklyForecastUnavailableReason = null,
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
                areaForecastUnavailableReason = null,
                weeklyForecastLoading = true,
                weeklyForecastUnavailableReason = null,
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
        areaForecastJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                coroutineScope {
                    // Start the short forecast immediately so it claims the first
                    // available CWA request slot. Weekly forecast and AQI may follow.
                    val shortRequest = async(start = CoroutineStart.UNDISPATCHED) {
                        catchingCancellable { loadAreaForecastWithRetry(target) }
                    }
                    val weeklyRequest = async { catchingCancellable { source.loadWeeklyForecast(target) } }
                    val airRequest = async {
                        airQualitySource?.let { source -> catchingCancellable { source.loadNearest(target) } }
                    }
                    val shortResult = shortRequest.await()
                    if (_uiState.value.target.coordinate != target) return@coroutineScope

                    val retainedShortForecast = _uiState.value.areaForecast
                        ?.takeIf { it.target == target && shortResult.isFailure }
                    val shortForecast = shortResult.getOrNull() ?: retainedShortForecast
                    val shortFailureReason = forecastUnavailableReason(
                        result = shortResult,
                        emptyMessage = "此位置暫無近期鄉鎮預報",
                        failurePrefix = "近期鄉鎮預報無法載入",
                    )
                    snapshot = snapshot?.copy(areaForecast = shortForecast)
                    _uiState.update { state ->
                        state.copy(
                            target = shortForecast?.let { forecast ->
                                state.target.copy(displayName = forecast.displayName)
                            } ?: state.target,
                            areaForecast = shortForecast,
                            areaForecastLoading = false,
                            areaForecastUnavailableReason = shortFailureReason.takeIf { shortForecast == null },
                            message = if (retainedShortForecast != null && shortFailureReason != null) {
                                "$shortFailureReason · 保留上次預報"
                            } else {
                                state.message
                            },
                        )
                    }

                    val weeklyResult = weeklyRequest.await()
                    val airResult = airRequest.await()
                    if (_uiState.value.target.coordinate != target) return@coroutineScope
                    val retainedWeeklyForecast = _uiState.value.weeklyForecast
                        ?.takeIf { it.target == target && weeklyResult.isFailure }
                    val weeklyForecast = weeklyResult.getOrNull() ?: retainedWeeklyForecast
                    val weeklyFailureReason = forecastUnavailableReason(
                        result = weeklyResult,
                        emptyMessage = "此位置暫無一週鄉鎮預報",
                        failurePrefix = "一週鄉鎮預報無法載入",
                    )
                    _uiState.update { state ->
                        state.copy(
                            weeklyForecast = weeklyForecast,
                            weeklyForecastLoading = false,
                            weeklyForecastUnavailableReason = weeklyFailureReason.takeIf { weeklyForecast == null },
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
                            message = if (retainedWeeklyForecast != null && weeklyFailureReason != null) {
                                "$weeklyFailureReason · 保留上次預報"
                            } else {
                                state.message
                            },
                        )
                    }
                }
            } finally {
                if (areaForecastJob === coroutineContext[Job]) {
                    areaForecastJob = null
                    startRequestedHistoryIfReady()
                }
            }
        }
    }

    private suspend fun loadAreaForecastWithRetry(target: GeoPoint): AreaForecast? {
        var firstFailure: Throwable? = null
        repeat(AREA_FORECAST_ATTEMPTS) { attempt ->
            try {
                return source.loadAreaForecast(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                if (attempt < AREA_FORECAST_ATTEMPTS - 1) delay(AREA_FORECAST_RETRY_DELAY_MILLIS)
            }
        }
        val finalFailure = checkNotNull(firstFailure)
        throw IllegalStateException(finalFailure.message ?: "近期鄉鎮預報載入失敗", finalFailure)
    }

    private fun forecastUnavailableReason(
        result: Result<AreaForecast?>,
        emptyMessage: String,
        failurePrefix: String,
    ): String? = when {
        result.isFailure -> {
            val error = result.exceptionOrNull()
            val detail = error?.message?.takeIf(String::isNotBlank)
                ?: error?.javaClass?.simpleName
                ?: "未知錯誤"
            "$failurePrefix：$detail"
        }
        result.getOrNull() == null -> emptyMessage
        else -> null
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
            val hourlyAmount = hourlyGrid.sample(point)
                ?: return decisionEngine.unavailableHourlyAtTarget(loaded.issuedAt)
            return decisionEngine.evaluateHourlyAccumulation(hourlyAmount, loaded.issuedAt)
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
                PrimaryLayer.RADAR_RAIN -> requestHistory(WeatherHistoryKind.RADAR, targetMinute = value)
                PrimaryLayer.CLOUD -> requestHistory(WeatherHistoryKind.CLOUD, targetMinute = value)
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
        if (_uiState.value.isPlaybackPending) {
            playbackJob?.cancel()
            playbackJob = null
            _uiState.update { it.copy(isPlaybackPending = false) }
        }
        if (layer == PrimaryLayer.ONE_HOUR_RAIN) playbackJob?.cancel()
        val minute = _uiState.value.selectedMinute
        if (minute < 0) {
            when (layer) {
                PrimaryLayer.RADAR_RAIN -> requestHistory(WeatherHistoryKind.RADAR, targetMinute = minute)
                PrimaryLayer.CLOUD -> requestHistory(WeatherHistoryKind.CLOUD, targetMinute = minute)
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
        savedStateHandle[MAP_CENTER_LATITUDE_STATE_KEY] = center.latitude
        savedStateHandle[MAP_CENTER_LONGITUDE_STATE_KEY] = center.longitude
        savedStateHandle[MAP_ZOOM_STATE_KEY] = zoom
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
            requestHistory(
                WeatherHistoryKind.RADAR,
                targetMinute = previous.selectedMinute.takeUnless { previous.isPlaying },
            )
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
            areaForecastJob?.join()
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

    private fun requestHistory(kind: WeatherHistoryKind, targetMinute: Int? = null) {
        historyLoadFailure = null
        historyLoadFailureKind = null
        val requested = HistoryLoadingRequest(kind, targetMinute)
        val existing = historyLoadingRequest
        historyLoadingRequest = when {
            existing?.kind == kind && existing.targetMinute == null -> existing
            requested.targetMinute == null -> requested
            else -> requested
        }
        if (isHistoryRequestReady(checkNotNull(historyLoadingRequest))) {
            historyLoadingRequest = null
            _uiState.update { it.copy(isHistoryLoading = false) }
            return
        }
        _uiState.update { it.copy(isHistoryLoading = true) }
        if (historyJob?.isActive == true && historyJobKind == kind) {
            return
        }
        requestedHistoryKind = kind
        if (kind == WeatherHistoryKind.RADAR) enrichmentJob?.cancel()
        startRequestedHistoryIfReady()
    }

    private fun isHistoryFullyLoaded(kind: WeatherHistoryKind): Boolean {
        val current = snapshot ?: return false
        val frameCount = when (kind) {
            WeatherHistoryKind.RADAR -> activeRadarHistoryFrames(current).size
            WeatherHistoryKind.CLOUD -> activeCloudHistoryFrames(current).size
        }
        return hasCompleteHistoryForPlayback(kind, frameCount)
    }

    private fun isHistoryMinuteLoaded(kind: WeatherHistoryKind, minute: Int): Boolean {
        val current = snapshot ?: return false
        val frames = when (kind) {
            WeatherHistoryKind.RADAR -> {
                val currentRadar = if (_uiState.value.radarCoverage == RadarCoverage.LOCAL) {
                    current.radarRegional ?: current.radar
                } else {
                    current.radar
                }
                activeRadarHistoryFrames(current) + currentRadar
            }
            WeatherHistoryKind.CLOUD -> activeCloudHistoryFrames(current)
        }
        val referenceAt = frames.maxOfOrNull(WeatherGrid::validAt) ?: return false
        return isObservationMinuteAvailable(referenceAt, frames.map(WeatherGrid::validAt), minute)
    }

    private fun isHistoryRequestReady(request: HistoryLoadingRequest): Boolean =
        request.targetMinute?.let { isHistoryMinuteLoaded(request.kind, it) }
            ?: isHistoryFullyLoaded(request.kind)

    private fun updateHistoryLoadingState() {
        val request = historyLoadingRequest
        if (request == null) {
            _uiState.update { it.copy(isHistoryLoading = false) }
            return
        }
        if (isHistoryRequestReady(request)) {
            if (historyJob?.isActive != true && requestedHistoryKind == request.kind) {
                requestedHistoryKind = null
            }
            historyLoadingRequest = null
            _uiState.update { it.copy(isHistoryLoading = false) }
            return
        }
        val requestStillActive =
            (historyJob?.isActive == true && historyJobKind == request.kind) ||
                requestedHistoryKind == request.kind
        val requestFailed = historyLoadFailureKind == request.kind
        if (!requestStillActive || requestFailed) {
            historyLoadingRequest = null
            _uiState.update { it.copy(isHistoryLoading = false) }
        } else {
            _uiState.update { it.copy(isHistoryLoading = true) }
        }
    }

    private fun activeRadarHistoryFrames(current: WeatherSnapshot): List<WeatherGrid> =
        if (_uiState.value.radarCoverage == RadarCoverage.LOCAL) {
            current.radarRegionalFrames
        } else {
            current.radarFrames
        }

    private fun activeCloudHistoryFrames(current: WeatherSnapshot): List<WeatherGrid> =
        if (_uiState.value.cloudCoverage == CloudCoverage.TAIWAN &&
            current.cloudRegionalFrames.isNotEmpty()
        ) {
            current.cloudRegionalFrames
        } else {
            current.cloudFrames
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
        if (areaForecastJob?.isActive == true) return
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
        if (isHistoryFullyLoaded(kind)) {
            requestedHistoryKind = null
            if (historyLoadingRequest?.kind == kind) historyLoadingRequest = null
            _uiState.update { it.copy(isHistoryLoading = false) }
            return
        }
        if (kind == WeatherHistoryKind.CLOUD &&
            current.cloudFrames.isEmpty() && current.cloudRegionalFrames.isEmpty()
        ) return
        requestedHistoryKind = null
        historyJobKind = kind
        historyJob = viewModelScope.launch {
            try {
                historyLoadFailure = null
                historyLoadFailureKind = null
                source.historyUpdates(current, kind).collect { loaded ->
                    val supplemental = loaded.preserveDecisionForecastFrom(snapshot)
                    applySnapshot(supplemental)
                    scheduleCacheWrite(snapshot ?: supplemental)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                historyLoadFailure = error
                historyLoadFailureKind = kind
                _uiState.update { it.copy(message = error.message ?: "歷史觀測載入失敗") }
            } finally {
                if (historyJob === coroutineContext[Job]) {
                    historyJob = null
                    historyJobKind = null
                    startRequestedHistoryIfReady()
                    updateHistoryLoadingState()
                }
            }
        }
    }

    private suspend fun awaitHistoryForPlayback(kind: WeatherHistoryKind): Boolean {
        if (isHistoryFullyLoaded(kind)) return true
        requestHistory(kind, targetMinute = null)
        while (_uiState.value.isPlaybackPending) {
            if (isHistoryFullyLoaded(kind)) {
                updateHistoryLoadingState()
                return true
            }
            startRequestedHistoryIfReady()
            val sameKindActive = historyJob?.isActive == true && historyJobKind == kind
            val sameKindQueued = requestedHistoryKind == kind
            if (!sameKindActive && !sameKindQueued) return false
            if (historyLoadFailureKind == kind) return false
            if (snapshot == null && refreshJob?.isActive != true) return false
            delay(HISTORY_WAIT_POLL_MILLIS)
        }
        return false
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

    /** Called only after the map's back buffer has finished blending into view. */
    fun onWeatherFramePresented(grid: WeatherGrid) {
        presentedWeatherFrame.value = grid.presentationKey()
    }

    private suspend fun awaitActiveWeatherFramePresented(): Boolean {
        val expected = _uiState.value.activeGrid?.presentationKey() ?: return false
        if (presentedWeatherFrame.value == expected) return true
        return withTimeoutOrNull(WEATHER_FRAME_PRESENT_TIMEOUT_MILLIS) {
            presentedWeatherFrame.first { it == expected }
            true
        } ?: false
    }

    fun togglePlayback() {
        val current = _uiState.value
        if (current.isPlaying || current.isPlaybackPending) {
            playbackJob?.cancel()
            playbackJob = null
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    isPlaybackPending = false,
                )
            }
            return
        }
        _uiState.update { it.copy(isPlaybackPending = true) }
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            val historyKind = when (_uiState.value.layers.primary) {
                PrimaryLayer.RADAR_RAIN -> WeatherHistoryKind.RADAR
                PrimaryLayer.CLOUD -> WeatherHistoryKind.CLOUD
                PrimaryLayer.ONE_HOUR_RAIN -> null
            }
            if (historyKind == null) {
                _uiState.update { it.copy(isPlaybackPending = false) }
                return@launch
            }
            val historyReady = awaitHistoryForPlayback(historyKind)
            if (!_uiState.value.isPlaybackPending) return@launch
            if (!historyReady) {
                _uiState.update {
                    it.copy(
                        isPlaying = false,
                        isPlaybackPending = false,
                        message = it.message ?: "歷史觀測資料不足，暫時無法播放",
                    )
                }
                return@launch
            }
            // Playback is observation-only: move chronologically from history to now.
            if (_uiState.value.selectedMinute >= 0) setMinute(-OBSERVATION_HISTORY_MINUTES)
            if (!awaitActiveWeatherFramePresented()) {
                _uiState.update {
                    it.copy(
                        isPlaying = false,
                        isPlaybackPending = false,
                        message = "天氣圖層準備逾時，請稍後再試",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isPlaybackPending = false,
                    isPlaying = true,
                )
            }
            var nextFrameAt = SystemClock.elapsedRealtime() + PLAYBACK_FRAME_DURATION_MILLIS
            while (_uiState.value.isPlaying) {
                val remainingBeforeFrame = nextFrameAt - SystemClock.elapsedRealtime()
                if (remainingBeforeFrame > 0L) delay(remainingBeforeFrame)
                if (!_uiState.value.isPlaying) break
                val nextMinute = nextObservationFrameMinute(_uiState.value.selectedMinute)
                val transitionStartedAt = SystemClock.elapsedRealtime()
                setMinute(nextMinute)
                if (!awaitActiveWeatherFramePresented()) {
                    _uiState.update {
                        it.copy(
                            isPlaying = false,
                            isPlaybackPending = false,
                            message = "天氣圖層準備逾時，播放已停止",
                        )
                    }
                    break
                }
                nextFrameAt = transitionStartedAt + PLAYBACK_FRAME_DURATION_MILLIS
                if (nextMinute >= TIMELINE_END_MINUTE) {
                    // Animation and frame timing share one deadline. Only hold for
                    // the part of the one-second cadence that remains after rendering.
                    val remainingFinalFrame = nextFrameAt - SystemClock.elapsedRealtime()
                    if (remainingFinalFrame > 0L) delay(remainingFinalFrame)
                    _uiState.update { it.copy(isPlaying = false) }
                    break
                }
            }
        }
    }

    private companion object {
        val PANEL_ANCHOR_KEY = stringPreferencesKey("panel_anchor")
        val WIND_ENABLED_KEY = booleanPreferencesKey("wind_enabled")
        const val PANEL_ANCHOR_STATE_KEY = "panelAnchor"
        const val WIND_ENABLED_STATE_KEY = "windEnabled"
        const val MAP_CENTER_LATITUDE_STATE_KEY = "mapCenterLatitude"
        const val MAP_CENTER_LONGITUDE_STATE_KEY = "mapCenterLongitude"
        const val MAP_ZOOM_STATE_KEY = "mapZoom"
        const val TIMELINE_END_MINUTE = 0
        const val PLAYBACK_FRAME_DURATION_MILLIS = 1_000L
        const val WEATHER_FRAME_PRESENT_TIMEOUT_MILLIS = 15_000L
        const val HISTORY_WAIT_POLL_MILLIS = 50L
        const val CURRENT_WEATHER_TIMEOUT_MILLIS = 10_000L
        const val CRITICAL_LOAD_TIMEOUT_MILLIS = 15_000L
        const val AREA_FORECAST_ATTEMPTS = 2
        const val AREA_FORECAST_RETRY_DELAY_MILLIS = 400L
        const val CACHE_WRITE_DEBOUNCE_MILLIS = 1_000L
        const val WIND_IDLE_PRELOAD_DELAY_MILLIS = 5_000L
        const val AUTO_REFRESH_MILLIS = 10 * 60 * 1_000L
    }

}

private data class WeatherFramePresentationKey(
    val width: Int,
    val height: Int,
    val unit: WeatherUnit,
    val bounds: GeoBounds,
    val validAt: Instant,
    val sourceId: String,
)

private fun WeatherGrid.presentationKey() = WeatherFramePresentationKey(
    width = width,
    height = height,
    unit = unit,
    bounds = bounds,
    validAt = validAt,
    sourceId = sourceId,
)

/**
 * A location forecast can finish before the first radar snapshot exists. In that
 * startup race it lives only in [HomeUiState], so later snapshot updates must keep
 * the already-visible result for the same target instead of replacing it with null.
 */
internal fun areaForecastForTarget(
    target: GeoPoint,
    incoming: AreaForecast?,
    previous: AreaForecast?,
    visible: AreaForecast?,
): AreaForecast? = sequenceOf(incoming, previous, visible)
    .filterNotNull()
    .firstOrNull { it.target == target }

internal fun nextObservationFrameMinute(minute: Int): Int =
    ((Math.floorDiv(minute, OBSERVATION_FRAME_INTERVAL_MINUTES) + 1) *
        OBSERVATION_FRAME_INTERVAL_MINUTES).coerceAtMost(0)

internal fun hasCompleteHistoryForPlayback(kind: WeatherHistoryKind, frameCount: Int): Boolean =
    when (kind) {
        WeatherHistoryKind.RADAR -> frameCount >= OBSERVATION_HISTORY_FRAME_COUNT - 1
        WeatherHistoryKind.CLOUD -> frameCount >= OBSERVATION_HISTORY_FRAME_COUNT
    }

internal fun isObservationMinuteAvailable(
    referenceAt: Instant,
    frameTimes: List<Instant>,
    minute: Int,
): Boolean {
    val targetAt = referenceAt.plusSeconds(minute * 60L)
    return frameTimes.any {
        kotlin.math.abs(java.time.Duration.between(it, targetAt).seconds) <=
            OBSERVATION_TARGET_TOLERANCE_SECONDS
    }
}

private data class HistoryLoadingRequest(
    val kind: WeatherHistoryKind,
    val targetMinute: Int?,
)

private const val OBSERVATION_TARGET_TOLERANCE_SECONDS = 60L
