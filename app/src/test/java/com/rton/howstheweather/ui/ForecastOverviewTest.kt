package com.rton.howstheweather.ui

import com.rton.howstheweather.domain.AreaForecastPeriod
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastOverviewTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val now = Instant.parse("2026-10-02T04:00:00Z") // 12:00 Taipei

    private fun period(startUtc: String, chance: Int?) = Instant.parse(startUtc).let { start ->
        AreaForecastPeriod(
            startAt = start,
            endAt = start.plusSeconds(3 * 3600),
            precipitationProbabilityPercent = chance,
            minimumTemperatureCelsius = 25,
            maximumTemperatureCelsius = 25,
            weatherDescription = "多雲",
            weatherCode = null,
        )
    }

    @Test
    fun outlookNamesMostLikelyRainWindowToday() {
        val periods = listOf(
            period("2026-10-02T03:00:00Z", 20),
            period("2026-10-02T06:00:00Z", 60),
            period("2026-10-02T09:00:00Z", 60),
            period("2026-10-02T12:00:00Z", 10),
        )
        assertEquals("14:00–17:00 最可能下雨，降雨機率 60%", rainOutlook(periods, now, zone))
    }

    @Test
    fun outlookPrefixesDayOnlyWhenPeakIsNotToday() {
        val tonight = listOf(period("2026-10-02T12:00:00Z", 10), period("2026-10-02T15:00:00Z", 40))
        assertEquals("23:00–02:00 最可能下雨，降雨機率 40%", rainOutlook(tonight, now, zone))
        val tomorrow = listOf(period("2026-10-02T18:00:00Z", 50))
        assertEquals("明天 02:00–05:00 最可能下雨，降雨機率 50%", rainOutlook(tomorrow, now, zone))
    }

    @Test
    fun outlookDoesNotClaimRainWhenChancesAreLow() {
        val periods = listOf(period("2026-10-02T03:00:00Z", 0), period("2026-10-02T06:00:00Z", 10))
        assertEquals("近 6 小時不太會下雨，降雨機率最高 10%", rainOutlook(periods, now, zone))
    }

    @Test
    fun outlookHandlesMissingChances() {
        assertEquals("暫無近期降雨機率", rainOutlook(listOf(period("2026-10-02T03:00:00Z", null)), now, zone))
    }

    @Test
    fun sceneFollowsOfficialDescription() {
        assertEquals(WeatherScene.THUNDERSTORM, weatherSceneFor("多雲午後短暫雷陣雨", true))
        assertEquals(WeatherScene.RAIN, weatherSceneFor("陰短暫雨", true))
        assertEquals(WeatherScene.HEAVY_RAIN, weatherSceneFor("陰有大雨", true))
        assertEquals(WeatherScene.PARTLY_CLOUDY_DAY, weatherSceneFor("晴時多雲", true))
        assertEquals(WeatherScene.CLEAR_NIGHT, weatherSceneFor("晴天", false))
        assertEquals(WeatherScene.OVERCAST, weatherSceneFor("多雲時陰", true))
        assertEquals(WeatherScene.CLOUDY, weatherSceneFor("多雲", true))
        assertEquals(null, weatherSceneFor("天氣未定", true))
    }

    @Test
    fun uprightStillPhoneRainFallsStraightDown() {
        val fall = precipitationFallVector(0f, 9.81f, null, null)
        assertEquals(0f, fall.x, 1e-4f)
        assertEquals(1f, fall.y, 1e-4f)
    }

    @Test
    fun tiltingRightSideDownSlantsRainToTheRight() {
        // Rotating clockwise makes the sensor x axis read negative.
        val fall = precipitationFallVector(-4f, 8.9f, null, null)
        assertTrue(fall.x > .3f)
    }

    @Test
    fun westerlyWindPushesRainEast() {
        val fall = precipitationFallVector(0f, 9.81f, windSpeedMetersPerSecond = 8f, windDirectionDegrees = 270f)
        assertTrue(fall.x > 0f)
        val calm = precipitationFallVector(0f, 9.81f, windSpeedMetersPerSecond = 0f, windDirectionDegrees = 270f)
        assertEquals(0f, calm.x, 1e-4f)
    }

    @Test
    fun aqiLevelsUseEqualWidthCategorySegments() {
        assertEquals(0f, aqiLevelFraction(0), 1e-4f)
        assertEquals(1f / 6f, aqiLevelFraction(50), 1e-4f)
        assertEquals(1.5f / 6f, aqiLevelFraction(75), 1e-4f)
        assertEquals(5f / 6f, aqiLevelFraction(300), 1e-4f)
        assertEquals(1f, aqiLevelFraction(800), 1e-4f)
        assertEquals(0, aqiLevelIndex(42))
        assertEquals(2, aqiLevelIndex(120))
        assertEquals(5, aqiLevelIndex(500))
    }

    @Test
    fun flatPhoneKeepsRainFallingDownTheScreen() {
        val fall = precipitationFallVector(0f, 0f, null, null)
        assertEquals(1f, fall.y, 1e-4f)
    }
}
