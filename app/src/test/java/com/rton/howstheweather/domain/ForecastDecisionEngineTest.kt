package com.rton.howstheweather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ForecastDecisionEngineTest {
    private val engine = ForecastDecisionEngine()
    private val issuedAt = Instant.parse("2026-07-11T12:00:00Z")

    @Test fun `dry forecast reports stable rain window`() {
        val result = engine.evaluate(
            series(0f, 0f, .4f, .8f, 2f, 1f, .2f),
            issuedAt,
            issuedAt,
        )
        assertEquals(RainState.DRY, result.state)
        assertEquals(10..20, result.eventWindow)
    }

    @Test fun `moderate rain ignores one frame dip and finds stable decrease`() {
        val result = engine.evaluate(
            series(6f, 1f, 7f, 2f, 1f, .2f, 0f),
            issuedAt,
            issuedAt,
        )
        assertEquals(RainState.MODERATE, result.state)
        assertEquals(20..30, result.eventWindow)
    }

    @Test fun `continuing heavy rain has no invented end time`() {
        val result = engine.evaluate(
            series(15f, 14f, 13f, 12f, 11f, 15f, 12f),
            issuedAt,
            issuedAt,
        )
        assertEquals(RainState.HEAVY, result.state)
        assertNull(result.eventWindow)
    }

    @Test fun `missing target grid returns unavailable`() {
        val result = engine.evaluate(listOf(ForecastPoint(0, null)), issuedAt, issuedAt)
        assertEquals(RainState.UNAVAILABLE, result.state)
        assertEquals("暫時無法判斷未來一小時雨勢", result.headline)
    }

    @Test fun `loading state leads with the one hour forecast priority`() {
        val result = engine.loadingHourly(issuedAt)

        assertEquals(RainState.UNAVAILABLE, result.state)
        assertEquals("正在取得未來一小時降雨預報", result.headline)
        assertEquals(false, result.isStale)
    }

    @Test fun `valid dry hourly accumulation reports no rain`() {
        val result = engine.evaluateHourlyAccumulation(0f, issuedAt, issuedAt)

        assertEquals(RainState.DRY, result.state)
        assertEquals("未來一小時應該不會下雨", result.headline)
    }

    @Test fun `official hourly accumulation does not invent minute event window`() {
        val result = engine.evaluateHourlyAccumulation(6.4f, issuedAt, issuedAt)
        assertEquals(RainState.MODERATE, result.state)
        assertNull(result.eventWindow)
        assertEquals(listOf(ForecastPoint(60, 6.4f)), result.series)
        assertEquals("預報時間範圍：現在至 +60 分鐘", result.detail)
    }

    @Test fun `invalid hourly sentinel stays unavailable instead of reporting no rain`() {
        val result = engine.evaluateHourlyAccumulation(-99f, issuedAt, issuedAt)

        assertEquals(RainState.UNAVAILABLE, result.state)
        assertEquals("暫時無法判斷未來一小時雨勢", result.headline)
        assertEquals("未取得有效的未來一小時累積降雨資料", result.detail)
        assertEquals(listOf(ForecastPoint(60, null)), result.series)
        assertNull(result.eventWindow)
    }

    @Test fun `stale hourly accumulation is explicitly labelled`() {
        val result = engine.evaluateHourlyAccumulation(6.4f, issuedAt, issuedAt.plusSeconds(31 * 60L))

        assertEquals(true, result.isStale)
        assertEquals("資料已超過 30 分鐘，正在取得最新一小時預報", result.detail)
    }

    private fun series(vararg values: Float?) = values.mapIndexed { index, value -> ForecastPoint(index * 10, value) }
}
