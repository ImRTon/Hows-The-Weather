package com.rton.howstheweather.data

import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class CwaWeatherDataSource(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val parser: CwaJsonGridParser = CwaJsonGridParser(),
    private val supplementaryDemoSource: WeatherDataSource = DemoWeatherDataSource(),
) : WeatherDataSource {
    init { require(apiKey.isNotBlank()) }

    override suspend fun load(target: GeoPoint): WeatherSnapshot = coroutineScope {
        val radarRequest = async { fetchGrid("O-A0059-001", WeatherUnit.DBZ) }
        val forecastRequest = async { fetchGrid("F-B0046-001", WeatherUnit.MILLIMETERS_ONE_HOUR) }
        val demoRequest = async { supplementaryDemoSource.load(target) }
        val radar = radarRequest.await()
        val forecast = forecastRequest.await()
        val demo = demoRequest.await()
        val hourlyAmount = forecast.sample(target)
        WeatherSnapshot(
            radar = radar,
            rainForecast = listOf(forecast),
            cloudFrames = demo.cloudFrames,
            forecastAtTarget = listOf(ForecastPoint(60, hourlyAmount)),
            winds = demo.winds,
            issuedAt = forecast.validAt,
            isDemo = false,
            hourlyAccumulationAtTarget = hourlyAmount,
            notice = null,
        )
    }

    private suspend fun fetchGrid(datasetId: String, unit: WeatherUnit) = withContext(Dispatchers.IO) {
        val url = "https://opendata.cwa.gov.tw/fileapi/v1/opendataapi/$datasetId".toHttpUrl()
            .newBuilder()
            .addQueryParameter("Authorization", apiKey)
            .addQueryParameter("format", "JSON")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "CWA $datasetId 回應 ${response.code}" }
            val body = response.body.string()
            parser.parse(body, unit, datasetId)
        }
    }
}

class CwaWithDemoFallbackDataSource(
    private val live: WeatherDataSource,
    private val fallback: WeatherDataSource = DemoWeatherDataSource(),
) : WeatherDataSource {
    override suspend fun load(target: GeoPoint): WeatherSnapshot = runCatching { live.load(target) }
        .getOrElse { error ->
            fallback.load(target).copy(
                notice = "CWA 載入失敗：${error.message ?: "未知錯誤"} · 已改用示範格點",
            )
        }
}
