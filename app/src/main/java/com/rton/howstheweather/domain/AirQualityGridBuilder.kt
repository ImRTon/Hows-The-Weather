package com.rton.howstheweather.domain

import java.time.Instant
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Builds an inverse-distance-weighted (IDW) [WeatherGrid] from MOENV air-quality stations
 * with a soft radial alpha envelope (`alphaWeights`).
 *
 * Each station exerts full influence within [CORE_RADIUS_KM] and smoothly tapers to zero at
 * [MAX_INFLUENCE_KM], keeping footprints honest around populated basins instead of spilling
 * hard-edged circles far over open ocean or unmonitored mountain ranges.
 */
object AirQualityGridBuilder {
    const val CORE_RADIUS_KM = 6.5
    const val MAX_INFLUENCE_KM = 16.0

    private const val WIDTH = 241
    private const val HEIGHT = 277
    private const val MIN_DISTANCE_KM = 0.25
    private const val KM_PER_DEG_LAT = 111.32
    private val TAIWAN_BOUNDS = GeoBounds(
        south = 21.8,
        west = 118.1,
        north = 26.4,
        east = 122.1,
    )

    fun build(stations: List<AirQualityObservation>): WeatherGrid? {
        if (stations.isEmpty()) return null
        val cellCount = WIDTH * HEIGHT
        val values = FloatArray(cellCount) { Float.NaN }
        val alphaWeights = FloatArray(cellCount)
        val latStep = (TAIWAN_BOUNDS.north - TAIWAN_BOUNDS.south) / (HEIGHT - 1)
        val lonStep = (TAIWAN_BOUNDS.east - TAIWAN_BOUNDS.west) / (WIDTH - 1)
        val maxRadiusSq = MAX_INFLUENCE_KM * MAX_INFLUENCE_KM
        val minDistSq = MIN_DISTANCE_KM * MIN_DISTANCE_KM
        val fadeSpan = MAX_INFLUENCE_KM - CORE_RADIUS_KM
        val latestObserved = stations.maxOfOrNull(AirQualityObservation::observedAt) ?: Instant.EPOCH

        val stationLats = DoubleArray(stations.size) { stations[it].coordinate.latitude }
        val stationLons = DoubleArray(stations.size) { stations[it].coordinate.longitude }
        val stationAqis = FloatArray(stations.size) { stations[it].aqi.toFloat() }

        for (row in 0 until HEIGHT) {
            val lat = TAIWAN_BOUNDS.north - row * latStep
            val kmPerDegLon = KM_PER_DEG_LAT * cos(Math.toRadians(lat))
            val rowOffset = row * WIDTH
            for (col in 0 until WIDTH) {
                val lon = TAIWAN_BOUNDS.west + col * lonStep
                var weightedSum = 0.0
                var weightTotal = 0.0
                var maxPresence = 0.0
                var exactValue = Float.NaN

                for (i in stations.indices) {
                    val dLatKm = (lat - stationLats[i]) * KM_PER_DEG_LAT
                    val dLonKm = (lon - stationLons[i]) * kmPerDegLon
                    val distSq = dLatKm * dLatKm + dLonKm * dLonKm
                    if (distSq >= maxRadiusSq) continue
                    if (distSq <= minDistSq) {
                        exactValue = stationAqis[i]
                        maxPresence = 1.0
                        break
                    }
                    val dist = sqrt(distSq)
                    val presence = if (dist <= CORE_RADIUS_KM) {
                        1.0
                    } else {
                        val t = ((MAX_INFLUENCE_KM - dist) / fadeSpan).coerceIn(0.0, 1.0)
                        // Cubic Hermite smoothstep for seamless atmospheric edge feathering.
                        t * t * (3.0 - 2.0 * t)
                    }
                    if (presence > maxPresence) maxPresence = presence
                    val weight = presence / distSq
                    weightedSum += weight * stationAqis[i]
                    weightTotal += weight
                }

                val idx = rowOffset + col
                if (exactValue.isFinite()) {
                    values[idx] = exactValue
                    alphaWeights[idx] = 1f
                } else if (weightTotal > 0.0 && maxPresence > 0.0) {
                    values[idx] = (weightedSum / weightTotal).toFloat()
                    alphaWeights[idx] = maxPresence.toFloat()
                }
            }
        }

        return WeatherGrid(
            width = WIDTH,
            height = HEIGHT,
            values = values,
            unit = WeatherUnit.AQI,
            bounds = TAIWAN_BOUNDS,
            resolutionKm = 1.85,
            validAt = latestObserved,
            sourceId = "MOENV_AQX_P_432",
            alphaWeights = alphaWeights,
        )
    }
}
