package com.rton.howstheweather.ui

import com.rton.howstheweather.domain.PanelAnchor
import kotlin.math.abs

internal data class ResizablePanelBounds(
    val totalHeight: Float,
    val handleHeight: Float,
    val minDecisionHeight: Float,
    val maxDecisionHeight: Float,
    val maxHandleTouchOffset: Float,
) {
    fun clampDecisionHeight(height: Float): Float =
        height.coerceIn(minDecisionHeight, maxDecisionHeight)
}

internal fun calculateResizablePanelBounds(
    totalHeight: Float,
    preferredMinDecisionHeight: Float,
    preferredMinMapHeight: Float,
    preferredHandleHeight: Float,
    handleTouchHeight: Float,
): ResizablePanelBounds {
    val safeTotalHeight = totalHeight.nonNegativeFinite()
    val handleHeight = preferredHandleHeight.nonNegativeFinite().coerceAtMost(safeTotalHeight)
    val contentHeight = safeTotalHeight - handleHeight
    val minDecisionHeight = preferredMinDecisionHeight
        .nonNegativeFinite()
        .coerceAtMost(contentHeight)
    val preferredMaxDecisionHeight = contentHeight - preferredMinMapHeight.nonNegativeFinite()
    val maxDecisionHeight = preferredMaxDecisionHeight.coerceIn(
        minimumValue = minDecisionHeight,
        maximumValue = contentHeight,
    )

    return ResizablePanelBounds(
        totalHeight = safeTotalHeight,
        handleHeight = handleHeight,
        minDecisionHeight = minDecisionHeight,
        maxDecisionHeight = maxDecisionHeight,
        maxHandleTouchOffset = (safeTotalHeight - handleTouchHeight.nonNegativeFinite())
            .coerceAtLeast(0f),
    )
}

/**
 * Panel heights for each anchor, derived from the measured content instead of
 * screen fractions so every device wraps the same content without padding.
 */
internal data class PanelAnchorHeights(
    val map: Float,
    val balanced: Float,
    val decision: Float,
) {
    fun heightFor(anchor: PanelAnchor): Float = when (anchor) {
        PanelAnchor.MAP -> map
        PanelAnchor.BALANCED -> balanced
        PanelAnchor.DECISION -> decision
    }

    /** Ordered from the smallest to the largest decision panel. */
    fun entries(): List<Pair<PanelAnchor, Float>> = listOf(
        PanelAnchor.MAP to map,
        PanelAnchor.BALANCED to balanced,
        PanelAnchor.DECISION to decision,
    )

    /** 0 at the collapsed summary, 1 once the priority cards fully fit. */
    fun summaryProgress(height: Float): Float = progressBetween(height, map, balanced)

    /** 0 at the balanced anchor, 1 once all expanded content fits. */
    fun detailProgress(height: Float): Float = progressBetween(height, balanced, decision)
}

internal fun calculatePanelAnchorHeights(
    bounds: ResizablePanelBounds,
    collapsedHeight: Float,
    balancedContentHeight: Float,
    expandedContentHeight: Float,
): PanelAnchorHeights {
    val map = bounds.clampDecisionHeight(collapsedHeight.nonNegativeFinite())
    val balanced = bounds.clampDecisionHeight(balancedContentHeight.nonNegativeFinite())
        .coerceAtLeast(map)
    val decision = bounds.clampDecisionHeight(expandedContentHeight.nonNegativeFinite())
        .coerceAtLeast(balanced)
    return PanelAnchorHeights(map = map, balanced = balanced, decision = decision)
}

/**
 * Chooses where a released drag settles.
 *
 * A slow release snaps to the nearest anchor, so a small pull always springs
 * back. A fling past [velocityThreshold] always advances at least one anchor in
 * the fling direction and may skip further when the decay projection carries it
 * there, matching the momentum of the gesture.
 */
internal fun resolvePanelSettleAnchor(
    anchors: PanelAnchorHeights,
    currentHeight: Float,
    velocity: Float,
    projectedHeight: Float,
    velocityThreshold: Float,
): PanelAnchor {
    val entries = anchors.entries()
    val nearest = entries.minBy { abs(it.second - currentHeight) }.first
    if (!velocity.isFinite() || abs(velocity) < velocityThreshold) return nearest
    val forward = if (velocity > 0f) {
        entries.filter { it.second > currentHeight + SETTLE_EPSILON }
    } else {
        entries.filter { it.second < currentHeight - SETTLE_EPSILON }
    }
    if (forward.isEmpty()) return nearest
    val projection = if (projectedHeight.isFinite()) projectedHeight else currentHeight
    return forward.minBy { abs(it.second - projection) }.first
}

private const val SETTLE_EPSILON = 0.5f

private fun progressBetween(value: Float, start: Float, end: Float): Float =
    if (end - start <= SETTLE_EPSILON) {
        if (value >= end) 1f else 0f
    } else {
        ((value - start) / (end - start)).coerceIn(0f, 1f)
    }

private fun Float.nonNegativeFinite(): Float =
    if (isFinite() && this > 0f) this else 0f
