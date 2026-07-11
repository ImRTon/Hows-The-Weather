package com.rton.howstheweather.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.rton.howstheweather.domain.ThemePreference

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5BE7FF),
    onPrimary = Color(0xFF002B32),
    primaryContainer = Color(0xFF0C4652),
    secondary = Color(0xFFB8C8D1),
    background = Color(0xFF071018),
    surface = Color(0xFF0D1B24),
    surfaceVariant = Color(0xFF142630),
    onBackground = Color(0xFFE6F1F5),
    onSurface = Color(0xFFE6F1F5),
    outline = Color(0xFF71858F),
    error = Color(0xFFFFB4AB),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006B75),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA3EEF5),
    secondary = Color(0xFF516064),
    background = Color(0xFFF6F4EF),
    surface = Color(0xFFFCFAF5),
    surfaceVariant = Color(0xFFE8E5DE),
    onBackground = Color(0xFF1C252B),
    onSurface = Color(0xFF1C252B),
    outline = Color(0xFF6F797C),
    error = Color(0xFFBA1A1A),
)

@Composable
fun WeatherTheme(preference: ThemePreference, content: @Composable () -> Unit) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
