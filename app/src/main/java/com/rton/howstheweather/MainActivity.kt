package com.rton.howstheweather

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rton.howstheweather.ui.HomeScreen
import com.rton.howstheweather.ui.theme.WeatherTheme
import com.rton.howstheweather.diagnostics.StartupDiagnostics

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "performance") {
            StartupDiagnostics.install { Log.d(StartupDiagnostics.TAG, it) }
        }
        StartupDiagnostics.event("activity.create restored=${savedInstanceState != null}")
        super.onCreate(savedInstanceState)
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
