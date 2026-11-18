package com.rton.howstheweather.widget

import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.AreaForecastPeriod
import com.rton.howstheweather.domain.CurrentWeatherObservation
import com.rton.howstheweather.domain.ForecastDecision
import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.RainState
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetWeatherFormatterTest {

    @Test
    fun `formats weekday, date, and subtle location badge in English`() {
        val sundayEvening = ZonedDateTime.of(2026, 11, 15, 20, 24, 0, 0, ZoneId.of("Asia/Taipei"))

        assertEquals("Sunday.", WidgetWeatherFormatter.formatWeekday(sundayEvening))
        assertEquals("November 15", WidgetWeatherFormatter.formatDateOnly(sundayEvening))
        assertEquals("· Taipei", WidgetWeatherFormatter.formatLocationBadge("臺北市大安區"))
        assertEquals("Good Evening!", WidgetWeatherFormatter.greetingForHour(sundayEvening.hour))
    }

    @Test
    fun `translates CWA weather codes and Chinese descriptions to English`() {
        assertEquals("mostly cloudy", WidgetWeatherFormatter.translateWeatherCondition("05", "多雲"))
        assertEquals("thunderstorms", WidgetWeatherFormatter.translateWeatherCondition("15", "午後短暫雷陣雨"))
        assertEquals("clear", WidgetWeatherFormatter.translateWeatherCondition("01", "晴"))
        assertEquals("light rain", WidgetWeatherFormatter.translateWeatherCondition(null, null, RainState.LIGHT))
    }

    @Test
    fun `builds fluent summary without time or single-quote artifacts`() {
        val now = Instant.parse("2026-11-15T12:24:00Z")
        val target = GeoPoint(25.0478, 121.5319)
        val current = CurrentWeatherObservation(
            stationName = "臺北",
            coordinate = target,
            weatherDescription = "多雲",
            temperatureCelsius = 23.4f,
            relativeHumidityPercent = 72f,
            windSpeedMetersPerSecond = 2.1f,
            windDirectionDegrees = 90f,
            precipitationTodayMillimeters = 0f,
            airPressureHectopascals = 1013f,
            uvIndex = 1,
            observedAt = now,
            sourceId = "O-A0003-001",
        )
        val forecast = AreaForecast(
            target = target,
            areaCoordinate = target,
            countyName = "臺北市",
            districtName = "中正區",
            periods = listOf(
                AreaForecastPeriod(
                    startAt = now,
                    endAt = now.plusSeconds(10_800),
                    precipitationProbabilityPercent = 20,
                    minimumTemperatureCelsius = 22,
                    maximumTemperatureCelsius = 24,
                    weatherDescription = "多雲",
                    weatherCode = "05",
                ),
            ),
            sourceId = "F-D0047-061",
        )
        val decision = ForecastDecision(
            state = RainState.DRY,
            headline = "未來一小時應該不會下雨",
            detail = "預報時間範圍：現在至 +60 分鐘",
            eventWindow = null,
            series = listOf(ForecastPoint(60, 0f)),
            issuedAt = now,
            isStale = false,
        )

        val summary = WidgetWeatherFormatter.fromAppState(
            targetDisplayName = "臺北市中正區",
            currentWeather = current,
            areaForecast = forecast,
            weeklyForecast = null,
            decision = decision,
            now = now,
        )

        val renderedSegments = WidgetWeatherFormatter.buildSummarySegments(
            greeting = "Good Evening!",
            timeSegment = "8:24",
            summary = summary,
            forDateFormatPattern = false,
        )
        val renderedText = renderedSegments.joinToString(separator = "") { it.text }
        assertEquals(
            "Good Evening!\nThe time is 8:24 and the weather outside is mostly cloudy (23°C) with a high of 24°C, a low of 22°C, and 20% rain chance.",
            renderedText,
        )
        assertFalse(renderedText.contains("'"))
    }

    @Test
    fun `resizes Wednesday and narrow widgets to fit on a single line`() {
        val defaultWidthDp = WidgetWeatherFormatter.resolveAvailableWeekdayWidthDp(0)
        val sundaySp = WidgetWeatherFormatter.computeWeekdayTextSizeSp("Sunday.", defaultWidthDp)
        val thursdaySp = WidgetWeatherFormatter.computeWeekdayTextSizeSp("Thursday.", defaultWidthDp)
        val wednesdaySp = WidgetWeatherFormatter.computeWeekdayTextSizeSp("Wednesday.", defaultWidthDp)

        assertEquals(50f, sundaySp, 0.01f)
        assertEquals(50f, thursdaySp, 0.01f)
        assertTrue("Expected Wednesday. to stay close to 50sp, was $wednesdaySp", wednesdaySp in 46f..49.5f)
        assertTrue(
            "Expected Wednesday. rendered width to fit within $defaultWidthDp dp",
            wednesdaySp * WidgetWeatherFormatter.estimateWeekdayWidthEm("Wednesday.") <= defaultWidthDp + 0.01f,
        )

        val narrowWidthDp = WidgetWeatherFormatter.resolveAvailableWeekdayWidthDp(180)
        val narrowMondaySp = WidgetWeatherFormatter.computeWeekdayTextSizeSp("Monday.", narrowWidthDp)
        assertTrue("Expected narrow widget to shrink Monday., was $narrowMondaySp", narrowMondaySp < 35f)
    }
}
