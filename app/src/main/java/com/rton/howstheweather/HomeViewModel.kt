package com.rton.howstheweather

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rton.howstheweather.data.DemoWeatherDataSource
import com.rton.howstheweather.data.CwaWeatherDataSource
import com.rton.howstheweather.data.CwaSatelliteImageParser
import com.rton.howstheweather.data.CwaWithDemoFallbackDataSource
import com.rton.howstheweather.data.MoenvAirQualityDataSource
import com.rton.howstheweather.data.WeatherSnapshot
import com.rton.howstheweather.data.WeatherSnapshotCache
import com.rton.howstheweather.data.WeatherDataSource
import com.rton.howstheweather.data.ObservedWeatherTimeline
import com.rton.howstheweather.data.OBSERVATION_FRAME_INTERVAL_MINUTES
import com.rton.howstheweather.data.OBSERVATION_HISTORY_MINUTES
import com.rton.howstheweather.data.isObservedFrame
import kotlinx.coroutines.Job
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
import java.time.Instant
import java.util.concurrent.TimeUnit

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

class HomeViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val source: WeatherDataSource = if (BuildConfig.CWA_API_KEY.isNotBlank()) {
        CwaWithDemoFallbackDataSource(
            CwaWeatherDataSource(
                BuildConfig.CWA_API_KEY,
                satelliteImageParser = CwaSatelliteImageParser.withBundledEastAsiaProjection(
                    application.resources,
                ),
            ),
        )
    } else {
        DemoWeatherDataSource()
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
    private var areaForecastJob: Job? = null
    private var cacheWriteJob: Job? = null
    private val cacheWriteMutex = Mutex()
    private var cacheWriteGeneration = 0L
    private var refreshSequence = 0L
    private var cacheWasRead = false
    private var panelSelectionChanged = false
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
            destination = savedStateHandle.get<String>("destination")
                ?.let { runCatching { AppDestination.valueOf(it) }.getOrNull() }
                ?: AppDestination.NOW,
        ),
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        restorePanelAnchor()
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
            val persistedAnchor = runCatching {
                getApplication<Application>().userPreferencesDataStore.data.first()[PANEL_ANCHOR_KEY]
                    ?.let { value -> runCatching { PanelAnchor.valueOf(value) }.getOrNull() }
            }.getOrNull()
            if (persistedAnchor != null && !panelSelectionChanged) {
                savedStateHandle[PANEL_ANCHOR_STATE_KEY] = persistedAnchor.name
                _uiState.update { it.copy(panelAnchor = persistedAnchor) }
            }
        }
    }

    fun refresh() {
        val sequence = ++refreshSequence
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true) }
            try {
                val target = _uiState.value.target
                refreshLocationDetails(target.coordinate)
                if (!cacheWasRead) {
                    cacheWasRead = true
                    snapshotCache.read()?.let { cached ->
                        cachedSnapshot = cached
                        applySnapshot(cached)
                    }
                }

                runCatching { source.load(target.coordinate) }
                    .onSuccess { critical ->
                    val previous = snapshot ?: cachedSnapshot
                    if (critical.isDemo && previous?.isDemo == false && BuildConfig.CWA_API_KEY.isNotBlank()) {
                        applySnapshot(previous.copy(notice = critical.notice))
                        return@onSuccess
                    }
                    val immediatelyUsable = critical.copy(
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
                        quantitativeForecastFrames = critical.quantitativeForecastFrames
                            .ifEmpty { previous?.quantitativeForecastFrames.orEmpty() },
                        windGrid = previous?.windGrid,
                        winds = previous?.winds.orEmpty(),
                        windsAreDemo = previous?.windsAreDemo ?: critical.windsAreDemo,
                        windProvenance = previous?.windProvenance ?: critical.windProvenance,
                    )
                    applySnapshot(immediatelyUsable)
                    scheduleCacheWrite(immediatelyUsable)

                    runCatching {
                        source.enrichmentUpdates(immediatelyUsable, target.coordinate).collect { enriched ->
                            applySnapshot(enriched)
                            scheduleCacheWrite(enriched)
                        }
                    }
                        .onFailure { error ->
                            _uiState.update { it.copy(message = error.message ?: "補充圖層載入失敗") }
                        }
                    }
                    .onFailure { error ->
                        val cached = cachedSnapshot
                        if (cached != null) applySnapshot(cached.copy(notice = "CWA 載入失敗：${error.message ?: "未知錯誤"} · 顯示上次資料"))
                        else _uiState.update { it.copy(message = error.message ?: "資料載入失敗") }
                    }
            } finally {
                if (sequence == refreshSequence) {
                    _uiState.update { it.copy(isUpdating = false) }
                }
            }
        }
    }

    private fun scheduleCacheWrite(loaded: WeatherSnapshot) {
        if (loaded.isDemo) return
        val generation = ++cacheWriteGeneration
        cacheWriteJob?.cancel()
        cacheWriteJob = viewModelScope.launch {
            delay(CACHE_WRITE_DEBOUNCE_MILLIS)
            cacheWriteMutex.withLock {
                if (generation == cacheWriteGeneration) {
                    runCatching { snapshotCache.write(loaded) }
                }
            }
        }
    }

    private fun applySnapshot(loaded: WeatherSnapshot) {
        val targetPoint = _uiState.value.target.coordinate
        val applicableAreaForecast = loaded.areaForecast?.takeIf { it.target == targetPoint }
            ?: snapshot?.areaForecast?.takeIf { it.target == targetPoint }
        val resolved = loaded.copy(areaForecast = applicableAreaForecast)
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
                windGrid = resolved.windGrid,
                winds = resolved.winds,
                windsAreDemo = resolved.windsAreDemo,
                windProvenance = resolved.windProvenance,
                message = resolved.notice ?: if (resolved.isDemo) {
                    "示範數值格點 · 尚未設定 CWA_API_KEY"
                } else null,
            )
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    fun showMessage(message: String) = _uiState.update { it.copy(message = message) }

    fun selectTarget(point: GeoPoint, deviceLocation: Boolean = false) {
        val loaded = snapshot
        areaForecastJob?.cancel()
        snapshot = loaded?.copy(areaForecast = null)
        _uiState.update {
            it.copy(
                target = TargetLocation(point, if (deviceLocation) "目前位置" else "地圖選取位置", deviceLocation),
                decision = loaded?.let { data -> decisionFor(data, point) } ?: it.decision,
                areaForecast = null,
                areaForecastLoading = true,
                weeklyForecast = null,
                weeklyForecastLoading = true,
                airQuality = null,
                airQualityLoading = airQualitySource != null,
                airQualityUnavailableReason = if (airQualitySource == null) "尚未設定 MOENV_API_KEY" else null,
            )
        }
        // Radar, forecast, satellite, and station observations do not depend on
        // the selected target. Re-sample the existing numerical data instead of
        // re-downloading every CWA product after a locate or map long-press.
        if (loaded == null) {
            refresh()
        } else {
            refreshLocationDetails(point)
        }
    }

    private fun refreshLocationDetails(target: GeoPoint) {
        areaForecastJob?.cancel()
        _uiState.update {
            it.copy(
                areaForecastLoading = true,
                weeklyForecastLoading = true,
                airQualityLoading = airQualitySource != null,
                airQualityUnavailableReason = if (airQualitySource == null) "尚未設定 MOENV_API_KEY" else null,
            )
        }
        areaForecastJob = viewModelScope.launch {
            coroutineScope {
                val shortRequest = async { runCatching { source.loadAreaForecast(target) } }
                val weeklyRequest = async { runCatching { source.loadWeeklyForecast(target) } }
                val airRequest = async { airQualitySource?.let { runCatching { it.loadNearest(target) } } }
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
        val grid = gridFor(value, _uiState.value.layers.primary)
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

    fun setPrimaryLayer(layer: PrimaryLayer) = _uiState.update {
        val minute = it.selectedMinute
        val activeGrid = gridFor(minute, layer)
        val showDemoCloudNotice = layer == PrimaryLayer.CLOUD &&
            it.layers.primary != PrimaryLayer.CLOUD &&
            activeGrid?.sourceId?.startsWith("DEMO-") == true
        it.copy(
            layers = it.layers.copy(primary = layer),
            selectedMinute = minute,
            activeGrid = activeGrid,
            isPlaying = if (layer == PrimaryLayer.ONE_HOUR_RAIN) false else it.isPlaying,
            message = if (showDemoCloudNotice) {
                "示範雲層觀測動畫"
            } else {
                it.message
            },
        )
    }

    fun setMapViewport(center: GeoPoint, zoom: Float) = _uiState.update { state ->
        val coverage = resolveCloudCoverage(state.cloudCoverage, zoom, center, snapshot)
        val radarCoverage = resolveRadarCoverage(state.radarCoverage, zoom, center, snapshot)
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

    fun setWindEnabled(enabled: Boolean) = _uiState.update { it.copy(layers = it.layers.copy(windEnabled = enabled)) }
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

    fun scheduleReminder() {
        val decision = _uiState.value.decision
        val delayMinutes = decision.eventWindow?.last ?: return
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMinutes.toLong().coerceAtLeast(1), TimeUnit.MINUTES)
            .setInputData(
                Data.Builder()
                    .putString(ReminderWorker.KEY_TITLE, decision.headline)
                    .putString(ReminderWorker.KEY_ISSUED_AT, decision.issuedAt.toString())
                    .build(),
            ).build()
        WorkManager.getInstance(getApplication()).enqueue(request)
        _uiState.update { it.copy(reminderScheduled = true, message = "已依目前預報建立一次性提醒") }
    }

    private companion object {
        val PANEL_ANCHOR_KEY = stringPreferencesKey("panel_anchor")
        const val PANEL_ANCHOR_STATE_KEY = "panelAnchor"
        const val TIMELINE_END_MINUTE = 0
        const val CACHE_WRITE_DEBOUNCE_MILLIS = 1_000L
        const val AUTO_REFRESH_MILLIS = 10 * 60 * 1_000L
    }

}
