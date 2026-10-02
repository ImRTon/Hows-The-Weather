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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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

private class AirStreak(val lane: Float, val length: Float, val speed: Float, val offset: Float)

private val AIR_STREAKS = listOf(
    AirStreak(lane = .18f, length = 46f, speed = 52f, offset = 0f),
    AirStreak(lane = .52f, length = 70f, speed = 38f, offset = 110f),
    AirStreak(lane = .34f, length = 32f, speed = 64f, offset = 200f),
    AirStreak(lane = .78f, length = 56f, speed = 44f, offset = 290f),
    AirStreak(lane = .66f, length = 38f, speed = 58f, offset = 360f),
    AirStreak(lane = .9f, length = 44f, speed = 34f, offset = 60f),
)

/**
 * Fills its whole bounds like a volume slider: the colored region grows from the left to the
 * AQI level and carries drifting air currents. Intended as the background of the AQI card.
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
            spring(dampingRatio = .72f, stiffness = Spring.StiffnessVeryLow),
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
        val fillWidth = max(16f * density, fill.value * size.width)
        val fillAlpha = if (dark) .32f else .26f
        val solid = color.copy(alpha = fillAlpha)
        val clear = color.copy(alpha = 0f)

        // Even body that dissolves softly into the air instead of ending at a hard, dark edge.
        val feather = 40f * density
        val bodyEnd = fillWidth + feather * .5f
        drawRect(
            brush = Brush.horizontalGradient(
                0f to color.copy(alpha = fillAlpha * .7f),
                ((fillWidth - feather * .5f) / bodyEnd).coerceIn(0f, 1f) to solid,
                1f to clear,
                startX = 0f,
                endX = bodyEnd,
            ),
            size = Size(bodyEnd, size.height),
        )

        // Bubbles blown from the level's edge into the open air, drifting downwind before popping.
        val openSpace = (size.width - fillWidth).coerceAtLeast(32f * density)
        repeat(BUBBLE_COUNT) { index ->
            val period = 3.6f + (index % 3) * .9f
            val clock = time + index * period / BUBBLE_COUNT
            val cycle = kotlin.math.floor(clock / period)
            val progress = clock / period - cycle
            drawBubble(
                progress = progress,
                originX = fillWidth,
                originY = size.height * (.25f + .5f * hash(index, cycle, 1)),
                travel = openSpace * (.35f + .6f * hash(index, cycle, 2)),
                radius = (5f + 7f * hash(index, cycle, 3)) * density,
                wobblePhase = hash(index, cycle, 4) * 6.28f,
                time = time,
                color = color,
                highlight = Color.White,
                dark = dark,
            )
        }

        // Air currents run across the whole card, a little stronger over the filled level.
        val step = 5f * density
        repeat(3) { index ->
            val baseline = size.height * (.26f + index * .26f)
            val wavelength = (140f + index * 50f) * density
            val amplitude = (4f + index * 1.5f) * density
            val phase = time * (1.8f - index * .4f)
            val current = Path()
            var x = 0f
            while (x <= size.width + step) {
                val y = baseline + sin(x / wavelength * 2f * PI.toFloat() - phase + index * 1.7f) * amplitude
                if (x == 0f) current.moveTo(x, y) else current.lineTo(x, y)
                x += step
            }
            drawPath(
                current,
                color = airColor.copy(alpha = .08f - index * .015f),
                style = Stroke(width = (2f - index * .4f) * density, cap = StrokeCap.Round),
            )
        }

        val span = size.width / density + 80f
        AIR_STREAKS.forEach { streak ->
            val head = ((streak.offset + time * streak.speed) % span) - 40f
            val start = Offset((head - streak.length) * density, streak.lane * size.height)
            val end = Offset(head * density, streak.lane * size.height)
            val strength = if (end.x <= fillWidth) .28f else .16f
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(airColor.copy(alpha = 0f), airColor.copy(alpha = strength), airColor.copy(alpha = 0f)),
                    startX = start.x,
                    endX = end.x,
                ),
                start = start,
                end = end,
                strokeWidth = 1.6f * density,
                cap = StrokeCap.Round,
            )
        }

        // Suspended particles: more of them as the air gets worse.
        repeat(particleCount) { index ->
            val seed = index * 37.31f
            val speed = 8f + (seed % 13f)
            val x = ((seed * 7f + time * speed) % span - 40f) * density
            val y = size.height * (.12f + ((seed * .618f) % 1f) * .76f) +
                sin(time * 1.3f + seed) * 3f * density
            val base = if (x <= fillWidth) .16f else .1f
            drawCircle(
                color = airColor.copy(alpha = base + .12f * sin(time * 2f + seed).coerceAtLeast(0f)),
                radius = (1.2f + (index % 3) * .5f) * density,
                center = Offset(x, y),
            )
        }
    }
}


private const val BUBBLE_COUNT = 5

/** Stable pseudo-random value in [0, 1) for a bubble's given life cycle. */
private fun hash(index: Int, cycle: Float, salt: Int): Float {
    val value = sin(index * 12.9898f + cycle * 78.233f + salt * 37.719f) * 43758.547f
    return value - kotlin.math.floor(value)
}

private fun DrawScope.drawBubble(
    progress: Float,
    originX: Float,
    originY: Float,
    travel: Float,
    radius: Float,
    wobblePhase: Float,
    time: Float,
    color: Color,
    highlight: Color,
    dark: Boolean,
) {
    val popAt = .88f
    // Blown out quickly, then eases into a gentle drift and a slight rise.
    val drift = 1f - (1f - progress.coerceAtMost(popAt) / popAt).let { it * it }
    val center = Offset(
        originX + travel * drift,
        originY - 10f * density * drift + sin(time * 2.2f + wobblePhase) * 2.5f * density * drift,
    )

    if (progress >= popAt) {
        val pop = (progress - popAt) / (1f - popAt)
        drawCircle(
            color = color.copy(alpha = (1f - pop) * .5f),
            radius = radius * (1f + pop * .8f),
            center = center,
            style = Stroke(width = 1.2f * density * (1f - pop) + .3f * density),
        )
        repeat(4) { spray ->
            val angle = wobblePhase + spray * PI.toFloat() / 2f
            val distance = radius * (1.1f + pop * 1.1f)
            drawCircle(
                color = color.copy(alpha = (1f - pop) * .6f),
                radius = 1.2f * density,
                center = center + Offset(kotlin.math.cos(angle) * distance, sin(angle) * distance),
            )
        }
        return
    }

    // Inflates as it leaves the edge, then breathes and wobbles in the air.
    val inflate = (progress / .14f).coerceAtMost(1f).let { 1f - (1f - it) * (1f - it) }
    val wobble = sin(time * 5f + wobblePhase) * .06f
    val radiusX = radius * inflate * (1f + wobble)
    val radiusY = radius * inflate * (1f - wobble)
    if (radiusX < .5f) return
    val fade = if (progress < .1f) progress / .1f else 1f
    val topLeft = center - Offset(radiusX, radiusY)
    val bubbleSize = Size(radiusX * 2f, radiusY * 2f)

    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0f), color.copy(alpha = .08f * fade), color.copy(alpha = .28f * fade)),
            center = center,
            radius = max(radiusX, radiusY),
        ),
        topLeft = topLeft,
        size = bubbleSize,
    )
    // Thin-film iridescence along the rim, slowly turning.
    val sheen = if (dark) .45f else .55f
    drawOval(
        brush = Brush.sweepGradient(
            colors = listOf(
                color.copy(alpha = sheen * fade),
                Color(0xFF7FE3FF).copy(alpha = sheen * .8f * fade),
                Color(0xFFE59BFF).copy(alpha = sheen * .7f * fade),
                color.copy(alpha = sheen * fade),
            ),
            center = center,
        ),
        topLeft = topLeft,
        size = bubbleSize,
        style = Stroke(width = 1.1f * density),
    )
    // Specular glint on the upper-left.
    drawCircle(
        color = highlight.copy(alpha = (if (dark) .7f else .85f) * fade),
        radius = max(radiusX, radiusY) * .18f,
        center = center + Offset(-radiusX * .38f, -radiusY * .42f),
    )
}
