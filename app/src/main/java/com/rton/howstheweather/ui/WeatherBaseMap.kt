package com.rton.howstheweather.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.rton.howstheweather.BuildConfig
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.TargetLocation
import com.rton.howstheweather.render.WeatherTileProvider

/** Base map SDK chosen at build time through `MAP_PROVIDER` in `.env` or local.properties. */
internal enum class MapProvider(val displayName: String) {
    GOOGLE("Google 地圖"),
    OSM("OpenStreetMap");

    companion object {
        fun fromConfig(value: String): MapProvider = when (value.trim().lowercase()) {
            "osm", "openstreetmap", "open_street_map" -> OSM
            else -> GOOGLE
        }
    }
}

internal val configuredMapProvider: MapProvider = MapProvider.fromConfig(BuildConfig.MAP_PROVIDER)

internal data class MapCameraPosition(val center: GeoPoint, val zoom: Float)

internal data class MapCameraRequest(val point: GeoPoint, val zoom: Float)

/**
 * Provider-neutral camera. Google Maps binds a live reader so draw-phase consumers
 * such as the wind overlay see every camera frame; osmdroid pushes updates.
 */
@Stable
internal class WeatherMapCameraState(initial: MapCameraPosition) {
    private var stored by mutableStateOf(initial)
    private var live by mutableStateOf<(() -> MapCameraPosition)?>(null)

    val position: MapCameraPosition
        get() = live?.invoke() ?: stored

    internal fun bindLive(reader: () -> MapCameraPosition) {
        live = reader
    }

    internal fun unbindLive() {
        stored = position
        live = null
    }

    internal fun update(position: MapCameraPosition) {
        stored = position
    }
}

/** Handle for invalidating one weather tile overlay once it is attached to the map. */
@Stable
internal class WeatherTileLayerState {
    var isAttached by mutableStateOf(false)
        private set
    private var clearAction: (() -> Unit)? = null

    fun clearTileCache() {
        checkNotNull(clearAction) { "Weather tile layer is not attached to a map" }.invoke()
    }

    internal fun bind(clear: () -> Unit) {
        clearAction = clear
        isAttached = true
    }

    internal fun unbind() {
        clearAction = null
        isAttached = false
    }
}

internal data class WeatherTileLayer(
    val key: String,
    val provider: WeatherTileProvider,
    val transparency: Float,
    val fadeIn: Boolean,
    val state: WeatherTileLayerState? = null,
    val zIndex: Float = 2f,
)

@Composable
internal fun WeatherBaseMap(
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
    when (configuredMapProvider) {
        MapProvider.GOOGLE -> GoogleWeatherMap(
            camera = camera,
            darkMap = darkMap,
            target = target,
            cameraRequest = cameraRequest,
            onCameraRequestHandled = onCameraRequestHandled,
            weatherLayers = weatherLayers,
            bottomContentPadding = bottomContentPadding,
            onLongPress = onLongPress,
            onMapLoaded = onMapLoaded,
            onCameraIdle = onCameraIdle,
            modifier = modifier,
        )
        MapProvider.OSM -> OsmWeatherMap(
            camera = camera,
            darkMap = darkMap,
            target = target,
            cameraRequest = cameraRequest,
            onCameraRequestHandled = onCameraRequestHandled,
            weatherLayers = weatherLayers,
            bottomContentPadding = bottomContentPadding,
            onLongPress = onLongPress,
            onMapLoaded = onMapLoaded,
            onCameraIdle = onCameraIdle,
            modifier = modifier,
        )
    }
}
