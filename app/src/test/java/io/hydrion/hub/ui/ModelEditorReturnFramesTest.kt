package io.hydrion.hub.ui

import io.hydrion.hub.ui.ModelEditorReturnFrames.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class ModelEditorReturnFramesTest {
    @Test fun bothLayersLiftTogetherAndTheComposerLandsBeforeRestoringItsKeyboard() {
        for (fps in listOf(30, 60, 120)) {
            val frames = ModelEditorReturnFrames()
            val dt = 1000f / fps
            assertEquals(Event.None, frames.advance(dt, 1f, false, 0f, false))
            assertTrue(frames.contentProgress > 0f && frames.composerLandingProgress > frames.contentProgress)
            repeat(ceil(ModelEditorMotionMillis / dt).toInt()) {
                assertEquals(Event.None, frames.advance(dt, 1f, false, 1f, true))
            }
            assertEquals(1f, frames.composerLandingProgress, 0f)
            assertTrue(frames.contentProgress < 1f)
            assertEquals(Event.Landed, frames.advance(dt, 1f, true, 0f, false))

            // IME frame with absent or reversing travel cannot rewind/reveal the message layer.
            for (travel in listOf(.2f, .6f, 0f, .85f, 1f)) {
                val before = frames.contentProgress
                assertEquals(Event.None, frames.advance(dt, 1f, true, travel, false))
                assertTrue(frames.contentProgress >= before && frames.contentProgress < 1f)
            }
            val finishFrames = List(ceil(ModelEditorMotionMillis / 3f / dt).toInt() + 1) { frames.advance(dt, 1f, true, 1f, true) }
            assertEquals(1, finishFrames.count { it == Event.Finished })
            assertEquals(1f, frames.contentProgress, 0f)
        }
    }

    @Test fun readyEditorCompletesBothLegsOnOneTimeline() {
        for (fps in listOf(30, 60, 120)) {
            val frames = ModelEditorReturnFrames()
            val dt = 1000f / fps
            val landing = ModelEditorMotionMillis * (2f / 3f)
            var landedAt = 0f
            var finishedAt = 0f
            repeat(ceil(ModelEditorMotionMillis / dt).toInt() + 4) { index ->
                val elapsed = (index + 1) * dt
                when (frames.advance(dt, 1f, true, 1f, true)) {
                    Event.Landed -> landedAt = elapsed
                    Event.Finished -> finishedAt = elapsed
                    else -> Unit
                }
            }
            // The bridge displays the landing pose for a frame before restoring the input target.
            assertTrue("landing at ${landedAt}ms (${fps}Hz)", landedAt in landing..(landing + 2f * dt + 1f))
            assertTrue("finished at ${finishedAt}ms (${fps}Hz)", finishedAt in (ModelEditorMotionMillis - 1f)..(ModelEditorMotionMillis + 4f * dt))
        }
    }

    @Test fun parkedDraftWithoutImeMustStillRestoreAndMeasureBeforeMenuAppears() {
        val frames = ModelEditorReturnFrames()
        assertEquals(Event.None, frames.advance(ModelEditorMotionMillis * .75f, 1f, true, 0f, false))
        assertEquals(1f, frames.composerLandingProgress, 0f)
        assertEquals(Event.Landed, frames.advance(16f, 1f, true, 0f, false))
        repeat(ceil(ModelEditorMotionMillis / 16f).toInt()) { assertEquals(Event.None, frames.advance(16f, 1f, true, 1f, false)) }
        assertTrue(frames.contentProgress < 1f)
        assertEquals(Event.Finished, frames.advance(16f, 1f, true, 1f, true))
    }

    @Test fun missingInsetAnimationDoesNotJumpStraightFromLandingToTheMenu() {
        val frames = ModelEditorReturnFrames()
        assertEquals(Event.None, frames.advance(ModelEditorMotionMillis.toFloat(), 1f, true, 0f, false))
        assertEquals(Event.Landed, frames.advance(10f, 1f, true, 0f, false))
        val before = frames.contentProgress
        assertEquals(Event.None, frames.advance(10f, 1f, true, 1f, true))
        assertTrue(frames.contentProgress > before && frames.contentProgress < 1f)
        val events = List(ceil(ModelEditorMotionMillis / 3f / 10f).toInt() + 1) { frames.advance(10f, 1f, true, 1f, true) }
        assertEquals(1, events.count { it == Event.Finished })
    }

    @Test fun imeRefusingToHideOrShowCannotTrapTheVisitOrTriggerRepeatedRequests() {
        for (oldImeHidden in listOf(false, true)) {
            val frames = ModelEditorReturnFrames()
            val events = List(500) { frames.advance(16f, 1f, oldImeHidden, 0f, false) }
                .filter { it != Event.None }
            assertEquals(if (oldImeHidden) listOf(Event.Landed, Event.ImeFallback, Event.Finished)
                else listOf(Event.ImeFallback, Event.Finished), events)
            assertEquals(1f, frames.contentProgress, 0f)
        }
    }

    @Test fun disabledAnimationsStillWaitForPlacedComposerAndSeparateTheRestoreAndMenuFrames() {
        val frames = ModelEditorReturnFrames()
        assertEquals(Event.None, frames.advance(16f, 0f, false, 1f, true))
        assertTrue(frames.contentProgress < 1f)
        assertEquals(Event.Landed, frames.advance(16f, 0f, true, 1f, true))
        assertEquals(Event.None, frames.advance(16f, 0f, true, .5f, false))
        assertEquals(Event.Finished, frames.advance(16f, 0f, true, 1f, true))
    }
}
