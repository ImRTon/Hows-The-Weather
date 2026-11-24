package com.rton.howstheweather.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.rton.howstheweather.domain.AirQualityGridBuilder
import com.rton.howstheweather.domain.AirQualityObservation
import com.rton.howstheweather.domain.HomeUiState
import com.rton.howstheweather.render.RenderTheme
import com.rton.howstheweather.render.WeatherGridBitmapRenderer
import com.rton.howstheweather.render.WeatherRenderStyle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AirQualityMapScreen(
    state: HomeUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    originBounds: Rect? = null,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val renderTheme = if (dark) RenderTheme.DARK else RenderTheme.LIGHT
    val scope = rememberCoroutineScope()

    // Container transform progress: 0f = capsule bounds, 1f = full screen.
    val openProgress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        openProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.88f, stiffness = 360f),
        )
    }

    // Predictive back state: drives real-time scale, translation, and corner rounding during edge swipe.
    val backProgress = remember { Animatable(0f) }
    val exitProgress = remember { Animatable(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var swipeDeltaY by remember { mutableFloatStateOf(0f) }
    var initialTouchY by remember { mutableFloatStateOf(Float.NaN) }

    val triggerAnimatedBack: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                if (originBounds != null) {
                    openProgress.animateTo(0f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
                } else {
                    exitProgress.animateTo(1f, tween(durationMillis = 200))
                }
                onBack()
            }
        }
    }

    PredictiveBackHandler(enabled = !closing) { progressFlow ->
        initialTouchY = Float.NaN
        swipeDeltaY = 0f
        try {
            progressFlow.collect { event ->
                swipeEdge = event.swipeEdge
                if (initialTouchY.isNaN()) initialTouchY = event.touchY
                swipeDeltaY = event.touchY - initialTouchY
                backProgress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            closing = true
            if (originBounds != null) {
                openProgress.animateTo(0f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
            } else {
                exitProgress.animateTo(1f, tween(durationMillis = 160))
            }
            onBack()
        } catch (_: CancellationException) {
            backProgress.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            swipeDeltaY = 0f
        }
    }

    var aqiGroundOverlay by remember(state.airQualityStations, renderTheme) {
        mutableStateOf<WeatherGroundOverlay?>(null)
    }
    LaunchedEffect(state.airQualityStations, renderTheme) {
        aqiGroundOverlay = withContext(Dispatchers.Default) {
            val grid = AirQualityGridBuilder.build(state.airQualityStations) ?: return@withContext null
            val style = WeatherRenderStyle.aqi(renderTheme)
            val bitmap = WeatherGridBitmapRenderer.render(grid, style)
            WeatherGroundOverlay(
                key = "aqi-${renderTheme.name}-${grid.validAt.toEpochMilli()}-${state.airQualityStations.size}",
                bitmap = bitmap,
                bounds = grid.bounds,
                zIndex = 2f,
            )
        }
    }

    var selectedStation by remember { mutableStateOf<AirQualityObservation?>(null) }
    val activeObservation = selectedStation ?: state.airQuality
    val isCustomSelection = selectedStation != null &&
        selectedStation?.stationName != state.airQuality?.stationName

    val mapCamera = remember {
        WeatherMapCameraState(MapCameraPosition(state.target.coordinate, 9.6f))
    }
    var cameraRequest by remember { mutableStateOf<MapCameraRequest?>(null) }
    var mapLoaded by remember { mutableStateOf(false) }
    var mapMounted by remember { mutableStateOf(originBounds == null) }
    LaunchedEffect(openProgress.value >= 0.85f) {
        if (openProgress.value >= 0.85f) mapMounted = true
    }

    val stationLayer = remember(state.airQualityStations, activeObservation?.stationName, dark) {
        AirQualityStationLayer(
            stations = state.airQualityStations,
            selectedStationName = activeObservation?.stationName,
            darkMap = dark,
            onSelectStation = { station ->
                selectedStation = station
                val targetZoom = maxOf(mapCamera.position.zoom, 10.2f)
                cameraRequest = MapCameraRequest(station.coordinate, targetZoom)
            },
            onSelectCluster = { center, targetZoom ->
                cameraRequest = MapCameraRequest(center, targetZoom)
            },
        )
    }

    val maxShiftPx = with(density) { 28.dp.toPx() }
    val cornerPx = with(density) { 28.dp.toPx() }
    val originCornerPx = with(density) { 20.dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim fades in as the capsule expands and fades out during predictive back / collapse.
        val t = openProgress.value.coerceIn(0f, 1f)
        val backFade = (1f - (backProgress.value + exitProgress.value).coerceIn(0f, 1f))
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f * t * backFade)),
        )

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val expand = openProgress.value.coerceIn(0f, 1f)
                    val p = backProgress.value
                    val e = exitProgress.value
                    val scale = 1f - 0.12f * p - 0.04f * e
                    scaleX = scale
                    scaleY = scale
                    val edgeSign = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                    translationX = edgeSign * maxShiftPx * p + with(density) { 36.dp.toPx() } * e
                    translationY = (swipeDeltaY * 0.18f).coerceIn(-maxShiftPx, maxShiftPx)
                    alpha = (1f - e) * if (originBounds == null) expand else 1f

                    if (originBounds != null && expand < 0.999f) {
                        val curLeft = lerp(originBounds.left, 0f, expand)
                        val curTop = lerp(originBounds.top, 0f, expand)
                        val curRight = lerp(originBounds.right, size.width, expand)
                        val curBottom = lerp(originBounds.bottom, size.height, expand)
                        val curRadius = lerp(originCornerPx, cornerPx * p, expand)
                        shape = ExpandingContainerShape(curLeft, curTop, curRight, curBottom, curRadius)
                        clip = true
                    } else {
                        val radius = cornerPx * p
                        shape = RoundedCornerShape(radius)
                        clip = p > 0.001f
                    }
                },
            color = colors.background,
        ) {
            val chromeAlpha = ((openProgress.value - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val mapReadyToReveal = mapLoaded && openProgress.value >= 0.85f && !closing
            val mapCoverAlpha by animateFloatAsState(
                targetValue = if (mapReadyToReveal) 0f else 1f,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "aqiMapCoverAlpha",
            )
            val capsuleTintAlpha = if (originBounds != null) {
                (1f - openProgress.value / 0.65f).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(Modifier.fillMaxSize()) {
                if (mapMounted) {
                    WeatherBaseMap(
                        camera = mapCamera,
                        darkMap = dark,
                        target = state.target,
                        cameraRequest = cameraRequest,
                        onCameraRequestHandled = { cameraRequest = null },
                        weatherLayers = emptyList(),
                        bottomContentPadding = 176.dp,
                        onLongPress = {},
                        onMapLoaded = { mapLoaded = true },
                        onCameraIdle = { mapCamera.update(it) },
                        airQualityStations = stationLayer,
                        groundOverlays = listOfNotNull(aqiGroundOverlay),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (!mapLoaded) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(colors.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }

                // Themed transition veil sits above the Map SurfaceView until both the capsule
                // expansion and base map first frame are ready, preventing any white flash.
                if (mapCoverAlpha > 0.001f) {
                    val veilColor = androidx.compose.ui.graphics.lerp(
                        colors.background,
                        colors.surfaceVariant,
                        capsuleTintAlpha,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(veilColor.copy(alpha = mapCoverAlpha)),
                    )
                }

                // Floating top chrome: back button, compact live title pill, recenter button.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .graphicsLayer { alpha = chromeAlpha },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    FloatingMapCircleButton(
                        contentDescription = "返回摘要",
                        onClick = triggerAnimatedBack,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                            tint = colors.onSurface,
                        )
                    }

                    val pillShape = RoundedCornerShape(24.dp)
                    Surface(
                        modifier = Modifier.floatingMapShadow(
                            shape = pillShape,
                            dark = dark,
                            elevation = 12.dp,
                        ),
                        shape = pillShape,
                        color = floatingOverlayColor(colors, dark, alpha = 0.95f),
                        border = floatingTopSheen(dark),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "空氣品質",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                            )
                            val countText = when {
                                state.airQualityLoading -> "更新中"
                                state.airQualityStations.isNotEmpty() -> "${state.airQualityStations.size} 站即時"
                                else -> "即時觀測"
                            }
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .background(colors.onSurfaceVariant.copy(alpha = 0.45f), CircleShape),
                            )
                            Text(
                                countText,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }

                    FloatingMapCircleButton(
                        contentDescription = "置中目前位置",
                        onClick = {
                            selectedStation = null
                            cameraRequest = MapCameraRequest(state.target.coordinate, 10.2f)
                        },
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.onSurface,
                        )
                    }
                }

                // Floating bottom station inspector card.
                AirQualityMapBottomCard(
                    observation = activeObservation,
                    isCustomSelection = isCustomSelection,
                    loading = state.airQualityLoading,
                    unavailableReason = state.airQualityUnavailableReason,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .graphicsLayer { alpha = chromeAlpha },
                )
            }
        }
    }
}

private class ExpandingContainerShape(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val cornerRadius: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            ),
        )
}

@Composable
private fun FloatingMapCircleButton(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = floatingOverlayColor(colors, dark, alpha = 0.95f),
        border = floatingTopSheen(dark),
        modifier = Modifier
            .size(48.dp)
            .floatingMapShadow(
                shape = CircleShape,
                dark = dark,
                elevation = 12.dp,
            )
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

private fun Modifier.floatingMapShadow(
    shape: Shape,
    dark: Boolean,
    elevation: androidx.compose.ui.unit.Dp,
    glowColor: Color? = null,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = when {
        dark && glowColor != null -> glowColor.copy(alpha = 0.55f)
        dark -> Color(0xFF7BB8E8).copy(alpha = 0.40f)
        else -> Color(0xFF0E2238).copy(alpha = 0.26f)
    },
    spotColor = if (dark) Color.Black else Color(0xFF0E2238).copy(alpha = 0.38f),
)

private fun floatingOverlayColor(
    colors: androidx.compose.material3.ColorScheme,
    dark: Boolean,
    alpha: Float = 0.96f,
): Color = (if (dark) colors.surfaceContainerHigh else colors.surfaceContainerLowest).copy(alpha = alpha)

/** Whisper-soft top-edge specular sheen in dark mode only; light mode relies purely on shadow. */
private fun floatingTopSheen(dark: Boolean): BorderStroke? = if (dark) {
    BorderStroke(
        width = 0.75.dp,
        brush = Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.18f),
            0.45f to Color.White.copy(alpha = 0.04f),
            1.0f to Color.Transparent,
        ),
    )
} else {
    null
}

@Composable
private fun AirQualityMapBottomCard(
    observation: AirQualityObservation?,
    isCustomSelection: Boolean,
    loading: Boolean,
    unavailableReason: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val zone = remember { ZoneId.of("Asia/Taipei") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }

    val cardColor = floatingOverlayColor(colors, dark, alpha = 0.96f)
    val cardShape = RoundedCornerShape(22.dp)
    val activeGlowColor = observation?.let { aqiTierTrackColor(aqiLevelIndex(it.aqi), dark) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .floatingMapShadow(
                shape = cardShape,
                dark = dark,
                elevation = 16.dp,
                glowColor = activeGlowColor,
            ),
        shape = cardShape,
        color = cardColor,
        border = floatingTopSheen(dark),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                loading && observation == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在取得鄰近測站 AQI", style = MaterialTheme.typography.bodyMedium)
                }
                observation != null -> {
                    AnimatedContent(
                        targetState = observation,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "aqiStationDetails",
                    ) { obs ->
                        val level = aqiLevelIndex(obs.aqi)
                        val tierText = aqiTierTextColor(level, dark)
                        val tierFill = aqiTierTrackColor(level, dark)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        "${obs.aqi}",
                                        style = MaterialTheme.typography.headlineLarge.copy(
                                            fontSize = 34.sp,
                                            lineHeight = 36.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFeatureSettings = "tnum",
                                        ),
                                        color = colors.onSurface,
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = tierFill.copy(alpha = if (dark) 0.22f else 0.14f),
                                    ) {
                                        Text(
                                            obs.status,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = tierText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "${obs.stationName}測站",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${if (isCustomSelection) "已選取" else "最近測站"} · ${timeFormatter.format(obs.observedAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    obs.primaryPollutant?.let { "指標污染物 $it" } ?: "點擊地圖測站查看詳情",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "半徑 ${AirQualityGridBuilder.MAX_INFLUENCE_KM.toInt()} km 內插",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                                )
                            }
                        }
                    }

                    AqiSpectrumBar(
                        aqi = observation.aqi,
                        dark = dark,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Air, null, modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        unavailableReason ?: "AQI 暫無資料",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AqiSpectrumBar(
    aqi: Int,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val level = aqiLevelIndex(aqi)
    val activeColor = aqiTierTrackColor(level, dark)
    val targetFraction = aqiLevelFraction(aqi)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "aqiMarkerFraction",
    )
    val surfaceColor = if (dark) colors.surfaceContainerHigh else colors.surfaceContainerLowest

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
        ) {
            val barHeight = 6.dp.toPx()
            val barTop = (size.height - barHeight) * 0.5f
            val corner = CornerRadius(barHeight * 0.5f, barHeight * 0.5f)
            val brush = Brush.horizontalGradient(
                0.00f to aqiTierTrackColor(0, dark),
                0.17f to aqiTierTrackColor(1, dark),
                0.34f to aqiTierTrackColor(2, dark),
                0.50f to aqiTierTrackColor(3, dark),
                0.67f to aqiTierTrackColor(4, dark),
                1.00f to aqiTierTrackColor(5, dark),
            )
            drawRoundRect(
                brush = brush,
                topLeft = Offset(0f, barTop),
                size = Size(size.width, barHeight),
                cornerRadius = corner,
            )

            val outerRadius = 6.5.dp.toPx()
            val innerRadius = 4.dp.toPx()
            val markerX = (animatedFraction * size.width).coerceIn(outerRadius, size.width - outerRadius)
            val center = Offset(markerX, size.height * 0.5f)
            drawCircle(color = Color.Black.copy(alpha = if (dark) 0.35f else 0.18f), radius = outerRadius + 0.8.dp.toPx(), center = center + Offset(0f, 0.8.dp.toPx()))
            drawCircle(color = surfaceColor, radius = outerRadius, center = center)
            drawCircle(color = activeColor, radius = innerRadius, center = center)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("0", "50", "100", "150", "200", "300+").forEach { tick ->
                Text(
                    tick,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                )
            }
        }
    }
}
