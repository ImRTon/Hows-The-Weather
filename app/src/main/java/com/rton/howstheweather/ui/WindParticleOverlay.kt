package com.rton.howstheweather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WindGrid
import com.rton.howstheweather.domain.WindObservation
import com.rton.howstheweather.domain.travelComponents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * [camera] is read only from the draw phase and background snapshot flows, so a
 * moving Maps camera invalidates this Canvas instead of recomposing the map.
 */
@Composable
internal fun WindParticleOverlay(
    windGrid: WindGrid?,
    winds: List<WindObservation>,
    camera: () -> WindParticleCamera,
    darkMap: Boolean,
    modifier: Modifier = Modifier,
) {
    if (windGrid == null && winds.isEmpty()) return
    var frameNanos by remember { mutableLongStateOf(0L) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var layout by remember { mutableStateOf<WindParticleLayout?>(null) }
    val density = LocalDensity.current.density
    val currentCamera by rememberUpdatedState(camera)
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos = it }
            delay(WIND_FRAME_INTERVAL_MILLIS)
        }
    }
    LaunchedEffect(windGrid, winds, canvasSize, density, darkMap) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) {
            layout = null
            return@LaunchedEffect
        }
        val width = canvasSize.width.toFloat()
        val height = canvasSize.height.toFloat()
        var latestRequest = 0
        suspend fun rebuild(target: WindParticleCamera) {
            val request = ++latestRequest
            val built = withContext(Dispatchers.Default) {
                val observations = selectRelevantWinds(winds, target.center, MAX_RENDER_OBSERVATIONS)
                    .map(::asVector)
                WindParticleLayout(
                    camera = target,
                    particles = buildWindParticles(
                        windGrid = windGrid,
                        observations = observations,
                        mapCenter = target.center,
                        mapZoom = target.zoom,
                        width = width,
                        height = height,
                        density = density,
                        darkMap = darkMap,
                    ),
                )
            }
            // Two collectors can build concurrently; never let an older camera win.
            if (request == latestRequest) layout = built
        }
        rebuild(currentCamera())
        launch {
            // During a gesture, rebuild only when the overscanned lattice is about
            // to run out; conflation drops camera frames that arrive mid-build.
            snapshotFlow { currentCamera() }
                .conflate()
                .collect { target ->
                    val current = layout
                    if (current == null ||
                        windParticleLayoutNeedsRebuild(current.camera, target, width, height, density)
                    ) rebuild(target)
                }
        }
        // Once the camera settles, refresh so lattice crossfade and sampling match exactly.
        snapshotFlow { currentCamera() }.collectLatest { target ->
            delay(WIND_SETTLE_REBUILD_MILLIS)
            if (layout?.camera != target) rebuild(target)
        }
    }
    Canvas(
        modifier.onSizeChanged { size ->
            if (canvasSize != size) canvasSize = size
        },
    ) {
        val current = layout ?: return@Canvas
        val timeSeconds = frameNanos / 1_000_000_000.0
        val cameraTransform = windParticleCameraTransform(
            reference = current.camera,
            current = currentCamera(),
            density = density,
        )
        val canvasCenter = Offset(size.width / 2f, size.height / 2f)
        clipRect(0f, 0f, size.width, size.height) {
            current.particles.forEach { particle ->
                val base = canvasCenter +
                    (particle.base - canvasCenter) * cameraTransform.scale +
                    cameraTransform.translation
                val reach = (particle.travelDistance + particle.length) * cameraTransform.scale
                if (base.x < -reach || base.x > size.width + reach ||
                    base.y < -reach || base.y > size.height + reach
                ) return@forEach
                val phase = (
                    (timeSeconds * (.22 + particle.speed * .035) + particle.phaseOffset) % 1.0
                    ).toFloat()
                val head = base +
                    particle.direction * (particle.travelDistance * phase * cameraTransform.scale)
                drawComet(
                    head = head,
                    direction = particle.direction,
                    length = particle.length * cameraTransform.scale,
                    color = particle.color,
                    alpha = windParticleAlpha(phase) * particle.layoutAlpha,
                    scale = windParticleScale(phase),
                    density = density,
                )
            }
        }
    }
}

internal data class WindParticleCamera(
    val center: GeoPoint,
    val zoom: Float,
)

internal data class WindParticleCameraTransform(
    val scale: Float,
    val translation: Offset,
)

private class WindParticleLayout(
    val camera: WindParticleCamera,
    val particles: List<RenderWindParticle>,
)

/**
 * A layout covers the viewport plus [WIND_LAYOUT_OVERSCAN] on every side. It is
 * rebuilt before a pan or zoom-out exposes its uncovered edge, or when the zoom
 * has drifted enough that the lattice crossfade weights are visibly stale.
 */
internal fun windParticleLayoutNeedsRebuild(
    reference: WindParticleCamera,
    current: WindParticleCamera,
    width: Float,
    height: Float,
    density: Float,
): Boolean {
    if (kotlin.math.abs(current.zoom - reference.zoom) >= WIND_LAYOUT_MAX_ZOOM_DRIFT) return true
    val transform = windParticleCameraTransform(reference, current, density)
    val slack = WIND_LAYOUT_OVERSCAN * .5f
    val centerX = width / 2f
    val centerY = height / 2f
    fun layoutX(screenX: Float) = centerX + (screenX - centerX - transform.translation.x) / transform.scale
    fun layoutY(screenY: Float) = centerY + (screenY - centerY - transform.translation.y) / transform.scale
    return layoutX(0f) < -width * slack ||
        layoutX(width) > width * (1f + slack) ||
        layoutY(0f) < -height * slack ||
        layoutY(height) > height * (1f + slack)
}

/**
 * Reprojects the most recent particle layout while the Maps camera is moving.
 *
 * This keeps existing seeds and animation phases alive during a gesture. Sampling and lattice
 * construction run in the background only when the overscanned layout is exhausted.
 */
internal fun windParticleCameraTransform(
    reference: WindParticleCamera,
    current: WindParticleCamera,
    density: Float,
): WindParticleCameraTransform {
    val referenceWorldSize = worldSizePixels(reference.zoom, density)
    var longitudeDelta = current.center.longitude - reference.center.longitude
    if (longitudeDelta > 180.0) longitudeDelta -= 360.0
    if (longitudeDelta < -180.0) longitudeDelta += 360.0
    val centerDeltaX = longitudeDelta / 360.0 * referenceWorldSize
    val centerDeltaY = (
        mercatorY(current.center.latitude) - mercatorY(reference.center.latitude)
        ) * referenceWorldSize
    val scale = 2.0.pow((current.zoom - reference.zoom).toDouble()).toFloat()
    return WindParticleCameraTransform(
        scale = scale,
        translation = Offset(
            x = (-centerDeltaX * scale).toFloat(),
            y = (-centerDeltaY * scale).toFloat(),
        ),
    )
}

private class RenderWindParticle(
    val phaseOffset: Double,
    val base: Offset,
    val speed: Float,
    val direction: Offset,
    val travelDistance: Float,
    val length: Float,
    val layoutAlpha: Float,
    val color: Color,
)

private fun buildWindParticles(
    windGrid: WindGrid?,
    observations: List<WindVector>,
    mapCenter: GeoPoint,
    mapZoom: Float,
    width: Float,
    height: Float,
    density: Float,
    darkMap: Boolean,
): List<RenderWindParticle> {
    if (width <= 0f || height <= 0f) return emptyList()
    return buildList {
        windParticleLayoutLayers(mapZoom).forEach { layout ->
            val layoutZoom = layout.zoom.toFloat()
            val worldSize = worldSizePixels(layoutZoom, density)
            val screenScale = 2.0.pow((mapZoom - layoutZoom).toDouble()).toFloat()
            val spacing = windParticleSpacingDp(layoutZoom) * density
            val viewportWidth = width / screenScale
            val viewportHeight = height / screenScale
            val overscanX = viewportWidth * WIND_LAYOUT_OVERSCAN
            val overscanY = viewportHeight * WIND_LAYOUT_OVERSCAN
            val centerWorldX = longitudeToWorldX(mapCenter.longitude, worldSize)
            val centerWorldY = mercatorY(mapCenter.latitude) * worldSize
            val viewportLeft = centerWorldX - viewportWidth / 2.0
            val viewportTop = centerWorldY - viewportHeight / 2.0
            val firstColumn = floor((viewportLeft - overscanX) / spacing).toInt() - 1
            val lastColumn = floor((viewportLeft + viewportWidth + overscanX) / spacing).toInt() + 1
            val firstRow = floor((viewportTop - overscanY) / spacing).toInt() - 1
            val lastRow = floor((viewportTop + viewportHeight + overscanY) / spacing).toInt() + 1
            val screenSpacing = spacing * screenScale

            for (row in firstRow..lastRow) for (column in firstColumn..lastColumn) {
                val seed = windParticleSeed(column, row)
                val base = worldAnchoredParticleOrigin(
                    column = column,
                    row = row,
                    viewportLeft = viewportLeft,
                    viewportTop = viewportTop,
                    spacing = spacing,
                    screenScale = screenScale,
                )
                val point = screenToGeo(base, width, height, mapCenter, mapZoom, density)
                val vector = windGrid?.sample(point)?.let { components ->
                    WindVector(point, components.eastMetersPerSecond, components.northMetersPerSecond)
                } ?: interpolate(point, observations)
                if (vector.speed < .15f) continue
                add(
                    RenderWindParticle(
                        phaseOffset = pseudoRandom(seed + 4096).toDouble(),
                        base = base,
                        speed = vector.speed,
                        direction = vector.normalizedScreenDirection(),
                        travelDistance = screenSpacing * 1.55f,
                        length = screenSpacing * .42f,
                        layoutAlpha = layout.alpha,
                        color = windColor(vector.speed, darkMap),
                    ),
                )
            }
        }
    }
}

/**
 * Keeps a particle lattice stable throughout a fractional camera zoom. Near an
 * integer boundary the old lattice fades out while the next one fades in, so a
 * pinch gesture never replaces every particle seed in a single frame.
 */
internal fun windParticleLayoutLayers(zoom: Float): List<WindParticleLayoutLayer> {
    val safeZoom = zoom.coerceIn(MIN_PARTICLE_LAYOUT_ZOOM.toFloat(), MAX_PARTICLE_LAYOUT_ZOOM.toFloat())
    val lowerZoom = floor(safeZoom).toInt()
    val fraction = safeZoom - lowerZoom
    if (fraction <= LAYOUT_ALPHA_EPSILON || lowerZoom == MAX_PARTICLE_LAYOUT_ZOOM) {
        return listOf(WindParticleLayoutLayer(lowerZoom, 1f))
    }
    return listOf(
        WindParticleLayoutLayer(lowerZoom, 1f - fraction),
        WindParticleLayoutLayer(lowerZoom + 1, fraction),
    ).filter { it.alpha > LAYOUT_ALPHA_EPSILON }
}

internal data class WindParticleLayoutLayer(val zoom: Int, val alpha: Float)

internal fun windParticleSeed(column: Int, row: Int): Int =
    (column * 73_856_093) xor (row * 19_349_663)

internal fun worldAnchoredParticleOrigin(
    column: Int,
    row: Int,
    viewportLeft: Double,
    viewportTop: Double,
    spacing: Float,
    screenScale: Float = 1f,
): Offset {
    val seed = windParticleSeed(column, row)
    val jitterX = pseudoRandom(seed * 2) * spacing * .72f
    val jitterY = pseudoRandom(seed * 2 + 1) * spacing * .72f
    return Offset(
        (((column - .35) * spacing + jitterX - viewportLeft) * screenScale).toFloat(),
        (((row - .35) * spacing + jitterY - viewportTop) * screenScale).toFloat(),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawComet(
    head: Offset,
    direction: Offset,
    length: Float,
    color: Color,
    alpha: Float,
    scale: Float,
    density: Float,
) {
    if (alpha <= 0f || scale <= 0f) return
    val scaledLength = length * scale
    repeat(COMET_SEGMENTS) { index ->
        val startFraction = (COMET_SEGMENTS - index).toFloat() / COMET_SEGMENTS
        val endFraction = (COMET_SEGMENTS - index - 1).toFloat() / COMET_SEGMENTS
        val start = head - direction * (scaledLength * startFraction)
        val end = head - direction * (scaledLength * endFraction)
        val headFraction = (index + 1).toFloat() / COMET_SEGMENTS
        val taper = headFraction * headFraction
        val segmentAlpha = alpha * (.05f + .95f * taper)
        drawLine(
            color = color.copy(alpha = segmentAlpha),
            start = start,
            end = end,
            strokeWidth = floatLerp(.12f, 1.45f, taper) * density * scale,
            cap = StrokeCap.Round,
        )
    }
}

internal fun selectRelevantWinds(
    winds: List<WindObservation>,
    center: GeoPoint,
    limit: Int,
): List<WindObservation> {
    if (winds.size <= limit) return winds
    val latitudeScale = cos(Math.toRadians(center.latitude))
    return winds.sortedBy { observation ->
        val dx = (observation.coordinate.longitude - center.longitude) * latitudeScale
        val dy = observation.coordinate.latitude - center.latitude
        dx * dx + dy * dy
    }.take(limit)
}

internal fun windParticleSpacingDp(zoom: Float): Float {
    // Regional views need a denser lattice so the flow still reads when Taiwan is
    // small on screen; close views are deliberately sparser because neither the
    // 3 km model nor station observations gain street-level detail.
    val normalizedZoom = ((zoom - 5f) / 10f).coerceIn(0f, 1f)
    return floatLerp(26f, 64f, normalizedZoom)
}

internal fun windParticleAlpha(phase: Float): Float {
    val normalized = phase.coerceIn(0f, 1f)
    return (normalized / .12f).coerceIn(0f, 1f)
}

internal fun windParticleScale(phase: Float): Float {
    val remaining = ((1f - phase.coerceIn(0f, 1f)) / .22f).coerceIn(0f, 1f)
    // Smoothstep avoids a mechanical linear collapse at the end of the path.
    return remaining * remaining * (3f - 2f * remaining)
}

private fun floatLerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

private data class WindVector(
    val coordinate: GeoPoint,
    val eastMetersPerSecond: Float,
    val northMetersPerSecond: Float,
) {
    val speed: Float get() = sqrt(eastMetersPerSecond * eastMetersPerSecond + northMetersPerSecond * northMetersPerSecond)

    fun normalizedScreenDirection(): Offset {
        val magnitude = speed.coerceAtLeast(.001f)
        return Offset(eastMetersPerSecond / magnitude, -northMetersPerSecond / magnitude)
    }
}

private fun asVector(observation: WindObservation): WindVector {
    val travel = observation.travelComponents()
    return WindVector(
        coordinate = observation.coordinate,
        eastMetersPerSecond = travel.eastMetersPerSecond,
        northMetersPerSecond = travel.northMetersPerSecond,
    )
}

private fun interpolate(point: GeoPoint, observations: List<WindVector>): WindVector {
    if (observations.isEmpty()) return WindVector(point, 0f, 0f)
    var east = 0.0
    var north = 0.0
    var weightSum = 0.0
    observations.forEach { observation ->
        val latitudeScale = cos(Math.toRadians((point.latitude + observation.coordinate.latitude) * .5))
        val dx = (point.longitude - observation.coordinate.longitude) * latitudeScale
        val dy = point.latitude - observation.coordinate.latitude
        val distanceSquared = dx * dx + dy * dy
        val weight = 1.0 / max(distanceSquared, .018)
        east += observation.eastMetersPerSecond * weight
        north += observation.northMetersPerSecond * weight
        weightSum += weight
    }
    return WindVector(point, (east / weightSum).toFloat(), (north / weightSum).toFloat())
}

internal fun screenToGeo(
    screen: Offset,
    width: Float,
    height: Float,
    center: GeoPoint,
    zoom: Float,
    density: Float,
): GeoPoint {
    // Google Maps camera zoom is based on a 256 dp world, while Canvas offsets
    // are physical pixels. Include display density so both projections agree.
    val worldSize = worldSizePixels(zoom, density)
    val centerX = longitudeToWorldX(center.longitude, worldSize)
    val centerY = mercatorY(center.latitude) * worldSize
    val worldX = centerX + screen.x - width / 2.0
    val worldY = centerY + screen.y - height / 2.0
    val longitude = worldX / worldSize * 360.0 - 180.0
    val normalizedY = worldY / worldSize
    val latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * normalizedY))))
    return GeoPoint(latitude, longitude)
}

private fun worldSizePixels(zoom: Float, density: Float): Double =
    256.0 * density * 2.0.pow(zoom.toDouble())

private fun longitudeToWorldX(longitude: Double, worldSize: Double): Double =
    (longitude + 180.0) / 360.0 * worldSize

private fun mercatorY(latitude: Double): Double {
    val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
    val radians = Math.toRadians(clamped)
    return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
}

private fun sinh(value: Double): Double = (kotlin.math.exp(value) - kotlin.math.exp(-value)) / 2.0

private fun pseudoRandom(seed: Int): Float {
    val value = sin(seed * 12.9898) * 43758.5453
    return (value - floor(value)).toFloat()
}

internal fun windColor(speed: Float, darkMap: Boolean): Color = if (darkMap) {
    when {
        speed < 3f -> Color(0xFFDCE6D5)
        speed < 7f -> Color(0xFFB9D7C5)
        speed < 11f -> Color(0xFF86BFAE)
        else -> Color(0xFF5AA69C)
    }
} else {
    when {
        speed < 3f -> Color(0xFF566557)
        speed < 7f -> Color(0xFF3F6C59)
        speed < 11f -> Color(0xFF287463)
        else -> Color(0xFF0E6F68)
    }
}

private const val MAX_RENDER_OBSERVATIONS = 32
private const val WIND_TARGET_FPS = 30L
private const val WIND_FRAME_INTERVAL_MILLIS = 1_000L / WIND_TARGET_FPS
private const val COMET_SEGMENTS = 2
private const val MIN_PARTICLE_LAYOUT_ZOOM = 0
private const val MAX_PARTICLE_LAYOUT_ZOOM = 22
private const val LAYOUT_ALPHA_EPSILON = .001f
private const val WIND_LAYOUT_OVERSCAN = .35f
private const val WIND_LAYOUT_MAX_ZOOM_DRIFT = .35f
private const val WIND_SETTLE_REBUILD_MILLIS = 160L
