package com.rton.howstheweather.data

import java.time.Instant
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CwaSatelliteAcquisitionTest {
    @Test fun `latest regions read native fileapi metadata and preserve source URL time and bounds`() = runTest {
        val requests = mutableListOf<Request>()
        val source = source(requests) { request ->
            metadata(CwaSatelliteProduct.entries.single { it.datasetId == request.url.pathSegments.last() })
        }

        val taiwan = source.fetchSatelliteMetadata(CwaSatelliteProduct.TAIWAN)
        val eastAsia = source.fetchSatelliteMetadata(CwaSatelliteProduct.EAST_ASIA)

        assertEquals(Instant.parse("2026-11-10T12:40:00Z"), taiwan.observedAt)
        assertEquals(Instant.parse("2026-11-10T12:40:00Z"), eastAsia.observedAt)
        assertTrue(taiwan.productUrl.endsWith("Observation/O-C0042-004.jpg"))
        assertTrue(eastAsia.productUrl.endsWith("Observation/O-B0032-003.jpg"))
        assertEquals(CwaSatelliteProduct.TAIWAN.bounds, taiwan.bounds)
        assertEquals(CwaSatelliteProduct.EAST_ASIA.bounds, eastAsia.bounds)
        assertEquals(2, requests.size)
        for (request in requests) {
            assertEquals("opendata.cwa.gov.tw", request.url.host)
            assertTrue(request.url.encodedPath.startsWith("/fileapi/v1/opendataapi/"))
            assertEquals("JSON", request.url.queryParameter("format"))
            assertEquals("test-key", request.header("Authorization"))
            assertNull(request.url.queryParameter("Authorization"))
            assertFalse(request.url.toString().contains("test-key"))
        }
    }

    @Test fun `cloud history request retains latest only without any network or image downloads`() = runTest {
        val requests = mutableListOf<Request>()
        val source = source(requests) { error("Latest-only cloud history must not fetch anything") }
        val latest = com.rton.howstheweather.domain.WeatherGrid(
            width = 2, height = 2, values = FloatArray(4),
            unit = com.rton.howstheweather.domain.WeatherUnit.LUMINANCE,
            bounds = CwaSatelliteProduct.TAIWAN.bounds, resolutionKm = 1.0,
            validAt = Instant.parse("2026-11-10T12:40:00Z"), sourceId = CwaSatelliteProduct.TAIWAN.sourceId,
        )
        val snapshot = WeatherSnapshot(radar = latest, rainForecast = emptyList(),
            cloudFrames = listOf(latest.copy(validAt = latest.validAt.minusSeconds(600)), latest),
            cloudRegionalFrames = listOf(latest), forecastAtTarget = emptyList(),
            winds = emptyList(), issuedAt = latest.validAt)

        val result = source.historyUpdates(snapshot, WeatherHistoryKind.CLOUD).last()

        assertTrue(requests.isEmpty())
        assertEquals(listOf(latest), result.cloudFrames)
        assertEquals(listOf(latest), result.cloudRegionalFrames)
    }

    @Test fun `refresh reads current metadata instead of pinning first observation in memory`() = runTest {
        val requests = mutableListOf<Request>()
        var downloads = 0
        val source = source(requests) {
            downloads++
            metadata(CwaSatelliteProduct.TAIWAN, if (downloads == 1) "20:40" else "20:50")
        }

        val first = source.fetchSatelliteMetadata(CwaSatelliteProduct.TAIWAN)
        val refreshed = source.fetchSatelliteMetadata(CwaSatelliteProduct.TAIWAN)

        assertEquals(2, downloads)
        assertEquals(first.observedAt.plusSeconds(600), refreshed.observedAt)
        assertEquals(first.productUrl, refreshed.productUrl)
    }

    @Test fun `invalid native metadata fails without animation or history fallback`() = runTest {
        val requests = mutableListOf<Request>()
        val source = source(requests) { "{}" }

        assertTrue(runCatching { source.fetchSatelliteMetadata(CwaSatelliteProduct.TAIWAN) }.isFailure)
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.startsWith("/fileapi/"))
    }

    @Test fun `native dimensions and provenance reject reduced or animation East Asia frames`() {
        CwaSatelliteProduct.TAIWAN.validateDimensions(800, 800)
        CwaSatelliteProduct.EAST_ASIA.validateDimensions(1200, 1200)
        assertTrue(runCatching { CwaSatelliteProduct.EAST_ASIA.validateDimensions(1000, 1000) }.isFailure)
        assertTrue(runCatching { CwaSatelliteProduct.TAIWAN.validateDimensions(400, 400) }.isFailure)
        assertEquals("O-C0042-004|fileapi-native", CwaSatelliteProduct.TAIWAN.sourceId)
        assertEquals("O-B0032-003|fileapi-native", CwaSatelliteProduct.EAST_ASIA.sourceId)
    }

    private fun source(requests: MutableList<Request>, body: (Request) -> String): CwaWeatherDataSource {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(requests) { requests += chain.request() }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CwaWeatherDataSource(apiKey = "test-key", client = client)
    }

    private fun metadata(product: CwaSatelliteProduct, time: String = "20:40"): String = """
        {"cwaopendata":{"dataset":{
          "GeoInfo":{"LongitudeRange":"${product.bounds.west}-${product.bounds.east}",
                     "LatitudeRange":"${product.bounds.south}-${product.bounds.north}"},
          "ObsTime":{"Datetime":"2026-11-10T$time:00+08:00"},
          "Resource":{"ProductURL":"https://cwaopendata.s3.ap-northeast-1.amazonaws.com/Observation/${product.datasetId}.jpg"}
        }}}
    """.trimIndent()
}
