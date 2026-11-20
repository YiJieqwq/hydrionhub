package io.hydrion.hub.ui

import io.hydrion.hub.ui.ModelEditorReturnFrames.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEditorReturnFramesTest {
    @Test fun bothLayersLiftTogetherAndTheComposerLandsBeforeRestoringItsKeyboard() {
        for (fps in listOf(30, 60, 120)) {
            val frames = ModelEditorReturnFrames()
            val dt = 1000f / fps
            assertEquals(Event.None, frames.advance(dt, 180f, false, 0f, false))
            assertTrue(frames.contentProgress > 0f && frames.composerLandingProgress > frames.contentProgress)
            repeat(fps / 3) {
                assertEquals(Event.None, frames.advance(dt, 180f, false, 1f, true))
            }
            assertEquals(1f, frames.composerLandingProgress, 0f)
            assertTrue(frames.contentProgress < 1f)
            assertEquals(Event.Landed, frames.advance(dt, 180f, true, 0f, false))

            // IME frame with absent or reversing travel cannot rewind/reveal the message layer.
            for (travel in listOf(.2f, .6f, 0f, .85f, 1f)) {
                val before = frames.contentProgress
                assertEquals(Event.None, frames.advance(dt, 180f, true, travel, false))
                assertTrue(frames.contentProgress >= before && frames.contentProgress < 1f)
            }
            val finishFrames = List(fps / 10) { frames.advance(dt, 180f, true, 1f, true) }
            assertEquals(1, finishFrames.count { it == Event.Finished })
            assertEquals(1f, frames.contentProgress, 0f)
        }
    }

    @Test fun parkedDraftWithoutImeMustStillRestoreAndMeasureBeforeMenuAppears() {
        val frames = ModelEditorReturnFrames()
        assertEquals(Event.None, frames.advance(150f, 180f, true, 0f, false))
        assertEquals(1f, frames.composerLandingProgress, 0f)
        assertEquals(Event.Landed, frames.advance(16f, 180f, true, 0f, false))
        repeat(8) { assertEquals(Event.None, frames.advance(16f, 180f, true, 1f, false)) }
        assertTrue(frames.contentProgress < 1f)
        assertEquals(Event.Finished, frames.advance(16f, 180f, true, 1f, true))
    }

    @Test fun missingInsetAnimationDoesNotJumpStraightFromLandingToTheMenu() {
        val frames = ModelEditorReturnFrames()
        assertEquals(Event.None, frames.advance(180f, 180f, true, 0f, false))
        assertEquals(Event.Landed, frames.advance(10f, 180f, true, 0f, false))
        val before = frames.contentProgress
        assertEquals(Event.None, frames.advance(10f, 180f, true, 1f, true))
        assertTrue(frames.contentProgress > before && frames.contentProgress < 1f)
        val events = List(15) { frames.advance(10f, 180f, true, 1f, true) }
        assertEquals(1, events.count { it == Event.Finished })
    }

    @Test fun imeRefusingToHideOrShowCannotTrapTheVisitOrTriggerRepeatedRequests() {
        for (oldImeHidden in listOf(false, true)) {
            val frames = ModelEditorReturnFrames()
            val events = List(500) { frames.advance(16f, 180f, oldImeHidden, 0f, false) }
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
