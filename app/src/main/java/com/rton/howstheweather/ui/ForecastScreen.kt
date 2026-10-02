package com.rton.howstheweather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rton.howstheweather.domain.AirQualityObservation
import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.AreaForecastPeriod
import com.rton.howstheweather.domain.HomeUiState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private const val NOTABLE_RAIN_CHANCE_PERCENT = 30
private const val OUTLOOK_PERIOD_COUNT = 4

@Composable
fun ForecastScreen(state: HomeUiState, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.of("Asia/Taipei") }
    val now = remember(state.areaForecast, state.weeklyForecast) { Instant.now() }
    val upcomingPeriods = remember(state.areaForecast, now) {
        val periods = state.areaForecast?.periods.orEmpty()
        periods.filter { it.endAt.isAfter(now) }.ifEmpty { periods }
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.areaForecast != null && upcomingPeriods.isNotEmpty()) {
            val wind = state.currentWeather
            ForecastOverviewCard(
                forecast = state.areaForecast,
                upcomingPeriods = upcomingPeriods,
                now = now,
                zone = zone,
                windSpeedMetersPerSecond = wind?.windSpeedMetersPerSecond,
                windDirectionDegrees = wind?.windDirectionDegrees?.takeUnless { wind.windDirectionVariable },
            )
        }

        ForecastSection(title = "逐時預報", subtitle = "每 3 小時") {
            when {
                state.areaForecastLoading -> LoadingForecastCard("正在取得近期預報")
                state.areaForecast == null || upcomingPeriods.isEmpty() -> EmptyForecastCard(
                    state.areaForecastUnavailableReason ?: "此位置暫無近期鄉鎮預報",
                )
                else -> HourlyForecastTimeline(upcomingPeriods, now, zone)
            }
        }

        ForecastSection(title = "一週預報", subtitle = "日夜最高降雨機率") {
            when {
                state.weeklyForecastLoading -> LoadingForecastCard("正在取得本週預報")
                state.weeklyForecast == null -> EmptyForecastCard(
                    state.weeklyForecastUnavailableReason ?: "此位置暫無一週鄉鎮預報",
                )
                else -> WeeklyForecastList(state.weeklyForecast.periods, now, zone)
            }
        }

        ForecastSection(title = "空氣品質", subtitle = "鄰近測站即時觀測") {
            AirQualityCard(
                observation = state.airQuality,
                loading = state.airQualityLoading,
                unavailableReason = state.airQualityUnavailableReason,
                zone = zone,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ForecastSection(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content()
    }
}

@Composable
private fun ForecastOverviewCard(
    forecast: AreaForecast,
    upcomingPeriods: List<AreaForecastPeriod>,
    now: Instant,
    zone: ZoneId,
    windSpeedMetersPerSecond: Float?,
    windDirectionDegrees: Float?,
) {
    val current = upcomingPeriods.first()
    val description = current.weatherDescription.ifBlank { "天氣未定" }
    val daytime = isDaytime(current.startAt, zone)
    val scene = remember(description, daytime) { weatherSceneFor(description, daytime) }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    val outlook = rainOutlook(upcomingPeriods.take(OUTLOOK_PERIOD_COUNT), now, zone)
    val currentLabel = if (!current.startAt.isAfter(now)) "目前時段" else "下個時段"
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
    ) {
        Box(Modifier.background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.surfaceVariant)))) {
            if (scene != null) {
                ForecastWeatherAnimation(
                    scene = scene,
                    windSpeedMetersPerSecond = windSpeedMetersPerSecond,
                    windDirectionDegrees = windDirectionDegrees,
                    modifier = Modifier.matchParentSize(),
                )
            }
            OverviewCardContent(
                forecast = forecast,
                current = current,
                description = description,
                daytime = daytime,
                currentLabel = currentLabel,
                outlook = outlook,
                timeFormatter = timeFormatter,
            )
        }
    }
}

@Composable
private fun OverviewCardContent(
    forecast: AreaForecast,
    current: AreaForecastPeriod,
    description: String,
    daytime: Boolean,
    currentLabel: String,
    outlook: String,
    timeFormatter: DateTimeFormatter,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "${forecast.displayName} · $currentLabel ${timeFormatter.format(current.startAt)}–${timeFormatter.format(current.endAt)}",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface.copy(alpha = .72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.surface.copy(alpha = .55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    forecastIcon(description, daytime),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = forecastIconColor(description),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    temperatureRange(current),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewChip(
                icon = Icons.Default.WaterDrop,
                label = "降雨 ${current.precipitationProbabilityPercent?.let { "$it%" } ?: "—"}",
            )
            OverviewChip(
                icon = Icons.Default.Opacity,
                label = "濕度 ${current.relativeHumidityPercent?.let { "$it%" } ?: "—"}",
            )
        }
        Text(
            outlook,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
    }
}

@Composable
private fun OverviewChip(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AirQualityCard(
    observation: AirQualityObservation?,
    loading: Boolean,
    unavailableReason: String?,
    zone: ZoneId,
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(16.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在取得鄰近測站 AQI")
                }
                observation != null -> {
                    val color = aqiColor(observation.aqi)
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = .16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${observation.aqi}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = color,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "AQI · ${observation.status}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = color,
                        )
                        Text(
                            buildString {
                                append(observation.stationName).append("測站")
                                observation.primaryPollutant?.let { append(" · 指標污染物 ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "觀測 ${timeFormatter.format(observation.observedAt)} · 非空品預報",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    Icon(Icons.Default.Air, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(unavailableReason ?: "AQI 暫無資料", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HourlyForecastTimeline(periods: List<AreaForecastPeriod>, now: Instant, zone: ZoneId) {
    val itemWidth = 72.dp
    val itemGap = 0.dp
    val timelineWidth = itemWidth * periods.size + itemGap * max(0, periods.size - 1)
    val currentIndex = periods.indexOfFirst { !it.startAt.isAfter(now) && it.endAt.isAfter(now) }
    val cardColor = MaterialTheme.colorScheme.surfaceVariant
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = cardColor,
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            Box(Modifier.width(timelineWidth)) {
                Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.spacedBy(itemGap)) {
                    periods.indices.forEach { index ->
                        Box(
                            Modifier
                                .width(itemWidth)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (index == currentIndex) highlightColor else Color.Transparent),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(itemGap)) {
                        periods.forEachIndexed { index, period ->
                            HourlyForecastTop(
                                period = period,
                                previous = periods.getOrNull(index - 1),
                                isCurrent = index == currentIndex,
                                now = now,
                                zone = zone,
                                modifier = Modifier.width(itemWidth),
                            )
                        }
                    }
                    TemperatureCurve(
                        temperatures = periods.map(::midpointTemperature),
                        temperatureRanges = periods.map(::temperatureBounds),
                        itemWidth = itemWidth,
                        itemGap = itemGap,
                        cutoutColor = cardColor,
                        highlightIndex = currentIndex,
                        highlightCutoutColor = highlightColor.compositeOver(cardColor),
                        modifier = Modifier.fillMaxWidth().height(88.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(itemGap)) {
                        periods.forEach { period -> HourlyForecastBottom(period, Modifier.width(itemWidth)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HourlyForecastTop(
    period: AreaForecastPeriod,
    previous: AreaForecastPeriod?,
    isCurrent: Boolean,
    now: Instant,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    val description = period.weatherDescription.ifBlank { "天氣未定" }
    val date = period.startAt.atZone(zone).toLocalDate()
    val showDate = previous == null || previous.startAt.atZone(zone).toLocalDate() != date
    val dateLabel = relativeDayLabel(period.startAt, now, zone)
    Column(
        modifier = modifier
            .padding(top = 6.dp)
            .clearAndSetSemantics {
                contentDescription = "${forecastDateLabel(period.startAt, zone)} ${timeFormatter.format(period.startAt)}，$description，${temperatureRange(period)}，降雨機率${period.precipitationProbabilityPercent?.let { "${it}%" } ?: "未知"}，濕度${period.relativeHumidityPercent?.let { "${it}%" } ?: "未知"}"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (showDate) dateLabel else " ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (isCurrent) "現在" else timeFormatter.format(period.startAt),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            forecastIcon(description, isDaytime(period.startAt, zone)),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = forecastIconColor(description),
        )
    }
}

@Composable
private fun HourlyForecastBottom(period: AreaForecastPeriod, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(bottom = 6.dp).clearAndSetSemantics { },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RainChance(period.precipitationProbabilityPercent)
        Text(
            period.relativeHumidityPercent?.let { "濕 $it%" } ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RainChance(chance: Int?, modifier: Modifier = Modifier) {
    val notable = chance != null && chance >= NOTABLE_RAIN_CHANCE_PERCENT
    val color = if (notable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            Icons.Default.WaterDrop,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color.copy(alpha = if (notable) 1f else .6f),
        )
        Text(
            chance?.let { "$it%" } ?: "—",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (notable) FontWeight.Bold else FontWeight.Normal,
            color = color,
        )
    }
}

@Composable
private fun WeeklyForecastList(periods: List<AreaForecastPeriod>, now: Instant, zone: ZoneId) {
    val dailyPeriods = remember(periods) {
        periods.groupBy { it.startAt.atZone(zone).toLocalDate() }.values.toList()
    }
    val weekBounds = remember(dailyPeriods) {
        val bounds = dailyPeriods.mapNotNull(::temperatureBounds)
        if (bounds.isEmpty()) null else TemperatureBounds(bounds.minOf { it.minimum }, bounds.maxOf { it.maximum })
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            dailyPeriods.forEachIndexed { index, dayPeriods ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = .18f),
                    )
                }
                WeeklyForecastRow(dayPeriods, weekBounds, now, zone)
            }
        }
    }
}

@Composable
private fun WeeklyForecastRow(
    periods: List<AreaForecastPeriod>,
    weekBounds: TemperatureBounds?,
    now: Instant,
    zone: ZoneId,
) {
    val day = periods.firstOrNull { it.startAt.atZone(zone).hour in 6..17 } ?: periods.firstOrNull()
    val night = periods.firstOrNull { it !== day }
    val start = periods.first().startAt
    val dayDescription = day?.weatherDescription?.ifBlank { "天氣未定" } ?: "天氣未定"
    val nightDescription = night?.weatherDescription?.ifBlank { "天氣未定" }
    val chance = listOfNotNull(day?.precipitationProbabilityPercent, night?.precipitationProbabilityPercent).maxOrNull()
    val bounds = temperatureBounds(periods)
    val dateFormatter = remember { DateTimeFormatter.ofPattern("M/d").withZone(zone) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append(forecastDateLabel(start, zone)).append("，")
                    append(if (day != null && isDaytime(day.startAt, zone)) "白天" else "此時段").append(dayDescription)
                    day?.precipitationProbabilityPercent?.let { append("，降雨機率").append(it).append("%") }
                    if (night != null) {
                        append("；晚上").append(nightDescription)
                        night.precipitationProbabilityPercent?.let { append("，降雨機率").append(it).append("%") }
                    }
                    append("；").append(dailyTemperatureRange(periods))
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    relativeDayLabel(start, now, zone),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    dateFormatter.format(start),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                dayDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            forecastIcon(dayDescription, day?.let { isDaytime(it.startAt, zone) } ?: true),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = forecastIconColor(dayDescription),
        )
        RainChance(chance, Modifier.width(52.dp))
        Text(
            bounds?.let { "${it.minimum.toInt()}°" } ?: "—",
            modifier = Modifier.width(30.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        TemperatureRangeBar(
            bounds = bounds,
            weekBounds = weekBounds,
            modifier = Modifier.padding(horizontal = 8.dp).width(56.dp).height(6.dp),
        )
        Text(
            bounds?.let { "${it.maximum.toInt()}°" } ?: "—",
            modifier = Modifier.width(30.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TemperatureRangeBar(
    bounds: TemperatureBounds?,
    weekBounds: TemperatureBounds?,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = .22f)
    Canvas(modifier) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(trackColor, cornerRadius = radius)
        if (bounds == null || weekBounds == null) return@Canvas
        val span = (weekBounds.maximum - weekBounds.minimum).coerceAtLeast(1f)
        val startX = (bounds.minimum - weekBounds.minimum) / span * size.width
        val endX = max(startX + size.height, (bounds.maximum - weekBounds.minimum) / span * size.width)
            .coerceAtMost(size.width)
        val left = startX.coerceAtMost(endX - size.height).coerceAtLeast(0f)
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(temperatureColor(bounds.minimum), temperatureColor(bounds.maximum)),
                startX = left,
                endX = endX,
            ),
            topLeft = Offset(left, 0f),
            size = Size(endX - left, size.height),
            cornerRadius = radius,
        )
    }
}

@Composable
private fun TemperatureCurve(
    temperatures: List<Float?>,
    temperatureRanges: List<TemperatureBounds?>,
    itemWidth: Dp,
    itemGap: Dp,
    cutoutColor: Color,
    highlightIndex: Int,
    highlightCutoutColor: Color,
    modifier: Modifier = Modifier,
) {
    val values = temperatures.filterNotNull()
    val lineColor = MaterialTheme.colorScheme.primary
    val rangeFillColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
    val rangeEdgeColor = MaterialTheme.colorScheme.primary.copy(alpha = .32f)
    val labelColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.clearAndSetSemantics { }) {
        if (values.isEmpty()) return@Canvas

        val validRanges = temperatureRanges.filterNotNull()
        val actualMinimum = validRanges.minOfOrNull(TemperatureBounds::minimum) ?: values.min()
        val actualMaximum = validRanges.maxOfOrNull(TemperatureBounds::maximum) ?: values.max()
        val spread = (actualMaximum - actualMinimum).coerceAtLeast(2f)
        val minimum = actualMinimum - spread * .1f
        val maximum = actualMaximum + spread * .1f
        val top = 20.dp.toPx()
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
            val edgeStyle = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(upperPath, rangeEdgeColor, style = edgeStyle)
            drawPath(lowerPath, rangeEdgeColor, style = edgeStyle)
        }

        if (validRanges.any { it.maximum > it.minimum }) {
            var segmentStart: Int? = null
            temperatureRanges.forEachIndexed { index, range ->
                if (range != null && segmentStart == null) segmentStart = index
                val start = segmentStart
                if (start != null && (range == null || index == temperatureRanges.lastIndex)) {
                    val endIndex = if (range == null) index - 1 else index
                    drawRangeProjection(start, endIndex)
                    segmentStart = null
                }
            }
        }

        var previous: Offset? = null
        temperatures.forEachIndexed { index, temperature ->
            if (temperature == null) {
                previous = null
            } else {
                val point = Offset(pointWidth / 2f + index * step, yFor(temperature))
                previous?.let { drawLine(lineColor, it, point, 2.5.dp.toPx(), StrokeCap.Round) }
                previous = point
            }
        }
        temperatures.forEachIndexed { index, temperature ->
            temperature ?: return@forEachIndexed
            val point = Offset(pointWidth / 2f + index * step, yFor(temperature))
            drawCircle(if (index == highlightIndex) highlightCutoutColor else cutoutColor, 6.dp.toPx(), point)
            drawCircle(lineColor, if (index == highlightIndex) 4.5.dp.toPx() else 3.5.dp.toPx(), point)
        }

        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = 13.dp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        val minimumPaint = android.graphics.Paint(labelPaint).apply {
            color = labelColor.copy(alpha = .6f).toArgb()
            isFakeBoldText = false
        }
        temperatureRanges.forEachIndexed { index, range ->
            range ?: return@forEachIndexed
            val x = pointWidth / 2f + index * step
            drawContext.canvas.nativeCanvas.apply {
                if (range.maximum > range.minimum) {
                    drawText("${range.maximum.toInt()}°", x, yFor(range.maximum) - 8.dp.toPx(), labelPaint)
                    drawText("${range.minimum.toInt()}°", x, yFor(range.minimum) + 16.dp.toPx(), minimumPaint)
                } else {
                    drawText("${range.maximum.toInt()}°", x, yFor(range.maximum) - 10.dp.toPx(), labelPaint)
                }
            }
        }
    }
}

@Composable
private fun LoadingForecastCard(label: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(label)
        }
    }
}

@Composable
private fun EmptyForecastCard(label: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun rainOutlook(periods: List<AreaForecastPeriod>, now: Instant, zone: ZoneId): String {
    if (periods.isEmpty()) return "暫無近期降雨機率"
    val hours = Duration.between(periods.first().startAt, periods.last().endAt).toHours()
    val (peakPeriod, peakChance) = periods
        .mapNotNull { period -> period.precipitationProbabilityPercent?.let { period to it } }
        .maxByOrNull { it.second }
        ?: return "暫無近期降雨機率"
    if (peakChance < NOTABLE_RAIN_CHANCE_PERCENT) {
        return "近 $hours 小時不太會下雨，降雨機率最高 $peakChance%"
    }
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    val dayPrefix = if (peakPeriod.startAt.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) {
        ""
    } else {
        relativeDayLabel(peakPeriod.startAt, now, zone) + " "
    }
    val window = "$dayPrefix${timeFormatter.format(peakPeriod.startAt)}–${timeFormatter.format(peakPeriod.endAt)}"
    return "$window 最可能下雨，降雨機率 $peakChance%"
}

private fun temperatureRange(period: AreaForecastPeriod): String = when {
    period.minimumTemperatureCelsius == null || period.maximumTemperatureCelsius == null -> "—°"
    period.minimumTemperatureCelsius == period.maximumTemperatureCelsius -> "${period.minimumTemperatureCelsius}°"
    else -> "${period.minimumTemperatureCelsius}–${period.maximumTemperatureCelsius}°"
}

private fun midpointTemperature(period: AreaForecastPeriod): Float? = when {
    period.minimumTemperatureCelsius == null || period.maximumTemperatureCelsius == null -> null
    else -> (period.minimumTemperatureCelsius + period.maximumTemperatureCelsius) / 2f
}

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
        minimum == null || maximum == null -> "溫度未知"
        minimum == maximum -> "$minimum°"
        else -> "$minimum 至 $maximum°"
    }
}

private fun temperatureColor(celsius: Float): Color {
    val stops = listOf(
        8f to Color(0xFF5B8DEF),
        16f to Color(0xFF3FB6C6),
        24f to Color(0xFFF2B33D),
        32f to Color(0xFFE5602E),
    )
    if (celsius <= stops.first().first) return stops.first().second
    if (celsius >= stops.last().first) return stops.last().second
    val upperIndex = stops.indexOfFirst { it.first >= celsius }
    val (lowT, lowColor) = stops[upperIndex - 1]
    val (highT, highColor) = stops[upperIndex]
    return lerp(lowColor, highColor, (celsius - lowT) / (highT - lowT))
}

private fun isDaytime(instant: Instant, zone: ZoneId): Boolean = instant.atZone(zone).hour in 6..17

private fun relativeDayLabel(instant: Instant, now: Instant, zone: ZoneId): String {
    val date = instant.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> DateTimeFormatter.ofPattern("E", Locale.TAIWAN).format(date)
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
private fun aqiColor(aqi: Int): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (aqi) {
        in 0..50 -> if (dark) Color(0xFF4CC38A) else Color(0xFF14805E)
        in 51..100 -> if (dark) Color(0xFFE0C341) else Color(0xFF8A6A00)
        in 101..150 -> if (dark) Color(0xFFFF9A4D) else Color(0xFFC05A00)
        in 151..200 -> MaterialTheme.colorScheme.error
        else -> if (dark) Color(0xFFD99BD8) else Color(0xFF8A2E88)
    }
}
