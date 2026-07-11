package com.rton.howstheweather.data

import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WindObservation
import java.time.Instant

data class WeatherSnapshot(
    val radar: WeatherGrid,
    val rainForecast: List<WeatherGrid>,
    val cloudFrames: List<WeatherGrid>,
    val forecastAtTarget: List<ForecastPoint>,
    val winds: List<WindObservation>,
    val issuedAt: Instant,
    val isDemo: Boolean,
    val hourlyAccumulationAtTarget: Float? = null,
    val notice: String? = null,
)

interface WeatherDataSource {
    suspend fun load(target: GeoPoint): WeatherSnapshot
}
