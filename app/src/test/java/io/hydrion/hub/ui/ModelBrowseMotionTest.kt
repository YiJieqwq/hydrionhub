package io.hydrion.hub.ui

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import io.hydrion.hub.provider.ChatModelPickerState
import io.hydrion.hub.ui.composer.ToolsPageState
import io.hydrion.hub.ui.composer.ToolsPageTravelMillis
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBrowseMotionTest {
    @Test fun modelAndToolsPagesTravelThroughTheSameFramesInBothDirections() = runBlocking {
        val frames = BroadcastFrameClock()
        val tools = ToolsPageState()
        val picker = ChatModelPickerState()
        val hidden = mutableStateOf(false)
        val models = ModelBrowseMotion(picker, hidden)
        var time = 1L
        suspend fun frame(afterMillis: Int) {
            assertTrue(frames.hasAwaiters)
            time += afterMillis * 1_000_000L
            frames.sendFrame(time)
            yield()
            assertEquals(tools.progress, models.progress, 0.0001f)
        }
        fun animate(open: Boolean) = listOf(
            launch(frames, start = CoroutineStart.UNDISPATCHED) { tools.animateToTarget(open) },
            launch(frames, start = CoroutineStart.UNDISPATCHED) { models.travel(open, restoring = false) },
        )
        picker.open()
        picker.models("provider", startSearching = true)
        models.retain(ChatModelPickerState.Page.Search)
        tools.open()
        val entrance = animate(true)
        frame(0)
        frame(ToolsPageTravelMillis / 4)
        frame(ToolsPageTravelMillis / 4)
        val middle = models.progress
        assertEquals(.5f, middle, .001f)
        assertFalse(models.interactive)
        entrance.forEach { it.cancelAndJoin() }

        picker.open()
        tools.close()
        val retreat = animate(false)
        frame(0)
        assertEquals(middle, models.progress, .0001f)
        frame(ToolsPageTravelMillis / 4)
        val interrupted = models.progress
        assertTrue(interrupted in 0.18f..0.22f)
        assertTrue(models.active)
        retreat.forEach { it.cancelAndJoin() }

        picker.models("provider", startSearching = true)
        tools.open()
        val resume = animate(true)
        frame(0)
        assertEquals(interrupted, models.progress, .0001f)
        frame(ToolsPageTravelMillis * 3 / 4)
        resume.forEach { it.join() }
        assertTrue(models.interactive)
        hidden.value = true
        models.travel(open = true, restoring = true)
        assertEquals(0f, models.progress, 0f)
        assertFalse(models.interactive)
        assertTrue(models.active) // Its list can restore scroll before the new entrance.
    }
}
