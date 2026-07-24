@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    com.google.maps.android.compose.MapsComposeExperimentalApi::class,
)

package com.rton.howstheweather.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.MarqueeSpacing
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
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HomeScreen(state: HomeUiState, viewModel: HomeViewModel) {
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
    NavigationBar {
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
                if (state.isUpdating || state.isHistoryLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                contentDescription = if (state.isHistoryLoading) {
                                    "正在載入歷史觀測資料"
                                } else {
                                    "正在背景更新天氣與風場資料"
                                }
                            },
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
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val totalHeight = maxHeight
        val handleTouchHeight = 48.dp
        val handleVisualHeight = 8.dp
        val minCard = 48.dp
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
            PanelAnchor.DECISION -> 0.48f
            PanelAnchor.BALANCED -> 0.30f
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(actualHandleVisualHeight)
                    .offset(y = displayedHeight)
                    .background(MaterialTheme.colorScheme.surface)
                    .zIndex(1f),
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
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
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
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = .75f))
                        .indication(
                            interactionSource = interactionSource,
                            indication = ripple(color = MaterialTheme.colorScheme.primary),
                        ),
                )
            }
        }
    }
}

@Composable
private fun DecisionPanel(state: HomeUiState, modifier: Modifier = Modifier) {
    val collapsed = state.panelAnchor == PanelAnchor.MAP
    val expanded = state.panelAnchor == PanelAnchor.DECISION
    val forecastPeriods = state.areaForecast?.periods.orEmpty()
        .ifEmpty { state.weeklyForecast?.periods.orEmpty() }
    val now = remember(forecastPeriods, state.target.coordinate) { java.time.Instant.now() }
    val priorityPeriods = remember(forecastPeriods, now) {
        PriorityForecastSelector.select(forecastPeriods, now)
    }
    val upcomingRain = remember(state.areaForecast, now) {
        val until = now.plusSeconds(12 * 60 * 60L)
        state.areaForecast?.periods.orEmpty()
            .filter { it.endAt > now && it.startAt < until }
            .sortedBy(AreaForecastPeriod::startAt)
            .take(4)
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = if (collapsed) 8.dp else 10.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(if (collapsed) 3.dp else 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.decision.headline,
                    modifier = Modifier
                        .weight(1f)
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 0,
                            initialDelayMillis = 0,
                            spacing = MarqueeSpacing.fractionOfContainer(0.08f),
                        ),
                    style = if (collapsed) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                if (collapsed) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Default.WaterDrop,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        priorityPeriods.firstOrNull()?.precipitationProbabilityPercent?.let { "降雨 $it%" }
                            ?: "降雨 —",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!collapsed) {
                PriorityForecastRow(
                    periods = priorityPeriods,
                    loading = state.areaForecastLoading && state.weeklyForecastLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (expanded) {
                UpcomingRainStrip(upcomingRain, Modifier.fillMaxWidth())
                CurrentObservationLine(
                    observation = state.currentWeather,
                    loading = state.currentWeatherLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PriorityForecastRow(
    periods: List<PriorityForecastPeriod>,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    if (periods.isEmpty()) {
        Surface(
            modifier = modifier.heightIn(min = 76.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Text(
                    if (loading) "正在取得今天與明天預報" else "今天與明天預報暫缺",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        periods.forEach { period ->
            PriorityForecastCard(period, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PriorityForecastCard(period: PriorityForecastPeriod, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 108.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f),
    ) {
        Column(
            Modifier
                .padding(horizontal = 9.dp, vertical = 8.dp)
                .semantics {
                    contentDescription = buildString {
                        append(period.label).append('，').append(period.weatherDescription)
                        period.precipitationProbabilityPercent?.let {
                            append("，降雨機率").append(it).append("百分比")
                        }
                        append('，').append(formatPriorityTemperature(period))
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(period.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    weatherIcon(period.weatherDescription),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = weatherIconColor(period.weatherDescription),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    period.weatherDescription,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WaterDrop, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    period.precipitationProbabilityPercent?.let { " $it%" } ?: " —",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(formatPriorityTemperature(period), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun UpcomingRainStrip(periods: List<AreaForecastPeriod>, modifier: Modifier = Modifier) {
    if (periods.isEmpty()) return
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Taipei")) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .32f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("接下來 12 小時降雨機率", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                periods.forEach { period ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatter.format(period.startAt), style = MaterialTheme.typography.labelSmall)
                        Text(
                            period.precipitationProbabilityPercent?.let { "$it%" } ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentObservationLine(
    observation: CurrentWeatherObservation?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Taipei")) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .32f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                observation != null -> {
                    val description = observation.weatherDescription ?: "目前天氣"
                    Icon(
                        weatherIcon(description),
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = weatherIconColor(description),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        buildString {
                            append("目前 ").append(description)
                            observation.temperatureCelsius?.let { append(" · ").append(formatOneDecimal(it, "°C")) }
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${observation.stationName} ${formatter.format(observation.observedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                loading -> Text("目前觀測載入中", style = MaterialTheme.typography.bodyMedium)
                else -> Text(
                    "目前觀測暫缺",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatPriorityTemperature(period: PriorityForecastPeriod): String {
    val minimum = period.minimumTemperatureCelsius
    val maximum = period.maximumTemperatureCelsius
    return when {
        minimum == null || maximum == null -> "溫度 —"
        minimum == maximum -> "$minimum°C"
        else -> "$minimum–$maximum°C"
    }
}

private fun formatOneDecimal(value: Float, suffix: String): String =
    String.format(Locale.TAIWAN, "%.1f%s", value, suffix)

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
    // Keep the Maps SDK attribution clear of our bottom controls and legend.
    // The Android Maps SDK fixes the logo to the start edge and only exposes
    // padding for moving its built-in attribution away from overlapping UI.
    val mapBottomContentPadding = when {
        state.layers.primary == PrimaryLayer.ONE_HOUR_RAIN && state.legendExpanded -> 108.dp
        state.legendExpanded -> 96.dp
        state.layers.primary == PrimaryLayer.ONE_HOUR_RAIN -> 72.dp
        else -> 68.dp
    }
    val firstTileOverlayState = rememberTileOverlayState()
    val secondTileOverlayState = rememberTileOverlayState()
    val tileProviders = remember(grid?.unit, renderTheme) {
        grid?.let {
            val style = when (it.unit) {
                WeatherUnit.DBZ -> WeatherRenderStyle.radar(renderTheme, 1f)
                WeatherUnit.MILLIMETERS_PER_HOUR -> WeatherRenderStyle.rain(renderTheme, 1f)
                WeatherUnit.MILLIMETERS_ONE_HOUR -> WeatherRenderStyle.hourlyRain(renderTheme, 1f)
                WeatherUnit.MILLIMETERS_TWELVE_HOURS -> WeatherRenderStyle.twelveHourRain(renderTheme, 1f)
                WeatherUnit.LUMINANCE -> WeatherRenderStyle.cloud(renderTheme, 1f)
            }
            arrayOf(
                WeatherTileProvider(it, style),
                WeatherTileProvider(it, style),
            )
        }
    }
    var frontTileSlot by remember(grid?.unit, renderTheme) { mutableIntStateOf(0) }
    var displayedGrid by remember(grid?.unit, renderTheme) { mutableStateOf(grid) }
    var displayedMinute by remember(grid?.unit, renderTheme) { mutableIntStateOf(state.selectedMinute) }
    var timelineFromMinute by remember(grid?.unit, renderTheme) { mutableIntStateOf(state.selectedMinute) }
    var timelineToMinute by remember(grid?.unit, renderTheme) { mutableIntStateOf(state.selectedMinute) }
    val tileBlend = remember(grid?.unit, renderTheme) { Animatable(0f) }
    LaunchedEffect(mapLoaded, grid, tileProviders) {
        // TileOverlayState is attached only after TileOverlay enters the map
        // composition. Clearing it before that point throws and crashes the
        // app when weather data arrives before Maps finishes loading.
        if (!mapLoaded) return@LaunchedEffect
        val providers = tileProviders ?: return@LaunchedEffect
        val targetGrid = grid ?: return@LaunchedEffect
        if (displayedGrid === targetGrid) {
            displayedMinute = state.selectedMinute
            timelineFromMinute = state.selectedMinute
            timelineToMinute = state.selectedMinute
            viewModel.onWeatherFramePresented(targetGrid)
            return@LaunchedEffect
        }

        val backTileSlot = 1 - frontTileSlot
        val frontProvider = providers[frontTileSlot]
        val backProvider = providers[backTileSlot]
        // Pre-render through the visible provider because it knows the current
        // viewport. The cache is shared, so the hidden provider can consume the
        // prepared PNGs immediately without clearing the visible overlay.
        withContext(Dispatchers.Default) { frontProvider.preloadGrid(targetGrid) }
        backProvider.updateGrid(targetGrid)
        if (backTileSlot == 0) firstTileOverlayState.clearTileCache()
        else secondTileOverlayState.clearTileCache()
        delay(TILE_BACK_BUFFER_SETTLE_MILLIS)

        tileBlend.snapTo(0f)
        if (state.isPlaying && displayedGrid?.unit == targetGrid.unit) {
            timelineFromMinute = displayedMinute
            timelineToMinute = state.selectedMinute
            tileBlend.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = TILE_BLEND_DURATION_MILLIS,
                    easing = LinearEasing,
                ),
            )
        } else {
            tileBlend.snapTo(1f)
        }
        frontTileSlot = backTileSlot
        displayedGrid = targetGrid
        displayedMinute = state.selectedMinute
        timelineFromMinute = state.selectedMinute
        timelineToMinute = state.selectedMinute
        tileBlend.snapTo(0f)
        viewModel.onWeatherFramePresented(targetGrid)
    }
    val tileWeights = tileBlendWeights(frontTileSlot, tileBlend.value)
    val timelineMinute = if (state.isPlaying) {
        timelineFromMinute + (timelineToMinute - timelineFromMinute) * tileBlend.value
    } else {
        state.selectedMinute.toFloat()
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
            contentPadding = PaddingValues(bottom = mapBottomContentPadding),
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
            if (mapLoaded && tileProviders != null) {
                TileOverlay(
                    tileProvider = tileProviders[0],
                    state = firstTileOverlayState,
                    transparency = 1f - state.layers.opacity * tileWeights.first,
                    fadeIn = false,
                    zIndex = 2f,
                )
                TileOverlay(
                    tileProvider = tileProviders[1],
                    state = secondTileOverlayState,
                    transparency = 1f - state.layers.opacity * tileWeights.second,
                    fadeIn = false,
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
                cameraMoving = cameraState.isMoving,
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
            TimelineControls(
                state = state,
                viewModel = viewModel,
                timelineMinute = timelineMinute,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = 12.dp,
                    bottom = if (state.legendExpanded) 132.dp else 94.dp,
                ),
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
private const val TILE_BACK_BUFFER_SETTLE_MILLIS = 32L
private const val TILE_BLEND_DURATION_MILLIS = 650

internal data class TileBlendWeights(val first: Float, val second: Float)

internal fun tileBlendWeights(frontSlot: Int, progress: Float): TileBlendWeights {
    require(frontSlot == 0 || frontSlot == 1)
    val blend = progress.coerceIn(0f, 1f)
    val first = if (frontSlot == 0) 1f - blend else blend
    return TileBlendWeights(first = first, second = 1f - first)
}

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
                    WindProvenance.UNAVAILABLE -> "風場資料尚不可用"
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
                    when (state.radarCoverage) {
                        RadarCoverage.WIDE -> "廣域雷達"
                        RadarCoverage.LOCAL -> if (state.radarRegionalLoading) "區域載入中" else "區域雷達"
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
private fun TimelineControls(
    state: HomeUiState,
    viewModel: HomeViewModel,
    timelineMinute: Float,
    modifier: Modifier = Modifier,
) {
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
                    if (state.isPlaybackPending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (state.isPlaying) "暫停" else "播放",
                        )
                    }
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
                    value = timelineMinute,
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
