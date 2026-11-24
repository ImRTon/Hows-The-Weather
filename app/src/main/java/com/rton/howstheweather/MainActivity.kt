package com.rton.howstheweather

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rton.howstheweather.ui.HomeScreen
import com.rton.howstheweather.ui.theme.WeatherTheme
import com.rton.howstheweather.ui.theme.isWeatherDarkTheme
import com.rton.howstheweather.diagnostics.StartupDiagnostics
import com.rton.howstheweather.widget.WeatherDesktopWidgetProvider
import com.rton.howstheweather.widget.WidgetHaptics
import com.rton.howstheweather.widget.WidgetWeatherStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "performance") {
            StartupDiagnostics.install { Log.d(StartupDiagnostics.TAG, it) }
        }
        StartupDiagnostics.event("activity.create restored=${savedInstanceState != null}")
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(WeatherDesktopWidgetProvider.EXTRA_FROM_WIDGET, false) == true) {
            WidgetHaptics.performClick(this)
            intent?.removeExtra(WeatherDesktopWidgetProvider.EXTRA_FROM_WIDGET)
        }
        enableEdgeToEdge()
        setContent {
            val viewModelStarted = StartupDiagnostics.begin("viewModel.obtain")
            val viewModel: HomeViewModel = viewModel()
            StartupDiagnostics.end("viewModel.obtain", viewModelStarted)
            LifecycleStartEffect(viewModel) {
                viewModel.onAppForeground()
                onStopOrDispose { viewModel.onAppBackground() }
            }
            val state = viewModel.uiState.collectAsStateWithLifecycle().value
            LaunchedEffect(
                state.target,
                state.currentWeather,
                state.areaForecast,
                state.weeklyForecast,
                state.decision,
            ) {
                withContext(Dispatchers.IO) {
                    WidgetWeatherStore.syncFromUiState(applicationContext, state)
                }
            }
            val darkTheme = isWeatherDarkTheme(state.themePreference)
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
                onDispose {}
            }
            WeatherTheme(state.themePreference) {
                HomeScreen(state = state, viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        StartupDiagnostics.event("activity.start")
    }

    override fun onStop() {
        StartupDiagnostics.event("activity.stop")
        super.onStop()
    }
}
