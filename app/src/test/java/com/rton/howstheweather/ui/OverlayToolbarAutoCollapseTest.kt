package com.rton.howstheweather.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayToolbarAutoCollapseTest {
    @Test
    fun `starts collapsed without running timer`() {
        val state = OverlayToolbarAutoCollapseState()

        assertFalse(state.expanded)
        assertFalse(state.shouldRunAutoCollapseTimer)
    }

    @Test
    fun `expanding starts auto collapse timer and timeout collapses when idle`() {
        val state = OverlayToolbarAutoCollapseState()

        state.expand()
        assertTrue(state.expanded)
        assertTrue(state.shouldRunAutoCollapseTimer)

        state.onTimeout()
        assertFalse(state.expanded)
        assertFalse(state.shouldRunAutoCollapseTimer)
    }

    @Test
    fun `active pointer pauses timer and release restarts countdown generation`() {
        val state = OverlayToolbarAutoCollapseState()
        state.expand()
        val initialGeneration = state.interactionGeneration

        state.onPointerActivity(pressed = true)
        assertTrue(state.expanded)
        assertFalse(state.shouldRunAutoCollapseTimer)
        assertEquals(initialGeneration + 1, state.interactionGeneration)

        // Timeout should be a no-op while finger is still pressing in the toolbar region.
        state.onTimeout()
        assertTrue(state.expanded)

        state.onPointerActivity(pressed = false)
        assertTrue(state.shouldRunAutoCollapseTimer)
        assertEquals(initialGeneration + 2, state.interactionGeneration)

        state.onTimeout()
        assertFalse(state.expanded)
    }

    @Test
    fun `toggle action bumps generation to reset auto collapse timer`() {
        val state = OverlayToolbarAutoCollapseState()
        state.expand()
        val generationAfterExpand = state.interactionGeneration

        state.onAction()
        assertEquals(generationAfterExpand + 1, state.interactionGeneration)
        assertTrue(state.expanded)
        assertTrue(state.shouldRunAutoCollapseTimer)
    }
}
