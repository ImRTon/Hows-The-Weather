@file:OptIn(com.google.maps.android.compose.MapsComposeExperimentalApi::class)

package com.rton.howstheweather.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.PatternItem
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberTileOverlayState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.rton.howstheweather.BuildConfig
import com.rton.howstheweather.R
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.TargetLocation
import kotlinx.coroutines.flow.filter
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Loads the Maps SDK module ahead of the first map view; repeated calls are cheap. */
internal fun warmUpGoogleMaps(context: Context) {
    MapsInitializer.initialize(context, MapsInitializer.Renderer.LATEST, null)
}

@Composable
internal fun GoogleWeatherMap(
    camera: WeatherMapCameraState,
    darkMap: Boolean,
    target: TargetLocation,
    cameraRequest: MapCameraRequest?,
    onCameraRequestHandled: () -> Unit,
    weatherLayers: List<WeatherTileLayer>,
    bottomContentPadding: Dp,
    onLongPress: (GeoPoint) -> Unit,
    onMapLoaded: () -> Unit,
    onCameraIdle: (MapCameraPosition) -> Unit,
    typhoon: TyphoonMapLayer?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val initialCamera = remember { camera.position }
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(initialCamera.center.latitude, initialCamera.center.longitude),
            initialCamera.zoom,
        )
    }
    DisposableEffect(camera, cameraState) {
        camera.bindLive {
            val position = cameraState.position
            MapCameraPosition(GeoPoint(position.target.latitude, position.target.longitude), position.zoom)
        }
        onDispose { camera.unbindLive() }
    }
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.isMoving }
            .filter { !it }
            .collect {
                val position = cameraState.position
                currentOnCameraIdle(
                    MapCameraPosition(GeoPoint(position.target.latitude, position.target.longitude), position.zoom),
                )
            }
    }
    val configuredMapId = BuildConfig.MAP_ID.trim()
    val usesBundledMapStyle = configuredMapId.isBlank() || configuredMapId == "DEMO_MAP_ID"
    val fallbackStyle = remember(darkMap, usesBundledMapStyle) {
        if (usesBundledMapStyle) {
            MapStyleOptions.loadRawResourceStyle(context, if (darkMap) R.raw.map_style_dark else R.raw.map_style_light)
        } else null
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraState,
        googleMapOptionsFactory = {
            GoogleMapOptions().apply {
                if (!usesBundledMapStyle) {
                    mapId(configuredMapId)
                }
            }
        },
        properties = MapProperties(mapStyleOptions = fallbackStyle),
        // Keep the Maps SDK attribution clear of our bottom controls and legend.
        // The Android Maps SDK fixes the logo to the start edge and only exposes
        // padding for moving its built-in attribution away from overlapping UI.
        contentPadding = PaddingValues(bottom = bottomContentPadding),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
        ),
        onMapLongClick = { onLongPress(GeoPoint(it.latitude, it.longitude)) },
        onMapLoaded = onMapLoaded,
    ) {
        MapEffect(cameraRequest) { googleMap ->
            val request = cameraRequest ?: return@MapEffect
            googleMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(request.point.latitude, request.point.longitude),
                    request.zoom,
                ),
                650,
                null,
            )
            onCameraRequestHandled()
        }
        weatherLayers.forEach { layer ->
            key(layer.key) {
                val overlayState = rememberTileOverlayState()
                TileOverlay(
                    tileProvider = layer.provider,
                    state = overlayState,
                    transparency = layer.transparency,
                    fadeIn = layer.fadeIn,
                    zIndex = layer.zIndex,
                    visible = layer.visible,
                )
                val layerState = layer.state
                if (layerState != null) {
                    // TileOverlayState is attached only after TileOverlay enters the
                    // map composition, and effects run after that composition applies.
                    DisposableEffect(layerState, overlayState) {
                        layerState.bind { overlayState.clearTileCache() }
                        onDispose { layerState.unbind() }
                    }
                }
            }
        }
        Marker(
            state = rememberUpdatedMarkerState(LatLng(target.coordinate.latitude, target.coordinate.longitude)),
            title = target.displayName,
        )
        if (typhoon != null) GoogleTyphoonLayer(typhoon, cameraState)
    }
}

/**
 * Typhoon geometry as native map objects, so the Maps renderer moves it in the
 * same frame as the base map. Only small animated properties recompose.
 */
@Composable
@GoogleMapComposable
private fun GoogleTyphoonLayer(layer: TyphoonMapLayer, cameraState: CameraPositionState) {
    val density = LocalDensity.current
    val palette = remember(layer.darkMap) { typhoonPalette(layer.darkMap) }
    val styles = remember(palette) { typhoonLabelStyles(palette) }
    val textMeasurer = rememberTextMeasurer()
    val geometries = remember(layer.cyclones) { layer.cyclones.map(::cycloneGeometry) }
    // Every animated property change redraws the whole Google map, so animate at a
    // lower rate and hold still while the camera moves to leave gestures the GPU.
    val seconds = rememberTyphoonSeconds(
        frameIntervalMillis = GOOGLE_TYPHOON_FRAME_INTERVAL_MILLIS,
        paused = { cameraState.isMoving },
    )
    geometries.forEach { geometry ->
        key(geometry) {
            GoogleCyclone(geometry, palette, styles, textMeasurer, density, seconds, cameraState)
        }
    }
}

@Composable
@GoogleMapComposable
private fun GoogleCyclone(
    geometry: CycloneGeometry,
    palette: TyphoonPalette,
    styles: TyphoonLabelStyles,
    textMeasurer: TextMeasurer,
    density: Density,
    seconds: State<Float>,
    cameraState: CameraPositionState,
) {
    val shapes = remember(geometry) { GoogleCycloneShapes(geometry) }
    val icons = remember(geometry, palette, styles, density) {
        GoogleCycloneIcons(geometry, palette, styles, textMeasurer, density)
    }
    fun px(dp: Float) = dp * density.density
    shapes.probabilityArea.forEach { (outline, holes) ->
        Polygon(
            points = outline,
            holes = holes,
            fillColor = palette.probabilityFill,
            strokeColor = palette.probabilityEdge,
            strokeWidth = px(TYPHOON_PROBABILITY_EDGE_DP),
            zIndex = TYPHOON_Z_PROBABILITY,
        )
    }
    shapes.gale?.let { gale ->
        val dash = px(TYPHOON_GALE_DASH_DP)
        MarchingDashPolygon(
            points = gale,
            fillColor = palette.galeFill,
            strokeColor = palette.galeEdge,
            strokeWidth = px(TYPHOON_GALE_EDGE_DP),
            dash = dash,
            gap = dash * TYPHOON_GALE_GAP_RATIO,
            speed = TYPHOON_GALE_DASH_SPEED,
            seconds = seconds,
            zIndex = TYPHOON_Z_GALE,
        )
    }
    shapes.storm?.let { storm ->
        Polygon(
            points = storm,
            fillColor = palette.stormFill,
            strokeColor = palette.stormEdge,
            strokeWidth = px(TYPHOON_STORM_EDGE_DP),
            zIndex = TYPHOON_Z_STORM,
        )
    }
    if (shapes.track.size > 1) {
        Polyline(
            points = shapes.track,
            color = palette.track.copy(alpha = TYPHOON_TRACK_ALPHA),
            width = px(TYPHOON_TRACK_WIDTH_DP),
            startCap = ROUND_CAP,
            endCap = ROUND_CAP,
            jointType = JointType.ROUND,
            zIndex = TYPHOON_Z_TRACK,
        )
    }
    if (shapes.forecast.size > 1) {
        val dash = px(TYPHOON_FORECAST_DASH_DP)
        MarchingDashPolyline(
            points = shapes.forecast,
            color = palette.track,
            width = px(TYPHOON_TRACK_WIDTH_DP),
            dash = dash,
            gap = dash * TYPHOON_FORECAST_GAP_RATIO,
            speed = TYPHOON_FORECAST_DASH_SPEED,
            seconds = seconds,
            zIndex = TYPHOON_Z_FORECAST,
        )
    }
    shapes.track.dropLast(1).forEach { point ->
        DecorationMarker(point, icons.trackDot, CENTER_ANCHOR, TYPHOON_MARKER_Z_DOT)
    }
    shapes.forecast.drop(1).forEach { point ->
        DecorationMarker(point, icons.forecastDot, CENTER_ANCHOR, TYPHOON_MARKER_Z_DOT)
    }
    shapes.forecastLabels.forEachIndexed { index, point ->
        val label = icons.forecastLabels[index]
        DecorationMarker(point, label.descriptor, label.anchor, TYPHOON_MARKER_Z_LABEL)
    }
    repeat(TYPHOON_PULSE_COUNT) { index ->
        PulseCircle(
            center = shapes.center,
            index = index,
            glyphRadiusPx = px(geometry.glyphRadiusDp),
            color = palette.glyph,
            strokeWidth = px(TYPHOON_PULSE_STROKE_DP),
            density = density.density,
            seconds = seconds,
            cameraState = cameraState,
        )
    }
    GlyphMarker(shapes.center, icons.glyph, geometry.southernHemisphere, seconds)
    DecorationMarker(
        position = shapes.center,
        icon = icons.name.descriptor,
        anchor = icons.name.anchor,
        zIndex = TYPHOON_MARKER_Z_NAME,
        contentDescription = geometry.shortName,
    )
}

private class GoogleCycloneShapes(geometry: CycloneGeometry) {
    val center = geometry.center.toLatLng()
    val probabilityArea = geometry.probabilityArea.map { polygon ->
        polygon.outline.map(GeoPoint::toLatLng) to polygon.holes.map { hole -> hole.map(GeoPoint::toLatLng) }
    }
    val gale = geometry.gale?.map(GeoPoint::toLatLng)
    val storm = geometry.storm?.map(GeoPoint::toLatLng)
    val track = geometry.track.map(GeoPoint::toLatLng)
    val forecast = geometry.forecast.map(GeoPoint::toLatLng)
    val forecastLabels = geometry.forecastLabels.map { (point, _) -> point.toLatLng() }
}

private class AnchoredIcon(val descriptor: BitmapDescriptor, val anchor: Offset)

/** Screen-sized decorations rendered once with the same drawing code as the canvas backend. */
private class GoogleCycloneIcons(
    geometry: CycloneGeometry,
    palette: TyphoonPalette,
    styles: TyphoonLabelStyles,
    textMeasurer: TextMeasurer,
    density: Density,
) {
    val trackDot = with(density) {
        val size = 2f * TYPHOON_TRACK_DOT_DP.dp.toPx() + 2f
        centeredIcon(size, density) { drawTrackDot(center, palette) }
    }
    val forecastDot = with(density) {
        val size = 2f * (TYPHOON_FORECAST_DOT_DP + TYPHOON_FORECAST_DOT_STROKE_DP / 2f).dp.toPx() + 2f
        centeredIcon(size, density) { drawForecastDot(center, palette) }
    }
    // The spiral rotates through the marker's rotation, so the bitmap is drawn once at rest.
    val glyph = with(density) {
        val radius = geometry.glyphRadiusDp.dp.toPx()
        centeredIcon(radius * 2.4f, density) { drawStormGlyph(center, radius, geometry, palette, seconds = 0f) }
    }
    val forecastLabels = geometry.forecastLabels.map { (_, text) ->
        labelIcon(text, styles.label, density.forecastLabelOffset(), textMeasurer, density)
    }
    val name = labelIcon(
        text = geometry.shortName,
        style = styles.name,
        offset = nameLabelOffset(with(density) { geometry.glyphRadiusDp.dp.toPx() }),
        textMeasurer = textMeasurer,
        density = density,
    )
}

private fun centeredIcon(size: Float, density: Density, draw: DrawScope.() -> Unit): BitmapDescriptor {
    val pixels = ceil(size).toInt().coerceAtLeast(1)
    return renderIcon(pixels, pixels, density, draw)
}

/** A text label whose top-left sits at [offset] pixels from the marker position. */
private fun labelIcon(
    text: String,
    style: TextStyle,
    offset: Offset,
    textMeasurer: TextMeasurer,
    density: Density,
): AnchoredIcon {
    val layout = textMeasurer.measure(text, style, density = density)
    val padding = LABEL_HALO_PADDING_PX
    val left = min(0f, offset.x - padding)
    val top = min(0f, offset.y - padding)
    val width = ceil(max(0f, offset.x + layout.size.width + padding) - left).toInt().coerceAtLeast(1)
    val height = ceil(max(0f, offset.y + layout.size.height + padding) - top).toInt().coerceAtLeast(1)
    val descriptor = renderIcon(width, height, density) {
        drawText(layout, topLeft = Offset(offset.x - left, offset.y - top))
    }
    return AnchoredIcon(descriptor, Offset(-left / width, -top / height))
}

private fun renderIcon(width: Int, height: Int, density: Density, draw: DrawScope.() -> Unit): BitmapDescriptor {
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat()), draw)
    return BitmapDescriptorFactory.fromBitmap(image.asAndroidBitmap())
}

@Composable
@GoogleMapComposable
private fun DecorationMarker(
    position: LatLng,
    icon: BitmapDescriptor,
    anchor: Offset,
    zIndex: Float,
    contentDescription: String? = null,
) {
    Marker(
        state = rememberUpdatedMarkerState(position),
        contentDescription = contentDescription,
        icon = icon,
        anchor = anchor,
        zIndex = zIndex,
        // Consume taps so the SDK does not recenter the camera on a decoration.
        onClick = { true },
    )
}

@Composable
@GoogleMapComposable
private fun GlyphMarker(position: LatLng, icon: BitmapDescriptor, southernHemisphere: Boolean, seconds: State<Float>) {
    val degrees = Math.toDegrees(typhoonGlyphRotationRadians(southernHemisphere, seconds.value).toDouble())
    Marker(
        state = rememberUpdatedMarkerState(position),
        contentDescription = null,
        icon = icon,
        anchor = CENTER_ANCHOR,
        rotation = (((degrees % 360.0) + 360.0) % 360.0).toFloat(),
        zIndex = TYPHOON_MARKER_Z_GLYPH,
        onClick = { true },
    )
}

/** Pulse rings keep a fixed screen size, so their ground radius follows the zoom. */
@Composable
@GoogleMapComposable
private fun PulseCircle(
    center: LatLng,
    index: Int,
    glyphRadiusPx: Float,
    color: Color,
    strokeWidth: Float,
    density: Float,
    seconds: State<Float>,
    cameraState: CameraPositionState,
) {
    val phase = typhoonPulsePhase(index, seconds.value)
    val metersPerPixel = metersPerPixel(center.latitude, cameraState.position.zoom, density)
    Circle(
        center = center,
        radius = glyphRadiusPx * typhoonPulseScale(phase) * metersPerPixel,
        fillColor = Color.Transparent,
        strokeColor = color.copy(alpha = typhoonPulseAlpha(phase)),
        strokeWidth = strokeWidth,
        zIndex = TYPHOON_Z_PULSE,
    )
}

@Composable
@GoogleMapComposable
private fun MarchingDashPolyline(
    points: List<LatLng>,
    color: Color,
    width: Float,
    dash: Float,
    gap: Float,
    speed: Float,
    seconds: State<Float>,
    zIndex: Float,
) {
    Polyline(
        points = points,
        color = color,
        width = width,
        jointType = JointType.ROUND,
        pattern = marchingDashPattern(dash, gap, typhoonDashOffset(dash, gap, speed, seconds.value)),
        zIndex = zIndex,
    )
}

@Composable
@GoogleMapComposable
private fun MarchingDashPolygon(
    points: List<LatLng>,
    fillColor: Color,
    strokeColor: Color,
    strokeWidth: Float,
    dash: Float,
    gap: Float,
    speed: Float,
    seconds: State<Float>,
    zIndex: Float,
) {
    Polygon(
        points = points,
        fillColor = fillColor,
        strokeColor = strokeColor,
        strokeWidth = strokeWidth,
        strokePattern = marchingDashPattern(dash, gap, typhoonDashOffset(dash, gap, speed, seconds.value)),
        zIndex = zIndex,
    )
}

/** A dash/gap pattern shifted forward by [offset] pixels; patterns have no phase of their own. */
private fun marchingDashPattern(dash: Float, gap: Float, offset: Float): List<PatternItem> =
    marchingDashSegments(dash, gap, offset).map { (isDash, length) -> if (isDash) Dash(length) else Gap(length) }

private fun metersPerPixel(latitude: Double, zoom: Float, density: Float): Double =
    EARTH_CIRCUMFERENCE_METERS * cos(Math.toRadians(latitude)) / (256.0 * density * 2.0.pow(zoom.toDouble()))

private fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

private val ROUND_CAP = RoundCap()
private val CENTER_ANCHOR = Offset(.5f, .5f)
private const val LABEL_HALO_PADDING_PX = 8f
private const val GOOGLE_TYPHOON_FRAME_INTERVAL_MILLIS = 1_000L / 20L
private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686

// Weather tiles use zIndex 2; typhoon shapes sit above them. Markers always draw above
// shapes, and negative values keep the selected-target marker on top of decorations.
private const val TYPHOON_Z_PROBABILITY = 3f
private const val TYPHOON_Z_GALE = 3.1f
private const val TYPHOON_Z_STORM = 3.2f
private const val TYPHOON_Z_TRACK = 3.3f
private const val TYPHOON_Z_FORECAST = 3.4f
private const val TYPHOON_Z_PULSE = 3.5f
private const val TYPHOON_MARKER_Z_DOT = -4f
private const val TYPHOON_MARKER_Z_LABEL = -3f
private const val TYPHOON_MARKER_Z_GLYPH = -2f
private const val TYPHOON_MARKER_Z_NAME = -1f
