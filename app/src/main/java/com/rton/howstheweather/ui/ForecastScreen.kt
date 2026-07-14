package com.rton.howstheweather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rton.howstheweather.domain.AirQualityObservation
import com.rton.howstheweather.domain.AreaForecastPeriod
import com.rton.howstheweather.domain.HomeUiState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ForecastScreen(state: HomeUiState, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.of("Asia/Taipei") }
    val dayFormatter = remember {
        DateTimeFormatter.ofPattern("M/d EEEE", Locale.TAIWAN).withZone(zone)
    }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AirQualityCard(
            observation = state.airQuality,
            loading = state.airQualityLoading,
            unavailableReason = state.airQualityUnavailableReason,
        )

        ForecastSectionTitle(title = "逐時預報")
        when {
            state.areaForecastLoading -> LoadingForecastCard("正在取得近期預報")
            state.areaForecast == null -> EmptyForecastCard("此位置暫無近期鄉鎮預報")
            else -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.areaForecast.periods.forEach { period -> HourlyForecastCard(period, zone) }
            }
        }

        ForecastSectionTitle(title = "本週預報")
        when {
            state.weeklyForecastLoading -> LoadingForecastCard("正在取得本週預報")
            state.weeklyForecast == null -> EmptyForecastCard("此位置暫無一週鄉鎮預報")
            else -> {
                val periodsByDate = state.weeklyForecast.periods.groupBy {
                    it.startAt.atZone(zone).toLocalDate()
                }
                periodsByDate.forEach { (_, periods) ->
                    DailyForecastCard(
                        dateLabel = dayFormatter.format(periods.first().startAt),
                        periods = periods,
                        zone = zone,
                    )
                }
            }
        }

        Text(
            "預報來源：${state.weeklyForecast?.sourceId ?: state.areaForecast?.sourceId ?: "尚未取得"}。AQI 為環境部鄰近測站目前觀測，不是未來空品預報。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ForecastSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun AirQualityCard(
    observation: AirQualityObservation?,
    loading: Boolean,
    unavailableReason: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .58f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Air, null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            when {
                loading -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在取得鄰近測站 AQI")
                }
                observation != null -> {
                    Column(Modifier.weight(1f)) {
                        Text("目前空氣品質", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "AQI ${observation.aqi} · ${observation.status}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = aqiColor(observation.aqi),
                        )
                        Text(
                            buildString {
                                append(observation.stationName).append("測站")
                                observation.primaryPollutant?.let { append(" · 指標污染物 ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> Column {
                    Text("目前空氣品質", style = MaterialTheme.typography.labelLarge)
                    Text(unavailableReason ?: "AQI 暫無資料", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HourlyForecastCard(period: AreaForecastPeriod, zone: ZoneId) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    val description = period.weatherDescription.ifBlank { "天氣未定" }
    Surface(
        modifier = Modifier
            .width(112.dp)
            .semantics {
                contentDescription = "${timeFormatter.format(period.startAt)}，$description，${temperatureRange(period)}，降雨機率${period.precipitationProbabilityPercent ?: 0}百分比"
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(timeFormatter.format(period.startAt), fontWeight = FontWeight.Bold)
            Icon(forecastIcon(description, daytime = true), null, Modifier.size(32.dp), tint = forecastIconColor(description))
            Text(description, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            Text(temperatureRange(period), fontWeight = FontWeight.SemiBold)
            Text("降雨 ${period.precipitationProbabilityPercent?.let { "$it%" } ?: "—"}", color = MaterialTheme.colorScheme.primary)
            Text("濕度 ${period.relativeHumidityPercent?.let { "$it%" } ?: "—"}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DailyForecastCard(dateLabel: String, periods: List<AreaForecastPeriod>, zone: ZoneId) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(dateLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val day = periods.firstOrNull { it.startAt.atZone(zone).hour in 6..17 }
                val night = periods.firstOrNull { it !== day }
                DayPartForecast("白天", day, true, Modifier.weight(1f))
                DayPartForecast("晚上", night, false, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayPartForecast(
    label: String,
    period: AreaForecastPeriod?,
    daytime: Boolean,
    modifier: Modifier = Modifier,
) {
    val description = period?.weatherDescription?.ifBlank { "天氣未定" } ?: "尚無時段"
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .72f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (period == null) Icons.Default.Info else forecastIcon(description, daytime),
                null,
                Modifier.size(28.dp),
                tint = forecastIconColor(description),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(period?.let(::temperatureRange) ?: "溫度 —", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("降雨 ${period?.precipitationProbabilityPercent?.let { "$it%" } ?: "—"}", style = MaterialTheme.typography.labelMedium)
            Text("濕度 ${period?.relativeHumidityPercent?.let { "$it%" } ?: "—"}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LoadingForecastCard(label: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(label)
        }
    }
}

@Composable
private fun EmptyForecastCard(label: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(label, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun temperatureRange(period: AreaForecastPeriod): String = when {
    period.minimumTemperatureCelsius == null || period.maximumTemperatureCelsius == null -> "溫度 —"
    period.minimumTemperatureCelsius == period.maximumTemperatureCelsius -> "${period.minimumTemperatureCelsius}°"
    else -> "${period.minimumTemperatureCelsius}–${period.maximumTemperatureCelsius}°"
}

private fun forecastIcon(description: String, daytime: Boolean): ImageVector = when {
    "雷" in description -> Icons.Default.Thunderstorm
    "雪" in description -> Icons.Default.AcUnit
    "雨" in description -> Icons.Default.WaterDrop
    "晴" in description && !daytime -> Icons.Default.NightsStay
    "晴" in description -> Icons.Default.WbSunny
    "雲" in description || "陰" in description -> Icons.Default.Cloud
    else -> Icons.Default.Info
}

@Composable
private fun forecastIconColor(description: String): Color = when {
    "雷" in description -> MaterialTheme.colorScheme.tertiary
    "雨" in description -> MaterialTheme.colorScheme.primary
    "晴" in description -> Color(0xFFE6A400)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun aqiColor(aqi: Int): Color = when (aqi) {
    in 0..50 -> Color(0xFF14805E)
    in 51..100 -> Color(0xFF8A6A00)
    in 101..150 -> Color(0xFFC05A00)
    in 151..200 -> MaterialTheme.colorScheme.error
    else -> Color(0xFF8A2E88)
}
