package io.hydrion.hub.ui.composer

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftRowMotionTest {
    @Test fun ordinaryDraftRevealsIn33msAndReversesFromItsDisplayedValue() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = DraftRowMotion(false)
        var now = 1L
        suspend fun frame(ms: Int) {
            assertTrue(clock.hasAwaiters)
            now += 1_000_000L * ms
            clock.sendFrame(now)
            yield()
        }
        val open = launch(clock, start = CoroutineStart.UNDISPATCHED) { motion.moveTo(true, false) }
        frame(0)
        frame(16)
        val halfway = motion.fraction
        assertEquals(16 / 33f, halfway, .0001f)
        open.cancelAndJoin()
        val close = launch(clock, start = CoroutineStart.UNDISPATCHED) { motion.moveTo(false, false) }
        frame(0)
        assertEquals(halfway, motion.fraction, 0f)
        frame(33)
        close.join()
        assertEquals(0f, motion.fraction, 0f)
    }

    @Test fun heldFoldSnapsAndAlreadyPresentDraftSettlesWithDisabledAnimations() = runBlocking {
        val motion = DraftRowMotion(true)
        assertEquals(1f, motion.fraction, 0f)
        motion.moveTo(content = true, heldFolded = true)
        assertEquals(0f, motion.fraction, 0f)
        val disabled = object : MotionDurationScale { override val scaleFactor = 0f }
        val clock = BroadcastFrameClock()
        val open = launch(clock + disabled, start = CoroutineStart.UNDISPATCHED) { motion.moveTo(true, false) }
        clock.sendFrame(1L)
        open.join()
        assertEquals(1f, motion.fraction, 0f)
    }
}
