package com.rton.howstheweather.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClothingAdvisorTest {
    private val zone = ZoneId.of("Asia/Taipei")

    @Test fun `warm humid day calibrated as comfortable in short sleeves`() {
        // 2026-11-18 Taipei: CWA feels-like 22–28 °C between 08:00 and 19:00.
        val now = Instant.parse("2026-11-18T00:00:00Z")
        val advice = advise(now, period(now, 0, 22, 28, rain = 10, humidity = 75), period(now, 3, 22, 26))!!

        assertEquals(TopWear.SHORT_SLEEVE, advice.top)
        assertEquals(OuterWear.NONE, advice.outer)
        assertEquals(BottomWear.SHORTS_OR_TROUSERS, advice.bottom)
        assertFalse(advice.dampChill)
    }

    @Test fun `cool drizzly morning calibrated as light long sleeves with a regular jacket`() {
        // 2026-11-19 Taipei: about 16–18 °C, drizzle, humidity in the high 80s.
        val now = Instant.parse("2026-11-19T00:00:00Z")
        val advice = advise(now, period(now, 0, 16, 18, rain = 30, humidity = 88))!!

        assertTrue(advice.dampChill)
        assertEquals(TopWear.LIGHT_LONG_SLEEVE, advice.top)
        assertEquals(OuterWear.REGULAR, advice.outer)
        assertEquals(BottomWear.TROUSERS, advice.bottom)
    }

    @Test fun `tolerance shifts tiers in both directions`() {
        val now = Instant.parse("2026-11-19T00:00:00Z")
        val period = period(now, 0, 19, 21, humidity = 70)

        assertEquals(OuterWear.LIGHT, advise(now, period)!!.outer)
        assertEquals(OuterWear.NONE, advise(now, period, tolerance = 3)!!.outer)
        assertEquals(OuterWear.REGULAR, advise(now, period, tolerance = -2)!!.outer)
        assertEquals(TopWear.LIGHT_LONG_SLEEVE, advise(now, period, tolerance = -4)!!.top)
    }

    @Test fun `tolerance is clamped to the slider range`() {
        val now = Instant.parse("2026-11-19T00:00:00Z")
        val period = period(now, 0, 19, 21, humidity = 70)

        assertEquals(
            advise(now, period, tolerance = ClothingAdvisor.TOLERANCE_MAX)!!.effectiveMinimum,
            advise(now, period, tolerance = 99)!!.effectiveMinimum,
            0f,
        )
    }

    @Test fun `window covers only the next twelve hours`() {
        val now = Instant.parse("2026-11-19T00:00:00Z")
        val advice = advise(now, period(now, 0, 24, 26), period(now, 15, 8, 10))!!

        assertEquals(24, advice.feelsLikeMinimum)
        assertEquals(OuterWear.NONE, advice.outer)
    }

    @Test fun `likely rain asks for a waterproof jacket and an umbrella`() {
        val now = Instant.parse("2026-11-19T00:00:00Z")
        val advice = advise(now, period(now, 0, 17, 19, rain = 70))!!

        assertTrue(advice.outerWaterproof)
        assertEquals(RainGear.UMBRELLA, advice.rainGear)
    }

    @Test fun `official rain now counts as rain even with a low area probability`() {
        val now = Instant.parse("2026-11-19T00:00:00Z")
        val advice = ClothingAdvisor.advise(forecast(period(now, 0, 17, 19, rain = 10)), null, 0, now, zone, rainingNow = true)!!

        assertEquals(RainGear.UMBRELLA, advice.rainGear)
    }

    @Test fun `high daytime uv suggests a sun hat and cold suggests a beanie`() {
        val now = Instant.parse("2026-07-14T00:00:00Z") // 08:00 local
        val weekly = forecast(period(now, -2, 28, 34, uv = 9, hours = 12))
        val sunny = ClothingAdvisor.advise(forecast(period(now, 0, 28, 34)), weekly, 0, now, zone)!!
        assertEquals(HatAdvice.SUN, sunny.hat)
        assertEquals(TopWear.SHORT_SLEEVE, sunny.top)
        assertEquals(BottomWear.SHORTS, sunny.bottom)

        val cold = advise(now, period(now, 0, 8, 11))!!
        assertEquals(HatAdvice.BEANIE, cold.hat)
        assertEquals(OuterWear.DOWN, cold.outer)
    }

    @Test fun `uv is ignored at night`() {
        val now = Instant.parse("2026-07-14T12:00:00Z") // 20:00 local; window ends 08:00
        val weekly = forecast(period(now, -14, 28, 34, uv = 9, hours = 12))
        val advice = ClothingAdvisor.advise(forecast(period(now, 0, 28, 30)), weekly, 0, now, zone)!!

        assertNull(advice.uvIndex)
        assertEquals(HatAdvice.NONE, advice.hat)
    }

    @Test fun `missing feels-like falls back to the CWA formula`() {
        val now = Instant.parse("2026-11-19T00:00:00Z")
        val period = period(now, 0, null, null, humidity = 78).copy(
            minimumTemperatureCelsius = 18,
            maximumTemperatureCelsius = 18,
            windSpeedMetersPerSecond = 2,
        )
        val advice = advise(now, period)!!

        assertTrue(advice.usedEstimatedFeelsLike)
        assertEquals(18, advice.feelsLikeMinimum)
        assertEquals(17.9f, ClothingAdvisor.cwaApparentTemperature(18f, 78f, 2f), .1f)
    }

    @Test fun `no usable temperature yields no advice`() {
        val now = Instant.parse("2026-11-19T00:00:00Z")
        assertNull(advise(now, period(now, 0, null, null)))
        assertNull(ClothingAdvisor.advise(null, null, 0, now, zone))
    }

    private fun advise(now: Instant, vararg periods: AreaForecastPeriod, tolerance: Int = 0) =
        ClothingAdvisor.advise(forecast(*periods), null, tolerance, now, zone)

    private fun forecast(vararg periods: AreaForecastPeriod) = AreaForecast(
        target = GeoPoint(25.04, 121.52),
        areaCoordinate = GeoPoint(25.04, 121.52),
        countyName = "臺北市",
        districtName = "中正區",
        periods = periods.toList(),
        sourceId = "F-D0047-061",
    )

    private fun period(
        now: Instant,
        startHour: Long,
        minimumFeelsLike: Int?,
        maximumFeelsLike: Int?,
        rain: Int? = 10,
        humidity: Int? = 70,
        uv: Int? = null,
        hours: Long = 3,
    ) = AreaForecastPeriod(
        startAt = now.plusSeconds(startHour * 3600),
        endAt = now.plusSeconds((startHour + hours) * 3600),
        precipitationProbabilityPercent = rain,
        minimumTemperatureCelsius = minimumFeelsLike,
        maximumTemperatureCelsius = maximumFeelsLike,
        weatherDescription = "多雲",
        weatherCode = "04",
        relativeHumidityPercent = humidity,
        minimumApparentTemperatureCelsius = minimumFeelsLike,
        maximumApparentTemperatureCelsius = maximumFeelsLike,
        uvIndex = uv,
    )
}
