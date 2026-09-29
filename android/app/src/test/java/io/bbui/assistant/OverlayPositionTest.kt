package io.bbui.assistant

import org.junit.Assert.*
import org.junit.Test

class OverlayPositionTest {
    @Test fun capsuleHas48DpHeightAndRoughlyHalfScreenWidthCappedAt240Dp() {
        val phone = OverlayPosition.Area(1080, 2400, OverlayPosition.Insets(top = 90, bottom = 75))
        val frame = OverlayPosition.place(phone, OverlayPosition.Anchor(), 3f)
        assertEquals(144, frame.height)
        assertTrue(frame.width in 540..720)
        assertEquals(1080 - 24 - frame.width, frame.x)
        assertTrue(frame.y >= 114); assertTrue(frame.y + frame.height <= 2400 - 75 - 24)
        val wide = OverlayPosition.place(OverlayPosition.Area(2400, 1080, OverlayPosition.Insets()), OverlayPosition.Anchor(), 3f)
        assertEquals(720, wide.width); assertEquals(144, wide.height)
    }
    @Test fun cutoutsAndBothEdgesClampDraggingWithoutCrossingSystemBars() {
        val area = OverlayPosition.Area(1200, 2400, OverlayPosition.Insets(80, 100, 50, 90))
        val frame = OverlayPosition.place(area, OverlayPosition.Anchor(false, 0f), 3f)
        assertEquals(104, frame.x); assertEquals(124, frame.y)
        val topLeft = OverlayPosition.drag(area, frame, -10000, -10000, 3f)
        assertEquals(frame, topLeft)
        val bottomRight = OverlayPosition.drag(area, frame, 10000, 10000, 3f)
        assertEquals(1200 - 50 - 24 - frame.width, bottomRight.x)
        assertEquals(2400 - 90 - 24 - frame.height, bottomRight.y)
        val anchored = OverlayPosition.anchor(area, bottomRight, 3f)
        assertTrue(anchored.rightEdge); assertEquals(1f, anchored.fraction, 0f)
        assertEquals(bottomRight, OverlayPosition.place(area, anchored, 3f))
    }
    @Test fun keyboardMovesCapsuleAboveImeWithoutChangingSavedPosition() {
        val full = OverlayPosition.Area(1080, 2400, OverlayPosition.Insets(top = 90, bottom = 75))
        val anchor = OverlayPosition.Anchor(false, 0.95f)
        val original = OverlayPosition.place(full, anchor, 3f)
        val keyboard = full.copy(keyboard = OverlayPosition.Insets(bottom = 1000))
        val moved = OverlayPosition.place(keyboard, anchor, 3f)
        assertTrue(moved.y < original.y)
        assertTrue(moved.y + moved.height <= 2400 - 1000 - 24)
        assertEquals(original, OverlayPosition.place(full, anchor, 3f))
        val clampedDrag = OverlayPosition.drag(keyboard, moved, moved.x, 2300, 3f)
        assertTrue(clampedDrag.y + clampedDrag.height <= 1376)
    }
    @Test fun rotationPreservesEdgeAndRelativeVerticalPositionAndHandlesInvalidPreference() {
        val portrait = OverlayPosition.Area(1080, 2400, OverlayPosition.Insets(top = 90, bottom = 75))
        val landscape = OverlayPosition.Area(2400, 1080, OverlayPosition.Insets(left = 90, right = 75))
        val anchor = OverlayPosition.Anchor(false, 0.5f)
        for (area in listOf(portrait, landscape)) {
            val frame = OverlayPosition.place(area, anchor, 3f)
            val restored = OverlayPosition.anchor(area, frame, 3f)
            assertFalse(restored.rightEdge); assertEquals(0.5f, restored.fraction, 0.002f)
        }
        val bad = OverlayPosition.place(portrait, OverlayPosition.Anchor(true, Float.NaN), 3f)
        assertEquals(OverlayPosition.place(portrait, OverlayPosition.Anchor(), 3f), bad)
    }
    @Test fun stopDoubleClickIsSuppressedAndSameSessionCanStopAgainAfterTerminalOrHidden() {
        val gate = OverlayStopGate()
        gate.update("session-a", true)
        assertTrue(gate.request(true)); assertFalse(gate.request(true))
        gate.update("session-a", true); assertFalse(gate.request(true))
        gate.update("session-a", false); assertFalse(gate.request(false))
        gate.update("session-a", true); assertTrue(gate.request(true))
        gate.update(null, false); gate.update("session-a", true); assertTrue(gate.request(true))
        gate.update("session-b", true); assertTrue(gate.request(true))
    }
}
