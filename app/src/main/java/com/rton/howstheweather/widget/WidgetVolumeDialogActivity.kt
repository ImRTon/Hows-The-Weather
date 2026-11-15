package com.rton.howstheweather.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Horizontal iOS-style volume HUD launched when the user taps the media volume
 * bar on the desktop widget. Features detented "tik-tak" haptics on every step
 * and a tactile spring-loaded mute button on the right.
 */
class WidgetVolumeDialogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WidgetHaptics.performClick(this)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setContent {
            VolumeOverlayScreen(
                audioManager = audioManager,
                onVolumeUpdated = {
                    WeatherDesktopWidgetProvider.updateVolumeOnly(applicationContext)
                },
                onDismiss = {
                    WeatherDesktopWidgetProvider.updateVolumeOnly(applicationContext)
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, android.R.anim.fade_out)
                },
            )
        }
    }
}

private val GradientStart = Color(0xFFFF0844)
private val GradientCenter = Color(0xFFFF1E56)
private val GradientEnd = Color(0xFFFF3868)
private val HudBackgroundColor = Color(0xD9151922)
private val HudPressedColor = Color(0xF2202634)
private val HudBorderColor = Color(0x45FFFFFF)
private val ButtonTopHighlight = Color(0x75FFFFFF)

@Composable
private fun VolumeOverlayScreen(
    audioManager: AudioManager,
    onVolumeUpdated: () -> Unit,
    onDismiss: () -> Unit,
) {
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    // Use at least 25 haptic detents across the bar so dragging feels like a fine mechanical dial
    val hapticStepCount = remember(maxVolume) { max(maxVolume, 25) }

    var currentVolume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume))
    }
    var sliderFraction by remember {
        mutableFloatStateOf((currentVolume.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f))
    }
    var lastHapticTickStep by remember {
        mutableIntStateOf((sliderFraction * hapticStepCount).roundToInt())
    }
    var isDragging by remember { mutableStateOf(false) }
    var lastInteractionSeed by remember { mutableIntStateOf(0) }
    var previousNonZeroVolume by remember {
        mutableIntStateOf(currentVolume.takeIf { it > 0 } ?: (maxVolume / 3).coerceAtLeast(1))
    }

    val context = LocalContext.current
    val view = LocalView.current

    // Sync with hardware volume keys while open
    DisposableEffect(context, audioManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (!isDragging) {
                    val updated = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume)
                    currentVolume = updated
                    sliderFraction = (updated.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f)
                    lastHapticTickStep = (sliderFraction * hapticStepCount).roundToInt()
                    if (updated > 0) previousNonZeroVolume = updated
                    onVolumeUpdated()
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // Auto-dismiss after inactivity
    LaunchedEffect(isDragging, lastInteractionSeed) {
        if (!isDragging) {
            delay(2_800L)
            onDismiss()
        }
    }

    fun applyFraction(fraction: Float, triggerStepHaptics: Boolean = true) {
        val clamped = fraction.coerceIn(0f, 1f)
        sliderFraction = clamped

        val tickStep = (clamped * hapticStepCount).roundToInt()
        if (triggerStepHaptics && tickStep != lastHapticTickStep) {
            lastHapticTickStep = tickStep
            if (tickStep == 0 || tickStep == hapticStepCount) {
                WidgetHaptics.performButtonPress(context, view)
            } else {
                WidgetHaptics.performTick(context, view)
            }
        }

        val targetVolume = (clamped * maxVolume).roundToInt().coerceIn(0, maxVolume)
        if (targetVolume != currentVolume) {
            currentVolume = targetVolume
            if (targetVolume > 0) previousNonZeroVolume = targetVolume
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            }
            onVolumeUpdated()
        }
        lastInteractionSeed++
    }

    val dismissInteraction = remember { MutableInteractionSource() }
    val rowInteraction = remember { MutableInteractionSource() }
    val percentage = (sliderFraction * 100f).roundToInt().coerceIn(0, 100)
    val isMuted = currentVolume == 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = dismissInteraction,
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = rowInteraction,
                    indication = null,
                    onClick = {},
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Entire horizontal capsule IS the volume visualization & touch scrubber (iOS HUD style)
            IosHorizontalVolumePill(
                fraction = sliderFraction,
                percentage = percentage,
                isDragging = isDragging,
                onFractionChange = { next -> applyFraction(next, triggerStepHaptics = true) },
                onDragStateChange = { dragging ->
                    if (dragging && !isDragging) {
                        WidgetHaptics.performTick(context, view)
                    }
                    isDragging = dragging
                    lastInteractionSeed++
                },
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Tactile physical-style Mute/Unmute button on the RIGHT side
            TactileMuteButton(
                isMuted = isMuted,
                onToggle = {
                    WidgetHaptics.performButtonPress(context, view)
                    val nextVolume = if (currentVolume > 0) 0 else previousNonZeroVolume
                    applyFraction(nextVolume.toFloat() / maxVolume.toFloat(), triggerStepHaptics = false)
                },
                onPressDown = {
                    WidgetHaptics.performTick(context, view)
                },
            )
        }
    }
}

@Composable
private fun TactileMuteButton(
    isMuted: Boolean,
    onToggle: () -> Unit,
    onPressDown: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (isPressed) onPressDown()
    }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "muteButtonScale",
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (isPressed) 2.dp else 14.dp,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "muteButtonShadow",
    )
    val unmutedBg by animateColorAsState(
        targetValue = if (isPressed) HudPressedColor else HudBackgroundColor,
        label = "muteButtonBg",
    )

    val buttonBrush = remember(isMuted, unmutedBg) {
        if (isMuted) {
            Brush.linearGradient(
                colors = listOf(GradientStart, GradientCenter, GradientEnd),
                start = Offset(0f, 0f),
                end = Offset(160f, 160f),
            )
        } else {
            Brush.linearGradient(listOf(unmutedBg, unmutedBg))
        }
    }
    val borderBrush = remember(isPressed, isMuted) {
        Brush.verticalGradient(
            colors = listOf(
                if (isPressed) Color.White.copy(alpha = 0.9f) else ButtonTopHighlight,
                if (isMuted) GradientStart.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.15f),
            ),
        )
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(shadowElevation, CircleShape)
            .clip(CircleShape)
            .background(brush = buttonBrush, shape = CircleShape)
            .border(BorderStroke(if (isPressed) 2.dp else 1.5.dp, borderBrush), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isMuted) {
                Icons.AutoMirrored.Filled.VolumeOff
            } else {
                Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = if (isMuted) "Unmute media volume" else "Mute media volume",
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun IosHorizontalVolumePill(
    fraction: Float,
    percentage: Int,
    isDragging: Boolean,
    onFractionChange: (Float) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val pillShape = RoundedCornerShape(28.dp)
    val fillBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(GradientStart, GradientCenter, GradientEnd),
        )
    }
    val scaleY by animateFloatAsState(
        targetValue = if (isDragging) 1.04f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pillScaleY",
    )

    Box(
        modifier = modifier
            .height(56.dp)
            .graphicsLayer {
                this.scaleY = scaleY
            }
            .shadow(14.dp, pillShape)
            .clip(pillShape)
            .background(HudBackgroundColor)
            .border(BorderStroke(1.2.dp, HudBorderColor), pillShape)
            .onSizeChanged { size ->
                widthPx = size.width.toFloat().coerceAtLeast(1f)
            }
            .semantics {
                contentDescription = "Media volume slider"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                setProgress { target ->
                    onFractionChange(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        onDragStateChange(true)
                        onFractionChange((offset.x / widthPx).coerceIn(0f, 1f))
                        tryAwaitRelease()
                        onDragStateChange(false)
                    },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onDragStateChange(true)
                        onFractionChange((offset.x / widthPx).coerceIn(0f, 1f))
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onFractionChange((change.position.x / widthPx).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onDragStateChange(false) },
                    onDragCancel = { onDragStateChange(false) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Full-capsule gradient fill visualization
        Canvas(modifier = Modifier.fillMaxSize()) {
            val clamped = fraction.coerceIn(0f, 1f)
            val fillWidth = size.width * clamped
            if (fillWidth > 0f) {
                drawRect(
                    brush = fillBrush,
                    size = Size(fillWidth, size.height),
                )
            }
        }

        // Foreground icon + label + percentage inside the capsule
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    percentage == 0 -> Icons.AutoMirrored.Filled.VolumeOff
                    percentage < 35 -> Icons.AutoMirrored.Filled.VolumeMute
                    percentage < 70 -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Volume",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = "$percentage%",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
