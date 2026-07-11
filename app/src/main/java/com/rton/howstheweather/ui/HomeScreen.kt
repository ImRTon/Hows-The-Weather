@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.rton.howstheweather.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.*
import com.rton.howstheweather.BuildConfig
import com.rton.howstheweather.HomeViewModel
import com.rton.howstheweather.R
import com.rton.howstheweather.domain.*
import com.rton.howstheweather.render.RenderTheme
import com.rton.howstheweather.render.WeatherRenderStyle
import com.rton.howstheweather.render.WeatherTileProvider
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun HomeScreen(state: HomeUiState, viewModel: HomeViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it, withDismissAction = true)
            viewModel.clearMessage()
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.scheduleReminder()
    }
    val scheduleReminder = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else viewModel.scheduleReminder()
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { AppTopBar(state, viewModel) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        ResizableWeatherPanels(
            state = state,
            viewModel = viewModel,
            onReminder = scheduleReminder,
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}

@Composable
private fun AppTopBar(state: HomeUiState, viewModel: HomeViewModel) {
    TopAppBar(
        title = {
            Column {
                Text("HOW’S THE WEATHER", fontSize = 13.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                Text(state.target.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = {
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, "重新整理") }
            IconButton(onClick = viewModel::cycleTheme) { Icon(Icons.Default.Contrast, "切換亮暗主題") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun ResizableWeatherPanels(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onReminder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val totalHeight = maxHeight
        val handleHeight = 48.dp
        val minCard = 112.dp
        val minMap = 180.dp
        fun fraction(anchor: PanelAnchor) = when (anchor) {
            PanelAnchor.DECISION -> 0.68f
            PanelAnchor.BALANCED -> 0.40f
            PanelAnchor.MAP -> minCard.value / totalHeight.value
        }
        var dragFraction by remember(state.panelAnchor, totalHeight) { mutableFloatStateOf(fraction(state.panelAnchor)) }
        var dragging by remember { mutableStateOf(false) }
        val targetHeight = (totalHeight * dragFraction).coerceIn(minCard, totalHeight - minMap - handleHeight)
        val animatedHeight by animateDpAsState(targetValue = targetHeight, label = "decision-panel")

        Column(Modifier.fillMaxSize()) {
            DecisionPanel(
                state = state,
                onReminder = onReminder,
                modifier = Modifier.fillMaxWidth().height(if (dragging) targetHeight else animatedHeight),
            )
            PanelHandle(
                onClick = viewModel::cyclePanel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(handleHeight)
                    .pointerInput(totalHeight) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true },
                            onVerticalDrag = { _, amount ->
                                val delta = with(density) { amount.toDp().value / totalHeight.value }
                                val min = minCard.value / totalHeight.value
                                val max = 1f - (minMap + handleHeight).value / totalHeight.value
                                dragFraction = (dragFraction + delta).coerceIn(min, max)
                            },
                            onDragEnd = {
                                dragging = false
                                val nearest = PanelAnchor.entries.minBy { abs(fraction(it) - dragFraction) }
                                viewModel.setPanel(nearest)
                            },
                            onDragCancel = { dragging = false },
                        )
                    },
            )
            WeatherMap(state, viewModel, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun PanelHandle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics {
            role = Role.Button
            contentDescription = "調整決策卡與地圖大小"
            onClick("切換面板大小") { onClick(); true }
        },
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(width = 52.dp, height = 5.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = .65f)),
            )
        }
    }
}

@Composable
private fun DecisionPanel(state: HomeUiState, onReminder: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when (state.activeGrid?.unit) {
                                WeatherUnit.MILLIMETERS_ONE_HOUR -> "未來 1 小時 · 累積預報"
                                WeatherUnit.LUMINANCE -> "+${state.selectedMinute} 分 · 實驗推估"
                                else -> if (state.selectedMinute == 0) "現在 · 觀測" else "+${state.selectedMinute} 分 · 預報"
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Schedule, null, Modifier.size(17.dp)) },
                )
                Spacer(Modifier.weight(1f))
                DataFreshness(state)
            }
            Text(
                state.decision.headline,
                style = if (state.panelAnchor == PanelAnchor.MAP) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.panelAnchor != PanelAnchor.MAP) {
                Text(state.decision.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ForecastChart(state.decision.series, Modifier.fillMaxWidth().weight(1f).heightIn(min = 72.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WaterDrop, null, tint = rainStateColor(state.decision.state), modifier = Modifier.size(18.dp))
                    Text("  ${rainStateLabel(state.decision.state)}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onReminder,
                        enabled = state.decision.eventWindow != null && !state.reminderScheduled,
                    ) {
                        Icon(if (state.reminderScheduled) Icons.Default.NotificationsActive else Icons.Default.AddAlert, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.reminderScheduled) "已設定" else "到時提醒我")
                    }
                }
            }
        }
    }
}

@Composable
private fun DataFreshness(state: HomeUiState) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    Text(
        "更新 ${formatter.format(state.decision.issuedAt)}",
        style = MaterialTheme.typography.labelSmall,
        color = if (state.decision.isStale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ForecastChart(points: List<ForecastPoint>, modifier: Modifier = Modifier) {
    val values = points.mapNotNull { it.millimetersPerHour }
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))) {
        if (points.size < 2) return@Canvas
        val step = size.width / (points.size - 1)
        points.zipWithNext().forEachIndexed { index, pair ->
            val a = pair.first.millimetersPerHour ?: 0f
            val b = pair.second.millimetersPerHour ?: 0f
            drawLine(
                color = Color(0xFFB62B96),
                start = androidx.compose.ui.geometry.Offset(index * step, size.height - (a / max * size.height * .82f)),
                end = androidx.compose.ui.geometry.Offset((index + 1) * step, size.height - (b / max * size.height * .82f)),
                strokeWidth = 6f,
            )
        }
    }
}

@Composable
private fun WeatherMap(state: HomeUiState, viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it } && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fused.lastLocation.addOnSuccessListener { location ->
                location?.let { viewModel.selectTarget(GeoPoint(it.latitude, it.longitude), true) }
            }
        }
    }
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(state.target.coordinate.latitude, state.target.coordinate.longitude), 8f)
    }
    val darkMap = MaterialTheme.colorScheme.background.luminance() < .35f
    val fallbackStyle = remember(darkMap) {
        if (BuildConfig.MAP_ID == "DEMO_MAP_ID") {
            MapStyleOptions.loadRawResourceStyle(context, if (darkMap) R.raw.map_style_dark else R.raw.map_style_light)
        } else null
    }
    var mapLoaded by remember { mutableStateOf(false) }
    var mapLoadTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(mapLoaded) {
        if (!mapLoaded) {
            delay(8_000)
            if (!mapLoaded) mapLoadTimedOut = true
        } else {
            mapLoadTimedOut = false
        }
    }
    val renderTheme = if (darkMap) RenderTheme.DARK else RenderTheme.LIGHT
    val grid = state.activeGrid
    val tileProvider = remember(grid, renderTheme, state.layers.opacity) {
        grid?.let {
            val style = when (it.unit) {
                WeatherUnit.DBZ -> WeatherRenderStyle.radar(renderTheme, state.layers.opacity)
                WeatherUnit.MILLIMETERS_PER_HOUR -> WeatherRenderStyle.rain(renderTheme, state.layers.opacity)
                WeatherUnit.MILLIMETERS_ONE_HOUR -> WeatherRenderStyle.hourlyRain(renderTheme, state.layers.opacity)
                WeatherUnit.LUMINANCE -> WeatherRenderStyle.cloud(renderTheme, state.layers.opacity)
            }
            WeatherTileProvider(it, style)
        }
    }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            googleMapOptionsFactory = {
                GoogleMapOptions().apply {
                    if (BuildConfig.MAP_ID.isNotBlank() && BuildConfig.MAP_ID != "DEMO_MAP_ID") {
                        mapId(BuildConfig.MAP_ID)
                    }
                }
            },
            properties = MapProperties(mapStyleOptions = fallbackStyle),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
            onMapLongClick = { viewModel.selectTarget(GeoPoint(it.latitude, it.longitude)) },
            onMapLoaded = { mapLoaded = true },
        ) {
            if (tileProvider != null) {
                TileOverlay(tileProvider = tileProvider, fadeIn = false, zIndex = 2f)
            }
            Marker(
                state = rememberUpdatedMarkerState(LatLng(state.target.coordinate.latitude, state.target.coordinate.longitude)),
                title = state.target.displayName,
            )
            if (state.layers.windEnabled) state.winds.forEach { wind ->
                Marker(
                    state = rememberUpdatedMarkerState(LatLng(wind.coordinate.latitude, wind.coordinate.longitude)),
                    title = wind.stationName,
                    snippet = "${wind.speedMetersPerSecond} m/s · ${wind.directionDegrees.toInt()}°",
                    rotation = wind.directionDegrees,
                    flat = true,
                    alpha = .82f,
                )
            }
        }
        MapControls(state, viewModel, Modifier.align(Alignment.TopCenter))
        TimelineControls(state, viewModel, Modifier.align(Alignment.BottomCenter))
        FloatingActionButton(
            onClick = {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp).size(48.dp),
        ) { Icon(Icons.Default.MyLocation, "回到目前位置") }
        AnimatedVisibility(
            visible = cameraState.position.zoom >= 11f,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 94.dp),
        ) {
            Text(
                "雷達原始格點約 1.25 km",
                modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), RoundedCornerShape(8.dp)).padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!mapLoaded) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!mapLoadTimedOut) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (mapLoadTimedOut) {
                            "Google 地圖無法載入\n請確認 Maps SDK、計費與 Android 金鑰限制"
                        } else {
                            "正在載入 Google 地圖…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapControls(state: HomeUiState, viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .94f), RoundedCornerShape(16.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = state.layers.primary == PrimaryLayer.RADAR_RAIN,
            onClick = { viewModel.setPrimaryLayer(PrimaryLayer.RADAR_RAIN) },
            label = { Text("降雨雷達") },
        )
        FilterChip(
            selected = state.layers.primary == PrimaryLayer.CLOUD,
            onClick = { viewModel.setPrimaryLayer(PrimaryLayer.CLOUD) },
            label = { Text("雲層 β") },
        )
        IconToggleButton(checked = state.layers.windEnabled, onCheckedChange = viewModel::setWindEnabled) {
            Icon(Icons.Default.Air, "切換風場", tint = if (state.layers.windEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
        IconButton(onClick = viewModel::toggleLegend) { Icon(Icons.Default.Gradient, "顯示圖例") }
    }
    if (state.layers.primary == PrimaryLayer.CLOUD) {
        Text(
            "實驗性雲層移動推估 · 不參與降雨決策",
            modifier = Modifier.padding(top = 60.dp).background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp)).padding(8.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun TimelineControls(state: HomeUiState, viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val hourlyProduct = state.layers.primary == PrimaryLayer.RADAR_RAIN &&
        state.activeGrid?.unit == WeatherUnit.MILLIMETERS_ONE_HOUR
    Surface(modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::togglePlayback) {
                    Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "暫停" else "播放")
                }
                Slider(
                    value = state.selectedMinute.toFloat(),
                    onValueChange = {
                        viewModel.setMinute(if (hourlyProduct) if (it < 30f) 0 else 60 else (it / 10).toInt() * 10)
                    },
                    valueRange = 0f..60f,
                    steps = if (hourlyProduct) 0 else 5,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (state.selectedMinute == 0) "現在" else if (hourlyProduct) "未來 1h" else "+${state.selectedMinute}m",
                    fontWeight = FontWeight.Bold,
                )
            }
            if (state.legendExpanded) WeatherLegend(state.activeGrid?.unit ?: WeatherUnit.MILLIMETERS_PER_HOUR)
        }
    }
}

@Composable
private fun WeatherLegend(unit: WeatherUnit) {
    val colors = listOf(0xFF37D6E6, 0xFF4169D8, 0xFF6D3AC6, 0xFFB62B96, 0xFFE64A54, 0xFFF49A38, 0xFFFFF1B5)
    val labels = when (unit) {
        WeatherUnit.DBZ -> listOf("5", "15", "25", "35", "45", "55", "65 dBZ")
        WeatherUnit.MILLIMETERS_PER_HOUR -> listOf("0.1", "0.5", "2.5", "10", "25", "50", "100 mm/h")
        WeatherUnit.MILLIMETERS_ONE_HOUR -> listOf("0.1", "0.5", "2.5", "10", "25", "50", "100 mm/1h")
        WeatherUnit.LUMINANCE -> listOf("薄", "", "", "雲層", "", "", "厚")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        colors.forEachIndexed { index, value ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 30.dp, height = 7.dp).background(Color(value), RoundedCornerShape(2.dp)))
                Text(labels[index], fontSize = 8.sp)
            }
        }
    }
}

private fun rainStateLabel(state: RainState) = when (state) {
    RainState.DRY -> "無雨"
    RainState.LIGHT -> "小雨"
    RainState.MODERATE -> "中雨"
    RainState.HEAVY -> "大雨"
    RainState.EXTREME -> "強降雨"
    RainState.UNAVAILABLE -> "資料不足"
}

private fun rainStateColor(state: RainState) = when (state) {
    RainState.DRY -> Color(0xFF71858F)
    RainState.LIGHT -> Color(0xFF37D6E6)
    RainState.MODERATE -> Color(0xFF6D3AC6)
    RainState.HEAVY -> Color(0xFFE64A54)
    RainState.EXTREME -> Color(0xFFF49A38)
    RainState.UNAVAILABLE -> Color.Gray
}
