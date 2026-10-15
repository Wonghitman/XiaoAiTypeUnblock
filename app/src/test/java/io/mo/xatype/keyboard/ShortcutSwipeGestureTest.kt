package io.mo.xatype.keyboard

import org.junit.Assert.*
import org.junit.Test

class ShortcutSwipeGestureTest {
    @Test fun `tap and small hand jitter keep native input`() {
        val gesture = ShortcutSwipeGesture()
        assertFalse(gesture.move(2f, 4f))
        assertEquals(0f, gesture.progress, 0f)
        assertEquals(ShortcutSwipeGesture.Release.NATIVE, gesture.release())
    }
    @Test fun `downward distance continuously crossfades instead of switching at the trigger`() {
        val gesture = ShortcutSwipeGesture()
        val samples = listOf(7f, 12f, 18f, 24f, 30f, 36f, 42f, 48f).map { y ->
            assertTrue(gesture.move(0f, y))
            gesture.progress
        }
        assertTrue(samples.zipWithNext().all { (a, b) -> b > a })
        assertEquals(1f, samples.last(), 0f)
        assertTrue(samples.dropLast(1).all { it > 0f && it < 1f })
        assertEquals(ShortcutSwipeGesture.Release.ACTION, gesture.release())
    }
    @Test fun `sliding back lowers opacity and disarms the release`() {
        val gesture = ShortcutSwipeGesture()
        gesture.move(0f, 48f)
        assertTrue(gesture.armed)
        gesture.move(0f, 20f)
        assertTrue(gesture.progress in 0.1f..0.9f)
        assertFalse(gesture.armed)
        gesture.move(0f, 0f)
        assertEquals(0f, gesture.progress, 0f)
        assertEquals(ShortcutSwipeGesture.Release.CANCEL, gesture.release())
    }
    @Test fun `short downward drags cancel instead of typing a letter`() {
        val gesture = ShortcutSwipeGesture()
        gesture.move(0f, 12f)
        assertEquals(ShortcutSwipeGesture.Release.CANCEL, gesture.release())
    }
    @Test fun `horizontal and upward gestures stay with the native keyboard`() {
        for ((x, y) in listOf(20f to 1f, 0f to -20f)) {
            val gesture = ShortcutSwipeGesture()
            assertFalse(gesture.move(x, y))
            assertFalse(gesture.move(0f, 48f))
            assertEquals(ShortcutSwipeGesture.Release.NATIVE, gesture.release())
        }
    }
    @Test fun `moving sideways out of an active drag cancels the action`() {
        val gesture = ShortcutSwipeGesture()
        gesture.move(0f, 48f)
        gesture.move(60f, 48f)
        assertEquals(0f, gesture.progress, 0f)
        assertEquals(ShortcutSwipeGesture.Release.CANCEL, gesture.release())
    }
    @Test fun `cancel including multitouch can never execute on a later release`() {
        val gesture = ShortcutSwipeGesture()
        gesture.move(0f, 48f)
        gesture.cancel()
        gesture.move(0f, 80f)
        assertEquals(0f, gesture.progress, 0f)
        assertEquals(ShortcutSwipeGesture.Release.CANCEL, gesture.release())
    }
    @Test fun `release executes only once and ignores subsequent movement`() {
        val gesture = ShortcutSwipeGesture()
        gesture.move(0f, 48f)
        assertEquals(ShortcutSwipeGesture.Release.ACTION, gesture.release())
        assertEquals(ShortcutSwipeGesture.Release.CANCEL, gesture.release())
        assertFalse(gesture.move(0f, 60f))
    }
    @Test fun `invalid motion data cancels and overscroll clamps the blend`() {
        val gesture = ShortcutSwipeGesture()
        gesture.move(0f, 1000f)
        assertEquals(1f, gesture.progress, 0f)
        gesture.move(Float.NaN, 20f)
        assertEquals(ShortcutSwipeGesture.Release.CANCEL, gesture.release())
    }
}
