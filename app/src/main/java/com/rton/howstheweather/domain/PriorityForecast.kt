package com.rton.howstheweather.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class PriorityForecastPeriod(
    val label: String,
    val startAt: Instant,
    val endAt: Instant,
    val weatherDescription: String,
    val precipitationProbabilityPercent: Int?,
    val minimumTemperatureCelsius: Int?,
    val maximumTemperatureCelsius: Int?,
)

/** Reduces native forecast periods into the next useful day/night decisions. */
object PriorityForecastSelector {
    private val taipeiZone = ZoneId.of("Asia/Taipei")

    fun select(
        periods: List<AreaForecastPeriod>,
        now: Instant,
        limit: Int = 3,
        zone: ZoneId = taipeiZone,
    ): List<PriorityForecastPeriod> {
        if (limit <= 0) return emptyList()
        val today = now.atZone(zone).toLocalDate()
        return periods
            .asSequence()
            .filter { it.endAt > now }
            .sortedBy(AreaForecastPeriod::startAt)
            .groupBy { slotFor(it.startAt, zone) }
            .entries
            .sortedBy { (slot, _) -> slot.startAt(zone) }
            .take(limit)
            .map { (slot, slotPeriods) -> slotPeriods.toSummary(slot.label(today)) }
    }

    private fun List<AreaForecastPeriod>.toSummary(label: String): PriorityForecastPeriod {
        val mostRelevant = maxWithOrNull(
            compareBy<AreaForecastPeriod> { weatherPriority(it.weatherDescription) }
                .thenBy { it.precipitationProbabilityPercent ?: -1 },
        ) ?: error("Cannot summarize an empty forecast period")
        return PriorityForecastPeriod(
            label = label,
            startAt = minOf(AreaForecastPeriod::startAt),
            endAt = maxOf(AreaForecastPeriod::endAt),
            weatherDescription = mostRelevant.weatherDescription.ifBlank { "天氣未定" },
            precipitationProbabilityPercent = mapNotNull(AreaForecastPeriod::precipitationProbabilityPercent)
                .maxOrNull(),
            minimumTemperatureCelsius = mapNotNull(AreaForecastPeriod::minimumTemperatureCelsius).minOrNull(),
            maximumTemperatureCelsius = mapNotNull(AreaForecastPeriod::maximumTemperatureCelsius).maxOrNull(),
        )
    }

    private fun weatherPriority(description: String): Int = when {
        "雷" in description -> 5
        "雨" in description -> 4
        "雪" in description -> 3
        "陰" in description -> 2
        "雲" in description -> 1
        else -> 0
    }

    private fun slotFor(at: Instant, zone: ZoneId): DayPartSlot {
        val local = at.atZone(zone)
        return when (local.hour) {
            in 6..17 -> DayPartSlot(local.toLocalDate(), daytime = true)
            in 18..23 -> DayPartSlot(local.toLocalDate(), daytime = false)
            else -> DayPartSlot(local.toLocalDate().minusDays(1), daytime = false)
        }
    }

    private data class DayPartSlot(val date: LocalDate, val daytime: Boolean) {
        fun startAt(zone: ZoneId): Instant = date
            .atTime(if (daytime) 6 else 18, 0)
            .atZone(zone)
            .toInstant()

        fun label(today: LocalDate): String {
            val dayOffset = date.toEpochDay() - today.toEpochDay()
            return when {
                dayOffset == 0L && daytime -> "今天白天"
                dayOffset == 0L -> "今晚"
                dayOffset == 1L && daytime -> "明天白天"
                dayOffset == 1L -> "明晚"
                daytime -> "${date.monthValue}/${date.dayOfMonth} 白天"
                else -> "${date.monthValue}/${date.dayOfMonth} 晚上"
            }
        }
    }
}
