package io.hydrion.hub.ui.composer

import androidx.compose.runtime.BroadcastFrameClock
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

    @Test fun interruptedEntranceAndExitKeepTheCurrentDrawnPosition() = runBlocking {
        val frames = Frames()
        val state = ToolsPageState()
        assertFalse(state.active)
        assertFalse(state.interactive)
        assertEquals(0f, state.progress, 0f)
        state.open()
        val opening = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(true) }
        frames.tick()
        val enteredMillis = ToolsPageTravelMillis / 2
        val reversedMillis = enteredMillis / 2
        frames.tick(enteredMillis)
        val onEnter = state.progress
        assertEquals(.5f, onEnter, .001f)
        state.close()
        frames.tick(16)
        assertEquals(onEnter, state.progress, 0f)
        opening.cancelAndJoin()
        assertTrue(state.active)
        assertFalse(state.interactive)
        val closing = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(false) }
        frames.tick()
        assertEquals(onEnter, state.progress, .0001f)
        frames.tick(reversedMillis)
        val remainingPositionMillis = enteredMillis - reversedMillis
        val onExit = state.progress
        assertTrue("roughly a fifth of travel at a quarter of the clock", onExit in 0.18f..0.22f)

        state.open()
        frames.tick(16)
        assertEquals(onExit, state.progress, 0f)
        closing.cancelAndJoin()
        val reopen = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(true) }
        frames.tick()
        assertEquals(onExit, state.progress, .0001f)
        frames.tick(ToolsPageTravelMillis - remainingPositionMillis)
        reopen.join()
        assertTrue(state.interactive)
        assertEquals(1f, state.progress, 0f)
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

    @Test fun panelIsReleasedOnlyAtTheLastFrame() = runBlocking {
        val frames = Frames()
        val state = ToolsPageState()
        state.open()
        val opening = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(true) }
        frames.tick()
        frames.tick(ToolsPageTravelMillis)
        opening.join()
        state.open()
        state.close()
        assertFalse(state.interactive)
        val closing = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) { state.animateToTarget(false) }
        frames.tick()
        frames.tick(ToolsPageTravelMillis - 1)
        assertTrue(state.active)
        frames.tick(1)
        closing.join()
        assertFalse(state.active)
        assertEquals(0f, state.progress, 0f)
    }
}
