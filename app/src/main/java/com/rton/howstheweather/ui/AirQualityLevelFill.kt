package com.rton.howstheweather.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

private val AQI_LEVEL_BOUNDS = intArrayOf(0, 50, 100, 150, 200, 300, 500)
internal val AQI_LEVEL_COUNT = AQI_LEVEL_BOUNDS.size - 1
internal val AQI_SCALE_MAXIMUM = AQI_LEVEL_BOUNDS.last()

/** Maps AQI onto equal-width category segments so low, common values stay readable. */
internal fun aqiLevelFraction(aqi: Int): Float {
    val value = aqi.coerceIn(AQI_LEVEL_BOUNDS.first(), AQI_LEVEL_BOUNDS.last())
    val segment = (1 until AQI_LEVEL_BOUNDS.size).first { value <= AQI_LEVEL_BOUNDS[it] } - 1
    val lower = AQI_LEVEL_BOUNDS[segment]
    val upper = AQI_LEVEL_BOUNDS[segment + 1]
    return (segment + (value - lower).toFloat() / (upper - lower)) / AQI_LEVEL_COUNT
}

internal fun aqiLevelIndex(aqi: Int): Int =
    (aqiLevelFraction(aqi) * AQI_LEVEL_COUNT).toInt().coerceIn(0, AQI_LEVEL_COUNT - 1)

/** High-contrast semantic accent color for status text on light/dark surfaces. */
internal fun aqiTierTextColor(levelIndex: Int, dark: Boolean): Color = when (levelIndex.coerceIn(0, 5)) {
    0 -> if (dark) Color(0xFF46D996) else Color(0xFF0E7C55)
    1 -> if (dark) Color(0xFFF5C443) else Color(0xFF9E6B00)
    2 -> if (dark) Color(0xFFFF9248) else Color(0xFFC0520A)
    3 -> if (dark) Color(0xFFFF6B6B) else Color(0xFFC42828)
    4 -> if (dark) Color(0xFFC074FC) else Color(0xFF7B2CBF)
    else -> if (dark) Color(0xFFD65A7A) else Color(0xFF6E142D)
}

/** Semantic bar fill color for each of the 6 AQI tiers (Green -> Yellow -> Orange -> Red -> Purple -> Dark Maroon). */
internal fun aqiTierTrackColor(levelIndex: Int, dark: Boolean): Color = when (levelIndex.coerceIn(0, 5)) {
    0 -> if (dark) Color(0xFF38C988) else Color(0xFF199E6C)
    1 -> if (dark) Color(0xFFF2C13A) else Color(0xFFD99B08)
    2 -> if (dark) Color(0xFFFF8833) else Color(0xFFE06010)
    3 -> if (dark) Color(0xFFF54D4D) else Color(0xFFD12C2C)
    4 -> if (dark) Color(0xFFA652E8) else Color(0xFF7B2CBF)
    else -> if (dark) Color(0xFF8C1D3B) else Color(0xFF631028)
}

/**
 * Restrained continuous 6-tier AQI color stops across [0, 1] for the uncovered future track.
 */
private fun aqiContinuousSpectrumStops(dark: Boolean, alpha: Float): Array<Pair<Float, Color>> {
    fun c(tier: Int) = aqiTierTrackColor(tier, dark).copy(alpha = alpha)
    return arrayOf(
        0.00f to c(0),
        0.13f to c(0),
        0.20f to c(1),
        0.30f to c(1),
        0.37f to c(2),
        0.46f to c(2),
        0.54f to c(3),
        0.63f to c(3),
        0.71f to c(4),
        0.80f to c(4),
        0.87f to c(5),
        1.00f to c(5),
    )
}

private class AirStreak(val lane: Float, val length: Float, val speed: Float, val offset: Float)

private val AIR_STREAKS = listOf(
    AirStreak(lane = .20f, length = 48f, speed = 46f, offset = 0f),
    AirStreak(lane = .52f, length = 68f, speed = 36f, offset = 110f),
    AirStreak(lane = .36f, length = 34f, speed = 58f, offset = 210f),
    AirStreak(lane = .76f, length = 54f, speed = 42f, offset = 300f),
    AirStreak(lane = .64f, length = 38f, speed = 52f, offset = 360f),
)

/**
 * Fills the card bounds like an iOS slider bar:
 * - The covered region (`0..fillWidth`) is a clean, single-hue iOS bar in the current AQI color.
 * - Only the uncovered region (`fillWidth..width`) shows a very subtle bottom color-scale hint
 *   of the remaining higher-severity tiers, disappearing as the bar covers it.
 */
@Composable
internal fun AirQualityLevelFill(
    aqi: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val fill = remember { Animatable(0f) }
    LaunchedEffect(aqi) {
        fill.animateTo(
            aqiLevelFraction(aqi),
            spring(dampingRatio = .76f, stiffness = Spring.StiffnessVeryLow),
        )
    }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> time = (now - start) / 1_000_000_000f }
        }
    }

    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val airColor = if (dark) Color.White else Color(0xFF1C252B)
    val particleCount = 3 + aqiLevelIndex(aqi) * 4

    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val fillWidth = max(16f * density, fill.value * size.width).coerceAtMost(size.width)
        val baseAlpha = if (dark) .35f else .27f
        val edgeAlpha = if (dark) .46f else .36f

        // 1. Subtle uncovered future-severity hint along the bottom edge ONLY to the right of
        //    fillWidth. Anything already covered by the bar (0..fillWidth) is never drawn here.
        val barHeight = 2.5f * density
        val barTop = size.height - barHeight
        if (fillWidth < size.width) {
            clipRect(left = fillWidth, top = barTop, right = size.width, bottom = size.height) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = aqiContinuousSpectrumStops(dark, alpha = if (dark) .20f else .16f),
                        startX = 0f,
                        endX = size.width,
                    ),
                    topLeft = Offset(0f, barTop),
                    size = Size(size.width, barHeight),
                )
            }
        }

        // 2. iOS-style bar body: smooth single-color horizontal fill up to fillWidth.
        drawRect(
            brush = Brush.horizontalGradient(
                0f to color.copy(alpha = baseAlpha * .78f),
                0.76f to color.copy(alpha = baseAlpha),
                1f to color.copy(alpha = edgeAlpha),
                startX = 0f,
                endX = fillWidth,
            ),
            size = Size(fillWidth, size.height),
        )

        // Subtle vertical glass sheen over the filled region.
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) .07f else .15f),
                0.48f to Color.Transparent,
                1f to color.copy(alpha = if (dark) .10f else .06f),
            ),
            size = Size(fillWidth, size.height),
        )

        // Soft ambient feather ahead of the leading edge.
        val feather = 10f * density
        if (fillWidth < size.width) {
            val featherEnd = (fillWidth + feather).coerceAtMost(size.width)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to color.copy(alpha = if (dark) .16f else .12f),
                    1f to Color.Transparent,
                    startX = fillWidth,
                    endX = featherEnd,
                ),
                topLeft = Offset(fillWidth, 0f),
                size = Size(featherEnd - fillWidth, size.height),
            )
        }

        // Tapered leading-edge line: crisp at top and bottom rims, softened through the middle
        // text band so it clearly marks the level without slicing through the AQI digits.
        drawLine(
            brush = Brush.verticalGradient(
                0f to color.copy(alpha = if (dark) .76f else .64f),
                0.28f to color.copy(alpha = if (dark) .28f else .22f),
                0.68f to color.copy(alpha = if (dark) .28f else .22f),
                1f to color.copy(alpha = if (dark) .88f else .76f),
            ),
            start = Offset(fillWidth, 0f),
            end = Offset(fillWidth, size.height),
            strokeWidth = 1.6f * density,
        )

        // 3. Laminar air currents and wind streaks across the card.
        val step = 5f * density
        repeat(3) { index ->
            val baseline = size.height * (.24f + index * .24f)
            val wavelength = (145f + index * 50f) * density
            val amplitude = (3.6f + index * 1.3f) * density
            val phase = time * (1.6f - index * .35f)
            val current = Path()
            var x = 0f
            while (x <= size.width + step) {
                val y = baseline + sin(x / wavelength * 2f * PI.toFloat() - phase + index * 1.7f) * amplitude
                if (x == 0f) current.moveTo(x, y) else current.lineTo(x, y)
                x += step
            }
            drawPath(
                current,
                color = airColor.copy(alpha = (if (dark) .07f else .055f) - index * .014f),
                style = Stroke(width = (1.8f - index * .35f) * density, cap = StrokeCap.Round),
            )
        }

        val span = size.width / density + 80f
        AIR_STREAKS.forEach { streak ->
            val head = ((streak.offset + time * streak.speed) % span) - 40f
            val start = Offset((head - streak.length) * density, streak.lane * size.height)
            val end = Offset(head * density, streak.lane * size.height)
            val strength = if (end.x <= fillWidth) {
                if (dark) .24f else .18f
            } else {
                if (dark) .11f else .08f
            }
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(airColor.copy(alpha = 0f), airColor.copy(alpha = strength), airColor.copy(alpha = 0f)),
                    startX = start.x,
                    endX = end.x,
                ),
                start = start,
                end = end,
                strokeWidth = 1.5f * density,
                cap = StrokeCap.Round,
            )
        }

        // Suspended atmospheric particles: more of them as the air gets worse.
        repeat(particleCount) { index ->
            val seed = index * 37.31f
            val speed = 8f + (seed % 12f)
            val x = ((seed * 7f + time * speed) % span - 40f) * density
            val y = size.height * (.12f + ((seed * .618f) % 1f) * .72f) +
                sin(time * 1.3f + seed) * 2.8f * density
            val base = if (x <= fillWidth) .15f else .08f
            drawCircle(
                color = airColor.copy(alpha = base + .10f * sin(time * 2f + seed).coerceAtLeast(0f)),
                radius = (1.1f + (index % 3) * .45f) * density,
                center = Offset(x, y),
            )
        }
    }
}
