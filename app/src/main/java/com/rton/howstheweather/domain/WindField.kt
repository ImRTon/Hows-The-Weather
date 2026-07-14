package com.rton.howstheweather.domain

import kotlin.math.cos
import kotlin.math.sin

data class WindComponents(
    val eastMetersPerSecond: Float,
    val northMetersPerSecond: Float,
)

/** Converts CWA's meteorological "from" direction into the direction particles travel. */
fun WindObservation.travelComponents(): WindComponents {
    val towardRadians = Math.toRadians((directionDegrees + 180f).toDouble())
    return WindComponents(
        eastMetersPerSecond = (speedMetersPerSecond * sin(towardRadians)).toFloat(),
        northMetersPerSecond = (speedMetersPerSecond * cos(towardRadians)).toFloat(),
    )
}
