package com.rton.howstheweather.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

@Composable
fun ForecastScreen(state: HomeUiState, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.of("Asia/Taipei") }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ForecastSectionTitle(title = "逐時預報")
        when {
            state.areaForecastLoading -> LoadingForecastCard("正在取得近期預報")
            state.areaForecast == null -> EmptyForecastCard("此位置暫無近期鄉鎮預報")
            else -> HourlyForecastTimeline(state.areaForecast.periods, zone)
        }

        ForecastSectionTitle(title = "本週預報")
        when {
            state.weeklyForecastLoading -> LoadingForecastCard("正在取得本週預報")
            state.weeklyForecast == null -> EmptyForecastCard("此位置暫無一週鄉鎮預報")
            else -> WeeklyForecastTimeline(state.weeklyForecast.periods, zone)
        }

        Text(
            "預報來源：${state.weeklyForecast?.sourceId ?: state.areaForecast?.sourceId ?: "尚未取得"}。AQI 為環境部鄰近測站目前觀測，不是未來空品預報。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AirQualityCard(
            observation = state.airQuality,
            loading = state.airQualityLoading,
            unavailableReason = state.airQualityUnavailableReason,
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
private fun HourlyForecastTimeline(periods: List<AreaForecastPeriod>, zone: ZoneId) {
    val cardWidth = 104.dp
    val cardGap = 4.dp
    val timelineWidth = cardWidth * periods.size + cardGap * max(0, periods.size - 1)

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Surface(
            modifier = Modifier.width(timelineWidth + 24.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                    periods.forEach { period -> HourlyForecastTop(period, zone, Modifier.width(cardWidth)) }
                }
                TemperatureCurve(
                    temperatures = periods.map(::midpointTemperature),
                    temperatureRanges = periods.map(::temperatureBounds),
                    itemWidth = cardWidth,
                    itemGap = cardGap,
                    modifier = Modifier.fillMaxWidth().height(104.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                    periods.forEach { period -> HourlyForecastBottom(period, Modifier.width(cardWidth)) }
                }
            }
        }
    }
}

@Composable
private fun HourlyForecastTop(period: AreaForecastPeriod, zone: ZoneId, modifier: Modifier = Modifier) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    val description = period.weatherDescription.ifBlank { "天氣未定" }
    Column(
        modifier = modifier
            .semantics {
                contentDescription = "${forecastDateLabel(period.startAt, zone)} ${timeFormatter.format(period.startAt)}，$description，${temperatureRange(period)}，降雨機率${period.precipitationProbabilityPercent ?: 0}百分比"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(timeFormatter.format(period.startAt), fontWeight = FontWeight.Bold)
        Text(forecastDateLabel(period.startAt, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(forecastIcon(description, daytime = true), null, Modifier.size(32.dp), tint = forecastIconColor(description))
        Text(description, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HourlyForecastBottom(period: AreaForecastPeriod, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RainChance(period.precipitationProbabilityPercent)
        Text("濕度 ${period.relativeHumidityPercent?.let { "$it%" } ?: "—"}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun WeeklyForecastTimeline(periods: List<AreaForecastPeriod>, zone: ZoneId) {
    val cardWidth = 148.dp
    val cardGap = 8.dp
    val dailyPeriods = periods.groupBy { it.startAt.atZone(zone).toLocalDate() }.values.toList()
    val timelineWidth = cardWidth * dailyPeriods.size + cardGap * max(0, dailyPeriods.size - 1)

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Surface(
            modifier = Modifier.width(timelineWidth + 24.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                    dailyPeriods.forEach { dayPeriods ->
                        WeeklyForecastTop(dayPeriods, zone, Modifier.width(cardWidth))
                    }
                }
                TemperatureCurve(
                    temperatures = dailyPeriods.map(::midpointTemperature),
                    temperatureRanges = dailyPeriods.map(::temperatureBounds),
                    itemWidth = cardWidth,
                    itemGap = cardGap,
                    modifier = Modifier.fillMaxWidth().height(104.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                    dailyPeriods.forEach { dayPeriods ->
                        WeeklyForecastBottom(dayPeriods, zone, Modifier.width(cardWidth))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyForecastTop(periods: List<AreaForecastPeriod>, zone: ZoneId, modifier: Modifier = Modifier) {
    val day = periods.firstOrNull { it.startAt.atZone(zone).hour in 6..17 } ?: periods.firstOrNull()
    val night = periods.firstOrNull { it !== day }
    val date = periods.firstOrNull()?.startAt
    Column(
        modifier = modifier
            .semantics {
                contentDescription = "${date?.let { forecastDateLabel(it, zone) } ?: "日期未定"}，${dailyTemperatureRange(periods)}"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(date?.let { forecastDateLabel(it, zone) } ?: "日期未定", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        WeeklyForecastWeather(day, true, Modifier.fillMaxWidth())
        WeeklyForecastWeather(night, false, Modifier.fillMaxWidth())
    }
}

@Composable
private fun WeeklyForecastWeather(period: AreaForecastPeriod?, daytime: Boolean, modifier: Modifier = Modifier) {
    val description = period?.weatherDescription?.ifBlank { "天氣未定" } ?: "尚無時段"
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(
            if (period == null) Icons.Default.Info else forecastIcon(description, daytime),
            contentDescription = description,
            modifier = Modifier.size(24.dp),
            tint = forecastIconColor(description),
        )
        Text(description, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WeeklyForecastBottom(periods: List<AreaForecastPeriod>, zone: ZoneId, modifier: Modifier = Modifier) {
    val day = periods.firstOrNull { it.startAt.atZone(zone).hour in 6..17 } ?: periods.firstOrNull()
    val night = periods.firstOrNull { it !== day }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WeeklyRainChance(day?.precipitationProbabilityPercent, Modifier.fillMaxWidth())
        WeeklyRainChance(night?.precipitationProbabilityPercent, Modifier.fillMaxWidth())
    }
}

@Composable
private fun WeeklyRainChance(chance: Int?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.WaterDrop, "降雨機率", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Text(chance?.let { "$it%" } ?: "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RainChance(chance: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(
            Icons.Default.WaterDrop,
            contentDescription = "降雨機率",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(chance?.let { "$it%" } ?: "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TemperatureCurve(
    temperatures: List<Float?>,
    temperatureRanges: List<TemperatureBounds?>,
    itemWidth: androidx.compose.ui.unit.Dp,
    itemGap: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val values = temperatures.filterNotNull()
    val lineColor = MaterialTheme.colorScheme.primary
    val cutoutColor = MaterialTheme.colorScheme.surfaceContainer
    val rangeFillColor = MaterialTheme.colorScheme.primary.copy(alpha = .14f)
    val rangeEdgeColor = MaterialTheme.colorScheme.primary.copy(alpha = .38f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.semantics { contentDescription = "各預報時段的最低至最高溫投影區間，以及平均溫度連續趨勢圖" }) {
        if (values.isEmpty()) return@Canvas

        val validRanges = temperatureRanges.filterNotNull()
        val actualMinimum = validRanges.minOfOrNull(TemperatureBounds::minimum) ?: values.min()
        val actualMaximum = validRanges.maxOfOrNull(TemperatureBounds::maximum) ?: values.max()
        val spread = (actualMaximum - actualMinimum).coerceAtLeast(2f)
        val minimum = actualMinimum - spread * .18f
        val maximum = actualMaximum + spread * .18f
        val top = 16.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val pointWidth = itemWidth.toPx()
        val step = pointWidth + itemGap.toPx()
        fun yFor(temperature: Float) = bottom - (temperature - minimum) / (maximum - minimum) * (bottom - top)

        fun drawRangeProjection(startIndex: Int, endIndex: Int) {
            val fillPath = Path()
            val upperPath = Path()
            val lowerPath = Path()
            for (index in startIndex..endIndex) {
                val range = temperatureRanges[index] ?: continue
                val point = Offset(pointWidth / 2f + index * step, yFor(range.maximum))
                if (index == startIndex) {
                    fillPath.moveTo(point.x, point.y)
                    upperPath.moveTo(point.x, point.y)
                } else {
                    fillPath.lineTo(point.x, point.y)
                    upperPath.lineTo(point.x, point.y)
                }
            }
            for (index in endIndex downTo startIndex) {
                val range = temperatureRanges[index] ?: continue
                val point = Offset(pointWidth / 2f + index * step, yFor(range.minimum))
                fillPath.lineTo(point.x, point.y)
                if (index == endIndex) lowerPath.moveTo(point.x, point.y) else lowerPath.lineTo(point.x, point.y)
            }
            fillPath.close()
            drawPath(fillPath, rangeFillColor)
            val edgeStyle = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(upperPath, rangeEdgeColor, style = edgeStyle)
            drawPath(lowerPath, rangeEdgeColor, style = edgeStyle)
        }

        var segmentStart: Int? = null
        temperatureRanges.forEachIndexed { index, range ->
            if (range != null && segmentStart == null) segmentStart = index
            val segmentEnds = segmentStart != null && (range == null || index == temperatureRanges.lastIndex)
            if (segmentEnds) {
                val endIndex = if (range == null) index - 1 else index
                drawRangeProjection(segmentStart!!, endIndex)
                segmentStart = null
            }
        }

        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = 12.dp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        temperatureRanges.forEachIndexed { index, range ->
            range ?: return@forEachIndexed
            val x = pointWidth / 2f + index * step
            drawContext.canvas.nativeCanvas.apply {
                drawText("${range.maximum.toInt()}°", x, yFor(range.maximum) - 4.dp.toPx(), labelPaint)
                drawText("${range.minimum.toInt()}°", x, yFor(range.minimum) + 13.dp.toPx(), labelPaint)
            }
        }
        var previous: Offset? = null

        temperatures.forEachIndexed { index, temperature ->
            if (temperature == null) {
                previous = null
            } else {
                val point = Offset(
                    x = pointWidth / 2f + index * step,
                    y = yFor(temperature),
                )
                previous?.let { drawLine(lineColor, it, point, 3.dp.toPx(), StrokeCap.Round) }
                drawCircle(cutoutColor, 7.dp.toPx(), point)
                drawCircle(lineColor, 4.dp.toPx(), point)
                previous = point
            }
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

private fun midpointTemperature(period: AreaForecastPeriod): Float? = when {
    period.minimumTemperatureCelsius == null || period.maximumTemperatureCelsius == null -> null
    else -> (period.minimumTemperatureCelsius + period.maximumTemperatureCelsius) / 2f
}

private fun midpointTemperature(periods: List<AreaForecastPeriod>): Float? =
    temperatureBounds(periods)?.let { (it.minimum + it.maximum) / 2f }

private data class TemperatureBounds(val minimum: Float, val maximum: Float)

private fun temperatureBounds(period: AreaForecastPeriod): TemperatureBounds? {
    val minimum = period.minimumTemperatureCelsius?.toFloat()
    val maximum = period.maximumTemperatureCelsius?.toFloat()
    return if (minimum != null && maximum != null) TemperatureBounds(minimum, maximum) else null
}

private fun temperatureBounds(periods: List<AreaForecastPeriod>): TemperatureBounds? {
    val minimum = periods.mapNotNull(AreaForecastPeriod::minimumTemperatureCelsius).minOrNull()?.toFloat()
    val maximum = periods.mapNotNull(AreaForecastPeriod::maximumTemperatureCelsius).maxOrNull()?.toFloat()
    return if (minimum != null && maximum != null) TemperatureBounds(minimum, maximum) else null
}

private fun dailyTemperatureRange(periods: List<AreaForecastPeriod>): String {
    val minimum = periods.mapNotNull(AreaForecastPeriod::minimumTemperatureCelsius).minOrNull()
    val maximum = periods.mapNotNull(AreaForecastPeriod::maximumTemperatureCelsius).maxOrNull()
    return when {
        minimum == null || maximum == null -> "溫度 —"
        minimum == maximum -> "$minimum°"
        else -> "$minimum–$maximum°"
    }
}

private fun forecastDateLabel(startAt: Instant, zone: ZoneId): String {
    return DateTimeFormatter.ofPattern("M/d（E）", Locale.TAIWAN).withZone(zone).format(startAt)
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
