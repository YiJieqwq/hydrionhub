package io.hydrion.hub.ui.settings

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsKeyboardDismissalTest {
    @Test fun onlyAVisibleKeyboardDisappearingInTheCurrentFocusSessionFoldsTheField() = runBlocking {
        val ime = MutableStateFlow(false)
        repeat(2) {
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) { ime.awaitSettingsKeyboardDismissal() }
            assertFalse(waiting.isCompleted)
            ime.value = true
            yield()
            assertFalse(waiting.isCompleted)
            ime.value = false
            yield()
            assertTrue(waiting.isCompleted)
        }
        ime.value = true
        val firstField = launch(start = CoroutineStart.UNDISPATCHED) { ime.awaitSettingsKeyboardDismissal() }
        firstField.cancelAndJoin()
        ime.value = false
        val newField = launch(start = CoroutineStart.UNDISPATCHED) { ime.awaitSettingsKeyboardDismissal() }
        assertFalse(newField.isCompleted)
        newField.cancelAndJoin()
    }
}
