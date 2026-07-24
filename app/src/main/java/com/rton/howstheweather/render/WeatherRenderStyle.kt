package com.rton.howstheweather.render

import android.graphics.Color
import com.rton.howstheweather.domain.WeatherUnit
import kotlin.math.cbrt
import kotlin.math.pow

enum class RenderTheme { LIGHT, DARK }

object CloudEnhancedPalette {
    fun colors(theme: RenderTheme): List<Int> = when (theme) {
        RenderTheme.DARK -> listOf(
            0xFF12082F, 0xFF32105F, 0xFF5A167A, 0xFF84206B,
            0xFFB83255, 0xFFE85B2A, 0xFFF6C945,
        )
        RenderTheme.LIGHT -> listOf(
            0xFF0B0624, 0xFF280B50, 0xFF4B1167, 0xFF74195F,
            0xFFA5294B, 0xFFD94A1F, 0xFFE8AD20,
        )
    }.map(Long::toInt)
}

data class ColorAnchor(val position: Float, val argb: Int)

data class WeatherRenderStyle(
    val theme: RenderTheme,
    val unit: WeatherUnit,
    val valueStops: FloatArray,
    val colorLut: IntArray,
    val contours: FloatArray,
    val opacity: Float,
    val contourColor: Int,
) {
    fun colorFor(value: Float): Int {
        if (!value.isFinite() || value < valueStops.first()) return Color.TRANSPARENT
        val normalized = normalize(value)
        if (unit == WeatherUnit.LUMINANCE) {
            val index = (normalized * 255f).toInt().coerceIn(0, 255)
            val source = colorLut[index]
            val alpha = (cloudOpacity(normalized, opacity) * 255f).toInt().coerceIn(0, 255)
            return Color.argb(alpha, Color.red(source), Color.green(source), Color.blue(source))
        }
        val index = (normalized * 255f).toInt().coerceIn(0, 255)
        val source = colorLut[index]
        return Color.argb(
            (Color.alpha(source) * opacity).toInt().coerceIn(0, 255),
            Color.red(source), Color.green(source), Color.blue(source),
        )
    }

    fun normalize(value: Float): Float {
        if (value <= valueStops.first()) return 0.08f
        if (value >= valueStops.last()) return 1f
        var segment = 0
        while (segment < valueStops.lastIndex - 1 && value > valueStops[segment + 1]) segment++
        val local = (value - valueStops[segment]) / (valueStops[segment + 1] - valueStops[segment])
        return NORMALIZED_POSITIONS[segment] +
            (NORMALIZED_POSITIONS[segment + 1] - NORMALIZED_POSITIONS[segment]) * local
    }

    companion object {
        private val NORMALIZED_POSITIONS = floatArrayOf(0.08f, 0.25f, 0.42f, 0.58f, 0.72f, 0.86f, 1f)

        fun rain(theme: RenderTheme, opacity: Float = 0.76f) = create(
            theme = theme,
            unit = WeatherUnit.MILLIMETERS_PER_HOUR,
            stops = floatArrayOf(0.1f, 0.5f, 2.5f, 10f, 25f, 50f, 100f),
            contours = floatArrayOf(2.5f, 10f, 40f),
            opacity = opacity,
        )

        fun hourlyRain(theme: RenderTheme, opacity: Float = 0.76f) = create(
            theme = theme,
            unit = WeatherUnit.MILLIMETERS_ONE_HOUR,
            stops = floatArrayOf(0.1f, 0.5f, 2.5f, 10f, 25f, 50f, 100f),
            contours = floatArrayOf(2.5f, 10f, 40f),
            opacity = opacity,
        )

        fun twelveHourRain(theme: RenderTheme, opacity: Float = 0.76f) = create(
            theme = theme,
            unit = WeatherUnit.MILLIMETERS_TWELVE_HOURS,
            stops = floatArrayOf(0.1f, 0.5f, 2.5f, 10f, 25f, 50f, 100f),
            contours = floatArrayOf(2.5f, 10f, 40f),
            opacity = opacity,
        )

        fun radar(theme: RenderTheme, opacity: Float = 0.76f) = create(
            theme = theme,
            unit = WeatherUnit.DBZ,
            stops = floatArrayOf(5f, 15f, 25f, 35f, 45f, 55f, 65f),
            contours = floatArrayOf(20f, 35f, 50f),
            opacity = opacity,
        )

        fun cloud(theme: RenderTheme, opacity: Float = 0.62f): WeatherRenderStyle {
            val positions = floatArrayOf(0.08f, 0.25f, 0.42f, 0.58f, 0.72f, 0.86f, 1f)
            val anchors = CloudEnhancedPalette.colors(theme).mapIndexed { index, color ->
                ColorAnchor(positions[index], color)
            }
            return WeatherRenderStyle(
                theme = theme,
                unit = WeatherUnit.LUMINANCE,
                valueStops = floatArrayOf(.08f, .2f, .35f, .5f, .65f, .8f, 1f),
                colorLut = PerceptualColorLut.create(anchors),
                contours = floatArrayOf(),
                opacity = opacity,
                contourColor = Color.TRANSPARENT,
            )
        }

        private fun create(
            theme: RenderTheme,
            unit: WeatherUnit,
            stops: FloatArray,
            contours: FloatArray,
            opacity: Float,
        ): WeatherRenderStyle {
            val colors = if (theme == RenderTheme.DARK) {
                listOf("#37D6E6", "#4169D8", "#6D3AC6", "#B62B96", "#E64A54", "#F49A38", "#FFF1B5")
            } else {
                listOf("#008A9A", "#275DB6", "#5B2FA7", "#9F1F78", "#C73843", "#D96A12", "#FFE3A1")
            }
            val positions = floatArrayOf(0.08f, 0.25f, 0.42f, 0.58f, 0.72f, 0.86f, 1f)
            val anchors = colors.mapIndexed { index, hex -> ColorAnchor(positions[index], Color.parseColor(hex)) }
            return WeatherRenderStyle(
                theme = theme,
                unit = unit,
                valueStops = stops,
                colorLut = PerceptualColorLut.create(anchors),
                contours = contours,
                opacity = opacity,
                contourColor = if (theme == RenderTheme.DARK) 0xB8FFF4DA.toInt() else 0xB84A2718.toInt(),
            )
        }
    }
}

internal fun cloudOpacity(normalized: Float, opacity: Float): Float {
    val threshold = 0.08f
    val strength = ((normalized - threshold) / (1f - threshold)).coerceIn(0f, 1f)
    val smooth = strength * strength * (3f - 2f * strength)
    return smooth * opacity.coerceIn(0f, 1f)
}

object PerceptualColorLut {
    fun create(anchors: List<ColorAnchor>): IntArray {
        require(anchors.size >= 2)
        return IntArray(256) { index ->
            val p = index / 255f
            if (p < anchors.first().position) return@IntArray Color.TRANSPARENT
            val rightIndex = anchors.indexOfFirst { it.position >= p }.coerceAtLeast(1)
            val left = anchors[rightIndex - 1]
            val right = anchors[rightIndex]
            val t = ((p - left.position) / (right.position - left.position)).coerceIn(0f, 1f)
            interpolateOklab(left.argb, right.argb, t)
        }
    }

    private fun interpolateOklab(a: Int, b: Int, t: Float): Int {
        val labA = rgbToOklab(a)
        val labB = rgbToOklab(b)
        return oklabToRgb(FloatArray(3) { labA[it] + (labB[it] - labA[it]) * t })
    }

    private fun rgbToOklab(color: Int): FloatArray {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(Color.red(color)); val g = linear(Color.green(color)); val b = linear(Color.blue(color))
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return floatArrayOf(
            (0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s).toFloat(),
            (1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s).toFloat(),
            (0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s).toFloat(),
        )
    }

    private fun oklabToRgb(lab: FloatArray): Int {
        val l = (lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2]).toDouble().pow(3)
        val m = (lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2]).toDouble().pow(3)
        val s = (lab[0] - 0.0894841775 * lab[1] - 1.291485548 * lab[2]).toDouble().pow(3)
        fun gamma(v: Double): Int {
            val c = if (v <= 0.0031308) 12.92 * v else 1.055 * v.coerceAtLeast(0.0).pow(1.0 / 2.4) - 0.055
            return (c * 255).toInt().coerceIn(0, 255)
        }
        return Color.rgb(
            gamma(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
            gamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
            gamma(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s),
        )
    }
}
