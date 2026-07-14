package com.rton.howstheweather.data

import com.rton.howstheweather.domain.WeatherGrid
import java.time.Duration
import kotlin.math.abs

internal const val OBSERVATION_HISTORY_MINUTES = 90
internal const val OBSERVATION_FRAME_INTERVAL_MINUTES = 10
internal const val OBSERVATION_HISTORY_FRAME_COUNT =
    OBSERVATION_HISTORY_MINUTES / OBSERVATION_FRAME_INTERVAL_MINUTES + 1

/** Keeps only compatible, time-ordered observations for map playback. */
class ObservedWeatherTimeline(
    private val maximumFrames: Int = OBSERVATION_HISTORY_FRAME_COUNT,
    private val maximumAge: Duration = Duration.ofMinutes(OBSERVATION_HISTORY_MINUTES.toLong()),
) {
    init {
        require(maximumFrames > 0)
        require(!maximumAge.isNegative && !maximumAge.isZero)
    }

    fun merge(current: WeatherGrid, candidates: List<WeatherGrid>): List<WeatherGrid> =
        (candidates + current)
            .asSequence()
            .filter(WeatherGrid::isObservedFrame)
            .filter { it.validAt <= current.validAt }
            .filter { Duration.between(it.validAt, current.validAt) <= maximumAge }
            .filter { it.unit == current.unit && it.width == current.width && it.height == current.height }
            .filter {
                abs(it.bounds.south - current.bounds.south) < BOUNDS_TOLERANCE &&
                    abs(it.bounds.west - current.bounds.west) < BOUNDS_TOLERANCE &&
                    abs(it.bounds.north - current.bounds.north) < BOUNDS_TOLERANCE &&
                    abs(it.bounds.east - current.bounds.east) < BOUNDS_TOLERANCE
            }
            .distinctBy(WeatherGrid::validAt)
            .sortedBy(WeatherGrid::validAt)
            .toList()
            .takeLast(maximumFrames)

    private companion object {
        const val BOUNDS_TOLERANCE = 1e-6
    }
}

/** Filters frames written by versions that still supported motion extrapolation. */
fun WeatherGrid.isObservedFrame(): Boolean = !sourceId.contains(LEGACY_EXTRAPOLATION_MARKER)

private const val LEGACY_EXTRAPOLATION_MARKER = "MOTION-EXTRAPOLATED"
