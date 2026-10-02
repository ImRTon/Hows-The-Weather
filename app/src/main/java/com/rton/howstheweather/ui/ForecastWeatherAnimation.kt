package com.rton.howstheweather.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Decorative scene derived from the official text description; never feeds rain decisions. */
internal enum class WeatherScene {
    CLEAR_DAY,
    CLEAR_NIGHT,
    PARTLY_CLOUDY_DAY,
    PARTLY_CLOUDY_NIGHT,
    CLOUDY,
    OVERCAST,
    RAIN,
    HEAVY_RAIN,
    THUNDERSTORM,
    SNOW,
}

internal fun weatherSceneFor(description: String, daytime: Boolean): WeatherScene? = when {
    "雷" in description -> WeatherScene.THUNDERSTORM
    "雪" in description -> WeatherScene.SNOW
    "大雨" in description || "豪雨" in description -> WeatherScene.HEAVY_RAIN
    "雨" in description -> WeatherScene.RAIN
    "晴" in description && ("雲" in description || "陰" in description) ->
        if (daytime) WeatherScene.PARTLY_CLOUDY_DAY else WeatherScene.PARTLY_CLOUDY_NIGHT
    "晴" in description -> if (daytime) WeatherScene.CLEAR_DAY else WeatherScene.CLEAR_NIGHT
    "陰" in description -> WeatherScene.OVERCAST
    "雲" in description -> WeatherScene.CLOUDY
    else -> null
}

internal data class FallVector(val x: Float, val y: Float)

private const val MAX_FALL_ANGLE_RADIANS = 1.2f
private const val MAX_RIPPLES = 96
private const val MAX_DROPLETS = 240
private const val RIPPLE_LIFETIME = .42f
private const val SPLASH_GRAVITY = 520f

/**
 * Screen-space unit direction for falling precipitation.
 *
 * [gravityX]/[gravityY] follow Android sensor convention already remapped to the display
 * (upright portrait reads y ≈ +9.81). Wind direction is meteorological (where it blows from).
 */
internal fun precipitationFallVector(
    gravityX: Float,
    gravityY: Float,
    windSpeedMetersPerSecond: Float?,
    windDirectionDegrees: Float?,
): FallVector {
    // A phone lying flat gives no useful vertical gravity; keep rain falling down the screen.
    val tiltAngle = atan2(-gravityX, gravityY.coerceAtLeast(4f))
    val windDrift = windDriftFactor(windSpeedMetersPerSecond, windDirectionDegrees)
    val rawX = sin(tiltAngle) + windDrift
    val rawY = cos(tiltAngle)
    val angle = atan2(rawX, rawY).coerceIn(-MAX_FALL_ANGLE_RADIANS, MAX_FALL_ANGLE_RADIANS)
    return FallVector(sin(angle), cos(angle))
}

/** Horizontal screen drift in [-0.6, 0.6], positive toward the east (right on a north-up view). */
internal fun windDriftFactor(windSpeedMetersPerSecond: Float?, windDirectionDegrees: Float?): Float {
    if (windSpeedMetersPerSecond == null || windDirectionDegrees == null) return 0f
    val strength = (windSpeedMetersPerSecond / 10f).coerceIn(0f, 1f)
    return -sin(Math.toRadians(windDirectionDegrees.toDouble())).toFloat() * strength * .6f
}

@Composable
internal fun ForecastWeatherAnimation(
    scene: WeatherScene,
    windSpeedMetersPerSecond: Float?,
    windDirectionDegrees: Float?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val gravity = rememberDisplayGravity(enabled = !reduceMotion)
    val palette = scenePalette(MaterialTheme.colorScheme.surface.luminance() < .5f)
    val simulation = remember(scene) { WeatherSimulation(scene, Random(scene.ordinal * 7919 + 17)) }
    val windSpeed by rememberUpdatedState(windSpeedMetersPerSecond)
    val windDirection by rememberUpdatedState(windDirectionDegrees)
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(simulation, reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val seconds = ((now - last) / 1_000_000_000f).coerceIn(0f, .05f)
                last = now
                simulation.step(seconds, gravity.x, gravity.y, windSpeed, windDirection)
                frame = now
            }
        }
    }

    Canvas(modifier.clearAndSetSemantics { }) {
        if (frame < 0L) return@Canvas
        simulation.draw(this, palette)
    }
}

private class GravityReading {
    @Volatile var x = 0f
    @Volatile var y = SensorManager.GRAVITY_EARTH
}

@Composable
private fun rememberDisplayGravity(enabled: Boolean): GravityReading {
    val context = LocalContext.current
    val view = LocalView.current
    val reading = remember { GravityReading() }
    if (enabled) {
        LifecycleResumeEffect(context, view) {
            val manager = context.getSystemService(SensorManager::class.java)
            // TYPE_GRAVITY is fused from the gyroscope and accelerometer, so tilting is smooth.
            val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val sensorX = event.values[0]
                    val sensorY = event.values[1]
                    val (x, y) = when (view.display?.rotation ?: android.view.Surface.ROTATION_0) {
                        android.view.Surface.ROTATION_90 -> -sensorY to sensorX
                        android.view.Surface.ROTATION_180 -> -sensorX to -sensorY
                        android.view.Surface.ROTATION_270 -> sensorY to -sensorX
                        else -> sensorX to sensorY
                    }
                    reading.x += (x - reading.x) * .25f
                    reading.y += (y - reading.y) * .25f
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (manager != null && sensor != null) {
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
            onPauseOrDispose { manager?.unregisterListener(listener) }
        }
    }
    return reading
}

@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private data class ScenePalette(
    val rain: Color,
    val snow: Color,
    val cloud: Color,
    val stormCloud: Color,
    val sun: Color,
    val sunCore: Color,
    val moon: Color,
    val star: Color,
    val flash: Color,
)

private fun scenePalette(dark: Boolean) = if (dark) {
    ScenePalette(
        rain = Color(0xFFBFEFFF),
        snow = Color(0xFFF2FAFF),
        cloud = Color(0xFFA9BCC6),
        stormCloud = Color(0xFF55636E),
        sun = Color(0xFFFFC94A),
        sunCore = Color(0xFFFFE29A),
        moon = Color(0xFFE9F1F5),
        star = Color(0xFFF4FBFF),
        flash = Color(0xFFE8F6FF),
    )
} else {
    ScenePalette(
        rain = Color(0xFF1F6A78),
        snow = Color(0xFF6E97A5),
        cloud = Color(0xFF7D8C94),
        stormCloud = Color(0xFF4A565E),
        sun = Color(0xFFF5A623),
        sunCore = Color(0xFFFFD36B),
        moon = Color(0xFF8C9AA2),
        star = Color(0xFF557380),
        flash = Color(0xFFFFFFFF),
    )
}

private class CloudPuff(
    var x: Float,
    val yFraction: Float,
    val scale: Float,
    val speed: Float,
    val depth: Float,
    val alpha: Float,
)

private class WeatherSimulation(private val scene: WeatherScene, private val random: Random) {
    private var widthDp = 0f
    private var heightDp = 0f
    private var time = 0f
    private var fallX = 0f
    private var fallY = 1f
    private var tiltX = 0f
    private var cloudDirection = 1f
    private var cloudSpeedFactor = 1f
    private var flash = 0f
    private var nextFlashIn = 2.5f
    private var secondFlashIn = -1f

    private val isRain = scene == WeatherScene.RAIN || scene == WeatherScene.HEAVY_RAIN || scene == WeatherScene.THUNDERSTORM
    private val isSnow = scene == WeatherScene.SNOW
    private val particleCount = when (scene) {
        WeatherScene.RAIN -> 70
        WeatherScene.HEAVY_RAIN -> 120
        WeatherScene.THUNDERSTORM -> 130
        WeatherScene.SNOW -> 60
        else -> 0
    }
    private val px = FloatArray(particleCount)
    private val py = FloatArray(particleCount)
    private val speed = FloatArray(particleCount)
    private val extent = FloatArray(particleCount)
    private val phase = FloatArray(particleCount)
    private var particlesPlaced = false

    private val rippleX = FloatArray(MAX_RIPPLES)
    private val rippleAge = FloatArray(MAX_RIPPLES) { RIPPLE_LIFETIME }
    private val rippleScale = FloatArray(MAX_RIPPLES)
    private var nextRipple = 0
    private val dropletX = FloatArray(MAX_DROPLETS)
    private val dropletY = FloatArray(MAX_DROPLETS)
    private val dropletVx = FloatArray(MAX_DROPLETS)
    private val dropletVy = FloatArray(MAX_DROPLETS)
    private val dropletLife = FloatArray(MAX_DROPLETS)
    private var nextDroplet = 0

    private val clouds: List<CloudPuff> = run {
        val count = when (scene) {
            WeatherScene.PARTLY_CLOUDY_DAY, WeatherScene.PARTLY_CLOUDY_NIGHT -> 2
            WeatherScene.CLOUDY -> 3
            WeatherScene.OVERCAST, WeatherScene.THUNDERSTORM -> 5
            WeatherScene.RAIN, WeatherScene.HEAVY_RAIN, WeatherScene.SNOW -> 4
            WeatherScene.CLEAR_DAY, WeatherScene.CLEAR_NIGHT -> 0
        }
        List(count) { index ->
            val depth = .4f + random.nextFloat() * .6f
            CloudPuff(
                x = random.nextFloat(),
                yFraction = .08f + (index % 3) * .16f + random.nextFloat() * .08f,
                scale = .8f + depth * .7f,
                speed = 6f + depth * 10f,
                depth = depth,
                alpha = .55f + depth * .45f,
            )
        }
    }

    private val starCount = when (scene) {
        WeatherScene.CLEAR_NIGHT -> 16
        WeatherScene.PARTLY_CLOUDY_NIGHT -> 9
        else -> 0
    }
    private val stars = List(starCount) {
        floatArrayOf(random.nextFloat(), random.nextFloat() * .7f, 1f + random.nextFloat() * 2.5f, random.nextFloat() * 6.28f)
    }

    private val cloudPath = Path().apply {
        addOval(Rect(Offset(-30f, 2f), 16f))
        addOval(Rect(Offset(-10f, -8f), 22f))
        addOval(Rect(Offset(14f, -2f), 18f))
        addOval(Rect(Offset(32f, 8f), 12f))
        addRoundRect(androidx.compose.ui.geometry.RoundRect(-44f, 4f, 44f, 22f, 9f, 9f))
    }

    fun step(dt: Float, gravityX: Float, gravityY: Float, windSpeed: Float?, windDirection: Float?) {
        time += dt
        val target = precipitationFallVector(gravityX, gravityY, windSpeed, windDirection)
        val follow = (dt * 5f).coerceAtMost(1f)
        fallX += (target.x - fallX) * follow
        fallY += (target.y - fallY) * follow
        val norm = sqrt(fallX * fallX + fallY * fallY).coerceAtLeast(.001f)
        fallX /= norm
        fallY /= norm
        tiltX += ((-gravityX / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) - tiltX) * follow

        val drift = windDriftFactor(windSpeed, windDirection)
        cloudDirection = if (abs(drift) > .02f) if (drift > 0) 1f else -1f else 1f
        cloudSpeedFactor = 1f + (windSpeed ?: 0f).coerceIn(0f, 15f) / 5f

        if (widthDp <= 0f || heightDp <= 0f) return
        stepClouds(dt)
        if (isRain) stepRain(dt)
        if (isSnow) stepSnow(dt)
        if (scene == WeatherScene.THUNDERSTORM) stepLightning(dt)
    }

    private fun stepClouds(dt: Float) {
        clouds.forEach { cloud ->
            val widthFraction = 100f * cloud.scale / widthDp
            cloud.x += cloudDirection * cloud.speed * cloudSpeedFactor * dt / widthDp
            if (cloud.x > 1f + widthFraction) cloud.x = -widthFraction
            if (cloud.x < -widthFraction) cloud.x = 1f + widthFraction
        }
    }

    private fun stepRain(dt: Float) {
        val margin = heightDp * .9f
        for (i in 0 until particleCount) {
            px[i] += fallX * speed[i] * dt
            py[i] += fallY * speed[i] * dt
            if (py[i] >= heightDp) {
                val impactX = px[i] - fallX * (py[i] - heightDp) / fallY.coerceAtLeast(.1f)
                val depth = (extent[i] - 9f) / 14f
                if (impactX in 0f..widthDp && random.nextFloat() < .2f + depth * .5f) {
                    spawnSplash(impactX, depth)
                }
                py[i] -= heightDp + extent[i] + random.nextFloat() * 40f
                px[i] = random.nextFloat() * (widthDp + 2f * margin) - margin
            }
            wrapX(i, margin)
        }
        stepSplashes(dt)
    }

    private fun spawnSplash(x: Float, depth: Float) {
        val ripple = nextRipple
        nextRipple = (nextRipple + 1) % MAX_RIPPLES
        rippleX[ripple] = x
        rippleAge[ripple] = 0f
        rippleScale[ripple] = .6f + depth * .7f

        val dropletCount = if (depth > .55f) 3 else 2
        repeat(dropletCount) {
            val droplet = nextDroplet
            nextDroplet = (nextDroplet + 1) % MAX_DROPLETS
            val spread = (random.nextFloat() - .5f) * 110f
            val rebound = 70f + random.nextFloat() * 70f * (.6f + depth)
            // Rebound against gravity: opposite the fall direction, plus a sideways spread.
            dropletX[droplet] = x
            dropletY[droplet] = heightDp - 1f
            dropletVx[droplet] = -fallX * rebound + fallY * spread
            dropletVy[droplet] = -fallY * rebound - fallX * spread
            dropletLife[droplet] = .45f + random.nextFloat() * .2f
        }
    }

    private fun stepSplashes(dt: Float) {
        for (i in 0 until MAX_RIPPLES) {
            if (rippleAge[i] < RIPPLE_LIFETIME) rippleAge[i] += dt
        }
        for (i in 0 until MAX_DROPLETS) {
            if (dropletLife[i] <= 0f) continue
            dropletLife[i] -= dt
            dropletVx[i] += fallX * SPLASH_GRAVITY * dt
            dropletVy[i] += fallY * SPLASH_GRAVITY * dt
            dropletX[i] += dropletVx[i] * dt
            dropletY[i] += dropletVy[i] * dt
            if (dropletY[i] > heightDp) dropletLife[i] = 0f
        }
    }

    private fun stepSnow(dt: Float) {
        val margin = 40f
        for (i in 0 until particleCount) {
            val sway = sin(time * 1.3f + phase[i]) * 14f
            px[i] += (fallX * speed[i] + sway) * dt
            py[i] += fallY * speed[i] * dt
            if (py[i] - extent[i] > heightDp) {
                py[i] = -extent[i] - random.nextFloat() * 20f
                px[i] = random.nextFloat() * widthDp
            }
            wrapX(i, margin)
        }
    }

    private fun wrapX(index: Int, margin: Float) {
        val span = widthDp + 2f * margin
        if (px[index] < -margin) px[index] += span
        if (px[index] > widthDp + margin) px[index] -= span
    }

    private fun stepLightning(dt: Float) {
        flash = (flash - dt * 3.2f).coerceAtLeast(0f)
        if (secondFlashIn > 0f) {
            secondFlashIn -= dt
            if (secondFlashIn <= 0f) flash = .75f
        }
        nextFlashIn -= dt
        if (nextFlashIn <= 0f) {
            flash = 1f
            secondFlashIn = .14f
            nextFlashIn = 5f + random.nextFloat() * 6f
        }
    }

    private fun ensureSize(width: Float, height: Float) {
        if (width == widthDp && height == heightDp && particlesPlaced) return
        widthDp = width
        heightDp = height
        if (!particlesPlaced && width > 0f && height > 0f) {
            for (i in 0 until particleCount) {
                val depth = random.nextFloat()
                px[i] = random.nextFloat() * width
                py[i] = random.nextFloat() * height
                phase[i] = random.nextFloat() * 6.28f
                if (isSnow) {
                    speed[i] = 28f + depth * 46f
                    extent[i] = 1.4f + depth * 2.4f
                } else {
                    speed[i] = 420f + depth * 380f + if (scene == WeatherScene.RAIN) 0f else 120f
                    extent[i] = 9f + depth * 14f
                }
            }
            particlesPlaced = true
        }
    }

    fun draw(scope: DrawScope, palette: ScenePalette) = with(scope) {
        val unit = density
        ensureSize(size.width / unit, size.height / unit)

        when (scene) {
            WeatherScene.CLEAR_DAY -> drawSun(palette, unit)
            WeatherScene.PARTLY_CLOUDY_DAY -> drawSun(palette, unit)
            WeatherScene.CLEAR_NIGHT, WeatherScene.PARTLY_CLOUDY_NIGHT -> {
                drawStars(palette, unit)
                drawMoon(palette, unit)
            }
            else -> Unit
        }
        drawClouds(palette, unit)
        if (isRain) {
            drawWetGround(palette, unit)
            drawRain(palette, unit)
            drawSplashes(palette, unit)
        }
        if (isSnow) drawSnow(palette, unit)
        if (flash > 0f) drawRect(palette.flash.copy(alpha = flash * .28f))
    }

    private fun DrawScope.drawSun(palette: ScenePalette, unit: Float) {
        val center = Offset(
            size.width - 64f * unit - tiltX * 6f * unit,
            62f * unit,
        )
        val pulse = .5f + .5f * sin(time * 1.6f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.sun.copy(alpha = .38f + .14f * pulse), palette.sun.copy(alpha = 0f)),
                center = center,
                radius = (78f + 8f * pulse) * unit,
            ),
            radius = (78f + 8f * pulse) * unit,
            center = center,
        )
        rotate(degrees = time * 9f, pivot = center) {
            val rays = 12
            for (i in 0 until rays) {
                val angle = (2 * PI * i / rays).toFloat()
                val shimmer = .5f + .5f * sin(time * 2.4f + i * 1.7f)
                val inner = 34f * unit
                val outer = (44f + 10f * shimmer) * unit
                drawLine(
                    color = palette.sun.copy(alpha = .45f + .45f * shimmer),
                    start = center + Offset(cos(angle) * inner, sin(angle) * inner),
                    end = center + Offset(cos(angle) * outer, sin(angle) * outer),
                    strokeWidth = 3f * unit,
                    cap = StrokeCap.Round,
                )
            }
        }
        drawCircle(palette.sun, radius = 26f * unit, center = center)
        drawCircle(palette.sunCore, radius = 17f * unit, center = center - Offset(4f * unit, 4f * unit))
        val glint = (sin(time * .9f) * 1.6f - .6f).coerceIn(0f, 1f)
        if (glint > 0f) {
            val glintCenter = center + Offset(-12f * unit, -14f * unit)
            val arm = 9f * unit * glint
            val glintColor = Color.White.copy(alpha = .85f * glint)
            drawLine(glintColor, glintCenter - Offset(arm, 0f), glintCenter + Offset(arm, 0f), 1.6f * unit, StrokeCap.Round)
            drawLine(glintColor, glintCenter - Offset(0f, arm), glintCenter + Offset(0f, arm), 1.6f * unit, StrokeCap.Round)
        }
    }

    private fun DrawScope.drawMoon(palette: ScenePalette, unit: Float) {
        val center = Offset(size.width - 64f * unit - tiltX * 6f * unit, 60f * unit)
        val pulse = .5f + .5f * sin(time * 1.1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.moon.copy(alpha = .22f + .08f * pulse), palette.moon.copy(alpha = 0f)),
                center = center,
                radius = 64f * unit,
            ),
            radius = 64f * unit,
            center = center,
        )
        val radius = 22f * unit
        val crescent = Path().apply {
            op(
                Path().apply { addOval(Rect(center, radius)) },
                Path().apply { addOval(Rect(center + Offset(10f * unit, -7f * unit), radius * .92f)) },
                PathOperation.Difference,
            )
        }
        drawPath(crescent, palette.moon)
    }

    private fun DrawScope.drawStars(palette: ScenePalette, unit: Float) {
        stars.forEach { (xFraction, yFraction, radius, starPhase) ->
            val twinkle = .25f + .75f * abs(sin(time * (0.8f + radius * .3f) + starPhase))
            drawCircle(
                color = palette.star.copy(alpha = twinkle * .8f),
                radius = radius * .6f * unit,
                center = Offset(xFraction * size.width - tiltX * 3f * unit, yFraction * size.height),
            )
        }
    }

    private fun DrawScope.drawClouds(palette: ScenePalette, unit: Float) {
        val storm = scene == WeatherScene.THUNDERSTORM || scene == WeatherScene.HEAVY_RAIN
        val heavy = storm || scene == WeatherScene.OVERCAST || scene == WeatherScene.RAIN
        val baseAlpha = when {
            storm -> .5f
            heavy -> .34f
            else -> .28f
        }
        clouds.forEach { cloud ->
            val color = if (storm) palette.stormCloud else palette.cloud
            val bob = sin(time * .5f + cloud.depth * 5f) * 2f * unit
            translate(
                left = cloud.x * size.width - tiltX * 10f * cloud.depth * unit,
                top = cloud.yFraction * size.height + bob,
            ) {
                scale(scale = cloud.scale * unit, pivot = Offset.Zero) {
                    drawPath(cloudPath, color.copy(alpha = baseAlpha * cloud.alpha))
                }
            }
        }
    }

    private fun DrawScope.drawRain(palette: ScenePalette, unit: Float) {
        for (i in 0 until particleCount) {
            val depth = (extent[i] - 9f) / 14f
            val head = Offset(px[i] * unit, py[i] * unit)
            val tail = head - Offset(fallX * extent[i] * unit, fallY * extent[i] * unit)
            drawLine(
                color = palette.rain.copy(alpha = .22f + depth * .38f),
                start = tail,
                end = head,
                strokeWidth = (1f + depth * .8f) * unit,
                cap = StrokeCap.Round,
            )
        }
    }

    private fun DrawScope.drawWetGround(palette: ScenePalette, unit: Float) {
        val sheenHeight = 14f * unit
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(palette.rain.copy(alpha = 0f), palette.rain.copy(alpha = .12f)),
                startY = size.height - sheenHeight,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - sheenHeight),
            size = Size(size.width, sheenHeight),
        )
    }

    private fun DrawScope.drawSplashes(palette: ScenePalette, unit: Float) {
        val groundY = size.height - 3f * unit
        for (i in 0 until MAX_RIPPLES) {
            val age = rippleAge[i]
            if (age >= RIPPLE_LIFETIME) continue
            val progress = age / RIPPLE_LIFETIME
            val scale = rippleScale[i]
            val center = Offset(rippleX[i] * unit, groundY)
            val width = (3f + 15f * progress) * scale * unit
            val height = width * .3f
            drawOval(
                color = palette.rain.copy(alpha = (1f - progress) * .55f),
                topLeft = center - Offset(width / 2f, height / 2f),
                size = Size(width, height),
                style = Stroke(width = 1f * unit),
            )
            // A brief crown at the moment of impact.
            if (progress < .3f) {
                val crown = 1f - progress / .3f
                val arm = (4f + 4f * progress / .3f) * scale * unit
                val crownColor = palette.rain.copy(alpha = crown * .6f)
                drawLine(crownColor, center, center + Offset(-arm * .8f, -arm), 1.1f * unit, StrokeCap.Round)
                drawLine(crownColor, center, center + Offset(arm * .8f, -arm), 1.1f * unit, StrokeCap.Round)
            }
        }
        for (i in 0 until MAX_DROPLETS) {
            val life = dropletLife[i]
            if (life <= 0f) continue
            drawCircle(
                color = palette.rain.copy(alpha = (life / .5f).coerceAtMost(1f) * .65f),
                radius = 1.2f * unit,
                center = Offset(dropletX[i] * unit, dropletY[i] * unit),
            )
        }
    }

    private fun DrawScope.drawSnow(palette: ScenePalette, unit: Float) {
        for (i in 0 until particleCount) {
            drawCircle(
                color = palette.snow.copy(alpha = .5f + (extent[i] - 1.4f) / 2.4f * .45f),
                radius = extent[i] * unit,
                center = Offset(px[i] * unit, py[i] * unit),
            )
        }
    }
}
