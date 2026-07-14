package com.rton.howstheweather.data

import com.rton.howstheweather.domain.AirQualityObservation
import com.rton.howstheweather.domain.GeoPoint
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

interface AirQualityDataSource {
    suspend fun loadNearest(target: GeoPoint): AirQualityObservation?
}

class MoenvAirQualityDataSource(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val parser: MoenvAirQualityParser = MoenvAirQualityParser(),
) : AirQualityDataSource {
    init { require(apiKey.isNotBlank()) }

    override suspend fun loadNearest(target: GeoPoint): AirQualityObservation? = withContext(Dispatchers.IO) {
        val url = "https://data.moenv.gov.tw/api/v2/aqx_p_432".toHttpUrl()
            .newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("limit", "1000")
            .addQueryParameter("format", "json")
            .build()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            check(it.isSuccessful) { "環境部 AQI 回應 ${it.code}" }
            parser.nearest(it.body.string(), target)
        }
    }
}

class MoenvAirQualityParser {
    fun nearest(json: String, target: GeoPoint): AirQualityObservation? =
        records(JSONTokener(json).nextValue())
            .mapNotNull(::parse)
            .minByOrNull { distanceKm(target, it.coordinate) }
            ?.takeIf { distanceKm(target, it.coordinate) <= MAX_STATION_DISTANCE_KM }

    private fun parse(record: JSONObject): AirQualityObservation? {
        val latitude = record.optString("latitude").toDoubleOrNull() ?: return null
        val longitude = record.optString("longitude").toDoubleOrNull() ?: return null
        val aqi = record.optString("aqi").toIntOrNull() ?: return null
        val observedAt = runCatching {
            LocalDateTime.parse(record.getString("publishtime"), TIME_FORMATTER)
                .atZone(TAIPEI_ZONE)
                .toInstant()
        }.getOrElse { Instant.EPOCH }
        return AirQualityObservation(
            stationName = record.optString("sitename").ifBlank { "鄰近測站" },
            coordinate = GeoPoint(latitude, longitude),
            aqi = aqi,
            status = record.optString("status").ifBlank { aqiStatus(aqi) },
            primaryPollutant = record.optString("pollutant").ifBlank { null },
            observedAt = observedAt,
        )
    }

    private fun records(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull(value::optJSONObject)
        is JSONObject -> when (val nested = value.opt("records")) {
            is JSONArray -> (0 until nested.length()).mapNotNull(nested::optJSONObject)
            is JSONObject -> listOf(nested)
            else -> listOf(value)
        }
        else -> emptyList()
    }

    private fun distanceKm(first: GeoPoint, second: GeoPoint): Double {
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(second.longitude - first.longitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun aqiStatus(aqi: Int): String = when (aqi) {
        in 0..50 -> "良好"
        in 51..100 -> "普通"
        in 101..150 -> "對敏感族群不健康"
        in 151..200 -> "對所有族群不健康"
        in 201..300 -> "非常不健康"
        else -> "危害"
    }

    private companion object {
        const val EARTH_RADIUS_KM = 6_371.0
        const val MAX_STATION_DISTANCE_KM = 120.0
        val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
