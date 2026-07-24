package com.rton.howstheweather.domain

import java.time.Duration
import java.time.Instant

class ForecastDecisionEngine {
    fun loadingHourly(issuedAt: Instant): ForecastDecision = ForecastDecision(
        state = RainState.UNAVAILABLE,
        headline = "正在取得未來一小時降雨預報",
        detail = "正在載入目前天氣與官方一小時累積降雨資料",
        eventWindow = null,
        series = listOf(ForecastPoint(60, null)),
        issuedAt = issuedAt,
        isStale = false,
    )

    fun evaluateHourlyAccumulation(
        millimeters: Float?,
        issuedAt: Instant,
        now: Instant = Instant.now(),
    ): ForecastDecision {
        if (millimeters == null || !millimeters.isFinite() || millimeters < 0f) {
            return unavailableHourly(issuedAt)
        }
        val state = classify(millimeters)
        val stale = Duration.between(issuedAt, now).toMinutes() > 30
        val headline = if (state == RainState.DRY) {
            "未來一小時應該不會下雨"
        } else {
            "未來一小時${label(state)}，預估累積 ${formatMillimeters(millimeters)} mm"
        }
        return ForecastDecision(
            state = state,
            headline = headline,
            detail = if (stale) {
                "資料已超過 30 分鐘，正在取得最新一小時預報"
            } else {
                "預報時間範圍：現在至 +60 分鐘"
            },
            eventWindow = null,
            series = listOf(ForecastPoint(60, millimeters)),
            issuedAt = issuedAt,
            isStale = stale,
        )
    }

    fun evaluate(
        series: List<ForecastPoint>,
        issuedAt: Instant,
        now: Instant = Instant.now(),
    ): ForecastDecision {
        val sorted = series.sortedBy { it.minutesFromNow }
        val current = sorted.firstOrNull()?.millimetersPerHour
        if (current == null) return unavailable(sorted, issuedAt)

        val currentState = classify(current)
        val event = when (currentState) {
            RainState.DRY -> firstStableIndex(sorted) { classify(it) != RainState.DRY }
            else -> firstStableIndex(sorted) { intensityRank(classify(it)) < intensityRank(currentState) }
        }
        val window = event?.let { index ->
            val end = sorted[index].minutesFromNow
            val start = sorted.getOrNull(index - 1)?.minutesFromNow ?: 0
            start..end
        }
        val headline = when {
            currentState == RainState.DRY && window != null -> "約 ${window.first}–${window.last} 分鐘後開始下雨"
            currentState == RainState.DRY -> "未來一小時應該不會下雨"
            window != null -> "目前${label(currentState)}，預計 ${window.first}–${window.last} 分鐘後轉小"
            else -> "目前${label(currentState)}，一小時內可能持續"
        }
        val stale = Duration.between(issuedAt, now).toMinutes() > 30
        return ForecastDecision(
            state = currentState,
            headline = headline,
            detail = if (stale) "資料已超過 30 分鐘，請重新整理" else "依 CWA 一小時定量降雨格點判斷",
            eventWindow = window,
            series = sorted,
            issuedAt = issuedAt,
            isStale = stale,
        )
    }

    private fun firstStableIndex(
        points: List<ForecastPoint>,
        predicate: (Float) -> Boolean,
    ): Int? = points.indices.firstOrNull { index ->
        val first = points[index].millimetersPerHour ?: return@firstOrNull false
        val secondIndex = points.indexOfFirst {
            it.minutesFromNow >= points[index].minutesFromNow + 10
        }
        val second = points.getOrNull(secondIndex)?.millimetersPerHour
        predicate(first) && second != null && predicate(second)
    }

    fun classify(value: Float): RainState = when {
        value < 0.1f -> RainState.DRY
        value < 2.5f -> RainState.LIGHT
        value < 10f -> RainState.MODERATE
        value < 40f -> RainState.HEAVY
        else -> RainState.EXTREME
    }

    private fun intensityRank(state: RainState): Int = when (state) {
        RainState.DRY -> 0
        RainState.LIGHT -> 1
        RainState.MODERATE -> 2
        RainState.HEAVY -> 3
        RainState.EXTREME -> 4
        RainState.UNAVAILABLE -> -1
    }

    private fun label(state: RainState): String = when (state) {
        RainState.DRY -> "無雨"
        RainState.LIGHT -> "小雨"
        RainState.MODERATE -> "中雨"
        RainState.HEAVY -> "大雨"
        RainState.EXTREME -> "強降雨"
        RainState.UNAVAILABLE -> "資料不足"
    }

    private fun formatMillimeters(value: Float): String =
        if (value < 10f) "%.1f".format(value) else "%.0f".format(value)

    private fun unavailable(series: List<ForecastPoint>, issuedAt: Instant) = ForecastDecision(
        state = RainState.UNAVAILABLE,
        headline = "暫時無法判斷未來一小時雨勢",
        detail = "找不到目標位置的有效數值格點",
        eventWindow = null,
        series = series,
        issuedAt = issuedAt,
        isStale = true,
    )

    private fun unavailableHourly(issuedAt: Instant) = ForecastDecision(
        state = RainState.UNAVAILABLE,
        headline = "暫時無法判斷未來一小時雨勢",
        detail = "未取得有效的未來一小時累積降雨資料",
        eventWindow = null,
        series = listOf(ForecastPoint(60, null)),
        issuedAt = issuedAt,
        isStale = true,
    )
}
