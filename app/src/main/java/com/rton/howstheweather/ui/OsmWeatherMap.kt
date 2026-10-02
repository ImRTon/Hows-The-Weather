package com.rton.howstheweather.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rton.howstheweather.BuildConfig
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.TargetLocation
import com.rton.howstheweather.render.WeatherTileProvider
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.util.GeoPoint as OsmGeoPoint

@OptIn(FlowPreview::class)
@Composable
internal fun OsmWeatherMap(
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnMapLoaded by rememberUpdatedState(onMapLoaded)
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)
    val loadingColor = MaterialTheme.colorScheme.background.toArgb()

    val resources = LocalResources.current
    val lightSource = remember { TemplateTileSource("osm-light", BuildConfig.OSM_TILE_URL_LIGHT, resources) }
    val darkSource = remember { TemplateTileSource("osm-dark", BuildConfig.OSM_TILE_URL_DARK, resources) }
    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setTileSource(if (darkMap) darkSource else lightSource)
            // Matches the Google Maps convention of a 256 dp world at zoom 0,
            // so zoom thresholds and the wind overlay stay provider-neutral.
            isTilesScaledToDpi = true
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(
                MapView.getTileSystem().maxLatitude,
                MapView.getTileSystem().minLatitude,
                0,
            )
            setMinZoomLevel(OSM_MIN_ZOOM)
            setMaxZoomLevel(OSM_MAX_ZOOM)
            val initial = camera.position
            controller.setZoom(initial.zoom.toDouble())
            controller.setCenter(OsmGeoPoint(initial.center.latitude, initial.center.longitude))
        }
    }
    val marker = remember {
        Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }
    }
    val weatherOverlays = remember { OsmWeatherOverlays(context, mapView) }

    DisposableEffect(mapView) {
        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: OsmGeoPoint): Boolean = false
            override fun longPressHelper(point: OsmGeoPoint): Boolean {
                currentOnLongPress(GeoPoint(point.latitude, point.longitude))
                return true
            }
        })
        mapView.overlays.add(0, eventsOverlay)
        mapView.overlays.add(marker)
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                camera.update(mapView.cameraPosition())
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                camera.update(mapView.cameraPosition())
                return false
            }
        }
        mapView.addMapListener(listener)
        var loaded = false
        val loadedHandler = Handler(Looper.getMainLooper()) { message ->
            if (!loaded && message.what == MapTileProviderBase.MAPTILE_SUCCESS_ID) {
                loaded = true
                currentOnMapLoaded()
            }
            false
        }
        mapView.tileProvider.tileRequestCompleteHandlers.add(loadedHandler)
        onDispose {
            mapView.tileProvider.tileRequestCompleteHandlers.remove(loadedHandler)
            mapView.removeMapListener(listener)
            weatherOverlays.detachAll()
            mapView.onDetach()
        }
    }
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        // A newly added observer receives ON_RESUME immediately when already resumed.
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(camera) {
        snapshotFlow { camera.position }
            .drop(1)
            .debounce(OSM_CAMERA_IDLE_MILLIS)
            .collect { currentOnCameraIdle(it) }
    }
    LaunchedEffect(cameraRequest) {
        val request = cameraRequest ?: return@LaunchedEffect
        mapView.controller.animateTo(
            OsmGeoPoint(request.point.latitude, request.point.longitude),
            request.zoom.toDouble(),
            650L,
        )
        onCameraRequestHandled()
    }
    SideEffect {
        val source = if (darkMap) darkSource else lightSource
        if (mapView.tileProvider.tileSource !== source) mapView.setTileSource(source)
        mapView.overlayManager.tilesOverlay.apply {
            if (loadingBackgroundColor != loadingColor) {
                loadingBackgroundColor = loadingColor
                loadingLineColor = loadingColor
            }
        }
        val markerPosition = OsmGeoPoint(target.coordinate.latitude, target.coordinate.longitude)
        if (marker.position != markerPosition || marker.title != target.displayName) {
            marker.position = markerPosition
            marker.title = target.displayName
            mapView.invalidate()
        }
        weatherOverlays.sync(weatherLayers, insertBefore = marker)
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Text(
            BuildConfig.OSM_TILE_ATTRIBUTION,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = bottomContentPadding + 4.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .8f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun MapView.cameraPosition(): MapCameraPosition {
    val center = mapCenter
    return MapCameraPosition(GeoPoint(center.latitude, center.longitude), zoomLevelDouble.toFloat())
}

private fun configureOsmdroid(context: Context) {
    Configuration.getInstance().apply {
        // Tile servers such as OSM and CARTO require an identifying User-Agent.
        userAgentValue = BuildConfig.APPLICATION_ID
        osmdroidBasePath = File(context.filesDir, "osmdroid")
        osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    }
}

/** Keeps one osmdroid [TilesOverlay] per [WeatherTileLayer], mirroring Compose TileOverlay slots. */
private class OsmWeatherOverlays(private val context: Context, private val mapView: MapView) {
    private val overlays = LinkedHashMap<String, OsmWeatherOverlay>()

    fun sync(layers: List<WeatherTileLayer>, insertBefore: Marker) {
        var orderChanged = false
        val wanted = layers.associateBy { it.key }
        overlays.keys.filter { it !in wanted }.forEach { key ->
            overlays.remove(key)?.detach()
            orderChanged = true
        }
        layers.forEach { layer ->
            val existing = overlays[layer.key]
            val overlay = if (existing == null || existing.provider !== layer.provider) {
                existing?.detach()
                OsmWeatherOverlay(context, mapView, layer.provider).also {
                    overlays[layer.key] = it
                    orderChanged = true
                }
            } else existing
            overlay.bindState(layer.state)
            overlay.setAlpha(1f - layer.transparency)
            overlay.setVisible(layer.visible)
        }
        if (orderChanged) {
            val all = mapView.overlays
            all.removeAll { overlay -> overlay is TilesOverlay && overlay !== mapView.overlayManager.tilesOverlay }
            val ordered = layers.sortedBy { it.zIndex }.mapNotNull { overlays[it.key]?.overlay }
            val index = all.indexOf(insertBefore).takeIf { it >= 0 } ?: all.size
            all.addAll(index, ordered)
            mapView.invalidate()
        }
    }

    fun detachAll() {
        mapView.overlays.removeAll(overlays.values.map { it.overlay }.toSet())
        overlays.values.forEach { it.detach() }
        overlays.clear()
    }
}

private class OsmWeatherOverlay(context: Context, private val mapView: MapView, val provider: WeatherTileProvider) {
    private val tileProvider = MapTileProviderArray(
        XYTileSource("weather", 0, OSM_MAX_ZOOM.toInt(), WeatherTileProvider.TILE_SIZE, ".png", arrayOf("")),
        null,
        arrayOf(WeatherTileModule(provider, context.resources)),
    ).apply { tileRequestCompleteHandlers.add(mapView.tileRequestCompleteHandler) }
    val overlay = TilesOverlay(tileProvider, context, true, false).apply {
        loadingBackgroundColor = android.graphics.Color.TRANSPARENT
        loadingLineColor = android.graphics.Color.TRANSPARENT
    }
    private var boundState: WeatherTileLayerState? = null
    private var alpha = -1f

    fun bindState(state: WeatherTileLayerState?) {
        if (state === boundState) return
        boundState?.unbind()
        boundState = state
        state?.bind(::clearTileCache)
    }

    fun setAlpha(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (abs(clamped - alpha) < .002f) return
        alpha = clamped
        overlay.setColorFilter(
            ColorMatrixColorFilter(ColorMatrix().apply { setScale(1f, 1f, 1f, clamped) }),
        )
        mapView.invalidate()
    }

    fun setVisible(value: Boolean) {
        if (overlay.isEnabled == value) return
        overlay.isEnabled = value
        mapView.invalidate()
    }

    private fun clearTileCache() {
        tileProvider.clearTileCache()
        mapView.invalidate()
    }

    fun detach() {
        boundState?.unbind()
        boundState = null
        overlay.onDetach(mapView)
    }
}

/** Serves [WeatherTileProvider] PNGs to osmdroid on its background loader threads. */
private class WeatherTileModule(
    private val provider: WeatherTileProvider,
    private val resources: Resources,
) : MapTileModuleProviderBase(WEATHER_TILE_THREADS, WEATHER_TILE_PENDING_QUEUE) {
    override fun getName(): String = "Weather tiles"
    override fun getThreadGroupName(): String = "weather-tiles"
    override fun getTileLoader(): TileLoader = Loader()
    override fun getUsesDataConnection(): Boolean = false
    override fun getMinimumZoomLevel(): Int = 0
    override fun getMaximumZoomLevel(): Int = OSM_MAX_ZOOM.toInt()
    override fun setTileSource(tileSource: ITileSource?) = Unit

    private inner class Loader : TileLoader() {
        override fun loadTile(index: Long): Drawable {
            val bytes = provider.tilePng(
                MapTileIndex.getX(index),
                MapTileIndex.getY(index),
                MapTileIndex.getZoom(index),
            ) ?: return emptyTile
            // A cached empty drawable keeps osmdroid from re-requesting dry tiles every frame.
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyTile
            return BitmapDrawable(resources, bitmap)
        }
    }

    private val emptyTile: Drawable by lazy {
        BitmapDrawable(resources, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    }
}

/** Raster tile source from a `{z}/{x}/{y}` URL template configured at build time. */
private class TemplateTileSource(
    name: String,
    private val template: String,
    private val resources: Resources,
) : OnlineTileSourceBase(
    "$name-${template.hashCode().toUInt().toString(16)}",
    0,
    OSM_MAX_ZOOM.toInt(),
    256,
    ".png",
    arrayOf(template),
) {
    override fun getTileURLString(index: Long): String = expandTileUrlTemplate(
        template,
        MapTileIndex.getZoom(index),
        MapTileIndex.getX(index),
        MapTileIndex.getY(index),
    )

    // High-DPI (@2x) tiles are larger than the 256 px pooled bitmaps osmdroid
    // would otherwise try to reuse, so decode them without a reusable bitmap.
    override fun getDrawable(stream: InputStream): Drawable? =
        BitmapFactory.decodeStream(stream)?.let { BitmapDrawable(resources, it) }

    override fun getDrawable(filePath: String): Drawable? =
        BitmapFactory.decodeFile(filePath)?.let { BitmapDrawable(resources, it) }
}

internal fun expandTileUrlTemplate(template: String, zoom: Int, x: Int, y: Int): String {
    val subdomain = TILE_SUBDOMAINS[Math.floorMod(x + y, TILE_SUBDOMAINS.length)]
    return template
        .replace("{z}", zoom.toString())
        .replace("{x}", x.toString())
        .replace("{y}", y.toString())
        .replace("{s}", subdomain.toString())
        .replace("{r}", "@2x")
}

private const val TILE_SUBDOMAINS = "abcd"
private const val OSM_MIN_ZOOM = 3.0
private const val OSM_MAX_ZOOM = 20.0
private const val OSM_CAMERA_IDLE_MILLIS = 300L
private const val WEATHER_TILE_THREADS = 3
private const val WEATHER_TILE_PENDING_QUEUE = 64
