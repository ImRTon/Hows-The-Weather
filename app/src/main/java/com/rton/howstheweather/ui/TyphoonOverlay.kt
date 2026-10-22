package com.rton.howstheweather.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.TropicalCyclone
import com.rton.howstheweather.domain.destinationPoint
import com.rton.howstheweather.domain.greatCircleDistanceKm
import com.rton.howstheweather.domain.outline
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Official CWA track, 70% probability area, and 7/10-level wind radii, plus a
 * stylized animated storm symbol at the latest official fix. The symbol is a
 * glyph, not observed cloud: its size depends on screen density and intensity,
 * never on the map scale, and its eye only marks the official center.
 *
 * Each base map renders this layer inside its own frame (GoogleWeatherMap.kt and
 * OsmWeatherMap.kt), so the geometry never trails the camera during a gesture.
 */
internal data class TyphoonMapLayer(
    val cyclones: List<TropicalCyclone>,
    val darkMap: Boolean,
)

/**
 * Calls [onFrame] with elapsed seconds every [frameIntervalMillis] until cancelled,
 * skipping frames while [paused] so a moving map is not asked to redraw extra frames.
 */
internal suspend fun runTyphoonAnimation(
    frameIntervalMillis: Long = TYPHOON_FRAME_INTERVAL_MILLIS,
    paused: () -> Boolean = { false },
    onFrame: (Float) -> Unit,
) {
    val start = withFrameNanos { it }
    while (true) {
        withFrameNanos { if (!paused()) onFrame(((it - start) / 1_000_000_000.0).toFloat()) }
        delay(frameIntervalMillis)
    }
}

/** Animation time; read it only in the smallest scope that animates. */
@Composable
internal fun rememberTyphoonSeconds(
    frameIntervalMillis: Long = TYPHOON_FRAME_INTERVAL_MILLIS,
    paused: () -> Boolean = { false },
): State<Float> {
    val seconds = remember { mutableFloatStateOf(0f) }
    val currentPaused by rememberUpdatedState(paused)
    LaunchedEffect(frameIntervalMillis) {
        runTyphoonAnimation(frameIntervalMillis, { currentPaused() }) { seconds.floatValue = it }
    }
    return seconds
}

internal class GeoPolygon(val outline: List<GeoPoint>, val holes: List<List<GeoPoint>>)

internal class CycloneGeometry(
    val shortName: String,
    val center: GeoPoint,
    val southernHemisphere: Boolean,
    val maxWind: Float?,
    val track: List<GeoPoint>,
    val forecast: List<GeoPoint>,
    val forecastLabels: List<Pair<GeoPoint, String>>,
    /** Union of the official 70% circles and the hulls joining them. */
    val probabilityArea: List<GeoPolygon>,
    val gale: List<GeoPoint>?,
    val storm: List<GeoPoint>?,
) {
    val glyphRadiusDp: Float
        get() {
            val strength = (((maxWind ?: 17f) - 17f) / (55f - 17f)).coerceIn(0f, 1f)
            return 18f + 14f * strength
        }
}

internal fun cycloneGeometry(cyclone: TropicalCyclone): CycloneGeometry {
    val latest = cyclone.latest
    val probability = cyclone.forecast.mapNotNull { point ->
        point.probability70RadiusKm?.let { radius ->
            point.center to (0 until 360 step 10).map { bearing ->
                destinationPoint(point.center, bearing.toDouble(), radius.toDouble())
            }
        }
    }
    val probabilityCircles = if (probability.isEmpty()) emptyList() else {
        listOf(latest.center to listOf(latest.center)) + probability
    }
    return CycloneGeometry(
        shortName = cyclone.cwaName.ifBlank { cyclone.englishName },
        center = latest.center,
        southernHemisphere = latest.center.latitude < 0.0,
        maxWind = latest.maxWindMetersPerSecond,
        track = cyclone.track.map { it.center },
        forecast = listOf(latest.center) + cyclone.forecast.map { it.center },
        forecastLabels = cyclone.forecast
            .filter { it.forecastHours % 24 == 0 }
            .map { it.center to "${it.forecastHours}h" },
        probabilityArea = probabilityArea(probabilityCircles, latest.center),
        gale = latest.galeRadius?.outline(latest.center),
        storm = latest.stormRadius?.outline(latest.center),
    )
}

/**
 * Unions the probability circles once in a fixed Web Mercator plane, which is a
 * uniform scale of every map frame, so frames only reproject a finished outline.
 */
private fun probabilityArea(
    circles: List<Pair<GeoPoint, List<GeoPoint>>>,
    reference: GeoPoint,
): List<GeoPolygon> {
    val plane = MercatorPlane(reference.longitude)
    val path = probabilityAreaPath(circles, plane::toPlane) ?: return emptyList()
    return polygonsFromContours(pathContours(path)).map { (outline, holes) ->
        GeoPolygon(outline.map(plane::toGeo), holes.map { hole -> hole.map(plane::toGeo) })
    }
}

/** Longitudes are kept relative to [referenceLongitude] so the plane never splits at the antimeridian. */
private class MercatorPlane(private val referenceLongitude: Double) {
    fun toPlane(point: GeoPoint): Offset = Offset(
        x = (wrapLongitude(point.longitude - referenceLongitude) / 360.0 * PLANE_SIZE).toFloat(),
        y = (mercatorFraction(point.latitude) * PLANE_SIZE).toFloat(),
    )

    fun toGeo(offset: Offset): GeoPoint = GeoPoint(
        latitude = inverseMercatorFraction(offset.y / PLANE_SIZE),
        longitude = wrapLongitude(referenceLongitude + offset.x / PLANE_SIZE * 360.0),
    )
}

private fun wrapLongitude(longitude: Double): Double = ((longitude + 540.0) % 360.0) - 180.0

private fun pathContours(path: Path): List<List<Offset>> {
    val contours = mutableListOf<List<Offset>>()
    var current = mutableListOf<Offset>()
    for (segment in path) {
        val points = segment.points
        when (segment.type) {
            PathSegment.Type.Move -> {
                if (current.size > 2) contours += current
                current = mutableListOf(Offset(points[0], points[1]))
            }
            PathSegment.Type.Close, PathSegment.Type.Done -> {
                if (current.size > 2) contours += current
                current = mutableListOf()
            }
            // Unions of polygons contain only lines; keep each curve's end point regardless.
            else -> current += Offset(points[points.size - 2], points[points.size - 1])
        }
    }
    if (current.size > 2) contours += current
    return contours
}

/**
 * Groups contours into outlines and holes: holes wind opposite to the largest
 * contour and belong to the outline that contains them.
 */
internal fun polygonsFromContours(
    contours: List<List<Offset>>,
): List<Pair<List<Offset>, List<List<Offset>>>> {
    val valid = contours.filter { it.size >= 3 && abs(signedArea(it)) > MIN_CONTOUR_AREA }
    if (valid.isEmpty()) return emptyList()
    val outerPositive = signedArea(valid.maxBy { abs(signedArea(it)) }) > 0.0
    val (outlines, holes) = valid.partition { (signedArea(it) > 0.0) == outerPositive }
    return outlines.map { outline -> outline to holes.filter { containsPoint(outline, it.first()) } }
}

private fun signedArea(points: List<Offset>): Double {
    var sum = 0.0
    points.forEachIndexed { index, point ->
        val next = points[(index + 1) % points.size]
        sum += point.x.toDouble() * next.y - next.x.toDouble() * point.y
    }
    return sum / 2.0
}

private fun containsPoint(polygon: List<Offset>, point: Offset): Boolean {
    var inside = false
    var previous = polygon.last()
    polygon.forEach { current ->
        if ((current.y > point.y) != (previous.y > point.y) &&
            point.x < (previous.x - current.x) * (point.y - current.y) / (previous.y - current.y) + current.x
        ) inside = !inside
        previous = current
    }
    return inside
}

private fun polygonPath(points: List<Offset>): Path = Path().apply { addContour(points) }

private fun Path.addContour(points: List<Offset>) {
    points.forEachIndexed { index, point ->
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}

/** Union of the official 70% circles and the tangent hulls joining consecutive circles. */
private fun probabilityAreaPath(
    circles: List<Pair<GeoPoint, List<GeoPoint>>>,
    project: (GeoPoint) -> Offset,
): Path? {
    if (circles.size < 2) return null
    val projected = circles.map { (center, outline) ->
        val screenCenter = project(center)
        val screenOutline = outline.map(project)
        val radius = screenOutline.maxOf { (it - screenCenter).getDistance() }
        Triple(screenCenter, screenOutline, radius)
    }
    var area = Path()
    projected.forEachIndexed { index, (center, outline, radius) ->
        if (outline.size > 2) area = union(area, polygonPath(outline))
        val next = projected.getOrNull(index + 1) ?: return@forEachIndexed
        val direction = next.first - center
        val length = direction.getDistance()
        if (length < 1f) return@forEachIndexed
        val normal = Offset(-direction.y / length, direction.x / length)
        val hull = polygonPath(
            listOf(
                center + normal * radius,
                next.first + normal * next.third,
                next.first - normal * next.third,
                center - normal * radius,
            ),
        )
        area = union(area, hull)
    }
    return area
}

private fun union(first: Path, second: Path): Path = Path().apply { op(first, second, PathOperation.Union) }

/** One cyclone projected into a canvas for a single frame. */
internal class TyphoonScreenGeometry(
    val center: Offset,
    val track: List<Offset>,
    val forecast: List<Offset>,
    val forecastLabels: List<Pair<Offset, String>>,
    val probabilityArea: Path?,
    val gale: Path?,
    val storm: Path?,
)

internal fun CycloneGeometry.project(project: (GeoPoint) -> Offset): TyphoonScreenGeometry = TyphoonScreenGeometry(
    center = project(center),
    track = track.map(project),
    forecast = forecast.map(project),
    forecastLabels = forecastLabels.map { (point, label) -> project(point) to label },
    probabilityArea = probabilityArea.takeIf { it.isNotEmpty() }?.let { polygons ->
        Path().apply {
            fillType = PathFillType.EvenOdd
            polygons.forEach { polygon ->
                addContour(polygon.outline.map(project))
                polygon.holes.forEach { hole -> addContour(hole.map(project)) }
            }
        }
    },
    gale = gale?.let { polygonPath(it.map(project)) },
    storm = storm?.let { polygonPath(it.map(project)) },
)

internal class TyphoonLabelStyles(val label: TextStyle, val name: TextStyle)

internal fun typhoonLabelStyles(palette: TyphoonPalette): TyphoonLabelStyles {
    val label = TextStyle(
        color = palette.label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        shadow = Shadow(palette.labelHalo, blurRadius = 6f),
    )
    return TyphoonLabelStyles(label, label.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
}

/** Draws one cyclone with every element; used by base maps that draw in their own frame. */
internal fun DrawScope.drawTyphoon(
    geometry: CycloneGeometry,
    screen: TyphoonScreenGeometry,
    palette: TyphoonPalette,
    styles: TyphoonLabelStyles,
    textMeasurer: TextMeasurer,
    seconds: Float,
) {
    drawProbabilityArea(screen, palette)
    drawWindRadii(screen, palette, seconds)
    drawTrack(screen, palette, seconds)
    screen.forecastLabels.forEach { (offset, label) ->
        if (!isOnScreen(offset, 48.dp.toPx())) return@forEach
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = offset + forecastLabelOffset(),
            style = styles.label,
        )
    }
    val glyphRadius = geometry.glyphRadiusDp.dp.toPx()
    if (isOnScreen(screen.center, glyphRadius * 2f)) {
        repeat(TYPHOON_PULSE_COUNT) { index ->
            val phase = typhoonPulsePhase(index, seconds)
            drawCircle(
                color = palette.glyph.copy(alpha = typhoonPulseAlpha(phase)),
                radius = glyphRadius * typhoonPulseScale(phase),
                center = screen.center,
                style = Stroke(width = TYPHOON_PULSE_STROKE_DP.dp.toPx()),
            )
        }
        drawStormGlyph(screen.center, glyphRadius, geometry, palette, seconds)
        drawText(
            textMeasurer = textMeasurer,
            text = geometry.shortName,
            topLeft = screen.center + nameLabelOffset(glyphRadius),
            style = styles.name,
        )
    }
}

internal fun Density.forecastLabelOffset(): Offset = Offset(7.dp.toPx(), -7.dp.toPx())

internal fun nameLabelOffset(glyphRadius: Float): Offset = Offset(glyphRadius * .9f, glyphRadius * .55f)

private fun DrawScope.drawProbabilityArea(screen: TyphoonScreenGeometry, palette: TyphoonPalette) {
    val area = screen.probabilityArea ?: return
    drawPath(area, palette.probabilityFill)
    drawPath(area, palette.probabilityEdge, style = Stroke(width = TYPHOON_PROBABILITY_EDGE_DP.dp.toPx()))
}

private fun DrawScope.drawWindRadii(screen: TyphoonScreenGeometry, palette: TyphoonPalette, seconds: Float) {
    screen.gale?.let { gale ->
        drawPath(gale, palette.galeFill)
        val dash = TYPHOON_GALE_DASH_DP.dp.toPx()
        val gap = dash * TYPHOON_GALE_GAP_RATIO
        drawPath(
            gale,
            palette.galeEdge,
            style = Stroke(
                width = TYPHOON_GALE_EDGE_DP.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(dash, gap),
                    -typhoonDashOffset(dash, gap, TYPHOON_GALE_DASH_SPEED, seconds),
                ),
            ),
        )
    }
    screen.storm?.let { storm ->
        drawPath(storm, palette.stormFill)
        drawPath(storm, palette.stormEdge, style = Stroke(width = TYPHOON_STORM_EDGE_DP.dp.toPx()))
    }
}

private fun DrawScope.drawTrack(screen: TyphoonScreenGeometry, palette: TyphoonPalette, seconds: Float) {
    if (screen.track.size > 1) {
        drawPath(
            polyline(screen.track),
            palette.track.copy(alpha = TYPHOON_TRACK_ALPHA),
            style = Stroke(width = TYPHOON_TRACK_WIDTH_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
    screen.track.dropLast(1).forEach { point ->
        if (isOnScreen(point, 8f)) drawTrackDot(point, palette)
    }
    if (screen.forecast.size > 1) {
        val dash = TYPHOON_FORECAST_DASH_DP.dp.toPx()
        val gap = dash * TYPHOON_FORECAST_GAP_RATIO
        drawPath(
            polyline(screen.forecast),
            palette.track,
            style = Stroke(
                width = TYPHOON_TRACK_WIDTH_DP.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                // Dashes flow forward along the official forecast track.
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(dash, gap),
                    -typhoonDashOffset(dash, gap, TYPHOON_FORECAST_DASH_SPEED, seconds),
                ),
            ),
        )
        screen.forecast.drop(1).forEach { point ->
            if (isOnScreen(point, 8f)) drawForecastDot(point, palette)
        }
    }
}

internal fun DrawScope.drawTrackDot(center: Offset, palette: TyphoonPalette) {
    drawCircle(palette.track.copy(alpha = TYPHOON_TRACK_ALPHA), TYPHOON_TRACK_DOT_DP.dp.toPx(), center)
}

internal fun DrawScope.drawForecastDot(center: Offset, palette: TyphoonPalette) {
    drawCircle(palette.labelHalo, TYPHOON_FORECAST_DOT_DP.dp.toPx(), center)
    drawCircle(
        palette.track,
        TYPHOON_FORECAST_DOT_DP.dp.toPx(),
        center,
        style = Stroke(TYPHOON_FORECAST_DOT_STROKE_DP.dp.toPx()),
    )
}

private fun polyline(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { index, point ->
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
}

/** Distance the dash pattern has advanced along its line, within one period. */
internal fun typhoonDashOffset(dash: Float, gap: Float, speed: Float, seconds: Float): Float {
    val period = dash + gap
    return ((seconds * dash * speed) % period + period) % period
}

/**
 * One period of a dash/gap pattern shifted forward by [offset], as (isDash, length)
 * pairs, for map SDKs whose stroke patterns have no phase.
 */
internal fun marchingDashSegments(dash: Float, gap: Float, offset: Float): List<Pair<Boolean, Float>> {
    val segments = if (offset <= gap) {
        listOf(false to offset, true to dash, false to gap - offset)
    } else {
        listOf(true to offset - gap, false to gap, true to dash - (offset - gap))
    }
    return segments.filter { (_, length) -> length > MIN_PATTERN_LENGTH }
}

internal fun typhoonPulsePhase(index: Int, seconds: Float): Float =
    ((seconds / PULSE_PERIOD_SECONDS) + index.toFloat() / TYPHOON_PULSE_COUNT) % 1f

internal fun typhoonPulseAlpha(phase: Float): Float = .32f * (1f - phase) * (1f - phase)

internal fun typhoonPulseScale(phase: Float): Float = .9f + phase * 1.1f

/** Spiral rotation: counter-clockwise in the northern hemisphere, clockwise in the southern. */
internal fun typhoonGlyphRotationRadians(southernHemisphere: Boolean, seconds: Float): Float =
    (if (southernHemisphere) 1f else -1f) * seconds * GLYPH_ANGULAR_SPEED

/** Three-armed spiral at [typhoonGlyphRotationRadians]; [seconds] 0 gives the unrotated glyph. */
internal fun DrawScope.drawStormGlyph(
    center: Offset,
    radius: Float,
    geometry: CycloneGeometry,
    palette: TyphoonPalette,
    seconds: Float,
) {
    val spin = if (geometry.southernHemisphere) 1f else -1f
    val wind = geometry.maxWind ?: 0f
    val hasEye = wind >= EYE_GLYPH_MIN_WIND
    drawCircle(
        brush = Brush.radialGradient(
            0f to palette.glyph.copy(alpha = .38f),
            .55f to palette.glyph.copy(alpha = .16f),
            1f to Color.Transparent,
            center = center,
            radius = radius * 1.15f,
        ),
        radius = radius * 1.15f,
        center = center,
    )
    val rotation = typhoonGlyphRotationRadians(geometry.southernHemisphere, seconds)
    val eyeRadius = radius * if (hasEye) .2f else .1f
    val curl = -spin * ARM_CURL_RADIANS
    repeat(ARM_COUNT) { arm ->
        val base = rotation + arm * (2f * PI.toFloat() / ARM_COUNT)
        var previous: Offset? = null
        for (step in 0..ARM_SEGMENTS) {
            val s = step.toFloat() / ARM_SEGMENTS
            val r = eyeRadius * 1.25f + (radius - eyeRadius * 1.25f) * s
            val angle = base + curl * s
            val point = center + Offset(cos(angle) * r, sin(angle) * r)
            previous?.let { start ->
                val strength = 1f - s
                drawLine(
                    color = palette.glyph.copy(alpha = .25f + .7f * strength),
                    start = start,
                    end = point,
                    strokeWidth = (1f + 4.5f * strength) * density,
                    cap = StrokeCap.Round,
                )
            }
            previous = point
        }
    }
    if (hasEye) {
        val breathe = 1f + .06f * sin(seconds * 2.4f)
        drawCircle(palette.glyph, eyeRadius * 1.25f * breathe, center, style = Stroke(width = 3.dp.toPx()))
        drawCircle(palette.eye, eyeRadius * breathe, center)
    } else {
        drawCircle(palette.glyph, eyeRadius, center)
    }
}

private fun DrawScope.isOnScreen(point: Offset, margin: Float): Boolean =
    point.x > -margin && point.y > -margin && point.x < size.width + margin && point.y < size.height + margin

/** The cyclone that matters most for the selected target: the nearest one. */
internal fun nearestCyclone(cyclones: List<TropicalCyclone>, target: GeoPoint): TropicalCyclone? =
    cyclones.minByOrNull { greatCircleDistanceKm(it.latest.center, target) }

/**
 * Camera that frames the target, the cyclone's current wind radius, and its
 * next three days of official forecast track on a map of [widthDp] × [heightDp].
 */
internal fun typhoonFocusCamera(
    cyclone: TropicalCyclone,
    target: GeoPoint,
    widthDp: Float,
    heightDp: Float,
): MapCameraRequest {
    val latest = cyclone.latest
    val reach = (latest.galeRadius?.quadrantsKm?.values?.maxOrNull() ?: latest.galeRadius?.meanKm ?: 150f).toDouble()
    val points = buildList {
        add(target)
        listOf(0.0, 90.0, 180.0, 270.0).forEach { add(destinationPoint(latest.center, it, reach)) }
        cyclone.forecast.filter { it.forecastHours <= FOCUS_FORECAST_HOURS }.forEach { add(it.center) }
    }
    val xs = points.map { (it.longitude + 180.0) / 360.0 }
    val ys = points.map { mercatorFraction(it.latitude) }
    val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-6)
    val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-6)
    val usableWidth = widthDp.coerceAtLeast(120f) * FOCUS_FILL
    val usableHeight = heightDp.coerceAtLeast(120f) * FOCUS_FILL
    val zoom = log2(min(usableWidth / (256.0 * spanX), usableHeight / (256.0 * spanY)))
    val centerX = (xs.max() + xs.min()) / 2.0
    val centerY = (ys.max() + ys.min()) / 2.0
    return MapCameraRequest(
        point = GeoPoint(latitude = inverseMercatorFraction(centerY), longitude = centerX * 360.0 - 180.0),
        zoom = zoom.toFloat().coerceIn(MIN_FOCUS_ZOOM, MAX_FOCUS_ZOOM),
    )
}

private fun mercatorFraction(latitude: Double): Double {
    val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
    return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
}

private fun inverseMercatorFraction(fraction: Double): Double =
    Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * fraction))))

internal data class TyphoonPalette(
    val galeFill: Color,
    val galeEdge: Color,
    val stormFill: Color,
    val stormEdge: Color,
    val probabilityFill: Color,
    val probabilityEdge: Color,
    val track: Color,
    val glyph: Color,
    val eye: Color,
    val label: Color,
    val labelHalo: Color,
)

internal fun typhoonPalette(darkMap: Boolean): TyphoonPalette = if (darkMap) {
    TyphoonPalette(
        galeFill = Color(0xFFFFC24D).copy(alpha = .13f),
        galeEdge = Color(0xFFFFC24D).copy(alpha = .9f),
        stormFill = Color(0xFFFF5A6E).copy(alpha = .18f),
        stormEdge = Color(0xFFFF5A6E).copy(alpha = .95f),
        probabilityFill = Color(0xFFE8F1F8).copy(alpha = .09f),
        probabilityEdge = Color(0xFFE8F1F8).copy(alpha = .38f),
        track = Color(0xFFE8F1F8),
        glyph = Color(0xFFE6F2FA),
        eye = Color(0xFF0B1A2A),
        label = Color(0xFFF2F7FB),
        labelHalo = Color(0xFF0B1A2A),
    )
} else {
    TyphoonPalette(
        galeFill = Color(0xFFC77700).copy(alpha = .12f),
        galeEdge = Color(0xFFB36A00),
        stormFill = Color(0xFFC62839).copy(alpha = .14f),
        stormEdge = Color(0xFFC62839),
        probabilityFill = Color(0xFF2B3A44).copy(alpha = .08f),
        probabilityEdge = Color(0xFF2B3A44).copy(alpha = .4f),
        track = Color(0xFF2B3A44),
        glyph = Color(0xFF34505F),
        eye = Color(0xFFF7F4EE),
        label = Color(0xFF1E2A32),
        labelHalo = Color(0xFFF7F4EE),
    )
}

private const val TYPHOON_FRAME_INTERVAL_MILLIS = 1_000L / 30L
private const val ARM_COUNT = 3
private const val ARM_SEGMENTS = 14
private const val ARM_CURL_RADIANS = 2.4f
private const val GLYPH_ANGULAR_SPEED = 1.1f
private const val PULSE_PERIOD_SECONDS = 2.8f
internal const val TYPHOON_PULSE_COUNT = 2
internal const val TYPHOON_PULSE_STROKE_DP = 1.4f
internal const val TYPHOON_PROBABILITY_EDGE_DP = 1f
internal const val TYPHOON_GALE_EDGE_DP = 1.6f
internal const val TYPHOON_GALE_DASH_DP = 7f
internal const val TYPHOON_GALE_GAP_RATIO = .7f
internal const val TYPHOON_GALE_DASH_SPEED = 1.2f
internal const val TYPHOON_STORM_EDGE_DP = 1.8f
internal const val TYPHOON_TRACK_WIDTH_DP = 2f
internal const val TYPHOON_TRACK_ALPHA = .75f
internal const val TYPHOON_TRACK_DOT_DP = 2.5f
internal const val TYPHOON_FORECAST_DASH_DP = 6f
internal const val TYPHOON_FORECAST_GAP_RATIO = .8f
internal const val TYPHOON_FORECAST_DASH_SPEED = 2f
internal const val TYPHOON_FORECAST_DOT_DP = 4f
internal const val TYPHOON_FORECAST_DOT_STROKE_DP = 1.6f
/** Unions run in a 2^22-unit Mercator world: roughly one-meter float precision at typhoon latitudes. */
private const val PLANE_SIZE = 4_194_304.0
private const val MIN_CONTOUR_AREA = 1.0
private const val MIN_PATTERN_LENGTH = .01f
/** CWA moderate-typhoon threshold; weaker systems get a plain center dot instead of an eye. */
private const val EYE_GLYPH_MIN_WIND = 32.7f
private const val FOCUS_FORECAST_HOURS = 72
private const val FOCUS_FILL = .78
private const val MIN_FOCUS_ZOOM = 3f
private const val MAX_FOCUS_ZOOM = 8f
