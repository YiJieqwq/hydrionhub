package io.hydrion.hub.ui.composer

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import io.hydrion.hub.ui.ChatPanelMotionMillis
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerHintMotionTest {
    private class Frames {
        val clock = BroadcastFrameClock()
        private var nanos = 1L
        suspend fun tick(millis: Int = 0) {
            assertTrue(clock.hasAwaiters)
            nanos += millis * 1_000_000L
            clock.sendFrame(nanos)
            yield()
        }
    }

    @Test fun parkedSearchAndItsRowShareEverySampleIncludingTheReverse() = runBlocking {
        val frames = Frames()
        val motion = ComposerHintMotion(ComposerRowTarget.Browsing, false)
        val quarter = ChatPanelMotionMillis / 4
        val opening = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) {
            motion.moveTo(ComposerRowTarget.ParkedSearch, true)
        }
        frames.tick()
        for (i in 1..3) {
            frames.tick(quarter)
            assertEquals(i / 4f, motion.row, .0001f)
            assertEquals(motion.row, motion.search, 0f)
        }
        opening.cancelAndJoin()
        val closing = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) {
            motion.moveTo(ComposerRowTarget.Browsing, false)
        }
        for (i in 3 downTo 0) {
            frames.tick(if (i == 3) 0 else quarter)
            assertEquals(i / 4f, motion.row, .0001f)
            assertEquals(motion.row, motion.search, 0f)
        }
        closing.join()
    }

    @Test fun imeArrivalKeepsHintProgressWhileRowUsesItsNormalTiming() = runBlocking {
        val frames = Frames()
        val motion = ComposerHintMotion(ComposerRowTarget.Browsing, false)
        val opening = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) {
            motion.moveTo(ComposerRowTarget.ParkedSearch, true)
        }
        frames.tick()
        frames.tick(ChatPanelMotionMillis / 4)
        opening.cancelAndJoin()
        val withKeyboard = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) {
            motion.moveTo(ComposerRowTarget.Content, true)
        }
        frames.tick()
        assertEquals(.25f, motion.search, .0001f)
        frames.tick(33)
        assertEquals(1f, motion.row, 0f)
        assertEquals(.25f + 33f / ChatPanelMotionMillis, motion.search, .0001f)
        frames.tick(ChatPanelMotionMillis * 3 / 4 - 33)
        withKeyboard.join()
        assertEquals(1f, motion.search, 0f)
        motion.moveTo(ComposerRowTarget.HeldFolded, true)
        assertEquals(0f, motion.row, 0f)
    }

    @Test fun restoringAnExistingDraftAndDisablingAnimationsBothRespectTheirEndpoints() = runBlocking {
        val frames = Frames()
        val motion = ComposerHintMotion(ComposerRowTarget.Content, false)
        val opening = launch(frames.clock, start = CoroutineStart.UNDISPATCHED) {
            motion.moveTo(ComposerRowTarget.Content, true)
        }
        frames.tick()
        frames.tick(ChatPanelMotionMillis / 2)
        assertEquals(1f, motion.row, 0f)
        assertEquals(.5f, motion.search, .0001f)
        opening.cancelAndJoin()
        val disabled = object : MotionDurationScale { override val scaleFactor = 0f }
        val closing = launch(frames.clock + disabled, start = CoroutineStart.UNDISPATCHED) {
            motion.moveTo(ComposerRowTarget.Browsing, false)
        }
        frames.tick()
        closing.join()
        assertEquals(0f, motion.row, 0f)
        assertEquals(0f, motion.search, 0f)
    }
}
