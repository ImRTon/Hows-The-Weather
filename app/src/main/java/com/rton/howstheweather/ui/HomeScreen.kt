@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    com.google.maps.android.compose.MapsComposeExperimentalApi::class,
)

package com.rton.howstheweather.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.*
import com.rton.howstheweather.BuildConfig
import com.rton.howstheweather.HomeViewModel
import com.rton.howstheweather.R
import com.rton.howstheweather.data.OBSERVATION_FRAME_INTERVAL_MINUTES
import com.rton.howstheweather.data.OBSERVATION_HISTORY_MINUTES
import com.rton.howstheweather.domain.*
import com.rton.howstheweather.render.RenderTheme
import com.rton.howstheweather.render.CloudEnhancedPalette
import com.rton.howstheweather.render.WeatherRenderStyle
import com.rton.howstheweather.render.WeatherTileProvider
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HomeScreen(state: HomeUiState, viewModel: HomeViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(
                message = it,
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
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
        bottomBar = { WeatherNavigationBar(state.destination, viewModel::selectDestination) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state.destination) {
            AppDestination.NOW -> ResizableWeatherPanels(
                state = state,
                viewModel = viewModel,
                onReminder = scheduleReminder,
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
            AppDestination.FORECAST -> ForecastScreen(
                state = state,
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
            AppDestination.PRECIPITATION -> QuantitativePrecipitationScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WeatherNavigationBar(
    destination: AppDestination,
    onSelect: (AppDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        listOf(
            Triple(AppDestination.NOW, Icons.Default.NearMe, "即時"),
            Triple(AppDestination.FORECAST, Icons.Default.CalendarMonth, "預報"),
            Triple(AppDestination.PRECIPITATION, Icons.Default.Map, "降水"),
        ).forEach { (item, icon, label) ->
            NavigationBarItem(
                selected = destination == item,
                onClick = { onSelect(item) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

@Composable
private fun AppTopBar(state: HomeUiState, viewModel: HomeViewModel) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    val timeContext = when (state.destination) {
        AppDestination.FORECAST -> "逐 3 小時 · 本週日夜"
        AppDestination.PRECIPITATION -> {
            val startHour = state.quantitativeForecastIndex * 12
            "未來 $startHour–${startHour + 12} 小時 · 累積預報"
        }
        AppDestination.NOW -> when {
            state.layers.primary == PrimaryLayer.ONE_HOUR_RAIN -> "未來 1 小時 · 累積預報"
            state.selectedMinute < 0 && state.activeGrid?.unit == WeatherUnit.LUMINANCE ->
                "過去 ${-state.selectedMinute} 分 · 衛星觀測"
            state.selectedMinute < 0 -> "過去 ${-state.selectedMinute} 分 · 雷達觀測"
            state.activeGrid?.unit == WeatherUnit.LUMINANCE -> "現在 · 衛星觀測"
            else -> "現在 · 雷達觀測"
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(48.dp)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        state.target.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$timeContext · 資料 ${formatter.format(
                            if (state.destination == AppDestination.PRECIPITATION) {
                                state.quantitativeForecastFrames.getOrNull(state.quantitativeForecastIndex)?.validAt
                                    ?: state.decision.issuedAt
                            } else {
                                state.activeGrid?.validAt ?: state.decision.issuedAt
                            },
                        )}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.decision.isStale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, "重新整理") }
                IconButton(onClick = viewModel::cycleTheme) { Icon(Icons.Default.Contrast, "切換亮暗主題") }
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (state.isUpdating) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "正在背景更新天氣與風場資料" },
                        color = MaterialTheme.colorScheme.primary.copy(alpha = .72f),
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
    }
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
        val handleTouchHeight = 48.dp
        val handleVisualHeight = 8.dp
        val minCard = 64.dp
        val minMap = 180.dp
        val bounds = calculateResizablePanelBounds(
            totalHeight = totalHeight.value,
            preferredMinDecisionHeight = minCard.value,
            preferredMinMapHeight = minMap.value,
            preferredHandleHeight = handleVisualHeight.value,
            handleTouchHeight = handleTouchHeight.value,
        )
        val actualHandleVisualHeight = bounds.handleHeight.dp
        fun fraction(anchor: PanelAnchor) = when (anchor) {
            PanelAnchor.DECISION -> 0.68f
            PanelAnchor.BALANCED -> 0.40f
            PanelAnchor.MAP -> if (bounds.totalHeight > 0f) {
                bounds.minDecisionHeight / bounds.totalHeight
            } else {
                0f
            }
        }
        var dragFraction by remember(state.panelAnchor, totalHeight) { mutableFloatStateOf(fraction(state.panelAnchor)) }
        var dragging by remember { mutableStateOf(false) }
        val targetHeight = bounds.clampDecisionHeight(bounds.totalHeight * dragFraction).dp
        val animatedHeight by animateDpAsState(targetValue = targetHeight, label = "decision-panel")
        val displayedHeight = bounds.clampDecisionHeight(
            (if (dragging) targetHeight else animatedHeight).value,
        ).dp
        val handleTouchOffset = (
            displayedHeight + actualHandleVisualHeight / 2 - handleTouchHeight / 2
        ).coerceIn(0.dp, bounds.maxHandleTouchOffset.dp)
        val dragState = rememberDraggableState { amount ->
            val delta = with(density) { amount.toDp().value }
            val nextHeight = bounds.clampDecisionHeight(targetHeight.value + delta)
            dragFraction = if (bounds.totalHeight > 0f) nextHeight / bounds.totalHeight else 0f
        }

        Box(Modifier.fillMaxSize()) {
            DecisionPanel(
                state = state,
                onReminder = onReminder,
                modifier = Modifier.fillMaxWidth().height(displayedHeight),
            )
            WeatherMap(
                state,
                viewModel,
                Modifier
                    .fillMaxWidth()
                    .offset(y = displayedHeight + actualHandleVisualHeight)
                    .height(
                        (bounds.totalHeight - displayedHeight.value - bounds.handleHeight)
                            .coerceAtLeast(0f)
                            .dp,
                    ),
            )
            PanelHandle(
                onClick = viewModel::cyclePanel,
                visualHeight = actualHandleVisualHeight,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(72.dp)
                    .height(handleTouchHeight)
                    .offset(y = handleTouchOffset)
                    .zIndex(1f)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStarted = { dragging = true },
                        onDragStopped = {
                            dragging = false
                            val nearest = PanelAnchor.entries.minBy { abs(fraction(it) - dragFraction) }
                            dragFraction = fraction(nearest)
                            viewModel.setPanel(nearest)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun PanelHandle(onClick: () -> Unit, visualHeight: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "上下拖曳調整決策卡與地圖大小"
                onClick("切換面板大小") { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(visualHeight),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 32.dp, height = 3.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = .75f)),
                )
            }
        }
    }
}

@Composable
private fun DecisionPanel(state: HomeUiState, onReminder: () -> Unit, modifier: Modifier = Modifier) {
    val collapsed = state.panelAnchor == PanelAnchor.MAP
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = if (collapsed) 6.dp else 10.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                state.decision.headline,
                style = if (collapsed) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!collapsed) {
                Text(state.decision.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
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
private fun AreaForecastStrip(
    forecast: AreaForecast?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }

    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (forecast == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                Text(
                    if (loading) "正在取得目標附近的鄉鎮預報" else "此位置暫無鄉鎮時段預報",
                    style = MaterialTheme.typography.bodyMedium,
                    color = labelColor,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                forecast.periods.forEachIndexed { index, period ->
                    AreaForecastPeriodCard(
                        period = period,
                        timeFormatter = timeFormatter,
                        showDemoLabel = forecast.isDemo && index == 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun AreaForecastPeriodCard(
    period: AreaForecastPeriod,
    timeFormatter: DateTimeFormatter,
    showDemoLabel: Boolean,
) {
    val description = period.weatherDescription.ifBlank { "天氣未定" }
    Column(
        Modifier
            .width(92.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .72f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics {
                contentDescription = buildString {
                    append(timeFormatter.format(period.startAt)).append("至")
                    append(timeFormatter.format(period.endAt)).append('，').append(description)
                    period.precipitationProbabilityPercent?.let { append("，降雨機率").append(it).append("百分比") }
                    append('，').append(formatTemperatureRange(period))
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(timeFormatter.format(period.startAt), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (showDemoLabel) {
                Text(" · 示範", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Icon(
            weatherIcon(description),
            contentDescription = null,
            tint = weatherIconColor(description),
            modifier = Modifier.size(26.dp),
        )
        Text(description, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WaterDrop, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                " ${period.precipitationProbabilityPercent?.let { "$it%" } ?: "—"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(formatTemperatureRange(period), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatTemperatureRange(period: AreaForecastPeriod): String {
    val minimum = period.minimumTemperatureCelsius
    val maximum = period.maximumTemperatureCelsius
    return when {
        minimum == null || maximum == null -> "溫度 —"
        minimum == maximum -> "$minimum°C"
        else -> "$minimum–$maximum°C"
    }
}

private fun weatherIcon(description: String): ImageVector = when {
    "雷" in description -> Icons.Default.Thunderstorm
    "雪" in description -> Icons.Default.AcUnit
    "雨" in description -> Icons.Default.WaterDrop
    "晴" in description -> Icons.Default.WbSunny
    "雲" in description || "陰" in description -> Icons.Default.Cloud
    else -> Icons.Default.Info
}

@Composable
private fun weatherIconColor(description: String): Color = when {
    "雷" in description -> MaterialTheme.colorScheme.tertiary
    "雨" in description -> MaterialTheme.colorScheme.primary
    "晴" in description -> Color(0xFFE6A400)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun WeatherMap(state: HomeUiState, viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
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
        if (grants.values.any { it }) {
            requestDeviceLocation()
        } else {
            viewModel.showMessage("需要位置權限才能回到目前位置")
        }
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
    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) requestDeviceLocation()
    }
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.isMoving }
            .filter { !it }
            .collect {
                val position = cameraState.position
                viewModel.setMapViewport(
                    center = GeoPoint(position.target.latitude, position.target.longitude),
                    zoom = position.zoom,
                )
            }
    }
    val darkMap = MaterialTheme.colorScheme.background.luminance() < .35f
    val configuredMapId = BuildConfig.MAP_ID.trim()
    val usesBundledMapStyle = configuredMapId.isBlank() || configuredMapId == "DEMO_MAP_ID"
    val fallbackStyle = remember(darkMap, usesBundledMapStyle) {
        if (usesBundledMapStyle) {
            MapStyleOptions.loadRawResourceStyle(context, if (darkMap) R.raw.map_style_dark else R.raw.map_style_light)
        } else null
    }
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
    val tileProvider = remember(grid, renderTheme) {
        grid?.let {
            val style = when (it.unit) {
                WeatherUnit.DBZ -> WeatherRenderStyle.radar(renderTheme, 1f)
                WeatherUnit.MILLIMETERS_PER_HOUR -> WeatherRenderStyle.rain(renderTheme, 1f)
                WeatherUnit.MILLIMETERS_ONE_HOUR -> WeatherRenderStyle.hourlyRain(renderTheme, 1f)
                WeatherUnit.MILLIMETERS_TWELVE_HOURS -> WeatherRenderStyle.twelveHourRain(renderTheme, 1f)
                WeatherUnit.LUMINANCE -> WeatherRenderStyle.cloud(renderTheme, 1f)
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
                    if (!usesBundledMapStyle) {
                        mapId(configuredMapId)
                    }
                }
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
                val targetZoom = if (state.target.isDeviceLocation) {
                    DEFAULT_MAP_ZOOM
                } else {
                    cameraState.position.zoom.coerceAtLeast(10f)
                }
                val current = googleMap.cameraPosition
                val needsMove = abs(current.target.latitude - state.target.coordinate.latitude) > .00001 ||
                    abs(current.target.longitude - state.target.coordinate.longitude) > .00001 ||
                    abs(current.zoom - targetZoom) > .01f
                if (needsMove) {
                    googleMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(state.target.coordinate.latitude, state.target.coordinate.longitude),
                            targetZoom,
                        ),
                        650,
                        null,
                    )
                }
            }
            if (mapLoaded && tileProvider != null) {
                TileOverlay(
                    tileProvider = tileProvider,
                    transparency = 1f - state.layers.opacity,
                    fadeIn = true,
                    zIndex = 2f,
                )
            }
            Marker(
                state = rememberUpdatedMarkerState(LatLng(state.target.coordinate.latitude, state.target.coordinate.longitude)),
                title = state.target.displayName,
            )
        }
        if (state.layers.windEnabled) {
            val position = cameraState.position
            WindParticleOverlay(
                windGrid = state.windGrid,
                winds = state.winds,
                mapCenter = GeoPoint(position.target.latitude, position.target.longitude),
                mapZoom = position.zoom,
                darkMap = darkMap,
                modifier = Modifier.fillMaxSize(),
            )
            WindLegend(
                provenance = state.windProvenance,
                darkMap = darkMap,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 72.dp),
            )
        }
        MapControls(state, viewModel, Modifier.align(Alignment.TopCenter))
        if (state.layers.primary == PrimaryLayer.ONE_HOUR_RAIN) {
            OneHourRainControls(state, Modifier.align(Alignment.BottomCenter))
        } else {
            TimelineControls(state, viewModel, Modifier.align(Alignment.BottomCenter))
        }
        FloatingActionButton(
            onClick = locateOrRequestPermission,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp)
                .size(48.dp)
                .semantics {
                    contentDescription = if (locating) "正在取得目前位置" else "回到目前位置"
                },
        ) {
            if (locating) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.MyLocation, "回到目前位置")
        }
        AnimatedVisibility(
            visible = state.layers.primary == PrimaryLayer.RADAR_RAIN &&
                state.radarCoverage == RadarCoverage.LOCAL && cameraState.position.zoom >= 11f,
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

@SuppressLint("MissingPermission")
internal fun requestBestLocation(
    client: FusedLocationProviderClient,
    onSuccess: (Location) -> Unit,
    onFailure: (String) -> Unit,
) {
    client.lastLocation
        .addOnSuccessListener { cached ->
            val cacheAgeMillis = cached?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
            if (cached != null && cacheAgeMillis in 0..LOCATION_CACHE_MAX_AGE_MILLIS) {
                onSuccess(cached)
                return@addOnSuccessListener
            }
            val fallback = cached?.takeIf { cacheAgeMillis in 0..LOCATION_FALLBACK_MAX_AGE_MILLIS }
            val request = CurrentLocationRequest.Builder()
                // A weather target does not need a slow GPS-only fix. Android can
                // still use a recent high-accuracy fix when one is available.
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(LOCATION_CACHE_MAX_AGE_MILLIS)
                .setDurationMillis(LOCATION_REQUEST_TIMEOUT_MILLIS)
                .build()
            val cancellation = CancellationTokenSource()
            client.getCurrentLocation(request, cancellation.token)
                .addOnSuccessListener { current ->
                    when {
                        current != null -> onSuccess(current)
                        fallback != null -> onSuccess(fallback)
                        else -> onFailure("暫時無法取得位置，請開啟裝置定位後再試")
                    }
                }
                .addOnFailureListener {
                    if (fallback != null) onSuccess(fallback)
                    else onFailure("定位失敗：${it.message ?: "請確認裝置定位已開啟"}")
                }
        }
        .addOnFailureListener { onFailure("定位失敗：${it.message ?: "無法讀取快取位置"}") }
}

private const val LOCATION_CACHE_MAX_AGE_MILLIS = 2 * 60 * 1_000L
private const val LOCATION_FALLBACK_MAX_AGE_MILLIS = 30 * 60 * 1_000L
private const val LOCATION_REQUEST_TIMEOUT_MILLIS = 5_000L

@Composable
private fun WindLegend(provenance: WindProvenance, darkMap: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text("粒子方向＝風吹去向", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    windColor(0f, darkMap) to "弱",
                    windColor(3f, darkMap) to "3",
                    windColor(7f, darkMap) to "7",
                    windColor(11f, darkMap) to "11+ m/s",
                ).forEach { (color, label) ->
                    Box(Modifier.size(9.dp).background(color, RoundedCornerShape(99.dp)))
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                when (provenance) {
                    WindProvenance.MODEL -> "WRF 3 km · 10 m 模式風"
                    WindProvenance.OBSERVATION -> "氣象署測站觀測內插"
                    WindProvenance.DEMO -> "示範測站內插"
                    WindProvenance.UNAVAILABLE -> "風場資料載入中"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            label = {
                Text(
                    if (state.selectedMinute <= 0 && state.radarCoverage == RadarCoverage.WIDE) {
                        "廣域雷達"
                    } else {
                        "降雨雷達"
                    },
                )
            },
        )
        FilterChip(
            selected = state.layers.primary == PrimaryLayer.ONE_HOUR_RAIN,
            onClick = { viewModel.setPrimaryLayer(PrimaryLayer.ONE_HOUR_RAIN) },
            label = { Text("1 小時") },
        )
        FilterChip(
            selected = state.layers.primary == PrimaryLayer.CLOUD,
            onClick = { viewModel.setPrimaryLayer(PrimaryLayer.CLOUD) },
            label = { Text("雲層") },
        )
        IconToggleButton(checked = state.layers.windEnabled, onCheckedChange = viewModel::setWindEnabled) {
            Icon(Icons.Default.Air, "切換風場", tint = if (state.layers.windEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
        IconButton(onClick = viewModel::toggleLegend) { Icon(Icons.Default.Gradient, "顯示圖例") }
    }
}

@Composable
private fun OneHourRainControls(state: HomeUiState, modifier: Modifier = Modifier) {
    val grid = state.quantitativeRainGrid
    val amount = grid?.sample(state.target.coordinate)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.WaterDrop, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("未來 1 小時累積雨量", style = MaterialTheme.typography.titleSmall)
                    Text(
                        grid?.sourceId ?: "等待 F-B0046-001 數值格點",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    amount?.let { "%.1f mm".format(it) } ?: "此點無有效值",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.legendExpanded) WeatherLegend(WeatherUnit.MILLIMETERS_ONE_HOUR)
        }
    }
}

@Composable
private fun TimelineControls(state: HomeUiState, viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = viewModel::togglePlayback,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "暫停" else "播放")
                }
                Column(Modifier.width(68.dp)) {
                    Text(
                        "過去 ${OBSERVATION_HISTORY_MINUTES} 分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        if (state.selectedMinute < 0) "${-state.selectedMinute} 分前" else "現在",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Slider(
                    value = state.selectedMinute.toFloat(),
                    onValueChange = {
                        viewModel.setMinute(
                            (it / OBSERVATION_FRAME_INTERVAL_MINUTES).roundToInt() *
                                OBSERVATION_FRAME_INTERVAL_MINUTES,
                        )
                    },
                    valueRange = -OBSERVATION_HISTORY_MINUTES.toFloat()..0f,
                    steps = OBSERVATION_HISTORY_MINUTES / OBSERVATION_FRAME_INTERVAL_MINUTES - 1,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription =
                                "過去 ${OBSERVATION_HISTORY_MINUTES} 分鐘至現在的觀測時間軸"
                        },
                )
            }
            if (state.legendExpanded) WeatherLegend(state.activeGrid?.unit ?: WeatherUnit.MILLIMETERS_PER_HOUR)
        }
    }
}

@Composable
private fun WeatherLegend(unit: WeatherUnit) {
    val renderTheme = if (MaterialTheme.colorScheme.background.luminance() < .35f) RenderTheme.DARK else RenderTheme.LIGHT
    val colors = if (unit == WeatherUnit.LUMINANCE) {
        CloudEnhancedPalette.colors(renderTheme)
    } else {
        listOf(0xFF37D6E6, 0xFF4169D8, 0xFF6D3AC6, 0xFFB62B96, 0xFFE64A54, 0xFFF49A38, 0xFFFFF1B5)
            .map(Long::toInt)
    }
    val labels = when (unit) {
        WeatherUnit.DBZ -> listOf("5", "15", "25", "35", "45", "55", "65 dBZ")
        WeatherUnit.MILLIMETERS_PER_HOUR -> listOf("0.1", "0.5", "2.5", "10", "25", "50", "100 mm/h")
        WeatherUnit.MILLIMETERS_ONE_HOUR -> listOf("0.1", "0.5", "2.5", "10", "25", "50", "100 mm/1h")
        WeatherUnit.MILLIMETERS_TWELVE_HOURS -> listOf("0.1", "0.5", "2.5", "10", "25", "50", "100+ mm/12h")
        WeatherUnit.LUMINANCE -> listOf("低亮", "", "", "雲頂", "", "", "高亮")
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
