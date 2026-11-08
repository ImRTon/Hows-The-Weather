package com.rton.howstheweather

import com.rton.howstheweather.data.WeatherHistoryKind
import com.rton.howstheweather.ui.snapTimelineValue
import com.rton.howstheweather.ui.timelineValueForX
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimelineTest {
    @Test fun `playback resumes toward the next official frame anchor`() {
        assertEquals(-80, nextObservationFrameMinute(-90))
        assertEquals(-80, nextObservationFrameMinute(-85))
        assertEquals(-70, nextObservationFrameMinute(-80))
        assertEquals(0, nextObservationFrameMinute(-1))
        assertEquals(0, nextObservationFrameMinute(0))
    }

    @Test fun `playback requires the complete ninety minute sequence`() {
        assertFalse(hasCompleteHistoryForPlayback(WeatherHistoryKind.RADAR, frameCount = 8))
        assertTrue(hasCompleteHistoryForPlayback(WeatherHistoryKind.RADAR, frameCount = 9))
        assertFalse(hasCompleteHistoryForPlayback(WeatherHistoryKind.CLOUD, frameCount = 9))
        assertTrue(hasCompleteHistoryForPlayback(WeatherHistoryKind.CLOUD, frameCount = 10))
    }

    @Test fun `scrubbing stops loading only when the selected minute arrives`() {
        val now = Instant.parse("2026-07-20T04:00:00Z")
        val framesWithoutTarget = listOf(now, now.minusSeconds(80 * 60L))
        val framesWithTarget = framesWithoutTarget + now.minusSeconds(90 * 60L)

        assertFalse(isObservationMinuteAvailable(now, framesWithoutTarget, minute = -90))
        assertTrue(isObservationMinuteAvailable(now, framesWithTarget, minute = -90))
    }

    @Test fun `custom timeline scrubber snaps to discrete frame intervals and clamps bounds`() {
        val range = -90f..0f
        assertEquals(-90f, snapTimelineValue(-120f, range, stepCount = 9), 1e-4f)
        assertEquals(-90f, snapTimelineValue(-86f, range, stepCount = 9), 1e-4f)
        assertEquals(-80f, snapTimelineValue(-84f, range, stepCount = 9), 1e-4f)
        assertEquals(-30f, snapTimelineValue(-28f, range, stepCount = 9), 1e-4f)
        assertEquals(0f, snapTimelineValue(15f, range, stepCount = 9), 1e-4f)

        val mappedStart = timelineValueForX(x = 8f, widthPx = 200, horizontalInsetPx = 8f, valueRange = range)
        val mappedMid = timelineValueForX(x = 100f, widthPx = 200, horizontalInsetPx = 8f, valueRange = range)
        val mappedEnd = timelineValueForX(x = 192f, widthPx = 200, horizontalInsetPx = 8f, valueRange = range)
        assertEquals(-90f, mappedStart, 1e-4f)
        assertEquals(-45f, mappedMid, 1e-4f)
        assertEquals(0f, mappedEnd, 1e-4f)
    }
}
