package com.rton.howstheweather.ui

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

private fun Float.nonNegativeFinite(): Float =
    if (isFinite() && this > 0f) this else 0f
