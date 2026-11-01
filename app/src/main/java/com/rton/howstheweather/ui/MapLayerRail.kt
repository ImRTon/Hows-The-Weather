package com.rton.howstheweather.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rton.howstheweather.domain.PrimaryLayer
import com.rton.howstheweather.domain.RadarCoverage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Height of the rail between the decision card and the map: segment row plus handle strip. */
internal val LAYER_RAIL_HEIGHT = 58.dp

/** Segment row at the top of the rail; the panel drag pill sits in the strip below it. */
internal val LAYER_RAIL_SEGMENT_ROW_HEIGHT = 48.dp

/** Bottom strip of the rail that carries the panel drag pill below the segments. */
private val LAYER_RAIL_HANDLE_STRIP = LAYER_RAIL_HEIGHT - LAYER_RAIL_SEGMENT_ROW_HEIGHT

private val SEGMENT_ROW_HEIGHT = LAYER_RAIL_SEGMENT_ROW_HEIGHT
private val SEGMENT_TRACK_HEIGHT = 40.dp
private val SEGMENT_INDICATOR_INSET = 3.dp
private val SEGMENT_FLING_THRESHOLD = 400.dp // per second
private val SegmentSettleSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 520f)

/**
 * Picks the segment a horizontal swipe settles on. A slow release snaps to the
 * nearest segment; a fling advances at least one segment from where the gesture
 * started, in the fling direction.
 */
internal fun resolveLayerSwipeIndex(
    startIndex: Int,
    position: Float,
    velocity: Float,
    velocityThreshold: Float,
    count: Int,
): Int {
    if (count <= 0) return 0
    val last = count - 1
    val target = when {
        velocity > velocityThreshold -> maxOf(ceil(position).toInt(), startIndex + 1)
        velocity < -velocityThreshold -> minOf(floor(position).toInt(), startIndex - 1)
        else -> position.roundToInt()
    }
    return target.coerceIn(0, last)
}

/**
 * The divider between the decision card and the map, widened into a layer rail.
 * Vertical drags on the whole rail resize the panels (via [modifier]); the segment
 * row switches the mutually exclusive primary layer by tap or horizontal swipe,
 * with the indicator following the finger.
 */
@Composable
internal fun MapLayerRail(
    selected: PrimaryLayer,
    radarCoverage: RadarCoverage,
    radarLoading: Boolean,
    onSelect: (PrimaryLayer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val dividerLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
    Box(
        modifier.background(surface),
    ) {
        LayerSegments(
            selected = selected,
            radarCoverage = radarCoverage,
            radarLoading = radarLoading,
            onSelect = onSelect,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(SEGMENT_ROW_HEIGHT)
                .padding(horizontal = 16.dp),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerLine),
        )
    }
}

@Composable
private fun LayerSegments(
    selected: PrimaryLayer,
    radarCoverage: RadarCoverage,
    radarLoading: Boolean,
    onSelect: (PrimaryLayer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layers = PrimaryLayer.entries
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    BoxWithConstraints(modifier) {
        val segmentPx = constraints.maxWidth.toFloat() / layers.size
        val segmentWidth = with(density) { segmentPx.toDp() }
        val flingThreshold = with(density) { SEGMENT_FLING_THRESHOLD.toPx() }
        // Indicator position in segment units: 0 = first segment, size - 1 = last.
        val position = remember { mutableFloatStateOf(selected.ordinal.toFloat()) }
        var dragStartIndex by remember { mutableIntStateOf(selected.ordinal) }
        var dragging by remember { mutableStateOf(false) }
        var lastTickIndex by remember { mutableIntStateOf(selected.ordinal) }
        val dragState = rememberDraggableState { delta ->
            if (segmentPx > 0f) {
                position.floatValue = (position.floatValue + delta / segmentPx)
                    .coerceIn(0f, (layers.size - 1).toFloat())
                val nearest = position.floatValue.roundToInt()
                if (nearest != lastTickIndex) {
                    lastTickIndex = nearest
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
        }

        suspend fun settleTo(index: Int, velocity: Float) {
            dragState.drag(MutatePriority.Default) {
                animate(
                    initialValue = position.floatValue,
                    targetValue = index.toFloat(),
                    initialVelocity = velocity,
                    animationSpec = SegmentSettleSpring,
                ) { value, _ -> dragBy((value - position.floatValue) * segmentPx) }
            }
        }

        LaunchedEffect(selected, segmentPx) {
            lastTickIndex = selected.ordinal
            if (!dragging && abs(position.floatValue - selected.ordinal) > 0.001f) {
                settleTo(selected.ordinal, 0f)
            }
        }

        val trackColor = MaterialTheme.colorScheme.surfaceVariant
        val indicatorColor = MaterialTheme.colorScheme.primaryContainer
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(SEGMENT_TRACK_HEIGHT)
                .clip(CircleShape)
                .background(trackColor),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((position.floatValue * segmentPx).roundToInt(), 0) }
                .width(segmentWidth)
                .height(SEGMENT_TRACK_HEIGHT)
                .padding(SEGMENT_INDICATOR_INSET)
                .clip(CircleShape)
                .background(indicatorColor),
        )
        Row(
            Modifier
                .fillMaxSize()
                .selectableGroup()
                .semantics { contentDescription = "地圖圖層，可左右滑動切換" }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    reverseDirection = true,
                    onDragStarted = {
                        dragging = true
                        dragStartIndex = currentSelected.ordinal
                    },
                    onDragStopped = { velocity ->
                        val target = resolveLayerSwipeIndex(
                            startIndex = dragStartIndex,
                            position = position.floatValue,
                            velocity = velocity,
                            velocityThreshold = flingThreshold,
                            count = layers.size,
                        )
                        val layer = layers[target]
                        if (layer != currentSelected) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            currentOnSelect(layer)
                        }
                        try {
                            settleTo(target, if (segmentPx > 0f) velocity / segmentPx else 0f)
                        } finally {
                            dragging = false
                        }
                    },
                ),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            layers.forEach { layer ->
                // 1 under the indicator, 0 one segment away; drives a continuous colour blend.
                val emphasis = (1f - abs(position.floatValue - layer.ordinal)).coerceIn(0f, 1f)
                LayerSegment(
                    layer = layer,
                    label = when (layer) {
                        PrimaryLayer.RADAR_RAIN -> when (radarCoverage) {
                            RadarCoverage.WIDE -> "廣域雷達"
                            RadarCoverage.LOCAL -> "區域雷達"
                        }
                        PrimaryLayer.ONE_HOUR_RAIN -> "1 小時"
                        PrimaryLayer.CLOUD -> "雲層 β"
                    },
                    loading = layer == PrimaryLayer.RADAR_RAIN && radarLoading,
                    selected = layer == selected,
                    emphasis = emphasis,
                    onClick = {
                        if (layer != currentSelected) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            currentOnSelect(layer)
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun LayerSegment(
    layer: PrimaryLayer,
    label: String,
    loading: Boolean,
    selected: Boolean,
    emphasis: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    val active = MaterialTheme.colorScheme.onSurface
    val textColor = lerp(idle, active, emphasis)
    val iconColor = lerp(idle, MaterialTheme.colorScheme.primary, emphasis)
    Box(
        modifier = modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = null,
            role = Role.Tab,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SEGMENT_TRACK_HEIGHT)
                .padding(SEGMENT_INDICATOR_INSET)
                .clip(CircleShape)
                .indication(interaction, ripple(color = MaterialTheme.colorScheme.primary)),
        )
        Row(
            Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = iconColor,
                    trackColor = Color.Transparent,
                )
            } else {
                Icon(
                    imageVector = when (layer) {
                        PrimaryLayer.RADAR_RAIN -> Icons.Default.Radar
                        PrimaryLayer.ONE_HOUR_RAIN -> Icons.Default.WaterDrop
                        PrimaryLayer.CLOUD -> Icons.Default.Cloud
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconColor,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (emphasis > .5f) FontWeight.SemiBold else FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
