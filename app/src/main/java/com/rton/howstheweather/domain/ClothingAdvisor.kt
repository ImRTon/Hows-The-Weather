package com.rton.howstheweather.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp

enum class TopWear(val hasThermalLayer: Boolean = false) {
    SHORT_SLEEVE,
    LIGHT_LONG_SLEEVE,
    WARM_LAYER(hasThermalLayer = true),
    THERMAL_LAYER(hasThermalLayer = true),
}
enum class OuterWear { NONE, CARRY_LIGHT, LIGHT, REGULAR, HEAVY, DOWN }
enum class BottomWear { SHORTS, SHORTS_OR_TROUSERS, TROUSERS, WARM_TROUSERS }
enum class HatAdvice { NONE, SUN, SUN_WINDY, BEANIE }
enum class RainGear { NONE, FOLDING_UMBRELLA, UMBRELLA }

enum class ClothingWindow(val hours: Int, val label: String) {
    HOURS_6(6, "6 小時"),
    HOURS_12(12, "12 小時"),
    HOURS_24(24, "24 小時"),
    ;

    val duration: Duration = Duration.ofHours(hours.toLong())

    companion object {
        fun fromHours(hours: Int): ClothingWindow =
            entries.firstOrNull { it.hours == hours } ?: HOURS_12
    }
}

data class ClothingAdvice(
    val windowStart: Instant,
    val windowEnd: Instant,
    val areaName: String,
    val sourceId: String,
    /** Official CWA feels-like range across the window, before personal or damp-chill adjustment. */
    val feelsLikeMinimum: Int,
    val feelsLikeMaximum: Int,
    /** The values the tiers were chosen from. */
    val effectiveMinimum: Float,
    val effectiveMaximum: Float,
    val top: TopWear,
    val outer: OuterWear,
    val outerWaterproof: Boolean,
    val bottom: BottomWear,
    val hat: HatAdvice,
    val rainGear: RainGear,
    val dampChill: Boolean,
    val muggy: Boolean,
    val maxRainChancePercent: Int?,
    val uvIndex: Int?,
    /** True when at least one period lacked a published 體感溫度 and the CWA formula was used instead. */
    val usedEstimatedFeelsLike: Boolean,
)

/**
 * Maps official feels-like temperatures to Taiwanese everyday clothing tiers.
 *
 * Thresholds were calibrated against personal reports: CWA feels-like 22–28 °C
 * was comfortable in short sleeves; about 16–17 °C in drizzle needed a light
 * long-sleeve top with a regular jacket; and a dry 15–16 °C day was fine in a
 * light long-sleeve top with a light jacket. The 18–22 °C band and everything
 * below 15 °C have no report yet.
 */
object ClothingAdvisor {
    const val TOLERANCE_MIN = -4
    const val TOLERANCE_MAX = 4
    val WINDOW: Duration = Duration.ofHours(12)

    private const val DAMP_CHILL_BELOW = 18f
    private const val DAMP_CHILL_HUMIDITY = 85
    private const val DAMP_CHILL_PENALTY = 1.5f
    private const val RAIN_LIKELY_PERCENT = 50
    private const val RAIN_POSSIBLE_PERCENT = 30
    private const val SUN_HAT_UV = 6
    private const val WINDY_METERS_PER_SECOND = 8
    private const val FALLBACK_HUMIDITY = 75
    private const val FALLBACK_WIND = 2
    private const val BEANIE_BELOW = 10f

    /** Personal sensitivity grows in the cold: one slider step is 1 °C at ≥22 °C and 2 °C at ≤10 °C. */
    private const val TOLERANCE_LINEAR_ABOVE = 22f
    private const val TOLERANCE_DOUBLED_BELOW = 10f

    internal fun toleranceGain(feelsLike: Float): Float {
        val coldness = (TOLERANCE_LINEAR_ABOVE - feelsLike) / (TOLERANCE_LINEAR_ABOVE - TOLERANCE_DOUBLED_BELOW)
        return 1f + coldness.coerceIn(0f, 1f)
    }

    internal fun personalized(feelsLike: Float, steps: Int): Float = feelsLike + steps * toleranceGain(feelsLike)

    fun advise(
        forecast: AreaForecast?,
        weekly: AreaForecast?,
        tolerance: Int,
        now: Instant,
        zone: ZoneId,
        rainingNow: Boolean = false,
        window: Duration = WINDOW,
    ): ClothingAdvice? {
        forecast ?: return null
        val windowEnd = now.plus(window)
        val periods = forecast.periods.filter { it.endAt.isAfter(now) && it.startAt.isBefore(windowEnd) }
        if (periods.isEmpty()) return null

        var estimated = false
        val ranges = periods.mapNotNull { period ->
            val published = period.minimumApparentTemperatureCelsius to period.maximumApparentTemperatureCelsius
            if (published.first != null && published.second != null) {
                published.first!! to published.second!!
            } else {
                val minimum = period.minimumTemperatureCelsius ?: return@mapNotNull null
                val maximum = period.maximumTemperatureCelsius ?: return@mapNotNull null
                val humidity = period.relativeHumidityPercent ?: FALLBACK_HUMIDITY
                val wind = period.windSpeedMetersPerSecond ?: FALLBACK_WIND
                estimated = true
                Math.round(cwaApparentTemperature(minimum.toFloat(), humidity.toFloat(), wind.toFloat())) to
                    Math.round(cwaApparentTemperature(maximum.toFloat(), humidity.toFloat(), wind.toFloat()))
            }
        }
        if (ranges.isEmpty()) return null
        val feelsMinimum = ranges.minOf { it.first }
        val feelsMaximum = ranges.maxOf { it.second }

        val maxRain = periods.mapNotNull(AreaForecastPeriod::precipitationProbabilityPercent).maxOrNull()
        val maxHumidity = periods.mapNotNull(AreaForecastPeriod::relativeHumidityPercent).maxOrNull()
        val maxWind = periods.mapNotNull(AreaForecastPeriod::windSpeedMetersPerSecond).maxOrNull()
        val rainLikely = rainingNow || (maxRain ?: 0) >= RAIN_LIKELY_PERCENT

        val dampChill = feelsMinimum < DAMP_CHILL_BELOW &&
            ((maxHumidity ?: 0) >= DAMP_CHILL_HUMIDITY || rainLikely)
        val penalty = if (dampChill) DAMP_CHILL_PENALTY else 0f
        val steps = tolerance.coerceIn(TOLERANCE_MIN, TOLERANCE_MAX)
        val effectiveMinimum = personalized(feelsMinimum - penalty, steps)
        val effectiveMaximum = personalized(feelsMaximum - penalty, steps)
        val effectiveMiddle = (effectiveMinimum + effectiveMaximum) / 2f

        val outer = outerFor(effectiveMinimum)
        val daylight = coversMidday(now, windowEnd, zone)
        val uv = if (daylight) uvFor(weekly, now, windowEnd) else null
        val sunny = uv != null && uv >= SUN_HAT_UV && !rainLikely
        val hat = when {
            effectiveMinimum < BEANIE_BELOW -> HatAdvice.BEANIE
            sunny && (maxWind ?: 0) >= WINDY_METERS_PER_SECOND -> HatAdvice.SUN_WINDY
            sunny -> HatAdvice.SUN
            else -> HatAdvice.NONE
        }

        return ClothingAdvice(
            windowStart = now,
            windowEnd = windowEnd,
            areaName = forecast.displayName,
            sourceId = forecast.sourceId,
            feelsLikeMinimum = feelsMinimum,
            feelsLikeMaximum = feelsMaximum,
            effectiveMinimum = effectiveMinimum,
            effectiveMaximum = effectiveMaximum,
            top = topFor(effectiveMiddle),
            outer = outer,
            outerWaterproof = rainLikely && outer != OuterWear.NONE,
            bottom = bottomFor(effectiveMiddle),
            hat = hat,
            rainGear = when {
                rainLikely -> RainGear.UMBRELLA
                (maxRain ?: 0) >= RAIN_POSSIBLE_PERCENT -> RainGear.FOLDING_UMBRELLA
                else -> RainGear.NONE
            },
            dampChill = dampChill,
            muggy = effectiveMaximum >= 28f && (maxHumidity ?: 0) >= 75,
            maxRainChancePercent = maxRain,
            uvIndex = uv,
            usedEstimatedFeelsLike = estimated,
        )
    }

    internal fun topFor(middle: Float): TopWear = when {
        middle >= 18f -> TopWear.SHORT_SLEEVE
        middle >= 13f -> TopWear.LIGHT_LONG_SLEEVE
        middle >= 9f -> TopWear.WARM_LAYER
        else -> TopWear.THERMAL_LAYER
    }

    // Bands widen toward the cold: 2 °C apart near short-sleeve weather, 4 °C apart below 15 °C.
    internal fun outerFor(minimum: Float): OuterWear = when {
        minimum >= 22f -> OuterWear.NONE
        minimum >= 20f -> OuterWear.CARRY_LIGHT
        minimum >= 15f -> OuterWear.LIGHT
        minimum >= 11f -> OuterWear.REGULAR
        minimum >= 7f -> OuterWear.HEAVY
        else -> OuterWear.DOWN
    }

    internal fun bottomFor(middle: Float): BottomWear = when {
        middle >= 26f -> BottomWear.SHORTS
        middle >= 22f -> BottomWear.SHORTS_OR_TROUSERS
        middle >= 10f -> BottomWear.TROUSERS
        else -> BottomWear.WARM_TROUSERS
    }

    /** CWA's published feels-like formula; used only when 體感溫度 is missing. */
    fun cwaApparentTemperature(celsius: Float, humidityPercent: Float, windMetersPerSecond: Float): Float {
        val vapourPressure = humidityPercent / 100f * 6.105f * exp(17.27f * celsius / (237.7f + celsius))
        return 1.04f * celsius + 0.2f * vapourPressure - 0.65f * windMetersPerSecond - 2.7f
    }

    private fun coversMidday(start: Instant, end: Instant, zone: ZoneId): Boolean {
        var cursor = start
        while (cursor.isBefore(end)) {
            if (cursor.atZone(zone).hour in 9..15) return true
            cursor = cursor.plus(Duration.ofHours(1))
        }
        return false
    }

    private fun uvFor(weekly: AreaForecast?, start: Instant, end: Instant): Int? =
        weekly?.periods
            ?.filter { it.endAt.isAfter(start) && it.startAt.isBefore(end) }
            ?.mapNotNull(AreaForecastPeriod::uvIndex)
            ?.maxOrNull()
}
