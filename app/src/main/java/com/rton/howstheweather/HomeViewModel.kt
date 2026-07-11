package com.rton.howstheweather

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rton.howstheweather.data.DemoWeatherDataSource
import com.rton.howstheweather.data.CwaWeatherDataSource
import com.rton.howstheweather.data.CwaWithDemoFallbackDataSource
import com.rton.howstheweather.data.WeatherSnapshot
import com.rton.howstheweather.data.WeatherDataSource
import com.rton.howstheweather.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

class HomeViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val source: WeatherDataSource = if (BuildConfig.CWA_API_KEY.isNotBlank()) {
        CwaWithDemoFallbackDataSource(CwaWeatherDataSource(BuildConfig.CWA_API_KEY))
    } else {
        DemoWeatherDataSource()
    }
    private val decisionEngine = ForecastDecisionEngine()
    private var snapshot: WeatherSnapshot? = null
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
        ),
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val target = _uiState.value.target
        runCatching { source.load(target.coordinate) }
            .onSuccess { loaded ->
                snapshot = loaded
                _uiState.update {
                    it.copy(
                        decision = if (loaded.hourlyAccumulationAtTarget != null) {
                            decisionEngine.evaluateHourlyAccumulation(loaded.hourlyAccumulationAtTarget, loaded.issuedAt)
                        } else {
                            decisionEngine.evaluate(loaded.forecastAtTarget, loaded.issuedAt)
                        },
                        activeGrid = loaded.radar,
                        winds = loaded.winds,
                        message = loaded.notice ?: if (loaded.isDemo) {
                            "示範數值格點 · 尚未設定 CWA_API_KEY"
                        } else null,
                    )
                }
            }
            .onFailure { error -> _uiState.update { it.copy(message = error.message ?: "資料載入失敗") } }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    fun selectTarget(point: GeoPoint, deviceLocation: Boolean = false) {
        _uiState.update {
            it.copy(target = TargetLocation(point, if (deviceLocation) "目前位置" else "地圖選取位置", deviceLocation))
        }
        refresh()
    }

    fun setMinute(minute: Int) {
        val value = minute.coerceIn(0, 60)
        val grid = gridFor(value, _uiState.value.layers.primary)
        _uiState.update { it.copy(selectedMinute = value, activeGrid = grid) }
    }

    private fun gridFor(minute: Int, layer: PrimaryLayer): WeatherGrid? {
        val loaded = snapshot ?: return null
        if (layer == PrimaryLayer.CLOUD) return loaded.cloudFrames.minByOrNull {
            kotlin.math.abs(java.time.Duration.between(loaded.issuedAt, it.validAt).toMinutes().toInt() - minute)
        }
        return if (minute == 0) loaded.radar else loaded.rainForecast.minByOrNull {
            kotlin.math.abs(java.time.Duration.between(loaded.issuedAt, it.validAt).toMinutes().toInt() - minute)
        }
    }

    fun setPanel(anchor: PanelAnchor) {
        savedStateHandle["panelAnchor"] = anchor.name
        _uiState.update { it.copy(panelAnchor = anchor) }
    }

    fun cyclePanel() {
        setPanel(when (_uiState.value.panelAnchor) {
            PanelAnchor.DECISION -> PanelAnchor.BALANCED
            PanelAnchor.BALANCED -> PanelAnchor.MAP
            PanelAnchor.MAP -> PanelAnchor.DECISION
        })
    }

    fun setPrimaryLayer(layer: PrimaryLayer) = _uiState.update {
        it.copy(layers = it.layers.copy(primary = layer), activeGrid = gridFor(it.selectedMinute, layer))
    }
    fun setWindEnabled(enabled: Boolean) = _uiState.update { it.copy(layers = it.layers.copy(windEnabled = enabled)) }
    fun setOpacity(value: Float) = _uiState.update { it.copy(layers = it.layers.copy(opacity = value)) }
    fun toggleLegend() = _uiState.update { it.copy(legendExpanded = !it.legendExpanded) }
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
            while (_uiState.value.isPlaying) {
                delay(800)
                val next = (_uiState.value.selectedMinute + 10).let { if (it > 60) 0 else it }
                setMinute(next)
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
}
