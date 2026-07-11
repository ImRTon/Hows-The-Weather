package com.rton.howstheweather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rton.howstheweather.ui.HomeScreen
import com.rton.howstheweather.ui.theme.WeatherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: HomeViewModel = viewModel()
            val state = viewModel.uiState.collectAsStateWithLifecycle().value
            WeatherTheme(state.themePreference) {
                HomeScreen(state = state, viewModel = viewModel)
            }
        }
    }
}
