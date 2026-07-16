@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    com.google.maps.android.compose.MapsComposeExperimentalApi::class,
)

package com.rton.howstheweather.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.rton.howstheweather.BuildConfig
import com.rton.howstheweather.HomeViewModel
import com.rton.howstheweather.R
import com.rton.howstheweather.domain.DEFAULT_MAP_ZOOM
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.HomeUiState
import com.rton.howstheweather.domain.WeatherUnit
import com.rton.howstheweather.render.RenderTheme
import com.rton.howstheweather.render.WeatherRenderStyle
import com.rton.howstheweather.render.WeatherTileProvider
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter

@Composable
fun QuantitativePrecipitationScreen(
    state: HomeUiState,
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val grid = state.quantitativeForecastFrames.getOrNull(state.quantitativeForecastIndex)
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    var locating by remember { mutableStateOf(false) }
    val requestDeviceLocation = {
        if (!locating) {
            locating = true
            requestBestLocation(
                client = fused,
                onSuccess = { location ->
                    locating = false
                    viewModel.selectTarget(GeoPoint(location.latitude, location.longitude), true)
                },
                onFailure = { reason ->
                    locating = false
                    viewModel.showMessage(reason)
                },
            )
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) requestDeviceLocation()
        else viewModel.showMessage("需要位置權限才能回到目前位置")
    }
    val locateOrRequestPermission = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) requestDeviceLocation()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(state.target.coordinate.latitude, state.target.coordinate.longitude),
            DEFAULT_MAP_ZOOM,
        )
    }
    var mapLoaded by remember { mutableStateOf(false) }
    var mapLoadTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.isMoving }
            .filter { !it }
            .collect {
                val position = cameraState.position
                viewModel.setMapViewport(
                    GeoPoint(position.target.latitude, position.target.longitude),
                    position.zoom,
                )
            }
    }
    LaunchedEffect(mapLoaded) {
        if (!mapLoaded) {
            delay(8_000)
            if (!mapLoaded) mapLoadTimedOut = true
        } else mapLoadTimedOut = false
    }

    val darkMap = MaterialTheme.colorScheme.background.luminance() < .35f
    val renderTheme = if (darkMap) RenderTheme.DARK else RenderTheme.LIGHT
    val tileProvider = remember(grid, renderTheme) {
        grid?.let {
            val style = when (it.unit) {
                WeatherUnit.MILLIMETERS_ONE_HOUR -> WeatherRenderStyle.hourlyRain(renderTheme, 1f)
                WeatherUnit.MILLIMETERS_PER_HOUR -> WeatherRenderStyle.rain(renderTheme, 1f)
                WeatherUnit.MILLIMETERS_TWELVE_HOURS -> WeatherRenderStyle.twelveHourRain(renderTheme, 1f)
                else -> null
            }
            style?.let { renderStyle -> WeatherTileProvider(it, renderStyle) }
        }
    }
    val configuredMapId = BuildConfig.MAP_ID.trim()
    val usesBundledMapStyle = configuredMapId.isBlank() || configuredMapId == "DEMO_MAP_ID"
    val fallbackStyle = remember(darkMap, usesBundledMapStyle) {
        if (usesBundledMapStyle) {
            MapStyleOptions.loadRawResourceStyle(context, if (darkMap) R.raw.map_style_dark else R.raw.map_style_light)
        } else null
    }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            googleMapOptionsFactory = {
                GoogleMapOptions().apply { if (!usesBundledMapStyle) mapId(configuredMapId) }
            },
            properties = MapProperties(mapStyleOptions = fallbackStyle),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                rotationGesturesEnabled = false,
                tiltGesturesEnabled = false,
            ),
            onMapLongClick = { viewModel.selectTarget(GeoPoint(it.latitude, it.longitude)) },
            onMapLoaded = { mapLoaded = true },
        ) {
            MapEffect(state.target.coordinate, state.target.isDeviceLocation) { googleMap ->
                val current = googleMap.cameraPosition
                val targetZoom = if (state.target.isDeviceLocation) {
                    DEFAULT_MAP_ZOOM
                } else {
                    current.zoom.coerceAtLeast(10f)
                }
                if (abs(current.target.latitude - state.target.coordinate.latitude) > .00001 ||
                    abs(current.target.longitude - state.target.coordinate.longitude) > .00001 ||
                    abs(current.zoom - targetZoom) > .01f
                ) {
                    googleMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(state.target.coordinate.latitude, state.target.coordinate.longitude),
                            targetZoom,
                        ),
                        500,
                        null,
                    )
                }
            }
            if (mapLoaded && tileProvider != null) {
                TileOverlay(
                    tileProvider = tileProvider,
                    transparency = 1f - QUANTITATIVE_FORECAST_OPACITY,
                    fadeIn = true,
                    zIndex = 2f,
                )
            }
            Marker(
                state = rememberUpdatedMarkerState(
                    LatLng(state.target.coordinate.latitude, state.target.coordinate.longitude),
                ),
                title = state.target.displayName,
            )
        }

        QuantitativeRainSummary(
            state = state,
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
        )
        QuantitativeRainLegend(
            selectedIndex = state.quantitativeForecastIndex,
            onSelectedIndex = viewModel::setQuantitativeForecastIndex,
            enabled = state.quantitativeForecastFrames.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        )
        FloatingActionButton(
            onClick = locateOrRequestPermission,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp)
                .size(48.dp)
                .semantics { contentDescription = if (locating) "正在取得目前位置" else "回到目前位置" },
        ) {
            if (locating) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.MyLocation, "回到目前位置")
        }
        AnimatedVisibility(
            visible = grid != null && cameraState.position.zoom >= 11f,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 138.dp),
        ) {
            Text(
                "原始格點約 ${grid?.resolutionKm?.let { "%.2f".format(it) } ?: "—"} km",
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .92f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!mapLoaded) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .95f),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!mapLoadTimedOut) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (mapLoadTimedOut) "Google 地圖無法載入\n請確認 Maps SDK、計費與 Android 金鑰限制"
                        else "正在載入 Google 地圖…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuantitativeRainSummary(state: HomeUiState, modifier: Modifier = Modifier) {
    val grid = state.quantitativeForecastFrames.getOrNull(state.quantitativeForecastIndex)
    val formatter = remember {
        DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Taipei"))
    }
    val sampled = grid?.sample(state.target.coordinate)
    val amountLabel = when (grid?.unit) {
        WeatherUnit.MILLIMETERS_ONE_HOUR -> sampled?.let { "%.1f mm/1h".format(it) } ?: "此點無有效值"
        WeatherUnit.MILLIMETERS_PER_HOUR -> sampled?.let { "%.1f mm/h".format(it) } ?: "此點無有效值"
        WeatherUnit.MILLIMETERS_TWELVE_HOURS -> sampled?.let { "%.1f mm/12h".format(it) } ?: "此點無有效值"
        else -> "載入中"
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WaterDrop, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (grid?.unit) {
                        WeatherUnit.MILLIMETERS_ONE_HOUR -> "未來 1 小時 · 累積預報"
                        WeatherUnit.MILLIMETERS_PER_HOUR -> "即時雨率"
                        WeatherUnit.MILLIMETERS_TWELVE_HOURS -> {
                            val startHour = state.quantitativeForecastIndex * 12
                            "未來 $startHour–${startHour + 12} 小時 · 累積預報"
                        }
                        else -> "0–48 小時定量降水載入中"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    grid?.let { "${formatter.format(it.validAt)} · ${it.sourceId}" } ?: "等待數值格點",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(amountLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun QuantitativeRainLegend(
    selectedIndex: Int,
    onSelectedIndex: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = listOf(
        0xFF37D6E6, 0xFF4169D8, 0xFF6D3AC6, 0xFFB62B96, 0xFFE64A54, 0xFFF49A38, 0xFFFFF1B5,
    ).map(Long::toInt)
    val labels = listOf("0.1", "0.5", "2.5", "10", "25", "50", "100+")
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .95f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0–12", "12–24", "24–36", "36–48 小時").forEach { label ->
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
            Slider(
                value = selectedIndex.toFloat(),
                onValueChange = { onSelectedIndex(it.roundToInt()) },
                valueRange = 0f..3f,
                steps = 2,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "未來 0 到 48 小時定量降水預報時間軸" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.forEachIndexed { index, label ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(width = 30.dp, height = 7.dp).background(Color(colors[index]), RoundedCornerShape(2.dp)))
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private const val QUANTITATIVE_FORECAST_OPACITY = .78f
