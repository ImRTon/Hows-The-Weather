package com.rton.howstheweather.data

import com.rton.howstheweather.domain.Quadrant
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CwaTropicalCycloneParserTest {
    private val parser = CwaTropicalCycloneParser()

    @Test
    fun parsesTrackRadiiAndForecast() {
        val cyclones = parser.parse(SAMPLE)

        assertEquals(1, cyclones.size)
        val cyclone = cyclones.single()
        assertEquals("颱風 薔琵", cyclone.displayName)
        assertEquals(29, cyclone.typhoonNumber)
        assertEquals(2, cyclone.track.size)

        val first = cyclone.track.first()
        assertNull(first.galeRadius)
        assertNull(first.stormRadius)

        val latest = cyclone.latest
        assertEquals(Instant.parse("2026-10-21T00:00:00Z"), latest.time)
        assertEquals(18.1, latest.center.latitude, 1e-9)
        assertEquals(130.1, latest.center.longitude, 1e-9)
        assertEquals(60f, latest.maxWindMetersPerSecond)
        assertEquals(895f, latest.pressureHectopascals)
        assertEquals("以每小時22轉18公里速度，向西北進行", latest.movingPrediction)
        val gale = checkNotNull(latest.galeRadius)
        assertEquals(280f, gale.meanKm)
        assertEquals(310f, gale.quadrantsKm[Quadrant.NE])
        assertEquals(240f, gale.quadrantsKm[Quadrant.SW])
        assertEquals(120f, checkNotNull(latest.stormRadius).meanKm)

        assertEquals(listOf(6, 96), cyclone.forecast.map { it.forecastHours })
        val sixHours = cyclone.forecast.first()
        assertEquals(Instant.parse("2026-10-21T06:00:00Z"), sixHours.validAt)
        assertEquals(40f, sixHours.probability70RadiusKm)
        assertTrue(checkNotNull(sixHours.galeRadius).quadrantsKm.isEmpty())
    }

    @Test
    fun missingValuesStayMissing() {
        val forecast = parser.parse(SAMPLE).single().forecast.last()

        assertNull(forecast.maxWindMetersPerSecond)
        assertNull(forecast.stormRadius)
        assertEquals(360f, forecast.probability70RadiusKm)
    }

    @Test
    fun emptyCycloneListMeansNoActiveCyclone() {
        val json = """{"success":"true","records":{"dataid":"C0034-005","TropicalCyclones":{}}}"""

        assertTrue(parser.parse(json).isEmpty())
    }

    @Test
    fun singleObjectsAreAcceptedInPlaceOfArrays() {
        val json = """
            {"success":"true","records":{"TropicalCyclones":{"TropicalCyclone":{
              "TyphoonName":"TEST","CwaTyphoonName":"","CwaTdNo":"5",
              "AnalysisData":{"Fix":{"DateTime":"2026-10-21T08:00:00+08:00",
                "CoordinateLongitude":"121.0","CoordinateLatitude":"20.0","MaxWindSpeed":"15"}}
            }}}}
        """.trimIndent()

        val cyclone = parser.parse(json).single()

        assertEquals("熱帶性低氣壓 TEST", cyclone.displayName)
        assertNull(cyclone.typhoonNumber)
        assertTrue(cyclone.forecast.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun failedResponseIsReported() {
        parser.parse("""{"success":"false","records":{}}""")
    }

    private companion object {
        val SAMPLE = """
            {"success":"true","records":{"dataid":"C0034-005","TropicalCyclones":{"TropicalCyclone":[{
              "Year":"2026","TyphoonName":"CHAMPI","CwaTyphoonName":"薔琵","CwaTdNo":"33","CwaTyNo":"29",
              "AnalysisData":{"Fix":[
                {"DateTime":"2026-10-21T08:00:00+08:00","CoordinateLongitude":"130.1","CoordinateLatitude":"18.1",
                 "MaxWindSpeed":"60","MaxGustSpeed":"75","Pressure":"895","MovingSpeed":"26","MovingDirection":"WNW",
                 "MovingPrediction":[{"value":"以每小時22轉18公里速度，向西北進行","lang":"zh-hant"},
                   {"value":"NW 22 KM/HR BECOMING 18 KM/HR","lang":"en-us"}],
                 "Circle15ms":{"Radius":"280","QuadrantRadii":{"Radius":[
                   {"value":"310","dir":"NE"},{"value":"290","dir":"SE"},{"value":"240","dir":"SW"},{"value":"270","dir":"NW"}]}},
                 "Circle25ms":{"Radius":"120","QuadrantRadii":{"Radius":[
                   {"value":"130","dir":"NE"},{"value":"130","dir":"SE"},{"value":"110","dir":"SW"},{"value":"120","dir":"NW"}]}}},
                {"DateTime":"2026-10-14T08:00:00+08:00","CoordinateLongitude":"160.3","CoordinateLatitude":"9.5",
                 "MaxWindSpeed":"12","MaxGustSpeed":"20","Pressure":"1004","MovingSpeed":"4","MovingDirection":"W"}
              ]},
              "ForecastData":{"Fix":[
                {"InitialTime":"2026-10-21T08:00:00+08:00","ForecastHour":"96","CoordinateLongitude":"130.9",
                 "CoordinateLatitude":"33.9","MaxWindSpeed":"","Pressure":"980",
                 "Circle15ms":{"Radius":"200"},"Circle25ms":{"Radius":"-"},"Radius70PercentProbability":"360"},
                {"InitialTime":"2026-10-21T08:00:00+08:00","ForecastHour":"6","CoordinateLongitude":"129.0",
                 "CoordinateLatitude":"18.8","MaxWindSpeed":"60","MaxGustSpeed":"75","Pressure":"895",
                 "Circle15ms":{"Radius":"280"},"Circle25ms":{"Radius":"120"},"Radius70PercentProbability":"40"}
              ]}
            }]}}}
        """.trimIndent()
    }
}
