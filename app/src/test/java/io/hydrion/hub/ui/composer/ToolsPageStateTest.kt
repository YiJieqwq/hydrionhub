package io.hydrion.hub.ui.composer

import androidx.compose.animation.core.FastOutSlowInEasing
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
        val onEnter = FastOutSlowInEasing.transform(enteredMillis.toFloat() / ToolsPageTravelMillis)
        assertEquals(onEnter, state.progress, .0001f)
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
        val onExit = FastOutSlowInEasing.transform(remainingPositionMillis.toFloat() / ToolsPageTravelMillis)
        assertEquals(onExit, state.progress, .0001f)

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
