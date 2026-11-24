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
    onPrimaryContainer = Color(0xFFB8F3FF),
    secondary = Color(0xFFB8C8D1),
    onSecondary = Color(0xFF1A2C36),
    secondaryContainer = Color(0xFF143542),
    onSecondaryContainer = Color(0xFFD2ECF5),
    background = Color(0xFF071018),
    surface = Color(0xFF0D1B24),
    surfaceVariant = Color(0xFF142630),
    surfaceContainerLowest = Color(0xFF071018),
    surfaceContainerLow = Color(0xFF0A161E),
    surfaceContainer = Color(0xFF0D1B24),
    surfaceContainerHigh = Color(0xFF12222C),
    surfaceContainerHighest = Color(0xFF172B37),
    onBackground = Color(0xFFE6F1F5),
    onSurface = Color(0xFFE6F1F5),
    onSurfaceVariant = Color(0xFFA7B8C2),
    outline = Color(0xFF71858F),
    outlineVariant = Color(0xFF233844),
    error = Color(0xFFFFB4AB),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006B75),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA3EEF5),
    onPrimaryContainer = Color(0xFF002024),
    secondary = Color(0xFF516064),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E9EC),
    onSecondaryContainer = Color(0xFF003B42),
    background = Color(0xFFF6F4EF),
    surface = Color(0xFFFCFAF5),
    surfaceVariant = Color(0xFFE8E5DE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F7F2),
    surfaceContainer = Color(0xFFFCFAF5),
    surfaceContainerHigh = Color(0xFFF1EEE8),
    surfaceContainerHighest = Color(0xFFE8E5DE),
    onBackground = Color(0xFF1C252B),
    onSurface = Color(0xFF1C252B),
    onSurfaceVariant = Color(0xFF4F5D63),
    outline = Color(0xFF6F797C),
    outlineVariant = Color(0xFFD6D2C9),
    error = Color(0xFFBA1A1A),
)

@Composable
fun isWeatherDarkTheme(preference: ThemePreference): Boolean = when (preference) {
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
    ThemePreference.DARK -> true
    ThemePreference.LIGHT -> false
}

@Composable
fun WeatherTheme(preference: ThemePreference, content: @Composable () -> Unit) {
    val dark = isWeatherDarkTheme(preference)
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
