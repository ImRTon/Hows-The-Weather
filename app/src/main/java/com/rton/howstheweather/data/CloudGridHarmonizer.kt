package com.rton.howstheweather.data

import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import java.time.Duration
import kotlin.math.abs
import kotlin.math.max

/** Matches Taiwan LOD contrast to the same-time global IR frame in their overlap. */
class CloudGridHarmonizer {
    fun harmonize(localFrames: List<WeatherGrid>, globalFrames: List<WeatherGrid>): List<WeatherGrid> =
        localFrames.map { local ->
            val global = globalFrames
                .filter { it.unit == WeatherUnit.LUMINANCE }
                .minByOrNull { abs(Duration.between(it.validAt, local.validAt).seconds) }
                ?.takeIf { abs(Duration.between(it.validAt, local.validAt).seconds) <= MAX_TIME_OFFSET_SECONDS }
                ?: return@map local
            harmonize(local, global)
        }

    internal fun harmonize(local: WeatherGrid, global: WeatherGrid): WeatherGrid {
        if (local.unit != WeatherUnit.LUMINANCE || global.unit != WeatherUnit.LUMINANCE) return local
        val localSamples = ArrayList<Float>(MAX_SAMPLES)
        val globalSamples = ArrayList<Float>(MAX_SAMPLES)
        val xStep = max(1, local.width / SAMPLE_AXIS_COUNT)
        val yStep = max(1, local.height / SAMPLE_AXIS_COUNT)
        for (y in 0 until local.height step yStep) {
            val latitude = local.bounds.north -
                y.toDouble() / (local.height - 1) * (local.bounds.north - local.bounds.south)
            for (x in 0 until local.width step xStep) {
                val localValue = local.valueAt(x, y)
                if (!localValue.isFinite() || localValue < MIN_CLOUD_LUMINANCE) continue
                val longitude = local.bounds.west +
                    x.toDouble() / (local.width - 1) * (local.bounds.east - local.bounds.west)
                val globalValue = global.sample(latitude, longitude) ?: continue
                if (!globalValue.isFinite() || globalValue < MIN_CLOUD_LUMINANCE) continue
                localSamples += localValue
                globalSamples += globalValue
            }
        }
        if (localSamples.size < MIN_SAMPLE_COUNT) return local
        localSamples.sort()
        globalSamples.sort()
        val localLow = localSamples.percentile(LOW_PERCENTILE)
        val localHigh = localSamples.percentile(HIGH_PERCENTILE)
        val globalLow = globalSamples.percentile(LOW_PERCENTILE)
        val globalHigh = globalSamples.percentile(HIGH_PERCENTILE)
        if (localHigh - localLow < MIN_TONE_RANGE || globalHigh - globalLow < MIN_TONE_RANGE) return local
        val scale = (globalHigh - globalLow) / (localHigh - localLow)
        val values = FloatArray(local.values.size) { index ->
            val value = local.values[index]
            if (!value.isFinite() || value < MIN_CLOUD_LUMINANCE) {
                value
            } else {
                ((value - localLow) * scale + globalLow).coerceIn(0f, 1f)
            }
        }
        return local.copy(values = values, sourceId = local.sourceId + "|global-tone")
    }

    private fun List<Float>.percentile(fraction: Float): Float =
        this[((lastIndex * fraction).toInt()).coerceIn(0, lastIndex)]

    private companion object {
        const val SAMPLE_AXIS_COUNT = 64
        const val MAX_SAMPLES = SAMPLE_AXIS_COUNT * SAMPLE_AXIS_COUNT
        const val MIN_SAMPLE_COUNT = 64
        const val MIN_CLOUD_LUMINANCE = 0.08f
        const val LOW_PERCENTILE = 0.1f
        const val HIGH_PERCENTILE = 0.9f
        const val MIN_TONE_RANGE = 0.05f
        const val MAX_TIME_OFFSET_SECONDS = 10 * 60L
    }
}
