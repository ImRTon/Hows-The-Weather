@file:OptIn(com.google.maps.android.compose.MapsComposeExperimentalApi::class)

package com.rton.howstheweather.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapsInitializer
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
import com.google.maps.android.compose.rememberTileOverlayState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.rton.howstheweather.BuildConfig
import com.rton.howstheweather.R
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.TargetLocation
import kotlinx.coroutines.flow.filter

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
    }
}
