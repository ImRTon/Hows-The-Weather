@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.rton.howstheweather.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rton.howstheweather.HomeViewModel
import com.rton.howstheweather.domain.HomeUiState
import com.rton.howstheweather.domain.WeatherUnit
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun QuantitativePrecipitationControls(
    state: HomeUiState,
    viewModel: HomeViewModel,
    locating: Boolean,
    onLocate: () -> Unit,
    cameraZoom: () -> Float,
    modifier: Modifier = Modifier,
) {
    val grid = state.quantitativeForecastFrames.getOrNull(state.quantitativeForecastIndex)
    val streetLevel by remember { derivedStateOf { cameraZoom() >= 11f } }
    val summaryHeight = remember { mutableIntStateOf(0) }
    val legendHeight = remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bottomTouchOverhangPx = with(density) { QUANTITATIVE_BAR_TOUCH_OVERHANG.roundToPx() }
    Box(modifier) {
        QuantitativeRainSummary(
            state = state,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { summaryHeight.intValue = it.height }
                .padding(12.dp),
        )
        QuantitativeRainLegend(
            selectedIndex = state.quantitativeForecastIndex,
            onSelectedIndex = viewModel::setQuantitativeForecastIndex,
            enabled = state.quantitativeForecastFrames.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged {
                    legendHeight.intValue = (it.height - bottomTouchOverhangPx).coerceAtLeast(0)
                },
        )
        MapEndActions(
            topControlsHeightPx = { summaryHeight.intValue },
            bottomControlsHeightPx = { legendHeight.intValue },
            locating = locating,
            onLocate = onLocate,
        )
        AnimatedVisibility(
            visible = grid != null && streetLevel,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 108.dp),
        ) {
            Text(
                "原始格點約 ${grid?.resolutionKm?.let { "%.2f".format(it) } ?: "—"} km",
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .92f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun QuantitativeRainSummary(state: HomeUiState, modifier: Modifier = Modifier) {
    val grid = state.quantitativeForecastFrames.getOrNull(state.quantitativeForecastIndex)
    val formatter = remember {
        DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Taipei"))
    }
    val sampled = grid?.sample(state.target.coordinate)
    val amountLabel = when (grid?.unit) {
        WeatherUnit.MILLIMETERS_ONE_HOUR -> sampled?.let { "%.1f mm/1h".format(it) } ?: "此點無有效值"
        WeatherUnit.MILLIMETERS_PER_HOUR -> sampled?.let { "%.1f mm/h".format(it) } ?: "此點無有效值"
        WeatherUnit.MILLIMETERS_TWELVE_HOURS -> sampled?.let { "%.1f mm/12h".format(it) } ?: "此點無有效值"
        else -> "載入中"
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WaterDrop, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (grid?.unit) {
                        WeatherUnit.MILLIMETERS_ONE_HOUR -> "未來 1 小時 · 累積預報"
                        WeatherUnit.MILLIMETERS_PER_HOUR -> "即時雨率"
                        WeatherUnit.MILLIMETERS_TWELVE_HOURS -> {
                            val startHour = state.quantitativeForecastIndex * 12
                            "未來 $startHour–${startHour + 12} 小時 · 累積預報"
                        }
                        else -> "0–48 小時定量降水載入中"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    grid?.let { "${formatter.format(it.validAt)} · ${it.sourceId}" } ?: "等待數值格點",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(amountLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private val QUANTITATIVE_BAR_TOUCH_OVERHANG = 22.dp

@Composable
private fun QuantitativeRainLegend(
    selectedIndex: Int,
    onSelectedIndex: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val slots = listOf("0–12", "12–24", "24–36", "36–48 小時")
    val activeLabel = slots.getOrElse(selectedIndex) { slots.first() }
    DockedMapBottomBar(
        modifier = modifier,
        topTouchOverhang = QUANTITATIVE_BAR_TOUCH_OVERHANG,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 6.dp),
        ) {
            TimelineScrubber(
                value = selectedIndex.toFloat(),
                valueRange = 0f..3f,
                stepCount = 3,
                majorStepEvery = 1,
                enabled = enabled,
                visualTopInset = QUANTITATIVE_BAR_TOUCH_OVERHANG,
                onValueChange = { onSelectedIndex(it.roundToInt()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics {
                        contentDescription = "未來 0 到 48 小時定量降水預報時間軸"
                        stateDescription = activeLabel
                    },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                slots.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            WeatherLegend(
                unit = WeatherUnit.MILLIMETERS_TWELVE_HOURS,
                customLabels = listOf("0.1", "0.5", "2.5", "10", "25", "50", "100+"),
            )
        }
    }
}

internal const val QUANTITATIVE_FORECAST_OPACITY = .78f
