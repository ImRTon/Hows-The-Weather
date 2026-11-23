package com.rton.howstheweather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import kotlin.math.roundToInt

@Composable
internal fun QuantitativePrecipitationControls(
    state: HomeUiState,
    viewModel: HomeViewModel,
    locating: Boolean,
    onLocate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val legendHeight = remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bottomTouchOverhangPx = with(density) { QUANTITATIVE_BAR_TOUCH_OVERHANG.roundToPx() }
    Box(modifier) {
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
            topControlsHeightPx = { 0 },
            bottomControlsHeightPx = { legendHeight.intValue },
            locating = locating,
            onLocate = onLocate,
        )
    }
}

@Composable
internal fun QuantitativePrecipitationHeader(state: HomeUiState, grid: WeatherGrid?) {
    val index = state.quantitativeForecastIndex
    val title = when (grid?.unit) {
        WeatherUnit.MILLIMETERS_ONE_HOUR -> "未來 1 小時 · 累積預報"
        WeatherUnit.MILLIMETERS_PER_HOUR -> "即時雨率"
        else -> "未來 ${index * 12}–${(index + 1) * 12} 小時 · 累積預報"
    }
    val unitLabel = when (grid?.unit) {
        WeatherUnit.MILLIMETERS_ONE_HOUR -> "mm/1h"
        WeatherUnit.MILLIMETERS_PER_HOUR -> "mm/h"
        else -> "mm/12h"
    }
    val sampled = grid?.sample(state.target.coordinate)
    val amount = when {
        sampled != null -> "%.1f %s".format(sampled, unitLabel)
        grid != null -> "此位置無雨量資料"
        state.isUpdating -> "載入中"
        else -> "暫無資料"
    }
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sampled != null) {
                Icon(
                    Icons.Default.WaterDrop,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = colors.primary,
                )
            }
            Text(
                text = amount,
                style = typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = if (sampled != null) colors.primary else colors.onSurfaceVariant,
            )
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
            WeatherLegend(unit = WeatherUnit.MILLIMETERS_TWELVE_HOURS)
        }
    }
}

internal const val QUANTITATIVE_FORECAST_OPACITY = .78f
