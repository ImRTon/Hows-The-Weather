package com.rton.howstheweather.cloud

import android.graphics.Bitmap

interface CloudMotionEstimator {
    /** Returns extrapolated frames only. These frames are visual guidance, never decision input. */
    fun extrapolate(previous: Bitmap, current: Bitmap, frameCount: Int = 6): List<Bitmap>
}
