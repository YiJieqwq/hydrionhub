package io.hydrion.hub.ui.composer

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsPageStateTest {
    private class Frames {
        val clock = BroadcastFrameClock()
        private var nanos = 1L

        suspend fun tick(millis: Int = 0) {
            assertTrue("animation must be waiting for this frame", clock.hasAwaiters)
            nanos += millis * 1_000_000L
            clock.sendFrame(nanos)
            yield()
        }
    }

    @Test fun interruptedEntranceAndExitKeepTheSameTimelineAndQuery() = runBlocking {
        val frames = Frames()
        val state = ToolsPageState()
        assertFalse(state.active)
        assertFalse(state.interactive)
        assertEquals(0f, state.progress, 0f)
        state.open()
        val typed = TextFieldValue("search", TextRange(2, 4))
        state.query = typed
        val opening = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(true) }
        frames.tick()
        val enteredMillis = ToolsPageTravelMillis / 2
        val reversedMillis = enteredMillis / 2
        frames.tick(enteredMillis)
        val onEnter = state.progress
        assertEquals(.5f, onEnter, .001f)
        state.close()
        opening.cancelAndJoin()
        assertTrue(state.active)
        assertFalse(state.interactive)
        assertEquals(typed, state.query)
        val closing = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(false) }
        frames.tick()
        assertEquals(onEnter, state.progress, .0001f)
        frames.tick(reversedMillis)
        val remainingPositionMillis = enteredMillis - reversedMillis
        val onExit = state.progress
        assertTrue("roughly a fifth of travel at a quarter of the clock", onExit in 0.18f..0.22f)

        state.open()
        closing.cancelAndJoin()
        val reopen = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(true) }
        frames.tick()
        assertEquals(onExit, state.progress, .0001f)
        assertEquals(typed, state.query)
        frames.tick(ToolsPageTravelMillis - remainingPositionMillis)
        reopen.join()
        assertTrue(state.interactive)
        assertEquals(1f, state.progress, 0f)
        assertEquals(typed, state.query)
    }

    @Test fun fullTravelKeepsTheSameVisibleMilestonesInBothDirections() = runBlocking {
        val frames = Frames()
        val state = ToolsPageState()
        for (open in listOf(true, false)) {
            if (open) state.open() else state.close()
            val travel = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(open) }
            frames.tick()
            for (expected in listOf(.197f, .5f, .803f, 1f)) {
                frames.tick(ToolsPageTravelMillis / 4)
                val travelled = if (open) state.progress else 1f - state.progress
                assertEquals(expected, travelled, .003f)
            }
            travel.join()
        }
    }

    @Test fun queryClearsOnlyOnCompletedExitAndFreshOpen() = runBlocking {
        val frames = Frames()
        val state = ToolsPageState()
        state.query = TextFieldValue("old")
        state.open()
        assertEquals(TextFieldValue(), state.query)
        val opening = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(true) }
        frames.tick()
        frames.tick(ToolsPageTravelMillis)
        opening.join()
        state.query = TextFieldValue("draft")
        state.open()
        assertEquals("draft", state.query.text)
        state.close()
        assertFalse(state.interactive)
        val closing = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(false) }
        frames.tick()
        frames.tick(ToolsPageTravelMillis - 1)
        assertTrue(state.active)
        assertEquals("draft", state.query.text)
        frames.tick(1)
        closing.join()
        assertFalse(state.active)
        assertEquals(TextFieldValue(), state.query)
        assertEquals(0f, state.progress, 0f)
    }
}
