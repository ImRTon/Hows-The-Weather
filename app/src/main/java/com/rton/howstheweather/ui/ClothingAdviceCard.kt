package com.rton.howstheweather.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rton.howstheweather.domain.BottomWear
import com.rton.howstheweather.domain.ClothingAdvice
import com.rton.howstheweather.domain.ClothingAdvisor
import com.rton.howstheweather.domain.ClothingWindow
import com.rton.howstheweather.domain.HatAdvice
import com.rton.howstheweather.domain.HomeUiState
import com.rton.howstheweather.domain.OuterWear
import com.rton.howstheweather.domain.RainGear
import com.rton.howstheweather.domain.RainState
import com.rton.howstheweather.domain.TopWear
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

private val ColdEnd = Color(0xFF4F8DF0)
private val NeutralMid = Color(0xFF3FB6A8)
private val WarmEnd = Color(0xFFE8763A)
private val ThermalDarkTint = Color(0xFFF28B4E)
private val ThermalLightTint = Color(0xFFD96528)
private val RAINING_STATES = setOf(RainState.LIGHT, RainState.MODERATE, RainState.HEAVY, RainState.EXTREME)

private val WINDOW_CAPSULE_TOUCH_HEIGHT = 48.dp
private val WINDOW_CAPSULE_TRACK_HEIGHT = 26.dp
private val WINDOW_CAPSULE_INSET = 2.dp
private val WINDOW_CAPSULE_PILL_H_PAD = 6.dp
private val WINDOW_CAPSULE_TEXT_GAP = 9.dp
private val WINDOW_CAPSULE_FLING_THRESHOLD = 400.dp // per second
private const val CLOTHING_WINDOW_POPUP_TIMEOUT_MILLIS = 5_000L
private val WindowCapsuleSettleSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 520f)

@Composable
internal fun rememberClothingAdvice(state: HomeUiState, now: Instant, zone: ZoneId): ClothingAdvice? {
    val rainingNow = state.decision.state in RAINING_STATES
    return remember(
        state.areaForecast,
        state.weeklyForecast,
        state.clothingTolerance,
        state.clothingWindow,
        now,
        zone,
        rainingNow,
    ) {
        ClothingAdvisor.advise(
            forecast = state.areaForecast,
            weekly = state.weeklyForecast,
            tolerance = state.clothingTolerance,
            now = now,
            zone = zone,
            rainingNow = rainingNow,
            window = state.clothingWindow.duration,
        )
    }
}

@Composable
internal fun HeaderClothingAdvice(
    advice: ClothingAdvice?,
    selected: ClothingWindow,
    onSelect: (ClothingWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val popupState = rememberAutoCollapseState(CLOTHING_WINDOW_POPUP_TIMEOUT_MILLIS)
    Box(modifier) {
        Box(
            modifier = Modifier
                .height(WINDOW_CAPSULE_TOUCH_HEIGHT)
                .widthIn(min = WINDOW_CAPSULE_TOUCH_HEIGHT)
                .clip(CircleShape)
                .clickable(onClickLabel = "切換穿衣建議時段", role = Role.Button, onClick = popupState::expand)
                .semantics { stateDescription = "未來 ${selected.hours} 小時" },
            contentAlignment = Alignment.Center,
        ) {
            if (advice != null) {
                ClothingAdviceIcons(advice)
            } else {
                Icon(
                    Icons.Default.Checkroom,
                    contentDescription = "這個時段暫無穿衣建議",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (popupState.expanded) {
            val offset = with(LocalDensity.current) {
                IntOffset(x = 0, y = (WINDOW_CAPSULE_TOUCH_HEIGHT + 4.dp).roundToPx())
            }
            Popup(
                alignment = Alignment.TopEnd,
                offset = offset,
                onDismissRequest = popupState::collapse,
                properties = PopupProperties(focusable = true),
            ) {
                ClothingWindowCapsule(
                    selected = selected,
                    onSelect = { window ->
                        popupState.onAction()
                        onSelect(window)
                    },
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .trackAutoCollapseInteraction(popupState)
                        .onPreviewKeyEvent {
                            popupState.onAction()
                            false
                        },
                    floating = true,
                )
            }
        }
    }
}

@Composable
private fun ClothingAdviceIcons(advice: ClothingAdvice) {
    val colors = MaterialTheme.colorScheme
    val recommended = colors.primary
    val optional = colors.onSurfaceVariant
    val umbrellaLabel = rainGearLabel(advice.rainGear)
    val garmentSize = 22.dp
    Row(
        modifier = Modifier.clearAndSetSemantics {
            val umbrellaDescription = umbrellaLabel?.let { "$it，" }.orEmpty()
            contentDescription = "${umbrellaDescription}穿衣建議，上衣：${topLabel(advice.top)}，" +
                "外套：${outerLabel(advice.outer)}，褲子：${bottomLabel(advice.bottom)}，帽子：${hatLabel(advice.hat)}"
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (umbrellaLabel != null) {
            Icon(
                Icons.Default.Umbrella,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp).size(16.dp),
                tint = recommended,
            )
        }
        Canvas(Modifier.size(garmentSize)) { drawTop(advice.top, recommended) }
        Canvas(Modifier.size(garmentSize)) {
            drawOuter(advice.outer, if (advice.outer == OuterWear.NONE) optional else recommended)
        }
        Canvas(Modifier.size(garmentSize)) { drawBottom(advice.bottom, recommended) }
        Canvas(Modifier.size(garmentSize)) {
            drawHat(advice.hat, if (advice.hat == HatAdvice.NONE) optional else recommended)
        }
    }
}

/**
 * Three-segment sliding capsule for switching the clothing forecast horizon
 * (`6 小時` / `12 小時` / `24 小時`), sharing the home rail's pill physics.
 */
@Composable
internal fun ClothingWindowCapsule(
    selected: ClothingWindow,
    onSelect: (ClothingWindow) -> Unit,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
) {
    val windows = ClothingWindow.entries
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val flingThreshold = with(density) { WINDOW_CAPSULE_FLING_THRESHOLD.toPx() }
    var stepPx by remember { mutableFloatStateOf(with(density) { 44.dp.toPx() }) }

    val position = remember { mutableFloatStateOf(selected.ordinal.toFloat()) }
    var dragStartIndex by remember { mutableIntStateOf(selected.ordinal) }
    var dragging by remember { mutableStateOf(false) }
    var lastTickIndex by remember { mutableIntStateOf(selected.ordinal) }
    val dragState = rememberDraggableState { delta ->
        if (stepPx > 0f) {
            position.floatValue = (position.floatValue + delta / stepPx)
                .coerceIn(0f, (windows.size - 1).toFloat())
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
                animationSpec = WindowCapsuleSettleSpring,
            ) { value, _ -> dragBy((value - position.floatValue) * stepPx) }
        }
    }

    LaunchedEffect(selected) {
        lastTickIndex = selected.ordinal
        if (!dragging && abs(position.floatValue - selected.ordinal) > 0.001f) {
            settleTo(selected.ordinal, 0f)
        }
    }

    val colors = MaterialTheme.colorScheme
    val trackColor = colors.surfaceVariant
    val indicatorColor = if (floating) colors.surface else colors.primaryContainer
    val activeTextColor = if (floating) colors.primary else colors.onSurface
    val trackShape = if (floating) Modifier.shadow(3.dp, CircleShape) else Modifier.clip(CircleShape)
    Layout(
        modifier = modifier
            .selectableGroup()
            .semantics { contentDescription = "穿衣建議時段，可左右滑動切換" }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
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
                        count = windows.size,
                    )
                    val window = windows[target]
                    if (window != currentSelected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        currentOnSelect(window)
                    }
                    try {
                        settleTo(target, if (stepPx > 0f) velocity / stepPx else 0f)
                    } finally {
                        dragging = false
                    }
                },
            ),
        content = {
            Box(trackShape.background(trackColor))
            Box(Modifier.clip(CircleShape).background(indicatorColor))
            windows.forEach { window ->
                val emphasis = (1f - abs(position.floatValue - window.ordinal)).coerceIn(0f, 1f)
                ClothingWindowSegment(
                    window = window,
                    selected = window == selected,
                    emphasis = emphasis,
                    activeTextColor = activeTextColor,
                    onClick = {
                        if (window != currentSelected) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            currentOnSelect(window)
                        }
                    },
                )
            }
        },
    ) { measurables, constraints ->
        val touchHeightPx = WINDOW_CAPSULE_TOUCH_HEIGHT.roundToPx()
        val trackHeightPx = WINDOW_CAPSULE_TRACK_HEIGHT.roundToPx()
        val insetPx = WINDOW_CAPSULE_INSET.roundToPx()
        val pillHeightPx = trackHeightPx - insetPx * 2
        val overlapSpacingPx = (WINDOW_CAPSULE_TEXT_GAP - WINDOW_CAPSULE_PILL_H_PAD * 2).roundToPx()

        val segmentConstraints = constraints.copy(
            minWidth = 0,
            minHeight = touchHeightPx,
            maxHeight = touchHeightPx,
        )
        val segmentPlaceables = measurables.drop(2).map { it.measure(segmentConstraints) }
        val segmentX = IntArray(segmentPlaceables.size)
        var cursorX = insetPx
        segmentPlaceables.forEachIndexed { index, placeable ->
            segmentX[index] = cursorX
            cursorX += placeable.width + overlapSpacingPx
        }
        val totalWidth = segmentX.last() + segmentPlaceables.last().width + insetPx
        if (segmentPlaceables.size > 1) {
            stepPx = (segmentX.last() - segmentX.first()).toFloat() / (segmentPlaceables.size - 1)
        }

        val pos = position.floatValue.coerceIn(0f, (segmentPlaceables.size - 1).toFloat())
        val i0 = kotlin.math.floor(pos).toInt().coerceIn(0, segmentPlaceables.lastIndex)
        val i1 = kotlin.math.ceil(pos).toInt().coerceIn(0, segmentPlaceables.lastIndex)
        val frac = pos - i0
        val indX = (segmentX[i0] + (segmentX[i1] - segmentX[i0]) * frac).roundToInt()
        val indW = (segmentPlaceables[i0].width + (segmentPlaceables[i1].width - segmentPlaceables[i0].width) * frac)
            .roundToInt()

        val trackPlaceable = measurables[0].measure(
            androidx.compose.ui.unit.Constraints.fixed(totalWidth, trackHeightPx),
        )
        val indicatorPlaceable = measurables[1].measure(
            androidx.compose.ui.unit.Constraints.fixed(indW, pillHeightPx),
        )

        layout(totalWidth, touchHeightPx) {
            trackPlaceable.place(0, (touchHeightPx - trackHeightPx) / 2)
            indicatorPlaceable.place(indX, (touchHeightPx - pillHeightPx) / 2)
            segmentPlaceables.forEachIndexed { index, placeable ->
                placeable.place(segmentX[index], 0)
            }
        }
    }
}

@Composable
private fun ClothingWindowSegment(
    window: ClothingWindow,
    selected: Boolean,
    emphasis: Float,
    activeTextColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = lerp(idle, activeTextColor, emphasis)
    Box(
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { contentDescription = "未來 ${window.hours} 小時" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = (WINDOW_CAPSULE_TOUCH_HEIGHT - WINDOW_CAPSULE_TRACK_HEIGHT) / 2 + WINDOW_CAPSULE_INSET)
                .clip(CircleShape)
                .indication(interaction, ripple(color = MaterialTheme.colorScheme.primary)),
        )
        Text(
            text = window.label,
            modifier = Modifier.padding(horizontal = WINDOW_CAPSULE_PILL_H_PAD),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (emphasis > .5f) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ClothingAdviceCard(
    advice: ClothingAdvice,
    tolerance: Int,
    onToleranceChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceVariant,
    ) {
        Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
            Text(
                feelsLikeLine(advice, tolerance),
                modifier = Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GarmentTile("上衣", topLabel(advice.top), topDetail(advice), true, Modifier.weight(1f)) { tint ->
                    drawTop(advice.top, tint)
                }
                GarmentTile(
                    "外套",
                    outerLabel(advice.outer),
                    outerDetail(advice),
                    advice.outer != OuterWear.NONE,
                    Modifier.weight(1f),
                ) { tint -> drawOuter(advice.outer, tint) }
                GarmentTile("褲子", bottomLabel(advice.bottom), null, true, Modifier.weight(1f)) { tint ->
                    drawBottom(advice.bottom, tint)
                }
                GarmentTile(
                    "帽子",
                    hatLabel(advice.hat),
                    hatDetail(advice),
                    advice.hat != HatAdvice.NONE,
                    Modifier.weight(1f),
                ) { tint -> drawHat(advice.hat, tint) }
            }

            val notes = adviceNotes(advice)
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    Modifier.padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    notes.forEach { (icon, label) -> AdviceNote(icon, label) }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 18.dp),
                color = colors.outline.copy(alpha = .18f),
            )
            ToleranceControl(
                tolerance = tolerance,
                onToleranceChange = onToleranceChange,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            if (advice.usedEstimatedFeelsLike) {
                Text(
                    "部分體感依溫濕度估算",
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = .8f),
                )
            }
        }
    }
}

@Composable
private fun GarmentTile(
    category: String,
    label: String,
    detail: String?,
    needed: Boolean,
    modifier: Modifier = Modifier,
    glyph: DrawScope.(Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val emphasis by animateFloatAsState(if (needed) 1f else 0f, tween(260), label = "garmentEmphasis")
    val tint = lerp(colors.onSurfaceVariant.copy(alpha = .45f), colors.primary, emphasis)
    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = buildString {
                append(category).append("：").append(label)
                detail?.let { append("，").append(it) }
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface.copy(alpha = .35f + .3f * emphasis)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(42.dp)) { glyph(tint) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            category,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            label = "garmentLabel",
        ) { value ->
            Text(
                formatGarmentLabelForWrap(value),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (needed) FontWeight.Bold else FontWeight.Medium,
                color = if (needed) colors.onSurface else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun AdviceNote(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .6f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToleranceControl(
    tolerance: Int,
    onToleranceChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    // Follow the finger continuously; snap and persist one step at a time.
    var dragValue by remember { mutableFloatStateOf(tolerance.toFloat()) }
    var lastStep by remember(tolerance) { mutableIntStateOf(tolerance) }
    var dragging by remember { mutableStateOf(false) }
    val shown = if (dragging) dragValue else tolerance.toFloat()
    val range = ClothingAdvisor.TOLERANCE_MIN.toFloat()..ClothingAdvisor.TOLERANCE_MAX.toFloat()
    val fraction = (shown - range.start) / (range.endInclusive - range.start)
    val thumbColor = toleranceColor(fraction)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("怕冷", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Slider(
            value = shown,
            onValueChange = { value ->
                dragging = true
                dragValue = value
                val step = value.roundToInt()
                if (step != lastStep) {
                    lastStep = step
                    haptics.performHapticFeedback(
                        if (step == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick,
                    )
                    onToleranceChange(step)
                }
            },
            onValueChangeFinished = {
                dragging = false
                dragValue = lastStep.toFloat()
            },
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = "體感偏好"
                    stateDescription = toleranceLabel(tolerance)
                },
            interactionSource = remember { MutableInteractionSource() },
            thumb = {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(4.dp, thumbColor, CircleShape),
                )
            },
            track = {
                ToleranceTrack(modifier = Modifier.fillMaxWidth().height(20.dp))
            },
        )
        Text("怕熱", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ToleranceTrack(modifier: Modifier = Modifier) {
    // Ticks are punched out of one continuous track so nothing sits on top of it.
    val cutout = MaterialTheme.colorScheme.surfaceVariant
    val stepCount = ClothingAdvisor.TOLERANCE_MAX - ClothingAdvisor.TOLERANCE_MIN
    Canvas(modifier) {
        val height = 6.dp.toPx()
        val top = (size.height - height) / 2f
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(ColdEnd, NeutralMid, WarmEnd)),
            topLeft = Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, height),
            cornerRadius = CornerRadius(height / 2f),
            alpha = .55f,
        )
        for (index in 1 until stepCount) {
            val x = size.width * index / stepCount
            val isCentre = index == stepCount / 2
            drawCircle(
                color = cutout,
                radius = if (isCentre) 2.2.dp.toPx() else 1.2.dp.toPx(),
                center = Offset(x, size.height / 2f),
            )
        }
    }
}

private fun toleranceColor(fraction: Float): Color = if (fraction < .5f) {
    lerp(ColdEnd, NeutralMid, fraction * 2f)
} else {
    lerp(NeutralMid, WarmEnd, (fraction - .5f) * 2f)
}

internal fun toleranceLabel(value: Int): String = when {
    value == 0 -> "標準"
    value < 0 -> "怕冷 ${abs(value)} 級 · 多穿一點"
    else -> "怕熱 $value 級 · 少穿一點"
}

private fun feelsLikeLine(advice: ClothingAdvice, tolerance: Int): String = buildString {
    append("體感 ")
    if (advice.feelsLikeMinimum == advice.feelsLikeMaximum) {
        append(advice.feelsLikeMinimum).append("°C")
    } else {
        append(advice.feelsLikeMinimum).append("–").append(advice.feelsLikeMaximum).append("°C")
    }
    advice.maxRainChancePercent?.let { append(" · 降雨 ").append(it).append("%") }
    if (tolerance != 0) append(" · 已依偏好").append(if (tolerance > 0) "少穿 " else "多穿 ").append(abs(tolerance)).append(" 級")
}

internal fun topLabel(top: TopWear): String = when (top) {
    TopWear.SHORT_SLEEVE -> "短袖"
    TopWear.LIGHT_LONG_SLEEVE -> "薄長袖"
    TopWear.WARM_LAYER -> "長袖+發熱衣"
    TopWear.THERMAL_LAYER -> "厚長袖+發熱衣"
}

/**
 * Keeps each garment term intact around `+` when a narrow tile wraps to two lines,
 * so `"厚長袖+發熱衣"` wraps cleanly after `+` rather than splitting `"發熱衣"`.
 */
internal fun formatGarmentLabelForWrap(label: String): String {
    if ('+' !in label) return label
    return label
        .split('+')
        .joinToString(separator = "\u2060+\u200B") { part ->
            part.toCharArray().joinToString(separator = "\u2060")
        }
}

private fun topDetail(advice: ClothingAdvice): String? = when {
    advice.muggy -> "透氣排汗"
    else -> null
}

internal fun outerLabel(outer: OuterWear): String = when (outer) {
    OuterWear.NONE -> "不需要"
    OuterWear.CARRY_LIGHT -> "早晚薄外套"
    OuterWear.LIGHT -> "薄外套"
    OuterWear.REGULAR -> "一般外套"
    OuterWear.HEAVY -> "厚外套"
    OuterWear.DOWN -> "羽絨外套"
}

private fun outerDetail(advice: ClothingAdvice): String? = when {
    advice.outerWaterproof -> "選防水款"
    advice.outer == OuterWear.CARRY_LIGHT -> "帶著備用"
    else -> null
}

internal fun bottomLabel(bottom: BottomWear): String = when (bottom) {
    BottomWear.SHORTS -> "短褲"
    BottomWear.SHORTS_OR_TROUSERS -> "短褲或長褲"
    BottomWear.TROUSERS -> "長褲"
    BottomWear.WARM_TROUSERS -> "厚長褲"
}

internal fun hatLabel(hat: HatAdvice): String = when (hat) {
    HatAdvice.NONE -> "不需要"
    HatAdvice.SUN -> "遮陽帽"
    HatAdvice.SUN_WINDY -> "棒球帽"
    HatAdvice.BEANIE -> "毛帽"
}

private fun hatDetail(advice: ClothingAdvice): String? = when (advice.hat) {
    HatAdvice.SUN -> advice.uvIndex?.let { "紫外線 $it" }
    HatAdvice.SUN_WINDY -> "風大不戴寬帽簷"
    else -> null
}

private fun rainGearLabel(rainGear: RainGear): String? = when (rainGear) {
    RainGear.UMBRELLA -> "帶傘"
    RainGear.FOLDING_UMBRELLA -> "可帶折傘"
    RainGear.NONE -> null
}

private fun adviceNotes(advice: ClothingAdvice): List<Pair<ImageVector, String>> = buildList {
    rainGearLabel(advice.rainGear)?.let { add(Icons.Default.Umbrella to it) }
    if (advice.dampChill) add(Icons.Default.AcUnit to "濕冷，體感再低一些")
}

// Garment glyphs are drawn in a unit square so every tier shares one stroke weight.

private fun DrawScope.unit(x: Float, y: Float) = Offset(x * size.width, y * size.height)

private fun DrawScope.garment(path: Path, tint: Color, dashed: Boolean = false) {
    drawPath(path, tint.copy(alpha = tint.alpha * .18f))
    drawPath(
        path,
        tint,
        style = Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null,
        ),
    )
}

private fun DrawScope.line(tint: Color, vararg points: Pair<Float, Float>, width: Float = 1.6f) {
    val path = Path().apply {
        points.forEachIndexed { index, (x, y) ->
            val point = unit(x, y)
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
    }
    drawPath(path, tint, style = Stroke(width.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.upperBodyPath(
    longSleeves: Boolean,
    bodyHalfWidth: Float = .23f,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
): Path {
    val left = .5f - bodyHalfWidth
    val right = .5f + bodyHalfWidth
    val path = Path()
    fun mappedUnit(x: Float, y: Float) = unit(
        .5f + (x - .5f) * scale + offsetX,
        .5f + (y - .5f) * scale + offsetY,
    )
    fun to(x: Float, y: Float) = mappedUnit(x, y).let { path.lineTo(it.x, it.y) }
    mappedUnit(.38f, .1f).let { path.moveTo(it.x, it.y) }
    val neck = mappedUnit(.5f, .2f)
    val neckEnd = mappedUnit(.62f, .1f)
    path.quadraticTo(neck.x, neck.y + 2f * scale, neckEnd.x, neckEnd.y)
    to(.8f, .17f)
    if (longSleeves) {
        to(.96f, .84f); to(.83f, .87f); to(right, .4f)
    } else {
        to(.96f, .38f); to(.84f, .46f); to(right, .38f)
    }
    to(right, .92f)
    to(left, .92f)
    if (longSleeves) {
        to(left, .4f); to(.17f, .87f); to(.04f, .84f)
    } else {
        to(left, .38f); to(.16f, .46f); to(.04f, .38f)
    }
    to(.2f, .17f)
    path.close()
    return path
}

private fun DrawScope.transformedLine(
    tint: Color,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    vararg points: Pair<Float, Float>,
    width: Float = 1.6f,
) {
    val mapped = Array(points.size) { index ->
        val (x, y) = points[index]
        (.5f + (x - .5f) * scale + offsetX) to (.5f + (y - .5f) * scale + offsetY)
    }
    line(tint, *mapped, width = width)
}

private fun thermalLayerColor(frontTint: Color): Color =
    if (frontTint.luminance() > .45f) ThermalDarkTint else ThermalLightTint

private fun DrawScope.drawTop(top: TopWear, tint: Color) {
    when (top) {
        TopWear.SHORT_SLEEVE -> garment(upperBodyPath(longSleeves = false), tint)
        TopWear.LIGHT_LONG_SLEEVE -> garment(upperBodyPath(longSleeves = true, bodyHalfWidth = .21f), tint)
        TopWear.WARM_LAYER -> {
            val scale = .86f
            val frontX = -.05f
            val frontY = .05f
            val backPath = upperBodyPath(
                longSleeves = true,
                bodyHalfWidth = .21f,
                scale = scale,
                offsetX = .07f,
                offsetY = -.07f,
            )
            val frontPath = upperBodyPath(
                longSleeves = true,
                bodyHalfWidth = .21f,
                scale = scale,
                offsetX = frontX,
                offsetY = frontY,
            )
            val warmTint = thermalLayerColor(tint)
            clipPath(frontPath, ClipOp.Difference) {
                garment(backPath, warmTint)
            }
            garment(frontPath, tint)
            transformedLine(tint, scale, frontX, frontY, .32f to .84f, .68f to .84f)
        }
        TopWear.THERMAL_LAYER -> {
            val scale = .86f
            val frontX = -.05f
            val frontY = .05f
            val backPath = upperBodyPath(
                longSleeves = true,
                bodyHalfWidth = .21f,
                scale = scale,
                offsetX = .07f,
                offsetY = -.07f,
            )
            val frontPath = upperBodyPath(
                longSleeves = true,
                bodyHalfWidth = .24f,
                scale = scale,
                offsetX = frontX,
                offsetY = frontY,
            )
            val warmTint = thermalLayerColor(tint)
            clipPath(frontPath, ClipOp.Difference) {
                garment(backPath, warmTint)
            }
            garment(frontPath, tint)
            transformedLine(tint, scale, frontX, frontY, .29f to .84f, .71f to .84f)
            transformedLine(tint, scale, frontX, frontY, .29f to .75f, .71f to .75f)
            transformedLine(tint, scale, frontX, frontY, .11f to .78f, .2f to .8f)
            transformedLine(tint, scale, frontX, frontY, .89f to .78f, .8f to .8f)
        }
    }
}

private fun DrawScope.drawOuter(outer: OuterWear, tint: Color) {
    val dashed = outer == OuterWear.NONE || outer == OuterWear.CARRY_LIGHT
    garment(upperBodyPath(longSleeves = true, bodyHalfWidth = .25f), tint, dashed = dashed)
    if (outer == OuterWear.NONE) return
    line(tint, .5f to .2f, .5f to .92f)
    when (outer) {
        OuterWear.REGULAR -> {
            line(tint, .38f to .1f, .45f to .3f, .5f to .2f)
            line(tint, .62f to .1f, .55f to .3f, .5f to .2f)
        }
        OuterWear.HEAVY -> {
            val hood = Path().apply {
                val start = unit(.36f, .12f)
                val control = unit(.5f, -.12f)
                val end = unit(.64f, .12f)
                moveTo(start.x, start.y)
                quadraticTo(control.x, control.y, end.x, end.y)
            }
            drawPath(hood, tint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            line(tint, .33f to .62f, .43f to .62f)
            line(tint, .57f to .62f, .67f to .62f)
        }
        OuterWear.DOWN -> {
            listOf(.36f, .52f, .68f).forEach { y ->
                line(tint, .27f to y, .73f to y)
            }
            line(tint, .1f to .5f, .2f to .48f)
            line(tint, .9f to .5f, .8f to .48f)
        }
        else -> Unit
    }
}

private fun DrawScope.drawBottom(bottom: BottomWear, tint: Color) {
    val path = Path()
    fun move(x: Float, y: Float) = unit(x, y).let { path.moveTo(it.x, it.y) }
    fun to(x: Float, y: Float) = unit(x, y).let { path.lineTo(it.x, it.y) }
    when (bottom) {
        BottomWear.SHORTS -> {
            move(.2f, .2f); to(.8f, .2f); to(.88f, .66f); to(.57f, .7f); to(.5f, .44f)
            to(.43f, .7f); to(.12f, .66f); path.close()
            garment(path, tint)
            line(tint, .2f to .3f, .8f to .3f)
        }
        else -> {
            move(.24f, .06f); to(.76f, .06f); to(.82f, .95f); to(.57f, .95f); to(.5f, .36f)
            to(.43f, .95f); to(.18f, .95f); path.close()
            garment(path, tint)
            line(tint, .24f to .15f, .76f to .15f)
            if (bottom == BottomWear.SHORTS_OR_TROUSERS) {
                line(tint, .2f to .58f, .46f to .58f, width = 1.2f)
                line(tint, .54f to .58f, .8f to .58f, width = 1.2f)
            }
            if (bottom == BottomWear.WARM_TROUSERS) {
                line(tint, .27f to .86f, .4f to .86f)
                line(tint, .6f to .86f, .73f to .86f)
            }
        }
    }
}

private fun DrawScope.drawHat(hat: HatAdvice, tint: Color) {
    when (hat) {
        HatAdvice.BEANIE -> {
            val crown = Path().apply {
                addArc(Rect(unit(.18f, .3f), unit(.82f, .94f)), 180f, 180f)
                close()
            }
            garment(crown, tint)
            val cuff = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        Rect(unit(.14f, .58f), unit(.86f, .78f)),
                        CornerRadius(4.dp.toPx()),
                    ),
                )
            }
            garment(cuff, tint)
            drawCircle(tint, radius = size.minDimension * .08f, center = unit(.5f, .22f))
        }
        HatAdvice.SUN -> {
            val crown = Path().apply {
                val a = unit(.3f, .3f); moveTo(a.x, a.y)
                unit(.7f, .3f).let { lineTo(it.x, it.y) }
                unit(.76f, .64f).let { lineTo(it.x, it.y) }
                unit(.24f, .64f).let { lineTo(it.x, it.y) }
                close()
            }
            garment(crown, tint)
            val brim = Path().apply {
                addOval(Rect(unit(.04f, .58f), unit(.96f, .78f)))
            }
            garment(brim, tint)
        }
        HatAdvice.NONE, HatAdvice.SUN_WINDY -> {
            val cap = Path().apply {
                addArc(Rect(unit(.16f, .32f), unit(.72f, .88f)), 180f, 180f)
                unit(.97f, .64f).let { lineTo(it.x, it.y) }
                unit(.72f, .68f).let { lineTo(it.x, it.y) }
                close()
            }
            garment(cap, tint, dashed = hat == HatAdvice.NONE)
            if (hat != HatAdvice.NONE) drawCircle(tint, radius = size.minDimension * .04f, center = unit(.44f, .3f))
        }
    }
}
